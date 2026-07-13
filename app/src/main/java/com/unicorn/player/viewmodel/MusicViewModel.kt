package com.unicorn.player.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MusicViewModel(
    private val repository: MusicRepository,
    private val context: Context
) : ViewModel() {

    private val _allSongs = MutableLiveData<List<Song>>(emptyList())
    val allSongs: LiveData<List<Song>> = _allSongs

    // 完整的歌曲列表（不随搜索变化）
    private val _fullSongs = MutableLiveData<List<Song>>(emptyList())
    val fullSongs: LiveData<List<Song>> = _fullSongs

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // 排序模式，ordinal 与 sort_mode_prefs 存储值一致，默认 BY_TIME
    enum class SortMode { BY_TIME, BY_TITLE, BY_ARTIST }

    private val _sortMode = MutableLiveData(SortMode.BY_TITLE)
    val sortMode: LiveData<SortMode> = _sortMode

    private var searchJob: Job? = null

    init {
        restoreSortMode()
    }

    /**
     * 从 SharedPreferences 同步恢复排序模式，恢复完成后才启动 collectSongs
     */
    private fun restoreSortMode() {
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences("sort_mode_prefs", Context.MODE_PRIVATE)
                val savedOrdinal = prefs.getInt("sort_mode", 0)
                _sortMode.value = SortMode.entries.getOrElse(savedOrdinal) { SortMode.BY_TITLE }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                collectSongs()
            }
        }
    }

    /**
     * 同步保存排序模式到 SharedPreferences，确保杀进程时也不丢失
     */
    private fun saveSortMode(mode: SortMode) {
        try {
            context.getSharedPreferences("sort_mode_prefs", Context.MODE_PRIVATE)
                .edit(commit = true) {
                    putInt("sort_mode", mode.ordinal)
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun collectSongs() {
        viewModelScope.launch {
            repository.getAllSongs().collect { songs ->
                val sorted = sortSongsInternal(songs, _sortMode.value ?: SortMode.BY_TIME)
                _allSongs.postValue(sorted)
                _fullSongs.postValue(sorted)
            }
        }
    }

    private fun sortSongsInternal(songs: List<Song>, mode: SortMode): List<Song> = when (mode) {
        SortMode.BY_TIME -> songs.sortedByDescending { it.lastModified }
        SortMode.BY_TITLE -> songs.sortedBy { it.title.lowercase() }
        SortMode.BY_ARTIST -> songs.sortedBy { it.artist.lowercase() }
    }

    /**
     * 扫描媒体库。
     *
     * @param force 是否强制扫描。默认 false：仅在数据库无歌曲时才真正扫描（避免重启应用时重复全表扫描）；
     *              true 表示用户显式触发（下拉刷新、文件变更通知），无论是否已有歌曲都扫描。
     */
    fun loadMusic(force: Boolean = false) {
        viewModelScope.launch {
            // 重启自动扫描时，若数据库已有歌曲则跳过，避免每次启动都全表扫描 MediaStore
            if (!force && repository.hasSongsInDb()) {
                return@launch
            }
            _isLoading.postValue(true)
            try {
                repository.scanMusicFiles()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    fun searchSongs(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (query.isBlank()) {
                repository.getAllSongs().collect { songs ->
                    val sorted = sortSongsInternal(songs, _sortMode.value ?: SortMode.BY_TIME)
                    _allSongs.postValue(sorted)
                    // 搜索清空时，fullSongs 也更新为完整列表
                    _fullSongs.postValue(sorted)
                }
            } else {
                repository.searchSongs(query).collect { songs ->
                    val sorted = sortSongsInternal(songs, _sortMode.value ?: SortMode.BY_TIME)
                    _allSongs.postValue(sorted)
                }
            }
        }
    }

    /**
     * 更新歌曲列表（用于删除歌曲后更新）
     */
    fun updateSongs(songs: List<Song>) {
        _allSongs.postValue(songs)
        _fullSongs.postValue(songs)
    }

    /**
     * 按指定模式排序当前歌曲列表
     */
    fun sortSongs(mode: SortMode) {
        _sortMode.value = mode
        saveSortMode(mode)
        val currentSongs = _allSongs.value ?: return
        val sorted = sortSongsInternal(currentSongs, mode)
        _allSongs.value = sorted
    }

    /**
     * 获取排序后的完整歌曲列表（用于同步到 MusicService）
     */
    fun getSortedFullSongs(): List<Song> {
        val fullSongs = _fullSongs.value ?: return emptyList()
        val mode = _sortMode.value ?: SortMode.BY_TIME
        return sortSongsInternal(fullSongs, mode)
    }
}