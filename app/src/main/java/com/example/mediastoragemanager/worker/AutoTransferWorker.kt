package com.example.mediastoragemanager.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.mediastoragemanager.R
import com.example.mediastoragemanager.data.MediaRepository
import com.example.mediastoragemanager.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

class AutoTransferWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "auto_transfer_channel"
        private const val NOTIF_ID = 2002
        private const val TAG = "AutoTransferWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = PreferencesManager(applicationContext)
        val schedule = prefs.getSchedule()
        val sdUri = prefs.getSdCardUri()

        // 1. Basic Validation
        if (!schedule.isEnabled || sdUri == null) {
            return@withContext Result.success()
        }

        // 2. [NEW] Check if today is a selected day
        // Calendar.DAY_OF_WEEK returns integers: Sunday=1, Monday=2, ... Saturday=7
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).toString()

        if (!schedule.selectedDays.contains(today)) {
            Log.d(TAG, "Today (Day $today) is not scheduled. Skipping.")
            // Return success so the worker is rescheduled for tomorrow/next run
            return@withContext Result.success()
        }

        // 3. Start Foreground Service (Required for long-running tasks on modern Android)
        setForeground(createForegroundInfo(applicationContext.getString(R.string.notif_move_preparing)))

        val repository = MediaRepository(applicationContext)

        try {
            // 4. Scan and Move Logic
            val files = repository.scanMedia(schedule.isImages, schedule.isVideos)
            if (files.isEmpty()) {
                // Optional: You can choose not to notify if nothing is found to be less intrusive
                return@withContext Result.success()
            }

            val summary = repository.moveMediaToSdCard(files, sdUri) { processed, total, name ->
                if (processed % 5 == 0 || processed == total) {
                    updateNotification(processed, total, name)
                }
            }

            // 5. Completion Notification
            val mb = (summary.totalMovedBytes / (1024 * 1024)).toInt()
            val resultMsg = "Auto-move complete: ${summary.movedCount} files (${mb}MB)."
            showFinishedNotification(resultMsg)

            return@withContext Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Auto transfer failed", e)
            showFinishedNotification("Auto-move failed: ${e.message}")
            return@withContext Result.failure()
        }
    }

    private fun createForegroundInfo(progress: String): ForegroundInfo {
        createChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Auto Media Transfer")
            .setContentText(progress)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIF_ID, notification)
    }

    private fun updateNotification(processed: Int, total: Int, name: String?) {
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Auto Transferring...")
            .setContentText("$processed/$total: $name")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setProgress(total, processed, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(NOTIF_ID, notif)
    }

    private fun showFinishedNotification(message: String) {
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Auto Transfer Report")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIF_ID + 1, notif)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Transfer Task",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}