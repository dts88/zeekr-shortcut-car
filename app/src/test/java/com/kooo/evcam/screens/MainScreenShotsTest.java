/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screens;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * 录制键和它里面的熄屏提示、四块开关、状态条的两格。
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

    /** 开关都开着、正在录：四块都亮，录制键里有熄屏提示那一行。 */
    @Test
    public void mainScreenRecordingWithEverythingOn() {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setRearViewEnabled(true);
        config.setSideViewEnabled(true);
        config.setDriveSoundEnabled(true);
        config.setFisheyeCorrection(true);
        shoot(Shots.DAY, "day-on", true);
    }

    /**
     * 录着、只差哨兵模式：录制键右边四分之一切给「开启哨兵模式」（项目所有者 2026-11-13）。照实车的宽，那个键是
     * （380 − 12）/ 4 = 92 dp，录制键剩 276 dp；字那一块窄了，第二行去掉「本段」，三行都要放得下。收回之后一切照旧。
     */
    @Test
    public void theSentryKeyTakesTheRightQuarterOfTheRecordKey() {
        sentryKey(Shots.DAY, "day", true);
        sentryKey(Shots.NIGHT, "night", true);
        // 英文、马来文的字长：第一行缩小到放得下，不裁掉
        sentryKey(Shots.DAY.replace("zh-rCN", "en"), "en", false);
        sentryKey(Shots.DAY.replace("zh-rCN", "ms"), "ms", false);
    }

    private void sentryKey(String qualifiers, String tag, boolean chinese) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        ViewGroup frame = activity.findViewById(activity.container);
        activity.getLayoutInflater().inflate(R.layout.activity_main_zeekr_7x, frame, true);
        Shots.settle();
        float density = activity.getResources().getDisplayMetrics().density;

        View rail = frame.findViewById(R.id.action_rail);
        ViewGroup row = rail.findViewById(R.id.record_key_row);
        View card = rail.findViewById(R.id.btn_start_record);
        View key = rail.findViewById(R.id.btn_sentry_on);
        TextView title = card.findViewById(R.id.record_title);
        TextView detail = card.findViewById(R.id.record_detail);
        TextView hint = card.findViewById(R.id.record_hint);
        assertNotNull("录制键不在它那一行里", row);
        assertNotNull("这一行里没有「开启哨兵模式」那个键", key);
        float titleSize = title.getTextSize();

        RecordButtonUi record = new RecordButtonUi(card);
        StatusLine.fill(frame);
        Shots.save(activity, "main-sentry-scratch");
        assertEquals("没在录时没有那个键", View.GONE, key.getVisibility());
        assertEquals("没在录时录制键占满这一行", row.getWidth(), card.getWidth());

        record.setState(RecordButtonUi.State.RECORDING);
        record.setSegmentProgress(42_000, 60_000);
        hint.setText(R.string.record_screen_off_pause);
        hint.setVisibility(View.VISIBLE);
        record.setSentryOffered(true);
        Shots.save(activity, "main-sentry-scratch");
        File file = Shots.save(activity, "main-sentry-" + tag);
        assertTrue(file + " 是空的", file.length() > 10_000);

        int gutter = activity.getResources().getDimensionPixelSize(R.dimen.gutter);
        int keyWidth = Math.round((row.getWidth() - gutter) / 4f);
        assertEquals("动作栏照实车是 380 dp 宽", Math.round(380 * density), row.getWidth());
        assertEquals(View.VISIBLE, key.getVisibility());
        assertEquals("那个键是右边四分之一", keyWidth, key.getWidth());
        assertEquals("那个键贴着这一行的右边", row.getWidth(), key.getRight());
        assertEquals("那个键滑到位了", 0f, key.getTranslationX(), 0f);
        assertEquals("完全显出来了", 1f, key.getAlpha(), 0f);
        assertEquals("录制键让出四分之一和一道缝", row.getWidth() - keyWidth - gutter, card.getWidth());
        assertTrue("那个键能点", key.isClickable());
        assertFits("第一行", title);
        assertFits("第二行", detail);
        android.graphics.Rect inside = new android.graphics.Rect();
        assertTrue("熄屏提示不在屏幕里", hint.getGlobalVisibleRect(inside));
        assertEquals("熄屏提示在录制键里被裁掉了一截", hint.getHeight(), inside.height());
        TextView label = key.findViewById(R.id.sentry_key_label);
        assertTrue("那个键上的字被裁掉了", label.getLayout().getEllipsisCount(label.getLineCount() - 1) == 0
                && label.getLineCount() <= 3);
        if (chinese) {
            assertEquals("中文四个字放得下，不缩小", titleSize, title.getTextSize(), 0.5f);
            assertEquals("熄屏提示一行写完", 1, hint.getLineCount());
            assertEquals("那个键上的字两行：开启 / 哨兵模式", 2, label.getLineCount());
        } else {
            assertTrue("放不下的语言第一行缩小", title.getTextSize() < titleSize);
        }

        // 收回：那个键没了，录制键回到原来的宽，字也回到原来的大小和写法
        record.setSentryOffered(false);
        Shots.save(activity, "main-sentry-scratch");
        Shots.save(activity, "main-sentry-scratch");
        assertEquals(View.GONE, key.getVisibility());
        assertEquals(row.getWidth(), card.getWidth());
        assertEquals(titleSize, title.getTextSize(), 0.5f);
        assertEquals(activity.getString(R.string.record_segment_detail, "00:42", 1), detail.getText().toString());
        // 停录：也没有
        record.setSentryOffered(true);
        record.setState(RecordButtonUi.State.IDLE);
        Shots.save(activity, "main-sentry-scratch");
        assertEquals("不写「停止录制」时没有那个键", View.GONE, key.getVisibility());
        new File("build/outputs/screenshots/main-sentry-scratch.png").delete();
    }

    /** 一行的字没被裁掉、没被省略。 */
    private static void assertFits(String what, TextView view) {
        assertNotNull(what + "还没排", view.getLayout());
        assertEquals(what + "折行了", 1, view.getLineCount());
        assertEquals(what + "被省略了", 0, view.getLayout().getEllipsisCount(0));
        assertTrue(what + "放不下：字宽 " + view.getLayout().getLineWidth(0) + "，能用 "
                        + (view.getWidth() - view.getPaddingLeft() - view.getPaddingRight()),
                view.getLayout().getLineWidth(0) <= view.getWidth() - view.getPaddingLeft() - view.getPaddingRight() + 0.5f);
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
        android.view.ViewGroup switches = rail.findViewById(R.id.quick_switches);
        assertNotNull("动作栏上没有那几块开关", switches);
        assertEquals("开关是四块、两排（项目所有者 2026-11-13 删了「悬浮按钮」「灵动贴」两块）", 2, switches.getChildCount());
        View card = rail.findViewById(R.id.btn_start_record);
        View hint = card.findViewById(R.id.record_hint);
        assertNotNull("熄屏提示应该在录制键里面（项目所有者 2026-11-13）", hint);
        assertFalse("熄屏提示只是说明，不能单独点", hint.isClickable());

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
            android.graphics.Rect inside = new android.graphics.Rect();
            assertTrue("熄屏提示不在屏幕里", hint.getGlobalVisibleRect(inside));
            assertEquals("熄屏提示在录制键里被裁掉了一截", hint.getHeight(), inside.height());
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
