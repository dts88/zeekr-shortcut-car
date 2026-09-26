package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 录像被打断之后接不接回去、接几次。
 *
 * <p>最要紧的一条是最后那个测试：开始录了但一直没画面的时候，不能无限循环地接回去 ——
 * 每接一次都会把环视重建一次。</p>
 */
public class RecordingStopsTest {

    @Test
    public void onlyCameraSideProblemsResumeWhenTheSurroundComesBack() {
        assertTrue(RecordingStops.resumesOnSurround(RecordingStops.Reason.NO_DATA));
        assertTrue(RecordingStops.resumesOnSurround(RecordingStops.Reason.UNKNOWN));
        assertTrue("写不进文件：重开一次录制就是新编码器、新文件",
                RecordingStops.resumesOnSurround(RecordingStops.Reason.WRITE_STALLED));
    }

    @Test
    public void aStopByThePersonIsNeverUndone() {
        assertFalse(RecordingStops.resumesOnSurround(RecordingStops.Reason.USER));
    }

    /** 存储满了跟环视好不好无关，接回去也录不下。 */
    @Test
    public void storageProblemsDoNotWaitForTheSurround() {
        assertFalse(RecordingStops.resumesOnSurround(RecordingStops.Reason.STORAGE_FULL));
        assertFalse(RecordingStops.resumesOnSurround(RecordingStops.Reason.STORAGE_CANNOT_FREE));
    }

    /** 熄屏录制不在这一次的范围里，维持原来的做法。 */
    @Test
    public void theScreenOffRuleIsLeftAsItWas() {
        assertFalse(RecordingStops.resumesOnSurround(RecordingStops.Reason.SCREEN_OFF));
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
     * 开始录了、但一直没画面：每一轮都「开始」了，却都没录满一分钟。
     * 以前在「开始」时就清零，这种情况会无限循环。
     */
    @Test
    public void startingWithoutPicturesDoesNotLoopForever() {
        RecordingStops.ResumeBudget budget = new RecordingStops.ResumeBudget();
        int rounds = 0;
        while (budget.allows() && rounds < 100) {
            budget.noteAttempt();
            budget.noteRecordingLasted(8_000L);   // 开始了，8 秒后没画面超时停掉
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
