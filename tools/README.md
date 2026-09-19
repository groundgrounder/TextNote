# tools/

开发期用的脚本。都不参与构建，手动跑。

## `checks/` —— core 层的纯逻辑断言

**635 条断言**，测的是 `core/` 与 `data/` 里不依赖 Android 运行时的部分：编码探测、行索引、
行尾往返、词法着色、搜索边界、撤销栈、体积上限的不变量、体积文案、草稿保留期、字号档位、
界面语言标签。不需要模拟器，秒级完成。

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
| `CheckUndo.java` | `UndoStack`：单段差分的**前后缀不重叠**、连续输入/退格/前向删除合并（⚠️ 删除的两个方向**拼接顺序相反**：退格是「先删的在右」、Delete 是「先删的在左」，写反了撤销会把文本恢复成**倒序**——所以断言里既比长度也比内容顺序）、跨类型与超时不合并、redo 分支作废、双重上限淘汰、**差分数与正文对不上时清栈而不是硬改** |
| `CheckSyntaxRegistry.java` | 34 种语法的文件名归属与优先级（**整名优先于扩展名**：`CMakeLists.txt` 不能落回纯文本；`jsx`/`tsx` 归 JS 而非 HTML）、按 id 取语法、认不出的名字退回纯文本（含「名字本身长得像扩展名却没有点」：`kt` / `rs` 是纯文本，`a.` 也是）、**补丁的逐行分类与判定顺序**（`@@`/`---`/`+++` 必须先于单字符的 `+`/`-`，否则 `@@ -12,7 +12,9 @@` 里的加减号会被当成增删行；行内的 `+` 不能另起 token；上下文行不着色）。另含两条扫全部语法的通用不变量：**词表里不能有永远匹配不到的条目**（扫描器只认标识符形状，`filter-out`、`defined?`、`foldl'` 写进去就是死数据）、**每种语法都要能着出颜色**（纯文本除外，那是它的功能定义） |
| `CheckBinarySniff.java` | 「这是不是文本」的判据，与仓库**同序**（`bytes → TextEncoding.decode → looksBinary`）。**两个方向都验**：PNG/JPEG/ZIP/ELF/MP3 该拒；而**带 BOM 的 UTF-16 不许拒**（它在字节层每隔一个字节就是 `0x00`，判据一旦落在字节上就会误杀整份正常文档）、制表符占三成不算可疑、阈值两侧（20% 放行 / 20.5% 拒收）、采样窗口之外有 NUL 时放过（已知取舍）、无 BOM 的 UTF-16 被拒（已知取舍，界面提示里写了自救办法） |
| `CheckDiagnostics.java` | 本地单文件分析（JSON 校验）：**每条非法用例都断言到具体偏移与区间长度**（扫两器最容易错的就是「多走一格、少走一格」）；合法用例（数字边界、转义、深嵌套、空文件）**一条都不许报**——会误报的校验器比没有校验器更糟；另含闸门（非 JSON 不分析、超上限不分析、`.jsonc` 宽松放过注释与尾随逗号）与「嵌套 1000 层报太深而不是栈溢出」 |
| `CheckLimits.java` | `EditorLimits` 三档上限**单调有序**、`READ_BYTES >= OPEN_CHARS × 4` 等不变量 |
| `CheckByteSize.java` | `formatBytes`：三个量级的切换点（1MB 差一字节的两侧）、只保留一位小数、小数点是点号（`Locale.US` 刻意固定）。**用户可见的「4.0 MB」「8.0 MB」就是它拼出来的**，设备脚本 `verify_open_tiers.py` 断言的那串也是它——原先它 `private` 在一个 Compose 文件里，只能等设备跑一遍才发现写错 |
| `CheckEncoding.java` | `TextEncoding` 的 BOM 优先、UTF-8→GB18030 探测顺序，以及 **`decode`（打开正文）与 `decodeTruncated`（列表摘要）对同一个文件必须认成同一种编码**；另含**全链路字节级往返**：LF/CRLF/CR × ASCII/GBK/UTF-8-BOM/UTF-16LE-BOM（比 `CheckLineIndex` 里那组多走一次 encode） |
| `CheckDraftStore.java` | 草稿保留期的算术：边界（恰好到期 / 差 1 毫秒 / 过期一年）、向上取整（界面上不能说「还剩 0 天」，那是「已经没了」的意思）、随时间是单调不增的。**真正的目的是钉住两条实现同进退**——`daysUntilExpiry`（首页那句「N 天后清理」）与 `prune()` 各自算一遍保留期，谁改错都不会让构建失败，只会让界面说的话与实际删除时间不一致 |
| `CheckFontRanges.java` | 字号 / 行距的候选档位：候选值 `coerce` 后等于自身（幂等，等于「滑得到=存得下」）、非法值退回默认而不是夹到最近一档、浮点容差（行距要过 SharedPreferences 的 Float 往返）、表严格递增无重复，以及**默认值必须在候选表里**——不在的话 `SIZES.indexOf(default)` 返回 -1，设置页的滑块会把当前值悄悄显示成第一档 |
| `CheckAppLanguage.java` | 界面语言标签的归一与往返：每种语言的 tag 必须能还原回自己（它是持久化的键）、认不出的一律「跟随系统」、旧版本标签 `zh_CN`（下划线）与系统回传的带区域形式 `zh-Hans-CN`/`en-US` 都要接得住、中国台湾/中国香港/中国澳门的地区码要落到繁体（资源用 `zh-Hant`，一次覆盖三地）、以及「只有 `SYSTEM` 的 tag 是空串」（`getResources()` 的判据）这类枚举自洽 |

