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

import com.kooo.evcam.R;
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
        String[] heat = {app.getString(R.string.quick_off), app.getString(R.string.knob_v_level, 1),
                app.getString(R.string.knob_v_level, 2), app.getString(R.string.knob_v_level, 3)};
        for (int style : new int[] {KnobDialView.ARC, KnobDialView.DRUM}) {
            String prefix = "knob-panel-" + (style == KnobDialView.ARC ? "arc" : "drum");
            KnobDialView choose = view(app, style);
            choose.choose(names.toArray(new String[0]), 3, 0, true);
            save(choose, prefix + "-choose" + suffix);
            KnobDialView temperature = view(app, style);
            temperature.start(KnobTurn.TEMPERATURE.label(app));
            KnobDials.Positions t = KnobDials.temperatures(22.5f);
            temperature.values(t.labels, t.index);
            save(temperature, prefix + "-value" + suffix);
            KnobDialView level = view(app, style);
            level.start(KnobTurn.DRIVER_SEAT_HEAT.label(app));
            level.values(heat, 2);
            save(level, prefix + "-level" + suffix);
            KnobDialView volume = view(app, style);
            volume.start(KnobTurn.VOLUME.label(app));
            volume.free();
            volume.text("+3");
            save(volume, prefix + "-free" + suffix);
        }
    }

    private static KnobDialView view(Context app, int style) {
        KnobDialView view = new KnobDialView(app, style, true);
        view.moving = false;
        return view;
    }

    private static void save(KnobDialView view, String name) {
        int w = view.widthPx();
        int h = view.heightPx();
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        view.settle();
        Bitmap bitmap = Bitmap.createBitmap(w + 96, h + 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xFFB8BCC2);
        canvas.translate(48, 48);
        view.draw(canvas);
        File file = Shots.save(bitmap, name);
        assertTrue(file.length() > 1_000);
    }
}
