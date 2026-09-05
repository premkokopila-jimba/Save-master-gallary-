package com.example.network

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

object FilenameSanitizer {

    private val INVALID_CHARS_REGEX = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1F]")
    private val CONTENT_DISPOSITION_PATTERN =
        Pattern.compile("filename\\*?=(?:UTF-8'')?[\"']?([^\"';]+)[\"']?", Pattern.CASE_INSENSITIVE)

    fun extractAndSanitize(
        contentDisposition: String?,
        url: String?,
        mimeType: String? = null
    ): String {
        var rawName: String? = null

        // 1. Try Content-Disposition
        if (!contentDisposition.isNullOrBlank()) {
            val matcher = CONTENT_DISPOSITION_PATTERN.matcher(contentDisposition)
            if (matcher.find()) {
                rawName = matcher.group(1)?.trim()
                try {
                    rawName = URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
                } catch (_: Exception) {
                }
            }
        }

        // 2. Try URL path
        if (rawName.isNullOrBlank() && !url.isNullOrBlank()) {
            try {
                val cleanUrl = url.split("?")[0].split("#")[0]
                val lastSegment = cleanUrl.substringAfterLast("/")
                if (lastSegment.isNotBlank()) {
                    rawName = URLDecoder.decode(lastSegment, StandardCharsets.UTF_8.name())
                }
            } catch (_: Exception) {
            }
        }

        // 3. Fallback to timestamp
        if (rawName.isNullOrBlank()) {
            rawName = "video_${System.currentTimeMillis()}"
        }

        return sanitize(rawName, mimeType)
    }

    fun sanitize(inputName: String, mimeType: String? = null): String {
        // Strip path traversal attempts and invalid characters
        var cleaned = inputName.replace("\\", "/")
        cleaned = cleaned.substringAfterLast("/")
        cleaned = INVALID_CHARS_REGEX.matcher(cleaned).replaceAll("_")
        cleaned = cleaned.replace("..", "_").trim('.', ' ')

        if (cleaned.isBlank()) {
            cleaned = "video_${System.currentTimeMillis()}"
        }

        // Separate name and extension
        val dotIndex = cleaned.lastIndexOf('.')
        var baseName: String
        var extension: String

        if (dotIndex > 0 && dotIndex < cleaned.length - 1) {
            baseName = cleaned.substring(0, dotIndex)
            extension = cleaned.substring(dotIndex + 1).lowercase(Locale.ROOT)
        } else {
            baseName = cleaned
            extension = ""
        }

        // Truncate baseName if too long (max 100 chars)
        if (baseName.length > 100) {
            baseName = baseName.substring(0, 100)
        }

        // Determine appropriate video extension
        val validVideoExts = setOf("mp4", "webm", "mkv", "mov", "avi", "3gp", "m4v", "ts")
        if (!validVideoExts.contains(extension)) {
            val fallbackExt = when (mimeType?.lowercase(Locale.ROOT)?.trim()) {
                "video/webm" -> "webm"
                "video/x-matroska" -> "mkv"
                "video/quicktime" -> "mov"
                "video/3gpp" -> "3gp"
                "video/x-msvideo" -> "avi"
                else -> "mp4"
            }
            extension = fallbackExt
        }

        return "$baseName.$extension"
    }
}
