package com.unicorn.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private var fragmentType: Int = 0
) : ListAdapter<Any, MultiChoiceFragmentAdapter.ViewHolder>(DiffCallback()) {

    interface OnCheckChangedListener {
        fun onCheckChanged(selectedIds: Set<Long>)
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
        holder.bind(getItem(position))
    }

    fun selectAll() {
        val currentList = currentList
        for (item in currentList) {
            when (item) {
                is Song -> selectedIds.add(item.id)
                is Album -> selectedIds.add(item.name.hashCode().toLong())
                is PlaylistInfo -> selectedIds.add(item.id)
                is Artist -> selectedIds.add(item.name.hashCode().toLong())
            }
        }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
    }

    fun getSelectedIds(): Set<Long> {
        return selectedIds
    }

    fun hasSelection(): Boolean {
        return selectedIds.isNotEmpty()
    }

    inner class ViewHolder(
        private val binding: ItemMultiChoiceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Any) {
            when (item) {
                is Song -> {
                    binding.ivIcon.imageTintList = null
                    binding.tvTitle.text = item.title
                    binding.tvSubtitle.text = item.artist
                    binding.tvSubtitle2.visibility = View.VISIBLE
                    binding.tvSubtitle2.text = item.album
                }

                is Artist -> {
                    binding.ivIcon.setImageResource(R.drawable.ic_artist)
                    binding.tvTitle.text = item.name
                    binding.tvSubtitle.text = "${item.songCount} 首"
                }

                is Album -> {
                    binding.ivIcon.setImageResource(R.drawable.ic_album)
                    binding.tvTitle.text = item.name
                    binding.tvSubtitle.text = "${item.artist} - ${"${item.songCount} 首"}"
                }

                is PlaylistInfo -> {
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

            // 先移除监听器，避免 setChecked 触发回调污染 selectedPaths
            binding.checkBox.setOnCheckedChangeListener(null)
            binding.checkBox.isChecked = isSelected
            binding.checkBox.setOnCheckedChangeListener { _, isChecked ->
                val id = when (item) {
                    is Song -> item.id
                    is Album -> item.name.hashCode().toLong()
                    is PlaylistInfo -> item.id
                    is Artist -> item.name.hashCode().toLong()
                    else -> -1L
                }
                if (id != -1L) {
                    if (selectedIds.contains(id)) {
                        selectedIds.remove(id)
                    } else {
                        selectedIds.add(id)
                    }
                    notifyItemChanged(bindingAdapterPosition)
                }
                // 通知Fragment
                onCheckChangedListener?.let {
                    onCheckChangedListener!!.onCheckChanged(selectedIds)
                }
            }

            // 点击 item 只切换复选框状态，由复选框监听器同步数据，无需刷新整个 item
            itemView.setOnClickListener {
                binding.checkBox.isChecked = !binding.checkBox.isChecked
            }
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
