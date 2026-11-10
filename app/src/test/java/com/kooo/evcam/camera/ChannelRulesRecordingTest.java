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

import com.kooo.evcam.camera.ChannelRules.PipelineReport;
import com.kooo.evcam.camera.ChannelRules.RecordPhase;
import com.kooo.evcam.camera.ChannelRules.RecordStop;
import com.kooo.evcam.camera.ChannelRules.ReportStep;

import org.junit.Test;

/**
 * 相机管家的录像规矩（{@link ChannelRules} 录像那一节，2026-11-03 录像协调器抹掉时从它的帮手类搬过来）：
 * 「在不在录」只有一份、过时的报告不算数、为什么停、停了接不接、等 U 盘多久查一次、启动自动录制什么时候看。
 */
public class ChannelRulesRecordingTest {

    // ------------------------------------------------------------------ 阶段

    /** 开录指令一发出去就算在录（开录中）；停录中、空闲不算。 */
    @Test
    public void preparingAndRecordingCountAsRecording() {
        assertTrue(RecordPhase.PREPARING.recording());
        assertTrue(RecordPhase.RECORDING.recording());
        assertFalse(RecordPhase.STOPPING.recording());
        assertFalse(RecordPhase.IDLE.recording());
    }

    /** 第一路加入是记一笔（开录中 → 在录）；在录时重建后又起来了也只是记一笔。 */
    @Test
    public void aLaneJoiningIsJustNoted() {
        assertEquals(ReportStep.NOTE, ChannelRules.onPipelineReport(RecordPhase.PREPARING, PipelineReport.STARTED));
        assertEquals(ReportStep.NOTE, ChannelRules.onPipelineReport(RecordPhase.RECORDING, PipelineReport.STARTED));
    }

    /**
     * 每一路的录制器都建不起来：这一次录像到头了，走停录那条路 —— 以前是「没在录，不用停」，什么都没收拾、也不接回，
     * 通知和悬浮按钮一直是「录制中」（2026-10-05 批准的问题）。开录中建不起来按开录失败停（不说「中断」），照样接回。
     */
    @Test
    public void aStartThatFailsEverywhereEndsTheAttemptAsStartFailed() {
        assertEquals(ReportStep.END, ChannelRules.onPipelineReport(RecordPhase.PREPARING, PipelineReport.START_FAILED));
        RecordStop why = ChannelRules.endReason(RecordPhase.PREPARING, PipelineReport.START_FAILED);
        assertEquals(RecordStop.START_FAILED, why);
        assertTrue(ChannelRules.resumesAfter(why));
    }

    /** 录了一阵之后才每一路都建不起来：录像开始过，按原因不明停。 */
    @Test
    public void aRebuildThatFailsLaterStopsAsUnknown() {
        assertEquals(ReportStep.END, ChannelRules.onPipelineReport(RecordPhase.RECORDING, PipelineReport.START_FAILED));
        assertEquals(RecordStop.UNKNOWN, ChannelRules.endReason(RecordPhase.RECORDING, PipelineReport.START_FAILED));
    }

    /** 没人叫它停、相机层自己停了（管线被释放、换了一份）：这一次录像到头，原因不明，照样接回。 */
    @Test
    public void aPipelineThatStopsByItselfEndsTheAttempt() {
        assertEquals(ReportStep.END, ChannelRules.onPipelineReport(RecordPhase.PREPARING, PipelineReport.STOPPED));
        assertEquals(ReportStep.END, ChannelRules.onPipelineReport(RecordPhase.RECORDING, PipelineReport.STOPPED));
        RecordStop why = ChannelRules.endReason(RecordPhase.RECORDING, PipelineReport.STOPPED);
        assertEquals(RecordStop.UNKNOWN, why);
        assertTrue(ChannelRules.resumesAfter(why));
    }

    /** 停录中等的收拾报上来了：收拾完了，等着开录的可以开了。 */
    @Test
    public void theCleanupReportSettlesTheStop() {
        assertEquals(ReportStep.SETTLED, ChannelRules.onPipelineReport(RecordPhase.STOPPING, PipelineReport.STOPPED));
    }

