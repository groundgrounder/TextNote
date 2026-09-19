#!/usr/bin/env python3
"""死代码扫描。改完 core/data 的声明面之后跑它。

守着的几件事，共同点是**都不会让构建失败**——所以只能靠一条静态检查盯住：

1. **没被引用的声明**：顶层 / `private` 各扫一遍。项目里还留着「以后可能用得上」的接口是
   最贵的债：它看起来是能力，实际是没人验证过的承诺面。
2. **只被赋值、从没被读的字段**：比死代码更值钱的是**死劳动**——算出来没人看的字段，
   如果它的赋值处还跨进程查了一次 provider，那就是每次刷新都白花的 IPC。
3. **只在签名里的函数参数**：四条扫描分支共用一套签名是刻意设计，但得在代码里说清楚；
   没说清楚的一律报出来（要留就加 `@Suppress("UNUSED_PARAMETER")`）。
4. **没人引用的资源**：字符串键、`values*/` 里的 color / style / dimen、按文件名寻址的
   drawable / mipmap / xml 文件。Android 的资源不裁剪（本项目没开 `shrinkResources`），
   没引用的资源会原样进包。

## 为什么必须用状态机剥注释

`re.sub(r"/\\*.*?\\*/", ...)` 会从**字符串内部**的 `/*` 一路吃到很远的 `*/`
（本项目的 `SyntaxRegistry` 里就有 `"/*" to "*/"` 这种字面量），把真实代码整段删掉，
于是下游报出来的「零引用」全是假阳性。剥注释还得**保留换行**，否则按行号做的检查全部错位。

另外两处同类陷阱：花括号配对必须跳过字符串与**字符字面量**（`'}'` 会被当成真括号，
函数体提前闭合），以及「字段的读取者」通常**在别的文件里**（必须全语料统计）。

## 用法

    python3 tools/dead_code.py               # 扫整个项目
    python3 tools/dead_code.py --self-test   # 用内置的人造死代码验扫描器本身

`--self-test` 存在的原因：**一个从不报警的检查脚本比没有检查更糟**。
「0 命中」和「扫描器根本没跑起来」在输出上长得一模一样，所以先把三类人造死代码喂进去，
确认每个检测器都能报出来，**并确认它不会误报那几个「用了但很像没用」的对照组**。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 语料 = 真正会被编译或运行的东西。`tools/` 也算：断言与设备脚本是**消费者**
# （Java 断言会调 `getUndoDepth()` 这类 Kotlin 属性，设备脚本会跑产品代码路径）。
DEFAULT_ROOTS = [ROOT / "app/src", ROOT / "tools"]

# ⚠️ 这些目录里的文本**不是消费者**，算进语料会把真死代码掩盖掉：
# `.workbuddy/` 里是审查报告与工作日志（它们会提到 `formatBytes`、`undoDepth` 这类名字，
# 但那些「引用」不构成任何东西在使用它）。
EXCLUDE_DIRS = {".workbuddy", "build", ".gradle", ".git", "__pycache__", ".kotlin"}
SCAN_EXT = {".kt", ".java", ".xml", ".py", ".sh"}

# 语料规模下界（见 main 里那段说明）：低于它就判「什么都没扫」而不是「很干净」。
MIN_FILES = 20
MIN_LINES = 5000

# Kotlin 属性在 Java 侧叫 getXxx() / isXxx()：断言文件是那样调的，漏了这个口径会把
# 「只被 Java 断言用到」的成员误报成死代码。
def _ref_patterns(name: str) -> list[str]:
    cap = name[:1].upper() + name[1:]
    return [
        r"(?<![A-Za-z0-9_])" + re.escape(name) + r"(?![A-Za-z0-9_])",
        r"(?<![A-Za-z0-9_])get" + re.escape(cap) + r"(?![A-Za-z0-9_])",
        r"(?<![A-Za-z0-9_])is" + re.escape(cap) + r"(?![A-Za-z0-9_])",
    ]


def refs(blob: str, name: str) -> int:
    return sum(len(re.findall(p, blob)) for p in _ref_patterns(name))


def strip_kotlin_comments(src: str) -> str:
    """剥掉注释，**保留换行**并按字符长度对齐（等长空白替换）。

    认四样东西：普通串（含 ``\\"``）、原始串 ``\"\"\"…\"\"\"``、字符字面量、可嵌套的块注释。
    保留换行是硬要求：剥完必须与原文逐行对齐，否则任何按行号的检查都会错位。
    """
    out: list[str] = []
    i, n = 0, len(src)

    def blank(chunk: str) -> str:
        return "".join("\n" if ch == "\n" else " " for ch in chunk)

    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j])
            i = j
            continue
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(src[i:j])
            i = j
            continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'" and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(src[i:j])
            i = j
            continue
        if c == "/" and nxt == "/":
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(blank(src[i:j]))
            i = j
            continue
        if c == "/" and nxt == "*":
            depth, j = 1, i + 2
            while j < n and depth > 0:
                if src.startswith("/*", j):
                    depth += 1
                    j += 2
                elif src.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            out.append(blank(src[i:j]))
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def brace_body(text: str, k: int) -> str:
    """从 `text[k]`（一个 `{`）起返回配对的那一段。

    必须跳过字符串 / 原始串 / 字符字面量里的括号——`if (peek() == '}')` 里的 `'}'`
    会把配对提前闭合，函数体被截短，于是报出一堆「参数没用」的假阳性。
    """
    depth, i, n = 0, k, len(text)
    while i < n:
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        c = text[i]
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
            continue
        if c == "'":
            i += 1
            while i < n and text[i] != "'" and text[i] != "\n":
                i += 2 if text[i] == "\\" else 1
            i += 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[k:i + 1]
        i += 1
    return text[k:]


def rel(path: Path, root: Path | None) -> str:
    if root is None:
        return str(path)
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def load_corpus(roots: list[Path]) -> tuple[list[tuple[Path, str, str]], str]:
    """返回 (文件列表, 全项目语料)。文件列表的第三项是**剥过注释**的正文（.kt 才有意义）。"""
    files: list[tuple[Path, str, str]] = []
    for base in roots:
        if not base.exists():
            continue
        for p in sorted(base.rglob("*")):
            if not p.is_file() or p.suffix not in SCAN_EXT:
                continue
            if any(part in EXCLUDE_DIRS for part in p.parts):
                continue
            try:
                raw = p.read_text(encoding="utf-8")
            except (UnicodeDecodeError, OSError):
                continue
            stripped = strip_kotlin_comments(raw) if p.suffix == ".kt" else raw
            files.append((p, raw, stripped))
    blob = "\n".join(s for _, _, s in files)
    return files, blob


# ============================ 各条检查 ============================
# 约定：每个检查器返回 [(定位字符串, 说明)]，空列表 = 这一条干净。


def check_unused_imports(files, blob) -> list[tuple[str, str]]:
    """文件里 import 进来的简单名在正文里一次都没出现。

    判定不能带 `(?<!\\.)`：`.dp` / `.padding()` / `Icons.Outlined.X` 全是「前面有点号」的形式，
    带上就会把它们全判成未用。`by` 委托用的 `getValue`/`setValue` 字面量不出现，必须白名单。
    """
    white = {"getValue", "setValue", "component1", "component2", "provideDelegate"}
    out = []
    for path, raw, stripped in files:
        if path.suffix != ".kt":
            continue
        body = "\n".join(l for l in stripped.split("\n") if not l.lstrip().startswith("import "))
        for m in re.finditer(r"^import\s+([\w.]+)(\.\*)?$", raw, re.M):
            full, star = m.group(1), m.group(2)
            name = full.split(".")[-1]
            if star or name in white:
                continue
            if not re.search(r"(?<![A-Za-z0-9_])" + re.escape(name) + r"(?![A-Za-z0-9_])", body):
                out.append((f"{rel(path, ROOT)}", f"未用的 import：{full}"))
    return out


def check_zero_ref_declarations(files, blob) -> list[tuple[str, str]]:
    """零引用的声明。

    三类必踩的误报（判之前先问「谁来调它」）：
    - **框架覆写**：`override fun onNewIntent` 由框架虚调用，源码里当然没人按名引用 → 跳过；
    - **Kotlin 属性在 Java 侧叫 getXxx()** → 见 `refs()`；
    - **`private` 与顶层要分开扫**：private 只在本文件可见，所以在文件内数；
      顶层的消费者可能在别的文件，要在全语料里数。
    """
    private_re = re.compile(
        r"^[ \t]*private\s+(?:const\s+|inline\s+|suspend\s+|operator\s+)*"
        r"(?:val|var|fun|class|object)\s+(\w+)",
        re.M,
    )
    # ⚠️ `^[ \t]*` 不能写成 `^\s*`：`\s` 会跨行，把被剥空的注释整段吞进缩进里。
    # 也刻意**允许前导空白**，否则类里缩进的成员（`fun clampIndex()` 这种）全被漏掉——
    # 那正是「零引用的成员函数」，是这一类里最该扫到的东西。
    top_re = re.compile(
        r"(?m)^(?P<ind>[ \t]*)(?P<ann>(?:@\w+(?:\([^)]*\))?[ \t]*\n)*)"
        r"(?:public\s+|internal\s+|private\s+)?"
        r"(?:const\s+|inline\s+|suspend\s+|operator\s+)?"
        r"(?P<kind>(?:data|enum|sealed|value)\s+(?:class|interface)"
        r"|val|var|fun|class|object|interface)\s+(?P<name>\w+)"
    )
    out = []
    for path, _, stripped in files:
        if path.suffix != ".kt":
            continue
        # ---- private：在本文件里数 ----
        for m in private_re.finditer(stripped):
            name = m.group(1)
            if len(re.findall(r"(?<![A-Za-z0-9_])" + re.escape(name) + r"(?![A-Za-z0-9_])", stripped)) <= 1:
                out.append((rel(path, ROOT), f"零引用的 private 声明：{name}"))
        # ---- 顶层 / 成员：在全语料里数 ----
        for m in top_re.finditer(stripped):
            if "override" in m.group("ann"):
                continue
            name = m.group("name")
            if refs(blob, name) <= 1:
                out.append((rel(path, ROOT), f"零引用的顶层声明：{name}"))
    return out


def check_write_only_fields(files, blob) -> list[tuple[str, str]]:
    """只被赋值、从没被读的 `val`/`var`（含 data class 的构造参数）。

    读取者通常在**别的文件**里，所以读写统计一律在全语料上做——只在声明所在文件里数的话，
    `Syntax.lineComment`、`Diagnostic.start` 这种都会变成假阳性。
    已知边界：`data class` 自动生成的 `toString`/`equals` 也算「读」，所以一个只用于调试输出的
    字段不会被报出来——这是刻意留的宽容，宁可漏报也不误报。
    """
    decl = re.compile(r"^[ \t]*(?:@\w+[ \t]+)*(?:private |internal |public )?(?:val|var) (\w+)[ \t]*:")
    out = []
    for path, _, stripped in files:
        if path.suffix != ".kt":
            continue
        for line in stripped.split("\n"):
            m = decl.match(line)
            if not m:
                continue
            name = m.group(1)
            member = len(re.findall(r"[.?][ \t]*" + re.escape(name) + r"(?![A-Za-z0-9_])", blob))
            named_write = len(re.findall(r"(?<![A-Za-z0-9_.])" + re.escape(name) + r"[ \t]*=", blob))
            bare = len(re.findall(r"(?<![A-Za-z0-9_.])" + re.escape(name) + r"(?![A-Za-z0-9_])", blob))
            java_side = refs(blob, name) - bare
            # `- 1` 是**声明处自己**那一次：不减掉的话每个字段都至少算出 1 次「读」，
            # 这一条就永远报不出东西（自检当场抓到了这个错）。
            reads = member + max(0, bare - named_write - 1) + max(0, java_side)
            if reads <= 0:
                out.append((rel(path, ROOT), f"只写不读的字段：{name}"))
    return out


def check_dead_enum_members(files, blob) -> list[tuple[str, str]]:
    """枚举里从没被引用过的成员。

    ⚠️ 遍历 `entries` 的写法**不算引用**——所以一个只靠通用迭代生效的成员会被这里报出来。
    真遇到时要看它是不是「行为由资源/数据驱动」的那种，是的话在声明处加一句说明。
    """
    out = []
    for path, _, stripped in files:
        if path.suffix != ".kt":
            continue
        for m in re.finditer(r"enum class (\w+)[^{]*\{", stripped):
            enum_name = m.group(1)
            j, depth = m.end() - 1, 0
            while j < len(stripped):
                if stripped[j] == "{":
                    depth += 1
                elif stripped[j] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            body = stripped[m.end():j].split(";")[0]
            # 按逗号切，**不能**用 `^[ \t]*(名字)[ \t]*(,|$)` 那种行锚定写法：
            # 枚举体常写成一行（`enum class E { A, B }`），行锚定只看得到第一个成员。
            for part in body.split(","):
                nm = part.strip().split("(")[0].strip()
                if re.fullmatch(r"[A-Z][A-Z0-9_]*", nm) and refs(blob, nm) <= 1:
                    out.append((rel(path, ROOT), f"零引用的枚举成员：{enum_name}.{nm}"))
    return out


def _params_of(text: str, open_paren: int) -> tuple[list[str], int]:
    """从 `(` 起取出参数名与配对 `)` 的位置。"""
    depth, j = 0, open_paren
    while j < len(text):
        if text[j] == "(":
            depth += 1
        elif text[j] == ")":
            depth -= 1
            if depth == 0:
                break
        j += 1
    parts, buf, d = [], "", 0
    for ch in text[open_paren + 1:j]:
        if ch in "([<{":
            d += 1
        elif ch in ")]>}":
            d -= 1
        if ch == "," and d == 0:
            parts.append(buf)
            buf = ""
        else:
            buf += ch
    parts.append(buf)
    names = []
    for p in parts:
        nm = p.strip().split("=")[0].split(":")[0].strip()
        if re.fullmatch(r"\w+", nm):
            names.append(nm)
    return names, j


def check_unused_params(files, blob) -> list[tuple[str, str]]:
    """函数体里一次都没出现的参数。

    只报 `internal`/`public` 的：kotlinc 自己会报 private 的未用参数与未用局部变量，
    所以只要编译 0 警告，那两类就是干净的。想知道「就是故意不读」的，在函数上写
    `@Suppress("UNUSED_PARAMETER")` 并说明原因（本项目里四条扫描分支共用签名就是这种情形）。
    """
    decl = re.compile(
        r"(?m)^(?P<ind>[ \t]*)(?P<ann>(?:@\w+(?:\([^)]*\))?[ \t]*\n)*)"
        r"(?P<mods>(?:private |internal |public |override |suspend |inline |operator )*)"
        r"fun\s+(?P<name>\w+)\s*\("
    )
    out = []
    for path, _, stripped in files:
        if path.suffix != ".kt":
            continue
        for m in decl.finditer(stripped):
            mods, ann = m.group("mods"), m.group("ann")
            if "override" in mods:
                continue
            # @Suppress 可能落在**紧邻的前两行**上，而不在同一个匹配里：
            # 这个正则既能从注解那行开始匹配，也能从 `fun` 那行开始匹配（两处都命中），
            # 只认 `ann` 的话从 `fun` 行那次匹配仍会把它报出来（自检抓到的）。
            head = stripped[:m.start()].rstrip("\n").split("\n")[-2:]
            if "UNUSED_PARAMETER" in ann or any("UNUSED_PARAMETER" in l for l in head):
                continue
            names, close = _params_of(stripped, m.end() - 1)
            if not names:
                continue
            k = close + 1
            while k < len(stripped) and stripped[k] in " \t\r\n:":
                k += 1
            while k < len(stripped) and stripped[k] not in "{=\n":
                k += 1
            if k >= len(stripped):
                continue
            if stripped[k] == "{":
                body = brace_body(stripped, k)
            else:
                # 表达式体：吃到下一个缩进 <= 声明行的非空行
                indent = len(m.group("ind"))
                lines = stripped[k:].split("\n")
                acc = [lines[0]]
                for ln in lines[1:]:
                    if ln.strip() and (len(ln) - len(ln.lstrip())) <= indent:
                        break
                    acc.append(ln)
                body = "\n".join(acc)
            for nm in names:
                if nm == "_":
                    continue
                if not re.search(r"(?<![A-Za-z0-9_])" + re.escape(nm) + r"(?![A-Za-z0-9_])", body):
                    out.append((rel(path, ROOT), f"参数在函数体里没出现：{m.group('name')}({nm})"))
    return out


def check_unused_strings(files, blob) -> list[tuple[str, str]]:
    """`values*/strings.xml` 里没人引用的键。

    键集合取**四套译文的并集**：某一套少键是 `check_locales.py` 的事，这里只管「有没有人用」。
    """
    out = []
    keys: dict[str, Path] = {}
    for path, raw, _ in files:
        if path.suffix != ".xml" or path.name != "strings.xml":
            continue
        for k in re.findall(r'<string name="([^"]+)"', raw):
            keys.setdefault(k, path)
    for k, path in sorted(keys.items()):
        if not re.search(r"R\.string\." + re.escape(k) + r"(?![A-Za-z0-9_])", blob) and not re.search(
            r"@string/" + re.escape(k) + r"(?![A-Za-z0-9_])", blob
        ):
            out.append((rel(path, ROOT), f"没被引用的字符串：{k}"))
    return out


def check_unused_values_entries(files, blob) -> list[tuple[str, str]]:
    """`values*/` 里没人引用的 `<color>` / `<style>` / `<dimen>` / `<bool>` / `<integer>`。

    ⚠️ `values*/` 是资源**容器**：文件名本身没人引用（所以它不进「未引用文件」那条），
    这里查的是容器里的**键名**。引用形式要把 `@color/` `@style/` 这些前缀都算上。
    """
    kinds = ("color", "style", "dimen", "bool", "integer")
    out = []
    for path, raw, _ in files:
        if path.suffix != ".xml" or not any(p.startswith("values") for p in path.parts):
            continue
        for kind in kinds:
            for m in re.finditer(r"<%s name=\"([^\"]+)\"" % kind, raw):
                n = m.group(1)
                if re.search(r"@%s/%s(?![A-Za-z0-9_])" % (kind, re.escape(n)), blob):
                    continue
                if re.search(r"R\.%s\.%s(?![A-Za-z0-9_])" % (kind, re.escape(n)), blob):
                    continue
                out.append((rel(path, ROOT), f"没被引用的资源：<{kind} name=\"{n}\">"))
    return out


def check_dead_resource_files(files, blob) -> list[tuple[str, str]]:
    """按**文件名**寻址的资源没人引用：drawable / mipmap / xml。

    `values*/` 不在其列（里面的条目按键名寻址，见上一条）。
    图标尤其容易漏：`ic_launcher_round.xml` 就是「文件在、manifest 里却没声明 roundIcon」，
    它不会让任何东西出错，只是白占体积。
    """
    out = []
    for path, _, _ in files:
        if path.suffix not in (".xml", ".png", ".webp", ".jpg"):
            continue
        parts = path.parts
        if not any(seg in ("drawable", "mipmap") or seg.startswith("mipmap-") for seg in parts):
            continue
        if any(seg.startswith("values") for seg in parts):
            continue
        stem = path.stem
        if re.search(r"@(drawable|mipmap|xml)/" + re.escape(stem) + r"(?![A-Za-z0-9_])", blob):
            continue
        if re.search(r"R\.(drawable|mipmap|xml)\.\w*" + re.escape(stem), blob):
            continue
        out.append((rel(path, ROOT), "按文件名没人引用的资源"))
    return out


def check_dead_files(files, blob) -> list[tuple[str, str]]:
    """整个 `.kt` 文件里没有任何顶层声明被别处引用。

    只看 `.kt`：manifest 与资源文件是「被系统按约定加载」的，没有源码引用，
    拿这套口径判它们会满盘误报。
    """
    decl = re.compile(
        r"(?m)^(?:@\w+(?:\([^)]*\))?[ \t]*\n)*(?:public |internal |private )?"
        r"(?:const |inline |suspend |operator )?"
        r"(?:val|var|fun|class|object|interface)\s+(\w+)"
    )
    out = []
    for path, _, stripped in files:
        if path.suffix != ".kt":
            continue
        names = set(decl.findall(stripped))
        names |= set(re.findall(r"(?m)^enum class (\w+)", stripped))
        names |= set(re.findall(r"(?m)^data class (\w+)", stripped))
        names |= set(re.findall(r"(?m)^sealed interface (\w+)", stripped))
        names = {n for n in names if not n.startswith("_")}
        if not names:
            continue
        if not any(refs(blob, n) > 1 or ("@" + n) in blob for n in names):
            out.append((rel(path, ROOT), f"整个文件的声明都零引用：{sorted(names)}"))
    return out


CHECKERS = [
    ("未用的 import", check_unused_imports),
    ("零引用的声明", check_zero_ref_declarations),
    ("只写不读的字段", check_write_only_fields),
    ("零引用的枚举成员", check_dead_enum_members),
    ("函数体里没出现的参数", check_unused_params),
    ("没被引用的字符串资源", check_unused_strings),
    ("values 里没被引用的条目", check_unused_values_entries),
    ("按文件名没被引用的资源", check_dead_resource_files),
    ("整个文件零引用", check_dead_files),
]


# ============================ 自检 ============================
# 内置一份「人造死代码 + 对照组」。每个检测器都必须报出自己那一类，
# 同时**不许**报出对照组——后者挡的是「把用得好好 的东西判成死的」。
#
# 多报（fixture 里那些没有外部引用的入口）是允许的：本自检只要求
# 「该报的都报了、不该报的一个都没报」，不要求「一处不多」。
FIXTURE = {
    # 死代码集中在这一个文件里：未用 import、零引用类、只写字段、三种未用参数、
    # 零引用 private、零引用枚举成员
    "app/src/main/java/fake/Fake.kt": """package fake

