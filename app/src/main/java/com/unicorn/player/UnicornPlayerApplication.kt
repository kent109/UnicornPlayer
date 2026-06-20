package com.unicorn.player

import android.app.Application
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自定义Application类，实现全局异常捕获
 * 当应用出现未捕获的异常时，将错误信息写入外部存储
 */
class UnicornPlayerApplication : Application() {

    private lateinit var defaultHandler: Thread.UncaughtExceptionHandler

    override fun onCreate() {
        super.onCreate()

        // 保存默认的异常处理器
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()!!

        // 设置自定义的异常处理器
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 将异常信息写入文件
            saveCrashLog(throwable)

            // 调用默认的异常处理器（强制退出应用）
            defaultHandler.uncaughtException(thread, throwable)
        }
    }

    /**
     * 保存崩溃日志到外部存储
     */
    private fun saveCrashLog(throwable: Throwable) {
        try {
            // 获取外部存储的崩溃日志目录
            val crashDir = File(getExternalFilesDir(null), "crash_logs")
            if (!crashDir.exists()) {
                crashDir.mkdirs()
            }

            // 创建带时间戳的日志文件名
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val logFile = File(crashDir, "crash_$timestamp.log")

            // 写入异常信息
            FileWriter(logFile).use { writer ->
                writer.write("========== UnicornPlayer Crash Log ==========\n")
                writer.write(
                    "崩溃时间: ${
                        SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                        ).format(Date())
                    }\n"
                )
                writer.write("设备信息: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
                writer.write("Android版本: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
                writer.write(
                    "应用版本: ${
                        packageManager.getPackageInfo(
                            packageName,
                            0
                        ).versionName
                    }\n"
                )
                writer.write("=============================================\n\n")

                // 写入完整的异常堆栈
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                writer.write("异常类型: ${throwable.javaClass.name}\n")
                writer.write("异常信息: ${throwable.message}\n\n")
                writer.write("堆栈跟踪:\n${sw.toString()}\n")

                // 写入异常原因（如果有）
                var cause = throwable.cause
                var causeIndex = 1
                while (cause != null) {
                    writer.write("\n--- 原因 $causeIndex ---\n")
                    val causeSw = StringWriter()
                    val causePw = PrintWriter(causeSw)
                    cause.printStackTrace(causePw)
                    writer.write("异常类型: ${cause.javaClass.name}\n")
                    writer.write("异常信息: ${cause.message}\n\n")
                    writer.write("堆栈跟踪:\n${causeSw.toString()}\n")
                    cause = cause.cause
                    causeIndex++
                }

                writer.write("\n========== End of Crash Log ==========\n")
            }

        } catch (e: Exception) {
            // 如果保存日志失败，避免再次崩溃，只打印到控制台
            e.printStackTrace()
        }
    }
}
