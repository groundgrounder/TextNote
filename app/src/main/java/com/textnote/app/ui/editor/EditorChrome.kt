package com.textnote.app.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.textnote.app.R
import com.textnote.app.core.formatBytes
import com.textnote.app.core.SyntaxRegistry
import com.textnote.app.data.OpenResult
import com.textnote.app.data.TextEncoding

/**
 * 编辑器界面里的**叶子组件**：草稿横幅、外部改动横幅、状态栏、语法芯片、两种失败页
 * （不可用 / 过大）。它们只拿参数或只读 viewModel，不认识编辑器内部的主线逻辑。
 *
 * 放在这里是为了让 `EditorScreen.kt` 只剩「整屏布局 + 编辑区」那一条主线；
 * 这些组件加起来的行数比主线还多，混在一起会把主线淹掉。
 */


/** 上次编辑留下的草稿。只提示、不自动套用——猜错了就是用户的内容没了。 */
@Composable
internal fun DraftBanner(
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.draft_found),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRestore) { Text(stringResource(R.string.restore)) }
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard)) }
        }
    }
}

/** 纯提示条：把「为什么会卡」写在界面上，比让用户猜是应用崩了要好。 */
@Composable
internal fun NoticeBanner(text: String) {
    Surface(tonalElevation = 1.dp) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * 原文件在别处被改过（或被删了）的提示。
 *
 * 不自动重载：内存里可能有未保存的改动，自动套用磁盘版本等于替用户丢掉内容。
 * 用 errorContainer 上色，因为这是唯一「你现在看到的可能已经不是真内容」的提示。
 */
@Composable
internal fun ExternalChangeBanner(
    change: ExternalChange,
    readOnly: Boolean,
    onReload: () -> Unit,
    onKeep: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    when (change) {
                        ExternalChange.Modified -> R.string.external_change_modified
                        ExternalChange.Gone -> R.string.external_change_gone
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            val actionColors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            // 文件都没了的时候没有「重新加载」这回事，只给一个确认
            if (change == ExternalChange.Modified) {
                TextButton(onClick = onReload, colors = actionColors) {
                    Text(stringResource(R.string.external_change_reload))
                }
            }
            TextButton(onClick = onKeep, colors = actionColors) {
                Text(
                    stringResource(
                        // 只读浏览时不存在「我的版本」，写「保留我的」只会让人困惑
                        if (readOnly || change == ExternalChange.Gone) {
                            R.string.external_change_dismiss
                        } else {
                            R.string.external_change_keep
                        },
                    ),
                )
            }
        }
    }
}

/** 底部状态栏：编码、行尾、语法、光标位置、字数与行数——CotEditor 式的常驻文档信息 */
@Composable
internal fun StatusBar(viewModel: EditorViewModel) {
    val index = viewModel.lineIndex
    val charCount = viewModel.field.text.length

    Surface(
        tonalElevation = 2.dp,
        // IME 抬起时状态栏要跟着上去，否则正好被键盘盖住
        modifier = Modifier.imePadding().navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusText(TextEncoding.displayName(viewModel.encoding))
            StatusText(
                // 混合行尾时标出来：此时保存必然改动一部分行，用户有权知道
                if (viewModel.mixedEndings) "${viewModel.lineEnding.name}*" else viewModel.lineEnding.name,
            )
            SyntaxChip(viewModel)
            if (viewModel.readOnly) {
                // 只读时没有光标，行列没有意义；改成一个明确的「只读」，别让用户白按键盘
                StatusText(stringResource(R.string.read_only))
            } else {
                val cursor = viewModel.field.selection.start.coerceIn(0, charCount)
                StatusText(
                    stringResource(
                        R.string.line_column,
                        index.lineOf(cursor) + 1,
                        index.columnOf(cursor) + 1,
                    ),
                )
            }
            StatusText(stringResource(R.string.stats_format, charCount, index.lineCount))
        }
    }
}

/**
 * 状态栏里的语法名，点开可以手动指定语法。
 *
 * 入口放在状态栏而不是顶栏菜单：语法是**当前文档**的属性（编码、行尾、行列号都在这里），
 * 而顶栏那几个按钮是动作。混在一起会让「改设置」和「对文档做点什么」失去区别。
 */
@Composable
internal fun SyntaxChip(viewModel: EditorViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val suppressed = viewModel.highlightSuppressed
    val name = viewModel.syntax.displayName
    val auto = viewModel.syntaxAuto
    val autoDescription = stringResource(R.string.syntax_desc_auto, name)
    val manualDescription = stringResource(R.string.syntax_desc_manual, name)

    Box {
        Text(
            text = if (suppressed) "$name*" else name,
            style = MaterialTheme.typography.labelSmall,
            // 手动指定的用强调色，自动识别的用次要色。状态栏挤不下第二个字/图标，
            // 所以「是不是手选的」靠颜色区分，并在 contentDescription 里说清楚。
            color = if (auto) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .clickable { expanded = true }
                .semantics {
                    contentDescription = if (auto) autoDescription else manualDescription
                },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.syntax_auto)) },
                onClick = {
                    viewModel.selectSyntax(null)
                    expanded = false
                },
                // 「自动」也要能看出它现在生效，否则用户会以为什么都没选
                trailingIcon = if (auto) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
            )
            SyntaxRegistry.picker.forEach { syntax ->
                DropdownMenuItem(
                    text = { Text(syntax.displayName) },
                    onClick = {
                        viewModel.selectSyntax(syntax)
                        expanded = false
                    },
                    trailingIcon = if (!auto && syntax.id == viewModel.syntax.id) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
internal fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun UnavailableNotice() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.document_unavailable_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/**
 * 连只读都读不动的体积。
 *
 * 只剩「字节数超限」这一种情况了：体积大到读进内存就崩，所以根本上不了查看器。
 * 界面只需要说清两件事——**这个文件有多大**、**多大以内能打开**。
 */
@Composable
internal fun TooLargeNotice(
    info: OpenResult.TooLarge,
    limitBytes: Long,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = stringResource(R.string.document_too_large_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                // 只说「大于」：拿不到 provider 声明的体积时，这里报的是读取上限而不是真实大小
                text = stringResource(
                    R.string.document_too_large_message,
                    formatBytes(info.bytes),
                    formatBytes(limitBytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.document_too_large_reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.document_too_large_back))
            }
        }
    }
}
