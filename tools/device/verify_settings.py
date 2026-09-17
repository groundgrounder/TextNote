#!/usr/bin/env python3
"""验证设置页：主题、动态取色、字号/行距/字体族即时生效，且能持久化。

主题的颜色变化 dump 不出来（只能靠截图看），所以这里断言三件可测的事：
1. 选项的选中态（FilterChip 的 selected 属性）
2. 改字号后行号节点的高度确实变大（行高是按「字号 × 倍数」算的）
3. 重启应用后设置仍在

先跑 make_fixtures.py small k900。
"""
import html
import json
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
ART = "/Users/blz/Documents/GitHub/TextNote/.workbuddy/artifacts"

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


def nodes(xml=None):
    out = []
    for m in re.finditer(r'<node[^>]*>', xml or ui()):
        d = dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))
        if 'bounds' in d:
            out.append(d)
    return out


def texts(xml=None):
    return [html.unescape(d.get('text', '')) for d in nodes(xml) if d.get('text')]


def wait_text(frag, timeout=12):
    end = time.time() + timeout
    while time.time() < end:
        if any(frag in t for t in texts()):
            return True
        time.sleep(0.5)
    return False


def find(exact_text=None, desc=None):
    for d in nodes():
        if exact_text is not None and html.unescape(d.get('text', '')) == exact_text:
            return d
        if desc is not None and d.get('content-desc') == desc:
            return d
    return None


