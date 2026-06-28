package com.unicorn.player.util

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
            LogWriter.writeError(TAG, "歌词文件不存在: ${lrcFile.absolutePath}")
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
     */
    private fun parseLrcFile(lrcFile: File): List<LrcRow>? {
        // 方法1: 尝试使用 LrcDataBuilder (可能参数类型不对)
        try {
            val result = LrcDataBuilder().Build(lrcFile)
            if (result != null && result.isNotEmpty()) {
                LogWriter.writeError(TAG, "LrcDataBuilder 解析成功: ${result.size} 行")
                return result
            }
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "LrcDataBuilder 解析失败: ${e.message}")
        }

        // 方法2: 手动解析 LRC 文件
        return parseLrcManually(lrcFile)
    }

    /**
     * 手动解析 LRC 文件格式
     * 标准 LRC 格式: [mm:ss.xx]歌词文本
     */
    private fun parseLrcManually(lrcFile: File): List<LrcRow>? {
        val lrcRows = mutableListOf<LrcRow>()

        try {
            // 尝试 UTF-8 编码
            val reader = BufferedReader(InputStreamReader(lrcFile.inputStream(), "UTF-8"))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                line?.let { lrcLine ->
                    parseLrcLine(lrcLine)?.let { row -> lrcRows.add(row) }
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
     * 解析单行 LRC 文本
     * LrcRow 构造函数: LrcRow(rowData, timeText, currentRowTime)
     */
    private fun parseLrcLine(line: String): LrcRow? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val matcher = LRC_TIME_PATTERN.matcher(trimmed)
        if (!matcher.find()) return null

        val minutes = matcher.group(1)?.toLongOrNull() ?: return null
        val seconds = matcher.group(2)?.toLongOrNull() ?: return null
        val milliseconds = matcher.group(3)?.toLongOrNull() ?: return null

        val timeMs = (minutes * 60 + seconds) * 1000 + milliseconds * 10 // 转换为毫秒
        val timeText = matcher.group(0) ?: return null // 完整的时间戳文本 [mm:ss.xx]
        val text = trimmed.replace(LRC_TIME_PATTERN.toRegex(), "").trim()

        return LrcRow(text, timeText, timeMs)
    }
}
