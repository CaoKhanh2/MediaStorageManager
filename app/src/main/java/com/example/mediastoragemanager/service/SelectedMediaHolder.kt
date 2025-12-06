package com.example.mediastoragemanager.service

import com.example.mediastoragemanager.model.MediaFile

/**
 * Simple in-memory holder used to pass selected media files
 * from the UI layer to the foreground service without hitting
 * the Binder transaction size limit.
 */
object SelectedMediaHolder {

    @Volatile
    var files: List<MediaFile>? = null
}
