package com.unicorn.player.util.lang

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.unicorn.player.util.datastore.themeDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

object LanguageManager {

    const val LANG_ZH_CN = "zh-CN"
    const val LANG_EN = "en"

    private val LANGUAGE_PREF = stringPreferencesKey("language")

    @Volatile
    private var needsRecreateFlag: Boolean = false

    // ========================================================================
    // 切换语言
    // ========================================================================

    fun apply(context: Context, language: String) {
        saveLanguage(context, language)
        val locale = languageToLocale(language)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(locale))
        needsRecreateFlag = true
    }

    fun restore(context: Context) {
        val lang = getSavedLanguage(context)
        val locale = languageToLocale(lang)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(locale))
        needsRecreateFlag = false
    }

    // ========================================================================
    // 持久化读写
    // ========================================================================

    fun getSavedLanguage(context: Context): String {
        return try {
            runBlocking {
                try {
                    context.applicationContext.themeDataStore.data.first()[LANGUAGE_PREF]
                        ?: LANG_ZH_CN
                } catch (_: Exception) { LANG_ZH_CN }
            }
        } catch (_: Exception) { LANG_ZH_CN }
    }

    private fun saveLanguage(context: Context, language: String) {
        runBlocking {
            try {
                context.applicationContext.themeDataStore.edit { it[LANGUAGE_PREF] = language }
            } catch (_: Exception) {}
        }
    }

    // ========================================================================
    // Activity 侧检测（两个条件任一满足就 recreate）
    // ========================================================================

    /**
     * 两个条件任一为 true 就需要 recreate：
     *   1. 用户刚切完语言（needsRecreateFlag — LanguageChooseActivity 设置的）
     *   2. AppCompatDelegate 期望的 Locale 和当前 Activity Resources 实际 Locale 不一致
     *      — 覆盖 back stack 里旧 Activity 没被 recreate 的情况
     */
    fun shouldRecreate(context: Context): Boolean {
        // 条件 1：显式标志（用户刚切语言）
        if (needsRecreateFlag) {
            needsRecreateFlag = false // 清标志，只触发一次
            return true
        }
        // 条件 2：AppCompatDelegate Locale vs Activity Resources Locale
        val expectedTag = AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag()
        if (expectedTag == null) return false
        val currentLocales: android.os.LocaleList? = context.resources.configuration.locales
        val currentTag = if (currentLocales != null && currentLocales.size() > 0)
            currentLocales[0].toLanguageTag()
        else Locale.getDefault().toLanguageTag()
        return normalizeTag(expectedTag) != normalizeTag(currentTag)
    }

    private fun normalizeTag(tag: String): String = when {
        tag.startsWith("zh") -> "zh"
        tag.startsWith("en") -> "en"
        else -> tag
    }

    // ========================================================================
    // 工具
    // ========================================================================

    private fun languageToLocale(language: String): Locale = when (language) {
        LANG_EN -> Locale.ENGLISH
        LANG_ZH_CN -> Locale.SIMPLIFIED_CHINESE
        else -> Locale.getDefault()
    }

    fun getLanguageOptions(): List<Pair<String, Int>> = listOf(
        LANG_ZH_CN to com.unicorn.player.R.string.language_zh_cn,
        LANG_EN to com.unicorn.player.R.string.language_en,
    )
}
