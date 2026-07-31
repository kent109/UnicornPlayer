package com.unicorn.player

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.unicorn.player.databinding.ActivitySettingsBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 设置页面Activity
 * 使用CardView自定义布局实现
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** 是否正在检查更新，防止连续点击 */
    private var isCheckingUpdate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSettingsItems()
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
                isFirst = !BuildConfig.DEBUG,
                isLast = true,
                onClick = {
                    startActivity(Intent(this, LyricsOptionsActivity::class.java))
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
                    title = "使用手册",
                    hasChevron = true,
                    isFirst = false,
                    isLast = false,
                    onClick = {
                        startActivity(Intent(this, ManualActivity::class.java))
                    }
                ),
                SettingItem(
                    key = "about",
                    title = "协议隐私",
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

    /**
     * 检查更新（模拟耗时3秒）
     */
    private fun checkForUpdate() {
        // 防止连续点击
        if (isCheckingUpdate) return
        isCheckingUpdate = true

        // 显示加载对话框
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(R.layout.dialog_loading)
            .setCancelable(false)
            .create()
        dialog.show()
        // 去除对话框默认背景，使圆角生效
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // 设置弹窗宽度为屏幕宽度的40%
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.75).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        lifecycleScope.launch {
            // 模拟耗时3秒检查新版本
            delay(3000L.milliseconds)
            dialog.dismiss()
            Toast.makeText(this@SettingsActivity, "已是最新版本", Toast.LENGTH_SHORT).show()
            isCheckingUpdate = false
        }
    }

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
