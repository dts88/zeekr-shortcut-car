package com.kooo.evcam.camera;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 最近一段已编码的视频样本，多留一份在内存里。
 *
 * <p>为什么要有它：录像盘掉线时（2026-09-26 哨兵模式，固态盘一锁车就从系统里消失），
 * 写进文件的最后二十来秒其实还在系统的写缓存里，盘一没它们就跟着没了 —— 那个文件最后是 0 字节。
 * 有了这一份，换到另一个盘之后先把它们补写进去，再接着录，掉盘前后的画面就不丢。</p>
 *
 * <p>留多久：最近 {@link #KEEP_MS} 一定留着；比这更早的，要等 fsync 确认它落盘了才丢。
 * 上限 {@link #MAX_MS} / {@link #MAX_BYTES}，超过就丢最老的 —— fsync 一直不成功时内存不能无限涨。
 * 丢只丢到关键帧：补写要从关键帧开始，否则前面几帧解不出来。</p>
 *
 * <p>只存样本本身。它们那一代编码器的输出格式由录制器记着：换了编码器就换了格式，
 * 两代样本不能写进同一个文件。</p>
 */
final class SampleRing {

    /** 最近这么久一定留着（项目拥有者 2026-09-26：「至少缓存了 15 秒的信息」）。 */
    static final long KEEP_MS = 15_000L;
    /** 最多留这么久。 */
    static final long MAX_MS = 60_000L;
    /** 最多占这么多内存。环视四宫格 2560×2560 HEVC 实测每分钟 58 MB，够一分钟。 */
    static final long MAX_BYTES = 64L * 1024 * 1024;

    static final class Sample {
        /** 编码器给的原始时间戳：整场录像单调递增，不按分段归零。 */
        final long ptsUs;
        /** 写进文件时的墙上时间。 */
        final long wallMs;
        final boolean keyframe;
        final byte[] data;

        Sample(long ptsUs, long wallMs, boolean keyframe, byte[] data) {
            this.ptsUs = ptsUs;
            this.wallMs = wallMs;
            this.keyframe = keyframe;
            this.data = data;
        }
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long bytes;
    /** 写入时间早于它的样本已经 fsync 到盘上了。 */
    private long syncedBeforeMs;

    /** 记一个刚写进文件的样本。环空着时先等关键帧：环从关键帧起才补得回去。 */
    synchronized void add(long ptsUs, long wallMs, boolean keyframe, ByteBuffer src, int size) {
        if (samples.isEmpty() && !keyframe) {
            return;
        }
        byte[] data = new byte[size];
        src.duplicate().get(data);
        samples.addLast(new Sample(ptsUs, wallMs, keyframe, data));
        bytes += size;
        trim(wallMs);
    }

    /** 写入时间早于 wallMs 的样本已经落盘（fsync 成功）。 */
    synchronized void markSyncedBefore(long wallMs) {
        syncedBeforeMs = Math.max(syncedBeforeMs, wallMs);
    }

    synchronized void clear() {
        samples.clear();
        bytes = 0;
    }

    synchronized boolean isEmpty() {
        return samples.isEmpty();
    }

    synchronized long bytes() {
        return bytes;
    }

    /** 第一个到最后一个样本隔了多久（毫秒）。 */
    synchronized long spanMs() {
        return samples.isEmpty() ? 0 : samples.peekLast().wallMs - samples.peekFirst().wallMs;
    }

    /** 从头到尾的一份拷贝，按时间顺序，头是关键帧。 */
    synchronized List<Sample> snapshot() {
        return new ArrayList<>(samples);
    }

    /** 应用保留规则；只在关键帧处截断。 */
    private void trim(long nowMs) {
        // 1. 已落盘、又早于 KEEP_MS 的丢掉：从头数，截到「前面的都能丢」的最后一个关键帧
        long keepFrom = nowMs - KEEP_MS;
        int cut = 0;
        int i = 0;
        for (Sample s : samples) {
            if (s.keyframe) {
                cut = i;
            }
            if (s.wallMs >= keepFrom || s.wallMs >= syncedBeforeMs) {
                break;
            }
            i++;
        }
        dropFirst(cut);

        // 2. 超过上限：整段整段（关键帧到下一个关键帧）丢最老的；只剩一段时没法再丢
        while (bytes > MAX_BYTES || spanMs() > MAX_MS) {
            int next = secondKeyframeIndex();
            if (next < 0) {
                break;
            }
            dropFirst(next);
        }
    }

    private int secondKeyframeIndex() {
        int i = 0;
        for (Sample s : samples) {
            if (i > 0 && s.keyframe) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private void dropFirst(int count) {
        for (int i = 0; i < count; i++) {
            Sample s = samples.pollFirst();
            if (s == null) {
                break;
            }
            bytes -= s.data.length;
        }
    }
}
