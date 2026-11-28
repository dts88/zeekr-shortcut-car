/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 悬浮按钮中间的限速（开发者选项「悬浮按钮显示限速」，项目本地文档 speed-limits/spec.md §二）：
 * 有人显示（{@link #start}）时登记成车辆信号的完整用户（要定位），每来一次新的定位，在自己的线程上匹配一次（{@link LimitMatcher}），
 * 连着两次一样才换数字（{@link LimitMatcher.Tracker}），在主线程上交给 {@link Sink}。
 *
 * <p>停车时定位本来就停（车辆信号那边管），这里没有新的定位就不算；定位 5 秒没更新算没有。
 * 坐标、道路、限速值都不写日志、黑匣子。</p>
 */
public final class LimitDisplay implements Telemetry.Listener {

    /** 主线程上收：该显示的限速，null = 不显示。 */
    public interface Sink {
        void show(LimitMatcher.Match match);
    }

    private static final String TAG = "LimitDisplay";
    private static final String USER = "speed-limit";
    /** 定位多久没更新算没有。 */
    private static final long STALE_MS = 5000L;

    /** 正在跑的那一个（包换了时叫它重开，{@link #packagesChanged}）。 */
    private static volatile LimitDisplay current;

    private final Context app;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LimitMatcher.Tracker tracker = new LimitMatcher.Tracker();
    private HandlerThread thread;
    private Handler worker;
    /** 只在 worker 线程上碰。 */
    private LimitAtlas atlas;
    private LimitMatcher.Match lastShown;
    private Long lastFixAt;
    private boolean running;

    public LimitDisplay(Context context, Sink sink) {
        this.app = context.getApplicationContext();
        this.sink = sink;
    }

    /** 包装上、删掉之后叫一声：正在显示的话重开那几个包。 */
    public static void packagesChanged() {
        LimitDisplay d = current;
        if (d != null) {
            d.reload();
        }
    }

    /** 主线程上调。 */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        current = this;
        thread = new HandlerThread("SpeedLimit");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(this::openAtlas);
        Telemetry.get().addListener(this);
        Telemetry.get().acquire(app, USER);
    }

    /** 主线程上调；按钮当场回到不显示。 */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (current == this) {
            current = null;
        }
        Telemetry.get().removeListener(this);
        Telemetry.get().release(USER);
        Handler w = worker;
        HandlerThread t = thread;
        worker = null;
        thread = null;
        if (w != null) {
            w.post(() -> {
                closeAtlas();
                tracker.reset();
                lastShown = null;
            });
        }
        if (t != null) {
            t.quitSafely();
        }
        lastFixAt = null;
        sink.show(null);
    }

    private void reload() {
        Handler w = worker;
        if (w != null) {
            w.post(() -> {
                closeAtlas();
                tracker.reset();
                openAtlas();
                publish(null);
            });
        }
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        Handler w = worker;
        if (w == null) {
            return;
        }
        VehicleState v = Telemetry.get().latest();
        Long at = v.latitude == null || v.longitude == null ? null : v.positionAt;
        if (at != null && at.equals(lastFixAt)) {
            return;
        }
        if (at == null && lastFixAt == null) {
            return;
        }
        lastFixAt = at;
        w.post(() -> handle(at == null ? null : v));
    }

    /** worker 线程。 */
    private void handle(VehicleState v) {
        LimitMatcher.Match match = null;
        if (v != null && atlas != null && !atlas.isEmpty() && v.positionAt != null
                && SystemClock.elapsedRealtime() - v.positionAt <= STALE_MS) {
            try {
                match = LimitMatcher.match(atlas, v.latitude, v.longitude, v.positionAccuracy,
                        v.positionBearing, v.speedKmh);
            } catch (IOException e) {
                // 包读坏了：这一次当没匹配到，日志里只记是哪种毛病（不记位置）
                AppLog.w(TAG, "读限速数据出错: " + e.getClass().getSimpleName());
            }
        }
        publish(tracker.update(match));
    }

    /** worker 线程。 */
    private void publish(LimitMatcher.Match shown) {
        if (Objects.equals(shown, lastShown)) {
            return;
        }
        lastShown = shown;
        main.post(() -> {
            if (running) {
                sink.show(shown);
            }
        });
    }

    /** worker 线程。 */
    private void openAtlas() {
        List<String> skipped = new ArrayList<>();
        atlas = LimitAtlas.open(new LimitStore(app).files(), skipped);
        if (!skipped.isEmpty()) {
            AppLog.w(TAG, "打不开的限速包: " + skipped);
        }
    }

    /** worker 线程。 */
    private void closeAtlas() {
        if (atlas != null) {
            atlas.close();
            atlas = null;
        }
    }
}
