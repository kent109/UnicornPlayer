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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.tabs.TabLayoutMediator
import com.unicorn.player.databinding.ActivityMainBinding
import com.unicorn.player.model.Playlist
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.service.PlaySource
import com.unicorn.player.ui.AlbumFragment
import com.unicorn.player.ui.ArtistFragment
import com.unicorn.player.ui.MainPagerAdapter
import com.unicorn.player.ui.PlaylistRefresher
import com.unicorn.player.ui.SelectPlaylistDialog
import com.unicorn.player.ui.SongsFragment
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory

class MainActivity : AppCompatActivity(), SongsFragment.SongListHost {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MusicViewModel
    private lateinit var songInfoHelper: SongInfoHelper

    // SongListHost 接口实现：暴露给 SongsFragment 使用
    override var musicService: MusicService? = null
        private set
    private var isServiceBound = false

    // 将 scrollToContentClick 提升为类级别变量，解决作用域问题
    private var scrollToContentClick = false

    // 标记是否正在扫描音乐库
    private var isScanning = false

    // 底部播放栏控制器，封装底部播放栏的按钮事件、观察者与 UI 更新
    private lateinit var bottomPlayerController: BottomPlayerController

    companion object {
        const val TAG = "MainActivity"
    }

    private val multiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (permission, granted) ->
            when {
                granted -> Unit
                permission == Manifest.permission.READ_MEDIA_AUDIO -> {
                    Toast.makeText(this, "需要存储权限才能访问音乐文件", Toast.LENGTH_LONG).show()
                    finish()
                }

                permission == Manifest.permission.POST_NOTIFICATIONS -> {
                    Toast.makeText(this, "通知权限被拒绝，通知功能可能受限", Toast.LENGTH_LONG)
                        .show()
                }

                permission == Manifest.permission.BLUETOOTH_CONNECT -> {
                    Toast.makeText(
                        this,
                        "禁止蓝牙连接(附近设备)无法监听蓝牙事件",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        // READ_MEDIA_AUDIO 已授予时加载音乐（用户拒绝时 finish() 已调用）
        if (results[Manifest.permission.READ_MEDIA_AUDIO] == true) {
            loadMusic()
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
            // 通知歌曲 Fragment 服务已连接
            getSongsFragment()?.onServiceConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isServiceBound = false
            // 清理观察者，避免内存泄漏
            removeBottomPlayerObservers()
            // 通知歌曲 Fragment 服务已断开
            getSongsFragment()?.onServiceDisconnected()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化歌曲信息帮助类
        songInfoHelper = SongInfoHelper(this)
        setupAddToPlaylistListener()
        songInfoHelper.onDeleteListener = object : SongInfoHelper.OnDeleteListener {
            override fun onDelete(song: Song) {
                // 若删除的是当前播放歌曲，移除该歌曲（停止播放、清空底部播放栏、移除通知）
                if (musicService?.currentSong?.value?.id == song.id) {
                    musicService?.removeCurrentSong()
                }
                // 持久化隐藏 ID + 更新列表
                viewModel.hideSong(song.id)
                // 同步服务播放列表
                updateServiceSongList()
            }
        }

        setupViewModel()
        setupViewPager()
        setupSearchView()
        setupBottomPlayer()
        setupBackPressHandler()

        checkPermissions()
        bindMusicService()
    }

    /**
     * 设置 ViewPager2 与 TabLayout 联动（滑动切换与标签点击双向同步）
     */
    private fun setupViewPager() {
        val titles = listOf("歌曲", "歌手", "专辑", "歌单")
        val pagerAdapter = MainPagerAdapter(this, titles)
        binding.viewPager.adapter = pagerAdapter
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = titles[position]
        }.attach()

        // 切换标签页时，仅在"歌曲"页显示排序按钮
        binding.viewPager.registerOnPageChangeCallback(object :
            androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateSortButtonVisibility(position)
            }

            /**
             * 页面滚动状态变化时控制 AlbumFragment 的 waveSideBar 可见性：
             * 滑动/动画过程中隐藏，静止（IDLE）后才显示，避免切换动画里侧边栏错位。
             *
             * 同时要求 currentItem == 2（当前在专辑页），避免停在歌手/歌单页时
             * 相邻的专辑页 waveSideBar 被淡入（alpha→1），拖向专辑瞬间滑入视野造成闪现。
             */
            override fun onPageScrollStateChanged(state: Int) {
                // 仅在静止且当前为专辑页、且有专辑数据时才显示 waveSideBar
                val hasAlbums = !viewModel.albums.value.isNullOrEmpty()
                val show = state == ViewPager2.SCROLL_STATE_IDLE
                    && binding.viewPager.currentItem == 2
                    && hasAlbums
                getAlbumFragment()?.setWaveSideBarVisible(show)
            }
        })
        // 初始化时同步一次可见性
        updateSortButtonVisibility(binding.viewPager.currentItem)
    }

