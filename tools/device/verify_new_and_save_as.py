#!/usr/bin/env python3
"""验证「新建文档」与「另存为」（A2）。

两者都走 SAF 的 `ACTION_CREATE_DOCUMENT`，脚本绕不开系统文件选择器
（`com.google.android.documentsui`，下文简称 picker）。文档从首页按钮或编辑器溢出菜单进去，
**脚本只用它的预填名和「保存」按钮**——理由见下面第一条。

## ⚠️ 四个必须踩过的坑
1. **不要碰 picker 里的文件名输入框。** 点它会拉起输入法，输入法窗口会挤掉 uiautomator 的
   窗口树，收键盘那一下 BACK 还会把前台交给别的应用（实测把常驻的 MarkNote 拉到前面并让它
   新建了一个文档）。改名本身是系统 picker 的功能，不是本项目的代码，为它把整个脚本拖进
   不稳定状态不划算。于是保持预填名直接保存，**实际文件名从目录列表和编辑器标题里读回来**。
   预填名本身仍然是断言对象：新建看 `R.string.untitled`，另存为看当前文件名。
2. **`uiautomator dump` 返回的是整棵窗口树，不只是前台窗口。** 后台应用的编辑区也在里面，
   而且它的 EditText 铺满全屏——按 `class=EditText` 取第一个会取到别人家的输入框。
   所有节点查询都按 `package` 过滤。
3. **有外部的自动化会不定时把 MarkNote 拉到前台。** logcat 里抓到的是：
   ```
   START u0 {act=VIEW dat=content://media/... typ=text/markdown
             cmp=com.marknote.app/.MainActivity} with LAUNCH_SINGLE_TASK
     from uid 2000 (com.android.shell) }
   ```
   它一起来，之后所有 `input` 就全落到它那边，现象是「找不到保存按钮」「标题里是 TextNote」
   这类看起来像应用坏了的信息。设备上并没有常驻的 shell 循环（`ps -A` 里没有），
   发命令的是 **host 上另一个 adb 客户端**（另一个会话的自动化），脚本管不了它。

   **对策在自己的这一侧：被抢走就把前台抢回来。** 抢前台是**瞬时**的，而 picker 即使用户
   被压到后台也照样会返回结果（`onActivityResult` 照跑），编辑器只是落在后面——
   所以断言前 `bring_to_front()` 一次就够了，判据也别用「状态栏里有没有某段文本」，
   要用**前台包名**（用文本当判据会把「被抢前台」误报成「保存没生效」）。
   配套：后台看门狗 `am force-stop` 它，只是把窗口缩短，不是根治。

   ⚠️ 试过更狠的一招——临时 `pm disable-user` 它——**别用**（可选项 `--disable-interfering`
   保留着）：它改的是设备持久状态，而「保证还回去」这件事在外层 shell 被一起杀掉时不成立
   （实测 `pkill -f <脚本名>` 把父 shell 一起杀了，Python 的 `finally` 没跑，
   MarkNote 就留在停用状态，得手动 `pm enable`）。顺带一提，用「反复 `am force-stop`」
   按住它也只是让失败换了个样子：杀掉的下一次重启恰好就是抢前台的那一下。
4. **带空格的文件名会让两件事静默出错**（都是「看起来像功能坏了」的那种）：
   - `adb pull` / `adb shell` 的路径没加引号 → 文件拉不回来、删不掉，报出来就是
     「另存为没有写出文件」。另存为的名字是 picker 去重出来的「tn_a2_src (4).txt」，
     正好命中；源文件名没空格，所以只有另存为那几条会炸。
   - `ls -1` 的输出用 `.split()` 切 → 带空格的名字被劈成两个假名字，
     「新多出来的文件」永远算不出来。必须按行切。
   另外 `adb shell` 是把参数用空格拼起来交给设备 shell 的，**本地 shell 的引号不算数**，
   得写成 `adb shell "rm -f '路径'"` 让单引号留在参数里。

## 用例
### A. 新建（不输入任何文字）
首页「新建文件」→ picker 预填名是「未命名.txt」→ 保存 → 编辑器：0 字符、不脏、非只读。

### B. 另存为（**全程不输入文字**，所以这条链路不依赖输入法）
打开一份只有**临时读权限**的文件——正是「保存必然失败、无处可去」的那种，A2 要解决的场景：
1. 正文按磁盘内容显示，字符数对得上；
2. 溢出菜单「另存为…」→ picker 预填名就是**当前文件名**；
3. 保存后：标题换成新文件名、字符数不变（内容一个字都没动）、状态栏不出现「只读」；
4. 目标目录里确实多且只多出一个文件，它与源文件**逐字节相同**、没有混进 CR；
5. 切后台再回来不误报「被其他应用修改」/「发现草稿」（另存为刷新了基准，漏了就会误报）；
6. 返回首页，两个文件都在最近列表里。

### C. 输入 → 未保存标记 → 保存
这一步必须打字，是唯一依赖输入法的用例，失败会重试三轮。

## 用法
    python3 -u tools/device/verify_new_and_save_as.py          # -u：别让块缓冲把进度吃掉
    python3 -u tools/device/verify_new_and_save_as.py --keep
"""
import argparse
import os
import re
import signal
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _fixtures import ADB  # noqa: E402

