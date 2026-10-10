package com.unicorn.player

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.appcompat.app.AppCompatDelegate
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import com.unicorn.player.databinding.ActivityThemeSettingBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal val Context.themeDataStore by preferencesDataStore(name = "theme_settings")

class ThemeSettingActivity : BaseActivity() {

    companion object {
        private const val TAG = "ThemeSetting"

        val THEME_MODE = intPreferencesKey("theme_mode")

        const val MODE_FOLLOW_SYSTEM = 0
        const val MODE_LIGHT = 1
        const val MODE_DARK = 2

        val THEME_COLOR_MODE = intPreferencesKey("theme_color_mode")
        val THEME_COLOR_INDEX = intPreferencesKey("theme_color_index")
        val THEME_COLOR_LAST_INDEX = intPreferencesKey("theme_color_last_index")
        val THEME_COLOR_LAST_SWITCH_DATE = stringPreferencesKey("theme_color_last_switch_date")

        const val COLOR_MODE_SYSTEM = 0
        const val COLOR_MODE_RANDOM = 1
        const val COLOR_MODE_FIXED = 2

        const val COLOR_COUNT = 7

        val HIGHLIGHT_COLOR_MODE = intPreferencesKey("highlight_color_mode")
        val HIGHLIGHT_COLOR_INDEX = intPreferencesKey("highlight_color_index")

        const val HIGHLIGHT_MODE_SYSTEM = 0
        const val HIGHLIGHT_MODE_FIXED = 1

        val SONG_ICON_MODE = intPreferencesKey("song_icon_mode")
        const val SONG_ICON_MODE_DISC = 0
        const val SONG_ICON_MODE_CASSETTE = 1

        val BOTTOM_PANEL_MODE = intPreferencesKey("bottom_panel_mode")
        const val BOTTOM_PANEL_MODE_CARD = 0
        const val BOTTOM_PANEL_MODE_BANNER = 1

        private val COLOR_THEME_RES = intArrayOf(
            R.style.Theme_UnicornPlayer_Color1,
            R.style.Theme_UnicornPlayer_Color2,
            R.style.Theme_UnicornPlayer_Color3,
            R.style.Theme_UnicornPlayer_Color4,
            R.style.Theme_UnicornPlayer_Color5,
            R.style.Theme_UnicornPlayer_Color6,
            R.style.Theme_UnicornPlayer_Color7
        )

        fun getColorValues(context: Context): IntArray {
            val typedArray = context.resources.obtainTypedArray(R.array.theme_color_values)
            val colors = IntArray(typedArray.length()) { i ->
                typedArray.getColor(i, 0)
            }
            typedArray.recycle()
            return colors
        }

        fun getColorThemeRes(index: Int): Int {
            return if (index in 0 until COLOR_COUNT) COLOR_THEME_RES[index] else 0
        }

        fun resolveHighlightColor(context: Context): Int {
            val prefs = runBlocking {
                try {
                    context.applicationContext.themeDataStore.data.first()
                } catch (e: Exception) {
                    null
                }
            } ?: return ContextCompat.getColor(context, R.color.highlight_color)

            val mode = prefs[HIGHLIGHT_COLOR_MODE] ?: HIGHLIGHT_MODE_SYSTEM
            if (mode == HIGHLIGHT_MODE_SYSTEM) {
                return ContextCompat.getColor(context, R.color.highlight_color)
            }

            val index = prefs[HIGHLIGHT_COLOR_INDEX] ?: 0
            val colors = getColorValues(context)
            return if (index in colors.indices) {
                colors[index] or 0xFF000000.toInt()
            } else {
                ContextCompat.getColor(context, R.color.highlight_color)
            }
        }

        fun resolveHighlightColorIndex(context: Context): Int {
            val prefs = runBlocking {
                try {
                    context.applicationContext.themeDataStore.data.first()
                } catch (e: Exception) {
                    null
                }
            } ?: return 0
            val mode = prefs[HIGHLIGHT_COLOR_MODE] ?: HIGHLIGHT_MODE_SYSTEM
            return if (mode == HIGHLIGHT_MODE_SYSTEM) 0
            else prefs[HIGHLIGHT_COLOR_INDEX] ?: 0
        }

        fun resolveIconMode(context: Context): Int {
            val prefs = runBlocking {
                try {
                    context.applicationContext.themeDataStore.data.first()
                } catch (e: Exception) {
                    null
                }
            } ?: return SONG_ICON_MODE_CASSETTE
            return prefs[SONG_ICON_MODE] ?: SONG_ICON_MODE_CASSETTE
        }

        fun getIconModeSummary(context: Context): String {
            val mode = resolveIconMode(context)
            return when (mode) {
                SONG_ICON_MODE_CASSETTE -> "磁带"
                else -> "唱片"
            }
        }

        fun resolveBottomPanelMode(context: Context): Int {
            val prefs = runBlocking {
                try {
                    context.applicationContext.themeDataStore.data.first()
                } catch (e: Exception) {
                    null
                }
            } ?: return BOTTOM_PANEL_MODE_CARD
            return prefs[BOTTOM_PANEL_MODE] ?: BOTTOM_PANEL_MODE_CARD
        }

        fun getBottomPanelSummary(context: Context): String {
            val mode = resolveBottomPanelMode(context)
            return when (mode) {
                BOTTOM_PANEL_MODE_BANNER -> "横幅"
                else -> "卡片"
            }
        }

        /**
         * 应用底部面板（播放条、多选操作栏）样式：横幅模式去掉圆角并把左右 8dp 间距填满屏幕宽度，
         * 同时把这 8dp 补进内容区的左右内边距，使封面与各按钮的屏幕位置和卡片模式保持一致，
         * 高度与上下间距不变。
         *
         * @param cardContentPaddingDp 卡片模式下内容区已有的左右内边距
         */
        fun applyBottomPanelStyle(
            panel: CardView, content: View?, mode: Int, cardContentPaddingDp: Int
        ) {
            val density = panel.resources.displayMetrics.density
            val gap = (8f * density).toInt()
            val isBanner = mode == BOTTOM_PANEL_MODE_BANNER

            panel.radius = if (isBanner) 0f else gap.toFloat()

            val lp = panel.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.leftMargin = if (isBanner) 0 else gap
                lp.rightMargin = if (isBanner) 0 else gap
                panel.layoutParams = lp
            }

            val padding = (cardContentPaddingDp * density).toInt() + if (isBanner) gap else 0
            content?.let {
                it.setPadding(padding, it.paddingTop, padding, it.paddingBottom)
            }
        }

