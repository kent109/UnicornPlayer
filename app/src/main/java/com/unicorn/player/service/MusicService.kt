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
import kotlinx.coroutines.runBlocking
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
    val PLAY_MODE = intPreferencesKey("play_mode")
}

class MusicService : Service() {

    private val binder = MusicBinder()
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var mediaSession: MediaSessionCompat

    private val _currentSong = MutableLiveData<Song?>()
    val currentSong: LiveData<Song?> = _currentSong

    // 公共方法设置当前歌曲
    fun setCurrentSong(song: Song) {
        _currentSong.value = song
    }

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _currentPosition = MutableLiveData(0)
    val currentPosition: LiveData<Int> = _currentPosition

    // 文件变化通知
    private val _fileChanged = MutableLiveData<Unit>()
    val fileChanged: LiveData<Unit> = _fileChanged

    // 请求重新设置歌曲列表通知（用于songList为空时通知MainActivity）
    private val _requestSongList = MutableLiveData<Event<Boolean>>()
    val requestSongList: LiveData<Event<Boolean>> = _requestSongList

    // 播放模式 LiveData，用于通知 UI 更新图标
    private val _playModeLiveData = MutableLiveData<PlayMode>(PlayMode.ALL_LOOP)
    val playModeLiveData: LiveData<PlayMode> = _playModeLiveData

    private var songList = mutableListOf<Song>()
    private val _songList = MutableLiveData<List<Song>>(emptyList())

    // 音频管理器
    private lateinit var audioManager: AudioManager

    private var _wasPlayingBeforeFocusLoss = false

    private lateinit var audioFocusRequest: AudioFocusRequest
    var currentIndex = 0
    var isChangingSong = false
    var isSkippingFailedSong = false

    // 广播接收器用于处理通知栏按钮点击
    private val notificationButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d(
                TAG, "Notification button clicked: $action, current isPlaying: ${_isPlaying.value}"
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

    // 广播接收器用于监听耳机插拔和蓝牙连接状态
    private val audioDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d(TAG, "Audio device event: $action")

            when (action) {
                // 有线耳机插拔
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", 0)
                    Log.d(TAG, "Headset plug state: $state")
                    if (state == 0) { // 0表示断开，1表示插入
                        // 有线耳机断开，暂停播放
                        if (_isPlaying.value == true) {
                            pause()
                        }
                    }
                }

                // 蓝牙设备连接状态变化
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    // 蓝牙设备断开连接
                    Log.d(TAG, "Bluetooth device disconnected")
                    if (_isPlaying.value == true) {
                        pause()
                    }
                }

