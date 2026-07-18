package com.unicorn.player

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.unicorn.player.util.toSimpleCustom
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.unicorn.player.adapter.LrcSearchResultAdapter
import com.unicorn.player.databinding.ActivityLrcSearchBinding
import com.unicorn.player.model.LrcSearchResult
import com.unicorn.player.util.LrcFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 歌词搜索界面
 * 允许用户手动输入歌手/歌名搜索 lrclib.net 并选择下载歌词
 */
class LrcSearchActivity : AppCompatActivity(),
    LrcSearchResultAdapter.OnResultClickListener,
    LrcSearchResultAdapter.OnResultLongClickListener {

    private lateinit var binding: ActivityLrcSearchBinding
    private lateinit var adapter: LrcSearchResultAdapter

    /** 当前播放歌曲的音频文件路径，用于确定 .lrc 保存位置 */
    private var audioPath: String = ""

    companion object {
        const val TAG = "LrcSearchActivity"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_AUDIO_PATH = "extra_audio_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLrcSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 获取传入参数
        val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH) ?: ""

        // 初始化 TitleBar 返回按钮
        binding.titleBar.findViewById<View>(R.id.ivBack).setOnClickListener { finish() }
        binding.titleBar.findViewById<android.widget.TextView>(R.id.tvTitle).text = "搜索歌词"

        // 预填输入框
        binding.etArtist.setText(artist)
        binding.etTitle.setText(title)

        // 初始化 RecyclerView
        adapter = LrcSearchResultAdapter(this, this)
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter

        // 搜索按钮点击
        binding.btnSearch.setOnClickListener { startSearch() }

        // 配置 SmartRefreshLayout 的 overscroll 效果
        binding.smartRefreshLayout.apply {
            setEnableOverScrollBounce(true)   // 回弹效果
            setEnableOverScrollDrag(true)    // 拖拽越界效果
            setEnableRefresh(false)          // 禁用下拉刷新（保持纯 overscroll 体验）
        }
    }

    /**
     * 开始搜索
     */
    private fun startSearch() {
        val artist = binding.etArtist.text?.toString()?.trim() ?: ""
        val title = binding.etTitle.text?.toString()?.trim() ?: ""

        when {
            artist.isBlank() && title.isBlank() -> {
                Toast.makeText(this, "请填写歌手和歌名", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 隐藏输入法
        hideKeyboard()

        // 禁用输入框和搜索按钮，按钮文字改为"正在搜索"
        setSearchingState(true)

        // 显示 loading，清空旧结果
        binding.progressBar.visibility = View.VISIBLE
        adapter.selectedId = null
        adapter.submitList(emptyList())

        LrcFetcher.searchLyrics(artist, title, object : LrcFetcher.LrcSearchCallback {
            override fun onSearchResults(results: List<LrcSearchResult>) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    setSearchingState(false)
                    if (results.isEmpty()) {
                        Toast.makeText(this@LrcSearchActivity, "未搜索到歌词", Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        adapter.submitList(results)
                        Log.i(TAG, "搜索到 ${results.size} 条歌词")
                    }
                }
            }

            override fun onSearchFailure(message: String) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    setSearchingState(false)
                    Toast.makeText(this@LrcSearchActivity, "搜索失败: $message", Toast.LENGTH_SHORT)
                        .show()
                    Log.e(TAG, "搜索失败: $message")
                }
            }
        })
    }

    /** 当前显示的歌词预览对话框 */
    private var previewDialog: LrcPreviewDialog? = null

    /**
     * 搜索结果点击 - 弹出歌词预览对话框（从 item 位置弹出，可滑动关闭，支持编辑）
     */
    override fun onResultLongClick(result: LrcSearchResult) {
        MaterialAlertDialogBuilder(this)
            .setTitle("使用歌词")
            .setMessage("确定要使用「${result.trackName} - ${result.artistName}」的歌词吗？")
            .setPositiveButton("确定") { _, _ ->
                saveSelectedLyrics(result)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 更新搜索结果项的歌词内容，并标记为已修改（右侧显示已修改图标）
     */
    private fun updateResultContent(result: LrcSearchResult, newContent: String) {
        val currentList = adapter.currentList.toMutableList()
        val index = currentList.indexOfFirst { it.id == result.id }
        if (index >= 0) {
            currentList[index] = result.copy(syncedLyrics = newContent, isModified = true)
            adapter.submitList(currentList)
            Log.i(TAG, "已更新第 ${index + 1} 条搜索结果歌词内容（已标记修改）")
        }
    }

    /**
     * 搜索结果长按 - 弹出"使用歌词"确认对话框
     */
    override fun onResultClick(result: LrcSearchResult) {
        // 获取列表在屏幕上的中心位置作为动画起点
        val location = IntArray(2)
        binding.rvResults.getLocationOnScreen(location)
        val centerX = location[0] + binding.rvResults.width / 2f
        val centerY = location[1] + binding.rvResults.height / 2f

        previewDialog = LrcPreviewDialog(
            this,
            result.syncedLyrics,
            centerX,
            centerY,
            onContentUpdated = { updatedContent ->
                // 更新列表 item 的 syncedLyrics
                updateResultContent(result, updatedContent)
            }
        ).also { it.show() }
    }

    /**
     * 保存选中的歌词到本地
     * 先删除当前本地歌词（如有），再写入新内容
     */
    private fun saveSelectedLyrics(result: LrcSearchResult) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val audioFile = File(audioPath)
                    val parentDir = audioFile.parent ?: throw Exception("无法获取文件目录")
                    val baseName = audioFile.nameWithoutExtension
                    val lrcFile = File(parentDir, "$baseName.lrc")

                    // 删除已有歌词文件
                    if (lrcFile.exists()) {
                        lrcFile.delete()
                        Log.i(TAG, "已删除旧歌词: ${lrcFile.absolutePath}")
                    }

                    // 首行插入 "[00:00.00]Artist - Title"
                    val artist = result.artistName.ifBlank {
                        baseName.substringBefore(" - ").trim()
                    }
                    val title = result.trackName.ifBlank {
                        baseName.substringAfter(" - ", baseName).trim()
                    }
                    // 清理 <数字:数字.数字> 标签
                    val cleanedLyrics = LrcFetcher.cleanTimestampTags(result.syncedLyrics)

                    val header = "[00:00.00]$artist - $title"
                    val lrcContent = header + "\r\n" + cleanedLyrics

                    // 转为简体中文后写入文件（CRLF 换行）
                    val simplifiedContent = lrcContent.toSimpleCustom()
                    lrcFile.writeText(simplifiedContent, Charsets.UTF_8)
                    Log.i(TAG, "歌词保存成功: ${lrcFile.absolutePath}")
                }

                Toast.makeText(this@LrcSearchActivity, "歌词已保存", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "保存歌词失败: ${e.message}", e)
                Toast.makeText(this@LrcSearchActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    /**
     * 设置搜索进行中的 UI 状态
     * @param searching true=禁用输入/按钮并显示"正在搜索"，false=恢复可用
     */
    private fun setSearchingState(searching: Boolean) {
        binding.etArtist.isEnabled = !searching
        binding.etTitle.isEnabled = !searching
        binding.btnSearch.isEnabled = !searching
        binding.btnSearch.text = if (searching) "正在搜索" else "搜索"
        // 搜索中使用禁用色
        val typedValue = TypedValue()
        theme.resolveAttribute(
            com.google.android.material.R.attr.colorPrimary, typedValue, true
        )
        val color = ContextCompat.getColor(this, typedValue.resourceId)
        val dimmed = if (searching) (color and 0x00FFFFFF) or (0xCC shl 24) else color
        binding.btnSearch.backgroundTintList = ColorStateList.valueOf(dimmed)
    }

    /**
     * 隐藏软键盘
     */
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val view = currentFocus ?: binding.root
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 取消所有进行中的网络请求
        LrcFetcher.cancelAll()
    }
}
