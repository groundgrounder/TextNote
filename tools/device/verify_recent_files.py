#!/usr/bin/env python3
"""验证首页的最近文件列表：列出、点开、移除、授权失效后重新授权。

先跑 make_fixtures.py 造数据（用到 small / k60 两个 fixture）。
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
ACT = f"{PKG}/.MainActivity"
DEVICE_DIR = "/sdcard/Documents"

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


def ui():
    """当前界面 XML。dump 前必须先删旧文件，否则失败时会读到上一屏。

    带重试：这台机器上 uiautomator dump 会偶发失败（尤其界面刚组合完时），
    一次失败就下结论会得到假阴性——本脚本最初因此误报过 3 处。

    屏幕休眠时 dump 只会返回 `null root node`（exit code 仍是 0），读出来全是空，
    所以先唤醒一次。
    """
    ensure_awake()
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


def wait_text(fragment, timeout=12):
    """轮询等到界面上出现某段文本。固定 sleep 在慢的一屏上不够，重试才可靠。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if any(fragment in x for x in texts(ui())):
            return True
        time.sleep(0.5)
    return False


def nodes(xml):
    out = []
    for m in re.finditer(r'<node[^>]*>', xml or ""):
        d = dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))
        if 'bounds' in d:
            out.append(d)
    return out


def texts(xml):
    return [html.unescape(d.get('text', '')) for d in nodes(xml) if d.get('text')]


def y_range(bounds):
    a, b = bounds.split('][')
    return int(a.strip('[').split(',')[1]), int(b.strip(']').split(',')[1])


