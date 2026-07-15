package com.unicorn.player.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.databinding.ItemSongSelectBinding
import com.unicorn.player.model.Song

/**
 * 选择歌曲弹窗内的歌曲列表适配器（ListAdapter + DiffUtil）
 *
 * - 维护 [selectedIds] 集合：入歌单已有歌曲默认勾选，用户可增删
 * - 整行点击切换选中态（仅展示，无旋转动画 / 无 btnMore / 无音质标签）
 */
class SelectableSongAdapter(
    private val onSelectionChanged: () -> Unit = {}
) : ListAdapter<Song, SelectableSongAdapter.SelectableSongHolder>(DIFF) {

    val selectedIds: MutableSet<Long> = linkedSetOf()

    init {
        // 通知外部选中数量变化
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectableSongHolder {
        val binding = ItemSongSelectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SelectableSongHolder(binding)
    }

    override fun onBindViewHolder(holder: SelectableSongHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * 以给定 id 集合初始化勾选状态（清空之前的选择）
     */
    fun initSelection(ids: Collection<Long>) {
        selectedIds.clear()
        selectedIds.addAll(ids)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    inner class SelectableSongHolder(
        private val binding: ItemSongSelectBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // 整行点击切换选中态
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val song = getItem(pos)
                if (selectedIds.contains(song.id)) {
                    selectedIds.remove(song.id)
                } else {
                    selectedIds.add(song.id)
                }
                binding.checkBox.isChecked = selectedIds.contains(song.id)
                onSelectionChanged()
            }
        }

        fun bind(song: Song) {
            binding.songTitle.text = song.title
            binding.artistName.text = song.artist
            binding.checkBox.isChecked = selectedIds.contains(song.id)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Song>() {
            override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean =
                oldItem == newItem
        }
    }
}
