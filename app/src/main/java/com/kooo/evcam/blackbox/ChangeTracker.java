package com.kooo.evcam.blackbox;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只记变化的小账本：每一项记着上一个值，变了才给出一段「旧 → 新」，没变什么都不给。
 *
 * <h3>为什么要（2026-10-12）</h3>
 *
 * <p>找「车机把屏幕真的黑掉」对应哪个信号：显示屏状态（{@code screen.DisplayWatch}）和几个车辆信号
 * （{@code telemetry.EcarxSource}）每变一次进黑匣子一行，只记录，不改任何判断。这几样大多一天变不了几回，
 * 可里面有含义还没弄清的号，万一来回跳，一秒一行就会把黑匣子刷爆（512 KB 轮换，前面的时间线就没了）。
 * 所以每一项限量：{@code windowMs} 里最多给 {@code maxPerWindow} 段，多出来的只数；下一段带上
 * 「(+N skipped)」，窗口过了还没等到下一次变化的，{@link #drain} 补一段「最后记下的 → 现在」
 * （什么时候该补：{@link #nextDrainAt}）。</p>
 *
 * <p>纯 Java，不是线程安全的：每个用它的人只在自己的一条线程上用（{@code ChangeTrackerTest}）。</p>
 */
public final class ChangeTracker {

    /** 没见过的值（起点之前、刚出现的那一项）写成它。 */
    public static final String UNKNOWN = "?";

    private static final class Entry {
        /** 最新的值：比较用。 */
        String last;
        /** 最后写进一段的值：下一段的「旧」。 */
        String logged;
        long windowStartMs;
        /** 这个窗口里已经给了几段；0 = 窗口还没开始。 */
        int inWindow;
        /** 超量没给出的变化次数。 */
        int skipped;
    }

    private final int maxPerWindow;
    private final long windowMs;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public ChangeTracker(int maxPerWindow, long windowMs) {
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
    }

    /** 起点：记下这个值，不给段（起点那一行由调用的人自己写）。 */
    public void seed(String key, String value) {
        Entry e = entry(key);
        e.last = value;
        e.logged = value;
    }

    /**
     * 新读到一个值。
     *
     * @return 变了、而且这一项这个窗口还没超量：「旧 → 新」（中间有没给出的变化就带「(+N skipped)」）；
     *         没变、或者超量了（只数一次）：null
     */
    public String change(String key, String value, long nowMs) {
        Entry e = entry(key);
        if (same(e.last, value)) {
            return null;
        }
        e.last = value;
        if (e.inWindow == 0 || nowMs - e.windowStartMs >= windowMs) {
            e.windowStartMs = nowMs;
            e.inWindow = 0;
        }
        if (e.inWindow >= maxPerWindow) {
            e.skipped++;
            return null;
        }
        e.inWindow++;
        String text = arrow(e.logged, value, e.skipped);
        e.logged = value;
        e.skipped = 0;
        return text;
    }

    /**
     * 超量压下过、窗口已经过了的那几项：补一段「最后记下的 → 现在」（带「(+N skipped)」，N 是压下的次数），这一段算进新窗口。
     * 来回跳完停住的那个值，不等下一次变化也记得下来。补的这一段写下的时刻不是变化的时刻，调用的人那一行要标出来。
     *
     * @return 项 → 段，按第一次见到的先后；没有就是空的
     */
    public Map<String, String> drain(long nowMs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry e = item.getValue();
            if (e.skipped == 0 || nowMs - e.windowStartMs < windowMs) {
                continue;
            }
            out.put(item.getKey(), arrow(e.logged, e.last, e.skipped));
            e.logged = e.last;
            e.skipped = 0;
            e.windowStartMs = nowMs;
            e.inWindow = 1;
        }
        return out;
    }

    /**
     * 最早哪一刻有项可以补（{@link #drain}，和 {@code nowMs} 同一个时钟）；没有压下过的项是 -1。
     * 用的人掐着这个点补一次：下一次变化可能永远不来（下电时进程说没就没），停在哪儿也要记得下来。
     */
    public long nextDrainAt() {
        long next = -1;
        for (Entry e : entries.values()) {
            if (e.skipped > 0) {
                long at = e.windowStartMs + windowMs;
                if (next < 0 || at < next) {
                    next = at;
                }
            }
        }
        return next;
    }

    /** 这一项最新的值；没见过是 null。 */
    public String value(String key) {
        Entry e = entries.get(key);
        return e == null ? null : e.last;
    }

    /** 这一项不要了（比如那块屏被拿掉了）：连同压下的次数一起扔掉，不再补；再出现就是新的一项。账本不跟着越记越大。 */
    public void forget(String key) {
        entries.remove(key);
    }

    private Entry entry(String key) {
        Entry e = entries.get(key);
        if (e == null) {
            e = new Entry();
            entries.put(key, e);
        }
        return e;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 「旧 → 新」；{@code skipped} 是从上一段到这一段之间没有各自写出来的变化次数（补的那一段把最后一次也算进去）。 */
    static String arrow(String from, String to, int skipped) {
        return (from == null ? UNKNOWN : from) + " → " + (to == null ? UNKNOWN : to)
                + (skipped > 0 ? " (+" + skipped + " skipped)" : "");
    }
}
