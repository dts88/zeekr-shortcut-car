package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 油门两路来源赛跑（{@link ThrottleSources}）：谁先带来新值用谁，快读的旧值不收，记下谁先、领先多少。 */
public class ThrottleSourcesTest {

    @Test
    public void thePushWinsAndAStalePollIsIgnored() {
        ThrottleSources s = new ThrottleSources();
        assertTrue("快读先读到 0", s.offer(0L, Float.NaN, 0L, 1L, 0f, 1000L));
        assertEquals(0f, s.sample, 0f);
        assertTrue("推送带来 40", s.offer(1L, 40f, 1100L, 1L, 0f, 1000L));
        assertEquals(40f, s.sample, 0f);
        assertEquals(1100L, s.sampleAt);
        assertFalse("快读还是旧的 0：不收", s.offer(1L, 40f, 1100L, 2L, 0f, 1120L));
        assertFalse("快读后来也读到 40：不是新值", s.offer(1L, 40f, 1100L, 3L, 40f, 1190L));
        assertEquals("推送先到", 1, s.pushFirst);
        assertEquals("领先 90 ms", 90f, s.pushLeadMs, 0.01f);
        assertEquals(0, s.pollFirst);
    }

    @Test
    public void aQuickPollWinsWhenItSeesTheValueFirst() {
        ThrottleSources s = new ThrottleSources();
        s.offer(1L, 0f, 1000L, 1L, 0f, 1000L);
        assertTrue("快读先读到 30", s.offer(1L, 0f, 1000L, 2L, 30f, 1150L));
        assertEquals(30f, s.sample, 0f);
        assertFalse("推送晚 40 ms 带来同一个值：不再交", s.offer(2L, 30f, 1190L, 2L, 30f, 1150L));
        assertEquals(1, s.pollFirst);
        assertEquals(40f, s.pollLeadMs, 0.01f);
    }

    @Test
    public void whenPushesStopTheFastReadCarriesOn() {
        ThrottleSources s = new ThrottleSources();
        s.offer(1L, 10f, 1000L, 1L, 10f, 1000L);
        assertTrue(s.offer(1L, 10f, 1000L, 2L, 25f, 1300L));
        assertTrue(s.offer(1L, 10f, 1000L, 3L, 50f, 1500L));
        assertEquals(50f, s.sample, 0f);
    }

    @Test
    public void anOlderValueFromTheFastReadIsNotTakenBack() {
        // 推送 20 → 40；快读晚一拍读到 20（旧的）：不能把油门拉回 20
        ThrottleSources s = new ThrottleSources();
        s.offer(1L, 20f, 1000L, 1L, 20f, 1000L);
        s.offer(2L, 40f, 1200L, 1L, 20f, 1000L);
        assertFalse(s.offer(2L, 40f, 1200L, 2L, 20f, 1220L));
        assertEquals(40f, s.sample, 0f);
        // 很久以后真回到 20，快读带来的照收
        assertTrue(s.offer(2L, 40f, 1200L, 3L, 20f, 2000L));
    }
}
