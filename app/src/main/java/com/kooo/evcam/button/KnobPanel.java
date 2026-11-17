/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.ui.MotionPolicy;

import java.util.List;

final class KnobPanel {

    private static final String TAG = "KnobPanel";
    static final long SHOW_MS = 2_500L;
    static final String TOP = "top";
    static final String SIDE = "side";

    private static final Runnable HIDE = KnobPanel::hide;
    private static Handler main;
    private static KnobDialView view;
    private static WindowManager windows;
    private static boolean attached;

    private static String key;
    private static String[] labels;
    private static boolean free;
    private static int target = -1;
    private static int pending;
    private static int seq;

    private KnobPanel() {
    }

    private static Handler main() {
        if (main == null) {
            main = new Handler(Looper.getMainLooper());
        }
        return main;
    }

    static int style(String setting) {
        return SIDE.equals(setting) ? KnobDialView.DRUM : KnobDialView.ARC;
    }

    static void choose(Context app, List<String> names, int index, int delta, long hideAfterMs) {
        if (names.isEmpty() || !ensure(app)) {
            return;
        }
        boolean fresh = key != null || view.mode != KnobDialView.CHOOSE || delta == 0;
        key = null;
        view.choose(names.toArray(new String[0]), Math.max(0, Math.min(names.size() - 1, index)), delta, fresh);
        later(hideAfterMs);
    }

    static int turn(Context app, String turnKey, String name, int delta, Runnable look) {
        if (!ensure(app)) {
            return -1;
        }
        if (!turnKey.equals(key)) {
            key = turnKey;
            labels = null;
            free = false;
            target = -1;
            pending = 0;
            view.start(name);
            if (look != null) {
                look.run();
            }
        }
        seq++;
        if (delta != 0) {
            view.turned(delta);
            if (labels != null) {
                step(delta);
            } else if (!free) {
                pending += delta;
            }
        }
        later(SHOW_MS);
        return seq;
    }

    static void seen(String turnKey, KnobDials.Positions positions) {
        if (!attached || !turnKey.equals(key) || labels != null || free) {
            return;
        }
        if (positions == null || positions.labels.length == 0) {
            free = true;
            pending = 0;
            view.free();
            return;
        }
        labels = positions.labels;
        target = positions.index;
        view.values(labels, Math.max(0, target));
        int d = pending;
        pending = 0;
        if (d != 0) {
            step(d);
        }
    }

    static void shown(String turnKey, String text, int at) {
        if (!attached || !turnKey.equals(key)) {
            return;
        }
        if (labels == null) {
            view.text(text);
            return;
        }
        if (at != seq || text == null) {
            return;
        }
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(text)) {
                if (i != target) {
                    target = i;
                    view.moveTo(i);
                }
                return;
            }
        }
    }

    static int next(int index, int delta, int size) {
        return index < 0 ? 0 : Math.max(0, Math.min(size - 1, index + delta));
    }

    private static void step(int delta) {
        int to = next(target, delta, labels.length);
        if (to == target) {
            view.push(Integer.signum(delta));
        }
        target = to;
        view.moveTo(to);
    }

    private static void later(long ms) {
        main().removeCallbacks(HIDE);
        main().postDelayed(HIDE, ms);
    }

    static void hide() {
        main().removeCallbacks(HIDE);
        key = null;
        if (!attached || view == null) {
            return;
        }
        try {
            windows.removeView(view);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "remove failed: " + e);
        }
        attached = false;
    }

    private static boolean ensure(Context app) {
        AppConfig config = new AppConfig(app);
        int style = style(config.getKnobPanel());
        boolean left = "left".equals(config.getActionRailSide());
        if (attached && (view.style != style || view.left != left)) {
            hide();
        }
        if (attached) {
            view.moving = MotionPolicy.decorative(app);
            return true;
        }
        if (!OverlayCoordinator.canShowOverlay(app)) {
            return false;
        }
        windows = app.getSystemService(WindowManager.class);
        view = new KnobDialView(app, style, left);
        view.moving = MotionPolicy.decorative(app);
        float dp = app.getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(view.widthPx(), view.heightPx(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        if (style == KnobDialView.ARC) {
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = Math.round(16 * dp);
        } else {
            params.gravity = (left ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL;
            params.x = Math.round(24 * dp);
        }
        params.windowAnimations = MotionPolicy.overlayWindowAnimations(app);
        try {
            windows.addView(view, params);
            attached = true;
        } catch (RuntimeException e) {
            AppLog.w(TAG, "add failed: " + e);
            return false;
        }
        return true;
    }
}
