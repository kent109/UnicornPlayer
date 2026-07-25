package com.unicorn.player.util

import android.content.Context
import android.util.Log
import com.unicorn.player.UnicornPlayerApplication
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 错误日志写入器
 * 将错误日志写入外部存储的 error_logs 目录
 */
object LogWriter {

    private const val TAG = "LogWriter"
    private const val LOG_DIR_NAME = "error_logs"
    private const val LOG_FILE_NAME = "error_log.txt"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB 最大文件大小

    /**
     * 写入错误日志
     * @param tag 日志标签
     * @param message 日志消息
     * @param throwable 异常（可选）
     */
    fun writeError(tag: String, message: String, throwable: Throwable? = null) {
        // 同时输出到 Logcat
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }

        // 写入文件
        writeToFile(tag, message, throwable)
    }

    /**
     * 写入文件
     */
    private fun writeToFile(tag: String, message: String, throwable: Throwable?) {
        try {
            val logFile = getLogFile() ?: return

            // 检查文件大小，超过则重命名旧文件
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                rotateLogFile(logFile)
            }

            val timestamp =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val logLine = buildString {
                append("[$timestamp] ")
                append("[ERROR] ")
                append("[$tag] ")
                append(message)
                if (throwable != null) {
                    append("\nException: ${throwable.javaClass.simpleName}: ${throwable.message}")
                    append("\nStackTrace: ${throwable.stackTraceToString()}")
                }
                append("\n")
            }

            FileWriter(logFile, true).use { writer ->
                writer.write(logLine)
                writer.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG, "写入日志文件失败: ${e.message}")
        }
    }

    /**
     * 轮转日志文件
     */
    private fun rotateLogFile(oldFile: File) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val saveName = "error_log_$timestamp.txt"
        val saveFile = File(oldFile.parentFile, saveName)
        oldFile.renameTo(saveFile)

        // 只保留最近 5 个保存文件
        val saveFiles = oldFile.parentFile?.listFiles { _, name ->
            name.startsWith("error_log_") && name.endsWith(".txt")
        }?.sortedByDescending { it.lastModified() }

        saveFiles?.drop(5)?.forEach { it.delete() }
    }

    /**
     * 获取日志文件
     */
    private fun getLogFile(): File? {
        val context = UnicornPlayerApplication.getInstance() ?: return null

        val logDir = getLogDirectory(context) ?: return null
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        return File(logDir, LOG_FILE_NAME)
    }

    /**
     * 获取日志目录
     */
    private fun getLogDirectory(context: Context): File? {
        val externalDir = context.getExternalFilesDir(null) ?: return null
        return File(externalDir, LOG_DIR_NAME)
    }

    /**
     * 获取日志文件路径（供外部查看）
     */
    fun getLogFilePath(): String? {
        val context = UnicornPlayerApplication.getInstance() ?: return null
        val logDir = getLogDirectory(context) ?: return null
        val logFile = File(logDir, LOG_FILE_NAME)
        return if (logFile.exists()) logFile.absolutePath else null
    }

    /**
     * 清除所有日志文件
     */
    fun clearLogs() {
        val context = UnicornPlayerApplication.getInstance() ?: return
        val logDir = getLogDirectory(context) ?: return
        if (logDir.exists()) {
            logDir.listFiles()?.forEach { it.delete() }
        }
    }
}