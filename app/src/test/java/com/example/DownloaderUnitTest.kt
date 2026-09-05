package com.example

import com.example.model.DownloadEntity
import com.example.model.DownloadStatus
import com.example.network.FilenameSanitizer
import com.example.network.UrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderUnitTest {

    @Test
    fun testUrlValidation_ValidUrls() {
        val result1 = UrlValidator.validate("https://example.com/videos/sample.mp4")
        assertTrue(result1 is UrlValidator.ValidationResult.Valid)
        assertEquals(
            "https://example.com/videos/sample.mp4",
            (result1 as UrlValidator.ValidationResult.Valid).normalizedUrl
        )

        val result2 = UrlValidator.validate("http://example.org/clip.webm?token=123")
        assertTrue(result2 is UrlValidator.ValidationResult.Valid)
    }

    @Test
    fun testUrlValidation_AddsHttpsScheme() {
        val result = UrlValidator.validate("example.com/video.mp4")
        assertTrue(result is UrlValidator.ValidationResult.Valid)
        assertEquals(
            "https://example.com/video.mp4",
            (result as UrlValidator.ValidationResult.Valid).normalizedUrl
        )
    }

    @Test
    fun testUrlValidation_InvalidUrls() {
        val resultEmpty = UrlValidator.validate("")
        assertTrue(resultEmpty is UrlValidator.ValidationResult.Invalid)

        val resultFtp = UrlValidator.validate("ftp://server.com/video.mp4")
        assertTrue(resultFtp is UrlValidator.ValidationResult.Invalid)

        val resultMalformed = UrlValidator.validate("https://")
        assertTrue(resultMalformed is UrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun testUrlValidator_IsProbableUrl() {
        assertTrue(UrlValidator.isProbableUrl("https://example.com/movie.mp4"))
        assertTrue(UrlValidator.isProbableUrl("example.com/video"))
        assertFalse(UrlValidator.isProbableUrl("just random text with spaces"))
        assertFalse(UrlValidator.isProbableUrl(null))
    }

    @Test
    fun testFilenameSanitizer_BasicUrl() {
        val filename = FilenameSanitizer.extractAndSanitize(
            contentDisposition = null,
            url = "https://cdn.example.com/movies/holiday_trip.mp4",
            mimeType = "video/mp4"
        )
        assertEquals("holiday_trip.mp4", filename)
    }

    @Test
    fun testFilenameSanitizer_UrlWithQueryParams() {
        val filename = FilenameSanitizer.extractAndSanitize(
            contentDisposition = null,
            url = "https://cdn.example.com/stream/video.mp4?auth=xyz&exp=9999",
            mimeType = "video/mp4"
        )
        assertEquals("video.mp4", filename)
    }

    @Test
    fun testFilenameSanitizer_ContentDispositionHeader() {
        val filename = FilenameSanitizer.extractAndSanitize(
            contentDisposition = "attachment; filename=\"nature_documentary.mp4\"",
            url = "https://cdn.example.com/download?id=45",
            mimeType = "video/mp4"
        )
        assertEquals("nature_documentary.mp4", filename)
    }

    @Test
    fun testFilenameSanitizer_PathTraversalAttackPrevention() {
        val filename = FilenameSanitizer.extractAndSanitize(
            contentDisposition = "attachment; filename=\"../../../../etc/passwd.mp4\"",
            url = "https://cdn.example.com/video",
            mimeType = "video/mp4"
        )
        assertFalse(filename.contains("/"))
        assertFalse(filename.contains("\\"))
        assertFalse(filename.contains(".."))
    }

    @Test
    fun testFilenameSanitizer_MimeTypeExtensionFallback() {
        val filename = FilenameSanitizer.extractAndSanitize(
            contentDisposition = null,
            url = "https://cdn.example.com/download/stream_file",
            mimeType = "video/webm"
        )
        assertTrue(filename.endsWith(".webm"))
    }

    @Test
    fun testDownloadStatus_ActiveTransitions() {
        assertTrue(DownloadStatus.QUEUED.isActive)
        assertTrue(DownloadStatus.PREPARING.isActive)
        assertTrue(DownloadStatus.DOWNLOADING.isActive)
        assertFalse(DownloadStatus.PAUSED.isActive)
        assertFalse(DownloadStatus.COMPLETED.isActive)
        assertFalse(DownloadStatus.FAILED.isActive)
        assertFalse(DownloadStatus.CANCELLED.isActive)
    }

    @Test
    fun testDownloadEntity_ProgressCalculation() {
        val entity = DownloadEntity(
            id = "test_1",
            url = "https://example.com/v.mp4",
            filename = "v.mp4",
            totalBytes = 1000L,
            downloadedBytes = 500L,
            localFilePath = "",
            mimeType = "video/mp4",
            status = DownloadStatus.DOWNLOADING
        )
        assertEquals(50, entity.progressPercent)
        assertEquals(0.5f, entity.progressFraction, 0.001f)
    }
}
