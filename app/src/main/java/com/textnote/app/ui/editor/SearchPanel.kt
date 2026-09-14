package com.textnote.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.textnote.app.R
import kotlinx.coroutines.delay

/**
 * 查找 / 替换面板（贴在状态栏上方）。
 *
 * 三行的分工是按**使用频率**排的：第一行只放关键词（最常用），第二行是开关与跳转，
 * 第三行才是替换（多数时候只是查找，不需要永远占着高度）。
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

    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(
                    value = search.query,
                    onValueChange = { search.updateQuery(it) },
                    placeholder = stringResource(R.string.search_query_hint),
                    onDone = onNext,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .focusRequester(focusRequester),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.search_close),
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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

                Text(
                    text = statusText(search),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (search.result.invalidPattern) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )

                FilledTonalIconButton(
                    onClick = onPrev,
                    enabled = search.matches.isNotEmpty(),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.search_prev),
                    )
                }
                FilledTonalIconButton(
                    onClick = onNext,
                    enabled = search.matches.isNotEmpty(),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.search_next),
                    )
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
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
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
 * 用 `OutlinedTextField` 的默认装饰会带 56dp 的最小高度和一整套内边距，三行叠起来太高，
 * 所以这里用 `textFieldColors` 去掉指示线并收紧 padding，靠 [Modifier.heightIn] 压到 44dp。
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium)
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true,
        maxLines = 1,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun ToggleChip(
    text: String,
    active: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.size(40.dp),
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
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}
