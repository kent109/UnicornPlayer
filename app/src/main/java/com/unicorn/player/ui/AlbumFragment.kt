package com.unicorn.player.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.AlbumSongsActivity
import com.unicorn.player.MainActivity
import com.unicorn.player.adapter.AlbumAdapter
import com.unicorn.player.databinding.FragmentAlbumBinding
import com.unicorn.player.model.Album
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.util.PinyinUtil
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory
import java.text.Collator
import java.util.Locale

/**
 * 专辑标签页 Fragment，按专辑名拼音首字母分组展示专辑列表。
 * 中文与英文同字母归为一组（啊、abc → A），非中英文开头统一归入 "#"。
 * 每个分组项左侧 ic_album 图标，标题为专辑名，副标题为 "$歌手名 - $count 首"。
 */
class AlbumFragment : Fragment(), AlbumAdapter.OnAlbumClickListener {

    private var _binding: FragmentAlbumBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MusicViewModel
    private lateinit var albumAdapter: AlbumAdapter

    private val collator = Collator.getInstance(Locale.CHINA)

    /** 延迟恢复 ViewPager 的主线程 Handler，避免在字母选择回调里直接恢复导致手势冲突 */
    private val handler = Handler(Looper.getMainLooper())
    private var restoreViewPagerRunnable: Runnable? = null

    /** 每个首字母对应的第一个 Header 在扁平列表中的位置，用于侧边栏快速定位 */
    private var letterIndexMap: Map<String, Int> = emptyMap()

    companion object {
        fun newInstance() = AlbumFragment()

        /** 字母选择回调停止多久后恢复 ViewPager，太短易频繁切换，太长则松手后响应迟钝 */
        private const val RESTORE_VIEWPAGER_DELAY_MS = 120L

        /** waveSideBar 淡入/淡出时长（ms），避免突兀显示 */
        private const val ALPHA_ANIM_DURATION_SHOW_MS = 500L

        /** waveSideBar 淡入/淡出时长（ms），避免突兀隐藏 */
        private const val ALPHA_ANIM_DURATION_HIDE_MS = 120L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlbumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModel()
        setupRecyclerView()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 必须移除待执行的回调，否则 Handler 持有旧 Fragment 引用，造成内存泄漏
        restoreViewPagerRunnable?.let { handler.removeCallbacks(it) }
        restoreViewPagerRunnable = null
        binding.recyclerView.adapter = null
        _binding = null
    }

    private fun setupViewModel() {
        val factory = MusicViewModelFactory(MusicRepository(requireContext()), requireContext())
        viewModel = ViewModelProvider(requireActivity(), factory)[MusicViewModel::class.java]

        viewModel.allSongs.observe(viewLifecycleOwner) { songs ->
            submitAlbums(songs)
        }
    }

    private fun setupRecyclerView() {
        albumAdapter = AlbumAdapter(this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = albumAdapter
        }
        setupScrollListener()
        setupWaveSideBar()
    }

