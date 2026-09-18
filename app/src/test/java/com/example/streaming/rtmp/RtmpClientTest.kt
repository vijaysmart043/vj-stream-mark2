package com.example.streaming.rtmp

import com.example.streaming.encoder.AudioSpecificConfig
import com.example.streaming.encoder.EncodedAudioFrame
import com.example.streaming.encoder.EncodedVideoFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RtmpClientTest {

    @Test
    fun `rtmp connection states have correct properties and flags`() {
        assertEquals("Disconnected", RtmpConnectionState.DISCONNECTED.displayLabel)
        assertEquals("Connecting", RtmpConnectionState.CONNECTING.displayLabel)
        assertEquals("Connected", RtmpConnectionState.CONNECTED.displayLabel)
        assertEquals("Connection Error", RtmpConnectionState.ERROR.displayLabel)
        assertEquals("Disconnecting", RtmpConnectionState.DISCONNECTING.displayLabel)

        assertTrue(RtmpConnectionState.CONNECTED.isConnected)
        assertFalse(RtmpConnectionState.CONNECTING.isConnected)
        assertFalse(RtmpConnectionState.DISCONNECTED.isConnected)

        assertTrue(RtmpConnectionState.CONNECTING.isConnecting)
        assertFalse(RtmpConnectionState.CONNECTED.isConnecting)

        assertTrue(RtmpConnectionState.CONNECTING.isBusy)
        assertTrue(RtmpConnectionState.DISCONNECTING.isBusy)
        assertFalse(RtmpConnectionState.CONNECTED.isBusy)
        assertFalse(RtmpConnectionState.DISCONNECTED.isBusy)
    }

    @Test
    fun `rtmp url validator accepts valid rtmp and rtmps endpoints`() {
        val standardRtmp = RtmpUrlValidator.validate("rtmp://a.rtmp.youtube.com/live2")
        assertTrue(standardRtmp is RtmpUrlValidator.ValidationResult.Valid)
        val valid1 = standardRtmp as RtmpUrlValidator.ValidationResult.Valid
        assertFalse(valid1.isSsl)
        assertEquals("a.rtmp.youtube.com", valid1.host)
        assertEquals(1935, valid1.port)
        assertEquals("live2", valid1.app)

        val standardRtmps = RtmpUrlValidator.validate("rtmps://a.rtmps.youtube.com/live2")
        assertTrue(standardRtmps is RtmpUrlValidator.ValidationResult.Valid)
        val valid2 = standardRtmps as RtmpUrlValidator.ValidationResult.Valid
        assertTrue(valid2.isSsl)
        assertEquals("a.rtmps.youtube.com", valid2.host)
        assertEquals(443, valid2.port)
        assertEquals("live2", valid2.app)

        val customPort = RtmpUrlValidator.validate("rtmp://192.168.1.100:1936/live")
        assertTrue(customPort is RtmpUrlValidator.ValidationResult.Valid)
        val valid3 = customPort as RtmpUrlValidator.ValidationResult.Valid
        assertEquals("192.168.1.100", valid3.host)
        assertEquals(1936, valid3.port)
        assertEquals("live", valid3.app)
    }

    @Test
    fun `rtmp url validator rejects empty, malformed, and unsupported protocols`() {
        assertTrue(RtmpUrlValidator.validate(null) is RtmpUrlValidator.ValidationResult.Invalid)
        assertTrue(RtmpUrlValidator.validate("") is RtmpUrlValidator.ValidationResult.Invalid)
        assertTrue(RtmpUrlValidator.validate("   ") is RtmpUrlValidator.ValidationResult.Invalid)

        // Unsupported protocol
        val httpResult = RtmpUrlValidator.validate("https://youtube.com/live2")
        assertTrue(httpResult is RtmpUrlValidator.ValidationResult.Invalid)
        val httpErr = httpResult as RtmpUrlValidator.ValidationResult.Invalid
        assertTrue(httpErr.reason.contains("Unsupported protocol", ignoreCase = true))

        // Malformed URLs
        assertTrue(RtmpUrlValidator.validate("rtmp://") is RtmpUrlValidator.ValidationResult.Invalid)
        assertTrue(RtmpUrlValidator.validate("rtmps://") is RtmpUrlValidator.ValidationResult.Invalid)
        assertTrue(RtmpUrlValidator.validate("rtmp://:1935/live") is RtmpUrlValidator.ValidationResult.Invalid)
        assertTrue(RtmpUrlValidator.validate("rtmp://example.com:abc/live") is RtmpUrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `rtmp client immediately rejects invalid url without opening connection`() {
        var failureReported: String? = null
        val latch = CountDownLatch(1)

        val client = RtmpClient(object : RtmpClient.Listener {
            override fun onConnected() {}
            override fun onConnectionFailed(reason: String) {
                failureReported = reason
                latch.countDown()
            }
            override fun onDisconnected() {}
        })

        client.connect("invalid://url", "my-stream-key")

        assertEquals(RtmpConnectionState.ERROR, client.currentState)
        assertTrue(failureReported?.contains("Unsupported protocol") == true)
    }

    @Test
    fun `rtmp client immediately rejects empty stream key without leaking credentials`() {
        var failureReported: String? = null

        val client = RtmpClient(object : RtmpClient.Listener {
            override fun onConnected() {}
            override fun onConnectionFailed(reason: String) {
                failureReported = reason
            }
            override fun onDisconnected() {}
        })

        client.connect("rtmp://a.rtmp.youtube.com/live2", "   ")

        assertEquals(RtmpConnectionState.ERROR, client.currentState)
        assertTrue(failureReported?.contains("Stream key cannot be empty") == true)
    }

    @Test
    fun `test connection reports failure cleanly for invalid url`() {
        val client = RtmpClient(object : RtmpClient.Listener {
            override fun onConnected() {}
            override fun onConnectionFailed(reason: String) {}
            override fun onDisconnected() {}
        })

        var resultSuccess: Boolean? = null
        var resultMessage: String? = null

        client.testConnection("http://invalid", "dummy-key") { success, message ->
            resultSuccess = success
            resultMessage = message
        }

        assertFalse(resultSuccess ?: true)
        assertTrue(resultMessage?.contains("Unsupported protocol") == true)
        assertEquals(RtmpConnectionState.ERROR, client.currentState)
    }

    @Test
    fun `encoded packet interfaces accept video and audio access units safely`() {
        val client = RtmpClient(object : RtmpClient.Listener {
            override fun onConnected() {}
            override fun onConnectionFailed(reason: String) {}
            override fun onDisconnected() {}
        })

        // Verify setSpsPps interface
        client.setSpsPps(byteArrayOf(0x67, 0x42, 0x00), byteArrayOf(0x68, 0xCE.toByte()))

        // Verify sendVideo interface (does not throw when disconnected)
        val videoFrame = EncodedVideoFrame(
            nalData = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte()),
            isKeyframe = true,
            isConfig = false,
            timestampUs = 33000L
        )
        client.sendVideo(videoFrame)
        client.sendVideo(byteArrayOf(0x41, 0x9A.toByte()), false, 66L)

        // Verify sendAudio interface
        val audioConfig = AudioSpecificConfig.fromSampleRateAndChannels(48000, 1)
        client.sendAudioConfig(audioConfig)

        val audioFrame = EncodedAudioFrame(
            aacData = byteArrayOf(0x21, 0x10, 0x05),
            isConfig = false,
            timestampUs = 21000L
        )
        client.sendAudio(audioFrame)
        client.sendAudio(byteArrayOf(0x21, 0x10, 0x06), 42L)

        // Disconnect cleans up properly
        client.disconnect()
        assertEquals(RtmpConnectionState.DISCONNECTED, client.currentState)
    }
}
