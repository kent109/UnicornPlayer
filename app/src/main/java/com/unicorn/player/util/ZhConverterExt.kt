package com.unicorn.player.util

import openccjava.OpenCC

/**
 * 将字符串转为简体中文（openccjava 实现，词典数据与官方 OpenCC 一致，
 * 词组级消歧由内置分词词典完成，如“想著→想着”“喝乾→喝干”且“著名/乾隆”不受影响），
 * 并做一项定制后处理：转换后把“妳”统一替换为“你”，确保歌词中不出现“妳”字
 * （“妳”不属于繁简映射，OpenCC 词典不处理，需显式替换）。
 *
 * 作为 String 扩展挂在 util 包下，调用形式：`text.toSimpleCustom()`。
 */
fun String.toSimpleCustom(): String {
    return OpenCC.convert(this, "t2s").replace("妳", "你")
}
