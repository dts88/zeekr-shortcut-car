/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import com.kooo.evcam.screens.Shots;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class KnobPanelShotsTest {

    @Test
    public void panel() {
        shoot(Shots.DAY, "");
    }

    @Test
    public void panelInEnglish() {
        shoot(Shots.DAY_EN, "-en");
    }

    private static void shoot(String qualifiers, String suffix) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        Context app = RuntimeEnvironment.getApplication();
        List<String> names = new ArrayList<>();
        for (KnobTurn turn : KnobTurn.home()) {
            names.add(turn.label(app));
        }
        KnobPanel.Holder choose = KnobPanel.build(app);
        choose.choose(names, 0);
        save(choose, "knob-panel-choose" + suffix);
        KnobPanel.Holder show = KnobPanel.build(app);
        show.show(KnobTurn.TEMPERATURE.label(app), "22.5\u00b0");
        save(show, "knob-panel-show" + suffix);
        KnobPanel.Holder level = KnobPanel.build(app);
        level.show(KnobTurn.DRIVER_SEAT_HEAT.label(app), app.getString(com.kooo.evcam.R.string.knob_v_level, 2));
        save(level, "knob-panel-level" + suffix);
    }

    private static void save(KnobPanel.Holder holder, String name) {
        View root = holder.root;
        int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        root.measure(spec, spec);
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(root.getMeasuredWidth() + 96, root.getMeasuredHeight() + 96,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xFFB8BCC2);
        canvas.translate(48, 48);
        root.draw(canvas);
        File file = Shots.save(bitmap, name);
        assertTrue(file.length() > 1_000);
    }
}
