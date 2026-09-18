#!/usr/bin/env python3
"""多语言资源的一致性检查。改动说明前先跑它。

守着四件靠人记必然记漏的事：

1. **各 `values*/strings.xml` 的键集合必须完全一致**。少了键不会报错 —— 该语言会静默回落到
   兜底英文，界面上是「一页里两句英文夹着」，很难在 review 时看出来。
2. **同一个键里的格式占位符必须一致**。占位符写错了，`%2$s` 会直接抛 `MissingFormatArgumentException`
   崩掉，或者更糟：参数串位，把行数显示成字符数。
3. **`locales_config.xml` 与 `values-*` 目录一一对应**。声明了却没资源 = 系统「应用语言」里
   出现选了没反应的假选项；有资源却没声明 = 用户根本选不到这套译文。
4. **`AppLanguage` 枚举与 `locales_config.xml` 一一对应** —— 这是语言清单的**第三处**
   （前两处是资源目录与 localeConfig）。枚举里少了某语言，应用内的选择器就选不到它
   （但系统「应用语言」里能选）；枚举里多了一种、系统里没声明，两处入口给出的可选范围又对不上。
   用户看到的只是「这里怎么没有那一项」，看不出是配置漏了。
   新增语言时这里、`values-xx/`、`locales_config.xml` 三处必须一起改。

只往目录里找，不做任何修复。有问题的项全部列出后再以状态码 1 退出。

可选参数：资源目录（默认 `app/src/main/res`）。只为能拿一份故意做坏的副本试它真的会报错 ——
一个从不报警的检查脚本比没有检查更糟。
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / "app/src/main/res"
LOCALES_CONFIG = RES / "xml/locales_config.xml"
# 语言清单的第三处：应用内选择器渲染的就是这个枚举。
APP_LANGUAGE = ROOT / "app/src/main/java/com/textnote/app/data/AppLanguage.kt"

# Android 里 `values-` 后面跟着的这些限定符与语言无关。判断某个目录是不是语言变体时排除它们，
# 免得把 `values-night` 当成某个叫 night 的语言。
NON_LOCALE_QUALIFIERS = re.compile(
    r"^(night|notnight|land|port|car|desk|television|watch|round|notround|"
    r"anydpi|nodpi|tvdpi|ldpi|mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi|"
    r"small|normal|large|xlarge|long|notlong|highdr|meddr|lowdr|"
    r"sw\d+dp|w\d+dp|h\d+dp|v\d+)$"
)

# `%1$d` / `%2$s` 这种带实参序号的写法，以及 `%%` 转义
SPECIFIER = re.compile(r"%(?:(\d+)\$)?([sdfxX])|%%")


def values_dirs() -> dict[str, Path]:
    """`语言标签 -> strings.xml 路径`。只收含有 strings.xml 的目录。"""
    found: dict[str, Path] = {}
    for d in sorted(RES.glob("values*")):
        xml = d / "strings.xml"
        if not d.is_dir() or not xml.is_file():
            continue
        qualifier = d.name[len("values"):].lstrip("-")
        if not qualifier:
            found["en"] = xml
        elif NON_LOCALE_QUALIFIERS.match(qualifier):
            continue
        elif qualifier.startswith("b+"):
            # 目录名里 BCP-47 的 `-` 被写成了 `+`：`values-b+zh+Hans` 就是 `zh-Hans`
            found[qualifier[2:].replace("+", "-")] = xml
        else:
            found[qualifier] = xml
    return found


def declared_in_config() -> list[str]:
    root = ET.parse(LOCALES_CONFIG).getroot()
    ns = "{http://schemas.android.com/apk/res/android}"
    return [el.get(f"{ns}name") for el in root.findall("locale")]


def declared_in_app() -> list[str]:
    """从 `AppLanguage` 枚举里读出应用内能选的语言标签。

    枚举项形如 `CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),` —— 缩进 4 空格、名字全大写、
    后面跟两个字符串字面量。`SYSTEM` 那一项的标签是空串，表示「跟随系统」，不是一种语言，跳过。

    文件不在时跳过这一项：自测可以把资源目录指到别处（见模块 docstring），那时这里本来就查不到。
    """
    if not APP_LANGUAGE.is_file():
        return []
    text = APP_LANGUAGE.read_text(encoding="utf-8")
    body = re.search(r"enum class AppLanguage\b.*?\{", text, re.S)
    if body is None:
        raise SystemExit(f"{APP_LANGUAGE}: 找不到 `enum class AppLanguage`，脚本与代码对不上了")
    tags = re.findall(r'^\s{4}[A-Z][A-Z_]*\("([^"]*)"\s*,', text[body.end():], re.M)
    return [t for t in tags if t]


def expected_dir(locale: str) -> str:
    """`zh-Hans` 这种带地区/书写系统的写成 BCP-47 的 `b+` 目录名，纯语言码直接拼。"""
    return "values" if locale == "en" else f"values-{locale}" if "-" not in locale else f"values-b+{locale}"


def read_strings(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    # `<string>` 之外的（plurals / string-array）本项目没有，真加了也应该被这层看见，一并收。
    entries: dict[str, str] = {}
    for el in root:
        name = el.get("name")
        if name is None:
            continue
        if name in entries:
            raise SystemExit(f"{path}: 键 `{name}` 重复定义")
        entries[name] = el.text or ""
    return entries


def specifiers(text: str) -> list[str]:
    return sorted(m.group(0) for m in SPECIFIER.finditer(text))


def bare_percent(text: str) -> list[str]:
    """挑出「不是合法占位符的 `%`」——它会让 `String.format` 直接崩。"""
    stripped = SPECIFIER.sub("", text)
    return ["%" for _ in stripped.split("%")[1:]]


def rel(path: Path) -> str:
    """报告里显示用。资源目录被指到仓库外（自测）时退回绝对路径。"""
    return str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path)


def main() -> int:
    problems: list[str] = []

    dirs = values_dirs()
    declared = declared_in_config()
    if len(set(declared)) != len(declared):
        problems.append(f"{LOCALES_CONFIG.name} 里有重复的 locale 声明")

    # 语言清单的第三处：应用内选择器。与 localeConfig 双向对齐，否则两处入口给出的可选范围
    # 不一致，而用户只会觉得「这里怎么少了一项」，看不出是配置漏了。
    in_app = declared_in_app()
    if in_app:
        for locale in declared:
            if locale not in in_app:
                problems.append(
                    f"`{locale}` 在 {LOCALES_CONFIG.name} 里声明了，但 AppLanguage 枚举里没有 —— "
                    f"应用内的语言选择器选不到它"
                )
        for locale in in_app:
            if locale not in declared:
                problems.append(
                    f"AppLanguage 枚举里有 `{locale}`，但 {LOCALES_CONFIG.name} 没声明 —— "
                    f"系统「应用语言」里选不到"
                )

    for locale in declared:
        if locale not in dirs:
            problems.append(
                f"声明了 `{locale}`，但找不到 {expected_dir(locale)}/strings.xml —— "
                f"系统里会多出一个选了没反应的假选项"
            )
    for locale, path in dirs.items():
        if locale not in declared:
            problems.append(f"{rel(path)} 有译文，但 {LOCALES_CONFIG.name} 没声明 `{locale}`")

    if "en" not in dirs:
        problems.append("找不到兜底的 values/strings.xml")
        print("\n".join(problems), file=sys.stderr)
        return 1

    tables = {locale: read_strings(path) for locale, path in dirs.items()}
    reference = tables["en"]

    for locale, table in tables.items():
        if locale == "en":
            continue
        path = rel(dirs[locale])
        for key in sorted(reference.keys() - table.keys()):
            problems.append(f"{path}: 缺键 `{key}`（会静默回落成英文）")
        for key in sorted(table.keys() - reference.keys()):
            problems.append(f"{path}: 多出键 `{key}`（values/ 里没有，永远不会被用到）")

    for key, source in reference.items():
        want = specifiers(source)
        for locale, table in tables.items():
            if locale == "en" or key not in table:
                continue
            got = specifiers(table[key])
            if got != want:
                problems.append(
                    f"{rel(dirs[locale])}: 键 `{key}` 的占位符是 {got or ['(无)']}，"
                    f"应为 {want or ['(无)']}"
                )
            for _ in bare_percent(table[key]):
                problems.append(f"{rel(dirs[locale])}: 键 `{key}` 里有非占位符的 `%`")

    total = len(tables)
    keys = len(reference)
    if problems:
        print(f"✗ {total} 套译文 / {keys} 个键，发现 {len(problems)} 处不一致：\n", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1

    summary = "、".join(f"{locale}({len(table)})" for locale, table in tables.items())
    print(f"✓ {total} 套译文、{keys} 个键、占位符一致；语言清单三处对齐：{summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
