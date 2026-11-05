package com.unicorn.player.util

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.bullhead.equalizer.Settings
import com.bullhead.equalizer.TenBandEqBridge
import com.unicorn.player.R
import com.unicorn.player.ShortcutBridgeActivity
import com.unicorn.player.service.DataStoreKeys
import com.unicorn.player.service.MusicService
import com.unicorn.player.service.applicationDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 快捷功能集合：无需进入设置/均衡器页面，一步完成常用切换。
 */
object ShortcutUtil {

    private const val TAG = "ShortcutUtil"

    private const val NUM_BANDS = 10
    private const val MAX_BASS_STRENGTH = 1000

    /** 动态快捷方式 ID */
    private const val SHORTCUT_ID_TOGGLE_EQ = "toggle_equalizer"

    /** 触发均衡器切换的 Intent Action */
    const val ACTION_TOGGLE_EQUALIZER = "com.unicorn.player.action.TOGGLE_EQUALIZER"

    /**
     * 切换均衡器总开关。
     *
     * 音效下发统一走 [MusicService.applyEqualizerEnabledState]（与均衡器页面、
     * 冷启动恢复共用同一入口：处理器电平 + 系统音效对齐 + 落盘）。
     *
     * 当 service == null 时（未播放、进程刚强杀后重启）：
     *   1. 先从 DataStore 恢复 Settings.isEqualizerEnabled — 进程内单例可能是默认值
     *   2. 切换后立即把新值持久化到 DataStore — 否则下次 MusicService 启动时
     *      restoreEqualizerSettings() 会把进程内改过的值覆盖回去
     *
     * @param context 用于 DataStore 读写（service == null 时必须）
     * @param service 正在运行的 MusicService，null 表示未启动
     * @return 切换后的开关状态，true 表示已打开
     */
    fun toggleEqualizer(context: Context, service: MusicService?): Boolean {
        // 服务未启动（进程刚重启 / 未播放）：Settings 库的进程内单例可能是默认值 false
        // 必须先从 DataStore 恢复持久化的状态，然后在末尾手动落盘
        if (service == null) {
            runBlocking {
                try {
                    val prefs = context.applicationDataStore.data.first()
                    Settings.isEqualizerEnabled =
                        prefs[DataStoreKeys.IS_EQUALIZER_ENABLED] == 1
                } catch (_: Exception) {
                    // DataStore 首次访问异常：保持默认值，下次起播时 MusicService 会重新恢复
                }
            }
        }

        val enabled = !Settings.isEqualizerEnabled
        Settings.isEqualizerEnabled = enabled
        Settings.equalizerModel?.isEqualizerEnabled = enabled

        if (service != null) {
            service.applyEqualizerEnabledState()
            // applyEqualizerEnabledState 内部调用 savePlaybackState() 已落盘
        } else {
            // 服务未绑定：通过桥接下发处理器电平
            if (enabled) {
                TenBandEqBridge.applyBandLevels(Settings.seekbarpos)
                TenBandEqBridge.applyBassStrength(clampedBassStrength())
            } else {
                // 关闭时必须显式下发全零：低音低架是独立滤波器，只清频段仍会染色
                TenBandEqBridge.applyBandLevels(IntArray(NUM_BANDS))
                TenBandEqBridge.applyBassStrength(0)
            }
            // 关键：手动持久化，否则下次 MusicService 启动时 restoreEqualizerSettings()
            // 会从 DataStore 读到旧值，把进程内改的 Settings.isEqualizerEnabled 覆盖回去
            runBlocking {
                try {
                    context.applicationDataStore.edit { prefs ->
                        prefs[DataStoreKeys.IS_EQUALIZER_ENABLED] = if (enabled) 1 else 0
                    }
                } catch (_: Exception) {
                    // 落盘失败只能忽略，MusicService 下次启动时 restore 会用旧值
                }
            }
        }

        Log.d(TAG, "toggleEqualizer: enabled=$enabled, service=${service != null}")
        return enabled
    }

    /**
     * 初始化桌面长按图标快捷方式：应用启动时调用。
     * 如果快捷方式已存在则更新其文本，不存在则新建。
     *
     * 最低 SDK 29 支持动态快捷方式，无需额外版本判断。
     */
    fun setupEqualizerShortcut(context: Context) {
        val shortcutManager = context.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager
            ?: return

        val shortcut = buildToggleEqShortcut(context, Settings.isEqualizerEnabled)

        val existingIds = shortcutManager.dynamicShortcuts.map { it.id }
        if (SHORTCUT_ID_TOGGLE_EQ in existingIds) {
            shortcutManager.updateShortcuts(listOf(shortcut))
        } else {
            shortcutManager.addDynamicShortcuts(listOf(shortcut))
        }
        Log.d(TAG, "setupEqualizerShortcut: enabled=${Settings.isEqualizerEnabled}")
    }

    /**
     * 更新快捷方式的文本（均衡器状态变化后调用）。
     * 如果快捷方式不存在（被用户移除）会自动重新添加。
     */
    fun updateEqualizerShortcut(context: Context, enabled: Boolean) {
        val shortcutManager = context.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager
            ?: return

        val shortcut = buildToggleEqShortcut(context, enabled)
        try {
            shortcutManager.updateShortcuts(listOf(shortcut))
        } catch (e: Exception) {
            // 快捷方式不存在时 updateShortcuts 抛异常，重新添加
            try {
                shortcutManager.addDynamicShortcuts(listOf(shortcut))
            } catch (e2: Exception) {
                Log.e(TAG, "updateEqualizerShortcut: add failed", e2)
            }
        }
        Log.d(TAG, "updateEqualizerShortcut: enabled=$enabled")
    }

    private fun buildToggleEqShortcut(context: Context, enabled: Boolean): ShortcutInfo {
        val label = if (enabled) "关闭均衡器" else "打开均衡器"

        // 透明 Activity，和 MainActivity 共用同一个 taskAffinity
        // onCreate 里完成切换后先 startActivity Launcher 再 finish
        val intent = Intent(context, ShortcutBridgeActivity::class.java).apply {
            action = ACTION_TOGGLE_EQUALIZER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return ShortcutInfo.Builder(context, SHORTCUT_ID_TOGGLE_EQ)
            .setShortLabel(label)
            .setLongLabel("开/关均衡器")
            .setIcon(Icon.createWithResource(context, R.drawable.ic_equalizer))
            .setIntent(intent)
            .build()
    }

    /** 取持久化的低音强度，越界（旧版本残留 -1 等）按 0 处理。 */
    private fun clampedBassStrength(): Int {
        val raw = Settings.equalizerModel?.bassStrength?.toInt()
            ?: Settings.bassStrength.toInt()
        return if (raw in 0..MAX_BASS_STRENGTH) raw else 0
    }
}