    /**
     * 开关 ViewPager2 的用户滑动。
     *
     * 歌单列表 item 正在侧滑露出「编辑 / 删除」时，需临时禁用 ViewPager2 的切页滑动，
     * 避免横向分页手势与 item 侧滑冲突；侧滑结束后恢复。
     */
    fun setViewPagerSwipeEnabled(enabled: Boolean) {
        binding.viewPager.isUserInputEnabled = enabled
    }

    /**
     * 切换标签页时，排序按钮始终显示
     */
    private fun updateSortButtonVisibility(position: Int) {
        binding.ivSort.visibility = android.view.View.VISIBLE
    }

    /**
     * 获取歌曲 Fragment 实例（ViewPager2 中 position 0 对应标签 "f0"），用于服务连接通知
     */
    private fun getSongsFragment(): SongsFragment? {
        return supportFragmentManager.findFragmentByTag("f0") as? SongsFragment
    }

    /**
     * 获取歌手 Fragment 实例（ViewPager2 中 position 1 对应标签 "f1"），用于双击滚动到顶部
     */
    private fun getArtistFragment(): ArtistFragment? {
        return supportFragmentManager.findFragmentByTag("f1") as? ArtistFragment
    }

    /**
     * 获取专辑 Fragment 实例（ViewPager2 中 position 2 对应标签 "f2"），用于控制 waveSideBar 可见性
     */
    private fun getAlbumFragment(): AlbumFragment? {
        return supportFragmentManager.findFragmentByTag("f2") as? AlbumFragment
    }

    /**
     * 根据当前标签页，滚动对应列表到顶部。
     * 双击 SearchView 时，歌曲/歌手/专辑页各自的列表滚到顶部。
     */
    private fun scrollCurrentTabToTop() {
        when (binding.viewPager.currentItem) {
            0 -> getSongsFragment()?.scrollToTop()
            1 -> getArtistFragment()?.scrollToTop()
            2 -> getAlbumFragment()?.scrollToTop()
        }
    }

