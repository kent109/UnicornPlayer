package com.unicorn.player.adapter

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Rect
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.TouchDelegate
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.databinding.ItemSongBinding
import com.unicorn.player.model.Song

class SongAdapter(
    private val listener: OnSongClickListener,
    private val moreClickListener: OnSongMoreClickListener? = null,
    var currentPlayingSong: Song? = null,
    var isPlaying: Boolean = false
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    companion object {
        const val TAG = "SongAdapter"
    }

    // 保存旋转角度，用于暂停/恢复动画时保持角度
    private val rotationAngleMap = mutableMapOf<Long, Float>()
    private var recyclerView: RecyclerView? = null

    // 标记是否暂停（MainActivity不可见时）
    var isPaused = false

    fun setRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    fun getRecyclerView(): RecyclerView? = recyclerView

    /**
     * 暂停当前播放歌曲的动画（保留角度）
     * 遍历所有可见子视图，找到当前播放歌曲的 ViewHolder 并暂停动画
     */
    fun pauseCurrentSongAnimation() {
        currentPlayingSong?.let { song ->
            val recyclerView = getRecyclerView() ?: return
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChildAt(i)
                val viewHolder = recyclerView.getChildViewHolder(child)
                if (viewHolder is SongViewHolder) {
                    val position = viewHolder.bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        val currentSong = getItem(position)
                        if (currentSong.id == song.id) {
                            // 保存当前角度
                            rotationAngleMap[song.id] = viewHolder.binding.albumArt.rotation
                            // 暂停动画
                            viewHolder.pauseRotationAnimation()
                            return
                        }
                    }
                }
            }
        }
    }

    /**
     * 暂停所有可见 ViewHolder 的动画（MainActivity 进入后台时调用）
     * 只对当前播放的歌曲暂停动画（保留角度），其他 item 直接停止
     */
    fun pauseAllAnimations() {
        isPaused = true
        val recyclerView = getRecyclerView() ?: return
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val viewHolder = recyclerView.getChildViewHolder(child)
            if (viewHolder is SongViewHolder) {
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val song = getItem(position)
                    if (currentPlayingSong?.id == song.id) {
                        // 保存当前旋转角度
                        rotationAngleMap[song.id] = viewHolder.binding.albumArt.rotation
                        // 暂停动画而不是停止，保留角度
                        viewHolder.pauseRotationAnimation()
                    } else {
                        // 非播放歌曲直接停止动画（重置状态）
                        viewHolder.stopRotationAnimation()
                    }
                }
            }
        }
    }

    /**
     * 恢复当前播放歌曲的动画（从保存的角度继续）
     */
    fun resumeCurrentSongAnimation() {
        if (!isPlaying || isPaused) return
        currentPlayingSong?.let { song ->
            val currentPosition = currentList.indexOfFirst { it.id == song.id }
            if (currentPosition != -1) {
                val recyclerView = getRecyclerView() ?: return
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentPosition)
                if (viewHolder is SongViewHolder) {
                    // 从保存的角度继续旋转
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

    interface OnSongMoreClickListener {
        fun onMoreClick(song: Song, position: Int)
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

        if (currentPlayingSong?.id == song.id) {
            if (isPaused) {
                // MainActivity 不可见，恢复角度并暂停动画
                val savedAngle = rotationAngleMap[song.id] ?: 0f
                holder.binding.albumArt.rotation = savedAngle
                holder.pauseRotationAnimation()
            } else if (isPlaying) {
                // 正在播放，恢复角度并启动动画
                val currentPlayingPosition =
                    currentList.indexOfFirst { it.id == currentPlayingSong?.id }
                if (currentPlayingPosition == position) {
                    val savedAngle = rotationAngleMap[song.id] ?: 0f
                    holder.binding.albumArt.rotation = savedAngle
                    holder.startRotationAnimation()
                }
            } else {
                // 暂停播放，保存当前角度并暂停动画（不重置角度）
                rotationAngleMap[song.id] = holder.binding.albumArt.rotation
                holder.pauseRotationAnimation()
            }
        } else {
            // 非播放歌曲直接停止动画（重置状态）
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

        // 如果处于暂停状态（MainActivity不可见），不启动动画
        if (isPaused) return

        val song = getItem(position)
        if (currentPlayingSong?.id == song.id) {
            // 恢复保存的角度
            val savedAngle = rotationAngleMap[song.id] ?: 0f
            holder.binding.albumArt.rotation = savedAngle
            if (isPlaying) {
                // 正在播放，确认位置匹配后启动动画
                val currentPlayingPosition =
                    currentList.indexOfFirst { it.id == currentPlayingSong?.id }
                if (currentPlayingPosition == position) {
                    holder.startRotationAnimation()
                }
            } else {
                // 暂停播放，暂停动画（不重置角度）
                holder.pauseRotationAnimation()
            }
        } else {
            // 非播放歌曲直接停止动画（重置状态）
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
                // 保存当前旋转角度（用于恢复）
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

        init {
            // 增大 btnMore 的点击区域
            binding.btnMore.post {
                val rect = Rect()
                binding.btnMore.getHitRect(rect)
                val extra = (10 * binding.root.context.resources.displayMetrics.density).toInt()
                rect.top -= extra
                rect.bottom += extra
                rect.left -= extra
                rect.right += extra
                binding.root.touchDelegate = TouchDelegate(rect, binding.btnMore)
            }
        }

        fun bind(song: Song, position: Int, currentPlayingSong: Song?, isPlaying: Boolean) {
            binding.apply {
                songTitle.text = song.title
                artistName.text = song.artist
                albumName.text =
                    if (TextUtils.equals(song.album, "Music")) "<unknown>" else song.album

                // 设置品质标签文字
                quality.setText(song.quality)

                // 获取context
                val context = binding.root.context

                // 检查是否是当前播放的歌曲
                val isCurrentPlaying = currentPlayingSong?.id == song.id

                // 动态设置颜色
                if (isCurrentPlaying) {
                    songTitle.setTextColor(context.getColor(android.R.color.holo_red_light))
                    artistName.setTextColor(context.getColor(android.R.color.holo_red_light))
                    albumName.setTextColor(context.getColor(android.R.color.holo_red_light))
                    albumArt.isSelected = true
                } else {
                    songTitle.setTextColor(context.getColor(com.unicorn.player.R.color.onSurface))
                    artistName.setTextColor(context.getColor(com.unicorn.player.R.color.onSurfaceVariant))
                    albumName.setTextColor(context.getColor(com.unicorn.player.R.color.onSurfaceVariant))
                    albumArt.isSelected = false
                }

                // 品质标签颜色根据音频质量固定设置
                val qualityColor = when (song.quality) {
                    "SQ" -> context.getColor(com.unicorn.player.R.color.quality_sq)
                    "HQ" -> context.getColor(com.unicorn.player.R.color.quality_hq)
                    "STD" -> context.getColor(com.unicorn.player.R.color.quality_std)
                    else -> context.getColor(com.unicorn.player.R.color.quality_ord)
                }
                quality.setColor(qualityColor)

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

                // 更多选项按钮点击事件
                btnMore.setOnClickListener {
                    moreClickListener?.onMoreClick(song, position)
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