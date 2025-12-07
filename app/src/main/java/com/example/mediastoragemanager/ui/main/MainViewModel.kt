package com.example.mediastoragemanager.ui.main

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.mediastoragemanager.R
import com.example.mediastoragemanager.data.MediaRepository
import com.example.mediastoragemanager.data.StorageRepository
import com.example.mediastoragemanager.model.MediaFile
import com.example.mediastoragemanager.service.MoveForegroundService
import com.example.mediastoragemanager.util.MediaTransferHelper
import com.example.mediastoragemanager.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MediaSelectionMode {
    IMAGES,
    VIDEOS,
    BOTH
}

data class MainUiState(
    val internalStorage: com.example.mediastoragemanager.model.StorageInfo? = null,
    val sdCardStorage: com.example.mediastoragemanager.model.StorageInfo? = null,
    val isLoadingStorage: Boolean = false,

    val isScanning: Boolean = false,
    val isMoving: Boolean = false,

    val mediaFiles: List<MediaFile> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),

    val scanSummaryText: String = "Found 0 files. Selected 0.",
    val moveProcessed: Int = 0,
    val moveTotal: Int = 0,
    val moveCurrentFileName: String? = null,

    val sdCardUri: Uri? = null,

    val errorMessage: String? = null,
    val moveResultMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val storageRepository = StorageRepository(application.applicationContext)
    private val mediaRepository = MediaRepository(application.applicationContext)
    private val prefs = PreferencesManager(application.applicationContext)
    private val localBroadcastManager = LocalBroadcastManager.getInstance(application)

    private val _uiState = MutableLiveData(
        MainUiState(sdCardUri = prefs.getSdCardUri())
    )
    val uiState: LiveData<MainUiState> = _uiState

    // Receiver to handle updates from MoveForegroundService
    private val moveProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MoveForegroundService.ACTION_MOVE_PROGRESS -> {
                    val processed = intent.getIntExtra(MoveForegroundService.EXTRA_PROGRESS_PROCESSED, 0)
                    val total = intent.getIntExtra(MoveForegroundService.EXTRA_PROGRESS_TOTAL, 0)
                    val name = intent.getStringExtra(MoveForegroundService.EXTRA_PROGRESS_NAME)

                    _uiState.value = _uiState.value?.copy(
                        moveProcessed = processed,
                        moveTotal = total,
                        moveCurrentFileName = name
                    )
                }
                MoveForegroundService.ACTION_MOVE_FINISHED -> {
                    val moved = intent.getIntExtra(MoveForegroundService.EXTRA_FINISHED_MOVED, 0)
                    val failed = intent.getIntExtra(MoveForegroundService.EXTRA_FINISHED_FAILED, 0)
                    val bytes = intent.getLongExtra(MoveForegroundService.EXTRA_FINISHED_BYTES, 0L)
                    val mb = (bytes / (1024 * 1024)).toInt()

                    // [FIX] Use Vietnamese string resource for the result message
                    // Using application context to get string resource
                    val summaryText = try {
                        getApplication<Application>().getString(
                            R.string.notif_move_finished_text,
                            moved, failed, mb
                        )
                    } catch (e: Exception) {
                        "Đã chuyển $moved tệp, thất bại $failed tệp. Tổng dung lượng: $mb MB."
                    }

                    // Remove moved files from the list
                    val current = _uiState.value
                    val remaining = current?.mediaFiles?.filterNot { file ->
                        current.selectedIds.contains(file.id)
                    } ?: emptyList()

                    _uiState.value = current?.copy(
                        isMoving = false,
                        mediaFiles = remaining,
                        selectedIds = emptySet(),
                        scanSummaryText = "Tìm thấy ${remaining.size} tệp. Đã chọn 0.",
                        moveProcessed = 0,
                        moveTotal = 0,
                        moveCurrentFileName = null,
                        moveResultMessage = summaryText // Trigger the dialog in UI
                    )
                    // Refresh storage info
                    loadStorageInfo()
                }
            }
        }
    }

    init {
        loadStorageInfo()
        // Register receiver
        val filter = IntentFilter().apply {
            addAction(MoveForegroundService.ACTION_MOVE_PROGRESS)
            addAction(MoveForegroundService.ACTION_MOVE_FINISHED)
        }
        localBroadcastManager.registerReceiver(moveProgressReceiver, filter)
    }

    override fun onCleared() {
        super.onCleared()
        localBroadcastManager.unregisterReceiver(moveProgressReceiver)
    }

    fun loadStorageInfo() {
        viewModelScope.launch {
            _uiState.value = _uiState.value?.copy(isLoadingStorage = true, errorMessage = null)
            try {
                val internal = withContext(Dispatchers.IO) { storageRepository.getInternalStorageInfo() }
                val sdCard = withContext(Dispatchers.IO) { storageRepository.getSdCardStorageInfo() }
                _uiState.value = _uiState.value?.copy(
                    internalStorage = internal,
                    sdCardStorage = sdCard,
                    isLoadingStorage = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value?.copy(
                    isLoadingStorage = false,
                    errorMessage = e.localizedMessage ?: "Failed to load storage information."
                )
            }
        }
    }

    fun scanMedia(selectionMode: MediaSelectionMode) {
        val includeImages = (selectionMode == MediaSelectionMode.IMAGES || selectionMode == MediaSelectionMode.BOTH)
        val includeVideos = (selectionMode == MediaSelectionMode.VIDEOS || selectionMode == MediaSelectionMode.BOTH)

        if (!includeImages && !includeVideos) {
            _uiState.value = _uiState.value?.copy(errorMessage = "Vui lòng chọn ít nhất một loại tệp để quét.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value?.copy(isScanning = true, errorMessage = null, moveResultMessage = null)
            val files: List<MediaFile> = withContext(Dispatchers.IO) {
                mediaRepository.scanMedia(includeImages, includeVideos)
            }
            _uiState.value = _uiState.value?.copy(
                isScanning = false,
                mediaFiles = files,
                selectedIds = emptySet(),
                scanSummaryText = "Tìm thấy ${files.size} tệp. Đã chọn 0."
            )
        }
    }

    fun onFileSelectionChanged(fileId: Long, isSelected: Boolean) {
        val current = _uiState.value ?: return
        val newSelected = current.selectedIds.toMutableSet()
        if (isSelected) newSelected.add(fileId) else newSelected.remove(fileId)

        val newFiles = current.mediaFiles.map {
            if (it.id == fileId) it.copy(isSelected = isSelected) else it
        }
        _uiState.value = current.copy(
            mediaFiles = newFiles,
            selectedIds = newSelected,
            scanSummaryText = "Tìm thấy ${newFiles.size} tệp. Đã chọn ${newSelected.size}."
        )
    }

    fun toggleSelectAll() {
        val current = _uiState.value ?: return
        if (current.mediaFiles.isEmpty()) return

        val allSelected = current.mediaFiles.size == current.selectedIds.size
        val newSelected = if (allSelected) emptySet() else current.mediaFiles.map { it.id }.toSet()
        val newFiles = current.mediaFiles.map { it.copy(isSelected = !allSelected) }

        _uiState.value = current.copy(
            mediaFiles = newFiles,
            selectedIds = newSelected,
            scanSummaryText = "Tìm thấy ${newFiles.size} tệp. Đã chọn ${newSelected.size}."
        )
    }

    fun setSdCardUri(uri: Uri) {
        prefs.saveSdCardUri(uri)
        _uiState.value = _uiState.value?.copy(sdCardUri = uri)
    }

    fun clearSdCardUri() {
        prefs.clearSdCardUri()
        _uiState.value = _uiState.value?.copy(sdCardUri = null)
    }

    /**
     * Move selected media files to SD card using Foreground Service
     */
    fun moveSelectedToSdCard() {
        val current = _uiState.value ?: return
        val sdUri = current.sdCardUri

        if (sdUri == null) {
            _uiState.value = current.copy(errorMessage = "Vui lòng chọn thẻ nhớ SD trước khi chuyển.")
            return
        }

        val filesToMove = current.mediaFiles.filter { current.selectedIds.contains(it.id) }
        if (filesToMove.isEmpty()) {
            _uiState.value = current.copy(errorMessage = "Vui lòng chọn ít nhất một tệp.")
            return
        }

        viewModelScope.launch {
            _uiState.value = current.copy(isMoving = true, errorMessage = null, moveResultMessage = null)

            // 1. Save list to cache
            val saveSuccess = withContext(Dispatchers.IO) {
                MediaTransferHelper.saveSelection(getApplication(), filesToMove)
            }

            if (!saveSuccess) {
                _uiState.value = current.copy(
                    isMoving = false,
                    errorMessage = "Lỗi: Không thể chuẩn bị tệp để chuyển."
                )
                return@launch
            }

            // 2. Start Service
            val context = getApplication<Application>()
            val intent = Intent(context, MoveForegroundService::class.java).apply {
                action = MoveForegroundService.ACTION_START_MOVE
                putExtra(MoveForegroundService.EXTRA_SD_URI, sdUri.toString())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    fun consumeError() {
        _uiState.value = _uiState.value?.copy(errorMessage = null)
    }

    fun consumeMoveResultMessage() {
        _uiState.value = _uiState.value?.copy(moveResultMessage = null)
    }
}