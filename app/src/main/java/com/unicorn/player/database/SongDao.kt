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

    /**
     * 同步查询全部歌曲（扫描时按 ID 比对路径变化，识别外部改名/移动）
     */
    @Query("SELECT * FROM songs")
    fun getAllSongsSync(): List<Song>

    /**
     * 同步查询全部歌曲路径（用于冷启动时按扩展名预检设备解码能力）
     */
    @Query("SELECT path FROM songs")
    fun getAllPathsSync(): List<String>

    @Query("SELECT * FROM songs WHERE id = :songId")
    fun getSongById(songId: Long): Flow<Song?>

    /**
     * 同步按 ID 查询单首歌曲（用于恢复"上次保存进度的歌曲"等场景，避开 Flow）
     */
    @Query("SELECT * FROM songs WHERE id = :songId")
    fun getSongByIdSync(songId: Long): Song?

    /**
     * 按 ID 查询文件路径（用于把按 MediaStore ID 传入的删除操作映射为按路径记录）
     */
    @Query("SELECT path FROM songs WHERE id = :songId")
    fun getPathBySongIdSync(songId: Long): String?

    /**
     * 按 ID 批量查询文件路径（用于把旧版按 ID 记录的隐藏歌曲迁移为按路径记录）
     */
    @Query("SELECT path FROM songs WHERE id IN (:songIds)")
    fun getPathsByIds(songIds: List<Long>): List<String>

    /**
     * 同步按文件路径查询单首歌曲（用于外部入库去重）
     */
    @Query("SELECT * FROM songs WHERE path = :path")
    fun getSongByPathSync(path: String): Song?

    @Query("SELECT * FROM songs WHERE title LIKE :query OR artist LIKE :query OR album LIKE :query")
    fun searchSongs(query: String): Flow<List<Song>>

    /**
     * 按文件路径批量查询歌曲（用于歌单导出/导入的路径匹配）
     */
    @Query("SELECT * FROM songs WHERE path IN (:paths)")
    fun getSongsByPaths(paths: List<String>): List<Song>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSong(song: Song): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSongs(songs: List<Song>): LongArray

    /**
     * 仅插入不存在的新歌曲（IGNORE 策略），避免 REPLACE 触发 DELETE CASCADE 误删 playlist_songs 关联
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSongsIgnoreExisting(songs: List<Song>): LongArray

    @Update
    fun updateSong(song: Song)

    /**
     * 批量更新歌曲（扫描时刷新 ID 不变但路径已变的记录，如同 inode 改名）
     */
    @Update
    fun updateSongs(songs: List<Song>)

    @Delete
    fun deleteSong(song: Song): Int

    @Query("DELETE FROM songs")
    fun deleteAllSongs(): Int

    @Query("DELETE FROM songs WHERE id IN (:songIds)")
    fun deleteSongsByIds(songIds: List<Long>)
}