package com.unicorn.player.activity

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.unicorn.player.R
import com.unicorn.player.databinding.ActivityLanguageChooseBinding
import com.unicorn.player.util.lang.LanguageManager

/**
 * 语言环境选择页
 * 仿照 GenreSelectActivity：卡片容器 + 单选对钩。
 * 选中后保存到 DataStore，finish() 返回上一页；BaseActivity.onResume 会检测语言变化并 recreate()。
 */
class LanguageChooseActivity : BaseActivity() {

    private lateinit var binding: ActivityLanguageChooseBinding

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLanguageChooseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.titleBar.setOnBackClickListener { finish() }
        buildLanguageList()
    }

    private fun buildLanguageList() {
        val container = binding.languageContainer
        container.removeAllViews()

        val options = LanguageManager.getLanguageOptions()
        val current = LanguageManager.getSavedLanguage(this)

        options.forEachIndexed { index, (langTag, labelRes) ->
            val isLast = index == options.size - 1

            val item = createLanguageItem(
                label = getString(labelRes),
                isSelected = langTag == current
            ) {
                LanguageManager.apply(this, langTag)
                finish()
            }

            container.addView(item)
            if (!isLast) {
                val divider = layoutInflater.inflate(
                    R.layout.item_setting_divider, container, false
                )
                container.addView(divider)
            }
        }
    }

    private fun createLanguageItem(
        label: String,
        isSelected: Boolean,
        onClick: () -> Unit
    ): View {
        val highlightColor = ThemeSettingActivity.resolveHighlightColor(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)
            )
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val text = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 15f
            this.text = label
            setTextColor(
                if (isSelected) highlightColor
                else getColor(R.color.text_primary)
            )
        }

        val checkMark = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24))
            setImageDrawable(
                ContextCompat.getDrawable(this@LanguageChooseActivity, R.drawable.ic_check)
            )
            visibility = if (isSelected) View.VISIBLE else View.GONE
            if (isSelected) setColorFilter(highlightColor)
        }

        container.addView(text)
        container.addView(checkMark)
        return container
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
