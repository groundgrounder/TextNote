package com.textnote.app.core

/**
 * 一次查找的条件。
 *
 * `regex = false` 时，用户输入被当成**字面量**（用 [Regex.escape] 转义），
 * 这是查找框的默认值——大部分人按 `Ctrl+F` 是找一个字符串，不是写正则。
 * 真要用正则得显式点开开关。
 */
data class SearchQuery(
    val pattern: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
)

/**
 * 一处命中。
 *
 * [groups] 与 `MatchResult.groupValues` **同构**：下标就是组号，第 0 项是整段命中，
 * 之后依次是各捕获组。字面量查找（非正则）时为空。
 *
 * 这里刻意不再「去掉第 0 项」：以前存的是 `drop(1)`，于是组号与下标差一，
 * 替换模板里写 `$0` 会被算成 `groups[-1]` 而静默吞成空串（见 [expandReplacement]）。
 * 让下标等于组号，`$0` 就是顺手的事。
 */
data class SearchMatch(val start: Int, val end: Int, val groups: List<String> = emptyList())

data class SearchResult(
    val matches: List<SearchMatch>,
    /** 命中数超过上限被截断 */
    val truncated: Boolean,
    /** 正则写错了（比如括号不匹配） */
    val invalidPattern: Boolean,
) {
    companion object {
        val Empty = SearchResult(emptyList(), truncated = false, invalidPattern = false)
    }
}

/**
 * 查找与替换。纯 Kotlin、不依赖 Android，可以直接用 JVM 断言实测。
 *
 * ## 为什么零宽匹配被丢掉
 *
 * `a*` 这类正则能匹配出长度为 0 的结果。留着它们有两个后果：选中时界面上什么都看不到
 * （用户不知道到底选中了哪），而且「下一处」会一直停在同一个位置——因为下一处的判定是
 * `start >= 光标`，零宽匹配的 start 正好等于光标，永远命中它自己。
 * 丢掉之后行为可预测：只在这类匹配不产生任何可见效果的前提下。
 *
 * ## 为什么有命中上限
 *
 * 在一个 1MB 文件里搜 `e` 会有几万处命中。全部装进内存既没必要也拖慢界面，
 * 而「找到了多少处」这个问题的答案，超过一万之后对用户的意义是一样的。
 */
object SearchEngine {

    const val MAX_MATCHES = 10_000

    /** 编译查询条件。正则非法时返回 null（调用方据此提示，而不是抛异常） */
    fun compile(query: SearchQuery): Regex? {
        if (query.pattern.isEmpty()) return null
        val body = if (query.regex) query.pattern else Regex.escape(query.pattern)
        // 整词用 \b 包一层；外面再套非捕获组，避免用户自己写的 | 把 \b 的语义带偏
        val source = if (query.wholeWord) "\\b(?:$body)\\b" else body
        val options = if (query.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return runCatching { Regex(source, options) }.getOrNull()
    }

    fun findAll(text: String, query: SearchQuery, limit: Int = MAX_MATCHES): SearchResult {
        if (query.pattern.isEmpty()) return SearchResult.Empty
        val regex = compile(query) ?: return SearchResult(emptyList(), false, invalidPattern = true)
        val out = ArrayList<SearchMatch>(64)
        var truncated = false
        // 整个循环包起来：病态正则（比如嵌套量词）可能抛 StackOverflow，
        // 不能让一次查找把编辑器搞崩
        runCatching {
            var from = 0
            while (from <= text.length) {
                val match = regex.find(text, from) ?: break
                val range = match.range
                if (!range.isEmpty()) {
                    if (out.size >= limit) {
                        truncated = true
                        break
                    }
                    out.add(
                        SearchMatch(
                            start = range.first,
                            end = range.last + 1,
                            // 字面量查找的 groups 保持空：那种正则没有捕获组，`groupValues`
                            // 只有「整段」一项，而替换模板那条路只在正则模式下才展开
                            // （见 replaceAll / replaceCurrent）——为每一处命中建一个用不上的
                            // String 列表，在 10K 命中的上限下是白花的分配。
                            groups = if (query.regex) match.groupValues else emptyList(),
                        ),
                    )
                }
                from = if (range.isEmpty()) range.first + 1 else range.last + 1
            }
        }
        return SearchResult(out, truncated, invalidPattern = false)
    }

    /**
     * 从 [from] 往后的第一处命中（找不到就绕回第一处）。返回索引，无命中返回 -1。
     *
     * 判定用 `start >= from`：这样在「已选中某处」时 from 是该处的**结尾**，
     * 再按一次「下一处」才会前进到下一处，而不是原地不动。
     */
    fun nextIndex(matches: List<SearchMatch>, from: Int): Int {
        if (matches.isEmpty()) return -1
        var lo = 0
        var hi = matches.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (matches[mid].start < from) lo = mid + 1 else hi = mid
        }
        return if (lo < matches.size) lo else 0
    }

