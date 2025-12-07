package com.example.mediastoragemanager.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.mediastoragemanager.model.MediaFile
import com.example.mediastoragemanager.model.MediaType
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

// Internal DTO to safely serialize MediaFile (since Uri is not Serializable by default in all Android versions)
private data class MediaFileTransferDto(
    val id: Long,
    val uriString: String,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val relativePath: String?,
    val fullPath: String?,
    val lastModifiedMillis: Long,
    val type: MediaType
) : Serializable

object MediaTransferHelper {
    private const val CACHE_FILE_NAME = "selected_media_cache.dat"
    private const val TAG = "MediaTransferHelper"

    /**
     * Saves the list of selected files to the app's cache directory.
     * This avoids TransactionTooLargeException when passing data to the Service.
     */
    fun saveSelection(context: Context, files: List<MediaFile>): Boolean {
        return try {
            val file = File(context.cacheDir, CACHE_FILE_NAME)
            // Map Domain Model to DTO
            val dtoList = files.map {
                MediaFileTransferDto(
                    it.id, it.uri.toString(), it.displayName, it.sizeBytes,
                    it.mimeType, it.relativePath, it.fullPath, it.lastModifiedMillis, it.type
                )
            }

            ObjectOutputStream(file.outputStream()).use {
                it.writeObject(dtoList)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save selection", e)
            false
        }
    }

    /**
     * Loads the list of selected files from the cache directory.
     */
    fun loadSelection(context: Context): List<MediaFile>? {
        val file = File(context.cacheDir, CACHE_FILE_NAME)
        if (!file.exists()) return null

        return try {
            ObjectInputStream(file.inputStream()).use { stream ->
                @Suppress("UNCHECKED_CAST")
                val dtoList = stream.readObject() as List<MediaFileTransferDto>

                // Map DTO back to Domain Model
                dtoList.map { dto ->
                    MediaFile(
                        id = dto.id,
                        uri = Uri.parse(dto.uriString),
                        displayName = dto.displayName,
                        sizeBytes = dto.sizeBytes,
                        mimeType = dto.mimeType,
                        relativePath = dto.relativePath,
                        fullPath = dto.fullPath,
                        lastModifiedMillis = dto.lastModifiedMillis,
                        type = dto.type,
                        isSelected = true // Default to true as they are part of the selection
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load selection", e)
            null
        }
    }

    /**
     * Clears the cache file after the operation is complete.
     */
    fun clearSelection(context: Context) {
        try {
            val file = File(context.cacheDir, CACHE_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cache", e)
        }
    }
}