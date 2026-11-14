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
    public void mainScreenByDayInEnglish() {
        shoot(Shots.DAY_EN, "day-en", false);
    }

    @Test
    public void mainScreenByNight() {
        shoot(Shots.NIGHT, "night", false);
    }

    /** 开关都开着、正在录：四块都亮，录制键里有熄屏提示那一行。 */
    @Test
    public void mainScreenRecordingWithEverythingOn() {
        everythingOn();
        shoot(Shots.DAY, "day-on", true);
    }

    @Test
    public void mainScreenRecordingWithEverythingOnInEnglish() {
        everythingOn();
        shoot(Shots.DAY_EN, "day-on-en", true);
    }

    private static void everythingOn() {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setRearViewEnabled(true);
        config.setSideViewEnabled(true);
        config.setDriveSoundEnabled(true);
        config.setFisheyeCorrection(true);
    }

    /**
     * 录着、只差哨兵模式：录制键右边切一块给「开启哨兵模式」（项目所有者 2026-11-13）。那个键三种语言一样宽（72 dp），
     * 最宽这一行的四分之一（92 dp）；录制键让出它和一道缝，字那一块窄了，三行都要放得下、不被省略。收回之后一切照旧。
     */
    @Test
    public void theSentryKeyTakesTheRightQuarterOfTheRecordKey() {
        sentryKey(Shots.DAY, "day", true);
        sentryKey(Shots.NIGHT, "night", true);
        // 英文、马来文的字长：第一行缩小到放得下，第三行的小字两行里写完（项目所有者 2026-11-13：英文原来写不下）
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
        record.setRecordedTime(754_000);
        assertEquals("第二行写「录制中」和这一次已经录了多久",
                activity.getString(R.string.record_recording_detail, "12:34"), detail.getText().toString());
        hint.setText(R.string.record_screen_off_pause);
        hint.setVisibility(View.VISIBLE);
        record.setSentryKey(true, false);
        Shots.save(activity, "main-sentry-scratch");
        File file = Shots.save(activity, "main-sentry-" + tag);
        assertTrue(file + " 是空的", file.length() > 10_000);

        int gutter = activity.getResources().getDimensionPixelSize(R.dimen.gutter);
        int quarter = Math.round((row.getWidth() - gutter) / 4f);
        int keyWidth = key.getWidth();
        TextView label = key.findViewById(R.id.sentry_key_label);
        assertEquals("动作栏照实车是 380 dp 宽", Math.round(380 * density), row.getWidth());
        assertEquals(View.VISIBLE, key.getVisibility());
        assertTrue("那个键最宽四分之一（" + quarter + "），量出来 " + keyWidth, keyWidth <= quarter);
        View icon = key.findViewById(R.id.sentry_key_icon);
        assertEquals("图标宽 30 dp（项目所有者 2026-11-14：比 44 dp 小三成）", Math.round(30 * density), icon.getWidth());
        assertEquals("图标高 33 dp（再高一些）", Math.round(33 * density), icon.getHeight());
        assertEquals("那个键三种语言一样宽（项目所有者 2026-11-14：向最宽的对齐）",
                activity.getResources().getDimensionPixelSize(R.dimen.sentry_key_width), keyWidth);
        assertTrue("字加两边各 10 dp 要放得进那个宽：字 " + label.getWidth() + "，键 " + keyWidth,
                Math.max(icon.getWidth(), label.getWidth()) + Math.round(20 * density) <= keyWidth);
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
        assertTrue("那个键上的字被裁掉了", label.getLayout().getEllipsisCount(label.getLineCount() - 1) == 0);
        assertTrue("熄屏提示最多两行", hint.getLineCount() <= 2);
        assertEquals("熄屏提示被省略了：" + hint.getText(), 0,
                hint.getLayout().getEllipsisCount(hint.getLineCount() - 1));
        if (chinese) {
            assertEquals("中文四个字放得下，不缩小", titleSize, title.getTextSize(), 0.5f);
            assertEquals("熄屏提示一行写完", 1, hint.getLineCount());
            assertEquals("那个键上的字两行：哨兵 / 模式（项目所有者 2026-11-14 删了「开启」、中文也折行）", 2,
                    label.getLineCount());
        } else {
            assertEquals("那个键上的字两行", 2, label.getLineCount());
        }

        // 图标五层：三道光、灯罩和底座、灯芯是正文色；灯芯、三道光各另有一份红的，没开时不露
        android.widget.ImageView lampOn = key.findViewById(R.id.sentry_key_lamp_on);
        android.widget.ImageView raysOn = key.findViewById(R.id.sentry_key_rays_on);
        int base = activity.getColor(R.color.text_primary);
        int red = activity.getColor(R.color.recording);
        for (int id : new int[] {R.id.sentry_key_rays, R.id.sentry_key_body, R.id.sentry_key_lamp}) {
            assertEquals(base, tintOf(key.findViewById(id)));
        }
        assertEquals(red, tintOf(lampOn));
        assertEquals(red, tintOf(raysOn));
        assertEquals("没开时红的灯芯不露", View.INVISIBLE, lampOn.getVisibility());
        assertEquals("没开时红的光不露", View.INVISIBLE, raysOn.getVisibility());

        // 哨兵模式开上了（项目所有者 2026-11-14）：图标先从没开变成开着 —— 红的灯芯、红的三道光整个露出来；
        // 键还在、还点得到（再点一下是反悔）。让人看清之后才收起
        // （测试里系统动画是关的，过渡一步到位；点亮后还要停 600 ms 才收，这里不往后拨钟）
        record.setSentryKey(false, true);
        Shots.save(activity, "main-sentry-scratch");
        assertEquals("点亮的时候键还在", View.VISIBLE, key.getVisibility());
        assertTrue("亮着的时候还点得到：反悔", key.isClickable());
        assertEquals("开着时红的灯芯露着", View.VISIBLE, lampOn.getVisibility());
        assertEquals("开着时红的光露着", View.VISIBLE, raysOn.getVisibility());
        android.graphics.Rect window = new android.graphics.Rect();
        assertTrue(lampOn.getClipBounds(window));
        float unit = lampOn.getWidth() / 24f;
        assertTrue("红的灯芯整颗露出来：窗口 " + window, window.left <= Math.round(9.1f * unit)
                && window.right >= Math.round(14.9f * unit) && window.top == 0 && window.bottom == lampOn.getHeight());
        assertTrue(raysOn.getClipBounds(window));
        assertTrue("红的光整个露出来：窗口 " + window, window.left <= Math.round(1.8f * unit)
                && window.top <= Math.round(1.0f * unit) && window.right >= Math.round(22.2f * unit));
        assertEquals("鼓的那一下收回来了", 1f, key.findViewById(R.id.sentry_key_icon).getScaleX(), 0.001f);
        File lit = Shots.save(activity, "main-sentry-lit-" + tag);
        assertTrue(lit + " 是空的", lit.length() > 10_000);
        record.setSentryKey(false, true);
        Shots.settle();
        assertEquals("同一件事再说一遍不会提前收起", View.VISIBLE, key.getVisibility());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3));
        Shots.save(activity, "main-sentry-scratch");
        Shots.save(activity, "main-sentry-scratch");
        assertEquals("看清之后收起", View.GONE, key.getVisibility());
        assertFalse("收起之后点不到", key.isClickable());
        assertEquals(row.getWidth(), card.getWidth());

        // 键只跟着车的读数走（实车 2026-11-14：连点几下，键和车对不上了 —— 原来自己计时、有 5 秒不认读数）：
        // 开、关、开、关接连读到，每一下键都照那一下的读数摆，不留上一下的尾巴
        android.os.Looper main = android.os.Looper.getMainLooper();
        record.setSentryKey(true, false);
        Shots.save(activity, "main-sentry-scratch");
        assertEquals(View.VISIBLE, key.getVisibility());
        assertEquals("再出现时回到没开的样子", View.INVISIBLE, lampOn.getVisibility());
        assertEquals(View.INVISIBLE, raysOn.getVisibility());
        assertTrue(key.isClickable());
        for (int round = 0; round < 3; round++) {
            record.setSentryKey(false, true);   // 读到开着
            Shots.settle();
            assertEquals("第 " + (round + 1) + " 回读到开着：亮", View.VISIBLE, lampOn.getVisibility());
            assertEquals(View.VISIBLE, key.getVisibility());
            record.setSentryKey(true, false);   // 马上又读到关着（反悔关掉了，或者别处关的）
            Shots.settle();
            assertEquals("第 " + (round + 1) + " 回读到关着：没开的样子", View.INVISIBLE, lampOn.getVisibility());
            assertEquals(View.INVISIBLE, raysOn.getVisibility());
            assertTrue("可以再点", key.isClickable());
            // 上一下「开着」排的收起不能留到现在才生效
            org.robolectric.Shadows.shadowOf(main).idleFor(java.time.Duration.ofSeconds(3));
            Shots.save(activity, "main-sentry-scratch");
            assertEquals("读数是关着：键留着，不收", View.VISIBLE, key.getVisibility());
            assertEquals(row.getWidth() - keyWidth - gutter, card.getWidth());
        }
        record.setSentryKey(false, true);       // 最后读到开着、不再变：亮，然后收起
        Shots.settle();
        assertEquals(View.VISIBLE, lampOn.getVisibility());
        org.robolectric.Shadows.shadowOf(main).idleFor(java.time.Duration.ofSeconds(3));
        Shots.save(activity, "main-sentry-scratch");
        Shots.save(activity, "main-sentry-scratch");
        assertEquals("开着不再变：收起", View.GONE, key.getVisibility());
        record.setSentryKey(false, true);
        Shots.save(activity, "main-sentry-scratch");
        assertEquals("收起之后还读到开着：不再出来", View.GONE, key.getVisibility());

        // 别的原因不该在了（开走了、设置改了）：不点亮，直接收回。那个键没了，录制键回到原来的宽，字也回到原来的大小和写法
        record.setSentryKey(true, false);
        Shots.save(activity, "main-sentry-scratch");
        assertEquals(View.VISIBLE, key.getVisibility());
        assertEquals(View.INVISIBLE, lampOn.getVisibility());
        assertTrue(key.isClickable());
        record.setSentryKey(false, false);
        Shots.save(activity, "main-sentry-scratch");
        Shots.save(activity, "main-sentry-scratch");
        assertEquals(View.GONE, key.getVisibility());
        assertEquals(row.getWidth(), card.getWidth());
        assertEquals(titleSize, title.getTextSize(), 0.5f);
        assertEquals(activity.getString(R.string.record_recording_detail, "12:34"), detail.getText().toString());
        // 停录：也没有
        record.setSentryKey(true, false);
        record.setState(RecordButtonUi.State.IDLE);
        Shots.save(activity, "main-sentry-scratch");
        assertEquals("不写「停止录制」时没有那个键", View.GONE, key.getVisibility());
        new File("build/outputs/screenshots/main-sentry-scratch.png").delete();
    }

    /**
     * 点亮的过渡在车上是 1.5 秒，电脑上一步到位、看不到中间：把过渡停在几处各出一张，拼起来给项目所有者看先后
     * （红的灯芯转三圈、定住，三道光长出去，图标鼓一下）。只为出图：直接叫录制键里那个不公开的 applyLit。
     */
    @Test
    public void lightingFilmStrip() throws Exception {
        RuntimeEnvironment.setQualifiers(Shots.NIGHT);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        ViewGroup frame = activity.findViewById(activity.container);
        activity.getLayoutInflater().inflate(R.layout.activity_main_zeekr_7x, frame, true);
        Shots.settle();
        View rail = frame.findViewById(R.id.action_rail);
        RecordButtonUi record = new RecordButtonUi(rail.findViewById(R.id.btn_start_record));
        StatusLine.fill(frame);
        record.setState(RecordButtonUi.State.RECORDING);
        record.setSentryKey(true, false);
        Shots.save(activity, "main-sentry-scratch");
        java.lang.reflect.Method applyLit = RecordButtonUi.class.getDeclaredMethod("applyLit", float.class);
        applyLit.setAccessible(true);
        int[] percents = {0, 6, 12, 24, 30, 42, 48, 60, 68, 76, 86, 100};
        for (int percent : percents) {
            applyLit.invoke(record, percent / 100f);
            File file = Shots.save(activity, "main-sentry-light-" + percent);
            assertTrue(file + " 是空的", file.length() > 10_000);
        }
        View lampOn = rail.findViewById(R.id.sentry_key_lamp_on);
        View raysOn = rail.findViewById(R.id.sentry_key_rays_on);
        android.graphics.Rect window = new android.graphics.Rect();
        float unit = lampOn.getWidth() / 24f;
        applyLit.invoke(record, 0.09f);
        assertEquals("转圈时红的灯芯露着一条", View.VISIBLE, lampOn.getVisibility());
        assertTrue(lampOn.getClipBounds(window));
        assertTrue("那一条比整颗窄：" + window, window.width() < Math.round(5.8f * unit));
        assertEquals("转圈时三道光还没动", View.INVISIBLE, raysOn.getVisibility());
        applyLit.invoke(record, 0.54f);
        assertEquals("转完三圈那一刻红的转出去了", View.INVISIBLE, lampOn.getVisibility());
        applyLit.invoke(record, 0.8f);
        assertTrue(lampOn.getClipBounds(window));
        assertTrue("定住之后整颗是红的：" + window, window.width() >= Math.round(5.8f * unit) - 1);
        assertEquals("三道光在往外长", View.VISIBLE, raysOn.getVisibility());
        assertTrue(raysOn.getClipBounds(window));
        assertTrue("还没长到头：" + window, window.top > Math.round(1.0f * unit));
        assertTrue("图标鼓着", rail.findViewById(R.id.sentry_key_icon).getScaleX() > 1.05f);
        new File("build/outputs/screenshots/main-sentry-scratch.png").delete();
    }

    private static int tintOf(android.widget.ImageView view) {
        return androidx.core.widget.ImageViewCompat.getImageTintList(view).getDefaultColor();
    }

    /** 一行的字没被裁掉、没被省略。 */
    private static void assertFits(String what, TextView view) {
        assertNotNull(what + "还没排", view.getLayout());
        assertEquals(what + "折行了：「" + view.getText() + "」字号 " + view.getTextSize() + "，第一行宽 "
                + view.getLayout().getLineWidth(0) + "，视图宽 " + view.getWidth()
                + "，整句宽 " + view.getPaint().measureText(view.getText(), 0, view.length()), 1, view.getLineCount());
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
        record.setRecordedTime(754_000);
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
