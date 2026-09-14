package com.textnote.app.core

/**
 * 行索引：文本的「每行起始偏移量」表，行号、跳转、可视区高亮全都依赖它。
 *
 * 只认 `\n`——外部文件读入时已经过 [LineEndings.normalize] 归一化，编辑期间内部一律 LF。
 * 这是刻意的前提：如果 LineIndex 要同时理解 \r\n 和 \r，每个查询都要回头看前一个字符，
 * 边界情况成倍增加，而收益为零。
 *
 * 构造是 O(n) 全量扫描。1MB（约 2.5 万行）扫描一次在毫秒级，但每次按键都重建仍会累计成
 * 可感知的开销，所以调用方应当用 `derivedStateOf` 之类的方式缓存，避免一帧内重复构造。
 * 真正的增量更新（只调整改动点之后的行偏移）留到 M1，接口不变，不影响调用方。
 */
class LineIndex private constructor(
    private val starts: IntArray,
    private val textLength: Int,
) {

    /** 行数。空文本也算 1 行（一个可输入的光标位置）。 */
    val lineCount: Int get() = starts.size

    /** 第 [line] 行的起始偏移（越界会被夹到合法范围，避免 UI 侧大量判空） */
    fun lineStart(line: Int): Int = starts[line.coerceIn(0, lineCount - 1)]

    /**
     * 第 [line] 行内容的结束偏移，**不含**行尾的 `\n`。
     *
     * 下一行的起点必然是「本行 \n 之后」，所以减去 1 就是 \n 的位置，也就是内容结束处。
     */
    fun lineEnd(line: Int): Int {
        val l = line.coerceIn(0, lineCount - 1)
        return if (l + 1 < lineCount) starts[l + 1] - 1 else textLength
    }

    /** 第 [line] 行的结束偏移，**含**行尾的 `\n`（用于「整行替换」类操作） */
    fun lineEndWithBreak(line: Int): Int {
        val l = line.coerceIn(0, lineCount - 1)
        return if (l + 1 < lineCount) starts[l + 1] else textLength
    }

    /**
     * 偏移 [offset] 落在第几行（0 起）。二分查找，O(log n)。
     *
     * 找的是「起始偏移 <= offset 的最后一行」。offset 正好落在行尾 `\n` 上时应属于该行
     * （光标停在行尾仍算这一行），这个语义与 `starts[mid] <= offset` 一致。
     */
    fun lineOf(offset: Int): Int {
        if (offset <= 0) return 0
        if (offset >= textLength) return lineCount - 1
        var lo = 0
        var hi = lineCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * 偏移 [offset] 的列号（0 起）。
     *
     * 以 UTF-16 码元计数，和 [String.length] 同口径，光标位置可直接相减得到。
     * 代价是 emoji 等增补平面字符会占 2 列——CotEditor 按字形计数是 1。这里先接受偏差，
     * 等遇到实际抱怨再改成按 code point 计。
     */
    fun columnOf(offset: Int): Int = (offset - lineStart(lineOf(offset))).coerceAtLeast(0)

    /** 第 [line] 行的内容区间（不含行尾符），供高亮与搜索按行切分 */
    fun lineRange(line: Int): IntRange = lineStart(line) until lineEnd(line)

    companion object {

        /** 空文本的索引：1 行，起止都是 0 */
        val EMPTY = LineIndex(intArrayOf(0), 0)

        fun of(text: String): LineIndex {
            if (text.isEmpty()) return EMPTY
            // 先数一遍行数，再一次性填偏移：避免用可变列表反复扩容（1MB 下能省下多次数组复制）
            var count = 1
            for (i in text.indices) {
                if (text[i] == '\n') count++
            }
            val starts = IntArray(count)
            var line = 0
            for (i in text.indices) {
                if (text[i] == '\n') {
                    line++
                    starts[line] = i + 1
                }
            }
            // 文本以 \n 结尾时，末尾会多出一个起始偏移 == textLength 的空行。
            // 这是符合直觉的：「a\n」在编辑器里就是两行，第二行为空。
            return LineIndex(starts, text.length)
        }
    }
}
