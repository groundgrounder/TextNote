import com.textnote.app.core.Diagnostic;
import com.textnote.app.core.DiagnosticEngine;
import com.textnote.app.core.DiagnosticKind;
import com.textnote.app.core.DiagnosticSeverity;
import com.textnote.app.core.LineIndex;
import com.textnote.app.core.Syntax;
import com.textnote.app.core.SyntaxRegistry;
import com.textnote.app.data.TextEncoding;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 本地单文件分析（目前只有 JSON 校验）。
 *
 * ## 断言的重点是**位置**
 *
 * 只断言「报了错」没用——诊断的价值一半在「报在哪」。所以每条非法用例都断言到**具体偏移**，
 * 而且用的是手数出来的字面量下标（不是从代码里取），这样一旦扫描器的游标逻辑漂了，
 * 断言会立刻红——扫描器最容易错的就是「多走一格、少走一格」。
 *
 * ## 两个方向都验
 *
 * 「该报的报了」只是一半。**合法的 JSON 一条都不能报**：一个会误报的校验器比没有校验器更糟，
 * 用户会先去怀疑自己的文件。所以合法用例（数字边界、转义、深嵌套、空文件）单独成组。
 */
public class CheckDiagnostics {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    static List<Diagnostic> json(String text) {
        return DiagnosticEngine.INSTANCE.analyze(text, syntax("a.json"), "a.json");
    }

    static List<Diagnostic> analyze(String text, String fileName) {
        return DiagnosticEngine.INSTANCE.analyze(text, syntax(fileName), fileName);
    }

    static Syntax syntax(String fileName) {
        return SyntaxRegistry.INSTANCE.forFileName(fileName);
    }

    /** 走仓库那条真实链路：bytes → decode（剥 BOM）→ analyze */
    static List<Diagnostic> fromBytes(byte[] bytes) {
        String text = TextEncoding.INSTANCE.decode(bytes).getText();
        return DiagnosticEngine.INSTANCE.analyze(text, syntax("a.json"), "a.json");
    }

