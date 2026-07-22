package com.unicorn.player.model

/**
 * 专辑模型，用于专辑标签页列表展示
 * @param name 专辑名
 * @param artist 该专辑下数量最多的歌手名（用于副标题显示）
 * @param songCount 该专辑的歌曲数量
 * @param firstLetter 专辑名首字母分组（A-Z / #），由 ViewModel 后台计算后携带，避免 Fragment 重复算拼音
 */
data class Album(
    val name: String,
    val artist: String,
    val songCount: Int,
    val firstLetter: String = ""
)
