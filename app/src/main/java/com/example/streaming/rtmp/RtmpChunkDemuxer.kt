package com.example.streaming.rtmp

import java.io.EOFException
import java.io.InputStream

/**
 * Demuxes RTMP chunks from an [InputStream] into complete [RtmpPacket] messages.
 * Handles fmt 0, 1, 2, 3 chunk headers, extended timestamps, and dynamic server chunk size changes.
 */
class RtmpChunkDemuxer {

    var inChunkSize: Int = 128
        private set

    private class ChunkStreamState {
        var timestamp: Long = 0L
        var timestampDelta: Long = 0L
        var messageLength: Int = 0
        var messageType: Byte = 0
        var messageStreamId: Int = 0
        var hasExtendedTimestamp: Boolean = false
        var buffer: ByteArray = ByteArray(0)
        var bytesRead: Int = 0
    }

    private val streams = mutableMapOf<Int, ChunkStreamState>()

    /**
     * Reads chunks until a complete [RtmpPacket] is assembled, or returns null on EOF / error.
     */
    fun readPacket(inStream: InputStream): RtmpPacket? {
        while (true) {
            val b0 = inStream.read()
            if (b0 == -1) return null

            val fmt = (b0 shr 6) and 0x03
            var csid = b0 and 0x3F

            if (csid == 0) {
                val b1 = inStream.read()
                if (b1 == -1) throw EOFException("EOF in csid extended 1")
                csid = 64 + b1
            } else if (csid == 1) {
                val b1 = inStream.read()
                val b2 = inStream.read()
                if (b1 == -1 || b2 == -1) throw EOFException("EOF in csid extended 2")
                csid = 64 + b1 + (b2 shl 8)
            }

            val state = streams.getOrPut(csid) { ChunkStreamState() }

            when (fmt) {
                0 -> {
                    // Type 0 (11 bytes): timestamp(3), length(3), type(1), streamId(4 LE)
                    val header = ByteArray(11)
                    readFully(inStream, header, 0, 11)

                    var ts = ((header[0].toInt() and 0xFF) shl 16) or
                            ((header[1].toInt() and 0xFF) shl 8) or
                            (header[2].toInt() and 0xFF)

                    val len = ((header[3].toInt() and 0xFF) shl 16) or
                            ((header[4].toInt() and 0xFF) shl 8) or
                            (header[5].toInt() and 0xFF)

                    val type = header[6]

                    val streamId = (header[7].toInt() and 0xFF) or
                            ((header[8].toInt() and 0xFF) shl 8) or
                            ((header[9].toInt() and 0xFF) shl 16) or
                            ((header[10].toInt() and 0xFF) shl 24)

                    val hasExt = (ts == 0xFFFFFF)
                    val actualTs = if (hasExt) readInt32BigEndian(inStream).toLong() else ts.toLong()

                    state.hasExtendedTimestamp = hasExt
                    state.timestamp = actualTs
                    state.messageLength = len
                    state.messageType = type
                    state.messageStreamId = streamId
                    state.buffer = ByteArray(len)
                    state.bytesRead = 0
                }
                1 -> {
                    // Type 1 (7 bytes): delta(3), length(3), type(1)
                    val header = ByteArray(7)
                    readFully(inStream, header, 0, 7)

                    val delta = ((header[0].toInt() and 0xFF) shl 16) or
                            ((header[1].toInt() and 0xFF) shl 8) or
                            (header[2].toInt() and 0xFF)

                    val len = ((header[3].toInt() and 0xFF) shl 16) or
                            ((header[4].toInt() and 0xFF) shl 8) or
                            (header[5].toInt() and 0xFF)

                    val type = header[6]

                    val hasExt = (delta == 0xFFFFFF)
                    val actualDelta = if (hasExt) readInt32BigEndian(inStream).toLong() else delta.toLong()

                    state.hasExtendedTimestamp = hasExt
                    state.timestampDelta = actualDelta
                    state.timestamp += actualDelta
                    state.messageLength = len
                    state.messageType = type
                    state.buffer = ByteArray(len)
                    state.bytesRead = 0
                }
                2 -> {
                    // Type 2 (3 bytes): delta(3)
                    val header = ByteArray(3)
                    readFully(inStream, header, 0, 3)

                    val delta = ((header[0].toInt() and 0xFF) shl 16) or
                            ((header[1].toInt() and 0xFF) shl 8) or
                            (header[2].toInt() and 0xFF)

                    val hasExt = (delta == 0xFFFFFF)
                    val actualDelta = if (hasExt) readInt32BigEndian(inStream).toLong() else delta.toLong()

                    state.hasExtendedTimestamp = hasExt
                    state.timestampDelta = actualDelta
                    state.timestamp += actualDelta
                    state.buffer = ByteArray(state.messageLength)
                    state.bytesRead = 0
                }
                3 -> {
                    // Type 3 (0 bytes continuation)
                    if (state.bytesRead == 0 && state.hasExtendedTimestamp) {
                        readInt32BigEndian(inStream) // consume extended timestamp
                    }
                }
            }

            val remaining = state.messageLength - state.bytesRead
            val chunkSizeToRead = Math.min(remaining, inChunkSize)
            if (chunkSizeToRead > 0) {
                readFully(inStream, state.buffer, state.bytesRead, chunkSizeToRead)
                state.bytesRead += chunkSizeToRead
            }

            if (state.bytesRead >= state.messageLength) {
                val packet = RtmpPacket(
                    messageType = state.messageType,
                    chunkStreamId = csid,
                    messageStreamId = state.messageStreamId,
                    timestamp = state.timestamp,
                    data = state.buffer
                )

                // Handle Set Chunk Size internally
                if (packet.messageType == RtmpPacket.TYPE_SET_CHUNK_SIZE && packet.data.size >= 4) {
                    val newSize = ((packet.data[0].toInt() and 0x7F) shl 24) or
                            ((packet.data[1].toInt() and 0xFF) shl 16) or
                            ((packet.data[2].toInt() and 0xFF) shl 8) or
                            (packet.data[3].toInt() and 0xFF)
                    if (newSize > 0) {
                        inChunkSize = newSize
                    }
                }

                state.bytesRead = 0
                return packet
            }
        }
    }

    private fun readInt32BigEndian(inStream: InputStream): Int {
        val b0 = inStream.read()
        val b1 = inStream.read()
        val b2 = inStream.read()
        val b3 = inStream.read()
        if (b0 == -1 || b1 == -1 || b2 == -1 || b3 == -1) {
            throw EOFException("EOF reading 32-bit int")
        }
        return ((b0 and 0xFF) shl 24) or
                ((b1 and 0xFF) shl 16) or
                ((b2 and 0xFF) shl 8) or
                (b3 and 0xFF)
    }

    private fun readFully(inStream: InputStream, target: ByteArray, offset: Int, length: Int) {
        var total = 0
        while (total < length) {
            val r = inStream.read(target, offset + total, length - total)
            if (r < 0) throw EOFException("Unexpected EOF reading RTMP chunk payload")
            total += r
        }
    }
}
