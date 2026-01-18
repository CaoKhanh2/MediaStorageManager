package com.example.mediastoragemanager.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.mediastoragemanager.model.MediaFile
import com.example.mediastoragemanager.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

data class MoveSummary(
    val movedCount: Int,
    val failedCount: Int,
    val totalMovedBytes: Long
)

class MediaRepository(private val context: Context) {

    companion object {
        private const val TAG = "MediaRepository"
        // Increased buffer size to 256KB to optimize copy speed for large video files
        private const val BUFFER_SIZE = 256 * 1024
    }

    /**
     * Scans media files (Images & Videos) from the device's internal storage.
     * Uses MediaStore API to query metadata.
     */
    suspend fun scanMedia(includeImages: Boolean, includeVideos: Boolean): List<MediaFile> = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaFile>()
        val contentResolver = context.contentResolver

        // 1. Scan Images
        if (includeImages) {
            val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATA, // Full path (Deprecated but useful for filtering)
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            // Filter: Only select files located in internal storage to avoid duplicates or external SD card files
            val selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("/storage/emulated/0/%")

            try {
                contentResolver.query(
                    imageUri,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                    val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val relPathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(imageUri, id)
                        val name = cursor.getString(nameCol) ?: "Unknown"
                        val size = cursor.getLong(sizeCol)
                        val mime = cursor.getString(mimeCol)
                        val path = cursor.getString(pathCol)
                        val date = cursor.getLong(dateCol) * 1000L
                        val relPath = cursor.getString(relPathCol)

                        mediaList.add(
                            MediaFile(
                                id, uri, name, size, mime, relPath, path, date, MediaType.IMAGE
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning images", e)
            }
        }

        // 2. Scan Videos
        if (includeVideos) {
            val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.RELATIVE_PATH
            )
            val selection = "${MediaStore.Video.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("/storage/emulated/0/%")

            try {
                contentResolver.query(
                    videoUri,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                    val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val relPathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(videoUri, id)
                        val name = cursor.getString(nameCol) ?: "Unknown"
                        val size = cursor.getLong(sizeCol)
                        val mime = cursor.getString(mimeCol)
                        val path = cursor.getString(pathCol)
                        val date = cursor.getLong(dateCol) * 1000L
                        val relPath = cursor.getString(relPathCol)

                        mediaList.add(
                            MediaFile(
                                id, uri, name, size, mime, relPath, path, date, MediaType.VIDEO
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning videos", e)
            }
        }

        return@withContext mediaList
    }

    /**
     * Helper: Copies data from input stream to output stream.
     * Uses a large buffer to optimize performance for large video files.
     */
    private fun copyStream(input: InputStream, output: OutputStream): Long {
        var total = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        return total
    }

    /**
     * Main function: Moves media files to the SD Card using Storage Access Framework (SAF).
     * Preserves directory structure and handles name conflicts.
     */
    suspend fun moveMediaToSdCard(
        files: List<MediaFile>,
        sdRootUri: Uri,
        onProgress: (processed: Int, total: Int, currentName: String?) -> Unit
    ): MoveSummary = withContext(Dispatchers.IO) {
        var movedCount = 0
        var failedCount = 0
        var totalMovedBytes = 0L

        val total = files.size
        val resolver = context.contentResolver

        // Retrieve DocumentFile representing the SD Card root selected by the user
        val sdRoot = DocumentFile.fromTreeUri(context, sdRootUri)

        // Validate write access
        if (sdRoot == null || !sdRoot.canWrite()) {
            Log.e(TAG, "SD Root is null or not writable: $sdRootUri")
            return@withContext MoveSummary(0, files.size, 0L)
        }

        files.forEachIndexed { index, file ->
            // Notify progress callback
            onProgress(index + 1, total, file.displayName)

            var targetFile: DocumentFile? = null
            try {
                // Step 1: Create target directory structure based on relative path
                // e.g., if original is in DCIM/Camera, ensure SDCard/DCIM/Camera exists
                val targetFolder = getOrCreateTargetDirectory(sdRoot, file.relativePath)

                if (targetFolder == null || !targetFolder.isDirectory) {
                    Log.e(TAG, "Failed to create directory structure for ${file.relativePath}")
                    failedCount++
                    return@forEachIndexed
                }

                // Step 2: Create the target file (Auto-renames if conflict exists)
                targetFile = createTargetFile(targetFolder, file.displayName, file.mimeType)

                if (targetFile == null) {
                    Log.e(TAG, "Failed to create file document: ${file.displayName}")
                    failedCount++
                    return@forEachIndexed
                }

                // Step 3: Open streams and copy data
                val input = resolver.openInputStream(file.uri)
                val output = resolver.openOutputStream(targetFile.uri)

                if (input == null || output == null) {
                    Log.e(TAG, "Failed to open streams for ${file.displayName}")
                    input?.close()
                    output?.close()
                    // Clean up the empty file created
                    targetFile.delete()
                    failedCount++
                    return@forEachIndexed
                }

                var successCopy = false
                // Use 'use' block to automatically close streams
                input.use { inp ->
                    output.use { out ->
                        val bytes = copyStream(inp, out)
                        // Verification: If original file had content but 0 bytes were copied, mark as failed
                        if (bytes > 0 || file.sizeBytes == 0L) {
                            successCopy = true
                        }
                    }
                }

                if (!successCopy) {
                    Log.e(TAG, "Copy stream returned 0 bytes or failed for ${file.displayName}")
                    targetFile.delete()
                    failedCount++
                    return@forEachIndexed
                }

                // Step 4: Delete the original file (Perform the "Move" operation)
                if (deleteOriginalFile(file.uri)) {
                    movedCount++
                    totalMovedBytes += file.sizeBytes
                } else {
                    Log.w(TAG, "Copied but failed to delete original: ${file.displayName}")
                    // Decision: Count as failed to avoid data loss, keep the copy
                    failedCount++
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception moving file ${file.displayName}", e)
                try { targetFile?.delete() } catch (ex: Exception) { }
                failedCount++
            }
        }

        MoveSummary(movedCount, failedCount, totalMovedBytes)
    }

    // --- HELPER METHODS ---

    /**
     * Recursively creates directories based on the relative path string.
     */
    private fun getOrCreateTargetDirectory(root: DocumentFile, relativePath: String?): DocumentFile? {
        if (relativePath.isNullOrEmpty()) return root

        // Normalize path: remove leading/trailing slashes
        val cleanPath = relativePath.trim('/')
        if (cleanPath.isEmpty()) return root

        val parts = cleanPath.split("/")
        var currentDir = root

        for (part in parts) {
            val existing = currentDir.findFile(part)

            if (existing != null) {
                if (existing.isDirectory) {
                    currentDir = existing
                } else {
                    // Critical Error: A FILE exists with the same name as the DIRECTORY we want to create
                    Log.e(TAG, "Conflict: '$part' exists but is a FILE. Cannot create directory.")
                    return null
                }
            } else {
                // Directory doesn't exist, try to create it
                val created = currentDir.createDirectory(part)
                if (created == null) {
                    Log.e(TAG, "Failed to create directory '$part' inside '${currentDir.name}'. Check permissions.")
                    return null
                }
                currentDir = created
            }
        }
        return currentDir
    }

    /**
     * Creates a new file in the target directory.
     * Automatically handles name conflicts by appending a counter (e.g., video (1).mp4).
     */
    private fun createTargetFile(dir: DocumentFile, displayName: String, mimeType: String?): DocumentFile? {
        val mime = mimeType ?: "application/octet-stream"

        // If file name is unique, create it directly
        if (dir.findFile(displayName) == null) {
            return dir.createFile(mime, displayName)
        }

        // If conflict exists, generate a unique name
        val nameWithoutExt = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', "")
        val extWithDot = if (ext.isNotEmpty()) ".$ext" else ""

        var counter = 1
        var newName = "$nameWithoutExt ($counter)$extWithDot"

        while (dir.findFile(newName) != null) {
            counter++
            newName = "$nameWithoutExt ($counter)$extWithDot"
        }

        return dir.createFile(mime, newName)
    }

    /**
     * Deletes the original file using ContentResolver.
     */
    private fun deleteOriginalFile(uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete original file: $uri", e)
            false
        }
    }
}