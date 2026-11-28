/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 限速的提示（ui-spec §十五，项目所有者 2026-11-28）：装上这个版本后第一次在主界面时弹一次。
 * 该不该弹、点了什么算弹过；白天 / 夜间 / 英文 / 马来文 / 阿拉伯文各存一张。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SpeedLimitNoticeTest {

    private static final String DAY_MS = "ms-w1600dp-h848dp-land-notnight-xhdpi";

    @Test
    public void dueOnceAfterSetupUnlessAlreadyOn() {
        Context app = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(app);
        assertFalse("要走初始设置的先走完", SpeedLimitNotice.due(app, config));
        config.setSetupDone();
        assertTrue(SpeedLimitNotice.due(app, config));
        config.setSpeedLimitOnButton(true);
        assertFalse("已经开着的不弹", SpeedLimitNotice.due(app, config));
        config.setSpeedLimitOnButton(false);
        assertTrue(SpeedLimitNotice.due(app, config));
    }

    @Test
    public void openSettingsCountsAndOpens() {
        AtomicInteger opened = new AtomicInteger();
        AlertDialog dialog = show(Shots.DAY, opened);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shots.settle();
        assertEquals(1, opened.get());
        assertFalse("弹过了", due());
    }

    @Test
    public void gotItCounts() {
        AtomicInteger opened = new AtomicInteger();
        AlertDialog dialog = show(Shots.DAY, opened);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        Shots.settle();
        assertEquals(0, opened.get());
        assertFalse(due());
    }

    @Test
    public void backKeyCounts() {
        AlertDialog dialog = show(Shots.DAY, new AtomicInteger());
        dialog.cancel();
        Shots.settle();
        assertFalse(due());
    }

    @Test
    public void closedWithoutAnswerComesBack() {
        AlertDialog dialog = show(Shots.DAY, new AtomicInteger());
        dialog.dismiss();
        Shots.settle();
        assertTrue("界面被关、没点：下次再弹", due());
    }

    @Test
    public void byDay() {
        shoot(Shots.DAY, "day");
    }

    @Test
    public void byNight() {
        shoot(Shots.NIGHT, "night");
    }

    @Test
    public void inEnglish() {
        shoot(Shots.DAY_EN, "day-en");
    }

    @Test
    public void inMalay() {
        shoot(DAY_MS, "day-ms");
    }

    @Test
    public void inArabic() {
        shoot(Shots.DAY_AR, "day-ar");
    }

    private void shoot(String qualifiers, String tag) {
        AlertDialog dialog = show(qualifiers, new AtomicInteger());
        Context context = dialog.getContext();
        assertEquals(context.getString(R.string.action_open_settings),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals(context.getString(R.string.action_got_it),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        assertTrue("最后一行是 Issues 页面的地址",
                context.getString(R.string.notice_speed_limit_msg).endsWith("github.com/dts88/zeekr-shortcut-car/issues"));
        File file = Shots.save(dialog, "notice-speed-limit-" + tag);
        assertTrue(file + " 是空的", file.length() > 5_000);
    }

    private static AlertDialog show(String qualifiers, AtomicInteger opened) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        new AppConfig(RuntimeEnvironment.getApplication()).setSetupDone();
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        AlertDialog dialog = SpeedLimitNotice.show(activity, opened::incrementAndGet);
        Shots.settle();
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static boolean due() {
        Context app = RuntimeEnvironment.getApplication();
        return SpeedLimitNotice.due(app, new AppConfig(app));
    }
}
