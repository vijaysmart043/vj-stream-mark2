package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.streaming.audio.AudioCaptureManager
import com.example.streaming.audio.AudioFrame
import com.example.streaming.audio.AudioLevelCalculator
import com.example.streaming.audio.AudioState
import com.example.streaming.audio.AudioTimestampGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioPipelineTest {

    @Test
    fun `audio state display labels match required specifications`() {
        assertEquals("MIC: ON", AudioState.CAPTURING.displayLabel)
        assertEquals("MIC: OFF", AudioState.OFF.displayLabel)
        assertEquals("MIC: OFF", AudioState.MUTED.displayLabel)
        assertEquals("MIC: INITIALIZING", AudioState.INITIALIZING.displayLabel)
        assertEquals("MIC: ERROR", AudioState.ERROR.displayLabel)

        assertTrue(AudioState.CAPTURING.isCapturing)
        assertTrue(AudioState.MUTED.isMuted)
        assertFalse(AudioState.OFF.isMuted)
    }

    @Test
    fun `audio timestamp generator maintains monotonic increasing timestamps`() {
        val generator = AudioTimestampGenerator(sampleRate = 48000, channelCount = 1)
        val ts1 = generator.nextTimestampUs(1024)
        val ts2 = generator.nextTimestampUs(1024)
        val ts3 = generator.nextTimestampUs(2048)

        assertTrue("Timestamps must be non-negative", ts1 >= 0)
        assertTrue("Subsequent timestamps must be greater or equal", ts2 >= ts1)
        assertTrue("Subsequent timestamps must be greater or equal", ts3 >= ts2)
    }

    @Test
    fun `audio level calculator calculates zero volume for silence`() {
        val silentPcm = ByteArray(2048) // All zeroes
        val (rms, peak) = AudioLevelCalculator.calculateLevels(silentPcm, silentPcm.size)

        assertEquals(0f, rms, 0.01f)
        assertEquals(0f, peak, 0.01f)

        val blocks = AudioLevelCalculator.formatMeterBlocks(0f, 10)
        assertEquals("░░░░░░░░░░", blocks)
    }

    @Test
    fun `audio level calculator calculates max volume for full scale sine or square wave`() {
        val fullScalePcm = ByteArray(2048)
        // Fill with full scale 16-bit signed values (32767 = 0x7FFF)
        for (i in 0 until fullScalePcm.size step 2) {
            fullScalePcm[i] = 0xFF.toByte()
            fullScalePcm[i + 1] = 0x7F.toByte()
        }

        val (rms, peak) = AudioLevelCalculator.calculateLevels(fullScalePcm, fullScalePcm.size)

        assertTrue("Peak should be near 1.0", peak > 0.99f)
        assertTrue("RMS should be near 1.0", rms > 0.95f)

        val blocks = AudioLevelCalculator.formatMeterBlocks(rms, 10)
        assertEquals("██████████", blocks)
    }

    @Test
    fun `audio frame correctly carries pcm metadata`() {
        val testBytes = byteArrayOf(0, 1, 2, 3)
        val frame = AudioFrame(
            data = testBytes,
            length = 4,
            timestampUs = 123456L,
            sampleRate = 48000,
            channelCount = 1
        )

        assertEquals(4, frame.length)
        assertEquals(123456L, frame.timestampUs)
        assertEquals(48000, frame.sampleRate)
        assertEquals(1, frame.channelCount)
        assertFalse(frame.isSilent())
    }

    @Test
    fun `audio capture manager safely initializes and handles mute toggle`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = AudioCaptureManager(
            context = context,
            preferredSampleRate = 48000,
            preferredChannels = 1
        )

        assertEquals(AudioState.OFF, manager.currentState)
        assertFalse(manager.isMuted)

        manager.setMuted(true)
        assertTrue(manager.isMuted)

        manager.setMuted(false)
        assertFalse(manager.isMuted)

        // Stop capture when already off must not throw
        manager.stopCapture()
        assertEquals(AudioState.OFF, manager.currentState)
    }
}
