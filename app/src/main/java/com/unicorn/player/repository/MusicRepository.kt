package com.unicorn.player.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.core.net.toUri
import com.unicorn.player.database.MusicDatabase
import com.unicorn.player.model.Playlist
import com.unicorn.player.model.PlaylistSong
import com.unicorn.player.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File


class MusicRepository(private val context: Context) {

    private val database = MusicDatabase.getDatabase(context)
    private val songDao = database.songDao()
    private val playlistDao = database.playlistDao()

    suspend fun scanMusicFiles(): List<Song> = withContext(Dispatchers.IO) {
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
                val title = cursor.getString(titleColumn) ?: "Unknown Title"
                val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                val album = cursor.getString(albumColumn) ?: "Unknown Album"
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(pathColumn)
                val albumId = cursor.getLong(albumIdColumn)
                val mime = cursor.getString(mimeColumn)

                // 过滤掉小于1MB的音频文件
                val file = java.io.File(path)
                if (!file.exists() || file.length() / 1024 < 1024) {
                    continue
                }

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

        // 5. 插入新歌曲（使用 REPLACE 策略，已存在的会更新）
        if (newSongs.isNotEmpty()) {
            songDao.insertSongs(newSongs)
        }

        newSongs
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

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        playlistDao.insertPlaylist(Playlist(name = name))
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
    }

    fun getPlaylistSongIds(playlistId: Long) = playlistDao.getPlaylistSongIds(playlistId)

    fun getPlaylistSongs(playlistId: Long) = playlistDao.getPlaylistSongs(playlistId)

    suspend fun addSongsToPlaylist(playlistId: Long, songs: List<Song>) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        playlistDao.addSongsToPlaylist(songs.map { PlaylistSong(playlistId, it.id) })
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