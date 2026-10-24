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
 * 模拟声浪（设置 → 系统「模拟声浪（试验性）」，项目所有者 2026-10-21）：进程里一个，跟着车辆信号开、关声音那一路。
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
 *       开始工作那一刻车停着放启动声、车在走像混动车那样起动发动机（{@link VirtualEngine#ignite}）；切到不出声的驾驶模式、下电时发动机熄火
 *       （{@link VirtualEngine#shutdown}），放完再放掉喇叭，熄火途中又切回来就很快淡掉、让新的那台点火；别的原因要停 0.6 秒淡出。</li>
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
    /** 熄火还没放完又要起新的一台（刚切出去又切回来）：旧的这么快淡掉（秒）。 */
    static final float HURRY_FADE_SEC = 0.15f;

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
    /** 刚叫停、可能还在放熄火的那一台（主线程管）：起新的一台前叫它快点淡掉。 */
    private Run leaving;
    /** 上一个声音线程自己出错停下的时刻（elapsedRealtime）；0 = 没有。 */
    private long failedAtMs;

    // ---- 设置（主线程、声音线程都会刷新，读的人拿到的总是完整的一项）
    private volatile boolean enabled;
    /** 「标准模式也启用」（项目所有者 2026-10-24，默认关）：开着时标准模式也出声。 */
    private volatile boolean standardMode;
    private volatile float userDb = 0f;
    private volatile VirtualEngine.RpmMode rpmMode = VirtualEngine.RpmMode.CONTINUOUS;
    /** 模拟换挡的变速箱（「模拟声浪」页的「变速箱」）：声音线程每块交给虚拟发动机，改了马上换，不重新开始。 */
    private volatile Gearbox gearbox = Gearbox.AUTO8;
    private volatile SoundStyle style = SoundStyle.AMERICAN;
    /** 听感：车内（隔着防火墙、车身）；关着是车外。 */
    private volatile boolean cabinView;
    /**
     * 播放喇叭（「模拟声浪」页，项目所有者 2026-10-24 要加的外放测试）：{@code AppConfig.DRIVE_SOUND_OUTPUT_*}，车内 / 车外 / 车内和车外。
     * 换了就换一台（像声音风格、听感）。
     */
    private volatile String outputMode = AppConfig.DRIVE_SOUND_OUTPUT_CABIN;
    /** 车外线路（「模拟声浪」页的「车外线路」：bus12 / bus11），换了就换一台；车外音量（dB，「车外音量」），随时生效。 */
    private volatile String exteriorRoute = AppConfig.DRIVE_SOUND_ROUTE_NOTIFY;
    private volatile float exteriorDb = 12f;
    /** 最近一个声音线程（诊断报告用）。 */
    private volatile Run latest;

    private DriveSound() {
    }

    /** 进程起来时装上（{@code ZeekrShortcutApp}，在熄屏判断登记了车辆信号核心之后）；重复叫无害。主线程上叫。 */
    public static void install(Context context) {
        INSTANCE.doInstall(context);
    }

    /** 这种声音风格（设置里存的值）用得上「模拟声浪」页的「听感」吗：只有美式 V8 分车外、车内（设置页按它显示这一行）。 */
    public static boolean styleHasViews(String style) {
        return SoundStyle.of(style).hasViews();
    }

    /** 这种声音风格用得上「模拟声浪」页的「变速箱」吗：两种超音速固定 8 速（设置页按它显示这一行）。 */
    public static boolean styleChoosesGearbox(String style) {
        return SoundStyle.of(style).choosesGearbox();
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
                r.code(Signal.IGNITION), r.text(Signal.GEAR), r.code(Signal.DRIVE_MODE), standardMode, 0f, 0f, 0L, false);
        if (why.armed != armed) {
            armed = why.armed;
            if (armed) {
                telemetry.acquireFast(USER, FAST, FAST_MS);
            } else {
                telemetry.releaseFast(USER);
            }
        }
        if (run != null && (run.style != style || run.cabin != cabinView || !run.outputMode.equals(outputMode)
                || (!run.exteriorRoute.equals(exteriorRoute) && !AppConfig.DRIVE_SOUND_OUTPUT_CABIN.equals(outputMode)))) {
            // 换了声音风格、听感、播放喇叭或者（用着车外时）车外线路：这一台淡出，下面按新的重新开始
            run.stop();
            run = null;
        }
        if (why.open && run == null) {
            long now = SystemClock.elapsedRealtime();
            if (failedAtMs != 0L && now - failedAtMs < RETRY_AFTER_FAILURE_MS) {
                return;
            }
            if (leaving != null) {
                // 刚切出去又切回来：上一台的熄火不放完了，很快淡掉
                leaving.hurry();
                leaving = null;
            }
            run = new Run();
            run.start();
        } else if (!why.open && run != null) {
            run.stop();
            leaving = run;
            run = null;
        }
    }

    /** 声音线程自己停了（出错）：主线程上把它摘掉，过一会儿才再起。 */
    private void ended(Run which, boolean failed) {
        main.post(() -> {
            if (leaving == which) {
                leaving = null;
            }
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
        standardMode = c.isDriveSoundStandardEnabled();
        userDb = DriveSoundRules.volumeDb(c.getDriveSoundVolume());
        rpmMode = AppConfig.DRIVE_SOUND_RPM_SHIFTS.equals(c.getDriveSoundRpm())
                ? VirtualEngine.RpmMode.SHIFTS : VirtualEngine.RpmMode.CONTINUOUS;
        gearbox = AppConfig.DRIVE_SOUND_GEARBOX_MANUAL6.equals(c.getDriveSoundGearbox()) ? Gearbox.MANUAL6 : Gearbox.AUTO8;
        style = SoundStyle.of(c.getDriveSoundStyle());
        cabinView = AppConfig.DRIVE_SOUND_VIEW_CABIN.equals(c.getDriveSoundView());
        String output = c.getDriveSoundOutput();
        outputMode = AppConfig.DRIVE_SOUND_OUTPUT_OUTSIDE.equals(output) || AppConfig.DRIVE_SOUND_OUTPUT_BOTH.equals(output)
                ? output : AppConfig.DRIVE_SOUND_OUTPUT_CABIN;
        exteriorRoute = AppConfig.DRIVE_SOUND_ROUTE_PLAYBACK.equals(c.getDriveSoundExteriorRoute())
                ? AppConfig.DRIVE_SOUND_ROUTE_PLAYBACK : AppConfig.DRIVE_SOUND_ROUTE_NOTIFY;
        exteriorDb = DriveSoundRules.exteriorDb(c.getDriveSoundExteriorVolume());
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
        sb.append(String.format(Locale.US,
                "  enabled=%s standard=%s style=%s view=%s speakers=%s exterior=%s %+.0f dB volume=%.0f dB rpm=%s gearbox=%s "
                        + "armed=%s fastUsers=%s%n",
                s.enabled, s.standardMode, s.style.key, s.cabinView ? "cabin" : "outside", s.outputMode, s.exteriorRoute, s.exteriorDb, s.userDb,
                s.rpmMode, s.gearbox, s.armed, Telemetry.get().fastUsers()));
        FastReadings fast = Telemetry.get().fast();
        long now = SystemClock.elapsedRealtime();
        sb.append(String.format(Locale.US, "  fast: age=%d ms speed=%s throttle=%s brake=%s%n", fast.ageMs(now),
                fast.number(Signal.SPEED), fast.number(Signal.THROTTLE_DEPTH), fast.number(Signal.BRAKE_DEPTH)));
        String pushStatus = Telemetry.get().pushStatus();
        sb.append("  push: ").append(pushStatus.isEmpty() ? "not tried" : pushStatus).append('\n');
        String formats = OutputProbe.result();
        sb.append("  output formats: ").append(formats.isEmpty() ? "not tried" : formats).append('\n');
        sb.append("  pushed: ").append(pushed(Signal.SPEED, "speed", now)).append(", ")
                .append(pushed(Signal.THROTTLE_DEPTH, "throttle", now)).append(", ")
                .append(pushed(Signal.BRAKE_DEPTH, "brake", now)).append('\n');
        sb.append("  throttle listeners: ").append(pushed(Telemetry.get().throttleListener(false), "fastest", now)).append(", ")
                .append(pushed(Telemetry.get().throttleListener(true), "RATE_DR", now)).append('\n');
        Run r = s.latest;
        sb.append("  ").append(r == null ? "no run since the process started" : r.describe()).append('\n');
        return sb.toString();
    }

    /** 诊断：推来的一个信号 —— 最近的值、多久以前、推了几次、平均隔多久。 */
    private static String pushed(Signal signal, String name, long now) {
        return pushed(Telemetry.get().pushed(signal), name, now);
    }

    private static String pushed(PushedValue p, String name, long now) {
        if (p.count == 0L) {
            return name + " -";
        }
        return String.format(Locale.US, "%s %s (%d ms ago, %d pushes, every %s)", name, p.value, now - p.atMs, p.count,
                p.everyMs < 0f ? "?" : String.format(Locale.US, "%.0f ms", p.everyMs));
    }

    /** 一个声音线程：开喇叭（车内、车外，按「播放喇叭」）、合成、写；要停时淡出再放掉。 */
    private final class Run implements Runnable {
        /** 这一台的声音风格、听感、播放喇叭（起的时候定下，换了就换一台）。 */
        final SoundStyle style = DriveSound.this.style;
        final boolean cabin = DriveSound.this.cabinView;
        final String outputMode = DriveSound.this.outputMode;
        final String exteriorRoute = DriveSound.this.exteriorRoute;
        // ---- 系统报给应用的媒体音量（音量键调的是它那一组；车外不在那一组）：第一次读到的、此刻的、最多几档、是不是写死的；
        //      跟着变过一次（看得出它真跟着音量键走）以后，车外按它缩放（DriveSoundRules.mediaFollowDb）
        private volatile int mediaFirst = Integer.MIN_VALUE;
        private volatile int mediaIndex = -1;
        private volatile int mediaMax = -1;
        private volatile boolean mediaFixed;
        private volatile boolean mediaTracked;
        private volatile boolean stopping;
        /** 熄火不放完了，很快淡掉（新的一台要起）。 */
        private volatile boolean hurry;
        // ---- 诊断：声音线程写，随时读
        private volatile String state = "opening";
        private volatile DriveSoundRules.Why why = DriveSoundRules.Why.STALE;
        private volatile String ignitedAs = "";
        private volatile String endedAs = "";
        private volatile String output = "";
        private volatile String trouble = "";
        private volatile float rpm;
        private volatile float load;
        private volatile float levelDb;
        private volatile int gear;
        private volatile boolean pseudo;
        /** 换挡积极性（0 标准 … 1 运动）、挂着挡接合到几成（0 起步打滑 … 1 硬连着）。 */
        private volatile float eager;
        private volatile float coupled;
        private volatile float gate;
        private volatile float pedalRate;
        private volatile float accel;
        private volatile long blocks;
        private volatile float renderMs;
        /** 混音多久来取一段（写的时候等了，就是它刚取走一段）、每段合多少块：输出这边我们能看到的节拍。 */
        private volatile float mixerEveryMs = -1f;
        private volatile float burstBlocks = -1f;
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
        /** 车速读数平均多久变一次（{@link SpeedTrack}：车机多久更新一次车速）。 */
        private volatile float speedEveryMs = -1f;
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
        /** 油门深度两个监听各自推得多勤（主监听最快档、另挂的 RATE_DR），停了以后诊断里也留着。 */
        private volatile float mainEveryMs = -1f;
        private volatile long mainPushes;
        private volatile float drEveryMs = -1f;
        private volatile long drPushes;
        private volatile float inputAgeMs = -1f;
        private volatile float outputLatencyMs = -1f;

        void start() {
            latest = this;
            Thread thread = new Thread(this, "DriveSound");
            thread.start();
        }

        /** 不等：线程自己熄火或淡出、放掉喇叭再退出。 */
        void stop() {
            stopping = true;
        }

        /** 不等：熄火不放完了，{@link #HURRY_FADE_SEC} 淡掉。 */
        void hurry() {
            hurry = true;
        }

        @Override
        public void run() {
            raisePriority();
            boolean toCabin = !AppConfig.DRIVE_SOUND_OUTPUT_OUTSIDE.equals(outputMode);
            boolean toOutside = !AppConfig.DRIVE_SOUND_OUTPUT_CABIN.equals(outputMode);
            SoundOutput inside = null;
            SoundOutput outside = null;
            long openStart = SystemClock.elapsedRealtime();
            if (toCabin) {
                try {
                    inside = SoundOutput.open(app, CarSpeakers.CABIN_MEDIA);
                } catch (RuntimeException e) {
                    trouble = "cabin: " + e.getMessage();
                    AppLog.w(TAG, "cabin output: " + e.getMessage());
                    BlackBox.note("模拟声浪：开不了车内喇叭（" + e.getMessage() + "）");
                }
            }
            if (toOutside) {
                // 车外只有一个喇叭，车机给了两路：用「模拟声浪」页的「车外线路」选的那一路，开不了就不放车外（不换另一路）
                String address = CarSpeakers.exteriorAddress(exteriorRoute);
                try {
                    outside = SoundOutput.open(app, address);
                } catch (RuntimeException e) {
                    trouble = (trouble.isEmpty() ? "" : trouble + "; ") + "exterior: " + e.getMessage();
                    AppLog.w(TAG, "exterior output: " + e.getMessage());
                    BlackBox.note("模拟声浪：开不了车外喇叭（" + e.getMessage() + "）");
                }
            }
            if (inside == null && outside == null) {
                state = "failed";
                ended(this, true);
                return;
            }
            openMs = SystemClock.elapsedRealtime() - openStart;
            output = describeOutputs(inside, outside);
            BlackBox.note("模拟声浪：开始（" + output + "）");
            if (inside != null) {
                // 试验：哪种格式能拿到低延迟通道（进程里一次，自己的线程，不出声）
                OutputProbe.runOnce(app, CarSpeakers.CABIN_MEDIA);
            }
            boolean failed = false;
            try {
                failed = loop(inside, outside);
            } catch (RuntimeException e) {
                failed = true;
                trouble = e.toString();
                AppLog.w(TAG, "drive sound stopped: " + e);
            } finally {
                if (inside != null) {
                    inside.close();
                }
                if (outside != null) {
                    outside.close();
                }
                state = failed ? "failed" : "stopped";
                output = describeOutputs(inside, outside);
                BlackBox.note("模拟声浪：停止（" + (failed ? "出错 " + trouble + "；" : "") + output + "）");
                ended(this, failed);
            }
        }

        /** 系统报给应用的媒体音量：读一次；跟第一次读到的不一样了，就算它跟着音量键走（记一行黑匣子），车外从此跟着它。 */
        private void readMedia(AudioManager audio) {
            if (audio == null) {
                return;
            }
            try {
                int index = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                mediaFixed = audio.isVolumeFixed();
                mediaMax = max;
                mediaIndex = index;
                if (mediaFirst == Integer.MIN_VALUE) {
                    mediaFirst = index;
                } else if (!mediaTracked && index != mediaFirst) {
                    mediaTracked = true;
                    BlackBox.note("模拟声浪：系统报的媒体音量变了（" + mediaFirst + " → " + index + " / " + max + "），车外跟着它");
                }
            } catch (RuntimeException e) {
                // 读不到就不跟
            }
        }

        /** 开着的几路，一句话（英文，诊断报告 / 黑匣子用）。 */
        private String describeOutputs(SoundOutput inside, SoundOutput outside) {
            if (outside == null) {
                return inside.describe();
            }
            return (inside == null ? "" : inside.describe() + "; ") + "exterior " + outside.describe();
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

        /**
         * 合成、写：车内那一路（{@code inside}）照「听感」合成；车外那一路（{@code outside}）总是车外的听感（站在车旁听的），车内正好
         * 也是车外听感（或者这种风格不分听感）时两路用同一份，否则另合成一份。车外那一路再合成单声道、去低音、压一点（{@link ExteriorMix}）。
         * 两路可以有一路是 null。
         *
         * @return 出错停的（写不进喇叭）
         */
        private boolean loop(SoundOutput inside, SoundOutput outside) {
            Telemetry telemetry = Telemetry.get();
            AudioManager audio = app.getSystemService(AudioManager.class);
            DriveInputs inputs = new DriveInputs();
            VirtualEngine engine = new VirtualEngine(SoundProfile.V8, style.gearbox(gearbox), style.cranks(), style.pseudoGears(),
                    style.realNeutral());
            Voice voice = inside != null ? style.voice(SoundOutput.RATE, cabin) : null;
            Voice voiceOut = null;
            if (outside != null) {
                voiceOut = voice != null && (!cabin || !style.hasViews()) ? voice : style.voice(SoundOutput.RATE, false);
            }
            ExteriorMix exterior = outside != null ? new ExteriorMix(SoundOutput.RATE) : null;
            VirtualEngine.Input in = new VirtualEngine.Input();
            VoiceParams params = new VoiceParams();
            float[] buffer = new float[2 * BLOCK];
            float[] outsideBuffer = new float[2 * BLOCK];
            float[] exteriorBuffer = new float[2 * BLOCK];
            float dt = BLOCK / (float) SoundOutput.RATE;
            boolean ignited = false;
            // 在放熄火（VirtualEngine.shutdown）
            boolean windingDown = false;
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
            long lastWakeNs = 0L;
            int blocksSinceWake = 0;
            long seenFastVersion = Long.MIN_VALUE;
            long seenPushCount = -1L;
            long inputVersion = 0L;
            // 油门：推送、快读赛跑，谁先带来新值用谁（ThrottleSources）；再还原成连续的踏板（车机约 200 ms 才更新一次，PedalTrack）
            ThrottleSources sources = new ThrottleSources();
            PedalTrack pedal = new PedalTrack();
            SpeedTrack speedTrack = new SpeedTrack();
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
                    readMedia(audio);
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
                boolean speedPushed = DriveSoundRules.usePush(pushSpeed.count, pushSpeed.atMs, pushSpeed.value, polledSpeed, now);
                float speed = speedPushed ? pushSpeed.value : polledSpeed;
                long speedAt = speedPushed ? pushSpeed.atMs : fast.atMs;
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
                // 车速还原成连续的，加速度也从这里来（读数一级一级来，SpeedTrack）
                speedTrack.sample(speed, speedAt);
                speedTrack.step(dt, now);
                speedEveryMs = speedTrack.everyMs();
                PushedValue mainListener = telemetry.throttleListener(false);
                PushedValue drListener = telemetry.throttleListener(true);
                if (mainListener.count > 0L) {
                    mainEveryMs = mainListener.everyMs;
                    mainPushes = mainListener.count;
                }
                if (drListener.count > 0L) {
                    drEveryMs = drListener.everyMs;
                    drPushes = drListener.count;
                }
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
                DriveSoundRules.Why asked = DriveSoundRules.decide(enabled, link, r.code(Signal.IGNITION), gearText,
                        r.code(Signal.DRIVE_MODE), standardMode, inputs.speed(), inputs.throttle(), fast.ageMs(now), inCall);
                DriveSoundRules.Why w = stopping ? DriveSoundRules.Why.OFF : asked;
                why = w;
                // 虚拟发动机看还原后的车速、踏板和按相邻读数算的快慢、加速度；还原的读不到时照快读的
                boolean speedTracked = !Float.isNaN(speedTrack.speed());
                in.speedKmh = speedTracked ? speedTrack.speed() : inputs.speed();
                in.accel = speedTracked ? speedTrack.accel() : inputs.accel();
                boolean tracked = !Float.isNaN(pedal.value());
                in.throttlePct = tracked ? pedal.value() : inputs.throttle();
                in.pedalRate = tracked ? pedal.rate(now) : inputs.pedalRate();
                in.braking = inputs.brake() > VirtualEngine.BRAKE_ON || Boolean.TRUE.equals(r.bool(Signal.BRAKE_PEDAL));
                in.gear = DriveSoundRules.gearOf(gearText);
                in.flow = DriveSoundRules.flowOf(r.code(Signal.POWER_FLOW));
                in.mode = rpmMode;
                in.eager = DriveSoundRules.eagerOf(r.code(Signal.DRIVE_MODE));
                in.sportPedal = DriveSoundRules.sportPedalOf(r.code(Signal.DRIVE_MODE));
                engine.setGearbox(style.gearbox(gearbox));
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
                if (stopping && !windingDown && !hurry && ignited && g > 0.5f && asked.shutsDown) {
                    // 切到不出声的驾驶模式、下电：发动机熄火（停着照真车，车在走像混动车停发动机），放完再放掉喇叭
                    engine.shutdown(in);
                    windingDown = true;
                    boolean moving = in.speedKmh >= VirtualEngine.START_LEAVE_KMH;
                    endedAs = moving ? String.format(Locale.US, "shutdown at %.0f km/h", in.speedKmh) : "shutdown";
                    BlackBox.note("模拟声浪：熄火（" + (moving ? "行驶中 " + Math.round(in.speedKmh) + " km/h" : "车停着") + "）");
                } else if (stopping && !windingDown && endedAs.isEmpty()) {
                    endedAs = asked == DriveSoundRules.Why.RUNNING ? "fade (style or view changed)" : "fade (" + asked + ")";
                } else if (windingDown && hurry && !endedAs.endsWith("cut short")) {
                    endedAs += ", cut short";
                }
                if (ignited) {
                    engine.step(dt, in);
                }
                float target;
                float fadeSec;
                if (windingDown && !hurry) {
                    // 熄火放着：门照开着，放完就停
                    if (engine.done()) {
                        return false;
                    }
                    target = g;
                    fadeSec = w.fadeSec;
                } else {
                    target = w.audible ? 1f : 0f;
                    fadeSec = hurry ? HURRY_FADE_SEC : w.fadeSec;
                }
                float stepSize = dt / Math.max(fadeSec, dt);
                g = target > g ? Math.min(target, g + stepSize) : Math.max(target, g - stepSize);
                gate = g;
                if (stopping && g <= 0f) {
                    return false;
                }
                if (ignited) {
                    engine.fill(params);
                    params.gain = g * EngineVoice.dbToLin(userDb + engine.outputDb());
                    rpm = engine.rpm();
                    load = engine.load();
                    levelDb = engine.levelDb();
                    gear = engine.geared() ? engine.gearIndex() + 1 : 0;
                    pseudo = engine.pseudoGears();
                    eager = engine.eager();
                    coupled = engine.coupled();
                } else {
                    params.gain = 0f;
                }
                pedalRate = in.pedalRate;
                accel = in.accel;
                long t0 = System.nanoTime();
                if (voice != null) {
                    voice.renderStereo(buffer, BLOCK, params);
                }
                if (voiceOut != null && voiceOut != voice) {
                    voiceOut.renderStereo(outsideBuffer, BLOCK, params);
                }
                if (exterior != null) {
                    float follow = mediaTracked ? DriveSoundRules.mediaFollowDb(mediaIndex, mediaMax) : 0f;
                    float exteriorGain = follow == Float.NEGATIVE_INFINITY ? 0f : EngineVoice.dbToLin(exteriorDb + follow);
                    exterior.process(voiceOut == voice ? buffer : outsideBuffer, exteriorBuffer, BLOCK, exteriorGain);
                }
                renderMs += ((System.nanoTime() - t0) / 1_000_000f - renderMs) * 0.01f;
                long w0 = System.nanoTime();
                if (inside != null && !inside.write(buffer, BLOCK)) {
                    trouble = "write failed (cabin)";
                    return true;
                }
                if (outside != null && !outside.write(exteriorBuffer, BLOCK)) {
                    trouble = "write failed (exterior)";
                    return true;
                }
                long w1 = System.nanoTime();
                blocks++;
                blocksSinceWake++;
                if (w1 - w0 > 1_000_000L) {
                    // 这一下写之前等了：混音刚取走一段。记它多久来一次、每次取多少块
                    if (lastWakeNs > 0L) {
                        float every = (w1 - lastWakeNs) / 1_000_000f;
                        mixerEveryMs = mixerEveryMs < 0f ? every : mixerEveryMs + (every - mixerEveryMs) * 0.05f;
                        burstBlocks = burstBlocks < 0f ? blocksSinceWake : burstBlocks + (blocksSinceWake - burstBlocks) * 0.05f;
                    }
                    lastWakeNs = w1;
                    blocksSinceWake = 0;
                }
                if (now >= nextWatch) {
                    nextWatch = now + WATCH_MS;
                    boolean grown = inside != null && inside.watch();
                    grown |= outside != null && outside.watch();
                    // 每秒刷新一次（输出延迟要实时的；以前只在加缓冲时刷新，诊断里一直是开的那一刻的「?」）
                    output = describeOutputs(inside, outside);
                    outputLatencyMs = (inside != null ? inside : outside).latencyMs();
                    if (grown) {
                        BlackBox.note("模拟声浪：欠载，缓冲加大（" + output + "）");
                    }
                }
            }
        }

        String describe() {
            return String.format(Locale.US,
                    "run: %s style=%s view=%s speakers=%s why=%s ignited=%s ended=%s rpm=%.0f gear=%d%s eager=%.1f coupled=%.2f "
                            + "load=%.2f level=%.1f dB gate=%.2f "
                            + "pedal=%.0f %%/s accel=%.2f m/s2 blocks=%d render=%.3f ms per %.1f ms block%s%n  output: %s%n"
                            + "  exterior: route %s, volume %+.0f dB, media volume %s%n"
                            + "  mixer: takes a period every %s (%s blocks each)%n"
                            + "  start: drive mode changed %s before the run was asked for, output opened in %s, "
                            + "first sound %s after the ask (%s after the mode change)%n"
                            + "  inputs: fast reads every %s (longest gap in 10 s %s), throttle changed every %s (%d changes), "
                            + "speed changed every %s%n"
                            + "  throttle push: every %s (%d pushes), a new value came first by push %d times (ahead by %s), "
                            + "by the fast read %d times (ahead by %s); fastest listener every %s (%d), RATE_DR listener every %s (%d)%n"
                            + "  pedal to speaker (the part measurable here): new throttle used %s after it arrived "
                            + "+ output %s (HAL timestamp; on the car's head unit it reads about 5.1 s whatever the buffer, so it is "
                            + "not real; + the head unit's own signal and amplifier delays, not measurable here)",
                    state, style.key, cabin ? "cabin" : "outside", outputMode, why, ignitedAs.isEmpty() ? "-" : ignitedAs,
                    endedAs.isEmpty() ? "-" : endedAs, rpm, gear,
                    pseudo ? " (pseudo)" : "", eager, coupled, load, levelDb, gate, pedalRate, accel,
                    blocks, renderMs, BLOCK * 1000f / SoundOutput.RATE,
                    trouble.isEmpty() ? "" : " trouble=" + trouble, output.isEmpty() ? "-" : output,
                    exteriorRoute, DriveSound.this.exteriorDb, mediaText(),
                    ms(mixerEveryMs), burstBlocks < 0f ? "?" : String.format(Locale.US, "%.1f", burstBlocks),
                    ms(modeAgeWhenAsked), ms(openMs), ms(askedToSoundMs), ms(modeToSoundMs),
                    ms(fastEveryMs), ms(fastGapMaxMs), ms(throttleEveryMs), throttleSteps, ms(speedEveryMs),
                    ms(pushEveryMs), pushes, pushFirst, ms(pushLeadMs), pollFirst, ms(pollLeadMs),
                    ms(mainEveryMs), mainPushes, ms(drEveryMs), drPushes,
                    ms(inputAgeMs), ms(outputLatencyMs));
        }

        /** 系统报的媒体音量（诊断）：此刻 / 最多，第一次读到的，写死没有，跟着变过没有、车外跟着缩放多少。 */
        private String mediaText() {
            if (mediaMax < 0) {
                return "not read";
            }
            float follow = DriveSoundRules.mediaFollowDb(mediaIndex, mediaMax);
            return String.format(Locale.US, "%d / %d (first %d, fixed=%s, %s)", mediaIndex, mediaMax, mediaFirst, mediaFixed,
                    mediaTracked ? (follow == Float.NEGATIVE_INFINITY ? "followed: muted"
                            : String.format(Locale.US, "followed: %.0f dB", follow)) : "never changed, not followed");
        }

        private String ms(float value) {
            return value < 0f ? "?" : String.format(Locale.US, "%.0f ms", value);
        }
    }
}
