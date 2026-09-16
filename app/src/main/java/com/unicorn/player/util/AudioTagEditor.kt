package com.unicorn.player.util

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
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.ID3v24Tag
import java.io.File
import java.util.Locale

/**
 * 音频标签编辑工具类
 * 负责使用 JAudiotagger 修改音频文件的标签（歌名、歌手、专辑）
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

    var writePermissionCallback: WritePermissionCallback? = null

    // 待重试的写入状态
    private var pendingModifiedFile: File? = null
    private var pendingContentUri: Uri? = null
    private var pendingSongPath: String? = null
    private var pendingSong: Song? = null
    private var pendingNewTitle: String? = null
    private var pendingNewArtist: String? = null
    private var pendingNewAlbum: String? = null

    /**
     * 使用 JAudiotagger 修改音频文件的标签（歌名、歌手、专辑）
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

            // 临时文件必须保留原扩展名，否则 JAudiotagger 无法识别格式，
            // 会抛出 "No Reader associated with this extension"
            val extension = song.path.substringAfterLast('.', "").lowercase(Locale.getDefault())
            if (extension.isEmpty()) {
                LogWriter.writeError(TAG, "无法识别文件扩展名: ${song.path}", null)
                return TagEditResult.FAILURE
            }

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

            // 使用 JAudiotagger 修改临时文件的标签
            val audioFile = AudioFileIO.read(temp)

            // 关键：必须把标签显式写回 AudioFile。
            // getTagOrCreateDefault() 只「返回」新标签而不会写回，
            // 会导致 AudioFileIO.write() 内部 getTag() 为 null 抛 NullPointerException。
            // MP3 的写入器只认 ID3v2 标签，需单独设置。
            if (audioFile is MP3File) {
                val id3Tag = audioFile.iD3v2Tag ?: ID3v24Tag()
                setOrDeleteField(id3Tag, FieldKey.TITLE, newTitle)
                setOrDeleteField(id3Tag, FieldKey.ARTIST, newArtist)
                setOrDeleteField(id3Tag, FieldKey.ALBUM, newAlbum)
                audioFile.setID3v2Tag(id3Tag)
                audioFile.tag = id3Tag
            } else {
                val tag = audioFile.tag ?: audioFile.createDefaultTag()
                setOrDeleteField(tag, FieldKey.TITLE, newTitle)
                setOrDeleteField(tag, FieldKey.ARTIST, newArtist)
                setOrDeleteField(tag, FieldKey.ALBUM, newAlbum)
                audioFile.tag = tag
            }

            AudioFileIO.write(audioFile)

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
    }

    /**
     * 设置或删除标签字段：值为空时删除字段，非空时设置字段
     */
    private fun setOrDeleteField(tag: Tag, key: FieldKey, value: String) {
        if (value.isEmpty()) {
            try {
                tag.deleteField(key)
            } catch (_: Exception) {
                // 字段不存在时忽略
            }
        } else {
            tag.setField(key, value)
        }
    }
}
