import com.textnote.app.core.EditorLimits;

public class CheckLimits {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        int highlight = EditorLimits.HIGHLIGHT_CHARS;
        int slow = EditorLimits.SLOW_CHARS;
        int open = EditorLimits.OPEN_CHARS;
        int readBytes = EditorLimits.READ_BYTES;
        int perChar = EditorLimits.MAX_BYTES_PER_CHAR;

        System.out.println("=== 常量的取值 ===");
        System.out.println("  HIGHLIGHT_CHARS = " + highlight
                + " | SLOW_CHARS = " + slow
                + " | OPEN_CHARS = " + open
                + " | READ_BYTES = " + readBytes
                + " | MAX_BYTES_PER_CHAR = " + perChar);
        System.out.println("  三档: 编辑/着色 → " + open + " 字符 → 只读浏览 → "
                + readBytes + " 字节 → 不开");

        System.out.println("=== 三档必须单调有序，否则某一档永远走不到 ===");
        check("HIGHLIGHT_CHARS <= OPEN_CHARS", highlight <= open);
        check("SLOW_CHARS <= OPEN_CHARS", slow <= open);
        check("HIGHLIGHT_CHARS > 0", highlight > 0);
        check("OPEN_CHARS > 0", open > 0);
        check("READ_BYTES > 0", readBytes > 0);
        check("MAX_BYTES_PER_CHAR 是 UTF-8 的上限 4", perChar == 4);

        System.out.println("=== 「只读」这一档必须真的存在 ===");
        // 若 READ_BYTES 小于 OPEN_CHARS，那么凡是读得进来的文件都能编辑，
        // 只读浏览的代码路径永远不会被走到——功能等于没做，而且没人会发现。
        check("OPEN_CHARS < READ_BYTES（否则只读这档是死代码）",
                (long) open < readBytes);

        System.out.println("=== 读取红线不能超过已验证的渲染范围 ===");
        // 只读渲染器的平坦性实测到 8MB（tools/kernel_bench.py 有记录）。
        // 读取红线若超过那个体积，就等于在宣称一个没验证过的能力。
        long verifiedRenderBytes = 8L * 1024 * 1024;
        check("READ_BYTES <= 8MB（已验证的渲染范围）", (long) readBytes <= verifiedRenderBytes);

        System.out.println("=== 与实测结论对齐（改了数字就要重测，别只改常量） ===");
        // 实测：60K 一次输入 101ms、188K 1.1s、900K 卡死。
        check("OPEN_CHARS 低于实测会卡死的 900K", open < 900_000);
        check("OPEN_CHARS 不低于实测勉强可用的 188K", open >= 188_000);
        check("HIGHLIGHT_CHARS 落在实测 60K 附近", highlight >= 32_000 && highlight <= 128_000);
        // 900K 的纯 ASCII 文件（900006 字节）必须小于读取红线，否则它会走进「不开」而不是「只读」
        check("900K 的 ASCII 文件走只读而不是被拒绝", 900_006L < readBytes);

        System.out.println("=== 边界算术（整数乘法不能溢出） ===");
        check("OPEN_CHARS * 4 不溢出 int", (long) open * perChar <= Integer.MAX_VALUE);
        check("最坏情况下（每个字符 4 字节）READ_BYTES 装得下 OPEN_CHARS 个字符",
                (long) open * perChar <= readBytes);

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
