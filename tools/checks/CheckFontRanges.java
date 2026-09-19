import com.textnote.app.data.EditorFontRanges;

import java.util.HashSet;
import java.util.Set;

/**
 * 字号与行距的**候选档位**：设置页的滑动条滑的就是它们的下标，`SettingsRepository`
 * 读回设置时又用 `coerce*` 把值钳进这张表。
 *
 * 为什么值得断言：这两处（写入时的钳制、UI 生成选项）共用同一份表，但分别实现。
 * 一旦「滑得到的档位」与「存得下的档位」对不上，症状是**滑块弹回去**——用户松开手，
 * 值跳回别处，而日志里什么都没有。所以这里钉三条：
 * 1. 候选值 coerce 后等于自身（幂等）——这是「滑得到=存得下」的充分条件；
 * 2. 候选集非空、严格递增、无重复——重复项会让滑动条出现两个位置对应同一个值；
 * 3. **默认值必须在候选集里**——默认值不在表里的话，`SIZES.indexOf(default)` 返回 -1，
 *    设置页那边 `coerceAtLeast(0)` 会把它悄悄显示成第一档（15 → 12），用户一进设置页就看见
 *    一个不是当前值的滑块。
 *
 * `EditorFontRanges` 没有任何 Android 依赖，不引 android.jar 也能实测（已确认）。
 */
public class CheckFontRanges {

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
        sizeCoercion();
        lineHeightCoercion();
        sizeTable();
        lineHeightTable();
        defaultsAreInTable();

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 每个能滑到的字号，存下去必须还是它自己 */
    static void sizeCoercion() {
        int[] sizes = EditorFontRanges.INSTANCE.getSIZES();
        boolean idempotent = true;
        for (int v : sizes) {
            if (EditorFontRanges.INSTANCE.coerceSize(v) != v) {
                System.out.println("       字号 " + v + " 被改写成了 "
                        + EditorFontRanges.INSTANCE.coerceSize(v));
                idempotent = false;
            }
        }
        check("每个候选字号 coerce 后等于自身", idempotent);

        // 越界与非法值都必须落到默认值，而不是夹到最近的一档
        // （夹到最近的话，用户在别处手改过的 prefs 会静默变成另一个字号）
        check("非法字号回到默认",
                EditorFontRanges.INSTANCE.coerceSize(0) == EditorFontRanges.DEFAULT_SIZE);
        check("过大字号回到默认",
                EditorFontRanges.INSTANCE.coerceSize(999) == EditorFontRanges.DEFAULT_SIZE);
        check("负数回到默认",
                EditorFontRanges.INSTANCE.coerceSize(-14) == EditorFontRanges.DEFAULT_SIZE);
        check("候选之间的空隙也回到默认（17 不在表里）",
                EditorFontRanges.INSTANCE.coerceSize(17) == EditorFontRanges.DEFAULT_SIZE);
    }

    /** 行距是 Float，而它要经过 SharedPreferences 的 Float 存取往返 */
    static void lineHeightCoercion() {
        float[] heights = EditorFontRanges.INSTANCE.getLINE_HEIGHTS();
        boolean idempotent = true;
        for (float v : heights) {
            if (Math.abs(EditorFontRanges.INSTANCE.coerceLineHeight(v) - v) > 1e-6f) {
                System.out.println("       行距 " + v + " 被改写成了 "
                        + EditorFontRanges.INSTANCE.coerceLineHeight(v));
                idempotent = false;
            }
        }
        check("每个候选行距 coerce 后等于自身", idempotent);

        check("非法行距回到默认",
                EditorFontRanges.INSTANCE.coerceLineHeight(1.15f) == EditorFontRanges.DEFAULT_LINE_HEIGHT);
        check("0 行距回到默认",
                EditorFontRanges.INSTANCE.coerceLineHeight(0f) == EditorFontRanges.DEFAULT_LINE_HEIGHT);
        check("负行距回到默认",
                EditorFontRanges.INSTANCE.coerceLineHeight(-2f) == EditorFontRanges.DEFAULT_LINE_HEIGHT);

        // 浮点带来的极小偏差必须仍然命中（1.4f 存成 Float 再读回来就是它自己，
        // 但用户手改 prefs 或将来换成 Double 存储时会出现 1.3999999 这种情况）
        float nearly = EditorFontRanges.DEFAULT_LINE_HEIGHT + 0.0000001f;
        check("临界抖动仍命中默认档（容差不是精确相等）",
                EditorFontRanges.INSTANCE.coerceLineHeight(nearly)
                        == EditorFontRanges.DEFAULT_LINE_HEIGHT);
    }

    static void sizeTable() {
        int[] sizes = EditorFontRanges.INSTANCE.getSIZES();
        check("字号表非空", sizes.length > 0);

        boolean ascending = true;
        for (int i = 1; i < sizes.length; i++) {
            if (sizes[i] <= sizes[i - 1]) ascending = false;
        }
        check("字号表严格递增", ascending);

        Set<Integer> seen = new HashSet<>();
        boolean unique = true;
        for (int v : sizes) {
            if (!seen.add(v)) unique = false;
        }
        check("字号表无重复", unique);

        boolean positive = true;
        for (int v : sizes) {
            if (v <= 0) positive = false;
        }
        check("字号都是正数", positive);
    }

    static void lineHeightTable() {
        float[] heights = EditorFontRanges.INSTANCE.getLINE_HEIGHTS();
        check("行距表非空", heights.length > 0);

        boolean ascending = true;
        boolean sane = true;
        for (int i = 0; i < heights.length; i++) {
            // 行高必须 >= 1：小于 1 的行距会让上下两行叠在一起（可读性直接崩）
            if (heights[i] < 1f) sane = false;
            if (i > 0 && heights[i] <= heights[i - 1]) ascending = false;
        }
        check("行距表严格递增", ascending);
        check("行距都 >= 1.0（小于 1 会叠行）", sane);
    }

    /** 默认值不在表里时，设置页的滑块会把当前值显示成第一档 */
    static void defaultsAreInTable() {
        int defSize = EditorFontRanges.DEFAULT_SIZE;
        boolean sizeFound = false;
        for (int v : EditorFontRanges.INSTANCE.getSIZES()) {
            if (v == defSize) sizeFound = true;
        }
        check("默认字号在候选表里", sizeFound);

        float defHeight = EditorFontRanges.DEFAULT_LINE_HEIGHT;
        boolean heightFound = false;
        for (float v : EditorFontRanges.INSTANCE.getLINE_HEIGHTS()) {
            if (Math.abs(v - defHeight) < 1e-6f) heightFound = true;
        }
        check("默认行距在候选表里", heightFound);
    }
}
