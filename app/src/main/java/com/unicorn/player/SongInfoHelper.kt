package com.unicorn.player

import android.content.Context
import android.content.Intent
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.Window
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.unicorn.player.databinding.DialogSongInfoBinding
import com.unicorn.player.databinding.ItemSongInfoBinding
import com.unicorn.player.model.Song
import com.unicorn.player.util.LogWriter
import java.util.Locale

/**
 * 歌曲信息对话框帮助类
 * 负责显示歌曲详细信息、分享和删除功能
 */
class SongInfoHelper(private val context: Context) {

    companion object {
        private const val TAG = "SongInfoHelper"
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
        val dialogBinding = DialogSongInfoBinding.inflate(LayoutInflater.from(context))
        bottomSheetDialog.setContentView(dialogBinding.root)

        // 设置歌曲标题
        dialogBinding.tvSongTitle.text = song.title
        dialogBinding.tvSongTitle.isSelected = true // 激活跑马灯效果

        // 获取文件大小
        val fileSize = getFileSize(song.path)

        // 格式化时长
        val durationText = formatDuration(song.duration)

        // 音质显示
        val qualityText = getQualityText(song.quality)

        // 添加信息项
        val infoItems = listOf(
            Pair("歌手", song.artist),
            Pair("专辑", if (TextUtils.equals(song.album, "Music")) "<unknown>" else song.album),
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

        // 添加到歌单（在"文件路径"后面，"分享本地文件"前面，仅当存在歌单时显示）
        if (showAddToPlaylist) {
            addClickableItem(
                dialogBinding.infoContainer,
                "添加到歌单",
                null
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
        val window: Window? = bottomSheetDialog.window
        window?.let {
            val layoutParams = it.attributes
            layoutParams.windowAnimations = R.style.BottomSheetDialogTheme
            it.attributes = layoutParams
        }
    }

    /**
     * 获取文件大小
     */
    private fun getFileSize(path: String): String {
        return try {
            val file = java.io.File(path)
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
            val file = java.io.File(song.path)
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