package com.textnote.app.core

import java.util.Locale

/**
 * 严格 JSON 的结构校验：**报出第一处语法错误的确切位置**，并在文件合法时顺带报出重复的键。
 *
 * ## 为什么不用 `org.json`
 *
 * Android 自带的 `org.json` 能告诉你「坏了」，但**给不出坏在哪**——它的异常只有一句消息、
 * 没有偏移。而诊断的价值一半在位置上：没有位置，用户还得自己一行行找。
 * 另外 `org.json` 在 `android.jar` 里，而 `core/` 禁止依赖 `android.*`（见项目约定），
 * 在这里手写一个递归下降的扫描器反而是唯一干净的选择。
 *
 * ## 只报第一处语法错误
 *
 * 一处坏了之后，后面的「错误」大多是它的连锁反应（少一个引号会让后面整段都不成立）。
 * 与其列十条互相矛盾的消息，不如只报第一条——修完再跑，自然会给出下一条。
 * 但**重复的键是另一回事**：它不影响结构，所以文件合法时照样报出来（严重级是警告）。
 *
 * ## 两处刻意的取舍
 *
 * - **空文件（或全空白）不报错**：新建一个 `.json` 立刻看到红字，是在惩罚用户还没开始写。
 * - **重复键只比原始写法**：`"a"` 与 `"\u0061"` 其实是同一个键，但这里只比较字符串原文。
 *   做真正的解码要引入第二套转义逻辑，而收益只覆盖极罕见的写法。
 */
internal object JsonLint {

    /**
     * 最大嵌套层数。超过就报 [DiagnosticKind.JSON_TOO_DEEP] 而不是继续递归——
     * 一份 `[[[[[…` 的文件会让递归下降直接栈溢出，而 `StackOverflowError` 是 Error、
     * 捕获起来并不可靠。宁可给一句诊断，也不能让打开文件变成崩溃。
     */
    const val MAX_DEPTH = 200

