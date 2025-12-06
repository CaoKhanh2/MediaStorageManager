package com.example.mediastoragemanager.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.mediastoragemanager.data.MediaRepository
import com.example.mediastoragemanager.data.MoveSummary
import com.example.mediastoragemanager.data.StorageRepository
import com.example.mediastoragemanager.model.MediaFile
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

    private val _uiState = MutableLiveData(
        MainUiState(sdCardUri = prefs.getSdCardUri())
    )
    val uiState: LiveData<MainUiState> = _uiState

    init {
        loadStorageInfo()
    }

    /**
     * Load storage information for internal and SD card.
     */
    fun loadStorageInfo() {
        viewModelScope.launch {
            _uiState.value = _uiState.value?.copy(
                isLoadingStorage = true,
                errorMessage = null
            )

            try {
                val internal = withContext(Dispatchers.IO) {
                    storageRepository.getInternalStorageInfo()
                }
                val sdCard = withContext(Dispatchers.IO) {
                    storageRepository.getSdCardStorageInfo()
                }
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

    /**
     * Start scanning media according to the chosen selection mode.
     */
    fun scanMedia(selectionMode: MediaSelectionMode) {
        val includeImages = (selectionMode == MediaSelectionMode.IMAGES || selectionMode == MediaSelectionMode.BOTH)
        val includeVideos = (selectionMode == MediaSelectionMode.VIDEOS || selectionMode == MediaSelectionMode.BOTH)

        if (!includeImages && !includeVideos) {
            _uiState.value = _uiState.value?.copy(
                errorMessage = "Please select at least one media type to scan."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value?.copy(
                isScanning = true,
                errorMessage = null,
                moveResultMessage = null
            )

            val files: List<MediaFile> = withContext(Dispatchers.IO) {
                mediaRepository.scanMedia(includeImages, includeVideos)
            }

            val selectedIds = emptySet<Long>()
            _uiState.value = _uiState.value?.copy(
                isScanning = false,
                mediaFiles = files,
                selectedIds = selectedIds,
                scanSummaryText = buildScanSummary(files.size, selectedIds.size),
                moveProcessed = 0,
                moveTotal = 0,
                moveCurrentFileName = null
            )
        }
    }

    fun onFileSelectionChanged(fileId: Long, isSelected: Boolean) {
        val current = _uiState.value ?: return
        val newSelected = current.selectedIds.toMutableSet()

        if (isSelected) {
            newSelected.add(fileId)
        } else {
            newSelected.remove(fileId)
        }

        val newFiles = current.mediaFiles.map { file ->
            if (file.id == fileId) {
                file.copy(isSelected = isSelected)
            } else {
                file
            }
        }

        val summary = buildScanSummary(current.mediaFiles.size, newSelected.size)

        _uiState.value = current.copy(
            mediaFiles = newFiles,
            selectedIds = newSelected,
            scanSummaryText = summary
        )
    }

    fun toggleSelectAll() {
        val current = _uiState.value ?: return
        if (current.mediaFiles.isEmpty()) return

        val allSelected = current.mediaFiles.size == current.selectedIds.size

        val newSelected: Set<Long>
        val newFiles: List<MediaFile>

        if (allSelected) {
            newSelected = emptySet()
            newFiles = current.mediaFiles.map { it.copy(isSelected = false) }
        } else {
            newSelected = current.mediaFiles.map { it.id }.toSet()
            newFiles = current.mediaFiles.map { it.copy(isSelected = true) }
        }

        val summary = buildScanSummary(newFiles.size, newSelected.size)

        _uiState.value = current.copy(
            mediaFiles = newFiles,
            selectedIds = newSelected,
            scanSummaryText = summary
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
     * Move selected media files to SD card using SAF.
     */
    fun moveSelectedToSdCard() {
        val current = _uiState.value ?: return
        val sdUri = current.sdCardUri

        if (sdUri == null) {
            _uiState.value = current.copy(
                errorMessage = "Please choose SD card root before moving files."
            )
            return
        }

        val filesToMove = current.mediaFiles.filter { current.selectedIds.contains(it.id) }
        if (filesToMove.isEmpty()) {
            _uiState.value = current.copy(
                errorMessage = "Please select at least one file to move."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = current.copy(
                isMoving = true,
                errorMessage = null,
                moveResultMessage = null,
                moveProcessed = 0,
                moveTotal = filesToMove.size,
                moveCurrentFileName = null
            )

            val moveSummary: MoveSummary = withContext(Dispatchers.IO) {
                mediaRepository.moveMediaToSdCard(
                    filesToMove,
                    sdUri
                ) { processed, total, name ->
                    _uiState.postValue(
                        _uiState.value?.copy(
                            moveProcessed = processed,
                            moveTotal = total,
                            moveCurrentFileName = name
                        )
                    )
                }
            }

            val remaining = _uiState.value?.mediaFiles?.filterNot { file ->
                current.selectedIds.contains(file.id)
            } ?: emptyList()

            val summaryText = "Moved ${moveSummary.movedCount} files, " +
                    "failed ${moveSummary.failedCount} files, " +
                    "total moved size: ${moveSummary.totalMovedBytes / (1024 * 1024)} MB."

            _uiState.value = _uiState.value?.copy(
                isMoving = false,
                mediaFiles = remaining,
                selectedIds = emptySet(),
                scanSummaryText = buildScanSummary(remaining.size, 0),
                moveProcessed = 0,
                moveTotal = 0,
                moveCurrentFileName = null,
                moveResultMessage = summaryText
            )
        }
    }

    fun consumeError() {
        _uiState.value = _uiState.value?.copy(errorMessage = null)
    }

    fun consumeMoveResultMessage() {
        _uiState.value = _uiState.value?.copy(moveResultMessage = null)
    }

    fun clearSelectionAfterMoveStarted() {
        val current = _uiState.value ?: return
        val clearedFiles = current.mediaFiles.map { it.copy(isSelected = false) }
        _uiState.value = current.copy(
            mediaFiles = clearedFiles,
            selectedIds = emptySet(),
            scanSummaryText = buildScanSummary(clearedFiles.size, 0)
        )
    }

    private fun buildScanSummary(totalFiles: Int, selectedFiles: Int): String {
        return "Found $totalFiles files. Selected $selectedFiles."
    }
}
