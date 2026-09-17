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
 *
 * ## 本文件只留入口与分发
 *
 * 三个扫描件已按职责分到另外两个文件：共用件（引号串 / 数字 / 标识符 / 行首状态常量）在
 * [HighlighterPrimitives]，三条语法分支在 [HighlighterGrammars]。这里只剩
 * [highlight] / [lineStates] / [highlightLine] 与把它们分派到对应分支的 [scanOneLine]。
 */
object Highlighter {
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
internal fun scanOneLine(
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
}
