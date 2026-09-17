package com.unicorn.player.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.core.net.toUri
import com.unicorn.player.database.MusicDatabase
import com.unicorn.player.model.Playlist
import com.unicorn.player.model.PlaylistSong
import com.unicorn.player.model.ScanFilterConfig
import com.unicorn.player.model.Song
import com.unicorn.player.util.PlaylistFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.internal.immutableListOf
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File
import java.util.Locale


class MusicRepository(private val context: Context) {

    private val database = MusicDatabase.getDatabase(context)
    private val songDao = database.songDao()
    private val playlistDao = database.playlistDao()

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
            MediaStore.Audio.Media.MIME_TYPE
        )

        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0" +
                " AND " + MediaStore.Audio.Media.DATA + " NOT LIKE '%/music/Recordings/%'" +
                " AND " + MediaStore.Audio.Media.DATA + " NOT LIKE '%/allsaintsMusic/%'" +
                " AND " + MediaStore.Audio.Media.DATA + " NOT LIKE '%/msc/%'"

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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                var title = cursor.getString(titleColumn) ?: "Unknown Title"
                var artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                var album = cursor.getString(albumColumn) ?: "Unknown Album"
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(pathColumn)
                val albumId = cursor.getLong(albumIdColumn)
                val mime = cursor.getString(mimeColumn)

                // WAV 文件需要从文件直接读取 ID3v2 标签覆盖 MediaStore 的值。
                // 原因：JAudioTagger 写入 WAV 的标签是 ID3v2 chunk，而 Android MediaStore
                // 的 WAV 解析器只读取 RIFF INFO / BWF 块，不解析 ID3v2 chunk。
                // 导致用户用 App 改完标签后，一旦清除应用数据（Room 缓存被清），
                // 应用重新从 MediaStore 读取时仍返回旧值，修改丢失。
                // 此处对 WAV 在扫描时直接读文件标签，保证修改过的元数据可恢复。
                if (isWavFile(mime, path)) {
                    val fileTags = readWavTagsFromFile(path)
                    fileTags.first?.let { title = it }
                    fileTags.second?.let { artist = it }
                    fileTags.third?.let { album = it }
                }

                if (isUnknownArtist(artist)) {
                    artist = "<unknown>"
                }
                if (isUnknownAlbum(album, path)) {
                    album = "<unknown>"
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

                val song = Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    path = path,
                    albumArt = albumArtUri,
                    lastModified = lastModified,
                    quality = quality
                )
                newSongs.add(song)
            }
        }

        // 2. 获取数据库中当前的歌曲ID列表
        val existingSongIds = try {
            songDao.getSongIdsSync()
        } catch (e: Exception) {
            emptyList()
        }

        // 3. 计算需要删除的歌曲（在数据库中但不在新扫描结果中）
        val newSongIds = newSongs.map { it.id }
        val songsToDelete = existingSongIds.filter { it !in newSongIds }

        // 4. 删除不再存在的歌曲
        if (songsToDelete.isNotEmpty()) {
            songDao.deleteSongsByIds(songsToDelete)
        }

        // 5. 插入新歌曲（使用 IGNORE 策略，跳过已存在的）
        // 注意：不能用 REPLACE，因为 SQLite 的 REPLACE = DELETE + INSERT，
        // 会触发 playlist_songs 的 ForeignKey.CASCADE 误删已有歌单关联。
        // 已存在的歌曲无需更新（元数据不变），只需插入新增的即可。
        if (newSongs.isNotEmpty()) {
            songDao.insertSongsIgnoreExisting(newSongs)
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
     * 判断是否为 WAV 文件（基于 MIME 或扩展名）。
     */
    private fun isWavFile(mime: String?, path: String): Boolean {
        if (mime.equals("audio/x-wav", ignoreCase = true) ||
            mime.equals("audio/wav", ignoreCase = true) ||
            mime.equals("audio/wave", ignoreCase = true)
        ) {
            return true
        }
        val ext = path.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return ext == "wav"
    }

    /**
     * 使用 JAudioTagger 从 WAV 文件直接读取 ID3v2 标签。
     *
     * 背景：MediaStore 的 WAV 解析器只读 RIFF INFO / BWF 块，不解析 ID3v2 chunk，
     * 而 AudioTagEditor 通过 JAudioTagger 写入 WAV 的标签正是 ID3v2 chunk。
     * 因此扫描时必须直接读文件，否则修改过的标签在清除应用数据后会丢失
     * （Room 缓存被清，MediaStore 又读不到 ID3v2）。
     *
     * @return Triple<title, artist, album>，任一字段读不到或异常时为 null。
     */
    private fun readWavTagsFromFile(path: String): Triple<String?, String?, String?> {
        return try {
            val audioFile = AudioFileIO.read(File(path))
            val tag: Tag? = audioFile.tag
            if (tag == null) {
                Triple(null, null, null)
            } else {
                Triple(
                    tag.getFirst(FieldKey.TITLE).ifEmpty { null },
                    tag.getFirst(FieldKey.ARTIST).ifEmpty { null },
                    tag.getFirst(FieldKey.ALBUM).ifEmpty { null }
                )
            }
        } catch (_: Exception) {
            Triple(null, null, null)
        }
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

    fun searchSongs(query: String) = songDao.searchSongs("%$query%")

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

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        playlistDao.insertPlaylist(Playlist(name = name))
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

    suspend fun renamePlaylist(id: Long, name: String) = withContext(Dispatchers.IO) {
        playlistDao.updatePlaylistName(id, name, System.currentTimeMillis())
    }

    suspend fun deletePlaylistById(id: Long) = withContext(Dispatchers.IO) {
        // 先清关联表（CASCADE 也会删，但显式清更稳妥），再删歌单
        playlistDao.clearPlaylist(id)
        // 需要完整 Playlist 对象供 @Delete，此处先取再删
        val pl = getPlaylistById(id) ?: return@withContext
        playlistDao.deletePlaylist(pl)
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