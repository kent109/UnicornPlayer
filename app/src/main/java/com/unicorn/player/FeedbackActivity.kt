package com.unicorn.player

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.unicorn.player.databinding.ActivityFeedbackBinding
import com.unicorn.player.util.FeedbackHelper

/**
 * 意见反馈页面
 * 展示反馈邮箱并提供反馈内容输入和提交功能
 */
class FeedbackActivity : AppCompatActivity() {

    companion object {
        const val TAG = "FeedbackActivity"
    }

    private lateinit var binding: ActivityFeedbackBinding
    private var deviceInfo: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeedbackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceInfo = "${Build.BRAND}_${Build.MODEL}_${Build.VERSION.RELEASE}"
        setupViews()
    }

    /**
     * 初始化视图
     */
    private fun setupViews() {
        val btnSubmit = binding.btnSubmit
        val etFeedbackContent = binding.etFeedbackContent
        val etContact = binding.etContact
        val etTitle = binding.etTitle

        btnSubmit.setOnClickListener {
            val feedbackContent = etFeedbackContent.text.toString().trim()
            val contact = etContact.text.toString().trim()
            val title = etTitle.text.toString().trim()

            if (title.isEmpty()) {
                Toast.makeText(this, "请输入问题描述", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (feedbackContent.isEmpty()) {
                Toast.makeText(this, "请输入反馈内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (feedbackContent.length > 500) {
                Toast.makeText(this, "反馈内容不能超过500字", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (contact.isEmpty()) {
                Toast.makeText(this, "请输入联系方式", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false

            FeedbackHelper.submitFeedback(
                title = title,
                content = feedbackContent,
                contact = contact,
                deviceInfo = deviceInfo,
                onSuccess = { _ ->
                    Toast.makeText(this, "提交成功", Toast.LENGTH_SHORT).show()
                    etFeedbackContent.text.clear()
                    etContact.text.clear()
                    etTitle.text.clear()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                    btnSubmit.isEnabled = true
                },
                onError = { errorMsg ->
                    Log.e(TAG, "errorMsg=$errorMsg")
                    Toast.makeText(this, "提交失败: $errorMsg", Toast.LENGTH_LONG).show()
                    btnSubmit.isEnabled = true
                }
            )
        }
    }
}
