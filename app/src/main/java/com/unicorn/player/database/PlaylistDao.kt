package com.unicorn.player.database

import androidx.room.*
import com.unicorn.player.model.Playlist
import com.unicorn.player.model.PlaylistSong
import com.unicorn.player.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun getPlaylistById(id: Long): Flow<Playlist?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylist(playlist: Playlist): Long

    @Update
    fun updatePlaylist(playlist: Playlist): Int

    @Delete
    fun deletePlaylist(playlist: Playlist): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addSongToPlaylist(playlistSong: PlaylistSong)

    @Delete
    fun removeSongFromPlaylist(playlistSong: PlaylistSong): Int

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    fun clearPlaylist(playlistId: Long): Int

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId
        ORDER BY s.title ASC
    """)
    fun getPlaylistSongs(playlistId: Long): Flow<List<Song>>

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId AND (
            s.title LIKE :query OR
            s.artist LIKE :query OR
            s.album LIKE :query
        )
        ORDER BY s.title ASC
    """)
    fun searchPlaylistSongs(playlistId: Long, query: String): Flow<List<Song>>
}