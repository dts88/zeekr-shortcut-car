package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 熄屏后这段录像会怎样：录制协调器、主界面、录制键上的小字照的是同一张表（lifecycle-spec §2.4，项目所有者 2026-10-10）。
 * 「在开车」怎么判、熄屏中哪些要重判、「车机要睡」什么时候写和清，照 channel-logic §四和大纲 §7 的答复（2026-10-11）。
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

    /** 哨兵模式在 D 挡读成 0：在开车时（行驶中、等红灯时关了屏幕）照以前的做法，不因为「哨兵模式关」停录。 */
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

    /**
     * 「在开车」= 挡位读得到、不是 P；读不到挡位时才看车速（读得到、大于 0）。车机只在 P 挡断电：D 挡等红灯（车速 0）
     * 也算在开车（项目所有者 2026-10-11，大纲 §7 第 2 问；以前要车速大于 0，等红灯时熄屏就当场停录、关相机）。
     */
    @Test
    public void drivingMeansAKnownGearOtherThanParkOrElseAMovingSpeed() {
        assertTrue(ScreenOffPlan.driving("D", 30f));
        assertTrue(ScreenOffPlan.driving("R", 3f));
        assertFalse("P 挡", ScreenOffPlan.driving("P", 0f));
        assertFalse("P 挡读到车速也不算", ScreenOffPlan.driving("P", 2f));
        assertTrue("D 挡停着（等红灯）", ScreenOffPlan.driving("D", 0f));
        assertTrue("N 挡也不是 P", ScreenOffPlan.driving("N", 0f));
        assertTrue("挡位读得到就不看车速", ScreenOffPlan.driving("D", null));
        assertTrue("挡位读不到，车速大于 0", ScreenOffPlan.driving(null, 30f));
        assertFalse("挡位读不到，车速 0", ScreenOffPlan.driving(null, 0f));
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
     * 熄屏期间哨兵模式、挡位变了：「接着录」（不是唤醒锁拉着的那种）和「10 秒后停」照表再判 —— 熄屏时在开车、
     * 后来挂 P 下车，或者 10 秒里关了哨兵模式，都不会再有熄屏事件（channel-logic §四「熄屏期间情况变了，重判一次」）。
     * 以前 10 秒后停的不重判，车机在这 10 秒里就睡了。现在停了的不因为信号变了录起来，锁拉着的等锁到点再判。
     */
    @Test
    public void aContinueOrAStopLaterWithoutTheWakeLockIsRejudgedWhenTheCarChanges() {
        assertTrue(ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.CONTINUE, false));
        assertFalse(ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.CONTINUE, true));
        assertTrue("10 秒后停的也重判", ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.STOP_LATER, false));
        assertFalse(ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.STOP_NOW, false));
        assertFalse("亮着、或者熄屏时没在录", ScreenOffPlan.rejudgesOnVehicleChange(null, false));
    }

    /**
     * 车辆信号变了的重判只会停在原处或者判成现在停：设置不变、锁没拉着，哨兵模式、挡位怎么变，接着录的不会变成
     * 10 秒后停，10 秒后停的也不会变成接着录（能往上走的只有开关和唤醒锁）。10 秒后停的判下来还是 10 秒后停，
     * 协调器的计时照原来的走。
     */
    @Test
    public void aRejudgeOnVehicleChangeOnlyStaysOrStopsNow() {
        for (boolean keeps : BOTH) {
            for (boolean autoRecord : BOTH) {
                for (Integer sentryBefore : ANY_SENTRY) {
                    for (boolean drivingBefore : BOTH) {
                        ScreenOffPlan.Action before = action(keeps, false, autoRecord, sentryBefore, drivingBefore);
                        if (!ScreenOffPlan.rejudgesOnVehicleChange(before, false)) {
                            continue;
                        }
                        for (Integer sentryAfter : ANY_SENTRY) {
                            for (boolean drivingAfter : BOTH) {
                                ScreenOffPlan.Action after =
                                        action(keeps, false, autoRecord, sentryAfter, drivingAfter);
                                String row = "keeps=" + keeps + " auto=" + autoRecord + " " + before + " -> " + after;
                                assertTrue(row, after == before || after == ScreenOffPlan.Action.STOP_NOW);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 等红灯（D 挡、0 km/h，哨兵模式在 D 挡读成 0）时熄屏：算在开车，车机不会睡 —— 熄屏持续录制开着接着录，
     * 关着 10 秒后停，不当场停录、关相机（大纲 §7 第 2 问，2026-10-11）。录制键上的小字停着、开着都一样，不来回跳。
     */
    @Test
    public void screenOffAtARedLightCountsAsDriving() {
        boolean redLight = ScreenOffPlan.driving("D", 0f);
        boolean moving = ScreenOffPlan.driving("D", 40f);
        assertFalse(ScreenOffPlan.headUnitSleeps(false, 0, redLight));
        for (boolean autoRecord : BOTH) {
            assertEquals(ScreenOffPlan.Action.CONTINUE, action(true, false, autoRecord, 0, redLight));
            assertEquals(ScreenOffPlan.Action.STOP_LATER, action(false, false, autoRecord, 0, redLight));
            for (boolean keeps : BOTH) {
                assertEquals(ScreenOffPlan.of(keeps, false, autoRecord, 0, moving),
                        ScreenOffPlan.of(keeps, false, autoRecord, 0, redLight));
            }
        }
    }

    /**
     * 熄屏持续录制关着、哨兵模式开着、停在 P 挡：熄屏 10 秒后停。这 10 秒里关掉哨兵模式 —— 车机几秒后就断电：
     * 重判成现在停、写下「车机要睡」，相机按次序关；亮屏接不接和熄屏那一刻定的一样。
     */
    @Test
    public void turningSentryModeOffWithinTheTenSecondsStopsNow() {
        boolean parked = ScreenOffPlan.driving("P", 0f);
        for (boolean autoRecord : BOTH) {
            ScreenOffPlan.Decision atOff = ScreenOffPlan.atScreenOff(false, false, autoRecord, 1, parked);
            assertEquals(ScreenOffPlan.Action.STOP_LATER, atOff.action);
            assertEquals("车机醒着，不写", ScreenOffPlan.SleepFact.KEEP,
                    ScreenOffPlan.sleepFact(true, atOff.action, false));
            assertTrue(ScreenOffPlan.rejudgesOnVehicleChange(atOff.action, false));
            ScreenOffPlan.Decision rejudged = ScreenOffPlan.atScreenOff(false, false, autoRecord, 0, parked);
            assertEquals(ScreenOffPlan.Action.STOP_NOW, rejudged.action);
            assertEquals(atOff.resumesOnWake, rejudged.resumesOnWake);
            assertEquals(ScreenOffPlan.SleepFact.WRITE, ScreenOffPlan.sleepFact(true, rejudged.action, false));
        }
    }

    /**
     * 熄屏时在开车（D 挡，哨兵模式读成 0），熄屏中挂 P：接着录的（熄屏持续录制开着）、10 秒后停的（关着）都重判成现在停，
     * 写下「车机要睡」。
     */
    @Test
    public void shiftingIntoParkWhileDarkStopsNow() {
        boolean inDrive = ScreenOffPlan.driving("D", 0f);
        boolean inPark = ScreenOffPlan.driving("P", 0f);
        for (boolean keeps : BOTH) {
            ScreenOffPlan.Action atOff = action(keeps, false, false, 0, inDrive);
            assertEquals(keeps ? ScreenOffPlan.Action.CONTINUE : ScreenOffPlan.Action.STOP_LATER, atOff);
            assertTrue(ScreenOffPlan.rejudgesOnVehicleChange(atOff, false));
            ScreenOffPlan.Action rejudged = action(keeps, false, false, 0, inPark);
            assertEquals(ScreenOffPlan.Action.STOP_NOW, rejudged);
            assertEquals(ScreenOffPlan.SleepFact.WRITE, ScreenOffPlan.sleepFact(true, rejudged, false));
        }
    }

    /**
     * 「车机要睡」就是表里「现在停」那一行：唤醒锁没拉着、哨兵模式关或读不到、没在开车。在不在录、熄屏持续录制和
     * 启动自动录制开没开都一样 —— 这是车的事实。
     */
    @Test
    public void theHeadUnitSleepsExactlyWhenTheTableSaysStopNow() {
        for (boolean keeps : BOTH) {
            for (boolean lockHeld : BOTH) {
                for (boolean autoRecord : BOTH) {
                    for (Integer sentry : ANY_SENTRY) {
                        for (boolean driving : BOTH) {
                            String row = "keeps=" + keeps + " lock=" + lockHeld + " auto=" + autoRecord
                                    + " sentry=" + sentry + " driving=" + driving;
                            assertEquals(row, ScreenOffPlan.headUnitSleeps(lockHeld, sentry, driving),
                                    action(keeps, lockHeld, autoRecord, sentry, driving)
                                            == ScreenOffPlan.Action.STOP_NOW);
                        }
                    }
                }
            }
        }
        assertTrue("哨兵模式关、没在开车", ScreenOffPlan.headUnitSleeps(false, 0, false));
        assertTrue("哨兵模式读不到算关", ScreenOffPlan.headUnitSleeps(false, null, false));
        assertFalse("唤醒锁拉着", ScreenOffPlan.headUnitSleeps(true, 0, false));
        assertFalse("哨兵模式开", ScreenOffPlan.headUnitSleeps(false, 1, false));
        assertFalse("哨兵模式布防", ScreenOffPlan.headUnitSleeps(false, 2, false));
        assertFalse("在开车", ScreenOffPlan.headUnitSleeps(false, 0, true));
    }

    /** 判成现在停：写下「车机要睡」。已经写着的不再写 —— 起算时刻不挪，调度「最多再等 1 秒」从第一次写下算。 */
    @Test
    public void stopNowWritesTheHeadUnitSleepsFactOnce() {
        assertEquals(ScreenOffPlan.SleepFact.WRITE,
                ScreenOffPlan.sleepFact(true, ScreenOffPlan.Action.STOP_NOW, false));
        assertEquals(ScreenOffPlan.SleepFact.KEEP,
                ScreenOffPlan.sleepFact(true, ScreenOffPlan.Action.STOP_NOW, true));
    }

    /** 亮屏：写着就清（车机醒着了），没写着不动。 */
    @Test
    public void screenOnClearsTheHeadUnitSleepsFact() {
        assertEquals(ScreenOffPlan.SleepFact.CLEAR, ScreenOffPlan.sleepFact(false, null, true));
        assertEquals(ScreenOffPlan.SleepFact.KEEP, ScreenOffPlan.sleepFact(false, null, false));
    }

    /** 10 秒后停的重判成现在停：写下「车机要睡」（熄屏那一刻判 10 秒后停时车机醒着，没写）。 */
    @Test
    public void aStopLaterRejudgedIntoStopNowWritesTheFact() {
        assertEquals(ScreenOffPlan.SleepFact.KEEP,
                ScreenOffPlan.sleepFact(true, ScreenOffPlan.Action.STOP_LATER, false));
        assertEquals(ScreenOffPlan.SleepFact.WRITE,
                ScreenOffPlan.sleepFact(true, ScreenOffPlan.Action.STOP_NOW, false));
    }

    /**
     * 黑着时判成接着录、10 秒后停：「车机要睡」不动 —— 没写的不写；写了的也不清（熄屏中只往下判，相机已经在按次序关，
     * 停了的录像黑着也不接），只有亮屏才清。
     */
    @Test
    public void whileDarkOnlyAStopNowTouchesTheFact() {
        for (boolean written : BOTH) {
            assertEquals(ScreenOffPlan.SleepFact.KEEP,
                    ScreenOffPlan.sleepFact(true, ScreenOffPlan.Action.CONTINUE, written));
            assertEquals(ScreenOffPlan.SleepFact.KEEP,
                    ScreenOffPlan.sleepFact(true, ScreenOffPlan.Action.STOP_LATER, written));
            assertEquals(ScreenOffPlan.SleepFact.KEEP, ScreenOffPlan.sleepFact(true, null, written));
        }
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
