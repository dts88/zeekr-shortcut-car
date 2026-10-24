package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 稀疏的油门读数还原成连续的踏板（{@link PedalTrack}）：一跳变成很快但平滑的一段，脚停了回到读数，连续踩着不出台阶，
 * 踩的快慢按相邻读数算，读不到原样交出。
 */
public class PedalTrackTest {

    /** 每 5 ms 走一步，走到 {@code untilMs}；返回此刻的值。 */
    private static float run(PedalTrack t, long[] now, long untilMs) {
        while (now[0] < untilMs) {
            now[0] += 5;
            t.step(0.005f, now[0]);
        }
        return t.value();
    }

    @Test
    public void aJumpBecomesAQuickSmoothRise() {
        // 200 ms 里 0 → 20 %（慢慢踩）：马上开始动、S 形走过去，顺着方向多走一点
        PedalTrack t = new PedalTrack();
        long[] now = {1000};
        t.sample(0f, 1000, 1000);
        run(t, now, 1200);
        t.sample(20f, 1200, 1200);
        float early = run(t, now, 1205);
        assertTrue("马上开始动，但不是一下跳过去: " + early, early > 0f && early < 3f);
        float middle = run(t, now, 1245);
        assertTrue("一半左右: " + middle, middle > 8f && middle < 16f);
        float done = run(t, now, 1300);
        assertTrue("走到了（顺着方向多走一点）: " + done, done >= 20f && done <= 20f + PedalTrack.LEAD_MAX);
    }

    @Test
    public void aFastFootGetsThereSooner() {
        // 急踩（200 ms 里 0 → 100 %）走得快：40 ms 就到；慢慢踩的那时候还在路上
        PedalTrack fast = new PedalTrack();
        long[] now = {1000};
        fast.sample(0f, 1000, 1000);
        run(fast, now, 1200);
        fast.sample(100f, 1200, 1200);
        assertEquals(100f, run(fast, now, 1240), 0.01f);
        PedalTrack slow = new PedalTrack();
        long[] then = {1000};
        slow.sample(0f, 1000, 1000);
        run(slow, then, 1200);
        slow.sample(20f, 1200, 1200);
        assertTrue(run(slow, then, 1240) < 0.9f * 20f);
    }

    @Test
    public void whenTheFootStopsItSettlesOnTheReading() {
        PedalTrack t = new PedalTrack();
        long[] now = {1000};
        t.sample(0f, 1000, 1000);
        run(t, now, 1200);
        t.sample(60f, 1200, 1200);
        float held = run(t, now, 1400);
        assertTrue("车机下一个读数来之前先停着: " + held, held > 60f);
        assertEquals(60f, run(t, now, 2000), 0.5f);
    }

    @Test
    public void aSteadyPressRisesWithoutSteps() {
        // 读数每 200 ms 来一个：0、20、40、60、80 —— 一路往上，没有往回、没有一下跳很多
        PedalTrack t = new PedalTrack();
        long[] now = {1000};
        t.sample(0f, 1000, 1000);
        float last = t.value();
        for (int k = 1; k <= 4; k++) {
            long at = 1000 + 200L * k;
            while (now[0] < at) {
                now[0] += 5;
                t.step(0.005f, now[0]);
                float v = t.value();
                assertTrue("不往回: " + v + " < " + last, v >= last - 1e-4f);
                assertTrue("每 5 ms 不跳超过 3 %: " + (v - last), v - last < 3f);
                last = v;
            }
            t.sample(20f * k, at, at);
        }
    }

    @Test
    public void thePedalSpeedComesFromNeighbouringReadings() {
        PedalTrack gentle = new PedalTrack();
        gentle.sample(0f, 1000, 1000);
        gentle.sample(20f, 1200, 1200);
        assertEquals("200 ms 踩了 20 %：100 %/s（不是按 50 ms 快读的台阶算成急踩）", 100f, gentle.rate(1200), 0.5f);
        assertEquals(100f, gentle.rate(1200 + 150), 0.5f);
        assertTrue("之后落下去", gentle.rate(1200 + 400) < 10f);
        PedalTrack stab = new PedalTrack();
        stab.sample(0f, 1000, 1000);
        stab.sample(100f, 1200, 1200);
        assertEquals(500f, stab.rate(1200), 0.5f);
        PedalTrack lift = new PedalTrack();
        lift.sample(80f, 1000, 1000);
        lift.sample(0f, 1200, 1200);
        assertEquals(-400f, lift.rate(1200), 0.5f);
        // 读数只在变了的时候来：松开 4 秒后一下到 70 %，脚是在最近一次更新里动的 —— 按最多 0.25 秒算 280 %/s，
        // 不是按整段算成 17.5 %/s 的慢慢踩（2026-10-24）
        PedalTrack rested = new PedalTrack();
        rested.sample(0f, 1000, 1000);
        rested.sample(70f, 5000, 5000);
        assertEquals(70f / PedalTrack.MAX_GAP_SEC, rested.rate(5000), 0.5f);
    }

    @Test
    public void unreadableIsPassedOn() {
        PedalTrack t = new PedalTrack();
        assertTrue(Float.isNaN(t.value()));
        t.sample(30f, 1000, 1000);
        assertEquals("第一个读数直接用", 30f, t.value(), 0f);
        t.sample(Float.NaN, 1200, 1200);
        assertTrue(Float.isNaN(t.value()));
        t.step(0.005f, 1205);
        assertTrue(Float.isNaN(t.value()));
    }
}
