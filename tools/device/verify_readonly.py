#!/usr/bin/env python3
"""验证只读浏览模式：大文件走只读、敲键盘改不动内容、查找可用且替换行被隐藏。

用 k900 fixture（超过可编辑上限，产品会走只读路径）。先跑 make_fixtures.py 造数据。

## 判据与退出码

**这里以前全是 `print`，永远不会失败**——「看输出的人觉得对」就是它唯一的验收方式。
现在改成 `check()` 计数，有失败就退出码 1。

打字类断言（敲键盘、输入关键词）在本机**注入通道坏掉**时标 `skip` 而不是记失败：
`adb shell input text` 注入的按键可能不带字符（`utf16CodePoint == 0`），那时 Compose 与普通
`EditText` 都不会插入——详见 `_input_probe` 的说明。注意方向性：**过了就记通过**，
只有「没过 + 探针确认通道坏掉」才豁免，免得把真正的回归也一起放过。
"""
import html
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import ensure_awake, fixture
from _input_probe import SKIP_REASON, input_injection_probe

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "com.textnote.app"

pass_count = 0
fails = []


def sh(c, t=120):
    return subprocess.run([ADB, "shell", c], capture_output=True, text=True, timeout=t).stdout


def ui():
    """取界面 XML。**先删旧文件**——dump 失败时留着上一次的会读到过期界面。"""
    ensure_awake()   # 屏幕休眠时 dump 只会返回 null root node，读出来全是空
    sh("rm -f /sdcard/ui.xml")
    for _ in range(3):
        r = subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                           capture_output=True, text=True, timeout=90)
        if "dumped" in r.stdout:
            out = subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
                                 capture_output=True, text=True, timeout=90).stdout or ""
            if out.strip():
                return out
        time.sleep(0.6)
    return ""


def flat(x):
    return " ".join(html.unescape(m) for m in re.findall(r'text="([^"]*)"', x or ""))


def nodes(x):
    for m in re.finditer(r'<node[^>]*>', x):
        yield dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))


def check(name, ok, detail=""):
    global pass_count
    if ok:
        pass_count += 1
    else:
        fails.append(name)
    print(f"  {'OK  ' if ok else 'FAIL'} {name}{('：' + detail) if detail else ''}")


def check_typed(name, ok, detail=""):
    """依赖注入键的断言。过了就记通过；没过且探针确认本机通道坏掉时标 skip。"""
    if ok:
        check(name, True, detail)
    elif INJECT_OK is False:
        print(f"  skip {name}{('：' + detail) if detail else ''}")
        print(f"       {SKIP_REASON}")
    else:
        check(name, False, detail)


def tap_by_desc(desc, retries=2):
    for _ in range(retries):
        for d in nodes(ui()):
            if d.get("content-desc") == desc and "bounds" in d:
                a, b = d["bounds"].split("][")
                xs, ys = a.strip("[").split(",")
                xe, ye = b.strip("]").split(",")
                sh(f"input tap {(int(xs)+int(xe))//2} {(int(ys)+int(ye))//2}")
                return True
        time.sleep(0.6)
    return False


def chars():
    m = re.search(r'(\d+) 字符', flat(ui()))
    return int(m.group(1)) if m else None


UID = fixture("k900", 127)

# 本机的注入键能不能用？判据见 _input_probe。**注意它不是门禁**：断言照跑，
# 只有「失败 + 探针确认通道坏掉」才豁免。
INJECT_OK = input_injection_probe()
print(f"注入键探针：{INJECT_OK}"
      + ("（本机注入的按键不带字符 → 打字类断言失败时标 skip）" if INJECT_OK is False else ""))

sh(f"am force-stop {PKG}")
time.sleep(1.5)
sh(f'am start -a android.intent.action.VIEW -d "content://media/external/file/{UID}" '
   f'-t text/plain --grant-read-uri-permission -n {PKG}/.MainActivity')
time.sleep(5)

print("\n=== 1. 大文件应以只读模式打开 ===")
x = ui()
f = flat(x)
check("状态栏/提示条出现「只读」", "只读" in f, "没有它说明没走只读路径")
count = chars()
check("读到的是个大文件（超过可编辑上限）", count is not None and count >= 200_000,
      f"实际={count}；拿不到就先把 make_fixtures.py 跑一遍")
check("替换行被隐藏（只读下没有替换这回事）", "全部" not in f,
      "「全部替换」按钮不该出现")

print("\n=== 2. 只读模式下敲键盘应无任何变化 ===")
before = chars()
sh("input tap 500 900")
time.sleep(3)
sh("input text 'ZZZZZ'")
time.sleep(2)
after = chars()
# 这条是**「不该变」型**断言：注入失效时它恒真（字根本没进去），"OK" 会假装验证过了，
# 所以注入确认坏掉时**必须标 skip**，而不是记通过。反过来如果是「该变」型断言（命中计数、
# 跳转位置那种），通过仍然有意义，就交给 check_typed 走「过了记通过、失败才豁免」的路子。
if INJECT_OK is False:
    print("  skip 敲键盘后字符数不变：本机注入的键进不去（见上），这个「没变化」不构成证据")
    print(f"       {SKIP_REASON}")
else:
    check_typed("敲键盘后字符数不变",
                before is not None and after is not None and before == after,
                f"敲前 {before} → 敲后 {after}")
print(f"   （键盘是否弹出："
      f"{'是' if 'mInputShown=true' in sh('dumpsys input_method | grep mInputShown') else '否'}）")

print("\n=== 3. 只读模式下查找可用 ===")
check("查找按钮点得到（面板能打开）", tap_by_desc("查找"))
time.sleep(3)
# 关键词框自动聚焦，直接输入（它没有语义节点，不能按占位文字定位）
sh("input text 'value12345'")
time.sleep(3)
f = flat(ui())
hit = re.search(r'(\d+)/(\d+)|(\d+) 处匹配|无匹配', f)
check_typed("输入关键词后出现命中计数", hit is not None and "无匹配" not in (hit.group(0) if hit else "无匹配"),
            f"面板显示 {hit.group(0) if hit else '未显示'}")

check("点得到「下一处」", tap_by_desc("下一处"))
time.sleep(3)
f = flat(ui())
# 命中总数由 fixture 决定（k900 里那串关键词出现上千次），所以只断言**形状**：停在第一处、
# 总数 > 0。不写成 1/1——那是我一开始想当然，机上跑出来是 1/1111。
jump = re.search(r'\b1/(\d+)\b', f)
check_typed("跳转后停在第一处（显示 1/N）",
            jump is not None and int(jump.group(1)) > 0,
            f"实际={jump.group(0) if jump else '未显示'}")

print()
if fails:
    print(f"通过 {pass_count} / 失败 {len(fails)}：{fails}")
    sys.exit(1)
print(f"全部通过（{pass_count} 项）")
