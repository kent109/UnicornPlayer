package com.unicorn.player.util

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.unicorn.player.R
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * 版本检查与 APK 下载辅助类
 * 封装完整的检查更新、下载、安装流程，UI 对话框管理
 */
class UpdateHelper(private val activity: AppCompatActivity) {

    interface UpdateCallback {
        /** 已是最新版本 */
        fun onAlreadyLatest()

        /** 检查失败 */
        fun onCheckFailed(message: String)
    }

    private var isCheckingUpdate = false
    private var loadingDialog: AlertDialog? = null
    private var updateDialog: AlertDialog? = null
    private var downloadCall: Call? = null
    private var pendingInstallFile: File? = null
    private var currentVersionInfo: VersionInfo? = null

    // 统一弹窗视图引用
    private var versionInfoGroup: View? = null
    private var progressGroup: View? = null
    private var progressBar: android.widget.ProgressBar? = null
    private var tvProgress: TextView? = null
    private var btnUpdate: com.google.android.material.button.MaterialButton? = null
    private var btnCancel: com.google.android.material.button.MaterialButton? = null
    private var tvTitle: TextView? = null

    // 上次更新的进度百分比，用于节流（仅在百分比变化时更新 UI）
    private var lastProgress = -1

    companion object {
        private const val TAG = "UpdateHelper"

        private const val VERSION_URL =
            "https://raw.giteeusercontent.com/kent0/UnicornPlayer/raw/master/version.json"

        private const val PREFS_NAME = "update_prefs"
        private const val KEY_DOWNLOADED_VERSION_CODE = "downloaded_version_code"
        private const val KEY_DOWNLOADED_FILE_NAME = "downloaded_file_name"
        private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
        private const val DOWNLOAD_DIR_NAME = "update"

        private val client = OkHttpClient()
        private val gson = Gson()

        /**
         * 获取 SharedPreferences 实例
         */
        internal fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        /**
         * 是否启用自动检查更新（默认启用）
         */
        fun isAutoCheckEnabled(context: Context): Boolean {
            return getPrefs(context).getBoolean(KEY_AUTO_CHECK_UPDATE, true)
        }

        /**
         * 设置是否启用自动检查更新
         */
        fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
            getPrefs(context).edit { putBoolean(KEY_AUTO_CHECK_UPDATE, enabled) }
        }
    }

    // ==================== 数据类 ====================

    data class VersionInfo(
        @SerializedName("versionCode")
        val versionCode: Int,
        @SerializedName("versionName")
        val versionName: String,
        @SerializedName("downloadUrl")
        val downloadUrl: String,
        @SerializedName("updateLog")
        val updateLog: String
    )

    interface DownloadCallback {
        /**
         * 下载进度
         * @param percent 进度百分比（0-100），若服务器未返回 Content-Length 则为 -1
         * @param downloadedBytes 已下载字节数
         */
        fun onProgress(percent: Int, downloadedBytes: Long)
        fun onSuccess(file: File)
        fun onFailure(message: String)
    }

    // ==================== 生命周期 ====================

    /**
     * Activity.onResume 中调用，处理安装权限返回后的逻辑
     */
    fun onResume() {
        val pendingFile = pendingInstallFile
        if (pendingFile != null && canRequestPackageInstalls()) {
            pendingInstallFile = null
            launchInstall(pendingFile)
        }
    }

    /**
     * Activity.onDestroy 中调用，清理资源
     */
    fun onDestroy() {
        downloadCall?.cancel()
        loadingDialog?.dismiss()
        updateDialog?.dismiss()
    }

    // ==================== 版本检查入口 ====================

    /**
     * 检查更新（防止连续点击）
     * @param silent 静默模式时不显示 loading 弹窗（用于启动时自动检查）
     */
    fun checkForUpdate(callback: UpdateCallback? = null, silent: Boolean = false) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true

        if (!silent) {
            showLoadingDialog()
        }

        val currentVersionCode = getCurrentVersionCode(activity)

        val request = Request.Builder()
            .url(VERSION_URL)
            .header("User-Agent", "UnicornPlayer/1.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                Log.e(TAG, "检查更新请求失败: ${e.message}")
                activity.runOnUiThread {
                    loadingDialog?.dismiss()
                    isCheckingUpdate = false
                    val msg = "网络请求失败: ${e.message}"
                    callback?.onCheckFailed(msg)
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (call.isCanceled()) return
                if (!response.isSuccessful) {
                    activity.runOnUiThread {
                        loadingDialog?.dismiss()
                        isCheckingUpdate = false
                        val msg = "HTTP 错误: ${response.code}"
                        callback?.onCheckFailed(msg)
                        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                try {
                    val json = response.body?.string()
                    if (json.isNullOrBlank()) {
                        activity.runOnUiThread {
                            loadingDialog?.dismiss()
                            isCheckingUpdate = false
                            val msg = "返回数据为空"
                            callback?.onCheckFailed(msg)
                            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                        }
                        return
                    }

                    val info = gson.fromJson(json, VersionInfo::class.java)
                    if (info == null) {
                        activity.runOnUiThread {
                            loadingDialog?.dismiss()
                            isCheckingUpdate = false
                            val msg = "解析版本信息失败"
                            callback?.onCheckFailed(msg)
                            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                        }
                        return
                    }

                    Log.i(TAG, "当前版本: $currentVersionCode, 服务器版本: ${info.versionCode}")

                    activity.runOnUiThread {
                        loadingDialog?.dismiss()
                        isCheckingUpdate = false

                        if (info.versionCode > currentVersionCode) {
                            handleUpdateAvailable(info)
                        } else {
                            callback?.onAlreadyLatest()
                            Toast.makeText(
                                activity,
                                R.string.update_already_latest,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析版本信息失败: ${e.message}", e)
                    activity.runOnUiThread {
                        loadingDialog?.dismiss()
                        isCheckingUpdate = false
                        val msg = "解析失败: ${e.message}"
                        callback?.onCheckFailed(msg)
                        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    // ==================== 发现新版本处理 ====================

    private fun handleUpdateAvailable(info: VersionInfo) {
        currentVersionInfo = info

        val localFile = getLocalApkFile(activity, info.versionCode, info.downloadUrl)
        if (localFile != null) {
            launchInstall(localFile)
        } else {
            showNewVersionDialog(info)
        }
    }

    /**
     * 显示统一弹窗 - 发现新版本（版本信息 + 更新/取消按钮）
     */
    private fun showNewVersionDialog(info: VersionInfo) {
        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.dialog_update, null)

        // 初始化视图引用
        tvTitle = view.findViewById(R.id.tvTitle)
        versionInfoGroup = view.findViewById(R.id.versionInfoGroup)
        progressGroup = view.findViewById(R.id.progressGroup)
        progressBar = view.findViewById(R.id.progressBar)
        tvProgress = view.findViewById(R.id.tvProgress)
        btnUpdate = view.findViewById(R.id.btnUpdate)
        btnCancel = view.findViewById(R.id.btnCancel)

        // 设置版本信息
        view.findViewById<TextView>(R.id.tvVersion).text =
            activity.getString(R.string.update_version_label, info.versionName)

        val tvUpdateLog = view.findViewById<TextView>(R.id.tvUpdateLog)
        if (info.updateLog.isNotBlank()) {
            tvUpdateLog.text = info.updateLog
        } else {
            tvUpdateLog.text = "暂无更新说明"
        }

        // 初始状态：显示版本信息，隐藏进度
        versionInfoGroup?.visibility = View.VISIBLE
        progressGroup?.visibility = View.GONE
        btnUpdate?.visibility = View.VISIBLE

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(true)
            .create()
        updateDialog = dialog

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.8).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        btnCancel?.setOnClickListener {
            dialog.dismiss()
            updateDialog = null
        }

        btnUpdate?.setOnClickListener {
            startDownload(info)
        }

        dialog.show()
    }

    /**
     * 在统一弹窗中切换到下载进度状态并开始下载
     */
    private fun startDownload(info: VersionInfo) {
        // 切换到下载进度 UI
        versionInfoGroup?.visibility = View.GONE
        progressGroup?.visibility = View.VISIBLE
        btnUpdate?.visibility = View.GONE
        tvTitle?.text = activity.getString(R.string.update_downloading)

        // 弹窗不可取消（下载中）
        updateDialog?.setCancelable(false)

        // 更新取消按钮行为（取消下载）
        btnCancel?.setOnClickListener {
            downloadCall?.cancel()
            downloadCall = null
            updateDialog?.dismiss()
            updateDialog = null
            deleteDownloadedFile(activity)
        }

        lastProgress = -1
        progressBar?.setProgress(0, false)

        downloadCall = downloadApk(
            activity, info.downloadUrl,
            object : DownloadCallback {
                override fun onProgress(percent: Int, downloadedBytes: Long) {
                    // percent=-1 表示服务器未提供 Content-Length，使用不确定进度模式
                    if (percent == -1) {
                        activity.runOnUiThread {
                            progressBar?.apply {
                                if (!isIndeterminate) {
                                    isIndeterminate = true
                                }
                            }
                            tvProgress?.text = formatDownloadProgress(downloadedBytes)
                        }
                        return
                    }
                    // 仅在百分比变化时更新 UI，避免 UI 线程被海量 Runnable 淹没
                    if (percent == lastProgress) return
                    lastProgress = percent
                    activity.runOnUiThread {
                        progressBar?.apply {
                            if (isIndeterminate) {
                                isIndeterminate = false
                            }
                            setProgress(percent, false)
                        }
                        tvProgress?.text =
                            activity.getString(R.string.update_downloaded, percent)
                    }
                }

                override fun onSuccess(file: File) {
                    activity.runOnUiThread {
                        progressBar?.apply {
                            if (isIndeterminate) {
                                isIndeterminate = false
                            }
                            setProgress(100, false)
                        }
                        tvProgress?.text = activity.getString(R.string.update_downloaded, 100)
                        tvProgress?.postDelayed({
                            updateDialog?.dismiss()
                            updateDialog = null
                            val fileName = info.downloadUrl.substringAfterLast("/")
                            saveDownloadedFileInfo(
                                activity,
                                info.versionCode,
                                fileName
                            )
                            launchInstall(file)
                        }, 500)
                    }
                }

                override fun onFailure(message: String) {
                    activity.runOnUiThread {
                        updateDialog?.dismiss()
                        updateDialog = null
                        Toast.makeText(
                            activity,
                            message,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    // ==================== 安装 APK ====================

    private fun launchInstall(file: File) {
        try {
            if (canRequestPackageInstalls()) {
                installApk(file)
            } else {
                pendingInstallFile = file
                requestInstallPermission()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun installApk(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    file
                )
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestInstallPermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${activity.packageName}".toUri()
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                activity,
                R.string.update_install_permission_denied,
                Toast.LENGTH_SHORT
            ).show()
            pendingInstallFile = null
        }
    }

    private fun canRequestPackageInstalls(): Boolean {
        return try {
            activity.packageManager.canRequestPackageInstalls()
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 下载实现 ====================

    private fun downloadApk(
        context: Context,
        downloadUrl: String,
        callback: DownloadCallback
    ): Call {
        val apkFile = getApkFile(context, downloadUrl)
        val tmpFile = File(apkFile.parent, "${apkFile.name}.tmp")

        // 下载前清理所有 .tmp 临时文件
        getDownloadDir(context).listFiles()?.forEach { file ->
            if (file.name.endsWith(".tmp")) {
                file.delete()
            }
        }
        if (apkFile.exists()) {
            apkFile.delete()
        }

        val request = Request.Builder()
            .url(downloadUrl)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "*/*")
            .header("Referer", "https://gitee.com/")
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                Log.e(TAG, "下载 APK 失败: ${e.message}")
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

                                if (totalBytes > 0) {
                                    val percent = (downloadedBytes * 100 / totalBytes).toInt()
                                    callback.onProgress(percent.coerceIn(0, 100), downloadedBytes)
                                } else {
                                    callback.onProgress(-1, downloadedBytes)
                                }
                            }
                            outputStream.flush()
                        }
                    }

                    if (apkFile.exists()) {
                        apkFile.delete()
                    }
                    if (tmpFile.renameTo(apkFile)) {
                        callback.onProgress(100, downloadedBytes)
                        callback.onSuccess(apkFile)
                    } else {
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

    // ==================== 文件管理 ====================

    private fun getDownloadDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), DOWNLOAD_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getApkFile(context: Context, downloadUrl: String): File {
        val fileName = downloadUrl.substringAfterLast("/")
        return File(getDownloadDir(context), fileName)
    }

    private fun getLocalApkFile(
        context: Context,
        versionCode: Int,
        downloadUrl: String
    ): File? {
        val fileName = downloadUrl.substringAfterLast("/")
        val savedFileName = getDownloadedFileName(context)
        val savedVersionCode = getDownloadedVersionCode(context)

        if (savedFileName != fileName || savedVersionCode != versionCode) {
            return null
        }

        val file = File(getDownloadDir(context), fileName)
        return if (file.exists() && file.length() > 0) file else null
    }

    private fun getDownloadedFileName(context: Context): String? {
        return getPrefs(context).getString(KEY_DOWNLOADED_FILE_NAME, null)
    }

    private fun getDownloadedVersionCode(context: Context): Int {
        return getPrefs(context).getInt(KEY_DOWNLOADED_VERSION_CODE, 0)
    }

    private fun saveDownloadedFileInfo(
        context: Context,
        versionCode: Int,
        fileName: String
    ) {
        getPrefs(context).edit {
            putInt(KEY_DOWNLOADED_VERSION_CODE, versionCode)
                .putString(KEY_DOWNLOADED_FILE_NAME, fileName)
        }
    }

    fun deleteDownloadedFile(context: Context) {
        val fileName = getDownloadedFileName(context) ?: return
        val file = File(getDownloadDir(context), fileName)
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "已删除本地 APK 文件: ${file.absolutePath}")
        }
        clearDownloadedFileInfo(context)
    }

    private fun clearDownloadedFileInfo(context: Context) {
        getPrefs(context).edit {
            remove(KEY_DOWNLOADED_VERSION_CODE).remove(KEY_DOWNLOADED_FILE_NAME)
        }
    }

    // ==================== 工具方法 ====================

    private fun getCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        } catch (e: Exception) {
            Log.e(TAG, "获取当前版本号失败: ${e.message}")
            0L
        }
    }

    private fun formatDownloadProgress(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> {
                val mb = bytes.toDouble() / (1024 * 1024)
                String.format(Locale.getDefault(), "已下载 %.1f MB", mb)
            }

            bytes >= 1024 -> {
                val kb = bytes.toDouble() / 1024
                String.format(Locale.getDefault(), "已下载 %.1f KB", kb)
            }

            else -> "已下载 $bytes B"
        }
    }

    private fun showLoadingDialog() {
        val dialog = AlertDialog.Builder(activity)
            .setView(R.layout.dialog_loading)
            .setCancelable(false)
            .create()
        loadingDialog = dialog

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

}
