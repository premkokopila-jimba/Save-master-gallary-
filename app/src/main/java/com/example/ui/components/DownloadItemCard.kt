package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.DownloadEntity
import com.example.model.DownloadProgress
import com.example.model.DownloadStatus
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.RoseError
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadItemCard(
    download: DownloadEntity,
    progress: DownloadProgress?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress?.let {
            if (it.totalBytes > 0) (it.downloadedBytes.toFloat() / it.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
        } ?: download.progressFraction,
        label = "download_progress"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("download_card_${download.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Thumbnail + Title + Status Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail or Video Icon
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!download.thumbnailPath.isNullOrBlank() && File(download.thumbnailPath).exists()) {
                        AsyncImage(
                            model = File(download.thumbnailPath),
                            contentDescription = "Video Thumbnail",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        val iconColor = when (download.status) {
                            DownloadStatus.COMPLETED -> EmeraldGreen
                            DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                            DownloadStatus.PAUSED -> AmberWarning
                            DownloadStatus.FAILED -> RoseError
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Filename & Date
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = download.filename,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    val dateStr = remember(download.createdAt) {
                        SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(Date(download.createdAt))
                    }
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Status Chip
                StatusBadge(download.status)
            }

            // Progress Bar (when downloading, queued, preparing, or paused)
            if (download.status.isActive || download.status == DownloadStatus.PAUSED) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val isIndeterminate = (download.totalBytes <= 0 && download.status == DownloadStatus.DOWNLOADING)
                    if (isIndeterminate) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (download.status == DownloadStatus.PAUSED) AmberWarning else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }

                    // Progress Details: "78%  1.24 GB / 1.58 GB   8.4 MB/s   ~41s remaining"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val percent = progress?.percentage ?: download.progressPercent
                        val downloadedBytes = progress?.downloadedBytes ?: download.downloadedBytes
                        val totalBytes = progress?.totalBytes ?: download.totalBytes

                        val sizeLabel = if (totalBytes > 0) {
                            "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
                        } else {
                            formatBytes(downloadedBytes)
                        }

                        Text(
                            text = if (totalBytes > 0) "$percent% ($sizeLabel)" else sizeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (download.status == DownloadStatus.DOWNLOADING && progress != null && progress.speedBytesPerSec > 0) {
                            val speedStr = formatSpeed(progress.speedBytesPerSec)
                            val etaStr = if (progress.etaSeconds > 0) formatEta(progress.etaSeconds) else ""
                            Text(
                                text = listOf(speedStr, etaStr).filter { it.isNotEmpty() }.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Error display if failed
            AnimatedVisibility(visible = download.status == DownloadStatus.FAILED && !download.errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = RoseError.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = RoseError,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = download.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = RoseError,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (download.status) {
                    DownloadStatus.COMPLETED -> {
                        val fileSizeStr = formatBytes(download.totalBytes)
                        Text(
                            text = fileSizeStr,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )

                        FilledTonalButton(
                            onClick = onOpen,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("open_video_button_${download.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open", fontSize = 13.sp)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = onShare,
                            modifier = Modifier.testTag("share_video_button_${download.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.testTag("delete_video_button_${download.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    DownloadStatus.DOWNLOADING, DownloadStatus.PREPARING -> {
                        OutlinedButton(
                            onClick = onPause,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("pause_download_button_${download.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause", fontSize = 13.sp)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.testTag("cancel_download_button_${download.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Cancel",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    DownloadStatus.PAUSED, DownloadStatus.QUEUED -> {
                        FilledTonalButton(
                            onClick = onResume,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("resume_download_button_${download.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume", fontSize = 13.sp)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.testTag("cancel_download_button_${download.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Cancel",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                        OutlinedButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("retry_download_button_${download.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry", fontSize = 13.sp)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.testTag("delete_download_button_${download.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: DownloadStatus) {
    val (bgColor, textColor, label, icon) = when (status) {
        DownloadStatus.COMPLETED -> Quad(
            EmeraldGreen.copy(alpha = 0.15f),
            EmeraldGreen,
            "Completed",
            Icons.Default.CheckCircle
        )
        DownloadStatus.DOWNLOADING -> Quad(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.primary,
            "Downloading",
            Icons.Default.PlayArrow
        )
        DownloadStatus.PREPARING -> Quad(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            MaterialTheme.colorScheme.primary,
            "Preparing",
            Icons.Default.HourglassEmpty
        )
        DownloadStatus.QUEUED -> Quad(
            AmberWarning.copy(alpha = 0.15f),
            AmberWarning,
            "Queued",
            Icons.Default.HourglassEmpty
        )
        DownloadStatus.PAUSED -> Quad(
            AmberWarning.copy(alpha = 0.15f),
            AmberWarning,
            "Paused",
            Icons.Default.Pause
        )
        DownloadStatus.FAILED -> Quad(
            RoseError.copy(alpha = 0.15f),
            RoseError,
            "Failed",
            Icons.Default.Error
        )
        DownloadStatus.CANCELLED -> Quad(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Cancelled",
            Icons.Default.Cancel
        )
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    return when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSec / (1024f * 1024f))
        bytesPerSec >= 1024 -> String.format(Locale.ROOT, "%.1f KB/s", bytesPerSec / 1024f)
        else -> "$bytesPerSec B/s"
    }
}

private fun formatEta(seconds: Long): String {
    return when {
        seconds >= 3600 -> "~${seconds / 3600}h ${(seconds % 3600) / 60}m"
        seconds >= 60 -> "~${seconds / 60}m ${seconds % 60}s"
        else -> "~${seconds}s"
    }
}
