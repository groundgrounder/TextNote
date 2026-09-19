package com.textnote.app.core

/**
 * 诊断的严重级。**只分两档**：错误（结构上就是坏的）与警告（合法但有坑）。
 *
 * 不预先造第三档「提示」：没有分析器会产出它，一个空档位只会让人以为「某处漏了」。
 * 真要加时再加——那时 [Diagnostic] 的渲染（颜色/下划线）才需要跟着分。
 */
enum class DiagnosticSeverity {
    ERROR,
    WARNING,
}

/**
 * 一类可诊断的问题。
 *
 * ## 为什么这里只有「种类」而没有消息文本
 *
 * `core/` 是纯 Kotlin，碰不到 Android 资源；而界面有四套语言（`values/` + 简中 + 繁中 + 拉丁）。
 * 把中文消息直接写在这里，等于让诊断文案永远只有一种语言——那是这个项目里唯一一处
 * 用户能看见、却又不跟着语言走的东西，太扎眼。所以分析器只回答「是哪一类问题 + 一个参数」，
 * 文案由 UI 层查资源（见 `EditorChrome` 里的 `diagnosticMessage`）。
 *
 * ⚠️ 将来接 LSP 时，服务端给的是**自由文本**，不是种类。那时要给 [Diagnostic] 补一条
 * 「直接带消息」的路（现在刻意不预留字段：没有生产者的字段就是死代码）。
 */
enum class DiagnosticKind {
    /** 期望一个值，实际不是（含未加引号的键、非法数字、单引号、以及 JSON 里的注释） */
    JSON_EXPECTED_VALUE,

    /** 键后面缺少冒号 */
    JSON_EXPECTED_COLON,

    /** 两个成员/元素之间缺少逗号 */
    JSON_EXPECTED_COMMA,

    /** 顶层值之后还有内容 */
    JSON_EXPECTED_END,

    /**
     * 括号没有闭合（参数是那个开括号 `{` / `[`）。
     *
     * 报在**开括号**上而不是文件末尾：用户要的是「我那是从哪开始的」，末尾只会让人面对一个
     * 空无一物的位置。文件被截断时这一条是最常见的错误。
     */
    JSON_UNCLOSED_BRACKET,

    /** 字符串没有闭合（引号没配对；在字符串里直接换行也会报这一条） */
    JSON_UNCLOSED_STRING,

    /** 非法转义（参数是那个转义字符，比如 Windows 路径里的 `C:\Users`） */
    JSON_BAD_ESCAPE,

    /** 多余的分隔逗号 */
    JSON_TRAILING_COMMA,

    /** 同一个对象里重复的键（参数是键名） */
    JSON_DUPLICATE_KEY,

    /** 嵌套太深：宁可报一句，也不要递归到栈溢出 */
    JSON_TOO_DEEP,
}

/**
 * 一处诊断。[start] 包含、[end] 不包含，偏移是**相对整份文本**的绝对值——与
 * [HighlightToken] 同一套坐标，这样界面可以把两者叠在同一个 `AnnotatedString` 上。
 *
 * [arg] 是消息里要指名的东西（重复的键名、非法转义字符）。没有就留空。
 */
data class Diagnostic(
    val kind: DiagnosticKind,
    val severity: DiagnosticSeverity,
    val start: Int,
    val end: Int,
    val arg: String? = null,
)

/**
 * 单文件分析的总入口：按语法（必要时还按文件名）挑分析器，并把体积闸门挡在最前面。
 *
 * **同一份文本进来，永远是同一份结果出去**——不持有状态、不碰 IO、不看时间。
 * 这条让它可以放心跑在 `Dispatchers.Default` 上，也让 JVM 断言能直接钉住输出。
 *
 * ## 体积闸门为什么与着色同一档
 *
 * 分析是 O(n) 的纯计算，而它每次输入后都要重跑。允许它跑在 4MB 的只读文档上，
 * 就等于在「按行懒加载」这条已经优化到 30ms 的路径上塞一次全篇扫描。
 * 于是取 [EditorLimits.HIGHLIGHT_CHARS]：与「不再着色」同一档，超过它连分析也停。
 * 只读浏览（超过 [EditorLimits.OPEN_CHARS]）本来就在这个闸门之外。
 */
object DiagnosticEngine {

    /** 超过这个字符数就不分析（与着色同一档，理由见类注释） */
    const val MAX_CHARS = EditorLimits.HIGHLIGHT_CHARS

    /**
     * 分析这份文本，返回**按 start 递增**的诊断列表。没有问题、或这份文件不受支持时返回空列表。
     *
     * [fileName] 只用来判断「这份文件本来就是宽松语法」——`.jsonc` / `.json5` 允许注释与
     * 尾随逗号，拿严格 JSON 的尺子去量它们会满屏误报，而误报比漏报更伤信任。
     */
    fun analyze(text: String, syntax: Syntax, fileName: String): List<Diagnostic> {
        if (text.isEmpty() || text.length > MAX_CHARS) return emptyList()
        return when (syntax.id) {
            JSON_ID -> JsonLint.analyze(text, lenient = isLenientJson(fileName))
            else -> emptyList()
        }
    }

    /** `.jsonc` / `.json5` 是 JSON 的宽松变体，允许注释与尾随逗号 */
    private fun isLenientJson(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".jsonc") || lower.endsWith(".json5")
    }

    private const val JSON_ID = "json"
}
