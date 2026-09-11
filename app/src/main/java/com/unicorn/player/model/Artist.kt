package com.unicorn.player.model

/**
 * 歌手模型，用于歌手标签页列表展示
 * @param name 歌手名
 * @param pinyinName 歌手名(转换为拼音)
 * @param songCount 该歌手的歌曲数量
 * @param songList 该歌手的歌曲列表
 */
data class Artist(
    val name: String,
    val pinyinName: String,
    var songCount: Int,
    val songList: MutableList<Song> = mutableListOf()
)
