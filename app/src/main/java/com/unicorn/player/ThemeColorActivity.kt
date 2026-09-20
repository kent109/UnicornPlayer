package com.unicorn.player

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import com.unicorn.player.databinding.ActivityThemeColorBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ThemeColorActivity : BaseActivity() {

    companion object {
        private const val TAG = "ThemeColorActivity"

        fun getColorModeSummary(context: Context): String {
            val mode = runBlocking {
                try {
                    context.applicationContext.themeDataStore.data.first()[THEME_COLOR_MODE]
                        ?: COLOR_MODE_SYSTEM
                } catch (e: Exception) {
                    COLOR_MODE_SYSTEM
                }
            }
            return when (mode) {
                COLOR_MODE_RANDOM -> "随机变化"
                COLOR_MODE_FIXED -> "固定使用"
                else -> "系统默认"
            }
        }

        private val THEME_COLOR_MODE = ThemeSettingActivity.THEME_COLOR_MODE
        private val THEME_COLOR_INDEX = ThemeSettingActivity.THEME_COLOR_INDEX
        private const val COLOR_MODE_SYSTEM = ThemeSettingActivity.COLOR_MODE_SYSTEM
        private const val COLOR_MODE_RANDOM = ThemeSettingActivity.COLOR_MODE_RANDOM
        private const val COLOR_MODE_FIXED = ThemeSettingActivity.COLOR_MODE_FIXED
    }

    private lateinit var binding: ActivityThemeColorBinding
    private var currentColorMode = COLOR_MODE_SYSTEM
    private var currentColorIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemeColorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.titleBar.setOnBackClickListener { finish() }

        loadCurrentSettings()
        setupRadioButtons()
        setupColorItems()
    }

    private fun loadCurrentSettings() {
        currentColorMode = runBlocking {
            try {
                applicationContext.themeDataStore.data.first()[THEME_COLOR_MODE]
                    ?: COLOR_MODE_SYSTEM
            } catch (e: Exception) {
                COLOR_MODE_SYSTEM
            }
        }
        currentColorIndex = runBlocking {
            try {
                applicationContext.themeDataStore.data.first()[THEME_COLOR_INDEX] ?: 0
            } catch (e: Exception) {
                0
            }
        }

        when (currentColorMode) {
            COLOR_MODE_RANDOM -> binding.rbRandom.isChecked = true
            COLOR_MODE_FIXED -> {
                binding.rbFixed.isChecked = true
                binding.colorContainer.visibility = View.VISIBLE
            }

            else -> binding.rbSystemDefault.isChecked = true
        }
    }

    private fun setupRadioButtons() {
        binding.rbSystemDefault.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.rbRandom.isChecked = false
                binding.rbFixed.isChecked = false
                onColorModeSelected(COLOR_MODE_SYSTEM)
            }
        }
        binding.rbRandom.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.rbSystemDefault.isChecked = false
                binding.rbFixed.isChecked = false
                onColorModeSelected(COLOR_MODE_RANDOM)
            }
        }
        binding.rbFixed.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.rbSystemDefault.isChecked = false
                binding.rbRandom.isChecked = false
                onColorModeSelected(COLOR_MODE_FIXED)
            }
        }
    }

    private fun onColorModeSelected(mode: Int) {
        if (mode == currentColorMode) return
        currentColorMode = mode

        runBlocking {
            try {
                applicationContext.themeDataStore.edit { prefs ->
                    prefs[THEME_COLOR_MODE] = mode
                    if (mode == COLOR_MODE_RANDOM && !prefs.contains(THEME_COLOR_INDEX)) {
                        val initialIndex = (0 until ThemeSettingActivity.COLOR_COUNT).random()
                        prefs[THEME_COLOR_INDEX] = initialIndex
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存主题色模式失败", e)
            }
        }

        recreate()
    }

    private fun setupColorItems() {
        val container = binding.colorContainer
        container.removeAllViews()

        val cardView = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
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
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        for (i in 0 until ThemeSettingActivity.COLOR_COUNT) {
            val itemView = createColorItem(i)
            contentContainer.addView(itemView)

            if (i < ThemeSettingActivity.COLOR_COUNT - 1) {
                val divider =
                    layoutInflater.inflate(R.layout.item_setting_divider, contentContainer, false)
                contentContainer.addView(divider)
            }
        }

        cardView.addView(contentContainer)
        container.addView(cardView)
    }

    private fun createColorItem(index: Int): View {
        val color = ThemeSettingActivity.COLOR_VALUES[index] or 0xFF000000.toInt()
        val isSelected = index == currentColorIndex

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)
            )
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onColorSelected(index) }
        }

        val colorBlock = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)).apply {
                marginEnd = dpToPx(12)
            }
            setBackgroundColor(color)
        }

        val checkMark = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24))
            setImageDrawable(
                ContextCompat.getDrawable(
                    this@ThemeColorActivity, R.drawable.ic_check
                )
            )
            visibility = if (isSelected) View.VISIBLE else View.GONE
            if (isSelected) {
                setColorFilter(
                    ContextCompat.getColor(
                        this@ThemeColorActivity, android.R.color.holo_red_light
                    )
                )
            }
        }

        container.addView(colorBlock)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        container.addView(spacer)

        container.addView(checkMark)

        return container
    }

    private fun onColorSelected(index: Int) {
        if (index == currentColorIndex) return
        currentColorIndex = index

        runBlocking {
            try {
                applicationContext.themeDataStore.edit { prefs ->
                    prefs[THEME_COLOR_INDEX] = index
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存主题色索引失败", e)
            }
        }

        recreate()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