    /**
     * 分析一份 JSON。
     *
     * [lenient] 为 true 时按 `.jsonc` / `.json5` 的规则放宽：允许 `//` 与 `/* */` 注释、
     * 允许尾随逗号。**这个开关必须由调用方按文件类型给**——拿严格模式去量一份 .jsonc
     * 会满屏误报，而误报比漏报更伤信任。
     */
    fun analyze(text: String, lenient: Boolean): List<Diagnostic> =
        Parser(text, lenient).run()
}

private class Parser(private val text: String, private val lenient: Boolean) {

    private var pos = 0

    /** 第一处语法错误。非 null 时整个解析立刻停下（见 [JsonLint] 的说明） */
    private var error: Diagnostic? = null

    /** 每个对象一层，用来发现重复的键（原始写法比较） */
    private val keyStack = ArrayDeque<HashSet<String>>()

    private val duplicates = ArrayList<Diagnostic>()

    fun run(): List<Diagnostic> {
        skipTrivia()
        // 空文件 / 全是空白：不报错（见 JsonLint 的说明）
        if (pos >= text.length) return emptyList()
        parseValue(depth = 0)
        if (error != null) return listOf(error!!)
        skipTrivia()
        if (pos < text.length) {
            atToken(DiagnosticKind.JSON_EXPECTED_END)
            return listOf(error!!)
        }
        return duplicates
    }

    // ---------- 值 ----------

    private fun parseValue(depth: Int) {
        if (error != null) return
        if (depth > JsonLint.MAX_DEPTH) {
            fail(DiagnosticKind.JSON_TOO_DEEP, pos)
            return
        }
        skipTrivia()
        when (peek()) {
            null -> fail(DiagnosticKind.JSON_EXPECTED_VALUE, text.length - 1)
            '{' -> parseObject(depth, start = pos)
            '[' -> parseArray(depth, start = pos)
            '"' -> parseString()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> {
                val c = peek()!!
                if (c == '-' || c.isDigit()) parseNumber() else atToken(DiagnosticKind.JSON_EXPECTED_VALUE)
            }
        }
    }

    private fun literal(word: String) {
        // 只吃字母：`TRUE` 报出的 arg 是整个词，而不是一个孤零零的 `T`
        val end = tokenEnd(pos)
        if (!text.startsWith(word, pos) || end != pos + word.length) {
            atToken(DiagnosticKind.JSON_EXPECTED_VALUE)
            return
        }
        pos = end
    }

    private fun parseNumber() {
        val end = tokenEnd(pos)
        val token = text.substring(pos, end)
        if (!isJsonNumber(token)) {
            atToken(DiagnosticKind.JSON_EXPECTED_VALUE)
            return
        }
        pos = end
    }

    private fun parseString(): String? {
        val open = pos
        pos++ // 开引号
        val rawStart = pos
        while (pos < text.length) {
            val c = text[pos]
            when {
                c == '"' -> {
                    val raw = text.substring(rawStart, pos)
                    pos++
                    return raw
                }
                c == '\\' -> {
                    if (!skipEscape()) return null
                }
                // 字符串里直接换行：绝大多数情况是漏了闭引号，所以指向**开引号**更好找
                c == '\n' || c == '\r' -> {
                    fail(DiagnosticKind.JSON_UNCLOSED_STRING, open)
                    return null
                }
                c < ' ' -> {
                    // 其余裸控制字符：合法的写法是转义，arg 给「转义后的样子」才看得见
                    fail(DiagnosticKind.JSON_BAD_ESCAPE, pos, arg = escapeName(c))
                    return null
                }
                else -> pos++
            }
        }
        fail(DiagnosticKind.JSON_UNCLOSED_STRING, open)
        return null
    }

    /** 解析一个反斜杠转义；非法时报错并返回 false */
    private fun skipEscape(): Boolean {
        val at = pos
        if (at + 1 >= text.length) {
            fail(DiagnosticKind.JSON_UNCLOSED_STRING, at)
            return false
        }
        when (val e = text[at + 1]) {
            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
                pos += 2
                return true
            }
            'u' -> {
                if (at + 6 > text.length || !isHex4(at + 2)) {
                    // `\u12g4` 整段标出来比只标一个反斜杠看得见
                    fail(DiagnosticKind.JSON_BAD_ESCAPE, at, at + 6, arg = "\\u")
                    return false
                }
                pos += 6
                return true
            }
            else -> {
                fail(DiagnosticKind.JSON_BAD_ESCAPE, at, at + 2, arg = "\\$e")
                return false
            }
        }
    }

    // ---------- 容器 ----------

    private fun parseObject(depth: Int, start: Int) {
        pos++ // '{'
        val keys = HashSet<String>()
        keyStack.addLast(keys)
        skipTrivia()
        if (peek() == '}') {
            pos++
            keyStack.removeLast()
            return
        }
        while (true) {
            skipTrivia()
            if (peek() != '"') {
                // 未加引号的键、注释、以及各种「这里本来该有个键」的情况都落到这条
                if (peek() == null) {
                    fail(DiagnosticKind.JSON_UNCLOSED_BRACKET, start, arg = "{")
                } else {
                    atToken(DiagnosticKind.JSON_EXPECTED_VALUE)
                }
                return
            }
            val keyAt = pos
            val key = parseString() ?: return
            if (!keys.add(key)) {
                duplicates += Diagnostic(
                    kind = DiagnosticKind.JSON_DUPLICATE_KEY,
                    severity = DiagnosticSeverity.WARNING,
                    start = keyAt,
                    end = pos,
                    arg = key,
                )
            }
            skipTrivia()
            if (peek() != ':') {
                if (peek() == null) {
                    fail(DiagnosticKind.JSON_UNCLOSED_BRACKET, start, arg = "{")
                } else {
                    // 标在「本来该是冒号」的那个东西上（整个 token），不是一个字符
                    fail(DiagnosticKind.JSON_EXPECTED_COLON, pos, valueSpanEnd(pos))
                }
                return
            }
            pos++ // ':'
            parseValue(depth + 1)
            if (error != null) return
            skipTrivia()
            when (peek()) {
                ',' -> {
                    pos++
                    skipTrivia()
                    if (peek() == '}') {
                        // 宽松模式（.jsonc / .json5）允许尾随逗号，严格模式才报，
                        // 且指向那个逗号本身
                        if (lenient) {
                            pos++
                            keyStack.removeLast()
                            return
                        }
                        fail(DiagnosticKind.JSON_TRAILING_COMMA, pos - 1)
                        return
                    }
                }
                '}' -> {
                    pos++
                    keyStack.removeLast()
                    return
                }
                null -> {
                    fail(DiagnosticKind.JSON_UNCLOSED_BRACKET, start, arg = "{")
                    return
                }
                else -> {
                    // 缺逗号：标在**下一个**元素上（就是没被逗号隔开的那一个）
                    fail(DiagnosticKind.JSON_EXPECTED_COMMA, pos, valueSpanEnd(pos))
                    return
                }
            }
        }
    }

    private fun parseArray(depth: Int, start: Int) {
        pos++ // '['
        skipTrivia()
        if (peek() == ']') {
            pos++
            return
        }
        while (true) {
            parseValue(depth + 1)
            if (error != null) return
            skipTrivia()
            when (peek()) {
                ',' -> {
                    pos++
                    skipTrivia()
                    if (peek() == ']') {
                        // 与对象那条同理：宽松模式放过尾随逗号
                        if (lenient) {
                            pos++
                            return
                        }
                        fail(DiagnosticKind.JSON_TRAILING_COMMA, pos - 1)
                        return
                    }
                }
                ']' -> {
                    pos++
                    return
                }
                null -> {
                    fail(DiagnosticKind.JSON_UNCLOSED_BRACKET, start, arg = "[")
                    return
                }
                else -> {
                    fail(DiagnosticKind.JSON_EXPECTED_COMMA, pos, valueSpanEnd(pos))
                    return
                }
            }
        }
    }

    // ---------- 小工具 ----------

    private fun peek(): Char? = if (pos < text.length) text[pos] else null

    /** 空白；[lenient] 时连注释一起吃（`.jsonc` / `.json5` 允许注释） */
    private fun skipTrivia() {
        while (pos < text.length) {
            when (text[pos]) {
                ' ', '\t', '\n', '\r' -> pos++
                '/' -> {
                    if (lenient && pos + 1 < text.length && text[pos + 1] == '/') {
                        pos += 2
                        while (pos < text.length && text[pos] != '\n') pos++
                    } else if (lenient && pos + 1 < text.length && text[pos + 1] == '*') {
                        val close = text.indexOf("*/", pos + 2)
                        pos = if (close < 0) text.length else close + 2
                    } else {
                        return
                    }
                }
                else -> return
            }
        }
    }

    /** 从 [from] 起吃一段「词」：字母数字与 `_ . + -`。用于把 `TRUE`、`01`、`NaN` 整段报出来 */
    private fun tokenEnd(from: Int): Int {
        var i = from
        while (i < text.length && isTokenChar(text[i])) i++
        return if (i == from) from + 1 else i
    }

    private fun isTokenChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '.' || c == '+' || c == '-'

    /** `\u` 后面必须正好四位十六进制 */
    private fun isHex4(from: Int): Boolean {
        for (i in from until from + 4) {
            if (i >= text.length) return false
            val c = text[i]
            val ok = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            if (!ok) return false
        }
        return true
    }

    /** 报「这里是个不合法的值」，并把整段词作为参数带出去 */
    private fun atToken(kind: DiagnosticKind) {
        val end = tokenEnd(pos).coerceAtMost(text.length)
        if (error != null) return
        error = Diagnostic(
            kind = kind,
            severity = DiagnosticSeverity.ERROR,
            start = pos.coerceAtMost(text.length - 1),
            end = end,
            arg = text.substring(pos.coerceAtMost(text.length - 1), end),
        )
    }

    private fun fail(kind: DiagnosticKind, at: Int, arg: String? = null) {
        fail(kind, at, at + 1, arg)
    }

    /**
     * 报错，区间是 `[start, end)`（会夹进正文）。
     *
     * 区间宽度是有讲究的：**一个字符的标记太细，用户看不见**。「缺逗号」标在那个被顶掉位置的
     * token 上、「非法转义」标满 `\U` 这两个字符，才既准确又看得见。
     */
    private fun fail(kind: DiagnosticKind, start: Int, end: Int, arg: String? = null) {
        if (error != null) return
        val s = start.coerceIn(0, text.length - 1)
        val e = end.coerceIn(s + 1, text.length)
        error = Diagnostic(kind, DiagnosticSeverity.ERROR, s, e, arg)
    }

    /**
     * 从 [from] 起，「一个值」占到哪里：字符串要把整个引号串吃进去，其余按词吃。
     * 用于把诊断标在「错位的那个东西」上，而不是它前面的一个字符。
     */
    private fun valueSpanEnd(from: Int): Int {
        if (from < text.length && text[from] == '"') {
            var i = from + 1
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> i += 2
                    '"' -> return i + 1
                    else -> i++
                }
            }
            return text.length
        }
        return tokenEnd(from).coerceAtMost(text.length)
    }

    /** 把裸控制字符写成它该有的转义样子，否则消息里什么都看不见 */
    private fun escapeName(c: Char): String = when (c) {
        '\u0008' -> "\\b"
        '\u000C' -> "\\f"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        // ⚠️ `Locale.US` 是刻意的（与 [com.textnote.app.core.formatBytes] 同一个理由）：
        // `String.format` 默认按**系统 locale** 走，而 `%X` 的数字会被本地化——
        // 阿拉伯语等环境下的十六进制位会变成当地数字，这条消息就没人看得懂了。
        else -> String.format(Locale.US, "\\u%04X", c.code)
    }
}

/**
 * 严格 JSON 数字：`-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`
 *
 * 这几条最容易漏：前导零（`01`）、小数点两边必须都有数字（`.5` / `1.`）、
 * 指数后面必须跟数字（`1e`）。写宽了会出现「校验通过、别的程序却读不了」这种最糟的结果。
 */
internal fun isJsonNumber(s: String): Boolean {
    var i = 0
    if (i < s.length && s[i] == '-') i++
    if (i >= s.length) return false
    if (s[i] == '0') {
        i++
    } else if (s[i] in '1'..'9') {
        while (i < s.length && s[i].isDigit()) i++
    } else {
        return false
    }
    if (i < s.length && s[i] == '.') {
        i++
        if (i >= s.length || !s[i].isDigit()) return false
        while (i < s.length && s[i].isDigit()) i++
    }
    if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
        i++
        if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
        if (i >= s.length || !s[i].isDigit()) return false
        while (i < s.length && s[i].isDigit()) i++
    }
    return i == s.length
}
