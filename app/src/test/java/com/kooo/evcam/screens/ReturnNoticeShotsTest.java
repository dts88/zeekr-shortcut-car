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

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.kooo.evcam.R;
import com.kooo.evcam.ui.RecordButtonUi;
import com.kooo.evcam.ui.ReturnNotice;
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
 * 回到前台的提示画成图片（ui-spec §十九，项目所有者 2026-11-21 选的「画面顶部横幅」）：横幅压在画面区的顶边、和画面区一样宽，
 * 盖住菜单键；四种语言的那一句都写得完（最多两行、不省略），行尾那一枚一行写完。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReturnNoticeShotsTest {

    @Test
    public void onThePictureInChinese() {
        View banner = onThePicture(Shots.DAY, "day", R.layout.activity_main_zeekr_7x);
        TextView sentence = banner.findViewById(R.id.return_notice_text);
        assertEquals("中文一行写完", 1, sentence.getLineCount());
        onThePicture(Shots.NIGHT, "night", R.layout.activity_main_zeekr_7x);
    }

    /** 开了座舱那几路的主界面：横幅横跨环视和座舱那一列。 */
    @Test
    public void onThePictureWithCabinLanes() {
        onThePicture(Shots.DAY, "multi-day", R.layout.activity_main_zeekr_7x_multi);
    }

    @Test
    public void onThePictureInEnglishAndMalay() {
        onThePicture(Shots.DAY_EN, "day-en", R.layout.activity_main_zeekr_7x);
        onThePicture(Shots.DAY.replace("zh-rCN", "ms"), "day-ms", R.layout.activity_main_zeekr_7x);
    }

    /** 阿拉伯文：主界面不镜像（动作栏还在原来那一边），横幅里面跟着语言 —— 行尾那一枚到了左边。 */
    @Test
    public void onThePictureInArabic() {
        View banner = onThePicture(Shots.DAY_AR, "day-ar", R.layout.activity_main_zeekr_7x);
        assertEquals("里面跟着语言的方向", View.LAYOUT_DIRECTION_RTL, banner.getLayoutDirection());
        assertEquals("主界面本身不镜像", View.LAYOUT_DIRECTION_LTR,
                ((View) banner.getParent()).getLayoutDirection());
    }

    /** 主界面还活着、上次停在设置：横幅盖在那一页的顶边（① 标题区），贴着窗口的边。 */
    @Test
    public void onASettingsPage() {
        RuntimeEnvironment.setQualifiers(Shots.DAY);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        ViewGroup frame = activity.findViewById(activity.container);
        activity.getLayoutInflater().inflate(R.layout.fragment_settings_two_pane, frame, true);
        new ReturnNotice(() -> true).show(frame, ReturnNotice.SECONDS);
        File file = Shots.save(activity, "return-notice-page-day");
        assertTrue(file + " 是空的", file.length() > 10_000);
        View banner = frame.findViewById(R.id.return_notice);
        assertEquals(0, banner.getTop());
        assertEquals(frame.getWidth(), banner.getWidth());
        assertEquals("和 ① 标题区一样高",
                activity.getResources().getDimensionPixelSize(R.dimen.title_bar_height), banner.getHeight());
        assertWrittenOut(banner);
    }

    /** 主界面（{@code layout}）上放出横幅，画一张，核对摆在哪、写没写完；返回横幅。 */
    private static View onThePicture(String qualifiers, String tag, int layout) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        ViewGroup frame = activity.findViewById(activity.container);
        activity.getLayoutInflater().inflate(layout, frame, true);
        Shots.settle();
        new RecordButtonUi(frame.findViewById(R.id.btn_start_record)).setState(RecordButtonUi.State.IDLE);
        StatusLine.overlay(frame);
        StatusLine.fill(frame);

        ViewGroup stage = frame.findViewById(R.id.preview_stage);
        assertNotNull("主界面里没有画面区", stage);
        ReturnNotice notice = new ReturnNotice(() -> true);
        notice.show(stage, ReturnNotice.SECONDS);
        File file = Shots.save(activity, "return-notice-" + tag);
        assertTrue(file + " 是空的", file.length() > 10_000);

        View banner = stage.findViewById(R.id.return_notice);
        assertNotNull("横幅不在画面区里", banner);
        assertEquals("压在画面区的顶边", 0, banner.getTop());
        assertEquals("和画面区一样宽", stage.getWidth(), banner.getWidth());
        assertEquals("高 64 dp（和 ① 标题区同高）",
                activity.getResources().getDimensionPixelSize(R.dimen.title_bar_height), banner.getHeight());
        assertEquals("排在最上面", stage.getChildCount() - 1, stage.indexOfChild(banner));

        // 菜单键、画面角标：碰到的都整个盖住，不露半截
        Rect covered = new Rect();
        banner.getGlobalVisibleRect(covered);
        for (int id : new int[] {R.id.btn_menu, R.id.label_front, R.id.label_back}) {
            View under = frame.findViewById(id);
            if (under == null || under.getVisibility() != View.VISIBLE) {
                continue;
            }
            Rect at = new Rect();
            under.getGlobalVisibleRect(at);
            if (Rect.intersects(covered, at)) {
                assertTrue("只盖住了一截：" + at + "，横幅 " + covered, covered.contains(at));
            }
        }
        Rect menu = new Rect();
        frame.findViewById(R.id.btn_menu).getGlobalVisibleRect(menu);
        assertTrue("菜单键要在横幅底下", covered.contains(menu));
        // ⑤ 状态条在画面底边，不碰它
        Rect status = new Rect();
        frame.findViewById(R.id.status_bar).getGlobalVisibleRect(status);
        assertTrue("横幅压到状态条了", covered.bottom <= status.top);

        TextView sentence = banner.findViewById(R.id.return_notice_text);
        assertEquals("照 3 秒写", activity.getResources().getQuantityString(R.plurals.return_notice_text, 3, 3),
                sentence.getText().toString());
        assertWrittenOut(banner);
        return banner;
    }

    /**
     * 那一句最多两行、没被省略、没被裁掉；行尾那一枚一行写完、整个在横幅里，排在那一句后面
     * （从右往左的语言里是左边）。
     */
    private static void assertWrittenOut(View banner) {
        TextView sentence = banner.findViewById(R.id.return_notice_text);
        TextView stay = banner.findViewById(R.id.return_notice_stay);
        assertNotNull("那一句还没排", sentence.getLayout());
        assertTrue("那一句超过两行：" + sentence.getText(), sentence.getLineCount() <= 2);
        assertEquals("那一句被省略了：" + sentence.getText(), 0,
                sentence.getLayout().getEllipsisCount(sentence.getLineCount() - 1));
        assertTrue("那一句比横幅高：" + sentence.getHeight() + " / " + banner.getHeight(),
                sentence.getHeight() <= banner.getHeight());
        assertEquals("行尾那一枚折行了", 1, stay.getLineCount());
        assertEquals("行尾那一枚被省略了", 0, stay.getLayout().getEllipsisCount(0));
        assertTrue("行尾那一枚伸到横幅外面了", stay.getLeft() >= 0 && stay.getRight() <= banner.getWidth());
        if (banner.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            assertTrue("行尾那一枚在那一句的左边", stay.getRight() <= sentence.getLeft());
        } else {
            assertTrue("行尾那一枚在那一句后面", stay.getLeft() >= sentence.getRight());
        }
    }
}
