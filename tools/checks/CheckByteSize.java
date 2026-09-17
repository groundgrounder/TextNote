import com.textnote.app.core.ByteSizeKt;

/**
 * 断言 `core/ByteSize.kt` 的 formatBytes。
 *
 * 为什么值得单开一个 check：它产出的是**用户可见**的文本，而且设备脚本
 * （`verify_open_tiers.py`）断言的那串「大于 8.0 MB」正是它拼出来的。
 * 混在 Compose 文件里当 private 时，这条只能在设备上跑一遍才会红——
 * 现在秒级的 `run_checks.sh` 就能挡住。
 */
public class CheckByteSize {

    static int pass = 0, fail = 0;

    static void eq(String name, String got, String want) {
        boolean ok = want.equals(got);
        if (ok) {
            pass++;
            System.out.println("  ok   " + name + " = " + got);
        } else {
            fail++;
            System.out.println("  FAIL " + name + ": 实际=" + got + " 期望=" + want);
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 三个量级 ===");
        eq("0 字节", ByteSizeKt.formatBytes(0L), "0 B");
        eq("1023 字节仍是 B 档", ByteSizeKt.formatBytes(1023L), "1023 B");
        eq("1024 进 KB 档", ByteSizeKt.formatBytes(1024L), "1.0 KB");

        System.out.println("=== 边界：差 1 字节就跨档 ===");
        // 1023.999 KB 按一位小数进位成 1024.0——这就是「量级判断」的取舍，钉住它
        eq("1MB-1 仍是 KB 档", ByteSizeKt.formatBytes(1024L * 1024 - 1), "1024.0 KB");
        eq("1MB 进 MB 档", ByteSizeKt.formatBytes(1024L * 1024), "1.0 MB");

        System.out.println("=== 用户与设备脚本会看到的那几串 ===");
        eq("4MB 上限（3.2 里那个 4.0 MB）", ByteSizeKt.formatBytes(4L * 1024 * 1024), "4.0 MB");
        eq("8MB 文件（设备脚本断言「大于 8.0 MB」）",
                ByteSizeKt.formatBytes(8L * 1024 * 1024), "8.0 MB");
        // 小数点必须是点号：换 locale 不能漂成逗号，否则设备断言会随系统语言变红
        eq("一个小数位、点号（Locale 固定）",
                ByteSizeKt.formatBytes(3L * 1024 * 1024 + 512 * 1024), "3.5 MB");

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
