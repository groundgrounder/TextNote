#!/usr/bin/env python3
"""只读渲染器的能力边界：正常多行 vs 超长单行。

注意它测的是 spike 工装（SpikeActivity 的 linesnowrap 模式），不是产品代码路径。
测产品路径用 verify_open_tiers.py。

先跑 make_fixtures.py 造数据。
"""
import os, re, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import fixture

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "com.textnote.app"
ACT = f"{PKG}/.spike.SpikeActivity"

def sh(c, t=180):
    return subprocess.run([ADB,"shell",c],capture_output=True,text=True,timeout=t).stdout

def ui():
    sh("rm -f /sdcard/ui.xml")
    r = subprocess.run([ADB,"shell","uiautomator","dump","/sdcard/ui.xml"],capture_output=True,text=True,timeout=120)
    if "dumped" not in r.stdout: return None
    return subprocess.run([ADB,"shell","cat","/sdcard/ui.xml"],capture_output=True,text=True,timeout=120).stdout or None

def run(uid, label):
    sh(f"am force-stop {PKG}"); time.sleep(1.5)
    t0=time.time()
    sh(f'am start -a android.intent.action.VIEW -d "content://media/external/file/{uid}" '
       f'-t text/plain --es mode linesnowrap --grant-read-uri-permission -n {ACT}')
    opened=None
    while time.time()-t0 < 150:
        x=ui()
        if x:
            m=re.search(r'SPIKE: mode=\w+ chars=(\d+)', x)
            if m and int(m.group(1))>0:
                opened=time.time()-t0; chars=int(m.group(1)); break
        time.sleep(0.4)
    if opened is None:
        print(f"  {label:>5}  打开超时（>150s）", flush=True); sh(f"am force-stop {PKG}"); return
    time.sleep(2)
    sh(f"dumpsys gfxinfo {PKG} reset"); time.sleep(0.5)
    for _ in range(10):
        sh("input swipe 700 1800 700 600 180"); time.sleep(0.5)
    time.sleep(2)
    out = subprocess.run([ADB,"shell","dumpsys","gfxinfo",PKG],capture_output=True,text=True,timeout=120).stdout
    if "Failure while dumping" in out:
        print(f"  {label:>5}  打开={opened:.1f}s chars={chars}  帧统计不可用（主线程被占住）", flush=True)
    else:
        g=lambda p:(int(re.search(p,out).group(1)) if re.search(p,out) else -1)
        print(f"  {label:>5}  打开={opened:.1f}s chars={chars} 帧数={g(r'Total frames rendered: (\d+)')} "
              f"p50={g(r'50th percentile: (\d+)ms')}ms p90={g(r'90th percentile: (\d+)ms')}ms", flush=True)
    sh(f"am force-stop {PKG}"); time.sleep(1)

# 第三列是内置默认 id（本机模拟器当前值）。换设备后 id 会变，先跑 make_fixtures.py。
for key, label, fallback in (("line2mb", "单行2MB", 124),
                             ("mix1mb", "混合1MB行", 125),
                             ("mb2", "2MB正常", 121),
                             ("mb8", "8MB正常", 123)):
    run(fixture(key, fallback), label)
