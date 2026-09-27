package com.unicorn.player

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.lifecycleScope
import com.unicorn.player.databinding.ActivityIconSettingBinding
import kotlinx.coroutines.launch

class IconSettingActivity : BaseActivity() {

    companion object {
        private const val TAG = "IconSetting"
    }

    private lateinit var binding: ActivityIconSettingBinding
    private var currentMode = ThemeSettingActivity.SONG_ICON_MODE_CASSETTE
    private val optionViews = mutableListOf<Triple<View, ImageView, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIconSettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentMode = ThemeSettingActivity.resolveIconMode(this)
        setupSettingsItems()
    }

    private fun setupSettingsItems() {
        val container = binding.settingsContainer
        val inflater = LayoutInflater.from(this)

        val headerView = TextView(this).apply {
            text = "歌曲图标"
            setTextColor(getColor(R.color.onSurfaceVariant))
            textSize = 14f
            setPadding(dpToPx(8), dpToPx(0), 0, dpToPx(8))
        }
        container.addView(headerView)

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

        val options = listOf(
            IconOption(
                ThemeSettingActivity.SONG_ICON_MODE_CASSETTE,
                "磁带",
                R.drawable.ic_cassette_photo
            ),
            IconOption(
                ThemeSettingActivity.SONG_ICON_MODE_DISC,
                "唱片",
                R.drawable.ic_disc_normal
            )
        )

        optionViews.clear()
        options.forEachIndexed { index, option ->
            val itemView = inflater.inflate(R.layout.item_icon_option, contentContainer, false)
            val ivIcon = itemView.findViewById<ImageView>(R.id.ivIcon)
            val tvName = itemView.findViewById<TextView>(R.id.tvName)
            val ivCheck = itemView.findViewById<ImageView>(R.id.ivCheck)

            ivIcon.setImageResource(option.iconRes)
            tvName.text = option.name

            val isSelected = currentMode == option.mode
            updateOptionState(ivCheck, isSelected)
            tvName.setTextColor(
                if (isSelected) ThemeSettingActivity.resolveHighlightColor(this)
                else ContextCompat.getColor(this, R.color.onSurface)
            )

            itemView.setOnClickListener {
                if (currentMode != option.mode) {
                    onIconModeSelected(option.mode)
                }
            }

            contentContainer.addView(itemView)
            optionViews.add(Triple(itemView, ivCheck, option.mode))

            if (index < options.size - 1) {
                val divider =
                    inflater.inflate(R.layout.item_setting_divider, contentContainer, false)
                contentContainer.addView(divider)
            }
        }

        val bgFirst = R.drawable.bg_preference_first
        val bgLast = R.drawable.bg_preference_last
        contentContainer.getChildAt(0).setBackgroundResource(bgFirst)
        val lastChildIndex = contentContainer.childCount - 1
        contentContainer.getChildAt(lastChildIndex).setBackgroundResource(bgLast)

        cardView.addView(contentContainer)
        container.addView(cardView)
    }

    private fun onIconModeSelected(mode: Int) {
        currentMode = mode
        lifecycleScope.launch {
            try {
                applicationContext.themeDataStore.edit { it[ThemeSettingActivity.SONG_ICON_MODE] = mode }
            } catch (e: Exception) {
                Log.e(TAG, "写入 song_icon_mode 失败", e)
            }
        }
        refreshOptionStates()
    }

    private fun refreshOptionStates() {
        val highlightColor = ThemeSettingActivity.resolveHighlightColor(this)
        val normalColor = ContextCompat.getColor(this, R.color.onSurface)

        for ((view, ivCheck, mode) in optionViews) {
            val tvName = view.findViewById<TextView>(R.id.tvName)
            val isSelected = currentMode == mode
            updateOptionState(ivCheck, isSelected)
            tvName.setTextColor(if (isSelected) highlightColor else normalColor)
            if (isSelected) {
                ivCheck.setColorFilter(highlightColor)
            }
        }
    }

    private fun updateOptionState(ivCheck: ImageView, isSelected: Boolean) {
        ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
        if (isSelected) {
            ivCheck.setColorFilter(ThemeSettingActivity.resolveHighlightColor(this))
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private data class IconOption(val mode: Int, val name: String, val iconRes: Int)
}
