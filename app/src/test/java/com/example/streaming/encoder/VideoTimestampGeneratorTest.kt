package com.example.streaming.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideoTimestampGeneratorTest {

    @Test
    fun `initial timestamp starts at 0`() {
        var simulatedTimeNs = 1_000_000_000L
        val generator = VideoTimestampGenerator(fps = 30, nanoTimeProvider = { simulatedTimeNs })

        val ts0 = generator.nextTimestampUs()
        assertEquals(0L, ts0)
    }

    @Test
    fun `frame spacing at 30 fps is approximately 33ms`() {
        var simulatedTimeNs = 1_000_000_000L
        val generator = VideoTimestampGenerator(fps = 30, nanoTimeProvider = { simulatedTimeNs })

        val ts0 = generator.nextTimestampUs()
        assertEquals(0L, ts0)

        // Advance simulated time by 33.333 ms
        simulatedTimeNs += 33_333_333L
        val ts1 = generator.nextTimestampUs()
        assertEquals(33_333L, ts1)

        simulatedTimeNs += 33_333_333L
        val ts2 = generator.nextTimestampUs()
        assertEquals(66_666L, ts2)

        simulatedTimeNs += 33_333_333L
        val ts3 = generator.nextTimestampUs()
        assertEquals(99_999L, ts3)
    }

    @Test
    fun `rapid consecutive calls never produce duplicate millisecond timestamps`() {
        var simulatedTimeNs = 1_000_000_000L
        // Simulated clock does NOT advance (or advances by only 100ns)
        val generator = VideoTimestampGenerator(fps = 30, nanoTimeProvider = { simulatedTimeNs })

        val ts0 = generator.nextTimestampUs()
        val ts1 = generator.nextTimestampUs()
        val ts2 = generator.nextTimestampUs()
        val ts3 = generator.nextTimestampUs()

        assertTrue(ts1 > ts0)
        assertTrue(ts2 > ts1)
        assertTrue(ts3 > ts2)

        val ms0 = ts0 / 1000L
        val ms1 = ts1 / 1000L
        val ms2 = ts2 / 1000L
        val ms3 = ts3 / 1000L

        assertTrue(ms1 > ms0)
        assertTrue(ms2 > ms1)
        assertTrue(ms3 > ms2)
    }

    @Test
    fun `clock going backwards never causes timestamp to regress`() {
        var simulatedTimeNs = 5_000_000_000L
        val generator = VideoTimestampGenerator(fps = 30, nanoTimeProvider = { simulatedTimeNs })

        val ts0 = generator.nextTimestampUs()
        simulatedTimeNs += 33_333_333L
        val ts1 = generator.nextTimestampUs()
        assertTrue(ts1 > ts0)

        // Clock suddenly jumps backwards
        simulatedTimeNs -= 1_000_000_000L
        val ts2 = generator.nextTimestampUs()
        assertTrue(ts2 > ts1)
        assertTrue((ts2 / 1000L) > (ts1 / 1000L))
    }

    @Test
    fun `reset restarts timeline from zero`() {
        var simulatedTimeNs = 1_000_000_000L
        val generator = VideoTimestampGenerator(fps = 30, nanoTimeProvider = { simulatedTimeNs })

        generator.nextTimestampUs()
        simulatedTimeNs += 100_000_000L
        generator.nextTimestampUs()

        generator.reset()
        simulatedTimeNs += 500_000_000L
        val tsAfterReset = generator.nextTimestampUs()
        assertEquals(0L, tsAfterReset)
    }
}