为什么要绕一圈、不放进 `app/src/test`：那个源集要引入 JUnit，而引入就得联网拉依赖；
这些断言又完全不需要 Android 运行时，只需要 `core/` 的 class 和 kotlin-stdlib。
所以走「复用 Gradle 已编译的 class + javac 现场编译」，**零新依赖**。

新增断言：在 `checks/` 里加一个 `CheckXxx.java`，照着现有文件的样子写
（`main` 里累加 pass/fail，失败时 `System.exit(1)`），脚本会自动带上它。

## `check_locales.py` —— 多语言资源的一致性

**改任何一套 `strings.xml` 或 `locales_config.xml` 之后跑它**，秒级完成、不需要模拟器：

```bash
python3 tools/check_locales.py
```

守三件靠人记必然记漏的事：

| 检查 | 漏了会怎样 |
|---|---|
| 各 `values*/strings.xml` 的键集合一致 | 该语言静默回落成英文，界面上变成「一页里夹着两句英文」，review 时看不出来 |
| 同一个键里的格式占位符一致 | `MissingFormatArgumentException` 直接崩，或参数串位（把行数显示成字符数） |
| `locales_config.xml` 与 `values-*` 目录一一对应 | 声明了却没资源 = 系统「应用语言」里出现选了没反应的假选项；有资源却没声明 = 用户根本选不到这套译文 |

目录名与 locale 标签的换算是 `zh-Hans` ↔ `values-b+zh+Hans`（BCP-47 的 `-` 在目录名里写成 `+`），
`en` 落在兜底的 `values/`。

它还带一个开关：`python3 tools/check_locales.py <资源目录>`，可以指向一份**故意做坏的副本**，
用来确认它真的会报警 —— 一个从不报警的检查脚本比没有检查更糟。CI 里也接了这条
（见 `.github/workflows/android.yml`）。

## `dead_code.py` —— 死代码扫描

**改完 `core/` / `data/` 的声明面之后跑它**，秒级完成、不需要模拟器：

```bash
python3 tools/dead_code.py               # 扫项目
python3 tools/dead_code.py --self-test   # 先验扫描器本身
```

九项口径，共同点是**都不会让构建失败**——所以只能靠这么一条静态检查盯住：

| 口径 | 漏了会怎样 |
|---|---|
| 未用的 import | 无害，但会误导后来人：看着像在用的东西 |
| 零引用的声明 | 「以后可能用得上」的接口 = 没人验证过的承诺面。`private` 在本文件里数、顶层在全语料里数（消费者通常在别的文件） |
| 只写不读的字段 | **死劳动**：算出来没人看。赋值处要是还跨进程查过一次 provider，那就是每次刷新白花的 IPC（`DocumentMeta.writable` 就是这么被扫出来的） |
| 零引用的枚举成员 | ⚠️ 遍历 `entries` 的写法**不算引用**，报出来先看它是不是「行为由数据驱动」的那种 |
| 函数体里没出现的参数 | 四条扫描分支共用签名是刻意设计，但得在代码里写明——留就加 `@Suppress("UNUSED_PARAMETER")`，不加就是漏删 |
| 没被引用的字符串键 | 资源不裁剪（本项目没开 `shrinkResources`），会原样进包；四套译文一起改时还容易漏删 |
| `values*/` 里没被引用的 color / style / dimen | 同上。注意 `values*/` 是资源**容器**，文件名自身没人引用，被引用的是里面的键名 |
| 按文件名没人引用的资源（drawable / mipmap / xml） | 图标最容易：`mipmap-anydpi-v26/ic_launcher_round.xml` 就是「文件在、manifest 里却没声明 `roundIcon`」 |
| 整个 `.kt` 文件的声明都零引用 | 一整块没人碰过的代码 |

两类必踩的误报脚本里已经排除：**框架覆写**（`override` 由框架虚调用，源码里当然没人按名引用）
与 **Kotlin 属性在 Java 侧叫 `getXxx()`**（`tools/checks/*.java` 就是那么调的，漏了这个口径
会把「只被断言用到」的成员误报成死代码）。只看 `app/src` 与 `tools/`：
`.workbuddy/` 里是审查报告与日志，那些文本里的名字**不构成引用**，算进语料反而会把真死代码掩盖掉。

