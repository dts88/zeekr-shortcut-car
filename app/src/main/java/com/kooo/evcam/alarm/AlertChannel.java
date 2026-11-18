/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import com.kooo.evcam.alarm.ntfy.NtfyChannel;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;
import com.kooo.evcam.alarm.policy.AlertPolicy;

/**
 * 一条提醒的出口：手机订阅的每一种服务都长这个样子。
 *
 * <p>{@link SentryAlerts} 决定「该不该发」；发到哪儿由这里决定。现在只有 ntfy 一个实现（{@link NtfyChannel}），
 * 以后的 Telegram 照这个样子再写一个。实现都薄：拼参数、归类失败原因，判断一条都不许写进来 ——
 * 判断在 {@link AlertPolicy}、{@link NtfyRisk}。</p>
 *
 * <p>The exit for one alert: every phone subscription service looks like this. {@link SentryAlerts}
 * decides whether to send; this decides where. ntfy is the only implementation for now
 * ({@link NtfyChannel}); Telegram will be another one of the same shape. Implementations stay thin: they
 * build the call and sort the failure. No policy lives here — that is {@link AlertPolicy} and
 * {@link NtfyRisk}.</p>
 */
public interface AlertChannel {

    /** 失败的种类。界面据此说人话，不把 HTTP 码以外的原文念给车主听。What went wrong, so the UI can say it plainly. */
    enum Kind {
        /** 发出去了。It went out. */
        OK,
        /** 还没配好：缺话题、服务器不认。Not configured: no topic, or a server that does not parse. */
        NOT_CONFIGURED,
        /** 这个话题的风险提示还没同意过（{@link NtfyRisk}）。The risk notice was not accepted for this topic. */
        NOT_ACKNOWLEDGED,
        /** 401 / 403：服务器不认。The server refused. */
        REJECTED,
        /** 404：服务器或话题找不到。Server or topic not found. */
        NOT_FOUND,
        /** 413：图太大。The picture is too big. */
        TOO_LARGE,
        /** 429：发得太勤。Too frequent. */
        RATE_LIMITED,
        /** 别的 HTTP 码：服务器回了话，但没收下。Any other HTTP code: the server answered but did not take it. */
        REFUSED,
        /** 连上了、等回话超时。Connected, then timed out waiting. */
        TIMEOUT,
        /** 连不上。Could not connect. */
        NETWORK
    }

    /** 一次发送的结果。{@link #detail} 只进日志，界面照 {@link #kind} 说。The result of one send. */
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
    }

    /** 黑匣子里写哪个名字。The name written into the blackbox. */
    String name();

    /** 现在这个出口配好了没有。Whether this exit is configured enough to send. */
    boolean ready();

    /** 没配好的时候，缺的是什么。Why it is not ready. */
    Kind blockedReason();

    /** 发一张图：标题、说明。Send one picture with a title and a caption. */
    Result sendPhoto(String title, String caption, byte[] jpeg);

    /** 没有图的时候只发一句话。Send one line when there is no picture. */
    Result sendText(String title, String text);
}
