package com.unicorn.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.unicorn.player.databinding.ActivityScanFilterBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// ---------------------------------------------------------------------------
// 进程级单例 DataStore 委托
// 顶层属性保证全进程唯一实例，避免 IllegalStateException
// ---------------------------------------------------------------------------
internal val Context.scanFiltersDataStore by preferencesDataStore(name = "scan_filters")

/**
 * 扫描过滤设置页面
 * 配置音频扫描时的过滤条件
 */
class ScanFilterActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ScanFilterActivity"

        // DataStore 键
        val SKIP_SHORT_AUDIO = booleanPreferencesKey("skip_short_audio")
        val SKIP_SMALL_FILES = booleanPreferencesKey("skip_small_files")
        val EXCLUDED_DIRS = stringSetPreferencesKey("excluded_dirs")
        val INCLUDED_DIRS = stringSetPreferencesKey("included_dirs")

        // 目录选择请求码
        private const val REQUEST_EXCLUDED_DIRS = 1001
        private const val REQUEST_INCLUDED_DIRS = 1002

        // SWITCH 类型设置项的 key → DataStore 键 映射
        val switchKeyMap = mapOf(
            "skip_short_audio" to SKIP_SHORT_AUDIO,
            "skip_small_files" to SKIP_SMALL_FILES
        )

        // SWITCH 类型设置项的 key → 默认值 映射
        val switchDefaultMap = mapOf(
            "skip_short_audio" to false,
            "skip_small_files" to false
        )
    }

    private lateinit var binding: ActivityScanFilterBinding

    // 防止开关恢复时递归触发 listener
    private var isRestoringSwitch = false

    // 当前目录选择模式
    private var currentMode = "exclude"

    // 目录选择启动器
    private val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val paths = result.data?.getStringArrayListExtra("selected_dirs")?.toSet() ?: emptySet()
                lifecycleScope.launch {
                    try {
                        applicationContext.scanFiltersDataStore.edit {
                            it[if (currentMode == "exclude") EXCLUDED_DIRS else INCLUDED_DIRS] = paths
                        }
                        // 写入完成后更新标题
                        updateDirsTitle()
                    } catch (e: Exception) {
                        Log.e(TAG, "写入目录设置失败", e)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSettingsItems()
    }

    /**
     * 设置设置项
     */
    private fun setupSettingsItems() {
        val container = binding.settingsContainer
        val inflater = LayoutInflater.from(this)

        // 分组1：扫描过滤设置
        addSettingGroup(
            container = container,
            inflater = inflater,
            items = listOf(
                SettingItem(
                    key = "skip_short_audio",
                    title = "不扫描少于30秒",
                    hasChevron = false,
                    isFirst = true,
                    isLast = false,
                    type = SettingItemType.SWITCH
                ),
                SettingItem(
                    key = "skip_small_files",
                    title = "不扫描小于100K",
                    hasChevron = false,
                    isFirst = false,
                    isLast = false,
                    type = SettingItemType.SWITCH
                ),
                SettingItem(
                    key = "excluded_dirs",
                    title = getDirsTitle("excluded_dirs", "不扫描的目录"),
                    hasChevron = true,
                    isFirst = false,
                    isLast = false,
                    type = SettingItemType.NORMAL,
                    onClick = { launchDirectoryPicker("exclude") }
                ),
                SettingItem(
                    key = "included_dirs",
                    title = getDirsTitle("included_dirs", "指定扫描目录"),
                    hasChevron = true,
                    isFirst = false,
                    isLast = true,
                    type = SettingItemType.NORMAL,
                    onClick = { launchDirectoryPicker("include") }
                )
            )
        )
    }

    /**
     * 获取目录设置项标题（含已选数量）
     */
    private fun getDirsTitle(key: String, baseTitle: String): String {
        val count = runBlocking {
            try {
                val prefs = applicationContext.scanFiltersDataStore.data.first()
                if (key == "excluded_dirs") prefs[EXCLUDED_DIRS]?.size ?: 0
                else prefs[INCLUDED_DIRS]?.size ?: 0
            } catch (e: Exception) {
                0
            }
        }
        return "$baseTitle($count)"
    }

    /**
     * 刷新目录设置项标题
     */
    private fun updateDirsTitle() {
        binding.settingsContainer.findViewWithTag<android.widget.TextView>("excluded_dirs_title")?.text =
            getDirsTitle("excluded_dirs", "不扫描的目录")
        binding.settingsContainer.findViewWithTag<android.widget.TextView>("included_dirs_title")?.text =
            getDirsTitle("included_dirs", "指定扫描目录")
    }

    /**
     * 启动目录选择页面
     * @param mode "exclude" 或 "include"
     */
    private fun launchDirectoryPicker(mode: String) {
        currentMode = mode
        val dataStoreKey = if (mode == "exclude") EXCLUDED_DIRS else INCLUDED_DIRS
        val selected = runBlocking {
            try {
                val prefs = applicationContext.scanFiltersDataStore.data.first()
                ArrayList(prefs[dataStoreKey] ?: emptySet())
            } catch (e: Exception) {
                arrayListOf()
            }
        }
        val intent = Intent(this, DirectoryPickerActivity::class.java).apply {
            putExtra(DirectoryPickerActivity.EXTRA_MODE, mode)
            putStringArrayListExtra(DirectoryPickerActivity.EXTRA_SELECTED, selected)
        }
        directoryPickerLauncher.launch(intent)
    }

    /**
     * 添加设置分组
     */
    private fun addSettingGroup(
        container: LinearLayout,
        inflater: LayoutInflater,
        items: List<SettingItem>
    ) {
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

        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        items.forEachIndexed { index, item ->
            val itemView = createSettingItem(inflater, contentContainer, item)
            contentContainer.addView(itemView)

            if (!item.isLast) {
                val divider = inflater.inflate(R.layout.item_setting_divider, contentContainer, false)
                contentContainer.addView(divider)
            }
        }

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
        val tvTitle = view.findViewById<android.widget.TextView>(R.id.tvTitle)
        tvTitle.text = item.title
        tvTitle.tag = "${item.key}_title"

        // 设置摘要
        val tvSummary = view.findViewById<android.widget.TextView>(R.id.tvSummary)
        if (item.summary != null) {
            tvSummary.text = item.summary
            tvSummary.visibility = View.VISIBLE
        }

        // 设置箭头
        val ivChevron = view.findViewById<android.widget.ImageView>(R.id.ivChevron)
        ivChevron.visibility = if (item.hasChevron) View.VISIBLE else View.GONE

        // 设置 SwitchButton
        val switchButton = view.findViewById<SwitchMaterial>(R.id.switchButton)
        if (item.type == SettingItemType.SWITCH) {
            switchButton.visibility = View.VISIBLE
            val prefKey = switchKeyMap[item.key]
            val defaultValue = switchDefaultMap[item.key] ?: false

            val enabled = runBlocking {
                try {
                    if (prefKey != null) {
                        applicationContext.scanFiltersDataStore.data.first()[prefKey] ?: defaultValue
                    } else defaultValue
                } catch (e: Exception) {
                    Log.e(TAG, "读取设置失败: ${item.key}", e)
                    defaultValue
                }
            }
            switchButton.isChecked = enabled
            switchButton.tag = "${item.key}_switch"

            switchButton.setOnCheckedChangeListener { _, isChecked ->
                lifecycleScope.launch {
                    try {
                        val k = switchKeyMap[item.key] ?: return@launch
                        applicationContext.scanFiltersDataStore.edit { it[k] = isChecked }
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

        // 设置点击事件
        if (item.type != SettingItemType.SWITCH) {
            view.isClickable = item.onClick != null
            view.isFocusable = item.onClick != null
            item.onClick?.let { clickListener ->
                view.setOnClickListener { clickListener() }
            }
        } else {
            view.isClickable = false
            view.isFocusable = false
        }

        return view
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
