package com.unicorn.player.util

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

class ScrollToTopHelper {

    companion object {
        private var lastTopTapTime = 0L
        private val clickViewRect = Rect()

        fun scrollToTop(clickView: View, ev: MotionEvent, action: Runnable) {
            // 获取clickView在屏幕上的区域
            clickView.getHitRect(clickViewRect)
            // 将触摸事件坐标转换到clickView的父坐标系
            val location = IntArray(2)
            clickView.getLocationOnScreen(location)
            val x = ev.rawX.toInt()
            val y = ev.rawY.toInt()
            if (clickViewRect.contains(
                    x - location[0] + clickViewRect.left, y - location[1] + clickViewRect.top
                )
            ) {
                // 点击在clickView范围内
                val canTrigger = true
                if (canTrigger) {
                    val currentTime = System.currentTimeMillis()
                    val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout()
                    if (currentTime - lastTopTapTime < doubleTapTimeout) {
                        // 双击检测：滚动当前标签页列表到顶部
                        action.run()
                        lastTopTapTime = 0L
                    } else {
                        lastTopTapTime = currentTime
                    }
                } else {
                    lastTopTapTime = 0L
                }
            }
        }
    }
}