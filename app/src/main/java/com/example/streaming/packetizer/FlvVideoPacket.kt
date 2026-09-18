package com.example.streaming.packetizer

/**
 * Types of FLV video packets for AVC/H.264 streaming.
 */
enum class FlvVideoPacketType {
    /**
     * AVC sequence header containing the AVCDecoderConfigurationRecord (SPS + PPS).
     * Sent once at the start of a stream or whenever video configuration changes.
     */
    SEQUENCE_HEADER,

    /**
     * Standard AVC video payload containing one or more length-prefixed NAL units (AVCC).
     */
    NALU
}

/**
 * Encapsulates an FLV/RTMP-compatible AVC/H.264 video packet.
 *
 * @param payload Complete FLV video tag body (5-byte AVC video header + AVCDecoderConfigurationRecord or AVCC NALUs)
 * @param timestampMs Presentation timestamp in milliseconds on the streaming timeline
 * @param isKeyframe True if this packet represents a keyframe (IDR) or sequence header
 * @param packetType The [FlvVideoPacketType] (SEQUENCE_HEADER or NALU)
 * @param compositionTimeMs Composition time offset in milliseconds (0 for baseline low-latency streams)
 */
data class FlvVideoPacket(
    val payload: ByteArray,
    val timestampMs: Long,
    val isKeyframe: Boolean,
    val packetType: FlvVideoPacketType,
    val compositionTimeMs: Int = 0
) {
    val size: Int
        get() = payload.size

    val isSequenceHeader: Boolean
        get() = packetType == FlvVideoPacketType.SEQUENCE_HEADER

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FlvVideoPacket
        if (!payload.contentEquals(other.payload)) return false
        if (timestampMs != other.timestampMs) return false
        if (isKeyframe != other.isKeyframe) return false
        if (packetType != other.packetType) return false
        if (compositionTimeMs != other.compositionTimeMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + isKeyframe.hashCode()
        result = 31 * result + packetType.hashCode()
        result = 31 * result + compositionTimeMs
        return result
    }
}
