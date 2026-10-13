package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;

/**
 * 读数表 → 信息条快照：转向灯的优先级、主驾在哪一边、车门位掩码、每个座位的状态。
 */
public class VehicleStateMapperTest {

    private static Readings readings(Object... pairs) {
        Map<Signal, Object> map = new EnumMap<>(Signal.class);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Signal) pairs[i], pairs[i + 1]);
        }
        return new Readings(map, 1);
    }

    private static VehicleState map(Readings r, boolean driverOnRight) {
        VehicleState.Builder b = VehicleState.empty().edit();
        new VehicleStateMapper().apply(b, r, 1000L, driverOnRight);
        return b.build();
    }

    /** 有不闪的「转向指示状态」就听它的，哪怕灯此刻正好在灭的半周期。 */
    @Test
    public void steadyIndicatorBeatsTheBlinkingLamps() {
        VehicleState s = map(readings(Signal.INDICATOR, 1, Signal.TURN_LEFT, false, Signal.TURN_RIGHT, false), true);
        assertEquals(Integer.valueOf(VehicleState.TURN_LEFT), s.turnSignal);
        assertEquals(Boolean.FALSE, s.hazard);
    }

    @Test
    public void withoutTheIndicatorTheLampHoldDecides() {
        VehicleState s = map(readings(Signal.TURN_RIGHT, true, Signal.TURN_LEFT, false), true);
        assertEquals(Integer.valueOf(VehicleState.TURN_RIGHT), s.turnSignal);
    }

    /** 转向指示状态 3 = 双闪，不闪、不用等两盏灯。 */
    @Test
    public void indicatorThreeIsHazard() {
        VehicleState s = map(readings(Signal.INDICATOR, 3, Signal.TURN_LEFT, false, Signal.TURN_RIGHT, false), true);
        assertEquals(Boolean.TRUE, s.hazard);
        assertEquals(Integer.valueOf(VehicleState.TURN_NONE), s.turnSignal);
    }

    /** 读不到指示状态时：两盏一起闪就是双闪。 */
    @Test
    public void bothLampsBlinkingIsHazard() {
        VehicleState s = map(readings(Signal.TURN_LEFT, true, Signal.TURN_RIGHT, true), true);
        assertEquals(Boolean.TRUE, s.hazard);
    }

    /** 主驾门落在主驾那一边：右舵在右前，左舵在左前。 */
    @Test
    public void driverDoorLandsOnTheDriverSide() {
        Readings r = readings(Signal.DOOR_DRIVER, true, Signal.DOOR_PASSENGER, false,
                Signal.DOOR_REAR_LEFT, false, Signal.DOOR_REAR_RIGHT, true);
        assertEquals(Integer.valueOf(VehicleState.FRONT_RIGHT | VehicleState.REAR_RIGHT), map(r, true).doorsOpen);
        assertEquals(Integer.valueOf(VehicleState.FRONT_LEFT | VehicleState.REAR_RIGHT), map(r, false).doorsOpen);
    }

    private static final int[] ALL_SEATS = {VehicleState.FRONT_LEFT, VehicleState.FRONT_RIGHT,
            VehicleState.REAR_LEFT, VehicleState.REAR_CENTER, VehicleState.REAR_RIGHT};

    /**
     * 有座椅传感器的座位（前排），座椅 × 安全带的每一种读数（true / false / 读不到）：
     * 系着优先；有人没系 = 红；没人 = 灰；其余没数据。
     */
    @Test
    public void aFrontSeatFollowsTheSensorAndTheBelt() {
        Boolean[] reads = {Boolean.TRUE, Boolean.FALSE, null};
        // [occupied][belted]，顺序同 reads
        int[][] expected = {
                {VehicleState.SEAT_BELTED, VehicleState.SEAT_UNBELTED, VehicleState.SEAT_UNKNOWN},
                {VehicleState.SEAT_BELTED, VehicleState.SEAT_EMPTY, VehicleState.SEAT_EMPTY},
                {VehicleState.SEAT_BELTED, VehicleState.SEAT_UNKNOWN, VehicleState.SEAT_UNKNOWN},
        };
        for (int o = 0; o < reads.length; o++) {
            for (int b = 0; b < reads.length; b++) {
                assertEquals("occupied " + reads[o] + ", belted " + reads[b], expected[o][b],
                        VehicleStateMapper.seatState(reads[o], reads[b], true));
            }
        }
    }

    /** 后排没有座椅传感器：读到没系就算没人（不误报红），系着就是系着，读不到是没数据。 */
    @Test
    public void aRearSeatHasOnlyTheBelt() {
        assertEquals(VehicleState.SEAT_BELTED, VehicleStateMapper.seatState(null, true, false));
        assertEquals(VehicleState.SEAT_EMPTY, VehicleStateMapper.seatState(null, false, false));
        assertEquals(VehicleState.SEAT_UNKNOWN, VehicleStateMapper.seatState(null, null, false));
    }

    /** 主驾座位落在主驾那一边：右舵在右前，左舵在左前；副驾在另一边。 */
    @Test
    public void theDriverSeatLandsOnTheDriverSide() {
        Readings r = readings(Signal.SEAT_DRIVER, true, Signal.BELT_DRIVER, false,
                Signal.SEAT_PASSENGER, false);
        VehicleState rhd = map(r, true);
        assertEquals(VehicleState.SEAT_UNBELTED, rhd.seat(VehicleState.FRONT_RIGHT));
        assertEquals(VehicleState.SEAT_EMPTY, rhd.seat(VehicleState.FRONT_LEFT));
        VehicleState lhd = map(r, false);
        assertEquals(VehicleState.SEAT_UNBELTED, lhd.seat(VehicleState.FRONT_LEFT));
        assertEquals(VehicleState.SEAT_EMPTY, lhd.seat(VehicleState.FRONT_RIGHT));
    }

    /**
     * 用力踩踏板时主驾座椅会闪成「没人」，安全带还系着：照旧是系着，不闪成灰。
     * 唤醒那一下安全带假读成系着（没人也是 1）也一样画成系着 —— 不误报红。
     */
    @Test
    public void aFastenedBeltWinsOverAnEmptySeat() {
        Readings r = readings(Signal.SEAT_DRIVER, false, Signal.BELT_DRIVER, true);
        assertEquals(VehicleState.SEAT_BELTED, map(r, true).seat(VehicleState.FRONT_RIGHT));
        assertEquals(VehicleState.SEAT_BELTED, map(r, false).seat(VehicleState.FRONT_LEFT));
    }

    /** 副驾：有人没系是红，系着是系着，没人是灰（安全带读不到也是灰）。 */
    @Test
    public void thePassengerSeatHasTheSameRules() {
        assertEquals(VehicleState.SEAT_UNBELTED, map(readings(Signal.SEAT_PASSENGER, true,
                Signal.BELT_PASSENGER, false), true).seat(VehicleState.FRONT_LEFT));
        assertEquals(VehicleState.SEAT_BELTED, map(readings(Signal.SEAT_PASSENGER, true,
                Signal.BELT_PASSENGER, true), true).seat(VehicleState.FRONT_LEFT));
        assertEquals(VehicleState.SEAT_EMPTY, map(readings(Signal.SEAT_PASSENGER, false), true)
                .seat(VehicleState.FRONT_LEFT));
        assertEquals(VehicleState.SEAT_UNKNOWN, map(readings(Signal.SEAT_PASSENGER, true), true)
                .seat(VehicleState.FRONT_LEFT));
    }

    /** 后排三个座位各看各的安全带，左右不随主驾那一边换。 */
    @Test
    public void rearSeatsFollowTheirBelts() {
        Readings r = readings(Signal.BELT_REAR_LEFT, false, Signal.BELT_REAR_RIGHT, true);
        for (boolean driverOnRight : new boolean[]{true, false}) {
            VehicleState s = map(r, driverOnRight);
            assertEquals(VehicleState.SEAT_EMPTY, s.seat(VehicleState.REAR_LEFT));
            assertEquals(VehicleState.SEAT_UNKNOWN, s.seat(VehicleState.REAR_CENTER));
            assertEquals(VehicleState.SEAT_BELTED, s.seat(VehicleState.REAR_RIGHT));
        }
    }

    /** 0x20060400 是自动驻车的功能开关，不是「正在驻车」：信息条上不能拿它亮灯。 */
    @Test
    public void autoHoldSwitchDoesNotLightTheCell() {
        assertNull(map(readings(Signal.AUTO_HOLD, true), true).autoHold);
    }

    /** 「正在驻车」0x20320600：停下被接管 1、起步 0。 */
    @Test
    public void autoHoldFollowsTheHoldingState() {
        assertEquals(Boolean.TRUE, map(readings(Signal.AUTO_HOLD, true, Signal.AUTO_HOLD_ACTIVE, true), true).autoHold);
        assertEquals(Boolean.FALSE, map(readings(Signal.AUTO_HOLD, true, Signal.AUTO_HOLD_ACTIVE, false), true).autoHold);
    }

    /** 车在走时「保持」不算正在驻车；停着（或读不到车速）就听车的。 */
    @Test
    public void autoHoldNeedsTheCarToStandStill() {
        assertEquals(Boolean.FALSE, map(readings(Signal.AUTO_HOLD_ACTIVE, true, Signal.SPEED, 12f), true).autoHold);
        assertEquals(Boolean.TRUE, map(readings(Signal.AUTO_HOLD_ACTIVE, true, Signal.SPEED, 0f), true).autoHold);
        assertEquals(Boolean.TRUE, map(readings(Signal.AUTO_HOLD_ACTIVE, true), true).autoHold);
    }

    /**
     * 非开发者：不能用的信号在映射前滤掉 —— 副驾、后排安全带没验证，坐了人的副驾画成没数据（不画红），
     * 后排都是没数据；主驾（安全带已确认）照样有人没系就红；先用着的（方向盘）留着。
     */
    @Test
    public void unusableSignalsAreMaskedBeforeMapping() {
        Readings r = readings(Signal.SEAT_DRIVER, true, Signal.BELT_DRIVER, false,
                Signal.SEAT_PASSENGER, true, Signal.BELT_PASSENGER, false,
                Signal.BELT_REAR_LEFT, false, Signal.STEERING, -12f).usableOnly();
        assertEquals(Float.valueOf(-12f), map(r, true).steeringDegrees);
        assertNull(r.bool(Signal.BELT_PASSENGER));
        assertEquals(Boolean.FALSE, r.bool(Signal.BELT_DRIVER));
        VehicleState s = map(r, true);
        assertEquals(VehicleState.SEAT_UNBELTED, s.seat(VehicleState.FRONT_RIGHT));
        assertEquals(VehicleState.SEAT_UNKNOWN, s.seat(VehicleState.FRONT_LEFT));
        assertEquals(VehicleState.SEAT_UNKNOWN, s.seat(VehicleState.REAR_LEFT));
    }

    /** 前灯带：白天日行灯亮；一开灯日行灯信号回 0，灯带以前位置灯身份接着亮 —— 这一格都要亮。 */
    @Test
    public void theLightBandCellFollowsWhatYouSee() {
        assertEquals(Boolean.TRUE, map(readings(Signal.DRL, true, Signal.FRONT_POSITION_LAMP, false), true).daytimeRunningLights);
        assertEquals(Boolean.TRUE, map(readings(Signal.DRL, false, Signal.FRONT_POSITION_LAMP, true), true).daytimeRunningLights);
        assertEquals(Boolean.FALSE, map(readings(Signal.DRL, false, Signal.FRONT_POSITION_LAMP, false), true).daytimeRunningLights);
        assertNull(map(readings(), true).daytimeRunningLights);
    }

    /** 闪远光时远光灯信号一直是 0，只有闪远光那个号变：这一格要跟着闪。 */
    @Test
    public void flashingTheHighBeamsLightsTheCell() {
        assertEquals(Boolean.TRUE, map(readings(Signal.HIGH_BEAM, false, Signal.HIGH_BEAM_FLASH, true), true).highBeam);
        assertEquals(Boolean.TRUE, map(readings(Signal.HIGH_BEAM, true, Signal.HIGH_BEAM_FLASH, false), true).highBeam);
        assertEquals(Boolean.FALSE, map(readings(Signal.HIGH_BEAM, false, Signal.HIGH_BEAM_FLASH, false), true).highBeam);
    }

    /** 闪一下只有 0.1 秒：至少显示半秒，过了才灭。 */
    @Test
    public void aShortFlashStaysVisibleForHalfASecond() {
        VehicleStateMapper mapper = new VehicleStateMapper();
        Boolean[] seen = new Boolean[3];
        long[] at = {1000L, 1100L, 1000L + VehicleStateMapper.FLASH_HOLD_MS + 10};
        Readings[] r = {readings(Signal.HIGH_BEAM, false, Signal.HIGH_BEAM_FLASH, true),
                readings(Signal.HIGH_BEAM, false, Signal.HIGH_BEAM_FLASH, false),
                readings(Signal.HIGH_BEAM, false, Signal.HIGH_BEAM_FLASH, false)};
        for (int i = 0; i < 3; i++) {
            VehicleState.Builder b = VehicleState.empty().edit();
            mapper.apply(b, r[i], at[i], true);
            seen[i] = b.build().highBeam;
        }
        assertEquals(Boolean.TRUE, seen[0]);
        assertEquals(Boolean.TRUE, seen[1]);
        assertEquals(Boolean.FALSE, seen[2]);
        assertEquals(VehicleStateMapper.FLASH_HOLD_MS, VehicleStateMapper.republishAfterMs(Signal.HIGH_BEAM_FLASH));
    }

    /** 车辆状态面板上的闪远光只看闪：开着远光不算。 */
    @Test
    public void flashToPassIsOnlyTheFlash() {
        assertEquals(Boolean.TRUE, map(readings(Signal.HIGH_BEAM, false, Signal.HIGH_BEAM_FLASH, true), true).flashToPass);
        assertEquals(Boolean.FALSE, map(readings(Signal.HIGH_BEAM, true, Signal.HIGH_BEAM_FLASH, false), true).flashToPass);
        assertNull(map(readings(), true).flashToPass);
    }

    /** 后灯组的四盏：按车外看到的直接带过去。 */
    @Test
    public void rearLampsComeThroughAsSeen() {
        VehicleState s = map(readings(Signal.REAR_POSITION_LAMP, true, Signal.STOP_LAMP, false,
                Signal.REAR_FOG, true, Signal.REVERSE_LAMP, true), true);
        assertEquals(Boolean.TRUE, s.rearPositionLamps);
        assertEquals(Boolean.FALSE, s.stopLamps);
        assertEquals(Boolean.TRUE, s.fogLights);
        assertEquals(Boolean.TRUE, s.reverseLamps);
        assertNull(map(readings(), true).stopLamps);
    }

    /** 哨兵模式原样带过去：0 关、1 开、2 布防；用户先开放了，非开发者也看得到。 */
    @Test
    public void sentryComesThroughEvenWhenOnlyUsableSignalsAreKept() {
        assertEquals(Integer.valueOf(2), map(readings(Signal.SENTRY_MODE, 2).usableOnly(), true).sentry);
        assertEquals(Integer.valueOf(0), map(readings(Signal.SENTRY_MODE, 0), true).sentry);
    }

    @Test
    public void nothingKnownLeavesEverythingUnknown() {
        VehicleState s = map(readings(), true);
        assertNull(s.doorsOpen);
        for (int bit : ALL_SEATS) {
            assertEquals(VehicleState.SEAT_UNKNOWN, s.seat(bit));
        }
        assertNull(s.turnSignal);
        assertNull(s.gear);
    }

    @Test
    public void depthsBecomeFractionsAndFogIsTheRearLamp() {
        VehicleState s = map(readings(Signal.BRAKE_DEPTH, 15f, Signal.THROTTLE_DEPTH, 50f,
                Signal.FRONT_FOG, false, Signal.REAR_FOG, true, Signal.GEAR, "D"), true);
        assertEquals(0.15f, s.brake, 1e-6f);
        assertEquals(57f, map(readings(Signal.SPEED, 57f), true).speedKmh, 1e-3f);
        assertEquals(0.5f, s.throttle, 1e-6f);
        assertEquals(Boolean.TRUE, s.fogLights);
        assertEquals("D", s.gear);
        assertNull(map(readings(), true).fogLights);
        // 前雾灯这台车多半没装：它亮不亮都不算
        assertNull(map(readings(Signal.FRONT_FOG, true), true).fogLights);
    }
}
