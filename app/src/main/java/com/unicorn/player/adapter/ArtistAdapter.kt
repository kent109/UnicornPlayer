package com.unicorn.player.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.R
import com.unicorn.player.ThemeSettingActivity
import com.unicorn.player.databinding.ItemArtistBinding
import com.unicorn.player.model.Artist

class ArtistAdapter(
    private val listener: OnArtistClickListener
) : ListAdapter<Artist, ArtistAdapter.ArtistViewHolder>(ArtistDiffCallback()) {

    private var currentPlayingArtist: String? = null

    fun setPlayingArtist(artistName: String?) {
        currentPlayingArtist = artistName
        notifyDataSetChanged()
    }

    fun getPlayingArtist(): String? {
        return currentPlayingArtist
    }

    interface OnArtistClickListener {
        fun onArtistClick(artist: Artist, position: Int)

        fun onArtistLongClick(artist: Artist, position: Int)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val binding = ItemArtistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ArtistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ArtistViewHolder(
        private val binding: ItemArtistBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(artist: Artist, position: Int) {
            binding.apply {
                tvArtistName.text = artist.name
                tvSongCount.text = "${artist.songCount} 首"

                val isPlaying = artist.name == currentPlayingArtist
                if (isPlaying) {
                    val highlightColor = ThemeSettingActivity.resolveHighlightColor(
                        ivArtistIcon.context
                    )
                    ivArtistIcon.isSelected = true
                    ivArtistIcon.setColorFilter(highlightColor)
                    tvArtistName.setTextColor(highlightColor)
                    tvSongCount.setTextColor(highlightColor)
                } else {
                    ivArtistIcon.isSelected = false
                    ivArtistIcon.clearColorFilter()
                    tvArtistName.setTextColor(
                        ContextCompat.getColor(
                            tvArtistName.context,
                            R.color.onSurface
                        )
                    )
                    tvSongCount.setTextColor(
                        ContextCompat.getColor(
                            tvSongCount.context,
                            R.color.onSurfaceVariant
                        )
                    )
                }

                root.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        listener.onArtistClick(artist, position)
                    }
                }

                root.setOnLongClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        listener.onArtistLongClick(artist, position)
                    }
                    true
                }
            }
        }
    }

    private class ArtistDiffCallback : DiffUtil.ItemCallback<Artist>() {
        override fun areItemsTheSame(oldItem: Artist, newItem: Artist): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: Artist, newItem: Artist): Boolean {
            return oldItem == newItem
        }
    }
}
