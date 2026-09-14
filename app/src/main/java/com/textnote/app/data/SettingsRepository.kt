package com.textnote.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主题模式 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 正文用的字体族。
 *
 * 只给三种系统族，不捆绑字体文件：一个等宽字体少说 200KB，对一个「轻量」的纯文本
 * 编辑器来说，这笔体积换来的收益还不如把字号和行距做扎实。
 */
enum class EditorFont { MONOSPACE, SANS, SERIF }

/** 字号与行距的可选范围。UI 直接拿去生成选项，避免两处各写一份。 */
object EditorFontRanges {
    val SIZES = intArrayOf(12, 13, 14, 15, 16, 18, 20, 22)
    val LINE_HEIGHTS = floatArrayOf(1.0f, 1.2f, 1.4f, 1.6f, 1.8f)

    const val DEFAULT_SIZE = 15
    const val DEFAULT_LINE_HEIGHT = 1.4f

    fun coerceSize(value: Int): Int = SIZES.firstOrNull { it == value } ?: DEFAULT_SIZE
    fun coerceLineHeight(value: Float): Float =
        LINE_HEIGHTS.firstOrNull { kotlin.math.abs(it - value) < 0.01f } ?: DEFAULT_LINE_HEIGHT
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * Material You 动态取色（Android 12+ 从壁纸取色）。
     * 默认是开的，但不强制：动态取色在某些壁纸上会让强调色偏灰，
     * 而纯文本编辑器对**正文对比度**敏感，所以留一个关掉的口子。
     */
    val dynamicColor: Boolean = true,
    val font: EditorFont = EditorFont.MONOSPACE,
    val fontSizeSp: Int = EditorFontRanges.DEFAULT_SIZE,
    val lineHeightMultiplier: Float = EditorFontRanges.DEFAULT_LINE_HEIGHT,
)

/**
 * 设置存储。
 *
 * 用 SharedPreferences 而不是 DataStore：设置项就 5 个、写入频率极低，
 * 而 DataStore 要额外依赖与协程作用域管理，收益不成比例。
 * （DraftStore 和 DocumentRepository 也都用 SharedPreferences，保持一致。）
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** 当前值。给那些不便收集 Flow 的地方（如 Activity 创建时）用 */
    val current: AppSettings get() = _settings.value

    fun setThemeMode(value: ThemeMode) = update(prefs, KEY_THEME, value.name) { copy(themeMode = value) }
    fun setDynamicColor(value: Boolean) = update(prefs, KEY_DYNAMIC, value) { copy(dynamicColor = value) }
    fun setFont(value: EditorFont) = update(prefs, KEY_FONT, value.name) { copy(font = value) }
    fun setFontSizeSp(value: Int) =
        update(prefs, KEY_SIZE, value) { copy(fontSizeSp = EditorFontRanges.coerceSize(value)) }
    fun setLineHeight(value: Float) = update(
        prefs,
        KEY_LINE,
        value,
    ) { copy(lineHeightMultiplier = EditorFontRanges.coerceLineHeight(value)) }

    /**
     * 写入内存与磁盘。
     *
     * 顺序是**先更新内存再落盘**：StateFlow 一改，界面立刻重绘，不必等磁盘写完；
     * 万一落盘失败也只是设置不持久，界面不会卡在旧值上。
     */
    private inline fun update(
        prefs: SharedPreferences,
        key: String,
        value: Any,
        block: AppSettings.() -> AppSettings,
    ) {
        _settings.value = _settings.value.block()
        prefs.edit {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Float -> putFloat(key, value)
                else -> putString(key, value.toString())
            }
        }
    }

    private fun read(): AppSettings {
        val theme = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrNull() ?: ThemeMode.SYSTEM
        val font = runCatching { EditorFont.valueOf(prefs.getString(KEY_FONT, null) ?: "") }
            .getOrNull() ?: EditorFont.MONOSPACE
        return AppSettings(
            themeMode = theme,
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
            font = font,
            fontSizeSp = EditorFontRanges.coerceSize(prefs.getInt(KEY_SIZE, EditorFontRanges.DEFAULT_SIZE)),
            lineHeightMultiplier = EditorFontRanges.coerceLineHeight(
                prefs.getFloat(KEY_LINE, EditorFontRanges.DEFAULT_LINE_HEIGHT),
            ),
        )
    }

    private companion object {
        const val PREFS = "settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_FONT = "font"
        const val KEY_SIZE = "font_size_sp"
        const val KEY_LINE = "line_height"
    }
}
