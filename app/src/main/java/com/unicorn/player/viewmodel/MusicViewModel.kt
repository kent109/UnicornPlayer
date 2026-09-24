package com.unicorn.player.viewmodel

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unicorn.player.ScanFilterActivity
import com.unicorn.player.model.Album
import com.unicorn.player.model.ScanFilterConfig
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.scanFiltersDataStore
import com.unicorn.player.service.DataStoreKeys
import com.unicorn.player.service.PlaySource
import com.unicorn.player.service.PlaySourceManager
import com.unicorn.player.service.applicationDataStore
import com.unicorn.player.util.PinyinUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

class MusicViewModel(
    private val repository: MusicRepository,
    private val context: Context
) : ViewModel() {

    private val _allSongs = MutableLiveData<List<Song>>(emptyList())
    val allSongs: LiveData<List<Song>> = _allSongs

    // 标记 Room Flow 是否已发射过首个值，防止启动时初始空列表导致 emptyView 闪现
    private val _hasLoaded = MutableLiveData(false)
    val hasLoaded: LiveData<Boolean> = _hasLoaded

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

    // 按首字母分组排序后的专辑列表，由后台协程计算后缓存，避免 Fragment 在主线程重复算拼音/排序
    private val _albums = MutableLiveData<List<Album>>(emptyList())
    val albums: LiveData<List<Album>> = _albums

    private var albumsJob: Job? = null

    private val collator = Collator.getInstance(Locale.CHINA)

    private var playSourceObserver: androidx.lifecycle.Observer<String>? = null

    private var _currentPlayingArtist = MutableLiveData<String?>(null)
    val currentPlayingArtist: LiveData<String?> = _currentPlayingArtist

    private var _currentPlayingAlbum = MutableLiveData<String?>(null)
    val currentPlayingAlbum: LiveData<String?> = _currentPlayingAlbum

    private var searchJob: Job? = null

    // Room DB 中的原始歌曲列表（未过滤），用于检测外部删除
    private var rawSongs: List<Song> = emptyList()
    // 歌单歌曲的原始列表（未过滤隐藏歌曲），用于注册表变化时重新过滤
    private var rawPlaylistSongs: List<Song> = emptyList()
    // 标记隐藏 ID 集合是否已从 DataStore 加载完成，防止竞态闪烁
    private var hiddenIdsReady = false
    // 隐藏歌曲注册表的观察者引用，用于在 onCleared 时移除
    private var hiddenRegistryObserver: androidx.lifecycle.Observer<Set<Long>>? = null

    // 监听歌曲列表变化，触发后台专辑分组计算
    // 歌曲列表为空时也需要 computeAlbums，确保 _albums 同步为空（否则 waveSideBar 仍显示）
    private val allSongsObserver: Observer<List<Song>> = Observer { songs ->
        computeAlbums(songs)
    }

    init {
        // 使用 observeForever 因为 ViewModel 本身没有 LifecycleOwner；在 onCleared 中移除
        _allSongs.observeForever(allSongsObserver)
        observePlaySource()
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

    private var songsCollected = false

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
                // 首次发射后标记已加载，避免启动时 emptyView 闪现
                if (!songsCollected) {
                    songsCollected = true
                    _hasLoaded.postValue(true)
                }
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
        _allSongs.removeObserver(allSongsObserver)
        hiddenRegistryObserver?.let { HiddenSongRegistry.hiddenSongIds.removeObserver(it) }
        hiddenRegistryObserver = null
        playSourceObserver?.let { PlaySourceManager.playSourceTagChanged.removeObserver(it) }
        playSourceObserver = null
    }

    private fun observePlaySource() {
        playSourceObserver?.let { PlaySourceManager.playSourceTagChanged.removeObserver(it) }
        playSourceObserver = androidx.lifecycle.Observer { sourceTag ->
            viewModelScope.launch {
                _currentPlayingArtist.value = getCurrentPlayingArtist(sourceTag)
                _currentPlayingAlbum.value = getCurrentPlayingAlbum(sourceTag)
            }
        }
        PlaySourceManager.playSourceTagChanged.observeForever(playSourceObserver!!)
    }

    private suspend fun getCurrentPlayingArtist(sourceTag: String? = null): String? {
        return try {
            val tag = sourceTag
                ?: context.applicationDataStore.data.first()
                    .let { it[DataStoreKeys.PLAY_SOURCE_TAG] ?: PlaySource.SONGS }

            if (tag.startsWith(PlaySource.ARTIST)) {
                val (_, artistName) = PlaySource.parse(tag)
                artistName
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getCurrentPlayingAlbum(sourceTag: String? = null): String? {
        return try {
            val tag = sourceTag
                ?: context.applicationDataStore.data.first()
                    .let { it[DataStoreKeys.PLAY_SOURCE_TAG] ?: PlaySource.SONGS }

            if (tag.startsWith(PlaySource.ALBUM)) {
                val (_, albumName) = PlaySource.parse(tag)
                albumName
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 歌曲列表变化时，后台计算按首字母分组排序的专辑列表。
     * 计算包含拼音转换与 Collator 排序，放在 Dispatchers.Default 避免阻塞主线程。
     * 结果缓存到 [_albums]，Fragment 观察此 LiveData 直接获取现成列表。
     */
    private fun computeAlbums(songs: List<Song>) {
        albumsJob?.cancel()
        albumsJob = viewModelScope.launch {
            val albums = withContext(Dispatchers.Default) {
                groupAndSortAlbums(songs)
            }
            _albums.value = albums
        }
    }

    /**
     * 按专辑名分组统计歌曲数量，按拼音首字母分组排序。
     * 原 AlbumFragment.submitAccounts 逻辑下沉到 ViewModel，主线程不再做重计算。
     */
    private fun groupAndSortAlbums(songs: List<Song>): List<Album> {
        // 1. 以 lowerCase 专辑名作为分组键，保留首次出现的原始大小写用于显示
        val displayCase = LinkedHashMap<String, String>()
        // 2. 每组维护：歌曲数
        val counts = LinkedHashMap<String, Int>()
        // 【修改点】：不再存储 ID，而是直接存储 Song 对象列表
        val albumSongs = LinkedHashMap<String, MutableList<Song>>()
        // 3. 每组维护：各歌手出现次数（用于取数量最多的歌手作为副标题）
        val artistCounts = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        for (song in songs) {
            val key = song.album.lowercase()
            // 记录原始专辑名（仅记录第一次出现的）
            if (!displayCase.containsKey(key)) {
                displayCase[key] = song.album
            }
            // 计数 +1
            counts[key] = (counts[key] ?: 0) + 1
            // getOrPut: 如果 key 不存在，创建一个空的 mutableListOf 并放入 map，然后返回该列表
            albumSongs.getOrPut(key) { mutableListOf() }.add(song)
            // 统计歌手出现次数
            val groupArtists = artistCounts.getOrPut(key) { LinkedHashMap() }
            groupArtists[song.artist] = (groupArtists[song.artist] ?: 0) + 1
        }
        // 4. 构建 Album 列表
        val albums = counts.map { (key, count) ->
            // 获取出现次数最多的歌手
            val topArtist = artistCounts[key]?.maxByOrNull { it.value }?.key ?: ""
            // 获取首字母
            val letter = PinyinUtil.getPinyinFirstLetter(displayCase[key] ?: key)
            // 注意：albumSongs[key] 一定存在，因为 counts 的 key 来源于同样的循环
            val songList = albumSongs[key] ?: mutableListOf()
            Album(
                name = displayCase[key] ?: key,
                artist = topArtist,
                songCount = count,
                songList = songList,
                firstLetter = letter
            )
        }

        // 按首字母分组：字母 A-Z 顺序，"#" 置于末尾
        val grouped = LinkedHashMap<String, MutableList<Album>>()
        for (album in albums) {
            grouped.getOrPut(album.firstLetter) { mutableListOf() }.add(album)
        }

        // 组内按专辑名排序（中文按拼音、英文不区分大小写）
        for ((_, list) in grouped) {
            list.sortWith(compareBy(collator) { it.name.lowercase() })
        }

        val orderedLetters = grouped.keys.sortedWith { a, b ->
            when {
                a == "#" -> 1
                b == "#" -> -1
                else -> a.compareTo(b)
            }
        }

        // 扁平化为按首字母排序的专辑列表
        val result = mutableListOf<Album>()
        for (letter in orderedLetters) {
            grouped[letter]?.let { result.addAll(it) }
        }
        return result
    }

    private fun sortSongsInternal(songs: List<Song>, mode: SortMode): List<Song> = when (mode) {
        SortMode.BY_TIME -> songs.sortedByDescending { it.lastModified }
        // 按首字母排序：中文名整体转拼音、英文名原样保留，再按字符串排序，
        // 中英文才能 A-Z 混排（与歌手/专辑列表一致）。
        // 不能用 Collator(Locale.CHINA)，该 Collator 在部分设备上把英文整体排在中文之后；
        // 也不能直接按原字符串排（中文按 Unicode 码点/部首排，不是拼音）。
        // sortedBy 会为每首歌只计算一次 key，避免 O(NlogN) 次重复拼音转换。
        SortMode.BY_TITLE -> songs.sortedBy {
            PinyinUtil.getPinyinString(it.title).lowercase()
        }
        SortMode.BY_ARTIST -> songs.sortedBy {
            PinyinUtil.getPinyinString(it.artist).lowercase()
        }
    }

    /**
     * 扫描媒体库。
     *
     * @param force 是否强制扫描。默认 false：仅在数据库无歌曲时才真正扫描（避免重启应用时重复全表扫描）；
     *              true 表示用户显式触发（下拉刷新、文件变更通知），无论是否已有歌曲都扫描。
     */
    fun loadMusic(force: Boolean = false, onScanned: ((Int) -> Unit)? = null) {
        viewModelScope.launch {
            // 重启自动扫描时，若数据库已有歌曲则跳过，避免每次启动都全表扫描 MediaStore
            if (!force && repository.hasSongsInDb()) {
                onScanned?.invoke(0)
                return@launch
            }
            // 下拉刷新（force=true）时清除隐藏集合，恢复完整列表
            if (force) {
                clearHiddenSongs()
            }
            _isLoading.postValue(true)
            val count = try {
                val config = runBlocking {
                    val prefs = context.scanFiltersDataStore.data.first()
                    ScanFilterConfig(
                        skipShortAudio = prefs[ScanFilterActivity.SKIP_SHORT_AUDIO] ?: false,
                        skipSmallFiles = prefs[ScanFilterActivity.SKIP_SMALL_FILES] ?: false,
                        excludedDirs = prefs[ScanFilterActivity.EXCLUDED_DIRS] ?: emptySet(),
                        includedDirs = prefs[ScanFilterActivity.INCLUDED_DIRS] ?: emptySet()
                    )
                }
                repository.scanMusicFiles(config).size
            } catch (e: Exception) {
                e.printStackTrace()
                -1
            } finally {
                _isLoading.postValue(false)
            }
            onScanned?.invoke(count)
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