#!/usr/bin/env python3
"""验证「分享文件给 TextNote」（`ACTION_SEND` + `EXTRA_STREAM`）。

两组判据，缺一不可：

1. **分享面板里有没有 TextNote**（`cmd package query-activities` 直接问系统，不用点界面）。
   只测正例不够——「不该出现的也在」等于把照片、视频、APK 的分享列表全占了，
   而用户点进去只会看到乱码。所以正例与反例成对出现。
2. **真的投一个 SEND intent 进来，文件能不能被打开**（编辑器顶栏出现文件名）。

## 已知局限（如实记下）

`am start` **不能设置 `ClipData`**，而平台给目标应用发放的读权限只覆盖 intent 的
`data` 与 `clipData`、**不覆盖 `EXTRA_STREAM`**。所以：

- 用例 A 同时给 `-d` 与 `--eu EXTRA_STREAM`（同一个 Uri）——权限虽然挂在 `data` 上，
  但**权限是按 Uri 发放的**，所以应用从 extra 取到同一个 Uri 时照样读得到。这样至少能证明
  「SEND 进来的文件确实进了编辑器」。
- 只用 `--eu EXTRA_STREAM` 不带 `-d` 时，应用会打开失败并显示「已没有该文件的访问权限」——
  那是**测试夹具的限制**（am 没带权限），不是应用的问题；真实发送方会走迁移把权限带上。
- `ClipData` 那条分支因此**没有被设备验证覆盖**，只能靠代码审查。

用法：
    python3 tools/device/verify_share_send.py
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import ADB, ensure_awake  # noqa: E402
from verify_syntax_detect import media_id, sh, ui, write_remote  # noqa: E402

PKG = "com.textnote.app"
NAME = "shared.ts"
CONTENT = "from typescript\nconst answer = 42;  // 分享进来的\n"

# (MIME, 是否期望 TextNote 出现在分享目标里)
SHARE_TARGETS = [
    ("text/plain", True),
    ("text/markdown", True),
    ("application/json", True),
    ("image/png", False),
    ("video/mp4", False),
    # ⚠️ 刻意不列：SEND 的 Uri 在 extra 里，扩展名规则（pathPattern）拿不到它，
    # 只收 text/* 与已知 application/*。代价写在这里，免得被当成 bug。
    ("application/octet-stream", False),
]

pass_count = 0
fails = []


def check(name, ok):
    global pass_count
    if ok:
        pass_count += 1
        print(f"  OK   {name}")
    else:
        fails.append(name)
        print(f"  FAIL {name}")


def share_targets(mime):
    """这个 MIME 的分享列表里有哪些应用（问系统，不用点界面）"""
    out = sh(f"{ADB} shell cmd package query-activities -a android.intent.action.SEND "
             f"-c android.intent.category.DEFAULT -t {mime}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="保留设备上的探针文件")
    args = ap.parse_args()

    ensure_awake()
    print("=== 分享面板里的目标 ===")
    for mime, expect in SHARE_TARGETS:
        listed = PKG in share_targets(mime)
        check(f"{mime:28} {'在' if expect else '不在'}分享列表里", listed == expect)

    print()
    print("=== 投递 SEND intent ===")
    remote = write_remote(NAME, CONTENT)
    mid = media_id(remote, NAME)
    if mid is None:
        check("拿到 media id", False)
    else:
        uri = f"content://media/external/file/{mid}"
        sh(f"{ADB} shell am force-stop {PKG}")
        time.sleep(0.8)
        # `-d` 与 `EXTRA_STREAM` 给同一个 Uri：见文件头的局限说明
        sh(f"{ADB} shell am start -a android.intent.action.SEND -t text/plain "
           f"-d {uri} --eu android.intent.extra.STREAM {uri} "
           f"--grant-read-uri-permission -n {PKG}/.MainActivity")
        opened = False
        for _ in range(10):
            time.sleep(0.5)
            if f'text="{NAME}"' in ui():
                opened = True
                break
        check(f"SEND 进来的 {NAME} 进了编辑器", opened)
        if not args.keep:
            sh(f"{ADB} shell rm -f {os.path.dirname(remote)}/{NAME}")
            sh(f"{ADB} shell rm -rf /sdcard/Documents/tn_probe")
            sh(f"{ADB} shell content delete --uri content://media/external/file/{mid}")

    print()
    print(f"通过 {pass_count} / 失败 {len(fails)}")
    if fails:
        print("失败：" + ", ".join(fails))
        sys.exit(1)


if __name__ == "__main__":
    main()
