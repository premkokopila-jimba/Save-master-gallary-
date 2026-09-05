package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.network.VideoDownloadException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class StorageManager(private val context: Context) {

    /**
     * Get free bytes available on the primary storage partition.
     */
    fun getAvailableStorageBytes(): Long {
        return try {
            val path = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val stat = StatFs(path.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            1024L * 1024L * 1024L // Fallback estimate: 1GB
        }
    }

    /**
     * Get total storage bytes on primary storage partition.
     */
    fun getTotalStorageBytes(): Long {
        return try {
            val path = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val stat = StatFs(path.absolutePath)
            stat.blockCountLong * stat.blockSizeLong
        } catch (_: Exception) {
            1024L * 1024L * 1024L * 32 // Fallback estimate: 32GB
        }
    }

    /**
     * Check if sufficient space is available with safety buffer (25MB).
     */
    fun checkStorageAvailability(requiredBytes: Long) {
        val available = getAvailableStorageBytes()
        val buffer = 25 * 1024 * 1024L // 25MB safety buffer
        if (requiredBytes > 0 && available < (requiredBytes + buffer)) {
            val neededMb = (requiredBytes / (1024 * 1024))
            val availMb = (available / (1024 * 1024))
            throw VideoDownloadException("Not enough storage space. Required: ${neededMb}MB, Available: ${availMb}MB.")
        }
    }

    /**
     * Get the temporary `.part` file where streaming download is written.
     */
    fun getTempFile(downloadId: String): File {
        val tempDir = File(context.cacheDir, "download_chunks").apply { mkdirs() }
        return File(tempDir, "$downloadId.part")
    }

    /**
     * Get the destination file in the app's accessible Movies directory.
     */
    fun getDestinationFile(filename: String): File {
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "Movies").apply { mkdirs() }
        var dest = File(moviesDir, filename)
        var counter = 1
        val baseName = filename.substringBeforeLast(".")
        val ext = filename.substringAfterLast(".", "mp4")

        while (dest.exists()) {
            dest = File(moviesDir, "${baseName}_$counter.$ext")
            counter++
        }
        return dest
    }

    /**
     * Move completed temp file to public destination, register with MediaStore,
     * and generate a thumbnail.
     */
    fun finalizeVideoFile(
        downloadId: String,
        suggestedFilename: String,
        mimeType: String
    ): Pair<File, String?> {
        val tempFile = getTempFile(downloadId)
        if (!tempFile.exists() || tempFile.length() == 0L) {
            throw VideoDownloadException("Download file is missing or empty.")
        }

        val destination = getDestinationFile(suggestedFilename)
        val success = tempFile.renameTo(destination)
        if (!success) {
            // Copy and delete if cross-partition
            tempFile.inputStream().use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.delete()
        }

        // Save video to public phone gallery (Movies/VideoDownloader) so it appears in Photos, Gallery, and File Manager
        saveVideoToPublicGallery(destination, mimeType)

        // Generate thumbnail
        val thumbnailPath = extractVideoThumbnail(destination, downloadId)

        return Pair(destination, thumbnailPath)
    }

    /**
     * Saves the completed video file into the user's phone storage (MediaStore & public Movies/VideoDownloader)
     * so that it immediately appears in the phone's Gallery, Google Photos, and standard video players.
     */
    fun saveVideoToPublicGallery(file: File, mimeType: String): Uri? {
        val safeMime = if (mimeType.isNotBlank() && mimeType.startsWith("video/")) mimeType else "video/mp4"
        val filename = file.name

        // 1. On Android 10+ (Q+), insert and stream bytes to MediaStore in Movies/VideoDownloader
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.TITLE, file.nameWithoutExtension)
                    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Video.Media.MIME_TYPE, safeMime)
                    put(MediaStore.Video.Media.SIZE, file.length())
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoDownloader")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)

                    // Also scan to ensure instant indexing
                    try {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.absolutePath),
                            arrayOf(safeMime),
                            null
                        )
                    } catch (_: Exception) {}

                    return uri
                }
            } catch (_: Exception) {
                // If scoped storage insert fails, fallback to direct public directory copy
            }
        }

        // 2. Direct public storage fallback (Android 9 and below, or when MediaStore is unavailable)
        try {
            val publicMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val folder = File(publicMovies, "VideoDownloader").apply { mkdirs() }
            val publicFile = File(folder, filename)
            if (!publicFile.exists() || publicFile.length() != file.length()) {
                file.inputStream().use { input ->
                    publicFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(publicFile.absolutePath, file.absolutePath),
                arrayOf(safeMime, safeMime),
                null
            )
            return Uri.fromFile(publicFile)
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Exports an additional copy directly to the user's Downloads folder if requested.
     */
    fun exportVideoToPublicDownloads(file: File, mimeType: String): Uri? {
        val safeMime = if (mimeType.isNotBlank() && mimeType.startsWith("video/")) mimeType else "video/mp4"
        val filename = file.name

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.TITLE, file.nameWithoutExtension)
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, safeMime)
                    put(MediaStore.Downloads.SIZE, file.length())
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VideoDownloader")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { inStream ->
                            inStream.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    return uri
                }
            } catch (_: Exception) {
            }
        }

        try {
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val folder = File(publicDownloads, "VideoDownloader").apply { mkdirs() }
            val publicFile = File(folder, filename)
            file.inputStream().use { input ->
                publicFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            MediaScannerConnection.scanFile(context, arrayOf(publicFile.absolutePath), arrayOf(safeMime), null)
            return Uri.fromFile(publicFile)
        } catch (_: Exception) {
        }
        return null
    }

    fun extractVideoThumbnail(videoFile: File, downloadId: String): String? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(1000000) // 1 second
            if (bitmap != null) {
                val thumbDir = File(context.cacheDir, "thumbnails").apply { mkdirs() }
                val thumbFile = File(thumbDir, "thumb_$downloadId.jpg")
                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                bitmap.recycle()
                thumbFile.absolutePath
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {
            }
        }
    }

    fun getVideoUri(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun openVideo(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            val uri = getVideoUri(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Play Video").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    fun shareVideo(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            val uri = getVideoUri(file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share Video").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    fun deleteDownloadFiles(filePath: String?, thumbnailPath: String?, downloadId: String) {
        try {
            if (!filePath.isNullOrBlank()) {
                val file = File(filePath)
                if (file.exists()) file.delete()
            }
            if (!thumbnailPath.isNullOrBlank()) {
                val thumb = File(thumbnailPath)
                if (thumb.exists()) thumb.delete()
            }
            val tempFile = getTempFile(downloadId)
            if (tempFile.exists()) tempFile.delete()
        } catch (_: Exception) {
        }
    }
}
