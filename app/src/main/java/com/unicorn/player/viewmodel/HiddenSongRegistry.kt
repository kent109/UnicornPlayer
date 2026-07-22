package com.unicorn.player.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * 隐藏歌曲注册表（进程级单例）
 *
 * 所有 MusicViewModel 实例共享此注册表。在一个页面（如 SongsFragment）隐藏歌曲后，
 * 其他页面（歌手、专辑、歌单详情）通过观察 [hiddenSongIds] 自动同步刷新，
 * 无需依赖各自 ViewModel 的本地副本。
 */
object HiddenSongRegistry {

    private val _hiddenSongIds = MutableLiveData<Set<Long>>(emptySet())
    val hiddenSongIds: LiveData<Set<Long>> = _hiddenSongIds

    /** 添加隐藏歌曲 ID（幂等） */
    fun hide(songId: Long) {
        val current = _hiddenSongIds.value ?: emptySet()
        if (current.contains(songId)) return
        _hiddenSongIds.value = current + songId
    }

    /** 批量设置隐藏 ID 集合（从 DataStore 恢复时使用） */
    fun setIds(ids: Set<Long>) {
        _hiddenSongIds.value = ids
    }

    /** 清空隐藏集合（下拉强制刷新时使用） */
    fun clear() {
        _hiddenSongIds.value = emptySet()
    }

    /** 当前隐藏的 ID 集合 */
    fun currentIds(): Set<Long> = _hiddenSongIds.value ?: emptySet()
}
