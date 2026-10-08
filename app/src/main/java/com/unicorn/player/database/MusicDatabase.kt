package com.unicorn.player.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.unicorn.player.model.Song
import com.unicorn.player.model.Playlist
import com.unicorn.player.model.PlaylistSong
import com.unicorn.player.model.Cover

@Database(
    entities = [Song::class, Playlist::class, PlaylistSong::class, Cover::class],
    version = 5,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun coverDao(): CoverDao

    companion object {
        @Volatile
        private var INSTANCE: MusicDatabase? = null

        /**
         * 版本 3 → 4：playlists 表新增 icon、updatedAt 列
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE playlists ADD COLUMN icon TEXT NOT NULL DEFAULT 'ic_playlist'")
                database.execSQL("ALTER TABLE playlists ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 版本 4 → 5：playlists 表新增 coverPath 列；新增 covers 表（歌手/专辑自定义封面）
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE playlists ADD COLUMN coverPath TEXT NOT NULL DEFAULT ''")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `covers` (" +
                        "`type` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                        "`coverPath` TEXT NOT NULL, PRIMARY KEY(`type`, `name`))"
                )
            }
        }

        fun getDatabase(context: Context): MusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5) // 增量迁移，不清库
                    .fallbackToDestructiveMigration() // 允许破坏性迁移（兜底）
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}