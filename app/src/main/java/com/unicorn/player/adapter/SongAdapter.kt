package com.unicorn.player.adapter

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.view.LayoutInflater
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.ThemeSettingActivity
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
        private const val CASSETTE_WIDTH_DP = 64
        private const val CASSETTE_HEIGHT_DP = 42
        private const val DISC_WIDTH_DP = 48
        private const val DISC_HEIGHT_DP = 48
        private const val CASSETTE_ART_GAP_DP = 12
        private const val DISC_ART_GAP_DP = 16

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
                                viewHolder.stopCassetteAnimation()
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
                            viewHolder.stopCassetteAnimation()
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
                        viewHolder.startCassetteAnimation()
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
                    holder.stopCassetteAnimation()
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
                        holder.startCassetteAnimation()
                    } else {
                        val savedAngle = rotationAngleMap[song.id] ?: 0f
                        holder.binding.albumArt.rotation = savedAngle
                        holder.startDiscAnimation()
                    }
                }
            } else {
                if (isCassetteMode) {
                    holder.stopCassetteAnimation()
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
                        holder.startCassetteAnimation()
                    } else {
                        val savedAngle = rotationAngleMap[song.id] ?: 0f
                        holder.binding.albumArt.rotation = savedAngle
                        holder.startDiscAnimation()
                    }
                }
            } else {
                if (isCassetteMode) {
                    holder.stopCassetteAnimation()
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
                    holder.stopCassetteAnimation()
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

        fun startCassetteAnimation() {
            val context = binding.albumArt.context
            val colorIndex = ThemeSettingActivity.resolveHighlightColorIndex(context)
            val resId = CASSETTE_PLAYING_DRAWABLES.getOrElse(colorIndex) { CASSETTE_PLAYING_DRAWABLES[0] }
            val avd = AppCompatResources.getDrawable(context, resId)
            binding.albumArt.setImageDrawable(avd)
            (avd as? Animatable)?.start()
        }

        fun stopCassetteAnimation() {
            (binding.albumArt.drawable as? Animatable)?.stop()
        }

        fun resetToStaticCassette() {
            (binding.albumArt.drawable as? Animatable)?.stop()
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
