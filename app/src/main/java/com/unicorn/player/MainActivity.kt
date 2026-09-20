package com.unicorn.player

import android.Manifest
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SearchView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.unicorn.player.databinding.ActivityMainBinding
import com.unicorn.player.manager.MusicManager
import com.unicorn.player.model.Playlist
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.service.PlaySource
import com.unicorn.player.ui.AlbumFragment
import com.unicorn.player.ui.ArtistFragment
import com.unicorn.player.ui.EmptyPlaylistActionDialog
import com.unicorn.player.ui.MainPagerAdapter
import com.unicorn.player.ui.MultiChoiceFragment
import com.unicorn.player.ui.PlaylistExportCleanupDialog
import com.unicorn.player.ui.PlaylistExportConflictDialog
import com.unicorn.player.ui.PlaylistFragment
import com.unicorn.player.ui.PlaylistRefresher
import com.unicorn.player.ui.SelectPlaylistDialog
import com.unicorn.player.ui.SongsFragment
import com.unicorn.player.util.AudioTagEditor
import com.unicorn.player.util.PlaylistFileManager
import com.unicorn.player.util.UpdateHelper
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory
import com.unicorn.player.viewmodel.PlaylistViewModel
import com.unicorn.player.viewmodel.PlaylistViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity(), SongsFragment.SongListHost,
    MusicManager.ConnectionCallback, MultiChoiceFragment.OnMultiChoiceActionListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MusicViewModel

    private lateinit var playlistViewModel: PlaylistViewModel
    private lateinit var songInfoHelper: SongInfoHelper

    // ActivityResultLauncher
    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // 用户授权成功，重试写入
            songInfoHelper.retryPendingWrite()
        }
    }

    // SongListHost 接口实现：通过MusicManager获取Service
    override var musicService: MusicService? = null
        private set

    // 将 scrollToContentClick 提升为类级别变量，解决作用域问题
    private var scrollToContentClick = false

    // 标记是否正在扫描音乐库
    private var isScanning = false

    // 底部播放栏控制器，封装底部播放栏的按钮事件、观察者与 UI 更新
    private lateinit var bottomPlayerController: BottomPlayerController

    // 多选相关
    private var multiChoiceFragment: MultiChoiceFragment? = null
    private var currentMultiChoiceType: Int = 0

    // 歌单批量导出相关
    /** 挂起批量导出的歌单 id 集合（SAF 授权后继续） */
    private var pendingExportPlaylistIds: Set<Long> = emptySet()

    /** 防连点标志：批量导出进行中 */
    private var isExportingPlaylists = false

    /** SAF 目录选择器：授权后持久化树 URI -> 确保目录存在 -> 执行批量导出 */
    private val playlistExportTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                PlaylistFileManager.saveTreeUri(this, treeUri)
                ensurePlaylistExportDirAndExport()
            } else {
                // 用户取消授权：停留多选页，恢复导出按钮
                finishPlaylistExportState()
            }
        }

    // 自动检查更新的延迟任务（用于在 onDestroy 时取消，避免内存泄漏）
    private var autoUpdateCheckRunnable: Runnable? = null

    companion object {
        const val TAG = "MainActivity"
        /** 启动后延迟自动检查更新的时间（毫秒） */
        private const val AUTO_UPDATE_CHECK_DELAY_MS = 3000L

        /** 待执行的静默检查任务 Handler（静态，供 SettingsActivity 手动检查时取消） */
        private var autoUpdateHandler: android.os.Handler? = null
        /** 待执行的静默检查任务 Runnable（静态，供 SettingsActivity 手动检查时取消） */
        private var autoUpdatePendingRunnable: Runnable? = null

        /**
         * 取消待执行的静默检查更新任务
         * 在 SettingsActivity 点击版本号手动检查时调用，避免重复检查
         */
        fun cancelPendingAutoUpdateCheck() {
            autoUpdatePendingRunnable?.let { autoUpdateHandler?.removeCallbacks(it) }
            autoUpdateHandler = null
            autoUpdatePendingRunnable = null
        }

        const val KEY_CREATE = "create"
        const val KEY_RESTORE = "restore"
        const val NORMAL_CREATE: Int = 0
        const val RESTORE_CREATE: Int = 1
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化歌曲信息帮助类
        songInfoHelper = SongInfoHelper(this)
        setupAddToPlaylistListener()
        setupWritePermissionCallback()
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

        val restoreFlag = if (savedInstanceState != null) RESTORE_CREATE else NORMAL_CREATE

        setupViewModel()
        setupViewPager()
        setupSearchView()
        setupBottomPlayer()
        setupBackPressHandler()

        checkPermissions()
        bindMusicService(restoreFlag)

        // 自动检查更新（若用户启用）
        checkUpdateOnStartup()
    }

    /**
     * 应用启动时延迟自动检查更新（遵循用户设置）
     * 使用 Handler.postDelayed 实现延迟，任务引用同时保存在实例变量和静态变量中：
     * - 实例变量：onDestroy 时移除，避免 Activity 销毁后仍执行
     * - 静态变量：SettingsActivity 手动检查时可取消待执行的静默任务
     */
    private fun checkUpdateOnStartup() {
        if (!UpdateHelper.isAutoCheckEnabled(this)) return

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = Runnable {
            if (isDestroyed || isFinishing) return@Runnable
            UpdateHelper(this).checkForUpdate(silent = true)
            // 执行完毕后清理静态引用
            autoUpdateHandler = null
            autoUpdatePendingRunnable = null
        }

        // 保存到实例变量（onDestroy 清理用）
        autoUpdateCheckRunnable = runnable
        // 保存到静态变量（SettingsActivity 手动检查时取消用）
        autoUpdateHandler = handler
        autoUpdatePendingRunnable = runnable

        handler.postDelayed(runnable, AUTO_UPDATE_CHECK_DELAY_MS)
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
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentMultiChoiceType = position
                updateSortButtonVisibility(position)
                dismissMultiChoiceFragment()
                showMultiChoiceButton(position)
                // 关闭歌单展开的item
                if (position != 3) {
                    binding.viewPager.postDelayed({
                        closeExpandedPlaylistItem()
                    }, 100)
                }
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
     * 关闭歌单页中展开的 item
     * @return 是否成功关闭了item
     */
    fun closeExpandedPlaylistItem(): Boolean {
        supportFragmentManager.findFragmentByTag("f3")?.let { fragment ->
            if (fragment is PlaylistFragment) {
                return fragment.closeExpandedItem()
            }
        }
        return false
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

    private fun updateMultiChoiceVisibility(visible: Boolean) {
        binding.ivMultiChoice.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showMultiChoiceButton(position: Int) {
        if (position != 3) {
            updateMultiChoiceVisibility(viewModel.allSongs.value!!.isNotEmpty())
        } else {
            updateMultiChoiceVisibility(playlistViewModel.playlists.value!!.isNotEmpty())
        }
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
                    } else if (multiChoiceFragment != null) {
                        dismissMultiChoiceFragment()
                    } else {
                        if (closeExpandedPlaylistItem()) {
                            return
                        }
                        // 非搜索模式，执行默认返回行为
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            })
    }

    private var lastSearchTapTime = 0L
    private val clickViewRect = android.graphics.Rect()

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_UP) {
            if (multiChoiceFragment == null) {
                scrollToTop(binding.searchView, ev) { scrollCurrentTabToTop() }
            } else {
                scrollToTop(
                    multiChoiceFragment!!.getClickView(), ev
                ) { multiChoiceFragment!!.scrollToTop() }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun scrollToTop(clickView: View, ev: android.view.MotionEvent, action: Runnable) {
        // 获取clickView在屏幕上的区域
        clickView.getHitRect(clickViewRect)
        // 将触摸事件坐标转换到clickView的父坐标系
        val location = IntArray(2)
        clickView.getLocationOnScreen(location)
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        if (clickViewRect.contains(
                x - location[0] + clickViewRect.left, y - location[1] + clickViewRect.top
            )
        ) {
            // 点击在clickView范围内
            val canTrigger =
                (clickView is SearchView && clickView.isIconified) || clickView !is SearchView
            if (canTrigger) {
                val currentTime = System.currentTimeMillis()
                val doubleTapTimeout = android.view.ViewConfiguration.getDoubleTapTimeout()
                if (currentTime - lastSearchTapTime < doubleTapTimeout) {
                    // 双击检测：滚动当前标签页列表到顶部
                    action.run()
                    lastSearchTapTime = 0L
                } else {
                    lastSearchTapTime = currentTime
                }
            } else {
                lastSearchTapTime = 0L
            }
        }
    }

    private fun setupViewModel() {
        val repository = MusicRepository(this)
        val factory = MusicViewModelFactory(repository, this)
        viewModel = ViewModelProvider(this, factory)[MusicViewModel::class.java]

        val playlistFactory = PlaylistViewModelFactory(repository, this.application)
        playlistViewModel =
            ViewModelProvider(this, playlistFactory)[PlaylistViewModel::class.java]

        // 监听完整歌曲列表变化，当服务已绑定时自动同步排序后的列表到 MusicService
        // 这确保应用重启后，MusicService 的播放顺序与 UI 显示的排序一致
        viewModel.fullSongs.observe(this) { songs ->
            if (songs.isNotEmpty() && musicService != null) {
                updateServiceSongList()
            }
        }

        viewModel.allSongs.observe(this) { songs ->
            if (currentMultiChoiceType != 3) {
                updateMultiChoiceVisibility(songs.isNotEmpty())
            }
        }

        playlistViewModel.playlists.observe(this) { playlists ->
            if (currentMultiChoiceType == 3) {
                updateMultiChoiceVisibility(playlists.isNotEmpty())
            }
        }
    }

    /**
     * 将排序后的完整列表同步到 MusicService，确保播放顺序与 UI 一致。
     *
     * 注意：只有当当前播放来源是 f0（全部歌曲）或从未播放过时，才允许覆盖。
     * f1（歌手）、f2（专辑）、f3（歌单）的播放列表由各自 Activity 管理，
     * 进入其他界面不点击播放时，不应改变 MusicService 的播放列表。
     */
    private fun updateServiceSongList() {
        // 当前播放来源不是 f0 时，不覆盖播放列表
        val currentSong = musicService?.currentSong?.value
        if (currentSong != null && musicService?.isPlayingFromSongs() == false) return

        val sortedSongs = viewModel.getSortedFullSongs()
        if (sortedSongs.isEmpty()) return

        val currentIndex = if (currentSong != null) {
            sortedSongs.indexOfFirst { it.id == currentSong.id }.takeIf { it != -1 } ?: 0
        } else {
            0
        }
        musicService?.setSongList(sortedSongs, currentIndex)
    }

    private fun setupSearchView() {
        binding.searchView.queryHint = "输入歌曲、歌手、专辑关键字"

        // 设置 SearchView 内部输入框的 hint 文字颜色，确保可见
        val searchAutoComplete = binding.searchView.findViewById<
                android.widget.AutoCompleteTextView>(
            androidx.appcompat.R.id.search_src_text
        )
        searchAutoComplete?.setHintTextColor(
            androidx.core.content.ContextCompat.getColor(
                this, R.color.secondary
            )
        )

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

        // 多选按钮点击事件
        binding.ivMultiChoice.setOnClickListener {
            val currentPosition = binding.viewPager.currentItem
            currentMultiChoiceType = when (currentPosition) {
                0 -> 0
                1 -> 1
                2 -> 2
                3 -> 3
                else -> return@setOnClickListener
            }
            // 如果当前是歌单fragment，关闭展开的item
            if (currentMultiChoiceType == 3) {
                closeExpandedPlaylistItem()
            }
            // 显示多选fragment
            showMultiChoiceFragment(currentMultiChoiceType)
        }

        // 增大ivSort和ivSetting的点击范围
        setupTouchDelegate()
    }

    private fun setupTouchDelegate() {
        val expandPx = (48 * resources.displayMetrics.density).toInt()
        expandTouchTarget(binding.ivLoop, expandPx)
        expandTouchTarget(binding.ivSort, expandPx)
        expandTouchTarget(binding.ivSetting, expandPx)
        expandTouchTarget(binding.ivMultiChoice, expandPx)
    }

    // ==================== MultiChoiceFragment 相关 ====================

    private fun setupMultiChoiceFragment(fragmentType: Int) {
        multiChoiceFragment = MultiChoiceFragment()
        val args = Bundle()
        args.putInt("type", fragmentType)
        multiChoiceFragment?.arguments = args
    }

    override fun onDeleteSelected(selectedSongIds: Set<Long>) {
        showDeleteConfirmDialog(selectedSongIds)
    }

    override fun onAddToPlaylist(selectedSongIds: Set<Long>) {
        handleAddToPlaylist(selectedSongIds)
    }

    override fun onExportSelected(selectedPlaylistIds: Set<Long>) {
        if (selectedPlaylistIds.isEmpty() || isExportingPlaylists) return
        isExportingPlaylists = true
        multiChoiceFragment?.setExportButtonsEnabled(false)
        pendingExportPlaylistIds = selectedPlaylistIds
        ensurePlaylistExportDirAndExport()
    }

    /** 检查 SAF 权限 + 确保目录存在，随后执行批量导出 */
    private fun ensurePlaylistExportDirAndExport() {
        if (!PlaylistFileManager.hasPermission(this)) {
            playlistExportTreeLauncher.launch(PlaylistFileManager.getInitialUri())
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val ready = PlaylistFileManager.ensureSaveDirExists(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (ready) {
                    checkExportLimitAndExport(pendingExportPlaylistIds)
                } else {
                    Toast.makeText(this@MainActivity, "授权目录创建失败", Toast.LENGTH_SHORT).show()
                    finishPlaylistExportState()
                }
            }
        }
    }

    /**
     * 批量导出预检（参照均衡器）：
     * - 存在同名（不同 playlistId）导出文件 → 先弹同名处理弹窗，用户选择
     *   合并 / 覆盖后继续；取消则停留多选页并恢复导出按钮；
     * - 解决冲突后仍超上限（或本就超上限）→ 弹清理弹窗，删除后需重新点击导出；
     * - 覆盖导出（同 playlistId 文件已存在）不占新名额。
     */
    private fun checkExportLimitAndExport(ids: Set<Long>) {
        playlistViewModel.precheckExport(ids) { pre ->
            if (pre.conflicts.isNotEmpty()) {
                PlaylistExportConflictDialog.show(
                    this@MainActivity, pre.conflicts
                ) { merge ->
                    if (merge == null) {
                        // 用户取消同名处理：停留多选页，恢复导出按钮
                        finishPlaylistExportState()
                        return@show
                    }
                    if (pre.overLimit) {
                        Toast.makeText(
                            this@MainActivity,
                            "导出文件已达上限 ${PlaylistFileManager.MAX_EXPORT_COUNT} 个，请先清理",
                            Toast.LENGTH_SHORT
                        ).show()
                        finishPlaylistExportState()
                        PlaylistExportCleanupDialog.show(this@MainActivity, lifecycleScope)
                    } else {
                        performPlaylistExport(merge)
                    }
                }
            } else if (pre.overLimit) {
                Toast.makeText(
                    this@MainActivity,
                    "导出文件已达上限 ${PlaylistFileManager.MAX_EXPORT_COUNT} 个，请先清理",
                    Toast.LENGTH_SHORT
                ).show()
                finishPlaylistExportState()
                PlaylistExportCleanupDialog.show(this@MainActivity, lifecycleScope)
            } else {
                performPlaylistExport(false)
            }
        }
    }

    private fun performPlaylistExport(mergeConflicts: Boolean = false) {
        val ids = pendingExportPlaylistIds
        if (ids.isEmpty()) return
        playlistViewModel.exportPlaylists(ids, mergeConflicts) { success, failed ->
            val msg = if (failed == 0) {
                "已导出 $success 个歌单"
            } else {
                "成功 $success 个，失败 $failed 个"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            // 无论成败退出多选页，并重置防连点状态
            dismissMultiChoiceFragment()
            finishPlaylistExportState()
        }
    }

    /** 恢复导出可点状态（停留多选页时使用） */
    private fun finishPlaylistExportState() {
        isExportingPlaylists = false
        pendingExportPlaylistIds = emptySet()
        multiChoiceFragment?.setExportButtonsEnabled(true)
    }

    override fun onCancel() {
        dismissMultiChoiceFragment()
    }

    private fun showMultiChoiceFragment(fragmentType: Int) {
        bottomPlayerController.hide()
        currentMultiChoiceType = fragmentType
        setupMultiChoiceFragment(fragmentType)
        supportFragmentManager.beginTransaction()
            .replace(R.id.multiChoiceFragmentContainer, multiChoiceFragment!!, "MCF")
            .commitNow()
    }

    private fun dismissMultiChoiceFragment() {
        if (multiChoiceFragment == null) {
            multiChoiceFragment = supportFragmentManager.findFragmentByTag("MCF") as MultiChoiceFragment?
        }

        if (multiChoiceFragment == null) {
            return
        }
        supportFragmentManager.beginTransaction()
            .remove(multiChoiceFragment!!)
            .commitNow()
        bottomPlayerController.show()
        resetMultiChoice()
    }

    private fun resetMultiChoice() {
        multiChoiceFragment = null
    }

    private fun showDeleteConfirmDialog(selectedSongIds: Set<Long>) {
        var title = ""
        var message = ""
        when (currentMultiChoiceType) {
            0 -> {
                title = "删除歌曲"
                message = "确定要从列表中删除所选的歌曲吗？\n\n注意：这不会删除本地文件。"
            }

            3 -> {
                title = "删除歌单"
                message = "确定要删除所选的歌单吗？此操作不可恢复。"
            }
        }

        val errorColor = this.getColor(R.color.error)
        val secondaryColor = this.getColor(R.color.secondary)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("删除") { _, _ ->
                // 通过回调通知外部处理删除
                handleDeleteSelected(selectedSongIds)
                Toast.makeText(this, "已从列表中删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
        // 设置删除按钮文字颜色为红色（error 色），取消按钮为 secondary 色
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(errorColor)
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setTextColor(secondaryColor)
    }

    private fun handleDeleteSelected(selectedSongIds: Set<Long>) {
        when (currentMultiChoiceType) {
            0 -> {
                lifecycleScope.launch {
                    val clone = selectedSongIds.toMutableSet()
                    for (songId in clone) {
                        if (musicService?.currentSong?.value?.id == songId) {
                            musicService?.removeCurrentSong()
                        }
                        viewModel.hideSong(songId)
                    }
                    updateServiceSongList()
                }
            }

            3 -> {
                lifecycleScope.launch {
                    val repository = MusicRepository(this@MainActivity)
                    val clone = selectedSongIds.toMutableSet()
                    for (playlistId in clone) {
                        repository.deletePlaylistById(playlistId)
                    }
                    // 如果删除的歌单包含当前正在播放的歌单，清理播放状态
                    if (clone.isNotEmpty()) {
                        musicService?.handlePlaylistDeleted(clone)
                    }
                    PlaylistRefresher.notifyPlaylistsChanged()
                }
            }

            else -> {
                // ArtistFragment 和 AlbumFragment 不支持删除
            }
        }
        dismissMultiChoiceFragment()
    }

    private fun handleAddToPlaylist(selectedSongIds: Set<Long>) {
        lifecycleScope.launch {
            val repository = MusicRepository(this@MainActivity)
            val playlists = withContext(Dispatchers.IO) {
                repository.getAllPlaylists().firstOrNull()
            } ?: emptyList()
            if (playlists.isEmpty()) {
                EmptyPlaylistActionDialog(this@MainActivity) {
                    handleAddToPlaylist(selectedSongIds)
                }.show()
                return@launch
            }

            val songCounts = withContext(Dispatchers.IO) {
                val counts = mutableMapOf<Long, Int>()
                playlists.forEach { playlist ->
                    val songs = repository.getPlaylistSongs(playlist.id).firstOrNull()
                    counts[playlist.id] = songs?.size ?: 0
                }
                counts
            }

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
                    addSelectedSongsToPlaylists(
                        selectedSongIds,
                        chosenPlaylistIds,
                        repository
                    )
                }
            ).show()
        }
    }

    private fun addSelectedSongsToPlaylists(
        selectedIds: Set<Long>,
        playlistIds: List<Long>,
        repository: MusicRepository
    ) {
        if (playlistIds.isEmpty()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                playlistIds.forEach { playlistId ->
                    repository.addSongsToPlaylist(playlistId, selectedIds.mapNotNull { id ->
                        viewModel.fullSongs.value?.find { it.id == id }
                    })
                }
            }
            Toast.makeText(
                this@MainActivity,
                "已添加到 ${playlistIds.size} 个歌单",
                Toast.LENGTH_SHORT
            ).show()
            PlaylistRefresher.notifyPlaylistsChanged()
        }
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
            // 列表为空时不调用 playNext()，避免 playNext() 再次触发 requestSongList 形成无限循环
            if (songs.isEmpty()) return@let
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

    private fun bindMusicService(restoreFlag: Int) {
        // 注册服务连接回调，在异步绑定完成时收到通知
        MusicManager.registerConnectionCallback(this)
        // 使用MusicManager绑定MusicService
        val alreadyConnected = MusicManager.bind(this)
        // 获取MusicService实例（如果服务已绑定则立即返回，否则需等待回调）
        musicService = MusicManager.getService()
        if (alreadyConnected && musicService != null) {
            // 服务已连接（非首次绑定），直接执行初始化
            onServiceConnected(musicService)
        }
        // 通过 startService 传递 KEY_CREATE/KEY_RESTORE 标记，
        // 让 MusicService.onStartCommand 的 else 分支执行 loadPlaybackState
        val serviceIntent = Intent(this, MusicService::class.java).apply {
            putExtra(KEY_CREATE, "1")
            putExtra(KEY_RESTORE, restoreFlag)
        }
        startService(serviceIntent)
    }

    // ==================== MusicManager.ConnectionCallback 实现 ====================

    override fun onServiceConnected(service: MusicService?) {
        musicService = service ?: return
        musicService?.syncCurrentPositionToDataStore()
        setupBottomPlayerObservers()
        updateBottomPlayerUI()
        // 服务连接后同步排序后的歌曲列表，确保播放顺序与UI一致
        if (viewModel.fullSongs.value?.isNotEmpty() == true) {
            updateServiceSongList()
        }
        // 通知歌曲 Fragment 服务已连接
        getSongsFragment()?.onServiceConnected()
    }

    override fun onServiceDisconnected() {
        musicService = null
        removeBottomPlayerObservers()
        getSongsFragment()?.onServiceDisconnected()
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
            if (musicService != null) {
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

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // 主题切换导致 Activity 重建时，清除 SearchView 残留的搜索文字
        exitSearchMode()
    }

    override fun onResume() {
        super.onResume()
        // 更新UI状态
        if (musicService != null) {
            updateBottomPlayerUI()
        }
    }

    override fun onPause() {
        super.onPause()
        musicService?.savePlaybackState()
    }

    override fun onDestroy() {
        // 移除自动检查更新的延迟任务，避免 Activity 销毁后仍执行
        autoUpdateCheckRunnable?.let { binding.root.removeCallbacks(it) }
        autoUpdateCheckRunnable = null

        // 清理静态 Handler 引用，避免内存泄漏
        cancelPendingAutoUpdateCheck()

        // 清理观察者，避免内存泄漏
        removeBottomPlayerObservers()

        // 注销服务连接回调
        MusicManager.unregisterConnectionCallback(this)

        // 使用MusicManager解绑
        MusicManager.unbind(this)

        super.onDestroy()
    }

    // 由 SongsFragment 转发的更多操作事件
    override fun onMoreClick(song: Song, position: Int) {
        // 先异步查询歌单，再决定是否显示"添加到歌单"选项
        lifecycleScope.launch {
            songInfoHelper.showSongInfoDialog(
                song,
                showDeleteOption = true,
                showAddToPlaylist = true
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
     * 写入权限请求回调
     */
    private fun setupWritePermissionCallback() {
        songInfoHelper.writePermissionCallback = object : AudioTagEditor.WritePermissionCallback {
            override fun onRequestWritePermission(intentSender: IntentSender, requestCode: Int) {
                writePermissionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
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
                EmptyPlaylistActionDialog(this@MainActivity) {
                    showSelectPlaylistDialog(song)
                }.show()
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
                        musicService?.syncPlaylistSongList(playlist.id, songs)
                    }
                }
            }
        }
    }
}
