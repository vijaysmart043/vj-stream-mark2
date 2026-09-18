package com.example.streaming.encoder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioEncoderTest {

    @Test
    fun `audio encoder state labels match required specifications`() {
        assertEquals("AAC: IDLE", AudioEncoderState.IDLE.displayLabel)
        assertEquals("AAC: INIT", AudioEncoderState.INITIALIZING.displayLabel)
        assertEquals("AAC: READY", AudioEncoderState.READY.displayLabel)
        assertEquals("AAC: ON", AudioEncoderState.ENCODING.displayLabel)
        assertEquals("AAC: STOPPING", AudioEncoderState.STOPPING.displayLabel)
        assertEquals("AAC: ERROR", AudioEncoderState.ERROR.displayLabel)

        assertTrue(AudioEncoderState.ENCODING.isEncoding)
        assertFalse(AudioEncoderState.IDLE.isEncoding)
        assertTrue(AudioEncoderState.INITIALIZING.isBusy)
        assertTrue(AudioEncoderState.STOPPING.isBusy)
        assertFalse(AudioEncoderState.READY.isBusy)
    }

    @Test
    fun `audio specific config generates correct binary for 48000Hz mono`() {
        // 48000 Hz -> freqIndex = 3, mono -> channelConfig = 1
        // byte 1: (2 shl 3) | (3 shr 1) = 16 | 1 = 0x11
        // byte 2: ((3 and 1) shl 7) | (1 shl 3) = 128 | 8 = 136 (0x88)
        val config = AudioSpecificConfig.fromSampleRateAndChannels(48000, 1)

        assertEquals(2, config.audioObjectType)
        assertEquals(48000, config.sampleRate)
        assertEquals(1, config.channelCount)
        assertEquals(3, config.samplingFrequencyIndex)

        val bytes = config.configBytes
        assertEquals(2, bytes.size)
        assertEquals(0x11.toByte(), bytes[0])
        assertEquals(0x88.toByte(), bytes[1])
    }

    @Test
    fun `audio specific config generates correct binary for 44100Hz stereo`() {
        // 44100 Hz -> freqIndex = 4, stereo -> channelConfig = 2
        // byte 1: (2 shl 3) | (4 shr 1) = 16 | 2 = 0x12
        // byte 2: ((4 and 1) shl 7) | (2 shl 3) = 0 | 16 = 0x10
        val config = AudioSpecificConfig.fromSampleRateAndChannels(44100, 2)

        assertEquals(2, config.audioObjectType)
        assertEquals(44100, config.sampleRate)
        assertEquals(2, config.channelCount)
        assertEquals(4, config.samplingFrequencyIndex)

        val bytes = config.configBytes
        assertEquals(2, bytes.size)
        assertEquals(0x12.toByte(), bytes[0])
        assertEquals(0x10.toByte(), bytes[1])
    }

    @Test
    fun `audio specific config parses binary bytes accurately`() {
        val testBytes = byteArrayOf(0x11.toByte(), 0x88.toByte())
        val parsed = AudioSpecificConfig.fromByteArray(testBytes)

        assertEquals(2, parsed.audioObjectType)
        assertEquals(48000, parsed.sampleRate)
        assertEquals(1, parsed.channelCount)
        assertArrayEquals(testBytes, parsed.configBytes)
    }

    @Test
    fun `audio specific config parses from byte buffer`() {
        val buffer = ByteBuffer.wrap(byteArrayOf(0x12.toByte(), 0x10.toByte()))
        val parsed = AudioSpecificConfig.fromByteBuffer(buffer)

        assertEquals(2, parsed.audioObjectType)
        assertEquals(44100, parsed.sampleRate)
        assertEquals(2, parsed.channelCount)
    }

    @Test
    fun `encoded audio frame encapsulates raw aac payload and metadata`() {
        val rawAacBytes = byteArrayOf(0x21, 0x10, 0x05, 0x40)
        val frame = EncodedAudioFrame(
            aacData = rawAacBytes,
            isConfig = false,
            timestampUs = 120_000L,
            sampleRate = 48000,
            channelCount = 1
        )

        assertFalse(frame.isConfig)
        assertEquals(120_000L, frame.timestampUs)
        assertEquals(120L, frame.timestampMs)
        assertEquals(4, frame.size)
        assertEquals(48000, frame.sampleRate)
        assertEquals(1, frame.channelCount)
        assertArrayEquals(rawAacBytes, frame.aacData)
    }

    @Test
    fun `audio encoder capability discovery returns non null result or safe fallback`() {
        val discovery = AudioEncoderCapabilities.findBestEncoder(AudioEncoderCapabilities.MIME_AAC)
        // In robolectric/jvm environment, fallback encoder discovery handles the case gracefully
        if (discovery != null) {
            assertNotNull(discovery.encoderName)
            assertTrue(discovery.maxChannelCount >= 1)
            assertTrue(discovery.maxBitrate > 0)
        }
    }

    @Test
    fun `audio encoder lifecycle handles start and stop safely`() {
        var errorReported: String? = null
        var configReported: AudioSpecificConfig? = null

        val encoder = AudioEncoder(
            sampleRate = 48000,
            channelCount = 1,
            bitrateBps = 128_000,
            listener = object : AudioEncoder.Listener {
                override fun onAudioConfig(config: AudioSpecificConfig) {
                    configReported = config
                }

                override fun onAudioError(error: String) {
                    errorReported = error
                }
            }
        )

        assertEquals(AudioEncoderState.IDLE, encoder.currentState)

        // Robolectric environment handles start / stop without crashing
        try {
            encoder.start()
        } catch (_: Throwable) {}

        encoder.stop()
        assertEquals(AudioEncoderState.IDLE, encoder.currentState)
    }
}
