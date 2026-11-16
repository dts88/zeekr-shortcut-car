/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.kooo.evcam.alarm.guard.GuardHome;
import com.kooo.evcam.alarm.guard.GuardPolicy;

/**
 * 「忘了开哨兵」的判断，一条规则一句话（{@link GuardPolicy}）。
 *
 * <p>这里每一个方法名都是一条要求，读一遍就知道这一项同意做什么、拒绝做什么。</p>
 */
public class GuardPolicyTest {

    private static final long LEFT = 1_000_000L;

    private static GuardPolicy.Verdict decide(GuardPolicy.Mode mode, Integer sentry, boolean parked,
                                              boolean afterDrive, GuardHome.Where where) {
        return decide(mode, sentry, parked, afterDrive, where, false);
    }

    private static GuardPolicy.Verdict decide(GuardPolicy.Mode mode, Integer sentry, boolean parked,
                                              boolean afterDrive, GuardHome.Where where,
                                              boolean anywhere) {
        return GuardPolicy.decide(mode, sentry, parked, afterDrive, where, anywhere,
                LEFT, 0L, LEFT + 10_000L);
    }

    @Test
    public void offMeansNothingHappensEvenWhenEverythingMatches() {
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.OFF, 0, true, true, GuardHome.Where.AWAY));
    }

    @Test
    public void askAsksAndSayNothingAboutTheCarUntilAsked() {
        assertEquals(GuardPolicy.Verdict.ASK,
                decide(GuardPolicy.Mode.ASK, 0, true, true, GuardHome.Where.AWAY));
        assertEquals(GuardPolicy.Verdict.ASK,
                decide(GuardPolicy.Mode.ASK_THEN_ON, 0, true, true, GuardHome.Where.AWAY));
    }

    @Test
    public void onActsWithoutAsking() {
        assertEquals(GuardPolicy.Verdict.ACT,
                decide(GuardPolicy.Mode.ON, 0, true, true, GuardHome.Where.AWAY));
    }

    @Test
    public void sentryAlreadyOnIsLeftAlone() {
        // 1 = 开着还没布防，2 = 已布防：两种都在守，没有要补的
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.ON, 1, true, true, GuardHome.Where.AWAY));
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.ON, 2, true, true, GuardHome.Where.AWAY));
    }

    @Test
    public void anUnreadableSentryValueIsNeverWrittenOver() {
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.ON, null, true, true, GuardHome.Where.AWAY));
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.ON, 255, true, true, GuardHome.Where.AWAY));
    }

    @Test
    public void aDrivingCarIsLeftAlone() {
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.ON, 0, false, true, GuardHome.Where.AWAY));
    }

    @Test
    public void aCarThatHasNotDrivenSinceItWasLastParkedIsLeftAlone() {
        // 停在车库里三天、哨兵一直没开，也要憋着
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.ON, 0, true, false, GuardHome.Where.AWAY));
    }

    @Test
    public void homeIsNeverTouchedWhateverTheMode() {
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.ON, 0, true, true, GuardHome.Where.HOME));
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.ASK, 0, true, true, GuardHome.Where.HOME));
    }

    @Test
    public void unknownDistanceStillAsksBecauseAReminderIsCheap() {
        assertEquals(GuardPolicy.Verdict.ASK,
                decide(GuardPolicy.Mode.ASK_THEN_ON, 0, true, true, GuardHome.Where.UNKNOWN));
    }

    @Test
    public void oneAskPerPark() {
        long askedAt = LEFT + GuardPolicy.ASK_AT_MS;
        assertEquals(GuardPolicy.Verdict.IGNORE,
                GuardPolicy.decide(GuardPolicy.Mode.ASK, 0, true, true, GuardHome.Where.AWAY, false,
                        LEFT, askedAt, askedAt + 15_000L));
    }

    @Test
    public void aPreviousParksAskDoesNotCount() {
        long previousAsk = LEFT - 4 * 3_600_000L;
        assertEquals(GuardPolicy.Verdict.ASK,
                GuardPolicy.decide(GuardPolicy.Mode.ASK, 0, true, true, GuardHome.Where.AWAY, false,
                        LEFT, previousAsk, LEFT + 10_000L));
    }

    @Test
    public void anUnknownLeavingMomentIsNeverActedOn() {
        assertEquals(GuardPolicy.Verdict.IGNORE,
                GuardPolicy.decide(GuardPolicy.Mode.ON, 0, true, true, GuardHome.Where.AWAY, false,
                        0L, 0L, LEFT));
    }

    @Test
    public void theWindowIsShorterThanTheMeasuredFourToSixMinutes() {
        // 实测四到六分钟下电；这里按三分钟收手
        assertEquals(180_000L, GuardPolicy.ACT_WINDOW_MS);
        assertTrue(GuardPolicy.stillActionable(LEFT, LEFT));
        assertTrue(GuardPolicy.stillActionable(LEFT, LEFT + 179_000L));
        assertFalse(GuardPolicy.stillActionable(LEFT, LEFT + 181_000L));
        assertFalse(GuardPolicy.stillActionable(LEFT, LEFT + 6 * 60_000L));
    }

    @Test
    public void aClockThatWentBackwardsIsNotActionable() {
        assertFalse(GuardPolicy.stillActionable(LEFT, LEFT - 1));
        assertFalse(GuardPolicy.stillActionable(0L, LEFT));
    }

    @Test
    public void tooLateMeansIgnoreRatherThanActOnAnOldPark() {
        assertEquals(GuardPolicy.Verdict.IGNORE,
                GuardPolicy.decide(GuardPolicy.Mode.ON, 0, true, true, GuardHome.Where.AWAY, false,
                        LEFT, 0L, LEFT + GuardPolicy.ACT_WINDOW_MS + 1));
    }

    @Test
    public void theAutoOnDelayIsSixtySeconds() {
        assertEquals(60_000L, GuardPolicy.AUTO_ON_MS);
        assertFalse(GuardPolicy.autoOnDue(LEFT, LEFT + 59_999L));
        assertTrue(GuardPolicy.autoOnDue(LEFT, LEFT + 60_000L));
        assertFalse(GuardPolicy.autoOnDue(0L, LEFT + 60_000L));
    }

    @Test
    public void actingUnaskedNeedsBeingAwayOrNeverHavingSetAHome() {
        assertTrue(GuardPolicy.maySwitchOnItself(GuardHome.Where.AWAY, true, false));
        assertTrue(GuardPolicy.maySwitchOnItself(GuardHome.Where.UNKNOWN, false, false));
        assertFalse(GuardPolicy.maySwitchOnItself(GuardHome.Where.UNKNOWN, true, false));
        assertFalse(GuardPolicy.maySwitchOnItself(GuardHome.Where.HOME, false, false));
    }

    @Test
    public void homeIsTouchedOnlyWhenTheOwnerAskedForEverywhere() {
        assertEquals(GuardPolicy.Verdict.ASK,
                decide(GuardPolicy.Mode.ASK_THEN_ON, 0, true, true, GuardHome.Where.HOME, true));
        assertEquals(GuardPolicy.Verdict.ACT,
                decide(GuardPolicy.Mode.ON, 0, true, true, GuardHome.Where.HOME, true));
    }

    @Test
    public void everywhereDoesNotOverrideTheOtherRefusals() {
        // 在家也开不等于什么都不看：开着、没停、没开过车、读不到，一样不动
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.ON, 1, true, true, GuardHome.Where.HOME, true));
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.ON, 0, false, true, GuardHome.Where.HOME, true));
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.ON, 0, true, false, GuardHome.Where.HOME, true));
        assertEquals(GuardPolicy.Verdict.IGNORE,
                decide(GuardPolicy.Mode.ON, null, true, true, GuardHome.Where.HOME, true));
    }

    @Test
    public void everywhereLetsTheCarActUnaskedWhereverItIs() {
        assertTrue(GuardPolicy.maySwitchOnItself(GuardHome.Where.HOME, true, true));
        assertTrue(GuardPolicy.maySwitchOnItself(GuardHome.Where.UNKNOWN, true, true));
        assertTrue(GuardPolicy.maySwitchOnItself(GuardHome.Where.AWAY, true, true));
        assertFalse(GuardPolicy.maySwitchOnItself(GuardHome.Where.HOME, true, false));
    }

    @Test
    public void storedValuesAreZeroToThreeAndAnythingElseIsOff() {
        assertEquals(GuardPolicy.Mode.OFF, GuardPolicy.Mode.of(0));
        assertEquals(GuardPolicy.Mode.ASK, GuardPolicy.Mode.of(1));
        assertEquals(GuardPolicy.Mode.ASK_THEN_ON, GuardPolicy.Mode.of(2));
        assertEquals(GuardPolicy.Mode.ON, GuardPolicy.Mode.of(3));
        assertEquals(GuardPolicy.Mode.OFF, GuardPolicy.Mode.of(7));
        assertEquals(GuardPolicy.Mode.OFF, GuardPolicy.Mode.of(-1));
        for (GuardPolicy.Mode mode : GuardPolicy.Mode.values()) {
            assertEquals(mode, GuardPolicy.Mode.of(mode.stored()));
        }
    }

    @Test
    public void theDefaultIsOffSoNothingChangesOnACarThatNeverTouchesIt() {
        assertEquals(GuardPolicy.Mode.OFF, GuardPolicy.DEFAULT_MODE);
    }
}
