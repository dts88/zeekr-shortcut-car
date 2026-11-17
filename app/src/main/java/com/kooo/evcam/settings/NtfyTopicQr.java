/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.alarm.ntfy.NtfyRequest;
import com.kooo.evcam.share.QrCode;
import com.kooo.evcam.ui.CamDialogs;

/**
 * 「在手机上收警报」那个框：二维码 + 三步说明。
 *
 * <h3>为什么单独一个类</h3>
 *
 * <p>两个地方要说同一件事：第一次配 ntfy（{@link NtfySetupDialog} 的第二步），和换话题之后
 * （设置里那一行）—— 两次画的码必须一模一样，否则「扫码订阅」这件事就有两套说法。码里装的是
 * {@code ntfy://} 深链接，扫码直接订阅，谁也不用抄字符串。</p>
 *
 * <h3>画不出码也不能卡住</h3>
 *
 * <p>{@link QrCode#encode} 失败时返回 null，那就把深链接本身印出来：仍然能订阅，只是要手动敲。</p>
 *
 * <h3>Why this is its own class</h3>
 *
 * <p>Two places say the same thing: the first setup (step two of {@link NtfySetupDialog}) and after a
 * rotation (the settings row). The code must be identical in both, or "scan to subscribe" has two
 * stories. The payload is the {@code ntfy://} deep link, so scanning subscribes and nobody copies a
 * string. When {@link QrCode#encode} fails it returns null, and the deep link is printed instead: still
 * subscribable, just typed.</p>
 */
public final class NtfyTopicQr {

    private static final int QR_SIZE_PX = 480;

    private NtfyTopicQr() {
    }

    /**
     * 弹出二维码。
     *
     * @param titleRes 这个框叫什么（第一次配和换话题说法不同）
     * @param onDone    关掉之后做什么；取消、返回键也走它
     *
     * Show the QR code. The title differs between first setup and a rotation; every exit path — the
     * button, cancel, back — runs {@code onDone}, so a caller cannot be left waiting.
     */
    public static void show(Fragment fragment, AppConfig config, int titleRes, Runnable onDone) {
        if (fragment == null || !fragment.isAdded() || fragment.getContext() == null) {
            return;
        }
        final String topic = config.getAlertsNtfyTopic();
        final String link = NtfyRequest.deepLink(config.getAlertsNtfyServer(), topic, "Zeekr");

        LinearLayout column = new LinearLayout(fragment.requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * fragment.getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad / 2, pad, pad / 2);

        Bitmap qr = QrCode.encode(link, QR_SIZE_PX);
        if (qr != null) {
            ImageView image = new ImageView(fragment.requireContext());
            image.setImageBitmap(qr);
            image.setAdjustViewBounds(true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER_HORIZONTAL;
            image.setLayoutParams(params);
            column.addView(image);
        }
        TextView steps = new TextView(fragment.requireContext());
        steps.setText(fragment.getString(R.string.set_alerts_ntfy_qr_body)
                + (qr == null ? "\n\n" + link : ""));
        steps.setLineSpacing(0f, 1.15f);
        column.addView(steps);

        CamDialogs.show(new MaterialAlertDialogBuilder(fragment.requireContext(),
                R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(titleRes)
                .setView(column)
                .setPositiveButton(R.string.set_alerts_ntfy_qr_open, (dialog, which) -> onDone.run())
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> onDone.run())
                .setOnCancelListener(dialog -> onDone.run()));
    }
}
