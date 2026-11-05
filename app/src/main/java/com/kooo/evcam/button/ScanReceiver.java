/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 灵动贴扫描的结果（PendingIntent 扫描，车主 2026-11-04 照 Lab 验证过的做法定）。系统用我们自己的身份送来，所以不导出。
 *
 * <p>应用在后台、甚至被系统收掉了，系统照样送：进程跟着起来，应用入口先装好 {@link SmartButtons}，结果交给它。
 * 用户退出了、关了灵动贴的，它只把扫描停掉、不处理（{@link SmartButtons#onScanIntent}）。</p>
 */
public final class ScanReceiver extends BroadcastReceiver {

    /** PendingIntent 里的 action：只认它。 */
    static final String ACTION = "com.kooo.evcam.button.SCAN_RESULT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        SmartButtons.install(context);
        SmartButtons buttons = SmartButtons.get();
        if (buttons != null) {
            buttons.onScanIntent(intent);
        }
    }
}
