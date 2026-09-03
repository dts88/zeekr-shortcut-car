package com.kooo.evcam.camera;

/**
 * 「这一帧渲不渲」的判断。
 *
 * <h3>为什么原来的写法会把帧率砍一半</h3>
 *
 * <p>原来的判断是「距上一帧不足一个目标间隔就跳过」。看着没问题，
 * 但相机的出帧间隔和目标间隔<b>不成整数倍</b>时，它会直接掉到一半：</p>
 *
 * <pre>
 *   相机 29fps（每 34.5ms 一帧），目标 25fps（间隔 40ms）
 *
 *   t=0      渲染          上一帧 t=0
 *   t=34.5   34.5 &lt; 40  跳过
 *   t=69     69 &gt;= 40   渲染   上一帧 t=69
 *   t=103.5  34.5 &lt; 40  跳过
 *   t=138    渲染
 *   ...
 *   结果：每两帧渲一帧 = 14.5 fps
 * </pre>
 *
 * <p>这正是「设置里选 25 或 30，录出来 15」的成因 —— 和相机、和自动曝光都无关，
 * 是我们自己把帧丢掉的。</p>
 *
 * <h3>丢帧只能得到 源/N 的速率</h3>
 *
 * <p>整帧丢弃这件事本身就决定了：29fps 的源，能得到的只有 29、14.5、9.7…
 * <b>拿不到 25</b>。所以问题不是「怎么精确压到 25」，而是「25 和 14.5 哪个更接近目标」。</p>
 *
 * <p>答案显然是 29。所以这里允许一点提前量：只要这一帧不比目标间隔早太多，
 * 就渲染它。宁可略高于设定值，也不要掉到一半 ——
 * 用户选 25 是想要「大约这么流畅」，不是想要「不许超过 25」。</p>
 *
 * <p>纯逻辑，时间由调用方传入，可以单独测。</p>
 */
public final class FrameThrottle {

    /**
     * 允许的提前量。
     *
     * <p>0.75 表示：只要距上一帧已经过了目标间隔的 75%，这一帧就算数。
     * 这个值决定了「宁可略快」的边界 —— 源速率在目标的 1 到 1.33 倍之间时全部放行，
     * 再快才开始隔帧。</p>
     */
    public static final float EARLY_TOLERANCE = 0.75f;

    private volatile long minIntervalNs;
    private volatile long lastRenderedNs;

    /** @param minIntervalNs 目标帧间隔；&lt;= 0 表示不限速 */
    public FrameThrottle(long minIntervalNs) {
        this.minIntervalNs = minIntervalNs;
    }

    public void setMinIntervalNs(long value) {
        this.minIntervalNs = value;
    }

    public long minIntervalNs() {
        return minIntervalNs;
    }

    /**
     * 这一帧要不要渲染。
     *
     * <p>返回 true 时会把它记为「上一帧」，所以同一帧不要问两次。</p>
     */
    public boolean shouldRender(long nowNs) {
        if (minIntervalNs <= 0) {
            lastRenderedNs = nowNs;
            return true;
        }
        if (lastRenderedNs == 0) {
            lastRenderedNs = nowNs;
            return true;
        }
        long since = nowNs - lastRenderedNs;
        // 提前一点点也放行：整帧丢弃只能得到 源/N 的速率，
        // 卡死在「必须满一个间隔」上就会掉到 源/2
        if (since < (long) (minIntervalNs * EARLY_TOLERANCE)) {
            return false;
        }
        lastRenderedNs = nowNs;
        return true;
    }

    public void reset() {
        lastRenderedNs = 0;
    }
}
