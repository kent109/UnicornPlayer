package com.unicorn.player.model

/**
 * 歌词搜索结果数据类
 * 对应 lrclib.net 返回的 JSON 对象中用于展示的字段
 */
data class LrcSearchResult(
    /** 歌词id */
    val id: Long,
    /** 歌曲名 */
    val trackName: String,
    /** 艺术家 */
    val artistName: String,
    /** 专辑名 */
    val albumName: String,
    /** 时长（秒） */
    val duration: Double,
    /** 同步歌词内容（LRC 格式） */
    val syncedLyrics: String
)
