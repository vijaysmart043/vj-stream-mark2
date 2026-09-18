package com.example.streaming.packetizer

/**
 * Types of FLV audio packets for AAC streaming.
 */
enum class FlvAudioPacketType(val value: Int) {
    /**
     * AAC sequence header (packet type 0) containing the AudioSpecificConfig (ASC).
     */
    SEQUENCE_HEADER(0),

    /**
     * Raw AAC access unit (packet type 1).
     */
    RAW(1)
}

/**
 * Encapsulates an FLV/RTMP-compatible AAC audio packet.
 *
 * Structure of payload:
 * - Byte 0: FLV Audio Tag Header (SoundFormat: 10=AAC, SoundRate: 3=44k/48k, SoundSize: 1=16bit, SoundType: 0=Mono/1=Stereo)
 * - Byte 1: AACPacketType (0 = Sequence Header, 1 = Raw AAC)
 * - Byte 2..N: AudioSpecificConfig (for sequence header) or raw AAC access unit data
 *
 * @param payload The complete FLV audio tag body
 * @param timestampMs Presentation timestamp in milliseconds on the media timeline
 * @param packetType The [FlvAudioPacketType] (SEQUENCE_HEADER or RAW)
 * @param isConfig True if this is an AAC sequence header configuration packet
 * @param sampleRate Audio sample rate in Hz (e.g. 48000)
 * @param channelCount Audio channel count (1 for Mono, 2 for Stereo)
 */
data class FlvAudioPacket(
    val payload: ByteArray,
    val timestampMs: Long,
    val packetType: FlvAudioPacketType,
    val isConfig: Boolean = packetType == FlvAudioPacketType.SEQUENCE_HEADER,
    val sampleRate: Int = 48000,
    val channelCount: Int = 1
) {
    val size: Int
        get() = payload.size

    val isSequenceHeader: Boolean
        get() = packetType == FlvAudioPacketType.SEQUENCE_HEADER

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FlvAudioPacket
        if (!payload.contentEquals(other.payload)) return false
        if (timestampMs != other.timestampMs) return false
        if (packetType != other.packetType) return false
        if (isConfig != other.isConfig) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        return true
    }

    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + packetType.hashCode()
        result = 31 * result + isConfig.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        return result
    }
}
