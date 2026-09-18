package com.example.streaming

enum class StreamStatus {
    OFFLINE,
    STOPPED,
    INITIALIZING,
    CONNECTING,
    CONNECTED,
    PUBLISHING,
    LIVE,
    RECONNECTING,
    STOPPING,
    ERROR;

    val isStreaming: Boolean
        get() = this == LIVE || this == RECONNECTING || this == PUBLISHING

    val isBusy: Boolean
        get() = this == INITIALIZING || this == CONNECTING || this == CONNECTED || this == PUBLISHING || this == STOPPING
}

enum class NetworkHealth {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    OFFLINE
}

data class StreamStatistics(
    val durationSeconds: Long = 0L,
    // Video metrics (real calculated)
    val cameraFps: Double = 0.0,
    val encoderFps: Double = 0.0,
    val transmittedFps: Double = 0.0,
    val fps: Double = 0.0, // legacy alias for encoderFps
    val encodedVideoBytes: Long = 0L,
    val actualVideoBitrateKbps: Int = 0,
    val videoBitrateKbps: Int = 0, // legacy alias
    val droppedVideoFrames: Long = 0L,
    val droppedFrames: Long = 0L, // legacy alias
    val keyframeCount: Long = 0L,
    val isEncoderActive: Boolean = false,
    val encoderName: String = "None",

    // Audio metrics (real calculated)
    val audioSamples: Long = 0L,
    val audioFrames: Long = 0L,
    val audioBytes: Long = 0L,
    val actualAudioBitrateKbps: Int = 0,
    val audioBitrateKbps: Int = 0, // legacy alias
    val audioSampleRate: Int = 48000,
    val audioChannels: Int = 1,
    val audioTimestampUs: Long = 0L,
    val audioEnabled: Boolean = true,

    // Network & RTMP metrics (real calculated)
    val bytesSent: Long = 0L,
    val totalBytesSent: Long = 0L, // legacy alias
    val sendRateKbps: Int = 0,
    val rtmpConnected: Boolean = false,
    val rtmpReconnectCount: Int = 0,
    val networkStatus: NetworkHealth = NetworkHealth.GOOD,
    val statusMessage: String = "Ready to stream",
    val connectionState: String = "OFFLINE",

    // Packet counters
    val encodedFrames: Long = 0L,
    val keyframes: Long = 0L,
    val videoPacketsSent: Long = 0L,
    val audioPacketsSent: Long = 0L,
    val videoFramesEncoded: Long = 0L,
    val audioFramesEncoded: Long = 0L,
    val videoBytesSent: Long = 0L,
    val audioBytesSent: Long = 0L,
    val rtmpBytesSent: Long = 0L
) {
    val durationFormatted: String
        get() {
            val hours = durationSeconds / 3600
            val minutes = (durationSeconds % 3600) / 60
            val seconds = durationSeconds % 60
            return if (hours > 0) {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

    val totalBitrateLabel: String
        get() {
            val totalKbps = videoBitrateKbps + audioBitrateKbps
            return if (totalKbps >= 1000) {
                String.format("%.1f Mbps", totalKbps / 1000.0)
            } else {
                "$totalKbps kbps"
            }
        }

    val formattedDataSent: String
        get() {
            val bytes = totalBytesSent
            return when {
                bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
                bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }
}
