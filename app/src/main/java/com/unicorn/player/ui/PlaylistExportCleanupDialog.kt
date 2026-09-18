package com.unicorn.player.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.unicorn.player.adapter.MultiSelectableEqConfigAdapter
import com.unicorn.player.databinding.DialogPlaylistCleanupBinding
import com.unicorn.player.databinding.DialogPlaylistConflictBinding
import com.unicorn.player.model.PlaylistExportData
import com.unicorn.player.repository.MusicRepository
import com.unicorn.player.util.PlaylistFileManager
import com.unicorn.player.viewmodel.PlaylistViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌单导出文件清理弹窗（PlaylistFragment 单个导出与 MainActivity 批量导出共用）。
 *
 * 参照均衡器的实现：达到导出文件数量上限（[PlaylistFileManager.MAX_EXPORT_COUNT]）时弹出，
 * 多选删除导出文件；删除完成后关闭弹窗，不自动继续导出，用户需重新点击导出。
 *
 * 列表项显示优先级：库内歌单名（按文件名解析 id 查库）→ 文件内 playlistName → 文件名本身；
 * 仅显示歌单名，重名时追加编号区分（如"流行(2)"）。
 */
object PlaylistExportCleanupDialog {

    /**
     * 加载导出文件列表并显示清理弹窗（数据加载与删除均在 IO 线程执行）。
     *
     * @param context 调用方 Context（Activity）
     * @param scope 调用方协程作用域（Activity 用 lifecycleScope，Fragment 用 viewLifecycleOwner.lifecycleScope）
     */
    fun show(context: Context, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val files = PlaylistFileManager.listExportFiles(context)
            val displayToFileName = linkedMapOf<String, String>()
            if (files.isNotEmpty()) {
                val repository = MusicRepository(context)
                val usedDisplays = mutableSetOf<String>()
                for (fileName in files) {
                    var display = buildDisplayName(context, repository, fileName)
                    // 去掉文件名后缀后可能出现重名，追加编号保证显示文案唯一
                    if (usedDisplays.contains(display)) {
                        var suffix = 2
                        while (usedDisplays.contains("$display($suffix)")) {
                            suffix++
                        }
                        display = "$display($suffix)"
                    }
                    usedDisplays.add(display)
                    displayToFileName[display] = fileName
                }
            }
            withContext(Dispatchers.Main) {
                showInternal(context, scope, displayToFileName)
            }
        }
    }

    /**
     * 构建列表项显示文案：优先显示歌单名（按文件名解析 id 查库，
     * 查不到时读文件内名称），都查不到时显示文件名本身。
     */
    private suspend fun buildDisplayName(
        context: Context,
        repository: MusicRepository,
        fileName: String
    ): String {
        return try {
            val id = fileName.removeSuffix(".json").toLongOrNull()
            val name = id?.let { repository.getPlaylistById(it) }?.name
                ?: parseNameFromJson(context, fileName)
            if (name.isNullOrBlank()) fileName else name
        } catch (e: Exception) {
            fileName
        }
    }

    /**
     * 从导出文件 JSON 中解析歌单名（歌单已删除、库中查不到时的兜底显示）。
     */
    private fun parseNameFromJson(context: Context, fileName: String): String? {
        return try {
            val content = PlaylistFileManager.readExport(context, fileName) ?: return null
            val data: PlaylistExportData? =
                Gson().fromJson(content, PlaylistExportData::class.java)
            data?.playlistName?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun showInternal(
        context: Context,
        scope: CoroutineScope,
        displayToFileName: Map<String, String>
    ) {
        val dialogBinding = DialogPlaylistCleanupBinding.inflate(LayoutInflater.from(context))
        val dialog = AlertDialog.Builder(context)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val adapter = MultiSelectableEqConfigAdapter { selectedCount ->
            dialogBinding.btnDelete.isEnabled = selectedCount > 0
            dialogBinding.btnDelete.text = if (selectedCount > 0) "删除($selectedCount)" else "删除"
        }
        dialogBinding.recyclerView.layoutManager = LinearLayoutManager(context)
        dialogBinding.recyclerView.adapter = adapter

        /** 渲染列表：空列表时显示空态提示。 */
        fun render(displays: List<String>) {
            if (displays.isEmpty()) {
                dialogBinding.recyclerView.visibility = View.GONE
                dialogBinding.tvEmpty.visibility = View.VISIBLE
            } else {
                dialogBinding.tvEmpty.visibility = View.GONE
                dialogBinding.recyclerView.visibility = View.VISIBLE
            }
            adapter.submitList(displays)
            dialogBinding.btnDelete.isEnabled = false
            dialogBinding.btnDelete.text = "删除"
        }
        render(displayToFileName.keys.toList())

        dialogBinding.btnDelete.setOnClickListener {
            // 选中项是显示文案，映射回真实文件名后删除
            val toDelete = adapter.getSelectedFiles().mapNotNull { displayToFileName[it] }
            if (toDelete.isEmpty()) {
                return@setOnClickListener
            }
            scope.launch(Dispatchers.IO) {
                var successCount = 0
                toDelete.forEach { fileName ->
                    if (PlaylistFileManager.deleteFile(context, fileName)) {
                        successCount++
                    }
                }
                withContext(Dispatchers.Main) {
                    // 删除完成后关闭弹窗，不自动进入导出，用户需重新点击导出
                    dialog.dismiss()
                    Toast.makeText(
                        context,
                        "已删除 $successCount 个导出文件",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}

/**
 * 同名歌单导出冲突弹窗（PlaylistFragment 单个导出与 MainActivity 批量导出共用）。
 *
 * 当导出歌单与已有导出文件同名但 playlistId 不同时弹出（常见于清除应用数据后
 * 重新新建同名歌单），提供：
 * - 合并：旧文件中的歌曲与当前歌单取并集后保存；
 * - 覆盖：仅保存当前歌单。
 * 两种选择都会删除旧文件并以当前 playlistId 重新保存；取消则中止导出。
 */
object PlaylistExportConflictDialog {

    /**
     * @param onChoose 用户选择回调：true = 合并，false = 覆盖，null = 取消
     */
    fun show(
        context: Context,
        conflicts: List<PlaylistViewModel.ExportConflict>,
        onChoose: (merge: Boolean?) -> Unit
    ) {
        val message = buildString {
            if (conflicts.size == 1) {
                append("已存在名为「${conflicts[0].playlistName}」的导出文件")
            } else {
                val names = conflicts.take(3).joinToString("、") { it.playlistName }
                append("存在 ${conflicts.size} 组同名导出文件（$names）")
            }
            append("，可能是清除数据后创建的同名歌单。\n\n")
            append("合并：旧文件与当前歌单合并后保存\n")
            append("覆盖：仅保存当前歌单\n\n")
            append("旧文件都会被删除，以当前歌单重新保存。")
        }
        val dialogBinding = DialogPlaylistConflictBinding.inflate(LayoutInflater.from(context))
        val dialog = AlertDialog.Builder(context)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // 返回键 / 点击外部关闭视为取消
        dialog.setOnCancelListener { onChoose(null) }
        dialogBinding.tvMessage.text = message
        dialogBinding.btnMerge.setOnClickListener {
            dialog.dismiss()
            onChoose(true)
        }
        dialogBinding.btnOverwrite.setOnClickListener {
            dialog.dismiss()
            onChoose(false)
        }
        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
            onChoose(null)
        }
        dialog.show()
    }
}
