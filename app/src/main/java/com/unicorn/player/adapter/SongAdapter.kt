package com.unicorn.player.adapter

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.util.KyrieDrawable
import com.unicorn.player.ThemeSettingActivity
import com.unicorn.player.databinding.ItemSongBinding
import com.unicorn.player.model.Song

class SongAdapter(
    private val listener: OnSongClickListener,
    private val moreClickListener: OnSongMoreClickListener? = null,
    var currentPlayingSong: Song? = null,
    var isPlaying: Boolean = false
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    var currentPlaybackPositionMs: Long = 0L

    companion object {
        const val TAG = "SongAdapter"
        private const val CASSETTE_WIDTH_DP = 48
        private const val CASSETTE_HEIGHT_DP = 32
        private const val DISC_WIDTH_DP = 48
        private const val DISC_HEIGHT_DP = 48
        private const val CASSETTE_ART_GAP_DP = 12
        private const val DISC_ART_GAP_DP = 12

        private val DISC_PLAYING_DRAWABLES = intArrayOf(
            com.unicorn.player.R.drawable.ic_disc_playing_1,
            com.unicorn.player.R.drawable.ic_disc_playing_2,
            com.unicorn.player.R.drawable.ic_disc_playing_3,
            com.unicorn.player.R.drawable.ic_disc_playing_4,
            com.unicorn.player.R.drawable.ic_disc_playing_5,
            com.unicorn.player.R.drawable.ic_disc_playing_6,
            com.unicorn.player.R.drawable.ic_disc_playing_7
        )

        private val CASSETTE_PLAYING_DRAWABLES = intArrayOf(
            com.unicorn.player.R.drawable.avd_cassette_play_1,
            com.unicorn.player.R.drawable.avd_cassette_play_2,
            com.unicorn.player.R.drawable.avd_cassette_play_3,
            com.unicorn.player.R.drawable.avd_cassette_play_4,
            com.unicorn.player.R.drawable.avd_cassette_play_5,
            com.unicorn.player.R.drawable.avd_cassette_play_6,
            com.unicorn.player.R.drawable.avd_cassette_play_7
        )
    }

    private var recyclerView: RecyclerView? = null

    var isPaused = false
    var isCassetteMode = true

    private val rotationAngleMap = mutableMapOf<Long, Float>()
    private val cassettePauseTimeMap = mutableMapOf<Long, Long>()

    fun setRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    fun getRecyclerView(): RecyclerView? = recyclerView

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
                            if (isCassetteMode) {
                                cassettePauseTimeMap[song.id] = viewHolder.pauseCassetteAnimation()
                            } else {
                                rotationAngleMap[song.id] = viewHolder.binding.albumArt.rotation
                                viewHolder.pauseDiscAnimation()
                            }
                            return
                        }
                    }
                }
            }
        }
    }

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
                        if (isCassetteMode) {
                            cassettePauseTimeMap[song.id] = viewHolder.pauseCassetteAnimation()
                        } else {
                            rotationAngleMap[song.id] = viewHolder.binding.albumArt.rotation
                            viewHolder.pauseDiscAnimation()
                        }
                    } else {
                        if (isCassetteMode) {
                            viewHolder.resetToStaticCassette()
                        } else {
                            viewHolder.stopDiscAnimation()
                        }
                    }
                }
            }
        }
    }

    fun resumeCurrentSongAnimation() {
        if (!isPlaying || isPaused) return
        currentPlayingSong?.let { song ->
            val currentPosition = currentList.indexOfFirst { it.id == song.id }
            if (currentPosition != -1) {
                val recyclerView = getRecyclerView() ?: return
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentPosition)
                if (viewHolder is SongViewHolder) {
                    if (isCassetteMode) {
                        viewHolder.resumeCassetteAnimation(currentPlaybackPositionMs, song.duration)
                    } else {
                        val savedAngle = rotationAngleMap[song.id] ?: 0f
                        viewHolder.binding.albumArt.rotation = savedAngle
                        viewHolder.startDiscAnimation()
                    }
                }
            }
        }
    }

    fun stopCurrentSongAnimation() {
        currentPlayingSong?.let { song ->
            rotationAngleMap.remove(song.id)
            cassettePauseTimeMap.remove(song.id)
            val currentPosition = currentList.indexOfFirst { it.id == song.id }
            if (currentPosition != -1) {
                val recyclerView = getRecyclerView() ?: return
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(currentPosition)
                if (viewHolder is SongViewHolder) {
                    if (isCassetteMode) {
                        viewHolder.resetToStaticCassette()
                    } else {
                        viewHolder.stopDiscAnimation()
                    }
                }
            }
        }
    }

    fun updateCassetteAnimationProgress(currentPositionMs: Long) {
        if (!isCassetteMode) return
        val song = currentPlayingSong ?: return
        val position = currentList.indexOfFirst { it.id == song.id }
        if (position == -1) return
        val animationTime = if (song.duration > 0) {
            (currentPositionMs.toFloat() / song.duration * song.duration).toLong()
        } else {
            0L
        }
        val recyclerView = getRecyclerView() ?: return
        val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
        if (viewHolder is SongViewHolder) {
            val drawable = viewHolder.binding.albumArt.drawable as? KyrieDrawable
            if (drawable != null) {
                drawable.currentPlayTime = animationTime
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
                if (isCassetteMode) {
                    holder.showPausedCassette(cassettePauseTimeMap[song.id], song.duration)
                } else {
                    val savedAngle = rotationAngleMap[song.id] ?: 0f
                    holder.binding.albumArt.rotation = savedAngle
                    holder.pauseDiscAnimation()
                }
            } else if (isPlaying) {
                val currentPlayingPosition =
                    currentList.indexOfFirst { it.id == currentPlayingSong?.id }
                if (currentPlayingPosition == position) {
                    if (isCassetteMode) {
                        holder.resumeCassetteAnimation(currentPlaybackPositionMs, song.duration)
                    } else {
                        val savedAngle = rotationAngleMap[song.id] ?: 0f
                        holder.binding.albumArt.rotation = savedAngle
                        holder.startDiscAnimation()
                    }
                }
            } else {
                if (isCassetteMode) {
                    holder.showPausedCassette(cassettePauseTimeMap[song.id], song.duration)
                } else {
                    val savedAngle = rotationAngleMap[song.id] ?: 0f
                    holder.binding.albumArt.rotation = savedAngle
                    holder.pauseDiscAnimation()
                }
            }
        } else {
            if (isCassetteMode) {
                holder.resetToStaticCassette()
            } else {
                holder.stopDiscAnimation()
            }
        }
    }

    override fun onViewAttachedToWindow(holder: SongViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return

        if (isPaused) return

        val song = getItem(position)
        if (currentPlayingSong?.id == song.id) {
            if (isPlaying) {
                val currentPlayingPosition =
                    currentList.indexOfFirst { it.id == currentPlayingSong?.id }
                if (currentPlayingPosition == position) {
                    if (isCassetteMode) {
                        val animationTime = if (song.duration > 0) {
                            (currentPlaybackPositionMs.toFloat() / song.duration * song.duration).toLong()
                        } else {
                            0L
                        }
                        holder.resumeCassetteAnimation(animationTime, song.duration)
                    } else {
                        val savedAngle = rotationAngleMap[song.id] ?: 0f
                        holder.binding.albumArt.rotation = savedAngle
                        holder.startDiscAnimation()
                    }
                }
            } else {
                if (isCassetteMode) {
                    val animationTime = if (song.duration > 0) {
                        (currentPlaybackPositionMs.toFloat() / song.duration * song.duration).toLong()
                    } else {
                        0L
                    }
                    holder.showPausedCassette(animationTime, song.duration)
                } else {
                    val savedAngle = rotationAngleMap[song.id] ?: 0f
                    holder.binding.albumArt.rotation = savedAngle
                    holder.pauseDiscAnimation()
                }
            }
        } else {
            if (isCassetteMode) {
                holder.resetToStaticCassette()
            } else {
                holder.stopDiscAnimation()
            }
        }
    }

    override fun onViewDetachedFromWindow(holder: SongViewHolder) {
        super.onViewDetachedFromWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            val song = getItem(position)
            if (currentPlayingSong?.id == song.id) {
                if (isCassetteMode) {
                    cassettePauseTimeMap[song.id] = holder.pauseCassetteAnimation()
                } else {
                    rotationAngleMap[song.id] = holder.binding.albumArt.rotation
                    holder.pauseDiscAnimation()
                }
            } else {
                if (isCassetteMode) {
                    holder.resetToStaticCassette()
                } else {
                    holder.stopDiscAnimation()
                }
            }
        } else {
            if (isCassetteMode) {
                holder.resetToStaticCassette()
            } else {
                holder.stopDiscAnimation()
            }
        }
    }

    override fun onViewRecycled(holder: SongViewHolder) {
        super.onViewRecycled(holder)
        if (isCassetteMode) {
            holder.resetToStaticCassette()
        } else {
            holder.stopDiscAnimation()
        }
    }

    inner class SongViewHolder(
        internal val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentAnimator: ObjectAnimator? = null

        init {
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
                albumName.text = song.album

                quality.setText(song.quality)

                val context = binding.root.context
                val density = context.resources.displayMetrics.density

                updateAlbumArtLayout(density)

                val gapPx = (if (isCassetteMode) CASSETTE_ART_GAP_DP else DISC_ART_GAP_DP) * density
                updateStartMarginRelativeToAlbumArt(songTitle, gapPx)
                updateStartMarginRelativeToAlbumArt(artistName, gapPx)
                updateStartMarginRelativeToAlbumArt(albumName, gapPx)

                val isCurrentPlaying = currentPlayingSong?.id == song.id

                if (isCurrentPlaying) {
                    val highlightColor = ThemeSettingActivity.resolveHighlightColor(context)
                    songTitle.setTextColor(highlightColor)
                    artistName.setTextColor(highlightColor)
                    albumName.setTextColor(highlightColor)
                    if (isCassetteMode) {
                        val colorIndex = ThemeSettingActivity.resolveHighlightColorIndex(context)
                        val resId = CASSETTE_PLAYING_DRAWABLES.getOrElse(colorIndex) { CASSETTE_PLAYING_DRAWABLES[0] }
                        albumArt.setImageResource(resId)
                    } else {
                        val colorIndex = ThemeSettingActivity.resolveHighlightColorIndex(context)
                        val resId = DISC_PLAYING_DRAWABLES.getOrElse(colorIndex) { DISC_PLAYING_DRAWABLES[0] }
                        albumArt.setImageResource(resId)
                    }
                } else {
                    songTitle.setTextColor(context.getColor(com.unicorn.player.R.color.onSurface))
                    artistName.setTextColor(context.getColor(com.unicorn.player.R.color.onSurfaceVariant))
                    albumName.setTextColor(context.getColor(com.unicorn.player.R.color.onSurfaceVariant))
                    if (isCassetteMode) {
                        resetToStaticCassette()
                    } else {
                        stopDiscAnimation()
                        albumArt.setImageResource(com.unicorn.player.R.drawable.ic_disc)
                    }
                }

                val qualityColor = when (song.quality) {
                    "SQ" -> context.getColor(com.unicorn.player.R.color.quality_sq)
                    "HQ" -> context.getColor(com.unicorn.player.R.color.quality_hq)
                    "STD" -> context.getColor(com.unicorn.player.R.color.quality_std)
                    else -> context.getColor(com.unicorn.player.R.color.quality_ord)
                }
                quality.setColor(qualityColor)

                if (!isCurrentPlaying && !isCassetteMode) {
                    albumArt.rotation = 0f
                }

                root.setOnClickListener {
                    listener.onSongClick(song, position)
                }

                btnMore.setOnClickListener {
                    moreClickListener?.onMoreClick(song, position)
                }
            }
        }

        private fun updateAlbumArtLayout(density: Float) {
            val widthPx = ((if (isCassetteMode) CASSETTE_WIDTH_DP else DISC_WIDTH_DP) * density).toInt()
            val heightPx = ((if (isCassetteMode) CASSETTE_HEIGHT_DP else DISC_HEIGHT_DP) * density).toInt()
            val params = binding.albumArt.layoutParams
            params.width = widthPx
            params.height = heightPx
            binding.albumArt.layoutParams = params
        }

        private fun updateStartMarginRelativeToAlbumArt(view: View, gapPx: Float) {
            val params = view.layoutParams as ConstraintLayout.LayoutParams
            params.marginStart = gapPx.toInt()
            view.layoutParams = params
        }

        fun startCassetteAnimation(durationMs: Long) {
            val context = binding.albumArt.context
            val colorIndex = ThemeSettingActivity.resolveHighlightColorIndex(context)
            val resId = CASSETTE_PLAYING_DRAWABLES.getOrElse(colorIndex) { CASSETTE_PLAYING_DRAWABLES[0] }
            val drawable = KyrieDrawable.create(context, resId)
            drawable.setAnimationDuration(durationMs)
            binding.albumArt.setImageDrawable(drawable)
            drawable.start()
        }

        fun pauseCassetteAnimation(): Long {
            val drawable = binding.albumArt.drawable as? KyrieDrawable
            val time = drawable?.currentPlayTime ?: 0L
            drawable?.pause()
            return time
        }

        fun resumeCassetteAnimation(savedTime: Long?, durationMs: Long) {
            val context = binding.albumArt.context
            val colorIndex = ThemeSettingActivity.resolveHighlightColorIndex(context)
            val resId = CASSETTE_PLAYING_DRAWABLES.getOrElse(colorIndex) { CASSETTE_PLAYING_DRAWABLES[0] }
            val currentDrawable = binding.albumArt.drawable as? KyrieDrawable
            if (currentDrawable != null) {
                if (currentDrawable.isPaused()) {
                    currentDrawable.resume()
                } else if (!currentDrawable.isRunning()) {
                    if (savedTime != null && savedTime > 0) {
                        currentDrawable.currentPlayTime = savedTime
                    }
                    currentDrawable.start()
                }
            } else {
                val drawable = KyrieDrawable.create(context, resId)
                drawable.setAnimationDuration(durationMs)
                binding.albumArt.setImageDrawable(drawable)
                if (savedTime != null && savedTime > 0) {
                    drawable.currentPlayTime = savedTime
                }
                drawable.start()
            }
        }

        fun showPausedCassette(savedTime: Long?, durationMs: Long) {
            val context = binding.albumArt.context
            val colorIndex = ThemeSettingActivity.resolveHighlightColorIndex(context)
            val resId = CASSETTE_PLAYING_DRAWABLES.getOrElse(colorIndex) { CASSETTE_PLAYING_DRAWABLES[0] }
            val currentDrawable = binding.albumArt.drawable as? KyrieDrawable
            if (currentDrawable != null) {
                currentDrawable.stop()
                if (savedTime != null && savedTime > 0) {
                    currentDrawable.currentPlayTime = savedTime
                }
            } else {
                val drawable = KyrieDrawable.create(context, resId)
                drawable.setAnimationDuration(durationMs)
                binding.albumArt.setImageDrawable(drawable)
                if (savedTime != null && savedTime > 0) {
                    drawable.currentPlayTime = savedTime
                }
            }
        }

        fun stopCassetteAnimation() {
            val drawable = binding.albumArt.drawable as? KyrieDrawable
            drawable?.stop()
        }

        fun resetToStaticCassette() {
            (binding.albumArt.drawable as? KyrieDrawable)?.stop()
            binding.albumArt.setImageResource(com.unicorn.player.R.drawable.ic_cassette_photo)
        }

        fun startDiscAnimation() {
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

        fun pauseDiscAnimation() {
            currentAnimator?.let {
                it.cancel()
                currentAnimator = null
            }
        }

        fun stopDiscAnimation() {
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
