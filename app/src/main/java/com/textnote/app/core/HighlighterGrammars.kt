package com.textnote.app.core

/**
 * 四条语法分支的**逐行扫描器**：代码（[scanCodeLine]）、标记语言（[scanMarkupLine]）、
 * Markdown（[scanMarkdownLine]）、补丁（[scanDiffLine]）。
 *
 * 彼此之间是「整套替换」而不是打补丁——标记语言与 Markdown 的行结构和代码完全不同，
 * 混在一个循环里只会互相干扰（推导见 [Highlighter] 的文件注释）。
 *
 * 共用件（引号串 / 数字 / 标识符 / 状态常量）在同一个包里：[HighlighterPrimitives]。
 */

internal fun scanCodeLine(
    text: String,
    start: Int,
    end: Int,
    syntax: Syntax,
    stateIn: Int,
    out: MutableList<HighlightToken>,
): Int {
    var i = start
    var state = stateIn
    while (i < end) {
        // ---- 多行构造的续行部分 ----
        val block = syntax.blockComment
        if (state == ST_BLOCK_COMMENT && block != null) {
            val closeIdx = indexOfWithin(text, block.second, i, end)
            if (closeIdx < 0) {
                out += HighlightToken(i, end, TokenKind.COMMENT)
                return ST_BLOCK_COMMENT
            }
            val stop = minOf(end, closeIdx + block.second.length)
            out += HighlightToken(i, stop, TokenKind.COMMENT)
            i = stop
            state = ST_NORMAL
            continue
        }
        if (state >= ST_VERBATIM) {
            val delim = verbatimDelimiter(syntax, state)
            if (delim == null) {
                state = ST_NORMAL
            } else {
                val closeIdx = indexOfWithin(text, delim, i, end)
                if (closeIdx < 0) {
                    addVerbatimTokens(text, i, end, syntax, out)
                    return state
                }
                val stop = minOf(end, closeIdx + delim.length)
                addVerbatimTokens(text, i, stop, syntax, out)
                i = stop
                state = ST_NORMAL
                continue
            }
        }

        // ---- 普通代码 ----
        val c = text[i]
        val line = syntax.lineComment
        // **块注释必须排在行注释之前**：Lua 的行注释 `--` 是块注释 `--[[` 的前缀，
        // 反过来先试行注释的话，`--[[ ... ]]` 永远只会被标成「这一行是注释」，
        // 后面几行照常着色。两个检查互斥（`/*` 与 `//` 没有前缀关系），所以换序安全。
        if (block != null && regionStartsWith(text, block.first, i, end)) {
            val closeIdx = indexOfWithin(text, block.second, i + block.first.length, end)
            if (closeIdx < 0) {
                out += HighlightToken(i, end, TokenKind.COMMENT)
                return ST_BLOCK_COMMENT
            }
            val stop = minOf(end, closeIdx + block.second.length)
            out += HighlightToken(i, stop, TokenKind.COMMENT)
            i = stop
            continue
        }
        if (line != null && regionStartsWith(text, line, i, end)) {
            out += HighlightToken(i, end, TokenKind.COMMENT)
            return ST_NORMAL
        }
        val verbatim = verbatimOpenAt(syntax, text, i, end)
        if (verbatim != null) {
            val closeIdx = indexOfWithin(text, verbatim, i + verbatim.length, end)
            if (closeIdx < 0) {
                addVerbatimTokens(text, i, end, syntax, out)
                return verbatimState(syntax, verbatim)
            }
            val stop = minOf(end, closeIdx + verbatim.length)
            addVerbatimTokens(text, i, stop, syntax, out)
            i = stop
            continue
        }
        if (c in syntax.stringDelims) {
            i = scanQuoted(text, i, end, c, syntax, out)
            continue
        }
        if (syntax.charDelim != null && c == syntax.charDelim) {
            i = scanQuoted(text, i, end, syntax.charDelim!!, syntax, out)
            continue
        }
        if (c.isDigit()) {
            i = scanNumber(text, i, end, syntax, out)
            continue
        }
        if (isIdentStart(c, syntax)) {
            i = scanIdent(text, i, end, syntax, out)
            continue
        }
        i++
    }
    return ST_NORMAL
}

