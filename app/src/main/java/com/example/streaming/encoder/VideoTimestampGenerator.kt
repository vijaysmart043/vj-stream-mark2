package com.example.streaming.encoder

/**
 * Generates monotonic, zero-anchored timestamps in microseconds (us) for encoded video frames.
 * Uses System.nanoTime() which provides high-precision nanosecond monotonicity across both
 * Android runtime and local host JVM unit tests without relying on unmocked Android SystemClock.
 * Designed to align seamlessly with audio presentation timestamps (PTS) on a common streaming timeline.
 */
class VideoTimestampGenerator(
    private val nanoTimeProvider: () -> Long = { System.nanoTime() }
) {

    private var baseTimeNs = -1L
    private var lastTimestampUs = -1L

    /**
     * Resets the monotonic timeline.
     */
    @Synchronized
    fun reset() {
        baseTimeNs = -1L
        lastTimestampUs = -1L
    }

    /**
     * Returns a strictly monotonic timestamp in microseconds anchored to when the timeline started.
     */
    @Synchronized
    fun nextTimestampUs(): Long {
        val nowNs = nanoTimeProvider()
        if (baseTimeNs < 0) {
            baseTimeNs = nowNs
            lastTimestampUs = 0L
            return 0L
        }

        val elapsedNs = nowNs - baseTimeNs
        var timestampUs = elapsedNs / 1000L

        // Enforce strictly non-decreasing monotonic ordering
        if (timestampUs <= lastTimestampUs) {
            timestampUs = lastTimestampUs + 1L
        }

        lastTimestampUs = timestampUs
        return timestampUs
    }

    @Synchronized
    fun currentTimestampUs(): Long = if (lastTimestampUs < 0) 0L else lastTimestampUs
}
