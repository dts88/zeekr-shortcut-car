package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link Heartbeat} 的单元测试。 */
public class HeartbeatTest {

    @Test
    public void aBeatResetsTheAge() {
        Heartbeat beat = new Heartbeat();
        beat.arm(0L, StallRules.ARM_GRACE_MS);
        beat.beat(10_000L);
        assertEquals(200L, beat.ageMs(10_200L));
        assertEquals(1L, beat.count());
    }

    /** 重新计时不能顺手重新开始盯：解绑窗口的主线程和监测线程会抢。 */
    @Test
    public void rebaseDoesNotRearm() {
        Heartbeat beat = new Heartbeat();
        beat.arm(0L, 0L);
        beat.disarm();
        beat.rebase(5_000L, 4_000L);
        assertFalse(beat.isArmed());
        assertEquals(-4_000L, beat.ageMs(5_000L));
    }

    @Test
    public void armAndDisarm() {
        Heartbeat beat = new Heartbeat();
        assertFalse(beat.isArmed());
        beat.arm(0L, 0L);
        assertTrue(beat.isArmed());
        beat.disarm();
        assertFalse(beat.isArmed());
    }

    @Test
    public void rateIsBeatsPerSecondSinceLastTake() {
        Heartbeat beat = new Heartbeat();
        assertEquals(-1f, beat.takeRate(0L), 0.001f);
        for (int i = 0; i < 300; i++) {
            beat.beat(i * 33L);
        }
        assertEquals(30f, beat.takeRate(10_000L), 0.01f);
        assertEquals(0f, beat.takeRate(11_000L), 0.01f);
    }

    @Test
    public void keepsOnlyTheSlowestOpUntilTaken() {
        Heartbeat beat = new Heartbeat();
        assertNull(beat.takeSlowestOp());
        beat.noteOp("draw", 4L);
        beat.noteOp("write", 85L);
        beat.noteOp("drain", 12L);
        assertEquals("write 85ms", beat.takeSlowestOp());
        assertNull(beat.takeSlowestOp());
    }

    /** 卡住时报告要能说出编码线程在忙什么、忙了多久。 */
    @Test
    public void taskShowsWhatTheThreadIsBusyWith() {
        Heartbeat beat = new Heartbeat();
        assertNull(beat.task());
        beat.beginTask("segment-switch", 1_000L);
        assertEquals("segment-switch", beat.task());
        assertEquals(700L, beat.taskAgeMs(1_700L));
        beat.endTask();
        assertNull(beat.task());
    }
}
