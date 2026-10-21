package com.kooo.evcam.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 谁要哪几个浮点信号读得比 {@link EcarxSource#FLOAT_POLL_MS} 勤：纯逻辑（{@code FastReadsTest}），{@link Telemetry} 照它让来源快读。
 *
 * <p>为什么（2026-10-21，模拟声浪）：浮点每 200 ms 读一次，再加 40 ms 合并窗口，油门踩下去最多晚 240 ms 才看得到 ——
 * 声浪跟不上脚。快读只读登记的那几个、按登记里最短的间隔读，结果放进单独一份快照（{@link FastReadings}）：
 * 不发布、不进主线程、不走信息条那条路，其余的读法一样不动。只收浮点：离散信号本来就是订阅的，变的那一刻就推来。</p>
 */
final class FastReads {

    /** 最勤读到多勤（毫秒）：每次读要 0.2–0.9 ms，再勤车机服务那边吃不消、也没意义。 */
    static final long MIN_INTERVAL_MS = 20L;

    /** 合起来的读法：读哪几个、多久一次。不可变。 */
    static final class Plan {
        final Set<Signal> signals;
        final long intervalMs;

        Plan(Set<Signal> signals, long intervalMs) {
            this.signals = Collections.unmodifiableSet(EnumSet.copyOf(signals));
            this.intervalMs = intervalMs;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Plan)) {
                return false;
            }
            Plan other = (Plan) o;
            return intervalMs == other.intervalMs && signals.equals(other.signals);
        }

        @Override
        public int hashCode() {
            return signals.hashCode() * 31 + (int) intervalMs;
        }

        @Override
        public String toString() {
            return signals + " every " + intervalMs + " ms";
        }
    }

    private final Map<String, Plan> users = new LinkedHashMap<>();

    /**
     * 登记（同一个名字再登记就是改）：只留浮点信号，间隔不短于 {@link #MIN_INTERVAL_MS}、不长于 {@link EcarxSource#FLOAT_POLL_MS}。
     * 一个浮点都没有的登记不算（当没登记）。
     *
     * @return 合起来的读法；没人要是 null
     */
    Plan acquire(String who, Set<Signal> signals, long intervalMs) {
        Set<Signal> floats = EnumSet.noneOf(Signal.class);
        if (signals != null) {
            for (Signal s : signals) {
                if (s != null && s.isFloat()) {
                    floats.add(s);
                }
            }
        }
        if (floats.isEmpty()) {
            users.remove(who);
        } else {
            long interval = Math.max(MIN_INTERVAL_MS, Math.min(EcarxSource.FLOAT_POLL_MS, intervalMs));
            users.put(who, new Plan(floats, interval));
        }
        return plan();
    }

    /** 注销；没登记过也无害。@return 合起来的读法；没人要了是 null */
    Plan release(String who) {
        users.remove(who);
        return plan();
    }

    /** 此刻合起来的读法：所有人要的信号，最短的间隔；没人要是 null。 */
    Plan plan() {
        if (users.isEmpty()) {
            return null;
        }
        Set<Signal> all = EnumSet.noneOf(Signal.class);
        long interval = Long.MAX_VALUE;
        for (Plan p : users.values()) {
            all.addAll(p.signals);
            interval = Math.min(interval, p.intervalMs);
        }
        return new Plan(all, interval);
    }

    /** 登记着的名字，登记的先后（诊断报告用）。 */
    List<String> names() {
        return new ArrayList<>(users.keySet());
    }
}
