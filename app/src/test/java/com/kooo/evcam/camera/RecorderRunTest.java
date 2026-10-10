package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.camera.RecorderRun.Phase;

import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 录制器能走的几步（2026-10-11）：一路退出、停录之后录制器停放着，加入时重新备好再录，能来回走好几趟。
 *
 * <p>判错的代价：放过了不该走的一步，两条线程一起动同一个编码器（停到一半又开录、停放到一半又重新备好），
 * 或者往一条卡死的写入线程排新文件；挡住了该走的一步，停放着的录制器再也接不回录像，相机回来了这一路也不录。</p>
 */
public class RecorderRunTest {

    /** 一个照合法的路走到 target 的录制器（路上每一步都得走得成）。 */
    private static RecorderRun at(Phase target) {
        RecorderRun run = new RecorderRun();
        if (target == Phase.NEW) {
            return run;
        }
        if (target == Phase.RELEASED) {
            assertEquals(Phase.NEW, run.release());
            return run;
        }
        assertTrue(run.prepared());
        if (target == Phase.PREPARED) {
            return run;
        }
        assertTrue(run.start());
        if (target == Phase.RECORDING) {
            return run;
        }
        assertTrue(run.beginStop());
        if (target == Phase.STOPPING) {
            return run;
        }
        assertTrue(run.stopped());
        if (target == Phase.STOPPED) {
            return run;
        }
        assertTrue(run.beginPark());
        if (target == Phase.PARKING) {
            return run;
        }
        assertTrue(run.parked());
        if (target == Phase.PARKED) {
            return run;
        }
        assertTrue(run.beginRearm());
        assertEquals(Phase.REARMING, target);
        return run;
    }

    /** 开、叫停、停放、重新备好，四步一样都走不成，原地不动。 */
    private static void assertNothingBegins(RecorderRun run, String why) {
        Phase before = run.phase();
        assertFalse(why + "：不能开", run.start());
        assertFalse(why + "：不能叫停", run.beginStop());
        assertFalse(why + "：不能停放", run.beginPark());
        assertFalse(why + "：不能重新备好", run.beginRearm());
        assertEquals(why, before, run.phase());
    }

    // ------------------------------------------------------------------ 表

    /** 表本身：每一步只在大纲写的那几个状态上走得成。 */
    @Test
    public void theTableAllowsEachStepOnlyWhereThePlanSays() {
        Set<Phase> start = EnumSet.noneOf(Phase.class);
        Set<Phase> stop = EnumSet.noneOf(Phase.class);
        Set<Phase> park = EnumSet.noneOf(Phase.class);
        Set<Phase> rearm = EnumSet.noneOf(Phase.class);
        Set<Phase> release = EnumSet.noneOf(Phase.class);
        for (Phase p : Phase.values()) {
            if (RecorderRun.canStart(p, false)) {
                start.add(p);
            }
            if (RecorderRun.canStop(p)) {
                stop.add(p);
            }
            if (RecorderRun.canPark(p)) {
                park.add(p);
            }
            if (RecorderRun.canRearm(p, false)) {
                rearm.add(p);
            }
            if (RecorderRun.canRelease(p)) {
                release.add(p);
            }
            assertFalse("写入线程被放弃过的哪一步都不能开：" + p, RecorderRun.canStart(p, true));
            assertFalse("写入线程被放弃过的哪一步都不能重新备好：" + p, RecorderRun.canRearm(p, true));
        }
        assertEquals("新备好的（含重新备好的）能开", EnumSet.of(Phase.PREPARED), start);
        assertEquals("开着的、备好了还没开过录的能叫停", EnumSet.of(Phase.PREPARED, Phase.RECORDING), stop);
        assertEquals("停完的能停放", EnumSet.of(Phase.STOPPED), park);
        assertEquals("停放着的能重新备好", EnumSet.of(Phase.PARKED), rearm);
        assertEquals("除了放掉的都能放", EnumSet.complementOf(EnumSet.of(Phase.RELEASED)), release);
    }

