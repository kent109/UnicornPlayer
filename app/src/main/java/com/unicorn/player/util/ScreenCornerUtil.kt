package com.unicorn.player.util

import android.content.Context

/**
 * 读取机型屏幕物理圆角半径。直角屏或未暴露该参数的机型回退到调用方给定的默认值。
 */
object ScreenCornerUtil {

    // 各厂商在 framework 资源中暴露的屏幕圆角半径（单位为 dp）
    private val CORNER_RES_NAMES = arrayOf(
        "kgd_rounded_corner_config",   // OPPO / OnePlus
        "rounded_corner_radius_dp",    // vivo
        "sys_notice_bg_corner_radius", // 小米 / Redmi
        "hwc_emui_view_radius",        // 华为 EMUI
        "hwc_hmos_view_radius"         // 鸿蒙
    )

    private const val MAX_CORNER_DP = 60f

    /** 直角屏（或取不到机型圆角参数）时弹层使用的圆角半径 */
    const val SHEET_FALLBACK_CORNER_DP = 32f

    private var cachedDp = -1f

    /**
     * 获取屏幕圆角半径，px 为单位；获取不到时返回 defaultDp 换算后的 px
     */
    fun getCornerRadiusPx(context: Context, defaultDp: Float): Float {
        var dp = cachedDp
        if (dp < 0f) {
            dp = readCornerRadiusDp(context) ?: 0f
            cachedDp = dp
        }
        val radiusDp = if (dp > 0f) dp else defaultDp
        return DisplayUtil.dp2px(context, radiusDp).toFloat()
    }

    private fun readCornerRadiusDp(context: Context): Float? {
        val resources = context.resources
        for (name in CORNER_RES_NAMES) {
            val id = resources.getIdentifier(name, "integer", "android")
            if (id == 0) {
                continue
            }
            try {
                val value = resources.getInteger(id).toFloat()
                if (value > 0f && value <= MAX_CORNER_DP && !value.isNaN()) {
                    return value
                }
            } catch (e: Exception) {
                // 该资源定义异常，忽略并尝试下一个
            }
        }
        return null
    }
}