`--self-test` 喂的是一份内置的**人造死代码 + 对照组**，要求每个检测器都报出自己那一类、
且对照组一个都不许误报（对照组挡的是「把用得好好 的东西判成死的」，比如被引用的字符串键——
`used_key` 还是 `unused_key` 的**子串**，比较方式写松了自己就会骗自己）。存在的原因是：
**一个从不报警的检查脚本比没有检查更糟**，「0 命中」和「扫描器根本没跑起来」在输出上长得一模一样。
CI 里两步都跑。

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
| `verify_syntax_detect.py` | 「文件名 → 语法」的**端到端**识别：把一批探针文件推上设备，走正常打开流程，按状态栏 `content-desc` 读回语法名。JVM 断言只测到 `forFileName`，这条补的是它后面那段（显示名的取法 → 自动识别 → 状态栏）。整名匹配的用例**必须用精确文件名**（`Makefile` 而不是 `tn_detect_Makefile`，后者本来就该是纯文本）。同一个文件被别的脚本复用：`write_remote`（支持 str/bytes）、`media_id`、`open_doc`、`ui`、`syntax_state` |
| `verify_not_text.py` | 二进制防线：**该拒的拒、不该拒的绝不拒**。PNG / 随机字节该被判为非文本；**带 BOM 的 UTF-16、GB18030、UTF-8 必须照常打开**。判据是失败页标题（中英两种写法都要认）。⚠️ 不能复用 `open_doc` 的「等状态栏 UTF-8」判据——被测行为恰恰是**没有状态栏** |
| `verify_share_send.py` | 分享文件（`ACTION_SEND` + `EXTRA_STREAM`）：用 `cmd package query-activities` 问系统「分享面板里有没有 TextNote」**并带反例**（PNG / MP4 / octet-stream 不该出现），再真投一个 SEND intent 验证文件进了编辑器。⚠️ `am` 设不了 `ClipData`，而平台的读权限不覆盖 `EXTRA_STREAM`——所以 `ClipData` 那条分支**没有设备覆盖**，见脚本头部说明 |
| `verify_diagnostics.py` | 本地分析（JSON 校验）：坏 JSON 与重复键要在状态栏报出「问题 · N」，**点一下要跳到那一行并在提示条里说出是什么问题**；同时**反例必须一条不报**——好 JSON、宽松的 `.jsonc`、以及「同样内容但扩展名是 .txt」（分析只对认得出的语法生效）。判据走 `content-desc`，中英两种文案都要认 |
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
  判断「当前是哪个界面」要两个都匹配。**注意别用 `grep -m1 ResumedActivity` 一把抓**——
  `mResumedActivity` 是每条任务记录里的另一个字段，抓错了会得出「前台是别的应用」这种
  根本不成立的结论（2026-09-16 为此白绕一圈）。
- **屏幕休眠时 `uiautomator dump` 一定返回 `null root node`，而 exit code 仍是 0**——不报错，
  但所有按文本读的断言会静默读成空（`实际=None` 一片），看起来像产品全崩。
  dump 前调一次 `_fixtures.ensure_awake()`（幂等，醒着只多一次 `dumpsys power`）；
  诊断用 `dumpsys power | grep mWakefulness=`。设备空闲一段时间就会睡，跑之前先想到它。
- **`input swipe` 别从屏幕边缘起手**：起手点落在系统的「边缘返回手势」区（默认左右各二三十像素）
  时，整个手势会被系统吃掉变成**返回**——症状是「拖滑块没反应」，或者更迷惑的「页面退了一层、
  控件从 dump 里消失」。实测拖字号/行距滑块时踩过：从 x≈20 起手，两次 swipe 把设置页和文档
  各退了一层，偏好文件里一个字节都没写。起手内移到离边缘 ≥100px，落点也留 30px。
- **点/拖之前先找控件、别按文字点**：字号/行距是 `SteppedSlider`（Slider + 当前值标签），
  **没有**「22」「1.8」这类可点文字——按文字点必然落空（2026-09-16 因此 5 条断言假失败）。
  同理，`SearchPanel` 的查询框/替换框是自绘 `BasicTextField`，**在语义树里没有节点**，
  只能靠「面板已开」判断 + 按按钮 bounds 反推位置。
- **注入键（`input text` / `input keyevent`）可能整台设备上都不带字符**：按键会到达应用，
  但 `utf16CodePoint == 0`，于是 Compose 与普通 `EditText` 都不插入（`tap` 正常、
  **IME 提交路径正常**——查找框那种自动聚焦的输入框反而进得去）。这类设备上「打字」测不了，
  所以输入类断言先探一下（`_input_probe.py`，三态）。两个方向都要小心：
  **「该变」型断言：过了就记通过，只有「没过 + 探针确认通道坏掉」才标 skip**（反过来会放过真回归）；
  **「不该变」型断言（敲了字内容不变）：通道坏掉时必须标 skip**，否则恒真的 "OK" 会假装验证过了。

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
- 所以产品代码里也有一条对应结论：**可编辑文档的判据只落在内容比对上**。元数据那条
  「明确没变就跳过」的快速路径在 2026-09-16 被收回到**只读的大文件**那一条路（那里重读 4MB
  是秒级，只能退而求其次）；可编辑文档每次回前台都读回来比内容。理由是实测看到元数据
  **滞后好几秒、甚至报着上一轮的旧值**（`content write` 写完，provider 那边还是老数字）——
  拿它去否决可信的内容比对，会让外部改动被静默漏掉（见 `EditorViewModel.checkExternalChange`）。
