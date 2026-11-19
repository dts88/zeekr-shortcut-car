/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.drive;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.button.CarMoves;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.function.Consumer;

/**
 * 每次上车、陡坡缓降能用了就把它打开（issue 138；{@code AppConfig.isHillDescentAtStart}，设置页里还没有这一行）。
 * 什么时候写照 {@link HillDescentRules}（纯函数），问能不能用、写车和读回在 {@code button.CarMoves} 的那条线程上
 * （{@link CarMoves#hillDescentAvailable}、{@link CarMoves#restoreHillDescent}）。
 *
 * <ul>
 *   <li>屏幕亮着（车通电了）时每 {@link HillDescentRules#POLL_MS} 问一次能不能用；熄屏就停，亮屏再接着问。设置关着时不读车。
 *       车没通电、车辆接口没连着的那一会儿不问，连着的次数从头数。</li>
 *   <li>熄屏（人下车了）、车机真正开过机，记下「在等下一次能用」；打开过了清掉。存在 {@link #STATE} 里，不是设置，不进设置备份：
 *       进程在行驶中重启的，打开过一次的不再打开。设置是半路打开的，从下一次上车起。</li>
 *   <li>界面上什么都不显示，只进黑匣子（要不要提示等项目所有者定）：写车的那一行是「上车设置 hill_descent：…」
 *       （{@code CarMoves}），写满次数都没成再记一行。</li>
 * </ul>
 *
 * <p>都在主线程上。测试换上假的 {@link World}（{@code HillDescentTest}）。</p>
 */
public final class HillDescent {

    /** 存「在等下一次能用」的那一份（运行状态）：熄屏、车机真正开过机时记下，打开过了清掉；进程没了也还在。 */
    static final String STATE = "hill_descent_state";
    static final String KEY_AWAITING_AVAILABLE = "awaiting_available";

    /** 外面的东西：钟、车辆信号、设置、读车写车、黑匣子。真的是 {@link SystemWorld}；测试里是假的。 */
    interface World {
        /** 此刻（毫秒，只拿来算过了多久）。 */
        long now();

        /** 过这么久在主线程上做。 */
        void later(long delayMs, Runnable action);

        boolean setting();

        boolean linkConnected();

        Integer ignition();

        /** 问陡坡缓降能不能用（1 能、0 不能、问不了 null），答案在主线程上交回来。 */
        void readAvailable(Consumer<Integer> seen);

        /** 写车、读回，结果在主线程上交回来。 */
        void restore(Consumer<CarMoves.Result> done);

        /** 写满了次数都没成：记一行。 */
        void gaveUp(int writes);

        boolean awaitingAvailable();

        void setAwaitingAvailable(boolean awaiting);
    }

    private static HillDescent instance;

    private final World world;
    /** 每开始问、停一次加一：排着的那一下、晚到的读车写车结果对不上就不算。 */
    private int generation;
    /** 这一次上车还没打开过（照存着的，{@link World#awaitingAvailable}）。 */
    private boolean awaiting;
    /** 只用它数连着几次能用。 */
    private OffOn track = OffOn.start(false);
    /** 读车、写车还没回话。 */
    private boolean busy;
    private int writes;
    private long lastWriteAt;

    HillDescent(World world) {
        this.world = world;
    }

    /**
     * 进程一起来就装上（{@code ZeekrShortcutApp.onCreate}，在 {@link ScreenState#install} 之后）：听亮屏、熄屏；
     * 屏幕此刻知道了、亮着就开始问。重复叫无害。
     */
    public static synchronized void install(Context context) {
        if (instance != null) {
            return;
        }
        HillDescent hill = new HillDescent(new SystemWorld(context.getApplicationContext()));
        instance = hill;
        ScreenState.addListener(new ScreenState.Listener() {
            @Override
            public void onScreenOff() {
                hill.stop();
            }

            @Override
            public void onScreenOn() {
                hill.start();
            }
        });
        ScreenState.whenKnown(() -> hill.processStarted(ScreenState.dark(), BlackBox.rebootedSinceLastRun()));
    }

