package com.textnote.app.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.textnote.app.data.AppSettings
import com.textnote.app.data.EditorFont

/**
 * 由设置算出的正文样式。
 *
 * 行高写成「字号 × 倍数」而不是固定的 sp：否则把字号从 12 调到 22 时，
 * 固定行高会让大字号的行挤在一起（22sp 的字配 21sp 的行高会重叠）。
 */
fun editorTextStyle(settings: AppSettings): TextStyle = TextStyle(
    fontFamily = when (settings.font) {
        EditorFont.MONOSPACE -> FontFamily.Monospace
        EditorFont.SANS -> FontFamily.SansSerif
        EditorFont.SERIF -> FontFamily.Serif
    },
    fontSize = settings.fontSizeSp.sp,
    lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
)

/**
 * 编辑区文字样式。
 *
 * 用 CompositionLocal 而不是模块级常量，是因为它现在**由设置决定**；
 * 而编辑区、行号栏、只读浏览三处必须读到同一份（行号栏拿它量行高），
 * 逐层往下传参数既啰嗦又容易漏传一处。
 *
 * 用 `compositionLocalOf` 而不是 `staticCompositionLocalOf`：改字号后需要让读到它的
 * 组合跟着重组，static 版本不会触发重组，界面就不会更新。
 */
val LocalEditorTextStyle = compositionLocalOf { editorTextStyle(AppSettings()) }
