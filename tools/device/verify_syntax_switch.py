#!/usr/bin/env python3
"""验证语法手动切换（A3）。

判据是**状态栏语法名的 content-desc**，不是截图比色：
    `语法：JSON（手动指定）`  ← 用户手动选的
    `语法：Plain Text（自动识别）`  ← 按扩展名认出来的
一句话里同时带上了「当前是哪门语法」和「这个结果是自动的还是手动的」，
而 uiautomator 读不到「是否为 disabled」，读 enabled 属性在 Compose 上不可信（见项目记忆），
所以走 content-desc 而不是界面属性。

## 用例
1. 打开 `.log`（扩展名认不出 → 纯文本），确认是**自动**；
2. 手动选 JSON → 变成**手动**的 JSON；
3. 返回首页、重新打开同一份文件 → 仍是**手动**的 JSON（**持久化的关键用例**——
   认不出扩展名的文件每次打开都要重选一遍的话，这个功能等于没有）；
4. 选回「自动」→ 退回自动的纯文本；
5. 再重开一次 → 仍是自动（手动记录确实被清掉了，不只是界面变了）；
6. 切换语法**不该把文档标脏**——标题前面不能冒出「•」。

## 用法
    python3 tools/device/verify_syntax_switch.py
    python3 tools/device/verify_syntax_switch.py --keep
"""
import argparse
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import ADB, ensure_awake  # noqa: E402

PKG = "com.textnote.app"
NAME = "tn_syntax_test.log"

# 内容故意是 JSON：.log 会被认成纯文本，手动选 JSON 之后着色才有肉眼可见的变化
CONTENT = '{\n  "name": "demo",\n  "port": 8080,\n  "on": true\n}\n'

pass_count = 0
fails = []


def sh(cmd, timeout=25):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout).stdout


def ui():
    """带重试地取当前界面。

    `uiautomator dump` 偶发失败，失败时会**留下上一次的 /sdcard/ui.xml**，
    pull 回来的是旧界面，据此断言会得到假阴性。所以每次先 rm，再重试。

    另外屏幕休眠时 dump 一定返回 `null root node`（exit code 仍是 0）→ 先唤醒。
    """
    ensure_awake()
    for _ in range(3):
        sh(f"{ADB} shell rm -f /sdcard/ui.xml")
        if "dumped" in sh(f"{ADB} shell uiautomator dump /sdcard/ui.xml"):
            out = sh(f"{ADB} shell cat /sdcard/ui.xml")
            if out.strip():
                return out
        time.sleep(0.6)
    return ""


def nodes(xml):
    return [dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))
            for m in re.finditer(r"<node[^>]*>", xml)]


def texts(xml):
    return [d.get("text", "") for d in nodes(xml) if d.get("text")]


def descs(xml):
    return [d.get("content-desc", "") for d in nodes(xml) if d.get("content-desc")]


