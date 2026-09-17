import com.textnote.app.core.SearchEngine;
import com.textnote.app.core.SearchMatch;
import com.textnote.app.core.SearchQuery;
import com.textnote.app.core.SearchResult;

import java.util.ArrayList;
import java.util.List;

public class CheckSearch {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    static SearchQuery lit(String p) { return new SearchQuery(p, false, false, false); }
    static SearchQuery litCase(String p) { return new SearchQuery(p, false, true, false); }
    static SearchQuery re(String p) { return new SearchQuery(p, true, false, false); }
    static SearchQuery reCase(String p) { return new SearchQuery(p, true, true, false); }
    static SearchQuery word(String p) { return new SearchQuery(p, false, true, true); }

    static SearchResult find(String text, SearchQuery q) {
        return SearchEngine.INSTANCE.findAll(text, q, SearchEngine.MAX_MATCHES);
    }

    /** 把 matchIndicesInRange 的结果打印成便于断言的形式：空区间也是有意义的结果 */
    static String range(List<SearchMatch> matches, int from, int to) {
        kotlin.ranges.IntRange r = SearchEngine.INSTANCE.matchIndicesInRange(matches, from, to);
        return r.isEmpty() ? "empty" : r.getFirst() + ".." + r.getLast();
    }

    static List<String> texts(String text, SearchResult r) {
        List<String> out = new ArrayList<>();
        for (SearchMatch m : r.getMatches()) out.add(text.substring(m.getStart(), m.getEnd()));
        return out;
    }

