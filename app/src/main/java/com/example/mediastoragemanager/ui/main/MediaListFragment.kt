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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.mediastoragemanager.R
import com.example.mediastoragemanager.databinding.FragmentMediaListBinding
import com.example.mediastoragemanager.service.MoveForegroundService
import com.example.mediastoragemanager.service.SelectedMediaHolder
import com.example.mediastoragemanager.util.PermissionUtils
import com.google.android.material.snackbar.Snackbar
import java.io.File   // <-- thêm import này

class MediaListFragment : Fragment() {

    private var _binding: FragmentMediaListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: MediaFileAdapter

    private val localBroadcastManager: LocalBroadcastManager by lazy {
        LocalBroadcastManager.getInstance(requireContext())
    }

    // Receiver nhận tiến độ / kết thúc từ MoveForegroundService
    private val moveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || _binding == null) return

            when (intent.action) {
                MoveForegroundService.ACTION_MOVE_PROGRESS -> {
                    val processed = intent.getIntExtra(
                        MoveForegroundService.EXTRA_PROGRESS_PROCESSED, 0
                    )
                    val total = intent.getIntExtra(
                        MoveForegroundService.EXTRA_PROGRESS_TOTAL, 0
                    )
                    val name = intent.getStringExtra(
                        MoveForegroundService.EXTRA_PROGRESS_NAME
                    ) ?: ""

                    binding.progressMove.visibility = View.VISIBLE
                    binding.textMoveProgress.visibility = View.VISIBLE

                    val percent =
                        if (total > 0) (processed * 100 / total).coerceIn(0, 100) else 0
                    binding.progressMove.isIndeterminate = false
                    binding.progressMove.max = 100
                    binding.progressMove.progress = percent

                    binding.textMoveProgress.text = getString(
                        R.string.moving_progress_format,
                        processed,
                        total,
                        name
                    )

                    binding.buttonSelectAll.isEnabled = false
                    binding.buttonMove.isEnabled = false
                }

                MoveForegroundService.ACTION_MOVE_FINISHED -> {
                    val moved = intent.getIntExtra(
                        MoveForegroundService.EXTRA_FINISHED_MOVED, 0
                    )
                    val failed = intent.getIntExtra(
                        MoveForegroundService.EXTRA_FINISHED_FAILED, 0
                    )
                    val bytes = intent.getLongExtra(
                        MoveForegroundService.EXTRA_FINISHED_BYTES, 0L
                    )
                    val mb = (bytes / (1024 * 1024)).toInt()

                    binding.progressMove.visibility = View.GONE
                    binding.textMoveProgress.visibility = View.GONE

                    // Refresh lại list sau khi chuyển xong
                    refreshListAfterMove()

                    binding.buttonSelectAll.isEnabled =
                        viewModel.uiState.value?.mediaFiles?.isNotEmpty() == true
                    binding.buttonMove.isEnabled = false

                    AlertDialog.Builder(requireContext())
                        .setTitle("Hoàn tất chuyển tệp media")
                        .setMessage(
                            getString(
                                R.string.notif_move_finished_text,
                                moved,
                                failed,
                                mb
                            )
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(MoveForegroundService.ACTION_MOVE_PROGRESS)
            addAction(MoveForegroundService.ACTION_MOVE_FINISHED)
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(moveReceiver, filter)
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(moveReceiver)
        super.onStop()
    }

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                viewModel.setSdCardUri(uri)

                Snackbar.make(
                    binding.root,
                    "Đã chọn thư mục gốc thẻ SD. Đang chuyển tệp trong nền...",
                    Snackbar.LENGTH_LONG
                ).show()

                startMoveServiceWithCurrentSelection()
            } else {
                Snackbar.make(
                    binding.root,
                    "Chưa chọn thư mục gốc thẻ SD.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = MediaFileAdapter { file, checked ->
            viewModel.onFileSelectionChanged(file.id, checked)
        }

        binding.recyclerMedia.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@MediaListFragment.adapter
        }

        binding.buttonSelectAll.setOnClickListener {
            viewModel.toggleSelectAll()
        }

        binding.buttonMove.setOnClickListener {
            if (!PermissionUtils.hasAllFilesAccess()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Cần quyền truy cập toàn bộ bộ nhớ")
                    .setMessage(
                        "Để chuyển tệp theo kiểu cắt sang thẻ SD, ứng dụng cần quyền " +
                                "truy cập toàn bộ bộ nhớ (All files access). Hãy cấp quyền trong màn hình cài đặt tiếp theo."
                    )
                    .setPositiveButton("Mở cài đặt") { _, _ ->
                        PermissionUtils.openAllFilesAccessSettings(requireContext())
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
                return@setOnClickListener
            }

            val state = viewModel.uiState.value
            if (state?.sdCardUri == null) {
                Snackbar.make(
                    binding.root,
                    "Hãy chọn thư mục gốc thẻ SD trước khi chuyển tệp.",
                    Snackbar.LENGTH_LONG
                ).show()
                openDocumentTreeLauncher.launch(null)
            } else {
                startMoveServiceWithCurrentSelection()
            }
        }

        binding.buttonBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            binding.textScanSummary.text = state.scanSummaryText

            val list = state.mediaFiles
            adapter.submitList(list)

            binding.textEmpty.visibility =
                if (list.isEmpty()) View.VISIBLE else View.GONE

            val sdStatus = if (state.sdCardUri != null) {
                getString(R.string.sdcard_selected)
            } else {
                getString(R.string.sdcard_not_selected)
            }
            binding.textSdCardUriStatus.text = sdStatus

            val sdUri = state.sdCardUri
            if (sdUri != null) {
                val docId = DocumentsContract.getTreeDocumentId(sdUri)
                val displayPath = if (docId.contains(":")) {
                    val parts = docId.split(":")
                    "/storage/${parts[0]}/${parts.getOrNull(1) ?: ""}"
                } else {
                    "/storage/$docId"
                }
                binding.textSdRootPath.visibility = View.VISIBLE
                binding.textSdRootPath.text = displayPath
            } else {
                binding.textSdRootPath.visibility = View.GONE
            }

            binding.buttonSelectAll.isEnabled = list.isNotEmpty()
            binding.buttonMove.isEnabled = state.selectedIds.isNotEmpty()

            binding.progressScan.visibility = View.GONE
        }
    }

    private fun startMoveServiceWithCurrentSelection() {
        val state = viewModel.uiState.value ?: return
        val sdUri = state.sdCardUri ?: return

        val filesToMove = state.mediaFiles.filter { state.selectedIds.contains(it.id) }
        if (filesToMove.isEmpty()) {
            Snackbar.make(
                binding.root,
                "Hãy chọn ít nhất một tệp để chuyển.",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        SelectedMediaHolder.files = filesToMove

        val intent = Intent(requireContext(), MoveForegroundService::class.java).apply {
            action = MoveForegroundService.ACTION_START_MOVE
            putExtra(MoveForegroundService.EXTRA_SD_URI, sdUri.toString())
        }

        ContextCompat.startForegroundService(requireContext(), intent)

        binding.progressMove.visibility = View.VISIBLE
        binding.progressMove.isIndeterminate = true
        binding.textMoveProgress.visibility = View.VISIBLE
        binding.textMoveProgress.text = "Đang chuẩn bị chuyển tệp..."

        binding.buttonSelectAll.isEnabled = false
        binding.buttonMove.isEnabled = false

        Snackbar.make(
            binding.root,
            "Đang chuyển tệp trong nền. Bạn có thể khóa màn hình hoặc chuyển sang ứng dụng khác.",
            Snackbar.LENGTH_LONG
        ).show()

        viewModel.clearSelectionAfterMoveStarted()
    }

    /**
     * Refresh lại list sau khi chuyển xong:
     * - Loại những file mà path gốc đã không còn tồn tại (đã được cắt sang thẻ SD).
     * - Cập nhật lại RecyclerView + text tổng số file.
     */
    private fun refreshListAfterMove() {
        val state = viewModel.uiState.value ?: return
        val currentList = state.mediaFiles
        if (currentList.isEmpty()) return

        val remaining = currentList.filter { mediaFile ->
            val path = mediaFile.fullPath
            if (path.isNullOrEmpty()) {
                // Nếu không biết path thì giữ lại (tránh xoá nhầm)
                true
            } else {
                File(path).exists()
            }
        }

        // Cập nhật lại list hiển thị
        adapter.submitList(remaining)

        // Cập nhật lại dòng summary đơn giản
        val found = remaining.size
        binding.textScanSummary.text = "Found $found files. Selected 0."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