    // ------------------------------------------------------------------ 开

    /** 新准备好的可以开；还没备好的不行。 */
    @Test
    public void aFreshlyPreparedRecorderCanStart() {
        RecorderRun run = new RecorderRun();
        assertEquals(Phase.NEW, run.phase());
        assertFalse("还没备好", run.start());
        assertTrue(run.prepared());
        assertTrue(run.start());
        assertEquals(Phase.RECORDING, run.phase());
    }

    /** 开着的不能再开（分段切换、编码器重建的那一下也算开着）。 */
    @Test
    public void aRunningRecorderCannotStartAgain() {
        RecorderRun run = at(Phase.RECORDING);
        assertFalse(run.start());
        assertEquals(Phase.RECORDING, run.phase());
    }

    // ------------------------------------------------------------------ 停

    /** 正在停的既不能开，也不能停放或重新备好；叫停只做一次。 */
    @Test
    public void aStoppingRecorderCanNeitherStartNorParkNorRearm() {
        assertNothingBegins(at(Phase.STOPPING), "正在停");
    }

    /** 备好了还没开过录的也照「停 → 停放」一条路放掉编码器（停录时它那一路还没加入）。 */
    @Test
    public void aPreparedRecorderThatNeverRecordedStopsAndParksTheSameWay() {
        RecorderRun run = at(Phase.PREPARED);
        assertTrue(run.beginStop());
        assertFalse("正在停", run.start());
        assertTrue(run.stopped());
        assertTrue(run.beginPark());
        assertTrue(run.parked());
        assertEquals(Phase.PARKED, run.phase());
    }

    // ------------------------------------------------------------------ 停放、重新备好

    /** 停完的可以停放，停放的可以重新备好，重新备好的又能开。停完的不能跳过停放直接重新备好。 */
    @Test
    public void aStoppedRecorderParksAndAParkedOneRearms() {
        RecorderRun run = at(Phase.STOPPED);
        assertFalse("停完的编码器收到过结束信号，不能再用", run.start());
        assertFalse("先停放再重新备好", run.beginRearm());
        assertTrue(run.beginPark());
        assertTrue(run.parked());
        assertFalse("停放着的没有编码器", run.start());
        assertTrue(run.beginRearm());
        assertTrue(run.rearmed());
        assertEquals(Phase.PREPARED, run.phase());
        assertTrue(run.start());
    }

    /** 一次录像里一路可以退出、接回好几次：同一个录制器来回走，每一趟都走得通。 */
    @Test
    public void oneRecorderGoesRoundSeveralTimes() {
        RecorderRun run = at(Phase.PREPARED);
        for (int round = 1; round <= 3; round++) {
            String why = "第 " + round + " 趟";
            assertTrue(why, run.start());
            assertTrue(why, run.beginStop());
            assertTrue(why, run.stopped());
            assertTrue(why, run.beginPark());
            assertTrue(why, run.parked());
            assertTrue(why, run.beginRearm());
            assertTrue(why, run.rearmed());
        }
        assertEquals(Phase.PREPARED, run.phase());
    }

    /** 停放、重新备好要花几秒：这中间什么都不许插进来。 */
    @Test
    public void nothingGetsInWhileParkingOrRearming() {
        assertNothingBegins(at(Phase.PARKING), "正在停放");
        assertNothingBegins(at(Phase.REARMING), "正在重新备好");
    }

    /** 新的编码器没建起来：还是停放着，不能开。 */
    @Test
    public void aRearmThatFailsLeavesItParked() {
        RecorderRun run = at(Phase.REARMING);
        assertTrue(run.rearmFailed());
        assertEquals(Phase.PARKED, run.phase());
        assertFalse(run.start());
    }

