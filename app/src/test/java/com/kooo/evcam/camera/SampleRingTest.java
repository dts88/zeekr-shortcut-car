package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link SampleRing} 的单元测试。
 *
 * <p>排队：多长、多少字节算满，满了编码线程才少编几帧；命令和样本的先后不能乱，否则收文件时
 * 这个文件的样本还没写完。留存：留少了，掉盘时补写不回来；留多了，内存涨到被系统杀掉；
 * 什么算落盘只看写入线程真做过的 fsync。哪些该留、哪些该丢，钉在这里。</p>
 */
public class SampleRingTest {

    private static final long SEC = 1000L;
    private static final int KEY = SampleRing.FLAG_KEY_FRAME;
    private static final int CONFIG = SampleRing.FLAG_CODEC_CONFIG;

    /** 排一个样本（编码线程那一侧）：t 毫秒的画面，t 时刻进队。 */
    private static SampleRing.Sample offer(SampleRing ring, long tMs, boolean key, int size) {
        return ring.offerSample(tMs * 1000L, key ? KEY : 0, new byte[size], tMs);
    }

    /** 写入线程把队头写掉，写的时刻是 nowMs。 */
    private static SampleRing.Sample writeHead(SampleRing ring, long nowMs) {
        Object head = ring.peek();
        assertTrue("队头应该是样本：" + head, head instanceof SampleRing.Sample);
        SampleRing.Sample sample = (SampleRing.Sample) head;
        ring.written(sample, nowMs);
        return sample;
    }

    /** 排一个样本马上写掉：t 毫秒的画面，t 时刻写进 muxer。 */
    private static SampleRing.Sample write(SampleRing ring, long tMs, boolean key, int size) {
        SampleRing.Sample sample = offer(ring, tMs, key, size);
        assertSame(sample, writeHead(ring, tMs));
        return sample;
    }

    /** 每秒一帧，每 3 秒一个关键帧，从 0 到 untilMs，写完。返回写过的样本。 */
    private static List<SampleRing.Sample> fill(SampleRing ring, long untilMs) {
        List<SampleRing.Sample> out = new ArrayList<>();
        for (long t = 0; t <= untilMs; t += SEC) {
            out.add(write(ring, t, t % (3 * SEC) == 0, 10));
        }
        return out;
    }

    private static long firstKeptMs(SampleRing ring) {
        return ring.keptSnapshot().get(0).ptsUs / 1000L;
    }

    // ================================================================= 写过之后多留的那段

    @Test
    public void keepsLastFifteenSecondsEvenWhenSynced() {
        SampleRing ring = new SampleRing();
        List<SampleRing.Sample> written = fill(ring, 30 * SEC);
        ring.markSynced(written.get(written.size() - 1).seq);
        write(ring, 31 * SEC, false, 10);
        // 最近 15 秒要留：从 16 秒起；截到关键帧（15 秒那一个）
        List<SampleRing.Sample> kept = ring.keptSnapshot();
        assertEquals(15 * SEC, kept.get(0).ptsUs / 1000L);
        assertTrue(kept.get(0).keyframe);
        assertEquals(31 * SEC, kept.get(kept.size() - 1).ptsUs / 1000L);
    }

    @Test
    public void keepsUnsyncedSamplesBeyondFifteenSeconds() {
        SampleRing ring = new SampleRing();
        List<SampleRing.Sample> written = fill(ring, 40 * SEC);
        // 什么都没确认落盘：一个都不丢（还没到上限）
        assertEquals(0L, firstKeptMs(ring));
        assertEquals(40 * SEC, ring.keptMs());
        // 确认到 19 秒那一个：之前的、且早于 26 秒（41-15）的才能丢，截到关键帧 18 秒
        ring.markSynced(written.get(19).seq);
        write(ring, 41 * SEC, false, 10);
        assertEquals(18 * SEC, firstKeptMs(ring));
    }

