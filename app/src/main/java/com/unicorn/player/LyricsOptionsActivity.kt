package com.unicorn.player

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.unicorn.player.databinding.ActivityLyricsOptionsBinding
import com.unicorn.player.util.LrcFetcher
import com.unicorn.player.util.LyricsSaveManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// ---------------------------------------------------------------------------
// 进程级单例 DataStore 委托
//
// 关键约束（来自 DataStore 官方文档）：
//   1. 每个文件在整个进程内只能有唯一 DataStore 实例
//   2. `by preferencesDataStore(name = "...")` 这种 property-delegate 写法会
//      在 delegate 对象的生命周期内维护一个单例；但如果 delegate 是类内部
//      Activity 实例的成员属性，每次 Activity 重建都会创建一个新的 delegate
//      实例 → 第二个 delegate 要绑定同一文件时被第一个 DataStore 还活着 →
//         IllegalStateException: "There are multiple DataStores active for the
//         same file"
//   3. 修复方法：delegate 必须设在 **top-level**（文件级/static），整个进程
//      仅实例化一次，所有 Activity 共享。
//     → Kotlin 顶层属性就是 static-like，全进程一份，lifecycle 超出任何
//      Activity，符合 DataStore 要求。
// ---------------------------------------------------------------------------
internal val Context.lyricsDataStore by preferencesDataStore(name = "lyrics_settings")

/**
 * 歌词显示设置页面
 * 配置歌词显示样式和行为
 */
class LyricsOptionsActivity : BaseActivity() {

    companion object {
        private const val TAG = "LyricsOptions"

        // 歌词设置键
        val LYRICS_ENABLED = booleanPreferencesKey("lyrics_enabled")
        val TIME_LABEL_VISIBLE = booleanPreferencesKey("time_label_visible")
        val FONT_SIZE = intPreferencesKey("font_size")

        // 字体大小常量
        const val FONT_SIZE_SMALL = 0
        const val FONT_SIZE_MEDIUM = 1
        const val FONT_SIZE_LARGE = 2

        // SWITCH 类型设置项的 key → DataStore 键 映射
        val switchKeyMap = mapOf(
            "lyrics_enable" to LYRICS_ENABLED,
            "time_label" to TIME_LABEL_VISIBLE
        )

        // SWITCH 类型设置项的 key → 默认值 映射
        val switchDefaultMap = mapOf(
            "lyrics_enable" to false,
            "time_label" to false
        )
    }

    private lateinit var binding: ActivityLyricsOptionsBinding

    // 字体大小弹窗
    private var fontPopup: android.widget.PopupWindow? = null

    // 防止开关恢复时递归触发 listener
    private var isRestoringSwitch = false

