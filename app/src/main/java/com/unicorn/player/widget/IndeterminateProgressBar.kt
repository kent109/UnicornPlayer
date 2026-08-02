package com.unicorn.player.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.unicorn.player.R

/**
 * 支持不确定动画的自定义进度条
 * - 确定模式：显示 0-100% 进度
 * - 不确定模式：高亮段循环扫过（非填充）
 */
class IndeterminateProgressBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FFE0E0E0".toColorInt()
    }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surfaceVariant)
    }
    private val rect = RectF()

    private var progress = -1f // -1 = 不确定, 0-100 = 确定
    private var highlightPos = 0f // 不确定模式下高亮段位置 0-1

    private var animator: ValueAnimator? = null

    // 不确定模式下高亮段宽度占比
    private val highlightRatio = 0.25f

    /**
     * 设置进度（-1 表示切换到不确定模式）
     */
    fun setProgressCompat(value: Int) {
        if (value < 0) {
            startIndeterminate()
        } else {
            stopIndeterminate()
            progress = value.toFloat().coerceIn(0f, 100f)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f

        // 背景
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, bgPaint)

        if (progress < 0) {
            // 不确定模式：绘制移动高亮段
            val hlW = w * highlightRatio
            val maxLeft = w - hlW
            val left = maxLeft * highlightPos
            rect.set(left, 0f, left + hlW, h)
            canvas.drawRoundRect(rect, r, r, fgPaint)
        } else {
            // 确定模式：绘制进度
            val pw = w * progress / 100f
            if (pw > 0f) {
                rect.set(0f, 0f, pw, h)
                canvas.drawRoundRect(rect, r, r, fgPaint)
            }
        }
    }

    private fun startIndeterminate() {
        if (progress >= 0) {
            progress = -1f
            invalidate()
        }
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener {
                    highlightPos = it.animatedValue as Float
                    invalidate()
                }
            }
        }
        if (animator?.isRunning != true) {
            animator?.start()
        }
    }

    private fun stopIndeterminate() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopIndeterminate()
    }
}
