package com.local.simhubconnect.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.local.simhubconnect.databinding.ItemRecordingBinding
import com.local.simhubconnect.telemetry.RecordingFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsAdapter(
    private val onOpen: (RecordingFile) -> Unit,
    private val onShare: (RecordingFile) -> Unit,
    private val onDelete: (RecordingFile) -> Unit
) : ListAdapter<RecordingFile, RecordingsAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemRecordingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecordingFile) {
            binding.nameText.text = item.name
            binding.metaText.text = "${
                DATE.format(Date(item.modifiedAt))
            } · ${formatSize(item.sizeBytes)}"
            binding.root.setOnClickListener { onOpen(item) }
            binding.shareButton.setOnClickListener { onShare(item) }
            binding.deleteButton.setOnClickListener { onDelete(item) }
        }
    }

    companion object {
        private val DATE = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)

        private val DIFF = object : DiffUtil.ItemCallback<RecordingFile>() {
            override fun areItemsTheSame(oldItem: RecordingFile, newItem: RecordingFile): Boolean =
                oldItem.file.absolutePath == newItem.file.absolutePath

            override fun areContentsTheSame(oldItem: RecordingFile, newItem: RecordingFile): Boolean =
                oldItem == newItem
        }

        private fun formatSize(bytes: Long): String = when {
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
