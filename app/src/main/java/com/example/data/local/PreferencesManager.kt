package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("video_downloader_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        return AppSettings(
            maxConcurrentDownloads = prefs.getInt(KEY_MAX_CONCURRENT, 2),
            downloadWifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false),
            autoStartDownloads = prefs.getBoolean(KEY_AUTO_START, true),
            notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, true),
            themeMode = prefs.getString(KEY_THEME_MODE, "dark") ?: "dark"
        )
    }

    fun setMaxConcurrentDownloads(limit: Int) {
        val safeLimit = limit.coerceIn(1, 5)
        prefs.edit().putInt(KEY_MAX_CONCURRENT, safeLimit).apply()
        _settings.value = _settings.value.copy(maxConcurrentDownloads = safeLimit)
    }

    fun setDownloadWifiOnly(wifiOnly: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).apply()
        _settings.value = _settings.value.copy(downloadWifiOnly = wifiOnly)
    }

    fun setAutoStartDownloads(autoStart: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, autoStart).apply()
        _settings.value = _settings.value.copy(autoStartDownloads = autoStart)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply()
        _settings.value = _settings.value.copy(notificationsEnabled = enabled)
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    companion object {
        private const val KEY_MAX_CONCURRENT = "max_concurrent_downloads"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_NOTIFICATIONS = "notifications"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
