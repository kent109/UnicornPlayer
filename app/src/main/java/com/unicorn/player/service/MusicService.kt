package com.unicorn.player.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.FileObserver
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.unicorn.player.MainActivity
import com.unicorn.player.R
import com.unicorn.player.model.Song
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MusicService : Service() {

    private val binder = MusicBinder()
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var mediaSession: MediaSessionCompat

    private val _currentSong = MutableLiveData<Song?>()
    val currentSong: LiveData<Song?> = _currentSong

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _currentPosition = MutableLiveData(0)
    val currentPosition: LiveData<Int> = _currentPosition

    // 文件变化通知
    private val _fileChanged = MutableLiveData<Unit>()
    val fileChanged: LiveData<Unit> = _fileChanged

    private var songList = mutableListOf<Song>()
    private var currentIndex = 0

    // 播放模式 - 默认为全部循环
    private var playMode = PlayMode.ALL_LOOP

    enum class PlayMode {
        ALL_LOOP,       // 全部循环
        SINGLE_LOOP,    // 单曲循环
        SEQUENCE        // 顺序播放
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "music_player_channel"

        const val ACTION_PLAY = "com.unicorn.player.action.PLAY"
        const val ACTION_PAUSE = "com.unicorn.player.action.PAUSE"
        const val ACTION_NEXT = "com.unicorn.player.action.NEXT"
        const val ACTION_PREVIOUS = "com.unicorn.player.action.PREVIOUS"
        const val ACTION_STOP = "com.unicorn.player.action.STOP"
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnCompletionListener {
                playNext()
            }
        }

        mediaSession = MediaSessionCompat(this, "MusicService")
        mediaSession.isActive = true

        // 启动文件监听
        startFileObserver()

        // 设置MediaSession回调
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                play()
            }

            override fun onPause() {
                pause()
            }

            override fun onSkipToNext() {
                playNext()
            }

            override fun onSkipToPrevious() {
                playPrevious()
            }

            override fun onStop() {
                stopSelf()
            }
        })

        // Start position updates
        startPositionUpdates()

        // 确保服务在前台运行
        if (_currentSong.value != null) {
            showNotification(_currentSong.value)
        } else {
            // 创建空通知确保服务在前台
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle("Unicorn Player")
                .setContentText("音乐播放中")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setVibrationPattern(null)
            enableLights(false)
            setSound(null, null)
            setBypassDnd(true)  // 绕过勿扰模式
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // 确保MediaPlayer已初始化
        if (!::mediaPlayer.isInitialized) {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setOnCompletionListener {
                    playNext()
                }
            }
        }

        when (action) {
            ACTION_PLAY -> {
                val songId = intent.getLongExtra("songId", -1L)
                val position = intent.getIntExtra("position", 0)
                val songListData = intent.getStringArrayListExtra("songList")

                if (songListData != null) {
                    // 将字符串数组转换回Song列表
                    val songs = songListData.map { songData ->
                        val parts = songData.split("|")
                        Song(
                            id = parts[0].toLong(),
                            title = parts[1],
                            artist = parts[2],
                            album = parts[3],
                            duration = parts[4].toLong(),
                            path = parts[5],
                            albumArt = parts[6].ifEmpty { null }
                        )
                    }
                    setSongList(songs, position)

                    // 如果有传递songId，播放对应的歌曲
                    if (songId != -1L) {
                        val song = songs.find { it.id == songId }
                        if (song != null) {
                            playSong(song)
                        } else {
                            // 如果找不到对应的歌曲，播放当前位置的歌曲
                            play()
                        }
                    } else {
                        // 如果没有传递songId，播放当前位置的歌曲
                        play()
                    }
                }
                updateNotification()
            }

            ACTION_PAUSE -> {
                pause()
                updateNotification()
            }

            ACTION_NEXT -> {
                playNext()
                updateNotification()
            }

            ACTION_PREVIOUS -> {
                playPrevious()
                updateNotification()
            }

            ACTION_STOP -> {
                stopSelf()
            }

            else -> {
                // 如果action为null但有当前歌曲，确保通知显示
                if (_currentSong.value != null) {
                    updateNotification()
                }
            }
        }
        return START_STICKY
    }

    fun setSongList(songs: List<Song>, startIndex: Int = 0) {
        songList.clear()
        songList.addAll(songs)
        currentIndex = startIndex
    }

    fun playSong(song: Song) {
        val index = songList.indexOfFirst { it.id == song.id }
        if (index != -1) {
            currentIndex = index
            playCurrentSong()
        }
    }

    fun playCurrentSong() {
        if (songList.isEmpty()) {
            return
        }

        val song = songList[currentIndex]
        _currentSong.postValue(song)

        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(song.path)
            mediaPlayer.prepare()
            mediaPlayer.start()
            _isPlaying.postValue(true)
            showNotification(song)  // 直接显示当前歌曲的通知
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun play() {
        if (!mediaPlayer.isPlaying) {
            mediaPlayer.start()
            _isPlaying.postValue(true)
            updateNotification()
        }
    }

    fun pause() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            _isPlaying.postValue(false)
            updateNotification()
        }
    }

    fun playNext() {
        if (songList.isEmpty()) return

        when (playMode) {
            PlayMode.ALL_LOOP -> {
                // 全部循环：到最后一首回到第一首
                currentIndex = if (currentIndex < songList.size - 1) {
                    currentIndex + 1
                } else {
                    0
                }
            }
            PlayMode.SINGLE_LOOP -> {
                // 单曲循环：保持当前索引不变
            }
            PlayMode.SEQUENCE -> {
                // 顺序播放：到最后一首停止
                if (currentIndex < songList.size - 1) {
                    currentIndex++
                } else {
                    // 已到最后一首，停止播放
                    pause()
                    return
                }
            }
        }
        playCurrentSong()
    }

    fun playPrevious() {
        if (songList.isEmpty()) return

        currentIndex = if (currentIndex > 0) {
            currentIndex - 1
        } else {
            songList.size - 1
        }
        playCurrentSong()
    }

    fun seekTo(position: Int) {
        mediaPlayer.seekTo(position)
        _currentPosition.postValue(position)
    }

    private var fileObserver: FileObserver? = null
    private val executorService: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var lastCheckTime = 0L

    private fun startFileObserver() {
        // 创建一个定期检查文件变化的定时任务
        executorService.scheduleWithFixedDelay({
            checkCurrentSongFile()
        }, 0, 5, TimeUnit.SECONDS)
    }

    private fun stopFileObserver() {
        executorService.shutdown()
        try {
            if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                executorService.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executorService.shutdownNow()
        }
    }

    private fun checkCurrentSongFile() {
        val currentSong = _currentSong.value ?: return

        // 避免过于频繁的检查
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastCheckTime < 5000) { // 至少间隔5秒
            return
        }
        lastCheckTime = currentTime

        val file = java.io.File(currentSong.path)
        if (!file.exists()) {
            // 当前播放的文件被删除，播放下一首
            playNext()
        } else {
            // 文件存在，检查是否有更新
            val lastModified = file.lastModified()
            if (lastModified > currentSong.lastModified) {
                // 文件被修改，通知刷新
                _fileChanged.postValue(Unit)
            }
        }
    }

    fun getCurrentPosition(): Int = mediaPlayer.currentPosition

    fun getDuration(): Int = mediaPlayer.duration

    private fun startPositionUpdates() {
        Thread {
            while (true) {
                try {
                    if (mediaPlayer.isPlaying) {
                        _currentPosition.postValue(mediaPlayer.currentPosition)
                    }
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    break
                }
            }
        }.start()
    }

    private fun showNotification(song: Song?) {
        if (song == null) return

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (_isPlaying.value == true) {
            NotificationCompat.Action(
                R.drawable.ic_pause,
                "Pause",
                createActionPendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play,
                "Play",
                createActionPendingIntent(ACTION_PLAY)
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText("${song.artist} - ${song.album}")
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(pendingIntent)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_previous,
                    "Previous",
                    createActionPendingIntent(ACTION_PREVIOUS)
                )
            )
            .addAction(playPauseAction)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_next,
                    "Next",
                    createActionPendingIntent(ACTION_NEXT)
                )
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(_isPlaying.value == true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(currentSong: Song? = _currentSong.value) {
        // Only update notification if we have a current song
        if (currentSong != null) {
            showNotification(currentSong)
        }
    }

    private fun createActionPendingIntent(action: String): PendingIntent {
        // 创建明确的Intent，确保包含组件名称
        val intent = Intent().apply {
            this.action = action
            component = ComponentName(this@MusicService, MusicService::class.java)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        // 使用不同的requestCode确保每个action都有独立的PendingIntent
        val requestCode = when (action) {
            ACTION_PLAY -> 1001
            ACTION_PAUSE -> 1002
            ACTION_NEXT -> 1003
            ACTION_PREVIOUS -> 1004
            ACTION_STOP -> 1005
            else -> action.hashCode()
        }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer.release()
        mediaSession.release()

        // 停止文件监听
        stopFileObserver()

        // Use modern API for stopping foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Cancel the notification
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    }
}