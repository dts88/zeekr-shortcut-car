/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 拼往 Telegram 的那一次请求（{@link TelegramRequest}）：地址怎么拼、令牌怎么擦、表单怎么写、令牌和会话号的形状。
 *
 * <p>令牌在路径里，所以“擦干净”不是讲究而是必须：异常、日志、故障报告都可能带出整条 URL。</p>
 */
public class TelegramRequestTest {

    private static final String TOKEN = "123456789:AAH_exampleTOKENvalue_0123456789";

    @Test
    public void aRealLookingTokenPasses() {
        assertTrue(TelegramRequest.looksLikeToken(TOKEN));
        assertTrue(TelegramRequest.looksLikeToken("  " + TOKEN + " "));
    }

    @Test
    public void shortOrShapelessTokensAreRefused() {
        assertFalse(TelegramRequest.looksLikeToken(null));
        assertFalse(TelegramRequest.looksLikeToken(""));
        assertFalse(TelegramRequest.looksLikeToken("not-a-token"));
        assertFalse(TelegramRequest.looksLikeToken("123:short"));
        assertFalse(TelegramRequest.looksLikeToken("abc:AAH_exampleTOKENvalue_0123456789"));
    }

    @Test
    public void chatIdsAreDigitsWithAnOptionalMinus() {
        assertTrue(TelegramRequest.looksLikeChatId("123456789"));
        assertTrue(TelegramRequest.looksLikeChatId("-1001234567890"));
        assertFalse(TelegramRequest.looksLikeChatId(""));
        assertFalse(TelegramRequest.looksLikeChatId("@channel"));
        assertFalse(TelegramRequest.looksLikeChatId("-"));
        assertFalse(TelegramRequest.looksLikeChatId("12 34"));
    }

    @Test
    public void theTokenNeverSurvivesRedaction() {
        String url = TelegramRequest.methodUrl(TOKEN, "sendPhoto");
        assertTrue(url.contains(TOKEN));
        String cleaned = TelegramRequest.redact("POST " + url + " failed: " + url);
        assertFalse("令牌不能留在要写下来的字符串里", cleaned.contains(TOKEN));
        assertTrue(cleaned.contains(TelegramRequest.REDACTED));
    }

    @Test
    public void redactionKeepsTheRestOfTheMessage() {
        assertEquals("connect timed out", TelegramRequest.redact("connect timed out"));
        assertEquals("", TelegramRequest.redact(null));
    }

    @Test
    public void methodUrlsPointAtTheApiAndTheMethod() {
        assertEquals("https://api.telegram.org/bot" + TOKEN + "/sendPhoto",
                TelegramRequest.methodUrl(TOKEN, "sendPhoto"));
        assertTrue(TelegramRequest.meUrl(TOKEN).endsWith("/getMe"));
        assertTrue(TelegramRequest.updatesUrl(TOKEN, 50).startsWith(
                "https://api.telegram.org/bot" + TOKEN + "/getUpdates?limit=50"));
    }

    @Test
    public void captionCarriesHeadlineTimeThenSentryLine() {
        assertEquals("Movement while parked\n07:12\nSentry Mode: on",
                TelegramRequest.caption("Movement while parked", "Sentry Mode: on", "07:12"));
        assertEquals("Test", TelegramRequest.caption("Test", "", ""));
    }

    @Test
    public void fieldsKeepChatIdFirstAndSkipAnEmptyCaption() {
        Map<String, String> withCaption = TelegramRequest.fields("42", "hello");
        assertEquals("[chat_id, caption]", withCaption.keySet().toString());
        Map<String, String> noCaption = TelegramRequest.fields("42", "");
        assertEquals("[chat_id]", noCaption.keySet().toString());
    }

    @Test
    public void multipartWrapsFieldsAndOneFile() throws IOException {
        byte[] jpeg = new byte[] {1, 2, 3, 4};
        byte[] body = TelegramRequest.multipartBytes("BOUND", TelegramRequest.fields("42", "hi"),
                "photo", "alert.jpg", jpeg, "image/jpeg");
        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue(text.contains("--BOUND\r\n"));
        assertTrue(text.contains("name=\"chat_id\"\r\n\r\n42\r\n"));
        assertTrue(text.contains("name=\"photo\"; filename=\"alert.jpg\""));
        assertTrue(text.contains("Content-Type: image/jpeg"));
        assertTrue(text.endsWith("--BOUND--\r\n"));
        // 文件字节原样在中间：JPEG 不能被当成文本处理
        int at = text.indexOf("image/jpeg\r\n\r\n");
        assertEquals(1, body[at + "image/jpeg\r\n\r\n".getBytes(StandardCharsets.UTF_8).length]);
    }

    @Test
    public void multipartWithoutAFileStillCloses() throws IOException {
        byte[] body = TelegramRequest.multipartBytes("BOUND", TelegramRequest.fields("42", ""),
                "photo", "alert.jpg", null, "image/jpeg");
        String text = new String(body, StandardCharsets.UTF_8);
        assertFalse(text.contains("filename="));
        assertTrue(text.endsWith("--BOUND--\r\n"));
    }
}
