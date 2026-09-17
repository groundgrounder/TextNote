package com.textnote.app.core

import java.util.Locale

/**
 * 体积按 1024 进制显示，保留一位小数——用户要的是量级判断，不是精确值。
 *
 * ## 为什么放在 core/，而不是放在显示它的那个 Composable 里
 *
 * 它是**纯函数**，而它产出的正是用户会读到的那串文本（「4.0 MB」「8.0 MB」）——设备脚本
 * `verify_open_tiers.py` 断言的就是这个串。原先它 `private` 在一个 Compose 文件里，
 * `tools/run_checks.sh` 永远够不到它，只能等有人在设备上跑一遍才发现格式写错了。
 *
 * `Locale.US` 是**刻意**的：小数点是固定的小圆点，不随用户 locale 漂——否则那条设备断言
 * 会随系统语言变红/变绿。
 */
fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    return when {
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}
