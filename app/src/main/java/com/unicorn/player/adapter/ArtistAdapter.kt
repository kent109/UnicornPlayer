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
import com.unicorn.player.util.CoverImage

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

                // 曲库里同名歌手可能混用大小写（如 Beyond/BEYOND），网格显示名取"首次出现的
                // 原始写法"，列表顺序一变显示名就会翻面；来源标签存的还是当初点击时的写法，
                // 高亮比较必须忽略大小写，否则显示名翻面后高亮永远匹配不上
                val isPlaying = artist.name.equals(currentPlayingArtist, ignoreCase = true)
                CoverImage.bind(
                    ivArtistIcon, artist.coverPath, isPlaying, R.drawable.artist_icon,
                    imageInsetDp = 6
                )
                if (isPlaying) {
                    val highlightColor = ThemeSettingActivity.resolveHighlightColor(
                        ivArtistIcon.context
                    )
                    tvArtistName.setTextColor(highlightColor)
                    tvSongCount.setTextColor(highlightColor)
                } else {
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
