package com.unicorn.player.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.databinding.ItemPlaylistSelectBinding
import com.unicorn.player.model.Playlist

/**
 * 选择歌单弹窗内的歌单列表适配器（ListAdapter + DiffUtil）
 *
 * - 维护 [selectedIds] 集合：用户勾选的歌单
 * - 整行点击切换选中态
 * - 显示歌单名称 + 歌曲数量（通过 [updateSongCounts] 更新）
 */
class SelectablePlaylistAdapter(
    initialCounts: Map<Long, Int> = emptyMap()
) : ListAdapter<Playlist, SelectablePlaylistAdapter.SelectablePlaylistHolder>(DIFF) {

    val selectedIds: MutableSet<Long> = linkedSetOf()

    /** 歌单 ID → 歌曲数量映射 */
    private val songCountMap: MutableMap<Long, Int> = mutableMapOf()

    init {
        songCountMap.putAll(initialCounts)
    }

    /**
     * 更新歌单歌曲数量显示
     */
    fun updateSongCounts(counts: Map<Long, Int>) {
        songCountMap.clear()
        songCountMap.putAll(counts)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectablePlaylistHolder {
        val binding = ItemPlaylistSelectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SelectablePlaylistHolder(binding)
    }

    override fun onBindViewHolder(holder: SelectablePlaylistHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SelectablePlaylistHolder(
        private val binding: ItemPlaylistSelectBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // 整行点击切换选中态
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val playlist = getItem(pos)
                if (selectedIds.contains(playlist.id)) {
                    selectedIds.remove(playlist.id)
                } else {
                    selectedIds.add(playlist.id)
                }
                binding.checkBox.isChecked = selectedIds.contains(playlist.id)
            }
        }

        fun bind(playlist: Playlist) {
            binding.tvPlaylistName.text = playlist.name
            val count = songCountMap[playlist.id] ?: 0
            binding.tvSongCount.text = "$count 首"
            binding.checkBox.isChecked = selectedIds.contains(playlist.id)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Playlist>() {
            override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean =
                oldItem == newItem
        }
    }
}