    /**
     * 设置返回键处理：搜索模式下先退出搜索模式，不退出 Activity
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!binding.searchView.isIconified) {
                        // 处于搜索模式，先退出搜索模式
                        exitSearchMode()
                    } else {
                        // 非搜索模式，执行默认返回行为
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            })
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
                        // 双击检测：滚动当前标签页列表到顶部
                        scrollCurrentTabToTop()
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

        // 监听完整歌曲列表变化，当服务已绑定时自动同步排序后的列表到 MusicService
        // 这确保应用重启后，MusicService 的播放顺序与 UI 显示的排序一致
        viewModel.fullSongs.observe(this) { songs ->
            if (songs.isNotEmpty() && isServiceBound) {
                updateServiceSongList()
            }
        }
    }

    /**
     * 将排序后的完整列表同步到 MusicService，确保播放顺序与 UI 一致。
     *
     * 注意：当正在播放歌单（f3$xxx）时，此方法不能覆盖歌单的歌曲列表，
     * 否则会将播放列表退化为全部歌曲。歌单列表由 syncPlaylistSongList 管理。
     */
    private fun updateServiceSongList() {
        // 正在播放歌单时，不覆盖歌单的歌曲列表
        if (musicService?.isPlayingPlaylist() == true) return

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

    private fun setupBottomPlayer() {
        // 初始化底部播放栏控制器，封装播放栏点击跳转、播放/上下首按钮事件
        bottomPlayerController = BottomPlayerController(
            this,
            binding.bottomPlayer
        ) { musicService }
        bottomPlayerController.setupClickListeners()

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

    // 保存观察者的引用，以便在重新连接时移除旧的观察者
    // 注意：isPlaying + currentSong 观察者已委托给 BottomPlayerController
    private var fileChangedObserver: androidx.lifecycle.Observer<Unit>? = null
    private var requestSongListObserver: androidx.lifecycle.Observer<com.unicorn.player.service.Event<Boolean>>? =
        null
    private var playModeObserver: androidx.lifecycle.Observer<MusicService.PlayMode>? = null

    private fun setupBottomPlayerObservers() {
        // 先移除旧的观察者，避免重复注册
        removeBottomPlayerObservers()

        // 委托控制器绑定播放按钮图标 + 歌曲信息观察者
        musicService?.let { bottomPlayerController.observe(it) }

        // 监听文件变化，自动刷新列表（文件被外部修改，需强制扫描）
        fileChangedObserver = androidx.lifecycle.Observer {
            viewModel.loadMusic(force = true)
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
        // 委托控制器移除播放按钮图标 + 歌曲信息观察者
        bottomPlayerController.removeObservers()
        fileChangedObserver?.let { musicService?.fileChanged?.removeObserver(it) }
        requestSongListObserver?.let { musicService?.requestSongList?.removeObserver(it) }
        playModeObserver?.let { musicService?.playModeLiveData?.removeObserver(it) }
        fileChangedObserver = null
        requestSongListObserver = null
        playModeObserver = null
    }

    private fun updateBottomPlayerUI() {
        // 委托控制器刷新播放按钮图标与歌曲信息
        val service = musicService ?: return
        bottomPlayerController.updateUI(service)
        // 注意：updateLoopIcon 现在通过 playModeObserver 异步更新，不再在这里同步调用
        // 这样可以确保 loadPlaybackState() 完成后正确恢复图标
    }

    private fun checkPermissions() {
        // 收集所有未授予的运行时权限，一次性申请（一个弹窗）
        val permissionsToRequest = mutableListOf<String>()

        // 存储权限（Android 13+ 使用 READ_MEDIA_AUDIO，及以下使用 READ_EXTERNAL_STORAGE）
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, storagePermission)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(storagePermission)
        }

        // 通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 蓝牙连接权限（Android 12+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            multiPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // 所有权限都已授予，直接加载音乐
            loadMusic()
        }
    }

    private fun loadMusic() {
        viewModel.loadMusic()
    }

