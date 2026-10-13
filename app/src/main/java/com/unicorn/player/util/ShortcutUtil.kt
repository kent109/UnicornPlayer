package com.unicorn.player.util

import android.util.Log
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

    /**
     * 切换均衡器总开关。
     *
     * 音效下发统一走 [MusicService.applyEqualizerEnabledState]（与均衡器页面、
     * 冷启动恢复共用同一入口：处理器电平 + 系统音效对齐 + 落盘）。
     *
     * @return 切换后的开关状态，true 表示已打开
     */
    fun toggleEqualizer(service: MusicService?): Boolean {
        val enabled = !Settings.isEqualizerEnabled
        Settings.isEqualizerEnabled = enabled
        Settings.equalizerModel?.isEqualizerEnabled = enabled

        if (service != null) {
            service.applyEqualizerEnabledState()
        } else {
            // 服务未绑定（未播放）：仅通过桥接下发处理器电平；
            // 系统音效与落盘由起播路径自行应用
            if (enabled) {
                TenBandEqBridge.applyBandLevels(Settings.seekbarpos)
                TenBandEqBridge.applyBassStrength(clampedBassStrength())
            } else {
                // 关闭时必须显式下发全零：低音低架是独立滤波器，只清频段仍会染色
                TenBandEqBridge.applyBandLevels(IntArray(NUM_BANDS))
                TenBandEqBridge.applyBassStrength(0)
            }
        }

        Log.d(TAG, "toggleEqualizer: enabled=$enabled")
        return enabled
    }

    /** 取持久化的低音强度，越界（旧版本残留 -1 等）按 0 处理。 */
    private fun clampedBassStrength(): Int {
        val raw = Settings.equalizerModel?.bassStrength?.toInt()
            ?: Settings.bassStrength.toInt()
        return if (raw in 0..MAX_BASS_STRENGTH) raw else 0
    }
}
