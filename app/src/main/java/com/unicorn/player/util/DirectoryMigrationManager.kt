package com.unicorn.player.util

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.unicorn.player.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/**
 * 目录迁移管理器：从 Documents/Unicorn 迁移到 Download/Unicorn
 *
 * 迁移流程：
 * 1. 检测旧 Documents 树 URI 是否有效
 * 2. 使用 MediaStore API 预创建 Download/Unicorn 目录
 * 3. 引导用户授权 Download/Unicorn 目录
 * 4. 复制 Lyrics、Equalizer、Playlist 子目录下的所有文件
 *    （旧树根 = Documents，需找 Unicorn 子目录；新树根 = Unicorn 本身）
 * 5. 删除旧 Documents/Unicorn 目录
 * 6. 释放旧 Documents 树 URI 权限
 * 7. 标记迁移完成
 */
object DirectoryMigrationManager {

    private const val TAG = "DirMigration"
    private const val PREFS_NAME = "directory_migration_prefs"
    private const val KEY_MIGRATION_COMPLETED = "migration_completed"
    private const val KEY_OLD_TREE_URI = "old_tree_uri"

    private const val UNICORN_DIR = "Unicorn"
    private const val LYRICS_DIR = "Lyrics"
    private const val EQUALIZER_DIR = "Equalizer"
    private const val PLAYLIST_DIR = "Playlist"

