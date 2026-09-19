#!/usr/bin/env python3
"""验证本地单文件分析（目前是 JSON 校验）在设备上的表现。

判据两处，缺一不可：

1. **状态栏有没有「问题」芯片**（`content-desc` 是 `Problems: N…`）——它证明分析跑通了、
   结论也落到了界面；
2. **点它会不会跳到那处并说出是什么问题**——只跳过去不说「哪不对」，用户到了地方也不知道
   该看什么。所以断言的是提示条里那句 `Line N: …`。

⚠️ **反例比正例重要**：一个会误报的校验器比没有校验器更糟（用户会先去怀疑自己的文件）。
所以好 JSON、宽松的 `.jsonc`、以及**同样内容但扩展名是 .txt** 的文件都必须一条不报——
最后那条还是「不分析」的口径说明：分析只对认得出的语法生效。

用法：
    python3 tools/device/verify_diagnostics.py
    python3 tools/device/verify_diagnostics.py --keep
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import ADB, ensure_awake  # noqa: E402
from verify_syntax_detect import media_id, open_doc, sh, ui, write_remote  # noqa: E402

PKG = "com.textnote.app"

# 第 3 行缺逗号（期望诊断落在第 3 行的引号上）
BROKEN = '{\n  "name": "demo"\n  "port": 8080\n}\n'
GOOD = '{ "name": "demo", "port": 8080 }\n'
# .jsonc 允许注释与尾随逗号：拿严格 JSON 的尺子量它会满屏误报
LENIENT = '{\n  // 环境配置\n  "name": "demo",\n  "port": 8080,\n}\n'

# (文件名, 内容, 该不该有诊断, 跳到的是第几行（没有诊断时给 None）)
CASES = [
    ("broken.json", BROKEN, True, 3),
    ("dup.json", '{"a": 1, "a": 2}\n', True, 1),
    ("good.json", GOOD, False, None),
    ("lenient.jsonc", LENIENT, False, None),
    ("looks_broken.txt", BROKEN, False, None),
]

# 芯片文案的两种语言（应用文案跟界面语言走）
COUNT_PATTERNS = (re.compile(r"^Problems · (\d+)$"), re.compile(r"^问题 · (\d+)$"))
DESC_PATTERNS = (re.compile(r"^Problems: (\d+)"), re.compile(r"^问题：(\d+)"))

pass_count = 0
fails = []


def nodes(xml):
    return [dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))
            for m in re.finditer(r"<node[^>]*>", xml)]


def texts(xml):
    return [n.get("text", "") for n in nodes(xml) if n.get("text")]


def descs(xml):
    return [n.get("content-desc", "") for n in nodes(xml) if n.get("content-desc")]


def diagnostics_badge(xml):
    """状态栏上的问题数；没有芯片返回 None"""
    for t in texts(xml):
        for p in COUNT_PATTERNS:
            m = p.match(t)
            if m:
                return int(m.group(1))
    return None


def center_of(node):
    nums = [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]
    if len(nums) != 4:
        return None
    return ((nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2)


def tap_text(label, timeout=8):
    """点一个文本等于 label 的节点。Compose 的文本节点就是可点的那个（芯片自己带 clickable）"""
    end = time.time() + timeout
    while time.time() < end:
        for n in nodes(ui()):
            if n.get("text") == label:
                xy = center_of(n)
                if xy:
                    sh(f"{ADB} shell input tap {xy[0]} {xy[1]}")
                    time.sleep(1.0)
                    return True
        time.sleep(0.4)
    return False


def wait_fragment(fragment, timeout=8):
    end = time.time() + timeout
    while time.time() < end:
        if any(fragment in t for t in texts(ui())):
            return True
        time.sleep(0.4)
    return False


def check(name, ok):
    global pass_count
    if ok:
        pass_count += 1
        print(f"  OK   {name}")
    else:
        fails.append(name)
        print(f"  FAIL {name}")


def cleanup(mid, remote):
    sh(f"{ADB} shell rm -f {remote}")
    if mid is not None:
        sh(f"{ADB} shell content delete --uri content://media/external/file/{mid}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="保留设备上的探针文件")
    args = ap.parse_args()

    ensure_awake()
    print("=== 本地分析（JSON 校验）设备端 ===")
    print("  注：探针会经由正常「打开文件」流程，之后留在首页最近列表里（✕ 可移除）")
    for name, content, want_badge, want_line in CASES:
        remote = write_remote(name, content)
        mid = media_id(remote, name)
        if mid is None:
            check(f"{name}: 拿不到 media id", False)
            continue
        # 等界面确实是**这一份**文档再读（见 open_doc 的说明：只等 UTF-8 会读到上一份）
        xml = open_doc(mid, expect=name)
        if not xml:
            check(f"{name}: 编辑器没起来", False)
            continue
        badge = diagnostics_badge(xml)
        if want_badge:
            check(f"{name}: 报了问题（芯片 = {badge}）", badge is not None)
        else:
            check(f"{name}: 一条都没报（芯片 = {badge}）", badge is None)

        # 点一下芯片：应当跳到出问题那一行，并在提示条里说出是什么问题。
        # 提示条文案跟界面语言走，所以只断言行号那一段（`Line 3` / `第 3 行`）。
        if want_badge and badge is not None:
            label = next((t for t in texts(xml)
                          if any(p.match(t) for p in COUNT_PATTERNS)), None)
            if label and tap_text(label):
                check(f"{name}: 点一下会跳到第 {want_line} 行并说明原因",
                      wait_fragment(f"Line {want_line}") or wait_fragment(f"第 {want_line} 行"))
            else:
                check(f"{name}: 点一下芯片", False)

        if not args.keep:
            cleanup(mid, remote)

    if not args.keep:
        sh(f"{ADB} shell rm -rf /sdcard/Documents/tn_probe")

    print()
    print(f"通过 {pass_count} / 失败 {len(fails)}")
    if fails:
        print("失败：" + ", ".join(fails))
        sys.exit(1)


if __name__ == "__main__":
    main()
