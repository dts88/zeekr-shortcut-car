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
 *   <li><b>登记</b>：「录像要用相机」（{@link CameraNeeds.Holder#RECORDING}）和车辆信号在录像意图开着时登记；
 *       存储一类整次停之后判了自动接回的，连同熄屏录制的唤醒锁一起留到接回开录、或者确定不接了
 *       （{@link RecordingStops.ResumeHold}，大纲 §7 第 8 问：存储停不动通道，停放的录制器接回时直接加入）。
 *       别的在等开录的（等 U 盘、等上一次收拾完）不登记，相机不为它白白出画面，GPS 也不一直开着；</li>
 *   <li><b>停只有一条路</b>（{@link #end}）：人停的、被打断、开录失败、录着的那一份管线没了，
 *       都在这里收通知、悬浮按钮、唤醒锁，让相机层按同一套收拾（{@link MultiCameraManager#stopRecording()}），
 *       收拾完了才开下一次；</li>
 *   <li><b>熄屏</b>照 {@link ScreenOffPlan} 一张表：接着录、10 秒后停，或者现在停（哨兵模式没开、车没在走，2026-10-10）；
 *       「这一趟要录」落盘（{@link RecordingIntent#recordingWanted}），亮屏、进程被结束后在屏幕亮着时被拉起来，
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
    /** 条件不满足（没 U 盘、盘满）时多久再试：不必两秒一次地去探盘。 */
    static final long REFUSED_RETRY_MS = 30_000L;

    /** 谁要录 —— 只进日志。 */
    public enum Why {
        USER, FLOATING, AUTO_START, RESUME, SCREEN_ON
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
         * @param liveKeys   落定时在录的几路；空 = 一路都没在录（等待摄像头）
         * @param planned    这次计划录的几路
         * @param sdFellBack 用户选了 U 盘但没插，这次落到了内置存储
         */
        default void onStartSettled(Collection<String> liveKeys, Collection<String> planned, boolean sdFellBack) {
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
    /** 这一次开录落到了内置存储（用户选了 U 盘但没插）：开录落定时随开录的提示一起告诉界面。 */
    private boolean startedOnFallback;

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
     * 熄屏持续录制关着、而车机醒着（哨兵模式开着、或车在走）时，熄屏后多久停录 —— 唯一的一个缓冲（项目所有者 2026-09-27）。
     * 车机会睡的时候不等它：现在就停（{@link ScreenOffPlan.Action#STOP_NOW}）。
     */
    static final long SCREEN_OFF_STOP_MS = 10_000L;
    private Runnable screenOffStop;
    /** 熄屏那一刻在录（含等接回）：熄屏时刻（含深睡 / 不含深睡）和中途停过几次，亮屏时汇总一行。 */
    private long darkSinceElapsedMs;
    private long darkSinceUptimeMs;
    private int stopsWhileDark;
    /**
     * 这一次熄屏照 {@link ScreenOffPlan#atScreenOff} 定下的做法：熄屏那一刻定，唤醒锁到点、车辆信号变了再定；
     * null = 亮着，或者熄屏那一刻没在录。
     */
    private ScreenOffPlan.Action darkAction;
    /** 上面那个「接着录」是开发者的唤醒锁拉着车机才定的：车辆信号变了不重判，锁到点了再判（{@link #lockTimedOut}）。 */
    private boolean darkActionByLock;
    /** 这一次因熄屏停的录像亮屏接不接：停的那一刻照表定（{@link #stopForScreenOff}），{@link #settleWanted} 照它留不留。 */
    private boolean screenOffResumes;

    /**
     * 向车辆信号登记用的名字：录像意图开着、留着等自动接回时登记（{@link #watchVehicle}）。不能和相机层信息条那份（"recording"）同名：
     * 登记是按名字算的，它停录时一注销，这一份也跟着没了。
     */
    private static final String VEHICLE_USER = "screen-off-plan";
    private boolean watchingVehicle;
    /**
     * 熄屏那张表看的哨兵模式、挡位：上一次看到的值，从什么时候起是这个值（elapsedRealtime，从登记车辆信号起算）。
     * 黑匣子里写「多久没变」；熄屏期间只有它俩变了才重判（{@link #onVehicleChanged}）。
     */
    private Integer seenSentry;
    private String seenGear;
    private long sentrySinceMs;
    private long gearSinceMs;
    private final Telemetry.Listener vehicleListener = readings -> onVehicleChanged();

    /** 熄屏期间为什么判了一次（进黑匣子那一行；判下来要放唤醒锁时，放锁那一行也写它）。 */
    private enum DarkCheck {
        /** 熄屏那一刻。 */
        SCREEN_OFF("screen-off"),
        /** 熄屏 {@link RecordingCoordinator#SCREEN_OFF_STOP_MS} 到了（10 秒后停的那种）。 */
        STOP_DUE("stop-due"),
        /** 开发者熄屏录制的唤醒锁到点。 */
        LOCK_TIMEOUT("timeout"),
        /** 录像停了、不接回，而开发者熄屏录制的唤醒锁正拉着车机（{@link RecordingCoordinator#letLockGo}）。 */
        ENDED("recording-stopped"),
        /** 不等自动接回了，而开发者熄屏录制的唤醒锁正拉着车机（{@link RecordingCoordinator#letLockGo}）。 */
        RESUME_DROPPED("resume-dropped"),
        /** 接着录的时候哨兵模式、挡位变了。 */
        VEHICLE_CHANGED("vehicle-changed");

        /** 这一次判下来放唤醒锁时，放锁那一行写的原因（ASCII，{@link ScreenOffRecording#release}）。 */
        final String lockWhy;

        DarkCheck(String lockWhy) {
            this.lockWhy = lockWhy;
        }
    }

    private RecordingCoordinator(Context context) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(this.context);
    }

    // ================================================================= 接线

    /**
     * 相机管理器换了对象就要重新交一次：它换了而这里还握着旧的，表现是「按了录制，什么都没发生」。
     * 录制管线的几个回调（写不进、盘满、一路加入 / 退出、开录 / 停录走到哪一步）都接到这里，主界面在不在都一样。
     *
     * <p>录着的时候换了一份（车型变了、路数对不上，旧的那份被释放重建）：旧的那份上的录像到头了，
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
     * （{@link RecordingStops.ResumeHold}，大纲 §7 第 8 问）。车辆信号照它登记（{@link #watchVehicle}），
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
            AppLog.i(TAG, "录像的样子：" + shownView + " → " + now);
            liveSinceMs = now == View.RECORDING ? at : 0;
            shownView = now;
            CameraForegroundService.showText(context, titleFor(now), contentFor(now));
            RecordingFloatingService.sendRecordingStateChanged(context);
        }
        if (summary != null && summary.settled && !settleReported) {
            settleReported = true;
            for (Listener listener : new ArrayList<>(listeners)) {
                listener.onStartSettled(summary.liveKeys, summary.planned, startedOnFallback);
            }
        }
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onRecordingViewChanged();
        }
    }

    /**
     * 眼下这个样子（{@link #shownView}）算到 {@code at}（elapsedRealtime）为止：这一次录像里有一路在录一共多久、
     * 一路都不在录（等待摄像头）一共多久。自动恢复的额度只认有一路在录的那几段（{@link #end}）。
     */
    private void account(long at) {
        long span = viewSinceMs > 0 ? at - viewSinceMs : 0;
        if (shownView == View.RECORDING) {
            recordedMs += span;
        } else if (shownView == View.WAITING) {
            waitedMs += span;
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
     * <p>屏幕黑着时不开（channel-logic §五：熄屏时不开相机）：只记下「这一趟要录」，亮屏时 {@link #screenOn} 照它接
     * （§四：醒来时熄屏持续录制或自动录制开着就接着录）。熄屏那张表只在熄屏那一刻、和录着时的重判里判，黑着以后才来的要录
     * 没人判 —— 以前它照样开起来：管线在相机线程上建、比熄屏晚好了，主界面这时才提出「启动自动录制」，录像意图在黑屏里开、
     * 熄屏录制拿着唤醒锁拉住车机，却因为「车机要睡」一路都开不起来（审查 2026-10-11）。唯一的例外是熄屏时照表接着录的那一次
     * 录像被存储一类打断后的自动接回（{@link Why#RESUME}）：它在表允许的那一段里。</p>
     */
    public void request(Why why) {
        if (isRecording()) {
            return;
        }
        if (ScreenState.dark() && why != Why.RESUME) {
            BlackBox.noteImportant("要录像（" + why + "）：屏幕黑着，先不开录，亮屏后接着录");
            noteWanted(true, why.name());
            return;
        }
        if (pending == null) {
            BlackBox.noteImportant("要录像（" + why + "）：查过盘就开录，不等环视");
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
     * 熄屏了（ScreenState 在主线程调）：不管在不在录，都照 {@link ScreenOffPlan#atScreenOff} 判一次、照着做（大纲 §2 阶段 6；
     * 以前没在录就不判）。在录（含等开录）的：接着录、{@link #SCREEN_OFF_STOP_MS} 后停，或者现在停（哨兵模式没开、车没在走：
     * 车机几秒后就断电）；只在等开录的，停就是不等了（黑匣子那一行照实写「接着等 / 不等开录了」）。
     * 判成现在停的，在不在录都写下「车机要睡」（{@link CameraNeeds#setHeadUnitSleeps}）：通道调度从此不保持、
     * 手上那一步最多再等 1 秒，然后按次序关相机（环视先单独关，channel-logic §四，项目所有者 2026-10-10）。没在录（也没留着等自动接回，
     * {@link #isRecordingOrResuming}）时车辆信号没人登记，
     * 读不到按没开算，于是空闲熄屏就是「车机要睡」—— 空闲熄屏本来也不保持、照登记表按次序关，结果一样，只是关得更早、更确定。
     * 手动开的、自动开的一样。这里先于主界面、后视镜的熄屏监听者被叫到（ScreenState 的固定顺序）：调度的那一轮排在它们之后。
     */
    public void screenOff() {
        cancelScreenOffStop();
        darkAction = null;
        if (isRecording() || pending != null) {
            // 熄屏这一段的汇总（亮屏时一行）只记在录、在等的那一段
            darkSinceElapsedMs = android.os.SystemClock.elapsedRealtime();
            darkSinceUptimeMs = android.os.SystemClock.uptimeMillis();
            stopsWhileDark = 0;
        }
        decideWhileDark(DarkCheck.SCREEN_OFF);
    }

    /**
     * 开发者熄屏录制的唤醒锁到点了（ScreenOffRecording 排到主线程上调）：锁不算拉着车机了，照熄屏那张表再判一次，
     * 和熄屏那一刻一样做（2026-10-10，以前到点就放锁，车机带着开着的相机睡过去）。现在停的那种，锁留到相机按次序关完
     * （{@link ScreenOffRecording#holdUntilCamerasClosed}）；接着录、10 秒后停的（车机醒着、或者车在走），锁现在就放。
     * 屏幕黑着就判，在不在录都一样（大纲 §2 阶段 6：每一次熄屏判定都要判）。
     */
    public void lockTimedOut() {
        if (!ScreenState.dark()) {
            ScreenOffRecording.release("timeout");
            return;
        }
        if (decideWhileDark(DarkCheck.LOCK_TIMEOUT) != ScreenOffPlan.Action.STOP_NOW) {
            ScreenOffRecording.release("timeout");
        }
    }

    /**
     * 照熄屏那张表判一次、照着做（主线程；屏幕黑着时）—— 熄屏的唯一裁判：熄屏那一刻、10 秒到点、唤醒锁到点、
     * 车辆信号变了、录像停了不接回或者不等自动接回了而唤醒锁正拉着车机（{@link #letLockGo}）都走这里，
     * 黑匣子一行写清判的时候哨兵模式、挡位是什么、多久没变，判成了什么。
     *
     * <p>判成现在停：先写下「车机要睡」（调度当场置「正在按次序关」，熄屏录制放锁之前要读它），再停录。写不写、清不清只照
     * {@link ScreenOffPlan#sleepFact}：已经写着的不再写，起算时刻不挪。没在录、也没在等开录的，只写这项事实，录像那边没有可做的。</p>
     *
     * @return 判下来的做法
     */
    private ScreenOffPlan.Action decideWhileDark(DarkCheck check) {
        boolean lockHeld = ScreenOffRecording.holdsCarAwake(context);
        VehicleState vehicle = Telemetry.get().latest();
        ScreenOffPlan.Decision decision = ScreenOffPlan.atScreenOff(appConfig, lockHeld, vehicle);
        boolean busy = isRecording() || pending != null;
        // 只在等开录（查盘、等 U 盘、等上一次收拾完；没登记相机，不算录像那一段）：判下来是接着等、不等了，
        // 不是接着录、停录。存储停之后等自动接回的那一段照在录写（相机、车辆信号都留着，大纲 §7 第 8 问）
        boolean waitingOnly = pending != null && !isRecordingOrResuming();
        // 唤醒锁拉着车机时判下来一定是接着录
        darkAction = decision.action;
        darkActionByLock = lockHeld;
        long now = android.os.SystemClock.elapsedRealtime();
        BlackBox.noteImportant((check == DarkCheck.SCREEN_OFF ? (!busy ? "熄屏时没在录像"
                : waitingOnly ? "熄屏时在等开录（" + pending + "）"
                : isRecording() ? "熄屏时在录像" : "熄屏时在等自动接回")
                : check == DarkCheck.STOP_DUE ? "熄屏已 " + (SCREEN_OFF_STOP_MS / 1000) + " 秒"
                : check == DarkCheck.LOCK_TIMEOUT ? "熄屏录制的唤醒锁到点"
                : check == DarkCheck.ENDED ? "熄屏中录像停了、不接回，唤醒锁不再拉着车机"
                : check == DarkCheck.RESUME_DROPPED ? "熄屏中不等自动接回了，唤醒锁不再拉着车机"
                : "熄屏中哨兵模式 / 挡位变了")
                + "：哨兵模式" + (vehicle.sentry == null ? "读不到" : vehicle.sentry == 0 ? "关"
                : vehicle.sentry == 1 ? "开" : "布防")
                + (watchingVehicle ? "（" + (now - sentrySinceMs) / 1000 + " 秒没变）" : "") + "、挡位 "
                + (vehicle.gear == null ? "读不到" : vehicle.gear)
                + (watchingVehicle ? "（" + (now - gearSinceMs) / 1000 + " 秒没变）" : "") + "、车速 "
                + (vehicle.speedKmh == null ? "读不到" : String.format(Locale.US, "%.1f", vehicle.speedKmh))
                + "；熄屏持续录制" + (appConfig.isScreenOffKeepRecording() ? "开" : "关")
                + "、熄屏录制" + (appConfig.isScreenOffRecordingEnabled() ? (lockHeld ? "拉着车机" : "开（锁不拉着车机）")
                : appConfig.isScreenOffRecordingStoredOn() ? "没生效（存着是开，开发者选项没解锁）" : "关")
                + "、启动自动录制" + (appConfig.isAutoStartRecording() ? "开" : "关") + " → "
                + (!busy ? (decision.action == ScreenOffPlan.Action.STOP_NOW
                ? "车机要睡了：相机按次序关（环视先单独关）" : "车机醒着：相机照登记表")
                : waitingOnly ? (decision.action == ScreenOffPlan.Action.CONTINUE ? "接着等，好了就开录"
                : decision.action == ScreenOffPlan.Action.STOP_NOW
                ? "车机要睡了：不等开录了，相机按次序关（环视先单独关）"
                : check == DarkCheck.STOP_DUE ? "不等开录了" : (SCREEN_OFF_STOP_MS / 1000) + " 秒后不等开录了")
                : decision.action == ScreenOffPlan.Action.CONTINUE ? "接着录"
                : decision.action == ScreenOffPlan.Action.STOP_NOW
                ? "车机要睡了：现在停录，相机按次序关（环视先单独关）"
                : check == DarkCheck.STOP_DUE ? "停录" : (SCREEN_OFF_STOP_MS / 1000) + " 秒后停录")
                + (!busy || decision.action == ScreenOffPlan.Action.CONTINUE ? ""
                : decision.resumesOnWake ? "；亮屏后接着录" : "；亮屏后不接")
                + BlackBox.afterScreenOff());
        // 「车机要睡」先于停录写下：调度当场置「正在按次序关」，停录放开 RECORDING 时它已经不再保持
        CameraNeeds needs = CameraNeeds.current();
        if (ScreenOffPlan.sleepFact(true, decision.action, needs.headUnitSleeps()) == ScreenOffPlan.SleepFact.WRITE) {
            needs.setHeadUnitSleeps(true);
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
                        if (ScreenState.refresh() && (isRecording() || pending != null)) {
                            decideWhileDark(DarkCheck.STOP_DUE);
                        }
                    };
                    main.postDelayed(screenOffStop, SCREEN_OFF_STOP_MS);
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
     * 车辆信号变了（主线程；录像意图开着、留着等自动接回时听着）。只看哨兵模式、挡位：车速一停一走不算 —— 等红灯不是停车。
     * 熄屏期间接着录的（不是唤醒锁拉着的那种，{@link ScreenOffPlan#rejudgesOnVehicleChange}）照熄屏那张表再判一次。
     */
    private void onVehicleChanged() {
        VehicleState vehicle = Telemetry.get().latest();
        long now = android.os.SystemClock.elapsedRealtime();
        boolean changed = false;
        if (!Objects.equals(vehicle.sentry, seenSentry)) {
            seenSentry = vehicle.sentry;
            sentrySinceMs = now;
            changed = true;
        }
        if (!Objects.equals(vehicle.gear, seenGear)) {
            seenGear = vehicle.gear;
            gearSinceMs = now;
            changed = true;
        }
        if (changed && ScreenOffPlan.rejudgesOnVehicleChange(darkAction, darkActionByLock) && ScreenState.dark()
                && (isRecording() || pending != null)) {
            decideWhileDark(DarkCheck.VEHICLE_CHANGED);
        }
    }

    /**
     * 录像意图开着、或者判了自动接回留着等的时候（{@link #isRecordingOrResuming}）向车辆信号登记（主线程；开录、停录、
     * 不等自动接回了时对一遍）：熄屏那一刻照哨兵模式、挡位、车速判（{@link ScreenOffPlan#atScreenOff}），没人登记时这些都读不到 ——
     * 存储一停就注销的话，接回之前熄屏一律判成车机要睡，开车、哨兵模式开着时也停（审查 2026-10-11，大纲 §7 第 8 问）。
     * 别的在等开录的（等 U 盘、等上一次收拾完）不登记 —— 以前等着的时候也登记，等 U 盘时 GPS 一直开着（大纲 §1）。
     */
    private void watchVehicle() {
        boolean want = isRecordingOrResuming();
        if (want == watchingVehicle) {
            return;
        }
        watchingVehicle = want;
        Telemetry telemetry = Telemetry.get();
        if (want) {
            VehicleState vehicle = telemetry.latest();
            long now = android.os.SystemClock.elapsedRealtime();
            seenSentry = vehicle.sentry;
            seenGear = vehicle.gear;
            sentrySinceMs = now;
            gearSinceMs = now;
            telemetry.addListener(vehicleListener);
            telemetry.acquire(context, VEHICLE_USER);
        } else {
            telemetry.removeListener(vehicleListener);
            telemetry.release(VEHICLE_USER);
        }
    }

    /**
     * 亮屏了（ScreenState 在主线程调）：「车机要睡」清掉（车机醒着了，调度照常保持、开相机，{@link ScreenOffPlan#sleepFact}）；
     * 熄屏那一段的结果记一行；这一趟要录而眼下没在录的（因熄屏停下的、进程被车机结束过的），接着录（{@link #resumeIfWanted}）。
     */
    public void screenOn() {
        cancelScreenOffStop();
        darkAction = null;
        CameraNeeds needs = CameraNeeds.current();
        if (ScreenOffPlan.sleepFact(false, null, needs.headUnitSleeps()) == ScreenOffPlan.SleepFact.CLEAR) {
            needs.setHeadUnitSleeps(false);
            BlackBox.note("亮屏：撤掉「车机要睡」，通道调度照常保持、开相机");
        }
        if (darkSinceElapsedMs > 0) {
            long offMs = android.os.SystemClock.elapsedRealtime() - darkSinceElapsedMs;
            long awakeMs = android.os.SystemClock.uptimeMillis() - darkSinceUptimeMs;
            int stops = stopsWhileDark;
            darkSinceElapsedMs = 0;
            darkSinceUptimeMs = 0;
            stopsWhileDark = 0;
            BlackBox.noteImportant("亮屏：熄屏这一段结束。熄屏 " + offMs / 1000 + " 秒，其中车机睡了 "
                    + Math.max(0L, offMs - awakeMs) / 1000 + " 秒；录像"
                    + (stops == 0 && isRecording() ? "一直在录"
                    : "中途停过 " + stops + " 次（原因见上面的「录像停止原因」），现在" + (isRecording() ? "在录" : "没在录")));
        }
        resumeIfWanted("screen-on");
    }

    /**
     * 这一趟要录（{@link RecordingIntent#recordingWanted}）而眼下没在录、没在等：接着录。接回只有这一个入口 ——
     * 亮屏（{@link #screenOn}）、进程在屏幕亮着时被拉起来（Recovery）都走它；进程被车机结束过也接（规格 1.2）。
     * 屏幕黑着不接（2026-10-10）：哨兵模式没开时车机几秒后就断电，车机自己醒来的那几分钟里也不该录起来。
     *
     * <p>现在用不了相机（主界面不在前、前台服务是后台起的 —— 安卓 12 起后台才起的前台服务用不了相机；和通道调度读的同一个事实，
     * {@link MultiCameraManager#cameraUsableNow}）：先把主界面拉起来（{@link Recovery#bringUpMainForRecording}，大纲 §2 阶段 6），
     * 录像照样现在就要 —— 没人拉主界面的话录像输出声明了、相机却开不起来，录像一直「等待摄像头」；拉了，它一起来、登记了预览，
     * 调度就能开，带着录像输出开。不等它起来再要（大纲原写法）：拉主界面可能被系统悄悄拦下（后台起界面的限制）、
     * 5 秒内重复的那一次也不再拉，那时没人再来要，录像就丢到下一次亮屏或 5 分钟一次的例行恢复（审查 2026-10-11）。</p>
     *
     * @param where 谁叫的（ASCII，进黑匣子）
     */
    public void resumeIfWanted(String where) {
        if (ScreenState.dark() || isRecording() || pending != null
                || !RecordingIntent.current().recordingWanted()) {
            return;
        }
        if (!MultiCameraManager.cameraUsableNow()) {
            Recovery.bringUpMainForRecording(context, where);
        }
        BlackBox.noteImportant("「这一趟要录」还在（" + where
                + (lastStopReason == null ? "，上一个进程留下的" : "，" + lastStopReason + " 停的") + "）：接着录");
        request(Why.SCREEN_ON);
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
        // 上一次查盘到点还没回来（盘掉线时探测卡在存储线程上，后面排着的也回不来）：判了自动接回时留着的相机、车辆信号、
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
            refused(why, context.getString(R.string.msg_keep_one_camera_refuse));
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
     * 每一路带着录像输出出了画面就加入，落定了报上来（{@link #pushView}）。
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
            refused(why, context.getString(R.string.msg_refuse_no_external));
            return;
        }
        // 开录前先确认写得下：以前不查，盘满时照常开录，第一笔数据写不进去，按钮就卡在「正在准备」
        if (checkStorage && !ensureRoomToStart(storage, cameras, why)) {
            return;
        }
        // 从这里起算「在录」（开录中）：录像意图开了，到这一步才清掉「等」
        lifecycle.begin();
        pending = null;
        main.removeCallbacks(poll);
        // 判了自动接回时留着的几样（相机、车辆信号、唤醒锁）从这里起是这一次录像的：下面再登记一遍无害，什么都不放
        resumeHold.finish();
        attemptManager = manager;
        startedAtMs = android.os.SystemClock.elapsedRealtime();
        startedOnFallback = storage.sdFellBack;
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
        // 录像意图开着才登记「录像要用相机」和车辆信号：相机关着的那几路，调度开的时候就带着录像输出
        CameraNeeds.current().claim(CameraNeeds.Holder.RECORDING);
        watchVehicle();
        // 这一趟要录过了：开录失败也算，失败了照样接回（规格 2.3）
        RecordingIntent.current().noteRecordingStarted();
        // 在录就立着「这一趟要录」：等的时候被清过（人点开主界面是新的一趟）也一样，录着的进程被结束了要接得回来
        noteWanted(true, why.name());
        if (!manager.startRecording(cameras)) {
            // 准备就失败了：准备到一半的录制器、录像输出照样由停录那条路收拾
            BlackBox.noteImportant("开录失败：相机层没能开始（" + why + "）");
            end(RecordingStops.Reason.START_FAILED);
            return;
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
            refused(why, context.getString(R.string.msg_storage_full_refuse));
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
                        ? R.string.msg_storage_locked_refuse : R.string.msg_storage_cannot_free));
            } else {
                startWith(storage, cameras, why, false);
            }
        });
        return false;
    }

    /**
     * 条件不满足：告诉界面，然后留在「要录」的状态里，过一会儿再看（U 盘插上、盘清出来了都算）。
     * 自动接回查过盘被拒了，就是确定这一次不接了：之后是等，等的不登记 —— 留着的几样放掉（{@link #dropResumeHold}）。
     */
    private void refused(Why why, String reason) {
        notifyRefused(reason);
        pending = why;
        main.removeCallbacks(poll);
        main.postDelayed(poll, REFUSED_RETRY_MS);
        dropResumeHold("refused");
    }

    // ================================================================= 停

    /**
     * 停。停的人说清原因；接不接、什么时候接，这里判。主线程调。
     *
     * <p>人停的、退出的：不再等。熄屏停的：等亮屏（{@link #screenOn}），接不接熄屏那一刻照表定好了。
     * 这两种停在等自动接回的那一段里，留着的几样一起放（{@link #cancelPending}）。
     * 写不进、没写进、开录失败、录制器自己停了（存储一类）：收拾完、查过盘就自动接回，有额度。
     * 盘满：接回去也录不下，不接。摄像头被拿走、断开不是停，不会走到这里。</p>
     */
    public void stop(RecordingStops.Reason reason) {
        boolean wasRecording = isRecording();
        if (!wasRecording && pending == null) {
            return;
        }
        // 人停的：不再等。熄屏停的：也不再等（黑着的时候不该自己录起来），亮屏时 screenOn 再判
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
     * <p>判了自动接回的（存储一类）：「录像要用相机」、车辆信号、熄屏录制的唤醒锁都留着（{@link RecordingStops.ResumeHold}，
     * 大纲 §7 第 8 问，项目所有者 2026-10-11「留」）—— 相机不放、停放的录制器留在会话里，接回开录时直接加入，存储停不动通道；
     * 接回之前熄屏，那张表照样读得到哨兵模式、挡位，锁拉着车机的照样拉着。接回开录了交给那一次录像（{@link #startWith}），
     * 确定不接了才放（{@link #dropResumeHold}）。以前停的那一下全放：熄屏时调度当场按次序关相机、接回时再开一轮，
     * 中间几秒到几十秒没录；接回之前熄屏一律判成车机要睡；锁一放车机几秒就睡。</p>
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
            stopsWhileDark++;
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
            // 熄屏录制的唤醒锁只在录像期间拿（规格 §3.1）：屏幕黑着、锁正拉着车机的先照熄屏那张表重判再放（车辆信号还登记着）
            letLockGo(DarkCheck.ENDED);
        }
        watchVehicle();
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
                + budget.attempts() + " 次）；相机、车辆信号留着等接回");
        request(Why.RESUME);
        return true;
    }

    /**
     * 不接了（主线程）：人停了、退出了、熄屏照表停了（{@link #cancelPending}），自动接回查过盘被拒了（{@link #refused}），
     * 接回前的查盘卡住了（{@link #start}）—— 判了自动接回时留着的几样放掉（{@link RecordingStops.ResumeHold}，大纲 §7 第 8 问）。
     * 没留着什么都不做，叫几次都一样。
     *
     * <p>次序和停录一样：「这一趟要录」留不留、前台服务（调用方已经定了）→ 唤醒锁（{@link #letLockGo}：屏幕黑着、锁正拉着车机的
     * 先照熄屏那张表重判）→ 车辆信号 → 放开相机。先不算留着，再判：锁不算拉着车机了。</p>
     *
     * @param why 为什么不接了（ASCII，进黑匣子）
     */
    private void dropResumeHold(String why) {
        if (!resumeHold.finish()) {
            return;
        }
        BlackBox.noteImportant("不等自动接回了（" + why + "）：留着的相机、车辆信号放开");
        letLockGo(DarkCheck.RESUME_DROPPED);
        watchVehicle();
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
     * <p>调用方在放车辆信号、「录像要用相机」之前叫：重判要读得到哨兵模式、挡位，「车机要睡」要先于放 RECORDING 写下
     * （调度当场置「正在按次序关」，放锁之前要读它）。</p>
     */
    private void letLockGo(DarkCheck check) {
        if (RecordingStops.rejudgesBeforeLockGoes(ScreenState.dark(), ScreenOffRecording.pullsCarAwake())) {
            // 判成现在停的：stopForScreenOff 让锁等相机按次序关完再放；还在等的停掉时又叫到 dropResumeHold，那时已经不留着了
            if (decideWhileDark(check) != ScreenOffPlan.Action.STOP_NOW) {
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
