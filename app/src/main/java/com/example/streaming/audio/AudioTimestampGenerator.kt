package com.example.streaming.audio

import android.os.SystemClock

/**
 * High-precision monotonic timestamp generator for streaming media.
 * Fulfills Section 12: Media timing must use a monotonic clock (not wall-clock time)
 * to avoid clock skips, NTP adjustments, or time-zone distortions.
 */
class AudioTimestampGenerator(
    private val sampleRate: Int = 48000,
    private val channelCount: Int = 1
) {
    private var totalSamplesProcessed: Long = 0L

    fun reset() {
        totalSamplesProcessed = 0L
    }

    /**
     * Obtains the monotonic timestamp in microseconds based on actual captured PCM samples.
     * Formula: (totalSamplesProcessed * 1_000_000L) / sampleRate
     * @param bytesRead size in bytes of the 16-bit PCM chunk read
     */
    fun nextTimestampUs(bytesRead: Int): Long {
        val bytesPerSample = 2 * channelCount // 16-bit PCM = 2 bytes per multi-channel sample frame
        val currentTimestampUs = if (sampleRate > 0) {
            (totalSamplesProcessed * 1_000_000L) / sampleRate
        } else {
            0L
        }

        if (bytesPerSample > 0 && bytesRead > 0) {
            val samplesInChunk = bytesRead / bytesPerSample
            totalSamplesProcessed += samplesInChunk
        }

        return currentTimestampUs
    }

    /**
     * Absolute monotonic timestamp in microseconds.
     */
    fun currentMonotonicTimeUs(): Long {
        return if (sampleRate > 0) {
            (totalSamplesProcessed * 1_000_000L) / sampleRate
        } else {
            0L
        }
    }
}
