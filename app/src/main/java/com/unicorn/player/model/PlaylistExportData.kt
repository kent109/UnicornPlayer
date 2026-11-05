package com.unicorn.player.model

/**
 * 歌单导出文件的数据模型（Gson 序列化，version 1）
 *
 * - [songs] 仅保存歌曲文件的绝对路径字符串，不保存 songId（MediaStore _ID，
 *   重新拷入会变）、标题、歌手、时长（用户可在界面修改标签导致过时）；
 *   导入时一律按路径查库获取最新歌曲信息
 * - [songs] 数组的先后次序就是歌单自身的顺序：导出按界面上的顺序写入，
 *   导入时把它还原成该歌单的自定义顺序
 */
data class PlaylistExportData(
    val version: Int = 1,
    val playlistId: Long = 0,
    val playlistName: String = "",
    val exportedAt: Long = 0,
    val songs: List<String> = emptyList()
)
