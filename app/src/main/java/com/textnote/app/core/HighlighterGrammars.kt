package com.textnote.app.core

/**
 * 三条语法分支的**逐行扫描器**：代码（[scanCodeLine]）、标记语言（[scanMarkupLine]）、
 * Markdown（[scanMarkdownLine]）。
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
        if (line != null && regionStartsWith(text, line, i, end)) {
            out += HighlightToken(i, end, TokenKind.COMMENT)
            return ST_NORMAL
        }
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

// ==================== 辅助 ====================
