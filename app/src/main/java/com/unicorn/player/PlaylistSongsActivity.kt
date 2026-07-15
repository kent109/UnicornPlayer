package com.unicorn.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.adapter.SongAdapter
import com.unicorn.player.adapter.SongAdapter.OnSongClickListener
import com.unicorn.player.adapter.SongAdapter.OnSongMoreClickListener
import com.unicorn.player.databinding.ActivityPlaylistSongsBinding
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.ui.SelectSongsDialog
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory

/**
 * 歌单歌曲列表 Activity
 *
 * 展示某个歌单的全部歌曲，复用 item_song.xml 布局（SongAdapter）；
 * 底部「添加」按钮 → 弹出全量歌曲选择框（SelectSongsDialog），
 * 确定后合并添加（REPLACE 去重）。
 */
class PlaylistSongsActivity : AppCompatActivity(), OnSongClickListener, OnSongMoreClickListener {

    private lateinit var binding: ActivityPlaylistSongsBinding
    private lateinit var viewModel: MusicViewModel
    private lateinit var songAdapter: SongAdapter
    private lateinit var songInfoHelper: SongInfoHelper

    private var playlistId: Long = -1L
    private var playlistName: String = ""

    // 底部播放栏控制器
    private lateinit var bottomPlayerController: BottomPlayerController

    // 当前歌单的歌曲列表（排序后），用于播放时设置给 MusicService
    private var playlistSongs: List<Song> = emptyList()

