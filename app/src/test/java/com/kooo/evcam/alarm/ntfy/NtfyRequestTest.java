/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
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
 *   <li>只走 https，填错的不悄悄改发默认的服务器；</li>
 *   <li>标题、配图的说明走查询串（HTTP 头只能装 ASCII），出错说明里不留话题名。</li>
 * </ol>
 *
 * The shape of an ntfy request, tested as pure strings. Three things pinned: the topic name's shape
 * and length (it is the password, and a short one is no door), https only with no silent fallback to
 * the default server, and the title and caption travelling in the query string (a header only carries
 * ASCII) with the topic kept out of error details.
 */
public class NtfyRequestTest {

    private static final String TOPIC = "zeekrsc_" + "aB3dE5fG7hJ9kL2mN4pQ6rS8tU0vW1xY3zA5bC7d";

    @Test
    public void generatedTopicsAreLongUniqueAndLegal() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String topic = NtfyRequest.generateTopic(1_700_000_000_000L + i, 1_699_000_000_000L, i + 1,
                    "test-build");
            assertTrue("形状不对: " + topic, NtfyRequest.looksLikeTopic(topic));
            assertTrue("生成的名字太短: " + topic, topic.length() >= 32);
            assertTrue("前缀丢了: " + topic, topic.startsWith("zeekrsc_"));
            assertTrue("撞了: " + topic, seen.add(topic));
        }
    }

    /**
     * 名字的来源：时刻、安装时刻、第几次、机型，任何一项变了都不该还是同一个名字。
     *
     * <p>随机字节每次都不同，所以这里只能钉「不同输入 → 不同输出」这个方向：五次里每一次都换一个
     * 输入，出来的名字必须全不一样。反方向（同样的输入得到同样的名字）本来就不该成立 —— 那正是
     * 随机字节在起作用。</p>
     *
     * Where the name comes from: any of the clock, the install stamp, the round or the model changing
     * should change the name. Randomness makes each call different anyway, so this pins the direction
     * that matters: five different inputs, five different names.
     */
    @Test
    public void differentSeedsGiveDifferentNames() {
        long now = 1_700_000_000_000L;
        long install = 1_699_000_000_000L;
        Set<String> seen = new HashSet<>();
        assertTrue(seen.add(NtfyRequest.generateTopic(now, install, 1, "a")));
        assertTrue(seen.add(NtfyRequest.generateTopic(now + 1, install, 1, "a")));
        assertTrue(seen.add(NtfyRequest.generateTopic(now, install + 1, 1, "a")));
        assertTrue(seen.add(NtfyRequest.generateTopic(now, install, 2, "a")));
        assertTrue(seen.add(NtfyRequest.generateTopic(now, install, 1, "b")));
    }

    /** 40 个字符每一位都在 ntfy 的字母表里，一位不多一位不少。 */
    @Test
    public void theTailIsExactlyFortyLegalCharacters() {
        String topic = NtfyRequest.generateTopic(1L, 0L, 1, null);
        assertEquals("zeekrsc_".length() + 40, topic.length());
        String tail = topic.substring("zeekrsc_".length());
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            boolean legal = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            assertTrue("尾巴里有非法字符: " + c, legal);
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
    public void serverAddressIsHttpsOnly() {
        assertEquals("", NtfyRequest.normalizeServer(""));
        assertEquals("", NtfyRequest.normalizeServer(null));
        assertEquals("https://ntfy.sh", NtfyRequest.normalizeServer("ntfy.sh"));
        assertEquals("https://ntfy.sh", NtfyRequest.normalizeServer("  https://ntfy.sh/  "));
        assertEquals("https://ntfy.sh", NtfyRequest.normalizeServer("HTTPS://NTFY.SH"));
        assertEquals("https://car.example:8443", NtfyRequest.normalizeServer("https://car.example:8443/"));
        assertEquals("https://car.example:65535", NtfyRequest.normalizeServer("car.example:65535"));
        assertEquals("端口 0 不收", "", NtfyRequest.normalizeServer("https://car.example:0"));
        assertEquals("端口超过 65535 不收", "", NtfyRequest.normalizeServer("https://car.example:84430"));
        assertEquals("http 不收", "", NtfyRequest.normalizeServer("http://car.local:8080/"));
        assertEquals("别的协议不收", "", NtfyRequest.normalizeServer("ftp://ntfy.sh"));
        assertEquals("带路径的不收", "", NtfyRequest.normalizeServer("https://example.com/ntfy"));
        assertEquals("带查询串的不收", "", NtfyRequest.normalizeServer("https://example.com?x=1"));
        assertEquals("带用户名的不收", "", NtfyRequest.normalizeServer("https://me@example.com"));
        assertEquals("有空格的不收", "", NtfyRequest.normalizeServer("ntfy sh"));
    }

    @Test
    public void anEmptyServerIsTheDefaultButABadOneIsNeverReplacedByIt() {
        assertEquals("https://ntfy.sh", NtfyRequest.server(""));
        assertEquals("https://ntfy.sh", NtfyRequest.server(null));
        assertEquals("ntfy.sh", NtfyRequest.host(""));
        assertEquals("填坏的不改发默认的", "", NtfyRequest.server("http://car.local"));
        assertEquals("", NtfyRequest.textUrl("http://car.local", TOPIC, "t"));
        assertEquals("", NtfyRequest.deepLink("http://car.local", TOPIC, "Zeekr"));
    }

    @Test
    public void theTitleAndTheCaptionGoInTheQuery() {
        String text = NtfyRequest.textUrl("", TOPIC, "哨兵提醒");
        assertTrue("文字发到话题上: " + text, text.startsWith("https://ntfy.sh/" + TOPIC + "?"));
        assertTrue("中文标题编进查询串: " + text, text.contains("title=%E5%93%A8%E5%85%B5%E6%8F%90%E9%86%92"));
        assertTrue("高优先级: " + text, text.contains("priority=4"));
        assertTrue("写明了是车、是警报: " + text, text.contains("tags=car%2Cwarning"));

        String caption = "双闪亮起 · 11-17 21:05\nline two";
        String url = NtfyRequest.photoUrl("https://ntfy.sh", TOPIC, "Sentry alert", caption, "alert.jpg");
        assertTrue("换行必须编成 %0A: " + url, url.contains("%0A"));
        assertTrue("说明在查询串里: " + url, url.contains("message=%E5%8F%8C%E9%97%AA"));
        assertTrue("标题在: " + url, url.contains("title=Sentry+alert"));
        assertTrue("文件名在: " + url, url.contains("filename=alert.jpg"));
        assertTrue("高优先级: " + url, url.contains("priority=4"));
        assertTrue("写明了是车、是警报: " + url, url.contains("tags=car%2Cwarning"));
    }

    @Test
    public void nothingIsBuiltForATopicThatDoesNotLookLikeOne() {
        assertEquals("", NtfyRequest.textUrl("", "short", "t"));
        assertEquals("", NtfyRequest.photoUrl("", "", "t", "c", "a.jpg"));
        assertEquals("", NtfyRequest.deepLink("", "short", "Zeekr"));
    }

    @Test
    public void deepLinkMatchesTheFormatTheAppUnderstands() {
        assertEquals("ntfy://ntfy.sh/" + TOPIC + "?display=Zeekr",
                NtfyRequest.deepLink("https://ntfy.sh", TOPIC, "Zeekr"));
        assertEquals("ntfy://ntfy.sh/" + TOPIC + "?display=%E6%9E%81%E6%B0%AA%E5%8D%B3%E5%88%BB",
                NtfyRequest.deepLink("", TOPIC, "极氪即刻"));
        assertEquals("ntfy://car.example:8443/" + TOPIC,
                NtfyRequest.deepLink("https://car.example:8443", TOPIC, ""));
    }

    @Test
    public void errorDetailsKeepOnlyTheExceptionsName() {
        // 异常的文字里带着主机名、IP、话题：一样都不留
        assertEquals("ConnectException", NtfySender.detail(new java.net.ConnectException(
                "Failed to connect to ntfy.myhome.example/203.0.113.7:8443 for /" + TOPIC)));
        assertEquals("UnknownHostException", NtfySender.detail(
                new java.net.UnknownHostException("Unable to resolve host \"ntfy.myhome.example\"")));
        assertEquals("SecurityException", NtfySender.detail(new SecurityException("Permission denied")));
    }

    @Test
    public void aSchemeOtherThanHttpsIsToldApartFromABadAddress() {
        assertTrue(NtfyRequest.wrongScheme("http://car.local:8080"));
        assertTrue(NtfyRequest.wrongScheme(" FTP://ntfy.sh"));
        assertFalse(NtfyRequest.wrongScheme("https://example.com/ntfy"));
        assertFalse(NtfyRequest.wrongScheme("example.com/ntfy"));
        assertFalse(NtfyRequest.wrongScheme(""));
        assertFalse(NtfyRequest.wrongScheme(null));
    }

    @Test
    public void httpCodesBecomeNamesTheUiCanSay() {
        assertEquals(AlertChannel.Kind.OK, NtfySender.kind(200));
        assertEquals(AlertChannel.Kind.REJECTED, NtfySender.kind(403));
        assertEquals(AlertChannel.Kind.NOT_FOUND, NtfySender.kind(404));
        assertEquals(AlertChannel.Kind.TOO_LARGE, NtfySender.kind(413));
        assertEquals(AlertChannel.Kind.RATE_LIMITED, NtfySender.kind(429));
        assertEquals(AlertChannel.Kind.REFUSED, NtfySender.kind(500));
        assertEquals(AlertChannel.Kind.REFUSED, NtfySender.kind(400));
    }
}
