package com.local.simhubconnect.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.local.simhubconnect.R
import com.local.simhubconnect.databinding.ItemServerBinding
import com.local.simhubconnect.net.FoundServer

data class ServerRow(val server: FoundServer, val isLastUsed: Boolean)

class ServerAdapter(
    private val onClick: (ServerRow) -> Unit
) : ListAdapter<ServerRow, ServerAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemServerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: ServerRow) {
            val context = binding.root.context
            binding.hostText.text = context.getString(
                R.string.server_address,
                row.server.host,
                row.server.port
            )
            binding.metaText.text = metaText(context, row)
            binding.root.setOnClickListener { onClick(row) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ServerRow>() {
            override fun areItemsTheSame(oldItem: ServerRow, newItem: ServerRow): Boolean =
                oldItem.server.host == newItem.server.host &&
                    oldItem.server.port == newItem.server.port

            override fun areContentsTheSame(oldItem: ServerRow, newItem: ServerRow): Boolean =
                oldItem == newItem
        }

        private fun metaText(context: Context, row: ServerRow): String {
            val latency = row.server.latencyMs ?: 0L
            return when {
                row.isLastUsed && row.server.latencyMs == null ->
                    context.getString(R.string.meta_last_used)
                row.isLastUsed -> context.getString(R.string.meta_last_used_latency, latency)
                row.server.simhub -> context.getString(R.string.meta_simhub, latency)
                else -> context.getString(R.string.meta_other, latency)
            }
        }
    }
}
