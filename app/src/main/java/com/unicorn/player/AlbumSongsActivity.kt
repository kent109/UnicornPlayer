package com.unicorn.player

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.adapter.SongAdapter
import com.unicorn.player.adapter.SongAdapter.OnSongClickListener
import com.unicorn.player.adapter.SongAdapter.OnSongMoreClickListener
import com.unicorn.player.databinding.ActivityAlbumSongsBinding
import com.unicorn.player.manager.MusicManager
import com.unicorn.player.model.Playlist
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.service.PlaySource
import com.unicorn.player.ui.PlaylistRefresher
import com.unicorn.player.ui.SelectPlaylistDialog
import com.unicorn.player.ui.SongMultiChoiceFragment
import com.unicorn.player.util.AudioTagEditor
import com.unicorn.player.util.ScrollToTopHelper
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 专辑歌曲列表 Activity
 * 展示某个专辑的全部歌曲，复用 item_song.xml 布局（通过 SongAdapter），底部带播放条。
 */
class AlbumSongsActivity : SongMultiChoiceBaseActivity(), OnSongClickListener,
    OnSongMoreClickListener,
    MusicManager.ConnectionCallback {

    private lateinit var binding: ActivityAlbumSongsBinding
    private lateinit var songAdapter: SongAdapter
    private lateinit var songInfoHelper: SongInfoHelper

    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            songInfoHelper.retryPendingWrite()
        }
    }

    private var albumName: String = ""

    // 当前专辑的歌曲列表（排序后），用于播放时设置给 MusicService
    private var albumSongs: List<Song> = emptyList()

    // 服务观察者
    private var currentSongObserver: Observer<Song?>? = null
    private var isPlayingObserver: Observer<Boolean>? = null

    companion object {
        const val TAG = "AlbumSongsActivity"
        const val EXTRA_ALBUM_NAME = "extra_album_name"

        fun newIntent(context: Context, albumName: String): Intent {
            return Intent(context, AlbumSongsActivity::class.java).apply {
                putExtra(EXTRA_ALBUM_NAME, albumName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumSongsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        albumName = intent.getStringExtra(EXTRA_ALBUM_NAME) ?: ""

        songInfoHelper = SongInfoHelper(this)
        setupAddToPlaylistListener()
        setupWritePermissionCallback()

        // 设置 TitleBar
        binding.titleBar.setTitle(albumName)
        binding.titleBar.setOnBackClickListener { finish() }
        multiChoiceView = LayoutInflater.from(this).inflate(R.layout.multi_choice_view, null)
        binding.titleBar.addViewToRight(multiChoiceView!!)

        setupViewModel()
        setupRecyclerView()
        setupSmartRefreshLayout()
        setupBottomPlayer()
        setupBackPressHandler()
        handleRecreate(savedInstanceState)

        bindMusicService()
    }

    /**
     * 初始化底部播放栏控制器，封装播放栏点击跳转与播放/上下首按钮事件
     */
    private fun setupBottomPlayer() {
        bottomPlayerController = BottomPlayerController(
            this,
            binding.bottomPlayer
        ) { musicService }
        bottomPlayerController.setupClickListeners()

        // 多选按钮点击事件
        multiChoiceView!!.setOnClickListener {
            showSongMultiChoiceFragment(SongMultiChoiceFragment.FT_ALBUM, albumSongs)
        }
    }

    override fun onResume() {
        super.onResume()
        // 重新注册观察者（onPause 中已移除），LiveData 会立即把当前值投递给新观察者，
        // 触发 notifyDataSetChanged()，刷新高亮与动画，使列表与当前播放歌曲一致。
        observeMusicService()
        musicService?.let { service ->
            songAdapter.isPlaying = service.isPlaying.value == true
            songAdapter.currentPlayingSong = service.currentSong.value
            songAdapter.isPaused = false
            songAdapter.resumeCurrentSongAnimation()
            // 同步底部播放栏状态
            bottomPlayerController.updateUI(service)
        }
    }

    override fun onPause() {
        super.onPause()
        musicService?.savePlaybackState()
        songAdapter.isPaused = true
        songAdapter.pauseCurrentSongAnimation()
        removeMusicServiceObservers()
    }

    override fun onDestroy() {
        super.onDestroy()
        songAdapter.stopCurrentSongAnimation()
        // 确保底部播放栏观察者被清理（服务未断开时 onPause 已处理）
        bottomPlayerController.removeObservers()
        // 注销服务连接回调并解绑
        MusicManager.unregisterConnectionCallback(this)
        MusicManager.unbind(this)
    }

    private fun setupViewModel() {
        val factory = MusicViewModelFactory(MusicRepository(this), this)
        viewModel = ViewModelProvider(this, factory)[MusicViewModel::class.java]

        // 监听完整歌曲列表，按专辑名过滤后提交
        viewModel.allSongs.observe(this) { songs ->
            submitAlbumSongs(songs)
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    /**
     * 按专辑名过滤歌曲，按全局排序模式排序后提交
     */
    private fun submitAlbumSongs(songs: List<Song>) {
        // 不区分大小写匹配专辑名；展示顺序跟随主界面 ivSort 所选排序模式
        val filtered = songs.filter {
            it.album.equals(albumName, ignoreCase = true)
        }
        val sorted = viewModel.sortWithCurrentMode(filtered)

        albumSongs = sorted
        songAdapter.submitList(sorted)
        updateSongCount(sorted.size)
    }

    private fun updateSongCount(count: Int) {
        binding.titleBar.setTitle("$albumName ($count 首)")
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(this, this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@AlbumSongsActivity)
            adapter = songAdapter
            songAdapter.setRecyclerView(this)
        }
    }

    private fun setupSmartRefreshLayout() {
        binding.smartRefreshLayout.apply {
            setEnableOverScrollBounce(true)
            setEnableOverScrollDrag(true)
            setEnableRefresh(false)
        }
    }

    private fun bindMusicService() {
        MusicManager.registerConnectionCallback(this)
        val alreadyConnected = MusicManager.bind(this)
        musicService = MusicManager.getService()
        if (alreadyConnected && musicService != null) {
            onServiceConnected(musicService)
        }
    }

    // ==================== MusicManager.ConnectionCallback 实现 ====================

    override fun onServiceConnected(service: MusicService?) {
        if (isDestroyed || isFinishing) return
        musicService = service ?: return
        observeMusicService()
    }

    override fun onServiceDisconnected() {
        musicService = null
        removeMusicServiceObservers()
    }

    /**
     * 观察 MusicService 的播放状态：
     * - 旋转动画与当前歌曲高亮（列表适配器）
     * - 底部播放栏（委托 BottomPlayerController）
     */
    private fun observeMusicService() {
        val service = musicService ?: return
        removeMusicServiceObservers()

        // 列表适配器观察者：旋转动画与当前歌曲高亮
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
        service.currentSong.observe(this, currentSongObserver!!)
        service.isPlaying.observe(this, isPlayingObserver!!)

        // 底部播放栏观察者
        bottomPlayerController.observe(service)
    }

    private fun removeMusicServiceObservers() {
        // 底部播放栏观察者
        bottomPlayerController.removeObservers()
        val service = musicService
        currentSongObserver?.let { service?.currentSong?.removeObserver(it) }
        isPlayingObserver?.let { service?.isPlaying?.removeObserver(it) }
        currentSongObserver = null
        isPlayingObserver = null
    }

    // ==================== OnSongClickListener ====================

    override fun onSongClick(song: Song, position: Int) {
        // 以当前专辑的歌曲列表作为播放列表，确保上下曲仅在专辑内切换
        val index =
            albumSongs.indexOfFirst { it.id == song.id }.takeIf { it != -1 } ?: position
        musicService?.setSongList(albumSongs, index)
        // 专辑详情作为播放入口：来源为 f2$专辑名
        musicService?.setPlaySource(
            PlaySource.build(PlaySource.ALBUM, albumName)
        )
        if (musicService?.currentSong?.value?.id == song.id) {
            if (musicService?.isPlaying?.value != true) {
                musicService?.requestAudioFocusAndPlay()
                musicService?.updateNotification()
            }
        } else {
            musicService?.requestAudioFocusAndPlayCurrentSong()
            musicService?.updateNotification()
        }
    }

    override fun onMoreClick(song: Song, position: Int) {
        // 先异步查询歌单，再决定是否显示"添加到歌单"选项
        lifecycleScope.launch {
            songInfoHelper.showSongInfoDialog(
                song,
                showDeleteOption = false,
                showAddToPlaylist = true
            )
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
            val repository = MusicRepository(this@AlbumSongsActivity)
            val playlists = withContext(Dispatchers.IO) {
                repository.getAllPlaylists().firstOrNull()
            } ?: emptyList()
            if (playlists.isEmpty()) {
                Toast.makeText(this@AlbumSongsActivity, "暂无歌单", Toast.LENGTH_SHORT).show()
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
            val metrics = resources.displayMetrics
            val centerX = metrics.widthPixels / 2f
            val centerY = metrics.heightPixels / 2f
            SelectPlaylistDialog(
                context = this@AlbumSongsActivity,
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
                this@AlbumSongsActivity,
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
            setResult(RESULT_OK)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_UP) {
            ScrollToTopHelper.scrollToTop(
                binding.titleBar,
                ev
            ) { scrollToTop() }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun scrollToTop() {
        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager
        if (layoutManager != null && layoutManager.itemCount > 0) {
            binding.recyclerView.smoothScrollToPosition(0)
        }
    }
}
