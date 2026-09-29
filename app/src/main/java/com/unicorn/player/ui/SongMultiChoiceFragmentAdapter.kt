package com.unicorn.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.databinding.ItemSongMultiChoiceBinding
import com.unicorn.player.model.Song

class SongMultiChoiceFragmentAdapter(
    private var selectedSongIds: MutableSet<Long>
) : ListAdapter<Any, SongMultiChoiceFragmentAdapter.ViewHolder>(DiffCallback()) {

    companion object {
        private const val CASSETTE_ART_GAP_DP = 12
        private const val DISC_ART_GAP_DP = 12
    }

    var isCassetteMode = true

    interface OnCheckChangedListener {
        fun onCheckChanged()
    }

    private var onCheckChangedListener: OnCheckChangedListener? = null

    fun setOnCheckChangedListener(onCheckChangedListener: OnCheckChangedListener) {
        this.onCheckChangedListener = onCheckChangedListener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSongMultiChoiceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), isCassetteMode)
    }

    inner class ViewHolder(
        private val binding: ItemSongMultiChoiceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Any, isCassetteMode: Boolean) {
            when (item) {
                is Song -> {
                    binding.ivIcon.imageTintList = null
                    val density = binding.root.resources.displayMetrics.density
                    val widthPx = ((if (isCassetteMode) 48 else 48) * density).toInt()
                    val heightPx = ((if (isCassetteMode) 32 else 48) * density).toInt()
                    val params = binding.ivIcon.layoutParams
                    params.width = widthPx
                    params.height = heightPx
                    binding.ivIcon.layoutParams = params
                    if (isCassetteMode) {
                        binding.ivIcon.setImageResource(com.unicorn.player.R.drawable.ic_cassette_photo)
                    } else {
                        binding.ivIcon.setImageResource(com.unicorn.player.R.drawable.ic_disc)
                    }
                    val gapPx = (if (isCassetteMode) CASSETTE_ART_GAP_DP else DISC_ART_GAP_DP) * density
                    updateStartMarginRelativeToAlbumArt(binding.tvTitle, gapPx)
                    updateStartMarginRelativeToAlbumArt(binding.tvSubtitle, gapPx)
                    updateStartMarginRelativeToAlbumArt(binding.tvSubtitle2, gapPx)
                    binding.tvTitle.text = item.title
                    binding.tvSubtitle.text = item.artist
                    binding.tvSubtitle2.visibility = View.VISIBLE
                    binding.tvSubtitle2.text = item.album
                }
            }

            val isSelected = selectedSongIds.contains(
                when (item) {
                    is Song -> item.id
                    else -> -1L
                }
            )

            // 先移除监听器，避免 setChecked 触发回调污染 selectedPaths
            binding.checkBox.setOnCheckedChangeListener(null)
            binding.checkBox.isChecked = isSelected
            binding.checkBox.setOnCheckedChangeListener { _, _ ->
                val id = when (item) {
                    is Song -> item.id
                    else -> -1L
                }
                if (id != -1L) {
                    if (selectedSongIds.contains(id)) {
                        selectedSongIds.remove(id)
                    } else {
                        selectedSongIds.add(id)
                    }
                    notifyItemChanged(bindingAdapterPosition)
                }
                // 通知Fragment
                onCheckChangedListener?.let {
                    onCheckChangedListener!!.onCheckChanged()
                }
            }

            // 点击 item 只切换复选框状态，由复选框监听器同步数据，无需刷新整个 item
            itemView.setOnClickListener {
                binding.checkBox.isChecked = !binding.checkBox.isChecked
            }
        }

        private fun updateStartMarginRelativeToAlbumArt(view: View, gapPx: Float) {
            val params = view.layoutParams as ConstraintLayout.LayoutParams
            params.marginStart = gapPx.toInt()
            view.layoutParams = params
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<Any>() {
        override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
            return when {
                oldItem is Song && newItem is Song -> oldItem.id == newItem.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean {
            return oldItem == newItem
        }
    }
}
