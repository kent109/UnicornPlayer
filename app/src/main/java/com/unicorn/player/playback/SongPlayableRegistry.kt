package com.unicorn.player.playback

import android.content.Context
import android.media.MediaCodecList
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.unicorn.player.database.MusicDatabase
import com.unicorn.player.playback.SongPlayableRegistry.blacklistVersion
import com.unicorn.player.playback.SongPlayableRegistry.isPlayable
import com.unicorn.player.playback.SongPlayableRegistry.markUnsupported
import com.unicorn.player.playback.SongPlayableRegistry.precheck
import com.unicorn.player.service.DataStoreKeys
import com.unicorn.player.service.applicationDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 设备可播放性黑名单（按文件扩展名）。
 *
 * 数据来源：
 * 1. 冷启动 [precheck]：枚举设备全部音频解码器（MediaCodecList.ALL_CODECS，含厂商自带），
 *    将曲库中出现的扩展名与解码器支持的 mime 对照，无解码器的扩展名直接入黑名单并持久化；
 * 2. 真实播放错误：MusicService 捕获 ExoPlayer 错误后调用 [markUnsupported] 累积，
 *    内存集合有变化时写回 DataStore。
 *
 * Song 表不存 playable 字段（避免 Room 破坏性迁移），列表 bind 时调 [isPlayable] 现场判断。
 * 黑名单变化通过 [blacklistVersion] 通知 UI 重绑刷新。
 */
object SongPlayableRegistry {

    private const val TAG = "SongPlayableRegistry"

    /**
     * 扩展名 → 可能对应的解码器 mime 列表。
     * 命中任意一个 mime 即视为该格式可播；全部不命中才标记为不支持。
     * 未列出的扩展名不参与预检（交给播放错误兜底），避免对未知格式误判。
     */
    private val EXT_CODEC_MIMES: Map<String, List<String>> = mapOf(
        "mp3" to listOf("audio/mpeg"),
        "aac" to listOf("audio/aac", "audio/mp4a-latm"),
        "m4a" to listOf("audio/mp4a-latm", "audio/mp4", "audio/aac"),
        "mp4" to listOf("audio/mp4a-latm", "audio/mp4"),
        "flac" to listOf("audio/flac", "audio/flac"),
        "wav" to listOf("audio/raw", "audio/wav", "audio/x-wav"),
        "ogg" to listOf("audio/ogg", "audio/vorbis", "audio/opus"),
        "oga" to listOf("audio/ogg", "audio/vorbis", "audio/opus"),
        "opus" to listOf("audio/opus", "audio/ogg"),
        "amr" to listOf("audio/amr", "audio/3gpp", "audio/amr-wb"),
        "3gp" to listOf("audio/3gpp", "audio/mp4a-latm", "audio/amr"),
        "mid" to listOf("audio/midi"),
        "midi" to listOf("audio/midi"),
        "wma" to listOf("audio/x-ms-wma", "audio/x-wma"),
        "ape" to listOf("audio/x-ape", "audio/ape"),
        "wv" to listOf("audio/x-wavpack", "audio/wavpack"),
        "tta" to listOf("audio/x-tta", "audio/tta"),
        "ac3" to listOf("audio/ac3", "audio/eac3"),
        "eac3" to listOf("audio/eac3", "audio/ac3"),
        "dts" to listOf("audio/vnd.dts", "audio/dts"),
        "aiff" to listOf("audio/aiff", "audio/x-aiff"),
        "aif" to listOf("audio/aiff", "audio/x-aiff")
    )

    private val unsupportedExtensions = mutableSetOf<String>()

    private val _blacklistVersion = MutableLiveData(0)

    /** 黑名单每次变化自增，列表观察此值后重绑 item 刷新置灰状态 */
    val blacklistVersion: LiveData<Int> = _blacklistVersion

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    /** 自动预检是否已对“非空曲库”执行过（首次安装时 Application 阶段曲库为空，需等扫描后补跑） */
    @Volatile
    private var autoPrecheckDone = false

    @Volatile
    private var precheckInProgress = false

