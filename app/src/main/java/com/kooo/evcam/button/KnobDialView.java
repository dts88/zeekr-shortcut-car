/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.animation.TimeAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import com.kooo.evcam.R;
import com.kooo.evcam.ui.Motion;

final class KnobDialView extends View {

    static final int ARC = 0;
    static final int DRUM = 1;

    static final int CHOOSE = 0;
    static final int VALUE = 1;
    static final int FREE = 2;

    static final float OMEGA = 5.8f * 1000f / Motion.MEDIUM;
    static final float KICK = 640f;
    static final float STEP_S = 0.004f;

    static final class Spring {
        float x;
        float v;
        float target;

        void step(float dt) {
            float a = -OMEGA * OMEGA * (x - target) - 2f * OMEGA * v;
            v += a * dt;
            x += v * dt;
        }

        boolean settled() {
            return Math.abs(x - target) < 0.002f && Math.abs(v) < 0.02f;
        }

        void snap() {
            x = target;
            v = 0f;
        }
    }

    final int style;
    final boolean left;
    final Spring pos = new Spring();
    final Spring spin = new Spring();
    final Spring bump = new Spring();
    boolean moving = true;
    int mode = CHOOSE;
    String[] labels = new String[0];
    boolean wrap;
    String name;
    String value;

    private final float dp;
    private final TimeAnimator ticker = new TimeAnimator();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final int surface;
    private final int sunken;
    private final int primary;
    private final int secondary;
    private final int tertiary;
    private final int energy;
    private final float title;
    private final float row;
    private final float caption;

    KnobDialView(Context context, int style, boolean left) {
        super(context);
        this.style = style;
        this.left = left;
        dp = context.getResources().getDisplayMetrics().density;
        Configuration conf = new Configuration(context.getResources().getConfiguration());
        conf.uiMode = (conf.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        Context night = context.createConfigurationContext(conf);
        surface = (night.getColor(R.color.surface) & 0x00FFFFFF) | 0xF2000000;
        sunken = night.getColor(R.color.sunken);
        primary = night.getColor(R.color.text_primary);
        secondary = night.getColor(R.color.text_secondary);
        tertiary = night.getColor(R.color.text_tertiary);
        energy = night.getColor(R.color.energy);
        title = context.getResources().getDimension(R.dimen.text_title);
        row = context.getResources().getDimension(R.dimen.text_row);
        caption = context.getResources().getDimension(R.dimen.text_caption);
        ticker.setTimeListener((animation, total, delta) -> tick(delta));
    }

    int widthPx() {
        return Math.round((style == ARC ? 740 : 294) * dp);
    }

    int heightPx() {
        return Math.round((style == ARC ? 216 : 320) * dp);
    }

    void choose(String[] names, int index, int delta, boolean fresh) {
        boolean again = mode == CHOOSE && !fresh && labels.length == names.length;
        mode = CHOOSE;
        labels = names;
        wrap = true;
        value = null;
        if (again) {
            pos.target += delta;
        } else {
            pos.target = index;
            pos.snap();
        }
        kick();
    }

    void start(String title) {
        mode = VALUE;
        name = title;
        value = null;
        labels = new String[0];
        wrap = false;
        pos.target = 0;
        pos.snap();
        bump.target = 0;
        bump.snap();
        kick();
    }

    void values(String[] all, int from) {
        mode = VALUE;
        labels = all;
        wrap = false;
        pos.target = from;
        pos.snap();
        kick();
    }

    void free() {
        mode = FREE;
        labels = new String[0];
        kick();
    }

    void moveTo(int index) {
        pos.target = index;
        kick();
    }

    void push(int direction) {
        bump.v += direction * KICK;
        kick();
    }

    void turned(int delta) {
        spin.target += delta;
        kick();
    }

    void text(String shown) {
        value = shown;
        invalidate();
    }

    void settle() {
        pos.snap();
        spin.snap();
        bump.snap();
        ticker.end();
        invalidate();
    }

    private void kick() {
        if (!moving) {
            settle();
            return;
        }
        if (!ticker.isStarted()) {
            ticker.start();
        }
        invalidate();
    }

    private void tick(long deltaMs) {
        float dt = Math.min(deltaMs, 48L) / 1000f;
        for (float t = 0f; t < dt; t += STEP_S) {
            float h = Math.min(STEP_S, dt - t);
            pos.step(h);
            spin.step(h);
            bump.step(h);
        }
        if (pos.settled() && spin.settled() && bump.settled()) {
            pos.snap();
            spin.snap();
            bump.snap();
            ticker.end();
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        ticker.end();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (style == ARC) {
            arc(canvas);
        } else {
            drum(canvas);
        }
    }

    private String label(int i) {
        int n = labels.length;
        if (n == 0) {
            return null;
        }
        if (wrap) {
            return labels[Math.floorMod(i, n)];
        }
        return i >= 0 && i < n ? labels[i] : null;
    }

    private float fraction() {
        int n = labels.length;
        if (n < 2) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, pos.x / (n - 1)));
    }