    /** 停过之后，上一次排着的开录任务报上来的一律不算；空闲时重复的收拾完了也不算。 */
    @Test
    public void reportsFromAnEndedAttemptAreIgnored() {
        assertEquals(ReportStep.IGNORE, ChannelRules.onPipelineReport(RecordPhase.STOPPING, PipelineReport.STARTED));
        assertEquals(ReportStep.IGNORE, ChannelRules.onPipelineReport(RecordPhase.STOPPING, PipelineReport.START_FAILED));
        assertEquals(ReportStep.IGNORE, ChannelRules.onPipelineReport(RecordPhase.IDLE, PipelineReport.STARTED));
        assertEquals(ReportStep.IGNORE, ChannelRules.onPipelineReport(RecordPhase.IDLE, PipelineReport.START_FAILED));
        assertEquals("重复的收拾完了", ReportStep.IGNORE, ChannelRules.onPipelineReport(RecordPhase.IDLE, PipelineReport.STOPPED));
    }

    /**
     * 相机层停录：几路一起叫停、一起等写入线程收好，共用 {@link CodecVideoRecorder#STOP_BUDGET_MS}；编码线程拖到期限的，
     * 之后再多给写入线程 {@link CodecVideoRecorder#STOP_GRACE_MS}。两样加起来只用相机管家收拾期限的一半 —— 另一半留给停放录制器。
     * 以前一路一路等，三路加起来超过了期限，不等了又开下一次，上一次还在写同一个盘。
     */
    @Test
    public void theCameraStopBudgetLeavesHalfOfTheDeadline() {
        assertTrue(CodecVideoRecorder.STOP_BUDGET_MS > 0);
        assertTrue(CodecVideoRecorder.STOP_GRACE_MS > 0);
        assertTrue((CodecVideoRecorder.STOP_BUDGET_MS + CodecVideoRecorder.STOP_GRACE_MS) * 2
                <= ChannelRules.STOP_DEADLINE_MS);
    }

    // ------------------------------------------------------------------ 停了接不接

    /**
     * 每一个停的原因都要表态：自动接回的只有存储一类（写不进、一笔没写进、管线被换、录制器都建不起来）。
     * 人停的、熄屏停的（亮屏时照「这一趟要录」接）、盘满的不接。新加一个原因而没想过接不接，这里就会失败。
     */
    @Test
    public void onlyTheStorageSideStopsResume() {
        java.util.Set<RecordStop> resuming = java.util.EnumSet.of(RecordStop.NO_DATA, RecordStop.WRITE_STALLED,
                RecordStop.START_FAILED, RecordStop.UNKNOWN);
        for (RecordStop why : RecordStop.values()) {
            assertEquals(why.name(), resuming.contains(why), ChannelRules.resumesAfter(why));
        }
    }

    /** 额度：连着三次都没录满一分钟就不再试。 */
    @Test
    public void theResumeBudgetRunsOutAfterThreeTries() {
        for (int tried = 0; tried < ChannelRules.RESUME_TRIES; tried++) {
            assertTrue("第 " + (tried + 1) + " 次还该让试", ChannelRules.resumeAllowed(tried));
        }
        assertFalse("三次都没成，不再试", ChannelRules.resumeAllowed(ChannelRules.RESUME_TRIES));
        assertEquals(60_000L, ChannelRules.RESUME_COUNTS_MS);
    }

    // ------------------------------------------------------------------ 等 U 盘

    /**
     * 开录等 U 盘（项目所有者 2026-10-13「U 盘一可用就开录」）：这台车机收不到 U 盘插拔广播，盘不能用拒了以后，从第一次拒起
     * 头 2 分钟每 2 秒查一次，之后照旧 30 秒；查得勤的那一段里同一个原因不再告诉界面。
     */
    @Test
    public void aStartWaitingForTheDiskChecksEveryTwoSecondsForTwoMinutes() {
        assertEquals("没在等盘：照旧", ChannelRules.START_RETRY_MS, ChannelRules.nextStartCheckMs(0, 1_000L));
        assertFalse(ChannelRules.inFastDiskWait(0, 1_000L));

        long since = 8_002_505L;
        assertEquals(ChannelRules.DISK_FAST_EVERY_MS, ChannelRules.nextStartCheckMs(since, since));
        assertTrue("2 秒后又拒：不再说", ChannelRules.inFastDiskWait(since, since + 2_000L));
        assertEquals(ChannelRules.DISK_FAST_EVERY_MS,
                ChannelRules.nextStartCheckMs(since, since + ChannelRules.DISK_FAST_FOR_MS - 1));
        assertFalse("2 分钟过了：照旧每次都说", ChannelRules.inFastDiskWait(since, since + ChannelRules.DISK_FAST_FOR_MS));
        assertEquals("之后照旧 30 秒", ChannelRules.START_RETRY_MS,
                ChannelRules.nextStartCheckMs(since, since + ChannelRules.DISK_FAST_FOR_MS));
    }

