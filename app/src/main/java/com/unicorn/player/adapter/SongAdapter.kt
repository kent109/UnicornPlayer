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
    var currentPlayingSong: Song? = null
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

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
        holder.bind(song, position, currentPlayingSong)
    }

    inner class SongViewHolder(
        private val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song, position: Int, currentPlayingSong: Song?) {
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

                // 动态设置颜色 - 如果是当前播放的歌曲
                if (currentPlayingSong?.id == song.id) {
                    songTitle.setTextColor(context.getColor(android.R.color.holo_red_light))
                    artistName.setTextColor(context.getColor(android.R.color.holo_red_light))
                    albumName.setTextColor(context.getColor(android.R.color.holo_red_light))
                    duration.setTextColor(context.getColor(android.R.color.holo_red_light))
                    albumArt.setImageResource(R.drawable.ic_disc_playing)
                } else {
                    songTitle.setTextColor(context.getColor(R.color.onSurface))
                    artistName.setTextColor(context.getColor(R.color.onSurfaceVariant))
                    albumName.setTextColor(context.getColor(R.color.onSurfaceVariant))
                    duration.setTextColor(context.getColor(R.color.onSurfaceVariant))
                    albumArt.setImageResource(R.drawable.ic_disc)
                }

                root.setOnClickListener {
                    listener.onSongClick(song, position)
                }

                root.setOnLongClickListener {
                    // 显示文件全路径的弱提示
                    Toast.makeText(
                        context,
                        song.path,
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
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