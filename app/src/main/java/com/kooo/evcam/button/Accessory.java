/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

public enum Accessory {

    BUTTON("ZEEKR_button", SmartButtonAd.LONG, 100L, 0L, false,
            new int[]{SmartButtonAd.SINGLE, SmartButtonAd.DOUBLE}),
    KNOB("ZEEKR_knob", SmartButtonAd.RELEASE, 50L, 500L, true,
            new int[]{SmartButtonAd.SINGLE, SmartButtonAd.DOUBLE, SmartButtonAd.CLOCKWISE, SmartButtonAd.ANTICLOCKWISE});

    public final String bleName;
    public final int connectGesture;
    final long subscribeDelayMs;
    final long firstQueryDelayMs;
    final boolean serialFirst;
    private final int[] actionGestures;

    Accessory(String bleName, int connectGesture, long subscribeDelayMs, long firstQueryDelayMs, boolean serialFirst,
              int[] actionGestures) {
        this.bleName = bleName;
        this.connectGesture = connectGesture;
        this.subscribeDelayMs = subscribeDelayMs;
        this.firstQueryDelayMs = firstQueryDelayMs;
        this.serialFirst = serialFirst;
        this.actionGestures = actionGestures;
    }

    public int[] actionGestures() {
        return actionGestures.clone();
    }

    public boolean hasAction(int gesture) {
        for (int g : actionGestures) {
            if (g == gesture) {
                return true;
            }
        }
        return false;
    }

    public static Accessory ofName(String name) {
        if (name != null) {
            for (Accessory kind : values()) {
                if (kind.bleName.equals(name)) {
                    return kind;
                }
            }
        }
        return null;
    }

    public static boolean isTurn(int gesture) {
        return gesture == SmartButtonAd.CLOCKWISE || gesture == SmartButtonAd.ANTICLOCKWISE;
    }
}
