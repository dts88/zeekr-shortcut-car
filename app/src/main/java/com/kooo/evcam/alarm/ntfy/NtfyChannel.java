/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.ntfy;

import com.kooo.evcam.alarm.AlertChannel;

/**
 * ntfy 这条出口：一个话题，可选的令牌，一张图一句话。
 *
 * <h3>没准备好就是没准备好</h3>
 *
 * <p>三种「不发」分得清清楚楚，界面才说得出人话：</p>
 *
 * <ul>
 *   <li>{@link Kind#NOT_CONFIGURED} —— 还没有话题（没配过，或者被清掉了）</li>
 *   <li>{@link Kind#NOT_ACKNOWLEDGED} —— 话题有了，但免费方案的提醒没被接受（{@link NtfyRisk}）</li>
 *   <li>{@link Kind#TOO_LARGE} —— 图超过这一档的上限（免费 2 MiB，量过）</li>
 * </ul>
 *
 * <p>「接受提醒之前不发」是刻意的：宁可一条都不发、并且说一声，也不能让车主以为自己被盯着。</p>
 *
 * <h3>Not ready means not ready</h3>
 *
 * <p>Three ways of not sending, kept apart so the UI can say which one: no topic
 * ({@link Kind#NOT_CONFIGURED}), topic but no accepted warning ({@link Kind#NOT_ACKNOWLEDGED}), or a
 * picture over this tier's limit ({@link Kind#TOO_LARGE}; 2 MiB free, measured). Refusing before the
 * warning is accepted is deliberate: sending nothing and saying so beats an owner believing the car is
 * watched when it is not.</p>
 */
public final class NtfyChannel implements AlertChannel {

    private final String server;
    private final String topic;
    private final String token;
    private final boolean paid;
    private final String riskAck;

    public NtfyChannel(String server, String topic, String token, boolean paid, String riskAck) {
        this.server = NtfyRequest.server(server);
        this.topic = topic == null ? "" : topic.trim();
        this.token = token == null ? "" : token.trim();
        this.paid = paid;
        this.riskAck = riskAck == null ? "" : riskAck.trim();
    }

    @Override
    public String name() {
        return "ntfy";
    }

    @Override
    public boolean ready() {
        return blockedReason() == Kind.OK;
    }

    @Override
    public Kind blockedReason() {
        if (!NtfyRequest.looksLikeTopic(topic)) {
            return Kind.NOT_CONFIGURED;
        }
        if (!NtfyRisk.accepted(topic, riskAck)) {
            return Kind.NOT_ACKNOWLEDGED;
        }
        return Kind.OK;
    }

    @Override
    public boolean supportsClips() {
        // 片段是 mp4，免费档 2 MiB 装不下，付费档也不该悄悄把「图 + 视频」变成两个上限。
        // Clips are mp4: 2 MiB on the free tier will not hold one, and a paid tier should not silently
        // turn "a picture" into two different limits either. Telegram keeps this feature.
        return false;
    }

    @Override
    public Result sendPhoto(byte[] jpeg, String caption) {
        Kind blocked = blockedReason();
        if (blocked != Kind.OK) {
            return Result.fail(blocked, 0, blocked == Kind.NOT_ACKNOWLEDGED
                    ? "free-plan warning not accepted" : "no topic");
        }
        return NtfySender.sendPhoto(server, topic, token, jpeg, caption, "", paid);
    }

    @Override
    public Result sendText(String text) {
        Kind blocked = blockedReason();
        if (blocked != Kind.OK) {
            return Result.fail(blocked, 0, blocked == Kind.NOT_ACKNOWLEDGED
                    ? "free-plan warning not accepted" : "no topic");
        }
        return NtfySender.sendText(server, topic, token, text, "");
    }
}
