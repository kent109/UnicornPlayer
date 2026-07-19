package com.unicorn.player.util

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.unicorn.player.model.LrcSearchResult
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Collections

/**
 * 歌词网络获取工具类
 * 从 lrclib.net 搜索并下载同步歌词（LRC），保存到应用私有外部目录
 *
 * 入参为音频文件路径（如 "/music/Artist - Title.flac"）：
 *  1. 检查应用私有外部目录下是否存在同名 .lrc 文件，存在则跳过
 *  2. 调用 https://lrclib.net/api/search?q= 搜索
 *  3. 取第一条结果的 syncedLyrics，首行插入 [00:00.00]Artist - Title
 *  4. 保存为应用私有外部目录下的 "Artist - Title.lrc"
 */
object LrcFetcher {

    private const val TAG = "LrcFetcher"

    private const val SEARCH_URL = "https://lrclib.net/api/search?q="

    // 复用的 OkHttp 客户端
    private val client = OkHttpClient()

    private val gson = Gson()

    // 歌词功能总开关：为 true 时正常加载本地歌词/请求网络歌词；为 false 时 fetchLrc 直接跳过
    @Volatile
    var lyricsEnabled: Boolean = true

    // 正在请求中的音频路径集合，防止同一首歌重复发起网络请求
    private val inFlightRequests = Collections.synchronizedSet<String>(LinkedHashSet())

    // 进行中的 OkHttp Call 集合，用于批量取消（如 Activity 销毁时）
    private val pendingCalls = Collections.synchronizedSet<Call>(LinkedHashSet())

    /**
     * 回调接口
     *
     * lrcFileName 歌词文件名（不含路径），如 "Artist - Title.lrc"
     */
    interface LrcFetchCallback {
        /** 成功下载并保存 */
        fun onSuccess(lrcFileName: String)

        /** 歌词文件已存在，无需下载 */
        fun onFileExists(lrcFileName: String)

        /** 未搜索到歌词 */
        fun onNoLyricsFound()

        /** 网络或解析失败 */
        fun onFailure(message: String)
    }

    /**
     * 搜索回调接口（返回多条结果供用户选择）
     */
    interface LrcSearchCallback {
        /** 搜索成功，返回结果列表（可能为空） */
        fun onSearchResults(results: List<LrcSearchResult>)

        /** 网络或解析失败 */
        fun onSearchFailure(message: String)
    }

