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
import com.kooo.evcam.UserExit;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.button.CarMoves;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 前排空调关掉再打开后，恢复设置的循环模式、打开二排空调（issue 138；{@code AppConfig.getCirculationAtClimateOn}、
 * {@code isRearClimateAtClimateOn}，设置页里还没有这两行）。什么时候算重新打开、什么时候写照 {@link ClimateOnRules}（纯函数），
 * 读电源、写车和读回在 {@code button.CarMoves} 的那条线程上（{@link CarMoves#frontClimatePower}、{@link CarMoves#restoreClimate}）。
 *
 * <ul>
 *   <li>屏幕亮着（车通电了）时每 {@link ClimateOnRules#POLL_MS} 读一次前排空调的电源；熄屏就停，亮屏再接着读。
 *       两项设置都关着时不读车。车没通电、车辆接口没连着、用户退出了还没再打开应用（{@code UserExit}）的那一会儿不读，
 *       连着的次数从头数。</li>
 *   <li>两样各记各的：一样做成了、另一样没成，再写时只写没成的那一样 —— 做成的那一样之后手动改了的不动。</li>
 *   <li>「在等空调重新打开」（确认关过、还没恢复）存在 {@link #STATE} 里，不是设置，不进设置备份：空调关着时下车、进程后来没了，
 *       回来打开空调照样恢复。</li>
 *   <li>界面上什么都不显示，只进黑匣子（要不要提示等项目所有者定）：确认关了记一行，写车的那一行是「空调设置 climate：…」
 *       （{@code CarMoves}），写满次数都没成再记一行。</li>
 * </ul>
 *
 * <p>都在主线程上。测试换上假的 {@link World}（{@code ClimateOnTest}）。</p>
 */
public final class ClimateOn {

    /** 存「在等空调重新打开」的那一份（运行状态）：确认关了记下，恢复过了清掉；进程没了也还在。 */
    static final String STATE = "climate_on_state";
    static final String KEY_AWAITING_CLIMATE_ON = "awaiting_climate_on";

    /** 外面的东西：钟、车辆信号、设置、读车写车、黑匣子。真的是 {@link SystemWorld}；测试里是假的。 */
    interface World {
        /** 此刻（毫秒，只拿来算过了多久）。 */
        long now();

        /** 过这么久在主线程上做。 */
        void later(long delayMs, Runnable action);

        /** 设置里存的值（{@code SettingsRegistry.CIRCULATION_AT_CLIMATE_ON}）。 */
        String circulationSetting();

        boolean rearSetting();

        boolean linkConnected();

        Integer ignition();

        /** 用户退出了、还没再打开应用（{@code UserExit}）。 */
        boolean userExited();

        /** 读前排空调的电源（1 开、0 关，读不到 null），读到的在主线程上交回来。 */
        void readFrontPower(Consumer<Integer> seen);

        /** 写车、读回，两样各自的结果在主线程上交回来（先循环模式、后二排空调；没要的那一样是 null）。 */
        void restore(int circulation, boolean rear, BiConsumer<CarMoves.Result, CarMoves.Result> done);

        /** 确认前排空调关了：记一行。 */
        void turnedOff();

        /** 写满了次数都没成：记一行。 */
        void gaveUp(int writes);

        boolean awaitingClimateOn();

        void setAwaitingClimateOn(boolean awaiting);
    }

    private static ClimateOn instance;

    private final World world;
    /** 每开始读、停一次加一：排着的那一下、晚到的读车写车结果对不上就不算。 */
    private int generation;
    private OffOn track = OffOn.start(false);
    /** 读车、写车还没回话。 */
    private boolean busy;
    private int writes;
    private long lastWriteAt;
    /** 空调这一次打开还没做成的：循环模式（0 是不用写了）、二排空调。第一次写之前照设置定。 */
    private int circulationLeft;
    private boolean rearLeft;

    ClimateOn(World world) {
        this.world = world;
    }

    /**
     * 进程一起来就装上（{@code ZeekrShortcutApp.onCreate}，在 {@link ScreenState#install} 之后）：听亮屏、熄屏；
     * 屏幕此刻知道了、亮着就开始读。重复叫无害。
     */
    public static synchronized void install(Context context) {
        if (instance != null) {
            return;
        }
        ClimateOn climate = new ClimateOn(new SystemWorld(context.getApplicationContext()));
        instance = climate;
        ScreenState.addListener(new ScreenState.Listener() {
            @Override
            public void onScreenOff() {
                climate.stop();
            }

            @Override
            public void onScreenOn() {
                climate.start();
            }
        });
        ScreenState.whenKnown(() -> {
            if (!ScreenState.dark()) {
                climate.start();
            }
        });
    }

    // ================================================================= 输入（主线程）

    /** 屏幕亮着：开始每隔一会儿读一次。在不在等空调重新打开照存着的。 */
    void start() {
        int gen = ++generation;
        busy = false;
        writes = 0;
        track = OffOn.start(world.awaitingClimateOn());
        tick(gen);
    }

    /** 熄屏：不读了。 */
    void stop() {
        generation++;
    }

    // ================================================================= 一下

    private void tick(int gen) {
        if (gen != generation) {
            return;
        }
        world.later(ClimateOnRules.POLL_MS, () -> tick(gen));
        if (ClimateOnRules.circulation(world.circulationSetting()) == 0 && !world.rearSetting()) {
            forget();
            return;
        }
        if (world.userExited() || !world.linkConnected() || !DriveStartRules.powered(world.ignition())) {
            track = track.unknown();
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        world.readFrontPower(power -> sampled(gen, power));
    }

    private void sampled(int gen, Integer power) {
        if (gen != generation) {
            return;
        }
        busy = false;
        boolean wasAwaiting = track.awaiting;
        track = track.next(power);
        if (track.off >= OffOn.CONFIRM) {
            // 确认又关了：下一次打开从头算写了几次、要写哪几样。只读到一次关着不算（同确认关着，一次不作数）
            writes = 0;
        }
        if (!wasAwaiting && track.awaiting) {
            world.setAwaitingClimateOn(true);
            world.turnedOff();
        }
        switch (ClimateOnRules.decide(track, writes, world.now() - lastWriteAt)) {
            case WRITE:
                if (writes == 0) {
                    circulationLeft = ClimateOnRules.circulation(world.circulationSetting());
                    rearLeft = world.rearSetting();
                }
                if (circulationLeft == 0 && !rearLeft) {
                    forget();
                    break;
                }
                busy = true;
                writes++;
                world.restore(circulationLeft, rearLeft, (circulation, rear) -> restored(gen, circulation, rear));
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

    private void restored(int gen, CarMoves.Result circulation, CarMoves.Result rear) {
        if (gen != generation) {
            return;
        }
        busy = false;
        lastWriteAt = world.now();
        if (circulation == CarMoves.Result.DONE) {
            circulationLeft = 0;
        }
        if (rear == CarMoves.Result.DONE) {
            rearLeft = false;
        }
        if (circulationLeft == 0 && !rearLeft) {
            finish();
        }
    }

    /** 恢复过了、或者不再写了：不等了，到空调下一次关掉。 */
    private void finish() {
        track = track.done();
        writes = 0;
        world.setAwaitingClimateOn(false);
    }

    /** 两项设置都关着：什么都不记着，再打开设置时从头看。 */
    private void forget() {
        track = OffOn.start(false);
        writes = 0;
        world.setAwaitingClimateOn(false);
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
        public String circulationSetting() {
            return config.getCirculationAtClimateOn();
        }

        @Override
        public boolean rearSetting() {
            return config.isRearClimateAtClimateOn();
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
        public boolean userExited() {
            return UserExit.isExited(app);
        }

        @Override
        public void readFrontPower(Consumer<Integer> seen) {
            CarMoves.frontClimatePower(seen);
        }

        @Override
        public void restore(int circulation, boolean rear, BiConsumer<CarMoves.Result, CarMoves.Result> done) {
            CarMoves.restoreClimate(app, circulation, rear, done);
        }

        @Override
        public void turnedOff() {
            BlackBox.note("空调设置 climate：前排空调关了，等它重新打开");
        }

        @Override
        public void gaveUp(int writes) {
            BlackBox.note("空调设置 climate：写了 " + writes + " 次都没成，这一次打开不再写");
        }

        @Override
        public boolean awaitingClimateOn() {
            return state.getBoolean(KEY_AWAITING_CLIMATE_ON, false);
        }

        @Override
        public void setAwaitingClimateOn(boolean awaiting) {
            if (awaitingClimateOn() != awaiting) {
                state.edit().putBoolean(KEY_AWAITING_CLIMATE_ON, awaiting).apply();
            }
        }
    }
}
