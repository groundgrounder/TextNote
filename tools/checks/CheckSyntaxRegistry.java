import com.textnote.app.core.Highlighter;
import com.textnote.app.core.HighlightToken;
import com.textnote.app.core.LineIndex;
import com.textnote.app.core.Syntax;
import com.textnote.app.core.SyntaxRegistry;
import com.textnote.app.core.TokenKind;

import java.util.ArrayList;
import java.util.List;

/**
 * 语法表：扩展名映射 + 每种语言的着色是否落在点上。
 *
 * 加一门语言时在这里补一节。两条不变量是硬约束：
 * 1. **token 必须按 start 递增且互不重叠**——重叠时渲染结果取决于 span 的叠加顺序，
 *    界面上表现为「这段颜色时有时无」，很难查；
 * 2. **每种语言至少有一个关键字 / 一个字符串被认出来**——整篇不着色通常意味着
 *    `Syntax` 里漏填了 `stringDelims` 或 `lineComment` 这类字段，而不是扫描器坏了。
 */
public class CheckSyntaxRegistry {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    static List<HighlightToken> run(String text, Syntax s) {
        return Highlighter.INSTANCE.highlight(text, LineIndex.Companion.of(text), s);
    }

    /** 覆盖该子串所在位置的所有 token 种类 */
    static List<TokenKind> kindsAt(String text, List<HighlightToken> toks, String sub) {
        int idx = text.indexOf(sub);
        if (idx < 0) throw new RuntimeException("测试文本里找不到片段: " + sub);
        List<TokenKind> r = new ArrayList<>();
        for (HighlightToken t : toks) {
            if (t.getStart() <= idx && idx < t.getEnd()) r.add(t.getKind());
        }
        return r;
    }

    static void checkOrder(String label, List<HighlightToken> toks, String text) {
        int prevEnd = -1;
        boolean ok = true;
        for (HighlightToken t : toks) {
            if (t.getStart() < prevEnd) { ok = false; break; }
            if (t.getEnd() <= t.getStart() || t.getEnd() > text.length()) { ok = false; break; }
            prevEnd = t.getEnd();
        }
        check(label + " token 有序且不重叠", ok);
    }

    static Syntax forName(String fileName) {
        return SyntaxRegistry.INSTANCE.forFileName(fileName);
    }

