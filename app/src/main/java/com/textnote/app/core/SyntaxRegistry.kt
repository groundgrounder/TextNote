package com.textnote.app.core

/**
 * 内置语法表。
 *
 * 覆盖 15 种：Plain / Markdown / JSON / HTML / CSS / JavaScript / Python / Kotlin /
 * YAML / Shell / SQL / Config(INI·TOML) / Java / C·C++ / Go。
 *
 * 每种只看**扩展名**——按内容猜（比如首字符是 `{` 就当 JSON）听起来聪明，实际会在
 * 用户打开一个恰好以 `{` 开头的 .log 时给出错误的高亮，而这种错误用户无法纠正。
 * 扩展名虽然笨，但是可预测的，错了也知道为什么。
 *
 * 加一门语言 = 加一个 [Syntax] 实例并放进 [all]，不用碰 [Highlighter]。
 * 两处容易踩：
 * - **`escape` 不是「有转义就填」**：只有当**所有**字符串界定符内都认反斜杠时才能填。
 *   Shell / YAML / Config 里单引号内 `\` 是字面量、Windows 路径又满是反斜杠，
 *   填了会大面积误标，宁可漏标。
 * - **`verbatimStrings` 必须能跨行**（因此要带扫描器状态），普通字符串填 [stringDelims]。
 */
object SyntaxRegistry {

    private val KEYWORDS_KOTLIN = setOf(
        "as", "break", "class", "continue", "do", "else", "for", "fun", "if", "in", "interface",
        "is", "object", "package", "return", "super", "this", "throw", "try", "typealias",
        "typeof", "val", "var", "when", "while", "by", "catch", "constructor", "delegate",
        "dynamic", "field", "file", "finally", "get", "import", "init", "param", "property",
        "receiver", "set", "setparam", "value", "where", "actual", "expect", "external",
        "infix", "inline", "inner", "internal", "lateinit", "operator", "out", "override",
        "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg",
        "const", "crossinline", "noinline",
    )
    private val TYPES_KOTLIN = setOf(
        "Byte", "Short", "Int", "Long", "Float", "Double", "Char", "Boolean", "String", "Any",
        "Unit", "Nothing", "Array", "List", "MutableList", "Map", "MutableMap", "Set",
        "MutableSet", "Collection", "Iterable", "Sequence", "Result",
    )