        fun resolveThemeColorIndex(context: Context): Int {
            val prefs = runBlocking {
                try {
                    context.applicationContext.themeDataStore.data.first()
                } catch (e: Exception) {
                    null
                }
            } ?: return 0
            val mode = prefs[THEME_COLOR_MODE] ?: COLOR_MODE_SYSTEM
            return when (mode) {
                COLOR_MODE_FIXED -> prefs[THEME_COLOR_INDEX] ?: 0
                COLOR_MODE_RANDOM -> prefs[THEME_COLOR_INDEX] ?: 0
                else -> 0
            }
        }

        fun getHighlightColorSummary(context: Context): String {
            val mode = runBlocking {
                try {
                    context.applicationContext.themeDataStore.data.first()[HIGHLIGHT_COLOR_MODE]
                        ?: HIGHLIGHT_MODE_SYSTEM
                } catch (e: Exception) {
                    HIGHLIGHT_MODE_SYSTEM
                }
            }
            return when (mode) {
                HIGHLIGHT_MODE_FIXED -> "固定使用"
                else -> "系统默认"
            }
        }

        fun applyTheme(mode: Int) {
            val nightMode = when (mode) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }

        fun getThemeSummary(context: Context): String {
            val mode = runBlocking {
                try {
                    context.applicationContext.themeDataStore.data.first()[THEME_MODE]
                        ?: MODE_FOLLOW_SYSTEM
                } catch (e: Exception) {
                    MODE_FOLLOW_SYSTEM
                }
            }
            return when (mode) {
                MODE_LIGHT -> "白天模式"
                MODE_DARK -> "黑夜模式"
                else -> "跟随系统"
            }
        }
    }

    private lateinit var binding: ActivityThemeSettingBinding
    private var themePopup: PopupWindow? = null
    private var bottomPanelPopup: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemeSettingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSettingsItems()
    }

    override fun onResume() {
        super.onResume()
        binding.settingsContainer
            .findViewWithTag<android.widget.TextView>("theme_color_summary")
            ?.text = ThemeColorActivity.getColorModeSummary(this)
        binding.settingsContainer
            .findViewWithTag<android.widget.TextView>("highlight_color_summary")
            ?.text = HighlightColorActivity.getHighlightColorSummary(this)
        updateColorPreview("theme_color")
        updateColorPreview("highlight_color")
    }

    private fun updateColorPreview(key: String) {
        val preview = binding.settingsContainer.findViewWithTag<View>("${key}_color_preview")
            ?: return
        val color = when (key) {
            "theme_color" -> {
                val idx = resolveThemeColorIndex(this)
                getColorValues(this).getOrElse(idx) { getColorValues(this)[0] } or 0xFF000000.toInt()
            }

            "highlight_color" -> resolveHighlightColor(this)
            else -> return
        }
        preview.background = GradientDrawable().apply {
            setColor(color)
        }
    }

    private fun setupSettingsItems() {
        val container = binding.settingsContainer
        val inflater = LayoutInflater.from(this)

        addSettingGroup(
            container = container,
            inflater = inflater,
            items = listOf(
                SettingItem(
                    key = "theme_mode",
                    title = "深色模式",
                    summary = getThemeSummary(),
                    hasChevron = true,
                    isFirst = true,
                    isLast = false,
                    type = SettingItemType.SELECT,
                    onClick = { anchor -> showThemeModeMenu(anchor) }
                ),
                SettingItem(
                    key = "theme_color",
                    title = "主题色",
                    summary = ThemeColorActivity.getColorModeSummary(this),
                    hasChevron = true,
                    isFirst = false,
                    isLast = false,
                    type = SettingItemType.NORMAL,
                    onClick = {
                        startActivity(android.content.Intent(this, ThemeColorActivity::class.java))
                    }
                ),
                SettingItem(
                    key = "highlight_color",
                    title = "高亮色",
                    summary = HighlightColorActivity.getHighlightColorSummary(this),
                    hasChevron = true,
                    isFirst = false,
                    isLast = false,
                    type = SettingItemType.NORMAL,
                    onClick = {
                        startActivity(
                            android.content.Intent(
                                this,
                                HighlightColorActivity::class.java
                            )
                        )
                    }
                ),
                SettingItem(
                    key = "icon_mode",
                    title = "图标外观",
                    hasChevron = true,
                    isFirst = false,
                    isLast = false,
                    type = SettingItemType.NORMAL,
                    onClick = {
                        startActivity(android.content.Intent(this, IconSettingActivity::class.java))
                    }
                ),
                SettingItem(
                    key = "bottom_panel",
                    title = "底部面板",
                    summary = getBottomPanelSummary(this),
                    hasChevron = true,
                    isFirst = false,
                    isLast = true,
                    type = SettingItemType.SELECT,
                    onClick = { anchor -> showBottomPanelMenu(anchor) }
                )
            )
        )
    }

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

        items.forEachIndexed { _, item ->
            val itemView = createSettingItem(inflater, contentContainer, item)
            contentContainer.addView(itemView)

            if (!item.isLast) {
                val divider =
                    inflater.inflate(R.layout.item_setting_divider, contentContainer, false)
                contentContainer.addView(divider)
            }
        }

        cardView.addView(contentContainer)
        container.addView(cardView)
    }

    private fun createSettingItem(
        inflater: LayoutInflater,
        parent: LinearLayout,
        item: SettingItem
    ): View {
        val view = inflater.inflate(R.layout.item_setting, parent, false)

        view.findViewById<android.widget.TextView>(R.id.tvTitle).text = item.title

        val summaryRow = view.findViewById<LinearLayout>(R.id.summaryRow)
        val tvSummary = view.findViewById<android.widget.TextView>(R.id.tvSummary)
        if (item.summary != null) {
            summaryRow.visibility = View.VISIBLE
            tvSummary.text = item.summary
            tvSummary.tag = "${item.key}_summary"

            val colorPreview = view.findViewById<View>(R.id.colorPreview)
            if (item.key == "theme_color" || item.key == "highlight_color") {
                colorPreview.visibility = View.VISIBLE
                val color = when (item.key) {
                    "theme_color" -> {
                        val idx = resolveThemeColorIndex(this)
                        getColorValues(this).getOrElse(idx) { getColorValues(this)[0] } or 0xFF000000.toInt()
                    }

                    "highlight_color" -> resolveHighlightColor(this)
                    else -> 0
                }
                colorPreview.background = GradientDrawable().apply {
                    setColor(color)
                }
                colorPreview.tag = "${item.key}_color_preview"
            }
        }

        val ivChevron = view.findViewById<android.widget.ImageView>(R.id.ivChevron)
        ivChevron.visibility = if (item.hasChevron) View.VISIBLE else View.GONE

        val switchButton =
            view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchButton)
        switchButton.visibility = View.GONE

        val bgRes = when {
            item.isFirst && item.isLast -> R.drawable.bg_preference_single
            item.isFirst -> R.drawable.bg_preference_first
            item.isLast -> R.drawable.bg_preference_last
            else -> R.drawable.bg_preference_middle
        }
        view.setBackgroundResource(bgRes)

        if (item.type != SettingItemType.SWITCH) {
            view.isClickable = item.onClick != null
            view.isFocusable = item.onClick != null
            item.onClick?.let { clickListener ->
                view.setOnClickListener { clickListener(view) }
            }
        } else {
            view.isClickable = false
            view.isFocusable = false
        }

        return view
    }

    private fun getThemeSummary(): String {
        return getThemeSummary(this)
    }

    private fun showThemeModeMenu(anchorView: View) {
        themePopup?.let {
            if (it.isShowing) {
                it.dismiss()
                return
            }
        }

        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_theme_mode, null)
        val tvFollowSystem =
            popupView.findViewById<android.widget.TextView>(R.id.tvThemeFollowSystem)
        val tvLight = popupView.findViewById<android.widget.TextView>(R.id.tvThemeLight)
        val tvDark = popupView.findViewById<android.widget.TextView>(R.id.tvThemeDark)

        val currentMode = runBlocking {
            try {
                applicationContext.themeDataStore.data.first()[THEME_MODE] ?: MODE_FOLLOW_SYSTEM
            } catch (e: Exception) {
                MODE_FOLLOW_SYSTEM
            }
        }

        val checkColor = resolveHighlightColor(this)
        val normalColor = ContextCompat.getColor(this, R.color.text_primary)

        setupThemeModeItem(
            tvFollowSystem,
            currentMode == MODE_FOLLOW_SYSTEM,
            checkColor,
            normalColor
        )
        setupThemeModeItem(tvLight, currentMode == MODE_LIGHT, checkColor, normalColor)
        setupThemeModeItem(tvDark, currentMode == MODE_DARK, checkColor, normalColor)

        tvFollowSystem.setOnClickListener { onThemeModeSelected(MODE_FOLLOW_SYSTEM) }
        tvLight.setOnClickListener { onThemeModeSelected(MODE_LIGHT) }
        tvDark.setOnClickListener { onThemeModeSelected(MODE_DARK) }

        val popupWidthPx = (180 * resources.displayMetrics.density).toInt()

        themePopup = PopupWindow(
            popupView, popupWidthPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true
        ).apply {
            elevation = 8f
            isOutsideTouchable = true
            animationStyle = R.style.PopupAnimation
            setOnDismissListener { themePopup = null }
        }

        val anchorLoc = IntArray(2)
        anchorView.getLocationOnScreen(anchorLoc)
        val screenWidth = resources.displayMetrics.widthPixels
        val xOff = anchorView.width - popupWidthPx
        val clampedXOff = xOff.coerceIn(
            -anchorLoc[0],
            screenWidth - anchorLoc[0] - popupWidthPx
        )
        themePopup?.showAsDropDown(anchorView, clampedXOff, -50)
    }

    private fun showBottomPanelMenu(anchorView: View) {
        bottomPanelPopup?.let {
            if (it.isShowing) {
                it.dismiss()
                return
            }
        }

        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_bottom_panel, null)
        val tvCard = popupView.findViewById<android.widget.TextView>(R.id.tvBottomPanelCard)
        val tvBanner = popupView.findViewById<android.widget.TextView>(R.id.tvBottomPanelBanner)

        val currentMode = resolveBottomPanelMode(this)
        val checkColor = resolveHighlightColor(this)
        val normalColor = ContextCompat.getColor(this, R.color.text_primary)

        setupThemeModeItem(tvCard, currentMode == BOTTOM_PANEL_MODE_CARD, checkColor, normalColor)
        setupThemeModeItem(tvBanner, currentMode == BOTTOM_PANEL_MODE_BANNER, checkColor, normalColor)

        tvCard.setOnClickListener { onBottomPanelModeSelected(BOTTOM_PANEL_MODE_CARD) }
        tvBanner.setOnClickListener { onBottomPanelModeSelected(BOTTOM_PANEL_MODE_BANNER) }

        val popupWidthPx = (180 * resources.displayMetrics.density).toInt()

        bottomPanelPopup = PopupWindow(
            popupView, popupWidthPx, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true
        ).apply {
            elevation = 8f
            isOutsideTouchable = true
            animationStyle = R.style.PopupAnimation
            setOnDismissListener { bottomPanelPopup = null }
        }

        val anchorLoc = IntArray(2)
        anchorView.getLocationOnScreen(anchorLoc)
        val screenWidth = resources.displayMetrics.widthPixels
        val xOff = anchorView.width - popupWidthPx
        val clampedXOff = xOff.coerceIn(
            -anchorLoc[0],
            screenWidth - anchorLoc[0] - popupWidthPx
        )
        bottomPanelPopup?.showAsDropDown(anchorView, clampedXOff, -50)
    }

    private fun onBottomPanelModeSelected(mode: Int) {
        lifecycleScope.launch {
            try {
                applicationContext.themeDataStore.edit { it[BOTTOM_PANEL_MODE] = mode }
            } catch (e: Exception) {
                Log.e(TAG, "写入 bottom_panel_mode 失败", e)
            }
        }
        binding.settingsContainer
            .findViewWithTag<android.widget.TextView>("bottom_panel_summary")
            ?.text = if (mode == BOTTOM_PANEL_MODE_BANNER) "横幅" else "卡片"
        bottomPanelPopup?.dismiss()
        bottomPanelPopup = null
    }

    private fun setupThemeModeItem(
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

    private fun onThemeModeSelected(mode: Int) {
        lifecycleScope.launch {
            try {
                applicationContext.themeDataStore.edit { it[THEME_MODE] = mode }
            } catch (e: Exception) {
                Log.e(TAG, "写入 theme_mode 失败", e)
            }
        }
        applyTheme(mode)
        updateThemeModeSummary(mode)
        themePopup?.dismiss()
        themePopup = null
    }

    private fun updateThemeModeSummary(mode: Int) {
        val summary = when (mode) {
            MODE_LIGHT -> "白天模式"
            MODE_DARK -> "黑夜模式"
            else -> "跟随系统"
        }
        binding.settingsContainer
            .findViewWithTag<android.widget.TextView>("theme_mode_summary")
            ?.text = summary
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

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

    enum class SettingItemType {
        NORMAL,
        SWITCH,
        SELECT
    }
}
