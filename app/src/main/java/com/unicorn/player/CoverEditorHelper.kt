package com.unicorn.player

import aman.taglib.TagLib
import android.content.Context
import android.content.DialogInterface
import android.content.IntentSender
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.unicorn.player.databinding.DialogEditCoverBinding
import com.unicorn.player.model.Song
import com.unicorn.player.util.AudioTagEditor
import com.unicorn.player.util.CoverStore
import com.unicorn.player.util.LogWriter
import java.io.File

/**
 * 播放页"修改封面"弹窗：直接读写当前歌曲音频文件的内嵌封面标签。
 *
 * - 取消：关闭弹窗
 * - 删除封面：写入空封面（仅当前歌曲带内嵌封面时显示该按钮）
 * - 选择封面：跳相册选图，选好后预览换成所选图，按钮变为"确定更换"再落盘
 *
 * 删除与更换都会修改音频文件本身，Android 10+ 分区存储下需要用户授权，
 * 授权结果由宿主通过 [onWritePermissionResult] 回传。
 */
class CoverEditorHelper(
    private val context: Context,
    private val startImagePicker: () -> Unit,
    private val requestWritePermission: (IntentSender) -> Unit,
    private val onCoverChanged: (updatedSong: Song) -> Unit
) {

    companion object {
        private const val TAG = "CoverEditorHelper"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioTagEditor = AudioTagEditor(context).apply {
        writePermissionCallback = object : AudioTagEditor.WritePermissionCallback {
            override fun onRequestWritePermission(intentSender: IntentSender, requestCode: Int) {
                requestWritePermission(intentSender)
            }
        }
    }

    private var dialog: AlertDialog? = null
    private var binding: DialogEditCoverBinding? = null
    private var targetSong: Song? = null

    // 选图后先落一份方形副本用于预览，写标签成功后才作为 albumArt 入库
    private var candidatePrefix: String? = null
    private var candidatePath: String? = null
    private var candidateBytes: ByteArray? = null

    // 写入成功后要应用的歌曲展示值与提示文案
    private var resultSong: Song? = null
    private var resultMessage: String? = null

    // 弹窗关闭时据此判断候选副本是否已被采用
    private var applied = false

    /** 展示修改封面弹窗（按钮显隐依赖文件是否带内嵌封面，先后台读标签） */
    fun show(song: Song) {
        if (dialog?.isShowing == true) return
        targetSong = song
        Thread {
            val hasCover = hasEmbeddedArtwork(song.path)
            mainHandler.post { buildDialog(song, hasCover) }
        }.start()
    }

    /** 相册选图返回：压缩成方形副本，刷新预览并把正向按钮变为"确定更换" */
    fun onImagePicked(uri: Uri?) {
        val song = targetSong ?: return
        if (uri == null) return
        Thread {
            val saved = CoverStore.saveCover(
                context, uri, CoverStore.TYPE_SONG, song.id.toString()
            )
            val bytes = saved?.let { runCatching { File(it.second).readBytes() }.getOrNull() }
            mainHandler.post {
                if (saved == null || bytes == null) {
                    toast("图片读取失败，请重试")
                    return@post
                }
                // 同一弹窗内重复选图：丢弃上一张候选副本
                candidatePath?.let { if (it != saved.second) File(it).delete() }
                candidatePrefix = saved.first
                candidatePath = saved.second
                candidateBytes = bytes
                binding?.let { loadCover(it.ivCoverPreview, saved.second) }
                dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.text = "确定更换"
            }
        }.start()
    }

    /** 文件写入授权返回：同意后重试写回，拒绝则放弃本次修改 */
    fun onWritePermissionResult(granted: Boolean) {
        if (!granted) {
            toast("封面修改失败")
            dialog?.dismiss()
            return
        }
        Thread {
            val success = audioTagEditor.retryPendingArtwork()
            mainHandler.post {
                if (success) {
                    finishApplied(resultSong?.albumArt)
                } else {
                    toast("封面修改失败")
                    dialog?.dismiss()
                }
            }
        }.start()
    }

    private fun buildDialog(song: Song, hasCover: Boolean) {
        val dialogBinding = DialogEditCoverBinding.inflate(LayoutInflater.from(context))
        binding = dialogBinding
        loadCover(dialogBinding.ivCoverPreview, song.albumArt)

        // 删除/更换按钮点击后不立即关闭：写入可能还要等用户授权
        val created = MaterialAlertDialogBuilder(context)
            .setTitle("修改封面")
            .setMessage("该操作会直接修改音频文件的封面标签，原始封面将被覆盖且无法找回。")
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .setNeutralButton("删除封面", null)
            .setPositiveButton("选择封面", null)
            .create()
        created.setOnDismissListener { onDialogDismiss() }
        created.show()
        dialog = created

        created.getButton(DialogInterface.BUTTON_NEGATIVE)
            ?.setTextColor(context.getColor(R.color.secondary))
        created.getButton(DialogInterface.BUTTON_NEUTRAL)?.apply {
            setTextColor(context.getColor(R.color.error))
            visibility = if (hasCover) View.VISIBLE else View.GONE
            setOnClickListener { removeCover() }
        }
        created.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
            setTextColor(resolveThemeColor())
            setOnClickListener { pickOrApply() }
        }
    }

    private fun onDialogDismiss() {
        dialog = null
        binding = null
        targetSong = null
        resultSong = null
        resultMessage = null
        if (!applied) {
            // 未采用就退出：候选副本作废，挂起的临时文件也一并丢弃
            candidatePath?.let { runCatching { File(it).delete() } }
            audioTagEditor.clearPendingWrite()
        }
        applied = false
        clearCandidate()
    }

    private fun pickOrApply() {
        val bytes = candidateBytes
        if (bytes == null) {
            startImagePicker()
        } else {
            applyCover(bytes, candidatePath, "封面已更新")
        }
    }

    private fun removeCover() {
        applyCover(null, null, "封面已删除")
    }

    /**
     * 写入封面标签
     * @param imageBytes 新封面字节，null 表示删除封面
     * @param albumArtAfter 成功后落到 Room 的 albumArt（删除时为 null）
     */
    private fun applyCover(
        imageBytes: ByteArray?,
        albumArtAfter: String?,
        successMessage: String
    ) {
        val song = targetSong ?: return
        resultSong = song.copy(albumArt = albumArtAfter)
        resultMessage = successMessage
        Thread {
            val result = audioTagEditor.modifyArtwork(song, imageBytes, albumArtAfter)
            mainHandler.post {
                when (result) {
                    AudioTagEditor.TagEditResult.SUCCESS -> finishApplied(albumArtAfter)

                    AudioTagEditor.TagEditResult.PENDING_PERMISSION ->
                        toast(R.string.tag_edit_permission_pending)

                    AudioTagEditor.TagEditResult.FAILURE -> {
                        toast("封面修改失败")
                        dialog?.dismiss()
                    }
                }
            }
        }.start()
    }

    /** 写入成功：清理该歌曲更早的封面副本，关闭弹窗并通知界面刷新 */
    private fun finishApplied(albumArtAfter: String?) {
        val prefix = candidatePrefix
        if (albumArtAfter == null) {
            // 删除封面：该歌曲的所有本地副本都不再需要
            prefix?.let { CoverStore.deletePrevious(context, it, keepPath = "") }
        } else {
            // 更换封面：只保留刚采用的这张
            prefix?.let { CoverStore.deletePrevious(context, it, keepPath = albumArtAfter) }
        }
        applied = true
        clearCandidate()

        val song = resultSong
        val message = resultMessage
        dialog?.dismiss()
        if (song != null) {
            onCoverChanged(song)
            message?.let { toast(it) }
        }
    }

    private fun clearCandidate() {
        candidatePrefix = null
        candidatePath = null
        candidateBytes = null
    }

    private fun hasEmbeddedArtwork(path: String): Boolean = try {
        val artworks = TagLib.getArtwork(path)
        artworks != null && artworks.any { it.data?.isNotEmpty() == true }
    } catch (e: Exception) {
        LogWriter.writeError(TAG, "读取内嵌封面失败: $path", e)
        false
    }

    private fun loadCover(target: ImageView, source: Any?) {
        Glide.with(context)
            .load(source)
            .placeholder(R.drawable.ic_music_note)
            .error(R.drawable.ic_music_note)
            .transform(CenterCrop(), RoundedCorners(36))
            .into(target)
    }

    private fun resolveThemeColor(): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorPrimary, typedValue, true
        )
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(context, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }
}
