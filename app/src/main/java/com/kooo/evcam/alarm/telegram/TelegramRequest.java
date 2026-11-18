/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.telegram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.kooo.evcam.alarm.guard.GuardToken;

/**
 * 发往 Telegram 的那一次请求 —— 拼地址、拼表单、擦掉令牌。纯字符串和字节，便于单测。
 *
 * <h3>令牌只出现在一个地方</h3>
 *
 * <p>Bot API 把令牌放在路径里（{@code /bot<token>/sendPhoto}），所以任何一个 URL、异常信息、
 * 日志行都可能把它带出去。这个类只在一处拼它（{@link #methodUrl}），其余地方拿到的字符串
 * 都先过 {@link #redact(String)}，把 {@code <token>} 换掉。日志、故障报告、
 * 本地通知都只能用擦过的那一份。</p>
 *
 * One request sent to Telegram; build the URL, build the form, redact the token. Pure strings and
 * bytes, easy to unit-test.
 *
 * <h3>The token appears in one place only</h3>
 *
 * <p>The Bot API puts the token in the path ({@code /bot<token>/sendPhoto}), so any URL, exception
 * message, or log line could leak it. This class builds it in one spot ({@link #methodUrl}); every
 * other string passes through {@link #redact(String)} first, which replaces {@code <token>}. Logs,
 * fault reports, and local notifications may only use the redacted copy.</p>
 */
public final class TelegramRequest {

    /**
     * 会话号 / 令牌都不该出现在日志里，擦成这个。
     *
     * Chat ids and tokens must not appear in logs; they are redacted to this.
     */
    public static final String REDACTED = "<token>";

    /**
     * 令牌外形：数字，冒号，然后是一串字母数字下划线横线。
     *
     * Token shape: digits, a colon, then a run of letters, digits, underscores, and dashes.
     */
    private static final String TOKEN_SHAPE = "\\d{5,}:[A-Za-z0-9_-]{25,}";

    private TelegramRequest() {
    }

    /**
     * 令牌看起来像不像真的。填错了当场说，而不是等一次 404。
     *
     * Whether the token looks real. A typo is caught immediately instead of waiting for a 404.
     */
    public static boolean looksLikeToken(String token) {
        return token != null && token.trim().matches(TOKEN_SHAPE);
    }

    /**
     * 会话号像个不像：一串十进制数字，群组是负数。
     *
     * Whether the chat id looks right: a run of decimal digits, negative for groups.
     */
    public static boolean looksLikeChatId(String chatId) {
        if (chatId == null) {
            return false;
        }
        String value = chatId.trim();
        if (value.isEmpty() || value.length() > 20) {
            return false;
        }
        if (value.startsWith("-")) {
            value = value.substring(1);
        }
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把令牌擦掉。任何要记下来的字符串都先过这里。
     *
     * <p>认不出来的部分原样留下 —— 宁可能少擦，也不能把整条信息擦没了。</p>
     *
     * Redact the token. Any string that is to be recorded goes through here first.
     *
     * <p>Unrecognized parts are left as-is; better to redact too little than to wipe the whole
     * message.</p>
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll(TOKEN_SHAPE, REDACTED);
    }

    /**
     * {@code sendPhoto} 的完整地址。这个方法的返回值<b>不能</b>进日志。
     *
     * The full {@code sendPhoto} URL. The return value of this method must <b>not</b> go into a log.
     */
    public static String methodUrl(String token, String method) {
        return "https://api.telegram.org/bot" + token.trim() + "/" + method;
    }

    /**
     * 取机器人的名字，用来在设置里确认「填对了」。
     *
     * Fetch the bot's name, used in settings to confirm "filled in correctly".
     */
    public static String meUrl(String token) {
        return methodUrl(token, "getMe");
    }

    /**
     * 取最近的会话，用来从车机上自动找到会话号。
     *
     * Fetch recent chats, used to auto-find the chat id from the head unit.
     */
    public static String updatesUrl(String token) {
        return methodUrl(token, "getUpdates");
    }

    /**
     * {@code getUpdates} 只看最近这么多条。
     *
     * {@code getUpdates} only looks at this many recent entries.
     */
    public static String updatesUrl(String token, int limit) {
        return updatesUrl(token) + "?limit=" + Math.max(1, Math.min(100, limit));
    }

    /**
     * 配图消息的文字。
     *
     * @param headline 触发原因（已翻好）
     * @param sentry   哨兵模式的状态文字
     * @param time     本地时间 {@code HH:mm}
     *
     * Text for the photo message.
     *
     * @param headline the trigger reason (already translated)
     * @param sentry   the sentry-mode status text
     * @param time     local time {@code HH:mm}
     */
    public static String caption(String headline, String sentry, String time) {
        return caption(headline, "", sentry, time);
    }

    /**
     * 带一行“要求于几点”的配文：车睡了一阵才醒过来时用。
     *
     * The caption with a "requested at" line: used when the car woke up after sleeping a while.
     */
    public static String caption(String headline, String late, String sentry, String time) {
        StringBuilder out = new StringBuilder();
        out.append(headline == null ? "" : headline.trim());
        if (late != null && !late.trim().isEmpty()) {
            out.append('\n').append(late.trim());
        }
        if (time != null && !time.trim().isEmpty()) {
            out.append('\n').append(time.trim());
        }
        if (sentry != null && !sentry.trim().isEmpty()) {
            out.append('\n').append(sentry.trim());
        }
        return out.toString();
    }

