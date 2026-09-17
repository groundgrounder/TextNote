"""共享助手：按名字取 fixture 在设备上的 MediaStore id，以及 `ensure_awake()`。

media id 是每台设备（每次重建模拟器）都不一样的，把 id 硬编码进验证脚本，换台机器
那些脚本就废了。先跑 `make_fixtures.py` 生成 `/tmp/tn-fixtures.json`，脚本按名字取即可。
拿不到表时退回调用方给的默认值，并提示一次。
"""
import json
import os
import subprocess
import time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
TABLE = "/tmp/tn-fixtures.json"


def ensure_awake():
    """dump 之前调一次：屏幕休眠时 `uiautomator dump` 会返回 `null root node`
    （**exit code 仍是 0**，不报错），于是所有按文本读的断言静默读成空——看起来像产品坏了。

    幂等：醒着时只多一次 `dumpsys power`。判据同 android-adb-app-verify 技能里的症状表。
    """
    out = subprocess.run([ADB, "shell", "dumpsys power"], capture_output=True,
                         text=True, timeout=40).stdout
    if "mWakefulness=Asleep" in out:
        subprocess.run([ADB, "shell", "input", "keyevent", "KEYCODE_WAKEUP"],
                       capture_output=True, timeout=40)
        time.sleep(0.8)

_cache = None
_warned = False


def fixture(key, default=None):
    """取 fixture 的 media id。表里没有就用 default；default 也是 None 则报错。"""
    global _cache, _warned
    if _cache is None:
        try:
            with open(TABLE, encoding="utf-8") as f:
                _cache = json.load(f)
        except Exception:
            _cache = {}
            if not _warned:
                print("  [warning] 没有 %s，使用脚本内置的 media id。" % TABLE)
                print("            换设备后 id 会变，建议先跑：python3 tools/device/make_fixtures.py")
                _warned = True
    value = _cache.get(key)
    if value is not None:
        return value
    if default is None:
        raise SystemExit("fixture %r 既不在 %s 里，也没有内置默认值" % (key, TABLE))
    return default
