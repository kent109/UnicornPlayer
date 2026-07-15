package com.unicorn.player.ui

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.adapter.SelectableSongAdapter
import com.unicorn.player.databinding.DialogSelectSongsBinding
import com.unicorn.player.model.Song

/**
 * 添加歌曲到歌单的弹窗（AlertDialog + 自定义布局，参考 LrcPreviewDialog）
 *
 * - 顶部：歌单名称输入框（可编辑以兼顾改名）
 * - 中间：全量歌曲列表，右侧 CheckBox；歌单内已有歌曲默认勾选
 * - 底部：取消 / 确定 按钮
 *
 * 确定 → 回调 [onConfirm]（歌单新名称 + 勾选的 songId 列表）
 */
class SelectSongsDialog(
    private val context: Context,
    private val playlistId: Long,
    private val playlistName: String,
    private val allSongs: List<Song>,
    private val alreadySelectedIds: List<Long>,
    private val onConfirm: (newName: String, chosenIds: List<Long>) -> Unit
) {

    private var dialog: AlertDialog? = null
    private lateinit var binding: DialogSelectSongsBinding
    private lateinit var adapter: SelectableSongAdapter

    fun show() {
        binding = DialogSelectSongsBinding.inflate(LayoutInflater.from(context))

        // 歌单名预填
        binding.etPlaylistName.setText(playlistName)
        binding.etPlaylistName.setSelection(playlistName.length)

        setupRecyclerView()

        dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        // 窗口尺寸：宽 90%、高 60%（参考 LrcPreviewDialog）
        dialog?.setOnShowListener {
            val metrics = context.resources.displayMetrics
            dialog?.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(
                    (metrics.widthPixels * 0.9f).toInt(),
                    (metrics.heightPixels * 0.6f).toInt()
                )
            }
        }

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnConfirm.setOnClickListener { confirm() }

        dialog?.show()
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

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
