package com.unicorn.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.R
import com.unicorn.player.databinding.ItemMultiChoiceBinding
import com.unicorn.player.model.Album
import com.unicorn.player.model.Artist
import com.unicorn.player.model.Song
import com.unicorn.player.viewmodel.PlaylistViewModel.PlaylistInfo

class MultiChoiceFragmentAdapter(
    private var selectedIds: MutableSet<Long>,
    private var selectedSongIds: MutableSet<Long>,
    private var fragmentType: Int = 0
) : ListAdapter<Any, MultiChoiceFragmentAdapter.ViewHolder>(DiffCallback()) {

    companion object {
        private const val CASSETTE_ART_GAP_DP = 12
        private const val DISC_ART_GAP_DP = 16
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
        val binding = ItemMultiChoiceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), isCassetteMode)
    }

    inner class ViewHolder(
        private val binding: ItemMultiChoiceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Any, isCassetteMode: Boolean) {
            when (item) {
                is Song -> {
                    binding.ivIcon.imageTintList = null
                    val density = binding.root.resources.displayMetrics.density
                    val widthPx = ((if (isCassetteMode) 64 else 48) * density).toInt()
                    val heightPx = ((if (isCassetteMode) 42 else 48) * density).toInt()
                    val params = binding.ivIcon.layoutParams
                    params.width = widthPx
                    params.height = heightPx
                    binding.ivIcon.layoutParams = params
                    if (isCassetteMode) {
                        binding.ivIcon.setImageResource(R.drawable.ic_cassette_photo)
                    } else {
                        binding.ivIcon.setImageResource(R.drawable.ic_disc)
                    }
                    val gapPx = (if (isCassetteMode) CASSETTE_ART_GAP_DP else DISC_ART_GAP_DP) * density
                    updateStartMarginRelativeToAlbumArt(binding.tvTitle, gapPx)
                    updateStartMarginRelativeToAlbumArt(binding.subtitleLayout, gapPx)
                    binding.tvTitle.text = item.title
                    binding.tvSubtitle.text = item.artist
                    binding.tvSubtitle2.visibility = View.VISIBLE
                    binding.tvSubtitle2.text = item.album
                }

                is Artist -> {
                    val params = binding.ivIcon.layoutParams
                    params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    binding.ivIcon.layoutParams = params
                    binding.ivIcon.setImageResource(R.drawable.ic_artist)
                    binding.tvTitle.text = item.name
                    binding.tvSubtitle.text = "${item.songCount} 首"
                }

                is Album -> {
                    val params = binding.ivIcon.layoutParams
                    params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    binding.ivIcon.layoutParams = params
                    binding.ivIcon.setImageResource(R.drawable.ic_album)
                    binding.tvTitle.text = item.name
                    binding.tvSubtitle.text = "${item.artist} - ${"${item.songCount} 首"}"
                }

                is PlaylistInfo -> {
                    val params = binding.ivIcon.layoutParams
                    params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    binding.ivIcon.layoutParams = params
                    binding.ivIcon.setImageResource(R.drawable.ic_playlist)
                    binding.tvTitle.text = item.name
                    binding.tvSubtitle.text = "${item.songCount} 首"
                }
            }

            val isSelected = selectedIds.contains(
                when (item) {
                    is Song -> item.id
                    is Album -> item.name.hashCode().toLong()
                    is PlaylistInfo -> item.id
                    is Artist -> item.name.hashCode().toLong()
                    else -> -1L
                }
            )

            val songIds = getSongIds(item)
            if (isSelected) {
                selectedSongIds.addAll(songIds)
            } else {
                selectedSongIds.removeAll(songIds)
            }

            // 先移除监听器，避免 setChecked 触发回调污染 selectedPaths
            binding.checkBox.setOnCheckedChangeListener(null)
            binding.checkBox.isChecked = isSelected
            binding.checkBox.setOnCheckedChangeListener { _, _ ->
                val id = when (item) {
                    is Song -> item.id
                    is Album -> item.name.hashCode().toLong()
                    is PlaylistInfo -> item.id
                    is Artist -> item.name.hashCode().toLong()
                    else -> -1L
                }
                if (id != -1L) {
                    val songIds = getSongIds(item)
                    if (selectedIds.contains(id)) {
                        selectedIds.remove(id)
                        selectedSongIds.removeAll(songIds)
                    } else {
                        selectedIds.add(id)
                        selectedSongIds.addAll(songIds)
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

    private fun getSongIds(item: Any): Set<Long> {
        return when (item) {
            is Song -> mutableSetOf(item.id)
            is Album -> item.songList.mapTo(HashSet()) { it.id }
            is PlaylistInfo -> mutableSetOf(item.id)
            is Artist -> item.songList.mapTo(HashSet()) { it.id }
            else -> mutableSetOf()
        }
    }

    private class DiffCallback : androidx.recyclerview.widget.DiffUtil.ItemCallback<Any>() {
        override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
            return when {
                oldItem is Song && newItem is Song -> oldItem.id == newItem.id
                oldItem is Artist && newItem is Artist -> oldItem.name.hashCode()
                    .toLong() == newItem.name.hashCode().toLong()

                oldItem is Album && newItem is Album -> oldItem.name.hashCode()
                    .toLong() == newItem.name.hashCode().toLong()

                oldItem is PlaylistInfo && newItem is PlaylistInfo -> oldItem.id == newItem.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean {
            return oldItem == newItem
        }
    }
}
