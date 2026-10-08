package com.unicorn.player.model

/**
 * 歌手模型，用于歌手标签页列表展示
 * @param name 歌手名
 * @param pinyinName 歌手名(转换为拼音)
 * @param songCount 该歌手的歌曲数量
 * @param songList 该歌手的歌曲列表
 * @param coverPath 用户自定义头像的本地副本路径，空串表示使用默认图标
 */
data class Artist(
    val name: String,
    val pinyinName: String,
    var songCount: Int,
    val songList: MutableList<Song> = mutableListOf(),
    val coverPath: String = ""
)
