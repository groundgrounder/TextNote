package com.textnote.app.ui.editor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicTextField
import com.textnote.app.R
import com.textnote.app.core.HighlightToken
import com.textnote.app.core.LineIndex
import com.textnote.app.core.formatBytes
import com.textnote.app.core.SearchMatch
import com.textnote.app.core.SyntaxRegistry
import com.textnote.app.data.OpenResult
import com.textnote.app.data.TextEncoding
import com.textnote.app.ui.theme.HighlightStyles
import com.textnote.app.ui.theme.LocalEditorTextStyle
import com.textnote.app.ui.theme.rememberHighlightStyles
import kotlinx.coroutines.launch

/**
 * 最多给多少处命中加背景高亮。
 *
 * 命中上限是一万（[com.textnote.app.core.SearchEngine.MAX_MATCHES]），全标就是一万个
 * SpanStyle，按 M2 的实测（span 数量是主要开销）那会把每次编辑都拖垮。
 * 只标 300 处，且以当前命中为中心取窗口——见 [EditorField] 里的说明。
 */
private const val MAX_VISIBLE_MATCHES = 300

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    /**
     * 此刻是否为深色主题。**必须由调用方按设置算出来传进来**，不能在这里读
     * `isSystemInDarkTheme()`：那样用户在设置里选了深色、而系统停在浅色时，
     * 背景会按设置变深、语法色却按系统留在亮色那套，深色文字压在深色背景上基本看不见。
     */
    darkTheme: Boolean,
    onClose: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.saved)
    val failedMessage = stringResource(R.string.save_failed)
    val defaultFileName = stringResource(R.string.untitled)
    var overflow by remember { mutableStateOf(false) }

    /**
     * 另存为的位置选择。放在编辑器这一层而不是 MainActivity：选完之后要在**这里**弹提示
     * （成功 / 失败用的是和保存同一套文案），而提示靠的是这一层持有的 Snackbar。
     */
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = viewModel.saveAs(uri)
                snackbar.showSnackbar(if (ok) savedMessage else failedMessage)
            }
        }
    }
    val highlightStyles = rememberHighlightStyles(darkTheme = darkTheme)
    // 匹配高亮用主题色而不是写死的黄色：黄色在深色主题和动态取色下都会脏。
    // 用半透明是因为它要盖在语法色之上又不能把字的颜色吃掉。
    val matchBackground = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
    val currentMatchBackground = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)

    // 系统返回键分两级：查找面板开着时先收起它，否则回到首页。
    // 少了前面那一级，面板开着按返回会直接退到首页——面板是浮在文档上的一层，
    // 用户按返回想收起的是它，结果文档一起没了（还要重新打开一次）。
    // 面板之外仍是「退出文档而不是退出应用」：用户在这一层的心智是「文档里」。
    BackHandler {
        if (viewModel.search.visible) viewModel.closeSearch() else onClose()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        // 交给 TopAppBar / bottomBar 各自处理，避免 systemBars 被重复算进内容 padding
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_to_files),
                        )
                    }
                },
                title = {
                    Text(
                        // 有未保存改动时加一个「•」，比另做一个编辑中标签更不占地方
                        text = if (viewModel.dirty) "• ${viewModel.fileName}" else viewModel.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        // 顶栏的高度是按一行标题算的，放任长文件名换行会折成好几行，
                        // 而文字块仍在容器里垂直居中——于是上下都溢出，跟左边的返回箭头、
                        // 右边的图标全对不上，看起来就是「标题错位」。文件名也没有换行显示的必要。
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(
                        onClick = viewModel::undo,
                        enabled = viewModel.canUndo,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(R.string.undo),
                        )
                    }
                    IconButton(
                        onClick = viewModel::redo,
                        enabled = viewModel.canRedo,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = stringResource(R.string.redo),
                        )
                    }
                    IconButton(
                        onClick = viewModel::openSearch,
                        // 没有可搜的正文时（读不到 / 读不动 / 还在读）不给入口：
                        // 搜索面板会显示「0 处命中」，那是在报一份并不存在的文档
                        enabled = !viewModel.loading &&
                            !viewModel.search.visible &&
                            !viewModel.unavailable &&
                            viewModel.tooLarge == null,
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                val ok = viewModel.save()
                                snackbar.showSnackbar(if (ok) savedMessage else failedMessage)
                            }
                        },
                        enabled = viewModel.writable &&
                            !viewModel.loading &&
                            !viewModel.readOnly &&
                            !viewModel.unavailable &&
                            viewModel.tooLarge == null,
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.save))
                    }
                    // 设置与「另存为」收进溢出菜单：顶栏已经有撤销/重做/查找/保存四个图标，
                    // 再加两个会把标题挤到只剩几个字。设置同时还在首页，收进来损失最小。
                    Box {
                        IconButton(onClick = { overflow = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.more_actions),
                            )
                        }
                        DropdownMenu(
                            expanded = overflow,
                            onDismissRequest = { overflow = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.save_as)) },
                                // 只读（文档大得不能编辑）时没有「存到别处」的意义：内容改不了，
                                // 存出去还是一份只能看的文件。
                                enabled = !viewModel.readOnly &&
                                    !viewModel.loading &&
                                    !viewModel.unavailable &&
                                    viewModel.tooLarge == null,
                                onClick = {
                                    overflow = false
                                    // 拿当前文件名当建议名，用户改个后缀就能换语法
                                    saveAsLauncher.launch(
                                        viewModel.fileName.ifBlank { defaultFileName },
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_title)) },
                                onClick = {
                                    overflow = false
                                    onSettingsClick()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (viewModel.search.visible) {
                    val replacedMessage = stringResource(R.string.search_replaced)
                    SearchPanel(
                        search = viewModel.search,
                        // 只读模式下没有替换这回事，把那一行整行去掉，别摆出按不动的按钮
                        allowReplace = !viewModel.readOnly,
                        onClose = viewModel::closeSearch,
                        onNext = viewModel::findNext,
                        onPrev = viewModel::findPrev,
                        // 单处替换不需要提示：替换的结果直接就在眼前，弹提示反而是噪音
                        onReplace = { viewModel.replaceCurrent() },
                        onReplaceAll = {
                            val count = viewModel.replaceAll()
                            scope.launch { snackbar.showSnackbar(replacedMessage.format(count)) }
                        },
                    )
                }
                // 没有装载正文时不要显示状态栏：那时它会报「0 字符 · 1 行」这种
                // 关于一份并不存在的文档的假信息，比空着更糟。三种「没有正文」都要挡住：
                // 读不动（tooLarge）、读不到（unavailable）、还在读（loading）。
                if (viewModel.tooLarge == null && !viewModel.unavailable && !viewModel.loading) {
                    StatusBar(viewModel)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (viewModel.externalChange != null) {
                // 放最上面：这是「你正在看的这份内容可能已经过期了」，比其他提示都要紧
                ExternalChangeBanner(
                    change = viewModel.externalChange!!,
                    readOnly = viewModel.readOnly,
                    onReload = viewModel::reloadFromDisk,
                    onKeep = viewModel::keepLocalVersion,
                )
            }
            if (viewModel.pendingDraft != null) {
                DraftBanner(
                    onRestore = viewModel::restoreDraft,
                    onDiscard = viewModel::discardDraft,
                )
            }
            if (viewModel.readOnly) {
                NoticeBanner(
                    text = stringResource(
                        R.string.document_read_only,
                        viewModel.field.text.length,
                        viewModel.editLimitChars,
                    ),
                )
            } else if (viewModel.slowDocument) {
                NoticeBanner(
                    text = stringResource(
                        R.string.document_slow,
                        viewModel.field.text.length,
                    ),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    viewModel.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    viewModel.tooLarge != null -> TooLargeNotice(
                        info = viewModel.tooLarge!!,
                        limitBytes = viewModel.readLimitBytes,
                        // 收尾统一交给外面的 onClose：它还要落草稿、清状态、刷新列表
                        onClose = onClose,
                    )
                    viewModel.unavailable -> UnavailableNotice()
                    viewModel.readOnly -> ReadOnlyReader(
                        text = viewModel.field.text,
                        // 与正文同源的那份行索引（`derivedStateOf` 缓存）。不传进来它就得自己
                        // `LineIndex.of(text)` 再扫一遍全篇——而这里正是一次 2MB 的首次组合。
                        lineIndex = viewModel.lineIndex,
                        syntax = viewModel.syntax,
                        styles = highlightStyles,
                        matches = viewModel.search.matches,
                        currentMatch = viewModel.search.currentIndex,
                        scrollToLine = viewModel.scrollToLine,
                        onScrolledToLine = viewModel::consumeScrollToLine,
                        matchBackground = matchBackground,
                        currentMatchBackground = currentMatchBackground,
                    )
                    else -> EditorField(
                        value = viewModel.field,
                        onValueChange = viewModel::onFieldChange,
                        onUndo = viewModel::undo,
                        onRedo = viewModel::redo,
                        lineIndex = viewModel.lineIndex,
                        tokens = viewModel.tokens,
                        styles = highlightStyles,
                        matches = viewModel.search.matches,
                        currentMatch = viewModel.search.currentIndex,
                        matchBackground = matchBackground,
                        currentMatchBackground = currentMatchBackground,
                        scrollToOffset = viewModel.scrollToOffset,
                        onScrolledToOffset = viewModel::consumeScrollRequest,
                    )
                }
            }
        }
    }
}

