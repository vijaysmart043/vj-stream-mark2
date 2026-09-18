package com.example.streaming.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

object VideoEncoderCapabilities {

    private const val TAG = "EncoderCapabilities"
    const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC

    data class EncoderDiscoveryResult(
        val codecInfo: MediaCodecInfo,
        val encoderName: String,
        val isHardwareAccelerated: Boolean,
        val supportedColorFormat: Int,
        val maxSupportedWidth: Int,
        val maxSupportedHeight: Int,
        val maxSupportedBitrate: Int
    )

    data class ResolvedResolution(
        val width: Int,
        val height: Int,
        val isFallback: Boolean = false,
        val reason: String? = null
    )

    /**
     * Preferred raw YUV color formats supported by MediaCodec when accepting byte buffers.
     * COLOR_FormatYUV420SemiPlanar (NV12 / NV21-compatible UV plane) is standard on mobile hardware.
     */
    private val PREFERRED_COLOR_FORMATS = intArrayOf(
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    )

    /**
     * Discovers the best matching H.264/AVC encoder.
     * Prioritizes hardware-accelerated encoders over software fallbacks.
     * Handles emulator and device sandbox limitations where querying specific components
     * or codec capabilities may throw or fail.
     */
    fun findBestEncoder(preferredMime: String = MIME_AVC): EncoderDiscoveryResult? {
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val candidateInfos = ArrayList<MediaCodecInfo>()

            for (info in codecList.codecInfos) {
                try {
                    if (!info.isEncoder) continue
                    val types = info.supportedTypes
                    val supportsAvc = types.any { it.equals(preferredMime, ignoreCase = true) }
                    if (supportsAvc) {
                        candidateInfos.add(info)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Skipping encoder component ${info.name}: ${e.message}")
                }
            }

            if (candidateInfos.isEmpty()) {
                Log.w(TAG, "No H.264 encoders found via MediaCodecList, attempting fallback encoder discovery")
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

            // Find first encoder whose capabilities can be cleanly queried
            for (chosenInfo in sorted) {
                try {
                    val caps = chosenInfo.getCapabilitiesForType(preferredMime)

                    // Select color format
                    var selectedColor = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    if (caps.colorFormats != null) {
                        for (preferred in PREFERRED_COLOR_FORMATS) {
                            if (caps.colorFormats.contains(preferred)) {
                                selectedColor = preferred
                                break
                            }
                        }
                    }

                    val videoCaps = caps.videoCapabilities
                    val maxWidth = videoCaps?.supportedWidths?.upper ?: 1920
                    val maxHeight = videoCaps?.supportedHeights?.upper ?: 1080
                    val maxBitrate = videoCaps?.bitrateRange?.upper ?: 20_000_000

                    val isHw = isHardware(chosenInfo)

                    Log.i(
                        TAG,
                        "Selected H.264 encoder: ${chosenInfo.name}, isHardware=$isHw, " +
                                "colorFormat=0x${Integer.toHexString(selectedColor)}, " +
                                "maxRes=${maxWidth}x${maxHeight}, maxBitrate=$maxBitrate"
                    )

                    return EncoderDiscoveryResult(
                        codecInfo = chosenInfo,
                        encoderName = chosenInfo.name,
                        isHardwareAccelerated = isHw,
                        supportedColorFormat = selectedColor,
                        maxSupportedWidth = maxWidth,
                        maxSupportedHeight = maxHeight,
                        maxSupportedBitrate = maxBitrate
                    )
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed inspecting capabilities for ${chosenInfo.name}: ${e.message}")
                }
            }

            return createFallbackEncoderResult(preferredMime)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed discovering encoder capabilities: ${e.message}", e)
            return createFallbackEncoderResult(preferredMime)
        }
    }

    private fun createFallbackEncoderResult(preferredMime: String): EncoderDiscoveryResult? {
        return try {
            val defaultCodec = MediaCodec.createEncoderByType(preferredMime)
            val info = defaultCodec.codecInfo
            val caps = try {
                info.getCapabilitiesForType(preferredMime)
            } catch (_: Throwable) {
                null
            }
            defaultCodec.release()

            val selectedColor = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            val videoCaps = caps?.videoCapabilities
            val maxWidth = videoCaps?.supportedWidths?.upper ?: 1280
            val maxHeight = videoCaps?.supportedHeights?.upper ?: 720
            val maxBitrate = videoCaps?.bitrateRange?.upper ?: 8_000_000

            EncoderDiscoveryResult(
                codecInfo = info,
                encoderName = info.name,
                isHardwareAccelerated = isHardware(info),
                supportedColorFormat = selectedColor,
                maxSupportedWidth = maxWidth,
                maxSupportedHeight = maxHeight,
                maxSupportedBitrate = maxBitrate
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

    /**
     * Validates whether requested width and height are supported by the selected encoder.
     * Automatically falls back (e.g. 1080p -> 720p -> 640x360) if not supported.
     */
    fun resolveSupportedResolution(
        discovery: EncoderDiscoveryResult?,
        requestedWidth: Int,
        requestedHeight: Int
    ): ResolvedResolution {
        if (discovery == null) {
            return ResolvedResolution(requestedWidth, requestedHeight)
        }

        val caps = try {
            discovery.codecInfo.getCapabilitiesForType(MIME_AVC).videoCapabilities
        } catch (_: Throwable) {
            null
        }

        if (caps != null) {
            try {
                if (caps.isSizeSupported(requestedWidth, requestedHeight)) {
                    return ResolvedResolution(requestedWidth, requestedHeight)
                }

                // Check if 720p (1280x720) is supported as fallback
                if (caps.isSizeSupported(1280, 720)) {
                    Log.w(TAG, "Requested resolution ${requestedWidth}x${requestedHeight} not supported. Falling back to 1280x720.")
                    return ResolvedResolution(1280, 720, isFallback = true, reason = "Resolution $requestedWidth x $requestedHeight not supported by ${discovery.encoderName}")
                }

                // Check if 640x360 is supported
                if (caps.isSizeSupported(640, 360)) {
                    Log.w(TAG, "Falling back to 640x360.")
                    return ResolvedResolution(640, 360, isFallback = true, reason = "Resolution $requestedWidth x $requestedHeight not supported. Fallen back to 640x360")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Size verification threw exception: ${e.message}")
            }
        }

        // Default to requested
        return ResolvedResolution(requestedWidth, requestedHeight)
    }
}
