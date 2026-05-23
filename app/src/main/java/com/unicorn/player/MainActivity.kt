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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.unicorn.player.databinding.ActivityMainBinding
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.MusicService
import com.unicorn.player.viewmodel.MusicViewModel
import com.unicorn.player.viewmodel.MusicViewModelFactory

class MainActivity : AppCompatActivity(), SongAdapter.OnSongClickListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MusicViewModel
    private lateinit var songAdapter: SongAdapter

    private var musicService: MusicService? = null
    private var isServiceBound = false

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
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isServiceBound = true
            setupBottomPlayerObservers()  // 服务连接成功后设置观察者
            updateBottomPlayerUI()  // 立即更新UI状态
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewModel()
        setupRecyclerView()
        setupSearchView()
        setupBottomPlayer()

        checkPermissions()
        bindMusicService()
    }

    private fun setupViewModel() {
        val repository = MusicRepository(this)
        val factory = MusicViewModelFactory(repository)
        viewModel = ViewModelProvider(this, factory)[MusicViewModel::class.java]

        // Observe LiveData after ViewModel is ready
        viewModel.allSongs.observe(this) { songs ->
            if (::songAdapter.isInitialized) {
                songAdapter.submitList(songs)
            }
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility =
                if (isLoading) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun setupRecyclerView() {
        songAdapter = SongAdapter(this)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = songAdapter
        }
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
    }

    private fun setupBottomPlayer() {
        binding.bottomPlayer.setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java)
            startActivity(intent)
        }

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

    private fun setupBottomPlayerObservers() {
        // Observe playing state to update play button icon
        musicService?.isPlaying?.observe(this) { isPlaying ->
            binding.playButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        }

        // Observe current song to update bottom player info
        musicService?.currentSong?.observe(this) { song ->
            song?.let { updateBottomPlayer(it) }
        }

        // 监听文件变化，自动刷新列表
        musicService?.fileChanged?.observe(this) {
            loadMusic()
        }
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
    }

    private fun checkPermissions() {
        // 先检查通知权限（Android 13及以上）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 检查存储权限
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                loadMusic()
            }

            ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.READ_MEDIA_AUDIO
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

    private fun bindMusicService() {
        val intent = Intent(this, MusicService::class.java)
        // 先startService确保服务在前台运行
        startService(intent)
        // 再bindService确保能正确绑定
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun ensureServiceRunning() {
        val intent = Intent(this, MusicService::class.java)
        startService(intent)
    }

    override fun onSongClick(song: Song, position: Int) {
        viewModel.allSongs.value?.let { songs ->
            // 使用binder方式与服务通信
            if (isServiceBound) {
                musicService?.setSongList(songs, position)
                musicService?.requestAudioFocusAndPlayCurrentSong()
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
            }

            updateBottomPlayer(song)
        }
    }

    private fun updateBottomPlayer(song: Song) {
        binding.bottomPlayer.visibility = android.view.View.VISIBLE
        binding.songTitle.text = song.title
        binding.artistName.text = song.artist

        // 使用Glide加载专辑封面并添加圆角
        Glide.with(this)
            .load(song.albumArt)
            .placeholder(R.drawable.ic_music_note)
            .error(R.drawable.ic_music_note)
            .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(20))
            .into(binding.albumArt)
    }

    override fun onResume() {
        super.onResume()
        ensureServiceRunning()  // 确保服务在运行
        // Update UI with current playing song
        setupBottomPlayerObservers()  // 重新设置观察者
        updateBottomPlayerUI()  // 更新UI状态
    }

    override fun onPause() {
        super.onPause()
        musicService?.savePlaybackState()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}