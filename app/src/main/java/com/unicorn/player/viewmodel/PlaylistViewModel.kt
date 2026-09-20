package com.unicorn.player.viewmodel

import android.app.Application
import android.content.Context
import android.os.Looper
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
            viewModelScope.launch { refreshPlaylistsInternal() }
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
        viewModelScope.launch { refreshPlaylistsInternal() }
    }

    /**
     * 强制重新查询（下拉刷新、写操作返回时调用，忽略 [hasLoadedOnce]）
     */
    fun refreshPlaylists() {
        viewModelScope.launch { refreshPlaylistsInternal() }
    }

    /**
     * 挂起版本：等待刷新完成后返回。供需要在刷新后继续执行外部调用方使用。
     */
    suspend fun refreshPlaylistsSuspend() {
        refreshPlaylistsInternal()
    }

    private suspend fun refreshPlaylistsInternal() {
        val onMain = Looper.getMainLooper().thread == Thread.currentThread()
        if (onMain) _isLoading.value = true else _isLoading.postValue(true)
        try {
            val playlistList = repository.getAllPlaylists().first()
            val hiddenIds = HiddenSongRegistry.currentIds()
            val infos = playlistList.map { pl ->
                try {
                    val allSongs = repository.getPlaylistSongs(pl.id).first()
                    val visibleSongList = if (hiddenIds.isEmpty()) {
                        allSongs.toMutableList()
                    } else {
                        allSongs.filter { it.id !in hiddenIds }.toMutableList()
                    }
                    val visibleCount = visibleSongList.size
                    pl.toInfo(visibleCount, visibleSongList)
                } catch (e: Exception) {
                    pl.toInfo(0, mutableListOf())
                }
            }
            if (onMain) {
                _playlists.value = infos
            } else {
                _playlists.postValue(infos)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (onMain) _isLoading.value = false else _isLoading.postValue(false)
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
     * 同名导出冲突分组：当前歌单 [playlistName] 与导出文件 [fileNames]
     *（文件内 playlistId 与当前歌单不同，常见于清除应用数据后重新新建同名歌单）同名。
     */
    data class ExportConflict(
        val playlistId: Long,
        val playlistName: String,
        val fileNames: List<String>
    )

    /**
     * 导出预检结果
     *
     * @property conflicts 同名（不同 playlistId）冲突分组
     * @property projectedFileCount 按冲突文件被删除、新文件写入后的预计文件总数
     * @property overLimit 预计总数是否超过 [PlaylistFileManager.MAX_EXPORT_COUNT]
     */
    data class ExportPrecheck(
        val conflicts: List<ExportConflict>,
        val projectedFileCount: Int,
        val overLimit: Boolean
    )

    /**
     * 导出预检：扫描导出目录，计算同名冲突与解决冲突后的文件总数（供 UI 决定
     * 弹「同名处理」弹窗还是「上限清理」弹窗）。
     */
    fun precheckExport(ids: Collection<Long>, onResult: (ExportPrecheck) -> Unit) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val pre = buildExportPrecheck(getApplication(), ids)
            withContext(Dispatchers.Main) { onResult(pre) }
        }
    }

    /** 已解析的导出文件（文件名 + JSON 数据），冲突匹配 / 合并时复用，避免重复读盘解析 */
    private data class ParsedFile(val fileName: String, val data: PlaylistExportData)

    private suspend fun loadParsedFiles(context: Context): List<ParsedFile> {
        val result = mutableListOf<ParsedFile>()
        for (fileName in PlaylistFileManager.listExportFiles(context)) {
            parseExportFile(context, fileName)?.let { result.add(ParsedFile(fileName, it)) }
        }
        return result
    }

    /**
     * 判断导出文件是否与当前歌单构成同名冲突：
     * 只要名称相同（trim + NOCASE）即视为冲突 —— 包括：
     * - 文件内 playlistId 与当前歌单不同（清除应用数据后重建的同名歌单，或历史遗留）；
     * - playlistId 相同（同一歌单重复导出）。
     * 重复导出时也弹合并/覆盖弹窗，用户可在「恢复之前导出过的歌曲」与「仅保存当前歌单」之间选择，
     * 避免静默覆盖丢失历史。结果统一以当前 playlistId 重新写入。
     */
    private fun isConflictFile(pf: ParsedFile, playlist: Playlist): Boolean {
        return pf.data.playlistName.trim().equals(playlist.name.trim(), ignoreCase = true)
    }

    /**
     * 构建预检结果：
     * - 同名文件无论覆盖 / 合并都会被删除后以当前 playlistId 重新写入。
     *   同 ID 重复导出时冲突文件就是自身旧文件，净增量 = 1 - 1 = 0；
     *   无同名文件时净增量 = 1；多个同名（不同 ID）时净增量 = 1 - 冲突数。
     */
    private suspend fun buildExportPrecheck(
        context: Context,
        ids: Collection<Long>
    ): ExportPrecheck {
        val allFiles = PlaylistFileManager.listExportFiles(context)
        val parsed = loadParsedFiles(context)
        val conflicts = mutableListOf<ExportConflict>()
        var projected = allFiles.size
        for (id in ids) {
            val playlist = repository.getPlaylistById(id) ?: continue
            val conflictFiles = parsed
                .filter { isConflictFile(it, playlist) }
                .map { it.fileName }
            if (conflictFiles.isNotEmpty()) {
                conflicts.add(ExportConflict(id, playlist.name, conflictFiles))
            }
            // 同 ID 重复导出时 ownFileName 就在 conflictFiles 中，净增 0
            val ownFileName = PlaylistFileManager.fileNameForPlaylist(id)
            if (ownFileName !in conflictFiles) {
                projected += 1 - conflictFiles.size
            }
        }
        return ExportPrecheck(
            conflicts = conflicts,
            projectedFileCount = projected,
            overLimit = projected > PlaylistFileManager.MAX_EXPORT_COUNT
        )
    }

    /**
     * 导出歌单到 Documents/Unicorn/Playlist/&lt;playlistId&gt;.json
     *
     * - 以 repository 实时数据为准（含被隐藏歌曲），不依赖界面缓存的 songList
     * - 同一 playlistId 的旧文件直接覆盖
     * - 同名（不同 playlistId）冲突文件：[mergeConflicts] 为 true 时将旧文件中的
     *   歌曲路径与当前歌单取并集后保存；false 时仅保存当前歌单；冲突文件均删除，
     *   结果统一以当前 playlistId 重新写入
     *
     * @param mergeConflicts 同名冲突的处理方式（由 UI 弹窗用户选择）
     * @param onResult 回主线程回调 (成功数, 失败数)
     */
    fun exportPlaylists(
        ids: Collection<Long>,
        mergeConflicts: Boolean = false,
        onResult: (success: Int, failed: Int) -> Unit
    ) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var success = 0
            var failed = 0
            val gson = Gson()
            val app = getApplication<Application>()
            val parsed = loadParsedFiles(app)
            for (id in ids) {
                try {
                    val playlist = repository.getPlaylistById(id)
                    if (playlist == null) {
                        failed++
                        continue
                    }
                    val songs = repository.getPlaylistSongs(id).first()
                    val ownFileName = PlaylistFileManager.fileNameForPlaylist(id)
                    val conflicts = parsed.filter { isConflictFile(it, playlist) }
                    var paths: List<String> = songs.map { it.path }
                    if (mergeConflicts && conflicts.isNotEmpty()) {
                        // 合并：当前歌单路径 + 旧导出文件路径取并集
                        val oldPaths = conflicts.flatMap { it.data.songs }
                        paths = (paths + oldPaths).filter { it.isNotBlank() }.distinct()
                    }
                    // 无论覆盖 / 合并，冲突旧文件都删除，结果以当前 playlistId 保存
                    conflicts.forEach {
                        PlaylistFileManager.deleteFile(app, it.fileName)
                    }
                    val data = PlaylistExportData(
                        version = 1,
                        playlistId = id,
                        playlistName = playlist.name,
                        exportedAt = System.currentTimeMillis(),
                        songs = paths
                    )
                    val json = gson.toJson(data)
                    val ok = PlaylistFileManager.writeExport(app, ownFileName, json)
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
