package com.example.network

import java.net.URI
import java.net.URL

object UrlValidator {

    sealed class ValidationResult {
        data class Valid(val normalizedUrl: String) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    fun validate(inputUrl: String?): ValidationResult {
        if (inputUrl.isNullOrBlank()) {
            return ValidationResult.Invalid("Please enter a video URL.")
        }

        val trimmed = inputUrl.trim()

        // Ensure scheme is http or https
        val schemeNormalized = if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            if (trimmed.startsWith("://")) {
                "https$trimmed"
            } else if (!trimmed.contains("://")) {
                "https://$trimmed"
            } else {
                return ValidationResult.Invalid("Only HTTP and HTTPS URLs are supported.")
            }
        } else {
            trimmed
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
                (trimmed.contains(".") && !trimmed.contains(" ") && trimmed.length > 4))
    }
}
