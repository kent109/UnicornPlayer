package com.unicorn.player.util

import aman.taglib.TagLib
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.unicorn.player.database.MusicDatabase
import com.unicorn.player.model.Song
import java.io.File

/**
 * 音频标签编辑工具类
 * 负责使用 TagLib 修改音频文件的标签（歌名、歌手、专辑）
 * 通过 content Uri + 临时文件读写实现
 */
class AudioTagEditor(private val context: Context) {

    companion object {
        private const val TAG = "AudioTagEditor"
        const val SAF_REQUEST_CODE = 1002
    }

    /**
     * 标签编辑结果
     */
    enum class TagEditResult {
        SUCCESS,
        FAILURE,
        PENDING_PERMISSION
    }

    /**
     * 重试写入的结果
     */
    data class RetryResult(
        val success: Boolean,
        val song: Song?,
        val newTitle: String?,
        val newArtist: String?,
        val newAlbum: String?
    )

    /**
     * 写入权限请求回调接口
     * 当 Android 10+ 分区存储拒绝写入时，需要通过 IntentSender 请求用户授权
     */
    interface WritePermissionCallback {
        fun onRequestWritePermission(intentSender: IntentSender, requestCode: Int)
    }

    /**
     * 待重试的封面写入状态
     * @param albumArtAfter 写入成功后应落到 Room 的 albumArt（删除封面时为 null）
     */
    private data class PendingArtworkWrite(
        val modifiedFile: File,
        val contentUri: Uri,
        val song: Song,
        val albumArtAfter: String?
    )

    var writePermissionCallback: WritePermissionCallback? = null

    // 待重试的写入状态
    private var pendingModifiedFile: File? = null
    private var pendingContentUri: Uri? = null
    private var pendingSongPath: String? = null
    private var pendingSong: Song? = null
    private var pendingNewTitle: String? = null
    private var pendingNewArtist: String? = null
    private var pendingNewAlbum: String? = null

    // 待重试的封面写入状态
    private var pendingArtworkWrite: PendingArtworkWrite? = null

    /**
     * 使用 TagLib 修改音频文件的标签（歌名、歌手、专辑）
     * @return 编辑结果
     */
    fun modifyAudioTags(
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String
    ): TagEditResult {
        var tempFile: File? = null
        return try {
            val uri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                song.id
            )

            val extension = song.path.substringAfterLast('.', "").lowercase()
            val temp = File(
                context.cacheDir,
                "temp_tag_edit_${System.currentTimeMillis()}.$extension"
            )
            tempFile = temp

            // 从 content Uri 读取原始音频并复制到临时文件
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                LogWriter.writeError(TAG, "无法读取音频文件: ${song.path}", null)
                return TagEditResult.FAILURE
            }
            input.use { src -> temp.outputStream().use { dst -> src.copyTo(dst) } }

            // 使用 TagLib 修改临时文件的标签
            val meta = HashMap<String, String>()
            if (newTitle.isNotEmpty()) meta["TITLE"] = newTitle
            if (newArtist.isNotEmpty()) meta["ARTIST"] = newArtist
            if (newAlbum.isNotEmpty()) meta["ALBUM"] = newAlbum
            TagLib.setMetadata(temp.absolutePath, meta)

