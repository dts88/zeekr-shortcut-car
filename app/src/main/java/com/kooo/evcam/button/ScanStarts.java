package com.kooo.evcam.button;

/**
 * 这一阵开过几次扫描：安卓 30 秒里只让一个应用开 5 次，多了要么报错要么悄悄不扫（{@code AppScanStats}），
 * 那样聋得更久。这里留一次余量，到限了就等最早的那次过了窗口再开。只在蓝牙线程上用。
 */
final class ScanStarts {

    static final long WINDOW_MS = 30_000L;
    static final int MOST = 4;

    private final long[] at = new long[MOST];
    private int next;
    private int count;

    /** 现在开要再等多久（毫秒）；0 是马上能开。时刻是含深睡的。 */
    long waitMs(long now) {
        if (count < MOST) {
            return 0;
        }
        return Math.max(0, at[next] + WINDOW_MS - now);
    }

    /** 开了一次。 */
    void record(long now) {
        at[next] = now;
        next = (next + 1) % MOST;
        count = Math.min(count + 1, MOST);
    }

    /** 刚才那次没开成，系统也不算。 */
    void forgetLast() {
        if (count > 0) {
            next = (next + MOST - 1) % MOST;
            count--;
        }
    }
}
