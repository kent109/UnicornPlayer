package com.unicorn.player

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.unicorn.player.databinding.ActivityFeedbackBinding

/**
 * 意见反馈页面
 * 展示反馈邮箱并提供反馈内容输入和提交功能
 */
class FeedbackActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFeedbackBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeedbackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
    }

    /**
     * 初始化视图
     */
    private fun setupViews() {
        val btnSubmit = binding.btnSubmit
        val etFeedbackContent = binding.etFeedbackContent
        val etContact = binding.etContact

        btnSubmit.setOnClickListener {
            val feedbackContent = etFeedbackContent.text.toString().trim()
            val contact = etContact.text.toString().trim()

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

            Toast.makeText(this, "提交成功", Toast.LENGTH_SHORT).show()

            etFeedbackContent.text.clear()
            etContact.text.clear()
        }
    }
}
