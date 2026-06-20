package com.unicorn.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.unicorn.player.databinding.ActivityPlayerBinding
import com.unicorn.player.service.MusicService
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var musicService: MusicService? = null
    private var isServiceBound = false
    private lateinit var pagerAdapter: PlayerPagerAdapter
    private var isUserScrolling = false
    private val scrollDebounceHandler = Handler(Looper.getMainLooper())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isServiceBound = true
            setupViewPager()
            observeCurrentSong()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isServiceBound = false
        }
    }

    // 防止循环调用的标志
    private var isHandlingSongChange = false

    // 标记是否是初始设置ViewPager
    private var isInitialSetup = false

    // 标记是否是代码设置的ViewPager位置
    private var isProgrammaticSetItem = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化adapter（初始为空）
        pagerAdapter = PlayerPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter

        // 配置ViewPager预加载：预加载前后各2页，避免切换时跳动感
        binding.viewPager.offscreenPageLimit = 2

        // 设置页面切换动画，提供更平滑的过渡效果
        binding.viewPager.setPageTransformer(
            androidx.viewpager2.widget.CompositePageTransformer().apply {
                // 添加透明度动画
                addTransformer { page, position ->
                    val absPosition = kotlin.math.abs(position)
                    page.alpha = 1f - absPosition * 0.3f
                }
                // 添加缩放动画
                addTransformer { page, position ->
                    val absPosition = kotlin.math.abs(position)
                    val scale = 1f - absPosition * 0.1f
                    page.scaleX = scale
                    page.scaleY = scale
                }
            }
        )

        // 向下箭头点击收起播放页面
        binding.ivCollapse.setOnClickListener {
            finishAndAnimate()
        }

        bindMusicService()
    }

    private fun setupViewPager() {
        musicService?.let { service ->
            // 设置页面切换监听（只注册一次）
            binding.viewPager.registerOnPageChangeCallback(object :
                androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int
                ) {
                    super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                    // 标记用户正在滑动
                    isUserScrolling = positionOffsetPixels != 0
                }

                override fun onPageScrollStateChanged(state: Int) {
                    super.onPageScrollStateChanged(state)
                    // 当滑动状态改变时
                    when (state) {
                        androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE -> {
                            // 滑动停止
                            isUserScrolling = false
                        }

                        androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_DRAGGING,
                        androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_SETTLING -> {
                            // 用户开始滑动
                            isUserScrolling = true
                        }
                    }
                }

                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    // 只在用户滑动时处理，代码设置时不处理
                    if (!isProgrammaticSetItem) {
                        handlePageSelected(position)
                    }
                }
            })

            // 标记为初始设置
            isInitialSetup = true
            // 标记为代码设置
            isProgrammaticSetItem = true

            // 检查是否有歌曲列表
            val songs = service.getSongList()
            if (songs.isEmpty()) {
                // 没有歌曲，显示空状态
                showEmptyState()
                // 尝试从数据库加载
                loadSongsFromDatabase(service)
            } else {
                // 有歌曲，隐藏空状态
                hideEmptyState()

                // 找到当前播放歌曲在列表中的位置
                val currentSong = service.currentSong.value
                val startPosition = if (currentSong != null) {
                    val index = songs.indexOfFirst { it.id == currentSong.id }
                    if (index >= 0) {
                        Log.d("PlayerActivity", "Found current song at position $index")
                        index
                    } else {
                        Log.w(
                            "PlayerActivity",
                            "Current song not in list, using index ${service.currentIndex}"
                        )
                        service.currentIndex
                    }
                } else {
                    Log.d("PlayerActivity", "No current song, using index ${service.currentIndex}")
                    service.currentIndex
                }

                // 设置初始歌曲
                pagerAdapter.updateSongs(songs, startPosition)
                Log.d(
                    "PlayerActivity",
                    "Initialized ViewPager with ${songs.size} songs, starting at position $startPosition"
                )

                // 重要：确保初始设置时不触发播放
                // 只更新当前歌曲，不播放
                Log.d("PlayerActivity", "Setting current song: ${songs[startPosition].title}")
                service.setCurrentSong(songs[startPosition])
            }

            // 初始设置完成
            isProgrammaticSetItem = false
            isInitialSetup = false
        }
    }

    private fun observeCurrentSong() {
        musicService?.let { service ->
            // 观察当前歌曲变化，同步ViewPager位置
            service.currentSong.observe(this) { song ->
                if (isHandlingSongChange) return@observe

                song?.let {
                    // 如果是初始设置，不处理
                    if (isInitialSetup) return@observe

                    val currentSongs = pagerAdapter.songs
                    if (currentSongs.isNotEmpty()) {
                        val index = currentSongs.indexOfFirst { it.id == song.id }
                        if (index >= 0 && binding.viewPager.currentItem != index) {
                            // 只在非用户滑动时更新ViewPager位置
                            if (!isUserScrolling) {
                                // 标记为代码设置
                                isProgrammaticSetItem = true
                                try {
                                    // 直接设置当前项，不触发onPageSelected
                                    binding.viewPager.setCurrentItem(index, false)
                                } finally {
                                    // 重置标记
                                    isProgrammaticSetItem = false
                                }
                            }
                        }
                        // 有歌曲时确保空状态被隐藏
                        hideEmptyState()
                    } else if (service.getSongList().isEmpty()) {
                        // 如果当前歌曲不为空但播放列表为空，尝试重新加载数据
                        loadSongsFromDatabase(service)
                    }
                }
            }
        }
    }

    private fun handlePageSelected(position: Int) {
        if (isHandlingSongChange) return

        musicService?.let { service ->
            val songs = pagerAdapter.songs
            if (songs.isEmpty()) return

            // 边界检查
            if (position !in songs.indices) return

            // 如果是初始设置，不触发播放
            if (isInitialSetup) {
                Log.d("PlayerActivity", "Initial setup, skipping playback")
                return
            }

            // 如果当前歌曲位置与目标位置不同，才切换歌曲
            if (service.currentIndex != position) {
                isHandlingSongChange = true
                try {
                    // 更新adapter数据
                    pagerAdapter.updateSongs(songs, position)

                    // 设置当前歌曲
                    service.currentIndex = position
                    service.setCurrentSong(songs[position])
                    service.requestAudioFocusAndPlayCurrentSong()
                } finally {
                    isHandlingSongChange = false
                }
            }
        }
    }

    private fun loadSongsFromDatabase(service: MusicService) {
        // 从数据库加载歌曲列表
        val database = com.unicorn.player.database.MusicDatabase.getDatabase(this)

        // 使用协程作用域来collect数据
        lifecycleScope.launch {
            try {
                database.songDao().getAllSongs().collect { songs ->
                    if (songs.isNotEmpty()) {
                        // 找到当前播放歌曲在列表中的位置
                        val currentSong = service.currentSong.value
                        val startPosition = if (currentSong != null) {
                            val index = songs.indexOfFirst { it.id == currentSong.id }
                            if (index >= 0) {
                                Log.d(
                                    "PlayerActivity",
                                    "Found current song at position $index in loaded list"
                                )
                                index
                            } else {
                                Log.w(
                                    "PlayerActivity",
                                    "Current song not in loaded list, using index ${service.currentIndex}"
                                )
                                service.currentIndex.coerceIn(0, songs.size - 1)
                            }
                        } else {
                            Log.d(
                                "PlayerActivity",
                                "No current song, using index ${service.currentIndex}"
                            )
                            service.currentIndex.coerceIn(0, songs.size - 1)
                        }

                        // 设置歌曲列表到service
                        service.setSongList(songs, startPosition)

                        // 更新ViewPager
                        pagerAdapter.updateSongs(songs, startPosition)
                        // 加载成功后隐藏空状态
                        hideEmptyState()
                        Log.d(
                            "PlayerActivity",
                            "Loaded ${songs.size} songs from database, starting at position $startPosition"
                        )

                        // 重要：确保初始设置时不触发播放
                        // 只更新当前歌曲，不播放
                        service.setCurrentSong(songs[startPosition])

                        // 注意：不再自动重启播放，让歌曲从当前位置继续
                        // 这样可以避免进入PlayerActivity时歌曲从头开始播放的问题
                    } else {
                        Log.w("PlayerActivity", "No songs found in database")
                    }
                }
            } catch (e: Exception) {
                Log.e("PlayerActivity", "Error loading songs from database: ${e.message}")
            }
        }
    }

    private fun bindMusicService() {
        val intent = Intent(this, MusicService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        startService(intent)
    }

    override fun onBackPressed() {
        finishAndAnimate()
    }

    private fun finishAndAnimate() {
        finish()
        // 主界面淡入，播放界面向下滑出
        overridePendingTransition(R.anim.fade_in, R.anim.slide_top_out)
    }

    override fun onPause() {
        super.onPause()
        musicService?.savePlaybackState()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 取消待处理的任务
        scrollDebounceHandler.removeCallbacksAndMessages(null)
        isHandlingSongChange = false

        // 移除空状态TextView
        emptyTextView?.let { textView ->
            binding.root.removeView(textView)
            emptyTextView = null
        }

        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    // 提供给Fragment访问Service的方法
    fun getMusicService(): MusicService? = musicService

    // 空状态提示
    private var emptyTextView: TextView? = null

    private fun showEmptyState() {
        binding.viewPager.visibility = View.GONE
        // 创建一个简单的空状态提示
        if (emptyTextView == null) {
            emptyTextView = TextView(this).apply {
                text = "没有找到音乐文件\n\n请在设备存储中添加音乐文件后重试"
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setPadding(50, 50, 50, 50)
                setTextColor(android.graphics.Color.parseColor("#666666"))
            }
            binding.root.addView(emptyTextView)
        }
        emptyTextView?.visibility = View.VISIBLE
    }

    private fun hideEmptyState() {
        binding.viewPager.visibility = View.VISIBLE
        emptyTextView?.visibility = View.GONE
    }
}