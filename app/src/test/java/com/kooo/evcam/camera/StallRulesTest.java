package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link StallRules} 的单元测试。
 *
 * <p>卡顿报告一小时最多几份、要带线程栈和日志尾巴 —— 判定错一次就是一份白写的报告，
 * 或者真卡了却没留下现场。所以把「什么时候算卡」钉死在这里。</p>
 */
public class StallRulesTest {

    private static final long T = StallRules.MIRROR_STALL_MS;

    @Test
    public void notStalledWhileFramesKeepComing() {
        StallRules.State state = new StallRules.State();
        assertEquals(StallRules.Transition.NONE, StallRules.step(state, true, 40L, T, 10_000L));
        // 正好等于阈值还不算
        assertEquals(StallRules.Transition.NONE, StallRules.step(state, true, T, T, 10_500L));
        assertFalse(state.isStalled());
    }

    /** 一直卡着只报一次，好了报一次 —— 否则卡住期间每半秒写一份报告。 */
    @Test
    public void reportsOnceThenRecoversOnce() {
        StallRules.State state = new StallRules.State();
        assertEquals(StallRules.Transition.STALLED, StallRules.step(state, true, T + 20, T, 20_000L));
        assertEquals(20_000L - T - 20, state.stalledSinceMs());
        assertEquals(StallRules.Transition.NONE, StallRules.step(state, true, T + 520, T, 20_500L));
        assertEquals(StallRules.Transition.RECOVERED, StallRules.step(state, true, 30L, T, 21_000L));
        assertFalse(state.isStalled());
    }

    /** 窗口不在屏幕上（息屏、被系统藏起来）时没有新画面是正常的。 */
    @Test
    public void unwatchedNeverStalls() {
        StallRules.State state = new StallRules.State();
        assertEquals(StallRules.Transition.NONE, StallRules.step(state, false, 60_000L, T, 70_000L));
        assertFalse(state.isStalled());
    }

    @Test
    public void stoppingToWatchEndsAStall() {
        StallRules.State state = new StallRules.State();
        StallRules.step(state, true, T + 1, T, 5_000L);
        assertEquals(StallRules.Transition.RECOVERED, StallRules.step(state, false, T + 900, T, 5_900L));
        assertFalse(state.isStalled());
    }

    /** 刚接上相机的宽限期里不算卡：会话还在建，没有帧是正常的。 */
    @Test
    public void graceDelaysTheFirstStall() {
        Heartbeat beat = new Heartbeat();
        beat.arm(1_000L, StallRules.ARM_GRACE_MS);
        StallRules.State state = new StallRules.State();
        long lastOk = 1_000L + StallRules.ARM_GRACE_MS + T;
        assertEquals(StallRules.Transition.NONE,
                StallRules.step(state, true, beat.ageMs(lastOk), T, lastOk));
        assertEquals(StallRules.Transition.STALLED,
                StallRules.step(state, true, beat.ageMs(lastOk + 1), T, lastOk + 1));
    }

    @Test
    public void budgetAllowsSixReportsAnHour() {
        StallRules.Budget budget = new StallRules.Budget();
        for (int i = 0; i < StallRules.MAX_REPORTS_PER_HOUR; i++) {
            assertTrue(budget.tryTake(1_000L + i * 60_000L));
        }
        assertFalse(budget.tryTake(1_000L + 10 * 60_000L));
        // 最早那份满一小时之后，又能写一份，但只有一份
        assertTrue(budget.tryTake(1_000L + StallRules.HOUR_MS));
        assertFalse(budget.tryTake(1_000L + StallRules.HOUR_MS + 1));
    }
}
