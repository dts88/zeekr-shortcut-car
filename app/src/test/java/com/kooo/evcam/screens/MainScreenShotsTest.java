/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screens;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.RecordButtonUi;
import com.kooo.evcam.ui.StatusLine;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;

/**
 * 主界面画成图片（{@link Shots}）：画面那一块是空的（电脑上没有相机），看的是动作栏和状态条 ——
 * 录制键、熄屏提示那一行、六块开关、状态条的两格。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainScreenShotsTest {

    @Test
    public void mainScreenByDay() {
        shoot(Shots.DAY, "day", false);
    }

    @Test
    public void mainScreenByNight() {
        shoot(Shots.NIGHT, "night", false);
    }

    /** 开关都开着、正在录：六块都亮，录制键下面有熄屏提示那一行。 */
    @Test
    public void mainScreenRecordingWithEverythingOn() {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setRearViewEnabled(true);
        config.setSideViewEnabled(true);
        config.setDriveSoundEnabled(true);
        config.setFisheyeCorrection(true);
        shoot(Shots.DAY, "day-on", true);
    }

    private void shoot(String qualifiers, String tag, boolean recording) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        ViewGroup frame = activity.findViewById(activity.container);
        activity.getLayoutInflater().inflate(R.layout.activity_main_zeekr_7x, frame, true);
        Shots.settle();

        View rail = frame.findViewById(R.id.action_rail);
        assertNotNull("主界面里没有动作栏", rail);
        assertNotNull("动作栏上没有六块开关", rail.findViewById(R.id.quick_switches));
        View hint = rail.findViewById(R.id.record_hint);
        assertNotNull("动作栏上没有熄屏提示那一行", hint);
        assertEquals("熄屏提示应该在录制键外面、和它并排在同一列里",
                rail.findViewById(R.id.btn_start_record).getParent(), hint.getParent());

        RecordButtonUi record = new RecordButtonUi(rail.findViewById(R.id.btn_start_record));
        record.setState(recording ? RecordButtonUi.State.RECORDING : RecordButtonUi.State.IDLE);
        StatusLine.fill(frame);
        if (recording && hint instanceof TextView && ((TextView) hint).length() == 0) {
            // 电脑上读不到熄屏那几项的实际情况时，这一行是空的：放一句进去看排布
            ((TextView) hint).setText(R.string.set_screen_off_keep_title);
            hint.setVisibility(View.VISIBLE);
        }
        File file = Shots.save(activity, "main-" + tag);
        assertTrue(file + " 是空的", file.length() > 10_000);
        if (recording) {
            assertEquals("录着的时候熄屏提示那一行要在", View.VISIBLE, hint.getVisibility());
        }
        // 4.0.0 在实车上把「设置」那一行顶出了屏幕：照实车的尺寸，这一行要整个在屏幕里，中间那一段也不用滚
        View settings = rail.findViewById(R.id.btn_settings);
        android.graphics.Rect seen = new android.graphics.Rect();
        assertTrue("「设置」不在屏幕里", settings.getGlobalVisibleRect(seen));
        assertEquals("「设置」被裁掉了一截", settings.getHeight(), seen.height());
        android.widget.ScrollView scroll = rail.findViewById(R.id.rail_scroll);
        assertTrue("动作栏中间那一段排不下、要滚了（高 " + scroll.getChildAt(0).getHeight() + "，能用 "
                + scroll.getHeight() + "）", scroll.getChildAt(0).getHeight() <= scroll.getHeight());
    }
}