            // 将修改后的临时文件写回原 content Uri（覆盖）
            try {
                val outputWritten =
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        temp.inputStream().use { input -> input.copyTo(output) }
                    } != null
                if (!outputWritten) {
                    LogWriter.writeError(TAG, "无法写入音频文件: ${song.path}", null)
                    return TagEditResult.FAILURE
                }
            } catch (securityException: RecoverableSecurityException) {
                // Android 10+ 分区存储：应用没有直接写入权限，需要通过 IntentSender 请求用户授权
                Log.w(TAG, "写入权限被拒绝，需要用户授权: ${securityException.message}")
                pendingModifiedFile = temp
                pendingContentUri = uri
                pendingSongPath = song.path
                pendingSong = song
                pendingNewTitle = newTitle
                pendingNewArtist = newArtist
                pendingNewAlbum = newAlbum
                tempFile = null // 防止 finally 中删除，保留给重试使用
                Handler(Looper.getMainLooper()).post {
                    writePermissionCallback?.onRequestWritePermission(
                        securityException.userAction.actionIntent.intentSender,
                        SAF_REQUEST_CODE
                    )
                }
                return TagEditResult.PENDING_PERMISSION
            }

            // 通知系统媒体库重新扫描，确保显示更新
            MediaScannerConnection.scanFile(context, arrayOf(song.path), null, null)

            // 同步更新 Room 数据库，使 UI 立即生效
            // 标题为空取文件名（去后缀），歌手/专辑为空取 "<unknown>"
            val fileName = song.path.substringAfterLast('/').substringBeforeLast('.')
            val dbTitle = newTitle.ifEmpty { fileName }
            val dbArtist = newArtist.ifEmpty { "<unknown>" }
            val dbAlbum = newAlbum.ifEmpty { "<unknown>" }
            val updatedSong = song.copy(title = dbTitle, artist = dbArtist, album = dbAlbum)
            MusicDatabase.getDatabase(context).songDao().updateSong(updatedSong)

            Log.d(TAG, "标签修改成功: ${song.path}")
            TagEditResult.SUCCESS
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "标签修改失败: ${e.message}", e)
            TagEditResult.FAILURE
        } finally {
            tempFile?.takeIf { it.exists() }?.delete()
        }
    }

    /**
     * 在用户授予写入权限后，重试将修改后的临时文件写回原文件
     * @return 重试结果，包含成功状态和修改的歌曲信息
     */
    fun retryPendingWrite(): RetryResult {
        val modifiedFile = pendingModifiedFile ?: return RetryResult(false, null, null, null, null)
        val uri = pendingContentUri ?: return RetryResult(false, null, null, null, null)
        val songPath = pendingSongPath ?: return RetryResult(false, null, null, null, null)
        val song = pendingSong
        val newTitle = pendingNewTitle
        val newArtist = pendingNewArtist
        val newAlbum = pendingNewAlbum

        val success = try {
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                modifiedFile.inputStream().use { input -> input.copyTo(output) }
            } != null
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "重试写入失败: ${e.message}", e)
            false
        }

        modifiedFile.takeIf { it.exists() }?.delete()
        pendingModifiedFile = null
        pendingContentUri = null
        pendingSongPath = null
        pendingSong = null
        pendingNewTitle = null
        pendingNewArtist = null
        pendingNewAlbum = null

        // 通知媒体库重新扫描
        MediaScannerConnection.scanFile(context, arrayOf(songPath), null, null)

        // 在后台线程更新 Room 数据库（retryPendingWrite 可能在主线程调用）
        // 标题为空取文件名（去后缀），歌手/专辑为空取 "<unknown>"
        if (song != null && newTitle != null && newArtist != null && newAlbum != null) {
            val fileName = song.path.substringAfterLast('/').substringBeforeLast('.')
            val updatedSong = song.copy(
                title = newTitle.ifEmpty { fileName },
                artist = newArtist.ifEmpty { "<unknown>" },
                album = newAlbum.ifEmpty { "<unknown>" })
            Thread {
                MusicDatabase.getDatabase(context).songDao().updateSong(updatedSong)
            }.start()
        }

        return RetryResult(success, song, newTitle, newArtist, newAlbum)
    }

    /**
     * 使用 TagLib 修改音频文件的内嵌封面
     * @param imageBytes 新封面图片字节，传 null 表示删除封面（TagLib 以空字节清除）
     * @param albumArtAfter 写入成功后落到 Room 的 albumArt（删除封面时为 null）
     * @return 编辑结果，PENDING_PERMISSION 表示已挂起等待用户授权写入
     */
    fun modifyArtwork(
        song: Song,
        imageBytes: ByteArray?,
        albumArtAfter: String?
    ): TagEditResult {
        var tempFile: File? = null
        return try {
            val uri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                song.id
            )

            val extension = song.path.substringAfterLast('.', "").lowercase()
            val temp = File(
                context.cacheDir,
                "temp_artwork_edit_${System.currentTimeMillis()}.$extension"
            )
            tempFile = temp

            // 从 content Uri 读取原始音频并复制到临时文件
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                LogWriter.writeError(TAG, "无法读取音频文件: ${song.path}", null)
                return TagEditResult.FAILURE
            }
            input.use { src -> temp.outputStream().use { dst -> src.copyTo(dst) } }

            val artwork = imageBytes ?: ByteArray(0)
            if (!TagLib.setArtwork(temp.absolutePath, artwork, "image/jpeg", "")) {
                LogWriter.writeError(TAG, "TagLib 写入封面失败: ${song.path}", null)
                return TagEditResult.FAILURE
            }

            // 将修改后的临时文件写回原 content Uri（覆盖）
            try {
                val outputWritten =
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        temp.inputStream().use { src -> src.copyTo(output) }
                    } != null
                if (!outputWritten) {
                    LogWriter.writeError(TAG, "无法写入音频文件: ${song.path}", null)
                    return TagEditResult.FAILURE
                }
            } catch (securityException: RecoverableSecurityException) {
                // Android 10+ 分区存储：需用户授权后重试，临时文件保留给重试使用
                Log.w(TAG, "封面写入被拒绝，需要用户授权: ${securityException.message}")
                pendingArtworkWrite = PendingArtworkWrite(temp, uri, song, albumArtAfter)
                tempFile = null
                Handler(Looper.getMainLooper()).post {
                    writePermissionCallback?.onRequestWritePermission(
                        securityException.userAction.actionIntent.intentSender,
                        SAF_REQUEST_CODE
                    )
                }
                return TagEditResult.PENDING_PERMISSION
            }

            finishArtworkWrite(song, albumArtAfter)
            TagEditResult.SUCCESS
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "封面修改失败: ${e.message}", e)
            TagEditResult.FAILURE
        } finally {
            tempFile?.takeIf { it.exists() }?.delete()
        }
    }

    /**
     * 在用户授予写入权限后，重试把带新封面的临时文件写回原文件
     * @return 是否完成写入；没有挂起的封面写入时同样返回 false
     */
    fun retryPendingArtwork(): Boolean {
        val pending = pendingArtworkWrite ?: return false
        pendingArtworkWrite = null

        val success = try {
            context.contentResolver.openOutputStream(pending.contentUri, "w")?.use { output ->
                pending.modifiedFile.inputStream().use { src -> src.copyTo(output) }
            } != null
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "重试封面写入失败: ${e.message}", e)
            false
        }

        pending.modifiedFile.takeIf { it.exists() }?.delete()
        if (success) finishArtworkWrite(pending.song, pending.albumArtAfter)
        return success
    }

    /**
     * 封面落盘后的收尾：通知媒体库重扫，并把新的 albumArt 写回 Room
     * （重扫是异步的，直接更新 Room 才能让界面立即换成新封面/默认封面）
     */
    private fun finishArtworkWrite(song: Song, albumArtAfter: String?) {
        MediaScannerConnection.scanFile(context, arrayOf(song.path), null, null)
        val updatedSong = song.copy(albumArt = albumArtAfter)
        Thread {
            MusicDatabase.getDatabase(context).songDao().updateSong(updatedSong)
        }.start()
    }

    /**
     * 清除待重试的写入状态（用户拒绝授权时调用）
     */
    fun clearPendingWrite() {
        pendingModifiedFile?.takeIf { it.exists() }?.delete()
        pendingModifiedFile = null
        pendingContentUri = null
        pendingSongPath = null
        pendingSong = null
        pendingNewTitle = null
        pendingNewArtist = null
        pendingNewAlbum = null
        pendingArtworkWrite?.modifiedFile?.delete()
        pendingArtworkWrite = null
    }
}
