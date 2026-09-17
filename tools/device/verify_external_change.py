#!/usr/bin/env python3
"""验证「回到前台时发现文件被别的应用改过」这条链路。

覆盖四条：
1. 无未保存改动时被外部改 —— 要**提示**，不能自动重载（自动重载会悄悄吃掉用户的编辑）
2. 「重新加载」真的取到磁盘版本；「保留我的」不丢内容
3. **保存自己的写入不能被误判成外部改动**（这是这个功能最容易退化的地方）
4. 文件被移走/删除要单独提示，且不与「被修改」混为一谈

先跑 make_fixtures.py small。
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
ACT = f"{PKG}/.MainActivity"
DEVICE_DIR = "/sdcard/Documents"
FILENAME = "small.txt"

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


def sh(cmd, timeout=120):
    return subprocess.run([ADB, "shell", cmd], capture_output=True, text=True,
                          timeout=timeout).stdout


def local_adb(*args):
    return subprocess.run([ADB] + list(args), capture_output=True, text=True, timeout=180).stdout


def ui():
    ensure_awake()   # 屏幕休眠时 dump 只会返回 null root node（exit code 仍是 0）
    for _ in range(3):
        sh("rm -f /sdcard/ui.xml")
        r = subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                           capture_output=True, text=True, timeout=90)
        if "dumped" in r.stdout:
            out = subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
                                 capture_output=True, text=True, timeout=90).stdout or ""
            if out.strip():
                return out
        time.sleep(0.6)
    return ""


def texts(xml=None):
    out = []
    for m in re.finditer(r'<node[^>]*>', xml or ui()):
        d = dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))
        if d.get('text'):
            out.append(html.unescape(d['text']))
    return out


def wait_text(fragment, timeout=12):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if any(fragment in x for x in texts()):
            return True
        time.sleep(0.5)
    return False


def tap_text(fragment):
    x = ui()
    for m in re.finditer(r'<node[^>]*>', x):
        d = dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))
        if fragment in html.unescape(d.get('text', '')) and 'bounds' in d:
            a, b = d['bounds'].split('][')
            xs, ys = a.strip('[').split(',')
            xe, ye = b.strip(']').split(',')
            sh("input tap %d %d" % ((int(xs) + int(xe)) // 2, (int(ys) + int(ye)) // 2))
            return True
    return False


def tap_desc(desc):
    x = ui()
    for m in re.finditer(r'<node[^>]*>', x):
        d = dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))
        if d.get('content-desc') == desc and 'bounds' in d:
            a, b = d['bounds'].split('][')
            xs, ys = a.strip('[').split(',')
            xe, ye = b.strip(']').split(',')
            sh("input tap %d %d" % ((int(xs) + int(xe)) // 2, (int(ys) + int(ye)) // 2))
            return True
    return False


def char_count():
    for t in texts():
        m = re.search(r'(\d+) 字符', t)
        if m:
            return int(m.group(1))
    return None


def write_from_outside(content):
    """模拟别的应用改了这个文件。

    **必须用 content write（走 ContentResolver），不能用 adb push**：push 会让 MediaStore
    把条目删掉重建，应用手里的 Uri 授权随之失效，于是「被修改」会被误判成「被删除」。
    同理也不能在改完后触发 scan_file。

    另外 content write **不截断**：新内容必须比当前内容长，否则旧内容的尾巴会留在后面，
    字数断言就会对不上。
    """
    local = "/tmp/tn-external-edit.txt"
    with open(local, "w", encoding="utf-8") as f:
        f.write(content)
    with open(local, "rb") as f:
        subprocess.run([ADB, "shell", "content", "write", "--uri", DOC_URI],
                       stdin=f, capture_output=True, timeout=120)
    time.sleep(2)


def to_background():
    sh("input keyevent KEYCODE_HOME")
    time.sleep(2)


def to_foreground():
    """不带 data 启动：只把界面带回前台，不会重新打开文档"""
    sh(f"am start -n {ACT}")
    time.sleep(2.5)


print("=== 0. 准备 ===")
# 先探本机的「注入键」能不能用：不能用的话第 4、5 步的输入断言必须标 skip 而不是记失败。
# 判据见 _input_probe（拿系统设置的搜索框当对照，不碰被测应用）。
INJECT_OK = input_injection_probe()
if INJECT_OK is None:
    print("  [!] 没找到对照输入框，输入类断言不下结论——若它失败，先怀疑字没进去")
elif INJECT_OK:
    print("  注入键可用（对照框收到了探针串）")
else:
    print("  [!] 本机 `input text` 对**任何应用**都无效（系统设置的对照框也进不去）")
    print("      第 4、5 步里的输入断言标 skip —— 这是设备侧的问题，不是产品问题")

sh(f"pm clear {PKG}")
HERE = os.path.dirname(os.path.abspath(__file__))
subprocess.run([sys.executable, os.path.join(HERE, "make_fixtures.py"), "small"],
               capture_output=True)
SMALL = fixture("small", 128)
DOC_URI = f"content://media/external/file/{SMALL}"
sh(f"am force-stop {PKG}")
time.sleep(1)
sh(f'am start -a android.intent.action.VIEW -d "{DOC_URI}" -t text/plain '
   f'--grant-read-uri-permission --grant-write-uri-permission -n {ACT}')
wait_text('small.txt')
base = char_count()
check("打开文件时读到基线字数", base == 34)

print("=== 1. 外部改动 + 无本地改动：只提示，不自动重载 ===")
write_from_outside("EXTERNAL EDIT\nsecond line\nthird line\n")
to_background()
to_foreground()
check("提示「在别的应用里被修改了」", wait_text('这个文件在别的应用里被修改了'))
check("没有自动丢弃内存里的版本（字数仍是打开时的）", char_count() == base)

print("=== 2. 「保留我的」 ===")
tap_text('保留我的')
time.sleep(1.5)
check("提示消失", not any('这个文件在别的应用里被修改了' in x for x in texts()))
check("内容没变（还是内存里的版本）", char_count() == base)
to_background()
to_foreground()
check("选过之后不再反复提示（基准已刷新）",
      not any('在别的应用里被修改了' in x for x in texts()))

print("=== 3. 「重新加载」取到磁盘版本 ===")
new_content = "RELOADED LINE\nsecond line\nthird line\n"
write_from_outside(new_content)
to_background()
to_foreground()
check("再次提示外部改动", wait_text('这个文件在别的应用里被修改了'))
tap_text('重新加载')
time.sleep(3)
check("重载后字数变成磁盘版本的", wait_text(str(len(new_content)) + ' 字符'))
check("提示已消失", not any('在别的应用里被修改了' in x for x in texts()))

print("=== 4. 回归：保存自己的写入不能误报外部改动 ===")
tap_text('second line')            # 点进编辑区
time.sleep(2)
if INJECT_OK:
    sh("input text 'ZZ'")
    time.sleep(1.5)
else:
    print("  note 输入没进去（" + SKIP_REASON + "）")
    print("       于是没有「未保存改动」这个前提，保存下去的正文与打开时相同")
    print("       （回归强度减弱，但「保存后不误报」本身仍然成立）")
check("保存按钮可点", tap_desc('保存'))
time.sleep(2)
to_background()
to_foreground()
check("保存后回到前台**没有**外部改动提示",
      not any('在别的应用里被修改了' in x for x in texts()))

print("=== 5. 有未保存改动时外部改动：保留我的 ===")
after_save = char_count()
sh("input text 'QQQ'")
time.sleep(1.5)
typed = char_count()
# 过了就记通过；只有「没进去 + 探针确认本机注入通道坏掉」才豁免——方向反了会放过真回归
if typed is not None and after_save is not None and typed > after_save:
    check("输入被记录（字数增加）", True)
elif INJECT_OK is False:
    print("  skip 输入被记录：本机注入键对任何应用都无效（见第 0 步），这条现在测不了")
    print(f"       {SKIP_REASON}")
    typed = after_save
else:
    check("输入被记录（字数增加）", False)
write_from_outside("THIRD EXTERNAL CHANGE\nsecond line\nthird line\n")
to_background()
to_foreground()
check("有未保存改动时同样给出提示", wait_text('这个文件在别的应用里被修改了'))
tap_text('保留我的')
time.sleep(1.5)
if not INJECT_OK:
    print("       （没有本地改动，这条退化成「外部写入没有顶替我的缓冲」）")
check("保留我的之后，我的内容还在", char_count() == typed)

print("=== 6. 文件被删除 ===")
sh(f"rm -f {DEVICE_DIR}/{FILENAME}")
sh("content delete --uri content://media/external/file --where \"\\\"_display_name='small.txt'\\\"\"")
to_background()
to_foreground()
check("提示「已被移动或删除」", wait_text('这个文件已被移动或删除'))

print()
print("通过 %d / 失败 %d" % (pass_n, fail_n))
if fail_n:
    sys.exit(1)