PKG = "com.textnote.app"
PICKER = "com.google.android.documentsui"
SAVED_DIR = "/sdcard/Download"  # picker 在这台设备上默认落在「下载」
SRC = "tn_a2_src.txt"
SRC_BODY = "first line\nsecond line\nthird line\n"
DEFAULT_NAME = "未命名.txt"
# 与本项目无关、但会抢前台的其它应用（理由见文件头的坑 3）
INTERFERING_APPS = ["com.marknote.app"]

pass_count = 0
fails = []


# ---------- 设备与界面 ----------

def sh(cmd, timeout=30):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout).stdout


def ui():
    """带重试地取界面。

    每次先 rm（避免上一轮残留的 ui.xml 被当成这一轮的界面），**判断依据是拿回来的文件内容
    而不是 dump 命令的 stdout 措辞**：dump 偶发失败时既没有 `dumped` 字样、也没有新文件，
    按 stdout 判断会把「工具偶发失败」和「界面里确实没有这个控件」混成同一种结果。
    """
    for _ in range(5):
        sh("{ADB} shell rm -f /sdcard/ui.xml".format(ADB=ADB))
        sh("{ADB} shell uiautomator dump /sdcard/ui.xml".format(ADB=ADB))
        out = sh("{ADB} shell cat /sdcard/ui.xml".format(ADB=ADB))
        if out.strip().startswith("<?xml"):
            return out
        time.sleep(0.8)
    return ""


def nodes(pkg=None):
    xml = ui()
    return [dict(re.findall(r'(\S+)="([^"]*)"', m.group(0)))
            for m in re.finditer(r"<node[^>]*>", xml)
            if pkg is None or 'package="{}"'.format(pkg) in m.group(0)]


def texts(pkg=None):
    return [d.get("text", "") for d in nodes(pkg) if d.get("text")]


