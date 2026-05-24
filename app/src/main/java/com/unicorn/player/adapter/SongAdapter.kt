package com.unicorn.player

import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.databinding.ItemSongBinding
import com.unicorn.player.model.Song
import java.util.Locale

class SongAdapter(
    private val listener: OnSongClickListener,
    var currentPlayingSong: Song? = null,
    var isPlaying: Boolean = false
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    companion object {
        const val TAG = "SongAdapter"
    }

    /**
     * 停止所有动画 - 用于Activity onPause时
     */
    fun stopAllAnimations() {
        isPlaying = false
        currentPlayingSong?.let { song ->
            // 查找当前播放歌曲在列表中的位置
            val currentPosition = currentList.indexOfFirst { it.id == song.id }
            if (currentPosition != -1) {
                notifyItemChanged(currentPosition)
            }
        }
    }

    interface OnSongClickListener {
        fun onSongClick(song: Song, position: Int)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = getItem(position)
        holder.bind(song, position, currentPlayingSong)
    }

    inner class SongViewHolder(
        private val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        // 保存当前动画的引用，以便可以停止
        private var currentAnimator: android.animation.ObjectAnimator? = null

        fun bind(song: Song, position: Int, currentPlayingSong: Song?) {
            binding.apply {
                songTitle.text = song.title
                artistName.text = song.artist
                albumName.text =
                    if (TextUtils.equals(song.album, "Music")) "<unknown>" else song.album

                // Format duration
                val durationMinutes = song.duration / 60000
                val durationSeconds = (song.duration % 60000) / 1000
                duration.text =
                    String.format(Locale.getDefault(), "%d:%02d", durationMinutes, durationSeconds)

                // 获取context
                val context = binding.root.context

                // 检查是否是当前播放的歌曲
                val isCurrentPlaying = currentPlayingSong?.id == song.id

                // 动态设置颜色
                if (isCurrentPlaying) {
                    songTitle.setTextColor(context.getColor(android.R.color.holo_red_light))
                    artistName.setTextColor(context.getColor(android.R.color.holo_red_light))
                    albumName.setTextColor(context.getColor(android.R.color.holo_red_light))
                    duration.setTextColor(context.getColor(android.R.color.holo_red_light))
                    albumArt.isSelected = true
                } else {
                    songTitle.setTextColor(context.getColor(R.color.onSurface))
                    artistName.setTextColor(context.getColor(R.color.onSurfaceVariant))
                    albumName.setTextColor(context.getColor(R.color.onSurfaceVariant))
                    duration.setTextColor(context.getColor(R.color.onSurfaceVariant))
                    albumArt.isSelected = false
                }

                // 先停止之前的动画
                currentAnimator?.end()
                currentAnimator = null
                binding.albumArt.rotation = 0f

                if (isCurrentPlaying && isPlaying) {
                    // 播放时：开始旋转动画
                    val animator = android.animation.ObjectAnimator.ofFloat(
                        binding.albumArt,
                        "rotation",
                        0f,
                        360f
                    )
                    animator.duration = 8000
                    animator.interpolator = android.view.animation.LinearInterpolator()
                    animator.repeatCount = android.animation.ValueAnimator.INFINITE
                    animator.start()
                    // 保存动画引用
                    currentAnimator = animator
                }

                root.setOnClickListener {
                    listener.onSongClick(song, position)
                }

                root.setOnLongClickListener {
                    // 显示文件全路径的弱提示
                    Toast.makeText(
                        context,
                        song.path,
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
        }
    }

    private class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }
    }
}