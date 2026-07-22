package com.unicorn.player.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.applicationDataStore
import com.unicorn.player.service.DataStoreKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
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

    // 当前歌单的歌曲列表（PlaylistSongsActivity 使用）
    private val _playlistSongs = MutableLiveData<List<Song>>(emptyList())
    val playlistSongs: LiveData<List<Song>> = _playlistSongs

    private var playlistSongsJob: Job? = null

    // 排序模式，ordinal 与 sort_mode_prefs 存储值一致，默认 BY_TIME
    enum class SortMode { BY_TIME, BY_TITLE, BY_ARTIST }

    private val _sortMode = MutableLiveData(SortMode.BY_TITLE)
    val sortMode: LiveData<SortMode> = _sortMode

    private var searchJob: Job? = null

    // Room DB 中的原始歌曲列表（未过滤），用于检测外部删除
    private var rawSongs: List<Song> = emptyList()
    // 歌单歌曲的原始列表（未过滤隐藏歌曲），用于注册表变化时重新过滤
    private var rawPlaylistSongs: List<Song> = emptyList()
    // 标记隐藏 ID 集合是否已从 DataStore 加载完成，防止竞态闪烁
    private var hiddenIdsReady = false
    // 隐藏歌曲注册表的观察者引用，用于在 onCleared 时移除
    private var hiddenRegistryObserver: androidx.lifecycle.Observer<Set<Long>>? = null

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
                loadHiddenSongs()
            }
        }
    }

    /**
     * 从 DataStore 加载隐藏歌曲 ID 集合，加载完成后启动 collectSongs 并注册跨实例观察
     */
    private fun loadHiddenSongs() {
        viewModelScope.launch {
            try {
                val prefs = context.applicationDataStore.data.first()
                val ids = prefs[DataStoreKeys.HIDDEN_SONG_IDS]
                    ?.mapNotNull { it.toLongOrNull() }
                    ?.toSet() ?: emptySet()
                HiddenSongRegistry.setIds(ids)
            } catch (e: Exception) {
                e.printStackTrace()
                HiddenSongRegistry.setIds(emptySet())
            } finally {
                hiddenIdsReady = true
                collectSongs()
                observeHiddenRegistry()
            }
        }
    }

    /**
     * 观察共享注册表的隐藏 ID 变化。当其他 ViewModel 实例隐藏歌曲时，
     * 当前实例也会收到通知并重新 emit 过滤后的列表，
     * 从而实现歌手/专辑/歌单页面的同步刷新。
     */
    private fun observeHiddenRegistry() {
        // 避免重复注册
        hiddenRegistryObserver?.let { HiddenSongRegistry.hiddenSongIds.removeObserver(it) }
        hiddenRegistryObserver = androidx.lifecycle.Observer { ids ->
            if (rawSongs.isEmpty()) return@Observer
            val visible = rawSongs.filter { it.id !in ids }
            val sorted = sortSongsInternal(visible, _sortMode.value ?: SortMode.BY_TIME)
            _allSongs.postValue(sorted)
            _fullSongs.postValue(sorted)
            // 同步过滤歌单歌曲列表，使歌单页面也响应隐藏/恢复操作
            if (rawPlaylistSongs.isNotEmpty()) {
                val visiblePlaylist = rawPlaylistSongs.filter { it.id !in ids }
                _playlistSongs.postValue(visiblePlaylist)
            }
        }
        HiddenSongRegistry.hiddenSongIds.observeForever(hiddenRegistryObserver!!)
    }

    /**
     * 异步持久化隐藏歌曲 ID 集合到 DataStore
     */
    private fun persistHiddenSongs(ids: Set<Long>) {
        viewModelScope.launch {
            try {
                context.applicationDataStore.edit { prefs ->
                    prefs[DataStoreKeys.HIDDEN_SONG_IDS] = ids.map { it.toString() }.toSet()
                    prefs
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 隐藏指定歌曲（从列表中删除）：更新共享注册表、持久化。
     * 注册表变化会自动通知所有 ViewModel 实例刷新各自的列表。
     *
     * 注意：不从 playlist_songs 表物理删除。原因：
     * 1. PlaylistSong 外键带 CASCADE，scanMusicFiles 删除 songs 行时会自动清理关联；
     * 2. 隐藏是"逻辑删除"，下拉刷新（clearHiddenSongs）后应恢复可见；
     * 3. 歌单数量通过 HiddenSongRegistry.currentIds() 过滤计算，无需物理删除。
     */
    fun hideSong(songId: Long) {
        if (!hiddenIdsReady) return
        if (HiddenSongRegistry.currentIds().contains(songId)) return
        HiddenSongRegistry.hide(songId)
        persistHiddenSongs(HiddenSongRegistry.currentIds())
    }

    /**
     * 清空隐藏集合（下拉刷新时调用）：持久化、重新 emit 完整列表
     */
    fun clearHiddenSongs() {
        HiddenSongRegistry.clear()
        persistHiddenSongs(emptySet())
    }

    /**
     * 过滤隐藏歌曲并按当前排序模式排序（共用逻辑）
     */
    private fun applyHiddenAndSort(songs: List<Song>): List<Song> {
        val hidden = HiddenSongRegistry.currentIds()
        val visible = songs.filter { it.id !in hidden }
        return sortSongsInternal(visible, _sortMode.value ?: SortMode.BY_TIME)
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
        if (!hiddenIdsReady) return
        viewModelScope.launch {
            repository.getAllSongs().collect { songs ->
                // 检测被外部删除的歌曲，清理其在隐藏集合中的残留
                cleanupRemovedSongs(songs)
                rawSongs = songs
                val sorted = applyHiddenAndSort(songs)
                _allSongs.postValue(sorted)
                _fullSongs.postValue(sorted)
            }
        }
    }

    /**
     * 对比新旧列表，找出已被外部删除的歌曲 ID，若存在于隐藏集合中则移除
     */
    private fun cleanupRemovedSongs(newSongs: List<Song>) {
        if (rawSongs.isEmpty()) return
        val newIds = newSongs.map { it.id }.toSet()
        val removedIds = rawSongs.map { it.id }.toSet() - newIds
        if (removedIds.isNotEmpty()) {
            val hidden = HiddenSongRegistry.currentIds()
            val cleaned = hidden - removedIds
            if (cleaned.size < hidden.size) {
                HiddenSongRegistry.setIds(cleaned)
                persistHiddenSongs(cleaned)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        hiddenRegistryObserver?.let { HiddenSongRegistry.hiddenSongIds.removeObserver(it) }
        hiddenRegistryObserver = null
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
            // 下拉刷新（force=true）时清除隐藏集合，恢复完整列表
            if (force) {
                clearHiddenSongs()
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
                    val sorted = applyHiddenAndSort(songs)
                    _allSongs.postValue(sorted)
                    // 搜索清空时，fullSongs 也更新为完整列表
                    _fullSongs.postValue(sorted)
                }
            } else {
                repository.searchSongs(query).collect { songs ->
                    val sorted = applyHiddenAndSort(songs)
                    _allSongs.postValue(sorted)
                }
            }
        }
    }

    /**
     * 更新歌曲列表（用于删除歌曲后更新）
     */
    fun updateSongs(songs: List<Song>) {
        rawSongs = songs
        val sorted = applyHiddenAndSort(songs)
        _allSongs.postValue(sorted)
        _fullSongs.postValue(sorted)
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
     * 按当前全局排序模式排序任意歌曲列表（供歌手/专辑/歌单详情页展示用），
     * 让详情页歌曲的展示顺序与主界面 ivSort 所选模式保持一致。
     */
    fun sortWithCurrentMode(songs: List<Song>): List<Song> {
        val mode = _sortMode.value ?: SortMode.BY_TITLE
        return sortSongsInternal(songs, mode)
    }

    /**
     * 获取排序后的完整歌曲列表（用于同步到 MusicService）
     */
    fun getSortedFullSongs(): List<Song> {
        val fullSongs = _fullSongs.value ?: return emptyList()
        val mode = _sortMode.value ?: SortMode.BY_TIME
        return sortSongsInternal(fullSongs, mode)
    }

    /**
     * 加载指定歌单的歌曲列表，写入 [_playlistSongs]（PlaylistSongsActivity 使用）
     */
    fun loadPlaylistSongs(playlistId: Long) {
        playlistSongsJob?.cancel()
        playlistSongsJob = viewModelScope.launch {
            try {
                repository.getPlaylistSongs(playlistId).collect { songs ->
                    rawPlaylistSongs = songs
                    val filtered = songs.filter { it.id !in HiddenSongRegistry.currentIds() }
                    _playlistSongs.postValue(filtered)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 同步更新歌单歌曲列表（添加歌曲、删除歌曲后立即刷新）
     */
    fun updatePlaylistSongs(songs: List<Song>) {
        rawPlaylistSongs = songs
        val filtered = songs.filter { it.id !in HiddenSongRegistry.currentIds() }
        _playlistSongs.postValue(filtered)
    }

    /**
     * 合并添加歌曲到指定歌单（REPLACE 去重）；写完后自动 reload 让 LiveData 更新
     */
    fun addSongsToPlaylist(playlistId: Long, songs: List<Song>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.addSongsToPlaylist(playlistId, songs)
                // 写操作完成后 reload，让 playlistSongs LiveData 立即反映最新
                loadPlaylistSongsInternal(playlistId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 从指定歌单移除单首歌曲；写完后自动 reload，让 playlistSongs LiveData 立即反映最新
     */
    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.removeSongFromPlaylist(playlistId, songId)
                loadPlaylistSongsInternal(playlistId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 重命名歌单
     */
    fun renamePlaylistName(playlistId: Long, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.renamePlaylist(playlistId, name)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 内部：reload 某歌单的歌曲列表并推送到 LiveData
     */
    private fun loadPlaylistSongsInternal(playlistId: Long) {
        viewModelScope.launch {
            try {
                repository.getPlaylistSongs(playlistId).collect { songs ->
                    rawPlaylistSongs = songs
                    val filtered = songs.filter { it.id !in HiddenSongRegistry.currentIds() }
                    _playlistSongs.postValue(filtered)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}