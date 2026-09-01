package com.unicorn.player.model

/**
 * 歌手模型，用于歌手标签页列表展示
 * @param name 歌手名
 * @param songCount 该歌手的歌曲数量
 * @param songIds 该歌手所有歌曲的id
 */
data class Artist(
    val name: String,
    val songCount: Int,
    val songIds: Set<Long> = mutableSetOf<Long>()
)
