package com.unicorn.player.database

import androidx.room.*
import com.unicorn.player.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongs(): Flow<List<Song>>

    @Query("SELECT id FROM songs")
    fun getSongIdsSync(): List<Long>

    @Query("SELECT * FROM songs WHERE id = :songId")
    fun getSongById(songId: Long): Flow<Song?>

    @Query("SELECT * FROM songs WHERE title LIKE :query OR artist LIKE :query OR album LIKE :query")
    fun searchSongs(query: String): Flow<List<Song>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSong(song: Song): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSongs(songs: List<Song>): LongArray

    @Delete
    fun deleteSong(song: Song): Int

    @Query("DELETE FROM songs")
    fun deleteAllSongs(): Int

    @Query("DELETE FROM songs WHERE id IN (:songIds)")
    fun deleteSongsByIds(songIds: List<Long>)
}