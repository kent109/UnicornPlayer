package com.unicorn.player.repository

import aman.taglib.TagLib
import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import com.unicorn.player.ScanFilterActivity
import com.unicorn.player.database.MusicDatabase
import com.unicorn.player.model.Cover
import com.unicorn.player.model.Playlist
import com.unicorn.player.model.PlaylistSong
import com.unicorn.player.model.ScanFilterConfig
import com.unicorn.player.model.Song
import com.unicorn.player.scanFiltersDataStore
import com.unicorn.player.util.CoverStore
import com.unicorn.player.util.PlaylistFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.internal.immutableListOf
import java.io.File
import java.util.Locale
import kotlin.math.absoluteValue


class MusicRepository(private val context: Context) {

    private val database = MusicDatabase.getDatabase(context)
    private val songDao = database.songDao()
    private val playlistDao = database.playlistDao()
    private val coverDao = database.coverDao()

    suspend fun scanMusicFiles(config: ScanFilterConfig = ScanFilterConfig()): List<Song> = withContext(Dispatchers.IO) {
        val newSongs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.GENRE
        )

        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0" +
                " AND " + MediaStore.Audio.Media.DATA + " NOT LIKE '%/music/Recordings/%'" +
                " AND " + MediaStore.Audio.Media.DATA + " NOT LIKE '%/allsaintsMusic/%'" +
                " AND " + MediaStore.Audio.Media.DATA + " NOT LIKE '%/msc/%'"

        // 用户设置过的封面按路径登记在私有目录里（文件名带路径摘要），
        // 删掉文件重新拷入、"清空并重新扫描"换了 _ID 也能按路径找回
        val songCovers = CoverStore.songCoverIndex(context)

        // 1. 扫描 MediaStore 获取最新歌曲列表
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            MediaStore.Audio.Media.TITLE + " ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val genreColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                var title = cursor.getString(titleColumn) ?: "Unknown Title"
                var artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                var album = cursor.getString(albumColumn) ?: "Unknown Album"
                var genre = cursor.getString(genreColumn)
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(pathColumn)
                val albumId = cursor.getLong(albumIdColumn)
                val mime = cursor.getString(mimeColumn)

                // 直接从文件读取标签，覆盖 MediaStore 的值
                // 确保 TagLib 写入的标签能正确读取
                val fileTags = TagLib.getMetadata(path)
                fileTags["TITLE"]?.takeIf { it.isNotEmpty() }?.let { title = it }
                fileTags["ARTIST"]?.takeIf { it.isNotEmpty() }?.let { artist = it }
                fileTags["ALBUM"]?.takeIf { it.isNotEmpty() }?.let { album = it }
                fileTags["GENRE"]?.takeIf { it.isNotEmpty() }?.let { genre = it }

                if (isUnknownArtist(artist)) {
                    artist = "<unknown>"
                }
                if (isUnknownAlbum(album, path)) {
                    album = "<unknown>"
                }
                if (genre.isNullOrEmpty() || genre == "<unknown>") {
                    genre = "<unknown>"
                }

                val file = File(path)
                if (!file.exists()) {
                    continue
                }

                // 应用扫描过滤配置
                if (config.shouldSkip(duration, file)) continue

                val albumArtUri = ContentUris.withAppendedId(
                    "content://media/external/audio/albumart".toUri(),
                    albumId
                ).toString()

                val lastModified = file.lastModified()

                val quality = classifyQuality(mime, file)

                // 有本地副本时以它作为展示封面，媒体库 URI 留作"默认封面"
                val customCover = songCovers[CoverStore.songCoverPrefix(path)]

