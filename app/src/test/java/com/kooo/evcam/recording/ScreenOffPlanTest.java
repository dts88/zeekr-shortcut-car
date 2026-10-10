package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 熄屏后这段录像会怎样：录制协调器、主界面、录制键上的小字照的是同一张表（lifecycle-spec §2.4，项目所有者 2026-10-10）。
 */
public class ScreenOffPlanTest {

    private static final Integer[] ANY_SENTRY = {0, 1, 2, null};
    private static final Integer[] SENTRY_ON = {1, 2};
    private static final Integer[] SENTRY_OFF_OR_UNKNOWN = {0, null};
    private static final boolean[] BOTH = {false, true};

    private static ScreenOffPlan.Action action(boolean keeps, boolean lockHeld, boolean autoRecord, Integer sentry,
                                               boolean driving) {
        return ScreenOffPlan.atScreenOff(keeps, lockHeld, autoRecord, sentry, driving).action;
    }

    private static boolean resumes(boolean keeps, boolean lockHeld, boolean autoRecord, Integer sentry,
                                   boolean driving) {
        return ScreenOffPlan.atScreenOff(keeps, lockHeld, autoRecord, sentry, driving).resumesOnWake;
    }

    /** 开发者的唤醒锁拉着车机：哨兵模式开没开、车在不在走，都接着录。 */
    @Test
    public void aHeldWakeLockKeepsRecordingWhateverTheCarSays() {
        for (Integer sentry : ANY_SENTRY) {
            for (boolean driving : BOTH) {
                for (boolean autoRecord : BOTH) {
                    assertEquals(ScreenOffPlan.Action.CONTINUE, action(true, true, autoRecord, sentry, driving));
                    assertFalse("接着录就没有「亮屏接回」", resumes(true, true, autoRecord, sentry, driving));
                    assertEquals(ScreenOffPlan.CONTINUE, ScreenOffPlan.of(true, true, autoRecord, sentry, driving));
                }
            }
        }
    }

    /** 哨兵模式开着（1 开、2 布防）：车机醒着 —— 熄屏持续录制开着接着录，关着 10 秒后停（和以前一样）。 */
    @Test
    public void withSentryModeOnTheHeadUnitStaysAwake() {
        for (Integer sentry : SENTRY_ON) {
            for (boolean driving : BOTH) {
                assertEquals(ScreenOffPlan.Action.CONTINUE, action(true, false, false, sentry, driving));
                assertEquals(ScreenOffPlan.CONTINUE, ScreenOffPlan.of(true, false, false, sentry, driving));
                assertEquals(ScreenOffPlan.Action.STOP_LATER, action(false, false, false, sentry, driving));
                assertEquals(ScreenOffPlan.Action.STOP_LATER, action(false, false, true, sentry, driving));
            }
        }
    }

    /**
     * 哨兵模式没开（或读不到）、车没在走：车机几秒后就断电 —— 现在停，相机按次序关（2026-10-10）。
     * 熄屏持续录制开着时以前什么都不做，进程带着开着的相机被车机结束。
     */
    @Test
    public void parkedWithoutSentryModeStopsNow() {
        for (Integer sentry : SENTRY_OFF_OR_UNKNOWN) {
            for (boolean keeps : BOTH) {
                for (boolean autoRecord : BOTH) {
                    assertEquals(ScreenOffPlan.Action.STOP_NOW, action(keeps, false, autoRecord, sentry, false));
                }
            }
        }
    }

    /** 哨兵模式在 D 挡读成 0：车明确在走时（行驶中关了屏幕）照以前的做法，不因为「哨兵模式关」停录。 */
    @Test
    public void drivingWithSentryModeReadingOffKeepsTodaysBehaviour() {
        for (Integer sentry : SENTRY_OFF_OR_UNKNOWN) {
            assertEquals(ScreenOffPlan.Action.CONTINUE, action(true, false, false, sentry, true));
            assertEquals(ScreenOffPlan.CONTINUE, ScreenOffPlan.of(true, false, false, sentry, true));
            assertEquals(ScreenOffPlan.Action.STOP_LATER, action(false, false, true, sentry, true));
            assertEquals(ScreenOffPlan.Action.STOP_LATER, action(false, false, false, sentry, true));
        }
    }

    /** 停了之后亮屏接不接：熄屏持续录制、启动自动录制开着一个就接（现在停、10 秒后停都一样）。 */
    @Test
    public void aStopResumesOnWakeWhenKeepRecordingOrAutoRecordIsOn() {
        for (Integer sentry : SENTRY_OFF_OR_UNKNOWN) {
            assertTrue(resumes(true, false, false, sentry, false));
            assertTrue(resumes(true, false, true, sentry, false));
            assertTrue(resumes(false, false, true, sentry, false));
            assertFalse(resumes(false, false, false, sentry, false));
        }
        for (Integer sentry : SENTRY_ON) {
            assertTrue(resumes(false, false, true, sentry, false));
            assertFalse(resumes(false, false, false, sentry, false));
        }
    }

