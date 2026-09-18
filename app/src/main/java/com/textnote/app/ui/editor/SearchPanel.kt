package com.textnote.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.textnote.app.R
import com.textnote.app.ui.theme.ControlHeight
import com.textnote.app.ui.theme.PanelTopShape
import com.textnote.app.ui.theme.PillShape
import com.textnote.app.ui.theme.isWideScreen
import kotlinx.coroutines.delay

/**
 * 查找 / 替换面板（贴在状态栏上方）。
 *
 * 分工按**使用频率**排：关键词最常用，开关与跳转次之，替换多数时候用不上、不该常占高度。
 * 窄屏竖着堆成三行；**宽屏把前两行并成一行**——横屏的平板本来就只有 800dp 高，软键盘一弹
 * 再吃掉四成，三行面板会把编辑区压到只剩几行可见（见 [isWideScreen] 与两处分支的注释）。
 * 开关一律用 `Aa` `.*` `ab|` 这种字面标签而不是图标——图标对同一含义的画法在不同
 * 图标集里不统一，而这三个字符在编辑器语境里几乎不需要翻译。
 */
@Composable
fun SearchPanel(
    search: EditorSearch,
    allowReplace: Boolean,
    onClose: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
) {
    // 打开面板就把光标放进关键词框并弹键盘：用户点「查找」的下一步必然是要输入，
    // 不自动聚焦就得多点一次，而且容易误输到替换框里。
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 输入停顿之后才真正去搜。这不是为了省电——是为了不在主线程上把 N 次全文正则扫描
    // 排成一队（实测在 2MB 只读文件上那样会直接 ANR，详见 EditorSearch.query 的说明）。
    LaunchedEffect(search.query) {
        delay(search.debounceMillis)
        search.commitQuery()
    }

    // 形状取自主题：只圆上沿、下沿与状态栏接平（半径口径统一在 ui/theme/Theme.kt 里）
    Surface(
        tonalElevation = 3.dp,
        shape = PanelTopShape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 顶边比底边多留一截：28dp 的弧在 y=6dp 处已经把左边界推到 10dp 边上，
                // 而字段是从 12dp 起的，留 6dp 会让它看着贴住那道弧。
                .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 宽屏把原来的一、二两行并成一行。横屏缺的是**高度**不是宽度：800dp 高的平板上
            // 软键盘一弹就吃掉四成屏高，三行面板再把编辑区压到只剩四行可见。
            // 窄屏保持竖排——那边反过来，宽度不够并排摆下这么多控件。
            if (isWideScreen()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 关键词框照旧吃掉全部富余宽度，不另外限宽：正则、路径这类关键词本来就长，
                    // 横屏给得起。右边那组控件宽度是固定的，所以它的位置不会随输入抖动。
                    QueryInput(search, focusRequester, onNext, Modifier.weight(1f))
                    FindToggles(search)
                    SearchStatus(search, Modifier.widthIn(min = 88.dp))
                    FindStepper(search, onNext = onNext, onPrev = onPrev)
                    CloseSearchButton(onClose)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QueryInput(search, focusRequester, onNext, Modifier.weight(1f))
                    CloseSearchButton(onClose)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FindToggles(search)
                    SearchStatus(search, Modifier.weight(1f))
                    FindStepper(search, onNext = onNext, onPrev = onPrev)
                }
            }

            if (allowReplace) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SearchField(
                        value = search.replaceText,
                        onValueChange = { search.updateReplaceText(it) },
                        placeholder = stringResource(R.string.search_replace_hint),
                        onDone = onReplace,
                        modifier = Modifier.weight(1f),
                    )
                    ActionChip(
                        text = stringResource(R.string.search_replace),
                        enabled = search.currentIndex >= 0,
                        onClick = onReplace,
                    )
                    ActionChip(
                        text = stringResource(R.string.search_replace_all),
                        enabled = search.matches.isNotEmpty(),
                        onClick = onReplaceAll,
                    )
                }
            }
        }
    }
}

/**
 * 关键词输入框。两套排布都要用它，所以连「焦点必须挂在输入框本身」那条说明一起收在这里。
 */
@Composable
private fun QueryInput(
    search: EditorSearch,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    SearchField(
        value = search.query,
        onValueChange = { search.updateQuery(it) },
        placeholder = stringResource(R.string.search_query_hint),
        onDone = onDone,
        modifier = modifier,
        // 焦点要交给**输入框本身**：挂在外面那个 Box 上（它不可聚焦）什么也不会发生，
        // 自动聚焦与弹键盘就静默失效了。
        focusRequester = focusRequester,
    )
}