/**
 * 编辑区：行号栏 + 等宽文本区。
 *
 * 行号栏放在滚动容器**外面**，自己按滚动偏移做反向位移。若把它塞进同一个 verticalScroll，
 * 横向滚动时行号会跟着跑掉，而且两层滚动容器嵌套会让 bring-into-view（光标滚入视野）失效。
 *
 * 语法着色走 [VisualTransformation] 而不是直接给 [BasicTextField] 传带 span 的
 * `TextFieldValue`：这样 `viewModel.field.text` 始终是**纯文本**，行索引、搜索、草稿、
 * 保存全都只需要处理一种形态，着色只是渲染层的事。映射是 Identity，不改变任何字符。
 */
@Suppress("LongParameterList")
@Composable
private fun EditorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    lineIndex: LineIndex,
    tokens: List<HighlightToken>,
    styles: HighlightStyles,
    matches: List<SearchMatch>,
    currentMatch: Int,
    matchBackground: Color,
    currentMatchBackground: Color,
    scrollToOffset: Int?,
    onScrolledToOffset: () -> Unit,
) {
    val placeholder = stringResource(R.string.editor_placeholder)
    // 从主题里取正文样式：行号栏要用同一份量行高，两侧的字体与行距必须完全一致
    val bodyStyle = LocalEditorTextStyle.current
    val scrollState = rememberScrollState()
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeight by remember { mutableIntStateOf(0) }

    val transformation = remember(value.text, tokens, styles, matches, currentMatch) {
        if (tokens.isEmpty() && matches.isEmpty()) {
            VisualTransformation.None
        } else {
            val annotated = AnnotatedString.Builder(value.text).apply {
                tokens.forEach { token -> addStyle(styles[token.kind], token.start, token.end) }
                // 匹配高亮放在语法色之后叠加。背景与前景色是 SpanStyle 的不同字段，
                // 叠加不会互相覆盖（上机验证过：命中处仍保留各自语法色）。
                //
                // 只标一段窗口而不是全部：命中上限一万，全标会把每次编辑都拖垮。
                // 窗口以**当前命中**为中心而不是从头数——用户按「下一处」往前走，
                // 视野就在当前命中附近；固定取前 300 处会在滚到文件深处时一个高亮都看不到。
                val start = if (currentMatch < 0) {
                    0
                } else {
                    (currentMatch - MAX_VISIBLE_MATCHES / 2).coerceAtLeast(0)
                }
                val end = minOf(matches.size, start + MAX_VISIBLE_MATCHES)
                for (i in start until end) {
                    val m = matches[i]
                    // 命中列表可能比正文滞后一个防抖周期（见 EditorViewModel.searchText），
                    // 那段窗口里偏移量是针对旧文本算的。越界的 addStyle 会抛异常，跳过即可——
                    // 150ms 之后自然会对上。
                    if (m.start >= value.text.length || m.end > value.text.length) continue
                    addStyle(
                        SpanStyle(
                            background = if (i == currentMatch) currentMatchBackground else matchBackground,
                        ),
                        m.start,
                        m.end,
                    )
                }
            }.toAnnotatedString()
            VisualTransformation { TransformedText(annotated, OffsetMapping.Identity) }
        }
    }

    // 把目标位置滚进视野。Compose 只在 BasicTextField 有焦点时自动滚，
    // 而查找时焦点在查找框，所以这一步必须自己做。
    LaunchedEffect(scrollToOffset, layoutResult) {
        val offset = scrollToOffset ?: return@LaunchedEffect
        val layout = layoutResult ?: return@LaunchedEffect
        val line = layout.getLineForOffset(offset.coerceIn(0, value.text.length))
        val top = layout.getLineTop(line).toInt()
        // 停在视口上三分之一处：正好贴顶的话看不出上下文
        scrollState.animateScrollTo((top - viewportHeight / 3).coerceAtLeast(0))
        onScrolledToOffset()
    }

    Row(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeight = it.height },
    ) {
        LineNumberGutter(
            lineIndex = lineIndex,
            layoutResult = layoutResult,
            scrollState = scrollState,
            textStyle = bodyStyle,
            modifier = Modifier.fillMaxHeight(),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            onTextLayout = { layoutResult = it },
            visualTransformation = transformation,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 8.dp)
                .verticalScroll(scrollState)
                // 硬件键盘的 Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y。挂在编辑区而不是整页：
                // 焦点在查找框时按 Ctrl+Z 应该撤销查找框里的输入，不是动正文。
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onKeyEvent false
                    when (event.key) {
                        Key.Z -> {
                            if (event.isShiftPressed) onRedo() else onUndo()
                            true
                        }
                        // Ctrl+Y 是 Windows 上的重做，顺手认下
                        Key.Y -> {
                            onRedo()
                            true
                        }
                        else -> false
                    }
                },
            textStyle = bodyStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                if (value.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = bodyStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
                inner()
            },
        )
    }
}
