/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.R;
import com.kooo.evcam.alarm.ntfy.NtfyRequest;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;

import org.junit.Test;

import java.util.List;

/**
 * 「检查链路」那几行的凑法：只有事实，没有网络，没有 Robolectric。
 *
 * <p>钉的是四件事：</p>
 *
 * <ol>
 *   <li>话题认不出就换成一句说明，不显示空的末四位。</li>
 *   <li>免费方案提醒、付费令牌各有一句话，接受过就是接受过。</li>
 *   <li>服务器答了、没答、答了但这一问没过，三种分开。</li>
 *   <li>令牌不进任何一句：详细里带着令牌，写进去的是擦过的那一份。</li>
 * </ol>
 *
 * The lines of "Check the link", assembled from facts only — no network, no Robolectric. Four things
 * are pinned: an unparseable topic becomes its own sentence rather than a blank tail; the warning and
 * the token each get one sentence; answered, unanswered and answered-but-refused stay apart; and the
 * token never enters a line — a detail carrying it is redacted first.
 */
public class ProbeReportTest {

    private static final String TOPIC = "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";
    private static final String TOKEN = "tk_deadbeefdeadbeefdeadbeefdeadbeef";

    @Test
    public void aValidTopicShowsTheServerAndTheLastFourOfTheTopic() {
        List<ProbeReport.Line> lines =
                ProbeReport.ntfyFacts("https://ntfy.sh/", TOPIC, NtfyRisk.ackFor(TOPIC), "");
        assertEquals(R.string.set_alerts_probe_ntfy_target, lines.get(0).resId);
        assertEquals("ntfy.sh", lines.get(0).args[0]);
        assertEquals(TOPIC.substring(TOPIC.length() - 4), lines.get(0).args[1]);
        assertEquals(R.string.set_alerts_probe_ntfy_risk_ok, lines.get(1).resId);
        assertEquals(R.string.set_alerts_probe_ntfy_token_none, lines.get(2).resId);
    }

    @Test
    public void anUnparseableTopicSaysSoInsteadOfShowingAnEmptyTail() {
        List<ProbeReport.Line> lines = ProbeReport.ntfyFacts("", "short", "", "");
        assertEquals(R.string.set_alerts_probe_ntfy_no_topic, lines.get(0).resId);
        assertEquals(0, lines.get(0).args.length);
        assertEquals("ntfy.sh", ProbeReport.host(""));
        // 空话题同样算「不能用」：没话题的时候不能报成「末四位 」。
        // An empty topic is unusable too: no topic must never read as "ends in ".
        assertEquals(R.string.set_alerts_probe_ntfy_no_topic,
                ProbeReport.ntfyFacts("", null, null, null).get(0).resId);
    }

    @Test
    public void aNewTopicOrANewWordingAsksForTheWarningAgain() {
        List<ProbeReport.Line> fresh = ProbeReport.ntfyFacts("", TOPIC, NtfyRisk.ackFor("othertopic1234"), "");
        assertEquals(R.string.set_alerts_probe_ntfy_risk_missing, fresh.get(1).resId);
    }

    @Test
    public void aSetTokenIsReportedAsSetAndNeverAsAValue() {
        List<ProbeReport.Line> lines = ProbeReport.ntfyFacts("", TOPIC, NtfyRisk.ackFor(TOPIC), TOKEN);
        assertEquals(R.string.set_alerts_probe_ntfy_token_set, lines.get(2).resId);
        assertEquals(0, lines.get(2).args.length);
        assertFalse(joinedArgs(lines).contains(TOKEN));
    }

    @Test
    public void answeredUnansweredAndRefusedStayApart() {
        ProbeReport.Line ok = ProbeReport.ntfyReach("https://ntfy.sh", "", AlertChannel.Kind.OK, 240L, "");
        assertEquals(R.string.set_alerts_probe_ntfy_reach_ok, ok.resId);
        assertEquals("ntfy.sh", ok.args[0]);
        assertEquals("240", ok.args[1]);

        ProbeReport.Line offline =
                ProbeReport.ntfyReach("https://ntfy.sh", "", AlertChannel.Kind.NETWORK, 15_000L, "timeout");
        assertEquals(R.string.set_alerts_probe_ntfy_reach_fail, offline.resId);
        assertEquals("timeout", offline.args[1]);

        ProbeReport.Line refused =
                ProbeReport.ntfyReach("https://ntfy.sh", TOKEN, AlertChannel.Kind.REJECTED, 120L, "401 refused");
        assertEquals(R.string.set_alerts_probe_ntfy_reach_reply, refused.resId);
        assertEquals("401 refused", refused.args[1]);

        // 不知道种类就按「没答」算：宁可说没连上，也不能替服务器点头。
        // An unknown kind counts as no answer: better to say "cannot reach" than to nod for the server.
        assertEquals(R.string.set_alerts_probe_ntfy_reach_fail,
                ProbeReport.ntfyReach("https://ntfy.sh", "", null, 1L, "").resId);
    }

    @Test
    public void theTokenIsRedactedBeforeItReachesASentence() {
        ProbeReport.Line line = ProbeReport.ntfyReach("https://ntfy.sh", TOKEN,
                AlertChannel.Kind.REJECTED, 90L, "GET https://ntfy.sh/v1/account token " + TOKEN);
        assertFalse(joinedArgs(line).contains(TOKEN));
        assertTrue(String.valueOf(line.args[1]).contains(NtfyRequest.REDACTED));
    }

    @Test
    public void telegramKeepsItsTwoSentences() {
        ProbeReport.Line ok = ProbeReport.telegramReach(true, "zeekr_bot", 310L, "");
        assertEquals(R.string.set_alerts_probe_reach_ok, ok.resId);
        assertEquals("310", ok.args[0]);
        assertEquals("zeekr_bot", ok.args[1]);

        ProbeReport.Line failed = ProbeReport.telegramReach(false, "", 0L, "401 Unauthorized");
        assertEquals(R.string.set_alerts_probe_reach_fail, failed.resId);
        assertEquals("401 Unauthorized", failed.args[0]);
    }

    @Test
    public void theHostDropsTheSchemeAndTheTrailingSlash() {
        assertEquals("ntfy.sh", ProbeReport.host("ntfy.sh"));
        assertEquals("ntfy.sh", ProbeReport.host("http://ntfy.sh/"));
        assertEquals("192.168.50.9:2586", ProbeReport.host("https://192.168.50.9:2586"));
        // 带路径的地址本来就不算地址（{@link NtfyRequest#normalizeServer}），那就报真正在用的那个：
        // 报告里显示一个发不出去的地址，比显示默认的更误导人。
        // An address with a path is not an address at all (NtfyRequest#normalizeServer), so the host
        // reported is the one actually in use: showing an address nothing sends to misleads more than
        // showing the default.
        assertEquals("ntfy.sh", ProbeReport.host("https://ntfy.mydomain.net/some/path"));
    }

    @Test
    public void aShortTopicSurvivesWholeAndALongOneOnlyInItsTail() {
        assertEquals("ab12", ProbeReport.tail("xxab12"));
        assertEquals("ab", ProbeReport.tail("ab"));
        assertEquals("", ProbeReport.tail(null));
    }

    private static String joinedArgs(ProbeReport.Line line) {
        StringBuilder out = new StringBuilder();
        for (Object arg : line.args) {
            out.append(arg).append('|');
        }
        return out.toString();
    }

    private static String joinedArgs(List<ProbeReport.Line> lines) {
        StringBuilder out = new StringBuilder();
        for (ProbeReport.Line line : lines) {
            out.append(joinedArgs(line));
        }
        return out.toString();
    }
}
