package com.example.mediastoragemanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.mediastoragemanager.R
import com.example.mediastoragemanager.data.MediaRepository
import com.example.mediastoragemanager.data.MoveSummary
import com.example.mediastoragemanager.model.MediaFile
import com.example.mediastoragemanager.util.MediaTransferHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MoveForegroundService : Service() {

    companion object {
        // Actions for communicating with the UI
        const val ACTION_START_MOVE = "com.example.mediastoragemanager.action.START_MOVE"
        const val ACTION_MOVE_PROGRESS = "com.example.mediastoragemanager.action.MOVE_PROGRESS"
        const val ACTION_MOVE_FINISHED = "com.example.mediastoragemanager.action.MOVE_FINISHED"

        // Extras keys
        const val EXTRA_SD_URI = "extra_sd_uri"
        const val EXTRA_PROGRESS_PROCESSED = "extra_progress_processed"
        const val EXTRA_PROGRESS_TOTAL = "extra_progress_total"
        const val EXTRA_PROGRESS_NAME = "extra_progress_name"

        const val EXTRA_FINISHED_MOVED = "extra_finished_moved"
        const val EXTRA_FINISHED_FAILED = "extra_finished_failed"
        const val EXTRA_FINISHED_BYTES = "extra_finished_bytes"

        private const val CHANNEL_ID = "move_media_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "MoveForegroundService"

        // Update notification at most once per second to prevent System UI lag on old devices
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1000L
    }

    // Service lifecycle scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var mediaRepository: MediaRepository

    // WakeLock to prevent Samsung devices from sleeping during long operations
    private var wakeLock: PowerManager.WakeLock? = null

    private val localBroadcast by lazy {
        LocalBroadcastManager.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        mediaRepository = MediaRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START_MOVE) {
            return START_NOT_STICKY
        }

        // [CRITICAL] Fix for Android 14+ Crash:
        // startForeground MUST be called immediately within 5 seconds of service start.
        val initialNotification = buildProgressNotification(0, 0, null)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 14 requires specifying the service type (dataSync) in code and manifest
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            // Fallback
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        // Acquire WakeLock to keep CPU running
        acquireWakeLock()

        // Now safe to perform heavy loading operations
        val files: List<MediaFile> = MediaTransferHelper.loadSelection(applicationContext) ?: emptyList()
        val sdUri: Uri? = intent.getStringExtra(EXTRA_SD_URI)?.let(Uri::parse)

        // Validation: If data is missing, stop service gracefully
        if (files.isEmpty() || sdUri == null) {
            Log.e(TAG, "No files found in cache or SD card uri missing")
            finalizeService(startId, MoveSummary(0, 0, 0))
            return START_NOT_STICKY
        }

        val nm = getSystemService<NotificationManager>()
        nm?.notify(NOTIFICATION_ID, buildProgressNotification(0, files.size, null))

        serviceScope.launch {
            var summary = MoveSummary(0, files.size, 0L)
            var lastUpdateTime = 0L

            try {
                // Start the heavy move operation
                summary = mediaRepository.moveMediaToSdCard(files, sdUri) { processed, total, name ->
                    val currentTime = System.currentTimeMillis()

                    // Optimization: Only update notification if 1 second has passed or operation is complete
                    // This prevents the "System UI isn't responding" error on Samsung S9+
                    if (currentTime - lastUpdateTime > NOTIFICATION_UPDATE_INTERVAL_MS || processed == total) {
                        lastUpdateTime = currentTime

                        // 1. Update System Notification
                        nm?.notify(NOTIFICATION_ID, buildProgressNotification(processed, total, name))

                        // 2. Send Broadcast to update UI
                        val progressIntent = Intent(ACTION_MOVE_PROGRESS).apply {
                            putExtra(EXTRA_PROGRESS_PROCESSED, processed)
                            putExtra(EXTRA_PROGRESS_TOTAL, total)
                            putExtra(EXTRA_PROGRESS_NAME, name)
                        }
                        localBroadcast.sendBroadcast(progressIntent)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while moving files", e)
            } finally {
                finalizeService(startId, summary)
            }
        }

        return START_REDELIVER_INTENT
    }

    private fun finalizeService(startId: Int, summary: MoveSummary) {
        // Operation finished: Send final broadcast
        val finishedIntent = Intent(ACTION_MOVE_FINISHED).apply {
            putExtra(EXTRA_FINISHED_MOVED, summary.movedCount)
            putExtra(EXTRA_FINISHED_FAILED, summary.failedCount)
            putExtra(EXTRA_FINISHED_BYTES, summary.totalMovedBytes)
        }
        localBroadcast.sendBroadcast(finishedIntent)

        // Show completion notification
        val nm = getSystemService<NotificationManager>()
        nm?.notify(NOTIFICATION_ID, buildFinishedNotification(summary))

        // Clean up cache file
        MediaTransferHelper.clearSelection(applicationContext)

        // Release WakeLock
        releaseWakeLock()

        // Stop service (but keep the finished notification visible)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf(startId)
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MediaStorageManager::MoveServiceWakelock"
            )
            // Set a timeout of 6 hours for 30GB transfer safety
            wakeLock?.acquire(6 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Vietnamese Channel Name/Desc
            val channelName = getString(R.string.notif_channel_move_name)
            val description = getString(R.string.notif_channel_move_desc)
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                this.description = description
            }
            val nm = getSystemService<NotificationManager>()
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(processed: Int, total: Int, currentName: String?): Notification {
        // Vietnamese Notification Title
        val title = getString(R.string.notif_move_in_progress_title)

        // Truncate name if too long to avoid layout issues
        val safeName = if ((currentName?.length ?: 0) > 30) "..." + currentName?.takeLast(30) else currentName ?: ""

        val content = if (total > 0) {
            getString(R.string.notif_move_in_progress_text, processed, total, safeName)
        } else {
            getString(R.string.notif_move_preparing)
        }

        val max = 100
        val progress = if (total > 0) (processed * max / total).coerceIn(0, max) else 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, progress, total == 0)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun buildFinishedNotification(summary: MoveSummary): Notification {
        val mb = (summary.totalMovedBytes / (1024 * 1024)).toInt()
        val title = getString(R.string.notif_move_finished_title)
        // Vietnamese Summary
        val text = getString(R.string.notif_move_finished_text, summary.movedCount, summary.failedCount, mb)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
    }
}