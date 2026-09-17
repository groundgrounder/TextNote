"""共享：探「注入键输入」在本机到底能不能用（`input text` 对任何应用是否有效）。

**为什么必须探**：`adb shell input text` / `input keyevent` 可能**整台设备上对所有应用都失效**。
2026-09-16 在这台 emulator-5554（API 36）上实测过：

- 按键**确实送到了**应用——在 `BasicTextField` 的 `onKeyEvent` 里能打到日志，但
  **`utf16CodePoint == 0`**（整个事件不带字符），于是 Compose 与普通 `EditText` 都无从插入；
- `tap` 完全正常（光标会动、草稿会重写），**IME 提交路径**也正常（查找框里注入的查询串进去了，
  命中数 1 处匹配）；
- 那台设备上 Gboard 是当前输入法、`show_ime_with_hard_keyboard=0`；
- 09-13 同一套脚本是能打字的 → 这是**环境漂移**（系统/输入法状态），不是产品回归。

抓这个结论的姿势值得复用：**别靠猜，也别靠「界面上没变化」下结论**——在 `onKeyEvent` 里临时打一行
`event.utf16CodePoint` 就能一眼分清「事件没到」和「到了但没字符」。事后记得把日志删掉。

那种状态下：
- 「敲完字界面该变」的断言会失败得像产品 bug；
- 「敲了字界面不该变」的断言反而**恒真**（本来就没敲进去），等于什么都没验。

所以先拿一个对照输入框探一下。对照用的是**系统里那个非 Compose 的 EditText 搜索框**
（`am start -a android.settings.SETTINGS` 在本镜像上会落到 GMS 的帮助页，无所谓——
只要它是 `EditText` 就能回答「注入的键到底带不带字符」这个问题），探完 force-stop 掉。

返回值三态，调用方必须区别对待：
- `True`  能注入 → 输入类断言照常跑；
- `False` **注入的键不带字符**（本机对所有应用都无效）→ 输入类断言标 skip，而不是记失败；
- `None`  连对照输入框都没找到（界面语言/版本不同）→ 不下结论。

**注意它不覆盖什么**：真键盘、软键盘、粘贴走的是 IME/InputConnection，不是这条注入通道。
所以 `False` 只说明「用 adb 注入键来验证打字」这件事在这台设备上不成立，不能推出产品不能打字。
"""

import html
import os
import re
import subprocess
import time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
SETTINGS_PKG = "com.android.settings"

# 调用方「失败但可以豁免」时统一打印这句，措辞只维护一处
SKIP_REASON = ("本机 `input text` 注入的按键不带字符（utf16CodePoint=0）→ Compose 与普通 "
               "EditText 都不插入，而 tap 正常；这是设备/工具链的限制，不是产品问题"
               "（判据见 _input_probe 的说明）")


def _sh(cmd, timeout=60):
    return subprocess.run([ADB, "shell", cmd], capture_output=True, text=True,
                          timeout=timeout).stdout


def _dump():
    """dump 一次界面 XML。失败时**先删旧文件**再重试——留着上一次的 dump 会读到过期界面。"""
    _sh("rm -f /sdcard/probe.xml")
    for _ in range(3):
        r = subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/probe.xml"],
                           capture_output=True, text=True, timeout=90)
        if "dumped" in r.stdout:
            out = subprocess.run([ADB, "shell", "cat", "/sdcard/probe.xml"],
                                 capture_output=True, text=True, timeout=90).stdout or ""
            if out.strip():
                return out
        time.sleep(0.6)
    return ""


def _nodes(xml):
    for m in re.finditer(r'<node[^>]*>', xml):
        yield dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))


def _texts(xml):
    out = []
    for m in re.finditer(r'text="([^"]*)"', xml):
        out.append(html.unescape(m.group(1)))
    return out


def _tap(node):
    a, b = node['bounds'].split('][')
    xs, ys = a.strip('[').split(',')
    xe, ye = b.strip(']').split(',')
    _sh("input tap %d %d" % ((int(xs) + int(xe)) // 2, (int(ys) + int(ye)) // 2))


def input_injection_probe():
    # 每次一个独特的探针串：设置页可能记得上次的查询，用固定串会得到假阳性
    probe = "tnprobe%d" % int(time.time())
    _sh("am start -a android.settings.SETTINGS")
    time.sleep(3)

    xml = _dump()
    box = None
    # 先找「搜索」入口（正常是它）；**找不到就直接找可输入的 EditText**——对照页第二次打开时
    # 可能已经停在搜索态，这时没有「搜索」这个提示节点，只有输入框本身。
    for n in _nodes(xml):
        t = html.unescape(n.get('text') or n.get('content-desc') or '')
        if ('搜索' in t or 'Search' in t) and 'bounds' in n:
            box = n
            break
    if box is None:
        for n in _nodes(xml):
            if 'EditText' in (n.get('class') or '') and 'bounds' in n:
                box = n
                break
    if box is None:
        _sh("am force-stop %s" % SETTINGS_PKG)
        return None

    _tap(box)          # 点提示文字或输入框本身，之后它自己会聚焦
    time.sleep(2)
    _sh("input text '%s'" % probe)
    time.sleep(2)
    landed = any(probe in t for t in _texts(_dump()))

    _sh("am force-stop %s" % SETTINGS_PKG)
    return landed
