package com.unicorn.player.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.withRotation
import com.unicorn.player.R

/**
 * 不支持格式角标：等腰梯形贴合 item 右上角。
 *
 * 几何（view 为正方形，边长 S，截角尺寸 cut = S * CUT_RATIO，边角偏移 off = S * OFFSET_RATIO）：
 * - 上腰：从 (off,0) 沿顶边到 (S-cut,0)，贴合 item 上边缘
 * - 右腰：从 (S,cut) 沿右边到 (S,S-off)，贴合 item 右边缘（两腰等长）
 * - 短底：(S-cut,0) → (S,cut) 的 45° 斜线（角上截角）
 * - 长底：(off,0) → (S,S-off) 的 45° 斜线，与短底平行
 * - 文字"不支持"旋转 45°，与两底平行，居中于梯形中位线
 */
class UnsupportedCornerLabel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 短底截角尺寸占 view 边长的比例（越大两底越近、梯形越扁、角上空白越大） */
        private const val CUT_RATIO = 0.35f

        /** 梯形整体向右上角偏移量占 view 边长的比例（避免长底伸入左下内容区） */
        private const val OFFSET_RATIO = 0.3f
        private const val LABEL_TEXT = "不支持"
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.songUnsupportedBg)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.songUnsupportedLabelText)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 7f, resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = width.toFloat()
        val cut = s * CUT_RATIO
        val off = s * OFFSET_RATIO

        path.reset()
        path.moveTo(off, 0f)
        path.lineTo(s - cut, 0f)
        path.lineTo(s, cut)
        path.lineTo(s, s - off)
        path.close()
        canvas.drawPath(path, bgPaint)

        // 文字中心 = 两底中点连线的中点（梯形中位线中心）
        val cx = (3 * s - cut + off) / 4f
        val cy = (s + cut - off) / 4f
        canvas.withRotation(45f, cx, cy) {
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            drawText(LABEL_TEXT, cx, baseline, textPaint)
        }
    }
}
