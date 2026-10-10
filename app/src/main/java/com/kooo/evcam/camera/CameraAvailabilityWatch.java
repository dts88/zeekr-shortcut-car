package com.kooo.evcam.camera;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 *
 * <h3>2.11 起：每一声分我们引起的、别人引起的；问一次补漏收的</h3>
 *
 * <p>通道开、改输出、救之前要「安静」：相机服务 {@link CameraTaken#QUIET_MS} 内没报过<b>别人引起的</b>变化
 * （通道的规矩，项目所有者 2026-10-10 确认）。我们自己开、关一路时它报的那几声不算，不然按次序开的每一路
 * 都要多等 3 秒；我们稳稳开着时它报的「空闲」算别人的 —— 那正是交接时的乱报（10-10 07:22，车机拿走相机 1 的
 * 同一毫秒报了我们开着的 0 和 2「空闲」）。怎么分见 {@link #classify}。</p>
 *
 * <p>被拿着的那一路每 30 秒问一次：注册一个新的可用性回调、收它的回放 —— 进程里的缓存，不进相机服务、不开相机
 * （项目所有者 2026-10-10 确认：只问，不开）。回放和我们的记录对不上的，照回放改；回放里没有的槽位记为「不在」
 * （10-10 车机拿着后座舱时，相机 1 就这样从缓存里消失了）。怎么并见 {@link #merge}。</p>
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

    // ================================================================= 2.11 的纯部分：分类、并快照

    /** 相机服务的一声算谁引起的（{@link #classify}）。只有 {@link #OTHERS} 记进「上一次别人引起的变化」。 */
    enum Cause {
        /** 注册那一刻的回放（相机服务把每一路的现状报一遍）：只记下，不算变化。 */
        INITIAL,
        /** 和我们记的一样（同一声又到了一次，或者问的时候已经照它改过）：不算变化。 */
        SAME,
        /** 我们在这一路有开或关在途：是我们自己引起的，不挡「安静」。 */
        OURS,
        /** 别人引起的，包括我们稳稳开着时它报的「空闲」：挡「安静」{@link CameraTaken#QUIET_MS}。 */
        OTHERS
    }

    /**
     * 相机服务报了一声（{@code onCameraAvailable} / {@code onCameraUnavailable}）：算谁引起的。
     *
     * <ol>
     *   <li>注册那一刻的回放 → {@link Cause#INITIAL}：那是现状，不是变化（冷启动时车机还拿着相机 1，回放里它是
     *       「被占用」、或者干脆没有它 —— 记下来，调度据此直接判被拿着，但不因此等 3 秒）；</li>
     *   <li>和我们记的一样 → {@link Cause#SAME}；</li>
     *   <li>我们在这一路有开或关在途 → {@link Cause#OURS}；</li>
     *   <li>其余 → {@link Cause#OTHERS}：别的程序拿、放，或者我们稳稳开着时它乱报的「空闲」。</li>
     * </ol>
     *
     * @param replay          这一声是注册那一刻的回放（注册之后、收尾任务跑之前到的）
     * @param before          我们记的这一路的最后一句：true 空闲、false 被占用；null = 从没收到过，或者记的是「不在」
     *                        （这时不是回放的那一声就是它回来了，算变化）
     * @param available       这一声说的：true 空闲
     * @param ourStepInFlight 我们在这一路有开或关在途（在开、在关；丢了之后的那一次关也算 —— 那几秒这一路本来就在途，
     *                        通道什么都不动）。只是改会话不算：改会话不会让相机服务改口
     */
    static Cause classify(boolean replay, Boolean before, boolean available, boolean ourStepInFlight) {
        if (replay) {
            return Cause.INITIAL;
        }
        if (before != null && before == available) {
            return Cause.SAME;
        }
        return ourStepInFlight ? Cause.OURS : Cause.OTHERS;
    }

    /** 问来的快照对一路的说法和我们的记录不一样（{@link #merge}）。 */
    static final class Correction {
        final String cameraId;
        /** 我们记的最后一句：true 空闲、false 被占用；null = 从没收到过，或者记的是「不在」（看 {@link #wasAbsent}）。 */
        final Boolean before;
        /** 我们记的是「不在」。 */
        final boolean wasAbsent;
        /** 快照说的：true 空闲、false 被占用；null = 快照里没回放它，记为「不在」。 */
        final Boolean after;

        Correction(String cameraId, Boolean before, boolean wasAbsent, Boolean after) {
            this.cameraId = cameraId;
            this.before = before;
            this.wasAbsent = wasAbsent;
            this.after = after;
        }

        /** 快照里没有它：记为「不在」。 */
        boolean nowAbsent() {
            return after == null;
        }

        /**
         * 算不算一次别人引起的变化：我们原来有记录（一句话，或者「不在」）而快照说的不一样 —— 漏收了一声。
         * 从没记过的只是补上（注册那一刻的回放还没并进来的那种），不是相机服务那边变了，不挡「安静」。
         */
        boolean changed() {
            return before != null || wasAbsent;
        }

        /** 日志用：{@code 1: busy -> free}、{@code 1: free -> absent}、{@code 1: ? -> busy}。 */
        @Override
        public String toString() {
            return cameraId + ": " + word(before, wasAbsent) + " -> " + word(after, after == null);
        }

        private static String word(Boolean available, boolean absent) {
            if (absent) {
                return "absent";
            }
            return available == null ? "?" : available ? "free" : "busy";
        }
    }

    /**
     * 用问来的快照修正我们的记录（{@code ask()}、以及注册那一刻的回放收全之后）：返回要改的那几路，按相机编号排好；
     * 一致就是空的，什么都不动。
     *
     * <ul>
     *   <li>我们漏收的（记的和快照说的不一样）→ 照快照改；</li>
     *   <li>快照里没回放的 —— 我们的槽位、或者我们记过的 —— 记为「不在」：相机服务的缓存里没有它，
     *       不会回放（AOSP 12：NOT_PRESENT 的不报）。10-10 车机拿着后座舱时，相机 1 就是这样；</li>
     *   <li>本来就记的「不在」、快照里还是没有 → 不动。</li>
     * </ul>
     *
     * <p>快照不会比相机服务自己的通知新：它补的只是我们漏收的那几声。</p>
     *
     * @param recorded 我们记的每一路的最后一句（true 空闲）；记为「不在」的那几路在 {@code absent} 里，这里有没有都不看
     * @param absent   我们记为「不在」的那几路
     * @param replay   这一次回放里收到的每一路（true 空闲）
     * @param slots    我们的几路用的相机编号：从没收到过的也要看（null 跳过）
     */
    static List<Correction> merge(Map<String, Boolean> recorded, Set<String> absent, Map<String, Boolean> replay,
                                  Collection<String> slots) {
        Set<String> ids = new TreeSet<>();
        ids.addAll(recorded.keySet());
        ids.addAll(absent);
        ids.addAll(replay.keySet());
        for (String id : slots) {
            if (id != null) {
                ids.add(id);
            }
        }
        List<Correction> out = new ArrayList<>();
        for (String id : ids) {
            boolean wasAbsent = absent.contains(id);
            Boolean before = wasAbsent ? null : recorded.get(id);
            Boolean after = replay.get(id);
            if (after == null) {
                if (!wasAbsent) {
                    out.add(new Correction(id, before, false, null));
                }
            } else if (wasAbsent || !after.equals(before)) {
                out.add(new Correction(id, before, wasAbsent, after));
            }
        }
        return out;
    }
}
