package com.unicorn.player.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.R
import com.unicorn.player.adapter.SongAdapter
import com.unicorn.player.databinding.FragmentSongsBinding
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory
import com.unicorn.player.widget.CustomRadarHeader

/**
 * 歌曲标签页 Fragment，承载歌曲列表、下拉刷新、滚动定位等功能。
 * 通过 SongListHost 接口将歌曲点击/更多操作委托给宿主 Activity。
 */
class SongsFragment : Fragment(), SongAdapter.OnSongClickListener,
    SongAdapter.OnSongMoreClickListener {

    private var _binding: FragmentSongsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MusicViewModel
    private lateinit var songAdapter: SongAdapter

    private var host: SongListHost? = null

    // 用于移除观察者
    private var currentSongObserver: Observer<Song?>? = null
    private var isPlayingObserver: Observer<Boolean>? = null

    // 扫描相关状态
    private var isScanning = false
    private var savedCurrentSongId: Long? = null
    private var savedIsPlaying = false

    private val hideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hideRunnable: Runnable = Runnable {
        binding.btnScrollToCurrent.visibility = View.GONE
    }
    private var scrollToContentClick = false

    companion object {
        const val TAG = "SongsFragment"
    }

    /**
     * 宿主 Activity 需实现的接口，用于处理歌曲交互
     */
    interface SongListHost {
        val musicService: MusicService?
        fun onSongClick(song: Song, position: Int)
        fun onMoreClick(song: Song, position: Int)
    }

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        host = context as? SongListHost
            ?: throw IllegalStateException("Host activity must implement SongListHost")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSongsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViewModel()
        setupRecyclerView()
        setupSmartRefreshLayout()
        setupScrollStateListener()
    }

    override fun onResume() {
        super.onResume()
        observeMusicService()
        val service = host?.musicService
        if (service != null) {
            songAdapter.isPlaying = service.isPlaying.value == true
            songAdapter.currentPlayingSong = service.currentSong.value
            songAdapter.isPaused = false
            songAdapter.resumeCurrentSongAnimation()
        }
    }

    override fun onPause() {
        super.onPause()
        host?.musicService?.savePlaybackState()
        songAdapter.isPaused = true
        songAdapter.pauseCurrentSongAnimation()
        removeMusicServiceObservers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        songAdapter.stopCurrentSongAnimation()
        recyclerView = null
        _binding = null
    }

    override fun onDetach() {
        super.onDetach()
        host = null
    }

    private var recyclerView: androidx.recyclerview.widget.RecyclerView? = null

    /**
     * 启动时尝试观察 MusicService 的播放状态（服务可能尚未绑定）
     */
    private fun observeMusicService() {
        val service = host?.musicService ?: return

        // 先移除旧的观察者，避免重复注册
        removeMusicServiceObservers()

        currentSongObserver = Observer { song ->
            song?.let {
                songAdapter.currentPlayingSong = it
                songAdapter.notifyDataSetChanged()
            }
        }
        isPlayingObserver = Observer { isPlaying ->
            songAdapter.isPlaying = isPlaying
            if (isPlaying) {
                songAdapter.resumeCurrentSongAnimation()
            } else {
                songAdapter.pauseCurrentSongAnimation()
            }
        }
        service.currentSong.observe(viewLifecycleOwner, currentSongObserver!!)
        service.isPlaying.observe(viewLifecycleOwner, isPlayingObserver!!)
    }

    /**
     * 服务连接后由宿主调用，重新注册观察者
     */
    fun onServiceConnected() {
        if (_binding != null) {
            observeMusicService()
        }
    }

    /**
     * 服务断开后由宿主调用，移除观察者
     */
    fun onServiceDisconnected() {
        removeMusicServiceObservers()
    }

    private fun removeMusicServiceObservers() {
        val service = host?.musicService
        currentSongObserver?.let { service?.currentSong?.removeObserver(it) }
        isPlayingObserver?.let { service?.isPlaying?.removeObserver(it) }
        currentSongObserver = null
        isPlayingObserver = null
    }

    private fun setupViewModel() {
        val factory = MusicViewModelFactory(MusicRepository(requireContext()), requireContext())
        viewModel = ViewModelProvider(requireActivity(), factory)[MusicViewModel::class.java]

        viewModel.allSongs.observe(viewLifecycleOwner) { songs ->
            songAdapter.submitList(songs)
            updateSongCount(songs.size)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isScanning) {
                // 下拉刷新（扫描）期间不显示屏幕中央的 loading 图标；
                // 扫描完成（isLoading 从 true 跳变到 false）时收尾，
                // 而非在 allSongs 变化时触发，避免 clearHiddenSongs 先行误判扫描结束。
                binding.progressBar.visibility = View.GONE
                if (!isLoading) {
                    onScanComplete()
                }
            } else {
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(this, this)
        recyclerView = binding.recyclerView
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = songAdapter
            songAdapter.setRecyclerView(this@apply)
        }
    }

    private fun updateSongCount(count: Int) {
        binding.tvSongCount.text = "共 $count 首歌曲"
    }

    private fun setupSmartRefreshLayout() {
        val smartRefreshLayout = binding.smartRefreshLayout

        val radarHeader = CustomRadarHeader(requireContext())
        radarHeader.setPrimaryColorId(R.color.background)
        radarHeader.setAccentColorId(R.color.icon_color)
        smartRefreshLayout.setRefreshHeader(radarHeader)

        smartRefreshLayout.setHeaderHeight(100f)
        smartRefreshLayout.setEnableOverScrollBounce(true)
        smartRefreshLayout.setEnableRefresh(true)
        smartRefreshLayout.setEnableOverScrollDrag(true)

        smartRefreshLayout.setOnRefreshListener {
            Log.d(TAG, "下拉刷新触发, isScanning=$isScanning")
            if (isScanning) {
                Log.d(TAG, "正在扫描中，忽略本次刷新")
                smartRefreshLayout.finishRefresh(0)
                return@setOnRefreshListener
            }
            // 保存当前播放状态
            savedCurrentSongId = host?.musicService?.currentSong?.value?.id
            savedIsPlaying = host?.musicService?.isPlaying?.value == true
            isScanning = true
            // 立即隐藏中间进度条，避免下拉刷新时显示
            binding.progressBar.visibility = View.GONE
            viewModel.loadMusic(force = true)
        }
    }

    private fun setupScrollStateListener() {
        var isScrolling = false

        binding.recyclerView.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                newState: Int
            ) {
                when (newState) {
                    androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING -> {
                        isScrolling = true
                        binding.btnScrollToCurrent.visibility = View.GONE
                        hideRunnable.let { hideHandler.removeCallbacks(it) }
                    }

                    androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE -> {
                        isScrolling = false
                        if (!scrollToContentClick) {
                            binding.btnScrollToCurrent.visibility = View.VISIBLE
                            hideRunnable.let { hideHandler.removeCallbacks(it) }
                            hideHandler.postDelayed(hideRunnable, 1000)
                        } else {
                            binding.btnScrollToCurrent.visibility = View.GONE
                        }
                        scrollToContentClick = false
                    }
                }
            }

            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
                if (isScrolling) {
                    binding.btnScrollToCurrent.visibility = View.GONE
                }

                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                if (layoutManager != null) {
                    val totalItemCount = layoutManager.itemCount
                    val lastVisibleItem = layoutManager.findLastVisibleItemPosition()

                    val isAtBottom = if (lastVisibleItem == totalItemCount - 1) {
                        val lastItemView = layoutManager.findViewByPosition(lastVisibleItem)
                        lastItemView != null && lastItemView.bottom <= recyclerView.bottom
                    } else {
                        false
                    }

                    binding.tvSongCount.visibility =
                        if (isAtBottom) View.VISIBLE else View.GONE
                }
            }
        })

        binding.btnScrollToCurrent.setOnClickListener {
            scrollToContentClick = true
            hideRunnable.let { hideHandler.removeCallbacks(it) }
            scrollToCurrentlyPlayingSong()
        }
    }

    private fun scrollToCurrentlyPlayingSong() {
        val currentSong = host?.musicService?.currentSong?.value ?: return
        val allSongs = viewModel.allSongs.value ?: return

        val position = allSongs.indexOfFirst { it.id == currentSong.id }
        if (position != -1) {
            binding.btnScrollToCurrent.visibility = View.GONE
            binding.recyclerView.smoothScrollToPosition(position)

            hideHandler.postDelayed({
                scrollToContentClick = false
            }, 500)
        }
    }

    private fun onScanComplete() {
        isScanning = false

        val currentSong = host?.musicService?.currentSong?.value
        if (currentSong != null && currentSong.id != savedCurrentSongId) {
            if (savedIsPlaying) {
                host?.musicService?.pause()
            }
        }

        binding.smartRefreshLayout.finishRefresh(500)
        Log.d(TAG, "扫描完成，列表已更新")
    }

    /**
     * 双击搜索框时滚动到顶部（由宿主 Activity 调用）
     */
    fun scrollToTop() {
        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager
        if (layoutManager != null && layoutManager.itemCount > 0) {
            binding.recyclerView.smoothScrollToPosition(0)
        }
    }

    // 实现 OnSongClickListener 接口：委托给宿主 Activity
    override fun onSongClick(song: Song, position: Int) {
        host?.onSongClick(song, position)
    }

    // 实现 OnSongMoreClickListener 接口：委托给宿主 Activity
    override fun onMoreClick(song: Song, position: Int) {
        host?.onMoreClick(song, position)
    }
}
