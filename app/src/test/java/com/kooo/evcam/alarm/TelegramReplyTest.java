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
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 一次 Telegram 回复怎么解读（{@code TelegramSender.parse}）。
 *
 * <p>跑在 Robolectric 上：org.json 在纯 JVM 单测里只有空实现，解析出来全是 null。
 * 这不是在测网络，测的是「拿到状态码和原文之后得什么结论」——
 * 界面上的每一句话、重试与否，都是从这一份结论出来的。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class TelegramReplyTest {

    private static TelegramSender.Reply parse(int code, String body) {
        return TelegramSender.parse(code, body);
    }

    @Test
    public void a200WithOkIsSuccess() {
        TelegramSender.Reply reply = parse(200, "{\"ok\":true,\"result\":{\"message_id\":3}}");
        assertTrue(reply.ok());
    }

    @Test
    public void a401Or404IsRejectedNotJustFailed() {
        TelegramSender.Reply unauthorized = parse(401, "{\"ok\":false,\"description\":\"Unauthorized\"}");
        assertFalse(unauthorized.ok());
        assertTrue("界面要能说「令牌或会话号被拒」，而不是甩一句 401", unauthorized.rejected());
        assertTrue(unauthorized.failureText().contains("Unauthorized"));

        TelegramSender.Reply missing = parse(404, "{\"ok\":false,\"description\":\"Not Found\"}");
        assertFalse(missing.ok());
        assertTrue(missing.rejected());
        assertTrue(missing.failureText().contains("Not Found"));
    }

    @Test
    public void a400IsAFailureButNotARejection() {
        TelegramSender.Reply reply = parse(400, "{\"ok\":false,\"description\":\"Bad Request: chat not found\"}");
        assertFalse(reply.ok());
        assertFalse("400 是内容被拒，不是令牌被拒", reply.rejected());
        assertEquals(0L, reply.retryAfterMs());
        assertTrue(reply.failureText().contains("chat not found"));
    }

    @Test
    public void a429CarriesTheServersWait() {
        TelegramSender.Reply reply = parse(429,
                "{\"ok\":false,\"description\":\"Too Many Requests\",\"parameters\":{\"retry_after\":5}}");
        assertFalse(reply.ok());
        assertEquals("轮询照 Telegram 说的等，而不是自己猜", 5_000L, reply.retryAfterMs());
        assertTrue(reply.failureText().contains("5"));
    }

    @Test
    public void aNonJsonBodyDoesNotPretendToBeSuccess() {
        TelegramSender.Reply reply = parse(200, "<html>gateway</html>");
        assertFalse(reply.ok());
        assertTrue(reply.failureText().contains("200"));
    }

    @Test
    public void anEmptyBodyIsAFailure() {
        assertFalse(parse(200, "").ok());
        assertFalse(parse(0, "").ok());
    }
}
