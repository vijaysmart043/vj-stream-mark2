package com.example.streaming.encoder

class EncodedVideoFrame(
    val nalData: ByteArray,
    val isKeyframe: Boolean,
    val isConfig: Boolean,
    val timestampUs: Long,
    val size: Int = nalData.size
) {
    val timestampMs: Long get() = timestampUs / 1000L

    /**
     * NAL unit type according to ITU-T H.264:
     * 7 = SPS (Sequence Parameter Set)
     * 8 = PPS (Picture Parameter Set)
     * 5 = IDR (Instantaneous Decoding Refresh / Keyframe)
     * 1 = Non-IDR Slice (P/B Frame)
     */
    val nalUnitType: Int
        get() = if (nalData.isNotEmpty()) nalData[0].toInt() and 0x1F else 0
}
