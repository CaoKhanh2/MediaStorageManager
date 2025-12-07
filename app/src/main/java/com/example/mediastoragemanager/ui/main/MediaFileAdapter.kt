package com.example.mediastoragemanager.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.mediastoragemanager.R
import com.example.mediastoragemanager.databinding.ItemMediaFileBinding
import com.example.mediastoragemanager.model.MediaFile
import com.example.mediastoragemanager.model.MediaType // Đừng quên import này
import com.example.mediastoragemanager.util.FormatUtils

class MediaFileAdapter(
    private val onSelectionChanged: (MediaFile, Boolean) -> Unit
) : ListAdapter<MediaFile, MediaFileAdapter.MediaViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<MediaFile>() {
        override fun areItemsTheSame(oldItem: MediaFile, newItem: MediaFile): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MediaFile, newItem: MediaFile): Boolean {
            return oldItem == newItem
        }
    }

    inner class MediaViewHolder(val binding: ItemMediaFileBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemMediaFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context

        // 1. Load Thumbnail (Ảnh/Video)
        holder.binding.imageThumbnail.load(item.uri) {
            crossfade(true)
            // Thêm hình chờ và hình lỗi để trải nghiệm mượt hơn
            placeholder(R.drawable.ic_launcher_foreground)
            error(R.drawable.ic_launcher_background)
        }

        // 2. [MỚI] Hiển thị icon Play nếu là Video
        if (item.type == MediaType.VIDEO) {
            holder.binding.iconPlayOverlay.visibility = View.VISIBLE
        } else {
            holder.binding.iconPlayOverlay.visibility = View.GONE
        }

        // 3. Các thông tin khác giữ nguyên
        holder.binding.textName.text = item.displayName
        holder.binding.textPath.text = item.fullPath ?: item.relativePath ?: "Unknown path"

        val sizeText = FormatUtils.formatBytes(context, item.sizeBytes)
        val dateText = FormatUtils.formatDateTime(item.lastModifiedMillis)
        holder.binding.textSizeAndDate.text = "$sizeText • $dateText"

        // Xử lý Checkbox (Tránh lỗi tái sử dụng View trong RecyclerView)
        holder.binding.checkboxSelect.setOnCheckedChangeListener(null)
        holder.binding.checkboxSelect.isChecked = item.isSelected

        holder.binding.checkboxSelect.setOnCheckedChangeListener { _, isChecked ->
            onSelectionChanged(item, isChecked)
        }

        holder.binding.root.setOnClickListener {
            val newChecked = !holder.binding.checkboxSelect.isChecked
            holder.binding.checkboxSelect.isChecked = newChecked
        }
    }
}