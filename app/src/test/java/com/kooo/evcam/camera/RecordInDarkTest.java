/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.camera.ChannelRules.DarkOutlook;
import com.kooo.evcam.camera.ChannelRules.DarkStart;
import com.kooo.evcam.camera.ChannelRules.Event.Cause;
import com.kooo.evcam.camera.ChannelRules.RecordInDark;
import com.kooo.evcam.camera.ChannelRules.RecordStep;

import org.junit.Test;

/**
 * 熄屏时的录像只由相机管家判（项目所有者 2026-11-02「熄屏判断收归通道调度」）：录像停不停、什么时候停（{@link ChannelRules#recordInDark}）、
 * 黑着能不能开（{@link ChannelRules#startsInDark}）、决定变了录像那一侧做什么（{@link ChannelRules#onRecordVerdict}）、录制键下那行小字
 * （{@link ChannelRules#darkOutlook}）、车机要睡、在开车 —— 每一样只有一个定义。以前在录像协调器那张表（ScreenOffPlan）里，
 * 熄屏持续录制关着时屏幕黑 10 秒就停，车机要睡另有一个定义（还要哨兵模式关着）。
 */
public class RecordInDarkTest {

    private static final boolean[] BOTH = {false, true};
    private static final Integer[] ANY_SENTRY = {0, 1, 2, null};
    private static final Cause[] ANY_CAUSE = {Cause.DARK_ONSET, Cause.SENTRY_ON, Cause.SENTRY_OFF, Cause.ANDROID_OFF, null};

    /** 通道还没算黑（亮着、黑了不到 30 秒）：怎样都不停 —— 停录和相机放开是同一刻（以前熄屏持续录制关着时黑 10 秒就停）。 */
    @Test
    public void nothingStopsUntilTheChannelCountsTheScreenDark() {
        for (boolean sleeps : BOTH) {
            for (boolean keep : BOTH) {
                for (boolean surround : BOTH) {
                    for (boolean driving : BOTH) {
                        for (boolean kept : BOTH) {
                            assertEquals(RecordInDark.LIT,
                                    ChannelRules.recordInDark(false, sleeps, keep, surround, driving, kept));
                        }
                    }
                }
            }
        }
    }

    /** 车机要睡：不管设置、计划、环视留没留，都停。 */
    @Test
    public void theHeadUnitSleepingStopsWhateverElse() {
        for (boolean keep : BOTH) {
            for (boolean surround : BOTH) {
                for (boolean kept : BOTH) {
                    assertEquals(RecordInDark.SLEEPS, ChannelRules.recordInDark(true, true, keep, surround, false, kept));
                }
            }
        }
    }

    /** 熄屏持续录制关着：黑满 30 秒停，在开车也一样，哨兵模式开没开都一样（项目所有者 2026-11-02：以前黑 10 秒）。 */
    @Test
    public void keepRecordingOffStopsAtThirtySecondsEvenWhileDriving() {
        for (boolean surround : BOTH) {
            for (boolean driving : BOTH) {
                for (boolean kept : BOTH) {
                    assertEquals(RecordInDark.SETTING_OFF,
                            ChannelRules.recordInDark(true, false, false, surround, driving, kept));
                }
            }
        }
    }

    /** 计划里没有环视：黑满 30 秒座舱谁都不要，没有可录的 —— 停。 */
    @Test
    public void aPlanWithoutTheSurroundStopsAtThirtySeconds() {
        for (boolean driving : BOTH) {
            for (boolean kept : BOTH) {
                assertEquals(RecordInDark.NO_SURROUND, ChannelRules.recordInDark(true, false, true, false, driving, kept));
            }
        }
    }

