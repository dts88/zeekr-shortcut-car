/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import android.content.Context;

import com.kooo.evcam.alarm.media.SnapshotTaker;

/**
 * 「拍这一张」这一步，换成数次数：拍了没有、拍了几次。
 *
 * <h3>为什么数次数就够</h3>
 *
 * <p>警报附不附图这条设置，真正要证明的只有一件事：关掉之后<b>车有没有去拍照</b>。不是「拍完没用」，
 * 是根本不动相机 —— 四路、拼一张 2×2、压到 100 kB 以下，在车机上是几百毫秒。所以这里既不画图也不
 * 调回调，只把「有人叫了拍照」记下来；真要走到发图那一步的测试得另配一套阴影，这里不需要。</p>
 *
 * <p>Counts the calls to "take the picture", nothing else. The setting is only worth anything if the
 * camera is never touched when it is off — not "captured and thrown away" — so this records the call and
 * deliberately never invokes the callback: a test that needs the send path sets up its own shadows.</p>
 */
@Implements(SnapshotTaker.class)
public class ShadowSnapshotTaker {

    /** 叫了几次拍照。How many times a capture was asked for. */
    public static volatile int taken;

    public static void reset() {
        taken = 0;
    }

    @Implementation
    public static void take(Context context, SnapshotTaker.Callback callback) {
        // 故意不回调：这一层只回答「要不要拍」。Deliberately no callback: this layer answers "did it
        // try", and the send path has its own test.
        taken++;
    }
}
