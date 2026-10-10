package com.kooo.evcam.camera;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 相机服务眼里，每一路相机此刻空不空 —— 和「我们自己开没开」对着看。
 *
 * <h3>为什么要这个</h3>
 *
 * <p>那种「环视打不开、座舱正常、重启应用和重装都没用、只有重启车机才好」的状态，
 * 到现在一次都没被日志抓到过：它发生的时候我们只知道「打不开」，不知道<b>是谁占着</b>。
 * 系统其实会告诉每个应用每一路相机什么时候被占用、什么时候空出来
 * （{@link CameraManager.AvailabilityCallback}），只是一直没人听。</p>
 *
 * <p>判据很直接：<b>相机服务说环视被占用，而我们自己没开它</b> —— 那就是别人拿着，
 * 或者是相机服务里一条没清掉的残留占用。后一种正是重启车机才能解开的那一类。
 * 注册的那一刻系统会把每一路当前的状态报一遍，所以卡死之后只要应用还能起来，
 * 黑匣子第一屏就能看到答案。</p>
 *
 * <p>2026-10-10 起看门狗也按这张表重开被别的程序拿走的那一路（{@link CameraTaken#gate}）：读的是此刻的状态
 * （这一路空闲了多久、所有相机多久没变过），不是一声声的事件 —— 相机服务会乱报，事件也会被当成重复丢掉。</p>
 */
public final class CameraAvailabilityWatch {

    private static final String TAG = "CameraAvailability";

    /** 相机服务最近一次报的状态：true=空闲。 */
    private static final Map<String, Boolean> AVAILABLE = new ConcurrentHashMap<>();
    /** 那个状态从什么时候开始（开机起算，含深睡）。 */
    private static final Map<String, Long> SINCE = new ConcurrentHashMap<>();
    /** 那一刻是不是我们自己开着（或正在开）。 */
    private static final Map<String, Boolean> OURS = new ConcurrentHashMap<>();
    /**
     * 最近一次哪一路的状态变了（开机起算，含深睡）；0 = 还没收到过。哪一路都算，我们开着的那几路被乱报的也算 ——
     * 看门狗的闸门要「所有相机这么久没变过」才重开被拿走的那一路（{@link CameraTaken#gate}，2026-10-10）。
     */
    private static volatile long lastChangeAt;

    private static CameraManager.AvailabilityCallback callback;

    private CameraAvailabilityWatch() {
    }

    /** 开始听。重复调用无害。 */
    public static synchronized void start(Context context) {
        if (callback != null || context == null) {
            return;
        }
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            return;
        }
        // 嫌疑应用要查使用情况，得有个 Context；争用都从这里来，在这里接上
        CameraHolderSuspects.attach(context);
        callback = new CameraManager.AvailabilityCallback() {
            @Override
            public void onCameraAccessPrioritiesChanged() {
                // 前后台切换时相机服务重排优先级（API 29+）：记一行；别的程序占着相机的话趁机试一次
                CameraContention.prioritiesChanged();
                CameraTaken.prioritiesChanged();
            }


            @Override
            public void onCameraAvailable(@NonNull String cameraId) {
                changed(cameraId, true);
            }

            @Override
            public void onCameraUnavailable(@NonNull String cameraId) {
                changed(cameraId, false);
            }
        };
        try {
            cm.registerAvailabilityCallback(callback, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            // 容器里这条 binder 走不通的话，报告里会显示「没收到过」，不影响别的
            AppLog.w(TAG, "registerAvailabilityCallback failed: " + e);
            callback = null;
        }
    }

    private static void changed(String cameraId, boolean available) {
        Boolean before = AVAILABLE.put(cameraId, available);
        if (before != null && before == available) {
            return;
        }
        boolean ours = weHoldOrOpen(cameraId);
        // 设备还在我们手上的那一路报「空闲」不算谁放开：那是相机服务交接时乱报（2026-10-10，车机拿走相机 1 的同一毫秒，
        // 报了我们正开着、正出画面的 0 和 2「空闲」）。我们自己关的那一次关完前报的「空闲」不在此列，那是真空出来了
        boolean spurious = available && weHoldDevice(cameraId);
        long now = SystemClock.elapsedRealtime();
        SINCE.put(cameraId, now);
        OURS.put(cameraId, ours);
        lastChangeAt = now;
        // 别的程序拿走了相机：争用日志记一行；重开的节奏和接回由 CameraTaken 管
        if (available) {
            if (!spurious) {
                CameraContention.othersReleased(cameraId);
                CameraTaken.othersReleased(cameraId);
            }
        } else if (!ours) {
            CameraContention.othersTook(cameraId, before == null);
            CameraTaken.othersTook(cameraId);
        }
        if (available) {
            BlackBox.noteImportant("相机服务: " + cameraId + " 空闲" + (before == null ? "（初始状态）" : "")
                    + (spurious ? "（可我们开着它：相机服务乱报，不算放开）" : ""));
        } else {
            BlackBox.noteImportant("相机服务: " + cameraId + " 被占用"
                    + (ours ? "（我们开着）" : "（不是我们：别的程序，或相机服务里没清掉的占用）")
                    + (before == null ? "（初始状态）" : ""));
        }
    }

    /**
     * 这一路此刻在相机服务那边是不是我们的：开着、正在开、正在照常关。被断开、报错 1 / 2 之后的那一次关不算
     * （{@link SingleCamera#holdsInService()}）—— 相机服务那时已经把我们踢了，它报的「被占用」是新主人。
     */
    static boolean weHoldOrOpen(String cameraId) {
        return anyOfOurs(cameraId, SingleCamera::holdsInService);
    }

    /** 这一路的设备此刻在我们手上（开成了、还没关）。 */
    private static boolean weHoldDevice(String cameraId) {
        return anyOfOurs(cameraId, SingleCamera::isCameraOpened);
    }

    /** 我们管着这一路的那份相机对象里，有没有一份此刻满足 {@code test}。 */
    private static boolean anyOfOurs(String cameraId, Predicate<SingleCamera> test) {
        try {
            MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
            if (manager == null) {
                return false;
            }
            for (String key : new String[]{CameraSlots.KEY_SURROUND, CameraSlots.KEY_CABIN_FRONT,
                    CameraSlots.KEY_CABIN_REAR, CameraSlots.KEY_FOURTH}) {
                SingleCamera camera = manager.getCamera(key);
                if (camera != null && cameraId.equals(camera.getCameraId()) && test.test(camera)) {
                    return true;
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "anyOfOurs: " + e);
        }
        return false;
    }

    /**
     * 丢了这一路之后，相机服务的表对它怎么说：true 空闲、false 被别的程序占着、null 说不准
     * （规则见 {@link CameraTaken#serviceSays}）。
     *
     * @param lostAt 丢的那一刻（开机起算，含深睡）
     */
    static Boolean statusAfterLoss(String cameraId, long lostAt) {
        Long since = SINCE.get(cameraId);
        return CameraTaken.serviceSays(AVAILABLE.get(cameraId), Boolean.TRUE.equals(OURS.get(cameraId)),
                since == null ? 0 : since, lostAt);
    }

    /** 相机服务说这一路空闲了多久（毫秒）；被占用、没收到过都是 -1。 */
    static long availableForMs(String cameraId) {
        Boolean available = AVAILABLE.get(cameraId);
        Long since = SINCE.get(cameraId);
        if (available == null || !available || since == null) {
            return -1;
        }
        return SystemClock.elapsedRealtime() - since;
    }

    /** 所有相机的空闲 / 占用多久没变过（毫秒）；一次都没收到过是 {@link Long#MAX_VALUE}。 */
    static long quietForMs() {
        long at = lastChangeAt;
        return at == 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - at;
    }

    /**
     * 这一路判定被别的程序拿走了（{@link CameraTaken#judgeLoss}）：表上改成「被占用、不是我们、从现在起」。
     *
     * <p>相机服务那一声「被占用」到的时候设备要是还算我们的（比断开到得早），就被记成了「我们开着」——
     * 2026-10-10 黑匣子里的「1 被占用（我们开着）」就是这样（那时还是先关设备、关完才摘）。改过来之后诊断报告
     * 说的是实情，下一声真的「空闲」也照常算放开。已经是「被占用、不是我们」的不动，原来的时刻更准。
     * 不算一次变化：这是我们改账，不是相机服务又报了一声。</p>
     */
    static void markTaken(String cameraId) {
        if (Boolean.FALSE.equals(AVAILABLE.get(cameraId)) && Boolean.FALSE.equals(OURS.get(cameraId))) {
            return;
        }
        AVAILABLE.put(cameraId, false);
        OURS.put(cameraId, false);
        SINCE.put(cameraId, SystemClock.elapsedRealtime());
    }

    /** 有没有收到过回调。没有的话，多半是容器没把这条接口转过来。 */
    public static boolean heardAnything() {
        return !AVAILABLE.isEmpty();
    }

    /** 每一路：{空闲?, 持续多少毫秒, 当时是不是我们}，按相机编号排好。 */
    public static Map<String, long[]> snapshot() {
        Map<String, long[]> out = new TreeMap<>();
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<String, Boolean> e : AVAILABLE.entrySet()) {
            Long since = SINCE.get(e.getKey());
            Boolean ours = OURS.get(e.getKey());
            out.put(e.getKey(), new long[]{
                    e.getValue() ? 1 : 0,
                    since == null ? -1 : now - since,
                    ours != null && ours ? 1 : 0,
            });
        }
        return out;
    }
}
