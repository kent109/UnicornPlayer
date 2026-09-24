package com.unicorn.player.util

class ViewUtil {

    companion object {
        fun expandTouchTarget(view: android.view.View, expandPx: Int) {
            view.post {
                val parent = view.parent as android.view.ViewGroup
                val rect = android.graphics.Rect()
                view.getHitRect(rect)
                rect.top -= expandPx / 2
                rect.bottom += expandPx / 2
                rect.left -= expandPx / 2
                rect.right += expandPx / 2
                parent.touchDelegate = android.view.TouchDelegate(rect, view)
            }
        }
    }
}