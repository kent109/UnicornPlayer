package com.unicorn.player.equalizer

/**
 * 均衡器自定义配置的 JSON 数据模型（Gson 序列化）。
 *
 * 用于导入/导出用户自定义的均衡器配置，存储于
 * Documents/Unicorn/Equalizer/ 目录下的 .json 文件。
 *
 * 字段说明：
 * - [version]：版本号，便于后续兼容
 * - [bandLevels]：5 个频段的电平值（millibels，范围约 -1500 ~ +1500），
 *                  对应 [com.bullhead.equalizer.Settings.seekbarpos]
 * - [bassStrength]：低音强度（0 ~ 1000），对应 [com.bullhead.equalizer.Settings.bassStrength]
 * - [reverbPreset]：虚拟音效预设（0 ~ 6，0=NONE, 1=SMALLROOM, 2=MEDIUMROOM,
 *                     3=LARGEROOM, 4=MEDIUMHALL, 5=LARGEHALL, 6=PLATE），
 *                     对应 [com.bullhead.equalizer.Settings.reverbPreset]
 */
data class EqualizerConfig(
    val version: Int = CURRENT_VERSION,
    val bandLevels: List<Int>,
    val bassStrength: Int,
    val reverbPreset: Int
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}
