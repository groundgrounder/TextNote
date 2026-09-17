#!/usr/bin/env python3
"""回归「查找框一次性打进 10 个字符」这个曾经必 ANR 的序列，连做 3 遍。

背景：只读模式下每次输入都触发一次全文 findAll，10 个字符排队就顶穿了 5 秒的输入分发
期限。修复是把「输入」与「搜索」拆开（停顿后才提交）。这个脚本守着它别退回去。

## 判据（2026-09-16 补）

以前这里只 `print`，**ANR 真回来了也只会打印一行 `ANR=1` 然后退 0**——等于没有门禁。
现在每遍断言「没有 ANR」「进程还活着」，有失败就退出码 1。

触发序列本身依赖把查询串打进查找框。**先看这一遍到底有没有打进去**（面板出现命中计数＝
真的进去了），没进去就把这一遍的 ANR 判定标 skip 并说明——否则「没触发 ⇒ 没 ANR」
会让断言恒真，变成一条永远绿的假门禁。

用 mb2 fixture。先跑 make_fixtures.py 造数据。
"""
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


def sh(c, t=180):
    return subprocess.run([ADB, "shell", c], capture_output=True, text=True, timeout=t).stdout


def ui():
    ensure_awake()   # 屏幕休眠时 dump 只会返回 null root node（exit code 仍是 0）
    sh("rm -f /sdcard/ui.xml")
    r = subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                       capture_output=True, text=True, timeout=90)
    if "dumped" not in r.stdout:
        return ""
    return subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
                          capture_output=True, text=True, timeout=90).stdout or ""


def tap_desc(desc):
    for m in re.finditer(r'<node[^>]*>', ui()):
        d = dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))
        if d.get('content-desc') == desc:
            b = d.get('bounds')
            a, c = b.split('][')
            xs, ys = a.strip('[').split(',')
            xe, ye = c.strip(']').split(',')
            sh(f"input tap {(int(xs)+int(xe))//2} {(int(ys)+int(ye))//2}")
            return True
    return False


def check(name, ok, detail=""):
    global pass_count
    if ok:
        pass_count += 1
    else:
        fails.append(name)
    print(f"  {'OK  ' if ok else 'FAIL'} {name}{('：' + detail) if detail else ''}")


UID = fixture("mb2", 121)

# 注入键能不能用（判据见 _input_probe）：只用来**豁免失败**，不是门禁
INJECT_OK = input_injection_probe()

for round_no in (1, 2, 3):
    print(f"--- 第 {round_no} 遍")
    sh(f"am force-stop {PKG}")
    time.sleep(1.5)
    sh(f'am start -a android.intent.action.VIEW -d "content://media/external/file/{UID}" '
       f'-t text/plain --grant-read-uri-permission -n {PKG}/.MainActivity')
    time.sleep(6)
    tap_desc("查找")
    time.sleep(3)
    sh("logcat -c -b events")
    sh("input text 'value12340'")   # ← 原 ANR 触发点
    time.sleep(4)
    anr = int(sh("logcat -d -b events 2>/dev/null | grep -c am_anr").strip() or 0)
    alive = PKG in sh("ps -A 2>/dev/null")
    f = " ".join(re.findall(r'text="([^"]*)"', ui()))
    m = re.search(r'\d+/\d+|\d+ 处匹配|无匹配', f)

    # 「该出现」型：任何一种计数文案出现＝查询串真的进了查找框（空查询什么都不会显示）。
    # 判定用 tie-breaker：过了记通过；没过且探针确认本机注入通道坏掉才标 skip。
    typed_ok = m is not None
    detail = f"面板显示={m.group(0) if m else '未显示'}"
    if typed_ok:
        check(f"第 {round_no} 遍：查询串进了查找框", True, detail)
    elif INJECT_OK is False:
        print(f"  skip 第 {round_no} 遍：查询串没进查找框（{detail}）")
        print(f"       {SKIP_REASON}")
    else:
        check(f"第 {round_no} 遍：查询串进了查找框", False, detail)

    if typed_ok:
        check(f"第 {round_no} 遍：没有 ANR", anr == 0, f"am_anr={anr}")
        check(f"第 {round_no} 遍：进程仍然存活", alive)
    else:
        # 「没触发 ⇒ 没 ANR」是恒真的假门禁，必须标 skip 而不是记通过
        print(f"  skip 第 {round_no} 遍的 ANR 判定：查询串没进查找框，触发序列没成立")

print()
if fails:
    print(f"通过 {pass_count} / 失败 {len(fails)}：{fails}")
    sys.exit(1)
print(f"全部通过（{pass_count} 项）")
