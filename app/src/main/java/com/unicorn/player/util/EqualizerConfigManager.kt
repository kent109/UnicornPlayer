package com.unicorn.player.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.unicorn.player.util.LogWriter
import java.nio.charset.Charset

/**
 * 均衡器自定义配置的 SAF 管理器（参考 [LyricsSaveManager] 实现）。
 *
 * 保存目录：Documents/Unicorn/Equalizer/
 *
 * 权限策略：
 * - 优先复用 [LyricsSaveManager] 已授权的 Documents 树 URI（同属 Unicorn 父目录），
 *   避免用户为均衡器功能重复授权；
 * - 若歌词树 URI 不可用，则使用本管理器自管的 SharedPreferences 中的树 URI；
 * - 若均不可用，调用方需通过 SAF 选择器引导用户授权。
 *
 * 关键约束（与 LyricsSaveManager 一致）：
 * - 必须通过 DocumentFile 在树内操作，不能直接拼接文档 URI，
 *   否则 ExternalStorageProvider 会要求系统级 MANAGE_DOCUMENTS 权限，导致 SecurityException。
 */
object EqualizerConfigManager {

    private const val UNICORN_DIR = "Unicorn"
    private const val EQUALIZER_DIR = "Equalizer"
    private const val PREFS_NAME = "equalizer_config_prefs"
    private const val KEY_TREE_URI = "tree_uri"
    private const val FILE_EXT = ".json"
    private const val TAG = "EqConfigManager"

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
            LogWriter.writeError("EqConfigSave", "获取持久权限失败: ${e.message}", e)
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
     * 确认 Documents/Unicorn/Equalizer 目录存在，不存在则创建。
     *
     * @return 目录存在且可用返回 true，创建失败返回 false
     */
    fun ensureSaveDirExists(context: Context): Boolean {
        return try {
            val treeUri = getAvailableTreeUri(context) ?: return false
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val unicorn = root.findFile(UNICORN_DIR)
                ?: root.createDirectory(UNICORN_DIR) ?: return false
            val eq = unicorn.findFile(EQUALIZER_DIR)
                ?: unicorn.createDirectory(EQUALIZER_DIR) ?: return false
            eq.isDirectory
        } catch (e: Throwable) {
            Log.e(TAG, "创建保存目录失败: ${e.message}")
            LogWriter.writeError("EqConfigSave", "创建保存目录失败: ${e.message}", e)
            false
        }
    }

    /**
     * 列出 Documents/Unicorn/Equalizer/ 下所有 .json 配置文件名。
     *
     * @return 文件名列表（包含 .json 后缀），按名称升序；目录不可用时返回空列表
     */
    fun listConfigFiles(context: Context): List<String> {
        return try {
            val treeUri = getAvailableTreeUri(context) ?: return emptyList()
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
            val unicorn = root.findFile(UNICORN_DIR) ?: return emptyList()
            val dir = unicorn.findFile(EQUALIZER_DIR) ?: return emptyList()
            dir.listFiles()
                .filter { it.isFile && it.name?.endsWith(FILE_EXT, ignoreCase = true) == true }
                .mapNotNull { it.name }
                .sortedBy { it.lowercase() }
        } catch (e: Throwable) {
            LogWriter.writeError("EqConfigSave", "列出配置文件失败: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 删除指定配置文件。
     *
     * @param fileName 文件名（包含 .json 后缀）
     * @return true 表示删除成功（含文件本就不存在的情况）；权限/目录异常返回 false
     */
    fun deleteConfig(context: Context, fileName: String): Boolean {
        return try {
            val treeUri = getAvailableTreeUri(context) ?: return false
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val unicorn = root.findFile(UNICORN_DIR) ?: return false
            val dir = unicorn.findFile(EQUALIZER_DIR) ?: return false
            val file = dir.findFile(fileName) ?: return true
            file.delete()
        } catch (e: Throwable) {
            LogWriter.writeError("EqConfigSave", "删除配置失败: $fileName, ${e.message}", e)
            false
        }
    }

    /**
     * 读取指定配置文件的内容。
     *
     * @param fileName 文件名（包含 .json 后缀）
     * @return 文件内容；不存在或读取失败返回 null
     */
    fun readConfig(context: Context, fileName: String): String? {
        return try {
            val treeUri = getAvailableTreeUri(context) ?: return null
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val unicorn = root.findFile(UNICORN_DIR) ?: return null
            val dir = unicorn.findFile(EQUALIZER_DIR) ?: return null
            val file = dir.findFile(fileName) ?: return null
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                input.readBytes().toString(Charset.forName("UTF-8"))
            }
        } catch (e: Throwable) {
            LogWriter.writeError("EqConfigSave", "读取配置失败: ${e.message}", e)
            null
        }
    }

    /**
     * 将配置内容写入 Documents/Unicorn/Equalizer/ 目录。
     * 同名文件会被覆盖。
     *
     * @param fileName 文件名（应包含 .json 后缀）
     * @param content JSON 文本
     * @return 是否写入成功
     */
    fun writeConfig(context: Context, fileName: String, content: String): Boolean {
        return try {
            val treeUri = getAvailableTreeUri(context) ?: return false
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val unicorn = root.findFile(UNICORN_DIR) ?: return false
            val dir = unicorn.findFile(EQUALIZER_DIR) ?: return false
            // 删除同名旧文件（覆盖写入）
            dir.findFile(fileName)?.delete()
            // 使用 application/octet-stream 避免 ExternalStorageProvider 为 text/plain 追加 .txt 后缀
            val file = dir.createFile("application/octet-stream", fileName) ?: return false
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(content.toByteArray(Charset.forName("UTF-8")))
            } ?: return false
            true
        } catch (e: Throwable) {
            LogWriter.writeError("EqConfigSave", "写入配置失败: ${e.message}", e)
            false
        }
    }

    /**
     * 规范化文件名：确保以 .json 后缀结尾。
     */
    fun normalizeFileName(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.endsWith(FILE_EXT, ignoreCase = true)) {
            trimmed
        } else {
            trimmed + FILE_EXT
        }
    }
}
