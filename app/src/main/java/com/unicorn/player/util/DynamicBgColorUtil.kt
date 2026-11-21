package com.unicorn.player.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.unicorn.player.R
import com.unicorn.player.util.DynamicBgColorUtil.BG_BLEND_ALPHA
import com.unicorn.player.util.DynamicBgColorUtil.OVERRIDE_SIZE
import com.unicorn.player.util.DynamicBgColorUtil.adjustForReadability
import com.unicorn.player.util.DynamicBgColorUtil.persistedBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 动态背景取色（播放页）。
 *
 * - 有封面：Glide 解码小尺寸位图（[OVERRIDE_SIZE] px）后用 Palette 取占比最高的主色；
 * - 无封面（或封面解码失败）：沿用最近一次取色值；无历史取色时以 R.color.primary
 *   为底色（该色偏深，禁止直接使用，必须走亮度回混）。
 *
 * 取出的原始色先按 [BG_BLEND_ALPHA] 与页面底色（R.color.background）预合成——
 * 等效半透明叠加，观感变浅；因为 PlayerActivity 的窗口背景本身就是动态色
 * （不透明窗口会忽略 alpha），真 alpha 只会叠到 window 的动态色上，必须在取色侧
 * 预合成出不透明的最终色，页面与窗口两层共用同一结果。
 * 合成后再经 [adjustForReadability]：日间过暗的背景向白色回混、夜间过亮的背景向
 * 黑色回混，保证标题、时间、按钮等主题固定色文字始终可读。
 *
 * 最近取色值缓存的是合成与回混前的原始色（进程内存 + 持久化 [persistedBase]），重用时按当前
 * 日夜模式重新合成回混，避免日夜切换后沿用反色的旧结果；原始色也持久化，让无封面回退
 * 不依赖取色时机（ViewPager 多页快照、窗口与页面并发取色都落到同一历史值）。最终色按
 * 日夜分键持久化到 SharedPreferences（[cachedColor]），供修改封面弹窗面板直接套用当前取色值。
 *
 * 采样尺寸、合成强度与亮度阈值收敛在本类，调视觉只改这里。
 */
object DynamicBgColorUtil {

    /** 取色采样尺寸：Palette 只需大致色彩分布，小图解码快、Glide 内存缓存占用小 */
    private const val OVERRIDE_SIZE = 96

    /** 日间背景最低亮度：低于它向白色回混，避免深色封面压成深底配日间深色文字 */
    private const val DAY_MIN_LUMINANCE = 200f

    /** 夜间背景最高亮度：高于它向黑色回混，避免浅色封面糊成浅底配夜间浅色文字 */
    private const val NIGHT_MAX_LUMINANCE = 90f

    /** 原始色向页面底色合成的强度（0-255，相当于 alpha）：越小背景越浅，调"深浅"主要改这个 */
    private const val BG_BLEND_ALPHA = 170

    // 最近一次最终色的持久化（按日夜分键）：修改封面弹窗面板用它直接套用当前取色值
    private const val PREFS_NAME = "dynamic_bg_color"
    private const val KEY_LAST_COLOR_DAY = "last_final_day"
    private const val KEY_LAST_COLOR_NIGHT = "last_final_night"
    private const val KEY_LAST_BASE = "last_base"

    // 最近一次取色的原始色（未做合成与日夜回混）。无封面/解码失败时沿用，每次取色都刷新
    @Volatile
    private var lastBaseColor: Int? = null

    /**
     * 取色（内部已切 IO 线程），回退链：封面主色 > 最近一次取色值 > R.color.primary。
     * 封面解码或取色失败不抛异常，直接走回退链。
     */
    suspend fun extract(context: Context, albumArt: String?): Int =
        withContext(Dispatchers.IO) {
            val base = if (albumArt.isNullOrEmpty()) {
                lastBaseColor ?: persistedBase(context) ?: defaultBaseColor(context)
            } else {
                extractFromArt(context, albumArt) ?: lastBaseColor
                ?: persistedBase(context) ?: defaultBaseColor(context)
            }
            lastBaseColor = base
            persistBase(context, base)
            val blended = blendOverBackground(context, base)
            val final = adjustForReadability(blended, isNightMode(context))
            persistColor(context, final)
            final
        }

    /** 上次取色的持久化最终色（按当前日夜模式取键）；无记录返回 null */
    fun cachedColor(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getInt(prefsKey(context), Int.MIN_VALUE)
        return if (value == Int.MIN_VALUE) null else value
    }

    private fun persistColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(prefsKey(context), color)
        }
    }

    private fun prefsKey(context: Context): String =
        if (isNightMode(context)) KEY_LAST_COLOR_NIGHT else KEY_LAST_COLOR_DAY

    /** 上次取色的持久化原始色：无封面回退不依赖取色时机，多页快照与并发取色都落到同一历史值 */
    private fun persistedBase(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getInt(KEY_LAST_BASE, Int.MIN_VALUE)
        return if (value == Int.MIN_VALUE) null else value
    }

    private fun persistBase(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_LAST_BASE, color)
        }
    }

    /** 等效半透明：按 [BG_BLEND_ALPHA] 把原始色叠到页面底色上，输出不透明颜色 */
    private fun blendOverBackground(context: Context, base: Int): Int {
        val background = ContextCompat.getColor(context, R.color.background)
        val translucent = (base and 0x00FFFFFF) or (BG_BLEND_ALPHA shl 24)
        return ColorUtils.compositeColors(translucent, background)
    }

    /** Glide 解码小图并取主色；封面不存在/解码失败返回 null */
    private fun extractFromArt(context: Context, albumArt: String): Int? {
        return runCatching {
            val appContext = context.applicationContext
            val target = Glide.with(appContext)
                .asBitmap()
                .load(albumArt)
                .override(OVERRIDE_SIZE)
                .submit()
            try {
                val bitmap = target.get()
                val palette = Palette.from(bitmap).generate()
                palette.dominantSwatch?.rgb
                    ?: palette.vibrantSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
            } finally {
                Glide.with(appContext).clear(target)
            }
        }.getOrNull()
    }

    /** 无封面且无历史取色时的兜底底色（资源自带日夜变体，偏深，禁止直接使用） */
    private fun defaultBaseColor(context: Context): Int =
        ContextCompat.getColor(context, R.color.primary)

    private fun isNightMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private fun adjustForReadability(color: Int, night: Boolean): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        return if (night) {
            if (luminance <= NIGHT_MAX_LUMINANCE) color
            else blend(color, Color.BLACK, (luminance - NIGHT_MAX_LUMINANCE) / luminance)
        } else {
            if (luminance >= DAY_MIN_LUMINANCE) color
            else blend(color, Color.WHITE, (DAY_MIN_LUMINANCE - luminance) / (255f - luminance))
        }
    }

    private fun blend(color: Int, target: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun mix(c: Int, t: Int) = (c + (t - c) * f).toInt()
        return Color.rgb(
            mix(Color.red(color), Color.red(target)),
            mix(Color.green(color), Color.green(target)),
            mix(Color.blue(color), Color.blue(target))
        )
    }
}
