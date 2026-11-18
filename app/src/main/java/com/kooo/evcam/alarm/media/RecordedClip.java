/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.media;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.alarm.ClipWindow;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.StoragePlan;
import com.kooo.evcam.zeekr.RecordingTimeline;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 几个小时前那一刻的短视频，从盘上的录像里切出来。
 *
 * <h3>为什么不是样本环</h3>
 *
 * <p>停车警报说的可能是几个小时前那一刻。内存里的样本环只留最近约一分钟
 * （{@code SampleRing}），再往前它就答不上来（{@code CodecVideoRecorder#writeClipSecondsEndingMsAgo}
 * 回 false 就是「那一刻没有」）；但那一刻的画面还在盘上的录像里，从录像里切出来，切到的是<b>那一刻</b>。
 * 这是兜底那条路，可以真的花时间读盘、解封装，只要对能切不能切说实话。</p>
 *
 * <h3>窗口怎么定</h3>
 *
 * <p>以那一刻为中心，前后各 {@code seconds / 2} 秒：{@code [wallMs - half, wallMs + half]}。
 * 先找盖住那一刻的那个文件 —— 目录、文件名的规矩和取帧那边同一套（{@code RecordedFrame}）：
 * {@link StorageHelper#getVideoDir}（外加降级那个 {@link StorageHelper#lastRecordingDir}）、
 * 只认本应用的分段（{@link StoragePlan#isOwnClip}）、开录时刻从名字里读
 * （{@link RecordingTimeline#parseStartEpochMs}）、优先环视那一路、时长用
 * {@link MediaMetadataRetriever} 真读一次才作数。</p>
 *
 * <h3>跨两个文件时怎么办</h3>
 *
 * <p>不接。窗口压在两个分段的接缝上时，只取落在同一个文件里的那半截 —— 画面不接，
 * 声音更不接（两段声音的起始偏移对不上，硬接就是一句被剪断的话加上另一句的开头）。
 * 切出来的片子于是比要的短，这是实话，不拿别处的画面凑够长度，也不假装接上了。
 * 那一刻本身必须落在某个文件里（{@link #covering}），落在空档里就是没有。</p>
 *
 * <h3>不重新编码</h3>
 *
 * <p>{@link MediaExtractor} 读、{@link MediaMuxer} 写，画面和声音的样本原样拷过去，
 * 只挑落在窗口里的：不解码、不重编、不动正在录的那个文件。代价是起点只能退到最近的关键帧
 * （H.264 从中间一帧开始就是花的，理由见 {@link ClipWindow}），所以片子可能比窗口早一点点开始；
 * 结尾是准的，窗口外的样本一个都不写。</p>
 *
 * <p>回 {@code null} 一律表示「那一刻切不出来」：没有录像盖住、窗口跨接缝跨到没有样本、
 * 一个样本都没选中、写不出来、读盘出错。绝不抛，也绝不拿别处的画面凑。</p>
 *
 * The short clip of a moment hours back, cut out of the recordings on storage.
 *
 * <h3>Why not the sample ring</h3>
 *
 * <p>A parked-alert moment can be hours old. The encoded ring in memory holds only the last minute or
 * so ({@code SampleRing}); older than that it answers nothing
 * ({@code CodecVideoRecorder#writeClipSecondsEndingMsAgo} returning false means "that moment is not
 * available"). The picture of that moment is still on storage, though, and cutting it out of the
 * recording gives <b>that</b> moment. This is the fallback lane: it may spend real time reading the
 * drive and demuxing, as long as it is honest about what it can and cannot produce.</p>
 *
 * <h3>How the window is resolved</h3>
 *
 * <p>Centred on the moment, {@code seconds / 2} either side: {@code [wallMs - half, wallMs + half]}.
 * The file covering the moment is found by the same rules the frame side uses
 * ({@code RecordedFrame}): {@link StorageHelper#getVideoDir} plus the fallback lane's
 * {@link StorageHelper#lastRecordingDir}, only this app's segments ({@link StoragePlan#isOwnClip}),
 * the start read from the name ({@link RecordingTimeline#parseStartEpochMs}), the surround lane
 * preferred, and the real duration read with a {@link MediaMetadataRetriever} before it counts.</p>
 *
 * <h3>When the window crosses two files</h3>
 *
 * <p>It is not stitched. A window straddling a segment boundary keeps only the part inside one
 * file — no joining pictures, and above all no joining sound (the two segments' audio offsets do not
 * line up; a hard join is half a word followed by the start of another). The clip is therefore
 * shorter than asked, which is the honest answer; no other moment's footage is borrowed to pad it and
 * no stitch is pretended. The moment itself must fall inside some file ({@link #covering}); falling in
 * a gap means there is nothing.</p>
 *
 * <h3>No re-encode</h3>
 *
 * <p>{@link MediaExtractor} reads, {@link MediaMuxer} writes, video and audio samples are copied
 * straight through, selecting only those inside the window: nothing decoded, nothing re-encoded, the
 * recording in progress untouched. The cost is that the start can only reach back to the nearest
 * keyframe (H.264 cut mid-GOP is corrupt, reasoning in {@link ClipWindow}), so the clip may begin a
 * little before the window; the end is exact, and not one sample past it is written.</p>
 *
 * <p>{@code null} always means "this moment cannot be cut": no recording covers it, the window
 * crossed a boundary into nothing, zero samples were selected, the write failed, or the drive
 * errored. It never throws, and it never substitutes another moment.</p>
 */
public final class RecordedClip {

    /** 最多打开几个候选文件读时长；和取帧那边一样，再往前就是换盘、空档。 */
    private static final int MAX_CANDIDATES = 6;

    private static final String VIDEO_PREFIX = "video/";
    private static final String AUDIO_PREFIX = "audio/";
    /** 读样本的缓冲区下限；真的样本更大时按 format 里的 KEY_MAX_INPUT_SIZE 开。 */
    private static final int DEFAULT_INPUT_BYTES = 256 * 1024;

    private RecordedClip() {
    }

    /**
     * 盘上录着的那一刻：哪个文件、它从几点开录、真读出来的多长。
     *
     * The moment as it sits on storage: which file, when it starts, and how long it really is.
     */
    static final class Covering {
        final File file;
        final long startMs;
        final long durationMs;

        Covering(File file, long startMs, long durationMs) {
            this.file = file;
            this.startMs = startMs;
            this.durationMs = durationMs;
        }

        long endMs() {
            return startMs + durationMs;
        }
    }

    /**
     * 切出以 {@code wallMs} 为中心、长 {@code seconds} 秒的一段，回写好的临时文件。
     *
     * <p>文件写在 {@code context.getCacheDir()} 里，调用方用完自己删。窗口压在两个分段的接缝上时，
     * 只取落在同一个文件里的那部分，片子比要的短 —— 不接画面，声音更不接。</p>
     *
     * Cut the {@code seconds}-long span centred on {@code wallMs} into a temporary file.
     *
     * <p>The file lands in {@code context.getCacheDir()} and the caller deletes it. When the window
     * straddles a segment boundary only the part inside one file is kept, so the clip is shorter than
     * asked — pictures are never joined, and sound even less so.</p>
     *
     * @param wallMs 墙上时间（毫秒），警报那一刻
     * @param seconds 要多长（5、10）
     * @return 临时文件；没有录像盖住那一刻、一个样本都没选中、写不出来、读盘出错，都是 {@code null}
     */
    public static File cut(Context context, long wallMs, int seconds) {
        if (context == null || wallMs <= 0L || seconds <= 0) {
            return null;
        }
        long[] window = window(wallMs, seconds);
        if (window == null) {
            return null;
        }
        File out = null;
        try {
            Covering covering = covering(context, wallMs);
            if (covering == null) {
                return null;  // 那一刻没有录像盖住（落在空档里、或者盘上还没有）
            }
            // 窗口夹到这个文件里：跨接缝的另一半不要，片子短一点也比硬接一段假的强
            // Clamp the window into this file: the other side of the boundary is dropped, a shorter honest clip beats a faked join
            long[] span = spanInside(covering.startMs, covering.durationMs, window[0], window[1]);
            if (span == null) {
                return null;
            }
            File cache = context.getCacheDir();
            if (cache == null) {
                return null;
            }
            out = new File(cache, "alert-clip-" + wallMs + "-" + seconds + "s-"
                    + System.nanoTime() + ".mp4");
            long fromUs = Math.max(0L, span[0] - covering.startMs) * 1000L;
            long toUs = Math.max(0L, span[1] - covering.startMs) * 1000L;
            if (toUs <= fromUs) {
                return null;
            }
            return mux(covering.file, fromUs, toUs, out) ? out : null;
        } catch (Throwable t) {
            // 存储掉线（U 盘抽走）、读盘出错：这一刻就是切不出来，不往上抛，留半个文件也不行
            // Storage gone (USB pulled) or a read error: this moment simply cannot be cut, never thrown on, and never left half written
            delete(out);
            return null;
        }
    }

    /**
     * 盘上还留着的最老一条录像的开录时刻，给车主说「最早能回到几点几分」。
     *
     * <p>只看文件名，不开文件：问的是盘上还有多久的东西，不是这一条能不能播。</p>
     *
     * The start of the oldest recording still on storage, so the owner can be told how far back it
     * goes. Only file names are read, no file is opened: the question is how much history is on the
     * drive, not whether one file plays.
     *
     * @return 毫秒时间戳；盘上一条本应用的录像都没有时 {@code null}
     */
    public static Long earliestRecording(Context context) {
        if (context == null) {
            return null;
        }
        try {
            long earliest = Long.MAX_VALUE;
            for (File file : segmentsOnStorage(context)) {
                long start = RecordingTimeline.parseStartEpochMs(file.getName());
                if (start > 0L && start < earliest) {
                    earliest = start;
                }
            }
            return earliest == Long.MAX_VALUE ? null : Long.valueOf(earliest);
        } catch (Throwable t) {
            // 目录都列不出来：答不上来就是 null，不编一个时刻
            // The directory will not even list: the answer is null, never an invented moment
            return null;
        }
    }

    // ---------- 纯算术：窗口、覆盖、选文件（RecordedClipTest） ----------

    /**
     * 以那一刻为中心、长 {@code seconds} 秒的窗口 {@code [from, to]}。
     *
     * @return {@code {from, to}}；参数不对时 null
     *
     * The window {@code [from, to]} centred on the moment and {@code seconds} long, or null when the
     * arguments make no sense.
     */
    static long[] window(long wallMs, int seconds) {
        if (wallMs <= 0L || seconds <= 0) {
            return null;
        }
        long halfMs = seconds * 1000L / 2L;
        return new long[] {wallMs - halfMs, wallMs + halfMs};
    }

    /**
     * 这一个文件盖不盖住那一刻：开录不晚于它，结束晚于它（结束那一刻不算，下一个文件从那里开始）。
     *
     * Does this file cover the moment: starting no later than it and ending after it (the end instant
     * belongs to the next file).
     */
    static boolean contains(long startMs, long durationMs, long wallMs) {
        return startMs > 0L && durationMs > 0L
                && wallMs >= startMs && wallMs < startMs + durationMs;
    }

    /**
     * 这一个文件盖不盖住整个窗口 —— 盖住了才不用把窗口夹短。
     *
     * Does this file cover the whole window — only then does the window need no clamping.
     */
    static boolean covers(long startMs, long durationMs, long fromMs, long toMs) {
        return startMs > 0L && durationMs > 0L && toMs > fromMs
                && fromMs >= startMs && toMs <= startMs + durationMs;
    }

    /**
     * 哪个文件盖住那一刻。
     *
     * <p>几条录像有重叠时取开录最晚的那条：它离那一刻最近，前面的说明那一刻已经录完一遍了。</p>
     *
     * @return 下标；没有一个盖住（空档、空数组、两个数组不等长）时 -1
     *
     * Which file covers the moment. With overlapping recordings the one that started latest wins: it is
     * the closest to the moment, the earlier one has already finished recording past it.
     *
     * @return the index, or -1 when none covers it (a gap, an empty array, arrays of unequal length)
     */
    static int containing(long[] startMs, long[] durationMs, long wallMs) {
        if (startMs == null || durationMs == null || startMs.length != durationMs.length) {
            return -1;
        }
        int best = -1;
        long bestStart = Long.MIN_VALUE;
        for (int i = 0; i < startMs.length; i++) {
            if (!contains(startMs[i], durationMs[i], wallMs)) {
                continue;
            }
            if (best < 0 || startMs[i] > bestStart) {
                best = i;
                bestStart = startMs[i];
            }
        }
        return best;
    }

    /**
     * 把窗口夹到一个文件里：只留两边重叠的那一段。
     *
     * <p>窗口压在两个分段的接缝上时，这里就是「只取落在同一个文件里的那部分」——
     * 接缝另一边的不接，于是片子比要的短。</p>
     *
     * Clamp the window into one file: keep only the overlapping stretch. On a segment boundary this is
     * exactly "only the part inside one file" — the other side is not stitched, so the clip comes out
     * shorter than asked.
     *
     * @return {@code {begin, end}}；完全不重叠时 null
     */
    static long[] spanInside(long startMs, long durationMs, long fromMs, long toMs) {
        if (durationMs <= 0L || toMs <= fromMs) {
            return null;
        }
        long begin = Math.max(fromMs, startMs);
        long end = Math.min(toMs, startMs + durationMs);
        return end > begin ? new long[] {begin, end} : null;
    }

    // ---------- 盘上找文件：和取帧那边同一套规矩 ----------

    /**
     * 盖住那一刻的那个文件。
     *
     * <p>优先环视那一路（一条画面盖住整个车头），没有才看别的几路。</p>
     *
     * The file covering the moment. The surround lane is preferred (one picture covers the whole
     * front); other lanes only when it has nothing.
     */
    static Covering covering(Context context, long wallMs) {
        List<File> candidates = candidates(context, wallMs);
        if (candidates.isEmpty()) {
            return null;
        }
        Covering surround = coveringIn(candidates, wallMs, CameraSlots.KEY_SURROUND);
        return surround != null ? surround : coveringIn(candidates, wallMs, null);
    }

    /**
     * 在新到旧的候选里找第一个真的盖住那一刻的。
     *
     * @param slot 只要这一路的文件；null 时哪一路都行
     */
    private static Covering coveringIn(List<File> candidates, long wallMs, String slot) {
        int opened = 0;
        for (File file : candidates) {
            if (slot != null && !RecordingTimeline.isSlot(file.getName(), slot)) {
                continue;
            }
            if (opened++ >= MAX_CANDIDATES) {
                return null;
            }
            long start = RecordingTimeline.parseStartEpochMs(file.getName());
            long duration = durationMs(file);
            if (!contains(start, duration, wallMs)) {
                continue;  // 时长真读出来才知道盖没盖住：锁定的、刚写过的，修改时间靠不住
            }
            return new Covering(file, start, duration);
        }
        return null;
    }

    /**
     * 目录里可能盖住那一刻的分段，按开录时刻从新到旧。
     *
     * Two directories are read: the configured one and the one written to last (they differ after a
     * drive drops out and writing continues on another).
     */
    private static List<File> candidates(Context context, long wallMs) {
        List<File> out = new ArrayList<>();
        for (File file : segmentsOnStorage(context)) {
            long start = RecordingTimeline.parseStartEpochMs(file.getName());
            if (start <= 0L || start > wallMs) {
                continue;  // 名字读不出时刻、或者这一刻还没开录
            }
            if (file.lastModified() < wallMs) {
                continue;  // 最后写入比那一刻还早：那一刻不在这个文件里
            }
            out.add(file);
        }
        // 开录时刻从新到旧：那一刻更可能落在最近开的那一段里
        // Newest start first: the moment is likelier to fall inside the segment opened most recently
        Collections.sort(out, (a, b) -> Long.compare(RecordingTimeline.parseStartEpochMs(b.getName()),
                RecordingTimeline.parseStartEpochMs(a.getName())));
        return out;
    }

    /**
     * 两个目录里本应用的分段（不按时刻筛）。
     *
     * The app's own segments in both directories, without filtering by moment.
     */
    private static List<File> segmentsOnStorage(Context context) {
        List<File> dirs = new ArrayList<>(2);
        File chosen = null;
        try {
            chosen = StorageHelper.getVideoDir(context);
        } catch (Throwable ignored) {
            // 读设定失败就只看最近写过的那个目录
            // A failed read of the setting just means only the last-written directory is looked at
        }
        File last = StorageHelper.lastRecordingDir();
        if (last != null && (chosen == null || !last.getAbsolutePath().equals(chosen.getAbsolutePath()))) {
            dirs.add(last);
        }
        if (chosen != null) {
            dirs.add(chosen);
        }
        List<File> out = new ArrayList<>();
        for (File dir : dirs) {
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }
                if (!StoragePlan.isOwnClip(file.getName())) {
                    continue;  // U 盘上用户自己的东西不算
                }
                out.add(file);
            }
        }
        return out;
    }

    /**
     * 这一条录像真的有多长（毫秒）。
     *
     * @return 读不出来时 0 —— 读不出来就不算它盖住了那一刻
     */
    private static long durationMs(File file) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value == null) {
                return 0L;
            }
            long duration = Long.parseLong(value.trim());
            return Math.max(0L, duration);
        } catch (Throwable t) {
            // 文件读到一半被删、盘抽走、编码认不出：这一条不能算盖住
            // Deleted mid-read, drive pulled, codec unknown: this one cannot count as covering
            return 0L;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                    // 已经放开过了 / 系统不认：不影响结论
                    // Already released, or the platform refuses: does not change the answer
                }
            }
        }
    }

    // ---------- 不重编码地拷样本 ----------

    /**
     * 把 {@code source} 里 {@code [fromUs, toUs)} 这一段写成 {@code out}。
     *
     * <p>样本原样拷：不解码、不重编。起点退到不晚于 {@code fromUs} 的最近关键帧（H.264 从中间切就是花的），
     * 第一个写出的画面样本定为时间 0；结尾严格在 {@code toUs} —— 窗口外的样本一个都不写。
     * 声音从第一个画面样本起算，之前的不要（那是接缝那一边的）。</p>
     *
     * Copy {@code [fromUs, toUs)} of {@code source} into {@code out}. Samples are copied as they are:
     * nothing decoded, nothing re-encoded. The start falls back to the nearest keyframe at or before
     * {@code fromUs} (H.264 cut mid-GOP is corrupt) and the first written picture sample becomes time
     * zero; the end is strictly {@code toUs} — not one sample beyond it is written. Audio starts with
     * the first picture sample; anything before it belongs to the other side of the boundary.
     *
     * <p>失败时删掉写了一半的文件：留下一个放不出来的 mp4 比没有更糟。</p>
     *
     * <p>On failure the half-written file is deleted: a file that will not play is worse than none.</p>
     */
    private static boolean mux(File source, long fromUs, long toUs, File out) {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        boolean started = false;
        boolean ok = false;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(source.getAbsolutePath());
            int videoTrack = trackOf(extractor, VIDEO_PREFIX);
            if (videoTrack < 0) {
                return false;  // 没有画面轨的文件不切
            }
            int audioTrack = trackOf(extractor, AUDIO_PREFIX);
            MediaFormat videoFormat = extractor.getTrackFormat(videoTrack);

            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outVideo = muxer.addTrack(videoFormat);
            int outAudio = audioTrack >= 0 ? muxer.addTrack(extractor.getTrackFormat(audioTrack)) : -1;
            muxer.start();
            started = true;

            extractor.selectTrack(videoTrack);
            if (audioTrack >= 0) {
                extractor.selectTrack(audioTrack);
            }
            extractor.seekTo(Math.max(0L, fromUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            int capacity = DEFAULT_INPUT_BYTES;
            if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                capacity = Math.max(capacity, videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            ByteBuffer buffer = ByteBuffer.allocate(capacity);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long baseUs = -1L;
            int written = 0;
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;  // 文件到底了
                }
                long ptsUs = extractor.getSampleTime();
                int track = extractor.getSampleTrackIndex();
                if (ptsUs < 0L || track < 0) {
                    extractor.advance();
                    continue;
                }
                if (ptsUs >= toUs) {
                    break;  // 窗口结尾：后面的一个都不要
                }
                if (baseUs < 0L) {
                    if (track != videoTrack) {
                        extractor.advance();
                        continue;  // 第一个画面样本才是 0，它前面到不了
                    }
                    baseUs = ptsUs;
                }
                if (ptsUs < baseUs) {
                    extractor.advance();
                    continue;  // 关键帧之前的声音不要：那是接缝那一边的
                }
                int outTrack = track == videoTrack ? outVideo : outAudio;
                if (outTrack < 0) {
                    extractor.advance();
                    continue;
                }
                // 关键帧位原样带上，别的标志位不往 muxer 里塞；时间从第一个画面样本算起
                // Keep the keyframe bit as it is, hand no other flag to the muxer, and count time from the first picture sample
                info.set(0, size, ptsUs - baseUs, extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC);
                buffer.position(0);
                buffer.limit(size);
                muxer.writeSampleData(outTrack, buffer, info);
                written++;
                extractor.advance();
            }
            ok = written > 0;
        } catch (Throwable t) {
            // 样本读一半、盘抽走、muxer 不认这个格式：切不出来，交给 finally 收干净
            // A sample dies mid-read, the drive is pulled, the muxer refuses the format: no clip, and the finally cleans up
            ok = false;
        } finally {
            if (muxer != null) {
                if (started) {
                    try {
                        muxer.stop();
                    } catch (Throwable ignored) {
                        // 一个样本都没写过时 stop 会抛：那就是没切出来
                        // stop() throws when nothing was written: that is exactly "no clip"
                        ok = false;
                    }
                }
                try {
                    muxer.release();
                } catch (Throwable ignored) {
                    // 同上
                    // Same as above
                    ok = false;
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                    // 已经放开过了 / 系统不认：不影响结论
                    // Already released, or the platform refuses: does not change the answer
                }
            }
            if (!ok) {
                delete(out);
            }
        }
        if (ok && out.length() <= 0L) {
            delete(out);  // 收尾没落盘：0 字节的文件等于没有
            ok = false;   // an empty file after the muxer closed is no clip
        }
        return ok;
    }

    /** 第一条 mime 以这个前缀开头的轨道；没有时 -1。 */
    private static int trackOf(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                return i;
            }
        }
        return -1;
    }

    /** 删掉写了一半的文件；没有文件、删不掉都不算错（下次清理会带走）。 */
    private static void delete(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
}
