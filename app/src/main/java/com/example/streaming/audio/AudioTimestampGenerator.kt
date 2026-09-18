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
    private var baseTimestampUs: Long = -1L
    private var totalFramesProcessed: Long = 0L

    fun reset() {
        baseTimestampUs = -1L
        totalFramesProcessed = 0L
    }

    /**
     * Obtains the monotonic timestamp in microseconds for the current audio chunk.
     * @param bytesRead size in bytes of the 16-bit PCM chunk read
     */
    fun nextTimestampUs(bytesRead: Int): Long {
        val currentUs = SystemClock.elapsedRealtimeNanos() / 1000L
        if (baseTimestampUs < 0L) {
            baseTimestampUs = currentUs
        }

        val relativeUs = currentUs - baseTimestampUs

        val bytesPerFrame = 2 * channelCount // 16-bit PCM = 2 bytes per sample
        if (bytesPerFrame > 0) {
            val frames = bytesRead / bytesPerFrame
            totalFramesProcessed += frames
        }

        return relativeUs
    }

    /**
     * Absolute monotonic timestamp in microseconds.
     */
    fun currentMonotonicTimeUs(): Long {
        return SystemClock.elapsedRealtimeNanos() / 1000L
    }
}
