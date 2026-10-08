package com.unicorn.player.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

/**
 * 系统图片选择 Intent 构建：
 *
 * - 优先 ACTION_PICK + MediaStore 图片表 URI：由系统相册/图库接管，直接进入相册视图。
 *   GET_CONTENT 在部分机型上由 DocumentsUI 接管，会落到文档界面（且记住上次目录），
 *   无法定位到相册。
 * - 无应用响应 ACTION_PICK 时回退 GET_CONTENT（MIME 限定 image）。
 *
 * 两者返回的 URI 都可被 ContentResolver 一次性读取，配合 CoverStore 拷贝副本无需持久授权。
 */
object ImagePicker {

    @SuppressLint("QueryPermissionsNeeded")
    @Suppress("DEPRECATION")
    fun createIntent(context: Context): Intent {
        val gallery = Intent(
            Intent.ACTION_PICK,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        )
        val hasGallery = gallery.resolveActivityInfo(
            context.packageManager, 0
        ) != null
        return if (hasGallery) {
            gallery
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        }
    }

    /** 从 StartActivityForResult 结果中取出选图 URI（无结果时返回 null） */
    fun extractUri(data: Intent?): Uri? = data?.data
}
