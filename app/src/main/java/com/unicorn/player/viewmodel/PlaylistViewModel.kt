package com.unicorn.player.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.unicorn.player.model.Playlist
import com.unicorn.player.model.PlaylistExportData
import com.unicorn.player.model.Song
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.service.DataStoreKeys
import com.unicorn.player.service.PlaySource
import com.unicorn.player.service.PlaySourceManager
import com.unicorn.player.service.applicationDataStore
import com.unicorn.player.ui.PlaylistRefresher
import com.unicorn.player.util.PlaylistFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        var songCount: Int,
        val songList: MutableList<Song> = mutableListOf()
    )

    private val _playlists = MutableLiveData<List<PlaylistInfo>>(emptyList())
    val playlists: LiveData<List<PlaylistInfo>> = _playlists

    /** 导入弹窗列表项 */
    data class PlaylistImportItem(
        val fileName: String,        // 12.json
        val playlistId: Long,        // 来自 JSON 内容
        val displayName: String,     // id 命中取库内当前名，否则取文件内名
        val songCount: Int,          // 文件内路径数
        val matchedCount: Int,       // 当前曲库能按路径匹配到的数量
        val targetExists: Boolean,   // id 或同名歌单是否已存在
        val sameNameMerge: Boolean   // id 不存在、靠同名匹配的情况
    )

    private val _currentPlayingPlaylistId = MutableLiveData<Long?>(null)
    val currentPlayingPlaylistId: LiveData<Long?> = _currentPlayingPlaylistId

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private var playSourceObserver: androidx.lifecycle.Observer<String>? = null

    /** 是否已首次加载过（控制懒加载） */
    private var hasLoadedOnce = false

    /** 隐藏歌曲注册表的观察者引用，用于在 onCleared 时移除 */
    private var hiddenRegistryObserver: androidx.lifecycle.Observer<Set<Long>>? = null

    init {
        viewModelScope.launch {
            val playlistId = getCurrentPlayingPlaylistId()
            _currentPlayingPlaylistId.value = playlistId
        }
        observeHiddenRegistry()
        observePlaySource()
    }

    /**
     * 观察共享注册表的隐藏 ID 变化。当其他 ViewModel 实例隐藏/恢复歌曲时，
     * 当前实例也会收到通知并重新计算歌单歌曲数量，实现即时同步。
     */
    private fun observeHiddenRegistry() {
        hiddenRegistryObserver?.let { HiddenSongRegistry.hiddenSongIds.removeObserver(it) }
        hiddenRegistryObserver = androidx.lifecycle.Observer { _ ->
            refreshPlaylistsInternal()
        }
        HiddenSongRegistry.hiddenSongIds.observeForever(hiddenRegistryObserver!!)
    }

    private fun observePlaySource() {
        playSourceObserver?.let { PlaySourceManager.playSourceTagChanged.removeObserver(it) }
        playSourceObserver = androidx.lifecycle.Observer { sourceTag ->
            viewModelScope.launch {
                _currentPlayingPlaylistId.value = getCurrentPlayingPlaylistId(sourceTag)
            }
        }
        PlaySourceManager.playSourceTagChanged.observeForever(playSourceObserver!!)
    }

    override fun onCleared() {
        super.onCleared()
        hiddenRegistryObserver?.let { HiddenSongRegistry.hiddenSongIds.removeObserver(it) }
        hiddenRegistryObserver = null
        playSourceObserver?.let { PlaySourceManager.playSourceTagChanged.removeObserver(it) }
        playSourceObserver = null
    }

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
                // 当前隐藏的 ID 集合，用于计算时过滤
                val hiddenIds = HiddenSongRegistry.currentIds()
                // 对每个歌单取一次快照数量（first() 单次取值后即取消订阅），过滤隐藏歌曲
                val infos = playlistList.map { pl ->
                    try {
                        // 1. 获取该歌单下的所有歌曲 (List<Song>)
                        val allSongs = repository.getPlaylistSongs(pl.id).first()
                        // 2. 过滤隐藏歌曲并转换为 MutableList
                        // 如果 hiddenIds 为空，直接转换；否则过滤掉 id 在 hiddenIds 中的歌曲
                        val visibleSongList = if (hiddenIds.isEmpty()) {
                            allSongs.toMutableList()
                        } else {
                            allSongs.filter { it.id !in hiddenIds }.toMutableList()
                        }
                        // 3. 计算可见歌曲数量 (直接使用过滤后列表的大小，避免重复遍历)
                        val visibleCount = visibleSongList.size
                        // 4. 构建 PlaylistInfo
                        pl.toInfo(visibleCount, visibleSongList)
                    } catch (e: Exception) {
                        // 异常情况下，数量为0，列表为空
                        pl.toInfo(0, mutableListOf())
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

    private fun Playlist.toInfo(songCount: Int, songList: MutableList<Song>) = PlaylistInfo(
        id = id,
        name = name,
        icon = icon,
        createdAt = createdAt,
        updatedAt = updatedAt,
        songCount = songCount,
        songList = songList
    )

    /**
     * 新建歌单；同名（大小写无关）已在库中时，直接回调 [onDuplicate] 而不写库。
     *
     * @param name 歌单名（会被 trim，空串视为不允许）
     * @param onDuplicate 当 [name] 已存在时调用；用于 Fragment 弹 Toast
     */
    fun createPlaylist(name: String, onDuplicate: (() -> Unit)? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                if (repository.isPlaylistNameUsed(trimmed)) {
                    onDuplicate?.invoke()
                    return@launch
                }
                repository.createPlaylist(trimmed)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            refreshPlaylistsInternal()
        }
    }

    /**
     * 重命名歌单；除自身外存在同名时，回调 [onDuplicate] 而不写库。
     */
    fun renamePlaylist(id: Long, name: String, onDuplicate: (() -> Unit)? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                if (repository.isPlaylistNameUsed(trimmed, excludeId = id)) {
                    onDuplicate?.invoke()
                    return@launch
                }
                repository.renamePlaylist(id, trimmed)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            refreshPlaylistsInternal()
        }
    }

    /**
     * 删除单个歌单。
     *
     * @param onPlaylistDeleted 删除完成后回调，参数为被删除歌单的 ID。
     *   外部可据此通知 MusicService 清理当前播放状态（如果正在播放该歌单）。
     */
    fun deletePlaylist(id: Long, onPlaylistDeleted: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                repository.deletePlaylistById(id)
                onPlaylistDeleted?.invoke(id)
                val currentId = _currentPlayingPlaylistId.value
                if (currentId == id) {
                    _currentPlayingPlaylistId.value = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            refreshPlaylistsInternal()
        }
    }

    private suspend fun getCurrentPlayingPlaylistId(sourceTag: String? = null): Long? {
        return try {
            val tag = sourceTag
                ?: getApplication<Application>().applicationContext.applicationDataStore.data.first()
                    .let { it[DataStoreKeys.PLAY_SOURCE_TAG] ?: PlaySource.SONGS }

            if (tag.startsWith(PlaySource.PLAYLIST)) {
                val (_, playlistIdStr) = PlaySource.parse(tag)
                playlistIdStr.toLongOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 歌单导出 / 导入 ====================

    /**
     * 导出歌单到 Documents/Unicorn/Playlist/&lt;playlistId&gt;.json
     *
     * - 以 repository 实时数据为准（含被隐藏歌曲），不依赖界面缓存的 songList
     * - 同名旧文件直接覆盖
     *
     * @param onResult 回主线程回调 (成功数, 失败数)
     */
    fun exportPlaylists(ids: Collection<Long>, onResult: (success: Int, failed: Int) -> Unit) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var success = 0
            var failed = 0
            val gson = Gson()
            val app = getApplication<Application>()
            for (id in ids) {
                try {
                    val playlist = repository.getPlaylistById(id)
                    if (playlist == null) {
                        failed++
                        continue
                    }
                    val songs = repository.getPlaylistSongs(id).first()
                    val data = PlaylistExportData(
                        version = 1,
                        playlistId = id,
                        playlistName = playlist.name,
                        exportedAt = System.currentTimeMillis(),
                        songs = songs.map { it.path }.distinct()
                    )
                    val json = gson.toJson(data)
                    val ok = PlaylistFileManager.writeExport(
                        app, PlaylistFileManager.fileNameForPlaylist(id), json
                    )
                    if (ok) success++ else failed++
                } catch (e: Exception) {
                    e.printStackTrace()
                    failed++
                }
            }
            withContext(Dispatchers.Main) { onResult(success, failed) }
        }
    }

    /**
     * 加载导入弹窗数据：读取导出目录下所有 json 文件并解析。
     * 损坏 / 缺字段 / 版本不符的文件被跳过。
     *
     * @param onResult 回主线程回调，参数为导入项列表
     */
    fun loadImportItems(onResult: (List<PlaylistImportItem>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = mutableListOf<PlaylistImportItem>()
            try {
                val app = getApplication<Application>()
                val files = PlaylistFileManager.listExportFiles(app)
                val parsed = mutableListOf<Triple<String, PlaylistExportData, List<String>>>()
                val allPaths = mutableSetOf<String>()
                for (fileName in files) {
                    val data = parseExportFile(app, fileName) ?: continue
                    val paths = data.songs.filter { it.isNotBlank() }.distinct()
                    parsed.add(Triple(fileName, data, paths))
                    allPaths.addAll(paths)
                }
                val existingPaths = repository.getSongsByPaths(allPaths.toList())
                    .map { it.path }.toSet()
                for ((fileName, data, paths) in parsed) {
                    val byId = repository.getPlaylistById(data.playlistId)
                    val target = byId ?: repository.findPlaylistByName(data.playlistName.trim())
                    items.add(
                        PlaylistImportItem(
                            fileName = fileName,
                            playlistId = data.playlistId,
                            displayName = byId?.name ?: data.playlistName,
                            songCount = paths.size,
                            matchedCount = paths.count { it in existingPaths },
                            targetExists = target != null,
                            sameNameMerge = byId == null && target != null
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) { onResult(items) }
        }
    }

    /**
     * 导入歌单
     *
     * - 目标歌单判定：playlistId 命中 → 合并；否则同名（大小写无关）命中 → 合并；
     *   都没有 → 用文件内名称新建
     * - 文件中的路径按曲库匹配，匹配不到的丢弃（计入 droppedSongs）
     * - 已存在歌曲不重复添加（REPLACE 去重，天然并集）
     *
     * @param onResult 回主线程回调 (新建数, 合并数, 失败数, 丢弃歌曲数)
     */
    fun importPlaylists(
        fileNames: List<String>,
        onResult: (created: Int, merged: Int, failed: Int, droppedSongs: Int) -> Unit
    ) {
        if (fileNames.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var created = 0
            var merged = 0
            var failed = 0
            var droppedSongs = 0
            try {
                val app = getApplication<Application>()
                for (fileName in fileNames) {
                    val data = parseExportFile(app, fileName)
                    if (data == null) {
                        failed++
                        continue
                    }
                    try {
                        val byId = repository.getPlaylistById(data.playlistId)
                        val target = byId
                            ?: repository.findPlaylistByName(data.playlistName.trim())
                        val targetId: Long
                        if (target != null) {
                            targetId = target.id
                            merged++
                        } else {
                            targetId = repository.createPlaylist(data.playlistName.trim())
                            if (targetId <= 0L) {
                                failed++
                                continue
                            }
                            created++
                        }
                        val distinctPaths = data.songs.filter { it.isNotBlank() }.distinct()
                        val matched = if (distinctPaths.isEmpty()) {
                            emptyList()
                        } else {
                            repository.getSongsByPaths(distinctPaths)
                        }
                        droppedSongs += distinctPaths.size - matched.size
                        if (matched.isNotEmpty()) {
                            // 只添加歌单中尚不存在的歌曲，避免重复 REPLACE
                            val existingIds =
                                repository.getPlaylistSongIds(targetId).first().toSet()
                            val newSongs = matched.filter { it.id !in existingIds }
                            if (newSongs.isNotEmpty()) {
                                repository.addSongsToPlaylist(targetId, newSongs)
                                // 实际有新增才刷新更新时间
                                repository.touchPlaylist(targetId)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        failed++
                    }
                }
                refreshPlaylistsInternal()
                PlaylistRefresher.notifyPlaylistsChanged()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) { onResult(created, merged, failed, droppedSongs) }
        }
    }

    /**
     * 解析单个导出文件；损坏 / 缺字段 / 版本不符返回 null。
     * 注意：空歌单的 songs 为 []（合法），不能据此判无效；
     * Gson 反序列化绕过构造函数默认值，songs 字段缺失时为运行时 null，统一规范化为空列表。
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun parseExportFile(context: Context, fileName: String): PlaylistExportData? {
        return try {
            val content = PlaylistFileManager.readExport(context, fileName) ?: return null
            val data = Gson().fromJson(content, PlaylistExportData::class.java)
                ?: return null
            if (data.version != 1 ||
                data.playlistId <= 0L || data.playlistName.isNullOrBlank()
            ) {
                return null
            }
            val songs: List<String> = if (data.songs == null) emptyList() else data.songs
            data.copy(songs = songs)
        } catch (e: Exception) {
            null
        }
    }
}
