package com.kooo.evcam.sound;

import com.kooo.evcam.telemetry.Signal;

/**
 * 模拟声浪什么时候出声：唯一的裁判，纯函数（{@code DriveSoundRulesTest}）。设置、车辆信号、通话 → 一个结论（{@link Why}）：
 * 要不要快读车辆信号、虚拟发动机转不转（开着声音那一路）、此刻响不响、多快淡过去。合成器、总控（{@link DriveSound}）只照它做。
 *
 * <h3>规则（项目所有者 2026-10-21）</h3>
 *
 * <ul>
 *   <li>只在车内；只做运动模式的效果 —— 舒适、标准不出声，驾驶模式读不到也不出声。</li>
 *   <li>运动模式下虚拟发动机一直转着：停着是怠速，P / N 踩油门轰转速，倒挡按倒挡齿比（{@link VirtualEngine}）。</li>
 *   <li>切出运动模式、开关关了、没上电：0.6 秒淡出，发动机「熄火」。</li>
 *   <li>车速、油门读不到或过期（超过 {@link #STALE_MS}）、车辆接口断了：0.3 秒淡到无声，不会卡在一个声音上；回来时 0.3 秒淡入。</li>
 *   <li>通话中静音（车机本来也压低媒体）。</li>
 * </ul>
 */
final class DriveSoundRules {

    /** 快读的值多旧算过期（毫秒）：快读 50 ms 一遍，十遍没来就不再信它。 */
    static final long STALE_MS = 500L;

    /** 结论。每一种的「快读 / 转着 / 响 / 淡多快」是定死的，所以结论本身就是全部信息，不用另外建对象（声音线程里不分配）。 */
    enum Why {
        /** 开关关着（或者开发者选项关了） */
        OFF(false, false, false, 0.6f),
        /** 车辆接口没连上 */
        NO_LINK(false, false, false, 0.3f),
        /** 没上电（点火不在 ON / DRIVING，挡位也不在 R / N / D） */
        POWER_OFF(false, false, false, 0.6f),
        /** 不是运动模式（驾驶模式读不到也算） */
        NOT_SPORT(false, false, false, 0.6f),
        /** 车速 / 油门读不到或过期 */
        STALE(true, true, false, 0.3f),
        /** 通话中 */
        CALL(true, true, false, 0.15f),
        /** 出声 */
        RUNNING(true, true, true, 0.3f);

        /**
         * 要快读车辆信号：只在运动模式下（不用的时候不让车机服务 50 ms 读一遍）。切进运动模式后第一遍快读 50 ms 内就到，
         * 读到之前算过期（{@link #STALE}），开始工作（启动 / 接入）等读数到了再定。
         */
        final boolean armed;
        /** 虚拟发动机转着：开着 AudioTrack 和声音线程。 */
        final boolean open;
        /** 此刻该响。 */
        final boolean audible;
        /** 淡到这个状态用多久（秒）。 */
        final float fadeSec;

        Why(boolean armed, boolean open, boolean audible, float fadeSec) {
            this.armed = armed;
            this.open = open;
            this.audible = audible;
            this.fadeSec = fadeSec;
        }
    }

    private DriveSoundRules() {
    }

    /**
     * @param enabled       开发者选项「模拟声浪」开着（开发者选项关了按关算）
     * @param linkConnected 车辆接口连着
     * @param ignition      点火（{@link Signal#IGNITION} 的码）；读不到 null
     * @param gear          档位字母；读不到 null
     * @param driveMode     驾驶模式（{@link Signal#DRIVE_MODE} 的码）；读不到 null
     * @param speedKmh      快读的车速；读不到 NaN
     * @param throttlePct   快读的油门；读不到 NaN
     * @param fastAgeMs     快读那份读数多旧（毫秒）；一次都没读到 -1
     * @param inCall        通话中
     */
    static Why decide(boolean enabled, boolean linkConnected, Integer ignition, String gear, Integer driveMode,
                      float speedKmh, float throttlePct, long fastAgeMs, boolean inCall) {
        if (!enabled) {
            return Why.OFF;
        }
        if (!linkConnected) {
            return Why.NO_LINK;
        }
        if (!poweredOn(ignition, gear)) {
            return Why.POWER_OFF;
        }
        if (driveMode == null || driveMode != Signal.DRIVE_MODE_SPORT) {
            return Why.NOT_SPORT;
        }
        if (Float.isNaN(speedKmh) || Float.isNaN(throttlePct) || fastAgeMs < 0L || fastAgeMs > STALE_MS) {
            return Why.STALE;
        }
        if (inCall) {
            return Why.CALL;
        }
        return Why.RUNNING;
    }

    /**
     * 油门用推来的还是快读的（试验，项目所有者 2026-10-21：订阅和 50 ms 快读并行，取更新的那个）：推来过、值读得到、
     * 不比这一份快读旧，就用推来的。
     *
     * @param pushCount 推来过几次（0 = 没推过）
     * @param pushAtMs  最近一次推来的时刻
     * @param pushValue 最近一次推来的值（NaN = 读不到）
     * @param pollAtMs  最近一份快读的时刻（0 = 还没读到）
     */
    static boolean usePush(long pushCount, long pushAtMs, float pushValue, long pollAtMs) {
        return pushCount > 0L && !Float.isNaN(pushValue) && pushAtMs >= pollAtMs;
    }

    /** 存着的音量（"-6" 这样的 dB 数，{@code SettingsRegistry.DRIVE_SOUND_VOLUME}）→ dB；不会比 0 dB 更响，认不出的按 0 dB（默认）。 */
    static float volumeDb(String stored) {
        try {
            float db = Float.parseFloat(stored);
            return db > 0f ? 0f : db;
        } catch (RuntimeException e) {
            return 0f;
        }
    }

    /** 上电了：点火在 ON / DRIVING；点火读不到时挂着 R / N / D 也算。 */
    static boolean poweredOn(Integer ignition, String gear) {
        if (ignition != null) {
            return ignition == Signal.IGNITION_ON || ignition == Signal.IGNITION_DRIVING;
        }
        return "R".equals(gear) || "N".equals(gear) || "D".equals(gear);
    }

    /** 档位字母 → 虚拟发动机的档位。 */
    static VirtualEngine.Gear gearOf(String gear) {
        if (gear == null) {
            return VirtualEngine.Gear.UNKNOWN;
        }
        switch (gear) {
            case "P":
                return VirtualEngine.Gear.P;
            case "R":
                return VirtualEngine.Gear.R;
            case "N":
                return VirtualEngine.Gear.N;
            case "D":
                return VirtualEngine.Gear.D;
            default:
                return VirtualEngine.Gear.UNKNOWN;
        }
    }

    /** 动力流的码 → 虚拟发动机的动力流。 */
    static VirtualEngine.Flow flowOf(Integer code) {
        if (code == null) {
            return VirtualEngine.Flow.UNKNOWN;
        }
        switch (code) {
            case Signal.POWER_FLOW_DRIVE:
                return VirtualEngine.Flow.DRIVE;
            case Signal.POWER_FLOW_REGEN:
                return VirtualEngine.Flow.REGEN;
            case Signal.POWER_FLOW_IDLE:
                return VirtualEngine.Flow.IDLE;
            case Signal.POWER_FLOW_NOT_READY:
                return VirtualEngine.Flow.NOT_READY;
            default:
                return VirtualEngine.Flow.UNKNOWN;
        }
    }
}
