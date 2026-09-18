package com.example.youtube

import java.net.URI

/**
 * Validation result for YouTube Live streaming configuration.
 */
sealed class YouTubeValidationResult {
    object Valid : YouTubeValidationResult()
    data class Invalid(val reason: String) : YouTubeValidationResult()

    val isValid: Boolean get() = this is Valid
    val errorMessage: String? get() = (this as? Invalid)?.reason
}

/**
 * Robust validator for YouTube RTMP/RTMPS server URLs and stream keys.
 */
object YouTubeStreamValidator {
    private const val TAG = "VJStream/YouTube"

    /**
     * Validates that the server URL is not empty, is a valid URI,
     * and uses either rtmp:// or rtmps:// protocol with a valid host.
     */
    fun validateServerUrl(url: String): YouTubeValidationResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return YouTubeValidationResult.Invalid("Server URL cannot be empty")
        }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return YouTubeValidationResult.Invalid("Invalid RTMP URL: ${e.message}")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme == null) {
            return YouTubeValidationResult.Invalid("Invalid RTMP URL: Missing protocol scheme")
        }

        if (scheme != "rtmp" && scheme != "rtmps") {
            return YouTubeValidationResult.Invalid("Invalid RTMP URL: Must use rtmp:// or rtmps:// (preferred: rtmps://)")
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return YouTubeValidationResult.Invalid("Invalid RTMP URL: Missing valid host")
        }

        return YouTubeValidationResult.Valid
    }

    /**
     * Validates that the stream key is present and not blank after trimming whitespace.
     */
    fun validateStreamKey(streamKey: String): YouTubeValidationResult {
        val trimmed = streamKey.trim()
        if (trimmed.isEmpty()) {
            return YouTubeValidationResult.Invalid("Stream key cannot be empty")
        }
        return YouTubeValidationResult.Valid
    }

    /**
     * Complete validation for a [YouTubeStreamConfig].
     */
    fun validate(config: YouTubeStreamConfig): YouTubeValidationResult {
        val urlResult = validateServerUrl(config.serverUrl)
        if (!urlResult.isValid) return urlResult

        val keyResult = validateStreamKey(config.streamKey)
        if (!keyResult.isValid) return keyResult

        return YouTubeValidationResult.Valid
    }
}
