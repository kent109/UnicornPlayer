package com.unicorn.player.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.databinding.ItemPlaylistBinding
import com.unicorn.player.viewmodel.PlaylistViewModel

/**
 * 歌单列表适配器（ListAdapter + DiffUtil）
 *
 * - item 点击 → [OnPlaylistClickListener.onPlaylistClick]
 * - 滑动露出底层按钮：[onEditClicked] / [onDeleteClicked]
 * - 由外部 ItemTouchHelper 操作滑动；[resetSwipedItem] 复位某项
 */
class PlaylistAdapter(
    private val listener: OnPlaylistClickListener
) : ListAdapter<PlaylistViewModel.PlaylistInfo, PlaylistAdapter.PlaylistHolder>(DIFF) {

    /** 当前处于"已滑开"状态的位置（单指仅允许一个展开），-1 表示无 */
    private var swipedPosition: Int = -1

    interface OnPlaylistClickListener {
        fun onPlaylistClick(playlist: PlaylistViewModel.PlaylistInfo, position: Int)
        fun onEditClicked(playlist: PlaylistViewModel.PlaylistInfo, position: Int)
        fun onDeleteClicked(playlist: PlaylistViewModel.PlaylistInfo, position: Int)
        fun onSwipedOpened(position: Int)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistHolder {
        val binding = ItemPlaylistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PlaylistHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistHolder, position: Int) {
        holder.bind(getItem(position), position, swipedPosition == position)
    }

    fun getPlaylistAt(position: Int): PlaylistViewModel.PlaylistInfo? = getItem(position)

    /**
     * 标记某个位置进入已滑开状态（先复位别的项）
     */
    fun openSwipe(position: Int) {
        if (position == swipedPosition) return
        val prev = swipedPosition
        swipedPosition = position
        if (prev != -1) notifyItemChanged(prev)
        notifyItemChanged(position)
        listener.onSwipedOpened(position)
    }

    /** 复位当前已滑开项 */
    fun resetSwipedItem() {
        if (swipedPosition == -1) return
        val prev = swipedPosition
        swipedPosition = -1
        notifyItemChanged(prev)
    }

    fun isSwiped(position: Int): Boolean = swipedPosition == position

    inner class PlaylistHolder(
        private val binding: ItemPlaylistBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // 整行点击：若当前已滑开，点击内容区应关闭；否则触发 onPlaylistClick
            binding.cardContent.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (swipedPosition == pos) {
                    resetSwipedItem()
                } else {
                    listener.onPlaylistClick(getItem(pos), pos)
                }
            }

            binding.btnEdit.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                resetSwipedItem()
                listener.onEditClicked(getItem(pos), pos)
            }

            binding.btnDelete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                resetSwipedItem()
                listener.onDeleteClicked(getItem(pos), pos)
            }
        }

        fun bind(
            playlist: PlaylistViewModel.PlaylistInfo,
            position: Int,
            isSwiped: Boolean
        ) {
            binding.tvPlaylistName.text = playlist.name
            binding.tvSongCount.text = "${playlist.songCount} 首"
            // 滑开时平移内容卡片露出底层操作按钮
            binding.cardContent.translationX = if (isSwiped) -200f else 0f
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PlaylistViewModel.PlaylistInfo>() {
            override fun areItemsTheSame(
                oldItem: PlaylistViewModel.PlaylistInfo,
                newItem: PlaylistViewModel.PlaylistInfo
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: PlaylistViewModel.PlaylistInfo,
                newItem: PlaylistViewModel.PlaylistInfo
            ): Boolean = oldItem == newItem
        }
    }
}
