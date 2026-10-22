package com.kooo.evcam.sound;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.FastReadings;
import com.kooo.evcam.telemetry.PushedValue;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.SignalAges;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 模拟声浪（开发者选项「模拟声浪（试验）」，项目所有者 2026-10-21）：进程里一个，跟着车辆信号开、关声音那一路。
 * 方案在 docs/local/drive-sound-plan.md；什么时候出声由 {@link DriveSoundRules} 一处定。
 *
 * <h3>两层</h3>
 *
 * <ul>
 *   <li><b>主线程</b>（{@link #update}）：设置或车辆信号（档位、驾驶模式、点火、车辆接口）一变就问一遍裁判 ——
 *       要不要快读（{@link Telemetry#acquireFast}：车速、油门、刹车深度 50 ms 一遍）、虚拟发动机转不转（起、停声音线程）。
 *       只登记 / 注销、起 / 停线程，不碰声音。</li>
 *   <li><b>声音线程</b>（{@link Run}，THREAD_PRIORITY_URGENT_AUDIO）：每 {@link #BLOCK} 帧（48 kHz 下 5.3 ms）读一次快读和读数
 *       （都是不可变快照，不加锁）→ {@link DriveInputs} 算出油门变化率、加减速度 → {@link VirtualEngine} 推进一步 →
 *       按声音风格（{@link SoundStyle}）的合成器出立体声 → {@link SoundOutput} 写进车内媒体喇叭。循环里不分配内存。
 *       换了声音风格或听感：这一台淡出，按新的重新开始（停着放启动声，在走就接入）。
 *       开始工作那一刻车停着放启动声、车在走开阀门式接入（{@link VirtualEngine#ignite}）；要停的时候 0.6 秒淡出再放掉喇叭。</li>
 * </ul>
 *
 * <p>前台服务的寿命规则不变：声浪跟着进程走（录制或开机自启动开着时进程在），进程不在就不出声。
 * 出任何错都只停声音、记黑匣子，不碰录像。</p>
 */
public final class DriveSound {

    private static final String TAG = "DriveSound";
    /** 快读登记用的名字。 */
    static final String USER = "drive-sound";
    /** 快读的间隔（毫秒）：项目所有者 2026-10-22 要这几个编码轮询再快一些（车机的读法最快 20 ms，见 FastReads）。 */
    static final long FAST_MS = 20L;
    private static final Set<Signal> FAST =
            Collections.unmodifiableSet(EnumSet.of(Signal.SPEED, Signal.THROTTLE_DEPTH, Signal.BRAKE_DEPTH));
    /** 一块多少帧：48 kHz 下 5.3 ms。 */
    static final int BLOCK = 256;
    /** 声音线程多久重读一次设置、看一次通话和车辆接口（毫秒）。 */
    static final long CHECK_MS = 500L;
    /** 多久看一次输出（欠载、延迟）。 */
    static final long WATCH_MS = 1000L;
    /** 声音线程自己出错停了，多久之内不再起（毫秒）：车上没有那个喇叭时不反复开。 */
    static final long RETRY_AFTER_FAILURE_MS = 30_000L;

    private static final DriveSound INSTANCE = new DriveSound();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Telemetry.Listener listener = readings -> update();
    private Context app;
    private AppConfig config;
    private boolean installed;
    /** 快读登记着（主线程）。 */
    private boolean armed;
    /** 此刻的声音线程（主线程管）；没转着是 null。 */
    private Run run;
    /** 上一个声音线程自己出错停下的时刻（elapsedRealtime）；0 = 没有。 */
    private long failedAtMs;

    // ---- 设置（主线程、声音线程都会刷新，读的人拿到的总是完整的一项）
    private volatile boolean enabled;
    private volatile float userDb = 0f;
    private volatile VirtualEngine.RpmMode rpmMode = VirtualEngine.RpmMode.CONTINUOUS;
    private volatile SoundStyle style = SoundStyle.AMERICAN;
    /** 听感：车内（隔着防火墙、车身）；关着是车外。 */
    private volatile boolean cabinView;
    /** 最近一个声音线程（诊断报告用）。 */
    private volatile Run latest;

    private DriveSound() {
    }

    /** 进程起来时装上（{@code ZeekrShortcutApp}，在熄屏判断登记了车辆信号核心之后）；重复叫无害。主线程上叫。 */
    public static void install(Context context) {
        INSTANCE.doInstall(context);
    }

    /** 设置变了（开关、声音风格、音量、转速变化方式，悬浮按钮也会改开关）：马上按新的设置再定一次。哪个线程都能叫。 */
    public static void refresh(Context context) {
        INSTANCE.main.post(() -> {
            INSTANCE.doInstall(context);
            INSTANCE.update();
        });
    }

    private void doInstall(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        app = context.getApplicationContext();
        config = new AppConfig(app);
        Telemetry.get().addListener(listener);
        update();
    }

    /** 主线程：问一遍裁判，登记 / 注销快读，起 / 停声音线程。 */
    private void update() {
        if (!installed) {
            return;
        }
        readSettings();
        Telemetry telemetry = Telemetry.get();
        Readings r = telemetry.readings();
        // 这里只看「快读不快读、转不转」，车速、油门给个新鲜的占位（是否过期由声音线程每块判）
        DriveSoundRules.Why why = DriveSoundRules.decide(enabled, telemetry.carLinkConnected(),
                r.code(Signal.IGNITION), r.text(Signal.GEAR), r.code(Signal.DRIVE_MODE), 0f, 0f, 0L, false);
        if (why.armed != armed) {
            armed = why.armed;
            if (armed) {
                telemetry.acquireFast(USER, FAST, FAST_MS);
            } else {
                telemetry.releaseFast(USER);
            }
        }
        if (run != null && (run.style != style || run.cabin != cabinView)) {
            // 换了声音风格或听感：这一台淡出，下面按新的重新开始
            run.stop();
            run = null;
        }
        if (why.open && run == null) {
            long now = SystemClock.elapsedRealtime();
            if (failedAtMs != 0L && now - failedAtMs < RETRY_AFTER_FAILURE_MS) {
                return;
            }
            run = new Run();
            run.start();
        } else if (!why.open && run != null) {
            run.stop();
            run = null;
        }
    }

    /** 声音线程自己停了（出错）：主线程上把它摘掉，过一会儿才再起。 */
    private void ended(Run which, boolean failed) {
        main.post(() -> {
            if (run == which) {
                run = null;
                if (failed) {
                    failedAtMs = SystemClock.elapsedRealtime();
                }
            }
        });
    }

    private void readSettings() {
        AppConfig c = config;
        if (c == null) {
            return;
        }
        enabled = c.isDriveSoundEnabled();
        userDb = DriveSoundRules.volumeDb(c.getDriveSoundVolume());
        rpmMode = AppConfig.DRIVE_SOUND_RPM_SHIFTS.equals(c.getDriveSoundRpm())
                ? VirtualEngine.RpmMode.SHIFTS : VirtualEngine.RpmMode.CONTINUOUS;
        style = SoundStyle.of(c.getDriveSoundStyle());
        cabinView = AppConfig.DRIVE_SOUND_VIEW_CABIN.equals(c.getDriveSoundView());
    }

    private static float num(Float value) {
        return value == null ? Float.NaN : value;
    }

    private static boolean inCall(AudioManager audio) {
        if (audio == null) {
            return false;
        }
        int mode = audio.getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
    }

    /** 诊断报告那一节的正文（英文、开发者看）；没装上也有一行。 */
    public static String diagnostics() {
        DriveSound s = INSTANCE;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "  enabled=%s style=%s view=%s volume=%.0f dB rpm=%s armed=%s fastUsers=%s%n",
                s.enabled, s.style.key, s.cabinView ? "cabin" : "outside", s.userDb, s.rpmMode, s.armed,
                Telemetry.get().fastUsers()));
        FastReadings fast = Telemetry.get().fast();
        long now = SystemClock.elapsedRealtime();
        sb.append(String.format(Locale.US, "  fast: age=%d ms speed=%s throttle=%s brake=%s%n", fast.ageMs(now),
                fast.number(Signal.SPEED), fast.number(Signal.THROTTLE_DEPTH), fast.number(Signal.BRAKE_DEPTH)));
        String pushStatus = Telemetry.get().pushStatus();
        sb.append("  push: ").append(pushStatus.isEmpty() ? "not tried" : pushStatus).append('\n');
        sb.append("  pushed: ").append(pushed(Signal.SPEED, "speed", now)).append(", ")
                .append(pushed(Signal.THROTTLE_DEPTH, "throttle", now)).append(", ")
                .append(pushed(Signal.BRAKE_DEPTH, "brake", now)).append('\n');
        Run r = s.latest;
        sb.append("  ").append(r == null ? "no run since the process started" : r.describe()).append('\n');
        return sb.toString();
    }

    /** 诊断：推来的一个信号 —— 最近的值、多久以前、推了几次、平均隔多久。 */
    private static String pushed(Signal signal, String name, long now) {
        PushedValue p = Telemetry.get().pushed(signal);
        if (p.count == 0L) {
            return name + " -";
        }
        return String.format(Locale.US, "%s %s (%d ms ago, %d pushes, every %s)", name, p.value, now - p.atMs, p.count,
                p.everyMs < 0f ? "?" : String.format(Locale.US, "%.0f ms", p.everyMs));
    }

    /** 一个声音线程：开车内喇叭、合成、写；要停时淡出再放掉。 */
    private final class Run implements Runnable {
        /** 这一台的声音风格、听感（起的时候定下，换了就换一台）。 */
        final SoundStyle style = DriveSound.this.style;
        final boolean cabin = DriveSound.this.cabinView;
        private volatile boolean stopping;
        // ---- 诊断：声音线程写，随时读
        private volatile String state = "opening";
        private volatile DriveSoundRules.Why why = DriveSoundRules.Why.STALE;
        private volatile String ignitedAs = "";
        private volatile String output = "";
        private volatile String trouble = "";
        private volatile float rpm;
        private volatile float load;
        private volatile float levelDb;
        private volatile int gear;
        private volatile boolean pseudo;
        private volatile float gate;
        private volatile float pedalRate;
        private volatile float accel;
        private volatile long blocks;
        private volatile float renderMs;
        // ---- 诊断：反应快慢（项目所有者 2026-10-21 实车嫌切运动模式、踩油门到出声都慢）
        /** 起这一台的时刻（elapsedRealtime）；那时驾驶模式变了多久（-1 = 没见它变过）。 */
        private final long askedAt = SystemClock.elapsedRealtime();
        private final long modeAgeWhenAsked = SignalAges.ageMs(Signal.DRIVE_MODE, askedAt);
        private volatile long openMs = -1L;
        private volatile long askedToSoundMs = -1L;
        private volatile long modeToSoundMs = -1L;
        /** 快读：新读数平均隔多久来一次、最近十秒最长隔了多久；油门读数平均隔多久变一次（踩、松的时候）、变了几次。 */
        private volatile float fastEveryMs = -1f;
        private volatile long fastGapMaxMs = -1L;
        private volatile float throttleEveryMs = -1f;
        private volatile int throttleSteps;
        /**
         * 油门推送（试验）：推来的平均隔多久、推了几次；新的油门值先由谁送到（推送 / 快读），推送领先快读多少；
         * 新的油门值读到时已经过了多久（合成用上它的那一刻）。和 output 那一行的延迟加起来，是「车机给出油门 → 喇叭」能量到的部分。
         */
        private volatile float pushEveryMs = -1f;
        private volatile long pushes;
        private volatile int pushFirst;
        private volatile int pollFirst;
        private volatile float pushLeadMs = -1f;
        private volatile float pollLeadMs = -1f;
        private volatile float inputAgeMs = -1f;
        private volatile float outputLatencyMs = -1f;

        void start() {
            latest = this;
            Thread thread = new Thread(this, "DriveSound");
            thread.start();
        }

        /** 不等：线程自己淡出、放掉喇叭再退出。 */
        void stop() {
            stopping = true;
        }

        @Override
        public void run() {
            raisePriority();
            SoundOutput out;
            long openStart = SystemClock.elapsedRealtime();
            try {
                out = SoundOutput.open(app, CarSpeakers.CABIN_MEDIA);
                openMs = SystemClock.elapsedRealtime() - openStart;
            } catch (RuntimeException e) {
                trouble = String.valueOf(e.getMessage());
                state = "failed";
                AppLog.w(TAG, "cabin output: " + trouble);
                BlackBox.note("模拟声浪：开不了车内喇叭（" + trouble + "）");
                ended(this, true);
                return;
            }
            output = out.describe();
            BlackBox.note("模拟声浪：开始（" + output + "）");
            boolean failed = false;
            try {
                failed = loop(out);
            } catch (RuntimeException e) {
                failed = true;
                trouble = e.toString();
                AppLog.w(TAG, "drive sound stopped: " + e);
            } finally {
                out.close();
                state = failed ? "failed" : "stopped";
                output = out.describe();
                BlackBox.note("模拟声浪：停止（" + (failed ? "出错 " + trouble + "；" : "") + output + "）");
                ended(this, failed);
            }
        }

        /** 音频线程的优先级：先要最高的那一档，系统不给就退一档，再不给就照默认的跑（不能让线程因此死掉）。 */
        private void raisePriority() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            } catch (RuntimeException urgentRefused) {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                } catch (RuntimeException audioRefused) {
                    AppLog.w(TAG, "audio thread priority refused: " + audioRefused);
                }
            }
        }

        /** @return 出错停的（写不进喇叭） */
        private boolean loop(SoundOutput out) {
            Telemetry telemetry = Telemetry.get();
            AudioManager audio = app.getSystemService(AudioManager.class);
            DriveInputs inputs = new DriveInputs();
            VirtualEngine engine = new VirtualEngine(SoundProfile.V8, style.cranks());
            Voice voice = style.voice(SoundOutput.RATE, cabin);
            VirtualEngine.Input in = new VirtualEngine.Input();
            VoiceParams params = new VoiceParams();
            float[] buffer = new float[2 * BLOCK];
            float dt = BLOCK / (float) SoundOutput.RATE;
            boolean ignited = false;
            boolean inCall = false;
            boolean link = false;
            float g = 0f;
            long nextCheck = 0L;
            long nextWatch = 0L;
            long lastFastVersion = Long.MIN_VALUE;
            long lastFastAt = -1L;
            long gapMax = 0L;
            long gapWindowEnd = 0L;
            float lastThrottle = Float.NaN;
            long lastThrottleAt = -1L;
            long seenFastVersion = Long.MIN_VALUE;
            long seenPushCount = -1L;
            long inputVersion = 0L;
            // 油门：推送、快读赛跑，谁先带来新值用谁（ThrottleSources）；再还原成连续的踏板（车机约 200 ms 才更新一次，PedalTrack）
            ThrottleSources sources = new ThrottleSources();
            PedalTrack pedal = new PedalTrack();
            long lastPushCount = 0L;
            float usedThrottle = Float.NaN;
            state = "running";
            while (true) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextCheck) {
                    nextCheck = now + CHECK_MS;
                    readSettings();
                    inCall = inCall(audio);
                    link = telemetry.carLinkConnected();
                }
                FastReadings fast = telemetry.fast();
                PushedValue push = telemetry.pushed(Signal.THROTTLE_DEPTH);
                PushedValue pushSpeed = telemetry.pushed(Signal.SPEED);
                PushedValue pushBrake = telemetry.pushed(Signal.BRAKE_DEPTH);
                float polled = num(fast.number(Signal.THROTTLE_DEPTH));
                float polledSpeed = num(fast.number(Signal.SPEED));
                float polledBrake = num(fast.number(Signal.BRAKE_DEPTH));
                // 油门：推送、快读谁先带来新值用谁（快读读到的旧值不收）
                boolean freshThrottle = sources.offer(push.count, push.value, push.atMs, fast.version, polled, fast.atMs);
                float throttle = sources.sample;
                long throttleAt = sources.sampleAt;
                // 车速、刹车：推来过就用推来的（快读的值比推送晚），推送停了、快读又不一样才用快读
                float speed = DriveSoundRules.usePush(pushSpeed.count, pushSpeed.atMs, pushSpeed.value, polledSpeed, now)
                        ? pushSpeed.value : polledSpeed;
                float brake = DriveSoundRules.usePush(pushBrake.count, pushBrake.atMs, pushBrake.value, polledBrake, now)
                        ? pushBrake.value : polledBrake;
                long pushCount = push.count + pushSpeed.count + pushBrake.count;
                if (fast.version != seenFastVersion || pushCount != seenPushCount) {
                    seenFastVersion = fast.version;
                    seenPushCount = pushCount;
                    inputVersion++;
                }
                long inputAt = Math.max(Math.max(fast.atMs, push.atMs), Math.max(pushSpeed.atMs, pushBrake.atMs));
                inputs.offer(inputVersion, inputAt, speed, throttle, brake);
                // 新的油门值交给踏板
                if (freshThrottle) {
                    pedal.sample(sources.sample, sources.sampleAt, now);
                }
                pedal.step(dt, now);
                pushFirst = sources.pushFirst;
                pollFirst = sources.pollFirst;
                pushLeadMs = sources.pushLeadMs;
                pollLeadMs = sources.pollLeadMs;
                if (!Float.isNaN(throttle) && (Float.isNaN(usedThrottle) || Math.abs(throttle - usedThrottle) >= 1f)) {
                    // 诊断：新的油门值用上时已经过了多久（读到 / 推来 → 这一块合成）
                    if (!Float.isNaN(usedThrottle) && throttleAt > 0L) {
                        long age = now - throttleAt;
                        inputAgeMs = inputAgeMs < 0f ? age : inputAgeMs + (age - inputAgeMs) * 0.1f;
                    }
                    usedThrottle = throttle;
                }
                if (push.count != lastPushCount && push.count > 0L) {
                    // 诊断：油门推送多久来一次（Telemetry 算的平均）
                    pushEveryMs = push.everyMs;
                    pushes = push.count;
                    lastPushCount = push.count;
                }
                if (fast.version != lastFastVersion && fast.atMs > 0L) {
                    // 诊断：快读多久来一次、油门读数多久变一次（看车机那边刷新得快不快）
                    lastFastVersion = fast.version;
                    if (lastFastAt > 0L) {
                        long gap = fast.atMs - lastFastAt;
                        fastEveryMs = fastEveryMs < 0f ? gap : fastEveryMs + (gap - fastEveryMs) * 0.05f;
                        gapMax = Math.max(gapMax, gap);
                    }
                    lastFastAt = fast.atMs;
                    if (now >= gapWindowEnd) {
                        gapWindowEnd = now + 10_000L;
                        fastGapMaxMs = gapMax;
                        gapMax = 0L;
                    }
                    float t = polled;
                    if (!Float.isNaN(t) && t != lastThrottle) {
                        if (!Float.isNaN(lastThrottle) && lastThrottleAt > 0L && fast.atMs - lastThrottleAt < 500L) {
                            long step = fast.atMs - lastThrottleAt;
                            throttleSteps++;
                            throttleEveryMs = throttleEveryMs < 0f ? step : throttleEveryMs + (step - throttleEveryMs) * 0.1f;
                        }
                        lastThrottle = t;
                        lastThrottleAt = fast.atMs;
                    }
                }
                Readings r = telemetry.readings();
                String gearText = r.text(Signal.GEAR);
                DriveSoundRules.Why w = stopping ? DriveSoundRules.Why.OFF
                        : DriveSoundRules.decide(enabled, link, r.code(Signal.IGNITION), gearText,
                        r.code(Signal.DRIVE_MODE), inputs.speed(), inputs.throttle(), fast.ageMs(now), inCall);
                why = w;
                in.speedKmh = inputs.speed();
                // 虚拟发动机看还原后的踏板和按相邻读数算的快慢；踏板读不到时照快读的
                boolean tracked = !Float.isNaN(pedal.value());
                in.throttlePct = tracked ? pedal.value() : inputs.throttle();
                in.pedalRate = tracked ? pedal.rate(now) : inputs.pedalRate();
                in.accel = inputs.accel();
                in.braking = inputs.brake() > VirtualEngine.BRAKE_ON || Boolean.TRUE.equals(r.bool(Signal.BRAKE_PEDAL));
                in.gear = DriveSoundRules.gearOf(gearText);
                in.flow = DriveSoundRules.flowOf(r.code(Signal.POWER_FLOW));
                in.mode = rpmMode;
                if (!ignited && w == DriveSoundRules.Why.RUNNING) {
                    engine.ignite(in);
                    ignited = true;
                    g = 1f;
                    askedToSoundMs = now - askedAt;
                    long modeAge = SignalAges.ageMs(Signal.DRIVE_MODE, now);
                    modeToSoundMs = modeAge >= 0L && modeAge < 10_000L ? modeAge : -1L;
                    boolean starting = engine.phase() == VirtualEngine.Phase.STARTING;
                    ignitedAs = starting ? "start" : String.format(Locale.US, "join at %.0f km/h", in.speedKmh);
                    BlackBox.note("模拟声浪：" + (starting ? "启动（车停着）" : "接入（行驶中 " + Math.round(in.speedKmh) + " km/h）"));
                }
                if (ignited) {
                    engine.step(dt, in);
                }
                float target = w.audible ? 1f : 0f;
                float stepSize = dt / Math.max(w.fadeSec, dt);
                g = target > g ? Math.min(target, g + stepSize) : Math.max(target, g - stepSize);
                gate = g;
                if (stopping && g <= 0f) {
                    return false;
                }
                if (ignited) {
                    params.rpm = engine.rpm();
                    params.load = engine.voiceLoad();
                    params.level = EngineVoice.dbToLin(engine.levelDb());
                    params.gain = g * engine.fade() * EngineVoice.dbToLin(userDb + engine.outputDb());
                    params.brightDb = engine.brightDb();
                    params.intakeBoostDb = engine.intakeBoostDb();
                    params.burble = engine.burble();
                    params.starter = engine.starter();
                    params.whineHz = engine.whineHz();
                    params.whineAmp = engine.whineAmp();
                    params.toneHz = engine.toneHz();
                    params.overrun = engine.overrun();
                    params.speedKmh = engine.speedKmh();
                    params.drive = in.gear == VirtualEngine.Gear.D;
                    params.gearIndex = engine.geared() ? engine.gearIndex() : -1;
                    params.cut = engine.cut();
                    params.shiftSerial = engine.shiftSerial();
                    params.shiftUp = engine.shiftUp();
                    rpm = engine.rpm();
                    load = engine.load();
                    levelDb = engine.levelDb();
                    gear = engine.geared() ? engine.gearIndex() + 1 : 0;
                    pseudo = engine.pseudoGears();
                } else {
                    params.gain = 0f;
                }
                pedalRate = in.pedalRate;
                accel = inputs.accel();
                long t0 = System.nanoTime();
                voice.renderStereo(buffer, BLOCK, params);
                renderMs += ((System.nanoTime() - t0) / 1_000_000f - renderMs) * 0.01f;
                if (!out.write(buffer, BLOCK)) {
                    trouble = "write failed";
                    return true;
                }
                blocks++;
                if (now >= nextWatch) {
                    nextWatch = now + WATCH_MS;
                    boolean grown = out.watch();
                    // 每秒刷新一次（输出延迟要实时的；以前只在加缓冲时刷新，诊断里一直是开的那一刻的「?」）
                    output = out.describe();
                    outputLatencyMs = out.latencyMs();
                    if (grown) {
                        BlackBox.note("模拟声浪：欠载，缓冲加大（" + output + "）");
                    }
                }
            }
        }

        String describe() {
            return String.format(Locale.US,
                    "run: %s style=%s view=%s why=%s ignited=%s rpm=%.0f gear=%d%s load=%.2f level=%.1f dB gate=%.2f "
                            + "pedal=%.0f %%/s accel=%.2f m/s2 blocks=%d render=%.3f ms per %.1f ms block%s%n  output: %s%n"
                            + "  start: drive mode changed %s before the run was asked for, output opened in %s, "
                            + "first sound %s after the ask (%s after the mode change)%n"
                            + "  inputs: fast reads every %s (longest gap in 10 s %s), throttle changed every %s (%d changes)%n"
                            + "  throttle push: every %s (%d pushes), a new value came first by push %d times (ahead by %s), "
                            + "by the fast read %d times (ahead by %s)%n"
                            + "  pedal to speaker (the part measurable here): new throttle used %s after it arrived "
                            + "+ output %s (HAL timestamp; on the car's head unit it reads about 5.1 s whatever the buffer, so it is "
                            + "not real; + the head unit's own signal and amplifier delays, not measurable here)",
                    state, style.key, cabin ? "cabin" : "outside", why, ignitedAs.isEmpty() ? "-" : ignitedAs, rpm, gear,
                    pseudo ? " (pseudo)" : "", load, levelDb, gate, pedalRate, accel,
                    blocks, renderMs, BLOCK * 1000f / SoundOutput.RATE,
                    trouble.isEmpty() ? "" : " trouble=" + trouble, output.isEmpty() ? "-" : output,
                    ms(modeAgeWhenAsked), ms(openMs), ms(askedToSoundMs), ms(modeToSoundMs),
                    ms(fastEveryMs), ms(fastGapMaxMs), ms(throttleEveryMs), throttleSteps,
                    ms(pushEveryMs), pushes, pushFirst, ms(pushLeadMs), pollFirst, ms(pollLeadMs),
                    ms(inputAgeMs), ms(outputLatencyMs));
        }

        private String ms(float value) {
            return value < 0f ? "?" : String.format(Locale.US, "%.0f ms", value);
        }
    }
}
