package com.example.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.youtube.AudioConfig
import com.example.youtube.VideoPresets
import com.example.youtube.YouTubeStreamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(
    context: Context,
    private val secureStorage: SecureStreamKeyStorage = AndroidKeyStoreSecureStorage(context)
) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _configFlow = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<YouTubeStreamConfig> = _configFlow.asStateFlow()

    fun getConfig(): YouTubeStreamConfig = _configFlow.value

    fun updateServerUrl(url: String) {
        val updated = _configFlow.value.copy(serverUrl = url.trim())
        saveConfig(updated)
    }

    fun updateStreamKey(key: String) {
        val updated = _configFlow.value.copy(streamKey = key.trim())
        saveConfig(updated)
    }

    fun clearCredentials() {
        secureStorage.clearStreamKey()
        val updated = _configFlow.value.copy(
            serverUrl = YouTubeStreamConfig.DEFAULT_SERVER_URL,
            streamKey = ""
        )
        saveConfig(updated)
    }

    fun updateVideoPreset(presetIndex: Int) {
        val preset = VideoPresets.ALL.getOrNull(presetIndex) ?: VideoPresets.MEDIUM
        val updated = _configFlow.value.copy(videoPreset = preset)
        saveConfig(updated)
    }

    fun updateMicEnabled(enabled: Boolean) {
        val currentAudio = _configFlow.value.audioConfig
        val updated = _configFlow.value.copy(audioConfig = currentAudio.copy(enabled = enabled))
        saveConfig(updated)
    }

    fun updateAudioBitrate(bitrateBps: Int) {
        val currentAudio = _configFlow.value.audioConfig
        val updated = _configFlow.value.copy(audioConfig = currentAudio.copy(bitrateBps = bitrateBps))
        saveConfig(updated)
    }

    fun updateCameraFacing(isFront: Boolean) {
        val updated = _configFlow.value.copy(isFrontCamera = isFront)
        saveConfig(updated)
    }

    fun updateKeepScreenAwake(keepAwake: Boolean) {
        val updated = _configFlow.value.copy(keepScreenAwake = keepAwake)
        saveConfig(updated)
    }

    fun updateAutoStartCamera(autoStart: Boolean) {
        val updated = _configFlow.value.copy(autoStartCamera = autoStart)
        saveConfig(updated)
    }

    fun resetToDefaults() {
        secureStorage.clearStreamKey()
        val default = YouTubeStreamConfig()
        saveConfig(default)
    }

    fun saveConfig(config: YouTubeStreamConfig) {
        val cleanKey = config.streamKey.trim()
        val cleanUrl = config.serverUrl.trim()
        val sanitized = config.copy(
            serverUrl = cleanUrl,
            streamKey = cleanKey
        )

        // 1. Store stream key securely via KeyStore / encrypted storage
        if (cleanKey.isNotEmpty()) {
            secureStorage.saveStreamKey(cleanKey)
        } else {
            secureStorage.clearStreamKey()
        }

        _configFlow.value = sanitized

        // 2. Store non-sensitive preferences in standard SharedPreferences
        // Note: KEY_STREAM_KEY is explicitly removed from plain preferences
        prefs.edit()
            .putString(KEY_SERVER_URL, sanitized.serverUrl)
            .remove(KEY_LEGACY_STREAM_KEY)
            .putInt(KEY_VIDEO_PRESET, VideoPresets.ALL.indexOf(sanitized.videoPreset).coerceAtLeast(0))
            .putBoolean(KEY_MIC_ENABLED, sanitized.audioConfig.enabled)
            .putInt(KEY_AUDIO_BITRATE, sanitized.audioConfig.bitrateBps)
            .putInt(KEY_AUDIO_SAMPLE_RATE, sanitized.audioConfig.sampleRate)
            .putBoolean(KEY_FRONT_CAMERA, sanitized.isFrontCamera)
            .putBoolean(KEY_KEEP_AWAKE, sanitized.keepScreenAwake)
            .putBoolean(KEY_AUTO_START_CAM, sanitized.autoStartCamera)
            .apply()

        Log.i(TAG, "YouTube configuration loaded")
        Log.i(TAG, "Server configured")
        if (cleanKey.isNotEmpty()) {
            Log.i(TAG, "Stream key configured")
        }
    }

    private fun loadConfig(): YouTubeStreamConfig {
        val serverUrl = prefs.getString(KEY_SERVER_URL, YouTubeStreamConfig.DEFAULT_SERVER_URL)
            ?: YouTubeStreamConfig.DEFAULT_SERVER_URL

        // Migration: If an unencrypted legacy stream key exists in SharedPreferences,
        // migrate it to secure storage and purge it from SharedPreferences.
        val legacyKey = prefs.getString(KEY_LEGACY_STREAM_KEY, null)
        if (!legacyKey.isNullOrEmpty()) {
            secureStorage.saveStreamKey(legacyKey)
            prefs.edit().remove(KEY_LEGACY_STREAM_KEY).apply()
        }

        val streamKey = secureStorage.getStreamKey()

        val presetIndex = prefs.getInt(KEY_VIDEO_PRESET, 1) // default to MEDIUM (index 1)
        val preset = VideoPresets.ALL.getOrNull(presetIndex) ?: VideoPresets.MEDIUM
        val micEnabled = prefs.getBoolean(KEY_MIC_ENABLED, true)
        val audioBitrate = prefs.getInt(KEY_AUDIO_BITRATE, 128000)
        val sampleRate = prefs.getInt(KEY_AUDIO_SAMPLE_RATE, 48000)
        val isFront = prefs.getBoolean(KEY_FRONT_CAMERA, false)
        val keepAwake = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        val autoStart = prefs.getBoolean(KEY_AUTO_START_CAM, true)

        Log.i(TAG, "YouTube configuration loaded")
        Log.i(TAG, "Server configured")
        if (streamKey.isNotBlank()) {
            Log.i(TAG, "Stream key configured")
        }

        return YouTubeStreamConfig(
            serverUrl = serverUrl,
            streamKey = streamKey,
            videoPreset = preset,
            audioConfig = AudioConfig(
                enabled = micEnabled,
                sampleRate = sampleRate,
                channelCount = 2,
                bitrateBps = audioBitrate
            ),
            isFrontCamera = isFront,
            keepScreenAwake = keepAwake,
            autoStartCamera = autoStart
        )
    }

    companion object {
        private const val TAG = "SettingsRepository"
        private const val PREFS_NAME = "vjstream_settings_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LEGACY_STREAM_KEY = "stream_key"
        private const val KEY_VIDEO_PRESET = "video_preset_index"
        private const val KEY_MIC_ENABLED = "mic_enabled"
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
        private const val KEY_AUDIO_SAMPLE_RATE = "audio_sample_rate"
        private const val KEY_FRONT_CAMERA = "is_front_camera"
        private const val KEY_KEEP_AWAKE = "keep_screen_awake"
        private const val KEY_AUTO_START_CAM = "auto_start_camera"
    }
}

