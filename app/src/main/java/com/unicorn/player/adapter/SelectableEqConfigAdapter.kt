package com.unicorn.player.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.databinding.ItemEqConfigSelectBinding

/**
 * 均衡器配置导入弹窗的列表适配器（ListAdapter + DiffUtil）。
 *
 * - 单选模式：用户点击任意 item 切换选中态，同一时刻仅一项处于选中态；
 * - 整行点击触发选择；
 * - 通过 [onSelectionChange] 回调通知外部（用于启用/禁用"确定"按钮）。
 */
class SelectableEqConfigAdapter(
    private val onSelectionChange: (selectedFileName: String?) -> Unit
) : ListAdapter<String, SelectableEqConfigAdapter.ConfigHolder>(DIFF) {

    /** 当前选中的文件名（含 .json 后缀），未选中为 null。 */
    var selectedFileName: String? = null
        private set

    init {
        // 注册稳定 ID 以确保 DiffUtil 在列表更新时保持 holder 不闪烁
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigHolder {
        val binding = ItemEqConfigSelectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ConfigHolder(binding)
    }

    override fun onBindViewHolder(holder: ConfigHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ConfigHolder(
        private val binding: ItemEqConfigSelectBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val name = getItem(pos)
                val newSelection = if (selectedFileName == name) null else name
                val previous = selectedFileName
                selectedFileName = newSelection
                // 刷新新选中项 + 旧选中项，避免整列表 notifyDataSetChanged 闪烁
                newSelection?.let { sel ->
                    val newPos = currentList.indexOf(sel)
                    if (newPos != -1) notifyItemChanged(newPos, PAYLOAD_SELECT)
                }
                previous?.let { old ->
                    val oldPos = currentList.indexOf(old)
                    if (oldPos != -1) notifyItemChanged(oldPos, PAYLOAD_SELECT)
                }
                onSelectionChange(newSelection)
            }
        }

        fun bind(fileName: String) {
            // 去掉 .json 后缀展示更友好
            val displayName = if (fileName.lowercase().endsWith(".json")) {
                fileName.substring(0, fileName.length - 5)
            } else {
                fileName
            }
            binding.tvConfigName.text = displayName
            binding.radioButton.isChecked = selectedFileName == fileName
        }
    }

    companion object {
        private const val PAYLOAD_SELECT = "select"
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean =
                oldItem == newItem

            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean =
                oldItem == newItem
        }
    }
}
