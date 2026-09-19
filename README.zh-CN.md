# TextNote

轻量 Android 纯文本编辑器 · 文档保真 · Kotlin + Jetpack Compose + Material 3

[English](README.md) | **简体中文**

[![Android CI](https://github.com/groundgrounder/TextNote/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/groundgrounder/TextNote/actions/workflows/android.yml)

<p>
  <img src="docs/screenshots/home.png" width="30%" alt="最近打开">
  <img src="docs/screenshots/editor.png" width="30%" alt="编辑界面">
  <img src="docs/screenshots/settings.png" width="30%" alt="设置">
</p>

给「打开看一眼、改两笔、再存回去」的人用的编辑器。**保真是第一位**：打开一个文件、什么都不改就
保存，它不该有任何字节变化——编码、行尾、BOM 全部原样奉还。

## 功能

**写**

- 编辑、撤销/重做（连续输入自动并成一步）、查找替换（正则 / 大小写 / 整词 / `$1` 模板）
- 接硬件键盘：`Ctrl+Z` 撤销、`Ctrl+Shift+Z` / `Ctrl+Y` 重做
- 底部状态栏常驻文档信息：编码、行尾、语法、问题数、行列号、字符数与行数

**看**

- 语法着色 34 种语言，按**文件名**识别：先看整名（`Makefile` / `Dockerfile` /
  `CMakeLists.txt` / `.gitignore`），再看扩展名；可手动指定，并**按文件记住**
- 补丁（`.diff` / `.patch` / `.rej`）按**行首前缀**着色，增删块一眼可见
- 行号栏；大文件转只读浏览后，查找与跳转照常可用

**不弄坏文件**

- 读写保真：编码（UTF-8 / GB18030 / BOM）与行尾（LF / CRLF / CR）读时探测、写时沿用；
  一个文件里混用了多种行尾会明确提示
- 草稿：切到后台立刻落盘；只在草稿与文件**确实不同**时才问「恢复 / 丢弃」，绝不自动套用；
  草稿保留 30 天，快到期时首页会提醒
- 文件被别的应用改过：只提示，不在背后替你套用
- 不是文本的文件（含 NUL 字节、大量不可打印字符）说明原因后拒收，而不是甩一屏乱码

**边界与保护**

- 大文件分三档：超 6.4 万字符不再着色并提示可能卡顿、超 20 万字符转**只读浏览**、
  超 4 MB 直接不打开（不拿内存去赌）
- JSON 边写边校：第一处语法错误标在正文上，状态栏显示问题数，点一下跳过去并说明哪里不对；
  `.jsonc` / `.json5` 按宽松规则校（允许注释与尾随逗号）

**界面**

- 主题：跟随系统 / 浅色 / 深色，支持动态取色（Android 12+）
- 字体族、字号、行距可调；长行折行可关（关掉即横向滚动）
- 界面语言：跟随系统 / English / 简体中文 / 繁體中文 / Latina
- 多窗口：分屏或桌面窗口里可同时开两份文档、各改各的；最近列表长按「在新窗口打开」

## 下载

到 [Releases](https://github.com/groundgrounder/TextNote/releases/latest) 下载 `TextNote-vX.Y.Z.apk`
安装即可（首次需允许「安装未知来源应用」）。要求 Android 8.0（API 26）及以上。

## 用法

- **打开文件**走系统文件选择器；**新建文件**从头写
- 在文件管理器里直接选 TextNote 打开：按 MIME（`text/*` 及一批 `application/*`）匹配，
  另有一长串源码 / 配置扩展名兜底
- 在别的应用里**分享一份文本文件**给 TextNote，同样直接打开
- 不申请任何存储权限——文件读写全部经由系统选择器的授权

## 已知边界（刻意的取舍）

- **不做 Markdown 预览**：这是纯文本编辑器，不是 Markdown 应用
- **着色是逐行近似**，不是编译器前端：嵌套块注释、Rust 原始字符串 `r#"…"#`、Ruby / Shell 的
  heredoc 都不解析；多行构造只认得「一个开界定符 + 一个闭界定符」
- **「是不是文本」按解码后的内容判**：带 BOM 的 UTF-16 能正常打开；无 BOM 的 UTF-16、
  以及第一个 8000 字符之后的 NUL 是已知盲区
- **校验是本地、单文件的**（目前只覆盖 JSON）：不带语言服务器，所以没有补全、悬停、跳转定义，
  也没有工程级分析
- **不捆绑字体**，只用系统三族；列号按 UTF-16 码元计，emoji 占 2 列
- **软换行只作用于可编辑的文档**：大文件的只读浏览按行懒加载，折行要对每行重做断行计算，
  所以它始终不折行
- **不单独识别 Big5**：GB18030 覆盖其编码空间，靠「读写同一编码」保真

## 从源码构建

需要 JDK 17 与 Android SDK（`local.properties` 写 `sdk.dir`）。

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 架构

`core/` 是纯 Kotlin（禁 `androidx.compose.*` 与 `android.*`，可直接在 JVM 上跑断言）、
`data/` 管 SAF 读写与草稿、`ui/` 是 Compose 界面。「可换内核」靠包边界而不是大接口：
`ui/` 只碰 `EditorViewModel` 的状态。`core/` 那套断言不需要模拟器，CI 每次提交都会跑。

## 许可证

版权所有 (C) 2026 groundgrounder

TextNote 是自由软件：你可以依据自由软件基金会发布的 **GNU 通用公共许可证**（第 3 版或
你选择的任何更新版本）条款重新发布和/或修改它。

TextNote 的发布是希望它能有用，但不提供任何担保，甚至不包含适销性或特定用途适用性的
默示担保。详情请见 [GNU 通用公共许可证](https://www.gnu.org/licenses/gpl-3.0.html)。

你应当已随本程序收到 GNU 通用公共许可证的副本。如果没有，请见
<https://www.gnu.org/licenses/>。
