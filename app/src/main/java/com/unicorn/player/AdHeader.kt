package com.unicorn.player

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.FrameLayout
import android.view.animation.AlphaAnimation
import androidx.core.view.isVisible
import com.scwang.smart.refresh.layout.api.RefreshHeader
import com.scwang.smart.refresh.layout.api.RefreshLayout
import com.scwang.smart.refresh.layout.simple.SimpleComponent

/**
 * 自定义广告 Header
 * 实现下拉展示广告，松手后自动回弹
 */
class AdHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SimpleComponent(context, attrs, 0), RefreshHeader {

    private val adContainer: FrameLayout
    private val adImageView: ImageView
    private val adText: TextView
    private val adCloseButton: ImageButton

    // 下拉百分比和偏移量
    private var currentPercent = 0f
    private var currentOffset = 0

    init {
        // 设置 Header 高度
        minimumHeight = dp2px(120f)

        // 加载广告布局
        inflate(context, R.layout.header_ad, this)

        adContainer = findViewById(R.id.ad_container)
        adImageView = findViewById(R.id.ad_image)
        adText = findViewById(R.id.ad_text)
        adCloseButton = findViewById(R.id.ad_close_button)

        // 设置关闭按钮点击事件
        adCloseButton.setOnClickListener {
            // 关闭广告 Header
            hideAdHeader()
        }

        // 设置广告容器点击事件
        adContainer.setOnClickListener {
            // 处理广告点击跳转
            // TODO: 实现广告点击跳转逻辑
        }

        // 初始状态隐藏
        adImageView.alpha = 0f
    }

    override fun onMoving(
        isDragging: Boolean,
        percent: Float,
        offset: Int,
        headerHeight: Int,
        maxDragHeight: Int
    ) {
        currentPercent = percent
        currentOffset = offset

        // 根据下拉百分比控制广告图片透明度（渐显效果）
        val alpha = (percent * 2).coerceIn(0f, 1f)
        adImageView.alpha = alpha

        // 视差效果：图片移动速度比下拉速度慢
        adImageView.translationY = offset * 0.3f

        // 根据下拉距离调整文本透明度
        if (percent > 0.5f) {
            adText.alpha = ((percent - 0.5f) * 2).coerceIn(0f, 1f)
        } else {
            adText.alpha = 0f
        }

        // 显示关闭按钮
        adCloseButton.isVisible = percent > 0.3f
    }

    override fun onReleased(layout: RefreshLayout, height: Int, maxDragHeight: Int) {
        // 释放后的处理
        if (currentPercent < 0.5f) {
            // 下拉距离不够，隐藏广告
            hideAdHeader()
        }
    }

    override fun onFinish(layout: RefreshLayout, success: Boolean): Int {
        // 动画完成后的处理
        return 500 // 返回动画时长
    }

    override fun onStateChanged(
        refreshLayout: RefreshLayout,
        oldState: com.scwang.smart.refresh.layout.constant.RefreshState,
        newState: com.scwang.smart.refresh.layout.constant.RefreshState
    ) {
        super.onStateChanged(refreshLayout, oldState, newState)

        when (newState) {
            com.scwang.smart.refresh.layout.constant.RefreshState.None -> {
                // 完全回弹后重置状态
                resetState()
            }
            else -> {}
        }
    }

    /**
     * 隐藏广告 Header
     */
    private fun hideAdHeader() {
        // 使用渐隐动画
        val fadeOut = AlphaAnimation(1f, 0f).apply {
            duration = 300
            fillAfter = true
        }
        adContainer.startAnimation(fadeOut)
        adContainer.isVisible = false
    }

    /**
     * 重置状态
     */
    private fun resetState() {
        currentPercent = 0f
        currentOffset = 0
        adImageView.alpha = 0f
        adImageView.translationY = 0f
        adText.alpha = 0f
        adContainer.isVisible = true
        adCloseButton.isVisible = false
    }

    /**
     * dp 转 px
     */
    private fun dp2px(dp: Float): Int {
        return (resources.displayMetrics.density * dp + 0.5f).toInt()
    }

    override fun getSpinnerStyle(): com.scwang.smart.refresh.layout.constant.SpinnerStyle {
        // 使用 Translate 样式，Header 跟随移动
        return com.scwang.smart.refresh.layout.constant.SpinnerStyle.Translate
    }
}
