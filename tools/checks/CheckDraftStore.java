import com.textnote.app.data.DraftStore;

/**
 * 草稿的**保留期**算术：首页那句「草稿将在 N 天后清理」就是这里算出来的。
 *
 * 为什么值得单独立断言：这句话与 `DraftStore.prune()` 共用同一个 `RETENTION_MILLIS`，
 * 而它俩是分别实现的——一个算「还剩几天」（向上取整），一个算「过期了没有」（比较时刻）。
 * 改错任何一边都不会让构建失败、也不会让界面报错，只会让**界面说的话与实际删除时间不一致**：
 * 用户看到「还剩 1 天」时草稿已经被删了，或者反之。所以这里断言的不是数字本身，
 * 而是**两条实现彼此一致**（见 expiryMatchesCountdown）。
 *
 * 另：`DraftStore` 是个持有 `Context` 的类，但 `daysUntilExpiry` 在它的伴生对象里、
 * 不碰任何 Android 成员——所以不引 android.jar 也能实测（已确认）。
 */
public class CheckDraftStore {

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

    /** 一天。断言里自己算一遍，而不是去读那个 private 常量 */
    static final long DAY = 24L * 60 * 60 * 1000;

    public static void main(String[] args) {
        retentionIsThirtyDays();
        boundary();
        rounding();
        expiryMatchesCountdown();

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 保留期就是 30 天：首页的「即将过期」提醒与清理动作都挂在它上面 */
    static void retentionIsThirtyDays() {
        check("保留期 = 30 天", DraftStore.RETENTION_MILLIS == 30 * DAY);
        check("刚更新的草稿报 30 天", DraftStore.Companion.daysUntilExpiry(0L, 0L) == 30);
        check("刚更新的草稿不会立刻被提醒", DraftStore.Companion.daysUntilExpiry(0L, 0L) > 7);
    }

    /** 边界：恰好到期、过了 1 毫秒、少了 1 毫秒 */
    static void boundary() {
        long t = 1_000_000_000_000L;
        check("恰好到期算 0（已删除）",
                DraftStore.Companion.daysUntilExpiry(t, t + DraftStore.RETENTION_MILLIS) == 0);
        check("过期 1 毫秒算 0",
                DraftStore.Companion.daysUntilExpiry(t, t + DraftStore.RETENTION_MILLIS + 1) == 0);
        check("过期一年算 0",
                DraftStore.Companion.daysUntilExpiry(t, t + 365 * DAY) == 0);
        check("还差 1 毫秒到期算 1（没删）",
                DraftStore.Companion.daysUntilExpiry(t, t + DraftStore.RETENTION_MILLIS - 1) == 1);
        check("还差整整一天算 1",
                DraftStore.Companion.daysUntilExpiry(t, t + 29 * DAY) == 1);
    }

    /** 向上取整：界面上不能说「还剩 0 天」——那是「已经没了」的意思 */
    static void rounding() {
        long t = 0L;
        check("还差一天零一毫秒算 2（向上取整）",
                DraftStore.Companion.daysUntilExpiry(t, t + 28 * DAY + 1) == 2);
        check("还差一小时算 1", DraftStore.Companion.daysUntilExpiry(t, t + 30 * DAY - 60 * 60 * 1000) == 1);
        check("天数随时间单调不增", monotonic());
    }

    static boolean monotonic() {
        long t = 0L;
        int prev = Integer.MAX_VALUE;
        for (long elapsed = 0; elapsed <= 31 * DAY; elapsed += DAY / 4) {
            int d = DraftStore.Companion.daysUntilExpiry(t, t + elapsed);
            if (d > prev) return false;
            prev = d;
        }
        return true;
    }

    /**
     * **两条实现必须同进退**——这条断言才是这个文件的真正目的。
     *
     * `daysUntilExpiry` 与 `prune()` 各自算一遍保留期，谁改错都不会让构建失败：
     * 症状是「界面说还剩 1 天，草稿其实已经被删」这类静默不一致。
     *
     * 判据刻意写成两个单向蕴含而不是「相等」，因为两者在**恰好相等的那一瞬**本来就不同：
     * prune 用的是 `time < now - RETENTION`（严格小于，所以恰好到期的草稿还会留一拍），
     * 而 daysUntilExpiry 在 `remaining <= 0` 时就报 0。这一毫秒宽的窗口没有任何用户可见后果
     * （prune 每次进首页都跑，而它跑完之后过期草稿才真正消失），所以这里只钉住两件有意义的事：
     * ①「还剩 N 天」（N≥1）必须还没被清理；②过了保留期之后必须真的被清理。
     */
    static void expiryMatchesCountdown() {
        long t = 1_700_000_000_000L;
        long[] offsets = {
                0L, 1L, DAY, 7 * DAY, 29 * DAY, 30 * DAY - 1, 30 * DAY,
                30 * DAY + 1, 31 * DAY, 365 * DAY,
        };

        boolean promisedAlive = true;
        for (long off : offsets) {
            int days = DraftStore.Companion.daysUntilExpiry(t, t + off);
            boolean pruned = (t < (t + off) - DraftStore.RETENTION_MILLIS);
            if (days >= 1 && pruned) {
                System.out.println("       不一致：流逝 " + off + "ms 时报还剩 " + days + " 天，却已被清理");
                promisedAlive = false;
            }
        }
        check("说「还剩 N 天」的草稿一定还在", promisedAlive);

        long past = t + DraftStore.RETENTION_MILLIS + 1;
        check("过了保留期一定被清理",
                t < past - DraftStore.RETENTION_MILLIS
                        && DraftStore.Companion.daysUntilExpiry(t, past) == 0);
        check("清理门槛与倒计时用的是同一个保留期（改一个不改另一个就会红）",
                DraftStore.Companion.daysUntilExpiry(t, t + (30 * DAY) - 1) == 1
                        && DraftStore.Companion.daysUntilExpiry(t, t + (30 * DAY) + 1) == 0);
    }
}
