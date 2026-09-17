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
 * 歌单导出文件的 SAF 管理器（参考 [EqualizerConfigManager] 实现）。
 *
 * 保存目录：Documents/Unicorn/Playlist/
 * 文件名："<playlistId>.json"，与歌单名解耦，重命名歌单不影响；重复导出直接覆盖。
 *
 * 权限策略与均衡器一致：
 * - 优先复用歌词模块已授权的 Documents 树 URI（同属 Unicorn 父目录）；
 * - 其次使用本管理器自管的 SharedPreferences 中的树 URI；
 * - 均不可用时由调用方通过 SAF 选择器引导用户授权。
 */
object PlaylistFileManager {

    private const val UNICORN_DIR = "Unicorn"
    private const val PLAYLIST_DIR = "Playlist"
    private const val PREFS_NAME = "playlist_file_prefs"
    private const val KEY_TREE_URI = "tree_uri"
    private const val FILE_EXT = ".json"
    private const val TAG = "PlaylistFileMgr"

    /** 导出文件数量上限（与均衡器一致，达到后需先清理；覆盖同一歌单的旧文件不受限） */
    const val MAX_EXPORT_COUNT = 10

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
            .edit { putString(KEY_TREE_URI, treeUri.toString()) }
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Throwable) {
            Log.e(TAG, "获取持久权限失败: ${e.message}")
        }
    }

    /**
     * 获取本管理器自管的树 URI（不依赖歌词模块）。
     */
    private fun getOwnTreeUri(context: Context): Uri? {
        val str = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null) ?: return null
        return try {
            str.toUri()
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 获取可用的树 URI：优先歌词模块已授权的树 URI，其次本管理器自管的树 URI。
     */
    fun getAvailableTreeUri(context: Context): Uri? {
        // 优先复用歌词模块已授权的树 URI（同指向 Documents）
        if (LyricsSaveManager.hasSavedTreeUri(context) &&
            LyricsSaveManager.isTreePermissionValid(context)
        ) {
            return LyricsSaveManager.getSavedTreeUri(context)
        }
        // 其次使用本管理器自管的树 URI
        val own = getOwnTreeUri(context) ?: return null
        return try {
            val valid = context.contentResolver.persistedUriPermissions.any {
                it.uri == own && it.isReadPermission && it.isWritePermission
            }
            if (valid) own else null
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 检查是否拥有可用的读写权限（来自歌词模块或本管理器）。
     */
    fun hasPermission(context: Context): Boolean {
        return getAvailableTreeUri(context) != null
    }

    /**
     * 确认 Documents/Unicorn/Playlist 目录存在，不存在则创建。
     *
     * @return 目录存在且可用返回 true，创建失败返回 false
     */
    fun ensureSaveDirExists(context: Context): Boolean {
        return try {
            val treeUri = getAvailableTreeUri(context) ?: return false
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val unicorn = root.findFile(UNICORN_DIR)
                ?: root.createDirectory(UNICORN_DIR) ?: return false
            val dir = unicorn.findFile(PLAYLIST_DIR)
                ?: unicorn.createDirectory(PLAYLIST_DIR) ?: return false
            dir.isDirectory
        } catch (e: Throwable) {
            Log.e(TAG, "创建保存目录失败: ${e.message}")
            false
        }
    }

    /**
     * 列出 Documents/Unicorn/Playlist/ 下所有 .json 导出文件名。
     *
     * @return 文件名列表（包含 .json 后缀），按名称升序；目录不可用时返回空列表
     */
    fun listExportFiles(context: Context): List<String> {
        return try {
            val treeUri = getAvailableTreeUri(context) ?: return emptyList()
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
            val unicorn = root.findFile(UNICORN_DIR) ?: return emptyList()
            val dir = unicorn.findFile(PLAYLIST_DIR) ?: return emptyList()
            dir.listFiles()
                .filter { it.isFile && it.name?.endsWith(FILE_EXT, ignoreCase = true) == true }
                .mapNotNull { it.name }
                .sortedBy { it.lowercase() }
        } catch (e: Throwable) {
            Log.e(TAG, "列出导出文件失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 读取指定导出文件的内容。
     *
     * @param fileName 文件名（包含 .json 后缀）
     * @return 文件内容；不存在或读取失败返回 null
     */
    fun readExport(context: Context, fileName: String): String? {
        return try {
            val treeUri = getAvailableTreeUri(context) ?: return null
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val unicorn = root.findFile(UNICORN_DIR) ?: return null
            val dir = unicorn.findFile(PLAYLIST_DIR) ?: return null
            val file = dir.findFile(fileName) ?: return null
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                input.readBytes().toString(Charset.forName("UTF-8"))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "读取导出文件失败: ${e.message}")
            null
        }
    }

    /**
     * 将导出内容写入 Documents/Unicorn/Playlist/ 目录。同名文件会被覆盖。
     *
     * @param fileName 文件名（应包含 .json 后缀）
     * @param content JSON 文本
     * @return 是否写入成功
     */
    fun writeExport(context: Context, fileName: String, content: String): Boolean {
        return try {
            val treeUri = getAvailableTreeUri(context) ?: return false
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val unicorn = root.findFile(UNICORN_DIR) ?: return false
            val dir = unicorn.findFile(PLAYLIST_DIR) ?: return false
            // 删除同名旧文件（覆盖写入）
            dir.findFile(fileName)?.delete()
            // 使用 application/octet-stream 避免 ExternalStorageProvider 为 text/plain 追加 .txt 后缀
            val file = dir.createFile("application/octet-stream", fileName) ?: return false
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(content.toByteArray(Charset.forName("UTF-8")))
            } ?: return false
            true
        } catch (e: Throwable) {
            Log.e(TAG, "写入导出文件失败: ${e.message}")
            false
        }
    }

    /**
     * 按文件名删除导出文件（清理弹窗使用）。
     * 文件不存在视为成功；无授权/目录异常返回 false（调用方可静默忽略）。
     *
     * @param fileName 文件名（包含 .json 后缀）
     */
    fun deleteFile(context: Context, fileName: String): Boolean {
        return try {
            val treeUri = getAvailableTreeUri(context) ?: return false
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val unicorn = root.findFile(UNICORN_DIR) ?: return false
            val dir = unicorn.findFile(PLAYLIST_DIR) ?: return false
            val file = dir.findFile(fileName) ?: return true
            file.delete()
        } catch (e: Throwable) {
            Log.e(TAG, "删除导出文件失败: $fileName, ${e.message}")
            false
        }
    }

    /**
     * 删除指定歌单的导出文件（删除歌单时同步调用）。
     * 文件不存在视为成功；无授权/目录异常返回 false（调用方可静默忽略）。
     *
     * @param playlistId 歌单 ID
     */
    fun deleteExport(context: Context, playlistId: Long): Boolean {
        return deleteFile(context, fileNameForPlaylist(playlistId))
    }

    /**
     * 歌单导出文件名规则："<playlistId>.json"
     */
    fun fileNameForPlaylist(playlistId: Long): String = "$playlistId$FILE_EXT"
}
