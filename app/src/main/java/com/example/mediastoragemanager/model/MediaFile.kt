package com.example.mediastoragemanager.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.Serializable

@Parcelize
data class MediaFile(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val relativePath: String?,
    val fullPath: String?,
    val lastModifiedMillis: Long,
    val type: MediaType,
    val isSelected: Boolean = false
) : Parcelable, Serializable