    /** 熄屏持续录制开着、计划里有环视：在开车、或者环视留着（哨兵模式开或布防）接着录环视；停着车、环视放开了就停。 */
    @Test
    public void drivingOrAKeptSurroundKeepsRecordingTheSurround() {
        assertEquals(RecordInDark.KEEP, ChannelRules.recordInDark(true, false, true, true, true, false));
        assertEquals(RecordInDark.KEEP, ChannelRules.recordInDark(true, false, true, true, false, true));
        assertEquals(RecordInDark.KEEP, ChannelRules.recordInDark(true, false, true, true, true, true));
        assertEquals(RecordInDark.RELEASED, ChannelRules.recordInDark(true, false, true, true, false, false));
    }

    @Test
    public void everythingButLitAndKeepStops() {
        for (RecordInDark verdict : RecordInDark.values()) {
            assertEquals(verdict.name(), verdict != RecordInDark.LIT && verdict != RecordInDark.KEEP, verdict.stops());
        }
    }

    /** 停了之后亮屏接不接：熄屏持续录制、启动自动录制开着一个就接。 */
    @Test
    public void aStopResumesOnLitWhenKeepRecordingOrAutoRecordIsOn() {
        assertTrue(ChannelRules.resumesOnLit(true, false));
        assertTrue(ChannelRules.resumesOnLit(false, true));
        assertTrue(ChannelRules.resumesOnLit(true, true));
        assertFalse(ChannelRules.resumesOnLit(false, false));
    }

    /**
     * 车机要睡（唯一定义）：安卓熄屏了、没在开车。哨兵模式开着时安卓本来不熄屏，所以不另看它；在开车时安卓熄屏不算
     * （照以前的做法，车机只在 P 挡断电）。
     */
    @Test
    public void theHeadUnitSleepsWhenAndroidIsOffAndTheCarIsParked() {
        assertTrue(ChannelRules.headUnitSleeps(true, false));
        assertFalse("在开车", ChannelRules.headUnitSleeps(true, true));
        assertFalse("安卓还亮着：车机醒着", ChannelRules.headUnitSleeps(false, false));
        assertFalse(ChannelRules.headUnitSleeps(false, true));
    }

    /**
     * 在开车（唯一定义）：挡位读得到、不是 P 就算 —— D 挡等红灯也算；读不到挡位时看车速：读得到、大于 0；都读不到按没在开车算
     * （项目所有者 2026-10-11，大纲 §7 第 2 问）。
     */
    @Test
    public void drivingMeansAKnownGearOtherThanParkOrElseAMovingSpeed() {
        assertFalse(ChannelRules.driving("P", 0f));
        assertFalse("P 挡、车速读成别的也不算", ChannelRules.driving("P", 30f));
        assertTrue("D 挡等红灯", ChannelRules.driving("D", 0f));
        assertTrue(ChannelRules.driving("R", null));
        assertTrue("挡位读不到，在走", ChannelRules.driving(null, 12f));
        assertFalse(ChannelRules.driving(null, 0f));
        assertFalse("都读不到", ChannelRules.driving(null, null));
    }

    /**
     * 黑着能不能开（只录环视）：车机要睡、计划里没有环视、熄屏持续录制关着都不开；通道还没算黑、在开车 → 开（之后照停不停录到停）。
     * 是不是哨兵模式变成开那一下、能不能用相机，这时都不看。
     */
    @Test
    public void beforeTheChannelCountsTheScreenDarkTheStartRuleIsTheSettings() {
        for (boolean bySentryRise : BOTH) {
            for (boolean usable : BOTH) {
                for (boolean kept : BOTH) {
                    assertEquals(DarkStart.SLEEPS,
                            ChannelRules.startsInDark(false, true, true, true, false, kept, bySentryRise, usable));
                    assertEquals(DarkStart.NO_SURROUND,
                            ChannelRules.startsInDark(false, false, true, false, false, kept, bySentryRise, usable));
                    assertEquals(DarkStart.SETTING_OFF,
                            ChannelRules.startsInDark(false, false, false, true, false, kept, bySentryRise, usable));
                    assertEquals(DarkStart.STARTS,
                            ChannelRules.startsInDark(false, false, true, true, false, kept, bySentryRise, usable));
                    assertEquals("在开车：照亮着算", DarkStart.STARTS,
                            ChannelRules.startsInDark(true, false, true, true, true, kept, bySentryRise, usable));
                }
            }
        }
    }

