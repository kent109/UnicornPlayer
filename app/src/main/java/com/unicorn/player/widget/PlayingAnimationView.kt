package com.unicorn.player.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.sin

class PlayingAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var isAnimating = false

    @ColorInt
    private var lineColor = resources.getColor(android.R.color.holo_red_light, context.theme)

    private var animationProgress = 0f

    init {
        paint.color = lineColor
        paint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isAnimating) {
            return
        }

        val width = width.toFloat()
        val height = height.toFloat()

        // 三根竖线的参数
        val lineWidth = width / 12f
        val spacing = width / 8f

        // 中间竖线的基础高度
        val baseHeight = height * 0.8f
        val centerX = width / 2f
        val centerY = height / 2f

        // 使用正弦波创建呼吸效果
        val time = animationProgress * 2 * Math.PI.toFloat()
        val frequency = 2f // 频率控制动画速度

        // 中间竖线 - 随时间变化高度
        val centerHeight = baseHeight * (0.7f + 0.15f * sin(time * frequency))

        // 左右竖线 - 与中间竖线变化方向完全相反
        val sideHeight = baseHeight * (0.7f - 0.15f * sin(time * frequency))

        // 绘制三根竖线 - 从中间位置向两端运动
        // 左竖线 - 中心对称
        val leftX = centerX - spacing - lineWidth / 2f
        val leftTop = centerY - sideHeight / 2f
        val leftBottom = centerY + sideHeight / 2f
        canvas.drawRect(
            leftX - lineWidth / 2f,
            leftTop,
            leftX + lineWidth / 2f,
            leftBottom,
            paint
        )

        // 中间竖线 - 中心对称
        val centerTop = centerY - centerHeight / 2f
        val centerBottom = centerY + centerHeight / 2f
        canvas.drawRect(
            centerX - lineWidth / 2f,
            centerTop,
            centerX + lineWidth / 2f,
            centerBottom,
            paint
        )

        // 右竖线 - 中心对称
        val rightX = centerX + spacing + lineWidth / 2f
        val rightTop = centerY - sideHeight / 2f
        val rightBottom = centerY + sideHeight / 2f
        canvas.drawRect(
            rightX - lineWidth / 2f,
            rightTop,
            rightX + lineWidth / 2f,
            rightBottom,
            paint
        )
    }

    /**
     * 开始动画
     */
    fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        doAnimate()
    }

    /**
     * 停止动画
     */
    fun stopAnimation() {
        isAnimating = false
    }

    /**
     * 设置线条颜色
     */
    fun setLineColor(@ColorInt color: Int) {
        lineColor = color
        paint.color = color
        invalidate()
    }

    /**
     * 动画循环
     */
    private fun doAnimate() {
        if (!isAnimating) return

        animationProgress += 0.007f // 控制动画速度
        if (animationProgress > 1f) {
            animationProgress = 0f
        }

        invalidate()

        // 继续动画循环
        postOnAnimation {
            doAnimate()
        }
    }
}