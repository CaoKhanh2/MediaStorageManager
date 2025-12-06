package com.example.mediastoragemanager.util

import android.content.Context
import android.net.Uri

class PreferencesManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "media_storage_manager_prefs"
        private const val KEY_SD_CARD_URI = "key_sd_card_uri"
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
}
