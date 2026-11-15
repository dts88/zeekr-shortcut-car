/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 和 ntfy 说话的那一头：发一句话、发一张图、问账号、问话题保护没保护上。
 *
 * <h3>三条规矩</h3>
 *
 * <ol>
 *   <li><b>令牌不进日志。</b>要写下来的字符串先过 {@link NtfyRequest#redact(String, String)}；
 *       本类自己不打印 URL，也不把响应体原样抛给界面。</li>
 *   <li><b>只在后台线程调。</b>全是阻塞的网络调用，主线程调会卡住录像的界面；
 *       调用方起线程（{@link SentryAlerts} 那条 {@code sender}）。</li>
 *   <li><b>失败要能说清楚。</b>401 / 403 是话题或令牌不认，404 是地址写错，413 是图太大，
 *       429 是发太勤，其余归成网络问题 —— 每一种都在 {@link AlertChannel.Kind} 里有个名字，
 *       翻译成人话是界面的事。</li>
 * </ol>
 *
 * <h3>Three rules</h3>
 *
 * <ol>
 *   <li><b>Token never in a log.</b> Anything written down goes through
 *       {@link NtfyRequest#redact(String, String)}; this class prints no URLs and never hands a raw
 *       response body to the UI.</li>
 *   <li><b>Call only on a background thread.</b> Every call blocks; the caller starts the thread
 *       ({@link SentryAlerts}' {@code sender}).</li>
 *   <li><b>Failures must be explainable.</b> 401 / 403 means the topic or the token was refused, 404 a
 *       wrong address, 413 a picture too large, 429 too frequent, anything else a network problem —
 *       each has a name in {@link AlertChannel.Kind}, and turning that into a sentence is the UI's
 *       job.</li>
 * </ol>
 */
public final class NtfySender {

    private static final String TAG = "NtfySender";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private NtfySender() {
    }

    /**
     * 发一句话。
     *
     * <p>文字走请求体：真换行在这里是合法的，而警报的说明就是三行。</p>
     *
     * Send one line of text. The body is the text, where real newlines are legal — and an alert caption
     * is three lines.
     */
    public static AlertChannel.Result sendText(String server, String topic, String token,
                                               String text, String title) {
        String url = NtfyRequest.textUrl(server, topic);
        byte[] body = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        return publish("POST", url, body, token, title);
    }

    /**
     * 发一张图：图是请求体，说明在查询串里。
     *
     * <p>超过这一档的上限就地拒绝，不发这一趟 —— 413 已经量过（免费档 2 MiB，多一个字节就拒）。</p>
     *
     * Send one picture: the body is the picture, the caption rides in the query string. Oversized
     * pictures are refused before the request — the 413 boundary is measured (2 MiB on the free tier,
     * one byte more is refused).
     */
    public static AlertChannel.Result sendPhoto(String server, String topic, String token,
                                                byte[] jpeg, String caption, String title,
                                                boolean paid) {
        if (jpeg == null || jpeg.length == 0) {
            return AlertChannel.Result.fail(AlertChannel.Kind.NOT_CONFIGURED, 0, "no picture");
        }
        int limit = NtfyRequest.maxPhotoBytes(paid);
        if (jpeg.length > limit) {
            return AlertChannel.Result.fail(AlertChannel.Kind.TOO_LARGE, 413,
                    "picture " + jpeg.length + " > " + limit);
        }
        String name = "alert_" + System.currentTimeMillis() + ".jpg";
        String url = NtfyRequest.photoUrl(server, topic, caption, title, name);
        return publish("PUT", url, jpeg, token, title);
    }

    /**
     * 设配好之后先发一条普通的：服务器收了，这条路就通了（手机收没收到是另一件事，
     * 那一步要车主回一个四位数字，见 {@code NtfySetupDialog}）。
     *
     * A plain send used by setup: if the server accepts it, this half of the path works. Whether the
     * phone received it is a different question, answered by the owner typing a four-digit code back
     * (see {@code NtfySetupDialog}).
     */
    public static AlertChannel.Result probe(String server, String topic, String token, String text) {
        return sendText(server, topic, token, text, "Zeekr");
    }

    /**
     * 问账号：用户名、档位、还剩多少额度。付费档的限额从这里读，不靠猜。
     *
     * Ask the account: name, tier, remaining quota. The paid plan's limits are read from here rather
     * than assumed.
     */
    public static Account account(String server, String token) {
        String url = NtfyRequest.accountUrl(server);
        try {
            Reply reply = call("GET", url, null, token, null);
            if (reply.code != 200) {
                return Account.failed(kind(reply.code), NtfyRequest.redact(reply.body, token));
            }
            JSONObject json = new JSONObject(reply.body);
            JSONObject limits = json.optJSONObject("limits");
            return Account.ok(
                    json.optString("username", ""),
                    json.optString("role", ""),
                    limits == null ? 0 : limits.optInt("reservations", 0),
                    limits == null ? 0 : limits.optInt("attachment_file_size", 0),
                    limits == null ? 0 : limits.optInt("messages", 0));
        } catch (IOException | org.json.JSONException e) {
            return Account.failed(AlertChannel.Kind.NETWORK,
                    NtfyRequest.redact(String.valueOf(e), token));
        }
    }

    /**
     * 这个话题被保护了没有：匿名试发一次，403 就是保护上了。
     *
     * @return {@code TRUE} 保护上、{@code FALSE} 谁都能发、{@code null} 问不出来（那就别声称保护了）
     *
     * Whether the topic is protected: one anonymous publish, 403 means yes. {@code null} means the
     * question could not be answered — and then nothing may claim protection.
     */
    public static Boolean protectedTopic(String server, String topic) {
        String url = NtfyRequest.probePublishUrl(server, topic);
        try {
            Reply reply = call("POST", url, null, null, null);
            if (reply.code == 403) {
                return Boolean.TRUE;
            }
            if (reply.code == 200) {
                return Boolean.FALSE;
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 发一次，把 HTTP 码翻成 {@link AlertChannel.Kind}。 */
    private static AlertChannel.Result publish(String method, String url, byte[] body, String token,
                                               String title) {
        try {
            Reply reply = call(method, url, body, token, title);
            if (reply.code == 200) {
                return AlertChannel.Result.ok();
            }
            return AlertChannel.Result.fail(kind(reply.code), reply.code,
                    NtfyRequest.redact(snippet(reply.body), token));
        } catch (IOException e) {
            return AlertChannel.Result.fail(AlertChannel.Kind.NETWORK, 0,
                    NtfyRequest.redact(String.valueOf(e), token));
        }
    }

    /**
     * HTTP 码 → 失败种类。写成一个函数，测试直接对着它钉。
     *
     * HTTP code to failure kind, as one function so the mapping can be tested on its own.
     */
    static AlertChannel.Kind kind(int code) {
        if (code == 200) {
            return AlertChannel.Kind.OK;
        }
        if (code == 401 || code == 403) {
            return AlertChannel.Kind.REJECTED;
        }
        if (code == 404) {
            return AlertChannel.Kind.NOT_FOUND;
        }
        if (code == 413) {
            return AlertChannel.Kind.TOO_LARGE;
        }
        if (code == 429) {
            return AlertChannel.Kind.RATE_LIMITED;
        }
        return AlertChannel.Kind.NETWORK;
    }

    /**
     * 一次请求。头里的 {@code Authorization} 是令牌唯一的落点；异常信息里不会有它，
     * 因为 URL 里本来就没有。
     *
     * One request. The {@code Authorization} header is the token's only landing place, and no exception
     * text carries it because the URL never does.
     */
    private static Reply call(String method, String url, byte[] body, String token, String title)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoInput(true);
            connection.setRequestProperty("Accept", "application/json");
            if (title != null && !title.isEmpty()) {
                // 标题不能带非 ASCII：不是每个实现都按 UTF-8 发头。非 ASCII 由说明正文承担。
                // A title must stay ASCII: not every stack sends headers as UTF-8; the caption carries
                // any non-ASCII text.
                connection.setRequestProperty("X-Title", ascii(title));
            }
            if (token != null && !token.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type",
                        "PUT".equals(method) ? "image/jpeg" : "text/plain; charset=utf-8");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Reply(code, read(stream));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    /** 只留 ASCII：头的值不保证按 UTF-8 出门。Keep ASCII only: header values are not guaranteed UTF-8. */
    private static String ascii(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(c < 128 ? c : '?');
        }
        return out.toString();
    }

    /** 响应体只留一小段，够看懂，不够把令牌带出去。A short slice of the body: enough to understand, too little to leak. */
    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160);
    }

    private static final class Reply {
        final int code;
        final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    /**
     * 账号的读数。{@link #reservations} 是「能不能保护话题」的答案：0 就是不能。
     *
     * What the account says. {@link #reservations} answers "can a topic be protected": 0 means no.
     */
    public static final class Account {
        public final boolean ok;
        public final AlertChannel.Kind kind;
        public final String detail;
        public final String username;
        public final String role;
        public final int reservations;
        public final int attachmentBytes;
        public final int messagesPerDay;

        private Account(boolean ok, AlertChannel.Kind kind, String detail, String username, String role,
                        int reservations, int attachmentBytes, int messagesPerDay) {
            this.ok = ok;
            this.kind = kind;
            this.detail = detail;
            this.username = username;
            this.role = role;
            this.reservations = reservations;
            this.attachmentBytes = attachmentBytes;
            this.messagesPerDay = messagesPerDay;
        }

        static Account ok(String username, String role, int reservations, int attachmentBytes,
                          int messagesPerDay) {
            return new Account(true, AlertChannel.Kind.OK, "", username, role, reservations,
                    attachmentBytes, messagesPerDay);
        }

        static Account failed(AlertChannel.Kind kind, String detail) {
            return new Account(false, kind, detail, "", "", 0, 0, 0);
        }

        /** 这一档能不能保护话题。Whether this tier can protect a topic. */
        public boolean canReserve() {
            return ok && reservations > 0;
        }
    }
}
