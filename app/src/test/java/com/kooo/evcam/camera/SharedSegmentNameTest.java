package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 几路分段时共用的名字和界面上的段号（2026-10-11）。
 *
 * <p>判错的代价：同一段出了两个名字，回放把几路对不到同一段上，段号也多加一次；
 * 不该共用的共用了，前后两段撞名；段号跟着各路自己的序号走，一路加入、退出就卡住或者跳。</p>
 */
public class SharedSegmentNameTest {

    /** 开机时长。 */
    private static final long T0 = 5_000_000L;
    /** 墙上时间（2026-10 某天）。 */
    private static final long WALL0 = 1_791_700_000_000L;

    private static String format(long wallMs) {
        return new SimpleDateFormat(SharedSegmentName.PATTERN, Locale.getDefault()).format(new Date(wallMs));
    }

    /** 一路在 T0 + afterMs 时来要名字（墙上时间跟着走）。 */
    private static SharedSegmentName.Name at(SharedSegmentName names, long afterMs) {
        return names.next(T0 + afterMs, WALL0 + afterMs);
    }

    // ------------------------------------------------------------------ 共用

    /** 开录之后、第一次分段之前，段号是 0（各路第一个文件按自己开录的时刻起名，不经这里）。 */
    @Test
    public void beforeAnySwitchTheSegmentIsZero() {
        assertEquals(0, new SharedSegmentName().count());
    }

    /** 第一个切段的那一路出新名字：按此刻的墙上时间起，段号加 1。 */
    @Test
    public void theFirstLaneToSwitchMintsANameFromTheWallClock() {
        SharedSegmentName names = new SharedSegmentName();
        SharedSegmentName.Name first = at(names, 0);
        assertTrue(first.fresh);
        assertEquals(format(WALL0), first.text);
        assertEquals(1, first.count);
        assertEquals(1, names.count());
    }

    /** 10 秒之内来切的几路拿同一个名字，段号不动。 */
    @Test
    public void lanesSwitchingWithinTenSecondsShareTheName() {
        SharedSegmentName names = new SharedSegmentName();
        String first = at(names, 0).text;
        SharedSegmentName.Name second = at(names, 1_200);
        SharedSegmentName.Name last = at(names, SharedSegmentName.SHARE_MS - 1);
        assertEquals(first, second.text);
        assertEquals(first, last.text);
        assertFalse(second.fresh);
        assertFalse(last.fresh);
        assertEquals(1, last.count);
        assertEquals(1, names.count());
    }

    /** 满 10 秒就是下一段：新名字，段号加 1。 */
    @Test
    public void tenSecondsLaterItIsANewName() {
        SharedSegmentName names = new SharedSegmentName();
        String first = at(names, 0).text;
        SharedSegmentName.Name next = at(names, SharedSegmentName.SHARE_MS);
        assertTrue(next.fresh);
        assertNotEquals(first, next.text);
        assertEquals(format(WALL0 + SharedSegmentName.SHARE_MS), next.text);
        assertEquals(2, next.count);
    }

    /** 10 秒从出名字那一刻算，不往后顺延：6 秒、12 秒各来一路，12 秒那一路出新名字。 */
    @Test
    public void theWindowStartsAtTheMintAndDoesNotSlide() {
        SharedSegmentName names = new SharedSegmentName();
        at(names, 0);
        assertFalse(at(names, 6_000).fresh);
        assertTrue("从 0 秒那个名字算已经过了 10 秒", at(names, 12_000).fresh);
        assertEquals(2, names.count());
    }

    // ------------------------------------------------------------------ 段号

    /** 段号只在出新名字时加：三段、每段三路来要，段号是 3。 */
    @Test
    public void theCountOnlyGrowsOnNewNames() {
        SharedSegmentName names = new SharedSegmentName();
        int fresh = 0;
        for (int segment = 0; segment < 3; segment++) {
            long start = segment * 60_000L;
            for (long lag : new long[] {0L, 800L, 2_500L}) {
                if (at(names, start + lag).fresh) {
                    fresh++;
                }
            }
            assertEquals("第 " + (segment + 1) + " 次分段", segment + 1, names.count());
        }
        assertEquals(3, fresh);
    }

    /** 开新的录像意图时清零：段号从 0 数，上一次的名字哪怕还在 10 秒之内也不沿用。 */
    @Test
    public void aNewRecordingIntentStartsFromZero() {
        SharedSegmentName names = new SharedSegmentName();
        at(names, 0);
        at(names, 60_000);
        assertEquals(2, names.count());

        names.reset();
        assertEquals(0, names.count());
        SharedSegmentName.Name first = at(names, 62_000);
        assertTrue("上一次的名字才出了 2 秒，也不沿用", first.fresh);
        assertEquals(format(WALL0 + 62_000), first.text);
        assertEquals(1, first.count);
    }

    // ------------------------------------------------------------------ 两个钟

    /**
     * 过没过 10 秒按开机时长算：车机睡醒后墙上时间往前跳 20 秒，紧接着来的那一路还是拿同一个名字；
     * 墙上时间往回调一小时，满 10 秒照样出新名字（名字按调过的墙上时间起）。
     */
    @Test
    public void aWallClockJumpNeitherSplitsNorStretchesASegment() {
        SharedSegmentName names = new SharedSegmentName();
        String first = names.next(T0, WALL0).text;

        SharedSegmentName.Name afterJump = names.next(T0 + 500, WALL0 + 20_500);
        assertFalse(afterJump.fresh);
        assertEquals(first, afterJump.text);

        long back = WALL0 - 3_600_000L;
        SharedSegmentName.Name later = names.next(T0 + SharedSegmentName.SHARE_MS, back);
        assertTrue(later.fresh);
        assertEquals(format(back), later.text);
        assertEquals(2, names.count());
    }

    // ------------------------------------------------------------------ 线程

    /** 几条编码线程同一刻来要：只出一个新名字，大家拿到的都是它。 */
    @Test
    public void lanesAskingAtOnceGetOneName() throws InterruptedException {
        final SharedSegmentName names = new SharedSegmentName();
        final int threads = 3;
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicInteger fresh = new AtomicInteger();
        final String[] got = new String[threads];
        Thread[] all = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int lane = i;
            all[i] = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                SharedSegmentName.Name name = names.next(T0, WALL0);
                got[lane] = name.text;
                if (name.fresh) {
                    fresh.incrementAndGet();
                }
            });
            all[i].start();
        }
        go.countDown();
        for (Thread t : all) {
            t.join(5_000L);
        }
        assertEquals(1, fresh.get());
        assertEquals(1, names.count());
        for (String text : got) {
            assertEquals(format(WALL0), text);
        }
    }
}
