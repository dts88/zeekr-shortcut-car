package com.kooo.evcam.sound;

/**
 * 稀疏的车速读数 → 连续的车速和加速度（模拟声浪用）。车速和油门一样是一级一级来的（推送、快读都要隔一阵才变一次）。
 * 直接用的话，转速跟着车速一顿一顿地走（增压器的啸叫听得出台阶）；按最近一小段读数算的加速度也一跳一跳。
 * 加速度管着自适应巡航自己加速时的负荷、猛加速时的电平和亮度、重刹时的降挡：一跳就像急加速、急刹车
 * （项目所有者 2026-10-22：以前响度差太大，部分是这么误判出来的）。这里：
 *
 * <ul>
 *   <li>读数变了才记一个点，同一个值反复读到不算。值一直不变时隔一阵记一个（读数停了也照样记），车速停住时加速度据此回到 0。
 *       隔多久按读数平均多久变一次定（{@link #HOLD_TIMES} 倍）：车机多久更新一次车速还没量过，比它短的话一级一级的台阶又回来了。</li>
 *   <li>加速度：最近一段（至少 {@link #WINDOW_MIN_MS}，读数变得慢时按 {@link #WINDOW_TIMES} 倍放长）里的这些点拟一条直线，
 *       取斜率，再按 {@link #ACCEL_SMOOTH_SEC} 平滑。</li>
 *   <li>车速：从最新的读数顺着斜率往前推，最多推到下一个读数该来的时候、也不超过 {@link #AHEAD_SEC}（读数断了不一直推）。
 *       新读数和推出来的差一点时，{@link #BLEND_SEC} 里并过去：连续，也不比读数晚。</li>
 * </ul>
 *
 * <p>纯计算（{@code SpeedTrackTest}），声音线程上每块叫一次，不分配内存。读不到（NaN）就交出 NaN。</p>
 */
final class SpeedTrack {

    /** 读数平均多久变一次，没量到之前按这个算（毫秒）：和油门一样约 0.2 s。 */
    static final float EVERY_START_MS = 200f;
    /** 值不变时，隔「平均多久变一次」的这么多倍记一个点；最短、最长（毫秒）。 */
    static final float HOLD_TIMES = 1.5f;
    static final float HOLD_MIN_MS = 250f;
    static final float HOLD_MAX_MS = 1000f;
    /** 拟直线看最近多久：至少这么久（毫秒），或者「平均多久变一次」的这么多倍（至少有三个点）。 */
    static final float WINDOW_MIN_MS = 450f;
    static final float WINDOW_TIMES = 2.2f;
    /** 两个点隔得比这久算断了（毫秒）：从头攒。 */
    static final long GAP_MS = 1500L;
    /** 顺着斜率最多往前推多久（秒）。 */
    static final float AHEAD_SEC = 0.2f;
    /** 新读数和推出来的车速差一点时多快并过去（秒）。 */
    static final float BLEND_SEC = 0.05f;
    /** 加速度的平滑时间常数（秒）。 */
    static final float ACCEL_SMOOTH_SEC = 0.1f;
    private static final int HISTORY = 16;

    private final long[] at = new long[HISTORY];
    private final float[] kmh = new float[HISTORY];
    private int count;
    private int head = -1;
    /** 最近给的那个读数的时刻：同一个读数再给一遍不算。 */
    private long lastSeenAt = Long.MIN_VALUE;
    /** 读数上一次变的时刻、平均多久变一次（毫秒）。 */
    private long lastChangeAt = Long.MIN_VALUE;
    private float everyMs = EVERY_START_MS;
    /** 拟出来的斜率（km/h 每秒）。 */
    private float slope;
    private float speed = Float.NaN;
    private float accel;

