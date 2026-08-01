package com.unicorn.player.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 版本检查与 APK 下载工具类
 * 从服务器获取版本信息，对比当前版本，下载新版 APK 文件
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val VERSION_URL =
        "https://raw.giteeusercontent.com/kent0/UnicornPlayer/raw/master/version.json"

    private const val PREFS_NAME = "update_prefs"
    private const val KEY_DOWNLOADED_VERSION_CODE = "downloaded_version_code"
    private const val KEY_DOWNLOADED_FILE_NAME = "downloaded_file_name"

    private const val DOWNLOAD_DIR_NAME = "update"

    private val client = OkHttpClient()
    private val gson = Gson()

    /**
     * 版本信息数据类
     */
    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val updateLog: String
    )

    /**
     * 检查结果封装
     */
    sealed class CheckResult {
        /** 有新版本可用 */
        data class UpdateAvailable(val info: VersionInfo) : CheckResult()

        /** 已是最新版本 */
        object AlreadyLatest : CheckResult()

        /** 检查失败 */
        data class Failed(val message: String) : CheckResult()
    }

    /**
     * 下载回调接口（回调在子线程执行，UI 操作需切回主线程）
     */
    interface DownloadCallback {
        /**
         * 下载进度
         * @param percent 进度百分比（0-100），若服务器未返回 Content-Length 则为 -1
         * @param downloadedBytes 已下载字节数
         */
        fun onProgress(percent: Int, downloadedBytes: Long)

        /** 下载成功 */
        fun onSuccess(file: File)

        /** 下载失败 */
        fun onFailure(message: String)
    }

    // ==================== 版本检查 ====================

    /**
     * 检查服务器是否有新版本
     * @param context 上下文
     * @param callback 结果回调（在子线程执行）
     */
    fun checkForUpdate(context: Context, callback: (CheckResult) -> Unit) {
        val currentVersionCode = getCurrentVersionCode(context)

        val request =
            Request.Builder().url(VERSION_URL).header("User-Agent", "UnicornPlayer/1.0").build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                Log.e(TAG, "检查更新请求失败: ${e.message}")
                callback(CheckResult.Failed("网络请求失败: ${e.message}"))
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) return
                if (!response.isSuccessful) {
                    callback(CheckResult.Failed("HTTP 错误: ${response.code}"))
                    return
                }

                try {
                    val json = response.body?.string()
                    if (json.isNullOrBlank()) {
                        callback(CheckResult.Failed("返回数据为空"))
                        return
                    }

                    val info = gson.fromJson(json, VersionInfo::class.java)
                    if (info == null) {
                        callback(CheckResult.Failed("解析版本信息失败"))
                        return
                    }

                    Log.i(TAG, "当前版本: $currentVersionCode, 服务器版本: ${info.versionCode}")

                    if (info.versionCode > currentVersionCode) {
                        callback(CheckResult.UpdateAvailable(info))
                    } else {
                        callback(CheckResult.AlreadyLatest)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析版本信息失败: ${e.message}", e)
                    callback(CheckResult.Failed("解析失败: ${e.message}"))
                }
            }
        })
    }

    /**
     * 获取当前应用的 versionCode
     */
    private fun getCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        } catch (e: Exception) {
            Log.e(TAG, "获取当前版本号失败: ${e.message}")
            0L
        }
    }

    // ==================== 文件管理 ====================

    /**
     * 获取下载目录（外存储应用私有目录下的 update 子目录）
     */
    fun getDownloadDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), DOWNLOAD_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 根据下载 URL 获取 APK 文件对象
     * @param context 上下文
     * @param downloadUrl 下载地址
     * @return APK 文件对象
     */
    fun getApkFile(context: Context, downloadUrl: String): File {
        val fileName = downloadUrl.substringAfterLast("/")
        return File(getDownloadDir(context), fileName)
    }

    /**
     * 获取已下载的文件名
     */
    fun getDownloadedFileName(context: Context): String? {
        return getPrefs(context).getString(KEY_DOWNLOADED_FILE_NAME, null)
    }

    /**
     * 获取已下载文件的版本号
     */
    fun getDownloadedVersionCode(context: Context): Int {
        return getPrefs(context).getInt(KEY_DOWNLOADED_VERSION_CODE, 0)
    }

    /**
     * 保存下载文件信息
     */
    fun saveDownloadedFileInfo(context: Context, versionCode: Int, fileName: String) {
        getPrefs(context).edit {
            putInt(KEY_DOWNLOADED_VERSION_CODE, versionCode).putString(
                KEY_DOWNLOADED_FILE_NAME, fileName
            )
        }
    }

    /**
     * 清除下载文件信息
     */
    fun clearDownloadedFileInfo(context: Context) {
        getPrefs(context).edit {
            remove(KEY_DOWNLOADED_VERSION_CODE).remove(KEY_DOWNLOADED_FILE_NAME)
        }
    }

    /**
     * 删除本地下载的 APK 文件（如果存在）
     */
    fun deleteDownloadedFile(context: Context) {
        val fileName = getDownloadedFileName(context) ?: return
        val file = File(getDownloadDir(context), fileName)
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "已删除本地 APK 文件: ${file.absolutePath}")
        }
        clearDownloadedFileInfo(context)
    }

    /**
     * 检查本地是否已有指定版本的 APK 文件
     * @return 如果本地文件存在且版本匹配则返回 File，否则返回 null
     */
    fun getLocalApkFile(context: Context, versionCode: Int, downloadUrl: String): File? {
        val fileName = downloadUrl.substringAfterLast("/")
        val savedFileName = getDownloadedFileName(context)
        val savedVersionCode = getDownloadedVersionCode(context)

        // 文件名和版本号都匹配才认为是同一个文件
        if (savedFileName != fileName || savedVersionCode != versionCode) {
            return null
        }

        val file = File(getDownloadDir(context), fileName)
        return if (file.exists() && file.length() > 0) file else null
    }

    // ==================== 下载 APK ====================

    /**
     * 下载 APK 文件（同步方法，需在子线程或协程中调用）
     * @param context 上下文
     * @param downloadUrl 下载地址
     * @param callback 下载回调（在子线程执行）
     * @return OkHttp Call 对象，可用于取消下载
     */
    fun downloadApk(context: Context, downloadUrl: String, callback: DownloadCallback): Call {
        val apkFile = getApkFile(context, downloadUrl)
        // 下载时先写入 .tmp 临时文件，成功后再重命名
        val tmpFile = File(apkFile.parent, "${apkFile.name}.tmp")

        // 下载前清理所有 .tmp 临时文件
        getDownloadDir(context).listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                file.delete()
            }
        }
        // 如果目标文件已存在，先删除
        if (apkFile.exists()) {
            apkFile.delete()
        }

        val request = Request.Builder().url(downloadUrl).header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        ).header("Accept", "*/*").header("Referer", "https://gitee.com/").build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                Log.e(TAG, "下载 APK 失败: ${e.message}")
                // 删除临时文件
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }
                callback.onFailure("下载失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) return
                if (!response.isSuccessful) {
                    if (tmpFile.exists()) {
                        tmpFile.delete()
                    }
                    callback.onFailure("HTTP 错误: ${response.code}")
                    return
                }

                try {
                    val body = response.body
                    if (body == null) {
                        callback.onFailure("响应体为空")
                        return
                    }

                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L

                    // 初始进度
                    callback.onProgress(0, 0)

                    FileOutputStream(tmpFile).use { outputStream ->
                        body.byteStream().use { inputStream ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                if (call.isCanceled()) {
                                    outputStream.flush()
                                    tmpFile.delete()
                                    return
                                }

                                outputStream.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                // 计算并回调进度
                                if (totalBytes > 0) {
                                    val percent = (downloadedBytes * 100 / totalBytes).toInt()
                                    callback.onProgress(percent.coerceIn(0, 100), downloadedBytes)
                                } else {
                                    // 服务器未返回 Content-Length，传递 -1 表示未知百分比
                                    callback.onProgress(-1, downloadedBytes)
                                }
                            }
                            outputStream.flush()
                        }
                    }

                    // 下载完成，将临时文件重命名为最终文件名
                    if (apkFile.exists()) {
                        apkFile.delete()
                    }
                    if (tmpFile.renameTo(apkFile)) {
                        // 重命名成功，最终进度 100%
                        callback.onProgress(100, downloadedBytes)
                        callback.onSuccess(apkFile)
                    } else {
                        // 重命名失败，尝试直接返回 tmpFile
                        Log.w(TAG, "重命名临时文件失败，返回临时文件")
                        callback.onProgress(100, downloadedBytes)
                        callback.onSuccess(tmpFile)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "写入 APK 文件失败: ${e.message}", e)
                    if (tmpFile.exists()) {
                        tmpFile.delete()
                    }
                    callback.onFailure("写入文件失败: ${e.message}")
                }
            }
        })

        return call
    }

    // ==================== 私有方法 ====================

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
