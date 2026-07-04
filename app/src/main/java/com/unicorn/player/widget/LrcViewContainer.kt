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
                return super.dispatchTouchEvent(ev)
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