    static byte[] b(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    static byte[] concat(byte[] a, byte[] c) {
        byte[] out = new byte[a.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(c, 0, out, a.length, c.length);
        return out;
    }

    /** 断言只有一条诊断，且种类/起点/参数都对 */
    static void checkOne(String name, List<Diagnostic> ds, DiagnosticKind kind, int start, String arg) {
        checkOne(name, ds, kind, start, -1, arg);
    }

    /**
     * 连**区间长度**一起断言。长度不是随便定的：一个字符的标记太细看不见，
     * 「缺逗号」要标在那个被顶掉的 token 上（整个 `"b"`），「非法转义」要标满 `\U` 两个字符。
     * [end] 传 -1 表示不检查终点。
     */
    static void checkOne(String name, List<Diagnostic> ds, DiagnosticKind kind, int start,
                         int end, String arg) {
        if (ds.size() != 1) {
            System.out.println("       " + name + " 期望 1 条诊断，实际 " + ds.size() + " 条");
            check(name, false);
            return;
        }
        Diagnostic d = ds.get(0);
        boolean ok = d.getKind() == kind && d.getStart() == start
                && (end < 0 || d.getEnd() == end)
                && (arg == null || arg.equals(d.getArg()));
        if (!ok) {
            System.out.println("       实际 kind=" + d.getKind() + " start=" + d.getStart()
                    + " end=" + d.getEnd() + " arg=" + d.getArg()
                    + " 期望 kind=" + kind + " start=" + start
                    + (end < 0 ? "" : " end=" + end) + " arg=" + arg);
        }
        check(name, ok);
    }

    static void inv(String label, String text, List<Diagnostic> ds) {
        boolean ok = true;
        int prevStart = -1;
        for (Diagnostic d : ds) {
            if (d.getStart() < prevStart) ok = false;
            if (d.getEnd() <= d.getStart() || d.getEnd() > text.length()) ok = false;
            if (d.getSeverity() != DiagnosticSeverity.ERROR
                    && d.getKind() != DiagnosticKind.JSON_DUPLICATE_KEY) ok = false;
            prevStart = d.getStart();
        }
        check(label + "：区间合法、按 start 递增、严重级只用两档", ok);
    }

    public static void main(String[] args) {
        gates();
        valid();
        invalid();
        duplicates();
        position();
        fuzz();
        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /**
     * 模糊测试：手写的扫描器最容易在**游标**上出错（多走一格、少走一格、走到末尾再取一次），
     * 而这类错的表现是抛异常或产出越界区间。所以这里不问「报得对不对」，只问三件硬事实：
     * **不抛异常、区间合法、不卡住**。
     *
     * 三种构造各有针对：
     * - **穷举截断**：一份合法 JSON 的每个前缀——模拟「文件正在被写」「用户刚删了一半」；
     * - **每个位置插一个字符**：模拟打错一个键；候选字符覆盖引号、括号、反斜杠、`/`、
     *   裸控制字符这些会把游标带偏的东西；
     * - **随机串**：从这些字符里随机拼，撞组合。
     */
    static void fuzz() {
        System.out.println("=== 模糊测试：不抛异常、区间合法 ===");
        String base = "{\"a\":[1,{\"b\":\"x\\u0041\"},true,null],\"c\":-1.5e3}\n";
        int[] cases = {0};

        // 1) 穷举截断
        for (int cut = 0; cut <= base.length(); cut++) {
            if (!audit(base.substring(0, cut))) cases[0]++;
        }
        check("穷举截断（" + (base.length() + 1) + " 个前缀）都不崩且区间合法", cases[0] == 0);

        // 2) 每个位置插一个字符
        String[] chars = {"\"", "'", "{", "}", "[", "]", ",", ":", "\\", "/", "*", "\t", "\u0001", "0", "-", "+"};
        int inserted = 0;
        for (int at = 0; at <= base.length(); at++) {
            for (String c : chars) {
                String s = base.substring(0, at) + c + base.substring(at);
                inserted++;
                if (!audit(s)) {
                    if (cases[0]++ == 0) System.out.println("       出事样本：" + escape(s));
                }
            }
        }
        check("每个位置插一个字符（" + inserted + " 个样本）都不崩且区间合法", cases[0] == 0);

        // 3) 随机串
        String alphabet = "{}[],:\"'\\ 0123456789abcdefnrtu-.eE+/ \t\n";
        java.util.Random rnd = new java.util.Random(20260919L);
        int randomCases = 4000;
        for (int i = 0; i < randomCases; i++) {
            int len = rnd.nextInt(48);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < len; k++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            if (!audit(sb.toString())) {
                if (cases[0]++ == 0) System.out.println("       出事样本：" + escape(sb.toString()));
            }
        }
        check("随机串（" + randomCases + " 个）都不崩且区间合法", cases[0] == 0);

        // 超长单行（压缩过的 JSON）：不该因为长度掉进别的问题
        StringBuilder longLine = new StringBuilder("[");
        for (int i = 0; i < 4000; i++) longLine.append(i).append(',');
        longLine.append("0]");
        check("超长单行数组合法", json(longLine.toString()).isEmpty());
    }

    /** 把一个样本跑一遍，检查「不抛异常 + 区间合法 + 严重级只用两档」 */
    static boolean audit(String text) {
        List<Diagnostic> ds;
        try {
            ds = json(text);
        } catch (Throwable t) {
            System.out.println("       抛异常：" + t + " 输入=" + escape(text));
            return false;
        }
        for (Diagnostic d : ds) {
            if (d.getStart() < 0 || d.getEnd() <= d.getStart() || d.getEnd() > text.length()) {
                System.out.println("       区间越界：" + d.getStart() + ".." + d.getEnd()
                        + " 长度=" + text.length() + " 输入=" + escape(text));
                return false;
            }
            if (d.getSeverity() != DiagnosticSeverity.WARNING
                    && d.getSeverity() != DiagnosticSeverity.ERROR) {
                return false;
            }
        }
        return true;
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) sb.append(String.format("\\u%04X", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    static void gates() {
        System.out.println("=== 闸门：什么情况下不分析 ===");
        check("空文本不报", json("").isEmpty());
        check("空文件（新建的 .json）不报", json("").isEmpty());
        check("纯空白不报", json("   \n\t\n").isEmpty());
        check("非 JSON 语法不分析", DiagnosticEngine.INSTANCE
                .analyze("fun main() {}", SyntaxRegistry.INSTANCE.forFileName("a.kt"), "a.kt").isEmpty());
        check("纯文本不分析", DiagnosticEngine.INSTANCE
                .analyze("{ 坏 ", SyntaxRegistry.INSTANCE.forFileName("a.txt"), "a.txt").isEmpty());

        // 带 BOM 的 JSON：解码层会**剥掉** BOM（`TextEncoding.decode` 的约定），所以扫描器
        // 看不到开头那个零宽的 U+FEFF，也就不会把好文件报成坏文件。
        // 这条断言同时钉住两件事：扫描器不误报、以及「剥 BOM」这个跨层约定没被改掉。
        check("UTF-8 + BOM 的 JSON 不报错",
                fromBytes(concat(b(0xEF, 0xBB, 0xBF), "{\"a\":1}".getBytes(StandardCharsets.UTF_8))).isEmpty());
        check("UTF-16LE + BOM 的 JSON 不报错",
                fromBytes(concat(b(0xFF, 0xFE), "{\"名字\":\"值\"}".getBytes(StandardCharsets.UTF_16LE))).isEmpty());

        // 体积闸门：与着色同一档
        StringBuilder huge = new StringBuilder("{");
        while (huge.length() <= DiagnosticEngine.MAX_CHARS) huge.append(' ');
        huge.append("坏}");
        check("超过分析上限就不分析",
                DiagnosticEngine.INSTANCE.analyze(huge.toString(), syntax("a.json"), "a.json").isEmpty());

        // .jsonc / .json5 是宽松变体：注释与尾随逗号都合法
        check(".jsonc 允许注释", analyze("{\n  // 注释\n  \"a\": 1\n}\n", "a.jsonc").isEmpty());
        check(".jsonc 允许尾随逗号", analyze("{\n  \"a\": [1, 2,],\n}\n", "a.jsonc").isEmpty());
        check(".json5 允许块注释", analyze("{ /* c */ \"a\": 1 }", "a.json5").isEmpty());
        check("同样内容在严格 .json 里报错", !json("{\n  // 注释\n  \"a\": 1\n}\n").isEmpty());
    }

    static void valid() {
        System.out.println("=== 合法 JSON：一条都不许报 ===");
        String[] cases = {
                "{}",
                "[]",
                "{\"a\":1}",
                "[1,2,3]",
                "{\"a\":{\"b\":[1,{\"c\":null}]}}",
                "  {\n  \"a\" : 1 ,\n  \"b\" : [ true , false ]\n}\n  ",
                "{\"n\":0}",
                "{\"n\":-0}",
                "{\"n\":-1.5}",
                "{\"n\":1e10}",
                "{\"n\":1E+2}",
                "{\"n\":-0.5e-3}",
                "{\"s\":\"\"}",
                "{\"s\":\"a\\\"b\"}",
                "{\"s\":\"a\\\\b\"}",
                "{\"s\":\"a\\/b\"}",
                "{\"s\":\"\\b\\f\\n\\r\\t\"}",
                "{\"s\":\"\\u0041\\u00e9\"}",
                "{\"中文键\":\"值\"}",
                "{\"a\":1,\"b\":2,\"c\":3}",
        };
        boolean allClean = true;
        for (String c : cases) {
            List<Diagnostic> ds = json(c);
            if (!ds.isEmpty()) {
                System.out.println("       误报：" + c + " -> " + ds.get(0).getKind()
                        + "@" + ds.get(0).getStart());
                allClean = false;
            }
        }
        check("合法用例全部无诊断（" + cases.length + " 例）", allClean);

        // 深的但不超限：合法，且不该触发「嵌套太深」
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 50; i++) deep.append('[');
        for (int i = 0; i < 50; i++) deep.append(']');
        check("50 层嵌套合法且不报", json(deep.toString()).isEmpty());
    }

    static void invalid() {
        System.out.println("=== 非法 JSON：种类与偏移都要准 ===");
        // 键没加引号
        checkOne("未加引号的键", json("{a:1}"),
                DiagnosticKind.JSON_EXPECTED_VALUE, 1, "a");
        checkOne("单引号键", json("{'a':1}"),
                DiagnosticKind.JSON_EXPECTED_VALUE, 1, "'");
        // 键后面缺冒号
        checkOne("缺冒号", json("{\"a\" 1}"), DiagnosticKind.JSON_EXPECTED_COLON, 5, null);
        // 成员之间缺逗号：标在**下一个**成员上（整个 `"b"`，不是它前面一个字符）
        checkOne("缺逗号", json("{\"a\":1 \"b\":2}"),
                DiagnosticKind.JSON_EXPECTED_COMMA, 7, 10, null);
        checkOne("数组缺逗号", json("[1 2]"), DiagnosticKind.JSON_EXPECTED_COMMA, 3, 4, null);
        // 尾随逗号：指向逗号本身
        checkOne("对象尾随逗号", json("{\"a\":1,}"), DiagnosticKind.JSON_TRAILING_COMMA, 6, null);
        checkOne("数组尾随逗号", json("[1,]"), DiagnosticKind.JSON_TRAILING_COMMA, 2, null);
        // 括号没闭合：指向开括号
        checkOne("对象没闭合（文件被截断）", json("{\"a\":1"),
                DiagnosticKind.JSON_UNCLOSED_BRACKET, 0, "{");
        checkOne("数组没闭合", json("[1"), DiagnosticKind.JSON_UNCLOSED_BRACKET, 0, "[");
        checkOne("只有开括号", json("{"), DiagnosticKind.JSON_UNCLOSED_BRACKET, 0, "{");
        // 字符串
        checkOne("字符串没闭合", json("{\"a\":\"b}"),
                DiagnosticKind.JSON_UNCLOSED_STRING, 5, null);
        checkOne("字符串里直接换行", json("{\"a\":\"b\nc\"}"),
                DiagnosticKind.JSON_UNCLOSED_STRING, 5, null);
        // 转义：标满整个转义序列（两个字符的 `\U`、六位的 unicode 转义），一个字符太细看不见。
        // ⚠️ 这里绝不能写出 "反斜杠 + u" 那个字面序列：Java 会在**词法分析之前**把源码里的
        // unicode 转义预处理掉，注释里写一样会报「非法的 Unicode 转义」。
        checkOne("Windows 路径式非法转义", json("{\"a\":\"C:\\Users\"}"),
                DiagnosticKind.JSON_BAD_ESCAPE, 8, 10, "\\U");
        checkOne("反斜杠 u 后面不是四位十六进制", json("{\"a\":\"\\u12g4\"}"),
                DiagnosticKind.JSON_BAD_ESCAPE, 6, 12, "\\u");
        checkOne("字符串里的裸制表符", json("{\"a\":\"b\tc\"}"),
                DiagnosticKind.JSON_BAD_ESCAPE, 7, 8, "\\t");
        // 数字：写宽了会「校验通过但别的程序读不了」
        checkOne("前导零", json("{\"a\":01}"), DiagnosticKind.JSON_EXPECTED_VALUE, 5, "01");
        checkOne("小数点前没数字", json("{\"a\":.5}"), DiagnosticKind.JSON_EXPECTED_VALUE, 5, ".5");
        checkOne("小数点后没数字", json("{\"a\":1.}"), DiagnosticKind.JSON_EXPECTED_VALUE, 5, "1.");
        checkOne("NaN 不是 JSON", json("{\"a\":NaN}"), DiagnosticKind.JSON_EXPECTED_VALUE, 5, "NaN");
        checkOne("十六进制不是 JSON", json("{\"a\":0x1f}"),
                DiagnosticKind.JSON_EXPECTED_VALUE, 5, "0x1f");
        // 顶层
        // 顶层两个值：`}` 在下标 6，空格 7，所以第二个值从 8 开始
        checkOne("顶层两个值", json("{\"a\":1} {\"b\":2}"),
                DiagnosticKind.JSON_EXPECTED_END, 8, "{");
        checkOne("顶层后面跟注释", json("{\"a\":1} // x"),
                DiagnosticKind.JSON_EXPECTED_END, 8, "/");
        // 嵌套太深：宁可报一句，也不要递归到栈溢出。
        // 这里刻意用一个远超上限的深度（1000，上限是 200 量级）——它同时是「递归有界」的守卫：
        // 若哪天有人把上限拿掉，这份断言会以「栈溢出」的形式红掉，而不是留下一个打开文件即崩的 bug。
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 1000; i++) deep.append('[');
        check("嵌套 1000 层报「太深」而不是崩",
                json(deep.toString()).size() == 1
                        && json(deep.toString()).get(0).getKind() == DiagnosticKind.JSON_TOO_DEEP);
    }

    static void duplicates() {
        System.out.println("=== 重复的键：合法但要提醒 ===");
        // 键 0 从下标 7 开始（`{"a":1,` 之后）
        checkOne("重复键报警告", json("{\"a\":1,\"a\":2}"),
                DiagnosticKind.JSON_DUPLICATE_KEY, 7, "a");
        check("重复键是警告而不是错误",
                json("{\"a\":1,\"a\":2}").get(0).getSeverity() == DiagnosticSeverity.WARNING);
        check("不同层级的同名键不算重复", json("{\"a\":1,\"b\":{\"a\":2}}").isEmpty());
        check("三次出现只提醒后两次", json("{\"a\":1,\"a\":2,\"a\":3}").size() == 2);
        // 结构坏了的时候只说结构问题：修好再看重复
        List<Diagnostic> both = json("{\"a\":1,\"a\":2,}");
        check("既重复又缺括号时只报语法错误",
                both.size() == 1 && both.get(0).getKind() == DiagnosticKind.JSON_TRAILING_COMMA);
    }

    static void position() {
        System.out.println("=== 偏移 → 行列（界面靠它说「第 N 行」）===");
        String text = "{\n  \"a\": 1\n  \"b\": 2\n}\n";
        List<Diagnostic> ds = json(text);
        // 缺逗号标在整个 `"b"` 上：13..16
        checkOne("多行文本里的缺逗号", ds, DiagnosticKind.JSON_EXPECTED_COMMA, 13, 16, null);
        if (!ds.isEmpty()) {
            LineIndex index = LineIndex.Companion.of(text);
            int off = ds.get(0).getStart();
            check("落在第 3 行（下标 2）", index.lineOf(off) == 2);
            check("列号指向行首的引号（下标 2）", index.columnOf(off) == 2);
        }
        // 不变量：区间合法、有序、严重级只有两档
        inv("合法 JSON", "{\"a\":1}", json("{\"a\":1}"));
        inv("非法 JSON", "{\"a\":1,\"a\":2", json("{\"a\":1,\"a\":2"));
        inv("重复键", "{\"a\":1,\"a\":2,\"a\":3}", json("{\"a\":1,\"a\":2,\"a\":3}"));
    }
}
