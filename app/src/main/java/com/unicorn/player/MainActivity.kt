package com.unicorn.player

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.unicorn.player.adapter.SongAdapter
import com.unicorn.player.databinding.ActivityMainBinding
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory

class MainActivity : AppCompatActivity(), SongAdapter.OnSongClickListener,
    SongAdapter.OnSongMoreClickListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MusicViewModel
    private lateinit var songAdapter: SongAdapter
    private lateinit var songInfoHelper: SongInfoHelper

    // 歌曲数量 TextView
    private lateinit var tvSongCount: android.widget.TextView

    private var musicService: MusicService? = null
    private var isServiceBound = false

    private val hideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hideRunnable: Runnable = Runnable {
        binding.btnScrollToCurrent.visibility = android.view.View.GONE
    }

    // 将 scrollToContentClick 提升为类级别变量，解决作用域问题
    private var scrollToContentClick = false

    // 标记是否正在扫描音乐库
    private var isScanning = false

    companion object {
        const val TAG = "MainActivity"
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadMusic()
        } else {
            Toast.makeText(this, "需要存储权限才能访问音乐文件", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "通知权限被拒绝，通知功能可能受限", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // 检查 Activity 是否已销毁，避免在销毁后操作 UI 导致 crash
            if (isDestroyed || isFinishing) return

            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isServiceBound = true
            setupBottomPlayerObservers()  // 服务连接成功后设置观察者
            updateBottomPlayerUI()  // 立即更新UI状态
            // 服务连接后，将 MusicService 当前进度同步到 DataStore
            // 确保保存的进度与实际进度一致，避免恢复时跳转到过时的位置
            musicService?.syncCurrentPositionToDataStore()
            // 服务连接后同步排序后的歌曲列表，确保播放顺序与UI一致
            if (viewModel.fullSongs.value?.isNotEmpty() == true) {
                updateServiceSongList()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isServiceBound = false
            // 清理观察者，避免内存泄漏
            removeBottomPlayerObservers()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化歌曲信息帮助类
        songInfoHelper = SongInfoHelper(this)
        songInfoHelper.onDeleteListener = object : SongInfoHelper.OnDeleteListener {
            override fun onDelete(song: Song) {
                // 从完整列表中删除歌曲
                viewModel.fullSongs.value?.let { songs ->
                    val updatedSongs = songs.filter { it.id != song.id }
                    // 通知 ViewModel 更新列表
                    viewModel.updateSongs(updatedSongs)
                }
            }
        }

        setupViewModel()
        setupRecyclerView()
        setupSmartRefreshLayout()
        setupSearchView()
        setupBottomPlayer()

        checkPermissions()
        bindMusicService()
    }

    private var lastSearchTapTime = 0L
    private val searchViewRect = android.graphics.Rect()

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_UP) {
            // 获取SearchView在屏幕上的区域
            binding.searchView.getHitRect(searchViewRect)
            // 将触摸事件坐标转换到SearchView的父坐标系
            val location = IntArray(2)
            binding.searchView.getLocationOnScreen(location)
            val x = ev.rawX.toInt()
            val y = ev.rawY.toInt()
            if (searchViewRect.contains(
                    x - location[0] + searchViewRect.left, y - location[1] + searchViewRect.top
                )
            ) {
                // 点击在SearchView范围内
                if (binding.searchView.isIconified) {
                    val currentTime = System.currentTimeMillis()
                    val doubleTapTimeout = android.view.ViewConfiguration.getDoubleTapTimeout()
                    if (currentTime - lastSearchTapTime < doubleTapTimeout) {
                        // 双击检测
                        val layoutManager =
                            binding.recyclerView.layoutManager as? LinearLayoutManager
                        if (layoutManager != null && layoutManager.itemCount > 0) {
                            binding.recyclerView.smoothScrollToPosition(0)
                        }
                        lastSearchTapTime = 0L
                    } else {
                        lastSearchTapTime = currentTime
                    }
                } else {
                    lastSearchTapTime = 0L
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupViewModel() {
        val repository = MusicRepository(this)
        val factory = MusicViewModelFactory(repository, this)
        viewModel = ViewModelProvider(this, factory)[MusicViewModel::class.java]

        // Observe LiveData after ViewModel is ready
        viewModel.allSongs.observe(this) { songs ->
            if (::songAdapter.isInitialized) {
                songAdapter.submitList(songs)
            }
            // 更新歌曲数量
            updateSongCount(songs.size)
            // 扫描完成后更新列表
            if (isScanning) {
                onScanComplete()
            }
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility =
                if (isLoading) android.view.View.VISIBLE else android.view.View.GONE
        }

        // 监听完整歌曲列表变化，当服务已绑定时自动同步排序后的列表到 MusicService
        // 这确保应用重启后，MusicService 的播放顺序与 UI 显示的排序一致
        viewModel.fullSongs.observe(this) { songs ->
            if (songs.isNotEmpty() && isServiceBound) {
                updateServiceSongList()
            }
        }
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(this, this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = songAdapter
            // 设置 RecyclerView 引用，以便 Adapter 能够找到 ViewHolder
            songAdapter.setRecyclerView(this@apply)
        }

        // 初始化歌曲数量 TextView
        tvSongCount = binding.tvSongCount

        // 初始化滚动状态监听
        setupScrollStateListener()
    }

    // 更新歌曲数量显示
    private fun updateSongCount(count: Int) {
        tvSongCount.text = "共 $count 首歌曲"
    }

    private fun setupSmartRefreshLayout() {
        val smartRefreshLayout = binding.smartRefreshLayout

        // 创建二级刷新头（TwoLevelHeader）
        val twoLevelHeader = com.scwang.smart.refresh.header.TwoLevelHeader(this)
        smartRefreshLayout.setRefreshHeader(twoLevelHeader)

        // 配置参数
        smartRefreshLayout.setHeaderHeight(120f) // Header 高度 120dp
        smartRefreshLayout.setEnableOverScrollBounce(true) // 启用回弹效果
        smartRefreshLayout.setEnableRefresh(true) // 启用下拉刷新
        smartRefreshLayout.setEnableOverScrollDrag(true) // 启用拖拽效果

        // 设置下拉刷新监听
        smartRefreshLayout.setOnRefreshListener {
            Log.d(TAG, "下拉刷新触发, isScanning=$isScanning")
            // 如果正在扫描，忽略本次刷新
            if (isScanning) {
                Log.d(TAG, "正在扫描中，忽略本次刷新")
                smartRefreshLayout.finishRefresh(0)
                return@setOnRefreshListener
            }
            // 保存当前播放状态
            savedCurrentSongId = musicService?.currentSong?.value?.id
            savedIsPlaying = musicService?.isPlaying?.value == true
            // 开始扫描
            isScanning = true
            loadMusic()
        }

        // 设置二级刷新监听
        twoLevelHeader.setOnTwoLevelListener {
            Log.d(TAG, "二级刷新触发")
            // 这里可以添加更多数据加载逻辑
            true // 返回true表示处理完成
        }
    }

    private fun setupScrollStateListener() {
        var isScrolling = false

        // 监听滚动状态
        binding.recyclerView.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(
                recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int
            ) {
                when (newState) {
                    // 开始滚动
                    androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING -> {
                        Log.d(TAG, "SCROLL_STATE_DRAGGING")
                        isScrolling = true
                        // 滑动过程中隐藏定位按钮
                        binding.btnScrollToCurrent.visibility = android.view.View.GONE
                        // 移除延迟消息
                        hideRunnable.let { hideHandler.removeCallbacks(it) }
                    }
                    // 停止滚动
                    androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE -> {
                        Log.d(TAG, "SCROLL_STATE_IDLE, scrollToContentClick=$scrollToContentClick")
                        isScrolling = false
                        if (!scrollToContentClick) {
                            binding.btnScrollToCurrent.visibility = android.view.View.VISIBLE
                            // 设置延迟隐藏
                            hideRunnable.let { hideHandler.removeCallbacks(it) }
                            hideHandler.postDelayed(hideRunnable, 1000)
                        } else {
                            binding.btnScrollToCurrent.visibility = android.view.View.GONE
                        }
                        scrollToContentClick = false;
                    }
                }
            }

            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int
            ) {
                Log.d(TAG, "onScrolled, isScrolling=$isScrolling, dy=$dy")
                // 滑动过程中保持按钮隐藏
                if (isScrolling) {
                    binding.btnScrollToCurrent.visibility = android.view.View.GONE
                }

                // 检测是否滚动到底部，显示或隐藏歌曲数量
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                if (layoutManager != null) {
                    val totalItemCount = layoutManager.itemCount
                    val lastVisibleItem = layoutManager.findLastVisibleItemPosition()

                    // 精确判断：最后一个 item 可见且其底部已经到达或超过 RecyclerView 底部
                    val isAtBottom = if (lastVisibleItem == totalItemCount - 1) {
                        val lastItemView = layoutManager.findViewByPosition(lastVisibleItem)
                        lastItemView != null && lastItemView.bottom <= recyclerView.bottom
                    } else {
                        false
                    }

                    // 在底部显示歌曲数量，不在底部隐藏
                    tvSongCount.visibility =
                        if (isAtBottom) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        })

        // 点击定位按钮的处理逻辑
        binding.btnScrollToCurrent.setOnClickListener {
            scrollToContentClick = true
            // 移除延迟消息
            hideRunnable.let { hideHandler.removeCallbacks(it) }
            scrollToCurrentlyPlayingSong()
        }
    }

    private fun scrollToCurrentlyPlayingSong() {
        val currentSong = musicService?.currentSong?.value ?: return
        val allSongs = viewModel.allSongs.value ?: return

        // 查找当前播放歌曲在列表中的位置
        val position = allSongs.indexOfFirst { it.id == currentSong.id }
        if (position != -1) {
            binding.btnScrollToCurrent.visibility = android.view.View.GONE
            binding.recyclerView.smoothScrollToPosition(position)

            // 修复：延迟重置 scrollToContentClick 标志
            // 确保即使 smoothScrollToPosition 不触发滚动状态变化，也能正确重置
            hideHandler.postDelayed({
                scrollToContentClick = false
            }, 500) // 500ms 延迟，足够覆盖 smoothScrollToPosition 的动画时间
        }
    }

    private fun highlightCurrentSong(position: Int) {
        // 实现高亮逻辑，例如改变背景颜色或显示指示器
        // 这里可以调用SongAdapter中的方法来高亮显示指定位置的歌曲
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.searchSongs(newText ?: "")
                return true
            }
        })

        // 监听搜索框展开/收起状态，控制操作按钮布局的显示
        binding.searchView.setOnSearchClickListener {
            // 进入搜索模式，隐藏操作按钮布局（包含循环模式、排序、设置）
            binding.actionButtonsLayout.visibility = android.view.View.GONE
        }

        binding.searchView.setOnCloseListener {
            // 退出搜索模式，显示操作按钮布局
            binding.actionButtonsLayout.visibility = android.view.View.VISIBLE
            false // 返回false表示不执行默认的关闭行为
        }
    }

    // 排序弹窗相关
    private var sortPopup: android.widget.PopupWindow? = null

    private fun showSortMenu() {
        // 如果弹窗已显示，则关闭
        if (sortPopup?.isShowing == true) {
            sortPopup?.dismiss()
            return
        }

        val popupView = layoutInflater.inflate(R.layout.popup_sort_menu, null)
        val tvSortByTime = popupView.findViewById<android.widget.TextView>(R.id.tvSortByTime)
        val tvSortByTitle = popupView.findViewById<android.widget.TextView>(R.id.tvSortByTitle)
        val tvSortByArtist = popupView.findViewById<android.widget.TextView>(R.id.tvSortByArtist)

        // 根据当前排序模式显示钩号
        val currentSortMode = viewModel.sortMode.value
        val checkColor = ContextCompat.getColor(this, android.R.color.holo_red_light)
        val normalTextColor = ContextCompat.getColor(this, R.color.text_primary)

        setupSortItem(
            tvSortByTime,
            currentSortMode == MusicViewModel.SortMode.BY_TIME,
            checkColor,
            normalTextColor
        )
        setupSortItem(
            tvSortByTitle,
            currentSortMode == MusicViewModel.SortMode.BY_TITLE,
            checkColor,
            normalTextColor
        )
        setupSortItem(
            tvSortByArtist,
            currentSortMode == MusicViewModel.SortMode.BY_ARTIST,
            checkColor,
            normalTextColor
        )

        // 点击排序选项
        tvSortByTime.setOnClickListener {
            viewModel.sortSongs(MusicViewModel.SortMode.BY_TIME)
            updateServiceSongList()
            sortPopup?.dismiss()
            sortPopup = null
        }
        tvSortByTitle.setOnClickListener {
            viewModel.sortSongs(MusicViewModel.SortMode.BY_TITLE)
            updateServiceSongList()
            sortPopup?.dismiss()
            sortPopup = null
        }
        tvSortByArtist.setOnClickListener {
            viewModel.sortSongs(MusicViewModel.SortMode.BY_ARTIST)
            updateServiceSongList()
            sortPopup?.dismiss()
            sortPopup = null
        }

        // 将 dp 转换为 px
        val popupWidthPx = (180 * resources.displayMetrics.density).toInt()
        val marginRightPx = (8 * resources.displayMetrics.density).toInt() // 右边留 8dp

        sortPopup = android.widget.PopupWindow(
            popupView, popupWidthPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true
        ).apply {
            elevation = 8f
            animationStyle = R.style.PopupAnimation
            setOnDismissListener { sortPopup = null }
        }

        // 计算 x 偏移，确保弹窗不超出屏幕右边缘
        val screenWidthPx = resources.displayMetrics.widthPixels
        val ivSortLocation = IntArray(2)
        binding.ivSort.getLocationOnScreen(ivSortLocation)
        val ivSortRight = ivSortLocation[0] + binding.ivSort.width
        val xOffset = if (ivSortRight + popupWidthPx + marginRightPx > screenWidthPx) {
            // 弹窗会超出屏幕，向左偏移
            screenWidthPx - ivSortRight - popupWidthPx - marginRightPx
        } else {
            0
        }

        // 显示在 ivSort 下方
        sortPopup?.showAsDropDown(binding.ivSort, xOffset, 32)
    }

    /**
     * 设置排序 item 的样式：选中状态红色文字+红色对钩
     */
    private fun setupSortItem(
        textView: android.widget.TextView, isSelected: Boolean, checkColor: Int, normalColor: Int
    ) {
        textView.setTextColor(if (isSelected) checkColor else normalColor)
        textView.setCompoundDrawablesWithIntrinsicBounds(
            0, 0, if (isSelected) R.drawable.ic_check else 0, 0
        )
        if (isSelected) {
            textView.compoundDrawables[2]?.setTint(checkColor)
        }
    }

    /**
     * 将排序后的完整列表同步到 MusicService，确保播放顺序与 UI 一致
     */
    private fun updateServiceSongList() {
        val sortedSongs = viewModel.getSortedFullSongs()
        if (sortedSongs.isEmpty()) return

        val currentSong = musicService?.currentSong?.value
        val currentIndex = if (currentSong != null) {
            sortedSongs.indexOfFirst { it.id == currentSong.id }.takeIf { it != -1 } ?: 0
        } else {
            0
        }
        musicService?.setSongList(sortedSongs, currentIndex)
    }

    private fun setupBottomPlayer() {
        binding.bottomPlayer.setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java)
            startActivity(intent)
            // 主界面淡出，播放界面上滑
            overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out)
        }

        // 播放模式切换按钮点击事件
        binding.ivLoop.setOnClickListener {
            val currentMode = musicService?.getPlayMode() ?: MusicService.PlayMode.ALL_LOOP
            val nextMode = when (currentMode) {
                MusicService.PlayMode.ALL_LOOP -> MusicService.PlayMode.SINGLE_LOOP
                MusicService.PlayMode.SINGLE_LOOP -> MusicService.PlayMode.RANDOM
                MusicService.PlayMode.RANDOM -> MusicService.PlayMode.SEQUENCE
                MusicService.PlayMode.SEQUENCE -> MusicService.PlayMode.ALL_LOOP
            }
            musicService?.setPlayMode(nextMode)
            updateLoopIcon(nextMode)
            // 弹出 Toast 提示当前播放模式
            val modeText = when (nextMode) {
                MusicService.PlayMode.ALL_LOOP -> "全部循环"
                MusicService.PlayMode.SINGLE_LOOP -> "单曲循环"
                MusicService.PlayMode.RANDOM -> "随机播放"
                MusicService.PlayMode.SEQUENCE -> "顺序播放"
            }
            Toast.makeText(this, modeText, Toast.LENGTH_SHORT).show()
        }

        // 排序按钮点击事件
        binding.ivSort.setOnClickListener {
            showSortMenu()
        }

        // 设置按钮点击事件
        binding.ivSetting.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // 增大ivSort和ivSetting的点击范围
        setupTouchDelegate()

        // 设置播放控制按钮点击事件
        setupBottomPlayerButtons()
    }

    private fun setupTouchDelegate() {
        val expandPx = (48 * resources.displayMetrics.density).toInt()
        expandTouchTarget(binding.ivLoop, expandPx)
        expandTouchTarget(binding.ivSort, expandPx)
        expandTouchTarget(binding.ivSetting, expandPx)
    }

    /**
     * 根据播放模式更新循环图标
     */
    private fun updateLoopIcon(mode: MusicService.PlayMode) {
        val iconRes = when (mode) {
            MusicService.PlayMode.ALL_LOOP -> R.drawable.ic_loop_all
            MusicService.PlayMode.SINGLE_LOOP -> R.drawable.ic_loop_one
            MusicService.PlayMode.RANDOM -> R.drawable.ic_random
            MusicService.PlayMode.SEQUENCE -> R.drawable.ic_sequence
        }
        binding.ivLoop.setImageResource(iconRes)
    }

    private fun expandTouchTarget(view: android.view.View, expandPx: Int) {
        view.post {
            val parent = view.parent as android.view.ViewGroup
            val rect = android.graphics.Rect()
            view.getHitRect(rect)
            rect.top -= expandPx / 2
            rect.bottom += expandPx / 2
            rect.left -= expandPx / 2
            rect.right += expandPx / 2
            parent.touchDelegate = android.view.TouchDelegate(rect, view)
        }
    }

    private fun setupBottomPlayerButtons() {
        binding.playButton.setOnClickListener {
            musicService?.let { service ->
                if (service.isPlaying.value == true) {
                    service.pause()
                } else {
                    // 请求音频焦点，如果获得焦点就播放
                    service.requestAudioFocusAndPlay()
                }
            }
        }

        binding.previousButton.setOnClickListener {
            musicService?.requestAudioFocusAndPlayPrevious()
        }

        binding.nextButton.setOnClickListener {
            musicService?.requestAudioFocusAndPlayNext()
        }
    }

    // 保存观察者的引用，以便在重新连接时移除旧的观察者
    private var isPlayingObserver: androidx.lifecycle.Observer<Boolean>? = null
    private var currentSongObserver: androidx.lifecycle.Observer<com.unicorn.player.model.Song?>? =
        null
    private var fileChangedObserver: androidx.lifecycle.Observer<Unit>? = null
    private var requestSongListObserver: androidx.lifecycle.Observer<com.unicorn.player.service.Event<Boolean>>? =
        null
    private var playModeObserver: androidx.lifecycle.Observer<MusicService.PlayMode>? = null

    private fun setupBottomPlayerObservers() {
        // 先移除旧的观察者，避免重复注册
        removeBottomPlayerObservers()

        // Observe playing state to update play button icon and animation
        isPlayingObserver = androidx.lifecycle.Observer { isPlaying ->
            binding.playButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            // 更新Adapter的播放状态
            songAdapter.isPlaying = isPlaying
            // 直接控制动画，避免notifyItemChanged触发onBindViewHolder重置角度
            if (isPlaying) {
                songAdapter.resumeCurrentSongAnimation()
            } else {
                songAdapter.pauseCurrentSongAnimation()
            }
        }
        musicService?.isPlaying?.observe(this, isPlayingObserver!!)

        // Observe current song to update bottom player info
        currentSongObserver = androidx.lifecycle.Observer { song ->
            song?.let { nonNullSong ->
                updateBottomPlayer(nonNullSong)
                // 更新Adapter中的当前播放歌曲状态
                songAdapter.currentPlayingSong = nonNullSong
                songAdapter.notifyDataSetChanged()
            }
        }
        musicService?.currentSong?.observe(this, currentSongObserver!!)

        // 监听文件变化，自动刷新列表
        fileChangedObserver = androidx.lifecycle.Observer {
            loadMusic()
        }
        musicService?.fileChanged?.observe(this, fileChangedObserver!!)

        // 监听请求重新设置歌曲列表的通知（当songList为空时）
        requestSongListObserver = androidx.lifecycle.Observer { event ->
            event.getContentIfNotHandled()?.let { request ->
                if (request) {
                    resetSongListAndPlayNext()
                }
            }
        }
        musicService?.requestSongList?.observe(this, requestSongListObserver!!)

        // 监听播放模式变化，更新 ivLoop 图标
        playModeObserver = androidx.lifecycle.Observer { mode ->
            updateLoopIcon(mode)
        }
        musicService?.playModeLiveData?.observe(this, playModeObserver!!)
    }

    /**
     * 重新设置歌曲列表并播放下一首
     * 当MusicService的songList为空时，由MainActivity重新设置
     */
    private fun resetSongListAndPlayNext() {
        // 使用 fullSongs（完整列表）确保播放顺序一致
        viewModel.fullSongs.value?.let { songs ->
            val currentSong = musicService?.currentSong?.value
            val currentIndex = if (currentSong != null) {
                songs.indexOfFirst { it.id == currentSong.id }.takeIf { it != -1 } ?: 0
            } else {
                0
            }
            // 重新设置歌曲列表
            musicService?.setSongList(songs, currentIndex)
            // 播放下一首
            musicService?.playNext()
        }
    }

    private fun removeBottomPlayerObservers() {
        isPlayingObserver?.let { musicService?.isPlaying?.removeObserver(it) }
        currentSongObserver?.let { musicService?.currentSong?.removeObserver(it) }
        fileChangedObserver?.let { musicService?.fileChanged?.removeObserver(it) }
        requestSongListObserver?.let { musicService?.requestSongList?.removeObserver(it) }
        playModeObserver?.let { musicService?.playModeLiveData?.removeObserver(it) }
        isPlayingObserver = null
        currentSongObserver = null
        fileChangedObserver = null
        requestSongListObserver = null
        playModeObserver = null
    }

    private fun updateBottomPlayerUI() {
        // Update UI with current playing state
        val service = musicService ?: return
        if (service.currentSong.value != null) {
            updateBottomPlayer(service.currentSong.value!!)
        }
        binding.playButton.setImageResource(
            if (service.isPlaying.value == true) R.drawable.ic_pause else R.drawable.ic_play
        )
        // 注意：updateLoopIcon 现在通过 playModeObserver 异步更新，不再在这里同步调用
        // 这样可以确保 loadPlaybackState() 完成后正确恢复图标
    }

    private fun checkPermissions() {
        // 先检查通知权限（Android 13及以上）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 检查存储权限
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                loadMusic()
            }

            ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.READ_MEDIA_AUDIO
            ) -> {
                // Show explanation if needed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    storagePermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                }
            }

            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    storagePermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                }
            }
        }
    }

    private fun loadMusic() {
        viewModel.loadMusic()
    }

    // 保存扫描前的当前播放歌曲信息
    private var savedCurrentSongId: Long? = null
    private var savedIsPlaying: Boolean = false

    // 扫描完成后更新列表
    private fun onScanComplete() {
        // 扫描完成，重置标志位
        isScanning = false

        // 保持当前播放状态
        val currentSong = musicService?.currentSong?.value
        if (currentSong != null && currentSong.id != savedCurrentSongId) {
            // 当前播放的歌曲被删除，停止播放
            if (savedIsPlaying) {
                musicService?.pause()
            }
        }

        // 结束刷新动画
        binding.smartRefreshLayout.finishRefresh(500)

        Log.d(TAG, "扫描完成，列表已更新")
    }

    private fun bindMusicService() {
        val intent = Intent(this, MusicService::class.java)
        // 先startService确保服务在前台运行
        startService(intent)
        // 再bindService确保能正确绑定
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onSongClick(song: Song, position: Int) {
        // 使用 fullSongs（完整列表）而不是 allSongs（可能是搜索结果）
        // 这样播放完成自动播放下一首时，会按照完整列表的顺序播放
        viewModel.fullSongs.value?.let { songs ->
            // 通过song ID查找在列表中的真实位置
            val realPosition =
                songs.indexOfFirst { it.id == song.id }.takeIf { it != -1 } ?: position
            if (isServiceBound) {
                musicService?.setSongList(songs, realPosition)
                // 检查是否已经在播放同一首歌
                if (musicService?.currentSong?.value?.id == song.id) {
                    if (musicService?.isPlaying?.value == true) {
                        // 如果正在播放同一首歌，不做处理
                    } else {
                        // 如果是同一首歌但暂停状态，恢复播放
                        musicService?.requestAudioFocusAndPlay()
                        musicService?.updateNotification()
                    }
                } else {
                    // 播放新歌曲
                    musicService?.requestAudioFocusAndPlayCurrentSong()
                    musicService?.updateNotification()
                    updateBottomPlayer(song)
                }
            } else {
                // 如果服务未绑定，先确保服务运行，然后通过startService传递播放参数
                val intent = Intent(this, MusicService::class.java).apply {
                    action = MusicService.ACTION_PLAY
                    putExtra("songId", song.id)
                    putExtra("position", position)
                    putExtra("songListSize", songs.size)
                    // 将songList转换为可序列化的数据
                    val songDataList = songs.map { song ->
                        "${song.id}|${song.title}|${song.artist}|${song.album}|${song.duration}|${song.path}|${song.albumArt ?: ""}"
                    }
                    putStringArrayListExtra("songList", ArrayList(songDataList))
                }
                startService(intent)
                updateBottomPlayer(song)
            }

            // 点击歌曲后退出搜索模式
            exitSearchMode()
        }
    }

    // 退出搜索模式
    private fun exitSearchMode() {
        // 清空搜索框文本并关闭搜索视图
        binding.searchView.setQuery("", false)
        binding.searchView.isIconified = true
    }

    private fun updateBottomPlayer(song: Song) {
        // 检查 Activity 是否已销毁，避免 Glide 在 destroyed activity 上加载导致 crash
        if (isDestroyed || isFinishing) return

        binding.bottomPlayer.visibility = android.view.View.VISIBLE
        binding.songTitle.text = song.title
        binding.artistName.text = song.artist

        // 激活跑马灯效果
        binding.songTitle.post {
            binding.songTitle.isSelected = true
            binding.songTitle.requestFocus()
        }

        // 使用Glide加载专辑封面并添加圆角
        Glide.with(this).load(song.albumArt).placeholder(R.drawable.ic_music_note)
            .error(R.drawable.ic_music_note)
            .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(20))
            .into(binding.albumArt)
    }

    override fun onResume() {
        super.onResume()
        // 只更新UI状态，不重新设置观察者或加载播放状态
        if (isServiceBound && musicService != null) {
            updateBottomPlayerUI()
            // 恢复Adapter中的播放状态和动画
            songAdapter.isPlaying = musicService?.isPlaying?.value == true
            songAdapter.currentPlayingSong = musicService?.currentSong?.value
            // 清除暂停标记
            songAdapter.isPaused = false
            // 恢复动画（从0角度开始，简化状态管理）
            songAdapter.resumeCurrentSongAnimation()
        }
    }

    override fun onPause() {
        super.onPause()
        musicService?.savePlaybackState()
        // 设置暂停标记，暂停当前播放歌曲的动画（保留角度）
        songAdapter.isPaused = true
        songAdapter.pauseCurrentSongAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理观察者，避免内存泄漏
        removeBottomPlayerObservers()
        // 停止所有动画，释放资源
        songAdapter.stopCurrentSongAnimation()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    // 实现 OnSongMoreClickListener 接口
    override fun onMoreClick(song: Song, position: Int) {
        songInfoHelper.showSongInfoDialog(song)
    }
}