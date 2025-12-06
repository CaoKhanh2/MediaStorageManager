package com.example.mediastoragemanager.ui.main

import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.mediastoragemanager.R
import com.example.mediastoragemanager.databinding.FragmentMainBinding
import com.example.mediastoragemanager.util.FormatUtils
import com.example.mediastoragemanager.util.PermissionUtils
import com.google.android.material.snackbar.Snackbar

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    // Shared ViewModel between fragments
    private val viewModel: MainViewModel by activityViewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val allGranted = result.values.all { it }
            if (allGranted) {
                startScanAndOpenList()
            } else {
                val rationaleNeeded = PermissionUtils.mediaPermissions().any { perm ->
                    shouldShowRequestPermissionRationale(perm)
                }
                if (!rationaleNeeded) {
                    showMessage("Vui lòng bật quyền truy cập media trong Cài đặt để có thể quét.")
                } else {
                    showMessage("Ứng dụng cần quyền truy cập media để quét ảnh và video.")
                }
            }
        }

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                // Lưu URI thư mục gốc thẻ SD
                viewModel.setSdCardUri(uri)

                Toast.makeText(
                    requireContext(),
                    "Đã chọn thư mục gốc thẻ SD.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Chưa chọn thư mục gốc thẻ SD.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Storage actions
        binding.buttonReloadStorage.setOnClickListener {
            viewModel.loadStorageInfo()
        }

        binding.buttonChooseSdCard.setOnClickListener {
            openDocumentTreeLauncher.launch(null)
        }

        // Scan button -> check SD root + permissions + open full screen list
        binding.buttonScan.setOnClickListener {
            val state = viewModel.uiState.value

            // Bắt buộc chọn thư mục gốc thẻ SD trước khi quét
            if (state?.sdCardUri == null) {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Chưa chọn thẻ SD")
                    .setMessage("Bạn cần chọn thư mục gốc của thẻ SD trước khi quét và chuyển media.")
                    .setPositiveButton("Chọn thẻ SD") { _, _ ->
                        openDocumentTreeLauncher.launch(null)
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
                return@setOnClickListener
            }

            // Đã có thẻ SD -> kiểm tra quyền & quét
            ensurePermissionsAndScan()
        }

        // Default: both images and videos
        binding.radioBoth.isChecked = true

        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            // Storage info
            binding.progressStorage.visibility =
                if (state.isLoadingStorage) View.VISIBLE else View.GONE

            state.internalStorage?.let { info ->
                val ctx = requireContext()
                binding.textInternalLabel.text = getString(R.string.internal_storage_label)
                binding.textInternalTotal.text = getString(
                    R.string.storage_total_format,
                    FormatUtils.formatBytes(ctx, info.totalBytes)
                )
                binding.textInternalUsed.text = getString(
                    R.string.storage_used_format,
                    FormatUtils.formatBytes(ctx, info.usedBytes),
                    info.percentUsed
                )
                binding.textInternalFree.text = getString(
                    R.string.storage_free_format,
                    FormatUtils.formatBytes(ctx, info.freeBytes)
                )
                binding.progressInternal.progress = info.percentUsed
            }

            val sdInfo = state.sdCardStorage
            if (sdInfo != null) {
                val ctx = requireContext()
                binding.cardSd.visibility = View.VISIBLE
                binding.textSdStatus.visibility = View.GONE

                binding.textSdLabel.text = getString(R.string.sd_storage_label)
                binding.textSdTotal.text = getString(
                    R.string.storage_total_format,
                    FormatUtils.formatBytes(ctx, sdInfo.totalBytes)
                )
                binding.textSdUsed.text = getString(
                    R.string.storage_used_format,
                    FormatUtils.formatBytes(ctx, sdInfo.usedBytes),
                    sdInfo.percentUsed
                )
                binding.textSdFree.text = getString(
                    R.string.storage_free_format,
                    FormatUtils.formatBytes(ctx, sdInfo.freeBytes)
                )
                binding.progressSd.progress = sdInfo.percentUsed
            } else {
                binding.cardSd.visibility = View.GONE
                binding.textSdStatus.visibility = View.VISIBLE
            }

            // Trạng thái đã chọn / chưa chọn thẻ SD
            val sdStatus = if (state.sdCardUri != null) {
                getString(R.string.sdcard_selected)
            } else {
                getString(R.string.sdcard_not_selected)
            }
            binding.textSdCardUriStatus.text = sdStatus

            // Hiển thị đường dẫn thư mục gốc thẻ SD
            val sdUri = state.sdCardUri
            if (sdUri != null) {
                val docId = DocumentsContract.getTreeDocumentId(sdUri)
                val displayPath = if (docId.contains(":")) {
                    val parts = docId.split(":")
                    "/storage/${parts[0]}/${parts[1]}"
                } else {
                    "/storage/$docId"
                }
                binding.textSdRootPath.visibility = View.VISIBLE
                binding.textSdRootPath.text = displayPath
            } else {
                binding.textSdRootPath.visibility = View.GONE
            }

            state.errorMessage?.let { msg ->
                showMessage(msg)
                viewModel.consumeError()
            }
        }
    }

    private fun ensurePermissionsAndScan() {
        if (PermissionUtils.hasMediaPermissions(requireContext())) {
            startScanAndOpenList()
        } else {
            Snackbar.make(
                binding.root,
                "Ứng dụng cần quyền truy cập media để quét ảnh và video.",
                Snackbar.LENGTH_LONG
            ).show()
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

        // Navigate to full-screen media list
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