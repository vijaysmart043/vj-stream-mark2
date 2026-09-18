package com.example.streaming.rtmp

/**
 * Validates RTMP and RTMPS endpoint URLs according to standard specifications.
 * Ensures that connections are only attempted for valid endpoints.
 */
object RtmpUrlValidator {

    sealed class ValidationResult {
        data class Valid(
            val isSsl: Boolean,
            val host: String,
            val port: Int,
            val app: String,
            val normalizedUrl: String
        ) : ValidationResult()

        data class Invalid(val reason: String) : ValidationResult()
    }

    /**
     * Validates an RTMP/RTMPS URL.
     *
     * @param rawUrl The raw URL string to validate
     * @return [ValidationResult.Valid] if the URL meets all criteria, or [ValidationResult.Invalid] with reason
     */
    fun validate(rawUrl: String?): ValidationResult {
        if (rawUrl.isNullOrBlank()) {
            return ValidationResult.Invalid("RTMP URL cannot be empty")
        }

        val trimmed = rawUrl.trim()
        val isRtmp = trimmed.startsWith("rtmp://", ignoreCase = true)
        val isRtmps = trimmed.startsWith("rtmps://", ignoreCase = true)

        if (!isRtmp && !isRtmps) {
            return ValidationResult.Invalid("Unsupported protocol: URL must begin with rtmp:// or rtmps://")
        }

        val withoutScheme = if (isRtmps) {
            trimmed.substring(8)
        } else {
            trimmed.substring(7)
        }

        if (withoutScheme.isBlank() || withoutScheme.startsWith("/")) {
            return ValidationResult.Invalid("Malformed RTMP URL: missing host")
        }

        val hostPart = withoutScheme.substringBefore("/")
        if (hostPart.isBlank()) {
            return ValidationResult.Invalid("Malformed RTMP URL: host cannot be empty")
        }

        val defaultPort = if (isRtmps) 443 else 1935
        val host: String
        val port: Int

        if (hostPart.contains(":")) {
            host = hostPart.substringBefore(":")
            val portStr = hostPart.substringAfter(":")
            val parsedPort = portStr.toIntOrNull()
            if (parsedPort == null || parsedPort !in 1..65535) {
                return ValidationResult.Invalid("Malformed RTMP URL: invalid port '$portStr'")
            }
            port = parsedPort
        } else {
            host = hostPart
            port = defaultPort
        }

        if (host.isBlank()) {
            return ValidationResult.Invalid("Malformed RTMP URL: host cannot be empty")
        }

        val appPart = if (withoutScheme.contains("/")) {
            withoutScheme.substringAfter("/").trimEnd('/')
        } else {
            "live2"
        }

        return ValidationResult.Valid(
            isSsl = isRtmps,
            host = host,
            port = port,
            app = if (appPart.isNotBlank()) appPart else "live2",
            normalizedUrl = trimmed
        )
    }

    /**
     * Helper to check if a URL is valid.
     */
    fun isValid(url: String?): Boolean = validate(url) is ValidationResult.Valid
}
