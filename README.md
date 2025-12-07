# Media Storage Manager

Media Storage Manager is an Android application developed to help users scan images and videos from internal storage and efficiently move them to an external SD card. It leverages the **Storage Access Framework (SAF)** and **Foreground Services** to handle large files (like 4K videos) reliably.

## Key Features

* **Media Scanning:** Quickly scan internal storage for images and videos using `MediaStore` API.
* **SD Card Transfer:** Move selected files to the SD Card while preserving directory structure (e.g., `DCIM/Camera` -> `SDCard/DCIM/Camera`).
* **Foreground Service:** Performs file operations in a foreground service to prevent the system from killing the process during long transfers (e.g., moving large video files).
* **Conflict Handling:** Automatically renames files if a file with the same name exists in the destination folder (e.g., `video.mp4` -> `video (1).mp4`).
* **Video Thumbnails:** Supports displaying video thumbnails with a play icon overlay using Coil.
* **File Cache Mechanism:** Uses `MediaTransferHelper` to serialize selected file data to disk, preventing `TransactionTooLargeException` and ensuring data survives process death.

## Project Structure

The project follows the **MVVM (Model-View-ViewModel)** architecture:

* **`com.example.mediastoragemanager`**
    * **`data`**:
        * `MediaRepository.kt`: Core logic for scanning media (MediaStore) and moving files (DocumentFile/SAF). Contains logic for directory creation and stream copying.
        * `StorageRepository.kt`: Helper to get storage space info.
    * **`ui`**:
        * `MainViewModel.kt`: Manages UI state, handles communication between UI and Data layer.
        * `MediaListFragment.kt`: UI for listing media, handling selection, and triggering the move operation.
    * **`service`**:
        * `MoveForegroundService.kt`: A foreground service that executes the file transfer. It reads the file list from cache, updates a notification, and broadcasts progress to the UI.
    * **`util`**:
        * `MediaTransferHelper.kt`: Utilities for saving/loading the list of selected files to `cacheDir`. Critical for passing large data to the Service.
        * `PermissionUtils.kt`: Helpers for requesting storage permissions.
    * **`model`**: Data classes (`MediaFile`, `MediaType`).

## Setup & Permissions

This app requires specific permissions to function on Android 10+ (Scoped Storage) and Android 14.

1.  **AndroidManifest.xml**:
    * `MANAGE_EXTERNAL_STORAGE`: Required for scanning all internal files (Android 11+).
    * `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_DATA_SYNC`: Required for the background move service (Android 14+).
    * `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`: For scanning (Android 13+).

2.  **Runtime Permissions**:
    * The app will request "All Files Access" upon clicking "Move".
    * It will ask the user to select the **SD Card Root** folder to grant persistent write access via SAF.

## Critical Implementation Details

### 1. Handling Large Files & ANR
The `MediaRepository.copyStream` method uses a **64KB buffer** (optimized from the default 8KB) to ensure faster copying speeds for large video files.

### 2. Service & Lifecycle
Instead of passing the list of files via `Intent` (which has a size limit) or a Singleton (which dies on process death), the app uses `MediaTransferHelper` to serialize the list to a file in the app's cache directory. The `MoveForegroundService` then reads this file independently.

### 3. Persistable URI Permission
In `MediaListFragment.kt`, when the user selects the SD Card, `contentResolver.takePersistableUriPermission()` is called. This is crucial because standard SAF permissions are temporary and would be lost if the app restarts or the service runs independently.

## Requirements

* **Min SDK:** 26 (Android 8.0)
* **Target SDK:** 34 (Android 14)
* **Language:** Kotlin
* **Libraries:**
    * AndroidX Core/AppCompat/ConstraintLayout
    * Material Design Components
    * Coil (Image Loading) + Coil Video
    * Coroutines (Async)
    * DocumentFile (SAF)
