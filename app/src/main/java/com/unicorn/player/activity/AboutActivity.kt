package com.unicorn.player.activity

import android.os.Bundle
import com.unicorn.player.R
import com.unicorn.player.databinding.ActivityAboutBinding
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme

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

        // 使用 Markwon 渲染 Markdown 到 TextView（headingBreakHeight=0 去掉标题下的分割线）
        val markwon = Markwon.builder(this)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.headingBreakHeight(0)
                }
            })
            .build()
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
            getString(R.string.load_about_failed, e.message ?: "")
        }
    }
}
