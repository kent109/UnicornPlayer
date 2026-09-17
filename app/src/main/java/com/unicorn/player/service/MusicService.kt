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
import android.media.audiofx.PresetReverb
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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bullhead.equalizer.AudioEffectManager
import com.bullhead.equalizer.EqualizerModel
import com.bullhead.equalizer.Settings
import com.unicorn.player.MainActivity
import com.unicorn.player.R
import com.unicorn.player.database.MusicDatabase
import com.unicorn.player.model.Song
import com.unicorn.player.util.LogWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

// 使用全局Application Context的DataStore
val Context.applicationDataStore: DataStore<Preferences> by preferencesDataStore(name = "music_player_state")

// 播放来源变化通知器
object PlaySourceManager {
    private val _playSourceTagChanged = MutableLiveData<String>()
    val playSourceTagChanged: LiveData<String> = _playSourceTagChanged

    private var currentSourceTag = PlaySource.SONGS

    fun notifyPlaySourceChanged(tag: String) {
        currentSourceTag = tag
        _playSourceTagChanged.postValue(currentSourceTag)
    }
}

object DataStoreKeys {
    val CURRENT_SONG_ID = longPreferencesKey("current_song_id")
    val CURRENT_POSITION = intPreferencesKey("current_position")
    val IS_PLAYING = intPreferencesKey("is_playing") // 0=暂停, 1=播放
    val SONG_TITLE = stringPreferencesKey("song_title")
    val SONG_ARTIST = stringPreferencesKey("song_artist")
    val SONG_PATH = stringPreferencesKey("song_path")
    val PLAY_MODE = intPreferencesKey("play_mode")

    // 标记用户从最近任务移除应用，防止服务重启后恢复通知
    val TASK_REMOVED_FLAG = intPreferencesKey("task_removed_flag")

    // 播放来源标签：f0 / f1$歌手名 / f2$专辑名 / f3$歌单名；默认 f0（全部歌曲）
    val PLAY_SOURCE_TAG = stringPreferencesKey("play_source_tag")

    // 用户从列表中隐藏的歌曲 ID 集合（从列表中删除的歌曲），下拉刷新时清除
    val HIDDEN_SONG_IDS = stringSetPreferencesKey("hidden_song_ids")

    // 均衡器相关键
    val IS_EQUALIZER_ENABLED = intPreferencesKey("is_equalizer_enabled")
    val EQUALIZER_BAND_LEVELS = stringPreferencesKey("equalizer_band_levels")
    val EQUALIZER_PRESET_POS = intPreferencesKey("equalizer_preset_pos")
    val BASS_STRENGTH = intPreferencesKey("bass_strength")
    val REVERB_PRESET = intPreferencesKey("reverb_preset")
}

/**
 * 播放来源标签工具：记录用户是从哪个入口点进来播放的，用于恢复时重建作用域内的歌曲列表。
 *
 * 标签格式：f0（全部歌曲）/ f1$歌手名 / f2$专辑名 / f3$歌单名。
 * `$` 为类型与名称的分隔符，按首个 `$` 切分；类型前缀本身不含 `$`。
 */
object PlaySource {
    const val SONGS = "f0"
    const val ARTIST = "f1"
    const val ALBUM = "f2"
    const val PLAYLIST = "f3"

    /** 构建来源标签；名称为空时退化为纯类型，如 "f0"（不带尾随 $） */
    fun build(type: String, name: String): String =
        if (name.isEmpty()) type else "$type$$name"

