package com.unicorn.player.model

/**
 * 专辑模型，用于专辑标签页列表展示
 * @param name 专辑名
 * @param artist 该专辑下数量最多的歌手名（用于副标题显示）
 * @param songCount 该专辑的歌曲数量
 */
data class Album(
    val name: String,
    val artist: String,
    val songCount: Int
)
