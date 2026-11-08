package com.unicorn.player.ui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
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

    /** 点面板外的空白处是否关闭弹窗（[setCancelable] false 时无论如何都不关） */
    var cancelOnTouchOutside: Boolean = true

    // Dialog 没有对外的是否可取消读数，只能自己跟着 setCancelable 记一份
    private var cancelable: Boolean = true

    private val panelLocation = IntArray(2)

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

    override fun setCancelable(flag: Boolean) {
        super.setCancelable(flag)
        cancelable = flag
    }

    // 浮窗时代由框架代管的「点空白处关闭」：窗口铺满屏幕后既没有 outside 区域，
    // 也不会收到 outside-touch 回调，只能自己按面板矩形判断
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN
            && cancelable && cancelOnTouchOutside && !isOnPanel(event)
        ) {
            dismiss()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isOnPanel(event: MotionEvent): Boolean {
        contentView.getLocationInWindow(panelLocation)
        val left = panelLocation[0]
        val top = panelLocation[1]
        return event.x >= left && event.x < left + contentView.width
            && event.y >= top && event.y < top + contentView.height
    }
}
