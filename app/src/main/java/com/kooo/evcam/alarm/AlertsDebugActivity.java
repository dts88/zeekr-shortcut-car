/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * \u505c\u8f66\u8b66\u62a5\u7684\u8c03\u8bd5\u9875\uff08\u5f00\u53d1\u8005\u9009\u9879\uff09\uff1a\u4e00\u5c4f JSON \u52a0\u4e0a\u6700\u8fd1\u7684\u9ed1\u5323\u5b50\uff0c\u53ef\u4ee5\u622a\u5c4f\u3001\u590d\u5236\u3001\u5b58\u5230 U \u76d8\u3002
 *
 * <h3>\u4e3a\u4ec0\u4e48\u662f JSON</h3>
 *
 * <p>\u201c\u5b83\u600e\u4e48\u6ca1\u53d1\u201d\u8981\u80fd\u4e00\u773c\u770b\u5b8c\uff1a\u8b66\u62a5\u5f00\u6ca1\u5f00\u3001\u5728\u4e0d\u5728\u76d1\u542c\u3001\u8f6e\u8be2\u8dd1\u6ca1\u8dd1\u3001
 * \u54e8\u5175\u4e0e\u6321\u4f4d\u8bfb\u5230\u4ec0\u4e48\u3001\u753b\u9762\u53d6\u4e86\u51e0\u6b21\u6837\u3001\u4e0a\u4e00\u6b21\u53d1\u5931\u8d25\u662f\u4e3a\u4ec0\u4e48\u3002
 * \u5c4f\u5e55\u4e0a\u90a3\u4efd\u4e3a\u4e86\u80fd\u7ffb\u52a8\u4f1a\u622a\u65ad\u5f88\u957f\u7684\u5b57\u4e32\uff1b\u5b8c\u6574\u7684\u5728\u4fdd\u5b58\u51fa\u53bb\u7684\u6587\u4ef6\u91cc\u3002</p>
 *
 * <p>\u4e0d\u9760\u7cfb\u7edf\u201c\u5206\u4eab\u201d\uff1a\u8f66\u673a\u4e0a\u6ca1\u6709\u80fd\u63a5\u6536\u5206\u4eab\u7684\u5e94\u7528\uff08{@code DiagnosticsActivity} \u5f53\u5e74\u5c31\u662f\u8fd9\u4e48\u5220\u7684\uff09\u3002
 * \u6240\u4ee5\u8fd9\u91cc\u53ea\u7559\u4e09\u6837\uff1a\u590d\u5236\u3001\u5b58\u5230\u5b58\u50a8\u3001\u5173\u95ed\u3002</p>
 */
public class AlertsDebugActivity extends AppCompatActivity {

    private TextView reportView;
    private String report = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.alerts_debug_title);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        reportView = new TextView(this);
        reportView.setTextSize(11f);
        reportView.setTypeface(android.graphics.Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(reportView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button(R.string.alerts_debug_refresh, v -> load()));
        buttons.addView(button(R.string.alerts_debug_copy, v -> copy()));
        buttons.addView(button(R.string.alerts_debug_save, v -> save()));
        buttons.addView(button(R.string.action_close, v -> finish()));
        root.addView(buttons);

        setContentView(root);
        load();
    }

    private Button button(int label, android.view.View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(action);
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        button.setLayoutParams(params);
        return button;
    }

    private void load() {
        report = com.kooo.evcam.alarm.SentryAlerts.get(this).debugReport();
        reportView.setText(report);
    }

    private void copy() {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("alerts", report));
            toast(getString(R.string.alerts_debug_copied));
        }
    }

    private void save() {
        try {
            File dir = StorageHelper.getExportDir(this);
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                toast(getString(R.string.diag_cannot_save));
                return;
            }
            File file = new File(dir, "alerts_debug_" + System.currentTimeMillis() + ".json");
            Files.write(file.toPath(), report.getBytes(StandardCharsets.UTF_8));
            toast(getString(R.string.alerts_debug_saved, file.getAbsolutePath()));
        } catch (java.io.IOException | RuntimeException e) {
            toast(getString(R.string.diag_cannot_save));
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
