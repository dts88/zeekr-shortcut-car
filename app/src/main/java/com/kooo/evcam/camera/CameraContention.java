package com.kooo.evcam.camera;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;

import com.kooo.evcam.MainActivity;
import com.kooo.evcam.blackbox.BlackBox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 相机争用的调试日志：谁在什么时候拿了哪一路、我们哪一路紧跟着被断开、我们重开之后谁被顶掉。
 *
 * <p>项目所有者 2026-09-27：车机原生的后座画面逻辑上不该和环视冲突，先照常拿相机，
 * 用日志把过程记清楚。这里只记，不改任何行为。每一行都带上我们当时的处境：
 * 进程重要性（相机服务按它排优先级）、主界面在不在前台、我们开着哪几路。</p>
 *
 * <p>要回答的问题：车机的座舱画面拿相机 1 时，我们的相机 2 是不是紧跟着被断开（两路在相机服务里冲突）；
 * 我们重开相机 2 之后，相机 1 是不是马上空出来（我们把原厂画面顶掉了）；
 * 以及这两件事跟主界面在不在前台有没有关系。</p>
 *
 * <p>是谁拿的，相机服务不说。开发者选项开着时，别的程序拿、放的每一次另记一行嫌疑应用
 * （{@link CameraHolderSuspects}，几秒后才写）。</p>
 */
public final class CameraContention {

    /** 两件事隔多久以内算「紧跟着」。 */
    private static final long LINK_MS = 5_000L;

    private static final Map<String, Long> OTHERS_TOOK_AT = new ConcurrentHashMap<>();
    private static volatile String lastOthersId = "";
    private static volatile long lastOthersAt;
    private static volatile String lastOurOpenId = "";
    private static volatile long lastOurOpenAt;

    private CameraContention() {
    }

    /**
     * 相机服务报：别的程序拿了这一路。
     *
     * @param initial 注册回调时报的当前状态：什么时候拿的不知道，前后几秒的嫌疑应用对不上，不查
     */
    static void othersTook(String cameraId, boolean initial) {
        long now = SystemClock.elapsedRealtime();
        OTHERS_TOOK_AT.put(cameraId, now);
        lastOthersId = cameraId;
        lastOthersAt = now;
        BlackBox.noteImportant("争用：别的程序拿了相机 " + cameraId + "；我们此刻 " + describeUs());
        if (!initial) {
            CameraHolderSuspects.lookAround(cameraId, true);
        }
    }

    /**
     * 我们的一路丢了、关完了才认出是别的程序拿走的（{@link CameraTaken#judgeLoss}，2026-10-10）：
     * 按丢的那一刻记，嫌疑应用也按那一刻查 —— 那一次关在相机服务里能卡 5–8 秒。只有 {@link #othersTook}
     * 没记上的才走这里：相机服务那一声「被占用」到得比断开还早（那时设备还是我们的，被记成「我们开着」），
     * 或者根本没有那一声（被占用只是换了个主人，状态没变）。
     *
     * @param atElapsed 丢的那一刻（开机起算，含深睡）
     */
    static void othersTookAt(String cameraId, long atElapsed) {
        long ago = Math.max(0, SystemClock.elapsedRealtime() - atElapsed);
        OTHERS_TOOK_AT.put(cameraId, atElapsed);
        lastOthersId = cameraId;
        lastOthersAt = atElapsed;
        BlackBox.noteImportant("争用：别的程序拿了相机 " + cameraId + "（" + ago + " ms 前我们丢了它，关完才认出来）；我们此刻 "
                + describeUs());
        CameraHolderSuspects.lookAround(cameraId, true, System.currentTimeMillis() - ago);
    }

    /** 相机服务报：这一路空出来了。 */
    static void othersReleased(String cameraId) {
        Long took = OTHERS_TOOK_AT.remove(cameraId);
        if (took == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long sinceOurOpen = lastOurOpenAt == 0 ? -1 : now - lastOurOpenAt;
        BlackBox.noteImportant("争用：相机 " + cameraId + " 空出来了，别的程序拿了 " + (now - took) + " ms"
                + (sinceOurOpen >= 0 && sinceOurOpen < LINK_MS
                        ? "；距我们重开相机 " + lastOurOpenId + " 才 " + sinceOurOpen + " ms —— 多半是被我们顶掉的" : ""));
        CameraHolderSuspects.lookAround(cameraId, false);
    }

    /** 我们的一路被相机服务断开了（onDisconnected）。 */
    public static void ourCameraDisconnected(String cameraId) {
        long since = lastOthersAt == 0 ? -1 : SystemClock.elapsedRealtime() - lastOthersAt;
        BlackBox.noteImportant("争用：我们的相机 " + cameraId + " 被断开"
                + (since >= 0 && since < LINK_MS
                        ? "，距别的程序拿相机 " + lastOthersId + " 才 " + since + " ms —— 两路在相机服务里冲突" : "")
                + "；我们此刻 " + describeUs());
    }

    /** 我们打开（或重开）成功了。别的程序还占着相机时才值一行。 */
    public static void ourCameraOpened(String cameraId) {
        lastOurOpenId = cameraId;
        lastOurOpenAt = SystemClock.elapsedRealtime();
        if (!OTHERS_TOOK_AT.isEmpty()) {
            BlackBox.noteImportant("争用：别的程序占着相机 " + OTHERS_TOOK_AT.keySet() + "，我们开相机 " + cameraId
                    + " 成功；我们此刻 " + describeUs());
        }
    }

    /** 我们打开失败了。别的程序占着相机时才值一行。 */
    public static void ourOpenFailed(String cameraId, String error) {
        if (!OTHERS_TOOK_AT.isEmpty()) {
            BlackBox.noteImportant("争用：我们开相机 " + cameraId + " 失败：" + error + "；别的程序占着 "
                    + OTHERS_TOOK_AT.keySet() + "；我们此刻 " + describeUs());
        }
    }

    /** 相机服务说访问优先级变了（前后台切换那种）。 */
    static void prioritiesChanged() {
        BlackBox.noteImportant("相机服务：访问优先级变了；我们此刻 " + describeUs());
    }

    /** 我们此刻的处境，ASCII 一行：importance=… main=… open=[…]。 */
    public static String describeUs() {
        StringBuilder sb = new StringBuilder();
        try {
            ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(info);
            sb.append("importance=").append(info.importance);
        } catch (Throwable t) {
            sb.append("importance=?");
        }
        sb.append(" main=").append(MainActivity.getInstance() == null ? "none"
                : (StallWatch.isForeground() ? "foreground" : "background"));
        try {
            MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
            StringBuilder open = new StringBuilder();
            if (manager != null) {
                for (String key : new String[]{CameraSlots.KEY_SURROUND, CameraSlots.KEY_CABIN_FRONT,
                        CameraSlots.KEY_CABIN_REAR, CameraSlots.KEY_FOURTH}) {
                    SingleCamera camera = manager.getCamera(key);
                    if (camera != null && camera.holdsOrIsOpening()) {
                        open.append(open.length() > 0 ? "," : "").append(camera.getCameraId());
                    }
                }
            }
            sb.append(" open=[").append(open).append("]");
        } catch (Throwable t) {
            sb.append(" open=?");
        }
        return sb.toString();
    }
}
