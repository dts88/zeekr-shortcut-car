/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

import com.kooo.evcam.alarm.guard.GuardToken;
import com.kooo.evcam.alarm.telegram.TelegramRequest;

/**
 * 一排能回话的按钮：字节要能进 Telegram，按钮上的字不能把 JSON 拆了（{@link TelegramRequest#inlineKeyboard}）。
 *
 * <p>手写 JSON 就要手测：引号、反斜线、换行，和一个空数组。</p>
 */
public class TelegramInlineKeyboardTest {

    @Test
    public void twoButtonsBecomeOneRow() {
        String markup = TelegramRequest.inlineKeyboard(
                new String[]{"Turn on now", "Leave it off"},
                new String[]{"g:on:abc123", "g:no:abc123"});
        assertEquals("{\"inline_keyboard\":[["
                + "{\"text\":\"Turn on now\",\"callback_data\":\"g:on:abc123\"},"
                + "{\"text\":\"Leave it off\",\"callback_data\":\"g:no:abc123\"}"
                + "]]}", markup);
    }

    @Test
    public void aLabelWithQuotesOrABackslashDoesNotBreakTheJson() {
        String markup = TelegramRequest.inlineKeyboard(
                new String[]{"say \"yes\"", "back\\slash"},
                new String[]{"g:on:1", "g:no:1"});
        assertTrue(markup, markup.contains("\\\"yes\\\""));
        assertTrue(markup, markup.contains("back\\\\slash"));
        // 形状没被拆：还是那一个对象、那一个数组
        assertTrue(markup, markup.startsWith("{\"inline_keyboard\":[["));
        assertTrue(markup, markup.endsWith("]]}"));
    }

    @Test
    public void aNewlineInALabelIsEscapedNotSentRaw() {
        String markup = TelegramRequest.inlineKeyboard(new String[]{"one\ntwo"},
                new String[]{"g:on:1"});
        assertTrue(markup, markup.contains("one\\ntwo"));
        assertTrue(markup, !markup.contains("one\ntwo"));
    }

    @Test
    public void mismatchedOrEmptyInputGivesNoButtonsAtAll() {
        assertEquals("", TelegramRequest.inlineKeyboard(null, new String[]{"g:on:1"}));
        assertEquals("", TelegramRequest.inlineKeyboard(new String[]{"a"}, null));
        assertEquals("", TelegramRequest.inlineKeyboard(new String[0], new String[0]));
        assertEquals("", TelegramRequest.inlineKeyboard(
                new String[]{"a", "b"}, new String[]{"g:on:1"}));
        // 一个没有 callback_data 的按钮按不下去：宁可没有那一排
        assertEquals("", TelegramRequest.inlineKeyboard(
                new String[]{"a", "b"}, new String[]{"g:on:1", ""}));
    }

    @Test
    public void everyPayloadFitsTelegramsSixtyFourBytes() {
        String token = GuardToken.mint(System.currentTimeMillis());
        for (String action : new String[]{GuardToken.ACTION_ON, GuardToken.ACTION_NO,
                GuardToken.ACTION_OFF, GuardToken.ACTION_STATE}) {
            String data = GuardToken.data(action, token);
            assertTrue(data, data.getBytes(StandardCharsets.UTF_8).length <= 64);
            String markup = TelegramRequest.inlineKeyboard(new String[]{"x"}, new String[]{data});
            assertTrue(markup, markup.contains(data));
        }
    }
}
