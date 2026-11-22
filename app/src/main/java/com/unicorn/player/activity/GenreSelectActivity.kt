package com.unicorn.player.activity

import com.unicorn.player.R
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.unicorn.player.databinding.ActivityGenreSelectBinding

/**
 * 流派选择页
 * 参考"设置 - 主题色"的单选样式，展示一组内置流派供点击选择。
 * 点击任意流派后立即通过 [onGenreSelected] 回调把值传回歌曲信息弹窗并销毁本页；
 * 本页不做持久化，是否写回文件由弹窗的"完成"按钮决定。
 */
class GenreSelectActivity : BaseActivity() {

    companion object {
        /** 传入弹窗当前的流派值，用于进入本页时预选中对应单选 */
        const val EXTRA_CURRENT_GENRE = "extra_current_genre"

        const val UNKNOWN_GENRE = "<unknown>"

        /**
         * 弹窗在跳转前登记的回调；选中流派后由本页调用，随后销毁。
         * 弹窗关闭时置空，避免引用泄漏。
         */
        var onGenreSelected: ((String) -> Unit)? = null
    }

    private lateinit var binding: ActivityGenreSelectBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGenreSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.titleBar.setOnBackClickListener { finish() }

        val current = intent.getStringExtra(EXTRA_CURRENT_GENRE) ?: UNKNOWN_GENRE
        buildGenreList(current)
    }

    /**
     * 在卡片容器内逐个添加流派条目，条目间插入分隔线，样式对齐主题色的色块条目（对钩）。
     * options = 内置流派（genre_options）+ "<unknown>"（清空流派）
     */
    private fun buildGenreList(current: String) {
        val container = binding.genreContainer
        container.removeAllViews()

        val options = resources.getStringArray(R.array.genre_options).toList() + UNKNOWN_GENRE
        val displayLabels = options.map { if (it == UNKNOWN_GENRE) getString(R.string.genre_unknown) else it }

        val selectedIndex = options.indexOfFirst { it == current }.let { if (it >= 0) it else options.size - 1 }

        options.forEachIndexed { index, genre ->
            container.addView(createGenreItem(genre, displayLabels[index], index == selectedIndex))
            if (index < options.size - 1) {
                val divider = layoutInflater.inflate(R.layout.item_setting_divider, container, false)
                container.addView(divider)
            }
        }
    }

    /**
     * 单条流派：左侧名称撑满、右侧对钩；仅当前选中项显示对钩。点击整行即选择并回传。
     * genre：内部值（回传给弹窗）；label：显示文本
     */
    private fun createGenreItem(genre: String, label: String, isSelected: Boolean): View {
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
            setOnClickListener { onGenrePicked(genre) }
        }

        val text = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.text = label
            textSize = 15f
            setTextColor(if (isSelected) highlightColor else getColor(R.color.text_primary))
        }

        val checkMark = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24))
            setImageDrawable(
                ContextCompat.getDrawable(this@GenreSelectActivity, R.drawable.ic_check)
            )
            visibility = if (isSelected) View.VISIBLE else View.GONE
            if (isSelected) setColorFilter(highlightColor)
        }

        container.addView(text)
        container.addView(checkMark)
        return container
    }

    /**
     * 选中流派：把值实时回传给弹窗，登记回调置空后销毁本页。
     */
    private fun onGenrePicked(genre: String) {
        onGenreSelected?.invoke(genre)
        onGenreSelected = null
        finish()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
