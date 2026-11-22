package com.unicorn.player.util.diag

import com.unicorn.player.util.LogWriter

/**
 * 诊断工具：栈帧过滤 + 调用链输出。
 * 集中在 util.diag 避免 Service/Activity 内部重复实现。
 */
object CallStack {

    /**
     * 从当前线程栈里提取调用链片段。
     * @param skipFrames 跳过的最上层帧数
     * @param prefixFilter 只保留类名以此前缀开头的栈帧（null 保留全部）
     * @param exactClassFilter 只保留类名完全等于该值的栈帧（优先级高于 prefixFilter）
     * @param maxFrames 最多返回帧数
     */
    fun callerTrace(
        skipFrames: Int = 3,
        prefixFilter: String? = "com.unicorn.player",
        exactClassFilter: String? = null,
        maxFrames: Int = 4,
    ): String {
        if (!LogWriter.isDiagEnabled) return ""
        return Thread.currentThread().stackTrace
            .drop(skipFrames)
            .let { stack ->
                when {
                    exactClassFilter != null -> stack.filter { it.className == exactClassFilter || it.className.startsWith("com.unicorn.player") }
                    prefixFilter != null -> stack.filter { it.className.startsWith(prefixFilter) }
                    else -> stack
                }
            }
            .take(maxFrames)
            .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    }
}
