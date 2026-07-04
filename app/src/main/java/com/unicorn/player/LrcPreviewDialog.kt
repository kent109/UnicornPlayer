package com.unicorn.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.drawable.toDrawable
import com.unicorn.player.databinding.ViewLrcPreviewBinding
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 歌词预览对话框（基于 AlertDialog）
 *
 * 支持上下滑动关闭、点击外部关闭、返回键关闭。
 * 支持编辑模式：点击"编辑"进入编辑态，点击"保存"判断是否更新内容。
 */
class LrcPreviewDialog(
    private val context: Context,
    private val lrcContent: String,
    private val triggerX: Float,
    private val triggerY: Float,
    private val onContentUpdated: ((String) -> Unit)? = null
) {

    private var dialog: AlertDialog? = null
    private var isDismissing = false
    private lateinit var previewView: View
    private lateinit var binding: ViewLrcPreviewBinding

    /** 初始文本，用于保存时对比是否修改 */
    private val originalContent: String = lrcContent

    /** 是否处于编辑模式 */
    private var isEditMode = false

    // 触摸追踪
    private var startY = 0f
    private var startX = 0f
    private var isDragging = false
    private val dismissThreshold = 200f

    fun show() {
        // 构建内容视图
        binding = ViewLrcPreviewBinding.inflate(android.view.LayoutInflater.from(context))
        binding.etLrcContent.setText(lrcContent)
        binding.dragHandle.visibility = View.GONE  // 隐藏拖动指示条
        previewView = binding.root

        // 初始透明，避免尺寸跳变（先以默认大小显示但不可见，调整尺寸后再淡入）
        previewView.alpha = 0f

        // 设置按钮点击监听
        setupButtonListeners()

        // 设置触摸监听
        setupTouchListener()

        // 创建 AlertDialog（无标题、无按钮）
        val builder = AlertDialog.Builder(context)
        builder.setView(previewView)
        builder.setCancelable(true)

        dialog = builder.create()

        // 预先设置透明背景，避免默认背景闪烁
        dialog?.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

        // 弹窗显示动画 + 设置窗口大小（必须在 show 之后设置才生效）
        dialog?.setOnShowListener {
            dialog?.window?.apply {
                setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
                val metrics = context.resources.displayMetrics
                // 宽度 90%，高度 50%
                setLayout(
                    (metrics.widthPixels * 0.9f).toInt(),
                    (metrics.heightPixels * 0.5f).toInt()
                )
            }
            playShowAnimation()
        }

        dialog?.setOnDismissListener {
            isDismissing = false
        }

        dialog?.show()
    }

    /**
     * 设置按钮点击监听
     */
    private fun setupButtonListeners() {
        // 编辑按钮 - 进入编辑模式
        binding.btnEdit.setOnClickListener {
            enterEditMode()
        }

        // 保存按钮 - 判断是否修改后关闭弹窗
        binding.btnSave.setOnClickListener {
            saveAndDismiss()
        }

        // 取消按钮 - 直接关闭弹窗
        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    /**
     * 进入编辑模式
     */
    private fun enterEditMode() {
        isEditMode = true

        // 切换按钮可见性：隐藏编辑按钮，显示保存和取消按钮
        binding.btnEdit.visibility = View.GONE
        binding.btnSave.visibility = View.VISIBLE
        binding.btnCancel.visibility = View.VISIBLE

        // 启用 EditText 编辑
        val editText = binding.etLrcContent
        editText.isEnabled = true
        editText.isFocusableInTouchMode = true
        editText.isCursorVisible = true
        editText.requestFocus()
        // 将光标移到末尾
        editText.setSelection(editText.text?.length ?: 0)

        // 弹出软键盘
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * 保存后关闭弹窗
     */
    private fun saveAndDismiss() {
        val currentText = binding.etLrcContent.text?.toString() ?: ""

        // 内容有变化才回调更新列表
        if (currentText != originalContent) {
            onContentUpdated?.invoke(currentText)
        }

        // 关闭弹窗
        dismiss()
    }

    /**
     * 显示动画：从触发位置放大到中心
     */
    private fun playShowAnimation() {
        val metrics = context.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f

        val offsetX = triggerX - centerX
        val offsetY = triggerY - centerY

        previewView.apply {
            scaleX = 0.1f
            scaleY = 0.1f
            translationX = offsetX
            translationY = offsetY
            alpha = 0f
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .translationY(0f)
                .alpha(1f)
                .setDuration(250)
                .setListener(null)
                .start()
        }
    }

    /**
     * 关闭动画
     */
    private fun dismissWithAnimation() {
        if (isDismissing) return
        isDismissing = true

        val metrics = context.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        val offsetX = triggerX - centerX
        val offsetY = triggerY - centerY

        previewView.animate()
            .scaleX(0.1f)
            .scaleY(0.1f)
            .translationX(offsetX)
            .translationY(offsetY)
            .alpha(0f)
            .setDuration(200)
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dialog?.dismiss()
                }
            })
            .start()
    }

    /**
     * 回弹到原始位置
     */
    private fun resetPreviewPosition() {
        previewView.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .start()
    }

    /**
     * 设置触摸监听：上下滑动关闭（编辑模式下不拦截）
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // 编辑模式下不处理滑动关闭
                if (isEditMode || isDismissing) return false

                val scrollView = binding.scrollContent
                val canScrollUp = scrollView.canScrollVertically(-1)
                val canScrollDown = scrollView.canScrollVertically(1)

                val deltaY = e2.y - (e1?.y ?: e2.y)
                val shouldIntercept = (deltaY > 0 && !canScrollUp) || (deltaY < 0 && !canScrollDown)

                if (shouldIntercept) {
                    isDragging = true
                    val offset =
                        hypot((e2.x - startX).toDouble(), (e2.y - startY).toDouble()).toFloat()
                    val progress = (offset / dismissThreshold).coerceIn(0f, 1f)
                    previewView.translationY += -distanceY * 0.5f
                    previewView.scaleX = 1f - progress * 0.15f
                    previewView.scaleY = 1f - progress * 0.15f
                    return true
                }
                return false
            }

            override fun onDown(e: MotionEvent): Boolean {
                startX = e.x
                startY = e.y
                isDragging = false
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // 编辑模式下不处理滑动关闭
                if (isEditMode || isDismissing) return false
                if (abs(velocityY) > 800) {
                    dismissWithAnimation()
                    return true
                }
                return false
            }
        }

        val gestureDetector = GestureDetector(context, gestureListener)

        previewView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging && !isDismissing && !isEditMode) {
                        val offset = hypot(
                            (event.x - startX).toDouble(),
                            (event.y - startY).toDouble()
                        ).toFloat()
                        if (offset > dismissThreshold) {
                            dismissWithAnimation()
                        } else {
                            resetPreviewPosition()
                        }
                    }
                    isDragging = false
                }
            }
            true
        }
    }

    /**
     * 关闭对话框
     */
    fun dismiss() {
        if (!isDismissing) {
            dismissWithAnimation()
        }
    }

    fun isShowing(): Boolean = dialog?.isShowing == true
}