    /**
     * 通道算黑、停着车以后（项目所有者 2026-10-27）：环视没留着不开；留着的只有熄屏中哨兵模式变成开那一下的接回、能用相机才开 ——
     * 别的时候来的要录不开（不为中间不明原因的启动去试着录）。
     */
    @Test
    public void afterThirtySecondsOnlyASentryRiseWithAUsableCameraStarts() {
        for (boolean bySentryRise : BOTH) {
            for (boolean usable : BOTH) {
                assertEquals(DarkStart.RELEASED,
                        ChannelRules.startsInDark(true, false, true, true, false, false, bySentryRise, usable));
            }
        }
        assertEquals(DarkStart.NO_SENTRY_RISE, ChannelRules.startsInDark(true, false, true, true, false, true, false, true));
        assertEquals(DarkStart.STARTS, ChannelRules.startsInDark(true, false, true, true, false, true, true, true));
        assertEquals(DarkStart.CAMERA_UNUSABLE,
                ChannelRules.startsInDark(true, false, true, true, false, true, true, false));
    }

    /**
     * 决定变了录像那一侧做什么：亮着什么都不做；在录（含等开录、等自动接回）判成停就停；没在录 —— 只有熄屏中哨兵模式变成开、
     * 录像能接着录环视时才开（R1：通道刚算黑那一下没在录的不开）。
     */
    @Test
    public void theRecordingSideStopsOnAStopAndStartsOnlyOnASentryRise() {
        for (RecordInDark verdict : RecordInDark.values()) {
            for (Cause cause : ANY_CAUSE) {
                for (boolean busy : BOTH) {
                    assertEquals("亮着", RecordStep.NOTHING, ChannelRules.onRecordVerdict(verdict, cause, false, busy));
                }
                assertEquals(verdict + " " + cause, verdict.stops() ? RecordStep.STOP : RecordStep.NOTHING,
                        ChannelRules.onRecordVerdict(verdict, cause, true, true));
                assertEquals(verdict + " " + cause,
                        verdict == RecordInDark.KEEP && cause == Cause.SENTRY_ON ? RecordStep.START : RecordStep.NOTHING,
                        ChannelRules.onRecordVerdict(verdict, cause, true, false));
            }
        }
    }

    /**
     * 录制键下那行小字和实际怎么录同一套规矩（项目所有者 2026-10-13：小字要说清车机休眠时还录不录）：熄屏持续录制开着、计划里有环视，
     * 在开车、或者哨兵模式开或布防 → 接着录；不然黑满 30 秒停（停着车、哨兵模式没开的，车机下电之前环视已经放开）→ 亮屏接的「暂停」，
     * 不接的「停止」。哨兵模式读不到（车辆接口没连着）按没开算。
     */
    @Test
    public void theRecordButtonTextFollowsTheSameRules() {
        for (Integer sentry : ANY_SENTRY) {
            boolean on = sentry != null && sentry != 0;
            for (boolean auto : BOTH) {
                assertEquals(on ? DarkOutlook.CONTINUE : DarkOutlook.PAUSE,
                        ChannelRules.darkOutlook(true, true, false, sentry, auto));
                assertEquals("在开车", DarkOutlook.CONTINUE, ChannelRules.darkOutlook(true, true, true, sentry, auto));
                assertEquals("计划里没有环视", DarkOutlook.PAUSE, ChannelRules.darkOutlook(true, false, false, sentry, auto));
                assertEquals("熄屏持续录制关着", auto ? DarkOutlook.PAUSE : DarkOutlook.STOP,
                        ChannelRules.darkOutlook(false, true, true, sentry, auto));
            }
        }
    }
}
