#!/usr/bin/env python3
"""验证产品路径下五档体积的行为：可编辑+卡顿提示 / 只读 / 完全不开。

覆盖的边界：200K 字符（可编辑 ↔ 只读的分界）、4MB 字节（只读 ↔ 完全不开的分界）。
先跑 make_fixtures.py 造数据。

## 判据（2026-09-16 补）

以前这里只 `print`，**分档错了也只会打印一行、退 0**——等于没有门禁。现在每一档断言三件事：

1. 出现了该档**应有**的交代（卡顿提示 / 只读 / 文件过大）；
2. 出现了该档应带的体积信息（可编辑与只读都报「N 字符」；过大报「大于 N」，且 **N 是文件
   真实体积而不是那个 4MB 上限**——这是 `TooLarge` 的设计意图，用 8MB 那一档才能验出来）；
3. **没有**出现上一档的交代（188K 不该被降级成只读、只读不该说「无法打开」）。

文案按应用当前语言（中文）匹配，与其它 device 脚本一致。
"""
import html
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import ensure_awake, fixture

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "com.textnote.app"

pass_count = 0
fails = []


def sh(c, t=120):
    return subprocess.run([ADB, "shell", c], capture_output=True, text=True, timeout=t).stdout


def check(name, ok, detail=""):
    global pass_count
    if ok:
        pass_count += 1
    else:
        fails.append(name)
    print(f"  {'OK  ' if ok else 'FAIL'} {name}{('：' + detail) if detail else ''}")


def open_main(uid):
    sh(f"am force-stop {PKG}")
    time.sleep(1.5)
    ensure_awake()   # 计时开始**之前**唤醒：休眠时 dump 只会返回 null root node
    t0 = time.time()
    sh(f'am start -a android.intent.action.VIEW -d "content://media/external/file/{uid}" '
       f'-t text/plain --grant-read-uri-permission --grant-write-uri-permission -n {PKG}/.MainActivity')
    while time.time() - t0 < 60:
        sh("rm -f /sdcard/ui.xml")
        r = subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                           capture_output=True, text=True, timeout=90)
        if "dumped" in r.stdout:
            x = subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
                               capture_output=True, text=True, timeout=90).stdout
            if "字符" in x or "过大" in x:
                return time.time() - t0, x
        time.sleep(0.4)
    return None, None


def texts(x):
    out = []
    for m in re.finditer(r'text="([^"]*)"', x or ""):
        t = html.unescape(m.group(1))
        if t.strip():
            out.append(t)
    return out


# key, 标签, 内置默认 id, 档位, 其它可能要看的片段
for key, label, fallback, tier in (("k188", "188K 可编辑+卡顿提示", 126, "slow"),
                                   ("k900", "900K 只读", 127, "readonly"),
                                   ("mb2", "2MB 只读", 121, "readonly"),
                                   ("mb4", "4MB 边界（不开）", 122, "too_large"),
                                   ("mb8", "8MB 过大（不开）", 123, "too_large")):
    el, x = open_main(fixture(key, fallback))
    print(f"--- {label} ---")
    if x is None:
        check(f"{label}：能打开", False, "60s 内没等到界面")
        sh(f"am force-stop {PKG}")
        continue
    ts = texts(x)
    f = " ".join(ts)
    shown = [t for t in ts if len(t) < 95 and any(k in t for k in
              ('字符', '过大', '只读', '大于', 'Kotlin'))]
    for t in shown[:6]:
        print("   ", t)
    print(f"    打开=%.1fs" % el)

    has_count = bool(re.search(r'\d+\s*字符', f))
    has_readonly = '只读' in f
    has_too_large = '文件过大' in f
    has_size = bool(re.search(r'大于\s*[\d.]+\s*(MB|KB|B)', f))

    if tier != "too_large":
        check(f"{label}：出现「N 字符」体积信息", has_count)
    if tier == "slow":
        check(f"{label}：出现卡顿提示", '卡顿' in f)
        check(f"{label}：**没有**被降级成只读", not has_readonly)
    elif tier == "readonly":
        check(f"{label}：走只读路径（出现「只读」）", has_readonly)
        check(f"{label}：没有说「无法打开」", not has_too_large)
    else:
        check(f"{label}：说清了「文件过大」", has_too_large)
        check(f"{label}：报出体积（「大于 N」）", has_size)
        # 8MB 那档能验出「报的是文件真实体积、而不是 4MB 上限」
        if key == "mb8":
            check(f"{label}：报的是文件真实体积（8.0 MB）", '8.0 MB' in f)
        check(f"{label}：没有体积字符计数（没读进来）", not has_count)

    sh(f"am force-stop {PKG}")
    time.sleep(1)

print()
if fails:
    print(f"通过 {pass_count} / 失败 {len(fails)}：{fails}")
    sys.exit(1)
print(f"全部通过（{pass_count} 项）")
