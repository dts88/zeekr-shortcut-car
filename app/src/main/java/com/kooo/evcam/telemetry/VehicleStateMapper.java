package com.kooo.evcam.telemetry;

/**
 * 读数表（{@link Readings}）→ 信息条要的快照（{@link VehicleState}）。
 *
 * <p>规则都在这里，纯函数（{@code VehicleStateMapperTest}）：</p>
 * <ul>
 *   <li>转向灯和双闪优先用不闪的「转向指示状态」（0 关 1 左 2 右 3 双闪）；读不到时退回两盏灯的
 *       闪烁保持判定（双闪 = 两盏同时在闪；专用的双闪功能号一直读 255，不用）。</li>
 *   <li>车门和座位（座椅有没有人 + 安全带）按「主驾在哪一边」落到左右：右舵车主驾门 / 主驾座位在右前。</li>
 *   <li>每个座位一个状态（{@link #seatState}）：系着优先；有座椅传感器的（前排）看有没有人；
 *       后排没有座椅传感器，读到没系算没人。</li>
 *   <li>刹车 / 油门深度 0–100 → 0..1；雾灯 = 后雾灯（前雾灯这台车多半没装）。车速在 {@link Signal#decode} 里已从 m/s 换成 km/h。</li>
 *   <li>前灯组那一格的两条日行灯条画的是前灯带，按车外看到的亮灭：日行灯或前位置灯有一个亮就亮。灯带白天以日行灯身份亮；
 *       一开灯（位置灯档、近光）日行灯信号就回 0，灯带改以前位置灯身份接着亮（Lab 0.13.0）。</li>
 *   <li>自动驻车看「正在驻车」{@link Signal#AUTO_HOLD_ACTIVE}（停下被接管 1、起步回 0），
 *       不看 {@link Signal#AUTO_HOLD}：那是功能开关，开车全程都是 1。「正在驻车」只在车停着时成立：
 *       这个号原名是「车辆保持时的刹车灯请求」，别的保持也可能让它变，车在走就不算（{@link #holding}）。</li>
 *   <li>拿到的读数已经滤过：只有能用的和勾了的（{@link Readings#usableOr}），这里不再分辨。</li>
 * </ul>
 */
public final class VehicleStateMapper {

    /** 闪远光至少显示这么久（实际一下短的只有 0.1 秒，Lab 0.18.0）。 */
    static final long FLASH_HOLD_MS = 500L;

    private final TurnSignalHold hold = new TurnSignalHold();
    private final MinimumOn flash = new MinimumOn(FLASH_HOLD_MS);

    /**
     * 这个信号变了之后，过多久要再算一次（显示里有「保持」的信号，到点才会灭）；不用再算的是 0。
     * 来源据此安排重算，规则只写在这一处。
     */
    static long republishAfterMs(Signal s) {
        switch (s) {
            case TURN_LEFT:
            case TURN_RIGHT:
                return TurnSignalHold.HOLD_MS;
            case HIGH_BEAM_FLASH:
                return FLASH_HOLD_MS;
            default:
                return 0L;
        }
    }

