package com.example.streaming.encoder

data class VideoEncoderStats(
    val encoderName: String = "None",
    val isHardwareAccelerated: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val targetFps: Int = 30,
    val targetBitrateBps: Int = 0,
    val currentFps: Double = 0.0,
    val currentBitrateKbps: Int = 0,
    val encodedFrameCount: Long = 0L,
    val keyframeCount: Long = 0L,
    val errorCount: Long = 0L,
    val averageFrameSizeBytes: Long = 0L,
    val lastError: String? = null
) {
    val resolutionLabel: String get() = "${width}x$height"
    val bitrateLabel: String
        get() {
            return if (currentBitrateKbps >= 1000) {
                String.format("%.1f Mbps", currentBitrateKbps / 1000.0)
            } else if (currentBitrateKbps > 0) {
                "$currentBitrateKbps kbps"
            } else {
                "${targetBitrateBps / 1_000_000.0} Mbps"
            }
        }
}
