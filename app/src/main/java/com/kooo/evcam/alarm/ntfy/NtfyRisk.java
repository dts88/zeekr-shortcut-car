/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.ntfy;

/**
 * 免费话题的风险提示接受过没有 —— 纯函数，一个字符串。
 *
 * <h3>为什么按话题、按版本存</h3>
 *
 * <p>接受的是「这个话题是公开的，风险我担」，不是「以后都别再问我」。所以存的是
 * {@code 版本|话题末八位}：换话题、换服务器、或者改了一版提醒的措辞，都要重新问一次。
 * 设置备份恢复到另一台车上也带不走同意 —— 那是故意的。</p>
 *
 * <h3>Why per topic and per version</h3>
 *
 * <p>What was agreed is "this topic is public and the risk is mine", not "never ask me again". So the
 * stored form is {@code version|last eight of topic}: a new topic, a new server, or a new wording all
 * ask again. A settings backup restored onto another car carries no consent with it — deliberately.</p>
 */
public final class NtfyRisk {

    /**
     * 提醒的措辞版本。改了那段话就把它加一：不能让人替自己没读过的字点头。
     *
     * <p>{@code docs/ntfy/WIZARD-COPY.md} 里那段六条理由改了字，就要动这个数字。</p>
     *
     * The wording version. Bump it when the warning text changes: nobody should be counted as having
     * accepted words they never read.
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
