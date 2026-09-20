package com.unicorn.player.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.R
import com.unicorn.player.adapter.SelectableImportPlaylistAdapter
import com.unicorn.player.databinding.DialogEmptyPlaylistActionBinding
import com.unicorn.player.databinding.DialogPlaylistImportBinding
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.util.PlaylistFileManager
import com.unicorn.player.viewmodel.PlaylistViewModel
import com.unicorn.player.viewmodel.PlaylistViewModelFactory
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 空歌单操作弹窗：当用户点击"添加到歌单"但暂无歌单时显示。
 *
 * - "取消"：关闭弹窗
 * - "创建"：弹出新建歌单对话框，创建成功后回调 [onPlaylistCreated]
 * - "导入"：仅当存在导出文件时显示，点击后直接弹出导入弹窗
 */
class EmptyPlaylistActionDialog(
    private val activity: AppCompatActivity,
    private val onPlaylistCreated: () -> Unit
) {

    fun show() {
        val hasExportFiles = PlaylistFileManager.hasPermission(activity) &&
            PlaylistFileManager.listExportFiles(activity).isNotEmpty()

        val dialogBinding = DialogEmptyPlaylistActionBinding.inflate(LayoutInflater.from(activity))
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        if (hasExportFiles) {
            dialogBinding.btnImport.visibility = View.VISIBLE
            dialogBinding.tvMessage.text = "可以新建歌单，或者导入已有歌单。"
        } else {
            dialogBinding.tvMessage.text = "是否创建新歌单？"
        }

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnCreate.setOnClickListener {
            dialog.dismiss()
            showCreatePlaylistFlow()
        }
        dialogBinding.btnImport.setOnClickListener {
            dialog.dismiss()
            showImportFlow()
        }

        dialog.show()
    }

    private fun showCreatePlaylistFlow() {
        if (!canShowDialog()) return
        NewPlaylistDialog(
            context = activity,
            initialName = "",
            onConfirm = { name ->
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) {
                    val repository = MusicRepository(activity)
                    val factory = PlaylistViewModelFactory(repository, activity.application)
                    val viewModel = ViewModelProvider(activity, factory)[PlaylistViewModel::class.java]
                    (activity as LifecycleOwner).lifecycleScope.launch {
                        val isDuplicate = withContext(Dispatchers.IO) {
                            repository.isPlaylistNameUsed(trimmed)
                        }
                        if (isDuplicate) {
                            if (canShowDialog()) {
                                Toast.makeText(
                                    activity,
                                    activity.getString(R.string.playlist_name_exists, trimmed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            return@launch
                        }
                        withContext(Dispatchers.IO) {
                            repository.createPlaylist(trimmed)
                        }
                        viewModel.refreshPlaylistsSuspend()
                        if (canShowDialog()) {
                            onPlaylistCreated()
                        }
                    }
                }
            }
        ).show()
    }

    private fun showImportFlow() {
        if (!canShowDialog()) return
        val repository = MusicRepository(activity)
        val factory = PlaylistViewModelFactory(repository, activity.application)
        val viewModel = ViewModelProvider(activity, factory)[PlaylistViewModel::class.java]
        viewModel.loadImportItems { items ->
            if (canShowDialog()) {
                showImportPlaylistDialog(items, viewModel)
            }
        }
    }

    private fun showImportPlaylistDialog(
        items: List<PlaylistViewModel.PlaylistImportItem>,
        viewModel: PlaylistViewModel
    ) {
        val dialogBinding = DialogPlaylistImportBinding.inflate(LayoutInflater.from(activity))
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val adapter = SelectableImportPlaylistAdapter { selectedCount ->
            dialogBinding.btnConfirm.isEnabled = selectedCount > 0
        }
        dialogBinding.recyclerView.layoutManager = LinearLayoutManager(activity)
        dialogBinding.recyclerView.adapter = adapter
        adapter.submitList(items)
        dialogBinding.btnConfirm.isEnabled = false
        if (items.isEmpty()) {
            dialogBinding.recyclerView.visibility = View.GONE
            dialogBinding.tvEmpty.visibility = View.VISIBLE
        } else {
            dialogBinding.tvEmpty.visibility = View.GONE
        }

        dialogBinding.btnConfirm.setOnClickListener {
            val selected = adapter.getSelectedFiles()
            if (selected.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            viewModel.importPlaylists(selected) { created, merged, failed, droppedSongs ->
                if (!canShowDialog()) return@importPlaylists
                if (created + merged > 0) {
                    var msg = "导入完成：新建 $created 个，合并 $merged 个"
                    if (failed > 0) {
                        msg += "，失败 $failed 个"
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                    if (droppedSongs > 0) {
                        Toast.makeText(
                            activity,
                            "$droppedSongs 首歌曲已不在曲库，未导入",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    (activity as LifecycleOwner).lifecycleScope.launch {
                        viewModel.refreshPlaylistsSuspend()
                        if (canShowDialog()) {
                            onPlaylistCreated()
                        }
                    }
                } else {
                    Toast.makeText(activity, "导入失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun canShowDialog(): Boolean {
        return !activity.isFinishing && !activity.isDestroyed
    }
}
