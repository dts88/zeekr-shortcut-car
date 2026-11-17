/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import com.kooo.evcam.AppConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 超级镜子的视野角度跟着「通用 → 鱼眼校正」选的方式夹：直线最大 140°，宽视野、全圆最大 180°。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class MirrorFovTest {

    private static final float EPS = 0.001f;
    private AppConfig config;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(AppConfig.PREF_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        config = new AppConfig(context);
    }

    @Test
    public void straightLinesStopAt140() {
        config.setFisheyeProjection(FisheyeProjection.PROJECTION_RECTILINEAR);
        config.setRearViewFov(170f);
        assertEquals(140f, config.getRearViewFov(), EPS);
    }

    @Test
    public void wideAndFullCircleGoTo180() {
        config.setFisheyeProjection(FisheyeProjection.PROJECTION_CYLINDRICAL);
        config.setRearViewFov(180f);
        assertEquals(180f, config.getRearViewFov(), EPS);
        config.setFisheyeProjection(FisheyeProjection.PROJECTION_STEREOGRAPHIC);
        assertEquals(180f, config.getRearViewFov(), EPS);
    }

    @Test
    public void switchingBackToStraightLinesReadsTheNarrowerAngle() {
        config.setFisheyeProjection(FisheyeProjection.PROJECTION_CYLINDRICAL);
        config.setRearViewFov(170f);
        config.setFisheyeProjection(FisheyeProjection.PROJECTION_RECTILINEAR);
        assertEquals(140f, config.getRearViewFov(), EPS);
        config.setFisheyeProjection(FisheyeProjection.PROJECTION_CYLINDRICAL);
        assertEquals(170f, config.getRearViewFov(), EPS);
    }
}
