package com.unicorn.player.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.unicorn.player.R
import com.unicorn.player.activity.ThemeSettingActivity
import com.unicorn.player.databinding.DialogSongInfoBinding
import com.unicorn.player.databinding.ItemSongInfoBinding
import com.unicorn.player.model.Album
import com.unicorn.player.util.LogWriter
import com.unicorn.player.util.ScreenCornerUtil
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
    private val onPlay: (() -> Unit)? = null,
    private val onEditCover: (() -> Unit)? = null,
    private val onResetCover: (() -> Unit)? = null
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
            tvSongTitle.text = context.getString(R.string.album_info)
            tvSongTitle.isSelected = true

            val fileSize = calculateTotalFileSize(album.songList)
            val songCount = album.songCount

            val infoItems = listOf(
                Triple(context.getString(R.string.info_album), album.name, R.drawable.ic_album_s),
                Triple(context.getString(R.string.info_count), context.resources.getQuantityString(R.plurals.song_count, songCount, songCount), R.drawable.ic_count_s),
                Triple(context.getString(R.string.info_file_size), fileSize, R.drawable.ic_filesize_s)
            )

            infoItems.forEach { (label, value, iconRes) ->
                val itemBinding = ItemSongInfoBinding.inflate(LayoutInflater.from(context))
                itemBinding.tvLabel.text = label
                itemBinding.tvValue.text = value
                itemBinding.root.background = null
                setIconForLabel(itemBinding.ivIcon, iconRes)
                infoContainer.addView(itemBinding.root)
            }

            if (onPlay != null) {
                addClickableItem(
                    infoContainer, context.getString(R.string.info_play), R.color.blue_500, R.drawable.ic_play2
                ) {
                    bottomSheetDialog.dismiss()
                    onPlay()
                }
            }

            addClickableItem(
                infoContainer, context.getString(R.string.info_add_to_playlist), R.color.disc_playing_color_4, R.drawable.ic_add_to_play
            ) {
                bottomSheetDialog.dismiss()
                onAddToPlaylist()
            }

            if (onEditCover != null) {
                addClickableItem(
                    infoContainer, context.getString(R.string.info_edit_cover), R.color.disc_playing_color_2, R.drawable.ic_edit2
                ) {
                    bottomSheetDialog.dismiss()
                    onEditCover()
                }
            }

            // 仅当前为自定义图片时才提供「默认封面」
            if (onResetCover != null && album.coverPath.isNotEmpty()) {
                addClickableItem(
                    infoContainer, context.getString(R.string.info_default_cover), R.color.primary, R.drawable.ic_reset
                ) {
                    bottomSheetDialog.dismiss()
                    onResetCover()
                }
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

            val cornerRadius =
                ScreenCornerUtil.getCornerRadiusPx(
                    designBottomSheet.context,
                    ScreenCornerUtil.SHEET_FALLBACK_CORNER_DP
                )
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(ThemeSettingActivity.resolveDialogPanelColor(context))
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
     * @param iconRes 左侧图标资源 ID，0 表示不显示
     */
    private fun addClickableItem(
        container: android.view.ViewGroup, text: String, textColor: Int, iconRes: Int = 0, onClick: () -> Unit
    ) {
        val itemBinding = ItemSongInfoBinding.inflate(LayoutInflater.from(context))
        itemBinding.tvLabel.visibility = android.view.View.GONE
        itemBinding.tvValue.text = text
        setIconForLabel(itemBinding.ivIcon, iconRes)
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

    /**
     * 为 item 左侧图标设置对应 drawable，resId 为 0 则隐藏
     */
    private fun setIconForLabel(icon: android.widget.ImageView, resId: Int) {
        if (resId != 0) {
            icon.setImageResource(resId)
            icon.visibility = View.VISIBLE
        } else {
            icon.visibility = View.GONE
        }
    }
}
