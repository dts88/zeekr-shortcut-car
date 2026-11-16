/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screens;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.kooo.evcam.telemetry.InfoBar;
import com.kooo.evcam.telemetry.InfoBarLayout;
import com.kooo.evcam.telemetry.InfoBarRenderer;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.VehicleState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;

/**
 * 录像下面那一条行驶信息条（项目所有者 2026-11-15，wiki 的 Driving info bar 页配图）：勾的是默认那几项
 * （{@link InfoBarLayout#defaultSelection}），各给一组看得懂的示例读数。中英各一张，宽 1920（录像的宽），高 {@link InfoBar#HEIGHT}。
 *
 * <p>经纬度用新加坡市区的一个通用坐标（项目所有者 2026-11-15「经纬度放一个新加坡的经纬度」，不是哪个具体地址）；车牌不填，前灯组的车牌上不写数字。座位照右舵车：主驾（右前）有人系着，副驾没人；
 * 后排的安全带没验证，不是开发者时就是没数据，照实画。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class InfoBarShotsTest {

    private static final int WIDTH = 1920;

    @Test
    public void infoBarInChinese() {
        shoot(Shots.DAY, "info-bar");
    }

    @Test
    public void infoBarInEnglish() {
        shoot(Shots.DAY_EN, "info-bar-en");
    }

    /** 开着车、往左打灯：舒适模式 D 挡，62 km/h，油门 35%，日行灯和近光开着。 */
    static VehicleState sample() {
        return VehicleState.empty().edit()
                .turnSignal(VehicleState.TURN_LEFT)
                .hazard(false)
                .steeringDegrees(-12f)
                .gear("D")
                .driveMode(Signal.DRIVE_MODE_COMFORT)
                .brake(0f)
                .throttle(0.35f)
                .speedKmh(62f)
                .autoHold(false)
                .stockSurroundShown(false)
                .sentry(0)
                .door(VehicleState.FRONT_LEFT, false)
                .door(VehicleState.FRONT_RIGHT, false)
                .door(VehicleState.REAR_LEFT, false)
                .door(VehicleState.REAR_RIGHT, false)
                .seat(VehicleState.FRONT_RIGHT, VehicleState.SEAT_BELTED)
                .seat(VehicleState.FRONT_LEFT, VehicleState.SEAT_EMPTY)
                .daytimeRunningLights(true)
                .lowBeam(true)
                .highBeam(false)
                .fogLights(false)
                .rearPositionLamps(true)
                .stopLamps(false)
                .reverseLamps(false)
                .odometerKm(8642.3f)
                .position(1.290270, 103.851959)
                .build();
    }

    private void shoot(String qualifiers, String name) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        Context context = RuntimeEnvironment.getApplication();
        assertTrue("照默认勾的画", InfoBar.selection(context).containsAll(InfoBarLayout.defaultSelection()));
        InfoBarRenderer renderer = new InfoBarRenderer(context, WIDTH);
        assertTrue(renderer.renderIfDue(sample()));
        assertTrue("格子都放上了：" + renderer.cellCount(), renderer.cellCount() >= 12);
        File file = Shots.save(renderer.bitmap(), name);
        renderer.recycle();
        assertTrue(file + " 是空的", file.length() > 5_000);
    }
}