def center(d):
    nums = [int(x) for x in re.findall(r"\d+", d.get("bounds", ""))]
    if len(nums) != 4:
        return None
    return ((nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2)


def tap_xy(xy):
    if xy:
        sh(f"{ADB} shell input tap {xy[0]} {xy[1]}")
        time.sleep(1.0)
        return True
    return False


def tap_text(label, timeout=10):
    end = time.time() + timeout
    while time.time() < end:
        for d in nodes(ui()):
            if d.get("text") == label:
                return tap_xy(center(d))
        time.sleep(0.4)
    return False


def tap_desc(*names, timeout=10):
    end = time.time() + timeout
    while time.time() < end:
        for d in nodes(ui()):
            if d.get("content-desc") in names:
                return tap_xy(center(d))
        time.sleep(0.4)
    return False


def wait_text(frag, timeout=12):
    end = time.time() + timeout
    while time.time() < end:
        if any(frag in t for t in texts(ui())):
            return True
        time.sleep(0.4)
    return False


def check(name, got, want):
    global pass_count
    ok = got == want
    if ok:
        pass_count += 1
    else:
        fails.append(name)
    print(f"  {'OK  ' if ok else 'FAIL'} {name}: 实际={got!r} 期望={want!r}")


def syntax_state():
    """返回 (语法名, 是否手动)。读不到返回 (None, None)。"""
    for d in descs(ui()):
        m = re.match(r"语法：(.+)（(自动识别|手动指定)）", d)
        if m:
            return m.group(1), m.group(2) == "手动指定"
    return None, None


def ensure_file():
    local = f"/tmp/{NAME}"
    with open(local, "w", encoding="utf-8") as f:
        f.write(CONTENT)
    remote = f"/sdcard/Documents/{NAME}"
    sh(f"{ADB} push {local} {remote} >/dev/null 2>&1")
    sh(f'{ADB} shell content call --uri content://media/external/file '
       f"--method scan_file --arg {remote} >/dev/null 2>&1")
    rows = sh(f"{ADB} shell content query --uri content://media/external/file "
              f"--projection _id:_display_name 2>/dev/null")
    for line in rows.splitlines():
        if NAME in line:
            m = re.search(r"_id=(\d+)", line)
            if m:
                return int(m.group(1))
    raise SystemExit(f"没能拿到 {NAME} 的 media id，看看 scan_file 是否成功")


def start_doc(media_id, clear=False):
    """用外部 Intent 打开文件。

    `clear=False` 是持久化用例的关键：**不能** pm clear，那会把存手动选择的
    SharedPreferences 一起清掉，然后「重开后还是 JSON」就永远测不出来（而且会假通过，
    因为清掉之后恰好退回自动，看起来像「没保存」——两种失败长得很像，只能靠不清来区分）。
    """
    if clear:
        sh(f"{ADB} shell pm clear {PKG}")
        time.sleep(1.5)
    sh(f"{ADB} shell am start -a android.intent.action.VIEW "
       f"-d content://media/external/file/{media_id} -t text/plain "
       f"--grant-read-uri-permission -n {PKG}/.MainActivity")
    if not wait_text("UTF-8"):  # 状态栏的编码，只在编辑器里出现
        raise SystemExit("编辑器没起来，看 logcat")
    time.sleep(1.2)


def open_menu():
    """点开状态栏的语法选择菜单。语法名会变，先读出当前名字再点它。"""
    name, _ = syntax_state()
    if name is None:
        return False
    if not tap_text(name):
        return False
    return wait_text("自动", timeout=6)  # 菜单第一项


def pick(label):
    if not tap_text(label):
        return False
    time.sleep(1.0)
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="保留设备上的测试文件")
    args = ap.parse_args()

    media_id = ensure_file()
    print(f"fixture: {NAME} -> media id {media_id}")

    print("\n=== 用例 1：.log 打开后是自动识别的纯文本 ===")
    start_doc(media_id, clear=True)
    name, manual = syntax_state()
    check("语法名是纯文本", name, "Plain Text")
    check("是自动识别（不是手动）", manual, False)

    print("\n=== 用例 2：手动选 JSON ===")
    if not open_menu():
        fails.append("点不开语法菜单")
    else:
        # 菜单里得真有这些条目，否则「选了 JSON」可能只是碰巧点到了别的东西
        menu = texts(ui())
        for want in ("自动", "JSON", "Python", "Go", "Plain Text"):
            check(f"菜单里有 {want}", want in menu, True)
        if not pick("JSON"):
            fails.append("菜单里点不到 JSON")
    name, manual = syntax_state()
    check("语法名变成 JSON", name, "JSON")
    check("标记为手动指定", manual, True)
    check("标题没有出现未保存标记「•」", any(NAME in t and "•" not in t for t in texts(ui())), True)

    print("\n=== 用例 3：返回首页再打开同一份文件（持久化）===")
    if not tap_desc("返回文件列表", "返回檔案列表", "Back to files"):
        fails.append("找不到返回按钮")
    time.sleep(1.0)
    start_doc(media_id, clear=False)  # 关键：这里不能清数据
    name, manual = syntax_state()
    check("重开后仍是 JSON", name, "JSON")
    check("重开后仍是手动指定", manual, True)

    print("\n=== 用例 4：选回「自动」===")
    if not open_menu():
        fails.append("第二次点不开语法菜单")
    elif not pick("自动"):
        fails.append("菜单里点不到「自动」")
    name, manual = syntax_state()
    check("退回自动识别", manual, False)
    check("语法名退回纯文本", name, "Plain Text")

    print("\n=== 用例 5：再重开一次，确认手动记录真的被清掉了 ===")
    if not tap_desc("返回文件列表", "返回檔案列表", "Back to files"):
        fails.append("找不到返回按钮")
    time.sleep(1.0)
    start_doc(media_id, clear=False)
    name, manual = syntax_state()
    check("重开后仍是纯文本", name, "Plain Text")
    check("重开后仍是自动识别", manual, False)

    if not args.keep:
        print("\n清理…")
        sh(f'{ADB} shell content delete --uri content://media/external/file '
           f"--where \"\\\"_display_name='{NAME}'\\\"\" >/dev/null 2>&1")
        sh(f"{ADB} shell rm -f /sdcard/Documents/{NAME} /sdcard/ui.xml")
        sh(f"{ADB} shell pm clear {PKG}")

    print(f"\n通过 {pass_count} / 失败 {len(fails)}")
    if fails:
        for f in fails:
            print(f"  - {f}")
        sys.exit(1)


if __name__ == "__main__":
    main()