    /** 解析标签：(类型, 名称)；对 f0 返回 ("f0", "") */
    fun parse(tag: String): Pair<String, String> {
        val idx = tag.indexOf('$')
        return if (idx >= 0) tag.substring(0, idx) to tag.substring(idx + 1) else tag to ""
    }
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
        // 重启恢复链中的典型竞争：Service 的 songList 在 DataStore 恢复出 currentSong 之前就被
        // MainActivity 装填（updateServiceSongList 用 index=0 兜底），导致 currentIndex=0 并不指向
        // currentSong，prev/next 就会从错误的 0 偏移，总是播同一首固定 item。
        // currentSong 是最终确定的真值，因此在它被设到这里时，把它在 songList 中的位置同步到 currentIndex，
        // 重建不变式：songList[currentIndex].id == currentSong.id。
        if (songList.isNotEmpty()) {
            val realIndex = songList.indexOfFirst { it.id == song.id }
            if (realIndex >= 0) {
                currentIndex = realIndex
            }
        }
    }

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _currentPosition = MutableLiveData(0)
    val currentPosition: LiveData<Int> = _currentPosition

    private var playStateFuture: ScheduledFuture<*>? = null

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

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
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
                    handleBluetoothDeviceDisconnect(intent, "disconnected")
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
                    if (state == android.bluetooth.BluetoothDevice.BOND_NONE) {
                        handleBluetoothDeviceDisconnect(intent, "bond state changed")
                    }
                }
            }
        }
    }

    // 标记loadPlaybackState是否已经被调用过
    private var isPlaybackStateLoaded = false

    // 标记用户重新打开应用后需要显示通知（用于从最近任务移除后，异步加载完成前用户重新打开应用的场景）
    // 注意：此标志仅在 isTaskRemoved=false 时使用，isTaskRemoved=true 时不应该触发通知显示
    private var pendingNotificationToShow = false

    // 标记Service是否重建
    private var serviceCreate = false

    // 播放模式 - 默认为全部循环
    private var playMode = PlayMode.ALL_LOOP

    // 当前播放来源标签（f0 / f1$name / f2$name / $f3$name），默认 f0 全部歌曲
    @Volatile
    private var playSourceTag: String = PlaySource.SONGS

    // 手动同步（syncPlaylistSongList）的时间戳，用于防止 loadSongListFromDatabase 覆盖正确的列表
    @Volatile
    private var lastManualSyncTime: Long = 0L

    /**
     * 由播放入口调用，标记本次播放的作用域来源。
     * 「仅更新列表」的内部路径（updateServiceSongList、resetSongListFromDatabase 等）不应调用此方法。
     */
    fun setPlaySource(tag: String) {
        playSourceTag = tag
        PlaySourceManager.notifyPlaySourceChanged(tag)
    }

    /**
     * 判断当前播放来源是否为全部歌曲（f0）。
     * 用于 updateServiceSongList 判断是否可以用全部歌曲列表覆盖播放列表。
     * 只有 f0 时才允许覆盖；f1（歌手）、f2（专辑）、f3（歌单）的列表由各自 Activity 管理。
     */
    fun isPlayingFromSongs(): Boolean {
        val (sourceType, _) = PlaySource.parse(playSourceTag)
        return sourceType == PlaySource.SONGS
    }

    /**
     * 删除歌单时，检查当前是否正在播放被删除的歌单。
     * 如果当前播放来源是 f3 且歌单 ID 匹配，则将播放列表替换为全部歌曲（f0），
     * 但不中断当前正在播放的歌曲。
     *
     * @param deletedPlaylistIds 被删除的歌单 ID 集合
     * @return true 如果当前播放的歌单被删除并执行了切换
     */
    fun handlePlaylistDeleted(deletedPlaylistIds: Set<Long>): Boolean {
        val (sourceType, sourceName) = PlaySource.parse(playSourceTag)
        if (sourceType != PlaySource.PLAYLIST) return false
        val currentPlaylistId = sourceName.toLongOrNull()
        if (currentPlaylistId == null || currentPlaylistId !in deletedPlaylistIds) return false

        // 重置播放来源为 f0，通知 UI 更新高亮
        setPlaySource(PlaySource.SONGS)
        // 保存状态（playSourceTag 已变为 f0，当前歌曲信息保留）
        savePlaybackState()
        // 异步加载全部歌曲列表，保持当前歌曲不中断
        loadSongListFromDatabase(PlaySource.SONGS, "")
        return true
    }

    enum class PlayMode {
        ALL_LOOP,       // 全部循环
        SINGLE_LOOP,    // 单曲循环
        RANDOM,         // 随机播放
        SEQUENCE        // 顺序播放
    }

    companion object {
        // 排序模式，ordinal 与 sort_mode_prefs 存储值一致，默认 BY_TIME
        enum class SortMode { BY_TIME, BY_TITLE, BY_ARTIST }

        fun sortSongs(songs: List<Song>, mode: SortMode): List<Song> = when (mode) {
            SortMode.BY_TIME -> songs.sortedByDescending { it.lastModified }
            SortMode.BY_TITLE -> songs.sortedBy { it.title.lowercase() }
            SortMode.BY_ARTIST -> songs.sortedBy { it.artist.lowercase() }
        }

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

        serviceCreate = true

        createNotificationChannel()

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            setOnCompletionListener {
                // 强制更新进度为100%，避免最后一次进度更新不到位
                try {
                    _currentPosition.postValue(mediaPlayer.duration)
                } catch (_: Exception) {
                    // MediaPlayer可能已释放，忽略
                }

                // 顺序播放模式下最后一首自然播放完毕，只更新 UI 状态，不操作 MediaPlayer
                if (playMode == PlayMode.SEQUENCE && currentIndex >= songList.size - 1) {
                    _isPlaying.value = false
                    updateNotification()
                    updateMediaSessionPlaybackState()
                    savePlaybackState()
                } else {
                    playNext()
                }
            }
        }

        mediaSession = MediaSessionCompat(this, "MusicService")

        // 先同步加载播放模式，避免图标闪烁
        runBlocking {
            try {
                val preferences = applicationDataStore.data.first()
                val savedPlayModeOrdinal = preferences[DataStoreKeys.PLAY_MODE] ?: 0
                val savedPlayMode =
                    PlayMode.entries.getOrElse(savedPlayModeOrdinal) { PlayMode.ALL_LOOP }
                playMode = savedPlayMode
                _playModeLiveData.value = savedPlayMode
                Log.d(TAG, "onCreate: loaded playMode=$savedPlayMode")
            } catch (e: Exception) {
                LogWriter.writeError(TAG, "Failed to load play mode", e)
            }
        }

        // 服务被系统重启时（START_STICKY），不恢复到旧进度
        // 以 MusicService 当前进度为准，避免跳转到过时的位置；
        // 无播放进度时不自动恢复歌曲到底部播放条
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

        // 关闭线程池
        shutdownThreadPool()

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

        // 销毁音频效果管理器
        AudioEffectManager.destroy()
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

                // 记录播放入口来源（f0 全部歌曲 / f1 歌手 / f2 专辑 / f3 歌单），默认 f0
                val sourceTag = intent.getStringExtra("sourceTag") ?: PlaySource.SONGS
                setPlaySource(sourceTag)

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
                    kotlinx.coroutines.delay(100.milliseconds) // 短暂延迟确保保存完成
                    stopSelf()
                }
            }

            else -> {
                if (intent != null && _currentSong.value != null) {
                    // 用户主动启动服务，显示通知
                    updateNotification()
                } else if (intent == null && _currentSong.value != null) {
                    // 服务被 START_STICKY 重启，取消通知（用户已从最近任务移除）
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else if (intent != null && _currentSong.value == null) {
                    // 用户重新打开应用，但异步加载尚未完成
                    // 设置标志让 loadPlaybackState 完成后显示通知
                    pendingNotificationToShow = true
                }
                // 加载进度
                if (intent != null && intent.getStringExtra(MainActivity.KEY_CREATE) != null) {
                    // 只在带有 KEY_CREATE 标记的 startService 调用中消费 serviceCreate，
                    // 避免其他 startService 调用（如 MusicManager.bind 的 startService）
                    // 提前消费标志导致 isServiceCreate 变为 false
                    val isServiceCreate = serviceCreate
                    serviceCreate = false
                    val restore =
                        intent.getIntExtra(MainActivity.KEY_RESTORE, MainActivity.NORMAL_CREATE)
                    // 完全重建才需要加载历史进度，例如：最近任务划掉(组件全部被杀，进程未死)、强行停止(整个进程被杀)
                    // 长期在后台灭屏播放，Activity可能被杀，Service未死，重新绑定服务后，不需要加载历史进度(以当前播放进度为准)
                    // 长期在后台灭屏不播放，跟强行停止类似，重启时好像系统会恢复状态
                    val restorePosition = isServiceCreate || restore == MainActivity.NORMAL_CREATE
                    // 加载播放状态和均衡器设置
                    // 传入 restore 用于判断是否为 Activity 被回收后恢复（RESTORE_CREATE），此时需要自动恢复播放
                    loadPlaybackState(isServiceCreate, restorePosition, restore)
                }
            }
        }
        return START_NOT_STICKY
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
            LogWriter.writeError(TAG, "Song file not found: ${song.path}")
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
            // 确保音频效果管理器已初始化（在 MediaPlayer 准备好之后）
            initializeAudioEffects()
            mediaPlayer.start()
            // 播放后立即更新通知和状态
            updateNotification(song)
            updateMediaSessionPlaybackState()
            // 重置跳过标志
            isSkippingFailedSong = false
            // 开始监听播放状态
            startPlayStateObserver()
        } catch (e: IOException) {
            LogWriter.writeError(TAG, "Error playing song: ${e.message}", e)
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
            loadPlaybackState(serviceCreate)
            return
        }

        if (!isMediaPlayerPlaying()) {
            try {
                val currentPos = mediaPlayer.currentPosition
                val duration = mediaPlayer.duration

                if (duration in 1..currentPos) {
                    // 播放完成（PlaybackCompleted 状态），seek 到开头重新播放
                    mediaPlayer.seekTo(0)
                } else if (currentPos == 0 && duration == 0) {
                    // 未准备或已释放，重新准备
                    _currentSong.value?.let { song ->
                        try {
                            mediaPlayer.reset()
                            mediaPlayer.setDataSource(song.path)
                            mediaPlayer.prepare()
                        } catch (e: IOException) {
                            LogWriter.writeError(
                                TAG,
                                "Error preparing media player: ${e.message}",
                                e
                            )
                            e.printStackTrace()
                            return
                        }
                    }
                }
                // 其他情况（暂停状态、Prepared 状态等）直接 start
            } catch (e: IllegalStateException) {
                LogWriter.writeError(TAG, "play: MediaPlayer state error", e)
                // MediaPlayer 处于无效状态，尝试重新准备
                _currentSong.value?.let { song ->
                    try {
                        mediaPlayer.reset()
                        mediaPlayer.setDataSource(song.path)
                        mediaPlayer.prepare()
                    } catch (e: Exception) {
                        LogWriter.writeError(
                            TAG,
                            "Error re-preparing media player: ${e.message}",
                            e
                        )
                        return
                    }
                }
            }
        }

        try {
            // 确保音频效果管理器已初始化（在 MediaPlayer 准备好之后）
            initializeAudioEffects()

            mediaPlayer.start()
            // 立即更新播放状态为true，确保通知栏能正确显示
            _isPlaying.value = true
            // 立即更新通知栏和MediaSession状态
            updateNotification()
            updateMediaSessionPlaybackState()
        } catch (e: IllegalStateException) {
            LogWriter.writeError(TAG, "play: MediaPlayer start failed, trying to re-prepare", e)
            // start 失败，尝试重新准备并播放
            _currentSong.value?.let { song ->
                try {
                    mediaPlayer.reset()
                    mediaPlayer.setDataSource(song.path)
                    mediaPlayer.prepare()
                    mediaPlayer.start()
                    _isPlaying.value = true
                    updateNotification()
                    updateMediaSessionPlaybackState()
                } catch (e2: Exception) {
                    LogWriter.writeError(
                        TAG,
                        "play: Failed to recover MediaPlayer: ${e2.message}",
                        e2
                    )
                    _isPlaying.value = false
                }
            }
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
            // 停止播放状态监听
            stopPlayStateObserver()
        }
    }

    /**
     * 移除当前播放歌曲（歌曲被删除时调用）：
     * 停止播放、清空当前歌曲、从 songList 移除、移除通知、保存空状态。
     * 底部播放栏会因 currentSong 观察者收到 null 而自动清空。
     */
    fun removeCurrentSong() {
        // 停止播放
        try {
            if (isMediaPlayerPlaying()) {
                mediaPlayer.pause()
            }
        } catch (_: IllegalStateException) {
            // MediaPlayer 可能已释放或处于错误状态，忽略
        }
        _isPlaying.value = false

        // 从 songList 中移除当前歌曲
        val currentId = _currentSong.value?.id
        if (currentId != null) {
            songList.removeAll { it.id == currentId }
        }
        currentIndex = 0

        // 清空当前歌曲
        _currentSong.value = null
        _currentPosition.value = 0

        // 移除通知
        stopForeground(STOP_FOREGROUND_REMOVE)

        // 保存空状态（清除 DataStore 中的播放状态）
        savePlaybackState()

        // 更新 MediaSession 状态
        updateMediaSessionPlaybackState()
    }

    /**
     * 清空播放列表并停止播放（扫描无歌曲时调用）。
     * 仅清空内存中的 songList，不清除 DataStore 持久化的播放状态。
     * 底部播放栏会因 currentSong 观察者收到 null 而自动清空。
     */
    fun clearSongListAndStop() {
        // 停止播放
        try {
            if (isMediaPlayerPlaying()) {
                mediaPlayer.stop()
            }
            mediaPlayer.reset()
        } catch (_: IllegalStateException) {
            // MediaPlayer 可能已释放或处于错误状态，忽略
        }
        _isPlaying.value = false

        // 清空内存中的歌曲列表
        songList.clear()
        _songList.value = emptyList()
        currentIndex = 0

        // 清空当前歌曲（触发底部播放栏重置）
        _currentSong.value = null
        _currentPosition.value = 0

        // 移除通知
        stopForeground(STOP_FOREGROUND_REMOVE)

        // 保存空状态
        savePlaybackState()

        // 更新 MediaSession 状态
        updateMediaSessionPlaybackState()
    }

    fun setPlayMode(mode: PlayMode) {
        playMode = mode
        _playModeLiveData.postValue(mode)
        savePlaybackState()
    }

    // 获取当前播放模式
    fun getPlayMode(): PlayMode = playMode

    /**
     * 若当前正在播放指定歌单，则用提供的歌曲列表同步更新播放列表。
     * 在歌曲被添加到歌单后调用，确保播放列表与歌单内容一致。
     *
     * 直接接收歌曲列表而非重新查询数据库，避免异步时序问题导致列表退化为全部歌曲。
     *
     * @param playlistId 歌单 ID
     * @param songs 歌单的最新歌曲列表
     */
    fun syncPlaylistSongList(playlistId: Long, songs: List<Song>) {
        val (sourceType, sourceName) = PlaySource.parse(playSourceTag)
        if (sourceType == PlaySource.PLAYLIST && sourceName == playlistId.toString()) {
            if (songs.isEmpty()) return
            val currentSong = _currentSong.value
            val currentIndex = if (currentSong != null) {
                songs.indexOfFirst { it.id == currentSong.id }.takeIf { it >= 0 } ?: 0
            } else {
                0
            }
            // setSongList 内部使用 setValue，必须在主线程调用
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                setSongList(songs, currentIndex)
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    setSongList(songs, currentIndex)
                }
            }
            // 标记手动同步时间戳，防止 loadSongListFromDatabase 覆盖正确的列表
            lastManualSyncTime = System.currentTimeMillis()
            Log.d(
                TAG,
                "syncPlaylistSongList: updated playlist '$playlistId' with ${songs.size} songs"
            )
        }
    }

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
                // 顺序播放：到达最后一首后点击下一首不做处理
                if (currentIndex >= songList.size - 1) {
                    return
                }
                currentIndex++
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

        currentIndex = when {
            // 顺序播放模式：到达第一首后点击上一首不做处理
            playMode == PlayMode.SEQUENCE && currentIndex == 0 -> return
            // 单曲循环模式：重新播放当前歌曲
            playMode == PlayMode.SINGLE_LOOP -> currentIndex
            // 随机播放模式：随机选择一首（尽量不选当前）
            playMode == PlayMode.RANDOM -> {
                if (songList.size > 1) {
                    val newIndex = (0 until songList.size).random()
                    if (newIndex == currentIndex && songList.size > 1) {
                        (currentIndex + 1) % songList.size
                    } else {
                        newIndex
                    }
                } else {
                    currentIndex
                }
            }

            currentIndex > 0 -> currentIndex - 1
            else -> songList.size - 1
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

    // 兼容不同API级别的getParcelableExtra（API 33+使用Class版本）
    @Suppress("DEPRECATION")
    private fun <T : android.os.Parcelable> getParcelableExtraCompat(
        intent: Intent,
        key: String,
        clazz: Class<T>
    ): T? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, clazz)
        } else {
            intent.getParcelableExtra(key) as? T
        }
    }

    // 检查当前是否通过A2DP蓝牙音频输出（替代废弃的isBluetoothA2dpOn）
    private fun isBluetoothA2dpConnected(): Boolean {
        return try {
            val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                val a2dpState = bluetoothAdapter.getProfileConnectionState(
                    android.bluetooth.BluetoothProfile.A2DP
                )
                a2dpState == android.bluetooth.BluetoothProfile.STATE_CONNECTED
            } else {
                false
            }
        } catch (e: SecurityException) {
            LogWriter.writeError(TAG, "BLUETOOTH_CONNECT permission denied for A2DP check", e)
            false
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "Error checking A2DP connection state", e)
            false
        }
    }

    // 安全地检查MediaPlayer是否在播放状态
    private fun isMediaPlayerPlaying(): Boolean {
        return try {
            if (isMediaPlayerReleased) {
                false
            } else {
                mediaPlayer.isPlaying
            }
        } catch (e: IllegalStateException) {
            LogWriter.writeError(TAG, "isMediaPlayerPlaying: MediaPlayer state error", e)
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

    // 统一处理蓝牙设备断开/未配对事件（ACL_DISCONNECTED 和 BOND_STATE_CHANGED）
    private fun handleBluetoothDeviceDisconnect(intent: Intent, eventType: String) {
        val device = getParcelableExtraCompat(
            intent,
            android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
            android.bluetooth.BluetoothDevice::class.java
        )
        // 检查 BLUETOOTH_CONNECT 权限后再获取设备名称
        val deviceName = if (ContextCompat.checkSelfPermission(
                this@MusicService,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            device?.name
        } else {
            "unknown"
        }
        Log.d(TAG, "Bluetooth device $eventType: $deviceName, type: ${device?.type}")

        // 只有当前通过蓝牙音频输出时才可能暂停
        if (device == null || !isBluetoothA2dpConnected()) {
            return
        }

        // 如果断开的是 LE 设备（如共享单车），不是音频设备，不需要暂停
        if (device.type == android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE) {
            Log.d(TAG, "LE device $eventType, not pausing")
            return
        }

        // 经典蓝牙设备断开，可能是音频设备，暂停
        if (_isPlaying.value == true) {
            pause()
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
        try {
            if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                executorService.shutdownNow()
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
            executorService.shutdownNow()
        }
    }

    private fun shutdownThreadPool() {
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

    private fun startPlayStateObserver() {
        if (playStateFuture != null) {
            return
        }
        playStateFuture = executorService.scheduleWithFixedDelay({
            savePlaybackState()
        }, 0, 5, TimeUnit.SECONDS)
    }

    private fun stopPlayStateObserver() {
        if (playStateFuture == null) {
            return
        }
        if (!playStateFuture!!.isDone && !playStateFuture!!.isCancelled) {
            playStateFuture!!.cancel(false)
        }
        playStateFuture = null
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

    fun getAudioSessionId(): Int {
        return try {
            mediaPlayer.audioSessionId
        } catch (e: IllegalStateException) {
            LogWriter.writeError(TAG, "getAudioSessionId: MediaPlayer state error", e)
            0
        }
    }

    /**
     * 将 MusicService 当前播放进度同步到 DataStore
     * 用于 Activity 重新绑定服务后，确保保存的进度与实际进度一致
     */
    fun syncCurrentPositionToDataStore() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (isMediaPlayerReleased) return@launch
                val currentSong = _currentSong.value ?: return@launch
                applicationDataStore.edit { preferences ->
                    preferences[DataStoreKeys.CURRENT_SONG_ID] = currentSong.id
                    preferences[DataStoreKeys.SONG_TITLE] = currentSong.title
                    preferences[DataStoreKeys.SONG_ARTIST] = currentSong.artist
                    preferences[DataStoreKeys.SONG_PATH] = currentSong.path
                    try {
                        preferences[DataStoreKeys.CURRENT_POSITION] = mediaPlayer.currentPosition
                        preferences[DataStoreKeys.IS_PLAYING] = if (mediaPlayer.isPlaying) 1 else 0
                    } catch (e: IllegalStateException) {
                        LogWriter.writeError(
                            TAG,
                            "syncCurrentPositionToDataStore: MediaPlayer state error",
                            e
                        )
                    }
                    preferences[DataStoreKeys.PLAY_MODE] = playMode.ordinal
                    // 播放入口来源（f0 全部歌曲 / f1 歌手 / f2 专辑 / f3 歌单）
                    preferences[DataStoreKeys.PLAY_SOURCE_TAG] = playSourceTag
                }
                Log.d(
                    TAG,
                    "syncCurrentPositionToDataStore: position=${mediaPlayer.currentPosition}"
                )
            } catch (e: Exception) {
                LogWriter.writeError(TAG, "syncCurrentPositionToDataStore failed", e)
            }
        }
    }

    private fun startPositionUpdates() {
        Thread {
            while (!isMediaPlayerReleased) {
                try {
                    if (mediaPlayer.isPlaying) {
                        _currentPosition.postValue(mediaPlayer.currentPosition)
                        // 更新MediaSession的播放状态，包含进度信息
                        updateMediaSessionPlaybackState()
                    }
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    // 线程被中断，退出循环
                    break
                } catch (e: IllegalStateException) {
                    // MediaPlayer处于Error或Idle状态（如夜间模式切换导致Activity重建时）
                    // 停止循环，避免持续报错
                    LogWriter.writeError(
                        TAG,
                        "startPositionUpdates: MediaPlayer state error, stopping",
                        e
                    )
                    break
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
                LogWriter.writeError(
                    TAG,
                    "updateMediaSessionPlaybackState: MediaPlayer state error",
                    e
                )
                android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
            }
            val position = try {
                player.currentPosition.toLong()
            } catch (e: IllegalStateException) {
                LogWriter.writeError(
                    TAG,
                    "updateMediaSessionPlaybackState: MediaPlayer currentPosition error",
                    e
                )
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
                startForeground(NOTIFICATION_ID, notification)
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
                            LogWriter.writeError(
                                TAG,
                                "savePlaybackState: MediaPlayer state error",
                                e
                            )
                          }
                          // 保存播放模式
                          preferences[DataStoreKeys.PLAY_MODE] = playMode.ordinal
                          // 播放入口来源（f0 全部歌曲 / f1 歌手 / f2 专辑 / f3 歌单）
                          preferences[DataStoreKeys.PLAY_SOURCE_TAG] = playSourceTag
                          // 保存均衡器设置
                          preferences[DataStoreKeys.IS_EQUALIZER_ENABLED] = if (Settings.isEqualizerEnabled) 1 else 0
                          preferences[DataStoreKeys.EQUALIZER_BAND_LEVELS] = Settings.seekbarpos.joinToString(",")
                          preferences[DataStoreKeys.EQUALIZER_PRESET_POS] = Settings.presetPos
                          // 防御：避免把越界值（如旧版本残留的 -1）写入 DataStore 形成脏数据循环
                          val bassToSave = Settings.bassStrength.toInt()
                          preferences[DataStoreKeys.BASS_STRENGTH] = if (bassToSave in 0..1000) bassToSave else 0
                          val reverbToSave = Settings.reverbPreset.toInt()
                          preferences[DataStoreKeys.REVERB_PRESET] = if (reverbToSave in 0..6) reverbToSave else 0
                      } else {
                          // 清除保存的状态（包括上次播放进度、播放入口标签）
                          preferences.remove(DataStoreKeys.CURRENT_SONG_ID)
                          preferences.remove(DataStoreKeys.SONG_TITLE)
                          preferences.remove(DataStoreKeys.SONG_ARTIST)
                          preferences.remove(DataStoreKeys.SONG_PATH)
                          preferences.remove(DataStoreKeys.CURRENT_POSITION)
                          preferences.remove(DataStoreKeys.IS_PLAYING)
                          preferences.remove(DataStoreKeys.PLAY_SOURCE_TAG)
                          preferences.remove(DataStoreKeys.IS_EQUALIZER_ENABLED)
                          preferences.remove(DataStoreKeys.EQUALIZER_BAND_LEVELS)
                          preferences.remove(DataStoreKeys.EQUALIZER_PRESET_POS)
                          preferences.remove(DataStoreKeys.BASS_STRENGTH)
                          preferences.remove(DataStoreKeys.REVERB_PRESET)
                      }
                }
            } catch (e: Exception) {
                LogWriter.writeError(TAG, "savePlaybackState failed", e)
              }
          }
      }

      fun initializeAudioEffects() {
          if (AudioEffectManager.areEffectsEnabled()) {
              return
          }

          fun tryInitialize() {
              try {
                  val sessionId = mediaPlayer.audioSessionId
                  if (sessionId != 0) {
                      AudioEffectManager.initialize(this, sessionId)
                      Log.d(TAG, "Audio effects initialized, session ID: $sessionId, enabled: ${Settings.isEqualizerEnabled}")
                      return
                  }
              } catch (e: Exception) {
                  Log.e(TAG, "Failed to initialize audio effects", e)
              }
          }

          tryInitialize()
      }

    fun loadPlaybackState(
        isServiceCreate: Boolean, restorePosition: Boolean = true,
        restoreFlag: Int = 0
    ) {
        // 如果已经加载过播放状态，不需要重复加载
        if (isPlaybackStateLoaded) {
            Log.i(TAG, "loadPlaybackState: already loaded, skip")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val preferences = applicationDataStore.data.first()
                // 检查用户是否从最近任务移除了应用
                val isTaskRemoved = preferences[DataStoreKeys.TASK_REMOVED_FLAG] == 1
                if (isTaskRemoved) {
                    // 清除标志，下次正常启动时可以正常显示通知
                    applicationDataStore.edit { p ->
                        p.remove(DataStoreKeys.TASK_REMOVED_FLAG)
                    }
                }

                // 加载均衡器设置（必须在currentPosition检查之前）
                val isEqualizerEnabled = preferences[DataStoreKeys.IS_EQUALIZER_ENABLED] == 1
                Settings.isEqualizerEnabled = isEqualizerEnabled

                // 加载均衡器参数（无论开关是否启用都读取，保留关闭开关前的预设/低音/虚拟值，
                // 下次打开开关时能恢复，否则 Settings.presetPos 会保持默认 0=自定义）
                val savedBandLevels = preferences[DataStoreKeys.EQUALIZER_BAND_LEVELS]
                if (savedBandLevels != null) {
                    val bandLevelsArray = savedBandLevels.split(",")
                    for (i in bandLevelsArray.indices) {
                        Settings.seekbarpos[i] = bandLevelsArray[i].toInt()
                    }
                }
                Settings.presetPos = preferences[DataStoreKeys.EQUALIZER_PRESET_POS] ?: 0
                // 兜底：DataStore 可能残留旧版本写入的 -1，强制收敛到合法范围 [0, 1000]
                val rawBass = preferences[DataStoreKeys.BASS_STRENGTH]?.toShort() ?: 0
                Settings.bassStrength = if (rawBass < 0 || rawBass > 1000) 0 else rawBass
                // 兜底：reverbPreset 合法范围 [0, 6]，-1 视为未设置 → PRESET_NONE
                val rawReverb = preferences[DataStoreKeys.REVERB_PRESET]?.toShort() ?: 0
                val reverbPreset = if (rawReverb < 0 || rawReverb > 6) 0 else rawReverb

                if (Settings.equalizerModel == null) {
                    Settings.equalizerModel = EqualizerModel()
                    Settings.equalizerModel.reverbPreset = reverbPreset
                    Settings.equalizerModel.bassStrength = Settings.bassStrength
                }

                if (isTaskRemoved) {
                    Log.d(
                        TAG,
                        "loadPlaybackState: task was removed, will restore state but skip notification"
                    )
                    // 清除标志，下次正常启动时可以正常显示通知
                    applicationDataStore.edit { p ->
                        p.remove(DataStoreKeys.TASK_REMOVED_FLAG)
                    }
                    // 注意：不设置 pendingNotificationToShow，因为 isTaskRemoved=true 时不应显示通知
                    // 当用户重新打开应用时，onStartCommand 中 intent != null 会触发通知显示
                }

                // 尽早恢复播放来源标签（f0/f1/f2/f3），避免后续早期 return 时丢失来源
                val savedSourceTag = preferences[DataStoreKeys.PLAY_SOURCE_TAG] ?: PlaySource.SONGS
                playSourceTag = savedSourceTag
                // 通知 PlaySourceManager，让 ViewModel 更新歌手/专辑高亮
                PlaySourceManager.notifyPlaySourceChanged(savedSourceTag)
                Log.d(TAG, "loadPlaybackState: restored playSourceTag=$savedSourceTag")

                // 如果用户从最近任务移除了应用，强制恢复保存的进度
                // 因为 onTaskRemoved() 中已同步保存了正确的进度到 DataStore
                val shouldRestorePosition = restorePosition || isTaskRemoved
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
                val currentPosition = preferences[DataStoreKeys.CURRENT_POSITION] ?: -1

                // 无播放进度时不自动恢复歌曲到底部播放条（避免扫描后无进度却自动加载）
                if (currentPosition == -1) {
                    return@launch
                }
                Log.d(
                    TAG,
                    "loadPlaybackState: will restore songId=$songId, title=$songTitle, position=$currentPosition"
                )
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
                    LogWriter.writeError(TAG, "Song file not found: $songPath")
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
                    // 仅在真正加载了歌曲后才标记为已加载，避免无进度时跳过后续合法恢复
                    isPlaybackStateLoaded = true

                    // 准备媒体播放器但不立即播放
                    try {
                        // 尝试reset，如果MediaPlayer处于Error状态会抛出IllegalStateException
                        try {
                            mediaPlayer.reset()
                        } catch (e: IllegalStateException) {
                            // MediaPlayer处于Error状态，需要重新创建实例
                            // LogWriter.writeError(TAG, "MediaPlayer in error state, recreating", e)
                            Log.e(TAG, "MediaPlayer in error state, recreating", e)
                            mediaPlayer.release()
                            mediaPlayer = MediaPlayer().apply {
                                setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .setUsage(AudioAttributes.USAGE_MEDIA).build()
                                )
                                setOnCompletionListener {
                                    playNext()
                                }
                            }
                        }
                        mediaPlayer.setDataSource(songPath)
                        mediaPlayer.prepare()
                        // 根据 shouldRestorePosition 决定是否恢复到保存的进度
                        // shouldRestorePosition=false 时（服务被系统重启且任务未被移除），不恢复到旧进度
                        // 而是以 MusicService 当前进度为准，避免跳转到过时的位置
                        // shouldRestorePosition=true 时（正常启动或任务被移除后重启），恢复到保存的进度
                        if (shouldRestorePosition) {
                            seekTo(currentPosition)
                        }

                        // 不再自动恢复播放，只准备媒体播放器
                        // 更新MediaSession状态（系统媒体控件需要）
                        updateMediaSessionPlaybackState()
                        // 显示通知的条件：
                        // 1. 用户没有从最近任务移除（isTaskRemoved=false）
                        // 2. 或者用户已从最近任务移除但重新打开了应用（pendingNotificationToShow=true）
                        if (!isTaskRemoved || pendingNotificationToShow) {
                            updateNotification(restoredSong)
                            pendingNotificationToShow = false
                        }
                        // 仅在「后台播放中 Activity 被系统回收后恢复」时自动恢复播放。
                        // 判定条件：之前正在播放 && restoreFlag==RESTORE_CREATE（savedInstanceState != null）
                        // 其他场景（最近任务划掉、杀进程重启、正常启动）都不自动播放。
                        if (isPlaying == 1 && restoreFlag == MainActivity.RESTORE_CREATE) {
                            play()
                        }
                    } catch (e: IOException) {
                        LogWriter.writeError(TAG, "Error preparing media player: ${e.message}", e)
                    } catch (e: IllegalStateException) {
                        LogWriter.writeError(TAG, "Error preparing media player: ${e.message}", e)
                    }

                    // 加载歌曲列表到service，确保播放完成后能自动播放下一首
                    // 根据保存的来源标签（f0/f1/f2/f3）重建作用域内的歌曲列表
                    val (sourceType, sourceName) = PlaySource.parse(playSourceTag)
                    loadSongListFromDatabase(sourceType, sourceName, songId)
                }

                // shouldRestorePosition=false 时（服务被系统重启且任务未被移除），将当前进度同步到 DataStore
                // 确保保存的进度与 MusicService 实际进度一致
                if (!shouldRestorePosition) {
                    savePlaybackState()
                }
            } catch (e: Exception) {
                LogWriter.writeError(TAG, "Error loading playback state: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }

    // 从 SharedPreferences 同步读取排序模式（与 MusicViewModel.restoreSortMode 同逻辑）
    private fun readSortModeFromPrefs(): SortMode {
        val ordinal = try {
            getSharedPreferences("sort_mode_prefs", Context.MODE_PRIVATE)
                .getInt("sort_mode", 0)
        } catch (e: Exception) {
            0
        }
        return SortMode.entries.getOrElse(ordinal) { SortMode.BY_TIME }
    }

    /**
     * 按「播放来源」重建 songList，并按用户当前排序模式排序，
     * 确保恢复的 songList 与来源页面显示顺序一致，上一首/下一首导航停留在来源作用域内。
     *
     * @param sourceType 播放来源类型（f0 全部歌曲 / f1 歌手 / f2 专辑 / f3 歌单）
     * @param sourceName 来源名称（歌手/专辑/歌单名；f0 时为空）
     * @param songId 当前恢复的歌曲 id，用于定位 startIndex
     *
     * 退化规则：来源作用域为空（f1/f2 下已无歌曲 / f3 歌单已不存在）→ 退化到全部歌曲（f0）。
     */
    private fun loadSongListFromDatabase(
        sourceType: String,
        sourceName: String = "",
        songId: Long = 0L
    ) {
        // 记录 DB 查询开始时间，用于与手动同步时间戳比较
        val dbQueryStartTime = lastManualSyncTime
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = MusicDatabase.getDatabase(this@MusicService)
                val songsFromDb = database.songDao().getAllSongs().first()

                if (songsFromDb.isNotEmpty()) {
                    // 按当前排序模式排序，而非使用数据库默认的 title ASC
                    val sortMode = readSortModeFromPrefs()

                    // 按来源过滤；f0（或未知类型）直接使用全部歌曲
                    val scoped = when (sourceType) {
                        PlaySource.ARTIST ->
                            songsFromDb.filter { it.artist.equals(sourceName, ignoreCase = true) }

                        PlaySource.ALBUM ->
                            songsFromDb.filter { it.album.equals(sourceName, ignoreCase = true) }

                        PlaySource.PLAYLIST -> {
                            // sourceName 为歌单 ID，直接按 ID 查询歌曲列表
                            val playlistId = sourceName.toLongOrNull()
                            if (playlistId != null) {
                                database.playlistDao().getPlaylistSongs(playlistId).first()
                                    .ifEmpty { null } ?: songsFromDb
                            } else {
                                songsFromDb
                            }
                        }

                        else -> songsFromDb
                    }

                    // 来源作用域为空（f1/f2 下已无歌曲）→ 退化到全部歌曲
                    val finalSongs =
                        if (scoped.isEmpty() && sourceType != PlaySource.SONGS) songsFromDb else scoped
                    val songs = sortSongs(finalSongs, sortMode)

                    // 按 songId 或当前播放歌曲定位 startIndex
                    val currentSongId = if (songId != 0L) songId else _currentSong.value?.id
                    val currentIndexInList = if (currentSongId != null) {
                        songs.indexOfFirst { it.id == currentSongId }
                    } else {
                        -1
                    }
                    val startIndex = if (currentIndexInList > 0) currentIndexInList else 0

                    withContext(Dispatchers.Main) {
                        // 如果在此期间发生了手动同步（syncPlaylistSongList），则跳过 DB 加载结果，
                        // 避免覆盖正确的歌单歌曲列表（防止退化为全部歌曲）。
                        if (lastManualSyncTime != dbQueryStartTime) {
                            Log.d(
                                TAG,
                                "loadSongListFromDatabase: skipped, manual sync occurred during DB query (source=$sourceType, name=$sourceName)"
                            )
                            return@withContext
                        }
                        // 始终装填列表：与 UI 使用相同的来源过滤 + SortMode 排序，
                        // 确保 service 的 songList 与对应页面完全一致。
                        // setCurrentSong 负责把 currentIndex 修正到 currentSong 的真实位置。
                        setSongList(songs, startIndex)
                        val logType =
                            if (finalSongs === songsFromDb && sourceType != PlaySource.SONGS) "$sourceType(degraded)" else sourceType
                        Log.d(
                            TAG,
                            "Loaded ${songs.size} songs from database (source=$logType, name=$sourceName, mode=$sortMode), currentIndex=$startIndex"
                        )
                    }
                }
            } catch (e: Exception) {
                LogWriter.writeError(TAG, "Error loading song list from database: ${e.message}", e)
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
        // 同步保存播放状态和标志，防止异步保存未完成时服务被停止
        runBlocking {
            applicationDataStore.edit { preferences ->
                // 设置任务移除标志
                preferences[DataStoreKeys.TASK_REMOVED_FLAG] = 1
                // 同步保存播放状态
                val currentSong = _currentSong.value
                if (currentSong != null && !isMediaPlayerReleased) {
                    preferences[DataStoreKeys.CURRENT_SONG_ID] = currentSong.id
                    preferences[DataStoreKeys.SONG_TITLE] = currentSong.title
                    preferences[DataStoreKeys.SONG_ARTIST] = currentSong.artist
                    preferences[DataStoreKeys.SONG_PATH] = currentSong.path
                    try {
                        preferences[DataStoreKeys.CURRENT_POSITION] = mediaPlayer.currentPosition
                        preferences[DataStoreKeys.IS_PLAYING] = if (mediaPlayer.isPlaying) 1 else 0
                    } catch (e: IllegalStateException) {
                        LogWriter.writeError(TAG, "onTaskRemoved: MediaPlayer state error", e)
                    }
                    preferences[DataStoreKeys.PLAY_MODE] = playMode.ordinal
                    // 播放入口来源（f0 全部歌曲 / f1 歌手 / f2 专辑 / f3 歌单）
                    preferences[DataStoreKeys.PLAY_SOURCE_TAG] = playSourceTag
                }
            }
        }
        // 立即取消通知，防止进程被杀死后通知残留
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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