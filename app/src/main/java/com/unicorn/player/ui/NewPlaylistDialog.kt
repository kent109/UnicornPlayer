package com.unicorn.player.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.unicorn.player.R
import com.unicorn.player.databinding.DialogNewPlaylistBinding

/**
 * 新建 / 编辑歌单 底部弹窗
 *
 * - 新建模式：标题「新建歌单」，输入框为空
 * - 编辑模式：标题「编辑歌单」，输入框预填 [initialName]
 *
 * 确定时若输入非空，回调 [onConfirm] 后自动关闭
 */
class NewPlaylistDialog(
    private val context: Context,
    private val initialName: String = "",
    private val onConfirm: (String) -> Unit
) {

    private var dialog: BottomSheetDialog? = null
    private lateinit var binding: DialogNewPlaylistBinding

    fun show() {
        binding = DialogNewPlaylistBinding.inflate(LayoutInflater.from(context))

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

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnConfirm.setOnClickListener { confirm() }

        dialog = BottomSheetDialog(context, R.style.BottomSheetDialogTheme).apply {
            setContentView(binding.root)
            show()

            // 用 GradientDrawable 替代 MaterialShapeDrawable，防止拖拽时圆角被动画化为 0
            val designBottomSheet = findViewById<android.view.ViewGroup>(com.google.android.material.R.id.design_bottom_sheet)
            designBottomSheet?.post {
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
        }
    }

    private fun confirm() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return
        onConfirm(name)
        dismiss()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
