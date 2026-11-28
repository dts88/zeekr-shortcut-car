/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.service;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.screens.Shots;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;

/**
 * 悬浮按钮中间写限速（开发者选项，项目所有者 2026-11-28）：没在录、在录、等摄像头三种样子，各画「没有限速」「60」「110」。
 * 外圈照旧说录没录，数字代替中间的点 / 方块；数字是正文色（红色只标「正在录」）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FloatingSpeedLimitShotsTest {

    @Test
    public void byDay() {
        shot(Shots.DAY, "day");
    }

    @Test
    public void byNight() {
        shot(Shots.NIGHT, "night");
    }

    private void shot(String qualifiers, String tag) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        Context context = RuntimeEnvironment.getApplication();
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.round(new AppConfig(context).getRecordingFloatingButtonSizeDp() * density);
        int gap = Math.round(24 * density);
        String[] limits = {null, "60", "110"};
        boolean[][] states = {{false, false}, {true, false}, {true, true}};  // 没在录、在录、等摄像头
        int width = gap + limits.length * (size + gap);
        int height = gap + states.length * (size + gap);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(ContextCompat.getColor(context, R.color.bg));
        for (int row = 0; row < states.length; row++) {
            for (int col = 0; col < limits.length; col++) {
                RecordingFloatingService.RecordingButtonView button =
                        new RecordingFloatingService.RecordingButtonView(context, size);
                button.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
                button.layout(0, 0, size, size);
                button.setRecording(states[row][0], states[row][1]);
                button.setProgress(0.35f);
                button.setLimit(limits[col]);
                canvas.save();
                canvas.translate(gap + col * (size + gap), gap + row * (size + gap));
                button.draw(canvas);
                canvas.restore();
            }
        }
        File file = Shots.save(bitmap, "floating-speed-limit-" + tag);
        assertTrue(file + " 是空的", file.length() > 2_000);
    }
}