internal fun scanMarkupLine(
    text: String,
    start: Int,
    end: Int,
    syntax: Syntax,
    stateIn: Int,
    out: MutableList<HighlightToken>,
): Int {
    var i = start
    // 标记语言的注释是 <!-- -->，与通用块注释不同，走同一套 machinery 但换界定符
    val comment = syntax.blockComment ?: ("<!--" to "-->")
    if (stateIn == ST_BLOCK_COMMENT) {
        val closeIdx = indexOfWithin(text, comment.second, i, end)
        if (closeIdx < 0) {
            out += HighlightToken(i, end, TokenKind.COMMENT)
            return ST_BLOCK_COMMENT
        }
        val stop = minOf(end, closeIdx + comment.second.length)
        out += HighlightToken(i, stop, TokenKind.COMMENT)
        i = stop
    }
    while (i < end) {
        if (regionStartsWith(text, comment.first, i, end)) {
            val closeIdx = indexOfWithin(text, comment.second, i + comment.first.length, end)
            if (closeIdx < 0) {
                out += HighlightToken(i, end, TokenKind.COMMENT)
                return ST_BLOCK_COMMENT
            }
            val stop = minOf(end, closeIdx + comment.second.length)
            out += HighlightToken(i, stop, TokenKind.COMMENT)
            i = stop
            continue
        }
        if (text[i] != '<') {
            i++
            continue
        }
        // <!DOCTYPE>、<!-- --> 之类：<! 开头的整句按标点处理，够用
        if (i + 1 < end && text[i + 1] == '!') {
            out += HighlightToken(i, minOf(end, i + 2), TokenKind.PUNCTUATION)
            i += 2
            continue
        }
        out += HighlightToken(i, i + 1, TokenKind.PUNCTUATION)
        i++
        if (i < end && text[i] == '/') {
            out += HighlightToken(i, i + 1, TokenKind.PUNCTUATION)
            i++
        }
        // 元素名
        val nameStart = i
        while (i < end && (text[i].isLetterOrDigit() || text[i] in "-_:.·")) i++
        if (i > nameStart) out += HighlightToken(nameStart, i, TokenKind.TAG)
        // 属性区直到 '>'
        while (i < end && text[i] != '>') {
            val c = text[i]
            if (c.isWhitespace()) {
                i++
                continue
            }
            if (c in syntax.stringDelims) {
                i = scanQuoted(text, i, end, c, syntax, out)
                continue
            }
            if (isIdentStart(c, syntax)) {
                val attrStart = i
                while (i < end && isIdentPart(text[i], syntax)) i++
                out += HighlightToken(attrStart, i, TokenKind.ATTRIBUTE)
                continue
            }
            i++
        }
        if (i < end) {
            out += HighlightToken(i, i + 1, TokenKind.PUNCTUATION)
            i++
        }
    }
    return ST_NORMAL
}

// ==================== Markdown ====================

/**
 * Markdown 的逐行扫描器。
 *
 * `syntax` 在这里**一次都没被读**：Markdown 的构造（`#` 标题、``` 围栏、`*` 强调）全是固定记号，
 * 没有可配置项。留着这个形参是为了让 [Highlighter.scanOneLine] 保持四路统一分发——
 * 去掉它，分发处就得为每个分支单写一套实参，而三条分支里只有这一条不需要它。
 *
 * ⚠️ 那个 `@Suppress` 不只是给编译器看的，`tools/dead_code.py` 也靠它区分
 * 「刻意不读」和「漏删」——删掉它会立刻变成一处扫描告警。
 */
