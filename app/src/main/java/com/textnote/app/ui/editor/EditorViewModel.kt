package com.textnote.app.ui.editor

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textnote.app.core.Diagnostic
import com.textnote.app.core.DiagnosticEngine
import com.textnote.app.core.EditorLimits
import com.textnote.app.core.HighlightToken
import com.textnote.app.core.Highlighter
import com.textnote.app.core.LineEnding
import com.textnote.app.core.SearchEngine
import com.textnote.app.core.SearchMatch
import com.textnote.app.core.LineIndex
import com.textnote.app.core.Syntax
import com.textnote.app.core.SyntaxRegistry
import com.textnote.app.core.UndoOutcome
import com.textnote.app.core.UndoStack
import com.textnote.app.data.DocumentEncoding
import com.textnote.app.data.DocumentRepository
import com.textnote.app.data.Draft
import com.textnote.app.data.DraftStore
import com.textnote.app.data.FileState
import com.textnote.app.data.OpenResult
import com.textnote.app.data.SyntaxOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 编辑器状态。UI 只能通过这里的方法和字段交互，不直接碰文本、行索引或文件读写——
 * 这是「换编辑器内核时只动 ui/editor 一个包」的约定靠什么守住。
 *
 * 文本真源在 [field]（Compose 的 TextFieldValue，含文本与选区）。
 */
