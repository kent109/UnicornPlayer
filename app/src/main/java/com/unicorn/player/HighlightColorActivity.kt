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
import com.unicorn.player.databinding.ActivityHighlightColorBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class HighlightColorActivity : BaseActivity() {

    companion object {
        private const val TAG = "HighlightColorActivity"

        fun getHighlightColorSummary(context: Context): String {
            return ThemeSettingActivity.getHighlightColorSummary(context)
        }

        private val HIGHLIGHT_COLOR_MODE = ThemeSettingActivity.HIGHLIGHT_COLOR_MODE
        private val HIGHLIGHT_COLOR_INDEX = ThemeSettingActivity.HIGHLIGHT_COLOR_INDEX
        private const val HIGHLIGHT_MODE_SYSTEM = ThemeSettingActivity.HIGHLIGHT_MODE_SYSTEM
        private const val HIGHLIGHT_MODE_FIXED = ThemeSettingActivity.HIGHLIGHT_MODE_FIXED
    }

    private lateinit var binding: ActivityHighlightColorBinding
    private var currentMode = HIGHLIGHT_MODE_SYSTEM
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHighlightColorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.titleBar.setOnBackClickListener { finish() }

        loadCurrentSettings()
        setupRadioButtons()
        setupColorItems()
    }

    private fun loadCurrentSettings() {
        currentMode = runBlocking {
            try {
                applicationContext.themeDataStore.data.first()[HIGHLIGHT_COLOR_MODE]
                    ?: HIGHLIGHT_MODE_SYSTEM
            } catch (e: Exception) {
                HIGHLIGHT_MODE_SYSTEM
            }
        }
        currentIndex = runBlocking {
            try {
                applicationContext.themeDataStore.data.first()[HIGHLIGHT_COLOR_INDEX] ?: 0
            } catch (e: Exception) {
                0
            }
        }

        when (currentMode) {
            HIGHLIGHT_MODE_FIXED -> {
                binding.rbFixed.isChecked = true
                binding.colorContainer.visibility = View.VISIBLE
            }

            else -> binding.rbSystemDefault.isChecked = true
        }
    }

    private fun setupRadioButtons() {
        binding.rbSystemDefault.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.rbFixed.isChecked = false
                onModeSelected(HIGHLIGHT_MODE_SYSTEM)
            }
        }
        binding.rbFixed.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.rbSystemDefault.isChecked = false
                onModeSelected(HIGHLIGHT_MODE_FIXED)
            }
        }
    }

    private fun onModeSelected(mode: Int) {
        if (mode == currentMode) return
        currentMode = mode

        runBlocking {
            try {
                applicationContext.themeDataStore.edit { prefs ->
                    prefs[HIGHLIGHT_COLOR_MODE] = mode
                    if (mode == HIGHLIGHT_MODE_FIXED && !prefs.contains(HIGHLIGHT_COLOR_INDEX)) {
                        prefs[HIGHLIGHT_COLOR_INDEX] = 0
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存高亮色模式失败", e)
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
        val color = ThemeSettingActivity.getColorValues(this)[index] or 0xFF000000.toInt()
        val isSelected = index == currentIndex

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
                    this@HighlightColorActivity, R.drawable.ic_check
                )
            )
            visibility = if (isSelected) View.VISIBLE else View.GONE
            if (isSelected) {
                setColorFilter(
                    ThemeSettingActivity.resolveHighlightColor(this@HighlightColorActivity)
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
        if (index == currentIndex) return
        currentIndex = index

        runBlocking {
            try {
                applicationContext.themeDataStore.edit { prefs ->
                    prefs[HIGHLIGHT_COLOR_INDEX] = index
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存高亮色索引失败", e)
            }
        }

        recreate()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