    /**
     * @param driverOnRight 主驾在右（右舵）；决定主驾门 / 主驾座位画在哪一边
     */
    public void apply(VehicleState.Builder b, Readings r, long nowMs, boolean driverOnRight) {
        b.readings(r);
        TurnSignalHold.Result blink = hold.update(nowMs, r.bool(Signal.TURN_LEFT), r.bool(Signal.TURN_RIGHT));
        Integer indicator = r.code(Signal.INDICATOR);
        if (indicator != null) {
            b.turnSignal(indicator == 1 ? VehicleState.TURN_LEFT : indicator == 2 ? VehicleState.TURN_RIGHT : VehicleState.TURN_NONE);
            b.hazard(indicator == 3);
        } else {
            b.turnSignal(blink.turn);
            b.hazard(blink.hazard);
        }

        b.steeringDegrees(r.number(Signal.STEERING));
        b.gear(r.text(Signal.GEAR));
        b.brake(percent(r.number(Signal.BRAKE_DEPTH)));
        b.throttle(percent(r.number(Signal.THROTTLE_DEPTH)));
        b.speedKmh(r.number(Signal.SPEED));
        b.autoHold(holding(r.bool(Signal.AUTO_HOLD_ACTIVE), r.number(Signal.SPEED)));
        b.stockSurroundShown(r.bool(Signal.STOCK_360));
        b.lowBeam(r.bool(Signal.LOW_BEAM));
        // 远光那一格也按车外看到的：开着远光，或者正在闪远光（闪的时候远光灯信号一直是 0）；
        // 闪一下最短只有 0.1 秒，至少显示 FLASH_HOLD_MS，录像里才看得见
        Boolean flashing = flash.update(nowMs, r.bool(Signal.HIGH_BEAM_FLASH));
        b.highBeam(anyOn(r.bool(Signal.HIGH_BEAM), flashing));
        b.flashToPass(flashing);
        b.fogLights(r.bool(Signal.REAR_FOG));
        b.rearPositionLamps(r.bool(Signal.REAR_POSITION_LAMP));
        b.stopLamps(r.bool(Signal.STOP_LAMP));
        b.reverseLamps(r.bool(Signal.REVERSE_LAMP));
        b.sentry(r.code(Signal.SENTRY_MODE));
        b.daytimeRunningLights(anyOn(r.bool(Signal.DRL), r.bool(Signal.FRONT_POSITION_LAMP)));
        b.odometerKm(r.number(Signal.ODOMETER));
        b.aeb(r.bool(Signal.AEB));
        b.forwardCollisionWarning(r.bool(Signal.FCW));
        b.laneDepartureWarning(r.bool(Signal.LDW));
        b.laneKeepingAid(r.bool(Signal.LKA));
        b.blindSpotAssist(r.bool(Signal.BSD));
        b.rearCollisionWarning(r.bool(Signal.RCW));

        int driverBit = driverOnRight ? VehicleState.FRONT_RIGHT : VehicleState.FRONT_LEFT;
        int passengerBit = driverOnRight ? VehicleState.FRONT_LEFT : VehicleState.FRONT_RIGHT;
        b.doorsOpen(mask(
                new Boolean[]{r.bool(Signal.DOOR_DRIVER), r.bool(Signal.DOOR_PASSENGER),
                        r.bool(Signal.DOOR_REAR_LEFT), r.bool(Signal.DOOR_REAR_RIGHT)},
                new int[]{driverBit, passengerBit, VehicleState.REAR_LEFT, VehicleState.REAR_RIGHT}));
        b.seat(driverBit, seatState(r.bool(Signal.SEAT_DRIVER), r.bool(Signal.BELT_DRIVER), true));
        b.seat(passengerBit, seatState(r.bool(Signal.SEAT_PASSENGER), r.bool(Signal.BELT_PASSENGER), true));
        b.seat(VehicleState.REAR_LEFT, seatState(null, r.bool(Signal.BELT_REAR_LEFT), false));
        b.seat(VehicleState.REAR_CENTER, seatState(null, r.bool(Signal.BELT_REAR_CENTER), false));
        b.seat(VehicleState.REAR_RIGHT, seatState(null, r.bool(Signal.BELT_REAR_RIGHT), false));
    }

    /**
     * 一个座位的状态（{@link VehicleState#SEAT_UNKNOWN} …）：
     * <ul>
     *   <li>安全带读到系着 = 系着，哪怕座椅说没人：主驾用力踩踏板时身体离开坐垫，座椅会闪成没人（Lab），
     *       不能因此闪成灰。唤醒那一下安全带偶尔假读成系着（Lab 0.21.0，没人也是 1），也画成系着 —— 不误报红。</li>
     *   <li>有座椅传感器（前排）：有人、安全带读到没系 = 有人没系（红）；座椅读到没人 = 没人。</li>
     *   <li>没有座椅传感器（后排）：安全带读到没系就算没人 —— 分不出有没有人，误报红比不报更糟。</li>
     *   <li>其余都是没数据：有人但安全带读不到（副驾、后排安全带没验证，非开发者拿不到）、
     *       前排座椅读不到而安全带没系、都读不到。</li>
     * </ul>
     *
     * @param occupied  座椅有人（null = 读不到；没有座椅传感器的传 null）
     * @param belted    安全带系着（null = 读不到）
     * @param hasSensor 这个座位有没有座椅传感器
     */
    static int seatState(Boolean occupied, Boolean belted, boolean hasSensor) {
        if (Boolean.TRUE.equals(belted)) {
            return VehicleState.SEAT_BELTED;
        }
        if (!hasSensor) {
            return Boolean.FALSE.equals(belted) ? VehicleState.SEAT_EMPTY : VehicleState.SEAT_UNKNOWN;
        }
        if (Boolean.TRUE.equals(occupied) && Boolean.FALSE.equals(belted)) {
            return VehicleState.SEAT_UNBELTED;
        }
        if (Boolean.FALSE.equals(occupied)) {
            return VehicleState.SEAT_EMPTY;
        }
        return VehicleState.SEAT_UNKNOWN;
    }

    /** 车停着的界限：0.1 m/s。 */
    static final float STANDSTILL_KMH = 0.36f;

    /** 正在驻车：车说在保持，而且车停着（车速读不到时只看车说的）。 */
    static Boolean holding(Boolean active, Float kmh) {
        if (Boolean.TRUE.equals(active) && kmh != null && kmh >= STANDSTILL_KMH) {
            return Boolean.FALSE;
        }
        return active;
    }

    static Float percent(Float v) {
        return v == null ? null : v / 100f;
    }

    /** 有一个亮就算亮；都不知道才是不知道。 */
    static Boolean anyOn(Boolean a, Boolean b) {
        if (a == null && b == null) {
            return null;
        }
        return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
    }

    /** 各扇门的开 / 关 → 位掩码（开着的门）；一个都不知道时为 null。 */
    static Integer mask(Boolean[] flags, int[] bits) {
        int mask = 0;
        boolean any = false;
        for (int i = 0; i < flags.length; i++) {
            if (flags[i] == null) {
                continue;
            }
            any = true;
            if (flags[i]) {
                mask |= bits[i];
            }
        }
        return any ? mask : null;
    }
}
