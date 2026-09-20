package com.unicorn.player

import android.os.Bundle
import com.unicorn.player.databinding.ActivityAboutBinding
import io.noties.markwon.Markwon

/**
 * 关于页面
 * 展示应用信息、用户协议与隐私政策
 */
class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 使用 Markwon 渲染 Markdown 到 TextView
        val markwon = Markwon.create(this)
        val markdown = getAboutContent()
        markwon.setMarkdown(binding.tvAboutContent, markdown)
    }

    /**
     * 获取关于页面 Markdown 文本
     */
    private fun getAboutContent(): String {
        return try {
            resources.openRawResource(R.raw.about).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "加载关于内容失败：${e.message}"
        }
    }
}
