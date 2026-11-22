package com.unicorn.player.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * 使用全局 Application Context 的播放器状态 DataStore
 * 顶层属性保证全进程唯一实例
 */
val Context.applicationDataStore: DataStore<Preferences> by preferencesDataStore(name = "music_player_state")

/**
 * 播放来源变化通知器
 */
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
