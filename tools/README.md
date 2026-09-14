# tools/

开发期用的脚本。都不参与构建，手动跑。

## `checks/` —— core 层的纯逻辑断言

**372 条断言**，测的是 `core/` 里不依赖 Android 运行时的部分：编码探测、行索引、行尾
往返、词法着色、搜索边界、撤销栈、体积上限的不变量。不需要模拟器，秒级完成。

（条数以 `tools/run_checks.sh` 的汇总行为准，改完断言顺手把这里也改一下。）

```bash
tools/run_checks.sh          # 直接跑，不用先构建
```

⚠️ 脚本开头会自己跑一次 `compileDebugKotlin`，**编译不过就退出**。因为 `kotlin-classes/debug`
是**上一次编译成功**留下的目录：改了代码而这次编译失败时，旧 class 还在，断言照跑照过——
一片绿，其实一个字节都没验证，编译错误被静默盖掉。

为什么是「主动编译」而不是「比 mtime 然后报错」：Gradle 的 up-to-date 判据是**内容哈希**，
内容没变就不会重编，于是 mtime 型检查一旦被触发就再也解除不掉（重新构建也清不掉），
变成一把锁死自己的假警报。

要有意测旧字节码（罕见），把 `CLASSES` 指到别处即可。

| 文件 | 守着什么 |
|---|---|
| `CheckLineIndex.java` | `LineIndex`（行偏移、二分查行号、软换行下的列号）与 `LineEndings`（探测/归一化/还原的字节级往返） |
| `CheckHighlight.java` | `Highlighter` 的词法正确性 + 着色耗时基准。含一条硬约束：**token 必须按 start 递增且互不重叠**；另含只读渲染器用的按行 API（`lineStates` / `highlightLine`，含超长单行的 `maxChars` 截断与整型溢出防护） |
| `CheckSearch.java` | `SearchEngine`：字面量/正则、大小写、整词、捕获组替换（含 `$0`=整段命中、`${12}` 多位数组号）、零宽匹配丢弃、病态正则不崩、**按行取命中的区间查询**（`matchIndicesInRange`，含跨行命中与「不绕回开头」） |
| `CheckUndo.java` | `UndoStack`：单段差分的**前后缀不重叠**、连续输入/退格合并、跨类型与超时不合并、redo 分支作废、双重上限淘汰、**差分数与正文对不上时清栈而不是硬改** |
| `CheckSyntaxRegistry.java` | 15 种语法的扩展名归属与优先级（`jsx`/`tsx` 归 JS 而非 HTML）、按 id 取语法、认不出的扩展名退回纯文本 |
| `CheckLimits.java` | `EditorLimits` 三档上限**单调有序**、`READ_BYTES >= OPEN_CHARS × 4` 等不变量 |
| `CheckEncoding.java` | `TextEncoding` 的 BOM 优先、UTF-8→GB18030 探测顺序，以及 **`decode`（打开正文）与 `decodeTruncated`（列表摘要）对同一个文件必须认成同一种编码**；另含**全链路字节级往返**：LF/CRLF/CR × ASCII/GBK/UTF-8-BOM/UTF-16LE-BOM（比 `CheckLineIndex` 里那组多走一次 encode） |

为什么要绕一圈、不放进 `app/src/test`：那个源集要引入 JUnit，而引入就得联网拉依赖；
这些断言又完全不需要 Android 运行时，只需要 `core/` 的 class 和 kotlin-stdlib。
所以走「复用 Gradle 已编译的 class + javac 现场编译」，**零新依赖**。

新增断言：在 `checks/` 里加一个 `CheckXxx.java`，照着现有文件的样子写
（`main` 里累加 pass/fail，失败时 `System.exit(1)`），脚本会自动带上它。

## `device/` —— 上机测量与验证脚本

需要一台连着的模拟器/真机，以及 `~/Library/Android/sdk/platform-tools/adb`。

**先造数据**（推文件、建 MediaStore 索引、把 media id 写成表）：

```bash
python3 tools/device/make_fixtures.py     # 造完打印 id 表，写入 /tmp/tn-fixtures.json
python3 tools/device/make_fixtures.py --clean   # 用完删掉设备上的文件
```

media id 每台设备都不一样，所以**不要在脚本里硬编码**——其他脚本都通过
`_fixtures.py` 按名字取 id。没有那张表时会退回内置默认值并打印警告。

