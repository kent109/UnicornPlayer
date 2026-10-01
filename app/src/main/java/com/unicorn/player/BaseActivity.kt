package com.unicorn.player

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        currentThemeRes = resolveColorTheme()
        if (currentThemeRes != 0) {
            setTheme(currentThemeRes)
        }
        currentHighlightColor = ThemeSettingActivity.resolveHighlightColor(this)
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
     * - 当检测到有虚拟导航键时，去掉底部播放条的 8dp marginBottom，避免双重间距
     */
    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        applySystemBarInsets()
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applySystemBarInsets()
    }

    /**
     * 子类可重写此方法返回 false，以禁用自动系统栏 padding
     * （例如需要全屏沉浸且自行处理 insets 的播放器页面）。
     */
    protected open val applySystemBarInsets: Boolean
        get() = true

    private fun applySystemBarInsets() {
        if (!applySystemBarInsets) return
        val rootView = findViewById<View>(android.R.id.content) ?: return
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                systemBars.bottom
            )
            // 有虚拟导航键时，去掉底部播放条的底部 margin（8dp），
            // 由根视图的 paddingBottom 避让导航键，避免双重间距；
            // 手势导航时恢复 8dp marginBottom。
            val bottomPlayer = findViewById<View>(R.id.bottomPlayer)
            if (bottomPlayer != null) {
                val mbDp = if (systemBars.bottom > 0) 0 else 8
                val lp = bottomPlayer.layoutParams
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.bottomMargin = (mbDp * density).toInt()
                    bottomPlayer.layoutParams = lp
                }
            }
            insets
        }
        // 强制请求一次 insets 派发，确保 listener 被触发
        ViewCompat.requestApplyInsets(rootView)
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
