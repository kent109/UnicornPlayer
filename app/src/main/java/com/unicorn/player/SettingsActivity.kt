package com.unicorn.player

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.unicorn.player.databinding.ActivitySettingsBinding
import com.unicorn.player.util.UpdateChecker
import okhttp3.Call
import java.io.File
import java.util.Locale

/**
 * 设置页面Activity
 * 使用CardView自定义布局实现
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** 是否正在检查更新，防止连续点击 */
    private var isCheckingUpdate = false

    /** 加载对话框 */
    private var loadingDialog: AlertDialog? = null

    /** 新版本对话框 */
    private var newVersionDialog: AlertDialog? = null

    /** 下载进度对话框 */
    private var progressDialog: AlertDialog? = null

    /** 下载进度条 */
    private var progressBar: android.widget.ProgressBar? = null

    /** 下载进度文本 */
    private var tvProgress: TextView? = null

    /** 当前下载的 Call 对象，用于取消 */
    private var downloadCall: Call? = null

    /** 待安装的文件（权限授权后安装） */
    private var pendingInstallFile: File? = null

    /** 当前最新版本信息 */
    private var currentVersionInfo: UpdateChecker.VersionInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSettingsItems()
    }

    override fun onResume() {
        super.onResume()
        // 从安装权限设置页返回后，检查是否可以调起安装
        val pendingFile = pendingInstallFile
        if (pendingFile != null && canRequestPackageInstalls()) {
            pendingInstallFile = null
            launchInstall(pendingFile)
        }
    }

    override fun onDestroy() {
        downloadCall?.cancel()
        loadingDialog?.dismiss()
        progressDialog?.dismiss()
        newVersionDialog?.dismiss()
        super.onDestroy()
    }

    /**
     * 设置设置项
     */
    private fun setupSettingsItems() {
        val container = binding.settingsContainer
        val inflater = LayoutInflater.from(this)

        // 分组1：播放设置
        val playItems = mutableListOf<SettingItem>()
        playItems.add(
            SettingItem(
                key = "equalizer",
                title = "均衡器",
                hasChevron = true,
                isFirst = true,
                isLast = false,
                onClick = {
                    Toast.makeText(this, "功能开发中，敬请期待", Toast.LENGTH_SHORT).show()
                }
            )
        )
        playItems.add(
            SettingItem(
                key = "lyrics",
                title = "歌词显示",
                hasChevron = true,
                isFirst = false,
                isLast = false,
                onClick = {
                    startActivity(Intent(this, LyricsOptionsActivity::class.java))
                }
            )
        )
        playItems.add(
            SettingItem(
                key = "scan_filter",
                title = "扫描过滤",
                hasChevron = true,
                isFirst = false,
                isLast = true,
                onClick = {
                    startActivity(Intent(this, ScanFilterActivity::class.java))
                }
            )
        )
        addSettingGroup(
            container = container,
            inflater = inflater,
            items = playItems
        )

        // 分组2：关于
        addSettingGroup(
            container = container,
            inflater = inflater,
            items = listOf(
                SettingItem(
                    key = "version",
                    title = "版本号",
                    summary = getVersionName(),
                    hasChevron = false,
                    isFirst = true,
                    isLast = false,
                    onClick = {
                        checkForUpdate()
                    }
                ),
                SettingItem(
                    key = "manual",
                    title = "使用指南",
                    hasChevron = true,
                    isFirst = false,
                    isLast = false,
                    onClick = {
                        startActivity(Intent(this, ManualActivity::class.java))
                    }
                ),
                SettingItem(
                    key = "about",
                    title = "隐私协议",
                    hasChevron = true,
                    isFirst = false,
                    isLast = true,
                    onClick = {
                        startActivity(Intent(this, AboutActivity::class.java))
                    }
                )
            )
        )
    }

    /**
     * 添加设置分组
     */
    private fun addSettingGroup(
        container: LinearLayout,
        inflater: LayoutInflater,
        items: List<SettingItem>
    ) {
        // 创建CardView
        val cardView = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            setCardBackgroundColor(getColor(R.color.surface))
            radius = dpToPx(12).toFloat()
            cardElevation = dpToPx(2).toFloat()
            useCompatPadding = true
        }

        // 创建内容容器
        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 添加设置项
        items.forEachIndexed { index, item ->
            val itemView = createSettingItem(inflater, contentContainer, item)
            contentContainer.addView(itemView)

            // 添加分割线（最后一个item之后不添加）
            if (!item.isLast) {
                val divider =
                    inflater.inflate(R.layout.item_setting_divider, contentContainer, false)
                contentContainer.addView(divider)
            }
        }

        // 将CardView添加到容器
        cardView.addView(contentContainer)
        container.addView(cardView)
    }

    /**
     * 创建设置项视图
     */
    private fun createSettingItem(
        inflater: LayoutInflater,
        parent: LinearLayout,
        item: SettingItem
    ): View {
        val view = inflater.inflate(R.layout.item_setting, parent, false)

        // 设置标题
        view.findViewById<android.widget.TextView>(R.id.tvTitle).text = item.title

        // 设置摘要
        val tvSummary = view.findViewById<android.widget.TextView>(R.id.tvSummary)
        if (item.summary != null) {
            tvSummary.text = item.summary
            tvSummary.visibility = View.VISIBLE
        }

        // 设置箭头
        val ivChevron = view.findViewById<android.widget.ImageView>(R.id.ivChevron)
        ivChevron.visibility = if (item.hasChevron) View.VISIBLE else View.GONE

        // 设置背景
        val bgRes = when {
            item.isFirst && item.isLast -> R.drawable.bg_preference_single
            item.isFirst -> R.drawable.bg_preference_first
            item.isLast -> R.drawable.bg_preference_last
            else -> R.drawable.bg_preference_middle
        }
        view.setBackgroundResource(bgRes)

        // 设置点击事件
        view.isClickable = item.onClick != null
        view.isFocusable = item.onClick != null
        item.onClick?.let { clickListener ->
            view.setOnClickListener { clickListener() }
        }

        return view
    }

    // ==================== 检查更新 ====================

    /**
     * 检查更新
     */
    private fun checkForUpdate() {
        // 防止连续点击
        if (isCheckingUpdate) return
        isCheckingUpdate = true

        // 显示加载对话框
        showLoadingDialog()

        // 检查服务器版本
        UpdateChecker.checkForUpdate(this) { result ->
            runOnUiThread {
                loadingDialog?.dismiss()

                when (result) {
                    is UpdateChecker.CheckResult.UpdateAvailable -> {
                        handleUpdateAvailable(result.info)
                    }

                    is UpdateChecker.CheckResult.AlreadyLatest -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.update_already_latest,
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    is UpdateChecker.CheckResult.Failed -> {
                        Toast.makeText(
                            this@SettingsActivity,
                            result.message,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                isCheckingUpdate = false
            }
        }
    }

    /**
     * 处理发现新版本
     */
    private fun handleUpdateAvailable(info: UpdateChecker.VersionInfo) {
        currentVersionInfo = info

        // 检查本地是否已有该版本的 APK 文件
        val localFile = UpdateChecker.getLocalApkFile(this, info.versionCode, info.downloadUrl)
        if (localFile != null) {
            // 本地已有最新版本，直接调起安装
            launchInstall(localFile)
        } else {
            // 显示发现新版本弹窗
            showNewVersionDialog(info)
        }
    }

    /**
     * 显示发现新版本对话框
     */
    private fun showNewVersionDialog(info: UpdateChecker.VersionInfo) {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.dialog_new_version, null)

        view.findViewById<TextView>(R.id.tvVersion).text =
            getString(R.string.update_version_label, info.versionName)

        val tvUpdateLog = view.findViewById<TextView>(R.id.tvUpdateLog)
        if (info.updateLog.isNotBlank()) {
            tvUpdateLog.text = info.updateLog
        } else {
            tvUpdateLog.text = "暂无更新说明"
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        newVersionDialog = dialog

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.8).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
            newVersionDialog = null
        }

        view.findViewById<View>(R.id.btnUpdate).setOnClickListener {
            dialog.dismiss()
            newVersionDialog = null
            startDownload(info)
        }

        dialog.show()
    }

    /**
     * 开始下载 APK
     */
    private fun startDownload(info: UpdateChecker.VersionInfo) {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.dialog_download_progress, null)

        progressBar = view.findViewById(R.id.progressBar)
        tvProgress = view.findViewById(R.id.tvProgress)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
        progressDialog = dialog

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.8).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
            // 取消下载
            downloadCall?.cancel()
            downloadCall = null
            dialog.dismiss()
            progressDialog = null
            // 删除部分下载的文件
            UpdateChecker.deleteDownloadedFile(this)
        }

        dialog.show()

        // 对话框显示后设置初始进度文本
        progressBar?.progress = 0

        // 开始下载
        downloadCall = UpdateChecker.downloadApk(
            this, info.downloadUrl,
            object : UpdateChecker.DownloadCallback {
                override fun onProgress(percent: Int, downloadedBytes: Long) {
                    runOnUiThread {
                        if (percent >= 0) {
                            // 有精确百分比
                            progressBar?.progress = percent
                            tvProgress?.text =
                                getString(R.string.update_downloaded, percent)
                        } else {
                            // 服务器未返回 Content-Length，显示已下载大小
                            progressBar?.progress = 0
                            tvProgress?.text = formatDownloadProgress(downloadedBytes)
                        }
                    }
                }

                override fun onSuccess(file: File) {
                    runOnUiThread {
                        // 确保用户看到 100% 后再关闭
                        progressBar?.progress = 100
                        tvProgress?.text = getString(R.string.update_downloaded, 100)
                        tvProgress?.postDelayed({
                            dialog.dismiss()
                            progressDialog = null
                            // 保存下载文件信息
                            val fileName = info.downloadUrl.substringAfterLast("/")
                            UpdateChecker.saveDownloadedFileInfo(
                                this@SettingsActivity,
                                info.versionCode,
                                fileName
                            )
                            // 调起安装
                            launchInstall(file)
                        }, 500)
                    }
                }

                override fun onFailure(message: String) {
                    runOnUiThread {
                        dialog.dismiss()
                        progressDialog = null
                        Toast.makeText(
                            this@SettingsActivity,
                            message,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    /**
     * 调起系统安装
     */
    private fun launchInstall(file: File) {
        try {
            if (canRequestPackageInstalls()) {
                // 有安装权限，直接调起
                installApk(file)
            } else {
                // 无安装权限，引导用户授权
                pendingInstallFile = file
                requestInstallPermission()
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 执行安装
     */
    private fun installApk(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val uri = FileProvider.getUriForFile(
                    this@SettingsActivity,
                    "${packageName}.fileprovider",
                    file
                )
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 请求安装未知应用权限
     */
    private fun requestInstallPermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.update_install_permission_denied, Toast.LENGTH_SHORT)
                .show()
            pendingInstallFile = null
        }
    }

    /**
     * 检查是否有安装未知应用权限
     */
    private fun canRequestPackageInstalls(): Boolean {
        return try {
            packageManager.canRequestPackageInstalls()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 格式化下载进度（显示已下载大小）
     */
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

    // ==================== 对话框管理 ====================

    /**
     * 显示加载对话框
     */
    private fun showLoadingDialog() {
        val dialog = AlertDialog.Builder(this)
            .setView(R.layout.dialog_loading)
            .setCancelable(false)
            .create()
        loadingDialog = dialog

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    // ==================== 工具方法 ====================

    /**
     * 获取版本名称
     */
    private fun getVersionName(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "未知"
        } catch (e: PackageManager.NameNotFoundException) {
            "未知"
        }
    }

    /**
     * dp转px
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /**
     * 设置项数据类
     */
    data class SettingItem(
        val key: String,
        val title: String,
        val summary: String? = null,
        val hasChevron: Boolean = false,
        val isFirst: Boolean = false,
        val isLast: Boolean = false,
        val onClick: (() -> Unit)? = null
    )
}
