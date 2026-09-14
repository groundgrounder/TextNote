package com.textnote.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.textnote.app.R
import com.textnote.app.core.Highlighter
import com.textnote.app.core.LineIndex
import com.textnote.app.core.SearchEngine
import com.textnote.app.core.SearchMatch
import com.textnote.app.core.Syntax
import com.textnote.app.ui.theme.LocalEditorTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 一份文本最多显示多少个字符的**单行**内容。
 *
 * 导火索是一个实测出来的洞：整份文件只有一行时（压缩过的 JSON、minified JS，
 * 2MB 挤在一行里），上面这套「一行一个 item」的结构里那个 item 就是个巨型段落，
 * 实测**打开超时 >150 秒**——和现役内核一样卡死。
 *
 * 把软换行关掉能救回来（实测 2MB 单行：打开 5.6s、单帧 2.95s），因为不用再对整行做断行计算。
 * 但一帧 3 秒仍然贴着 ANR 的边，所以再加一道显示上限。上限只影响这一条行，
 * 而且**界面上会明确写出被截断了多少**——宁可承认看不全，也不要悄悄吞掉内容。
 *
 * 20 万字符单行大约 0.3 秒一帧。这个数字随设备变化，但数量级安全。
 */
private const val MAX_LINE_CHARS = 200_000

/**
 * 单行最多标多少处命中的底色。
 *
 * 只读模式会把可见行上的命中**都**标出来（见下面匹配高亮那段的说明），于是要给一个封顶：
 * 一行最长 `MAX_LINE_CHARS`，在压缩过的 JSON 里搜单个字符能命中上万处，全加就是上万个
 * SpanStyle——按 M2 的实测（span 数量是主要开销）那会把滚动拖垮。
 *
 * 封顶放在「单行」上而不是像可编辑那样搞全局窗口，是因为这里每行各自一个 AnnotatedString，
 * 天然按行封顶。真正会撞到这个上限的是病态输入，正常查找一行上也就几处。
 */
private const val MAX_MATCHES_PER_LINE = 64

/**
 * 大文件的**只读**浏览渲染器。
 *
 * ## 为什么它能把代价与文件大小解耦
 *
 * `BasicTextField` 的代价来自「把整篇文本交给一个 MultiParagraph」——每次改动都要重排全文。
 * 这里改成 **一行一个 `Text`，交给 `LazyColumn` 按需组合**，于是只排版划到屏幕上的那几十行。
 * 实测（emulator-5554，连续滑动 10 次的中位帧耗时）：
 *
 * | 文件 | p50 |
 * |---|---|
 * | 188K 字符 | 31ms |
 * | 900K 字符 | 32ms |
 * | 2MB 字符 | 29ms |
 * | 8MB 字符 | 36ms |
 *
 * 完全平坦。所以真正的约束不是渲染，而是**内存**（读取上限 [com.textnote.app.core.EditorLimits.READ_BYTES]）。
 *
 * ## 为什么是只读
 *
 * 不能编辑，就绕开了 InputConnection、组合输入、选择手柄、光标管理那一整套——
 * 那才是「自绘可编辑内核」真正贵的部分（sora-editor 那个量级）。这个取舍是刻意的：
 * 先把「大文件至少能看、能搜」落地，而不是去啃一个说不清能不能做完的工程。
 *
 * ## 着色怎么做到不加载全文 token
 *
 * 按行懒加载意味着任意一行随时可能被组合，而那一刻得知道它行首处于什么词法状态
 * （是否还在块注释 / 原样字符串里）。做法是先花一次 O(n) 扫描只记**每行行首状态**
 * （每行 4 字节，2.5 万行约 100KB），之后每一行都能 O(该行长度) 单独着色。
 * 整篇的 token 列表从头到尾不需要存在内存里——那才是大文件真正的内存开销来源。
 *
 * ## 内存上的一处刻意选择
 *
 * 行文本**不**预先 `split('\n')` 存成列表，而是在每个可见 item 里现切
 * `text.substring(行首, 行尾)`。4 万行的文件若预先切出来就是 4 万个 String 对象、
 * 约等于再复制一份全文，而且那次切分发生在**主线程首次组合时**——实测曾在这种量级下
 * 触发过一次 ANR（主线程 CPU 93%、kernel 44%，像 GC 抖动）。现切的话只有屏幕上那几十行会被实例化。
 */
