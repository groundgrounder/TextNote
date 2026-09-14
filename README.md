# TextNote

Android 纯文本编辑器。核心约定是**文档保真**：打开看一眼再保存，文件不该有任何字节变化。
v0.1.0，minSdk 26 / target 35。

## 功能

- **读写保真**：编码（UTF-8 / GB18030 / BOM）与行尾（LF / CRLF / CR）读时探测、写时沿用，混合会提示
- **编辑**：撤销/重做（连续输入合并）、查找替换（正则 / 大小写 / 整词 / `$1` 模板）、`Ctrl+Z` 等快捷键
- **语法着色**：15 种语言，按扩展名识别，也可手动指定并**按文件记住**
- **大文件**：超 20 万字符转**只读浏览**（8MB 仍 30ms/帧）；超 4MB 不打开——上限在 `core/EditorLimits.kt`
- **不丢内容**：`onStop` 同步落盘草稿；草稿与文件不一致才问「恢复 / 丢弃」；外部改动只提示不套用
- **外观**：主题 / 动态取色 / 字体 / 字号 / 行距；英文 · 简中 · 繁中

## 构建

需要 JDK 17 与 Android SDK（`local.properties` 写 `sdk.dir`）。

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 测试

```bash
tools/run_checks.sh                        # 372 条纯逻辑断言，不用设备，秒级
python3 tools/device/make_fixtures.py      # 上机验证：先造测试数据
python3 tools/device/verify_open_tiers.py  # 之后跑任意 verify_*.py
```

脚本清单、各自守什么、踩过的坑都在 `tools/README.md`。

## 架构

`core/` 纯 Kotlin（禁 `androidx.compose.*` 与 `android.*`，可直接 JVM 实测）、`data/` SAF 读写与
草稿、`ui/` Compose 界面。「可换内核」靠包边界而不是大接口：`ui/` 只碰 `EditorViewModel` 的状态。

## 已知边界（刻意的取舍）

- 超 20 万字符只能读不能改（自绘内核是 sora-editor 量级，不做）；不做 Markdown 预览
- 不捆绑字体，只用系统三族；列号按 UTF-16 码元计，emoji 占 2 列
- 不单独识别 Big5：GB18030 覆盖其编码空间，靠「读写同一编码」保真

## 签名与发布

Release 用 `keystore/textnote.jks` 签名，口令在根目录 `keystore.properties`（两者都不入库）。
缺这个文件时构建仍会成功，只是产出未签名的包。

推 `v*` tag 触发 CI 出包并发布 Release；CI 从 `SIGNING_KEYSTORE_BASE64` 与
`SIGNING_KEYSTORE_PASSWORD` 还原密钥，并校验产物签名没有退回 debug 密钥。
