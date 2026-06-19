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

class MainActivity : AppCompatActivity(), SongAdapter.OnSongClickListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MusicViewModel
    private lateinit var songAdapter: SongAdapter

    private var musicService: MusicService? = null
    private var isServiceBound = false

    private val hideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hideRunnable: Runnable = Runnable {
        binding.btnScrollToCurrent.visibility = android.view.View.GONE
    }

    // 将 scrollToContentClick 提升为类级别变量，解决作用域问题
    private var scrollToContentClick = false

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
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isServiceBound = true
            setupBottomPlayerObservers()  // 服务连接成功后设置观察者
            updateBottomPlayerUI()  // 立即更新UI状态
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

        // 添加异常处理，避免crash
        try {
            setupViewModel()
            setupRecyclerView()
            setupSmartRefreshLayout()
            setupSearchView()
            setupBottomPlayer()

            checkPermissions()
            bindMusicService()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error", e)
            // 如果出错，尝试重新初始化
            finish()
            startActivity(Intent(this, MainActivity::class.java))
        }
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
            // 设置RecyclerView 引用，以便 Adapter 能够找到 ViewHolder
            songAdapter.setRecyclerView(this@apply)
        }

        // 初始化滚动状态监听
        setupScrollStateListener()
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

        // 设置下拉刷新监听
        smartRefreshLayout.setOnRefreshListener {
            Log.d(TAG, "下拉刷新触发")
            // 重新加载音乐文件
            loadMusic()
            // 模拟刷新延迟
            smartRefreshLayout.finishRefresh(1000)
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
                Log.d(TAG, "onScrolled, isScrolling=$isScrolling")
                // 滑动过程中保持按钮隐藏
                if (isScrolling) {
                    binding.btnScrollToCurrent.visibility = android.view.View.GONE
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

    // 保存观察者的引用，以便在重新连接时移除旧的观察者
    private var isPlayingObserver: androidx.lifecycle.Observer<Boolean>? = null
    private var currentSongObserver: androidx.lifecycle.Observer<com.unicorn.player.model.Song?>? =
        null
    private var fileChangedObserver: androidx.lifecycle.Observer<Unit>? = null

    private fun setupBottomPlayerObservers() {
        // 先移除旧的观察者，避免重复注册
        removeBottomPlayerObservers()

        // Observe playing state to update play button icon and animation
        isPlayingObserver = androidx.lifecycle.Observer { isPlaying ->
            binding.playButton.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            // 更新Adapter的播放状态以控制动画
            songAdapter.isPlaying = isPlaying
            if (songAdapter.currentPlayingSong != null) {
                // 只更新当前播放的歌曲item
                viewModel.allSongs.value?.let { songs ->
                    val currentPosition =
                        songs.indexOfFirst { s -> s.id == songAdapter.currentPlayingSong!!.id }
                    if (currentPosition != -1) {
                        songAdapter.notifyItemChanged(currentPosition)
                    }
                }
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
    }

    private fun removeBottomPlayerObservers() {
        isPlayingObserver?.let { musicService?.isPlaying?.removeObserver(it) }
        currentSongObserver?.let { musicService?.currentSong?.removeObserver(it) }
        fileChangedObserver?.let { musicService?.fileChanged?.removeObserver(it) }
        isPlayingObserver = null
        currentSongObserver = null
        fileChangedObserver = null
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

    private fun bindMusicService() {
        val intent = Intent(this, MusicService::class.java)
        // 先startService确保服务在前台运行
        startService(intent)
        // 再bindService确保能正确绑定
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onSongClick(song: Song, position: Int) {
        viewModel.allSongs.value?.let { songs ->
            if (isServiceBound) {
                musicService?.setSongList(songs, position)
                // 检查是否已经在播放同一首歌
                if (musicService?.currentSong?.value?.id == song.id) {
                    if (musicService?.isPlaying?.value == true) {
                        // 如果正在播放同一首歌，只做UI更新
                        updateBottomPlayer(song)
                        return@let
                    } else {
                        // 如果是同一首歌但暂停状态，恢复播放
                        musicService?.requestAudioFocusAndPlay()
                        musicService?.updateNotification()
                        updateBottomPlayer(song)
                        return@let
                    }
                }
                // 播放新歌曲
                musicService?.requestAudioFocusAndPlayCurrentSong()
                // 立即更新通知栏
                musicService?.updateNotification()
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
        if (isServiceBound) {
            updateBottomPlayerUI()
            // 恢复Adapter中的播放状态和动画
            songAdapter.isPlaying = musicService?.isPlaying?.value == true
            songAdapter.currentPlayingSong = musicService?.currentSong?.value
            // 恢复动画（如果当前播放歌曲可见则恢复，不可见时由onViewAttachedToWindow恢复）
            songAdapter.resumeCurrentSongAnimation()
        }
    }

    override fun onPause() {
        super.onPause()
        musicService?.savePlaybackState()
        // 暂停Adapter中的动画（保留角度，不重置）
        songAdapter.pauseCurrentSongAnimation()
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
}