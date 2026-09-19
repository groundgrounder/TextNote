import com.textnote.app.core.BinarySniff;
import com.textnote.app.data.DecodedText;
import com.textnote.app.data.TextEncoding;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 二进制防线：`bytes → decode → looksBinary`。
 *
 * 与生产代码**同序**（`DocumentRepository.openDocument` 就是先 `TextEncoding.decode`
 * 再 `BinarySniff.looksBinary`），所以这里不测字节层的启发式，而是测这条实际跑的链路。
 *
 * ## 两个方向都要验（这是本项目踩过的坑）
 *
 * 「该拒的拒了」只是一半。把一份**正常文本**判成二进制更糟：用户会彻底打不开它，
 * 而且没有任何自救手段。所以下面每一条「拒」的用例都配了一条「不许拒」的：
 * 特别是 **带 BOM 的 UTF-16**——它在字节层每隔一个字节就是 `0x00`，判据一旦落在字节上
 * 就会把它判成二进制。
 */
public class CheckBinarySniff {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    /** 把不可见字符显式写出来，失败时才看得出差在哪 */
    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(s.length(), 40);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 0xFFFD) sb.append(String.format("\\u%04X", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** 生产路径：先解码，再判。返回值同时用于失败时打印。 */
    static boolean refused(byte[] bytes) {
        DecodedText decoded = TextEncoding.INSTANCE.decode(bytes);
        return BinarySniff.INSTANCE.looksBinary(decoded.getText());
    }

    static byte[] b(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    /** BOM + 用某种编码写的正文 */
    static byte[] withBom(int[] bom, String text, Charset charset) {
        return join(b(bom), text.getBytes(charset));
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static byte[] repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) sb.append(unit);
        return utf8(sb.toString());
    }

    static void expectRefused(String name, byte[] bytes) {
        boolean got = refused(bytes);
        if (!got) {
            System.out.println("       解码后前 40 字符：" + esc(TextEncoding.INSTANCE.decode(bytes).getText()));
        }
        check(name, got);
    }

    static void expectAccepted(String name, byte[] bytes) {
        boolean got = refused(bytes);
        if (got) {
            System.out.println("       解码后前 40 字符：" + esc(TextEncoding.INSTANCE.decode(bytes).getText()));
        }
        check(name, !got);
    }

    public static void main(String[] args) {
        binary();
        text();
        boundary();
        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    static void binary() {
        System.out.println("=== 该拒：常见二进制格式 ===");
        // 各格式的真实文件头，后面跟几个 NUL
        expectRefused("PNG", b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x01, 0x00));
        expectRefused("JPEG", join(b(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01),
                utf8("some payload"), b(0x00, 0x00, 0x00, 0xFF, 0xD9)));
        expectRefused("ZIP / APK / docx", join(b(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00),
                utf8("PK\u0001\u0002"), b(0x00, 0x00, 0x00, 0x00, 0x08, 0x00)));
        expectRefused("ELF 可执行文件", b(0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00));
        expectRefused("MP3 帧头 + 数据", join(b(0xFF, 0xFB, 0x90, 0x00), utf8("audio"), b(0x00, 0x00, 0x00)));
        expectRefused("单个 NUL 也算", b(0x00));

        // 没有 NUL 的二进制（部分压缩流 / 加密数据）：靠控制字符占比兜住
        StringBuilder none = new StringBuilder();
        for (int i = 0; i < 100; i++) none.append('\u0001').append('\u001B').append('\u0007');
        for (int i = 0; i < 100; i++) none.append('a');
        expectRefused("没有 NUL、但控制字符占 75%", utf8(none.toString()));
    }

    static void text() {
        System.out.println("=== 不许拒：正常文本 ===");
        expectAccepted("纯 ASCII 源码", utf8("fun main() {\n\tval x = 1\n}\n"));
        expectAccepted("UTF-8 中文", utf8("# 标题\n\n中文正文，含全角标点（）、。\n"));
        expectAccepted("GB18030 中文", "中文正文，含全角标点（）、。\n".getBytes(Charset.forName("GB18030")));
        expectAccepted("UTF-8 + BOM", withBom(new int[]{0xEF, 0xBB, 0xBF}, "带 BOM 的文本\n", StandardCharsets.UTF_8));
        // ⚠️ 这两条是核心：UTF-16 的 ASCII 文本在**字节层**每隔一个字节就是 0x00，
        // 判据一旦落在字节上就会把整份文档拒掉。判在解码后就不会。
        expectAccepted("UTF-16LE + BOM（字节层满是 0x00）",
                withBom(new int[]{0xFF, 0xFE}, "hello world\n", StandardCharsets.UTF_16LE));
        expectAccepted("UTF-16BE + BOM（字节层满是 0x00）",
                withBom(new int[]{0xFE, 0xFF}, "hello world\n", StandardCharsets.UTF_16BE));
        expectAccepted("等宽缩进：制表符占三成",
                repeat("\t\t\ta\n", 90)); // 制表符不算可疑字符
        expectAccepted("日志里的少量 ESC 序列", join(
                repeat("2026-09-18 INFO ok\n", 40), b(0x1B, 0x5B, 0x33, 0x31, 0x6D), utf8("colored\n")));
        expectAccepted("少量替换字符（局部乱码）", utf8("正常内容".repeat(40) + "\uFFFD\uFFFD\uFFFD\n"));
        expectAccepted("换页符分页的老式文本", utf8("page one\n\u000Cpage two\n"));
        expectAccepted("空文件", new byte[0]);
        expectAccepted("单个字符", utf8("a"));
        expectAccepted("CRLF 行尾", utf8("a\r\nb\r\n"));
    }

    static void boundary() {
        System.out.println("=== 边界：阈值与采样窗口 ===");
        // 恰好 20% 不算可疑（判据是「严格大于」），20.5% 才算。
        // 只验一侧的防线等于没验，这里两侧都钉住。
        StringBuilder at = new StringBuilder();
        for (int i = 0; i < 40; i++) at.append('\u0001');
        for (int i = 0; i < 160; i++) at.append('a');
        expectAccepted("控制字符恰好 20%", utf8(at.toString()));

        StringBuilder over = new StringBuilder();
        for (int i = 0; i < 41; i++) over.append('\u0001');
        for (int i = 0; i < 159; i++) over.append('a');
        expectRefused("控制字符 20.5%", utf8(over.toString()));

        // 采样窗口只有前 BinarySniff.SNIFF_CHARS 个字符：窗口之外的 NUL 看不见。
        // 这是刻意的取舍（代价与体积解耦），写成断言是为了它**别被当成 bug 改回来**。
        StringBuilder late = new StringBuilder();
        for (int i = 0; i < BinarySniff.SNIFF_CHARS + 1000; i++) late.append('a');
        late.append('\u0000');
        expectAccepted("NUL 出现在采样窗口之外（已知取舍）", utf8(late.toString()));

        // 无 BOM 的 UTF-16 会被拒。那种文件本来也只能显示成「a\0b\0c」的乱码，
        // 所以拒掉是改善而不是退步——界面上的提示里写了怎么自救（转成 UTF-8）。
        expectRefused("无 BOM 的 UTF-16（已知取舍：先转 UTF-8）",
                "hello world\n".getBytes(StandardCharsets.UTF_16LE));
    }
}
