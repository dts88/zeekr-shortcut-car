/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.StoragePlan;
import com.kooo.evcam.zeekr.RecordingTimeline;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 录在盘上的画面里，某一刻的那一帧。
 *
 * <h3>为什么要它</h3>
 *
 * <p>警报说的是十几分钟前的那一刻。车主点开时，相机的样本环里早就没有那几秒了（环只留最近几十秒），
 * 但那一刻的画面还在盘上的录像里 —— 从那条录像里取那一帧，取到的是<b>那一刻</b>，
 * 不是此刻重新拍一张。取不到就是没有（{@code null}），不拿别的时刻的画面凑。</p>
 *
 * <h3>用哪条录像、怎么把时刻对到帧</h3>
 *
 * <p>目录和文件名的规矩都不另起一套：目录是 {@link StorageHelper#getVideoDir}（没插 U 盘时降级的那个也看，
 * {@link StorageHelper#lastRecordingDir}），文件名只认本应用的分段（{@link StoragePlan#isOwnClip}），
 * 开录时刻从名字里读（{@link RecordingTimeline#parseStartEpochMs}，形如 {@code 20260828_221530_front.mp4}），
 * 哪几路是同一条录制也照回放那边的认法（{@link RecordingTimeline#isSlot}）。</p>
 *
 * <p>找一个文件要盖住那一刻，先按文件名和修改时间筛：开录时刻不晚于那一刻、最后写入不早于那一刻。
 * 这一步只看目录，不开文件；锁定的文件、最近操过的文件修改时间靠不住，所以真的时长再用
 * {@link MediaMetadataRetriever} 读一次，读到了才算盖住。偏移是 {@code 那一刻 - 开录时刻}，
 * 取帧用 {@code OPTION_CLOSEST}：分段录的是流水画面，那一刻多半对不上帧的边界。</p>
 *
 * <p>纯查询，不改录像、不加锁、不重新录。</p>
 *
 * The frame at a given moment, out of the recordings on storage.
 *
 * <h3>Why it exists</h3>
 *
 * <p>An alert names a moment a dozen minutes back. By the time the owner taps it the camera's sample
 * ring no longer holds those seconds (the ring keeps only the last few dozen), but that moment's
 * picture is still on storage — take that frame from the recording that covers it and it is
 * <b>that</b> moment, not a fresh picture of this instant. When nothing covers it the answer is null;
 * another moment's picture is never offered instead.</p>
 *
 * <h3>Which recording, and how the moment maps to a frame</h3>
 *
 * <p>Directory and file-name rules are not invented here: the directory is
 * {@link StorageHelper#getVideoDir} (plus the fallback lane's {@link StorageHelper#lastRecordingDir}),
 * only this app's segments count ({@link StoragePlan#isOwnClip}), the start time comes out of the name
 * ({@link RecordingTimeline#parseStartEpochMs}, e.g. {@code 20260828_221530_front.mp4}), and which
 * files belong to the same lane follows the playback side ({@link RecordingTimeline#isSlot}).</p>
 *
 * <p>Coverage is filtered cheaply first — a name starting no later than the moment, a last write no
 * earlier than it. That step only reads the directory. Locked or recently touched files have
 * untrustworthy modification times, so the real duration is read with a
 * {@link MediaMetadataRetriever} and only then counted. The offset is {@code moment - start}, and the
 * frame comes from {@code OPTION_CLOSEST}: a segment is continuous footage, so the moment rarely sits
 * on a frame boundary.</p>
 *
 * <p>A read-only query: no recording touched, no lock taken, nothing recorded.</p>
 */
public final class RecordedFrame {

    /** 最多打开几个候选文件读时长；再往前就是换盘、空档，不值得把目录扫到底。 */
    private static final int MAX_CANDIDATES = 6;

    private RecordedFrame() {
    }

    /**
     * 那一刻的那一帧；那一刻没有录像盖住，回 {@code null}。
     *
     * <p>优先环视那一路（一条画面盖住整个车头），没有才看别的几路。</p>
     *
     * The frame at {@code wallMs}, or {@code null} when no recording covers that moment. The surround
     * lane is preferred (one picture covers the whole front); other lanes only when it has nothing.
     *
     * @param wallMs 墙上时间（毫秒），警报那一刻
     */
    public static Bitmap at(Context context, long wallMs) {
        if (context == null || wallMs <= 0L) {
            return null;
        }
        try {
            List<File> segments = segmentsCandidates(context, wallMs);
            if (segments.isEmpty()) {
                return null;
            }
            Bitmap surround = firstCovered(segments, wallMs, CameraSlots.KEY_SURROUND);
            if (surround != null) {
                return surround;
            }
            return firstCovered(segments, wallMs, null);
        } catch (Throwable t) {
            // 存储掉线（U 盘抽走）、读盘出错：这一刻就是没有，不往上抛
            // Storage gone (USB pulled) or a read error: this moment is simply not available, never thrown on
            return null;
        }
    }

    /**
     * 目录里可能盖住那一刻的分段，按开录时刻从新到旧。
     *
     * <p>两个目录都看：设定的那个，和最近一次真的写进去的那个（掉盘后接到另一个盘的时候两者不同）。</p>
     *
     * The segments in the directories that could cover the moment, newest start first. Both
     * directories are read: the configured one, and the one actually written to last (they differ
     * after a drive drops out and writing continues on another).
     */
    private static List<File> segmentsCandidates(Context context, long wallMs) {
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
                String name = file.getName();
                if (!StoragePlan.isOwnClip(name)) {
                    continue;  // U 盘上用户自己的东西不算
                }
                long start = RecordingTimeline.parseStartEpochMs(name);
                if (start <= 0L || start > wallMs) {
                    continue;  // 名字读不出时刻、或者这一刻还没开录
                }
                if (file.lastModified() < wallMs) {
                    continue;  // 最后写入比那一刻还早：那一刻不在这个文件里
                }
                out.add(file);
            }
        }
        // 开录时刻从新到旧：那一刻更可能落在最近开的那一段里
        // Newest start first: the moment is likelier to fall inside the segment opened most recently
        java.util.Collections.sort(out, (a, b) -> Long.compare(RecordingTimeline.parseStartEpochMs(b.getName()),
                RecordingTimeline.parseStartEpochMs(a.getName())));
        return out;
    }

    /**
     * 在新到旧的候选里找第一个真的盖住那一刻的，取那一帧。
     *
     * @param slot 只要这一路的文件；null 时哪一路都行
     * @return 那一帧；没有盖住的、读不出来的，null
     */
    private static Bitmap firstCovered(List<File> segments, long wallMs, String slot) {
        int opened = 0;
        for (File file : segments) {
            if (slot != null && !RecordingTimeline.isSlot(file.getName(), slot)) {
                continue;
            }
            if (opened++ >= MAX_CANDIDATES) {
                return null;
            }
            Bitmap frame = frameFrom(file, wallMs);
            if (frame != null) {
                return frame;
            }
        }
        return null;
    }

    /**
     * 从这一个文件里取那一刻的帧；这个文件没盖住那一刻、或者读不出来，null。
     *
     * <p>时长和帧用同一个 extractor 读；用完一定放掉（{@code finally}）。</p>
     *
     * The frame at that moment from this one file, or null when the file does not cover the moment or
     * cannot be read. Duration and frame come from the same retriever, always released in a
     * {@code finally}.
     */
    private static Bitmap frameFrom(File file, long wallMs) {
        long start = RecordingTimeline.parseStartEpochMs(file.getName());
        if (start <= 0L || wallMs < start) {
            return null;
        }
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(file.getAbsolutePath());
            long durationMs = 0L;
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value != null) {
                durationMs = Long.parseLong(value.trim());
            }
            if (durationMs <= 0L || wallMs >= start + durationMs) {
                return null;  // 那一刻不在这个文件里
            }
            long offsetUs = Math.max(0L, wallMs - start) * 1000L;
            return retriever.getFrameAtTime(offsetUs, MediaMetadataRetriever.OPTION_CLOSEST);
        } catch (Throwable t) {
            // 文件读到一半被删、盘抽走、编码认不出：这一个没有
            // Deleted mid-read, drive pulled, codec unknown: this one has nothing
            return null;
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
}
