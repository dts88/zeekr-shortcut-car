/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.alarm.ntfy.NtfyChannel;
import com.kooo.evcam.alarm.ntfy.NtfyRequest;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;

/**
 * 哨兵提醒存的东西（ui-spec §十六「录制 → 哨兵提醒」）：开没开、用哪个手机订阅服务、附不附画面、ntfy 的话题和服务器、
 * 风险提示同意过哪一个话题、上一次提醒的结果。
 *
 * <h3>为什么不放进 AppConfig</h3>
 *
 * <p>话题名就是钥匙：知道它就能看到提醒和画面。设置备份整份带走 app_config（导出的文件、诊断报告里都有这一份），
 * 诊断报告还逐项列出 app_config 的原始值 —— 话题、服务器放在那里就会跟着出去；导入别人的设置文件还能把提醒改发到别处。
 * 所以这一份单独一个文件（{@link #FILE}）：不导出、不进诊断报告、不进安卓系统的备份（backup_rules、data_extraction_rules
 * 里排除了），话题、服务器也不写黑匣子。开没开也放在这里：
 * 导入设置不会把提醒打开。</p>
 *
 * <h3>开没开、用哪个服务</h3>
 *
 * <p>设置里是一行「关闭 · ntfy · Telegram」（ui-spec §三「开关合进分段」），存的还是两样：开没开、用哪个服务。
 * 选「关闭」只关，话题、同意都留着，再选 ntfy 直接打开。Telegram 还没做，现在只认 ntfy。</p>
 *
 * <p>The sentry alerts' own store: on or off, which phone subscription service, whether to attach a
 * picture, the ntfy topic and server, which topic the risk notice was accepted for, and the last result.
 * It is a separate file on purpose: the topic name is the key to the alerts, and the settings backup and
 * the diagnostics report both carry app_config whole. Nothing here is exported, put in a report, kept in
 * Android's own backup or (topic
 * and server) written to the blackbox, and importing settings never turns alerts on.</p>
 */
public final class AlertStore {

    /** 单独的那个文件：不在设置备份的两份存储里（{@code settings.SettingsBackup}）。Not one of the backed-up stores. */
    public static final String FILE = "sentry_alerts";

    public static final String SERVICE_NTFY = "ntfy";
    /** 还没做：设置里摆出来、置灰（项目所有者 2026-11-18）。Not built yet: shown grayed in settings. */
    public static final String SERVICE_TELEGRAM = "telegram";

    private static final String KEY_ON = "on";
    private static final String KEY_SERVICE = "service";
    private static final String KEY_PICTURE = "picture";
    private static final String KEY_NTFY_TOPIC = "ntfy_topic";
    private static final String KEY_NTFY_SERVER = "ntfy_server";
    private static final String KEY_NTFY_RISK = "ntfy_risk";
    private static final String KEY_TOPIC_ROUND = "topic_round";
    private static final String KEY_LAST_AT = "last_at";
    private static final String KEY_LAST_OK = "last_ok";
    private static final String KEY_LAST_KIND = "last_kind";
    private static final String KEY_LAST_CODE = "last_code";

    private final SharedPreferences prefs;

