package com.unicorn.player.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.unicorn.player.R
import com.unicorn.player.databinding.DialogSongInfoBinding
import com.unicorn.player.databinding.ItemSongInfoBinding
import com.unicorn.player.model.Album
import com.unicorn.player.util.LogWriter
import com.unicorn.player.util.SizeUtil
import java.io.File

/**
 * 专辑信息对话框帮助类
 * 负责显示专辑详细信息、播放专辑歌曲、添加到歌单功能
 */
class AlbumInfoDialog(
    private val context: Context,
    private val album: Album,
    private val onAddToPlaylist: () -> Unit,
    private val onPlay: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "AlbumInfoDialog"
    }

    /**
     * 显示专辑信息的 BottomSheetDialog
     */
    fun show() {
        val bottomSheetDialog = BottomSheetDialog(context, R.style.BottomSheetDialogTheme)
        val dialogBinding = DialogSongInfoBinding.inflate(LayoutInflater.from(context))
        bottomSheetDialog.setContentView(dialogBinding.root)

        dialogBinding.apply {
            tvSongTitle.text = "专辑信息"
            tvSongTitle.isSelected = true

            val fileSize = calculateTotalFileSize(album.songList)
            val songCount = album.songCount

            val infoItems = listOf(
                Pair("专辑", album.name),
                Pair("歌曲数量", "$songCount 首"),
                Pair("文件大小", fileSize)
            )

            infoItems.forEach { (label, value) ->
                val itemBinding = ItemSongInfoBinding.inflate(LayoutInflater.from(context))
                itemBinding.tvLabel.text = label
                itemBinding.tvValue.text = value
                itemBinding.root.background = null
                infoContainer.addView(itemBinding.root)
            }

            if (onPlay != null) {
                addClickableItem(
                    infoContainer, "播放", R.color.blue_500
                ) {
                    bottomSheetDialog.dismiss()
                    onPlay()
                }
            }

            addClickableItem(
                infoContainer, "添加到歌单", R.color.quality_std
            ) {
                bottomSheetDialog.dismiss()
                onAddToPlaylist()
            }
        }

        bottomSheetDialog.show()

        val designBottomSheet = bottomSheetDialog.findViewById<android.view.ViewGroup>(
            com.google.android.material.R.id.design_bottom_sheet
        )
        designBottomSheet?.post {
            val behavior =
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(designBottomSheet)
            behavior.state =
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.isHideable = true
            behavior.skipCollapsed = true

            val cornerRadius = 32f * designBottomSheet.resources.displayMetrics.density
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(designBottomSheet.context.getColor(R.color.surface))
                cornerRadii = floatArrayOf(
                    cornerRadius, cornerRadius, cornerRadius, cornerRadius, 0f, 0f, 0f, 0f
                )
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
     * 计算专辑所有歌曲的总文件大小
     */
    private fun calculateTotalFileSize(songs: List<com.unicorn.player.model.Song>): String {
        var totalSize = 0L
        for (song in songs) {
            try {
                val file = File(song.path)
                if (file.exists()) {
                    totalSize += file.length()
                }
            } catch (e: Exception) {
                LogWriter.writeError(TAG, "获取文件大小失败: ${song.path}", e)
            }
        }

        return SizeUtil.formatSize(totalSize)
    }

    /**
     * 添加可点击的操作项
     */
    private fun addClickableItem(
        container: android.view.ViewGroup, text: String, textColor: Int, onClick: () -> Unit
    ) {
        val itemBinding = ItemSongInfoBinding.inflate(LayoutInflater.from(context))
        itemBinding.tvLabel.visibility = android.view.View.GONE
        itemBinding.tvValue.text = text
        itemBinding.tvValue.textSize = 16f
        itemBinding.tvValue.setTypeface(
            itemBinding.tvValue.typeface, android.graphics.Typeface.BOLD
        )
        itemBinding.tvValue.gravity = android.view.Gravity.CENTER_VERTICAL
        itemBinding.root.gravity = android.view.Gravity.CENTER_VERTICAL
        val paddingV = (10 * context.resources.displayMetrics.density).toInt()
        itemBinding.root.setPadding(
            itemBinding.root.paddingLeft, paddingV, itemBinding.root.paddingRight, paddingV
        )
        itemBinding.tvValue.setTextColor(context.getColor(textColor))
        itemBinding.root.setOnClickListener { onClick() }
        container.addView(itemBinding.root)
    }
}
