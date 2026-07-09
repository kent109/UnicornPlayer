package com.unicorn.player.util

import android.util.Log
import com.hw.lrcviewlib.LrcDataBuilder
import com.hw.lrcviewlib.LrcRow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * 歌词加载工具类
 * 负责从音频文件同目录加载 .lrc 歌词文件
 */
object LrcHelper {

    private const val TAG = "LrcHelper"

    // LRC时间戳格式: [mm:ss.xx] 或 [mm:ss.xxx]
    private val LRC_TIME_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})]")

    // LRC offset 标签格式: [offset:±500]，单位毫秒（正=提前，负=延后）
    private val LRC_OFFSET_PATTERN = Pattern.compile("^\\[offset:\\s*([+-]?\\d+)\\s*]\\s*$")

    /**
     * 根据音频文件路径加载对应的歌词数据
     * 歌词文件应与音频文件在同一目录下，同名但扩展名为 .lrc
     *
     * @param audioPath 音频文件路径
     * @return 歌词行列表，如果未找到歌词文件则返回 null
     */
    fun loadLrcFromAudioPath(audioPath: String): List<LrcRow>? {
        if (audioPath.isBlank()) return null

        val audioFile = File(audioPath)
        val lrcFile = File(audioFile.parent, audioFile.nameWithoutExtension + ".lrc")

        if (!lrcFile.exists()) {
            Log.i(TAG, "歌词文件不存在: ${lrcFile.absolutePath}")
            return null
        }

        return parseLrcFile(lrcFile)
    }

    /**
     * 从指定路径加载歌词文件
     *
     * @param lrcPath 歌词文件路径
     * @return 歌词行列表，如果加载失败则返回 null
     */
    fun loadLrcFromPath(lrcPath: String): List<LrcRow>? {
        if (lrcPath.isBlank()) return null

        val lrcFile = File(lrcPath)
        if (!lrcFile.exists()) {
            LogWriter.writeError(TAG, "歌词文件不存在: $lrcPath")
            return null
        }

        return parseLrcFile(lrcFile)
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

        // 方法1: 尝试使用 LrcDataBuilder (可能参数类型不对)
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
            lrcFile.inputStream().bufferedReader(Charsets.UTF_8)?.use { reader ->
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
        }.sortedBy { it.currentRowTime }
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
            return lrcRows.sortedBy { it.currentRowTime }
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
