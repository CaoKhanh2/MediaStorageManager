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

// DTO dùng để serialize an toàn (tránh lỗi Uri của Android không hỗ trợ Serializable)
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
     * Ghi danh sách file vào bộ nhớ đệm (Cache)
     */
    fun saveSelection(context: Context, files: List<MediaFile>): Boolean {
        return try {
            val file = File(context.cacheDir, CACHE_FILE_NAME)
            // Chuyển đổi sang DTO
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
     * Đọc danh sách file từ bộ nhớ đệm
     */
    fun loadSelection(context: Context): List<MediaFile>? {
        val file = File(context.cacheDir, CACHE_FILE_NAME)
        if (!file.exists()) return null

        return try {
            ObjectInputStream(file.inputStream()).use { stream ->
                @Suppress("UNCHECKED_CAST")
                val dtoList = stream.readObject() as List<MediaFileTransferDto>

                // Chuyển đổi ngược từ DTO sang Model
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
                        isSelected = true // Mặc định là true vì đang trong list selected
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load selection", e)
            null
        }
    }

    /**
     * Xóa cache sau khi dùng xong để giải phóng bộ nhớ
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