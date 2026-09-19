#!/usr/bin/env python3
"""验证「文件名 → 语法」的识别在**设备上**真的走通。

JVM 断言（`tools/checks/CheckSyntaxRegistry.java`）测的是 `SyntaxRegistry.forFileName`
这一层；这里补的是它**后面**那一段：文件名的取法（`DocumentRepository.displayName`）、
自动识别落进 `EditorViewModel`、以及状态栏把它读出来。中间任何一环断了，断言全绿而用户
看到的仍是纯文本。

判据是状态栏的 content-desc `语法：<名字>（自动识别）`——一句话同时给出「认成哪门语言」
与「这个结果是自动的」。不用截图比色：着色对不对由 JVM 断言管，这里只管**识别**。

⚠️ 全程 **不能** `pm clear`：那会清掉「按文件记住的手动语法」（`SyntaxOverrides`），
但本脚本从不手动选语法，所以不受影响；保持不 clear 是为了不打扰用户已有状态。

用法：
    python3 tools/device/verify_syntax_detect.py
    python3 tools/device/verify_syntax_detect.py --keep   # 保留设备上的探针文件
"""
import argparse
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import ADB, ensure_awake  # noqa: E402

PKG = "com.textnote.app"

# 探针文件放独立子目录：这样能整目录删掉，也不会与用户 Documents 里的同名文件打架。
# MediaStore 会递归扫到它，且 `_display_name` 只取文件名部分——所以**整名匹配的用例
# 必须用精确的文件名**（`Makefile` 而不是 `tn_detect_Makefile`：后者本来就该判成纯文本）。
REMOTE_DIR = "/sdcard/Documents/tn_probe"

# (设备上的文件名, 文件内容, 期望的语法显示名)
# 三条是**整名匹配**（光看扩展名认不出来），五条是新语言，三条是回归（老行为不能被抢走）。
CASES = [
    ("main.rs", "fn main() {\n    let s = \"hi\";\n}\n", "Rust"),
    ("Makefile", "CFLAGS = -O2\nall: build\n", "Makefile"),
    ("dockerfile", "FROM alpine\nRUN apk add curl\n", "Dockerfile"),
    ("CMakeLists.txt", "project(demo)\n", "CMake"),
    ("notes.lua", "local x = 1\nprint(x)\n", "Lua"),
    ("setup.ps1", "$x = Get-Date\nWrite-Host $x\n", "PowerShell"),
    ("schema.graphql", "type Query {\n  a: String\n}\n", "GraphQL"),
    ("main.tf", "resource \"a\" \"b\" {\n  x = 1\n}\n", "HCL / Terraform"),
    # 回归：老语言与「认不出就是纯文本」都不能被新增的规则抢走
    ("Main.kt", "fun main() {}\n", "Kotlin"),
    ("fix.patch", "-  total = old\n+  total = new\n", "Diff / Patch"),
    ("notes.txt", "plain text\n", "Plain Text"),
    ("README", "no extension here\n", "Plain Text"),
]

pass_count = 0
fails = []


def sh(cmd, timeout=25):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout).stdout


def ui():
    """取当前界面。每次先删旧 dump——失败时 uiautomator 会留下上一次的 /sdcard/ui.xml，
    pull 回来的是旧界面，据此断言会得到假阴性。屏幕休眠时 dump 必返 `null root node`。"""
    ensure_awake()
    for _ in range(3):
        sh(f"{ADB} shell rm -f /sdcard/ui.xml")
        if "dumped" in sh(f"{ADB} shell uiautomator dump /sdcard/ui.xml"):
            out = sh(f"{ADB} shell cat /sdcard/ui.xml")
            if out.strip():
                return out
        time.sleep(0.6)
    return ""


# 状态栏语法名的 content-desc。**两种界面语言都要认**：模拟器多半是英文，
# 而应用文案是跟着界面语言走的（`syntax_desc_auto` 有两种写法）。
DESC_PATTERNS = (
    re.compile(r"^语法：(.+)（(自动识别|手动指定)）$"),
    re.compile(r"^Syntax: (.+) \((auto|manual)\)$"),
)
MANUAL_WORDS = ("手动指定", "manual")


def syntax_state(xml):
    """状态栏读出的 (语法名, 是否手动)；读不到返回 (None, None)"""
    for m in re.finditer(r'content-desc="([^"]*)"', xml):
        for pattern in DESC_PATTERNS:
            g = pattern.match(m.group(1))
            if g:
                return g.group(1), g.group(2) in MANUAL_WORDS
    return None, None


