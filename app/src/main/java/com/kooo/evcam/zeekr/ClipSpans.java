/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 不打开文件就知道时长的那些录像分段（项目所有者 2026-11-14：「可否通过文件名判断是否可以分入同一组？这样就避免读时长了」）。
 *
 * <h3>凭什么</h3>
 *
 * <p>文件名里只有开录时刻。结束时刻文件系统替我们记着：文件的修改时间，就是录完封好的那一下。连着录的时候，
 * 这一段封好和下一段开录是前后脚 —— 所以同一路里，这一段的修改时间和下一段的开录时刻差不到 {@link #TOLERANCE_MS}，
 * 这一段就是一直录到了下一段开头，时长算作两段开录时刻的差，不用打开它。</p>
 *
 * <p>实车量过（2026-11-14，4.4.0 的诊断，781 个文件）：拿「修改时间 − 开录时刻」估的时长和打开文件读出来的，
 * 差的中位数 0.37 秒；777 个里差 3 秒以上的只有 1 个，估不出的 1 个。全部打开读一遍要 37.7 秒。</p>
 *
 * <h3>对不上的照旧打开读</h3>
 *
 * <p>后面没有紧挨着的下一段（一条录制的最后一段）、修改时间和下一段的开头差得多（中间停过；文件后来被动过；
 * 盘上的时间不准）、文件系统没给修改时间、两段隔得比一个分段还长 —— 这些这里不给时长，调用方照旧打开文件读
 * （{@link ClipDurations}）。所以分条的界线还是照真的时长定的：界线只可能出在「对不上」的地方。</p>
 *
 * <h3>和读出来的差在哪</h3>
 *
 * <p>算出来的是「到下一段开头」，比文件里的画面长一个换段的空（零点几秒）。进度条上这一段就占到下一段开头为止：
 * 放到这一段的画面完了，接着放下一段，位置往前跳这零点几秒。别的几路照真实时刻跟着环视走，不受影响。</p>
 */
final class ClipSpans {

    /** 修改时间和下一段的开录时刻差不到这么多，就算这一段录到了下一段开头。 */
    static final long TOLERANCE_MS = 2_000L;
    /** 两段开录时刻隔得比这还长就不算紧挨着：最长的分段是 10 分钟，宽一点。 */
    static final long LIMIT_MS = 15 * 60_000L;

    /** 一路里的一个文件：名字里的开录时刻、文件系统记的修改时间（没有是 0）。 */
    static final class Timed {
        final String path;
        final long startEpochMs;
        final long modifiedMs;

        Timed(String path, long startEpochMs, long modifiedMs) {
            this.path = path;
            this.startEpochMs = startEpochMs;
            this.modifiedMs = modifiedMs;
        }
    }

    private ClipSpans() {
    }

    /**
     * 这一路里不用打开文件就知道时长的那些：路径 → 时长（毫秒）。不在里面的要打开读。
     *
     * @param lane 同一路的全部文件（次序随便）
     */
    static Map<String, Long> known(List<Timed> lane) {
        List<Timed> sorted = new ArrayList<>(lane);
        Collections.sort(sorted, (a, b) -> Long.compare(a.startEpochMs, b.startEpochMs));
        Map<String, Long> out = new HashMap<>();
        for (int i = 0; i + 1 < sorted.size(); i++) {
            Timed clip = sorted.get(i);
            Timed next = sorted.get(i + 1);
            long span = next.startEpochMs - clip.startEpochMs;
            if (span <= 0 || span > LIMIT_MS || clip.modifiedMs <= 0) {
                continue;
            }
            if (Math.abs(next.startEpochMs - clip.modifiedMs) > TOLERANCE_MS) {
                continue;
            }
            out.put(clip.path, span);
        }
        return out;
    }
}
