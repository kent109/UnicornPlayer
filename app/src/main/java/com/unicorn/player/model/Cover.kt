package com.unicorn.player.model

import androidx.room.Entity

/**
 * 歌手/专辑自定义封面图片，按 (type, name) 关联到分组键（lowercase 名）。
 * @param type 封面类型，见 [TYPE_ARTIST] / [TYPE_ALBUM]
 * @param name 分组键：歌手/专辑名的 lowercase 形式
 * @param coverPath 应用私有目录下的图片副本绝对路径
 */
@Entity(tableName = "covers", primaryKeys = ["type", "name"])
data class Cover(
    val type: Int,
    val name: String,
    val coverPath: String
) {
    companion object {
        const val TYPE_ARTIST = 0
        const val TYPE_ALBUM = 1
    }
}
