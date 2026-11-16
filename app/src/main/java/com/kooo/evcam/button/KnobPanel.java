/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.overlay.OverlayCoordinator;

import java.util.List;

final class KnobPanel {

    private static final String TAG = "KnobPanel";
    static final long SHOW_MS = 2_500L;

    private static final Runnable HIDE = KnobPanel::hide;
    private static Handler main;
    private static Holder holder;
    private static WindowManager windows;
    private static boolean attached;

    private KnobPanel() {
    }

    private static Handler main() {
        if (main == null) {
            main = new Handler(Looper.getMainLooper());
        }
        return main;
    }

    static final class Holder {
        final LinearLayout root;
        final TextView before;
        final TextView now;
        final TextView value;
        final TextView after;

        Holder(LinearLayout root, TextView before, TextView now, TextView value, TextView after) {
            this.root = root;
            this.before = before;
            this.now = now;
            this.value = value;
            this.after = after;
        }

        void choose(List<String> names, int index) {
            int n = names.size();
            String prev = n > 1 ? names.get(Math.floorMod(index - 1, n)) : null;
            String next = n > 2 ? names.get(Math.floorMod(index + 1, n)) : null;
            set(before, prev);
            set(now, names.get(index));
            set(value, null);
            set(after, next);
            now.setPaintFlags(now.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        }

        void show(String name, String shown) {
            set(before, null);
            set(now, name);
            set(value, shown);
            set(after, null);
            now.setPaintFlags(now.getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        }

        private static void set(TextView view, String text) {
            view.setText(text);
            view.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    static Holder build(Context context) {
        Configuration conf = new Configuration(context.getResources().getConfiguration());
        conf.uiMode = (conf.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        Context night = context.createConfigurationContext(conf);
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(32 * density);
        int padV = Math.round(14 * density);
        root.setPadding(padH, padV, padH, padV);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor((night.getColor(R.color.surface) & 0x00FFFFFF) | 0xF2000000);
        pill.setCornerRadius(999 * density);
        root.setBackground(pill);
        TextView before = text(context, night.getColor(R.color.text_secondary), R.dimen.text_row, false);
        TextView now = text(context, night.getColor(R.color.text_primary), R.dimen.text_title, true);
        TextView value = text(context, night.getColor(R.color.energy_text), R.dimen.text_title, true);
        TextView after = text(context, night.getColor(R.color.text_secondary), R.dimen.text_row, false);
        int gap = Math.round(28 * density);
        root.addView(before, margins(0, gap));
        root.addView(now, margins(0, 0));
        root.addView(value, margins(gap, 0));
        root.addView(after, margins(gap, 0));
        return new Holder(root, before, now, value, after);
    }

    private static TextView text(Context context, int color, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources().getDimension(size));
        view.setSingleLine(true);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        view.setVisibility(View.GONE);
        return view;
    }

    private static LinearLayout.LayoutParams margins(int start, int end) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(start);
        lp.setMarginEnd(end);
        return lp;
    }

    static void choose(Context app, List<String> names, int index, long hideAfterMs) {
        if (names.isEmpty() || !ensure(app)) {
            return;
        }
        holder.choose(names, Math.max(0, Math.min(names.size() - 1, index)));
        main().removeCallbacks(HIDE);
        main().postDelayed(HIDE, hideAfterMs);
    }

    static void show(Context app, String name, String shown) {
        if (!ensure(app)) {
            return;
        }
        holder.show(name, shown);
        main().removeCallbacks(HIDE);
        main().postDelayed(HIDE, SHOW_MS);
    }

    static void hide() {
        main().removeCallbacks(HIDE);
        if (!attached || holder == null) {
            return;
        }
        try {
            windows.removeView(holder.root);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "remove failed: " + e);
        }
        attached = false;
    }

    private static boolean ensure(Context app) {
        if (attached) {
            return true;
        }
        if (!OverlayCoordinator.canShowOverlay(app)) {
            return false;
        }
        if (holder == null) {
            holder = build(app);
            windows = app.getSystemService(WindowManager.class);
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = Math.round(24 * app.getResources().getDisplayMetrics().density);
        try {
            windows.addView(holder.root, params);
            attached = true;
        } catch (RuntimeException e) {
            AppLog.w(TAG, "add failed: " + e);
            return false;
        }
        return true;
    }
}
