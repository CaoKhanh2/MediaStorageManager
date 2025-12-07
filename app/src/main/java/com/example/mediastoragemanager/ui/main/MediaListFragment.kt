package com.example.mediastoragemanager.ui.main

import android.app.AlertDialog
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.mediastoragemanager.R
import com.example.mediastoragemanager.databinding.FragmentMediaListBinding
import com.example.mediastoragemanager.util.PermissionUtils
import com.google.android.material.snackbar.Snackbar

class MediaListFragment : Fragment() {

    private var _binding: FragmentMediaListBinding? = null
    private val binding get() = _binding!!

    // Shared ViewModel
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: MediaFileAdapter

    // Launcher for selecting SD Card root folder
    private val openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                // Persist permissions
                val contentResolver = requireContext().contentResolver
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            viewModel.setSdCardUri(uri)
            Snackbar.make(binding.root, "Đã chọn thẻ SD. Đang bắt đầu chuyển...", Snackbar.LENGTH_SHORT).show()

            viewModel.moveSelectedToSdCard()
        } else {
            Snackbar.make(binding.root, "Chưa chọn thư mục gốc thẻ SD.", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupButtons()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = MediaFileAdapter { file, checked ->
            viewModel.onFileSelectionChanged(file.id, checked)
        }
        binding.recyclerMedia.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerMedia.adapter = adapter
    }

    private fun setupButtons() {
        binding.buttonSelectAll.setOnClickListener {
            viewModel.toggleSelectAll()
        }

        binding.buttonMove.setOnClickListener {
            if (!PermissionUtils.hasAllFilesAccess()) {
                showPermissionDialog()
                return@setOnClickListener
            }

            val state = viewModel.uiState.value
            if (state?.sdCardUri == null) {
                Snackbar.make(binding.root, "Hãy chọn thư mục gốc thẻ SD để cấp quyền ghi.", Snackbar.LENGTH_LONG).show()
                openDocumentTreeLauncher.launch(null)
            } else {
                viewModel.moveSelectedToSdCard()
            }
        }

        binding.buttonBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            // 1. Update List
            adapter.submitList(state.mediaFiles)
            binding.textScanSummary.text = state.scanSummaryText
            binding.textEmpty.visibility = if (state.mediaFiles.isEmpty()) View.VISIBLE else View.GONE

            binding.buttonSelectAll.isEnabled = state.mediaFiles.isNotEmpty() && !state.isMoving
            binding.buttonMove.isEnabled = state.selectedIds.isNotEmpty() && !state.isMoving

            // 2. Update SD Card Info
            val sdStatus = if (state.sdCardUri != null) "Đã chọn thẻ SD" else "Chưa chọn thẻ SD"
            binding.textSdCardUriStatus.text = sdStatus

            if (state.sdCardUri != null) {
                val docId = DocumentsContract.getTreeDocumentId(state.sdCardUri)
                val displayPath = if (docId.contains(":")) "/storage/${docId.split(":")[0]}/${docId.split(":")[1]}" else "/storage/$docId"
                binding.textSdRootPath.visibility = View.VISIBLE
                binding.textSdRootPath.text = displayPath
            } else {
                binding.textSdRootPath.visibility = View.GONE
            }

            // 3. Update Progress (UI controlled by State)
            if (state.isMoving) {
                binding.progressMove.visibility = View.VISIBLE
                binding.textMoveProgress.visibility = View.VISIBLE
                binding.progressMove.isIndeterminate = (state.moveTotal == 0)
                if (state.moveTotal > 0) {
                    val progress = (state.moveProcessed * 100 / state.moveTotal).coerceIn(0, 100)
                    binding.progressMove.progress = progress
                }
                // Vietnamese progress text
                binding.textMoveProgress.text = getString(R.string.moving_progress_format, state.moveProcessed, state.moveTotal, state.moveCurrentFileName ?: "")
            } else {
                binding.progressMove.visibility = View.GONE
                binding.textMoveProgress.visibility = View.GONE
            }

            // 4. Show Result Dialog (Single Source of Truth)
            state.moveResultMessage?.let { message ->
                showResultDialog(message)
                viewModel.consumeMoveResultMessage() // Clear message so it doesn't show again on rotation
            }

            state.errorMessage?.let { error ->
                Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
                viewModel.consumeError()
            }
        }
    }

    private fun showResultDialog(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Hoàn tất")
            .setMessage(message)
            .setPositiveButton("Đóng", null)
            .show()
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Cần quyền truy cập")
            .setMessage("Ứng dụng cần quyền truy cập toàn bộ file để di chuyển ảnh.")
            .setPositiveButton("Mở Cài Đặt") { _, _ ->
                PermissionUtils.openAllFilesAccessSettings(requireContext())
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}