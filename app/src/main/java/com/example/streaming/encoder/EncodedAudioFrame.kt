package com.example.streaming.encoder

/**
 * Encapsulates an encoded AAC audio access unit produced by MediaCodec.
 * Contains raw AAC access unit data (without ADTS headers) ready for RTMP/FLV muxing.
 */
data class EncodedAudioFrame(
    val aacData: ByteArray,
    val isConfig: Boolean,
    val timestampUs: Long,
    val sampleRate: Int = 48000,
    val channelCount: Int = 1,
    val size: Int = aacData.size
) {
    val timestampMs: Long get() = timestampUs / 1000L

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncodedAudioFrame
        if (isConfig != other.isConfig) return false
        if (timestampUs != other.timestampUs) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (!aacData.contentEquals(other.aacData)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = aacData.contentHashCode()
        result = 31 * result + isConfig.hashCode()
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        return result
    }
}
