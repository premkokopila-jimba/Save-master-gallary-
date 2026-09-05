package com.example.network

import com.example.model.VideoInfo
import com.example.model.VideoQualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object SocialVideoExtractor {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val USER_AGENT_MOBILE =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    private const val USER_AGENT_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val USER_AGENT_BOT =
        "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    private const val USER_AGENT_TWITTER =
        "Twitterbot/1.0"
    private const val USER_AGENT_IOS =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    enum class Platform {
        TIKTOK,
        INSTAGRAM,
        FACEBOOK,
        YOUTUBE,
        TWITTER,
        GENERIC
    }

    fun detectPlatform(url: String): Platform {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.contains("tiktok.com") -> Platform.TIKTOK
            lower.contains("instagram.com") -> Platform.INSTAGRAM
            lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.com") -> Platform.FACEBOOK
            lower.contains("youtube.com") || lower.contains("youtu.be") -> Platform.YOUTUBE
            lower.contains("twitter.com") || lower.contains("x.com") -> Platform.TWITTER
            else -> Platform.GENERIC
        }
    }

    suspend fun extract(url: String): VideoInfo = withContext(Dispatchers.IO) {
        val platform = detectPlatform(url)
        when (platform) {
            Platform.TIKTOK -> extractTikTok(url)
            Platform.INSTAGRAM -> extractInstagram(url)
            Platform.FACEBOOK -> extractFacebook(url)
            Platform.YOUTUBE -> extractYouTube(url)
            Platform.TWITTER, Platform.GENERIC -> {
                // Try Cobalt / generic extractor
                tryCobaltExtractor(url, if (platform == Platform.TWITTER) "Twitter / X" else "Web Video")
                    ?: extractGenericWebpage(url)
            }
        }
    }

    // ==================== TIKTOK ====================

    private suspend fun extractTikTok(url: String): VideoInfo {
        // Method 1: TikWM API (Fast, Free, No Watermark)
        try {
            val apiUrl = "https://www.tikwm.com/api/?url=" + URLEncoder.encode(url, "UTF-8")
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", USER_AGENT_MOBILE)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    if (json.optInt("code", -1) == 0) {
                        val data = json.optJSONObject("data")
                        if (data != null) {
                            var videoUrl = data.optString("play").ifBlank { data.optString("wmplay") }
                            if (videoUrl.startsWith("/")) {
                                videoUrl = "https://www.tikwm.com$videoUrl"
                            }
                            if (videoUrl.isNotBlank()) {
                                val title = data.optString("title").ifBlank { "TikTok Video" }
                                val author = data.optJSONObject("author")?.optString("nickname")
                                    ?: data.optJSONObject("author")?.optString("unique_id")
                                val cover = data.optString("cover").ifBlank { data.optString("origin_cover") }
                                val size = data.optLong("size", -1L)

                                val filename = sanitizeTitleToFilename(title, "TikTok", "mp4")
                                val qualityOptions = mutableListOf<VideoQualityOption>()
                                val hdPlay = data.optString("hdplay")
                                if (hdPlay.isNotBlank()) {
                                    val hdUrl = if (hdPlay.startsWith("/")) "https://www.tikwm.com$hdPlay" else hdPlay
                                    qualityOptions.add(
                                        VideoQualityOption("HD (No Watermark)", hdUrl, "1080p", "MP4", size)
                                    )
                                }
                                qualityOptions.add(
                                    VideoQualityOption("Standard (No Watermark)", videoUrl, "720p", "MP4", size)
                                )

                                return VideoInfo(
                                    originalUrl = url,
                                    finalUrl = videoUrl,
                                    suggestedFilename = filename,
                                    contentLength = size,
                                    contentType = "video/mp4",
                                    supportsRange = true,
                                    qualityOptions = qualityOptions,
                                    isDirectVideo = true,
                                    title = title,
                                    author = if (!author.isNullOrBlank()) "@$author" else null,
                                    thumbnailUrl = cover.ifBlank { null },
                                    platformName = "TikTok"
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Method 2: Cobalt API fallback
        tryCobaltExtractor(url, "TikTok")?.let { return it }

        // Method 3: HTML Scraper fallback
        val htmlInfo = extractGenericWebpage(url, platformName = "TikTok")
        return htmlInfo
    }

    // ==================== INSTAGRAM ====================

    private suspend fun extractInstagram(url: String): VideoInfo {
        // Extract reel or post shortcode
        val shortcodeMatcher = Pattern.compile("(?:reel|p|tv)/([A-Za-z0-9_-]+)").matcher(url)
        val shortcode = if (shortcodeMatcher.find()) shortcodeMatcher.group(1) else null

        // Method 1: Embed Page Scraping
        if (shortcode != null) {
            try {
                val embedUrl = "https://www.instagram.com/reel/$shortcode/embed/captioned/"
                val request = Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", USER_AGENT_IOS)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val html = response.body?.string().orEmpty()
                        val videoMatcher = Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\"").matcher(html)
                        if (videoMatcher.find()) {
                            val rawUrl = videoMatcher.group(1)
                            val cleanUrl = unescapeJsonUrl(rawUrl)
                            val thumbMatcher = Pattern.compile("class=\"EmbeddedMediaImage\"[^>]*src=\"([^\"]+)\"").matcher(html)
                            val thumb = if (thumbMatcher.find()) unescapeJsonUrl(thumbMatcher.group(1)) else null
                            val captionMatcher = Pattern.compile("class=\"Caption\"[^>]*>([^<]+)<").matcher(html)
                            val caption = if (captionMatcher.find()) captionMatcher.group(1).trim() else "Instagram Reel"

                            val filename = sanitizeTitleToFilename(caption, "Instagram", "mp4")
                            return VideoInfo(
                                originalUrl = url,
                                finalUrl = cleanUrl,
                                suggestedFilename = filename,
                                contentLength = -1L,
                                contentType = "video/mp4",
                                supportsRange = true,
                                qualityOptions = listOf(
                                    VideoQualityOption("Original Quality", cleanUrl, "HD", "MP4", -1L)
                                ),
                                isDirectVideo = true,
                                title = caption,
                                author = null,
                                thumbnailUrl = thumb,
                                platformName = "Instagram"
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Method 2: Cobalt API fallback
        tryCobaltExtractor(url, "Instagram")?.let { return it }

        // Method 3: OpenGraph Bot Crawler
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT_BOT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string().orEmpty()
                    val videoMatcher = Pattern.compile("<meta\\s+property=\"og:video(?::secure_url|:url)?\"\\s+content=\"([^\"]+)\"").matcher(html)
                    if (videoMatcher.find()) {
                        val videoUrl = unescapeJsonUrl(videoMatcher.group(1))
                        val titleMatcher = Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"").matcher(html)
                        val title = if (titleMatcher.find()) titleMatcher.group(1) else "Instagram Video"
                        val imageMatcher = Pattern.compile("<meta\\s+property=\"og:image\"\\s+content=\"([^\"]+)\"").matcher(html)
                        val thumb = if (imageMatcher.find()) imageMatcher.group(1) else null

                        return VideoInfo(
                            originalUrl = url,
                            finalUrl = videoUrl,
                            suggestedFilename = sanitizeTitleToFilename(title, "Instagram", "mp4"),
                            contentLength = -1L,
                            contentType = "video/mp4",
                            supportsRange = true,
                            qualityOptions = listOf(VideoQualityOption("Original Quality", videoUrl, "HD", "MP4", -1L)),
                            isDirectVideo = true,
                            title = title,
                            author = null,
                            thumbnailUrl = thumb,
                            platformName = "Instagram"
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        return extractGenericWebpage(url, platformName = "Instagram")
    }

    // ==================== FACEBOOK ====================

    private suspend fun extractFacebook(url: String): VideoInfo {
        // Step 1: Follow redirects (handles fb.watch, facebook.com/share/r/, facebook.com/share/v/, etc.)
        val resolvedUrl = resolveFacebookUrl(url)

        // Method 1: Facebook Video Embed Widget (Public plugin endpoint, never requires login)
        tryFacebookEmbed(resolvedUrl, url)?.let { return it }

        // Method 2: Facebook External Hit Bot Scraper (Facebook serves OpenGraph metadata without login wall)
        tryFacebookBotCrawler(resolvedUrl, url)?.let { return it }

        // Method 3: FDOWN / FBDown API Engine (High reliability Facebook video processor)
        tryFdownExtractor(resolvedUrl, url)?.let { return it }

        // Method 4: Mobile / MBasic Facebook Page (bypasses desktop JavaScript walls)
        tryFacebookMobileBasic(resolvedUrl, url)?.let { return it }

        // Method 5: TwitterBot Crawler Scraper
        tryFacebookTwitterBot(resolvedUrl, url)?.let { return it }

        // Method 6: Modern Cobalt instances
        tryCobaltExtractor(resolvedUrl, "Facebook")?.let { return it }
        if (resolvedUrl != url) {
            tryCobaltExtractor(url, "Facebook")?.let { return it }
        }

        // Method 7: Generic Webpage parser
        try {
            return extractGenericWebpage(resolvedUrl, platformName = "Facebook")
        } catch (_: Exception) {
            if (resolvedUrl != url) {
                try {
                    return extractGenericWebpage(url, platformName = "Facebook")
                } catch (_: Exception) {
                }
            }
        }

        throw VideoDownloadException(
            "Could not find a downloadable video at this Facebook link. Please make sure the video or reel is public and not private or deleted."
        )
    }

    private fun resolveFacebookUrl(startUrl: String): String {
        var current = startUrl.trim()
        for (i in 0 until 5) {
            try {
                val req = Request.Builder()
                    .url(current)
                    .head()
                    .header("User-Agent", USER_AGENT_BOT)
                    .build()
                httpClient.newCall(req).execute().use { res ->
                    val location = res.header("Location")
                    if (!location.isNullOrBlank()) {
                        current = if (location.startsWith("http")) location else URI(current).resolve(location).toString()
                    } else {
                        current = res.request.url.toString()
                        return current
                    }
                }
            } catch (_: Exception) {
                break
            }
        }
        return current
    }

    private fun parseFacebookStreamsFromHtml(html: String, originalUrl: String): VideoInfo? {
        if (html.isBlank()) return null

        var hdUrl: String? = null
        var sdUrl: String? = null

        // 1. HD Regex Patterns
        val hdPatterns = listOf(
            Pattern.compile("(?:\"|&quot;|\\\\\"|')browser_native_hd_url(?:\"|&quot;|\\\\\"|')\\s*:\\s*(?:\"|&quot;|\\\\\"|')([^\"'&]+)"),
            Pattern.compile("(?:\"|&quot;|\\\\\"|')playable_url_quality_hd(?:\"|&quot;|\\\\\"|')\\s*:\\s*(?:\"|&quot;|\\\\\"|')([^\"'&]+)"),
            Pattern.compile("(?:\"|&quot;|\\\\\"|')hd_src(?:\"|&quot;|\\\\\"|')\\s*:\\s*(?:\"|&quot;|\\\\\"|')([^\"'&]+)"),
            Pattern.compile("(?:\"|&quot;|\\\\\"|')hd_src_no_ratelimit(?:\"|&quot;|\\\\\"|')\\s*:\\s*(?:\"|&quot;|\\\\\"|')([^\"'&]+)")
        )
        for (pattern in hdPatterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val candidate = unescapeJsonUrl(matcher.group(1))
                if (candidate.startsWith("http") && (candidate.contains(".mp4") || candidate.contains("fbcdn.net") || candidate.contains("video"))) {
                    hdUrl = candidate
                    break
                }
            }
        }

        // 2. SD Regex Patterns
        val sdPatterns = listOf(
            Pattern.compile("(?:\"|&quot;|\\\\\"|')browser_native_sd_url(?:\"|&quot;|\\\\\"|')\\s*:\\s*(?:\"|&quot;|\\\\\"|')([^\"'&]+)"),
            Pattern.compile("(?:\"|&quot;|\\\\\"|')playable_url(?:\"|&quot;|\\\\\"|')\\s*:\\s*(?:\"|&quot;|\\\\\"|')([^\"'&]+)"),
            Pattern.compile("(?:\"|&quot;|\\\\\"|')sd_src(?:\"|&quot;|\\\\\"|')\\s*:\\s*(?:\"|&quot;|\\\\\"|')([^\"'&]+)"),
            Pattern.compile("(?:\"|&quot;|\\\\\"|')sd_src_no_ratelimit(?:\"|&quot;|\\\\\"|')\\s*:\\s*(?:\"|&quot;|\\\\\"|')([^\"'&]+)")
        )
        for (pattern in sdPatterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val candidate = unescapeJsonUrl(matcher.group(1))
                if (candidate.startsWith("http") && (candidate.contains(".mp4") || candidate.contains("fbcdn.net") || candidate.contains("video"))) {
                    sdUrl = candidate
                    break
                }
            }
        }

        // 3. OpenGraph Video Patterns
        var ogVideoUrl: String? = null
        val ogPatterns = listOf(
            Pattern.compile("<meta\\s+property=\"og:video(?::secure_url|:url)?\"\\s+content=\"([^\"]+)\""),
            Pattern.compile("<meta\\s+content=\"([^\"]+)\"\\s+property=\"og:video(?::secure_url|:url)?\""),
            Pattern.compile("<meta\\s+name=\"twitter:player:stream\"\\s+content=\"([^\"]+)\"")
        )
        for (pattern in ogPatterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val candidate = unescapeJsonUrl(matcher.group(1))
                if (candidate.startsWith("http")) {
                    ogVideoUrl = candidate
                    break
                }
            }
        }

        // 4. Direct fbcdn.net MP4 patterns
        var directCdnUrl: String? = null
        if (hdUrl == null && sdUrl == null && ogVideoUrl == null) {
            val cdnMatcher = Pattern.compile("(https?:\\\\?/\\\\?/[a-zA-Z0-9.-]+\\.fbcdn\\.net\\\\?/[^\\s\"'<>]+?\\.mp4(?:\\?[^\\s\"'<>]*)?)").matcher(html)
            if (cdnMatcher.find()) {
                directCdnUrl = unescapeJsonUrl(cdnMatcher.group(1))
            }
        }

        val chosenUrl = hdUrl ?: sdUrl ?: ogVideoUrl ?: directCdnUrl ?: return null

        // Extract title
        val titleMatcher = Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"").matcher(html)
        val rawTitle = if (titleMatcher.find()) titleMatcher.group(1) else {
            val htmlTitleMatcher = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
            if (htmlTitleMatcher.find()) htmlTitleMatcher.group(1) else "Facebook Video"
        }
        val cleanTitle = rawTitle.replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"").trim()

        // Extract thumbnail
        val imageMatcher = Pattern.compile("<meta\\s+property=\"og:image\"\\s+content=\"([^\"]+)\"").matcher(html)
        val thumb = if (imageMatcher.find()) unescapeJsonUrl(imageMatcher.group(1)) else null

        val qualityOptions = mutableListOf<VideoQualityOption>()
        if (!hdUrl.isNullOrBlank()) {
            qualityOptions.add(VideoQualityOption("HD Quality (720p)", hdUrl, "720p", "MP4", -1L))
        }
        if (!sdUrl.isNullOrBlank()) {
            qualityOptions.add(VideoQualityOption("SD Quality (480p)", sdUrl, "480p", "MP4", -1L))
        }
        if (qualityOptions.isEmpty()) {
            qualityOptions.add(VideoQualityOption("Direct Video", chosenUrl, "Standard", "MP4", -1L))
        }

        return VideoInfo(
            originalUrl = originalUrl,
            finalUrl = chosenUrl,
            suggestedFilename = sanitizeTitleToFilename(cleanTitle, "Facebook", "mp4"),
            contentLength = -1L,
            contentType = "video/mp4",
            supportsRange = true,
            qualityOptions = qualityOptions,
            isDirectVideo = true,
            title = cleanTitle,
            author = null,
            thumbnailUrl = thumb,
            platformName = "Facebook"
        )
    }

    private fun tryFacebookEmbed(resolvedUrl: String, originalUrl: String): VideoInfo? {
        try {
            val embedUrl = "https://www.facebook.com/plugins/video.php?href=" + URLEncoder.encode(resolvedUrl, "UTF-8")
            val request = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Sec-Fetch-Dest", "iframe")
                .header("Sec-Fetch-Mode", "navigate")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string().orEmpty()
                    parseFacebookStreamsFromHtml(html, originalUrl)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun tryFacebookBotCrawler(resolvedUrl: String, originalUrl: String): VideoInfo? {
        try {
            val request = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", USER_AGENT_BOT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string().orEmpty()
                    parseFacebookStreamsFromHtml(html, originalUrl)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun tryFdownExtractor(resolvedUrl: String, originalUrl: String): VideoInfo? {
        val targets = listOf(resolvedUrl, originalUrl).distinct()
        for (target in targets) {
            try {
                val formBody = FormBody.Builder()
                    .add("URLz", target)
                    .build()

                val request = Request.Builder()
                    .url("https://fdown.net/download.php")
                    .post(formBody)
                    .header("User-Agent", USER_AGENT_DESKTOP)
                    .header("Referer", "https://fdown.net/")
                    .header("Origin", "https://fdown.net")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val html = response.body?.string().orEmpty()
                        var hdUrl: String? = null
                        var sdUrl: String? = null

                        val hdMatcher = Pattern.compile("id=[\"']hdlink[\"'][^>]+href=[\"']([^\"']+)[\"']").matcher(html)
                        if (hdMatcher.find()) {
                            hdUrl = unescapeJsonUrl(hdMatcher.group(1))
                        } else {
                            val hdMatcher2 = Pattern.compile("href=[\"']([^\"']+)[\"'][^>]+id=[\"']hdlink[\"']").matcher(html)
                            if (hdMatcher2.find()) hdUrl = unescapeJsonUrl(hdMatcher2.group(1))
                        }

                        val sdMatcher = Pattern.compile("id=[\"']sdlink[\"'][^>]+href=[\"']([^\"']+)[\"']").matcher(html)
                        if (sdMatcher.find()) {
                            sdUrl = unescapeJsonUrl(sdMatcher.group(1))
                        } else {
                            val sdMatcher2 = Pattern.compile("href=[\"']([^\"']+)[\"'][^>]+id=[\"']sdlink[\"']").matcher(html)
                            if (sdMatcher2.find()) sdUrl = unescapeJsonUrl(sdMatcher2.group(1))
                        }

                        val chosen = hdUrl ?: sdUrl
                        if (!chosen.isNullOrBlank()) {
                            val titleMatcher = Pattern.compile("<div class=[\"']lib-row lib-header[\"']>([^<]+)</div>").matcher(html)
                            val cleanTitle = if (titleMatcher.find()) titleMatcher.group(1).trim() else "Facebook Video"

                            val imgMatcher = Pattern.compile("<img[^>]+class=[\"']lib-img-show[\"'][^>]+src=[\"']([^\"']+)[\"']").matcher(html)
                            val thumb = if (imgMatcher.find()) imgMatcher.group(1) else null

                            val qualityOptions = mutableListOf<VideoQualityOption>()
                            if (!hdUrl.isNullOrBlank()) {
                                qualityOptions.add(VideoQualityOption("HD Quality", hdUrl, "720p", "MP4", -1L))
                            }
                            if (!sdUrl.isNullOrBlank()) {
                                qualityOptions.add(VideoQualityOption("SD Quality", sdUrl, "480p", "MP4", -1L))
                            }
                            if (qualityOptions.isEmpty()) {
                                qualityOptions.add(VideoQualityOption("Direct Video", chosen, "Standard", "MP4", -1L))
                            }

                            return VideoInfo(
                                originalUrl = originalUrl,
                                finalUrl = chosen,
                                suggestedFilename = sanitizeTitleToFilename(cleanTitle, "Facebook", "mp4"),
                                contentLength = -1L,
                                contentType = "video/mp4",
                                supportsRange = true,
                                qualityOptions = qualityOptions,
                                isDirectVideo = true,
                                title = cleanTitle,
                                author = null,
                                thumbnailUrl = thumb,
                                platformName = "Facebook"
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun tryFacebookMobileBasic(resolvedUrl: String, originalUrl: String): VideoInfo? {
        try {
            val mobileUrl = resolvedUrl
                .replace("https://www.facebook.com", "https://mbasic.facebook.com")
                .replace("https://m.facebook.com", "https://mbasic.facebook.com")

            val request = Request.Builder()
                .url(mobileUrl)
                .header("User-Agent", USER_AGENT_MOBILE)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string().orEmpty()

                    // Look for video redirect link: /video_redirect/?src=...
                    val redirectMatcher = Pattern.compile("href=[\"'](/video_redirect/\\?src=[^\"']+)[\"']").matcher(html)
                    if (redirectMatcher.find()) {
                        val rawRedirect = redirectMatcher.group(1).replace("&amp;", "&")
                        val srcMatcher = Pattern.compile("[?&]src=([^&]+)").matcher(rawRedirect)
                        if (srcMatcher.find()) {
                            val decoded = URLDecoder.decode(srcMatcher.group(1), "UTF-8")
                            if (decoded.startsWith("http")) {
                                return VideoInfo(
                                    originalUrl = originalUrl,
                                    finalUrl = decoded,
                                    suggestedFilename = sanitizeTitleToFilename("Facebook_Video", "Facebook", "mp4"),
                                    contentLength = -1L,
                                    contentType = "video/mp4",
                                    supportsRange = true,
                                    qualityOptions = listOf(VideoQualityOption("Original Quality", decoded, "Standard", "MP4", -1L)),
                                    isDirectVideo = true,
                                    title = "Facebook Video",
                                    author = null,
                                    thumbnailUrl = null,
                                    platformName = "Facebook"
                                )
                            }
                        }
                    }

                    parseFacebookStreamsFromHtml(html, originalUrl)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun tryFacebookTwitterBot(resolvedUrl: String, originalUrl: String): VideoInfo? {
        try {
            val request = Request.Builder()
                .url(resolvedUrl)
                .header("User-Agent", USER_AGENT_TWITTER)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string().orEmpty()
                    parseFacebookStreamsFromHtml(html, originalUrl)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    // ==================== YOUTUBE ====================

    private suspend fun extractYouTube(url: String): VideoInfo {
        // Extract video ID (11 chars)
        val idMatcher = Pattern.compile("(?:youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?)/|.*[?&]v=)|youtu\\.be/|youtube\\.com/shorts/)([a-zA-Z0-9_-]{11})")
            .matcher(url)
        val videoId = if (idMatcher.find()) idMatcher.group(1) else null
            ?: throw VideoDownloadException("Could not detect a valid YouTube video ID from the provided link.")

        var videoTitle = "YouTube Video"
        var videoAuthor: String? = null
        val defaultThumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

        // Fetch oEmbed metadata
        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val req = Request.Builder().url(oembedUrl).header("User-Agent", USER_AGENT_DESKTOP).build()
            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val json = JSONObject(res.body?.string().orEmpty())
                    videoTitle = json.optString("title", videoTitle)
                    videoAuthor = json.optString("author_name", null)
                }
            }
        } catch (_: Exception) {
        }

        // Method 1: Invidious API instances (High speed direct MP4 video+audio formats)
        val invidiousInstances = listOf(
            "https://inv.tux.pizza",
            "https://invidious.nerdvpn.de",
            "https://yt.artemislena.eu",
            "https://invidious.drgns.space"
        )

        for (instance in invidiousInstances) {
            try {
                val apiUrl = "$instance/api/v1/videos/$videoId"
                val req = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", USER_AGENT_DESKTOP)
                    .build()

                httpClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val json = JSONObject(res.body?.string().orEmpty())
                        val formatStreams = json.optJSONArray("formatStreams")
                        if (formatStreams != null && formatStreams.length() > 0) {
                            val qualityOptions = mutableListOf<VideoQualityOption>()
                            var bestUrl: String? = null
                            var bestSize: Long = -1L

                            for (i in 0 until formatStreams.length()) {
                                val item = formatStreams.optJSONObject(i) ?: continue
                                val streamUrl = item.optString("url")
                                val qualityLabel = item.optString("qualityLabel", "MP4")
                                val resolution = item.optString("resolution", "720p")
                                val size = item.optLong("size", -1L)

                                if (streamUrl.isNotBlank()) {
                                    if (bestUrl == null) {
                                        bestUrl = streamUrl
                                        bestSize = size
                                    }
                                    qualityOptions.add(
                                        VideoQualityOption(
                                            label = "$qualityLabel ($resolution)",
                                            url = streamUrl,
                                            resolution = resolution,
                                            format = "MP4",
                                            estimatedBytes = size
                                        )
                                    )
                                }
                            }

                            if (bestUrl != null) {
                                return VideoInfo(
                                    originalUrl = url,
                                    finalUrl = bestUrl,
                                    suggestedFilename = sanitizeTitleToFilename(videoTitle, "YouTube", "mp4"),
                                    contentLength = bestSize,
                                    contentType = "video/mp4",
                                    supportsRange = true,
                                    qualityOptions = qualityOptions,
                                    isDirectVideo = true,
                                    title = videoTitle,
                                    author = videoAuthor,
                                    thumbnailUrl = defaultThumbnail,
                                    platformName = "YouTube"
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Method 2: Cobalt API fallback
        tryCobaltExtractor(url, "YouTube")?.let { return it }

        throw VideoDownloadException(
            "Unable to resolve downloadable stream for this YouTube video. The video may be private, age-restricted, or geo-blocked."
        )
    }

    // ==================== COBALT API HELPER ====================

    private suspend fun tryCobaltExtractor(url: String, platformName: String): VideoInfo? {
        val cobaltInstances = listOf(
            "https://cobalt-api.kwiatekm.tokyo",
            "https://api.cobalt.tools",
            "https://cobalt.tools/api"
        )

        for (host in cobaltInstances) {
            try {
                // Try Cobalt v10 endpoint (POST /) and legacy (/api/json)
                val endpoints = listOf(
                    host,
                    if (host.endsWith("/api/json")) host else "$host/api/json"
                ).distinct()

                for (endpoint in endpoints) {
                    try {
                        val jsonPayload = JSONObject().apply {
                            put("url", url)
                            put("videoQuality", "720")
                            put("vQuality", "720")
                        }
                        val body = jsonPayload.toString().toRequestBody("application/json".toMediaTypeOrNull())

                        val request = Request.Builder()
                            .url(endpoint)
                            .post(body)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .header("User-Agent", USER_AGENT_MOBILE)
                            .build()

                        httpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val respJson = JSONObject(response.body?.string().orEmpty())
                                val status = respJson.optString("status")
                                var streamUrl = respJson.optString("url")
                                if (streamUrl.isBlank()) {
                                    val picker = respJson.optJSONArray("picker")
                                    if (picker != null && picker.length() > 0) {
                                        streamUrl = picker.optJSONObject(0)?.optString("url").orEmpty()
                                    }
                                }

                                if ((status == "stream" || status == "redirect" || status == "tunnel" || status == "picker" || status.isBlank()) && streamUrl.isNotBlank()) {
                                    val filename = sanitizeTitleToFilename("${platformName}_Video", platformName, "mp4")
                                    return VideoInfo(
                                        originalUrl = url,
                                        finalUrl = streamUrl,
                                        suggestedFilename = filename,
                                        contentLength = -1L,
                                        contentType = "video/mp4",
                                        supportsRange = true,
                                        qualityOptions = listOf(VideoQualityOption("Original Quality", streamUrl, "HD", "MP4", -1L)),
                                        isDirectVideo = true,
                                        title = "$platformName Video",
                                        author = null,
                                        thumbnailUrl = null,
                                        platformName = platformName
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    // ==================== GENERIC HTML SCRAPER ====================

    private suspend fun extractGenericWebpage(url: String, platformName: String = "Web Video"): VideoInfo {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT_MOBILE)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw VideoDownloadException("Server returned HTTP error ${response.code} when loading page.")
                }

                val html = response.body?.string().orEmpty()

                // Check OpenGraph video
                var foundVideoUrl: String? = null
                val ogMatcher = Pattern.compile("<meta\\s+(?:property|name)=\"(?:og:video|og:video:url|og:video:secure_url|twitter:player:stream)\"\\s+content=\"([^\"]+)\"")
                    .matcher(html)
                if (ogMatcher.find()) {
                    foundVideoUrl = unescapeJsonUrl(ogMatcher.group(1))
                }

                // Check HTML5 video or source tag
                if (foundVideoUrl.isNullOrBlank()) {
                    val tagMatcher = Pattern.compile("<(?:video|source)[^>]+src=[\"']([^\"']+\\.mp4[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
                        .matcher(html)
                    if (tagMatcher.find()) {
                        foundVideoUrl = unescapeJsonUrl(tagMatcher.group(1))
                    }
                }

                // Check JSON-LD contentUrl
                if (foundVideoUrl.isNullOrBlank()) {
                    val jsonLdMatcher = Pattern.compile("\"contentUrl\"\\s*:\\s*\"([^\"]+\\.mp4[^\"]*)\"")
                        .matcher(html)
                    if (jsonLdMatcher.find()) {
                        foundVideoUrl = unescapeJsonUrl(jsonLdMatcher.group(1))
                    }
                }

                // Check generic MP4 link inside scripts
                if (foundVideoUrl.isNullOrBlank()) {
                    val mp4Matcher = Pattern.compile("(https?:\\\\?/\\\\?/[^\\s\"'<>]+?\\.mp4(?:\\?[^\\s\"'<>]*)?)")
                        .matcher(html)
                    if (mp4Matcher.find()) {
                        foundVideoUrl = unescapeJsonUrl(mp4Matcher.group(1))
                    }
                }

                if (!foundVideoUrl.isNullOrBlank()) {
                    // Extract title and thumbnail
                    val titleMatcher = Pattern.compile("<meta\\s+(?:property|name)=\"(?:og:title|twitter:title)\"\\s+content=\"([^\"]+)\"")
                        .matcher(html)
                    val rawTitle = if (titleMatcher.find()) titleMatcher.group(1) else {
                        val htmlTitleMatcher = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (htmlTitleMatcher.find()) htmlTitleMatcher.group(1) else "$platformName Video"
                    }
                    val cleanTitle = rawTitle.trim()

                    val imgMatcher = Pattern.compile("<meta\\s+(?:property|name)=\"(?:og:image|twitter:image)\"\\s+content=\"([^\"]+)\"")
                        .matcher(html)
                    val thumb = if (imgMatcher.find()) imgMatcher.group(1) else null

                    return VideoInfo(
                        originalUrl = url,
                        finalUrl = foundVideoUrl,
                        suggestedFilename = sanitizeTitleToFilename(cleanTitle, platformName, "mp4"),
                        contentLength = -1L,
                        contentType = "video/mp4",
                        supportsRange = true,
                        qualityOptions = listOf(VideoQualityOption("Standard Video", foundVideoUrl, "Standard", "MP4", -1L)),
                        isDirectVideo = true,
                        title = cleanTitle,
                        author = null,
                        thumbnailUrl = thumb,
                        platformName = platformName
                    )
                }
            }
        } catch (e: VideoDownloadException) {
            throw e
        } catch (e: Exception) {
            throw VideoDownloadException("Could not extract video: ${e.localizedMessage ?: "Page parsing failed"}", e)
        }

        throw VideoDownloadException(
            "No downloadable video was found on this webpage. Please ensure the link points to a public video on Facebook, TikTok, Instagram, YouTube, or direct MP4."
        )
    }

    internal fun unescapeJsonUrl(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("\"") && s.endsWith("\"") && s.length > 1) {
            s = s.substring(1, s.length - 1)
        }
        if (s.startsWith("&quot;") && s.endsWith("&quot;") && s.length > 12) {
            s = s.substring(6, s.length - 6)
        }
        return s.replace("\\/", "/")
            .replace("\\\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u0025", "%")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("\\\"", "\"")
            .replace("\r", "")
            .replace("\n", "")
            .trim()
    }

    internal fun parseFacebookStreamsFromHtmlForTesting(html: String, originalUrl: String): VideoInfo? {
        return parseFacebookStreamsFromHtml(html, originalUrl)
    }

    private fun sanitizeTitleToFilename(title: String, prefix: String, extension: String): String {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(50)
            .trim('_')
        return if (cleaned.isNotBlank()) {
            "${prefix}_$cleaned.$extension"
        } else {
            "${prefix}_video_${System.currentTimeMillis() % 10000}.$extension"
        }
    }
}
