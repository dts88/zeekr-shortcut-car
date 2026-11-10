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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link ChannelRules#sharedOverflow}、{@link ChannelRules#onConfigFailed}：会话装不下时谁先让（相机管家 3c，项目所有者
 * 2026-11-10：次序照旧 —— 拍照 → 盲区画面 → 录像中的超级镜子 → 录像，主界面预览不让）。
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

    /** 共享预览流挂满：只有盲区画面让，第二块先让，再是 HUD，再是第一块；预览、超级镜子不让。 */
    @Test
    public void aFullSharedStreamDropsSideViewsFromTheBack() {
        List<ChannelRules.Out> shared = Arrays.asList(PREVIEW, MIRROR, SIDE, HUD, SECOND);
        assertEquals(Collections.singletonList(SECOND), ChannelRules.sharedOverflow(shared, 4));
        assertEquals(Arrays.asList(SECOND, HUD), ChannelRules.sharedOverflow(shared, 3));
        assertEquals(Arrays.asList(SECOND, HUD, SIDE), ChannelRules.sharedOverflow(shared, 2));
        assertEquals("预览、超级镜子挂不下也不让", Arrays.asList(SECOND, HUD, SIDE),
                ChannelRules.sharedOverflow(shared, 1));
    }

    @Test
    public void aSharedStreamThatFitsDropsNothing() {
        assertTrue(ChannelRules.sharedOverflow(Arrays.asList(PREVIEW, SIDE, HUD, SECOND), 4).isEmpty());
        assertTrue(ChannelRules.sharedOverflow(Collections.singletonList(PREVIEW), 4).isEmpty());
    }

    /** 配不上：先丢拍照，再把盲区画面三块一起丢。 */
    @Test
    public void aFailedSessionDropsThePhotoThenTheSideViews() {
        List<ChannelRules.Out> carried = Arrays.asList(PREVIEW, MIRROR, SIDE, HUD, SECOND, RECORD);
        ChannelRules.ConfigRetry first = ChannelRules.onConfigFailed(carried, true, 0);
        assertTrue(first.dropJpeg);
        assertTrue(first.drop.isEmpty());
        assertEquals(ChannelRules.CONFIG_RETRY_MS, first.delayMs);

        ChannelRules.ConfigRetry second = ChannelRules.onConfigFailed(carried, false, 0);
        assertFalse(second.dropJpeg);
        assertEquals(Arrays.asList(SECOND, HUD, SIDE), second.drop);
        assertEquals(ChannelRules.CONFIG_RETRY_MS, second.delayMs);
    }

    /** 录像中：先丢超级镜子，再丢录像；主界面预览不丢。 */
    @Test
    public void whileRecordingTheMirrorGoesBeforeTheRecording() {
        ChannelRules.ConfigRetry mirror = ChannelRules.onConfigFailed(Arrays.asList(PREVIEW, MIRROR, RECORD), false, 0);
        assertEquals(Collections.singletonList(MIRROR), mirror.drop);
        assertEquals(ChannelRules.CONFIG_RETRY_RECORDING_MS, mirror.delayMs);

        ChannelRules.ConfigRetry record = ChannelRules.onConfigFailed(Arrays.asList(PREVIEW, RECORD), false, 0);
        assertEquals(Collections.singletonList(RECORD), record.drop);
        assertEquals(ChannelRules.CONFIG_RETRY_RECORDING_MS, record.delayMs);
    }

    /** 不在录像：不丢超级镜子，同一组再试 3 次，还不行交给救援（待办里的那一条，这次不改）。 */
    @Test
    public void notRecordingRetriesTheSameSetThenGivesUp() {
        List<ChannelRules.Out> carried = Arrays.asList(PREVIEW, MIRROR);
        for (int tries = 0; tries < ChannelRules.CONFIG_FAIL_RETRIES; tries++) {
            ChannelRules.ConfigRetry next = ChannelRules.onConfigFailed(carried, false, tries);
            assertFalse(next.dropJpeg);
            assertTrue("不丢超级镜子", next.drop.isEmpty());
            assertEquals(ChannelRules.CONFIG_RETRY_MS, next.delayMs);
        }
        ChannelRules.ConfigRetry last = ChannelRules.onConfigFailed(carried, false, ChannelRules.CONFIG_FAIL_RETRIES);
        assertTrue(last.drop.isEmpty());
        assertEquals("交给救援", 0L, last.delayMs);
    }

    /** 次序表本身：盲区画面在前（第二块、HUD、第一块），再是超级镜子、录像；没有预览。 */
    @Test
    public void theYieldOrderHasNoPreview() {
        assertEquals(Arrays.asList(ChannelRules.Kind.SIDE_SECOND, ChannelRules.Kind.HUD, ChannelRules.Kind.SIDE,
                ChannelRules.Kind.MIRROR, ChannelRules.Kind.RECORD), Arrays.asList(ChannelRules.YIELD_ORDER));
    }
}
