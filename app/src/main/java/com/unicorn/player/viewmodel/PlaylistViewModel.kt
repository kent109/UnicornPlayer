package com.unicorn.player.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.unicorn.player.model.Playlist
import com.unicorn.player.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 歌单 Fragment 作用域 ViewModel
 *
 * - 仅在首次调用 [loadPlaylists] 时真正查询数据库（[hasLoadedOnce] 守卫）
 * - 下拉刷新调 [refreshPlaylists] 强制重查
 * - 写操作（创建/重命名/删除）返回后，由 UI 主动调 [refreshPlaylists] 让列表立即更新
 */
class PlaylistViewModel(
    private val repository: MusicRepository,
    application: Application
) : AndroidViewModel(application) {

    /** 歌单列表展示数据，含聚合的歌曲数量 */
    data class PlaylistInfo(
        val id: Long,
        val name: String,
        val icon: String,
        val createdAt: Long,
        val updatedAt: Long,
        val songCount: Int
    )

    private val _playlists = MutableLiveData<List<PlaylistInfo>>(emptyList())
    val playlists: LiveData<List<PlaylistInfo>> = _playlists

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    /** 是否已首次加载过（控制懒加载） */
    private var hasLoadedOnce = false

    /**
     * 首次可见时由 Fragment 调用。
     *
     * - 首次调用时真正查询；
     * - 非首次则保持懒加载，直接复用 LiveData 缓存（切回 tab 不重查）。
     *
     * 写操作返回后，UI 应主动调 [refreshPlaylists] 立即更新列表。
     */
    fun loadPlaylists() {
        if (hasLoadedOnce) return
        hasLoadedOnce = true
        refreshPlaylistsInternal()
    }

    /**
     * 强制重新查询（下拉刷新、写操作返回时调用，忽略 [hasLoadedOnce]）
     */
    fun refreshPlaylists() {
        refreshPlaylistsInternal()
    }

    private fun refreshPlaylistsInternal() {
        viewModelScope.launch {
            _isLoading.postValue(true)
            try {
                // 收集歌单列表：getAllPlaylists 是持续 Flow，仅取最新一次即停止
                val playlistList = repository.getAllPlaylists().first()
                // 对每个歌单取一次快照数量（first() 单次取值后即取消订阅）
                val infos = playlistList.map { pl ->
                    try {
                        val songs = repository.getPlaylistSongs(pl.id).first()
                        pl.toInfo(songs.size)
                    } catch (e: Exception) {
                        pl.toInfo(0)
                    }
                }
                _playlists.postValue(infos)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    private fun Playlist.toInfo(songCount: Int) = PlaylistInfo(
        id = id,
        name = name,
        icon = icon,
        createdAt = createdAt,
        updatedAt = updatedAt,
        songCount = songCount
    )

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            try {
                repository.createPlaylist(name)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            refreshPlaylistsInternal()
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch {
            try {
                repository.renamePlaylist(id, name)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            refreshPlaylistsInternal()
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            try {
                repository.deletePlaylistById(id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            refreshPlaylistsInternal()
        }
    }
}