    public AlertStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public static AlertStore of(Context context) {
        return new AlertStore(context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    // ================================================================= 开没开、用哪个服务

    public boolean isOn() {
        return prefs.getBoolean(KEY_ON, false);
    }

    /** 用哪个服务：现在只有 ntfy（存着别的也按 ntfy 算）。Which service: only ntfy for now. */
    public String service() {
        return SERVICE_NTFY;
    }

    /** 照现在存着的话题、同意打开 ntfy（没有话题、没同意过的，先走「开启哨兵提醒？」，{@link #newTopic}）。 */
    public void turnOnNtfy() {
        prefs.edit().putBoolean(KEY_ON, true).putString(KEY_SERVICE, SERVICE_NTFY).apply();
    }

    /** 关掉所有手机订阅服务；话题、同意、服务器都留着。Off for every service; topic, consent and server stay. */
    public void turnOff() {
        prefs.edit().putBoolean(KEY_ON, false).apply();
    }

    /** 附不附画面（对所有服务都管用），默认附。Whether to attach a picture, for every service; on by default. */
    public boolean picture() {
        return prefs.getBoolean(KEY_PICTURE, true);
    }

    public void setPicture(boolean on) {
        prefs.edit().putBoolean(KEY_PICTURE, on).apply();
    }

    /** 开着、配好了，提醒真的会发。On and set up, so alerts really go out. */
    public boolean ready() {
        return isOn() && channel().ready();
    }

    // ================================================================= ntfy

    public String topic() {
        return prefs.getString(KEY_NTFY_TOPIC, "");
    }

    /** 存着的服务器；空串是默认的那一个（{@link NtfyRequest#DEFAULT_SERVER}）。The stored server; empty is the default. */
    public String server() {
        return prefs.getString(KEY_NTFY_SERVER, "");
    }

    /**
     * 换服务器（已经认过一次的 {@code https://主机名}，{@link NtfyRequest#normalizeServer}）；默认的那一个存成空串。
     *
     * @return 真的换了
     */
    public boolean setServer(String normalized) {
        String value = NtfyRequest.normalizeServer(normalized);
        if (value.isEmpty()) {
            return false;
        }
        String stored = NtfyRequest.DEFAULT_SERVER.equals(value) ? "" : value;
        if (stored.equals(server())) {
            return false;
        }
        prefs.edit().putString(KEY_NTFY_SERVER, stored).apply();
        return true;
    }

    /** 这个话题同意过风险提示，可以用（{@link NtfyRisk}）。The topic exists and its risk notice was accepted. */
    public boolean ntfyAccepted() {
        return NtfyRequest.looksLikeTopic(topic()) && NtfyRisk.accepted(topic(), prefs.getString(KEY_NTFY_RISK, ""));
    }

    /**
     * 车主同意了风险提示（「开启哨兵提醒？」里点了「开启」，或者「更换话题？」里点了「更换」）：生成一个新话题、记下对它的同意。
     * 第一次开启时还没有话题；话题有了而同意过期了（提示改过一版，{@link NtfyRisk#WARNING_VERSION}）也走这里，换一个新的。
     *
     * @param installedAt 这份安装装上的时刻；只用来防撞（{@link NtfyRequest#generateTopic}）
     * @param buildSalt   机型 / 编译指纹，只用来防撞
     * @return 新话题
     */
    public String newTopic(long now, long installedAt, String buildSalt) {
        int round = prefs.getInt(KEY_TOPIC_ROUND, 0) + 1;
        String topic = NtfyRequest.generateTopic(now, installedAt, round, buildSalt);
        prefs.edit().putInt(KEY_TOPIC_ROUND, round).putString(KEY_NTFY_TOPIC, topic)
                .putString(KEY_NTFY_RISK, NtfyRisk.ackFor(topic)).apply();
        return topic;
    }

    /** 话题的最后 4 位（界面上「尾号 a1b2」）；没有话题时是空串。The last four characters of the topic. */
    public String topicTail() {
        String topic = topic();
        return topic.length() < 4 ? "" : topic.substring(topic.length() - 4);
    }

    /** 发提醒用的出口。The exit alerts go through. */
    public AlertChannel channel() {
        return new NtfyChannel(server(), topic(), prefs.getString(KEY_NTFY_RISK, ""));
    }

    // ================================================================= 上一次提醒

    /** 记下这一次提醒的结果（设置里说明下面那一行小字）。Record how the last alert went. */
    public void noteResult(long atMs, AlertChannel.Result result) {
        prefs.edit().putLong(KEY_LAST_AT, atMs).putBoolean(KEY_LAST_OK, result.ok)
                .putString(KEY_LAST_KIND, result.kind.name()).putInt(KEY_LAST_CODE, result.httpCode).apply();
    }

    /** 上一次提醒的时刻（系统时间）；还没发过是 0。 */
    public long lastAt() {
        return prefs.getLong(KEY_LAST_AT, 0L);
    }

    public boolean lastOk() {
        return prefs.getBoolean(KEY_LAST_OK, true);
    }

    public AlertChannel.Kind lastKind() {
        try {
            return AlertChannel.Kind.valueOf(prefs.getString(KEY_LAST_KIND, AlertChannel.Kind.OK.name()));
        } catch (IllegalArgumentException e) {
            return AlertChannel.Kind.NETWORK;
        }
    }

    public int lastCode() {
        return prefs.getInt(KEY_LAST_CODE, 0);
    }
}
