package com.kooo.evcam.zeekr;

import com.kooo.evcam.AppConfig;

/** Shared size limits for the settings slider and an on-screen pinch. */
public final class SidePopupSizing {
    private SidePopupSizing() { }

    public static int size(int percent, int screenWidth, int screenHeight) {
        int maxSize = Math.max(1, screenWidth / 2);
        int bounded = Math.max(AppConfig.SIDE_POPUP_MIN_SIZE_PERCENT,
                Math.min(AppConfig.SIDE_POPUP_MAX_SIZE_PERCENT, percent));
        return Math.min(maxSize, Math.max(AppConfig.REARVIEW_MIN_SIZE,
                Math.round(Math.max(0, screenHeight) * bounded / 100f)));
    }

    public static int resizedPercent(int startSize, float scale, int screenHeight) {
        if (screenHeight <= 0) {
            return AppConfig.SIDE_POPUP_DEFAULT_SIZE_PERCENT;
        }
        float safeScale = Float.isFinite(scale) && scale > 0f ? scale : 1f;
        int percent = Math.round(Math.max(0, startSize) * safeScale * 100f / screenHeight);
        return Math.max(AppConfig.SIDE_POPUP_MIN_SIZE_PERCENT,
                Math.min(AppConfig.SIDE_POPUP_MAX_SIZE_PERCENT, percent));
    }

    public static int clampPosition(int coordinate, int screenExtent, int windowExtent) {
        return Math.max(0, Math.min(Math.max(0, screenExtent - windowExtent), coordinate));
    }
}