    // 服务绑定
    private var musicService: MusicService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (isDestroyed || isFinishing) return
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isServiceBound = true
            observeMusicService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isServiceBound = false
            removeMusicServiceObservers()
        }
    }

    // 服务观察者
    private var currentSongObserver: Observer<Song?>? = null
    private var isPlayingObserver: Observer<Boolean>? = null

    companion object {
        const val TAG = "PlaylistSongsActivity"
        const val EXTRA_PLAYLIST_ID = "extra_playlist_id"
        const val EXTRA_PLAYLIST_NAME = "extra_playlist_name"

        fun newIntent(context: Context, playlistId: Long, playlistName: String): Intent {
            return Intent(context, PlaylistSongsActivity::class.java).apply {
                putExtra(EXTRA_PLAYLIST_ID, playlistId)
                putExtra(EXTRA_PLAYLIST_NAME, playlistName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistSongsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, -1L)
        playlistName = intent.getStringExtra(EXTRA_PLAYLIST_NAME) ?: ""

        songInfoHelper = SongInfoHelper(this)
        songInfoHelper.onDeleteListener = object : SongInfoHelper.OnDeleteListener {
            override fun onDelete(song: Song) {
                // 从当前歌单移除该歌曲；ViewModel reload 后列表自动刷新
                viewModel.removeSongFromPlaylist(playlistId, song.id)
                setResult(RESULT_OK)
            }
        }

        // 设置 TitleBar
        binding.titleBar.setTitle(playlistName)
        binding.titleBar.setOnBackClickListener { finish() }

        setupViewModel()
        setupRecyclerView()
        setupSmartRefreshLayout()
        setupBottomPlayer()
        setupAddButton()

        bindMusicService()
    }

    private fun setupAddButton() {
        binding.btAddSongs.setOnClickListener {
            showSelectSongsDialog()
        }
    }

    /**
     * 弹出添加歌曲弹窗；外部传入已收集的全量歌曲与歌单内已有 songId（预勾选）
     */
    private fun showSelectSongsDialog() {
        // 收集全量歌曲与歌单内已有 songId（同步方式通过已缓存的 list）
        val allSongs = viewModel.getSortedFullSongs()
        val alreadySelectedIds = playlistSongs.map { it.id }
        SelectSongsDialog(
            context = this,
            playlistId = playlistId,
            playlistName = playlistName,
            allSongs = allSongs,
            alreadySelectedIds = alreadySelectedIds,
            onConfirm = { newName, chosenIds ->
                // 改名回调
                if (newName != playlistName) {
                    playlistName = newName
                    binding.titleBar.setTitle(playlistName)
                    viewModel.renamePlaylistName(playlistId, newName)
                }
                // 合并添加（REPLACE 去重）：用 id 反查 Song 对象
                val idToSong = allSongs.associateBy { it.id }
                val toAdd = chosenIds.mapNotNull { idToSong[it] }
                viewModel.addSongsToPlaylist(playlistId, toAdd)
                // 通知父 Fragment 歌曲列表有改动，返回后主动刷新歌曲数量/更新时间
                setResult(RESULT_OK)
            }
        ).show()
    }

    /**
     * 初始化底部播放栏控制器
     */
    private fun setupBottomPlayer() {
        bottomPlayerController = BottomPlayerController(
            this,
            binding.bottomPlayer
        ) { musicService }
        bottomPlayerController.setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        observeMusicService()
        musicService?.let { service ->
            songAdapter.isPlaying = service.isPlaying.value == true
            songAdapter.currentPlayingSong = service.currentSong.value
            songAdapter.isPaused = false
            songAdapter.resumeCurrentSongAnimation()
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
        bottomPlayerController.removeObservers()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun setupViewModel() {
        val factory = MusicViewModelFactory(MusicRepository(this), this)
        viewModel = ViewModelProvider(this, factory)[MusicViewModel::class.java]

        // 加载歌单歌曲
        viewModel.loadPlaylistSongs(playlistId)

        // 监听歌单歌曲列表变化
        viewModel.playlistSongs.observe(this) { songs ->
            submitPlaylistSongs(songs)
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun submitPlaylistSongs(songs: List<Song>) {
        playlistSongs = songs
        songAdapter.submitList(songs)
        updateSongCount(songs.size)
    }

    private fun updateSongCount(count: Int) {
        binding.titleBar.setTitle("$playlistName ($count 首)")
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(this, this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlaylistSongsActivity)
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
        val intent = Intent(this, MusicService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeMusicService() {
        val service = musicService ?: return
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
        service.currentSong.observe(this, currentSongObserver!!)
        service.isPlaying.observe(this, isPlayingObserver!!)

        bottomPlayerController.observe(service)
    }

    private fun removeMusicServiceObservers() {
        bottomPlayerController.removeObservers()
        val service = musicService
        currentSongObserver?.let { service?.currentSong?.removeObserver(it) }
        isPlayingObserver?.let { service?.isPlaying?.removeObserver(it) }
        currentSongObserver = null
        isPlayingObserver = null
    }

    // ==================== OnSongClickListener ====================

    override fun onSongClick(song: Song, position: Int) {
        if (isServiceBound) {
            val index = playlistSongs.indexOfFirst { it.id == song.id }.takeIf { it != -1 } ?: position
            musicService?.setSongList(playlistSongs, index)
            if (musicService?.currentSong?.value?.id == song.id) {
                if (musicService?.isPlaying?.value != true) {
                    musicService?.requestAudioFocusAndPlay()
                    musicService?.updateNotification()
                }
            } else {
                musicService?.requestAudioFocusAndPlayCurrentSong()
                musicService?.updateNotification()
            }
        } else {
            // 服务未绑定，通过 Intent 启动服务播放
            val intent = Intent(this, MusicService::class.java).apply {
                action = MusicService.ACTION_PLAY
                putExtra("songId", song.id)
                putExtra("position", position)
                putExtra("songListSize", playlistSongs.size)
                val songDataList = playlistSongs.map { s ->
                    "${s.id}|${s.title}|${s.artist}|${s.album}|${s.duration}|${s.path}|${s.albumArt ?: ""}"
                }
                putStringArrayListExtra("songList", ArrayList(songDataList))
            }
            startService(intent)
        }
    }

    override fun onMoreClick(song: Song, position: Int) {
        songInfoHelper.showSongInfoDialog(song)
    }
}
