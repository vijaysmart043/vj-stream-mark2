package com.example.youtube

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.settings.AndroidKeyStoreSecureStorage
import com.example.settings.SecureStreamKeyStorage
import com.example.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YouTubeStreamConfigTest {

    // 1. Empty server URL
    @Test
    fun testEmptyServerUrl_isInvalid() {
        val result = YouTubeStreamValidator.validateServerUrl("")
        assertFalse(result.isValid)
        assertTrue(result is YouTubeValidationResult.Invalid)
        assertEquals("Server URL cannot be empty", (result as YouTubeValidationResult.Invalid).reason)

        val whitespaceResult = YouTubeStreamValidator.validateServerUrl("   ")
        assertFalse(whitespaceResult.isValid)
    }

    // 2. Invalid server URL
    @Test
    fun testInvalidServerUrl_isInvalid() {
        val badScheme = YouTubeStreamValidator.validateServerUrl("http://a.rtmp.youtube.com/live2")
        assertFalse(badScheme.isValid)
        assertTrue((badScheme as YouTubeValidationResult.Invalid).reason.contains("rtmp:// or rtmps://"))

        val notUri = YouTubeStreamValidator.validateServerUrl("not a valid uri at all ::::")
        assertFalse(notUri.isValid)

        val noHost = YouTubeStreamValidator.validateServerUrl("rtmp://")
        assertFalse(noHost.isValid)
    }

    // 3. rtmp:// server URL
    @Test
    fun testRtmpServerUrl_isValid() {
        val rtmpUrl = "rtmp://a.rtmp.youtube.com/live2"
        val result = YouTubeStreamValidator.validateServerUrl(rtmpUrl)
        assertTrue(result.isValid)
        assertEquals(YouTubeValidationResult.Valid, result)
    }

    // 4. rtmps:// server URL
    @Test
    fun testRtmpsServerUrl_isValid() {
        val rtmpsUrl = "rtmps://a.rtmp.youtube.com/live2"
        val result = YouTubeStreamValidator.validateServerUrl(rtmpsUrl)
        assertTrue(result.isValid)
        assertEquals(YouTubeValidationResult.Valid, result)
    }

    // 5. Empty stream key
    @Test
    fun testEmptyStreamKey_isInvalid() {
        val emptyResult = YouTubeStreamValidator.validateStreamKey("")
        assertFalse(emptyResult.isValid)
        assertTrue(emptyResult is YouTubeValidationResult.Invalid)

        val whitespaceResult = YouTubeStreamValidator.validateStreamKey("   \t\n  ")
        assertFalse(whitespaceResult.isValid)

        val config = YouTubeStreamConfig(
            serverUrl = "rtmps://a.rtmp.youtube.com/live2",
            streamKey = ""
        )
        assertFalse(config.isValidForStreaming)
    }

    // 6. Valid configuration
    @Test
    fun testValidConfiguration_isValid() {
        val config = YouTubeStreamConfig(
            serverUrl = "rtmps://a.rtmp.youtube.com/live2",
            streamKey = "abcd-1234-efgh-5678"
        )
        val result = YouTubeStreamValidator.validate(config)
        assertTrue(result.isValid)
        assertTrue(config.isValidForStreaming)
        assertEquals("••••••••••••", config.maskedStreamKey)
        assertFalse(config.toString().contains("abcd-1234-efgh-5678"))
    }

    // 7. Configuration persistence
    @Test
    fun testConfigurationPersistence_persistsCorrectly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)

        val testUrl = "rtmps://a.rtmp.youtube.com/live2"
        val testKey = "demo-stream-key-xyz"

        repository.updateServerUrl(testUrl)
        repository.updateStreamKey(testKey)

        val loaded = repository.getConfig()
        assertEquals(testUrl, loaded.serverUrl)
        assertEquals(testKey, loaded.streamKey)

        // Ensure key is NOT stored in plain text in default settings preferences
        val rawPrefs = context.getSharedPreferences("vjstream_settings_prefs", Context.MODE_PRIVATE)
        assertFalse("Stream key must not be in plain SharedPreferences", rawPrefs.contains("stream_key"))
    }

    // 8. Configuration clearing
    @Test
    fun testConfigurationClearing_clearsSuccessfully() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)

        repository.updateServerUrl("rtmps://custom.server.com/live")
        repository.updateStreamKey("secret-key-123")
        assertEquals("secret-key-123", repository.getConfig().streamKey)

        // Clear credentials
        repository.clearCredentials()

        val clearedConfig = repository.getConfig()
        assertEquals(YouTubeStreamConfig.DEFAULT_SERVER_URL, clearedConfig.serverUrl)
        assertEquals("", clearedConfig.streamKey)
        assertFalse(clearedConfig.isValidForStreaming)
    }

    @Test
    fun testStreamKeyTrimming() {
        val trimmedKey = "  spaced-stream-key-456  "
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)

        repository.updateStreamKey(trimmedKey)
        assertEquals("spaced-stream-key-456", repository.getConfig().streamKey)
    }
}
