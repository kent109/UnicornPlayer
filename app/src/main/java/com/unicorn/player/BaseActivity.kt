package com.unicorn.player

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

abstract class BaseActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BaseActivity"
    }

    private var currentThemeRes = 0
    private var currentHighlightColor = 0
    private var currentBottomPanelMode = ThemeSettingActivity.BOTTOM_PANEL_MODE_CARD

    override fun onCreate(savedInstanceState: Bundle?) {
        currentThemeRes = resolveColorTheme()
        if (currentThemeRes != 0) {
            setTheme(currentThemeRes)
        }
        currentHighlightColor = ThemeSettingActivity.resolveHighlightColor(this)
        currentBottomPanelMode = ThemeSettingActivity.resolveBottomPanelMode(this)
        super.onCreate(savedInstanceState)
    }

    /**
     * 重写 setContentView，为根视图应用系统栏 insets 的 padding。
     *
     * 问题背景：targetSdk 35（Android 15+）强制启用 edge-to-edge，
     * 内容会延伸到系统状态栏和导航栏下方。主题设了 fitsSystemWindows=false 和透明状态栏，
     * 但没有代码层 insets 处理，导致界面顶部延伸进状态栏、底部播放条被虚拟导航键遮挡。
     *
     * 此处统一在根视图上监听 WindowInsets：
     * - 顶部：状态栏高度作为 paddingTop，所有 Activity 自动避让状态栏
     * - 底部：导航栏高度作为 paddingBottom，内容不被虚拟导航键遮挡
     * - 播放条底部 margin：虚拟按键导航时为 0（paddingBottom 已避让）；
     *   手势导航时保留 8dp（指示条悬浮不占位，如 ColorOS 上报 inset 为 0）
     */
    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applySystemBarInsets()
        refreshBottomPanelStyle()
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applySystemBarInsets()
        refreshBottomPanelStyle()
    }

    /**
     * 子类可重写此方法返回 false，以禁用自动系统栏 padding
     * （例如需要全屏沉浸且自行处理 insets 的播放器页面）。
     */
    protected open val applySystemBarInsets: Boolean
        get() = true

    private var lastNavBottomPx = 0
    private var lastMandatoryBottomPx = 0

    private fun applySystemBarInsets() {
        if (!applySystemBarInsets) return
        val rootView = findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val mandatoryGestures =
                insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                systemBars.bottom
            )
            lastNavBottomPx = systemBars.bottom
            lastMandatoryBottomPx = mandatoryGestures.bottom
            findViewById<View>(R.id.bottomPlayer)?.let { applyBottomPanelMargin(it) }
            insets
        }
        // 强制请求一次 insets 派发，确保 listener 被触发
        ViewCompat.requestApplyInsets(rootView)
    }

    /**
     * 底部面板（播放条 / 多选操作栏）的底部 margin 规则：
     * - 横幅面板恒为 0；
     * - 卡片面板在虚拟按键导航时为 0（由根视图 paddingBottom 避让），手势导航时为 8dp。
     * 虚拟按键的导航栏高度不低于底部强制手势区；
     * 手势导航的指示条要么上报 0（如 ColorOS），要么低于强制手势区。
     */
    fun applyBottomPanelMargin(panel: View) {
        val lp = panel.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = resources.displayMetrics.density
        val isThreeButton =
            lastNavBottomPx > 0 && lastNavBottomPx >= lastMandatoryBottomPx
        val mbDp = if (
            currentBottomPanelMode == ThemeSettingActivity.BOTTOM_PANEL_MODE_BANNER ||
            isThreeButton
        ) 0 else 8
        lp.bottomMargin = (mbDp * density).toInt()
        panel.layoutParams = lp
    }

    override fun onResume() {
        super.onResume()
        val newThemeRes = resolveColorTheme()
        if (newThemeRes != currentThemeRes) {
            recreate()
            return
        }
        val newHighlightColor = ThemeSettingActivity.resolveHighlightColor(this)
        if (newHighlightColor != currentHighlightColor) {
            recreate()
        }
        val newBottomPanelMode = ThemeSettingActivity.resolveBottomPanelMode(this)
        if (newBottomPanelMode != currentBottomPanelMode) {
            currentBottomPanelMode = newBottomPanelMode
            refreshBottomPanelStyle()
            // 重新派发 insets，让 listener 按新面板模式重算播放条底部 margin
            findViewById<View>(android.R.id.content)?.let { ViewCompat.requestApplyInsets(it) }
        }
    }

    /**
     * 按"底部面板"样式设置调整本页播放条；不含播放条的页面直接返回。
     */
    private fun refreshBottomPanelStyle() {
        val bottomPlayer = findViewById<View>(R.id.bottomPlayer) as? CardView ?: return
        ThemeSettingActivity.applyBottomPanelStyle(
            bottomPlayer,
            findViewById<View>(R.id.playerBarContent),
            currentBottomPanelMode,
            8
        )
    }

    private fun resolveColorTheme(): Int {
        return try {
            val prefs = runBlocking {
                try {
                    applicationContext.themeDataStore.data.first()
                } catch (e: Exception) {
                    null
                }
            } ?: return 0

            val colorMode = prefs[ThemeSettingActivity.THEME_COLOR_MODE]
                ?: ThemeSettingActivity.COLOR_MODE_SYSTEM

            when (colorMode) {
                ThemeSettingActivity.COLOR_MODE_FIXED -> {
                    val index = prefs[ThemeSettingActivity.THEME_COLOR_INDEX] ?: 0
                    ThemeSettingActivity.getColorThemeRes(index)
                }

                ThemeSettingActivity.COLOR_MODE_RANDOM -> {
                    val currentIndex = prefs[ThemeSettingActivity.THEME_COLOR_INDEX] ?: -1
                    val lastIndex = prefs[ThemeSettingActivity.THEME_COLOR_LAST_INDEX] ?: -1
                    val lastDate = prefs[ThemeSettingActivity.THEME_COLOR_LAST_SWITCH_DATE] ?: ""

                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val hour = SimpleDateFormat("HH", Locale.getDefault()).format(Date()).toInt()

                    val needSwitch = lastDate != today && hour >= 2

                    if (needSwitch || currentIndex == -1) {
                        val newIndex = pickRandomColor(currentIndex, lastIndex)
                        runBlocking {
                            try {
                                applicationContext.themeDataStore.edit { prefs ->
                                    prefs[ThemeSettingActivity.THEME_COLOR_INDEX] = newIndex
                                    prefs[ThemeSettingActivity.THEME_COLOR_LAST_INDEX] =
                                        currentIndex
                                    prefs[ThemeSettingActivity.THEME_COLOR_LAST_SWITCH_DATE] = today
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "保存随机主题色失败", e)
                            }
                        }
                        ThemeSettingActivity.getColorThemeRes(newIndex)
                    } else {
                        ThemeSettingActivity.getColorThemeRes(currentIndex)
                    }
                }

                else -> 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析主题色失败", e)
            0
        }
    }

    private fun pickRandomColor(currentIndex: Int, lastIndex: Int): Int {
        val count = ThemeSettingActivity.COLOR_COUNT
        var newIndex: Int
        do {
            newIndex = Random.nextInt(count)
        } while (newIndex == currentIndex || (newIndex == lastIndex))
        return newIndex
    }
}
