package com.textnote.app.core

/**
 * 语法着色的产物。
 *
 * **语义单位，不是颜色**：这里描述「这一段是注释 / 字符串 / 关键字」，具体用什么颜色由 UI 层
 * 决定。这样 core/ 保持零 Compose 依赖（项目约定），换主题、加暗色也不用动分词逻辑。
 */
enum class TokenKind {
    COMMENT,
    STRING,
    ESCAPE,
    NUMBER,
    KEYWORD,
    TYPE,
    BUILTIN,
    FUNCTION,
    PROPERTY,
    TAG,
    ATTRIBUTE,
    HEADING,
    EMPHASIS,
    CODE,
    LINK,
    QUOTE,
    PUNCTUATION,
}

/** 一个着色片段，[start] 包含、[end] 不包含，偏移是相对**整份文本**的绝对偏移 */
data class HighlightToken(val start: Int, val end: Int, val kind: TokenKind)

/**
 * 一门语言的语法定义。
 *
 * 刻意做成**数据驱动**：下面是一个通用扫描器，加一门新语言 = 加一个 [Syntax] 实例，
 * 不用碰 [Highlighter]。代价是没法精确表达每种语言的所有边角（比如 CSS 的选择器与声明、
 * Python 的字符串前缀），这些地方接受近似——纯文本编辑器的着色是辅助阅读，
 * 不是编译器前端，为一个 `` `calc(100% - 1px)` `` 写一套完整 CSS 解析不划算。
 */
data class Syntax(
    val id: String,
    val displayName: String,
    val extensions: Set<String>,

    /** 行注释开头，如 `//`、`#` */
    val lineComment: String? = null,
    /** 块注释的起止偶对，如 `"/*" to "*/"` */
    val blockComment: Pair<String, String>? = null,

    /** 普通字符串界定符，如 `"` `'` */
    val stringDelims: Set<Char> = emptySet(),
    /** 单引号字符字面量（Kotlin 的 `'a'`） */
    val charDelim: Char? = null,
    /**
     * 可跨行的「原样」字符串界定序列，如 JS 的反引号、Python 的 `"""` `'''`、
     * Kotlin 的 `"""`。与 [stringDelims] 的区别是它**能跨行**，所以要带扫描器状态。
     *
     * 用 List 而不是 Set：行首状态里要编码「卡在哪一个界定符里」，需要稳定的下标。
     */
    val verbatimStrings: List<String> = emptyList(),
    /** 字符串内的转义符。`\n` 这类序列会被单独标出来，便于一眼看出转义而非字面内容 */
    val escape: Char? = '\\',

    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val builtins: Set<String> = emptySet(),

    /** 标识符除字母数字下划线外还允许的字符（CSS 的属性名里有 `-`） */
    val identChars: Set<Char> = emptySet(),
    /** 额外的标识符首字符（CSS 的 `#id` `.class` `@media`） */
    val identStarts: Set<Char> = emptySet(),
    val caseSensitive: Boolean = true,

    /** 数字后缀，如 Kotlin 的 `L` `f`、Python 的 `j` */
    val numberSuffixes: Set<Char> = emptySet(),

    /** 「字符串后紧跟 `:`」算对象键（JSON） */
    val jsonKeys: Boolean = false,
    /** 「标识符后紧跟 `:`」算声明名（CSS、YAML） */
    val declProps: Boolean = false,
    /**
     * 「标识符后紧跟 `=`」算键（INI / TOML 的 `key = value`）。
     *
     * 与 [declProps] 分开是因为分隔符不同，而这两种文件的键都是最该被看到的构造——
     * 键不着色的话，一份 INI 里就只剩注释和字符串有颜色了。
     */
    val declAssign: Boolean = false,

    /** 走标记语言（HTML/XML）扫描分支 */
    val markup: Boolean = false,
    /** 走 Markdown 扫描分支 */
    val markdown: Boolean = false,
) {
    companion object {
        val PLAIN = Syntax(id = "txt", displayName = "Plain Text", extensions = setOf("txt", "log"))
    }
}
