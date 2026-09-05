package com.example.network

import com.example.model.VideoInfo
import com.example.model.VideoQualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

class VideoDownloadException(
    val userMessage: String,
    cause: Throwable? = null
) : Exception(userMessage, cause)

object UrlAnalyzer {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    suspend fun analyze(url: String): VideoInfo = withContext(Dispatchers.IO) {
        val validation = UrlValidator.validate(url)
        if (validation is UrlValidator.ValidationResult.Invalid) {
            throw VideoDownloadException(validation.reason)
        }
        val targetUrl = (validation as UrlValidator.ValidationResult.Valid).normalizedUrl

        // Try HEAD first
        var response: Response? = null
        var finalUrl = targetUrl
        try {
            val headRequest = Request.Builder()
                .url(targetUrl)
                .head()
                .header("User-Agent", USER_AGENT)
                .build()

            val headResponse = httpClient.newCall(headRequest).execute()
            if (headResponse.isSuccessful || headResponse.code == 405 || headResponse.code == 403 || headResponse.code == 302 || headResponse.code == 301) {
                if (headResponse.code == 405 || headResponse.header("Content-Type") == null) {
                    // Method not allowed or incomplete headers, fallback to GET range 0-0
                    headResponse.close()
                    response = executeProbeGet(targetUrl)
                } else {
                    response = headResponse
                }
            } else {
                headResponse.close()
                response = executeProbeGet(targetUrl)
            }
        } catch (e: Exception) {
            // HEAD might fail on some servers, try GET probe
            try {
                response = executeProbeGet(targetUrl)
            } catch (probeEx: Exception) {
                mapAndThrowException(probeEx)
            }
        }

        val res = response ?: throw VideoDownloadException("Unable to establish connection to the video server.")

        res.use { r ->
            finalUrl = r.request.url.toString()
            val statusCode = r.code

            if (!r.isSuccessful && statusCode != 206) {
                mapStatusCodeToException(statusCode)
            }

            val contentType = (r.header("Content-Type") ?: "application/octet-stream").lowercase(Locale.ROOT)
            val contentDisposition = r.header("Content-Disposition")
            val acceptRanges = r.header("Accept-Ranges")
            val contentRange = r.header("Content-Range")
            val contentLengthHeader = r.header("Content-Length")

            var contentLength: Long = -1
            if (contentLengthHeader != null) {
                try {
                    contentLength = contentLengthHeader.toLong()
                } catch (_: NumberFormatException) {
                }
            }

            if (contentRange != null && contentLength <= 0) {
                // Example: bytes 0-0/1048576
                val totalStr = contentRange.substringAfterLast("/")
                try {
                    contentLength = totalStr.toLong()
                } catch (_: NumberFormatException) {
                }
            }

            // Check if this is an HTML webpage
            if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                throw VideoDownloadException(
                    "This URL points to an HTML webpage rather than a direct downloadable video. Please provide a direct video link (.mp4, .webm)."
                )
            }

            val filename = FilenameSanitizer.extractAndSanitize(
                contentDisposition = contentDisposition,
                url = finalUrl,
                mimeType = contentType
            )

            // Determine if downloadable video
            val isVideoMime = contentType.startsWith("video/") ||
                    contentType == "application/octet-stream" ||
                    contentType == "application/x-mpegurl" ||
                    contentType == "binary/octet-stream"

            val isVideoExtension = filename.endsWith(".mp4", ignoreCase = true) ||
                    filename.endsWith(".webm", ignoreCase = true) ||
                    filename.endsWith(".mkv", ignoreCase = true) ||
                    filename.endsWith(".mov", ignoreCase = true) ||
                    filename.endsWith(".avi", ignoreCase = true) ||
                    filename.endsWith(".3gp", ignoreCase = true)

            if (!isVideoMime && !isVideoExtension) {
                throw VideoDownloadException(
                    "The link does not appear to be a supported video file (Content-Type: $contentType). Only direct video files are supported."
                )
            }

            val supportsRange = (acceptRanges != null && acceptRanges.contains("bytes", ignoreCase = true)) ||
                    contentRange != null || statusCode == 206

            val qualityOptions = mutableListOf<VideoQualityOption>()
            val formatLabel = filename.substringAfterLast(".", "MP4").uppercase(Locale.ROOT)
            qualityOptions.add(
                VideoQualityOption(
                    label = "Original Quality ($formatLabel)",
                    url = finalUrl,
                    resolution = if (contentLength > 100 * 1024 * 1024) "High Definition" else "Standard",
                    format = formatLabel,
                    estimatedBytes = contentLength
                )
            )

            return@withContext VideoInfo(
                originalUrl = targetUrl,
                finalUrl = finalUrl,
                suggestedFilename = filename,
                contentLength = contentLength,
                contentType = contentType,
                supportsRange = supportsRange,
                qualityOptions = qualityOptions,
                isDirectVideo = true
            )
        }
    }

    private fun executeProbeGet(url: String): Response {
        val probeRequest = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=0-1024")
            .build()
        return httpClient.newCall(probeRequest).execute()
    }

    fun mapStatusCodeToException(code: Int): Nothing {
        val message = when (code) {
            400 -> "Bad request. The video server could not understand the request."
            401 -> "This video requires authorization or login credentials, which are not supported."
            403 -> "Download isn't available from this source. The server denied access to the video (HTTP 403)."
            404 -> "Video not found at this address. The link may have expired or been removed (HTTP 404)."
            408 -> "Request timed out while connecting to the video server (HTTP 408)."
            410 -> "This video is no longer available at this address (HTTP 410 Gone)."
            416 -> "Requested range not satisfiable by the video server."
            429 -> "Too many requests. The server is rate-limiting downloads; please try again later."
            500 -> "The remote video server encountered an internal server error (HTTP 500)."
            502, 503, 504 -> "The remote video server is temporarily unavailable or timed out."
            else -> "Server responded with error code HTTP $code."
        }
        throw VideoDownloadException(message)
    }

    private fun mapAndThrowException(e: Throwable): Nothing {
        when (e) {
            is VideoDownloadException -> throw e
            is UnknownHostException -> throw VideoDownloadException("No internet connection or server domain could not be resolved.")
            is SocketTimeoutException -> throw VideoDownloadException("Connection to the video server timed out. Check your internet connection.")
            is IOException -> throw VideoDownloadException("Network communication error: ${e.localizedMessage ?: "Failed to connect"}")
            else -> throw VideoDownloadException("Unexpected error analyzing URL: ${e.localizedMessage ?: "Please try again"}")
        }
    }
}
