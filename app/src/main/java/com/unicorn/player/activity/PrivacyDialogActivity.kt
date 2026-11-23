package com.unicorn.player.activity

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.unicorn.player.R
import com.unicorn.player.util.datastore.privacyDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 隐私协议确认 Activity（全屏）
 * 作为应用 LAUNCHER 入口，在 MainActivity 之前拦截。
 * 用户同意隐私协议后才允许继续启动 MainActivity；拒绝则强退应用。
 */
class PrivacyDialogActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 拦截系统返回键，防止用户绕过隐私协议退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 什么都不做，必须点"退出应用"按钮
            }
        })

        // 已同意 → 直接进入 MainActivity
        if (isPrivacyAgreed()) {
            launchMainActivity()
            return
        }

        // 未同意 → 展示全屏隐私协议界面
        setContentView(R.layout.activity_privacy_consent)
        setupViews()
    }

    // ========================================================================
    // DataStore 读写
    // ========================================================================

    private fun isPrivacyAgreed(): Boolean {
        return try {
            runBlocking {
                privacyDataStore.data.first()[PRIVACY_AGREED_KEY] ?: false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun markPrivacyAgreed() {
        runBlocking {
            try {
                privacyDataStore.edit { it[PRIVACY_AGREED_KEY] = true }
            } catch (_: Exception) {
            }
        }
    }

    // ========================================================================
    // 视图初始化
    // ========================================================================

    private fun setupViews() {
        // 标题（TitleBar 已通过 layout 中 app:title 设置）

        // 构建带超链接的正文
        val tvContent = findViewById<TextView>(R.id.tvDialogContent)
        tvContent.text = buildContentWithLink()
        tvContent.movementMethod = LinkMovementMethod.getInstance()

        val checkBox = findViewById<CheckBox>(R.id.cbAgree)
        val btnAgree = findViewById<Button>(R.id.btnAgree)
        val btnReject = findViewById<Button>(R.id.btnReject)

        // 同意按钮默认不可用
        btnAgree.isEnabled = false

        checkBox.setOnCheckedChangeListener { _, isChecked ->
            btnAgree.isEnabled = isChecked
        }

        btnReject.setOnClickListener { exitApp() }

        btnAgree.setOnClickListener {
            markPrivacyAgreed()
            launchMainActivity()
        }
    }

    /**
     * 构建带超链接的 Spannable 文本
     */
    private fun buildContentWithLink(): SpannableStringBuilder {
        val fullText = getString(R.string.privacy_dialog_content_full)
        val privacyLabel =
            getString(R.string.privacy_dialog_link_label) // 如 "《隐私协议》" 或 "Privacy Policy"

        val ssb = SpannableStringBuilder(fullText)

        val start = fullText.indexOf(privacyLabel)
        if (start >= 0) {
            val end = start + privacyLabel.length
            val linkColor = getColor(R.color.privacy_link_color)

            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    startActivity(Intent(this@PrivacyDialogActivity, AboutActivity::class.java))
                }
                override fun updateDrawState(ds: android.text.TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = false
                    ds.color = linkColor
                }
            }
            ssb.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return ssb
    }

    // ========================================================================
    // 流程控制
    // ========================================================================

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun exitApp() {
        finish()
        android.os.Handler(mainLooper).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 100)
    }

    companion object {
        private val PRIVACY_AGREED_KEY = booleanPreferencesKey("privacy_agreed")
    }
}
