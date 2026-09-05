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

    @Test
    fun testSocialVideoExtractor_PlatformDetection() {
        assertEquals(
            com.example.network.SocialVideoExtractor.Platform.TIKTOK,
            com.example.network.SocialVideoExtractor.detectPlatform("https://www.tiktok.com/@user/video/1234567890")
        )
        assertEquals(
            com.example.network.SocialVideoExtractor.Platform.TIKTOK,
            com.example.network.SocialVideoExtractor.detectPlatform("https://vm.tiktok.com/ZM8abcxyz/")
        )
        assertEquals(
            com.example.network.SocialVideoExtractor.Platform.INSTAGRAM,
            com.example.network.SocialVideoExtractor.detectPlatform("https://www.instagram.com/reel/C8XYZ123abc/")
        )
        assertEquals(
            com.example.network.SocialVideoExtractor.Platform.FACEBOOK,
            com.example.network.SocialVideoExtractor.detectPlatform("https://www.facebook.com/watch/?v=1015891234567890")
        )
        assertEquals(
            com.example.network.SocialVideoExtractor.Platform.FACEBOOK,
            com.example.network.SocialVideoExtractor.detectPlatform("https://fb.watch/mXz8172/")
        )
        assertEquals(
            com.example.network.SocialVideoExtractor.Platform.YOUTUBE,
            com.example.network.SocialVideoExtractor.detectPlatform("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            com.example.network.SocialVideoExtractor.Platform.YOUTUBE,
            com.example.network.SocialVideoExtractor.detectPlatform("https://youtu.be/dQw4w9WgXcQ")
        )
        assertEquals(
            com.example.network.SocialVideoExtractor.Platform.YOUTUBE,
            com.example.network.SocialVideoExtractor.detectPlatform("https://youtube.com/shorts/abcdefghijk")
        )
        assertEquals(
            com.example.network.SocialVideoExtractor.Platform.FACEBOOK,
            com.example.network.SocialVideoExtractor.detectPlatform("https://www.facebook.com/reel/123456789012345")
        )
        assertEquals(
            com.example.network.SocialVideoExtractor.Platform.FACEBOOK,
            com.example.network.SocialVideoExtractor.detectPlatform("https://www.facebook.com/share/r/7X8mNAbcde/")
        )
        assertEquals(
            com.example.network.SocialVideoExtractor.Platform.FACEBOOK,
            com.example.network.SocialVideoExtractor.detectPlatform("https://m.facebook.com/story.php?story_fbid=123&id=456")
        )
    }

    @Test
    fun testUrlValidator_ExtractUrlFromMessyText() {
        val messyText = "Check this video out: https://www.facebook.com/reel/123456789?mibextid=rS40aB7S9Ucbxw6v"
        val extracted = UrlValidator.extractUrl(messyText)
        assertEquals("https://www.facebook.com/reel/123456789?mibextid=rS40aB7S9Ucbxw6v", extracted)

        val validation = UrlValidator.validate(messyText)
        assertTrue(validation is UrlValidator.ValidationResult.Valid)
        assertEquals(
            "https://www.facebook.com/reel/123456789?mibextid=rS40aB7S9Ucbxw6v",
            (validation as UrlValidator.ValidationResult.Valid).normalizedUrl
        )
    }

    @Test
    fun testSocialVideoExtractor_UnescapeJsonUrl() {
        val raw = "https:\\/\\/video.fbcdn.net\\/v\\/t39.109-2\\/test.mp4?_nc_cat=101\\u0026efg=eyJ\\u00253D\\u00253D&amp;bytestart=0"
        val cleaned = com.example.network.SocialVideoExtractor.unescapeJsonUrl(raw)
        assertEquals(
            "https://video.fbcdn.net/v/t39.109-2/test.mp4?_nc_cat=101&efg=eyJ%3D%3D&bytestart=0",
            cleaned
        )
    }

    @Test
    fun testSocialVideoExtractor_ParseFacebookStreamsFromHtml() {
        val fakeHtml = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta property="og:title" content="Viral Facebook Video 2026" />
            <meta property="og:image" content="https://scontent.fbcdn.net/v/thumb.jpg" />
            <script>
            var videoData = {
                "browser_native_hd_url": "https:\/\/video.fbcdn.net\/v\/t39\/hd_stream.mp4?token=123",
                "browser_native_sd_url": "https:\/\/video.fbcdn.net\/v\/t39\/sd_stream.mp4?token=456"
            };
            </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val videoInfo = com.example.network.SocialVideoExtractor.parseFacebookStreamsFromHtmlForTesting(
            html = fakeHtml,
            originalUrl = "https://www.facebook.com/reel/987654321"
        )

        org.junit.Assert.assertNotNull(videoInfo)
        assertEquals("Viral Facebook Video 2026", videoInfo?.title)
        assertEquals("https://video.fbcdn.net/v/t39/hd_stream.mp4?token=123", videoInfo?.finalUrl)
        assertEquals(2, videoInfo?.qualityOptions?.size)
        assertEquals("HD Quality (720p)", videoInfo?.qualityOptions?.get(0)?.label)
        assertEquals("SD Quality (480p)", videoInfo?.qualityOptions?.get(1)?.label)
    }

    @Test
    fun testSocialVideoExtractor_ParseFacebookOgVideoFallback() {
        val fakeHtml = """
            <html>
            <head>
            <meta property="og:title" content="Nature FB Reel" />
            <meta property="og:video" content="https://video.fbcdn.net/v/nature.mp4" />
            <meta property="og:image" content="https://scontent.fbcdn.net/thumb.jpg" />
            </head>
            </html>
        """.trimIndent()

        val videoInfo = com.example.network.SocialVideoExtractor.parseFacebookStreamsFromHtmlForTesting(
            html = fakeHtml,
            originalUrl = "https://fb.watch/sampleWatch123/"
        )

        org.junit.Assert.assertNotNull(videoInfo)
        assertEquals("Nature FB Reel", videoInfo?.title)
        assertEquals("https://video.fbcdn.net/v/nature.mp4", videoInfo?.finalUrl)
    }
}
