package com.unicorn.player.util

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType

/**
 * 拼音工具类：将专辑/歌手名的首字符转换为其拼音首字母，用于分组。
 * - 英文字母 → 大写字母（a/A → A）
 * - 中文字符 → 拼音首字母（啊 → A）
 * - 其他字符（数字、符号等）→ "#"
 */
object PinyinUtil {

    private val outputFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    /**
     * 获取字符串首字符对应的分组字母。
     * @return 大写 A-Z，或 "#"（非中英文开头时）
     */
    fun getPinyinFirstLetter(name: String): String {
        if (name.isBlank()) return "#"
        val firstChar = name.trim().first()
        return when {
            firstChar in 'a'..'z' -> firstChar.uppercaseChar().toString()
            firstChar in 'A'..'Z' -> firstChar.toString()
            Character.UnicodeBlock.of(firstChar) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ->
                pinyinFirstLetter(firstChar)
            else -> "#"
        }
    }

    /**
     * 中文字符的拼音首字母（大写）；无法解析时返回 "#"。
     */
    private fun pinyinFirstLetter(chineseChar: Char): String {
        return try {
            val pinyinArray = PinyinHelper.toHanyuPinyinStringArray(chineseChar, outputFormat)
            val pinyin = pinyinArray?.firstOrNull()
            val letter = pinyin?.firstOrNull()
            if (letter != null && letter.isLetter()) letter.uppercaseChar().toString() else "#"
        } catch (e: Exception) {
            "#"
        }
    }

    fun getPinyinString(str: String): String {
        if (str.isBlank()) return str
        return try {
            PinyinHelper.toHanYuPinyinString(str, outputFormat, "", true)
        } catch (e: Exception) {
            str
        }
    }
}
