package com.unicorn.player

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.unicorn.player.databinding.DialogEditCoverBinding
import com.unicorn.player.model.Song
import com.unicorn.player.ui.PanelDialog
import com.unicorn.player.util.CoverStore
import java.io.File

/**
 * 播放页"修改封面"弹窗：与歌手/专辑封面同一套形式，图片压缩成方形副本存进应用私有目录，
 * 副本路径落到歌曲的 albumArt，不改动音频文件本身，因此也不需要分区存储写入授权。
 * 副本按歌曲路径分组，删掉文件重新拷入或"清空并重新扫描"后仍能找回（见 [CoverStore] 注释）。
 *
 * - 取消：关闭弹窗
 * - 默认封面：仅当前已有本地副本封面时显示，移除副本回到歌曲自己的封面
 * - 选择封面：跳相册选图，选好后预览换成所选图，按钮变为"确定更换"再落库
 *
 * 确认后的写库与界面刷新由宿主完成（[onCoverConfirmed] 的 coverPath 为 null 表示恢复默认）。
 */
class CoverEditorHelper(
    private val context: Context,
    private val startImagePicker: () -> Unit,
    private val onCoverConfirmed: (song: Song, coverPath: String?) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var dialog: PanelDialog? = null
    private var binding: DialogEditCoverBinding? = null
    private var targetSong: Song? = null

    // 选图后先落一份方形副本用于预览，确认后才作为 albumArt 入库
    private var candidatePath: String? = null

    // 弹窗关闭时据此判断候选副本是否已被采用
    private var applied = false

    /** 展示修改封面弹窗（按钮显隐只取决于当前 albumArt 是否指向本地副本） */
    fun show(song: Song) {
        if (dialog?.isShowing == true) return
        targetSong = song
        buildDialog(song, CoverStore.isCustomSongCover(context, song.albumArt))
    }

    /** 相册选图返回：压缩成方形副本，刷新预览并把正向按钮变为"确定更换" */
    fun onImagePicked(uri: Uri?) {
        val song = targetSong ?: return
        if (uri == null) return
        Thread {
            val saved = CoverStore.saveSongCover(context, uri, song.path)
            mainHandler.post {
                // 选图期间弹窗可能已因切歌关闭：此时副本没有归属，直接丢弃
                if (dialog == null || targetSong?.path != song.path) {
                    saved?.let { runCatching { File(it).delete() } }
                    return@post
                }
                if (saved == null) {
                    toast("图片读取失败，请重试")
                    return@post
                }
                // 同一弹窗内重复选图：丢弃上一张候选副本
                candidatePath?.let { if (it != saved) File(it).delete() }
                candidatePath = saved
                binding?.let { loadCover(it.ivCoverPreview, saved) }
                binding?.btnPickCover?.text = "确定更换"
            }
        }.start()
    }

    /** 播放的歌曲发生变化时收起弹窗：本次编辑只针对打开弹窗时那一首歌 */
    fun dismissForSongChange() {
        dialog?.dismiss()
    }

    private fun buildDialog(song: Song, hasCustomCover: Boolean) {
        val created = PanelDialog(context, R.layout.dialog_edit_cover, sideMarginDp = 32f)
        val dialogBinding = DialogEditCoverBinding.bind(created.contentView)
        binding = dialogBinding
        created.setOnDismissListener { onDialogDismiss() }

        loadCover(dialogBinding.ivCoverPreview, song.albumArt)
        dialogBinding.btnDefaultCover.visibility =
            if (hasCustomCover) View.VISIBLE else View.GONE
        dialogBinding.btnCancel.setOnClickListener { created.dismiss() }
        dialogBinding.btnDefaultCover.setOnClickListener { confirm(null, "已恢复默认封面") }
        dialogBinding.btnPickCover.setOnClickListener { pickOrConfirm() }

        created.show()
        dialog = created
    }

    private fun onDialogDismiss() {
        dialog = null
        binding = null
        targetSong = null
        if (!applied) {
            // 未采用就退出：候选副本作废
            candidatePath?.let { runCatching { File(it).delete() } }
        }
        applied = false
        clearCandidate()
    }

    private fun pickOrConfirm() {
        val path = candidatePath
        if (path == null) {
            startImagePicker()
        } else {
            confirm(path, "封面已更新")
        }
    }

    /**
     * 确认本次修改：清理该歌曲更早的封面副本，关闭弹窗并交给宿主写库与刷新
     * @param coverPath 采用的副本路径，null 表示恢复默认封面
     */
    private fun confirm(coverPath: String?, successMessage: String) {
        val song = targetSong ?: return
        applied = true
        // 更换只保留刚采用的这张，恢复默认则把该歌曲的副本清干净
        CoverStore.retainSongCover(context, song.path, coverPath ?: "")
        clearCandidate()

        dialog?.dismiss()
        onCoverConfirmed(song, coverPath)
        toast(successMessage)
    }

    private fun clearCandidate() {
        candidatePath = null
    }

    private fun loadCover(view: ImageView, source: Any?) {
        // 先按「显示的是默认音符」垫上灰底；媒体库封面 URI 常失效，是不是真图片只能问 Glide
        view.setBackgroundResource(R.drawable.bg_dialog_cover_placeholder)
        Glide.with(context)
            .load(source)
            .placeholder(R.drawable.ic_music_note)
            .error(R.drawable.ic_music_note)
            .transform(
                CenterCrop(),
                RoundedCorners(
                    context.resources.getDimensionPixelSize(R.dimen.dialog_cover_corner_radius)
                )
            )
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean = false

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    view.background = null
                    return false
                }
            })
            .into(view)
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