@Suppress("UNUSED_PARAMETER")
internal fun scanMarkdownLine(
    text: String,
    start: Int,
    end: Int,
    syntax: Syntax,
    stateIn: Int,
    out: MutableList<HighlightToken>,
): Int {
    var i = start
    var state = if (stateIn in MD_STATES) stateIn else ST_NORMAL

    // ``` 围栏：整行按代码块处理
    if (regionStartsWith(text, "```", i, end)) {
        out += HighlightToken(i, end, TokenKind.CODE)
        // 语言标记（```kotlin）跟在后面，保持 CODE 色即可，不额外拆
        return if (state == ST_MD_FENCE) ST_NORMAL else ST_MD_FENCE
    }
    if (state == ST_MD_FENCE) {
        out += HighlightToken(i, end, TokenKind.CODE)
        return ST_MD_FENCE
    }

    // ATX 标题：# 号本身用标点色，正文用标题色
    var hashes = 0
    while (i + hashes < end && text[i + hashes] == '#') hashes++
    if (hashes in 1..6 && (i + hashes >= end || text[i + hashes].isWhitespace())) {
        out += HighlightToken(i, i + hashes, TokenKind.PUNCTUATION)
        out += HighlightToken(i + hashes, end, TokenKind.HEADING)
        return ST_NORMAL
    }

    if (i < end && text[i] == '>') {
        out += HighlightToken(i, i + 1, TokenKind.QUOTE)
        i++
    } else if (isListMarkerAt(text, i, end)) {
        val markerEnd = listMarkerEnd(text, i, end)
        out += HighlightToken(i, markerEnd, TokenKind.PUNCTUATION)
        i = markerEnd
    }

    // 行内：代码 span、强调、链接 URL
    while (i < end) {
        when (text[i]) {
            '`' -> {
                val close = indexOfWithin(text, "`", i + 1, end)
                val stop = if (close < 0) end else minOf(end, close + 1)
                out += HighlightToken(i, stop, TokenKind.CODE)
                i = stop
            }
            '*', '_' -> {
                val strong = i + 1 < end && text[i + 1] == text[i]
                val marker = if (strong) text.substring(i, i + 2) else text[i].toString()
                val close = indexOfWithin(text, marker, i + marker.length, end)
                if (close < 0) {
                    i++
                } else {
                    val stop = minOf(end, close + marker.length)
                    out += HighlightToken(i, stop, TokenKind.EMPHASIS)
                    i = stop
                }
            }
            '[' -> {
                val closeBracket = indexOfWithin(text, "](", i + 1, end)
                if (closeBracket >= 0) {
                    val closeParen = indexOfWithin(text, ")", closeBracket + 2, end)
                    if (closeParen >= 0) {
                        // 顺序很重要：token 必须按 start 递增，否则渲染时
                        // 后面那段的样式会被前面那段盖掉。
                        out += HighlightToken(i, i + 1, TokenKind.PUNCTUATION)
                        // 只给 URL 上色，链接文字保持正文色——否则整段变蓝，读起来更累
                        out += HighlightToken(closeBracket + 2, closeParen, TokenKind.LINK)
                        i = closeParen + 1
                        continue
                    }
                }
                i++
            }
            else -> i++
        }
    }
    return ST_NORMAL
}

internal fun isListMarkerAt(text: String, i: Int, end: Int): Boolean {
    if (i >= end) return false
    return when {
        text[i] == '-' || text[i] == '*' || text[i] == '+' -> i + 1 < end && text[i + 1].isWhitespace()
        text[i].isDigit() -> {
            var j = i
            while (j < end && text[j].isDigit()) j++
            j < end && (text[j] == '.' || text[j] == ')') && j + 1 < end && text[j + 1].isWhitespace()
        }
        else -> false
    }
}

internal fun listMarkerEnd(text: String, i: Int, end: Int): Int {
    if (i < end && text[i].isDigit()) {
        var j = i
        while (j < end && text[j].isDigit()) j++
        return minOf(end, j + 1)
    }
    return minOf(end, i + 1)
}

// ==================== 补丁（diff / patch） ====================

