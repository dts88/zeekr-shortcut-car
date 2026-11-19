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
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 上车时切到设置的驾驶模式（issue 138；{@code AppConfig.getDriveModeAtStart}，设置页里还没有这一行）：车每次通电后驾驶模式
 * 回到标准，选了舒适、标准或运动，就在每次上车时切过去一次。什么时候写、什么时候算了照 {@link DriveStartRules}（纯函数），写车和读回在 {@code button.CarMoves}
 * 的那条线程上（{@link CarMoves#driveModeAtStart}）。
 *
 * <h3>什么算一次上车</h3>
 *
 * <ul>
 *   <li>亮屏那条边（{@link ScreenState} 的 E3：黑着时车通电了）。车上实测过的就是它：点火 ACC → ON、屏幕电源事件 1 → 0
 *       之后一秒多写，切成了（澳洲 7X，PR 145）。车没通电时屏幕亮了（远程开空调那一类）不是亮屏，不算。</li>
 *   <li>进程起来时屏幕已经亮着的：上一个进程最后看到的是熄屏、或者车机真正开过机才算（{@link DriveStartRules#startsAtProcessStart}）；
 *       进程在行驶中重启的不算。「在等下一次上车」（熄屏时记下、算上车时清掉）存在 {@link #STATE} 里，不是设置，不进设置备份。</li>
 * </ul>
 *
 * <p>用户退出以后（{@code UserExit}，进程可能又被系统拉起来）不写车：退出期间的那一次上车先记着，人再打开应用时补上
 * （{@link #resumeAfterExit}）。</p>
 *
 * <p>一次上车只做一遍：熄屏（人下车了）时还没做完的那一遍作废；设置关着的那一次上车什么都不做，半路打开也要等下一次上车。
 * 界面上什么都不显示，切成没切成只进黑匣子（要不要提示等项目所有者定）：写车的那一行是「上车设置 drive_mode：…」
 * （{@code CarMoves}）；这里再记没切的原因（已经挂出 P 挡、车辆信号一直没就绪、写了几次都没变），和切成
 * {@link DriveStartRules#VERIFY_MS} 之后还是不是那个模式（车自己的初始化晚到、又改回去了的话，从这一行看得出来；不再写第二次）。</p>
 *
 * <p>都在主线程上。测试换上假的 {@link World}（{@code DriveStartTest}）。</p>
 */
public final class DriveStart {

    /** 存「在等下一次上车」的那一份（运行状态）：熄屏时记下，算上一次上车时清掉；进程没了也还在。 */
    static final String STATE = "drive_start_state";
    static final String KEY_AWAITING_NEXT_DRIVE = "awaiting_next_drive";
    /** 等的时候多久再看一次。 */
    static final long POLL_MS = 500L;

    /** 外面的东西：钟、车辆信号、设置、写车、黑匣子。真的是 {@link SystemWorld}；测试里是假的。 */
    interface World {
        /** 此刻（毫秒，只拿来算过了多久）。 */
        long now();

        /** 过这么久在主线程上做。哪个线程都能叫。 */
        void later(long delayMs, Runnable action);

        /** 设置里存的值（{@code SettingsRegistry.DRIVE_MODE_AT_START}）。 */
        String setting();

        boolean linkConnected();

        Integer ignition();

        String gear();

        Integer driveMode();

        /** 用户退出了、还没再打开应用（{@code UserExit}）。 */
        boolean userExited();

        /**
         * 写车、读回，结果在主线程上交回来。写车排在别的线程上，轮到时可能已经过了几秒：临写之前在那条线程上现读一次挡位，
         * 把挡位字母（读不到 null）交给 {@code stillWanted} 再问一次，说不要了就不写，结果是 {@link CarMoves.Result#MOVING}。
         */
        void write(int mode, Predicate<String> stillWanted, Consumer<CarMoves.Result> done);

        /** 这一次上车没切成（{@code why} 不是 WAIT、WRITE）：记一行。{@code sinceStartMs} 是算上车起过了多久。 */
        void gaveUp(DriveStartRules.Decision why, int writes, boolean connected, Integer ignition, String gear,
                    long sinceStartMs);

        /** 切成 {@link DriveStartRules#VERIFY_MS} 之后读到的驾驶模式（{@code seen}，读不到 null）：记一行。 */
        void verified(int mode, Integer seen);

        boolean awaitingNextDrive();

        void setAwaitingNextDrive(boolean awaiting);
    }

    private static volatile DriveStart instance;

    private final World world;
    /** 每开始一遍、作废一遍加一：排着的那一步、晚到的写车结果对不上就不算。写车的线程临写之前也看它（{@link #stillWanted}）。 */
    private volatile int generation;
    /** 屏幕此刻亮着（{@link #resumeAfterExit} 看）。 */
    private boolean lit;
    private long startedAt;
    private int writes;
    private long lastWriteAt;

    DriveStart(World world) {
        this.world = world;
    }

    /**
     * 进程一起来就装上（{@code ZeekrShortcutApp.onCreate}，在 {@link ScreenState#install} 之后）：听亮屏、熄屏；
     * 屏幕此刻知道了再看进程起来的这一下算不算一次上车。重复叫无害。
     */
    public static synchronized void install(Context context) {
        if (instance != null) {
            return;
        }
        DriveStart start = new DriveStart(new SystemWorld(context.getApplicationContext()));
        instance = start;
        ScreenState.addListener(new ScreenState.Listener() {
            @Override
            public void onScreenOff() {
                start.screenWentDark();
            }

            @Override
            public void onScreenOn() {
                start.screenWentLit();
            }
        });
        ScreenState.whenKnown(() -> start.processStarted(ScreenState.dark(), BlackBox.rebootedSinceLastRun()));
    }

    // ================================================================= 输入（主线程）

    /** 熄屏：人下车了。没做完的那一遍作废，记下在等下一次上车。 */
    void screenWentDark() {
        lit = false;
        generation++;
        world.setAwaitingNextDrive(true);
    }

    /** 亮屏（车通电了）：一次上车。 */
    void screenWentLit() {
        lit = true;
        begin();
    }

    /** 进程起来、屏幕知道了：亮着、又算得上一次上车的就开始。 */
    void processStarted(boolean dark, boolean rebooted) {
        lit = !dark;
        if (!dark && DriveStartRules.startsAtProcessStart(world.awaitingNextDrive(), rebooted)) {
            begin();
        }
    }

    /** 用户退出后又打开了应用（{@code UserExit.clear}，哪个线程都能叫）：退出期间记着的那一次上车补上。 */
    public static void resumeAfterExit() {
        DriveStart start = instance;
        if (start != null) {
            start.world.later(0, start::userReturned);
        }
    }

    void userReturned() {
        if (lit && world.awaitingNextDrive()) {
            begin();
        }
    }

    // ================================================================= 一遍

    private void begin() {
        if (world.userExited()) {
            // 退出以后不写车：这一次上车先记着，人再打开应用时补上
            generation++;
            world.setAwaitingNextDrive(true);
            return;
        }
        world.setAwaitingNextDrive(false);
        int gen = ++generation;
        if (DriveStartRules.target(world.setting()) == 0) {
            return;
        }
        startedAt = world.now();
        writes = 0;
        lastWriteAt = startedAt;
        step(gen);
    }

    private void step(int gen) {
        if (gen != generation) {
            return;
        }
        int mode = DriveStartRules.target(world.setting());
        if (mode == 0 || world.userExited()) {
            // 等的时候在设置里关掉了、用户退出了
            generation++;
            return;
        }
        long now = world.now();
        boolean connected = world.linkConnected();
        Integer ignition = world.ignition();
        String gear = world.gear();
        DriveStartRules.Decision decision = DriveStartRules.decide(now - startedAt, connected, ignition, gear, writes,
                now - lastWriteAt);
        switch (decision) {
            case WAIT:
                world.later(POLL_MS, () -> step(gen));
                break;
            case WRITE:
                writes++;
                world.write(mode, liveGear -> stillWanted(gen, liveGear), result -> written(gen, mode, result));
                break;
            default:
                generation++;
                world.gaveUp(decision, writes, connected, ignition, gear, now - startedAt);
                break;
        }
    }

    private void written(int gen, int mode, CarMoves.Result result) {
        if (gen != generation) {
            return;
        }
        lastWriteAt = world.now();
        if (result == CarMoves.Result.DONE) {
            world.later(DriveStartRules.VERIFY_MS, () -> verify(gen, mode));
            return;
        }
        if (result == CarMoves.Result.MOVING) {
            // 轮到写的时候已经不要了（stillWanted），没写：不算写过一次。隔一会儿再判 —— 发布出来的挡位这时多半还没跟上
            // （现读的已经不是 P 挡），马上再判又会去写
            writes--;
            world.later(DriveStartRules.RETRY_MS, () -> step(gen));
            return;
        }
        step(gen);
    }

    /**
     * 写车的线程上、临写之前问：这一遍还作数、用户没退出、车还通着电、还挂着 P 挡。排队的那几秒里挂出了 P 挡的不写
     * （{@link World#write}）。挡位看的是 {@code liveGear}：写车的线程刚从车上现读的（读不到 null，不写），不是发布出来的
     * 那一份 —— 那一份合并发布，会晚几十毫秒。只读不改，哪个线程都能叫。
     */
    private boolean stillWanted(int gen, String liveGear) {
        return gen == generation && !world.userExited() && DriveStartRules.powered(world.ignition())
                && "P".equals(liveGear);
    }

    /** 切成之后再看一眼，只记一行：车自己的初始化要是晚到、又改回去了，从这里看得出来。 */
    private void verify(int gen, int mode) {
        if (gen != generation) {
            return;
        }
        world.verified(mode, world.driveMode());
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
        public String setting() {
            return config.getDriveModeAtStart();
        }

        @Override
        public boolean linkConnected() {
            return Telemetry.get().carLinkConnected();
        }

        @Override
        public Integer ignition() {
            return readings().code(Signal.IGNITION);
        }

        @Override
        public String gear() {
            return readings().text(Signal.GEAR);
        }

        @Override
        public Integer driveMode() {
            return readings().code(Signal.DRIVE_MODE);
        }

        private Readings readings() {
            return Telemetry.get().readings();
        }

        @Override
        public boolean userExited() {
            return UserExit.isExited(app);
        }

        @Override
        public void write(int mode, Predicate<String> stillWanted, Consumer<CarMoves.Result> done) {
            CarMoves.driveModeAtStart(app, mode, stillWanted, done);
        }

        @Override
        public void gaveUp(DriveStartRules.Decision why, int writes, boolean connected, Integer ignition, String gear,
                           long sinceStartMs) {
            BlackBox.note("上车设置 drive_mode：这一次上车不切（" + why + "：写过 " + writes + " 次，车辆接口"
                    + (connected ? "连着" : "没连着") + "、点火=" + hex(ignition) + "、挡位=" + (gear == null ? "?" : gear)
                    + "，上车后 +" + sinceStartMs + "ms）");
        }

        @Override
        public void verified(int mode, Integer seen) {
            BlackBox.note("上车设置 drive_mode：切成 " + DriveStartRules.VERIFY_MS / 1000 + " 秒后"
                    + (seen != null && seen == mode ? "还是 " + hex(seen) : "读到 " + hex(seen) + "，不是写的 " + hex(mode)));
        }

        private static String hex(Integer v) {
            return v == null ? "?" : String.format(Locale.US, "0x%X", v);
        }

        @Override
        public boolean awaitingNextDrive() {
            return state.getBoolean(KEY_AWAITING_NEXT_DRIVE, false);
        }

        @Override
        public void setAwaitingNextDrive(boolean awaiting) {
            if (awaitingNextDrive() != awaiting) {
                state.edit().putBoolean(KEY_AWAITING_NEXT_DRIVE, awaiting).apply();
            }
        }
    }
}
