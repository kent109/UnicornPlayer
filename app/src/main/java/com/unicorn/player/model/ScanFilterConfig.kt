package com.unicorn.player.model

import java.io.File

/**
 * 扫描过滤配置
 * 用于 [com.unicorn.player.repository.MusicRepository.scanMusicFiles] 过滤音频文件
 */
data class ScanFilterConfig(
    val skipShortAudio: Boolean = false,
    val skipSmallFiles: Boolean = false,
    val excludedDirs: Set<String> = emptySet(),
    val includedDirs: Set<String> = emptySet()
) {
    /**
     * 判断指定文件是否应被跳过
     * @param duration 音频时长（毫秒）
     * @param file 音频文件
     * @return true 表示应跳过该文件
     */
    fun shouldSkip(duration: Long, file: File): Boolean {
        if (skipShortAudio && duration < 30_000) return true
        if (skipSmallFiles && file.length() < 100 * 1024) return true
        val absolutePath = file.absolutePath
        if (excludedDirs.any { absolutePath.startsWith(it) }) return true
        if (includedDirs.isNotEmpty() && includedDirs.none { absolutePath.startsWith(it) }) return true
        return false
    }
}
