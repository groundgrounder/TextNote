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

    // ---- 下面四个只被 diff 分支产出（见 [scanDiffLine]）。单独列出而不是复用上面某个，
    // 是因为它们的语义是「这一行在补丁里的角色」，与注释/字符串/关键字不是一回事；
    // 复用 STRING 或 COMMENT 会让配色被迫跟着那两类走，改一处就撞一处。----
    /** 新增的行（`+` 开头） */
    INSERTED,
    /** 删除的行（`-` 开头） */
    DELETED,
    /** 补丁的文件头与元信息（`diff --git`、`index`、`---`、`+++`、`rename from`…） */
    META,
    /** 改动块位置（`@@ -12,7 +12,9 @@`） */
    HUNK,
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

    /**
     * 按**整个文件名**（含点）匹配，与 [extensions] 二选一命中即可。
     *
     * 存在的理由：`Makefile`、`Dockerfile`、`CMakeLists.txt`、`.gitignore` 这类文件要么没有
     * 扩展名、要么扩展名是别人家的（`CMakeLists.txt` 的 `.txt` 是纯文本），光看扩展名永远
     * 认不出来。名字是**唯一**能识别它们的线索。
     *
     * 一律存**小写**，匹配时也把文件名转小写：文件系统大小写敏感，但用户心里的
     * `Dockerfile` 与 `dockerfile` 是同一个东西。名字里的点要保留（`.gitignore` 整体是一个名字）。
     */
    val fileNames: Set<String> = emptySet(),

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
    /**
     * 走补丁（diff / patch）扫描分支。
     *
     * 它是**第四条**分支而不是一串开关：另外三条（代码 / 标记语言 / Markdown）看的是**字符**
     * ——引号、注释符、关键字；而补丁的语义完全落在**行首前缀**上——`+` 是新增行、`-` 是删除行、
     * `@@` 是块位置、`diff --git` 是文件头。`total = a + b` 与 `+total = a + b` 的区别不在
     * 任何字符上，只在「这一行以什么开头」。
     *
     * 因此这一支**不给行内做二次分词**：一份补丁要的是「一眼看出增删块」，把关键字也染上色
     * 反而会把增删的边界淹掉。
     */
    val diff: Boolean = false,
) {
    companion object {
        val PLAIN = Syntax(
            id = "txt",
            displayName = "Plain Text",
            // csv / tsv 没有语法可着，但它们是常见文本文件，列进来是为了让系统「打开方式」
            // 与手动选择菜单认得它们（`forFileName` 认不出扩展名时本来就退回纯文本）。
            extensions = setOf("txt", "log", "text", "csv", "tsv"),
            fileNames = setOf("readme", "license", "copying", "notice", "authors", "changelog"),
        )

    }
}
