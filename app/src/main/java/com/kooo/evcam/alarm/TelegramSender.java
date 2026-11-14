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
        /**
         * 401 / 404：Telegram 不认这个令牌或这个会话。
         *
         * <p>分出来是为了让界面说人话 —— 「Unauthorized」上车主看不懂，也不该由他去猜是令牌还是网络。</p>
         */
        public final boolean rejected;

        private Result(boolean ok, String error, int httpCode, boolean rejected) {
            this.ok = ok;
            this.error = error == null ? "" : error;
            this.httpCode = httpCode;
            this.rejected = rejected;
        }

        static Result ok() {
            return new Result(true, "", 200, false);
        }

        static Result fail(String error, int httpCode) {
            return new Result(false, error, httpCode, httpCode == 401 || httpCode == 404);
        }

        public String display() {
            return ok ? "" : error;
        }
    }

    /** 一个能和机器人说话的会话：私聊、群、频道。 */
    public static final class Chat {
        public final String id;
        public final String title;
        /** 最后给机器人发话的那个人（群聊里就是里面的人）；没有为空串。 */
        public final String userId;

        Chat(String id, String title, String userId) {
            this.id = id;
            this.title = title;
            this.userId = userId;
        }

        @Override
        public String toString() {
            return title == null || title.isEmpty() ? id : title + " (" + id + ")";
        }
    }

    /** 取会话列表失败。消息已擦过令牌。 */
    public static final class ChatListException extends Exception {
        /** Telegram 说「等一下」时给的建议（429 的 retry_after），没有就是 0。 */
        final long retryAfterMs;

        ChatListException(String message) {
            this(message, 0L);
        }

        ChatListException(String message, long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
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
        return sendPhoto(token, chatId, jpeg, caption, null);
    }

    /** 同上，带一排按钮（{@link TelegramRequest#keyboardMarkup()}）。 */
    public static Result sendPhoto(String token, String chatId, byte[] jpeg, String caption,
                                   String replyMarkup) {
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
            java.util.Map<String, String> fields = TelegramRequest.fields(chatId, text);
            if (replyMarkup != null && !replyMarkup.isEmpty()) {
                fields.put("reply_markup", replyMarkup);
            }
            body = TelegramRequest.multipartBytes(boundary, fields,
                    "photo", "alert.jpg", jpeg, "image/jpeg");
        } catch (IOException e) {
            return Result.fail("could not build request", 0);
        }

        // 上传一条照片最容易被 4G 掐断：写到一半连接被对面关掉，报的就是一句「closed」。
        // 所以同一张照片再试一次（只在一次状态码都没拿到时重试 —— 已经答过的重试会变成重复消息）。
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(1200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                Result result = post(token, boundary, body);
                if (result != null) {
                    return result;
                }
            } catch (IOException e) {
                last = e;
                AppLog.w(TAG, "photo upload attempt " + (attempt + 1) + " failed: "
                        + TelegramRequest.redact(String.valueOf(e)));
            }
        }
        String reason = last == null ? "no answer from Telegram"
                : TelegramRequest.redact(String.valueOf(last));
        return Result.fail("upload interrupted: " + reason, 0);
    }

    /**
     * 真正发一次。
     *
     * @return 拿到状态码的结果；一次都没拿到时返回 {@code null}，让外面重试
     */
    private static Result postMultipart(String token, String method, String boundary, byte[] body)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = open(TelegramRequest.methodUrl(token, method));
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Connection", "close");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            return read(connection);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static Result post(String token, String boundary, byte[] body) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = open(TelegramRequest.methodUrl(token, "sendPhoto"));
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);
            // 不设固定长度：让实现自己缓冲好再发。一边写一边发时，对面提前关连接就只剩一句「closed」，
            // 既看不出是网络还是内容被拒；这条请求最多几 MB，内存吃得下。
            connection.setRequestProperty("Connection", "close");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            return read(connection);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 发一段短视频（MP4）。
     *
     * <p>和照片一样是 multipart，只是字段叫 {@code video}、类型是 {@code video/mp4}。
     * {@code supports_streaming} 让手机上点开就能看，不用等整段下完。</p>
     *
     * @return 和 {@link #sendPhoto} 一样的结果；文件读不了也算失败
     */
    public static Result sendVideo(String token, String chatId, java.io.File file, String caption,
                                   String replyMarkup) {
        if (!TelegramRequest.looksLikeToken(token)) {
            return Result.fail("bad token", 0);
        }
        if (!TelegramRequest.looksLikeChatId(chatId)) {
            return Result.fail("bad chat id", 0);
        }
        if (file == null || !file.isFile() || file.length() == 0) {
            return Result.fail("no clip", 0);
        }
        if (file.length() > MAX_PHOTO_BYTES * 5L) {
            // Telegram 的上限是 50 MB；9 MB 的五倍是我们自己的线
            return Result.fail("clip too big", 413);
        }
        String text = caption == null ? "" : caption;
        if (text.length() > 1000) {
            text = text.substring(0, 1000);
        }
        String boundary = "zeekr" + Long.toHexString(System.nanoTime());
        byte[] clip;
        try {
            clip = java.nio.file.Files.readAllBytes(file.toPath());
        } catch (java.io.IOException e) {
            return Result.fail("clip unreadable", 0);
        }
        java.util.Map<String, String> fields = TelegramRequest.fields(chatId, text);
        fields.put("supports_streaming", "true");
        if (replyMarkup != null && !replyMarkup.isEmpty()) {
            fields.put("reply_markup", replyMarkup);
        }
        byte[] body;
        try {
            body = TelegramRequest.multipartBytes(boundary, fields,
                    "video", "alert.mp4", clip, "video/mp4");
        } catch (java.io.IOException e) {
            return Result.fail("could not build request", 0);
        }
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(1200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                Result result = postMultipart(token, "sendVideo", boundary, body);
                if (result != null) {
                    return result;
                }
            } catch (IOException e) {
                last = e;
                AppLog.w(TAG, "video upload attempt " + (attempt + 1) + " failed: "
                        + TelegramRequest.redact(String.valueOf(e)));
            }
        }
        return Result.fail("upload interrupted: " + TelegramRequest.redact(String.valueOf(last)), 0);
    }

    /**
     * 只发一句话（没有照片时用）。
     *
     * <p>照片没拍成也要让车主知道「刚才有人碰了车」，这条路就是那时候用的。
     * 表单用 {@code application/x-www-form-urlencoded}，比 multipart 简单，也够用。</p>
     */
    public static Result sendMessage(String token, String chatId, String text) {
        return sendMessage(token, chatId, text, null);
    }

    /** 同上，带一排按钮（{@link TelegramRequest#keyboardMarkup()}）。 */
    public static Result sendMessage(String token, String chatId, String text, String replyMarkup) {
        if (!TelegramRequest.looksLikeToken(token)) {
            return Result.fail("bad token", 0);
        }
        if (!TelegramRequest.looksLikeChatId(chatId)) {
            return Result.fail("bad chat id", 0);
        }
        String body = "chat_id=" + java.net.URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                + "&text=" + java.net.URLEncoder.encode(text == null ? "" : text,
                StandardCharsets.UTF_8);
        if (replyMarkup != null && !replyMarkup.isEmpty()) {
            body += "&reply_markup=" + java.net.URLEncoder.encode(replyMarkup, StandardCharsets.UTF_8);
        }
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

    /**
     * 这个会话的名字（{@code getChat}）。空串表示问到了但没有名字。
     *
     * <p>用来确认「填进去的会话号真的存在」 —— 形状对不代表它存在，错一位就是别人的会话。</p>
     */
    static String chatTitle(String token, String chatId) throws ChatListException {
        if (!TelegramRequest.looksLikeToken(token)) {
            throw new ChatListException("bad token");
        }
        if (!TelegramRequest.looksLikeChatId(chatId)) {
            throw new ChatListException("bad chat id");
        }
        JSONObject reply = get(TelegramRequest.methodUrl(token, "getChat") + "?chat_id=" + chatId);
        JSONObject result = reply.optJSONObject("result");
        if (result == null) {
            return "";
        }
        String title = result.optString("title", "");
        if (title.isEmpty()) {
            title = result.optString("first_name", "");
        }
        if (title.isEmpty()) {
            title = result.optString("username", "");
        }
        return title;
    }

    /**
     * 长轮询：把 {@code offset} 之后的命令取回来，最多等 {@code timeoutSeconds} 秒。
     *
     * <p>长轮询是这套「反向命令」能穿过运营商 NAT 的全部原因：请求是车发出去的，
     * 车主的消息塞在这条请求的回复里回来，车永远是客户端。</p>
     *
     * @param offset 已经处理到哪一条（{@code 0} 表示不要历史，只等新的）
     */
    static org.json.JSONObject getUpdates(String token, long offset, int timeoutSeconds)
            throws ChatListException {
        int wait = Math.max(1, Math.min(25, timeoutSeconds));
        StringBuilder url = new StringBuilder(TelegramRequest.updatesUrl(token))
                .append("?timeout=").append(wait)
                .append("&allowed_updates=%5B%22message%22%5D");
        if (offset > 0) {
            url.append("&offset=").append(offset);
        }
        return get(url.toString());
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
            JSONObject from = message.optJSONObject("from");
            chats.add(new Chat(id, title, from == null ? "" : from.optString("id", "")));
        }
        return chats;
    }

    static JSONObject get(String url) throws ChatListException {
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            connection.setRequestMethod("GET");
            // 一次请求只读一次回复体：读完再读一次，Android 的实现会抛 IOException("closed")。
            Reply reply = exchange(connection);
            if (reply.ok()) {
                return reply.json;
            }
            throw new ChatListException(reply.failureText(), reply.retryAfterMs());
        } catch (IOException e) {
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

    /** 一次回复：状态码、原文、解析出来的 JSON。读一次就够，谁也别再读第二次。 */
    static final class Reply {
        final int code;
        final JSONObject json;

        Reply(int code, JSONObject json) {
            this.code = code;
            this.json = json;
        }

        boolean ok() {
            return code == HttpURLConnection.HTTP_OK && json != null && json.optBoolean("ok", false);
        }

        /** 401 / 404：令牌或会话号那头被拒（界面据此说人话）。 */
        boolean rejected() {
            return code == 401 || code == 404;
        }

        String description() {
            return json == null ? "" : json.optString("description", "");
        }

        /** 429 时 Telegram 建议等多久（毫秒）；不是 429 就是 0。 */
        long retryAfterMs() {
            if (code != 429) {
                return 0L;
            }
            JSONObject parameters = json == null ? null : json.optJSONObject("parameters");
            long seconds = parameters == null ? 1L : parameters.optLong("retry_after", 1L);
            return Math.max(1L, seconds) * 1000L;
        }

        /** 翻成车主看得懂的一句。已经擦过令牌。 */
        String failureText() {
            if (code == 401 || code == 404) {
                return "telegram: " + (description().isEmpty() ? "token or chat id rejected" : description());
            }
            if (code == 400) {
                return "telegram: " + (description().isEmpty() ? "request rejected" : description());
            }
            if (code == 413) {
                return "telegram: picture too big";
            }
            if (code == 429) {
                long retry = 1;
                JSONObject parameters = json == null ? null : json.optJSONObject("parameters");
                if (parameters != null) {
                    retry = parameters.optLong("retry_after", 1);
                }
                return "telegram: too many messages, wait " + retry + "s";
            }
            return "telegram: HTTP " + code + (description().isEmpty() ? "" : " " + description());
        }
    }

    private static Reply exchange(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        // 只读一次：读完再读一次，Android 的实现会抛 IOException("closed")。
        return parse(code, readText(connection));
    }

    /**
     * 把一次回复的状态码和原文翻成 {@link Reply}。纯函数，单测直接调（{@code TelegramReplyTest}）。
     *
     * <p>这里不读网络：“只读一次”那个修复在 {@link #exchange} 里，
     * 可测的是它交出来的结论 —— 这就是界面和重试看得到的那一份。</p>
     */
    static Reply parse(int code, String text) {
        JSONObject json = null;
        try {
            json = text == null || text.isEmpty() ? null : new JSONObject(text);
        } catch (org.json.JSONException ignored) {
            // 不是 JSON：下面按状态码说
        }
        return new Reply(code, json);
    }

    /** 读一次回复：够用就返回，不够用就把 Telegram 的原话翻成一句短话。 */
    private static Result read(HttpURLConnection connection) throws IOException {
        Reply reply = exchange(connection);
        if (reply.ok()) {
            return Result.ok();
        }
        return Result.fail(reply.failureText(), reply.code);
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
                if (buffer.size() > 4 * 1024 * 1024) {
                    break;
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