    @Test
    public void cutsOnlyAtKeyframes() {
        SampleRing ring = new SampleRing();
        write(ring, 0, true, 10);
        write(ring, SEC, false, 10);
        write(ring, 2 * SEC, false, 10);
        write(ring, 3 * SEC, true, 10);
        SampleRing.Sample last = write(ring, 4 * SEC, false, 10);
        ring.markSynced(last.seq);
        write(ring, 30 * SEC, false, 10);
        // 0、1、2 秒能丢，3 秒是关键帧且能丢，4 秒能丢，但 4 秒后面没有关键帧 —— 截到 3 秒
        assertEquals(3 * SEC, firstKeptMs(ring));
    }

    @Test
    public void dropsOldestGopWhenOverByteCap() {
        SampleRing ring = new SampleRing();
        int big = (int) (SampleRing.MAX_BYTES / 4) + 1;
        write(ring, 0, true, big);
        write(ring, SEC, false, big);
        write(ring, 2 * SEC, true, big);
        write(ring, 3 * SEC, false, big);
        assertTrue(ring.keptBytes() <= SampleRing.MAX_BYTES);
        write(ring, 4 * SEC, true, big);
        // 超了：丢掉第一段（0、1 秒）
        assertTrue(ring.keptBytes() <= SampleRing.MAX_BYTES);
        assertEquals(2 * SEC, firstKeptMs(ring));
    }

    /** 一个内存模型：排队涨了，写过留着的让出来，一共不超过 MAX_BYTES。排着的一个都不丢。 */
    @Test
    public void queuedBytesCountAgainstTheSameMemoryCap() {
        SampleRing ring = new SampleRing();
        ring.setQueueCapBytes(SampleRing.MAX_BYTES);
        int big = (int) (SampleRing.MAX_BYTES / 4) + 1;
        write(ring, 0, true, big);
        write(ring, SEC, true, big);
        write(ring, 2 * SEC, true, big);
        assertEquals(0L, firstKeptMs(ring));
        // 写入线程卡住，排队涨了一个：留着的丢最老的一段
        offer(ring, 3 * SEC, true, big);
        assertEquals(SEC, firstKeptMs(ring));
        assertTrue(ring.keptBytes() + ring.queuedBytes() <= SampleRing.MAX_BYTES);
        assertEquals(1, ring.queuedSamples());
    }

    @Test
    public void dropsOldestGopWhenOverTimeCap() {
        SampleRing ring = new SampleRing();
        fill(ring, SampleRing.MAX_MS + 5 * SEC);
        assertTrue(ring.keptMs() <= SampleRing.MAX_MS);
        assertTrue(ring.keptSnapshot().get(0).keyframe);
    }

    @Test
    public void keptStartsAtTheFirstKeyframe() {
        SampleRing ring = new SampleRing();
        write(ring, 0, false, 10);
        write(ring, SEC, false, 10);
        assertTrue(ring.keptIsEmpty());
        write(ring, 2 * SEC, true, 10);
        write(ring, 3 * SEC, false, 10);
        assertEquals(2, ring.keptSnapshot().size());
        assertEquals(20L, ring.keptBytes());
    }

    @Test
    public void clearEmptiesEverything() {
        SampleRing ring = new SampleRing();
        fill(ring, 10 * SEC);
        offer(ring, 11 * SEC, false, 10);
        ring.offerCommand("close");
        ring.clear();
        assertTrue(ring.keptIsEmpty());
        assertEquals(0L, ring.keptBytes());
        assertEquals(0L, ring.keptMs());
        assertEquals(0, ring.queuedSamples());
        assertEquals(0L, ring.queuedBytes());
        assertEquals(0L, ring.highBytes());
        assertNull(ring.peek());
    }

    @Test
    public void clearKeptLeavesTheQueue() {
        SampleRing ring = new SampleRing();
        fill(ring, 5 * SEC);
        offer(ring, 6 * SEC, false, 10);
        ring.clearKept();
        assertTrue(ring.keptIsEmpty());
        assertEquals(1, ring.queuedSamples());
    }

    // ================================================================= 排队：多长、多少字节算满

