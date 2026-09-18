package com.example.streaming.rtmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RtmpChunkDemuxerTest {

    @Test
    fun testDemuxSingleChunkType0() {
        val demuxer = RtmpChunkDemuxer()
        val baos = ByteArrayOutputStream()

        val csid = 3
        val msgType = RtmpPacket.TYPE_COMMAND_AMF0
        val payload = "hello rtmp".toByteArray()
        val payloadLen = payload.size
        val timestamp = 100L
        val streamId = 1

        // Type 0 header
        baos.write(csid and 0x3F)
        baos.write((timestamp shr 16).toInt() and 0xFF)
        baos.write((timestamp shr 8).toInt() and 0xFF)
        baos.write(timestamp.toInt() and 0xFF)

        baos.write((payloadLen shr 16) and 0xFF)
        baos.write((payloadLen shr 8) and 0xFF)
        baos.write(payloadLen and 0xFF)

        baos.write(msgType.toInt())

        baos.write(streamId and 0xFF)
        baos.write((streamId shr 8) and 0xFF)
        baos.write((streamId shr 16) and 0xFF)
        baos.write((streamId shr 24) and 0xFF)

        baos.write(payload)

        val bais = ByteArrayInputStream(baos.toByteArray())
        val packet = demuxer.readPacket(bais)

        assertNotNull(packet)
        assertEquals(msgType, packet!!.messageType)
        assertEquals(csid, packet.chunkStreamId)
        assertEquals(streamId, packet.messageStreamId)
        assertEquals(timestamp, packet.timestamp)
        assertEquals("hello rtmp", String(packet.data))
    }

    @Test
    fun testDemuxMultiChunkContinuation() {
        val demuxer = RtmpChunkDemuxer()
        val baos = ByteArrayOutputStream()

        val csid = 4
        val msgType = RtmpPacket.TYPE_AUDIO
        val payload = ByteArray(200) { it.toByte() }
        val payloadLen = payload.size
        val timestamp = 200L
        val streamId = 1

        // Type 0 header for chunk 1 (default chunk size 128)
        baos.write(csid and 0x3F)
        baos.write((timestamp shr 16).toInt() and 0xFF)
        baos.write((timestamp shr 8).toInt() and 0xFF)
        baos.write(timestamp.toInt() and 0xFF)

        baos.write((payloadLen shr 16) and 0xFF)
        baos.write((payloadLen shr 8) and 0xFF)
        baos.write(payloadLen and 0xFF)

        baos.write(msgType.toInt())

        baos.write(streamId and 0xFF)
        baos.write((streamId shr 8) and 0xFF)
        baos.write((streamId shr 16) and 0xFF)
        baos.write((streamId shr 24) and 0xFF)

        // Write first 128 bytes
        baos.write(payload, 0, 128)

        // Chunk 2: Type 3 continuation (0xC0 | csid)
        baos.write(0xC0 or (csid and 0x3F))
        // Remaining 72 bytes
        baos.write(payload, 128, 72)

        val bais = ByteArrayInputStream(baos.toByteArray())
        val packet = demuxer.readPacket(bais)

        assertNotNull(packet)
        assertEquals(200, packet!!.data.size)
        assertEquals(msgType, packet.messageType)
        assertEquals(csid, packet.chunkStreamId)
        assertEquals(timestamp, packet.timestamp)
    }
}
