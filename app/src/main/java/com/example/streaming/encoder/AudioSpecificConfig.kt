package com.example.streaming.encoder

import java.nio.ByteBuffer

/**
 * Encapsulates the 2-byte MPEG-4 AudioSpecificConfig (ASC) for AAC-LC.
 * Required for RTMP / FLV audio sequence headers (AAC sequence header tag 0xAF 0x00).
 *
 * Structure (16 bits):
 * - 5 bits: audioObjectType (2 = AAC-LC)
 * - 4 bits: samplingFrequencyIndex
 * - 4 bits: channelConfiguration (1 = Mono, 2 = Stereo)
 * - 3 bits: 0 (frameLengthFlag=0, dependsOnCoreCoder=0, extensionFlag=0)
 */
data class AudioSpecificConfig(
    val audioObjectType: Int = 2,
    val sampleRate: Int = 48000,
    val channelCount: Int = 1,
    val configBytes: ByteArray = generateConfigBytes(sampleRate, channelCount, audioObjectType)
) {
    val samplingFrequencyIndex: Int
        get() = samplingFrequencyIndex(sampleRate)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioSpecificConfig
        if (audioObjectType != other.audioObjectType) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (!configBytes.contentEquals(other.configBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = audioObjectType
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + configBytes.contentHashCode()
        return result
    }

    companion object {
        const val AUDIO_OBJECT_TYPE_AAC_LC = 2

        private val SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350
        )

        fun samplingFrequencyIndex(sampleRate: Int): Int {
            val idx = SAMPLE_RATES.indexOf(sampleRate)
            return if (idx >= 0) idx else 4 // Default to 44100 Hz index if unknown
        }

        fun sampleRateForIndex(index: Int): Int {
            return if (index in SAMPLE_RATES.indices) SAMPLE_RATES[index] else 44100
        }

        /**
         * Generates the standard 2-byte AudioSpecificConfig ByteArray for AAC-LC.
         */
        fun generateConfigBytes(
            sampleRate: Int,
            channelCount: Int,
            audioObjectType: Int = AUDIO_OBJECT_TYPE_AAC_LC
        ): ByteArray {
            val freqIndex = samplingFrequencyIndex(sampleRate)
            val byte1 = ((audioObjectType and 0x1F) shl 3) or ((freqIndex and 0x0E) shr 1)
            val byte2 = ((freqIndex and 0x01) shl 7) or ((channelCount and 0x0F) shl 3)
            return byteArrayOf(byte1.toByte(), byte2.toByte())
        }

        /**
         * Parses AudioSpecificConfig from a ByteArray (e.g. MediaCodec csd-0).
         */
        fun fromByteArray(bytes: ByteArray): AudioSpecificConfig {
            if (bytes.size < 2) {
                return AudioSpecificConfig()
            }
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF

            val audioObjectType = (b0 shr 3) and 0x1F
            val freqIndex = ((b0 and 0x07) shl 1) or ((b1 and 0x80) shr 7)
            val channelCount = (b1 and 0x78) shr 3
            val sampleRate = sampleRateForIndex(freqIndex)

            val configData = bytes.copyOf(2)
            return AudioSpecificConfig(
                audioObjectType = if (audioObjectType > 0) audioObjectType else AUDIO_OBJECT_TYPE_AAC_LC,
                sampleRate = sampleRate,
                channelCount = if (channelCount > 0) channelCount else 1,
                configBytes = configData
            )
        }

        /**
         * Parses AudioSpecificConfig from a ByteBuffer (e.g. MediaCodec csd-0 output buffer).
         */
        fun fromByteBuffer(buffer: ByteBuffer): AudioSpecificConfig {
            val duplicate = buffer.duplicate()
            val bytes = ByteArray(duplicate.remaining())
            duplicate.get(bytes)
            return fromByteArray(bytes)
        }

        fun fromSampleRateAndChannels(
            sampleRate: Int,
            channelCount: Int,
            audioObjectType: Int = AUDIO_OBJECT_TYPE_AAC_LC
        ): AudioSpecificConfig {
            return AudioSpecificConfig(
                audioObjectType = audioObjectType,
                sampleRate = sampleRate,
                channelCount = channelCount,
                configBytes = generateConfigBytes(sampleRate, channelCount, audioObjectType)
            )
        }
    }
}
