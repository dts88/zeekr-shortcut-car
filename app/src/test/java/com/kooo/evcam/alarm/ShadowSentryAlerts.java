/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import com.kooo.evcam.R;
import com.kooo.evcam.alarm.media.PictureBudget;
import com.kooo.evcam.alarm.telegram.TelegramSender;

import java.util.ArrayList;
import java.util.List;

/**
 * 截图测试里的「网络」：向导问什么，这里答什么。
 *
 * <p>向导那三步全靠 {@link SentryAlerts} 的三个方法往回说话（令牌认不认、找到哪些会话、照片发没发出去），
 * 而它们背后是 HTTP。截图不该因为这台电脑连不上 api.telegram.org 就少几张图，也不该为了截图去改产品代码 ——
 * 所以只在测试里把这三个方法换成「照剧本回答」，向导本身、它的对话框、它的措辞都还是真的。</p>
 *
 * <p>The network, for the screenshot tests: whatever the wizard asks, this answers.</p>
 *
 * <p>All three wizard steps depend on three {@link SentryAlerts} methods to speak back (is the token
 * accepted, which chats were found, did the picture go out), and behind them is HTTP. A screenshot
 * run must not lose steps because this machine cannot reach api.telegram.org, and it must not change
 * product code to take pictures — so only these three methods are replaced, in the test, with a script.
 * The wizard itself, its dialogs and its wording stay real.</p>
 */
@Implements(SentryAlerts.class)
public class ShadowSentryAlerts {

    /** 令牌认不认。Whether Telegram is said to accept the token. */
    public static boolean tokenAccepted = true;
    /** 找会话号找到的（默认一个也没有）。What "find my chat" comes back with; empty by default. */
    public static final List<TelegramSender.Chat> chats = new ArrayList<>();
    /** 非空时 findChats 走失败那一条。When not null, findChats fails with this message. */
    public static String chatsFailure;
    /** 照片发没发出去。Whether the test picture is said to have arrived. */
    public static boolean pictureSent = true;

    public static void reset() {
        tokenAccepted = true;
        chats.clear();
        chatsFailure = null;
        pictureSent = true;
    }

    /** 造一个会话，名字和号都是编的。Add one invented chat. */
    public static void addChat(String title, String id, String userId) {
        chats.add(new TelegramSender.Chat(id, title, userId));
    }

    private static String text(int id, Object... args) {
        return RuntimeEnvironment.getApplication().getString(id, args);
    }

    @Implementation
    public void checkToken(SentryAlerts.Callback callback) {
        callback.onResult(tokenAccepted, tokenAccepted
                ? text(R.string.set_alerts_token_ok, "zeekr_shortcut_bot")
                : text(R.string.set_alerts_token_rejected));
    }

    @Implementation
    public void findChats(SentryAlerts.ChatListCallback callback) {
        if (chatsFailure != null) {
            callback.onFailed(chatsFailure);
            return;
        }
        callback.onChats(new ArrayList<>(chats));
    }

    @Implementation
    public void sendTestPicture(SentryAlerts.Callback callback) {
        sendTestPicture(callback, false);
    }

    @Implementation
    public void sendTestPictureHighQuality(SentryAlerts.Callback callback) {
        sendTestPicture(callback, true);
    }

    private static void sendTestPicture(SentryAlerts.Callback callback, boolean highQuality) {
        callback.onResult(pictureSent, pictureSent
                ? text(R.string.set_alerts_test_size, PictureBudget.size(highQuality ? 480_000 : 96_000))
                : text(R.string.set_alerts_failed, "api.telegram.org"));
    }

    /** 截图里不需要真的去开轮询线程。No need to really start the polling thread for a screenshot. */
    @Implementation
    public void refreshConfig() {
    }
}
