package com.unicorn.player.adapter

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
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

    // 保存旋转角度，用于暂停/恢复动画时保持角度
    private val rotationAngleMap = mutableMapOf<Long, Float>()
    private var recyclerView: RecyclerView? = null

    fun setRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    fun getRecyclerView(): RecyclerView? = recyclerView

    /**
     * 暂停当前播放歌曲的动画（保留角度）
     */
    fun pauseCurrentSongAnimation() {
        currentPlayingSong?.let { song ->
            val currentPosition = currentList.indexOfFirst { it.id == song.id }
            if (currentPosition != -1) {
                val recyclerView = getRecyclerView() ?: return
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentPosition)
                if (viewHolder is SongViewHolder) {
                    viewHolder.pauseRotationAnimation()
                }
            }
        }
    }

    /**
     * 恢复当前播放歌曲的动画
     */
    fun resumeCurrentSongAnimation() {
        if (!isPlaying) return
        currentPlayingSong?.let { song ->
            val currentPosition = currentList.indexOfFirst { it.id == song.id }
            if (currentPosition != -1) {
                val recyclerView = getRecyclerView() ?: return
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentPosition)
                if (viewHolder is SongViewHolder) {
                    val savedAngle = rotationAngleMap[song.id] ?: 0f
                    viewHolder.binding.albumArt.rotation = savedAngle
                    viewHolder.startRotationAnimation()
                }
            }
        }
    }

    /**
     * 停止当前播放歌曲的动画（重置角度）
     */
    fun stopCurrentSongAnimation() {
        currentPlayingSong?.let { song ->
            rotationAngleMap.remove(song.id)
            val currentPosition = currentList.indexOfFirst { it.id == song.id }
            if (currentPosition != -1) {
                val recyclerView = getRecyclerView() ?: return
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentPosition)
                if (viewHolder is SongViewHolder) {
                    viewHolder.stopRotationAnimation()
                }
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

        // 确保当前播放歌曲的动画正常运行
        if (currentPlayingSong?.id == song.id && isPlaying) {
            // 再次确认位置匹配，避免 ViewHolder 复用时的状态污染
            val currentPlayingPosition =
                currentList.indexOfFirst { it.id == currentPlayingSong?.id }
            if (currentPlayingPosition == position) {
                val savedAngle = rotationAngleMap[song.id] ?: 0f
                holder.binding.albumArt.rotation = savedAngle
                holder.startRotationAnimation()
            }
        } else {
            // 确保非播放歌曲的动画被停止，避免状态污染
            holder.stopRotationAnimation()
        }
    }

    /**
     * 当ViewHolder附加到窗口时调用 - 用于恢复动画（从不可见变为可见）
     * 只对当前播放的歌曲恢复动画
     */
    override fun onViewAttachedToWindow(holder: SongViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return

        val song = getItem(position)
        // 严格检查：必须是当前播放的歌曲 且 正在播放状态 且 ViewHolder 确实是当前播放歌曲的 ViewHolder
        if (currentPlayingSong?.id == song.id && isPlaying) {
            // 再次确认：检查当前可见的 ViewHolder 确实是当前播放歌曲的
            val currentPlayingPosition =
                currentList.indexOfFirst { it.id == currentPlayingSong?.id }
            if (currentPlayingPosition == position) {
                val savedAngle = rotationAngleMap[song.id] ?: 0f
                holder.binding.albumArt.rotation = savedAngle
                holder.startRotationAnimation()
            }
        } else {
            // 确保非播放歌曲的动画被停止，避免状态污染
            holder.stopRotationAnimation()
        }
    }

    /**
     * 当ViewHolder从窗口分离时调用 - 用于暂停动画（从可见变为不可见）
     * 只对当前播放的歌曲暂停动画，其他item直接停止
     */
    override fun onViewDetachedFromWindow(holder: SongViewHolder) {
        super.onViewDetachedFromWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            val song = getItem(position)
            if (currentPlayingSong?.id == song.id) {
                // 保存当前旋转角度
                rotationAngleMap[song.id] = holder.binding.albumArt.rotation
                // 暂停动画而不是停止，保留角度
                holder.pauseRotationAnimation()
            } else {
                // 非播放歌曲直接停止动画（重置状态）
                holder.stopRotationAnimation()
            }
        } else {
            // 如果 position 是 NO_POSITION，停止所有动画以避免状态污染
            holder.stopRotationAnimation()
        }
    }

    inner class SongViewHolder(
        internal val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        // 保存当前动画的引用，以便可以停止
        private var currentAnimator: ObjectAnimator? = null

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
                    songTitle.setTextColor(context.getColor(com.unicorn.player.R.color.onSurface))
                    artistName.setTextColor(context.getColor(com.unicorn.player.R.color.onSurfaceVariant))
                    albumName.setTextColor(context.getColor(com.unicorn.player.R.color.onSurfaceVariant))
                    duration.setTextColor(context.getColor(com.unicorn.player.R.color.onSurfaceVariant))
                    albumArt.isSelected = false
                }

                // 只对非当前播放的歌曲重置旋转角度
                if (!isCurrentPlaying) {
                    albumArt.rotation = 0f
                }

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
         * 开始旋转动画（从当前角度继续）
         */
        fun startRotationAnimation() {
            if (currentAnimator != null) return

            val currentRotation = binding.albumArt.rotation
            val animator = ObjectAnimator.ofFloat(
                binding.albumArt,
                "rotation",
                currentRotation,
                currentRotation + 360f
            )
            animator.duration = 8000
            animator.interpolator = LinearInterpolator()
            animator.repeatCount = ValueAnimator.INFINITE
            animator.start()
            currentAnimator = animator
        }

        /**
         * 暂停旋转动画（保留角度）
         */
        fun pauseRotationAnimation() {
            currentAnimator?.let {
                // 保存当前角度后取消动画
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    val song = currentList.getOrNull(bindingAdapterPosition)
                    if (song != null) {
                        rotationAngleMap[song.id] = binding.albumArt.rotation
                    }
                }
                it.cancel()
                currentAnimator = null
            }
        }

        /**
         * 停止旋转动画（重置角度）
         */
        fun stopRotationAnimation() {
            currentAnimator?.cancel()
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