    @Test
    public void queueIsFullAtThreeSecondsOfMedia() {
        SampleRing ring = new SampleRing();
        offer(ring, 0, true, 10);
        offer(ring, SEC, false, 10);
        offer(ring, 2 * SEC, false, 10);
        assertEquals(2 * SEC, ring.queuedMs());
        assertTrue(ring.hasRoom());
        offer(ring, SampleRing.QUEUE_MS, false, 10);
        assertEquals(SampleRing.QUEUE_MS, ring.queuedMs());
        assertFalse(ring.hasRoom());
        // 写入线程写走最老的一个，又有空位了
        writeHead(ring, 4 * SEC);
        assertEquals(2 * SEC, ring.queuedMs());
        assertTrue(ring.hasRoom());
    }

    @Test
    public void queueIsFullAtItsByteCap() {
        SampleRing ring = new SampleRing();
        ring.setQueueCapBytes(1000);
        offer(ring, 0, true, 600);
        assertTrue(ring.hasRoom());
        offer(ring, 33, false, 400);
        assertEquals(1000L, ring.queuedBytes());
        assertFalse(ring.hasRoom());
        writeHead(ring, 100);
        assertEquals(400L, ring.queuedBytes());
        assertTrue(ring.hasRoom());
    }

    /** 满了也照收：已经编码的不能丢，少编几帧是编码线程的事。 */
    @Test
    public void aFullQueueStillTakesEncodedSamples() {
        SampleRing ring = new SampleRing();
        ring.setQueueCapBytes(100);
        offer(ring, 0, true, 100);
        assertFalse(ring.hasRoom());
        offer(ring, 33, false, 50);
        offer(ring, 66, false, 50);
        assertEquals(3, ring.queuedSamples());
        assertEquals(200L, ring.queuedBytes());
    }

    @Test
    public void queueByteCapFollowsTheBitrate() {
        // 码率「高」的环视：24 Mbps × 3 秒 × 2 倍余量
        assertEquals(18_000_000L, SampleRing.queueBytesFor(24_000_000));
        // 座舱那种低码率：落到下限，由 3 秒的画面说了算
        assertEquals(SampleRing.QUEUE_MIN_BYTES, SampleRing.queueBytesFor(2_000_000));
        assertEquals(SampleRing.QUEUE_MIN_BYTES, SampleRing.queueBytesFor(0));
        assertEquals(SampleRing.QUEUE_MIN_BYTES, SampleRing.queueBytesFor(-1));
        // 再高也不超过上限
        assertEquals(SampleRing.QUEUE_MAX_BYTES, SampleRing.queueBytesFor(200_000_000));
        // 上限加上 15 秒（24 Mbps 约 45 MB）的留存，要在一个内存模型的总上限里放得下
        assertTrue(SampleRing.QUEUE_MAX_BYTES < SampleRing.MAX_BYTES);
    }

    @Test
    public void commandsAloneLeaveRoomAndDoNotCount() {
        SampleRing ring = new SampleRing();
        ring.setQueueCapBytes(10);
        ring.offerCommand("open");
        ring.offerCommand("track");
        assertTrue(ring.hasRoom());
        assertEquals(0, ring.queuedSamples());
        assertEquals(0L, ring.queuedBytes());
        assertEquals(0L, ring.queuedMs());
    }

    /**
     * 输出格式里没带参数集时，参数集当样本排（时间戳常是 0）。第二段起画面的时间戳早过了 60 秒：
     * 算排了多长的画面、最深到过多长都不看它，不然一开段就像排满了，平白丢帧、平白提示 U 盘慢。它照样排着、照样写。
     */
    @Test
    public void codecConfigSamplesDoNotStretchTheQueue() {
        SampleRing ring = new SampleRing();
        ring.offerCommand("open b");
        SampleRing.Sample config = ring.offerSample(0L, CONFIG, new byte[30], 60 * SEC);
        assertTrue(config.config);
        assertFalse(config.keyframe);
        offer(ring, 60 * SEC, true, 10);
        offer(ring, 61 * SEC, false, 10);
        assertEquals(SEC, ring.queuedMs());
        assertEquals(SEC, ring.highMs());
        assertTrue(ring.hasRoom());
        assertTrue(ring.isDrained());
        assertEquals(3, ring.queuedSamples());
        assertEquals(50L, ring.queuedBytes());

        // 只排着参数集，也不算排了画面
        SampleRing alone = new SampleRing();
        alone.offerSample(0L, CONFIG, new byte[30], 0);
        assertEquals(0L, alone.queuedMs());
        assertTrue(alone.hasRoom());
    }

