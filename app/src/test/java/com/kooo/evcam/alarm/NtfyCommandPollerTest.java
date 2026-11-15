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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 警报之后那五分钟的算术：窗口、消息新旧、自己消息的认法、{@code since} 的推进。
 *
 * <h3>钉住的四件事</h3>
 *
 * <ol>
 *   <li>窗口是 {@code [start, end)}：到点就关，边界不许两边都能说；</li>
 *   <li>比窗口早的消息不是命令 —— ntfy 的时刻只到整秒，所以那一秒之内的都算；</li>
 *   <li>车自己发的（带 {@link NtfySender#MARKER_TAG}）不认成命令；</li>
 *   <li>第一趟用窗口长度当 {@code since}，之后一直往前走，不回头。</li>
 * </ol>
 *
 * <p><b>这里测不到什么，直说：</b>真正那一趟 {@code GET …/json?since=…&poll=1}、线程起落、
 * 30 秒的节拍，都要一台服务器和一个真的进程 —— 这个测试类不碰它们，也不假装碰了。</p>
 *
 * The arithmetic of the five minutes after an alert: the window, message freshness, the own-message test
 * and the {@code since} advance. Four things pinned: the window is {@code [start, end)} so the boundary
 * cannot be argued both ways; a message from before the window is not a command (ntfy times are whole
 * seconds, so that second counts); the car's own messages (carrying {@link NtfySender#MARKER_TAG}) are
 * never commands; and the first poll uses the window length as {@code since}, then moves forward only.
 *
 * <p><b>What is not tested here, plainly:</b> the actual {@code GET …/json?since=…&poll=1} round, the
 * thread's start and stop, and the 30-second beat all need a server and a live process. This class does
 * not touch them and does not pretend to.
 */
public class NtfyCommandPollerTest {

    private static final long FIVE_MINUTES = 5 * 60_000L;

    @Test
    public void windowOpensAtStartAndClosesAtTheEnd() {
        NtfyCommandPoller.Window window = NtfyCommandPoller.Window.startingAt(1_000_000L, FIVE_MINUTES);
        assertEquals(1_000_000L, window.startMs);
        assertEquals(1_000_000L + FIVE_MINUTES, window.endMs);
        assertEquals(FIVE_MINUTES, window.lengthMs());

        assertTrue("起点那一毫秒还开着", window.openAt(1_000_000L));
        assertTrue(window.openAt(1_000_000L + FIVE_MINUTES - 1));
        assertFalse("终点已经关了", window.openAt(1_000_000L + FIVE_MINUTES));
        assertFalse("窗口之前", window.openAt(999_999L));
        assertFalse("窗口之后", window.openAt(1_500_000L));
    }

    @Test
    public void aZeroOrNegativeWindowNeverOpens() {
        NtfyCommandPoller.Window zero = NtfyCommandPoller.Window.startingAt(42L, 0L);
        assertEquals(0L, zero.lengthMs());
        assertFalse(zero.openAt(42L));

        NtfyCommandPoller.Window negative = NtfyCommandPoller.Window.startingAt(42L, -1_000L);
        assertEquals("负数不能把窗口开到过去", 42L, negative.startMs);
        assertFalse(negative.openAt(42L));
    }

    @Test
    public void remainingTimeIsNeverNegative() {
        NtfyCommandPoller.Window window = NtfyCommandPoller.Window.startingAt(0L, FIVE_MINUTES);
        assertEquals(FIVE_MINUTES, window.remainingMs(0L));
        assertEquals(60_000L, window.remainingMs(FIVE_MINUTES - 60_000L));
        assertEquals(0L, window.remainingMs(FIVE_MINUTES));
        assertEquals("到点之后不往回数", 0L, window.remainingMs(FIVE_MINUTES + 1));
    }

    @Test
    public void aMessageOlderThanTheWindowIsNotACommand() {
        long start = 1_700_000_000_500L;
        assertFalse("警报之前一分钟", NtfyCommandPoller.arrivesAfterStart(start - 60_000L, start));
        assertFalse("不知道什么时候来的", NtfyCommandPoller.arrivesAfterStart(0L, start));
        assertFalse("负数时刻", NtfyCommandPoller.arrivesAfterStart(-5L, start));
        assertTrue("窗口开始那一秒里发的（ntfy 只到整秒）",
                NtfyCommandPoller.arrivesAfterStart(1_700_000_000_000L, start));
        assertTrue("之后", NtfyCommandPoller.arrivesAfterStart(start + 1_000L, start));
        assertTrue("正好同一毫秒（只可能在整秒上）",
                NtfyCommandPoller.arrivesAfterStart(start, start));
    }

    @Test
    public void theAlertsOwnMessagesAreRecognisedByTheirTag() {
        assertTrue(NtfyCommandPoller.isOwnMessage(
                Collections.singletonList(NtfySender.MARKER_TAG)));
        assertTrue("配图那条路两个标签都在", NtfyCommandPoller.isOwnMessage(
                Arrays.asList("car", "warning")));
        assertTrue(NtfyCommandPoller.isOwnMessage(Collections.singletonList("warning")));
        assertTrue("大小写不计", NtfyCommandPoller.isOwnMessage(Collections.singletonList("CAR")));
        assertFalse(NtfyCommandPoller.isOwnMessage(Collections.singletonList("skull")));
        assertFalse("没有标签就不是自己发的", NtfyCommandPoller.isOwnMessage(
                Collections.emptyList()));
        assertFalse(NtfyCommandPoller.isOwnMessage((List<String>) null));
        assertFalse("null 元素不算", NtfyCommandPoller.isOwnMessage(
                Collections.singletonList(null)));
    }

    @Test
    public void sinceOnlyEverMovesForward() {
        assertEquals("abc", NtfyCommandPoller.nextSince("", "abc"));
        assertEquals("丢了 id 就留着原来那个", "abc", NtfyCommandPoller.nextSince("abc", ""));
        assertEquals("abc", NtfyCommandPoller.nextSince("abc", null));
        assertEquals("abc", NtfyCommandPoller.nextSince("abc", "   "));
        assertEquals("def", NtfyCommandPoller.nextSince("abc", "def"));
        assertEquals(" def ", "def", NtfyCommandPoller.nextSince("abc", " def "));
        assertEquals("", NtfyCommandPoller.nextSince(null, null));
    }

    @Test
    public void firstPollAsksForTheWindowItself() {
        assertEquals("5m", NtfyCommandPoller.sinceParam("", FIVE_MINUTES));
        assertEquals("1m", NtfyCommandPoller.sinceParam("", 0L));
        assertEquals("2m", NtfyCommandPoller.sinceParam("", 90_000L));
        assertEquals("id 有了就用 id", "Zl9nGFr2KDXy",
                NtfyCommandPoller.sinceParam("Zl9nGFr2KDXy", FIVE_MINUTES));
        assertEquals("空白等于没有", "5m", NtfyCommandPoller.sinceParam("  ", FIVE_MINUTES));
        assertEquals("5m", NtfyCommandPoller.sinceParam(null, FIVE_MINUTES));
    }

    @Test
    public void thePollAddressIsPinned() {
        String topic = "zeekrsc_" + "aB3dE5fG7hJ9kL2mN4pQ6rS8tU0vW1xY3zA5bC7d";
        assertEquals("https://ntfy.sh/" + topic + "/json?since=5m&poll=1",
                NtfyCommandPoller.pollUrl("", topic, "5m"));
        assertEquals("末尾斜杠和空地址按同一套规矩",
                "https://ntfy.sh/" + topic + "/json?since=AbC&poll=1",
                NtfyCommandPoller.pollUrl("https://ntfy.sh/", topic, "AbC"));
        assertEquals("http://car.local:8080/" + topic + "/json?since=5m&poll=1",
                NtfyCommandPoller.pollUrl("http://car.local:8080", " " + topic + " ", "5m"));
    }

    @Test
    public void thePolledVocabularyIsTelegrams() {
        // 轮询器不自己解释命令：一句 pic front 读回来就是 Telegram 那一个 PICTURE#0。
        // The poller does not interpret commands itself: pic front reads back as Telegram's PICTURE#0.
        assertEquals(TelegramCommand.Verb.PICTURE,
                TelegramCommand.parseText("pic front").verb);
        assertEquals(0, TelegramCommand.parseText("pic front").lane);
        assertEquals(TelegramCommand.Verb.STATUS, TelegramCommand.parseText("status").verb);
        assertEquals(TelegramCommand.GuardAction.ON,
                TelegramCommand.parseText("guard on").guard);
        assertEquals("认不出来的一律不动", TelegramCommand.Verb.UNKNOWN,
                TelegramCommand.parseText("見て").verb);
    }
}
