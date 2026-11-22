package com.unicorn.player.util

import android.os.Handler
import android.os.Looper
import com.unicorn.player.R
import com.unicorn.player.UnicornPlayerApplication
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

object FeedbackHelper {

    private val client = OkHttpClient()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getCloudFunctionUrl(): String {
        val context = UnicornPlayerApplication.getInstance()
        return context?.getString(R.string.cloud_func_github_issue)
            ?: throw IllegalStateException("Application context not available")
    }

    fun submitFeedback(
        title: String,
        content: String,
        contact: String,
        deviceInfo: String,
        assignee: String? = "kent109",     // 可选，指定处理人
        onSuccess: (String) -> Unit,       // 成功回调，返回 Issue URL
        onError: (String) -> Unit          // 失败回调，返回错误信息
    ) {
        val json = JSONObject().apply {
            put("title", title)
            put("content", content)
            put("contact", contact)
            put("deviceInfo", deviceInfo)
            if (!assignee.isNullOrEmpty()) {
                put("assignee", assignee)
            }
        }

        val requestBody = json.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(getCloudFunctionUrl())
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { bodyStr ->
                    try {
                        val result = JSONObject(bodyStr)
                        val code = result.optInt("code", -1)
                        if (code == 0) {
                            val issueUrl = result.optString("issue_url", "")
                            mainHandler.post { onSuccess(issueUrl) }
                        } else {
                            mainHandler.post { onError(result.optString("msg", "未知错误")) }
                        }
                    } catch (e: Exception) {
                        mainHandler.post { onError("解析响应失败: ${e.message}") }
                    }
                } ?: mainHandler.post { onError("服务器无响应") }
            }

            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onError("网络请求失败: ${e.message}") }
            }
        })
    }
}
