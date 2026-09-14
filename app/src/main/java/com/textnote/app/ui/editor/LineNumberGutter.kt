package com.textnote.app.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.textnote.app.core.LineIndex
import kotlin.math.max
import kotlin.math.roundToInt

private val GUTTER_START_PAD = 10.dp
private val GUTTER_END_PAD = 6.dp
private const val MIN_DIGITS = 2

/** 一个待绘制的行号：逻辑行号（0 起）与它在文本布局里的绝对 y（px） */
private data class GutterNumber(val line: Int, val topPx: Float)

/**
 * 行号栏。
 *
 * **不能假设「第 n 行的 y = n × 行高」**。开启软换行后一行会占多个视觉行，这个等式立刻失效，
 * 行号会从上往下越错越多。正确做法是从 [TextLayoutResult] 拿真实位置：该逻辑行行首所在
 * 的那个视觉行，其 `getLineTop()` 就是行号的 y。
 *
 * 行号栏不参与滚动，而是自己按 [scrollState] 反向位移。这样它始终贴在编辑器左侧，
 * 也避免两层滚动容器嵌套导致 bring-into-view（光标滚入视野）失效。
 *
 * 性能上有两个关键处理，缺一个都会在滚动时掉帧：
 *
 * 1. **滚动位移走 [graphicsLayer]**。在图层块里读 [ScrollState.value]，滚动只让这一层重绘，
 *    不触发重组和重新布局。
 * 2. **只在跨屏时重算可视区**。用 derivedStateOf 把「滚到哪一像素」降采样成「滚到第几屏」，
 *    于是整列数字的组合次数从「每帧一次」降到「每屏一次」。窗口取当前屏上下各一屏的余量，
 *    保证两次重组之间不会出现空档。
 *
 * 于是两万行的文件每帧真正做的只有「画一屏数字」，文件多大都一样。
 */
@Composable
fun LineNumberGutter(
    lineIndex: LineIndex,
    layoutResult: TextLayoutResult?,
    scrollState: ScrollState,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    // TextStyle 每次组合都是新对象，不 remember 会让下面的宽度测量每次重组都重排一次版
    val style = remember(textStyle, numberColor) { textStyle.copy(color = numberColor) }

    val digits = remember(lineIndex.lineCount) {
        max(MIN_DIGITS, lineIndex.lineCount.toString().length)
    }
    // 按最宽的数字串量宽度，行号 9→10、99→100 时列宽才不会跳
    val widthPx = remember(digits, style) { measurer.measure("8".repeat(digits), style).size.width }
    val startPx = with(density) { GUTTER_START_PAD.toPx() }
    val endPx = with(density) { GUTTER_END_PAD.toPx() }
    val gutterWidth: Dp = with(density) { (widthPx + startPx + endPx).toDp() }

    BoxWithConstraints(
        modifier = modifier
            .width(gutterWidth)
            .clipToBounds()
            .drawBehind {
                drawLine(
                    color = dividerColor,
                    start = Offset(size.width - 0.5f, 0f),
                    end = Offset(size.width - 0.5f, size.height),
                    strokeWidth = 1f,
                )
            },
    ) {
        val startOffset = startPx.roundToInt()
        val pageHeight = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)

        val page by remember(pageHeight) {
            derivedStateOf { scrollState.value / pageHeight }
        }

        val numbers = remember(lineIndex, layoutResult, page, pageHeight) {
            numbersBetween(
                lineIndex = lineIndex,
                layoutResult = layoutResult,
                fromY = (page - 1) * pageHeight.toFloat(),
                toY = (page + 2) * pageHeight.toFloat(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .graphicsLayer { translationY = -scrollState.value.toFloat() },
        ) {
            numbers.forEach { item ->
                key(item.line) {
                    Text(
                        text = (item.line + 1).toString(),
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.offset { IntOffset(startOffset, item.topPx.roundToInt()) },
                    )
                }
            }
        }
    }
}

/**
 * 取出 y 落在 `[fromY, toY]` 内的行号。
 *
 * 软换行会让一个逻辑行占多个视觉行，这里只认**第一个**视觉行：判定依据是该视觉行的起始偏移
 * 正好等于逻辑行的行首偏移。续行不编号——CotEditor 也是这个行为。
 */
private fun numbersBetween(
    lineIndex: LineIndex,
    layoutResult: TextLayoutResult?,
    fromY: Float,
    toY: Float,
): List<GutterNumber> {
    val layout = layoutResult ?: return emptyList()
    val visualCount = layout.lineCount
    if (visualCount == 0) return emptyList()

    // 二分找「底部越过 fromY」的第一个视觉行
    var lo = 0
    var hi = visualCount - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (layout.getLineBottom(mid) > fromY) hi = mid else lo = mid + 1
    }

    val result = ArrayList<GutterNumber>(64)
    var v = lo
    var drawnLine = -1
    while (v < visualCount) {
        val top = layout.getLineTop(v)
        if (top > toY) break
        val offset = layout.getLineStart(v)
        val line = lineIndex.lineOf(offset)
        if (line != drawnLine && offset == lineIndex.lineStart(line)) {
            result.add(GutterNumber(line, top))
            drawnLine = line
        }
        v++
    }
    return result
}
