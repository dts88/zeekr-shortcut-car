package com.kooo.evcam.camera;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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
 *
 * <h3>2.11 起：怎么收、记在哪、怎么交给调度</h3>
 *
 * <ul>
 *   <li><b>回放怎么收全</b>（{@link #start} 和 {@link #ask} 一样）：登记一个可用性回调时，相机服务进程内的缓存里每一路的现状
 *       都在登记那一刻 post 进我们给的 Handler（AOSP 12 {@code CameraManagerGlobal.updateCallbackLocked}：读进程里的缓存，
 *       不额外进相机服务，也不开任何相机）；登记之后紧接着在同一个 Handler 上再 post 一个收尾任务，它跑的时候回放都到了。
 *       缓存里没有的那一路（NOT_PRESENT）不回放：回放收全以后从没听相机服务说过它的，就是「不在」。</li>
 *   <li><b>记在哪</b>：每一声都用 {@link #classify} 分类，记进 {@link Ledger} —— 最后一句（不管多早说的）、是不是我们引起的、
 *       哪几路不在、上一次别人引起的变化。纯 Java，见 {@code CameraAvailabilityWatchTest}。只在主线程上改。</li>
 *   <li><b>交给调度</b>：调度（ChannelScheduler）用 {@link #addListener} 接上：分类时问它「这一路有没有我们的开或关在途」
 *       （它在主线程上的账最准，见 {@link Listener#ourStepInFlight}），有话、问完、回放收全时叫醒它；
 *       调度读 {@link #word}、{@link #serviceSaysFree}、{@link #absent}、{@link #lastOthersChangeAt}、{@link #heardAnything}，
 *       被拿着的那一路有人要时每 30 秒 {@link #ask}。</li>
 *   <li><b>时刻</b>都是开机起算、含深睡（{@code SystemClock.elapsedRealtime}），和 {@link CameraContention}、
 *       {@link CameraHolderSuspects} 一样。调度换到自己的钟：自己的此刻 −（{@code elapsedRealtime()} − 那个时刻）。</li>
 * </ul>
 *
 * <p>旧的看门狗那一套（{@code lastChangeAt}、{@link #quietForMs}、{@link #availableForMs}、{@link #statusAfterLoss}、
 * {@link #markTaken}、对 {@link CameraTaken} 的几处调用）照旧记、照旧报：调度接手、看门狗拆掉之后一起删。</p>
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

    /** 2.11 的记录：相机服务对每一路说的最后一句、哪几路不在、上一次别人引起的变化（{@link Ledger}）。只在主线程上改。 */
    private static final Ledger LEDGER = new Ledger();

    /** 接上来的调度（{@link Listener}）。换车型时两份管理器并存，各接各的。 */
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    /** {@link #start} 成功登记时用的 CameraManager（应用的 Context 取的）：{@link #ask} 用它登记一次性的回调。 */
    private static volatile CameraManager cameraManager;

    /** 主线程的 Handler：回放、相机服务的每一声、收尾任务都排在它的队列里，先后就是到的先后。用时才建。 */
    private static volatile Handler mainHandler;

    /** 主界面、录像、拍照、后视镜用到的几路的 key：我们管着的相机都在这几路里。 */
    private static final String[] KEYS = {CameraSlots.KEY_SURROUND, CameraSlots.KEY_CABIN_FRONT,
            CameraSlots.KEY_CABIN_REAR, CameraSlots.KEY_FOURTH};

    private CameraAvailabilityWatch() {
    }

    /**
     * 开始听。重复调用无害。前台服务和 MultiCameraManager 的构造都调：以前只有前台服务调，它没起来时相机服务说的话
     * 一句都没收，每次丢失都被判成「没说空闲」。
     *
     * <p>登记之后紧接着在主线程上排一个收尾任务：它跑的时候注册那一刻的回放都已经到了（见类说明），回放里没有的槽位
     * 记为「不在」。在管理器的构造里调，这个收尾就排在调度的第一轮之前 —— 冷启动时车机还拿着相机 1，调度第一眼就看得到
     * 「1 不在」，一次也不试开。</p>
     */
    public static synchronized void start(Context context) {
        if (callback != null || context == null) {
            return;
        }
        // 用应用的 Context：这里留着 CameraManager 给 ask() 用，拿 Activity 取的那份会把界面留住
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            return;
        }
        // 嫌疑应用要查使用情况，得有个 Context；争用都从这里来，在这里接上
        CameraHolderSuspects.attach(context);
        Handler main = main();
        Watcher watcher = new Watcher();
        callback = watcher;
        try {
            cm.registerAvailabilityCallback(watcher, main);
        } catch (Exception e) {
            // 容器里这条 binder 走不通的话，报告里会显示「没收到过」，不影响别的
            AppLog.w(TAG, "registerAvailabilityCallback failed: " + e);
            callback = null;
            return;
        }
        cameraManager = cm;
        // 回放都已经 post 进主线程的队列了：紧接着排收尾，它跑的时候回放都到了
        main.post(watcher::replayCollected);
    }

    /**
     * 一直听着的那个回调。注册那一刻的回放（收尾任务跑之前到的每一声）按「初始状态」记，同时收进 {@link #replay}，
     * 收尾时并进记录、补上「不在」。
     */
    private static final class Watcher extends CameraManager.AvailabilityCallback {
        /** 注册那一刻的回放还没收全：收尾任务跑之前到的每一声都算回放。只在主线程上读写（建它时写的初值除外）。 */
        private boolean replaying = true;
        /** 回放里收到的每一路（true 空闲）。 */
        private final Map<String, Boolean> replay = new TreeMap<>();

        @Override
        public void onCameraAccessPrioritiesChanged() {
            // 前后台切换时相机服务重排优先级（API 29+）：记一行；别的程序占着相机的话趁机试一次（旧的看门狗）
            CameraContention.prioritiesChanged();
            CameraTaken.prioritiesChanged();
            // 调度：叫醒、马上问一次，绝不因此去开
            for (Listener listener : LISTENERS) {
                try {
                    listener.onAccessPrioritiesChanged();
                } catch (RuntimeException e) {
                    AppLog.e(TAG, "listener.onAccessPrioritiesChanged failed", e);
                }
            }
        }

        @Override
        public void onCameraAvailable(@NonNull String cameraId) {
            onWord(cameraId, true);
        }

        @Override
        public void onCameraUnavailable(@NonNull String cameraId) {
            onWord(cameraId, false);
        }

        private void onWord(String cameraId, boolean available) {
            if (replaying) {
                replay.put(cameraId, available);
            }
            heard(cameraId, available, replaying);
        }

        /** 收尾任务（登记之后紧接着 post 的）：回放都到了，并进记录、补上「不在」，叫醒调度。 */
        void replayCollected() {
            replaying = false;
            settleReplay(replay, false);
            wake();
        }
    }

    /**
     * 相机服务说了一声（主线程）：用 {@link #classify} 分类、记进 {@link Ledger}，写黑匣子那一行，叫醒调度；
     * 旧的看门狗那一套照旧记（{@link #changed}）。
     *
     * <p>黑匣子那一行的格式不变（「相机服务: N 空闲 / 被占用」加括号），括号按新的分类写：我们开着它时报的「空闲」
     * 是相机服务乱报、算别人引起的变化；丢了之后那一次关的途中报的「被占用」是新的主人，算在途。</p>
     */
    private static void heard(String cameraId, boolean available, boolean replay) {
        long now = SystemClock.elapsedRealtime();
        Word before = LEDGER.word(cameraId);
        boolean holdsOrOpen = weHoldOrOpen(cameraId);
        boolean holdsDevice = weHoldDevice(cameraId);
        Cause cause = LEDGER.heard(cameraId, available, replay, ourStepInFlight(cameraId), holdsOrOpen, now);
        changed(cameraId, available);
        if (cause == Cause.SAME) {
            return;
        }
        boolean cameBack = cause == Cause.OTHERS && before.absent;
        if (available) {
            BlackBox.noteImportant("相机服务: " + cameraId + " 空闲"
                    + (cause == Cause.INITIAL ? "（初始状态）"
                    : cause == Cause.OURS ? ""
                    : holdsDevice ? "（可我们开着它：相机服务乱报，算别人引起的变化）"
                    : cameBack ? "（回来了：之前相机服务的缓存里没有它）" : ""));
        } else {
            BlackBox.noteImportant("相机服务: " + cameraId + " 被占用"
                    + (holdsOrOpen ? "（我们开着）" : "（不是我们：别的程序，或相机服务里没清掉的占用）")
                    + (cause == Cause.INITIAL ? "（初始状态）"
                    : cause == Cause.OURS && !holdsOrOpen ? "（我们这边正在关它：算在途）"
                    : cause == Cause.OTHERS && holdsOrOpen ? "（相机服务改口，算别人引起的变化）"
                    : cameBack ? "（回来了：之前相机服务的缓存里没有它）" : ""));
        }
        wake();
    }

    /**
     * 旧的看门狗那一套的记账（2.10 的 {@link CameraTaken} 闸门、{@link #statusAfterLoss}、诊断报告的 {@link #snapshot}）：
     * 照旧记、照旧报争用。黑匣子那一行挪到了 {@link #heard}。对 {@link CameraTaken} 的几处调用在调度接手、看门狗拆掉之后删，
     * 对 {@link CameraContention} 的留着。
     */
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
            for (String key : KEYS) {
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

    // ================================================================= 2.11：调度读的事实、问、叫醒

    /** 调度（ChannelScheduler，每个管理器一个）接在这里，见 {@link #addListener}。三个方法都在主线程上调。 */
    interface Listener {
        /**
         * 这一路此刻有没有我们的开或关在途：在开（打开发出去了、设备还没回来）、在关、丢了之后的那一次关。只是改会话不算
         * （{@link CameraAvailabilityWatch#classify} 的那个参数）。
         *
         * <p>按调度自己在主线程上的账答，别看 SingleCamera 的字段：相机服务的那一声和相机的回调排在主线程的同一个队列里，
         * 我们开一路时它报的「被占用」、关一路时它报的「空闲」到的时候，调度的账还停在「在开 / 在关」；SingleCamera 的字段
         * 却在相机线程上早改了 —— 关完前 5 ms 报的「空闲」（10-10 现场 (b)），主线程收到时那一次关已经不算在途。</p>
         */
        boolean ourStepInFlight(String cameraId);

        /** 相机服务说了一句（不是重复的那种）、问的结果并进来了、注册那一刻的回放收全了：叫醒调度。 */
        void onServiceChanged();

        /** 相机服务说访问优先级变了（前后台切换那种）：叫醒，马上问一次 —— 绝不因此去开。 */
        default void onAccessPrioritiesChanged() {
            onServiceChanged();
        }
    }

    /** 接上一个调度（管理器建调度时）。重复接无害。 */
    static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    /** 摘掉一个调度（管理器放掉时）。 */
    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** 相机服务对这一路说的最后一句（{@link Word}）：调度每一轮照它填这一路的事实。 */
    static Word word(String cameraId) {
        return LEDGER.word(cameraId);
    }

    /**
     * 相机服务对这一路说的最后一句是「空闲」，不管是多早说的：AOSP 对「空闲 → 空闲」不再回调 —— 10-10 08:56:57 那句假的
     * 「2 空闲」之后我们关 2，相机服务就没再说话（大纲 §7 解读 1）。不在的不算。
     */
    static boolean serviceSaysFree(String cameraId) {
        return Boolean.TRUE.equals(LEDGER.word(cameraId).free);
    }

    /** 不在：相机服务的缓存里没有这一路，回放里也没有它（{@link Word#absent}）。车机拿着后座舱时就是这样。 */
    static boolean absent(String cameraId) {
        return LEDGER.word(cameraId).absent;
    }

    /** 上一次<b>别人引起的</b>变化（开机起算、含深睡）；0 = 没有过。通道的「安静」从这里算（{@link CameraTaken#QUIET_MS}）。 */
    static long lastOthersChangeAt() {
        return LEDGER.lastOthersChangeAt();
    }

    /** 问相机服务一次，不要结果（结果照样并进记录、叫醒调度）。 */
    static void ask() {
        ask(null);
    }

    /**
     * 问相机服务一次：这几路此刻空不空、在不在 —— 只问，不开（项目所有者 2026-10-10 确认）。
     *
     * <p>在主线程上登记一个新的一次性可用性回调，收完它的回放就注销（怎么收全见类说明），和我们的记录对不上的照回放改，
     * 当作别人引起的变化并进来（{@link Ledger#replayed}），然后叫醒调度。读的是进程里的缓存，不额外进相机服务，不开任何相机。
     * 不在主线程上调的，挪到主线程上做。</p>
     *
     * <p>黑匣子：对不上的每一处这里写一行（「问相机服务：表上 1 被占用，它现在说空闲（漏收了一声）→ 照它说的改」）；
     * 每问一次写 AppLog。「第一次、每第 10 次」那一行由调度写 —— 被拿走多久、第几次在它的账上。</p>
     *
     * @param then 收尾时在主线程上交回结果；问不成（还没开始听、登记失败）也交，回放是空的。可以是 null
     */
    static void ask(@Nullable Consumer<Asked> then) {
        Handler main = main();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> ask(then));
            return;
        }
        CameraManager cm = cameraManager;
        if (cm == null) {
            AppLog.w(TAG, "问相机服务：还没开始听（start 没做成），问不了");
            answer(then, new Asked(new TreeMap<>(), new ArrayList<>()));
            return;
        }
        Asker asker = new Asker(cm, then);
        try {
            cm.registerAvailabilityCallback(asker, main);
        } catch (Exception e) {
            AppLog.w(TAG, "问相机服务：registerAvailabilityCallback failed: " + e);
            answer(then, new Asked(new TreeMap<>(), new ArrayList<>()));
            return;
        }
        // 回放都已经 post 进主线程的队列了：紧接着排收尾，它跑的时候回放都到了
        main.post(asker::collected);
    }

    /** 问一次的结果（{@link #ask}），在主线程上交回。 */
    static final class Asked {
        /** 回放里收到的每一路（true 空闲），按相机编号排好；空的 = 没问成（还没开始听、登记失败、一声都没回放），什么都没改。 */
        final Map<String, Boolean> replay;
        /** 照回放改了的那几路（{@link CameraAvailabilityWatch#merge}），按相机编号排好；空的 = 和记录一致。 */
        final List<Correction> corrections;

        Asked(Map<String, Boolean> replay, List<Correction> corrections) {
            this.replay = Collections.unmodifiableMap(new TreeMap<>(replay));
            this.corrections = Collections.unmodifiableList(new ArrayList<>(corrections));
        }

        /** 问成了：收到了回放。 */
        boolean answered() {
            return !replay.isEmpty();
        }
    }

    /**
     * 问一次用的一次性回调：只收登记那一刻的回放，收尾时注销。收尾之后才到的（注销之前已经排进队列的那几声）不收 ——
     * 一直听着的那个回调同样收到了，照常分类。
     */
    private static final class Asker extends CameraManager.AvailabilityCallback {
        private final CameraManager cm;
        @Nullable
        private final Consumer<Asked> then;
        /** 回放里收到的每一路（true 空闲）。只在主线程上读写。 */
        private final Map<String, Boolean> replay = new TreeMap<>();
        /** 收尾跑过了。只在主线程上读写。 */
        private boolean done;

        Asker(CameraManager cm, @Nullable Consumer<Asked> then) {
            this.cm = cm;
            this.then = then;
        }

        @Override
        public void onCameraAvailable(@NonNull String cameraId) {
            if (!done) {
                replay.put(cameraId, true);
            }
        }

        @Override
        public void onCameraUnavailable(@NonNull String cameraId) {
            if (!done) {
                replay.put(cameraId, false);
            }
        }

        /** 收尾任务（登记之后紧接着 post 的）：注销，并进记录，交回结果，叫醒调度。 */
        void collected() {
            done = true;
            try {
                cm.unregisterAvailabilityCallback(this);
            } catch (Exception e) {
                AppLog.w(TAG, "unregisterAvailabilityCallback failed: " + e);
            }
            List<Correction> fixes = settleReplay(replay, true);
            answer(then, new Asked(replay, fixes));
            wake();
        }
    }

    /**
     * 一次回放收全了（注册那一刻的、问来的）：并进记录（{@link Ledger#replayed}），写日志。主线程。
     *
     * <ul>
     *   <li>注册那一刻的回放每一声都已经按「初始状态」记过了，这里只补上回放里没有的槽位：「不在」，第一次记，不算变化；</li>
     *   <li>问来的和记录对不上的照它改，算别人引起的变化，每一处写一行黑匣子；第一次记的、一致的只写 AppLog。</li>
     * </ul>
     */
    private static List<Correction> settleReplay(Map<String, Boolean> replay, boolean asked) {
        List<Correction> fixes = LEDGER.replayed(replay, ourCameraIds(), SystemClock.elapsedRealtime());
        if (replay.isEmpty()) {
            AppLog.w(TAG, (asked ? "问相机服务" : "注册回放") + "：一声都没有（多半没连上相机服务），不据此记「不在」");
            return fixes;
        }
        if (asked) {
            AppLog.i(TAG, "问相机服务：" + replay + (fixes.isEmpty() ? "，和记录一致" : "，对不上：" + fixes));
        } else {
            AppLog.i(TAG, "注册回放收全了：" + replay + (fixes.isEmpty() ? "" : "；补上 " + fixes));
        }
        for (Correction fix : fixes) {
            if (!asked && fix.nowAbsent()) {
                BlackBox.noteImportant("相机服务: " + fix.cameraId
                        + " 不在（初始状态：回放里没有它，相机服务的缓存里没有这一路）");
            } else if (asked && fix.changed()) {
                BlackBox.noteImportant("问相机服务：表上 " + fix.cameraId + " "
                        + (fix.wasAbsent ? "不在" : Boolean.TRUE.equals(fix.before) ? "空闲" : "被占用")
                        + "，它现在" + (fix.nowAbsent() ? "没有这一路（相机服务的缓存里没有它）→ 记为不在"
                        : (Boolean.TRUE.equals(fix.after) ? "说空闲" : "说被占用")
                        + (fix.wasAbsent ? "（它回来了）" : "（漏收了一声）") + " → 照它说的改"));
            }
        }
        return fixes;
    }

    /**
     * 我们在这一路有没有开或关在途（{@link #classify} 的那个参数）：接上来的调度谁说有就算有（换车型时两份管理器并存，
     * 哪一份在动它都是我们）；再看 SingleCamera 的字段 —— 设备不在手上、在开或在关（含丢了之后的那一次关、另一份管理器
     * 在关同一台相机）。调度接上之前只有后者，它在相机线程上改得早，见 {@link Listener#ourStepInFlight}。
     */
    private static boolean ourStepInFlight(String cameraId) {
        for (Listener listener : LISTENERS) {
            try {
                if (listener.ourStepInFlight(cameraId)) {
                    return true;
                }
            } catch (RuntimeException e) {
                AppLog.e(TAG, "listener.ourStepInFlight failed", e);
            }
        }
        return anyOfOurs(cameraId, camera -> !camera.isCameraOpened() && camera.holdsOrIsOpening());
    }

    /** 叫醒接上来的调度（主线程）。调度自己把几次叫醒并成一轮。 */
    private static void wake() {
        for (Listener listener : LISTENERS) {
            try {
                listener.onServiceChanged();
            } catch (RuntimeException e) {
                AppLog.e(TAG, "listener.onServiceChanged failed", e);
            }
        }
    }

    private static void answer(@Nullable Consumer<Asked> then, Asked asked) {
        if (then == null) {
            return;
        }
        try {
            then.accept(asked);
        } catch (RuntimeException e) {
            AppLog.e(TAG, "ask answer failed", e);
        }
    }

    /**
     * 我们的几路用的相机编号（当前管理器里建了的那几路）。管线还没建好时是空的：那时回放里没有的槽位，
     * 等用到时再算「不在」（{@link Ledger#word}）。
     */
    private static List<String> ourCameraIds() {
        List<String> ids = new ArrayList<>();
        try {
            MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
            if (manager == null) {
                return ids;
            }
            for (String key : KEYS) {
                SingleCamera camera = manager.getCamera(key);
                String id = camera == null ? null : camera.getCameraId();
                if (id != null && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "ourCameraIds: " + e);
        }
        return ids;
    }

    /** 主线程的 Handler，用时才建（单元测试只用纯部分，不碰它）。 */
    private static Handler main() {
        Handler handler = mainHandler;
        if (handler == null) {
            synchronized (CameraAvailabilityWatch.class) {
                handler = mainHandler;
                if (handler == null) {
                    handler = new Handler(Looper.getMainLooper());
                    mainHandler = handler;
                }
            }
        }
        return handler;
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

    // ================================================================= 2.11 的记录（纯 Java）

    /** 相机服务对一路说的最后一句，2.11 的记法（{@link CameraAvailabilityWatch#word}）。不可变。 */
    static final class Word {
        /** 什么都不知道：从没听它说过，也还没收全过回放 —— 这时判不出「不在」。 */
        static final Word NONE = new Word(null, false, false, 0L);

        /** 说的：TRUE 空闲、FALSE 被占用；null = 从没说过，或者不在（看 {@link #absent}）。 */
        final Boolean free;
        /** 不在：相机服务的缓存里没有这一路 —— 回放里没有它，之后也没听它开过口（10-10 车机拿着后座舱时的相机 1）。 */
        final boolean absent;
        /**
         * 那一句是我们引起的：说的时候我们在这一路有开或关在途（{@link Cause#OURS}）；注册回放里的「被占用」看那一刻是不是
         * 我们拿着。问出来的、照快照改的都不是。调度据此主动认被拿走：关着的一路，最后一句是「被占用」而不是我们 → 别人拿着。
         */
        final boolean ours;
        /** 那一句（或者记为不在）的时刻，开机起算、含深睡；0 = 没有。问出来的、照快照改的，就是改的那一刻。 */
        final long at;

        Word(Boolean free, boolean absent, boolean ours, long at) {
            this.free = free;
            this.absent = absent;
            this.ours = ours;
            this.at = at;
        }

        static Word absentSince(long at) {
            return new Word(null, true, false, at);
        }

        /** 日志用：{@code free @1234}、{@code busy ours @1234}、{@code absent @1234}、{@code ? @0}。 */
        @Override
        public String toString() {
            return (absent ? "absent" : free == null ? "?" : free ? "free" : "busy") + (ours ? " ours" : "") + " @" + at;
        }
    }

    /**
     * 2.11 的记录：相机服务对每一路说的最后一句（{@link Word}）、哪几路不在、上一次别人引起的变化。
     *
     * <p>纯 Java，时刻由调用方传入（开机起算、含深睡），见 {@code CameraAvailabilityWatchTest}。只在主线程上改
     * （相机服务的每一声、两种收尾任务都在主线程上），哪个线程都能读。</p>
     *
     * <p>「不在」有两种来路，意思一样 —— 相机服务的缓存里没有它：问来的回放里没有我们记过的那一路、或者我们的槽位
     * （照快照记下）；收全过一次回放之后从没听它开过口的那一路（回放收全时管线可能还没建好、不知道槽位，用到时再算）。
     * 它一开口就不再是「不在」：被拿着的那一路要等被拿着以后的一句「空闲」才放开，这一句同时把「不在」清掉。</p>
     */
    static final class Ledger {
        private final Map<String, Word> words = new HashMap<>();
        /** 第一次收全回放（一声都没有的不算）的时刻；0 = 还没有。之后从没开过口的那一路从这一刻起算不在。 */
        private long firstReplayAt;
        /** 上一次别人引起的变化；0 = 没有过。 */
        private long lastOthersChangeAt;

        /**
         * 相机服务说了一声：分类（{@link CameraAvailabilityWatch#classify}），不是重复的就记下；
         * 别人引起的记进 {@link #lastOthersChangeAt()}。
         *
         * @param replay          这一声是注册那一刻的回放
         * @param ourStepInFlight 我们在这一路有开或关在途
         * @param weHoldOrOpen    此刻相机服务那边算不算我们拿着它（开着、在开、照常在关）：只用来记回放里的「被占用」是不是我们
         * @param now             此刻
         * @return 这一声算谁引起的
         */
        synchronized Cause heard(String cameraId, boolean available, boolean replay, boolean ourStepInFlight,
                                 boolean weHoldOrOpen, long now) {
            Word before = words.get(cameraId);
            Cause cause = classify(replay, before == null ? null : before.free, available, ourStepInFlight);
            switch (cause) {
                case SAME:
                    break;
                case INITIAL:
                    words.put(cameraId, new Word(available, false, !available && weHoldOrOpen, now));
                    break;
                case OURS:
                    words.put(cameraId, new Word(available, false, true, now));
                    break;
                default:
                    words.put(cameraId, new Word(available, false, false, now));
                    lastOthersChangeAt = now;
                    break;
            }
            return cause;
        }

        /**
         * 收全了一次回放（注册那一刻的，或者问来的）：用 {@link CameraAvailabilityWatch#merge} 并进记录，
         * 返回改了的那几路（按相机编号排好）。原来有记录（一句话，或者「不在」）而回放说的不一样，算一次别人引起的变化；
         * 第一次记的只是补上，不算。
         *
         * <p>一声都没有的回放不算数，什么都不改：那是没连上相机服务（进程里的缓存是空的），不是每一路都不在了 ——
         * 照它记的话，每一路都会被当成被拿着，再也不开。</p>
         *
         * @param slots 我们的几路用的相机编号；管线还没建好时是空的（null 元素跳过）
         */
        synchronized List<Correction> replayed(Map<String, Boolean> replay, Collection<String> slots, long now) {
            if (replay.isEmpty()) {
                return new ArrayList<>();
            }
            Map<String, Boolean> recorded = new HashMap<>();
            Set<String> absent = new HashSet<>();
            for (Map.Entry<String, Word> e : words.entrySet()) {
                Word word = e.getValue();
                if (word.absent) {
                    absent.add(e.getKey());
                } else if (word.free != null) {
                    recorded.put(e.getKey(), word.free);
                }
            }
            if (firstReplayAt != 0) {
                // 收全过回放、从没开过口的槽位：已经是「不在」了（word() 那样算），这次回放里有它就是它回来了
                for (String slot : slots) {
                    if (slot != null && !words.containsKey(slot)) {
                        absent.add(slot);
                    }
                }
            }
            List<Correction> fixes = merge(recorded, absent, replay, slots);
            for (Correction fix : fixes) {
                words.put(fix.cameraId, fix.nowAbsent() ? Word.absentSince(now) : new Word(fix.after, false, false, now));
                if (fix.changed()) {
                    lastOthersChangeAt = now;
                }
            }
            if (firstReplayAt == 0) {
                firstReplayAt = now;
            }
            return fixes;
        }

        /** 这一路的最后一句。收全过回放、从没听它开过口的是「不在」，从第一次收全回放算起；那之前是 {@link Word#NONE}。 */
        synchronized Word word(String cameraId) {
            Word word = words.get(cameraId);
            if (word != null) {
                return word;
            }
            return firstReplayAt != 0 ? Word.absentSince(firstReplayAt) : Word.NONE;
        }

        /** 上一次别人引起的变化；0 = 没有过。 */
        synchronized long lastOthersChangeAt() {
            return lastOthersChangeAt;
        }
    }
}
