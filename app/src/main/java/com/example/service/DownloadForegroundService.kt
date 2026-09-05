package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.engine.DownloadEngine

class DownloadForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationHelper = DownloadNotificationHelper(this)
        val initialNotification = notificationHelper.buildProgressNotification(
            download = com.example.model.DownloadEntity(
                id = "service_root",
                url = "",
                filename = "Video Downloader Service",
                totalBytes = -1,
                downloadedBytes = 0,
                localFilePath = "",
                mimeType = "video/mp4",
                status = com.example.model.DownloadStatus.DOWNLOADING
            ),
            progress = null
        )

        try {
            startForeground(DownloadNotificationHelper.FOREGROUND_NOTIFICATION_ID, initialNotification)
        } catch (_: Exception) {
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            try {
                val intent = Intent(context, DownloadForegroundService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, DownloadForegroundService::class.java)
                context.stopService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
