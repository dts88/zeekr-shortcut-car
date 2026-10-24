package com.kooo.evcam.storage;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.FileTransferManager;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.StoragePlan;
import com.kooo.evcam.profile.RecordSpecs;
import com.kooo.evcam.settings.Languages;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 锁定当前录像：录像时闪一下远光（车机信号，自动），或者按一下悬浮按钮的「锁定当前录像」（手动），
 * 那一刻前后 10 秒的录像（每一路）锁上；打开双闪（车机信号，自动，项目所有者 2026-10-23），从双闪亮起前 1 分钟
 * 锁到关闭双闪。规则见 docs/storage-spec.md。
 *
 * <h3>框架</h3>
 *
 * <ul>
 *   <li><b>开不开</b>（{@link #flashEnabled}、{@link #hazardEnabled}）：「锁定影像」开着，而且下面「自动锁定」的
 *       「闪远光」/「双闪」按着（默认都关：自动锁的也占空间，锁满了就停录）。真正录上了 / 不录了（{@code MultiCameraManager}
 *       里录像状态只在一处改，开录失败、重建前停也走那里）、开关变了（设置页）都来这里对一次（{@link #update}）。
 *       悬浮按钮只看「锁定影像」（{@link Source}）。</li>
 *   <li><b>信号</b>：要看的时候自己在 {@link Telemetry} 登记（信息条关着也得读），不看了就注销。
 *       闪远光读数从「没拨 / 没数据」变成「拨着」的那一下算一次（上升沿），那一刻 t = 系统时间 ——
 *       和文件名里的时刻是同一个钟。连着闪几下各算各的，锁过的再锁一遍没影响。
 *       双闪看转向指示状态（{@link Signal#INDICATOR}，不跟着灯闪的那个）：变成 3 是亮起，变成别的值是关闭；
 *       没数据（车机连不上）不算关闭。开始看的时候已经亮着不算亮起，和闪远光一个规矩。</li>
 *   <li><b>锁哪些</b>：{@link LockWindow}（纯函数）—— 闪远光、悬浮按钮是 [t − 10 秒, t + 10 秒]，双闪是
 *       [亮起 − 1 分钟, 关闭]（{@link LockWindow#hazard}）碰到的录像文件，每一路都算。</li>
 *   <li><b>闪远光、悬浮按钮锁两遍</b>，都在 {@link #worker} 这一个线程上：t 那一刻锁盘上已经有的；t + 10 秒再过
 *       {@link LockWindow#SECOND_PASS_DELAY_MS} 再锁一遍，接住 t 之后才开始的那一段（几路共用一个文件名时间戳，
 *       晚切的那一路要晚一阵才换文件，写入线程排着队还要晚一阵才建好；停了录也照样补）。两遍都先看开关。
 *       写进 {@link FootageLocks}，和回放里手动锁的是同一份清单，删除规则一个字不用改。</li>
 *   <li><b>双闪</b>用的是同一个 {@link #pass}：亮起那一刻锁 [亮起 − 1 分钟, 亮起]（前一分钟和正在录的那一段），
 *       亮着时每 {@link #HAZARD_TICK_MS} 锁一遍新录的，关闭时照闪远光锁两遍 [亮起 − 1 分钟, 关闭]
 *       （关闭那一刻、再过 {@link LockWindow#SECOND_PASS_DELAY_MS}）。亮着时录像停了、开关关了，都当在那一刻关闭。</li>
 *   <li><b>录像在哪</b>（{@link #scan}）：U 盘录像目录；录制器换过盘就还有新盘上那个；中转写入时先写在内部缓存里，
 *       写完才搬到开录时定下的目标目录 —— 缓存里的按文件名锁进<b>那个</b>目录的清单（搬过去时名字不变），
 *       不是此刻的录像目录（U 盘掉线时它会落到内置存储上，锁进去等于没锁）。目标目录此刻不在（盘掉了），
 *       缓存里的先不锁，每 {@link #RELAY_RETRY_MS} 再试一次，等盘回来。</li>
 * </ul>
 *
 * <p>鸣笛自动锁定规则一样，但车上还读不到喇叭信号（Lab 还在找）：设置里那一项一直置灰、打不开，这里没有它的代码。
 * 找到信号后，它就是 {@link #lockAround} 的又一个来源（{@link Source}）。</p>
 */
public final class AutoLock implements Telemetry.Listener {

    private static final String TAG = "AutoLock";
    /** 在 {@link Telemetry} 登记用的名字。 */
    private static final String USER = "auto-lock";
    /** 中转写入的目标目录不在（盘掉了）时，隔多久再试一遍。 */
    static final long RELAY_RETRY_MS = 30_000L;
    /** 最多再试几遍：一小时。中转缓存里的文件放一小时就过期删掉（{@code FileTransferManager}），再等也没用。 */
    static final int RELAY_RETRY_LIMIT = 120;
    /** 离上一次弹不到这么久，提示不再弹（连着锁上几次时不一个接一个地弹）。 */
    private static final long TOAST_GAP_MS = 4_000L;
    /** 双闪亮着时隔多久把新录的锁上一遍：最短的分段是 1 分钟，半分钟一遍，正在录的那一段一开始就锁上。 */
    static final long HAZARD_TICK_MS = 30_000L;
    /** {@link #pass} 的第几遍：0 当时（双闪是关闭那一刻）、1 补锁、2 起等盘回来；双闪另有亮起那一遍和亮着时那几遍。 */
    private static final int ROUND_HAZARD_ON = -2;
    private static final int ROUND_HAZARD_ONGOING = -1;

    /**
     * 锁定从哪来。几个来源共用一套：此刻前后 10 秒、锁两遍、同一份清单（{@link #lockAround}）；
     * 不同的只有什么时候起作用、黑匣子上写哪个名字、怎么提示。
     */
    private enum Source {
        /** 闪远光（车机信号）：「锁定影像」开着、「自动锁定」的「闪远光」按着才锁；第一遍锁上了新的才提示。 */
        FLASH,
        /** 双闪（车机信号）：「锁定影像」开着、「自动锁定」的「双闪」按着才锁；亮起那一遍锁上了新的才提示。 */
        HAZARD,
        /** 悬浮按钮「锁定当前录像」：「锁定影像」开着就锁；每按一次回一句（{@link Reply}）。 */
        BUTTON;

        boolean enabled(Context context) {
            switch (this) {
                case FLASH:
                    return flashEnabled(context);
                case HAZARD:
                    return hazardEnabled(context);
                default:
                    return FootageLocks.enabled(context);
            }
        }
    }

    private static final AutoLock INSTANCE = new AutoLock();

    private final Handler main = new Handler(Looper.getMainLooper());
    /** 两遍锁定（列目录、算重叠、交给 FootageLocks）和取分段时长都在这一个线程上，按先后排队。 */
    private final Handler worker;

    // ---- 下面这几个只在主线程上动
    private Context app;
    private boolean recording;
    /** 这次录像中转写入时，缓存里的文件写完搬去哪（开录时定下的）；不是中转写入为 null。 */
    private File relayTarget;
    private boolean watching;
    private Boolean lastFlash;
    private Boolean lastHazard;
    /** 这一次双闪亮起的时刻（系统时间）；没在锁双闪时为 0。 */
    private long hazardSinceMs;
    private final Runnable hazardTick = this::hazardTick;
    private long lastToastAt;

    /** 这次录像每一路的分段时长：对外的槽位名 → 毫秒。开录时取；只在 {@link #worker} 上读写。 */
    private Map<String, Long> segmentMs = new HashMap<>();

    private AutoLock() {
        HandlerThread thread = new HandlerThread("AutoLock");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public static AutoLock get() {
        return INSTANCE;
    }

    /** 闪远光自动锁定此刻起不起作用：「锁定影像」开着，而且这一项开着。 */
    public static boolean flashEnabled(Context context) {
        return FootageLocks.enabled(context) && new AppConfig(context).isFlashLockEnabled();
    }

    /** 双闪自动锁定此刻起不起作用：「锁定影像」开着，而且这一项开着（项目所有者 2026-10-23，默认关）。 */
    public static boolean hazardEnabled(Context context) {
        return FootageLocks.enabled(context) && new AppConfig(context).isHazardLockEnabled();
    }

    // ================================================================= 什么时候看信号

    /**
     * 真正录上了（{@code MultiCameraManager} 的录像状态变成「在录」）。哪个线程调都行。
     *
     * @param relayTarget 中转写入时，缓存里的文件写完搬去的目录（开录时定下的）；不是中转写入传 null
     */
    public void recordingStarted(Context context, File relayTarget) {
        final Context appContext = context.getApplicationContext();
        // 排在之后所有的锁定前面：同一个线程，先来先做
        worker.post(() -> segmentMs = segmentLengths(appContext));
        main.post(() -> {
            app = appContext;
            recording = true;
            this.relayTarget = relayTarget;
            update();
        });
    }

    /**
     * 不录了（{@code MultiCameraManager} 的录像状态变成「不在录」：停录、开录失败、重建前停）：不再看信号。
     * 已经排上的第二遍照样锁。
     */
    public void recordingStopped() {
        main.post(() -> {
            recording = false;
            update();
        });
    }

    /** 设置页里「锁定影像」「闪远光」「双闪」变了：正在录的话马上按新的来。 */
    public void settingsChanged(Context context) {
        if (context == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        main.post(() -> {
            if (app == null) {
                app = appContext;
            }
            update();
        });
    }

    /** 该不该看信号，和现在看没看对一下。主线程。 */
    private void update() {
        boolean want = recording && app != null && (flashEnabled(app) || hazardEnabled(app));
        // 双闪亮着时录像停了、双闪那一项（或者锁定影像）关了：这一次双闪当在此刻关闭（开关关了的，那两遍看到开关不锁）
        if (hazardSinceMs > 0 && (!want || !hazardEnabled(app))) {
            hazardOff(System.currentTimeMillis());
        }
        if (want == watching) {
            return;
        }
        watching = want;
        Telemetry telemetry = Telemetry.get();
        if (want) {
            // 信号已经在收（信息条开着）时从此刻的读数起算：拨杆一直拨着、双闪一直亮着都不算新的一下
            Readings now = telemetry.readings();
            lastFlash = now.bool(Signal.HIGH_BEAM_FLASH);
            lastHazard = hazardOn(now);
            telemetry.addListener(this);
            telemetry.acquire(app, USER);
            BlackBox.note("车机信号自动锁定：录像期间看闪远光、双闪信号");
        } else {
            telemetry.removeListener(this);
            telemetry.release(USER);
            lastFlash = null;
            lastHazard = null;
            BlackBox.note("车机信号自动锁定：不再看闪远光、双闪信号（" + (recording ? "开关关了" : "录像停了") + "）");
        }
    }

    /** 双闪亮没亮：转向指示状态是 3（双闪）；没数据为 null。 */
    private static Boolean hazardOn(Readings readings) {
        Integer indicator = readings.code(Signal.INDICATOR);
        return indicator == null ? null : indicator == 3;
    }

    /** 读数变了（主线程）：闪远光的上升沿算一次；双闪亮起、关闭各算一次。 */
    @Override
    public void onReadingsChanged(Readings readings) {
        if (!watching) {
            return;
        }
        long now = System.currentTimeMillis();
        Boolean flash = readings.bool(Signal.HIGH_BEAM_FLASH);
        boolean rising = Boolean.TRUE.equals(flash) && !Boolean.TRUE.equals(lastFlash);
        lastFlash = flash;
        if (rising && flashEnabled(app)) {
            lockAround(app, now, Source.FLASH);
        }
        Boolean hazard = hazardOn(readings);
        if (Boolean.TRUE.equals(hazard) && !Boolean.TRUE.equals(lastHazard) && hazardEnabled(app)) {
            hazardOnAt(now);
        } else if (Boolean.FALSE.equals(hazard) && hazardSinceMs > 0) {
            hazardOff(now);
        }
        if (hazard != null) {
            lastHazard = hazard;
        }
    }

    /**
     * 悬浮按钮「锁定当前录像」：按下那一刻前后 10 秒，和闪远光同一套（{@link #lockAround}）。哪个线程调都行。
     * 没在录、「锁定影像」关着时不锁，直接说一句。
     */
    public void lockNow(Context context) {
        final long momentMs = System.currentTimeMillis();
        final Context appContext = context.getApplicationContext();
        main.post(() -> {
            if (!recording) {
                BlackBox.note("悬浮按钮锁定：没在录，不锁");
                toast(appContext, R.string.msg_footage_nothing_here);
            } else if (!FootageLocks.enabled(appContext)) {
                BlackBox.note("悬浮按钮锁定：「锁定影像」关着，不锁");
                toast(appContext, R.string.msg_footage_lock_needs_setting);
            } else {
                lockAround(appContext, momentMs, Source.BUTTON);
            }
        });
    }

    // ================================================================= 锁

    /** 这一刻前后 10 秒（闪远光、悬浮按钮）：现在锁一遍，窗口结束后再锁一遍（{@link #lockWindow}）。主线程。 */
    private void lockAround(Context context, long momentMs, Source source) {
        lockWindow(context, LockWindow.around(momentMs), momentMs, source,
                source == Source.FLASH ? R.string.msg_flash_locked : 0);
    }

    /**
     * 一个时间窗锁两遍：现在一遍（当时），窗口结束后再过 {@link LockWindow#SECOND_PASS_DELAY_MS} 补一遍。主线程。
     *
     * @param toast 第一遍锁上了新的弹哪一句；0 不弹（手动锁定另有 {@link Reply}）
     */
    private void lockWindow(Context context, LockWindow window, long nowMs, Source source, int toast) {
        // 这次录像的中转目标跟着这一下走：第二遍时可能已经停了录、又开了一次
        final File target = relayTarget;
        // 手动按的每按一次回一句：两遍共用一个，先碰到录像的那一遍回
        final Reply reply = source == Source.BUTTON ? new Reply(context) : null;
        worker.post(() -> pass(context, window, target, 0, source, reply, toast));
        worker.postDelayed(() -> pass(context, window, target, 1, source, reply, 0),
                Math.max(0L, window.endMs - nowMs) + LockWindow.SECOND_PASS_DELAY_MS);
    }

    /**
     * 双闪亮起：锁 [亮起 − 1 分钟, 亮起] —— 前一分钟和正在录的那一段；锁上了新的提示一句。
     * 亮着时每 {@link #HAZARD_TICK_MS} 再锁一遍新录的（{@link #hazardTick}）。主线程。
     */
    private void hazardOnAt(long nowMs) {
        hazardSinceMs = nowMs;
        final Context context = app;
        final LockWindow window = LockWindow.hazard(nowMs, nowMs);
        final File target = relayTarget;
        worker.post(() -> pass(context, window, target, ROUND_HAZARD_ON, Source.HAZARD, null, R.string.msg_hazard_locked));
        main.removeCallbacks(hazardTick);
        main.postDelayed(hazardTick, HAZARD_TICK_MS);
    }

    /** 双闪亮着：从亮起前 1 分钟锁到此刻，接住这半分钟里新开始的那一段。主线程。 */
    private void hazardTick() {
        if (hazardSinceMs <= 0 || app == null) {
            return;
        }
        final Context context = app;
        final LockWindow window = LockWindow.hazard(hazardSinceMs, System.currentTimeMillis());
        final File target = relayTarget;
        worker.post(() -> pass(context, window, target, ROUND_HAZARD_ONGOING, Source.HAZARD, null, 0));
        main.postDelayed(hazardTick, HAZARD_TICK_MS);
    }

    /** 双闪关闭（或者录像停了、开关关了）：[亮起 − 1 分钟, 此刻] 照闪远光锁两遍（{@link #lockWindow}）。主线程。 */
    private void hazardOff(long nowMs) {
        main.removeCallbacks(hazardTick);
        long since = hazardSinceMs;
        hazardSinceMs = 0;
        if (since > 0 && app != null) {
            lockWindow(app, LockWindow.hazard(since, nowMs), nowMs, Source.HAZARD, 0);
        }
    }

    /**
     * 一遍：列目录、算重叠、锁上还没锁的。{@link #worker} 上。
     *
     * @param relayTarget 中转写入的目标目录（见 {@link #scan}）；不是中转写入为 null
     * @param round       0 = 当时（双闪是关闭那一刻），1 = 补锁（窗口结束后），2 起 = 等中转目标目录回来再试；
     *                    双闪另有 {@link #ROUND_HAZARD_ON}（亮起那一刻）、{@link #ROUND_HAZARD_ONGOING}（亮着时）
     * @param reply       手动锁定时回的那一句；自动的为 null
     * @param toast       这一遍锁上了新的弹哪一句；0 不弹
     */
    private void pass(Context context, LockWindow window, File relayTarget, int round, Source source, Reply reply,
                      int toast) {
        if (!source.enabled(context)) {
            BlackBox.noteImportant((source == Source.FLASH ? "闪远光自动锁定" : source == Source.HAZARD ? "双闪自动锁定" : "悬浮按钮锁定")
                    + (round == ROUND_HAZARD_ON ? "（双闪亮起）" : round == ROUND_HAZARD_ONGOING ? "（双闪中）"
                            : round == 0 ? (source == Source.HAZARD ? "（双闪关闭）" : "（当时）")
                            : round == 1 ? "（补锁）" : "（等盘回来）")
                    + " " + describe(window) + "：开关已关，不锁");
            return;
        }
        Scan scan = scan(context, relayTarget);
        Set<String> all = new HashSet<>(scan.waiting);
        for (Set<String> names : scan.byListDir.values()) {
            all.addAll(names);
        }
        Set<String> hit = window.overlapping(all, segmentMs, longest(segmentMs));
        // 碰到窗口、却因为中转目标目录不在而这一遍锁不了的
        Set<String> waiting = new TreeSet<>(scan.waiting);
        waiting.retainAll(hit);
        List<String> locking = new ArrayList<>();
        int alreadyLocked = 0;
        // 碰到了能锁的录像（已经锁着的也算）；每份清单这一遍要新锁上的
        boolean found = false;
        Map<File, Set<String>> toLock = new LinkedHashMap<>();
        for (Map.Entry<File, Set<String>> entry : scan.byListDir.entrySet()) {
            Set<String> names = new TreeSet<>(entry.getValue());
            names.retainAll(hit);
            if (names.isEmpty()) {
                continue;
            }
            found = true;
            Set<String> listed = FootageLocks.read(entry.getKey());
            if (listed != null) {
                int before = names.size();
                names.removeAll(listed);
                alreadyLocked += before - names.size();
            }
            if (!names.isEmpty()) {
                locking.addAll(names);
                toLock.put(entry.getKey(), names);
            }
        }
        FootageLocks.Result result = reply != null ? reply.after(round, found, !waiting.isEmpty(), toLock.size())
                : toast != 0 ? toastWhenLocked(toast) : null;
        for (Map.Entry<File, Set<String>> entry : toLock.entrySet()) {
            FootageLocks.set(entry.getKey(), entry.getValue(), true, result);
        }
        // 第二遍起还有锁不了的：隔一阵再试，等盘回来（第一遍不用，第二遍本来就排着；双闪亮着时那几遍后面还有）
        boolean retry = !waiting.isEmpty() && round >= 1 && round < RELAY_RETRY_LIMIT;
        if (retry) {
            worker.postDelayed(() -> pass(context, window, relayTarget, round + 1, source, reply, 0), RELAY_RETRY_MS);
        }
        // 等盘回来的那几遍、双闪亮着时那几遍只在锁上了（或者不再等）时记一行，不然一小时能记一百多行
        boolean log = round == ROUND_HAZARD_ONGOING ? !locking.isEmpty() : round <= 1 || !locking.isEmpty() || !retry;
        if (log) {
            BlackBox.noteImportant((source == Source.FLASH ? "闪远光自动锁定" : source == Source.HAZARD ? "双闪自动锁定" : "悬浮按钮锁定")
                    + (round == ROUND_HAZARD_ON ? "（双闪亮起）" : round == ROUND_HAZARD_ONGOING ? "（双闪中）"
                            : round == 0 ? (source == Source.HAZARD ? "（双闪关闭）" : "（当时）")
                            : round == 1 ? "（补锁）" : "（等盘回来）")
                    + " " + describe(window)
                    + "：" + (locking.isEmpty() ? "没有新的要锁" : "锁上 " + locking.size() + " 个 " + locking)
                    + (alreadyLocked > 0 ? "，已经锁着 " + alreadyLocked + " 个" : "")
                    + (hit.isEmpty() ? "（盘上没有这段时间的录像）" : "")
                    + (waiting.isEmpty() ? "" : "；中转缓存里 " + waiting.size() + " 个 " + waiting + " 要搬去的 "
                            + relayTarget + " 此刻不在（盘掉了？），这一遍没锁"
                            + (retry ? "，" + (RELAY_RETRY_MS / 1000) + " 秒后再试"
                                    : round == 0 ? "，补锁时再试" : round < 0 ? "，下一遍再试" : "，不再等")));
        }
    }

    /**
     * 第一遍（双闪是亮起那一遍）锁上了新的就告诉一声（{@link FootageLocks#set} 在主线程上回调）。只在这时弹
     * （项目所有者 2026-10-04）：又闪了一下、那一段本来就锁着，不弹。
     */
    private FootageLocks.Result toastWhenLocked(int message) {
        return ok -> {
            if (ok) {
                toastLocked(message);
            }
        };
    }

    /** 「闪远光：已锁定前后 10 秒……」「双闪：已锁定前 1 分钟起……」。离上一次弹不到 {@link #TOAST_GAP_MS} 就不再弹。主线程。 */
    private void toastLocked(int message) {
        Context context = app;
        long now = SystemClock.uptimeMillis();
        if (context == null || (lastToastAt > 0 && now - lastToastAt < TOAST_GAP_MS)) {
            return;
        }
        lastToastAt = now;
        Toast.makeText(context, Languages.localized(context).getString(message), Toast.LENGTH_SHORT).show();
    }

    /**
     * 手动锁定（悬浮按钮）回的那一句：一次按下只回一句，由两遍里先碰到录像的那一遍回 ——
     * 碰到的都锁上了（新锁的、本来就锁着的都算）说「已锁定」，有一份清单没写成说「无法保存锁定状态」；
     * 两遍都没碰到说「当前时刻无可锁定的录像」。中转写入、目标盘不在、要等盘回来才能锁的不算碰到，
     * 也不说没有（开发者选项里才有中转写入）。
     */
    private final class Reply {
        private final Context context;
        /** 已经有一遍认领了这一句。只在 {@link #worker} 上读写（两遍都在它上面）。 */
        private boolean claimed;

        Reply(Context context) {
            this.context = context;
        }

        /**
         * 这一遍之后回不回、怎么回。{@link #worker} 上。
         *
         * @param found   这一遍碰到了能锁的录像（已经锁着的也算）
         * @param waiting 这一遍碰到了要等盘回来才能锁的
         * @param lists   这一遍要写几份清单（0 = 碰到的都已经锁着）
         * @return 每份清单写完时的回调；不用回调时为 null
         */
        FootageLocks.Result after(int round, boolean found, boolean waiting, int lists) {
            if (claimed || round > 1) {
                return null;
            }
            if (!found) {
                if (round == 1 && !waiting) {
                    claimed = true;
                    main.post(() -> toast(context, R.string.msg_footage_nothing_here));
                }
                return null;
            }
            claimed = true;
            if (lists == 0) {
                main.post(() -> toast(context, R.string.msg_footage_locked_around));
                return null;
            }
            // 几份清单都写完再说（FootageLocks 在主线程上回调）
            final int[] left = {lists};
            final boolean[] failed = {false};
            return ok -> {
                failed[0] |= !ok;
                if (--left[0] == 0) {
                    toast(context, failed[0] ? R.string.msg_footage_lock_failed : R.string.msg_footage_locked_around);
                }
            };
        }
    }

    /** 主线程。没保存成的说得久一点，和回放里一样。 */
    private static void toast(Context context, int message) {
        Toast.makeText(context, Languages.localized(context).getString(message),
                message == R.string.msg_footage_lock_failed ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    // ================================================================= 盘上有什么

    /** 一遍列出来的：按锁定清单所在目录分好的文件名；中转目标目录不在、这一遍锁不了的缓存文件名。 */
    private static final class Scan {
        final Map<File, Set<String>> byListDir = new LinkedHashMap<>();
        final Set<String> waiting = new TreeSet<>();
    }

    /**
     * 录像可能在哪，按「锁定清单在哪个目录」分好：此刻的录像目录；录制器换过盘时新盘上那个。
     * 中转写入（{@code relayTarget} 不为 null）时还有：已经搬到目标目录的；内部缓存里还没搬的 ——
     * 锁进目标目录的清单（搬过去名字不变）。目标目录此刻不在（盘掉了），缓存里的不往别处写，放进 waiting。
     * 只认本应用的录像文件名。
     */
    private static Scan scan(Context context, File relayTarget) {
        Scan scan = new Scan();
        File video = StorageHelper.getVideoDir(context);
        File temp = new File(context.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
        File actual = StorageHelper.lastRecordingDir();
        collect(scan.byListDir, video, video);
        if (actual != null && !actual.getAbsoluteFile().equals(temp.getAbsoluteFile())) {
            collect(scan.byListDir, actual, actual);
        }
        if (relayTarget != null) {
            if (relayTarget.isDirectory()) {
                collect(scan.byListDir, relayTarget, relayTarget);
                collect(scan.byListDir, temp, relayTarget);
            } else {
                scan.waiting.addAll(ownClips(temp));
            }
        }
        return scan;
    }

    private static void collect(Map<File, Set<String>> out, File dir, File listDir) {
        Set<String> names = ownClips(dir);
        if (names.isEmpty()) {
            return;
        }
        File key = listDir.getAbsoluteFile();
        Set<String> set = out.get(key);
        if (set == null) {
            set = new TreeSet<>();
            out.put(key, set);
        }
        set.addAll(names);
    }

    /** 目录里本应用的录像文件名；列不出来（不在、读不了）为空。 */
    private static Set<String> ownClips(File dir) {
        Set<String> out = new TreeSet<>();
        String[] names = dir == null ? null : dir.list();
        if (names != null) {
            for (String name : names) {
                if (StoragePlan.isOwnClip(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /** 这次录像每一路的分段时长，和录制器开录时取的是同一处（{@link RecordSpecs}）。 */
    private static Map<String, Long> segmentLengths(Context context) {
        Map<String, Long> out = new HashMap<>();
        for (String key : RecordSpecs.enabledCameraKeys(context)) {
            out.put(CameraSlots.suffixFor(key),
                    RecordSpecs.segmentMs(RecordSpecs.forCameraKey(context, key).segmentMinutes));
        }
        AppLog.d(TAG, "segment lengths " + out);
        return out;
    }

    /** 表里没有的那一路（自定义车型）按最长的算：宁可多锁。 */
    private static long longest(Map<String, Long> lengths) {
        long longest = RecordSpecs.segmentMs(RecordSpecs.DEFAULT_SEGMENT_MINUTES);
        for (Long ms : lengths.values()) {
            if (ms != null && ms > longest) {
                longest = ms;
            }
        }
        return longest;
    }

    private static String describe(LockWindow window) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.US);
        return format.format(new Date(window.startMs)) + "-" + format.format(new Date(window.endMs));
    }
}