    /**
     * Application.onCreate 调用：同步加载持久化黑名单（保证首个列表渲染即可判断），
     * 随后在后台跑 MediaCodecList 预检（此时曲库可能为空，为空会在 [precheckLibrary] 补跑）。
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext

        runBlocking {
            try {
                val prefs = appContext.applicationDataStore.data.first()
                val saved = prefs[DataStoreKeys.UNSUPPORTED_AUDIO_EXTENSIONS].orEmpty()
                synchronized(unsupportedExtensions) {
                    unsupportedExtensions.addAll(saved)
                }
                Log.d(TAG, "Loaded ${saved.size} unsupported extensions: $saved")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load unsupported extensions", e)
            }
        }

        precheckLibrary(appContext)
    }

    /**
     * 触发 MediaCodecList 预检：对照设备解码器能力，把曲库中无解码器的扩展名加入黑名单。
     * - 曲库首次加载为非空时自动调用一次（覆盖“已有数据库”与“首次扫描完成”两种场景）；
     * - [force] = true 用于手动扫描完成后强制重跑（检查新引入的格式）。
     * 内部做并发/重复防抖，可安全多次调用。
     */
    fun precheckLibrary(context: Context, force: Boolean = false) {
        if (!initialized) return
        if (precheckInProgress) return
        if (!force && autoPrecheckDone) return
        val appContext = context.applicationContext
        precheckInProgress = true
        scope.launch {
            try {
                precheck(appContext)
            } finally {
                precheckInProgress = false
            }
        }
    }

    /**
     * MediaCodecList 预检：对照设备解码器能力，把曲库中无解码器的扩展名加入黑名单。
     * 全部在 IO 线程，查库与枚举编解码器均为一次性开销。
     */
    private suspend fun precheck(context: Context) {
        try {
            val paths = withContext(Dispatchers.IO) {
                MusicDatabase.getDatabase(context).songDao().getAllPathsSync()
            }
            if (paths.isEmpty()) return
            autoPrecheckDone = true

            val extsInLibrary = paths.asSequence()
                .map { extensionOf(it) }
                .filter { it.isNotEmpty() }
                .toSet()

            val decoderMimes = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { it.supportedTypes.asSequence() }
                .toSet()

            val detected = mutableSetOf<String>()
            for (ext in extsInLibrary) {
                val candidates = EXT_CODEC_MIMES[ext] ?: continue
                if (candidates.none { it in decoderMimes }) {
                    detected.add(ext)
                }
            }

            val changed: Boolean
            synchronized(unsupportedExtensions) {
                changed = unsupportedExtensions.addAll(detected)
            }
            if (changed) {
                persist(context)
                _blacklistVersion.postValue(_blacklistVersion.value!! + 1)
                Log.d(TAG, "Precheck added unsupported extensions: $detected")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Precheck failed", e)
        }
    }

    /**
     * 播放错误时调用：把文件扩展名加入黑名单。
     * @return 黑名单是否发生变化（调用方可据此持久化/刷新 UI）
     */
    fun markUnsupported(context: Context, path: String): Boolean {
        val ext = extensionOf(path)
        if (ext.isEmpty()) return false
        val changed = synchronized(unsupportedExtensions) { unsupportedExtensions.add(ext) }
        if (changed) {
            Log.d(TAG, "Marked unsupported extension from playback error: $ext")
            scope.launch { persist(context.applicationContext) }
            _blacklistVersion.postValue(_blacklistVersion.value!! + 1)
        }
        return changed
    }

    /** 该路径的扩展名是否在黑名单中（未知/无扩展名按可播放处理） */
    fun isPlayable(path: String?): Boolean {
        if (path.isNullOrEmpty()) return true
        val ext = extensionOf(path)
        if (ext.isEmpty()) return true
        synchronized(unsupportedExtensions) {
            return ext !in unsupportedExtensions
        }
    }

    private fun extensionOf(path: String): String {
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        return if (dot > slash && dot < path.length - 1) {
            path.substring(dot + 1).lowercase()
        } else {
            ""
        }
    }

    private suspend fun persist(context: Context) {
        val snapshot = synchronized(unsupportedExtensions) { unsupportedExtensions.toSet() }
        try {
            context.applicationDataStore.edit { prefs ->
                prefs[DataStoreKeys.UNSUPPORTED_AUDIO_EXTENSIONS] = snapshot
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist unsupported extensions", e)
        }
    }
}
