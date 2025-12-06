package com.example.mediastoragemanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.mediastoragemanager.R
import com.example.mediastoragemanager.data.MediaRepository
import com.example.mediastoragemanager.data.MoveSummary
import com.example.mediastoragemanager.model.MediaFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MoveForegroundService : Service() {

    companion object {
        const val ACTION_START_MOVE =
            "com.example.mediastoragemanager.action.START_MOVE"

        const val ACTION_MOVE_PROGRESS =
            "com.example.mediastoragemanager.action.MOVE_PROGRESS"
        const val ACTION_MOVE_FINISHED =
            "com.example.mediastoragemanager.action.MOVE_FINISHED"

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
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var mediaRepository: MediaRepository

    // LocalBroadcastManager for in-app broadcasts between Service and UI
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

        val files: List<MediaFile> = SelectedMediaHolder.files ?: emptyList()
        val sdUri: Uri? = intent.getStringExtra(EXTRA_SD_URI)?.let(Uri::parse)

        if (files.isEmpty() || sdUri == null) {
            Log.e(TAG, "No files or SD card uri, stopping service")
            // Notify UI that operation is finished with 0 files
            localBroadcast.sendBroadcast(
                Intent(ACTION_MOVE_FINISHED).apply {
                    putExtra(EXTRA_FINISHED_MOVED, 0)
                    putExtra(EXTRA_FINISHED_FAILED, 0)
                    putExtra(EXTRA_FINISHED_BYTES, 0L)
                }
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Start foreground service with initial notification
        startForeground(
            NOTIFICATION_ID,
            buildProgressNotification(
                processed = 0,
                total = files.size,
                currentName = null
            )
        )

        serviceScope.launch {
            var summary = MoveSummary(
                movedCount = 0,
                failedCount = files.size,
                totalMovedBytes = 0L
            )

            try {
                summary = mediaRepository.moveMediaToSdCard(
                    files,
                    sdUri
                ) { processed, total, name ->
                    // Update system notification
                    val nm = getSystemService<NotificationManager>()
                    nm?.notify(
                        NOTIFICATION_ID,
                        buildProgressNotification(processed, total, name)
                    )

                    // Send progress broadcast to UI
                    val progressIntent = Intent(ACTION_MOVE_PROGRESS).apply {
                        putExtra(EXTRA_PROGRESS_PROCESSED, processed)
                        putExtra(EXTRA_PROGRESS_TOTAL, total)
                        putExtra(EXTRA_PROGRESS_NAME, name)
                    }
                    localBroadcast.sendBroadcast(progressIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while moving files", e)
            } finally {
                // Always send finished broadcast to UI
                val finishedIntent = Intent(ACTION_MOVE_FINISHED).apply {
                    putExtra(EXTRA_FINISHED_MOVED, summary.movedCount)
                    putExtra(EXTRA_FINISHED_FAILED, summary.failedCount)
                    putExtra(EXTRA_FINISHED_BYTES, summary.totalMovedBytes)
                }
                localBroadcast.sendBroadcast(finishedIntent)

                // Final notification
                val nm = getSystemService<NotificationManager>()
                nm?.notify(
                    NOTIFICATION_ID,
                    buildFinishedNotification(summary)
                )

                SelectedMediaHolder.files = null
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }

        // If system kills the service, it will restart with the last intent
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    private fun buildProgressNotification(
        processed: Int,
        total: Int,
        currentName: String?
    ): Notification {
        val title = getString(R.string.notif_move_in_progress_title)
        val content = if (total > 0) {
            val safeName = currentName ?: ""
            getString(
                R.string.notif_move_in_progress_text,
                processed,
                total,
                safeName
            )
        } else {
            getString(R.string.notif_move_preparing)
        }

        val max = 100
        val progress = if (total > 0) {
            (processed * max / total).coerceIn(0, max)
        } else {
            0
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, progress, total == 0)
            .build()
    }

    private fun buildFinishedNotification(summary: MoveSummary): Notification {
        val mb = (summary.totalMovedBytes / (1024 * 1024)).toInt()
        val title = getString(R.string.notif_move_finished_title)
        val text = getString(
            R.string.notif_move_finished_text,
            summary.movedCount,
            summary.failedCount,
            mb
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
    }
}
