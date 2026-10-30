package com.kooo.evcam.recording;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.CameraNeeds;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.ChannelRules;
import com.kooo.evcam.camera.ChannelScheduler;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.RecordingLanes;
import com.kooo.evcam.recovery.Recovery;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.service.RecordingFloatingService;
import com.kooo.evcam.storage.StorageState;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 录像的唯一入口：要不要录、能不能录、什么时候录、为什么停、停了接不接、此刻是什么样子。
 *
 * <h3>规则（项目所有者 2026-09-27：一个框架，不到处建小房子；2026-10-10 确认文档 §三）</h3>
 *
 * <ul>
 *   <li><b>要不要录</b>由 {@link RecordingIntent}（这一趟开过没有、人停过没有）和开关决定，
 *       决定的人只需要调 {@link #request}；</li>
 *   <li><b>能不能录</b>只看这里：至少选了一路、有 U 盘（存储快照）、写得下；</li>
 *   <li><b>什么时候录</b>：查过盘就开 —— 打开录像意图（{@link RecordingLifecycle#begin}），定下这次录哪几路、存在哪，
 *       不等环视，也不等相机开完。每一路的相机带着录像输出出了画面，那一路就加入；第一次开录和中途接回是同一件事，
 *       都是相机层的事（{@link MultiCameraManager#startRecording}）；</li>
 *   <li><b>一路的事不是停</b>：一路相机被别的程序拿走、断开、卡住，那一路自己收尾退出、回来自己加入，别的路不动；
 *       一路都录不了时录像也不停，显示「等待摄像头，恢复后自动继续」（{@link View#WAITING}）。这里什么都不动，
 *       只把样子推出去（{@link #pushView}）；</li>
 *   <li><b>为什么停</b>：停的人把原因交给 {@link #stop}；停了之后接不接（存储一类自动接回，
 *       {@link RecordingStops#resumesAutomatically}，大纲 §7 第 3 问）、还剩几次额度（{@link RecordingStops.ResumeBudget}），
 *       都在这里判；</li>
 *   <li><b>在不在录</b>只有这里说了算（{@link RecordingLifecycle}：空闲 → 开录中 → 在录 → 停录中）。
 *       熄屏录制、恢复、前台服务心跳、主界面、悬浮按钮都问 {@link #isRecording}，不再问相机层（2026-10-05）；
 *       录像此刻是什么样子（开录中 / 在录 / 等待摄像头）问 {@link #view}；</li>
 *   <li><b>登记</b>：「录像要用相机」（{@link CameraNeeds.Holder#RECORDING}）在录像意图开着时登记；
 *       存储一类整次停之后判了自动接回的，连同熄屏录制的唤醒锁一起留到接回开录、或者确定不接了
 *       （{@link RecordingStops.ResumeHold}，大纲 §7 第 8 问：存储停不动通道，停放的录制器接回时直接加入）。
 *       别的在等开录的（等 U 盘、等上一次收拾完）不登记，相机不为它白白出画面。车辆信号不在这里登记：熄屏判断进程一起来就登记了
 *       核心（{@code ScreenState}，离散信号一直在读、不开定位），这里只听着哨兵模式、挡位；</li>
 *   <li><b>停只有一条路</b>（{@link #end}）：人停的、被打断、开录失败、录着的那一份管线没了，
 *       都在这里收通知、悬浮按钮、唤醒锁，让相机层按同一套收拾（{@link MultiCameraManager#stopRecording()}），
 *       收拾完了才开下一次；</li>
 *   <li><b>熄屏</b>（屏幕真黑了，熄屏 v3）照 {@link ScreenOffPlan} 一张表：座舱屏幕黑满 30 秒放开（相机层的事，2026-10-13），环视接着录或 10 秒后停；
 *       车机要睡（黑着时安卓熄屏）时现在停（哨兵模式没开、车没在走，2026-10-10）。黑满 30 秒以后照通道对环视的熄屏决定
 *       （{@code ChannelScheduler.surroundVerdict}，项目所有者 2026-10-27）：通道放开了环视就停；熄屏中哨兵模式变成开、通道留下了环视，
 *       「这一趟要录」立着就接着录（只录环视，{@link #onChannelVerdict}）—— 车机黑着醒了不开录。
 *       「这一趟要录」落盘（{@link RecordingIntent#recordingWanted}），亮屏、进程被结束后又被拉起来，
 *       都从 {@link #resumeIfWanted} 接回；</li>
 *   <li>主界面、悬浮按钮、开机自启动、亮屏、被打断 —— 都是同一个入口，同一套答案。</li>
 * </ul>
 *
 * <p>进程里只有一份（{@link #get}）：录像不属于某个窗口，主界面不在时悬浮按钮照样走这里。
 * 画面反馈（按钮、计时器、Toast）由 {@link Listener} 的实现方做，这里不碰界面。</p>
 */
public final class RecordingCoordinator {

    private static final String TAG = "RecordingCoordinator";

    /** 要录、却还没有相机管线时多久再看一眼。 */
    static final long POLL_MS = 2_000L;
    /**
     * 条件不满足（盘满、一路都没选）时多久再试：不必两秒一次地去探盘。U 盘不能用的那种头 2 分钟每 2 秒查一次、之后才是它
     * （{@link RecordingStops.DiskWait}，项目所有者 2026-10-13「U 盘一可用就开录」）。
     */
    static final long REFUSED_RETRY_MS = 30_000L;

    /**
     * 谁要录 —— 进日志；屏幕黑着时只有 {@link #RESUME} 不看黑着开不开（{@link #request}）。{@link #SENTRY} 是熄屏中哨兵模式变成开
     * （布防）、通道留下了环视之后的接回（{@link #onChannelVerdict}）：黑满 30 秒以后只有它开得起来（{@link ScreenOffPlan#startsWhileDark}）。
     * {@link #BUTTON} 是灵动贴（2026-10-30），和悬浮按钮一样是人按的。
     */
    public enum Why {
        USER, FLOATING, AUTO_START, RESUME, SCREEN_ON, SENTRY, BUTTON
    }

    /**
     * 录像此刻的样子（{@link #view}）：录制键、悬浮按钮、前台通知、黑匣子都照它。
     * 录像意图开着的三种照管线给的各路摘要定（{@link RecordingLanes}，确认文档 §三）。
     */
    public enum View {
        /** 没在录（含上一次停录还在收拾）。 */
        IDLE,
        /** 录像意图开着，这一次还没有一路在录过，有一路在起来。 */
        PREPARING,
        /** 至少一路在录。 */
        RECORDING,
        /**
         * 录像意图开着，一路都没在录：都被别的程序占用、在救、停手了、现在用不了相机（主界面不在前、前台服务是后台起的），
         * 或者录制器建不起来。录像不算停 ——
         * 「等待摄像头，恢复后自动继续」，哪一路回来哪一路自己加入。
         */
        WAITING
    }

    /** 决策的结果告诉谁（主线程）。实现方负责画面上的反馈。 */
    public interface Listener {
        /** 开录了：录像意图开着。各路带着录像输出出了画面才加入，落定时报 {@link #onStartSettled}。 */
        void onRecordingStarted();

        /**
         * 开录落定了（管线说的，每个录像意图只报一次）：此刻在录的是哪几路、这次计划录哪几路。被占用、在救、
         * 还没出画面的那几路不算在录 —— 它们回来了自己加入，状态条上说。
         *
         * @param liveKeys 落定时在录的几路；空 = 一路都没在录（等待摄像头）
         * @param planned  这次计划录的几路
         */
        default void onStartSettled(Collection<String> liveKeys, Collection<String> planned) {
        }

        /**
         * 录像的样子（{@link RecordingCoordinator#view}）或者各路的摘要（{@link RecordingCoordinator#summary}）可能变了：
         * 照它们重画。只是叫重画，样子以协调器此刻说的为准。
         */
        default void onRecordingViewChanged() {
        }

        /**
         * 停了。
         *
         * @param reason     为什么停
         * @param lastedMs   这一段录了多久
         * @param willResume 这里会在收拾完、查过盘后自动接回
         */
        void onRecordingStopped(RecordingStops.Reason reason, long lastedMs, boolean willResume);

        /** 条件不满足，压根没开始。 */
        void onRecordingRefused(String reason);

        /**
         * U 盘写入跟不上：录像照常在录，但写入排队满了，相机这一侧开始丢帧。
         * 已经限过频（{@link RecordingCoordinator#SLOW_WRITE_NOTICE_GAP_MS}），收到就提示。
         */
        default void onWriteSlow() {
        }
    }

    private static RecordingCoordinator instance;

    /** 整个进程共用的那一份。 */
    public static synchronized RecordingCoordinator get(Context context) {
        if (instance == null) {
            instance = new RecordingCoordinator(context);
        }
        return instance;
    }

    private final Context context;
    private final AppConfig appConfig;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final RecordingStops.ResumeBudget budget = new RecordingStops.ResumeBudget();
    /** 判了自动接回、还没开起来：录像要的几样留着（{@link #isRecordingOrResuming}）。 */
    private final RecordingStops.ResumeHold resumeHold = new RecordingStops.ResumeHold();
    /** 开录在等 U 盘（查盘时盘不能用，拒了接着等）：头 2 分钟每 2 秒查一次（{@link #refused}）。 */
    private final RecordingStops.DiskWait diskWait = new RecordingStops.DiskWait();
    /**
     * 开录前还没回来的那一次查盘是什么时候发出去的（elapsedRealtime）；0 = 没有。查盘卡住了，
     * 判了自动接回时留着的几样不跟着一直拿着（{@link #start}，{@link RecordingStops.ResumeHold#checkStuck}）。
     */
    private long checkSentAtMs;
    /** 下一次开录用的那一份管线。 */
    private MultiCameraManager cameraManager;
    /** 正等着建好的那一份管线（{@link #currentManager}）：一份只挂一次「建好了再看」。 */
    private MultiCameraManager awaitedBuild;
    /** 这一次录像在哪一份管线上开的：停、收拾都找它，它报上来的才算数（管线换了也一样）。 */
    private MultiCameraManager attemptManager;
    private final RecordingLifecycle lifecycle = new RecordingLifecycle();

    /**
     * 在等开录：查盘、等 U 盘插上、等上一次停录收拾完、等相机管线建出来；null = 没在等。
     * 一直留到真正开录（{@link RecordingLifecycle#begin}）才清：查盘是异步的，这中间人停了、退出了，
     * 清掉它，查完回来就不开。
     */
    private Why pending;
    private RecordingStops.Reason lastStopReason;
    private long startedAtMs;

    /** 上一次推出去的样子（{@link #pushView}）：变了才换通知上的字、告诉悬浮按钮。 */
    private View shownView = View.IDLE;
    /** {@link #shownView} 从什么时候起是这个样子（elapsedRealtime）；0 = 还没推过。 */
    private long viewSinceMs;
    /** 这一次录像里有一路在录一共多久（{@link #account}）：自动恢复的额度照它算录没录满一分钟。 */
    private long recordedMs;
    /** 这一次录像里一路都不在录（等待摄像头）一共多久：停录那一行写。 */
    private long waitedMs;
    /** 这一次录像的开录落定报过没有：每个录像意图只报一次。 */
    private boolean settleReported;
    /** 这一阵有一路在录是从什么时候起的（elapsedRealtime）：一路都没在录变成有一路在录的那一刻；0 = 眼下没有一路在录。 */
    private long liveSinceMs;

    /**
     * 「U 盘写入跟不上」提示的限频：一次录像最多提示一次，两次提示之间至少隔这么久
     * （录像被打断、自动接回算新的一次录像，也受这个间隔管）。U 盘一直跟不上时一段接一段地满，
     * 每段都提示会变成一串关不掉的 Toast；黑匣子那边照样每段都有数。
     */
    static final long SLOW_WRITE_NOTICE_GAP_MS = 30 * 60 * 1000L;
    /** 上一次提示「U 盘写入跟不上」的时刻（elapsedRealtime）；0 = 这个进程里还没提示过。 */
    private long slowWriteNoticedAtMs;
    /** 这一次录像里提示过没有。开录时清。 */
    private boolean slowWriteNoticedThisRecording;

    /**
     * 熄屏持续录制关着（或计划里没有环视）时，熄屏后多久停录 —— 唯一的一个缓冲（项目所有者 2026-09-27）。屏幕黑了车机还醒着
     * 几分钟（熄屏 v3），这 10 秒总会走完；车机要睡的时候不等它：现在就停（{@link ScreenOffPlan.Action#STOP_NOW}）。
     */
    static final long SCREEN_OFF_STOP_MS = 10_000L;
    private Runnable screenOffStop;
    /** {@link #screenOffStop} 到点的时刻（elapsedRealtime）：重判时计时照原来的走，那一行写还剩几秒。 */
    private long screenOffStopAtMs;

    // ---- 这一段熄屏（熄屏那一下起，到亮屏）：熄屏小结要的几个数（设计 v3 §6 L8、v3.1 C16）。车机要睡那一行写完不清，亮屏那一行接着用
    /** 这一段熄屏从什么时候起：elapsedRealtime（含深睡；0 = 亮着）、uptime（不含深睡，算车机睡了多久）、墙上时间（小结里写几点几分）。 */
    private long darkSinceElapsedMs;
    private long darkSinceUptimeMs;
    private long darkSinceWallMs;
    /** 这一段熄屏是谁判的（车辆信号 / 读到时已经黑着 / 安卓熄屏）。 */
    private ScreenState.Source darkSource;
    /** 熄屏那一刻在录（含等开录、等自动接回）。 */
    private boolean busyAtDark;
    /** 这一段里录像停了几次（中途的、熄屏停的分开数）、熄屏中哨兵模式变成开后接着录开起来几次（{@link RecordingStops.DarkTally}）。 */
    private final RecordingStops.DarkTally darkTally = new RecordingStops.DarkTally();
    /** 这一段里一路都不在录（等待摄像头）几次、一共多久（不含眼下还在等的这一阵，写小结时补上）。 */
    private int darkWaits;
    private long darkWaitedMs;
    /** 这一段里车机黑着醒过几次（安卓亮了、车辆信号还说黑着）。 */
    private int darkWakes;
    /**
     * 这一次熄屏照 {@link ScreenOffPlan#atDark} 定下的做法：熄屏那一刻定，车机要睡、唤醒锁到点、车辆信号变了、通道放开了环视再定；
     * 车机黑着醒了不动（车机要睡时定的现在停留着，之后接回来的那一次不会被之前的 10 秒后停、重判停掉）；null = 亮着。
     */
    private ScreenOffPlan.Action darkAction;
    /** 上面那个「接着录」是开发者的唤醒锁拉着车机才定的：车辆信号变了不重判，锁到点了再判（{@link #lockTimedOut}）。 */
    private boolean darkActionByLock;
    /** 这一次因熄屏停的录像亮屏接不接：停的那一刻照表定（{@link #stopForScreenOff}），{@link #settleWanted} 照它留不留。 */
    private boolean screenOffResumes;

    /**
     * 熄屏那张表看的哨兵模式、挡位：上一次看到的值，从什么时候起是这个值（elapsedRealtime，从第一次读到起算；0 = 还没读到过）。
     * 黑匣子里写「多久没变」；熄屏期间只有它俩变了才重判（{@link #onVehicleChanged}）。车辆信号不在这里登记：熄屏判断
     * （{@code ScreenState}）进程一起来就登记了核心（离散信号一直在读，不开定位），这里只听着 —— 以前录像意图开着时另登记一份
     * 「screen-off-plan」，空闲时读不到哨兵模式，车机要睡的判断跟着录没录走（设计 v3.1 C17）。
     */
    private Integer seenSentry;
    private String seenGear;
    private long sentrySinceMs;
    private long gearSinceMs;
    private final Telemetry.Listener vehicleListener = readings -> onVehicleChanged();

    /** 熄屏期间为什么判了一次（进黑匣子那一行；判下来要放唤醒锁时，放锁那一行也写它）。 */
    private enum DarkCheck {
        /** 熄屏那一刻（屏幕真黑了；车辆信号读不到时就是安卓熄屏，和车机要睡一起来）。 */
        DARK("dark"),
        /** 车机要睡：黑着时安卓熄屏（这台车上就是车机下电）。 */
        SLEEP("sleep"),
        /** 熄屏 {@link RecordingCoordinator#SCREEN_OFF_STOP_MS} 到了（10 秒后停的那种）。 */
        STOP_DUE("stop-due"),
        /** 开发者熄屏录制的唤醒锁到点。 */
        LOCK_TIMEOUT("timeout"),
        /** 录像停了、不接回，而开发者熄屏录制的唤醒锁正拉着车机（{@link RecordingCoordinator#letLockGo}）。 */
        ENDED("recording-stopped"),
        /** 不等自动接回了，而开发者熄屏录制的唤醒锁正拉着车机（{@link RecordingCoordinator#letLockGo}）。 */
        RESUME_DROPPED("resume-dropped"),
        /** 接着录的时候哨兵模式、挡位变了（只在安卓熄屏着时，{@link ScreenOffPlan#rejudgesOnVehicleChange}）。 */
        VEHICLE_CHANGED("vehicle-changed"),
        /** 通道对环视的熄屏决定变了：放开了环视（{@code ChannelScheduler.surroundVerdict}，{@link RecordingCoordinator#onChannelVerdict}）。 */
        CHANNEL("channel");

        /** 这一次判下来放唤醒锁时，放锁那一行写的原因（ASCII，{@link ScreenOffRecording#release}）。 */
        final String lockWhy;

        DarkCheck(String lockWhy) {
            this.lockWhy = lockWhy;
        }
    }

    private RecordingCoordinator(Context context) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(this.context);
        // 车辆信号（核心，进程活着一直在读）：哨兵模式、挡位变了照表重判。只挂一次。ScreenState 装上时先挂了它的那一份
        // （进程一起来就装，这里总在它之后）：同一批读数里先判屏幕，再看这里
        Telemetry.get().addListener(vehicleListener);
        // 通道对环视的熄屏决定（调度在一轮当中发）：排到主线程上再做，做的时候读此刻的决定。只挂一次 —— 先装上调度
        // （应用一起来就装上了，装过了什么都不做）：没装上时挂的会丢
        com.kooo.evcam.camera.ChannelFacts.install(this.context);
        ChannelScheduler.setSurroundVerdictListener(() -> main.post(this::onChannelVerdict));
    }

    // ================================================================= 接线

    /**
     * 相机管理器换了对象就要重新交一次：它换了而这里还握着旧的，表现是「按了录制，什么都没发生」。
     * 录制管线的几个回调（写不进、盘满、一路加入 / 退出、开录 / 停录走到哪一步）都接到这里，主界面在不在都一样。
     *
     * <p>录着的时候换了一份（车型变了、启用的几路变了，旧的那份被释放重建）：旧的那份上的录像到头了，
     * 按「录制器自己停了」走停录那条路，收拾完、查过盘在新的这一份上自动接回。以前这里不知道，
     * 一直以为还在录。</p>
     */
    public void setCameraManager(MultiCameraManager manager) {
        if (cameraManager == manager) {
            return;
        }
        cameraManager = manager;
        if (manager == null) {
            return;
        }
        wire(manager);
        if (lifecycle.isRecording() && attemptManager != null && attemptManager != manager) {
            BlackBox.noteImportant("录着的那一份相机管线换掉了：这一段到头，收拾完在新的管线上自动接回");
            end(RecordingStops.Reason.UNKNOWN);
        }
    }

    /**
     * 管线的回调。只认这一次录像所在的那一份（{@link #attemptManager}）报上来的：
     * 换掉的、释放了的旧管线再报什么都不算。都转到主线程。
     */
    private void wire(MultiCameraManager manager) {
        manager.setStorageFullCallback(decision -> main.post(() -> {
            if (manager == attemptManager) {
                stop(decision.capless ? RecordingStops.Reason.STORAGE_FULL : decision.lockedFull
                        ? RecordingStops.Reason.STORAGE_LOCKED : RecordingStops.Reason.STORAGE_CANNOT_FREE);
            }
        }));
        // 录制器判的、管线认定是盘或编码器的事（相机在出画面、会话里带着它的录像输出）：写出过数据又断了是「写不进」，
        // 一个字节都没写出过是「没写进」。相机的问题只那一路退出，不报到这里
        manager.setWriteStallCallback((stalledMs, everWrote) -> main.post(() -> {
            if (manager == attemptManager) {
                stop(everWrote ? RecordingStops.Reason.WRITE_STALLED : RecordingStops.Reason.NO_DATA);
            }
        }));
        // 一路加入、退出，各路的样子变了：录像状态、前台服务、唤醒锁、计时都不动，只把样子推出去
        manager.setLaneListener(() -> main.post(() -> {
            if (manager == attemptManager) {
                pushView();
            }
        }));
        // 写盘跟不上：不停录（录像照常，只是开始丢帧），提示一句，限频
        manager.setWriteBacklogCallback(cameraId -> main.post(() -> {
            if (manager == attemptManager) {
                onWriteBacklog(cameraId);
            }
        }));
        manager.setPipelineCallback(new MultiCameraManager.PipelineCallback() {
            @Override
            public void onPipelineStarted(Set<String> active, Set<String> failed) {
                main.post(() -> onPipelineReport(manager, RecordingLifecycle.Report.STARTED,
                        active + (failed.isEmpty() ? "" : " / failed " + failed)));
            }

            @Override
            public void onPipelineStartFailed(String why) {
                main.post(() -> onPipelineReport(manager, RecordingLifecycle.Report.START_FAILED, why));
            }

            @Override
            public void onPipelineStopped() {
                main.post(() -> onPipelineReport(manager, RecordingLifecycle.Report.STOPPED, null));
            }
        });
    }

    /** 管线报上来开录 / 停录走到了哪一步（主线程）。该怎么办由 {@link RecordingLifecycle#on} 判。 */
    private void onPipelineReport(MultiCameraManager from, RecordingLifecycle.Report report, String detail) {
        if (from != attemptManager) {
            return;
        }
        RecordingLifecycle.Action action = lifecycle.on(report);
        switch (action) {
            case NOTE:
                BlackBox.noteImportant("录制器已启动 " + detail);
                pushView();
                break;
            case END:
                BlackBox.noteImportant(report == RecordingLifecycle.Report.START_FAILED
                        ? "开录失败：" + detail : "录制管线自己停了（没人叫它停，被释放了）");
                // 原因按停之前的阶段定：开录中录制器都建不起来是开录失败，录了一阵之后每一路都录不了了是录制器自己停了
                end(lifecycle.endReason(report));
                break;
            case SETTLED:
                settled("pipeline-stopped");
                break;
            default:
                break;
        }
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * 在不在录 —— 进程里唯一的答案：录像意图开着，从开录指令发出去起（开录中），到停为止（{@link RecordingLifecycle}）。
     * 一路都没在录（等待摄像头）也算在录；此刻是什么样子问 {@link #view}。
     */
    public boolean isRecording() {
        return lifecycle.isRecording();
    }

    /**
     * 录像要的几样此刻该不该拿着：在录（{@link #isRecording}），或者存储一类整次停之后判了自动接回、还没开起来
     * （{@link RecordingStops.ResumeHold}，大纲 §7 第 8 问）。
     * 熄屏录制的唤醒锁照它拿、照它算拉不拉得住车机（{@link ScreenOffRecording#holdsCarAwake}）。主线程。
     */
    public boolean isRecordingOrResuming() {
        return resumeHold.claims(isRecording());
    }

    /**
     * 这一段录像是什么时候开的（{@code SystemClock.elapsedRealtime}）；没在录是 0。
     * 悬浮按钮的时长从这里算：用系统时钟算的话，车机对时把钟往回拨，时长就成了负数。
     */
    public long startedAtElapsedMs() {
        return isRecording() ? startedAtMs : 0;
    }

    /**
     * 这一阵有一路在录是从什么时候起的（{@code SystemClock.elapsedRealtime}）；眼下没有一路在录是 0。
     * 录制键、悬浮按钮上「本段」的进度从这里量：一路都不在录（等待摄像头）之后第一路接回，它的文件从那一刻起，
     * 分段也从那一刻重新算（RecordingLanes：之后加入的几路到下一次分段时跟它对齐），不再是开录时的那个节拍。
     */
    public long liveSinceElapsedMs() {
        return isRecording() ? liveSinceMs : 0;
    }

    /** 在等开录：查盘、等 U 盘插上、等上一次停录收拾完、等相机管线建出来。 */
    public boolean isWaiting() {
        return pending != null;
    }

    /**
     * 录制管线还有人要：在录、在等开录、或者上一次还在收拾。主界面销毁时据此决定留不留前台服务和中转写入
     * （管线本身属于应用，主界面销毁不放它，大纲 §2 阶段 6）。
     */
    public boolean needsPipeline() {
        return !lifecycle.isIdle() || pending != null;
    }

    public RecordingStops.Reason lastStopReason() {
        return lastStopReason;
    }

    // ================================================================= 样子

    /**
     * 录像此刻的样子 —— 录制键、悬浮按钮、前台通知都照它画（大纲 5c）。没在录是 {@link View#IDLE}；
     * 录像意图开着时照管线给的各路摘要（{@link #summary}，{@link RecordingLanes}）：有一路在录是在录；
     * 这一次还没有一路录过、有一路在起来是开录中；一路都没在录、也没有一路在起来是等待摄像头。
     * 管线还没定下这一次录哪几路时（开录指令发出去的那一下）照生命周期的阶段说。主线程。
     */
    public View view() {
        return viewOf(summary());
    }

    /**
     * 这一次录像各路的摘要（管线给的：计划录哪几路、在录的几路、落定没有、状态条该说哪一句）；
     * 没在录（含停录中）、没有管线时是 null。主线程。
     */
    public RecordingLanes.Summary summary() {
        return lifecycle.isRecording() && attemptManager != null ? attemptManager.laneSummary() : null;
    }

    private View viewOf(RecordingLanes.Summary summary) {
        if (!lifecycle.isRecording()) {
            return View.IDLE;
        }
        if (summary == null || summary.planned.isEmpty()) {
            return lifecycle.phase() == RecordingLifecycle.Phase.RECORDING ? View.RECORDING : View.PREPARING;
        }
        switch (summary.view) {
            case RECORDING:
                return View.RECORDING;
            case WAITING:
                return View.WAITING;
            default:
                return View.PREPARING;
        }
    }

    /**
     * 前台通知的标题照录像此刻的样子写（通知：标题写状态）：正在录制、等待摄像头；没在录是在后台运行。
     * 前台服务每次被叫起来（{@link CameraForegroundService#start}）都照它写，样子变了由 {@link #pushView} 当场换。
     */
    public String notificationTitle() {
        return titleFor(view());
    }

    /** 前台通知的正文：能做的动作（点击返回应用）。 */
    public String notificationContent() {
        return contentFor(view());
    }

    private String titleFor(View view) {
        return context.getString(view == View.WAITING ? R.string.notif_waiting_cameras_title
                : view == View.IDLE ? R.string.notif_background_title : R.string.notif_recording_title);
    }

    private String contentFor(View view) {
        return context.getString(view == View.IDLE ? R.string.notif_tap_to_return : R.string.notif_recording_tap);
    }

    /**
     * 把录像的样子推出去（主线程）：开录、停录、管线报上来、一路加入或退出、各路的样子变了都叫一次。
     *
     * <ul>
     *   <li>样子变了：前台通知换字（服务在才换，用 NotificationManager 当场换 —— 在后台时绝不为换字
     *       startForegroundService，{@link CameraForegroundService#showText}）、悬浮按钮重画；
     *       在录、等摄像头各自多久记下来（{@link #account}）。黑匣子里那几行（一路都不在录了、又有一路在录了、
     *       各路都停手了、开录落定）只在管线里写（MultiCameraManager 算各路样子的那一处）：各路此刻怎样只有它知道；</li>
     *   <li>开录落定（管线说的，每个录像意图只报一次）：告诉监听者此刻在录的几路、计划的几路；</li>
     *   <li>监听者每次都叫：状态条上那一句跟着摘要变，不一定换样子。</li>
     * </ul>
     */
    private void pushView() {
        RecordingLanes.Summary summary = summary();
        View now = viewOf(summary);
        if (now != shownView) {
            long at = android.os.SystemClock.elapsedRealtime();
            account(at);
            if (now == View.WAITING && darkSinceElapsedMs > 0) {
                // 熄屏这一段里又一次一路都不在录（熄屏小结写几次、共多久）
                darkWaits++;
            }
            AppLog.i(TAG, "录像的样子：" + shownView + " → " + now);
            liveSinceMs = now == View.RECORDING ? at : 0;
            shownView = now;
            CameraForegroundService.showText(context, titleFor(now), contentFor(now));
            RecordingFloatingService.sendRecordingStateChanged(context);
        }
        if (summary != null && summary.settled && !settleReported) {
            settleReported = true;
            for (Listener listener : new ArrayList<>(listeners)) {
                listener.onStartSettled(summary.liveKeys, summary.planned);
            }
        }
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onRecordingViewChanged();
        }
    }

    /**
     * 眼下这个样子（{@link #shownView}）算到 {@code at}（elapsedRealtime）为止：这一次录像里有一路在录一共多久、
     * 一路都不在录（等待摄像头）一共多久。自动恢复的额度只认有一路在录的那几段（{@link #end}）。熄屏这一段里等待摄像头的那几阵
     * 另算一份（从熄屏起算，熄屏小结用）。
     */
    private void account(long at) {
        long span = viewSinceMs > 0 ? at - viewSinceMs : 0;
        if (shownView == View.RECORDING) {
            recordedMs += span;
        } else if (shownView == View.WAITING) {
            waitedMs += span;
            if (darkSinceElapsedMs > 0 && viewSinceMs > 0) {
                darkWaitedMs += Math.max(0L, at - Math.max(viewSinceMs, darkSinceElapsedMs));
            }
        }
        viewSinceMs = at;
    }

    /** 黑匣子里的几路：按开的次序写每一路的叫法（环视、后座舱、前座舱）。 */
    private static String roles(Collection<String> keys) {
        List<String> names = new ArrayList<>();
        for (String key : CameraSlots.openOrder(keys)) {
            names.add(MultiCameraManager.roleName(key));
        }
        return String.join("、", names);
    }

    // ================================================================= 要录

    /**
     * 要录：查过盘就开，不等环视。已经在录、已经在等，都无害。主线程调。
     *
     * <p>屏幕黑着（熄屏 = 屏幕真黑了）时照 {@link ScreenOffPlan#startsWhileDark}（{@link #darkStartNow}）：车机醒着（安卓亮着）、
     * 计划里有环视；黑了不到 30 秒、在开车时熄屏后不停录就开，黑满 30 秒以后只有熄屏中哨兵模式变成开、通道留下环视的那一下
     * （{@link Why#SENTRY}）、能用相机才开 —— 只录环视，座舱黑着时谁都不要。别的只记下「这一趟要录」，亮屏时
     * {@link #screenOn} 照它接（channel-logic §五：熄屏时不开相机；设计 v3.1 C1：熄屏持续录制关着就是熄屏后不录；
     * 项目所有者 2026-10-27：不为中间不明原因的启动去试着录）。以前黑着一律不开：熄屏那张表只在熄屏那一刻、和录着时的重判里判，
     * 黑着以后才来的要录没人判，录像意图在黑屏里开、却因为「车机要睡」一路都开不起来（审查 2026-10-11）—— 车机要睡（安卓熄屏）时
     * 现在照样不开。例外是存储一类打断后的自动接回（{@link Why#RESUME}）：它接的是通道允许、正在录的那一次，安卓熄屏着也接（C10；
     * 车机要睡判成现在停、通道放开了环视时会先把它取消掉）。</p>
     *
     * <p>黑着不开的那一下只在「这一趟要录」真的变了时记一行（C9：恢复每 5 分钟例行来一次，不然一夜几十行）。</p>
     */
    public void request(Why why) {
        if (isRecording()) {
            return;
        }
        ScreenOffPlan.DarkStart start = ScreenState.dark() && why != Why.RESUME ? darkStartNow(why) : null;
        if (start != null && start != ScreenOffPlan.DarkStart.STARTS) {
            if (RecordingIntent.current().noteRecordingWanted(true)) {
                // 熄屏持续录制开着、计划里有环视的：熄屏中哨兵模式变成开（醒来读到睡着时被打开的也算）、通道留下环视时接（onChannelVerdict）
                BlackBox.noteImportant("要录像（" + why + "）：屏幕黑着（"
                        + (start == ScreenOffPlan.DarkStart.HEAD_UNIT_SLEEPS ? "车机要睡"
                        : start == ScreenOffPlan.DarkStart.NO_SURROUND ? "计划里没有环视"
                        : start == ScreenOffPlan.DarkStart.SETTING_OFF ? "熄屏持续录制关着，熄屏后不录"
                        : start == ScreenOffPlan.DarkStart.CHANNEL_RELEASED ? "通道没留环视"
                        : start == ScreenOffPlan.DarkStart.NO_SENTRY_RISE ? "通道留着环视，熄屏中只在哨兵模式变成开时开录"
                        : "现在用不了相机") + "），先不开录；记下「这一趟要录」，"
                        + (appConfig.isScreenOffKeepRecording() && surroundPlanned()
                        ? "亮屏、或熄屏中哨兵模式变成开（布防）后接着录" : "亮屏后接着录"));
            }
            return;
        }
        if (pending == null) {
            // 黑着开录录哪几路照通道眼里的熄屏写（MultiCameraManager.channelCountsDark）：黑了不到 30 秒时关着的座舱不开、
            // 开着的录到黑满 30 秒才放开（ChannelRules.releasedByDark，项目所有者 2026-10-13）
            BlackBox.noteImportant("要录像（" + why + "）：查过盘就开录，不等环视"
                    + (!ScreenState.dark() ? "" : MultiCameraManager.channelCountsDark() ? "（屏幕黑着：只录环视）"
                    : "（屏幕黑了不到 " + (com.kooo.evcam.camera.ChannelRules.HOLD_MS / 1000)
                    + " 秒：座舱不开，开着的录到黑满 " + (com.kooo.evcam.camera.ChannelRules.HOLD_MS / 1000) + " 秒）"));
        }
        pending = why;
        noteWanted(true, why.name());
        main.removeCallbacks(poll);
        main.post(poll);
    }

    /** 不等了：人停了、退出了、熄屏了。判了自动接回时留着的几样一起放（{@link #dropResumeHold}）。 */
    public void cancelPending(String why) {
        if (pending != null) {
            AppLog.i(TAG, "不再等开录（" + why + "）");
            pending = null;
            main.removeCallbacks(poll);
        }
        diskWait.cancel();
        dropResumeHold(why);
    }

    /**
     * 等开录的那一个等待：要录、上一次收拾完了（{@link #settled}）、拒了之后过一会儿、还没有管线时过一会儿，都从这里再看一次。
     * 以前主界面里有七处各自「等两秒再开」的梯子，后来都归到这一个；2.11 起它不再等环视。
     */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (pending == null) {
                return;
            }
            if (isRecording()) {
                pending = null;
                return;
            }
            if (!lifecycle.isIdle()) {
                // 上一次还在收拾：收拾完了（settled，到期限也算）会立刻再来看
                return;
            }
            if (currentManager() == null) {
                // 还没有相机管线：过一会儿再看
                main.postDelayed(this, POLL_MS);
                return;
            }
            start(pending);
        }
    };

    /**
     * 熄屏了（ScreenState 在主线程调，屏幕真黑了）：不管在不在录，都照 {@link ScreenOffPlan#atDark} 判一次、照着做。
     * 平常这时车机醒着（安卓还亮着，几分钟后才下电）：熄屏持续录制开着、计划里有环视就接着录环视，不然 10 秒后停 ——
     * 座舱不归这张表，屏幕黑满 30 秒相机层放开（{@code ChannelRules.releasedByDark}、{@code darkForChannel}，项目所有者 2026-10-12 D2、
     * 2026-10-13「黑满 30 秒再放」）—— 这张表不跟着挪，10 秒后停照屏幕真黑的那一刻算。
     * 车辆信号读不到时熄屏就是安卓熄屏（{@code androidOff}），也就是车机要睡一起来了：照表现在停、写下「车机要睡」
     * （和 2.11.1 一样），熄屏小结当场写（几秒后进程就可能没了，C16）。
     * 这一段熄屏的小结从这里起算，在不在录都算（以前只算在录、在等的那一段）。这里先于主界面、后视镜、盲区画面、相机层的熄屏监听者被叫到
     * （ScreenState 的固定顺序）：调度的那一轮排在它们之后。
     *
     * @param androidOff 安卓也熄屏了（车辆信号读不到、或者说亮着时的安卓熄屏）
     */
    public void screenOff(boolean androidOff) {
        cancelScreenOffStop();
        darkAction = null;
        darkSinceElapsedMs = android.os.SystemClock.elapsedRealtime();
        darkSinceUptimeMs = android.os.SystemClock.uptimeMillis();
        darkSinceWallMs = System.currentTimeMillis();
        darkSource = ScreenState.source();
        busyAtDark = isRecording() || pending != null;
        darkTally.reset();
        // 熄屏时已经在等待摄像头：这一阵算这一段里的一次，时长从熄屏起算（noteDarkSummary）
        darkWaits = shownView == View.WAITING ? 1 : 0;
        darkWaitedMs = 0;
        darkWakes = 0;
        decideWhileDark(DarkCheck.DARK, androidOff);
        if (androidOff) {
            noteDarkSummary(true, true);
        }
    }

    /**
     * 车机要睡了（ScreenState 在主线程调：黑着时安卓熄屏 —— 这台车上就是车机下电，哨兵模式没开时离车约 4–6 分钟后）：
     * 照表再判一次（安卓熄屏了）。现在停的写下「车机要睡」、停录，调度按次序关（座舱早放开了，只剩环视）；然后写熄屏小结 ——
     * 进程 3–5 秒后就可能没了（设计 v3 §3.2、v3.1 C16）。小结写完这一段的数不清：亮屏那一行还要用（车机睡了多久）。
     */
    public void headUnitSleeps() {
        decideWhileDark(DarkCheck.SLEEP, true);
        noteDarkSummary(true, false);
    }

    /**
     * 车机黑着醒了（ScreenState 在主线程调：屏幕还黑着，安卓又亮了 —— 车机自己醒了一阵，10-12 01:40 那种；车辆信号是醒来后
     * 现读的，不是睡之前的）：撤掉「车机要睡」（车机醒着，调度照常保持、开相机；座舱照样放开）。不开录（项目所有者 2026-10-27：
     * 醒来哨兵模式还开着不算变成开）—— 睡着时哨兵模式被打开的，醒来那一轮通道读到、留下环视，照通道的决定接着录（{@link #onChannelVerdict}）。
     *
     * <p>这一次熄屏定下的做法不动：车机要睡时定的现在停留着（{@link #darkAction}），之前排的 10 秒后停早取消了，车辆信号变了也不重判
     * （{@link ScreenOffPlan#rejudgesOnVehicleChange}）—— 接回来的那一次录到下一次车机要睡、通道放开环视、或者亮屏。</p>
     */
    public void headUnitWoke() {
        CameraNeeds needs = CameraNeeds.current();
        if (ScreenOffPlan.sleepFact(false, null, needs.headUnitSleeps()) == ScreenOffPlan.SleepFact.CLEAR) {
            needs.setHeadUnitSleeps(false);
            BlackBox.note("车机醒着（屏幕还黑着）：撤掉「车机要睡」，通道调度照常保持、开相机（座舱照样放开）");
        }
        if (darkSinceElapsedMs > 0) {
            darkWakes++;
        }
    }

    /**
     * 开发者熄屏录制的唤醒锁到点了（ScreenOffRecording 排到主线程上调）：锁不算拉着车机了，照熄屏那张表再判一次
     * （2026-10-10，以前到点就放锁，车机带着开着的相机睡过去）。车机醒着（安卓亮着）时判下来是接着录（熄屏后不停录）或者
     * 10 秒后停，锁现在放，环视录到车机要睡那一次再判；安卓已经熄屏、判成现在停的，锁留到相机按次序关完
     * （{@link ScreenOffRecording#holdUntilCamerasClosed}）。屏幕黑着就判，在不在录都一样。
     */
    public void lockTimedOut() {
        if (!ScreenState.dark()) {
            ScreenOffRecording.release("timeout");
            return;
        }
        if (decideWhileDark(DarkCheck.LOCK_TIMEOUT, ScreenState.androidOff()) != ScreenOffPlan.Action.STOP_NOW) {
            ScreenOffRecording.release("timeout");
        }
    }

    /**
     * 照熄屏那张表判一次、照着做（主线程；屏幕黑着时）—— 熄屏的唯一裁判：熄屏那一刻、车机要睡、10 秒到点、唤醒锁到点、
     * 车辆信号变了、通道放开了环视（{@link #onChannelVerdict}）、录像停了不接回或者不等自动接回了而唤醒锁正拉着车机
     * （{@link #letLockGo}）都走这里，黑匣子一行写清判的时候哨兵模式、挡位是什么、多久没变，安卓熄没熄屏，判成了什么。
     * 表的输入里有通道对环视的熄屏决定（{@link #channelVerdict}）：照亮着算时和以前一样，黑满 30 秒以后通道放开了就现在停。
     *
     * <p>判成现在停：先写下「车机要睡」（调度当场置「正在按次序关」，熄屏录制放锁之前要读它），再停录。写不写、清不清只照
     * {@link ScreenOffPlan#sleepFact}：已经写着的不再写，起算时刻不挪；安卓亮着时写着的清掉。没在录、也没在等开录的，
     * 只动这项事实，录像那边没有可做的。</p>
     *
     * @param androidOff 安卓熄屏着（车机要睡了 / 睡着）：只有这时才判得出现在停
     * @return 判下来的做法
     */
    private ScreenOffPlan.Action decideWhileDark(DarkCheck check, boolean androidOff) {
        boolean lockHeld = ScreenOffRecording.holdsCarAwake(context);
        VehicleState vehicle = Telemetry.get().latest();
        boolean keeps = ScreenOffPlan.keepsRecording(appConfig);
        boolean surround = surroundPlanned();
        ChannelRules.SurroundInDark channel = channelVerdict();
        ScreenOffPlan.Decision decision = ScreenOffPlan.atDark(appConfig, lockHeld, vehicle, androidOff, surround, channel);
        // 接着录的那一种录到什么时候（那一行照实写）：安卓熄屏了、没在开车 —— 通道随后放开环视（车机要睡，不管哨兵模式）
        boolean driving = ScreenOffPlan.driving(vehicle.gear, vehicle.speedKmh);
        boolean busy = isRecording() || pending != null;
        // 只在等开录（查盘、等 U 盘、等上一次收拾完；没登记相机，不算录像那一段）：判下来是接着等、不等了，
        // 不是接着录、停录。存储停之后等自动接回的那一段照在录写（相机留着，大纲 §7 第 8 问）
        boolean waitingOnly = pending != null && !isRecordingOrResuming();
        // 唤醒锁拉着车机时判下来一定不是现在停
        darkAction = decision.action;
        darkActionByLock = lockHeld;
        long now = android.os.SystemClock.elapsedRealtime();
        // 又判成 10 秒后停（哨兵模式、挡位变了，还是车机醒着）：计时照原来的走、不重新开始，那一行照实写还剩几秒
        boolean timerKept = screenOffStop != null;
        long leftS = (Math.max(0L, screenOffStopAtMs - now) + 999) / 1000;
        BlackBox.noteImportant((check == DarkCheck.DARK || check == DarkCheck.SLEEP
                ? (check == DarkCheck.DARK ? "熄屏时" : "车机要睡时") + (!busy ? "没在录像"
                : waitingOnly ? "在等开录（" + pending + "）" : isRecording() ? "在录像" : "在等自动接回")
                : check == DarkCheck.STOP_DUE ? "熄屏已 " + (SCREEN_OFF_STOP_MS / 1000) + " 秒"
                : check == DarkCheck.LOCK_TIMEOUT ? "熄屏录制的唤醒锁到点"
                : check == DarkCheck.ENDED ? "熄屏中录像停了、不接回，唤醒锁不再拉着车机"
                : check == DarkCheck.RESUME_DROPPED ? "熄屏中不等自动接回了，唤醒锁不再拉着车机"
                : check == DarkCheck.CHANNEL ? "通道对环视的熄屏决定变了"
                : "熄屏中哨兵模式 / 挡位变了")
                + "：哨兵模式" + (vehicle.sentry == null ? "读不到" : vehicle.sentry == 0 ? "关"
                : vehicle.sentry == 1 ? "开" : "布防")
                + (sentrySinceMs > 0 ? "（" + (now - sentrySinceMs) / 1000 + " 秒没变）" : "") + "、挡位 "
                + (vehicle.gear == null ? "读不到" : vehicle.gear)
                + (gearSinceMs > 0 ? "（" + (now - gearSinceMs) / 1000 + " 秒没变）" : "") + "、车速 "
                + (vehicle.speedKmh == null ? "读不到" : String.format(Locale.US, "%.1f", vehicle.speedKmh))
                + "；熄屏持续录制" + (appConfig.isScreenOffKeepRecording() ? "开" : "关")
                + "、熄屏录制" + (appConfig.isScreenOffRecordingEnabled() ? (lockHeld ? "拉着车机" : "开（锁不拉着车机）")
                : appConfig.isScreenOffRecordingStoredOn() ? "没生效（存着是开，开发者选项没解锁）" : "关")
                + "、启动自动录制" + (appConfig.isAutoStartRecording() ? "开" : "关")
                + (androidOff ? "；安卓熄屏了" : "；车机醒着（安卓亮着）") + " → "
                // 没在录、通道没留环视：熄屏那一刻、车机要睡那一刻说「不开录」；录像停了、不等自动接回了那一下只说环视照通道
                + (!busy ? (decision.action == ScreenOffPlan.Action.STOP_NOW
                ? "车机要睡了：相机按次序关"
                : decision.action != ScreenOffPlan.Action.STOP_RELEASED ? "环视照登记表"
                : check == DarkCheck.DARK || check == DarkCheck.SLEEP ? "通道没留环视：不开录" : "环视照通道")
                : waitingOnly ? (decision.action == ScreenOffPlan.Action.CONTINUE
                ? "接着等，好了就开录（" + (MultiCameraManager.channelCountsDark() ? "只录环视"
                : "座舱不开，开着的录到黑满 " + (com.kooo.evcam.camera.ChannelRules.HOLD_MS / 1000) + " 秒") + "）"
                : decision.action == ScreenOffPlan.Action.STOP_NOW
                ? "车机要睡了：不等开录了，相机按次序关"
                : decision.action == ScreenOffPlan.Action.STOP_RELEASED ? "通道放开了环视：不等开录了"
                : check == DarkCheck.STOP_DUE ? "不等开录了" : timerKept ? "照原来的计时（还剩 " + leftS + " 秒）不等开录了"
                : (SCREEN_OFF_STOP_MS / 1000) + " 秒后不等开录了")
                // 接着录的录到什么时候：安卓熄屏了、没在开车 —— 通道随后放开环视（车机要睡）；通道留着 —— 接着录；
                // 照亮着算（黑了不到 30 秒、在开车）—— 通道算黑时照哨兵模式、熄屏持续录制定
                : decision.action == ScreenOffPlan.Action.CONTINUE
                ? "接着录环视" + (androidOff && !driving ? "（安卓熄屏了：通道随后放开环视，那时停）"
                : channel == ChannelRules.SurroundInDark.KEPT ? "（通道留着环视）"
                : appConfig.isScreenOffKeepRecording() ? "（黑满 " + (ChannelRules.HOLD_MS / 1000)
                + " 秒、没在开车时哨兵模式开或布防才接着录，不然停）"
                : "（熄屏持续录制关着：黑满 " + (ChannelRules.HOLD_MS / 1000) + " 秒、没在开车时停）")
                : decision.action == ScreenOffPlan.Action.STOP_NOW
                ? "车机要睡了：现在停录，相机按次序关"
                : decision.action == ScreenOffPlan.Action.STOP_RELEASED ? (isRecording()
                ? "通道放开了环视（熄屏持续录制关着、或者黑满 " + (ChannelRules.HOLD_MS / 1000)
                + " 秒时哨兵模式没开、熄屏中哨兵关了、车机要睡）：现在停录" : "通道放开了环视：不等自动接回了")
                : check == DarkCheck.STOP_DUE ? "停录" : timerKept ? "照原来的计时（还剩 " + leftS + " 秒）停录"
                : (SCREEN_OFF_STOP_MS / 1000) + " 秒后停录")
                + (busy && (keeps || lockHeld) && !surround && decision.action == ScreenOffPlan.Action.STOP_LATER
                ? "（计划里没有环视）" : "")
                // 座舱不归这张表：通道眼里黑了才放开 —— 黑满 30 秒；起点不明、安卓也熄屏了（车机要睡）是当场
                // （MultiCameraManager.channelCountsDark，和调度同一句，不在这里另判）。停录时前台服务一起停的（亮屏后不接、
                // 开机自启动关着，CameraForegroundService.stop）：之后没人要的相机不保持，当场关（规格 §3.5「保持」）
                + (check != DarkCheck.DARK ? "" : MultiCameraManager.channelCountsDark() ? "，座舱放开"
                : "，座舱黑满 " + (com.kooo.evcam.camera.ChannelRules.HOLD_MS / 1000) + " 秒后放开"
                + (isRecording() && decision.action == ScreenOffPlan.Action.STOP_LATER && !decision.resumesOnWake
                && !appConfig.isAutoStartOnBoot()
                ? "（停录时前台服务一起停：那时没人要的相机当场关，不等 "
                + (com.kooo.evcam.camera.ChannelRules.HOLD_MS / 1000) + " 秒）" : ""))
                + (!busy || decision.action == ScreenOffPlan.Action.CONTINUE ? ""
                : !decision.resumesOnWake ? "；亮屏后不接"
                : appConfig.isScreenOffKeepRecording() && surround ? "；亮屏、或熄屏中哨兵模式变成开（布防）后接着录"
                : "；亮屏后接着录")
                + BlackBox.afterScreenOff());
        // 「车机要睡」先于停录写下：调度当场置「正在按次序关」，停录放开 RECORDING 时它已经不再保持。安卓亮着时写着的清掉
        CameraNeeds needs = CameraNeeds.current();
        ScreenOffPlan.SleepFact fact = ScreenOffPlan.sleepFact(androidOff, decision.action, needs.headUnitSleeps());
        if (fact == ScreenOffPlan.SleepFact.WRITE) {
            needs.setHeadUnitSleeps(true);
        } else if (fact == ScreenOffPlan.SleepFact.CLEAR) {
            needs.setHeadUnitSleeps(false);
            BlackBox.note("车机醒着（安卓亮着）：撤掉「车机要睡」，通道调度照常保持、开相机");
        }
        switch (decision.action) {
            case CONTINUE:
                cancelScreenOffStop();
                break;
            case STOP_LATER:
                if (!busy) {
                    break;   // 没在录：没有可停的，车机醒着，相机照登记表
                }
                if (check == DarkCheck.STOP_DUE) {
                    stopForScreenOff(decision.resumesOnWake, check);
                } else if (screenOffStop == null) {
                    screenOffStop = () -> {
                        screenOffStop = null;
                        // 到点再看一眼：屏幕其实亮了、录像早停了 —— 都不停；还在录就照表再判（等的这几秒里开关被打开了，就接着录）
                        if (ScreenState.dark() && (isRecording() || pending != null)) {
                            decideWhileDark(DarkCheck.STOP_DUE, ScreenState.androidOff());
                        }
                    };
                    screenOffStopAtMs = now + SCREEN_OFF_STOP_MS;
                    main.postDelayed(screenOffStop, SCREEN_OFF_STOP_MS);
                }
                break;
            case STOP_RELEASED:
                // 通道放开了环视：在录、在等开录、在等自动接回的现在停（「这一趟要录」照表留）；没在录的没有可停的，相机由通道关
                if (busy) {
                    stopForScreenOff(decision.resumesOnWake, check);
                }
                break;
            default:
                stopForScreenOff(decision.resumesOnWake, check);
                break;
        }
        return decision.action;
    }

    /**
     * 因熄屏停录（现在停、10 秒到点）：亮屏接不接先记下，{@link #settleWanted} 照它留不留「这一趟要录」。
     * 开发者的唤醒锁还拿着的（到点那一次、录像停了不接回或者不等自动接回了的那一次，{@link #letLockGo}），
     * 留到相机按次序关完再放，不在停录那一步当场放；放锁那一行写这一次为什么判（{@link DarkCheck#lockWhy}）。
     */
    private void stopForScreenOff(boolean resumes, DarkCheck check) {
        cancelScreenOffStop();
        screenOffResumes = resumes;
        ScreenOffRecording.holdUntilCamerasClosed(check.lockWhy);
        stop(RecordingStops.Reason.SCREEN_OFF);
    }

    private void cancelScreenOffStop() {
        if (screenOffStop != null) {
            main.removeCallbacks(screenOffStop);
            screenOffStop = null;
        }
    }

    /**
     * 车辆信号变了（主线程；进程活着一直听着）。只看哨兵模式、挡位：车速一停一走不算 —— 等红灯不是停车。第一次读到只记下
     * （不算变）。熄屏期间接着录的（不是唤醒锁拉着的那种，{@link ScreenOffPlan#rejudgesOnVehicleChange}）照熄屏那张表再判一次 ——
     * 只在安卓熄屏着时（车机要睡时在开车、哨兵模式开着）：车机醒着时它改不了结果（判不出现在停），哨兵模式变了归通道管
     * （{@link #onChannelVerdict}，2026-10-27）。
     */
    private void onVehicleChanged() {
        VehicleState vehicle = Telemetry.get().latest();
        long now = android.os.SystemClock.elapsedRealtime();
        boolean changed = false;
        if (sentrySinceMs == 0 || !Objects.equals(vehicle.sentry, seenSentry)) {
            changed = sentrySinceMs != 0;
            seenSentry = vehicle.sentry;
            sentrySinceMs = now;
        }
        if (gearSinceMs == 0 || !Objects.equals(vehicle.gear, seenGear)) {
            changed |= gearSinceMs != 0;
            seenGear = vehicle.gear;
            gearSinceMs = now;
        }
        if (changed && ScreenOffPlan.rejudgesOnVehicleChange(darkAction, darkActionByLock, ScreenState.androidOff())
                && ScreenState.dark() && (isRecording() || pending != null)) {
            decideWhileDark(DarkCheck.VEHICLE_CHANGED, ScreenState.androidOff());
        }
    }

    /**
     * 亮屏了（ScreenState 在主线程调）：「车机要睡」写着就清（车机醒着了，调度照常保持、开相机，{@link ScreenOffPlan#sleepFact}）；
     * 熄屏这一段的小结写一行；这一趟要录而眼下没在录的（因熄屏停下的、进程被车机结束过的），接着录（{@link #resumeIfWanted}）；
     * 黑着时开起来、用不了相机一直「等待摄像头」的，照开录时那条规矩拉主界面（{@link #bringUpMainIfCamerasUnusable}）。
     * 座舱不用这里管：亮屏了相机层又要它们，开出画面就各自接回录像。
     */
    public void screenOn() {
        cancelScreenOffStop();
        darkAction = null;
        CameraNeeds needs = CameraNeeds.current();
        if (ScreenOffPlan.sleepFact(false, null, needs.headUnitSleeps()) == ScreenOffPlan.SleepFact.CLEAR) {
            needs.setHeadUnitSleeps(false);
            BlackBox.note("亮屏：撤掉「车机要睡」，通道调度照常保持、开相机");
        }
        noteDarkSummary(false, false);
        darkSinceElapsedMs = 0;
        darkSinceUptimeMs = 0;
        darkSinceWallMs = 0;
        darkSource = null;
        resume("screen-on", Why.SCREEN_ON);
        if (isRecording()) {
            bringUpMainIfCamerasUnusable(Why.SCREEN_ON);
        }
    }

    /**
     * 熄屏小结（设计 v3 §6 L8、v3.1 C16），一段熄屏最多两行：车机要睡时一行（进程几秒后就可能没了，赶在那之前），亮屏时一行
     * （整段，带上车机睡了多久）。替代以前亮屏时那一行「亮屏：熄屏这一段结束…」（那一行只在熄屏时在录才有，哨兵模式开着、
     * 安卓一直不熄屏的那一种一行都没有）。「共」「车机睡了」用 elapsedRealtime（含深睡）减 uptime（不含深睡）；
     * 座舱放开完用相机层记的那一刻（{@link MultiCameraManager#cabinsReleasedAtElapsed}，elapsedRealtime；平常是熄屏后约 30 秒，
     * 通道一直没把这一段算作黑就亮了的写「没到 30 秒，座舱没动」，{@link MultiCameraManager#channelCountedDark}）。
     * 录像停、开的次数照 {@link RecordingStops.DarkTally}：中途停过、熄屏停录（照表的、通道放开的，每一次都数，第一次的时刻）、
     * 熄屏中哨兵模式变成开后接着录。
     *
     * @param atSleep      车机要睡时写（否则亮屏时）
     * @param withDarkEdge 车机要睡和熄屏是同一下（车辆信号读不到，安卓熄屏就是两样一起来）：座舱还没开始关，相机层这一段还没起算
     */
    private void noteDarkSummary(boolean atSleep, boolean withDarkEdge) {
        if (darkSinceElapsedMs == 0) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long totalMs = now - darkSinceElapsedMs;
        long sleptMs = Math.max(0L, totalMs - (android.os.SystemClock.uptimeMillis() - darkSinceUptimeMs));
        long waitedMs = darkWaitedMs + (shownView == View.WAITING && viewSinceMs > 0
                ? Math.max(0L, now - Math.max(viewSinceMs, darkSinceElapsedMs)) : 0L);
        boolean recordingNow = isRecording();
        boolean allAlong = busyAtDark && !darkTally.anyStop() && darkWaits == 0 && recordingNow;
        MultiCameraManager manager = cameraManager;
        long releasedAt = manager == null ? 0 : manager.cabinsReleasedAtElapsed();
        BlackBox.noteImportant("熄屏小结：" + clock(darkSinceWallMs) + " 起（"
                + (darkSource == ScreenState.Source.VEHICLE ? "车辆信号"
                : darkSource == ScreenState.Source.VEHICLE_READ ? "车辆信号，起点不明" : "安卓熄屏")
                + "）到 " + clock(System.currentTimeMillis()) + (atSleep ? " 车机要睡（安卓熄屏）" : " 亮屏")
                + "，共 " + totalMs / 1000 + " 秒" + (sleptMs >= 1000 ? "，车机睡了 " + sleptMs / 1000 + " 秒" : "")
                + (darkWakes > 0 ? "，车机黑着醒过 " + darkWakes + " 次" : "")
                + "；录像：" + (allAlong ? "一直在录"
                : (busyAtDark ? "熄屏时在录" : "熄屏时没在录")
                + (darkTally.midwayStops() > 0
                ? "，中途停过 " + darkTally.midwayStops() + " 次（原因见上面的「录像停止原因」）" : "")
                + (darkWaits > 0 ? "，等待摄像头 " + darkWaits + " 次共 " + waitedMs / 1000 + " 秒" : "")
                + (darkTally.tableStops() > 0 ? "，熄屏停录 " + darkTally.tableStops() + " 次（第一次熄屏后 +"
                + (darkTally.firstTableStopAt() - darkSinceElapsedMs) / 1000 + " 秒）" : "")
                + (darkTally.sentryStarts() > 0 ? "，熄屏中哨兵模式变成开后接着录 " + darkTally.sentryStarts() + " 次" : "")
                + "，现在" + (recordingNow ? "在录" : "没在录"))
                + "；" + (manager == null ? "座舱：没有相机管线"
                : withDarkEdge ? "座舱：和环视一起按次序关（安卓熄屏就是车机要睡）"
                : releasedAt > 0 && releasedAt >= darkSinceElapsedMs
                ? "座舱 熄屏后 +" + (releasedAt - darkSinceElapsedMs) + "ms 放开完"
                : !manager.cabinsOpenAtDark() ? "座舱：熄屏时没开着"
                // 黑满 30 秒才放开座舱（ChannelRules.darkForChannel，项目所有者 2026-10-13）：通道一直没把这一段算作黑
                // （亮屏那一轮还没跑，快照是这一段最后的样子；起点不明的当场就算黑了），之前就亮了，相机一步没动
                : !atSleep && !manager.channelCountedDark()
                ? "没到 " + (com.kooo.evcam.camera.ChannelRules.HOLD_MS / 1000) + " 秒，座舱没动"
                : "座舱：还没关完"));
    }

    /** 墙上时间写成「20:05:46.709」（熄屏小结用）。 */
    private static String clock(long wallMs) {
        return new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new java.util.Date(wallMs));
    }

    /**
     * 这一次录像的计划里有环视 —— 熄屏那张表和录制键上的小字同一个来源（设计 v3 §3.1）：在录时看这一次的计划；没在录、
     * 计划还没定、在等自动接回时看配置里启用的那几路（开录时计划就从它们里定）。计划里没有环视的，黑着时和熄屏持续录制关着一样。
     * 主线程。
     */
    public boolean surroundPlanned() {
        RecordingLanes.Summary s = summary();
        if (s != null && !s.planned.isEmpty()) {
            return s.planned.contains(CameraSlots.KEY_SURROUND);
        }
        return com.kooo.evcam.profile.RecordSpecs.enabledCameraKeys(context).contains(CameraSlots.KEY_SURROUND);
    }

    /**
     * 通道对环视的熄屏决定 —— 协调器只读这一处（{@link ScreenOffPlan#channelVerdict}）：通道还没把屏幕算黑（亮着、黑了不到 30 秒，
     * {@link MultiCameraManager#channelCountsDark}，和调度同一句、同一组事实）时照亮着算，不靠调度跑没跑过一轮；
     * 算黑了读调度发布的那一个（{@link ChannelScheduler#surroundVerdict}：进程在黑着时起来的是放开）。主线程。
     */
    private static ChannelRules.SurroundInDark channelVerdict() {
        return ScreenOffPlan.channelVerdict(MultiCameraManager.channelCountsDark(), ChannelScheduler.surroundVerdict());
    }

    /**
     * 屏幕黑着时录像能不能（再）开起来，开不起来是因为什么（{@link ScreenOffPlan#startsWhileDark}）：车机醒着（安卓亮着）、计划里有环视；
     * 照亮着算时熄屏后不停录就开，黑满 30 秒以后只有熄屏中哨兵模式变成开那一下的接回（{@link Why#SENTRY}）、能用相机才开 ——
     * 只录环视。黑着时来的要录、接回（恢复、亮屏之外的）都照它。
     */
    private ScreenOffPlan.DarkStart darkStartNow(Why why) {
        return ScreenOffPlan.startsWhileDark(channelVerdict(), why == Why.SENTRY, ScreenState.androidOff(),
                ScreenOffPlan.keepsRecording(appConfig), appConfig.isScreenOffKeepRecording(), surroundPlanned(),
                MultiCameraManager.cameraUsableNow());
    }

    /**
     * 通道对环视的熄屏决定变了（{@link ChannelScheduler} 在一轮当中叫，排到主线程上做；做的时候读此刻的决定，多做一次无害）——
     * 照 {@link ScreenOffPlan#onChannel}（项目所有者 2026-10-27）：
     *
     * <ul>
     *   <li>黑着、在录（含等开录、等自动接回）而通道放开了环视 → 熄屏停（{@link DarkCheck#CHANNEL}：在等的不等了，
     *       「这一趟要录」照表留），不写「车机要睡」；</li>
     *   <li>黑着、没在录，熄屏中哨兵模式变成开（布防；醒来读到睡着时被打开的也算）让通道留下了环视 →「这一趟要录」立着就接着录，
     *       只录环视（{@link Why#SENTRY}）。现在用不了相机（前台服务是后台起的）就不开、写一行，亮屏再接 —— 不进「等待摄像头」
     *       （App 自己起不来就没有后续）。</li>
     * </ul>
     *
     * <p>屏幕不知道黑没黑时排到知道了再做（{@code ScreenState.whenKnown}）：不丢下这一次变。</p>
     */
    private void onChannelVerdict() {
        if (!ScreenState.known()) {
            ScreenState.whenKnown(this::onChannelVerdict);
            return;
        }
        boolean busy = isRecordingOrResuming() || pending != null;
        switch (ScreenOffPlan.onChannel(channelVerdict(), ChannelScheduler.surroundVerdictCause(), ScreenState.dark(),
                busy)) {
            case STOP:
                decideWhileDark(DarkCheck.CHANNEL, ScreenState.androidOff());
                break;
            case START:
                if (RecordingIntent.current().recordingWanted()
                        && darkStartNow(Why.SENTRY) == ScreenOffPlan.DarkStart.CAMERA_UNUSABLE) {
                    BlackBox.noteImportant("通道留下了环视（熄屏中哨兵模式变成开或布防），「这一趟要录」立着，但现在用不了相机"
                            + "（前台服务是后台起的）：不开录，亮屏后接着录" + BlackBox.afterScreenOff());
                } else {
                    // 「这一趟要录」立着、没在录：开（只录环视）；不立着的什么都不做
                    resume("sentry", Why.SENTRY);
                }
                break;
            default:
                break;
        }
    }

    /**
     * 这一趟要录（{@link RecordingIntent#recordingWanted}）而眼下没在录、没在等：接着录。接回只有这一个入口 ——
     * 亮屏（{@link #screenOn}）、熄屏中哨兵模式变成开（{@link #onChannelVerdict}）、恢复（Recovery：进程被拉起来、例行、车机醒了）都走它；
     * 进程被车机结束过也接（规格 1.2）。屏幕不知道黑没黑的那一小段（进程刚起来、安卓刚亮在等车辆信号）排到知道了再判
     * （{@code ScreenState.whenKnown}，设计 v3.1 C11）。
     *
     * <p>屏幕黑着时照 {@link #darkStartNow}：车机醒着、计划里有环视，黑了不到 30 秒时熄屏后不停录就接；黑满 30 秒以后只有熄屏中
     * 哨兵模式变成开那一下接（恢复的例行接回、车机醒了都不接）。不接就什么都不做、也不写（C9：恢复每 5 分钟例行来一次）。</p>
     *
     * <p>现在用不了相机（主界面不在前、前台服务是后台起的）时，屏幕亮着就先把主界面拉起来，和别的开录一样在打开录像意图那一下判
     * （{@link #bringUpMainIfCamerasUnusable}）：录像照样当场要，不等主界面起来再要 —— 拉主界面可能被系统悄悄拦下（后台起界面的限制）、
     * 5 秒内重复的那一次也不再拉，那时录像「等待摄像头」，到下一次亮屏再判（审查 2026-10-11）。屏幕黑着不拉（项目所有者 2026-10-13）：
     * 黑满 30 秒以后用不了相机就不开录（{@link #onChannelVerdict} 写一行），亮屏再接。</p>
     *
     * @param where 谁叫的（ASCII，进黑匣子）
     */
    public void resumeIfWanted(String where) {
        ScreenState.whenKnown(() -> resume(where, Why.SCREEN_ON));
    }

    /** {@link #resumeIfWanted} 本身（屏幕已经知道黑没黑）：亮屏、熄屏中哨兵模式变成开直接走这里。 */
    private void resume(String where, Why why) {
        if (isRecording() || pending != null || !RecordingIntent.current().recordingWanted()) {
            return;
        }
        boolean dark = ScreenState.dark();
        if (dark && darkStartNow(why) != ScreenOffPlan.DarkStart.STARTS) {
            return;
        }
        BlackBox.noteImportant("「这一趟要录」还在（" + where
                + (lastStopReason == null ? "，上一个进程留下的" : "，" + lastStopReason + " 停的") + "）：接着录"
                + (!dark ? "" : MultiCameraManager.channelCountsDark() ? "（屏幕黑着，只录环视）"
                : "（屏幕黑了不到 " + (com.kooo.evcam.camera.ChannelRules.HOLD_MS / 1000)
                + " 秒，座舱不开，开着的录到黑满 " + (com.kooo.evcam.camera.ChannelRules.HOLD_MS / 1000) + " 秒）"));
        request(why);
    }

    // ================================================================= 开

    /**
     * 开录用哪一份管线：进程里那一份（{@link CameraManagerHolder#getOrInit}；大纲 §2 阶段 6：管线属于应用，只在那里建，
     * 主界面在不在都一样）。还没有就让它建；还在建（相机列表、合成流探测在相机线程上做）就当还没有，建好那一下马上再看
     * （{@link CameraManagerHolder#whenBuilt}，不等 {@link #POLL_MS} 的下一轮 —— 管线一般几百毫秒就建好，冷启动开录不该白等两秒）。
     * 换了一份（换车型重建了）就交过来 —— 以前从 Holder 里拿现成的，没有就一直等到主界面把它建出来。
     */
    private MultiCameraManager currentManager() {
        CameraManagerHolder holder = CameraManagerHolder.getInstance();
        MultiCameraManager held = holder.getOrInit(context);
        if (held != cameraManager) {
            setCameraManager(held);
        }
        if (held.isBuilt()) {
            return held;
        }
        if (awaitedBuild != held) {
            awaitedBuild = held;
            holder.whenBuilt(held, built -> {
                awaitedBuild = null;
                if (pending != null) {
                    main.removeCallbacks(poll);
                    main.post(poll);
                }
            });
        }
        return null;
    }

    /** 真正去开：先查盘。查盘在存储线程上，查完回主线程接着开。 */
    private void start(Why why) {
        // 上一次查盘到点还没回来（盘掉线时探测卡在存储线程上，后面排着的也回不来）：判了自动接回时留着的相机、
        // 唤醒锁不跟着一直拿着 —— 放掉，「要录」留着、接着等（审查 2026-10-11）。熄屏中照表把等的也停了，就不再查
        if (resumeHold.checkStuck(checkSentAtMs, android.os.SystemClock.elapsedRealtime())) {
            dropResumeHold("check-stuck");
            if (pending == null) {
                return;
            }
        }
        // 录哪几路 = 配置里启用了哪几路。这两件事本来就是同一件：关掉的相机不开、不录、不占流
        Set<String> cameras = com.kooo.evcam.profile.RecordSpecs.enabledCameraKeys(context);
        if (cameras.isEmpty()) {
            refused(why, context.getString(R.string.msg_keep_one_camera_refuse), false);
            return;
        }
        // 查盘是异步的：查完回来就接着开（或者拒了再等）。万一一直不回来，到点再看一次，别一直等下去
        main.removeCallbacks(poll);
        main.postDelayed(poll, REFUSED_RETRY_MS);
        if (checkSentAtMs == 0) {
            checkSentAtMs = android.os.SystemClock.elapsedRealtime();
        }
        StorageState.refresh(context, "start", snapshot -> {
            checkSentAtMs = 0;
            startWith(snapshot, cameras, why, true);
        });
    }

    /**
     * 查过盘了：打开录像意图。相机层定下这次录哪几路、建录制器、声明各路的录像输出；
     * 每一路带着录像输出出了画面就加入，落定了报上来（{@link #pushView}）。屏幕亮着、现在用不了相机时先把主界面拉起来
     * （{@link #bringUpMainIfCamerasUnusable}）：哪一条路要的录像都在这一处判。
     *
     * @param checkStorage false：刚为这次开录清理过空间，不再检查一遍 ——
     *                     否则估算的余量稍有变化就会清完又清，一直开不起来
     */
    private void startWith(StorageState.Snapshot storage, Set<String> cameras, Why why, boolean checkStorage) {
        // 查盘这段时间里人停了、退出了（不等了）：不开
        if (pending == null) {
            return;
        }
        MultiCameraManager manager = currentManager();
        if (!lifecycle.isIdle() || manager == null) {
            // 已经开起来了（轮询会把「等」清掉），或者还没有管线：接着等
            main.removeCallbacks(poll);
            main.postDelayed(poll, POLL_MS);
            return;
        }
        // 正常模式下不往内置存储录：行车记录是一直在写的，而车机闪存换不了。
        // 判断放在这里，所有入口才是同一个答案（以前只有按按钮那条路拦得住）
        if (!storage.available) {
            refused(why, context.getString(R.string.msg_refuse_no_external), true);
            return;
        }
        // 在等 U 盘的，等到了（项目所有者 2026-10-13「U 盘一可用就开录」）：等了多久写一行。只说盘能用了，不说开录 ——
        // 下面还要查写不写得下（盘满了拒的不进黑匣子），开没开看后面「录像意图开」那一行
        long waitedForDisk = diskWait.ready(android.os.SystemClock.elapsedRealtime());
        if (waitedForDisk >= 0) {
            BlackBox.noteImportant("U 盘能用了：开录（" + why + "）等了 " + String.format(Locale.US, "%.1f",
                    waitedForDisk / 1000f) + " 秒（盘不能用时头 " + (RecordingStops.DiskWait.FAST_FOR_MS / 60_000)
                    + " 分钟每 " + (RecordingStops.DiskWait.FAST_EVERY_MS / 1000) + " 秒查一次）；此刻挂着的盘："
                    + storage.mounts);
        }
        // 开录前先确认写得下：以前不查，盘满时照常开录，第一笔数据写不进去，按钮就卡在「正在准备」
        if (checkStorage && !ensureRoomToStart(storage, cameras, why)) {
            return;
        }
        // 从这里起算「在录」（开录中）：录像意图开了，到这一步才清掉「等」
        lifecycle.begin();
        pending = null;
        main.removeCallbacks(poll);
        // 判了自动接回时留着的几样（相机、唤醒锁）从这里起是这一次录像的：下面再登记一遍无害，什么都不放
        resumeHold.finish();
        attemptManager = manager;
        startedAtMs = android.os.SystemClock.elapsedRealtime();
        lastStopReason = null;
        slowWriteNoticedThisRecording = false;
        settleReported = false;
        viewSinceMs = 0;
        recordedMs = 0;
        waitedMs = 0;
        // 自动接回每开一次就算一次：连着 3 次都没录满一分钟就不再试（{@link RecordingStops.ResumeBudget}）
        if (why == Why.RESUME) {
            budget.noteAttempt();
        }
        BlackBox.noteImportant("录像意图开（" + why + (why == Why.RESUME ? "，第 " + budget.attempts() + " 次自动接回" : "")
                + "）：计划 " + roles(cameras) + "，写到 " + storage.videoDir
                + "；不等环视，每一路带着录像输出出了画面就加入");
        // 录像意图开着才登记「录像要用相机」：相机关着的那几路，调度开的时候就带着录像输出
        CameraNeeds.current().claim(CameraNeeds.Holder.RECORDING);
        // 这一趟要录过了：开录失败也算，失败了照样接回（规格 2.3）
        RecordingIntent.current().noteRecordingStarted();
        // 在录就立着「这一趟要录」：等的时候被清过（人点开主界面是新的一趟）也一样，录着的进程被结束了要接得回来
        noteWanted(true, why.name());
        if (!manager.startRecording(cameras, storage)) {
            // 准备就失败了：准备到一半的录制器、录像输出照样由停录那条路收拾
            BlackBox.noteImportant("开录失败：相机层没能开始（" + why + "）");
            end(RecordingStops.Reason.START_FAILED);
            return;
        }
        if (why == Why.SENTRY && darkSinceElapsedMs > 0) {
            // 熄屏小结：熄屏中哨兵模式变成开、通道留下环视后接着录，真开起来的这一次
            darkTally.sentryStarted();
        }
        // 屏幕已经黑着才开始的录像（熄屏期间接回的那种），熄屏录制同样要拿锁
        ScreenOffRecording.onRecordingStarted(context);
        AppLog.d(TAG, "开始录制 " + cameras.size() + " 路: " + cameras);
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onRecordingStarted();
        }
        pushView();
        // 前台服务：没有它，系统会在应用退到后台后把录制掐掉。已经在的不再 start 一次 —— 通知上的字由 pushView 换；
        // 不在的起来时照此刻的样子写（notificationTitle）
        if (!CameraForegroundService.isRunning()) {
            CameraForegroundService.start(context);
        }
        // 现在用不了相机：屏幕亮着就先把主界面拉起来，录像照样现在就开（黑着不拉，等亮屏）。不放在 request 里：悬浮按钮拉起的
        // 主界面在 onCreate 里就要录，那时还没 onResume、算不在前；查盘总是异步回来的，到这一步它已经在前了
        bringUpMainIfCamerasUnusable(why);
    }

    /**
     * 录像意图开着、屏幕亮着、现在用不了相机（主界面不在前、前台服务是后台起的 —— 安卓 12 起后台才起的前台服务用不了相机；
     * 和通道调度读的同一对事实，{@link MultiCameraManager#cameraUsableNow}）：先把主界面拉起来（{@link Recovery#bringUpMainForRecording}）。
     * 不拉的话录像输出声明了、相机却开不起来，录像一直「等待摄像头」，直到人自己打开主界面；拉了，它一起来、刷新了前台服务，
     * 调度就带着录像输出开。
     *
     * <p>一条规矩，在它可能变成真的两下判：打开录像意图那一下（{@link #startWith}：人按的、悬浮按钮、启动自动录制、亮屏接回、
     * 存储一类的自动接回、黑着时照表开的，都经过那里；审查 2026-10-11：以前只有亮屏接回拉，悬浮按钮在后台开录、前台服务是后台起的
     * 就一直「等待摄像头」），和亮屏那一下（{@link #screenOn}：黑着时开起来的录像）。</p>
     *
     * <p>屏幕黑着一律不拉（项目所有者 2026-10-13：熄屏时不拉主界面；前后座舱不录、环视照设置录或不录，都由通道调度那一处管）：
     * 黑了不到 30 秒时照以前的规矩开起来的录像用不了相机就「等待摄像头」，亮屏时这里再判；黑满 30 秒以后用不了相机不开录
     * （{@link #onChannelVerdict}，项目所有者 2026-10-27）。</p>
     */
    private void bringUpMainIfCamerasUnusable(Why why) {
        if (!ScreenState.dark() && !MultiCameraManager.cameraUsableNow()) {
            Recovery.bringUpMainForRecording(context, why.name());
        }
    }

    /**
     * 空间够就返回 true。不够时：没设上限直接拒绝（不删录像）；设了上限就在后台清理，
     * 清完再开一次，这次返回 false。
     */
    private boolean ensureRoomToStart(StorageState.Snapshot storage, Set<String> cameras, Why why) {
        java.io.File dir = storage.videoDir;
        long free = storage.freeBytes;
        if (dir == null || free < 0 || free >= com.kooo.evcam.camera.StorageGuard.lastMarginBytes()) {
            return true;
        }
        if (appConfig.getVideoStorageLimitGb() <= 0) {
            refused(why, context.getString(R.string.msg_storage_full_refuse), false);
            return false;
        }
        AppLog.i(TAG, "开录前空间不够，先清理最旧的录像");
        // 清理出错时不会回来（StorageGuard 只记日志）：到点再看一次
        main.removeCallbacks(poll);
        main.postDelayed(poll, REFUSED_RETRY_MS);
        // 「正在删除最旧的录像」等真要删时才弹：一个都删不掉（全锁着、删光也不够）时不该先说在删
        com.kooo.evcam.camera.StorageGuard.enforceAsync(context, dir,
                () -> notifyRefused(context.getString(R.string.msg_storage_cleaning)), decision -> {
            if (pending == null) {
                return;  // 清理的这段时间里人停了、退出了
            }
            if (decision.verdict == com.kooo.evcam.camera.StoragePlan.Verdict.FULL) {
                refused(why, context.getString(decision.lockedFull
                        ? R.string.msg_storage_locked_refuse : R.string.msg_storage_cannot_free), false);
            } else {
                startWith(storage, cameras, why, false);
            }
        });
        return false;
    }

    /**
     * 条件不满足：告诉界面，然后留在「要录」的状态里，过一会儿再看（U 盘插上、盘清出来了都算）。
     * 自动接回查过盘被拒了，就是确定这一次不接了：之后是等，等的不登记 —— 留着的几样放掉（{@link #dropResumeHold}）。
     *
     * <p>U 盘不能用拒的（{@code noDisk}）：盘一能用就开（项目所有者 2026-10-13）—— 这台车机收不到 U 盘插拔的广播，
     * 所以等盘的头 2 分钟每 2 秒再查一次，之后照旧 {@link #REFUSED_RETRY_MS}（{@link RecordingStops.DiskWait}）。
     * 查得勤的那一段里同一个原因不再告诉界面（界面本来也只说一次），黑匣子不为每一次查写行（存储快照只在盘的样子变了时写）；
     * 等到了，开录那一下写一行等了多久（{@link #startWith}）。别的原因（盘满、一路都没选）照旧 30 秒一次。</p>
     */
    private void refused(Why why, String reason, boolean noDisk) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!(noDisk && diskWait.refused(now))) {
            notifyRefused(reason);
        }
        pending = why;
        main.removeCallbacks(poll);
        main.postDelayed(poll, noDisk ? diskWait.nextCheckMs(now, REFUSED_RETRY_MS) : REFUSED_RETRY_MS);
        dropResumeHold("refused");
    }

    // ================================================================= 停

    /**
     * 停。停的人说清原因；接不接、什么时候接，这里判。主线程调。
     *
     * <p>人停的、退出的：不再等。熄屏停的：等亮屏（{@link #screenOn}）或者熄屏中哨兵模式变成开（{@link #onChannelVerdict}，只录环视），
     * 接不接停的那一刻照表定好了。
     * 这两种停在等自动接回的那一段里，留着的几样一起放（{@link #cancelPending}）。
     * 写不进、没写进、开录失败、录制器自己停了（存储一类）：收拾完、查过盘就自动接回，有额度。
     * 盘满：接回去也录不下，不接。摄像头被拿走、断开不是停，不会走到这里。</p>
     */
    public void stop(RecordingStops.Reason reason) {
        boolean wasRecording = isRecording();
        if (!wasRecording && pending == null) {
            return;
        }
        // 人停的：不再等。熄屏停的：也不再等（照表停了、通道放开了，这一阵不该自己录起来），亮屏、熄屏中哨兵模式变成开时再判
        if (reason == RecordingStops.Reason.USER || reason == RecordingStops.Reason.SCREEN_OFF) {
            if (!wasRecording) {
                // 只是在等开录（没登记相机）：「这一趟要录」留不留先定，前台服务跟着收（等着接回时为它留着的）——
                // 和停录同一个次序（见 end）
                lastStopReason = reason;
                settleWanted(reason);
                CameraForegroundService.stop(context);
                cancelPending(reason.name());
                return;
            }
            cancelPending(reason.name());
        }
        if (!wasRecording) {
            return;
        }
        end(reason);
    }

    /**
     * 这一次录像到头了 —— 唯一的停录路径。开录中、在录都从这里停：人停的、被打断、开录失败、管线没了。
     *
     * <p>以前停录先问相机层在不在录，一路都没起来时它说没有，于是通知、悬浮按钮、唤醒锁都没人收，
     * 也不接回（2026-10-05）。现在不问：这里说在录就收，相机层按同一套收拾（开录走到哪一步都一样），
     * 收拾完了报上来（{@link #settled}）才开下一次。</p>
     *
     * <p>判了自动接回的（存储一类）：「录像要用相机」、熄屏录制的唤醒锁都留着（{@link RecordingStops.ResumeHold}，
     * 大纲 §7 第 8 问，项目所有者 2026-10-11「留」）—— 相机不放、停放的录制器留在会话里，接回开录时直接加入，存储停不动通道；
     * 锁拉着车机的照样拉着。接回开录了交给那一次录像（{@link #startWith}），确定不接了才放（{@link #dropResumeHold}）。
     * 以前停的那一下全放：熄屏时调度当场按次序关相机、接回时再开一轮，中间几秒到几十秒没录；锁一放车机几秒就睡。</p>
     *
     * <p>不接回的（人停的、盘满、额度用完……）当场放；熄屏录制的唤醒锁和不接了的那一次走同一条路（{@link #letLockGo}）：
     * 屏幕黑着、锁正拉着车机的先照熄屏那张表重判，车机要睡的锁留到相机按次序关完。</p>
     */
    private void end(RecordingStops.Reason reason) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!lifecycle.stop(now)) {
            return;
        }
        if (attemptManager != null) {
            attemptManager.stopRecording();
        }
        main.removeCallbacks(stopDeadline);
        main.postDelayed(stopDeadline, RecordingLifecycle.STOP_DEADLINE_MS);

        long lasted = startedAtMs > 0 ? now - startedAtMs : 0;
        account(now);
        startedAtMs = 0;
        lastStopReason = reason;
        if (darkSinceElapsedMs > 0) {
            // 熄屏小结：熄屏停的（10 秒到点、车机要睡、通道放开了环视）单独数，不算「中途停过」
            darkTally.stopped(reason, now);
        }
        // 录满一分钟说的是有一路在录满一分钟：一路都不在录（等待摄像头）、开录中的那几段不算
        budget.noteRecordingLasted(recordedMs);
        BlackBox.noteImportant("录像停止原因: " + reason + "，这一段录了 " + (lasted / 1000) + " 秒（有一路在录 "
                + recordedMs / 1000 + " 秒" + (waitedMs > 0 ? "，一路都不在录 " + waitedMs / 1000 + " 秒" : "") + "）"
                + BlackBox.afterScreenOff());
        AppLog.d(TAG, "录制已停止（" + reason + "）");

        boolean willResume = resumeAfter(reason);
        // 判了自动接回：录像要的几样留到接回开录、或者确定不接了
        resumeHold.onStopped(willResume);
        // 不接回、也没别人在等：这一次连同「要录」都到头了
        boolean letGo = !willResume && pending == null;
        if (letGo) {
            settleWanted(reason);
        }
        if (!willResume) {
            // 熄屏录制的唤醒锁只在录像期间拿（规格 §3.1）：屏幕黑着、锁正拉着车机的先照熄屏那张表重判再放
            letLockGo(DarkCheck.ENDED);
        }
        // 停录的次序：「这一趟要录」留不留 → 前台服务 → 放开相机。等着接回（这一趟要录）时前台服务留着 ——
        // 亮屏后从后台开相机要有它；不接了、开机自启动又关着的先停服务再放相机：调度看到没有前台服务，
        // 没人要的相机当场按次序关，不在后台拿着相机保持 30 秒
        CameraForegroundService.stop(context);
        if (!willResume) {
            CameraNeeds.current().release(CameraNeeds.Holder.RECORDING);
        }
        pushView();
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onRecordingStopped(reason, lasted, willResume);
        }
    }

    /** 停录中，管线收拾了太久还没报完：不等了，免得再也开不起来。 */
    private final Runnable stopDeadline = () -> {
        if (lifecycle.stopOverdue(android.os.SystemClock.elapsedRealtime())) {
            BlackBox.noteImportant("停录收拾了 " + (RecordingLifecycle.STOP_DEADLINE_MS / 1000)
                    + " 秒还没报完，不等了");
            settled("deadline");
        }
    };

    /** 上一次收拾完了（停录中 → 空闲）：等着开录的现在就去看。 */
    private void settled(String why) {
        main.removeCallbacks(stopDeadline);
        attemptManager = null;
        AppLog.d(TAG, "停录收拾完（" + why + "）");
        if (pending != null) {
            main.removeCallbacks(poll);
            main.post(poll);
        }
    }

    /**
     * 录像因为这个原因停了，要不要自动接回（大纲 §7 第 3 问：存储一类照旧接回）。
     *
     * <p>总原则（规格 §0）：用户开着录像，App 就该一直录着 —— 手动开的、自动开的都算，
     * 只要这一趟录起来过、不是人停的。每一次自动接回都计入额度：额度防的是「接回去又立刻停」的循环，
     * 连着 3 次都没有一路录满一分钟就不再试。不再等环视：收拾完、查过盘就重开。</p>
     */
    private boolean resumeAfter(RecordingStops.Reason reason) {
        if (!RecordingStops.resumesAutomatically(reason)) {
            return false;
        }
        if (!RecordingIntent.current().shouldRestore(true)) {
            BlackBox.noteImportant("录像被打断（" + reason + "），不自动接回");
            return false;
        }
        if (!budget.allows()) {
            BlackBox.noteImportant("录像被打断（" + reason + "），自动恢复已连续 "
                    + budget.attempts() + " 次没录满一分钟，不再尝试");
            return false;
        }
        BlackBox.noteImportant("录像被打断（" + reason + "）：收拾完、查过盘就自动接回（已试 "
                + budget.attempts() + " 次）；相机留着等接回");
        request(Why.RESUME);
        return true;
    }

    /**
     * 不接了（主线程）：人停了、退出了、熄屏照表停了（{@link #cancelPending}），自动接回查过盘被拒了（{@link #refused}），
     * 接回前的查盘卡住了（{@link #start}）—— 判了自动接回时留着的几样放掉（{@link RecordingStops.ResumeHold}，大纲 §7 第 8 问）。
     * 没留着什么都不做，叫几次都一样。
     *
     * <p>次序和停录一样：「这一趟要录」留不留、前台服务（调用方已经定了）→ 唤醒锁（{@link #letLockGo}：屏幕黑着、锁正拉着车机的
     * 先照熄屏那张表重判）→ 放开相机。先不算留着，再判：锁不算拉着车机了。</p>
     *
     * @param why 为什么不接了（ASCII，进黑匣子）
     */
    private void dropResumeHold(String why) {
        if (!resumeHold.finish()) {
            return;
        }
        BlackBox.noteImportant("不等自动接回了（" + why + "）：留着的相机放开");
        letLockGo(DarkCheck.RESUME_DROPPED);
        CameraNeeds.current().release(CameraNeeds.Holder.RECORDING);
    }

    /**
     * 录像要的几样放了（录像停了、不接回 {@link #end}；不等自动接回了 {@link #dropResumeHold}）：熄屏录制的唤醒锁怎么放 ——
     * 两处同一条路（审查 2026-10-11；以前录像停了不接回就当场放，不经熄屏那张表）。
     *
     * <p>屏幕黑着、锁正拉着车机（{@link RecordingStops#rejudgesBeforeLockGoes}）：锁一放车机几秒就睡，先照熄屏那张表重判
     * （锁不算拉着了，和到点那一次一样，{@link #lockTimedOut}）—— 车机要睡的写下「车机要睡」、停掉还在等的，
     * 锁留到相机按次序关完再放；车机醒着的锁当场放。别的当场放，已经在等相机关完的那一次留到关完。
     * 「拉着」看的是锁本身（{@link ScreenOffRecording#pullsCarAwake}），不是时限：时限刚过、到点那一下还没轮到主线程时，
     * 锁还拉着车机。</p>
     *
     * <p>调用方在放「录像要用相机」之前叫：「车机要睡」要先于放 RECORDING 写下（调度当场置「正在按次序关」，放锁之前要读它）。
     * 车机醒着（安卓亮着）时判不出现在停，锁当场放，环视照表录到车机要睡那一次再判。</p>
     */
    private void letLockGo(DarkCheck check) {
        if (RecordingStops.rejudgesBeforeLockGoes(ScreenState.dark(), ScreenOffRecording.pullsCarAwake())) {
            // 判成现在停的：stopForScreenOff 让锁等相机按次序关完再放；还在等的停掉时又叫到 dropResumeHold，那时已经不留着了
            if (decideWhileDark(check, ScreenState.androidOff()) != ScreenOffPlan.Action.STOP_NOW) {
                ScreenOffRecording.release(check.lockWhy);
            }
        } else {
            ScreenOffRecording.onRecordingStopped(check.lockWhy);
        }
    }

    /**
     * 停了、也不等了（主线程）：「这一趟要录」留不留。因熄屏停下、亮屏要接的留着（{@link #resumeIfWanted}，
     * 进程被车机结束了也接）；别的停 —— 人停的、不接回的 —— 都撤。前台服务随后跟着它收（{@link CameraForegroundService#stop}）。
     */
    private void settleWanted(RecordingStops.Reason reason) {
        noteWanted(reason == RecordingStops.Reason.SCREEN_OFF && screenOffResumes, reason.name());
    }

    /** 立、撤「这一趟要录」（{@link RecordingIntent#recordingWanted}，落盘）：变了才记黑匣子一行。 */
    private void noteWanted(boolean wanted, String why) {
        if (RecordingIntent.current().noteRecordingWanted(wanted)) {
            BlackBox.noteImportant(wanted ? "记下「这一趟要录」（" + why + "）：进程被结束了，屏幕亮着时也接着录"
                    : "撤掉「这一趟要录」（" + why + "）");
        }
    }

    /**
     * 录制器报写盘跟不上（主线程）：某一路的写入排队满了，相机这一侧开始丢帧。不停录 —— 录像照常，
     * 只是可能丢帧；提示一句（{@link Listener#onWriteSlow}），一次录像最多一次，两次至少隔
     * {@link #SLOW_WRITE_NOTICE_GAP_MS}。满了多久、丢了几帧，录制器自己记黑匣子。
     */
    private void onWriteBacklog(String cameraId) {
        if (!isRecording()) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (slowWriteNoticedThisRecording
                || (slowWriteNoticedAtMs != 0 && now - slowWriteNoticedAtMs < SLOW_WRITE_NOTICE_GAP_MS)) {
            return;
        }
        slowWriteNoticedThisRecording = true;
        slowWriteNoticedAtMs = now;
        BlackBox.noteImportant("提示用户：U 盘写入跟不上（相机 " + cameraId + " 的写入排队满了，开始丢帧）");
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onWriteSlow();
        }
    }

    /** 人自己开了：前面的失败都不算了。 */
    public void resetBudget() {
        budget.reset();
    }

    public int resumeAttempts() {
        return budget.attempts();
    }

    private void notifyRefused(String reason) {
        AppLog.w(TAG, "不满足录制条件：" + reason);
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onRecordingRefused(reason);
        }
    }
}
