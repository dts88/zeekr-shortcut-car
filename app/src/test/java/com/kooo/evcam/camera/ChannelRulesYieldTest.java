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

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link ChannelRules#sharedOverflow}、{@link ChannelRules#onConfigFailed}：会话装不下时谁先让（相机管家 3c，项目所有者
 * 2026-11-10：次序照旧 —— 拍照 → 盲区画面 → 录像中的超级镜子 → 录像，主界面预览不让）。时间和次数照批过的写死
 * （200、500 毫秒，同一组再试 3 次），改了规则这里跟着改。
 */
public class ChannelRulesYieldTest {

    private static final ChannelRules.Out PREVIEW = out(ChannelRules.Kind.PREVIEW);
    private static final ChannelRules.Out MIRROR = out(ChannelRules.Kind.MIRROR);
    private static final ChannelRules.Out SIDE = out(ChannelRules.Kind.SIDE);
    private static final ChannelRules.Out HUD = out(ChannelRules.Kind.HUD);
    private static final ChannelRules.Out SECOND = out(ChannelRules.Kind.SIDE_SECOND);
    private static final ChannelRules.Out RECORD = out(ChannelRules.Kind.RECORD);

    private static ChannelRules.Out out(ChannelRules.Kind kind) {
        return new ChannelRules.Out(kind, new Object());
    }

    private static List<String> names(List<ChannelRules.Out> outs) {
        List<String> names = new ArrayList<>();
        for (ChannelRules.Out out : outs) {
            names.add(out.kind.name());
        }
        return names;
    }

    /** 共享预览流挂满：只有盲区画面让，第二块先让，再是 HUD，再是第一块；预览、超级镜子不让。 */
    @Test
    public void aFullSharedStreamDropsSideViewsFromTheBack() {
        List<ChannelRules.Out> shared = Arrays.asList(PREVIEW, MIRROR, SIDE, HUD, SECOND);
        assertEquals(Collections.singletonList(SECOND), ChannelRules.sharedOverflow(shared, 4));
        assertEquals(Arrays.asList(SECOND, HUD), ChannelRules.sharedOverflow(shared, 3));
        assertEquals(Arrays.asList(SECOND, HUD, SIDE), ChannelRules.sharedOverflow(shared, 2));
        assertEquals("预览、超级镜子挂不下也不让", Arrays.asList(SECOND, HUD, SIDE),
                ChannelRules.sharedOverflow(shared, 1));
        assertEquals(Arrays.asList(SECOND, HUD, SIDE), ChannelRules.sharedOverflow(shared, 0));
    }

    /** 缺了几块也照同一个先后让；只有盲区画面时也一样。 */
    @Test
    public void aPartialSharedStreamYieldsInTheSameOrder() {
        assertEquals(Collections.singletonList(HUD),
                ChannelRules.sharedOverflow(Arrays.asList(PREVIEW, MIRROR, SIDE, HUD), 3));
        assertEquals(Arrays.asList(SECOND, HUD),
                ChannelRules.sharedOverflow(Arrays.asList(MIRROR, SIDE, HUD, SECOND), 2));
        assertEquals(Collections.singletonList(SECOND),
                ChannelRules.sharedOverflow(Arrays.asList(SIDE, HUD, SECOND), 2));
    }

    /** 排在最前的那块建这条流，总是带着：就算它是盲区画面、上限报得再小。 */
    @Test
    public void theBlockThatOpensTheSharedStreamStays() {
        assertEquals(Collections.singletonList(HUD), ChannelRules.sharedOverflow(Arrays.asList(SIDE, HUD), 0));
        assertEquals(Collections.singletonList(SIDE), ChannelRules.sharedOverflow(Arrays.asList(SECOND, SIDE), 1));
    }

    @Test
    public void aSharedStreamThatFitsDropsNothing() {
        assertTrue(ChannelRules.sharedOverflow(Arrays.asList(PREVIEW, SIDE, HUD, SECOND), 4).isEmpty());
        assertTrue(ChannelRules.sharedOverflow(Collections.singletonList(PREVIEW), 4).isEmpty());
        assertTrue(ChannelRules.sharedOverflow(Collections.<ChannelRules.Out>emptyList(), 4).isEmpty());
    }

    /** 配不上：先丢拍照，再把盲区画面三块一起丢（照中控屏、第二块、HUD 的先后报），都是 200 毫秒后再建。 */
    @Test
    public void aFailedSessionDropsThePhotoThenTheSideViews() {
        List<ChannelRules.Out> carried = Arrays.asList(PREVIEW, MIRROR, SIDE, HUD, SECOND, RECORD);
        ChannelRules.ConfigRetry first = ChannelRules.onConfigFailed(carried, true, 0);
        assertTrue(first.dropJpeg);
        assertTrue(first.drop.isEmpty());
        assertEquals(200L, first.delayMs);

        ChannelRules.ConfigRetry second = ChannelRules.onConfigFailed(carried, false, 0);
        assertFalse(second.dropJpeg);
        assertEquals(Arrays.asList(SIDE, SECOND, HUD), second.drop);
        assertEquals(200L, second.delayMs);
    }