                // 蓝牙音频连接状态变化
                android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        android.bluetooth.BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        android.bluetooth.BluetoothAdapter.STATE_DISCONNECTED
                    )
                    Log.d(TAG, "Bluetooth connection state changed: $state")
                    if (state == android.bluetooth.BluetoothAdapter.STATE_DISCONNECTED) {
                        // 蓝牙音频断开，暂停播放
                        if (_isPlaying.value == true) {
                            pause()
                        }
                    }
                }

                // A2DP音频流连接状态变化
                android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        android.bluetooth.BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        android.bluetooth.BluetoothA2dp.STATE_DISCONNECTED
                    )
                    Log.d(TAG, "A2DP audio stream connection state changed: $state")
                    if (state == android.bluetooth.BluetoothA2dp.STATE_DISCONNECTED) {
                        // A2DP音频流断开，暂停播放
                        if (_isPlaying.value == true) {
                            pause()
                        }
                    }
                }

                // 蓝牙设备配对状态变化
                android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE,
                        android.bluetooth.BluetoothDevice.BOND_NONE
                    )
                    Log.d(TAG, "Bluetooth device bond state changed: $state")
                    if (state == android.bluetooth.BluetoothDevice.BOND_NONE) {
                        // 蓝牙设备未配对，暂停播放
                        if (_isPlaying.value == true) {
                            pause()
                        }
                    }
                }
            }
        }
    }

    // 标记loadPlaybackState是否已经被调用过
    private var isPlaybackStateLoaded = false

    // 播放模式 - 默认为全部循环
    private var playMode = PlayMode.ALL_LOOP

    enum class PlayMode {
        ALL_LOOP,       // 全部循环
        SINGLE_LOOP,    // 单曲循环
        RANDOM,         // 随机播放
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
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            setOnCompletionListener {
                playNext()
            }
        }

        mediaSession = MediaSessionCompat(this, "MusicService")

        // 先同步加载播放模式，避免图标闪烁
        runBlocking {
            try {
                val preferences = applicationDataStore.data.first()
                val savedPlayModeOrdinal = preferences[DataStoreKeys.PLAY_MODE] ?: 0
                val savedPlayMode = PlayMode.entries.getOrElse(savedPlayModeOrdinal) { PlayMode.ALL_LOOP }
                playMode = savedPlayMode
                _playModeLiveData.value = savedPlayMode
                Log.d(TAG, "onCreate: loaded playMode=$savedPlayMode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load play mode", e)
            }
        }

        // 加载上次播放状态（包含歌曲信息）
        loadPlaybackState()
        mediaSession.isActive = true

        // 初始化音频管理器（但不请求焦点）
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 启动文件监听
        // startFileObserver()

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
        val notificationFilter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREVIOUS)
        }
        // 使用ContextCompat来处理不同API版本的广播注册
        ContextCompat.registerReceiver(
            this, notificationButtonReceiver, notificationFilter, ContextCompat.RECEIVER_EXPORTED
        )

        // 注册音频设备监听接收器
        val audioDeviceFilter = IntentFilter().apply {
            // 有线耳机插拔
            addAction(Intent.ACTION_HEADSET_PLUG)
            // 蓝牙设备连接状态变化
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            // 蓝牙音频连接状态变化
            addAction(android.bluetooth.BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            // A2DP音频流状态变化
            addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            // 蓝牙设备配对状态变化
            addAction(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // 使用ContextCompat来处理不同API版本的广播注册
        ContextCompat.registerReceiver(
            this, audioDeviceReceiver, audioDeviceFilter, ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        // 取消注册广播接收器
        unregisterReceiver(notificationButtonReceiver)

        // 先保存播放状态（协程异步执行，但此时mediaPlayer还未release）
        savePlaybackState()
        // 标记MediaPlayer即将释放，若协程延迟执行到release之后则会跳过
        isMediaPlayerReleased = true
        mediaPlayer.release()
        mediaSession.release()

        // 停止文件监听
        // stopFileObserver()

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
                    AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA).build()
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
                            albumArt = parts[6].ifEmpty { null })
                    }
                    setSongList(songs, position)
                }

                // 如果已经有当前歌曲且处于暂停状态，直接调用play()从暂停位置继续播放
                // 只有在新传入songList时才调用playCurrentSong()重新播放
                if (songListData != null && _currentSong.value != null && !isMediaPlayerPlaying()) {
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
        _songList.value = songs
        currentIndex = startIndex
        isChangingSong = true
    }

    fun getSongList(): List<Song> = songList

    fun playCurrentSong() {
        if (songList.isEmpty()) {
            // 如果没有songList，尝试使用当前歌曲播放
            _currentSong.value?.let { song ->
                playSongDirectly(song)
            }
            return
        }

        val song = songList[currentIndex]
        isChangingSong = false
        playSongDirectly(song)
    }

    private fun playSongDirectly(song: Song) {
        // 检查文件是否存在
        val file = java.io.File(song.path)
        if (!file.exists()) {
            Log.e(TAG, "Song file not found: ${song.path}")
            // 文件不存在，尝试播放下一首
            if (!isSkippingFailedSong) {
                isSkippingFailedSong = true
                playNext()
            }
            return
        }

        // 使用setCurrentSong确保立即更新
        setCurrentSong(song)
        _isPlaying.value = true  // 确保立即更新状态

        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(song.path)
            mediaPlayer.prepare()
            mediaPlayer.start()
            // 播放后立即更新通知和状态
            updateNotification(song)
            updateMediaSessionPlaybackState()
            // 重置跳过标志
            isSkippingFailedSong = false
        } catch (e: IOException) {
            Log.e(TAG, "Error playing song: ${e.message}")
            e.printStackTrace()
            // 播放失败时重置状态
            _isPlaying.value = false
            // 播放失败，尝试播放下一首
            if (!isSkippingFailedSong) {
                isSkippingFailedSong = true
                playNext()
            }
        }
    }

    fun requestAudioFocusAndPlayCurrentSong() {
        // 请求音频焦点
        val result = requestAudioFocus()

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // 获得音频焦点，播放当前歌曲
            playCurrentSong()
        }
    }

    private fun requestAudioFocus(): Int {
        // 使用新的AudioFocusRequest API
        if (!::audioFocusRequest.isInitialized) {
            audioFocusRequest =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(
                    AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA).build()
                ).setOnAudioFocusChangeListener(audioFocusChangeListener).build()
        }
        return audioManager.requestAudioFocus(audioFocusRequest)
    }

    fun play() {
        // 如果当前没有歌曲，尝试加载或播放当前歌曲
        if (_currentSong.value == null) {
            loadPlaybackState()
            return
        }

        if (!isMediaPlayerPlaying()) {
            // 检查媒体播放器是否已准备，只有在未准备时才重新准备
            // 如果已经准备但处于暂停状态，直接start()会从暂停位置继续播放
            try {
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
            } catch (e: IllegalStateException) {
                Log.e(TAG, "play: MediaPlayer state error", e)
                // 如果MediaPlayer处于无效状态，尝试重新准备
                _currentSong.value?.let { song ->
                    try {
                        mediaPlayer.reset()
                        mediaPlayer.setDataSource(song.path)
                        mediaPlayer.prepare()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error re-preparing media player: ${e.message}")
                        return
                    }
                }
            }

            mediaPlayer.start()
            // 立即更新播放状态为true，确保通知栏能正确显示
            _isPlaying.value = true
            // 立即更新通知栏和MediaSession状态
            updateNotification()
            updateMediaSessionPlaybackState()
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
        if (isMediaPlayerPlaying()) {
            mediaPlayer.pause()
            // 立即更新播放状态为false，确保通知栏能正确显示
            _isPlaying.value = false
            // 暂停时放弃音频焦点
            // abandonAudioFocus()
            // 立即更新通知栏和MediaSession状态
            updateNotification()
            updateMediaSessionPlaybackState()
            // 保存播放状态
            savePlaybackState()
        }
    }

    fun setPlayMode(mode: PlayMode) {
        playMode = mode
        _playModeLiveData.postValue(mode)
        savePlaybackState()
    }

    // 获取当前播放模式
    fun getPlayMode(): PlayMode = playMode

    fun playNext() {
        if (songList.isEmpty()) {
            // 如果songList为空，通知MainActivity重新设置歌曲列表
            // 这样可以确保播放顺序与用户界面一致
            Log.d(TAG, "songList is empty, requesting MainActivity to reset song list")
            _requestSongList.postValue(Event(true))
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

            PlayMode.RANDOM -> {
                // 随机播放：随机选择一首（尽量不选当前）
                if (songList.size > 1) {
                    val newIndex = (0 until songList.size).random()
                    currentIndex = if (newIndex == currentIndex && songList.size > 1) {
                        (currentIndex + 1) % songList.size
                    } else {
                        newIndex
                    }
                }
            }

            PlayMode.SEQUENCE -> {
                // 顺序播放：到最后一首停止
                if (currentIndex < songList.size - 1) {
                    currentIndex++
                } else {
                    // 已到最后一首，停止播放并更新状态
                    // 注意：此时 MediaPlayer 已经播放完毕，isPlaying() 返回 false
                    // 所以不能依赖 pause() 来更新状态，需要直接更新
                    _isPlaying.value = false
                    // 更新通知和 MediaSession 状态
                    updateNotification()
                    updateMediaSessionPlaybackState()
                    // 保存播放状态
                    savePlaybackState()
                    return
                }
            }
        }
        // 直接调用playCurrentSong，它会自动更新通知
        playCurrentSong()
        // 保存进度
        savePlaybackState()
    }

    fun playPrevious() {
        if (songList.isEmpty()) {
            // 如果songList为空，通知MainActivity重新设置歌曲列表
            // 这样可以确保播放顺序与用户界面一致
            Log.d(TAG, "songList is empty, requesting MainActivity to reset song list")
            _requestSongList.postValue(Event(true))
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
        // 保存进度
        savePlaybackState()
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

    // 安全地检查MediaPlayer是否在播放状态
    private fun isMediaPlayerPlaying(): Boolean {
        return try {
            if (isMediaPlayerReleased) {
                false
            } else {
                mediaPlayer.isPlaying
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "isMediaPlayerPlaying: MediaPlayer state error", e)
            false
        }
    }

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
                _wasPlayingBeforeFocusLoss = isMediaPlayerPlaying()
                pause()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                // 长时间失去音频焦点，需要暂停播放
                _wasPlayingBeforeFocusLoss = isMediaPlayerPlaying()
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
                } catch (e: IllegalStateException) {
                    // MediaPlayer处于Error或Idle状态（如夜间模式切换导致Activity重建时）
                    Log.e(TAG, "startPositionUpdates: MediaPlayer state error", e)
                }
            }
        }.start()
    }

    private fun updateMediaSessionPlaybackState() {
        val playbackState = mediaPlayer.let { player ->
            val state = try {
                if (player.isPlaying) {
                    android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                } else {
                    android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "updateMediaSessionPlaybackState: MediaPlayer state error", e)
                android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
            }
            val position = try {
                player.currentPosition.toLong()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "updateMediaSessionPlaybackState: MediaPlayer currentPosition error", e)
                0L
            }
            android.support.v4.media.session.PlaybackStateCompat.Builder()
                .setState(state, position, 1.0f).setActions(
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT or android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or android.support.v4.media.session.PlaybackStateCompat.ACTION_SEEK_TO
                ).build()
        }
        mediaSession.setPlaybackState(playbackState)

        // 更新MediaSession的元数据，包含歌曲信息
        updateMediaSessionMetadata()
    }

    private fun updateMediaSessionMetadata() {
        val currentSong = _currentSong.value ?: return
        val metadata = android.support.v4.media.MediaMetadataCompat.Builder().putString(
            android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.title
        ).putString(
            android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.artist
        ).putString(
            android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM, currentSong.album
        ).putLong(
            android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION,
            mediaPlayer.duration.toLong()
        ).build()
        mediaSession.setMetadata(metadata)
    }

    fun updateNotification(currentSong: Song? = _currentSong.value) {
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
                R.drawable.ic_pause, "Pause", createActionPendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play, "Play", createActionPendingIntent(ACTION_PLAY)
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(song.title)
            .setContentText("${song.artist} - ${song.album}").setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(pendingIntent).addAction(
                NotificationCompat.Action(
                    R.drawable.ic_previous, "Previous", createActionPendingIntent(ACTION_PREVIOUS)
                )
            ).addAction(playPauseAction).addAction(
                NotificationCompat.Action(
                    R.drawable.ic_next, "Next", createActionPendingIntent(ACTION_NEXT)
                )
            ).setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2)
            ).setPriority(NotificationCompat.PRIORITY_HIGH).setOngoing(isPlaying)
            .setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
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
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Volatile
    private var isMediaPlayerReleased = false

    fun savePlaybackState() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                applicationDataStore.edit { preferences ->
                    if (isMediaPlayerReleased) {
                        Log.w(TAG, "savePlaybackState: MediaPlayer already released, skip")
                        return@edit
                    }
                    val currentSong = _currentSong.value
                    if (currentSong != null) {
                        preferences[DataStoreKeys.CURRENT_SONG_ID] = currentSong.id
                        preferences[DataStoreKeys.SONG_TITLE] = currentSong.title
                        preferences[DataStoreKeys.SONG_ARTIST] = currentSong.artist
                        preferences[DataStoreKeys.SONG_PATH] = currentSong.path
                        try {
                            preferences[DataStoreKeys.CURRENT_POSITION] =
                                mediaPlayer.currentPosition
                            preferences[DataStoreKeys.IS_PLAYING] =
                                if (mediaPlayer.isPlaying) 1 else 0
                        } catch (e: IllegalStateException) {
                            Log.e(TAG, "savePlaybackState: MediaPlayer state error", e)
                        }
                        // 保存播放模式
                        preferences[DataStoreKeys.PLAY_MODE] = playMode.ordinal
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
            } catch (e: Exception) {
                Log.e(TAG, "savePlaybackState failed", e)
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
                // 恢复播放模式
                val savedPlayModeOrdinal = preferences[DataStoreKeys.PLAY_MODE] ?: 0
                playMode = PlayMode.entries.getOrElse(savedPlayModeOrdinal) { PlayMode.ALL_LOOP }
                _playModeLiveData.postValue(playMode)
                Log.d(TAG, "loadPlaybackState: restored playMode=$playMode")

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
                    setCurrentSong(restoredSong)

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

                    // 加载歌曲列表到service，确保播放完成后能自动播放下一首
                    loadSongListFromDatabase()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading playback state: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // 从数据库加载歌曲列表
    private fun loadSongListFromDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = MusicDatabase.getDatabase(this@MusicService)
                val songs = database.songDao().getAllSongs().first()

                if (songs.isNotEmpty()) {
                    val currentSongId = _currentSong.value?.id
                    val currentIndexInList = if (currentSongId != null) {
                        songs.indexOfFirst { it.id == currentSongId }
                    } else {
                        -1
                    }

                    val startIndex = if (currentIndexInList >= 0) currentIndexInList else 0

                    withContext(Dispatchers.Main) {
                        setSongList(songs, startIndex)
                        Log.d(
                            TAG,
                            "Loaded ${songs.size} songs from database, current song at index $startIndex"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading song list from database: ${e.message}")
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

/**
 * 一次性事件包装类，避免LiveData重复触发
 */
open class Event<out T>(private val content: T) {
    private var hasBeenHandled = false

    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }

    fun peekContent(): T = content
}