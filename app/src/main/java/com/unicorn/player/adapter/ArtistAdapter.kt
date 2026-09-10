package com.unicorn.player.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.R
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

        private var lastPlaying = false

        fun bind(artist: Artist, position: Int) {
            binding.apply {
                tvArtistName.text = artist.name
                tvSongCount.text = "${artist.songCount} 首"

                val isPlaying = artist.name == currentPlayingArtist
                if (isPlaying != lastPlaying) {
                    lastPlaying = isPlaying
                    if (isPlaying) {
                        ivArtistIcon.isSelected = true
                        tvArtistName.setTextColor(
                            ContextCompat.getColor(
                                tvArtistName.context,
                                android.R.color.holo_red_light
                            )
                        )
                        tvSongCount.setTextColor(
                            ContextCompat.getColor(
                                tvSongCount.context,
                                android.R.color.holo_red_light
                            )
                        )
                    } else {
                        ivArtistIcon.isSelected = false
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