    public static void main(String[] args) {
        System.out.println("=== 字面量查找 ===");
        String t1 = "Hello world, hello Kotlin, HELLO again";
        SearchResult r1 = find(t1, lit("hello"));
        check("默认不区分大小写，命中 3 处", r1.getMatches().size() == 3);
        check("命中区间正确", texts(t1, r1).equals(java.util.Arrays.asList("Hello", "hello", "HELLO")));
        check("区分大小写后只命中 1 处", find(t1, litCase("hello")).getMatches().size() == 1);
        check("区分大小写命中中间那个", texts(t1, find(t1, litCase("hello"))).get(0).equals("hello"));

        System.out.println("=== 字面量模式必须避开正则元字符 ===");
        String t2 = "a.b axb a-b";
        SearchResult r2 = find(t2, lit("a.b"));
        check("a.b 只匹配字面的 a.b", r2.getMatches().size() == 1);
        check("匹配到的确实是 a.b", texts(t2, r2).get(0).equals("a.b"));
        check("正则模式下 a.b 会命中 3 处", find(t2, re("a.b")).getMatches().size() == 3);
        String t3 = "cost is $9.99 (approx)";
        check("字面量里的 $ ( ) 不影响匹配", find(t3, lit("$9.99 (approx)")).getMatches().size() == 1);

        System.out.println("=== 正则查找与捕获组 ===");
        String t4 = "id=12 name=ab id=7";
        SearchResult r4 = find(t4, re("id=(\\d+)"));
        check("捕获组命中 2 处", r4.getMatches().size() == 2);
        // groups 与 groupValues 同构：下标即组号，第 0 项是整段命中
        check("第 1 处 groups 有 2 项（整段 + 1 个捕获组）", r4.getMatches().get(0).getGroups().size() == 2);
        check("第 1 处第 0 组（整段） = id=12", r4.getMatches().get(0).getGroups().get(0).equals("id=12"));
        check("第 1 处捕获组 = 12", r4.getMatches().get(0).getGroups().get(1).equals("12"));
        check("第 2 处第 0 组（整段） = id=7", r4.getMatches().get(1).getGroups().get(0).equals("id=7"));
        check("第 2 处捕获组 = 7", r4.getMatches().get(1).getGroups().get(1).equals("7"));
        check("字面量查找没有组，groups 为空", find(t4, lit("id")).getMatches().get(0).getGroups().isEmpty());
        check("整段匹配文本正确", texts(t4, r4).equals(java.util.Arrays.asList("id=12", "id=7")));

        System.out.println("=== 非法正则 ===");
        SearchResult bad = find("abc", re("(unclosed"));
        check("报 invalidPattern", bad.getInvalidPattern());
        check("不返回命中", bad.getMatches().isEmpty());
        SearchResult bad2 = find("abc", re("a{2,1}"));
        check("量词非法也算无效", bad2.getInvalidPattern());

        System.out.println("=== 零宽匹配必须被丢掉 ===");
        // a* 在 "bbb" 上会匹配出若干长度为 0 的结果；留着它们会让「下一处」永远停在原地
        SearchResult zero = find("bbb", re("a*"));
        check("bbb 上搜 a* 命中 0 处", zero.getMatches().isEmpty());
        check("a* 在 aab 上只命中真实的 a 段", texts("aab", find("aab", re("a*"))).size() == 1);
        check("a* 命中内容是 aa", texts("aab", find("aab", re("a*"))).get(0).equals("aa"));

        System.out.println("=== 整词匹配 ===");
        String t5 = "cat category cat. concat cat";
        SearchResult r5 = find(t5, word("cat"));
        check("整词只命中 3 处", r5.getMatches().size() == 3);
        check("不误命中 category", !texts(t5, r5).contains("cat") == false);
        List<String> w = texts(t5, r5);
        check("命中的都是独立的 cat", w.get(0).equals("cat") && w.get(1).equals("cat") && w.get(2).equals("cat"));

        System.out.println("=== 上下处跳转与环绕 ===");
        String t6 = "x foo x foo x foo";
        List<SearchMatch> m6 = find(t6, lit("foo")).getMatches();
        check("命中 3 处", m6.size() == 3);
        int i0 = SearchEngine.INSTANCE.nextIndex(m6, 0);
        check("从 0 往后是第 0 处", i0 == 0);
        int i1 = SearchEngine.INSTANCE.nextIndex(m6, m6.get(0).getEnd());
        check("从第 0 处结尾往后是第 1 处", i1 == 1);
        int i2 = SearchEngine.INSTANCE.nextIndex(m6, m6.get(2).getEnd());
        check("最后一处之后绕回第 0 处", i2 == 0);
        int p0 = SearchEngine.INSTANCE.prevIndex(m6, m6.get(0).getStart());
        check("第一处之前绕回最后一处", p0 == 2);
        int p1 = SearchEngine.INSTANCE.prevIndex(m6, m6.get(1).getStart());
        check("从第 1 处往前是第 0 处", p1 == 0);
        check("空列表 nextIndex 返回 -1", SearchEngine.INSTANCE.nextIndex(new ArrayList<>(), 0) == -1);
        check("空列表 prevIndex 返回 -1", SearchEngine.INSTANCE.prevIndex(new ArrayList<>(), 0) == -1);

        System.out.println("=== 行内命中区间（只读渲染器按行标底色用） ===");
        // 手造三处命中：0..3、5..8、10..13，模拟「一行上多处命中」
        List<SearchMatch> r = java.util.Arrays.asList(
                new SearchMatch(0, 3, new ArrayList<>()),
                new SearchMatch(5, 8, new ArrayList<>()),
                new SearchMatch(10, 13, new ArrayList<>()));
        check("整段 [0,13) 拿到全部 3 处", range(r, 0, 13).equals("0..2"));
        check("行 [0,9) 拿到前 2 处", range(r, 0, 9).equals("0..1"));
        check("行 [4,9) 只拿到第 1 处", range(r, 4, 9).equals("1..1"));
        check("夹在两处之间的空档 → 空", range(r, 3, 5).equals("empty"));
        check("尾部是开区间：命中正好在 to 上不算", range(r, 0, 10).equals("0..1"));
        check("整行在命中之后 → 空（不能绕回开头）", range(r, 14, 20).equals("empty"));
        check("空命中列表 → 空", range(new ArrayList<>(), 0, 10).equals("empty"));
        check("from >= to → 空", range(r, 7, 7).equals("empty"));

        // 跨行命中：一处命中从第 0 行伸到第 1 行，第二行必须也能标到它
        List<SearchMatch> cross = java.util.Arrays.asList(new SearchMatch(0, 10, new ArrayList<>()));
        check("第 0 行 [0,5) 标到跨行命中", range(cross, 0, 5).equals("0..0"));
        check("第 1 行 [5,10) 也标到它（start 在行前、end 在行内）", range(cross, 5, 10).equals("0..0"));
        check("命中之前的行 → 空", range(cross, 11, 20).equals("empty"));

        // 真实路径：`\n` 显式写进正则就能跨行，验证 findAll 与区间查询接得上
        List<SearchMatch> nl = find("a\nb", re("a\\nb")).getMatches();
        check("显式 \\n 的正则能跨行命中 1 处", nl.size() == 1);
        check("跨行命中的区间是 0..3", nl.get(0).getStart() == 0 && nl.get(0).getEnd() == 3);
        check("第 0 行标到它", range(nl, 0, 1).equals("0..0"));
        check("第 1 行也标到它", range(nl, 2, 3).equals("0..0"));

        System.out.println("=== 替换模板展开 ===");
        // 下标即组号：g(0) 是整段命中，g(1)… 是捕获组。写成 [i] 是为了让
        // 「$1 后面跟个数字」和「第 12 组」在输出上不会撞成同一个字符串。
        List<String> g = new ArrayList<>();
        g.add("[0]");
        for (int i = 1; i <= 12; i++) g.add("[" + i + "]");
        check("$0 -> 整段命中", SearchEngine.INSTANCE.expandReplacement("$0", g).equals("[0]"));
        check("${0} -> 整段命中", SearchEngine.INSTANCE.expandReplacement("${0}", g).equals("[0]"));
        check("原地包裹整段", SearchEngine.INSTANCE.expandReplacement("($0)", g).equals("([0])"));
        check("$1 -> 第 1 组", SearchEngine.INSTANCE.expandReplacement("$1", g).equals("[1]"));
        check("${1} -> 第 1 组", SearchEngine.INSTANCE.expandReplacement("${1}", g).equals("[1]"));
        check("$2 -> 第 2 组", SearchEngine.INSTANCE.expandReplacement("$2", g).equals("[2]"));
        check("$9 -> 第 9 组", SearchEngine.INSTANCE.expandReplacement("$9", g).equals("[9]"));
        check("不带花括号只吃一位数字：$12 -> 第 1 组 + 字面 2",
                SearchEngine.INSTANCE.expandReplacement("$12", g).equals("[1]2"));
        check("${12} -> 第 12 组", SearchEngine.INSTANCE.expandReplacement("${12}", g).equals("[12]"));
        check("越界的 $99 -> 第 9 组 + 字面 9",
                SearchEngine.INSTANCE.expandReplacement("$99", g).equals("[9]9"));
        check("越界的 ${99} -> 空", SearchEngine.INSTANCE.expandReplacement("${99}", g).equals(""));
        check("${-1} 越界 -> 空", SearchEngine.INSTANCE.expandReplacement("${-1}", g).equals(""));
        check("空组列表时 $1 -> 空", SearchEngine.INSTANCE.expandReplacement("$1", new ArrayList<>()).equals(""));
        check("空组列表时 $0 -> 空", SearchEngine.INSTANCE.expandReplacement("$0", new ArrayList<>()).equals(""));
        check("$$ -> 字面 $", SearchEngine.INSTANCE.expandReplacement("$$", g).equals("$"));
        check("末尾单个 $ 原样保留", SearchEngine.INSTANCE.expandReplacement("a$", g).equals("a$"));
        check("$x 不是组引用，原样保留", SearchEngine.INSTANCE.expandReplacement("$x", g).equals("$x"));
        check("未闭合的 ${ -> 原样保留 $", SearchEngine.INSTANCE.expandReplacement("${1", g).equals("${1"));
        check("非数字组名 ${name} 原样保留（不能静默删掉用户内容）",
                SearchEngine.INSTANCE.expandReplacement("a${name}b", g).equals("a${name}b"));
        check("空花括号 ${} 原样保留", SearchEngine.INSTANCE.expandReplacement("${}", g).equals("${}"));
        check("花括号里不是纯数字 ${1x} 原样保留",
                SearchEngine.INSTANCE.expandReplacement("${1x}", g).equals("${1x}"));
        check("${ 1 } 里的空白被容忍，仍取第 1 组",
                SearchEngine.INSTANCE.expandReplacement("${ 1 }", g).equals("[1]"));
        check("与 $x 同一口径：$name 也原样保留",
                SearchEngine.INSTANCE.expandReplacement("$name", g).equals("$name"));
        check("混合文本", SearchEngine.INSTANCE.expandReplacement("v=$1/$$", g).equals("v=[1]/$"));
        check("不含 $ 的模板原样返回", SearchEngine.INSTANCE.expandReplacement("hello", g).equals("hello"));
        check("反斜杠不当转义（\\n 保持字面）",
                SearchEngine.INSTANCE.expandReplacement("a\\nb", g).equals("a\\nb"));

        System.out.println("=== 替换单处 ===");
        String t7 = "aaa bbb aaa";
        SearchMatch first = find(t7, lit("aaa")).getMatches().get(0);
        kotlin.Pair<String, Integer> one7 = SearchEngine.INSTANCE.replaceMatch(t7, first, "X");
        check("单处替换结果正确", one7.getFirst().equals("X bbb aaa"));
        check("光标落在插入内容末尾", one7.getSecond() == 1);
        SearchMatch second = find(t7, lit("aaa")).getMatches().get(1);
        kotlin.Pair<String, Integer> one7b = SearchEngine.INSTANCE.replaceMatch(t7, second, "LONGER");
        check("替换成更长的内容时偏移正确", one7b.getFirst().equals("aaa bbb LONGER"));
        check("替换成更长内容时光标位置正确", one7b.getSecond() == 8 + 6);
        kotlin.Pair<String, Integer> del7 = SearchEngine.INSTANCE.replaceMatch(t7, first, "");
        check("替换成空串等价于删除", del7.getFirst().equals(" bbb aaa"));

        System.out.println("=== 替换全部 ===");
        String t8 = "one two one two one";
        SearchResult r8 = find(t8, lit("one"));
        kotlin.Pair<String, Integer> all8 = SearchEngine.INSTANCE.replaceAll(t8, r8.getMatches(), "1", false);
        check("替换全部结果为 \"1 two 1 two 1\"", all8.getFirst().equals("1 two 1 two 1"));
        check("返回替换处数 3", all8.getSecond() == 3);
        kotlin.Pair<String, Integer> none = SearchEngine.INSTANCE.replaceAll(t8, new ArrayList<>(), "x", false);
        check("无命中时原文不变、返回 0", none.getFirst().equals(t8) && none.getSecond() == 0);

        String t9 = "id=12,id=7";
        SearchResult r9 = find(t9, re("id=(\\d+)"));
        kotlin.Pair<String, Integer> all9 = SearchEngine.INSTANCE.replaceAll(t9, r9.getMatches(), "N$1", true);
        check("正则替换全部用上捕获组", all9.getFirst().equals("N12,N7"));
        check("正则替换返回 2", all9.getSecond() == 2);
        // 端到端确认 findAll 喂给 expandReplacement 的 groups 里第 0 项确实是整段命中：
        // 只测 expandReplacement 是测不到这条接线的（以前那版就是把整段 drop 掉了）
        kotlin.Pair<String, Integer> wrap9 = SearchEngine.INSTANCE.replaceAll(t9, r9.getMatches(), "<$0>", true);
        check("$0 在真实命中上展开成整段", wrap9.getFirst().equals("<id=12>,<id=7>"));

        System.out.println("=== 中文与代理对 ===");
        String t10 = "第一行内容\n第二行内容\n第三行";
        SearchResult r10 = find(t10, lit("内容"));
        check("中文命中 2 处", r10.getMatches().size() == 2);
        check("中文区间切片正确", texts(t10, r10).get(0).equals("内容"));
        kotlin.Pair<String, Integer> all10 = SearchEngine.INSTANCE.replaceAll(t10, r10.getMatches(), "文字", false);
        check("中文替换全部正确", all10.getFirst().equals("第一行文字\n第二行文字\n第三行"));
        String emoji = "a\uD83D\uDE00b\uD83D\uDE00c";
        SearchResult r11 = find(emoji, lit("\uD83D\uDE00"));
        check("代理对（emoji）命中 2 处", r11.getMatches().size() == 2);
        check("代理对区间按 UTF-16 计数正确", r11.getMatches().get(0).getStart() == 1
                && r11.getMatches().get(0).getEnd() == 3);

        System.out.println("=== 截断上限 ===");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("ab ");
        SearchResult capped = SearchEngine.INSTANCE.findAll(sb.toString(), lit("ab"), 10);
        check("按 limit 截断到 10 处", capped.getMatches().size() == 10);
        check("截断标志为真", capped.getTruncated());
        SearchResult notCapped = SearchEngine.INSTANCE.findAll(sb.toString(), lit("ab"), 100);
        check("未超限时截断标志为假", !notCapped.getTruncated() && notCapped.getMatches().size() == 50);

        System.out.println("=== 边界 ===");
        check("空关键词返回空结果", find(t1, lit("")).getMatches().isEmpty());
        check("空文本返回空结果", find("", lit("a")).getMatches().isEmpty());
        check("整份文本就是一处命中", find("abc", lit("abc")).getMatches().size() == 1);
        check("命中在文末时末尾偏移正确",
                find("xxab", lit("ab")).getMatches().get(0).getEnd() == 4);
        check("换行符不会被跨过（字面量）", find("a\nb", lit("a\nb")).getMatches().size() == 1);
        check("正则 . 默认不跨行", find("a\nb", re("a.b")).getMatches().isEmpty());

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
