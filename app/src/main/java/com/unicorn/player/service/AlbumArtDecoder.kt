package com.unicorn.player.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import com.unicorn.player.R
import com.unicorn.player.database.MusicDatabase
import com.unicorn.player.model.Cover
import com.unicorn.player.model.Song
import java.io.InputStream

/**
 * 专辑封面解码器：按序尝试 4 种来源，返回第一张可用 Bitmap 或占位图。
 *
 * 来源优先级：
 *   1. song.albumArt 本地路径或 content:// URI（手动设置/数据库记录）
 *   2. 音频文件内嵌封面（MediaMetadataRetriever.embeddedPicture）
 *   3. covers 表里的专辑/歌手分组封面（按分组名 lowercase 匹配）
 *   4. 默认占位图（按当前日夜模式取对应 drawable）
 *
 * 设计上**零闭包依赖** MusicService 的内部字段/方法：
 *   - Context 构造传入（Service 的 applicationContext 即可）
 *   - maxArtSize 限制输出位图边长（媒体会话封面超 1MB 会被静默丢弃）
 *   - logError 闭包由调用方注入（MusicService 用 LogWriter）
 */
internal class AlbumArtDecoder(
    private val context: Context,
    private val maxArtSize: Int,
    private val logError: (String, Throwable) -> Unit,
) {

    /** 取序：封面记录 → 音频内嵌图 → 专辑/歌手分组封面 → 中性占位图 */
    fun decodeAlbumArt(song: Song): Bitmap? {
        val source = song.albumArt
        val fromSource =
            if (source.isNullOrEmpty()) null else runCatching { decodeArtStream(source) }.getOrNull()
        return fromSource
            ?: decodeEmbeddedArt(song.path)
            ?: decodeGroupCover(song)
            ?: placeholderArt()
    }

    /** 专辑/歌手分组封面（covers 表，键为分组名 lowercase） */
    private fun decodeGroupCover(song: Song): Bitmap? {
        val dao = MusicDatabase.getDatabase(context).coverDao()
        val path = dao.getCoverSync(Cover.TYPE_ALBUM, song.album.lowercase())?.coverPath
            ?: dao.getCoverSync(Cover.TYPE_ARTIST, song.artist.lowercase())?.coverPath
            ?: return null
        return runCatching { decodeArtStream(path) }.getOrNull()
    }

    /** 一路都取不到图时也要给一张：锁屏媒体卡片对"无封面"会保留上一张，必须显式占位 */
    private fun placeholderArt(): Bitmap? {
        val drawable = AppCompatResources.getDrawable(nightContext(), R.drawable.art_placeholder)
            ?: return null
        val size = maxArtSize
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** 当前真正生效的日夜标志：应用内指定优先，跟随系统时读系统全局配置 */
    private fun currentNightMode(): Int = when (AppCompatDelegate.getDefaultNightMode()) {
        AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
        AppCompatDelegate.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
        else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    }

    private fun nightContext(): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or currentNightMode()
        return context.createConfigurationContext(configuration)
    }

    private fun decodeArtStream(source: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openAlbumArtStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val decoded = openAlbumArtStream(source)?.use {
            BitmapFactory.decodeStream(
                it, null,
                BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds) })
        } ?: return null
        return fitArtSize(decoded)
    }

    /**
     * 音频文件内嵌封面：媒体库的 content://media/external/audio/albumart/<albumId> 行未必存在
     * （无封面图或 album_id 为 UNKNOWN），打开会抛 No album art found，此时只能从文件标签里取。
     */
    private fun decodeEmbeddedArt(path: String): Bitmap? {
        val retriever = android.media.MediaMetadataRetriever()
        var bitmap: Bitmap? = null
        try {
            retriever.setDataSource(path)
            val bytes = retriever.embeddedPicture
            if (bytes != null && bytes.isNotEmpty()) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    bitmap = BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds) })
                        ?.let { fitArtSize(it) }
                }
            }
        } catch (e: Exception) {
            logError("读取内嵌封面失败: $path", e)
        } finally {
            runCatching { retriever.release() }
        }
        return bitmap
    }

    private fun sampleSizeFor(bounds: BitmapFactory.Options): Int {
        var sampleSize = 1
        val half = maxOf(bounds.outWidth, bounds.outHeight) / 2
        while (half / sampleSize > maxArtSize) sampleSize *= 2
        return sampleSize
    }

    /**
     * inSampleSize 只能按 2 的幂降采样，解码结果长边仍可能接近 2×maxArtSize；
     * 等比缩放到边长上限以内，否则字节数超出系统对会话封面位图的 1MB 限制会被静默丢弃。
     */
    private fun fitArtSize(source: Bitmap): Bitmap {
        val maxEdge = maxOf(source.width, source.height)
        if (maxEdge <= maxArtSize) return source
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * maxArtSize / maxEdge).coerceAtLeast(1),
            (source.height * maxArtSize / maxEdge).coerceAtLeast(1), true
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun openAlbumArtStream(source: String): InputStream? =
        if (source.startsWith("content://")) context.contentResolver.openInputStream(
            Uri.parse(
                source
            )
        )
        else java.io.File(source).inputStream()
}
