package com.textnote.app.core

/**
 * 词法着色扫描器。
 *
 * ## 为什么按行扫
 *
 * 不按行扫就得维护一个「光标处处于什么状态」的全局词法机，任何一处修改都要从头重算。
 * 按行扫则把状态压缩成**每行一个整数**（行首状态），而绝大多数语言的跨行构造只有
 * 「块注释」和「原样字符串」两种，所以这个整数很小。副作用是：想画出第 n 行，必须知道
 * 第 n-1 行的行尾状态，于是从第一行开始顺序扫——但这也让整个算法 O(n) 且无回溯，很好预测。
 *
 * ## 关于「行」
 *
 * [LineIndex.lineEnd] 不含 `\n`，所以换行符永远不在任何一段 token 里。这点很重要：
 * 若把 `\n` 算进 STRING/COMMENT，末尾多行字符串的最后一个 token 会吞掉换行。
 *
 * ## 语言差异的处理
 *
 * 三条专用分支（[scanCodeLine] / [scanMarkupLine] / [scanMarkdownLine]）+ 一堆开关。
 * 专用分支之间是「整套替换」而不是打补丁，因为标记语言和 Markdown 的行结构和代码完全不同，
 * 混在一个循环里只会互相干扰。
 */
object Highlighter {

    /** 行首状态：普通代码 */
    private const val ST_NORMAL = 0
    /** 行首状态：处在 `/* */` 里 */
    private const val ST_BLOCK_COMMENT = 1
    /** 行首状态：处在某个原样字符串里。真实状态 = ST_VERBATIM + 界定符下标，见 [verbatimState] */
    private const val ST_VERBATIM = 2
    /** Markdown 行首状态：处在 ``` 围栏代码块里 */
    private const val ST_MD_FENCE = 64

    private val MD_STATES = intArrayOf(ST_NORMAL, ST_MD_FENCE)

    /**
     * 给整份文本着色。
     *
     * 复杂度 O(n)。1MB 源码（约 2.5 万行）实测数十毫秒级——所以 UI 侧对超大文件
     * 有降级策略（见 EditorViewModel.highlight），这里不自己偷偷决定要不要着色。
     */
    fun highlight(text: String, lineIndex: LineIndex, syntax: Syntax): List<HighlightToken> {
        if (text.isEmpty() || syntax.id == Syntax.PLAIN.id) return emptyList()
        val out = ArrayList<HighlightToken>(text.length / 8 + 16)
        var state = ST_NORMAL
        for (line in 0 until lineIndex.lineCount) {
            val start = lineIndex.lineStart(line)
            val end = lineIndex.lineEnd(line)
            if (end <= start) continue // 空行：没有内容可标，行首状态原样带走
            state = scanOneLine(text, start, end, syntax, state, out)
        }
        return out
    }

    /**
     * 每一行**行首**的词法状态。
     *
     * 给「大文件只读浏览」用的：那份渲染器按行懒加载，一行一个 `Text`，只在行划过屏幕时才组合。
     * 想在那一刻给这行着色，就需要知道它行首处于什么状态（是否还在块注释 / 原样字符串里）——
     * 而从第一行重新扫过来是不可接受的。
     *
     * 于是先花一次 O(n) 扫描、只记状态不产出 token（每行 4 字节，2.5 万行约 100KB），
     * 之后任意一行都能 O(该行长度) 单独着色。整篇的 token 列表则完全不需要存在内存里——
     * 那才是大文件真正的内存开销来源。
     */
    fun lineStates(text: String, lineIndex: LineIndex, syntax: Syntax): IntArray {
        val count = lineIndex.lineCount
        val states = IntArray(count)
        if (text.isEmpty() || syntax.id == Syntax.PLAIN.id) return states
        var state = ST_NORMAL
        val sink = ArrayList<HighlightToken>(16)
        for (line in 0 until count) {
            states[line] = state
            val start = lineIndex.lineStart(line)
            val end = lineIndex.lineEnd(line)
            if (end <= start) continue
            sink.clear()
            state = scanOneLine(text, start, end, syntax, state, sink)
        }
        return states
    }