    // ------------------------------------------------------------------ 启动自动录制

    /** 应用启动、屏幕亮着、这一趟还没看过：看一次。 */
    @Test
    public void autoStartIsLookedAtOnceWhenTheAppStartsWithTheScreenLit() {
        assertTrue(ChannelRules.autoStartMoment(false, true, false));
        assertFalse("这一趟看过、人开过或停过：不再看", ChannelRules.autoStartMoment(true, true, false));
    }

    /** 亮屏本身不是启动：只接「这一趟要录」。 */
    @Test
    public void theScreenComingOnIsNotAStart() {
        assertFalse(ChannelRules.autoStartMoment(false, false, false));
    }

    // ------------------------------------------------------------------ 前台服务

    /** 前台服务（规格 §3.5）：开机自启动开着、在录、或者「这一趟要录」（等接回、等亮屏接）时开着；都不是、或者退出了就停。 */
    @Test
    public void theForegroundServiceStaysForBootRecordingOrAWantedRecording() {
        assertTrue("开机自启动开着", ChannelRules.foregroundServiceNeeded(false, true, false, false));
        assertTrue("在录", ChannelRules.foregroundServiceNeeded(false, false, true, false));
        assertTrue("这一趟要录：接回时相机从后台开", ChannelRules.foregroundServiceNeeded(false, false, false, true));
        assertFalse("都不是", ChannelRules.foregroundServiceNeeded(false, false, false, false));
        assertFalse("退出了", ChannelRules.foregroundServiceNeeded(true, true, true, true));
    }

    /** 屏幕黑着不看，等亮屏（不为中间不明原因的启动去试着录，项目所有者 2026-10-27）。 */
    @Test
    public void aStartInTheDarkWaitsForTheScreen() {
        assertFalse(ChannelRules.autoStartMoment(false, true, true));
    }

    // ------------------------------------------------------------------ 熄屏后的帧率（项目所有者 2026-11-04）

    /** 只在环视留着只录像时（通道的熄屏决定是 KEPT）按选的帧率；亮着、在开车、放开了，一律照视频流配置。 */
    @Test
    public void darkFpsOnlyWhileTheSurroundIsKeptInTheDark() {
        assertEquals(3, ChannelRules.darkFps(ChannelRules.SurroundInDark.KEPT, 3));
        assertEquals(10, ChannelRules.darkFps(ChannelRules.SurroundInDark.KEPT, 10));
        assertEquals("跟随视频流配置", 0, ChannelRules.darkFps(ChannelRules.SurroundInDark.KEPT, 0));
        assertEquals("亮着、黑了不到 30 秒、在开车", 0, ChannelRules.darkFps(ChannelRules.SurroundInDark.LIT, 3));
        assertEquals("放开了（录像也停）", 0, ChannelRules.darkFps(ChannelRules.SurroundInDark.RELEASED, 3));
    }

    /** 降下来从下一段起（换段本来就换编码器），升回去马上切一段（人回到车上了）；0 = 视频流配置，算最高。 */
    @Test
    public void darkFpsGoesDownAtTheNextSegmentAndBackUpAtOnce() {
        assertEquals(ChannelRules.FpsChange.NONE, ChannelRules.darkFpsChange(0, 0));
        assertEquals(ChannelRules.FpsChange.NONE, ChannelRules.darkFpsChange(3, 3));
        assertEquals(ChannelRules.FpsChange.NEXT_SEGMENT, ChannelRules.darkFpsChange(0, 3));
        assertEquals(ChannelRules.FpsChange.NEXT_SEGMENT, ChannelRules.darkFpsChange(0, 10));
        assertEquals(ChannelRules.FpsChange.NEXT_SEGMENT, ChannelRules.darkFpsChange(10, 3));
        assertEquals(ChannelRules.FpsChange.CUT_NOW, ChannelRules.darkFpsChange(3, 0));
        assertEquals(ChannelRules.FpsChange.CUT_NOW, ChannelRules.darkFpsChange(10, 0));
        assertEquals(ChannelRules.FpsChange.CUT_NOW, ChannelRules.darkFpsChange(3, 10));
    }
}
