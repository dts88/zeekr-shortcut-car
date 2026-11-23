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
 * ntfy 这条出口：一个服务器、一个话题，一张图一句话。不登录，不收任何东西回来。
 *
 * <h3>没准备好就是没准备好</h3>
 *
 * <p>两种「不发」分得清清楚楚：</p>
 *
 * <ul>
 *   <li>{@link Kind#NOT_CONFIGURED} —— 还没有话题，或者服务器不认（只收 https，{@link NtfyRequest#server}）</li>
 *   <li>{@link Kind#NOT_ACKNOWLEDGED} —— 话题有了，但这个话题的风险提示没同意过（{@link NtfyRisk}）</li>
 * </ul>
 *
 * <p>「同意之前不发」是刻意的：宁可一条都不发，也不能让提醒和画面去了车主不知道谁都看得见的地方。</p>
 *
 * <h3>Not ready means not ready</h3>
 *
 * <p>Two ways of not sending, kept apart: no topic or a server that does not parse
 * ({@link Kind#NOT_CONFIGURED}), or a topic whose risk notice was never accepted
 * ({@link Kind#NOT_ACKNOWLEDGED}). Refusing before the notice is accepted is deliberate: nothing goes to
 * a place anyone can read until the owner has been told so.</p>
 */
public final class NtfyChannel implements AlertChannel {

    private final String server;
    private final String topic;
    private final String riskAck;

    /**
     * @param server  存着的服务器（空的是默认的那一个）
     * @param topic   话题名
     * @param riskAck 存着的同意记号（{@link NtfyRisk#ackFor}）
     */
    public NtfyChannel(String server, String topic, String riskAck) {
        this.server = server == null ? "" : server.trim();
        this.topic = topic == null ? "" : topic.trim();
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
        if (!NtfyRequest.looksLikeTopic(topic) || NtfyRequest.server(server).isEmpty()) {
            return Kind.NOT_CONFIGURED;
        }
        if (!NtfyRisk.accepted(topic, riskAck)) {
            return Kind.NOT_ACKNOWLEDGED;
        }
        return Kind.OK;
    }

    @Override
    public Result sendPhoto(String title, String caption, byte[] jpeg) {
        Kind blocked = blockedReason();
        if (blocked != Kind.OK) {
            return Result.fail(blocked, 0, blocked.name());
        }
        return NtfySender.sendPhoto(server, topic, title, caption, jpeg);
    }

    @Override
    public Result sendText(String title, String text) {
        Kind blocked = blockedReason();
        if (blocked != Kind.OK) {
            return Result.fail(blocked, 0, blocked.name());
        }
        return NtfySender.sendText(server, topic, title, text);
    }
}
