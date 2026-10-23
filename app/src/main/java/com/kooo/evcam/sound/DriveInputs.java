package com.kooo.evcam.sound;

/**
 * 快读来的车速、油门、刹车 → 声浪用的几个量：此刻的值、踩油门的快慢（%/s，正 = 踩下、负 = 松开）、加减速度（m/s²）。
 * 纯逻辑（{@code DriveInputsTest}），声音线程上用，不分配内存。
 *
 * <h3>怎么算（项目所有者 2026-10-21：信号的采集和使用要细腻、科学）</h3>
 *
 * <ul>
 *   <li>只在有新读数时更新：快读每 50 ms 一遍，声音线程每 5 ms 一块，同一遍读数不重复算（看版本号）。</li>
 *   <li>变化率用最近一小段读数的最小二乘直线斜率：油门取 {@link #PEDAL_WINDOW_MS}、车速取 {@link #ACCEL_WINDOW_MS}。
 *       不拿相邻两个读数直接相减 —— 读数有量化台阶，相邻两个一减全是毛刺；窗口再长又跟不上急踩。</li>
 *   <li>加速度再过一道 {@link #ACCEL_SMOOTH_SEC} 的平滑。车上的纵向加速度传感器开车时一直是 0（Lab 0.21.0），只能从车速算。</li>
 *   <li>读数断了（读不到、或者两遍之间隔了超过 {@link #GAP_MS}）就从头攒，不跨着断口算斜率。</li>
 * </ul>
 */
final class DriveInputs {

    /** 油门变化率看最近多久的读数。 */
    static final long PEDAL_WINDOW_MS = 150L;
    /** 加速度看最近多久的车速。 */
    static final long ACCEL_WINDOW_MS = 300L;
    /** 加速度的平滑时间常数（秒）。 */
    static final float ACCEL_SMOOTH_SEC = 0.15f;
    /** 两遍读数隔得比这久算断了（毫秒）。 */
    static final long GAP_MS = 400L;
    private static final int HISTORY = 16;

    private final long[] at = new long[HISTORY];
    private final float[] speed = new float[HISTORY];
    private final float[] throttle = new float[HISTORY];
    /** 环里有几个（最多 {@link #HISTORY}），最新的在 {@link #head}。 */
    private int count;
    private int head = -1;
    private long lastVersion = Long.MIN_VALUE;

    private float speedNow = Float.NaN;
    private float throttleNow = Float.NaN;
    private float brakeNow = Float.NaN;
    private float pedalRate;
    private float accel;

    /**
     * 一份读数（同一个版本再给一次不算）。读不到的传 NaN；车速取绝对值（倒车也按快慢算）。
     *
     * @param version 快读的版本号
     * @param atMs    读到的时刻（毫秒，单调）
     */
    void offer(long version, long atMs, float speedKmh, float throttlePct, float brakePct) {
        if (version == lastVersion) {
            return;
        }
        lastVersion = version;
        brakeNow = brakePct;
        boolean usable = !Float.isNaN(speedKmh) && !Float.isNaN(throttlePct);
        if (!usable) {
            clearHistory();
            speedNow = Float.isNaN(speedKmh) ? Float.NaN : Math.abs(speedKmh);
            throttleNow = throttlePct;
            return;
        }
        float v = Math.abs(speedKmh);
        long gap = head < 0 ? 0L : atMs - at[head];
        if (head >= 0 && gap == 0L) {
            // 同一毫秒又读了一遍：只换掉最新那一个的值，不算新的一点
            speed[head] = v;
            throttle[head] = throttlePct;
            speedNow = v;
            throttleNow = throttlePct;
            return;
        }
        if (head >= 0 && (gap < 0L || gap > GAP_MS)) {
            // 时刻往回走了，或者断了太久：从头攒
            clearHistory();
            gap = 0L;
        }
        head = (head + 1) % HISTORY;
        at[head] = atMs;
        speed[head] = v;
        throttle[head] = throttlePct;
        if (count < HISTORY) {
            count++;
        }
        speedNow = v;
        throttleNow = throttlePct;
        // 油门：%/ms → %/s
        pedalRate = slope(throttle, PEDAL_WINDOW_MS) * 1000f;
        // 车速：km/h 每毫秒 → m/s²（× 1000 / 3.6）
        float raw = slope(speed, ACCEL_WINDOW_MS) * (1000f / 3.6f);
        if (count < 2) {
            accel = 0f;
        } else {
            float dt = gap / 1000f;
            float k = dt > 0f ? 1f - (float) Math.exp(-dt / ACCEL_SMOOTH_SEC) : 1f;
            accel += (raw - accel) * k;
        }
    }

    /** 忘掉攒下的（声音重新开始时）。 */
    void reset() {
        clearHistory();
        lastVersion = Long.MIN_VALUE;
        speedNow = Float.NaN;
        throttleNow = Float.NaN;
        brakeNow = Float.NaN;
    }

    private void clearHistory() {
        count = 0;
        head = -1;
        pedalRate = 0f;
        accel = 0f;
    }

    /**
     * 最近 {@code windowMs} 里的读数（至少两个）拟一条直线，返回斜率（每毫秒）；不够两个是 0。
     * 时刻以最新那一个为 0 往回算，免得大数相减丢精度。
     */
    private float slope(float[] values, long windowMs) {
        if (count < 2) {
            return 0f;
        }
        long newest = at[head];
        double sumT = 0;
        double sumX = 0;
        int n = 0;
        for (int i = 0; i < count; i++) {
            int idx = (head - i + HISTORY) % HISTORY;
            long age = newest - at[idx];
            if (age > windowMs) {
                break;
            }
            sumT += -age;
            sumX += values[idx];
            n++;
        }
        if (n < 2) {
            // 窗口里只有最新那一个：用它和前一个（读得慢的车机上也有个斜率）
            int prev = (head - 1 + HISTORY) % HISTORY;
            long dt = newest - at[prev];
            return dt > 0 ? (values[head] - values[prev]) / dt : 0f;
        }
        double meanT = sumT / n;
        double meanX = sumX / n;
        double num = 0;
        double den = 0;
        for (int i = 0; i < n; i++) {
            int idx = (head - i + HISTORY) % HISTORY;
            double t = -(double) (newest - at[idx]) - meanT;
            num += t * (values[idx] - meanX);
            den += t * t;
        }
        return den > 0 ? (float) (num / den) : 0f;
    }

    /** 车速（km/h，非负）；读不到是 NaN。 */
    float speed() {
        return speedNow;
    }

    /** 油门（%）；读不到是 NaN。 */
    float throttle() {
        return throttleNow;
    }

    /** 刹车行程（%）；读不到是 NaN。 */
    float brake() {
        return brakeNow;
    }

    /** 踩油门的快慢（%/s）：正 = 踩下，负 = 松开。 */
    float pedalRate() {
        return pedalRate;
    }

    /** 加速度（m/s²）：正 = 加速，负 = 减速。 */
    float accel() {
        return accel;
    }
}
