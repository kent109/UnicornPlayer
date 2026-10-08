package com.unicorn.player.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.unicorn.player.R
import com.unicorn.player.databinding.DialogNewPlaylistBinding
import com.unicorn.player.util.CoverImage
import com.unicorn.player.util.ScreenCornerUtil

/**
 * 新建 / 编辑歌单 底部弹窗
 *
 * - 新建模式：标题「新建歌单」，输入框为空
 * - 编辑模式：标题「编辑歌单」，输入框预填 [initialName]，封面预填 [initialCoverPath]
 *
 * 确定时若输入非空，回调 [onConfirm]（歌单名 + 封面路径）后自动关闭；
 * 点击「修改」按钮触发 [onEditCover]（弹窗不关闭，参数为弹窗自身引用），
 * 外部选图完成后调用其 [updateCover] 刷新预览；
 * 已有自定义封面时显示「默认」按钮，点击回退为默认图标（同样只在确定时写库）；
 * 列表项仍是默认图标（新建或尚未保存过自定义封面）时不显示该按钮。
 */
class NewPlaylistDialog(
    private val context: Context,
    private val initialName: String = "",
    private val initialCoverPath: String = "",
    private val onEditCover: ((NewPlaylistDialog) -> Unit)? = null,
    private val onConfirm: (String, String) -> Unit
) {

    private var dialog: BottomSheetDialog? = null
    private lateinit var binding: DialogNewPlaylistBinding

    /** 当前选定的封面路径，空串表示使用默认图标 */
    private var coverPath: String = ""

    fun show() {
        binding = DialogNewPlaylistBinding.inflate(LayoutInflater.from(context))
        coverPath = initialCoverPath

        if (initialName.isNotEmpty()) {
            binding.tvTitle.text = "编辑歌单"
            binding.etName.setText(initialName)
            binding.etName.setSelection(initialName.length)
        } else {
            binding.tvTitle.text = "新建歌单"
        }

        binding.etName.doAfterTextChanged { text ->
            binding.btnConfirm.isEnabled = !text.isNullOrBlank()
        }
        binding.btnConfirm.isEnabled = initialName.isNotEmpty()

        CoverImage.bind(
            binding.ivCover, coverPath, isPlaying = false, R.drawable.playlist_icon,
            imageInsetDp = 6
        )
        binding.btnEditCover.isEnabled = onEditCover != null
        binding.btnEditCover.setOnClickListener { onEditCover?.invoke(this) }
        binding.btnResetCover.setOnClickListener { resetCover() }
        updateResetCoverVisibility()

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnConfirm.setOnClickListener { confirm() }

        dialog = BottomSheetDialog(context, R.style.BottomSheetDialogTheme).apply {
            setContentView(binding.root)
            show()

            // 用 GradientDrawable 替代 MaterialShapeDrawable，防止拖拽时圆角被动画化为 0
            val designBottomSheet = findViewById<android.view.ViewGroup>(com.google.android.material.R.id.design_bottom_sheet)
            designBottomSheet?.post {
                val cornerRadius =
                    ScreenCornerUtil.getCornerRadiusPx(
                        designBottomSheet.context,
                        ScreenCornerUtil.SHEET_FALLBACK_CORNER_DP
                    )
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(designBottomSheet.context.getColor(R.color.surface))
                    cornerRadii = floatArrayOf(cornerRadius, cornerRadius, cornerRadius, cornerRadius, 0f, 0f, 0f, 0f)
                }
                designBottomSheet.background = drawable
                designBottomSheet.clipToOutline = true
                designBottomSheet.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
        }
    }

    /**
     * 外部选图完成后刷新弹窗上的封面预览
     */
    fun updateCover(path: String) {
        if (!::binding.isInitialized) return
        coverPath = path
        CoverImage.bind(
            binding.ivCover, coverPath, isPlaying = false, R.drawable.playlist_icon,
            imageInsetDp = 6
        )
        updateResetCoverVisibility()
    }

    /** 恢复默认封面：仅更新弹窗内的选择，确认写库时才真正清除旧封面 */
    private fun resetCover() {
        if (!::binding.isInitialized) return
        coverPath = ""
        CoverImage.bind(
            binding.ivCover, coverPath, isPlaying = false, R.drawable.playlist_icon,
            imageInsetDp = 6
        )
        updateResetCoverVisibility()
    }

    /**
     * 「默认」按钮只在列表项已保存自定义封面时显示：
     * 选图后尚未点确定写库，此时 item 仍是默认图标，不提供回退入口
     */
    private fun updateResetCoverVisibility() {
        binding.btnResetCover.visibility =
            if (initialCoverPath.isNotEmpty() && coverPath.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirm() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return
        onConfirm(name, coverPath)
        dismiss()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    /** 弹窗当前是否仍在显示（选图返回时判断能否刷新预览） */
    fun isShowing(): Boolean = dialog?.isShowing == true
}
