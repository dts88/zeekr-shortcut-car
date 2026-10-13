package com.kooo.evcam.camera;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 几路录像分段时共用的名字（文件名里时刻那一段，如 {@code 20261011_073122}），和这一次录像出过几个名字（2026-10-11）。
 *
 * <h3>为什么共用</h3>
 *
 * <p>几路各按自己的分段计时切段，前后差个一两秒。各自按此刻起名的话，同一段的几个文件名字差一秒，
 * 回放按名字把几路对到同一段上就对不齐。所以切段时的名字共用：第一个切的那一路出一个新名字，
 * {@link #SHARE_MS} 之内来切的几路都拿这同一个。规矩和以前 {@code MultiCameraManager} 里的分段时间戳缓存一样，
 * 抽出来单独测。</p>
 *
 * <h3>段号</h3>
 *
 * <p>项目所有者 2026-10-10 定：录像每一路各自进出。之后各路自己的分段序号就对不上了 —— 中途加入的那一路从第 0 段数起，
 * 退出又加入的也是。界面上的段号以前取「各路报上来的最大序号」，一路加入、退出一次就会卡住或者跳。
 * 现在段号只看这里：<b>出一个新名字，段号才加 1</b>（{@link #count}）；同一个名字被几路拿走，段号不动。
 * 每一路的第一个文件按它自己开录的时刻起名，不经这里（加入的那一路到下一次分段时再和别的路对齐），
 * 所以开录之后、第一次分段之前段号是 0。</p>
 *
 * <p>开新的录像意图时清零（{@link #reset}）：段号从 0 数起，上一次的名字哪怕还在 10 秒之内也不再沿用。</p>
 *
 * <h3>两个钟</h3>
 *
 * <p>名字按墙上时间起（回放、文件管理按它摆）；过没过 10 秒按开机时长算（调用方传
 * {@code SystemClock.elapsedRealtime()}）。车机睡醒后墙上时间会跳 18–23 秒：按墙上时间算的话，
 * 刚出的名字一跳就「过期」，紧接着来切的那一路另出一个，同一段出两个名字、段号多加一次；
 * 墙上时间往回调时，又会把一个名字共用到远超 10 秒。</p>
 *
 * <p>几条编码线程会同时来要名字：几个方法在同一把锁里。纯 Java，时间由调用方传入，见 {@code SharedSegmentNameTest}。</p>
 */
public final class SharedSegmentName {

    /**
     * 出了一个名字之后，这么久之内来要的都拿它。从出名字那一刻算，不往后顺延。
     * 要盖得住各路切段的时间差（各路首次写入的先后不同，分段计时从那时起算）。
     * 和 {@code MultiCameraManager.TIMESTAMP_CACHE_DURATION_MS} 是同一个数，自动锁定算文件结束时按那个放宽
     * （{@code LockWindow.SLACK_MS}），两边要一起改。
     */
    public static final long SHARE_MS = 10_000L;

    /** 名字的格式：和各路第一个文件、MediaRecorder 模式用的是同一个。 */
    public static final String PATTERN = "yyyyMMdd_HHmmss";

    /** 一次要名字的结果。 */
    public static final class Name {
        /** 名字：只有时刻那一段，不带路的后缀。 */
        public final String text;
        /** 是不是这一次新出的（不是沿用 {@link SharedSegmentName#SHARE_MS} 之内出过的那个）。 */
        public final boolean fresh;
        /** 到这一个为止，这一次录像出过几个名字 —— 也就是此刻的段号（从 0 数）。 */
        public final int count;

        Name(String text, boolean fresh, int count) {
            this.text = text;
            this.fresh = fresh;
            this.count = count;
        }
    }

    /** 最近出的名字；这一次录像还没出过是 null。 */
    private String text;
    /** 它是什么时候出的（开机时长）。 */
    private long mintedAtMs;
    /** 这一次录像出过几个名字。 */
    private int count;

    /**
     * 一路要切段了，要一个名字：{@link #SHARE_MS} 之内出过的就沿用，否则按此刻的墙上时间出一个新的，段号加 1。
     *
     * @param nowMs  此刻的开机时长（{@code SystemClock.elapsedRealtime()}），只用来算过没过 10 秒
     * @param wallMs 此刻的墙上时间（{@code System.currentTimeMillis()}），只用来起名字
     */
    public synchronized Name next(long nowMs, long wallMs) {
        if (text != null && nowMs - mintedAtMs < SHARE_MS) {
            return new Name(text, false, count);
        }
        // 时区、语言每次现取，和各路第一个文件起名时一样（车机改了时区，新名字跟着变）
        text = new SimpleDateFormat(PATTERN, Locale.getDefault()).format(new Date(wallMs));
        mintedAtMs = nowMs;
        count++;
        return new Name(text, true, count);
    }

    /** 这一次录像出过几个名字，也就是此刻的段号（从 0 数）。 */
    public synchronized int count() {
        return count;
    }

    /** 开新的录像意图：段号清零，上一次的名字不再沿用。 */
    public synchronized void reset() {
        text = null;
        mintedAtMs = 0L;
        count = 0;
    }
}
