package com.example.mediastoragemanager.ui.main

import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.mediastoragemanager.R
import com.example.mediastoragemanager.databinding.FragmentMainBinding
import com.example.mediastoragemanager.util.FormatUtils
import com.example.mediastoragemanager.util.PermissionUtils
import com.getkeepsafe.taptargetview.TapTarget
import com.getkeepsafe.taptargetview.TapTargetSequence
import com.google.android.material.snackbar.Snackbar

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    // Shared ViewModel
    private val viewModel: MainViewModel by activityViewModels()

    // Permissions launcher
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val allGranted = result.values.all { it }
            if (allGranted) {
                startScanAndOpenList()
            } else {
                showMessage("Cần quyền truy cập Media để quét file.")
            }
        }

    // SD Card selection launcher
    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    val contentResolver = requireContext().contentResolver
                    val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                viewModel.setSdCardUri(uri)
                Toast.makeText(requireContext(), "Đã chọn thẻ SD thành công!", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup Buttons
        binding.buttonReloadStorage.setOnClickListener { viewModel.loadStorageInfo() }

        binding.buttonChooseSdCard.setOnClickListener { openDocumentTreeLauncher.launch(null) }

        // Help Button
        binding.btnHelp.setOnClickListener {
            startInteractiveGuide()
        }

        binding.buttonScan.setOnClickListener {
            val state = viewModel.uiState.value
            if (state?.sdCardUri == null) {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Chưa chọn thẻ SD")
                    .setMessage("Bạn cần chọn thư mục gốc của thẻ SD trước để ứng dụng có quyền ghi.")
                    .setPositiveButton("Chọn ngay") { _, _ ->
                        startGuideForSdCardOnly()
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
                return@setOnClickListener
            }
            ensurePermissionsAndScan()
        }

        if (viewModel.uiState.value?.isScanning == false) {
            binding.radioBoth.isChecked = true
        }

        observeViewModel()
    }

    /**
     * Starts the Interactive Tour/Guide sequence.
     * Logic:
     * 1. If SD Card is NOT selected -> Show "Select SD Card" step first.
     * 2. If SD Card IS selected -> Skip "Select SD Card" step directly to File Type.
     */
    private fun startInteractiveGuide() {
        // Define colors
        val colorOuter = R.color.purple_500
        val colorTarget = android.R.color.white
        val colorText = android.R.color.white

        // List of steps to show
        val targets = ArrayList<TapTarget>()

        // Step A: Select SD Card (Only show if NOT selected yet)
        if (viewModel.uiState.value?.sdCardUri == null) {
            targets.add(
                TapTarget.forView(binding.buttonChooseSdCard, "Bước 1: Cấp quyền thẻ nhớ", "Bấm vào đây và chọn thư mục GỐC của thẻ nhớ để cấp quyền ghi.")
                    .outerCircleColor(android.R.color.holo_red_dark) // Red for importance
                    .targetCircleColor(colorTarget)
                    .textColor(colorText)
                    .drawShadow(true)
                    .cancelable(false)
                    .transparentTarget(true)
            )
        }

        // Step B: Select File Type
        targets.add(
            TapTarget.forView(binding.radioGroupMediaType, "Chọn loại tệp", "Bạn muốn chuyển Ảnh, Video hay cả hai?")
                .outerCircleColor(colorOuter)
                .targetCircleColor(colorTarget)
                .textColor(colorText)
                .transparentTarget(true)
        )

        // Step C: Scan Button
        targets.add(
            TapTarget.forView(binding.buttonScan, "Bắt đầu", "Bấm nút này để quét và xem danh sách file.")
                .outerCircleColor(colorOuter)
                .targetCircleColor(colorTarget)
                .textColor(colorText)
                .transparentTarget(true)
        )

        // Start the sequence
        TapTargetSequence(requireActivity())
            .targets(targets)
            .start()
    }

    private fun startGuideForSdCardOnly() {
        TapTargetSequence(requireActivity())
            .targets(
                TapTarget.forView(binding.buttonChooseSdCard, "Bấm vào đây!", "Mở trình chọn file -> Chọn Menu -> Tên thẻ nhớ -> 'USE THIS FOLDER'.")
                    .outerCircleColor(android.R.color.holo_red_dark)
                    .targetCircleColor(android.R.color.white)
                    .drawShadow(true)
                    .cancelable(true)
                    .transparentTarget(true)
            ).start()
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            binding.progressStorage.visibility = if (state.isLoadingStorage) View.VISIBLE else View.GONE

            state.internalStorage?.let { info ->
                binding.textInternalLabel.text = getString(R.string.internal_storage_label)
                binding.textInternalTotal.text = "Tổng: ${FormatUtils.formatBytes(requireContext(), info.totalBytes)}"
                binding.textInternalUsed.text = "Đã dùng: ${FormatUtils.formatBytes(requireContext(), info.usedBytes)}"
                binding.textInternalFree.text = "Trống: ${FormatUtils.formatBytes(requireContext(), info.freeBytes)}"
                binding.progressInternal.progress = info.percentUsed
            }

            val sdInfo = state.sdCardStorage
            if (sdInfo != null) {
                binding.cardSd.visibility = View.VISIBLE
                binding.textSdStatus.visibility = View.GONE

                binding.textSdLabel.text = getString(R.string.sd_storage_label)
                binding.textSdTotal.text = "Tổng: ${FormatUtils.formatBytes(requireContext(), sdInfo.totalBytes)}"
                binding.textSdUsed.text = "Đã dùng: ${FormatUtils.formatBytes(requireContext(), sdInfo.usedBytes)}"
                binding.textSdFree.text = "Trống: ${FormatUtils.formatBytes(requireContext(), sdInfo.freeBytes)}"
                binding.progressSd.progress = sdInfo.percentUsed
            } else {
                binding.cardSd.visibility = View.GONE
                binding.textSdStatus.visibility = View.VISIBLE
            }

            if (state.sdCardUri != null) {
                binding.textSdCardUriStatus.text = "Đã chọn thẻ SD"
                binding.textSdCardUriStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))

                val docId = DocumentsContract.getTreeDocumentId(state.sdCardUri)
                val displayPath = if (docId.contains(":")) "/storage/${docId.replace(":", "/")}" else docId
                binding.textSdRootPath.visibility = View.VISIBLE
                binding.textSdRootPath.text = "Đường dẫn: $displayPath"
            } else {
                binding.textSdCardUriStatus.text = "Chưa chọn thẻ SD"
                binding.textSdCardUriStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                binding.textSdRootPath.visibility = View.GONE
            }

            if (state.errorMessage != null) {
                showMessage(state.errorMessage)
                viewModel.consumeError()
            }
        }
    }

    private fun ensurePermissionsAndScan() {
        if (PermissionUtils.hasMediaPermissions(requireContext())) {
            startScanAndOpenList()
        } else {
            permissionLauncher.launch(PermissionUtils.mediaPermissions())
        }
    }

    private fun startScanAndOpenList() {
        val mode = when {
            binding.radioImages.isChecked -> MediaSelectionMode.IMAGES
            binding.radioVideos.isChecked -> MediaSelectionMode.VIDEOS
            else -> MediaSelectionMode.BOTH
        }
        viewModel.scanMedia(mode)

        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, MediaListFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}