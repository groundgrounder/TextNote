package com.textnote.app.spike

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textnote.app.ui.theme.TextNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/** 顶栏显示的对话标签，测量脚本靠它判断「界面是否已经可用」 */
private const val LABEL_PREFIX = "SPIKE:"
private const val TAG = "SpikeKernel"

/**
 * **M4 内核评估用的对照工装，不是产品代码。**
 *
 * 目的只有一个：在同一个 APK、同一台机器、同一份文本上，比较两种 Compose 文本内核的排版代价——
 * - `--es mode value` → `BasicTextField(value, onValueChange)`，也就是 TextNote 当前用的那套；
 * - `--es mode state` → `BasicTextField(state = TextFieldState)`（BasicTextField2）。
 *
 * 要回答的问题是：**状态版是否真的做增量排版**。回答方式只能是量，不能靠读文档——
 * 我在这上面已经栽过两次（`DrawScope.drawText` 不存在、`TextFieldState` 是否存在）。
 *
 * 为了让差异只来自「排版」，这里刻意**什么都不加**：没有语法着色、没有行号栏、
 * 没有搜索面板，纯文本 + 等宽字体 + 垂直滚动。生产代码里那些东西是叠加在同一个基础代价之上的，
 * 先把这个基础代价测清楚才有意义。
 *
 * 另外它**绕过了 `EditorLimits` 的体积守卫**（直接 `readBytes()`）：要测的就是被守卫挡住的
 * 那部分体积。所以别把这个读法抄到生产代码里去。
 */
class SpikeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_VALUE
        val uri = intent.data
        Log.i(TAG, "created mode=$mode uri=$uri")
        setContent {
            TextNoteTheme {
                SpikeScreen(mode = mode, uri = uri)
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        /** 当前生产实现：值驱动 */
        const val MODE_VALUE = "value"
        /** 候选一：状态驱动（BasicTextField2）。实测证明它不是增量排版 */
        const val MODE_STATE = "state"
        /** 候选二：按行懒加载的**只读**渲染器，"只排版可视区" */
        const val MODE_LINES = "lines"
        /**
         * 候选二的变体：不换行（`softWrap = false` + `maxLines = 1`）。
         *
         * 起因是发现 `lines` 有个洞：整份文件只有一行、且长达几 MB 时（压缩过的 JSON / minified JS），
         * 那一个 item 就是一个巨型段落，照样卡死。怀疑软换行要对整行做断行计算才贵，
         * 而不换行只需要量一次宽度——如果是这样，这个变体就能补上那个洞。
         */
        const val MODE_LINES_NOWRAP = "linesnowrap"
    }
}

@Composable
private fun SpikeScreen(mode: String, uri: Uri?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var loaded by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        val target = uri ?: return@LaunchedEffect
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val text = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(target)?.use { it.readBytes() }
                    ?.toString(Charset.forName("UTF-8"))
            }.getOrNull()
        }
        Log.i(
            TAG,
            "read ${text?.length ?: -1} chars in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
        )
        loaded = text
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = "$LABEL_PREFIX mode=$mode chars=${loaded?.length ?: -1}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            val text = loaded
            when {
                text == null -> Text("loading…", Modifier.padding(16.dp))
                mode == SpikeActivity.MODE_STATE -> StateField(text)
                mode == SpikeActivity.MODE_LINES -> LinesField(text, softWrap = true)
                mode == SpikeActivity.MODE_LINES_NOWRAP -> LinesField(text, softWrap = false)
                else -> ValueField(text)
            }
        }
    }
}

/**
 * 候选二：按行懒加载的**只读**渲染器。
 *
 * 思路：`BasicTextField` 的代价来自「把整篇文本交给一个 MultiParagraph」，
 * 那么只要不交给它、改成每行一个独立的 `Text` 让 `LazyColumn` 按需组合，
 * 就天然只排版可视区——代价与文件大小脱钩。
 *
 * 代价是**不能编辑**：没有光标、没有输入法。这不是偷懒，而是刻意的次序——
 * 自定义渲染 + 自管 InputConnection（组合输入、选择手柄、光标）是 sora-editor 那个量级的工程，
 * 而「只读浏览」把最难的那部分整个绕开了，能先把「大文件至少能看」这件事落地。
 *
 * 顺带一提，语法着色在这个结构里反而变便宜了：`Highlighter` 本来就是按行分词的，
 * 每行单独标注，不用再拼一个整篇的 AnnotatedString。
 */
@Composable
private fun LinesField(text: String, softWrap: Boolean) {
    // 只切一次；LazyColumn 按需组合，所以这里的 O(n) 是可接受的
    val lines = remember(text) { text.split('\n') }
    // 不换行时给所有行共用一个横向滚动状态，长行才能滚着看
    val hScroll = rememberScrollState()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(count = lines.size, key = { it }) { index ->
            Text(
                text = lines[index],
                style = SPIKE_TEXT_STYLE,
                softWrap = softWrap,
                maxLines = if (softWrap) Int.MAX_VALUE else 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (softWrap) Modifier else Modifier.horizontalScroll(hScroll))
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

/** 当前生产实现：值驱动 */
@Composable
private fun ValueField(text: String) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(text) { value = TextFieldValue(text) }
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        textStyle = SPIKE_TEXT_STYLE,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
}

/**
 * 待评估：状态驱动。
 *
 * 用 [TextFieldState] + `edit {}` 而不是构造时传文本，是因为文本是异步读进来的；
 * 而且 `edit {}` 正是这个 API 的常规写入口，测量时不该绕开它。
 */
@Composable
private fun StateField(text: String) {
    val state: TextFieldState = rememberTextFieldState()
    LaunchedEffect(text) {
        state.edit { replace(0, length, text) }
    }
    BasicTextField(
        state = state,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        textStyle = SPIKE_TEXT_STYLE,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
}

/** 与生产同一份文字样式，避免字体度量差异混进测量结果 */
private val SPIKE_TEXT_STYLE = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 15.sp,
    lineHeight = 21.sp,
)
