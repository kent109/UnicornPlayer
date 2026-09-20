package com.unicorn.player

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
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

        private val COLOR_THEME_RES = intArrayOf(
            R.style.Theme_UnicornPlayer_Color1,
            R.style.Theme_UnicornPlayer_Color2,
            R.style.Theme_UnicornPlayer_Color3,
            R.style.Theme_UnicornPlayer_Color4,
            R.style.Theme_UnicornPlayer_Color5,
            R.style.Theme_UnicornPlayer_Color6,
            R.style.Theme_UnicornPlayer_Color7
        )

        val COLOR_VALUES = intArrayOf(
            0xf800f8,
            0xf00078,
            0xf3c832,
            0x4ab069,
            0x377eea,
            0x26fdfd,
            0x7e3ae4
        )

        fun getColorThemeRes(index: Int): Int {
            return if (index in 0 until COLOR_COUNT) COLOR_THEME_RES[index] else 0
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
                    title = "颜色模式",
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
                    isLast = true,
                    type = SettingItemType.NORMAL,
                    onClick = {
                        startActivity(android.content.Intent(this, ThemeColorActivity::class.java))
                    }
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

        val tvSummary = view.findViewById<android.widget.TextView>(R.id.tvSummary)
        if (item.summary != null) {
            tvSummary.text = item.summary
            tvSummary.visibility = View.VISIBLE
            tvSummary.tag = "${item.key}_summary"
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

        val checkColor = ContextCompat.getColor(this, android.R.color.holo_red_light)
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
