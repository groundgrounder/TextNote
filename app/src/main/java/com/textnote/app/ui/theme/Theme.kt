package com.textnote.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.textnote.app.data.AppSettings
import com.textnote.app.data.ThemeMode

// 静态回退配色（关掉动态取色、或 Android 12 以下没有动态取色时使用）
private val LightColors = lightColorScheme(
    primary = Color(0xFF00658F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCBE6FF),
    onPrimaryContainer = Color(0xFF001E30),
    secondary = Color(0xFF50616F),
    secondaryContainer = Color(0xFFD3E5F7),
    tertiary = Color(0xFF674FA0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ECDFF),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF004B6B),
    onPrimaryContainer = Color(0xFFCBE6FF),
    secondary = Color(0xFFB7C9DA),
    secondaryContainer = Color(0xFF384857),
    tertiary = Color(0xFFD2BBFF),
)

/** 把「跟随系统 / 浅色 / 深色」解析成此刻是否用深色。设置页的预览也要这个判断。 */
@Composable
fun AppSettings.resolveDarkTheme(): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun TextNoteTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = settings.resolveDarkTheme()
    val colorScheme = when {
        // Material You 动态取色（壁纸取色）。默认开，但可以关——某些壁纸取出来的
        // 强调色偏灰，而纯文本编辑器对正文对比度敏感，得留个口子。
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colorScheme) {
        // 编辑器样式要在 MaterialTheme 之后提供：ReadOnlyReader 与 EditorScreen 都从这里取，
        // 行号栏也靠它量行高——三处必须是同一份，否则行号会与正文错位。
        CompositionLocalProvider(
            LocalEditorTextStyle provides editorTextStyle(settings),
            content = content,
        )
    }
}
