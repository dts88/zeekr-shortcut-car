/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发往 Telegram 的那一次请求 —— 拼地址、拼表单、擦掉令牌。纯字符串和字节，便于单测。
 *
 * <h3>令牌只出现在一个地方</h3>
 *
 * <p>Bot API 把令牌放在路径里（{@code /bot<token>/sendPhoto}），所以任何一个 URL、异常信息、
 * 日志行都可能把它带出去。这个类只在一处拼它（{@link #methodUrl}），其余地方拿到的字符串
 * 都先过 {@link #redact(String)}，把 {@code <token>} 换掉。日志、故障报告、
 * 本地通知都只能用擦过的那一份。</p>
 */
public final class TelegramRequest {

    /** 会话号 / 令牌都不该出现在日志里，擦成这个。 */
    public static final String REDACTED = "<token>";

    /** 令牌外形：数字，冒号，然后是一串字母数字下划线横线。 */
    private static final String TOKEN_SHAPE = "\\d{5,}:[A-Za-z0-9_-]{25,}";

    private TelegramRequest() {
    }

    /** 令牌看起来像不像真的。填错了当场说，而不是等一次 404。 */
    public static boolean looksLikeToken(String token) {
        return token != null && token.trim().matches(TOKEN_SHAPE);
    }

    /** 会话号像个不像：一串十进制数字，群组是负数。 */
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
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replaceAll(TOKEN_SHAPE, REDACTED);
    }

    /** {@code sendPhoto} 的完整地址。这个方法的返回值<b>不能</b>进日志。 */
    public static String methodUrl(String token, String method) {
        return "https://api.telegram.org/bot" + token.trim() + "/" + method;
    }

    /** 取机器人的名字，用来在设置里确认「填对了」。 */
    public static String meUrl(String token) {
        return methodUrl(token, "getMe");
    }

    /** 取最近的会话，用来从车机上自动找到会话号。 */
    public static String updatesUrl(String token) {
        return methodUrl(token, "getUpdates");
    }

    /** {@code getUpdates} 只看最近这么多条。 */
    public static String updatesUrl(String token, int limit) {
        return updatesUrl(token) + "?limit=" + Math.max(1, Math.min(100, limit));
    }

    /**
     * 配图消息的文字。
     *
     * @param headline 触发原因（已翻好）
     * @param sentry   哨兵模式的状态文字
     * @param time     本地时间 {@code HH:mm}
     */
    public static String caption(String headline, String sentry, String time) {
        StringBuilder out = new StringBuilder();
        out.append(headline == null ? "" : headline.trim());
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
     */
    public static String keyboardMarkup() {
        return "{\"keyboard\":[[{\"text\":\"pic\"},{\"text\":\"status\"}]],"
                + "\"resize_keyboard\":true,\"is_persistent\":true,"
                + "\"input_field_placeholder\":\"pic / status\"}";
    }

    /**
     * 表单里的普通字段。
     *
     * <p>顺序固定（{@link LinkedHashMap}），写出来的字节才可预期，测试也才对得上。</p>
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

    /** 拼好的一整份表单，给测试和「大小合不合适」的检查用。 */
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
