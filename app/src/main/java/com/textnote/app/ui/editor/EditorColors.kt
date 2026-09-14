package com.textnote.app.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.textnote.app.core.TokenKind

/**
 * 语义 token → 具体 SpanStyle。
 *
 * ## 为什么不用主题色
 *
 * 语法色要的是**彼此可区分的一组色相**（注释灰绿、字符串绿、关键字紫、数字蓝…）。
 * Material 的 colorScheme 只有 primary/secondary/tertiary 几个语义槽，凑不出十几种还要互相
 * 分得开的颜色，动态取色（壁纸色）更会让某两个 token 撞成同一个颜色。所以亮/暗两套写死，
 * 代价是不跟随取色，收益是任何主题下都可读。
 *
 * ## 为什么几乎不用粗体/斜体（实测结论，别改回去）
 *
 * 在 60K 字符的 Kotlin 文件上测「一次输入的中位帧耗时」（emulator-5554）：
 *
 * | 配置 | 中位 |
 * |---|---|
 * | span 带 FontWeight / FontStyle（最初写法） | 450ms |
 * | span 只用颜色 | **250ms** |
 * | .kt 内容几乎不产生 span | 117ms |
 * | 完全没有 span（.txt） | 101ms |
 *
 * 结论有两条：
 * 1. **开销来自 span 数量**，而不是 VisualTransformation 这条路径——后两行相差无几；
 * 2. **带字重/斜体的 span 是纯色 span 的两倍多**：Android 要为每个变体做字体合成或回退，
 *    等宽字体上尤其贵。
 *
 * 所以规则是：**字体变体只留给出现频率极低的 token**。标题与链接在文档里就那么几处，
 * 保留粗体/下划线既有用又几乎不花钱；注释和关键字每几行就出现一次，绝不给它们加斜体/字重。
 *
 * 另外斜体还是个对齐隐患：等宽斜体的字形宽度未必与正体一致，而行号栏是按正体量的宽度。
 */
@Composable
fun rememberHighlightStyles(darkTheme: Boolean): HighlightStyles {
    return remember(darkTheme) { if (darkTheme) Dark else Light }
}

class HighlightStyles(
    private val map: Map<TokenKind, SpanStyle>,
) {
    private val default: SpanStyle = SpanStyle()
    operator fun get(kind: TokenKind): SpanStyle = map[kind] ?: default
}

private val Light = HighlightStyles(
    mapOf(
        TokenKind.COMMENT to SpanStyle(color = Color(0xFF4A7A52)),
        TokenKind.STRING to SpanStyle(color = Color(0xFF0B6A3A)),
        TokenKind.ESCAPE to SpanStyle(color = Color(0xFF9A4B00)),
        TokenKind.NUMBER to SpanStyle(color = Color(0xFF0B4F9E)),
        TokenKind.KEYWORD to SpanStyle(color = Color(0xFF7A1FA2)),
        TokenKind.TYPE to SpanStyle(color = Color(0xFF00695C)),
        TokenKind.BUILTIN to SpanStyle(color = Color(0xFF8A4B00)),
        TokenKind.FUNCTION to SpanStyle(color = Color(0xFF1A5FB4)),
        TokenKind.PROPERTY to SpanStyle(color = Color(0xFF3B4C9E)),
        TokenKind.TAG to SpanStyle(color = Color(0xFFB3261E)),
        TokenKind.ATTRIBUTE to SpanStyle(color = Color(0xFF825500)),
        TokenKind.PUNCTUATION to SpanStyle(color = Color(0xFF5F6368)),
        TokenKind.HEADING to SpanStyle(color = Color(0xFF00658F), fontWeight = FontWeight.Bold),
        TokenKind.EMPHASIS to SpanStyle(color = Color(0xFF00658F)),
        TokenKind.CODE to SpanStyle(color = Color(0xFF8A2B5B)),
        TokenKind.LINK to SpanStyle(
            color = Color(0xFF0B57A4),
            textDecoration = TextDecoration.Underline,
        ),
        TokenKind.QUOTE to SpanStyle(color = Color(0xFF4A7A52)),
    ),
)

private val Dark = HighlightStyles(
    mapOf(
        TokenKind.COMMENT to SpanStyle(color = Color(0xFF8FAF96)),
        TokenKind.STRING to SpanStyle(color = Color(0xFF7FD8A0)),
        TokenKind.ESCAPE to SpanStyle(color = Color(0xFFFFB77C)),
        TokenKind.NUMBER to SpanStyle(color = Color(0xFF9CC7FF)),
        TokenKind.KEYWORD to SpanStyle(color = Color(0xFFE0A6FF)),
        TokenKind.TYPE to SpanStyle(color = Color(0xFF6FE3C8)),
        TokenKind.BUILTIN to SpanStyle(color = Color(0xFFFFC98A)),
        TokenKind.FUNCTION to SpanStyle(color = Color(0xFF9DBEFF)),
        TokenKind.PROPERTY to SpanStyle(color = Color(0xFFB3BFFF)),
        TokenKind.TAG to SpanStyle(color = Color(0xFFFF9B93)),
        TokenKind.ATTRIBUTE to SpanStyle(color = Color(0xFFE8BE7A)),
        TokenKind.PUNCTUATION to SpanStyle(color = Color(0xFFA8B0BA)),
        TokenKind.HEADING to SpanStyle(color = Color(0xFF8ECDFF), fontWeight = FontWeight.Bold),
        TokenKind.EMPHASIS to SpanStyle(color = Color(0xFF8ECDFF)),
        TokenKind.CODE to SpanStyle(color = Color(0xFFFFADD3)),
        TokenKind.LINK to SpanStyle(
            color = Color(0xFF8FBEFF),
            textDecoration = TextDecoration.Underline,
        ),
        TokenKind.QUOTE to SpanStyle(color = Color(0xFF8FAF96)),
    ),
)
