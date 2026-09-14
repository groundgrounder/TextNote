#!/usr/bin/env python3
"""回归「查找框一次性打进 10 个字符」这个曾经必 ANR 的序列，连做 3 遍。

背景：只读模式下每次输入都触发一次全文 findAll，10 个字符排队就顶穿了 5 秒的输入分发
期限。修复是把「输入」与「搜索」拆开（停顿后才提交）。这个脚本守着它别退回去。

用 mb2 fixture。先跑 make_fixtures.py 造数据。
"""
import os, re, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import fixture

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "com.textnote.app"

def sh(c, t=180):
    return subprocess.run([ADB,"shell",c],capture_output=True,text=True,timeout=t).stdout

def ui():
    sh("rm -f /sdcard/ui.xml")
    r=subprocess.run([ADB,"shell","uiautomator","dump","/sdcard/ui.xml"],capture_output=True,text=True,timeout=90)
    if "dumped" not in r.stdout: return ""
    return subprocess.run([ADB,"shell","cat","/sdcard/ui.xml"],capture_output=True,text=True,timeout=90).stdout or ""

def tap_desc(desc):
    for m in re.finditer(r'<node[^>]*>', ui()):
        d=dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))
        if d.get('content-desc') == desc:
            b=d.get('bounds'); a,c=b.split(']['); xs,ys=a.strip('[').split(','); xe,ye=c.strip(']').split(',')
            sh(f"input tap {(int(xs)+int(xe))//2} {(int(ys)+int(ye))//2}")
            return True
    return False

UID = fixture("mb2", 121)

for round_no in (1,2,3):
    sh(f"am force-stop {PKG}"); time.sleep(1.5)
    sh(f'am start -a android.intent.action.VIEW -d "content://media/external/file/{UID}" '
       f'-t text/plain --grant-read-uri-permission -n {PKG}/.MainActivity')
    time.sleep(6)
    tap_desc("查找"); time.sleep(3)
    sh("logcat -c -b events")
    sh("input text 'value12340'")   # ← 原 ANR 触发点
    time.sleep(4)
    anr = sh("logcat -d -b events 2>/dev/null | grep -c am_anr").strip()
    alive = "com.textnote.app" in sh("ps -A 2>/dev/null")
    f = " ".join(re.findall(r'text="([^"]*)"', ui()))
    m = re.search(r'\d+/\d+|\d+ 处匹配|无匹配', f)
    print(f"  第 {round_no} 遍: ANR={anr} 进程存活={'是' if alive else '否'} 命中计数={m.group(0) if m else '?'}",
          flush=True)
