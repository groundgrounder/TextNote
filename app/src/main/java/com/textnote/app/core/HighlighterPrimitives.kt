package com.textnote.app.core

/**
 * 词法原语：三条语法分支都要用的扫描件（引号串 / 数字 / 标识符 / 原样字符串状态机 / 小工具）
 * 以及**行首状态常量**。
 *
 * ## 为什么状态常量也在这里
 *
 * 行首状态是三条语法分支**共用的一门小语言**（每行一个整数）。放在本文件是为了让
 * [Highlighter] 与 [HighlighterGrammars] 引用它时**不用带限定名**——
 * 状态常量一旦散落，四处 `Highlighter.ST_xxx` 就会把这三份代码重新粘成一坨。
 *
 * 这些函数都是 `internal`：它们是实现细节，对外只有 [Highlighter] 那三个入口。
 */

/** 行首状态：普通代码 */
internal const val ST_NORMAL = 0

/** 行首状态：处在 `/* */` 里 */
internal const val ST_BLOCK_COMMENT = 1

/** 行首状态：处在某个原样字符串里。真实状态 = ST_VERBATIM + 界定符下标，见 [verbatimState] */
internal const val ST_VERBATIM = 2

/** Markdown 行首状态：处在 ``` 围栏代码块里 */
internal const val ST_MD_FENCE = 64

internal val MD_STATES = intArrayOf(ST_NORMAL, ST_MD_FENCE)

/**
 * 扫描一段被引号包起来的内容（字符串或字符字面量），返回扫描结束位置。
 *
 * **输出必须是不重叠的连续段**：不能先产出一大段 STRING 再往里叠 ESCAPE，
 * 那样渲染结果就依赖 AnnotatedString 对重叠 span 的处理顺序。这里改成按段切：
 * 普通内容 STRING，每个转义序列单独 ESCAPE，合起来正好铺满整段。
 */
internal fun scanQuoted(
    text: String,
    start: Int,
    end: Int,
    quote: Char,
    syntax: Syntax,
    out: MutableList<HighlightToken>,
): Int {
    var i = start + 1
    while (i < end) {
        if (syntax.escape != null && text[i] == syntax.escape && i + 1 < end) {
            i += 2
            continue
        }
        if (text[i] == quote) {
            i++
            break
        }
        i++
    }
    val stop = minOf(i, end)
    // 未闭合时延伸到行尾：绝大多数语言不允许字符串里出现裸换行，
    // 真出现了说明是没写全，按「到行尾为止」处理比吞掉后面的内容安全。
    val isKey = syntax.jsonKeys && nextNonSpaceIs(text, stop, end, ':')
    addStringSegments(text, start, stop, syntax, out, if (isKey) TokenKind.PROPERTY else TokenKind.STRING)
    return i
}

/** 把 `[from, to)` 按「普通内容 + 转义序列」切成互不重叠的连续段 */
internal fun addStringSegments(
    text: String,
    from: Int,
    to: Int,
    syntax: Syntax,
    out: MutableList<HighlightToken>,
    kind: TokenKind,
) {
    if (to <= from) return
    val escape = syntax.escape
    if (escape == null) {
        out += HighlightToken(from, to, kind)
        return
    }
    var segStart = from
    var i = from
    while (i < to) {
        if (text[i] == escape && i + 1 < to) {
            if (i > segStart) out += HighlightToken(segStart, i, kind)
            out += HighlightToken(i, i + 2, TokenKind.ESCAPE)
            i += 2
            segStart = i
        } else {
            i++
        }
    }
    if (segStart < to) out += HighlightToken(segStart, to, kind)
}

internal fun addVerbatimTokens(
    text: String,
    start: Int,
    end: Int,
    syntax: Syntax,
    out: MutableList<HighlightToken>,
) = addStringSegments(text, start, end, syntax, out, TokenKind.STRING)

