import com.textnote.app.data.AppLanguage;

import java.util.HashSet;
import java.util.Set;

/**
 * 界面语言标签的**归一与往返**。
 *
 * 这段逻辑小，但它有三重身份，任何一处错都不报错、只表现成「语言选项坏了」：
 * - **持久化**：`AppLocaleStore` 按 `tag` 存进 SharedPreferences，下次启动按 tag 还原；
 * - **系统同步**：API 33+ 要把它交给 `LocaleManager`（系统「按应用语言」），
 *   而系统回传的是 `zh-Hans-CN` / `en-US` 这种**带区域**的形式，必须能解析回同一个枚举；
 * - **迁移**：旧版本存过 `zh-CN` / `zh-TW`，系统侧还可能留着本版本已经没有的语言。
 *
 * 所以这里断言的重点是**往返**（`fromTag(tag) == 本项`）与**兜底**（认不出的一律「跟随系统」），
 * 而不是逐个输入的正确性。`AppLanguage` 不依赖 Android，不引 android.jar 也能实测（已确认）。
 */
public class CheckAppLanguage {

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

    static AppLanguage from(String tag) {
        return AppLanguage.Companion.fromTag(tag);
    }

    public static void main(String[] args) {
        roundTrip();
        systemFallback();
        legacyTags();
        chineseVariants();
        invariants();

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 最重要的一条：存下去的 tag 必须能还原成同一项，否则下次启动语言会漂 */
    static void roundTrip() {
        boolean ok = true;
        for (AppLanguage lang : AppLanguage.values()) {
            AppLanguage back = from(lang.getTag());
            if (back != lang) {
                System.out.println("       " + lang + " 的 tag「" + lang.getTag() + "」还原成了 " + back);
                ok = false;
            }
        }
        check("每种语言的 tag 都能还原回自己", ok);
    }

    /** 认不出来的一律「跟随系统」——宁可跟系统，也不要随机挑一种语言 */
    static void systemFallback() {
        check("空串 -> 跟随系统", from("") == AppLanguage.SYSTEM);
        check("null -> 跟随系统", from(null) == AppLanguage.SYSTEM);
        check("纯空白 -> 跟随系统", from("   ") == AppLanguage.SYSTEM);
        check("und（未知语言）-> 跟随系统", from("und") == AppLanguage.SYSTEM);
        check("本版本没有的语言（ja）-> 跟随系统", from("ja") == AppLanguage.SYSTEM);
        check("本版本没有的语言（de-DE）-> 跟随系统", from("de-DE") == AppLanguage.SYSTEM);
        check("跟随系统的 tag 是空串（getResources 靠它判「不包装」）",
                AppLanguage.SYSTEM.getTag().isEmpty());
    }

    /**
     * 旧版本与本机遗留的写法都要接得住。
     *
     * `zh_CN` 是下划线形式（不是合法的 BCP 47，但历史上被存过）；`zh-Hans-CN` / `en-US`
     * 是系统回传的带区域形式——解析不回同一个枚举，就会出现「系统里选了中文、
     * 应用内选择器显示跟随系统」这种两处不一致。
     */
    static void legacyTags() {
        check("zh_CN（下划线）-> 简体", from("zh_CN") == AppLanguage.CHINESE_SIMPLIFIED);
        check("zh-Hans-CN（带区域）-> 简体", from("zh-Hans-CN") == AppLanguage.CHINESE_SIMPLIFIED);
        check("en-US（带区域）-> 英文", from("en-US") == AppLanguage.ENGLISH);
        check("la-Latn -> 拉丁", from("la-Latn") == AppLanguage.LATIN);
    }

    /**
     * 中文按**地区**推断繁体，这是旧版本标签（`zh-CN` / `zh-TW` 那套）的迁移路径。
     *
     * 中国台湾 / 中国香港 / 中国澳门三地都要落到繁体：资源目录用的是 `zh-Hant`，
     * 一次覆盖三地；掉回简体的话这三个地区的用户会看到简体界面。
     */
    static void chineseVariants() {
        check("zh-CN -> 简体", from("zh-CN") == AppLanguage.CHINESE_SIMPLIFIED);
        check("zh-SG -> 简体", from("zh-SG") == AppLanguage.CHINESE_SIMPLIFIED);
        check("zh-TW -> 繁体", from("zh-TW") == AppLanguage.CHINESE_TRADITIONAL);
        check("zh-HK -> 繁体", from("zh-HK") == AppLanguage.CHINESE_TRADITIONAL);
        check("zh-MO -> 繁体", from("zh-MO") == AppLanguage.CHINESE_TRADITIONAL);
        check("zh-Hant（显式 script）-> 繁体", from("zh-Hant") == AppLanguage.CHINESE_TRADITIONAL);
        check("zh-Hant-TW（script 优先于地区）-> 繁体",
                from("zh-Hant-TW") == AppLanguage.CHINESE_TRADITIONAL);
        check("zh-Hans-HK（script 优先于地区）-> 简体",
                from("zh-Hans-HK") == AppLanguage.CHINESE_SIMPLIFIED);
    }

    /** tag 与自称都要唯一：tag 撞了持久化只能还原出一种，自称撞了选择列表分不清 */
    static void invariants() {
        Set<String> tags = new HashSet<>();
        Set<String> endonyms = new HashSet<>();
        boolean tagsUnique = true;
        boolean endonymsUnique = true;
        boolean endonymsPresent = true;

        for (AppLanguage lang : AppLanguage.values()) {
            if (!tags.add(lang.getTag())) tagsUnique = false;
            // 「跟随系统」的自称是空的——它的显示文案走资源（要跟着界面语言翻译），
            // 所以只有非 SYSTEM 的项参与「自称唯一」的判定
            if (lang != AppLanguage.SYSTEM) {
                if (lang.getEndonym().isEmpty()) endonymsPresent = false;
                if (!endonyms.add(lang.getEndonym())) endonymsUnique = false;
            }
        }
        check("tag 唯一", tagsUnique);
        check("非「跟随系统」的语言都有自称", endonymsPresent);
        check("自称唯一（选择列表里两项同名就分不清）", endonymsUnique);

        // 只有「跟随系统」允许空 tag：多一个空的，getResources() 的判据就被破坏
        int emptyTags = 0;
        for (AppLanguage lang : AppLanguage.values()) {
            if (lang.getTag().isEmpty()) emptyTags++;
        }
        check("只有「跟随系统」的 tag 是空串", emptyTags == 1);
    }
}