    private val KEYWORDS_JS = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete",
        "do", "else", "export", "extends", "finally", "for", "function", "if", "import", "in",
        "instanceof", "let", "new", "of", "return", "super", "switch", "this", "throw", "try",
        "typeof", "var", "void", "while", "with", "yield", "async", "await", "static", "get",
        "set", "public", "private", "protected", "readonly", "abstract", "as", "implements",
        "interface", "package", "type", "namespace", "declare", "enum", "satisfies",
    )
    private val BUILTINS_JS = setOf(
        "true", "false", "null", "undefined", "NaN", "Infinity", "console", "window", "document",
        "Math", "JSON", "Object", "Array", "String", "Number", "Boolean", "Promise", "Symbol",
        "Map", "Set", "WeakMap", "Proxy", "Reflect", "Error", "globalThis",
    )

    private val KEYWORDS_PY = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
        "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while",
        "with", "yield", "match", "case",
    )
    private val BUILTINS_PY = setOf(
        "True", "False", "None", "self", "cls", "print", "len", "range", "type", "int", "float",
        "str", "bool", "list", "dict", "set", "tuple", "super", "isinstance", "enumerate", "zip",
        "open", "input", "map", "filter", "sorted", "sum", "min", "max", "abs", "round",
    )

    // CSS 的 at-规则也是「关键字」，但它们带 @ 前缀会被当成选择器，
    // 所以要么列在这里，要么接受它们被着成 TAG 色——列进来更准。
    private val KEYWORDS_CSS = setOf(
        "important", "inherit", "initial", "unset", "var", "calc", "url",
        "@media", "@import", "@charset", "@keyframes", "@supports", "@font-face", "@page",
    )

    private val BUILTINS_JSON = setOf("true", "false", "null")

    private val BUILTINS_YAML = setOf("true", "false", "null", "yes", "no", "on", "off", "~")

    private val KEYWORDS_SHELL = setOf(
        "if", "then", "elif", "else", "fi", "for", "while", "until", "do", "done", "case",
        "esac", "in", "function", "select", "time", "coproc", "return", "break", "continue",
        "local", "export", "readonly", "declare", "typeset", "unset", "shift", "eval",
        "source", "alias", "trap", "exit", "set", "shopt",
    )
    private val BUILTINS_SHELL = setOf(
        "echo", "printf", "cd", "ls", "cp", "mv", "rm", "mkdir", "rmdir", "touch", "cat",
        "head", "tail", "grep", "sed", "awk", "cut", "sort", "uniq", "wc", "find", "xargs",
        "tee", "tr", "diff", "tar", "zip", "curl", "wget", "git", "docker", "kubectl", "npm",
        "yarn", "make", "sudo", "apt", "brew", "chmod", "chown", "kill", "ps", "df", "du",
        "true", "false", "test", "read", "exec", "wait", "sleep", "which", "env", "pwd",
    )

    private val KEYWORDS_SQL = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
        "create", "table", "drop", "alter", "add", "column", "index", "view", "as", "join",
        "inner", "left", "right", "full", "outer", "cross", "on", "using", "group", "by",
        "order", "having", "limit", "offset", "distinct", "union", "all", "except", "intersect",
        "and", "or", "not", "null", "is", "in", "exists", "between", "like", "ilike", "case",
        "when", "then", "else", "end", "primary", "key", "foreign", "references", "unique",
        "check", "default", "constraint", "cascade", "asc", "desc", "with", "recursive",
        "over", "partition", "window", "returning", "conflict", "begin", "commit", "rollback",
        "transaction", "explain", "analyze", "vacuum", "pragma",
    )
    private val TYPES_SQL = setOf(
        "int", "integer", "bigint", "smallint", "tinyint", "decimal", "numeric", "float",
        "double", "real", "char", "varchar", "text", "boolean", "bool", "date", "time",
        "timestamp", "datetime", "interval", "blob", "json", "jsonb", "uuid", "serial",
    )
    private val BUILTINS_SQL = setOf(
        "count", "sum", "avg", "min", "max", "abs", "round", "floor", "ceil", "coalesce",
        "nullif", "cast", "concat", "length", "lower", "upper", "trim", "substring", "now",
        "current_date", "current_timestamp", "true", "false",
    )

    private val BUILTINS_CONFIG = setOf("true", "false", "yes", "no", "on", "off", "null", "none")

    private val KEYWORDS_JAVA = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "record",
        "sealed", "permits", "var", "yield", "exports", "opens", "requires", "module",
    )
    private val TYPES_JAVA = setOf(
        "String", "Integer", "Long", "Double", "Float", "Boolean", "Character", "Byte",
        "Short", "Object", "Number", "List", "ArrayList", "Map", "HashMap", "Set", "HashSet",
        "Collection", "Iterable", "Iterator", "Optional", "Stream", "StringBuilder", "Enum",
        "Exception", "RuntimeException", "Throwable", "Thread", "Runnable", "Comparable",
    )

    private val KEYWORDS_C = setOf(
        "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
        "return", "goto", "sizeof", "typedef", "struct", "union", "enum", "static", "const",
        "volatile", "inline", "extern", "register", "auto", "restrict", "alignof", "asm",
        // C++ 部分
        "class", "public", "private", "protected", "template", "typename", "namespace",
        "using", "new", "delete", "try", "catch", "throw", "virtual", "override", "explicit",
        "friend", "operator", "this", "constexpr", "consteval", "noexcept", "concept",
        "requires", "decltype", "static_cast", "dynamic_cast", "const_cast", "reinterpret_cast",
        "co_await", "co_return", "co_yield", "mutable", "export", "typeid",
    )
    private val TYPES_C = setOf(
        "void", "bool", "char", "short", "int", "long", "float", "double", "signed",
        "unsigned", "size_t", "ssize_t", "ptrdiff_t", "int8_t", "int16_t", "int32_t",
        "int64_t", "uint8_t", "uint16_t", "uint32_t", "uint64_t", "char16_t", "char32_t",
        "wchar_t", "nullptr_t", "string", "wstring", "vector", "map", "unordered_map", "set",
        "pair", "shared_ptr", "unique_ptr", "auto",
    )

    private val KEYWORDS_GO = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else",
        "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map",
        "package", "range", "return", "select", "struct", "switch", "type", "var",
    )
    private val TYPES_GO = setOf(
        "bool", "byte", "complex64", "complex128", "error", "float32", "float64", "int",
        "int8", "int16", "int32", "int64", "rune", "string", "uint", "uint8", "uint16",
        "uint32", "uint64", "uintptr", "any", "comparable",
    )
    private val BUILTINS_GO = setOf(
        "true", "false", "nil", "iota", "append", "cap", "close", "complex", "copy", "delete",
        "imag", "len", "make", "new", "panic", "print", "println", "real", "recover",
    )

    private val MD = Syntax(
        id = "markdown",
        displayName = "Markdown",
        extensions = setOf("md", "markdown", "mdown", "mkd"),
        markdown = true,
        escape = null,
    )

    private val JSON = Syntax(
        id = "json",
        displayName = "JSON",
        extensions = setOf("json", "jsonc", "json5"),
        stringDelims = setOf('"'),
        lineComment = "//",
        blockComment = "/*" to "*/",
        jsonKeys = true,
        builtins = BUILTINS_JSON,
    )

    private val HTML = Syntax(
        id = "html",
        displayName = "HTML",
        extensions = setOf("html", "htm", "xhtml", "xml", "svg", "vue"),
        markup = true,
        stringDelims = setOf('"', '\''),
        // 标记语言没有转义符：这里的 escape 是扫描器用来标 `\n` 这类序列的，
        // 若沿用默认的 `\`，HTML 里的 Windows 路径 `src="C:\a"` 会被误标成转义。
        escape = null,
    )

    private val CSS = Syntax(
        id = "css",
        displayName = "CSS",
        extensions = setOf("css", "scss", "less", "sass"),
        blockComment = "/*" to "*/",
        stringDelims = setOf('"', '\''),
        // '.' '#' '@' 是选择器/at-规则的前缀，'-' 只在标识符**内部**出现（font-family）——
        // 放进 identStarts 会让孤立的 '-' 被误认成标识符。
        identStarts = setOf('#', '.', '@'),
        identChars = setOf('-'),
        keywords = KEYWORDS_CSS,
        declProps = true,
        caseSensitive = false,
    )

    private val JS = Syntax(
        id = "javascript",
        displayName = "JavaScript",
        extensions = setOf("js", "mjs", "cjs", "jsx", "ts", "tsx"),
        lineComment = "//",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"', '\''),
        verbatimStrings = listOf("`"),
        keywords = KEYWORDS_JS,
        builtins = BUILTINS_JS,
        types = setOf("string", "number", "boolean", "void", "any", "unknown", "never"),
        identChars = setOf('$'),
        numberSuffixes = setOf('n'),
    )

    private val PYTHON = Syntax(
        id = "python",
        displayName = "Python",
        extensions = setOf("py", "pyw", "pyi"),
        lineComment = "#",
        stringDelims = setOf('"', '\''),
        verbatimStrings = listOf("\"\"\"", "'''"),
        keywords = KEYWORDS_PY,
        builtins = BUILTINS_PY,
        numberSuffixes = setOf('j'),
    )

    private val KOTLIN = Syntax(
        id = "kotlin",
        displayName = "Kotlin",
        extensions = setOf("kt", "kts"),
        lineComment = "//",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"'),
        charDelim = '\'',
        verbatimStrings = listOf("\"\"\""),
        keywords = KEYWORDS_KOTLIN,
        types = TYPES_KOTLIN,
        numberSuffixes = setOf('L', 'f', 'F', 'u', 'U'),
    )

    private val YAML = Syntax(
        id = "yaml",
        displayName = "YAML",
        extensions = setOf("yaml", "yml"),
        lineComment = "#",
        stringDelims = setOf('"', '\''),
        // YAML 的双引号字符串才认转义，单引号里 `\` 就是字面反斜杠。通用扫描器分不清
        // 两种引号，标错比漏标更糟（Windows 路径、正则里反斜杠很多），所以整个关掉。
        escape = null,
        builtins = BUILTINS_YAML,
        // 「标识符 + :」就是键，YAML 里这是最高频的构造
        declProps = true,
        caseSensitive = false,
    )

    private val SHELL = Syntax(
        id = "shell",
        displayName = "Shell",
        extensions = setOf("sh", "bash", "zsh", "fish", "ksh", "bashrc", "zshrc"),
        lineComment = "#",
        stringDelims = setOf('"', '\''),
        // 同上：单引号内不转义、双引号内才转义，扫描器分不清，索性不标
        escape = null,
        keywords = KEYWORDS_SHELL,
        builtins = BUILTINS_SHELL,
    )

    private val SQL = Syntax(
        id = "sql",
        displayName = "SQL",
        extensions = setOf("sql", "mysql", "pgsql", "psql", "sqlite"),
        lineComment = "--",
        blockComment = "/*" to "*/",
        stringDelims = setOf('\''),
        // SQL 用 `''` 表示字面单引号，`\` 在多数方言里不是转义符
        escape = null,
        keywords = KEYWORDS_SQL,
        types = TYPES_SQL,
        builtins = BUILTINS_SQL,
        caseSensitive = false,
    )

    /**
     * INI 与 TOML 共用一个定义：两者的行结构一样（`key = value` / `key: value`），
     * 差别不值得两套表。代价是 INI 的 `;` 注释不高亮——那只是少一点颜色，不是错误。
     */
    private val CONFIG = Syntax(
        id = "config",
        displayName = "Config (INI/TOML)",
        extensions = setOf("ini", "cfg", "conf", "config", "toml", "properties", "env", "desktop"),
        lineComment = "#",
        stringDelims = setOf('"', '\''),
        escape = null, // `path = C:\x` 这种在配置文件里太常见，不能标成转义
        builtins = BUILTINS_CONFIG,
        // `key = value`。TOML 也写 `key: value` 的场合少，这里只认 `=`
        declAssign = true,
        caseSensitive = false,
        identChars = setOf('-', '.'),
    )

    private val JAVA = Syntax(
        id = "java",
        displayName = "Java",
        extensions = setOf("java", "jsp"),
        lineComment = "//",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"'),
        charDelim = '\'',
        // Java 15+ 的文本块。与 Kotlin 的 """ 同形，能跨行
        verbatimStrings = listOf("\"\"\""),
        keywords = KEYWORDS_JAVA,
        types = TYPES_JAVA,
        numberSuffixes = setOf('L', 'l', 'f', 'F', 'd', 'D'),
    )

    /** C 与 C++ 共用：扩展名高度重叠，关键字取并集比分开猜更实用 */
    private val CPP = Syntax(
        id = "cpp",
        displayName = "C/C++",
        extensions = setOf("c", "h", "cpp", "hpp", "cc", "hh", "cxx", "hxx", "c++", "ipp", "tpp"),
        lineComment = "//",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"'),
        charDelim = '\'',
        keywords = KEYWORDS_C,
        types = TYPES_C,
        // `#include` `#define` 这类预处理指令整行按 TAG 上色：它们与标识符形状相同，
        // 用 identStarts 让 `#` 开头的串被认成标识符，比什么都不标好认
        identStarts = setOf('#'),
        numberSuffixes = setOf('u', 'U', 'l', 'L', 'f', 'F'),
    )

    private val GO = Syntax(
        id = "go",
        displayName = "Go",
        extensions = setOf("go", "mod", "sum"),
        lineComment = "//",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"'),
        charDelim = '\'',
        verbatimStrings = listOf("`"), // raw string，能跨行
        keywords = KEYWORDS_GO,
        types = TYPES_GO,
        builtins = BUILTINS_GO,
    )

    /** 全部内置语法。顺序无关，查找走 [byExtension]。 */
    val all: List<Syntax> = listOf(
        Syntax.PLAIN, MD, JSON, HTML, CSS, JS, PYTHON, KOTLIN,
        YAML, SHELL, SQL, CONFIG, JAVA, CPP, GO,
    )

    private val byExtension: Map<String, Syntax> = buildMap {
        // 后出现的覆盖先出现的：jsx/tsx 归 JS 而不是 HTML，放在后面定义才有优先级。
        // 但 all 里 JS 定义在 HTML 之后，这里遍历顺序天然给出了这个优先级。
        all.forEach { syntax -> syntax.extensions.forEach { ext -> put(ext, syntax) } }
    }

    /**
     * 手动选择菜单里的顺序：纯文本打头，其余按显示名排序。
     *
     * 与 [all] 分开是因为两者要的东西不同：[all] 的顺序是**扩展名冲突时的裁决顺序**
     * （后定义的优先，见 [byExtension]），菜单要的是**能一眼扫到**。压成一个顺序的话，
     * 为了排版调一次 [all] 就可能让 `.jsx` 换一门语言。
     */
    val picker: List<Syntax> = listOf(Syntax.PLAIN) +
        all.filter { it.id != Syntax.PLAIN.id }.sortedBy { it.displayName }

    /** 按文件名选语法。认不出扩展名（含无扩展名）就当纯文本——不高亮也好过高亮错。 */
    fun forFileName(name: String): Syntax {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == name.lowercase()) return Syntax.PLAIN
        return byExtension[ext] ?: Syntax.PLAIN
    }

    /**
     * 按 id 取语法，找不到返回 null。
     *
     * 手动选择要按 **id** 持久化，不能按下标或显示名：[all] 的顺序将来会变（加语言、调顺序），
     * 存下标会把「Python」变成别的语言；显示名是给人看的，也不该当键。
     */
    fun byId(id: String): Syntax? = all.firstOrNull { it.id == id }
}
