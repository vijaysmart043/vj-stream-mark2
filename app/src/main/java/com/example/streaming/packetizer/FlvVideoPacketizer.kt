package com.example.streaming.packetizer

import com.example.streaming.encoder.EncodedVideoFrame
import com.example.streaming.encoder.H264NalParser
import java.io.ByteArrayOutputStream

/**
 * High-performance, zero-external-dependency H.264 -> FLV video packetizer.
 *
 * Responsibilities:
 * - Converts Annex-B formatted H.264 streams (00 00 01 or 00 00 00 01) into AVCC (4-byte length prefixed) format.
 * - Extracts and stores Sequence Parameter Sets (SPS) and Picture Parameter Sets (PPS).
 * - Constructs standard FLV AVC Sequence Headers (AVCDecoderConfigurationRecord per ISO/IEC 14496-15).
 * - Constructs standard FLV AVC NALU video packets for IDR (keyframe) and non-IDR (P/inter) frames.
 * - Preserves monotonic frame presentation timestamps in milliseconds.
 * - Enforces zero composition-time offset for low-latency live streaming.
 */
class FlvVideoPacketizer {

    private var spsBytes: ByteArray? = null
    private var ppsBytes: ByteArray? = null

    /**
     * Whether the AVC sequence header has been emitted for the active stream.
     */
    var hasEmittedSequenceHeader: Boolean = false
        private set

    /**
     * Updates SPS and PPS configuration and clears the emitted sequence header flag
     * if the parameters have changed.
     */
    @Synchronized
    fun setSpsPps(sps: ByteArray, pps: ByteArray) {
        val cleanSps = H264NalParser.stripStartCode(sps)
        val cleanPps = H264NalParser.stripStartCode(pps)

        if (!cleanSps.contentEquals(spsBytes) || !cleanPps.contentEquals(ppsBytes)) {
            this.spsBytes = cleanSps
            this.ppsBytes = cleanPps
            this.hasEmittedSequenceHeader = false
        }
    }

    /**
     * Returns true if both SPS and PPS have been received.
     */
    val hasConfiguration: Boolean
        get() = spsBytes != null && ppsBytes != null

    /**
     * Builds an AVC Sequence Header containing the AVCDecoderConfigurationRecord.
     *
     * FLV AVC Sequence Header structure:
     * - Byte 0: 0x17 (Keyframe: 1, CodecID: 7 for AVC)
     * - Byte 1: 0x00 (AVCPacketType: 0 for Sequence Header)
     * - Bytes 2-4: 0x00 0x00 0x00 (Composition Time: 0)
     * Followed by AVCDecoderConfigurationRecord:
     * - configurationVersion: 0x01
     * - AVCProfileIndication: sps[1]
     * - profile_compatibility: sps[2]
     * - AVCLevelIndication: sps[3]
     * - lengthSizeMinusOne: 0xFF (3 -> 4-byte NAL length)
     * - numOfSequenceParameterSets: 0xE1 (1)
     * - sequenceParameterSetLength: 2 bytes
     * - sequenceParameterSetNALUnit: SPS data
     * - numOfPictureParameterSets: 0x01
     * - pictureParameterSetLength: 2 bytes
     * - pictureParameterSetNALUnit: PPS data
     */
    @Synchronized
    fun buildSequenceHeader(timestampMs: Long = 0L): FlvVideoPacket? {
        val sps = spsBytes ?: return null
        val pps = ppsBytes ?: return null

        val baos = ByteArrayOutputStream(sps.size + pps.size + 16)

        // 1. FLV Video Tag Header (5 bytes)
        baos.write(FLV_FRAME_KEYFRAME_AVC) // 0x17
        baos.write(AVC_PACKET_TYPE_SEQUENCE_HEADER) // 0x00
        baos.write(0x00) // Composition time 0 (3 bytes)
        baos.write(0x00)
        baos.write(0x00)

        // 2. AVCDecoderConfigurationRecord
        baos.write(0x01) // configurationVersion = 1

        // Profile, compatibility, level from actual SPS
        val profile = if (sps.size > 1) sps[1].toInt() else 0x42
        val compatibility = if (sps.size > 2) sps[2].toInt() else 0x00
        val level = if (sps.size > 3) sps[3].toInt() else 0x1F

        baos.write(profile)
        baos.write(compatibility)
        baos.write(level)

        // 6 reserved bits (0x3F) + 2 bits lengthSizeMinusOne (3 = 4 bytes) -> 0xFF
        baos.write(0xFF)

        // 3 reserved bits (0x07) + 5 bits numOfSequenceParameterSets (1) -> 0xE1
        baos.write(0xE1)

        // SPS length (2 bytes, Big-Endian)
        baos.write((sps.size shr 8) and 0xFF)
        baos.write(sps.size and 0xFF)
        baos.write(sps)

        // numOfPictureParameterSets (1)
        baos.write(0x01)

        // PPS length (2 bytes, Big-Endian)
        baos.write((pps.size shr 8) and 0xFF)
        baos.write(pps.size and 0xFF)
        baos.write(pps)

        hasEmittedSequenceHeader = true

        return FlvVideoPacket(
            payload = baos.toByteArray(),
            timestampMs = timestampMs,
            isKeyframe = true,
            packetType = FlvVideoPacketType.SEQUENCE_HEADER,
            compositionTimeMs = 0
        )
    }

