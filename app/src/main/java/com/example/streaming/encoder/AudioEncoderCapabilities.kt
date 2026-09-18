package com.example.streaming.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

object AudioEncoderCapabilities {

    private const val TAG = "AudioEncoderCaps"
    const val MIME_AAC = "audio/mp4a-latm"

    data class AudioEncoderDiscoveryResult(
        val codecInfo: MediaCodecInfo,
        val encoderName: String,
        val isHardwareAccelerated: Boolean,
        val supportedSampleRates: IntArray,
        val maxChannelCount: Int,
        val maxBitrate: Int
    )

    /**
     * Discovers the best matching AAC-LC audio encoder.
     * Prioritizes hardware encoders over software fallbacks.
     */
    fun findBestEncoder(preferredMime: String = MIME_AAC): AudioEncoderDiscoveryResult? {
        return try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val candidateInfos = ArrayList<MediaCodecInfo>()

            for (info in codecList.codecInfos) {
                try {
                    if (!info.isEncoder) continue
                    val types = info.supportedTypes
                    val supportsAac = types.any { it.equals(preferredMime, ignoreCase = true) }
                    if (supportsAac) {
                        candidateInfos.add(info)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Skipping audio encoder component ${info.name}: ${e.message}")
                }
            }

            if (candidateInfos.isEmpty()) {
                Log.w(TAG, "No AAC encoders found via MediaCodecList, creating fallback")
                return createFallbackEncoderResult(preferredMime)
            }

            // Sort: hardware encoders first, then software encoders
            val sorted = candidateInfos.sortedWith { a, b ->
                val aHw = isHardware(a)
                val bHw = isHardware(b)
                when {
                    aHw && !bHw -> -1
                    !aHw && bHw -> 1
                    else -> a.name.compareTo(b.name)
                }
            }

            for (chosenInfo in sorted) {
                try {
                    val caps = chosenInfo.getCapabilitiesForType(preferredMime)
                    val audioCaps = caps.audioCapabilities
                    val sampleRates = audioCaps?.supportedSampleRates ?: intArrayOf(48000, 44100, 16000)
                    val maxChannels = audioCaps?.maxInputChannelCount ?: 2
                    val maxBitrate = audioCaps?.bitrateRange?.upper ?: 320_000
                    val isHw = isHardware(chosenInfo)

                    Log.i(
                        TAG,
                        "Selected AAC encoder: ${chosenInfo.name}, isHardware=$isHw, maxChannels=$maxChannels, maxBitrate=$maxBitrate"
                    )

                    return AudioEncoderDiscoveryResult(
                        codecInfo = chosenInfo,
                        encoderName = chosenInfo.name,
                        isHardwareAccelerated = isHw,
                        supportedSampleRates = sampleRates,
                        maxChannelCount = maxChannels,
                        maxBitrate = maxBitrate
                    )
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed inspecting capabilities for ${chosenInfo.name}: ${e.message}")
                }
            }

            createFallbackEncoderResult(preferredMime)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed discovering audio encoder capabilities: ${e.message}", e)
            createFallbackEncoderResult(preferredMime)
        }
    }

    private fun createFallbackEncoderResult(preferredMime: String): AudioEncoderDiscoveryResult? {
        return try {
            val defaultCodec = MediaCodec.createEncoderByType(preferredMime)
            val info = defaultCodec.codecInfo
            val caps = try {
                info.getCapabilitiesForType(preferredMime)
            } catch (_: Throwable) {
                null
            }
            defaultCodec.release()

            val audioCaps = caps?.audioCapabilities
            val sampleRates = audioCaps?.supportedSampleRates ?: intArrayOf(48000, 44100)
            val maxChannels = audioCaps?.maxInputChannelCount ?: 2
            val maxBitrate = audioCaps?.bitrateRange?.upper ?: 192_000

            AudioEncoderDiscoveryResult(
                codecInfo = info,
                encoderName = info.name,
                isHardwareAccelerated = isHardware(info),
                supportedSampleRates = sampleRates,
                maxChannelCount = maxChannels,
                maxBitrate = maxBitrate
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Could not create default fallback encoder for $preferredMime: ${e.message}")
            null
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                val name = info.name.lowercase()
                !name.startsWith("omx.google.") &&
                        !name.startsWith("c2.android.") &&
                        !name.contains("sw") &&
                        !name.contains("software")
            }
        } catch (_: Throwable) {
            false
        }
    }
}
