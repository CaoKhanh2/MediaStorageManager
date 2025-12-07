package com.example.mediastoragemanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.example.mediastoragemanager.util.MediaTransferHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MoveForegroundService : Service() {

    companion object {
        const val ACTION_START_MOVE = "com.example.mediastoragemanager.action.START_MOVE"
        const val ACTION_MOVE_PROGRESS = "com.example.mediastoragemanager.action.MOVE_PROGRESS"
        const val ACTION_MOVE_FINISHED = "com.example.mediastoragemanager.action.MOVE_FINISHED"

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

        // [QUAN TRỌNG] FIX CRASH: Phải gọi startForeground NGAY LẬP TỨC!
        // Android yêu cầu phải có thông báo trong vòng 5s kể từ khi startForegroundService được gọi.
        val initialNotification = buildProgressNotification(0, 0, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 14 yêu cầu khai báo type nếu có (dataSync)
            try {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e: Exception) {
                // Fallback nếu manifest chưa khai báo type
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        // Sau khi đã an toàn với hệ thống, mới tiến hành load dữ liệu
        val files: List<MediaFile> = MediaTransferHelper.loadSelection(applicationContext) ?: emptyList()
        val sdUri: Uri? = intent.getStringExtra(EXTRA_SD_URI)?.let(Uri::parse)

        if (files.isEmpty() || sdUri == null) {
            Log.e(TAG, "No files found in cache or SD card uri missing")

            // Gửi broadcast báo lỗi về UI để tắt loading
            localBroadcast.sendBroadcast(
                Intent(ACTION_MOVE_FINISHED).apply {
                    putExtra(EXTRA_FINISHED_MOVED, 0)
                    putExtra(EXTRA_FINISHED_FAILED, 0)
                    putExtra(EXTRA_FINISHED_BYTES, 0L)
                }
            )

            // Dọn dẹp cache và dừng service an toàn
            MediaTransferHelper.clearSelection(applicationContext)
            stopForeground(STOP_FOREGROUND_REMOVE) // Xóa thông báo "Preparing"
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Cập nhật lại thông báo với tổng số file thực tế
        val nm = getSystemService<NotificationManager>()
        nm?.notify(NOTIFICATION_ID, buildProgressNotification(0, files.size, null))

        serviceScope.launch {
            var summary = MoveSummary(0, files.size, 0L)
            try {
                summary = mediaRepository.moveMediaToSdCard(files, sdUri) { processed, total, name ->
                    // 1. Update Notification
                    nm?.notify(NOTIFICATION_ID, buildProgressNotification(processed, total, name))

                    // 2. Update UI via Broadcast
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
                // Gửi broadcast kết thúc
                val finishedIntent = Intent(ACTION_MOVE_FINISHED).apply {
                    putExtra(EXTRA_FINISHED_MOVED, summary.movedCount)
                    putExtra(EXTRA_FINISHED_FAILED, summary.failedCount)
                    putExtra(EXTRA_FINISHED_BYTES, summary.totalMovedBytes)
                }
                localBroadcast.sendBroadcast(finishedIntent)

                // Hiện thông báo kết thúc
                nm?.notify(NOTIFICATION_ID, buildFinishedNotification(summary))

                // Dọn dẹp cache
                MediaTransferHelper.clearSelection(applicationContext)

                // Dừng service (nhưng giữ thông báo kết thúc để user xem)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }

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

    private fun buildProgressNotification(processed: Int, total: Int, currentName: String?): Notification {
        val title = getString(R.string.notif_move_in_progress_title)
        val content = if (total > 0) {
            val safeName = currentName ?: ""
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE) // Hiện ngay lập tức
            .build()
    }

    private fun buildFinishedNotification(summary: MoveSummary): Notification {
        val mb = (summary.totalMovedBytes / (1024 * 1024)).toInt()
        val title = getString(R.string.notif_move_finished_title)
        val text = getString(R.string.notif_move_finished_text, summary.movedCount, summary.failedCount, mb)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
    }
}