    /**
     * 聊天里那排按钮（Telegram 的 reply keyboard）。
     *
     * <p>按钮不回调、不发请求：按下去就是把这段文字当成一条普通消息发出去，
     * 所以车那边照 {@link TelegramCommand} 解析就行，不用另做一套回调查询。
     * {@code is_persistent} 让它在聊天里常驻，而不是每次发完消息就被收起。</p>
     *
     * The row of buttons in the chat (Telegram's reply keyboard).
     *
     * <p>The buttons make no callback and send no request: pressing one sends that text as an ordinary
     * message, so the car can parse it with {@link TelegramCommand}; no separate callback-query flow.
     * {@code is_persistent} keeps it pinned in the chat instead of collapsing after each message.</p>
     */
    public static String keyboardMarkup() {
        return "{\"keyboard\":[[{\"text\":\"pic\"},{\"text\":\"status\"}]],"
                + "\"resize_keyboard\":true,\"is_persistent\":true,"
                + "\"input_field_placeholder\":\"pic / status\"}";
    }

    /**
     * 一条消息下面那排能回话的按钮（Telegram 的 inline keyboard）。
     *
     * <p>和 {@link #keyboardMarkup()} 不是一回事：那排是把文字发回来，这排是 {@code callback_query}，
     * 按下去不进聊天记录，也不经过命令解析。所以每一颗必须带上这一次提醒的号
     * （{@code alarm.GuardToken}），否则一周前的按钮按下去也算数。</p>
     *
     * <p>手写 JSON 而不是用 {@code org.json}：这里只要可预期的字节，JVM 单测也不用 Robolectric。
     * 按钮上的字来自 {@code strings.xml}，可能带引号，所以两个值都要转义。</p>
     *
     * <p>The row of buttons that can answer back under one message (Telegram's inline keyboard).
     * Unlike {@link #keyboardMarkup()} these arrive as a {@code callback_query}: nothing is written into
     * the chat and no command parser sees them, so each button must carry this reminder's token.
     * JSON is written by hand for predictable bytes and a JVM-testable unit, and both values are
     * escaped because the label comes from {@code strings.xml}.</p>
     *
     * @param labels 按钮上的字，从左到右
     * @param datas  {@code callback_data}，和 {@code labels} 一一对应
     * @return {@code reply_markup} 的值；两个数组不配对时是空串（宁可没有按钮，也不要一排按不动的）
     */
    public static String inlineKeyboard(String[] labels, String[] datas) {
        return inlineKeyboard(labels, datas, labels == null ? 0 : labels.length);
    }

    /**
     * 同一排按钮，每 {@code perRow} 颗换一行。
     *
     * <p>四颗挤在一行，手机上一颗只有几个字的宽度，所以警报那张照片下面是两行两行排的。</p>
     *
     * The same buttons, wrapped every {@code perRow} of them: four in one row leaves each button only a
     * few characters wide on a phone, so the alert's picture carries two rows of two.
     */
    public static String inlineKeyboard(String[] labels, String[] datas, int perRow) {
        if (labels == null || datas == null || labels.length == 0
                || labels.length != datas.length || perRow <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder("{\"inline_keyboard\":[");
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null || datas[i] == null || datas[i].isEmpty()) {
                return "";
            }
            if (i % perRow == 0) {
                out.append(i == 0 ? "[" : ",[");
            } else {
                out.append(',');
            }
            out.append("{\"text\":\"").append(jsonEscape(labels[i]))
                    .append("\",\"callback_data\":\"").append(jsonEscape(datas[i])).append("\"}");
            if (i % perRow == perRow - 1 || i == labels.length - 1) {
                out.append(']');
            }
        }
        return out.append("]}").toString();
    }

    /** JSON 字符串里的那两个字符。The two characters that matter inside a JSON string. */
    static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }

    /**
     * 表单里的普通字段。
     *
     * <p>顺序固定（{@link LinkedHashMap}），写出来的字节才可预期，测试也才对得上。</p>
     *
     * The ordinary fields of the form.
     *
     * <p>Order is fixed ({@link LinkedHashMap}) so the bytes written are predictable and tests match.</p>
     */
    public static Map<String, String> fields(String chatId, String caption) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("chat_id", chatId);
        if (caption != null && !caption.isEmpty()) {
            fields.put("caption", caption);
        }
        return fields;
    }

    /**
     * 拼 multipart 表单：先普通字段，再一次一个文件。
     *
     * <p>只有一份文件（这一张照片），所以不追求通用，够用、字节可预期就好。</p>
     *
     * Build the multipart form: ordinary fields first, then one file at a time.
     *
     * <p>There is only one file (this one photo), so it does not aim to be general; good enough and
     * byte-predictable is enough.</p>
     */
    public static void writeMultipart(OutputStream out, String boundary, Map<String, String> fields,
                                      String fileField, String fileName, byte[] file,
                                      String contentType) throws IOException {
        byte[] dash = ("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            out.write(dash);
            writeText(out, "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n");
            writeText(out, entry.getValue());
            writeText(out, "\r\n");
        }
        if (file != null && file.length > 0) {
            out.write(dash);
            writeText(out, "Content-Disposition: form-data; name=\"" + fileField
                    + "\"; filename=\"" + fileName + "\"\r\n");
            writeText(out, "Content-Type: " + contentType + "\r\n\r\n");
            out.write(file);
            writeText(out, "\r\n");
        }
        writeText(out, "--" + boundary + "--\r\n");
        out.flush();
    }

    /**
     * 拼好的一整份表单，给测试和「大小合不合适」的检查用。
     *
     * The fully built form, for tests and the "is the size right" check.
     */
    public static byte[] multipartBytes(String boundary, Map<String, String> fields,
                                        String fileField, String fileName, byte[] file,
                                        String contentType) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                file == null ? 1024 : file.length + 1024);
        writeMultipart(out, boundary, fields, fileField, fileName, file, contentType);
        return out.toByteArray();
    }

    private static void writeText(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }
}
