package com.unicorn.player

import android.os.Bundle
import com.unicorn.player.databinding.ActivityManualBinding
import io.noties.markwon.Markwon

/**
 * 使用指南页面
 * 展示应用使用说明
 */
class ManualActivity : BaseActivity() {

    private lateinit var binding: ActivityManualBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManualBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 使用 Markwon 渲染 Markdown 到 TextView
        val markwon = Markwon.create(this)
        val markdown = getManualContent()
        markwon.setMarkdown(binding.tvManualContent, markdown)
    }

    /**
     * 获取使用指南 Markdown 文本
     */
    private fun getManualContent(): String {
        return try {
            resources.openRawResource(R.raw.manual).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "加载使用指南失败：${e.message}"
        }
    }
}