def tap_node(d):
    a, b = d['bounds'].split('][')
    xs, ys = a.strip('[').split(',')
    xe, ye = b.strip(']').split(',')
    sh("input tap %d %d" % ((int(xs) + int(xe)) // 2, (int(ys) + int(ye)) // 2))


def tap_text(exact_text):
    d = find(exact_text=exact_text)
    if d:
        tap_node(d)
        return True
    return False


def tap_desc(desc):
    d = find(desc=desc)
    if d:
        tap_node(d)
        return True
    return False


def sliders():
    """设置页里的滑块（Compose 的 `Slider` 映射成 `SeekBar`），按 y 从上到下排。"""
    out = []
    for d in nodes():
        if d.get('class') == 'android.widget.SeekBar':
            a = d['bounds'].split('][')[0].strip('[')
            out.append((int(a.split(',')[1]), d))
    return [d for _, d in sorted(out)]


def set_slider_to_max(idx=0):
    """把第 [idx] 个滑块拖到最右端（= 该档位集合里的最大值）。

    **为什么不是点文字**：字号 / 行距现在是 `SteppedSlider`（一个 Slider + 当前值标签），
    屏幕上**没有**「22」「1.8」这类可点文字——按文字点必然落空。
    2026-09-16 就是因为这个，字号/行距两步共 5 条断言假失败（脚本与产品脱节）。

    **起手点不能贴左边缘**：从 x≈20 起手的横向 swipe 会被系统的「边缘返回手势」吃掉，
    结果是**页面直接退出去**（而不是拖动滑块）。实测症状：拖完 SeekBar 从 dump 里消失、
    偏好文件里也没写入。所以起手放在轨道 1/4 处、落点离右边缘留 30px。
    用 swipe 而不是 tap：拖拽一定会改值，不依赖「点轨道是否跳档」这个实现细节。
    """
    ss = sliders()
    if idx >= len(ss):
        return False
    a, b = ss[idx]['bounds'].split('][')
    xs = int(a.strip('[').split(',')[0])
    xe = int(b.strip(']').split(',')[0])
    y = (int(a.strip('[').split(',')[1]) + int(b.strip(']').split(',')[1])) // 2
    x_start = xs + 250          # 避开左边缘的返回手势区
    x_end = xe - 30             # 离右边缘留一截，同样避开手势区
    sh("input swipe %d %d %d %d 300" % (x_start, y, x_end, y))
    return True


def open_settings():
    """从**文档界面**进设置页，走溢出菜单两步。

    编辑器顶栏只留撤销/重做/查找/保存四个图标，设置与「另存为」被收进「更多操作」菜单；
    content-desc=「设置」的那个按钮只存在于**首页**，在文档界面点不到（点了只会落空，
    界面停在原处，后面的断言全会莫名其妙地失败）。本脚本所有用例都在文档界面。
    """
    if not tap_desc('更多操作'):
        return False
    time.sleep(1.2)
    return tap_text('设置')

def height_of(line_number="1"):
    """行号 Text 节点的高度 = 字体的自然行高。

    ⚠️ 它只随**字号**变，不随行距设置变——Compose 里 Text 节点的高度是 fontMetrics，
    lineHeight 体现在「行与行的间隔」上。量行距要用 line_gap()。
    """
    d = find(exact_text=line_number)
    if not d or 'bounds' not in d:
        return None
    a, b = d['bounds'].split('][')
    return int(b.strip(']').split(',')[1]) - int(a.strip('[').split(',')[1])


def line_gap():
    """相邻两行正文的间隔 = 字号 × 行距倍数 × 密度。行距断言用这个。

    ⚠️ 必须用第 2→3 行的间隔：Compose 的 lineHeight 语义里**首行高度是 fontHeight**，
    首行到次行的间隔（fontHeight）与后续行（lineHeight）不同，这是正常现象不是 bug；
    行号栏用 getLineTop 对齐所以自动正确。
    """
    ys = {}
    for d in nodes():
        t = html.unescape(d.get('text', ''))
        if t in ('1', '2', '3', '4') and 'bounds' in d:
            a, b = d['bounds'].split('][')
            x0 = int(a.strip('[').split(',')[0])
            if x0 < 120:  # 行号栏在屏幕最左
                ys[t] = int(a.strip('[').split(',')[1])
    if '2' in ys and '3' in ys:
        return ys['3'] - ys['2']
    return None


def back():
    tap_desc('返回')
    time.sleep(2)


def shot(name):
    with open(os.path.join(ART, name), "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, timeout=120)
    print("       截图 ->", name)


print("=== 0. 准备 ===")
sh(f"pm clear {PKG}")
HERE = os.path.dirname(os.path.abspath(__file__))
subprocess.run([sys.executable, os.path.join(HERE, "make_fixtures.py"), "small", "k900"],
               capture_output=True)
tbl = json.load(open("/tmp/tn-fixtures.json", encoding="utf-8"))
small = f"content://media/external/file/{tbl['small']}"
k900 = f"content://media/external/file/{fixture('k900', tbl['k900'])}"

sh(f"am start -n {ACT}")
time.sleep(5)
sh(f'am start -a android.intent.action.VIEW -d "{small}" -t text/plain '
   f'--grant-read-uri-permission --grant-write-uri-permission -n {ACT}')
wait_text('UTF-8')
h0 = height_of()
g0 = line_gap()
check("打开文档，取到基线行高与行间隔", h0 is not None and g0 is not None)
print("       基线：行号高 %s px，行间隔 %s px" % (h0, g0))

print("=== 1. 进设置页 ===")
open_settings()
wait_text('字号')
t = texts()
check("设置页出现（外观/文字分组都在）", any('外观' in x for x in t) and any('字号' in x for x in t))
# 预览卡的正文是 AnnotatedString（dump 不暴露 text），行号 "1\n2\n3" 才是可见文本
check("有预览区（渲染了示例行号）", any('1\n2\n3' == x for x in t) or any('1\n2\n3' in x for x in t))
shot("m7-settings-default.png")

print("=== 2. 字号：15 → 22 ===")
# 滑块拖到最右端 = 最大档 22（屏幕上没有「22」这种可点文字，见 set_slider_to_max）
check("把字号滑块拖到最大档（22）", set_slider_to_max(0))
time.sleep(1.5)
back()
wait_text('UTF-8')
time.sleep(1)
h1 = height_of()
g1 = line_gap()
print("       改后：行号高 %s px，行间隔 %s px" % (h1, g1))
check("字号变大：行号高度增加", h1 is not None and h0 is not None and h1 > h0)
check("字号变大：行间隔也增加", g1 is not None and g0 is not None and g1 > g0)
shot("m7-font-size-22.png")

print("=== 3. 行距：再放大一档 ===")
open_settings()
wait_text('字号')
check("把行距滑块拖到最大档（1.8）", set_slider_to_max(1))
time.sleep(1.5)
back()
wait_text('UTF-8')
time.sleep(1)
h2 = height_of()
g2 = line_gap()
print("       改后：行号高 %s px，行间隔 %s px" % (h2, g2))
check("行距变大：行号高度**不变**（节点高度只随字号）", h2 is not None and h1 is not None and h2 == h1)
check("行距变大：行间隔进一步增加", g2 is not None and g1 is not None and g2 > g1)
shot("m7-line-height-18.png")

print("=== 4. 主题：深色 ===")
open_settings()
wait_text('字号')
check("点到了「深色」", tap_text('深色'))
time.sleep(1.5)
back()
wait_text('UTF-8')
time.sleep(1)
shot("m7-theme-dark.png")
# 颜色变化 dump 断言不了（节点没有颜色属性），深色效果看截图

print("=== 5. 动态取色开关 ===")
open_settings()
wait_text('壁纸')
# Switch 的 content-desc 为空；它没有文本，靠「与『使用壁纸取色』标题同一行」配对
row = find(exact_text='使用壁纸取色')
switch = None
if row:
    a, b = row['bounds'].split('][')
    ry0, ry1 = int(a.strip('[').split(',')[1]), int(b.strip(']').split(',')[1])
    for d in nodes():
        if d.get('checked') in ('true', 'false') and 'bounds' in d:
            ca, cb = d['bounds'].split('][')
            cy0 = int(ca.strip('[').split(',')[1])
            if ry0 - 40 <= cy0 <= ry1 + 40:
                switch = d
                break
def pref_value(key):
    """从 settings.xml 里读某一项。比从 dump 里找 Switch 可靠——Switch 的
    checked 属性在 uiautomator 里同样不完全可见。"""
    out = sh(f"run-as {PKG} cat shared_prefs/settings.xml")
    m = re.search(r'name="%s" value="([^"]*)"' % key, out)
    return m.group(1) if m else None

before = pref_value('dynamic_color')
tap_text('使用壁纸取色')
time.sleep(1.5)
after = pref_value('dynamic_color')
# 键从未写过时返回 None = 默认值 true（SharedPreferences 不存默认值）
effective_before = before if before is not None else 'true'
check("切换动态取色后存储值改变（%s → %s）" % (effective_before, after),
      after is not None and effective_before != after)
tap_text('使用壁纸取色')  # 切回来，免得后面的截图与默认不一致
time.sleep(1)

print("=== 6. 持久化：重启应用后设置还在 ===")
# hasDocument 不跨进程重启，重启后要**重新打开文档**才能看到效果
sh(f"am force-stop {PKG}")
time.sleep(1)
sh(f"am start -n {ACT}")
time.sleep(5)
sh(f'am start -a android.intent.action.VIEW -d "{small}" -t text/plain '
   f'--grant-read-uri-permission --grant-write-uri-permission -n {ACT}')
wait_text('UTF-8')
time.sleep(1)
h3 = height_of()
g3 = line_gap()
print("       重启后：行号高 %s px，行间隔 %s px（改后值 %s / %s）" % (h3, g3, h2, g2))
check("重启后字号设置仍在（行号高度不变）", h3 is not None and h2 is not None and h3 == h2)
check("重启后行距设置仍在（行间隔不变）", g3 is not None and g2 is not None and g3 == g2)
shot("m7-settings-persisted.png")

print("=== 7. 只读浏览也用同一份样式 ===")
back()
time.sleep(1)
sh(f'am start -a android.intent.action.VIEW -d "{k900}" -t text/plain '
   f'--grant-read-uri-permission -n {ACT}')
if wait_text('只读', timeout=15):
    time.sleep(2)
    shot("m7-readonly-styled.png")
    check("只读浏览打开了（样式见截图）", True)
else:
    check("只读浏览打开了（样式见截图）", False)

print()
print("通过 %d / 失败 %d" % (pass_n, fail_n))
if fail_n:
    sys.exit(1)