    /**
     * 根据歌手和歌名搜索歌词(不能两个都为空)，返回所有匹配结果
     *
     * @param artist 歌手名
     * @param title 歌名
     * @param callback 结果回调（在子线程执行，勿直接操作 UI）
     */
    fun searchLyrics(artist: String, title: String, callback: LrcSearchCallback) {
        val query = "${encodeUrlParam(artist)} ${encodeUrlParam(title)}".trim()
        val url = SEARCH_URL + query.toSimpleCustom()
        Log.i(TAG, "搜索歌词: $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "UnicornPlayer/1.0 (https://github.com/unicorn-player)")
            .build()

        val call = client.newCall(request)
        pendingCalls.add(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                pendingCalls.remove(call)
                if (call.isCanceled()) return
                Log.e(TAG, "歌词搜索请求失败: ${e.message}")
                callback.onSearchFailure("网络请求失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                pendingCalls.remove(call)
                if (call.isCanceled()) return
                if (!response.isSuccessful) {
                    callback.onSearchFailure("HTTP 错误: ${response.code}")
                    return
                }

                val json = response.body?.string()
                if (json.isNullOrBlank()) {
                    callback.onSearchResults(emptyList())
                    return
                }

                try {
                    val results = parseSearchResults(json)
                    callback.onSearchResults(results)
                } catch (e: Exception) {
                    Log.e(TAG, "解析搜索结果失败: ${e.message}", e)
                    callback.onSearchFailure("解析失败: ${e.message}")
                }
            }
        })
    }

    /**
     * 解析 JSON 搜索结果，返回所有有 syncedLyrics 的条目
     */
    private fun parseSearchResults(json: String): List<LrcSearchResult> {
        val jsonArray = gson.fromJson(json, JsonArray::class.java)
        if (jsonArray == null || jsonArray.size() == 0) {
            return emptyList()
        }

        return jsonArray.mapNotNull { element ->
            val obj = element.asJsonObject ?: return@mapNotNull null
            val syncedLyrics = obj.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString
            if (syncedLyrics.isNullOrBlank()) return@mapNotNull null

            val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0
            val trackName = obj.get("trackName")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val artistName = obj.get("artistName")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val albumName = obj.get("albumName")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val duration = obj.get("duration")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0

            LrcSearchResult(
                id = id,
                trackName = trackName,
                artistName = artistName,
                albumName = albumName,
                duration = duration,
                syncedLyrics = syncedLyrics
            )
        }
    }

    /**
     * 根据音频文件路径获取歌词
     *
     * @param context 上下文，用于访问应用私有外部目录
     * @param audioPath 音频文件完整路径，如 "/storage/music/Artist - Title.flac"
     * @param callback 结果回调（在子线程执行，勿直接操作 UI）
     */
    fun fetchLrc(context: Context, audioPath: String, callback: LrcFetchCallback) {
        if (!lyricsEnabled) {
            return
        }

        if (audioPath.isBlank()) {
            callback.onFailure("音频路径为空")
            return
        }

        // 去重：如果同一首歌正在请求中，直接忽略
        if (!inFlightRequests.add(audioPath)) {
            Log.d(TAG, "歌词请求已在进行中，跳过重复请求: $audioPath")
            return
        }

        // 包装回调，确保请求结束后从 inFlightRequests 中移除
        val wrappedCallback = object : LrcFetchCallback {
            private fun notifyOriginal(callbackFn: () -> Unit) {
                try {
                    callbackFn()
                } finally {
                    inFlightRequests.remove(audioPath)
                }
            }

            override fun onSuccess(lrcFileName: String) =
                notifyOriginal { callback.onSuccess(lrcFileName) }

            override fun onFileExists(lrcFileName: String) =
                notifyOriginal { callback.onFileExists(lrcFileName) }

            override fun onNoLyricsFound() = notifyOriginal { callback.onNoLyricsFound() }
            override fun onFailure(message: String) = notifyOriginal { callback.onFailure(message) }
        }

        val baseName = java.io.File(audioPath).nameWithoutExtension // "Artist - Title"
        val lrcFileName = "$baseName.lrc"

        // 1. 检查应用私有外部目录下歌词文件是否已存在
        if (LrcHelper.readLrcFromMusic(context, lrcFileName) != null) {
            inFlightRequests.remove(audioPath)
            wrappedCallback.onFileExists(lrcFileName)
            return
        }

        // 2. 从文件名解析 artist 和 title（格式: "Artist - Title"）
        val (artist, title) = parseArtistTitle(baseName)
        if (title.isBlank()) {
            inFlightRequests.remove(audioPath)
            wrappedCallback.onFailure("无法解析歌曲名: $baseName")
            return
        }

        // 3. 发起网络请求
        val query = "${encodeUrlParam(artist)} ${encodeUrlParam(title)}".trim()
        val url = SEARCH_URL + query.toSimpleCustom()
        Log.i(TAG, "搜索歌词: $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "UnicornPlayer/1.0 (https://github.com/unicorn-player)")
            .build()

        val call = client.newCall(request)
        pendingCalls.add(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                pendingCalls.remove(call)
                if (call.isCanceled()) return
                Log.e(TAG, "歌词搜索请求失败: ${e.message}")
                wrappedCallback.onFailure("网络请求失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                pendingCalls.remove(call)
                if (call.isCanceled()) return
                if (!response.isSuccessful) {
                    wrappedCallback.onFailure("HTTP 错误: ${response.code}")
                    return
                }

                val json = response.body?.string()
                if (json.isNullOrBlank()) {
                    wrappedCallback.onNoLyricsFound()
                    return
                }

                try {
                    parseAndSaveLyrics(context, json, artist, title, lrcFileName, wrappedCallback)
                } catch (e: Exception) {
                    Log.e(TAG, "解析歌词失败: ${e.message}", e)
                    wrappedCallback.onFailure("解析失败: ${e.message}")
                }
            }
        })
    }

    /**
     * 取消所有进行中的歌词网络请求
     * 应在 Activity/Fragment 销毁时调用，避免回调访问已销毁的 UI
     */
    fun cancelAll() {
        synchronized(pendingCalls) {
            pendingCalls.forEach { it.cancel() }
            pendingCalls.clear()
        }
        inFlightRequests.clear()
        Log.i(TAG, "cancelAll")
    }

    /**
     * 解析 JSON 响应并保存歌词文件
     *
     * 匹配策略：
     * 1. 遍历所有结果，找到 trackName 与搜索 title 精确匹配（忽略大小写）的项
     * 2. 如果有多条匹配，随机选取一条
     * 3. 如果没有精确匹配，退化为取第一条有 syncedLyrics 的结果
     *
     * @param lrcFileName 目标歌词文件名（不含路径）
     */
    private fun parseAndSaveLyrics(
        context: Context,
        json: String,
        artist: String,
        title: String,
        lrcFileName: String,
        callback: LrcFetchCallback
    ) {
        // lrclib 返回的是 JSON 数组
        val jsonArray = gson.fromJson(json, JsonArray::class.java)
        if (jsonArray == null || jsonArray.size() == 0) {
            callback.onNoLyricsFound()
            return
        }

        // 提取有 syncedLyrics 的有效结果
        val validResults = jsonArray.mapNotNull { element ->
            val obj = element.asJsonObject ?: return@mapNotNull null
            val syncedLyrics = obj.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString
            if (syncedLyrics.isNullOrBlank()) null else obj
        }

        if (validResults.isEmpty()) {
            callback.onNoLyricsFound()
            return
        }

        // 优先找 trackName 与 title 精确匹配的结果（忽略大小写）
        val titleLower = title.trim().lowercase()
        val matchedResults = validResults.filter { obj ->
            val trackName = obj.get("trackName")?.takeIf { !it.isJsonNull }?.asString
            !trackName.isNullOrBlank() && trackName.trim().lowercase() == titleLower
        }

        val selectedResult = when {
            matchedResults.size == 1 -> matchedResults[0]
            matchedResults.size > 1 -> {
                // 多条匹配，随机取一个
                matchedResults[kotlin.random.Random.nextInt(matchedResults.size)]
            }

            else -> {
                // 无精确匹配，退化取第一条
                validResults[0]
            }
        }

        val syncedLyrics = selectedResult.get("syncedLyrics").asString
        val matchedTrackName = selectedResult.get("trackName")?.takeIf { !it.isJsonNull }?.asString
        if (matchedTrackName != null && matchedTrackName.trim().lowercase() != titleLower) {
            Log.i(TAG, "无精确匹配，使用第一条结果: trackName=$matchedTrackName, 搜索title=$title")
        } else {
            Log.i(TAG, "精确匹配成功: trackName=$matchedTrackName")
        }

        // 清理歌词中的 <数字:数字.数字> 标签（如 <00:26.623>）
        val cleanedLyrics = cleanTimestampTags(syncedLyrics)

        // 首行插入 "[00:00.00]Artist - Title"
        val header = "[00:00.00]$artist - $title"
        val lrcContent = header + "\r\n" + cleanedLyrics

        // 转为简体中文后写入应用私有外部目录（CRLF 换行）
        val simplifiedContent = lrcContent.toSimpleCustom()
        val success = LrcHelper.writeLrcToMusic(context, lrcFileName, simplifiedContent)
        if (success) {
            Log.i(TAG, "歌词保存成功: $lrcFileName")
            callback.onSuccess(lrcFileName)
        } else {
            callback.onFailure("保存歌词文件失败")
        }
    }

    /**
     * 清理歌词中的 <数字:数字.数字> 标签（如 <00:26.623>）
     * 这些是非标准 LrcView 时间戳标签，需删除以保证歌词正常显示
     */
    internal fun cleanTimestampTags(content: String): String {
        return content.replace(Regex("<\\d+:\\d+\\.\\d+>"), "")
    }

    /**
     * 从 "Artist - Title" 格式中解析艺术家和标题
     *
     * @return Pair(artist, title)，如果没有分隔符则 artist 为空，title 为全名
     */
    private fun parseArtistTitle(baseName: String): Pair<String, String> {
        val separator = " - "
        val index = baseName.indexOf(separator)
        return if (index > 0) {
            val artist = baseName.substring(0, index).trim()
            val title = baseName.substring(index + separator.length).trim()
            artist to title
        } else {
            "" to baseName.trim()
        }
    }

    /**
     * 简单的 URL 编码（OkHttp 不会自动编码 query 中的空格和中文）
     */
    private fun encodeUrlParam(param: String): String {
        if (param.isBlank()) return ""
        return java.net.URLEncoder.encode(param, "UTF-8")
    }
}
