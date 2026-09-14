#!/usr/bin/env python3
"""验证编辑操作：撤销/重做，以及「连按替换」不会原地打转。

两个都是**改动的正确性**，不是性能，所以判据用状态栏的字符计数就够——
它随正文变化且是纯数字，比读界面文本稳。每个用例都写成「实际 = 期望」，
失败时退出码 1。

## 用例 1：撤销/重做
输入 `ABC` 一次提交（`input text` 是**一次**编辑，等价粘贴），然后：
- 刚打开、还没有历史时点撤销 → 字符数不变；
- 撤销一次 → 退回输入前（连续输入被合并成一步，这是设计而不是巧合）；
- 重做 → 回到输入后；
- 退到底之后再点撤销 → 仍不变（按钮已禁用，用**行为**验证，不能读 `enabled` 属性）。

## 用例 2：连续替换的重定位
正文 `cat cat`，查找 `cat` 替换成 `catalog`，连点两次「替换」：
```
修复后：8 → 12 → 16   （两处依次替换，得到 "catalog catalog"）
修复前：8 → 16 → 24   （第二处永远轮不到，一直替换刚插入的内容）
```
计数就能把两者分开，不需要读正文。

## 用法
    python3 tools/device/make_fixtures.py      # 不是必需，但保证设备是干净状态
    python3 tools/device/verify_edit_ops.py
    python3 tools/device/verify_edit_ops.py --keep    # 保留造出来的文件，便于人工复看
"""
import argparse
import os
import re
import subprocess
import sys
import time

# 直接运行脚本时 Python 已经把脚本目录放进 sys.path[0]，被别处 import 时则没有
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import ADB  # noqa: E402

PKG = "com.textnote.app"

pass_count = 0
fails = []


def sh(cmd, timeout=25):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout).stdout


def ui():
    """带重试地取当前界面。

    `uiautomator dump` 会偶发失败，而且失败时会**留下上一次的 /sdcard/ui.xml**——
    pull 回来的是旧界面，据此断言会得到假阴性。所以每次先 rm，再重试。
    """
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


