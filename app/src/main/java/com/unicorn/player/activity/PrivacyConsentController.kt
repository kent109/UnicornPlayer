package com.unicorn.player.activity

import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.unicorn.player.R
import com.unicorn.player.util.datastore.privacyDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 隐私协议确认浮层控制器（嵌入 MainActivity 同一窗口展示，无 Activity 跳转）
 *
 * 未同意隐私协议时由 MainActivity.onCreate 守卫调用 [show]，把全屏隐私协议布局
 * 设为 Activity 的当前内容；用户同意后回调 onAccepted（MainActivity 随之初始化主界面），
 * 拒绝则回调 onRefused（MainActivity 结束整个 task 回到桌面）。
 * 同一窗口内不存在 Activity 转场，从结构上规避了 ColorOS 等 ROM 上
 * Splash starting-window 与新 Activity 窗口的叠加竞态。
 */
class PrivacyConsentController(
    private val activity: ComponentActivity,
    private val onAccepted: () -> Unit,
    private val onRefused: () -> Unit,
) {

    private var backBlocker: OnBackPressedCallback? = null

    /** 展示全屏隐私协议浮层（作为 Activity 的当前内容） */
    fun show() {
        // 拦截系统返回键，防止用户绕过隐私协议退出；必须点"退出应用"按钮
        backBlocker = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 什么都不做
            }
        }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }

        activity.setContentView(R.layout.activity_privacy_consent)

        val tvContent = activity.findViewById<TextView>(R.id.tvDialogContent)
        tvContent.text = buildContentWithLink()
        tvContent.movementMethod = LinkMovementMethod.getInstance()

        val checkBox = activity.findViewById<CheckBox>(R.id.cbAgree)
        val btnAgree = activity.findViewById<Button>(R.id.btnAgree)
        val btnReject = activity.findViewById<Button>(R.id.btnReject)

        // 同意按钮默认不可用，勾选复选框后才可点击
        btnAgree.isEnabled = false
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            btnAgree.isEnabled = isChecked
        }

        btnReject.setOnClickListener { onRefused() }

        btnAgree.setOnClickListener {
            markAgreed()
            backBlocker?.remove()
            backBlocker = null
            onAccepted()
        }
    }

    /** 构建带《隐私协议》超链接的正文（链接无下划线，点击打开 AboutActivity） */
    private fun buildContentWithLink(): SpannableStringBuilder {
        val fullText = activity.getString(R.string.privacy_dialog_content_full)
        val privacyLabel = activity.getString(R.string.privacy_dialog_link_label)

        val ssb = SpannableStringBuilder(fullText)
        val start = fullText.indexOf(privacyLabel)
        if (start >= 0) {
            val end = start + privacyLabel.length
            val linkColor = activity.getColor(R.color.privacy_link_color)

            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    activity.startActivity(Intent(activity, AboutActivity::class.java))
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

    private fun markAgreed() {
        runBlocking {
            try {
                activity.privacyDataStore.edit { it[PRIVACY_AGREED_KEY] = true }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        val PRIVACY_AGREED_KEY = booleanPreferencesKey("privacy_agreed")

        /** 同步读取隐私协议同意标记（DataStore 本地读取，无网络） */
        fun isAgreed(activity: ComponentActivity): Boolean = try {
            runBlocking {
                activity.privacyDataStore.data.first()[PRIVACY_AGREED_KEY] ?: false
            }
        } catch (_: Exception) {
            false
        }
    }
}
