/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;

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

/** 照片列表的一条：四宫格缩略图照相机位摆，从右往左的语言里也不镜像，和预览区对得上（ui-spec §十七）。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoListShotsTest {

    @Test
    public void theThumbnailGridKeepsItsPlacesRightToLeft() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_RTL);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        ViewGroup frame = activity.findViewById(activity.container);
        activity.getLayoutInflater().inflate(R.layout.item_video_group, frame, true);
        File file = Shots.save(activity, "photo-list-item-day-rtl");
        assertTrue(file + " 是空的", file.length() > 0);
        assertEquals("外面跟着语言方向", View.LAYOUT_DIRECTION_RTL, frame.getLayoutDirection());

        int[] front = new int[2];
        int[] back = new int[2];
        int[] left = new int[2];
        int[] right = new int[2];
        frame.findViewById(R.id.thumb_front).getLocationInWindow(front);
        frame.findViewById(R.id.thumb_back).getLocationInWindow(back);
        frame.findViewById(R.id.thumb_left).getLocationInWindow(left);
        frame.findViewById(R.id.thumb_right).getLocationInWindow(right);
        assertTrue("上一排：环视那一格在左", front[0] < back[0]);
        assertTrue("下一排：同样左右不换", left[0] < right[0]);
    }
}
