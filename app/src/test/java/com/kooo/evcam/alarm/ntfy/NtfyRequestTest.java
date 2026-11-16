/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.ntfy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import com.kooo.evcam.alarm.AlertChannel;

/**
 * ntfy 的请求拼得对不对 —— 纯字符串，不需要网络、不需要手机。
 *
 * <h3>钉住的三件事</h3>
 *
 * <ol>
 *   <li>话题名的形状与长度（它就是密码，短了等于没门）；</li>
 *   <li>配图的说明走查询串、真换行编成 {@code %0A} —— 这是量出来的（docs/ntfy/FINDINGS.md）；</li>
 *   <li>令牌擦得干净。</li>
 * </ol>
 *
 * The shape of an ntfy request, tested as pure strings. Three things pinned: the topic name's shape
 * and length (it is the password, and a short one is no door), the caption travelling in the query
 * string with real newlines as {@code %0A} (measured), and the token being redacted.
 */
public class NtfyRequestTest {

    private static final String TOPIC = "zeekrsc_" + "aB3dE5fG7hJ9kL2mN4pQ6rS8tU0vW1xY3zA5bC7d";

    @Test
    public void generatedTopicsAreLongUniqueAndLegal() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String topic = NtfyRequest.generateTopic();
            assertTrue("形状不对: " + topic, NtfyRequest.looksLikeTopic(topic));
            assertTrue("生成的名字太短: " + topic, topic.length() >= 32);
            assertTrue("前缀丢了: " + topic, topic.startsWith("zeekrsc_"));
            assertTrue("撞了: " + topic, seen.add(topic));
        }
    }

    @Test
    public void topicShapeIsPinned() {
        assertTrue(NtfyRequest.looksLikeTopic(TOPIC));
        assertTrue(NtfyRequest.looksLikeTopic("zeekrsc_with-dash_AND_1234_abcdefghijklmn"));
        assertFalse("短名字等于没有门", NtfyRequest.looksLikeTopic("zeekrsc_short"));
        assertFalse("空格不是合法字符", NtfyRequest.looksLikeTopic(TOPIC + " x"));
        assertFalse("斜杠不是合法字符", NtfyRequest.looksLikeTopic("zeekrsc_/abcdefghijklmnopqrstuvwx"));
        assertFalse(NtfyRequest.looksLikeTopic(null));
        assertFalse(NtfyRequest.looksLikeTopic(""));
        assertFalse("超过 64 也不行", NtfyRequest.looksLikeTopic(
                "zeekrsc_" + "abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz"));
    }

    @Test
    public void serverAddressIsNormalised() {
        assertEquals("", NtfyRequest.normalizeServer(""));
        assertEquals("", NtfyRequest.normalizeServer(null));
        assertEquals("https://ntfy.sh", NtfyRequest.normalizeServer("ntfy.sh"));
        assertEquals("https://ntfy.sh", NtfyRequest.normalizeServer("  https://ntfy.sh/  "));
        assertEquals("http://car.local:8080", NtfyRequest.normalizeServer("http://car.local:8080/"));
        assertEquals("空地址按默认算", "https://ntfy.sh", NtfyRequest.server(""));
        assertEquals("带路径的不收", "", NtfyRequest.normalizeServer("https://example.com/ntfy"));
    }

    @Test
    public void textGoesInTheBodyAndTheCaptionGoesInTheQuery() {
        assertEquals("https://ntfy.sh/" + TOPIC, NtfyRequest.textUrl("", TOPIC));

        String caption = "Motion at the car\n2026-11-15 11:46\nSentry: armed";
        String url = NtfyRequest.photoUrl("https://ntfy.sh", TOPIC, caption, "Zeekr", "alert.jpg");
        assertTrue("换行必须编成 %0A: " + url, url.contains("%0A"));
        assertTrue("说明在查询串里: " + url, url.contains("message=Motion+at+the+car"));
        assertTrue("文件名在: " + url, url.contains("filename=alert.jpg"));
        assertTrue("高优先级: " + url, url.contains("priority=4"));
        assertTrue("写明了是车、是警报: " + url, url.contains("tags=car%2Cwarning"));
    }

    @Test
    public void deepLinkMatchesTheFormatTheAppUnderstands() {
        assertEquals("ntfy://ntfy.sh/" + TOPIC + "?display=Zeekr",
                NtfyRequest.deepLink("https://ntfy.sh", TOPIC, "Zeekr"));
        assertEquals("ntfy://car.local:8080/" + TOPIC,
                NtfyRequest.deepLink("http://car.local:8080", TOPIC, ""));
    }

    @Test
    public void theTokenIsRedacted() {
        String token = "tk_secret_value_123";
        assertFalse(NtfyRequest.redact("failed with " + token, token).contains(token));
        assertTrue(NtfyRequest.redact("failed with " + token, token).contains(NtfyRequest.REDACTED));
        assertEquals("空令牌时原样返回", "nothing here", NtfyRequest.redact("nothing here", ""));
    }

    @Test
    public void photoCeilingFollowsTheTier() {
        assertEquals(2 * 1024 * 1024, NtfyRequest.maxPhotoBytes(false));
        assertEquals(25 * 1024 * 1024, NtfyRequest.maxPhotoBytes(true));
    }

    @Test
    public void httpCodesBecomeNamesTheUiCanSay() {
        assertEquals(AlertChannel.Kind.OK, NtfySender.kind(200));
        assertEquals(AlertChannel.Kind.REJECTED, NtfySender.kind(403));
        assertEquals(AlertChannel.Kind.NOT_FOUND, NtfySender.kind(404));
        assertEquals(AlertChannel.Kind.TOO_LARGE, NtfySender.kind(413));
        assertEquals(AlertChannel.Kind.RATE_LIMITED, NtfySender.kind(429));
        assertEquals(AlertChannel.Kind.NETWORK, NtfySender.kind(500));
    }
}
