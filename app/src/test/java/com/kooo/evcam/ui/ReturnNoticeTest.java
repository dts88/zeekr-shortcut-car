/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.TextView;

import com.kooo.evcam.R;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.Duration;

/**
 * 回到前台的提示（ui-spec §十九，项目所有者 2026-11-21）：数 3 秒、没人碰屏幕才退回后台；有人碰了留下；
 * 还在数时界面被盖住算数完；界面重建接着数。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class ReturnNoticeTest {

    private ShotActivity activity;
    private ViewGroup stage;
    private ReturnNotice notice;
    private int leaves;
    private boolean leaveWorks = true;

    @Before
    public void setUp() {
        RuntimeEnvironment.setQualifiers(Shots.DAY);
        activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        // 系统动画关着：横幅收起一步到位
        Shots.stillFrames(activity);
        stage = activity.findViewById(activity.container);
        notice = newNotice();
    }

    private ReturnNotice newNotice() {
        return new ReturnNotice(() -> {
            leaves++;
            return leaveWorks;
        });
    }

    @Test
    public void countsThreeSecondsFromTheWindowBeingReadyThenLeaves() {
        notice.show(stage, ReturnNotice.SECONDS);
        assertEquals(sentence(3), shown());
        assertTrue(notice.visiting());

        pass(5);
        assertEquals("窗口就绪之前不数", sentence(3), shown());
        assertEquals(0, leaves);

        notice.start();
        notice.start();
        pass(1);
        assertEquals(sentence(2), shown());
        pass(1);
        assertEquals(sentence(1), shown());
        assertEquals("还没数完", 0, leaves);
        pass(1);
        assertNull("数完横幅拿掉，不显示 0", banner());
        assertEquals("数完退回后台，只叫一次", 1, leaves);
        assertTrue("退回去了、还没再回到前台：这一趟不是人要的", notice.visiting());
        assertEquals(0, notice.secondsLeft());

        // 退回后台带来的那次暂停不再叫一次
        notice.paused(false);
        pass(1);
        assertEquals(1, leaves);
        notice.resumed();
        assertFalse("下一次回到前台，和平常一样", notice.visiting());
    }

    @Test
    public void aTouchStaysAndIsSwallowedWhole() {
        notice.show(stage, ReturnNotice.SECONDS);
        notice.start();
        pass(1);

        assertTrue("按下的那一下算留下，吞掉", notice.takes(touch(MotionEvent.ACTION_DOWN)));
        assertNull("横幅收起", banner());
        assertFalse("留下了：和人自己打开的一样", notice.visiting());
        assertTrue("同一下的移动也吞掉", notice.takes(touch(MotionEvent.ACTION_MOVE)));
        assertTrue("抬起也吞掉", notice.takes(touch(MotionEvent.ACTION_UP)));
        assertFalse("下一下照常往下传", notice.takes(touch(MotionEvent.ACTION_DOWN)));
        assertFalse(notice.takes(touch(MotionEvent.ACTION_UP)));

        pass(10);
        assertEquals("留下了就不退", 0, leaves);
    }

    /** 人自己要它到前面（点通知、悬浮按钮和灵动贴的动作送来的意图）：在数的话不退了。 */
    @Test
    public void beingOpenedByThePersonStays() {
        notice.show(stage, ReturnNotice.SECONDS);
        notice.start();
        assertTrue(notice.stay("opened"));
        assertFalse("已经留下了，再来一下不算", notice.stay("opened"));
        assertNull(banner());
        pass(10);
        assertEquals(0, leaves);
    }

    @Test
    public void coveredWhileCountingCountsAsTimeUp() {
        notice.show(stage, ReturnNotice.SECONDS);
        notice.start();
        pass(1);

        notice.paused(false);
        assertNull("界面被盖住了：横幅拿掉", banner());
        assertTrue(notice.visiting());
        assertEquals("等这一轮生命周期走完再退", 0, leaves);
        Shots.settle();
        assertEquals("还没回到前台：退回后台", 1, leaves);
        pass(10);
        assertEquals("表停了，不再叫第二次", 1, leaves);
    }

    /** 主界面起来了却没到过前台就被盖住（窗口没就绪过，表没走过）：停下那一下也算数完，退回后台。 */
    @Test
    public void stoppedBeforeEverBeingInFrontCountsAsTimeUp() {
        notice.show(stage, ReturnNotice.SECONDS);

        notice.paused(false);
        assertNull(banner());
        Shots.settle();
        assertEquals(1, leaves);
        notice.paused(false);
        Shots.settle();
        assertEquals("暂停、停下各叫一次也只退一次", 1, leaves);
        assertTrue(notice.visiting());
    }

    /** 请它退回后台而它还在前面、又被人碰到：这一趟到此为止，这一下照常往下传。 */
    @Test
    public void aTouchAfterLeavingEndsTheVisit() {
        notice.show(stage, ReturnNotice.SECONDS);
        notice.start();
        pass(3);
        assertEquals(1, leaves);
        assertTrue(notice.visiting());

        assertFalse("不吞", notice.takes(touch(MotionEvent.ACTION_DOWN)));
        assertFalse(notice.takes(touch(MotionEvent.ACTION_UP)));
        assertFalse(notice.visiting());
        pass(10);
        assertEquals(1, leaves);
    }

    /** 安卓给在前的界面送新意图时先暂停、再恢复：那不是离开，不退。 */
    @Test
    public void aPauseFollowedByResumeAtOnceIsNotLeaving() {
        notice.show(stage, ReturnNotice.SECONDS);
        notice.start();

        notice.paused(false);
        assertFalse("新意图不是应用带到前面的那一种：已经不在数了，不算留下的那一下", notice.stay("opened"));
        notice.resumed();
        Shots.settle();
        pass(10);
        assertEquals(0, leaves);
        assertFalse(notice.visiting());
        assertNull(banner());
    }

    /** 界面重建（日夜切换、换语言、照存下的配置重摆）：只停表，剩下的秒数带给新界面接着数。 */
    @Test
    public void rebuildingHandsTheRemainingSecondsToTheNextScreen() {
        notice.show(stage, ReturnNotice.SECONDS);
        notice.start();
        pass(1);

        notice.paused(true);
        assertEquals(2, notice.secondsLeft());
        pass(10);
        assertEquals("重建时表停着", 2, notice.secondsLeft());
        assertEquals(0, leaves);
        notice.drop();
        assertNull(banner());

        ReturnNotice next = newNotice();
        next.show(stage, 2);
        assertEquals(sentence(2), shown());
        next.start();
        pass(1);
        assertEquals(sentence(1), shown());
        pass(1);
        assertEquals(1, leaves);
    }

    @Test
    public void aLeaveThatDidNotHappenEndsTheVisit() {
        leaveWorks = false;
        notice.show(stage, ReturnNotice.SECONDS);
        notice.start();
        pass(3);
        assertEquals(1, leaves);
        assertFalse("没退成（界面正在关）：不再算不是人要的", notice.visiting());
    }

    @Test
    public void withoutANoticeNothingIsTakenOrLeft() {
        assertFalse(notice.visiting());
        assertFalse(notice.takes(touch(MotionEvent.ACTION_DOWN)));
        assertFalse(notice.takes(touch(MotionEvent.ACTION_UP)));
        assertFalse(notice.stay("opened"));
        notice.start();
        notice.paused(false);
        notice.resumed();
        pass(10);
        assertEquals(0, leaves);
        assertEquals(0, notice.secondsLeft());
    }

    @Test
    public void showingAgainStartsOver() {
        notice.show(stage, ReturnNotice.SECONDS);
        notice.start();
        pass(2);
        notice.show(stage, ReturnNotice.SECONDS);
        assertEquals("只有一条横幅", 1, stage.getChildCount());
        assertEquals(sentence(3), shown());
        notice.start();
        pass(2);
        assertEquals(0, leaves);
        pass(1);
        assertEquals(1, leaves);
    }

    @Test
    public void destroyedWhileCountingNeverLeaves() {
        notice.show(stage, ReturnNotice.SECONDS);
        notice.start();
        notice.paused(false);
        notice.drop();
        pass(10);
        assertEquals(0, leaves);
        assertFalse(notice.visiting());
    }

    private String sentence(int seconds) {
        return activity.getResources().getQuantityString(R.plurals.return_notice_text, seconds, seconds);
    }

    private android.view.View banner() {
        return stage.findViewById(R.id.return_notice);
    }

    private String shown() {
        android.view.View banner = banner();
        assertNotNull("横幅不在", banner);
        return ((TextView) banner.findViewById(R.id.return_notice_text)).getText().toString();
    }

    private static void pass(int seconds) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds));
    }

    private static MotionEvent touch(int action) {
        return MotionEvent.obtain(0, 0, action, 100f, 100f, 0);
    }
}