@Composable
private fun CloseSearchButton(onClose: () -> Unit) {
    IconButton(onClick = onClose, modifier = Modifier.size(ControlHeight)) {
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.search_close),
        )
    }
}

/**
 * 正则 / 大小写 / 全词三个开关。
 *
 * 写成 [RowScope] 扩展而不是自带一个 `Row`：两套排布里它和邻居的间距口径不同
 * （窄屏 4dp、宽屏 6dp），让调用方的那个 `Row` 统一管，这里只负责摆出三个开关。
 */
@Composable
private fun RowScope.FindToggles(search: EditorSearch) {
    ToggleChip(
        text = ".*",
        active = search.regexMode,
        description = stringResource(R.string.search_regex),
        onClick = { search.toggleRegex() },
    )
    ToggleChip(
        text = "Aa",
        active = search.caseSensitive,
        description = stringResource(R.string.search_case),
        onClick = { search.toggleCaseSensitive() },
    )
    ToggleChip(
        text = "ab|",
        active = search.wholeWord,
        description = stringResource(R.string.search_word),
        onClick = { search.toggleWholeWord() },
    )
}

/** 命中数 / 当前位置。窄屏靠 weight 把这一行撑开居中，宽屏只给最小宽度免得数字一二位变化时左右晃。 */
@Composable
private fun RowScope.SearchStatus(search: EditorSearch, modifier: Modifier) {
    Text(
        text = statusText(search),
        style = MaterialTheme.typography.labelMedium,
        color = if (search.result.invalidPattern) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
        // 宽屏那一行里全是并列控件，这段文字一旦换行就会把整条面板撑高——省高度正是这次改动的目的
        maxLines = 1,
        modifier = modifier,
    )
}

/** 上一处 / 下一处。 */
@Composable
private fun RowScope.FindStepper(search: EditorSearch, onNext: () -> Unit, onPrev: () -> Unit) {
    FilledTonalIconButton(
        onClick = onPrev,
        enabled = search.matches.isNotEmpty(),
        modifier = Modifier.size(ControlHeight),
    ) {
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = stringResource(R.string.search_prev),
        )
    }
    FilledTonalIconButton(
        onClick = onNext,
        enabled = search.matches.isNotEmpty(),
        modifier = Modifier.size(ControlHeight),
    ) {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(R.string.search_next),
        )
    }
}

@Composable
private fun statusText(search: EditorSearch): String = when {
    search.query.isEmpty() -> ""
    search.result.invalidPattern -> stringResource(R.string.search_invalid_regex)
    search.matches.isEmpty() -> stringResource(R.string.search_no_match)
    search.currentIndex < 0 -> stringResource(
        R.string.search_count,
        search.matches.size,
        if (search.result.truncated) "+" else "",
    )
    else -> stringResource(R.string.search_position, search.currentIndex + 1, search.matches.size)
}

/**
 * 查找/替换输入框。
 *
 * 为什么不用 `OutlinedTextField`：它的内部下限是 **56dp**，而且那是 16+行高+16 的固定内边距撑出来的，
 * 光去掉指示线（原做法）根本不减高度；硬用 `Modifier.height(44.dp)` 压下去会把文字**裁掉一截**
 * （实测占位文字底部被切）。这里改成自己画容器：高度、内边距、圆角都说了算，也顺手把
 * 「把 filled 的配色传给 OutlinedTextField」这个混用去掉了。
 *
 * 形状与按钮统一成药丸（口径见 `ui/theme/Theme.kt` 的 `PillShape`）。
 * 曾经为了区分「能输入」和「点一下就执行」特意把它收成 16dp 圆角，作者要求控件形状统一成药丸后取消——
 * 现在靠占位文字、光标与各自的宽度来区分，不再靠形状。
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Box(
        modifier = modifier
            .height(ControlHeight)
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
            textStyle = MaterialTheme.typography.bodyMedium
                .copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onDone() }),
        )
    }
}

@Composable
private fun ToggleChip(
    text: String,
    active: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.size(ControlHeight), contentAlignment = Alignment.Center) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier
                .size(ControlHeight)
                // 按钮上的字面标签（`.*` `Aa` `ab|`）对读屏没有意义，描述得单独挂上去。
                .semantics { contentDescription = description },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = if (active) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ),
        ) {
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ActionChip(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
        modifier = Modifier.height(ControlHeight),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
