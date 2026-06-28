package com.unicorn.player.util

import android.content.Context
import android.util.TypedValue

/**
 * 显示单位转换工具类
 */
object DisplayUtil {

    /**
     * 将 sp 转换为 px
     */
    fun sp2px(context: Context, spValue: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            spValue.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * 将 dp 转换为 px
     */
    fun dp2px(context: Context, dpValue: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dpValue,
            context.resources.displayMetrics
        ).toInt()
    }
}
