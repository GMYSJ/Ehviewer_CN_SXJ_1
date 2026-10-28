package com.hippo.ehviewer.site

import java.util.Locale

/**
 * 从帖子正文里提取压缩包解压密码。
 *
 * 作者们的写法五花八门，这里用一组正则覆盖常见中英日写法，并按「更可能为真」的顺序返回**候选列表**，
 * 解压时逐个尝试，比只赌一条命中率高得多。提取不到时允许人工输入（见 SiteExtractor）。
 */
object PasswordExtractor {

    /**
     * 密码本体（ASCII 版）：可打印 ASCII，长度 2~64，不能以标点结尾。
     *
     * <p>末尾边界必须放得足够宽：很多作者把密码直接写进<b>附件文件名</b>里，
     * 例如 {@code 清宵-被染的双修（解压密码Ryx8）.zip}——密码后面紧跟的是全角右括号，
     * 只认空白/句号/逗号的话会整条漏掉（实测这种写法能占到一半以上）。
     * 因此边界取「空白 / 结尾 / 任意非 ASCII 可打印字符（含全角括号）/ 压缩包扩展名」。
     */
    private const val VALUE_ASCII =
        """([!-~]{2,64}?)[.,;:!?]?(?=\s|${'$'}|[^!-~]|\.(?:zip|rar|7z|cbz|tar|gz)|[<\u3002\uff0c])"""

    /**
     * 密码本体（宽版）：允许全角/假名，停止于空白、结尾、尖括号与中文标点。
     *
     * <p>只在 ASCII 版一个候选都没提到时才启用，用来兜住
     * {@code パスワード：テスト123}、{@code 解压密码：测试123abc} 这类带非 ASCII 字符的密码。
     * 放宽会带来误报（把中文句子当密码），所以顺序上排在后面，且额外过一道散文过滤。
     */
    private const val VALUE_ANY = """([^\s]{2,64}?)(?=\s|${'$'}|[<\u3002\uff0c])"""

    /**
     * 密码关键词。注意第二个必须是 {@code pwd|pw}：
     * 交替是按顺序尝试的，写成 {@code pw|pwd} 时正则会先吃掉 {@code pw}，
     * 于是 {@code pwd=123456} 会被截成 {@code d=123456}（实测踩过）。
     */
    private val KEYWORDS: List<String> = listOf(
        // Password / Passcode / Pass phrase / Zip password / PASSワード
        """(?i)(?:zip|archive|rar|7z)?\s*pass(?:phrase|word|code|ワード)?\s*[:：=]?\s*""",
        // PW / PWD
        """(?i)\b(?:pwd|pw)\b\s*[:：=]?\s*""",
        // 解压密码 / 解圧密码 / 压缩包密码
        """解[压圧][缩縮]?密[码碼]\s*[:：=]?\s*""",
        // 提取码 / 密码（中文）
        """(?:提取码|提取碼|密[码碼])\s*[:：=]?\s*""",
        // パスワード
        """パスワード\s*[:：=]?\s*""",
    )

    /** 第一轮：只认 ASCII 密码，最准。 */
    private val PATTERNS_ASCII: List<Regex> = KEYWORDS.map { Regex(it + VALUE_ASCII) }

    /** 第二轮：允许全角/假名，仅在上一轮无收获时启用。 */
    private val PATTERNS_ANY: List<Regex> = KEYWORDS.map { Regex(it + VALUE_ANY) }

    /**
     * 明确表示“没有密码”的值，命中即丢弃。
     */
    private val NO_PASSWORD: Set<String> = setOf(
        "none", "no", "null", "nil", "n/a", "na", "-", "--", "xxx", "???",
        "无", "没有", "なし", "不要", "不需要", "無",
    )

    /**
     * 明显是“让人自己去找”的散文词。宽版正则很容易把 {@code 密码：请见群公告}
     * 整句当成密码，这类词一出现就丢弃。
     */
    private val PROSE_WORDS: List<String> = listOf(
        "公告", "留言", "简介", "詳見", "详见", "请见", "見下", "见下",
        "如下", "群内", "群里", "私信",
    )

    /** 候选值两端要剥掉的包裹字符（含冒号，宽版可能把分隔符一起吞进来）。 */
    private const val TRIM_CHARS = "\"'“”。，、,.:：="

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
            val before = result.size
            collect(plain, PATTERNS_ASCII, result)
            // 这一条文本用精确规则已经提到东西了，就不要再上宽规则制造噪音
            if (result.size == before) {
                collect(plain, PATTERNS_ANY, result)
            }
        }
        return result.toList()
    }

    private fun collect(plain: String, patterns: List<Regex>, into: MutableSet<String>) {
        for (pattern in patterns) {
            for (match in pattern.findAll(plain)) {
                val candidate = (match.groupValues.getOrNull(1) ?: "")
                    .trim()
                    .trim(*TRIM_CHARS.toCharArray())
                if (isPlausible(candidate)) {
                    into.add(candidate)
                }
            }
        }
    }

    /**
     * 最常见的用法：只要一个密码。
     */
    fun firstPassword(vararg texts: String?): String? = extract(*texts).firstOrNull()

    private fun isPlausible(candidate: String): Boolean {
        if (candidate.length < 2) return false
        if (candidate.length > 64) return false
        // 纯标点/装饰符不是密码
        if (candidate.none { it.isLetterOrDigit() }) return false
        val lower = candidate.lowercase(Locale.getDefault())
        if (NO_PASSWORD.contains(lower)) return false
        if (PROSE_WORDS.any { candidate.contains(it) }) return false
        // 全是同一种字符且很长，通常是装饰线而不是密码
        if (candidate.length > 8 && candidate.toSet().size <= 2) return false
        return true
    }
}