import java.util.zip.CRC32

class TotallyUnusedThing {
    val writeOnly: Int = 1

    fun braceUnused(p: Int, q: Int): Int {
        return p
    }

    fun exprUnused(a: Int, b: Int): Int = a

    @Suppress("UNUSED_PARAMETER")
    fun suppressed(unused: Int, p: Int): Int = p

    private fun neverCalled(): Int = 0

    fun touchEnum(): Boolean = E.USED == E.USED

    enum class E { USED, NEVER_USED }
}
""",
    # 对照组：`usedTop` 被下个文件用着，不许判死
    "app/src/main/java/fake/Shared.kt": """package fake

val usedTop: Int = 1
""",
    # 对照组：资源引用（字符串键 / 颜色）都写在这里，真实形态
    "app/src/main/java/fake/Consumers.kt": """package fake

fun consumer(): Int {
    val label = R.string.used_key
    return usedTop + label.length
}
""",
    # 整个文件零引用
    "app/src/main/java/fake/Orphan.kt": """package fake

class OrphanThing {
    fun alsoOrphan(): Int = 1
}
""",
    "app/src/main/AndroidManifest.xml": """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:icon="@mipmap/ic_launcher" />
</manifest>
""",
    # 对照组：这份被 manifest 引用了，不许判成孤儿资源
    "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml": """<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/used_color" />
