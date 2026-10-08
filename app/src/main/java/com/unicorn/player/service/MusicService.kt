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
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.annotation.OptIn
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.bullhead.equalizer.AudioEffectManager
import com.bullhead.equalizer.EqualizerModel
import com.bullhead.equalizer.Settings
import com.unicorn.player.MainActivity
import com.unicorn.player.R
import com.unicorn.player.ScanFilterActivity
import com.unicorn.player.database.MusicDatabase
import com.unicorn.player.equalizer.TenBandEqualizerProcessor
import com.unicorn.player.model.Song
import com.unicorn.player.playback.SongPlayableRegistry
import com.unicorn.player.scanFiltersDataStore
import com.unicorn.player.util.LogWriter
import com.unicorn.player.util.PinyinUtil
import io.github.eugenedibtsev.media3.ape.ApeSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import androidx.media3.common.AudioAttributes as Media3AudioAttributes

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

    // 用户从列表中隐藏的歌曲文件路径（从列表中删除的歌曲），是否随扫描恢复由扫描过滤开关决定
    val HIDDEN_SONG_PATHS = stringSetPreferencesKey("hidden_song_paths")

    // 旧版隐藏歌曲记录键（存 MediaStore 行 ID），仅在首次加载时迁移到 HIDDEN_SONG_PATHS 后删除
    val HIDDEN_SONG_IDS = stringSetPreferencesKey("hidden_song_ids")

    // 均衡器相关键
    val IS_EQUALIZER_ENABLED = intPreferencesKey("is_equalizer_enabled")
    val EQUALIZER_BAND_LEVELS = stringPreferencesKey("equalizer_band_levels")
    val EQUALIZER_PRESET_POS = intPreferencesKey("equalizer_preset_pos")
    val BASS_STRENGTH = intPreferencesKey("bass_strength")
    val REVERB_PRESET = intPreferencesKey("reverb_preset")

    // 设备不支持播放的音频扩展名黑名单（由 MediaCodecList 预检 + 真实播放错误累积）
    val UNSUPPORTED_AUDIO_EXTENSIONS = stringSetPreferencesKey("unsupported_audio_extensions")
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

@OptIn(markerClass = [UnstableApi::class])
class MusicService : Service() {

    private val binder = MusicBinder()
    private lateinit var player: ExoPlayer
    private lateinit var eqProcessor: TenBandEqualizerProcessor
    private lateinit var mediaSession: MediaSessionCompat

    /** 起播 PCM 级淡入时长：系统增益效果已全部禁用（EQ/低音在 App 内、系数先于
     * 信号落定），无需音量包络遮盖，置 0 关闭；仅保留处理器内 23ms 防爆淡入。
     * 若后续出现起播瞬态，可临时改回 300~800 观察对比 */
    private val startupFadeInMs = 0L

    /**
     * 服务生命周期内固定的 audioSessionId（onCreate 时预生成并 setAudioSessionId 到播放器）。
     * 让系统音效（Equalizer/BassBoost/PresetReverb）能在音频流启动前就挂到该 session 上：
     * AudioFlinger 在 AudioTrack 创建时即建立效果链，而非播放中途向运行中的流插入效果对象
     * （中途插入是冷启动开均衡器时爆音的直接来源）；同时路由切换不再更换 session，
     * 避免效果对象销毁重建带来的二次爆音。0 表示预生成失败，回退为播放器动态分配。
     */
    @Volatile
    private var presetAudioSessionId = 0

    // 恢复流程标志：loadPlaybackState 调用 player.prepare() 后置 true，
    // 在 Player.Listener.onPlaybackStateChanged(STATE_READY) 中消费，执行 seek + 可选自动播放。
    // ExoPlayer 状态机干净，无需旧的 isRestoringState 守卫，但需要在该回调中拿到 STATE_READY
    // 后才能执行 seekTo（prepared 前 duration 不可用）。
    @Volatile
    private var isPendingRestore = false
    private var pendingRestorePosition = 0
    private var pendingAutoPlay = false

    /**
     * 恢复进度是否已通过 setMediaItem(item, startPositionMs) 内嵌给播放器。
     * true 时 STATE_READY 不再补一次 seekTo——冷启动实测"prepare@0 → READY 后 seek"会让
     * media3 建第一个 AudioTrack 后立刻 flush/销毁再建第二个（渲染器 enable→flush 空转），
     * 已 enable 的均衡/低音效果链在第二个音轨 start 瞬间处理从零到大振幅的阶跃，产生爆音。
     */
    private var restorePositionEmbedded = false

    // 恢复流程中是否抑制通知（task removed 且用户未重新打开应用时不显示通知）
    private var pendingSuppressNotification = false

    // 恢复流程中用于通知的歌曲（STATE_READY 时传入 updateNotification）
    private var pendingRestoreSong: Song? = null

    // 防止 playNext/playPrevious 并发调用：歌曲即将播完时用户点击下一首，
    // onCompletion 和按钮点击可能同时触发，导致跳过两首歌
    @Volatile
    private var isNavigating = false

    private val _currentSong = MutableLiveData<Song?>()
    val currentSong: LiveData<Song?> = _currentSong

    // 公共方法设置当前歌曲
    fun setCurrentSong(song: Song) {
        val isSongChanged = _currentSong.value?.id != song.id
        _currentSong.value = song
        // 仅在真正切歌时重置进度为 0，避免打开 PlayerActivity 时进度条从实际位置跳到 0
        if (isSongChanged) {
            _currentPosition.value = 0
        }
        // 切到普通歌曲（路径不在排除目录内）时清除临时播放态，
        // 标志着从临时播放回到正常队列。路径动态判断，不依赖持久化字段。
        if (!isPathExcludedSync(song.path)) {
            clearTempPlayback()
        }
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

    // 播放错误状态：true = 当前歌曲格式不支持/播放失败，UI 应禁用进度条并置灰
    private val _isPlayableError = MutableLiveData(false)
    val isPlayableError: LiveData<Boolean> = _isPlayableError

    private var songList = mutableListOf<Song>()
    private val _songList = MutableLiveData<List<Song>>(emptyList())

    // ===== 外部文件临时播放 =====
    // isTempPlayback=true 表示当前在播放"临时歌曲"（外部打开排除目录内文件，未入库）：
    // - 不保存播放进度到 DataStore（保留上次保存的歌曲状态）
    // - 通知栏/MediaSession/耳机的 next/prev 全部禁用
    // - PlayerActivity 销毁后继续播放，自然结束或用户切歌时回到上次保存的歌曲从头播放
    @Volatile
    private var isTempPlayback = false
    private var tempSong: Song? = null
    private val _tempPlayback = MutableLiveData(false)

    // 用户配置的排除目录缓存（onCreate 同步加载）。
    // 用于动态判断当前歌曲是否为临时播放：路径在排除目录内 → 临时（不保存进度）。
    // 不持久化到 Song/数据库，路径已知即可计算。
    @Volatile
    private var excludedDirsCache: Set<String> = emptySet()

    /**
     * 同步判断路径是否落在排除目录内（使用缓存，供主线程调用）。
     */
    private fun isPathExcludedSync(path: String): Boolean =
        excludedDirsCache.any { path.startsWith(it) }

    /**
     * 从 scanFiltersDataStore 重新加载排除目录缓存（IO 调用，保证设置修改后及时生效）。
     */
    private suspend fun refreshExcludedDirsCache() {
        try {
            excludedDirsCache =
                scanFiltersDataStore.data.first()[ScanFilterActivity.EXCLUDED_DIRS] ?: emptySet()
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "refreshExcludedDirsCache failed", e)
        }
    }

