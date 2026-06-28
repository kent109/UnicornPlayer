package com.unicorn.player

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unicorn.player.databinding.ActivityLyricsOptionsBinding

/**
 * 歌词显示设置页面
 * 配置歌词显示样式和行为
 */
class LyricsOptionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLyricsOptionsBinding

    // 歌词设置 DataStore
    val Context.lyricsDataStore by preferencesDataStore(name = "lyrics_settings")

    companion object {
        // 歌词设置键
        val LYRICS_ENABLED = booleanPreferencesKey("lyrics_enabled")
        val TIME_LABEL_VISIBLE = booleanPreferencesKey("time_label_visible")
        val FONT_SIZE = intPreferencesKey("font_size")
        val COLOR_THEME = intPreferencesKey("color_theme")

        // 字体大小常量
        const val FONT_SIZE_SMALL = 0
        const val FONT_SIZE_MEDIUM = 1
        const val FONT_SIZE_LARGE = 2

        // 颜色主题常量
        const val COLOR_THEME_SYSTEM = 0
        const val COLOR_THEME_LIGHT = 1
        const val COLOR_THEME_DARK = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLyricsOptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSettingsItems()
    }

    /**
     * 设置设置项
     */
    private fun setupSettingsItems() {
        val container = binding.settingsContainer
        val inflater = LayoutInflater.from(this)

        // 分组1：歌词显示设置
        addSettingGroup(
            container = container,
            inflater = inflater,
            items = listOf(
                SettingItem(
                    key = "lyrics_enable",
                    title = "显示歌词",
                    summary = "在播放界面显示歌词",
                    hasChevron = false,
                    isFirst = true,
                    isLast = false,
                    type = SettingItemType.SWITCH
                ),
                SettingItem(
                    key = "time_label",
                    title = "显示时间标签",
                    summary = "在歌词左侧显示时间",
                    hasChevron = false,
                    isFirst = false,
                    isLast = false,
                    type = SettingItemType.SWITCH
                ),
                SettingItem(
                    key = "font_size",
                    title = "字体大小",
                    summary = "中",
                    hasChevron = true,
                    isFirst = false,
                    isLast = false,
                    type = SettingItemType.SELECT
                ),
                SettingItem(
                    key = "color_theme",
                    title = "颜色主题",
                    summary = "跟随系统",
                    hasChevron = true,
                    isFirst = false,
                    isLast = true,
                    type = SettingItemType.SELECT
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
     * 获取版本名称（复用SettingsActivity的逻辑）
     */
    private fun getVersionName(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "未知"
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
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
}
