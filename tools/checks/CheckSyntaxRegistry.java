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

    static boolean hasKind(List<HighlightToken> toks, TokenKind kind) {
        for (HighlightToken t : toks) if (t.getKind() == kind) return true;
        return false;
    }

    public static void main(String[] args) {
        mapping();
        mappingByName();
        byId();
        picker();
        yaml();
        shell();
        sql();
        config();
        java_();
        cpp();
        go();
        rust();
        lua();
        php();
        ruby();
        makefile();
        dockerfile();
        powershell();
        hcl();
        cmake();
        graphql();
        perl();
        rLang();
        swift();
        csharp();
        diff();
        genericInvariants();

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
     * 按**整个文件名**识别。
     *
     * 这批文件的共同点是「光看扩展名认不出来」：`Makefile` 没有扩展名、`CMakeLists.txt` 的
     * 扩展名是别人家的 `.txt`、`.gitignore` 的点是名字的一部分。整名规则必须**优先于**
     * 扩展名，否则它们永远落到纯文本。
     */
    static void mappingByName() {
        System.out.println("=== 文件名映射 ===");
        check("Makefile -> Makefile", forName("Makefile").getId().equals("makefile"));
        check("GNUmakefile -> Makefile", forName("GNUmakefile").getId().equals("makefile"));
        check("Dockerfile -> Dockerfile", forName("Dockerfile").getId().equals("dockerfile"));
        check("CMakeLists.txt -> CMake", forName("CMakeLists.txt").getId().equals("cmake"));
        check(".gitignore -> Config", forName(".gitignore").getId().equals("config"));
        check(".editorconfig -> Config", forName(".editorconfig").getId().equals("config"));
        check("Gemfile -> Ruby", forName("Gemfile").getId().equals("ruby"));
        check("Cargo.toml 仍是 Config（整名不抢扩展名）",
                forName("Cargo.toml").getId().equals("config"));
        check("pubspec.yaml 仍是 YAML", forName("pubspec.yaml").getId().equals("yaml"));
        check("README -> 纯文本", forName("README").getId().equals("txt"));
        // 大小写不敏感：文件系统分大小写，但用户心里的 `Dockerfile` 与 `dockerfile` 是同一个
        check("dockerfile（小写）也认", forName("dockerfile").getId().equals("dockerfile"));
        check("cMakelists.TXT 也认", forName("cMakelists.TXT").getId().equals("cmake"));
        // 大写扩展名
        check("A.RS -> Rust", forName("A.RS").getId().equals("rust"));
        check("A.TXT -> 纯文本", forName("A.TXT").getId().equals("txt"));
        // 无扩展名且不在名单里：不能因为「没有点」就崩，仍退回纯文本
        check("未知无扩展名文件 -> 纯文本", forName("somefile").getId().equals("txt"));
        check("点开头的未知文件 -> 纯文本", forName(".unknownrc").getId().equals("txt"));
        check("空名字 -> 纯文本", forName("").getId().equals("txt"));
        // 名字本身长得像扩展名（没有点）时**不能**拿去查扩展名表——`kt` 不是 Kotlin 文件
        check("名字就是扩展名（kt）-> 纯文本", forName("kt").getId().equals("txt"));
        check("名字就是扩展名（rs）-> 纯文本", forName("rs").getId().equals("txt"));
        // 点结尾：扩展名是空串，同样退回纯文本（不能去查 byExtension[""]）
        check("点结尾（a.）-> 纯文本", forName("a.").getId().equals("txt"));
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

    // ==================== 第二批语言 ====================

    static void rust() {
        System.out.println("=== Rust ===");
        Syntax s = forName("main.rs");
        String t = "fn main() {\n"
                + "    let s = \"hi\";  // 注释\n"
                + "}\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("Rust", toks, t);
        check("Rust fn 是 KEYWORD", kindsAt(t, toks, "fn").contains(TokenKind.KEYWORD));
        check("Rust let 是 KEYWORD", kindsAt(t, toks, "let").contains(TokenKind.KEYWORD));
        check("Rust 字符串是 STRING", kindsAt(t, toks, "hi").contains(TokenKind.STRING));
        check("Rust // 后是 COMMENT", kindsAt(t, toks, "注释").contains(TokenKind.COMMENT));

        // 生命周期不能当字符字面量：开了 charDelim 的话 `'a str) {}` 会被吞成字符串，
        // 这是本项目刻意不开 Rust charDelim 的原因，用它守住这个决定。
        String life = "fn f<'a>(x: &'a str) {}\n";
        check("Rust 生命周期不产生 STRING", !hasKind(run(life, s), TokenKind.STRING));
    }

    static void lua() {
        System.out.println("=== Lua ===");
        Syntax s = forName("a.lua");
        // 块注释与行注释同前缀（`--` / `--[[`）：扫描器必须**先试块注释**
        String t = "--[[ 说明\n"
                + "依然是注释 ]]\n"
                + "local x = 1  -- 行注释\n"
                + "print(\"hi\")\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("Lua", toks, t);
        check("Lua 块注释第二行仍是 COMMENT",
                kindsAt(t, toks, "依然是注释").contains(TokenKind.COMMENT));
        check("Lua 块注释结束后 local 恢复为 KEYWORD",
                kindsAt(t, toks, "local").contains(TokenKind.KEYWORD));
        check("Lua -- 行注释是 COMMENT", kindsAt(t, toks, "行注释").contains(TokenKind.COMMENT));
        check("Lua print 是 BUILTIN", kindsAt(t, toks, "print").contains(TokenKind.BUILTIN));
    }

    static void php() {
        System.out.println("=== PHP ===");
        Syntax s = forName("index.php");
        String t = "<?php\n"
                + "function greet($name) {\n"
                + "    echo \"hi $name\";  // 注释\n"
                + "}\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("PHP", toks, t);
        check("PHP function 是 KEYWORD", kindsAt(t, toks, "function").contains(TokenKind.KEYWORD));
        check("PHP $name 有颜色", !kindsAt(t, toks, "$name").isEmpty());
        check("PHP 字符串是 STRING", kindsAt(t, toks, "hi $name").contains(TokenKind.STRING));
        check("PHP // 后是 COMMENT", kindsAt(t, toks, "注释").contains(TokenKind.COMMENT));
    }

    static void ruby() {
        System.out.println("=== Ruby ===");
        Syntax s = forName("a.rb");
        String t = "class Demo\n"
                + "  def greet\n"
                + "    @name = \"x\"   # 注释\n"
                + "    puts @name\n"
                + "  end\n"
                + "end\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("Ruby", toks, t);
        check("Ruby def 是 KEYWORD", kindsAt(t, toks, "def").contains(TokenKind.KEYWORD));
        check("Ruby @name 有颜色", !kindsAt(t, toks, "@name").isEmpty());
        check("Ruby puts 是 BUILTIN", kindsAt(t, toks, "puts").contains(TokenKind.BUILTIN));
        check("Ruby 字符串是 STRING", kindsAt(t, toks, "x").contains(TokenKind.STRING));
        check("Ruby # 后是 COMMENT", kindsAt(t, toks, "注释").contains(TokenKind.COMMENT));
        // 单引号内不转义：路径里的反斜杠不能标成 ESCAPE
        String p = "p = 'C:\\dir\\file'\n";
        check("Ruby 不把反斜杠当转义", !hasKind(run(p, s), TokenKind.ESCAPE));
    }

    static void makefile() {
        System.out.println("=== Makefile ===");
        Syntax s = forName("Makefile");
        String t = "CFLAGS = -O2   # 编译选项\n"
                + "all: build\n"
                + "\t@echo done\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("Makefile", toks, t);
        check("Makefile 变量名是 PROPERTY", kindsAt(t, toks, "CFLAGS").contains(TokenKind.PROPERTY));
        check("Makefile 目标是 PROPERTY", kindsAt(t, toks, "all").contains(TokenKind.PROPERTY));
        check("Makefile # 后是 COMMENT", kindsAt(t, toks, "编译选项").contains(TokenKind.COMMENT));
        // 无扩展名的文件靠整名匹配
        check("名字 makefile（小写）也认", forName("makefile").getId().equals("makefile"));
    }

    static void dockerfile() {
        System.out.println("=== Dockerfile ===");
        Syntax s = forName("Dockerfile");
        String t = "FROM eclipse-temurin:17 AS builder\n"
                + "# 构建\n"
                + "RUN ./gradlew assembleRelease\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("Dockerfile", toks, t);
        // 指令大小写不敏感：`FROM` 与 `from` 都要认
        check("Dockerfile FROM 是 KEYWORD", kindsAt(t, toks, "FROM").contains(TokenKind.KEYWORD));
        String lower = "from alpine\n";
        check("Dockerfile from 小写也认",
                kindsAt(lower, run(lower, s), "from").contains(TokenKind.KEYWORD));
        check("Dockerfile # 后是 COMMENT", kindsAt(t, toks, "构建").contains(TokenKind.COMMENT));
    }

    static void powershell() {
        System.out.println("=== PowerShell ===");
        Syntax s = forName("a.ps1");
        String t = "$files = Get-ChildItem -Path C:\\tmp\n"
                + "# 注释\n"
                + "foreach ($f in $files) { Write-Host $f.Name }\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("PowerShell", toks, t);
        // 连字符是标识符的一部分，所以 `Get-ChildItem` 要整词命中内置命令表
        check("PowerShell Get-ChildItem 是 BUILTIN",
                kindsAt(t, toks, "Get-ChildItem").contains(TokenKind.BUILTIN));
        check("PowerShell foreach 是 KEYWORD",
                kindsAt(t, toks, "foreach").contains(TokenKind.KEYWORD));
        check("PowerShell $files 有颜色", !kindsAt(t, toks, "$files").isEmpty());
        check("PowerShell # 后是 COMMENT", kindsAt(t, toks, "注释").contains(TokenKind.COMMENT));
        // 转义符是反引号：Windows 路径里的反斜杠不能被标成转义
        check("PowerShell 不把反斜杠当转义", !hasKind(toks, TokenKind.ESCAPE));
    }

    static void hcl() {
        System.out.println("=== HCL / Terraform ===");
        Syntax s = forName("main.tf");
        String t = "resource \"aws_instance\" \"web\" {\n"
                + "  instance_type = \"t3.micro\"  # 规格\n"
                + "}\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("HCL", toks, t);
        check("HCL resource 是 KEYWORD", kindsAt(t, toks, "resource").contains(TokenKind.KEYWORD));
        check("HCL 键是 PROPERTY",
                kindsAt(t, toks, "instance_type").contains(TokenKind.PROPERTY));
        check("HCL # 后是 COMMENT", kindsAt(t, toks, "规格").contains(TokenKind.COMMENT));
    }

    static void cmake() {
        System.out.println("=== CMake ===");
        Syntax s = forName("CMakeLists.txt");
        String t = "cmake_minimum_required(VERSION 3.20)\n"
                + "project(demo)\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("CMake", toks, t);
        check("CMake project 是 KEYWORD", kindsAt(t, toks, "project").contains(TokenKind.KEYWORD));
        // 「标识符 + (」这条通用规则让未列进词表的命令名也有颜色
        String custom = "my_custom_command(ARG)\n";
        check("CMake 未列出的命令名（后跟括号）是 FUNCTION",
                kindsAt(custom, run(custom, s), "my_custom_command").contains(TokenKind.FUNCTION));
        // 扩展名是 .txt，只有整名规则能把它从纯文本里救出来
        check("CMakeLists.txt 不是纯文本", !forName("CMakeLists.txt").getId().equals("txt"));
    }

    static void graphql() {
        System.out.println("=== GraphQL ===");
        Syntax s = forName("schema.graphql");
        String t = "\"\"\"说明文档\"\"\"\n"
                + "type Query {\n"
                + "  user(id: ID!): String  # 注释\n"
                + "}\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("GraphQL", toks, t);
        check("GraphQL type 是 KEYWORD", kindsAt(t, toks, "type").contains(TokenKind.KEYWORD));
        check("GraphQL 三引号是 STRING", kindsAt(t, toks, "说明文档").contains(TokenKind.STRING));
        check("GraphQL ID 是 TYPE", kindsAt(t, toks, "ID").contains(TokenKind.TYPE));
        check("GraphQL # 后是 COMMENT", kindsAt(t, toks, "注释").contains(TokenKind.COMMENT));
    }

    static void perl() {
        System.out.println("=== Perl ===");
        Syntax s = forName("a.pl");
        String t = "my $count = 3;   # 计数\n"
                + "print \"total: $count\\n\";\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("Perl", toks, t);
        check("Perl my 是 KEYWORD", kindsAt(t, toks, "my").contains(TokenKind.KEYWORD));
        check("Perl $count 有颜色", !kindsAt(t, toks, "$count").isEmpty());
        check("Perl print 是 KEYWORD", kindsAt(t, toks, "print").contains(TokenKind.KEYWORD));
        check("Perl # 后是 COMMENT", kindsAt(t, toks, "计数").contains(TokenKind.COMMENT));
    }

    static void rLang() {
        System.out.println("=== R ===");
        Syntax s = forName("plot.R");
        String t = "df <- data.frame(x = 1:3)\n"
                + "mean(df$x)   # 均值\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("R", toks, t);
        // 标识符里允许点，`data.frame` 才会被当成一个词命中内置函数表
        check("R data.frame 是 BUILTIN", kindsAt(t, toks, "data.frame").contains(TokenKind.BUILTIN));
        check("R mean 是 BUILTIN", kindsAt(t, toks, "mean").contains(TokenKind.BUILTIN));
        check("R # 后是 COMMENT", kindsAt(t, toks, "均值").contains(TokenKind.COMMENT));
        check("R 扩展名大写 .R 也认", forName("plot.R").getId().equals("r"));
    }

    static void swift() {
        System.out.println("=== Swift ===");
        Syntax s = forName("View.swift");
        String t = "struct Demo: String {\n"
                + "    @State private var name = \"x\"\n"
                + "    func body() {}\n"
                + "}\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("Swift", toks, t);
        check("Swift struct 是 KEYWORD", kindsAt(t, toks, "struct").contains(TokenKind.KEYWORD));
        check("Swift func 是 KEYWORD", kindsAt(t, toks, "func").contains(TokenKind.KEYWORD));
        check("Swift String 是 TYPE", kindsAt(t, toks, "String").contains(TokenKind.TYPE));
        check("Swift @State 有颜色", !kindsAt(t, toks, "@State").isEmpty());
        // 没有字符字面量：不开 charDelim，单引号不该被当成字符串起点
        check("Swift 不把单引号当字符串", !hasKind(run("let c = 'x'\n", s), TokenKind.STRING));
    }

    static void csharp() {
        System.out.println("=== C# ===");
        Syntax s = forName("Program.cs");
        String t = "#region 头部\n"
                + "public class Demo {\n"
                + "    private string name = \"x\";  // 注释\n"
                + "}\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("C#", toks, t);
        check("C# public 是 KEYWORD", kindsAt(t, toks, "public").contains(TokenKind.KEYWORD));
        // C# 的 string / int 是**别名类型**，归在 types 里（它们着色相同，语义上更准）
        check("C# string 是 TYPE", kindsAt(t, toks, "string").contains(TokenKind.TYPE));
        check("C# #region 有颜色", !kindsAt(t, toks, "#region").isEmpty());
        check("C# // 后是 COMMENT", kindsAt(t, toks, "注释").contains(TokenKind.COMMENT));
    }

    // ==================== 补丁 ====================

    /**
     * 补丁走的是第四条分支，判据全在**行首前缀**上。
     *
     * 最容易写错的是判定顺序：`@@ -12,7 +12,9 @@` 里同时有 `-` 和 `+`，`--- a/x` 是文件头
     * 而不是「删除行」。所以这里逐行断言，而不只看「整篇有没有颜色」。
     */
    static void diff() {
        System.out.println("=== Diff / Patch ===");
        Syntax s = forName("fix.patch");
        String t = "diff --git a/src/A.kt b/src/A.kt\n"
                + "index 1f2e3d4..5a6b7c8 100644\n"
                + "--- a/src/A.kt\n"
                + "+++ b/src/A.kt\n"
                + "@@ -12,7 +12,9 @@ fun main() {\n"
                + "     val total = 0\n"
                + "-    total = old\n"
                + "+    total = a + b\n"
                + "+    total += 1\n"
                + "\\ No newline at end of file\n";
        List<HighlightToken> toks = run(t, s);
        checkOrder("Diff", toks, t);

        check("diff --git 是 META", kindsAt(t, toks, "diff --git").contains(TokenKind.META));
        check("index 行是 META", kindsAt(t, toks, "index 1f2e3d4").contains(TokenKind.META));
        check("@@ 是 HUNK", kindsAt(t, toks, "@@ -12,7").contains(TokenKind.HUNK));
        check("删除行是 DELETED", kindsAt(t, toks, "-    total = old").contains(TokenKind.DELETED));
        check("新增行是 INSERTED", kindsAt(t, toks, "+    total = a + b").contains(TokenKind.INSERTED));
        check("No newline 标记是 META",
                kindsAt(t, toks, "\\ No newline").contains(TokenKind.META));

        // ---- 顺序：长前缀优先，别把文件头当成增删 ----
        check("--- a/src/A.kt 是 META 而不是 DELETED",
                kindsAt(t, toks, "--- a/src/A.kt").contains(TokenKind.META)
                        && !kindsAt(t, toks, "--- a/src/A.kt").contains(TokenKind.DELETED));
        // "+++ b/src/A.kt" 与上一行同理：单独取 `+++` 之后的位置，避开 `---` 那行的干扰
        int plusPlus = t.indexOf("+++ b/src");
        boolean plusIsMeta = false, plusIsInserted = false;
        for (HighlightToken k : toks) {
            if (k.getStart() <= plusPlus && plusPlus < k.getEnd()) {
                plusIsMeta = k.getKind() == TokenKind.META;
                plusIsInserted = k.getKind() == TokenKind.INSERTED;
            }
        }
        check("+++ b/src/A.kt 是 META 而不是 INSERTED", plusIsMeta && !plusIsInserted);

        // ---- 行内的 + 不能另起一个 token ----
        // `total = a + b` 里那个加号如果被当成新增，一行会裂成两个 span，配色就花了
        int inner = t.indexOf("a + b");
        int covering = 0;
        for (HighlightToken k : toks) if (k.getStart() <= inner && inner < k.getEnd()) covering++;
        check("行内的 + 不产生额外 token", covering == 1);
        int inserted = 0;
        for (HighlightToken k : toks) if (k.getKind() == TokenKind.INSERTED) inserted++;
        check("整篇只有两行 INSERTED", inserted == 2);

        // ---- 上下文行保持正文色（它没有被改动，染色反而会淹掉增删边界）----
        check("上下文行不着色", kindsAt(t, toks, "val total = 0").isEmpty());

        // ---- 扩展名 ----
        check("a.diff -> Diff", forName("a.diff").getId().equals("diff"));
        check("a.rej -> Diff", forName("a.rej").getId().equals("diff"));
        // 其它语言不能被这一支抢走（`patch` 是扩展名，不是语言）
        check("a.rs 仍是 Rust", forName("a.rs").getId().equals("rust"));
    }

    // ==================== 通用不变量 ====================

    /**
     * 不为每门语言手写一节，而是对**全部**语法扫一遍。
     *
     * 两条自检故意做得很硬：
     * 1. **关键字 / 类型 / 内置里不允许有永远匹配不到的条目**——扫描器只认
     *    「以标识符字符开头、且全部由标识符字符组成」的词，于是 `filter-out`（Makefile）、
     *    `defined?`（Perl / Ruby）、`foldl'`（Haskell）这类写法写进去就是死数据：
     *    它们既不着色，也不会报错，只会让人误以为支持。这条断言把「注释与代码矛盾」
     *    这类问题挡在提交前。
     * 2. **每门语言至少要着出一处颜色**——整篇不着色通常意味着 `stringDelims` /
     *    `lineComment` 这类字段漏填了，而不是扫描器坏了。
     */
    static void genericInvariants() {
        System.out.println("=== 通用不变量（全部 " + SyntaxRegistry.INSTANCE.getAll().size() + " 种） ===");

        List<String> deadEntries = new ArrayList<>();
        List<String> colorless = new ArrayList<>();
        List<String> badOrder = new ArrayList<>();
        boolean allNamesLowercase = true;
        boolean allNamesReachable = true;

        for (Syntax s : SyntaxRegistry.INSTANCE.getAll()) {
            // ---- 词表条目必须写得出来才可能被认出来 ----
            List<String> words = new ArrayList<>();
            for (String k : s.getKeywords()) words.add("keywords:" + k);
            for (String k : s.getTypes()) words.add("types:" + k);
            for (String k : s.getBuiltins()) words.add("builtins:" + k);
            for (String entry : words) {
                String word = entry.substring(entry.indexOf(':') + 1);
                if (!matchable(word, s)) deadEntries.add(s.getId() + " " + entry);
            }

            // ---- 整名匹配用的名字一律小写（查表前会把文件名转小写） ----
            for (String n : s.getFileNames()) {
                if (!n.equals(n.toLowerCase())) allNamesLowercase = false;
                if (!forName(n).getId().equals(s.getId())) {
                    System.out.println("       " + s.getId() + " 的文件名 " + n + " 反查不到自己");
                    allNamesReachable = false;
                }
            }

            // ---- 一段各构造混在一起的样本：token 必须有序、不重叠、不出界 ----
            StringBuilder sb = new StringBuilder();
            sb.append("\"q\" 42 foo bar\n");
            sb.append("<a b=\"c\">text</a>\n");
            sb.append("# heading\n*em* `code`\n");
            if (s.getLineComment() != null) sb.append(s.getLineComment()).append(" c\n");
            if (s.getBlockComment() != null) {
                sb.append(s.getBlockComment().getFirst()).append(" b ")
                        .append(s.getBlockComment().getSecond()).append("\n");
            }
            if (!s.getVerbatimStrings().isEmpty()) {
                String v = s.getVerbatimStrings().get(0);
                sb.append(v).append(" v ").append(v).append("\n");
            }
            String sample = sb.toString();
            List<HighlightToken> toks = run(sample, s);
            int prevEnd = -1;
            boolean ok = true;
            for (HighlightToken t : toks) {
                if (t.getStart() < prevEnd || t.getEnd() <= t.getStart()
                        || t.getEnd() > sample.length()) {
                    ok = false;
                    break;
                }
                prevEnd = t.getEnd();
            }
            if (!ok) badOrder.add(s.getId());

            // ---- 至少要着出颜色 ----
            boolean colored = !toks.isEmpty();
            // 标记语言、Markdown 与补丁各走自己那条分支，都不读上面那份样本
            // （它既没有标签、也没有 Markdown 构造、更没有一行以 +/-/@@ 开头），
            // 所以给它们各喂一份自家样本单独判一次
            if (s.getMarkup()) {
                colored = !run("<div class=\"x\">a</div>\n", s).isEmpty();
            } else if (s.getMarkdown()) {
                colored = !run("# t\n\n**b** `c`\n", s).isEmpty();
            } else if (s.getDiff()) {
                // 补丁同样不读上面那份样本：里面没有一行是以 +/-/@@ 开头的
                colored = !run("+ added\n- removed\n@@ -1 +1 @@\n", s).isEmpty();
            } else if (!colored) {
                // 只写了关键字、没写注释/字符串的语言，用第一个关键字试
                if (!s.getKeywords().isEmpty()) {
                    colored = hasKind(run(s.getKeywords().iterator().next() + "\n", s),
                            TokenKind.KEYWORD);
                }
            }
            // ---- 至少要着出颜色（纯文本除外：不着色就是它的功能定义） ----
            if (!colored && !s.getId().equals(Syntax.Companion.getPLAIN().getId())) {
                colorless.add(s.getId());
            }
        }

        for (String d : deadEntries) System.out.println("       永远匹配不到的词表条目：" + d);
        check("词表里没有永远匹配不到的条目", deadEntries.isEmpty());
        for (String c : colorless) System.out.println("       着不出颜色：" + c);
        check("每种语法都能着出颜色", colorless.isEmpty());
        for (String b : badOrder) System.out.println("       token 顺序异常：" + b);
        check("全部语法的 token 都有序且不重叠", badOrder.isEmpty());
        check("文件名单一律小写", allNamesLowercase);
        check("每个文件名都能反查回自己", allNamesReachable);
    }

    /** 这个词在扫描器眼里写得出来吗（首字符算标识符起点、其余字符算标识符内部） */
    static boolean matchable(String word, Syntax s) {
        if (word.isEmpty()) return false;
        if (!isIdentStart(word.charAt(0), s)) return false;
        for (int i = 1; i < word.length(); i++) {
            if (!isIdentPart(word.charAt(i), s)) return false;
        }
        return true;
    }

    static boolean isIdentStart(char c, Syntax s) {
        return Character.isLetter(c) || c == '_' || c == '$' || s.getIdentStarts().contains(c);
    }

    static boolean isIdentPart(char c, Syntax s) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$'
                || s.getIdentChars().contains(c);
    }
}
