/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import com.kooo.evcam.alarm.media.AlertPicture;
import com.kooo.evcam.alarm.media.PictureBudget;

import java.util.Random;

/**
 * 提醒附带的那张图（{@link AlertPicture}）：压进 100 KB、压不进就不附，盖时间不动源图，开关关着不掰直。
 *
 * The picture attached to an alert ({@link AlertPicture}): squeezed under 100 KB or not attached at all,
 * the time stamp drawn on a copy, and no straightening when the switch is off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AlertPictureTest {

    @Test
    public void aSurroundSizedPictureFitsTheBudget() {
        Bitmap grid = scene(1280, 1280);
        byte[] jpeg = AlertPicture.jpegUnder(grid, PictureBudget.ALERT_BYTES);
        assertNotNull("压不进 100 KB", jpeg);
        assertTrue("超了: " + jpeg.length, jpeg.length <= PictureBudget.ALERT_BYTES);
        assertFalse("源图被回收了", grid.isRecycled());
    }

    @Test
    public void nothingIsAttachedWhenNoStepFits() {
        // 满屏噪点、预算只有 1 KB：哪一档都压不进去，宁可不附图
        assertNull(AlertPicture.jpegUnder(noise(800, 800), 1024));
    }

    @Test
    public void theStampGoesOnACopy() {
        Bitmap source = scene(640, 640);
        Bitmap stamped = AlertPicture.stamped(source, "2026-11-18 21:05:33");
        assertNotNull(stamped);
        assertNotSame(source, stamped);
        assertEquals(source.getWidth(), stamped.getWidth());
        assertEquals(source.getHeight(), stamped.getHeight());
        assertFalse(source.isRecycled());
        assertNull(AlertPicture.stamped(null, "x"));
    }

    @Test
    public void theSwitchOffMeansTheSamePicture() {
        Bitmap grid = scene(640, 640);
        assertSame(grid, AlertPicture.straighten(grid, false, 120f, null));
        assertNull(AlertPicture.straighten(null, true, 120f, null));
    }

    /** 像一张四宫格：四块不同的底色，上面几道线。Four tinted cells with a few lines, like a 2x2 grid. */
    private static Bitmap scene(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        int[] colors = {Color.rgb(90, 110, 130), Color.rgb(120, 100, 80), Color.rgb(70, 90, 70),
                Color.rgb(140, 140, 150)};
        for (int i = 0; i < 4; i++) {
            paint.setColor(colors[i]);
            float left = (i % 2) * width / 2f;
            float top = (i / 2) * height / 2f;
            canvas.drawRect(left, top, left + width / 2f, top + height / 2f, paint);
        }
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(4f);
        for (int x = 0; x < width; x += 80) {
            canvas.drawLine(x, 0, width - x, height, paint);
        }
        return bitmap;
    }

    private static Bitmap noise(int width, int height) {
        Random random = new Random(7);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | random.nextInt(0xFFFFFF);
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
}
