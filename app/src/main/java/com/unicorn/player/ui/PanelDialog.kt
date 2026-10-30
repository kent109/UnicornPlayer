package com.unicorn.player.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import com.unicorn.player.R

/**
 * 通用弹窗外壳：透明窗口铺满屏幕，居中的面板宽 = 屏宽减去左右各 [sideMarginDp]。
 *
 * 不用 AlertDialog：浮窗的宽度由主题的 windowMinWidth 直接决定，给不了「屏宽减固定 dp」这种百分比之外的值，
 * 而 show 之后再 setLayout 会先画一帧默认宽度再跳。窗口改成非浮窗后，宽度由面板自己的 LayoutParams 说话，
 * 首帧就是最终宽度。
 *
 * 传进来的布局根节点就是可见面板（背景、圆角、内边距都归它）；要操作里面的子视图用 [contentView] 绑定。
 */
class PanelDialog(
    context: Context,
    @LayoutRes layoutResId: Int,
    /** 面板与屏幕左右边缘的间隙（dp）；不传就用 @dimen/dialog_panel_side_margin 这个全局默认值 */
    sideMarginDp: Float? = null
) : Dialog(context, R.style.ThemeOverlay_Unicorn_Dialog_Panel) {

    val contentView: View = LayoutInflater.from(context).inflate(layoutResId, null)

    init {
        val density = context.resources.displayMetrics.density
        val sideMargin = sideMarginDp?.let { (it * density).toInt() }
            ?: context.resources.getDimensionPixelSize(R.dimen.dialog_panel_side_margin)
        val container = FrameLayout(context)
        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        panelParams.gravity = Gravity.CENTER
        panelParams.setMargins(sideMargin, 0, sideMargin, 0)
        container.addView(contentView, panelParams)
        setContentView(container)
    }
}
