package com.unicorn.player.util.datastore

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/**
 * 进程级单例 DataStore 委托：主题相关设置
 * 顶层属性保证全进程唯一实例，避免 IllegalStateException
 */
internal val Context.themeDataStore by preferencesDataStore(name = "theme_settings")

/**
 * 进程级单例 DataStore 委托：扫描过滤设置
 * 顶层属性保证全进程唯一实例，避免 IllegalStateException
 */
internal val Context.scanFiltersDataStore by preferencesDataStore(name = "scan_filters")

/**
 * 进程级单例 DataStore 委托：歌词显示设置
 * 顶层属性保证全进程唯一实例，避免 IllegalStateException
 */
internal val Context.lyricsDataStore by preferencesDataStore(name = "lyrics_settings")

/**
 * 进程级单例 DataStore 委托：隐私协议同意标记
 * 顶层属性保证全进程唯一实例，避免 IllegalStateException
 */
internal val Context.privacyDataStore by preferencesDataStore(name = "privacy_consent")
