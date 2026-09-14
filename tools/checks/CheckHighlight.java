import com.textnote.app.core.Highlighter;
import com.textnote.app.core.LineIndex;
import com.textnote.app.core.Syntax;
import com.textnote.app.core.SyntaxRegistry;
import com.textnote.app.core.HighlightToken;
import com.textnote.app.core.TokenKind;

import java.util.ArrayList;
import java.util.List;

public class CheckHighlight {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    static String span(String text, HighlightToken t) { return text.substring(t.getStart(), t.getEnd()); }

    /** 取所有落在某段文本上的 token 的种类，去重后返回 */
    static List<TokenKind> kindsAt(String text, List<HighlightToken> toks, String substring) {
        int idx = text.indexOf(substring);
        if (idx < 0) throw new RuntimeException("测试文本里找不到片段: " + substring);
        List<TokenKind> r = new ArrayList<>();
        for (HighlightToken t : toks) {
            if (t.getStart() <= idx && idx < t.getEnd()) r.add(t.getKind());
        }
        return r;
    }

    static boolean hasKind(List<TokenKind> kinds, TokenKind k) { return kinds.contains(k); }

    static List<HighlightToken> run(String text, Syntax s) {
        return Highlighter.INSTANCE.highlight(text, LineIndex.Companion.of(text), s);
    }

    /** 核心不变量：token 必须按 start 递增且互不重叠 */
    static void checkOrder(String label, List<HighlightToken> toks, String text) {
        int prevEnd = -1;
        boolean ok = true;
        for (HighlightToken t : toks) {
            if (t.getStart() < prevEnd) { ok = false; break; }
            if (t.getEnd() <= t.getStart() || t.getEnd() > text.length()) { ok = false; break; }
            if (prevEnd >= 0 && t.getStart() < prevEnd) { ok = false; break; }
            prevEnd = t.getEnd();
        }
        check(label + " token 有序且不重叠", ok);
    }

