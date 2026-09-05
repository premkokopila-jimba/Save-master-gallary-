package com.example.network

import java.net.URI
import java.net.URL
import java.util.regex.Pattern

object UrlValidator {

    private val URL_IN_TEXT_PATTERN = Pattern.compile("https?://[^\\s\"'<>]+")

    sealed class ValidationResult {
        data class Valid(val normalizedUrl: String) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        val matcher = URL_IN_TEXT_PATTERN.matcher(trimmed)
        return if (matcher.find()) {
            matcher.group(0)
        } else {
            trimmed
        }
    }

    fun validate(inputUrl: String?): ValidationResult {
        if (inputUrl.isNullOrBlank()) {
            return ValidationResult.Invalid("Please enter a video URL.")
        }

        val extracted = extractUrl(inputUrl) ?: inputUrl.trim()

        // Ensure scheme is http or https
        val schemeNormalized = if (!extracted.startsWith("http://", ignoreCase = true) &&
            !extracted.startsWith("https://", ignoreCase = true)
        ) {
            if (extracted.startsWith("://")) {
                "https$extracted"
            } else if (!extracted.contains("://")) {
                "https://$extracted"
            } else {
                return ValidationResult.Invalid("Only HTTP and HTTPS URLs are supported.")
            }
        } else {
            extracted
        }

        return try {
            val uri = URI(schemeNormalized)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return ValidationResult.Invalid("Only HTTP and HTTPS protocols are supported.")
            }
            if (uri.host.isNullOrBlank()) {
                return ValidationResult.Invalid("URL is missing a valid domain host.")
            }

            // Test if valid URL object can be created
            URL(schemeNormalized)

            ValidationResult.Valid(schemeNormalized)
        } catch (e: Exception) {
            ValidationResult.Invalid("Invalid URL format: ${e.localizedMessage ?: "Check the link syntax"}")
        }
    }

    fun isProbableUrl(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val trimmed = text.trim()
        return (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                URL_IN_TEXT_PATTERN.matcher(trimmed).find() ||
                (trimmed.contains(".") && !trimmed.contains(" ") && trimmed.length > 4))
    }
}

