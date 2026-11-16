/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import com.kooo.evcam.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 「检查链路」那几行字怎么凑出来 —— 纯函数，只有事实，没有网络，没有 Android 调用。
 *
 * <h3>为什么单独一个类</h3>
 *
 * <p>探一次链路要发请求，请求不能进单测。但「哪几条事实凑成哪一句」可以钉死：话题认不认得出、
 * 免费方案提醒接受过没有、付费令牌设没设、服务器回没回话、回话算不算成功。这些都只是输入，
 * 输出是一行一行的字符串 id 加参数，界面那边再翻成人话（{@code SentryAlerts.probe}）。</p>
 *
 * <p>换一条出口就换一组事实，所以 Telegram 那份和 ntfy 那份分开写：Telegram 只多一句
 * 「现在能不能连上」，ntfy 多四句 —— 话题在不在、提醒接受没接受、令牌有没有、服务器答不答。</p>
 *
 * <h3>Why a separate class</h3>
 *
 * <p>Probing the chain sends requests, and requests cannot go into a unit test. Which facts turn into
 * which sentence can be pinned, though: whether the topic parses, whether the free-plan warning was
 * accepted, whether a paid token is set, whether the server answered, and whether that answer counts.
 * All of that is input; the output is one string id plus arguments per line, and the UI turns it into
 * words ({@code SentryAlerts.probe}).</p>
 *
 * <p>A different exit means a different set of facts, so the Telegram facts and the ntfy facts are
 * written apart: Telegram adds only "can it connect now", ntfy adds four lines — does the topic exist,
 * was the warning accepted, is a token set, does the server answer.</p>
 */
public final class ProbeReport {

    /** 一行：字符串 id 加参数，界面那边再翻成人话。One line: a string id and its arguments; the UI renders it. */
    public static final class Line {
        public final int resId;
        public final Object[] args;

        Line(int resId, Object... args) {
            this.resId = resId;
            this.args = args == null ? new Object[0] : args;
        }
    }

    /** 话题末几位保留：认得出是哪一条，又拼不出整条。How many trailing characters of a topic survive. */
    private static final int TAIL_LENGTH = 4;

    private ProbeReport() {
    }

    /**
     * ntfy 这边不发请求就能知道的事实，三行：发给哪、提醒接受没接受、令牌有没有。
     *
     * <p>话题不认得出就换成一句话说明，而不是显示「末四位 」这样的空话。</p>
     *
     * The ntfy facts that need no request, three lines: where it goes, whether the warning was
     * accepted, whether a token is set. An unparseable topic becomes one sentence of its own rather
     * than "ends in " with nothing after it.
     */
    public static List<Line> ntfyFacts(String server, String topic, String riskAck, String token) {
        List<Line> lines = new ArrayList<>(3);
        String value = topic == null ? "" : topic.trim();
        if (NtfyRequest.looksLikeTopic(value)) {
            lines.add(new Line(R.string.set_alerts_probe_ntfy_target, host(server), tail(value)));
        } else {
            lines.add(new Line(R.string.set_alerts_probe_ntfy_no_topic));
        }
        lines.add(NtfyRisk.accepted(value, riskAck)
                ? new Line(R.string.set_alerts_probe_ntfy_risk_ok)
                : new Line(R.string.set_alerts_probe_ntfy_risk_missing));
        lines.add(token != null && !token.trim().isEmpty()
                ? new Line(R.string.set_alerts_probe_ntfy_token_set)
                : new Line(R.string.set_alerts_probe_ntfy_token_none));
        return lines;
    }

    /**
     * ntfy 那一次活探的结果，一行。
     *
     * <p>三件事分得清清楚楚，车主才不会为了「地址写错」去换令牌：服务器没答（{@code NETWORK}）、
     * 答了（{@code OK}）、答了但这一问没过（令牌不认、地址 404、发太勤）。{@code detail} 过
     * {@link NtfyRequest#redact} 才进字里 —— 令牌不进任何一句。</p>
     *
     * The verdict of that one live ntfy attempt, as one line. Three cases kept apart so nobody changes a
     * token over a mistyped address: no answer ({@code NETWORK}), answered ({@code OK}), answered but
     * this question was refused (token, 404, rate limit). {@code detail} goes through
     * {@link NtfyRequest#redact} first — the token never enters any sentence.
     */
    public static Line ntfyReach(String server, String token, AlertChannel.Kind kind, long ms,
                                 String detail) {
        String answer = NtfyRequest.redact(detail == null ? "" : detail, token);
        if (kind == AlertChannel.Kind.OK) {
            return new Line(R.string.set_alerts_probe_ntfy_reach_ok, host(server), String.valueOf(ms));
        }
        if (kind == null || kind == AlertChannel.Kind.NETWORK) {
            return new Line(R.string.set_alerts_probe_ntfy_reach_fail, host(server), answer);
        }
        return new Line(R.string.set_alerts_probe_ntfy_reach_reply, host(server), answer);
    }

    /**
     * Telegram 那一次活探的结果，一行。形状和以前一样，只是挪到这里来，好和 ntfy 那条并排看。
     *
     * The verdict of that one live Telegram attempt, as one line. Same shape as before; it moved here so
     * it can be read next to the ntfy one.
     */
    public static Line telegramReach(boolean reached, String botName, long ms, String error) {
        if (!reached) {
            return new Line(R.string.set_alerts_probe_reach_fail, error == null ? "" : error);
        }
        return new Line(R.string.set_alerts_probe_reach_ok, String.valueOf(ms), botName == null ? "" : botName);
    }

    /**
     * 只留主机名：协议和路径对车主没意义，地址写错时主机名才是认得出的那一段。
     *
     * Only the host: the scheme and any path say nothing to the owner, and the host is the part they
     * recognise when the address is wrong.
     */
    public static String host(String server) {
        String value = NtfyRequest.server(server);
        int scheme = value.indexOf("://");
        String rest = scheme < 0 ? value : value.substring(scheme + 3);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    /**
     * 话题末四位。和 {@code maskTail} 同一个规矩：够认出这一条，不够别人拿去用。
     *
     * The last four characters of a topic, the same rule as {@code maskTail}: enough to recognise it,
     * not enough to use it.
     */
    public static String tail(String topic) {
        String value = topic == null ? "" : topic.trim();
        return value.length() <= TAIL_LENGTH ? value : value.substring(value.length() - TAIL_LENGTH);
    }
}
