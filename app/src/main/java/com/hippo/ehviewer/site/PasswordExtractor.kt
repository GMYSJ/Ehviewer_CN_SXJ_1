package com.hippo.ehviewer.site

import java.util.Locale

/**
 * 从帖子正文里提取压缩包解压密码。
 *
 * 作者们的写法五花八门，这里用一组正则覆盖常见中英日写法，并按「更可能为真」的顺序返回**候选列表**，
 * 解压时逐个尝试，比只赌一条命中率高得多。提取不到时允许人工输入（见 SiteExtractor）。
 */
object PasswordExtractor {

    /** 密码本体：可打印 ASCII，长度 2~64，不能以标点结尾。 */
    private const val VALUE = """([!-~]{2,64}?)[.,;:!?]?(?=\s|${'$'}|[<\u3002\uff0c])"""

    private val PATTERNS: List<Regex> = listOf(
        // Password / Passcode / Pass phrase / Zip password / Archive password
        Regex(
            """(?i)(?:zip|archive|rar|7z)?\s*pass(?:word|code|phrase)?\s*[:：=]?\s*$VALUE"""),
        // PW / PWD
        Regex("""(?i)\b(?:pw|pwd)\s*[:：=]?\s*$VALUE"""),
        // 解压密码 / 解圧密码 / 压缩包密码
        Regex("""解[压圧][缩縮]?密[码碼]\s*[:：=]?\s*$VALUE"""),
        // 提取码 / 密码（中文）
        Regex("""(?:提取码|提取碼|密[码碼])\s*[:：=]?\s*$VALUE"""),
        // パスワード / PASSワード
        Regex("""パスワード\s*[:：=]?\s*$VALUE"""),
    )

    /**
     * 明确表示“没有密码”的值，命中即丢弃。
     */
    private val NO_PASSWORD: Set<String> = setOf(
        "none", "no", "null", "nil", "n/a", "na", "-", "--", "xxx", "???",
        "无", "没有", "なし", "不要", "不需要", "無",
    )

    /**
     * 把 HTML 正文压成纯文本，避免把标签属性当成密码。
     */
    fun stripHtml(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var text = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(raw, "\n")
        text = Regex("""</p>""", RegexOption.IGNORE_CASE).replace(text, "\n")
        text = Regex("""<[^>]+>""").replace(text, "")
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&＃39;", "'")
    }

    /**
     * 提取候选密码，按可能性排序后去重。
     */
    fun extract(vararg texts: String?): List<String> {
        val result = LinkedHashSet<String>()
        for (text in texts) {
            val plain = if (text == null) "" else stripHtml(text)
            if (plain.isEmpty()) continue
            for (pattern in PATTERNS) {
                for (match in pattern.findAll(plain)) {
                    val candidate = (match.groupValues.getOrNull(1) ?: "").trim()
                        .trim('"', '\'', '“', '”', '。', '，', '、', ',', '.')
                    if (isPlausible(candidate)) {
                        result.add(candidate)
                    }
                }
            }
        }
        return result.toList()
    }

    /**
     * 最常见的用法：只要一个密码。
     */
    fun firstPassword(vararg texts: String?): String? = extract(*texts).firstOrNull()

    private fun isPlausible(candidate: String): Boolean {
        if (candidate.length < 2) return false
        if (candidate.length > 64) return false
        val lower = candidate.lowercase(Locale.getDefault())
        if (NO_PASSWORD.contains(lower)) return false
        // 全是同一种字符且很长，通常是装饰线而不是密码
        if (candidate.length > 8 && candidate.toSet().size <= 2) return false
        return true
    }
}
