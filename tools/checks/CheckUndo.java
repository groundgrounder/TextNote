import com.textnote.app.core.TextEdit;
import com.textnote.app.core.UndoOutcome;
import com.textnote.app.core.UndoStack;

public class CheckUndo {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("PASS  " + name);
        } else {
            fail++;
            System.out.println("FAIL  " + name);
        }
    }

    static TextEdit ed(int start, String removed, String inserted) {
        return new TextEdit(start, removed, inserted);
    }

    public static void main(String[] args) {
        diffBasics();
        diffOverlapSafety();
        applyBasics();
        roundTrip();
        coalesceTyping();
        coalesceBackspace();
        coalesceForwardDelete();
        deleteDirectionsAreNotMixed();
        noCoalesceAcrossKinds();
        noCoalesceAfterPause();
        noCoalesceWhenDisabled();
        redoClearedOnNewEdit();
        entryCap();
        charCap();
        desyncClearsStack();
        caretPositions();

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    static void diffBasics() {
        TextEdit a = UndoStack.diff("abc", "abcX");
        check("末尾插入：起点在 3", a.getStart() == 3);
        check("末尾插入：删空、插 X", a.getRemoved().isEmpty() && a.getInserted().equals("X"));

        TextEdit b = UndoStack.diff("abcX", "abc");
        check("末尾删除：删 X、插空", b.getRemoved().equals("X") && b.getInserted().isEmpty());

        TextEdit c = UndoStack.diff("abc", "aXc");
        check("中间替换：起点在 1", c.getStart() == 1);
        check("中间替换：b -> X", c.getRemoved().equals("b") && c.getInserted().equals("X"));

        TextEdit d = UndoStack.diff("", "hello");
        check("空文本插入全部", d.getStart() == 0 && d.getRemoved().isEmpty() && d.getInserted().equals("hello"));

        TextEdit e = UndoStack.diff("hello", "");
        check("全删：删 hello", e.getRemoved().equals("hello") && e.getInserted().isEmpty());

        // 公共前缀优先，所以是「在 3 处补 aaa」而不是「整段替换」
        TextEdit f = UndoStack.diff("aaa", "aaaaaa");
        check("重复文本按前缀切分", f.getStart() == 3 && f.getRemoved().isEmpty() && f.getInserted().equals("aaa"));

        TextEdit g = UndoStack.diff("ab", "ba");
        check("完全不同的两字符整段替换", g.getStart() == 0 && g.getRemoved().equals("ab") && g.getInserted().equals("ba"));
    }

    /**
     * 前缀与后缀不能重叠，否则会算出负区间。
     * 这几个用例是「短字符串 + 增删一个字符」，正是重叠最容易发生的地方。
     */
    static void diffOverlapSafety() {
        TextEdit a = UndoStack.diff("a", "aa");
        check("a -> aa 不重叠", a.getStart() == 1 && a.getRemoved().isEmpty() && a.getInserted().equals("a"));

        TextEdit b = UndoStack.diff("aa", "a");
        check("aa -> a 不重叠", b.getStart() == 1 && b.getRemoved().equals("a") && b.getInserted().isEmpty());

        TextEdit c = UndoStack.diff("abab", "ababab");
        check("abab -> ababab 前缀优先", c.getStart() == 4 && c.getInserted().equals("ab"));
    }

    static void applyBasics() {
        check("正向应用插入", UndoStack.apply("abc", ed(3, "", "X"), true).equals("abcX"));
        check("反向应用撤销插入", UndoStack.apply("abcX", ed(3, "", "X"), false).equals("abc"));
        check("反向应用还原替换", UndoStack.apply("aXc", ed(1, "b", "X"), false).equals("abc"));
        check("正向应用替换", UndoStack.apply("abc", ed(1, "b", "X"), true).equals("aXc"));
        check("中间位置的差分不影响两端", UndoStack.apply("hello world", ed(5, "", ","), true).equals("hello, world"));
    }

    static void roundTrip() {
        UndoStack s = new UndoStack();
        check("空栈不可撤销", !s.getCanUndo());
        check("空栈不可重做", !s.getCanRedo());

        s.record("abc", "abcX", 0L);
        check("记录后可撤销", s.getCanUndo());
        check("记录后不可重做", !s.getCanRedo());

        UndoOutcome u = s.undo("abcX");
        check("撤销得到原文", u != null && u.getText().equals("abc"));
        check("撤销后可重做", s.getCanRedo());
        check("撤销后不可再撤销", !s.getCanUndo());

        UndoOutcome r = s.redo("abc");
        check("重做回到改动后", r != null && r.getText().equals("abcX"));
        check("重做后不可再重做", !s.getCanRedo());

        s.record("abcX", "abcXY", 10L);
        check("新改动后仍可重做被清掉", !s.getCanRedo());
    }

    /** 连打三个字应当只算一步——撤销一次退掉整段输入 */
    static void coalesceTyping() {
        UndoStack s = new UndoStack();
        s.record("", "a", 0L);
        s.record("a", "ab", 10L);
        s.record("ab", "abc", 20L);
        check("连续打字合并成 1 步", s.getUndoDepth() == 1);

        UndoOutcome u = s.undo("abc");
        check("一次撤销退掉整段输入", u != null && u.getText().isEmpty());
    }

    static void coalesceBackspace() {
        UndoStack s = new UndoStack();
        s.record("abc", "ab", 0L);
        s.record("ab", "a", 10L);
        check("连续退格合并成 1 步", s.getUndoDepth() == 1);

        UndoOutcome u = s.undo("a");
        check("一次撤销恢复整段删除", u != null && u.getText().equals("abc"));
    }

    /**
     * 连按 **Delete**（前向删除）：位置不动，新删掉的内容接在已删内容之后。
     *
     * 与退格那一条是两回事——退格是「先删的在右」，前向删除是「先删的在左」，
     * 拼接顺序相反。少一条分支的症状是「连按 20 次 Delete 要按 20 次撤销」，
     * 顺序写反的症状更严重：撤销会把文本恢复成**倒序**（静默损坏正文）。
     */
    static void coalesceForwardDelete() {
        UndoStack s = new UndoStack();
        s.record("abc", "bc", 0L);
        s.record("bc", "c", 10L);
        check("连续 Delete 合并成 1 步", s.getUndoDepth() == 1);

        UndoOutcome u = s.undo("c");
        check("一次撤销恢复两个字符", u != null && u.getText().equals("abc"));
        // 顺序断言不能省：只比长度的话 `cb` 也算过
        check("恢复的是原顺序而不是倒序", u != null && u.getText().equals("abc"));
    }

    /** 前向删除与退格的**方向**都必须对：同一份文本，两种删法各自撤回到原文 */
    static void deleteDirectionsAreNotMixed() {
        UndoStack back = new UndoStack();
        back.record("abcdef", "abcde", 0L);
        back.record("abcde", "abcd", 10L);
        UndoOutcome b = back.undo("abcd");
        check("退格方向：撤销回到 abcdef", b != null && b.getText().equals("abcdef"));

        UndoStack fwd = new UndoStack();
        fwd.record("abcdef", "bcdef", 0L);
        fwd.record("bcdef", "cdef", 10L);
        UndoOutcome f = fwd.undo("cdef");
        check("前向方向：撤销回到 abcdef", f != null && f.getText().equals("abcdef"));
    }

    /** 删一个字再打一个字是两次意图，不能并成一步 */
    static void noCoalesceAcrossKinds() {
        UndoStack s = new UndoStack();
        s.record("abc", "ab", 0L);
        s.record("ab", "abX", 10L);
        check("删除后插入不合并", s.getUndoDepth() == 2);

        UndoOutcome u = s.undo("abX");
        check("先撤销插入", u != null && u.getText().equals("ab"));
    }

    static void noCoalesceAfterPause() {
        UndoStack s = new UndoStack();
        s.record("", "a", 0L);
        s.record("a", "ab", 5000L);
        check("超过停顿阈值另起一步", s.getUndoDepth() == 2);
    }

    static void noCoalesceWhenDisabled() {
        UndoStack s = new UndoStack();
        s.record("", "a", 0L);
        s.record("a", "ab", 10L, false);
        check("coalesce=false 不合并", s.getUndoDepth() == 2);
    }

    static void redoClearedOnNewEdit() {
        UndoStack s = new UndoStack();
        s.record("", "a", 0L);
        s.undo("a");
        check("撤销后有重做可用", s.getCanRedo());
        s.record("a", "aX", 5000L);
        check("产生新改动后重做分支作废", !s.getCanRedo());
    }

    static void entryCap() {
        UndoStack s = new UndoStack(3, 1_000_000, 600L);
        // 间隔拉大，避免被合并
        for (int i = 0; i < 6; i++) {
            s.record("x" + i, "x" + i + "y", i * 1000L);
        }
        check("条目数不超过上限 3", s.getUndoDepth() == 3);
        check("淘汰后仍可撤销", s.getCanUndo());

        // 被淘汰的是最老的那条，所以只能退回最近 3 步
        UndoOutcome u1 = s.undo("x5y");
        check("最近一步可正常撤销", u1 != null && u1.getText().equals("x5"));
    }

    static void charCap() {
        // 总字符预算 10，每次插入 4 字符（间隔大、不合并）
        UndoStack s = new UndoStack(100, 10, 600L);
        String text = "";
        for (int i = 0; i < 5; i++) {
            String next = text + "abcd";
            s.record(text, next, i * 1000L);
            text = next;
        }
        check("字符预算把深度压到 2", s.getUndoDepth() <= 2);
        check("字符预算下仍可撤销", s.getCanUndo());
    }

    /** 文本与栈对不上时不能硬改，否则会静默损坏正文 */
    static void desyncClearsStack() {
        UndoStack s = new UndoStack();
        s.record("abc", "abcX", 0L);
        UndoOutcome u = s.undo("WRONG");
        check("对不上时撤销返回 null", u == null);
        check("对不上时清空整个栈", !s.getCanUndo() && !s.getCanRedo());
    }

    static void caretPositions() {
        UndoStack s = new UndoStack();
        s.record("abc", "abcX", 0L);
        UndoOutcome u = s.undo("abcX");
        // 插入起点是 3、删掉的是空串，撤销后光标回到 3
        check("撤销后光标在被恢复内容末尾", u != null && u.getCaret() == 3);
        UndoOutcome r = s.redo("abc");
        check("重做后光标在插入内容末尾", r != null && r.getCaret() == 4);

        UndoStack s2 = new UndoStack();
        s2.record("hello world", "hello", 0L);
        UndoOutcome u2 = s2.undo("hello");
        // 删除起点 5、删掉 " world"（6 字符），撤销后光标应在 11
        check("撤销删除后光标在恢复内容末尾", u2 != null && u2.getCaret() == 11);
        check("撤销删除恢复了内容", u2 != null && u2.getText().equals("hello world"));
    }
}
