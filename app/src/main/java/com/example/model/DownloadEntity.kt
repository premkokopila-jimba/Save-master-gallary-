package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val filename: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val localFilePath: String,
    val mimeType: String,
    val status: DownloadStatus,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val supportsResume: Boolean = false,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val thumbnailPath: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val progressPercent: Int
        get() = (progressFraction * 100).toInt()
}