    /** 带关键帧标志的从来不算「只有参数集」（两个标志都带时按关键帧算）。 */
    @Test
    public void aKeyframeIsNeverTreatedAsConfigOnly() {
        SampleRing ring = new SampleRing();
        SampleRing.Sample both = ring.offerSample(5 * SEC * 1000L, KEY | CONFIG, new byte[10], 0);
        assertTrue(both.keyframe);
        assertFalse(both.config);
    }

    /** 这里的两个标志位和 MediaCodec 的是同一个数（常量，编译时就定了，不加载 Android 的类）。 */
    @Test
    public void flagValuesMatchMediaCodec() {
        assertEquals(android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME, SampleRing.FLAG_KEY_FRAME);
        assertEquals(android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG, SampleRing.FLAG_CODEC_CONFIG);
    }

    /** 排队有多长只看样本：开文件、收文件这些命令夹在头尾也不算。 */
    @Test
    public void queuedLengthSkipsCommands() {
        SampleRing ring = new SampleRing();
        ring.offerCommand("open");
        offer(ring, 0, true, 10);
        ring.offerCommand("close");
        ring.offerCommand("open");
        offer(ring, 2 * SEC, true, 10);
        ring.offerCommand("close");
        assertEquals(2 * SEC, ring.queuedMs());
        assertEquals(2, ring.queuedSamples());
        assertEquals(20L, ring.queuedBytes());
    }

    @Test
    public void samplesGoThroughOfferSample() {
        SampleRing ring = new SampleRing();
        SampleRing.Sample sample = offer(ring, 0, true, 10);
        try {
            ring.offerCommand(sample);
            fail("a sample is not a command");
        } catch (IllegalArgumentException expected) {
            // 样本只能走 offerSample，否则字节、时长都算不上
        }
    }

    // ================================================================= 命令和样本的先后

    /** 收文件之前，这个文件的样本一定都写完了；下一个文件的样本一定在开文件之后。 */
    @Test
    public void commandsAndSamplesComeOutInTheOrderTheyWentIn() {
        SampleRing ring = new SampleRing();
        ring.offerCommand("open a");
        ring.offerCommand("track a");
        SampleRing.Sample a1 = offer(ring, 0, true, 10);
        SampleRing.Sample a2 = offer(ring, 33, false, 10);
        ring.offerCommand("close a");
        ring.offerCommand("open b");
        ring.offerCommand("track b");
        SampleRing.Sample b1 = offer(ring, 66, true, 10);
        assertEquals(3, ring.queuedSamples());

        List<Object> order = new ArrayList<>();
        Object head;
        long now = 100;
        while ((head = ring.peek()) != null) {
            order.add(head);
            if (head instanceof SampleRing.Sample) {
                ring.written((SampleRing.Sample) head, now++);
            } else {
                if ("close a".equals(head)) {
                    // 收文件那一步：写过的那段跟着这个文件一起了结
                    ring.clearKept();
                }
                ring.done(head);
            }
        }
        List<Object> expected = new ArrayList<>();
        expected.add("open a");
        expected.add("track a");
        expected.add(a1);
        expected.add(a2);
        expected.add("close a");
        expected.add("open b");
        expected.add("track b");
        expected.add(b1);
        assertEquals(expected, order);
        assertEquals(0, ring.queuedSamples());
        // 留着的只有 b 那个文件的
        List<SampleRing.Sample> kept = ring.keptSnapshot();
        assertEquals(1, kept.size());
        assertSame(b1, kept.get(0));
    }

    @Test
    public void discardDropsWithoutKeeping() {
        SampleRing ring = new SampleRing();
        SampleRing.Sample sample = offer(ring, 0, true, 10);
        ring.discard(sample);
        assertTrue(ring.keptIsEmpty());
        assertEquals(0, ring.queuedSamples());
        assertEquals(0L, ring.queuedBytes());
        assertNull(ring.peek());
    }

