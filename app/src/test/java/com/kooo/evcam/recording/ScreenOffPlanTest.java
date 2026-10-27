package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.camera.ChannelRules.Event.Cause;
import com.kooo.evcam.camera.ChannelRules.SurroundInDark;

import org.junit.Test;

/**
 * 熄屏后这段录像会怎样：录制协调器、录制键上的小字照的是同一张表（lifecycle-spec §2.4，项目所有者 2026-10-10）。
 * 「在开车」怎么判、熄屏中哪些要重判、「车机要睡」什么时候写和清，照 channel-logic §四和大纲 §7 的答复（2026-10-11）。
 * 熄屏 v3（2026-10-12 D1–D3，设计 v3 §3、v3.1 C1）：熄屏 = 屏幕真黑了，车机还醒着几分钟；只有安卓熄屏（车机下电）才判得出现在停；
 * 黑着时只有环视录（熄屏持续录制开着、计划里有环视），座舱屏幕一黑就放开（不归这张表）。
 * 黑满 30 秒以后照通道对环视的熄屏决定（项目所有者 2026-10-27）：放开了就停，熄屏中哨兵模式变成开、留下了才开。
 */
public class ScreenOffPlanTest {

    private static final Integer[] ANY_SENTRY = {0, 1, 2, null};
    private static final Integer[] SENTRY_ON = {1, 2};
    private static final Integer[] SENTRY_OFF_OR_UNKNOWN = {0, null};
    private static final boolean[] BOTH = {false, true};
    private static final SurroundInDark LIT = SurroundInDark.LIT;
    private static final SurroundInDark KEPT = SurroundInDark.KEPT;
    private static final SurroundInDark RELEASED = SurroundInDark.RELEASED;
    /** 通道的决定变了的原因，加上「不是环视留不留变出来的」（null）。 */
    private static final Cause[] ANY_CAUSE = {Cause.DARK_ONSET, Cause.SENTRY_ON, Cause.SENTRY_OFF, Cause.ANDROID_OFF, null};

    /** 计划里有环视（平常的情形）。 */
    private static ScreenOffPlan.Decision judge(boolean keeps, boolean lockHeld, boolean autoRecord, Integer sentry,
                                                boolean driving, boolean androidOff) {
        return ScreenOffPlan.atDark(keeps, lockHeld, autoRecord, sentry, driving, androidOff, true);
    }

    private static ScreenOffPlan.Action action(boolean keeps, boolean lockHeld, boolean autoRecord, Integer sentry,
                                               boolean driving, boolean androidOff) {
        return judge(keeps, lockHeld, autoRecord, sentry, driving, androidOff).action;
    }

    /**
     * 录制键上那一句，和 {@code ScreenOffHintView} 一样算：熄屏那一刻（车辆信号判着屏幕时安卓还亮着；判不出时 —— 降级 ——
     * 安卓熄屏）和之后车机要睡那一刻（同样的输入，安卓熄屏了）各照表判一次。
     */
    private static ScreenOffPlan hint(boolean keeps, boolean lockHeld, boolean autoRecord, Integer sentry, boolean driving,
                                      boolean degraded, boolean surround) {
        return ScreenOffPlan.of(
                ScreenOffPlan.atDark(keeps, lockHeld, autoRecord, sentry, driving, degraded, surround),
                ScreenOffPlan.atDark(keeps, lockHeld, autoRecord, sentry, driving, true, surround));
    }

    /** 车辆信号判着屏幕、停在 P 挡、哨兵模式关、唤醒锁不拉着时录制键上那一句。 */
    private static ScreenOffPlan hint(boolean keeps, boolean autoRecord, boolean surround) {
        return hint(keeps, false, autoRecord, 0, false, false, surround);
    }

    /**
     * 屏幕黑了、车机还醒着（安卓亮着）：怎样都不会现在停 —— 车机几分钟后才下电。熄屏持续录制开着接着录环视，关着 10 秒后停；
     * 哨兵模式、挡位、启动自动录制都改不了（以前熄屏那一刻哨兵模式没开就现在停，那时安卓的熄屏就是下电）。
     */
    @Test
    public void whileTheHeadUnitIsAwakeTheDarkNeverStopsNow() {
        for (boolean lockHeld : BOTH) {
            for (boolean autoRecord : BOTH) {
                for (Integer sentry : ANY_SENTRY) {
                    for (boolean driving : BOTH) {
                        String row = "lock=" + lockHeld + " auto=" + autoRecord + " sentry=" + sentry + " driving=" + driving;
                        assertEquals(row, ScreenOffPlan.Action.CONTINUE, action(true, lockHeld, autoRecord, sentry, driving, false));
                        assertEquals(row, lockHeld ? ScreenOffPlan.Action.CONTINUE : ScreenOffPlan.Action.STOP_LATER,
                                action(false, lockHeld, autoRecord, sentry, driving, false));
                        assertFalse(row, ScreenOffPlan.headUnitSleeps(false, lockHeld, sentry, driving));
                    }
                }
            }
        }
    }

