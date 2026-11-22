package com.unicorn.player.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * 音频焦点管理器：请求/释放焦点，其他应用抢占时暂停，失焦前正在播放则焦点恢复后续播。
 *
 * 零闭包依赖 Service 字段：所有状态通过构造参数传入。
 * Service 内部的 requestAudioFocusAndPlay*() 业务方法（包含切歌逻辑）保留在 Service 内部。
 */
internal class AudioFocusManager(
    private val audioManager: AudioManager,
    private val getIsPlaying: () -> Boolean,
    private val getPlayWhenReady: () -> Boolean,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val logDiag: (String) -> Unit,
) {
    /** 焦点丢失前是否在播放——重新获得焦点时决定是否自动续播 */
    private var wasPlayingBeforeFocusLoss = false

    private var audioFocusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        logDiag(
            "audioFocusChanged: what=$focusChange, wasPlayingBeforeFocusLoss=$wasPlayingBeforeFocusLoss, " +
                    "isPlaying=${getIsPlaying()}, playWhenReady=${getPlayWhenReady()}"
        )
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 重新获得焦点：仅在焦点丢失前正在播放时恢复播放
                if (wasPlayingBeforeFocusLoss) {
                    wasPlayingBeforeFocusLoss = false
                    onPlay()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS -> {
                // 其他应用抢占焦点（短暂或永久）：暂停播放并记录之前是否在播放
                wasPlayingBeforeFocusLoss = getIsPlaying()
                onPause()
            }
        }
    }

    /** 播放前请求音频焦点（handleAudioFocus=false，由本类手动管理） */
    fun requestAudioFocus(): Int {
        if (audioFocusRequest == null) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
        }
        return audioManager.requestAudioFocus(audioFocusRequest!!)
    }

    /** 释放焦点（Service.onDestroy 时调用） */
    fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        wasPlayingBeforeFocusLoss = false
    }

    companion object {
        /** 便捷工厂：从 Context 取 AudioManager */
        fun fromContext(
            context: Context,
            getIsPlaying: () -> Boolean,
            getPlayWhenReady: () -> Boolean,
            onPlay: () -> Unit,
            onPause: () -> Unit,
            logDiag: (String) -> Unit,
        ) = AudioFocusManager(
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            getIsPlaying, getPlayWhenReady, onPlay, onPause, logDiag
        )
    }
}
