package com.kooo.evcam.recording;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.ChannelRules;
import com.kooo.evcam.camera.ChannelScheduler;

/**
 * 熄屏录制（开发者选项，规格 §3.1）：熄屏时正在录像，就拿住唤醒锁不让车机睡（熄屏 v3 起只录环视：座舱屏幕黑满 30 秒放开）。
 *
 * <p>熄屏录制 = 熄屏持续录制 + 防止休眠。车机下电（安卓熄屏）后几秒就深睡、进程被结束或冻住，睡着的进程一行代码都不跑，
 * 要在会睡的车上接着录，只有不让它睡这一个手段（平台笔记 §3.6）。项目所有者 2026-09-27 明确允许。
 * 原来「常驻唤醒锁」那个开关的能力全部归到这里，再往上做：</p>
 *
 * <ul>
 *   <li><b>只在录像期间拿</b>，录像停了就放；存储一类停了、判了自动接回的那一段也算（{@link RecordingCoordinator#isRecordingOrResuming}）——
 *       锁留到接回开录、或者确定不接了（大纲 §7 第 8 问）。放的时候屏幕黑着、锁正拉着车机（{@link #pullsCarAwake}），
 *       协调器先照熄屏那张表重判：车机要睡的锁留到相机按次序关完（以前一停就放，车机几秒后睡过去，不经那张表）；</li>
 *   <li>最长拿多久由用户设，<b>从熄屏那一刻起算</b>：熄屏 v3 起熄屏是屏幕真黑了（车辆信号判的，车主下车那一下；
 *       ScreenState 的熄屏那一下拿、亮屏那一下放，车机要睡、车机黑着醒了都不叫到这里）。
 *       到点不当场放：照熄屏那张表再判一次（{@link RecordingCoordinator#lockTimedOut}，锁不算拉着车机了）——
 *       车机醒着（安卓亮着）、哨兵模式开着、车在走，接着录或 10 秒后停，锁现在放；车机要睡了（安卓熄屏），现在停录、
 *       相机按次序关完再放（2026-10-10，以前到点就放，车机带着开着的相机睡过去），亮屏、或熄屏中哨兵模式变成开时接（同 §2.4）。
 *       黑满 30 秒时通道放开了环视（熄屏持续录制关着、哨兵模式没开）也停录，锁随停录放（2026-10-27）；</li>
 *   <li>屏幕已经黑着时才开始的录像（熄屏期间接回的那种）同样拿，剩余时长从熄屏那一刻算；</li>
 *   <li>活在进程上，不靠主界面：熄屏、亮屏由 {@code ScreenState}（进程里唯一的屏幕状态源）告诉这里，
 *       主界面在不在都一样。</li>
 * </ul>
 */
public final class ScreenOffRecording {

    private static final String TAG = "ScreenOffRecording";
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    /**
     * 到点判了「现在停」之后，等相机按次序关完再放锁：最多等这么久（锁一放，车机几秒就睡）—— 调度的「唤醒锁最多拉」：
     * 车机要睡时手上那一步再等 1 秒，再按次序关一轮（环视单独先关最多 10 秒，座舱最多 30 秒），由常量算出来（大纲 §2 阶段 6）。
     * 以前 5 秒封顶，盖不住环视那一次关。
     */
    private static final long CLOSE_WAIT_MAX_MS = ChannelRules.WAKE_HOLD_MAX_MS;

    private static Runnable timeout;
    /** 屏幕什么时候黑的（开机起算，含深睡）；0 = 亮着或不知道。 */
    private static long screenOffAtMs;
    private static long heldSinceMs;
    private static int heldForMinutes;
    /** 到点停录之后，从什么时候起等相机关完再放锁（开机起算，含深睡）；0 = 没在等。 */
    private static long closeWaitSinceMs;
    /** 等相机关完再放锁的那一次，为什么判的（放锁那一行写，ASCII）。 */
    private static String closeWaitWhy;

    private ScreenOffRecording() {
    }

    /** 熄屏了：记下时刻；正在录像、熄屏录制开着，就拿锁。 */
    public static void onScreenOff(Context context) {
        screenOffAtMs = SystemClock.elapsedRealtime();
        ensure(context);
    }

