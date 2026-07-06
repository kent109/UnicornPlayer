package com.unicorn.player.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.R
import com.unicorn.player.databinding.ItemLrcSearchResultBinding
import com.unicorn.player.model.LrcSearchResult
import java.util.Locale

/**
 * 搜索结果列表适配器
 */
class LrcSearchResultAdapter(
    private val listener: OnResultClickListener,
    private val longClickListener: OnResultLongClickListener? = null
) : ListAdapter<LrcSearchResult, LrcSearchResultAdapter.ResultViewHolder>(ResultDiffCallback()) {

    /** 当前选中项的 ID，仅记录最后一次点击/长按的项 */
    var selectedId: Long? = null
        set

    interface OnResultClickListener {
        fun onResultClick(result: LrcSearchResult)
    }

    interface OnResultLongClickListener {
        fun onResultLongClick(result: LrcSearchResult)
    }

    /** 选中指定项并刷新相关 item */
    fun setSelectedId(id: Long) {
        val oldId = selectedId
        if (oldId == id) return
        selectedId = id
        notifyItemChangedBySongId(oldId)
        notifyItemChangedBySongId(id)
    }

    private fun notifyItemChangedBySongId(id: Long?) {
        if (id == null) return
        val position = currentList.indexOfFirst { it.id == id }
        if (position >= 0) notifyItemChanged(position)
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

    override fun onBindViewHolder(
        holder: ResultViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        // 局部刷新：仅重绘背景
        payloads.forEach { payload ->
            if (payload == PAYLOAD_SELECTION_CHANGED) {
                holder.bindSelection(getItem(position))
            }
        }
    }

    inner class ResultViewHolder(
        private val binding: ItemLrcSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(result: LrcSearchResult) {
            binding.tvTrackName.text = result.trackName
            binding.tvArtistName.text = result.artistName
            binding.tvAlbumName.text = result.albumName
            binding.tvDuration.text = formatDuration(result.duration)
            binding.ivModified.visibility = if (result.isModified) View.VISIBLE else View.GONE
            updateSelectionBackground(result)
            binding.root.setOnClickListener {
                setSelectedId(result.id)
                listener.onResultClick(result)
            }
            binding.root.setOnLongClickListener {
                setSelectedId(result.id)
                longClickListener?.onResultLongClick(result)
                true
            }
        }

        /** 仅更新选中背景，供局部刷新使用 */
        fun bindSelection(result: LrcSearchResult) {
            updateSelectionBackground(result)
        }

        private fun updateSelectionBackground(result: LrcSearchResult) {
            val context = binding.root.context
            if (result.id == selectedId) {
                binding.border.background = ContextCompat.getDrawable(
                    context, R.drawable.bg_lrc_item_selected
                )
            } else {
                binding.border.background = null
            }
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
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: LrcSearchResult,
            newItem: LrcSearchResult
        ): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION_CHANGED = "selection_changed"
    }
}
