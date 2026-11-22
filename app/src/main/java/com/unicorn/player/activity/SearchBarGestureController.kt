package com.unicorn.player.activity

import android.content.Context
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.SearchView
import androidx.core.view.isVisible
import com.unicorn.player.service.MusicService

/**
 * MainActivity 搜索栏手势识别控制器。
 *
 * 负责：
 *   - 长按搜索栏 → 切换均衡器开关（GestureDetector 挂 dispatchTouchEvent）
 *   - 双击搜索栏 → 滚动当前 tab 到顶部（ViewConfiguration 双击阈值）
 *   - 长按消费保护：长按触发后吞掉本次 up，不让 SearchView 展开
 *
 * 纯控制器对象：所有依赖通过构造参数/闭包传入，零 Context/Service 持有。
 */
internal class SearchBarGestureController(
    private val musicService: MusicService?,
    private val isMultiChoiceActive: () -> Boolean,
    private val isSearchCollapsed: () -> Boolean,
    private val onToggleEqualizer: () -> Unit,
) {
    /** Activity 需注入：searchView view（用于分发 CANCEL 事件） */
    var searchView: View? = null

    /** Activity 需注入：操作按钮区 layout（长按到此区域不触发快捷手势） */
    var actionButtonsLayout: View? = null

    // —— 内部状态（对应原 MainActivity 私有字段） ——
    private var lastSearchTapTime = 0L
    private val clickViewRect = Rect()
    private var searchLongPressFired = false
    private var searchLongPressTracking = false
    private var searchBarDownEvent: MotionEvent? = null

    private val gestureDetector by lazy {
        GestureDetector(
            null as Context?,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onLongPress(e: MotionEvent) {
                    // 标记"已被长按消费"，阻止后续双击滚顶
                    searchLongPressFired = true
                    // 合成 CANCEL 事件发给 SearchView，吞掉它的长按弹出菜单
                    val down = searchBarDownEvent
                    if (down != null) {
                        val cancel = MotionEvent.obtain(down)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        searchView?.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                    onToggleEqualizer()
                }
            }
        )
    }

    /** 触点（屏幕坐标）是否落在 view 可见区域内 */
    fun isTouchInView(ev: MotionEvent, view: View): Boolean {
        if (view.width == 0 || view.height == 0) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return ev.rawX >= location[0] && ev.rawX < location[0] + view.width &&
                ev.rawY >= location[1] && ev.rawY < location[1] + view.height
    }

    /** 按下点是否落在收起状态（非编辑）的搜索栏内 */
    private fun isDownOnCollapsedSearchBar(ev: MotionEvent): Boolean {
        if (isMultiChoiceActive()) return false
        if (!isSearchCollapsed()) return false
        val sv = searchView ?: return false
        if (!isTouchInView(ev, sv)) return false
        // 操作按钮区叠在搜索栏右端，长按图标不该走快捷手势
        val abl = actionButtonsLayout
        return !(abl != null && abl.isVisible && isTouchInView(ev, abl))
    }

    /** 长按跟踪入口：MainActivity.dispatchTouchEvent 每帧调用 */
    fun trackSearchBarLongPress(ev: MotionEvent?) {
        if (ev == null) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                searchLongPressFired = false
                searchLongPressTracking = isDownOnCollapsedSearchBar(ev)
                searchBarDownEvent?.recycle()
                searchBarDownEvent = if (searchLongPressTracking) MotionEvent.obtain(ev) else null
                if (searchLongPressTracking) {
                    gestureDetector.onTouchEvent(ev)
                }
            }
            else -> {
                if (!searchLongPressTracking) return
                gestureDetector.onTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP ||
                    ev.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    searchLongPressTracking = false
                    searchBarDownEvent?.recycle()
                    searchBarDownEvent = null
                }
            }
        }
    }

    /**
     * 双击滚顶：点击在 clickView 区域内、未被长按消费、两次 up 间隔 < 双击阈值时执行 action。
     * MainActivity.dispatchTouchEvent 里 ACTION_UP 时调用。
     */
    fun scrollToTopIfDoubleTap(clickView: View, ev: MotionEvent, action: () -> Unit) {
        clickView.getHitRect(clickViewRect)
        val location = IntArray(2)
        clickView.getLocationOnScreen(location)
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        val hit = clickViewRect.contains(
            x - location[0] + clickViewRect.left, y - location[1] + clickViewRect.top
        )
        if (!hit) return

        val canTrigger =
            ((clickView is SearchView && clickView.isIconified) || clickView !is SearchView)
                && !searchLongPressFired

        if (canTrigger) {
            val now = System.currentTimeMillis()
            if (now - lastSearchTapTime < ViewConfiguration.getDoubleTapTimeout()) {
                action()
                lastSearchTapTime = 0L
            } else {
                lastSearchTapTime = now
            }
        } else {
            lastSearchTapTime = 0L
        }
    }
}
