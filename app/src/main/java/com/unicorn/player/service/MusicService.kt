package com.unicorn.player.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.unicorn.player.MainActivity
import com.unicorn.player.R
import com.unicorn.player.database.MusicDatabase
import com.unicorn.player.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// 使用全局Application Context的DataStore
val Context.applicationDataStore: DataStore<Preferences> by preferencesDataStore(name = "music_player_state")

object DataStoreKeys {
    val CURRENT_SONG_ID = longPreferencesKey("current_song_id")
    val CURRENT_POSITION = intPreferencesKey("current_position")
    val IS_PLAYING = intPreferencesKey("is_playing") // 0=暂停, 1=播放
    val SONG_TITLE = stringPreferencesKey("song_title")
    val SONG_ARTIST = stringPreferencesKey("song_artist")
    val SONG_PATH = stringPreferencesKey("song_path")
}

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

    // 音频管理器
    private lateinit var audioManager: AudioManager

    private var _wasPlayingBeforeFocusLoss = false

    private lateinit var audioFocusRequest: AudioFocusRequest
    private var currentIndex = 0

    // 广播接收器用于处理通知栏按钮点击
    private val notificationButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d(
                TAG,
                "Notification button clicked: $action, current isPlaying: ${_isPlaying.value}"
            )

            when (action) {
                ACTION_PLAY -> {
                    _isPlaying.value = true
                    play()
                }

                ACTION_PAUSE -> {
                    _isPlaying.value = false
                    pause()
                }

                ACTION_NEXT -> {
                    playNext()
                }

                ACTION_PREVIOUS -> {
                    playPrevious()
                }
            }

            // 立即更新通知
            updateNotification()
            Log.d(TAG, "After button click: isPlaying: ${_isPlaying.value}")
        }
    }

    // 标记loadPlaybackState是否已经被调用过
    private var isPlaybackStateLoaded = false

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

        const val TAG = "MusicService"
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

        // 初始化音频管理器（但不请求焦点）
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 启动文件监听
        startFileObserver()

        // 设置MediaSession回调
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                requestAudioFocusAndPlay()
            }

            override fun onPause() {
                pause()
            }

            override fun onSkipToNext() {
                requestAudioFocusAndPlayNext()
            }

            override fun onSkipToPrevious() {
                requestAudioFocusAndPlayPrevious()
            }

            override fun onStop() {
                stopSelf()
            }

            override fun onSeekTo(pos: Long) {
                // 处理通知栏进度条拖动事件
                seekTo(pos.toInt())
                updateMediaSessionPlaybackState()
            }
        })

        // Start position updates
        startPositionUpdates()

        // 只在有当前歌曲时才显示通知
        if (_currentSong.value != null) {
            updateNotification(_currentSong.value)
        }

        // 注册通知栏按钮点击接收器
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREVIOUS)
        }
        // 使用ContextCompat来处理不同API版本的广播注册
        ContextCompat.registerReceiver(
            this,
            notificationButtonReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        // 取消注册广播接收器
        unregisterReceiver(notificationButtonReceiver)

        // 保存播放状态
        savePlaybackState()
        mediaPlayer.release()
        mediaSession.release()

        // 停止文件监听
        stopFileObserver()

        // Use modern API for stopping foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Cancel the notification
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
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
                }

                // 如果已经有当前歌曲且处于暂停状态，直接调用play()从暂停位置继续播放
                // 只有在新传入songList时才调用playCurrentSong()重新播放
                if (songListData != null && _currentSong.value != null && !mediaPlayer.isPlaying) {
                    // 新传入的歌曲列表，重新播放
                    _isPlaying.value = true
                    playCurrentSong()
                } else {
                    // 没有新传入歌曲列表，或者当前没有歌曲，或者已经在播放
                    // 调用play()方法，它会智能处理暂停恢复或重新播放
                    _isPlaying.value = true
                    play()
                }
                updateNotification()
            }

            ACTION_PAUSE -> {
                // 强制暂停并更新状态
                _isPlaying.value = false
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
                // 停止前保存状态
                savePlaybackState()
                CoroutineScope(Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(100) // 短暂延迟确保保存完成
                    stopSelf()
                }
            }

            else -> {
                // 如果action为null，尝试加载保存的状态
                if (_currentSong.value == null) {
                    loadPlaybackState()
                }
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

    fun playCurrentSong() {
        if (songList.isEmpty()) {
            // 如果没有songList，尝试使用当前歌曲播放
            _currentSong.value?.let { song ->
                playSongDirectly(song)
            }
            return
        }

        val song = songList[currentIndex]
        playSongDirectly(song)
    }

    private fun playSongDirectly(song: Song) {
        // 使用setValue确保立即更新
        _currentSong.value = song
        _isPlaying.value = true  // 确保立即更新状态

        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(song.path)
            mediaPlayer.prepare()
            mediaPlayer.start()
            // 播放后立即更新通知和状态
            updateNotification(song)
            updateMediaSessionPlaybackState()
        } catch (e: IOException) {
            Log.e(TAG, "Error playing song: ${e.message}")
            e.printStackTrace()
            // 播放失败时重置状态
            _isPlaying.value = false
        }
    }

    fun requestAudioFocusAndPlayCurrentSong() {
        // 请求音频焦点
        val result = requestAudioFocus()

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // 只有在没有正在播放时才播放
            if (!_isPlaying.value!! && !mediaPlayer.isPlaying) {
                playCurrentSong()
            }
        }
    }

    private fun requestAudioFocus(): Int {
        // 使用新的AudioFocusRequest API
        if (!::audioFocusRequest.isInitialized) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
        }
        return audioManager.requestAudioFocus(audioFocusRequest)
    }

    fun play() {
        // 如果当前没有歌曲，尝试加载或播放当前歌曲
        if (_currentSong.value == null) {
            loadPlaybackState()
            return
        }

        if (!mediaPlayer.isPlaying) {
            // 检查媒体播放器是否已准备，只有在未准备时才重新准备
            // 如果已经准备但处于暂停状态，直接start()会从暂停位置继续播放
            if (mediaPlayer.currentPosition == 0 && mediaPlayer.duration == 0) {
                // 重新准备播放
                _currentSong.value?.let { song ->
                    try {
                        mediaPlayer.reset()
                        mediaPlayer.setDataSource(song.path)
                        mediaPlayer.prepare()
                    } catch (e: IOException) {
                        Log.e(TAG, "Error preparing media player: ${e.message}")
                        e.printStackTrace()
                        return
                    }
                }
            }

            mediaPlayer.start()
            _isPlaying.postValue(true)
            updateNotification()
        }
    }

    fun requestAudioFocusAndPlay() {
        // 请求音频焦点
        val result = requestAudioFocus()

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // 获得音频焦点，可以播放
            play()
        } else {
            // 没有获得音频焦点，显示提示
            // 注意：这里不能直接访问UI，需要通过LiveData或其他方式通知
        }
    }

    fun pause() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            _isPlaying.postValue(false)
            // 暂停时放弃音频焦点
            // abandonAudioFocus()
            updateNotification()
            // 更新播放状态
            updateMediaSessionPlaybackState()
            // 保存播放状态
            savePlaybackState()
        }
    }

    fun playNext() {
        if (songList.isEmpty()) {
            // 如果songList为空，但我们有当前歌曲，尝试从数据库加载完整列表
            _currentSong.value?.let { currentSong ->
                // 在协程中加载歌曲列表
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // 获取数据库中的歌曲列表
                        val database = MusicDatabase.getDatabase(this@MusicService)
                        database.songDao().getAllSongs().collect { songs ->
                            if (songs.isNotEmpty()) {
                                val currentIndexInList =
                                    songs.indexOfFirst { it.id == currentSong.id }
                                if (currentIndexInList >= 0) {
                                    setSongList(songs, currentIndexInList)
                                } else {
                                    setSongList(songs, 0)
                                }
                                // 使用 Handler 延迟执行 playNext
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    playNext() // 递归调用，现在有songList了
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading song list from database: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
            // 没有当前歌曲，无法播放
            return
        }

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
        // 直接调用playCurrentSong，它会自动更新通知
        playCurrentSong()
    }

    fun playPrevious() {
        if (songList.isEmpty()) {
            // 如果songList为空，但我们有当前歌曲，尝试从数据库加载完整列表
            _currentSong.value?.let { currentSong ->
                // 在协程中加载歌曲列表
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // 获取数据库中的歌曲列表
                        val database = MusicDatabase.getDatabase(this@MusicService)
                        database.songDao().getAllSongs().collect { songs ->
                            if (songs.isNotEmpty()) {
                                val currentIndexInList =
                                    songs.indexOfFirst { it.id == currentSong.id }
                                if (currentIndexInList >= 0) {
                                    setSongList(songs, currentIndexInList)
                                } else {
                                    setSongList(songs, 0)
                                }
                                // 使用 Handler 延迟执行 playPrevious
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    playPrevious() // 递归调用，现在有songList了
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading song list from database: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
            // 没有当前歌曲，无法播放
            return
        }

        currentIndex = if (currentIndex > 0) {
            currentIndex - 1
        } else {
            songList.size - 1
        }
        // 先获取上一首的歌曲信息，更新歌曲信息，再播放
        val previousSong = songList[currentIndex]
        _currentSong.postValue(previousSong)
        playCurrentSong()
        // 立即更新通知和MediaSession状态
        updateNotification(previousSong)
        updateMediaSessionPlaybackState()
    }

    fun requestAudioFocusAndPlayNext() {
        // 请求音频焦点
        val result = requestAudioFocus()

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // 获得音频焦点，播放下一首
            playNext()
            // 确保通知立即更新
            updateNotification(_currentSong.value)
            updateMediaSessionPlaybackState()
        }
    }

    fun requestAudioFocusAndPlayPrevious() {
        // 请求音频焦点
        val result = requestAudioFocus()

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // 获得音频焦点，播放上一首
            playPrevious()
            // 确保通知立即更新
            updateNotification(_currentSong.value)
            updateMediaSessionPlaybackState()
        }
    }

    fun seekTo(position: Int) {
        mediaPlayer.seekTo(position)
        _currentPosition.postValue(position)
    }

    private val executorService: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()
    private var lastCheckTime = 0L

    // 音频焦点变化监听
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 获得音频焦点，可以继续播放
                val wasPlayingBefore = _wasPlayingBeforeFocusLoss
                if (wasPlayingBefore) {
                    play()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 短暂失去音频焦点（如来电），需要暂停播放
                _wasPlayingBeforeFocusLoss = mediaPlayer.isPlaying
                pause()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                // 长时间失去音频焦点，需要暂停播放
                _wasPlayingBeforeFocusLoss = mediaPlayer.isPlaying
                pause()
            }
        }
    }

    private fun abandonAudioFocus() {
        // 放弃音频焦点
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    private fun startFileObserver() {
        // 创建一个定期检查文件变化的定时任务
        executorService.scheduleWithFixedDelay({
            checkCurrentSongFile()
            // savePlaybackState()
        }, 0, 10, TimeUnit.SECONDS)
    }

    private fun stopFileObserver() {
        executorService.shutdown()
        try {
            if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                executorService.shutdownNow()
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
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
                        // 更新MediaSession的播放状态，包含进度信息
                        updateMediaSessionPlaybackState()
                    }
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    break
                }
            }
        }.start()
    }

    private fun updateMediaSessionPlaybackState() {
        val playbackState = mediaPlayer.let { player ->
            val state = if (player.isPlaying) {
                android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
            } else {
                android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
            }
            android.support.v4.media.session.PlaybackStateCompat.Builder()
                .setState(state, player.currentPosition.toLong(), 1.0f)
                .setActions(
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_SEEK_TO
                )
                .build()
        }
        mediaSession.setPlaybackState(playbackState)

        // 更新MediaSession的元数据，包含歌曲信息
        updateMediaSessionMetadata()
    }

    private fun updateMediaSessionMetadata() {
        val currentSong = _currentSong.value ?: return
        val metadata = android.support.v4.media.MediaMetadataCompat.Builder()
            .putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE,
                currentSong.title
            )
            .putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST,
                currentSong.artist
            )
            .putString(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM,
                currentSong.album
            )
            .putLong(
                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION,
                mediaPlayer.duration.toLong()
            )
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun updateNotification(currentSong: Song? = _currentSong.value) {
        // Only update notification if we have a current song
        if (currentSong != null) {
            // 检查是否具有通知权限
            if (NotificationManagerCompat.from(this@MusicService).areNotificationsEnabled()) {
                // 创建通知并更新
                val notification = createNotification(currentSong)
                val notificationManager = NotificationManagerCompat.from(this@MusicService)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun createNotification(song: Song): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 根据当前播放状态决定按钮图标和动作
        val isPlaying = _isPlaying.value == true
        val playPauseAction = if (isPlaying) {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
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
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createActionPendingIntent(action: String): PendingIntent {
        // 创建明确的Intent，确保包含组件名称
        val intent = Intent(action).apply {
            component = ComponentName(this@MusicService, MusicService::class.java)
            // 清除之前的flag，使用更适合服务的flag
            flags = Intent.FLAG_RECEIVER_FOREGROUND
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

    fun savePlaybackState() {
        CoroutineScope(Dispatchers.IO).launch {
            applicationDataStore.edit { preferences ->
                val currentSong = _currentSong.value
                if (currentSong != null) {
                    preferences[DataStoreKeys.CURRENT_SONG_ID] = currentSong.id
                    preferences[DataStoreKeys.SONG_TITLE] = currentSong.title
                    preferences[DataStoreKeys.SONG_ARTIST] = currentSong.artist
                    preferences[DataStoreKeys.SONG_PATH] = currentSong.path
                    preferences[DataStoreKeys.CURRENT_POSITION] = mediaPlayer.currentPosition
                    preferences[DataStoreKeys.IS_PLAYING] = if (mediaPlayer.isPlaying) 1 else 0
                } else {
                    // 清除保存的状态
                    preferences.remove(DataStoreKeys.CURRENT_SONG_ID)
                    preferences.remove(DataStoreKeys.SONG_TITLE)
                    preferences.remove(DataStoreKeys.SONG_ARTIST)
                    preferences.remove(DataStoreKeys.SONG_PATH)
                    preferences.remove(DataStoreKeys.CURRENT_POSITION)
                    preferences.remove(DataStoreKeys.IS_PLAYING)
                }
            }
        }
    }

    fun loadPlaybackState() {
        // 如果已经加载过播放状态，不需要重复加载
        if (isPlaybackStateLoaded) {
            Log.i(TAG, "loadPlaybackState: already loaded, skip")
            return
        }
        isPlaybackStateLoaded = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val preferences = applicationDataStore.data.first()
                val songId = preferences[DataStoreKeys.CURRENT_SONG_ID] ?: run {
                    return@launch
                }
                val songTitle = preferences[DataStoreKeys.SONG_TITLE] ?: run {
                    return@launch
                }
                val songArtist = preferences[DataStoreKeys.SONG_ARTIST] ?: run {
                    return@launch
                }
                val songPath = preferences[DataStoreKeys.SONG_PATH] ?: run {
                    return@launch
                }
                val currentPosition = preferences[DataStoreKeys.CURRENT_POSITION] ?: 0
                val isPlaying = preferences[DataStoreKeys.IS_PLAYING] ?: 0

                Log.d(
                    TAG,
                    "loadPlaybackState, songId=$songId, songTitle=$songTitle, currentPosition=$currentPosition, isPlaying=$isPlaying"
                )

                // 检查文件是否存在
                val file = java.io.File(songPath)
                if (!file.exists()) {
                    Log.e(TAG, "Song file not found: $songPath")
                    return@launch
                }

                // 恢复歌曲信息（在主线程更新）
                withContext(Dispatchers.Main) {
                    val restoredSong = Song(
                        id = songId,
                        title = songTitle,
                        artist = songArtist,
                        album = "",
                        duration = 0,
                        path = songPath
                    )
                    _currentSong.value = restoredSong

                    // 准备媒体播放器但不立即播放
                    try {
                        mediaPlayer.reset()
                        mediaPlayer.setDataSource(songPath)
                        mediaPlayer.prepare()
                        seekTo(currentPosition)

                        // 不再自动恢复播放，只准备媒体播放器
                        // 如果之前是播放状态，只更新UI状态，不自动播放
                        if (isPlaying == 1) {
                            // 更新通知和UI，显示暂停状态
                            updateNotification(restoredSong)
                            updateMediaSessionPlaybackState()
                        } else {
                            // 更新通知和UI
                            updateNotification(restoredSong)
                            updateMediaSessionPlaybackState()
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Error preparing media player: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading playback state: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.d(TAG, "onLowMemory")
        // 尝试保存状态
        savePlaybackState()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved")
        // 尝试保存状态
        savePlaybackState();
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(300)
            stopSelf()
        }
    }
}