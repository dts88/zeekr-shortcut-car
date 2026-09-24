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
 */
public final class CameraAvailabilityWatch {

    private static final String TAG = "CameraAvailability";

    /** 相机服务最近一次报的状态：true=空闲。 */
    private static final Map<String, Boolean> AVAILABLE = new ConcurrentHashMap<>();
    /** 那个状态从什么时候开始（开机起算，含深睡）。 */
    private static final Map<String, Long> SINCE = new ConcurrentHashMap<>();
    /** 那一刻是不是我们自己开着（或正在开）。 */
    private static final Map<String, Boolean> OURS = new ConcurrentHashMap<>();

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
        callback = new CameraManager.AvailabilityCallback() {
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
        SINCE.put(cameraId, SystemClock.elapsedRealtime());
        OURS.put(cameraId, ours);
        if (available) {
            BlackBox.noteImportant("相机服务: " + cameraId + " 空闲" + (before == null ? "（初始状态）" : ""));
        } else {
            BlackBox.noteImportant("相机服务: " + cameraId + " 被占用"
                    + (ours ? "（我们开着）" : "（不是我们：别的程序，或相机服务里没清掉的占用）")
                    + (before == null ? "（初始状态）" : ""));
        }
    }

    /** 这一路此刻是不是我们开着、或者正在开。 */
    static boolean weHoldOrOpen(String cameraId) {
        try {
            MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
            if (manager == null) {
                return false;
            }
            for (String key : new String[]{CameraSlots.KEY_SURROUND, CameraSlots.KEY_CABIN_FRONT,
                    CameraSlots.KEY_CABIN_REAR, CameraSlots.KEY_FOURTH}) {
                SingleCamera camera = manager.getCamera(key);
                if (camera != null && cameraId.equals(camera.getCameraId())
                        && camera.holdsOrIsOpening()) {
                    return true;
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "weHoldOrOpen: " + e);
        }
        return false;
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
