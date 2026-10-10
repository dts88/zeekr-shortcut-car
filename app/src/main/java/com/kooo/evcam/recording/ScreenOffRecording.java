package com.kooo.evcam.recording;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.blackbox.BlackBox;

/**
 * 熄屏录制（开发者选项，规格 §3.1）：熄屏时正在录像，就拿住唤醒锁不让车机睡。
 *
 * <p>熄屏录制 = 熄屏持续录制 + 防止休眠。车机熄屏六秒就深睡，睡着的进程一行代码都不跑，
 * 要在会睡的车上接着录，只有不让它睡这一个手段（平台笔记 §3.6）。项目所有者 2026-09-27 明确允许。
 * 原来「常驻唤醒锁」那个开关的能力全部归到这里，再往上做：</p>
 *
 * <ul>
 *   <li><b>只在录像期间拿</b>，录像停了就放；</li>
 *   <li>最长拿多久由用户设，<b>从熄屏那一刻起算</b>（App 拿不到「下车」这个事件，熄屏是最接近的近似）；
 *       到点不当场放：照熄屏那张表再判一次（{@link RecordingCoordinator#lockTimedOut}，锁不算拉着车机了）——
 *       哨兵模式开着、车在走，接着录，锁现在放；车机要睡了，现在停录、相机按次序关完再放（2026-10-10，
 *       以前到点就放，车机带着开着的相机睡过去），醒来接着录（同 §2.4）；</li>
 *   <li>屏幕已经黑着时才开始的录像（熄屏期间接回的那种）同样拿，剩余时长从熄屏那一刻算；</li>
 *   <li>活在进程上，不靠主界面：熄屏、亮屏由 {@code ScreenState}（进程里唯一的屏幕状态源）告诉这里，
 *       主界面在不在都一样。</li>
 * </ul>
 */
public final class ScreenOffRecording {

    private static final String TAG = "ScreenOffRecording";
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    /** 到点停录之后等相机关完再放锁：多久看一次、最多等多久（锁一放，车机几秒就睡）。 */
    private static final long CLOSE_POLL_MS = 100L;
    private static final long CLOSE_WAIT_MAX_MS = 5_000L;

    private static Runnable timeout;
    /** 屏幕什么时候黑的（开机起算，含深睡）；0 = 亮着或不知道。 */
    private static long screenOffAtMs;
    private static long heldSinceMs;
    private static int heldForMinutes;
    /** 到点停录之后，从什么时候起等相机关完再放锁（开机起算，含深睡）；0 = 没在等。 */
    private static long closeWaitSinceMs;

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
     * 录像停了：放。到点之后判了「现在停」的那一次除外 —— 锁留到相机关完（{@link #holdUntilCamerasClosed}）。
     */
    public static void onRecordingStopped() {
        if (closeWaitSinceMs != 0) {
            return;
        }
        release("recording-stopped");
    }

    /** 亮屏了：放。 */
    public static void onScreenOn() {
        screenOffAtMs = 0;
        release("screen-on");
    }

    /**
     * 熄屏录制此刻拉不拉得住车机：开关开着、在录、从熄屏起还没到用户设的时长 —— 和拿锁的条件同一个（{@link #ensure}），
     * 屏幕黑着时它成立就是锁拿着。熄屏那一刻、到点那一刻怎么做（{@link ScreenOffPlan#atScreenOff}）看的是它，不是开关本身：
     * 到点之后开关还开着，锁已经不拉着车机了；没在录（含只在等接回）时不拿锁，车机照样会睡。
     * 屏幕亮着时问的是「现在熄屏的话拿不拿」（录制键上的小字）。
     */
    public static boolean holdsCarAwake(Context context) {
        AppConfig config = new AppConfig(context);
        return config.isScreenOffRecordingEnabled() && RecordingCoordinator.get(context).isRecording()
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
        // 「在录」问协调器：开录指令一发出去就算（以前问相机层，屏幕黑着时开始的录像在开录中这一步拿不到锁）
        if (!config.isScreenOffRecordingEnabled() || !RecordingCoordinator.get(context).isRecording()) {
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
            BlackBox.noteImportant("熄屏录制：在录像，拿住唤醒锁不让车机睡，还能拿 " + remaining / 60000
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
     * 到点之后判了「现在停、相机按次序关」：锁留着，等相机都关好（或等满 {@link #CLOSE_WAIT_MAX_MS}）再放 ——
     * 先放的话车机几秒就睡，环视、座舱还没关完（2026-10-10）。停录那一步的放锁这时不放（{@link #onRecordingStopped}）；
     * 亮屏照样当场放。协调器在停录之前调。
     */
    static void holdUntilCamerasClosed() {
        if (!WakeUpHelper.isPersistentWakeLockHeld()) {
            return;
        }
        closeWaitSinceMs = SystemClock.elapsedRealtime();
        HANDLER.removeCallbacks(closeWait);
        HANDLER.postDelayed(closeWait, CLOSE_POLL_MS);
    }

    private static final Runnable closeWait = new Runnable() {
        @Override
        public void run() {
            if (closeWaitSinceMs == 0) {
                return;
            }
            com.kooo.evcam.camera.MultiCameraManager manager =
                    com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
            boolean closed = manager == null || manager.isReleased() || manager.allCamerasClosed();
            if (!closed && SystemClock.elapsedRealtime() - closeWaitSinceMs < CLOSE_WAIT_MAX_MS) {
                HANDLER.postDelayed(this, CLOSE_POLL_MS);
                return;
            }
            release(closed ? "timeout, cameras closed" : "timeout, cameras still open after "
                    + CLOSE_WAIT_MAX_MS / 1000 + "s");
        }
    };

    /**
     * 放开唤醒锁。亮屏、录像停了、到点（相机关完）都从这里走；没拿着时什么也不做。
     *
     * @param why 给黑匣子看的原因，用 ASCII（screen-on / recording-stopped / timeout …）
     */
    public static void release(String why) {
        cancelTimeout();
        HANDLER.removeCallbacks(closeWait);
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