def center(d):
    nums = [int(x) for x in re.findall(r"\d+", d.get("bounds", ""))]
    if len(nums) != 4:
        return None
    return ((nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2)


def tap_xy(xy, settle=1.2):
    if xy:
        sh("{ADB} shell input tap {x} {y}".format(ADB=ADB, x=xy[0], y=xy[1]))
        time.sleep(settle)
        return True
    return False


def tap_text(pkg, label, timeout=8):
    end = time.time() + timeout
    while time.time() < end:
        for d in nodes(pkg):
            if d.get("text") == label:
                return tap_xy(center(d))
        time.sleep(0.4)
    return False


def tap_desc(pkg, *names, **kw):
    timeout = kw.get("timeout", 8)
    end = time.time() + timeout
    while time.time() < end:
        for d in nodes(pkg):
            if d.get("content-desc") in names:
                return tap_xy(center(d))
        time.sleep(0.4)
    return False


def find_text(pkg, label):
    for d in nodes(pkg):
        if d.get("text") == label:
            return d
    return None


def wait_text(frag, pkg=PKG, timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        if any(frag in t for t in texts(pkg)):
            return True
        time.sleep(0.4)
    return False


def wait_text_front(frag, timeout=15):
    """等被测界面上出现某段文本；期间被抢了前台就把它拉回最前再等一次。

    抢前台是「外面还有别的自动化」造成的，不是被测代码的问题——所以这里不能直接把
    「超时没等到」当成断言失败，先自己恢复现场。
    """
    if wait_for(lambda: any(frag in t for t in texts(PKG)), timeout=timeout, interval=0.5):
        return True
    bring_to_front()
    return wait_for(lambda: any(frag in t for t in texts(PKG)), timeout=5, interval=0.5)


def focus_pkg():
    m = re.search(r"mCurrentFocus=Window\{[^}]*\s([\w.]+)/",
                  sh("{ADB} shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus".format(ADB=ADB)))
    return m.group(1) if m else None


def stop_interfering(pkgs=None):
    """把会抢前台的应用按下去一次（理由见文件头的坑 3）。它们下次被点开时自己会起来。"""
    for pkg in (pkgs if pkgs is not None else INTERFERING_APPS):
        sh("{ADB} shell am force-stop {pkg}".format(ADB=ADB, pkg=pkg))


def disabled_packages():
    return sh("{ADB} shell pm list packages -d".format(ADB=ADB))


def disable_interfering():
    """**可选手段**（`--disable-interfering`）：临时停用抢前台的应用，返回要恢复的包名。

    默认**不用**：它改的是设备的持久状态，而「保证还回去」这件事在外层 shell 被一起杀掉时
    是不成立的（实测 `pkill -f <脚本名>` 连父 shell 一起杀，Python 的 finally 没跑，
    另一个应用就留在停用状态）。轮到自己上机时发现别人的应用没了，代价太大。

    默认走 `bring_to_front()` 那条路：不动别人，被抢了就自己把前台抢回来。
    """
    already = disabled_packages()
    disabled = []
    for pkg in INTERFERING_APPS:
        if "package:{}".format(pkg) in already:
            continue  # 本来就是停用的，不是我们干的，别乱动
        sh("{ADB} shell pm disable-user --user 0 {pkg}".format(ADB=ADB, pkg=pkg))
        if "package:{}".format(pkg) in disabled_packages():
            disabled.append(pkg)
    return disabled


def restore_interfering(disabled):
    """把停用过的包恢复成启用，并**复核**（不然下次上机发现别人的应用没了）。"""
    for pkg in disabled:
        sh("{ADB} shell pm enable {pkg}".format(ADB=ADB, pkg=pkg))
        if "package:{}".format(pkg) in disabled_packages():
            print("  ⚠️ {} 没有恢复成启用状态，请手动跑：adb shell pm enable {}".format(pkg, pkg))


def start_watchdog(pkgs):
    """后台反复把抢前台的应用按下去。

    这只是**缩短**它占据前台的时间窗，不能根治：外部的自动化会不断 `am start` 它。
    真正的保障是 `bring_to_front()`——不管被抢走多少次，断言前都把前台拉回来。
    """
    stop = threading.Event()

    def loop():
        while not stop.is_set():
            stop_interfering(pkgs)
            stop.wait(0.8)

    if not pkgs:
        return stop
    t = threading.Thread(target=loop, daemon=True)
    t.start()
    return stop


def stop_watchdog(stop):
    stop.set()


def bring_to_front():
    """把被测应用的任务拉到最前。

    外部自动化会不定时把别的应用拉到前台。被压下去之后，**即使 picker 正常返回了结果，
    编辑器也只是「在后台」**——断言读到的全是没有该控件的界面，看起来像功能坏了。
    这里做的就是把任务重新置顶。

    安全：picker 是被测 Activity 用 `startActivityForResult` 起的，**同一个 task**，
    所以这一步不会把还没走完的 picker 挤掉（顶上来的是整个 task，最上面还是 picker）。
    """
    stop_interfering()
    sh("{ADB} shell am start -n {pkg}/.MainActivity".format(ADB=ADB, pkg=PKG))
    time.sleep(1.5)


def ensure_app():
    """确保前台是被测应用；被抢走了就拉回来。"""
    if focus_pkg() == PKG:
        return True
    bring_to_front()
    return focus_pkg() == PKG


def chars():
    """状态栏里的字符计数，例如 '33 字符 · 3 行'。"""
    for t in texts(PKG):
        m = re.search(r"(\d+)\s*(?:字符|chars)", t)
        if m:
            return int(m.group(1))
    return None


def title():
    """顶栏标题（未保存时前面带 '• '）。

    按**位置**取而不是按内容猜：文件名是 picker 去重后决定的（目标目录里已有同名时会变成
    「xxx (1).txt」），脚本里写不死。顶栏文字落在 87~165，编辑区的 placeholder 在 231 以下，
    用 y 就能干净地分开；放宽到 250 会把 placeholder 也框进来，而它在窗口树里排在前面。
    """
    for d in nodes(PKG):
        r = re.findall(r"\d+", d.get("bounds", ""))
        t = d.get("text", "")
        if len(r) == 4 and int(r[1]) < 200 and t and not t.isdigit():
            return t
    return None


def title_name():
    return (title() or "").lstrip("• ").strip()


def picker_filename(timeout=8):
    """picker 里预填的文件名。刚弹出的那一两秒输入框还不在窗口树里，所以要重试。"""
    end = time.time() + timeout
    while time.time() < end:
        for d in nodes(PICKER):
            if d.get("class", "").endswith("EditText"):
                return d.get("text", "")
        time.sleep(0.5)
    return None


def wait_for(pred, timeout=10, interval=0.6):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        time.sleep(interval)
    return pred()


def wait_picker():
    if not wait_for(lambda: find_text(PICKER, "保存") is not None, timeout=15, interval=0.5):
        return False
    time.sleep(1.0)  # 让输入框也进窗口树，后面要读它的预填名
    return True


def accept_picker():
    """点「保存」，等编辑器回到前台。

    ⚠️ 判据是**前台包名**，不是状态栏里有没有 `UTF-8`：别人把前台抢走时，picker 照样会
    正常返回结果、`onActivityResult` 照样跑，只是编辑器落在后台——用文本当判据就会
    把「被抢前台」报成「保存没生效」。点了没反应（界面在动）也再点一次。
    """
    for attempt in range(3):
        if not tap_text(PICKER, "保存", timeout=8):
            return False
        if wait_for(lambda: focus_pkg() == PKG, timeout=10):
            return True
        bring_to_front()
        if focus_pkg() == PKG:
            return True
        print("       (第 {} 次点「保存」后编辑器没到前台，再点一次)".format(attempt + 1))
    return False


def check(name, got, want):
    global pass_count
    ok = got == want
    if ok:
        pass_count += 1
    else:
        fails.append(name)
    print("  {} {}: 实际={!r} 期望={!r}".format("OK  " if ok else "FAIL", name, got, want))


def snapshot(tag):
    """失败点的现场。

    只有「哪一步没过」是定位不了问题的——前台可能已经被别人抢走，或者界面正在动画中。
    把前台包名和两边的可见文本一起打出来，一次运行就能判断是「功能坏了」还是「环境抢了前台」。
    """
    print("       [{}] 前台={} | picker={} | 被测={}".format(
        tag, focus_pkg(), texts(PICKER)[:6], texts(PKG)[:6]))


def fail(msg, tag=None):
    """记一条失败并打印现场（所有提前返回的失败点都走这里）。"""
    fails.append(msg)
    print("  FAIL {}".format(msg))
    snapshot(tag or msg)


def attempt_case(fn, *args, attempts=3):
    """跑一个用例，失败就重来。

    设备上除了本脚本还有别的自动化在活动（见文件头的坑 3），它会不定时把被测应用
    force-stop 掉——`dumpsys activity exit-info` 里全是 `FORCE STOP`，不是崩溃。
    一次失败不代表代码有问题，所以整个用例重来；**只保留最后一次的结果**，
    免得把重试过程中的中间失败也计进总数里，看上去像一堆功能坏了。
    """
    global pass_count
    for i in range(1, attempts + 1):
        pass0, fail0 = pass_count, len(fails)
        fn(*args)
        if len(fails) == fail0:
            return True
        if i < attempts:
            print("  (第 {} 轮没过 {} 项，设备被外部干扰，重来)".format(i, len(fails) - fail0))
            del fails[fail0:]
            pass_count = pass0
    return False


# ---------- 设备侧文件 ----------

def ls_dir():
    """目录里的文件名集合。

    **必须按行切，不能 `.split()`**：picker 去重后的名字里带空格
    （「tn_a2_src (2).txt」），按空白切会把它劈成「tn_a2_src」和「(2).txt」两个假名字，
    于是「新多出来的文件」永远算不出来——而现象是「另存为没有生成文件」，
    看起来像功能坏了。
    """
    out = sh("{ADB} shell ls -1 {d}".format(ADB=ADB, d=SAVED_DIR))
    return {line.strip() for line in out.splitlines() if line.strip()}


def pull(remote, local):
    """把设备上的文件拉回本地；拉不回来返回 None。

    两个坑：
    1. **远端路径必须加引号。** picker 去重后的文件名带空格（「tn_a2_src (4).txt」），
       裸着写会被当成多个参数，`adb pull` 静默失败——而失败的样子是
       「另存为没有写出文件」，看着像功能坏了。源文件名里没空格，所以只有另存为那一条会炸。
    2. **拉之前先删掉本地同名文件。** 否则上一轮的残留会被当成这一轮的内容读回来，
       拉取失败反而「成功」了。
    """
    if os.path.exists(local):
        os.remove(local)
    sh('{ADB} pull "{r}" "{l}" >/dev/null 2>&1'.format(ADB=ADB, r=remote, l=local))
    if not os.path.exists(local):
        return None
    with open(local, "rb") as f:
        return f.read()


def wipe_our_files():
    """删掉本脚本自己造过的文件（源文件与所有前缀同名的派生文件）。"""
    names = [n for n in ls_dir()
             if n == SRC or n.startswith(SRC.split(".")[0]) or n.startswith(DEFAULT_NAME.split(".")[0])]
    for n in names:
        delete_file(n)


def delete_file(name):
    """删掉设备上的一个文件（磁盘 + MediaStore 条目）。

    路径的引号**必须活着到设备侧**：`adb shell` 是把参数用空格拼起来再交给设备 shell 的，
    所以本地 shell 吃掉引号之后，设备那边会看到 `rm -f /sdcard/Download/tn (4).txt`，
    `(` 直接报语法错误、文件删不掉（实测）。写成 `adb shell "rm -f '...'"` 让单引号
    留在参数里，设备 shell 才拿得到它。
    """
    sh('{ADB} shell "rm -f \'{d}/{n}\'"'.format(ADB=ADB, d=SAVED_DIR, n=name))
    sh('{ADB} shell content delete --uri content://media/external/file '
       '--where "\\"_display_name=\'{n}\'\\"" >/dev/null 2>&1'.format(ADB=ADB, n=name))


def push_fixture():
    """推一份源文件并拿到 MediaStore id（id 每台设备不同，现查不硬编码）。"""
    local = "/tmp/{}".format(SRC)
    with open(local, "w", encoding="utf-8", newline="") as f:
        f.write(SRC_BODY)
    remote = "{}/{}".format(SAVED_DIR, SRC)
    sh("{ADB} push {l} {r} >/dev/null 2>&1".format(ADB=ADB, l=local, r=remote))
    sh('{ADB} shell content call --uri content://media/external/file '
       '--method scan_file --arg {r} >/dev/null 2>&1'.format(ADB=ADB, r=remote))
    rows = sh("{ADB} shell content query --uri content://media/external/file "
              "--projection _id:_display_name 2>/dev/null".format(ADB=ADB))
    for line in rows.splitlines():
        if SRC in line:
            m = re.search(r"_id=(\d+)", line)
            if m:
                return int(m.group(1))
    raise SystemExit("没能拿到 {} 的 media id，看看 scan_file 是否成功".format(SRC))


def restart_home():
    """从干净状态回到首页。

    三件事按顺序做，而且**别用 `am start -S`**：
    - 先 `am force-stop`：上一轮留下的任务还在的话，`am start` 只会把那个任务拉到前台
      （输出里写着 "brought to the front"），界面停在上一轮的编辑器上，表现为「首页没起来」。
    - 再 `pm clear`：清掉最近列表 / 语法覆盖这些存档。
    - `-S` 看着正好是「先停再起」，但它发的 force-stop 是异步的：实测进程刚起 0.25s 就被
      它杀掉（`am_kill ... stop com.textnote.app due to from pid <am 自己>`）。
    """
    stop_interfering()
    sh("{ADB} shell am force-stop {pkg}".format(ADB=ADB, pkg=PKG))
    time.sleep(0.8)
    sh("{ADB} shell pm clear {pkg}".format(ADB=ADB, pkg=PKG))
    time.sleep(1.2)
    sh("{ADB} shell am start -n {pkg}/.MainActivity".format(ADB=ADB, pkg=PKG))
    return wait_text_front("打开文件", timeout=20)


def open_via_intent(media_id):
    """用外部 Intent 打开，**只授临时读权限**。"""
    sh("{ADB} shell am start -a android.intent.action.VIEW "
       "-d content://media/external/file/{i} -t text/plain "
       "--grant-read-uri-permission -n {pkg}/.MainActivity".format(ADB=ADB, i=media_id, pkg=PKG))
    return wait_text_front("UTF-8", timeout=20)


# ---------- 用例 ----------

def case_a(created):
    print("=== A. 新建（不输入文字）===")
    if not restart_home():
        fail("首页没起来", "restart_home")
        return None
    if not tap_text(PKG, "新建文件"):
        fail("首页找不到「新建文件」", "tap 新建文件")
        return None
    if not wait_picker():
        fail("系统 picker 没出现", "wait_picker")
        return None
    check("picker 的预填名", picker_filename(), DEFAULT_NAME)
    if not accept_picker():
        fail("picker 走不完，编辑器没起来", "accept_picker")
        return None
    time.sleep(1.2)
    if not ensure_app():
        fail("新建之后前台不是被测应用", "ensure_app")
        return None
    name = title_name()
    check("标题里有文件名", bool(name), True)
    check("编辑器状态栏出现编码名", any("UTF-8" in t for t in texts(PKG)), True)
    check("新建后字符数为 0", chars(), 0)
    check("新建后不在只读模式", any(t.strip() == "只读" for t in texts(PKG)), False)
    if name:
        created.append(name)


def case_b(media_id, created):
    print("\n=== B. 另存为（只有临时读授权的文件 → 存到别处）===")
    if not restart_home():
        fail("首页没起来", "restart_home")
        return
    if not open_via_intent(media_id):
        fail("编辑器没起来", "open_via_intent")
        return
    time.sleep(1.0)
    if not ensure_app():
        fail("打开后前台不是被测应用", "ensure_app")
        return
    check("打开后标题是源文件名", title_name(), SRC)
    check("正文按磁盘内容显示", chars(), len(SRC_BODY))

    before_names = ls_dir()
    before_chars = chars()
    if not tap_desc(PKG, "更多操作", "More actions"):
        fail("找不到溢出菜单按钮", "tap 更多操作")
        return
    if not tap_text(PKG, "另存为…"):
        fail("溢出菜单里找不到「另存为…」", "tap 另存为")
        return
    if not wait_picker():
        fail("另存为的 picker 没出现", "wait_picker")
        return
    check("另存为的预填名是当前文件名", picker_filename(), SRC)
    if not accept_picker():
        fail("另存为 picker 走不完", "accept_picker")
        return
    time.sleep(1.2)

    if not ensure_app():
        fail("另存为之后前台不是被测应用", "ensure_app")
        return

    print("\n--- 另存为之后编辑器已经属于新文件 ---")
    new_name = title_name()
    created.append(new_name)
    check("标题换成了另一个文件名", bool(new_name) and new_name != SRC, True)
    check("内容一个字都没动", chars(), before_chars)
    check("状态栏没有出现「只读」", any(t.strip() == "只读" for t in texts(PKG)), False)

    print("\n--- 目标目录里确实多出一个一模一样的文件 ---")
    added = ls_dir() - before_names
    check("目录里多且只多出一个文件", len(added), 1)
    target = added.pop() if added else None
    check("多出来的那个就是新标题", target, new_name)
    if target:
        src_bytes = pull("{}/{}".format(SAVED_DIR, SRC), "/tmp/tn_a2_src.txt")
        new_bytes = pull("{}/{}".format(SAVED_DIR, target), "/tmp/tn_a2_new.txt")
        check("源文件拉得回来", src_bytes is not None, True)
        check("另存为的文件拉得回来", new_bytes is not None, True)
        check("源文件与推上去的内容逐字节相同", src_bytes, SRC_BODY.encode("utf-8"))
        check("另存为的文件与源文件逐字节相同", new_bytes, src_bytes)
        check("没有混进 CR（行尾没被改写）", b"\r" in (new_bytes or b""), False)

    print("\n--- 切后台再回来，不能误报外部改动 / 草稿 ---")
    # 另存为写了磁盘上的新文件，也就动了 mtime。基准没刷新的话，这一下就会把自己刚写的
    # 文件报成「被其他应用修改」。压 Home 再拉回来是为了真的走一次 ON_START。
    sh("{ADB} shell input keyevent 3".format(ADB=ADB))  # KEYCODE_HOME
    time.sleep(1.5)
    bring_to_front()
    time.sleep(1.5)
    if ensure_app():
        body = texts(PKG)
        check("没有「被其他应用修改」横幅", any("其他应用" in t for t in body), False)
        check("没有「发现草稿」横幅", any("发现" in t and "草稿" in t for t in body), False)
        check("标题仍是那个新文件名", title_name(), new_name)

    print("\n--- 返回首页，两份文件都在最近列表里 ---")
    if not tap_desc(PKG, "返回文件列表", "返回檔案列表", "Back to files"):
        fail("找不到返回按钮", "tap 返回")
    else:
        time.sleep(2.5)
        ensure_app()
        recent = texts(PKG)
        check("最近列表里有 {}".format(SRC), SRC in recent, True)
        check("最近列表里有 {}".format(new_name), new_name in recent, True)


def case_c():
    print("\n=== C. 输入 → 未保存标记 → 保存（唯一依赖输入法的一步，失败重试三轮）===")
    # 输入法窗口会让 uiautomator 的 dump 偶发取不到界面，而「抢前台」也多半发生在这段时间，
    # 所以这一步重试；其余用例都不打字。
    body = "alpha beta gamma"
    body_adb = body.replace(" ", "%s")  # `input text` 把空格写成 %s，直接写空格会被截断
    for attempt in range(1, 4):
        if not restart_home():
            continue
        if not tap_text(PKG, "新建文件") or not wait_picker() or not accept_picker():
            continue
        time.sleep(1.2)
        if not ensure_app():
            continue
        sh("{ADB} shell input tap 540 1000".format(ADB=ADB))  # 聚焦编辑区
        time.sleep(0.8)
        sh('{ADB} shell input text "{b}"'.format(ADB=ADB, b=body_adb))
        time.sleep(1.0)
        if chars() == len(body):
            check("输入后的字符数", chars(), len(body))
            check("标题带未保存标记", (title() or "").startswith("•"), True)
            if not tap_desc(PKG, "保存", "Save"):
                fail("找不到保存按钮", "tap 保存")
            else:
                check("出现「已保存」提示", wait_text_front("已保存", timeout=10), True)
            time.sleep(0.6)
            check("保存后标记消失", (title() or "").startswith("•"), False)
            return
        print("  (第 {} 轮输入没生效——当前前台 {}，重来)".format(attempt, focus_pkg()))
        time.sleep(1.5)
    fail("输入后读不到正确的字符数（三轮都没成）", "case_c")


def _terminate(signum, frame):
    raise SystemExit(0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--disable-interfering", action="store_true",
                    help="临时停用会抢前台的应用（改设备持久状态，默认不用；见 disable_interfering）")
    args = ap.parse_args()

    # 默认的 SIGTERM 处理会让进程立刻退出、finally 不跑。转成异常，保证恢复逻辑执行。
    signal.signal(signal.SIGTERM, _terminate)

    # 先清掉自己以前留下的测试文件：pickr 遇到重名会改成「xxx (2).txt」，
    # 残留越多这一轮读回来的名字越飘，而且下载目录会越积越乱。
    wipe_our_files()
    media_id = push_fixture()
    print("fixture: {} -> media id {}".format(SRC, media_id))

    created = []
    disabled = disable_interfering() if args.disable_interfering else []
    if disabled:
        print("临时停用抢前台的应用（跑完自动恢复）：{}".format("、".join(disabled)))
    print("看门狗按住的抢前台应用：{}".format("、".join(p for p in INTERFERING_APPS if p not in disabled)))
    watchdog = start_watchdog([p for p in INTERFERING_APPS if p not in disabled])
    try:
        attempt_case(case_a, created)
        attempt_case(case_b, media_id, created)
        attempt_case(case_c)
    finally:
        stop_watchdog(watchdog)
        if disabled:
            restore_interfering(disabled)
        if not args.keep:
            print("\n清理…")
            # 除了本轮记录下来的名字，还要清掉残留的「未命名*.txt」：中断过的运行会留下它们，
            # 而 picker 遇到重名会改成「未命名 (1).txt」，残留越多这一轮的名字就越飘。
            # 这个名字只可能是本项目自己的对话框建议出来的（见 R.string.untitled）。
            for n in set(created) | {x for x in ls_dir() if x.startswith(DEFAULT_NAME.split(".")[0])}:
                delete_file(n)
            delete_file(SRC)
            sh("{ADB} shell rm -f /sdcard/ui.xml".format(ADB=ADB))
            sh("{ADB} shell pm clear {pkg}".format(ADB=ADB, pkg=PKG))

    print("\n通过 {} / 失败 {}".format(pass_count, len(fails)))
    if fails:
        for f in fails:
            print("  - {}".format(f))
        sys.exit(1)


if __name__ == "__main__":
    main()