    /** 一个读数（km/h，取绝对值：倒车也按快慢算；NaN = 读不到）和它到的时刻。同一个读数（同一时刻）再给一遍不算。 */
    void sample(float value, long atMs) {
        if (Float.isNaN(value)) {
            count = 0;
            head = -1;
            slope = 0f;
            accel = 0f;
            speed = Float.NaN;
            lastSeenAt = Long.MIN_VALUE;
            lastChangeAt = Long.MIN_VALUE;
            return;
        }
        if (atMs == lastSeenAt) {
            return;
        }
        lastSeenAt = atMs;
        float v = Math.abs(value);
        if (head >= 0) {
            long gap = atMs - at[head];
            if (gap < 0L) {
                // 比记下的还旧（推送、快读换着用时会有）：不要
                return;
            }
            if (gap > GAP_MS) {
                count = 0;
                head = -1;
                slope = 0f;
                lastChangeAt = Long.MIN_VALUE;
            } else if (v == kmh[head]) {
                if (gap < holdMs()) {
                    return;
                }
            } else {
                if (lastChangeAt != Long.MIN_VALUE && atMs - lastChangeAt <= GAP_MS) {
                    everyMs += (atMs - lastChangeAt - everyMs) * 0.2f;
                }
                lastChangeAt = atMs;
            }
        }
        add(v, atMs);
        if (Float.isNaN(speed)) {
            speed = v;
        }
    }

    /** 往前走 {@code dtSec} 秒（每块叫一次）。 */
    void step(float dtSec, long nowMs) {
        if (head < 0 || Float.isNaN(speed)) {
            return;
        }
        long since = nowMs - at[head];
        if (since >= holdMs() && since <= GAP_MS) {
            // 读数停了（或者推送只在变了的时候来）：照样按值没变记一个点
            add(kmh[head], nowMs);
            since = 0L;
        }
        float ahead = Math.min(Math.max(0f, since / 1000f), Math.min(AHEAD_SEC, everyMs / 1000f));
        float target = Math.max(0f, kmh[head] + slope * ahead);
        speed += (target - speed) * VirtualEngine.follow(dtSec, BLEND_SEC);
        accel += (slope / 3.6f - accel) * VirtualEngine.follow(dtSec, ACCEL_SMOOTH_SEC);
    }

    /** 此刻的车速（km/h，非负）；读不到是 NaN。 */
    float speed() {
        return speed;
    }

    /** 加速度（m/s²）：正 = 加速，负 = 减速。 */
    float accel() {
        return accel;
    }

    /** 读数平均多久变一次（毫秒，诊断：车机多久更新一次车速）。 */
    float everyMs() {
        return everyMs;
    }

    private float holdMs() {
        return VirtualEngine.clamp(HOLD_TIMES * everyMs, HOLD_MIN_MS, HOLD_MAX_MS);
    }

    private void add(float v, long atMs) {
        head = (head + 1) % HISTORY;
        at[head] = atMs;
        kmh[head] = v;
        if (count < HISTORY) {
            count++;
        }
        slope = fit();
    }

    /**
     * 最近一段里的点（至少两个）拟一条直线，返回斜率（km/h 每秒）；不够两个是 0。
     * 时刻以最新那一个为 0 往回算，免得大数相减丢精度。
     */
    private float fit() {
        long newest = at[head];
        float window = Math.max(WINDOW_MIN_MS, WINDOW_TIMES * everyMs);
        double sumT = 0;
        double sumV = 0;
        int n = 0;
        for (int i = 0; i < count; i++) {
            int idx = (head - i + HISTORY) % HISTORY;
            long age = newest - at[idx];
            if (age > window) {
                break;
            }
            sumT -= age;
            sumV += kmh[idx];
            n++;
        }
        if (n < 2) {
            return 0f;
        }
        double meanT = sumT / n;
        double meanV = sumV / n;
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            int idx = (head - i + HISTORY) % HISTORY;
            double t = -(double) (newest - at[idx]) - meanT;
            num += t * (kmh[idx] - meanV);
            den += t * t;
        }
        return den > 0 ? (float) (num / den * 1000.0) : 0f;
    }
}