    /** 「在走」要确知：挡位读得到、不是 P，车速读得到、大于 0。等红灯（D 挡、车速 0）、读不到的都不算。 */
    @Test
    public void drivingMeansKnownGearOtherThanParkAndMoving() {
        assertTrue(ScreenOffPlan.driving("D", 30f));
        assertTrue(ScreenOffPlan.driving("R", 3f));
        assertFalse("P 挡", ScreenOffPlan.driving("P", 0f));
        assertFalse("P 挡读到车速也不算", ScreenOffPlan.driving("P", 2f));
        assertFalse("D 挡停着（等红灯）", ScreenOffPlan.driving("D", 0f));
        assertFalse("挡位读不到", ScreenOffPlan.driving(null, 30f));
        assertFalse("车速读不到", ScreenOffPlan.driving("D", null));
        assertFalse("都读不到", ScreenOffPlan.driving(null, null));
    }

    /**
     * 录制键上的小字和表是同一份：接着录 →「熄屏后继续录制」，停了亮屏接 →「熄屏暂停，唤醒后恢复」，
     * 不接 →「熄屏后停止录制」，熄屏持续录制开着却读不到哨兵模式（按没开算：现在停、亮屏接）→「熄屏续录需开启哨兵模式」。
     */
    @Test
    public void theRecordButtonTextSaysWhatTheTableDoes() {
        for (boolean keeps : BOTH) {
            for (boolean lockHeld : BOTH) {
                for (boolean autoRecord : BOTH) {
                    for (Integer sentry : ANY_SENTRY) {
                        for (boolean driving : BOTH) {
                            ScreenOffPlan.Decision decision =
                                    ScreenOffPlan.atScreenOff(keeps, lockHeld, autoRecord, sentry, driving);
                            ScreenOffPlan text = ScreenOffPlan.of(keeps, lockHeld, autoRecord, sentry, driving);
                            String row = "keeps=" + keeps + " lock=" + lockHeld + " auto=" + autoRecord
                                    + " sentry=" + sentry + " driving=" + driving;
                            if (decision.action == ScreenOffPlan.Action.CONTINUE) {
                                assertEquals(row, ScreenOffPlan.CONTINUE, text);
                            } else if (keeps && sentry == null) {
                                assertEquals(row, ScreenOffPlan.Action.STOP_NOW, decision.action);
                                assertTrue(row, decision.resumesOnWake);
                                assertEquals(row, ScreenOffPlan.NEEDS_SENTRY, text);
                            } else {
                                assertEquals(row, decision.resumesOnWake ? ScreenOffPlan.PAUSE : ScreenOffPlan.STOP,
                                        text);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 熄屏期间哨兵模式、挡位变了：只有「接着录」、不是唤醒锁拉着的那种照表再判 —— 熄屏时在走、后来挂 P 下车，
     * 不会再有熄屏事件。停了的不因为信号变了录起来，10 秒后停的照样到点停，锁拉着的等锁到点再判。
     */
    @Test
    public void onlyAContinueWithoutTheWakeLockIsRejudgedWhenTheCarChanges() {
        assertTrue(ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.CONTINUE, false));
        assertFalse(ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.CONTINUE, true));
        assertFalse(ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.STOP_LATER, false));
        assertFalse(ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.STOP_NOW, false));
        assertFalse("亮着、或者熄屏时没在录", ScreenOffPlan.rejudgesOnVehicleChange(null, false));
    }

    /** 驾驶中关了屏幕、后来挂 P 停车：重判就是现在停（亮屏接）。 */
    @Test
    public void parkingAfterTheScreenWentOffWhileDrivingStopsNow() {
        assertEquals(ScreenOffPlan.Action.CONTINUE, action(true, false, false, 0, true));
        ScreenOffPlan.Decision parked = ScreenOffPlan.atScreenOff(true, false, false, 0,
                ScreenOffPlan.driving("P", 0f));
        assertEquals(ScreenOffPlan.Action.STOP_NOW, parked.action);
        assertTrue(parked.resumesOnWake);
    }

    /** 熄屏持续录制开着：哨兵模式关、停着 →「熄屏暂停，唤醒后恢复」（现在停、亮屏接），启动自动录制开不开一样。 */
    @Test
    public void keepRecordingWithSentryModeOffPausesWhateverAutoRecordSays() {
        assertEquals(ScreenOffPlan.PAUSE, ScreenOffPlan.of(true, false, false, 0, false));
        assertEquals(ScreenOffPlan.PAUSE, ScreenOffPlan.of(true, false, true, 0, false));
        assertEquals(ScreenOffPlan.NEEDS_SENTRY, ScreenOffPlan.of(true, false, false, null, false));
    }

    /** 熄屏持续录制关着：哨兵模式开没开都停；亮屏后只在启动自动录制开着时接回。 */
    @Test
    public void withKeepRecordingOffOnlyAutoRecordDecidesTheText() {
        for (Integer sentry : ANY_SENTRY) {
            for (boolean driving : BOTH) {
                assertEquals(ScreenOffPlan.PAUSE, ScreenOffPlan.of(false, false, true, sentry, driving));
                assertEquals(ScreenOffPlan.STOP, ScreenOffPlan.of(false, false, false, sentry, driving));
            }
        }
    }
}
