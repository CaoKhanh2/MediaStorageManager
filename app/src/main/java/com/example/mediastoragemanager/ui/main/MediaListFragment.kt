package com.example.mediastoragemanager.ui.main

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.mediastoragemanager.R
import com.example.mediastoragemanager.databinding.FragmentMediaListBinding
import com.example.mediastoragemanager.service.MoveForegroundService
import com.example.mediastoragemanager.util.PermissionUtils
import com.google.android.material.snackbar.Snackbar
import java.io.File

class MediaListFragment : Fragment() {

    private var _binding: FragmentMediaListBinding? = null
    private val binding get() = _binding!!

    // Shared ViewModel
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: MediaFileAdapter

    // Receiver to handle updates from MoveForegroundService
    private val moveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || _binding == null) return

            when (intent.action) {
                MoveForegroundService.ACTION_MOVE_PROGRESS -> {
                    val processed = intent.getIntExtra(MoveForegroundService.EXTRA_PROGRESS_PROCESSED, 0)
                    val total = intent.getIntExtra(MoveForegroundService.EXTRA_PROGRESS_TOTAL, 0)
                    val name = intent.getStringExtra(MoveForegroundService.EXTRA_PROGRESS_NAME) ?: ""

                    binding.progressMove.visibility = View.VISIBLE
                    binding.textMoveProgress.visibility = View.VISIBLE

                    val percent = if (total > 0) (processed * 100 / total).coerceIn(0, 100) else 0
                    binding.progressMove.isIndeterminate = false
                    binding.progressMove.max = 100
                    binding.progressMove.progress = percent

                    binding.textMoveProgress.text = getString(R.string.moving_progress_format, processed, total, name)

                    binding.buttonSelectAll.isEnabled = false
                    binding.buttonMove.isEnabled = false
                }

                MoveForegroundService.ACTION_MOVE_FINISHED -> {
                    val moved = intent.getIntExtra(MoveForegroundService.EXTRA_FINISHED_MOVED, 0)
                    val failed = intent.getIntExtra(MoveForegroundService.EXTRA_FINISHED_FAILED, 0)
                    val bytes = intent.getLongExtra(MoveForegroundService.EXTRA_FINISHED_BYTES, 0L)
                    val mb = (bytes / (1024 * 1024)).toInt()

                    binding.progressMove.visibility = View.GONE
                    binding.textMoveProgress.visibility = View.GONE

                    binding.buttonSelectAll.isEnabled = viewModel.uiState.value?.mediaFiles?.isNotEmpty() == true
                    binding.buttonMove.isEnabled = false

                    AlertDialog.Builder(requireContext())
                        .setTitle("Transfer Completed")
                        .setMessage(getString(R.string.notif_move_finished_text, moved, failed, mb))
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    // Launcher for selecting SD Card root folder
    private val openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // [IMPORTANT] Persist URI permission so the Service can use it later/independently
            try {
                val contentResolver = requireContext().contentResolver
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            viewModel.setSdCardUri(uri)
            Snackbar.make(binding.root, "SD Card selected. Starting transfer...", Snackbar.LENGTH_SHORT).show()

            // Trigger the move operation in ViewModel
            viewModel.moveSelectedToSdCard()
        } else {
            Snackbar.make(binding.root, "SD Card root not selected.", Snackbar.LENGTH_LONG).show()
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

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(MoveForegroundService.ACTION_MOVE_PROGRESS)
            addAction(MoveForegroundService.ACTION_MOVE_FINISHED)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(moveReceiver, filter)
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(moveReceiver)
        super.onStop()
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
                Snackbar.make(binding.root, "Please select SD Card root folder to grant permission.", Snackbar.LENGTH_LONG).show()
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
            adapter.submitList(state.mediaFiles)
            binding.textScanSummary.text = state.scanSummaryText
            binding.textEmpty.visibility = if (state.mediaFiles.isEmpty()) View.VISIBLE else View.GONE

            binding.buttonSelectAll.isEnabled = state.mediaFiles.isNotEmpty() && !state.isMoving
            binding.buttonMove.isEnabled = state.selectedIds.isNotEmpty() && !state.isMoving

            val sdStatus = if (state.sdCardUri != null) getString(R.string.sdcard_selected) else getString(R.string.sdcard_not_selected)
            binding.textSdCardUriStatus.text = sdStatus

            if (state.sdCardUri != null) {
                val docId = DocumentsContract.getTreeDocumentId(state.sdCardUri)
                val displayPath = if (docId.contains(":")) "/storage/${docId.split(":")[0]}/${docId.split(":")[1]}" else "/storage/$docId"
                binding.textSdRootPath.visibility = View.VISIBLE
                binding.textSdRootPath.text = displayPath
            } else {
                binding.textSdRootPath.visibility = View.GONE
            }

            if (state.isMoving) {
                binding.progressMove.visibility = View.VISIBLE
                binding.textMoveProgress.visibility = View.VISIBLE
                binding.progressMove.isIndeterminate = (state.moveTotal == 0)
                if (state.moveTotal > 0) {
                    val progress = (state.moveProcessed * 100 / state.moveTotal).coerceIn(0, 100)
                    binding.progressMove.progress = progress
                }
                binding.textMoveProgress.text = getString(R.string.moving_progress_format, state.moveProcessed, state.moveTotal, state.moveCurrentFileName ?: "")
            } else {
                binding.progressMove.visibility = View.GONE
                binding.textMoveProgress.visibility = View.GONE
            }

            state.moveResultMessage?.let { message ->
                showResultDialog(message)
                viewModel.consumeMoveResultMessage()
            }

            state.errorMessage?.let { error ->
                Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
                viewModel.consumeError()
            }
        }
    }

    private fun showResultDialog(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Transfer Result")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Permission Required")
            .setMessage("This app needs 'All Files Access' to move media files.")
            .setPositiveButton("Open Settings") { _, _ ->
                PermissionUtils.openAllFilesAccessSettings(requireContext())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}