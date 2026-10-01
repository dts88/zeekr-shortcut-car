package com.kooo.evcam.zeekr;

/**
 * 打转向灯弹侧视：此刻该不该弹、弹哪一边。
 *
 * <h3>规则</h3>
 *
 * <ul>
 *   <li>左灯只弹左侧那一路，右灯只弹右侧那一路；双闪、不打灯、读不到都不弹。</li>
 *   <li><b>原厂画面优先</b>：挂 R 档、原厂 360 在显示、原厂画面弹着（360 或侧方小窗）、
 *       泊车辅助开着，任何一样成立都不弹；已经弹着的立刻收。</li>
 *   <li>车速低于设定值不弹（默认 30 km/h：再慢原厂自己会弹侧方小窗）；车速读不到不拦。
 *       只在「还没弹」时看车速 —— 弹出来之后减速不收，免得在门槛上来回闪。</li>
 *   <li>灯一灭就收。</li>
 * </ul>
 *
 * <p>纯 Java，不碰 Android，见 {@code SideViewDecisionTest}。</p>
 */
public final class SideViewDecision {

    /** 不弹。 */
    public static final int NONE = -1;

    private SideViewDecision() {
    }

    /** 一份输入：信号读不到的都是 null。 */
    public static final class Input {
        /** {@code VehicleState.TURN_*}：0 不打、1 左、2 右；null 读不到。 */
        public Integer turnSignal;
        public Boolean hazard;
        public String gear;
        public Boolean stock360Shown;
        public Boolean stockPopupShown;
        public Boolean parkAssistOn;
        public Float speedKmh;
        /** 设置里的门槛，km/h；0 表示不看车速。 */
        public int minSpeedKmh;
        /** 此刻已经弹着的是哪一路（{@link LaneCycle#LEFT} / {@link LaneCycle#RIGHT}），没弹是 {@link #NONE}。 */
        public int showing = NONE;
    }

    /**
     * @return 该显示的那一路：{@link LaneCycle#LEFT} / {@link LaneCycle#RIGHT}，或 {@link #NONE}
     */
    public static int decide(Input in) {
        if (factoryViewActive(in)) {
            return NONE;
        }
        if (Boolean.TRUE.equals(in.hazard) || in.turnSignal == null) {
            return NONE;
        }
        int lane;
        if (in.turnSignal == 1) {
            lane = LaneCycle.LEFT;
        } else if (in.turnSignal == 2) {
            lane = LaneCycle.RIGHT;
        } else {
            return NONE;
        }
        if (in.showing == lane) {
            // 已经弹着同一边：不再看车速
            return lane;
        }
        if (in.minSpeedKmh > 0 && in.speedKmh != null && in.speedKmh < in.minSpeedKmh) {
            return NONE;
        }
        return lane;
    }

    /**
     * 「即时弹出」开着时，该不该把窗口备着（透明、相机照推）。
     *
     * <p>只看 D 档：开车时才会打灯变道，P / N / R 都不必为它开着相机。
     * 档位读不到就不备（退回打灯时再接相机，慢一点而已）。</p>
     */
    public static boolean shouldStayReady(Input in) {
        return "D".equals(in.gear);
    }

    /** 原厂有画面在屏幕上（或马上要有）：倒车、360、原厂弹窗、泊车辅助。 */
    public static boolean factoryViewActive(Input in) {
        return "R".equals(in.gear)
                || Boolean.TRUE.equals(in.stock360Shown)
                || Boolean.TRUE.equals(in.stockPopupShown)
                || Boolean.TRUE.equals(in.parkAssistOn);
    }
}
