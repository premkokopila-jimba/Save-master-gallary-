package com.example.model

data class VideoQualityOption(
    val label: String,
    val url: String,
    val resolution: String,
    val format: String,
    val estimatedBytes: Long
)

data class VideoInfo(
    val originalUrl: String,
    val finalUrl: String,
    val suggestedFilename: String,
    val contentLength: Long,
    val contentType: String,
    val supportsRange: Boolean,
    val qualityOptions: List<VideoQualityOption> = emptyList(),
    val isDirectVideo: Boolean = true
)

data class DownloadProgress(
    val downloadId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val percentage: Int
)

data class AppSettings(
    val maxConcurrentDownloads: Int = 2,
    val downloadWifiOnly: Boolean = false,
    val autoStartDownloads: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val themeMode: String = "dark" // "system", "light", "dark"
)