def write_remote(name, content):
    """把探针内容推到设备上。`content` 可以是 str（按 UTF-8 写）或 bytes（原样写）——
    二进制用例（PNG、UTF-16）必须走 bytes，否则本地这一侧就会先把内容改坏。"""
    sh(f"{ADB} shell mkdir -p {REMOTE_DIR}")
    local = "/tmp/tn_probe_" + name
    if isinstance(content, bytes):
        with open(local, "wb") as f:
            f.write(content)
    else:
        with open(local, "w", encoding="utf-8") as f:
            f.write(content)
    remote = f"{REMOTE_DIR}/{name}"
    sh(f"{ADB} push {local} {remote}")
    return remote


def media_id(remote, name):
    """scan_file 之后按显示名取 MediaStore id。

    ⚠️ `push` 之后**必须** scan 一次：MediaStore 里没有行，`content://media/...` 就查不到，
    而且 `scan_file` 失败时不会报错——只会得到「文件不存在」这个假结论。
    """
    sh(f"{ADB} shell content call --uri content://media/external/file "
       f"--method scan_file --arg {remote}")
    rows = sh(f"{ADB} shell content query --uri content://media/external/file "
              f"--projection _id:_display_name")
    for line in rows.splitlines():
        if f"_display_name={name}" in line:
            m = re.search(r"_id=(\d+)", line)
            if m:
                return int(m.group(1))
    return None


def open_doc(mid, expect=None):
    """打开文档并**等到界面确实是这一份文档**，返回那时的界面 XML（取不到则返回空串）。

    ⚠️ 判据不能只用状态栏里的 `UTF-8`：**任何**文档打开时它都在，于是上一份文档留下的旧
    dump 也能过这一关，而随后读出来的语法名是**上一份文件的**。实测踩到过：`.patch` 被读成
    `JSON`（因为前一个用例开的是 JSON）；更糟的是它也可能**假通过**（相邻两个用例期望同一门
    语言时）。所以这里等的是**文件名**（顶栏标题），它每个用例都不一样。

    调用方应当用返回的这份 XML 断言，不要再自己 dump 一次——那样又会引入同一个竞态。
    """
    for _ in range(2):
        sh(f"{ADB} shell am force-stop com.textnote.app")
        time.sleep(0.6)
        sh(f"{ADB} shell am start -a android.intent.action.VIEW "
           f"-d content://media/external/file/{mid} -t text/plain "
           f"--grant-read-uri-permission -n {PKG}/.MainActivity")
        for _ in range(12):
            time.sleep(0.5)
            xml = ui()
            if not xml:
                continue
            if (f'text="{expect}"' in xml) if expect else ("UTF-8" in xml):
                time.sleep(0.4)  # 让分析/着色落定，别读到半个界面
                return ui() or xml
    return ""


def check(name, got, want):
    global pass_count
    if got == want:
        pass_count += 1
        print(f"  OK   {name}: {got}")
    else:
        fails.append(name)
        print(f"  FAIL {name}: 实际={got!r} 期望={want!r}")


def cleanup(name, mid):
    sh(f"{ADB} shell rm -f {REMOTE_DIR}/{name}")
    if mid is not None:
        sh(f"{ADB} shell content delete --uri content://media/external/file/{mid}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="保留设备上的探针文件")
    args = ap.parse_args()

    print("=== 文件名 → 语法（设备端识别） ===")
    print("  注：探针会经由正常「打开文件」流程，所以之后会留在应用首页的最近列表里")
    print("      （清理掉设备文件后它们显示为「授权已失效」，长按可移除）")
    leftovers = []
    for name, content, want in CASES:
        remote = write_remote(name, content)
        mid = media_id(remote, name)
        if mid is None:
            check(name, "拿不到 media id", want)
            continue
        # 等界面确实是**这一份**文档再读（见 open_doc 的说明：只等 UTF-8 会读到上一份）
        xml = open_doc(mid, expect=name)
        if not xml:
            check(name, "编辑器没起来", want)
        else:
            got, manual = syntax_state(xml)
            check(name, f"{got}{'（手动）' if manual else ''}", want)
            # 手动的说明这份文件上有旧的覆盖记录，那是环境问题不是识别问题
            if manual:
                print("       ↑ 这是手动指定，不是自动识别结果，检查 SyntaxOverrides")
        if args.keep:
            leftovers.append((name, mid))
        else:
            cleanup(name, mid)

    if not args.keep:
        sh(f"{ADB} shell rm -rf {REMOTE_DIR}")
    if leftovers:
        print("保留的文件：", ", ".join(n for n, _ in leftovers))

    print()
    print(f"通过 {pass_count} / 失败 {len(fails)}")
    if fails:
        print("失败：" + ", ".join(fails))
        sys.exit(1)


if __name__ == "__main__":
    main()
