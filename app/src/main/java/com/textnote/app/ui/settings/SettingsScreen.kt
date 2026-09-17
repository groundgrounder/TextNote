package com.textnote.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.textnote.app.R
import com.textnote.app.core.Highlighter
import com.textnote.app.core.LineIndex
import com.textnote.app.core.SyntaxRegistry
import com.textnote.app.data.AppSettings
import com.textnote.app.data.EditorFont
import com.textnote.app.data.EditorFontRanges
import com.textnote.app.data.ThemeMode
import com.textnote.app.ui.theme.rememberHighlightStyles
import com.textnote.app.ui.theme.ControlHeight
import com.textnote.app.ui.theme.LocalEditorTextStyle
import com.textnote.app.ui.theme.PillShape
import com.textnote.app.ui.theme.resolveDarkTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** 预览用的示例。故意三种 token 都有，一眼能看出配色是否还分得开 */
private const val PREVIEW_SOURCE = "val total = 42\n// a comment\nval name = \"TextNote\"\n"
private const val PREVIEW_NAME = "preview.kt"

@OptIn(ExperimentalMaterial3Api::class)
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
                    OptionRow(
                        options = ThemeMode.entries,
                        selected = settings.themeMode,
                        label = { themeLabel(it) },
                        onSelect = onThemeMode,
                    )
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
                    OptionRow(
                        options = EditorFont.entries,
                        selected = settings.font,
                        label = { fontLabel(it) },
                        onSelect = onFont,
                    )
                }
            }

            item {
                SteppedSlider(
                    label = stringResource(R.string.font_size_label),
                    valueText = settings.fontSizeSp.toString(),
                    index = EditorFontRanges.SIZES.indexOf(settings.fontSizeSp).coerceAtLeast(0),
                    count = EditorFontRanges.SIZES.size,
                    onChange = { onFontSize(EditorFontRanges.SIZES[it]) },
                )
            }

            item {
                SteppedSlider(
                    label = stringResource(R.string.line_height_label),
                    valueText = "%.1f".format(settings.lineHeightMultiplier),
                    index = EditorFontRanges.LINE_HEIGHTS.toList()
                        .indexOfFirst { abs(it - settings.lineHeightMultiplier) < 0.01f }
                        .coerceAtLeast(0),
                    count = EditorFontRanges.LINE_HEIGHTS.size,
                    onChange = { onLineHeight(EditorFontRanges.LINE_HEIGHTS[it]) },
                )
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
/**
 * 一行等宽的单选项（外观三档、字体三档），铺满整行宽度。
 *
 * 为什么不用默认样式的 `FilterChip`：M3 的 chip 自带 1dp 描边，浅色主题下看着就是一圈「黑边」。
 * 这里去掉描边、改成纯色底（未选中 `surfaceContainerHighest` / 选中 `secondaryContainer`），
 * 与查找面板里那些开关是同一套语汇——**填充而不是描边**。
 */
@Composable
private fun <T> OptionRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = {
                    // chip 的标签默认靠左，等宽之后会显得散，所以自己居中
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                            // 拉丁语的 "Sine unguibus" 比中文长得多，允许折两行，不要省略号
                            maxLines = 2,
                        )
                    }
                },
                border = null,
                // 形状与按钮统一成药丸（口径见 ui/theme/Theme.kt 的 PillShape）
                shape = PillShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ControlHeight),
            )
        }
    }
}

/**
 * 离散取值的滑动条（字号 8 档、行距 5 档）。
 *
 * 滑的是**下标**而不是数值本身：取值集合来自 [EditorFontRanges]，而字号那些档不是等差
 * （12,13,14,15,16,18,20,22），按下标滑才不会出现「能滑到、存下去又被 coerce 掉」的假选项。
 * 当前值放在标题行右端——比塞在滑条旁边省地方，也顺带把标题行铺满。
 */
@Composable
private fun SteppedSlider(
    label: String,
    valueText: String,
    index: Int,
    count: Int,
    onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(0, count - 1)) },
            valueRange = 0f..(count - 1).toFloat(),
            // `steps` 是「两端点之间的刻度数」，不是总档数
            steps = (count - 2).coerceAtLeast(0),
        )
    }
}

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
