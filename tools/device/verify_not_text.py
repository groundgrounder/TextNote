#!/usr/bin/env python3
"""验证二进制防线：**该拒的拒、不该拒的绝不拒**。

判据是失败页上的标题文案（`Not a text file` / `这不是文本文件`）——它同时证明了
「路由到了 NotText 分支」和「文案真的渲染出来了」。

⚠️ 两个方向都要测。把一份正常文本判成二进制比漏判更糟：用户会彻底打不开它，
而且没有自救手段。所以这里三组用例：

1. **该拒**：PNG 头 + 数据（真二进制）；
2. **不许拒**：带 BOM 的 UTF-16LE——它在**字节层**每隔一个字节就是 `0x00`，
   判据一旦落在字节上就会把整份文档拒掉。这条是这个功能最容易犯的错；
3. **对照组**：普通 UTF-8 文本，确认没被前两组带偏。

用法：
    python3 tools/device/verify_not_text.py
    python3 tools/device/verify_not_text.py --keep
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import ADB, ensure_awake  # noqa: E402
from verify_syntax_detect import media_id, sh, ui, write_remote  # noqa: E402

PNG = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + bytes(64) + b"IHDR" + bytes(32)
UTF16_BOM = b"\xff\xfe" + "第一行 hello\n第二行 world\n".encode("utf-16-le")
PLAIN = "普通文本\n".encode("utf-8")

# (文件名, 内容, 是否期望被判为非文本)
CASES = [
    ("probe.png", PNG, True),
    ("probe.bin", bytes(range(1, 256)) * 4, True),
    ("utf16_bom.txt", UTF16_BOM, False),
    ("gbk.txt", "中文正文，含全角标点。\n".encode("gb18030"), False),
    ("plain.txt", PLAIN, False),
]

# 失败页标题的两种写法（应用文案跟界面语言走）
REJECT_TITLES = ("Not a text file", "这不是文本文件", "這不是文字檔")

pass_count = 0
fails = []


def texts():
    return re.findall(r'text="([^"]*)"', ui())


def open_and_dump(mid):
    """打开文档并取一次界面。

    **不能**照搬 `verify_syntax_detect.open_doc` 的判据（它在等状态栏的 `UTF-8`）：
    本脚本的被测行为恰恰是「**没有状态栏**」——被判为二进制的文件根本不显示状态栏，
    照那个判据永远等不到，会把「正确拒收」误读成「编辑器没起来」。

    所以这里改成：等界面出现**任意一个**可辨识的锚点（状态栏编码、或失败页标题）。
    另外每次先 `am force-stop`：应用是多实例的，一份文档一个窗口，不清理会越开越多，
    而 uiautomator 只 dump 焦点窗口。
    """
    for _ in range(2):
        sh(f"{ADB} shell am force-stop com.textnote.app")
        time.sleep(0.6)
        sh(f"{ADB} shell am start -a android.intent.action.VIEW "
           f"-d content://media/external/file/{mid} -t text/plain "
           f"--grant-read-uri-permission -n com.textnote.app/.MainActivity")
        for _ in range(10):
            time.sleep(0.4)
            shown = texts()
            if any(t.startswith(("UTF-", "GB")) for t in shown):
                return shown
            if any(t in REJECT_TITLES for t in shown):
                return shown
    return []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="保留设备上的探针文件")
    args = ap.parse_args()

    ensure_awake()
    print("=== 二进制防线（设备端） ===")
    print("  注：探针会经由正常「打开文件」流程，之后留在首页最近列表里（✕ 可移除）")
    global pass_count
    for name, data, want_rejected in CASES:
        remote = write_remote(name, data)
        mid = media_id(remote, name)
        if mid is None:
            fails.append(name)
            print(f"  FAIL {name}: 拿不到 media id")
            continue
        shown = open_and_dump(mid)
        if not shown:
            fails.append(name)
            print(f"  FAIL {name}: 取不到界面（uiautomator 偶发崩溃，重跑一次）")
            continue
        rejected = any(t in REJECT_TITLES for t in shown)
        label = "被判为非文本" if rejected else "当作文本打开"
        if rejected == want_rejected:
            pass_count += 1
            print(f"  OK   {name}: {label}")
        else:
            fails.append(name)
            print(f"  FAIL {name}: {label}（期望{'拒收' if want_rejected else '正常打开'}）")
            print("       界面文本：" + " | ".join(t for t in shown if t)[:160])
        if not args.keep:
            sh(f"{ADB} shell rm -f {os.path.dirname(remote)}/{name}")
            sh(f"{ADB} shell content delete --uri content://media/external/file/{mid}")

    if not args.keep:
        sh(f"{ADB} shell rm -rf /sdcard/Documents/tn_probe")

    print()
    print(f"通过 {pass_count} / 失败 {len(fails)}")
    if fails:
        print("失败：" + ", ".join(fails))
        sys.exit(1)


if __name__ == "__main__":
    main()
