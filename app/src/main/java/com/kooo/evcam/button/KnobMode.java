/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;

import java.util.ArrayList;
import java.util.List;

final class KnobMode {

    static final long CHOOSE_MS = 3_500L;

    private static final Runnable CLOSE = KnobMode::close;
    private static Handler main;
    private static String choosing;

    private KnobMode() {
    }

    private static Handler main() {
        if (main == null) {
            main = new Handler(Looper.getMainLooper());
        }
        return main;
    }

    static String currentKey(String address) {
        return "knob_current." + address;
    }

    static KnobTurn current(SharedPreferences state, String address, List<KnobTurn> home) {
        KnobTurn saved = KnobTurn.fromKey(state.getString(currentKey(address), null));
        return home.contains(saved) ? saved : home.get(0);
    }

    static int move(int index, int delta, int size) {
        return Math.floorMod(index + delta, size);
    }

    static void press(Context app, String address, int gesture, int steps) {
        List<KnobTurn> home = new AppConfig(app).getKnobHome(address);
        SharedPreferences state = app.getSharedPreferences(SmartButtons.STATE, Context.MODE_PRIVATE);
        KnobTurn current = current(state, address, home);
        if (gesture == SmartButtonAd.SINGLE || gesture == SmartButtonAd.DOUBLE) {
            if (!address.equals(choosing)) {
                choosing = address;
                KnobPanel.choose(app, names(app, home), home.indexOf(current), CHOOSE_MS);
                main().removeCallbacks(CLOSE);
                main().postDelayed(CLOSE, CHOOSE_MS);
                return;
            }
            close();
            current.peek(app, shown -> KnobPanel.show(app, current.label(app), shown));
            return;
        }
        if (!Accessory.isTurn(gesture)) {
            return;
        }
        int delta = gesture == SmartButtonAd.CLOCKWISE ? steps : -steps;
        if (address.equals(choosing)) {
            int i = move(home.indexOf(current), delta, home.size());
            state.edit().putString(currentKey(address), home.get(i).key).apply();
            KnobPanel.choose(app, names(app, home), i, CHOOSE_MS);
            main().removeCallbacks(CLOSE);
            main().postDelayed(CLOSE, CHOOSE_MS);
            return;
        }
        KnobPanel.show(app, current.label(app), null);
        current.runTS(app, delta, shown -> KnobPanel.show(app, current.label(app), shown));
    }

    static void close() {
        main().removeCallbacks(CLOSE);
        choosing = null;
    }

    private static List<String> names(Context app, List<KnobTurn> home) {
        List<String> out = new ArrayList<>();
        for (KnobTurn turn : home) {
            out.add(turn.label(app));
        }
        return out;
    }
}