    /**
     * 从 DataStore 恢复均衡器参数到 com.bullhead.equalizer.Settings。
     * - 冷启动外部播放时 loadPlaybackState 可能尚未执行或被 isSettingExternalSong 提前 return 跳过，
     *   需要主动调用保证 initializeAudioEffects() 用到正确状态。
     * - 参数（bandLevels/presetPos/bassStrength/reverbPreset）无论开关是否启用都读取，
     *   保留关闭开关前的预设，下次打开开关时能恢复。
     */
    private suspend fun restoreEqualizerSettings() {
        try {
            val preferences = applicationDataStore.data.first()
            Settings.isEqualizerEnabled = preferences[DataStoreKeys.IS_EQUALIZER_ENABLED] == 1
            val savedBandLevels = preferences[DataStoreKeys.EQUALIZER_BAND_LEVELS]
            if (savedBandLevels != null) {
                val bandLevelsArray = savedBandLevels.split(",")
                // 丢弃旧 5 段配置，仅接受 10 段格式
                if (bandLevelsArray.size == 10) {
                    for (i in bandLevelsArray.indices) {
                        Settings.seekbarpos[i] = bandLevelsArray[i].toInt()
                    }
                } else {
                    Log.w(TAG, "Discarding old ${bandLevelsArray.size}-band config (expected 10)")
                }
            }
            Settings.presetPos = preferences[DataStoreKeys.EQUALIZER_PRESET_POS] ?: 0
            // 兜底：DataStore 可能残留旧版本写入的 -1，强制收敛到合法范围 [0, 1000]
            val rawBass = preferences[DataStoreKeys.BASS_STRENGTH]?.toShort() ?: 0
            Settings.bassStrength = if (rawBass !in 0..1000) 0 else rawBass
            // 兜底：reverbPreset 合法范围 [0, 6]，-1 视为未设置 → PRESET_NONE
            val rawReverb = preferences[DataStoreKeys.REVERB_PRESET]?.toShort() ?: 0
            val reverbPreset = if (rawReverb !in 0..6) {
                0
            } else rawReverb
            if (Settings.equalizerModel == null) {
                Settings.equalizerModel = EqualizerModel()
                Settings.equalizerModel.reverbPreset = reverbPreset
                Settings.equalizerModel.bassStrength = Settings.bassStrength
            }
            // 频段电平以 equalizer 模块的即时持久化（EqualizerPrefs）为准重新解析：
            // 用户在均衡器页选择预设/保存自定义时立即写入该存储，而上方 DataStore 的
            // bandLevels 仅在播放状态保存时更新——选完预设立刻杀进程（尤其未播放时）
            // 会读到旧电平，表现为不进入均衡器页预设不生效。
            // pos=-1 表示从未进入过均衡器页，保留 DataStore/默认全零结果。
            val savedPresetPos =
                com.bullhead.equalizer.EqualizerPresets.loadPresetPosition(applicationContext)
            if (savedPresetPos >= 0) {
                Settings.presetPos = savedPresetPos
                val resolved =
                    com.bullhead.equalizer.EqualizerPresets.resolveLevels(applicationContext)
                // pos=0（自定义）且从未显式保存过 custom_preset 时，resolveLevels 返回全零；
                // 此时不能用它覆盖上方 DataStore 恢复的真实曲线（用户拖动的频段保存在
                // EQUALIZER_BAND_LEVELS），否则冷启动会以"平直频段+低音"起播，与均衡器页
                // 实际显示/下发的曲线不一致。仅当解析出非零数据（内置预设或已保存的
                // 自定义）或 DataStore 无任何记录时才采用解析结果。
                val resolvedIsFlat = resolved.all { it == 0 }
                if (!resolvedIsFlat || savedBandLevels == null) {
                    for (i in resolved.indices) {
                        Settings.seekbarpos[i] = resolved[i]
                    }
                }
            }
            Log.d(TAG, "restoreEqualizerSettings: enabled=${Settings.isEqualizerEnabled}, presetPos=${Settings.presetPos}, bass=${Settings.bassStrength}, reverb=$reverbPreset, seekbarpos=${Settings.seekbarpos.contentToString()}")
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "restoreEqualizerSettings failed", e)
        }
    }

    /**
     * 按均衡器总开关状态向自研 10 段 AudioProcessor 下发电平：
     * 开关打开 → 当前预设/自定义电平；开关关闭 → 全零（AudioProcessor 不受
     * 系统 Equalizer.enable 控制，关闭开关时必须显式清零，否则音效仍在染色）。
     * Settings.seekbarpos 中的预设值始终保留，下次打开开关可直接恢复。
     */
    private fun applyEqProcessorLevels() {
        if (Settings.isEqualizerEnabled) {
            eqProcessor.setBandLevels(Settings.seekbarpos)
            eqProcessor.setBassStrength(
                Settings.equalizerModel?.bassStrength?.toInt() ?: 0
            )
        } else {
            eqProcessor.setBandLevels(IntArray(10))
            eqProcessor.setBassStrength(0)
        }
    }

    // 音频管理器（保留用于蓝牙/音量控制等）
    private lateinit var audioManager: AudioManager

    // 手动音频焦点管理（替代 ExoPlayer handleAudioFocus=true 的自动管理，
    // 避免 Activity 重建时重新请求焦点导致音频停顿）：
    // 播放前请求焦点，其他应用抢占焦点时暂停，焦点恢复且之前在播放时续播。
    // 逻辑与 MediaPlayer 时代一致。
    private var wasPlayingBeforeFocusLoss = false

    private lateinit var audioFocusRequest: AudioFocusRequest

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 重新获得焦点：仅在焦点丢失前正在播放时恢复播放
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    play()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS -> {
                // 其他应用抢占焦点（短暂或永久）：暂停播放并记录之前是否在播放
                wasPlayingBeforeFocusLoss = player.isPlaying
                pause()
            }
        }
    }

    var currentIndex = 0
    var isChangingSong = false

    @Volatile
    private var isProcessingSongEnd = false

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

    // 广播接收器用于监听蓝牙连接状态（耳机拔出由 ExoPlayer setHandleAudioBecomingNoisy 接管）
    private val audioDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d(TAG, "Audio device event: $action")

            when (action) {
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

    // 标记正在设置外部歌曲播放，防止 loadPlaybackState 覆盖外部歌曲
    @Volatile
    private var isSettingExternalSong = false

    // 外部播放期间需要在 Service 销毁时校验播放来源：
    // 如果销毁时当前歌曲不属于保存的播放来源列表，则重置播放来源为"全部歌曲"
    @Volatile
    private var pendingPlaySourceValidation = false

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
            // 与 MusicViewModel.sortSongsInternal 保持一致：
            // 中文转拼音、英文原样保留后按字符串排，中英文 A-Z 混排
            SortMode.BY_TITLE -> songs.sortedBy {
                PinyinUtil.getPinyinString(it.title).lowercase()
            }
            SortMode.BY_ARTIST -> songs.sortedBy {
                PinyinUtil.getPinyinString(it.artist).lowercase()
            }
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
        Log.d(TAG, "onCreate")

        serviceCreate = true

        createNotificationChannel()

        // ExoPlayer 接管音频焦点（handleAudioFocus=true）和耳机拔出（handleAudioBecomingNoisy=true），
        // 并通过 WAKE_MODE_LOCAL 在播放期间持有 partial wake lock（本地文件播放）。
        // 自研 10 段 EQ 通过 AudioProcessor 注入，频点固定不依赖系统均衡器。
        eqProcessor = TenBandEqualizerProcessor()
        TenBandEqualizerProcessor.instance = eqProcessor
        // 注入桥接：让 equalizer 库模块的 EqualizerFragment 可以调用自研 AudioProcessor，
        // 而不引入 equalizer → app 的反向依赖（app 依赖 equalizer 单向）。
        com.bullhead.equalizer.TenBandEqBridge.setApplier { levels ->
            eqProcessor.setBandLevels(levels)
        }
        com.bullhead.equalizer.TenBandEqBridge.setBassApplier { strength ->
            eqProcessor.setBassStrength(strength)
        }
        val renderersFactory = object : DefaultRenderersFactory(this@MusicService) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(eqProcessor))
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
        // APE（Monkey's Audio）在 extractor 内解码为裸 PCM，由标准音频渲染器播放。
        // ApeSupport.extractorsFactory() 返回"先嗅探 APE、其余行为同 DefaultExtractorsFactory"的工厂，
        // 不影响 mp3/flac/ogg 等其他格式。
        val mediaSourceFactory = DefaultMediaSourceFactory(
            this,
            ApeSupport.extractorsFactory()
        )
        // 预生成固定 audioSessionId 并在 build 后立刻绑定到播放器：
        // 系统音效可在播放开始前挂到该 session（效果链随 AudioTrack 创建即建立，消除冷启动爆音）
        try {
            val audioManagerForSession = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val generated = audioManagerForSession.generateAudioSessionId()
            if (generated > 0) {
                presetAudioSessionId = generated
            }
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "generateAudioSessionId failed", e)
        }
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                Media3AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA).build(), /* handleAudioFocus= */ false
            ).setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        // setAudioSessionId 必须在首次播放前调用（此时尚未 prepare，安全）；
        // 调用后 player.audioSessionId 立即为该固定值，播放前即可初始化系统音效
        if (presetAudioSessionId != 0) {
            try {
                player.setAudioSessionId(presetAudioSessionId)
            } catch (e: Exception) {
                LogWriter.writeError(TAG, "setAudioSessionId($presetAudioSessionId) failed", e)
                presetAudioSessionId = 0
            }
        }

        // media3 1.6.0+ 起初始 audioSessionId 不再在 player 创建后立即可得，
        // 必须通过 AnalyticsListener.onAudioSessionIdChanged 监听初始分配与后续变更（如设备切换/格式变化），
        // 才能把系统音效（Equalizer/BassBoost/PresetReverb）正确绑定到真实 session id 上。
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: AnalyticsListener.EventTime,
                audioSessionId: Int
            ) {
                if (audioSessionId == 0) return
                // 已绑定的 session id 发生变化时，先释放旧 session 上的音效对象，
                // 否则 initialize() 会因 sIsInitialized 直接 return，新 session 上没有任何音效生效。
                if (AudioEffectManager.areEffectsEnabled() &&
                    AudioEffectManager.getAudioSessionId() != audioSessionId
                ) {
                    AudioEffectManager.destroy()
                }
                initializeAudioEffects()
            }
        })

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_IDLE -> {
                        // STATE_IDLE 可能来自 setMediaItem（正常）或 onPlayerError 后（错误）
                        // 仅处理 suppressed error 后的 STATE_IDLE
                        if (hasSuppressedError) {
                            hasSuppressedError = false
                            if (errorRetryCount < 1) {
                                errorRetryCount++
                                Log.w(TAG, "STATE_IDLE after suppressed error, retrying prepare")
                                player.prepare()
                            } else {
                                errorRetryCount = 0
                                isProcessingSongEnd = false
                                Log.w(TAG, "Retry limit exceeded, showing unsupported format toast")
                                android.widget.Toast.makeText(
                                    this@MusicService,
                                    "暂不支持播放该格式",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                _isPlaying.value = false
                                _isPlayableError.value = true
                                _currentSong.value?.path?.let {
                                    SongPlayableRegistry.markUnsupported(this@MusicService, it)
                                }
                                player.pause()
                                updateNotification()
                                updateMediaSessionPlaybackState()
                                savePlaybackState()
                                stopPlayStateObserver()
                            }
                        }
                    }

                    Player.STATE_BUFFERING -> {
                        // 新歌曲正在准备，不清除 isProcessingSongEnd
                        // （旧解码器的 onPlayerError 可能在此之后异步到达）
                    }

                    Player.STATE_READY -> {
                        // 新歌曲准备就绪，清除所有标志
                        isProcessingSongEnd = false
                        hasSuppressedError = false
                        errorRetryCount = 0
                        _isPlayableError.value = false
                        // 切歌后新歌曲准备就绪，同步 MediaSession 状态（此时 isPlaying 已稳定为 true）
                        updateMediaSessionPlaybackState()
                        updateNotification()
                        // 恢复流程：prepare 完成后执行 seek + 可选自动播放 + 通知
                        if (isPendingRestore) {
                            isPendingRestore = false
                            // 进度已内嵌进 setMediaItem(item, startPositionMs) 时不再 seek：
                            // READY 后补 seek 会触发渲染器/AudioTrack 的 flush 重建空转（冷启动爆音源）
                            if (pendingRestorePosition > 0 && !restorePositionEmbedded) {
                                player.seekTo(pendingRestorePosition.toLong())
                            }
                            restorePositionEmbedded = false
                            // 恢复 seek 后立即更新通知栏进度
                            updateMediaSessionPlaybackState()
                            // 立即同步 position 到 LiveData，让 seekBar 显示正确进度。
                            // startPositionUpdates 线程仅在 isPlaying 时 post，恢复不自动播放时
                            // LiveData 不更新，seekBar 会停在 0
                            _currentPosition.postValue(player.currentPosition.toInt())
                            // 显示通知的条件：
                            // 1. 用户没有从最近任务移除（isTaskRemoved=false）
                            // 2. 或者用户已从最近任务移除但重新打开了应用（pendingNotificationToShow=true）
                            if (!pendingSuppressNotification) {
                                updateNotification(pendingRestoreSong)
                                pendingNotificationToShow = false
                            }
                            pendingRestoreSong = null
                            updateMediaSessionPlaybackState()
                            if (pendingAutoPlay) {
                                player.play()
                                // 服务重启自动播放：同样请求 PCM 级起播淡入
                                if (Settings.isEqualizerEnabled) {
                                    TenBandEqualizerProcessor.instance
                                        ?.requestStartupFadeIn(startupFadeInMs)
                                }
                            }
                        }
                    }

                    Player.STATE_ENDED -> {
                        // 防重入：切歌过渡期间可能收到重复的 STATE_ENDED 回调
                        if (isProcessingSongEnd) {
                            Log.w(TAG, "STATE_ENDED already being processed, skip")
                            return
                        }
                        isProcessingSongEnd = true
                        Log.d(
                            TAG,
                            "STATE_ENDED: natural song end, index=$currentIndex, mode=$playMode"
                        )
                        // 强制更新进度为100%，避免最后一次进度更新不到位
                        // 使用 setValue（而非 postValue）同步更新：
                        // postValue 会异步派发，在后续 playNext→setCurrentSong 的 setValue(0) 之后
                        // 才到达观察者，导致 currentPlaybackPositionMs 被旧歌曲的最大进度覆盖，
                        // 新歌曲的磁带动画会从上一首的结束进度开始播放。
                        _currentPosition.value = player.duration.toInt()

                        // 临时播放结束：回到上次保存进度的歌曲从头播放（恢复全库队列）
                        if (isTempPlayback) {
                            resumeLastSavedSong()
                            return
                        }

                        // 顺序播放模式下最后一首自然播放完毕，只更新 UI 状态，不操作播放器
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
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // ExoPlayer 内部自动暂停/恢复（handleAudioBecomingNoisy / audio focus）
                // 不会走 pause()/play()，必须在此同步 _isPlaying，否则 UI 和通知栏状态脱节。
                // 注意：isPlaying=false 可能是切歌/seek 过渡期间的短暂状态，而非真实暂停。
                // 用 playWhenReady 区分真实暂停（用户意图）和过渡状态。
                if (!isPlaying && !player.playWhenReady) {
                    // 真实暂停（用户暂停或音频焦点丢失）
                    if (_isPlaying.value != false) {
                        Log.d(TAG, "onIsPlayingChanged: paused (external)")
                        _isPlaying.value = false
                        updateNotification()
                        updateMediaSessionPlaybackState()
                        savePlaybackState()
                        stopPlayStateObserver()
                    }
                } else if (isPlaying) {
                    // 播放恢复
                    if (_isPlaying.value != true) {
                        Log.d(TAG, "onIsPlayingChanged: resumed (external)")
                        _isPlaying.value = true
                        startPlayStateObserver()
                        updateMediaSessionPlaybackState()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                LogWriter.writeError(TAG, "ExoPlayer onPlayerError: ${error.message}", error)

                // 切歌过渡期间（isProcessingSongEnd 仍为 true），
                // 旧解码器 teardown 可能异步抛出错误，抑制避免误触发 playNext。
                // 抑制后 ExoPlayer 会转 STATE_IDLE，由 STATE_IDLE 处理重试或跳过。
                if (isProcessingSongEnd) {
                    hasSuppressedError = true
                    Log.w(
                        TAG,
                        "onPlayerError during song transition, suppressing (likely old decoder)"
                    )
                    return
                }

                // 仅"真正不支持"的格式/解码错误才提示"暂不支持播放该格式"并永久拉黑。
                // media3 1.9.0 默认开启 StuckPlayer 检测，触发后会以 ERROR_CODE_TIMEOUT(1003)
                // 上报到 onPlayerError；这类超时/IO/写入失败等运行时错误不应被当作格式不支持。
                val unsupportedFormatErrorCodes = setOf(
                    PlaybackException.ERROR_CODE_NOT_SUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                )

                if (error.errorCode in unsupportedFormatErrorCodes) {
                    android.widget.Toast.makeText(
                        this@MusicService, "暂不支持播放该格式", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    _isPlaying.value = false
                    _isPlayableError.value = true
                    _currentSong.value?.path?.let {
                        SongPlayableRegistry.markUnsupported(this@MusicService, it)
                    }
                    player.pause()
                } else {
                    // 运行时错误（含 media3 1.9.0 StuckPlayerException 上报的 TIMEOUT）：
                    // 不拉黑歌曲，提示后尝试重新 prepare 自愈。
                    android.widget.Toast.makeText(
                        this@MusicService, "播放失败，正在重试", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    _isPlaying.value = false
                    _isPlayableError.value = true
                    try {
                        val position = player.currentPosition
                        player.prepare()
                        if (position > 0) {
                            player.seekTo(position)
                        }
                        player.play()
                    } catch (e: Exception) {
                        LogWriter.writeError(TAG, "Self-heal prepare failed", e)
                        player.pause()
                    }
                }
                updateNotification()
                updateMediaSessionPlaybackState()
                savePlaybackState()
                stopPlayStateObserver()
            }
        })

        mediaSession = MediaSessionCompat(this, "MusicService")

        // 先同步加载播放模式和排除目录，避免图标闪烁和临时态判断缺失
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
            try {
                excludedDirsCache =
                    scanFiltersDataStore.data.first()[ScanFilterActivity.EXCLUDED_DIRS]
                        ?: emptySet()
                Log.d(TAG, "onCreate: loaded excludedDirs=${excludedDirsCache.size}")
            } catch (e: Exception) {
                LogWriter.writeError(TAG, "Failed to load excluded dirs", e)
            }
        }

        // 服务被系统重启时（START_STICKY），不恢复到旧进度
        // 以 MusicService 当前进度为准，避免跳转到过时的位置；
        // 无播放进度时不自动恢复歌曲到底部播放条
          mediaSession.isActive = true

          // 初始化音频管理器（但不请求焦点）
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // 启动文件监听
        // startFileObserver()

        // 设置MediaSession回调
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                // 当前歌曲格式不支持时处于错误暂停态，蓝牙耳机的单击会被系统路由到 onPlay()
                // 而非 onSkipToNext()，此时自动跳下一首，避免卡在错误歌曲上
                if (_isPlayableError.value == true) {
                    requestAudioFocusAndPlayNext()
                } else {
                    requestAudioFocusAndPlay()
                }
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

        // 注册音频设备监听接收器（耳机拔出由 ExoPlayer setHandleAudioBecomingNoisy 接管，
        // 此处只保留蓝牙相关 action）
        val audioDeviceFilter = IntentFilter().apply {
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

        // 放弃手动管理的音频焦点
        if (::audioFocusRequest.isInitialized) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        }
        // 修复：注销 audioDeviceReceiver，防止 IntentReceiverLeaked
        // 之前遗漏导致 service 销毁时 receiver 泄露，系统重建 service 时
        // onCreate 中的 ContextCompat.registerReceiver 抛出 IntentReceiverLeaked，
        // service 无法正常初始化，onTaskRemoved/loadPlaybackState 等状态恢复链路
        // 全部失效，引发播放状态错乱（如：划掉应用重开后自动播放下一首）
        try {
            unregisterReceiver(audioDeviceReceiver)
        } catch (e: IllegalArgumentException) {
            LogWriter.writeError(TAG, "onDestroy: audioDeviceReceiver not registered", e)
        }

        // 关闭线程池
        shutdownThreadPool()

        // 修复：同步保存当前播放进度，防止 service 销毁重建后 loadPlaybackState
        // 读到旧进度导致"从头播放"或"进度往前跳"。
        // 之前 savePlaybackState() 是异步的，紧接着 player.release() 会让协程
        // 命中 isPlayerReleased 守卫而跳过保存，DataStore 中 currentPosition
        // 残留 playStateFuture 5 秒前的旧值，重建后 seekTo 到旧值。
        // 场景：切换主题 Activity recreate → MainActivity.onDestroy → MusicManager.unbind
        // → service 失去最后一个 binding → 系统销毁 service → 重建后 loadPlaybackState
        // 读取旧 currentPosition。
        // ExoPlayer 强制主线程访问，onDestroy 在主线程，提前读取 player 值。
        val savedPosition = if (!isPlayerReleased) player.currentPosition.toInt() else 0
        val savedIsPlaying = if (!isPlayerReleased && player.isPlaying) 1 else 0
        runBlocking {
            try {
                // 外部播放结束，校验当前歌曲是否属于保存的播放来源，不属于则重置为"全部歌曲"
                validatePlaySourceForCurrentSong()

                applicationDataStore.edit { preferences ->
                    val currentSong = _currentSong.value
                    if (currentSong != null) {
                        if (!isTempPlayback) {
                            // 正常歌曲：保存歌曲状态
                            preferences[DataStoreKeys.CURRENT_POSITION] = savedPosition
                            preferences[DataStoreKeys.IS_PLAYING] = savedIsPlaying
                            preferences[DataStoreKeys.CURRENT_SONG_ID] = currentSong.id
                            preferences[DataStoreKeys.SONG_TITLE] = currentSong.title
                            preferences[DataStoreKeys.SONG_ARTIST] = currentSong.artist
                            preferences[DataStoreKeys.SONG_PATH] = currentSong.path
                            Log.d(
                                TAG,
                                "onDestroy: saved currentPosition=$savedPosition, isPlaying=$savedIsPlaying, songId=${currentSong.id}"
                            )
                        } else {
                            // 临时歌曲：不覆盖上次保存的歌曲状态，仅标记不自动播放
                            preferences[DataStoreKeys.IS_PLAYING] = 0
                            Log.d(
                                TAG,
                                "onDestroy: temp song, preserve previous saved state, IS_PLAYING=0"
                            )
                        }
                        preferences[DataStoreKeys.PLAY_MODE] = playMode.ordinal
                        preferences[DataStoreKeys.PLAY_SOURCE_TAG] = playSourceTag
                    }
                }
            } catch (e: Exception) {
                LogWriter.writeError(TAG, "onDestroy: savePlaybackState failed", e)
            }
        }
        // 标记播放器即将释放
        isPlayerReleased = true
        stopPositionUpdates()
        player.release()
        TenBandEqualizerProcessor.instance = null
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
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_PLAY -> {
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
                // 格式不支持时处于错误暂停态，播放按钮应跳下一首而非尝试恢复
                if (_isPlayableError.value == true) {
                    requestAudioFocusAndPlayNext()
                } else if (songListData != null && _currentSong.value != null && !player.isPlaying) {
                    // 新传入的歌曲列表，重新播放
                    requestAudioFocusAndPlayCurrentSong()
                } else {
                    // 没有新传入歌曲列表，或者当前没有歌曲，或者已经在播放
                    // 调用play()方法，它会智能处理暂停恢复或重新播放
                    requestAudioFocusAndPlay()
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
                requestAudioFocusAndPlayNext()
            }

            ACTION_PREVIOUS -> {
                requestAudioFocusAndPlayPrevious()
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
                    // 播放未中断场景（主题/高亮色切换、夜间模式等导致 Activity recreate，
                    // Service 未死）：当前歌曲仍在播放器中且已准备时，完全跳过历史状态恢复。
                    // 否则 setMediaItem+prepare+seekTo 会产生可闻的停顿，并把播放位置 seek 回
                    // 几秒前保存的旧进度（表现为返回主界面后进度回跳、衔接不自然）。
                    // ExoPlayer 不抛 IllegalStateException，duration 在未准备时返回 C.TIME_UNSET。
                    val isPlaybackAlive =
                        _currentSong.value != null && player.playbackState != Player.STATE_IDLE
                    if (isPlaybackAlive) {
                        Log.d(
                            TAG,
                            "onStartCommand: playback alive (songId=${_currentSong.value?.id}, state=${player.playbackState}), skip loadPlaybackState on recreate"
                        )
                        isPlaybackStateLoaded = true
                    } else {
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
            android.widget.Toast.makeText(
                this, "暂不支持播放该格式", android.widget.Toast.LENGTH_SHORT
            ).show()
            // 文件不存在，恢复为暂停状态，不自动跳下一首
            _isPlaying.value = false
            _isPlayableError.value = true
            updateNotification()
            updateMediaSessionPlaybackState()
            return
        }

        // 使用setCurrentSong确保立即更新
        setCurrentSong(song)
        _isPlaying.value = true  // 确保立即更新状态

        // 标记切歌过渡期开始：stop() 释放旧解码器可能异步抛出错误，
        // 让 onPlayerError 中的 isProcessingSongEnd 抑制逻辑生效
        isProcessingSongEnd = true

        // ExoPlayer 切歌前强制清理：stop() 立即释放解码器资源，
        // clearMediaItems() 清空媒体队列，彻底消除旧解码器的异步残留错误。
        // 这比单纯的 setMediaItem 更可靠，避免切歌后旧歌曲的错误误伤新歌曲。
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(song.path))
        player.prepare()
        // 确保音频效果管理器已初始化（在播放器准备好之后）
        initializeAudioEffects()
        player.play()
        // 播放后立即更新通知和状态
        updateNotification(song)
        updateMediaSessionPlaybackState()
        // 开始监听播放状态
        startPlayStateObserver()
    }

    private fun requestAudioFocus(): Int {
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

    fun requestAudioFocusAndPlayCurrentSong() {
        // 手动请求音频焦点（handleAudioFocus=false，见 audioFocusChangeListener），
        // 获得焦点后才播放；焦点丢失时由 listener 暂停。
        if (requestAudioFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            playCurrentSong()
        }
    }

    /**
     * 清除临时播放态（切到路径不在排除目录内的普通歌曲时由 setCurrentSong 自动调用）。
     * 通知栏/MediaSession/耳机的 next/prev 随之恢复。
     */
    private fun clearTempPlayback() {
        if (isTempPlayback || tempSong != null) {
            isTempPlayback = false
            tempSong = null
            _tempPlayback.postValue(false)
            Log.d(TAG, "clearTempPlayback: temp mode cleared")
        }
    }

    /**
     * 外部文件打开（文件管理器 ACTION_VIEW）时调用。
     * - isTemp=true：单曲队列，不入库，禁用 next/prev，不保存进度。
     * - isTemp=false：全库队列，定位到该歌曲，next/prev 正常，保存进度。
     * 始终从头播放（playSongDirectly 走 setMediaItem+prepare+play，天然 position=0），
     * 即使当前正在播放同一首歌也会重播。
     */
    fun playExternalSong(song: Song, isTemp: Boolean) {
        // 标记正在设置外部歌曲，防止 loadPlaybackState 覆盖
        isSettingExternalSong = true
        isTempPlayback = isTemp
        tempSong = if (isTemp) song else null
        _tempPlayback.postValue(isTemp)
        // 立即重置进度为 0，避免 UI 从旧歌曲的进度跳到 0
        _currentPosition.value = 0

        // 冷启动时 loadPlaybackState 不会被调用（MusicManager.bind 的 intent 不带 KEY_CREATE），
        // playSourceTag 保持默认值 "f0"，后续 onPause/savePlaybackState 会把默认值覆盖写入 DataStore，
        // 导致下次正常启动时播放来源丢失。这里同步恢复，确保在任何保存之前内存中已是正确值。
        if (playSourceTag == PlaySource.SONGS) {
            try {
                val savedTag = runBlocking {
                    applicationDataStore.data.first()[DataStoreKeys.PLAY_SOURCE_TAG]
                }
                if (savedTag != null) {
                    playSourceTag = savedTag
                    PlaySourceManager.notifyPlaySourceChanged(savedTag)
                }
            } catch (e: Exception) {
                LogWriter.writeError(TAG, "playExternalSong: restore playSourceTag failed", e)
            }
        }

        // 标记需要在 Service 销毁时校验播放来源（当前歌曲是否属于播放来源列表）
        pendingPlaySourceValidation = true

        CoroutineScope(Dispatchers.IO).launch {
            // 刷新排除目录缓存，保证 setCurrentSong 的动态判断使用最新设置
            refreshExcludedDirsCache()
            // 主动恢复均衡器设置：冷启动外部播放时 loadPlaybackState 可能尚未执行或被
            // isSettingExternalSong 提前 return 跳过，导致 Settings.isEqualizerEnabled 为默认 false，
            // initializeAudioEffects() 初始化后均衡器实际未启用。这里同步恢复保证播放前就位。
            restoreEqualizerSettings()
            applyEqProcessorLevels()
            // 播放开始前初始化系统音效并收敛开关状态（同 loadPlaybackState，消除中途插效果链的爆音）
            initializeAudioEffects()
            syncSystemEffectsEnableState()

            if (isTemp) {
                withContext(Dispatchers.Main) {
                    setSongList(listOf(song), 0)
                    setCurrentSong(song)
                    requestAudioFocusAndPlayCurrentSong()
                    updateNotification(song)
                }
            } else {
                // 永久：全库队列，定位到该歌曲
                val allSongs = MusicDatabase.getDatabase(this@MusicService).songDao()
                    .getAllSongs().first()
                val idx = allSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                val target = allSongs.getOrElse(idx) { song }
                withContext(Dispatchers.Main) {
                    setSongList(allSongs, idx)
                    setCurrentSong(target)
                    requestAudioFocusAndPlayCurrentSong()
                    updateNotification(target)
                }
            }
        }
    }

    /**
     * 校验当前歌曲是否属于保存的播放来源，不属于则重置为"全部歌曲"。
     * 在 Service 销毁时调用（onDestroy / onTaskRemoved），确保用户有足够时间切回属于来源的歌曲。
     */
    private suspend fun validatePlaySourceForCurrentSong() {
        if (!pendingPlaySourceValidation) return
        pendingPlaySourceValidation = false
        val currentSong = _currentSong.value ?: return
        val (sourceType, sourceName) = PlaySource.parse(playSourceTag)
        val belongsToSource = when (sourceType) {
            PlaySource.SONGS -> true
            PlaySource.ARTIST -> currentSong.artist == sourceName
            PlaySource.ALBUM -> currentSong.album == sourceName
            PlaySource.PLAYLIST -> {
                val playlistId = sourceName.toLongOrNull()
                if (playlistId != null) {
                    val ids = MusicDatabase.getDatabase(this@MusicService)
                        .playlistDao().getPlaylistSongIds(playlistId).first()
                    currentSong.id in ids
                } else false
            }

            else -> false
        }
        if (!belongsToSource) {
            playSourceTag = PlaySource.SONGS
            PlaySourceManager.notifyPlaySourceChanged(PlaySource.SONGS)
        }
    }

    /**
     * 临时播放结束后：从 DataStore 读取上次保存进度的歌曲，从全库恢复队列并从头播放。
     * 无保存歌曲或文件不存在则停止。
     */
    private fun resumeLastSavedSong() {
        Log.d(TAG, "resumeLastSavedSong: enter")
        CoroutineScope(Dispatchers.IO).launch {
            refreshExcludedDirsCache()
            val preferences = applicationDataStore.data.first()
            val songId = preferences[DataStoreKeys.CURRENT_SONG_ID]
            val saved = songId?.let {
                MusicDatabase.getDatabase(this@MusicService).songDao().getSongByIdSync(it)
            }
            if (saved == null) {
                Log.d(TAG, "resumeLastSavedSong: no saved song, stop")
                withContext(Dispatchers.Main) {
                    clearTempPlayback()
                    _isPlaying.value = false
                    _currentSong.value = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@launch
            }
            val file = java.io.File(saved.path)
            if (!file.exists()) {
                LogWriter.writeError(TAG, "resumeLastSavedSong: file not found: ${saved.path}")
                withContext(Dispatchers.Main) {
                    clearTempPlayback()
                    _isPlaying.value = false
                    _currentSong.value = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@launch
            }
            val allSongs = MusicDatabase.getDatabase(this@MusicService).songDao()
                .getAllSongs().first()
            val idx = allSongs.indexOfFirst { it.id == saved.id }.coerceAtLeast(0)
            val target = allSongs.getOrElse(idx) { saved }
            withContext(Dispatchers.Main) {
                clearTempPlayback()
                setSongList(allSongs, idx)
                setCurrentSong(target)
                requestAudioFocusAndPlayCurrentSong()
                updateNotification(target)
            }
        }
    }

    fun play() {
        // 如果当前没有歌曲，尝试加载或播放当前歌曲
        if (_currentSong.value == null) {
            loadPlaybackState(serviceCreate)
            return
        }

        Log.d(
            TAG,
            "play: state=${player.playbackState}, isPlaying=${player.isPlaying}, duration=${player.duration}"
        )

        // 用户主动播放，重置错误标志，确保新的播放尝试能正确处理错误
        isProcessingSongEnd = false

        // ExoPlayer 状态机干净：未准备/已结束/暂停三种情况分别处理，不抛 IllegalStateException
        if (!player.isPlaying) {
            // 播放完成（STATE_ENDED），seek 到开头重新播放
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            } else if (player.playbackState == Player.STATE_IDLE || player.duration == 0L) {
                // 未准备或媒体项为空，重新准备当前歌曲
                _currentSong.value?.let { song ->
                    player.setMediaItem(MediaItem.fromUri(song.path))
                    player.prepare()
                }
            }
            // 其他情况（暂停状态 STATE_READY 但 isPlaying=false）直接 play
            startPlayStateObserver()
        }

        // 确保音频效果管理器已初始化（在播放器准备好之后）
        initializeAudioEffects()
        player.play()
        // EQ 打开时：PCM 级起播淡入（采样级包络，与出声帧严格同步）。系统效果链已在
        // 创建时带上最终参数，此包络呈现恒定音色的纯响度渐入；EQ 关闭时不处理
        if (Settings.isEqualizerEnabled) {
            TenBandEqualizerProcessor.instance?.requestStartupFadeIn(startupFadeInMs)
        }
        // 立即更新播放状态为true，确保通知栏能正确显示
        _isPlaying.value = true
        // 立即更新通知栏和MediaSession状态
        updateNotification()
        updateMediaSessionPlaybackState()
    }

    fun requestAudioFocusAndPlay() {
        // 手动请求音频焦点（handleAudioFocus=false），获得焦点后才播放
        if (requestAudioFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            play()
        }
    }

    fun pause() {
        if (player.isPlaying) {
            player.pause()
            // 立即更新播放状态为false，确保通知栏能正确显示
            _isPlaying.value = false
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
        if (player.isPlaying) {
            player.pause()
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
        // 停止播放并清空媒体项
        player.stop()
        player.clearMediaItems()
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
            if (Looper.myLooper() == Looper.getMainLooper()) {
                setSongList(songs, currentIndex)
            } else {
                Handler(Looper.getMainLooper()).post {
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

    /**
     * 校正 currentIndex 不变式：songList[currentIndex].id == currentSong.id。
     * songList 的异步回填/覆盖可能让 currentIndex 指向与当前实际播放歌曲不一致的位置
     * （如恢复流程按过期的恢复入参定位），prev/next 的索引加减会从错误位置偏移，
     * 导致"播完后又重播同一首"。以 currentSong 真值为准重新定位。
     */
    private fun realignCurrentIndex() {
        val song = _currentSong.value ?: return
        if (songList.isEmpty()) return
        if (currentIndex in songList.indices && songList[currentIndex].id == song.id) return
        val realIndex = songList.indexOfFirst { it.id == song.id }
        if (realIndex >= 0) {
            Log.d(TAG, "realignCurrentIndex: $currentIndex -> $realIndex (song=${song.title})")
            currentIndex = realIndex
        }
    }

    fun playNext() {
        // 防止并发调用：歌曲即将播完时用户点击下一首，onCompletion 和按钮点击可能同时触发
        if (isNavigating) {
            Log.d(TAG, "playNext ignored: already navigating")
            return
        }
        isNavigating = true
        Log.d(
            TAG,
            "playNext: fromIndex=$currentIndex, mode=$playMode, tempPlayback=$isTempPlayback"
        )
        try {
            // 临时播放态：用户主动点"下一首"（UI 按钮/通知/耳机）→ 回到上次保存的歌曲从头播放
            // 单曲队列下，不守卫会重播这首临时歌曲
            if (isTempPlayback) {
                resumeLastSavedSong()
                return
            }
            if (songList.isEmpty()) {
                // 如果songList为空，通知MainActivity重新设置歌曲列表
                // 这样可以确保播放顺序与用户界面一致
                Log.d(TAG, "songList is empty, requesting MainActivity to reset song list")
                _requestSongList.postValue(Event(true))
                return
            }
            realignCurrentIndex()

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
                        val newIndex = songList.indices.random()
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
        } finally {
            isNavigating = false
        }
    }

    fun playPrevious() {
        // 防止并发调用
        if (isNavigating) {
            Log.d(TAG, "playPrevious ignored: already navigating")
            return
        }
        isNavigating = true
        try {
            // 临时播放态：用户主动点"上一首"（UI 按钮/通知/耳机）→ 回到上次保存的歌曲从头播放
            if (isTempPlayback) {
                resumeLastSavedSong()
                return
            }
            if (songList.isEmpty()) {
                // 如果songList为空，通知MainActivity重新设置歌曲列表
                // 这样可以确保播放顺序与用户界面一致
                Log.d(TAG, "songList is empty, requesting MainActivity to reset song list")
                _requestSongList.postValue(Event(true))
                return
            }
            realignCurrentIndex()

            currentIndex = when {
                // 顺序播放模式：到达第一首后点击上一首不做处理
                playMode == PlayMode.SEQUENCE && currentIndex == 0 -> return
                // 单曲循环模式：重新播放当前歌曲
                playMode == PlayMode.SINGLE_LOOP -> currentIndex
                // 随机播放模式：随机选择一首（尽量不选当前）
                playMode == PlayMode.RANDOM -> {
                    if (songList.size > 1) {
                        val newIndex = songList.indices.random()
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
        } finally {
            isNavigating = false
        }
    }

    fun requestAudioFocusAndPlayNext() {
        // 手动请求音频焦点（handleAudioFocus=false），获得焦点后才播放下一首
        if (requestAudioFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            playNext()
            updateNotification(_currentSong.value)
            updateMediaSessionPlaybackState()
        }
    }

    fun requestAudioFocusAndPlayPrevious() {
        // 手动请求音频焦点（handleAudioFocus=false），获得焦点后才播放上一首
        if (requestAudioFocus() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            playPrevious()
            updateNotification(_currentSong.value)
            updateMediaSessionPlaybackState()
        }
    }

    fun seekTo(position: Int) {
        player.seekTo(position.toLong())
        _currentPosition.postValue(position)
    }

    private val executorService: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()
    private var lastCheckTime = 0L

    // 兼容不同API级别的getParcelableExtra（API 33+使用Class版本）
    @Suppress("DEPRECATION")
    private fun <T : Parcelable> getParcelableExtraCompat(
        intent: Intent,
        key: String,
        clazz: Class<T>
    ): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, clazz)
        } else {
            intent.getParcelableExtra(key) as? T
        }
    }

    // 检查当前是否通过A2DP蓝牙音频输出（替代废弃的isBluetoothA2dpOn）
    private fun isBluetoothA2dpConnected(): Boolean {
        return try {
            // getDefaultAdapter() 在 API 31 废弃，改用 BluetoothManager.adapter（API 23+ 可用）
            val bluetoothAdapter =
                (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
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

    fun getCurrentPosition(): Int = player.currentPosition.toInt()

    fun getDuration(): Int = player.duration.toInt()

    fun getAudioSessionId(): Int = player.audioSessionId

    /**
     * 将 MusicService 当前播放进度同步到 DataStore
     * 用于 Activity 重新绑定服务后，确保保存的进度与实际进度一致
     */
    fun syncCurrentPositionToDataStore() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentSong = _currentSong.value ?: return@launch
                // ExoPlayer 强制主线程访问，在进入 IO 协程前于主线程捕获 player 值
                val (position, isPlaying) = withContext(Dispatchers.Main) {
                    Pair(player.currentPosition.toInt(), if (player.isPlaying) 1 else 0)
                }
                applicationDataStore.edit { preferences ->
                    preferences[DataStoreKeys.CURRENT_SONG_ID] = currentSong.id
                    preferences[DataStoreKeys.SONG_TITLE] = currentSong.title
                    preferences[DataStoreKeys.SONG_ARTIST] = currentSong.artist
                    preferences[DataStoreKeys.SONG_PATH] = currentSong.path
                    preferences[DataStoreKeys.CURRENT_POSITION] = position
                    preferences[DataStoreKeys.IS_PLAYING] = isPlaying
                    Log.d(
                        TAG,
                        "syncCurrentPositionToDataStore: songId=${currentSong.id}, position=$position, isPlaying=$isPlaying"
                    )
                    preferences[DataStoreKeys.PLAY_MODE] = playMode.ordinal
                    // 播放入口来源（f0 全部歌曲 / f1 歌手 / f2 专辑 / f3 歌单）
                    preferences[DataStoreKeys.PLAY_SOURCE_TAG] = playSourceTag
                }
            } catch (e: Exception) {
                LogWriter.writeError(TAG, "syncCurrentPositionToDataStore failed", e)
            }
        }
    }

    private fun startPositionUpdates() {
        mainHandler.post(positionUpdateRunnable)
    }

    private fun stopPositionUpdates() {
        mainHandler.removeCallbacks(positionUpdateRunnable)
    }

    private fun updateMediaSessionPlaybackState() {
        // 用 playWhenReady 而非 isPlaying：isPlaying 在切歌/seek 过渡期间短暂为 false，
        // 导致通知栏播放按钮闪烁。playWhenReady 表示用户播放意图，过渡期间保持稳定。
        val state = if (player.playWhenReady) {
            android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
        } else {
            android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
        }
        val position = player.currentPosition
        val playbackState =
            android.support.v4.media.session.PlaybackStateCompat.Builder()
                .setState(state, position, 1.0f).setActions(
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            android.support.v4.media.session.PlaybackStateCompat.ACTION_SEEK_TO
                ).build()
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
            player.duration
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

        // 根据当前播放状态决定按钮图标和动作。
        // 用 playWhenReady 而非 _isPlaying：后者在切歌过渡期间被 onIsPlayingChanged(false) 污染，
        // 导致通知栏按钮闪烁。playWhenReady 表示用户播放意图，过渡期间保持稳定。
        val isPlaying = player.playWhenReady
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_pause, "Pause", createActionPendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_play, "Play", createActionPendingIntent(ACTION_PLAY)
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(song.title)
            .setContentText("${song.artist} - ${song.album}").setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(pendingIntent)

        builder.addAction(
            NotificationCompat.Action(
                R.drawable.ic_previous, "Previous", createActionPendingIntent(ACTION_PREVIOUS)
            )
        ).addAction(playPauseAction).addAction(
            NotificationCompat.Action(
                R.drawable.ic_next, "Next", createActionPendingIntent(ACTION_NEXT)
            )
        )
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2)
        )

        return builder.setPriority(NotificationCompat.PRIORITY_HIGH).setOngoing(isPlaying)
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
    private var isPlayerReleased = false

    @Volatile
    private var hasSuppressedError = false
    private var errorRetryCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            if (!isPlayerReleased && player.isPlaying) {
                _currentPosition.value = player.currentPosition.toInt()
                updateMediaSessionPlaybackState()
            }
            if (!isPlayerReleased) {
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    fun savePlaybackState() {
        // 临时播放态不保存进度，保留 DataStore 中上次保存的歌曲状态
        // （用于临时播放结束后回到上次保存的歌曲从头播放）
        if (isTempPlayback) {
            Log.d(TAG, "savePlaybackState: temp song, skip")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 注意：之前曾加过 isPlaybackStateLoaded 守卫，但该标志在 loadPlaybackState
                // 的 prepare 前就已设置为 true，根本无法防止 prepare-seekTo 期间写入脏数据，
                // ExoPlayer 强制主线程访问，在进入 edit 前于主线程捕获 player 值
                val (position, isPlaying) = withContext(Dispatchers.Main) {
                    Pair(player.currentPosition.toInt(), if (player.isPlaying) 1 else 0)
                }
                // 反而会阻止"清除数据后未播放就配置均衡器并划掉应用"的配置保存。已移除。
                applicationDataStore.edit { preferences ->
                    if (isPlayerReleased) {
                        Log.w(TAG, "savePlaybackState: player already released, skip")
                        return@edit
                    }
                    val currentSong = _currentSong.value
                    if (currentSong != null) {
                        preferences[DataStoreKeys.CURRENT_SONG_ID] = currentSong.id
                        preferences[DataStoreKeys.SONG_TITLE] = currentSong.title
                        preferences[DataStoreKeys.SONG_ARTIST] = currentSong.artist
                        preferences[DataStoreKeys.SONG_PATH] = currentSong.path
                        preferences[DataStoreKeys.CURRENT_POSITION] = position
                        preferences[DataStoreKeys.IS_PLAYING] = isPlaying
                        Log.d(
                            TAG,
                            "savePlaybackState: songId=${currentSong.id}, position=$position, isPlaying=$isPlaying"
                        )
                        // 保存播放模式
                        preferences[DataStoreKeys.PLAY_MODE] = playMode.ordinal
                        // 播放入口来源：外部播放期间暂不保存，等 Service 销毁时校验后再保存
                        if (!pendingPlaySourceValidation) {
                            preferences[DataStoreKeys.PLAY_SOURCE_TAG] = playSourceTag
                        }
                        // 保存均衡器设置
                        preferences[DataStoreKeys.IS_EQUALIZER_ENABLED] =
                            if (Settings.isEqualizerEnabled) 1 else 0
                        preferences[DataStoreKeys.EQUALIZER_BAND_LEVELS] =
                            Settings.seekbarpos.joinToString(",")
                        preferences[DataStoreKeys.EQUALIZER_PRESET_POS] = Settings.presetPos
                        // 防御：避免把越界值（如旧版本残留的 -1）写入 DataStore 形成脏数据循环
                        val bassToSave = Settings.bassStrength.toInt()
                        preferences[DataStoreKeys.BASS_STRENGTH] =
                            if (bassToSave in 0..1000) bassToSave else 0
                        val reverbToSave = Settings.reverbPreset.toInt()
                        preferences[DataStoreKeys.REVERB_PRESET] =
                            if (reverbToSave in 0..6) reverbToSave else 0
                    } else {
                        // 清除保存的状态（包括上次播放进度、播放入口标签）
                        preferences.remove(DataStoreKeys.CURRENT_SONG_ID)
                        preferences.remove(DataStoreKeys.SONG_TITLE)
                        preferences.remove(DataStoreKeys.SONG_ARTIST)
                        preferences.remove(DataStoreKeys.SONG_PATH)
                        preferences.remove(DataStoreKeys.CURRENT_POSITION)
                        preferences.remove(DataStoreKeys.IS_PLAYING)
                        // 外部播放期间暂不清除播放来源，等 Service 销毁时校验后再决定
                        if (!pendingPlaySourceValidation) {
                            preferences.remove(DataStoreKeys.PLAY_SOURCE_TAG)
                        }
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
                  // 固定 session id 优先（onCreate 预生成，build 后即有效，无需等 prepare）；
                  // 预生成失败时回退为播放器动态分配的 id（prepare 后才非零）
                  val sessionId = if (presetAudioSessionId != 0) {
                      presetAudioSessionId
                  } else {
                      player.audioSessionId
                  }
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

    /**
     * 将已创建的系统音效（Equalizer/BassBoost/PresetReverb）enable 状态与开关对齐（幂等）。
     * 音效可能在 Settings 从 DataStore 恢复完成前被均衡器页面提前创建（当时开关状态未知），
     * 恢复完成后调用本方法收敛；正常路径下与 initializeAudioEffects 的启用参数一致，无副作用。
     */
    private fun syncSystemEffectsEnableState() {
        if (!AudioEffectManager.areEffectsEnabled()) return
        if (Settings.isEqualizerEnabled) {
            AudioEffectManager.enableEffects(this)
        } else {
            AudioEffectManager.disableEffects()
        }
    }

    fun loadPlaybackState(
        isServiceCreate: Boolean, restorePosition: Boolean = true,
        restoreFlag: Int = 0
    ) {
        Log.d(
            TAG,
            "loadPlaybackState: enter, isServiceCreate=$isServiceCreate, restorePosition=$restorePosition, restoreFlag=$restoreFlag, isPlaybackStateLoaded=$isPlaybackStateLoaded"
        )
        // 如果已经加载过播放状态，不需要重复加载
        if (isPlaybackStateLoaded) {
            Log.i(TAG, "loadPlaybackState: already loaded, skip")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val preferences = applicationDataStore.data.first()

                // 尽早恢复播放来源标签和播放模式（用户偏好），即使外部歌曲跳过歌曲恢复也要保留
                val savedSourceTag = preferences[DataStoreKeys.PLAY_SOURCE_TAG] ?: PlaySource.SONGS
                playSourceTag = savedSourceTag
                PlaySourceManager.notifyPlaySourceChanged(savedSourceTag)
                val savedPlayModeOrdinal = preferences[DataStoreKeys.PLAY_MODE] ?: 0
                playMode = PlayMode.entries.getOrElse(savedPlayModeOrdinal) { PlayMode.ALL_LOOP }
                _playModeLiveData.postValue(playMode)
                Log.d(
                    TAG,
                    "loadPlaybackState: restored playSourceTag=$savedSourceTag, playMode=$playMode"
                )

                // 加载均衡器设置（必须在currentPosition检查之前，且在 isSettingExternalSong 检查之前）：
                // 外部文件播放时 isSettingExternalSong=true 会提前 return 跳过歌曲恢复，
                // 但均衡器参数必须恢复，否则冷启动外部播放时 Settings.isEqualizerEnabled 为默认 false，
                // initializeAudioEffects() 初始化后均衡器实际未启用。
                restoreEqualizerSettings()
                applyEqProcessorLevels()
                // 播放开始前初始化系统音效（session id 已固定，无需等 prepare）：
                // 效果链随 AudioTrack 创建即建立，避免播放中途插入 Equalizer/BassBoost/Reverb
                // 产生的爆音；若音效已被均衡器页面在恢复前提前创建，此处按恢复后的开关状态收敛 enable
                initializeAudioEffects()
                syncSystemEffectsEnableState()

                // 如果正在设置外部歌曲，跳过歌曲恢复，避免覆盖外部歌曲
                if (isSettingExternalSong) {
                    isPlaybackStateLoaded = true
                    return@launch
                }

                // 检查用户是否从最近任务移除了应用
                val isTaskRemoved = preferences[DataStoreKeys.TASK_REMOVED_FLAG] == 1
                if (isTaskRemoved) {
                    // 清除标志，下次正常启动时可以正常显示通知
                    applicationDataStore.edit { p ->
                        p.remove(DataStoreKeys.TASK_REMOVED_FLAG)
                    }
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
                    // 再次检查：如果在加载期间外部歌曲已被设置，跳过恢复
                    if (isSettingExternalSong) {
                        isPlaybackStateLoaded = true
                        return@withContext
                    }
                    // 播放未中断场景（主题切换等导致 Activity recreate，Service 未死）：
                    // 待恢复歌曲就是当前已准备的歌曲时，跳过重准备。否则 setMediaItem+prepare 会
                    // 打断正在进行的播放，且 restorePosition=false 跳过 seekTo 后会从头播放。
                    // ExoPlayer 不抛 IllegalStateException，duration 未准备时返回 C.TIME_UNSET。
                    val isSameSongAlive =
                        _currentSong.value?.id == songId && player.duration > 0
                    if (isSameSongAlive) {
                        Log.d(
                            TAG,
                            "loadPlaybackState: same song alive (id=$songId), skip re-prepare"
                        )
                        isPlaybackStateLoaded = true
                        // 与尾部逻辑一致：不恢复进度时以当前实际进度为准同步一次
                        if (!shouldRestorePosition) {
                            savePlaybackState()
                        }
                        // 极端情况下 songList 可能为空，回填以保证上一首/下一首可用
                        if (songList.isEmpty()) {
                            val (sourceType, sourceName) = PlaySource.parse(playSourceTag)
                            loadSongListFromDatabase(sourceType, sourceName, songId)
                        }
                        return@withContext
                    }
                    val restoredSong = Song(
                        id = songId,
                        title = songTitle,
                        artist = songArtist,
                        album = "",
                        duration = 0,
                        path = songPath
                    )
                    Log.d(
                        TAG,
                        "loadPlaybackState: about to setCurrentSong for restored song=${restoredSong.title}, id=${restoredSong.id}"
                    )
                    setCurrentSong(restoredSong)
                    // 仅在真正加载了歌曲后才标记为已加载，避免无进度时跳过后续合法恢复
                    isPlaybackStateLoaded = true

                    // 准备播放器但不立即播放：setMediaItem + prepare，
                    // 实际的 seek/通知/自动播放交给 Player.Listener.onPlaybackStateChanged(STATE_READY) 处理。
                    // ExoPlayer 状态机干净，无 MediaPlayer 的 -38/-38 竞态和 OnPreparedListener 残留问题。
                    isPendingRestore = true
                    pendingRestorePosition = if (shouldRestorePosition) currentPosition else 0
                    // 进度直接内嵌进媒体项：渲染器只 enable 一次、只建一个 AudioTrack，
                    // 避免"prepare@0 → READY 后 seek"造成的音轨 flush/重建空转（冷启动爆音源）
                    restorePositionEmbedded = pendingRestorePosition > 0
                    pendingAutoPlay = isPlaying == 1 && restoreFlag == MainActivity.RESTORE_CREATE
                    pendingSuppressNotification = isTaskRemoved && !pendingNotificationToShow
                    pendingRestoreSong = restoredSong
                    Log.d(
                        TAG,
                        "loadPlaybackState: setMediaItem+prepare, targetPos=$pendingRestorePosition, embedded=$restorePositionEmbedded, autoPlay=$pendingAutoPlay, suppressNotification=$pendingSuppressNotification"
                    )
                    // 冷启动恢复 EQ 设置（含低音强度）并同步进 App 内 AudioProcessor。
                    // 必须在 prepare 之前：处理器 configure（发生在 prepare 内）以
                    // initial=true 一次性落定系数，EQ 从第一个采样帧即生效；若在
                    // prepare 之后补发，会变成 mid-stream 渐变（先平直播放再渐入 EQ）
                    restoreEqualizerSettings()
                    applyEqProcessorLevels()
                    initializeAudioEffects()
                    if (restorePositionEmbedded) {
                        player.setMediaItem(
                            MediaItem.fromUri(songPath),
                            pendingRestorePosition.toLong()
                        )
                    } else {
                        player.setMediaItem(MediaItem.fromUri(songPath))
                    }
                    player.prepare()

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
            getSharedPreferences("sort_mode_prefs", MODE_PRIVATE)
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
     * @param songId 定位用的歌曲 id（仅当 currentSong 为空时作为回退），用于定位 startIndex
     * @param onSettled 列表回填结算（成功/跳过/异常）后的回调，用于关闭恢复窗口
     *
     * 退化规则：来源作用域为空（f1/f2 下已无歌曲 / f3 歌单已不存在）→ 退化到全部歌曲（f0）。
     */
    private fun loadSongListFromDatabase(
        sourceType: String,
        sourceName: String = "",
        songId: Long = 0L,
        onSettled: (() -> Unit)? = null
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

                    // 以当前实际播放的歌曲定位 startIndex（currentSong 真值优先）。
                    // 恢复入参 songId 只是回退值：DB 查询期间 playNext 可能已切到下一首，
                    // 用过期的入参定位会把 currentIndex 重置回旧歌，导致下一首播完后
                    // playNext 的 +1 又指向它，造成"播完立即从头重播"。
                    val currentSongId = _currentSong.value?.id ?: songId.takeIf { it != 0L }
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
            } finally {
                // 无论回填成功、被跳过还是异常，都要结算恢复窗口
                onSettled?.invoke()
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
        // 修复：先停止 playStateFuture，防止 stopSelf 后到 onDestroy 真正执行前，
        // playStateFuture 每 5 秒触发的 savePlaybackState 用当前进度/IS_PLAYING=1
        // 覆盖 onTaskRemoved 保存的快照（导致重新打开应用时状态偏离 onTaskRemoved 时刻）
        stopPlayStateObserver()
        // 修复：暂停播放器，防止 onDestroy 真正执行前 STATE_ENDED 回调
        // 触发 playNext -> playCurrentSong(B) -> savePlaybackState，导致 currentSong
        // 切换到下一首且覆盖 A 的状态
        if (player.isPlaying) {
            player.pause()
            _isPlaying.value = false
            Log.d(TAG, "onTaskRemoved: paused player to prevent post-removal state drift")
        }
        // ExoPlayer 强制主线程访问，onTaskRemoved 在主线程，提前读取 player 值
        val taskRemovedPosition = player.currentPosition.toInt()
        // 同步保存播放状态和标志，防止异步保存未完成时服务被停止
        runBlocking {
            // 外部播放期间移除任务，校验当前歌曲是否属于保存的播放来源
            validatePlaySourceForCurrentSong()

            applicationDataStore.edit { preferences ->
                // 设置任务移除标志
                preferences[DataStoreKeys.TASK_REMOVED_FLAG] = 1
                // 同步保存播放状态
                val currentSong = _currentSong.value
                if (currentSong != null) {
                    if (!isTempPlayback) {
                        // 正常歌曲：保存歌曲状态
                        preferences[DataStoreKeys.CURRENT_SONG_ID] = currentSong.id
                        preferences[DataStoreKeys.SONG_TITLE] = currentSong.title
                        preferences[DataStoreKeys.SONG_ARTIST] = currentSong.artist
                        preferences[DataStoreKeys.SONG_PATH] = currentSong.path
                        // 修复：强制保存 IS_PLAYING=0，重新打开应用时不应自动播放
                        // （用户主动从最近任务划掉应用，恢复时应为暂停状态）
                        preferences[DataStoreKeys.CURRENT_POSITION] = taskRemovedPosition
                        preferences[DataStoreKeys.IS_PLAYING] = 0
                        Log.d(
                            TAG,
                            "onTaskRemoved: saved songId=${currentSong.id}, title=${currentSong.title}, position=$taskRemovedPosition, isPlaying=0"
                        )
                    } else {
                        // 临时歌曲：不覆盖上次保存的歌曲状态，仅强制 IS_PLAYING=0
                        preferences[DataStoreKeys.IS_PLAYING] = 0
                        Log.d(
                            TAG,
                            "onTaskRemoved: temp song, preserve previous saved state, IS_PLAYING=0"
                        )
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

}