    private fun bindMusicService() {
        val intent = Intent(this, MusicService::class.java)
        // 先startService确保服务在前台运行
        startService(intent)
        // 再bindService确保能正确绑定
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ==================== SongListHost 接口实现 ====================
    // 由 SongsFragment 转发的歌曲点击事件

    override fun onSongClick(song: Song, position: Int) {
        // 使用 fullSongs（完整列表）而不是 allSongs（可能是搜索结果）
        // 这样播放完成自动播放下一首时，会按照完整列表的顺序播放
        viewModel.fullSongs.value?.let { songs ->
            // 通过song ID查找在列表中的真实位置
            val realPosition =
                songs.indexOfFirst { it.id == song.id }.takeIf { it != -1 } ?: position
            if (isServiceBound) {
                musicService?.setSongList(songs, realPosition)
                // 歌曲 tab 作为播放入口：来源为 f0（全部歌曲）
                musicService?.setPlaySource(PlaySource.SONGS)
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
                    // 歌曲 tab 作为播放入口：来源为 f0（全部歌曲）
                    putExtra("sourceTag", PlaySource.SONGS)
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
        // 委托控制器更新标题、艺术家、专辑封面
        bottomPlayerController.updateSongInfo(song)
    }

    override fun onResume() {
        super.onResume()
        // 只更新UI状态，不重新设置观察者或加载播放状态
        if (isServiceBound && musicService != null) {
            updateBottomPlayerUI()
        }
    }

    override fun onPause() {
        super.onPause()
        musicService?.savePlaybackState()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理观察者，避免内存泄漏
        removeBottomPlayerObservers()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    // 由 SongsFragment 转发的更多操作事件
    override fun onMoreClick(song: Song, position: Int) {
        // 先异步查询歌单，再决定是否显示"添加到歌单"选项
        lifecycleScope.launch {
            val playlists = withContext(Dispatchers.IO) {
                MusicRepository(this@MainActivity).getAllPlaylists().firstOrNull()
            } ?: emptyList()
            songInfoHelper.showSongInfoDialog(
                song,
                showDeleteOption = true,
                showAddToPlaylist = playlists.isNotEmpty()
            )
        }
    }

    /**
     * 扫描完成回调：扫描结果为空时清空播放列表、停止播放、重置底部播放条，
     * 并删除数据库中所有歌曲及歌单-歌曲关联记录（保留歌单定义、DataStore 播放状态、SharedPreferences 排序模式等应用配置）。
     */
    override fun onScanCompleted(empty: Boolean) {
        if (empty) {
            musicService?.clearSongListAndStop()
            // 异步清除数据库歌曲记录，不阻塞 UI
            lifecycleScope.launch {
                MusicRepository(this@MainActivity).deleteAllSongsAndPlaylistAssociations()
            }
        }
    }

    /**
     * 添加到歌单回调
     */
    private fun setupAddToPlaylistListener() {
        songInfoHelper.onAddToPlaylistListener = object : SongInfoHelper.OnAddToPlaylistListener {
            override fun onAddToPlaylist(song: Song) {
                showSelectPlaylistDialog(song)
            }
        }
    }

    /**
     * 显示歌单选择弹窗
     */
    private fun showSelectPlaylistDialog(song: Song) {
        lifecycleScope.launch {
            val repository = MusicRepository(this@MainActivity)
            val playlists = withContext(Dispatchers.IO) {
                repository.getAllPlaylists().firstOrNull()
            } ?: emptyList()
            if (playlists.isEmpty()) {
                Toast.makeText(this@MainActivity, "暂无歌单", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 查询每个歌单的歌曲数量，用于弹窗显示
            val songCounts = withContext(Dispatchers.IO) {
                val counts = mutableMapOf<Long, Int>()
                playlists.forEach { playlist ->
                    val songs = repository.getPlaylistSongs(playlist.id).firstOrNull()
                    counts[playlist.id] = songs?.size ?: 0
                }
                counts
            }
            // 以屏幕中心作为弹窗动画起点
            val metrics = resources.displayMetrics
            val centerX = metrics.widthPixels / 2f
            val centerY = metrics.heightPixels / 2f
            SelectPlaylistDialog(
                context = this@MainActivity,
                triggerX = centerX,
                triggerY = centerY,
                allPlaylists = playlists,
                songCounts = songCounts,
                onConfirm = { chosenPlaylistIds ->
                    addSongToPlaylists(song, chosenPlaylistIds, playlists, repository)
                }
            ).show()
        }
    }

    /**
     * 将歌曲添加到选中的歌单（持久化到数据库）
     */
    private fun addSongToPlaylists(
        song: Song,
        playlistIds: List<Long>,
        playlists: List<Playlist>,
        repository: MusicRepository
    ) {
        if (playlistIds.isEmpty()) return
        lifecycleScope.launch {
            // 先写入数据库
            withContext(Dispatchers.IO) {
                playlistIds.forEach { playlistId ->
                    repository.addSongsToPlaylist(playlistId, listOf(song))
                }
            }
            Toast.makeText(
                this@MainActivity,
                "已添加到 ${playlistIds.size} 个歌单",
                Toast.LENGTH_SHORT
            ).show()
            // 通知 PlaylistFragment 刷新歌单列表（更新歌曲数量）
            PlaylistRefresher.notifyPlaylistsChanged()
            // 若当前正在播放其中某个歌单，同步更新 MusicService 的歌曲列表
            withContext(Dispatchers.IO) {
                playlistIds.forEach { playlistId ->
                    val playlist = playlists.find { it.id == playlistId }
                    if (playlist != null) {
                        val songs = repository.getPlaylistSongs(playlist.id).firstOrNull() ?: emptyList()
                        musicService?.syncPlaylistSongList(playlist.name, songs)
                    }
                }
            }
        }
    }
}
