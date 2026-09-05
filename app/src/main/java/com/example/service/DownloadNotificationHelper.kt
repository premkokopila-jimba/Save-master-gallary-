package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.DownloadEntity
import com.example.model.DownloadProgress

class DownloadNotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS,
                "Active Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time progress for ongoing video downloads"
                setShowBadge(false)
            }

            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED,
                "Download Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when a video download finishes or encounters an error"
            }

            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(completedChannel)
        }
    }

    fun buildProgressNotification(
        download: DownloadEntity,
        progress: DownloadProgress?
    ): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val percent = progress?.percentage ?: download.progressPercent
        val speedStr = if (progress != null && progress.speedBytesPerSec > 0) {
            formatSpeed(progress.speedBytesPerSec)
        } else ""

        val etaStr = if (progress != null && progress.etaSeconds > 0) {
            formatEta(progress.etaSeconds)
        } else ""

        val subtext = listOf(speedStr, etaStr).filter { it.isNotEmpty() }.joinToString(" • ")

        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading: ${download.filename}")
            .setContentText(if (subtext.isNotEmpty()) "$percent% • $subtext" else "$percent%")
            .setProgress(100, percent, percent == 0 && download.totalBytes <= 0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        return builder.build()
    }

    fun showCompletionNotification(download: DownloadEntity) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            download.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText(download.filename)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(download.id.hashCode(), notification)
    }

    fun showErrorNotification(download: DownloadEntity, error: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download Failed")
            .setContentText("${download.filename}: $error")
            .setAutoCancel(true)
            .build()

        notificationManager.notify(download.id.hashCode(), notification)
    }

    fun cancelNotification(id: Int) {
        notificationManager.cancel(id)
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024f * 1024f))
            bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024f)
            else -> "$bytesPerSec B/s"
        }
    }

    private fun formatEta(seconds: Long): String {
        return when {
            seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m remaining"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s remaining"
            else -> "~${seconds}s remaining"
        }
    }

    companion object {
        const val CHANNEL_PROGRESS = "video_downloads_channel"
        const val CHANNEL_COMPLETED = "video_downloads_completed_channel"
        const val FOREGROUND_NOTIFICATION_ID = 8801
    }
}
