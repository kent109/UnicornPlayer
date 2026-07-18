package com.unicorn.player.util

import com.github.houbb.opencc4j.util.ZhConverterUtil

/**
 * 将字符串转为简体中文后，再把"妳"统一替换为"你"。
 *
 * 作为 String 扩展挂在 util 包下，调用形式：`text.toSimpleCustom()`，
 * 用于替代散落的 `ZhConverterUtil.toSimple(...)`，并强制附加"妳→你"后处理，
 * 确保歌词中不出现"妳"字。
 */
fun String.toSimpleCustom(): String =
	ZhConverterUtil.toSimple(this).replace("妳", "你")
