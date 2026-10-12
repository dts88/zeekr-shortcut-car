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

    /**
     * 存储一类停了、判了自动接回：录像要的几样（「录像要用相机」、车辆信号、熄屏录制的唤醒锁）留着 ——
     * 相机不放、停放的录制器接回时直接加入（大纲 §7 第 8 问，项目所有者 2026-10-11「留」）。
     */
    @Test
    public void aStopThatWillResumeKeepsWhatTheRecordingHeld() {
        RecordingStops.ResumeHold hold = new RecordingStops.ResumeHold();
        hold.onStopped(true);
        assertTrue(hold.held());
        assertTrue("没在录，照在录时那样拿着", hold.claims(false));
    }

    /** 留不留只看判没判自动接回：存储一类留着；人停、熄屏停、盘满停的，停的那一下就放。 */
    @Test
    public void onlyAStopThatWillResumeKeepsTheHold() {
        for (RecordingStops.Reason reason : RecordingStops.Reason.values()) {
            RecordingStops.ResumeHold hold = new RecordingStops.ResumeHold();
            hold.onStopped(RecordingStops.resumesAutomatically(reason));
            assertEquals(reason.name(), RecordingStops.resumesAutomatically(reason), hold.claims(false));
        }
    }

    /** 额度用完（连着 3 次都没录满一分钟）：判不接回，停的那一下就放。 */
    @Test
    public void anExhaustedBudgetLetsGoAtTheStop() {
        RecordingStops.ResumeBudget budget = new RecordingStops.ResumeBudget();
        for (int i = 0; i < RecordingStops.ResumeBudget.MAX_FAILURES; i++) {
            budget.noteAttempt();
        }
        RecordingStops.ResumeHold hold = new RecordingStops.ResumeHold();
        hold.onStopped(RecordingStops.resumesAutomatically(RecordingStops.Reason.WRITE_STALLED) && budget.allows());
        assertFalse(hold.held());
        assertFalse(hold.claims(false));
    }

    /**
     * 接回开录了：留着的几样交给这一次录像，中间没有一刻放过；这一次再因为不接回的原因停了（比如盘满），才放。
     */
    @Test
    public void theResumeStartingHandsTheHoldOver() {
        RecordingStops.ResumeHold hold = new RecordingStops.ResumeHold();
        hold.onStopped(true);
        assertTrue(hold.claims(false));
        hold.finish();
        assertFalse(hold.held());
        assertTrue("接回开录了，在录照样拿着", hold.claims(true));
        hold.onStopped(false);
        assertFalse(hold.claims(false));
    }

    /** 不接了只放一次：熄屏重判里停掉还在等的那一下又叫到「不接了」，那时已经不留着，不再放一遍。 */
    @Test
    public void droppingTheHoldLetsGoOnlyOnce() {
        RecordingStops.ResumeHold hold = new RecordingStops.ResumeHold();
        hold.onStopped(true);
        assertTrue(hold.finish());
        assertFalse(hold.finish());
        assertFalse(hold.claims(false));
    }

    /** 没有判了自动接回的停就什么都不留：等 U 盘、人刚停又开在等上一次收拾完，都不拿着；不接了也没有可放的。 */
    @Test
    public void nothingIsHeldWithoutAStopThatWillResume() {
        RecordingStops.ResumeHold hold = new RecordingStops.ResumeHold();
        assertFalse(hold.held());
        assertFalse(hold.claims(false));
        assertTrue(hold.claims(true));
        assertFalse(hold.finish());
    }

    /**
     * 接回前的查盘卡住了（盘掉线时探测卡在存储线程上，后面排着的也回不来）：发出去
     * {@link RecordingStops.ResumeHold#CHECK_STUCK_MS} 还没回来，留着的几样放掉、「要录」留着接着等（审查 2026-10-11）。
     * 开录那边到点再看的那一轮（30 秒）一定判得出来；刚发出去又来看一次（人这时按了录制）不算；没留着、没在查都不算。
     */
    @Test
    public void aStuckCheckLetsTheHoldGo() {
        assertTrue(RecordingStops.ResumeHold.CHECK_STUCK_MS < RecordingCoordinator.REFUSED_RETRY_MS);
        long sent = 1_000L;
        long stuck = sent + RecordingStops.ResumeHold.CHECK_STUCK_MS;
        RecordingStops.ResumeHold hold = new RecordingStops.ResumeHold();
        hold.onStopped(true);
        assertTrue(hold.checkStuck(sent, stuck));
        assertTrue("到点再看的那一轮", hold.checkStuck(sent, sent + RecordingCoordinator.REFUSED_RETRY_MS));
        assertFalse("刚发出去", hold.checkStuck(sent, sent + 100));
        assertFalse(hold.checkStuck(sent, stuck - 1));
        assertFalse("没有没回来的查盘", hold.checkStuck(0, stuck));
        hold.finish();
        assertFalse("没留着：没有可放的", hold.checkStuck(sent, stuck));
    }

    /**
     * 录像要的几样放了（录像停了、不接回；不等自动接回了）的时候唤醒锁怎么放：屏幕黑着、锁正拉着车机 —— 先照熄屏那张表重判
     * （锁不算拉着了）。锁拿着就是开发者「熄屏录制」开着，熄屏后不停录（keepsRecording 为真）。安卓已经熄屏（车机要睡）、
     * 哨兵模式没开、停在 P 挡：判成现在停、写下「车机要睡」（已经写着的不再写），锁留到相机按次序关完再放；哨兵模式开着、
     * 在开车、或者安卓还亮着（屏幕黑了、车机醒着，熄屏 v3）：锁当场放，环视照表接着录。亮着、锁没拿着、已经在等相机关完：
     * 照停录那一下放（审查 2026-10-11：以前录像停了一律当场放，车机几秒后睡过去）。
     */
    @Test
    public void lettingGoWhileTheLockPullsTheCarIsJudgedByTheTable() {
        assertTrue(RecordingStops.rejudgesBeforeLockGoes(true, true));
        assertFalse("亮着：锁本来就没拿", RecordingStops.rejudgesBeforeLockGoes(false, true));
        assertFalse("锁没拿着、或者在等相机关完", RecordingStops.rejudgesBeforeLockGoes(true, false));
        assertFalse(RecordingStops.rejudgesBeforeLockGoes(false, false));
        boolean parked = ScreenOffPlan.driving("P", 0f);
        for (boolean autoRecord : new boolean[]{false, true}) {
            ScreenOffPlan.Decision sentryOff = ScreenOffPlan.atDark(true, false, autoRecord, 0, parked, true, true);
            assertEquals(ScreenOffPlan.Action.STOP_NOW, sentryOff.action);
            assertEquals(ScreenOffPlan.SleepFact.WRITE, ScreenOffPlan.sleepFact(true, sentryOff.action, false));
            assertEquals("已经写着：不再写，起算时刻不挪", ScreenOffPlan.SleepFact.KEEP,
                    ScreenOffPlan.sleepFact(true, sentryOff.action, true));
            assertEquals("安卓还亮着：车机醒着", ScreenOffPlan.Action.CONTINUE,
                    ScreenOffPlan.atDark(true, false, autoRecord, 0, parked, false, true).action);
            assertEquals("哨兵模式开着：车机醒着", ScreenOffPlan.Action.CONTINUE,
                    ScreenOffPlan.atDark(true, false, autoRecord, 1, parked, true, true).action);
            assertEquals("在开车：车机醒着", ScreenOffPlan.Action.CONTINUE,
                    ScreenOffPlan.atDark(true, false, autoRecord, 0, ScreenOffPlan.driving("D", 0f), true, true).action);
        }
    }
}
