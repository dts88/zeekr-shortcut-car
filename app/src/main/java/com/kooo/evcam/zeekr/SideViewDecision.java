package com.kooo.evcam.zeekr;

/**
 * 盲区画面：此刻该不该显示、显示哪一侧。
 *
 * <h3>规则</h3>
 *
 * <ul>
 *   <li>左灯只显示左侧那一路，右灯只显示右侧那一路；双闪、不打灯、读不到都不显示（{@link #turnLane}）。</li>
 *   <li><b>后视镜上的灯</b>（项目所有者 2026-11-03，设置里各自开关，默认关）：盲区指示灯、停车后的开门预警，哪一侧亮了就显示哪一侧，
 *       不用打转向灯（{@link #lightLane}）。不看车速（开门预警是停着车亮的，盲区灯低速也会亮）；双闪不拦。
 *       打着转向灯时以转向灯为准；显示着的那一侧灯还亮着就一直显示它，不在两侧之间来回跳。</li>
 *   <li><b>原厂画面优先</b>：挂 R 挡、原厂 360 在显示、原厂画面弹着（360 或侧方小窗）、
 *       泊车辅助开着，任何一样成立都不显示；已经显示着的立刻收。</li>
 *   <li>打灯时车速低于设定值不显示（默认 30 km/h：再慢原厂自己会弹侧方小窗）；车速读不到不拦。
 *       只在「还没显示」时看车速 —— 显示出来之后减速不收，免得在门槛上来回闪。</li>
 *   <li>灯一灭就不再要（灯灭后再留多久由 {@link SideViewTrigger} 管）。</li>
 * </ul>
 *
 * <p>来源：FerixzTanz 的 PR #4（规则和测试），wysie 在 PR #19 里补了「原厂画面在时不备着」；GPL-3.0。</p>
 *
 * <p>纯 Java，不碰 Android，见 {@code SideViewDecisionTest}。</p>
 */
public final class SideViewDecision {

    /** 不显示。 */
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
        /** 此刻已经显示着的是哪一路（{@link LaneCycle#LEFT} / {@link LaneCycle#RIGHT}），没显示是 {@link #NONE}。 */
        public int showing = NONE;
        /** 后视镜上的盲区指示灯：左、右亮着没有；null 读不到。 */
        public Boolean blindSpotLeft;
        public Boolean blindSpotRight;
        /** 停车后的开门预警（亮的是后视镜上同一盏灯）：左、右亮着没有；null 读不到。 */
        public Boolean doorWarningLeft;
        public Boolean doorWarningRight;
        /** 设置里「盲区画面」那一行开着附加项「盲区提醒」。 */
        public boolean useBlindSpot;
        /** 设置里「盲区画面」那一行开着附加项「开门预警」。 */
        public boolean useDoorWarning;
    }

    /**
     * @return 该显示的那一路：{@link LaneCycle#LEFT} / {@link LaneCycle#RIGHT}，或 {@link #NONE}
     */
    public static int decide(Input in) {
        if (factoryViewActive(in)) {
            return NONE;
        }
        int turn = turnLane(in);
        if (turn != NONE) {
            return turn;
        }
        return lightLane(in.showing, lit(in, LaneCycle.LEFT), lit(in, LaneCycle.RIGHT));
    }

    /** 转向灯要显示哪一侧（双闪、不打灯、读不到、原厂画面在都不显示；还没显示时看车速）。 */
    static int turnLane(Input in) {
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
            // 已经显示着同一侧：不再看车速
            return lane;
        }
        if (in.minSpeedKmh > 0 && in.speedKmh != null && in.speedKmh < in.minSpeedKmh) {
            return NONE;
        }
        return lane;
    }

    /** 这一侧后视镜上的灯亮着（盲区指示灯、开门预警里设置开着的那几样）。 */
    static boolean lit(Input in, int lane) {
        boolean left = lane == LaneCycle.LEFT;
        return in.useBlindSpot && Boolean.TRUE.equals(left ? in.blindSpotLeft : in.blindSpotRight)
                || in.useDoorWarning && Boolean.TRUE.equals(left ? in.doorWarningLeft : in.doorWarningRight);
    }

    /** 这一侧亮着的是盲区指示灯（不是只有开门预警）：黑匣子那一行照它写原因。 */
    static boolean blindSpotLit(Input in, int lane) {
        return in.useBlindSpot
                && Boolean.TRUE.equals(lane == LaneCycle.LEFT ? in.blindSpotLeft : in.blindSpotRight);
    }

    /**
     * 后视镜上的灯要显示哪一侧：显示着的那一侧还亮着就接着显示它；不然左边亮着先左边，再右边。不看车速。
     *
     * @param showing 此刻显示着的那一侧（{@link #NONE} 是没显示）
     */
    static int lightLane(int showing, boolean leftLit, boolean rightLit) {
        if (showing == LaneCycle.LEFT && leftLit) {
            return LaneCycle.LEFT;
        }
        if (showing == LaneCycle.RIGHT && rightLit) {
            return LaneCycle.RIGHT;
        }
        if (leftLit) {
            return LaneCycle.LEFT;
        }
        if (rightLit) {
            return LaneCycle.RIGHT;
        }
        return NONE;
    }

    /**
     * 该不该把窗口备着：窗口挂着、隐藏，画面留在环视的会话里，打灯只是显示出来（项目所有者 2026-10-16 定的 B）。
     *
     * <p>只看 D 挡：开车时才会打灯变道，P / N / R 都不必为它开着环视。挡位读不到就不备（打灯时再挂窗口，慢一点而已）。
     * 原厂画面在时不备：原厂要用环视。停着车时开门预警亮了，那时才挂窗口（等环视出画面要一点时间）。</p>
     */
    public static boolean shouldStayReady(Input in) {
        return "D".equals(in.gear) && !factoryViewActive(in);
    }

    /** 原厂有画面在屏幕上（或马上要有）：倒车、360、原厂弹窗、泊车辅助。 */
    public static boolean factoryViewActive(Input in) {
        return "R".equals(in.gear)
                || Boolean.TRUE.equals(in.stock360Shown)
                || Boolean.TRUE.equals(in.stockPopupShown)
                || Boolean.TRUE.equals(in.parkAssistOn);
    }
}
