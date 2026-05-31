package com.unicorn.player

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.adapter.FragmentViewHolder
import com.unicorn.player.model.Song

class PlayerPagerAdapter(
    fragment: androidx.fragment.app.FragmentActivity,
    private var _songs: List<Song> = emptyList(),
    currentPosition: Int = 0
) : FragmentStateAdapter(fragment) {

    var currentPosition: Int = currentPosition
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    val songs: List<Song>
        get() = _songs

    fun updateSongs(newSongs: List<Song>, newPosition: Int = 0) {
        val oldSongs = _songs.toList()
        this._songs = newSongs.toList()
        this.currentPosition = newPosition

        // 比较新旧歌曲列表，只更新变化的项
        if (oldSongs.size == newSongs.size) {
            var hasChanges = false
            for (i in newSongs.indices) {
                if (oldSongs[i].id != newSongs[i].id) {
                    hasChanges = true
                    break
                }
            }
            if (hasChanges) {
                // 使用payloads更新，避免完整重建
                notifyItemRangeChanged(0, newSongs.size, "song_update")
            }
        } else {
            // 歌曲数量变化，需要完全重建
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int = _songs.size

    override fun createFragment(position: Int): Fragment {
        return PlayerFragment.newInstance(_songs[position])
    }

    fun getSongAt(position: Int): Song? {
        return if (position in _songs.indices) _songs[position] else null
    }

    override fun getItemId(position: Int): Long {
        // 使用歌曲ID作为stableId，避免因位置变化导致Fragment重建
        return if (position in _songs.indices) _songs[position].id else position.toLong()
    }

    override fun containsItem(itemId: Long): Boolean {
        return if (_songs.isEmpty()) false else _songs.any { it.id == itemId }
    }

    override fun onBindViewHolder(holder: FragmentViewHolder, position: Int, payloads: MutableList<Any>) {
        super.onBindViewHolder(holder, position, payloads)
        // 当payloads不为空时，只更新必要的数据，避免完整重建
        if (payloads.isNotEmpty()) {
            val fragment = holder.itemView.tag as? PlayerFragment
            val song = _songs[position]
            fragment?.updateCurrentSong(song)
        }
    }
}