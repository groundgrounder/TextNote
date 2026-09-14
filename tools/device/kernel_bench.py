#!/usr/bin/env python3
"""M4 文本内核对照测量工具。

## 它回答什么

TextNote 的编辑器每次改动都重排全文，于是「能编辑多大」有个硬上限。这个工具用于**量**这个上限，
以及验证候选方案是否真的解决问题。测的是同一个 debug APK 里的三种内核实现
（见 `app/src/debug/java/com/textnote/app/spike/SpikeActivity.kt`）：

| mode    | 实现                                        | 可编辑 |
|---------|---------------------------------------------|--------|
| value   | `BasicTextField(value, onValueChange)`      | 是     |
| state   | `BasicTextField(state = TextFieldState)`    | 是     |
| lines   | 按行 `LazyColumn` 懒加载                     | 否     |

## 用法

```bash
# 先跑 make_fixtures.py 造数据，再选要测的尺寸与模式
python3 tools/kernel_bench.py            # 全部尺寸 × 全部模式
python3 tools/kernel_bench.py 900K lines # 只测一组
```

先造数据（推文件 + 建 MediaStore 索引 + 写出 id 表）：

```bash
python3 tools/device/make_fixtures.py
```

## 三个方法上的坑（都踩过，别再踩）

1. **不要用「主线程被占住」当唯一判据。** `dumpsys gfxinfo` 输出里的
   `Failure while dumping the app` 确实说明主线程被占住，但这个信号只能发现**数秒级**的阻塞。
   用它测 60K/188K 会得到「一次输入 0.0s」，看起来像免费的——其实只是这个尺子量不出来。
   要测几百毫秒级的卡顿，必须读帧耗时百分位。

2. **每次 uiautomator dump 前先 `rm -f /sdcard/ui.xml`。** dump 失败时会留下上一次的文件，
   pull 回来的是旧界面，于是「打开耗时」会被测成一个偏小的假值。这个坑让我一度把
   900K 的首次渲染记成 9 秒（真实值 18 秒）。

3. 测输入代价前要先点一下编辑区并**等 6 秒**让输入法弹出动画结束，否则键盘动画会被算进帧统计。

## 实测结论（2026-09-13，emulator-5554，一次性记在这里）

逐字输入 10 次 / 连续滑动 10 次的帧耗时：

| 尺寸 | mode  | 打开   | p50   | p90    | p95    |
|------|-------|--------|-------|--------|--------|
| 188K | value | 3.5s   | 550ms | 1000ms | 1000ms |
| 188K | state | 3.7s   | 400ms | 550ms  | 550ms  |
| 188K | lines | 3.4s   | 31ms  | 42ms   | 44ms   |
| 900K | value | 34.0s  | 主线程被占住，不可用 |  |  |
| 900K | state | 24.4s  | 主线程被占住，不可用 |  |  |
| 900K | lines | 3.4s   | 32ms  | 46ms   | 53ms   |

**两条结论：**

- `TextFieldState`（BasicTextField2）**不做增量排版**。它的代价同样随文本长度线性增长
  （60K 89ms → 188K 450ms → 900K 卡死），只在尾部延迟上小胜（p90 550 对 1000ms）。
  换这个 API 救不了大文件。
- 按行懒加载的**只读**渲染器把代价与文件大小**完全解耦**：188K 与 900K 的 p50 都是 31/32ms，
  打开都是 3.4s（其中绝大部分是冷启动）。代价是不能编辑——这正是它绕开
  InputConnection / 组合输入 / 选择手柄那一整套难题的原因。
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import fixture

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "com.textnote.app"
ACT = f"{PKG}/.spike.SpikeActivity"
KEYCODE_A = 29

# 标签 → fixture 的 media id。id 每台设备都不同，由 make_fixtures.py 查出并写进表；
# 第二列是内置兜底值，只在没有表时用（会打印一次警告）。
CASES = {
    "60K": fixture("k60", 118),
    "188K": fixture("k188", 119),
    "900K": fixture("k900", 120),
}
ALL_MODES = ["value", "state", "lines"]


def sh(cmd, timeout=120):
    return subprocess.run([ADB, "shell", cmd], capture_output=True, text=True, timeout=timeout).stdout


def ui_text():
    """当前界面 XML。dump 失败（主线程忙 / 等不到空闲）返回 None。

    先删旧文件是关键：dump 失败会留下上一次的 ui.xml，读到的会是旧界面。
    """
    sh("rm -f /sdcard/ui.xml")
    r = subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                       capture_output=True, text=True, timeout=90)
    if "dumped" not in r.stdout:
        return None
    return subprocess.run([ADB, "shell", "cat", "/sdcard/ui.xml"],
                          capture_output=True, text=True, timeout=90).stdout or None


def open_spike(uid, mode, budget=120):
    """启动工装并等界面可用，返回耗时秒；超预算返回 None"""
    uri = f"content://media/external/file/{uid}"
    sh(f"am force-stop {PKG}")
    time.sleep(1.5)
    t0 = time.time()
    sh(f'am start -a android.intent.action.VIEW -d "{uri}" -t text/plain '
       f'--es mode {mode} --grant-read-uri-permission -n {ACT}')
    while time.time() - t0 < budget:
        xml = ui_text()
        if xml:
            m = re.search(r'SPIKE: mode=\w+ chars=(\d+)', xml)
            if m and int(m.group(1)) > 0:
                return time.time() - t0
        time.sleep(0.4)
    return None


def frame_stats():
    out = subprocess.run([ADB, "shell", "dumpsys", "gfxinfo", PKG],
                         capture_output=True, text=True, timeout=120).stdout
    if "Failure while dumping" in out:
        return None

    def g(pat):
        m = re.search(pat, out)
        return int(m.group(1)) if m else -1

    return {
        "frames": g(r'Total frames rendered: (\d+)'),
        "p50": g(r'50th percentile: (\d+)ms'),
        "p90": g(r'90th percentile: (\d+)ms'),
        "p95": g(r'95th percentile: (\d+)ms'),
    }


def measure(mode, rounds=10):
    """可编辑内核测「逐字输入」，只读渲染器测「连续滑动」——各自是真实使用动作"""
    if mode == "lines":
        time.sleep(2)
        sh(f"dumpsys gfxinfo {PKG} reset")
        time.sleep(0.5)
        for _ in range(rounds):
            sh("input swipe 700 1800 700 600 180")
            time.sleep(0.5)
    else:
        sh("input tap 500 1200")
        time.sleep(6)  # 等输入法动画结束，别把键盘动画算进帧统计
        sh(f"dumpsys gfxinfo {PKG} reset")
        time.sleep(0.5)
        for _ in range(rounds):
            sh(f"input keyevent {KEYCODE_A}")
            time.sleep(0.6)
    time.sleep(2)
    return frame_stats()


def main():
    labels = [sys.argv[1]] if len(sys.argv) > 1 and sys.argv[1] in CASES else list(CASES)
    modes = [sys.argv[2]] if len(sys.argv) > 2 and sys.argv[2] in ALL_MODES else ALL_MODES

    rows = []
    for label in labels:
        for mode in modes:
            open_s = open_spike(CASES[label], mode)
            if open_s is None:
                print(f"  {label:>5} {mode:<6} 打开超时", flush=True)
                sh(f"am force-stop {PKG}")
                time.sleep(1)
                rows.append((label, mode, None, None))
                continue
            st = measure(mode)
            rows.append((label, mode, open_s, st))
            if st:
                print(f"  {label:>5} {mode:<6} 打开={open_s:5.1f}s 帧数={st['frames']:>4} "
                      f"p50={st['p50']:>5}ms p90={st['p90']:>5}ms p95={st['p95']:>5}ms", flush=True)
            else:
                print(f"  {label:>5} {mode:<6} 打开={open_s:5.1f}s 帧统计不可用（主线程被占住）",
                      flush=True)
            sh(f"am force-stop {PKG}")
            time.sleep(1)

    print()
    print("=== 汇总（value/state = 逐字输入 10 次；lines = 连续滑动 10 次）===")
    print(f"{'尺寸':>6} | {'模式':<7} | {'打开':>7} | {'帧数':>5} | {'p50':>7} | {'p90':>7} | {'p95':>7}")
    for label, mode, open_s, st in rows:
        o = ("%.1fs" % open_s) if open_s else "超时"
        if st:
            print(f"{label:>6} | {mode:<7} | {o:>7} | {st['frames']:>5} | "
                  f"{st['p50']:>6}ms | {st['p90']:>6}ms | {st['p95']:>6}ms")
        else:
            print(f"{label:>6} | {mode:<7} | {o:>7} | {'n/a':>5} | "
                  f"{'n/a':>7} | {'n/a':>7} | {'n/a':>7}")


if __name__ == "__main__":
    main()
