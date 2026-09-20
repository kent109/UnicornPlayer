package com.unicorn.player

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        currentThemeRes = resolveColorTheme()
        if (currentThemeRes != 0) {
            setTheme(currentThemeRes)
        }
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val newThemeRes = resolveColorTheme()
        if (newThemeRes != currentThemeRes) {
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