    /**
     * 给单行着色。[stateIn] 必须是这一行行首的状态（取自 [lineStates]）。
     *
     * 返回的偏移是**相对整篇文本**的绝对偏移，直接就能拿去切 `AnnotatedString`。
     *
     * [maxChars] 限制这一行**只看前多少个字符**，默认不限。只读渲染器在单行超长时会把显示
     * 截断（见 `ReadOnlyReader.MAX_LINE_CHARS`），那里必须把同一个上限传进来：不传的话
     * 「看不见的字符」照样要被分词、照样要分配 token，白花的代价与整行长度成正比——
     * 压缩过的 JSON / minified JS 单行可以有几 MB，那一行的分词能在主线程上跑几秒。
     */
    fun highlightLine(
        text: String,
        lineIndex: LineIndex,
        syntax: Syntax,
        line: Int,
        stateIn: Int,
        maxChars: Int = Int.MAX_VALUE,
    ): List<HighlightToken> {
        if (text.isEmpty() || syntax.id == Syntax.PLAIN.id) return emptyList()
        val start = lineIndex.lineStart(line)
        val end = lineIndex.lineEnd(line)
        if (end <= start) return emptyList()
        // ⚠️ 不能写成 `minOf(end, start + maxChars)`：maxChars 的默认值就是 Int.MAX_VALUE，
        // 只要 start > 0 就会整型溢出成负数，然后被下面的 `limit <= start` 判成「没有可扫的
        // 范围」而返回空列表——也就是「除第一行之外的所有行都不着色」。改成先比长度再相加。
        val lineLength = end - start
        val limit = when {
            maxChars <= 0 -> start
            maxChars >= lineLength -> end
            else -> start + maxChars
        }
        if (limit <= start) return emptyList()
        val out = ArrayList<HighlightToken>(8)
        scanOneLine(text, start, limit, syntax, stateIn, out)
        return out
    }

    /** 按语言选扫描分支。三种行结构没有共同点，所以这里是「整套替换」而不是打补丁 */
    private fun scanOneLine(
        text: String,
        start: Int,
        end: Int,
        syntax: Syntax,
        stateIn: Int,
        out: MutableList<HighlightToken>,
    ): Int = when {
        syntax.markdown -> scanMarkdownLine(text, start, end, syntax, stateIn, out)
        syntax.markup -> scanMarkupLine(text, start, end, syntax, stateIn, out)
        else -> scanCodeLine(text, start, end, syntax, stateIn, out)
    }

    // ==================== 代码 ====================

    private fun scanCodeLine(
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

    /**
     * 扫描一段被引号包起来的内容（字符串或字符字面量），返回扫描结束位置。
     *
     * **输出必须是不重叠的连续段**：不能先产出一大段 STRING 再往里叠 ESCAPE，
     * 那样渲染结果就依赖 AnnotatedString 对重叠 span 的处理顺序。这里改成按段切：
     * 普通内容 STRING，每个转义序列单独 ESCAPE，合起来正好铺满整段。
     */
    private fun scanQuoted(
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
    private fun addStringSegments(
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

    private fun addVerbatimTokens(
        text: String,
        start: Int,
        end: Int,
        syntax: Syntax,
        out: MutableList<HighlightToken>,
    ) = addStringSegments(text, start, end, syntax, out, TokenKind.STRING)

    private fun scanNumber(
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

    private fun scanIdent(
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

    private fun scanMarkupLine(
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

    private fun scanMarkdownLine(
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

    private fun isListMarkerAt(text: String, i: Int, end: Int): Boolean {
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

    private fun listMarkerEnd(text: String, i: Int, end: Int): Int {
        if (i < end && text[i].isDigit()) {
            var j = i
            while (j < end && text[j].isDigit()) j++
            return minOf(end, j + 1)
        }
        return minOf(end, i + 1)
    }

    // ==================== 辅助 ====================

    private fun verbatimOpenAt(syntax: Syntax, text: String, i: Int, end: Int): String? =
        syntax.verbatimStrings.firstOrNull { regionStartsWith(text, it, i, end) }

    private fun verbatimState(syntax: Syntax, delim: String): Int {
        val index = syntax.verbatimStrings.indexOf(delim)
        return ST_VERBATIM + index
    }

    private fun verbatimDelimiter(syntax: Syntax, state: Int): String? =
        syntax.verbatimStrings.getOrNull(state - ST_VERBATIM)

    private fun isIdentStart(c: Char, syntax: Syntax): Boolean =
        c.isLetter() || c == '_' || c == '$' || c in syntax.identStarts

    private fun isIdentPart(c: Char, syntax: Syntax): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '$' || c in syntax.identChars

    /** 在 `[from, end)` 范围内查找子串，越界不算命中 */
    private fun indexOfWithin(text: String, token: String, from: Int, end: Int): Int {
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

    private fun regionStartsWith(text: String, token: String, at: Int, end: Int): Boolean {
        if (at + token.length > end) return false
        for (k in token.indices) {
            if (text[at + k] != token[k]) return false
        }
        return true
    }

    private fun nextNonSpaceIs(text: String, from: Int, end: Int, target: Char): Boolean {
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
    private fun isAssignAt(text: String, from: Int, end: Int): Boolean {
        var i = from
        while (i < end && text[i].isWhitespace()) i++
        if (i >= end || text[i] != '=') return false
        return i + 1 >= end || text[i + 1] != '='
    }
}
