package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.VideoDownloaderApp
import com.example.model.AppSettings
import com.example.model.DownloadEntity
import com.example.model.DownloadProgress
import com.example.model.VideoInfo
import com.example.network.UrlAnalyzer
import com.example.network.VideoDownloadException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

sealed class UrlAnalysisUiState {
    object Idle : UrlAnalysisUiState()
    object Analyzing : UrlAnalysisUiState()
    data class Success(val info: VideoInfo) : UrlAnalysisUiState()
    data class Error(val message: String) : UrlAnalysisUiState()
}

data class StorageStats(
    val availableBytes: Long,
    val totalBytes: Long
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()) else 0f
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VideoDownloaderApp
    private val downloadEngine = app.downloadEngine
    private val storageManager = app.storageManager
    private val preferencesManager = app.preferencesManager
    private val database = app.database

    val allDownloads: StateFlow<List<DownloadEntity>> = database.downloadDao()
        .getAllDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val liveProgress: StateFlow<Map<String, DownloadProgress>> = downloadEngine.liveProgress

    val settings: StateFlow<AppSettings> = preferencesManager.settings

    private val _analysisState = MutableStateFlow<UrlAnalysisUiState>(UrlAnalysisUiState.Idle)
    val analysisState: StateFlow<UrlAnalysisUiState> = _analysisState.asStateFlow()

    private val _storageStats = MutableStateFlow(loadStorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    fun refreshStorageStats() {
        _storageStats.value = loadStorageStats()
    }

    private fun loadStorageStats(): StorageStats {
        return StorageStats(
            availableBytes = storageManager.getAvailableStorageBytes(),
            totalBytes = storageManager.getTotalStorageBytes()
        )
    }

    fun analyzeUrl(url: String) {
        if (url.isBlank()) {
            _analysisState.value = UrlAnalysisUiState.Error("Please enter a valid video URL.")
            return
        }

        viewModelScope.launch {
            _analysisState.value = UrlAnalysisUiState.Analyzing
            try {
                val videoInfo = UrlAnalyzer.analyze(url)
                _analysisState.value = UrlAnalysisUiState.Success(videoInfo)
            } catch (e: VideoDownloadException) {
                _analysisState.value = UrlAnalysisUiState.Error(e.userMessage)
            } catch (e: Exception) {
                _analysisState.value = UrlAnalysisUiState.Error(
                    e.localizedMessage ?: "Unable to analyze URL. Please verify the link is accessible."
                )
            }
        }
    }

    fun clearAnalysis() {
        _analysisState.value = UrlAnalysisUiState.Idle
    }

    fun startDownload(videoInfo: VideoInfo, customFilename: String? = null) {
        val downloadId = UUID.randomUUID().toString()
        val filename = if (!customFilename.isNullOrBlank()) customFilename else videoInfo.suggestedFilename

        downloadEngine.enqueueDownload(
            id = downloadId,
            url = videoInfo.finalUrl,
            filename = filename,
            totalBytes = videoInfo.contentLength,
            mimeType = videoInfo.contentType,
            supportsResume = videoInfo.supportsRange
        )
        clearAnalysis()
        refreshStorageStats()
    }

    fun pauseDownload(id: String) {
        downloadEngine.pauseDownload(id)
    }

    fun resumeDownload(id: String) {
        downloadEngine.resumeDownload(id)
    }

    fun cancelDownload(id: String) {
        downloadEngine.cancelDownload(id)
    }

    fun retryDownload(id: String) {
        downloadEngine.retryDownload(id)
    }

    fun deleteDownload(id: String) {
        downloadEngine.deleteDownload(id)
        refreshStorageStats()
    }

    fun openVideoFile(localFilePath: String): Boolean {
        if (localFilePath.isBlank()) return false
        val file = File(localFilePath)
        return storageManager.openVideo(file)
    }

    fun shareVideoFile(localFilePath: String): Boolean {
        if (localFilePath.isBlank()) return false
        val file = File(localFilePath)
        return storageManager.shareVideo(file)
    }

    fun exportVideoToPublicStorage(localFilePath: String, mimeType: String): Boolean {
        if (localFilePath.isBlank()) return false
        val file = File(localFilePath)
        if (!file.exists()) return false
        val uri = storageManager.saveVideoToPublicGallery(file, mimeType)
            ?: storageManager.exportVideoToPublicDownloads(file, mimeType)
        return uri != null
    }

    fun updateMaxConcurrentDownloads(limit: Int) {
        preferencesManager.setMaxConcurrentDownloads(limit)
        downloadEngine.processQueue()
    }

    fun updateWifiOnly(wifiOnly: Boolean) {
        preferencesManager.setDownloadWifiOnly(wifiOnly)
    }

    fun updateAutoStart(autoStart: Boolean) {
        preferencesManager.setAutoStartDownloads(autoStart)
    }

    fun updateNotificationsEnabled(enabled: Boolean) {
        preferencesManager.setNotificationsEnabled(enabled)
    }

    fun updateThemeMode(mode: String) {
        preferencesManager.setThemeMode(mode)
    }

    fun clearAllHistory() {
        downloadEngine.clearAllHistory()
        refreshStorageStats()
    }
}
