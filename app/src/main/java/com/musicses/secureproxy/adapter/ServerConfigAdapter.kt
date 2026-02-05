package com.musicses.secureproxy.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musicses.secureproxy.databinding.ItemServerConfigBinding
import com.musicses.secureproxy.model.ServerConfig
import java.text.SimpleDateFormat
import java.util.*

/**
 * 服务器配置列表适配器
 */
class ServerConfigAdapter(
    private val onItemClick: (Int, ServerConfig) -> Unit,
    private val onEditClick: (Int, ServerConfig) -> Unit,
    private val onDeleteClick: (Int, ServerConfig) -> Unit
) : ListAdapter<ServerConfig, ServerConfigAdapter.ViewHolder>(DiffCallback()) {

    private var selectedPosition = -1

    fun setSelectedPosition(position: Int) {
        val oldPosition = selectedPosition
        selectedPosition = position

        if (oldPosition >= 0) notifyItemChanged(oldPosition)
        if (selectedPosition >= 0) notifyItemChanged(selectedPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemServerConfigBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position == selectedPosition)
    }

    inner class ViewHolder(
        private val binding: ItemServerConfigBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(config: ServerConfig, isSelected: Boolean) {
            binding.apply {
                textConfigName.text = config.name
                textSniHost.text = "SNI: ${config.sniHost}"
                textProxyAddress.text = "地址: ${config.proxyAddress}:${config.serverPort}"
                textPath.text = "路径: ${config.path}"

                // 显示更新时间
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                textUpdatedAt.text = "更新: ${dateFormat.format(Date(config.updatedAt))}"

                // 选中状态
                root.isSelected = isSelected
                cardView.strokeWidth = if (isSelected) 4 else 0

                // 点击事件
                root.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onItemClick(position, config)
                    }
                }

                btnEdit.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onEditClick(position, config)
                    }
                }

                btnDelete.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onDeleteClick(position, config)
                    }
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ServerConfig>() {
        override fun areItemsTheSame(oldItem: ServerConfig, newItem: ServerConfig): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ServerConfig, newItem: ServerConfig): Boolean {
            return oldItem == newItem
        }
    }
}