def tap_bounds(bounds):
    a, b = bounds.split('][')
    xs, ys = a.strip('[').split(',')
    xe, ye = b.strip(']').split(',')
    sh("input tap %d %d" % ((int(xs) + int(xe)) // 2, (int(ys) + int(ye)) // 2))


def tap_desc(desc, xml=None):
    for d in nodes(xml or ui()):
        if d.get('content-desc') == desc:
            tap_bounds(d['bounds'])
            return True
    return False


def tap_text(fragment, xml=None):
    for d in nodes(xml or ui()):
        if fragment in html.unescape(d.get('text', '')):
            tap_bounds(d['bounds'])
            return True
    return False


def tap_remove_for(name_fragment, xml=None):
    """点某一行的「从列表移除」。靠行文本与按钮的垂直重叠定位那一行——
    只按 content-desc 找会点到第一行去。

    接受外部传入的 snapshot：再 dump 一次的话，那次失败就会静默变成「没找到」，
    看起来像是按钮不存在。
    """
    ns = nodes(xml or ui())
    row = next((d for d in ns if name_fragment in html.unescape(d.get('text', ''))), None)
    if row is None:
        return False
    ry0, _ = y_range(row['bounds'])
    # 按钮在行里是**垂直居中**的：行内容越高，按钮越往下，未必与「文件名」这个文本节点
    # 有重叠（实测：行文本 y=554-607，按钮 y=613-676）。所以按 y 排序后取第一个
    # 中心点不低于行顶部的按钮——它就是这一行的那个。
    buttons = sorted(
        (d for d in ns if d.get('content-desc') == '从列表移除'),
        key=lambda d: y_range(d['bounds'])[0],
    )
    for d in buttons:
        by0, by1 = y_range(d['bounds'])
        if (by0 + by1) // 2 >= ry0:
            tap_bounds(d['bounds'])
            return True
    return False


def open_doc(uid):
    sh(f"am force-stop {PKG}")
    time.sleep(1.2)
    sh(f'am start -a android.intent.action.VIEW -d "content://media/external/file/{uid}" '
       f'-t text/plain --grant-read-uri-permission --grant-write-uri-permission -n {ACT}')
    time.sleep(4)


def back_home():
    """回到首页，并**确认**真的到了。

    不能只发一个 BACK 就往下走：关掉大文档时界面切换有延迟，此时 dump 拿到的还是
    编辑器——而编辑器的标题里同样有文件名，后面那些断言会变成假阳性。
    """
    sh("input keyevent KEYCODE_BACK")
    if wait_text('打开文件', timeout=8):
        return
    # BACK 不灵时退而点顶部的返回按钮
    tap_desc('返回文件列表')
    time.sleep(2)
    if not any('打开文件' in x for x in texts()):
        print("       [诊断] 没能回到首页，当前文本：", [t[:40] for t in texts() if t][:8])


HERE = os.path.dirname(os.path.abspath(__file__))

print("=== 0. 干净起点 ===")
sh(f"pm clear {PKG}")
# 每次都重造：本脚本末尾会**故意**把 small.txt 删掉（见第 4 步），不重造下次就没得跑
subprocess.run([sys.executable, os.path.join(HERE, "make_fixtures.py"), "small", "k60"],
               capture_output=True)
SMALL = fixture("small", 128)
K60 = fixture("k60", 129)
sh(f"am start -n {ACT}")
time.sleep(5)
check("空状态没有最近列表（首次启动）", '最近打开' not in texts(ui()))

print("=== 1. 打开两个文件后回到首页 ===")
open_doc(SMALL)
check("编辑器顶部有返回入口",
      any(d.get('content-desc') == '返回文件列表' for d in nodes(ui())))
back_home()
t = texts(ui())
check("首页出现「最近打开」分组", '最近打开' in t)
check("small.txt 出现在列表里", any('small.txt' in x for x in t))

open_doc(K60)
back_home()
t = texts(ui())
check("kb60.kt 出现在列表里", any('kb60.kt' in x for x in t))
check("两个文件都在列表里",
      any('small.txt' in x for x in t) and any('kb60.kt' in x for x in t))

# 最近打开在最前：kb60.kt 的 y 应该小于 small.txt
rows = {}
for d in nodes(ui()):
    for name in ('small.txt', 'kb60.kt'):
        if name in html.unescape(d.get('text', '')):
            rows[name] = y_range(d['bounds'])[0]
check("最近打开的排在最前（kb60.kt 在上）",
      rows.get('kb60.kt', 9999) < rows.get('small.txt', 0))

print("=== 2. 点列表项直接打开 ===")
# 点**最近打开**的那一项（kb60.kt）：它刚在同一个进程里打开过，授权一定还在。
tap_text('kb60.kt')
time.sleep(2)
# 用状态栏的编码字段判断「确实进了编辑器」：首页上是不会出现它的。
# 只断言标题是靠不住的——没点动的话，首页列表里同样有这个文本。
entered = wait_text('UTF-8')
check("点列表项后进了编辑器", entered)
check("打开的是这份文件（按其字数）", wait_text('60026 字符'))
if not entered:
    print("       [诊断] 点击后界面文本：", [t[:40] for t in texts() if t][:10])
back_home()

print("=== 3. 从列表移除 ===")
wait_text('kb60.kt')
time.sleep(1)
snap = ui()
before = len([1 for d in nodes(snap) if 'kb60.kt' in html.unescape(d.get('text', ''))])
check("移除前 kb60.kt 在列表里", before > 0)
ok = tap_remove_for('kb60.kt', snap)
time.sleep(2)
t = texts(ui())
check("移除按钮点到了正确的行", ok)
check("kb60.kt 已从列表消失", not any('kb60.kt' in x for x in t))
check("small.txt 仍在列表里", any('small.txt' in x for x in t))

print("=== 4. 授权失效的条目 ===")
# 把文件从设备上删掉：uri 就打不开了，probe 失败 → 标记为「需要重新授权」。
# 这比伪造 SharedPreferences 更接近真实场景（文件被移走）。
sh(f"rm -f {DEVICE_DIR}/small.txt")
sh("content delete --uri content://media/external/file --where \"\\\"_display_name='small.txt'\\\"\"")
sh(f"am force-stop {PKG}")
time.sleep(1)
sh(f"am start -n {ACT}")
time.sleep(4)
t = texts(ui())
check("失效的条目仍然列出来（不是悄悄消失）", any('small.txt' in x for x in t))
check("并标注了授权已失效", any('授权已失效' in x for x in t))

print("=== 5. 点失效项应去重新选文件 ===")
tap_text('small.txt')
time.sleep(4)
# 注意字段名：dumpsys 有时只输出 topResumedActivity，只 grep mResumedActivity 会漏
resumed = sh("dumpsys activity activities 2>/dev/null | grep -m1 -E 'ResumedActivity|mCurrentFocus'")
check("拉起了系统的文件选择器（而不是报一句打不开）",
      'documentsui' in resumed.lower())
sh("input keyevent KEYCODE_BACK")
time.sleep(2)
check("取消后回到首页", any('最近打开' in x for x in texts(ui())))

print()
print("通过 %d / 失败 %d" % (pass_n, fail_n))
if fail_n:
    sys.exit(1)