    /**
     * 哨兵模式没开离车、熄屏持续录制开着（项目所有者 2026-10-12 D3）：屏幕一黑接着录环视（座舱放开），几分钟后安卓熄屏
     * （车机要睡）照表再判 —— 现在停、写下「车机要睡」。这是照亮着算时的那张表；2026-10-27 起黑满 30 秒时通道放开环视就停
     * （{@link #theChannelReleasingTheSurroundStopsNowWithoutTheSleepFact}），车机要睡那一下照样写「车机要睡」。
     */
    @Test
    public void sentryOffLeaveRecordsTheSurroundUntilTheHeadUnitSleeps() {
        boolean parked = ScreenOffPlan.driving("P", 0f);
        for (boolean autoRecord : BOTH) {
            for (Integer sentry : SENTRY_OFF_OR_UNKNOWN) {
                ScreenOffPlan.Decision atDark = judge(true, false, autoRecord, sentry, parked, false);
                assertEquals(ScreenOffPlan.Action.CONTINUE, atDark.action);
                assertEquals("车机醒着，不写", ScreenOffPlan.SleepFact.KEEP,
                        ScreenOffPlan.sleepFact(false, atDark.action, false));
                ScreenOffPlan.Decision atSleep = judge(true, false, autoRecord, sentry, parked, true);
                assertEquals(ScreenOffPlan.Action.STOP_NOW, atSleep.action);
                assertTrue("醒来接着录", atSleep.resumesOnWake);
                assertEquals(ScreenOffPlan.SleepFact.WRITE, ScreenOffPlan.sleepFact(true, atSleep.action, false));
            }
        }
    }

    /** 哨兵模式开着（1 开、2 布防）：车机一直醒着，安卓熄屏了也接着录（熄屏持续录制开着），关着的 10 秒后停。 */
    @Test
    public void withSentryModeOnTheHeadUnitStaysAwake() {
        for (Integer sentry : SENTRY_ON) {
            for (boolean driving : BOTH) {
                for (boolean androidOff : BOTH) {
                    assertEquals(ScreenOffPlan.Action.CONTINUE, action(true, false, false, sentry, driving, androidOff));
                    assertEquals(ScreenOffPlan.Action.STOP_LATER, action(false, false, false, sentry, driving, androidOff));
                    assertEquals(ScreenOffPlan.Action.STOP_LATER, action(false, false, true, sentry, driving, androidOff));
                }
            }
        }
    }

    /** 熄屏持续录制关着：熄屏 10 秒后停，哨兵模式开没开都一样（车机醒着，这 10 秒走得完）；安卓熄屏时哨兵模式没开才现在停。 */
    @Test
    public void keepRecordingOffStopsLaterWhateverTheSentrySays() {
        for (Integer sentry : ANY_SENTRY) {
            for (boolean autoRecord : BOTH) {
                assertEquals(ScreenOffPlan.Action.STOP_LATER, action(false, false, autoRecord, sentry, false, false));
            }
        }
        for (Integer sentry : SENTRY_OFF_OR_UNKNOWN) {
            assertEquals(ScreenOffPlan.Action.STOP_NOW, action(false, false, false, sentry, false, true));
        }
    }

    /** 安卓熄屏了（车机下电）、哨兵模式没开（或读不到）、车没在走、锁没拉着：现在停，相机按次序关（2026-10-10）。 */
    @Test
    public void theHeadUnitSleepingWithoutSentryModeStopsNow() {
        for (Integer sentry : SENTRY_OFF_OR_UNKNOWN) {
            for (boolean keeps : BOTH) {
                for (boolean autoRecord : BOTH) {
                    assertEquals(ScreenOffPlan.Action.STOP_NOW, action(keeps, false, autoRecord, sentry, false, true));
                }
            }
        }
    }

    /** 哨兵模式在 D 挡读成 0：在开车时安卓熄屏了也不因为「哨兵模式关」停录（照以前的做法）。 */
    @Test
    public void drivingWithSentryModeReadingOffKeepsTodaysBehaviour() {
        for (Integer sentry : SENTRY_OFF_OR_UNKNOWN) {
            for (boolean androidOff : BOTH) {
                assertEquals(ScreenOffPlan.Action.CONTINUE, action(true, false, false, sentry, true, androidOff));
                assertEquals(ScreenOffPlan.Action.STOP_LATER, action(false, false, true, sentry, true, androidOff));
            }
        }
    }

    /** 开发者的唤醒锁拉着车机：哨兵模式开没开、车在不在走、安卓熄没熄屏，都接着录。 */
    @Test
    public void aHeldWakeLockKeepsRecordingWhateverTheCarSays() {
        for (Integer sentry : ANY_SENTRY) {
            for (boolean driving : BOTH) {
                for (boolean androidOff : BOTH) {
                    for (boolean autoRecord : BOTH) {
                        ScreenOffPlan.Decision d = judge(true, true, autoRecord, sentry, driving, androidOff);
                        assertEquals(ScreenOffPlan.Action.CONTINUE, d.action);
                        assertFalse("接着录就没有「亮屏接回」", d.resumesOnWake);
                    }
                }
            }
        }
    }

    /**
     * 计划里没有环视：黑着时没有可录的（座舱屏幕一黑就放开），和熄屏持续录制关着一样 —— 10 秒后停，亮屏照开关接；
     * 不另开分支，公式本身就是。黑着时也不再开录。
     */
    @Test
    public void aPlanWithoutTheSurroundBehavesLikeKeepRecordingOff() {
        for (boolean keeps : BOTH) {
            for (boolean lockHeld : BOTH) {
                for (boolean autoRecord : BOTH) {
                    for (Integer sentry : ANY_SENTRY) {
                        for (boolean driving : BOTH) {
                            for (boolean androidOff : BOTH) {
                                ScreenOffPlan.Decision without = ScreenOffPlan.atDark(keeps, lockHeld, autoRecord, sentry,
                                        driving, androidOff, false);
                                assertNotEquals(ScreenOffPlan.Action.CONTINUE, without.action);
                                assertEquals(ScreenOffPlan.headUnitSleeps(androidOff, lockHeld, sentry, driving)
                                        ? ScreenOffPlan.Action.STOP_NOW : ScreenOffPlan.Action.STOP_LATER, without.action);
                                assertEquals(keeps || autoRecord, without.resumesOnWake);
                            }
                        }
                    }
                }
            }
            for (SurroundInDark channel : SurroundInDark.values()) {
                for (boolean bySentryRise : BOTH) {
                    assertEquals(ScreenOffPlan.DarkStart.NO_SURROUND,
                            ScreenOffPlan.startsWhileDark(channel, bySentryRise, false, keeps, keeps, false, true));
                }
            }
        }
        assertEquals(ScreenOffPlan.PAUSE, hint(true, false, false));
        assertEquals(ScreenOffPlan.PAUSE, hint(false, true, false));
        assertEquals(ScreenOffPlan.STOP, hint(false, false, false));
    }