    /**
     * 构建旧 Documents 目录的 SAF 初始 URI
     */
    fun getOldDocumentsUri(): Uri {
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Documents"
        )
    }

    /**
     * 使用 MediaStore API 预创建 Download/Unicorn 目录
     *
     * 通过插入一个临时文件到 Download/Unicorn/ 路径，系统会自动创建目录结构。
     * 创建成功后删除临时文件，目录会保留。
     *
     * @return 是否成功创建（目录已存在也视为成功）
     */
    fun ensureUnicornDirExists(context: Context): Boolean {
        return try {
            // 检查目录是否已存在（通过尝试列出文件）
            val downloadDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val unicornDir = java.io.File(downloadDir, UNICORN_DIR)
            if (unicornDir.exists() && unicornDir.isDirectory) {
                Log.d(TAG, "Download/Unicorn 目录已存在")
                return true
            }

            // 使用 MediaStore 创建临时文件以触发目录创建
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, ".unicorn_temp")
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$UNICORN_DIR/")
            }

            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            )

            if (uri != null) {
                // 删除临时文件，目录会保留
                context.contentResolver.delete(uri, null, null)
                Log.d(TAG, "已创建 Download/Unicorn 目录")
                true
            } else {
                Log.e(TAG, "MediaStore 插入失败")
                false
            }
        } catch (e: Throwable) {
            LogWriter.writeError(TAG, "创建 Download/Unicorn 目录失败: ${e.message}", e)
            false
        }
    }

    /**
     * 检查是否需要迁移（旧版本升级且未完成迁移）
     *
     * 检测逻辑：
     * 1. 已标记迁移完成 → 无需迁移
     * 2. Documents/Unicorn 目录存在 → 需要迁移（物理检测，最可靠）
     * 3. 存在旧的 Documents 树 URI → 需要迁移（备用检测）
     */
    fun needsMigration(context: Context): Boolean {
        if (isMigrationCompleted(context)) {
            Log.d(TAG, "迁移已标记完成，跳过")
            return false
        }

        val dirExists = documentsUnicornDirExists()
        Log.d(TAG, "Documents/Unicorn 目录存在: $dirExists")
        if (dirExists) {
            return true
        }

        val oldUri = getOldDocumentsTreeUri(context)
        Log.d(TAG, "旧的 Documents 树 URI: $oldUri")
        if (oldUri != null) {
            return true
        }

        Log.d(TAG, "无需迁移")
        return false
    }

    /**
     * 检查 Documents/Unicorn 目录是否存在
     */
    private fun documentsUnicornDirExists(): Boolean {
        return try {
            val documentsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
            )
            Log.d(TAG, "Documents 路径: ${documentsDir.absolutePath}")
            val unicornDir = java.io.File(documentsDir, UNICORN_DIR)
            Log.d(TAG, "Unicorn 路径: ${unicornDir.absolutePath}, exists=${unicornDir.exists()}")
            unicornDir.exists() && unicornDir.isDirectory
        } catch (e: Throwable) {
            LogWriter.writeError(TAG, "检查 Documents/Unicorn 目录失败: ${e.message}", e)
            false
        }
    }

    /**
     * 检查迁移是否已完成
     */
    fun isMigrationCompleted(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getBoolean(KEY_MIGRATION_COMPLETED, false) ?: false
    }

    /**
     * 获取旧的 Documents 树 URI（如果存在）
     */
    private fun getOldDocumentsTreeUri(context: Context): Uri? {
        val lyricsTreeUri = LyricsSaveManager.getSavedTreeUri(context)
        Log.d(TAG, "LyricsSaveManager 保存的树 URI: $lyricsTreeUri")
        if (lyricsTreeUri != null && isDocumentsUri(lyricsTreeUri)) {
            return lyricsTreeUri
        }
        return null
    }

    /**
     * 判断树 URI 是否指向 Documents 目录
     */
    private fun isDocumentsUri(uri: Uri): Boolean {
        val uriStr = uri.toString()
        Log.d(TAG, "检查 URI 是否指向 Documents: $uriStr")
        return uriStr.contains("primary%3ADocuments") ||
                uriStr.contains("primary:Documents")
    }

    /**
     * 保存旧的树 URI（用于迁移过程中读取旧文件）
     */
    fun saveOldTreeUri(context: Context, treeUri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit { putString(KEY_OLD_TREE_URI, treeUri.toString()) }
    }

    /**
     * 获取保存的旧树 URI
     */
    fun getSavedOldTreeUri(context: Context): Uri? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_OLD_TREE_URI, null)
            ?.toUri()
    }

    /**
     * 检查旧树 URI 权限是否仍有效
     */
    fun isOldTreePermissionValid(context: Context): Boolean {
        return try {
            val uri = getSavedOldTreeUri(context) ?: return false
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        } catch (e: Throwable) {
            LogWriter.writeError(TAG, "检查旧树权限失败: ${e.message}", e)
            false
        }
    }

    /**
     * 执行文件迁移：从旧 Documents 树复制到新 Unicorn 树
     *
     * 旧树结构：Documents（根） → Unicorn → Lyrics/Equalizer/Playlist
     * 新树结构：Unicorn（根） → Lyrics/Equalizer/Playlist
     *
     * @param context 上下文
     * @param newTreeUri 新的 Unicorn 树 URI（直接指向 Download/Unicorn）
     * @return 迁移是否成功
     */
    fun migrateFiles(context: Context, newTreeUri: Uri): Boolean {
        val oldTreeUri = getSavedOldTreeUri(context) ?: return false

        return try {
            val oldRoot = DocumentFile.fromTreeUri(context, oldTreeUri) ?: return false
            val newRoot = DocumentFile.fromTreeUri(context, newTreeUri) ?: return false

            // 新树根就是 Unicorn 目录，直接在其下创建子目录
            val lyricsOk = migrateSubDirectory(
                context, oldRoot, newRoot, LYRICS_DIR
            )
            val equalizerOk = migrateSubDirectory(
                context, oldRoot, newRoot, EQUALIZER_DIR
            )
            val playlistOk = migrateSubDirectory(
                context, oldRoot, newRoot, PLAYLIST_DIR
            )

            Log.d(
                TAG,
                "迁移完成: Lyrics=$lyricsOk, Equalizer=$equalizerOk, Playlist=$playlistOk"
            )

            lyricsOk && equalizerOk && playlistOk
        } catch (e: Throwable) {
            LogWriter.writeError(TAG, "迁移文件失败: ${e.message}", e)
            false
        }
    }

    /**
     * 迁移单个子目录
     *
     * @param oldRoot 旧树根（Documents）
     * @param newDir 新树根（Unicorn），直接在其下创建子目录
     * @param subDirName 子目录名（Lyrics/Equalizer/Playlist）
     */
    private fun migrateSubDirectory(
        context: Context,
        oldRoot: DocumentFile,
        newDir: DocumentFile,
        subDirName: String
    ): Boolean {
        return try {
            val oldUnicorn = oldRoot.findFile(UNICORN_DIR) ?: return true
            val oldSubDir = oldUnicorn.findFile(subDirName) ?: return true

            // 在新目录下创建对应子目录
            val newSubDir = newDir.findFile(subDirName)
                ?: newDir.createDirectory(subDirName) ?: return false

            // 遍历旧目录下的所有文件
            oldSubDir.listFiles().forEach { oldFile ->
                if (oldFile.isFile) {
                    migrateFile(context, oldFile, newSubDir)
                }
            }
            true
        } catch (e: Throwable) {
            LogWriter.writeError(TAG, "迁移 $subDirName 目录失败: ${e.message}", e)
            false
        }
    }

    /**
     * 迁移单个文件
     */
    private fun migrateFile(
        context: Context,
        oldFile: DocumentFile,
        newDir: DocumentFile
    ): Boolean {
        return try {
            val fileName = oldFile.name ?: return false
            val mimeType = oldFile.type ?: "application/octet-stream"

            // 读取旧文件内容
            val content = context.contentResolver.openInputStream(oldFile.uri)?.use { input ->
                input.readBytes().toString(Charset.forName("UTF-8"))
            } ?: return false

            // 删除新目录下的同名文件（如果存在）
            newDir.findFile(fileName)?.delete()

            // 在新目录下创建文件并写入
            val newFile = newDir.createFile(mimeType, fileName) ?: return false
            context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                out.write(content.toByteArray(Charset.forName("UTF-8")))
            } ?: return false

            true
        } catch (e: Throwable) {
            LogWriter.writeError(TAG, "迁移文件 ${oldFile.name} 失败: ${e.message}", e)
            false
        }
    }

    /**
     * 删除旧的 Documents/Unicorn 目录及其所有内容
     * 必须在释放旧树权限之前调用
     */
    fun deleteOldUnicornDirectory(context: Context): Boolean {
        return try {
            val oldTreeUri = getSavedOldTreeUri(context) ?: return false
            val oldRoot = DocumentFile.fromTreeUri(context, oldTreeUri) ?: return false
            val oldUnicorn = oldRoot.findFile(UNICORN_DIR) ?: return true

            // 递归删除 Unicorn 目录下的所有内容
            deleteDirectoryRecursively(oldUnicorn)

            Log.d(TAG, "已删除旧 Documents/Unicorn 目录")
            true
        } catch (e: Throwable) {
            LogWriter.writeError(TAG, "删除旧目录失败: ${e.message}", e)
            false
        }
    }

    /**
     * 递归删除目录及其所有内容
     */
    private fun deleteDirectoryRecursively(dir: DocumentFile) {
        dir.listFiles().forEach { file ->
            if (file.isDirectory) {
                deleteDirectoryRecursively(file)
            }
            file.delete()
        }
        dir.delete()
    }

    /**
     * 释放旧的 Documents 树 URI 权限
     */
    fun releaseOldTreePermission(context: Context) {
        try {
            val oldTreeUri = getSavedOldTreeUri(context) ?: return
            context.contentResolver.releasePersistableUriPermission(
                oldTreeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // 清除 LyricsSaveManager 中保存的旧树 URI
            LyricsSaveManager.clearSavedTreeUri(context)
            Log.d(TAG, "已释放旧 Documents 树权限")
        } catch (e: Throwable) {
            LogWriter.writeError(TAG, "释放旧树权限失败: ${e.message}", e)
        }
    }

    /**
     * 标记迁移完成
     */
    fun markMigrationCompleted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit { putBoolean(KEY_MIGRATION_COMPLETED, true) }
    }

    /**
     * 清理迁移临时数据（迁移完成后调用）
     */
    fun cleanup(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit {
                remove(KEY_OLD_TREE_URI)
            }
    }

    /**
     * 显示迁移提醒弹窗并启动迁移流程
     *
     * @param activity Activity 上下文（用于显示对话框）
     * @param onStartMigration 用户点击"开始迁移"后的回调（用于启动 SAF 选择器）
     */
    fun showMigrationDialog(
        activity: Activity,
        onStartMigration: () -> Unit
    ) {
        if (!needsMigration(activity)) return

        // 保存旧的树 URI（用于迁移时读取旧文件）
        val oldTreeUri = LyricsSaveManager.getSavedTreeUri(activity) ?: return
        saveOldTreeUri(activity, oldTreeUri)

        // 预创建 Download/Unicorn 目录（使用 MediaStore API）
        ensureUnicornDirExists(activity)

        // 显示迁移说明对话框
        val secondaryColor = activity.getColor(R.color.secondary)
        val primaryColor = activity.getColor(R.color.primary)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("存储目录升级")
            .setMessage("为提供更好的体验，文件的保存位置已从 Documents/Unicorn 升级到 Download/Unicorn。\n\n点击\"开始迁移\"后，请在文件管理器中选择 Download/Unicorn 文件夹以完成授权，我们将自动迁移您的文件。\n\n后续使用需要您重新授权之前已授权的目录文件。")
            .setNegativeButton("稍后提醒", null)
            .setPositiveButton("开始迁移") { _, _ ->
                onStartMigration()
            }
            .setCancelable(false)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(primaryColor)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(secondaryColor)
    }

    /**
     * 处理 SAF 选择器返回的结果，执行文件迁移
     *
     * @param activity Activity 上下文（用于显示 loading 和 Toast）
     * @param treeUri SAF 选择器返回的树 URI（用户选择的 Download/Unicorn 目录）
     * @param scope CoroutineScope（用于在 IO 线程执行迁移）
     */
    fun performMigrationWithLoading(
        activity: Activity,
        treeUri: Uri?,
        scope: CoroutineScope
    ) {
        if (treeUri == null) {
            // 用户取消授权，标记迁移完成（不再提示）
            markMigrationCompleted(activity)
            return
        }

        // 保存新树 URI 到 LyricsSaveManager
        LyricsSaveManager.saveTreeUri(activity, treeUri)

        // 复用 dialog_loading 布局，修改文字
        val loadingView = LayoutInflater.from(activity).inflate(R.layout.dialog_loading, null)
        loadingView.findViewById<TextView>(R.id.tvLoadingMessage)?.text = "正在迁移目录，请稍候..."

        // 显示不可取消的 loading 对话框
        val loadingDialog = MaterialAlertDialogBuilder(activity)
            .setView(loadingView)
            .create()
        loadingDialog.setCancelable(false)
        loadingDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        loadingDialog.show()

        scope.launch(Dispatchers.IO) {
            val success = migrateFiles(activity, treeUri)

            // 在 IO 线程执行耗时的文件操作
            if (success) {
                deleteOldUnicornDirectory(activity)
            }
            releaseOldTreePermission(activity)
            markMigrationCompleted(activity)
            cleanup(activity)

            // 回到主线程显示结果
            withContext(Dispatchers.Main) {
                if (loadingDialog.isShowing) {
                    loadingDialog.dismiss()
                }
                val message = if (success) {
                    "目录迁移完成"
                } else {
                    "部分文件迁移失败，请手动检查"
                }
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