    /** 从 [from] 往前一处（-1 表示已选中第一处时绕回最后一处） */
    fun prevIndex(matches: List<SearchMatch>, from: Int): Int {
        if (matches.isEmpty()) return -1
        var lo = 0
        var hi = matches.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (matches[mid].start < from) lo = mid + 1 else hi = mid
        }
        return if (lo == 0) matches.size - 1 else lo - 1
    }

    /**
     * 与 `[from, to)` 有交叠的命中在 [matches] 里的下标区间（**左闭右闭**）。没有则返回 [IntRange.EMPTY]。
     *
     * 给只读渲染器用：它按行渲染，得知道「这一行上有哪些命中」，而命中列表是整篇文本的。
     * 要求 [matches] 按 start 递增且互不重叠（[findAll] 的产出满足）。
     *
     * 三个容易写错的地方：
     * - **跨行命中要回头看一处**。正则完全可能匹配到换行符，于是一处命中从第 3 行伸到第 5 行。
     *   只看 `start >= from` 会把它从后面的行里漏掉；而因为互不重叠且有序，
     *   「start 在行前、end 伸进行内」的命中**最多一处**——回头一步就够了，不用线性回扫。
     * - **不能拿 [nextIndex] 当二分下界**：它找不到时**绕回第一处**（那是「下一处」按钮的环绕
     *   语义），用在这里会让文件最后一行错误地标上开头的命中。
     * - **尾部是开区间**：`start < to` 而不是 `<=`，否则下一行行首的命中会被前一行多标一次。
     */
    fun matchIndicesInRange(matches: List<SearchMatch>, from: Int, to: Int): IntRange {
        if (matches.isEmpty() || from >= to) return IntRange.EMPTY
        var lo = 0
        var hi = matches.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (matches[mid].start < from) lo = mid + 1 else hi = mid
        }
        // 二分落在「第一处 start >= from」上，再把它前面那一处（可能跨行伸进来）收进来
        var first = lo
        if (first > 0 && matches[first - 1].end > from) first -= 1
        var last = first
        while (last < matches.size && matches[last].start < to) last++
        return if (last > first) first..(last - 1) else IntRange.EMPTY
    }

    /**
     * 展开替换模板里的组引用：`$0`（整段命中）/ `$1` / `${1}` / `$$`（字面 `$`）。
     *
     * [groups] 与 [SearchMatch.groups] 同一口径：下标即组号，`groups[0]` 是整段命中。
     * 越界的组号展开成空串（不报错、不留下 `$9` 这种字面量残渣）。
     *
     * **认不出来的引用一律原样保留**：`$x`、`${name}`、`${}`、未闭合的 `${1` 都按字面量
     * 留在结果里。这条比「越界组号给空串」更严，因为「越界」是数字明确说了要引用某组、
     * 只是没有那一组；而「认不出来」意味着**我们不知道用户想写什么**——替换是会写进文档的
     * 操作，猜错就是把他的字删了。
     *
     * 只认 `$`。不把 `\` 当转义符，是因为在非正则替换里用户写 `\n` 通常是想要字面的
     * 反斜杠 n，而不是换行——编辑器不该在这里自作聪明。
     *
     * 与 Java/VS Code 的一处已知差异：不带花括号时**只吃一位数字**，所以 `$12` 是
     * 「第 1 组 + 字面 2」而不是第 12 组。要引用两位以上的组号写 `${12}`。
     * 不做贪婪匹配是为了让「`$1` 后面正好跟个数字」这种模板不会被意外改写。
     */
    fun expandReplacement(template: String, groups: List<String>): String {
        if ('$' !in template) return template
        val sb = StringBuilder(template.length + 8)
        var i = 0
        while (i < template.length) {
            if (template[i] != '$' || i + 1 >= template.length) {
                sb.append(template[i])
                i++
                continue
            }
            when (val next = template[i + 1]) {
                '$' -> {
                    sb.append('$')
                    i += 2
                }
                '{' -> {
                    val close = template.indexOf('}', i + 2)
                    val index = if (close < 0) null else template.substring(i + 2, close).trim().toIntOrNull()
                    if (index == null) {
                        // 认不出来的 ${...}——组名不是数字（`${name}`）、空花括号、或干脆没闭合——
                        // 一律**按字面量留住**，与下面 `$x` 那一支同一口径。曾经这里会把整段
                        // `${name}` 悄悄删掉：那是在用户按下的「全部替换」里删掉他自己写的字，
                        // 而且同一个「未知组引用」的两种写法（`$x` 留着、`${x}` 删掉）给出相反结果，
                        // 出事之后无从解释。
                        sb.append('$')
                        i++
                    } else {
                        sb.append(groups.getOrNull(index).orEmpty())
                        i = close + 1
                    }
                }
                else -> {
                    val index = next.digitToIntOrNull()
                    if (index != null) {
                        sb.append(groups.getOrNull(index).orEmpty())
                        i += 2
                    } else {
                        sb.append('$')
                        i++
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * 替换单处。返回新文本与替换后光标应处的位置（插入内容的末尾），
     * 这样连按「替换」能一路往后走。
     */
    fun replaceMatch(text: String, match: SearchMatch, replacement: String): Pair<String, Int> {
        val newText = text.substring(0, match.start) + replacement + text.substring(match.end)
        return newText to (match.start + replacement.length)
    }

    /**
     * 替换全部。命中列表必须按 start 递增且不重叠（[findAll] 的产出满足这一点），
     * 于是可以一趟顺序拼接，不需要反复重算偏移。
     */
    fun replaceAll(
        text: String,
        matches: List<SearchMatch>,
        template: String,
        regex: Boolean,
    ): Pair<String, Int> {
        if (matches.isEmpty()) return text to 0
        val sb = StringBuilder(text.length)
        var cursor = 0
        matches.forEach { match ->
            sb.append(text, cursor, match.start)
            sb.append(if (regex) expandReplacement(template, match.groups) else template)
            cursor = match.end
        }
        sb.append(text, cursor, text.length)
        return sb.toString() to matches.size
    }
}
