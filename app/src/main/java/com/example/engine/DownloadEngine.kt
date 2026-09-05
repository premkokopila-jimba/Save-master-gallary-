package com.example.engine

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.PreferencesManager
import com.example.model.DownloadEntity
import com.example.model.DownloadProgress
import com.example.model.DownloadStatus
import com.example.network.UrlAnalyzer
import com.example.network.VideoDownloadException
import com.example.service.DownloadForegroundService
import com.example.service.DownloadNotificationHelper
import com.example.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadEngine(
    private val context: Context,
    private val database: AppDatabase,
    private val storageManager: StorageManager,
    private val preferencesManager: PreferencesManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationHelper = DownloadNotificationHelper(context)

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val queueMutex = Mutex()

    private val _liveProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val liveProgress: StateFlow<Map<String, DownloadProgress>> = _liveProgress.asStateFlow()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    init {
        // App restart recovery: reset any downloads that were interrupted in flight
        scope.launch {
            database.downloadDao().resetInterruptedDownloads()
            if (preferencesManager.settings.value.autoStartDownloads) {
                processQueue()
            }
        }
    }

    fun enqueueDownload(
        id: String,
        url: String,
        filename: String,
        totalBytes: Long,
        mimeType: String,
        supportsResume: Boolean
    ) {
        scope.launch {
            val entity = DownloadEntity(
                id = id,
                url = url,
                filename = filename,
                totalBytes = totalBytes,
                downloadedBytes = 0,
                localFilePath = "",
                mimeType = mimeType,
                status = DownloadStatus.QUEUED,
                supportsResume = supportsResume
            )
            database.downloadDao().insertDownload(entity)
            processQueue()
        }
    }

    fun pauseDownload(id: String) {
        scope.launch {
            val job = activeJobs.remove(id)
            job?.cancel()

            val entity = database.downloadDao().getDownloadByIdDirect(id)
            val tempFile = storageManager.getTempFile(id)
            val currentBytes = if (tempFile.exists()) tempFile.length() else (entity?.downloadedBytes ?: 0L)

            database.downloadDao().updateProgress(
                id = id,
                downloaded = currentBytes,
                total = entity?.totalBytes ?: -1L,
                status = DownloadStatus.PAUSED
            )
            updateLiveProgressMap(id, null)
            checkForegroundService()
            processQueue()
        }
    }

    fun resumeDownload(id: String) {
        scope.launch {
            database.downloadDao().updateStatus(id, DownloadStatus.QUEUED)
            processQueue()
        }
    }

    fun cancelDownload(id: String) {
        scope.launch {
            val job = activeJobs.remove(id)
            job?.cancel()

            storageManager.deleteDownloadFiles(null, null, id)
            database.downloadDao().updateStatus(id, DownloadStatus.CANCELLED)
            updateLiveProgressMap(id, null)
            checkForegroundService()
            processQueue()
        }
    }

    fun retryDownload(id: String) {
        scope.launch {
            val entity = database.downloadDao().getDownloadByIdDirect(id)
            if (entity != null) {
                database.downloadDao().updateDownload(
                    entity.copy(
                        status = DownloadStatus.QUEUED,
                        errorMessage = null,
                        retryCount = 0
                    )
                )
                processQueue()
            }
        }
    }

    fun deleteDownload(id: String) {
        scope.launch {
            val entity = database.downloadDao().getDownloadByIdDirect(id)
            val job = activeJobs.remove(id)
            job?.cancel()

            storageManager.deleteDownloadFiles(
                filePath = entity?.localFilePath,
                thumbnailPath = entity?.thumbnailPath,
                downloadId = id
            )
            database.downloadDao().deleteDownloadById(id)
            updateLiveProgressMap(id, null)
            checkForegroundService()
            processQueue()
        }
    }

    fun processQueue() {
        scope.launch {
            queueMutex.withLock {
                val maxConcurrent = preferencesManager.settings.value.maxConcurrentDownloads
                val runningCount = activeJobs.size

                val slotsAvailable = maxConcurrent - runningCount
                if (slotsAvailable <= 0) return@withLock

                val queued = database.downloadDao().getQueuedDownloads()
                for (item in queued.take(slotsAvailable)) {
                    if (!activeJobs.containsKey(item.id)) {
                        startDownloadTask(item.id)
                    }
                }
            }
        }
    }

    private fun startDownloadTask(downloadId: String) {
        val job = scope.launch {
            executeDownload(downloadId)
        }
        activeJobs[downloadId] = job
        checkForegroundService()
    }

    private suspend fun executeDownload(downloadId: String) {
        var entity = database.downloadDao().getDownloadByIdDirect(downloadId) ?: return

        try {
            database.downloadDao().updateStatus(downloadId, DownloadStatus.PREPARING)

            // Storage pre-check
            if (entity.totalBytes > 0) {
                storageManager.checkStorageAvailability(entity.totalBytes)
            }

            val tempFile = storageManager.getTempFile(downloadId)
            var startByte = 0L
            if (tempFile.exists() && entity.supportsResume) {
                startByte = tempFile.length()
                if (entity.totalBytes in 1..startByte) {
                    // Already full temp file
                    startByte = 0L
                    tempFile.delete()
                }
            } else if (tempFile.exists() && !entity.supportsResume) {
                tempFile.delete()
            }

            val requestBuilder = Request.Builder()
                .url(entity.url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                )

            if (startByte > 0) {
                requestBuilder.header("Range", "bytes=$startByte-")
            }

            database.downloadDao().updateStatus(downloadId, DownloadStatus.DOWNLOADING)

            val call = httpClient.newCall(requestBuilder.build())
            val response = call.execute()

            response.use { res ->
                val responseCode = res.code

                // Handle status codes
                if (!res.isSuccessful && responseCode != 206) {
                    if (responseCode == 416) {
                        // Range not satisfiable, delete temp and restart from 0
                        tempFile.delete()
                        throw VideoDownloadException("Server could not satisfy resume range. Restarting download.")
                    }
                    UrlAnalyzer.mapStatusCodeToException(responseCode)
                }

                val isPartialContent = (responseCode == 206)
                val appendMode = (startByte > 0 && isPartialContent)

                val body = res.body ?: throw VideoDownloadException("Empty response body received from server.")
                val streamLength = body.contentLength()

                var totalBytes = entity.totalBytes
                if (totalBytes <= 0) {
                    if (isPartialContent) {
                        val contentRange = res.header("Content-Range")
                        if (contentRange != null) {
                            totalBytes = contentRange.substringAfterLast("/").toLongOrNull() ?: -1L
                        }
                    } else if (streamLength > 0) {
                        totalBytes = streamLength
                    }
                }

                var currentDownloaded = if (appendMode) startByte else 0L
                if (!appendMode && tempFile.exists()) {
                    tempFile.delete()
                }

                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(tempFile, appendMode)

                outputStream.use { out ->
                    val buffer = ByteArray(32 * 1024) // 32KB buffer
                    var bytesRead: Int

                    var lastUiUpdate = System.currentTimeMillis()
                    var bytesSinceLastUpdate = 0L
                    var rollingSpeed = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (!coroutineContext.isActive) {
                            return@use
                        }

                        out.write(buffer, 0, bytesRead)
                        currentDownloaded += bytesRead
                        bytesSinceLastUpdate += bytesRead

                        val now = System.currentTimeMillis()
                        val deltaMs = now - lastUiUpdate
                        if (deltaMs >= 350) {
                            // Compute speed in bytes/sec
                            rollingSpeed = (bytesSinceLastUpdate * 1000L) / deltaMs
                            bytesSinceLastUpdate = 0L
                            lastUiUpdate = now

                            val etaSeconds = if (rollingSpeed > 0 && totalBytes > currentDownloaded) {
                                (totalBytes - currentDownloaded) / rollingSpeed
                            } else 0L

                            val percent = if (totalBytes > 0) {
                                ((currentDownloaded.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                            } else 0

                            val progress = DownloadProgress(
                                downloadId = downloadId,
                                downloadedBytes = currentDownloaded,
                                totalBytes = totalBytes,
                                speedBytesPerSec = rollingSpeed,
                                etaSeconds = etaSeconds,
                                percentage = percent
                            )
                            updateLiveProgressMap(downloadId, progress)

                            // Lightweight database progress update
                            database.downloadDao().updateProgress(
                                id = downloadId,
                                downloaded = currentDownloaded,
                                total = totalBytes,
                                status = DownloadStatus.DOWNLOADING
                            )

                            // Update notification
                            if (preferencesManager.settings.value.notificationsEnabled) {
                                val currentEntity = database.downloadDao().getDownloadByIdDirect(downloadId)
                                if (currentEntity != null) {
                                    val notification = notificationHelper.buildProgressNotification(currentEntity, progress)
                                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                    notificationManager.notify(DownloadNotificationHelper.FOREGROUND_NOTIFICATION_ID, notification)
                                }
                            }
                        }
                    }
                    out.flush()
                }

                // Verify file size if totalBytes was known
                if (totalBytes > 0 && currentDownloaded < totalBytes) {
                    throw VideoDownloadException("Download connection closed prematurely ($currentDownloaded/$totalBytes bytes).")
                }

                // Finalize file: move to Movies, register MediaStore, extract thumbnail
                val finalized = storageManager.finalizeVideoFile(
                    downloadId = downloadId,
                    suggestedFilename = entity.filename,
                    mimeType = entity.mimeType
                )

                val completedEntity = entity.copy(
                    status = DownloadStatus.COMPLETED,
                    downloadedBytes = currentDownloaded,
                    totalBytes = if (totalBytes > 0) totalBytes else currentDownloaded,
                    localFilePath = finalized.first.absolutePath,
                    thumbnailPath = finalized.second,
                    completedAt = System.currentTimeMillis(),
                    errorMessage = null
                )

                database.downloadDao().updateDownload(completedEntity)
                updateLiveProgressMap(downloadId, null)

                if (preferencesManager.settings.value.notificationsEnabled) {
                    notificationHelper.showCompletionNotification(completedEntity)
                }
            }
        } catch (e: Exception) {
            if (coroutineContext.isActive) {
                handleDownloadFailure(downloadId, e)
            }
        } finally {
            activeJobs.remove(downloadId)
            checkForegroundService()
            processQueue()
        }
    }

    private suspend fun handleDownloadFailure(downloadId: String, error: Throwable) {
        val current = database.downloadDao().getDownloadByIdDirect(downloadId) ?: return

        val userMessage = when (error) {
            is VideoDownloadException -> error.userMessage
            is java.net.UnknownHostException -> "Internet connection lost. Server host unreachable."
            is java.net.SocketTimeoutException -> "Network connection timed out during download."
            is java.io.IOException -> "Download interrupted: ${error.localizedMessage ?: "I/O error"}"
            else -> error.localizedMessage ?: "Unknown error occurred."
        }

        val retryCount = current.retryCount + 1
        val canRetry = (retryCount < current.maxRetries) && current.supportsResume

        if (canRetry) {
            // Auto-retry after brief backoff
            database.downloadDao().updateDownload(
                current.copy(
                    status = DownloadStatus.QUEUED,
                    retryCount = retryCount,
                    errorMessage = "$userMessage (Retrying attempt $retryCount/${current.maxRetries}...)"
                )
            )
            delay(2000)
            processQueue()
        } else {
            database.downloadDao().updateDownload(
                current.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = userMessage
                )
            )
            updateLiveProgressMap(downloadId, null)
            if (preferencesManager.settings.value.notificationsEnabled) {
                notificationHelper.showErrorNotification(current, userMessage)
            }
        }
    }

    private fun updateLiveProgressMap(downloadId: String, progress: DownloadProgress?) {
        val current = _liveProgress.value.toMutableMap()
        if (progress == null) {
            current.remove(downloadId)
        } else {
            current[downloadId] = progress
        }
        _liveProgress.value = current
    }

    private fun checkForegroundService() {
        if (activeJobs.isNotEmpty()) {
            DownloadForegroundService.start(context)
        } else {
            DownloadForegroundService.stop(context)
        }
    }

    fun clearAllHistory() {
        scope.launch {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
            checkForegroundService()
            database.downloadDao().clearAll()
        }
    }
}
