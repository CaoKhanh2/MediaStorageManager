package com.example.mediastoragemanager.ui.main

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.mediastoragemanager.R
import com.example.mediastoragemanager.databinding.FragmentMediaListBinding
import com.example.mediastoragemanager.util.PermissionUtils
import com.google.android.material.snackbar.Snackbar
import java.io.File

class MediaListFragment : Fragment() {

    private var _binding: FragmentMediaListBinding? = null
    private val binding get() = _binding!!

    // Sử dụng activityViewModels để share ViewModel với Activity và các Fragment khác
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: MediaFileAdapter

    // Launcher để chọn thư mục SD Card
    private val openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            viewModel.setSdCardUri(uri)
            Snackbar.make(binding.root, "Đã chọn thẻ SD. Đang bắt đầu chuyển...", Snackbar.LENGTH_SHORT).show()

            // Gọi ViewModel để thực hiện toàn bộ logic (Cache -> Start Service)
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
            // 1. Kiểm tra quyền truy cập file
            if (!PermissionUtils.hasAllFilesAccess()) {
                showPermissionDialog()
                return@setOnClickListener
            }

            // 2. Kiểm tra xem đã chọn thẻ SD chưa
            val state = viewModel.uiState.value
            if (state?.sdCardUri == null) {
                // Nếu chưa chọn, mở trình chọn thư mục
                Snackbar.make(binding.root, "Hãy chọn thư mục gốc thẻ SD để cấp quyền ghi.", Snackbar.LENGTH_LONG).show()
                openDocumentTreeLauncher.launch(null)
            } else {
                // Nếu đã chọn rồi, tiến hành chuyển luôn
                viewModel.moveSelectedToSdCard()
            }
        }

        binding.buttonBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            // 1. Cập nhật danh sách file
            adapter.submitList(state.mediaFiles)
            binding.textScanSummary.text = state.scanSummaryText
            binding.textEmpty.visibility = if (state.mediaFiles.isEmpty()) View.VISIBLE else View.GONE

            // 2. Cập nhật trạng thái nút bấm
            binding.buttonSelectAll.isEnabled = state.mediaFiles.isNotEmpty() && !state.isMoving
            binding.buttonMove.isEnabled = state.selectedIds.isNotEmpty() && !state.isMoving

            // 3. Cập nhật UI Tiến độ (Progress)
            if (state.isMoving) {
                binding.progressMove.visibility = View.VISIBLE
                binding.textMoveProgress.visibility = View.VISIBLE

                binding.progressMove.isIndeterminate = (state.moveTotal == 0)
                if (state.moveTotal > 0) {
                    val progress = (state.moveProcessed * 100 / state.moveTotal).coerceIn(0, 100)
                    binding.progressMove.progress = progress
                }

                binding.textMoveProgress.text = getString(
                    R.string.moving_progress_format, // Đảm bảo string này có trong strings.xml: "Đang chuyển: %1$d/%2$d\n%3$s"
                    state.moveProcessed,
                    state.moveTotal,
                    state.moveCurrentFileName ?: ""
                )
            } else {
                binding.progressMove.visibility = View.GONE
                binding.textMoveProgress.visibility = View.GONE
            }

            // 4. Hiển thị thông báo kết quả (nếu có)
            state.moveResultMessage?.let { message ->
                showResultDialog(message)
                viewModel.consumeMoveResultMessage() // Xóa message để không hiện lại khi xoay màn hình
            }

            // 5. Hiển thị lỗi (nếu có)
            state.errorMessage?.let { error ->
                Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
                viewModel.consumeError()
            }
        }
    }

    private fun showResultDialog(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Kết quả chuyển tệp")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Cần quyền truy cập")
            .setMessage("Ứng dụng cần quyền truy cập toàn bộ file để có thể di chuyển ảnh sang thẻ nhớ.")
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