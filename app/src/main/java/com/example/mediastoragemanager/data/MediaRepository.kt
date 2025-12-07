package com.example.mediastoragemanager.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.mediastoragemanager.model.MediaFile
import com.example.mediastoragemanager.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
    }

    // Simple stream copy helper used by moveMediaToSdCard
    private fun copyStream(input: InputStream, output: OutputStream): Long {
        var total = 0L
        val buffer = ByteArray(64 * 1024) // Tăng buffer lên 64KB (tối ưu cho Video)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        return total
    }

    /**
     * Xóa file gốc dùng ContentResolver (Phiên bản mới nhận Uri)
     */
    private fun deleteOriginalFile(uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete original file: $uri", e)
            false
        }
    }

    /**
     * Deletes a row in MediaStore by ID if direct delete by Uri did not work.
     */
    private fun deleteFromMediaStoreById(id: Long, type: MediaType): Boolean {
        return try {
            val collectionUri = when (type) {
                MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val where = "${MediaStore.MediaColumns._ID}=?"
            val args = arrayOf(id.toString())
            val rows = context.contentResolver.delete(collectionUri, where, args)
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete from MediaStore by id", e)
            false
        }
    }

    /**
     * Scan internal primary external storage media using MediaStore.
     * includeImages/includeVideos decide which collections to query.
     */
    suspend fun scanMedia(
        includeImages: Boolean,
        includeVideos: Boolean
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val result = mutableListOf<MediaFile>()

        // On Android 10+ use the primary external volume (internal shared storage)
        // so that files on removable SD card are not included.
        val imagesCollectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val videosCollectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        if (includeImages) {
            result += queryCollection(imagesCollectionUri, MediaType.IMAGE)
        }
        if (includeVideos) {
            result += queryCollection(videosCollectionUri, MediaType.VIDEO)
        }

        // Extra safety: keep only files clearly on internal shared storage
        val filtered = result.filter { file ->
            val path = file.fullPath
            if (path == null) {
                // If path is unknown (scoped storage), assume it is on primary storage
                true
            } else {
                path.startsWith("/storage/emulated/0/") ||
                        path.startsWith("/sdcard/") ||
                        path.startsWith("/mnt/sdcard/")
            }
        }

        filtered.sortedBy { it.displayName.lowercase() }
    }

    private fun queryCollection(
        collectionUri: Uri,
        type: MediaType
    ): List<MediaFile> {
        val resolver = context.contentResolver
        val items = mutableListOf<MediaFile>()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH, // Android Q+
            MediaStore.MediaColumns.DATA,          // Deprecated but useful for older devices
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        val selection = null
        val selectionArgs = null
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        try {
            resolver.query(
                collectionUri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol =
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val relPathCol =
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val dataCol =
                    cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val dateModifiedCol =
                    cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "Unknown"
                    val sizeBytes = cursor.getLong(sizeCol)
                    val mimeType = if (mimeCol != -1 && !cursor.isNull(mimeCol)) {
                        cursor.getString(mimeCol)
                    } else {
                        null
                    }

                    val relativePath = if (relPathCol != -1 && !cursor.isNull(relPathCol)) {
                        cursor.getString(relPathCol)
                    } else {
                        null
                    }

                    val fullPath = if (dataCol != -1 && !cursor.isNull(dataCol)) {
                        // For older devices, DATA gives full filesystem path
                        cursor.getString(dataCol)
                    } else {
                        // For newer devices with scoped storage, approximate full path
                        if (relativePath != null) {
                            "/storage/emulated/0/$relativePath$displayName"
                        } else {
                            null
                        }
                    }

                    val dateModifiedSeconds =
                        if (dateModifiedCol != -1 && !cursor.isNull(dateModifiedCol)) {
                            cursor.getLong(dateModifiedCol)
                        } else {
                            0L
                        }
                    val lastModifiedMillis = dateModifiedSeconds * 1000L

                    val contentUri = ContentUris.withAppendedId(collectionUri, id)

                    items.add(
                        MediaFile(
                            id = id,
                            uri = contentUri,
                            displayName = displayName,
                            sizeBytes = sizeBytes,
                            mimeType = mimeType,
                            relativePath = relativePath,
                            fullPath = fullPath,
                            lastModifiedMillis = lastModifiedMillis,
                            type = type
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // If permissions are missing, avoid crashing and just log the issue.
            Log.e(TAG, "SecurityException querying MediaStore for $collectionUri", e)
            return items
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error querying MediaStore for $collectionUri", e)
            return items
        }

        return items
    }

    /**
     * Move selected media files to SD card using SAF.
     * Preserves directory structure based on relativePath when available.
     */
    /**
     * Di chuyển media sang thẻ nhớ (SD Card)
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

        // Lấy DocumentFile gốc từ URI
        val sdRoot = DocumentFile.fromTreeUri(context, sdRootUri)

        if (sdRoot == null || !sdRoot.canWrite()) {
            Log.e(TAG, "SD Root is null or not writable: $sdRootUri")
            return@withContext MoveSummary(0, files.size, 0L)
        }

        files.forEachIndexed { index, file ->
            onProgress(index + 1, total, file.displayName)

            var targetFile: DocumentFile? = null
            try {
                // [SỬA LỖI TẠI ĐÂY] Truyền 'file.relativePath' (String) thay vì 'file' (Object)
                val targetFolder = getOrCreateTargetDirectory(sdRoot, file.relativePath)

                if (targetFolder == null || !targetFolder.isDirectory) {
                    Log.e(TAG, "Failed to create directory structure for ${file.relativePath}")
                    failedCount++
                    return@forEachIndexed
                }

                // [SỬA LỖI TẠI ĐÂY] Truyền displayName và mimeType thay vì 'file'
                targetFile = createTargetFile(targetFolder, file.displayName, file.mimeType)

                if (targetFile == null) {
                    Log.e(TAG, "Failed to create file document: ${file.displayName}")
                    failedCount++
                    return@forEachIndexed
                }

                // Copy dữ liệu
                val input = resolver.openInputStream(file.uri)
                val output = resolver.openOutputStream(targetFile.uri)

                if (input == null || output == null) {
                    Log.e(TAG, "Failed to open streams for ${file.displayName}")
                    input?.close()
                    output?.close()
                    targetFile.delete()
                    failedCount++
                    return@forEachIndexed
                }

                var successCopy = false
                input.use { inp ->
                    output.use { out ->
                        val bytes = copyStream(inp, out)
                        if (bytes > 0 || file.sizeBytes == 0L) {
                            successCopy = true
                        }
                    }
                }

                if (!successCopy) {
                    Log.e(TAG, "Copy failed for ${file.displayName}")
                    targetFile.delete()
                    failedCount++
                    return@forEachIndexed
                }

                // Xóa file gốc
                if (deleteOriginalFile(file.uri)) {
                    movedCount++
                    totalMovedBytes += file.sizeBytes
                } else {
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

    /**
     * Wrapper used by moveMediaToSdCard to get or create the target directory on SD card
     * that matches the original media relative path.
     */
    private fun getOrCreateTargetDirectory(root: DocumentFile, relativePath: String?): DocumentFile? {
        // Nếu không có đường dẫn con, trả về thư mục gốc
        if (relativePath.isNullOrEmpty()) return root

        // Chuẩn hóa path: bỏ dấu / ở đầu và cuối
        val cleanPath = relativePath.trim('/')
        if (cleanPath.isEmpty()) return root

        val parts = cleanPath.split("/")
        var currentDir = root

        for (part in parts) {
            // Tìm xem thư mục/file có tên này đã tồn tại chưa
            val existing = currentDir.findFile(part)

            if (existing != null) {
                if (existing.isDirectory) {
                    // Nếu đã có thư mục -> Đi tiếp vào trong
                    currentDir = existing
                } else {
                    // LỖI: Đã có FILE trùng tên với THƯ MỤC định tạo
                    Log.e(TAG, "Conflict: '$part' exists but is a FILE. Cannot create directory.")
                    return null
                }
            } else {
                // Nếu chưa có -> Tạo thư mục mới
                val created = currentDir.createDirectory(part)
                if (created == null) {
                    // LỖI: Hệ thống từ chối tạo thư mục (Thường do sai quyền Write)
                    Log.e(TAG, "Failed to create directory '$part' inside '${currentDir.name}'. Check SD Card permissions.")
                    return null
                }
                currentDir = created
            }
        }
        return currentDir
    }

    /**
     * Creates the target DocumentFile on SD card, handling name conflicts by
     * generating a new name with a timestamp suffix.
     */
    private fun createTargetFile(dir: DocumentFile, displayName: String, mimeType: String?): DocumentFile? {
        val mime = mimeType ?: "application/octet-stream"

        if (dir.findFile(displayName) == null) {
            return dir.createFile(mime, displayName)
        }

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

    private fun ensureTargetDirectory(
        rootDoc: DocumentFile,
        file: MediaFile
    ): DocumentFile {
        // Prefer RELATIVE_PATH if available (Android 10+)
        val relative = when {
            !file.relativePath.isNullOrBlank() -> file.relativePath
            !file.fullPath.isNullOrBlank() -> {
                val path = file.fullPath
                path.substringAfter("/storage/emulated/0/", "")
                    .substringBeforeLast('/', "")
            }
            else -> defaultRelativePathForType(file.type)
        }

        val cleaned = relative.trim().trim('/')

        var current = rootDoc
        if (cleaned.isNotEmpty()) {
            val segments = cleaned.split("/").filter { it.isNotBlank() }
            for (segment in segments) {
                val existing = current.findFile(segment)
                current = if (existing != null && existing.isDirectory) {
                    existing
                } else {
                    current.createDirectory(segment) ?: current
                }
            }
        }
        return current
    }

    private fun defaultRelativePathForType(type: MediaType): String {
        return when (type) {
            MediaType.IMAGE -> "Pictures"
            MediaType.VIDEO -> "Movies"
        }
    }

    private fun generateSafeFileName(
        parent: DocumentFile,
        originalName: String
    ): String {
        val existing = parent.findFile(originalName)
        if (existing == null) return originalName

        val dotIndex = originalName.lastIndexOf('.')
        val base = if (dotIndex != -1) originalName.substring(0, dotIndex) else originalName
        val ext = if (dotIndex != -1) originalName.substring(dotIndex) else ""
        val timestamp = System.currentTimeMillis()

        return "${base}_$timestamp$ext"
    }

    private fun guessMimeType(file: MediaFile): String {
        val lower = file.displayName.lowercase()
        return when (file.type) {
            MediaType.IMAGE -> when {
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".webp") -> "image/webp"
                else -> "image/jpeg"
            }

            MediaType.VIDEO -> when {
                lower.endsWith(".mp4") -> "video/mp4"
                lower.endsWith(".mkv") -> "video/x-matroska"
                else -> "video/*"
            }
        }
    }

    private fun copyFileContent(sourceUri: Uri, targetUri: Uri): Boolean {
        val resolver = context.contentResolver
        return try {
            resolver.openInputStream(sourceUri).use { input ->
                resolver.openOutputStream(targetUri).use { output ->
                    if (input == null || output == null) {
                        throw IOException("Input or output stream is null")
                    }
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read = input.read(buffer)
                    while (read != -1) {
                        output.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                    output.flush()
                }
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error copying file from $sourceUri to $targetUri", e)
            false
        }
    }
}