    /** 停了之后亮屏接不接：熄屏持续录制、启动自动录制开着一个就接（现在停、10 秒后停都一样）。 */
    @Test
    public void aStopResumesOnWakeWhenKeepRecordingOrAutoRecordIsOn() {
        for (Integer sentry : SENTRY_OFF_OR_UNKNOWN) {
            assertTrue(judge(true, false, false, sentry, false, true).resumesOnWake);
            assertTrue(judge(true, false, true, sentry, false, true).resumesOnWake);
            assertTrue(judge(false, false, true, sentry, false, true).resumesOnWake);
            assertFalse(judge(false, false, false, sentry, false, true).resumesOnWake);
        }
        for (boolean androidOff : BOTH) {
            assertTrue(judge(false, false, true, 1, false, androidOff).resumesOnWake);
            assertFalse(judge(false, false, false, 1, false, androidOff).resumesOnWake);
        }
    }

    /**
     * 照亮着算（通道的决定是 LIT：黑了不到 30 秒、在开车）时黑着开不开录和以前一样：车机醒着、熄屏后不停录、计划里有环视
     * （只录环视；设计 v3.1 C1）。是不是哨兵模式变成开那一下、「熄屏持续录制」本身、能不能用相机都不看。
     */
    @Test
    public void whileTheChannelCountsTheScreenLitTheOldStartRuleHolds() {
        for (boolean bySentryRise : BOTH) {
            for (boolean androidOff : BOTH) {
                for (boolean keeps : BOTH) {
                    for (boolean keepSetting : BOTH) {
                        for (boolean surround : BOTH) {
                            for (boolean usable : BOTH) {
                                String row = "sentryRise=" + bySentryRise + " androidOff=" + androidOff + " keeps=" + keeps
                                        + " setting=" + keepSetting + " surround=" + surround + " usable=" + usable;
                                assertEquals(row, !androidOff && keeps && surround,
                                        ScreenOffPlan.startsWhileDark(LIT, bySentryRise, androidOff, keeps, keepSetting,
                                                surround, usable) == ScreenOffPlan.DarkStart.STARTS);
                            }
                        }
                    }
                }
            }
        }
        assertEquals(ScreenOffPlan.DarkStart.SETTING_OFF,
                ScreenOffPlan.startsWhileDark(LIT, false, false, false, false, true, true));
        for (boolean autoRecord : BOTH) {
            assertEquals("黑着能开录的，熄屏那一刻也是接着录", ScreenOffPlan.Action.CONTINUE,
                    action(true, false, autoRecord, null, false, false));
        }
    }

    /**
     * 黑满 30 秒以后（项目所有者 2026-10-27）：通道留着环视时只有熄屏中哨兵模式变成开那一下的接回开录，能用相机才开 ——
     * 恢复的例行接回、启动自动录制、人按的（悬浮按钮）、存储停了从恢复来的接回都不开（不为中间不明原因的启动去试着录）。
     * 通道放开了：不开；熄屏持续录制关着的照实说是设置（先于「通道没留」）。车机要睡最先。
     */
    @Test
    public void afterThirtySecondsOnlyASentryRiseWithAUsableCameraStarts() {
        assertEquals(ScreenOffPlan.DarkStart.STARTS,
                ScreenOffPlan.startsWhileDark(KEPT, true, false, true, true, true, true));
        assertEquals("用不了相机：不开，不进等待摄像头", ScreenOffPlan.DarkStart.CAMERA_UNUSABLE,
                ScreenOffPlan.startsWhileDark(KEPT, true, false, true, true, true, false));
        for (boolean usable : BOTH) {
            assertEquals("通道留着，可不是哨兵模式变成开那一下", ScreenOffPlan.DarkStart.NO_SENTRY_RISE,
                    ScreenOffPlan.startsWhileDark(KEPT, false, false, true, true, true, usable));
            for (boolean bySentryRise : BOTH) {
                assertEquals(ScreenOffPlan.DarkStart.CHANNEL_RELEASED,
                        ScreenOffPlan.startsWhileDark(RELEASED, bySentryRise, false, true, true, true, usable));
                for (boolean keeps : BOTH) {
                    assertEquals("熄屏持续录制关着：照实说是设置", ScreenOffPlan.DarkStart.SETTING_OFF,
                            ScreenOffPlan.startsWhileDark(RELEASED, bySentryRise, false, keeps, false, true, usable));
                    for (SurroundInDark channel : SurroundInDark.values()) {
                        assertEquals("车机要睡最先", ScreenOffPlan.DarkStart.HEAD_UNIT_SLEEPS,
                                ScreenOffPlan.startsWhileDark(channel, bySentryRise, true, keeps, keeps, true, usable));
                    }
                }
            }
        }
        // 进程在黑着时起来（通道的决定从放开起）：恢复来的接回不开，哨兵模式变成开之前什么都不开
        assertNotEquals(ScreenOffPlan.DarkStart.STARTS,
                ScreenOffPlan.startsWhileDark(RELEASED, false, false, true, true, true, false));
        // 在开车：通道照亮着算，照以前的规矩
        assertEquals(ScreenOffPlan.DarkStart.STARTS,
                ScreenOffPlan.startsWhileDark(LIT, false, false, true, true, true, false));
    }

