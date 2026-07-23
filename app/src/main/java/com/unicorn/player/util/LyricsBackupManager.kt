package com.unicorn.player.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.nio.charset.Charset

/**
 * 歌词备份工具类（SAF 实现）
 *
 * 备份目录：Documents/Unicorn/Lyrics/
 * 使用 SAF 树 URI 授权。用户通过系统文件选择器授权 Documents 目录后，
 * 通过 DocumentFile API 在树内逐级创建子目录和文件。
 *
 * 关键约束：
 * - 必须通过 DocumentFile 在树内操作，不能直接拼接文档 URI，
 * - 否则 ExternalStorageProvider 会要求系统级 MANAGE_DOCUMENTS 权限，导致 SecurityException。
 */
object LyricsBackupManager {

    private const val UNICORN_DIR = "Unicorn"
    private const val LYRICS_DIR = "Lyrics"
    private const val PREFS_NAME = "lyrics_backup_prefs"
    private const val KEY_TREE_URI = "tree_uri"

    /**
     * 构建 SAF 选择器的初始 URI，定位到 Documents 目录。
     */
    fun getInitialUri(): Uri {
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Documents"
        )
    }

    /**
     * 保存用户授权的树 URI 并获取持久权限。
     */
    fun saveTreeUri(context: Context, treeUri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit { putString(KEY_TREE_URI, treeUri.toString()) }
            ?: LogWriter.writeError("LyricsBackup", "getSharedPreferences 返回 null", null)
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Throwable) {
            LogWriter.writeError("LyricsBackup", "获取持久权限失败: ${e.message}", e)
        }
    }

    /**
     * 读取已保存的树 URI。
     */
    fun getSavedTreeUri(context: Context): Uri? {
        val str = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_TREE_URI, null) ?: return null
        return try {
            str.toUri()
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 检查是否已保存树 URI（不检查权限）。
     */
    fun hasSavedTreeUri(context: Context): Boolean {
        return getSavedTreeUri(context) != null
    }

    /**
     * 检查已保存的树 URI 是否仍持有有效的读写权限。
     */
    fun isTreePermissionValid(context: Context): Boolean {
        return try {
            val uri = getSavedTreeUri(context) ?: return false
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 确认 Documents/Unicorn/Lyrics 目录存在，不存在则创建。
     *
     * @return 目录存在且可用返回 true，创建失败返回 false
     */
    fun ensureBackupDirExists(context: Context): Boolean {
        return try {
            val treeUri = getSavedTreeUri(context) ?: return false
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            // 在树内逐级查找/创建 Unicorn 子目录
            val unicorn = root.findFile(UNICORN_DIR)
                ?: root.createDirectory(UNICORN_DIR) ?: return false
            // 在 Unicorn 内查找/创建 Lyrics 子目录
            val lyrics = unicorn.findFile(LYRICS_DIR)
                ?: unicorn.createDirectory(LYRICS_DIR) ?: return false
            Log.d("LyricsBackup", "lyrics.isDirectory=${lyrics.isDirectory}")
            lyrics.isDirectory
        } catch (e: Throwable) {
            Log.e("LyricsBackup", "创建备份目录失败: ${e.message}")
            LogWriter.writeError("LyricsBackup", "创建备份目录失败: ${e.message}", e)
            false
        }
    }

    /**
     * 将歌词内容写入 Documents/Unicorn/Lyrics/ 目录。
     *
     * @param context Android 上下文
     *
     *
     * @param fileName 文件名，如 "Artist - Title.lrc"
     * @param content 歌词文本内容
     * @return 是否写入成功
     */
    fun writeLrcFile(context: Context, fileName: String, content: String): Boolean {
        return try {
            val treeUri = getSavedTreeUri(context) ?: return false
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val unicorn = root.findFile(UNICORN_DIR) ?: return false
            val dir = unicorn.findFile(LYRICS_DIR) ?: return false
            // 删除同名旧文件（覆盖写入）
            dir.findFile(fileName)?.delete()
            // 在树内创建文件（使用 application/octet-stream 避免 ExternalStorageProvider 为 text/plain 追加 .txt 后缀）
            val file = dir.createFile("application/octet-stream", fileName) ?: return false
            // 通过 ContentResolver 写入内容
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(content.toByteArray(Charset.forName("UTF-8")))
            } ?: return false
            true
        } catch (e: Throwable) {
            LogWriter.writeError("LyricsBackup", "写入备份失败: ${e.message}", e)
            false
        }
    }
}