class EditorViewModel(
    private val repository: DocumentRepository,
    private val drafts: DraftStore,
    private val syntaxOverrides: SyntaxOverrides,
) : ViewModel() {

    /** 文本与选区 */
    // ---------- 状态：文档身份与正文 ----------

    var field by mutableStateOf(TextFieldValue(""))
        private set

    /** 打开时探测出的编码，写回时原样沿用 */
    var encoding by mutableStateOf(DocumentEncoding.UTF8)
        private set

    /** 打开时探测出的行尾，写回时原样沿用 */
    var lineEnding by mutableStateOf(LineEnding.LF)
        private set

    /** 文件里混用了多种行尾。此时无论按哪一种写回，都会改动其中一部分行，值得提示。 */
    var mixedEndings by mutableStateOf(false)
        private set

    var fileName by mutableStateOf("")
        private set

    /**
     * 此刻有没有一份「当前文档」——包括正在打开、以及打开失败的交代。
     *
     * 界面据此决定显示编辑器还是首页。**判据必须落在 ViewModel 自己的状态上**，
     * 不能只用 Activity 侧那个 `rememberSaveable` 的「进过编辑器」标志：进程被杀后重建时
     * 那个标志会被恢复，而 ViewModel 是新的（正文、Uri、标题全空），用户会被丢进一个
     * 标题为空、正文为空的编辑器。旋转屏幕时 ViewModel 还活着，两种判据都成立，不会误伤。
     *
     * [fileName] 是可观察状态，且成功与失败两条路都会设上它，所以拿它当「有没有文档」的
     * 主判据；另外四种状态各自对应一个「正在给用户交代」的界面。
     */
    val hasDocument: Boolean
        get() = fileName.isNotEmpty() || loading || unavailable || notText || tooLarge != null

    /**
     * **正文能不能显示**。四种「没有正文」的情况都收在这里：
     * 读不动（[tooLarge]）、读不到（[unavailable]）、不是文本（[notText]）、还在读（[loading]）。
     *
     * 单独抽出来是因为界面要在**四处**挡住「没有正文」时的功能（查找、保存、另存为、状态栏）。
     * 原先每个调用点各自列一遍那三个条件，加第四种状态时漏一处就会露出破绽——
     * 而破绽的形式是「状态栏报 0 字符 · 1 行」这种关于一份并不存在的文档的假信息。
     */
    val showsText: Boolean
        get() = !loading && !unavailable && !notText && tooLarge == null

    var writable by mutableStateOf(true)
        private set

    // ---------- 状态：加载 / 不可用 / 只读 / 过大 ----------

    var loading by mutableStateOf(false)
        private set

    /** 文档读不到（权限被撤 / 被移动删除）。与「空文件」是两回事，不能混。 */
    var unavailable by mutableStateOf(false)
        private set

    /**
     * 文件超出可编辑体积，但**可以看**。
     *
     * 非 null 时只渲染 [ReadOnlyReader]：它的代价与文件大小无关，所以「能看」是能做到的，
     * 「能编辑」不是——后者要等自绘内核，那是另一个量级的工程。
     */
    var readOnly by mutableStateOf(false)
        private set

    /**
     * 太大了，连读都不读（超过读取上限）。此时界面只解释原因，不给查看器。
     *
     * 与 [unavailable] 分开：一个是权限问题（可以重新授权），一个是能力边界（只能换工具），
     * 给用户的下一步动作完全不同。
     */
    var tooLarge by mutableStateOf<OpenResult.TooLarge?>(null)
        private set

    /**
     * 读得到，但内容不是文本（二进制）。与 [tooLarge] 同样是「能力边界」，但原因不同：
     * 那个是「太大」，这个是「用文本编辑器看它没有意义」。两者给用户的交代也不一样，
     * 所以是两个字段而不是一个带原因的错误。
     */
    var notText by mutableStateOf(false)
        private set

    /** 需要跳到的行（只读浏览用）。LazyColumn 能按下标直接跳，不必算 y 偏移 */
    var scrollToLine by mutableStateOf<Int?>(null)
        private set

    /**
     * 正文与磁盘上的版本是否已经不同。
     *
     * 用**比对**而不是「改过就置位」的标志位：后者在撤销回原样之后仍然挂着未保存标记，
     * 而那一瞬间用户看到的与文件里的逐字节相同。比对则让撤销自动把标记清掉。
     *
     * 基线与正文都得是可观察状态——只改基线而不触发重组的话，保存后标记不会消失。
     */
    // ⚠️ 必须写 `this.field`：取值器里裸写 `field` 会被 Kotlin 解析成**本属性的 backing
    // field** 而不是 ViewModel 的 field 属性，编译期报 "Property must be initialized" +
    // "Unresolved reference 'text'"，错误信息很有迷惑性。
    val dirty: Boolean get() = this.field.text != baselineText

    /**
     * 上次与磁盘同步时的正文（打开时的内容，或最近一次保存成功后的内容）。
     * 见 [dirty]。
     */
    private var baselineText by mutableStateOf("")

    /** 体积已大到输入会明显卡顿（但还没到拒绝打开的程度）。界面提示一句，免得被当成卡死。 */
    val slowDocument: Boolean
        get() = this.field.text.length > EditorLimits.SLOW_CHARS

    /** 可编辑的字符数上限。界面要把它显示给用户（「超过 20 万字符只能看」），所以公开出来。 */
    val editLimitChars: Int get() = EditorLimits.OPEN_CHARS

    /** 能读进内存的字节上限。超过它连只读都打不开，界面要显示这个数 */
    val readLimitBytes: Long get() = EditorLimits.READ_BYTES.toLong()

    /**
     * 上次编辑留下了未保存的草稿，且内容与文件当前内容不同。
     * 非 null 时界面要问用户恢复还是丢弃——自动套用等于替用户决定，猜错了就是数据丢失。
     */
    // ---------- 状态：草稿 / 语法 / 撤销按钮 / 外部改动 ----------

    var pendingDraft by mutableStateOf<Draft?>(null)
        private set

    /**
     * 当前生效的语法。手动指定过就用手动的，否则按扩展名自动识别。
     *
     * 两个来源必须分成两个状态（[manualSyntax] + [syntax]）而不是只留一个「当前语法」：
     * 只有后者的话，用户选了 Python 之后我们就再也说不清「这是他选的」还是「自动认出来的」，
     * 于是下次打开同一份文件、或者只是重算一次，都可能把他的选择覆盖回去。
     */
    var syntax by mutableStateOf(Syntax.PLAIN)
        private set

    /**
     * 当前语法是自动识别的结果，而不是用户手动指定的。状态栏据此上色。
     *
     * 必须是可观察状态：普通字段变化不会通知 Compose，颜色就永远停在初始值。
     */
    var syntaxAuto by mutableStateOf(true)
        private set

    /** 手动指定的语法。null = 交给自动识别 */
    private var manualSyntax: Syntax? = null

    /**
     * 因文件过大而未着色。状态栏要给个交代，否则用户会以为是高亮坏了。
     *
     * **只读文档不适用这条**：只读渲染器按行懒加载、逐行分词（`Highlighter.lineStates` +
     * `highlightLine`），代价与整篇体积无关——「太大就不着色」只约束可编辑路径的 [tokens]。
     * 少了 `!readOnly` 这一项，每一份只读文档（必然超过 `OPEN_CHARS`，也就必然超过
     * [HIGHLIGHT_LIMIT]）都会在状态栏顶着 `语法名*` 说「已停止着色」，而屏幕上明明有颜色：
     * 状态栏在报一件与事实相反的事。
     *
     * 注意 `this.field`：在取值器里裸写 `field` 会被 Kotlin 解析成该属性的**backing field**，
     * 而不是同名的 ViewModel 属性（`this@EditorViewModel.field`）—编译期报
     * "Property must be initialized" + "Unresolved reference 'text'"，错误信息很有迷惑性。
     */
    val highlightSuppressed: Boolean
        get() = !readOnly && syntax.id != Syntax.PLAIN.id && this.field.text.length > HIGHLIGHT_LIMIT

    /**
     * 当前文档的 Uri。
     *
     * 对外可读：界面要拿它登记「本窗口正在显示哪份文档」，多窗口去重靠这张表
     * （见 `MainActivity` 的 `OpenDocumentRegistry`）。写入仍然只由本类负责。
     */
    var currentUri: Uri? by mutableStateOf(null)
        private set
    private var draftJob: Job? = null

    /**
     * 「当前这一份文档」的代号。[open] 与 [closeDocument] 各让它自增一次，异步流程在每个
     * 挂起点之后用它确认「我还是当前那次打开」。
     *
     * 为什么必须有它：[open] 要落的状态是**分段**的——[readOnly] 一段、正文与 [currentUri]
     * 一段、语法覆盖一段——而段与段之间全是挂起点。取消旧协程**远远不够**（每段之间还有成串的
     * 非挂起赋值，取消检查可能刚好落在赋值之后）。连点两个文件、或「应用内点击 + 外部 intent」
     * 交错时，最终落地的组合可能来自两份不同的文档，最刺眼的一种是
     * 「正文来自小文件、[readOnly] 来自大文件」——用户看到一份几十 KB 却只能看不能改的文件，
     * 而且无从理解为什么。
     *
     * 只在主线程读写（写入点与 [stale] 的调用点都在协程的主调度器上），所以普通 Int 就够。
     * 不要把它改成可观察状态：它一变就要重组，而这个值只服务于「谁有权写状态」这一件事。
     */
    private var documentToken = 0

    /** 这次异步流程还代表当前文档吗。见 [documentToken] */
    private fun stale(token: Int): Boolean = token != documentToken

    /**
     * 撤销栈。它本身是纯 Kotlin（在 `core/`），VM 只负责喂正文与时间戳。
     *
     * 只读模式下不会有编辑回调，也就不会往这里记东西——大文件的正文从头到尾是同一份，
     * 没有可撤销的历史。
     */
    private val undoStack = UndoStack()

    /**
     * 有历史可撤。
     *
     * 必须是可观察状态而不能写成 `undoStack.canUndo` 的转发：撤销栈是普通对象，
     * 它内部那个 ArrayList 变化**不会通知 Compose**，按钮的禁用态就永远停在初始的 false。
     * 所以每次动过栈都要手动同步一次（见 [refreshUndoState]）。
     */
    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    /** 动过撤销栈之后调用，把它的状态搬进可观察字段 */
    private fun refreshUndoState() {
        canUndo = undoStack.canUndo
        canRedo = undoStack.canRedo
    }

    /**
     * 打开/保存时刻的文件状态，作为「有没有被外部改过」的基准。
     *
     * **只有只读的大文件会在检测时读它**（见 [checkExternalChange]）：那条路上不能为了确认而
     * 重读 4MB，只能拿元数据比。可编辑的文档一律用正文基准 [baselineText]——元数据本身
     * 不可信（会滞后、会报旧值），不足以充当判据。
     *
     * null 表示这个 provider 报不出体积与时间，检测做不了。此时**不提示**——
     * 检测不到不等于没变化，编一个「一切正常」出来比不检测更糟。
     */
    private var baseline: FileState? = null

    /**
     * 回到前台时发现原文件被别的应用改过。
     *
     * 只**发现**、不自动处理：内存里可能有未保存的改动，自动重载等于替用户丢掉内容。
     * 和草稿一样，得让用户自己选（见 [reloadFromDisk] / [keepLocalVersion]）。
     */
    var externalChange by mutableStateOf<ExternalChange?>(null)
        private set

    /**
     * 行索引。用 derivedStateOf 缓存：文本每变一次只重算一次，而不是每个读取它的
     * 组合各算一遍。
     */
    // ---------- 派生状态（正文一变就重算）与查找面板 ----------

    val lineIndex: LineIndex by derivedStateOf { LineIndex.of(field.text) }

    /**
     * 着色结果。
     *
     * 为什么不 debounce：debounce 会让正在输入的文字先显示成无色、停顿一拍后突然上色，
     * 那个跳变比多花几毫秒难看得多。这里改成同步算，代价由 [HIGHLIGHT_LIMIT] 兜住——
     * 超过上限直接不标色，而不是让输入掉帧。
     */
    val tokens: List<HighlightToken> by derivedStateOf {
        val text = field.text
        if (syntax.id == Syntax.PLAIN.id || text.length > HIGHLIGHT_LIMIT) {
            emptyList()
        } else {
            Highlighter.highlight(text, lineIndex, syntax)
        }
    }

    /**
     * 参与查找的正文快照。
     *
     * 与 [field] 之间隔一层延迟，而不是直接用它：命中列表依赖这段文本，正文每变一次
     * 就要把全文正则重跑一遍——20 万字符的文档上那是一次几十毫秒的扫描，
     * 正好叠在「Compose 重排全文」这条已经很贵的输入路径上。
     *
     * M5 修过一次同类的 ANR（那次是关键词输入把 N 次扫描排成一队），这里补的是另一条
     * 路径：**正文**变化。延迟值与关键词用同一个 150ms——超过它用户基本已经停手。
     */
    private var searchText by mutableStateOf("")

    private var searchTextJob: Job? = null

    /** 查找/替换的状态与命中列表。文本来源见 [searchText] 的说明。 */
    val search = EditorSearch { searchText }

    /**
     * 本地单文件分析的结论（目前只有 JSON 校验，见 [DiagnosticEngine]）。
     *
     * 它与着色有本质区别：着色是**每次编辑重算的派生结果**，而诊断是「上一次分析时的结论」，
     * 正文一变就过期。所以渲染前必须夹取区间（与命中高亮同一套做法），并且每次输入后
     * 隔 [ANALYSIS_DEBOUNCE_MS] 重跑一遍。
     *
     * 空列表的语义是「没问题」或「这份文件不做分析」——两者对界面是一回事。
     */
    var diagnostics by mutableStateOf<List<Diagnostic>>(emptyList())
        private set

    private var analysisJob: Job? = null

    /** 「跳到下一个问题」的游标。每轮分析重算后归零，否则会停在一个已经不存在的位置上。 */
    private var diagnosticCursor = 0

    /**
     * 需要滚进视野的字符偏移，由 [findNext] / [findPrev] / 替换操作设置。
     *
     * 为什么不直接靠选中：Compose 的 BasicTextField 只在**自己获得焦点**时才会把光标滚进
     * 视野，而查找时光标在查找框里，编辑器没有焦点，于是「下一处」选中了却看不见。
     * 所以这里发一个一次性的滚动请求，由界面拿到布局结果后自己滚。
     */
    var scrollToOffset by mutableStateOf<Int?>(null)
        private set

    // ---------- 行为：编辑、撤销、开关文档 ----------

    fun onFieldChange(value: TextFieldValue) {
        if (readOnly) return // 只读模式下不该有编辑回调，这是道防线而不是正常路径
        // 必须在 field 被覆盖之前记录：oldText 是这一次改动之前的正文
        undoStack.record(field.text, value.text, SystemClock.elapsedRealtime())
        refreshUndoState()
        field = value
        scheduleSearchReindex()
        search.clampIndex()
        scheduleAnalysis()
        scheduleDraftSave()
    }

    /** 输入后延迟同步查找用的正文。理由见 [searchText]。 */
    private fun scheduleSearchReindex() {
        searchTextJob?.cancel()
        searchTextJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            searchText = this@EditorViewModel.field.text
        }
    }

    /**
     * 安排一次本地分析。[immediate] 用于离散动作（打开文件、撤销、换语法）——
     * 那些时刻用户在等结果，防抖只会显得迟钝；连续输入才需要防抖。
     *
     * ⚠️ **凡是整份正文被换掉的地方都必须调它**，一处都不能漏。当前清单：
     * `open()`、`onFieldChange()`、`applyOutcome()`（撤销/重做）、`selectSyntax()`、
     * `replaceCurrent()`、`replaceAll()`、`restoreDraft()`。
     * 漏掉的后果是**静默的陈旧数据**：正文已经换了，界面上还标着上一份正文的问题
     * （渲染时的区间夹取只保证不崩，不保证内容是对的）。历史上同一个形状的坑出现过：
     * 三处漏了 `refreshUndoState()`，替换完撤销按钮一直是灰的。
     */
    private fun scheduleAnalysis(immediate: Boolean = false) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            if (!immediate) delay(ANALYSIS_DEBOUNCE_MS)
            runAnalysis()
        }
    }

    /**
     * 跑一次分析并落结果。
     *
     * 分析是 O(n) 纯计算，放 Default 上；但**必须把参与分析的那份文本一起快照**，
     * 回来时比对正文是否仍是那一份——分析比一次查找扫描贵，中间用户完全可能又敲了几个字，
     * 不比对的话下划线会落在已经挪位的字符上。代号（[documentToken]）也一并比：
     * 两份内容恰好相同的文档不该互相覆盖结果。
     */
    private suspend fun runAnalysis() {
        val token = documentToken
        val text = this.field.text
        val syntaxNow = syntax
        val name = fileName
        val found = withContext(Dispatchers.Default) {
            DiagnosticEngine.analyze(text, syntaxNow, name)
        }
        if (stale(token) || this.field.text != text) return
        diagnostics = found
        diagnosticCursor = 0
    }

    /**
     * 跳到下一个问题，返回跳到的那个（界面据此提示「第 N 行：…」）。到末尾后回到第一个。
     *
     * 为什么不做「问题列表」面板：手机上真正高频的动作是「带我去看看」，而不是「读一列消息」。
     * 跳转复用了查找那套一次性滚动请求，零新增机制。
     */
    fun jumpToNextDiagnostic(): Diagnostic? {
        val list = diagnostics
        if (list.isEmpty()) return null
        val target = list[diagnosticCursor % list.size]
        diagnosticCursor = (diagnosticCursor + 1) % list.size
        scrollToOffset = target.start
        return target
    }

    /**
     * 立刻把查找用的正文同步到当前正文。
     *
     * 跳转与替换之前必须调：它们要用最新的命中列表，等 150ms 会把偏移算在过期的文本上。
     * 撤销/重做也是离散动作，没有「连续输入」的问题，直接同步。
     */
    private fun syncSearchText() {
        searchTextJob?.cancel()
        searchTextJob = null
        searchText = this.field.text
    }

    /**
     * 撤销一步。没有历史可撤、或历史已与正文不符（栈会自己清空）时什么都不做。
     *
     * 光标跟着回到改动处并请求滚动：被撤销的内容常常在视野之外，看不见的撤销等于没发生。
     */
    fun undo() {
        if (readOnly) return
        val outcome = undoStack.undo(field.text) ?: return
        applyOutcome(outcome)
    }

    /** 重做一步。见 [undo] */
    fun redo() {
        if (readOnly) return
        val outcome = undoStack.redo(field.text) ?: return
        applyOutcome(outcome)
    }

    private fun applyOutcome(outcome: UndoOutcome) {
        val caret = outcome.caret.coerceIn(0, outcome.text.length)
        field = TextFieldValue(outcome.text, TextRange(caret))
        refreshUndoState()
        syncSearchText()
        search.clampIndex()
        scrollToOffset = caret
        // 撤销/重做是离散动作，分析立刻重跑：刚撤掉的那处错误该马上消失
        scheduleAnalysis(immediate = true)
        scheduleDraftSave()
    }

    /** 关闭当前文档，回到「请选择文件」的空状态（也用于从「读不了」页面退出） */
    fun closeDocument() {
        // 走掉的这一刻一并作废所有在飞的 open：否则它读完之后会把刚关掉的文档又摆回来，
        // 界面上看不出来（`hasDocument` 已经是 false），但 ViewModel 里多出一份没人认领的
        // 文档，草稿归属、语法覆盖都会跟着它走。
        documentToken++
        tooLarge = null
        unavailable = false
        notText = false
        loading = false
        fileName = ""
        search.close()
        clearCurrentDocument()
    }

    /**
     * 丢掉「当前这一份文档」的全部状态，回到没有归属的空状态。
     *
     * 打开**失败**时也必须走这里。原来的写法只在成功路径上覆盖状态，于是新文件读不到时，
     * 上一次打开的文档状态原样留着：顶栏标题已经换成失败的那个文件名，正文、[currentUri]、
     * 撤销历史却还是上一份的——界面像在编辑新文件，实际动的是旧文件，连「保存」都会把
     * 旧文件的内容写回旧位置。这种错位比直接报错危险，所以宁可清空。
     *
     * 只有 [currentUri] 及其**派生状态**在这一层；告知用户「是哪个文件出了问题」的
     * [fileName] / [unavailable] / [tooLarge] / [notText] 由调用方按需要保留或清除。
     */
    private fun clearCurrentDocument() {
        readOnly = false
        scrollToLine = null
        // 滚动请求也要一起丢：它由界面在 animateScrollTo **返回之后**才消费（见
        // consumeScrollRequest），中间隔着一次动画；只读模式下更是没人消费它。
        // 留着的话，下一份文档会被滚到上一份算出来的偏移上。
        scrollToOffset = null
        externalChange = null
        pendingDraft = null
        currentUri = null
        baseline = null
        field = TextFieldValue("")
        baselineText = ""
        searchTextJob?.cancel()
        searchTextJob = null
        searchText = ""
        // 诊断同理：它是对**某一份正文**的结论，正文清空了就不该再挂着，
        // 否则切到新文档前那一瞬会看到上一份文件的红下划线。
        analysisJob?.cancel()
        analysisJob = null
        diagnostics = emptyList()
        // 查找面板也要收起来：它显示的是上一份文档的命中数，而此刻界面上已经
        // 没有可搜的正文了（打开失败时也会走到这里，不只是 closeDocument）。
        search.close()
        // 没有当前文档就无从谈起写权限。留成 true 会让「保存」在空状态上亮着。
        writable = false
        // 文档元信息也一起丢掉：空状态下它们没有任何意义，留着只会在「哪天有空状态也显示编码的
        // 界面」时冒出一个属于上一份文件的值。
        //
        // [fileName] **故意不清**：打开失败时标题栏上的是那个**被拒绝的文件**的名字，
        // 用户更需要知道「是哪个文件打不开」而不是看到一个空白标题。
        encoding = DocumentEncoding.UTF8
        lineEnding = LineEnding.LF
        mixedEndings = false
        undoStack.clear()
        refreshUndoState()
        manualSyntax = null
        applySyntax()
    }

    /** 按 [manualSyntax] 重算生效语法。手动为 null 时退回按文件名的自动识别。 */
    private fun applySyntax() {
        val manual = manualSyntax
        syntax = manual ?: SyntaxRegistry.forFileName(fileName)
        syntaxAuto = manual == null
    }

    /**
     * 手动指定当前文档的语法；传 null 回到自动识别。
     *
     * 只改着色，不碰正文，所以**不进撤销栈**——撤销一步却顺带把颜色改回去，
     * 会让人以为是文本被撤掉了。
     *
     * 选择会按文件记下来（[SyntaxOverrides]）：认不出扩展名的文件每次打开都要重选一遍的话，
     * 这个功能等于没有。
     */
    // ⚠️ 不能叫 setSyntax：`var syntax` 生成的 setter 已经占了 setSyntax(Syntax) 这个
    // JVM 签名，同名会编译期报 "Platform declaration clash"。
    fun selectSyntax(target: Syntax?) {
        manualSyntax = target
        applySyntax()
        // 换语法会换一套分析器（甚至从「不分析」变成「分析」），立刻重跑
        scheduleAnalysis(immediate = true)
        val uri = currentUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (target == null) syntaxOverrides.clear(uri) else syntaxOverrides.set(uri, target)
        }
    }

    /** 打开文档：探测编码与行尾、归一化行尾、登记最近列表、检查有无可恢复草稿 */
    // ---------- 行为：打开 / 保存 / 外部改动检测 ----------

    fun open(uri: Uri, dropDraft: Boolean = false) {
        // 这次打开的代号。往后每个挂起点之后都要问一句「我还是当前那次吗」——见 [documentToken]
        val token = ++documentToken
        viewModelScope.launch {
            loading = true
            unavailable = false
            pendingDraft = null
            tooLarge = null
            notText = false
            // 诊断也要一起清：它是对**上一份正文**的结论。成功路径下新正文会在下面整批落下，
            // 而新分析还要等一次调度，中间这段时间旧诊断会盖在新正文上——渲染时的区间夹取
            // 只保证不崩，不保证内容还是对的。
            analysisJob?.cancel()
            analysisJob = null
            diagnostics = emptyList()
            readOnly = false
            scrollToLine = null
            // 不在这里清的话，上一份文档留下的滚动请求会作用到**新文档**上：它由界面在
            // animateScrollTo 返回之后才消费（见 consumeScrollRequest），中间隔着一次动画。
            scrollToOffset = null
            externalChange = null
            val startedAt = SystemClock.elapsedRealtime()

            // 文件名要早拿：被拒绝打开的文档，用户更需要知道是哪个文件被拒了
            val name = withContext(Dispatchers.IO) { repository.displayName(uri) }
            if (stale(token)) return@launch
            fileName = name

            // ⚠️ 这个校验必须紧跟在挂起点之后。下面三个失败分支除写
            // [unavailable]/[tooLarge]/[notText] 之外还会 [clearCurrentDocument]，若放任它们落在
            // **两次打开交错**的时候，就会盖到新文档
            // 头上：新文档的成功路径不复位这两个字段（它们只在 `open()` 开头复位，那时这次调用
            // 还没返回），于是界面显示「标题是新文件、正文却是『打不开 / 文件过大』」，状态栏与
            // 保存一起消失。这正是 [documentToken] 那套代号要挡的交错。
            if (stale(token)) return@launch

            // 体积与「是不是文本」的判断都在仓库里做（只有那里拿到了字节），这里只按结果分流。
            // 四种结果要给用户四种完全不同的交代，所以用 when 而不是叠 if。
            val loaded = when (val result = repository.openDocument(uri)) {
                is OpenResult.Unavailable -> {
                    unavailable = true
                    loading = false
                    // 上一次打开的文档状态要一起丢掉：留着它就会出现「标题是这个文件、
                    // 正文与保存目标却是上一个文件」的错位（见 clearCurrentDocument）。
                    clearCurrentDocument()
                    return@launch
                }
                is OpenResult.TooLarge -> {
                    tooLarge = result
                    loading = false
                    clearCurrentDocument()
                    return@launch
                }
                is OpenResult.NotText -> {
                    notText = true
                    loading = false
                    clearCurrentDocument()
                    return@launch
                }
                // 刻意**不**在这里顺手写 readOnly：那是给用户看的状态，必须等下面全部挂起点
                // 过完、确认自己仍是当前那次打开之后再落，否则会盖掉更新的那一次。
                is OpenResult.Loaded -> result
            }
            val doc = loaded.document

            // 剩下要问 provider / 碰磁盘的三件事，都在写任何 UI 状态**之前**问完。
            // 原来是分散在赋值之间做的，于是 readOnly（早落）与正文（晚落）之间隔着两次挂起
            // ——「小文件的正文 + 大文件的只读标记」这类错位就是这么来的。
            val overrideId = withContext(Dispatchers.IO) { syntaxOverrides.get(uri) }
            val state = withContext(Dispatchers.IO) { repository.fileState(uri) }
            val canWriteNow = repository.canWrite(uri)
            if (stale(token)) return@launch
            val readMs = SystemClock.elapsedRealtime() - startedAt

            // ⚠️ 从这里到 loading = false 之间**不能插挂起点**：readOnly、正文、Uri、基准、
            // 语法必须作为**同一批**落下，否则回到前台触发的检测会看到「新 uri + 旧基准」
            // 的组合，并把它误报成「文件被别的应用改过」。
            readOnly = loaded.readOnly
            currentUri = uri
            baseline = state
            field = TextFieldValue(doc.text)
            baselineText = doc.text
            searchText = doc.text
            // 正文整份换过了，而查找面板可能正开着：它的「第 N / M 处」是按上一份正文算的，
            // 不复位就会显示「6 / 2」这种矛盾数字（见 EditorSearch.resetPosition）。
            // 这里刻意用重置而不是 clampIndex：旧序号在这份正文里**没有对应物**，夹回来也只是
            // 把强调色挪到一处用户从没跳过去的命中上。
            search.resetPosition()
            undoStack.clear() // 换了一份文档，上一份的撤销历史没有意义
            refreshUndoState()
            encoding = doc.encoding
            lineEnding = doc.lineEnding
            mixedEndings = doc.mixedEndings
            writable = canWriteNow
            // 上次为这个文件手动选的语法优先于扩展名
            manualSyntax = overrideId?.let { SyntaxRegistry.byId(it) }
            applySyntax()
            loading = false

            // 打开后立刻分析一次（不防抖）：刚打开就该看到问题，而不是先干净一会儿再标红
            scheduleAnalysis(immediate = true)

            // 慢文档留个记录。打开大文件时这几个数字能直接指出瓶颈在读取、还是在我们自己
            // 的索引构造（剩下的差额就是 Compose 首次排版整篇文本）。比反复猜要省事。
            val total = SystemClock.elapsedRealtime() - startedAt
            if (total > SLOW_OPEN_MS) {
                Log.d(
                    LOG_TAG,
                    "open ${fileName}: read=${readMs}ms total=${total}ms chars=${doc.text.length}",
                )
            }
            // 登记权限与最近列表：跨进程 + 碰磁盘，放 IO 线程做，别拖住主线程
            withContext(Dispatchers.IO) {
                repository.persistPermission(uri)
                repository.addToRecents(uri)
            }
            // 草稿归谁必须与「当前文档」一致：这次打开若已不是当前那次，就别去动 pendingDraft
            // ——否则用户会在 A 文件上被问「要不要恢复 B 文件的草稿」。
            if (stale(token)) return@launch
            val restore = when {
                // 只读模式既不能编辑、保存也禁用了，拿草稿问「要不要恢复」是个空承诺
                // ——恢复完也无处可用。留着它，等文件回到可编辑体积时自然会再问。
                readOnly -> null
                // 用户刚放弃内存里的版本、明确选了磁盘版本，那份草稿就没理由留着了：
                // 留着的话重载完立刻又会弹一次「发现草稿」，弹的正是他刚放弃的内容。
                dropDraft -> {
                    withContext(Dispatchers.IO) { drafts.delete(uri.toString()) }
                    null
                }
                else -> withContext(Dispatchers.IO) { draftToRestore(uri, doc.text) }
            }
            if (stale(token)) return@launch
            pendingDraft = restore
        }
    }

    /**
     * 写回原位置。编码与行尾都用打开时的那一组——这是纯文本编辑器不能破的底线：
     * 用户只是打开看了一眼，文件不该有任何字节变化。
     */
    suspend fun save(): Boolean {
        val uri = currentUri ?: return false
        val token = documentToken
        // 写下去的内容**只取一次**。原来这里是 `saveDocument(..., field.text, ...)` 之后又
        // 用 `field.text` 去刷基准：`saveDocument` 是挂起的，这中间用户完全可以再敲一个字，
        // 于是「写进磁盘的是 A，基准却记成 B」——那一刻未保存标记消失，而磁盘上没有那个字。
        // 宁可让他多按一次保存，也不能让标记撒谎。
        val content = field.text
        val ok = repository.saveDocument(uri, content, encoding, lineEnding)
        if (!ok) return false
        // 挂起期间用户可能换了文档：那这一份的基准归 open() 写，这里再写就是覆盖别人的状态。
        if (stale(token)) return true
        // 基准记的是**真正写下去的那一份**（content），不是此刻的正文：保存期间新敲的内容
        // 必须仍然算未保存，否则它会被静默丢掉。
        //
        // **防「自写自报」靠的就是这一行**：下次回前台拿磁盘比内容，两边都是 content，于是不提示。
        // 这里刻意**不再**顺手刷新 [baseline]——它只服务只读那条路（见其 KDoc），
        // 可编辑文档没有人读它，刷新它就是白问一次 provider。
        baselineText = content
        // 已经覆盖写回，磁盘与内存的分歧就此结束，那句提示没有理由还挂着
        externalChange = null
        // 撤销栈**不清**——刚保存过不等于想放弃历史，用户完全可以接着撤销回更早的状态
        // （那时 dirty 会重新亮起来）。
        withContext(Dispatchers.IO) { drafts.delete(uri.toString()) }
        return true
    }

    /**
     * 另存为：把当前内容写到另一个位置，并把「当前文档」**整体迁到那里**。
     *
     * 迁的不只是 [currentUri]。文件名、写权限、草稿归属、语法覆盖全都要跟着走，
     * 少一处就会出鬼：漏了 [baselineText]，界面会顶着一个假的未保存标记，而用户刚刚才存过。
     * [baseline] 这里**不迁**（置空）：它只服务只读文档的元数据比对，而另存为对只读是关着的
     * ——可编辑文档每次都拿内容比，留着上一个文件的元数据只会误导后来人。
     *
     * 编码与行尾**沿用当前文档**的：这个动作是「换个地方放同一份东西」，不是新建文档。
     * 换位置本身不改变内容一丝一毫，这一点和 [save] 的底线是同一条。
     *
     * 撤销栈**不清**：内容没有变，历史当然还成立。
     */
    suspend fun saveAs(uri: Uri): Boolean {
        val previous = currentUri
        val token = documentToken
        // 与 [save] 同理：内容只取一次，[baselineText] 记的必须是**真正写下去的那一份**
        val content = field.text
        val ok = repository.saveDocument(uri, content, encoding, lineEnding)
        if (!ok) return false

        withContext(Dispatchers.IO) {
            repository.persistPermission(uri)
            repository.addToRecents(uri)
            // 旧位置的草稿没有存在理由了：那份内容已经落到新文件上，旧文件本身没被改过。
            // 留着它，下次打开旧文件会弹一次「发现草稿」，而弹的正是用户以为已经处理掉的内容。
            previous?.takeIf { it != uri }?.let { drafts.delete(it.toString()) }
        }
        // 挂起期间换了文档：文件确实已经写出去了（那是用户按下的动作），但「当前文档」不能再
        // 迁到这个新位置——要迁的是当时那一份的状态，而它已经不是当前这一份了。
        if (stale(token)) return true
        currentUri = uri
        // 见 KDoc：只读才用 [baseline]，而这里不可能只读，所以置空而不是迁过来
        baseline = null
        baselineText = content
        writable = true // 刚写成功，写权限一定有；再问一次 provider 只会引入一个可能出错的点
        externalChange = null
        // 标题取新文件名。读名字要碰 provider，所以放在状态落完之后，取回来再确认一次
        val name = withContext(Dispatchers.IO) { repository.displayName(uri) }
        if (stale(token)) return true
        fileName = name
        // 手动指定的语法跟着**文档内容**走，而不是跟着文件名走：用户明确选过就沿用，
        // 没选过才按新名字重新认——「a.log 存成 a.json」时前者会让人以为设置丢了，
        // 后者正好是他想要的。
        manualSyntax?.let { chosen ->
            withContext(Dispatchers.IO) { syntaxOverrides.set(uri, chosen) }
        }
        if (stale(token)) return true
        applySyntax()
        return true
    }

    // ---------- 外部改动 ----------

    /**
     * 回到前台时调用：看原文件有没有被别的应用改过。
     *
     * 分两条路，因为两条路上可信的信号不一样：
     * - **只读的大文件**只比体积与修改时间，**不重读内容**。4MB 的读取 + 解码要一秒以上，
     *   切回前台时卡这么一下不值得；只有元数据明确说变了才提示，而且这时才用得上
     *   「元数据没变就跳过」的快速路径（见下）。
     * - **可编辑的文件**每次都把内容**读回来比**。元数据在这条路上不可靠——实测没有读媒体权限时
     *   MediaStore 的 query 返回空 cursor，于是「拿不到」会被误当成「没变」；而这个体积的
     *   文件重读一遍只要几十毫秒，用内容判据更准也更省心。
     *
     * ## 可编辑这条路上**没有**快速路径，这是刻意的
     *
     * 「元数据没变」这件事本身不可信：实测 MediaStore 的 `_size` / `date_modified` 会**滞后**
     * 好几秒，甚至报着上一轮的旧值（`content write` 写完，provider 那边还是老数字）。拿一个
     * 不可信的信号去否决可信的内容比对，等于给外部改动检测装了一道会误关的闸——实测漏报就是
     * 这么来的：应用手里是 34 字符的正文，provider 报的 size 却是上一轮 37，两边「相等」，
     * 于是跳过检测、外面那份 37 字节的新内容再没被提起。代价只有几十毫秒（可编辑上限 20 万字符），
     * 比一次输入的排版开销还小。
     *
     * 只读那条路保留快速路径，是因为那里重读一遍的代价是**秒级**，只能退而求其次。
     *
     * ## 判据的基准是「上次同步的那一份」，不是「此刻的缓冲」
     *
     * 只读那条路比的是 [baseline]，可编辑那条路比的是 [baselineText]——两次都是拿**基准**
     * 去对，而不是拿 [field]。有未保存改动时缓冲本来就与磁盘不同，拿它去比等于把「用户自己
     * 还没存」报成「别人改了文件」，而那条横幅上的按钮叫「重新加载」（点下去连草稿一起丢）。
     *
     * 同理，**「问不出来」不下任何结论**：provider 报不出体积与时间（[DocumentRepository.fileState]
     * 返回 null）时既不能说「变了」也不能说「没了」。判据必须落在「明确给出的信号」上。
     */
    fun checkExternalChange() {
        val uri = currentUri ?: return
        if (loading || unavailable || notText || tooLarge != null) return
        val base = baseline
        // 这次检测针对的是哪一份文档。检测要跨进程问 provider、可编辑时还要重读全文，中间
        // 用户完全可能换了文件——那就既不该报「被改过」，更不该把别的文件的基准写进 [baseline]。
        val token = documentToken
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { repository.fileState(uri) }
            if (stale(token)) return@launch

            if (readOnly) {
                // 快速路径只留在这里：元数据说完全没变就不必把 4MB 重读一遍。
                if (base != null && state == base) return@launch
                // 大文件不为了「确认一下」而重读一遍：4MB 的读取 + 解码要一秒以上，
                // 切回前台时卡这么一下不值得。这里只信元数据**明确给出**的变化。
                when {
                    // 开文档时也问不出来（base == null）说明这个 provider 本来就不报这些列，
                    // 那什么都不能推断，宁可什么都不说；**先前问得出来、现在连元数据都查不到**
                    // 才算「打不开了」的证据——与可编辑那条路把 Unavailable 判成 Gone 同一口径。
                    state == null -> if (base != null) externalChange = ExternalChange.Gone
                    base != null && differs(base, state) -> externalChange = ExternalChange.Modified
                }
                return@launch
            }

            // 可编辑的文件：**每次都把内容读回来，和上次同步的那一份比**（不走元数据快速路径，
            // 理由见 KDoc）。
            // 元数据在这条路径上不可靠——实测应用没有读媒体权限时，MediaStore 的 query
            // 返回空 cursor（不报错），于是「拿不到」会被误当成「没变」。内容才是可信信号；
            // 这个文件本来就在可编辑范围内，重读一遍只要几十毫秒。
            val result = withContext(Dispatchers.IO) { repository.openDocument(uri) }
            // 重读全文是这条路上最长的挂起，回来必须再确认一次身份
            if (stale(token)) return@launch
            when (result) {
                is OpenResult.Unavailable -> externalChange = ExternalChange.Gone
                is OpenResult.TooLarge -> Unit // 变成超大文件了，先不动，等下次打开再说
                // 文件内容变成了二进制（例如被别的应用改写成别的格式）：
                // 这条路径上不做任何事——把用户正在看的内容换成「不是文本」的失败页，
                // 比留着可能已经过期的正文更糟。重开时才走拒绝分支。
                is OpenResult.NotText -> Unit
                is OpenResult.Loaded -> {
                    // baselineText 在这里读（挂起之后）而不是开头：期间用户可能刚好保存过，
                    // 那就该拿最新的那一份当基准，否则会把这次保存自己报成外部改动。
                    //
                    // 内容一致时**什么都不做**：这里刻意不顺手刷新 [baseline]，那个基准只服务
                    // 只读那条路（见 KDoc 与 [baseline] 的说明），可编辑这条路每次都用内容判据，
                    // 刷新它既没人读、又白问一次 provider。
                    if (result.document.text != baselineText) externalChange = ExternalChange.Modified
                }
            }
        }
    }

    /** 两个文件状态是否表明内容变了 */
    private fun differs(a: FileState, b: FileState): Boolean =
        if (a.hasModifiedAt && b.hasModifiedAt) {
            a.modifiedAt != b.modifiedAt || a.size != b.size
        } else {
            a.size >= 0 && b.size >= 0 && a.size != b.size
        }

    /**
     * 采用磁盘上的版本——用户明确选择放弃内存里的内容。
     *
     * [open] 传 `dropDraft = true`：草稿里存的是被放弃的那份内容，留着只会在重载后
     * 触发一次「发现草稿」的询问。
     */
    fun reloadFromDisk() {
        val uri = currentUri ?: return
        externalChange = null
        open(uri, dropDraft = true)
    }

    /**
     * 保留内存里的版本（只读浏览时是「忽略」）。
     *
     * 要让「同一个分歧不再问第二遍」，就得把基准挪到用户**刚刚认可过的那一份**上——
     * 两条路各自的「那一份」不一样：
     * - 只读比的是元数据，所以刷新 [baseline]；
     * - 可编辑比的是正文，所以把 [baselineText] 挪到**磁盘当前那一份**。这不是「假装我的内容
     *   已经存好了」：挪完之后 [dirty] 会正确地变成 true（用户的内容确实还没进磁盘，标题上那个
     *   `•` 正是要说这件事），而且下次真的保存时磁盘才会被覆盖。
     *
     * 反过来说，**只更新元数据基准对可编辑文档没用**——那条路每次都拿内容比，元数据根本不参与
     * 判断，于是同一个分歧会被反复提示（B6 刚把快速路径从这条路上抽掉时就是这样，靠上机第 2 步
     * 抓出来的）。
     *
     * 例外是**文件已经不存在**：那时什么都挪不了，于是每次回前台还会再提示一次。这是对的
     * ——那份文件确实写不回去了，反复告知比装作没事更安全。
     */
    fun keepLocalVersion() {
        val uri = currentUri ?: return
        externalChange = null
        // 下面这些读写都要过代号：期间换了文档的话，基准已经是新文档的，写下去就是把
        // 上一份文件的版本按到新文档头上（下次回前台会据此误报或漏报外部改动）。
        val token = documentToken
        val stillReadOnly = readOnly
        viewModelScope.launch {
            if (stillReadOnly) {
                val state = withContext(Dispatchers.IO) { repository.fileState(uri) }
                if (stale(token)) return@launch
                baseline = state
                return@launch
            }
            val disk = withContext(Dispatchers.IO) { repository.openDocument(uri) }
            if (stale(token)) return@launch
            // 读不到（被删/被移走）就什么都不动：下一次检测会以 Gone 提示，那比假装认可了安全。
            (disk as? OpenResult.Loaded)?.let { baselineText = it.document.text }
        }
    }

    // ---------- 查找与替换 ----------

    /** 打开查找面板。有选区时拿选区内容当初始关键词（只读模式没有光标，从空开始） */
    // ---------- 行为：查找与替换 ----------

    fun openSearch() {
        syncSearchText() // 面板一打开就要立刻搜，不能等到延迟结束
        val selection = field.selection
        val initial = if (readOnly || selection.collapsed) {
            ""
        } else {
            field.text.substring(selection.min, selection.max.coerceAtMost(field.text.length))
        }
        search.open(initial)
    }

    fun closeSearch() {
        search.close()
    }

    fun findNext() {
        // 关键词可能还没过防抖就按了回车（软键盘的「搜索」键会直接走到这里）。
        // 不先提交的话搜的是**上一次**的关键词，表现为「按回车没反应，得按第二次」。
        search.commitQuery()
        syncSearchText()
        val match = search.findNext(findAnchor()) ?: return
        revealMatch(match)
    }

    fun findPrev() {
        search.commitQuery()
        syncSearchText()
        // 只读模式没有光标，锚点取**当前命中的开头**，与 [findAnchor] 取结尾对称。
        // 少了这个分支的话只读下锚点恒为 0，「上一处」第一次点就直接绕到最后一处。
        val from = if (readOnly) {
            search.matches.getOrNull(search.currentIndex)?.start ?: 0
        } else {
            // 从选区**开头**往前找：否则「上一处」会反复选中当前这一处
            field.selection.start
        }
        val match = search.findPrev(from) ?: return
        revealMatch(match)
    }

    /**
     * 查找的锚点偏移。
     *
     * 只读模式下没有光标，用「当前命中」当锚点——否则连按「下一处」永远从文件开头找，
     * 一直停在第一处。这也是为什么 replaceCurrent 里换完不重置 currentIndex。
     */
    private fun findAnchor(): Int =
        if (readOnly) search.matches.getOrNull(search.currentIndex)?.end ?: 0
        else field.selection.end

    /** 把命中揭示给用户：可编辑模式选中它，只读模式滚到它所在的行 */
    private fun revealMatch(match: SearchMatch) {
        if (readOnly) {
            scrollToLine = lineIndex.lineOf(match.start)
        } else {
            field = field.copy(selection = TextRange(match.start, match.end))
            scrollToOffset = match.start
        }
    }

    fun consumeScrollRequest() {
        scrollToOffset = null
    }

    fun consumeScrollToLine() {
        scrollToLine = null
    }

    /** 替换当前这一处。返回是否真的替换了（没有命中、序号失效、或只读时返回 false） */
    fun replaceCurrent(): Boolean {
        if (readOnly) return false
        // 与查找同理：关键词没过防抖就点替换时，先提交再取命中。
        search.commitQuery()
        // 命中列表是按 searchText 算的，它比正文滞后一个防抖周期。替换是**按偏移改文本**的，
        // 拿旧偏移去改新正文会改错位置（静默损坏正文），所以这里必须先对齐一次。
        syncSearchText()
        val index = search.currentIndex
        val match = search.matches.getOrNull(index) ?: return false
        val replacement = if (search.regexMode) {
            SearchEngine.expandReplacement(search.replaceText, match.groups)
        } else {
            search.replaceText
        }
        val (text, caret) = SearchEngine.replaceMatch(field.text, match, replacement)
        undoStack.record(field.text, text, SystemClock.elapsedRealtime(), coalesce = false)
        // 动过栈就要同步按钮态，否则「替换」之后撤销按钮仍是灰的（canRedo 也不会清）
        refreshUndoState()
        field = TextFieldValue(text, TextRange(caret))
        syncSearchText()
        scrollToOffset = caret
        // 正文整份换过了，诊断必须重跑（见 scheduleAnalysis 的清单）
        scheduleAnalysis(immediate = true)
        // 按光标重定位，不能沿用旧序号：替换串比匹配串长、且自身还能被搜到时
        // （cat -> catalog），重算后的 matches[旧序号] 指向的正是刚插入的内容，
        // 连按替换会永远停在原地并且让文本越来越长。
        search.reindexFrom(caret)
        scheduleDraftSave()
        return true
    }

    /** 替换全部。返回替换处数（只读模式恒为 0） */
    fun replaceAll(): Int {
        if (readOnly) return 0
        search.commitQuery()
        // 同 [replaceCurrent]：命中与正文必须先对上，否则会按错偏移改坏正文。
        // 这里比单处替换更危险——错一次就是整篇多处一起错。
        syncSearchText()
        val (text, count) = SearchEngine.replaceAll(
            text = field.text,
            matches = search.matches,
            template = search.replaceText,
            regex = search.regexMode,
        )
        if (count > 0) {
            undoStack.record(field.text, text, SystemClock.elapsedRealtime(), coalesce = false)
            refreshUndoState()
            // 光标留在原处（夹到新长度内），而不是弹回文件开头——替换不该顺手把视线带走
            val caret = field.selection.start.coerceIn(0, text.length)
            field = TextFieldValue(text, TextRange(caret))
            syncSearchText()
            search.clampIndex()
            // 替换全部动的是整篇，诊断必须立刻重算（见 scheduleAnalysis 的清单）
            scheduleAnalysis(immediate = true)
            scheduleDraftSave()
        }
        return count
    }

    /** 采用草稿：覆盖当前正文，并把草稿里记的编码与行尾一并取回 */
    // ---------- 行为：草稿生命周期 ----------

    fun restoreDraft() {
        val draft = pendingDraft ?: return
        // 整体替换，独立成一步撤销：与手打输入合并的话，撤销一次会连带上一段输入
        undoStack.record(field.text, draft.text, SystemClock.elapsedRealtime(), coalesce = false)
        // 同 [replaceCurrent]：动了栈就得同步按钮态，否则刚恢复完草稿，「撤销」还是灰的
        refreshUndoState()
        field = TextFieldValue(draft.text)
        encoding = draft.encoding
        lineEnding = draft.lineEnding
        // 正文整个换掉了：查找快照与命中序号都是按旧文本算的，必须一起重来
        syncSearchText()
        search.clampIndex()
        scrollToOffset = 0
        pendingDraft = null
        // 草稿把正文整个换掉了，诊断必须重算（见 scheduleAnalysis 的清单）：
        // 恢复出来的草稿很可能正是「有问题的那一版」，界面要立刻把它标出来
        scheduleAnalysis(immediate = true)
    }

    /** 丢弃草稿：用户明确选择了文件里的版本，草稿就没有存在理由了 */
    fun discardDraft() {
        val draft = pendingDraft ?: return
        pendingDraft = null
        viewModelScope.launch(Dispatchers.IO) { drafts.delete(draft.uri) }
    }

    // ---------- 草稿 ----------

    /**
     * 输入后延迟落盘。延迟是为了不在每次按键都写 1MB；而 onStop 那条路径不依赖这里，
     * 所以延迟多久都不影响「切后台不丢内容」的保证。
     */
    private fun scheduleDraftSave() {
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            currentUri?.let { writeDraft(it) }
        }
    }

    /**
     * `onStop` 调用。必须用 [runBlocking] 同步写完再返回：这里起协程的话，系统随时可以
     * 在协程跑完之前杀掉进程，那就等于没存。1MB 文本写内部存储是毫秒级，阻塞得起。
     */
    fun flushDraft() {
        draftJob?.cancel()
        draftJob = null
        val uri = currentUri ?: return
        if (!dirty) {
            // 不脏也可能是**残留**：编辑过（防抖那一路已经把草稿写到盘上）→ 一路撤销回原样
            // → `dirty` 是「正文 != 基准」的比对（见类头说明），于是又变回 false，而盘上那份
            // 草稿还在。窗口只有 1.5s（再久会被防抖覆盖成正确内容），但它是**唯一**会把用户
            // 已明确放弃的内容重新推回去的路径，所以这里要清掉。
            //
            // 两个前提不能少，少任何一个都会把「该留的草稿」删成数据丢失：
            //  · `pendingDraft != null`：用户还没对「发现草稿」做选择，那份草稿正在被引用；
            //  · `readOnly`：只读时 `open()` 刻意不把草稿交出来（恢复了也没处可用），
            //    留着等文件回到可编辑体积时再问。
            if (pendingDraft == null && !readOnly) {
                runBlocking(Dispatchers.IO) { drafts.delete(uri.toString()) }
            }
            return
        }
        runBlocking(Dispatchers.IO) { writeDraft(uri) }
    }

    private suspend fun writeDraft(uri: Uri) = withContext(Dispatchers.IO) {
        val draft = Draft(
            uri = uri.toString(),
            name = fileName,
            text = field.text,
            encoding = encoding,
            lineEnding = lineEnding,
            updatedAt = System.currentTimeMillis(),
        )
        drafts.write(draft)
    }

    /**
     * 打开文档后核对草稿：返回**该问用户要不要恢复**的那一份，没有则返回 null。
     *
     * 草稿内容与文件一致说明上次正常保存过了（或用户没改动），属于残留，直接清掉，
     * 不要拿这种草稿去打扰用户。
     *
     * 只读不写状态（不在这里直接赋值 [pendingDraft]）：调用它的是 [open]，而那个方法要在
     * 挂起点之间来回，返回值必须等它确认自己仍是「当前那次打开」之后再落地——见 [documentToken]。
     * 老写法在这里直接写 `pendingDraft`，于是慢文档的草稿有可能落到 B 文件头上。
     */
    private fun draftToRestore(uri: Uri, fileText: String): Draft? {
        val draft = drafts.read(uri.toString()) ?: return null
        if (draft.text == fileText) {
            drafts.delete(uri.toString())
            return null
        }
        return draft
    }

    private companion object {
        const val DRAFT_DEBOUNCE_MS = 1500L

        /** 查找面板里关键词与正文共用的防抖延迟。见 [searchText] 与 `EditorSearch.query` */
        const val SEARCH_DEBOUNCE_MS = 150L

        /**
         * 输入后延迟多久重跑本地分析。
         *
         * 比查找的 150ms 长一倍：分析是一次全篇扫描（查找也扫，但它的结果直接决定面板计数，
         * 慢一点用户就看得见），而诊断在打字过程中**不需要实时**——下划线晚 300ms 出现
         * 完全不影响打字，但每个字符都重跑一次全篇扫描是要花钱的。
         */
        const val ANALYSIS_DEBOUNCE_MS = 300L
        const val LOG_TAG = "TextNote"
        /** 超过这个耗时就打日志，方便用户反馈「打开很慢」时直接定位 */
        const val SLOW_OPEN_MS = 250L

        /**
         * 着色上限（字符数）。超过就不再配色，状态栏给语法名加个 `*` 说明原因。
         *
         * 注意它与 [com.textnote.app.core.EditorLimits] 里的上限是两回事：
         * 那个管「开不开」，这个只管「着不着色」。数值由那边的实测表支撑。
         */
        const val HIGHLIGHT_LIMIT = EditorLimits.HIGHLIGHT_CHARS
    }
}

/**
 * 回到前台时发现原文件在别处发生了变化。
 *
 * 分两种而不是一个布尔：「被改过」（内容分叉，用户要选保留哪一份）和「没了」
 * （保存必然失败）要给的下一步动作完全不同。
 */
sealed interface ExternalChange {

    /** 内容被改过。内存里的版本与磁盘上的版本已经分叉 */
    data object Modified : ExternalChange

    /** 被移动或删除了。此时保存必然失败 */
    data object Gone : ExternalChange
}
