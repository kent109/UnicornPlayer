package com.unicorn.player.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * 主界面 ViewPager2 适配器，承载 4 个标签页：
 * 0-歌曲 1-歌手 2-专辑 3-歌单
 */
class MainPagerAdapter(
    activity: FragmentActivity,
    private val titles: List<String>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> SongsFragment()
            1 -> ArtistFragment.newInstance()
            2 -> AlbumFragment.newInstance()
            3 -> com.unicorn.player.ui.PlaylistFragment.newInstance()
            else -> PlaceholderFragment.newInstance(titles.getOrElse(position) { "" })
        }
    }
}
