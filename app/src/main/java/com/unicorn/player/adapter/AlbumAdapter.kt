package com.unicorn.player.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.R
import com.unicorn.player.ThemeSettingActivity
import com.unicorn.player.databinding.ItemAlbumBinding
import com.unicorn.player.databinding.ItemAlbumHeaderBinding
import com.unicorn.player.model.Album

/**
 * 专辑列表适配器，展示按首字母分组的专辑。
 * 列表由「分组头 + 专辑行」交错构成，两种 viewType 共用一个扁平列表。
 */
class AlbumAdapter(
    private val listener: OnAlbumClickListener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<AlbumListItem> = emptyList()
    private var currentPlayingAlbum: String? = null

    fun setPlayingAlbum(albumName: String?) {
        currentPlayingAlbum = albumName
        notifyDataSetChanged()
    }

    fun getPlayingAlbum(): String? {
        return currentPlayingAlbum
    }

    interface OnAlbumClickListener {
        fun onAlbumClick(album: Album, position: Int)

        fun onAlbumLongClick(album: Album, position: Int)
    }

    /**
     * 提交分组后的扁平列表（Header、Item 按展示顺序排列）。
     */
    fun submitList(newItems: List<AlbumListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is AlbumListItem.Header -> TYPE_HEADER
        is AlbumListItem.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemAlbumHeaderBinding.inflate(inflater, parent, false))
        } else {
            AlbumHolder(ItemAlbumBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is AlbumListItem.Header -> (holder as HeaderHolder).bind(item.letter)
            is AlbumListItem.Item -> (holder as AlbumHolder).bind(item.album, position)
        }
    }

    inner class HeaderHolder(
        private val binding: ItemAlbumHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(letter: String) {
            binding.tvSectionLetter.text = letter
        }
    }

    inner class AlbumHolder(
        private val binding: ItemAlbumBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(album: Album, position: Int) {
            binding.apply {
                tvAlbumName.text = album.name
                tvAlbumSubtitle.text = "${album.artist} - ${album.songCount} 首"

                val isPlaying = album.name == currentPlayingAlbum
                if (isPlaying) {
                    val highlightColor = ThemeSettingActivity.resolveHighlightColor(
                        ivAlbumIcon.context
                    )
                    ivAlbumIcon.isSelected = true
                    ivAlbumIcon.setColorFilter(highlightColor)
                    tvAlbumName.setTextColor(highlightColor)
                    tvAlbumSubtitle.setTextColor(highlightColor)
                } else {
                    ivAlbumIcon.isSelected = false
                    ivAlbumIcon.clearColorFilter()
                    tvAlbumName.setTextColor(
                        ContextCompat.getColor(
                            tvAlbumName.context,
                            R.color.onSurface
                        )
                    )
                    tvAlbumSubtitle.setTextColor(
                        ContextCompat.getColor(
                            tvAlbumSubtitle.context,
                            R.color.onSurfaceVariant
                        )
                    )
                }

                root.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        listener.onAlbumClick(album, position)
                    }
                }

                root.setOnLongClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        listener.onAlbumLongClick(album, position)
                    }
                    true
                }
            }
        }
    }

    sealed class AlbumListItem {
        data class Header(val letter: String) : AlbumListItem()
        data class Item(val album: Album) : AlbumListItem()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }
}
