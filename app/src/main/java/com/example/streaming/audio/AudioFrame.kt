package com.example.streaming.audio

/**
 * Encapsulates raw 16-bit PCM audio data captured from the microphone.
 * Includes a monotonic presentation timestamp in microseconds (timestampUs)
 * designed for audio/video synchronization (Section 12 of VJStream Phase 3).
 */
data class AudioFrame(
    val data: ByteArray,
    val length: Int,
    val timestampUs: Long,
    val sampleRate: Int,
    val channelCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioFrame
        if (length != other.length) return false
        if (timestampUs != other.timestampUs) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + length
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        return result
    }

    fun isSilent(): Boolean {
        for (i in 0 until length) {
            if (data[i] != 0.toByte()) return false
        }
        return true
    }
}
