#!/usr/bin/env python3
"""守两条容易静默回归的东西。

A. **查找面板开着时，返回键是分级的**：先收面板（文档不丢），再按才回首页。
   这里有个陷阱：面板一打开搜索框就自动聚焦、软键盘弹起，**第一次 BACK 会被输入法
   吃掉**（只收键盘）。所以「按第 2 次才关面板」是正常的，用固定次数猜会得出相反结论——
   本脚本改成逐次按键、把状态转移打出来。

B. **编码探测的两条路径必须给出同一种编码**：`decodeTruncated`（最近列表摘要，只读前
   512 字节）与 `decode`（打开正文）。原先前者不看 BOM，于是 UTF-16 文件在列表里是乱码、
   点开却完全正常——用户只会以为文件坏了。断言直接比对「摘要」与「正文」。

先跑 `python3 tools/device/make_fixtures.py small k188`（A 用 kb188.kt）。
B 的 UTF-16 文件由本脚本自己造、自己清，不依赖 make_fixtures。
"""
import html
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import fixture

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "com.textnote.app"

UTF16_NAME = "tn_utf16_fixture.txt"
UTF16_REMOTE = f"/sdcard/Documents/{UTF16_NAME}"
UTF16_LOCAL = "/tmp/tn_utf16_fixture.txt"
UTF16_LINE = "utf16 first line"

pass_n = 0
fail_n = 0


def check(name, ok):
    global pass_n, fail_n
    if ok:
        pass_n += 1
        print("  ok   %s" % name)
    else:
        fail_n += 1
        print("  FAIL %s" % name)


def sh(c, t=120):
    return subprocess.run([ADB, "shell", c], capture_output=True, text=True, timeout=t).stdout


def ui():
    """带重试地取窗口树：dump 偶发失败，一次失败就下断言会得到假阴性。"""
    for _ in range(4):
        sh("rm -f /sdcard/ui.xml")
        r = subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                           capture_output=True, text=True, timeout=90)
        if "dumped" in r.stdout:
            out = subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
                                 capture_output=True, text=True, timeout=90).stdout
            if out.strip():
                return out
        time.sleep(0.6)
    return ""


def nodes(x):
    """只取被测应用的节点——dump 返回的是整棵窗口树，别人的窗口也在里面。"""
    out = []
    for m in re.finditer(r"<node[^>]*>", x or ""):
        d = dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))
        if d.get("package") == PKG:
            out.append(d)
    return out


def texts(x):
    return [html.unescape(d.get("text", "")) for d in nodes(x) if d.get("text", "").strip()]


def wait_text(frag, timeout=20):
    end = time.time() + timeout
    x = ""
    while time.time() < end:
        x = ui()
        if any(frag in t for t in texts(x)):
            return x
        time.sleep(0.5)
    return x


def where(x):
    """当前在哪一屏。用各屏独有的文本，别拿文件名——列表页和编辑器标题上都有它。"""
    t = texts(x)
    if any("打开文件" in s for s in t):
        return "首页"
    if any(s == "全部" for s in t):       # search_replace_all
        return "文档·面板开着"
    if any("字符" in s for s in t):       # 状态栏 stats_format
        return "文档·面板已关"
    return "未知/别的应用"


def keyboard_shown():
    return "mInputShown=true" in sh("dumpsys input_method | grep mInputShown")


def tap_desc(desc):
    for d in nodes(ui()):
        if html.unescape(d.get("content-desc", "")) == desc:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", d.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
                return True
    return False


def open_uri(uid, write=True):
    sh(f"am force-stop {PKG}")
    time.sleep(1.2)
    w = "--grant-write-uri-permission " if write else ""
    sh(f'am start -a android.intent.action.VIEW -d "content://media/external/file/{uid}" '
       f'-t text/plain --grant-read-uri-permission {w}-n {PKG}/.MainActivity')


# ---------------------------------------------------------------- A. 返回键分级

print("=== A. 查找面板开着时返回键分级 ===")
open_uri(fixture("k188", 21))
x = wait_text("字符", timeout=25)
check("文档已打开", where(x) == "文档·面板已关")
check("点「查找」按钮", tap_desc("查找"))
x = wait_text("全部", timeout=15)
check("查找面板已展开", where(x) == "文档·面板开着")
print("       键盘状态：%s（面板打开即弹，会吃掉第一次 BACK）"
      % ("开" if keyboard_shown() else "关"))

first = second = third = None
for i in (1, 2, 3):
    sh("input keyevent KEYCODE_BACK")
    time.sleep(2.5)
    s = where(ui())
    print("       第 %d 次 BACK → %s" % (i, s))
    if i == 1:
        first = s
    elif i == 2:
        second = s
    else:
        third = s

check("第 1 次返回只收键盘（面板仍在、仍在文档）", first == "文档·面板开着")
check("第 2 次返回收起面板，且仍在文档（不能连文档一起退掉）", second == "文档·面板已关")
check("第 3 次返回才回到首页", third == "首页")

# ------------------------------------------------- B. 两条解码路径给出同一种编码

print("=== B. UTF-16 文件的正文与最近列表摘要 ===")
open(UTF16_LOCAL, "wb").write(b"\xff\xfe" + (UTF16_LINE + "\nsecond line utf16\n").encode("utf-16-le"))
sh(f"rm -f '{UTF16_REMOTE}'")
subprocess.run([ADB, "push", UTF16_LOCAL, UTF16_REMOTE], capture_output=True, timeout=60)
sh(f"content call --uri content://media/external/file --method scan_file --arg {UTF16_REMOTE}")
dump = sh("content query --uri content://media/external/file --projection _id:_display_name")
m = re.search(r"_id=(\d+), _display_name=%s" % re.escape(UTF16_NAME), dump)
uid = m.group(1) if m else None
check("UTF-16 测试文件已进 MediaStore", uid is not None)

if uid:
    open_uri(uid, write=False)
    x = wait_text(UTF16_LINE, timeout=25)
    check("正文正确（BOM 已剥、按 UTF-16LE 解码）", any(UTF16_LINE in t for t in texts(x)))
    check("状态栏编码为 UTF-16LE",
          any("UTF-16LE" in t for t in texts(x)))
    sh("input keyevent KEYCODE_BACK")
    time.sleep(2.5)
    x = wait_text("打开文件", timeout=15)
    hits = [t for t in texts(x) if "utf16" in t.lower()]
    print("       首页与 utf16 有关的文本：%s" % hits)
    snippet = next((t for t in hits if UTF16_LINE in t), None)
    check("最近列表摘要与正文一致（不是乱码）", snippet is not None)
    check("摘要里没有替换字符", snippet is not None and "\ufffd" not in snippet)

# ---------------------------------------------------------------------- 收尾
sh("content delete --uri content://media/external/file "
   "--where \"_display_name='%s'\"" % UTF16_NAME)
sh(f"rm -f '{UTF16_REMOTE}'")
print("       （已清掉自己推进设备的 UTF-16 测试文件）")

print()
print("通过 %d / 失败 %d" % (pass_n, fail_n))
if fail_n:
    sys.exit(1)
