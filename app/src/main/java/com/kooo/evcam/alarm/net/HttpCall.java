/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 HTTP 请求，两条出口共用：连接、超时、写请求体、读一次回复。
 *
 * <h3>为什么把这一层抽出来</h3>
 *
 * <p>Telegram 和 ntfy 的 {@code Sender} 各写了一份 {@code open} / {@code read} /
 * {@code getResponseCode}。两份写法不一样的地方（一份跟着重定向、一份不跟；一份截 4 MiB、一份不截），
 * 没有一处是故意的 —— 都是各自抄的时候带上的。这一层只有一份，两个出口调它。</p>
 *
 * <p>这里<b>不做判断</b>：什么码算「太大」、算「不认」，是各自的 {@code Sender} 的事。这里只负责
 * 「把话发出去，把对方说的话原样带回来」。</p>
 *
 * <h3>Why this layer exists</h3>
 *
 * <p>The Telegram and ntfy senders each carried their own {@code open} / {@code read} /
 * {@code getResponseCode}. Where the two copies differed — one follows redirects, one does not; one
 * caps the body at 4 MiB, one does not — nothing about it was deliberate, it is just what came with
 * each copy. There is now one copy and both senders call it.</p>
 *
 * <p><b>No judgement lives here.</b> Which code means "too large" or "refused" is the business of
 * each sender. This layer sends the request and hands back the reply as it arrived.</p>
 */
public final class HttpCall {

    /**
     * 两次 {@code Sender} 原来各有一份一模一样的数：连接 15 秒，读 30 秒。
     *
     * <p>仍然是一个判断而不是一个测量：车机上 4G 慢的时候 15 秒够连上，且不会把发送线程按到录像卡顿。
     * 改这里就是同时改两条出口。</p>
     *
     * The two senders each carried the same pair: connect 15 s, read 30 s. Still a judgement rather
     * than a measurement: on a slow 4G link 15 s is enough to connect and short enough not to hold the
     * sending thread while recording stutters. Changing it here changes both exits at once.
     */
    public static final int CONNECT_TIMEOUT_MS = 15_000;
    public static final int READ_TIMEOUT_MS = 30_000;

    /**
     * 回复体最多读这么多：够看懂一句错误，不够把内存吃掉。
     *
     * <p>4 MiB 原来是 Telegram 那一份带的（一份长轮询的回复可能很大），ntfy 那一份不截 —— 而 ntfy 的
     * 回复是几十字节的 JSON，截了也碰不到。取小的那个当共同的线，两条出口都不会碰上限。</p>
     *
     * A reply is read up to this size: enough to understand an error line, not enough to eat memory.
     * The 4 MiB came from the Telegram copy (a long-poll reply can be large); the ntfy copy did not cap
     * at all, and ntfy replies are tens of bytes of JSON, so the cap is never reached there either.
     */
    public static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private HttpCall() {
    }

    /** 一次请求的开头。Start one request. */
    public static Request request(String method, String url) {
        return new Request(method, url);
    }

    /** 拼好的一次请求。One request, ready to send. */
    public static final class Request {

        private final String method;
        private final String url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body;
        private boolean fixedLength = true;
        private boolean followRedirects = true;
        private boolean useCaches = true;

        private Request(String method, String url) {
            this.method = method;
            this.url = url;
        }

        /** 加一个头。同名只留最后一个。Add one header; the last value for a name wins. */
        public Request header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        /**
         * 请求体，按已知长度发（{@code setFixedLengthStreamingMode}）。
         *
         * <p>文字和图片走这条：长度已知，一边写一边发，不用在内存里多留一份。</p>
         *
         * The body, sent with a known length. Text and pictures take this path: the length is known,
         * it streams while writing, and no second copy is needed in memory.
         */
        public Request body(byte[] bytes) {
            this.body = bytes;
            this.fixedLength = true;
            return this;
        }

