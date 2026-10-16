package com.kooo.evcam.recovery;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.recording.RecordingCoordinator;
import com.kooo.evcam.recording.RecordingIntent;

/**
 * 开机自启动（规格 §1）的统一入口：收到各类信号、判断自己能正常运行了，就把核心程序起来。
 *
 * <p>核心程序 = 用户设定状态里开着的那些：悬浮按钮、超级后视镜、录像（自动录制；被结束之前在录、等着接回的那一次）。
 * 谁来调：前台服务起来时、保活任务跑到时（睡醒后一秒内必跑）、亮屏广播来时、应用更新后。
 * 幂等，多调无害：已经在的不重复起。</p>
 *
 * <p>只有「开机自启动」开着才做（规格 1.3：它管的是 App 不在时要不要带回来）；
 * 用户退出了不做（1.4）。主界面还在的时候，它自己会回到设定状态（§0），这里只管它不在的情况。
 * 相机管线属于应用（2.11 阶段 6），接录像不必经过主界面；但现在用不了相机（前台服务是后台起的）时，录像协调器打开录像意图那一下
 * 先静默把主界面拉起来，它闪一下就退后台，录像照样当场开、等它起来再开相机（{@link #bringUpMainForRecording}）。「启动自动录制」的入口在主界面，
 * 主界面不在也静默拉起来（1.6）。</p>
 *
 * <p>屏幕黑着（熄屏 = 屏幕真黑了，熄屏 v3）时不拉界面；录像接不接交给录像协调器照熄屏那张表判（{@link RecordingCoordinator#resumeIfWanted}：
 * 车机醒着、熄屏持续录制开着、计划里有环视才在黑着时接，只录环视），这里不再一律只记一笔（设计 v3 §3.2）。
 * 屏幕此刻不知道黑没黑（进程刚起来、安卓刚亮在等车辆信号）时排到知道了再做（{@code ScreenState.whenKnown}，C11）。
 * 谁来调都先排到主线程上（C8）：保活任务在 WorkManager 的线程上调，而协调器只在主线程上动。</p>
 */
public final class Recovery {

    private static final String TAG = "Recovery";
    /** 前台服务每分钟都会被例行启动一次；这种例行调用五分钟做一次就够。 */
    private static final long ROUTINE_INTERVAL_MS = 5 * 60_000L;
    /** 前台服务例行启动时传的原因；别的原因（睡醒、亮屏、更新）每次都做。 */
    public static final String WHY_ROUTINE = "service-start";
    /** 拉主界面时带上：它不是人点开的，这一趟的录像选择不清（规格 1.2）。 */
    public static final String EXTRA_SILENT = "from_service_restart";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastRoutineAtMs;
    private static String lastSummary = "";

    private Recovery() {
    }

    /**
     * 按开机自启动的规矩恢复（任何线程都能调）：排到主线程上，屏幕知道黑没黑了再做（{@link #restoreNow}）。
     *
     * @param why 谁叫的（ASCII，进黑匣子）
     */
    public static void restore(Context context, String why) {
        MAIN.post(() -> com.kooo.evcam.screen.ScreenState.whenKnown(() -> {
            try {
                restoreNow(context, why);
            } catch (Throwable t) {
                AppLog.w(TAG, "restore failed (" + why + "): " + t);
            }
        }));
    }