    private void arc(Canvas c) {
        float r = 573 * dp;
        float apex = 58 * dp;
        float cx = getWidth() / 2f;
        float cy = apex + r;
        float span = 0.6f;
        float mark = r - 35 * dp;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(100 * dp);
        paint.setColor(surface);
        c.drawArc(oval, -90f - deg(span), 2f * deg(span), false, paint);
        if (mode == CHOOSE) {
            float item = 0.33f;
            for (int k = -9; k <= 9; k++) {
                float at = Math.round(pos.x * 3f) / 3f + k / 3f;
                float a = (at - pos.x) * item;
                if (Math.abs(a) <= span) {
                    dot(c, cx, cy, mark, a, 1.6f * dp, tertiary, fade(Math.abs(a), span));
                }
            }
            for (int k = -2; k <= 2; k++) {
                int i = Math.round(pos.x) + k;
                float a = (i - pos.x) * item;
                String s = label(i);
                if (s == null || Math.abs(a) > span - 0.06f) {
                    continue;
                }
                float u = Math.min(1f, Math.abs(a) / (span - 0.06f));
                float size = title - (title - row) * u;
                c.save();
                c.rotate(deg(a), cx, cy);
                text(c, s, cx, cy - r - 6 * dp + size * 0.36f, size, u < 0.25f ? primary : secondary, u < 0.25f,
                        u < 0.25f ? 250 * dp : 170 * dp, Math.round(255 * (1f - 0.85f * u)));
                c.restore();
            }
            dot(c, cx, cy, mark, 0f, 4f * dp, energy, 255);
            return;
        }
        text(c, name, cx, apex - 28 * dp + caption * 0.36f, caption, secondary, false, 420 * dp, 255);
        float g = 0.46f;
        oval.set(cx - mark, cy - mark, cx + mark, cy + mark);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(3 * dp);
        paint.setColor(tertiary);
        paint.setAlpha(110);
        c.drawArc(oval, -90f - deg(g), 2f * deg(g), false, paint);
        if (mode == FREE || labels.length < 2) {
            float spacing = 0.08f;
            float frac = spin.x - (float) Math.floor(spin.x);
            for (int k = -7; k <= 7; k++) {
                float a = (k - frac) * spacing;
                if (Math.abs(a) <= g) {
                    dot(c, cx, cy, mark, a, 2f * dp, secondary, fade(Math.abs(a), g));
                }
            }
            dot(c, cx, cy, mark, 0f, 4f * dp, energy, 255);
            if (value != null) {
                text(c, value, cx, apex + 6 * dp + title * 0.36f, title, primary, true, 420 * dp, 255);
            }
            return;
        }
        int n = labels.length;
        if (n <= 30) {
            for (int j = 0; j < n; j++) {
                dot(c, cx, cy, mark, -g + 2f * g * j / (n - 1), 1.5f * dp, tertiary, 255);
            }
        }
        float a = -g + 2f * g * fraction();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * dp);
        paint.setColor(energy);
        if (a > -g + 0.002f) {
            c.drawArc(oval, -90f - deg(g), deg(Math.min(a, g) + g), false, paint);
        }
        dot(c, cx, cy, mark, a + bump.x * dp / mark, 5f * dp, energy, 255);
        roll(c, cx + bump.x * dp, apex + 6 * dp, 420 * dp);
    }

    private void drum(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float cy = h / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(surface);
        oval.set(0, 0, w, h);
        c.drawRoundRect(oval, 24 * dp, 24 * dp, paint);
        c.save();
        c.clipRect(0, 0, w, h);
        if (mode == CHOOSE) {
            paint.setColor(sunken);
            oval.set(15 * dp, cy - 31 * dp, w - 15 * dp, cy + 31 * dp);
            c.drawRoundRect(oval, 14 * dp, 14 * dp, paint);
            paint.setColor(energy);
            oval.set(15 * dp, cy - 17.5f * dp, 19 * dp, cy + 17.5f * dp);
            c.drawRoundRect(oval, 2 * dp, 2 * dp, paint);
            for (int k = -3; k <= 3; k++) {
                int i = Math.round(pos.x) + k;
                float an = (i - pos.x) * 0.42f;
                String s = label(i);
                if (s == null || Math.abs(an) > 1.25f) {
                    continue;
                }
                float cos = (float) Math.cos(an);
                float y = cy + (float) Math.sin(an) * 147 * dp;
                boolean centre = Math.abs(an) < 0.2f;
                float size = centre ? title : row;
                c.save();
                c.translate(w / 2f, y);
                c.scale(1f, Math.max(0.2f, cos));
                text(c, s, 0, size * 0.36f, size, centre ? primary : secondary, centre, w - 60 * dp,
                        Math.round(255 * cos * cos * cos));
                c.restore();
            }
            c.restore();
            return;
        }
        float bar = left ? w - 38 * dp : 38 * dp;
        float mid = w / 2f + (left ? -20 : 20) * dp;
        text(c, name, mid, 50 * dp + caption * 0.36f, caption, secondary, false, w - 96 * dp, 255);
        float top = 41 * dp;
        float bottom = h - 41 * dp;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(tertiary);
        paint.setAlpha(110);
        oval.set(bar - 2 * dp, top, bar + 2 * dp, bottom);
        c.drawRoundRect(oval, 2 * dp, 2 * dp, paint);
        if (mode == FREE || labels.length < 2) {
            float spacing = 22 * dp;
            float frac = spin.x - (float) Math.floor(spin.x);
            for (int k = -8; k <= 8; k++) {
                float y = cy + (k - frac) * spacing;
                if (y >= top && y <= bottom) {
                    paint.setColor(secondary);
                    paint.setAlpha(fade(Math.abs(y - cy), (bottom - top) / 2f));
                    c.drawCircle(bar, y, 2 * dp, paint);
                }
            }
            if (value != null) {
                text(c, value, mid, cy + 6 * dp + title * 0.36f, title, primary, true, w - 96 * dp, 255);
            }
            c.restore();
            return;
        }
        int n = labels.length;
        if (n <= 30) {
            for (int j = 0; j < n; j++) {
                paint.setColor(tertiary);
                c.drawCircle(bar, bottom - (bottom - top) * j / (n - 1), 1.5f * dp, paint);
            }
        }
        float y = bottom - (bottom - top) * fraction();
        paint.setColor(energy);
        if (y < bottom - dp) {
            oval.set(bar - 2 * dp, Math.max(top, y), bar + 2 * dp, bottom);
            c.drawRoundRect(oval, 2 * dp, 2 * dp, paint);
        }
        c.drawCircle(bar, y - bump.x * dp, 5.5f * dp, paint);
        roll(c, mid, cy + 6 * dp - bump.x * dp, w - 96 * dp);
        c.restore();
    }

    private void roll(Canvas c, float x, float centre, float maxWidth) {
        float base = (float) Math.floor(pos.x);
        float frac = pos.x - base;
        float lift = title * 1.1f;
        float baseline = centre + title * 0.36f;
        c.save();
        c.clipRect(x - maxWidth / 2f, centre - title * 0.75f, x + maxWidth / 2f, centre + title * 0.75f);
        String now = label((int) base);
        String next = label((int) base + 1);
        if (now != null) {
            text(c, now, x, baseline - frac * lift, title, primary, true, maxWidth, Math.round(255 * (1f - frac)));
        }
        if (next != null && frac > 0.001f) {
            text(c, next, x, baseline + (1f - frac) * lift, title, primary, true, maxWidth, Math.round(255 * frac));
        }
        c.restore();
    }

    private void dot(Canvas c, float cx, float cy, float radius, float angle, float size, int color, int alpha) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(alpha);
        c.drawCircle(cx + radius * (float) Math.sin(angle), cy - radius * (float) Math.cos(angle), size, paint);
    }

    private void text(Canvas c, String s, float x, float baseline, float size, int color, boolean bold,
                      float maxWidth, int alpha) {
        if (s == null || alpha <= 0) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size);
        float width = paint.measureText(s);
        if (width > maxWidth) {
            paint.setTextSize(Math.max(size * 0.6f, size * maxWidth / width));
        }
        paint.setColor(color);
        paint.setAlpha(Math.min(255, alpha));
        c.drawText(s, x, baseline, paint);
    }

    private static int fade(float distance, float range) {
        float t = Math.min(1f, distance / range);
        return Math.round(255 * (1f - t * t));
    }

    private static float deg(float radians) {
        return (float) Math.toDegrees(radians);
    }
}