    // SAF 目录选择器启动器：用户授权后持久化树 URI
    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                LyricsSaveManager.saveTreeUri(this, treeUri)
                // 授权成功，重新设置开关为打开（此时已有权限，不会再次触发 SAF）
                setSwitchChecked("lyrics_enable", true)
            } else {
                // 用户取消选择，恢复开关为关闭
                setSwitchChecked("lyrics_enable", false)
            }
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
                    summary = getFontSizeSummary(),
                    hasChevron = true,
                    isFirst = false,
                    isLast = false,
                    type = SettingItemType.SELECT,
                    onClick = { anchor -> showFontSizeMenu(anchor) }
                ),
                SettingItem(
                    key = "search_lyrics",
                    title = "歌词目录",
                    summary = "/storage/emulated/0/Documents/Unicorn/Lyrics",
                    hasChevron = false,
                    isFirst = false,
                    isLast = true,
                    type = SettingItemType.NORMAL
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
            tvSummary.tag = "${item.key}_summary"
        }

        // 设置箭头
        val ivChevron = view.findViewById<android.widget.ImageView>(R.id.ivChevron)
        ivChevron.visibility = if (item.hasChevron) View.VISIBLE else View.GONE

        // 设置 SwitchButton（仅 SWITCH 类型显示）
        // 使用官方 MaterialSwitch（替换三方 com.suke.widget.SwitchButton），
        // 彻底消除三方库内部状态与 Android SavedState 恢复冲突的 bug。
        val switchButton = view.findViewById<SwitchMaterial>(R.id.switchButton)
        if (item.type == SettingItemType.SWITCH) {
            switchButton.visibility = View.VISIBLE
            // 根据 item.key 获取对应的 DataStore 键与默认值
            val prefKey = switchKeyMap[item.key]
            val defaultValue = switchDefaultMap[item.key] ?: true

            // 【方案 A】同步读取 DataStore 恢复开关状态。
            // 放在 onCreate 主线程直接 runBlocking，避免 lifecycleScope 在 Activity
            // 快速退出时被 cancel → CancellationException 被 catch 吞掉 → 回退到
            // defaultValue=true（自动打开的根因）。
            //
            // 关键：必须从 applicationContext 取 DataStore，保证进程级单例 —— 否则每次
            // Activity 重建（销毁后重新进入）委托再跑一次，会尝试再建一个新 DataStore
            // 持有同一文件 → IllegalStateException: "multiple DataStores active"。
            val enabled = runBlocking {
                try {
                    if (prefKey != null) {
                        applicationContext.lyricsDataStore.data.first()[prefKey] ?: defaultValue
                    } else defaultValue
                } catch (e: Exception) {
                    Log.e(TAG, "读取设置失败: ${item.key}", e)
                    defaultValue
                }
            }
            switchButton.isChecked = enabled
            // 设置 tag 以便后续通过 findViewWithTag 定位
            switchButton.tag = "${item.key}_switch"
            // 同步到对应的运行时状态（避免 Activity 重建后 object 状态丢失）
            if (item.key == "lyrics_enable") {
                LrcFetcher.lyricsEnabled = enabled
            }

            // 【方案 C】监听开关变化：即时同步运行时状态 + 即时异步写入 DataStore，
            // 移除 pendingSwitchStates 中间层 —— 解决 runBlocking 在 onPause 中可被中断的问题。
            switchButton.setOnCheckedChangeListener { _, isChecked ->
                if (item.key == "lyrics_enable") {
                    if (isChecked) {
                        // 打开前检查 SAF 权限——无权限则恢复开关并启动选择器
                        if (!LyricsSaveManager.hasSavedTreeUri(this@LyricsOptionsActivity) ||
                            !LyricsSaveManager.isTreePermissionValid(this@LyricsOptionsActivity)
                        ) {
                            if (!isRestoringSwitch) {
                                isRestoringSwitch = true
                                switchButton.isChecked = false
                                isRestoringSwitch = false
                                openDocumentTreeLauncher.launch(LyricsSaveManager.getInitialUri())
                            }
                            return@setOnCheckedChangeListener
                        }
                    }
                    LrcFetcher.lyricsEnabled = isChecked
                }
                lifecycleScope.launch {
                    try {
                        val k = switchKeyMap[item.key] ?: return@launch
                        applicationContext.lyricsDataStore.edit { it[k] = isChecked }
                    } catch (e: Exception) {
                        Log.e(TAG, "写入设置失败: ${item.key}", e)
                    }
                }
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
                view.setOnClickListener { clickListener(view) }
            }
        } else {
            // SWITCH 类型：行本身不接点击，让 SwitchMaterial 自己处理
            view.isClickable = false
            view.isFocusable = false
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
     * 获取字体大小 summary 文字（小/中/大）
     */
    private fun getFontSizeSummary(): String {
        val size = runBlocking {
            try {
                applicationContext.lyricsDataStore.data.first()[FONT_SIZE] ?: FONT_SIZE_MEDIUM
            } catch (e: Exception) {
                FONT_SIZE_MEDIUM
            }
        }
        return when (size) {
            FONT_SIZE_SMALL -> "小"
            FONT_SIZE_LARGE -> "大"
            else -> "中"
        }
    }

    /**
     * 显示字体大小选择弹窗
     */
    private fun showFontSizeMenu(anchorView: View) {
        fontPopup?.let {
            if (it.isShowing) {
                it.dismiss()
                return
            }
        }

        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_font_size, null)
        val tvSmall = popupView.findViewById<android.widget.TextView>(R.id.tvFontSmall)
        val tvMedium = popupView.findViewById<android.widget.TextView>(R.id.tvFontMedium)
        val tvLarge = popupView.findViewById<android.widget.TextView>(R.id.tvFontLarge)

        // 当前字号，用于显示钩号
        val currentSize = runBlocking {
            try {
                applicationContext.lyricsDataStore.data.first()[FONT_SIZE] ?: FONT_SIZE_MEDIUM
            } catch (e: Exception) {
                FONT_SIZE_MEDIUM
            }
        }

        val checkColor = ContextCompat.getColor(this, android.R.color.holo_red_light)
        val normalColor = ContextCompat.getColor(this, R.color.text_primary)

        setupFontSizeItem(tvSmall, currentSize == FONT_SIZE_SMALL, checkColor, normalColor)
        setupFontSizeItem(tvMedium, currentSize == FONT_SIZE_MEDIUM, checkColor, normalColor)
        setupFontSizeItem(tvLarge, currentSize == FONT_SIZE_LARGE, checkColor, normalColor)

        // 点击选项
        tvSmall.setOnClickListener {
            onFontSizeSelected(FONT_SIZE_SMALL)
        }
        tvMedium.setOnClickListener {
            onFontSizeSelected(FONT_SIZE_MEDIUM)
        }
        tvLarge.setOnClickListener {
            onFontSizeSelected(FONT_SIZE_LARGE)
        }

        val popupWidthPx = (180 * resources.displayMetrics.density).toInt()

        fontPopup = PopupWindow(
            popupView, popupWidthPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true
        ).apply {
            elevation = 8f
            isOutsideTouchable = true
            animationStyle = R.style.PopupAnimation
            setOnDismissListener { fontPopup = null }
        }

        // 弹窗右边缘对齐锚点（字体大小行）右边缘，并保证不超出屏幕左右边界
        val anchorLoc = IntArray(2)
        anchorView.getLocationOnScreen(anchorLoc)
        val screenWidth = resources.displayMetrics.widthPixels
        val xOff = anchorView.width - popupWidthPx
        val clampedXOff = xOff.coerceIn(
            -anchorLoc[0],
            screenWidth - anchorLoc[0] - popupWidthPx
        )
        fontPopup?.showAsDropDown(anchorView, clampedXOff, -50)
    }

    /**
     * 设置字体大小 item 样式：选中=红色文字+红色钩号
     */
    private fun setupFontSizeItem(
        textView: android.widget.TextView,
        isSelected: Boolean,
        checkColor: Int,
        normalColor: Int
    ) {
        textView.setTextColor(if (isSelected) checkColor else normalColor)
        textView.setCompoundDrawablesWithIntrinsicBounds(
            0, 0, if (isSelected) R.drawable.ic_check else 0, 0
        )
        if (isSelected) {
            textView.compoundDrawables[2]?.setTint(checkColor)
        }
    }

    /**
     * 字号选中回调：持久化 + 更新 summary + 关闭弹窗
     */
    private fun onFontSizeSelected(size: Int) {
        lifecycleScope.launch {
            try {
                applicationContext.lyricsDataStore.edit { it[FONT_SIZE] = size }
            } catch (e: Exception) {
                Log.e(TAG, "写入 font_size 失败", e)
            }
        }
        updateFontSizeSummary(size)
        fontPopup?.dismiss()
        fontPopup = null
    }

    /**
     * 通过 tag 找到指定 key 对应的开关并设置其状态（防递归触发 listener）
     */
    private fun setSwitchChecked(key: String, checked: Boolean) {
        isRestoringSwitch = true
        binding.settingsContainer
            .findViewWithTag<SwitchMaterial>("${key}_switch")
            ?.isChecked = checked
        isRestoringSwitch = false
    }

    /**
     * 刷新"字体大小"行的 summary 文本
     */
    private fun updateFontSizeSummary(size: Int) {
        val summary = when (size) {
            FONT_SIZE_SMALL -> "小"
            FONT_SIZE_LARGE -> "大"
            else -> "中"
        }
        // 通过 tag 定位"字体大小"行的 summary（tag 在 createSettingItem 里赋值为 "${item.key}_summary"）
        binding.settingsContainer
            .findViewWithTag<android.widget.TextView>("font_size_summary")
            ?.text = summary
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
        val onClick: ((View) -> Unit)? = null,
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
