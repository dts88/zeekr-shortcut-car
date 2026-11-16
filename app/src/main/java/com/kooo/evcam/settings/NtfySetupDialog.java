/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.graphics.Bitmap;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.share.QrCode;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.alarm.AlertChannel;
import com.kooo.evcam.alarm.ntfy.NtfyRequest;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;
import com.kooo.evcam.alarm.ntfy.NtfySender;

import java.security.SecureRandom;

/**
 * 把 ntfy 配好：接受风险 → 扫码 → 填回四个数字。
 *
 * <h3>为什么是三步</h3>
 *
 * <ol>
 *   <li><b>风险先说清楚。</b>免费话题是公开的，这个话必须在生成名字、露出二维码<b>之前</b>说。
 *       先说再给码；倒过来只是让「同意」更容易拿到，不是更明白。</li>
 *   <li><b>扫码。</b>深链接 {@code ntfy://} 直接订阅，车主不用抄任何字符串。</li>
 *   <li><b>填回四个数字。</b>ntfy 没有回执，服务器收了不等于手机收到 —— 这一步是唯一能证明
 *       整条路通了的东西（{@code docs/ntfy/FINDINGS.md} §4.5）。</li>
 * </ol>
 *
 * <h3>Three steps</h3>
 *
 * <ol>
 *   <li><b>The risk first.</b> A free topic is public, and that has to be said before a name exists or
 *       a QR is drawn. Consent first, then the code; the other order only makes the answer easier to
 *       get, not better informed.</li>
 *   <li><b>Scan.</b> The {@code ntfy://} deep link subscribes the app, so nobody copies a string.</li>
 *   <li><b>Type four digits back.</b> ntfy has no read receipt: the server accepting a message is not
 *       the phone receiving it. This step is the only proof the whole path works.</li>
 * </ol>
 */
public final class NtfySetupDialog {

    private static final int QR_SIZE_PX = 480;

    private final Fragment fragment;
    private final AppConfig config;
    private final Runnable onFinished;
    private final SecureRandom random = new SecureRandom();

    private NtfySetupDialog(Fragment fragment, AppConfig config, Runnable onFinished) {
        this.fragment = fragment;
        this.config = config;
        this.onFinished = onFinished;
    }

    /** 从「设置 ntfy」那一行进来。Entered from the "set up ntfy" row. */
    public static void show(Fragment fragment, AppConfig config, Runnable onFinished) {
        if (fragment == null || fragment.getContext() == null) {
            return;
        }
        new NtfySetupDialog(fragment, config, onFinished).start();
    }

    private void start() {
        if (!fragment.isAdded() || fragment.getContext() == null) {
            return;
        }
        // 已经设过、也接受过风险：直接进到「发一条试试」，不重复念一遍已经答应过的话。
        // Already set up with an accepted warning: go straight to the test send, without repeating
        // what has already been agreed.
        String topic = config.getAlertsNtfyTopic();
        if (NtfyRequest.looksLikeTopic(topic) && NtfyRisk.accepted(topic, config.getAlertsNtfyRiskAck())) {
            askForCode();
            return;
        }
        if (!NtfyRequest.looksLikeTopic(topic)) {
            config.setAlertsNtfyTopic(NtfyRequest.generateTopic());
            BlackBox.noteImportant("ntfy: topic generated");
        }
        askForRisk();
    }

