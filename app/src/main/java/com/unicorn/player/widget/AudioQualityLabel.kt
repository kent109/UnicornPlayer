package com.unicorn.player.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import com.unicorn.player.R
import kotlin.math.min

/**
 * 音频品质标签控件
 * 长宽比 1.6:1，圆角矩形边框，中间显示文字
 * 边框和文字使用相同颜色
 */
class AudioQualityLabel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var labelText = ""

    @ColorInt
    private var labelColor = 0

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var cornerRadius = 0f

    init {
        context.withStyledAttributes(attrs, R.styleable.AudioQualityLabel) {

            labelText = getString(R.styleable.AudioQualityLabel_aq_text) ?: ""
            labelColor = getColor(
                R.styleable.AudioQualityLabel_aq_color,
                ContextCompat.getColor(context, android.R.color.white)
            )

        }

        updatePaintColors()
    }

    private fun updatePaintColors() {
        borderPaint.color = labelColor
        textPaint.color = labelColor
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = textPaint.measureText(labelText)
        val textHeight = textPaint.textSize

        // 根据文字内容计算理想尺寸（含内边距）
        val paddingHorizontal = textHeight * 1.2f
        val paddingVertical = textHeight * 0.6f

        val desiredWidth = (textWidth + paddingHorizontal * 2).toInt()
        val desiredHeight = (textHeight + paddingVertical * 2).toInt()

        // 强制保持 1.6:1 长宽比
        val width: Int
        val height: Int

        // 根据内容自适应，同时保持比例
        if (desiredWidth > desiredHeight * 1.6f) {
            // 宽度主导
            width = desiredWidth
            height = (width / 1.6f).toInt()
        } else {
            // 高度主导
            height = desiredHeight
            width = (height * 1.6f).toInt()
        }

        // 考虑 MeasureSpec 约束
        val resolvedWidth = resolveSize(width, widthMeasureSpec)
        val resolvedHeight = resolveSize(height, heightMeasureSpec)

        // 在约束下保持 1.6:1 比例
        val constrainedByWidth = resolvedWidth.toFloat() / 1.6f <= resolvedHeight
        val finalWidth: Int
        val finalHeight: Int

        if (constrainedByWidth) {
            finalWidth = resolvedWidth
            finalHeight = (finalWidth / 1.6f).toInt().coerceAtLeast(1)
        } else {
            finalHeight = resolvedHeight
            finalWidth = (finalHeight * 1.6f).toInt()
        }

        setMeasuredDimension(finalWidth, finalHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cornerRadius = min(w, h) * 0.18f
        // 文字大小根据控件高度自适应
        textPaint.textSize = h * 0.6f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // 绘制圆角矩形边框
        canvas.drawRoundRect(
            borderPaint.strokeWidth / 2f,
            borderPaint.strokeWidth / 2f,
            width - borderPaint.strokeWidth / 2f,
            height - borderPaint.strokeWidth / 2f,
            cornerRadius,
            cornerRadius,
            borderPaint
        )

        // 绘制居中文字
        if (labelText.isNotEmpty()) {
            val centerX = width / 2f
            val centerY = height / 2f

            // 计算文字基线位置（垂直居中）
            val fontMetrics = textPaint.fontMetrics
            val baselineY = centerY - (fontMetrics.top + fontMetrics.bottom) / 2f

            canvas.drawText(labelText, centerX, baselineY, textPaint)
        }
    }

    /**
     * 设置标签文字
     */
    fun setText(text: String) {
        labelText = text
        invalidate()
        requestLayout()
    }

    /**
     * 设置边框和文字颜色
     */
    fun setColor(@ColorInt color: Int) {
        labelColor = color
        updatePaintColors()
        invalidate()
    }

    /**
     * 获取当前文字
     */
    fun getText(): String = labelText

    /**
     * 获取当前颜色
     */
    fun getLabelColor(): Int = labelColor
}
