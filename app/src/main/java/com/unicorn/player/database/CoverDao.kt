package com.unicorn.player.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unicorn.player.model.Cover
import kotlinx.coroutines.flow.Flow

@Dao
interface CoverDao {

    @Query("SELECT * FROM covers")
    fun getAllCovers(): Flow<List<Cover>>

    @Query("SELECT * FROM covers WHERE type = :type")
    fun getCoversByType(type: Int): Flow<List<Cover>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertCover(cover: Cover)

    @Query("DELETE FROM covers WHERE type = :type AND name = :name")
    fun deleteCover(type: Int, name: String)
}