        /**
         * 请求体，不报长度，让实现先缓冲好再发。
         *
         * <p>Telegram 的 multipart 走这条：一边写一边发的时候，对面提前关连接就只剩一句
         * {@code closed}，看不出是网络还是内容被拒；这条请求最多几 MiB，内存吃得下。</p>
         *
         * The body with no declared length, so the implementation buffers before sending. Telegram's
         * multipart takes this path: streaming while writing leaves only {@code closed} when the peer
         * closes early, with no way to tell network from rejected content; the request is a few MiB at
         * most, which memory can afford.
         */
        public Request bufferedBody(byte[] bytes) {
            this.body = bytes;
            this.fixedLength = false;
            return this;
        }

        /** 不跟重定向：3xx 原样回来。Do not follow redirects; a 3xx comes back as it is. */
        public Request noRedirects() {
            this.followRedirects = false;
            return this;
        }

        /** 不用缓存。Do not use the cache. */
        public Request noCaches() {
            this.useCaches = false;
            return this;
        }

        /**
         * 发出去，把回复读一次。
         *
         * <p>只读一次：读完再读一次，Android 的实现会抛 {@code IOException("closed")}。</p>
         *
         * @throws IOException 连不上、超时，或者写请求体的时候被对面关掉
         *
         * Send it and read the reply once. Reading it a second time makes Android's implementation
         * throw {@code IOException("closed")}.
         *
         * @throws IOException could not connect, timed out, or the peer closed while the body was
         *                     being written
         */
        public Reply send() throws IOException {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(followRedirects);
                connection.setUseCaches(useCaches);
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                if (body != null) {
                    connection.setDoOutput(true);
                    if (fixedLength) {
                        connection.setFixedLengthStreamingMode(body.length);
                    }
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(body);
                    }
                }
                int code = connection.getResponseCode();
                return new Reply(code, bodyOf(code, connection));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    /** 对方说的一句话：状态码和原文。What the other side said: the code and the text. */
    public static final class Reply {

        public final int code;
        public final String body;

        Reply(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        /** 200 才算成。注意「成」是这一层的事，能不能用还要各自的发送方判断。200 is success here. */
        public boolean ok() {
            return code == HttpURLConnection.HTTP_OK;
        }
    }

    /**
     * 挑对的那一股流去读：4xx / 5xx 的说明在 {@code errorStream} 里，别的一律 {@code inputStream}。
     *
     * <p>一股读不了就试另一股（一份旧代码就是这么写的，换过来不能少这一步）；两股都不行才把第一个
     * 异常抛出去。</p>
     *
     * Pick the right stream: on a 4xx / 5xx the explanation is in {@code errorStream}, everything else
     * comes from {@code inputStream}. If one cannot be read, try the other (an older copy worked this
     * way and swapping to this layer must not lose the step); only when both fail does the first
     * exception surface.
     */
    static String bodyOf(int code, HttpURLConnection connection) throws IOException {
        boolean error = code >= 400;
        try {
            return read(error ? connection.getErrorStream() : connection.getInputStream());
        } catch (IOException first) {
            InputStream other;
            try {
                other = error ? connection.getInputStream() : connection.getErrorStream();
            } catch (IOException ignored) {
                other = null;
            }
            if (other == null) {
                throw first;
            }
            return read(other);
        }
    }

    /**
     * 读到底，最多 {@link #MAX_BODY_BYTES}，按 UTF-8 解。
     *
     * <p>{@code null} 流当作空回复：{@code getErrorStream()} 在对面没写错误体的时候就是 null。</p>
     *
     * Read to the end, at most {@link #MAX_BODY_BYTES}, decoded as UTF-8. A null stream counts as an
     * empty reply: {@code getErrorStream()} is null when the other side wrote no error body.
     */
    static String read(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) > 0) {
                out.write(chunk, 0, read);
                if (out.size() >= MAX_BODY_BYTES) {
                    break;
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