    public static void main(String[] args) {
        mapping();
        byId();
        picker();
        yaml();
        shell();
        sql();
        config();
        java_();
        cpp();
        go();

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 扩展名 → 语法。每种至少验一个代表扩展名 */
    static void mapping() {
        System.out.println("=== 扩展名映射 ===");
        check("a.yaml -> YAML", forName("a.yaml").getId().equals("yaml"));
        check("a.yml -> YAML", forName("a.yml").getId().equals("yaml"));
        check("a.sh -> Shell", forName("a.sh").getId().equals("shell"));
        check("a.bash -> Shell", forName("a.bash").getId().equals("shell"));
        check("a.sql -> SQL", forName("a.sql").getId().equals("sql"));
        check("a.ini -> Config", forName("a.ini").getId().equals("config"));
        check("a.toml -> Config", forName("a.toml").getId().equals("config"));
        check("a.conf -> Config", forName("a.conf").getId().equals("config"));
        check("A.java -> Java", forName("A.java").getId().equals("java"));
        check("a.c -> C/C++", forName("a.c").getId().equals("cpp"));
        check("a.cpp -> C/C++", forName("a.cpp").getId().equals("cpp"));
        check("a.hpp -> C/C++", forName("a.hpp").getId().equals("cpp"));
        check("a.go -> Go", forName("a.go").getId().equals("go"));
        // 老语言的映射不能被新增的覆盖掉
        check("a.kt 仍是 Kotlin", forName("a.kt").getId().equals("kotlin"));
        check("a.py 仍是 Python", forName("a.py").getId().equals("python"));
        check("a.log 仍是纯文本", forName("a.log").getId().equals("txt"));
        // 表里每一项的扩展名都必须能反查回自己，否则是拼错了或被人抢占了
        boolean allReachable = true;
        for (Syntax s : SyntaxRegistry.INSTANCE.getAll()) {
            for (String ext : s.getExtensions()) {
                if (!forName("x." + ext).getId().equals(s.getId())) {
                    System.out.println("       " + s.getId() + " 的 ." + ext + " 反查不到自己");
                    allReachable = false;
                }
            }
        }
        check("每种语法的每个扩展名都能反查回自己", allReachable);
    }

    /**
     * 手动切换用的两个接口。
     *
     * 手动选择按 **id** 持久化，所以 byId 必须能把存下来的 id 还原成同一门语法——
     * 还原错了就是「我选的 Python 下次打开变成了别的」，而且用户看不出发生了什么。
     */
    static void byId() {
        System.out.println("=== byId（手动选择的持久化往返） ===");
        boolean roundTrip = true;
        for (Syntax s : SyntaxRegistry.INSTANCE.getAll()) {
            Syntax back = SyntaxRegistry.INSTANCE.byId(s.getId());
            if (back == null || !back.getId().equals(s.getId())) {
                System.out.println("       " + s.getId() + " 按 id 反查不到自己");
                roundTrip = false;
            }
        }
        check("每种语法都能按 id 反查回自己", roundTrip);
        check("未知 id 返回 null（由调用方退化成自动）",
                SyntaxRegistry.INSTANCE.byId("no-such-language") == null);
        check("空 id 返回 null", SyntaxRegistry.INSTANCE.byId("") == null);
        // id 必须唯一，否则持久化只可能还原出其中一门
        boolean unique = true;
        List<String> ids = new ArrayList<>();
        for (Syntax s : SyntaxRegistry.INSTANCE.getAll()) {
            if (ids.contains(s.getId())) unique = false;
            ids.add(s.getId());
        }
        check("id 唯一", unique);
    }

    /** 手动选择菜单的列表：纯文本打头、其余按显示名排序、不丢不重 */
    static void picker() {
        System.out.println("=== picker（手动选择菜单） ===");
        List<Syntax> picker = SyntaxRegistry.INSTANCE.getPicker();
        check("picker 第一项是纯文本",
                picker.get(0).getId().equals(Syntax.Companion.getPLAIN().getId()));
        // 覆盖全部且每种恰好一次：漏掉一种就等于那门语言在菜单里选不到
        boolean complete = true;
        for (Syntax s : SyntaxRegistry.INSTANCE.getAll()) {
            int n = 0;
            for (Syntax p : picker) if (p.getId().equals(s.getId())) n++;
            if (n != 1) complete = false;
        }
        check("picker 覆盖全部语法且每种恰好一次", complete);
        boolean sorted = true;
        for (int i = 2; i < picker.size(); i++) {
            if (picker.get(i - 1).getDisplayName().compareTo(picker.get(i).getDisplayName()) > 0) {
                sorted = false;
            }
        }
        check("picker 除首项外按显示名排序", sorted);
        boolean noPlainTail = true;
        for (int i = 1; i < picker.size(); i++) {
            if (picker.get(i).getId().equals(Syntax.Companion.getPLAIN().getId())) noPlainTail = false;
        }
        check("picker 里纯文本只出现一次", noPlainTail);
    }

    static void yaml() {
        System.out.println("=== YAML ===");
        Syntax s = forName("a.yml");
        String t = "name: demo\n"
                + "enabled: true\n"
                + "items:\n"
                + "  - one   # 注释\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("YAML", toks, t);
        check("YAML 键是 PROPERTY", kindsAt(t, toks, "name").contains(TokenKind.PROPERTY));
        check("YAML 布尔是 BUILTIN", kindsAt(t, toks, "true").contains(TokenKind.BUILTIN));
        check("YAML # 后是 COMMENT", kindsAt(t, toks, "注释").contains(TokenKind.COMMENT));
        // 关掉了 escape：路径里的反斜杠不能被标成 ESCAPE
        String p = "path: \"C:\\dir\\file\"\n";
        List<HighlightToken> pt = run(p, s);
        boolean noEscape = true;
        for (HighlightToken k : pt) if (k.getKind() == TokenKind.ESCAPE) noEscape = false;
        check("YAML 不把反斜杠当转义", noEscape);
    }

    static void shell() {
        System.out.println("=== Shell ===");
        Syntax s = forName("a.sh");
        String t = "#!/bin/sh\n"
                + "if [ -f \"$1\" ]; then\n"
                + "  echo \"found\"\n"
                + "fi\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("Shell", toks, t);
        check("Shell if 是 KEYWORD", kindsAt(t, toks, "if").contains(TokenKind.KEYWORD));
        check("Shell echo 是 BUILTIN", kindsAt(t, toks, "echo").contains(TokenKind.BUILTIN));
        check("Shell 字符串是 STRING", kindsAt(t, toks, "found").contains(TokenKind.STRING));
        check("Shell # 后是 COMMENT", kindsAt(t, toks, "/bin/sh").contains(TokenKind.COMMENT));
    }

    static void sql() {
        System.out.println("=== SQL ===");
        Syntax s = forName("a.sql");
        String t = "SELECT id, name FROM users\n"
                + "WHERE age > 18;   -- 只查成年人\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("SQL", toks, t);
        // SQL 关键字通常大写写，表是 caseSensitive = false
        check("SQL SELECT 是 KEYWORD", kindsAt(t, toks, "SELECT").contains(TokenKind.KEYWORD));
        check("SQL 数字是 NUMBER", kindsAt(t, toks, "18").contains(TokenKind.NUMBER));
        check("SQL -- 后是 COMMENT", kindsAt(t, toks, "只查成年人").contains(TokenKind.COMMENT));
        String lower = "select * from t;\n";
        check("SQL 小写关键字同样认",
                kindsAt(lower, run(lower, s), "select").contains(TokenKind.KEYWORD));
    }

    static void config() {
        System.out.println("=== Config (INI/TOML) ===");
        Syntax s = forName("a.toml");
        String t = "[server]\n"
                + "host = \"localhost\"  # 地址\n"
                + "port = 8080\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("Config", toks, t);
        check("Config 键是 PROPERTY", kindsAt(t, toks, "host").contains(TokenKind.PROPERTY));
        check("Config 字符串是 STRING", kindsAt(t, toks, "localhost").contains(TokenKind.STRING));
        check("Config 数字是 NUMBER", kindsAt(t, toks, "8080").contains(TokenKind.NUMBER));
        check("Config # 后是 COMMENT", kindsAt(t, toks, "地址").contains(TokenKind.COMMENT));
    }

    static void java_() {
        System.out.println("=== Java ===");
        Syntax s = forName("A.java");
        String t = "public class Demo {\n"
                + "    private int count = 42;\n"
                + "    // 注释\n"
                + "}\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("Java", toks, t);
        check("Java public 是 KEYWORD", kindsAt(t, toks, "public").contains(TokenKind.KEYWORD));
        check("Java int 是 KEYWORD", kindsAt(t, toks, "int").contains(TokenKind.KEYWORD));
        check("Java String 是 TYPE", kindsAt("String x = \"a\";\n", run("String x = \"a\";\n", s), "String")
                .contains(TokenKind.TYPE));
        check("Java 数字是 NUMBER", kindsAt(t, toks, "42").contains(TokenKind.NUMBER));
        check("Java // 后是 COMMENT", kindsAt(t, toks, "注释").contains(TokenKind.COMMENT));
    }

    static void cpp() {
        System.out.println("=== C/C++ ===");
        Syntax s = forName("a.cpp");
        String t = "#include <stdio.h>\n"
                + "int main() {\n"
                + "    return 0;   // 退出\n"
                + "}\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("C/C++", toks, t);
        // 预处理指令靠 identStarts = {'#'} 被认成标识符，着 TAG 色
        check("C 预处理指令有颜色", !kindsAt(t, toks, "include").isEmpty());
        check("C int 是 TYPE 或 KEYWORD",
                kindsAt(t, toks, "int").contains(TokenKind.TYPE)
                        || kindsAt(t, toks, "int").contains(TokenKind.KEYWORD));
        check("C 数字是 NUMBER", kindsAt(t, toks, "0;").contains(TokenKind.NUMBER));
        check("C // 后是 COMMENT", kindsAt(t, toks, "退出").contains(TokenKind.COMMENT));
    }

    static void go() {
        System.out.println("=== Go ===");
        Syntax s = forName("a.go");
        String t = "package main\n"
                + "func main() {\n"
                + "    s := `raw\n"
                + "string`\n"
                + "}\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("Go", toks, t);
        check("Go package 是 KEYWORD", kindsAt(t, toks, "package").contains(TokenKind.KEYWORD));
        check("Go func 是 KEYWORD", kindsAt(t, toks, "func").contains(TokenKind.KEYWORD));
        // raw string 能跨行：第二行 string` 也应被当作字符串的一部分
        check("Go raw string 跨行仍然闭合成 STRING",
                kindsAt(t, toks, "string`").contains(TokenKind.STRING));
    }
}
