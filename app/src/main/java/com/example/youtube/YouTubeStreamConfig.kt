package com.example.youtube

data class VideoPreset(
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int
) {
    val bitrateBps: Int get() = bitrateKbps * 1000
    val resolutionLabel: String get() = "${width}x$height"
    val bitrateLabel: String get() = "${bitrateKbps / 1000.0} Mbps"
}

object VideoPresets {
    val LOW = VideoPreset(
        name = "LOW (720p 30fps)",
        width = 1280,
        height = 720,
        fps = 30,
        bitrateKbps = 2500
    )
    val MEDIUM = VideoPreset(
        name = "MEDIUM (720p 30fps)",
        width = 1280,
        height = 720,
        fps = 30,
        bitrateKbps = 4000
    )
    val HIGH = VideoPreset(
        name = "HIGH (1080p 30fps)",
        width = 1920,
        height = 1080,
        fps = 30,
        bitrateKbps = 6000
    )
    val HIGH_60 = VideoPreset(
        name = "HIGH 60 (720p 60fps)",
        width = 1280,
        height = 720,
        fps = 60,
        bitrateKbps = 4500
    )

    val ALL = listOf(LOW, MEDIUM, HIGH, HIGH_60)
}

data class AudioConfig(
    val enabled: Boolean = true,
    val sampleRate: Int = 48000,
    val channelCount: Int = 2, // Stereo default, falls back to mono
    val bitrateBps: Int = 128000
)

data class YouTubeStreamConfig(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val streamKey: String = "",
    val videoPreset: VideoPreset = VideoPresets.MEDIUM,
    val audioConfig: AudioConfig = AudioConfig(),
    val isFrontCamera: Boolean = false,
    val keepScreenAwake: Boolean = true,
    val autoStartCamera: Boolean = true
) {
    companion object {
        const val DEFAULT_SERVER_URL = "rtmps://a.rtmp.youtube.com/live2"
        const val BACKUP_SERVER_URL = "rtmp://a.rtmp.youtube.com/live2"
    }

    val fullRtmpUrl: String
        get() {
            val cleanUrl = serverUrl.trim().removeSuffix("/")
            val cleanKey = streamKey.trim()
            return if (cleanKey.isEmpty()) cleanUrl else "$cleanUrl/$cleanKey"
        }

    val maskedStreamKey: String
        get() = if (streamKey.isBlank()) "" else "••••••••••••"

    val isValidForStreaming: Boolean
        get() = YouTubeStreamValidator.validate(this).isValid

    override fun toString(): String {
        val safeKey = if (streamKey.isBlank()) "<empty>" else "••••••••"
        return "YouTubeStreamConfig(serverUrl='$serverUrl', streamKey='$safeKey', videoPreset=${videoPreset.name}, audioEnabled=${audioConfig.enabled})"
    }
}
