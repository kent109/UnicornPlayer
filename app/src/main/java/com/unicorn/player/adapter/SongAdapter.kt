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
        holder.bind(song, position, currentPlayingSong, isPlaying)
    }

    /**
     * 当ViewHolder附加到窗口时调用 - 用于启动动画
     */
    override fun onViewAttachedToWindow(holder: SongViewHolder) {
        super.onViewAttachedToWindow(holder)
        // 检查是否是当前播放歌曲且正在播放，如果是则启动动画
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            val song = getItem(position)
            if (currentPlayingSong?.id == song.id && isPlaying) {
                holder.startRotationAnimation()
            }
        }
    }

    /**
     * 当ViewHolder从窗口分离时调用 - 用于停止动画
     */
    override fun onViewDetachedFromWindow(holder: SongViewHolder) {
        super.onViewDetachedFromWindow(holder)
        // 停止动画
        holder.stopRotationAnimation()
    }

    inner class SongViewHolder(
        private val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        // 保存当前动画的引用，以便可以停止
        private var currentAnimator: android.animation.ObjectAnimator? = null

        fun bind(song: Song, position: Int, currentPlayingSong: Song?, isPlaying: Boolean) {
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

                // 重置旋转角度
                albumArt.rotation = 0f

                root.setOnClickListener {
                    listener.onSongClick(song, position)
                }

                root.setOnLongClickListener {
                    Toast.makeText(
                        context,
                        song.path,
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
        }

        /**
         * 开始旋转动画
         */
        fun startRotationAnimation() {
            if (currentAnimator != null) return

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
            currentAnimator = animator
        }

        /**
         * 停止旋转动画
         */
        fun stopRotationAnimation() {
            currentAnimator?.end()
            currentAnimator = null
            binding.albumArt.rotation = 0f
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