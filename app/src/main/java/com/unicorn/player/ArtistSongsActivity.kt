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
import com.unicorn.player.databinding.ActivityArtistSongsBinding
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory

/**
 * 歌手歌曲列表 Activity
 * 展示某个歌手的全部歌曲，复用 item_song.xml 布局（通过 SongAdapter）
 */
class ArtistSongsActivity : AppCompatActivity(), OnSongClickListener, OnSongMoreClickListener {

    private lateinit var binding: ActivityArtistSongsBinding
    private lateinit var viewModel: MusicViewModel
    private lateinit var songAdapter: SongAdapter
    private lateinit var songInfoHelper: SongInfoHelper

    private var artistName: String = ""

    // 底部播放栏控制器，封装播放栏的按钮事件、观察者与 UI 更新
    private lateinit var bottomPlayerController: BottomPlayerController

    // 当前歌手的歌曲列表（排序后），用于播放时设置给 MusicService
    private var artistSongs: List<Song> = emptyList()

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
        const val TAG = "ArtistSongsActivity"
        const val EXTRA_ARTIST_NAME = "extra_artist_name"

        fun newIntent(context: Context, artistName: String): Intent {
            return Intent(context, ArtistSongsActivity::class.java).apply {
                putExtra(EXTRA_ARTIST_NAME, artistName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArtistSongsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        artistName = intent.getStringExtra(EXTRA_ARTIST_NAME) ?: ""

        songInfoHelper = SongInfoHelper(this)

        // 设置 TitleBar
        binding.titleBar.setTitle(artistName)
        binding.titleBar.setOnBackClickListener { finish() }

        setupViewModel()
        setupRecyclerView()
        setupSmartRefreshLayout()
        setupBottomPlayer()

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
    }

    override fun onResume() {
        super.onResume()
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
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun setupViewModel() {
        val factory = MusicViewModelFactory(MusicRepository(this), this)
        viewModel = ViewModelProvider(this, factory)[MusicViewModel::class.java]

        // 监听完整歌曲列表，按歌手名过滤后提交
        viewModel.allSongs.observe(this) { songs ->
            submitArtistSongs(songs)
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    /**
     * 按歌手名过滤歌曲，按标题排序后提交
     */
    private fun submitArtistSongs(songs: List<Song>) {
        // 不区分大小写匹配艺术家
        val filtered = songs.filter {
            it.artist.equals(artistName, ignoreCase = true)
        }.sortedBy { it.title.lowercase() }

        artistSongs = filtered
        songAdapter.submitList(filtered)
        updateSongCount(filtered.size)
    }

    private fun updateSongCount(count: Int) {
        binding.titleBar.setTitle("$artistName ($count 首)")
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(this, this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@ArtistSongsActivity)
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
        if (isServiceBound) {
            // 以当前歌手的歌曲列表作为播放列表，确保上下曲仅在歌手内切换
            val index = artistSongs.indexOfFirst { it.id == song.id }.takeIf { it != -1 } ?: position
            musicService?.setSongList(artistSongs, index)
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
                putExtra("songListSize", artistSongs.size)
                val songDataList = artistSongs.map { s ->
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
