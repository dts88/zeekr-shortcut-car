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

/**
 * 车主发来的一句话怎么读（{@link TelegramCommand}）：只认配好的那个会话，只认四种命令和四个方向。
 */
public class TelegramCommandTest {

    @Test
    public void picturesAreAskedForInFourWays() {
        for (String word : new String[] {"pic", "photo", "snap", "snapshot", "PIC", "Photo"}) {
            TelegramCommand command = TelegramCommand.parseText(word);
            assertEquals(word, TelegramCommand.Verb.PICTURE, command.verb);
            assertEquals(word, TelegramCommand.ANY_LANE, command.lane);
        }
    }

    @Test
    public void aSlashAndABotMentionAreIgnored() {
        assertEquals(TelegramCommand.Verb.PICTURE, TelegramCommand.parseText("/pic").verb);
        assertEquals(TelegramCommand.Verb.PICTURE,
                TelegramCommand.parseText("/pic@ZeekrSentry_bot").verb);
        assertEquals("带方向也要认", 2, TelegramCommand.parseText("/pic@ZeekrSentry_bot left").lane);
    }

    @Test
    public void aDirectionPicksThatLane() {
        assertEquals(0, TelegramCommand.parseText("pic front").lane);
        assertEquals(1, TelegramCommand.parseText("pic rear").lane);
        assertEquals(2, TelegramCommand.parseText("picture left").lane);
        assertEquals(3, TelegramCommand.parseText("PHOTO RIGHT").lane);
        assertEquals("不认识的词当没写", TelegramCommand.ANY_LANE,
                TelegramCommand.parseText("pic roof").lane);
    }

    @Test
    public void statusIsItsOwnVerbAndTakesNoPicture() {
        assertEquals(TelegramCommand.Verb.STATUS, TelegramCommand.parseText("status").verb);
        assertEquals(TelegramCommand.Verb.STATUS, TelegramCommand.parseText("/state").verb);
    }

    @Test
    public void everythingElseIsUnknown() {
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText("").verb);
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText("   ").verb);
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText(null).verb);
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText("hello").verb);
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText("\uD83D\uDE00").verb);
    }

    @Test
    public void onlyTheConfiguredChatIsTheOwner() {
        assertTrue(TelegramCommand.fromOwner("7761390174", "7761390174"));
        assertTrue("负数是群组会话号，也照样按字符串比", TelegramCommand.fromOwner("-1001234", "-1001234"));
        assertFalse(TelegramCommand.fromOwner("999", "7761390174"));
        assertFalse("没配好之前谁都不是主人", TelegramCommand.fromOwner("7761390174", ""));
        assertFalse(TelegramCommand.fromOwner(null, "7761390174"));
        assertFalse(TelegramCommand.fromOwner("7761390174", null));
    }
}