/**
 * 补丁文件里的元信息行前缀（`diff --git …`、`index abc..def`、`rename from …`…）。
 *
 * 一次前缀匹配就能定整行，所以用「以什么开头」而不是逐字符扫描——补丁的行语义本来就是
 * 行首决定的（见 [Syntax.diff]）。
 */
private val DIFF_META_PREFIXES = listOf(
    "diff ",
    "index ",
    "Index: ",
    "old mode ",
    "new mode ",
    "new file mode ",
    "deleted file mode ",
    "similarity index ",
    "dissimilarity index ",
    "rename from ",
    "rename to ",
    "copy from ",
    "copy to ",
    "Binary files ",
    "GIT binary patch",
    "*** ",
)

/**
 * 补丁（unified diff）的逐行扫描器。
 *
 * 整行一个 token，不做行内二次分词：一份补丁要的是「一眼看出增删块在哪」，
 * 把行内的关键字也染上色反而会把增删的边界淹掉。
 *
 * ## 判定顺序是这里唯一的难点
 *
 * `+` / `-` 既可能是「新增 / 删除」，也可能是块位置的 `@@ -12,7 +12,9 @@`，
 * 还可能是文件头的 `--- a/x` / `+++ b/x`。所以顺序必须是**长前缀优先**：
 * 先 `@@`，再 `---` / `+++`，最后才是单字符的 `+` / `-`。
 *
 * ## 一处已知近似
 *
 * `--- ` 既是「旧文件路径」也可能是「一行内容以 `--` 开头的删除行」（Lua / SQL / Haskell
 * 里的注释就是 `--`，删掉它就正好长这样）。逐行扫描看不到上下文，分不出两者，
 * 这里统一按文件头色处理：**颜色不会丢，只是用了元信息色而不是删除色**。
 * 一份补丁里这种行远少于真正的增删行，换一套跨行状态机不值得。
 *
 * 上下文行（以空格开头）**不着色**：它们就是没改动的原文，保持正文色才突出增删。
 *
 * ## 为什么有 `@Suppress("UNUSED_PARAMETER")`
 *
 * 补丁没有跨行构造（不像块注释 / 原样字符串），每行独立判定、返回状态永远是 NORMAL，
 * 所以 `syntax` 与 `stateIn` 在这条分支上都不被读。它们留着是为了与另外三条分支**共用签名**，
 * 让 [Highlighter.scanOneLine] 保持四路统一分发。这个标注同时是 `tools/dead_code.py`
 * 区分「刻意不读」与「漏删」的依据——要删它请连形参一起删。
 */
@Suppress("UNUSED_PARAMETER")
internal fun scanDiffLine(
    text: String,
    start: Int,
    end: Int,
    syntax: Syntax,
    stateIn: Int,
    out: MutableList<HighlightToken>,
): Int {
    // 补丁没有跨行构造（不像块注释 / 原样字符串），每行独立判定，返回状态永远是 NORMAL
    val kind = diffKindAt(text, start, end) ?: return ST_NORMAL
    out += HighlightToken(start, end, kind)
    return ST_NORMAL
}

/** 这一行在补丁里是什么角色；认不出（上下文行 / 空行）返回 null */
private fun diffKindAt(text: String, start: Int, end: Int): TokenKind? {
    val c = text[start]
    // 长前缀优先：`@@` `---` `+++` 都比单字符的 `+` `-` 更具体
    if (c == '@' && regionStartsWith(text, "@@", start, end)) return TokenKind.HUNK
    if (c == '+' && regionStartsWith(text, "+++", start, end)) return TokenKind.META
    if (c == '-' && regionStartsWith(text, "---", start, end)) return TokenKind.META
    // `\ No newline at end of file`：补丁自己的控制行，不是文件内容
    if (c == '\\') return TokenKind.META
    if (c == '+') return TokenKind.INSERTED
    if (c == '-') return TokenKind.DELETED
    for (prefix in DIFF_META_PREFIXES) {
        if (regionStartsWith(text, prefix, start, end)) return TokenKind.META
    }
    return null
}

// ==================== 辅助 ====================
