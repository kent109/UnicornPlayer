package com.unicorn.player.util

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bullhead.equalizer.AudioEffectManager
import com.bullhead.equalizer.Settings
import com.bullhead.equalizer.TenBandEqBridge
import com.unicorn.player.service.MusicService

/**
 * 快捷功能集合：无需进入设置/均衡器页面，一步完成常用切换。
 */
object ShortcutUtil {

    private const val TAG = "ShortcutUtil"

    private const val NUM_BANDS = 10
    private const val MAX_BASS_STRENGTH = 1000

    /** 打开均衡器后的媒体音量百分比 */
    private const val VOLUME_PERCENT_ON = 90

    /** 关闭均衡器后的媒体音量百分比 */
    private const val VOLUME_PERCENT_OFF = 60

    /** 打开均衡器后推迟调高音量的时长：等音效链建立、频段增益渐变到位再抬音量 */
    private const val VOLUME_DELAY_ON_MS = 1000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 切换均衡器总开关，并按开关状态调整系统媒体音量。
     *
     * @return 切换后的开关状态，true 表示已打开
     */
    fun toggleEqualizer(context: Context, service: MusicService?): Boolean {
        val enabled = !Settings.isEqualizerEnabled
        Settings.isEqualizerEnabled = enabled
        Settings.equalizerModel?.isEqualizerEnabled = enabled

        if (enabled) {
            // 频段增益与低音由 App 内 AudioProcessor 承担（内部有系数渐变，无阶跃断层），
            // 混响仍由系统 PresetReverb 负责，需按 session 创建/对齐系统音效
            TenBandEqBridge.applyBandLevels(Settings.seekbarpos)
            TenBandEqBridge.applyBassStrength(clampedBassStrength())
            syncSystemEffectsOn(context, service)
            // 打开成功 1s 后再抬音量，避免与音效接通的渐变叠在一起
            mainHandler.postDelayed(
                { setMediaVolumePercent(context, VOLUME_PERCENT_ON) },
                VOLUME_DELAY_ON_MS
            )
        } else {
            // 关闭时必须显式下发全零：低音低架是独立滤波器，只清频段仍会染色
            TenBandEqBridge.applyBandLevels(IntArray(NUM_BANDS))
            TenBandEqBridge.applyBassStrength(0)
            AudioEffectManager.disableEffects()
            // 撤掉尚未执行的抬音量任务，否则关完 1s 后又被拉回 80%
            mainHandler.removeCallbacksAndMessages(null)
            setMediaVolumePercent(context, VOLUME_PERCENT_OFF)
        }

        // 与均衡器页面一致：落盘开关状态，冷启动后可还原
        service?.savePlaybackState()
        Log.d(TAG, "toggleEqualizer: enabled=$enabled")
        return enabled
    }

    /**
     * 音效未创建时按当前播放 session 初始化，再按开关对齐 enable 状态。
     * 未播放（session 为 0）时跳过，起播路径会自行应用均衡器参数。
     */
    private fun syncSystemEffectsOn(context: Context, service: MusicService?) {
        val sessionId = service?.getAudioSessionId() ?: 0
        if (sessionId == 0) {
            return
        }
        if (!AudioEffectManager.areEffectsEnabled()) {
            AudioEffectManager.initialize(context, sessionId)
        }
        AudioEffectManager.enableEffects(context)
    }

    /** 取持久化的低音强度，越界（旧版本残留 -1 等）按 0 处理。 */
    private fun clampedBassStrength(): Int {
        val raw = Settings.equalizerModel?.bassStrength?.toInt()
            ?: Settings.bassStrength.toInt()
        return if (raw in 0..MAX_BASS_STRENGTH) raw else 0
    }

    /**
     * 设置系统媒体音量百分比。STREAM_MUSIC 的音量调节无需用户授权，
     * 未声明任何权限也可写入；flags 传 0 让系统弹出音量条作为反馈。
     */
    private fun setMediaVolumePercent(context: Context, percent: Int) {
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return
        }
        val target = (maxVolume * percent / 100f).toInt().coerceIn(0, maxVolume)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: Exception) {
            Log.e(TAG, "setMediaVolumePercent failed: percent=$percent", e)
        }
    }
}
