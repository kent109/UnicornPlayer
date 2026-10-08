package com.unicorn.player.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.unicorn.player.model.Cover
import java.io.File

/**
 * 封面图片存储：把系统选图器返回的 URI 压缩成方形缩略图副本，写入应用私有目录
 * filesDir/covers/，数据库只保存副本文件路径。
 *
 * - 先用 inSampleSize 降采样解码，避免大图全尺寸解码导致 OOM
 * - 每次修改写入新文件（文件名带时间戳），避免 Glide 按路径缓存旧图
 * - 存私有目录意味着用户无法在文件管理器中误删图片，不会产生悬空引用
 */
object CoverStore {

    const val MAX_SIZE = 512
    private const val JPEG_QUALITY = 90

    /**
     * 保存选中的图片副本，返回 (同键文件名前缀, 绝对路径)；失败返回 null。
     * 前缀用于删除同一 (type, key) 被替换的旧副本。
     */
    fun saveCover(context: Context, uri: Uri, type: Int, key: String): Pair<String, String>? {
        return try {
            val dir = File(context.filesDir, "covers")
            if (!dir.exists() && !dir.mkdirs()) return null
            val prefix = "${typePrefix(type)}_${sanitizeKey(key)}_"
            val dest = File(dir, "$prefix${System.currentTimeMillis()}.jpg")
            if (decodeAndSave(context, uri, dest)) prefix to dest.absolutePath else null
        } catch (e: Exception) {
            LogWriter.writeError("CoverStore", "保存封面副本失败: $uri", e)
            null
        }
    }

    /**
     * 数据库封面记录的索引前缀（与 [typePrefix] 一致），
     * 供列表端用 "前缀 + lowercase 分组键" 查找路径。
     */
    fun coverKeyPrefix(type: Int): String = typePrefix(type) + "_"

    /**
     * 删除同一 (type, key) 组内的旧副本，保留 [keepPath]（即刚写入的新副本）。
     * 组内文件名共享前缀，若不保留会把新图一起删掉。尽力清理，失败忽略。
     */
    fun deletePrevious(context: Context, prefix: String, keepPath: String) {
        try {
            val dir = File(context.filesDir, "covers")
            dir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { file ->
                if (file.absolutePath != keepPath) file.delete()
            }
        } catch (e: Exception) {
            LogWriter.writeError("CoverStore", "清理旧封面失败: $prefix", e)
        }
    }

    /**
     * 歌单封面副本按文件名前缀（"playlist_p<id>_"）整组清理，保留 [keepPath]。
     * 歌单弹窗中多次"修改"只更新预览不写库，确认时同组内会有多张候选图，
     * 写库成功后删除该组除最终选定图之外的所有副本。
     */
    fun cleanupPlaylistCoverGroup(context: Context, playlistId: Long, keepPath: String) {
        try {
            val dir = File(context.filesDir, "covers")
            val prefix = "playlist_p${playlistId}_"
            dir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { file ->
                if (file.absolutePath != keepPath) file.delete()
            }
        } catch (e: Exception) {
            LogWriter.writeError(
                "CoverStore", "清理歌单封面副本失败: playlistId=$playlistId", e
            )
        }
    }

    private fun typePrefix(type: Int): String = when (type) {
        Cover.TYPE_ARTIST -> "artist"
        Cover.TYPE_ALBUM -> "album"
        else -> "playlist"
    }

    private fun sanitizeKey(raw: String): String {
        return raw.replace(Regex("[^A-Za-z0-9_\\u4e00-\\u9fa5-]"), "_").take(48)
    }

    private fun decodeAndSave(context: Context, uri: Uri, dest: File): Boolean {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 只判断流是否存在：inJustDecodeBounds 模式下 decodeStream 必定返回 null，
        // 不能拿它的返回值当失败依据（否则永远走失败分支）
        val boundsInput = context.contentResolver.openInputStream(uri) ?: return false
        boundsInput.use { BitmapFactory.decodeStream(it, null, boundsOptions) }
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return false

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calcInSampleSize(boundsOptions.outWidth, boundsOptions.outHeight)
        }
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return false

        val cropped = centerCropSquare(raw, MAX_SIZE)
        return try {
            dest.outputStream().use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } catch (e: Exception) {
            LogWriter.writeError("CoverStore", "写入封面文件失败: ${dest.path}", e)
            false
        } finally {
            if (cropped !== raw) raw.recycle()
            cropped.recycle()
        }
    }

    /** 中心裁剪为 size×size 方形缩略图 */
    private fun centerCropSquare(source: Bitmap, size: Int): Bitmap {
        val scale = size.toFloat() / minOf(source.width, source.height)
        val width = (source.width * scale).toInt()
        val height = (source.height * scale).toInt()
        val matrix = Matrix().apply {
            preScale(scale, scale)
            postTranslate((size - width) / 2f, (size - height) / 2f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun calcInSampleSize(width: Int, height: Int): Int {
        var sample = 1
        val half = maxOf(width, height) / 2
        while (half / sample > MAX_SIZE) sample *= 2
        return sample
    }
}
