package com.unicorn.player.widget

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * LrcView 容器，用于拦截长按事件
 *
 * LrcView 的 onTouchEvent 没有调用 super.onTouchEvent()，
 * 导致 Android 原生的 CheckForLongPress 机制失效。
 * 在此容器的 dispatchTouchEvent 中用 GestureDetector 检测长按，
 * 检测到后拦截事件并触发监听器。
 */
class LrcViewContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 长按回调 */
    var onLongPressListener: (() -> Unit)? = null

    /** 是否已进入长按拦截模式 */
    private var intercepting = false

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                intercepting = true
                onLongPressListener?.invoke()
            }
        })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 让 GestureDetector 观察事件（用于检测长按）
        gestureDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                intercepting = false
                super.dispatchTouchEvent(ev)
                // 必须消费 DOWN：LrcView 隐藏时（歌词关闭 / 显示"新建歌词"）没有子 View 接收事件，
                // 父布局便不再把后续 MOVE/UP 派发给本容器，GestureDetector 收不到 UP，
                // 待触发的长按定时器无法取消，轻点也会在超时后误入歌词搜索页
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // 一旦触发长按，后续所有事件都拦截
                return if (intercepting) true else super.dispatchTouchEvent(ev)
            }

            MotionEvent.ACTION_UP -> {
                return if (intercepting) {
                    performClick()
                    true
                } else {
                    super.dispatchTouchEvent(ev)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                return if (intercepting) {
                    true
                } else {
                    super.dispatchTouchEvent(ev)
                }
            }

            else -> return super.dispatchTouchEvent(ev)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
