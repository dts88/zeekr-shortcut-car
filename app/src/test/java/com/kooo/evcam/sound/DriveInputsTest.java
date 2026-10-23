package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 快读来的车速、油门 → 踩油门的快慢、加减速度（项目所有者 2026-10-21：信号的采集和使用要细腻、科学）。
 *
 * <p>算错的表现：急踩和缓踩听起来一样（变化率算不出来）；读数的量化台阶被当成「急踩急松」（相邻两个直接相减的毛刺）；
 * 断了一阵之后跨着断口算出一个假的猛加速。</p>
 */
public class DriveInputsTest {

    /** 每 50 ms 一遍，版本号、时刻往上走。 */
    private static void feed(DriveInputs in, int samples, long startMs, float speed0, float speedStep,
                             float throttle0, float throttleStep) {
        for (int i = 0; i < samples; i++) {
            in.offer(startMs / 50 + i, startMs + i * 50L, speed0 + speedStep * i, throttle0 + throttleStep * i, 0f);
        }
    }

    @Test
    public void pedalRateFollowsAStabAndASlowPress() {
        DriveInputs stab = new DriveInputs();
        // 每 50 ms 多踩 20 %：400 %/s
        feed(stab, 5, 1000L, 0f, 0f, 0f, 20f);
        assertEquals(400f, stab.pedalRate(), 4f);
        DriveInputs slow = new DriveInputs();
        // 每 50 ms 多踩 1 %：20 %/s
        feed(slow, 10, 1000L, 0f, 0f, 0f, 1f);
        assertEquals(20f, slow.pedalRate(), 0.5f);
        assertTrue("急踩和缓踩分得开", stab.pedalRate() > 10f * slow.pedalRate());
    }

    @Test
    public void releasingShowsAsANegativeRate() {
        DriveInputs in = new DriveInputs();
        feed(in, 5, 0L, 50f, 0f, 80f, -20f);
        assertEquals(-400f, in.pedalRate(), 4f);
    }

    @Test
    public void quantisationStepsAreNotMistakenForPedalMovement() {
        DriveInputs in = new DriveInputs();
        // 油门停在 30 %，读数在 30.0 / 30.4 之间跳（相邻一减就是 8 %/s）
        for (int i = 0; i < 12; i++) {
            in.offer(i, i * 50L, 60f, i % 2 == 0 ? 30f : 30.4f, 0f);
        }
        assertTrue("最小二乘把台阶抹平: " + in.pedalRate(), Math.abs(in.pedalRate()) < 5f);
    }

    @Test
    public void accelerationComesFromTheSpeed() {
        DriveInputs in = new DriveInputs();
        // 3 m/s² = 每秒 10.8 km/h = 每 50 ms 0.54 km/h，读 1 秒
        feed(in, 21, 0L, 20f, 0.54f, 50f, 0f);
        assertEquals(3f, in.accel(), 0.05f);
        DriveInputs braking = new DriveInputs();
        feed(braking, 21, 0L, 80f, -1.08f, 0f, 0f);
        assertEquals(-6f, braking.accel(), 0.1f);
    }

    @Test
    public void reverseSpeedCountsByItsSize() {
        DriveInputs in = new DriveInputs();
        in.offer(1, 0L, -7f, 10f, 0f);
        assertEquals(7f, in.speed(), 0f);
    }

    @Test
    public void theSameReadingIsNotCountedTwice() {
        DriveInputs in = new DriveInputs();
        feed(in, 5, 0L, 0f, 0f, 0f, 20f);
        float rate = in.pedalRate();
        in.offer(4, 200L, 0f, 99f, 0f);
        assertEquals("同一个版本再给一次不算", rate, in.pedalRate(), 0f);
    }

    @Test
    public void aGapStartsOverInsteadOfInventingASurge() {
        DriveInputs in = new DriveInputs();
        feed(in, 10, 0L, 30f, 0f, 20f, 0f);
        // 断了一秒，回来时车速、油门都变了：不跨着断口算斜率
        in.offer(100, 1450L, 60f, 80f, 0f);
        assertEquals(0f, in.pedalRate(), 0f);
        assertEquals(0f, in.accel(), 0f);
        assertEquals(60f, in.speed(), 0f);
    }

    @Test
    public void missingReadingsClearTheHistory() {
        DriveInputs in = new DriveInputs();
        feed(in, 5, 0L, 30f, 1f, 20f, 10f);
        in.offer(9, 250L, Float.NaN, 30f, 0f);
        assertTrue(Float.isNaN(in.speed()));
        assertEquals(0f, in.pedalRate(), 0f);
        assertEquals(0f, in.accel(), 0f);
    }
}
