package com.textnote.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.textnote.app.data.AppSettings
import com.textnote.app.data.ThemeMode

// 静态回退配色（关掉动态取色、或 Android 12 以下没有动态取色时使用）
/**
 * 主题：颜色方案，以及三个**全局口径**——圆角（[TextNoteShapes] / [PanelTopShape] / [PillShape]）、
 * 控件高度（[ControlHeight]）、系统栏图标明暗。
 *
 * 口径收在这里是为了「改一处、全应用一致」：组件里**不许再写**
 * `RoundedCornerShape(数字)` 或 `height(44.dp)` 这类字面量，理由见各常量的 KDoc。
 */
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

/**
 * 全应用圆角口径 —— **唯一真源**，组件里别再写 `RoundedCornerShape(数字)` 的字面量。
 *
 * 为什么不用 M3 的默认档（4/8/12/16/28）：最小的那档 `extraSmall = 4dp` 正是**文本字段**
 * 的圆角，而查找/替换面板里那两片字段有 44dp 高 —— 4dp 落上去基本就是直角，整片面板看着很硬。
 * 所以整体上调一档。`extraLarge` 保持 28dp：对话框与面板按规范就是它，跟着改反而离 Material 更远。
 *
 * 半径先写成常量、再拼进 [Shapes]，是因为「只圆上沿」那种组合要拿到数值本身，而 `Shapes` 里
 * 存的是 `CornerSize`，`RoundedCornerShape(topStart: CornerSize, …)` 这个重载并不存在。
 */
private val CornerField = 8.dp // 文本字段、下拉菜单、Snackbar（M3 默认 4）
private val CornerCard = 12.dp // 列表项、预览卡（M3 默认 8）
private val CornerPanel = 28.dp // 对话框那一档；也用作面板上沿

private val TextNoteShapes = Shapes(
    extraSmall = RoundedCornerShape(CornerField),
    small = RoundedCornerShape(CornerCard),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(CornerPanel),
)

/**
 * 贴着屏幕边缘、**只圆上沿**的面板（查找/替换栏）。下沿要跟状态栏接平——圆了就成了浮在
 * 半空的卡片，反倒说不清它属于谁。半径与 `extraLarge` 同一个数，别两处各写各的。
 */
val PanelTopShape: Shape = RoundedCornerShape(topStart = CornerPanel, topEnd = CornerPanel)

/**
 * 全圆（药丸）形状 —— 按钮、单选项、输入框统一用它。
 *
 * `Shapes` 只有五档、没有「full」这一档，而 M3 的按钮默认恰恰就是全圆
 * （`RoundedCornerShape(50)`），所以单列一个出来，免得各处自己写百分比。
 *
 * 口径：控件形状一律药丸，**区分「能输入」和「点一下就执行」靠文字、图标与各自的宽度**，
 * 不再靠形状（曾经把输入框收成 16dp 圆角以与按钮区分，作者要求统一成药丸后取消）。
 */
val PillShape: Shape = RoundedCornerShape(percent = 50)

/**
 * 交互件（输入框、按钮、单选项）的统一高度。
 *
 * 为什么必须是同一个数：M3 各组件的默认高度本来就不一致——`IconButton` 40dp、
 * `FilledTonalButton` 44dp、`FilterChip` 32dp、而 `OutlinedTextField` 的内部下限是 **56dp**。
 * 混着用就会出现「同一屏里左边缘参差」。44 取的是本项目原本写在 `heightIn(min = 44.dp)` 上的值。
 */
val ControlHeight = 44.dp

/**
 * 判定「宽屏」的宽度阈值。
 *
 * 取 600 而不是 M3 里 expanded 那一档的 840：这台平板竖屏是 800dp、横屏 1280dp，
 * 若按 840 划线，同一个界面转个方向就会在两套排布之间跳。600 能把「平板竖屏 / 平板横屏 /
 * 手机横屏」收进同一档，手机竖屏（多数是 360~430dp）仍走原来那套，一个字不改。
 */
private const val WideScreenMinWidthDp = 600

/**
 * 宽屏下列表与表单类内容的宽度上限。
 *
 * 不限的话一行会横跨整块屏幕——实测 1280dp 宽的平板上，首页列表项被拉到 1250dp、
 * 设置页的滑块 1240dp、三个等宽选项各 415dp：文字都在左边、控件散在两头，
 * 眼睛要横着扫一整屏才看得完一行。
 *
 * 只约束**列表与表单**。[EditorScreen] 的正文与 [SearchPanel] 都不套它：
 * 正文全宽是编辑器的常态（桌面上的编辑器都如此），而查找面板的富余宽度正好用来多摆控件。
 */
val ContentMaxWidth = 640.dp

/** 此刻是否算宽屏。只有需要**换一套排布**时才用它（目前只有查找面板）。 */
@Composable
fun isWideScreen(): Boolean =
    LocalConfiguration.current.screenWidthDp >= WideScreenMinWidthDp

/**
 * 宽屏下把内容收进 [ContentMaxWidth] 并居中，窄屏原样返回。
 *
 * 做成 [Modifier] 扩展而不是包一层 `Box`：调用点只在原有的 modifier 链上多接一环，
 * 要不要限宽、限多宽都由这里说了算，界面侧不需要知道自己此刻是宽屏还是窄屏。
 * 先 `wrapContentWidth` 再 `widthIn` 的顺序不能反——外层得先允许子节点比约束小，
 * 内层收窄之后才有地方可居中。
 */
@Composable
fun Modifier.readableWidth(): Modifier {
    if (!isWideScreen()) return this
    return this
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = ContentMaxWidth)
}

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

    // 系统栏图标也要跟着**设置算出的**深色，理由见 [ApplySystemBarAppearance]
    ApplySystemBarAppearance(darkTheme)

    MaterialTheme(colorScheme = colorScheme, shapes = TextNoteShapes) {
        // 编辑器样式要在 MaterialTheme 之后提供：ReadOnlyReader 与 EditorScreen 都从这里取，
        // 行号栏也靠它量行高——三处必须是同一份，否则行号会与正文错位。
        CompositionLocalProvider(
            LocalEditorTextStyle provides editorTextStyle(settings),
            content = content,
        )
    }
}

/**
 * 把系统栏图标（时钟、电量、手势条附近那些）的明暗对齐到**当前主题**。
 *
 * 为什么不能只在 `onCreate` 里设一次：`enableEdgeToEdge()` 用的是 `SystemBarStyle.auto`，
 * 它的默认判据是 `resources.configuration.uiMode`（系统夜间模式）；资源侧 `windowLightStatusBar`
 * 又是按 `values-night` 限定的。两者看的都是**系统**，而本项目的主题由设置决定、允许跟系统相反
 * ——于是「设置里选深色 + 系统停在浅色」时，深色图标会压在深色正文上，反过来也一样。
 * 这与编辑器当初必须把 `darkTheme` 显式传进去、不能读 `isSystemInDarkTheme()` 是同一个坑。
 *
 * 用 [LaunchedEffect] 而不是直接在组合里调：这是对 Window 的一次命令，只在真正变化时做一遍。
 */
@Composable
private fun ApplySystemBarAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    LaunchedEffect(darkTheme) {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

/** Compose 的 View 拿到的可能是被包装过的 Context（主题包装、ContextThemeWrapper 等） */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
