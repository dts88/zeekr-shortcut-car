/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

/**
 * 车主在 Telegram 里要照片的频率上限 —— 纯计数，便于单测。
 *
 * <p>和警报那套 {@link AlertPolicy} 分开放：警报是车自己发起的（要停着、要布防、一次事件一条），
 * 这里是<b>人</b>在要（车停着就行，布防不布防无所谓 —— 坐在车里想看一张也该给）。</p>
 *
 * <p>上限的理由不是省钱，是别把车机当图床：按着不放、脚本写错、Telegram 那边重发，
 * 都会变成一串连拍。30 秒一张、每小时 6 张，够一个人看，不够把电池拍空。</p>
 */
public final class RequestPolicy {

    /** 两张之间至少隔这么久。 */
    public static final long COOLDOWN_MS = 30_000L;

    /** 一小时里最多几张。 */
    public static final int MAX_PER_HOUR = 6;

    private long lastSentAt;
    private long hourStart;
    private int sentThisHour;

    /** 现在能不能拍。 */
    public boolean allows(long nowMs) {
        rotateHour(nowMs);
        if (sentThisHour >= MAX_PER_HOUR) {
            return false;
        }
        return lastSentAt == 0 || nowMs - lastSentAt >= COOLDOWN_MS;
    }

    /** 拍了一张。 */
    public void noteSent(long nowMs) {
        rotateHour(nowMs);
        lastSentAt = nowMs;
        sentThisHour++;
    }

    /** 还要等多久；不在冷却里是 0。 */
    public long cooldownLeftMs(long nowMs) {
        if (lastSentAt == 0) {
            return 0;
        }
        return Math.max(0, COOLDOWN_MS - (nowMs - lastSentAt));
    }

    /** 这一小时还剩几张。 */
    public int leftThisHour(long nowMs) {
        rotateHour(nowMs);
        return Math.max(0, MAX_PER_HOUR - sentThisHour);
    }

    private void rotateHour(long nowMs) {
        if (hourStart == 0 || nowMs - hourStart >= 3_600_000L) {
            hourStart = nowMs;
            sentThisHour = 0;
        }
    }
}