    /** 第一步：风险。免费档每次换新话题都要再问一次（{@link NtfyRisk}）。 */
    private void askForRisk() {
        TextView body = new TextView(fragment.requireContext());
        body.setText(riskText());
        body.setLineSpacing(0f, 1.15f);
        int pad = (int) (20 * fragment.getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad / 2, pad, pad / 2);

        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(fragment.requireContext(),
                R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_alerts_ntfy_risk_title)
                .setView(body)
                .setPositiveButton(R.string.set_alerts_ntfy_risk_accept, (dialog, which) -> {
                    config.setAlertsNtfyRiskAck(NtfyRisk.ackFor(config.getAlertsNtfyTopic()));
                    BlackBox.noteImportant("ntfy: free-plan warning accepted");
                    showQr();
                })
                .setNegativeButton(R.string.set_alerts_ntfy_risk_decline, (dialog, which) -> {
                    config.setAlertsNtfyRiskAck("");
                    BlackBox.noteImportant("ntfy: free-plan warning declined");
                    onFinished.run();
                })
                .setOnCancelListener(dialog -> {
                    config.setAlertsNtfyRiskAck("");
                    onFinished.run();
                }));
    }

    /** 那六条，一条一行 —— 一段话读完和六条读完不是一回事。 */
    private String riskText() {
        return fragment.getString(R.string.set_alerts_ntfy_risk_intro) + "\n\n"
                + "• " + fragment.getString(R.string.set_alerts_ntfy_risk_r1) + "\n"
                + "• " + fragment.getString(R.string.set_alerts_ntfy_risk_r2) + "\n"
                + "• " + fragment.getString(R.string.set_alerts_ntfy_risk_r3) + "\n"
                + "• " + fragment.getString(R.string.set_alerts_ntfy_risk_r4) + "\n"
                + "• " + fragment.getString(R.string.set_alerts_ntfy_risk_r5) + "\n"
                + "• " + fragment.getString(R.string.set_alerts_ntfy_risk_r6);
    }

    /** 第二步：二维码。扫一下就订阅，谁也不用抄字符串。 */
    private void showQr() {
        if (!fragment.isAdded() || fragment.getContext() == null) {
            return;
        }
        String topic = config.getAlertsNtfyTopic();
        String link = NtfyRequest.deepLink(config.getAlertsNtfyServer(), topic, "Zeekr");

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
        // 二维码画不出来也不能卡在这：把深链接原样给出来，仍然能订阅。
        // A QR that will not draw must not block setup: show the deep link itself, which still works.
        TextView steps = new TextView(fragment.requireContext());
        steps.setText(fragment.getString(R.string.set_alerts_ntfy_qr_body)
                + (qr == null ? "\n\n" + link : ""));
        steps.setLineSpacing(0f, 1.15f);
        column.addView(steps);

        CamDialogs.show(new MaterialAlertDialogBuilder(fragment.requireContext(),
                R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_alerts_ntfy_qr_title)
                .setView(column)
                .setPositiveButton(R.string.set_alerts_ntfy_qr_open, (dialog, which) -> askForCode())
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> onFinished.run())
                .setOnCancelListener(dialog -> onFinished.run()));
    }

    /** 第三步：发一条带四位数的，让车主填回来。 */
    private void askForCode() {
        if (!fragment.isAdded() || fragment.getContext() == null) {
            return;
        }
        final String code = String.format(java.util.Locale.US, "%04d", random.nextInt(10_000));

        EditText input = CamDialogs.input(fragment.requireContext());
        input.setHint(R.string.set_alerts_ntfy_code_hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText("");
        int pad = (int) (20 * fragment.getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        LinearLayout column = new LinearLayout(fragment.requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad / 2, pad, 0);
        // 四位数摆在输入框上面，大一号：它是给隔壁屏幕上的 ntfy 打字用的，不是说明文字。
        // The four digits sit above the input, larger: they are typed into the ntfy app on the phone,
        // they are not prose.
        TextView big = new TextView(fragment.requireContext());
        big.setText(code);
        big.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 44f);
        big.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        big.setGravity(android.view.Gravity.CENTER);
        big.setLetterSpacing(0.25f);
        column.addView(big, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView body = new TextView(fragment.requireContext());
        body.setText(R.string.set_alerts_ntfy_code_body);
        body.setLineSpacing(0f, 1.15f);
        column.addView(body);
        column.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sendCode(code);

        CamDialogs.show(new MaterialAlertDialogBuilder(fragment.requireContext(),
                R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_alerts_ntfy_code_title)
                .setView(column)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    if (code.equals(input.getText().toString().trim())) {
                        BlackBox.noteImportant("ntfy: path confirmed by the owner");
                        toast(fragment.getString(R.string.set_alerts_ntfy_code_ok));
                        onFinished.run();
                    } else {
                        BlackBox.note("ntfy: code mismatch (owner typed something else)");
                        toast(fragment.getString(R.string.set_alerts_ntfy_code_wrong));
                        askForCode();
                    }
                })
                .setNegativeButton(R.string.set_alerts_ntfy_resend, (dialog, which) -> askForCode())
                .setOnCancelListener(dialog -> {
                    toast(fragment.getString(R.string.set_alerts_ntfy_code_none));
                    onFinished.run();
                }));
    }

    /** 发那条带码的通知。后台线程，主线程只弹对话框。 */
    private void sendCode(String code) {
        final String server = config.getAlertsNtfyServer();
        final String topic = config.getAlertsNtfyTopic();
        final String token = config.getAlertsNtfyToken();
        final String text = fragment.getString(R.string.alert_headline_test) + "\n" + code;
        new Thread(() -> {
            AlertChannel.Result result = NtfySender.probe(server, topic, token, text);
            BlackBox.note("ntfy: probe " + (result.ok ? "accepted by the server" : "failed (" + result.kind + ")"));
        }, "ntfy-probe").start();
    }

    private void toast(String message) {
        if (fragment.isAdded() && fragment.getContext() != null) {
            android.widget.Toast.makeText(fragment.getContext(), message,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