</adaptive-icon>
""",
    # 没人引用的资源文件
    "app/src/main/res/mipmap-anydpi-v26/orphan.xml": """<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android" />
""",
    "app/src/main/res/values/strings.xml": """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="used_key">用了</string>
    <string name="unused_key">没人用</string>
</resources>
""",
    "app/src/main/res/values/colors.xml": """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="used_color">#111111</color>
    <color name="unused_color">#222222</color>
</resources>
""",
}


def _fixture_corpus() -> tuple[list[tuple[Path, str, str]], str]:
    files = []
    for name, raw in FIXTURE.items():
        p = Path("/fixture") / name
        files.append((p, raw, strip_kotlin_comments(raw) if p.suffix == ".kt" else raw))
    blob = "\n".join(s for _, _, s in files)
    return files, blob


def self_test() -> int:
    files, blob = _fixture_corpus()
    expect = {
        "未用的 import": ["CRC32"],
        "零引用的声明": ["neverCalled", "TotallyUnusedThing"],
        "只写不读的字段": ["writeOnly"],
        "零引用的枚举成员": ["NEVER_USED"],
        "函数体里没出现的参数": ["braceUnused(q)", "exprUnused(b)"],
        "没被引用的字符串资源": ["unused_key"],
        "values 里没被引用的条目": ["unused_color"],
        "按文件名没被引用的资源": ["orphan"],
        "整个文件零引用": ["Orphan.kt"],
    }
    # 对照组：报到这些就是误报。
    # ⚠️ 这里必须写**完整消息尾巴**而不是裸名字：`used_key` 是 `unused_key` 的子串，
    # 裸名字比较会把「正确报出 unused_key」当成「误报 used_key」（自检自己踩过，同样的坑
    # 在 `used_color` / `unused_color` 上又踩了一次）。
    forbid = {
        "零引用的声明": ["零引用的顶层声明：usedTop", "零引用的 private 声明：usedTop"],
        "只写不读的字段": ["只写不读的字段：usedTop"],
        "零引用的枚举成员": ["零引用的枚举成员：E.USED"],
        "函数体里没出现的参数": [
            "参数在函数体里没出现：suppressed(unused)",
            "参数在函数体里没出现：braceUnused(p)",
            "参数在函数体里没出现：exprUnused(a)",
        ],
        "没被引用的字符串资源": ["没被引用的字符串：used_key"],
        "values 里没被引用的条目": ['没被引用的资源：<color name="used_color">'],
        "按文件名没被引用的资源": ["ic_launcher.xml"],
        "整个文件零引用": ["Shared.kt"],
    }

    bad = 0
    for label, fn in CHECKERS:
        msgs = [f"{loc} {msg}" for loc, msg in fn(files, blob)]
        missing = [e for e in expect.get(label, []) if not any(e in m for m in msgs)]
        noise = [f for f in forbid.get(label, []) if any(m.endswith(f) for m in msgs)]
        if missing or noise:
            bad += 1
            print(f"✗ 自检失败 · {label}")
            if missing:
                print(f"    没报出来：{missing}\n    实际：{' | '.join(msgs) or '(空)'}", file=sys.stderr)
            if noise:
                print(f"    误报：{noise}", file=sys.stderr)
        else:
            print(f"✓ 自检通过 · {label}")

    if bad:
        print(f"\n✗ {bad} 个检测器没通过自检——它报出来的东西不可信，先修它", file=sys.stderr)
        return 1
    print(f"\n✓ {len(CHECKERS)} 个检测器全部通过自检（报得出来 + 不误报对照组）")
    return 0


# ============================ 主流程 ============================
def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    roots = [Path(a).resolve() for a in sys.argv[1:] if not a.startswith("-")]
    roots = roots or DEFAULT_ROOTS
    files, blob = load_corpus(roots)
    lines = sum(len(t.split("\n")) for _, _, t in files)

    # 「扫了个寂寞」的防线：语料为空或异常小时**不许报通过**。
    # CI 门禁最怕的就是这条——`DEFAULT_ROOTS` 写错、目录改名、`SCAN_EXT` 手滑，
    # 结果都是「0 命中、绿灯」，和「项目很干净」在输出上长得一模一样。
    # 阈值取的是当前实际规模（约 83 个文件 / 1.8 万行）的一个宽松下界。
    if len(files) < MIN_FILES or lines < MIN_LINES:
        print(
            f"✗ 语料异常：只扫到 {len(files)} 个文件 / {lines} 行（下界是 "
            f"{MIN_FILES} 个 / {MIN_LINES} 行）。\n"
            f"  根目录：{[str(r) for r in roots]}\n"
            "  这条不是「项目很干净」，是**什么都没扫**——先确认目录与 --self-test。",
            file=sys.stderr,
        )
        return 1

    findings: list[tuple[str, str, str]] = []
    for label, fn in CHECKERS:
        for loc, msg in fn(files, blob):
            findings.append((label, loc, msg))

    # 整份零引用的文件就不再逐条列它里面的声明了——那几行是同一条结论的碎片，
    # 而这份清单是给人读的：一条「这个文件没人碰」比它下面挂着的十条「类/方法没人用」有用。
    dead_files = {loc for cat, loc, _ in findings if cat == "整个文件零引用"}
    collapsed = 0
    if dead_files:
        kept = []
        for f in findings:
            if f[1] in dead_files and f[0] != "整个文件零引用":
                collapsed += 1
            else:
                kept.append(f)
        findings = kept

    if findings:
        print(
            f"✗ 死代码扫描：{len(files)} 个文件 / {lines} 行，发现 {len(findings)} 处"
            + (f"（另有 {collapsed} 处声明落在整份零引用的文件里，已折叠）" if collapsed else "")
            + "：\n",
            file=sys.stderr,
        )
        for label, loc, msg in findings:
            print(f"  - [{label}] {loc}: {msg}", file=sys.stderr)
        print(
            "\n确认是误报的，把口径补进 tools/dead_code.py 的说明里再放宽；\n"
            "确实是死代码的，删掉；确实是「留着的」，在代码里写明原因（如 @Suppress）。",
            file=sys.stderr,
        )
        return 1

    print(f"✓ 死代码扫描：{len(files)} 个文件 / {lines} 行，{len(CHECKERS)} 项口径全过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
