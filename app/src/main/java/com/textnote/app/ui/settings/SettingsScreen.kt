package com.textnote.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.textnote.app.R
import com.textnote.app.core.Highlighter
import com.textnote.app.core.LineIndex
import com.textnote.app.core.SyntaxRegistry
import com.textnote.app.data.AppSettings
import com.textnote.app.data.EditorFont
import com.textnote.app.data.EditorFontRanges
import com.textnote.app.data.ThemeMode
import com.textnote.app.ui.editor.rememberHighlightStyles
import com.textnote.app.ui.theme.LocalEditorTextStyle
import com.textnote.app.ui.theme.resolveDarkTheme

/** 预览用的示例。故意三种 token 都有，一眼能看出配色是否还分得开 */
private const val PREVIEW_SOURCE = "val total = 42\n// a comment\nval name = \"TextNote\"\n"
private const val PREVIEW_NAME = "preview.kt"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onFont: (EditorFont) -> Unit,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onBack: () -> Unit,
) {
    // 系统返回键回到上一层（文档或首页），而不是直接退出应用
    BackHandler(onBack = onBack)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { PreviewCard(dark = settings.resolveDarkTheme()) }

            item {
                Section(stringResource(R.string.settings_appearance)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { onThemeMode(mode) },
                                label = { Text(themeLabel(mode)) },
                            )
                        }
                    }
                }
            }

            item {
                SwitchRow(
                    title = stringResource(R.string.dynamic_color_title),
                    description = if (dynamicColorSupported) {
                        stringResource(R.string.dynamic_color_desc)
                    } else {
                        stringResource(R.string.dynamic_color_unsupported)
                    },
                    checked = settings.dynamicColor,
                    enabled = dynamicColorSupported,
                    onToggle = onDynamicColor,
                )
            }

            item {
                Section(stringResource(R.string.settings_text)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditorFont.entries.forEach { font ->
                            FilterChip(
                                selected = settings.font == font,
                                onClick = { onFont(font) },
                                label = { Text(fontLabel(font)) },
                            )
                        }
                    }
                }
            }

            item {
                Section(stringResource(R.string.font_size_label)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditorFontRanges.SIZES.forEach { size ->
                            FilterChip(
                                selected = settings.fontSizeSp == size,
                                onClick = { onFontSize(size) },
                                label = { Text(size.toString()) },
                            )
                        }
                    }
                }
            }

            item {
                Section(stringResource(R.string.line_height_label)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditorFontRanges.LINE_HEIGHTS.forEach { value ->
                            FilterChip(
                                selected = settings.lineHeightMultiplier == value,
                                onClick = { onLineHeight(value) },
                                label = { Text("%.1f".format(value)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 动态取色要 Android 12+。以下系统把它禁用，而不是摆一个按不动的开关 */
private val dynamicColorSupported: Boolean
    get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
    }
}

/** 预览卡：与编辑区共用同一份样式，改字号/行距/字体这里立刻看得见 */
@Composable
private fun PreviewCard(dark: Boolean) {
    val style = LocalEditorTextStyle.current
    val syntax = remember { SyntaxRegistry.forFileName(PREVIEW_NAME) }
    val styles = rememberHighlightStyles(darkTheme = dark)
    val annotated = remember(styles) {
        val index = LineIndex.of(PREVIEW_SOURCE)
        val tokens = Highlighter.highlight(PREVIEW_SOURCE, index, syntax)
        buildAnnotatedString {
            append(PREVIEW_SOURCE)
            tokens.forEach { addStyle(styles[it.kind], it.start, it.end) }
        }
    }

    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = "1\n2\n3",
                style = style.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                ),
            )
            Text(
                text = annotated,
                style = style.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

@Composable
private fun fontLabel(font: EditorFont): String = stringResource(
    when (font) {
        EditorFont.MONOSPACE -> R.string.font_monospace
        EditorFont.SANS -> R.string.font_sans
        EditorFont.SERIF -> R.string.font_serif
    },
)
