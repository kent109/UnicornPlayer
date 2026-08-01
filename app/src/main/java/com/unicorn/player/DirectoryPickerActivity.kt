package com.unicorn.player

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.unicorn.player.databinding.ActivityDirectoryPickerBinding
import java.io.File

/**
 * 目录选择页面
 * 列出 sdcard 下一级子目录，供用户选择排除或指定扫描的目录
 */
class DirectoryPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_SELECTED = "extra_selected"
        const val MODE_EXCLUDE = "exclude"
        const val MODE_INCLUDE = "include"
    }

    private lateinit var binding: ActivityDirectoryPickerBinding
    private lateinit var adapter: DirectoryAdapter
    private val selectedPaths = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDirectoryPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 根据模式设置标题
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_EXCLUDE
        val title = if (mode == MODE_INCLUDE) "选择扫描目录" else "选择不扫描目录"
        binding.titleBar.setTitle(title)

        // 恢复已选目录
        intent.getStringArrayListExtra(EXTRA_SELECTED)?.let {
            selectedPaths.addAll(it)
        }

        setupRecyclerView()
        loadDirectories()

        binding.btnConfirm.setOnClickListener {
            val resultIntent = Intent().putStringArrayListExtra(
                "selected_dirs", ArrayList(selectedPaths)
            )
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    /**
     * 设置 RecyclerView
     */
    private fun setupRecyclerView() {
        adapter = DirectoryAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    /**
     * 加载 sdcard 一级子目录（过滤隐藏目录）
     */
    private fun loadDirectories() {
        val dirs = try {
            val sdcard = Environment.getExternalStorageDirectory()
            sdcard.listFiles { file ->
                file.isDirectory && !file.name.startsWith(".") && !file.name.startsWith("com.")
            }?.sortedBy { it.name.lowercase() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (dirs.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            adapter.submitList(dirs)
        }
    }

    /**
     * 目录列表适配器
     */
    inner class DirectoryAdapter : RecyclerView.Adapter<DirectoryAdapter.ViewHolder>() {

        private val items = mutableListOf<File>()

        fun submitList(list: List<File>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view =
                LayoutInflater.from(parent.context).inflate(R.layout.item_directory, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val dir = items[position]
            holder.tvTitle.text = dir.name
            holder.tvSummary.text = dir.absolutePath

            // 先移除监听器，避免 setChecked 触发回调污染 selectedPaths
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = selectedPaths.contains(dir.absolutePath)
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                val path = items[pos].absolutePath
                if (isChecked) selectedPaths.add(path) else selectedPaths.remove(path)
            }

            // 点击 item 只切换复选框状态，由复选框监听器同步数据，无需刷新整个 item
            holder.itemView.setOnClickListener {
                holder.checkBox.isChecked = !holder.checkBox.isChecked
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
            val tvSummary: TextView = itemView.findViewById(R.id.tvSummary)
            val checkBox: CheckBox = itemView.findViewById(R.id.checkBox)
        }
    }
}