    /**
     * Converts Annex-B formatted NAL units to length-prefixed AVCC format.
     * Each NAL unit is prefixed with a 4-byte big-endian length.
     *
     * @param nalData Annex-B formatted byte array containing one or more NAL units
     * @return Byte array containing length-prefixed NAL units (AVCC)
     */
    fun annexBToAvcc(nalData: ByteArray): ByteArray {
        if (nalData.isEmpty()) return ByteArray(0)

        val nals = H264NalParser.splitAnnexB(nalData)
        if (nals.isEmpty()) return ByteArray(0)

        val totalSize = nals.sumOf { 4 + it.size }
        val baos = ByteArrayOutputStream(totalSize)

        for (nal in nals) {
            writeNaluWithLength(baos, nal)
        }

        return baos.toByteArray()
    }

    /**
     * Packetizes an [EncodedVideoFrame] into FLV/RTMP-compatible video packets.
     * If the frame contains SPS/PPS or if sequence header has not yet been emitted,
     * the sequence header packet is generated and prepended.
     *
     * @param frame The encoded H.264 video frame from VideoEncoder
     * @return A list of [FlvVideoPacket]s (sequence header if needed, followed by video slice packet)
     */
    @Synchronized
    fun packetize(frame: EncodedVideoFrame): List<FlvVideoPacket> {
        if (frame.nalData.isEmpty()) return emptyList()

        val packets = ArrayList<FlvVideoPacket>(2)
        val nals = H264NalParser.splitAnnexB(frame.nalData)

        // Check for in-band SPS / PPS updates
        var foundSps: ByteArray? = null
        var foundPps: ByteArray? = null
        val sliceNals = ArrayList<ByteArray>(nals.size)
        var hasIdrSlice = false

        for (nal in nals) {
            when (H264NalParser.getNalType(nal)) {
                H264NalParser.NAL_TYPE_SPS -> foundSps = nal
                H264NalParser.NAL_TYPE_PPS -> foundPps = nal
                H264NalParser.NAL_TYPE_IDR -> {
                    hasIdrSlice = true
                    sliceNals.add(nal)
                }
                H264NalParser.NAL_TYPE_SEI, H264NalParser.NAL_TYPE_SLICE -> {
                    sliceNals.add(nal)
                }
                else -> {
                    sliceNals.add(nal)
                }
            }
        }

        if (foundSps != null && foundPps != null) {
            setSpsPps(foundSps, foundPps)
        } else if (foundSps != null) {
            spsBytes = H264NalParser.stripStartCode(foundSps)
            hasEmittedSequenceHeader = false
        } else if (foundPps != null) {
            ppsBytes = H264NalParser.stripStartCode(foundPps)
            hasEmittedSequenceHeader = false
        }

        // If this frame is purely codec configuration (CSD / SPS + PPS without slices)
        if (frame.isConfig || sliceNals.isEmpty()) {
            if (hasConfiguration) {
                buildSequenceHeader(frame.timestampMs)?.let { packets.add(it) }
            }
            return packets
        }

        // Emit sequence header prior to first video frame if available and not yet sent
        if (!hasEmittedSequenceHeader && hasConfiguration) {
            buildSequenceHeader(frame.timestampMs)?.let { packets.add(it) }
        }

        // Build the video NALU packet
        val isKeyframe = frame.isKeyframe || hasIdrSlice
        val naluPacket = buildNaluPacket(sliceNals, isKeyframe, frame.timestampMs)
        if (naluPacket != null) {
            packets.add(naluPacket)
        }

        return packets
    }

