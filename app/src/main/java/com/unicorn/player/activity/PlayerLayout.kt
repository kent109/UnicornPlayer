package com.unicorn.player.activity

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * PlayerActivity ViewPager2 翻页/布局相关的纯工具函数与常量。
 *
 * 零闭包依赖：所有函数通过参数接收必要上下文，可安全独立。
 */
internal object PlayerLayout {

    /**
     * ViewPager2 翻页阈值：水平位移 < 此比例 × 页宽时，视为"轻扫"被作废并回位。
     * 在 disableLightFling() 里用于阻止未达意图阈值的 fling 切歌。
     */
    internal const val MIN_SWIPE_DRAG_RATIO = 0.5f

    /**
     * PageTransformer：根据相对 position 做透明+缩放动画。
     * 位置 0 → 100% 不透明 原尺寸；位置 ±1 → 70% 不透明 90% 尺寸。
     */
    fun transformPage(page: View, position: Float) {
        val absPosition = kotlin.math.abs(position)
        page.alpha = 1f - absPosition * 0.3f
        val scale = 1f - absPosition * 0.1f
        page.scaleX = scale
        page.scaleY = scale
    }

    /**
     * 把 position 页对齐回去所需的滚动量（该页取不到时返回 0）。
     * scrollBy 正方向让子 View 左移，因此滚动量就是 child.left 本身。
     */
    fun pageOffset(rv: RecyclerView, position: Int): Int {
        val child = (0 until rv.childCount)
            .map { rv.getChildAt(it) }
            .firstOrNull { rv.getChildAdapterPosition(it) == position }
        return child?.left ?: 0
    }

    /**
     * 字号档位（小/中/大）对应的像素值。
     * 下标对应 FONT_SIZE_SMALL=0 / MEDIUM=1 / LARGE=2，
     * 三个元素分别为 { 普通行字号, 高亮行字号, 拖动选中行字号 }。
     */
    val fontSizeTiers = arrayOf(
        intArrayOf(13, 16, 14),  // 小
        intArrayOf(15, 18, 16),  // 中（默认）
        intArrayOf(19, 22, 20)   // 大
    )
}
