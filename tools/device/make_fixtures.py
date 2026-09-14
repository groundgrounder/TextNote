#!/usr/bin/env python3
"""造 M4/M5 用的测试文件、推到设备、触发媒体索引，并把 media id 写成一张表。

为什么要单独一个脚本：验证脚本原来把 media id 硬编码在自己里面，而 id 是每台设备
（每次重建模拟器）都不一样的——那些脚本换台机器就废了。这里把「造数据 → 拿 id」抽出来，
跑完写 `/tmp/tn-fixtures.json`，其他脚本按名字查 id 即可。

用法：
    python3 tools/device/make_fixtures.py            # 造数据并打印 id 表
    python3 tools/device/make_fixtures.py --clean    # 从设备上删掉这些文件
"""
import argparse
import io
import json
import os
import re
import subprocess
import sys
import time

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
DEVICE_DIR = "/sdcard/Documents"
TABLE = "/tmp/tn-fixtures.json"

# name -> (文件名, 生成方式)
# 内容统一用「Kotlin 风格行」，这样既是真源码（能着色、能走 span 路径），
# 又能让 .txt 变体作为「同内容不着色」的对照组。
CODE_LINE = '    val value%d = "string %d" // note %d\n'


def code_of(approx_bytes):
    """生成约 approx_bytes 字节的 Kotlin 风格代码"""
    buf = io.StringIO()
    i = 0
    while buf.tell() < approx_bytes:
        buf.write(CODE_LINE % (i, i, i))
        i += 1
    return buf.getvalue()


def build(name):
    """返回 (文件名, 内容)。尺寸是按「验证什么问题」挑的，不是凑数。"""
    if name == "small":
        # 可编辑区间里的最小样本：验外部改动、编码往返这类事都用它
        return "small.txt", "first line\nsecond line\nthird line\n"
    if name == "k60":
        return "kb60.kt", code_of(60_000)
    if name == "k188":
        return "kb188.kt", code_of(188_000)
    if name == "k900":
        return "kb900.kt", code_of(900_000)
    if name == "mb2":
        return "mb2.kt", code_of(2 * 1024 * 1024)
    if name == "mb4":
        return "mb4.kt", code_of(4 * 1024 * 1024)
    if name == "mb8":
        return "mb8.kt", code_of(8 * 1024 * 1024)
    if name == "line2mb":
        # 整份文件只有一行 —— 压缩过的 JSON / minified JS 就是这种形状。
        # 逐行懒加载在这里会遇到一个巨型段落，是只读渲染器唯一的洞。
        return "line2m.txt", "x" * (2 * 1024 * 1024)
    if name == "mix1mb":
        # 正常行里夹一条 1MB 的长行：滚到它才暴露问题，不滚则一切正常。
        lines = []
        for i in range(20_000):
            lines.append("line %d normal" % i)
            if i == 10_000:
                lines.append("y" * (1024 * 1024))
        return "mix1m.txt", "\n".join(lines) + "\n"
    raise SystemExit("不认识的 fixture: %s" % name)


ALL = ["small", "k60", "k188", "k900", "mb2", "mb4", "mb8", "line2mb", "mix1mb"]


def sh(cmd, timeout=300):
    return subprocess.run(
        [ADB, "shell", cmd], capture_output=True, text=True, timeout=timeout
    ).stdout


def media_ids():
    """返回 {文件名: media id}"""
    out = sh("content query --uri content://media/external/file --projection _id:_display_name")
    table = {}
    for line in out.splitlines():
        m_id = re.search(r"_id=(\d+)", line)
        m_name = re.search(r"_display_name=([^,]+)", line)
        if m_id and m_name:
            table[m_name.group(1).strip()] = int(m_id.group(1))
    return table


def clean():
    names = [build(n)[0] for n in ALL]
    for filename in names:
        sh("rm -f %s/%s" % (DEVICE_DIR, filename))
        sh("content delete --uri content://media/external/file --where \"\\\"_display_name='%s'\\\"\"" % filename)
    print("已从设备删除 %d 个 fixture" % len(names))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("names", nargs="*", help="只造这些 fixture（默认全部）")
    ap.add_argument("--clean", action="store_true", help="删除设备上的 fixture 后退出")
    args = ap.parse_args()

    if args.clean:
        clean()
        return

    unknown = [n for n in args.names if n not in ALL]
    if unknown:
        sys.exit("不认识的 fixture: %s（可选：%s）" % (", ".join(unknown), ", ".join(ALL)))
    targets = args.names or ALL

    if not os.path.exists(ADB):
        sys.exit("找不到 adb: %s" % ADB)

    table = {}
    for name in targets:
        filename, content = build(name)
        local = "/tmp/tn-fixture-%s" % filename
        with open(local, "w", encoding="utf-8") as f:
            f.write(content)
        subprocess.run([ADB, "push", local, "%s/%s" % (DEVICE_DIR, filename)],
                       capture_output=True, timeout=300)
        sh("content call --uri content://media/external/file --method scan_file "
           "--arg %s/%s" % (DEVICE_DIR, filename))
        print("  %-8s -> %-12s %8d 字节" % (name, filename, os.path.getsize(local)))

    # 媒体索引不是同步的，轮询到全部齐了再写表
    for _ in range(20):
        ids = media_ids()
        table = {n: ids.get(build(n)[0]) for n in targets}
        if all(v is not None for v in table.values()):
            break
        time.sleep(0.5)

    # 合并进已有的表：只造一部分时不该把其他 fixture 的记录抹掉
    merged = {}
    try:
        with open(TABLE, encoding="utf-8") as f:
            merged = json.load(f)
    except Exception:
        pass
    merged.update({k: v for k, v in table.items() if v is not None})
    with open(TABLE, "w", encoding="utf-8") as f:
        json.dump(merged, f, indent=2)

    missing = [n for n, v in table.items() if v is None]
    print("\nmedia id 表（已写入 %s）：" % TABLE)
    for name in ALL:
        if name in merged:
            print("  %-8s %s" % (name, merged[name]))
    if missing:
        print("\n以下 fixture 没拿到 id，验证脚本会退回内置默认值：%s" % ", ".join(missing))
        sys.exit(1)


if __name__ == "__main__":
    main()