    /**
     * 停录时不再等卡住的写入线程：留着的和排着的按顺序交给抢救（命令也带上，抢救按开轨那条分文件），
     * 样本从队里拿掉、内存腾出来；命令留在队里，写入线程回过神来照样收文件、退出。
     */
    @Test
    public void abandonHandsOverEverythingUnsyncedAndKeepsTheCommands() {
        SampleRing ring = new SampleRing();
        SampleRing.Sample k0 = write(ring, 0, true, 10);
        SampleRing.Sample k1 = write(ring, SEC, false, 10);
        SampleRing.Sample q0 = offer(ring, 2 * SEC, false, 10);
        ring.offerCommand("close a");
        ring.offerCommand("open b");
        ring.offerCommand("track b");
        SampleRing.Sample q1 = offer(ring, 3 * SEC, true, 10);
        ring.offerCommand("close stop");

        List<Object> handed = ring.abandon();
        List<Object> expected = new ArrayList<>();
        expected.add(k0);
        expected.add(k1);
        expected.add(q0);
        expected.add("close a");
        expected.add("open b");
        expected.add("track b");
        expected.add(q1);
        expected.add("close stop");
        assertEquals(expected, handed);

        assertTrue(ring.keptIsEmpty());
        assertEquals(0L, ring.keptBytes());
        assertEquals(0, ring.queuedSamples());
        assertEquals(0L, ring.queuedBytes());
        assertTrue(ring.hasRoom());
        // 写入线程那边只剩命令，按原来的顺序
        List<Object> left = new ArrayList<>();
        Object head;
        while ((head = ring.peek()) != null) {
            left.add(head);
            ring.done(head);
        }
        List<Object> commands = new ArrayList<>();
        commands.add("close a");
        commands.add("open b");
        commands.add("track b");
        commands.add("close stop");
        assertEquals(commands, left);
    }

    /** 抢救拿走之后（或者整个清掉之后），写入线程才写完手里那一个：不再留，也不把计数算坏。 */
    @Test
    public void writtenAfterAbandonOrClearIsIgnored() {
        SampleRing ring = new SampleRing();
        SampleRing.Sample inHand = offer(ring, 0, true, 10);
        ring.abandon();
        ring.written(inHand, 10);
        assertTrue(ring.keptIsEmpty());
        assertEquals(0L, ring.queuedBytes());
        assertEquals(0, ring.queuedSamples());

        SampleRing.Sample other = offer(ring, SEC, true, 10);
        ring.clear();
        ring.written(other, 20);
        assertTrue(ring.keptIsEmpty());
        assertEquals(0L, ring.queuedBytes());
        assertEquals(0, ring.queuedSamples());
    }

    // ================================================================= 最深到过多少

    @Test
    public void highWaterMarkRemembersTheDeepest() {
        SampleRing ring = new SampleRing();
        offer(ring, 0, true, 10);
        offer(ring, SEC, false, 10);
        offer(ring, 2 * SEC, false, 10);
        writeHead(ring, 2 * SEC);
        writeHead(ring, 2 * SEC);
        writeHead(ring, 2 * SEC);
        assertEquals(0, ring.queuedSamples());
        offer(ring, 3 * SEC, false, 10);
        assertEquals(30L, ring.highBytes());
        assertEquals(2 * SEC, ring.highMs());
        ring.clear();
        assertEquals(0L, ring.highBytes());
        assertEquals(0L, ring.highMs());
    }

    // ================================================================= 满了编码线程等多久

    @Test
    public void awaitRoomGivesUpAfterItsTimeout() throws InterruptedException {
        SampleRing ring = new SampleRing();
        ring.setQueueCapBytes(100);
        offer(ring, 0, true, 100);
        long start = System.nanoTime();
        assertFalse(ring.awaitRoom(30));
        assertTrue((System.nanoTime() - start) / 1_000_000L >= 25);
    }

    @Test
    public void awaitRoomReturnsAtOnceWhenThereIsRoom() throws InterruptedException {
        SampleRing ring = new SampleRing();
        assertTrue(ring.awaitRoom(10_000));
    }

