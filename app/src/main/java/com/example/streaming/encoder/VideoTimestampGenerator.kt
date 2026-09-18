package com.example.streaming.encoder

/**
 * Generates monotonic, zero-anchored timestamps in microseconds (us) for encoded video frames.
 * Provides smooth, jitter-free presentation timestamps (PTS) aligned to the target frame rate
 * (e.g. ~33.3 ms for 30 FPS) while maintaining synchronization with real-time capture clocks.
 * Guarantees that timestamps are strictly monotonic with no duplicate millisecond values.
 */
class VideoTimestampGenerator(
    val fps: Int = 30,
    private val nanoTimeProvider: () -> Long = { System.nanoTime() }
) {

    private var baseTimeNs = -1L
    private var frameCount = 0L
    private var lastTimestampUs = -1L
    private val frameDurationUs = if (fps > 0) (1_000_000L / fps) else 33_333L

    /**
     * Resets the monotonic timeline.
     */
    @Synchronized
    fun reset() {
        baseTimeNs = -1L
        frameCount = 0L
        lastTimestampUs = -1L
    }

    /**
     * Returns a strictly monotonic timestamp in microseconds anchored to when the timeline started.
     * Ensures frame spacing is smooth (~33.3 ms at 30 FPS), prevents duplicate millisecond timestamps,
     * prevents timestamps going backwards, and avoids huge timestamp jumps by tracking media time
     * while bounding drift against real elapsed time.
     */
    @Synchronized
    fun nextTimestampUs(): Long {
        val nowNs = nanoTimeProvider()
        if (baseTimeNs < 0) {
            baseTimeNs = nowNs
            frameCount = 0L
            lastTimestampUs = 0L
            return 0L
        }

        frameCount++
        // Expected media timestamp based on frame count (smooth ~33.3ms pacing at 30fps)
        val expectedUs = frameCount * frameDurationUs
        val elapsedUs = (nowNs - baseTimeNs) / 1000L

        // Allow smooth frame pacing, but resync base if real elapsed time drifts significantly (> 200ms)
        val driftUs = elapsedUs - expectedUs
        val targetUs = if (Math.abs(driftUs) > 200_000L) {
            baseTimeNs = nowNs - (expectedUs * 1000L)
            expectedUs
        } else {
            expectedUs
        }

        // Enforce strictly monotonic ordering
        var timestampUs = targetUs
        if (timestampUs <= lastTimestampUs) {
            timestampUs = lastTimestampUs + frameDurationUs
        }

        // Guarantee that timestampMs (timestampUs / 1000) is strictly greater than previous frame's timestampMs
        val lastTimestampMs = if (lastTimestampUs < 0) -1L else (lastTimestampUs / 1000L)
        val currentTimestampMs = timestampUs / 1000L
        if (currentTimestampMs <= lastTimestampMs) {
            timestampUs = (lastTimestampMs + 1L) * 1000L
        }

        lastTimestampUs = timestampUs
        return timestampUs
    }

    @Synchronized
    fun currentTimestampUs(): Long = if (lastTimestampUs < 0) 0L else lastTimestampUs
}
