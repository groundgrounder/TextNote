package com.textnote.app.ui.editor

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.textnote.app.core.SearchEngine
import com.textnote.app.core.SearchMatch
import com.textnote.app.core.SearchQuery
import com.textnote.app.core.SearchResult

/**
 * 查找/替换的状态。从 [EditorViewModel] 里拆出来，是为了不让那个类变成一团：
 * 它只负责「条件 + 命中列表 + 当前是第几处」，改文本与移动光标由 ViewModel 执行。
 *
 * [textProvider] 是个 lambda 而不是直接传字符串，因为文本内容在 ViewModel 里是可变状态。
 * 在 derivedStateOf 里读它，命中列表就会随文本自动重算；不读则不会。
 */
class EditorSearch(private val textProvider: () -> String) {

    var visible by mutableStateOf(false)
        private set

    /**
     * 输入框里正在编辑的关键词。
     *
     * **它不直接参与搜索**——真正拿去搜的是 [committedQuery]。原因是一次实测事故：
     * 在 2MB 的只读文件里往查找框一次性打进 10 个字符，界面直接 ANR
     * （`Input dispatching timed out ... Waited 5000ms for KeyEvent`）。
     * 因为每个字符都会在主线程上对整篇文本重跑一次 `findAll`，10 次全文正则扫描排成一队，
     * 顶穿了 5 秒的输入分发期限。而单次扫描只要 0.1~0.2 秒，逐个字符打（每次之间系统有机会
     * 喘口气）就完全没事——**所以这不是"慢"，是"排队"**。
     *
     * 于是把「输入」和「搜索」拆开：输入框绑定 [query]，界面在停止输入一小段之后再
     * 调 [commitQuery] 把它提交给搜索。一次输入突发只搜一次。
     */
    var query by mutableStateOf("")
        private set

    /** 真正参与搜索的关键词。见 [query] 的说明 */
    private var committedQuery by mutableStateOf("")

    /** 输入停止多久之后才提交搜索。150ms 是打字停顿与人眼可察延迟之间的常规取值 */
    val debounceMillis: Long get() = SEARCH_DEBOUNCE_MS

    var replaceText by mutableStateOf("")
        private set

    var regexMode by mutableStateOf(false)
        private set

    var caseSensitive by mutableStateOf(false)
        private set

    var wholeWord by mutableStateOf(false)
        private set

    /**
     * 当前是第几处，-1 表示还没跳转过（此时界面只显示总数，不显示 x/y）。
     * 用 -1 而不是 0 作初值，是因为打开查找面板时不该自作主张把光标挪走。
     */
    var currentIndex by mutableIntStateOf(-1)
        private set

    /** 打开面板。若此刻有选区，用它作为初始关键词——这是编辑器里通行且好用的小动作 */
    fun open(initialQuery: String = "") {
        visible = true
        query = initialQuery
        committedQuery = initialQuery // 初始值直接提交，不必等 debounce
        currentIndex = -1
    }

    fun close() {
        visible = false
    }

    // 命名刻意避开 setQuery/setReplaceText：那正是 var 属性私有 setter 的 JVM 签名，
    // 同名函数会报 "Platform declaration clash"。
    fun updateQuery(value: String) {
        query = value
    }

    /** 把当前输入提交给搜索。由界面在输入停顿后调用（见 [query] 的说明） */
    fun commitQuery() {
        if (committedQuery == query) return
        committedQuery = query
        currentIndex = -1
    }

    fun updateReplaceText(value: String) {
        replaceText = value
    }

    fun toggleRegex() {
        regexMode = !regexMode
        currentIndex = -1
    }

    fun toggleCaseSensitive() {
        caseSensitive = !caseSensitive
        currentIndex = -1
    }

    fun toggleWholeWord() {
        wholeWord = !wholeWord
        currentIndex = -1
    }

    /** 命中列表。面板关着或关键词为空时返回空——省掉无谓的搜索 */
    val result: SearchResult by derivedStateOf {
        if (!visible || committedQuery.isEmpty()) {
            SearchResult.Empty
        } else {
            SearchEngine.findAll(
                textProvider(),
                SearchQuery(
                    pattern = committedQuery,
                    regex = regexMode,
                    caseSensitive = caseSensitive,
                    wholeWord = wholeWord,
                ),
            )
        }
    }

    val matches: List<SearchMatch> get() = result.matches

    /** 往下一处。返回该处区间并更新 [currentIndex]；无命中返回 null */
    fun findNext(fromOffset: Int): SearchMatch? {
        val index = SearchEngine.nextIndex(matches, fromOffset)
        if (index < 0) return null
        currentIndex = index
        return matches[index]
    }

    /** 往上一处 */
    fun findPrev(fromOffset: Int): SearchMatch? {
        val index = SearchEngine.prevIndex(matches, fromOffset)
        if (index < 0) return null
        currentIndex = index
        return matches[index]
    }

    /** 文本变化后调用：命中列表可能变短，把当前序号夹回合法范围 */
    fun clampIndex() {
        if (currentIndex >= matches.size) currentIndex = -1
    }

    /**
     * 正文**整份换过**之后调用（打开另一份文档、按磁盘版本重新加载）：回到「还没跳转过」。
     *
     * 与 [clampIndex] 的区别在语义，不在效果：那个是「同一份正文改了，序号可能越界，夹回来接着用」；
     * 这个正文已经换掉了，旧序号指向的位置根本不存在了。只夹不重置的话，新正文命中数比旧序号小时
     * 面板会显示「6 / 2」这种自相矛盾的数字，而在可编辑路径上「当前命中」的强调色会落到一处
     * 无关的命中上——两者都是在报一件不成立的事。
     */
    fun resetPosition() {
        currentIndex = -1
    }

    /**
     * 替换一处之后重定位当前序号：跳到第一个 `start >= [caret]` 的命中，没有则置 -1。
     *
     * 不能沿用旧序号。替换串比匹配串长、且自身还能被搜到时（`cat` → `catalog`），
     * 重算后的命中列表里旧序号指向的正是**刚插入的内容**，于是连按「替换」会不停替换
     * 同一处、文本越来越长，后面的命中永远轮不到。
     */
    fun reindexFrom(caret: Int) {
        currentIndex = matches.indexOfFirst { it.start >= caret }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
    }
}
