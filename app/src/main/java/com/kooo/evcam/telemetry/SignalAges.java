package com.kooo.evcam.telemetry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每一个车辆信号上一次真的变了（从一个读得到的值变成另一个读得到的值）是什么时候：只记时刻，不记值、不判什么。
 *
 * <p>为什么（项目所有者 2026-10-14 批准，2.11.4 第 1a 项）：开座舱的那一行要写「点火、主驾门、屏幕电源事件多久以前变过」——
 * 10-12、10-13 两次一帧不出的后座舱都是 ACC→ON 之后两秒内开的，可也有 0.2 秒后开、照样出画面的；要攒样本看它和没画面有没有关系。
 * 读的是这里记下的时刻，不进车辆接口（调度在主线程上读）。</p>
 *
 * <p>{@code EcarxSource} 在它自己的线程上写（每一次解出来的值变了，{@link #changed}），哪个线程都能读。
 * 起点（第一次读到）不算变化；读不到（占位值）进出也不算。时刻用 {@code SystemClock.elapsedRealtime}，由调用方传入（纯 Java，{@code SignalAgesTest}）。</p>
 */
public final class SignalAges {

    private static final Map<Signal, Long> CHANGED_AT = new ConcurrentHashMap<>();

    private SignalAges() {
    }

    /**
     * 一个信号解出来的值变了。从读不到变成读得到（起点、占位值之后）、或者变成读不到，不算变化。
     *
     * @param before 变之前的值；null = 读不到 / 还没读过
     * @param after  变之后的值；null = 读不到
     * @param nowMs  此刻（{@code elapsedRealtime}）
     */
    public static void changed(Signal signal, Object before, Object after, long nowMs) {
        if (signal != null && before != null && after != null && !before.equals(after)) {
            CHANGED_AT.put(signal, nowMs);
        }
    }

    /** 上一次变了是多久以前（毫秒）；进程起来以后没见它变过是 -1。 */
    public static long ageMs(Signal signal, long nowMs) {
        Long at = signal == null ? null : CHANGED_AT.get(signal);
        return at == null ? -1 : Math.max(0, nowMs - at);
    }

    /** 测试用：忘掉记下的。 */
    static void clear() {
        CHANGED_AT.clear();
    }
}
