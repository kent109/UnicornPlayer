package com.unicorn.player.service

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.media3.exoplayer.ExoPlayer

/**
 * 播放进度轮询器：1 秒一次更新 LiveData 进度 + MediaSession playback state。
 *
 * 所有 Service 内部依赖通过构造闭包注入——零 MusicService 字段直接访问。
 * Service 保留 startPositionUpdates() / stopPositionUpdates() 方法签名不变。
 */
internal class PositionUpdater(
    private val getPlayer: () -> ExoPlayer,
    private val currentPositionLive: MutableLiveData<Int>,
    private val updateMediaSessionPlaybackState: () -> Unit,
    private val isPlayerReleased: () -> Boolean,
    private val intervalMs: Long = 1000L,
) {
    private val handler = Handler(Looper.getMainLooper())

    private val runnable = object : Runnable {
        override fun run() {
            val player = getPlayer()
            if (!isPlayerReleased() && player.isPlaying) {
                currentPositionLive.value = player.currentPosition.toInt()
                updateMediaSessionPlaybackState()
            }
            if (!isPlayerReleased()) {
                handler.postDelayed(this, intervalMs)
            }
        }
    }

    fun start() = handler.post(runnable)
    fun stop() = handler.removeCallbacks(runnable)
}