    /**
     * Direct overload for raw NAL bytes, keyframe flag, and timestamp.
     */
    @Synchronized
    fun packetize(nalData: ByteArray, isKeyframe: Boolean, timestampMs: Long): FlvVideoPacket? {
        if (nalData.isEmpty()) return null

        val nals = H264NalParser.splitAnnexB(nalData)
        if (nals.isEmpty()) return null

        val sliceNals = ArrayList<ByteArray>(nals.size)
        var detectedKeyframe = isKeyframe

        for (nal in nals) {
            when (H264NalParser.getNalType(nal)) {
                H264NalParser.NAL_TYPE_SPS -> spsBytes = nal
                H264NalParser.NAL_TYPE_PPS -> ppsBytes = nal
                H264NalParser.NAL_TYPE_IDR -> {
                    detectedKeyframe = true
                    sliceNals.add(nal)
                }
                else -> sliceNals.add(nal)
            }
        }

        if (sliceNals.isEmpty()) return null

        return buildNaluPacket(sliceNals, detectedKeyframe, timestampMs)
    }

    private fun buildNaluPacket(
        nals: List<ByteArray>,
        isKeyframe: Boolean,
        timestampMs: Long
    ): FlvVideoPacket? {
        if (nals.isEmpty()) return null

        val payloadSize = 5 + nals.sumOf { 4 + it.size }
        val baos = ByteArrayOutputStream(payloadSize)

        // 1. FLV Video Header (5 bytes)
        val frameType = if (isKeyframe) 1 else 2
        val headerByte0 = ((frameType shl 4) or AVC_CODEC_ID).toByte()

        baos.write(headerByte0.toInt())
        baos.write(AVC_PACKET_TYPE_NALU) // 0x01
        baos.write(0x00) // Composition time 0 (3 bytes)
        baos.write(0x00)
        baos.write(0x00)

        // 2. AVCC NALUs (4 bytes length + NAL unit bytes)
        for (nal in nals) {
            writeNaluWithLength(baos, nal)
        }

        return FlvVideoPacket(
            payload = baos.toByteArray(),
            timestampMs = timestampMs,
            isKeyframe = isKeyframe,
            packetType = FlvVideoPacketType.NALU,
            compositionTimeMs = 0
        )
    }

    private fun writeNaluWithLength(baos: ByteArrayOutputStream, nal: ByteArray) {
        val size = nal.size
        baos.write((size shr 24) and 0xFF)
        baos.write((size shr 16) and 0xFF)
        baos.write((size shr 8) and 0xFF)
        baos.write(size and 0xFF)
        baos.write(nal)
    }

    /**
     * Resets sequence header tracking.
     */
    @Synchronized
    fun reset() {
        hasEmittedSequenceHeader = false
    }

    /**
     * Clears all stored configuration and state.
     */
    @Synchronized
    fun release() {
        spsBytes = null
        ppsBytes = null
        hasEmittedSequenceHeader = false
    }

    companion object {
        const val FLV_FRAME_KEYFRAME_AVC = 0x17 // FrameType = 1 (Keyframe), CodecID = 7 (AVC)
        const val FLV_FRAME_INTER_AVC = 0x27    // FrameType = 2 (Inter/P frame), CodecID = 7 (AVC)

        const val AVC_CODEC_ID = 7
        const val AVC_PACKET_TYPE_SEQUENCE_HEADER = 0x00
        const val AVC_PACKET_TYPE_NALU = 0x01
    }
}
