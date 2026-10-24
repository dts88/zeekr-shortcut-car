package com.kooo.evcam.sound;

/**
 * 稀疏的油门读数 → 连续的踏板（模拟声浪用）。车机上的油门深度每秒只更新约 5 次（2026-10-22 实车：推送约 200 ms 一次，
 * 订阅时要最快的速率也一样）：新值一来直接跳过去，声音就一级一级的（项目所有者：「没有平滑的过渡，直接就从 0 到了我踩到的开度」），
 * 踩油门的快慢按 50 ms 快读的台阶算也被算大了 —— 每一级都像急踩。这里把读数还原成脚的动作：
 *
 * <ul>
 *   <li>新读数一到马上开始动（不加延迟），按 S 形走过去（踩得越快走得越快：{@link #RAMP_SEC} 到 {@link #RAMP_FAST_SEC}）；顺着这一段的方向多走一点
 *       （速度 × {@link #LEAD_SEC}，最多 {@link #LEAD_MAX} 个百分点）—— 脚多半还在踩、还在松。</li>
 *   <li>走到以后先停着；过了 {@link #HOLD_SEC} 还没有新读数（脚停了），再按 {@link #SETTLE_SEC} 回到读数本身。</li>
 *   <li>踩油门的快慢按相邻两个读数算（变了多少 / 隔了多久），报 {@link #RATE_HOLD_SEC}，之后按 {@link #RATE_DECAY_SEC} 落下去。</li>
 * </ul>
 *
 * <p>纯计算（{@code PedalTrackTest}），声音线程上每块叫一次，不分配内存。读不到（NaN）就原样交出 NaN。</p>
 */
final class PedalTrack {

    /** 走到新读数用多久（秒）：慢慢踩时 {@link #RAMP_SEC}，踩得越快越短，到 {@link #FAST_RATE} 以上是 {@link #RAMP_FAST_SEC}（急踩时脚本来就快）。 */
    static final float RAMP_SEC = 0.09f;
    static final float RAMP_FAST_SEC = 0.035f;
    static final float SLOW_RATE = 100f;
    static final float FAST_RATE = 500f;
    /** 顺着方向多走：这一段的速度 × 它（秒），最多这么多个百分点。 */
    static final float LEAD_SEC = 0.04f;
    static final float LEAD_MAX = 8f;
    /** 走到以后多久没有新读数就算脚停了（秒）：比车机的更新间隔（约 0.2 s）长一点。 */
    static final float HOLD_SEC = 0.25f;
    /** 脚停了以后回到读数本身的时间常数（秒）。 */
    static final float SETTLE_SEC = 0.12f;
    /** 两个读数的时间差至少按这么多算（秒）：同一刻来两个时不出无穷大的速度。 */
    static final float MIN_GAP_SEC = 0.05f;
    /**
     * 最多按这么多算（秒）：读数只在变了的时候来，上一个读数停了几秒，脚也是在最近一次更新（约 0.2 s）里才动的。2026-10-24：
     * 原来按整段算，松开 4 秒后一下踩到 70 % 算成 17.5 %/s（像慢慢踩），急踩认不出、换挡也当成没在踩。
     */
    static final float MAX_GAP_SEC = 0.25f;
    /** 踩油门的快慢报多久（秒），之后按这个时间常数落下去。 */
    static final float RATE_HOLD_SEC = 0.15f;
    static final float RATE_DECAY_SEC = 0.1f;

    private float value = Float.NaN;
    private float sample = Float.NaN;
    private long sampleAtMs = -1L;
    private float from;
    private float to;
    private long rampStartMs;
    private float rampSec = RAMP_SEC;
    private boolean ramping;
    private float rate;
    private long rateAtMs;

    /** 来了一个读数（%，NaN = 读不到）；{@code atMs} 是它到的时刻，{@code nowMs} 是此刻。同一个值再来一遍也算（说明脚停着）。 */
    void sample(float pct, long atMs, long nowMs) {
        if (Float.isNaN(pct)) {
            value = Float.NaN;
            sample = Float.NaN;
            ramping = false;
            rate = 0f;
            return;
        }
        if (Float.isNaN(sample) || Float.isNaN(value)) {
            value = pct;
            sample = pct;
            sampleAtMs = atMs;
            ramping = false;
            rate = 0f;
            rateAtMs = nowMs;
            return;
        }
        float gap = Math.min(MAX_GAP_SEC, Math.max(MIN_GAP_SEC, (atMs - sampleAtMs) / 1000f));
        float slope = (pct - sample) / gap;
        float lead = VirtualEngine.clamp(slope * LEAD_SEC, -LEAD_MAX, LEAD_MAX);
        from = value;
        to = VirtualEngine.clamp(pct + lead, 0f, 100f);
        float quick = VirtualEngine.clamp01((Math.abs(slope) - SLOW_RATE) / (FAST_RATE - SLOW_RATE));
        rampSec = RAMP_SEC + (RAMP_FAST_SEC - RAMP_SEC) * quick;
        rampStartMs = nowMs;
        ramping = true;
        sample = pct;
        sampleAtMs = atMs;
        rate = slope;
        rateAtMs = nowMs;
    }

    /** 往前走 {@code dtSec} 秒（每块叫一次）。 */
    void step(float dtSec, long nowMs) {
        if (Float.isNaN(value)) {
            return;
        }
        if (ramping) {
            float f = (nowMs - rampStartMs) / (rampSec * 1000f);
            if (f >= 1f) {
                value = to;
                ramping = false;
            } else {
                value = from + (to - from) * VirtualEngine.smooth(VirtualEngine.clamp01(f));
            }
        } else if ((nowMs - sampleAtMs) / 1000f > HOLD_SEC) {
            value += (sample - value) * VirtualEngine.follow(dtSec, SETTLE_SEC);
        }
    }

    /** 此刻的踏板（%）；读不到是 NaN。 */
    float value() {
        return value;
    }

    /** 此刻踩油门的快慢（%/s，正 = 踩下、负 = 松开）。 */
    float rate(long nowMs) {
        float age = (nowMs - rateAtMs) / 1000f;
        if (age <= RATE_HOLD_SEC) {
            return rate;
        }
        return rate * VirtualEngine.decay(age - RATE_HOLD_SEC, RATE_DECAY_SEC);
    }
}
