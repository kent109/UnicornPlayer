package com.unicorn.player.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.databinding.ItemLrcSearchResultBinding
import com.unicorn.player.model.LrcSearchResult
import java.util.Locale

/**
 * 搜索结果列表适配器
 */
class LrcSearchResultAdapter(
    private val listener: OnResultClickListener
) : ListAdapter<LrcSearchResult, LrcSearchResultAdapter.ResultViewHolder>(ResultDiffCallback()) {

    interface OnResultClickListener {
        fun onResultClick(result: LrcSearchResult)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val binding = ItemLrcSearchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ResultViewHolder(
        private val binding: ItemLrcSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(result: LrcSearchResult) {
            binding.tvTrackName.text = result.trackName
            binding.tvArtistName.text = result.artistName
            binding.tvAlbumName.text = result.albumName
            binding.tvDuration.text = formatDuration(result.duration)
            binding.root.setOnClickListener { listener.onResultClick(result) }
        }

        private fun formatDuration(seconds: Double): String {
            val totalSeconds = seconds.toInt()
            val minutes = totalSeconds / 60
            val secs = totalSeconds % 60
            return String.format(Locale.getDefault(), "%02d:%02d", minutes, secs)
        }
    }

    private class ResultDiffCallback : DiffUtil.ItemCallback<LrcSearchResult>() {
        override fun areItemsTheSame(oldItem: LrcSearchResult, newItem: LrcSearchResult): Boolean {
            // 以 trackName + artistName + 前50字符歌词作为唯一标识
            val oldKey = "${oldItem.trackName}|${oldItem.artistName}|${oldItem.syncedLyrics.take(50)}"
            val newKey = "${newItem.trackName}|${newItem.artistName}|${newItem.syncedLyrics.take(50)}"
            return oldKey == newKey
        }

        override fun areContentsTheSame(oldItem: LrcSearchResult, newItem: LrcSearchResult): Boolean {
            return oldItem == newItem
        }
    }
}
