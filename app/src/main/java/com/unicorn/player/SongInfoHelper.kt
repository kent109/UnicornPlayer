package com.unicorn.player

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.unicorn.player.databinding.DialogSongInfo2Binding
import com.unicorn.player.databinding.ItemSongInfoBinding
import com.unicorn.player.model.Song
import com.unicorn.player.util.AudioTagEditor
import com.unicorn.player.util.LogWriter
import java.io.File
import java.util.Locale

/**
 * 歌曲信息对话框帮助类
 * 负责显示歌曲详细信息、分享和删除功能
 */
class SongInfoHelper(private val context: Context) {

    companion object {
        private const val TAG = "SongInfoHelper"
    }

    private val audioTagEditor = AudioTagEditor(context)

    // 待重试的弹窗 binding 引用，重试成功后用于更新 UI
    private var pendingDialogBinding: DialogSongInfo2Binding? = null
    private var pendingArtistItemBinding: ItemSongInfoBinding? = null
    private var pendingAlbumItemBinding: ItemSongInfoBinding? = null
    private var pendingNewTitle: String? = null
    private var pendingNewArtist: String? = null
    private var pendingNewAlbum: String? = null

    /**
     * 设置写入权限回调，委托给 AudioTagEditor
     */
    var writePermissionCallback: AudioTagEditor.WritePermissionCallback?
        get() = audioTagEditor.writePermissionCallback
        set(value) {
            audioTagEditor.writePermissionCallback = value
        }