    /** 录像开始了。屏幕可能早就黑着（熄屏期间接回的那种），那也要拿。 */
    public static void onRecordingStarted(Context context) {
        if (!com.kooo.evcam.screen.ScreenState.dark()) {
            return;
        }
        if (screenOffAtMs == 0) {
            // 不知道什么时候黑的（进程刚起来）：从现在起算
            screenOffAtMs = SystemClock.elapsedRealtime();
        }
        ensure(context);
    }

    /**
     * 录像要的几样放了（录像停了、不接回；判了自动接回的，协调器留到接回开录或者不接了才叫）：放。
     * 判了「现在停」、在等相机关完的那一次除外 —— 锁留到相机关完（{@link #holdUntilCamerasClosed}）。
     * 屏幕黑着、锁正拉着车机的，协调器先照熄屏那张表重判，不走这里（{@link #pullsCarAwake}）。
     *
     * @param why 给黑匣子看的原因（ASCII）
     */
    static void onRecordingStopped(String why) {
        if (closeWaitSinceMs != 0) {
            return;
        }
        release(why);
    }

    /**
     * 锁此刻拿着、而且不是在等相机关完再放（{@link #holdUntilCamerasClosed}）：屏幕黑着时就是锁正拉着车机 ——
     * 录像要的几样放了的时候，协调器照它决定放锁之前要不要先照熄屏那张表重判（{@link RecordingStops#rejudgesBeforeLockGoes}）。
     * 看的是锁本身，不是时限（{@link #holdsCarAwake}）：时限刚过、到点那一下还没轮到主线程时，锁还拉着车机。
     */
    static boolean pullsCarAwake() {
        return WakeUpHelper.isPersistentWakeLockHeld() && closeWaitSinceMs == 0;
    }

    /** 亮屏了：放。 */
    public static void onScreenOn() {
        screenOffAtMs = 0;
        release("screen-on");
    }

    /**
     * 熄屏录制此刻拉不拉得住车机：开关开着、在录（含存储一类停了、留着等自动接回的那一段，
     * {@link RecordingCoordinator#isRecordingOrResuming}）、从熄屏起还没到用户设的时长 —— 和拿锁的条件同一个（{@link #ensure}），
     * 屏幕黑着时它成立就是锁拿着。熄屏那一刻、到点那一刻怎么做（{@link ScreenOffPlan#atDark}）看的是它，不是开关本身：
     * 到点之后开关还开着，锁已经不拉着车机了；没在录（在等 U 盘、在等亮屏接）时不拿锁，车机照样会睡。
     * 屏幕亮着时问的是「现在熄屏的话拿不拿」（录制键上的小字）。
     */
    public static boolean holdsCarAwake(Context context) {
        AppConfig config = new AppConfig(context);
        return config.isScreenOffRecordingEnabled() && RecordingCoordinator.get(context).isRecordingOrResuming()
                && remainingMs(config) > 0;
    }

    /** 从熄屏起算还能拿多久；还亮着（还没熄屏）是整段时长。 */
    private static long remainingMs(AppConfig config) {
        long limit = config.getScreenOffWakeMinutes() * 60_000L;
        return screenOffAtMs == 0 ? limit : limit - (SystemClock.elapsedRealtime() - screenOffAtMs);
    }

    /** 该拿就拿、到点就交给协调器重判。幂等，多调无害。 */
    private static void ensure(Context context) {
        AppConfig config = new AppConfig(context);
        // 「在录」问协调器：开录指令一发出去就算（以前问相机层，屏幕黑着时开始的录像在开录中这一步拿不到锁）；
        // 存储一类停了、留着等自动接回的那一段也算（大纲 §7 第 8 问）
        RecordingCoordinator coordinator = RecordingCoordinator.get(context);
        if (!config.isScreenOffRecordingEnabled() || !coordinator.isRecordingOrResuming()) {
            return;
        }
        int minutes = config.getScreenOffWakeMinutes();
        long remaining = remainingMs(config);
        final Context app = context.getApplicationContext();
        if (remaining <= 0) {
            timedOut(app);
            return;
        }
        cancelTimeout();
        timeout = () -> timedOut(app);
        HANDLER.postDelayed(timeout, remaining);
        if (!WakeUpHelper.isPersistentWakeLockHeld()) {
            WakeUpHelper.acquirePersistentWakeLock(context);
            heldSinceMs = SystemClock.elapsedRealtime();
            heldForMinutes = minutes;
            BlackBox.noteImportant("熄屏录制：" + (coordinator.isRecording() ? "在录像" : "在等自动接回")
                    + "，拿住唤醒锁不让车机睡，还能拿 " + remaining / 60000
                    + " 分钟（上限 " + minutes + " 分钟，从熄屏起算）");
            AppLog.i(TAG, "wake lock held, " + remaining / 60000 + " min left of " + minutes);
        }
    }

