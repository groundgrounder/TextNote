#!/usr/bin/env python3
"""验证只读浏览模式的两条关键交互：敲键盘改不动内容、查找可用且隐藏替换行。

用 k900 fixture（超过可编辑上限，产品会走只读路径）。
先跑 make_fixtures.py 造数据。
"""
import os, re, subprocess, sys, time, html
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import fixture

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "com.textnote.app"

def sh(c, t=120):
    return subprocess.run([ADB,"shell",c],capture_output=True,text=True,timeout=t).stdout

def ui():
    sh("rm -f /sdcard/ui.xml")
    r=subprocess.run([ADB,"shell","uiautomator","dump","/sdcard/ui.xml"],capture_output=True,text=True,timeout=90)
    if "dumped" not in r.stdout: return ""
    return subprocess.run([ADB,"shell","cat","/sdcard/ui.xml"],capture_output=True,text=True,timeout=90).stdout

def flat(x): return " ".join(html.unescape(m) for m in re.findall(r'text="([^"]*)"', x or ""))

UID = fixture("k900", 127)

sh(f"am force-stop {PKG}"); time.sleep(1.5)
sh(f'am start -a android.intent.action.VIEW -d "content://media/external/file/{UID}" '
   f'-t text/plain --grant-read-uri-permission -n {PKG}/.MainActivity')
time.sleep(5)

print("=== 1. 只读模式下敲键盘应无任何变化 ===")
before = ui()
before_count = re.search(r'(\d+) 字符', flat(before)).group(1)
sh("input tap 500 900"); time.sleep(3)
sh("input text 'ZZZZZ'"); sleep=time.sleep(2)
after = ui()
after_count = re.search(r'(\d+) 字符', flat(after)).group(1)
print(f"   敲键盘前 {before_count} 字符 → 后 {after_count} 字符  "
      f"{'✓ 未被修改' if before_count == after_count else '✗ 被改了！'}")
print(f"   键盘是否弹出: {'是' if 'mInputShown=true' in sh('dumpsys input_method | grep mInputShown') else '否'}")

print("=== 2. 只读模式下查找 ===")
def tap_by_desc(desc):
    x = ui()
    for m in re.finditer(r'<node[^>]*>', x or ""):
        d=dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))
        if d.get('content-desc') == desc:
            b=d.get('bounds'); xs,ys=b.split('][')[0].strip('[').split(','); xe,ye=b.split('][')[1].strip(']').split(',')
            sh(f"input tap {(int(xs)+int(xe))//2} {(int(ys)+int(ye))//2}")
            return True
    return False

print("   点查找按钮:", tap_by_desc("查找"))
time.sleep(3)
# 关键词框自动聚焦，直接输入
sh("input text 'value12345'"); time.sleep(3)
x = ui()
m = re.search(r'(\d+)/(\d+)|(\d+) 处匹配|无匹配', flat(x))
print("   命中计数:", m.group(0) if m else "未显示")
print("   替换行是否隐藏:", "不在界面上" if '全部' not in flat(x) else "✗ 仍显示全部替换")
print("   点下一处:", tap_by_desc("下一处"))
time.sleep(3)
x = ui()
f = flat(x)
print("   跳转后可见内容含 value12345:", "✓" if 'value12345' in f else "✗")
print("   跳转后计数:", (re.search(r'\d+/\d+', f) or [None])[0] if re.search(r'\d+/\d+', f) else "?")
