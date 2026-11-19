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
 *   <li>两样各记各的：一样做成了、另一样没成，再写时只写没成的那一样 —— 做成的那一样之后手动改了的不动。每次写之前照此刻的
 *       设置：没成的那一样设置里关掉了，就不再写它。</li>
 *   <li>确认关过之后还没恢复的是哪几样，存在 {@link #STATE} 里，不是设置，不进设置备份：空调关着时下车、进程后来没了，
 *       回来打开空调照样恢复；一样恢复了、另一样还没成时熄屏再亮屏、进程重启，也只写没成的那一样。</li>
 *   <li>界面上什么都不显示，只进黑匣子（要不要提示等项目所有者定）：确认关了记一行，写车的那一行是「空调设置 climate：…」
 *       （{@code CarMoves}），写满次数都没成再记一行。</li>
 * </ul>
 *
 * <p>都在主线程上。测试换上假的 {@link World}（{@code ClimateOnTest}）。</p>
 */
public final class ClimateOn {

    /** 存「确认关过、还没恢复的是哪几样」的那一份（运行状态）：确认关了两样都记下，恢复一样清一样；进程没了也还在。 */
    static final String STATE = "climate_on_state";
    static final String KEY_CIRCULATION_PENDING = "circulation_pending";
    static final String KEY_REAR_PENDING = "rear_pending";

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

        /** 确认关过之后循环模式还没恢复、二排空调还没恢复（存着的）。 */
        boolean circulationPending();

        boolean rearPending();

        void setPending(boolean circulation, boolean rear);
    }

    private static ClimateOn instance;

    private final World world;
    /** 每开始读、停一次加一：排着的那一下、晚到的读车写车结果对不上就不算。 */
    private int generation;
    private OffOn track = OffOn.NONE;
    /** 读车、写车还没回话。 */
    private boolean busy;
    private int writes;
    private long lastWriteAt;
    /** 确认关过之后还没恢复的那几样（照存着的，{@link World#circulationPending}、{@link World#rearPending}）。 */
    private boolean circulationPending;
    private boolean rearPending;
    /**
     * 空调确认关了几回（这个进程里）。写车的结果记在要写的那一回上，不看屏幕：熄屏时还在写的那一件，做成了哪一样照样记下
     * （{@link #restored}）—— 不然亮屏后两样又都写一遍。
     */
    private int cycle;

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

    /** 屏幕亮着：开始每隔一会儿读一次。还有哪几样没恢复照存着的。 */
    void start() {
        int gen = ++generation;
        busy = false;
        writes = 0;
        track = OffOn.NONE;
        circulationPending = world.circulationPending();
        rearPending = world.rearPending();
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
            // 两项设置都关着：什么都不记着，再打开设置时从头看
            track = OffOn.NONE;
            pending(false, false);
            return;
        }
        if (world.userExited() || !world.linkConnected() || !DriveStartRules.powered(world.ignition())) {
            track = OffOn.NONE;
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
        track = track.next(power);
        if (track.off == OffOn.CONFIRM) {
            // 确认关了（只读到一次关着不算）：两样都等它重新打开后恢复，写了几次从头算
            cycle++;
            writes = 0;
            pending(true, true);
            world.turnedOff();
        }
        switch (ClimateOnRules.decide(circulationPending || rearPending, track.on, writes, world.now() - lastWriteAt)) {
            case WRITE:
                // 照此刻的设置：没恢复的那一样设置里关掉了的，不再写
                int circulation = circulationPending ? ClimateOnRules.circulation(world.circulationSetting()) : 0;
                boolean rear = rearPending && world.rearSetting();
                pending(circulation != 0, rear);
                if (circulation == 0 && !rear) {
                    break;
                }
                busy = true;
                writes++;
                int asked = cycle;
                world.restore(circulation, rear, (first, second) -> restored(gen, asked, first, second));
                break;
            case FAILED:
                giveUp();
                break;
            default:
                break;
        }
    }

    private void restored(int gen, int asked, CarMoves.Result circulation, CarMoves.Result rear) {
        if (asked == cycle) {
            // 做成的那一样不再等：再写时只写没成的，做成的之后手动改了的不动。空调没再关过就记，这中间熄过屏也记
            boolean circulationLeft = circulationPending && circulation != CarMoves.Result.DONE;
            boolean rearLeft = rearPending && rear != CarMoves.Result.DONE;
            if (circulationLeft != circulationPending || rearLeft != rearPending) {
                pending(circulationLeft, rearLeft, gen == generation);
            }
        }
        if (gen != generation) {
            return;
        }
        busy = false;
        lastWriteAt = world.now();
        if ((circulationPending || rearPending) && writes >= ClimateOnRules.MAX_WRITES) {
            giveUp();
        }
    }

    /** 写满了次数都没成：不等了，到空调下一次关掉。 */
    private void giveUp() {
        int tried = writes;
        pending(false, false);
        world.gaveUp(tried);
    }

    private void pending(boolean circulation, boolean rear) {
        pending(circulation, rear, true);
    }

    /**
     * 还没恢复的是哪几样：记下、存下（进程没了、熄屏再亮屏也还在）。都恢复了，写了几次清零 —— {@code current} 是 false
     * （熄屏之前要的那一件晚到的结果）时不动写了几次：那是现在这一轮读的账。
     */
    private void pending(boolean circulation, boolean rear, boolean current) {
        circulationPending = circulation;
        rearPending = rear;
        world.setPending(circulation, rear);
        if (current && !circulation && !rear) {
            writes = 0;
        }
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
            CarMoves.frontClimatePower(app, seen);
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
        public boolean circulationPending() {
            return state.getBoolean(KEY_CIRCULATION_PENDING, false);
        }

        @Override
        public boolean rearPending() {
            return state.getBoolean(KEY_REAR_PENDING, false);
        }

        @Override
        public void setPending(boolean circulation, boolean rear) {
            if (circulationPending() != circulation || rearPending() != rear) {
                state.edit().putBoolean(KEY_CIRCULATION_PENDING, circulation).putBoolean(KEY_REAR_PENDING, rear).apply();
            }
        }
    }
}
