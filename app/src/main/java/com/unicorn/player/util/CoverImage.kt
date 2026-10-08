package com.unicorn.player.util

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.unicorn.player.ThemeSettingActivity
import java.io.File

/**
 * 列表封面图标绑定：有本地副本时 Glide 加载方形缩略图（圆角裁剪，图片不加高亮滤镜），
 * 无副本或文件丢失时回退默认图标（播放中用高亮色着色）。
 * 副本文件名为时间戳唯一路径，Glide 缓存不会残留旧图。
 *
 * [imageInsetDp] 仅作用于自定义图片：默认图标自身带留白，照片铺满同一尺寸会显得偏大，
 * 用内边距把照片向内收一点使其视觉大小对齐；回退默认图标时内边距清零。
 */
object CoverImage {

    /** 自定义照片的圆角半径（dp），内缩后可见区域约 36dp */
    private const val CORNER_RADIUS_DP = 6f

    fun bind(
        view: ImageView,
        coverPath: String,
        isPlaying: Boolean,
        defaultIconRes: Int,
        imageInsetDp: Int = 0
    ) {
        val hasCover = coverPath.isNotEmpty() && File(coverPath).let { it.exists() && it.length() > 0 }
        val inset = if (hasCover) {
            (imageInsetDp * view.context.resources.displayMetrics.density).toInt()
        } else 0
        view.setPadding(inset, inset, inset, inset)
        if (hasCover) {
            val radius = (CORNER_RADIUS_DP * view.context.resources.displayMetrics.density).toInt()
            Glide.with(view)
                .load(File(coverPath))
                .transform(CenterCrop(), RoundedCorners(radius))
                .placeholder(defaultIconRes)
                .error(defaultIconRes)
                .into(view)
            view.isSelected = false
            view.clearColorFilter()
        } else {
            view.setImageResource(defaultIconRes)
            if (isPlaying) {
                val highlightColor = ThemeSettingActivity.resolveHighlightColor(view.context)
                view.isSelected = true
                view.setColorFilter(highlightColor)
            } else {
                view.isSelected = false
                view.clearColorFilter()
            }
        }
    }
}
