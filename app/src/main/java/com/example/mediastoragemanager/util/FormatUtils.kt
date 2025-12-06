package com.example.mediastoragemanager.util

import android.content.Context
import android.text.format.Formatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    fun formatBytes(context: Context, bytes: Long): String {
        return Formatter.formatFileSize(context, bytes)
    }

    fun formatDateTime(millis: Long): String {
        if (millis <= 0L) return "Unknown"
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return df.format(Date(millis))
    }
}
