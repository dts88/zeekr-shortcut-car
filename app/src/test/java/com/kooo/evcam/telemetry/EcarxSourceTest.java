package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * 车辆接口这一路什么时候算断了、断了之后拿什么再连（审查 rev3 F1、B4）。
 *
 * <p>算错的表现：车机下电前那一遍读不到、醒来头一遍也读不到，凑成「连着两遍」—— 夜里车机一醒车辆接口就算断了，
 * 只看安卓判成亮屏、座舱开回来；或者车机服务重启过，再连一直拿旧的车辆对象，这一趟停车一直只看安卓。</p>
 */
public class EcarxSourceTest {

    /** 读到了清零；没读到一遍一遍往上数，连着 {@link EcarxSource#DEAD_PASSES} 遍算断了。 */
    @Test
    public void emptyPassesCountUpUntilAReadAnswers() {
        int n = EcarxSource.emptyPassesAfter(0, false, false);
        assertEquals(1, n);
        n = EcarxSource.emptyPassesAfter(n, false, false);
        assertEquals("连着两遍：断了", EcarxSource.DEAD_PASSES, n);
        assertEquals(0, EcarxSource.emptyPassesAfter(n, true, false));
        assertEquals("读到了，睡没睡过都清零", 0, EcarxSource.emptyPassesAfter(n, true, true));
    }

    /** 车机下电时那一遍读不到、隔着一觉醒来那一遍又读不到：不算连着两遍，从醒来这一遍重新数（醒来再读不到一遍才算断）。 */
    @Test
    public void aPassBeforeTheSleepAndOneAfterItAreNotInARow() {
        int beforeSleep = EcarxSource.emptyPassesAfter(0, false, false);
        int afterWake = EcarxSource.emptyPassesAfter(beforeSleep, false, true);
        assertEquals(1, afterWake);
        assertTrue(afterWake < EcarxSource.DEAD_PASSES);
        assertEquals("醒来以后又一遍读不到：断了", EcarxSource.DEAD_PASSES,
                EcarxSource.emptyPassesAfter(afterWake, false, false));
    }

    /**
     * 连不上（含连上后断了）的车辆对象不再用：下次再连重新 Car.create，传感器在新的那一个上重新挂。
     * 不是手上那一个的（停了的旧来源晚到的）不动。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aFailedLinkDropsItsCarObject() throws Exception {
        Field cached = EcarxSource.class.getDeclaredField("cachedCar");
        cached.setAccessible(true);
        Field sensorsField = EcarxSource.class.getDeclaredField("sensorTypesRegistered");
        sensorsField.setAccessible(true);
        Set<Integer> sensors = (Set<Integer>) sensorsField.get(null);
        Object car = new Object();
        try {
            cached.set(null, car);
            sensors.add(0x00100100);

            EcarxSource.forgetCar(null);
            EcarxSource.forgetCar(new Object());
            assertSame("别的车辆对象：不动", car, cached.get(null));
            assertEquals(1, sensors.size());

            EcarxSource.forgetCar(car);
            assertNull("下次再连重新建", cached.get(null));
            assertTrue("传感器在新的那一个上重新挂", sensors.isEmpty());
        } finally {
            cached.set(null, null);
            sensors.clear();
        }
    }
}
