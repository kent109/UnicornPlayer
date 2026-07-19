package com.unicorn.player.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.adapter.SelectableSongAdapter
import com.unicorn.player.databinding.DialogSelectSongsBinding
import com.unicorn.player.model.Song
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 添加歌曲到歌单的弹窗（完整复刻 LrcPreviewDialog 的实现，仅内容不同）
 *
 * - 顶部：歌单名称输入框（可编辑以兼顾改名）
 * - 中间：全量歌曲列表，右侧 CheckBox；歌单内已有歌曲默认勾选
 * - 底部：取消 / 确定 按钮
 *
 * 确定 → 回调 [onConfirm]（歌单新名称 + 勾选的 songId 列表）
 *
 * 显示/关闭动画、上下滑动关闭、触摸手势等全部与 LrcPreviewDialog 一致：
 * 初始 alpha=0 + 透明背景，显示时从触发点缩放淡入，关闭时缩回触发点淡出。
 */
class SelectSongsDialog(
    private val context: Context,
    private val triggerX: Float,
    private val triggerY: Float,
    private val playlistId: Long,
    private val playlistName: String,
    private val allSongs: List<Song>,
    private val alreadySelectedIds: List<Long>,
    private val onConfirm: (newName: String, chosenIds: List<Long>) -> Unit
) {

    private var dialog: AlertDialog? = null
    private var isDismissing = false
    private lateinit var dialogView: View
    private lateinit var binding: DialogSelectSongsBinding
    private lateinit var adapter: SelectableSongAdapter

    // 触摸追踪（与 LrcPreviewDialog 一致）
    private var startY = 0f
    private var startX = 0f
    private var isDragging = false
    private val dismissThreshold = 200f

    fun show() {
        // 构建内容视图
        binding = DialogSelectSongsBinding.inflate(LayoutInflater.from(context))
        // 歌单名预填
        binding.etPlaylistName.setText(playlistName)
        binding.etPlaylistName.setSelection(playlistName.length)
        setupRecyclerView()
        dialogView = binding.root

        // 初始透明，避免尺寸跳变（先以默认大小显示但不可见，调整尺寸后再淡入）
        dialogView.alpha = 0f

        // 设置按钮点击监听
        setupButtonListeners()

        // 设置触摸监听（上下滑动关闭）
        setupTouchListener()

        // 创建 AlertDialog（无标题、无按钮）
        val builder = AlertDialog.Builder(context)
        builder.setView(dialogView)
        builder.setCancelable(true)

        dialog = builder.create()

        // 预先设置透明背景，避免默认背景闪烁
        dialog?.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())

        // 弹窗显示动画 + 设置窗口大小（必须在 show 之后设置才生效）
        dialog?.setOnShowListener {
            val metrics = context.resources.displayMetrics
            // 动态把根布局 minHeight 设为屏幕 60%：保证窗口高度稳定
            dialogView.minimumHeight = (metrics.heightPixels * 0.6f).toInt()

            dialog?.window?.apply {
                setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
                // 宽度 90%，高度 60%
                setLayout(
                    (metrics.widthPixels * 0.9f).toInt(),
                    (metrics.heightPixels * 0.6f).toInt()
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
     * 设置按钮点击监听（与 LrcPreviewDialog.setupButtonListeners 结构一致）
     */
    private fun setupButtonListeners() {
        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnConfirm.setOnClickListener {
            confirm()
        }
    }

    private fun setupRecyclerView() {
        adapter = SelectableSongAdapter()
        adapter.initSelection(alreadySelectedIds)
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SelectSongsDialog.adapter
        }
        adapter.submitList(allSongs)
    }

    private fun confirm() {
        val newName = binding.etPlaylistName.text?.toString()?.trim().orEmpty()
        val chosenIds = adapter.selectedIds.toList()
        val finalName = newName.ifEmpty { playlistName }
        onConfirm(finalName, chosenIds)
        dismiss()
    }

    /**
     * 显示动画：从触发位置放大到中心（参考 LrcPreviewDialog.playShowAnimation）
     */
    private fun playShowAnimation() {
        val metrics = context.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f

        val offsetX = triggerX - centerX
        val offsetY = triggerY - centerY

        dialogView.apply {
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
     * 关闭动画（参考 LrcPreviewDialog.dismissWithAnimation）
     */
    private fun dismissWithAnimation() {
        if (isDismissing) return
        isDismissing = true

        val metrics = context.resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        val offsetX = triggerX - centerX
        val offsetY = triggerY - centerY

        dialogView.animate()
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
     * 回弹到原始位置（参考 LrcPreviewDialog.resetPreviewPosition）
     */
    private fun resetDialogPosition() {
        dialogView.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .start()
    }

    /**
     * 设置触摸监听：上下滑动关闭（参考 LrcPreviewDialog.setupTouchListener）
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
                if (isDismissing) return false

                val recyclerView = binding.recyclerView
                val canScrollUp = recyclerView.canScrollVertically(-1)
                val canScrollDown = recyclerView.canScrollVertically(1)

                val deltaY = e2.y - (e1?.y ?: e2.y)
                val shouldIntercept = (deltaY > 0 && !canScrollUp) || (deltaY < 0 && !canScrollDown)

                if (shouldIntercept) {
                    isDragging = true
                    val offset =
                        hypot((e2.x - startX).toDouble(), (e2.y - startY).toDouble()).toFloat()
                    val progress = (offset / dismissThreshold).coerceIn(0f, 1f)
                    dialogView.translationY += -distanceY * 0.5f
                    dialogView.scaleX = 1f - progress * 0.15f
                    dialogView.scaleY = 1f - progress * 0.15f
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
                if (isDismissing) return false
                if (abs(velocityY) > 800) {
                    dismissWithAnimation()
                    return true
                }
                return false
            }
        }

        val gestureDetector = GestureDetector(context, gestureListener)

        dialogView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging && !isDismissing) {
                        val offset = hypot(
                            (event.x - startX).toDouble(),
                            (event.y - startY).toDouble()
                        ).toFloat()
                        if (offset > dismissThreshold) {
                            dismissWithAnimation()
                        } else {
                            resetDialogPosition()
                        }
                    }
                    isDragging = false
                }
            }
            true
        }
    }

    /**
     * 关闭对话框（带缩放 + 淡出动画，参考 LrcPreviewDialog.dismiss）
     */
    fun dismiss() {
        if (!isDismissing) {
            dismissWithAnimation()
        }
    }

    fun isShowing(): Boolean = dialog?.isShowing == true
}
