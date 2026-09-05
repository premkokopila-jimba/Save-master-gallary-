package com.example.model

enum class DownloadStatus {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isActive: Boolean
        get() = this == QUEUED || this == PREPARING || this == DOWNLOADING

    val isFinished: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}
