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
     *
     * 规则：
     * 1. 时长/大小限制（开关打开时生效）优先判断
     * 2. 目录规则：不扫描目录始终优先于指定扫描目录
     *    - 路径匹配不扫描目录 → 跳过
     *    - 指定扫描列表非空且路径不匹配任何指定目录 → 跳过
     *    - 其余情况 → 不跳过
     */
    fun shouldSkip(duration: Long, file: File): Boolean {
        // 规则 1：时长和大小限制
        if (skipShortAudio && duration < 30_000) return true
        if (skipSmallFiles && file.length() < 100 * 1024) return true
        val absolutePath = file.absolutePath

        // 规则 2：不扫描目录始终优先
        if (excludedDirs.any { absolutePath.startsWith(it) }) return true

        // 指定扫描列表非空时，仅扫描指定目录
        if (includedDirs.isNotEmpty() && includedDirs.none { absolutePath.startsWith(it) }) return true
        return false
    }
}
