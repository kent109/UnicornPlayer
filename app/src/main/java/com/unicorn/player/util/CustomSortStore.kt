package com.unicorn.player.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.unicorn.player.model.Song
import com.unicorn.player.service.PlaySource
import androidx.core.content.edit

/**
 * 自定义排序存储（进程级）
 *
 * 排序入口在原有 3 种方式之后新增"自定义排序"：它不替换原有方式，而是把它们记为
 * 基准顺序（`sort_mode_prefs/sort_mode` 语义不变），另用一个开关
 * （`sort_mode_prefs/custom_sort_enabled`）表示当前是否处于自定义排序。
 *
 * 拖动结果按播放来源分别持久化，键与 MusicService 的播放来源标签同构
 * （f0、f1 加歌手名、f2 加专辑名、f3 加歌单 ID），因此队列恢复、详情页展示都能取到同一份顺序。
 *
 * 顺序以歌曲文件路径而非 MediaStore 行 ID 保存：重扫或重新拷入会让行 ID 变化，
 * 用 ID 记录会让整份拖拽顺序失效（与隐藏歌曲、歌单导出的选择一致）。
 */
object CustomSortStore {

    private const val SORT_PREFS_NAME = "sort_mode_prefs"
    private const val CUSTOM_ENABLED_KEY = "custom_sort_enabled"
    private const val ORDER_PREFS_NAME = "custom_sort_prefs"
    private const val ORDER_KEY_PREFIX = "order_"

    private val gson = Gson()
    private val pathListType = object : TypeToken<List<String>>() {}.type

    /** 来源标签 -> (路径 -> 名次)。写入与清除时同步失效，避免每次排序重新解析 JSON */
    private val rankCache = HashMap<String, Map<String, Int>>()

    /** 当前是否处于自定义排序 */
    fun isEnabled(context: Context): Boolean =
        sortPrefs(context).getBoolean(CUSTOM_ENABLED_KEY, false)

    /**
     * 切换自定义排序开关。同步提交：切回基础排序时要紧接一次列表重排与队列同步，
     * 异步落盘可能让另一处入口读到旧值。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        sortPrefs(context).edit(commit = true) {
            putBoolean(CUSTOM_ENABLED_KEY, enabled)
        }
    }

    /** 来源标签：与 PlaySource 一致，name 为空时退化为纯类型（f0） */
    fun scopeOf(type: String, name: String): String = PlaySource.build(type, name)

    /** 各列表页统一用这四个函数取键，避免和 PlayHelper 写进来源标签的拼法对不上 */
    fun allSongsScope(): String = PlaySource.SONGS

    // 网格显示名取"首次出现的原始大小写"，列表顺序一变就会翻面（Beyond/BEYOND）；
    // scope 键统一转小写，拖动顺序记录不随显示名翻面丢失
    fun artistScope(artistName: String): String =
        PlaySource.build(PlaySource.ARTIST, artistName.lowercase())

    fun albumScope(albumName: String): String =
        PlaySource.build(PlaySource.ALBUM, albumName.lowercase())

    fun playlistScope(playlistId: Long): String =
        PlaySource.build(PlaySource.PLAYLIST, playlistId.toString())

    /**
     * 在基准排序结果上叠加该来源的拖动顺序。
     *
     * 未开启开关或该来源没有拖动记录时原样返回，保证"选了自定义但还没拖动"
     * 列表顺序不变；没有拖动记录的新增歌曲（重新扫描后入库）按基准顺序排在末尾。
     */
    fun applyOrder(context: Context, scope: String, baseSorted: List<Song>): List<Song> {
        if (!isEnabled(context)) return baseSorted
        val ranks = ranksFor(context, scope)
        if (ranks.isEmpty()) return baseSorted
        val ordered = ArrayList<Song>(baseSorted.size)
        val unranked = ArrayList<Song>()
        for (song in baseSorted) {
            if (ranks.containsKey(song.path)) ordered.add(song) else unranked.add(song)
        }
        ordered.sortBy { ranks[it.path] }
        return ordered + unranked
    }

    /** 保存该来源的拖动顺序；列表为空时等同于清除，避免留下无主的空记录 */
    fun saveOrder(context: Context, scope: String, paths: List<String>) {
        rankCache.remove(scope)
        if (paths.isEmpty()) {
            clearScope(context, scope)
            return
        }
        // 同步提交：拖动松手后用户可能立刻划掉/强杀进程，异步 apply 会丢掉这份顺序
        orderPrefs(context).edit(commit = true) {
            putString(ORDER_KEY_PREFIX + scope, gson.toJson(paths, pathListType))
        }
    }

    /** 清除某个来源的拖动顺序（来源被删空、歌单被删除时调用） */
    fun clearScope(context: Context, scope: String) {
        rankCache.remove(scope)
        orderPrefs(context).edit(commit = true) { remove(ORDER_KEY_PREFIX + scope) }
    }

    /** 清除全部来源的拖动顺序（从自定义切回基础排序时调用） */
    fun clearAll(context: Context) {
        rankCache.clear()
        orderPrefs(context).edit(commit = true) { clear() }
    }

    private fun ranksFor(context: Context, scope: String): Map<String, Int> {
        rankCache[scope]?.let { return it }
        val json = orderPrefs(context).getString(ORDER_KEY_PREFIX + scope, null)
        val paths: List<String> = if (json.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                gson.fromJson<List<String>>(json, pathListType) ?: emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
        val ranks = HashMap<String, Int>(paths.size * 2)
        for ((index, path) in paths.withIndex()) {
            // 同一路径重复出现时以首次出现的名次为准
            if (!ranks.containsKey(path)) ranks[path] = index
        }
        rankCache[scope] = ranks
        return ranks
    }

    private fun sortPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(SORT_PREFS_NAME, Context.MODE_PRIVATE)

    private fun orderPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(ORDER_PREFS_NAME, Context.MODE_PRIVATE)
}