| 脚本 | 回答什么问题 |
|---|---|
| `make_fixtures.py` | 造 / 清理测试数据。可只造一部分：`make_fixtures.py small k60` |
| `kernel_bench.py` | 三种文本内核（`value` / `TextFieldState` / 只读懒加载）在同尺寸下的打开耗时与帧耗时百分位。**M4 就是靠它否掉了 `TextFieldState` 这条路的** |
| `readonly_capacity.py` | 只读渲染器在哪个体积开始撑不住；整份文件只有一行时会不会卡死 |
| `verify_open_tiers.py` | 产品路径下五档体积的行为：可编辑 / 可编辑+卡顿提示 / 只读 / 过大 |
| `verify_readonly.py` | 只读模式敲键盘改不动内容、查找可用且隐藏替换行 |
| `verify_recent_files.py` | 首页最近文件列表：列出、点开、移除、授权失效后重新授权 |
| `verify_external_change.py` | 回到前台时能否发现文件被别的应用改过（含「保存自己的写入不能误报」这条回归） |
| `verify_settings.py` | 设置页：字号/行距/主题即时生效、持久化、只读浏览共用同一份样式 |
| `verify_edit_ops.py` | 编辑操作的正确性：撤销/重做（含「连续输入合并成一步」「停顿后分成两步」「退到底后按钮禁用」），以及**连续替换不会原地打转**（`cat`→`catalog` 连点两次应得到两个 `catalog`） |
| `verify_new_and_save_as.py` | 新建文档与另存为（走 SAF 的 `ACTION_CREATE_DOCUMENT`）：预填名、**整体迁到新 Uri 后各状态项不丢**、字节级往返（pull 回来比对）。绕不开系统 picker，**脚本不碰它的文件名输入框**——理由与另外三个坑写在文件头 |
| `verify_syntax_switch.py` | 语法手动切换：手选后覆盖扩展名推断、**重开仍是手选**（持久化，按 Uri 存）、切回「自动」能退回、以及**切换不该把文档标脏**。判据用状态栏语法名的 `content-desc`，不用界面属性（Compose 上读不到 disabled） |
| `verify_back_and_encoding.py` | 两条易静默回归的事：①**查找面板开着时返回键分级**（先收面板、再按才退文档——注意第一次 BACK 会被输入法吃掉，所以逐次按键看状态转移，别用固定次数猜）；②**编码探测的两条路径给同一答案**（`decodeTruncated` 的列表摘要 vs `decode` 的正文，UTF-16 文件曾是「列表乱码、点开正常」）。自造自清 UTF-16 fixture |
| `repro_search_anr.py` | 回归那个曾经必 ANR 的序列（查找框一次性打进 10 个字符） |

`kernel_bench.py` / `readonly_capacity.py` 依赖 debug 包里的 spike 工装
（`app/src/debug/.../spike/SpikeActivity.kt`），其余几个走产品代码路径。
**spike 只存在于 debug 构建里**，release 包里确认没有它。

## `render_icon.py`

生成应用图标资源。

## 这些脚本的共同坑

- **`uiautomator dump` 会偶发失败，而且失败时会留下上一次的 `/sdcard/ui.xml`**——pull 回来
  的是旧界面。每次 dump 前先 `rm -f`，并且**重试**；一次失败就下断言会得到假阴性
  （`verify_recent_files.py` 最初因此误报了 3 处）。
- **固定 `sleep` 不如轮询等待**：界面刚组合完时 dump 会失败。用 `wait_text(...)` 这类
  轮询，别用固定睡眠。
- **`dumpsys gfxinfo` 报 `Failure while dumping the app` 不是工具故障**，是**被测应用主线程
  被占住**——这是「卡死」的最强证据，但只能发现数秒级的阻塞，量不出几百毫秒的卡顿。
- **ANR 不在 `logcat -s AndroidRuntime` 里**，要查 `logcat -b events | grep am_anr`。
- **`dumpsys activity` 不一定输出 `mResumedActivity`**（有时只有 `topResumedActivity`），
  判断「当前是哪个界面」要两个都匹配。

## 怎么模拟「文件被别的应用改了」

这一条单独写，因为它踩了两个坑：

**必须用 `adb shell content write --uri <uri>`（走 ContentResolver），不能用 `adb push`。**
`push` 会让 MediaStore 把条目**删掉重建**，应用手里的 Uri 授权随之失效——于是「被修改」
会被误判成「被删除」，测试结论完全跑偏。改完之后也**不能**触发 `scan_file`，同样会掉授权。

```bash
adb shell content write --uri "content://media/external/file/<id>" < new_content.txt
```

**`content write` 不截断**：新内容必须比当前内容长，否则旧内容的尾巴会留在后面。

## 另一个反直觉的事实：元数据不可信

`recentDocuments()` 与外部改动检测都要读文件的 size / mtime，但：

- MediaStore 对**没有读媒体权限**的应用，`query` 会返回**空 cursor**（不抛异常、不报错），
  看起来就像文件不存在；而 `adb shell content query` 用同一个 Uri 却查得到。
- 所以 `tools/README` 这一节之外，产品代码里也有一条对应结论：**外部改动的判据落在内容
  比对上，元数据只用来走「明确没变」的快速路径**（见 `EditorViewModel.checkExternalChange`）。
