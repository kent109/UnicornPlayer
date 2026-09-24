package com.unicorn.player.util

import com.unicorn.player.UnicornPlayerApplication
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import java.util.concurrent.ConcurrentHashMap

/**
 * 拼音工具类：将歌曲/专辑/歌手名转换为拼音，用于排序与字母分组。
 * - 英文字母/数字/符号 → 原样保留
 * - 中文字符 → 无声调全拼（小写）
 *
 * 多音字读音不在代码中维护，全部配置在 assets/pinyin/polyphonic.txt：
 * 1. [words] 多音词（最长匹配优先，如"长远"→changyuan）；
 * 2. [chars] 单字常用读音（如单独出现的"长"→chang）；
 * 3. 都未命中时交给 pinyin4j 兜底。
 * pinyin4j 自身只会返回内置读音数组的第一个，常为生僻读音（长→zhang、乐→le 等）。
 */
object PinyinUtil {

    /** assets 中多音字配置的路径，文件格式见该文件头部注释 */
    private const val CONFIG_FILE = "pinyin/polyphonic.txt"

    private val outputFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    /** 单字拼音缓存：排序时同一汉字在大量歌名中反复出现，避免重复调用 pinyin4j */
    private val charPinyinCache = ConcurrentHashMap<Char, String>()

    /** 多音词词典：词语 → 全拼，从 assets 配置文件懒加载 */
    @Volatile
    private var wordDict: Map<String, String>? = null

    /** 单字常用读音表：单字 → 全拼，从 assets 配置文件懒加载 */
    @Volatile
    private var charDict: Map<Char, String>? = null

    /** 多音词词典中最长词语的长度，避免每次匹配都遍历全部 key */
    @Volatile
    private var wordMaxLen: Int = 0

    private val dictLock = Any()

    /**
     * 在应用启动时后台预加载多音字配置，避免首次排序时在主线程读 assets。
     */
    fun preload() {
        if (wordDict != null) return
        Thread({
            try {
                getDicts()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, "pinyin-dict-loader").start()
    }

    /**
     * 获取字符串首字符对应的分组字母。
     * @return 大写 A-Z，或 "#"（非中英文开头时）
     */
    fun getPinyinFirstLetter(name: String): String {
        if (name.isBlank()) return "#"
        getDicts()
        val trimmed = name.trim()
        val firstChar = trimmed.first()
        return when {
            firstChar in 'a'..'z' -> firstChar.uppercaseChar().toString()
            firstChar in 'A'..'Z' -> firstChar.toString()
            isCjk(firstChar) -> {
                // 先尝试匹配开头的多音词（如"长远"→chang→C），否则按单字取音
                val pinyin = matchWord(trimmed, 0)?.second ?: charPinyin(firstChar)
                val letter = pinyin.firstOrNull()
                if (letter != null && letter.isLetter()) {
                    letter.uppercaseChar().toString()
                } else {
                    "#"
                }
            }
            else -> "#"
        }
    }

    /**
     * 将字符串转换为无声调全拼：中文转拼音、非中文原样保留。
     * 多音字先经配置文件中的词语/单字表纠正，pinyin4j 仅作兜底。
     */
    fun getPinyinString(str: String): String {
        if (str.isBlank()) return str
        getDicts()
        val sb = StringBuilder(str.length * 5)
        var i = 0
        while (i < str.length) {
            val ch = str[i]
            if (isCjk(ch)) {
                val word = matchWord(str, i)
                if (word != null) {
                    sb.append(word.second)
                    i += word.first
                } else {
                    sb.append(charPinyin(ch))
                    i++
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * 获取词语词典与单字读音表（双重检查锁懒加载）；读取失败时返回空表，
     * 此时仍有 pinyin4j 兜底，不影响排序功能。
     */
    private fun getDicts() {
        wordDict?.let { return }
        synchronized(dictLock) {
            if (wordDict != null) return
            val (words, chars) = loadConfig()
            wordDict = words
            charDict = chars
            wordMaxLen = words.keys.maxOfOrNull { it.length } ?: 0
        }
    }

    /**
     * 从 assets/pinyin/polyphonic.txt 读取多音字配置。
     * [words] 段为多音词、[chars] 段为单字读音；每行 "汉字=拼音"，# 注释、空行忽略。
     */
    private fun loadConfig(): Pair<Map<String, String>, Map<Char, String>> {
        val words = HashMap<String, String>(320)
        val chars = HashMap<Char, String>(96)
        try {
            val app = UnicornPlayerApplication.getInstance() ?: return words to chars
            // 未出现段标记前默认按词语段处理，兼容旧格式
            var section = 'w'
            app.assets.open(CONFIG_FILE).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    if (line.startsWith("[") && line.endsWith("]")) {
                        section = line.substring(1, line.length - 1).trim().firstOrNull() ?: section
                        return@forEachLine
                    }
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@forEachLine
                    val key = line.substring(0, idx).trim()
                    val pinyin = line.substring(idx + 1).trim()
                    if (key.isEmpty() || pinyin.isEmpty()) return@forEachLine
                    if (section == 'c' && key.length == 1) {
                        chars[key.first()] = pinyin
                    } else if (key.length >= 2) {
                        words[key] = pinyin
                    } else {
                        chars[key.first()] = pinyin
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return words to chars
    }

    /**
     * 在 [start] 位置做最长词语匹配。
     * @return (词语长度, 词语全拼)，未命中返回 null
     */
    private fun matchWord(s: String, start: Int): Pair<Int, String>? {
        val dict = wordDict ?: return null
        if (dict.isEmpty() || wordMaxLen < 2) return null
        val maxLen = minOf(wordMaxLen, s.length - start)
        for (len in maxLen downTo 2) {
            val pinyin = dict[s.substring(start, start + len)]
            if (pinyin != null) return len to pinyin
        }
        return null
    }

    /**
     * 单个汉字的拼音：配置单字读音表 → pinyin4j 首个读音 → 原字符。
     */
    private fun charPinyin(ch: Char): String {
        charPinyinCache[ch]?.let { return it }
        val configured = charDict?.get(ch)
        val pinyin = configured ?: try {
            PinyinHelper.toHanyuPinyinStringArray(ch, outputFormat)?.firstOrNull()
        } catch (e: Exception) {
            null
        } ?: ch.toString()
        charPinyinCache[ch] = pinyin
        return pinyin
    }

    private fun isCjk(ch: Char): Boolean =
        Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
}
