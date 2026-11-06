/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.kooo.evcam.AppLog;

/**
 * 灵动贴「语音与媒体」那几项里不碰车的（2026-11-05，用安卓标准的做法，Lab 3.5.x）：
 * 唤醒语音助手、媒体键、媒体静音。在主线程上做，做成了返回 true。音量加减要先看超级省电，走 {@link CarMoves#volume}。
 */
enum MediaMove {
    /** 系统的语音入口（{@link Intent#ACTION_VOICE_COMMAND}）；车机上没有接它的就做不了。 */
    VOICE,
    PLAY_PAUSE,
    PREVIOUS,
    NEXT,
    /** 媒体静音 / 取消静音。 */
    MUTE;

    private static final String TAG = "MediaMove";

    boolean perform(Context app) {
        try {
            switch (this) {
                case VOICE:
                    return voice(app);
                case PLAY_PAUSE:
                    return key(app, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                case PREVIOUS:
                    return key(app, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                case NEXT:
                    return key(app, KeyEvent.KEYCODE_MEDIA_NEXT);
                case MUTE:
                default:
                    AudioManager audio = app.getSystemService(AudioManager.class);
                    if (audio == null) {
                        return false;
                    }
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                    return true;
            }
        } catch (RuntimeException e) {
            AppLog.w(TAG, this + " failed: " + e);
            return false;
        }
    }

    /** 直接打开、没有接它的就抛（不先问系统：安卓 11 起不登记就问不到别的应用，问了也白问）。 */
    private static boolean voice(Context app) {
        Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(intent);
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            return false;
        }
    }

    /** 一个媒体键：按下、松开各发一次（正在放音乐的应用收）。 */
    private static boolean key(Context app, int code) {
        AudioManager audio = app.getSystemService(AudioManager.class);
        if (audio == null) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        return true;
    }
}
