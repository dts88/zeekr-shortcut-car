/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import com.kooo.evcam.alarm.ntfy.NtfyChannel;
import com.kooo.evcam.alarm.ntfy.NtfyGate;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;
import com.kooo.evcam.alarm.policy.AlertPolicy;

/**
 * 一条警报的出口：一个话题、一个机器人，都长这个样子。
 *
 * <p>{@link SentryAlerts} 决定「该不该发」；发到哪儿由这里决定。两个实现各管一条路
 * （{@link TelegramChannel}、{@link NtfyChannel}），都薄：拼参数、翻译失败原因，
 * 判断一条都不许写进来 —— 判断在 {@link AlertPolicy}、{@link NtfyGate}、{@link NtfyRisk}。</p>
 *
 * <p>The exit for one alert. {@link SentryAlerts} decides whether to send; this decides where. Two
 * implementations, one per route ({@link TelegramChannel}, {@link NtfyChannel}), both thin: they build
 * the call and translate failures. No policy lives here — that is {@link AlertPolicy},
 * {@link NtfyGate} and {@link NtfyRisk}.</p>
 */
public interface AlertChannel {

    /** 失败的种类。界面据此说人话，不把 HTTP 码念给车主听。What went wrong, so the UI can say it plainly. */
    enum Kind {
        /** 发出去了。It went out. */
        OK,
        /** 还没配好：缺话题、缺令牌、缺会话号。Not configured yet. */
        NOT_CONFIGURED,
        /** 免费话题的风险还没被接受（{@link NtfyRisk}）。The free-topic risk has not been accepted. */
        NOT_ACKNOWLEDGED,
        /** 401 / 403：服务器不认。The server refused. */
        REJECTED,
        /** 404：服务器或话题找不到。Server or topic not found. */
        NOT_FOUND,
        /** 413：图太大，这一档发不了。The picture is too big for this tier. */
        TOO_LARGE,
        /** 429：发得太勤。Too frequent. */
        RATE_LIMITED,
        /** 联不上、超时、别的码。Could not connect, timed out, or anything else. */
        NETWORK
    }

    /** 一次发送的结果。{@link #detail} 已经擦过令牌，可以直接显示。 */
    final class Result {
        public final boolean ok;
        public final Kind kind;
        public final int httpCode;
        public final String detail;

        private Result(boolean ok, Kind kind, int httpCode, String detail) {
            this.ok = ok;
            this.kind = kind == null ? Kind.NETWORK : kind;
            this.httpCode = httpCode;
            this.detail = detail == null ? "" : detail;
        }

        public static Result ok() {
            return new Result(true, Kind.OK, 200, "");
        }

        public static Result fail(Kind kind, int httpCode, String detail) {
            return new Result(false, kind, httpCode, detail);
        }

        /** 界面直接显示的那一句（已擦过令牌）。The one line the UI shows, token already redacted. */
        public String display() {
            return ok ? "" : detail;
        }
    }

    /** 黑匣子里写哪个名字。The name written into the blackbox. */
    String name();

    /** 现在这个出口配好了没有。Whether this exit is configured enough to send. */
    boolean ready();

    /** 没配好的时候，缺的是什么。Why it is not ready. */
    Kind blockedReason();

    /** 这条路能不能发视频片段（只有 Telegram 能）。Whether this route can carry a video clip (Telegram only). */
    boolean supportsClips();

    /** 发一张图，附一句说明。Send one picture with a caption. */
    Result sendPhoto(byte[] jpeg, String caption);

    /** 没有图的时候只发一句话。Send one line when there is no picture. */
    Result sendText(String text);
}
