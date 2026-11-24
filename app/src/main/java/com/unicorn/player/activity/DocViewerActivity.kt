package com.unicorn.player.activity

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import com.unicorn.player.R
import com.unicorn.player.databinding.ActivityDocViewerBinding

/**
 * 文档查看页（WebView 渲染）
 * EXTRA_DOC 决定展示哪份文档：
 * - privacy：隐私政策（默认，隐私弹窗超链接也指向这里）
 * - agreement：用户协议
 * HTML 按 res/raw 语言限定符自动匹配系统/应用语言（raw 与 raw-en）。
 */
class DocViewerActivity : BaseActivity() {

    private lateinit var binding: ActivityDocViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        when (intent.getStringExtra(EXTRA_DOC) ?: DOC_PRIVACY) {
            DOC_AGREEMENT -> {
                binding.titleBar.setTitle(getString(R.string.title_user_agreement))
                loadDoc("agreement.html")
            }
            else -> {
                binding.titleBar.setTitle(getString(R.string.title_privacy))
                loadDoc("privacy.html")
            }
        }

        // 返回键 / TitleBar 返回箭头：WebView 有历史记录时先回退页面，没有历史才退出
        binding.titleBar.setOnBackClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleBack()
            }
        )
    }

    /** WebView 可回退时退回上一页，否则退出 Activity */
    private fun handleBack() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            finish()
        }
    }

    /**
     * WebView 加载 raw 下同名 HTML（file:///android_res/raw/ 会按语言限定符解析）。
     * 页面背景透明，露出 Activity 背景色；文字颜色由 HTML 内的
     * prefers-color-scheme 媒体查询适配深色模式。
     * mailto: 链接转交给系统邮件应用，其余链接（file:// 文档互跳）仍由 WebView 加载。
     */
    private fun loadDoc(fileName: String) {
        binding.webView.settings.javaScriptEnabled = false
        binding.webView.setBackgroundColor(Color.TRANSPARENT)
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                if (uri.scheme == "mailto") {
                    try {
                        startActivity(Intent(Intent.ACTION_SENDTO, uri))
                    } catch (_: Exception) {
                        // 无邮件应用时静默忽略
                    }
                    return true
                }
                return false
            }
        }
        binding.webView.loadUrl("file:///android_res/raw/$fileName")
    }

    companion object {
        /** 文档类型 extra：privacy / agreement */
        const val EXTRA_DOC = "extra_doc"
        const val DOC_PRIVACY = "privacy"
        const val DOC_AGREEMENT = "agreement"
    }
}
