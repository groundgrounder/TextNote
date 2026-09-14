#!/usr/bin/env python3
"""验证产品路径下五档体积的行为：可编辑 / 可编辑+卡顿提示 / 只读 / 过大。

覆盖的边界：200K 字符（可编辑 ↔ 只读的分界）、4MB 字节（只读 ↔ 完全不开的分界）。
先跑 make_fixtures.py 造数据。
"""
import os, re, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import fixture

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "com.textnote.app"

def sh(c, t=120):
    return subprocess.run([ADB,"shell",c],capture_output=True,text=True,timeout=t).stdout

def open_main(uid):
    sh(f"am force-stop {PKG}"); time.sleep(1.5)
    t0=time.time()
    sh(f'am start -a android.intent.action.VIEW -d "content://media/external/file/{uid}" '
       f'-t text/plain --grant-read-uri-permission --grant-write-uri-permission -n {PKG}/.MainActivity')
    while time.time()-t0 < 60:
        sh("rm -f /sdcard/ui.xml")
        r=subprocess.run([ADB,"shell","uiautomator","dump","/sdcard/ui.xml"],capture_output=True,text=True,timeout=90)
        if "dumped" in r.stdout:
            x=subprocess.run([ADB,"shell","cat","/sdcard/ui.xml"],capture_output=True,text=True,timeout=90).stdout
            if "字符" in x or "过大" in x: return time.time()-t0, x
        time.sleep(0.4)
    return None, None

def texts(x):
    import html
    out=[]
    for m in re.finditer(r'text="([^"]*)"', x or ""):
        t=html.unescape(m.group(1))
        if t.strip(): out.append(t)
    return out

# 第三列是内置默认 id（本机模拟器当前值）。换设备后 id 会变，先跑 make_fixtures.py。
for key, label, fallback in (("k188", "188K 可编辑", 126),
                             ("k900", "900K 只读", 127),
                             ("mb2", "2MB 只读", 121),
                             ("mb4", "4MB 边界", 122),
                             ("mb8", "8MB 过大", 123)):
    el, x = open_main(fixture(key, fallback))
    print(f"--- {label} ---")
    if x is None:
        print("   打开超时"); sh(f"am force-stop {PKG}"); continue
    ts = texts(x)
    shown=[t for t in ts if len(t)<95 and ('字符' in t or '过大' in t or '只读' in t or '大于' in t or 'Kotlin' in t or '只读浏览' in t or t in ('只读',))]
    for t in shown[:6]: print("   ", t)
    print(f"    打开=%.1fs" % el)
    sh(f"am force-stop {PKG}"); time.sleep(1)