def center(d):
    nums = [int(x) for x in re.findall(r"\d+", d.get("bounds", ""))]
    if len(nums) != 4:
        return None
    return ((nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2)


def tap_xy(xy):
    if xy:
        sh(f"{ADB} shell input tap {xy[0]} {xy[1]}")
        time.sleep(1.1)
        return True
    return False


def tap_text(label, timeout=10):
    """按可见文本点。查找/替换输入框的 placeholder 在框为空时会作为 text 出现，也走这里。"""
    end = time.time() + timeout
    while time.time() < end:
        for d in nodes(ui()):
            if d.get("text") == label:
                return tap_xy(center(d))
        time.sleep(0.4)
    return False


def tap_desc(*names, timeout=10):
    """按 content-desc 点。图标按钮只有 content-desc，没有 text。"""
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


def chars():
    """状态栏里的字符计数，例如 '8 字符 · 2 行'。"""
    for t in texts(ui()):
        m = re.search(r"(\d+)\s*(?:字符|chars)", t)
        if m:
            return int(m.group(1))
    return None


def check(name, got, want):
    global pass_count
    ok = got == want
    if ok:
        pass_count += 1
    else:
        fails.append(name)
    print(f"  {'OK  ' if ok else 'FAIL'} {name}: 实际={got} 期望={want}")


def ensure_file(name, content, keep=False):
    """推一份测试文件上设备并拿到 MediaStore id。

    不放进 make_fixtures 的公共表里：这些是本脚本私有的小文件，自己造自己清，
    免得污染别的脚本依赖的那张表。media id 每次现查，不硬编码。
    """
    local = f"/tmp/tn_{name}"
    with open(local, "w", encoding="utf-8") as f:
        f.write(content)
    remote = f"/sdcard/Documents/{name}"
    sh(f"{ADB} push {local} {remote} >/dev/null 2>&1")
    sh(f"{ADB} shell content call --uri content://media/external/file "
       f"--method scan_file --arg {remote} >/dev/null 2>&1")
    rows = sh(f"{ADB} shell content query --uri content://media/external/file "
              f"--projection _id:_display_name 2>/dev/null")
    for line in rows.splitlines():
        if name in line:
            m = re.search(r"_id=(\d+)", line)
            if m:
                return int(m.group(1))
    raise SystemExit(f"没能拿到 {name} 的 media id，看看 scan_file 是否成功")


def cleanup(names):
    for name in names:
        sh(f"{ADB} shell content delete --uri content://media/external/file "
           f"--where \"\\\"_display_name='{name}'\\\"\" >/dev/null 2>&1")
        sh(f"{ADB} shell rm -f /sdcard/Documents/{name}")
    sh(f"{ADB} shell rm -f /sdcard/ui.xml")
    sh(f"{ADB} shell pm clear {PKG}")


def open_doc(media_id, mime="text/plain"):
    """用外部 Intent 打开，顺带授临时读权限。先 pm clear 保证每一轮都是干净状态——
    否则上一轮残留的查找面板会吃掉这一轮的点击。"""
    sh(f"{ADB} shell pm clear {PKG}")
    time.sleep(1.5)
    sh(f"{ADB} shell am start -a android.intent.action.VIEW "
       f"-d content://media/external/file/{media_id} -t {mime} "
       f"--grant-read-uri-permission -n {PKG}/.MainActivity")
    if not wait_text("UTF-8"):  # 状态栏的编码，只在编辑器里出现
        raise SystemExit("编辑器没起来，看 logcat")


def case_undo():
    print("\n=== 用例 1：撤销 / 重做 ===")
    content = "hello world\nsecond line\n"
    media_id = ensure_file("tn_undo_test.txt", content)
    open_doc(media_id)
    time.sleep(1.5)

    base = chars()
    print(f"初始：{base} 字符")
    check("初始字符数", base, len(content))

    print("\n[1] 还没有历史时点撤销（应无反应）")
    if not tap_desc("撤销", "Undo"):
        fails.append("找不到撤销按钮")
        return
    check("无历史时撤销不改变文本", chars(), base)

    print("\n[2] 聚焦编辑区并输入 ABC（一次提交）")
    sh(f"{ADB} shell input tap 500 900")
    time.sleep(2.0)
    sh(f'{ADB} shell "input text \'ABC\'"')
    time.sleep(2.0)
    check("输入后 +3", chars(), base + 3)

    print("\n[3] 撤销一次（连续输入应合并成一步）")
    tap_desc("撤销", "Undo")
    check("一次撤销退回输入前", chars(), base)

    print("\n[4] 重做")
    tap_desc("重做", "Redo")
    check("重做恢复输入", chars(), base + 3)

    print("\n[5] 撤销到底后再点（应无变化 = 按钮已禁用）")
    tap_desc("撤销", "Undo")
    check("已回到原点", chars(), base)
    tap_desc("撤销", "Undo")
    check("再点无变化", chars(), base)

    print("\n[6] 中间停顿超过合并阈值 → 应分成两步")
    sh(f"{ADB} shell input tap 500 900")
    time.sleep(1.5)
    sh(f'{ADB} shell "input text \'XYZ\'"')
    time.sleep(2.0)
    sh(f'{ADB} shell "input text \'PQ\'"')
    time.sleep(2.0)
    check("两段都进去了", chars(), base + 5)
    tap_desc("撤销", "Undo")
    check("第一次撤销只退掉 PQ", chars(), base + 3)
    tap_desc("撤销", "Undo")
    check("第二次撤销退掉 XYZ", chars(), base)


def case_replace():
    print("\n=== 用例 2：连按替换不会原地打转 ===")
    content = "cat cat\n"
    media_id = ensure_file("tn_replace_test.txt", content)
    open_doc(media_id)
    time.sleep(1.5)

    base = chars()
    print(f"初始：{base} 字符，正文 'cat cat'")
    check("初始字符数（含换行）", base, len(content))

    print("\n[1] 打开查找面板，输入 cat")
    if not tap_desc("查找", "Find"):
        fails.append("找不到查找按钮")
        return
    time.sleep(1.0)
    if not tap_text("查找", timeout=6) and not tap_text("Find", timeout=4):
        fails.append("找不到查找输入框")
        return
    sh(f'{ADB} shell "input text \'cat\'"')
    time.sleep(1.5)
    check("命中 2 处", wait_text("2 处") or wait_text("2 found"), True)

    print("\n[2] 跳到下一处，填入替换文本 catalog")
    tap_desc("下一处", "Next match")
    time.sleep(1.0)
    if not tap_text("替换为", timeout=6) and not tap_text("Replace", timeout=4):
        fails.append("找不到替换输入框")
        return
    sh(f'{ADB} shell "input text \'catalog\'"')
    time.sleep(1.2)

    print("\n[3] 连点两次「替换」")
    if not tap_text("替换") and not tap_text("Replace"):
        fails.append("找不到替换按钮")
        return
    time.sleep(1.5)
    check("第一次替换 +4 字符", chars(), base + 4)
    tap_text("替换") or tap_text("Replace")
    time.sleep(1.5)
    check("第二次替换再 +4（而不是原地打转）", chars(), base + 8)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="保留造出来的文件，便于人工复看")
    args = ap.parse_args()

    print(f"adb: {ADB}")
    if "device" not in sh(f"{ADB} devices"):
        raise SystemExit("没有连着的设备")

    names = ["tn_undo_test.txt", "tn_replace_test.txt"]
    try:
        case_undo()
        case_replace()
    finally:
        if args.keep:
            print(f"\n--keep：保留 {names}，应用数据未清")
        else:
            cleanup(names)
            print(f"\n已清理 {names} 并清空应用数据")

    print()
    if fails:
        print(f"通过 {pass_count} / 失败 {len(fails)}：{fails}")
        sys.exit(1)
    print(f"全部通过（{pass_count} 项）")


if __name__ == "__main__":
    main()
