package com.textnote.app.core

/**
 * 内置语法表。
 *
 * 覆盖 34 种，分四类：**通用**（Plain / JSON / HTML·XML / CSS / YAML / Config / Shell / SQL）、
 * **编程语言**（Kotlin / Java / C·C++ / Go / Rust / Swift / C# / PHP / Ruby / Lua / Perl / R /
 * Dart / Scala / PowerShell / Haskell）、**构建与数据描述**
 * （Markdown / Dockerfile / Makefile / CMake / Terraform·HCL / Protobuf / GraphQL）、
 * **补丁**（Diff·Patch）。
 *
 * 识别**只看文件名**，两层：整个文件名（`Makefile`、`Dockerfile`、`CMakeLists.txt`、`.gitignore`）
 * 与扩展名。不按内容猜（比如首字符是 `{` 就当 JSON）——那听起来聪明，实际会在用户打开一个
 * 恰好以 `{` 开头的 .log 时给出错误的高亮，而这种错误用户无法纠正。名字虽然笨，但是可预测的，
 * 错了也知道为什么。
 * 唯一的例外是补丁：它不是按**字符**着色，而是按**行首前缀**（`+` / `-` / `@@` / `diff --git`），
 * 所以它有自己的扫描分支（见 [Syntax.diff]）。
 *
 * 加一门语言 = 加一个 [Syntax] 实例并放进 [all]，不用碰 [Highlighter]。
 * 三处容易踩：
 * - **`escape` 不是「有转义就填」**：只有当**所有**字符串界定符内都认反斜杠时才能填。
 *   Shell / YAML / Config / Ruby / Perl / SQL / Makefile 里单引号内 `\` 是字面量、
 *   Windows 路径又满是反斜杠，填了会大面积误标，宁可漏标。
 * - **`verbatimStrings` 必须能跨行**（因此要带扫描器状态），普通字符串填 [stringDelims]。
 * - **`charDelim` 要当心与语言本身的语法撞车**：Rust 的 `&'a str` 生命周期、Swift 根本
 *   没有字符字面量——所以这两门语言宁可不开字符字面量，也不要把半行代码吞成字符串。
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

    // 没有 `~`：它虽然是 YAML 的 null 写法，但不是标识符，永远匹配不到（见
    // `CheckSyntaxRegistry` 的「词表里没有永远匹配不到的条目」）
    private val BUILTINS_YAML = setOf("true", "false", "null", "yes", "no", "on", "off")


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
     *
     * [fileNames] 里那批是**点开头的配置文件**：它们没有扩展名（整个 `.gitignore` 就是一个名字），
     * 而内容形态与 INI 一致——`#` 起头的注释、`key = value` 的行。`.gitignore` 里全是模式串，
     * 只会着上注释色，但也比一片黑白强。
     */
    private val CONFIG = Syntax(
        id = "config",
        displayName = "Config (INI/TOML)",
        extensions = setOf("ini", "cfg", "conf", "config", "toml", "properties", "env", "desktop"),
        fileNames = setOf(
            ".editorconfig", ".gitattributes", ".gitconfig", ".gitignore", ".gitmodules",
            ".htaccess", ".dockerignore", ".containerignore", ".env", ".npmrc", ".flake8",
        ),
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

    // ==================== 第二批：编程语言 ====================

    private val KEYWORDS_RUST = setOf(
        "as", "async", "await", "box", "break", "const", "continue", "crate", "dyn", "else",
        "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "macro",
        "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static",
        "struct", "super", "trait", "true", "type", "union", "unsafe", "use", "where", "while",
        "yield",
    )
    private val TYPES_RUST = setOf(
        "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize",
        "f32", "f64", "bool", "char", "str", "String", "Vec", "VecDeque", "Option", "Result",
        "Box", "Rc", "Arc", "RefCell", "Cell", "Mutex", "RwLock", "HashMap", "HashSet",
        "BTreeMap", "BTreeSet", "Cow", "Path", "PathBuf", "OsStr", "OsString", "Duration",
        "Instant", "Ordering", "Some", "None", "Ok", "Err", "Iterator", "IntoIterator",
        "Display", "Debug", "Clone", "Copy", "Default", "PartialEq", "Eq", "Hash", "Sized",
        "Send", "Sync", "Fn", "FnMut", "FnOnce",
    )

    private val KEYWORDS_SWIFT = setOf(
        "associatedtype", "async", "await", "borrowing", "case", "catch", "class", "consuming",
        "continue", "convenience", "default", "defer", "deinit", "do", "dynamic", "else", "enum",
        "extension", "fallthrough", "false", "file", "fileprivate", "final", "for", "func",
        "guard", "if", "import", "in", "indirect", "infix", "init", "inout", "internal", "is",
        "lazy", "let", "mutating", "nil", "nonisolated", "nonmutating", "open", "operator",
        "override", "postfix", "precedencegroup", "prefix", "private", "protocol", "public",
        "repeat", "required", "rethrows", "return", "self", "Self", "some", "static", "struct",
        "subscript", "super", "switch", "throw", "throws", "true", "try", "typealias",
        "unowned", "var", "weak", "where", "while", "actor", "any", "package",
    )
    private val TYPES_SWIFT = setOf(
        "Int", "Int8", "Int16", "Int32", "Int64", "UInt", "UInt8", "UInt16", "UInt32", "UInt64",
        "Double", "Float", "Bool", "String", "Character", "Void", "Any", "AnyObject", "Array",
        "Dictionary", "Set", "Optional", "Result", "Error", "Codable", "Decodable", "Encodable",
        "Equatable", "Hashable", "Comparable", "Identifiable", "Sequence", "Collection",
        "Range", "ClosedRange", "Substring", "TimeInterval", "Date", "Data", "URL", "UUID",
        "Never",
    )

    private val KEYWORDS_CS = setOf(
        "abstract", "add", "as", "async", "await", "base", "break", "case", "catch", "checked",
        "class", "const", "continue", "default", "delegate", "do", "dynamic", "else", "enum",
        "event", "explicit", "extern", "false", "file", "finally", "fixed", "for", "foreach",
        "get", "global", "goto", "if", "implicit", "in", "init", "interface", "internal", "is",
        "lock", "namespace", "new", "null", "operator", "out", "override", "params", "partial",
        "private", "protected", "public", "readonly", "record", "ref", "remove", "required",
        "return", "sealed", "set", "sizeof", "stackalloc", "static", "struct", "switch", "this",
        "throw", "true", "try", "typeof", "unchecked", "unsafe", "using", "value", "var",
        "virtual", "volatile", "when", "where", "while", "with", "yield", "nameof",
    )
    private val TYPES_CS = setOf(
        "int", "uint", "long", "ulong", "short", "ushort", "byte", "sbyte", "float", "double",
        "decimal", "bool", "char", "string", "object", "nint", "nuint", "Task", "ValueTask",
        "List", "Dictionary", "HashSet", "Queue", "Stack", "IEnumerable", "IList", "IDictionary",
        "Exception", "StringBuilder", "DateTime", "TimeSpan", "Guid", "Math", "Console",
        "Array", "Tuple", "Nullable", "Span", "Memory", "CancellationToken", "Uri",
    )

    private val KEYWORDS_PHP = setOf(
        "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class", "clone",
        "const", "continue", "declare", "default", "do", "echo", "else", "elseif", "empty",
        "enddeclare", "endfor", "endforeach", "endif", "endswitch", "endwhile", "enum",
        "extends", "final", "finally", "fn", "for", "foreach", "from", "function", "global",
        "goto", "if", "implements", "include", "include_once", "instanceof", "insteadof",
        "interface", "isset", "list", "match", "namespace", "new", "or", "print", "private",
        "protected", "public", "readonly", "require", "require_once", "return", "static",
        "switch", "throw", "trait", "try", "unset", "use", "var", "while", "xor", "yield",
        "true", "false", "null",
    )
    private val TYPES_PHP = setOf(
        "int", "float", "string", "bool", "array", "object", "mixed", "void", "never",
        "iterable", "callable", "self", "parent", "static", "Closure", "Generator", "Iterator",
        "Traversable", "Throwable", "Exception", "DateTime", "ArrayObject", "stdClass",
    )

    // Ruby 的单引号串**不认**转义（只有双引号认），扫描器分不清两种引号 → escape 关掉。
    // 词表里没有 `defined?` / `nil?` / `empty?` 这类带问号的名字：扫描器不把 `?` 算作标识符
    // 的一部分，写进去永远匹配不到（`CheckSyntaxRegistry` 会当场报错），不如不写。
    private val KEYWORDS_RUBY = setOf(
        "alias", "and", "begin", "break", "case", "class", "def", "do", "else",
        "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not",
        "or", "redo", "rescue", "retry", "return", "self", "super", "then", "true", "undef",
        "unless", "until", "when", "while", "yield", "lambda", "proc", "require",
        "require_relative", "include", "extend", "prepend", "attr_accessor", "attr_reader",
        "attr_writer", "attr", "raise",
    )
    private val BUILTINS_RUBY = setOf(
        "puts", "print", "p", "gets", "chomp", "to_s", "to_i", "to_f", "to_a", "to_h", "each",
        "each_with_index", "map", "select", "reject", "reduce", "inject", "length", "size",
        "push", "pop", "shift", "unshift", "first", "last", "sort", "sort_by", "min", "max",
        "sum", "freeze", "dup", "clone", "loop", "format",
        "sprintf", "rand", "sleep", "exit", "at_exit", "call", "new",
    )
    private val TYPES_RUBY = setOf(
        "String", "Integer", "Float", "Array", "Hash", "Symbol", "Range", "Regexp", "NilClass",
        "TrueClass", "FalseClass", "Object", "Class", "Module", "Struct", "Proc", "Method",
        "Time", "File", "IO", "Dir", "Numeric", "Comparable", "Enumerable", "Exception",
        "StandardError", "RuntimeError", "ArgumentError", "TypeError", "NameError",
        "NoMethodError",
    )

    // Lua 的块注释是 `--[[ ]]`，**与行注释 `--` 同前缀**——把块注释排在行注释之前
    // 才能认出来（见 [scanCodeLine] 的顺序注释）。
    private val KEYWORDS_LUA = setOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if",
        "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
    )
    private val BUILTINS_LUA = setOf(
        "print", "type", "tostring", "tonumber", "pairs", "ipairs", "next", "select", "rawget",
        "rawset", "rawequal", "rawlen", "setmetatable", "getmetatable", "require", "error",
        "assert", "pcall", "xpcall", "unpack", "collectgarbage", "table", "string", "math",
        "io", "os", "coroutine", "debug", "utf8", "_G", "_VERSION",
    )

    // Perl 的 `$ @ % &` 都是 sigil；`%` 做取模时后面通常不跟字母，代价可接受
    private val KEYWORDS_PERL = setOf(
        "my", "our", "local", "state", "sub", "if", "elsif", "else", "unless", "while", "until",
        "for", "foreach", "do", "given", "when", "default", "return", "last", "next", "redo",
        "goto", "package", "use", "require", "no", "BEGIN", "END", "CHECK", "INIT", "UNITCHECK",
        "and", "or", "not", "xor", "eq", "ne", "lt", "gt", "le", "ge", "cmp", "undef",
        "exists", "delete", "scalar", "wantarray", "bless", "ref", "die", "warn", "eval",
        "print", "printf", "say", "chomp", "chop", "split", "join", "map", "grep", "sort",
        "reverse", "push", "pop", "shift", "unshift", "splice", "keys", "values", "each", "open",
        "close", "read", "write", "length", "substr", "index", "sprintf", "lc", "uc", "lcfirst",
        "ucfirst", "chdir", "mkdir", "unlink", "rename", "stat", "sleep", "exit", "qw", "qq",
        "qr", "qx",
    )
    private val TYPES_PERL = setOf(
        "STDIN", "STDOUT", "STDERR", "ARGV", "ENV", "ARRAY", "HASH", "SCALAR", "CODE", "GLOB",
        "REF", "LVALUE", "FORMAT",
    )

    // R 的标识符里允许点（`data.frame`），所以 identChars 带 '.'：整个名字是一次词法扫描
    private val KEYWORDS_R = setOf(
        "if", "else", "repeat", "while", "function", "for", "in", "next", "break", "TRUE",
        "FALSE", "NULL", "Inf", "NaN", "NA", "NA_integer_", "NA_real_", "NA_character_",
        "library", "require", "return", "switch", "invisible",
    )
    private val BUILTINS_R = setOf(
        "print", "cat", "paste", "paste0", "sprintf", "format", "length", "names", "colnames",
        "rownames", "class", "typeof", "is.null", "is.na", "c", "list", "data.frame", "matrix",
        "array", "vector", "numeric", "integer", "character", "logical", "factor", "mean",
        "median", "sum", "min", "max", "sd", "var", "quantile", "seq", "seq_len", "seq_along",
        "rep", "apply", "lapply", "sapply", "vapply", "tapply", "mapply", "read.csv",
        "read.table", "read.delim", "write.csv", "write.table", "plot", "barplot", "hist",
        "lines", "points", "setwd", "getwd", "head", "tail", "subset", "merge", "order",
        "sort", "unique", "table", "str", "summary", "nrow", "ncol", "dim", "which", "match",
        "rbind", "cbind", "grep", "gsub", "sub", "strsplit", "trimws", "toupper", "tolower",
        "nchar", "round", "floor", "ceiling", "abs", "sqrt", "exp", "log", "runif", "rnorm",
        "sample", "set.seed", "install.packages", "options",
    )

    private val KEYWORDS_DART = setOf(
        "abstract", "as", "assert", "async", "await", "base", "break", "case", "catch", "class",
        "const", "continue", "covariant", "default", "deferred", "do", "dynamic", "else", "enum",
        "export", "extends", "extension", "external", "factory", "false", "final", "finally",
        "for", "get", "hide", "if", "implements", "import", "in", "interface", "is", "late",
        "library", "mixin", "new", "null", "on", "operator", "part", "required", "rethrow",
        "return", "sealed", "set", "show", "static", "super", "switch", "sync", "this", "throw",
        "true", "try", "typedef", "var", "void", "when", "while", "with", "yield",
    )
    private val TYPES_DART = setOf(
        "int", "double", "num", "String", "bool", "List", "Map", "Set", "Object", "Future",
        "Stream", "Iterable", "Duration", "DateTime", "Exception", "Error", "Null", "Never",
        "Symbol", "Uri", "RegExp", "BigInt", "Function", "Comparable", "Type", "Record",
    )

    private val KEYWORDS_SCALA = setOf(
        "abstract", "case", "catch", "class", "def", "derives", "do", "else", "end", "enum",
        "export", "extends", "extension", "false", "final", "finally", "for", "forSome",
        "given", "if", "implicit", "import", "infix", "inline", "lazy", "match", "new", "null",
        "object", "opaque", "open", "override", "package", "private", "protected", "return",
        "sealed", "super", "then", "this", "throw", "trait", "transparent", "true", "try",
        "type", "using", "val", "var", "while", "with", "yield",
    )
    private val TYPES_SCALA = setOf(
        "Int", "Long", "Double", "Float", "Boolean", "Char", "Byte", "Short", "String", "Unit",
        "Any", "AnyRef", "AnyVal", "Nothing", "Null", "List", "Seq", "Vector", "Map", "Set",
        "Option", "Some", "None", "Either", "Left", "Right", "Future", "Array", "Tuple",
        "BigInt", "BigDecimal", "Try", "Success", "Failure", "Iterator", "Iterable",
        "Collection", "PartialFunction", "Function", "Ordering", "IArray",
    )

    // PowerShell 不区分大小写、词法上允许连字符（`Get-ChildItem` 是一个标识符）
    private val KEYWORDS_PS = setOf(
        "function", "filter", "workflow", "configuration", "param", "begin", "process", "end",
        "dynamicparam", "if", "elseif", "else", "switch", "foreach", "for", "while", "do",
        "until", "break", "continue", "return", "throw", "try", "catch", "finally", "trap",
        "class", "enum", "using", "namespace", "module", "in", "not", "and", "or", "xor",
        "exit", "default", "data", "parallel", "sequence", "hidden", "static",
    )
    private val BUILTINS_PS = setOf(
        "write-host", "write-output", "write-error", "write-warning", "write-verbose",
        "get-childitem", "get-content", "set-content", "add-content", "clear-content",
        "new-item", "remove-item", "copy-item", "move-item", "rename-item", "test-path",
        "join-path", "split-path", "resolve-path", "get-item", "get-location", "set-location",
        "push-location", "pop-location", "select-object", "where-object", "foreach-object",
        "sort-object", "group-object", "measure-object", "compare-object", "select-string",
        "get-member", "format-table", "format-list", "out-file", "out-string", "out-null",
        "start-process", "stop-process", "get-process", "start-sleep", "get-service", "get-date",
        "get-help", "get-command", "get-variable", "set-variable", "invoke-webrequest",
        "invoke-restmethod", "invoke-expression", "convertfrom-json", "convertto-json",
        "export-csv", "import-csv", "new-object", "read-host", "import-module",
    )

    private val KEYWORDS_HASKELL = setOf(
        "module", "where", "import", "qualified", "as", "hiding", "data", "type", "newtype",
        "class", "instance", "deriving", "do", "let", "in", "if", "then", "else", "case", "of",
        "infixl", "infixr", "infix", "default", "foreign", "forall", "mdo", "rec", "proc",
        "family", "role", "pattern", "static", "group", "by", "using", "stock", "anyclass",
        "via",
    )
    private val TYPES_HASKELL = setOf(
        "Int", "Integer", "Float", "Double", "Bool", "Char", "String", "Maybe", "Either", "IO",
        "Ordering", "Rational", "Word", "Word8", "Word16", "Word32", "Word64", "Int8", "Int16",
        "Int32", "Int64", "Map", "Set", "Text", "ByteString", "Show", "Read", "Eq", "Ord",
        "Enum", "Bounded", "Num", "Real", "Fractional", "Integral", "Functor", "Applicative",
        "Monad", "Foldable", "Traversable", "Semigroup", "Monoid", "IOError",
    )
    private val BUILTINS_HASKELL = setOf(
        "map", "filter", "foldr", "foldl", "head", "tail", "init", "last", "length", "reverse",
        "zip", "zipWith", "unzip", "take", "drop", "takeWhile", "dropWhile", "span", "break",
        "splitAt", "elem", "notElem", "lookup", "null", "minimum", "maximum", "sum", "product",
        "concat", "concatMap", "replicate", "cycle", "iterate", "error", "undefined", "id",
        "const", "flip", "curry", "uncurry", "show", "read", "print", "putStrLn", "putStr",
        "getLine", "return", "pure", "fmap", "otherwise", "seq", "True", "False", "Just",
        "Nothing", "Left", "Right",
    )

    // ==================== 第三批：构建与数据描述 ====================

    // Dockerfile 的指令大小写不敏感（`FROM` 与 `from` 等价），所以 caseSensitive = false
    private val DOCKERFILE = Syntax(
        id = "dockerfile",
        displayName = "Dockerfile",
        extensions = setOf("dockerfile", "containerfile"),
        fileNames = setOf("dockerfile", "containerfile"),
        lineComment = "#",
        stringDelims = setOf('"', '\''),
        // 续行反斜杠不是字符串转义；填了会把 `RUN a \` 后面的内容标成转义
        escape = null,
        keywords = setOf(
            "from", "run", "cmd", "label", "maintainer", "expose", "env", "add", "copy",
            "entrypoint", "volume", "user", "workdir", "arg", "onbuild", "stopsignal",
            "healthcheck", "shell", "as",
        ),
        caseSensitive = false,
    )

    // Makefile 的核心构造就是 `VAR = value`（变量）与 `target: deps`（规则），
    // 两个开关同时开才能都着色——它们的分隔符不同，不冲突。
    private val MAKEFILE = Syntax(
        id = "makefile",
        displayName = "Makefile",
        extensions = setOf("mk", "mak", "make"),
        fileNames = setOf("makefile", "gnumakefile", "bsdmakefile"),
        lineComment = "#",
        stringDelims = setOf('"', '\''),
        escape = null,
        keywords = setOf(
            "ifeq", "ifneq", "ifdef", "ifndef", "else", "endif", "include", "sinclude",
            "define", "endef", "export", "unexport", "override", "vpath", "private",
            "undefine", "foreach", "call", "eval", "origin", "wildcard", "shell", "patsubst",
            "subst", "strip", "sort", "word", "words", "wordlist", "firstword", "lastword",
            "dir", "notdir", "suffix", "basename", "addsuffix", "addprefix", "join", "filter",
            "findstring", "abspath", "realpath", "error", "warning", "info",
        ),
        declAssign = true,
        declProps = true,
    )

    // CMake 的命令几乎都以 `(` 收尾，扫描器本来就会把 `name(` 标成函数色，
    // 所以关键字表只需要补上控制流与不带参数的命令。
    private val CMAKE = Syntax(
        id = "cmake",
        displayName = "CMake",
        extensions = setOf("cmake"),
        fileNames = setOf("cmakelists.txt"),
        lineComment = "#",
        stringDelims = setOf('"', '\''),
        escape = '\\',
        keywords = setOf(
            "if", "else", "elseif", "endif", "foreach", "endforeach", "function", "endfunction",
            "macro", "endmacro", "while", "endwhile", "break", "continue", "return", "include",
            "set", "unset", "option", "message", "project", "add_executable", "add_library",
            "add_subdirectory", "add_custom_command", "add_custom_target", "add_test",
            "target_link_libraries", "target_include_directories", "target_compile_definitions",
            "target_compile_options", "target_compile_features", "target_sources",
            "find_package", "find_library", "find_program", "find_path", "find_file", "install",
            "list", "string", "file", "math", "get_filename_component", "configure_file",
            "set_target_properties", "set_property", "get_property", "cmake_minimum_required",
            "cmake_policy", "cmake_parse_arguments", "enable_testing", "enable_language",
            "mark_as_advanced", "source_group", "separate_arguments", "execute_process",
            "block", "endblock",
        ),
    )

    private val HCL = Syntax(
        id = "hcl",
        displayName = "HCL / Terraform",
        extensions = setOf("tf", "tfvars", "hcl"),
        lineComment = "#",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"'),
        escape = '\\',
        keywords = setOf(
            "resource", "data", "variable", "output", "module", "provider", "terraform",
            "locals", "backend", "required_providers", "required_version", "for_each", "count",
            "depends_on", "lifecycle", "dynamic", "for", "in", "if", "source", "version",
            "type", "default", "description", "sensitive", "validation", "condition",
            "error_message", "import", "moved", "check", "can", "try", "create_before_destroy",
            "prevent_destroy", "ignore_changes", "replace_triggered_by", "precondition",
            "postcondition",
        ),
        builtins = setOf("true", "false", "null"),
        declAssign = true,
    )

    private val PROTOBUF = Syntax(
        id = "protobuf",
        displayName = "Protocol Buffers",
        extensions = setOf("proto"),
        lineComment = "//",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"', '\''),
        keywords = setOf(
            "syntax", "edition", "package", "import", "option", "message", "enum", "service",
            "rpc", "returns", "stream", "repeated", "optional", "required", "oneof", "map",
            "reserved", "extend", "extensions", "group", "to", "max", "public", "weak",
            "default", "json_name", "deprecated",
        ),
        types = setOf(
            "double", "float", "int32", "int64", "uint32", "uint64", "sint32", "sint64",
            "fixed32", "fixed64", "sfixed32", "sfixed64", "bool", "string", "bytes", "Any",
            "Timestamp", "Duration", "Struct", "Value", "ListValue", "FieldMask", "Empty",
        ),
    )

    private val GRAPHQL = Syntax(
        id = "graphql",
        displayName = "GraphQL",
        extensions = setOf("graphql", "gql", "graphqls"),
        lineComment = "#",
        stringDelims = setOf('"'),
        verbatimStrings = listOf("\"\"\""), // 描述文档用三引号，能跨行
        keywords = setOf(
            "query", "mutation", "subscription", "fragment", "on", "type", "interface", "union",
            "enum", "input", "scalar", "schema", "directive", "extend", "implements",
            "repeatable", "true", "false", "null",
        ),
        types = setOf("Int", "Float", "String", "Boolean", "ID"),
    )

    private val RUST = Syntax(
        id = "rust",
        displayName = "Rust",
        extensions = setOf("rs"),
        lineComment = "//",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"'),
        // 不开 charDelim：`&'a str` 的生命周期与字符字面量同形，标错了会把半行代码吞进字符串
        keywords = KEYWORDS_RUST,
        types = TYPES_RUST,
    )

    private val SWIFT = Syntax(
        id = "swift",
        displayName = "Swift",
        extensions = setOf("swift"),
        lineComment = "//",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"'),
        verbatimStrings = listOf("\"\"\""),
        // Swift 没有字符字面量（'a' 不合法），charDelim 必须留空
        keywords = KEYWORDS_SWIFT,
        types = TYPES_SWIFT,
        // `#available`、`@State` 这类前缀构造：认成标识符后走 TAG 色
        identStarts = setOf('#', '@'),
    )

    private val CSHARP = Syntax(
        id = "csharp",
        displayName = "C#",
        extensions = setOf("cs"),
        lineComment = "//",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"'),
        charDelim = '\'',
        keywords = KEYWORDS_CS,
        types = TYPES_CS,
        identStarts = setOf('#'), // #region / #if 这类预处理指令
        numberSuffixes = setOf('f', 'F', 'd', 'D', 'm', 'M', 'u', 'U', 'l', 'L'),
    )

    private val PHP = Syntax(
        id = "php",
        displayName = "PHP",
        extensions = setOf("php", "phtml"),
        // PHP 的 `#` 也是行注释，但 Syntax 只有一个 lineComment 槽位，取更常用的 `//`
        lineComment = "//",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"', '\''),
        keywords = KEYWORDS_PHP,
        types = TYPES_PHP,
        identStarts = setOf('$'), // $var 是变量，着 TAG 色
    )

    private val RUBY = Syntax(
        id = "ruby",
        displayName = "Ruby",
        extensions = setOf("rb", "rbw", "rake", "gemspec"),
        fileNames = setOf("gemfile", "rakefile", "guardfile", "vagrantfile", "brewfile"),
        lineComment = "#",
        stringDelims = setOf('"', '\''),
        escape = null,
        keywords = KEYWORDS_RUBY,
        builtins = BUILTINS_RUBY,
        types = TYPES_RUBY,
        identStarts = setOf('@', '$'), // @ivar / $global
        numberSuffixes = setOf('r', 'i'),
    )

    private val LUA = Syntax(
        id = "lua",
        displayName = "Lua",
        extensions = setOf("lua"),
        lineComment = "--",
        blockComment = "--[[" to "]]",
        stringDelims = setOf('"', '\''),
        keywords = KEYWORDS_LUA,
        builtins = BUILTINS_LUA,
    )

    private val PERL = Syntax(
        id = "perl",
        displayName = "Perl",
        extensions = setOf("pl", "pm"),
        lineComment = "#",
        stringDelims = setOf('"', '\''),
        escape = null, // 单引号内不转义
        keywords = KEYWORDS_PERL,
        types = TYPES_PERL,
        identStarts = setOf('$', '@', '%', '&'),
    )

    private val R = Syntax(
        id = "r",
        displayName = "R",
        extensions = setOf("r"),
        lineComment = "#",
        stringDelims = setOf('"', '\''),
        keywords = KEYWORDS_R,
        builtins = BUILTINS_R,
        identChars = setOf('.'),
        numberSuffixes = setOf('L', 'i'),
    )

    private val DART = Syntax(
        id = "dart",
        displayName = "Dart",
        extensions = setOf("dart"),
        lineComment = "//",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"', '\''),
        verbatimStrings = listOf("\"\"\"", "'''"),
        keywords = KEYWORDS_DART,
        types = TYPES_DART,
        identStarts = setOf('@'), // @override 这类注解
    )

    private val SCALA = Syntax(
        id = "scala",
        displayName = "Scala",
        extensions = setOf("scala", "sc", "sbt"),
        lineComment = "//",
        blockComment = "/*" to "*/",
        stringDelims = setOf('"'),
        charDelim = '\'',
        verbatimStrings = listOf("\"\"\""),
        keywords = KEYWORDS_SCALA,
        types = TYPES_SCALA,
        numberSuffixes = setOf('L', 'l', 'f', 'F', 'd', 'D'),
    )

    private val POWERSHELL = Syntax(
        id = "powershell",
        displayName = "PowerShell",
        extensions = setOf("ps1", "psm1", "psd1"),
        lineComment = "#",
        blockComment = "<#" to "#>",
        stringDelims = setOf('"', '\''),
        escape = '`', // PowerShell 的转义符是反引号，不是反斜杠（路径恰好满是反斜杠）
        keywords = KEYWORDS_PS,
        builtins = BUILTINS_PS,
        identStarts = setOf('$'),
        identChars = setOf('-'),
        caseSensitive = false,
    )

    private val HASKELL = Syntax(
        id = "haskell",
        displayName = "Haskell",
        extensions = setOf("hs", "lhs"),
        lineComment = "--",
        blockComment = "{-" to "-}",
        stringDelims = setOf('"'),
        charDelim = '\'',
        keywords = KEYWORDS_HASKELL,
        types = TYPES_HASKELL,
        builtins = BUILTINS_HASKELL,
        numberSuffixes = setOf('L'),
    )

    // ==================== 第四类：补丁 ====================

    /**
     * 补丁（unified diff）。
     *
     * 走的是第四条扫描分支，所以这里**没有** `lineComment` / `stringDelims` 之类的字段：
     * 它们在这条分支上不会被读，填了属于「注释与代码矛盾」。识别完全按行首前缀做（见
     * `scanDiffLine`）。
     *
     * `.rej` 也挂在这里：`patch` 打不上时留下的拒绝文件就是补丁片段，同一套着色正好。
     */
    private val DIFF = Syntax(
        id = "diff",
        displayName = "Diff / Patch",
        extensions = setOf("diff", "patch", "rej"),
        diff = true,
    )

    /** 全部内置语法。顺序无关，查找走 [byExtension]、[byFileName]。 */
    val all: List<Syntax> = listOf(
        Syntax.PLAIN, MD, JSON, HTML, CSS, JS, PYTHON, KOTLIN,
        YAML, SHELL, SQL, CONFIG, JAVA, CPP, GO,
        RUST, SWIFT, CSHARP, PHP, RUBY, LUA, PERL, R, DART, SCALA, POWERSHELL, HASKELL,
        DOCKERFILE, MAKEFILE, CMAKE, HCL, PROTOBUF, GRAPHQL, DIFF,
    )

    private val byExtension: Map<String, Syntax> = buildMap {
        // 后出现的覆盖先出现的：jsx/tsx 归 JS 而不是 HTML，放在后面定义才有优先级。
        // 但 all 里 JS 定义在 HTML 之后，这里遍历顺序天然给出了这个优先级。
        all.forEach { syntax -> syntax.extensions.forEach { ext -> put(ext, syntax) } }
    }

    /** 文件名是**小写**存的，查表前把文件名也转小写（见 [Syntax.fileNames]） */
    private val byFileName: Map<String, Syntax> = buildMap {
        all.forEach { syntax -> syntax.fileNames.forEach { put(it.lowercase(), syntax) } }
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

    /**
     * 按文件名选语法：**先整名、后扩展名**，都认不出就当纯文本
     * ——不高亮也好过高亮错。
     *
     * 整名优先是必须的：`CMakeLists.txt` 的扩展名是 `.txt`，只看扩展名它永远是纯文本。
     */
    fun forFileName(name: String): Syntax {
        val lower = name.lowercase()
        byFileName[lower]?.let { return it }
        // 没有点的名字（`Makefile` 那种，整名规则已在上一步判过）拿到的是空串：
        // `substringAfterLast` 的 missingDelimiterValue 显式给了 `""`，所以不需要另判
        // 「名字本身就等于 ext」——那个条件在这种写法下永远不成立。
        val ext = lower.substringAfterLast('.', "")
        if (ext.isEmpty()) return Syntax.PLAIN
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