    // ================================================================= 输入（主线程）

    /** 进程起来、屏幕知道了：车机真正开过机的算新的一次上车；亮着就开始问。 */
    void processStarted(boolean dark, boolean rebooted) {
        if (rebooted && world.setting()) {
            world.setAwaitingAvailable(true);
        }
        if (!dark) {
            start();
        }
    }

    /** 屏幕亮着：开始每隔一会儿问一次。在不在等照存着的。 */
    void start() {
        int gen = ++generation;
        busy = false;
        writes = 0;
        awaiting = world.awaitingAvailable();
        track = OffOn.start(false);
        tick(gen);
    }

    /** 熄屏：人下车了。不问了，下一次能用就是新的一次上车（设置关着时不记）。 */
    void stop() {
        generation++;
        if (world.setting()) {
            world.setAwaitingAvailable(true);
        }
    }

    // ================================================================= 一下

    private void tick(int gen) {
        if (gen != generation) {
            return;
        }
        world.later(HillDescentRules.POLL_MS, () -> tick(gen));
        if (!world.setting()) {
            forget();
            return;
        }
        if (!world.linkConnected() || !DriveStartRules.powered(world.ignition())) {
            track = track.unknown();
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        world.readAvailable(available -> sampled(gen, available));
    }

    private void sampled(int gen, Integer available) {
        if (gen != generation) {
            return;
        }
        busy = false;
        track = track.next(available);
        switch (HillDescentRules.decide(awaiting, track.on, writes, world.now() - lastWriteAt)) {
            case WRITE:
                busy = true;
                writes++;
                world.restore(result -> restored(gen, result));
                break;
            case FAILED:
                int tried = writes;
                finish();
                world.gaveUp(tried);
                break;
            default:
                break;
        }
    }

    private void restored(int gen, CarMoves.Result result) {
        if (gen != generation) {
            return;
        }
        busy = false;
        lastWriteAt = world.now();
        if (result == CarMoves.Result.DONE) {
            finish();
        }
    }

    /** 打开过了、或者不再写了：不等了，到下一次上车。 */
    private void finish() {
        awaiting = false;
        writes = 0;
        world.setAwaitingAvailable(false);
    }

    /** 设置关着：什么都不记着，再打开设置时从下一次上车起。 */
    private void forget() {
        awaiting = false;
        track = OffOn.start(false);
        writes = 0;
        world.setAwaitingAvailable(false);
    }

    // ================================================================= 真的

    private static final class SystemWorld implements World {
        private final Context app;
        private final AppConfig config;
        private final SharedPreferences state;
        private final Handler main = new Handler(Looper.getMainLooper());

        SystemWorld(Context app) {
            this.app = app;
            this.config = new AppConfig(app);
            this.state = app.getSharedPreferences(STATE, Context.MODE_PRIVATE);
        }

        @Override
        public long now() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public void later(long delayMs, Runnable action) {
            main.postDelayed(action, delayMs);
        }

        @Override
        public boolean setting() {
            return config.isHillDescentAtStart();
        }

        @Override
        public boolean linkConnected() {
            return Telemetry.get().carLinkConnected();
        }

        @Override
        public Integer ignition() {
            return Telemetry.get().readings().code(Signal.IGNITION);
        }

        @Override
        public void readAvailable(Consumer<Integer> seen) {
            CarMoves.hillDescentAvailable(seen);
        }

        @Override
        public void restore(Consumer<CarMoves.Result> done) {
            CarMoves.restoreHillDescent(app, done);
        }

        @Override
        public void gaveUp(int writes) {
            BlackBox.note("上车设置 hill_descent：写了 " + writes + " 次都没成，这一次上车不再写");
        }

        @Override
        public boolean awaitingAvailable() {
            return state.getBoolean(KEY_AWAITING_AVAILABLE, false);
        }

        @Override
        public void setAwaitingAvailable(boolean awaiting) {
            if (awaitingAvailable() != awaiting) {
                state.edit().putBoolean(KEY_AWAITING_AVAILABLE, awaiting).apply();
            }
        }
    }
}
