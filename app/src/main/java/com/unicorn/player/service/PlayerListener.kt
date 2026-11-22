package com.unicorn.player.service

import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.unicorn.player.model.Song
import com.unicorn.player.playback.SongPlayableRegistry
import com.unicorn.player.util.LogWriter

/**
 * ExoPlayer Player.Listener 实现，处理播放状态转换、播放/暂停切换、播放器错误。
 *
 * 所有 Service 内部依赖通过闭包传入——零 MusicService 字段直接访问。
 * 调用方 MusicService 在 onCreate 里构造：
 * ```kotlin
 * player.addListener(PlayerListener(
 *     context = this,
 *     tag = TAG,
 *     player = { player },
 *     currentSong = { _currentSong.value },
 *     currentPosition = { _currentPosition },
 *     currentIsPlaying = { _isPlaying },
 *     currentIsPlayableError = { _isPlayableError },
 *     currentIndex = { currentIndex },
 *     songList = { songList },
 *     playMode = { playMode },
 *     // ... 业务闭包
 * ))
 * ```
 */
internal class PlayerListener(
    private val context: android.content.Context,
    private val tag: String,
    // —— 状态只读闭包（从 Service 读） ——
    private val player: () -> androidx.media3.exoplayer.ExoPlayer,
    private val currentSong: () -> Song?,
    private val currentPositionLive: () -> androidx.lifecycle.MutableLiveData<Int>,
    private val currentIsPlayingLive: () -> androidx.lifecycle.MutableLiveData<Boolean>,
    private val currentIsPlayableErrorLive: () -> androidx.lifecycle.MutableLiveData<Boolean>,
    private val currentIndex: () -> Int,
    private val songList: () -> List<Song>,
    private val playMode: () -> MusicService.PlayMode,
    private val isTempPlayback: () -> Boolean,
    private val isProcessingSongEnd: () -> Boolean,
    private val setProcessingSongEnd: (Boolean) -> Unit,
    private val isPendingRestore: () -> Boolean,
    private val setPendingRestore: (Boolean) -> Unit,
    private val pendingRestorePosition: () -> Int,
    private val restorePositionEmbedded: () -> Boolean,
    private val setRestorePositionEmbedded: (Boolean) -> Unit,
    private val pendingSuppressNotification: () -> Boolean,
    private val setPendingSuppressNotification: (Boolean) -> Unit,
    private val pendingNotificationToShow: () -> Boolean,
    private val setPendingNotificationToShow: (Boolean) -> Unit,
    private val pendingRestoreSong: () -> Song?,
    private val setPendingRestoreSong: (Song?) -> Unit,
    private val pendingAutoPlay: () -> Boolean,
    private val setPendingAutoPlay: (Boolean) -> Unit,
    private val hasSuppressedError: () -> Boolean,
    private val setHasSuppressedError: (Boolean) -> Unit,
    private val errorRetryCount: () -> Int,
    private val setErrorRetryCount: (Int) -> Unit,
    private val isEqualizerEnabled: () -> Boolean,
    private val startupFadeInMs: () -> Long,
    // —— 业务操作闭包（让 Service 执行操作） ——
    private val doPlay: () -> Unit,
    private val doPause: () -> Unit,
    private val doSeekTo: (Int) -> Unit,
    private val doPrepare: () -> Unit,
    private val playNext: () -> Unit,
    private val resumeLastSavedSong: () -> Unit,
    private val updateNotification: (() -> Unit)? = null,
    private val updateNotificationWithSong: ((Song?) -> Unit)? = null,
    private val updateMediaSessionPlaybackState: () -> Unit,
    private val savePlaybackState: () -> Unit,
    private val stopPlayStateObserver: () -> Unit,
    private val startPlayStateObserver: () -> Unit,
) : Player.Listener {

    // ———— 错误码集合：media3 1.9.0 StuckPlayer 检测会把 TIMEOUT 错误上报，运行时错误不应被当作格式不支持 ————
    private val unsupportedFormatErrorCodes = setOf(
        PlaybackException.ERROR_CODE_NOT_SUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
    )

    private fun isDiagEnabled(): Boolean = LogWriter.isDiagEnabled

    override fun onPlaybackStateChanged(state: Int) {
        LogWriter.writeDiag(
            tag,
            "onPlaybackStateChanged: state=$state, isPlaying=${player().isPlaying}, " +
                "playWhenReady=${player().playWhenReady}, position=${player().currentPosition}, " +
                "song=${currentSong()?.title}"
        )
        when (state) {
            Player.STATE_IDLE -> {
                // STATE_IDLE 可能来自 setMediaItem（正常）或 onPlayerError 后（错误）
                if (hasSuppressedError()) {
                    setHasSuppressedError(false)
                    if (errorRetryCount() < 1) {
                        setErrorRetryCount(errorRetryCount() + 1)
                        android.util.Log.w(tag, "STATE_IDLE after suppressed error, retrying prepare")
                        doPrepare()
                    } else {
                        setErrorRetryCount(0)
                        setProcessingSongEnd(false)
                        android.util.Log.w(tag, "Retry limit exceeded, showing unsupported format toast")
                        Toast.makeText(context, "暂不支持播放该格式", Toast.LENGTH_SHORT).show()
                        currentIsPlayingLive().value = false
                        currentIsPlayableErrorLive().value = true
                        currentSong()?.path?.let {
                            SongPlayableRegistry.markUnsupported(context, it)
                        }
                        doPause()
                        updateNotification?.invoke() ?: updateNotificationWithSong?.invoke(null)
                        updateMediaSessionPlaybackState()
                        savePlaybackState()
                        stopPlayStateObserver()
                    }
                }
            }

            Player.STATE_BUFFERING -> {
                // 新歌曲正在准备，不清除 isProcessingSongEnd
                // （旧解码器的 onPlayerError 可能在此之后异步到达）
            }

            Player.STATE_READY -> {
                setProcessingSongEnd(false)
                setHasSuppressedError(false)
                setErrorRetryCount(0)
                currentIsPlayableErrorLive().value = false
                updateMediaSessionPlaybackState()
                updateNotification?.invoke() ?: updateNotificationWithSong?.invoke(null)

                if (isPendingRestore()) {
                    setPendingRestore(false)
                    if (pendingRestorePosition() > 0 && !restorePositionEmbedded()) {
                        doSeekTo(pendingRestorePosition())
                    }
                    setRestorePositionEmbedded(false)
                    updateMediaSessionPlaybackState()
                    currentPositionLive().postValue(player().currentPosition.toInt())

                    if (!pendingSuppressNotification()) {
                        updateNotificationWithSong?.invoke(pendingRestoreSong())
                        setPendingNotificationToShow(false)
                    }
                    setPendingRestoreSong(null)
                    updateMediaSessionPlaybackState()
                    if (pendingAutoPlay()) {
                        doPlay()
                        if (isEqualizerEnabled()) {
                            com.unicorn.player.equalizer.TenBandEqualizerProcessor.instance
                                ?.requestStartupFadeIn(startupFadeInMs())
                        }
                    }
                }
            }

            Player.STATE_ENDED -> {
                if (isProcessingSongEnd()) {
                    android.util.Log.w(tag, "STATE_ENDED already being processed, skip")
                    return
                }
                setProcessingSongEnd(true)
                android.util.Log.d(
                    tag,
                    "STATE_ENDED: natural song end, index=${currentIndex()}, mode=${playMode()}"
                )
                currentPositionLive().value = player().duration.toInt()

                if (isTempPlayback()) {
                    resumeLastSavedSong()
                    return
                }

                if (playMode() == MusicService.PlayMode.SEQUENCE && currentIndex() >= songList().size - 1) {
                    currentIsPlayingLive().value = false
                    updateNotification?.invoke() ?: updateNotificationWithSong?.invoke(null)
                    updateMediaSessionPlaybackState()
                    savePlaybackState()
                } else {
                    playNext()
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying && !player().playWhenReady) {
            if (currentIsPlayingLive().value != false) {
                android.util.Log.d(tag, "onIsPlayingChanged: paused (external)")
                currentIsPlayingLive().value = false
                updateNotification?.invoke() ?: updateNotificationWithSong?.invoke(null)
                updateMediaSessionPlaybackState()
                savePlaybackState()
                stopPlayStateObserver()
            }
        } else if (isPlaying) {
            if (currentIsPlayingLive().value != true) {
                android.util.Log.d(tag, "onIsPlayingChanged: resumed (external)")
                currentIsPlayingLive().value = true
                startPlayStateObserver()
                updateMediaSessionPlaybackState()
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        LogWriter.writeError(tag, "ExoPlayer onPlayerError: ${error.message}", error)

        if (isProcessingSongEnd()) {
            setHasSuppressedError(true)
            android.util.Log.w(
                tag,
                "onPlayerError during song transition, suppressing (likely old decoder)"
            )
            return
        }

        if (error.errorCode in unsupportedFormatErrorCodes) {
            Toast.makeText(context, "暂不支持播放该格式", Toast.LENGTH_SHORT).show()
            currentIsPlayingLive().value = false
            currentIsPlayableErrorLive().value = true
            currentSong()?.path?.let {
                SongPlayableRegistry.markUnsupported(context, it)
            }
            doPause()
        } else {
            Toast.makeText(context, "播放失败，正在重试", Toast.LENGTH_SHORT).show()
            currentIsPlayingLive().value = false
            currentIsPlayableErrorLive().value = true
            try {
                val position = player().currentPosition
                doPrepare()
                if (position > 0) doSeekTo(position.toInt())
                doPlay()
            } catch (e: Exception) {
                LogWriter.writeError(tag, "Self-heal prepare failed", e)
                doPause()
            }
        }
        updateNotification?.invoke() ?: updateNotificationWithSong?.invoke(null)
        updateMediaSessionPlaybackState()
        savePlaybackState()
        stopPlayStateObserver()
    }
}
