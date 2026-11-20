/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 地点与自动化跑起来的那一个（ui-spec §十八）：收触发、判条件、一个一个动作去做，触发结束时恢复。骨架照 Calvin 的 PR 261
 * （上车切驾驶模式、空调重新打开后恢复循环模式和二排空调、陡坡缓降能用时打开 —— 三套合成这一套，那三件现在是三条规则）。
 *
 * <h3>触发从哪来</h3>
 *
 * <ul>
 *   <li>上车：亮屏那条边（{@link ScreenState} 的 E3：黑着时车通电了）；进程起来时屏幕已经亮着的，上一个进程最后看到的是熄屏、
 *       或者车机真正开过机才算（{@link RunRules#startsAtProcessStart}）。「在等下一次上车」存在 {@link #STATE} 里。</li>
 *   <li>熄屏（人下车了）：上车的那几次就此结束。人下车以后不再写车（项目所有者 2026-11-21），所以没有「离车」这个触发。</li>
 *   <li>挂入某一挡：发布出来的挡位从别的挡变成这一挡；离开这一挡时，这几次结束。</li>
 *   <li>某项设置变为某个值：有开着的规则要看的那几样，亮屏、车通电时每秒读一次（{@link RunRules#WATCH_MS}），
 *       连着两次读到才算（{@link Steady}）；确认过的值存在 {@link #STATE} 里，进程没了回来也认得出变化
 *       （Calvin：空调关着时下车，回来打开空调照样恢复）。自动化写的值不算（{@link RunRules#OWN_WRITE_MS}，不连锁）。</li>
 * </ul>
 *
 * <p>用户退出以后（{@code UserExit}）不写车：退出期间的那一次上车先记着，人再打开应用时补上（{@link #resumeAfterExit}）。
 * 黑匣子写第几条、什么触发、做了什么或为什么没做；不写名字。都在主线程上，测试换上假的 {@link World}（{@code AutomationsTest}）。</p>
 */
public final class Automations {

    /** 运行状态（不是设置，不进设置备份）：在等下一次上车、每一样确认过的值。 */
    static final String STATE = "automation_state";
    static final String KEY_AWAITING_NEXT_DRIVE = "awaiting_next_drive";
    static final String KEY_CONFIRMED = "confirmed_";
    /** 写成之后隔这么久再读一眼，车自己改回去了的记一行（Calvin：驾驶模式那一条；不写第二次）。 */
    static final long VERIFY_MS = 5_000L;

    /** 外面的东西：钟、规则、车辆信号、读写车、黑匣子、运行状态。真的是 {@link SystemWorld}；测试里是假的。 */
    interface World {
        long now();

        /** 过这么久在主线程上做。哪个线程都能叫。 */
        void later(long delayMs, Runnable action);

        /** 存着的规则（照列表的先后）。 */
        List<Rule> rules();

        boolean linkConnected();

        /** 点火的原值（{@link Signal#IGNITION}），读不到 null。 */
        Integer ignition();

        /** 挡位字母，读不到 null。 */
        String gear();

        /** 电量（%），读不到 null。 */
        Integer battery();

        int minuteOfDay();

        /** 0 星期一 …… 6 星期日。 */
        int weekday();

        boolean userExited();

        /** 读一样此刻的值（{@link Setting} 的码），在主线程上交回来；读不到 null。 */
        void read(Setting setting, Consumer<Integer> seen);

        /** 这一样此刻能不能写：1 能、0 暂时不能、问不了 null。 */
        void available(Setting setting, Consumer<Integer> seen);

        /**
         * 写一样、读回，结果和写之前读到的值在主线程上交回来。临写之前在写车的线程上现读点火、挡位，交给 {@code stillWanted}
         * 再问一次（{@code CarMoves.automationJob}）。
         */
        void write(String key, Setting setting, int value, BiPredicate<Integer, String> stillWanted,
                   Consumer<CarMoves.Outcome> done);

        /** 结束时恢复：此刻还是 {@code ours} 才写回 {@code previous}。 */
        void restore(String key, Setting setting, int ours, int previous);

        void note(String line);

        boolean awaitingNextDrive();

        void setAwaitingNextDrive(boolean awaiting);

        /** 这一样上一次确认过的值（存着的），没有 null。 */
        Integer confirmed(Setting setting);

        void setConfirmed(Setting setting, Integer value);

        /** 要不要读电量（电量是浮点读数：要登记完整的车辆信号）。 */
        void wantBattery(boolean want);
    }

    private static volatile Automations instance;

    private final World world;
    /** 屏幕此刻亮着。 */
    private boolean lit;
    /** 上一次发布出来的挡位（挂入某一挡看它变）。 */
    private String lastGear;
    /** 还在走、或者等着结束时恢复的那几次。 */
    private final List<Run> runs = new ArrayList<>();
    /** 每秒读一次的那几样（有开着的规则要看）。 */
    private final Map<Setting, Watch> watches = new EnumMap<>(Setting.class);
    /** 自动化最近写的值和时刻（不连锁）。 */
    private final Map<Setting, long[]> ownWrites = new EnumMap<>(Setting.class);
    /** 每秒读一次的那一圈：熄屏、重新开始时加一，排着的那一下对不上就不算。 */
    private int watchGen;
    private boolean batteryWanted;

    Automations(World world) {
        this.world = world;
    }

    /**
     * 进程一起来就装上（{@code ZeekrShortcutApp.onCreate}，在 {@link ScreenState#install} 之后）：听亮屏、熄屏、车辆信号；
     * 屏幕此刻知道了再看进程起来的这一下算不算一次上车。重复叫无害。一条规则都没开时不读车。
     */
    public static synchronized void install(Context context) {
        if (instance != null) {
            return;
        }
        Automations a = new Automations(new SystemWorld(context.getApplicationContext()));
        instance = a;
        ScreenState.addListener(new ScreenState.Listener() {
            @Override
            public void onScreenOff() {
                a.screenWentDark();
            }

            @Override
            public void onScreenOn() {
                a.screenWentLit();
            }
        });
        Telemetry.get().addListener(readings -> a.readingsChanged());
        ScreenState.whenKnown(() -> a.processStarted(ScreenState.dark(), BlackBox.rebootedSinceLastRun()));
    }

    /** 用户退出后又打开了应用（{@code UserExit.clear}，哪个线程都能叫）：退出期间记着的那一次上车补上。 */
    public static void resumeAfterExit() {
        Automations a = instance;
        if (a != null) {
            a.world.later(0, a::userReturned);
        }
    }

    // ================================================================= 输入（主线程）

    /** 熄屏：人下车了。上车的那几次结束（勾了恢复的恢复），记下在等下一次上车。之后不再写车。 */
    void screenWentDark() {
        lit = false;
        watchGen++;
        world.setAwaitingNextDrive(true);
        wantBattery(false);
        endRuns(t -> t.kind == Trigger.Kind.GET_IN);
    }

    /** 亮屏（车通电了）：一次上车。 */
    void screenWentLit() {
        lit = true;
        startWatching();
        getIn();
    }

    /** 进程起来、屏幕知道了：亮着就开始看；又算得上一次上车的就是一次上车。 */
    void processStarted(boolean dark, boolean rebooted) {
        lit = !dark;
        if (lit) {
            startWatching();
            if (RunRules.startsAtProcessStart(world.awaitingNextDrive(), rebooted)) {
                getIn();
            }
        }
    }

    void userReturned() {
        if (lit && world.awaitingNextDrive()) {
            getIn();
        }
    }

    /** 车辆信号变了：挡位换了就是挂入那一挡（离开原来那一挡）。 */
    void readingsChanged() {
        String gear = world.gear();
        if (gear == null) {
            return;
        }
        String old = lastGear;
        lastGear = gear;
        if (old == null || old.equals(gear)) {
            return;
        }
        endRuns(t -> t.kind == Trigger.Kind.GEAR && old.equals(t.gear));
        fire(Trigger.gear(gear));
    }

    private void getIn() {
        if (world.userExited()) {
            // 退出以后不写车：这一次上车先记着，人再打开应用时补上
            world.setAwaitingNextDrive(true);
            return;
        }
        world.setAwaitingNextDrive(false);
        fire(Trigger.getIn());
    }

    // ================================================================= 车辆状态（每秒读一次的那几样）

    private static final class Watch {
        Steady steady = Steady.NONE;
        boolean busy;
    }

    private void startWatching() {
        int gen = ++watchGen;
        watches.clear();
        world.later(0, () -> watchTick(gen));
    }

    private void watchTick(int gen) {
        if (gen != watchGen || !lit) {
            return;
        }
        List<Rule> rules = world.rules();
        if (!anyEnabled(rules)) {
            // 一条开着的规则都没有：不读车，也不再排下一次；规则变了（rulesChanged）、下一次亮屏再看
            watches.clear();
            wantBattery(false);
            return;
        }
        world.later(RunRules.WATCH_MS, () -> watchTick(gen));
        wantBattery(needsBattery(rules));
        Set<Setting> wanted = watched(rules);
        watches.keySet().retainAll(wanted);
        if (wanted.isEmpty() || world.userExited() || !world.linkConnected() || !RunRules.powered(world.ignition())) {
            // 没要看的、读不了：连着的次数从头数
            watches.clear();
            return;
        }
        for (Setting s : wanted) {
            Watch w = watches.get(s);
            if (w == null) {
                w = new Watch();
                watches.put(s, w);
            }
            if (w.busy) {
                continue;
            }
            w.busy = true;
            Watch watch = w;
            world.read(s, v -> seen(gen, s, watch, v));
        }
    }

    private void seen(int gen, Setting s, Watch w, Integer v) {
        w.busy = false;
        if (gen != watchGen || watches.get(s) != w) {
            return;
        }
        w.steady = w.steady.next(v);
        if (!w.steady.justConfirmed()) {
            return;
        }
        int now = w.steady.value;
        Integer prev = world.confirmed(s);
        if (prev != null && prev == now) {
            return;
        }
        world.setConfirmed(s, now);
        if (prev == null) {
            // 第一次读到：不算变化
            return;
        }
        String from = s.value(prev);
        String to = s.value(now);
        if (from != null) {
            endRuns(t -> t.kind == Trigger.Kind.STATE && t.setting == s && from.equals(t.value));
        }
        if (to == null) {
            return;
        }
        if (own(s, now)) {
            world.note("自动化 " + s.key + "->" + to + "：是自动化写的，不算触发");
            return;
        }
        fire(Trigger.state(s, to));
    }

    /** 开着的规则要看的那几样：车辆状态的触发、条件里的设置。 */
    private static Set<Setting> watched(List<Rule> rules) {
        Set<Setting> set = EnumSet.noneOf(Setting.class);
        for (Rule r : rules) {
            if (!r.enabled) {
                continue;
            }
            if (r.trigger.kind == Trigger.Kind.STATE) {
                set.add(r.trigger.setting);
            }
            for (Condition c : r.conditions) {
                if (c.kind == Condition.Kind.STATE_IS) {
                    set.add(c.setting);
                }
            }
        }
        return set;
    }

    private static boolean anyEnabled(List<Rule> rules) {
        for (Rule r : rules) {
            if (r.enabled) {
                return true;
            }
        }
        return false;
    }

    /** 规则改了（{@code AppConfig.onAutomationRulesChanged}：设置页存了、设置备份导入了）：亮着就重新开始看。 */
    private static void rulesChanged() {
        Automations a = instance;
        if (a != null) {
            a.world.later(0, () -> {
                if (a.lit) {
                    a.startWatching();
                }
            });
        }
    }

    private static boolean needsBattery(List<Rule> rules) {
        for (Rule r : rules) {
            if (r.enabled && r.needsBattery()) {
                return true;
            }
        }
        return false;
    }

    private void wantBattery(boolean want) {
        if (want != batteryWanted) {
            batteryWanted = want;
            world.wantBattery(want);
        }
    }

    private void markOwn(Setting s, int code) {
        ownWrites.put(s, new long[]{code, world.now()});
    }

    private boolean own(Setting s, int code) {
        long[] w = ownWrites.get(s);
        return w != null && w[0] == code && world.now() - w[1] <= RunRules.OWN_WRITE_MS;
    }

    // ================================================================= 一次触发

    private enum Status {
        WAITING, DONE, SKIPPED, FAILED
    }

    /** 一个动作这一次的账。 */
    private static final class Step {
        final Change change;
        Status status = Status.WAITING;
        /** 写车、问能不能用还没回话。 */
        boolean busy;
        int writes;
        long lastWriteAt;
        long lastAskAt = Long.MIN_VALUE / 2;
        /** 临写之前说不要了（不是挂出 P 挡）：这之前不再写。 */
        long notBefore;
        Steady available = Steady.NONE;
        /** 写之前读到的值（恢复用），读不到 null。 */
        Integer before;
        /** 临写之前从车上读到不是 P（写车的线程上记）。 */
        volatile boolean leftPark;

        Step(Change change) {
            this.change = change;
        }
    }

    /** 一条规则被触发的这一次。 */
    private final class Run {
        final Rule rule;
        /** 第几条（列表里的位置，从 1 起）：黑匣子用，不写名字。 */
        final int number;
        final Trigger trigger;
        final long startedAt;
        final List<Step> steps = new ArrayList<>();
        /** 触发结束了、或者算了（写车的线程临写之前也看它）。 */
        volatile boolean ended;
        boolean checked;

        Run(Rule rule, int number, Trigger trigger, long startedAt) {
            this.rule = rule;
            this.number = number;
            this.trigger = trigger;
            this.startedAt = startedAt;
            for (Change c : rule.changes) {
                steps.add(new Step(c));
            }
        }

        String tag() {
            return "#" + number + " " + trigger.describe();
        }
    }

    /** 照列表的先后，开着的、当是这个的每条规则开一次。 */
    private void fire(Trigger trigger) {
        if (trigger == null) {
            return;
        }
        List<Rule> rules = world.rules();
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (r.enabled && trigger.equals(r.trigger)) {
                Run run = new Run(r, i + 1, trigger, world.now());
                runs.add(run);
                tick(run);
            }
        }
    }

    private void tick(Run run) {
        if (run.ended) {
            return;
        }
        if (world.userExited()) {
            if (run.trigger.kind == Trigger.Kind.GET_IN) {
                world.setAwaitingNextDrive(true);
            }
            drop(run, "用户退出了，没做");
            return;
        }
        long now = world.now();
        long since = now - run.startedAt;
        boolean ready = world.linkConnected() && RunRules.powered(world.ignition())
                && (run.trigger.kind != Trigger.Kind.GET_IN || since >= RunRules.SETTLE_MS);
        if (!run.checked) {
            if (!ready) {
                if (since > RunRules.WINDOW_MS) {
                    drop(run, "车辆接口、点火一直没就绪（" + (world.linkConnected() ? "连着" : "没连着") + "），没做");
                } else {
                    world.later(RunRules.TICK_MS, () -> tick(run));
                }
                return;
            }
            int failed = failedCondition(run.rule);
            if (failed == UNKNOWN) {
                if (since > RunRules.WINDOW_MS) {
                    drop(run, "条件要的读数一直读不到，没做");
                } else {
                    world.later(RunRules.TICK_MS, () -> tick(run));
                }
                return;
            }
            if (failed > 0) {
                drop(run, "条件 " + failed + "（" + run.rule.conditions.get(failed - 1).describe() + "）不成立，没做");
                return;
            }
            run.checked = true;
            world.note("自动化 " + run.tag() + "：条件成立，开始");
        }
        boolean waiting = false;
        for (Step step : run.steps) {
            if (step.status != Status.WAITING) {
                continue;
            }
            if (!step.busy) {
                advance(run, step, now, since, ready);
            }
            waiting |= step.status == Status.WAITING;
        }
        if (waiting) {
            world.later(RunRules.TICK_MS, () -> tick(run));
        } else if (!run.rule.restores()) {
            // 都做完了、不用恢复：不再记着
            runs.remove(run);
        }
    }

    private static final int UNKNOWN = -1;

    /** 条件判一次：都成立 0，第几条不成立（从 1 起），要的读数读不到 {@link #UNKNOWN}。 */
    private int failedCondition(Rule rule) {
        Condition.Facts facts = new Condition.Facts() {
            @Override
            public String gear() {
                return world.gear();
            }

            @Override
            public Integer battery() {
                return world.battery();
            }

            @Override
            public Integer state(Setting setting) {
                Watch w = watches.get(setting);
                return w != null && w.steady.confirmed() ? w.steady.value : null;
            }

            @Override
            public int minuteOfDay() {
                return world.minuteOfDay();
            }

            @Override
            public int weekday() {
                return world.weekday();
            }
        };
        boolean unknown = false;
        for (int i = 0; i < rule.conditions.size(); i++) {
            Boolean holds = rule.conditions.get(i).holds(facts);
            if (holds == null) {
                unknown = true;
            } else if (!holds) {
                return i + 1;
            }
        }
        return unknown ? UNKNOWN : 0;
    }

    private void advance(Run run, Step step, long now, long since, boolean ready) {
        if (now < step.notBefore) {
            return;
        }
        Setting s = step.change.setting;
        String gear = world.gear();
        // 能用：连着两次都说能（1）；连着两次说不能（0）不算
        boolean available = step.available.confirmed() && Integer.valueOf(1).equals(step.available.value);
        switch (RunRules.decide(since, ready, s.parkOnly(), gear, s.waitsUntilAvailable(), available,
                now - step.lastAskAt, step.writes, now - step.lastWriteAt)) {
            case WAIT:
                return;
            case ASK:
                step.busy = true;
                step.lastAskAt = now;
                world.available(s, v -> {
                    step.busy = false;
                    step.available = step.available.next(v);
                });
                return;
            case WRITE:
                step.busy = true;
                step.writes++;
                world.write("#" + run.number + " " + step.change.describe(), s, step.change.code(),
                        (ignition, liveGear) -> stillWanted(run, step, ignition, liveGear),
                        outcome -> written(run, step, outcome));
                return;
            case LEFT_PARK:
                finish(run, step, Status.SKIPPED, "已挂出 P 挡（" + gear + "），没写");
                return;
            case TIMED_OUT:
                finish(run, step, Status.SKIPPED, "等了 " + RunRules.WINDOW_MS / 1000 + " 秒没就绪，没写");
                return;
            default:
                finish(run, step, Status.FAILED, "写了 " + step.writes + " 次都没成");
        }
    }

    /**
     * 写车的线程上、临写之前问：这一次还作数、用户没退出、车还通着电、只在 P 挡写的还挂着 P 挡。点火、挡位是写车的线程刚从
     * 车上现读的，不是发布出来的那一份（Calvin：那一份合并发布，会晚几十毫秒）。读到了不是 P 的挡位记下来：这一次就此不写。
     */
    private boolean stillWanted(Run run, Step step, Integer ignition, String gear) {
        boolean parkOnly = step.change.setting.parkOnly();
        if (parkOnly && gear != null && !"P".equals(gear)) {
            step.leftPark = true;
        }
        return !run.ended && !world.userExited() && RunRules.powered(ignition) && (!parkOnly || "P".equals(gear));
    }

    private void written(Run run, Step step, CarMoves.Outcome outcome) {
        step.busy = false;
        step.lastWriteAt = world.now();
        if (outcome.result == CarMoves.Result.DONE) {
            step.before = outcome.before;
            markOwn(step.change.setting, step.change.code());
            finish(run, step, Status.DONE, null);
            if (run.ended) {
                // 写的时候触发已经结束了：马上恢复
                restore(run, step);
            } else {
                world.later(VERIFY_MS, () -> verify(run, step));
            }
            return;
        }
        if (outcome.result == CarMoves.Result.MOVING) {
            // 临写之前说不要了，没写：不算写过一次
            step.writes--;
            if (step.leftPark) {
                finish(run, step, Status.SKIPPED, "临写前读到已挂出 P 挡，没写");
            } else {
                // 别的原因（车刚断电、点火一时读不到）：隔一会儿再判，不马上再去写
                step.notBefore = world.now() + RunRules.RETRY_MS;
            }
            return;
        }
        if (!run.ended && step.writes >= RunRules.MAX_WRITES) {
            finish(run, step, Status.FAILED, "写了 " + step.writes + " 次都没成");
        }
    }

    /** 写成之后再看一眼，只记一行：车自己的初始化要是晚到、又改回去了，从这里看得出来（不再写第二次）。 */
    private void verify(Run run, Step step) {
        if (run.ended) {
            return;
        }
        Setting s = step.change.setting;
        world.read(s, v -> {
            if (v == null || v != step.change.code()) {
                world.note("自动化 " + run.tag() + " " + step.change.describe() + "：写成 " + VERIFY_MS / 1000
                        + " 秒后读到 " + s.value(v) + "，车自己改回去了");
            }
        });
    }

    private void finish(Run run, Step step, Status status, String why) {
        step.status = status;
        if (why != null) {
            world.note("自动化 " + run.tag() + " " + step.change.describe() + "：" + why);
        }
    }

    /** 这一次算了（还没写任何东西）。 */
    private void drop(Run run, String why) {
        run.ended = true;
        runs.remove(run);
        world.note("自动化 " + run.tag() + "：" + why);
    }

    /** 触发结束了：这几次不再写，勾了恢复的把做成的那几样写回原来的值。 */
    private void endRuns(Predicate<Trigger> which) {
        for (Iterator<Run> it = runs.iterator(); it.hasNext(); ) {
            Run run = it.next();
            if (!which.test(run.trigger)) {
                continue;
            }
            it.remove();
            run.ended = true;
            if (!run.rule.restores()) {
                continue;
            }
            for (Step step : run.steps) {
                if (step.status == Status.DONE) {
                    restore(run, step);
                }
            }
        }
    }

    /** 写回原来的值：原来的值读不到、本来就是它的不动。 */
    private void restore(Run run, Step step) {
        if (!run.rule.restores() || step.before == null) {
            return;
        }
        int ours = step.change.code();
        int previous = step.before;
        if (previous == ours) {
            return;
        }
        Setting s = step.change.setting;
        markOwn(s, previous);
        world.restore("#" + run.number + " " + step.change.describe(), s, ours, previous);
    }

    // ================================================================= 真的

    private static final class SystemWorld implements World {
        private final Context app;
        private final AppConfig config;
        private final SharedPreferences state;
        private final Handler main = new Handler(Looper.getMainLooper());
        /** 拿住：SharedPreferences 只弱引用监听器。 */
        @SuppressWarnings({"FieldCanBeLocal", "unused"})
        private final SharedPreferences.OnSharedPreferenceChangeListener rulesListener;
        private String parsedJson;
        private List<Rule> parsed = Collections.emptyList();

        SystemWorld(Context app) {
            this.app = app;
            this.config = new AppConfig(app);
            this.state = app.getSharedPreferences(STATE, Context.MODE_PRIVATE);
            this.rulesListener = config.onAutomationRulesChanged(Automations::rulesChanged);
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
        public List<Rule> rules() {
            String json = config.getAutomationRules();
            if (!json.equals(parsedJson)) {
                parsedJson = json;
                parsed = RuleStore.parse(json);
            }
            return parsed;
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
        public Integer battery() {
            Float b = readings().number(Signal.BATTERY);
            return b == null ? null : Math.round(b);
        }

        private Readings readings() {
            return Telemetry.get().readings();
        }

        @Override
        public int minuteOfDay() {
            Calendar c = Calendar.getInstance();
            return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        }

        @Override
        public int weekday() {
            // Calendar：星期日是 1、星期六是 7
            return (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7;
        }

        @Override
        public boolean userExited() {
            return UserExit.isExited(app);
        }

        @Override
        public void read(Setting setting, Consumer<Integer> seen) {
            CarMoves.automationRead(app, setting.target, seen);
        }

        @Override
        public void available(Setting setting, Consumer<Integer> seen) {
            CarMoves.automationAvailable(app, setting.target, seen);
        }

        @Override
        public void write(String key, Setting setting, int value, BiPredicate<Integer, String> stillWanted,
                          Consumer<CarMoves.Outcome> done) {
            CarMoves.automationWrite(app, key, setting.target, value, stillWanted, done);
        }

        @Override
        public void restore(String key, Setting setting, int ours, int previous) {
            CarMoves.automationRestore(app, key, setting.target, ours, previous, result -> {
            });
        }

        @Override
        public void note(String line) {
            BlackBox.note(line);
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

        @Override
        public Integer confirmed(Setting setting) {
            String key = KEY_CONFIRMED + setting.key;
            return state.contains(key) ? state.getInt(key, 0) : null;
        }

        @Override
        public void setConfirmed(Setting setting, Integer value) {
            String key = KEY_CONFIRMED + setting.key;
            if (value == null) {
                state.edit().remove(key).apply();
            } else {
                state.edit().putInt(key, value).apply();
            }
        }

        @Override
        public void wantBattery(boolean want) {
            if (want) {
                Telemetry.get().acquire(app, "automation");
            } else {
                Telemetry.get().release("automation");
            }
        }
    }
}
