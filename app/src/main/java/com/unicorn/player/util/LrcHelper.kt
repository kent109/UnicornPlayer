package com.unicorn.player.util

import android.content.Context
import android.util.Log
import com.hw.lrcviewlib.LrcDataBuilder
import com.hw.lrcviewlib.LrcRow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * 歌词加载工具类
 * 负责从应用私有外部目录（getExternalFilesDir("Lyrics")）读写 .lrc 歌词文件，并解析为 LrcRow 列表
 *
 * 文件命名为 "<音频文件名>.lrc"（不含路径），统一存放在
 * /storage/emulated/0/Android/data/com.unicorn.player/files/Lyrics/ 目录下，使用 File API 直接读写。
 */
object LrcHelper {

    private const val TAG = "LrcHelper"

    // 歌词存放的子目录名（相对 getExternalFilesDir）
    private const val LRC_DIR_NAME = "Lyrics"

    // LRC时间戳格式: [mm:ss.xx] 或 [mm:ss.xxx]
    private val LRC_TIME_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})]")

    // LRC offset 标签格式: [offset:±500]，单位毫秒（正=提前，负=延后）
    private val LRC_OFFSET_PATTERN = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*]\\s*$")

    // ===================== 文件目录 =====================

    /**
     * 获取歌词存放目录，不存在则创建
     * 优先使用外部私有目录，回退到内部 filesDir
     */
    private fun getLyricsDir(context: Context): File {
        val dir = context.getExternalFilesDir(LRC_DIR_NAME)
            ?: File(context.filesDir, LRC_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 根据文件名获取歌词文件对象
     */
    private fun getLrcFile(context: Context, fileName: String): File {
        return File(getLyricsDir(context), fileName)
    }

    // ===================== 文件读写 =====================

    /**
     * 将 LRC 歌词内容写入应用私有外部目录，返回是否写入成功。
     *
     * 同名文件会被直接覆盖。
     *
     * @param fileName 显示名，如 "Artist - Title.lrc"
     * @param lrcContent 歌词文本内容
     *
     * @deprecated 请使用 {@link LyricsSaveManager#writeLrcFile} 写入 Documents/Unicorn/Lyrics/（SAF）。
     *             应用私有外部目录存储已废弃，仅作为向后兼容保留。
     */
    @Deprecated("Use LyricsSaveManager.writeLrcFile instead")
    fun writeLrcToMusic(context: Context, fileName: String, lrcContent: String): Boolean {
        return try {
            val file = getLrcFile(context, fileName)
            file.writeText(lrcContent, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "写入 LRC 失败: ${e.message}", e)
            false
        }
    }

    /**
     * 通过文件名读取歌词内容
     *
     * @param fileName 显示名，如 "Artist - Title.lrc"
     * @return 文件内容；未找到或读取失败返回 null
     *
     * @deprecated 请使用 {@link LyricsSaveManager#readLrcFile} 从 Documents/Unicorn/Lyrics/（SAF）读取。
     *             应用私有外部目录存储已废弃，仅作为向后兼容保留。
     */
    @Deprecated("Use LyricsSaveManager.readLrcFile instead")
    fun readLrcFromMusic(context: Context, fileName: String): String? {
        val file = getLrcFile(context, fileName)
        return try {
            if (file.exists()) file.readText(Charsets.UTF_8) else null
        } catch (e: Exception) {
            Log.e(TAG, "读取 LRC 失败: ${e.message}")
            LogWriter.writeError(TAG, "读取 LRC 失败: ${e.message}", e)
            null
        }
    }

    /**
     * 删除指定文件名的歌词文件
     *
     * @return 是否成功删除到至少一个文件
     *
     * @deprecated 请使用 {@link LyricsSaveManager} 的 Documents/Unicorn/Lyrics/（SAF）操作替代。
     *             应用私有外部目录存储已废弃，仅作为向后兼容保留。
     */
    @Deprecated("Use LyricsSaveManager instead")
    fun deleteLrcFromMusic(context: Context, fileName: String): Boolean {
        val file = getLrcFile(context, fileName)
        return file.exists() && file.delete()
    }

    // ===================== 歌词加载 =====================

    /**
     * 根据音频文件路径加载对应的歌词数据
     * 歌词文件按 "<音频文件名>.lrc" 存放在应用私有外部目录下
     *
     * @param audioPath 音频文件完整路径，如 "/storage/music/Artist - Title.flac"
     * @return 歌词行列表；未找到返回 null
     *
     * @deprecated 请使用 {@link LyricsSaveManager#readLrcFile} 从 Documents/Unicorn/Lyrics/（SAF）读取，
     *             配合 {@link #parseLrcContent} 解析。应用私有外部目录存储已废弃，仅作为向后兼容保留。
     */
    @Deprecated("Use LyricsSaveManager.readLrcFile + parseLrcContent instead")
    fun loadLrcFromAudioPath(context: Context, audioPath: String): List<LrcRow>? {
        if (audioPath.isBlank()) return null

        val fileName = File(audioPath).nameWithoutExtension.toSimpleCustom() + ".lrc"
        val content = readLrcFromMusic(context, fileName) ?: run {
            Log.i(TAG, "歌词文件不存在: $fileName")
            return null
        }
        return parseLrcContent(context, content)
    }

    // ===================== 解析 =====================

    /**
     * 解析 LRC 文本内容（公共方法，接受字符串输入）。
     *
     * 注意：LrcDataBuilder 要求传入 File，因此将内容写入缓存临时文件后再解析，
     * 解析完成即删除。
     */
    fun parseLrcContent(context: Context, content: String): List<LrcRow>? {
        val tmpFile = File.createTempFile("lrc_parse", ".lrc", context.cacheDir)
        return try {
            tmpFile.writeText(content, Charsets.UTF_8)
            parseLrcFile(tmpFile)
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "解析 LRC 内容失败: ${e.message}", e)
            null
        } finally {
            tmpFile.delete()
        }
    }

    /**
     * 解析 LRC 文件
     * 先尝试使用 LrcDataBuilder，失败则使用手动解析
     * 若文件中存在 [offset:±xxx] 标签，则对所有行的时间戳做补偿：DISPLAY_TIME = LRC_TIME + offset
     */
    private fun parseLrcFile(lrcFile: File): List<LrcRow>? {
        // 先扫描 offset 标签
        val offset = parseOffsetFromLrc(lrcFile)
        if (offset != 0) {
            Log.i(TAG, "检测到 offset 标签: ${offset}ms")
        }

        // 方法1: 尝试使用 LrcDataBuilder
        try {
            val result = LrcDataBuilder().Build(lrcFile)
            if (result != null && result.isNotEmpty()) {
                Log.i(TAG, "LrcDataBuilder 解析成功: ${result.size} 行")
                return if (offset != 0) applyOffsetToRows(result, offset) else result
            }
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "LrcDataBuilder 解析失败: ${e.message}")
        }

        // 方法2: 手动解析 LRC 文件（offset 在 tag 行过滤阶段自然剔除）
        return parseLrcManually(lrcFile, offset)
    }

    /**
     * 扫描 LRC 文件中的 [offset:±xxx] 标签，返回补偿毫秒数
     * 正偏移 → 歌词延后显示；负偏移 → 歌词提前显示
     *
     * 标准语义：DISPLAY_TIME = LRC_TIME + offset
     */
    private fun parseOffsetFromLrc(lrcFile: File): Int {
        try {
            lrcFile.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { raw ->
                        val trimmed = raw.trim()
                        val m = LRC_OFFSET_PATTERN.matcher(trimmed)
                        if (m.matches()) {
                            return m.group(1)?.toIntOrNull() ?: 0
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "解析 offset 标签失败: ${e.message}")
        }
        return 0
    }

    /**
     * 对已解析的 LrcRow 列表应用时间偏移
     * 过滤 offset 标签行，调整每行的 currentRowTime
     */
    private fun applyOffsetToRows(rows: List<LrcRow>, offset: Int): List<LrcRow> {
        return rows.filter { row ->
            // 过滤掉 offset 标签行本身
            !LRC_OFFSET_PATTERN.matcher(row.rowData.trim()).matches()
        }.map { row ->
            val newTime = (row.CurrentRowTime + offset).coerceAtLeast(0)
            LrcRow(row.rowData, row.TimeText, newTime)
        }.sortedBy { it.CurrentRowTime }
    }

    /**
     * 手动解析 LRC 文件格式
     * 标准 LRC 格式: [mm:ss.xxx]歌词文本（3位毫秒）
     *
     * @param offset 毫秒偏移量，会被加到每一行的 currentRowTime 上
     */
    private fun parseLrcManually(lrcFile: File, offset: Int = 0): List<LrcRow>? {
        val lrcRows = mutableListOf<LrcRow>()

        try {
            // 尝试 UTF-8 编码
            val reader = BufferedReader(InputStreamReader(lrcFile.inputStream(), "UTF-8"))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                line?.let { lrcLine ->
                    val trimmed = lrcLine.trim()
                    // 跳过 offset 标签行（它们不含歌词时间戳，不应被解析为歌词行）
                    if (LRC_OFFSET_PATTERN.matcher(trimmed).matches()) return@let
                    parseLrcLine(lrcLine)?.let { row ->
                        val newTime = (row.CurrentRowTime + offset).coerceAtLeast(0)
                        lrcRows.add(LrcRow(row.rowData, row.TimeText, newTime))
                    }
                }
            }
            reader.close()

            if (lrcRows.isEmpty()) {
                LogWriter.writeError(TAG, "手动解析 LRC 文件失败: 无有效行")
                return null
            }

            // 按时间排序
            return lrcRows.sortedBy { it.CurrentRowTime }
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "手动解析 LRC 文件失败: ${e.message}", e)
            return null
        }
    }

    /**
     * 解析单行 LRC 文本（单行单时间戳）
     * LrcRow 构造函数: LrcRow(rowData, timeText, currentRowTime)
     *
     * 支持2位(.xx)和3位(.xxx)毫秒格式：
     * - .xx (2位) → 乘以10转为毫秒，如 .15 → 150ms
     * - .xxx (3位) → 直接为毫秒值，如 .156 → 156ms
     */
    private fun parseLrcLine(line: String): LrcRow? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val matcher = LRC_TIME_PATTERN.matcher(trimmed)
        if (!matcher.find()) return null

        val minutes = matcher.group(1)?.toLongOrNull() ?: return null
        val seconds = matcher.group(2)?.toLongOrNull() ?: return null
        val millisStr = matcher.group(3) ?: return null
        val milliseconds = millisStr.toLongOrNull() ?: return null

        // 根据毫秒位数正确转换：2位需×10，3位直接使用
        val timeMs = when (millisStr.length) {
            2 -> (minutes * 60 + seconds) * 1000 + milliseconds * 10
            3 -> (minutes * 60 + seconds) * 1000 + milliseconds
            else -> return null
        }
        val timeText = matcher.group(0) ?: return null // 完整的时间戳文本 [mm:ss.xxx]
        // 整行保留（含时间戳）；开关关闭时由调用方去掉时间戳
        return LrcRow(trimmed, timeText, timeMs)
    }
}
