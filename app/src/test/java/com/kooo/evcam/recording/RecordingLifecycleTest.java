package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.camera.CodecVideoRecorder;
import com.kooo.evcam.recording.RecordingLifecycle.Action;
import com.kooo.evcam.recording.RecordingLifecycle.Phase;
import com.kooo.evcam.recording.RecordingLifecycle.Report;

import org.junit.Test;

/**
 * 一次录像的生命周期：「在不在录」只有一份，停只有一条路，过时的报告不算数。
 *
 * <p>前几个测试对着 2026-10-05 批准的那个问题：每一路都没起来时，录像没收拾、没接回，
 * 通知和悬浮按钮一直是「录制中」。</p>
 */
public class RecordingLifecycleTest {

    private static final long T0 = 1_000_000L;

    /** 开录指令一发出去就算在录 —— 熄屏录制拿唤醒锁、心跳、恢复问到的都是这一个答案。 */
    @Test
    public void preparingCountsAsRecording() {
        RecordingLifecycle life = new RecordingLifecycle();
        assertFalse(life.isRecording());
        assertTrue(life.begin());
        assertEquals(Phase.PREPARING, life.phase());
        assertTrue("开录中就算在录", life.isRecording());
        assertEquals(Action.NOTE, life.on(Report.STARTED));
        assertEquals(Phase.RECORDING, life.phase());
        assertTrue(life.isRecording());
    }

    /** 每一路都没起来：这一次录像到头了，要走停录那条路 —— 以前是「没在录，不用停」，什么都没收拾。 */
    @Test
    public void aStartThatFailsEverywhereEndsTheAttempt() {
        RecordingLifecycle life = new RecordingLifecycle();
        life.begin();
        assertEquals(Action.END, life.on(Report.START_FAILED));
        assertTrue("停之前还算在录：停录那条路要看到它", life.isRecording());
        assertTrue(life.stop(T0));
        assertEquals(Phase.STOPPING, life.phase());
        assertFalse("停录中不算在录", life.isRecording());
        assertEquals(Action.SETTLED, life.on(Report.STOPPED));
        assertTrue(life.isIdle());
    }

    /**
     * 一路都没起来按「开录失败」停（提示「录制未能启动」，不说「中断：原因未知」），
     * 照样等环视接回、走同一份额度。
     */
    @Test
    public void aFailedStartStopsAsStartFailedAndIsResumed() {
        RecordingLifecycle life = new RecordingLifecycle();
        life.begin();
        assertEquals(Action.END, life.on(Report.START_FAILED));
        RecordingStops.Reason reason = life.endReason(Report.START_FAILED);
        assertEquals(RecordingStops.Reason.START_FAILED, reason);
        assertTrue(RecordingStops.resumesOnSurround(reason));
    }

    /** 管线自己停了（被释放、换了一份）：原因不明，照样接回。 */
    @Test
    public void aPipelineThatWentAwayStopsAsUnknownAndIsResumed() {
        RecordingLifecycle life = new RecordingLifecycle();
        life.begin();
        life.on(Report.STARTED);
        assertEquals(Action.END, life.on(Report.STOPPED));
        RecordingStops.Reason reason = life.endReason(Report.STOPPED);
        assertEquals(RecordingStops.Reason.UNKNOWN, reason);
        assertTrue(RecordingStops.resumesOnSurround(reason));
    }

    /**
     * 在录时 MediaRecorder 重建后没起来，同样到头 —— 但录像是开始过的，
     * 不能说「录制未能启动」，按原因不明停。
     */
    @Test
    public void aRebuildThatFailsEndsTheAttemptToo() {
        RecordingLifecycle life = new RecordingLifecycle();
        life.begin();
        life.on(Report.STARTED);
        assertEquals(Action.END, life.on(Report.START_FAILED));
        assertEquals(RecordingStops.Reason.UNKNOWN, life.endReason(Report.START_FAILED));
    }

    @Test
    public void aRebuildThatComesBackIsJustNoted() {
        RecordingLifecycle life = new RecordingLifecycle();
        life.begin();
        life.on(Report.STARTED);
        assertEquals(Action.NOTE, life.on(Report.STARTED));
        assertEquals(Phase.RECORDING, life.phase());
    }

    /** 开录中被停：停只有一条路，和在录时停一样。 */
    @Test
    public void stoppingWhilePreparingTakesTheSamePath() {
        RecordingLifecycle life = new RecordingLifecycle();
        life.begin();
        assertTrue(life.stop(T0));
        assertEquals(Phase.STOPPING, life.phase());
    }

