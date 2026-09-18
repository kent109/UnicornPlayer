package com.unicorn.player.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.databinding.ItemPlaylistImportBinding
import com.unicorn.player.viewmodel.PlaylistViewModel

/**
 * 歌单导入弹窗的多选适配器（ListAdapter + DiffUtil）。
 *
 * - 点击 item 整行切换勾选态，可同时选中多个导出文件；
 * - 右侧 CheckBox 仅作展示（clickable=false），点击事件统一由整行处理；
 * - 通过 [onSelectionChange] 回调通知外部当前选中数量（用于启用/禁用"确定"按钮）。
 */
class SelectableImportPlaylistAdapter(
    private val onSelectionChange: (selectedCount: Int) -> Unit
) : ListAdapter<PlaylistViewModel.PlaylistImportItem, SelectableImportPlaylistAdapter.ImportHolder>(
    DIFF
) {

    /** 当前选中的文件名集合（含 .json 后缀） */
    private val selectedFileNames = linkedSetOf<String>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).fileName.hashCode().toLong()
    }

    fun getSelectedFiles(): List<String> = selectedFileNames.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImportHolder {
        val binding = ItemPlaylistImportBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ImportHolder(binding)
    }

    override fun onBindViewHolder(holder: ImportHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ImportHolder(
        private val binding: ItemPlaylistImportBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val fileName = getItem(pos).fileName
                if (selectedFileNames.contains(fileName)) {
                    selectedFileNames.remove(fileName)
                } else {
                    selectedFileNames.add(fileName)
                }
                notifyItemChanged(pos, PAYLOAD_CHECK)
                onSelectionChange(selectedFileNames.size)
            }
        }

        fun bind(item: PlaylistViewModel.PlaylistImportItem) {
            binding.tvPlaylistName.text = item.displayName
            binding.tvPlaylistInfo.text = buildString {
                append(item.songCount)
                append(" 首 · ")
                append(
                    when {
                        item.targetExists && !item.sameNameMerge -> "将合并到当前歌单"
                        item.sameNameMerge -> "存在同名歌单，将合并"
                        else -> "将新建歌单"
                    }
                )
                if (item.matchedCount < item.songCount) {
                    append("，曲库缺失 ")
                    append(item.songCount - item.matchedCount)
                    append(" 首")
                }
            }
            binding.checkBox.isChecked = selectedFileNames.contains(item.fileName)
        }
    }

    companion object {
        private const val PAYLOAD_CHECK = "check"
        private val DIFF = object : DiffUtil.ItemCallback<PlaylistViewModel.PlaylistImportItem>() {
            override fun areItemsTheSame(
                oldItem: PlaylistViewModel.PlaylistImportItem,
                newItem: PlaylistViewModel.PlaylistImportItem
            ): Boolean = oldItem.fileName == newItem.fileName

            override fun areContentsTheSame(
                oldItem: PlaylistViewModel.PlaylistImportItem,
                newItem: PlaylistViewModel.PlaylistImportItem
            ): Boolean = oldItem == newItem
        }
    }
}
