package com.example.streaming.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Lightweight, allocation-free RMS and Peak amplitude calculator for 16-bit PCM audio.
 * Section 9 of VJStream Phase 3:
 * Calculates approximate input amplitude without expensive DSP/FFT analysis.
 * Provides normalized levels (0.0..1.0) and visual meter block formatting (████████░░).
 */
object AudioLevelCalculator {

    /**
     * Calculates the perceived RMS volume and absolute Peak level.
     * @param pcmData 16-bit signed PCM audio bytes (little-endian)
     * @param length Number of valid bytes in [pcmData]
     * @return Pair of (rmsLevel, peakLevel) in range 0.0f..1.0f
     */
    fun calculateLevels(pcmData: ByteArray, length: Int): Pair<Float, Float> {
        val numSamples = length / 2
        if (numSamples <= 0) return Pair(0f, 0f)

        var sumSquares = 0.0
        var maxSample = 0

        var i = 0
        while (i < length - 1) {
            val low = pcmData[i].toInt() and 0xFF
            val high = pcmData[i + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            val absSample = if (sample < 0) -sample else sample
            if (absSample > maxSample) {
                maxSample = absSample
            }
            sumSquares += (sample.toDouble() * sample.toDouble())
            i += 2
        }

        val rms = sqrt(sumSquares / numSamples)
        val normalizedRms = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        val normalizedPeak = (maxSample / 32768.0f).coerceIn(0f, 1f)

        // Convert to perceived dB scale (-60 dB to 0 dB)
        val rmsDb = if (normalizedRms > 0.0001f) {
            20f * log10(normalizedRms)
        } else {
            -60f
        }
        val perceivedLevel = ((rmsDb + 60f) / 60f).coerceIn(0f, 1f)

        return Pair(perceivedLevel, normalizedPeak)
    }

    /**
     * Formats normalized volume level (0.0..1.0) into a visual block string:
     * e.g. level 0.8 => "████████░░" (10 segments)
     */
    fun formatMeterBlocks(level: Float, totalBlocks: Int = 10): String {
        val clampedLevel = level.coerceIn(0f, 1f)
        val filledBlocks = kotlin.math.round(clampedLevel * totalBlocks).toInt().coerceIn(0, totalBlocks)
        val sb = StringBuilder(totalBlocks)
        for (i in 0 until filledBlocks) {
            sb.append("█")
        }
        for (i in filledBlocks until totalBlocks) {
            sb.append("░")
        }
        return sb.toString()
    }
}
