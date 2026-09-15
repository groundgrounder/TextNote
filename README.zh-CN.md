# TextNote

轻量 Android 纯文本编辑器 · 文档保真 · Kotlin + Jetpack Compose + Material 3

[English](README.md) | **简体中文**

[![Android CI](https://github.com/groundgrounder/TextNote/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/groundgrounder/TextNote/actions/workflows/android.yml)

<p>
  <img src="docs/screenshots/home.png" width="30%" alt="最近打开">
  <img src="docs/screenshots/editor.png" width="30%" alt="编辑界面">
  <img src="docs/screenshots/settings.png" width="30%" alt="设置">
</p>

TextNote 是一个把**文档保真**放在第一位的纯文本编辑器：打开看一眼再保存，文件不该有任何字节变化。

## 功能

- **读写保真**：编码（UTF-8 / GB18030 / BOM）与行尾（LF / CRLF / CR）读时探测、写时沿用，混合会提示
- **编辑**：撤销/重做（连续输入合并）、查找替换（正则 / 大小写 / 整词 / `$1` 模板）、`Ctrl+Z` 等快捷键
- **语法着色**：15 种语言，按扩展名识别，也可手动指定并**按文件记住**
- **大文件**：超 20 万字符转**只读浏览**；超 4MB 不打开，不会把内存拖垮
- **不丢内容**：切到后台立刻落盘草稿；草稿与文件不一致才问「恢复 / 丢弃」；外部改动只提示不套用
- **外观**：跟随系统 / 浅色 / 深色，支持动态取色；字体、字号、行距可调；英文 · 简中 · 繁中

## 下载

到 [Releases](https://github.com/groundgrounder/TextNote/releases/latest) 下载 `TextNote-*.apk`
安装即可（首次需允许「安装未知来源应用」）。要求 Android 8.0 及以上。

## 用法

- 点「打开文件」走系统文件选择器，或点「新建文件」从头写
- 也可以在文件管理器里直接选 TextNote 打开：`.txt` / `.md` / `.log` / `.json`
- 不申请任何存储权限——文件读写全部经由系统文件选择器的授权

## 已知边界（刻意的取舍）

- 超 20 万字符只能读不能改（自绘内核是 sora-editor 量级，不做）；不做 Markdown 预览
- 不捆绑字体，只用系统三族；列号按 UTF-16 码元计，emoji 占 2 列
- 不单独识别 Big5：GB18030 覆盖其编码空间，靠「读写同一编码」保真

## 从源码构建

需要 JDK 17 与 Android SDK（`local.properties` 写 `sdk.dir`）。

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 架构

`core/` 纯 Kotlin（禁 `androidx.compose.*` 与 `android.*`，可直接 JVM 实测）、`data/` SAF 读写与
草稿、`ui/` Compose 界面。「可换内核」靠包边界而不是大接口：`ui/` 只碰 `EditorViewModel` 的状态。

## 许可证

版权所有 (C) 2026 groundgrounder

TextNote 是自由软件：你可以依据自由软件基金会发布的 **GNU 通用公共许可证**（第 3 版或
你选择的任何更新版本）条款重新发布和/或修改它。

TextNote 的发布是希望它能有用，但不提供任何担保，甚至不包含适销性或特定用途适用性的
默示担保。详情请见 [GNU 通用公共许可证](https://www.gnu.org/licenses/gpl-3.0.html)。

你应当已随本程序收到 GNU 通用公共许可证的副本。如果没有，请见
<https://www.gnu.org/licenses/>。