    /**
     * 到了用户设的时长（或者屏幕黑着才开始的录像，开录时已经过了时长）：锁先不放，协调器照熄屏那张表重判一次
     * （{@link RecordingCoordinator#lockTimedOut}），由它放。排到主线程的下一轮：这里可能是开录那一步叫到的，
     * 不能在开录的途中停录。
     */
    private static void timedOut(Context app) {
        cancelTimeout();
        HANDLER.post(() -> RecordingCoordinator.get(app).lockTimedOut());
    }

    /**
     * 到点之后判了「现在停、相机按次序关」：锁留着，等调度发出「关完」（{@link ChannelScheduler#closingAnywhere} 变假，
     * {@link ChannelScheduler#whenClosedEverywhere}），或者等满 {@link #CLOSE_WAIT_MAX_MS}，再放 ——
     * 先放的话车机几秒就睡，环视、座舱还没关完（2026-10-10）。停录那一步的放锁这时不放（{@link #onRecordingStopped}）；
     * 亮屏照样当场放。协调器在写下「车机要睡」之后、停录之前调：调度那时已经当场置了「正在按次序关」。
     * 到点、录像停了不接回、不等自动接回了，都可能判到这里（协调器的 letLockGo）。
     *
     * <p>以前每 100 ms 问一次管理器「关完没有」、5 秒封顶（大纲 §2 阶段 6 改）。</p>
     *
     * @param why 这一次为什么判的，放锁那一行写（ASCII：timeout / recording-stopped / resume-dropped …）
     */
    static void holdUntilCamerasClosed(String why) {
        if (!WakeUpHelper.isPersistentWakeLockHeld()) {
            return;
        }
        closeWaitWhy = why;
        closeWaitSinceMs = SystemClock.elapsedRealtime();
        HANDLER.removeCallbacks(closeWaitCap);
        HANDLER.postDelayed(closeWaitCap, CLOSE_WAIT_MAX_MS);
        ChannelScheduler.cancelClosedWaiter(camerasClosed);
        // 此刻已经没有在关的（没有管线、相机本来就关着）：当场叫，当场放
        ChannelScheduler.whenClosedEverywhere(camerasClosed);
    }

    /** 调度说都关完了：放锁。 */
    private static final Runnable camerasClosed = new Runnable() {
        @Override
        public void run() {
            if (closeWaitSinceMs != 0) {
                release(closeWaitWhy + ", cameras closed after "
                        + (SystemClock.elapsedRealtime() - closeWaitSinceMs) + "ms");
            }
        }
    };

    /** 等满了还没关完：照样放（锁不能一直拿着）。 */
    private static final Runnable closeWaitCap = new Runnable() {
        @Override
        public void run() {
            if (closeWaitSinceMs != 0) {
                release(closeWaitWhy + ", cameras still closing after " + CLOSE_WAIT_MAX_MS / 1000 + "s");
            }
        }
    };

    /**
     * 放开唤醒锁。亮屏、录像停了、不等自动接回了、到点（相机关完）都从这里走；没拿着时什么也不做。
     *
     * @param why 给黑匣子看的原因，用 ASCII（screen-on / recording-stopped / resume-dropped / timeout …）
     */
    public static void release(String why) {
        cancelTimeout();
        HANDLER.removeCallbacks(closeWaitCap);
        ChannelScheduler.cancelClosedWaiter(camerasClosed);
        closeWaitSinceMs = 0;
        if (!WakeUpHelper.isPersistentWakeLockHeld()) {
            return;
        }
        long heldSeconds = (SystemClock.elapsedRealtime() - heldSinceMs) / 1000;
        WakeUpHelper.releasePersistentWakeLock();
        BlackBox.noteImportant("熄屏录制：放开唤醒锁（" + why + "，拿了 " + heldSeconds + " 秒，上限 "
                + heldForMinutes + " 分钟" + BlackBox.afterScreenOff() + "）");
        AppLog.i(TAG, "wake lock released: " + why + " after " + heldSeconds + "s");
    }

    private static void cancelTimeout() {
        if (timeout != null) {
            HANDLER.removeCallbacks(timeout);
            timeout = null;
        }
    }

}