@Composable
fun ReadOnlyReader(
    text: String,
    /**
     * 与 [text] **同一份**文本的行索引，由调用方（[EditorViewModel.lineIndex]）传进来。
     *
     * 这里原本是 `remember(text) { LineIndex.of(text) }`。那是一次 O(n) 全量扫描，
     * 2MB 文件在**主线程首次组合**时为它付过一次账——而 ViewModel 里那份 `derivedStateOf`
     * 已经在算了，等于同一件事做两遍。传进来即可。
     */
    lineIndex: LineIndex,
    syntax: Syntax,
    styles: HighlightStyles,
    matches: List<SearchMatch>,
    currentMatch: Int,
    scrollToLine: Int?,
    onScrolledToLine: () -> Unit,
    matchBackground: Color,
    /** 当前命中用另一种底色，与可编辑模式保持一致 */
    currentMatchBackground: Color,
    modifier: Modifier = Modifier,
) {
    val lineCount = lineIndex.lineCount
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // 行首状态放在后台算：几 MB 的文件也要几十毫秒，不该占着主线程
    var states by remember(text, syntax) { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(text, syntax) {
        states = withContext(Dispatchers.Default) { Highlighter.lineStates(text, lineIndex, syntax) }
    }

    // 行号栏宽度：按最宽的数字串量，行号从 9→10 时列宽才不会跳
    val digits = remember(lineCount) {
        max(2, lineCount.toString().length)
    }
    // 与可编辑模式共用同一份样式（由设置决定）：同一个文件在两种模式下看起来应当一致
    val bodyStyle = LocalEditorTextStyle.current
    val numberStyle = bodyStyle.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
    )
    val gutterWidth = remember(digits, numberStyle, measurer, density) {
        with(density) { measurer.measure("8".repeat(digits), numberStyle).size.width.toDp() }
    }

    val currentLine = if (currentMatch >= 0) {
        matches.getOrNull(currentMatch)?.let { lineIndex.lineOf(it.start) }
    } else {
        null
    }
    // 命名按**用途**分，不按色板分：这两个色相近（同一个 tertiary 系），看错一次就会把
    // 「整行淡底」和「命中高亮」写反，而写反了在界面上很不显眼。
    // lineBackground = 当前行整行的淡底（截断标记也借它，两者都是「这一行有特殊情况」的提示）。
    val lineBackground = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)

    // 跳到命中所在的行。LazyColumn 能直接按 item 下标跳，不需要算 y 偏移——
    // 这是懒加载白送的好处，编辑器那边还得自己用 TextLayoutResult 算。
    LaunchedEffect(scrollToLine, lineCount) {
        val target = scrollToLine ?: return@LaunchedEffect
        listState.scrollToItem(target.coerceIn(0, max(0, lineCount - 1)))
        onScrolledToLine()
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
    ) {
        items(count = lineCount, key = { it }) { index ->
            val lineStart = lineIndex.lineStart(index)
            val lineStop = lineIndex.lineEnd(index)
            // 先量长度再切，不要「先切出整行、再 take」：超长单行（压缩过的 JSON / minified JS
            // 可以有几 MB）那样每组合一次就多分配一份整行的 String，而最贵的那次分配正是它。
            val lineLength = lineStop - lineStart
            val truncated = lineLength > MAX_LINE_CHARS
            val body = text.substring(lineStart, if (truncated) lineStart + MAX_LINE_CHARS else lineStop)
            // 分词只覆盖看得见的那一段，理由同上——`maxChars` 不给的话，被截掉的部分照样要
            // 逐字符扫一遍并产出 token（见 Highlighter.highlightLine）。
            //
            // 必须 remember：`highlightLine` 是 O(行长)，而这里每次重组都会重跑。原来它被塞进
            // 下面那个 remember 当 key，于是 key 每轮都是新 List、`AnnotatedString` 每轮重建，
            // 分词跟着付一遍——超长单行上这笔账很显眼。
            val tokens = remember(states, syntax, lineIndex, index, body) {
                states?.let {
                    Highlighter.highlightLine(
                        text = text,
                        lineIndex = lineIndex,
                        syntax = syntax,
                        line = index,
                        stateIn = it[index],
                        maxChars = body.length,
                    )
                }
            }
            // 这一行上有哪些命中。二分查出来的下标区间，`matches` 是整篇文本的命中列表，
            // 而这里只关心当前这一行（见 SearchEngine.matchIndicesInRange）。
            val onThisLine = SearchEngine.matchIndicesInRange(matches, lineStart, lineStart + body.length)
            // 刻意**不**给下面的 AnnotatedString 套 remember：想跳过重建就得把 `matches` 整个
            // 列表当 key，那是一次 O(命中数) 的结构比较，乘以屏幕上的几十行反而更贵；
            // 而真正贵的那一步（分词）已经在上面记住了。跳到这里重建的只是些 SpanStyle。
            val annotated = AnnotatedString.Builder(body).apply {
                tokens?.forEach { token ->
                    val from = (token.start - lineStart).coerceIn(0, body.length)
                    val to = (token.end - lineStart).coerceIn(0, body.length)
                    if (from < to) addStyle(styles[token.kind], from, to)
                }
                // 把这一行上的命中**都**标出来，而不只是当前那一处：只读模式正是因为文件太大
                // 才进来的，查找在这里是主要工具，只看得见一处高亮基本等于没有。
                //
                // 可编辑那边只标「当前命中附近 300 处」是因为它整篇只有**一个**
                // AnnotatedString，总量必须封顶；这里每个 item 各自一个，天然按行封顶——
                // 所以限制放在**单行**上（下面那个 MAX_MATCHES_PER_LINE），不是全局窗口。
                var marked = 0
                for (i in onThisLine) {
                    if (marked >= MAX_MATCHES_PER_LINE) break
                    val m = matches[i]
                    // 跨行命中的 start 可能落在更早的行上，尾端也可能伸出截断处，两头都要夹住。
                    // 夹完若为空（命中已完全落在截断部分之后）就跳过。
                    val from = (m.start - lineStart).coerceAtLeast(0)
                    val to = (m.end - lineStart).coerceAtMost(body.length)
                    if (from >= to) continue
                    addStyle(
                        SpanStyle(
                            background = if (i == currentMatch) currentMatchBackground else matchBackground,
                        ),
                        from,
                        to,
                    )
                    marked++
                }
                if (truncated) {
                    // 截断处在末尾留一个底色，让「这里被切了」不只是靠右边那行文字说明
                    addStyle(SpanStyle(background = lineBackground), body.length - 1, body.length)
                }
            }.toAnnotatedString()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (index == currentLine) lineBackground else androidx.compose.ui.graphics.Color.Transparent,
                    ),
            ) {
                Text(
                    text = (index + 1).toString(),
                    style = numberStyle,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(gutterWidth)
                        .padding(end = 8.dp),
                )
                Text(
                    text = annotated,
                    style = bodyStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    // 不换行：软换行要对整行做断行计算，超长单行会因此卡住（见 MAX_LINE_CHARS）
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier
                        .horizontalScroll(hScroll)
                        .padding(start = 4.dp),
                )
                if (truncated) {
                    Text(
                        // 前导间隔不走文案：资源里的首尾空白会被 aapt 裁掉，这里改用 padding，
                        // 三种语言也就自然对齐了。
                        text = stringResource(R.string.readonly_line_truncated, lineLength),
                        style = numberStyle,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}