    /**
     * 因熄屏自己退下去的主界面，屏幕亮了就接回来（规格 §0：停车熄屏是特殊情况，亮了就回到用户设定的状态）。
     *
     * <p>只认「是它自己因为熄屏退下去的」这个记号：用户自己切走的、从来没进过前台的，一律不动。
     * 界面还活着就把它挪到前面；已经被系统收走了就重新起一个（带记号，这一趟的录像选择不清，规格 1.2）。
     * ScreenState 在亮屏时调；进程在屏幕亮着时被拉起来也调一次（那时没有亮屏事件可听）；为录像拉主界面时记号还在的也走这里
     * （{@link #bringUpMainForRecording}）。都在屏幕亮着时。熄屏 v3 起「熄屏」是屏幕真黑了：
     * 车主下车、屏幕一黑记号就记上，开车门亮屏时主界面就接回来（以前要等几分钟后车机下电那一次熄屏才记）。</p>
     */
    public static void bringBackUiAfterScreenOn(Context context) {
        try {
            AppConfig config = new AppConfig(context);
            if (!config.didUiLeaveForScreenOff() || UserExit.isExited(context) || factoryCameraActive()) {
                return;
            }
            config.setUiLeftForScreenOff(false);
            if (com.kooo.evcam.camera.StallWatch.isForeground()) {
                // 记号是熄屏那一刻记的（主界面在前就记，进程被车机结束了也接得回来）；熄屏期间它一直没退下去（在录、在等开录、
                // 熄屏录制开着）就还在前面，不用再拉
                return;
            }
            boolean alive = MainActivity.getInstance() != null;
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | (alive ? Intent.FLAG_ACTIVITY_SINGLE_TOP : 0));
            if (!alive) {
                intent.putExtra("restored_by_app", true);
            }
            context.startActivity(intent);
            // 主界面这就起来了：这一阵不再为接录像另外静默拉一次（那样它起来闪一下又退回后台）
            broughtUpAtMs = SystemClock.elapsedRealtime();
            BlackBox.noteImportant("亮屏：把因熄屏退下去的主界面接回前台（" + (alive ? "界面还在" : "重新起一个") + "）");
        } catch (Exception e) {
            AppLog.w(TAG, "bringBackUiAfterScreenOn failed: " + e);
        }
    }

    public static boolean factoryCameraActive() {
        com.kooo.evcam.telemetry.Readings r = com.kooo.evcam.telemetry.Telemetry.get().readings();
        return RecoveryUiPolicy.factoryCameraActive(r.text(com.kooo.evcam.telemetry.Signal.GEAR),
                r.bool(com.kooo.evcam.telemetry.Signal.STOCK_360),
                r.bool(com.kooo.evcam.telemetry.Signal.STOCK_POPUP),
                r.bool(com.kooo.evcam.telemetry.Signal.PARK_ASSIST));
    }

    /**
     * 上一次为接录像把主界面拉起来（或者亮屏把因熄屏退下去的主界面接回来）的时刻（elapsedRealtime）：
     * 亮屏那一下协调器、恢复各叫一次，拉一次就够。
     */
    private static long broughtUpAtMs;
    /** 这么久之内再要拉，当作还在拉（主界面起来、登记预览要一两秒）。 */
    private static final long BRING_UP_DEDUPE_MS = 5_000L;

    /**
     * 录像意图开了、而现在用不了相机（主界面不在前，前台服务是后台起的 —— 安卓 12 起后台才起的前台服务用不了相机，
     * lifecycle-spec §3）：把主界面静默拉起来（大纲 §2 阶段 6）。录像照样当场开，先「等待摄像头」；主界面一起来、
     * 刷新了前台服务（从此能用相机）、登记了预览，调度开的时候带着预览和录像输出。不拉的话录像输出声明了、
     * 相机却开不起来，没人打开主界面就永远开不起来。
     *
     * <p>静默：带着不是人点开的记号（这一趟的录像选择不清，规格 1.2），起来闪一下就退后台（1.6）。用户退出了不拉；
     * 5 秒内拉过（亮屏那一下接回界面、接回录像各来一次）不再拉。只由 RecordingCoordinator 调，屏幕亮着时
     * （{@code RecordingCoordinator.bringUpMainIfCamerasUnusable}：打开录像意图那一下 —— 人按的、悬浮按钮、启动自动录制、
     * 亮屏接回、存储一类的自动接回都经那里，审查 2026-10-11 —— 和亮屏那一下）。屏幕黑着时它不调（项目所有者 2026-10-13：
     * 熄屏不拉主界面，没有例外）。</p>
     *
     * @param where 谁要录的（ASCII，进黑匣子：RecordingCoordinator.Why）
     */
    public static void bringUpMainForRecording(Context context, String where) {
        if (UserExit.isExited(context) || factoryCameraActive()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (broughtUpAtMs != 0 && now - broughtUpAtMs < BRING_UP_DEDUPE_MS) {
            return;
        }
        broughtUpAtMs = now;
        try {
            if (new AppConfig(context).didUiLeaveForScreenOff()) {
                // 熄屏那一刻主界面就在前（因熄屏退下去的记号还在）：照亮屏接回界面那样接回来、留在前面 ——
                // 静默拉的话它起来闪一下又退回后台，人上车看到的是车机桌面
                bringBackUiAfterScreenOn(context);
            } else {
                launchMainSilently(context);
            }
        } catch (RuntimeException e) {
            AppLog.w(TAG, "bringUpMainForRecording failed: " + e);
            return;
        }
        BlackBox.noteImportant("要录像（" + where + "）：现在用不了相机（主界面不在前、前台服务是后台起的），"
                + "先拉起主界面，录像等它起来再开相机");
    }

    /** 静默拉起主界面：不是人点开的（这一趟的录像选择不清），起来闪一下就退后台。还活着就挪到前面。 */
    private static void launchMainSilently(Context context) {
        if (factoryCameraActive()) return;
        boolean alive = MainActivity.getInstance() != null;
        Intent main = new Intent(context, MainActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | (alive ? Intent.FLAG_ACTIVITY_SINGLE_TOP : 0));
        main.putExtra("auto_start_from_boot", true);
        main.putExtra(EXTRA_SILENT, true);
        context.startActivity(main);
    }

    private static void restoreNow(Context context, String why) {
        if (UserExit.isExited(context)) {
            return;
        }
        boolean dark = com.kooo.evcam.screen.ScreenState.dark();
        // 进程是在屏幕亮着时被拉起来的：亮屏事件早过了，该接回的界面在这里接
        if (!dark) {
            bringBackUiAfterScreenOn(context);
        }
        AppConfig config = new AppConfig(context);
        if (!config.isAutoStartOnBoot()) {
            BlackBox.count("restore-skipped-switch-off");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (WHY_ROUTINE.equals(why)) {
            if (now - lastRoutineAtMs < ROUTINE_INTERVAL_MS) {
                return;
            }
            lastRoutineAtMs = now;
        }

        StringBuilder did = new StringBuilder();
        if (OverlayCoordinator.canShowOverlay(context)) {
            // 按设置起悬浮按钮和后视镜；已经在的不会重复。后视镜自己知道屏幕黑着就不接相机
            OverlayCoordinator.restoreOnLaunch(context);
            did.append("overlays");
        }

        RecordingCoordinator coordinator = RecordingCoordinator.get(context);
        boolean recording = coordinator.isRecording();
        boolean mainScreenAlive = MainActivity.getInstance() != null;
        RecordingIntent intent = RecordingIntent.current();
        boolean wanted = !recording && intent.recordingWanted();
        boolean autoStart = !recording && config.isAutoStartRecording() && intent.shouldAutoStart(true);
        // 被结束之前在录、在等接回、或因熄屏停下等亮屏接的（「这一趟要录」落着盘）：和亮屏同一个接回入口，
        // 不看「启动自动录制」—— 以前只看它，而它关着、熄屏持续录制开着（默认）时「唤醒后恢复」在进程被结束后就落空了。
        // 现在用不了相机（前台服务是后台起的）时协调器打开录像意图那一下把主界面拉起来（bringUpMainForRecording），
        // 录像照样当场开、等它起来再开相机；能用相机就直接接 —— 管线属于应用，开录不用再经过主界面（大纲 §2 阶段 6）。
        // 屏幕黑着时接不接也由协调器照熄屏那张表判：车机醒着、熄屏持续录制开着、计划里有环视才接（只录环视）；
        // 别的只记下「这一趟要录」，亮屏再接（设计 v3 §3.2，以前黑着一律只记一笔）。黑着不拉主界面：用不了相机就等亮屏
        coordinator.resumeIfWanted(why);
        if (dark) {
            // 黑着不拉界面：「启动自动录制」的入口在主界面，等亮屏
            if ((wanted || autoStart) && !coordinator.isRecording() && !coordinator.isWaiting()) {
                did.append(did.length() > 0 ? "; " : "").append("recording-waits-for-screen-on");
            }
        } else {
            // 「启动自动录制」的入口在主界面（这一趟开没开过、停没停过都在那里记）：主界面不在就静默拉起来
            if (autoStart && !wanted && !mainScreenAlive) {
                launchMainSilently(context);
                did.append(did.length() > 0 ? "; " : "").append("main-screen-for-recording");
            }
        }

        String summary = did.length() == 0 ? "nothing" : did.toString();
        // 每次都记的话，例行那一条五分钟一行；只有做的事变了、或者拉了主界面，才值一行
        if (!summary.equals(lastSummary) || summary.contains("main-screen")) {
            lastSummary = summary;
            BlackBox.noteImportant("恢复（" + why + "）：" + summary + "；录像中=" + recording
                    + " 主界面在=" + mainScreenAlive + " " + intent.describe());
        }
    }
}