    @Test
    public void stoppingWhenNotRecordingDoesNothing() {
        RecordingLifecycle life = new RecordingLifecycle();
        assertFalse(life.stop(T0));
        life.begin();
        life.stop(T0);
        assertFalse("停录中再停：已经在停了", life.stop(T0));
    }

    /**
     * 开 → 停 → 开两秒内：上一次还没收拾完就不开，收拾完了才开。
     * 以前上一次的收拾会把这一次的编码器一起放掉。
     */
    @Test
    public void aNewStartWaitsForTheLastCleanup() {
        RecordingLifecycle life = new RecordingLifecycle();
        assertTrue(life.begin());
        life.stop(T0);
        assertFalse("还在收拾", life.begin());
        assertEquals(Action.SETTLED, life.on(Report.STOPPED));
        assertTrue(life.begin());
    }

    @Test
    public void onlyOneStartAtATime() {
        RecordingLifecycle life = new RecordingLifecycle();
        assertTrue(life.begin());
        assertFalse(life.begin());
        life.on(Report.STARTED);
        assertFalse(life.begin());
    }

    /** 停过之后，上一次排着的开录任务报上来的东西一律不算。 */
    @Test
    public void reportsFromAnEndedAttemptAreIgnored() {
        RecordingLifecycle life = new RecordingLifecycle();
        life.begin();
        life.stop(T0);
        assertEquals(Action.IGNORE, life.on(Report.STARTED));
        assertEquals(Action.IGNORE, life.on(Report.START_FAILED));
        assertEquals(Phase.STOPPING, life.phase());
        life.on(Report.STOPPED);
        assertEquals(Action.IGNORE, life.on(Report.STARTED));
        assertEquals(Action.IGNORE, life.on(Report.START_FAILED));
        assertEquals("重复的收拾完了", Action.IGNORE, life.on(Report.STOPPED));
        assertTrue(life.isIdle());
    }

    /** 没人叫它停、管线自己停了（被释放、换了一份）：这一次录像到头，要收拾、要接回。 */
    @Test
    public void aPipelineThatStopsByItselfEndsTheAttempt() {
        RecordingLifecycle life = new RecordingLifecycle();
        life.begin();
        assertEquals(Action.END, life.on(Report.STOPPED));
        life.on(Report.STARTED);
        assertEquals(Action.END, life.on(Report.STOPPED));
    }

    /** 收拾一直不报完：到点不等了，免得再也开不起来。 */
    @Test
    public void aCleanupThatNeverReportsIsGivenUpOnAtTheDeadline() {
        RecordingLifecycle life = new RecordingLifecycle();
        life.begin();
        life.stop(T0);
        assertFalse(life.stopOverdue(T0 + RecordingLifecycle.STOP_DEADLINE_MS - 1));
        assertEquals(Phase.STOPPING, life.phase());
        assertTrue(life.stopOverdue(T0 + RecordingLifecycle.STOP_DEADLINE_MS));
        assertTrue(life.isIdle());
        assertTrue(life.begin());
    }

    @Test
    public void theDeadlineOnlyAppliesWhileStopping() {
        RecordingLifecycle life = new RecordingLifecycle();
        assertFalse(life.stopOverdue(T0));
        life.begin();
        assertFalse(life.stopOverdue(T0 + RecordingLifecycle.STOP_DEADLINE_MS * 10));
        assertEquals(Phase.PREPARING, life.phase());
    }

    /**
     * 相机层停录：几路一起叫停、一起等写入线程收好，共用 {@link CodecVideoRecorder#STOP_BUDGET_MS} 这一个期限；
     * 编码线程拖到期限的，期限之后再多给写入线程 {@link CodecVideoRecorder#STOP_GRACE_MS}（几路共用，只多这一次）。
     * 两样加起来只用协调器收拾期限的一半 —— 另一半留给释放录制器、摘录像输出、重建会话。以前一路一路等，
     * 三路加起来超过了期限，协调器不等了、又开下一次，上一次还在写同一个盘。
     */
    @Test
    public void theCameraStopBudgetLeavesHalfOfTheDeadline() {
        assertTrue(CodecVideoRecorder.STOP_BUDGET_MS > 0);
        assertTrue(CodecVideoRecorder.STOP_GRACE_MS > 0);
        assertTrue((CodecVideoRecorder.STOP_BUDGET_MS + CodecVideoRecorder.STOP_GRACE_MS) * 2
                <= RecordingLifecycle.STOP_DEADLINE_MS);
    }
}
