/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import java.util.ArrayList;
import java.util.List;

/**
 * 最近几条警报的时刻，留着给「回那条警报」用：图（HD 那张）、当时的说明、那一刻的墙钟毫秒。
 *
 * <h3>为什么要有这个缓存</h3>
 *
 * <p>车主回一条警报时，回的字里只有时间（{@code 18:16}），没有别的。要回答那个时刻，就得有那个
 * 时刻的图和录像 —— 而图和录像在发出去的时候才有。所以发出去的每一条都往这里放一份，回消息时
 * 按时间取回来。</p>
 *
 * <h3>为什么只留四条，为什么认两秒</h3>
 *
 * <p>四条够覆盖「刚发的几条」，再多的图在内存里放着不值。认两秒是因为回的字里的时间只到分钟，
 * 而警报可能发生在那一分钟的任意一刻 —— 两秒是写进字和真发生之间允许的差。</p>
 *
 * <h3>为什么全加锁</h3>
 *
 * <p>放进来的在发警报那条路（主线程），取的在轮询线程。一个 {@code ArrayList} 两头同时动会坏。</p>
 *
 * The last few alert moments kept for "reply to that alert": the picture (the HD one), the
 * caption it went out with, and the wall-clock millisecond of the moment.
 *
 * <h3>Why this cache exists</h3>
 *
 * <p>When the owner replies to an alert, the reply carries only a time ({@code 18:16}) and
 * nothing else. Answering about that moment needs that moment's picture and clip -- and those
 * only exist at send time. So every alert sent puts a copy here, and a reply looks the moment
 * back up by time.</p>
 *
 * <h3>Why four entries, why two seconds</h3>
 *
 * <p>Four covers "the last few sent"; more bitmaps than that are not worth holding in memory.
 * The two seconds are because a reply's time is minute-precision while the alert may have fired
 * at any point inside that minute -- two seconds is the slack allowed between the text and the
 * actual moment.</p>
 *
 * <h3>Why everything is locked</h3>
 *
 * <p>Writers run on the alert path (main thread), readers on the polling thread. One
 * {@code ArrayList} touched from both ends breaks.</p>
 */
public final class AlertAnchor {

    /** 留住几条。 */
    private static final int CAPACITY = 4;

    /** 按时间找回来时允许的差。 */
    private static final long SLACK_MS = 2_000L;

    /**
     * 一条：那一刻的墙钟毫秒、那一刻的 HD 图（JPEG，可能是 {@code null}）、当时的说明。
     *
     * <p>图是照原样存的，不复制也不回收 —— 调用方给什么就是什么，用的人自己别去 recycle。</p>
     *
     * One entry: the moment's wall-clock millisecond, its HD picture (JPEG, may be
     * {@code null}) and the caption it carried.
     *
     * <p>The picture is kept as handed over, neither copied nor recycled -- the caller decides;
     * whoever reads it must not recycle it.</p>
     */
    public static final class Entry {
        public final long wallMs;
        public final byte[] hd;
        public final String caption;

        Entry(long wallMs, byte[] hd, String caption) {
            this.wallMs = wallMs;
            this.hd = hd;
            this.caption = caption;
        }
    }

    /** 新的在前。 */
    private final List<Entry> entries = new ArrayList<>(CAPACITY);

    /**
     * 记下刚发出去的那一条，满了丢最旧的。
     *
     * Record the one just sent, dropping the oldest once full.
     */
    public synchronized void add(long wallMs, byte[] hdJpeg, String caption) {
        entries.add(0, new Entry(wallMs, hdJpeg, caption));
        while (entries.size() > CAPACITY) {
            entries.remove(entries.size() - 1);
        }
    }

    /**
     * 最新的一条；一条都没有就是 {@code null}。
     *
     * The newest one, or {@code null} when there is none.
     */
    public synchronized Entry newest() {
        return entries.isEmpty() ? null : entries.get(0);
    }

    /**
     * 按时间找：先找完全一样的，没有就找差在两秒以内最近的那条；都没有就是 {@code null}。
     *
     * Look up by time: an exact match first, then the nearest within two seconds; {@code null}
     * when neither exists.
     */
    public synchronized Entry at(long wallMs) {
        Entry nearest = null;
        long nearestGap = Long.MAX_VALUE;
        for (Entry entry : entries) {
            long gap = Math.abs(entry.wallMs - wallMs);
            if (gap == 0) {
                return entry;
            }
            if (gap <= SLACK_MS && gap < nearestGap) {
                nearest = entry;
                nearestGap = gap;
            }
        }
        return nearest;
    }

    /**
     * 全丢掉。换了车、换了会话、或者车主说「不用管那条了」都会走到这里。
     *
     * Drop everything. A different car, a different session, or the owner saying "never mind
     * that one" all land here.
     */
    public synchronized void clear() {
        entries.clear();
    }
}
