package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.model.DownloadEntity
import com.example.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun getDownloadById(id: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getDownloadByIdDirect(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'PREPARING', 'DOWNLOADING') ORDER BY createdAt ASC")
    fun getActiveDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY createdAt ASC")
    suspend fun getQueuedDownloads(): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('PREPARING', 'DOWNLOADING')")
    suspend fun getActiveRunningCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadEntity)

    @Update
    suspend fun updateDownload(download: DownloadEntity)

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total, status = :status WHERE id = :id")
    suspend fun updateProgress(id: String, downloaded: Long, total: Long, status: DownloadStatus)

    @Query("UPDATE downloads SET status = :status, errorMessage = :error, completedAt = :completedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: DownloadStatus, error: String? = null, completedAt: Long? = null)

    @Query("UPDATE downloads SET thumbnailPath = :thumbPath WHERE id = :id")
    suspend fun updateThumbnail(id: String, thumbPath: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownloadById(id: String)

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun deleteCompletedDownloads()

    @Query("DELETE FROM downloads")
    suspend fun clearAll()

    @Query("UPDATE downloads SET status = 'PAUSED' WHERE status IN ('DOWNLOADING', 'PREPARING')")
    suspend fun resetInterruptedDownloads()
}
