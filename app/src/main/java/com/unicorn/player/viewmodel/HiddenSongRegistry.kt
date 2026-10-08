package com.unicorn.player.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * 隐藏歌曲注册表（进程级单例）
 *
 * 所有 MusicViewModel 实例共享此注册表。在一个页面（如 SongsFragment）隐藏歌曲后，
 * 其他页面（歌手、专辑、歌单详情）通过观察 [hiddenSongPaths] 自动同步刷新，
 * 无需依赖各自 ViewModel 的本地副本。
 *
 * 以文件路径而非 MediaStore 行 ID 作为身份：媒体库重建或文件被重新索引后行 ID 会变，
 * 用 ID 记录会让隐藏关系失效、已删除的歌曲在下次扫描后重新出现。
 */
object HiddenSongRegistry {

    private val _hiddenSongPaths = MutableLiveData<Set<String>>(emptySet())
    val hiddenSongPaths: LiveData<Set<String>> = _hiddenSongPaths

    /** 添加隐藏歌曲路径（幂等） */
    fun hide(path: String) {
        val current = _hiddenSongPaths.value ?: emptySet()
        if (current.contains(path)) return
        _hiddenSongPaths.value = current + path
    }

    /** 批量设置隐藏路径集合（从 DataStore 恢复时使用） */
    fun setPaths(paths: Set<String>) {
        _hiddenSongPaths.value = paths
    }

    /** 清空隐藏集合（开启"扫描删除的歌曲"后下拉刷新时使用） */
    fun clear() {
        _hiddenSongPaths.value = emptySet()
    }

    /** 当前隐藏的路径集合 */
    fun currentPaths(): Set<String> = _hiddenSongPaths.value ?: emptySet()
}