    /**
     * 显示歌曲信息的 BottomSheetDialog
     * @param showDeleteOption 是否显示"删除"选项。歌手/专辑等聚合视图传 false
     * @param showAddToPlaylist 是否显示"添加到歌单"选项。当前存在歌单时传 true
     */
    fun showSongInfoDialog(
        song: Song,
        showDeleteOption: Boolean = true,
        showAddToPlaylist: Boolean = false
    ) {
        val bottomSheetDialog = BottomSheetDialog(context, R.style.BottomSheetDialogTheme)
        val dialogBinding = DialogSongInfo2Binding.inflate(LayoutInflater.from(context))
        bottomSheetDialog.setContentView(dialogBinding.root)

        // 设置歌曲标题
        dialogBinding.tvSongTitle.text = song.title
        dialogBinding.tvSongTitle.isSelected = true // 激活跑马灯效果

        // JAudiotagger 仅支持修改以下格式的标签，不支持的格式隐藏编辑按钮
        val ext = song.path.substringAfterLast('.', "").lowercase(Locale.getDefault())
        if (ext !in listOf("mp3", "flac", "ogg", "wav", "m4a")) {
            dialogBinding.btnEditContainer.visibility = View.INVISIBLE
        }

        // 锁定标题区域高度，避免切换编辑状态时布局抖动
        dialogBinding.root.post {
            val titleHeight = dialogBinding.tvSongTitle.height
            if (titleHeight > 0) {
                dialogBinding.etSongTitle.layoutParams.height = titleHeight
            }
            val titleArea = dialogBinding.tvSongTitle.parent as View
            titleArea.minimumHeight = titleArea.height
        }

        // 获取文件大小
        val fileSize = getFileSize(song.path)

        // 格式化时长
        val durationText = formatDuration(song.duration)

        // 音质显示
        val qualityText = getQualityText(song.quality)

        // 歌手、专辑为可编辑字段，单独保留 binding 引用
        val artistItemBinding = ItemSongInfoBinding.inflate(LayoutInflater.from(context)).apply {
            tvLabel.text = "歌手"
            tvValue.text = song.artist
            root.background = null
        }
        val albumItemBinding = ItemSongInfoBinding.inflate(LayoutInflater.from(context)).apply {
            tvLabel.text = "专辑"
            tvValue.text = song.album
            root.background = null
        }
        dialogBinding.infoContainer.addView(artistItemBinding.root)
        dialogBinding.infoContainer.addView(albumItemBinding.root)

        // 其余不可编辑的信息项
        val infoItems = listOf(
            Pair("时长", durationText),
            Pair("音质", qualityText),
            Pair("文件大小", fileSize),
            Pair("文件路径", song.path)
        )

        infoItems.forEach { (label, value) ->
            val itemBinding = ItemSongInfoBinding.inflate(LayoutInflater.from(context))
            itemBinding.tvLabel.text = label
            itemBinding.tvValue.text = value
            itemBinding.root.background = null
            dialogBinding.infoContainer.addView(itemBinding.root)
        }

        // 记录编辑前的原始值
        var originalTitle = song.title
        var originalArtist = song.artist
        var originalAlbum = song.album

        // 编辑按钮：进入编辑状态
        dialogBinding.btnEdit.setOnClickListener {
            originalTitle = dialogBinding.tvSongTitle.text.toString()
            originalArtist = artistItemBinding.tvValue.text.toString()
            originalAlbum = albumItemBinding.tvValue.text.toString()

            dialogBinding.btnEdit.visibility = View.GONE
            dialogBinding.btnDone.visibility = View.VISIBLE
            dialogBinding.tvSongTitle.visibility = View.GONE
            dialogBinding.etSongTitle.visibility = View.VISIBLE
            dialogBinding.tvTitleAsterisk.visibility = View.VISIBLE
            dialogBinding.etSongTitle.setText(originalTitle)
            artistItemBinding.tvValue.visibility = View.GONE
            artistItemBinding.etValue.visibility = View.VISIBLE
            artistItemBinding.tvAsterisk.visibility = View.VISIBLE
            artistItemBinding.etValue.setText(originalArtist)
            albumItemBinding.tvValue.visibility = View.GONE
            albumItemBinding.etValue.visibility = View.VISIBLE
            albumItemBinding.tvAsterisk.visibility = View.VISIBLE
            albumItemBinding.etValue.setText(originalAlbum)
        }

        // 完成按钮：比较修改并执行
        dialogBinding.btnDone.setOnClickListener {
            val rawTitle = dialogBinding.etSongTitle.text.toString().trim()
            val rawArtist = artistItemBinding.etValue.text.toString().trim()
            val rawAlbum = albumItemBinding.etValue.text.toString().trim()

            // 空字符串和 "<unknown>" 视为等价，避免无意义的修改
            fun normalize(v: String) = if (v.isEmpty()) "<unknown>" else v
            val normOrigTitle = normalize(originalTitle)
            val normOrigArtist = normalize(originalArtist)
            val normOrigAlbum = normalize(originalAlbum)
            val normNewTitle = normalize(rawTitle)
            val normNewArtist = normalize(rawArtist)
            val normNewAlbum = normalize(rawAlbum)

            // 三个值均未修改（忽略空/<unknown>差异），退出编辑状态
            if (normNewTitle == normOrigTitle &&
                normNewArtist == normOrigArtist &&
                normNewAlbum == normOrigAlbum
            ) {
                exitEditMode(dialogBinding, artistItemBinding, albumItemBinding)
                return@setOnClickListener
            }

            // 展示值：标题为空取文件名（去后缀），歌手/专辑为空取 "<unknown>"
            val fileName = song.path.substringAfterLast('/').substringBeforeLast('.')
            val displayTitle = rawTitle.ifEmpty { fileName }
            val displayArtist = normNewArtist
            val displayAlbum = normNewAlbum

            // 有修改，在后台线程执行标签修改（传原始值，空值由 AudioTagEditor 删除标签）
            Thread {
                val result = audioTagEditor.modifyAudioTags(song, rawTitle, rawArtist, rawAlbum)
                Handler(Looper.getMainLooper()).post {
                    when (result) {
                        AudioTagEditor.TagEditResult.SUCCESS -> {
                            // 更新展示值并退出编辑状态
                            dialogBinding.tvSongTitle.text = displayTitle
                            artistItemBinding.tvValue.text = displayArtist
                            albumItemBinding.tvValue.text = displayAlbum
                            exitEditMode(dialogBinding, artistItemBinding, albumItemBinding)
                            Toast.makeText(
                                context,
                                R.string.tag_edit_success,
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        AudioTagEditor.TagEditResult.PENDING_PERMISSION -> {
                            // 等待用户授权，保存弹窗引用以便重试后更新 UI
                            pendingDialogBinding = dialogBinding
                            pendingArtistItemBinding = artistItemBinding
                            pendingAlbumItemBinding = albumItemBinding
                            pendingNewTitle = displayTitle
                            pendingNewArtist = displayArtist
                            pendingNewAlbum = displayAlbum
                            // 保持编辑状态不变
                            Toast.makeText(
                                context,
                                R.string.tag_edit_permission_pending,
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        AudioTagEditor.TagEditResult.FAILURE -> {
                            exitEditMode(dialogBinding, artistItemBinding, albumItemBinding)
                            Toast.makeText(
                                context,
                                R.string.tag_edit_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }.start()
        }

        // 添加到歌单（在"文件路径"后面，"分享本地文件"前面，仅当存在歌单时显示）
        if (showAddToPlaylist) {
            addClickableItem(
                dialogBinding.infoContainer,
                "添加到歌单",
                R.color.quality_std
            ) {
                bottomSheetDialog.dismiss()
                onAddToPlaylistListener?.onAddToPlaylist(song)
            }
        }

        // 添加可点击的操作项（分享本地文件使用默认颜色）
        addClickableItem(
            dialogBinding.infoContainer,
            "分享本地文件",
            R.color.quality_sq
        ) {
            bottomSheetDialog.dismiss()
            shareLocalFile(song)
        }

        // 删除使用红色（仅在 showDeleteOption 为 true 时显示）
        if (showDeleteOption) {
            addClickableItem(
                dialogBinding.infoContainer,
                "删除",
                android.R.color.holo_red_light
            ) {
                bottomSheetDialog.dismiss()
                showDeleteConfirmDialog(song)
            }
        }

        // 设置窗口动画
        bottomSheetDialog.show()

        // 用 GradientDrawable 替代 MaterialShapeDrawable，防止拖拽时圆角被动画化为 0
        val designBottomSheet = bottomSheetDialog.findViewById<android.view.ViewGroup>(com.google.android.material.R.id.design_bottom_sheet)
        designBottomSheet?.post {
            // 设置完全展开，下滑时跳过折叠状态直接消失
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(designBottomSheet)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.isHideable = true
            behavior.skipCollapsed = true

            val cornerRadius = 40f * designBottomSheet.resources.displayMetrics.density
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(designBottomSheet.context.getColor(R.color.surface))
                cornerRadii = floatArrayOf(cornerRadius, cornerRadius, cornerRadius, cornerRadius, 0f, 0f, 0f, 0f)
            }
            designBottomSheet.background = drawable
            designBottomSheet.clipToOutline = true
            designBottomSheet.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        val window: Window? = bottomSheetDialog.window
        window?.let {
            val layoutParams = it.attributes
            layoutParams.windowAnimations = R.style.BottomSheetDialogTheme
            it.attributes = layoutParams
        }
    }

    /**
     * 退出编辑状态：隐藏完成按钮与 EditText，恢复编辑按钮与 TextView
     */
    private fun exitEditMode(
        dialogBinding: DialogSongInfo2Binding,
        artistItemBinding: ItemSongInfoBinding,
        albumItemBinding: ItemSongInfoBinding
    ) {
        hideKeyboard(dialogBinding.etSongTitle)
        hideKeyboard(artistItemBinding.etValue)
        hideKeyboard(albumItemBinding.etValue)
        dialogBinding.btnEdit.visibility = View.VISIBLE
        dialogBinding.btnDone.visibility = View.GONE
        dialogBinding.tvSongTitle.visibility = View.VISIBLE
        dialogBinding.etSongTitle.visibility = View.GONE
        dialogBinding.tvTitleAsterisk.visibility = View.INVISIBLE
        artistItemBinding.tvValue.visibility = View.VISIBLE
        artistItemBinding.etValue.visibility = View.GONE
        artistItemBinding.tvAsterisk.visibility = View.GONE
        albumItemBinding.tvValue.visibility = View.VISIBLE
        albumItemBinding.etValue.visibility = View.GONE
        albumItemBinding.tvAsterisk.visibility = View.GONE
    }

    /**
     * 隐藏输入法键盘
     */
    private fun hideKeyboard(view: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /**
     * 在用户授予写入权限后，重试将修改后的临时文件写回原文件
     * @return 是否写入成功
     */
    fun retryPendingWrite(): Boolean {
        val result = audioTagEditor.retryPendingWrite()
        val dlgBinding = pendingDialogBinding
        val artistBinding = pendingArtistItemBinding
        val albumBinding = pendingAlbumItemBinding
        val newTitle = pendingNewTitle
        val newArtist = pendingNewArtist
        val newAlbum = pendingNewAlbum

        // 清除待重试状态
        pendingDialogBinding = null
        pendingArtistItemBinding = null
        pendingAlbumItemBinding = null
        pendingNewTitle = null
        pendingNewArtist = null
        pendingNewAlbum = null

        // 更新弹窗 UI 并退出编辑状态
        Handler(Looper.getMainLooper()).post {
            if (result.success && dlgBinding != null && artistBinding != null && albumBinding != null
                && newTitle != null && newArtist != null && newAlbum != null
            ) {
                dlgBinding.tvSongTitle.text = newTitle
                artistBinding.tvValue.text = newArtist
                albumBinding.tvValue.text = newAlbum
                exitEditMode(dlgBinding, artistBinding, albumBinding)
                Toast.makeText(context, R.string.tag_edit_success, Toast.LENGTH_SHORT).show()
            } else if (!result.success) {
                if (dlgBinding != null && artistBinding != null && albumBinding != null) {
                    exitEditMode(dlgBinding, artistBinding, albumBinding)
                }
                Toast.makeText(context, R.string.tag_edit_failed, Toast.LENGTH_SHORT).show()
            }
        }
        return result.success
    }

    /**
     * 清除待重试的写入状态（用户拒绝授权时调用）
     */
    fun clearPendingWrite() {
        audioTagEditor.clearPendingWrite()
    }

    /**
     * 获取文件大小
     */
    private fun getFileSize(path: String): String {
        return try {
            val file = File(path)
            if (file.exists()) {
                val sizeInBytes = file.length()
                when {
                    sizeInBytes >= 1024 * 1024 * 1024 -> String.format(
                        Locale.getDefault(),
                        "%.2f GB",
                        sizeInBytes / (1024.0 * 1024.0 * 1024.0)
                    )

                    sizeInBytes >= 1024 * 1024 -> String.format(
                        Locale.getDefault(),
                        "%.2f MB",
                        sizeInBytes / (1024.0 * 1024.0)
                    )

                    sizeInBytes >= 1024 -> String.format(
                        Locale.getDefault(),
                        "%.2f KB",
                        sizeInBytes / 1024.0
                    )

                    else -> "$sizeInBytes B"
                }
            } else {
                "未知"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "未知"
        }
    }

    /**
     * 格式化时长（毫秒转换为 mm:ss 格式）
     */
    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    /**
     * 获取音质显示文本
     */
    private fun getQualityText(quality: String): String {
        return when (quality) {
            "SQ" -> "SQ (超品质)"
            "HQ" -> "HQ (高品质)"
            "STD" -> "STD (标准)"
            "ORD" -> "ORD (普通)"
            else -> "UNK (未知)"
        }
    }

    /**
     * 添加可点击的信息项
     * @param textColor 文字颜色
     */
    private fun addClickableItem(
        container: android.view.ViewGroup,
        text: String,
        textColor: Int?,
        onClick: () -> Unit
    ) {
        val itemBinding = ItemSongInfoBinding.inflate(LayoutInflater.from(context))
        itemBinding.tvLabel.visibility = android.view.View.GONE
        itemBinding.tvValue.text = text
        // 设置文字大小和样式与 tvLabel 一致
        itemBinding.tvValue.textSize = 16f
        itemBinding.tvValue.setTypeface(
            itemBinding.tvValue.typeface,
            android.graphics.Typeface.BOLD
        )
        // 垂直居中
        itemBinding.tvValue.gravity = android.view.Gravity.CENTER_VERTICAL
        itemBinding.root.gravity = android.view.Gravity.CENTER_VERTICAL
        // 增大可点击项的高度
        val paddingV = (10 * context.resources.displayMetrics.density).toInt()
        itemBinding.root.setPadding(
            itemBinding.root.paddingLeft,
            paddingV,
            itemBinding.root.paddingRight,
            paddingV
        )
        if (textColor != null) {
            itemBinding.tvValue.setTextColor(context.getColor(textColor))
        } else {
            itemBinding.tvValue.setTextColor(context.getColor(R.color.onSurfaceVariant))
        }
        itemBinding.root.setOnClickListener { onClick() }
        container.addView(itemBinding.root)
    }

    /**
     * 分享本地文件
     */
    private fun shareLocalFile(song: Song) {
        try {
            val file = File(song.path)
            if (!file.exists()) {
                Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(shareIntent, "分享歌曲"))
        } catch (e: Exception) {
            LogWriter.writeError(TAG, "分享文件失败: ${e.message}", e)
            Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示删除确认对话框
     */
    private fun showDeleteConfirmDialog(song: Song) {
        val errorColor = context.getColor(R.color.error)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("删除歌曲")
            .setMessage("确定要从列表中删除「${song.title}」吗？\n\n注意：这不会删除本地文件。")
            .setPositiveButton("删除") { _, _ ->
                // 通过回调通知外部处理删除
                onDeleteListener?.onDelete(song)
                Toast.makeText(context, "已从列表中删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
        // 设置删除按钮文字颜色为红色（error 色）
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(errorColor)
    }

    /**
     * 删除回调接口
     */
    interface OnDeleteListener {
        fun onDelete(song: Song)
    }

    var onDeleteListener: OnDeleteListener? = null

    /**
     * 添加到歌单回调接口
     */
    interface OnAddToPlaylistListener {
        fun onAddToPlaylist(song: Song)
    }

    var onAddToPlaylistListener: OnAddToPlaylistListener? = null
}
