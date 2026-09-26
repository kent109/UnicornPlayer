package com.unicorn.player.adapter

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.R
import com.unicorn.player.ThemeSettingActivity
import com.unicorn.player.databinding.ItemSongBinding
import com.unicorn.player.model.Song

/**
 * 播放队列适配器（播放页 ivSongList 弹出的 BottomSheetDialog 使用）。
 * 复用 item_song 布局，但隐藏更多按钮；
 * 当前播放歌曲高亮（文字 + 唱片图标使用主题高亮色），唱片图标带旋转动画，
 * 行为与 SongAdapter 一致：item 可见时旋转、滚动出屏/弹窗关闭/播放暂停时暂停并保留角度。
 */
class PlayQueueAdapter(
    private val onSongClick: (Song, Int) -> Unit
) : ListAdapter<Song, PlayQueueAdapter.QueueViewHolder>(QueueDiffCallback()) {

    companion object {
        private val DISC_PLAYING_DRAWABLES = intArrayOf(
            R.drawable.ic_disc_playing_1,
            R.drawable.ic_disc_playing_2,
            R.drawable.ic_disc_playing_3,
            R.drawable.ic_disc_playing_4,
            R.drawable.ic_disc_playing_5,
            R.drawable.ic_disc_playing_6,
            R.drawable.ic_disc_playing_7
        )
    }

    /** 当前正在播放的歌曲，用于高亮 */
    var currentPlayingSong: Song? = null

    /** 当前是否正在播放，决定旋转动画启停 */
    var isPlaying: Boolean = false

    /** 宿主不可见时为 true，可见 item 也保持动画暂停（与 SongAdapter 一致） */
    var isPaused: Boolean = false

    // 保存旋转角度，暂停/恢复时保持角度连续
    private val rotationAngleMap = mutableMapOf<Long, Float>()

    // 挂载的 RecyclerView，用于遍历可见 holder（弹窗关闭后随视图一起释放）
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val binding = ItemSongBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return QueueViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val song = getItem(position)
        holder.bind(song, position)

        // 动画状态与 SongAdapter.onBindViewHolder 保持一致
        if (currentPlayingSong?.id == song.id) {
            val savedAngle = rotationAngleMap[song.id] ?: 0f
            holder.binding.albumArt.rotation = savedAngle
            when {
                isPaused -> holder.pauseRotationAnimation()
                isPlaying -> holder.startRotationAnimation()
                else -> holder.pauseRotationAnimation()
            }
        } else {
            holder.stopRotationAnimation()
        }
    }

    override fun onViewAttachedToWindow(holder: QueueViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION || isPaused) return
        val song = getItem(position)
        if (currentPlayingSong?.id == song.id && isPlaying) {
            val savedAngle = rotationAngleMap[song.id] ?: 0f
            holder.binding.albumArt.rotation = savedAngle
            holder.startRotationAnimation()
        } else {
            holder.stopRotationAnimation()
        }
    }

    override fun onViewDetachedFromWindow(holder: QueueViewHolder) {
        super.onViewDetachedFromWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            val song = getItem(position)
            if (currentPlayingSong?.id == song.id) {
                // 保存角度后暂停，重新滑入时从该角度继续
                rotationAngleMap[song.id] = holder.binding.albumArt.rotation
            }
        }
        holder.pauseRotationAnimation()
    }

    /**
     * 暂停当前播放歌曲的动画（保留角度）。遍历可见 holder 查找
     */
    fun pauseCurrentSongAnimation() {
        val song = currentPlayingSong ?: return
        notifyVisibleHolders { holder, position ->
            if (getItem(position).id == song.id) {
                rotationAngleMap[song.id] = holder.binding.albumArt.rotation
                holder.pauseRotationAnimation()
            }
        }
    }

    /**
     * 恢复当前播放歌曲的动画（从保存的角度继续）
     */
    fun resumeCurrentSongAnimation() {
        if (!isPlaying || isPaused) return
        val song = currentPlayingSong ?: return
        val position = currentList.indexOfFirst { it.id == song.id }
        if (position == -1) return
        notifyVisibleHolders { holder, holderPosition ->
            if (holderPosition == position) {
                val savedAngle = rotationAngleMap[song.id] ?: 0f
                holder.binding.albumArt.rotation = savedAngle
                holder.startRotationAnimation()
            }
        }
    }

    private inline fun notifyVisibleHolders(
        action: (QueueViewHolder, Int) -> Unit
    ) {
        val rv = recyclerView ?: return
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i))
            if (holder is QueueViewHolder) {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    action(holder, position)
                }
            }
        }
    }

    inner class QueueViewHolder(
        internal val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentAnimator: ObjectAnimator? = null

        init {
            // 播放队列不提供更多操作，隐藏更多按钮
            binding.btnMore.visibility = View.GONE
        }

        fun bind(song: Song, position: Int) {
            binding.apply {
                songTitle.text = song.title
                artistName.text = song.artist
                albumName.text = song.album
                quality.setText(song.quality)

                val context = binding.root.context
                val isCurrentPlaying = currentPlayingSong?.id == song.id

                if (isCurrentPlaying) {
                    val highlightColor = ThemeSettingActivity.resolveHighlightColor(context)
                    songTitle.setTextColor(highlightColor)
                    artistName.setTextColor(highlightColor)
                    albumName.setTextColor(highlightColor)
                    val colorIndex = ThemeSettingActivity.resolveHighlightColorIndex(context)
                    val resId =
                        DISC_PLAYING_DRAWABLES.getOrElse(colorIndex) { DISC_PLAYING_DRAWABLES[0] }
                    albumArt.setImageResource(resId)
                } else {
                    songTitle.setTextColor(context.getColor(R.color.onSurface))
                    artistName.setTextColor(context.getColor(R.color.onSurfaceVariant))
                    albumName.setTextColor(context.getColor(R.color.onSurfaceVariant))
                    albumArt.setImageResource(R.drawable.ic_disc)
                }

                // 品质标签颜色根据音频质量固定设置
                val qualityColor = when (song.quality) {
                    "SQ" -> context.getColor(R.color.quality_sq)
                    "HQ" -> context.getColor(R.color.quality_hq)
                    "STD" -> context.getColor(R.color.quality_std)
                    else -> context.getColor(R.color.quality_ord)
                }
                quality.setColor(qualityColor)

                root.setOnClickListener {
                    onSongClick(song, position)
                }
            }
        }

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

        fun pauseRotationAnimation() {
            currentAnimator?.cancel()
            currentAnimator = null
        }

        fun stopRotationAnimation() {
            currentAnimator?.cancel()
            currentAnimator = null
            binding.albumArt.rotation = 0f
        }
    }

    private class QueueDiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }
    }
}
