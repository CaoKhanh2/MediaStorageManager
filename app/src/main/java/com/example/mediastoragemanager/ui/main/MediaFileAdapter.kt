package com.example.mediastoragemanager.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.mediastoragemanager.databinding.ItemMediaFileBinding
import com.example.mediastoragemanager.model.MediaFile
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

        holder.binding.imageThumbnail.load(item.uri) {
            crossfade(true)
        }

        holder.binding.textName.text = item.displayName
        holder.binding.textPath.text = item.fullPath ?: item.relativePath ?: "Unknown path"

        val sizeText = FormatUtils.formatBytes(context, item.sizeBytes)
        val dateText = FormatUtils.formatDateTime(item.lastModifiedMillis)
        holder.binding.textSizeAndDate.text = "$sizeText • $dateText"

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
