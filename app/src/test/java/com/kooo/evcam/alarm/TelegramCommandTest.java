/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.kooo.evcam.alarm.telegram.TelegramCommand;

/**
 * 车主发来的一句话怎么读（{@link TelegramCommand}）：只认配好的那个会话，只认命令、四个方向和
 * {@code guard} 那一族。
 *
 * How to read a message the owner sends ({@link TelegramCommand}): only the configured chat counts, and
 * only the commands, the four directions, and the {@code guard} family are recognized.
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

    private static TelegramCommand.GuardAction guard(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals(text, TelegramCommand.Verb.GUARD, command.verb);
        return command.guard;
    }

    private static void notAGuard(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals(text, TelegramCommand.Verb.UNKNOWN, command.verb);
        assertEquals(text, null, command.guard);
    }

    @Test
    public void everyGuardPhraseMeansOneAction() {
        assertEquals(TelegramCommand.GuardAction.REPORT, guard("guard"));
        assertEquals(TelegramCommand.GuardAction.ON, guard("guard on"));
        assertEquals(TelegramCommand.GuardAction.OFF, guard("guard off"));
        assertEquals(TelegramCommand.GuardAction.HOME_SET, guard("guard home"));
        assertEquals(TelegramCommand.GuardAction.HOME_CLEAR, guard("guard home clear"));
        assertEquals(TelegramCommand.GuardAction.AUTO_OFF, guard("guard auto off"));
        assertEquals(TelegramCommand.GuardAction.AUTO_ASK, guard("guard auto ask"));
        assertEquals(TelegramCommand.GuardAction.AUTO_ASK_THEN_ON, guard("guard auto then"));
        assertEquals(TelegramCommand.GuardAction.AUTO_ON, guard("guard auto on"));
        assertEquals("guard then 是 guard auto then 的短写法",
                TelegramCommand.GuardAction.AUTO_ASK_THEN_ON, guard("guard then"));
    }

    @Test
    public void aSlashABotMentionCaseAndWhitespaceDoNotMatterForGuard() {
        assertEquals("群里会写成 /guard@BotName on",
                TelegramCommand.GuardAction.ON, guard("/guard@ZeekrSentry_bot on"));
        assertEquals(TelegramCommand.GuardAction.REPORT, guard("/GUARD"));
        assertEquals(TelegramCommand.GuardAction.AUTO_ASK, guard("  /Guard   Auto   ASK  "));
        assertEquals(TelegramCommand.GuardAction.HOME_CLEAR, guard("guard HOME clear"));
    }

    @Test
    public void aWordAfterGuardThatMeansNothingIsUnknown() {
        // 不猜：认不出来的词不会撑到某个最近的意思上
        // No guessing: an unrecognized word does not snap to a nearby meaning
        notAGuard("guard nonsense");
        notAGuard("guard off now");
        notAGuard("guard home on");
        notAGuard("guard auto");
        notAGuard("guard auto maybe");
        notAGuard("guard home clear now");
    }

    @Test
    public void theOlderCommandsDoNotGrowAGuardAction() {
        assertEquals(null, TelegramCommand.parseText("pic").guard);
        assertEquals(null, TelegramCommand.parseText("pic left").guard);
        assertEquals(null, TelegramCommand.parseText("status").guard);
        assertEquals(null, TelegramCommand.parseText("hello").guard);
    }
}
