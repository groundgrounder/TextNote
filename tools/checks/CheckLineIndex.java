import com.textnote.app.core.LineEnding;
import com.textnote.app.core.LineEndings;
import com.textnote.app.core.LineIndex;
import kotlin.ranges.IntRange;

public class CheckLineIndex {

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

    public static void main(String[] args) {
        lineIndexBasics();
        lineIndexLarge();
        lineEndings();

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    static void lineIndexBasics() {
        LineIndex e = LineIndex.Companion.of("");
        check("空文本算 1 行", e.getLineCount() == 1);
        check("空文本起止都是 0", e.lineStart(0) == 0 && e.lineEnd(0) == 0);

        LineIndex t = LineIndex.Companion.of("a\nb");
        check("a\\nb 是两行", t.getLineCount() == 2);
        check("行0 起止 0..1", t.lineStart(0) == 0 && t.lineEnd(0) == 1);
        // "a\nb" 长度为 3：行1 从 2 开始、到 3 结束（末行的 end 就是文本长度）
        check("行1 起止 2..3", t.lineStart(1) == 2 && t.lineEnd(1) == 3);
        check("行0 含换行符止于 2", t.lineEndWithBreak(0) == 2);

        LineIndex tail = LineIndex.Companion.of("a\n");
        check("末尾换行多出一个空行", tail.getLineCount() == 2 && tail.lineStart(1) == 2 && tail.lineEnd(1) == 2);

        check("连续两个换行产生三行", LineIndex.Companion.of("a\n\n").getLineCount() == 3);

        check("offset 落在 \\n 上仍属上一行", t.lineOf(1) == 0);
        check("offset 2 落到第 1 行", t.lineOf(2) == 1);
        check("offset 0 是第 0 行", t.lineOf(0) == 0);
        check("offset 越界夹到末行", t.lineOf(9999) == 1);

        LineIndex h = LineIndex.Companion.of("hello\nworld");
        // "hello\nworld"：行1 起于 6，所以 offset 7 是第 1 列（0 起）
        check("columnOf(7) 是 1", h.columnOf(7) == 1);
        check("columnOf(6) 是 0", h.columnOf(6) == 0);
        IntRange r0 = h.lineRange(0);
        check("lineRange(0) 是 0..4", r0.getStart() == 0 && r0.getEndInclusive() == 4);
        IntRange r1 = h.lineRange(1);
        check("lineRange(1) 是 6..10", r1.getStart() == 6 && r1.getEndInclusive() == 10);
    }

    static void lineIndexLarge() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("line ").append(i).append(" with some content\n");
        String big = sb.toString();
        LineIndex idx = LineIndex.Companion.of(big);

        check("5000 行 + 末尾空行 = 5001", idx.getLineCount() == 5001);

        java.util.Random rnd = new java.util.Random(42);
        boolean ok = true;
        for (int k = 0; k < 3000; k++) {
            int off = rnd.nextInt(big.length());
            int naive = 0;
            for (int i = 0; i < off; i++) if (big.charAt(i) == '\n') naive++;
            if (idx.lineOf(off) != naive) ok = false;
        }
        check("lineOf 与朴素算法一致（3000 次随机采样）", ok);

        boolean contentOk = true;
        for (int l = 0; l < idx.getLineCount(); l++) {
            String seg = big.substring(idx.lineStart(l), idx.lineEnd(l));
            if (seg.indexOf('\n') >= 0) contentOk = false;
            if (l + 1 < idx.getLineCount() && idx.lineEnd(l) + 1 != idx.lineStart(l + 1)) contentOk = false;
        }
        check("每行区间不含 \\n 且首尾相接（全 5001 行）", contentOk);

        long t0 = System.nanoTime();
        for (int i = 0; i < 10; i++) LineIndex.Companion.of(big);
        long ms = (System.nanoTime() - t0) / 1000000 / 10;
        System.out.println("      参考：" + big.length() + " 字符构造一次索引约 " + ms + "ms");
    }

    static void lineEndings() {
        check("detect LF", LineEndings.INSTANCE.detect("a\nb") == LineEnding.LF);
        check("detect CRLF", LineEndings.INSTANCE.detect("a\r\nb") == LineEnding.CRLF);
        check("detect CR", LineEndings.INSTANCE.detect("a\rb") == LineEnding.CR);
        check("detect 无换行默认 LF", LineEndings.INSTANCE.detect("abc") == LineEnding.LF);

        check("isMixed 纯 LF 为假", !LineEndings.INSTANCE.isMixed("a\nb\nc"));
        check("isMixed CRLF 混 LF 为真", LineEndings.INSTANCE.isMixed("a\r\nb\nc"));
        check("isMixed CRLF 混 CR 为真", LineEndings.INSTANCE.isMixed("a\r\nb\rc"));
        check("isMixed 纯 CRLF 为假", !LineEndings.INSTANCE.isMixed("a\r\nb\r\n"));

        check("normalize CRLF", LineEndings.INSTANCE.normalize("a\r\nb").equals("a\nb"));
        check("normalize CR", LineEndings.INSTANCE.normalize("a\rb").equals("a\nb"));
        check("normalize 无 \\r 时原样返回", LineEndings.INSTANCE.normalize("a\nb").equals("a\nb"));

        // 往返性质：normalize → restore 必须回到原文，否则「打开再保存」会改掉全文件行尾
        String crlf = "第一行\r\n第二行\r\n第三行\r\n";
        check("CRLF 往返一致", LineEndings.INSTANCE.restore(LineEndings.INSTANCE.normalize(crlf), LineEnding.CRLF).equals(crlf));
        String cr = "第一行\r第二行\r";
        check("CR 往返一致", LineEndings.INSTANCE.restore(LineEndings.INSTANCE.normalize(cr), LineEnding.CR).equals(cr));
        String lf = "第一行\n第二行\n";
        check("LF 往返一致", LineEndings.INSTANCE.restore(LineEndings.INSTANCE.normalize(lf), LineEnding.LF).equals(lf));

        check("空串往返", LineEndings.INSTANCE.restore(LineEndings.INSTANCE.normalize(""), LineEnding.CRLF).equals(""));
        check("单个 LF 转 CRLF", LineEndings.INSTANCE.restore("\n", LineEnding.CRLF).equals("\r\n"));

        String normalized = LineEndings.INSTANCE.normalize(crlf);
        LineIndex idx = LineIndex.Companion.of(normalized);
        check("CRLF 归一化后 4 行", idx.getLineCount() == 4);
        check("CRLF 归一化后第 1 行内容正确",
                normalized.substring(idx.lineStart(1), idx.lineEnd(1)).equals("第二行"));
        check("CRLF 归一化后无残留 \\r", normalized.indexOf('\r') < 0);
    }
}
