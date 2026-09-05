package com.example

import android.app.Application
import com.example.data.local.AppDatabase
import com.example.data.local.PreferencesManager
import com.example.engine.DownloadEngine
import com.example.storage.StorageManager

class VideoDownloaderApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var storageManager: StorageManager
        private set

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var downloadEngine: DownloadEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getInstance(this)
        storageManager = StorageManager(this)
        preferencesManager = PreferencesManager(this)
        downloadEngine = DownloadEngine(this, database, storageManager, preferencesManager)
    }

    companion object {
        lateinit var instance: VideoDownloaderApp
            private set
    }
}