    /** 没开始的那一步不能说做完了（迟到的、重复的回报不算数）。 */
    @Test
    public void aStepThatWasNotBegunCannotFinish() {
        RecorderRun run = at(Phase.PREPARED);
        assertFalse("备好了的不能再备好一次", run.prepared());
        assertFalse(run.stopped());
        assertFalse(run.parked());
        assertFalse(run.rearmed());
        assertFalse(run.rearmFailed());
        assertEquals(Phase.PREPARED, run.phase());

        RecorderRun parked = at(Phase.PARKED);
        assertFalse("停放好了的不能再停放好一次", parked.parked());
        assertEquals(Phase.PARKED, parked.phase());
    }

    // ------------------------------------------------------------------ 写入线程被放弃

    /** 写入线程被放弃过的（盘卡死了）：照样停完、停放，但不能重新备好 —— 要录就换一个新录制器。 */
    @Test
    public void aRecorderWhoseWriterWasAbandonedCannotBeRearmed() {
        RecorderRun run = at(Phase.STOPPING);
        run.noteWriterAbandoned();
        assertTrue(run.writerAbandoned());
        assertTrue("到点没收好也算停完", run.stopped());
        assertTrue(run.beginPark());
        assertTrue(run.parked());
        assertFalse(run.beginRearm());
        assertFalse(run.start());
        assertEquals(Phase.PARKED, run.phase());
        assertEquals("PARKED, writer abandoned", run.toString());
        assertEquals("放还是能放", Phase.PARKED, run.release());
    }

    // ------------------------------------------------------------------ 放

    /** 已经放掉的，什么都不能做；再放一次说「已经放过了」。 */
    @Test
    public void aReleasedRecorderCanDoNothing() {
        RecorderRun run = at(Phase.RELEASED);
        assertNothingBegins(run, "放掉了");
        assertFalse(run.prepared());
        assertFalse(run.stopped());
        assertFalse(run.parked());
        assertFalse(run.rearmed());
        assertFalse(run.rearmFailed());
        assertEquals(Phase.RELEASED, run.release());
        assertEquals(Phase.RELEASED, run.phase());
    }

    /** 哪一步都能放，放的一方拿到放之前在哪一步（开着的要先收文件）。 */
    @Test
    public void releaseComesAtAnyStepAndSaysWhereItWas() {
        for (Phase p : Phase.values()) {
            if (p == Phase.RELEASED) {
                continue;
            }
            RecorderRun run = at(p);
            assertEquals(p, run.release());
            assertEquals(Phase.RELEASED, run.phase());
        }
    }

    /** 停、停放、重新备好做到一半被放掉：做完时走不成，录制器不会被带回来。 */
    @Test
    public void aStepThatFinishesAfterReleaseDoesNotBringItBack() {
        RecorderRun stopping = at(Phase.STOPPING);
        stopping.release();
        assertFalse(stopping.stopped());

        RecorderRun parking = at(Phase.PARKING);
        parking.release();
        assertFalse(parking.parked());

        RecorderRun rearming = at(Phase.REARMING);
        rearming.release();
        assertFalse("刚建的编码器由调用方自己放", rearming.rearmed());
        assertFalse(rearming.rearmFailed());

        assertEquals(Phase.RELEASED, stopping.phase());
        assertEquals(Phase.RELEASED, parking.phase());
        assertEquals(Phase.RELEASED, rearming.phase());
    }

    // ------------------------------------------------------------------ 线程

    /** 几条线程同时想开录（或同时想停放）：只有一条走得成。 */
    @Test
    public void threadsRacingForTheSameStepOnlyOneWins() throws InterruptedException {
        RecorderRun run = at(Phase.PREPARED);
        assertEquals(1, race(run::start));

        RecorderRun stopped = at(Phase.STOPPED);
        assertEquals(1, race(stopped::beginPark));
    }

    private interface Step {
        boolean go();
    }

    /** 8 条线程同一刻走同一步，返回走成了几次。 */
    private static int race(Step step) throws InterruptedException {
        final int threads = 8;
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicInteger wins = new AtomicInteger();
        Thread[] all = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            all[i] = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (step.go()) {
                    wins.incrementAndGet();
                }
            });
            all[i].start();
        }
        go.countDown();
        for (Thread t : all) {
            t.join(5_000L);
        }
        return wins.get();
    }
}