    @Test
    public void awaitRoomWakesWhenTheWriterWrites() throws Exception {
        SampleRing ring = new SampleRing();
        ring.setQueueCapBytes(100);
        offer(ring, 0, true, 100);
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                // 测试线程
            }
            writeHead(ring, 50);
        });
        writer.start();
        assertTrue(ring.awaitRoom(10_000));
        writer.join(10_000);
        assertEquals(0, ring.queuedSamples());
    }

    // ================================================================= 什么算落盘：只看写入线程真做过的 fsync

    @Test
    public void syncCountsWhatWasWrittenLongEnoughBefore() {
        SampleRing ring = new SampleRing();
        List<SampleRing.Sample> written = fill(ring, 10 * SEC);
        // 10 秒时开始 fsync：交给 muxer 早于 7 秒、画面也早于最新的（10 秒）3 秒以上的才算
        assertEquals(written.get(7).seq, ring.syncPoint(10 * SEC));
        // 一个都还没满 3 秒
        assertEquals(-1L, ring.syncPoint(2 * SEC));
    }

    /** 卡了一阵之后一口气补写好几秒：交给 muxer 的时刻挨得很近，muxer 手里还攒着，不算落盘。 */
    @Test
    public void aBurstAfterAStallIsNotCountedUntilTheSlackPasses() {
        SampleRing ring = new SampleRing();
        List<SampleRing.Sample> written = new ArrayList<>();
        for (long t = 0; t <= 10 * SEC; t += SEC) {
            offer(ring, t, t % (3 * SEC) == 0, 10);
        }
        for (int i = 0; i <= 10; i++) {
            written.add(writeHead(ring, 20 * SEC));
        }
        assertEquals(-1L, ring.syncPoint(20 * SEC));
        // 3 秒之后：交给 muxer 够久了，画面上还得比最新的早 3 秒
        assertEquals(written.get(7).seq, ring.syncPoint(23 * SEC));
    }

    @Test
    public void syncedSamplesOlderThanKeepAreDropped() {
        SampleRing ring = new SampleRing();
        fill(ring, 30 * SEC);
        assertEquals(0L, firstKeptMs(ring));
        long point = ring.syncPoint(31 * SEC);
        // 没确认的不丢
        assertEquals(0L, firstKeptMs(ring));
        ring.markSynced(point);
        // 确认到 27 秒；最近 15 秒（15 秒起）一定留着，截到关键帧 15 秒
        assertEquals(15 * SEC, firstKeptMs(ring));
    }

    /** 换盘补写之后，那段在新盘上还没落盘：旧盘上的 fsync 不算数，等新盘上的。 */
    @Test
    public void rewrittenSamplesWaitForASyncOnTheNewDrive() {
        SampleRing ring = new SampleRing();
        fill(ring, 30 * SEC);
        ring.markSynced(ring.syncPoint(31 * SEC));
        assertEquals(15 * SEC, firstKeptMs(ring));
        ring.rewritten(40 * SEC);
        assertEquals(-1L, ring.syncPoint(41 * SEC));
        // 接着录：最近 15 秒以外的也不丢，因为新盘上还一个都没确认
        for (long t = 31 * SEC; t <= 50 * SEC; t += SEC) {
            write(ring, t, t % (3 * SEC) == 0, 10);
        }
        assertEquals(15 * SEC, firstKeptMs(ring));
        // 新盘上 fsync 过了：补写的那段算落盘，早于最近 15 秒的可以丢
        ring.markSynced(ring.syncPoint(51 * SEC));
        assertEquals(33 * SEC, firstKeptMs(ring));
    }

    // ================================================================= 排满了的那几段怎么记

    @Test
    public void backpressureNotesTheFirstEpisodeThenSummarises() {
        SampleRing.Backpressure bp = new SampleRing.Backpressure();
        assertTrue(bp.dropped(1_000, 3_000, 9_000_000));
        assertFalse(bp.dropped(1_033, 3_010, 9_100_000));
        assertTrue(bp.isFull());
        SampleRing.Backpressure.Summary first = bp.admitted(1_500, true);
        assertNotNull(first);
        assertEquals(1, first.episodes);
        assertEquals(500L, first.fullMs);
        assertEquals(2L, first.droppedFrames);
        assertEquals(3_010L, first.deepestMs);
        assertEquals(9_100_000L, first.deepestBytes);
        assertFalse(bp.isFull());

        // 五分钟之内的只累加
        assertTrue(bp.dropped(2_000, 3_000, 8_000_000));
        assertNull(bp.admitted(2_600, true));
        assertTrue(bp.dropped(3_000, 3_000, 8_000_000));
        assertNull(bp.admitted(3_100, true));
        // 停录时交出来
        SampleRing.Backpressure.Summary rest = bp.flush(4_000);
        assertNotNull(rest);
        assertEquals(2, rest.episodes);
        assertEquals(700L, rest.fullMs);
        assertEquals(2L, rest.droppedFrames);
        assertNull(bp.flush(5_000));

        assertEquals(3, bp.totalEpisodes());
        assertEquals(4L, bp.totalDropped());
    }

    /** 卡在上限边上：编一帧、丢一帧来回跳，排队没退到一半以下，都算同一段，只提示一次。 */
    @Test
    public void hoveringAtTheCapIsOneEpisode() {
        SampleRing.Backpressure bp = new SampleRing.Backpressure();
        assertTrue(bp.dropped(0, 3_000, 1));
        for (long t = 33; t < 1_000; t += 66) {
            assertNull(bp.admitted(t, false));
            assertTrue(bp.isFull());
            assertFalse(bp.dropped(t + 33, 3_000, 1));
        }
        SampleRing.Backpressure.Summary summary = bp.admitted(1_200, true);
        assertNotNull(summary);
        assertEquals(1, summary.episodes);
        assertEquals(1_200L, summary.fullMs);
        assertEquals(1, bp.totalEpisodes());
    }

    @Test
    public void drainedMeansBelowHalfOfBothCaps() {
        SampleRing ring = new SampleRing();
        ring.setQueueCapBytes(1000);
        assertTrue(ring.isDrained());
        offer(ring, 0, true, 400);
        offer(ring, SEC, false, 50);
        // 450 字节、1 秒：两个都不过一半
        assertTrue(ring.isDrained());
        offer(ring, 2 * SEC, false, 10);
        // 460 字节没过一半，画面 2 秒过了 3 秒的一半
        assertFalse(ring.isDrained());
        writeHead(ring, 2 * SEC);
        writeHead(ring, 2 * SEC);
        assertTrue(ring.isDrained());
        offer(ring, 2 * SEC + 33, false, 600);
        // 字节过了一半
        assertFalse(ring.isDrained());
    }

    @Test
    public void backpressureNotesAgainAfterTheGap() {
        SampleRing.Backpressure bp = new SampleRing.Backpressure();
        bp.dropped(0, 3_000, 1);
        assertNotNull(bp.admitted(100, true));
        bp.dropped(SampleRing.Backpressure.NOTE_GAP_MS, 3_000, 1);
        SampleRing.Backpressure.Summary later = bp.admitted(SampleRing.Backpressure.NOTE_GAP_MS + 200, true);
        assertNotNull(later);
        assertEquals(1, later.episodes);
        assertEquals(200L, later.fullMs);
    }

    @Test
    public void flushClosesAnEpisodeStillRunning() {
        SampleRing.Backpressure bp = new SampleRing.Backpressure();
        assertNull(bp.flush(0));
        bp.dropped(1_000, 3_000, 1);
        SampleRing.Backpressure.Summary summary = bp.flush(1_800);
        assertNotNull(summary);
        assertEquals(800L, summary.fullMs);
        assertFalse(bp.isFull());
        assertNull(bp.admitted(2_000, true));
    }

    @Test
    public void resetStartsOver() {
        SampleRing.Backpressure bp = new SampleRing.Backpressure();
        bp.dropped(0, 3_000, 1);
        bp.admitted(10, true);
        bp.dropped(20, 3_000, 1);
        bp.reset();
        assertEquals(0, bp.totalEpisodes());
        assertEquals(0L, bp.totalDropped());
        assertFalse(bp.isFull());
        // 新的一次录像：第一段照样马上记
        bp.dropped(30, 3_000, 1);
        assertNotNull(bp.admitted(40, true));
    }
}
