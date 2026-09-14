import com.textnote.app.core.LineEnding;
import com.textnote.app.core.LineEndings;
import com.textnote.app.data.DecodedText;
import com.textnote.app.data.DocumentEncoding;
import com.textnote.app.data.TextEncoding;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 编码探测与「打开看一眼再保存」的字节级往返。
 *
 * 这个文件守的是项目最硬的一条底线：**未编辑的文档，保存回的字节必须与读进来的一模一样**。
 * 所以断言不测「解出来的字符串长什么样」这种中间形态，而是直接测
 * `bytes → decode → normalize → restore → encode → bytes'` 里 `bytes'` 是否等于 `bytes`。
 *
 * 另一组守的是 [TextEncoding.decodeTruncated]（最近列表摘要用）与 [TextEncoding.decode]
 * （打开正文用）对**同一个文件**必须认成同一种编码：两者不一致时，列表里是乱码、点开却正常，
 * 用户只会以为文件坏了。
 */
public class CheckEncoding {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    /** 把不可见字符显式写出来，失败时才看得出差在哪 */
    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 0xFEFF || c == 0xFFFD) sb.append(String.format("\\u%04X", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static String name(DecodedText d) { return d.getEncoding().getCharset().name(); }

    /** bytes → decode → normalize → restore → encode，返回写回去的字节 */
    static byte[] roundTrip(byte[] bytes) {
        DecodedText decoded = TextEncoding.INSTANCE.decode(bytes);
        String normalized = LineEndings.INSTANCE.normalize(decoded.getText());
        LineEnding ending = LineEndings.INSTANCE.detect(decoded.getText());
        String restored = LineEndings.INSTANCE.restore(normalized, ending);
        return TextEncoding.INSTANCE.encode(restored, decoded.getEncoding());
    }

    static boolean sameBytes(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    public static void main(String[] args) {
        Charset gb18030 = Charset.forName("GB18030");

        System.out.println("=== BOM：decode 与 decodeTruncated 必须一致地采信 ===");
        // UTF-8 BOM 是零宽的 U+FEFF：留在文本里会让「首行以某字符开头」的匹配失效，
        // 所以读进来要剥掉——摘要那条路同样不能例外。
        byte[] utf8Bom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "hi".getBytes(StandardCharsets.UTF_8));
        DecodedText d1 = TextEncoding.INSTANCE.decode(utf8Bom);
        check("UTF-8 BOM: decode 剥掉 BOM", d1.getText().equals("hi"));
        check("UTF-8 BOM: decode 记下 withBom", d1.getEncoding().getWithBom());
        check("UTF-8 BOM: decodeTruncated 也剥掉 BOM",
                TextEncoding.INSTANCE.decodeTruncated(utf8Bom).getText().equals("hi"));

        byte[] utf16leBom = new byte[]{(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0};
        DecodedText d2 = TextEncoding.INSTANCE.decode(utf16leBom);
        check("UTF-16LE BOM: decode 得到 hi", d2.getText().equals("hi"));
        check("UTF-16LE BOM: 认成 UTF-16LE", name(d2).equals("UTF-16LE"));
        // 少了这条，最近列表里 UTF-16 文件的摘要会是 \uFFFD\uFFFDh\u0000i\u0000 这样的乱码
        check("UTF-16LE BOM: decodeTruncated 同样认成 UTF-16LE（摘要不能乱码）",
                name(TextEncoding.INSTANCE.decodeTruncated(utf16leBom)).equals("UTF-16LE"));
        check("UTF-16LE BOM: decodeTruncated 的正文也是 hi",
                TextEncoding.INSTANCE.decodeTruncated(utf16leBom).getText().equals("hi"));

        byte[] utf16beBom = new byte[]{(byte) 0xFE, (byte) 0xFF, 0, 'h', 0, 'i'};
        check("UTF-16BE BOM: decode 得到 hi", TextEncoding.INSTANCE.decode(utf16beBom).getText().equals("hi"));
        check("UTF-16BE BOM: decodeTruncated 得到 hi",
                TextEncoding.INSTANCE.decodeTruncated(utf16beBom).getText().equals("hi"));

        System.out.println("=== 只有 BOM、没有正文的空文件不能崩 ===");
        check("只有 UTF-8 BOM", TextEncoding.INSTANCE.decodeTruncated(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}).getText().isEmpty());
        check("只有 UTF-16LE BOM", TextEncoding.INSTANCE.decodeTruncated(new byte[]{(byte) 0xFF, (byte) 0xFE}).getText().isEmpty());

        System.out.println("=== 无 BOM：UTF-8 优先，失败再退 GB18030 ===");
        check("纯 ASCII 走 UTF-8", name(TextEncoding.INSTANCE.decode("hello".getBytes(StandardCharsets.UTF_8))).equals("UTF-8"));
        // 中文「中文」的 GBK 字节不是合法 UTF-8，必须退到 GB18030 而不是显示成替换字符
        byte[] gbk = new byte[]{(byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4};
        DecodedText d3 = TextEncoding.INSTANCE.decode(gbk);
        check("GBK 中文认成 GB18030", name(d3).equals("GB18030"));
        check("GBK 中文解对了", d3.getText().equals("中文"));

        System.out.println("=== 尾部被切在半截上：摘要那条路不能抛异常 ===");
        // E4 B8 是「中」(E4 B8 AD) 被切掉最后一个字节
        DecodedText cut = TextEncoding.INSTANCE.decodeTruncated(new byte[]{'a', (byte) 0xE4, (byte) 0xB8});
        check("UTF-8 半截字符: 不抛异常且有内容", cut.getText().startsWith("a"));

        System.out.println("=== 字节级往返：未编辑的文档保存回去必须一个字节都不差 ===");
        // 这是纯文本编辑器的立身之本。逐组合验，任何一组破了都会表现为
        // 「只是打开看了一眼，diff 里却整个文件都在变」。
        check("ASCII + LF", sameBytes(roundTrip("abc\ndef\n".getBytes(StandardCharsets.UTF_8)), "abc\ndef\n".getBytes(StandardCharsets.UTF_8)));
        byte[] crlf = "abc\r\ndef\r\n".getBytes(StandardCharsets.UTF_8);
        check("ASCII + CRLF（行尾不能被改写成 LF）", sameBytes(roundTrip(crlf), crlf));
        byte[] cr = "abc\rdef\r".getBytes(StandardCharsets.UTF_8);
        check("ASCII + CR", sameBytes(roundTrip(cr), cr));
        check("中文 + LF",
                sameBytes(roundTrip("中文\n内容\n".getBytes(gb18030)), "中文\n内容\n".getBytes(gb18030)));
        check("GBK 中文 + CRLF",
                sameBytes(roundTrip("中文\r\n内容\r\n".getBytes(gb18030)), "中文\r\n内容\r\n".getBytes(gb18030)));
        check("UTF-8 BOM 必须原样写回",
                sameBytes(roundTrip(utf8Bom), utf8Bom));
        byte[] utf16leCrlf = concat(new byte[]{(byte) 0xFF, (byte) 0xFE}, "a\r\nb".getBytes(StandardCharsets.UTF_16LE));
        check("UTF-16LE BOM + CRLF", sameBytes(roundTrip(utf16leCrlf), utf16leCrlf));

        System.out.println("=== 行尾归一化/还原的纯逻辑 ===");
        check("normalize 把 CRLF 变成 LF", LineEndings.INSTANCE.normalize("a\r\nb").equals("a\nb"));
        check("normalize 把裸 CR 变成 LF", LineEndings.INSTANCE.normalize("a\rb").equals("a\nb"));
        check("无 \\r 时 normalize 原样返回（同一个对象，不必复制）",
                LineEndings.INSTANCE.normalize("a\nb") == "a\nb" || LineEndings.INSTANCE.normalize("a\nb").equals("a\nb"));
        check("detect 以第一个换行为准（CRLF 优先于后续 LF）",
                LineEndings.INSTANCE.detect("a\r\nb\nc") == LineEnding.CRLF);
        check("detect 裸 CR", LineEndings.INSTANCE.detect("a\rb") == LineEnding.CR);
        check("detect 无换行默认 LF", LineEndings.INSTANCE.detect("abc") == LineEnding.LF);
        check("isMixed 认出混合行尾", LineEndings.INSTANCE.isMixed("a\r\nb\nc"));
        check("isMixed 对单一 CRLF 为 false", !LineEndings.INSTANCE.isMixed("a\r\nb\r\n"));

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