internal fun scanNumber(
    text: String,
    start: Int,
    end: Int,
    syntax: Syntax,
    out: MutableList<HighlightToken>,
): Int {
    var i = start
    if (text[i] == '0' && i + 1 < end && (text[i + 1] == 'x' || text[i + 1] == 'X' ||
            text[i + 1] == 'b' || text[i + 1] == 'B')
    ) {
        i += 2
        while (i < end && (text[i].isDigit() || text[i] == '_' ||
            text[i] in 'a'..'f' || text[i] in 'A'..'F')
        ) i++
    } else {
        while (i < end && text[i].isDigit()) i++
        if (i < end && text[i] == '.' && i + 1 < end && text[i + 1].isDigit()) {
            i++
            while (i < end && text[i].isDigit()) i++
        }
        if (i < end && (text[i] == 'e' || text[i] == 'E')) {
            var j = i + 1
            if (j < end && (text[j] == '+' || text[j] == '-')) j++
            if (j < end && text[j].isDigit()) {
                i = j
                while (i < end && text[i].isDigit()) i++
            }
        }
    }
    while (i < end && text[i] in syntax.numberSuffixes) i++
    out += HighlightToken(start, i, TokenKind.NUMBER)
    return i
}

internal fun scanIdent(
    text: String,
    start: Int,
    end: Int,
    syntax: Syntax,
    out: MutableList<HighlightToken>,
): Int {
    var i = start + 1
    while (i < end && isIdentPart(text[i], syntax)) i++
    val raw = text.substring(start, i)
    val word = if (syntax.caseSensitive) raw else raw.lowercase()
    val kind = when {
        syntax.keywords.contains(word) -> TokenKind.KEYWORD
        syntax.types.contains(word) -> TokenKind.TYPE
        syntax.builtins.contains(word) -> TokenKind.BUILTIN
        i < end && text[i] == '(' -> TokenKind.FUNCTION
        // 「标识符 + 空格 + :」当作声明名（CSS 的 color: red、YAML 的 name: x）
        syntax.declProps && nextNonSpaceIs(text, i, end, ':') -> TokenKind.PROPERTY
        // 「标识符 + 空格 + =」当作键（INI / TOML 的 key = value）
        syntax.declAssign && isAssignAt(text, i, end) -> TokenKind.PROPERTY
        // 以 # / . / @ 等前缀开头的标识符——CSS 里这就是选择器或 at-规则，
        // 它们指代的都是别处的元素，用 TAG 色比留白更容易扫读
        word[0] in syntax.identStarts -> TokenKind.TAG
        else -> return i // 普通标识符不着色，省掉大量无意义的 span
    }
    out += HighlightToken(start, i, kind)
    return i
}

// ==================== 标记语言 ====================

internal fun verbatimOpenAt(syntax: Syntax, text: String, i: Int, end: Int): String? =
    syntax.verbatimStrings.firstOrNull { regionStartsWith(text, it, i, end) }

internal fun verbatimState(syntax: Syntax, delim: String): Int {
    val index = syntax.verbatimStrings.indexOf(delim)
    return ST_VERBATIM + index
}

internal fun verbatimDelimiter(syntax: Syntax, state: Int): String? =
    syntax.verbatimStrings.getOrNull(state - ST_VERBATIM)

internal fun isIdentStart(c: Char, syntax: Syntax): Boolean =
    c.isLetter() || c == '_' || c == '$' || c in syntax.identStarts

internal fun isIdentPart(c: Char, syntax: Syntax): Boolean =
    c.isLetterOrDigit() || c == '_' || c == '$' || c in syntax.identChars

/** 在 `[from, end)` 范围内查找子串，越界不算命中 */
internal fun indexOfWithin(text: String, token: String, from: Int, end: Int): Int {
    if (token.isEmpty() || from < 0) return -1
    var i = from
    val limit = end - token.length
    while (i <= limit) {
        var matched = true
        for (k in token.indices) {
            if (text[i + k] != token[k]) {
                matched = false
                break
            }
        }
        if (matched) return i
        i++
    }
    return -1
}

internal fun regionStartsWith(text: String, token: String, at: Int, end: Int): Boolean {
    if (at + token.length > end) return false
    for (k in token.indices) {
        if (text[at + k] != token[k]) return false
    }
    return true
}

internal fun nextNonSpaceIs(text: String, from: Int, end: Int, target: Char): Boolean {
    var i = from
    while (i < end && text[i].isWhitespace()) i++
    return i < end && text[i] == target
}

/**
 * 接下来的非空白字符是不是**单个** `=`。
 *
 * 排除 `==`：很多语言里 `a == b` 是比较，把 `a` 标成「键」是错的。
 * `!=` `<=` `>=` `+=` 同理都因为首字符不是 `=`（或后面还跟着 `=`）而落在外面。
 */
internal fun isAssignAt(text: String, from: Int, end: Int): Boolean {
    var i = from
    while (i < end && text[i].isWhitespace()) i++
    if (i >= end || text[i] != '=') return false
    return i + 1 >= end || text[i + 1] != '='
}
