/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.kooo.evcam.alarm.telegram.PollerGate;
import com.kooo.evcam.alarm.telegram.TelegramCommand;

/**
 * 一条更新该不该执行（{@link PollerGate}）：私聊、是那个人、没过期、是命令，四道都要过。
 *
 * <p>钉住的是那次真事：群里任何人按一下机器人那排按钮，车周围的照片就会发到群里 ——
 * 只比会话号是挡不住的。</p>
 *
 * Whether an update should be handled ({@link PollerGate}): it must be a private
 * chat, from that person, not stale, and a command; all four gates.
 *
 * This pins down the real incident: anyone in the group who tapped the bot's
 * button row would get the car's surroundings sent to the group; comparing only
 * the chat id cannot stop that.
 */
public class PollerGateTest {

    private static final String OWNER = "7761390174";
    private static final long NOW = 1_700_000_000_000L;

    private static TelegramCommand pic() {
        return TelegramCommand.parseText("pic");
    }

    private static PollerGate.Verdict verdict(String type, String chatId, String fromId,
                                              long sentAt, TelegramCommand command) {
        // 没记下车主号时：只认私聊里的本人（会话号 == 用户号）
        // When the owner id is not recorded: only accept the owner in a private
        // chat (chat id == user id)
        return PollerGate.verdict(type, chatId, fromId, OWNER, "", sentAt, NOW, command);
    }

    private static PollerGate.Verdict verdictWithOwner(String type, String chatId, String fromId,
                                                       long sentAt, TelegramCommand command) {
        return PollerGate.verdict(type, chatId, fromId, OWNER, OWNER, sentAt, NOW, command);
    }

    @Test
    public void aFreshPrivateCommandFromTheOwnerIsAccepted() {
        assertEquals(PollerGate.Verdict.ACCEPT, verdict("private", OWNER, OWNER, NOW - 1_000L, pic()));
    }

    @Test
    public void aGroupIsAllowedOnceTheOwnerIdIsKnown() {
        assertEquals("记下了车主号：群里只有他自己算数",
                PollerGate.Verdict.ACCEPT, verdictWithOwner("group", OWNER, OWNER, NOW, pic()));
        assertEquals("群里别人不算数",
                PollerGate.Verdict.NOT_OWNER, verdictWithOwner("group", OWNER, "42", NOW, pic()));
    }

    @Test
    public void aGroupIsRefusedUntilTheOwnerIdIsKnown() {
        // 还不知道车主是谁时，群聊一律不认：否则群里任何人按一下就能拿走车周围的照片。
        // When the owner's identity is not known yet, group chats are always
        // refused: otherwise anyone in the group could tap once and take the
        // photos around the car.
        assertEquals(PollerGate.Verdict.NOT_OWNER, verdict("group", OWNER, "42", NOW, pic()));
        assertEquals(PollerGate.Verdict.NOT_OWNER, verdict("group", OWNER, OWNER, NOW, pic()));
        assertEquals(PollerGate.Verdict.NOT_OWNER, verdict("supergroup", OWNER, OWNER, NOW, pic()));
        assertEquals(PollerGate.Verdict.NOT_OWNER, verdict("channel", OWNER, OWNER, NOW, pic()));
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

    // ---- 按钮（callback_query）
    // ---- Buttons (callback_query)

    private static PollerGate.Verdict button(String type, String chatId, String fromId, long sentAt) {
        return PollerGate.verdictForCallback(type, chatId, fromId, OWNER, "", sentAt, NOW);
    }

    @Test
    public void aButtonFromTheOwnerInHisPrivateChatIsAccepted() {
        assertEquals(PollerGate.Verdict.ACCEPT, button("private", OWNER, OWNER, NOW - 1_000L));
        assertEquals("记下了车主号之后，群里他自己按也算", PollerGate.Verdict.ACCEPT,
                PollerGate.verdictForCallback("group", OWNER, OWNER, OWNER, OWNER, NOW, NOW));
    }

    @Test
    public void aButtonFromAnotherChatIsRefused() {
        assertEquals("别的会话里的按钮不是我们的", PollerGate.Verdict.NOT_OWNER,
                button("private", "999", "999", NOW));
        assertEquals(PollerGate.Verdict.NOT_OWNER, button("group", "-100999", OWNER, NOW));
    }

    @Test
    public void aButtonPressedBySomeoneElseInTheOwnersGroupIsRefused() {
        // 车主自己的群里，别人按下去也不算：否则群里任何人一点就能让哨兵开关。
        // In the owner's own group, anyone else pressing does not count either; otherwise any member
        // could toggle sentry with one tap.
        assertEquals(PollerGate.Verdict.NOT_OWNER,
                PollerGate.verdictForCallback("group", OWNER, "42", OWNER, OWNER, NOW, NOW));
        assertEquals(PollerGate.Verdict.NOT_OWNER,
                PollerGate.verdictForCallback("private", OWNER, "42", OWNER, OWNER, NOW, NOW));
    }

    @Test
    public void aButtonOlderThanTheCapIsStale() {
        assertEquals(PollerGate.Verdict.STALE,
                button("private", OWNER, OWNER, NOW - PollerGate.MAX_AGE_MS - 1_000L));
        assertEquals("刚好在窗口里还算", PollerGate.Verdict.ACCEPT,
                button("private", OWNER, OWNER, NOW - PollerGate.MAX_AGE_MS + 1_000L));
        assertEquals("拿不到消息时间时不按过期处理", PollerGate.Verdict.ACCEPT,
                button("private", OWNER, OWNER, 0L));
    }

    @Test
    public void aButtonIsNeverAnUnparsedCommand() {
        // 按钮上带的是 data，没有句子可解析，所以那一道门对按钮不存在。
        // A button carries data, no sentence to parse, so that gate does not exist for buttons.
        for (String type : new String[] {"private", "group", "supergroup", "channel"}) {
            PollerGate.Verdict fromOwner = PollerGate.verdictForCallback(type, OWNER, OWNER, OWNER,
                    OWNER, NOW, NOW);
            assertEquals(type, PollerGate.Verdict.ACCEPT, fromOwner);
        }
        assertEquals("频道里按下去不是私聊，也不是我们的", PollerGate.Verdict.NOT_OWNER,
                button("channel", OWNER, OWNER, NOW));
    }
}