    /**
     * 通道的决定变了协调器做什么（{@link ScreenOffPlan#onChannel}）：亮着什么都不做；在录（含等开录、等自动接回）时通道放开了就停，
     * 不管为什么放开（黑满 30 秒、哨兵关了、车机要睡）；没在录时只有熄屏中哨兵模式变成开（醒来读到也算）留下的才开 ——
     * 黑满 30 秒那一下留着、没在录的不开（R1）。
     */
    @Test
    public void theCoordinatorStopsOnAReleaseAndStartsOnlyOnASentryRise() {
        for (Cause cause : ANY_CAUSE) {
            for (SurroundInDark verdict : SurroundInDark.values()) {
                for (boolean busy : BOTH) {
                    assertEquals("亮着", ScreenOffPlan.ChannelStep.NOTHING,
                            ScreenOffPlan.onChannel(verdict, cause, false, busy));
                }
            }
            assertEquals("在录、通道放开了：停（" + cause + "）", ScreenOffPlan.ChannelStep.STOP,
                    ScreenOffPlan.onChannel(RELEASED, cause, true, true));
            assertEquals("在录、通道留着：接着录", ScreenOffPlan.ChannelStep.NOTHING,
                    ScreenOffPlan.onChannel(KEPT, cause, true, true));
            assertEquals("在录、照亮着算：照那张表", ScreenOffPlan.ChannelStep.NOTHING,
                    ScreenOffPlan.onChannel(LIT, cause, true, true));
            assertEquals("没在录、通道放开了", ScreenOffPlan.ChannelStep.NOTHING,
                    ScreenOffPlan.onChannel(RELEASED, cause, true, false));
            assertEquals("没在录、照亮着算", ScreenOffPlan.ChannelStep.NOTHING,
                    ScreenOffPlan.onChannel(LIT, cause, true, false));
            assertEquals("没在录、通道留着：只有哨兵模式变成开那一下开（" + cause + "）",
                    cause == Cause.SENTRY_ON ? ScreenOffPlan.ChannelStep.START : ScreenOffPlan.ChannelStep.NOTHING,
                    ScreenOffPlan.onChannel(KEPT, cause, true, false));
        }
        assertEquals("黑满 30 秒那一下留着、没在录：不开", ScreenOffPlan.ChannelStep.NOTHING,
                ScreenOffPlan.onChannel(KEPT, Cause.DARK_ONSET, true, false));
        assertEquals("车机要睡放开、在录：停", ScreenOffPlan.ChannelStep.STOP,
                ScreenOffPlan.onChannel(RELEASED, Cause.ANDROID_OFF, true, true));
    }

    /**
     * 协调器读的通道决定：通道还没把屏幕算黑（亮着、黑了不到 30 秒）时一律照亮着算，不管调度发布的是什么 ——
     * 熄屏那一刻、头 30 秒不靠调度跑没跑过一轮（进程起来时发布的是放开）。算黑了照发布的。
     */
    @Test
    public void theCoordinatorReadsLitUntilTheChannelCountsTheScreenDark() {
        for (SurroundInDark published : SurroundInDark.values()) {
            assertEquals(LIT, ScreenOffPlan.channelVerdict(false, published));
            assertEquals(published, ScreenOffPlan.channelVerdict(true, published));
        }
    }

