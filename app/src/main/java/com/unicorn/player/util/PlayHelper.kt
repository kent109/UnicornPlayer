package com.unicorn.player.util

import android.content.Context
import android.widget.Toast
import com.unicorn.player.model.Song
import com.unicorn.player.service.MusicService
import com.unicorn.player.service.PlaySource

/**
 * 播放辅助工具类
 * 封装通用的播放逻辑，减少重复代码
 */
object PlayHelper {
    private const val TAG = "PlayHelper"

    /**
     * 播放歌曲列表
     * @param context 上下文
     * @param service MusicService 实例
     * @param songs 要播放的歌曲列表
     * @param playSource 播放来源类型 (PlaySource.ALBUM/ARTIST/PLAYLIST)
     * @param sourceName 来源名称 (专辑名/歌手名/歌单名)
     * @param emptyMessage 空列表提示消息
     */
    private fun playSongs(
        context: Context,
        service: MusicService?,
        songs: List<Song>,
        playSource: String,
        sourceName: String,
        emptyMessage: String = "${sourceName}无歌曲"
    ): Boolean {
        if (service == null) {
            Toast.makeText(context, "音乐服务没有运行", Toast.LENGTH_SHORT).show()
            return false
        }

        if (songs.isEmpty()) {
            LogWriter.writeError(TAG, "$sourceName 歌曲列表为空")
            Toast.makeText(context, emptyMessage, Toast.LENGTH_SHORT).show()
            return false
        }

        // 检查当前播放的歌曲是否在集合内
        val currentSongId = service.currentSong.value?.id
        val isCurrentSongInPlaylist = currentSongId != null && songs.any { it.id == currentSongId }
        val isCurrentlyPlaying = service.isPlaying.value ?: false

        // 无论什么情况，都要先更新播放来源以触发 UI 高亮
        service.setPlaySource(PlaySource.build(playSource, sourceName))

        try {
            // 如果当前正在播放的歌曲就在这个集合中
            if (isCurrentSongInPlaylist) {
                // 更新播放列表，但保持当前歌曲的播放位置
                val currentSongIndex = songs.indexOfFirst { it.id == currentSongId }
                service.setSongList(songs, if (currentSongIndex >= 0) currentSongIndex else 0)

                if (!isCurrentlyPlaying) {
                    // 之前暂停了，继续从当前进度播放
                    service.play()
                }
                // 如果正在播放，不需要额外操作，因为 setSongList 不会改变当前播放状态
            } else {
                // 当前歌曲不在集合中，设置新列表并播放第一首歌
                service.setSongList(songs, 0)
                service.requestAudioFocusAndPlayCurrentSong()
            }
            service.updateNotification()
            return true
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "播放失败：${e.message}", e)
            Toast.makeText(context, "播放失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
        return false
    }

    /**
     * 播放专辑歌曲
     * @param context 上下文
     * @param service MusicService 实例
     * @param albumName 专辑名称
     * @param songs 专辑歌曲列表
     */
    fun playAlbum(
        context: Context,
        service: MusicService?,
        albumName: String,
        songs: List<Song>
    ): Boolean {
        return playSongs(
            context = context,
            service = service,
            songs = songs,
            playSource = PlaySource.ALBUM,
            sourceName = albumName,
            emptyMessage = "专辑无歌曲"
        )
    }

    /**
     * 播放歌手歌曲
     * @param context 上下文
     * @param service MusicService 实例
     * @param artistName 歌手名称
     * @param songs 歌手歌曲列表
     */
    fun playArtist(
        context: Context,
        service: MusicService?,
        artistName: String,
        songs: List<Song>
    ): Boolean {
        return playSongs(
            context = context,
            service = service,
            songs = songs,
            playSource = PlaySource.ARTIST,
            sourceName = artistName,
            emptyMessage = "歌手无歌曲"
        )
    }

    /**
     * 播放歌单歌曲
     * @param context 上下文
     * @param service MusicService 实例
     * @param playlistName 歌单名称
     * @param songs 歌单歌曲列表
     */
    fun playPlaylist(
        context: Context,
        service: MusicService?,
        playlistName: String,
        songs: List<Song>
    ): Boolean {
        return playSongs(
            context = context,
            service = service,
            songs = songs,
            playSource = PlaySource.PLAYLIST,
            sourceName = playlistName,
            emptyMessage = "歌单无歌曲"
        )
    }
}
