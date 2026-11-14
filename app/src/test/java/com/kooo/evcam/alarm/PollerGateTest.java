/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 一条更新该不该执行（{@link PollerGate}）：私聊、是那个人、没过期、是命令，四道都要过。
 *
 * <p>钉住的是那次真事：群里任何人按一下机器人那排按钮，车周围的照片就会发到群里 ——
 * 只比会话号是挡不住的。</p>
 */
public class PollerGateTest {

    private static final String OWNER = "7761390174";
    private static final long NOW = 1_700_000_000_000L;

    private static TelegramCommand pic() {
        return TelegramCommand.parseText("pic");
    }

    private static PollerGate.Verdict verdict(String type, String chatId, String fromId,
                                              long sentAt, TelegramCommand command) {
        return PollerGate.verdict(type, chatId, fromId, OWNER, sentAt, NOW, command);
    }

    @Test
    public void aFreshPrivateCommandFromTheOwnerIsAccepted() {
        assertEquals(PollerGate.Verdict.ACCEPT, verdict("private", OWNER, OWNER, NOW - 1_000L, pic()));
    }

    @Test
    public void aGroupChatIsRefusedEvenWhenItsIdIsTheConfiguredOne() {
        assertEquals("群里任何人按一下就会把照片发到群里",
                PollerGate.Verdict.NOT_PRIVATE, verdict("group", OWNER, "42", NOW, pic()));
        assertEquals(PollerGate.Verdict.NOT_PRIVATE, verdict("supergroup", OWNER, OWNER, NOW, pic()));
        assertEquals(PollerGate.Verdict.NOT_PRIVATE, verdict("channel", OWNER, OWNER, NOW, pic()));
    }

    @Test
    public void aDifferentChatOrADifferentPersonIsRefused() {
        assertEquals(PollerGate.Verdict.NOT_OWNER, verdict("private", "999", "999", NOW, pic()));
        assertEquals("会话号被改写、人不是本人：也不认",
                PollerGate.Verdict.NOT_OWNER, verdict("private", OWNER, "999", NOW, pic()));
        assertEquals(PollerGate.Verdict.NOT_OWNER, verdict("private", "999", OWNER, NOW, pic()));
    }

    @Test
    public void aCommandFromHoursAgoIsStale() {
        assertEquals(PollerGate.Verdict.STALE,
                verdict("private", OWNER, OWNER, NOW - PollerGate.MAX_AGE_MS - 1_000L, pic()));
        assertEquals("刚好在窗口里还算",
                PollerGate.Verdict.ACCEPT,
                verdict("private", OWNER, OWNER, NOW - PollerGate.MAX_AGE_MS + 1_000L, pic()));
    }

    @Test
    public void aMessageThatIsNotACommandIsIgnored() {
        assertEquals(PollerGate.Verdict.NOT_A_COMMAND,
                verdict("private", OWNER, OWNER, NOW, TelegramCommand.parseText("hello")));
        assertEquals(PollerGate.Verdict.NOT_A_COMMAND,
                verdict("private", OWNER, OWNER, NOW, TelegramCommand.parseText("")));
        assertEquals(PollerGate.Verdict.NOT_A_COMMAND,
                verdict("private", OWNER, OWNER, NOW, null));
    }

    @Test
    public void anUnknownDateDoesNotMakeACommandStale() {
        assertEquals("拿不到消息时间时不按过期处理", PollerGate.Verdict.ACCEPT,
                verdict("private", OWNER, OWNER, 0L, pic()));
    }
}
