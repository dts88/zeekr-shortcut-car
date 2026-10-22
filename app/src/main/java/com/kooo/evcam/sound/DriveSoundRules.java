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
 *   <li>切出运动模式、没上电：发动机熄火（{@link Why#shutsDown}，2026-10-22：停着照真车熄火，车在走像混动车停发动机），
 *       放完再放掉喇叭（{@link DriveSound}）；开关关了：0.6 秒淡出。</li>
 *   <li>车速、油门读不到或过期（超过 {@link #STALE_MS}）、车辆接口断了：0.3 秒淡到无声，不会卡在一个声音上；回来时 0.3 秒淡入。</li>
 *   <li>通话中静音（车机本来也压低媒体）。</li>
 * </ul>
 */
final class DriveSoundRules {

    /** 快读的值多旧算过期（毫秒）：快读 20 ms 一遍，这么久没来就不再信它。 */
    static final long STALE_MS = 500L;

    /** 结论。每一种的「快读 / 转着 / 响 / 淡多快」是定死的，所以结论本身就是全部信息，不用另外建对象（声音线程里不分配）。 */
    enum Why {
        /** 开关关着（或者开发者选项关了） */
        OFF(false, false, false, 0.6f, false),
        /** 车辆接口没连上 */
        NO_LINK(false, false, false, 0.3f, false),
        /** 没上电（点火不在 ON / DRIVING，挡位也不在 R / N / D） */
        POWER_OFF(false, false, false, 0.6f, true),
        /** 不是运动模式（驾驶模式读不到也算） */
        NOT_SPORT(false, false, false, 0.6f, true),
        /** 车速 / 油门读不到或过期 */
        STALE(true, true, false, 0.3f, false),
        /** 通话中 */
        CALL(true, true, false, 0.15f, false),
        /** 出声 */
        RUNNING(true, true, true, 0.3f, false);

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
        /**
         * 因为它停下来时发动机熄火（{@link VirtualEngine#shutdown}），放完再放掉喇叭，而不是淡出：切出运动模式、下电 ——
         * 和停着切进运动模式放启动声是一个说法（项目所有者 2026-10-22）。
         */
        final boolean shutsDown;

        Why(boolean armed, boolean open, boolean audible, float fadeSec, boolean shutsDown) {
            this.armed = armed;
            this.open = open;
            this.audible = audible;
            this.fadeSec = fadeSec;
            this.shutsDown = shutsDown;
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

    /** 推送停了这么久（毫秒），快读又和它不一样，就改用快读：推送可能断了、或者漏了最后一个值。 */
    static final long PUSH_QUIET_MS = 1000L;

    /**
     * 油门用推来的还是快读的（项目所有者 2026-10-21：订阅和 50 ms 快读并行，用新的那个）。车上量到（2026-10-22）：
     * 快读读到的值比推送晚约 100 ms（同一个新值推送先到 374 次、平均早 102 ms，快读先到 49 次），所以快读读到的时刻晚，
     * 不等于值新 —— 推来过就用推来的；只有推送已经停了 {@link #PUSH_QUIET_MS}、快读又和它不一样（推送断了、漏了），才改用快读。
     * 踩着、松着的时候两者本来就差一截（快读晚），那时推送一直在来，不会切过去。
     *
     * @param pushCount 推来过几次（0 = 没推过）
     * @param pushAtMs  最近一次推来的时刻
     * @param pushValue 最近一次推来的值（NaN = 读不到）
     * @param polled    这一份快读的值（NaN = 读不到）
     * @param nowMs     此刻
     */
    static boolean usePush(long pushCount, long pushAtMs, float pushValue, float polled, long nowMs) {
        if (pushCount <= 0L || Float.isNaN(pushValue)) {
            return false;
        }
        boolean quiet = nowMs - pushAtMs > PUSH_QUIET_MS;
        boolean differs = !Float.isNaN(polled) && Math.abs(polled - pushValue) > 0.5f;
        return !(quiet && differs);
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
