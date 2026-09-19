# TextNote

轻量 Android 纯文本编辑器 · 文档保真

[English](README.md) | **简体中文**

[![Android CI](https://github.com/groundgrounder/TextNote/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/groundgrounder/TextNote/actions/workflows/android.yml)

<p>
  <img src="docs/screenshots/home.png" width="30%" alt="最近打开">
  <img src="docs/screenshots/editor.png" width="30%" alt="编辑界面">
  <img src="docs/screenshots/settings.png" width="30%" alt="设置">
</p>

打开一个文件、改两个字、保存——这个过程中文件不该有任何变化。TextNote 就是为这件事做的：
UTF-8、GB18030、带 BOM 的 UTF-16，LF、CRLF、CR 换行符，怎么读进来就怎么写回去，一个字节都不动。

## 功能

**写**

- 撤销、重做、查找替换（正则、区分大小写、整词、`$1` 替换模板）
- 插上键盘可以用 `Ctrl+Z` 撤销，`Ctrl+Shift+Z` 或 `Ctrl+Y` 重做
- 底部一直显示这份文件的底细：编码、换行符、语法、行列号、字数与行数

**看**

- 34 种语言的语法着色，认文件名——`Makefile`、`Dockerfile`、`CMakeLists.txt`、`.gitignore`
  都认得；也能手动指定，而且记得住，同一个文件下次打开还是你选的那门
- 行号栏；`.diff` / `.patch` / `.rej` 按行首的 `+` `-` 上色，改了什么一眼看得到

**文件安全**

- 一份文件里混着几种换行符时会告诉你——因为不管按哪一种保存，都会改动其中一部分行
- 切到后台立刻存草稿；只有草稿和文件**确实不一样**才问你要不要恢复，绝不替你决定。
  草稿留 30 天，快到期时首页会提醒
- 文件被别的应用改过：告诉你，但不动你正在看的内容
- 不是文本的文件（一堆 NUL 字节和控制符）会说清原因再拒收，不会甩给你一屏天书；
  比如不带 BOM 的 UTF-16 会被认成二进制，在别的工具里转成 UTF-8 就能打开

**大文件**

- 超过 6.4 万字符：不再着色，并提示输入可能变慢
- 超过 20 万字符：只能看不能改，但查找和跳转照常
- 超过 4 MB：不打开——这个体积读进内存会把应用撑爆，不是体验问题

**JSON**

- 边写边校，全部在设备上完成、不联网：第一处错误直接标在正文上，状态栏显示有几处问题，
  点一下跳过去并说清错在哪
- `.jsonc` / `.json5` 按宽松规则校：注释和尾随逗号都允许

**外观**

- 跟随系统 / 浅色 / 深色，支持动态取色（Android 12 及以上）
- 字体、字号、行距都能调，长行折行也可以关（关掉就横向滚动）
- 界面语言：跟随系统 / English / 简体中文 / 繁體中文 / Latina
- 分屏或桌面窗口里可以同时开两份文档、各改各的；最近列表长按可以「在新窗口打开」

## 下载

到 [Releases](https://github.com/groundgrounder/TextNote/releases/latest) 下载 `TextNote-vX.Y.Z.apk`
安装（第一次装需要允许「安装未知来源应用」）。Android 8.0 及以上。

## 用法

- 「打开文件」走系统选择器，「新建文件」从头写
- 在文件管理器里选 TextNote 打开；从别的应用里把一份文本文件**分享**过来也一样
- 不申请任何存储权限——读写都走系统选择器给你的那一次授权

## 自己编译

需要 JDK 17 和 Android SDK（在 `local.properties` 里写 `sdk.dir`）：

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 许可证

版权所有 (C) 2026 groundgrounder

TextNote 是自由软件：你可以依据自由软件基金会发布的 **GNU 通用公共许可证**（第 3 版或
你选择的任何更新版本）条款重新发布和/或修改它。

TextNote 的发布是希望它能有用，但不提供任何担保，甚至不包含适销性或特定用途适用性的
默示担保。详情请见 [GNU 通用公共许可证](https://www.gnu.org/licenses/gpl-3.0.html)。

你应当已随本程序收到 GNU 通用公共许可证的副本。如果没有，请见
<https://www.gnu.org/licenses/>。