                val song = Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    path = path,
                    albumArt = customCover ?: albumArtUri,
                    lastModified = lastModified,
                    quality = quality,
                    genre = genre
                )
                newSongs.add(song)
            }
        }

        // 2. 获取数据库中当前的歌曲（需要 id 与 path，用于识别外部改名）
        val existingSongs = try {
            songDao.getAllSongsSync()
        } catch (e: Exception) {
            emptyList()
        }
        val existingPathById = existingSongs.associateBy({ it.id }, { it.path })

        // 3. 计算需要删除的歌曲（在数据库中但不在新扫描结果中）
        val newSongIds = newSongs.mapTo(HashSet()) { it.id }
        val songsToDelete = existingPathById.keys.filter { it !in newSongIds }

        // 4. 删除不再存在的歌曲
        if (songsToDelete.isNotEmpty()) {
            songDao.deleteSongsByIds(songsToDelete)
        }

        // 5. 新增歌曲用 IGNORE 插入，已存在的只更新路径变化的记录
        // 注意：不能用 REPLACE，因为 SQLite 的 REPLACE = DELETE + INSERT，
        // 会触发 playlist_songs 的 ForeignKey.CASCADE 误删已有歌单关联。
        //
        // 同 ID 但路径不同必须更新：MediaProvider 按 inode 索引，在文件管理器里改名后
        // _ID 不变、只有 _data 变，若继续跳过该行曲库会停留在旧路径上，
        // 既播放不了，也会让按路径记录的"已删除歌曲"把它一直过滤掉。
        val songsToInsert = newSongs.filter { !existingPathById.containsKey(it.id) }
        val songsToRefresh = newSongs.filter { existingPathById[it.id]?.let { p -> p != it.path } == true }
        if (songsToInsert.isNotEmpty()) {
            songDao.insertSongsIgnoreExisting(songsToInsert)
        }
        if (songsToRefresh.isNotEmpty()) {
            // 改名/移动：整行会被扫描结果覆盖，自定义封面要跟着迁到新路径的分组，
            // 否则下次重新索引按新路径找不回它
            val existingById = existingSongs.associateBy { it.id }
            val refreshed = songsToRefresh.map { newSong ->
                val customCover = existingById[newSong.id]?.albumArt
                    ?.takeIf { CoverStore.isCustomSongCover(context, it) }
                if (customCover != null) {
                    newSong.copy(
                        albumArt = CoverStore.migrateSongCover(context, customCover, newSong.path)
                    )
                } else newSong
            }
            songDao.updateSongs(refreshed)
        }

        newSongs
    }

    private fun isUnknownArtist(artist: String?): Boolean {
        if (artist.isNullOrEmpty()) {
            return true
        }
        val list = immutableListOf("<unknown>", "unknown")
        return list.contains(artist.lowercase(Locale.getDefault()))
    }

    private fun isUnknownAlbum(album: String?, filePath: String): Boolean {
        if (album.isNullOrEmpty()) {
            return true
        }
        val list = immutableListOf("<unknown>", "unknown", "download", "document")
        if (list.contains(album.lowercase(Locale.getDefault()))) {
            return true
        }
        var externalPath = "/storage/emulated/"
        val subPath = filePath.substring(externalPath.length)
        externalPath += subPath.substring(0, subPath.indexOf("/") + 1)
        var dirName = filePath.substring(externalPath.length)
        if (dirName.contains("/")) {
            dirName = dirName.substring(0, dirName.lastIndexOf("/"))
            if (dirName.contains("/")) {
                dirName = dirName.substring(dirName.lastIndexOf("/") + 1)
            }
        } else {
            dirName = ""
        }
        if (dirName.isEmpty()) {
            val file = File(externalPath + album)
            if (file.exists()) {
                return true
            }
        } else if (dirName.equals(album)) {
            return true
        }
        return false
    }

    /**
     * 清除所有歌曲及歌单-歌曲关联记录，保留歌单定义和应用配置。
     * DELETE FROM songs 会通过 ForeignKey.CASCADE 自动删除 playlist_songs 关联；
     * playlists 表（歌单定义）、DataStore（播放状态/歌词设置）、SharedPreferences（排序模式）不受影响。
     */
    suspend fun deleteAllSongsAndPlaylistAssociations() = withContext(Dispatchers.IO) {
        songDao.deleteAllSongs()
    }

    fun getAllSongs() = songDao.getAllSongs()

    /**
     * 同步判断数据库中是否已有歌曲，用于启动时决定是否跳过自动扫描。
     * 在 IO 调度器上执行，避免阻塞主线程。
     */
    suspend fun hasSongsInDb(): Boolean = withContext(Dispatchers.IO) {
        songDao.getSongIdsSync().isNotEmpty()
    }

    suspend fun getSongById(id: Long) = songDao.getSongById(id)

    /**
     * 按 MediaStore ID 查询文件路径（歌曲已不在库中时返回 null）
     */
    suspend fun getSongPath(songId: Long): String? = withContext(Dispatchers.IO) {
        songDao.getPathBySongIdSync(songId)
    }

    /**
     * 按 MediaStore ID 批量查询文件路径，用于迁移旧版按 ID 记录的隐藏歌曲。
     * 空集合直接返回，避免 Room 生成非法的 `IN ()`。
     */
    suspend fun getSongPaths(songIds: Collection<Long>): List<String> = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) emptyList() else songDao.getPathsByIds(songIds.toList())
    }

    fun searchSongs(query: String) = songDao.searchSongs("%$query%")

    /**
     * 判断文件路径是否落在用户配置的排除目录前缀下。
     * 用于外部文件播放时决定是否走临时模式（不保存进度、不入库）。
     */
    suspend fun isPathExcluded(path: String): Boolean = withContext(Dispatchers.IO) {
        val excluded = context.scanFiltersDataStore.data.first()[ScanFilterActivity.EXCLUDED_DIRS]
            ?: emptySet()
        excluded.any { path.startsWith(it) }
    }

    /**
     * 从外部 URI（文件管理器 ACTION_VIEW）构建 Song 对象。
     * 仅支持可解析为真实文件路径的 URI：
     * - content://media/...（MediaStore）：从 _ID 查 DATA 列得到真实路径
     * - file://...：直接取路径
     * 其他 URI（如 SAF document）无法解析真实路径时返回 null，由调用方提示用户。
     *
     * 是否走临时播放（不保存进度）由调用方用 [isPathExcluded] 动态判断，
     * 不持久化到 Song/数据库。
     */
    suspend fun buildSongFromExternalUri(uri: Uri): Song? = withContext(Dispatchers.IO) {
        val scheme = uri.scheme?.lowercase()
        var mediaStoreId: Long? = null
        var path: String? = null
        var mime: String? = null
        var albumId: Long? = null

        when (scheme) {
            "content" -> {
                // 1. MediaStore 音频 URI：content://media/external/audio/media/<id>
                val idFromUri = uri.lastPathSegment?.toLongOrNull()
                if (uri.authority == "media" && idFromUri != null) {
                    mediaStoreId = idFromUri
                    val projection = arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.ALBUM_ID,
                        MediaStore.Audio.Media.MIME_TYPE
                    )
                    val selection = "${MediaStore.Audio.Media._ID} = ?"
                    val selectionArgs = arrayOf(idFromUri.toString())
                    context.contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            path = cursor.getString(
                                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                            )
                            mime = cursor.getString(
                                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                            )
                            albumId = cursor.getLong(
                                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                            )
                        }
                    }
                } else {
                    // 2. 三方文件管理器 content URI（如 content://com.filemanager.files/...）：
                    // uri.path 经 URL 解码后即真实文件路径，直接尝试使用。
                    val rawPath = uri.path
                    if (rawPath != null) {
                        val decoded = Uri.decode(rawPath)
                        val f = File(decoded)
                        if (f.exists()) {
                            path = decoded
                        }
                    }
                }
            }

            "file" -> {
                path = uri.path
            }

            else -> return@withContext null
        }

        val realPath = path ?: return@withContext null
        val file = File(realPath)
        if (!file.exists()) return@withContext null

        // 元数据：优先 TagLib 读标签，失败回退到文件名
        val fileTags = TagLib.getMetadata(realPath)
        val title = fileTags["TITLE"]?.takeIf { it.isNotEmpty() } ?: file.nameWithoutExtension
        var artist = fileTags["ARTIST"]?.takeIf { it.isNotEmpty() } ?: "<unknown>"
        var album = fileTags["ALBUM"]?.takeIf { it.isNotEmpty() } ?: "<unknown>"

        if (isUnknownArtist(artist)) artist = "<unknown>"
        if (isUnknownAlbum(album, realPath)) album = "<unknown>"
        val genre = fileTags["GENRE"]?.takeIf { it.isNotEmpty() } ?: "<unknown>"

        // duration：MediaMetadataRetriever
        val duration = try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(realPath)
            val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            mmr.release()
            d
        } catch (e: Exception) {
            0L
        }

        val quality = classifyQuality(mime, file)
        val albumArtUri = albumId?.let {
            ContentUris.withAppendedId(
                "content://media/external/audio/albumart".toUri(),
                it
            ).toString()
        }
        val lastModified = file.lastModified()
        val id = mediaStoreId ?: -(realPath.hashCode().toLong().absoluteValue)

        Song(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            path = realPath,
            albumArt = albumArtUri,
            lastModified = lastModified,
            quality = quality,
            genre = genre
        )
    }

    /**
     * 永久模式下将外部歌曲写入数据库（不存在时插入）。
     * 命中相同路径的已存在记录则返回库内对象（保证 ID 一致、避免重复入库）；
     * 未命中则插入。
     */
    suspend fun ensureSongInDb(song: Song): Song = withContext(Dispatchers.IO) {
        val existing = songDao.getSongByPathSync(song.path)
        if (existing != null) {
            existing
        } else {
            songDao.insertSong(song)
            song
        }
    }

    private fun isFileSizeValid(path: String): Boolean {
        return try {
            val file = java.io.File(path)
            if (file.exists()) {
                val fileSizeInKB = file.length() / 1024
                fileSizeInKB >= 1024
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun computeBitrate(file: File): Int? {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val bitrateStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            mmr.release()
            bitrateStr?.toInt()?.takeIf { it > 0 }?.div(1024)
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 歌单（Playlist） ====================

    fun getAllPlaylists() = playlistDao.getAllPlaylists()

    suspend fun getPlaylistById(id: Long): Playlist? = withContext(Dispatchers.IO) {
        playlistDao.getPlaylistById(id).firstOrNull()
    }

    /**
     * 按歌单名精确查询是否已存在同名（大小写无关）。
     * @param excludeId 重命名时需排除当前歌单自身的 id（传入负数则不排除）
     */
    suspend fun isPlaylistNameUsed(name: String, excludeId: Long = -1L): Boolean =
        withContext(Dispatchers.IO) {
            playlistDao.countByName(name, excludeId) > 0
        }

    suspend fun createPlaylist(name: String, coverPath: String = ""): Long =
        withContext(Dispatchers.IO) {
            playlistDao.insertPlaylist(Playlist(name = name, coverPath = coverPath))
        }

    /**
     * 按歌单名查询歌单（大小写无关），找不到返回 null
     */
    suspend fun findPlaylistByName(name: String): Playlist? = withContext(Dispatchers.IO) {
        playlistDao.findPlaylistByName(name)
    }

    /**
     * 刷新歌单更新时间
     */
    suspend fun touchPlaylist(id: Long) = withContext(Dispatchers.IO) {
        playlistDao.touchPlaylist(id, System.currentTimeMillis())
    }

    /**
     * 按文件路径批量查询歌曲；路径按每批 900 个分片查询，规避 SQLite 参数上限
     */
    suspend fun getSongsByPaths(paths: List<String>): List<Song> = withContext(Dispatchers.IO) {
        val distinct = paths.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return@withContext emptyList()
        val result = mutableListOf<Song>()
        distinct.chunked(900).forEach { batch ->
            result.addAll(songDao.getSongsByPaths(batch))
        }
        result
    }

    suspend fun renamePlaylist(id: Long, name: String, coverPath: String? = null) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (coverPath != null) {
                playlistDao.updatePlaylistNameAndCover(id, name, coverPath, now)
            } else {
                playlistDao.updatePlaylistName(id, name, now)
            }
        }

    // ==================== 封面（歌手/专辑/歌单图片） ====================

    fun getAllCovers() = coverDao.getAllCovers()

    suspend fun upsertCover(cover: Cover) = withContext(Dispatchers.IO) {
        coverDao.upsertCover(cover)
    }

    /**
     * 恢复默认图片：删除歌手/专辑的封面记录并删除其副本文件。
     * covers Flow 会自动驱动列表回退为默认图标。
     */
    suspend fun deleteCover(type: Int, name: String, coverPath: String) =
        withContext(Dispatchers.IO) {
            coverDao.deleteCover(type, name)
            if (coverPath.isNotEmpty()) {
                runCatching { File(coverPath).delete() }
            }
        }

    /**
     * 应用"修改封面"的结果：把私有目录中的方形副本路径写入 albumArt；
     * coverPath 为 null 表示恢复默认，此时取回媒体库记录的专辑封面 URI。
     * 图片只保存在应用数据里，不改动音频文件本身。
     */
    suspend fun applySongCover(song: Song, coverPath: String?): Song =
        withContext(Dispatchers.IO) {
            // 外部文件以临时方式播放时手上的 id 未必是库里那一行，按路径找回再写
            val row = songDao.getSongByPathSync(song.path) ?: song
            val albumArt = coverPath ?: systemAlbumArt(row.id)
            songDao.updateSong(row.copy(albumArt = albumArt))
            // 交给界面用记录时保持手上的 id，播放队列是按 id 认歌的
            song.copy(albumArt = albumArt)
        }

    /** 媒体库记录的该歌曲所属专辑的封面 URI；歌曲已不在媒体库时返回 null */
    private fun systemAlbumArt(songId: Long): String? {
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.ALBUM_ID),
            "${MediaStore.Audio.Media._ID} = ?",
            arrayOf(songId.toString()),
            null
        ) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return null
            val albumId = it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID))
            ContentUris.withAppendedId(
                "content://media/external/audio/albumart".toUri(),
                albumId
            ).toString()
        }
    }

    /**
     * 清理悬空封面记录：数据库记录的图片文件已不存在时，删除记录并尝试清理残留文件。
     * 在封面加载完成后调用，保证列表回退为默认图标。
     */
    suspend fun cleanupMissingCovers() = withContext(Dispatchers.IO) {
        var removed = false
        coverDao.getAllCovers().first().forEach { cover ->
            if (!File(cover.coverPath).exists()) {
                coverDao.deleteCover(cover.type, cover.name)
                removed = true
            }
        }
        playlistDao.getAllPlaylists().first().forEach { playlist ->
            val path = playlist.coverPath
            if (path.isNotEmpty() && !File(path).exists()) {
                playlistDao.updatePlaylistCoverPath(playlist.id, "")
                runCatching { File(path).delete() }
                removed = true
            }
        }
        removed
    }

    suspend fun deletePlaylistById(id: Long) = withContext(Dispatchers.IO) {
        // 先清关联表（CASCADE 也会删，但显式清更稳妥），再删歌单
        playlistDao.clearPlaylist(id)
        // 需要完整 Playlist 对象供 @Delete，此处先取再删
        val pl = getPlaylistById(id) ?: return@withContext
        playlistDao.deletePlaylist(pl)
        // 删除歌单封面副本及同组残留候选图
        if (pl.coverPath.isNotEmpty()) {
            runCatching { File(pl.coverPath).delete() }
            CoverStore.cleanupPlaylistCoverGroup(context, id, keepPath = "")
        }
        // 同步删除该歌单的导出文件（无授权/文件不存在时静默跳过）
        PlaylistFileManager.deleteExport(context, id)
    }

    fun getPlaylistSongIds(playlistId: Long) = playlistDao.getPlaylistSongIds(playlistId)

    fun getPlaylistSongs(playlistId: Long) = playlistDao.getPlaylistSongs(playlistId)

    suspend fun addSongsToPlaylist(playlistId: Long, songs: List<Song>) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        playlistDao.addSongsToPlaylist(songs.map { PlaylistSong(playlistId, it.id) })
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        playlistDao.removeSongFromPlaylist(PlaylistSong(playlistId, songId))
    }

    fun classifyQuality(mime: String?, file: File): String {
        // 1. 无损格式
        if (mime != null && mime in setOf(
                "audio/flac", "audio/x-wav", "audio/alac", "audio/x-ape", "audio/dsd"
            )
        ) {
            return "SQ"
        }
        // 计算比特率（kbps）
        val bitrateKbps: Int? = computeBitrate(file)
        if (bitrateKbps == null || bitrateKbps <= 0) {
            return "UNK"
        }
        val isAacOrOgg = mime in setOf("audio/aac", "audio/mp4a-latm", "audio/ogg", "audio/vorbis")
        return when {
            // 高品：MP3≥320 或 AAC/OGG≥256
            bitrateKbps >= 320 || (isAacOrOgg && bitrateKbps >= 256) -> "HQ"
            bitrateKbps >= 128 -> "STD"
            else -> "ORD"
        }
    }
}