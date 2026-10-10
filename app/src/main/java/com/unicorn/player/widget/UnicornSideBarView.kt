package com.unicorn.player.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.unicorn.player.R
import kotlin.math.abs
import kotlin.math.max

/**
 * 右侧字母索引条（专辑页使用）：触摸时字母会向左波纹位移并放大。
 *
 * 由第三方 WaveSideBarView 改写而来，因为原库字号硬编码且行高不可配，
 * 无法单独调整字体大小与整体长度。
 */
class UnicornSideBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 字母选中回调接口，参数为当前字母 */
    fun interface OnSelectIndexItemListener {
        fun onSelectIndexItem(letter: String)
    }

    companion object {
        /** 字母字号（sp），调这里改字体大小 */
        private const val TEXT_SIZE_SP = 12f

        /** 行高相对字高的比例，调这里改索引条整体长度（越小越短） */
        private const val ITEM_HEIGHT_SCALE = 1.05f

        /** 选中字母向左突出的最大距离（dp） */
        private const val WAVE_OFFSET_DP = 80f
    }

    /** 字母选中回调，由 [setOnSelectIndexItemListener] 注册 */
    private var onSelectIndexItemListener: OnSelectIndexItemListener? = null

    private val textSizePx = spToPx(TEXT_SIZE_SP)
    private val waveOffsetPx = dpToPx(WAVE_OFFSET_DP)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.onSurfaceVariant)
        textAlign = Paint.Align.CENTER
        textSize = textSizePx
    }

    private var indexItems: Array<String> = emptyArray()

    /** 单个字母占据的行高 */
    private var itemHeight = 0f

    /** 整条索引条的高度 */
    private var barHeight = 0f

    /** 最宽字母的宽度，同时作为索引条宽度 */
    private var barWidth = 0f

    /** 首个字母基线的 Y */
    private var firstItemBaseLineY = 0f

    private val touchingArea = RectF()

    /** 当前选中项下标，手指抬起后重置为 -1 */
    private var currentIndex = -1

    /** 手指落点相对索引条顶部的 Y，-1 表示未选中 */
    private var currentY = -1f

    private var touching = false

    fun setIndexItems(vararg indexItems: String) {
        this.indexItems = arrayOf(*indexItems)
        requestLayout()
    }

    fun setOnSelectIndexItemListener(listener: OnSelectIndexItemListener) {
        onSelectIndexItemListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val fontMetrics = paint.fontMetrics
        itemHeight = (fontMetrics.bottom - fontMetrics.top) * ITEM_HEIGHT_SCALE
        barHeight = indexItems.size * itemHeight
        barWidth = 0f
        for (item in indexItems) {
            barWidth = max(barWidth, paint.measureText(item))
        }

        val areaLeft = measuredWidth - barWidth - paddingEnd
        touchingArea.set(
            areaLeft,
            measuredHeight / 2f - barHeight / 2f,
            measuredWidth.toFloat(),
            measuredHeight / 2f - barHeight / 2f + barHeight
        )

        firstItemBaseLineY = measuredHeight / 2f - barHeight / 2f +
                (itemHeight / 2f - (fontMetrics.descent - fontMetrics.ascent) / 2f) - fontMetrics.ascent
    }

    override fun onDraw(canvas: Canvas) {
        for (i in indexItems.indices) {
            val scale = itemScale(i)
            paint.alpha = if (i == currentIndex) 255 else (255 * (1 - scale)).toInt()
            paint.textSize = textSizePx + textSizePx * scale
            canvas.drawText(
                indexItems[i],
                measuredWidth - paddingEnd - barWidth / 2f - waveOffsetPx * scale,
                firstItemBaseLineY + itemHeight * i,
                paint
            )
        }
        paint.alpha = 255
        paint.textSize = textSizePx
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (indexItems.isEmpty()) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!touchingArea.contains(event.x, event.y)) {
                    currentIndex = -1
                    return false
                }
                touching = true
                selectIndexAt(event.y)
            }

            MotionEvent.ACTION_MOVE -> if (touching) selectIndexAt(event.y)

            else -> {
                currentIndex = -1
                touching = false
            }
        }

        invalidate()
        return true
    }

    private fun selectIndexAt(touchY: Float) {
        currentY = touchY - (measuredHeight / 2f - barHeight / 2f)
        val index = when {
            currentY <= 0f -> 0
            else -> (currentY / itemHeight).toInt().coerceAtMost(indexItems.lastIndex)
        }
        currentIndex = index
        onSelectIndexItemListener?.onSelectIndexItem(indexItems[index])
    }

    /** 距选中项越近放大与位移越明显，形成波纹效果 */
    private fun itemScale(index: Int): Float {
        if (currentIndex == -1) return 0f
        val distance = abs(currentY - (itemHeight * index + itemHeight / 2f)) / itemHeight
        return max(0f, 1 - distance * distance / 16)
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
}
