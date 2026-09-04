package com.unicorn.player.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.AlbumSongsActivity
import com.unicorn.player.MainActivity
import com.unicorn.player.adapter.AlbumAdapter
import com.unicorn.player.databinding.FragmentAlbumBinding
import com.unicorn.player.model.Album
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.ui.SongsFragment.SongListHost
import com.unicorn.player.util.PlaylistHelper
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory
import com.unicorn.player.viewmodel.PlaylistViewModel
import com.unicorn.player.viewmodel.PlaylistViewModelFactory

/**
 * 专辑标签页 Fragment，按专辑名拼音首字母分组展示专辑列表。
 * 中文与英文同字母归为一组（啊、abc → A），非中英文开头统一归入 "#"。
 * 每个分组项左侧 ic_album 图标，标题为专辑名，副标题为 "$歌手名 - $count 首"。
 */
class AlbumFragment : Fragment(), AlbumAdapter.OnAlbumClickListener {

    private var _binding: FragmentAlbumBinding? = null
    private val binding get() = _binding!!

    private var host: SongListHost? = null

    private lateinit var viewModel: MusicViewModel

    private lateinit var playlistViewModel: PlaylistViewModel

    private lateinit var playlistSongsLauncher: ActivityResultLauncher<Intent>
    private lateinit var albumAdapter: AlbumAdapter

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
        setupPlaylistSongsLauncher()
    }

    override fun onResume() {
        super.onResume()
        // 切换回此 Fragment 时同步 waveSideBar 可见性，避免无数据时仍显示
        val albums = viewModel.albums.value
        setWaveSideBarVisible(!albums.isNullOrEmpty())
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
        val repository = MusicRepository(requireContext())
        val factory = MusicViewModelFactory(repository, requireContext())
        viewModel = ViewModelProvider(requireActivity(), factory)[MusicViewModel::class.java]

        val playlistFactory = PlaylistViewModelFactory(repository, requireActivity().application)
        playlistViewModel =
            ViewModelProvider(requireActivity(), playlistFactory)[PlaylistViewModel::class.java]

        // 观察 ViewModel 后台预计算好的专辑列表（已分组、已排序），主线程仅做轻量扁平化
        viewModel.albums.observe(viewLifecycleOwner) { albums ->
            submitAlbumItems(albums)
            // 空列表时显示空数据提示，隐藏侧边栏；有数据时恢复侧边栏
            if (albums.isNullOrEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                setWaveSideBarVisible(false)
            } else {
                binding.emptyView.visibility = View.GONE
                setWaveSideBarVisible(true)
            }
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

    private fun setupPlaylistSongsLauncher() {
        playlistSongsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // 歌单内歌曲有改动 → 主动刷新列表（更新歌曲数量、更新时间）
                playlistViewModel.refreshPlaylists()
            }
        }
    }

    /**
     * 绑定右侧字母导航栏，选中字母时滚动到对应的第一个分组头。
     */
    private fun setupWaveSideBar() {
        binding.waveSideBar.setIndexItems(
            "↑", "☆", "A", "B", "C", "D", "E", "F", "G", "H", "I",
            "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "#", "↓"
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
     * 将 ViewModel 预计算好的专辑列表扁平化为 Header + items，并记录每个首字母第一个 Header 的位置。
     * 拼音转换与分组排序已在 ViewModel 后台完成，此处仅做 O(n) 的轻量遍历，不阻塞主线程。
     */
    private fun submitAlbumItems(albums: List<Album>) {
        val items = mutableListOf<AlbumAdapter.AlbumListItem>()
        val indexMap = LinkedHashMap<String, Int>()

        // 列表已按首字母有序，遇到新字母即插入 Header
        var currentLetter: String? = null
        for (album in albums) {
            if (album.firstLetter != currentLetter) {
                currentLetter = album.firstLetter
                indexMap[currentLetter] = items.size
                items.add(AlbumAdapter.AlbumListItem.Header(currentLetter))
            }
            items.add(AlbumAdapter.AlbumListItem.Item(album))
        }
        letterIndexMap = indexMap

        albumAdapter.submitList(items)
        updateAlbumCount(albums.size)
    }

    private fun updateAlbumCount(count: Int) {
        binding.tvAlbumCount.text = "共 $count 张专辑"
    }

    override fun onAlbumClick(album: Album, position: Int) {
        // 使用 launcher 启动，以便在歌曲改动后（RESULT_OK）触发刷新
        playlistSongsLauncher.launch(
            AlbumSongsActivity.newIntent(requireContext(), album.name)
        )
    }

    override fun onAlbumLongClick(album: Album, position: Int) {
        val service = host?.musicService
        if (service != null) {
            val songIds = album.run {
                album.songList.map { it.id }.toSet()
            }
            PlaylistHelper.addToPlaylist(
                songIds, requireActivity() as AppCompatActivity, viewModel, service
            )
        } else {
            Toast.makeText(context, "音乐服务没有运行", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = context as? SongListHost
            ?: throw IllegalStateException("Host activity must implement SongListHost")
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
        val sideBar = binding.waveSideBar
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
