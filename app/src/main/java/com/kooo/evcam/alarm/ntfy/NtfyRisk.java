/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.ntfy;

/**
 * 话题的风险提示同意过没有 —— 纯函数，一个字符串。
 *
 * <h3>为什么按话题、按版本存</h3>
 *
 * <p>同意的是「这个话题是公开的，风险我担」，不是「以后都别再问我」。所以存的是
 * {@code 版本|话题末八位}：换了话题、改了一版提示的措辞，都要重新同意 —— 换话题时的「更换话题？」本身就是这一句
 * （新话题同样公开），点了「更换」就记下新话题的同意。同意和话题一起存在不导出的那一份里（{@code alarm.AlertStore}），
 * 设置备份恢复到另一台车上带不走 —— 那是故意的。</p>
 *
 * <h3>Why per topic and per version</h3>
 *
 * <p>What was agreed is "this topic is public and the risk is mine", not "never ask me again". So the
 * stored form is {@code version|last eight of topic}: a new topic or a new wording asks again — the
 * change-topic confirmation says the same thing about the new topic, so confirming it records consent for
 * that topic. Consent lives with the topic in the store that is never exported ({@code alarm.AlertStore}),
 * so a settings backup restored onto another car carries none of it — deliberately.</p>
 */
public final class NtfyRisk {

    /**
     * 提示的措辞版本。「开启哨兵提醒？」那段话（{@code dlg_sentry_alerts_risk_body}）改了意思就把它加一：
     * 不能让人替自己没读过的字点头。
     *
     * The wording version. Bump it when the meaning of the risk notice ({@code dlg_sentry_alerts_risk_body})
     * changes: nobody should be counted as having accepted words they never read.
     */
    public static final String WARNING_VERSION = "1";

    private NtfyRisk() {
    }

    /** 记下来的形状：{@code 版本|话题末八位}。The stored shape. */
    public static String ackFor(String topic) {
        String value = topic == null ? "" : topic.trim();
        if (value.isEmpty()) {
            return "";
        }
        String tail = value.length() <= 8 ? value : value.substring(value.length() - 8);
        return WARNING_VERSION + "|" + tail;
    }

    /**
     * 这个话题、这一版措辞，接受过没有。
     *
     * <p>话题为空、记下来的为空、形状对不上，都算没接受 —— 宁可多问一次，不能少问一次。</p>
     *
     * Whether this topic with this wording has been accepted. An empty topic, an empty record or a
     * mismatched shape all count as not accepted: asking once too often is the cheaper mistake.
     */
    public static boolean accepted(String topic, String stored) {
        String expected = ackFor(topic);
        return !expected.isEmpty() && expected.equals(stored == null ? "" : stored.trim());
    }
}
