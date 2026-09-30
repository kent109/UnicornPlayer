package com.unicorn.player.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes

class KyrieDrawable private constructor(
    private val kyrieDrawable: com.github.alexjlockwood.kyrie.KyrieDrawable
) : Drawable(), Drawable.Callback {

    interface Listener {
        fun onAnimationStart(drawable: KyrieDrawable) {}
        fun onAnimationEnd(drawable: KyrieDrawable) {}
    }

    private val listeners = mutableListOf<Listener>()

    init {
        kyrieDrawable.callback = this
        kyrieDrawable.addListener(object : com.github.alexjlockwood.kyrie.KyrieDrawable.Listener {
            override fun onAnimationStart(drawable: com.github.alexjlockwood.kyrie.KyrieDrawable) {
                listeners.forEach { it.onAnimationStart(this@KyrieDrawable) }
            }

            override fun onAnimationUpdate(drawable: com.github.alexjlockwood.kyrie.KyrieDrawable) {}

            override fun onAnimationPause(drawable: com.github.alexjlockwood.kyrie.KyrieDrawable) {}

            override fun onAnimationResume(drawable: com.github.alexjlockwood.kyrie.KyrieDrawable) {}

            override fun onAnimationCancel(drawable: com.github.alexjlockwood.kyrie.KyrieDrawable) {}

            override fun onAnimationEnd(drawable: com.github.alexjlockwood.kyrie.KyrieDrawable) {
                listeners.forEach { it.onAnimationEnd(this@KyrieDrawable) }
            }
        })
    }

    companion object {
        fun create(context: Context, @DrawableRes resId: Int): KyrieDrawable {
            val kyrieDrawable = com.github.alexjlockwood.kyrie.KyrieDrawable.create(context, resId)
                ?: throw IllegalArgumentException("Failed to create KyrieDrawable for resId=$resId")
            return KyrieDrawable(kyrieDrawable)
        }
    }

    var currentPlayTime: Long
        get() = kyrieDrawable.currentPlayTime
        set(value) {
            kyrieDrawable.currentPlayTime = value
        }

    fun start() {
        kyrieDrawable.start()
    }

    fun pause() {
        kyrieDrawable.pause()
    }

    fun resume() {
        kyrieDrawable.resume()
    }

    fun stop() {
        kyrieDrawable.stop()
    }

    fun isRunning(): Boolean = kyrieDrawable.isRunning

    fun isPaused(): Boolean = kyrieDrawable.isPaused

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun setAnimationDuration(durationMs: Long) {
        try {
            val timelineField = kyrieDrawable.javaClass.getDeclaredField("timeline")
            timelineField.isAccessible = true
            val timeline = timelineField.get(kyrieDrawable)

            val propertiesField = timeline.javaClass.getDeclaredField("properties")
            propertiesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val properties = propertiesField.get(timeline) as List<Any>

            for (property in properties) {
                val animationsField = property.javaClass.getDeclaredField("animations")
                animationsField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val animations = animationsField.get(property) as List<Any>

                for (animation in animations) {
                    val repeatCountField = try {
                        animation.javaClass.getDeclaredField("repeatCount")
                    } catch (e: NoSuchFieldException) {
                        null
                    }

                    if (repeatCountField != null) {
                        repeatCountField.isAccessible = true
                        val repeatCount = repeatCountField.getInt(animation)
                        if (repeatCount != 0) {
                            continue
                        }
                    }

                    val durationField = animation.javaClass.getDeclaredField("duration")
                    durationField.isAccessible = true
                    durationField.setLong(animation, durationMs)
                }

                val propertyTotalDurationField = property.javaClass.getDeclaredField("totalDuration")
                propertyTotalDurationField.isAccessible = true
                propertyTotalDurationField.setLong(property, durationMs)
            }

            val timelineTotalDurationField = timeline.javaClass.getDeclaredField("totalDuration")
            timelineTotalDurationField.isAccessible = true
            timelineTotalDurationField.setLong(timeline, durationMs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun draw(canvas: Canvas) {
        kyrieDrawable.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        kyrieDrawable.alpha = alpha
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = kyrieDrawable.opacity

    override fun setColorFilter(colorFilter: ColorFilter?) {
        kyrieDrawable.colorFilter = colorFilter
    }

    override fun getIntrinsicWidth(): Int = kyrieDrawable.intrinsicWidth

    override fun getIntrinsicHeight(): Int = kyrieDrawable.intrinsicHeight

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        kyrieDrawable.bounds = bounds
    }

    override fun invalidateDrawable(who: Drawable) {
        callback?.invalidateDrawable(this)
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        callback?.scheduleDrawable(this, what, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        callback?.unscheduleDrawable(this, what)
    }
}
