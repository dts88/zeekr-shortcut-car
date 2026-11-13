/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import com.kooo.evcam.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 和 Telegram 说话的那一头：发一张照片、问机器人叫什么、问最近谁给它发过话。
 *
 * <h3>三条规矩</h3>
 *
 * <ol>
 *   <li><b>令牌不进日志。</b>任何要写下来的字符串先过 {@link TelegramRequest#redact(String)}；
 *       本类自己也不打印 URL。</li>
 *   <li><b>只在后台线程调。</b>这里全是阻塞的网络调用，主线程调会卡住录像的界面；
 *       调用方负责起线程（{@code new Thread(..., "telegram")}）。</li>
 *   <li><b>失败要能说清楚。</b>401 / 404 是令牌或会话号写错了，400 是内容被拒，429 是发太勤，
 *       其余归成一句网络问题；都翻成车主看得懂的一句话，交给设置界面显示。</li>
 * </ol>
 */
public final class TelegramSender {

    private static final String TAG = "TelegramSender";

    /** Telegram {@code sendPhoto} 的上限是 10 MB；留一点余量，超过就不发（省一次白跑）。 */
    public static final int MAX_PHOTO_BYTES = 9 * 1024 * 1024;

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** 一次发送的结果。{@link #error} 已经擦过令牌，可以直接显示。 */
    public static final class Result {
        public final boolean ok;
        public final String error;
        public final int httpCode;

        private Result(boolean ok, String error, int httpCode) {
            this.ok = ok;
            this.error = error;
            this.httpCode = httpCode;
        }

        static Result ok() {
            return new Result(true, "", 200);
        }

        static Result fail(String error, int httpCode) {
            return new Result(false, error == null ? "" : error, httpCode);
        }

        public String display() {
            return ok ? "" : error;
        }
    }

    /** 一个能和机器人说话的会话：私聊、群、频道。 */
    public static final class Chat {
        public final String id;
        public final String title;

        Chat(String id, String title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public String toString() {
            return title == null || title.isEmpty() ? id : title + " (" + id + ")";
        }
    }

    /** 取会话列表失败。消息已擦过令牌。 */
    public static final class ChatListException extends Exception {
        ChatListException(String message) {
            super(message);
        }
    }

    private TelegramSender() {
    }

    /**
     * 发一张照片。
     *
     * @param jpeg    已经压好的 JPEG，别超过 {@link #MAX_PHOTO_BYTES}
     * @param caption 配图文字，可以为空；Telegram 上限 1024 字，这里先截
     */
    public static Result sendPhoto(String token, String chatId, byte[] jpeg, String caption) {
        if (!TelegramRequest.looksLikeToken(token)) {
            return Result.fail("bad token", 0);
        }
        if (!TelegramRequest.looksLikeChatId(chatId)) {
            return Result.fail("bad chat id", 0);
        }
        if (jpeg == null || jpeg.length == 0) {
            return Result.fail("empty picture", 0);
        }
        if (jpeg.length > MAX_PHOTO_BYTES) {
            return Result.fail("picture too big", 413);
        }
        String text = caption == null ? "" : caption;
        if (text.length() > 1000) {
            text = text.substring(0, 1000);
        }

        String boundary = "zeekr" + Long.toHexString(System.nanoTime());
        byte[] body;
        try {
            body = TelegramRequest.multipartBytes(boundary,
                    TelegramRequest.fields(chatId, text),
                    "photo", "alert.jpg", jpeg, "image/jpeg");
        } catch (IOException e) {
            return Result.fail("could not build request", 0);
        }

        HttpURLConnection connection = null;
        try {
            connection = open(TelegramRequest.methodUrl(token, "sendPhoto"));
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            return read(connection);
        } catch (IOException e) {
            // 这里的 message 里可能带 URL（HTTP 异常会），出去之前一律擦掉。
            AppLog.w(TAG, "send failed: " + TelegramRequest.redact(String.valueOf(e)));
            return Result.fail(TelegramRequest.redact(String.valueOf(e)), 0);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 只发一句话（没有照片时用）。
     *
     * <p>照片没拍成也要让车主知道「刚才有人碰了车」，这条路就是那时候用的。
     * 表单用 {@code application/x-www-form-urlencoded}，比 multipart 简单，也够用。</p>
     */
    public static Result sendMessage(String token, String chatId, String text) {
        if (!TelegramRequest.looksLikeToken(token)) {
            return Result.fail("bad token", 0);
        }
        if (!TelegramRequest.looksLikeChatId(chatId)) {
            return Result.fail("bad chat id", 0);
        }
        String body = "chat_id=" + java.net.URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                + "&text=" + java.net.URLEncoder.encode(text == null ? "" : text,
                StandardCharsets.UTF_8);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = null;
        try {
            connection = open(TelegramRequest.methodUrl(token, "sendMessage"));
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            return read(connection);
        } catch (IOException e) {
            AppLog.w(TAG, "send text failed: " + TelegramRequest.redact(String.valueOf(e)));
            return Result.fail(TelegramRequest.redact(String.valueOf(e)), 0);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 问机器人叫什么。名字对得上，说明令牌没错。 */
    public static String botName(String token) throws ChatListException {
        if (!TelegramRequest.looksLikeToken(token)) {
            throw new ChatListException("bad token");
        }
        JSONObject reply = get(TelegramRequest.meUrl(token));
        return reply.optJSONObject("result") == null ? ""
                : reply.optJSONObject("result").optString("username", "");
    }

    /**
     * 最近给机器人发过话的会话，新的在前。
     *
     * <p>车机上没有键盘舒服到能敲令牌，所以令牌填一次之后，会话号用这个自动取：
     * 车主先在手机上给机器人发一句话，这里读 {@code getUpdates} 就能看见是谁。</p>
     */
    public static List<Chat> recentChats(String token) throws ChatListException {
        if (!TelegramRequest.looksLikeToken(token)) {
            throw new ChatListException("bad token");
        }
        JSONObject reply = get(TelegramRequest.updatesUrl(token, 50));
        JSONArray updates = reply.optJSONArray("result");
        if (updates == null) {
            return Collections.emptyList();
        }
        // LinkedHashMap 反过来就是「新的在前」：一次遍历，不排序。
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Chat> chats = new ArrayList<>();
        for (int i = updates.length() - 1; i >= 0; i--) {
            JSONObject update = updates.optJSONObject(i);
            if (update == null) {
                continue;
            }
            JSONObject message = update.optJSONObject("message");
            if (message == null) {
                message = update.optJSONObject("edited_message");
            }
            if (message == null) {
                continue;
            }
            JSONObject chat = message.optJSONObject("chat");
            if (chat == null) {
                continue;
            }
            String id = chat.optString("id", "");
            if (id.isEmpty() || !seen.add(id)) {
                continue;
            }
            String title = chat.optString("title", "");
            if (title.isEmpty()) {
                title = chat.optString("first_name", "");
            }
            if (title.isEmpty()) {
                title = chat.optString("username", "");
            }
            chats.add(new Chat(id, title));
        }
        return chats;
    }

    private static JSONObject get(String url) throws ChatListException {
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            connection.setRequestMethod("GET");
            Result result = read(connection);
            if (!result.ok) {
                throw new ChatListException(result.error);
            }
            String text = readText(connection);
            return new JSONObject(text);
        } catch (IOException | org.json.JSONException e) {
            throw new ChatListException(TelegramRequest.redact(String.valueOf(e)));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        return connection;
    }

    /** 读一次回复：够用就返回，不够用就把 Telegram 的原话翻成一句短话。 */
    private static Result read(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        String text = readText(connection);
        JSONObject reply = null;
        try {
            reply = text.isEmpty() ? null : new JSONObject(text);
        } catch (org.json.JSONException ignored) {
            // 不是 JSON：下面按状态码说
        }
        if (code == HttpURLConnection.HTTP_OK && reply != null && reply.optBoolean("ok", false)) {
            return Result.ok();
        }
        String description = reply == null ? "" : reply.optString("description", "");
        if (code == 401 || code == 404) {
            return Result.fail("telegram: " + (description.isEmpty() ? "token or chat id rejected" : description), code);
        }
        if (code == 400) {
            return Result.fail("telegram: " + (description.isEmpty() ? "request rejected" : description), code);
        }
        if (code == 413) {
            return Result.fail("telegram: picture too big", code);
        }
        if (code == 429) {
            long retry = 1;
            if (reply != null) {
                JSONObject parameters = reply.optJSONObject("parameters");
                if (parameters != null) {
                    retry = parameters.optLong("retry_after", 1);
                }
            }
            return Result.fail("telegram: too many messages, wait " + retry + "s", code);
        }
        return Result.fail("telegram: HTTP " + code
                + (description.isEmpty() ? "" : " " + description), code);
    }

    private static String readText(HttpURLConnection connection) throws IOException {
        InputStream stream;
        try {
            stream = connection.getInputStream();
        } catch (IOException e) {
            stream = connection.getErrorStream();
            if (stream == null) {
                throw e;
            }
        }
        try (InputStream in = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > 512 * 1024) {
                    break;
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
