package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * 快照：不知道就是 null（座位是 SEAT_UNKNOWN），改一项其余不动，版本号只往上走。
 */
public class VehicleStateTest {

    @Test
    public void anEmptySnapshotKnowsNothing() {
        VehicleState s = VehicleState.empty();
        assertNull(s.speedKmh);
        assertNull(s.doorsOpen);
        assertEquals(VehicleState.SEAT_UNKNOWN, s.seat(VehicleState.FRONT_LEFT));
        assertNull(s.turnSignal);
    }

    @Test
    public void editingKeepsTheOtherFieldsAndBumpsTheVersion() {
        VehicleState first = VehicleState.empty().edit().speedKmh(42f).gear("D").build();
        VehicleState second = first.edit().turnSignal(VehicleState.TURN_LEFT).build();
        assertEquals(Float.valueOf(42f), second.speedKmh);
        assertEquals("D", second.gear);
        assertEquals(Integer.valueOf(VehicleState.TURN_LEFT), second.turnSignal);
        assertEquals(first.version + 1, second.version);
    }

    /**
     * 车门按位记：一扇一扇地报，之前不知道就从「都关着」起。座位各记各的状态：改一个不动别的，
     * 没设过的是没数据；改一份快照（edit）时座位跟着带过去。
     */
    @Test
    public void doorsAndSeatsAreKeptPerPosition() {
        VehicleState s = VehicleState.empty().edit()
                .door(VehicleState.FRONT_LEFT, true)
                .door(VehicleState.REAR_RIGHT, true)
                .door(VehicleState.FRONT_LEFT, false)
                .seat(VehicleState.FRONT_RIGHT, VehicleState.SEAT_BELTED)
                .seat(VehicleState.REAR_CENTER, VehicleState.SEAT_EMPTY)
                .seat(VehicleState.FRONT_RIGHT, VehicleState.SEAT_UNBELTED)
                .build();
        assertEquals(Integer.valueOf(VehicleState.REAR_RIGHT), s.doorsOpen);
        assertEquals(VehicleState.SEAT_UNBELTED, s.seat(VehicleState.FRONT_RIGHT));
        assertEquals(VehicleState.SEAT_EMPTY, s.seat(VehicleState.REAR_CENTER));
        assertEquals(VehicleState.SEAT_UNKNOWN, s.seat(VehicleState.FRONT_LEFT));
        assertEquals(VehicleState.SEAT_UNKNOWN, s.seat(VehicleState.REAR_LEFT));
        assertEquals(VehicleState.SEAT_UNKNOWN, s.seat(VehicleState.REAR_RIGHT));

        VehicleState next = s.edit().speedKmh(10f).build();
        assertEquals(VehicleState.SEAT_UNBELTED, next.seat(VehicleState.FRONT_RIGHT));
        assertEquals(VehicleState.SEAT_EMPTY, next.seat(VehicleState.REAR_CENTER));
        assertEquals(Integer.valueOf(VehicleState.REAR_RIGHT), next.doorsOpen);
        VehicleState cleared = next.edit()
                .seat(VehicleState.FRONT_RIGHT, VehicleState.SEAT_UNKNOWN)
                .seat(VehicleState.REAR_CENTER, VehicleState.SEAT_UNKNOWN)
                .build();
        assertEquals(VehicleState.SEAT_UNKNOWN, cleared.seat(VehicleState.FRONT_RIGHT));
        assertEquals(VehicleState.SEAT_UNKNOWN, cleared.seat(VehicleState.REAR_CENTER));
        assertEquals(Integer.valueOf(VehicleState.REAR_RIGHT), cleared.doorsOpen);
        assertEquals(Float.valueOf(10f), cleared.speedKmh);
    }

    /** 座位一次只收一个位置位、四种状态之一；别的是写错了，当场报出来。 */
    @Test
    public void aSeatTakesOneBitAndOneOfFourStates() {
        VehicleState.Builder b = VehicleState.empty().edit();
        for (int bad : new int[]{0, VehicleState.FRONT_LEFT | VehicleState.FRONT_RIGHT, VehicleState.REAR_CENTER << 1}) {
            try {
                b.seat(bad, VehicleState.SEAT_EMPTY);
                fail("seat bit " + bad);
            } catch (IllegalArgumentException expected) {
                // 对
            }
        }
        for (int bad : new int[]{VehicleState.SEAT_UNKNOWN - 1, VehicleState.SEAT_UNBELTED + 1}) {
            try {
                b.seat(VehicleState.FRONT_LEFT, bad);
                fail("seat state " + bad);
            } catch (IllegalArgumentException expected) {
                // 对
            }
        }
    }

    @Test
    public void pedalsAreClampedToZeroOne() {
        VehicleState s = VehicleState.empty().edit().throttle(1.7f).brake(-0.2f).build();
        assertEquals(Float.valueOf(1f), s.throttle);
        assertEquals(Float.valueOf(0f), s.brake);
    }
}
