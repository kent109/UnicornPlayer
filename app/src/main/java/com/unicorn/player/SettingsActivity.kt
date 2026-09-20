package com.unicorn.player

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.unicorn.player.databinding.ActivitySettingsBinding
import com.unicorn.player.util.UpdateHelper
import java.io.File

/**
 * 设置页面Activity
 * 使用CardView自定义布局实现
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** 检查更新与下载安装辅助类 */
    private lateinit var updateHelper: UpdateHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateHelper = UpdateHelper(this)

        setupSettingsItems()
    }

    override fun onResume() {
        super.onResume()
        updateHelper.onResume()
    }

    override fun onDestroy() {
        updateHelper.onDestroy()
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
                    startActivity(Intent(this, EqualizerActivity::class.java))
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
                key = "theme_style",
                title = "主题样式",
                hasChevron = true,
                isFirst = false,
                isLast = false,
                onClick = {
                    startActivity(Intent(this, ThemeSettingActivity::class.java))
                }
            )
        )
        playItems.add(
            SettingItem(
                key = "scan_filter",
                title = "扫描过滤",
                hasChevron = true,
                isFirst = false,
                isLast = false,
                onClick = {
                    startActivity(Intent(this, ScanFilterActivity::class.java))
                }
            )
        )
        playItems.add(
            SettingItem(
                key = "clear_cache",
                title = "清理存储",
                hasChevron = false,
                isFirst = false,
                isLast = true,
                onClick = {
                    showClearCacheDialog()
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
                    key = "auto_update",
                    title = "自动检查更新",
                    hasChevron = false,
                    isFirst = true,
                    isLast = false,
                    type = SettingItemType.SWITCH
                ),
                SettingItem(
                    key = "version",
                    title = "版本号",
                    summary = getVersionName(),
                    hasChevron = false,
                    isFirst = false,
                    isLast = false,
                    onClick = {
                        // 取消 MainActivity 中待执行的静默检查任务，避免重复检查
                        MainActivity.cancelPendingAutoUpdateCheck()
                        updateHelper.checkForUpdate()
                    }
                ),
                SettingItem(
                    key = "share",
                    title = getString(R.string.share_friends),
                    hasChevron = false,
                    isFirst = false,
                    isLast = false,
                    onClick = {
                        shareAppImage()
                    }
                ),
                SettingItem(
                    key = "feedback",
                    title = "意见反馈",
                    hasChevron = true,
                    isFirst = false,
                    isLast = false,
                    onClick = {
                        startActivity(Intent(this, FeedbackActivity::class.java))
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
     * 显示清除缓存确认弹窗
     * 使用自定义布局，风格与发现新版本弹窗保持一致
     */
    private fun showClearCacheDialog() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.dialog_clear_cache, null)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnConfirm).setOnClickListener {
            dialog.dismiss()
            performClearCache()
        }
        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * 执行缓存清理：清除应用缓存目录 + 下载的更新文件
     */
    private fun performClearCache() {
        try {
            // 1. 清除应用缓存
            cacheDir.deleteRecursively()
            externalCacheDir?.deleteRecursively()

            // 2. 清除下载的更新文件（update 目录）
            val updateDir = File(getExternalFilesDir(null), UpdateHelper.DOWNLOAD_DIR_NAME)
            if (updateDir.exists()) {
                updateDir.deleteRecursively()
            }

            Toast.makeText(this, R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("SettingsActivity", "清除缓存失败: ${e.message}", e)
        }
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

        // 设置 SwitchButton（仅 SWITCH 类型显示）
        val switchButton = view.findViewById<SwitchMaterial>(R.id.switchButton)
        if (item.type == SettingItemType.SWITCH) {
            switchButton.visibility = View.VISIBLE
            switchButton.isChecked = UpdateHelper.isAutoCheckEnabled(this)
            switchButton.tag = "${item.key}_switch"

            // 监听开关变化
            switchButton.setOnCheckedChangeListener { _, isChecked ->
                UpdateHelper.setAutoCheckEnabled(this, isChecked)
            }
        } else {
            switchButton.visibility = View.GONE
        }

        // 设置背景
        val bgRes = when {
            item.isFirst && item.isLast -> R.drawable.bg_preference_single
            item.isFirst -> R.drawable.bg_preference_first
            item.isLast -> R.drawable.bg_preference_last
            else -> R.drawable.bg_preference_middle
        }
        view.setBackgroundResource(bgRes)

        // 设置点击事件（仅 NORMAL/SELECT 类型的 item 有 onClick；SWITCH 类型不能拦截，否则开关无效）
        if (item.type != SettingItemType.SWITCH) {
            view.isClickable = item.onClick != null
            view.isFocusable = item.onClick != null
            item.onClick?.let { clickListener ->
                view.setOnClickListener { clickListener() }
            }
        } else {
            // SWITCH 类型：行本身不接点击，让 SwitchMaterial 自己处理
            view.isClickable = false
            view.isFocusable = false
        }

        return view
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
        val onClick: (() -> Unit)? = null,
        val type: SettingItemType = SettingItemType.NORMAL
    )

    /**
     * 设置项类型
     */
    enum class SettingItemType {
        NORMAL,
        SWITCH,
        SELECT
    }

    /**
     * 分享应用图片
     */
    private fun shareAppImage() {
        try {
            val inputStream = resources.openRawResource(R.raw.app)
            val destFile = File(getExternalFilesDir(null), "share_image.png")

            inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val imageUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                destFile
            )

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, imageUri)
                type = "image/png"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_text))
                putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "分享轻籁"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
