package com.unicorn.player.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.net.Uri
import com.unicorn.player.model.Song
import com.unicorn.player.database.MusicDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicRepository(private val context: Context) {

    private val database = MusicDatabase.getDatabase(context)
    private val songDao = database.songDao()

    suspend fun scanMusicFiles(): List<Song> = withContext(Dispatchers.IO) {
        val newSongs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown Title"
                val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                val album = cursor.getString(albumColumn) ?: "Unknown Album"
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(pathColumn)
                val albumId = cursor.getLong(albumIdColumn)

                // 过滤掉小于1MB的音频文件
                if (!isFileSizeValid(path)) {
                    continue
                }

                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                ).toString()

                val file = java.io.File(path)
                val lastModified = if (file.exists()) file.lastModified() else 0L

                val song = Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    path = path,
                    albumArt = albumArtUri,
                    lastModified = lastModified
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
}