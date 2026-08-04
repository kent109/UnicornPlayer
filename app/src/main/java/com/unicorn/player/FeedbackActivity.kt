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
    private var isSubmitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeedbackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceInfo = "${Build.BRAND}_${Build.MODEL}_${Build.VERSION.RELEASE}"
        setupViews()
        setupInputListeners()
        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.alpha = 0.6f
    }

    /**
     * 初始化视图
     */
    private fun setupViews() {
        val etFeedbackContent = binding.etFeedbackContent
        val etContact = binding.etContact
        val etTitle = binding.etTitle

        binding.btnSubmit.setOnClickListener {
            if (isSubmitting) {
                return@setOnClickListener
            }

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

            isSubmitting = true
            binding.btnSubmit.isEnabled = false

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
                    isSubmitting = false
                    checkInputs()
                },
                onError = { errorMsg ->
                    Log.e(TAG, "errorMsg=$errorMsg")
                    Toast.makeText(this, "提交失败: $errorMsg", Toast.LENGTH_LONG).show()
                    isSubmitting = false
                    checkInputs()
                }
            )
        }
    }

    /**
     * 为输入框添加监听器
     */
    private fun setupInputListeners() {
        val listener = { _: android.view.View ->
            checkInputs()
        }

        binding.etFeedbackContent.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                listener(binding.etFeedbackContent)
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.etContact.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                listener(binding.etContact)
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.etTitle.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                listener(binding.etTitle)
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    /**
     * 检查3个输入框是否有内容
     */
    private fun checkInputs() {
        if (isSubmitting) {
            return
        }

        val feedbackContent = binding.etFeedbackContent.text.toString().trim()
        val contact = binding.etContact.text.toString().trim()
        val title = binding.etTitle.text.toString().trim()

        val hasContent = feedbackContent.isNotEmpty() && contact.isNotEmpty() && title.isNotEmpty()

        binding.btnSubmit.isEnabled = hasContent
        binding.btnSubmit.alpha = if (hasContent) 1.0f else 0.6f
    }

    /**
     * 重置提交状态
     */
    override fun onDestroy() {
        super.onDestroy()
        isSubmitting = false
    }
}
