package com.example.mediastoragemanager.util

import android.content.Context
import android.net.Uri

class PreferencesManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "media_storage_manager_prefs"
        private const val KEY_SD_CARD_URI = "key_sd_card_uri"
        private const val KEY_AUTO_TIME_HOUR = "auto_time_hour"
        private const val KEY_AUTO_TIME_MINUTE = "auto_time_minute"
        private const val KEY_AUTO_TYPE_IMAGES = "auto_type_images"
        private const val KEY_AUTO_TYPE_VIDEOS = "auto_type_videos"
        private const val KEY_IS_AUTO_ENABLED = "is_auto_enabled"
        private const val KEY_AUTO_DAYS = "auto_selected_days"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSdCardUri(uri: Uri) {
        prefs.edit()
            .putString(KEY_SD_CARD_URI, uri.toString())
            .apply()
    }

    fun getSdCardUri(): Uri? {
        val str = prefs.getString(KEY_SD_CARD_URI, null) ?: return null
        return Uri.parse(str)
    }

    fun clearSdCardUri() {
        prefs.edit().remove(KEY_SD_CARD_URI).apply()
    }

    fun saveSchedule(hour: Int, minute: Int, image: Boolean, video: Boolean, enabled: Boolean, days: Set<String>) {
        prefs.edit().apply {
            putInt(KEY_AUTO_TIME_HOUR, hour)
            putInt(KEY_AUTO_TIME_MINUTE, minute)
            putBoolean(KEY_AUTO_TYPE_IMAGES, image)
            putBoolean(KEY_AUTO_TYPE_VIDEOS, video)
            putBoolean(KEY_IS_AUTO_ENABLED, enabled)
            putStringSet(KEY_AUTO_DAYS, days) // Save the set of days
        }.apply()
    }

    fun getSchedule(): ScheduleConfig {
        return ScheduleConfig(
            hour = prefs.getInt(KEY_AUTO_TIME_HOUR, 0),
            minute = prefs.getInt(KEY_AUTO_TIME_MINUTE, 0),
            isImages = prefs.getBoolean(KEY_AUTO_TYPE_IMAGES, true),
            isVideos = prefs.getBoolean(KEY_AUTO_TYPE_VIDEOS, true),
            isEnabled = prefs.getBoolean(KEY_IS_AUTO_ENABLED, false),
            // Default to all days if empty (1..7 represents Sun..Sat in Calendar)
            selectedDays = prefs.getStringSet(KEY_AUTO_DAYS, null) ?: setOf("1", "2", "3", "4", "5", "6", "7")
        )
    }
}
data class ScheduleConfig(
    val hour: Int,
    val minute: Int,
    val isImages: Boolean,
    val isVideos: Boolean,
    val isEnabled: Boolean,
    val selectedDays: Set<String>
)
