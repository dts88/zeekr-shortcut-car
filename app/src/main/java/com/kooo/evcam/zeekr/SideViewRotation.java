package com.kooo.evcam.zeekr;

import android.graphics.Canvas;

/** Per-camera roll adjustment, applied to the image before labels and hints. */
public final class SideViewRotation {
    public static final int MAX_DEGREES = 90;

    private SideViewRotation() { }

    public static int clamp(int degrees) {
        return Math.max(-MAX_DEGREES, Math.min(MAX_DEGREES, degrees));
    }

    /** Scale a rotated image enough to cover every corner of its original window. */
    public static float coverScale(int degrees, int width, int height) {
        if (width <= 0 || height <= 0) {
            return 1f;
        }
        double angle = Math.toRadians(clamp(degrees));
        double c = Math.abs(Math.cos(angle));
        double s = Math.abs(Math.sin(angle));
        double aspect = (double) width / height;
        return (float) Math.max(1.0, Math.max(c + s / aspect, c + s * aspect));
    }

    public static int begin(Canvas canvas, int degrees, int width, int height) {
        int save = canvas.save();
        canvas.clipRect(0, 0, width, height);
        int angle = clamp(degrees);
        if (angle != 0) {
            float scale = coverScale(angle, width, height);
            canvas.rotate(angle, width / 2f, height / 2f);
            canvas.scale(scale, scale, width / 2f, height / 2f);
        }
        return save;
    }
}
