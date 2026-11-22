package com.unicorn.player.service

import android.support.v4.media.session.MediaSessionCompat

/**
 * MusicService MediaSessionCompat.Callback 实现。
 *
 * 所有 Service 内部依赖通过构造闭包注入——零 MusicService 字段直接访问。
 */
internal class MusicServiceMediaSessionCallback(
    private val getIsPlayableError: () -> Boolean?,
    private val requestAudioFocusAndPlay: () -> Unit,
    private val requestAudioFocusAndPlayNext: () -> Unit,
    private val requestAudioFocusAndPlayPrevious: () -> Unit,
    private val doPause: () -> Unit,
    private val doSeekTo: (Int) -> Unit,
    private val stopSelfDiag: (String) -> Unit,
    private val updateMediaSessionPlaybackState: () -> Unit,
) : MediaSessionCompat.Callback() {

    override fun onPlay() {
        // 当前歌曲格式不支持时处于错误暂停态，蓝牙耳机的单击会被系统路由到 onPlay()
        // 而非 onSkipToNext()，此时自动跳下一首，避免卡在错误歌曲上
        if (getIsPlayableError() == true) {
            requestAudioFocusAndPlayNext()
        } else {
            requestAudioFocusAndPlay()
        }
    }

    override fun onPause() = doPause()

    override fun onSkipToNext() = requestAudioFocusAndPlayNext()

    override fun onSkipToPrevious() = requestAudioFocusAndPlayPrevious()

    override fun onStop() = stopSelfDiag("mediaSession.onStop")

    override fun onSeekTo(pos: Long) {
        // 处理通知栏进度条拖动事件
        doSeekTo(pos.toInt())
        updateMediaSessionPlaybackState()
    }
}
