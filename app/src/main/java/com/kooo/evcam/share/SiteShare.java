/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.share;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 「分享到 zeekr.autos」（项目所有者 2026-11-28）：把一条自动化的分享码交给 zeekr.autos，让它出现在网站的热门自动化里。
 *
 * <ul>
 *   <li>只在用户点按钮时发，发的只有这串码：{@code POST https://zeekr.autos/api/share}，请求体 {@code {"code":"…"}}。</li>
 *   <li>请求头的 User-Agent 写死成应用名（{@link #USER_AGENT}）：安卓默认的那个带车机型号和系统版本，不发。不带 Cookie。</li>
 *   <li>连同建立加密连接，实测一次约 6 KB（2026-11-28：TLS 1.3 握手约 4.3 KB，请求约 0.2 KB，回话约 0.8 KB）。</li>
 *   <li>不跟跳转、只认 https。网站只存码和被分享的次数（zeekr.autos 的隐私原则），回话里带当前的次数（热度）。</li>
 * </ul>
 *
 * <p>同一台车分享过的码记在本机（{@link #remember}），按钮变成「已分享」：网站不认人，重复点会把热度刷上去。</p>
 */
public final class SiteShare {

    public static final String URL = "https://zeekr.autos/api/share";
    /** 固定的应用名：不带版本、车机型号、系统版本。 */
    static final String USER_AGENT = "ZeekrShortcut";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_ANSWER_BYTES = 4 * 1024;
    private static final String PREFS = "share_site";
    private static final String KEY_CODES = "codes";

    public enum Outcome {
        /** 网站收下了，{@link Result#count} 是现在的热度。 */
        SHARED,
        /** 一小会儿里分享太多次（网站那一层限速）。 */
        TOO_OFTEN,
        /** 网站说这串码不对（不该发生：码是应用自己出的）。 */
        REFUSED,
        /** 连不上、超时、回话看不懂。 */
        FAILED
    }

    public static final class Result {
        public final Outcome outcome;
        /** 分享成功时网站上的热度；别的情况是 0。 */
        public final int count;

        public Result(Outcome outcome, int count) {
            this.outcome = outcome;
            this.count = count;
        }
    }

    private SiteShare() {
    }

    /** 发一次（阻塞，在后台线程上调）。 */
    public static Result post(String code) {
        byte[] body = body(code).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return read(status, stream == null ? "" : text(stream));
        } catch (IOException | RuntimeException e) {
            return new Result(Outcome.FAILED, 0);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 请求体：只有这串码。码只会是 0–9、A–Z（{@code ShareCode}），不合的不发。 */
    static String body(String code) {
        if (code == null || !code.matches("[0-9A-Z]{1,64}")) {
            throw new IllegalArgumentException("not a share code");
        }
        return "{\"code\":\"" + code + "\"}";
    }

    /** 网站的回话 → 结果。 */
    static Result read(int status, String answer) {
        if (status == 429) {
            return new Result(Outcome.TOO_OFTEN, 0);
        }
        if (status == 400) {
            return new Result(Outcome.REFUSED, 0);
        }
        if (status != 200) {
            return new Result(Outcome.FAILED, 0);
        }
        try {
            JsonElement parsed = JsonParser.parseString(answer);
            if (parsed.isJsonObject()) {
                JsonObject o = parsed.getAsJsonObject();
                JsonElement count = o.get("count");
                if (count != null && count.isJsonPrimitive() && count.getAsJsonPrimitive().isNumber()) {
                    return new Result(Outcome.SHARED, Math.max(1, count.getAsInt()));
                }
            }
        } catch (RuntimeException e) {
            // 看不懂就当没成
        }
        return new Result(Outcome.FAILED, 0);
    }

    /** 这台车分享过这串码没有。 */
    public static boolean sharedBefore(Context context, String code) {
        return prefs(context).getStringSet(KEY_CODES, Collections.emptySet()).contains(code);
    }

    /** 记下：这台车分享过这串码。 */
    public static void remember(Context context, String code) {
        SharedPreferences prefs = prefs(context);
        Set<String> codes = new HashSet<>(prefs.getStringSet(KEY_CODES, Collections.emptySet()));
        codes.add(code);
        prefs.edit().putStringSet(KEY_CODES, codes).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String text(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        try (InputStream in = new BufferedInputStream(stream)) {
            int n;
            while ((n = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
                if (buffer.size() > MAX_ANSWER_BYTES) {
                    throw new IOException("answer too large");
                }
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