    /**
     * 通道放开了环视（黑满 30 秒时哨兵模式没开、熄屏中哨兵关了、熄屏持续录制关着；车机要睡时安卓熄屏了而车机不睡的那一种也是）：
     * 车机醒着时现在停，不写「车机要睡」；「这一趟要录」照表留（熄屏后不停录、启动自动录制开着一个就留）。
     */
    @Test
    public void theChannelReleasingTheSurroundStopsNowWithoutTheSleepFact() {
        for (boolean keeps : BOTH) {
            for (boolean lockHeld : BOTH) {
                for (boolean autoRecord : BOTH) {
                    for (Integer sentry : ANY_SENTRY) {
                        for (boolean driving : BOTH) {
                            for (boolean surround : BOTH) {
                                String row = "keeps=" + keeps + " lock=" + lockHeld + " auto=" + autoRecord + " sentry=" + sentry
                                        + " driving=" + driving + " surround=" + surround;
                                ScreenOffPlan.Decision d = ScreenOffPlan.atDark(keeps, lockHeld, autoRecord, sentry, driving,
                                        false, surround, RELEASED);
                                assertEquals(row, ScreenOffPlan.Action.STOP_RELEASED, d.action);
                                assertEquals(row, keeps || autoRecord, d.resumesOnWake);
                                for (boolean written : BOTH) {
                                    assertEquals(row, written ? ScreenOffPlan.SleepFact.CLEAR : ScreenOffPlan.SleepFact.KEEP,
                                            ScreenOffPlan.sleepFact(false, d.action, written));
                                }
                            }
                        }
                    }
                }
            }
        }
        // 安卓熄屏了而车机不睡（哨兵模式开着、唤醒锁拉着）：通道放开了（车机要睡），停，不写「车机要睡」
        ScreenOffPlan.Decision armed = ScreenOffPlan.atDark(true, false, false, 2, false, true, true, RELEASED);
        assertEquals(ScreenOffPlan.Action.STOP_RELEASED, armed.action);
        assertEquals(ScreenOffPlan.SleepFact.KEEP, ScreenOffPlan.sleepFact(true, armed.action, false));
        assertFalse("已经停了：车辆信号变了不重判",
                ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.STOP_RELEASED, false, true));
    }

    /** 车机要睡（安卓熄屏、哨兵模式没开、没在开车、唤醒锁没拉着）照样现在停、写「车机要睡」，不管通道的决定是什么。 */
    @Test
    public void aHeadUnitThatSleepsStillWritesTheSleepFactWhateverTheChannel() {
        for (SurroundInDark channel : SurroundInDark.values()) {
            for (Integer sentry : SENTRY_OFF_OR_UNKNOWN) {
                for (boolean keeps : BOTH) {
                    for (boolean autoRecord : BOTH) {
                        ScreenOffPlan.Decision d = ScreenOffPlan.atDark(keeps, false, autoRecord, sentry, false, true, true,
                                channel);
                        assertEquals(channel + " " + sentry, ScreenOffPlan.Action.STOP_NOW, d.action);
                        assertEquals(keeps || autoRecord, d.resumesOnWake);
                        assertEquals(ScreenOffPlan.SleepFact.WRITE, ScreenOffPlan.sleepFact(true, d.action, false));
                    }
                }
            }
        }
    }

    /** 通道照亮着算（LIT）或者留着环视（KEPT）：那张表和以前一模一样，每一种输入都一样。 */
    @Test
    public void aKeptOrUndecidedChannelLeavesTheTableAsItWas() {
        for (SurroundInDark channel : new SurroundInDark[]{LIT, KEPT}) {
            for (boolean keeps : BOTH) {
                for (boolean lockHeld : BOTH) {
                    for (boolean autoRecord : BOTH) {
                        for (Integer sentry : ANY_SENTRY) {
                            for (boolean driving : BOTH) {
                                for (boolean androidOff : BOTH) {
                                    for (boolean surround : BOTH) {
                                        ScreenOffPlan.Decision before = ScreenOffPlan.atDark(keeps, lockHeld, autoRecord,
                                                sentry, driving, androidOff, surround);
                                        ScreenOffPlan.Decision now = ScreenOffPlan.atDark(keeps, lockHeld, autoRecord,
                                                sentry, driving, androidOff, surround, channel);
                                        String row = channel + " keeps=" + keeps + " lock=" + lockHeld + " auto="
                                                + autoRecord + " sentry=" + sentry + " driving=" + driving
                                                + " androidOff=" + androidOff + " surround=" + surround;
                                        assertEquals(row, before.action, now.action);
                                        assertEquals(row, before.resumesOnWake, now.resumesOnWake);
                                        assertNotEquals(row, ScreenOffPlan.Action.STOP_RELEASED, now.action);
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
     * 录制键上的小字就是协调器照表判的那两下（项目所有者 2026-10-13，不另立规矩）：熄屏那一刻（E1）和同样的输入、
     * 车机要睡那一刻（E2，安卓熄屏了）。两下都接着录 →「熄屏后继续录制环视」（哨兵模式开着或布防、在开车、唤醒锁拉着）；
     * 熄屏接着录、车机要睡现在停 →「熄屏暂停，亮屏后恢复」（车主 2026-10-13：不另加句子）；熄屏那一刻就停 → 亮屏接的「熄屏暂停，亮屏后恢复」，
     * 不接的「熄屏后停止录制」。车辆信号判不出屏幕（降级）时熄屏就是车机下电，两下是同一下。
     *
     * <p>每一种输入都走一遍（哨兵模式 0/1/2/读不到、在开车、唤醒锁、熄屏后不停录、启动自动录制、计划里有环视、降级），
     * 对的是照车主的话直接写出来的期望，不是照着实现抄的。</p>
     */
    @Test
    public void theRecordButtonTextIsTheTableAtTheDarkAndAtTheSleep() {
        for (boolean keeps : BOTH) {
            for (boolean lockHeld : BOTH) {
                for (boolean autoRecord : BOTH) {
                    for (Integer sentry : ANY_SENTRY) {
                        for (boolean driving : BOTH) {
                            for (boolean surround : BOTH) {
                                for (boolean degraded : BOTH) {
                                    String row = "keeps=" + keeps + " lock=" + lockHeld + " auto=" + autoRecord
                                            + " sentry=" + sentry + " driving=" + driving + " surround=" + surround
                                            + " degraded=" + degraded;
                                    // 车机睡不了：唤醒锁拉着、哨兵模式开着或布防、在开车
                                    boolean staysAwake = lockHeld || (sentry != null && sentry != 0) || driving;
                                    // 熄屏那一刻接着录：熄屏后不停录（或锁拉着）、计划里有环视；降级时熄屏就是车机下电，还得车机睡不了
                                    boolean recordsAtDark = (keeps || lockHeld) && surround && (!degraded || staysAwake);
                                    ScreenOffPlan expected = recordsAtDark
                                            ? (staysAwake ? ScreenOffPlan.CONTINUE : ScreenOffPlan.PAUSE)
                                            : (keeps || autoRecord) ? ScreenOffPlan.PAUSE : ScreenOffPlan.STOP;
                                    assertEquals(row, expected,
                                            hint(keeps, lockHeld, autoRecord, sentry, driving, degraded, surround));

                                    ScreenOffPlan.Decision atDark = ScreenOffPlan.atDark(keeps, lockHeld, autoRecord, sentry,
                                            driving, degraded, surround);
                                    ScreenOffPlan.Decision atSleep = ScreenOffPlan.atDark(keeps, lockHeld, autoRecord, sentry,
                                            driving, true, surround);
                                    if (atDark.action == ScreenOffPlan.Action.CONTINUE) {
                                        assertNotEquals("熄屏接着录的，车机要睡时只会接着录或现在停", ScreenOffPlan.Action.STOP_LATER,
                                                atSleep.action);
                                    }
                                    if (recordsAtDark && !staysAwake) {
                                        assertTrue(row + "：车机休眠时暂停 —— 停了必定接", atSleep.resumesOnWake);
                                        if (keeps) {
                                            // 熄屏持续录制开着：睡着时哨兵模式被打开、醒来读到（通道留下环视）也接得回来
                                            assertEquals(row, ScreenOffPlan.DarkStart.STARTS, ScreenOffPlan.startsWhileDark(
                                                    KEPT, true, false, keeps, true, surround, true));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals("三句，没有「需开启哨兵模式」", 3, ScreenOffPlan.values().length);
    }

    /** 车主那几句话，一句一个例子：熄屏持续录制开着、计划里有环视、车辆信号判着屏幕、唤醒锁不拉着、停在 P 挡。 */
    @Test
    public void theRecordButtonTextSaysWhetherRecordingOutlastsTheHeadUnitSleep() {
        boolean parked = ScreenOffPlan.driving("P", 0f);
        boolean inDrive = ScreenOffPlan.driving("D", 0f);
        for (boolean autoRecord : BOTH) {
            assertEquals("哨兵模式关：环视录到车机休眠，写「熄屏暂停，亮屏后恢复」", ScreenOffPlan.PAUSE,
                    hint(true, false, autoRecord, 0, parked, false, true));
            assertEquals("哨兵模式读不到算关", ScreenOffPlan.PAUSE,
                    hint(true, false, autoRecord, null, parked, false, true));
            assertEquals("哨兵模式开着：车机不睡", ScreenOffPlan.CONTINUE, hint(true, false, autoRecord, 1, parked, false, true));
            assertEquals("哨兵模式布防：车机不睡", ScreenOffPlan.CONTINUE, hint(true, false, autoRecord, 2, parked, false, true));
            assertEquals("在开车（D 挡等红灯也算）", ScreenOffPlan.CONTINUE, hint(true, false, autoRecord, 0, inDrive, false, true));
            assertEquals("开发者的唤醒锁拉着车机", ScreenOffPlan.CONTINUE, hint(true, true, autoRecord, 0, parked, false, true));
        }
        assertEquals("熄屏持续录制关、启动自动录制开", ScreenOffPlan.PAUSE, hint(false, true, true));
        assertEquals("都关", ScreenOffPlan.STOP, hint(false, false, true));
        assertEquals("计划里没有环视：和熄屏持续录制关着一样", ScreenOffPlan.PAUSE, hint(true, false, false));
    }

    /**
     * 车辆信号判不出屏幕（降级：车辆接口没连着、两项读不到）：熄屏只能是安卓熄屏 = 车机下电，熄屏和车机要睡是同一下 ——
     * 熄屏持续录制开着、哨兵模式没开或读不到、没在开车、唤醒锁没拉着：现在停、亮屏接，写「熄屏暂停，亮屏后恢复」，
     * 不写「继续录制」（熄屏那一下就停了，审查 rev3 B1）。唤醒锁拉着、哨兵模式开着、在开车的，照样接着录。
     */
    @Test
    public void whenTheVehicleCannotTellTheScreenTheHintIsTheHeadUnitSleeping() {
        for (Integer sentry : SENTRY_OFF_OR_UNKNOWN) {
            for (boolean autoRecord : BOTH) {
                ScreenOffPlan.Decision atDark = ScreenOffPlan.atDark(true, false, autoRecord, sentry, false, true, true);
                assertEquals(ScreenOffPlan.Action.STOP_NOW, atDark.action);
                assertEquals("熄屏那一下就停，亮屏接", ScreenOffPlan.PAUSE,
                        hint(true, false, autoRecord, sentry, false, true, true));
                assertEquals("车辆判着时同样的设置也是这一句（环视录到车机休眠）", ScreenOffPlan.PAUSE,
                        hint(true, false, autoRecord, sentry, false, false, true));
            }
            assertEquals(ScreenOffPlan.PAUSE, hint(false, false, true, sentry, false, true, true));
            assertEquals(ScreenOffPlan.STOP, hint(false, false, false, sentry, false, true, true));
            assertEquals("唤醒锁拉着车机：接着录", ScreenOffPlan.CONTINUE, hint(true, true, false, sentry, false, true, true));
            assertEquals("在开车：接着录", ScreenOffPlan.CONTINUE, hint(true, false, false, sentry, true, true, true));
        }
        for (Integer sentry : SENTRY_ON) {
            assertEquals("哨兵模式开着（读得到）：车机不下电，接着录", ScreenOffPlan.CONTINUE,
                    hint(true, false, false, sentry, false, true, true));
        }
    }

    /**
     * 熄屏期间哨兵模式、挡位变了：「接着录」（不是唤醒锁拉着的那种）和「10 秒后停」照表再判 —— 车机要睡时在开车、
     * 后来挂 P 下车，或者关了哨兵模式，都不会再有熄屏事件（channel-logic §四「熄屏期间情况变了，重判一次」）。
     * 现在停了的、通道放开了停的不因为信号变了录起来，锁拉着的等锁到点再判。只在安卓熄屏着时重判（2026-10-27）：
     * 车机醒着时重判改不了结果，哨兵模式变了归通道管。
     */
    @Test
    public void aContinueOrAStopLaterWithoutTheWakeLockIsRejudgedWhenTheCarChanges() {
        assertTrue(ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.CONTINUE, false, true));
        assertFalse(ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.CONTINUE, true, true));
        assertTrue("10 秒后停的也重判", ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.STOP_LATER, false, true));
        assertFalse(ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.STOP_NOW, false, true));
        assertFalse("通道放开了停的", ScreenOffPlan.rejudgesOnVehicleChange(ScreenOffPlan.Action.STOP_RELEASED, false, true));
        assertFalse("亮着", ScreenOffPlan.rejudgesOnVehicleChange(null, false, true));
        for (ScreenOffPlan.Action action : ScreenOffPlan.Action.values()) {
            for (boolean byLock : BOTH) {
                assertFalse("车机醒着（安卓亮着）：不重判", ScreenOffPlan.rejudgesOnVehicleChange(action, byLock, false));
            }
        }
    }

    /**
     * 车辆信号变了的重判只会停在原处或者判成现在停：设置不变、锁没拉着，哨兵模式、挡位怎么变，接着录的不会变成
     * 10 秒后停，10 秒后停的也不会变成接着录。车机醒着（安卓亮着）时连现在停也判不出：结果不变 —— 所以那时不重判。
     */
    @Test
    public void aRejudgeOnVehicleChangeOnlyStaysOrStopsNow() {
        for (boolean androidOff : BOTH) {
            for (boolean keeps : BOTH) {
                for (boolean autoRecord : BOTH) {
                    for (Integer sentryBefore : ANY_SENTRY) {
                        for (boolean drivingBefore : BOTH) {
                            ScreenOffPlan.Action before = action(keeps, false, autoRecord, sentryBefore, drivingBefore,
                                    androidOff);
                            boolean rejudged = ScreenOffPlan.rejudgesOnVehicleChange(before, false, androidOff);
                            assertEquals(androidOff && before != ScreenOffPlan.Action.STOP_NOW, rejudged);
                            if (androidOff && !rejudged) {
                                continue;
                            }
                            for (Integer sentryAfter : ANY_SENTRY) {
                                for (boolean drivingAfter : BOTH) {
                                    ScreenOffPlan.Action after =
                                            action(keeps, false, autoRecord, sentryAfter, drivingAfter, androidOff);
                                    String row = "androidOff=" + androidOff + " keeps=" + keeps + " auto=" + autoRecord
                                            + " " + before + " -> " + after;
                                    assertTrue(row, after == before || after == ScreenOffPlan.Action.STOP_NOW);
                                    if (!androidOff) {
                                        assertEquals(row, before, after);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 等红灯（D 挡、0 km/h，哨兵模式在 D 挡读成 0）时安卓熄屏：算在开车，车机不会睡 —— 熄屏持续录制开着接着录，
     * 关着 10 秒后停，不当场停录、关相机（大纲 §7 第 2 问，2026-10-11）。
     */
    @Test
    public void screenOffAtARedLightCountsAsDriving() {
        boolean redLight = ScreenOffPlan.driving("D", 0f);
        assertFalse(ScreenOffPlan.headUnitSleeps(true, false, 0, redLight));
        for (boolean autoRecord : BOTH) {
            assertEquals(ScreenOffPlan.Action.CONTINUE, action(true, false, autoRecord, 0, redLight, true));
            assertEquals(ScreenOffPlan.Action.STOP_LATER, action(false, false, autoRecord, 0, redLight, true));
        }
    }

    /**
     * 熄屏持续录制关着、哨兵模式开着、停在 P 挡、安卓熄屏了（车机醒着：哨兵模式拉着）：10 秒后停。这 10 秒里关掉哨兵模式 ——
     * 车机几秒后就断电：重判成现在停、写下「车机要睡」，相机按次序关；亮屏接不接和熄屏那一刻定的一样。
     */
    @Test
    public void turningSentryModeOffWithinTheTenSecondsStopsNow() {
        boolean parked = ScreenOffPlan.driving("P", 0f);
        for (boolean autoRecord : BOTH) {
            ScreenOffPlan.Decision atOff = judge(false, false, autoRecord, 1, parked, true);
            assertEquals(ScreenOffPlan.Action.STOP_LATER, atOff.action);
            assertEquals("车机醒着，不写", ScreenOffPlan.SleepFact.KEEP,
                    ScreenOffPlan.sleepFact(true, atOff.action, false));
            assertTrue(ScreenOffPlan.rejudgesOnVehicleChange(atOff.action, false, true));
            ScreenOffPlan.Decision rejudged = judge(false, false, autoRecord, 0, parked, true);
            assertEquals(ScreenOffPlan.Action.STOP_NOW, rejudged.action);
            assertEquals(atOff.resumesOnWake, rejudged.resumesOnWake);
            assertEquals(ScreenOffPlan.SleepFact.WRITE, ScreenOffPlan.sleepFact(true, rejudged.action, false));
        }
    }

    /** 安卓熄屏时在开车（D 挡，哨兵模式读成 0），之后挂 P：接着录的、10 秒后停的都重判成现在停，写下「车机要睡」。 */
    @Test
    public void shiftingIntoParkWhileTheHeadUnitIsOffStopsNow() {
        boolean inDrive = ScreenOffPlan.driving("D", 0f);
        boolean inPark = ScreenOffPlan.driving("P", 0f);
        for (boolean keeps : BOTH) {
            ScreenOffPlan.Action atOff = action(keeps, false, false, 0, inDrive, true);
            assertEquals(keeps ? ScreenOffPlan.Action.CONTINUE : ScreenOffPlan.Action.STOP_LATER, atOff);
            assertTrue(ScreenOffPlan.rejudgesOnVehicleChange(atOff, false, true));
            ScreenOffPlan.Action rejudged = action(keeps, false, false, 0, inPark, true);
            assertEquals(ScreenOffPlan.Action.STOP_NOW, rejudged);
            assertEquals(ScreenOffPlan.SleepFact.WRITE, ScreenOffPlan.sleepFact(true, rejudged, false));
        }
    }

    /**
     * 「车机要睡」就是表里「现在停」那一行：安卓熄屏了、唤醒锁没拉着、哨兵模式关或读不到、没在开车。在不在录、
     * 熄屏持续录制和启动自动录制开没开、计划里有没有环视都一样 —— 这是车的事实。
     */
    @Test
    public void theHeadUnitSleepsExactlyWhenTheTableSaysStopNow() {
        for (boolean keeps : BOTH) {
            for (boolean lockHeld : BOTH) {
                for (boolean autoRecord : BOTH) {
                    for (Integer sentry : ANY_SENTRY) {
                        for (boolean driving : BOTH) {
                            for (boolean androidOff : BOTH) {
                                for (boolean surround : BOTH) {
                                    String row = "keeps=" + keeps + " lock=" + lockHeld + " auto=" + autoRecord
                                            + " sentry=" + sentry + " driving=" + driving + " androidOff=" + androidOff
                                            + " surround=" + surround;
                                    assertEquals(row, ScreenOffPlan.headUnitSleeps(androidOff, lockHeld, sentry, driving),
                                            ScreenOffPlan.atDark(keeps, lockHeld, autoRecord, sentry, driving, androidOff,
                                                    surround).action == ScreenOffPlan.Action.STOP_NOW);
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("安卓熄屏、哨兵模式关、没在开车", ScreenOffPlan.headUnitSleeps(true, false, 0, false));
        assertTrue("哨兵模式读不到算关", ScreenOffPlan.headUnitSleeps(true, false, null, false));
        assertFalse("安卓还亮着：车机醒着", ScreenOffPlan.headUnitSleeps(false, false, 0, false));
        assertFalse("唤醒锁拉着", ScreenOffPlan.headUnitSleeps(true, true, 0, false));
        assertFalse("哨兵模式开", ScreenOffPlan.headUnitSleeps(true, false, 1, false));
        assertFalse("哨兵模式布防", ScreenOffPlan.headUnitSleeps(true, false, 2, false));
        assertFalse("在开车", ScreenOffPlan.headUnitSleeps(true, false, 0, true));
    }

    /** 判成现在停：写下「车机要睡」。已经写着的不再写 —— 起算时刻不挪，调度「最多再等 1 秒」从第一次写下算。 */
    @Test
    public void stopNowWritesTheHeadUnitSleepsFactOnce() {
        assertEquals(ScreenOffPlan.SleepFact.WRITE,
                ScreenOffPlan.sleepFact(true, ScreenOffPlan.Action.STOP_NOW, false));
        assertEquals(ScreenOffPlan.SleepFact.KEEP,
                ScreenOffPlan.sleepFact(true, ScreenOffPlan.Action.STOP_NOW, true));
    }

    /**
     * 安卓亮着（车机醒着）：写着就清，没写着不动 —— 亮屏是这样，屏幕还黑着、车机自己醒了（夜里那一次）也是这样：
     * 事实说的是车机，不是屏幕（设计 v3 §3.1）。
     */
    @Test
    public void androidOnClearsTheHeadUnitSleepsFactEvenWhileDark() {
        assertEquals(ScreenOffPlan.SleepFact.CLEAR, ScreenOffPlan.sleepFact(false, null, true));
        assertEquals(ScreenOffPlan.SleepFact.KEEP, ScreenOffPlan.sleepFact(false, null, false));
        for (ScreenOffPlan.Action judged : ScreenOffPlan.Action.values()) {
            assertEquals(ScreenOffPlan.SleepFact.CLEAR, ScreenOffPlan.sleepFact(false, judged, true));
            assertEquals("车机醒着时判下来什么都不写", ScreenOffPlan.SleepFact.KEEP,
                    ScreenOffPlan.sleepFact(false, judged, false));
        }
    }

    /**
     * 安卓熄屏着时判成接着录、10 秒后停、通道放开了：「车机要睡」不动 —— 没写的不写；写了的也不清（相机已经在按次序关，
     * 停了的录像黑着不接），只有安卓亮了才清。
     */
    @Test
    public void whileTheHeadUnitIsOffOnlyAStopNowTouchesTheFact() {
        for (boolean written : BOTH) {
            assertEquals(ScreenOffPlan.SleepFact.KEEP,
                    ScreenOffPlan.sleepFact(true, ScreenOffPlan.Action.CONTINUE, written));
            assertEquals(ScreenOffPlan.SleepFact.KEEP,
                    ScreenOffPlan.sleepFact(true, ScreenOffPlan.Action.STOP_LATER, written));
            assertEquals(ScreenOffPlan.SleepFact.KEEP,
                    ScreenOffPlan.sleepFact(true, ScreenOffPlan.Action.STOP_RELEASED, written));
            assertEquals(ScreenOffPlan.SleepFact.KEEP, ScreenOffPlan.sleepFact(true, null, written));
        }
    }

    /**
     * 存储一类停了、等自动接回的那一段里熄屏（大纲 §7 第 8 问）：车辆信号一直在读，照在录时那样判 —— 车机醒着时熄屏持续录制
     * 开着就接着录（接回照常开）；车机要睡时在开车、哨兵模式开着也接着录；哨兵模式没开、停在 P 挡才现在停。
     */
    @Test
    public void aResumeWaitingAfterAStorageStopIsJudgedFromTheCar() {
        for (boolean autoRecord : BOTH) {
            assertEquals("车机醒着", ScreenOffPlan.Action.CONTINUE,
                    action(true, false, autoRecord, 0, ScreenOffPlan.driving("P", 0f), false));
            assertEquals("D 挡、哨兵模式读成 0", ScreenOffPlan.Action.CONTINUE,
                    action(true, false, autoRecord, 0, ScreenOffPlan.driving("D", 0f), true));
            assertEquals("P 挡、哨兵模式开着", ScreenOffPlan.Action.CONTINUE,
                    action(true, false, autoRecord, 1, ScreenOffPlan.driving("P", 0f), true));
            assertEquals("P 挡、哨兵模式关", ScreenOffPlan.Action.STOP_NOW,
                    action(true, false, autoRecord, 0, ScreenOffPlan.driving("P", 0f), true));
            assertEquals("车辆信号读不到", ScreenOffPlan.Action.STOP_NOW,
                    action(true, false, autoRecord, null, ScreenOffPlan.driving(null, null), true));
        }
    }
}
