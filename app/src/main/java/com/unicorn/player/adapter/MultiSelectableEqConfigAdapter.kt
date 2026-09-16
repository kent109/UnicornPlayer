package com.unicorn.player.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.databinding.ItemEqConfigMultiSelectBinding

/**
 * 均衡器配置清理弹窗的列表适配器（ListAdapter + DiffUtil）。
 *
 * - 多选模式：点击 item 整行切换勾选态，可同时选中多个配置；
 * - 右侧 CheckBox 仅作展示（clickable=false），点击事件统一由整行处理；
 * - 通过 [onSelectionChange] 回调通知外部当前选中数量（用于启用/禁用"删除"按钮）。
 */
class MultiSelectableEqConfigAdapter(
    private val onSelectionChange: (selectedCount: Int) -> Unit
) : ListAdapter<String, MultiSelectableEqConfigAdapter.ConfigHolder>(DIFF) {

    /** 当前选中的文件名集合（含 .json 后缀）。 */
    private val selectedFileNames = linkedSetOf<String>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).hashCode().toLong()
    }

    fun getSelectedFiles(): List<String> = selectedFileNames.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigHolder {
        val binding = ItemEqConfigMultiSelectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ConfigHolder(binding)
    }

    override fun onBindViewHolder(holder: ConfigHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ConfigHolder(
        private val binding: ItemEqConfigMultiSelectBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val name = getItem(pos)
                if (selectedFileNames.contains(name)) {
                    selectedFileNames.remove(name)
                } else {
                    selectedFileNames.add(name)
                }
                notifyItemChanged(pos, PAYLOAD_CHECK)
                onSelectionChange(selectedFileNames.size)
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
            binding.checkBox.isChecked = selectedFileNames.contains(fileName)
        }
    }

    companion object {
        private const val PAYLOAD_CHECK = "check"
        private val DIFF = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean =
                oldItem == newItem

            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean =
                oldItem == newItem
        }
    }
}
