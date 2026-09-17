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

    /**
     * 按歌单名查询歌单（大小写无关），用于歌单导入时的同名合并判断
     */
    @Query("SELECT * FROM playlists WHERE name = :name COLLATE NOCASE LIMIT 1")
    fun findPlaylistByName(name: String): Playlist?

    /**
     * 刷新歌单更新时间（导入合并新增歌曲后调用）
     */
    @Query("UPDATE playlists SET updatedAt = :time WHERE id = :id")
    fun touchPlaylist(id: Long, time: Long): Int

    /**
     * 按歌单名精确计数（大小写无关），用于新建/重命名时校验同名。
     * 重命名场景需排除当前自身的 id（调用方传入 [excludeId]）。
     */
    // COLLATE NOCASE 让 "我的歌单" 和 "我的歌单"（大小写差异）也视为同名
    @Query(
        "SELECT COUNT(*) FROM playlists " +
            "WHERE name = :name COLLATE NOCASE AND (:excludeId < 0 OR id != :excludeId)"
    )
    fun countByName(name: String, excludeId: Long = -1L): Int

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

    // ==================== 批量操作 / 辅助查询 ====================

    /**
     * 批量向歌单合并添加歌曲（REPLACE 去重，ON DUPLICATE KEY UPDATE 语义）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addSongsToPlaylist(songs: List<PlaylistSong>)

    /**
     * 查询歌单内已存在的 songId 列表（用于弹窗预勾选）
     */
    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId")
    fun getPlaylistSongIds(playlistId: Long): Flow<List<Long>>

    /**
     * 重命名歌单并刷新更新时间
     */
    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    fun updatePlaylistName(id: Long, name: String, updatedAt: Long): Int
}