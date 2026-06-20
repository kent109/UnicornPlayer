package com.unicorn.player.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.withStyledAttributes
import com.unicorn.player.R

/**
 * 通用标题栏自定义控件
 * 分为左、中、右三个区域：
 * - 左：返回按钮（默认显示）+ 标题
 * - 中：自定义内容
 * - 右：自定义内容
 */
class TitleBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private val ATTRS = intArrayOf(
            R.attr.title,
            R.attr.midView,
            R.attr.rightView,
            R.attr.backButtonVisible
        )
    }

    private val leftContainer: LinearLayout
    private val centerContainer: LinearLayout
    private val rightContainer: LinearLayout

    init {
        orientation = HORIZONTAL

        // 填充布局
        val view = LayoutInflater.from(context).inflate(R.layout.title_bar, this, true)

        leftContainer = view.findViewById(R.id.titleBarLeft)
        centerContainer = view.findViewById(R.id.titleBarCenter)
        rightContainer = view.findViewById(R.id.titleBarRight)

        // 解析自定义属性
        parseAttributes(attrs)

        // 默认返回按钮点击事件
        view.findViewById<View>(R.id.ivBack).setOnClickListener {
            context.let { ctx ->
                if (ctx is android.app.Activity) {
                    ctx.finish()
                }
            }
        }
    }

    /**
     * 解析自定义属性
     */
    @Suppress("ResourceType")
    private fun parseAttributes(attrs: AttributeSet?) {
        context.withStyledAttributes(attrs, ATTRS) {

            // 标题
            val title = getString(0)
            if (!title.isNullOrEmpty()) {
                setTitle(title)
            }

            // 中间区域布局
            val midViewRes = getResourceId(1, 0)
            if (midViewRes != 0) {
                LayoutInflater.from(context).inflate(midViewRes, centerContainer, true)
            }

            // 右侧区域布局
            val rightViewRes = getResourceId(2, 0)
            if (rightViewRes != 0) {
                LayoutInflater.from(context).inflate(rightViewRes, rightContainer, true)
            }

            // 返回按钮是否显示（默认显示）
            val backVisible = getBoolean(3, true)
            setBackButtonVisible(backVisible)

        }
    }

    /**
     * 获取中间容器中的指定ID视图
     */
    fun <T : View> getMidView(viewId: Int): T? {
        return centerContainer.findViewById(viewId)
    }

    /**
     * 获取左布局容器
     */
    fun getLeftContainer(): LinearLayout = leftContainer

    /**
     * 获取中布局容器
     */
    fun getCenterContainer(): LinearLayout = centerContainer

    /**
     * 获取右布局容器
     */
    fun getRightContainer(): LinearLayout = rightContainer

    /**
     * 设置标题文字
     */
    fun setTitle(title: String) {
        val tvTitle = leftContainer.findViewById<android.widget.TextView>(R.id.tvTitle)
        tvTitle.text = title
    }

    /**
     * 设置返回按钮点击回调（覆盖默认的 finish 行为）
     */
    fun setOnBackClickListener(listener: OnClickListener?) {
        leftContainer.findViewById<View>(R.id.ivBack).setOnClickListener(listener)
    }

    /**
     * 设置返回按钮是否可见
     */
    fun setBackButtonVisible(visible: Boolean) {
        leftContainer.findViewById<View>(R.id.ivBack).visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 向左布局添加视图
     */
    fun addViewToLeft(view: View) {
        leftContainer.addView(view)
    }

    /**
     * 向中布局添加视图
     */
    fun addViewToCenter(view: View) {
        centerContainer.addView(view)
    }

    /**
     * 向右布局添加视图
     */
    fun addViewToRight(view: View) {
        rightContainer.addView(view)
    }
}
