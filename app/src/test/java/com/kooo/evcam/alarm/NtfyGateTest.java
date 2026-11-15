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
 * ntfy 这道门、这张风险单：两个纯函数类，不碰 Android、不碰网络。
 *
 * <p>钉的是 {@code docs/ntfy/DECISIONS.md} D13/D14 里那几条：开发者选项关着就是 Telegram；
 * 风险提示按「话题 + 措辞版本」存，换话题、换版本都要重新点头。</p>
 *
 * The gate and the risk slip: two pure classes, no Android and no network. What is pinned is D13/D14 —
 * developer options closed means Telegram, and the warning is recorded per topic and per wording
 * version, so a new topic or a new wording asks again.
 */
public class NtfyGateTest {

    private static final String TOPIC = "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

    @Test
    public void developerOptionsClosedMeansTelegram() {
        assertEquals(NtfyGate.TELEGRAM, NtfyGate.effective(false, NtfyGate.NTFY));
        assertEquals(NtfyGate.TELEGRAM, NtfyGate.effective(false, NtfyGate.TELEGRAM));
        assertFalse(NtfyGate.available(false, NtfyGate.NTFY));
    }

    @Test
    public void developerOptionsOpenFollowsTheStoredChoice() {
        assertEquals(NtfyGate.NTFY, NtfyGate.effective(true, NtfyGate.NTFY));
        assertEquals(NtfyGate.TELEGRAM, NtfyGate.effective(true, NtfyGate.TELEGRAM));
        assertTrue(NtfyGate.available(true, NtfyGate.NTFY));
    }

    @Test
    public void unknownStoredValuesFallBackToTelegram() {
        assertEquals(NtfyGate.TELEGRAM, NtfyGate.effective(true, ""));
        assertEquals(NtfyGate.TELEGRAM, NtfyGate.effective(true, null));
        assertEquals(NtfyGate.TELEGRAM, NtfyGate.effective(true, "telepathy"));
    }

    @Test
    public void theAckIsPerTopic() {
        String ack = NtfyRisk.ackFor(TOPIC);
        assertTrue(NtfyRisk.accepted(TOPIC, ack));
        assertFalse("换了话题就要重新问", NtfyRisk.accepted(TOPIC + "x", ack));
        assertFalse("没有话题就没有同意", NtfyRisk.accepted("", ack));
        assertFalse(NtfyRisk.accepted(TOPIC, ""));
        assertFalse(NtfyRisk.accepted(TOPIC, null));
        assertFalse(NtfyRisk.accepted(TOPIC, "0|" + ack.substring(ack.indexOf('|') + 1)));
    }

    @Test
    public void theAckCarriesTheWarningVersion() {
        assertTrue(NtfyRisk.ackFor(TOPIC).startsWith(NtfyRisk.WARNING_VERSION + "|"));
        assertEquals("同一个话题永远给同一个串", NtfyRisk.ackFor(TOPIC), NtfyRisk.ackFor(TOPIC));
        assertEquals("话题两边的空白不算", NtfyRisk.ackFor("  " + TOPIC + " "), NtfyRisk.ackFor(TOPIC));
    }

    @Test
    public void anUnconfiguredChannelSaysWhatIsMissing() {
        NtfyChannel noTopic = new NtfyChannel("https://ntfy.sh", "", "", false, "");
        assertFalse(noTopic.ready());
        assertEquals(AlertChannel.Kind.NOT_CONFIGURED, noTopic.blockedReason());

        NtfyChannel notAcked = new NtfyChannel("https://ntfy.sh", TOPIC, "", false, "");
        assertFalse(notAcked.ready());
        assertEquals("话题有了、提醒没接受：还是不发",
                AlertChannel.Kind.NOT_ACKNOWLEDGED, notAcked.blockedReason());

        NtfyChannel ready = new NtfyChannel("https://ntfy.sh", TOPIC, "", false,
                NtfyRisk.ackFor(TOPIC));
        assertTrue(ready.ready());
        assertEquals(AlertChannel.Kind.OK, ready.blockedReason());
        assertFalse("片段只有 Telegram 能发", ready.supportsClips());
        assertEquals(NtfyGate.NTFY, ready.name());
    }
}
