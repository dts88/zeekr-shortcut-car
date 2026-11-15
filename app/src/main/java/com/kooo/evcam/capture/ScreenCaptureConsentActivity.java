/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.capture;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.kooo.evcam.blackbox.BlackBox;

/**
 * 弹系统的屏幕录制同意框（{@code createScreenCaptureIntent}）：只有界面才弹得出来，所以要这个看不见的界面。
 * 透明、不进最近任务、单独一个任务（清单里 taskAffinity 是空的）：弹框时屏幕上原来是什么还是什么，主界面不会跟着出来。
 * 同意了交给 {@link ScreenCaptureService} 截第一张，没同意什么都不做。
 */
public class ScreenCaptureConsentActivity extends ComponentActivity {

    private final ActivityResultLauncher<Intent> consent = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    ScreenCaptureService.start(this, result.getResultCode(), result.getData());
                } else {
                    BlackBox.note("截屏：没有同意，不截");
                }
                finish();
                overridePendingTransition(0, 0);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            // 系统重建的：框已经弹过，结果照样会回到上面
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        try {
            consent.launch(manager.createScreenCaptureIntent());
        } catch (RuntimeException e) {
            // 车机上没有接这个的界面（Lab：系统界面里有，没上车试过）
            BlackBox.note("截屏：系统的同意框打不开（" + e + "）");
            ScreenCapture.failed(getApplicationContext());
            finish();
        }
    }
}
