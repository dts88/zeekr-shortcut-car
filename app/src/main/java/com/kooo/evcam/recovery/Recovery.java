package com.kooo.evcam.recovery;

import android.content.Context;
import android.content.Intent;
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
 * 录像要经过主界面这个对象（1.6）：静默把主界面拉起来，它闪一下就退后台，录像照常。屏幕黑着时不为录像拉（2026-10-10）。</p>
 */
public final class Recovery {

    private static final String TAG = "Recovery";
    /** 前台服务每分钟都会被例行启动一次；这种例行调用五分钟做一次就够。 */
    private static final long ROUTINE_INTERVAL_MS = 5 * 60_000L;
    /** 前台服务例行启动时传的原因；别的原因（睡醒、亮屏、更新）每次都做。 */
    public static final String WHY_ROUTINE = "service-start";
    /** 拉主界面时带上：它不是人点开的，这一趟的录像选择不清（规格 1.2）。 */
    public static final String EXTRA_SILENT = "from_service_restart";

    private static long lastRoutineAtMs;
    private static String lastSummary = "";

    private Recovery() {
    }

    public static void restore(Context context, String why) {
        try {
            restoreNow(context, why);
        } catch (Throwable t) {
            AppLog.w(TAG, "restore failed (" + why + "): " + t);
        }
    }

    /**
     * 因熄屏自己退下去的主界面，屏幕亮了就接回来（规格 §0：停车熄屏是特殊情况，亮了就回到用户设定的状态）。
     *
     * <p>只认「是它自己因为熄屏退下去的」这个记号：用户自己切走的、从来没进过前台的，一律不动。
     * 界面还活着就把它挪到前面；已经被系统收走了就重新起一个（带记号，这一趟的录像选择不清，规格 1.2）。
     * ScreenState 在亮屏时调；进程在屏幕亮着时被拉起来也调一次（那时没有亮屏事件可听）。</p>
     */
    public static void bringBackUiAfterScreenOn(Context context) {
        try {
            AppConfig config = new AppConfig(context);
            if (!config.didUiLeaveForScreenOff() || UserExit.isExited(context)) {
                return;
            }
            config.setUiLeftForScreenOff(false);
            boolean alive = MainActivity.getInstance() != null;
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | (alive ? Intent.FLAG_ACTIVITY_SINGLE_TOP : 0));
            if (!alive) {
                intent.putExtra("restored_by_app", true);
            }
            context.startActivity(intent);
            BlackBox.noteImportant("亮屏：把因熄屏退下去的主界面接回前台（" + (alive ? "界面还在" : "重新起一个") + "）");
        } catch (Exception e) {
            AppLog.w(TAG, "bringBackUiAfterScreenOn failed: " + e);
        }
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
        // 录像只在屏幕亮着时接（2026-10-10）：哨兵模式没开时车机熄屏几秒就断电，黑着拉起来的主界面一开就是三路相机。
        // 被结束之前在录、在等接回、或因熄屏停下等亮屏接的（「这一趟要录」落着盘）：和亮屏同一个接回入口，
        // 不看「启动自动录制」—— 以前只看它，而它关着、熄屏持续录制开着（默认）时「唤醒后恢复」在进程被结束后就落空了。
        // 开录要经过主界面建的相机管线（1.6），主界面不在就静默拉起来
        if (!dark) {
            coordinator.resumeIfWanted(why);
        }
        boolean wantRecording = !dark && !recording && (intent.recordingWanted()
                || config.isAutoStartRecording() && intent.shouldAutoStart(true));
        if (wantRecording && !mainScreenAlive) {
            Intent main = new Intent(context, MainActivity.class);
            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            main.putExtra("auto_start_from_boot", true);
            main.putExtra(EXTRA_SILENT, true);
            context.startActivity(main);
            did.append(did.length() > 0 ? "; " : "").append("main-screen-for-recording");
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