    /** 不在录像也先丢盲区画面，超级镜子留着；丢了东西不算「同一组再试」。 */
    @Test
    public void sideViewsGoFirstWhenNotRecordingToo() {
        ChannelRules.ConfigRetry one = ChannelRules.onConfigFailed(Arrays.asList(PREVIEW, SIDE), false, 0);
        assertEquals(Collections.singletonList(SIDE), one.drop);
        assertEquals(200L, one.delayMs);
        assertEquals(0, one.sameRetries);

        ChannelRules.ConfigRetry two = ChannelRules.onConfigFailed(Arrays.asList(PREVIEW, MIRROR, SIDE, HUD), false, 2);
        assertEquals(Arrays.asList(SIDE, HUD), two.drop);
        assertEquals(2, two.sameRetries);
    }

    /** 录像中：先丢超级镜子，再丢录像，500 毫秒后再建；主界面预览不丢。 */
    @Test
    public void whileRecordingTheMirrorGoesBeforeTheRecording() {
        ChannelRules.ConfigRetry mirror = ChannelRules.onConfigFailed(Arrays.asList(PREVIEW, MIRROR, RECORD), false, 0);
        assertEquals(Collections.singletonList(MIRROR), mirror.drop);
        assertEquals(500L, mirror.delayMs);

        ChannelRules.ConfigRetry record = ChannelRules.onConfigFailed(Arrays.asList(PREVIEW, RECORD), false, 0);
        assertEquals(Collections.singletonList(RECORD), record.drop);
        assertEquals(500L, record.delayMs);
    }

    /** 不在录像：不丢超级镜子，同一组 200 毫秒后再试，试满 3 次再配不上就交给救援、计数归零（待办那一条，这次不改）。 */
    @Test
    public void notRecordingRetriesTheSameSetThreeTimesThenGivesUp() {
        List<ChannelRules.Out> carried = Arrays.asList(PREVIEW, MIRROR);
        int tries = 0;
        for (int retry = 1; retry <= 3; retry++) {
            ChannelRules.ConfigRetry next = ChannelRules.onConfigFailed(carried, false, tries);
            assertFalse(next.dropJpeg);
            assertTrue("不丢超级镜子", next.drop.isEmpty());
            assertEquals(200L, next.delayMs);
            assertEquals(retry, next.sameRetries);
            tries = next.sameRetries;
        }
        ChannelRules.ConfigRetry last = ChannelRules.onConfigFailed(carried, false, tries);
        assertTrue(last.drop.isEmpty());
        assertEquals("交给救援", 0L, last.delayMs);
        assertEquals(0, last.sameRetries);
    }

    /** 录像中一直配不上，从头走到底：拍照、盲区画面、超级镜子、录像，再同一组试 3 次，第 8 次交给救援。 */
    @Test
    public void aSessionThatNeverFitsWalksTheWholeOrder() {
        List<ChannelRules.Out> carried = new ArrayList<>(Arrays.asList(PREVIEW, MIRROR, SIDE, HUD, SECOND, RECORD));
        boolean jpeg = true;
        int tries = 0;
        List<String> steps = new ArrayList<>();
        for (int failure = 1; failure <= 8; failure++) {
            ChannelRules.ConfigRetry next = ChannelRules.onConfigFailed(carried, jpeg, tries);
            tries = next.sameRetries;
            if (next.dropJpeg) {
                jpeg = false;
                steps.add("JPEG " + next.delayMs);
            } else if (!next.drop.isEmpty()) {
                carried.removeAll(next.drop);
                steps.add(names(next.drop) + " " + next.delayMs);
            } else {
                steps.add((next.delayMs > 0 ? "same " : "give up ") + next.delayMs);
            }
        }
        assertEquals(Arrays.asList("JPEG 200", "[SIDE, SIDE_SECOND, HUD] 200", "[MIRROR] 500", "[RECORD] 500",
                "same 200", "same 200", "same 200", "give up 0"), steps);
        assertEquals(Collections.singletonList(PREVIEW), carried);
    }

    /** 两张次序表本身：谁先让（盲区画面第二块、HUD、第一块，再是超级镜子、录像；没有预览）；三块一起丢时报的先后。 */
    @Test
    public void theOrderTables() {
        assertEquals(Arrays.asList(ChannelRules.Kind.SIDE_SECOND, ChannelRules.Kind.HUD, ChannelRules.Kind.SIDE,
                ChannelRules.Kind.MIRROR, ChannelRules.Kind.RECORD), Arrays.asList(ChannelRules.YIELD_ORDER));
        assertEquals(Arrays.asList(ChannelRules.Kind.SIDE, ChannelRules.Kind.SIDE_SECOND, ChannelRules.Kind.HUD),
                Arrays.asList(ChannelRules.SIDE_DROP_ORDER));
    }
}
