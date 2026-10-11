package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 录像被打断之后接不接回去、接几次。
 *
 * <p>最要紧的一条是最后那个测试：开始录了但一直写不进的时候，不能无限循环地接回去。</p>
 *
 * <p>摄像头被拿走、断开不在这里：那不是停（2.11，只那一路退出，一路都录不了时显示等待摄像头），
 * 没有停的原因，也就没有接回、没有额度。</p>
 */
public class RecordingStopsTest {

    /** 存储一类的整次停照旧自动接回（大纲 §7 第 3 问，2026-10-11）：收拾完、查过盘就重开，不等环视。 */
    @Test
    public void storageSideStopsResumeAutomatically() {
        assertTrue(RecordingStops.resumesAutomatically(RecordingStops.Reason.NO_DATA));
        assertTrue("管线被换：在新的管线上接回",
                RecordingStops.resumesAutomatically(RecordingStops.Reason.UNKNOWN));
        assertTrue("写不进文件：重开一次录制就是新编码器、新文件",
                RecordingStops.resumesAutomatically(RecordingStops.Reason.WRITE_STALLED));
        assertTrue("每一路的录制器都建不起来：用户要录，查过盘再试，走同一份额度",
                RecordingStops.resumesAutomatically(RecordingStops.Reason.START_FAILED));
    }

    /**
     * 每一个停的原因都要表态：自动接回的只有存储一类（写不进、一笔没写进、管线被换、录制器都建不起来）。
     * 新加一个原因而没想过接不接，这里就会失败。摄像头被拿走、断开不在原因里 —— 那不是停。
     */
    @Test
    public void onlyTheStorageSideStopsResume() {
        java.util.Set<RecordingStops.Reason> resuming = java.util.EnumSet.of(RecordingStops.Reason.NO_DATA,
                RecordingStops.Reason.WRITE_STALLED, RecordingStops.Reason.START_FAILED, RecordingStops.Reason.UNKNOWN);
        for (RecordingStops.Reason reason : RecordingStops.Reason.values()) {
            assertEquals(reason.name(), resuming.contains(reason), RecordingStops.resumesAutomatically(reason));
        }
    }

    @Test
    public void aStopByThePersonIsNeverUndone() {
        assertFalse(RecordingStops.resumesAutomatically(RecordingStops.Reason.USER));
    }

    /** 存储满了：接回去也录不下。 */
    @Test
    public void aFullDriveIsNotResumed() {
        assertFalse(RecordingStops.resumesAutomatically(RecordingStops.Reason.STORAGE_FULL));
        assertFalse(RecordingStops.resumesAutomatically(RecordingStops.Reason.STORAGE_CANNOT_FREE));
        assertFalse(RecordingStops.resumesAutomatically(RecordingStops.Reason.STORAGE_LOCKED));
    }

    /** 熄屏停的不自动接：黑着的时候不该自己录起来，亮屏时接（接不接熄屏时照 ScreenOffPlan 定）。 */
    @Test
    public void theScreenOffRuleIsLeftAsItWas() {
        assertFalse(RecordingStops.resumesAutomatically(RecordingStops.Reason.SCREEN_OFF));
    }

    @Test
    public void theBudgetRunsOutAfterThreeFailures() {
        RecordingStops.ResumeBudget budget = new RecordingStops.ResumeBudget();
        for (int i = 0; i < RecordingStops.ResumeBudget.MAX_FAILURES; i++) {
            assertTrue("第 " + (i + 1) + " 次还该让试", budget.allows());
            budget.noteAttempt();
        }
        assertFalse("三次都没成，不再试", budget.allows());
    }

    /** 录满一分钟才算恢复成功，额度回满。 */
    @Test
    public void aMinuteOfRecordingRefillsTheBudget() {
        RecordingStops.ResumeBudget budget = new RecordingStops.ResumeBudget();
        budget.noteAttempt();
        budget.noteAttempt();
        budget.noteRecordingLasted(RecordingStops.ResumeBudget.SUCCESS_AFTER_MS);
        assertEquals(0, budget.attempts());
        assertTrue(budget.allows());
    }

    /**
     * 开始录了、但一直写不进：每一轮都「开始」了，却都没录满一分钟。
     * 以前在「开始」时就清零，这种情况会无限循环。
     */
    @Test
    public void startingWithoutWritingDoesNotLoopForever() {
        RecordingStops.ResumeBudget budget = new RecordingStops.ResumeBudget();
        int rounds = 0;
        while (budget.allows() && rounds < 100) {
            budget.noteAttempt();
            budget.noteRecordingLasted(15_000L);   // 开始了，15 秒一笔没写进，停掉
            rounds++;
        }
        assertEquals(RecordingStops.ResumeBudget.MAX_FAILURES, rounds);
    }

    @Test
    public void resetClearsEarlierFailures() {
        RecordingStops.ResumeBudget budget = new RecordingStops.ResumeBudget();
        budget.noteAttempt();
        budget.noteAttempt();
        budget.noteAttempt();
        budget.reset();
        assertTrue(budget.allows());
    }
}
