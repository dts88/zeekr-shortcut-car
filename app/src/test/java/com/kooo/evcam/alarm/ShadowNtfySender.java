/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import com.kooo.evcam.alarm.ntfy.NtfySender;

/**
 * 截图测试里的 ntfy 服务器：收到了（`NtfySetupDialog` 那一步只负责把那条带四位数的通知发出去，
 * 手机上收没收到由车主填回来说）。不发的话测试会去连真的 ntfy.sh，慢，而且不该连。
 *
 * <p>The ntfy server, for the screenshot tests: it accepted the message. (That step of
 * `NtfySetupDialog` only sends the notification carrying four digits; whether the phone got it
 * is answered by the owner typing them back.) Without this the test would reach for the real
 * ntfy.sh — slow, and it has no business doing that.</p>
 */
@Implements(NtfySender.class)
public class ShadowNtfySender {

    /** 服务器收不收。Whether the server is said to accept it. */
    public static boolean accepted = true;

    /** 文字发了几次、图发了几次 —— 「只发文字」那条设置就靠这两个数证明。 */
    /** Text sends and picture sends, counted; "text only" is proven with these two numbers. */
    public static volatile int texts;
    public static volatile int photos;

    /** 有一次发送落地就放开：发送在别的线程上，测试要看的是「最终发了什么」。 */
    /** Opened by the first send that lands: sending happens on another thread. */
    public static volatile java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);

    public static void reset() {
        accepted = true;
        texts = 0;
        photos = 0;
        latch = new java.util.concurrent.CountDownLatch(1);
    }

    @Implementation
    public static AlertChannel.Result sendText(String server, String topic, String token,
                                              String text, String title) {
        texts++;
        latch.countDown();
        return AlertChannel.Result.ok();
    }

    @Implementation
    public static AlertChannel.Result sendPhoto(String server, String topic, String token,
                                                byte[] jpeg, String caption, String title,
                                                boolean paid) {
        photos++;
        latch.countDown();
        return AlertChannel.Result.ok();
    }

    @Implementation
    public static AlertChannel.Result probe(String server, String topic, String token, String text) {
        return accepted
                ? AlertChannel.Result.ok()
                : AlertChannel.Result.fail(AlertChannel.Kind.NETWORK, 0, "ntfy.sh");
    }
}
