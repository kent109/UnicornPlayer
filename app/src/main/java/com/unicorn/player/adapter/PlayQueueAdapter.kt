package com.unicorn.player.adapter

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.util.KyrieDrawable
import com.unicorn.player.R
import com.unicorn.player.ThemeSettingActivity
import com.unicorn.player.databinding.ItemSongBinding
import com.unicorn.player.model.Song

class PlayQueueAdapter(
    private val onSongClick: (Song, Int) -> Unit
) : ListAdapter<Song, PlayQueueAdapter.QueueViewHolder>(QueueDiffCallback()) {

    companion object {
        private const val CASSETTE_WIDTH_DP = 48
        private const val CASSETTE_HEIGHT_DP = 32
        private const val DISC_WIDTH_DP = 48
        private const val DISC_HEIGHT_DP = 48
        private const val CASSETTE_ART_GAP_DP = 12
        private const val DISC_ART_GAP_DP = 12

        private val DISC_PLAYING_DRAWABLES = intArrayOf(
            R.drawable.ic_disc_playing_1,
            R.drawable.ic_disc_playing_2,
            R.drawable.ic_disc_playing_3,
            R.drawable.ic_disc_playing_4,
            R.drawable.ic_disc_playing_5,
            R.drawable.ic_disc_playing_6,
            R.drawable.ic_disc_playing_7
        )

        private val CASSETTE_PLAYING_DRAWABLES = intArrayOf(
            R.drawable.avd_cassette_play_1,
            R.drawable.avd_cassette_play_2,
            R.drawable.avd_cassette_play_3,
            R.drawable.avd_cassette_play_4,
            R.drawable.avd_cassette_play_5,
            R.drawable.avd_cassette_play_6,
            R.drawable.avd_cassette_play_7
        )
    }

    var currentPlayingSong: Song? = null
    var isPlaying: Boolean = false
    var isPaused: Boolean = false
    var isCassetteMode = true
    var currentPlaybackPositionMs: Long = 0L

    private val rotationAngleMap = mutableMapOf<Long, Float>()
    private val cassettePauseTimeMap = mutableMapOf<Long, Long>()
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
                if (isCassetteMode) {
                    holder.resumeCassetteAnimation(currentPlaybackPositionMs, song.duration)
                } else {
                    val savedAngle = rotationAngleMap[song.id] ?: 0f
                    holder.binding.albumArt.rotation = savedAngle
                    holder.startDiscAnimation()
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

    override fun onViewAttachedToWindow(holder: QueueViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION || isPaused) return
        val song = getItem(position)
        if (currentPlayingSong?.id == song.id) {
            if (isPlaying) {
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

    override fun onViewDetachedFromWindow(holder: QueueViewHolder) {
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

    fun pauseCurrentSongAnimation() {
        val song = currentPlayingSong ?: return
        notifyVisibleHolders { holder, position ->
            if (getItem(position).id == song.id) {
                if (isCassetteMode) {
                    cassettePauseTimeMap[song.id] = holder.pauseCassetteAnimation()
                } else {
                    rotationAngleMap[song.id] = holder.binding.albumArt.rotation
                    holder.pauseDiscAnimation()
                }
            }
        }
    }

    fun resumeCurrentSongAnimation() {
        if (!isPlaying || isPaused) return
        val song = currentPlayingSong ?: return
        val position = currentList.indexOfFirst { it.id == song.id }
        if (position == -1) return
        notifyVisibleHolders { holder, holderPosition ->
            if (holderPosition == position) {
                if (isCassetteMode) {
                    holder.resumeCassetteAnimation(currentPlaybackPositionMs, song.duration)
                } else {
                    val savedAngle = rotationAngleMap[song.id] ?: 0f
                    holder.binding.albumArt.rotation = savedAngle
                    holder.startDiscAnimation()
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
        notifyVisibleHolders { holder, holderPosition ->
            if (holderPosition == position) {
                val drawable = holder.binding.albumArt.drawable as? KyrieDrawable
                if (drawable != null) {
                    drawable.currentPlayTime = animationTime
                }
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
            binding.btnMore.visibility = View.GONE
        }

        fun bind(song: Song, position: Int) {
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
                        val resId =
                            CASSETTE_PLAYING_DRAWABLES.getOrElse(colorIndex) { CASSETTE_PLAYING_DRAWABLES[0] }
                        albumArt.setImageResource(resId)
                    } else {
                        val colorIndex = ThemeSettingActivity.resolveHighlightColorIndex(context)
                        val resId =
                            DISC_PLAYING_DRAWABLES.getOrElse(colorIndex) { DISC_PLAYING_DRAWABLES[0] }
                        albumArt.setImageResource(resId)
                    }
                } else {
                    songTitle.setTextColor(context.getColor(R.color.onSurface))
                    artistName.setTextColor(context.getColor(R.color.onSurfaceVariant))
                    albumName.setTextColor(context.getColor(R.color.onSurfaceVariant))
                    if (isCassetteMode) {
                        resetToStaticCassette()
                    } else {
                        stopDiscAnimation()
                        albumArt.setImageResource(R.drawable.ic_disc)
                    }
                }

                val qualityColor = when (song.quality) {
                    "SQ" -> context.getColor(R.color.quality_sq)
                    "HQ" -> context.getColor(R.color.quality_hq)
                    "STD" -> context.getColor(R.color.quality_std)
                    else -> context.getColor(R.color.quality_ord)
                }
                quality.setColor(qualityColor)

                if (!isCurrentPlaying) {
                    albumArt.rotation = 0f
                }

                root.setOnClickListener {
                    onSongClick(song, position)
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
            (binding.albumArt.drawable as? KyrieDrawable)?.stop()
        }

        fun resetToStaticCassette() {
            (binding.albumArt.drawable as? KyrieDrawable)?.stop()
            binding.albumArt.rotation = 0f
            binding.albumArt.setImageResource(R.drawable.ic_cassette_photo)
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
            currentAnimator?.cancel()
            currentAnimator = null
        }

        fun stopDiscAnimation() {
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