    /**
     * 绑定右侧字母导航栏，选中字母时滚动到对应的第一个分组头。
     */
    private fun setupWaveSideBar() {
        binding.waveSideBar.setIndexItems(
            "↑", "☆", "A", "B", "C", "D", "E", "F", "G", "H", "I",
            "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "#"
        )
        binding.waveSideBar.setOnSelectIndexItemListener { letter ->
            // 手指正在 WaveSideBar 上检索字母（每次字母变化都会回调），此时禁用 ViewPager
            setViewPagerSwipe(false)
            // 重置延迟恢复：若一定时间内再无新回调，说明手指已松开，恢复 ViewPager
            restoreViewPagerRunnable?.let { handler.removeCallbacks(it) }
            restoreViewPagerRunnable = Runnable { setViewPagerSwipe(true) }.also {
                handler.postDelayed(it, RESTORE_VIEWPAGER_DELAY_MS)
            }
            val position = letterIndexMap[letter] ?: return@setOnSelectIndexItemListener
            (binding.recyclerView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(position, 0)
        }
    }

    /**
     * 开关主界面 ViewPager2 的用户滑动。
     * 手指在 WaveSideBar 上滑动时禁用，避免横向分页手势与字母导航手势冲突。
     */
    private fun setViewPagerSwipe(enabled: Boolean) {
        (activity as? MainActivity)?.setViewPagerSwipeEnabled(enabled)
    }

    /**
     * 监听滚动，滚到底部时显示专辑数量（参考 ArtistFragment）。
     */
    private fun setupScrollListener() {
        binding.recyclerView.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()

                val isAtBottom = if (lastVisibleItem == totalItemCount - 1) {
                    val lastItemView = layoutManager.findViewByPosition(lastVisibleItem)
                    lastItemView != null && lastItemView.bottom <= recyclerView.bottom
                } else {
                    false
                }

                binding.tvAlbumCount.visibility = if (isAtBottom) View.VISIBLE else View.GONE
            }
        })
    }

    /**
     * 按专辑名分组统计歌曲数量，按拼音首字母分组排序后提交。
     */
    private fun submitAlbums(songs: List<Song>) {
        // 以 lowerCase 专辑名作为分组键，保留首次出现的原始大小写用于显示
        val displayCase = LinkedHashMap<String, String>()
        // 每组维护：歌曲数 与 各歌手出现次数（用于取数量最多的歌手作为副标题）
        val counts = LinkedHashMap<String, Int>()
        val artistCounts = LinkedHashMap<String, LinkedHashMap<String, Int>>()

        for (song in songs) {
            val key = song.album.lowercase()
            if (!displayCase.containsKey(key)) {
                displayCase[key] = song.album
            }
            counts[key] = (counts[key] ?: 0) + 1
            val groupArtists = artistCounts.getOrPut(key) { LinkedHashMap() }
            groupArtists[song.artist] = (groupArtists[song.artist] ?: 0) + 1
        }

        val albums = counts.map { (key, count) ->
            val topArtist = artistCounts[key]?.maxByOrNull { it.value }?.key ?: ""
            Album(displayCase[key] ?: key, topArtist, count)
        }

        // 按拼音首字母分组：字母 A-Z 顺序，"#" 置于末尾
        val grouped = LinkedHashMap<String, MutableList<Album>>()
        for (album in albums) {
            val letter = PinyinUtil.getPinyinFirstLetter(album.name)
            grouped.getOrPut(letter) { mutableListOf() }.add(album)
        }

        // 组内按专辑名排序（中文按拼音、英文不区分大小写）
        for ((_, list) in grouped) {
            list.sortWith(compareBy(collator) { it.name.lowercase() })
        }

        val orderedLetters = grouped.keys.sortedWith { a, b ->
            when {
                a == "#" -> 1
                b == "#" -> -1
                else -> a.compareTo(b)
            }
        }

        // 扁平化为 header + items 列表，并记录每个首字母第一个 Header 的位置
        val items = mutableListOf<AlbumAdapter.AlbumListItem>()
        val indexMap = LinkedHashMap<String, Int>()
        for (letter in orderedLetters) {
            indexMap[letter] = items.size
            items.add(AlbumAdapter.AlbumListItem.Header(letter))
            grouped[letter]?.forEach { album ->
                items.add(AlbumAdapter.AlbumListItem.Item(album))
            }
        }
        letterIndexMap = indexMap

        albumAdapter.submitList(items)
        updateAlbumCount(albums.size)
    }

    private fun updateAlbumCount(count: Int) {
        binding.tvAlbumCount.text = "共 $count 张专辑"
    }

    override fun onAlbumClick(album: Album, position: Int) {
        startActivity(AlbumSongsActivity.newIntent(requireContext(), album.name))
    }

    /**
     * 控制 waveSideBar 可见性，带淡入/淡出动画避免突兀显隐。
     * ViewPager2 切换动画过程中隐藏，避免侧边栏错位；静止后才重新显示。
     */
    /**
     * 双击搜索框时滚动到顶部（由宿主 Activity 调用）
     */
    fun scrollToTop() {
        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager
        if (layoutManager != null && layoutManager.itemCount > 0) {
            binding.recyclerView.smoothScrollToPosition(0)
        }
    }

    fun setWaveSideBarVisible(visible: Boolean) {
        val sideBar = binding?.waveSideBar ?: return
        // 取消进行中的动画，避免显隐快速切换时互相覆盖
        sideBar.animate().cancel()
        if (visible) {
            // 先可见再以淡入动画显现
            sideBar.visibility = View.VISIBLE
            sideBar.alpha = 0f
            sideBar.animate().alpha(1f).setDuration(ALPHA_ANIM_DURATION_SHOW_MS).start()
        } else {
            // 淡出动画结束后再设为 GONE
            sideBar.animate().alpha(0f).setDuration(ALPHA_ANIM_DURATION_HIDE_MS).withEndAction {
                sideBar.visibility = View.GONE
            }.start()
        }
    }
}