    public static void main(String[] args) {
        System.out.println("=== 语法注册 ===");
        check("a.kt -> Kotlin", SyntaxRegistry.INSTANCE.forFileName("a.kt").getId().equals("kotlin"));
        check("a.js -> JavaScript", SyntaxRegistry.INSTANCE.forFileName("a.js").getId().equals("javascript"));
        check("a.tsx -> JavaScript（优先于 HTML）", SyntaxRegistry.INSTANCE.forFileName("a.tsx").getId().equals("javascript"));
        check("a.html -> HTML", SyntaxRegistry.INSTANCE.forFileName("a.html").getId().equals("html"));
        check("a.css -> CSS", SyntaxRegistry.INSTANCE.forFileName("a.css").getId().equals("css"));
        check("a.json -> JSON", SyntaxRegistry.INSTANCE.forFileName("a.json").getId().equals("json"));
        check("a.py -> Python", SyntaxRegistry.INSTANCE.forFileName("a.py").getId().equals("python"));
        check("a.md -> Markdown", SyntaxRegistry.INSTANCE.forFileName("a.md").getId().equals("markdown"));
        check("a.log -> 纯文本", SyntaxRegistry.INSTANCE.forFileName("a.log").getId().equals("txt"));
        check("无扩展名 -> 纯文本", SyntaxRegistry.INSTANCE.forFileName("Makefile").getId().equals("txt"));
        check("未知扩展名 -> 纯文本", SyntaxRegistry.INSTANCE.forFileName("a.weird").getId().equals("txt"));
        check("大写扩展名也能认", SyntaxRegistry.INSTANCE.forFileName("A.KT").getId().equals("kotlin"));

        Syntax kt = SyntaxRegistry.INSTANCE.forFileName("a.kt");
        Syntax js = SyntaxRegistry.INSTANCE.forFileName("a.js");
        Syntax py = SyntaxRegistry.INSTANCE.forFileName("a.py");
        Syntax md = SyntaxRegistry.INSTANCE.forFileName("a.md");
        Syntax html = SyntaxRegistry.INSTANCE.forFileName("a.html");
        Syntax css = SyntaxRegistry.INSTANCE.forFileName("a.css");
        Syntax json = SyntaxRegistry.INSTANCE.forFileName("a.json");

        System.out.println("=== Kotlin ===");
        String k = "fun main(args: Array<String>) {\n"
                + "    // 注释\n"
                + "    val s = \"hello\\nworld\"\n"
                + "    val n = 0xFF + 3.14e2 + 42L\n"
                + "    /* 块注释\n"
                + "       第二行 */\n"
                + "    val c = 'x'\n"
                + "    val t = \"\"\"raw\n"
                + "raw 第二行\"\"\"\n"
                + "}\n";
        List<HighlightToken> tk = run(k, kt);
        checkOrder("Kotlin", tk, k);
        check("fun 是关键字", hasKind(kindsAt(k, tk, "fun"), TokenKind.KEYWORD));
        check("Array 是类型", hasKind(kindsAt(k, tk, "Array"), TokenKind.TYPE));
        check("main 是函数名", hasKind(kindsAt(k, tk, "main"), TokenKind.FUNCTION));
        check("行注释被识别", hasKind(kindsAt(k, tk, "// 注释"), TokenKind.COMMENT));
        check("字符串里的 \\n 是 ESCAPE", hasKind(kindsAt(k, tk, "\\nworld"), TokenKind.ESCAPE));
        check("字符串本体是 STRING", hasKind(kindsAt(k, tk, "hello"), TokenKind.STRING));
        check("十六进制是 NUMBER", hasKind(kindsAt(k, tk, "0xFF"), TokenKind.NUMBER));
        check("科学计数法是 NUMBER", hasKind(kindsAt(k, tk, "3.14e2"), TokenKind.NUMBER));
        check("带后缀的是 NUMBER", hasKind(kindsAt(k, tk, "42L"), TokenKind.NUMBER));
        check("块注释第一行是 COMMENT", hasKind(kindsAt(k, tk, "/* 块注释"), TokenKind.COMMENT));
        check("块注释**续行**仍是 COMMENT", hasKind(kindsAt(k, tk, "       第二行 */"), TokenKind.COMMENT));
        check("块注释结束后回到普通代码（val 是关键字）", hasKind(kindsAt(k, tk, "val c"), TokenKind.KEYWORD));
        check("字符字面量是 STRING", hasKind(kindsAt(k, tk, "'x'"), TokenKind.STRING));
        check("三引号第一行是 STRING", hasKind(kindsAt(k, tk, "\"\"\"raw"), TokenKind.STRING));
        check("三引号**续行**仍是 STRING", hasKind(kindsAt(k, tk, "raw 第二行"), TokenKind.STRING));
        check("三引号闭合后回到代码（val t 之后）", hasKind(kindsAt(k, tk, "val t"), TokenKind.KEYWORD));

        System.out.println("=== Python ===");
        String p = "# 头部注释\nx = 1\n\"\"\"文档\n第二行\"\"\"\ny = 2\n";
        List<HighlightToken> tp = run(p, py);
        checkOrder("Python", tp, p);
        check("# 注释被识别", hasKind(kindsAt(p, tp, "# 头部注释"), TokenKind.COMMENT));
        check("三引号第一行 STRING", hasKind(kindsAt(p, tp, "\"\"\"文档"), TokenKind.STRING));
        check("三引号续行 STRING", hasKind(kindsAt(p, tp, "第二行"), TokenKind.STRING));
        check("三引号闭合后 y 不是 STRING", !hasKind(kindsAt(p, tp, "y = 2"), TokenKind.STRING));

        System.out.println("=== JavaScript ===");
        String j = "const a = `模板\n第二行 ${x}`;\nconsole.log(\"hi\");\n";
        List<HighlightToken> tj = run(j, js);
        checkOrder("JS", tj, j);
        check("const 是关键字", hasKind(kindsAt(j, tj, "const"), TokenKind.KEYWORD));
        check("反引号第一行 STRING", hasKind(kindsAt(j, tj, "`模板"), TokenKind.STRING));
        check("反引号**续行** STRING", hasKind(kindsAt(j, tj, "第二行"), TokenKind.STRING));
        check("反引号闭合后分号不是 STRING", !hasKind(kindsAt(j, tj, ");"), TokenKind.STRING));
        check("console 是内置量", hasKind(kindsAt(j, tj, "console"), TokenKind.BUILTIN));
        check("log 是函数名", hasKind(kindsAt(j, tj, "log"), TokenKind.FUNCTION));

        System.out.println("=== JSON ===");
        String js2 = "{\n  \"name\": \"Tom\",\n  \"n\": 12\n}\n";
        List<HighlightToken> tjs = run(js2, json);
        checkOrder("JSON", tjs, js2);
        check("键是 PROPERTY", hasKind(kindsAt(js2, tjs, "\"name\""), TokenKind.PROPERTY));
        check("值不是 PROPERTY", !hasKind(kindsAt(js2, tjs, "\"Tom\""), TokenKind.PROPERTY));
        check("值是 STRING", hasKind(kindsAt(js2, tjs, "Tom"), TokenKind.STRING));
        check("数字是 NUMBER", hasKind(kindsAt(js2, tjs, "12"), TokenKind.NUMBER));

        System.out.println("=== HTML ===");
        String h = "<div class=\"box\" id='y'>text</div>\n<!-- 注释\n延续 --><p>x</p>\n";
        List<HighlightToken> th = run(h, html);
        checkOrder("HTML", th, h);
        check("div 是 TAG", hasKind(kindsAt(h, th, "div"), TokenKind.TAG));
        check("class 是 ATTRIBUTE", hasKind(kindsAt(h, th, "class"), TokenKind.ATTRIBUTE));
        check("属性值是 STRING", hasKind(kindsAt(h, th, "\"box\""), TokenKind.STRING));
        check("单引号属性值也是 STRING", hasKind(kindsAt(h, th, "'y'"), TokenKind.STRING));
        check("HTML 注释第一行 COMMENT", hasKind(kindsAt(h, th, "<!--"), TokenKind.COMMENT));
        check("HTML 注释续行 COMMENT", hasKind(kindsAt(h, th, "延续 -->"), TokenKind.COMMENT));
        check("注释后 <p> 仍是 TAG", hasKind(kindsAt(h, th, "p>"), TokenKind.TAG));

        System.out.println("=== Markdown ===");
        String m = "# 标题\n正文 `code` 结束\n\n- 列表项\n\n```\nfenced code\n```\n末尾\n";
        List<HighlightToken> tm = run(m, md);
        checkOrder("Markdown", tm, m);
        check("# 是 PUNCTUATION", hasKind(kindsAt(m, tm, "#"), TokenKind.PUNCTUATION));
        check("标题正文是 HEADING", hasKind(kindsAt(m, tm, "标题"), TokenKind.HEADING));
        check("行内代码是 CODE", hasKind(kindsAt(m, tm, "`code`"), TokenKind.CODE));
        check("列表标记是 PUNCTUATION", hasKind(kindsAt(m, tm, "- "), TokenKind.PUNCTUATION));
        check("围栏第一行 CODE", hasKind(kindsAt(m, tm, "```"), TokenKind.CODE));
        check("围栏内 CODE", hasKind(kindsAt(m, tm, "fenced code"), TokenKind.CODE));
        check("围栏关闭后回到普通（末尾不着色）", kindsAt(m, tm, "末尾").isEmpty());
        String m2 = "**粗体** 和 [文字](http://a.b) 结束\n";
        List<HighlightToken> tm2 = run(m2, md);
        checkOrder("Markdown 行内", tm2, m2);
        check("**粗体** 是 EMPHASIS", hasKind(kindsAt(m2, tm2, "**"), TokenKind.EMPHASIS));
        check("链接 URL 是 LINK", hasKind(kindsAt(m2, tm2, "http://a.b"), TokenKind.LINK));
        check("链接**文字**不是 LINK", !hasKind(kindsAt(m2, tm2, "文字"), TokenKind.LINK));

        System.out.println("=== CSS ===");
        String c = ".box { color: red; }\n";
        List<HighlightToken> tc = run(c, css);
        checkOrder("CSS", tc, c);
        check(".box 选择器被识别为 TAG", hasKind(kindsAt(c, tc, ".box"), TokenKind.TAG));
        String c2 = "@media screen { color: red; }\n";
        List<HighlightToken> tc2 = run(c2, css);
        check("@media 是 KEYWORD 而不是选择器", hasKind(kindsAt(c2, tc2, "@media"), TokenKind.KEYWORD));
        check("color 是声明名 PROPERTY", hasKind(kindsAt(c, tc, "color"), TokenKind.PROPERTY));

        System.out.println("=== 边界情况 ===");
        check("空文本不产出 token", run("", kt).isEmpty());
        check("纯文本语法无 token", run("fun main() {}", Syntax.Companion.getPLAIN()).isEmpty());
        String none = "没有换行符\n";
        check("无换行也能量出最后一行的 token", !run("val x = \"a\"", kt).isEmpty());
        List<HighlightToken> tUn = run("val s = \"未闭合", kt);
        checkOrder("未闭合字符串", tUn, "val s = \"未闭合");
        check("未闭合字符串延伸到行尾", hasKind(kindsAt("val s = \"未闭合", tUn, "未闭合"), TokenKind.STRING));
        String crlf = "fun a() {}\r\n";
        check("CR 不会被算进任何 token",
                run(LineIndex.Companion.of(crlf) != null ? normalize(crlf) : crlf, kt)
                        .stream().noneMatch(t -> normalize(crlf).substring(t.getStart(), t.getEnd()).contains("\r")));

        System.out.println("=== 按行 API（只读渲染器用的那条路） ===");
        checkPerLine(kt);

        System.out.println("=== 性能基准（用于定降级阈值） ===");
        benchmark("Kotlin 源码", kt);
        benchmark("Python 源码", py);
        benchmark("Markdown", md);

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /**
     * lineStates + highlightLine 这条按行 API 之前一条断言都没有——而它正是「大文件只读浏览」
     * 的实现基础（整篇 token 不常驻，任意一行单独着色）。这里守住三件事：
     * 行首状态跨行传递正确、单行结果与整篇一致、以及超长单行的 maxChars 截断真的生效。
     */
    static void checkPerLine(Syntax kt) {
        // 块注释跨三行：中间那行的行首状态必须是「在块注释里」
        String text = "val a = 1\n/* comment\n still comment\n*/\nval b = 2";
        LineIndex idx = LineIndex.Companion.of(text);
        int[] states = Highlighter.INSTANCE.lineStates(text, idx, kt);
        check("lineStates 行数与行数一致", states.length == idx.getLineCount());

        List<HighlightToken> whole = Highlighter.INSTANCE.highlight(text, idx, kt);
        // 这条专门守整型溢出：maxChars 的默认值就是 Int.MAX_VALUE，而 start 不为 0 时
        // `start + maxChars` 会溢出成负数，使得「除第一行外全都不着色」而毫无报错。
        List<HighlightToken> l1 = Highlighter.INSTANCE.highlightLine(text, idx, kt, 1, states[1], Integer.MAX_VALUE);
        check("上限放开时非首行也照常着色（start+maxChars 不能溢出）", !l1.isEmpty());
        check("第 1 行（块注释起点）是注释",
                hasKind(kindsAt(text, l1, "/* comment"), TokenKind.COMMENT));
        List<HighlightToken> l2 = Highlighter.INSTANCE.highlightLine(text, idx, kt, 2, states[2], Integer.MAX_VALUE);
        check("第 2 行（块注释续行）也是注释",
                hasKind(kindsAt(text, l2, "still comment"), TokenKind.COMMENT));
        check("第 4 行（注释已闭合）不再是注释",
                !hasKind(kindsAt(text, Highlighter.INSTANCE.highlightLine(text, idx, kt, 4, states[4], Integer.MAX_VALUE),
                        "val b"), TokenKind.COMMENT));

        // 按行切出来的 token 必须与整篇一致（否则只读模式的着色会和编辑模式对不上）
        int lineStart = idx.lineStart(2);
        int lineEnd = idx.lineEnd(2);
        List<HighlightToken> wholeOnLine2 = new ArrayList<>();
        for (HighlightToken t : whole) {
            if (t.getStart() >= lineStart && t.getEnd() <= lineEnd) wholeOnLine2.add(t);
        }
        check("第 2 行按行着色 = 整篇里落在该行的 token",
                wholeOnLine2.size() == l2.size() && wholeOnLine2.equals(l2));

        // maxChars：超长单行只分词看得见的那一段
        String longLine = "val s = \"" + "x".repeat(5000) + "\" // tail";
        LineIndex lIdx = LineIndex.Companion.of(longLine);
        List<HighlightToken> capped = Highlighter.INSTANCE.highlightLine(longLine, lIdx, kt, 0, 0, 100);
        check("maxChars=100 时没有 token 越过该上限",
                capped.stream().allMatch(t -> t.getEnd() <= 100));
        check("maxChars=100 时仍标出了开头的字符串",
                hasKind(kindsAt(longLine, capped, "val s"), TokenKind.KEYWORD));
        check("maxChars<=0 时不着色（不该退化成整行扫）",
                Highlighter.INSTANCE.highlightLine(longLine, lIdx, kt, 0, 0, 0).isEmpty());
        List<HighlightToken> full = Highlighter.INSTANCE.highlightLine(longLine, lIdx, kt, 0, 0, Integer.MAX_VALUE);
        check("上限放开后能标到行尾的注释",
                hasKind(kindsAt(longLine, full, "// tail"), TokenKind.COMMENT));
        checkOrder("截断后的 token", capped, longLine);
        checkOrder("整行的 token", full, longLine);
    }

    static String normalize(String s) { return s.replace("\r\n", "\n").replace('\r', '\n'); }

    static void benchmark(String label, Syntax s) {
        StringBuilder sb = new StringBuilder();
        // 造 1MB 左右的源码：混合注释、字符串、关键字、数字
        String[] line = {
                "    val value%d = \"string %d\" // comment %d",
                "    fun method%d(arg: Int): Boolean { return arg > %d }",
                "    /* block comment %d */",
                "    // line comment %d",
                "    val n%d = 0x%X + 3.14e2 + %dL",
        };
        int i = 0;
        while (sb.length() < 1_000_000) {
            String t = line[i % line.length];
            sb.append(String.format(t, i, i, i)).append('\n');
            i++;
        }
        String text = sb.toString();
        LineIndex idx = LineIndex.Companion.of(text);
        int lines = idx.getLineCount();

        long best = Long.MAX_VALUE;
        for (int r = 0; r < 5; r++) {
            long t0 = System.nanoTime();
            List<HighlightToken> out = Highlighter.INSTANCE.highlight(text, idx, s);
            long t1 = System.nanoTime();
            best = Math.min(best, t1 - t0);
            if (r == 4) {
                System.out.printf("  %s: %d 字符 / %d 行 -> 着色 %.1f ms, %d tokens%n",
                        label, text.length(), lines, best / 1e6, out.size());
            }
        }
    }
}
