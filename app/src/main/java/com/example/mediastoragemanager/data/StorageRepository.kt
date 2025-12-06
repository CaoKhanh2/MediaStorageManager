package com.example.mediastoragemanager.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.example.mediastoragemanager.model.StorageInfo
import java.io.File

class StorageRepository(private val context: Context) {

    /**
     * Returns storage info for the internal phone memory.
     */
    fun getInternalStorageInfo(): StorageInfo {
        val internalRoot: File = Environment.getDataDirectory()
        return buildStorageInfo("Internal storage", internalRoot)
    }

    /**
     * Returns storage info for SD card / external storage if present, or null otherwise.
     */
    fun getSdCardStorageInfo(): StorageInfo? {
        val dirs = context.getExternalFilesDirs(null)
        val sdCardDir = dirs.firstOrNull { dir ->
            dir != null && Environment.isExternalStorageRemovable(dir)
        } ?: return null

        return buildStorageInfo("SD card", sdCardDir)
    }

    private fun buildStorageInfo(label: String, file: File): StorageInfo {
        val statFs = StatFs(file.absolutePath)
        val blockSize = statFs.blockSizeLong
        val totalBlocks = statFs.blockCountLong
        val availableBlocks = statFs.availableBlocksLong

        val totalBytes = blockSize * totalBlocks
        val freeBytes = blockSize * availableBlocks
        val usedBytes = totalBytes - freeBytes
        val percentUsed = if (totalBytes > 0L) {
            ((usedBytes * 100L) / totalBytes).toInt()
        } else {
            0
        }

        return StorageInfo(
            label = label,
            totalBytes = totalBytes,
            usedBytes = usedBytes,
            freeBytes = freeBytes,
            percentUsed = percentUsed
        )
    }
}
