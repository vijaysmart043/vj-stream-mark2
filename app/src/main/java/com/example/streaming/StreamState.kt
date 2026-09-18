package com.example.streaming

enum class StreamStatus {
    OFFLINE,
    INITIALIZING,
    CONNECTING,
    LIVE,
    RECONNECTING,
    ERROR,
    STOPPING;

    val isStreaming: Boolean
        get() = this == LIVE || this == RECONNECTING

    val isBusy: Boolean
        get() = this == INITIALIZING || this == CONNECTING || this == STOPPING
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
    val fps: Double = 0.0,
    val videoBitrateKbps: Int = 0,
    val audioBitrateKbps: Int = 0,
    val droppedFrames: Long = 0L,
    val networkStatus: NetworkHealth = NetworkHealth.GOOD,
    val audioEnabled: Boolean = true,
    val statusMessage: String = "Ready to stream",
    val isEncoderActive: Boolean = false,
    val encoderName: String = "None",
    val encodedFrames: Long = 0L,
    val keyframes: Long = 0L
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
}
