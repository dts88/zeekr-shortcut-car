package com.kooo.evcam.camera;


import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.profile.RecordSpecs;
import com.kooo.evcam.profile.StreamSpec;
import com.kooo.evcam.FileTransferManager;
import com.kooo.evcam.StorageHelper;
import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.util.Size;
import android.view.TextureView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 四路摄像头管理器
 */
public class MultiCameraManager {
    private static final String TAG = "MultiCameraManager";

    private static final int DEFAULT_MAX_OPEN_CAMERAS = 4;
    private static final long RECORDING_STABLE_FRAME_MAX_AGE_MS = 1500;
    private static final int MAX_STABLE_WAIT_ATTEMPTS = 10;
    private static final long STABLE_WAIT_INTERVAL_MS = 200;
    // 录制分辨率将使用预览的实际分辨率，不再硬编码

    private final Context context;
    private final Map<String, SingleCamera> cameras = new LinkedHashMap<>();
    private final Map<String, VideoRecorder> recorders = new LinkedHashMap<>();
    private final Map<String, CodecVideoRecorder> codecRecorders = new LinkedHashMap<>();  // 软编码录制器
    private final List<String> activeCameraKeys = new ArrayList<>();
    private int maxOpenCameras = DEFAULT_MAX_OPEN_CAMERAS;

    private boolean isRecording = false;
    private boolean useCodecRecording = false;  // 是否使用软编码录制（用于 L6/L7）
    private boolean useRelayWrite = false;      // 是否使用中转写入（录制到内部存储，异步传输到U盘）
    private File finalSaveDir = null;           // 最终存储目录（用于中转写入模式）
    private volatile int lastNotifiedSegmentIndex = -1;  // 已通知的分段索引，避免重复通知

    // 统一分段时间戳管理（解决多路摄像头分段切换时时间戳差1秒的问题）
    private String cachedSegmentTimestamp = null;  // 缓存的分段时间戳
    private long timestampGeneratedTime = 0;  // 时间戳生成时间（毫秒）
    public static final long TIMESTAMP_CACHE_DURATION_MS = 10000;  // 时间戳缓存有效期（10秒，需覆盖各摄像头首次写入的时间差）；自动锁定算文件结束时也按它放宽（LockWindow.SLACK_MS）
    private final Object timestampLock = new Object();  // 时间戳访问锁
    
    // Watchdog 回退相关
    private String currentRecordingTimestamp = null;  // 当前录制的时间戳（用于重建时继续录制）
    private Set<String> currentEnabledCameras = null;  // 当前启用的摄像头集合
    private int rebuildAttemptCount = 0;  // 重建尝试次数（0=首次, 1=重建MediaRecorder, 2+=回退Codec）
    private static final int CODEC_FALLBACK_THRESHOLD = 2;  // 触发 Codec 回退的阈值
    private volatile boolean isRebuildingRecording = false;  // 是否正在重建录制（防止多摄像头并发触发）
    private StatusCallback statusCallback;
    private PreviewSizeCallback previewSizeCallback;
    private volatile int sessionConfiguredCount = 0;
    private volatile int expectedSessionCount = 0;
    private Runnable pendingRecordingStart = null;
    private android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // ---- 环视单独开、单独关：它开、关的时候座舱不动（项目所有者 2026-10-09：「依照单个环视的思路」）----
    // 开：环视先开，出了画面再开后座舱、前座舱（一路出了画面再开下一路）；开之前等上一轮关完。
    // 关：环视先关，关完了再把两路座舱一起关。
    // 为什么（2026-10-09 黑匣子，16 次关相机对下一次打开）：环视比座舱先关完的 4 次，下一次打开都正常；
    // 环视最后关完的 11 次里 9 次那一次关要 4–17 秒，下一次打开它一帧不出（隔 3 分钟再开也一样），
    // 其中一次紧接着再开就报错 4，一直坏到 12 小时后重启车机。一起关的时候（2.10.9 / 2.10.10）谁先关完
    // 看运气，所以有时快有时慢 —— 车主看到的「退出时悬浮按钮很久才消失，下一次打开就没画面」就是这个。
    // 只开环视时它开、关的时候也没有别的相机在动，所以从来不出这种事。
    /** 一路开到「出画面 / 报错」最多等多久；再久就先开下一路，别让一路坏的挡住全部，它自己由看门狗救。 */
    private static final long OPEN_STEP_MAX_MS = 15_000L;
    /** 配好会话之后多久看一眼出没出画面。 */
    private static final long FIRST_FRAME_POLL_MS = 250L;
    /** 会话配好以后，这么长时间里有帧就算出画面了。 */
    private static final long FIRST_FRAME_FRESH_MS = 1_000L;
    /** 开第一路之前等别的相机关完：多久看一次、最多等多久（相机服务一次关卡住时不能永远不开）。 */
    private static final long OPEN_WAIT_POLL_MS = 100L;
    private static final long OPEN_WAIT_FOR_CLOSES_MS = SingleCamera.IN_FLIGHT_MAX_MS;
    /** 环视单独关最多等多久；到点（相机服务卡住）不再等它，接着关座舱，别让退出等满。 */
    private static final long SURROUND_CLOSE_MAX_MS = 10_000L;
    /** 座舱一起关最多等多久都关完；到点记一行哪几路还没关，不再等它们。 */
    private static final long CLOSE_ALL_MAX_MS = 30_000L;
    private final java.util.ArrayDeque<Step> openQueue = new java.util.ArrayDeque<>();
    /** 正在等它出画面的那一路；null = 没有一路在开（可能还在等别的相机关完，看 openWaitStartedAt）。 */
    private Step openingStep;
    /** 开第一路之前从什么时候起在等别的相机关完（uptime）；0 = 没在等。 */
    private long openWaitStartedAt;
    /** 正在单独关、还没关完的环视；null = 环视不在关。 */
    private Step surroundClosing;
    /** 等环视关完再关的那几路（座舱）。 */
    private final List<Step> closeAfterSurround = new ArrayList<>();
    /** 一起发出去关、还没关完的那几路（座舱）。 */
    private final List<Step> closingSteps = new ArrayList<>();
    /** 这一轮关的原因（第一次叫关时给的），座舱那一步沿用。 */
    private String closeWhy;
    private final Runnable openStepTimeout = this::openStepTimedOut;
    private final Runnable openWhenClosed = this::openNext;
    private final Runnable surroundCloseTimeout = this::surroundCloseTimedOut;
    private final Runnable closeAllTimeout = this::closeAllTimedOut;
    /**
     * 开关相机自己的 Handler（同一个主线程 Looper）。不用 mainHandler：release() 开头会
     * removeCallbacksAndMessages(null)，而退出时 release() 会被调三次 —— 2.10.5 时第二次把
     * 「上一路关完、关下一路」的续命清掉了，后两路没关进程就退了（2026-10-08 23:18，退出等满 20 秒）。
     */
    private final android.os.Handler rollCall = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<String> openTrail = new ArrayList<>();
    /** 这一轮环视关了多久（「环视 0.1s」）；null = 这一轮没有关环视。 */
    private String surroundTrail;
    private final List<String> closeTrail = new ArrayList<>();
    /** 有没有哪份管理器的相机还在关（这一轮还没全部关完）。开相机、退出都等它（openNext、MainActivity.finishExit）。 */
    private static volatile boolean closingAll;
    private Runnable sessionTimeoutRunnable = null;
    private final Object sessionLock = new Object();  // 用于同步 session 配置计数
    
    // 按摄像头维度跟踪配置状态（解决超时强制启动问题）
    private final Map<String, Boolean> cameraSessionReady = new LinkedHashMap<>();
    private PipelineCallback pipelineCallback;

    /**
     * 录像的代数：每次开录、每次停录都 +1（和会话重建的代数号一个意思）。
     *
     * <p>开录之后排着的任务 —— 会话都建好后延迟 300ms 的那一下、等画面稳定的重试、3 秒超时、
     * MediaRecorder 重建前等的 500ms —— 都带着开录时的那一代，跑的时候对不上就作废，什么都不报。
     * 以前停录只清 {@link #pendingRecordingStart}，已经 post 出去的这几个照样跑：开 → 停 → 开两秒内，
     * 上一次剩下的开录任务会打到这一次的编码器上，再走「一路都没起来」把这一次弄死；
     * 重建那 500ms 里人按了停，停也会被它撤销。</p>
     */
    private volatile int recordGeneration;
    /** {@link #pendingRecordingStart} 是哪一代的（会话全配不上时报失败要带上它）。 */
    private int pendingStartGeneration;
    /**
     * 停录的收拾一次只做一份（停一路编码器最长要几秒，不能在主线程上做）。空闲几秒线程就退，
     * 管线释放后再停也照样能用。
     */
    private final java.util.concurrent.ThreadPoolExecutor teardown = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 5, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
            runnable -> new Thread(runnable, "StopRecording"));
    /** 还在跑或排着的收拾有几份。 */
    private final java.util.concurrent.atomic.AtomicInteger teardownsRunning =
            new java.util.concurrent.atomic.AtomicInteger();

    // ---- 一路单独离开、单独接回录像（项目所有者 2026-10-10）----
    // 一路被别的程序拿走（车主离车时车机拿走后座舱相机 1），只停这一路：别的几路照录，会话不重建，录像的代数、
    // 「在录」、协调器都不动。它放开了、通道安静了，看门狗单独重开它、单独接回录像（checkLiveness 的那一下重开就是接回）。
    // 它是最后一路在录的，才和以前一样整次停、等环视接回。MediaRecorder 模式没有单独接回，离开就整次停。

    /** 这一次录像里每一路的状态（主线程）：在录、离开中、不在录、接回中。见 {@link RecordingLanes}。 */
    private final RecordingLanes lanes = new RecordingLanes();
    /**
     * 这一次录像的计划：开录时每一路照当时的设置定下的录法（主线程）。接回只照它建录制器 ——
     * 录到一半改了视频流配置、车牌号、信息条，和别的路一样从下一次录像起生效；不在计划里的路不接回。
     */
    private final Map<String, LanePlan> recordingPlan = new LinkedHashMap<>();
    /** 在录、接回中的那几路挂在相机上的录像输出（主线程）：离开、接回没成时摘的就是它。 */
    private final Map<String, android.view.Surface> laneSurfaces = new HashMap<>();
    /** 正在离开、接回的那几路这一段带着的东西（主线程）。 */
    private final Map<String, LaneChange> laneChanges = new HashMap<>();
    /**
     * 接回时在后台建录制器（编码器、EGL、SurfaceTexture，不开文件；等 EGL 最多 5 秒，不能放在主线程上）。
     * 一次一份，和停录的收拾分开：收拾一路编码器要几秒，接回不该排在它后面。
     */
    private final java.util.concurrent.ThreadPoolExecutor joinPrep = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 5, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
            runnable -> new Thread(runnable, "JoinLane"));
    /** 接回的一路在等会话、等第一帧时多看几眼（{@link #FIRST_FRAME_POLL_MS} 一次），不等看门狗的 2 秒。 */
    private final Runnable joinPoll = this::pollJoins;
    private LaneListener laneListener;

    /** 这一次录像里一路的录法：开录时照当时的设置定下（{@link #recordingPlan}），接回照它建录制器。 */
    private static final class LanePlan {
        final StreamSpec spec;
        /** 环视录像下面加一条行驶信息条（只放环视）。 */
        final boolean infoBar;
        /** 左上角那行字：应用名 + 版本号（+ 车牌号）。 */
        final String brandLine;
        final boolean forceH264;
        final boolean watermark;
        final boolean watermarkSpec;

        LanePlan(StreamSpec spec, boolean infoBar, String brandLine, boolean forceH264,
                 boolean watermark, boolean watermarkSpec) {
            this.spec = spec;
            this.infoBar = infoBar;
            this.brandLine = brandLine;
            this.forceH264 = forceH264;
            this.watermark = watermark;
            this.watermarkSpec = watermarkSpec;
        }
    }

    /** 一路离开、接回的这一段（主线程）：黑匣子那一行要的时刻，量别的路用的起点。 */
    private static final class LaneChange {
        /** 这一段从什么时候起（uptime）：别的路最长断帧从这里量。 */
        final long startedAt = android.os.SystemClock.uptimeMillis();
        /** 那一刻别的在录的几路的会话代数：这一段结束时对一下，变了就是它们的会话被动过。 */
        final Map<String, Integer> generations;
        /** 为什么：离开的原因，或者这一次接回是怎么来的（放开了、第几次、被占着时每 30 秒试的那一下）。 */
        final String why;
        /** 离开：这一路的录制器收拾完了没有（没有录制器的一开始就算完）、收拾的结果（文件）。 */
        boolean recorderDone;
        String recorderResult = "";
        /** 接回：建好录制器、挂上录像输出（开相机 / 重建会话）、启动录制器的时刻（uptime）；0 = 还没到这一步。 */
        long preparedAt;
        long attachedAt;
        long recordingAt;
        /** 接回：最后一次看到相机在途的时刻（uptime），不在途之后等了多久从这里算（{@link RecordingLanes#opening}）。 */
        long busyAt;

        LaneChange(Map<String, Integer> generations, String why) {
            this.generations = generations;
            this.why = why;
        }
    }

    /** 录像里有一路离开、接回了（主线程）：RecordingCoordinator 转给界面，界面照 {@link #lanesOut()} 重画。 */
    public interface LaneListener {
        void onLanesChanged();
    }

    /**
     * 状态回调里的取值。这是模块之间的约定，不上界面，所以用固定的英文标记 ——
     * 以前是「预览已启动」「错误: …」这样的中文，接收方靠 contains 去猜。
     */
    public static final String STATUS_OPENED = "opened";
    public static final String STATUS_PREVIEW_STARTED = "preview_started";
    public static final String STATUS_CLOSED = "closed";
    /** 后面跟错误码，例如 {@code error:-4}。 */
    public static final String STATUS_ERROR_PREFIX = "error:";

    public interface StatusCallback {
        void onCameraStatusUpdate(String cameraId, String status);
    }

    public interface PreviewSizeCallback {
        void onPreviewSizeChosen(String cameraKey, String cameraId, Size previewSize);
    }
    
    public interface SegmentSwitchCallback {
        void onSegmentSwitch(int newSegmentIndex);
    }

    /**
     * 录不下去了（U 盘满）。
     *
     * <p>由 RecordingCoordinator 去停：它同步按钮、前台服务、提示，主界面在不在都一样。</p>
     */
    public interface StorageFullCallback {
        /** @param decision FULL 的那个决定：看 capless（没设上限）、lockedFull（剩下的都锁着） */
        void onStorageFull(StoragePlan.Decision decision);
    }

    /**
     * 损坏文件删除回调
     */
    public interface CorruptedFilesCallback {
        void onCorruptedFilesDeleted(List<String> deletedFiles);
    }

    /**
     * Codec 回退通知回调
     */
    public interface CodecFallbackCallback {
        void onCodecFallback();
    }
    
    /**
     * 开录、停录走到哪一步了，报给 RecordingCoordinator。「在不在录」只有它说了算（RecordingLifecycle），
     * 这里只报事实；过时的任务（代数对不上）不报。可能在任何线程上调。
     */
    public interface PipelineCallback {
        /**
         * 至少一路录制器启动了（含 MediaRecorder 重建后又启动）。
         *
         * @param active 启动了的几路
         * @param failed 没起来的几路
         */
        void onPipelineStarted(Set<String> active, Set<String> failed);

        /**
         * 开录走到底一路都没起来：会话全配不上、录制器全启动失败、MediaRecorder 重建没起来。
         * 这里不收拾 —— 协调器走停录那条路，由 {@link #stopRecording()} 按同一套收拾。
         */
        void onPipelineStartFailed(String why);

        /** 停录收拾完了：编码器放了，录像输出摘了，会话重建了。 */
        void onPipelineStopped();
    }

    /**
     * 首次数据写入回调
     * 用于通知外部录制已真正开始（有数据写入），可以开始计时
     */
    public interface FirstDataWrittenCallback {
        /**
         * 当任一摄像头首次成功写入数据时调用（只通知一次）
         */
        void onFirstDataWritten();
    }

    /**
     * 录制时间戳更新回调
     * 当 Watchdog 触发重建录制时，时间戳会改变，需要通知外部更新
     */
    public interface TimestampUpdateCallback {
        /**
         * 当录制时间戳更新时调用（通常在 Watchdog 重建后）
         * @param newTimestamp 新的录制时间戳
         */
        void onTimestampUpdated(String newTimestamp);
    }

    public MultiCameraManager(Context context) {
        this.context = context;
        // 相机服务眼里每一路空不空：管线一建就听。以前只有前台服务调它，前台服务没起来时相机服务说的话一句都没收，
        // 每次丢失都被判成「没说空闲」。注册那一刻的回放在主线程上收全，排在之后 post 的每一轮之前（重复调用无害）
        CameraAvailabilityWatch.start(context);
        livenessRunning = true;
        mainHandler.postDelayed(livenessTick, LIVENESS_TICK_MS);
        // 登记表一变就来看：有人要就开，没人要就关（相机开关的唯一裁判）
        CameraNeeds.current().setListener(this::reconcileCameras);
        // 熄屏时没人要相机就不等 30 秒（ScreenState 在主线程上叫）
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            com.kooo.evcam.screen.ScreenState.addListener(screenListener);
        } else {
            mainHandler.post(() -> com.kooo.evcam.screen.ScreenState.addListener(screenListener));
        }
    }

    /** 熄屏：没人要相机的话，原来排着的 30 秒改成现在关 —— 车机熄屏几秒后就断电，相机不能开着睡过去（{@link #closeDelayMs}）。 */
    private final com.kooo.evcam.screen.ScreenState.Listener screenListener =
            new com.kooo.evcam.screen.ScreenState.Listener() {
                @Override
                public void onScreenOff() {
                    reconcileCameras();
                }

                @Override
                public void onScreenOn() {
                    // 亮屏不自己开相机：主界面在前面时它自己登记「预览」
                }
            };

    // ------------------------------------------------------------------ 相机开关的裁判

    /**
     * 没人要相机了多久才关（项目所有者 2026-10-09 定 30 秒，原来 1.5 秒）：最小化、进诊断信息 / 回看，
     * 报告里 79% 在 30 秒内回来 —— 这段时间里相机不关，回来不用重开，通道不动。
     */
    private static final long CLOSE_WHEN_UNNEEDED_MS = 30_000L;
    /**
     * 前台服务不在时，没人要了还是 1.5 秒就关：后台拿着相机安卓要求有前台服务，没有它我们一退到后台相机就会被系统停掉。
     */
    private static final long CLOSE_WHEN_UNNEEDED_NOW_MS = 1_500L;

    /**
     * 这一次没人要了，等多久关：熄屏 → 现在关；前台服务不在 → 1.5 秒；否则 30 秒。
     * 熄屏不再等 1.5 秒（2026-10-10）：哨兵模式没开时车机熄屏 3–5 秒就结束进程，等 1.5 秒再按次序关（环视先单独关，
     * 关完再关座舱）常常关不完，相机就留给相机服务去乱序收拾 —— 下一次打开环视不出画面就是这么来的。
     */
    private static long closeDelayMs() {
        if (com.kooo.evcam.screen.ScreenState.dark()) {
            return 0;
        }
        return com.kooo.evcam.CameraForegroundService.isRunning() ? CLOSE_WHEN_UNNEEDED_MS : CLOSE_WHEN_UNNEEDED_NOW_MS;
    }

    private final Runnable closeWhenUnneeded = () -> {
        if (isRecording || CameraNeeds.current().heldByAnyone()) {
            return;
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("没人要相机了，让相机去关"
                + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        for (SingleCamera camera : cameras.values()) {
            camera.setKeepStreaming(false);
        }
        closeAllCameras("nobody-needs");
    };

    /**
     * 相机开不开、关不关，只看登记表（{@link CameraNeeds}）：谁要用就登记，没人登记才关（1.65.0）。
     *
     * <p>主界面预览、录像、拍照要的是全部启用的路，登记了就开；后视镜只要自己那一路，它自己开（{@code bindCamera}）。
     * 没人要了等 {@link #CLOSE_WHEN_UNNEEDED_MS}（30 秒）再关；前台服务不在时 1.5 秒就关，熄屏时现在就关（{@link #closeDelayMs}）。
     * 等的这 30 秒里相机照常出帧：主界面看不见，画面没地方显示，就送进不显示的出帧口
     * （{@link SingleCamera#setKeepStreaming}）—— 停了流再起，和关了再开一样要动通道。
     * 开着的每一路再按这些调整自己的输出（{@link SingleCamera#followNeeds}）；马上就关的不调（{@link #closesSoon}）：
     * 熄屏时关相机就在下一刻，摘出帧口那一次重建会落在按次序关的途中。
     * 以前这个判断散在主界面退后台、熄屏 1.5 秒、熄屏 15 秒、后视镜四处，各问一遍登记表。</p>
     */
    public void reconcileCameras() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post(this::reconcileCameras);
            return;
        }
        CameraNeeds needs = CameraNeeds.current();
        mainHandler.removeCallbacks(closeWhenUnneeded);
        boolean waitingToClose = false;
        if (needs.heldByAnyone()) {
            if (!isReleased() && (needs.isHeld(CameraNeeds.Holder.PREVIEW)
                    || needs.isHeld(CameraNeeds.Holder.RECORDING)
                    || needs.isHeld(CameraNeeds.Holder.PHOTO))) {
                openAllCameras();   // 已经开着的那几路会被 openCamera 自己跳过
            }
        } else {
            long delay = closeDelayMs();
            waitingToClose = delay == CLOSE_WHEN_UNNEEDED_MS;
            mainHandler.postDelayed(closeWhenUnneeded, delay);
        }
        for (SingleCamera camera : cameras.values()) {
            // 等关的这 30 秒照常出帧；马上就关的不必为它重建会话。只剩后视镜要的时候（座舱没人看）不出帧，和以前一样
            camera.setKeepStreaming(waitingToClose);
            if (!closesSoon(camera)) {
                camera.followNeeds();
            }
        }
    }

    // ------------------------------------------------------------------ 相机兜底看门狗

    /** 兜底看门狗多久看一眼。空转时只是几次字段读取，放密一点不心疼。 */
    private static final long LIVENESS_TICK_MS = 2000L;

    private final Map<String, CameraLiveness.State> livenessStates = new HashMap<>();
    private boolean livenessRunning;
    /** 上一次是哪一路在途、挡住了整轮看门狗（只在换了一路时记一行）；null = 上一次没被挡。 */
    private String livenessBlockedBy;
    /** 按次序开相机时跳过、已经记过一行的那几路（被别的程序拿走了的相机 id）：每次被拿走只记一行。 */
    private final Set<String> benchSkipNoted = new HashSet<>();

    private final Runnable livenessTick = new Runnable() {
        @Override
        public void run() {
            try {
                // 正在离开、接回录像的那几路先往前走一步：离开收拾完的算不在录（这一次检查就能接回它）
                stepLanes();
                checkLiveness();
            } catch (Exception e) {
                AppLog.w(TAG, "相机看门狗检查失败: " + e);
            }
            if (livenessRunning) {
                mainHandler.postDelayed(this, LIVENESS_TICK_MS);
            }
        }
    };

    /**
     * 该出帧而长时间没帧的那一路，直接重开。
     *
     * <p>为什么 {@link SingleCamera} 自己已经有一套自愈还要这一层，见 {@link CameraLiveness}
     * 的类说明：那一套全靠相机回调驱动，而回调不来正是车机相机挂住时的样子。
     * 这一层只看有没有帧，不看任何状态标志。</p>
     *
     * <p>2026-10-10 起这里也是被别的程序拿走的相机唯一的重开处：按次序开相机、后视镜都跳过它，
     * 别的程序放开时 {@link #retryTaken} 也只叫这里下一次检查就看。闸门（{@link CameraTaken#gate}）要别的程序
     * 一路都不占了、这一路自己空闲满 3 秒、所有相机 3 秒没变过，才单独重开它；还被占着时每 30 秒试一次，
     * 不计次数。录像、预览、拍照、后视镜要它都是这一条路。</p>
     *
     * <p>录像里不在录、等着接回的一路（{@link RecordingLanes#waitsToJoin}），这一下重开就是接回（{@link #startJoin}）：
     * 它没有录像的帧，照「卡住」算，看门狗照常计次数、歇着、停手（被拿走的照闸门、每 30 秒那一下不计次数）。
     * 一路正在离开、或者接回到一半，它这一步走完之前别的路都不动。</p>
     */
    private void checkLiveness() {
        if (cameras.isEmpty() || !openInOrderDone() || closingAll) {
            // 正在按次序开、或者正在关：开到哪一路、关到哪一路由次序管，看门狗这时不插手
            return;
        }
        String changing = lanes.changing();
        if (changing != null && android.os.SystemClock.elapsedRealtime() - lanes.sinceMs(changing)
                < SingleCamera.IN_FLIGHT_MAX_MS) {
            // 一次只动一路：一路正在离开录像（录制器在收拾、相机在关）、或者接回到一半（建录制器、开相机、等画面）。
            // 和相机在途一样最多挡 SingleCamera.IN_FLIGHT_MAX_MS（60 秒）：卡住了（建录制器时编解码服务不回）也不能一直不救别的路
            noteLivenessBlocked(changing, lanes.state(changing) == RecordingLanes.State.LEAVING
                    ? "正在离开录像" : "正在接回录像（" + lanes.step(changing) + "）");
            return;
        }
        String busy = busyCamera();
        if (busy != null) {
            // 一次只动一路：有一路在开 / 关 / 重开 / 配会话，别的路这时也不动 —— 环视开、关的时候座舱不能跟着变。
            // 在途最多算 SingleCamera.IN_FLIGHT_MAX_MS（60 秒），再久就当回调不会来了，不再挡
            noteLivenessBlocked(busy, "在途（" + cameras.get(busy).describeBusy() + "）");
            return;
        }
        livenessBlockedBy = null;
        // 被拿走的那几路过闸门时看的两样，这一轮大家看同一份：别的程序占着哪一路、所有相机多久没变过
        boolean othersHoldAny = CameraTaken.othersHold();
        long quietMs = CameraAvailabilityWatch.quietForMs();
        long now = android.os.SystemClock.uptimeMillis();
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            SingleCamera camera = entry.getValue();
            if (camera == null) {
                continue;
            }
            RecordingLanes.State laneState = lanes.state(entry.getKey());
            if (laneState == RecordingLanes.State.JOINING) {
                // 接回到了最后一步（录制器启动了、等第一笔数据）：成不成由录制器说，看门狗不插手
                continue;
            }
            CameraLiveness.State state = livenessStates.get(entry.getKey());
            if (state == null) {
                state = new CameraLiveness.State();
                livenessStates.put(entry.getKey(), state);
            }
            // 有人要画面就盯着 —— 包括「打开发出去了、一直没回音」的那种（以前靠前台服务每 10 秒
            // 一次的修复循环兜着，那个循环会把退避和放弃全部作废，1.62.0 删了）
            boolean watch = wanted(camera);
            // 被别的程序拿走的这一路：只从这道闸门重开。看的是这一路自己被没被拿走 —— 以前看「别的程序占着哪一路」，
            // 一路被占着，卡住的环视也跟着每 30 秒整个重开、不计次数
            boolean heldByOthers = false;
            boolean reopenNow = false;
            String id = camera.getCameraId();
            if (CameraTaken.benched(id)) {
                if (camera.isConnected() && camera.hasFramesWithin(FIRST_FRAME_FRESH_MS)) {
                    // 等的时候试的那一下拿到了（前台的一方拿得到），已经在出画面
                    noteBenchOver(entry.getKey(), camera, "试开拿到了，在出画面");
                } else if (watch) {
                    long availableMs = CameraAvailabilityWatch.availableForMs(id);
                    CameraTaken.Gate gate = CameraTaken.gate(othersHoldAny, availableMs, quietMs);
                    if (gate == CameraTaken.Gate.WAIT) {
                        continue;   // 通道还在变（交接还没完）：这一路这一次不动
                    }
                    if (gate == CameraTaken.Gate.REOPEN) {
                        noteBenchOver(entry.getKey(), camera, "放开了：别的程序一路都不占，这一路空闲 "
                                + seconds(availableMs) + "、所有相机 " + seconds(quietMs) + " 没变过，单独重开");
                        state.released();
                        reopenNow = true;
                    } else {
                        heldByOthers = true;
                    }
                }
            }
            // 设备报错 / 被断开之后不用等 8 秒：有人要画面，下一次检查就动手（2.10.10 起相机自己不重连）
            boolean lost = camera.deviceLost();
            // 录像里等着接回的一路没有录像的帧：照卡住算，重开就是接回，次数照计（相机自己出不出预览的画面不算数）
            boolean joinWanted = RecordingLanes.waitsToJoin(isRecording, useCodecRecording, laneState);
            long age = lost || reopenNow || joinWanted ? Long.MAX_VALUE : camera.progressAgeMs();
            CameraLiveness.Action action = CameraLiveness.step(state, watch, age, now, heldByOthers);
            String since = reopenNow ? "被别的程序拿走后放开了" : joinWanted ? "不在录像里、等着接回"
                    : lost ? "设备报错 / 被断开" : "已经 " + age + "ms 没有画面";
            if (action == CameraLiveness.Action.RESET) {
                if (joinWanted) {
                    startJoin(entry.getKey(), camera, reopenNow ? "放开了、通道安静了"
                            : heldByOthers ? "还被别的程序拿着：每 " + (CameraTaken.RETRY_WHILE_HELD_MS / 1000)
                            + " 秒试一次，不计次数" : "第 " + state.attempts() + " 次");
                    return;   // 一次只动一路
                }
                if (state.attempts() == 1 && camera.isConnected() && camera.sessionHasStreamed()) {
                    // 出过画面、后来停了的：第一次先只重建会话（便宜、快）；再不行才重开相机。
                    // 配好之后一帧都没出过的不走这一步：它没有东西可排空，重建每次都 waitUntilIdle 超时、
                    // 报设备错误、再关一次设备（2026-10-08，环视一次 4–13 秒），直接重开设备才出画面
                    // 这两级以前是 SingleCamera 自己那套 2.5 秒墙钟检测在做，现在只有这一处判
                    AppLog.w(TAG, "相机 " + entry.getKey() + "(" + camera.getCameraId() + ") " + since + "，先重建会话");
                    camera.recreateSession();
                } else {
                    AppLog.w(TAG, "相机 " + entry.getKey() + "(" + camera.getCameraId() + ") " + since + "，重开"
                            + (heldByOthers ? "（被别的程序拿走了，别的程序占着 " + CameraTaken.describe() + "：每 "
                            + (CameraTaken.RETRY_WHILE_HELD_MS / 1000) + " 秒试一次，不计次数）"
                            : "（第 " + state.attempts() + " 次）"));
                    camera.forceReopen();
                }
                return;   // 一次只动一路：这一路在动，别的等它动完（它还忙着的那几次检查整轮跳过）
            } else if (action == CameraLiveness.Action.GIVE_UP) {
                AppLog.e(TAG, "相机 " + entry.getKey() + "(" + camera.getCameraId() + ") 连着重开 "
                        + CameraLiveness.MAX_ATTEMPTS + " 次都没救回来，先停手 "
                        + (CameraLiveness.COOL_OFF_MS / 1000) + " 秒");
            } else if (action == CameraLiveness.Action.STOP) {
                AppLog.e(TAG, "相机 " + entry.getKey() + "(" + camera.getCameraId() + ") 试了 "
                        + CameraLiveness.MAX_CYCLES + " 轮都没用，彻底停手 —— "
                        + "再捶下去只会让相机服务更难缓过来。"
                        + "最后一次报错: " + camera.lastErrorName());
            }
        }
    }

    /**
     * 这一路该不该出画面：有输出挂着（预览 / 后视镜 / 录像）、有人等拍照，或者登记表上有人要全部的路
     * （预览 / 录像 / 拍照 —— 和 {@link #reconcileCameras} 开相机的条件同一个）。只看输出不够：
     * 打开就报错的那一路从来没有过输出，看门狗得知道它是该开着的。
     */
    private static boolean wanted(SingleCamera camera) {
        if (camera.wantsFrames()) {
            return true;
        }
        CameraNeeds needs = CameraNeeds.current();
        return needs.isHeld(CameraNeeds.Holder.PREVIEW) || needs.isHeld(CameraNeeds.Holder.RECORDING)
                || needs.isHeld(CameraNeeds.Holder.PHOTO);
    }

    /** 哪一路挡住了整轮看门狗（在途、正在离开 / 接回录像）：换了一路才记一行，同一路挡着的那几次检查不重复记。 */
    private void noteLivenessBlocked(String key, String what) {
        if (key.equals(livenessBlockedBy)) {
            return;
        }
        livenessBlockedBy = key;
        AppLog.i(TAG, "看门狗：相机 " + laneName(key) + what + "，这一轮别的路都不动");
    }

    /** 此刻有动作在途（开、关、重开、配会话、丢了之后在判）的那一路；没有是 null。 */
    private String busyCamera() {
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            SingleCamera camera = entry.getValue();
            if (camera != null && camera.isBusy()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 黑匣子里一路的叫法：相机编号加环视 / 前座舱 / 后座舱，「1（后座舱）」。 */
    private String laneName(String key) {
        SingleCamera camera = cameras.get(key);
        return (camera == null ? "?" : camera.getCameraId()) + "（" + roleName(key) + "）";
    }

    /** 相机 id 是哪一路（key）；不在这份管理器里是 null。 */
    private String keyOf(String cameraId) {
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            if (entry.getValue().getCameraId().equals(cameraId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 被拿走的这一路不再算被拿走：闸门开了、要单独重开它，或者等的时候试的那一下拿到了。
     * 黑匣子一行，带被拿走了多久 —— 车上核对「放开之后多久接回、是不是单独接回」看这一行。
     */
    private void noteBenchOver(String key, SingleCamera camera, String what) {
        long ms = CameraTaken.unbench(camera.getCameraId());
        benchSkipNoted.remove(camera.getCameraId());
        com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + camera.getCameraId() + "（" + roleName(key) + "）"
                + what + "（被拿走了 " + seconds(ms) + "）");
    }

    /**
     * 按次序开相机跳过被别的程序拿走的那一路（它只从看门狗的闸门重开）：每次被拿走只记一行。
     * 已经不算被拿走的从记过的里划掉，下一次被拿走再记。
     */
    private void noteBenchSkip(String key, SingleCamera camera) {
        benchSkipNoted.removeIf(id -> !CameraTaken.benched(id));
        if (benchSkipNoted.add(camera.getCameraId())) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("按次序开相机：相机 " + camera.getCameraId()
                    + "（" + roleName(key) + "）被别的程序拿走了，不开 —— 等它放开、通道安静了由看门狗单独重开");
        }
    }

    /** 黑匣子里的秒数，一位小数：「3.4 秒」。 */
    private static String seconds(long ms) {
        return String.format(java.util.Locale.US, "%.1f 秒", ms / 1000f);
    }

    /**
     * 统一的分段时间戳提供者
     * 确保在短时间内（3秒）所有摄像头获取到相同的时间戳
     * 解决多路摄像头分段切换时因启动时机不同导致时间戳差1秒的问题
     */
    private final VideoRecorder.SegmentTimestampProvider segmentTimestampProvider = 
            new VideoRecorder.SegmentTimestampProvider() {
        @Override
        public String getSegmentTimestamp() {
            synchronized (timestampLock) {
                long now = System.currentTimeMillis();
                // 如果缓存的时间戳仍在有效期内，返回缓存值
                if (cachedSegmentTimestamp != null && 
                    (now - timestampGeneratedTime) < TIMESTAMP_CACHE_DURATION_MS) {
                    AppLog.d(TAG, "Using cached segment timestamp: " + cachedSegmentTimestamp + 
                            " (age: " + (now - timestampGeneratedTime) + "ms)");
                    return cachedSegmentTimestamp;
                }
                // 生成新的时间戳
                cachedSegmentTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(new Date());
                timestampGeneratedTime = now;
                AppLog.d(TAG, "Generated new segment timestamp: " + cachedSegmentTimestamp);
                // 有一路在切段、换了新的共用名字：单独接回、名字还错开着的那几路马上跟着切，拿这同一个名字
                mainHandler.post(MultiCameraManager.this::alignJoinedLanes);
                return cachedSegmentTimestamp;
            }
        }
    };

    /**
     * 清除缓存的分段时间戳
     * 在开始新的录制时调用，确保使用新的时间戳
     */
    private void clearCachedSegmentTimestamp() {
        synchronized (timestampLock) {
            cachedSegmentTimestamp = null;
            timestampGeneratedTime = 0;
        }
    }
    
    private SegmentSwitchCallback segmentSwitchCallback;
    private StorageFullCallback storageFullCallback;
    /** 几路相机各自触发一次分段切换，合并成一次检查。 */
    private long lastStorageCheckMs = 0;
    private static final long STORAGE_CHECK_DEBOUNCE_MS = 20_000L;
    private static final long STORAGE_TICK_MS = 30_000L;

    /**
     * 录制中每 30 秒看一眼剩余空间。只是一次 statfs；低于余量才做完整检查。
     * 分段切换时的检查管的是「按上限删旧的」，这一条管的是分段写到一半盘就满了。
     */
    private final Runnable storageTick = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) {
                return;
            }
            // statfs 去存储线程做（盘掉线时它会卡住），结果回主线程再判断
            StorageGuard.freeBytesAsync(guardedVideoDir(), free -> {
                if (isRecording && free >= 0 && free < StorageGuard.lastMarginBytes()) {
                    checkStorage("剩余空间低于余量");
                }
            });
            mainHandler.postDelayed(this, STORAGE_TICK_MS);
        }
    };
    /**
     * 写不进文件：裁判在录制器里（{@link CodecVideoRecorder} 的 15 秒规则），这里只把它转给协调器。
     * 和相机被拿走共用一个「已经报过」的标记：一次录像只报一次打断。
     */
    private WriteStallCallback writeStallCallback;
    private boolean interruptReported;


    /**
     * 录像写不进文件了。
     *
     * <p>交给 RecordingCoordinator：它按「录像被打断」处理，按钮、悬浮按钮回到未录，等能录了再接。</p>
     */
    /**
     * 这次录像实际写到哪个盘，进黑匣子。
     *
     * <p>设定的盘不在时，路径选择会悄悄改用别的盘（2026-09-26 21:55 那次，1D8C 掉线后自动接回，
     * 就写到了 B905 上）—— 不记下来的话，事后连「录像在哪」都要猜。</p>
     */
    private void noteRecordingDir() {
        try {
            File dir = StorageHelper.getRecordingDir(context);
            if (dir == null) {
                // 刚开录盘就没了（没有地方可存）：录制器写不进会自己换盘或报停，这里没有目录可记
                return;
            }
            StorageHelper.noteRecordingDir(dir);
            String custom = new AppConfig(context).getCustomSdCardPath();
            boolean offTarget = custom != null && !custom.isEmpty()
                    && !dir.getAbsolutePath().startsWith(custom);
            StorageHelper.noteRecordingFallback(offTarget ? StorageHelper.volumeOf(dir.getAbsolutePath()) : null,
                    offTarget ? StorageHelper.volumeOf(custom) : null);
            com.kooo.evcam.blackbox.BlackBox.noteImportant("录像写到 " + dir.getAbsolutePath()
                    + (offTarget ? "（设定的是 " + custom + "，那个盘此刻不可用）" : "")
                    + "；此刻挂着的盘：" + com.kooo.evcam.storage.StorageState.current().mounts);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "noteRecordingDir failed: " + e);
        }
    }

    public interface WriteStallCallback {
        /** @param everWrote false：这次录像一个字节都没写出过（「没收到画面」） */
        void onWriteStalled(long stalledMs, boolean everWrote);
    }

    /** 写盘跟不上：某一路的写入排队满了，开始在相机这一侧丢帧（录像照常在录）。 */
    public interface WriteBacklogCallback {
        void onWriteBacklog(String cameraId);
    }

    private WriteBacklogCallback writeBacklogCallback;

    public void setWriteBacklogCallback(WriteBacklogCallback callback) {
        this.writeBacklogCallback = callback;
    }

    /** 录制器报写盘跟不上（主线程）。提示不提示、多久提示一次由 RecordingCoordinator 定。 */
    private void onWriteBacklog(String cameraId) {
        if (!isRecording) {
            return;
        }
        if (writeBacklogCallback != null) {
            writeBacklogCallback.onWriteBacklog(cameraId);
        }
    }

    /**
     * 录着的一路被相机服务断开了（别的程序拿走了相机），而录像里没有别的路在录（MediaRecorder 模式下哪一路都是）：
     * 整次停，由协调器按「录像被打断」处理。
     */
    public interface CameraLostCallback {
        void onCameraLost(String cameraId);
    }

    private CameraLostCallback cameraLostCallback;

    public void setCameraLostCallback(CameraLostCallback callback) {
        this.cameraLostCallback = callback;
    }

    /** 录像里有一路离开、接回时告诉谁（RecordingCoordinator，它转给界面）。 */
    public void setLaneListener(LaneListener listener) {
        this.laneListener = listener;
    }

    /**
     * 一路相机出错了（相机线程，{@code onCameraError}）：带着此刻录像的代数挪到主线程上判 —— 录像的表、录制器都归主线程，
     * 以前在相机线程上直接读，和停录同时动同一张表。被断开、报错 1 / 2 时相机层在关之前就报上来（那一次关要 5–8 秒）。
     */
    private void onRecordingCameraError(String cameraId, int errorCode) {
        final int gen = recordGeneration;
        mainHandler.post(() -> onLaneCameraError(cameraId, errorCode, gen));
    }

    /**
     * 录着的一路相机出错了（主线程）。
     *
     * <ul>
     *   <li>被断开、报错 1 / 2（多半是别的程序拿走了它）：在录的这一路单独离开（{@link #leaveLane}），
     *       它是最后一路在录的才整次停（{@link #onRecordingCameraLost}）；</li>
     *   <li>接回中的一路出了什么错，都是这一次接回没成（{@link #joinFailed}）；</li>
     *   <li>在录的一路报设备错误 3 / 4 / 5：录制器留着，看门狗重开，画面回来同一个录制器接着录。重开拖过 15 秒没写进数据，
     *       录制器的裁判报上来时再按「相机没了」单独离开（{@link #onLaneWriteStalled}）。</li>
     * </ul>
     *
     * <p>MediaRecorder 模式没有单独接回：被断开照旧整次停。</p>
     */
    private void onLaneCameraError(String cameraId, int errorCode, int gen) {
        if (gen != recordGeneration || !isRecording) {
            return;   // 没在录，或者报上来的这一会儿里停过、又开过录
        }
        String key = keyOf(cameraId);
        if (key == null) {
            return;
        }
        String why = describeLoss(errorCode);
        if (!useCodecRecording) {
            if (errorCode == -4) {
                onRecordingCameraLost(key, why);
            }
            return;
        }
        RecordingLanes.State state = lanes.state(key);
        if (state == RecordingLanes.State.JOINING) {
            joinFailed(key, why);
        } else if (state == RecordingLanes.State.LIVE && lostToOthers(errorCode)) {
            leaveLane(key, why, () -> onRecordingCameraLost(key, why));
        }
    }

    /** 被断开、报错 1（被占用）/ 2（相机数到上限）：多半是别的程序拿走了它，相机层在关之前就报上来。 */
    private static boolean lostToOthers(int errorCode) {
        return errorCode == -4
                || errorCode == android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
                || errorCode == android.hardware.camera2.CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE;
    }

    /** 黑匣子里说这一路的相机怎么了。 */
    private String describeLoss(int errorCode) {
        return errorCode == -4 ? "被相机服务断开" : "报错 " + errorCode + "（" + getErrorMessage(errorCode) + "）";
    }

    /**
     * 录着的一路被相机服务断开了，而录像里没有别的路在录：整次停 —— 交给协调器按「录像被打断」处理，
     * 等环视恢复再自动接回（{@link CameraTaken}）。MediaRecorder 模式下哪一路被断开都走这里（那里没有单独接回）。
     *
     * <p>2026-09-27 实测：车机原生功能拿走相机后我们几毫秒内被断开，之后每次重开都失败，
     * 直到它放开（一次 45 秒）。以前要等看门狗发现「18 秒没新数据」才停段，白等这一段。
     * 2026-10-10 起编码录制只在最后一路在录的被拿走时才整次停，别的时候只停那一路（{@link #leaveLane}）。</p>
     *
     * @param why 黑匣子里说它的相机怎么了（被断开、报错 1 / 2）
     */
    private void onRecordingCameraLost(String key, String why) {
        if (!isRecording || interruptReported || !(recorders.containsKey(key) || codecRecorders.containsKey(key))) {
            return;
        }
        SingleCamera camera = cameras.get(key);
        String cameraId = camera == null ? key : camera.getCameraId();
        interruptReported = true;
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像的相机 " + laneName(key) + why + "，"
                + (useCodecRecording ? "它是最后一路在录的" : "MediaRecorder 模式不单独停一路")
                + "：整次停录，等环视恢复后接回；别的程序占着 " + CameraTaken.describe());
        if (cameraLostCallback != null) {
            cameraLostCallback.onCameraLost(cameraId);
        } else {
            stopRecording();
        }
    }

    // ------------------------------------------------------------------ 一路单独离开、单独接回（2026-10-10）

    /**
     * 这一次录像里此刻不在录的几路（离开中、不在录、接回中），按开相机的次序（主线程）：主界面的状态条、拍照、
     * 诊断、熄屏都看它。没在录、MediaRecorder 模式（那里没有单独接回）是空的。
     */
    public List<RecordingLanes.Out> lanesOut() {
        List<RecordingLanes.Out> out = new ArrayList<>();
        for (String key : CameraSlots.openOrder(recordingPlan.keySet())) {
            RecordingLanes.Out lane = lanes.outOf(key);
            if (lane != null) {
                out.add(lane);
            }
        }
        return out;
    }

    private void notifyLanes() {
        if (laneListener != null) {
            laneListener.onLanesChanged();
        }
    }

    /**
     * 在录的一路离开录像（主线程）：它的相机被别的程序拿走了（被断开、报错 1 / 2），或者相机没了、在重开时它写不进。
     *
     * <p>只停这一路（{@link #retireLane}）：别的路照录、会话不动；录像的代数、「在录」、协调器都不动，不报停录。
     * 它收拾完、相机关完判完（{@link #settleLeave}）就算不在录，看门狗接着就看什么时候接回它。
     * 它是最后一路在录的（{@link RecordingLanes#lastLiveLane}）：录像里一路都不在录了，就是一次停录 ——
     * 整次停（{@code wholeStop}），和以前一样等环视恢复再接回。</p>
     *
     * @param why       黑匣子里说它怎么了
     * @param wholeStop 它是最后一路在录的时候怎么停：以前这件事怎么整次停，现在照样
     */
    private void leaveLane(String key, String why, Runnable wholeStop) {
        if (RecordingLanes.lastLiveLane(key, liveLanes())) {
            wholeStop.run();
            return;
        }
        int token = lanes.leave(key, android.os.SystemClock.elapsedRealtime());
        LaneChange change = new LaneChange(liveGenerations(key), why);
        laneChanges.put(key, change);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + laneName(key) + why + "：只停这一路，"
                + liveNames() + "照录、会话不动；它的录制器收好、相机关完判出是不是被别的程序拿走，再单独接回");
        retireLane(key, token, change);
        notifyLanes();
    }

    /**
     * 开录时没录起来的一路（会话没在 3 秒内配好、录制器没启动）：和离开走同一条路退出（{@link #retireLane}），
     * 收拾完了等着单独接回。不整次停：起来了的几路照录。
     */
    private void startFailed(String key, String why) {
        int token = lanes.leave(key, android.os.SystemClock.elapsedRealtime());
        LaneChange change = new LaneChange(liveGenerations(key), why);
        laneChanges.put(key, change);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("开录：相机 " + laneName(key) + " 没录起来（" + why + "），"
                + liveNames() + "照录；它收拾完了单独接回");
        retireLane(key, token, change);
    }

    /** 新的一次录像、停录：每一路的状态、计划、挂着的输出都清掉（排着的接回任务回来时代数对不上，作废）。 */
    private void clearLanes() {
        lanes.clear();
        recordingPlan.clear();
        laneSurfaces.clear();
        laneChanges.clear();
        mainHandler.removeCallbacks(joinPoll);
    }

    /**
     * 让一路的录制器退出这一次录像（离开、接回没成、开录时没录起来）：从录制器表里拿出来叫停，在收拾线程上排空、
     * 收文件、放掉（和停录同一个期限，{@link CodecVideoRecorder#STOP_BUDGET_MS}）；这一路相机上的录像输出摘掉。
     * 相机还开着的（写不进时它在重开、接回没成时它没丢）只重建它自己的会话，免得会话对着一个要放掉的输出；
     * 被断开的那种设备已经没了，什么都不重建。别的路一概不动。
     *
     * <p>中转写入只转存这一个录制器收掉的那几个文件（{@link CodecVideoRecorder#stoppedFiles}）：缓存目录里别的路还在写。
     * 以前只有整次停录时才把缓存目录整个转存。</p>
     */
    private void retireLane(String key, int token, LaneChange change) {
        CodecVideoRecorder recorder = codecRecorders.remove(key);
        android.view.Surface surface = laneSurfaces.remove(key);
        SingleCamera camera = cameras.get(key);
        if (camera != null && surface != null && camera.clearRecordSurfaceIf(surface) && camera.isConnected()) {
            camera.recreateSession();
        }
        if (recorder == null) {
            change.recorderDone = true;
            change.recorderResult = "它还没有录制器";
            return;
        }
        final int gen = recordGeneration;
        final File relayTarget = useRelayWrite ? finalSaveDir : null;
        final long deadline = android.os.SystemClock.uptimeMillis() + CodecVideoRecorder.STOP_BUDGET_MS;
        recorder.beginStop();
        teardownsRunning.incrementAndGet();
        teardown.execute(() -> {
            String result;
            try {
                recorder.awaitDrain(deadline);
                recorder.finishStop(deadline);
                recorder.release();
                result = handOverStopped(recorder, relayTarget);
            } catch (Exception e) {
                AppLog.e(TAG, "Error retiring the codec recorder of " + key, e);
                result = "收拾它的录制器出错：" + e;
            } finally {
                teardownsRunning.decrementAndGet();
            }
            final String retired = result;
            mainHandler.post(() -> laneRetired(key, token, gen, retired));
        });
    }

    /**
     * 退出录像的那个录制器收掉了哪些文件（收拾线程上）：黑匣子一句；中转写入时把它们转存到这一次录像的目标目录
     * （写入线程还攥着的那个除外，半个文件转过去就坏了）。
     */
    private String handOverStopped(CodecVideoRecorder recorder, File relayTarget) {
        String held = recorder.heldFile();
        List<File> files = new ArrayList<>();
        StringBuilder names = new StringBuilder();
        for (String path : recorder.stoppedFiles()) {
            File file = new File(path);
            if (path.equals(held) || !file.exists()) {
                continue;
            }
            files.add(file);
            names.append(names.length() > 0 ? "、" : "").append(file.getName());
        }
        if (relayTarget != null && !files.isEmpty()) {
            final File[] transfer = files.toArray(new File[0]);
            mainHandler.postDelayed(() -> transferSpecificTempFiles(relayTarget, transfer), 500);
        }
        String result = names.length() == 0 ? "没有留下文件" : "文件收好了：" + names;
        return held == null ? result
                : result + "；" + new File(held).getName() + " 写入线程卡住没收好，交给了抢救";
    }

    /** 退出录像的那个录制器收拾完了（主线程）。停过录、这一路之后又变过的不算。 */
    private void laneRetired(String key, int token, int gen, String result) {
        LaneChange change = laneChanges.get(key);
        if (gen != recordGeneration || !lanes.current(key, token) || change == null) {
            return;
        }
        change.recorderDone = true;
        change.recorderResult = result;
        settleLeave(key);
    }

    /**
     * 离开中的一路走完了没有（主线程；它的录制器收拾完时、看门狗每次检查之前看）：录制器收好了、相机不在途了 ——
     * 被断开的那一次关、判是不是被别的程序拿走的都完了。完了就是不在录：判出被拿走的（{@link CameraTaken#benched}）
     * 等它放开、通道安静了接回；别的失败按看门狗的次数接回。黑匣子一行：离开用了多久、文件、这段时间里别的路怎么样。
     */
    private void settleLeave(String key) {
        LaneChange change = laneChanges.get(key);
        SingleCamera camera = cameras.get(key);
        if (lanes.state(key) != RecordingLanes.State.LEAVING || change == null || !change.recorderDone
                || (camera != null && camera.isBusy())) {
            return;
        }
        boolean taken = camera != null && CameraTaken.benched(camera.getCameraId());
        lanes.out(key, taken ? RecordingLanes.Reason.TAKEN : RecordingLanes.Reason.FAILED,
                android.os.SystemClock.elapsedRealtime());
        laneChanges.remove(key);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + laneName(key) + " 离开录像了（" + change.why
                + "，离开用了 " + seconds(android.os.SystemClock.uptimeMillis() - change.startedAt) + "）："
                + (taken ? "被别的程序拿走了，等它放开、通道安静了单独接回" : "不是被别的程序拿走的，看门狗按次数单独接回")
                + "；" + change.recorderResult + "；" + othersDuring(change));
        notifyLanes();
    }

    /**
     * 正在离开、接回录像的那几路往前走一步（主线程，看门狗每次检查之前）：离开的收拾完了就算不在录；
     * 接回建好了录制器的，通道安静了开相机；在等会话、等画面的看一眼。
     */
    private void stepLanes() {
        for (String key : lanes.keys()) {
            RecordingLanes.Step step = lanes.step(key);
            if (lanes.state(key) == RecordingLanes.State.LEAVING) {
                settleLeave(key);
            } else if (step == RecordingLanes.Step.PREPARED) {
                attachJoin(key);
            } else if (step == RecordingLanes.Step.OPENING) {
                checkJoin(key);
            }
        }
    }

    /**
     * 单独接回一路（看门狗对它的那一下重开，{@link #checkLiveness}）。
     *
     * <ol>
     *   <li>在后台照这一次录像的计划建它的录制器（编码器、EGL、SurfaceTexture；不开文件），带着录像的代数和这一路的计数；</li>
     *   <li>建好了回主线程，通道安静时连着录像输出开这一路（{@link #attachJoin}）；</li>
     *   <li>配好的会话里真有这一路的录像输出、出了画面，才启动录制器，第一个文件这时开、名字是这一刻（{@link #checkJoin}）；</li>
     *   <li>写进第一笔数据才算接回（{@link #joinLive}）。之前哪一步没成都是这一次接回没成（{@link #joinFailed}），
     *       下一次什么时候试、试几次，看门狗照常计。</li>
     * </ol>
     *
     * @param why 这一次是怎么来的（黑匣子）：放开了、第几次、被占着时每 30 秒试的那一下
     */
    private void startJoin(String key, SingleCamera camera, String why) {
        final LanePlan plan = recordingPlan.get(key);
        // 写到录像此刻实际写着的那个目录：录制器换过盘就是新盘，中转写入是缓存目录
        File recordingDir = StorageHelper.lastRecordingDir();
        final File dir = recordingDir != null ? recordingDir : StorageHelper.getRecordingDir(context);
        if (plan == null || dir == null) {
            AppLog.w(TAG, "接回 " + key + "：" + (plan == null ? "不在这一次录像的计划里" : "没有地方存录像") + "，不接");
            return;
        }
        final int token = lanes.join(key, android.os.SystemClock.elapsedRealtime());
        laneChanges.put(key, new LaneChange(liveGenerations(key), why));
        final int gen = recordGeneration;
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：单独接回相机 " + laneName(key) + "（" + why
                + "）：先在后台建录制器，建好了等通道安静，连着录像输出开它");
        notifyLanes();
        joinPrep.execute(() -> {
            CodecVideoRecorder recorder = null;
            android.graphics.SurfaceTexture texture = null;
            try {
                recorder = newCodecRecorder(key, camera, plan);
                recorder.setCallback(codecCallback(gen, key, token));
                texture = recorder.prepareWithoutFile(dir, key);
            } catch (RuntimeException e) {
                AppLog.e(TAG, "Error building the codec recorder to rejoin " + key, e);
            }
            final CodecVideoRecorder built = recorder;
            final android.graphics.SurfaceTexture surfaceTexture = texture;
            mainHandler.post(() -> joinPrepared(key, token, gen, built, surfaceTexture));
        });
    }

    /**
     * 接回的录制器建好了（主线程）。这一次接回还算数（没停过录、这一路没又变过）就记进录制器表 —— 整次停录、释放时
     * 一并收拾它 —— 然后等通道安静了开相机（{@link #attachJoin}）；不算数了就放掉；没建成就是这一次接回没成。
     */
    private void joinPrepared(String key, int token, int gen, CodecVideoRecorder recorder,
                              android.graphics.SurfaceTexture texture) {
        if (gen != recordGeneration || !lanes.current(key, token)) {
            if (recorder != null) {
                releaseLater(recorder);
            }
            return;
        }
        if (texture == null) {
            // 没建成。准备失败时录制器自己放过一次；没走到准备那一步就出了错的在这里放（再放一次无害）
            if (recorder != null) {
                releaseLater(recorder);
            }
            joinFailed(key, "建录制器没成");
            return;
        }
        LaneChange change = laneChanges.get(key);
        if (change != null) {
            change.preparedAt = android.os.SystemClock.uptimeMillis();
        }
        codecRecorders.put(key, recorder);
        laneSurfaces.put(key, new android.view.Surface(texture));
        lanes.step(key, RecordingLanes.Step.PREPARED);
        attachJoin(key);
    }

    /**
     * 接回的录制器建好了，通道安静时（没有哪一路在开、在关、在配会话，按次序开相机走完了，不在关相机那一轮里）
     * 连着录像输出开这一路：输出在打开的同一把锁里挂上（{@link SingleCamera#openForRecording}）—— 后台没有预览时
     * 它是这一路唯一的输出，不挂着就建不起会话、出不了帧。相机还开着、在出画面的（开录时没录起来、出错之后自己好了的那种），
     * 挂上输出、只重建它自己的会话；开着却没画面的，挂上输出强制重开它（强制重开不摘录像输出）。
     * 通道不安静就等下一次检查（{@link #stepLanes}）。
     */
    private void attachJoin(String key) {
        SingleCamera camera = cameras.get(key);
        android.view.Surface surface = laneSurfaces.get(key);
        LaneChange change = laneChanges.get(key);
        if (camera == null || surface == null || change == null || !openInOrderDone() || closingAll
                || busyCamera() != null) {
            return;
        }
        String how;
        if (camera.isConnected()) {
            camera.setRecordSurface(surface, true);
            if (camera.isSessionReady() && camera.hasFramesWithin(FIRST_FRAME_FRESH_MS)) {
                camera.recreateSession();
                how = "它开着、在出画面：挂上录像输出，只重建它自己的会话";
            } else {
                camera.forceReopen();
                how = "它开着却没有画面：挂上录像输出，强制重开它";
            }
        } else if (camera.openForRecording(surface)) {
            how = "连着录像输出打开它";
        } else {
            return;   // 它正在开（别处开的）：下一次检查再看
        }
        long now = android.os.SystemClock.uptimeMillis();
        change.attachedAt = now;
        change.busyAt = now;
        lanes.step(key, RecordingLanes.Step.OPENING);
        AppLog.i(TAG, "接回 " + laneName(key) + "：" + how);
    }

    /** 接回中、在等会话 / 等画面的那几路都看一眼（会话配好时；出画面之前每 {@link #FIRST_FRAME_POLL_MS}）。 */
    private void pollJoins() {
        for (String key : lanes.keys()) {
            if (lanes.step(key) == RecordingLanes.Step.OPENING) {
                checkJoin(key);
            }
        }
    }

    /**
     * 接回等会话、等画面（{@link RecordingLanes.Step#OPENING}）：配好的会话里真有这一路的录像输出、而且出了画面，
     * 才启动录制器 —— 第一个文件这时开，名字是这一刻；相机不在途了还等不到就是这一次没成（{@link RecordingLanes#opening}）。
     */
    private void checkJoin(String key) {
        SingleCamera camera = cameras.get(key);
        android.view.Surface surface = laneSurfaces.get(key);
        CodecVideoRecorder recorder = codecRecorders.get(key);
        LaneChange change = laneChanges.get(key);
        if (lanes.step(key) != RecordingLanes.Step.OPENING || camera == null || surface == null
                || recorder == null || change == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        boolean busy = camera.isBusy();
        if (busy) {
            change.busyAt = now;
        }
        boolean carries = camera.sessionCarries(surface);
        switch (RecordingLanes.opening(busy, carries, camera.hasFramesWithin(FIRST_FRAME_FRESH_MS),
                now - change.busyAt)) {
            case START:
                if (!recorder.startRecording()) {
                    joinFailed(key, "录制器没启动");
                    return;
                }
                change.recordingAt = now;
                lanes.step(key, RecordingLanes.Step.STARTED);
                AppLog.i(TAG, "接回 " + laneName(key) + "：会话里有它的录像输出、出了画面，录制器启动了，等第一笔数据");
                break;
            case FAILED:
                joinFailed(key, carries ? "会话里有它的录像输出，却 " + seconds(now - change.busyAt) + " 没有画面"
                        : "配好的会话里没有它的录像输出（会话配不上时被丢掉了）");
                break;
            default:
                if (!busy && carries) {
                    // 会话里有它了，等第一帧：隔一会儿再看，不等看门狗的 2 秒
                    mainHandler.removeCallbacks(joinPoll);
                    mainHandler.postDelayed(joinPoll, FIRST_FRAME_POLL_MS);
                }
                break;
        }
    }

    /**
     * 接回的一路写进了第一笔数据（主线程）：它又在录了。黑匣子一行：怎么来的、每一步用了多久、文件名、
     * 这段时间里别的路最长断帧多久、会话动没动过 —— 车上核对「放开之后单独接回、别的路没被动到」看这一行。
     */
    private void joinLive(String key) {
        LaneChange change = laneChanges.remove(key);
        lanes.live(key, android.os.SystemClock.elapsedRealtime());
        CodecVideoRecorder recorder = codecRecorders.get(key);
        String file = recorder == null ? null : recorder.currentFile();
        if (change != null) {
            long now = android.os.SystemClock.uptimeMillis();
            com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + laneName(key) + " 接回录像了（"
                    + change.why + "）：建录制器 " + seconds(change.preparedAt - change.startedAt)
                    + "、等通道安静 " + seconds(change.attachedAt - change.preparedAt)
                    + "、开相机到会话里有它并出画面 " + seconds(change.recordingAt - change.attachedAt)
                    + "、开录到写进第一笔 " + seconds(now - change.recordingAt) + "，共 " + seconds(now - change.startedAt)
                    + "；文件 " + (file == null ? "?" : new File(file).getName()) + "；" + othersDuring(change));
        }
        notifyLanes();
    }

    /**
     * 这一次接回没成（主线程）：建录制器没成、会话里没有它的录像输出或者没画面、录制器没启动、启动了 15 秒没写进数据、
     * 相机又被拿走或者出错。和离开走同一条路收拾（{@link #retireLane}），收拾完、相机不在途了就是不在录 ——
     * 下一次什么时候试、试几次，看门狗照常计（被拿走的照闸门）。不整次停：别的路照录。
     */
    private void joinFailed(String key, String why) {
        LaneChange joining = laneChanges.get(key);
        int token = lanes.leave(key, android.os.SystemClock.elapsedRealtime());
        LaneChange change = new LaneChange(liveGenerations(key), why);
        laneChanges.put(key, change);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：单独接回相机 " + laneName(key) + " 没成（" + why + "）"
                + (joining == null ? "" : "；" + othersDuring(joining))
                + "：收拾它的录制器，相机关完、判完，按看门狗的节奏再接");
        retireLane(key, token, change);
        notifyLanes();
    }

    /**
     * 一路的录制器报写不进（主线程；它的裁判：15 秒没写进新数据）。
     *
     * <ul>
     *   <li>接回中的一路（还没写进第一笔）：这一次接回没成，不整次停；</li>
     *   <li>在录的一路，而它的相机没了、在重开（设备错误之后看门狗在救）：是相机的事，这一路单独离开 ——
     *       它是最后一路在录的才整次停；</li>
     *   <li>别的（相机好好的，写不进是盘的事）：和以前一样整次停（{@link #onWriteStalled}）。</li>
     * </ul>
     *
     * @param token 报上来的那个录制器是这一路第几个（这一路离开过、接回过，之前那个报的不算）
     */
    private void onLaneWriteStalled(String key, int token, long stalledMs, boolean everWrote) {
        RecordingLanes.State state = lanes.state(key);
        if (state != null && !lanes.current(key, token)) {
            return;
        }
        if (state == RecordingLanes.State.JOINING) {
            joinFailed(key, "录制器启动了，" + (stalledMs / 1000) + " 秒没写进数据");
            return;
        }
        SingleCamera camera = cameras.get(key);
        if (state == RecordingLanes.State.LIVE && camera != null && (camera.deviceLost() || camera.isBusy())) {
            String why = (stalledMs / 1000) + " 秒没写进数据，它的相机" + (camera.deviceLost() ? "没了" : "在重开");
            leaveLane(key, why, () -> {
                com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + laneName(key) + why
                        + "，它是最后一路在录的：整次停录");
                onWriteStalled(stalledMs, everWrote);
            });
            return;
        }
        onWriteStalled(stalledMs, everWrote);
    }

    /** 一路的录制器写进了第一笔数据（主线程）：接回中的那一路就算接回了。 */
    private void onLaneFirstData(String key, int token) {
        if (lanes.current(key, token) && lanes.state(key) == RecordingLanes.State.JOINING) {
            joinLive(key);
        }
    }

    /** 这一次录像的每一路此刻在不在录（{@link RecordingLanes#lastLiveLane} 用）：状态是在录、录制器开过录又没叫停。 */
    private Map<String, Boolean> liveLanes() {
        Map<String, Boolean> live = new HashMap<>();
        for (String key : recordingPlan.keySet()) {
            CodecVideoRecorder recorder = codecRecorders.get(key);
            live.put(key, lanes.state(key) == RecordingLanes.State.LIVE && recorder != null && recorder.isLive());
        }
        return live;
    }

    /** 黑匣子：此刻在录的几路，「环视、前座舱」。 */
    private String liveNames() {
        StringBuilder sb = new StringBuilder();
        for (String key : recordingPlan.keySet()) {
            if (lanes.state(key) == RecordingLanes.State.LIVE) {
                sb.append(sb.length() > 0 ? "、" : "").append(roleName(key));
            }
        }
        return sb.toString();
    }

    /** 除了这一路，在录的几路此刻的会话代数：一路开始离开 / 接回时记下，结束时对。 */
    private Map<String, Integer> liveGenerations(String exceptKey) {
        Map<String, Integer> generations = new LinkedHashMap<>();
        for (String key : recordingPlan.keySet()) {
            SingleCamera camera = cameras.get(key);
            if (!key.equals(exceptKey) && camera != null && lanes.state(key) == RecordingLanes.State.LIVE) {
                generations.put(key, camera.sessionGeneration());
            }
        }
        return generations;
    }

    /** 黑匣子：这一段里别的在录的几路最长断帧多久、会话动没动过 —— 车上核对「一路离开、接回时别的路没被动到」看这一句。 */
    private String othersDuring(LaneChange change) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : change.generations.entrySet()) {
            SingleCamera camera = cameras.get(entry.getKey());
            if (camera == null) {
                continue;
            }
            long gap = camera.longestFrameGapSince(change.startedAt);
            sb.append(sb.length() > 0 ? "，" : "").append(roleName(entry.getKey()))
                    .append(gap < SingleCamera.NOTABLE_GAP_MS ? "最长断帧不到 " + seconds(SingleCamera.NOTABLE_GAP_MS)
                            : "最长断帧 " + seconds(gap))
                    .append(camera.sessionGeneration() == entry.getValue() ? "、会话没动" : "、会话被重建过");
        }
        return sb.length() == 0 ? "这段时间里没有别的路在录" : "这段时间里" + sb;
    }

    /**
     * 共用的分段时间戳刚生成了一个新的（主线程）：单独接回、名字还和别的路错开着的那几路马上跟着切、拿同一个名字
     * （{@link CodecVideoRecorder#switchNow}；名字本来就对得上的什么都不做）。
     */
    private void alignJoinedLanes() {
        for (CodecVideoRecorder recorder : codecRecorders.values()) {
            recorder.switchNow();
        }
    }

    /** 放掉一个没开过录、用不上了的录制器：放在收拾线程上（拆 GL、等线程退出要几百毫秒）。 */
    private void releaseLater(CodecVideoRecorder recorder) {
        teardownsRunning.incrementAndGet();
        teardown.execute(() -> {
            try {
                recorder.release();
            } catch (Exception e) {
                AppLog.e(TAG, "Error releasing an unused codec recorder", e);
            } finally {
                teardownsRunning.decrementAndGet();
            }
        });
    }

    /** 存储检查看哪个目录：录像实际写进的那个（换过盘就是新盘）；中转写入时看最终目录。 */
    private File guardedVideoDir() {
        File actual = StorageHelper.lastRecordingDir();
        if (!isRecording || useRelayWrite || actual == null) {
            return StorageHelper.getVideoDir(context);
        }
        return actual;
    }

    /**
     * 录像盘写不进时还能写到哪，给录制器换盘用。
     *
     * <p>别的挂着的 U 盘，设定的那个优先、其余按剩余空间；开发者放行时最后是内置存储。
     * 中转写入时录制器写的是内置缓存，U 盘掉不掉线它感觉不到，不换。</p>
     */
    private List<File> fallbackDirsFor(Set<String> deadVolumes) {
        List<File> dirs = new ArrayList<>();
        AppConfig config = new AppConfig(context);
        if (config.shouldUseRelayWrite()) {
            return dirs;
        }
        StorageHelper.clearCache();
        File internal = StorageHelper.isInternalStorageAllowed()
                ? Environment.getExternalStorageDirectory() : null;
        for (File root : FallbackVolumes.rank(StorageHelper.mountedVolumes(), config.getCustomSdCardPath(),
                deadVolumes, StorageHelper::getAvailableSpace, internal)) {
            dirs.add(StorageHelper.videoDirOn(root));
        }
        return dirs;
    }

    /** 录制器换了盘：记下实际写到哪、状态条那一格跟着变、存储检查改看新盘。 */
    private void noteRelocated(File dir) {
        StorageHelper.noteRecordingDir(dir);
        String custom = new AppConfig(context).getCustomSdCardPath();
        boolean offTarget = custom != null && !custom.isEmpty()
                && !dir.getAbsolutePath().startsWith(custom);
        StorageHelper.noteRecordingFallback(offTarget ? StorageHelper.volumeOf(dir.getAbsolutePath()) : null,
                offTarget ? StorageHelper.volumeOf(custom) : null);
        checkStorage("换盘");
        com.kooo.evcam.storage.StorageState.refresh(context, "relocated");
    }

    public void setWriteStallCallback(WriteStallCallback callback) {
        this.writeStallCallback = callback;
    }

    /** 录制器报写不进文件了（主线程）。停不停、接不接由 RecordingCoordinator 判。 */
    private void onWriteStalled(long stalledMs, boolean everWrote) {
        if (!isRecording || interruptReported) {
            return;
        }
        interruptReported = true;
        if (writeStallCallback != null) {
            writeStallCallback.onWriteStalled(stalledMs, everWrote);
        } else {
            stopRecording();
        }
    }

    private CorruptedFilesCallback corruptedFilesCallback;
    private CodecFallbackCallback codecFallbackCallback;
    private FirstDataWrittenCallback firstDataWrittenCallback;
    private TimestampUpdateCallback timestampUpdateCallback;
    private boolean hasNotifiedFirstDataWritten = false;  // 是否已通知首次写入（每次录制只通知一次）

    /**
     * 这次录制有没有写出过第一笔数据、什么时候写出的。
     *
     * <p>和 {@code hasNotifiedFirstDataWritten} 分开记：那个管的是「通知过没有」，只在有人听时才有意义。
     * 主界面重建的那段时间里没人听，第一笔数据恰好在这时写出，新界面就等不到这次通知 ——
     * 按钮会一直停在「正在准备」。所以新界面接上时直接来问这两个值。</p>
     */
    private volatile boolean firstDataWritten = false;
    private volatile long firstDataWrittenAtMs = 0;

    public void setStatusCallback(StatusCallback callback) {
        this.statusCallback = callback;
    }

    public void setPreviewSizeCallback(PreviewSizeCallback callback) {
        this.previewSizeCallback = callback;
    }
    
    public void setCorruptedFilesCallback(CorruptedFilesCallback callback) {
        this.corruptedFilesCallback = callback;
    }
    
    public void setPipelineCallback(PipelineCallback callback) {
        this.pipelineCallback = callback;
    }
    
    public void setSegmentSwitchCallback(SegmentSwitchCallback callback) {
        this.segmentSwitchCallback = callback;
    }

    public void setStorageFullCallback(StorageFullCallback callback) {
        this.storageFullCallback = callback;
    }

    /** 这次录制写出过第一笔数据没有。见 {@link #firstDataWritten}。 */
    public boolean hasWrittenFirstData() {
        return firstDataWritten;
    }

    /** 第一笔数据写出的时间（墙钟毫秒）；还没写出时是 0。新界面拿它接着计时。 */
    public long getFirstDataWrittenAtMs() {
        return firstDataWrittenAtMs;
    }

    /** 当前是第几个分段，从 0 数。 */
    public int getCurrentSegmentIndex() {
        return Math.max(0, lastNotifiedSegmentIndex);
    }

    /**
     * 主界面要走了、录制管线留下：把它设的回调换成什么都不做的实现。
     *
     * <h3>为什么不直接置空</h3>
     *
     * <p>这些回调大多在相机线程上「先判空、再调用」。主界面在主线程上把字段置空，
     * 正好落在两步之间，相机线程就空指针崩溃 —— 而且是在录制中。换成空实现就没有这个窗口，
     * 也不再握着那个已经销毁的界面。</p>
     *
     * <p>盘满、写不进、相机被拿走、一路离开 / 接回、开录 / 停录走到哪一步这几个<b>不在这里动</b>：它们是
     * RecordingCoordinator 接的，它不随主界面走 —— 换掉的话主界面一重建，录像就没人停、没人接了。</p>
     */
    public void detachUiCallbacks() {
        statusCallback = (cameraId, status) -> { };
        previewSizeCallback = (cameraKey, cameraId, previewSize) -> { };
        corruptedFilesCallback = deletedFiles -> { };
        segmentSwitchCallback = newSegmentIndex -> { };
        codecFallbackCallback = () -> { };
        firstDataWrittenCallback = () -> { };
        timestampUpdateCallback = newTimestamp -> { };
        AppLog.i(TAG, "主界面已离开，回调换成空实现，录制管线继续 recording=" + isRecording);
    }

    public void setCodecFallbackCallback(CodecFallbackCallback callback) {
        this.codecFallbackCallback = callback;
    }

    public void setFirstDataWrittenCallback(FirstDataWrittenCallback callback) {
        this.firstDataWrittenCallback = callback;
    }

    public void setTimestampUpdateCallback(TimestampUpdateCallback callback) {
        this.timestampUpdateCallback = callback;
    }

    public void setMaxOpenCameras(int maxOpenCameras) {
        this.maxOpenCameras = Math.max(1, maxOpenCameras);
    }

    /**
     * 设置软编码录制模式（用于 L6/L7 等不支持 MediaRecorder 直接录制的车机平台）
     * 在此模式下，使用 OpenGL 渲染 + MediaCodec 编码 + MediaMuxer 写入文件
     * 
     * 优点：
     * - 预览保持流畅，不会冻结
     * - 不依赖硬件对 MediaRecorder Surface 的支持
     * 
     * @param enabled true 表示启用软编码录制模式
     */
    public void setCodecRecordingMode(boolean enabled) {
        this.useCodecRecording = enabled;
        AppLog.d(TAG, "Codec recording mode: " + (enabled ? "ENABLED" : "DISABLED"));
    }

    /**
     * 检查是否使用软编码录制模式
     */
    public boolean isCodecRecordingMode() {
        return useCodecRecording;
    }

    /**
     * 获取指定位置的摄像头实例
     * @param position 位置（front/back/left/right）
     * @return SingleCamera实例，如果不存在则返回null
     */
    /** 当前已初始化的摄像头路数。用于判断后台那份实例是否还匹配当前车型。 */
    public int getCameraCount() {
        return cameras.size();
    }

    public SingleCamera getCamera(String position) {
        return cameras.get(position);
    }

    public void updatePreviewTextureViews(TextureView frontView,
                                          TextureView backView,
                                          TextureView leftView,
                                          TextureView rightView) {
        updatePreviewTextureView("front", frontView);
        updatePreviewTextureView("back", backView);
        updatePreviewTextureView("left", leftView);
        updatePreviewTextureView("right", rightView);
    }

    /**
     * 主界面的画布没了（退后台、界面重建）。预览输出从这一路摘掉，重配会话：有人要画面（在录、后视镜）、
     * 或者在等关的那 30 秒里（照常出帧，画面送进不显示的出帧口），都不能让会话对着一块已经没了的画布。
     * 只有马上就关的（熄屏时现在关、没有前台服务时 1.5 秒后关）不重配：不必为了摘掉预览再进一次相机服务。
     */
    public void onPreviewTextureDestroyed(String cameraKey) {
        SingleCamera camera = cameras.get(cameraKey);
        if (camera == null) {
            return;
        }
        camera.setTextureView(null);
        camera.clearPreviewSurface();
        if (closesSoon(camera)) {
            AppLog.d(TAG, "Preview texture for " + cameraKey + " gone and the camera closes in a moment; not rebuilding the session");
            return;
        }
        camera.recreateSession();
    }

    /**
     * 这一路马上就要关了：关相机的那一轮正在走，或者没人要相机、也不在等那 30 秒（熄屏时现在关、没有前台服务时 1.5 秒后关）。
     * 摘了它的输出也不为它重建会话 —— 不为摘一个输出再进一次相机服务，更不在关之前动它：环视会话还在配就去关，
     * 是车上见过的坏样子（关得慢、下一次打开没画面，见类顶上的说明）。画布没了、停录收拾完、登记表变了摘出帧口，都按这一条。
     */
    private static boolean closesSoon(SingleCamera camera) {
        return closingAll || (!CameraNeeds.current().heldByAnyone() && !camera.keepsStreaming());
    }

    private void updatePreviewTextureView(String cameraKey, TextureView view) {
        SingleCamera camera = cameras.get(cameraKey);
        if (camera == null) {
            return;
        }
        camera.setTextureView(view);
        camera.recreateSession();
    }

    /**
     * 手动触发所有已有 previewSize 的摄像头的 PreviewSizeCallback。
     * 用于后台初始化（CameraManagerHolder）复用场景：
     * 摄像头在后台服务里已打开并确定了预览尺寸，
     * 但 MainActivity 的回调（旋转变换等）此时尚未注册。
     * 在 MainActivity 注册回调后调用此方法，补偿缺失的回调触发。
     */
    public void firePreviewSizeCallbacks() {
        if (previewSizeCallback == null) return;
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            SingleCamera camera = entry.getValue();
            Size size = camera.getPreviewSize();
            if (size != null) {
                previewSizeCallback.onPreviewSizeChosen(entry.getKey(), camera.getCameraId(), size);
            }
        }
    }

    /**
     * 初始化摄像头
     * 支持 null 参数以适配不同数量的摄像头配置（1摄/2摄/4摄）
     */
    public void initCameras(String frontId, TextureView frontView,
                           String backId, TextureView backView,
                           String leftId, TextureView leftView,
                           String rightId, TextureView rightView) {

        // 清空之前的摄像头实例；排着队要开的也作废（关的队自己拿着相机对象，照常关）
        cancelOpenInOrder();
        cameras.clear();

        // 同一路相机只建一份：两个槽位指到同一个 id（手动映射填错）时，后面的槽位空着。
        // 以前是两份都建、标成主 / 从实例，从实例什么都不做 —— 十处守卫只为这一种配置错误
        Set<String> used = new HashSet<>();
        // 根据参数创建摄像头实例（支持 null TextureView 用于后台初始化）
        if (frontId != null && used.add(frontId)) {
            SingleCamera frontCamera = new SingleCamera(context, frontId, frontView);
            frontCamera.setCameraPosition("front");
            cameras.put("front", frontCamera);
            AppLog.d(TAG, "初始化前摄像头: ID=" + frontId);
        }

        if (backId != null && used.add(backId)) {
            SingleCamera backCamera= new SingleCamera(context, backId, backView);
            backCamera.setCameraPosition("back");
            cameras.put("back", backCamera);
            AppLog.d(TAG, "初始化后摄像头: ID=" + backId);
        }

        if (leftId != null && used.add(leftId)) {
            SingleCamera leftCamera= new SingleCamera(context, leftId, leftView);
            leftCamera.setCameraPosition("left");
            cameras.put("left", leftCamera);
            AppLog.d(TAG, "初始化左摄像头: ID=" + leftId);
        }

        if (rightId != null && used.add(rightId)) {
            SingleCamera rightCamera= new SingleCamera(context, rightId, rightView);
            rightCamera.setCameraPosition("right");
            cameras.put("right", rightCamera);
            AppLog.d(TAG, "初始化右摄像头: ID=" + rightId);
        }
        
        AppLog.d(TAG, "共初始化 " + cameras.size() + " 个摄像头");

        // 为每个摄像头设置回调
        CameraCallback callback = new CameraCallback() {
            @Override
            public void onCameraOpened(String cameraId) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " opened");
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, STATUS_OPENED);
                }
            }

            @Override
            public void onCameraConfigured(String cameraId) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " configured");
                onCameraSettled(cameraId, true);
                // 接回录像的那一路在等这一下：配好的会话里有没有它的录像输出，马上看（主线程，见 checkJoin）
                mainHandler.post(joinPoll);
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, STATUS_PREVIEW_STARTED);
                }

                // 检查是否有录制器正在等待会话重新配置（分段切换）
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        String key = entry.getKey();
                        VideoRecorder recorder = recorders.get(key);

                        if (recorder != null && recorder.isWaitingForSessionReconfiguration()) {
                            AppLog.d(TAG, "Camera " + cameraId + " session reconfigured, starting next segment recording");
                            recorder.clearWaitingForSessionReconfiguration();
                            recorder.startRecording();
                        }
                        break;
                    }
                }

                // 检查是否所有会话都已配置完成（线程安全处理）
                synchronized (sessionLock) {
                    if (expectedSessionCount > 0) {
                        // 找到对应的摄像头 key 并标记为就绪
                        String cameraKey = null;
                        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                            if (entry.getValue().getCameraId().equals(cameraId)) {
                                cameraKey = entry.getKey();
                                break;
                            }
                        }
                        if (cameraKey != null) {
                            Boolean wasReady = cameraSessionReady.get(cameraKey);
                            if (wasReady != null && wasReady) {
                                AppLog.d(TAG, "Camera " + cameraKey + " (id=" + cameraId + ") session already marked ready, skipping count");
                            } else {
                                cameraSessionReady.put(cameraKey, true);
                                sessionConfiguredCount++;
                                AppLog.d(TAG, "Camera " + cameraKey + " (id=" + cameraId + ") session marked as ready");
                                AppLog.d(TAG, "Session configured: " + sessionConfiguredCount + "/" + expectedSessionCount);
                            }
                        }

                        if (sessionConfiguredCount >= expectedSessionCount) {
                            // 所有会话都已配置完成，执行待处理的录制启动
                            final Runnable recordingTask = pendingRecordingStart;
                            if (recordingTask != null) {
                                AppLog.d(TAG, "All sessions configured, starting recording...");
                                // 取消超时任务
                                if (sessionTimeoutRunnable != null) {
                                    mainHandler.removeCallbacks(sessionTimeoutRunnable);
                                    sessionTimeoutRunnable = null;
                                }
                                pendingRecordingStart = null;
                                sessionConfiguredCount = 0;
                                expectedSessionCount = 0;
                                // 延迟 300ms 再启动录制，让 Camera Session 稳定
                                // 某些车机设备需要这个延迟才能正确将帧发送到 MediaRecorder Surface
                                mainHandler.postDelayed(recordingTask, 300);
                            }
                        }
                    }
                }
            }

            @Override
            public void onCameraClosed(String cameraId) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " closed");
                onCameraClosedInRound(cameraId);
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, STATUS_CLOSED);
                }
            }

            @Override
            public void onCameraError(String cameraId, int errorCode) {
                String errorMsg = getErrorMessage(errorCode);
                AppLog.e(TAG, "Callback: Camera " + cameraId + " error: " + errorCode + " - " + errorMsg);
                onCameraSettled(cameraId, false);
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, STATUS_ERROR_PREFIX + errorCode);
                }
                // 录着的一路出了错：这一路单独离开、整次停、还是留着录制器等看门狗重开，挪到主线程上判
                onRecordingCameraError(cameraId, errorCode);

                // 如果在等待会话配置期间发生错误，减少期望计数（线程安全处理）
                synchronized (sessionLock) {
                    if (expectedSessionCount > 0 && errorCode == -3) {
                        expectedSessionCount--;
                        AppLog.d(TAG, "Session configuration failed, adjusted expected count: " + sessionConfiguredCount + "/" + expectedSessionCount);

                        // 检查是否所有剩余会话都已配置完成
                        if (sessionConfiguredCount >= expectedSessionCount && expectedSessionCount > 0) {
                            final Runnable recordingTask = pendingRecordingStart;
                            if (recordingTask != null) {
                                AppLog.d(TAG, "Remaining sessions configured, starting recording...");
                                // 取消超时任务
                                if (sessionTimeoutRunnable != null) {
                                    mainHandler.removeCallbacks(sessionTimeoutRunnable);
                                    sessionTimeoutRunnable = null;
                                }
                                pendingRecordingStart = null;
                                // 延迟 300ms 再启动录制，让 Camera Session 稳定
                                mainHandler.postDelayed(recordingTask, 300);
                            }
                            sessionConfiguredCount = 0;
                            expectedSessionCount = 0;
                        } else if (expectedSessionCount == 0) {
                            // 所有会话都失败了：开录到此为止。以前到这里就清掉待办、什么都不报，
                            // 协调器一直以为在录（2026-10-05）
                            AppLog.e(TAG, "All sessions failed to configure");
                            if (sessionTimeoutRunnable != null) {
                                mainHandler.removeCallbacks(sessionTimeoutRunnable);
                                sessionTimeoutRunnable = null;
                            }
                            sessionConfiguredCount = 0;
                            expectedSessionCount = 0;
                            if (pendingRecordingStart != null) {
                                pendingRecordingStart = null;
                                reportStartFailed(pendingStartGeneration, "所有相机会话都配置失败");
                            }
                        }
                    }
                }
            }

            @Override
            public void onPreviewSizeChosen(String cameraId, Size previewSize) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " preview size: " + previewSize);
                // 找到对应的 camera key
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        if (previewSizeCallback != null) {
                            previewSizeCallback.onPreviewSizeChosen(entry.getKey(), cameraId, previewSize);
                        }
                    }
                }
            }
        };

        // 为已初始化的摄像头设置回调
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            entry.getValue().setCallback(callback);
        }

        // 为已初始化的摄像头创建录制器实例
        recorders.clear();
        if (frontId != null && cameras.containsKey("front")) {
            VideoRecorder recorder = new VideoRecorder(frontId);
            recorder.setTimestampProvider(segmentTimestampProvider);  // 设置统一时间戳提供者
            recorders.put("front", recorder);
        }
        if (backId != null && cameras.containsKey("back")) {
            VideoRecorder recorder = new VideoRecorder(backId);
            recorder.setTimestampProvider(segmentTimestampProvider);  // 设置统一时间戳提供者
            recorders.put("back", recorder);
        }
        if (leftId != null && cameras.containsKey("left")) {
            VideoRecorder recorder = new VideoRecorder(leftId);
            recorder.setTimestampProvider(segmentTimestampProvider);  // 设置统一时间戳提供者
            recorders.put("left", recorder);
        }
        if (rightId != null && cameras.containsKey("right")) {
            VideoRecorder recorder = new VideoRecorder(rightId);
            recorder.setTimestampProvider(segmentTimestampProvider);  // 设置统一时间戳提供者
            recorders.put("right", recorder);
        }

        // 为每个录制器设置回调
        RecordCallback recordCallback = new RecordCallback() {
            @Override
            public void onRecordStart(String cameraId) {
                AppLog.d(TAG, "Recording started for camera " + cameraId);
            }

            @Override
            public void onRecordStop(String cameraId) {
                AppLog.d(TAG, "Recording stopped for camera " + cameraId);
            }

            @Override
            public void onRecordError(String cameraId, String error) {
                AppLog.e(TAG, "Recording error for camera " + cameraId + ": " + error);
                com.kooo.evcam.blackbox.BlackBox.noteImportant("录制器报错（相机 " + cameraId + "）：" + error);
            }

            @Override
            public void onPrepareSegmentSwitch(String cameraId, int currentSegmentIndex) {
                AppLog.d(TAG, "Prepare segment switch for camera " + cameraId + " (current segment: " + currentSegmentIndex + ")");
                // 找到对应的 camera 并切换到仅预览模式
                // 使用优化的 switchToPreviewOnlyMode() 方法：预览继续流畅，只停止向录制 Surface 发送帧
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        SingleCamera camera = entry.getValue();
                        // 优先使用新的仅预览模式（保持预览不卡顿）
                        boolean success = camera.switchToPreviewOnlyMode();
                        AppLog.d(TAG, "Camera " + cameraId + " switched to preview-only mode: " + (success ? "success" : "fallback to pause"));
                        break;
                    }
                }
            }

            @Override
            public void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath) {
                AppLog.d(TAG, "Segment switch for camera " + cameraId + " to segment " + newSegmentIndex);
                // 找到对应的 camera key 和 camera
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        String key = entry.getKey();
                        SingleCamera camera = entry.getValue();
                        VideoRecorder recorder = recorders.get(key);

                        if (camera != null && recorder != null) {
                            // 如果使用中转写入，将上一个分段的文件传输到最终目录
                            if (useRelayWrite && finalSaveDir != null && newSegmentIndex > 0 && completedFilePath != null) {
                                // 传输已完成的文件（由回调提供确切路径，避免传输正在录制的新文件）
                                scheduleRelayTransfer(completedFilePath);
                            }
                            
                            // 更新录制 Surface 并重新创建会话（MediaRecorder 模式）
                            camera.setRecordSurface(recorder.getSurface(), false);
                            camera.recreateSession();
                            AppLog.d(TAG, "Recreated session for camera " + cameraId + " after segment switch");
                        }
                        
                        // 通知分段切换回调（只通知一次，第一个触发的摄像头会通知）
                        if (segmentSwitchCallback != null && newSegmentIndex > lastNotifiedSegmentIndex) {
                            lastNotifiedSegmentIndex = newSegmentIndex;
                            segmentSwitchCallback.onSegmentSwitch(newSegmentIndex);
                        }
                        checkStorage("分段切换");
                        break;
                    }
                }
            }

            @Override
            public void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles) {
                if (deletedFiles != null && !deletedFiles.isEmpty()) {
                    AppLog.w(TAG, "Corrupted files deleted for camera " + cameraId + ": " + deletedFiles.size() + " file(s)");
                    for (String file : deletedFiles) {
                        AppLog.d(TAG, "  Deleted: " + file);
                    }
                    // 通知 MainActivity 显示弹窗
                    if (corruptedFilesCallback != null) {
                        mainHandler.post(() -> corruptedFilesCallback.onCorruptedFilesDeleted(deletedFiles));
                    }
                }
            }

            @Override
            public void onRecordingRebuildRequested(String cameraId, String reason) {
                AppLog.e(TAG, "Recording rebuild requested for camera " + cameraId + ", reason: " + reason);
                handleRecordingRebuildRequest(cameraId, reason);
            }

            @Override
            public void onRecordingRelocated(String cameraId, File dir, String why, long rescuedMs) {
                // MediaRecorder 录制器不换盘
            }

            @Override
            public void onFirstDataWritten(String cameraId) {
                AppLog.d(TAG, "First data written for camera " + cameraId);
                // 只在第一个摄像头首次写入时通知外部（每次录制只通知一次）
                if (!firstDataWritten) {
                    firstDataWritten = true;
                    firstDataWrittenAtMs = System.currentTimeMillis();
                }
                if (!hasNotifiedFirstDataWritten && firstDataWrittenCallback != null) {
                    hasNotifiedFirstDataWritten = true;
                    AppLog.d(TAG, "Notifying external: first data written, recording truly started");
                    mainHandler.post(() -> firstDataWrittenCallback.onFirstDataWritten());
                }
            }
        };

        // 为已创建的录制器设置回调
        for (Map.Entry<String, VideoRecorder> entry : recorders.entrySet()) {
            entry.getValue().setCallback(recordCallback);
        }

        AppLog.d(TAG, "Cameras initialized");
    }

    /**
     * 获取错误信息描述
     */
    private String getErrorMessage(int errorCode) {
        switch (errorCode) {
            case 1: // ERROR_CAMERA_IN_USE
                return "摄像头正在被使用";
            case 2: // ERROR_MAX_CAMERAS_IN_USE
                return "已达到最大摄像头数量";
            case 3: // ERROR_CAMERA_DISABLED
                return "摄像头被禁用";
            case 4: // ERROR_CAMERA_DEVICE
                return "摄像头设备错误(资源不足?)";
            case 5: // ERROR_CAMERA_SERVICE
                return "摄像头服务错误";
            case -1:
                return "访问失败";
            case -2:
                return "权限不足";
            case -3:
                return "会话配置失败";
            case -4:
                return "被相机服务断开(onDisconnected)";
            default:
                return "未知错误(" + errorCode + ")";
        }
    }

    /** 开关里的一路：key 只给日志，相机对象自己拿着 —— release() 会先把 cameras 清空，按 key 再查就查不到了。 */
    private static final class Step {
        final String key;
        final SingleCamera camera;
        long startedAt;

        Step(String key, SingleCamera camera) {
            this.key = key;
            this.camera = camera;
        }
    }

    /** 黑匣子里的叫法：环视 / 后座舱 / 前座舱；别的 key 原样。 */
    static String roleName(String key) {
        if (CameraSlots.KEY_SURROUND.equals(key)) {
            return "环视";
        }
        if (CameraSlots.KEY_CABIN_REAR.equals(key)) {
            return "后座舱";
        }
        if (CameraSlots.KEY_CABIN_FRONT.equals(key)) {
            return "前座舱";
        }
        return key;
    }

    /** 一路走完，记到这一轮的轨迹里；整轮走完黑匣子记一行「环视 1.1s → 后座舱 0.2s → 前座舱 0.3s」（关的那行按关完的先后）。 */
    private static void noteStep(List<String> trail, Step step) {
        if (step != null) {
            trail.add(roleName(step.key) + " "
                    + String.format(java.util.Locale.US, "%.1fs",
                    (android.os.SystemClock.uptimeMillis() - step.startedAt) / 1000f));
        }
    }

    private static void noteTrail(String what, List<String> trail, String separator) {
        if (!trail.isEmpty()) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant(what + "：" + String.join(separator, trail));
            trail.clear();
        }
    }

    /**
     * 打开所有摄像头 —— 按次序，一路出了画面再开下一路（项目所有者 2026-10-08 定）：
     * 环视先开，再后座舱，最后前座舱；开第一路之前先等所有在关的相机关完（不在别的相机关的途中开）。
     * 已经在按次序开的话，这一次只把还没开的排进队。
     *
     * <p>被别的程序拿走的那一路不排（2026-10-10）：它只从看门狗的闸门重开（{@link CameraTaken#gate}）。
     * 以前每次登记表一变都排它一次 —— 熄屏那一下就排过（「按次序开相机：后座舱 0.0s」），在相机服务交接的中途。</p>
     */
    public void openAllCameras() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            rollCall.post(this::openAllCameras);
            return;
        }
        activeCameraKeys.clear();
        Set<String> openedIds = new HashSet<>();
        List<Step> order = new ArrayList<>();
        for (String key : CameraSlots.openOrder(cameras.keySet())) {
            if (activeCameraKeys.size() >= maxOpenCameras) {
                break;
            }
            SingleCamera camera = cameras.get(key);
            if (camera == null || !openedIds.add(camera.getCameraId())) {
                continue;
            }
            activeCameraKeys.add(key);
            if (CameraTaken.benched(camera.getCameraId())) {
                noteBenchSkip(key, camera);
                continue;
            }
            order.add(new Step(key, camera));
        }
        AppLog.d(TAG, "Opening cameras in order: " + activeCameraKeys);
        if (openingStep != null || openWaitStartedAt != 0) {
            // 正在开（或者还在等别的相机关完）：没排进去的排进去，照原来的节奏走
            for (Step step : order) {
                if ((openingStep == null || step.camera != openingStep.camera) && !queued(openQueue, step.camera)) {
                    openQueue.add(step);
                }
            }
            return;
        }
        openQueue.clear();
        openQueue.addAll(order);
        openNext();
    }

    private static boolean queued(java.util.Collection<Step> queue, SingleCamera camera) {
        for (Step step : queue) {
            if (step.camera == camera) {
                return true;
            }
        }
        return false;
    }

    /**
     * 开下一路：先等这一轮关完（环视、座舱都关完，任何一份实例）—— 环视要在没有别的相机在动的时候开；
     * 已经开着、在出画面的跳过，被别的程序拿走的也跳过（它只从看门狗的闸门重开）；
     * 其余的开一路，等它出画面或报错（最多 OPEN_STEP_MAX_MS）。
     */
    private void openNext() {
        rollCall.removeCallbacks(openStepTimeout);
        rollCall.removeCallbacks(openWhenClosed);
        noteStep(openTrail, openingStep);
        openingStep = null;
        long now = android.os.SystemClock.uptimeMillis();
        if (!openQueue.isEmpty() && (closingAll || SingleCamera.anyClosing())) {
            if (openWaitStartedAt == 0) {
                openWaitStartedAt = now;
            }
            if (now - openWaitStartedAt < OPEN_WAIT_FOR_CLOSES_MS) {
                rollCall.postDelayed(openWhenClosed, OPEN_WAIT_POLL_MS);
                return;
            }
            com.kooo.evcam.blackbox.BlackBox.noteImportant("开相机：等别的相机关完等了 "
                    + (OPEN_WAIT_FOR_CLOSES_MS / 1000) + " 秒还没完（相机服务卡住？），照样开");
        }
        if (openWaitStartedAt != 0) {
            long waited = now - openWaitStartedAt;
            openWaitStartedAt = 0;
            if (waited >= 500) {
                com.kooo.evcam.blackbox.BlackBox.noteImportant("开相机：等上一次关完用了 " + waited + "ms");
            }
        }
        while (!openQueue.isEmpty()) {
            Step step = openQueue.poll();
            if (step.camera.isSessionReady() && step.camera.hasFramesWithin(FIRST_FRAME_FRESH_MS)) {
                continue;
            }
            if (CameraTaken.benched(step.camera.getCameraId())) {
                noteBenchSkip(step.key, step.camera);   // 排进队之后才被拿走的：同样不开
                continue;
            }
            step.startedAt = android.os.SystemClock.uptimeMillis();
            openingStep = step;
            step.camera.openCamera();   // 已经在开、在重连的它自己会跳过；我们照样等它的「配好 / 报错」
            rollCall.postDelayed(openStepTimeout, OPEN_STEP_MAX_MS);
            return;
        }
        noteTrail("按次序开相机", openTrail, " → ");
    }

    private void openStepTimedOut() {
        Step step = openingStep;
        if (step == null) {
            return;
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("按次序开相机：" + roleName(step.key) + "(" + step.camera.getCameraId()
                + ") 等了 " + (OPEN_STEP_MAX_MS / 1000) + " 秒还没出画面，先开下一路");
        openNext();
    }

    /**
     * 配好了、或者报错了：是正在等的那一路就往下走。报错的直接开下一路；配好的还要等它真出画面 ——
     * 三路配置下环视配好会话之后常常一帧都不出（2026-10-08），「配好」不等于「在出画面」，
     * 这时就开下一路等于三路一起起管线。回调来自相机线程，挪到主线程。
     */
    private void onCameraSettled(String cameraId, boolean configured) {
        rollCall.post(() -> {
            Step step = openingStep;
            if (step == null || !step.camera.getCameraId().equals(cameraId)) {
                return;
            }
            if (configured) {
                waitForFirstFrame(step);
            } else {
                openNext();
            }
        });
    }

    private void waitForFirstFrame(Step step) {
        if (openingStep != step) {
            return;
        }
        if (step.camera.hasFramesWithin(FIRST_FRAME_FRESH_MS)) {
            openNext();
            return;
        }
        rollCall.postDelayed(() -> waitForFirstFrame(step), FIRST_FRAME_POLL_MS);
    }

    private void cancelOpenInOrder() {
        rollCall.removeCallbacks(openStepTimeout);
        rollCall.removeCallbacks(openWhenClosed);
        openWaitStartedAt = 0;
        openQueue.clear();
        openingStep = null;
        openTrail.clear();
    }

    /** 按次序开相机走完了没有。开录前要等它：没开完就开录，晚开的那一路这一段就录不上。 */
    public boolean openInOrderDone() {
        return openingStep == null && openQueue.isEmpty();
    }

    /** 有没有哪份管理器的相机还在关（这一轮还没全部关完）。 */
    public static boolean closingAll() {
        return closingAll;
    }

    /**
     * 关闭所有摄像头
     */
    public void closeAllCameras() {
        closeAllCameras(null);
    }

    /**
     * 关闭所有摄像头 —— 环视单独先关，关完了再把座舱一起关（项目所有者 2026-10-09：「依照单个环视的思路」）。
     * 环视关的时候座舱还开着、不动，和开的时候对称（环视单独先开，出了画面再开座舱）。为什么见类顶上的说明：
     * 环视比座舱晚关完，那一次关就慢、下一次打开一帧不出。每一路交给自己的相机线程去关
     * （见 {@link SingleCamera#closeCamera(String)}），这里不等；整轮关完黑匣子记一行每一路用了多久。
     * 关的途中又来叫关的（退出时 release 在「没人要」那一轮还没走完时来）：已经在这一轮里的不再排。
     *
     * @param why 为什么关（英文短语）。给了的话，每一路关完时往黑匣子记一行，带用时
     */
    public void closeAllCameras(String why) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            // 退出那条路会看这个标志等我们：先立起来，再挪到主线程
            closingAll = true;
            rollCall.post(() -> closeAllCameras(why));
            return;
        }
        cancelOpenInOrder();
        boolean running = closeRoundRunning();
        if (!running) {
            closeWhy = why;
            surroundTrail = null;
            closeTrail.clear();
        }
        Step surround = null;
        List<Step> others = new ArrayList<>();
        for (String key : CameraSlots.closeOrder(cameras.keySet())) {
            SingleCamera camera = cameras.get(key);
            if (camera == null || inCloseRound(camera)) {
                continue;
            }
            Step step = new Step(key, camera);
            if (CameraSlots.KEY_SURROUND.equals(key) && camera.holdsOrIsOpening() && surroundClosing == null) {
                surround = step;
            } else {
                others.add(step);
            }
        }
        if (surround == null && others.isEmpty()) {
            if (!running) {
                closingAll = false;   // 没有要关的、也没有在关的（release() 第二次来就是这样）
            }
            return;
        }
        closingAll = true;
        closeAfterSurround.addAll(others);
        if (surround != null) {
            surround.startedAt = android.os.SystemClock.uptimeMillis();
            surroundClosing = surround;   // 先记再关：关完的回调可能就在 closeCamera 里同步来
            rollCall.postDelayed(surroundCloseTimeout, SURROUND_CLOSE_MAX_MS);
            surround.camera.closeCamera(closeWhy);
        } else if (surroundClosing == null) {
            // 环视没开着（或者不在这一轮）：座舱现在就关
            closeTheRest();
        }
        AppLog.d(TAG, "All cameras asked to close");
    }

    /** 这一轮还没关完：环视在关、座舱在等、或者座舱在关。 */
    private boolean closeRoundRunning() {
        return surroundClosing != null || !closeAfterSurround.isEmpty() || !closingSteps.isEmpty();
    }

    private boolean inCloseRound(SingleCamera camera) {
        return (surroundClosing != null && surroundClosing.camera == camera)
                || queued(closeAfterSurround, camera) || queued(closingSteps, camera);
    }

    /** 环视关完了（或者不用关）：剩下的几路一起关。没开着的只清标志、不进相机服务，当场就完。 */
    private void closeTheRest() {
        List<Step> rest = new ArrayList<>(closeAfterSurround);
        closeAfterSurround.clear();
        for (Step step : rest) {
            step.startedAt = android.os.SystemClock.uptimeMillis();
            if (!step.camera.holdsOrIsOpening()) {
                step.camera.closeCamera(closeWhy);
                continue;
            }
            closingSteps.add(step);   // 先记再关：关完的回调可能就在 closeCamera 里同步来
            step.camera.closeCamera(closeWhy);
        }
        if (closingSteps.isEmpty()) {
            finishClose();
        } else {
            rollCall.removeCallbacks(closeAllTimeout);
            rollCall.postDelayed(closeAllTimeout, CLOSE_ALL_MAX_MS);
        }
    }

    private void surroundCloseTimedOut() {
        Step step = surroundClosing;
        if (step == null) {
            return;
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("关相机：环视等了 " + (SURROUND_CLOSE_MAX_MS / 1000)
                + " 秒还没关完（相机服务卡住？），先关座舱" + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        surroundTrail = roleName(step.key) + " >" + (SURROUND_CLOSE_MAX_MS / 1000) + "s";
        surroundClosing = null;
        closeTheRest();
    }

    private void closeAllTimedOut() {
        if (closingSteps.isEmpty()) {
            return;
        }
        StringBuilder stuck = new StringBuilder();
        for (Step step : closingSteps) {
            stuck.append(stuck.length() > 0 ? "、" : "")
                    .append(roleName(step.key)).append("(").append(step.camera.getCameraId()).append(")");
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("关相机：座舱等了 " + (CLOSE_ALL_MAX_MS / 1000)
                + " 秒还没关完：" + stuck + "，不再等" + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        closingSteps.clear();
        finishClose();
    }

    /**
     * 整轮关完：黑匣子一行「关相机：环视 0.1s → 后座舱 0.1s、前座舱 0.1s」（座舱按关完的先后），
     * 熄屏期间再带上「熄屏后 +N ms」—— 车机断电之前关没关完，看它。
     */
    private void finishClose() {
        rollCall.removeCallbacks(closeAllTimeout);
        rollCall.removeCallbacks(surroundCloseTimeout);
        closingAll = false;
        String cabins = String.join("、", closeTrail);
        String line = surroundTrail == null ? cabins
                : cabins.isEmpty() ? surroundTrail : surroundTrail + " → " + cabins;
        if (!line.isEmpty()) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("关相机：" + line
                    + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        }
        surroundTrail = null;
        closeTrail.clear();
    }

    /**
     * 这一份管线的相机都关好了：没有一路开着、在开、在关，按次序关的那一轮也走完了。
     * 熄屏录制到点停录之后，等它成立再放唤醒锁（{@code ScreenOffRecording}）。
     */
    public boolean allCamerasClosed() {
        if (closingAll) {
            return false;
        }
        for (SingleCamera camera : cameras.values()) {
            if (camera.holdsOrIsOpening()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 一路关完了：是环视就接着关座舱；是座舱就从还在关的里划掉，都关完了这一轮就完。
     * 回调可能来自相机线程，也可能就在 closeCamera 里同步来，一律挪到主线程。
     */
    private void onCameraClosedInRound(String cameraId) {
        rollCall.post(() -> {
            Step surround = surroundClosing;
            if (surround != null && surround.camera.getCameraId().equals(cameraId)) {
                rollCall.removeCallbacks(surroundCloseTimeout);
                surroundTrail = roleName(surround.key) + " " + String.format(java.util.Locale.US, "%.1fs",
                        (android.os.SystemClock.uptimeMillis() - surround.startedAt) / 1000f);
                surroundClosing = null;
                closeTheRest();
                return;
            }
            for (java.util.Iterator<Step> it = closingSteps.iterator(); it.hasNext(); ) {
                Step step = it.next();
                if (step.camera.getCameraId().equals(cameraId)) {
                    noteStep(closeTrail, step);
                    it.remove();
                    if (closingSteps.isEmpty() && surroundClosing == null && closeAfterSurround.isEmpty()) {
                        finishClose();
                    }
                    return;
                }
            }
        });
    }

    /**
     * 开始录制指定的摄像头（使用指定的时间戳和摄像头列表）
     * @param timestamp 统一的时间戳，用于所有摄像头的文件命名
     * @param enabledCameras 要录制的摄像头位置集合（如 ["front", "back"]），为 null 时录制所有摄像头
     */
    public boolean startRecording(String timestamp, Set<String> enabledCameras) {
        // 唯一的开录入口是 RecordingCoordinator：以前还有不带参数的两个重载给悬浮按钮走，
        // 那条路没有写入看门狗、没有存储检查、也不拒录内置存储（2026-09-27 审查），删了
        if (isRecording) {
            AppLog.w(TAG, "Already recording");
            return false;
        }
        if (!useCodecRecording && teardownsRunning.get() > 0) {
            // MediaRecorder 的录制器每一路一个、一直复用：上一次停录还在收拾它们（协调器等收拾等到了点），
            // 这时去准备只会两边一起动同一个录制器。按开录失败处理，过一会儿再试
            AppLog.w(TAG, "上一次停录还在收拾 MediaRecorder，这次不开");
            return false;
        }
        final int gen;
        synchronized (sessionLock) {
            gen = ++recordGeneration;
        }

        // 清除缓存的分段时间戳，开始新的录制周期
        clearCachedSegmentTimestamp();

        // 根据模式选择录制方式。返回 false 时这里不收拾：协调器按开录失败走停录，由 stopRecording 收
        boolean started = useCodecRecording
                ? startCodecRecording(timestamp, enabledCameras, gen)
                : startMediaRecorderRecording(timestamp, enabledCameras, gen);
        if (started) {
            lastStorageCheckMs = 0;
            mainHandler.removeCallbacks(storageTick);
            mainHandler.postDelayed(storageTick, STORAGE_TICK_MS);
            interruptReported = false;
            noteRecordingDir();
        }
        return started;
    }

    /**
     * 软编码录制器的回调是不是上一次录像的（之后停过录、又开过）。录制器收尾要几秒，新旧两次可能交叠：
     * 写不进、写盘跟不上、分段、换盘、第一笔数据这些只对这一次算数 —— 上一次的混进来，会把这一次打断、
     * 把分段计数和中转目标弄乱。上一次停录时的文件报告（删掉了哪些坏文件）不在这里拦，那本来就是停录之后才来的。
     */
    private boolean staleCallback(int gen, String what, String cameraId) {
        if (gen == recordGeneration) {
            return false;
        }
        AppLog.d(TAG, "Ignoring " + what + " from camera " + cameraId + " of recording gen " + gen
                + " (now gen " + recordGeneration + ")");
        return true;
    }

    /** 这一代至少一路录起来了：报给协调器。过时的（停过、又开过）不报。 */
    private void reportStarted(int gen, Set<String> active, Set<String> failed) {
        final Set<String> activeCopy = new HashSet<>(active);
        final Set<String> failedCopy = new HashSet<>(failed);
        mainHandler.post(() -> {
            if (gen != recordGeneration) {
                return;
            }
            if (pipelineCallback != null) {
                pipelineCallback.onPipelineStarted(activeCopy, failedCopy);
            }
        });
    }

    /**
     * 这一代一路都没起来：报给协调器，它走停录那条路收拾。没人接（没有协调器）就自己停。
     * 过时的（停过、又开过）不报 —— 以前上一次的失败会把这一次的录像一起停掉。
     */
    private void reportStartFailed(int gen, String why) {
        mainHandler.post(() -> {
            if (gen != recordGeneration) {
                AppLog.w(TAG, "过时的开录失败（第 " + gen + " 代，现在第 " + recordGeneration + " 代），不报: " + why);
                return;
            }
            AppLog.e(TAG, "开录失败: " + why);
            if (pipelineCallback != null) {
                pipelineCallback.onPipelineStartFailed(why);
            } else {
                stopRecording();
            }
        });
    }

    /**
     * 录像状态只在这里改。录像期间才有的登记跟着它走，不靠哪一条停录路径记得撤：
     * 变成「在录」时闪远光自动锁定开始看信号（它自己登记车辆信号，带上这次中转写入的目标目录）；
     * 变成「不在录」时 —— 停录、开录时所有相机都没起来、重建前先停 —— 车辆信号的两份登记
     * （信息条的、自动锁定的）都撤掉。以前只有 stopRecording 撤，而开录失败后 RecordingCoordinator
     * 看到「没在录」就不会再调它，登记一直挂着、车辆信号一直在收（2026-10-04 审查）。
     */
    private void setRecording(boolean recording) {
        boolean was = isRecording;
        isRecording = recording;
        if (recording && !was) {
            com.kooo.evcam.storage.AutoLock.get().recordingStarted(context,
                    useRelayWrite ? finalSaveDir : null);
        } else if (!recording) {
            // 信息条那份在开录时就登记了（还没真正录上），开录失败也要撤；没登记过时无害
            com.kooo.evcam.telemetry.Telemetry.get().release("recording");
            com.kooo.evcam.storage.AutoLock.get().recordingStopped();
        }
    }

    /**
     * 管一次录像空间：设了上限就按上限删最旧的，录不下去了就停。
     *
     * <p>放在相机层而不是界面里：分段切换的回调以前要经过 MainActivity 才有人处理，
     * 界面被关掉或重建的那段时间里就没人管空间了。录制在，检查就在。</p>
     */
    private void checkStorage(String why) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastStorageCheckMs < STORAGE_CHECK_DEBOUNCE_MS) {
            return;
        }
        lastStorageCheckMs = now;
        StorageGuard.enforceAsync(context, guardedVideoDir(),
                decision -> {
                    if (decision.verdict != StoragePlan.Verdict.FULL || !isRecording) {
                        return;
                    }
                    AppLog.w(TAG, "存储检查（" + why + "）：录不下去了，停止录制 capless="
                            + decision.capless + " locked=" + decision.lockedFull);
                    if (storageFullCallback != null) {
                        storageFullCallback.onStorageFull(decision);
                    } else {
                        stopRecording();
                    }
                });
    }

    /**
     * 使用 MediaRecorder 开始录制（标准模式）
     * @param timestamp 时间戳
     * @param enabledCameras 要录制的摄像头位置集合，为 null 时录制所有摄像头
     * @param gen 这次开录（或重建）属于哪一代，排着的任务带着它
     */
    private boolean startMediaRecorderRecording(String timestamp, Set<String> enabledCameras, int gen) {
        AppLog.d(TAG, "Starting MediaRecorder recording with timestamp: " + timestamp);

        // 重置首次写入通知标志（每次录制只通知一次）
        hasNotifiedFirstDataWritten = false;
        firstDataWritten = false;
        firstDataWrittenAtMs = 0;

        // 记录当前录制参数（用于 Watchdog 重建）
        currentRecordingTimestamp = timestamp;
        currentEnabledCameras = enabledCameras;

        // 检查是否使用中转写入模式
        AppConfig appConfig = new AppConfig(context);
        useRelayWrite = appConfig.shouldUseRelayWrite();
        
        // 获取录制目录（可能是临时目录或最终目录）
        File saveDir = StorageHelper.getRecordingDir(context);
        if (saveDir == null) {
            // 没有地方可存（没有 U 盘、开发者选项没开）：RecordingCoordinator 开录前已经拦过，走到这里是查完之后盘没了
            AppLog.e(TAG, "没有地方存录像（没有 U 盘），不开录");
            return false;
        }

        // 如果不使用中转写入（直接写入U盘），检查U盘是否可用
        if (!useRelayWrite) {
            File sdCard = StorageHelper.getExternalSdCardRoot(context);
            if (sdCard == null || !sdCard.exists() || !sdCard.canWrite()) {
                AppLog.e(TAG, "U盘不可用，无法直接写入！U盘状态: exists=" + 
                        (sdCard != null ? sdCard.exists() : "null") + 
                        ", canWrite=" + (sdCard != null ? sdCard.canWrite() : "N/A"));
                // 提示用户并建议启用中转写入
                AppLog.w(TAG, "建议：在设置中启用「中转写入」功能，可以避免U盘问题导致的录制失败");
                // 不直接返回，因为可能只是检测问题，尝试继续录制
            } else {
                AppLog.i(TAG, "直接写入模式，U盘可用: " + sdCard.getAbsolutePath());
            }
        }
        
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }
        
        // 如果使用中转写入，记录最终目录
        if (useRelayWrite) {
            finalSaveDir = StorageHelper.getFinalVideoDir(context);
            if (!finalSaveDir.exists()) {
                finalSaveDir.mkdirs();
            }
            AppLog.d(TAG, "Relay write mode: recording to " + saveDir.getAbsolutePath() + 
                    ", will transfer to " + finalSaveDir.getAbsolutePath());
        } else {
            finalSaveDir = null;
        }

        List<String> allKeys = getActiveCameraKeys();
        if (allKeys.isEmpty()) {
            AppLog.e(TAG, "No active cameras for recording");
            return false;
        }

        // 如果指定了摄像头列表，过滤 keys
        final List<String> keys;
        if (enabledCameras != null && !enabledCameras.isEmpty()) {
            List<String> filteredKeys = new ArrayList<>();
            for (String key : allKeys) {
                if (enabledCameras.contains(key)) {
                    filteredKeys.add(key);
                }
            }
            keys = filteredKeys;
            AppLog.d(TAG, "Filtered recording cameras: " + keys);
        } else {
            keys = allKeys;
        }
        // 被别的程序拿走的那一路这一次不录（2026-10-10）：不准备、不等它的会话。MediaRecorder 模式没有单独接回，
        // 它放开之后要到下一次整次开录才再录 —— 以前照样准备、照样等，等满 3 秒超时，它的录制器再也没启动过
        for (java.util.Iterator<String> it = keys.iterator(); it.hasNext(); ) {
            String key = it.next();
            SingleCamera camera = cameras.get(key);
            if (camera != null && CameraTaken.benched(camera.getCameraId())) {
                com.kooo.evcam.blackbox.BlackBox.noteImportant("开录：相机 " + laneName(key)
                        + " 被别的程序拿走了，这一次不录（MediaRecorder 模式，放开之后下一次开录再录）");
                it.remove();
            }
        }

        if (keys.isEmpty()) {
            AppLog.e(TAG, "No enabled cameras for recording after filtering");
            return false;
        }

        // 帧率、码率、分段都写在每一路自己的配置里，所以在下面的循环里按路取。
        // 远程录制那条路仍然可以临时覆盖分段时长。

        // 第一步：准备所有 MediaRecorder（但不启动）
        // 使用每个摄像头的实际预览分辨率，而不是硬编码的值
        boolean prepareSuccess = true;
        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            VideoRecorder recorder = recorders.get(key);
            if (camera == null || recorder == null) {
                continue;
            }
            
            // 获取摄像头的实际预览分辨率
            Size previewSize = camera.getPreviewSize();
            if (previewSize == null) {
                AppLog.e(TAG, "Camera " + key + " preview size not available, using fallback 1280x720");
                previewSize = new Size(1280, 720);  // 回退到常见分辨率
            }
            
            StreamSpec spec = RecordSpecs.forCameraKey(context, key);
            // MediaRecorder 只接受一个具体数字，没有「不限制」这个说法
            int targetFrameRate = RecordSpecs.nominal(spec.fps, hardwareMaxFps());
            long segmentDurationMs = RecordSpecs.segmentMs(spec.segmentMinutes);
            int bitrate = AppConfig.actualBitrate(spec.bitrate,
                    previewSize.getWidth(),
                    previewSize.getHeight(),
                    targetFrameRate);
            AppLog.d(TAG, "Camera " + key + " 录制配置: " + spec
                    + "，分段 " + (segmentDurationMs / 1000) + " 秒");

            // 设置录制参数
            recorder.setSegmentDuration(segmentDurationMs);
            recorder.setVideoBitrate(bitrate);
            recorder.setVideoFrameRate(targetFrameRate);
            // 注：最大编码分辨率限制使用 VideoRecorder 内部默认值（4096x4096）
            
            AppLog.d(TAG, "Recording params for " + key + ": " + 
                    previewSize.getWidth() + "x" + previewSize.getHeight() + 
                    " @ " + targetFrameRate + "fps, " + AppConfig.formatBitrate(bitrate));
            
            // 所有摄像头使用统一的时间戳：日期_时间_摄像头位置.mp4
            String path = new File(saveDir, timestamp + "_"
                    + CameraSlots.suffixFor(key) + ".mp4").getAbsolutePath();
            // 只准备 MediaRecorder，获取 Surface，使用预览的实际分辨率
            AppLog.d(TAG, "Preparing recording for " + key + " with size: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            if (!recorder.prepareRecording(path, previewSize.getWidth(), previewSize.getHeight())) {
                prepareSuccess = false;
                break;
            }
        }

        if (!prepareSuccess) {
            // 准备好的那几路这里不放：协调器按开录失败走停录，stopRecording 一并收拾
            AppLog.e(TAG, "Failed to prepare recording");
            return false;
        }

        // 第二步：将录制 Surface 添加到摄像头会话并重新创建会话
        synchronized (sessionLock) {
            sessionConfiguredCount = 0;
            expectedSessionCount = keys.size();
            // 初始化每个摄像头的配置状态跟踪
            cameraSessionReady.clear();
        }

        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            VideoRecorder recorder = recorders.get(key);
            if (camera == null || recorder == null) {
                continue;
            }
            camera.setRecordSurface(recorder.getSurface(), false);  // MediaRecorder 模式
            camera.recreateSession();
        }

        // 第三步：设置待处理的录制启动任务（将被 executeRecordingStart 替代）
        final List<String> recordingKeys = new ArrayList<>(keys);
        synchronized (sessionLock) {
            pendingStartGeneration = gen;
            pendingRecordingStart = () -> executeRecordingStart(recordingKeys, false, 0, gen);
        }

        // 设置超时机制：如果 3 秒内没有所有会话配置完成，只启动已就绪的摄像头
        sessionTimeoutRunnable = () -> {
            AppLog.w(TAG, "Session configuration timeout after 3 seconds");
            synchronized (sessionLock) {
                // 标记未响应的摄像头为失败
                for (String key : recordingKeys) {
                    if (!cameraSessionReady.containsKey(key)) {
                        cameraSessionReady.put(key, false);
                        AppLog.w(TAG, "Camera " + key + " session not configured in time");
                    }
                }
                // 执行录制启动（仅已就绪的摄像头）
                executeRecordingStart(recordingKeys, true, 0, gen);
            }
        };
        mainHandler.postDelayed(sessionTimeoutRunnable, 3000);

        return true;
    }

    /**
     * 执行录制启动（仅启动已就绪的摄像头）
     * @param keys 要启动录制的摄像头 key 列表
     * @param fromTimeout 是否是从超时触发的
     * @param gen 排这个任务时是哪一代；停过、又开过就作废
     */
    private void executeRecordingStart(List<String> keys, boolean fromTimeout, int stableAttempt, int gen) {
        if (gen != recordGeneration) {
            AppLog.w(TAG, "过时的开录任务（第 " + gen + " 代，现在第 " + recordGeneration + " 代），作废");
            return;
        }
        if (!fromTimeout) {
            long now = System.currentTimeMillis();
            List<String> unstable = getUnstableCameras(keys, now);
            if (!unstable.isEmpty()) {
                if (stableAttempt < MAX_STABLE_WAIT_ATTEMPTS) {
                    AppLog.w(TAG, "Waiting for stable frames before recording, attempt " + (stableAttempt + 1) +
                            "/" + MAX_STABLE_WAIT_ATTEMPTS + ", unstable=" + unstable);
                    mainHandler.postDelayed(() -> executeRecordingStart(keys, false, stableAttempt + 1, gen),
                            STABLE_WAIT_INTERVAL_MS);
                    return;
                }
                // 等了两秒还不稳就按已就绪的那几路开：相机好不好由相机层自己的看门狗管，录制这条路不重开相机
                AppLog.w(TAG, "Frames still unstable after wait, starting recording with stable subset: " + unstable);
                fromTimeout = true;
            }
        }

        Set<String> activeCameras = new HashSet<>();
        Set<String> failedCameras = new HashSet<>();
        
        AppLog.d(TAG, "Executing recording start for " + keys.size() + " cameras" + 
                (fromTimeout ? " (from timeout)" : ""));
        
        for (String key : keys) {
            // 检查摄像头会话是否已就绪
            Boolean ready = cameraSessionReady.get(key);
            if (ready == null || !ready) {
                // 会话未就绪
                if (fromTimeout) {
                    failedCameras.add(key);
                    AppLog.w(TAG, "Camera " + key + " session not ready, skipping");
                }
                continue;
            }

            // 帧稳定性检查：如果是从超时触发的，检查帧稳定性
            // 但如果会话已经就绪，即使帧暂时不稳定也尝试启动录制（针对后台启动场景）
            if (fromTimeout && !isFrameStable(key, System.currentTimeMillis())) {
                AppLog.w(TAG, "Camera " + key + " frame not stable, but session is ready, will try to start anyway");
                // 不再跳过，而是继续尝试启动录制
            }
            
            VideoRecorder recorder = recorders.get(key);
            if (recorder != null) {
                if (recorder.startRecording()) {
                    activeCameras.add(key);
                } else {
                    failedCameras.add(key);
                    AppLog.e(TAG, "Failed to start recording for " + key);
                }
            } else {
                failedCameras.add(key);
            }
        }
        
        if (!activeCameras.isEmpty()) {
            setRecording(true);
            lastNotifiedSegmentIndex = -1;
            AppLog.d(TAG, activeCameras.size() + " camera(s) started recording successfully: " + activeCameras);
            if (!failedCameras.isEmpty()) {
                AppLog.w(TAG, failedCameras.size() + " camera(s) failed to start: " + failedCameras);
            }
            reportStarted(gen, activeCameras, failedCameras);
        } else {
            // 一路都没起来：录制器不在这里放，协调器走停录那条路由 stopRecording 收拾（录像输出、会话一起）
            AppLog.e(TAG, "All cameras failed to start recording");
            reportStartFailed(gen, "MediaRecorder 一路都没启动: " + failedCameras);
        }

        // 清理状态
        pendingRecordingStart = null;
        sessionConfiguredCount = 0;
        expectedSessionCount = 0;
    }

    private boolean isFrameStable(String key, long nowMs) {
        SingleCamera camera = cameras.get(key);
        if (camera == null) {
            return false;
        }
        long last = camera.getLastFrameTimestampMs();
        return last > 0 && (nowMs - last) <= RECORDING_STABLE_FRAME_MAX_AGE_MS;
    }

    private List<String> getUnstableCameras(List<String> keys, long nowMs) {
        List<String> unstable = new ArrayList<>();
        for (String key : keys) {
            Boolean ready = cameraSessionReady.get(key);
            if (ready == null || !ready) {
                unstable.add(key);
                continue;
            }
            if (!isFrameStable(key, nowMs)) {
                unstable.add(key);
            }
        }
        return unstable;
    }

    /**
     * 使用软编码开始录制（L6/L7 模式）
     * 使用 OpenGL 渲染 + MediaCodec 编码 + MediaMuxer 写入
     * @param timestamp 时间戳
     * @param enabledCameras 要录制的摄像头位置集合，为 null 时录制所有摄像头
     */
    /**
     * 按这一路的配置建一个软编码录制器。
     *
     * <h3>为什么抽出来</h3>
     *
     * <p>启动录制和「强制重开相机后重新准备」是两条路径，以前各写一遍这段。
     * 重新准备那一遍写的是<b>预览尺寸、不拆四宫格、帧率写死 25</b> ——
     * 相机重开一次，剩下整场录制就悄悄变成预览那么大的一条长条，
     * 日志里看不出来，下车才发现。一段代码两个调用方，这类分叉才不会再长出来。
     * 现在的两个调用方是开录和一路单独接回（2026-10-10），录法都照这一次录像的计划（{@link LanePlan}）。</p>
     *
     * @return 配好尺寸、四宫格、帧率、画质和水印的录制器，还没 prepareRecording
     */
    private CodecVideoRecorder newCodecRecorder(String key, SingleCamera camera, LanePlan plan) {
        StreamSpec spec = plan.spec;
        Size previewSize = camera.getPreviewSize();
        if (previewSize == null) {
            AppLog.e(TAG, "Camera " + key + " preview size not available, using fallback 1280x800");
            previewSize = new Size(1280, 800);
        }

        // 两个值：标称值给编码器，上限给渲染节流（可以是「不限制」）
        int targetFrameRate = RecordSpecs.nominal(spec.fps, hardwareMaxFps());
        int frameRateCap = RecordSpecs.cap(spec.fps, hardwareMaxFps());
        long segmentDurationMs = RecordSpecs.segmentMs(spec.segmentMinutes);
        AppLog.d(TAG, "Camera " + key + " 录制配置: " + spec + "，节流上限 "
                + (frameRateCap == 0 ? "不限制" : frameRateCap + " fps")
                + "，分段 " + (segmentDurationMs / 1000) + " 秒");

        // 尺寸怎么算的在 EncodeSize 里（有单元测试）。
        // 录制是一条独立的相机输出流，尺寸可以和预览不同。
        // 以前直接拿预览尺寸，于是配置里改录制分辨率毫无反应，
        // 改预览却把录制一起带动了。
        String role = com.kooo.evcam.profile.ProfileSizes.roleForCameraKey(key);
        Size recordSource = role == null ? null
                : com.kooo.evcam.profile.ProfileSizes.record(context, role, previewSize);
        if (recordSource != null && !recordSource.equals(previewSize)) {
            AppLog.i(TAG, "Camera " + key + " 录制流按配置用 " + recordSource
                    + "（预览是 " + previewSize + "）");
        }
        Size source = recordSource != null ? recordSource : previewSize;
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        EncodeSize encodeSize = EncodeSize.forSource(
                camera.getCameraId(), sourceWidth, sourceHeight, spec.grid);
        // 行驶信息条：开着就在环视录像的画面下面加一条（项目所有者 2026-10-09：只放环视，座舱两路不放）
        if (plan.infoBar) {
            encodeSize = encodeSize.withInfoBar(com.kooo.evcam.telemetry.InfoBar.HEIGHT);
        }

        com.kooo.evcam.zeekr.CompositeStreamGeometry.Plan fourLanePlan = null;
        if (encodeSize.grid) {
            fourLanePlan = com.kooo.evcam.zeekr.CompositeStreamGeometry.analyse(
                    camera.getCameraId(), sourceWidth, sourceHeight);
            AppLog.i(TAG, "Camera " + key + " 四宫格录制: 源 "
                    + sourceWidth + "x" + sourceHeight
                    + " -> 编码 " + encodeSize.width + "x" + encodeSize.height);
        } else {
            // 配置写着四宫格却录出长条时，得能一眼看出是哪一步没成立
            boolean sourceIsComposite =
                    com.kooo.evcam.zeekr.CompositeStreamGeometry.looksLikeComposite(
                            camera.getCameraId(), sourceWidth, sourceHeight);
            AppLog.i(TAG, "Camera " + key + " 不做四宫格重排："
                    + (spec.grid ? "" : "配置为原始长条；")
                    + (sourceIsComposite ? "" : "源尺寸 " + sourceWidth + "x" + sourceHeight
                            + " 不像合成条带；")
                    + "将按原样编码");
            if (encodeSize.width != sourceWidth || encodeSize.height != sourceHeight) {
                AppLog.i(TAG, "Camera " + key + " 超出编码器上限，缩到 "
                        + encodeSize.width + "x" + encodeSize.height
                        + "（上限 " + EncodeSize.MAX_SIDE + "）");
            }
        }

        CodecVideoRecorder codecRecorder = new CodecVideoRecorder(
                camera.getCameraId(), encodeSize.width, encodeSize.height);
        codecRecorder.setBrandLine(plan.brandLine);
        if (plan.infoBar) {
            codecRecorder.setInfoBar(
                    new com.kooo.evcam.telemetry.InfoBarRenderer(context, encodeSize.width));
        }
        if (fourLanePlan != null) {
            codecRecorder.setFourLaneSource(sourceWidth, sourceHeight, fourLanePlan, null);
        }
        // 统一时间戳提供者：多路摄像头分段切换时用同一个时间戳
        codecRecorder.setTimestampProvider(segmentTimestampProvider);
        // 盘写不进时换到哪个盘
        codecRecorder.setFallbackDirs(this::fallbackDirsFor);
        codecRecorder.setSegmentDuration(segmentDurationMs);
        codecRecorder.setFrameRate(targetFrameRate, frameRateCap);
        // 跟随这一路配置里的码率等级。写死一档的话那个选项就是个摆设。
        // 码率本身由录制器按这一档和编码尺寸算（见 TargetBitrate）——
        // 这里不再算第二遍：以前算了，然后那个数在默认路径上被丢掉。
        codecRecorder.setQualityLevel(RecordSpecs.qualityLevel(spec.bitrate));
        codecRecorder.setForceH264(plan.forceH264);
        codecRecorder.setWatermarkEnabled(plan.watermark);
        codecRecorder.setWatermarkSpecEnabled(plan.watermarkSpec);
        return codecRecorder;
    }

    /**
     * 这一次录像里一路的录法，照此刻的设置定下（开录时，{@link #recordingPlan}）。
     *
     * @param infoBarOn 行驶信息条开着（只放环视）
     * @param brandLine 左上角那行字
     */
    private LanePlan planFor(String key, AppConfig appConfig, boolean infoBarOn, String brandLine) {
        StreamSpec spec = RecordSpecs.forCameraKey(context, key);
        return new LanePlan(spec, CameraSlots.KEY_SURROUND.equals(key) && infoBarOn, brandLine,
                // 设置里的「强制 H.264」是总闸，盖过这一路配置里的编码选择
                appConfig.isForceH264Encoding() || RecordSpecs.forceH264(spec.codec),
                appConfig.isTimestampWatermarkEnabled(), appConfig.isWatermarkSpecEnabled());
    }

    /** @param gen 这次开录（或重建）属于哪一代，排着的任务带着它 */
    private boolean startCodecRecording(String timestamp, Set<String> enabledCameras, int gen) {
        AppLog.d(TAG, "Starting CODEC recording with timestamp: " + timestamp);

        // 重置首次写入通知标志（每次录制只通知一次）
        hasNotifiedFirstDataWritten = false;
        firstDataWritten = false;
        firstDataWrittenAtMs = 0;

        // 检查是否使用中转写入模式
        AppConfig appConfig = new AppConfig(context);
        useRelayWrite = appConfig.shouldUseRelayWrite();

        // 获取录制目录（可能是临时目录或最终目录）
        File saveDir = StorageHelper.getRecordingDir(context);
        if (saveDir == null) {
            // 没有地方可存（没有 U 盘、开发者选项没开）：RecordingCoordinator 开录前已经拦过，走到这里是查完之后盘没了。
            // 放在登记车辆信号之前：不开录就不登记
            AppLog.e(TAG, "没有地方存录像（没有 U 盘），不开录");
            return false;
        }
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }

        // 行驶信息条开着：录像期间登记要用车辆信号，停录时注销（没别人在用就全停）
        boolean infoBarOn = com.kooo.evcam.telemetry.InfoBar.isOnForRecording(context);
        if (infoBarOn) {
            com.kooo.evcam.telemetry.Telemetry.get().acquire(context, "recording");
        }

        // 如果使用中转写入，记录最终目录
        if (useRelayWrite) {
            finalSaveDir = StorageHelper.getFinalVideoDir(context);
            if (!finalSaveDir.exists()) {
                finalSaveDir.mkdirs();
            }
            AppLog.d(TAG, "Codec relay write mode: recording to " + saveDir.getAbsolutePath() +
                    ", will transfer to " + finalSaveDir.getAbsolutePath());
        } else {
            finalSaveDir = null;
        }

        List<String> allKeys = getActiveCameraKeys();
        if (allKeys.isEmpty()) {
            AppLog.e(TAG, "No active cameras for codec recording");
            return false;
        }

        // 如果指定了摄像头列表，过滤 keys
        final List<String> keys;
        if (enabledCameras != null && !enabledCameras.isEmpty()) {
            List<String> filteredKeys = new ArrayList<>();
            for (String key : allKeys) {
                if (enabledCameras.contains(key)) {
                    filteredKeys.add(key);
                }
            }
            keys = filteredKeys;
            AppLog.d(TAG, "Filtered codec recording cameras: " + keys);
        } else {
            keys = allKeys;
        }

        if (keys.isEmpty()) {
            AppLog.e(TAG, "No enabled cameras for codec recording after filtering");
            return false;
        }

        // 帧率、码率、分段都写在每一路自己的配置里，所以在下面的循环里按路取。

        // 清理之前的软编码录制器
        for (CodecVideoRecorder recorder : codecRecorders.values()) {
            recorder.release();
        }
        codecRecorders.clear();
        // 新的一次录像：每一路的状态、计划从头来
        clearLanes();

        // 这一次录像的计划：每一路的录法照此刻的设置定下，接回只照它
        String brandLine = buildBrandLine();
        // 为每个摄像头创建软编码录制器并准备
        boolean prepareSuccess = true;
        List<String> prepared = new ArrayList<>();
        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            if (camera == null) {
                continue;
            }

            LanePlan plan = planFor(key, appConfig, infoBarOn, brandLine);
            recordingPlan.put(key, plan);
            if (CameraTaken.benched(camera.getCameraId())) {
                // 被别的程序拿走的那一路这一次先不录：不建录制器、不挂输出、不等它的会话。以前照样建、照样等 ——
                // 等满 3 秒会话超时、再等 2 秒画面稳定，环视那一段也跟着晚开；它的录制器准备好了却再也没启动过
                lanes.out(key, RecordingLanes.Reason.START_SKIPPED, android.os.SystemClock.elapsedRealtime());
                com.kooo.evcam.blackbox.BlackBox.noteImportant("开录：相机 " + laneName(key)
                        + " 被别的程序拿走了，这一路先不录；它放开、通道安静了单独接回");
                continue;
            }
            CodecVideoRecorder codecRecorder = newCodecRecorder(key, camera, plan);
            codecRecorder.setCallback(codecCallback(gen, key, lanes.token(key)));

            // 准备录制
            String path = new File(saveDir, timestamp + "_"
                    + CameraSlots.suffixFor(key) + ".mp4").getAbsolutePath();
            AppLog.d(TAG, "Preparing codec recording for " + key);

            android.graphics.SurfaceTexture surfaceTexture = codecRecorder.prepareRecording(path);
            if (surfaceTexture == null) {
                AppLog.e(TAG, "Failed to prepare codec recording for " + key);
                prepareSuccess = false;
                break;
            }

            // 将 SurfaceTexture 设置给 Camera（通过 Surface）
            android.view.Surface recordSurface = new android.view.Surface(surfaceTexture);
            camera.setRecordSurface(recordSurface, true);  // Codec 模式

            codecRecorders.put(key, codecRecorder);
            laneSurfaces.put(key, recordSurface);
            prepared.add(key);
        }

        if (!prepareSuccess) {
            // 准备好的那几路（录制器、已经挂到相机上的录像输出）这里不放：
            // 协调器按开录失败走停录，stopRecording 一并收拾。以前这里只放了录制器，
            // 前面几路相机上挂着的录像输出没人摘，之后的会话一直带着一个死掉的输出去配
            AppLog.e(TAG, "Failed to prepare codec recording");
            return false;
        }
        if (prepared.isEmpty()) {
            // 要录的每一路都被别的程序拿着（或者没有相机）：没有能开的，按开录失败走（协调器等环视恢复再接）
            AppLog.e(TAG, "没有能开录的一路（被别的程序拿着、或者没有相机）: " + keys);
            return false;
        }
        notifyLanes();

        // 重新创建摄像头会话（只有准备了录制器的那几路）
        synchronized (sessionLock) {
            sessionConfiguredCount = 0;
            expectedSessionCount = prepared.size();
            cameraSessionReady.clear();
        }

        for (String key : prepared) {
            SingleCamera camera = cameras.get(key);
            if (camera != null) {
                camera.recreateSession();
            }
        }

        final List<String> recordingKeys = new ArrayList<>(prepared);
        synchronized (sessionLock) {
            pendingStartGeneration = gen;
            pendingRecordingStart = () -> executeCodecRecordingStart(recordingKeys, 0, gen);
        }

        // 设置超时机制
        sessionTimeoutRunnable = () -> {
            AppLog.w(TAG, "Session configuration timeout, starting codec recording with available cameras");
            synchronized (sessionLock) {
                final Runnable recordingTask = pendingRecordingStart;
                if (recordingTask != null) {
                    pendingRecordingStart = null;
                    recordingTask.run();
                }
                sessionConfiguredCount = 0;
                expectedSessionCount = 0;
            }
        };
        mainHandler.postDelayed(sessionTimeoutRunnable, 3000);

        return true;
    }

    /**
     * 软编码录制器的回调：开录的每一路、单独接回的那一路用同一份（2026-10-10 从开录里抽出来）。
     *
     * @param gen   这一次录像的代数：停过录、又开过录，上一次的回调不算数
     * @param key   这一路
     * @param token 建这个录制器时这一路的计数（{@link RecordingLanes#token}）：这一路之后离开过、接回过，
     *              这个录制器报的写不进、第一笔数据就不算这一路的了
     */
    private RecordCallback codecCallback(int gen, String key, int token) {
        return new RecordCallback() {
            @Override
            public void onRecordStart(String cameraId) {
                AppLog.d(TAG, "Codec recording started for camera " + cameraId);
            }

            @Override
            public void onRecordStop(String cameraId) {
                AppLog.d(TAG, "Codec recording stopped for camera " + cameraId);
            }

            @Override
            public void onRecordError(String cameraId, String error) {
                AppLog.e(TAG, "Codec recording error for camera " + cameraId + ": " + error);
                // 以前到这里就完了：只写一行内部日志，界面和黑匣子都不知道
                com.kooo.evcam.blackbox.BlackBox.noteImportant("录制器报错（相机 " + cameraId + "）：" + error);
            }

            @Override
            public void onPrepareSegmentSwitch(String cameraId, int currentSegmentIndex) {
                AppLog.d(TAG, "Codec prepare segment switch for camera " + cameraId + " (current segment: " + currentSegmentIndex + ")");
                // 软编码录制器使用独立的 SurfaceTexture，不需要暂停 Camera CaptureSession
                // 但为了一致性，我们记录日志
            }

            @Override
            public void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath) {
                AppLog.d(TAG, "Codec segment switch for camera " + cameraId + " to segment " + newSegmentIndex);
                if (staleCallback(gen, "segment switch", cameraId)) {
                    // 停过录（又开过）：分段计数、中转目标、空间检查都是这一次的了。上一次的文件由那次停录转存
                    return;
                }

                // 如果使用中转写入，将上一个分段的文件传输到最终目录
                if (useRelayWrite && finalSaveDir != null && newSegmentIndex > 0 && completedFilePath != null) {
                    // 传输已完成的文件（由回调提供确切路径，避免传输正在录制的新文件）
                    scheduleRelayTransfer(completedFilePath);
                }
                
                // 通知分段切换回调（只通知一次，第一个触发的摄像头会通知）
                if (segmentSwitchCallback != null && newSegmentIndex > lastNotifiedSegmentIndex) {
                    lastNotifiedSegmentIndex = newSegmentIndex;
                    segmentSwitchCallback.onSegmentSwitch(newSegmentIndex);
                }
                checkStorage("分段切换");
            }

            @Override
            public void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles) {
                if (deletedFiles != null && !deletedFiles.isEmpty()) {
                    AppLog.w(TAG, "Corrupted files deleted for codec camera " + cameraId + ": " + deletedFiles.size() + " file(s)");
                    for (String file : deletedFiles) {
                        AppLog.d(TAG, "  Deleted: " + file);
                    }
                    // 通知 MainActivity 显示弹窗
                    if (corruptedFilesCallback != null) {
                        mainHandler.post(() -> corruptedFilesCallback.onCorruptedFilesDeleted(deletedFiles));
                    }
                }
            }

            @Override
            public void onRecordingRebuildRequested(String cameraId, String reason) {
                // CodecVideoRecorder 通常不会触发此回调，但为了接口完整性实现
                AppLog.e(TAG, "Codec recording rebuild requested for camera " + cameraId + ", reason: " + reason);
                // Codec 模式不需要回退，记录日志即可
            }

            @Override
            public void onRecordingRelocated(String cameraId, File dir, String why, long rescuedMs) {
                AppLog.w(TAG, "Camera " + cameraId + " relocated recording to " + dir + " (" + why
                        + "), rescued " + rescuedMs + "ms from memory");
                if (staleCallback(gen, "relocation", cameraId)) {
                    return;
                }
                noteRelocated(dir);
            }

            @Override
            public void onWriteStalled(String cameraId, long stalledMs, boolean everWrote) {
                mainHandler.post(() -> {
                    if (!staleCallback(gen, "write stall", cameraId)) {
                        onLaneWriteStalled(key, token, stalledMs, everWrote);
                    }
                });
            }

            @Override
            public void onWriteBacklog(String cameraId) {
                mainHandler.post(() -> {
                    if (!staleCallback(gen, "write backlog", cameraId)) {
                        MultiCameraManager.this.onWriteBacklog(cameraId);
                    }
                });
            }

            @Override
            public void onFirstDataWritten(String cameraId) {
                AppLog.d(TAG, "Codec first data written for camera " + cameraId);
                if (staleCallback(gen, "first data", cameraId)) {
                    return;
                }
                // 只在第一个摄像头首次写入时通知外部（每次录制只通知一次）
                if (!firstDataWritten) {
                    firstDataWritten = true;
                    firstDataWrittenAtMs = System.currentTimeMillis();
                }
                if (!hasNotifiedFirstDataWritten && firstDataWrittenCallback != null) {
                    hasNotifiedFirstDataWritten = true;
                    AppLog.d(TAG, "Notifying external: first data written, recording truly started");
                    mainHandler.post(() -> firstDataWrittenCallback.onFirstDataWritten());
                }
                // 单独接回的那一路：写进第一笔才算接回
                mainHandler.post(() -> {
                    if (!staleCallback(gen, "first data", cameraId)) {
                        onLaneFirstData(key, token);
                    }
                });
            }
        };
    }

    /** @param gen 排这个任务时是哪一代；停过、又开过就作废 */
    private void executeCodecRecordingStart(List<String> keys, int stableAttempt, int gen) {
        if (gen != recordGeneration) {
            AppLog.w(TAG, "过时的开录任务（第 " + gen + " 代，现在第 " + recordGeneration + " 代），作废");
            return;
        }
        AppLog.d(TAG, "Attempting to start codec recording...");
        if (isRecording) {
            AppLog.w(TAG, "Codec recording already active, skipping duplicate start");
            synchronized (sessionLock) {
                pendingRecordingStart = null;
                sessionConfiguredCount = 0;
                expectedSessionCount = 0;
                cameraSessionReady.clear();
            }
            if (sessionTimeoutRunnable != null) {
                mainHandler.removeCallbacks(sessionTimeoutRunnable);
                sessionTimeoutRunnable = null;
            }
            return;
        }

        long now = System.currentTimeMillis();
        List<String> unstable = getUnstableCameras(keys, now);
        if (!unstable.isEmpty()) {
            if (stableAttempt < MAX_STABLE_WAIT_ATTEMPTS) {
                AppLog.w(TAG, "Waiting for stable frames before codec recording, attempt " + (stableAttempt + 1) +
                        "/" + MAX_STABLE_WAIT_ATTEMPTS + ", unstable=" + unstable);
                mainHandler.postDelayed(() -> executeCodecRecordingStart(keys, stableAttempt + 1, gen),
                        STABLE_WAIT_INTERVAL_MS);
                return;
            }
            // 等了两秒还不稳就照样开：相机好不好由相机层自己的看门狗管，录制这条路不重开相机
            AppLog.w(TAG, "Codec frames still unstable after wait, will try to start anyway: " + unstable);
            unstable.clear();
        }

        Set<String> activeCameras = new HashSet<>();
        Set<String> failedCameras = new HashSet<>();

        AppLog.d(TAG, "executeCodecRecordingStart: keys=" + keys + ", codecRecorders=" + codecRecorders.keySet() + ", cameraSessionReady=" + cameraSessionReady);

        for (String key : keys) {
            Boolean ready = cameraSessionReady.get(key);
            AppLog.d(TAG, "Checking camera " + key + ": ready=" + ready + ", codecRecorder=" + codecRecorders.get(key));
            if (ready == null || !ready) {
                AppLog.w(TAG, "Camera " + key + " session not ready, skipping");
                failedCameras.add(key);
                continue;
            }
            CodecVideoRecorder codecRecorder = codecRecorders.get(key);
            if (codecRecorder == null) {
                AppLog.e(TAG, "Camera " + key + " codecRecorder is null");
                failedCameras.add(key);
                continue;
            }
            if (codecRecorder.isRecording()) {
                activeCameras.add(key);
                continue;
            }
            AppLog.d(TAG, "Starting codec recording for camera " + key);
            boolean started = codecRecorder.startRecording();
            AppLog.d(TAG, "Camera " + key + " startRecording returned: " + started + ", isRecording=" + codecRecorder.isRecording());
            if (started || codecRecorder.isRecording()) {
                activeCameras.add(key);
                AppLog.d(TAG, "Camera " + key + " codec recording started successfully");
            } else {
                failedCameras.add(key);
                AppLog.e(TAG, "Failed to start codec recording for " + key);
            }
        }

        if (!activeCameras.isEmpty()) {
            lastNotifiedSegmentIndex = -1;
            setRecording(true);
            AppLog.d(TAG, activeCameras.size() + " camera(s) started codec recording successfully");
            // 起来了的几路在录；没录起来的几路单独退出（放掉录制器、摘掉输出），收拾完了和离开的一样等着单独接回 ——
            // 以前它们的录制器准备好了留在表里、输出挂在相机上，这一次录像剩下的时间都没人再启动它
            long nowMs = android.os.SystemClock.elapsedRealtime();
            for (String key : keys) {
                if (activeCameras.contains(key)) {
                    lanes.live(key, nowMs);
                }
            }
            for (String key : keys) {
                if (failedCameras.contains(key)) {
                    Boolean ready = cameraSessionReady.get(key);
                    startFailed(key, ready == null || !ready ? "会话没在 3 秒内配好" : "录制器没启动");
                }
            }
            notifyLanes();
            reportStarted(gen, activeCameras, failedCameras);
        } else {
            // 一路都没起来：录制器不在这里放，协调器走停录那条路由 stopRecording 收拾（录像输出、会话一起）。
            // 以前这里放掉录制器就完了，什么都不报，协调器一直以为在录（2026-10-05）
            AppLog.e(TAG, "Failed to start codec recording on all cameras");
            reportStartFailed(gen, "编码录制一路都没启动: " + failedCameras);
        }

        synchronized (sessionLock) {
            pendingRecordingStart = null;
            sessionConfiguredCount = 0;
            expectedSessionCount = 0;
            cameraSessionReady.clear();
        }
        if (sessionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(sessionTimeoutRunnable);
            sessionTimeoutRunnable = null;
        }
    }

    /**
     * 停止录制所有摄像头
     */
    public void stopRecording() {
        stopRecording(false);
    }

    /**
     * 停录 —— 唯一的收拾路径，开录走到哪一步都一样（2026-10-05）。
     *
     * <ol>
     *   <li>代数 +1：排着的开录任务（等会话后的 300ms、等稳定画面、3 秒超时、重建前的 500ms）跑的时候对不上，
     *       作废、什么都不报；</li>
     *   <li>把这一次的编码器、MediaRecorder、挂着录像输出的那几路相机快照下来，编码器表这就空出来；</li>
     *   <li>软编码录制器当场每一路都叫停（不等，{@link CodecVideoRecorder#beginStop}）；后台线程上一起等 ——
     *       先等编码线程都排空（{@link CodecVideoRecorder#awaitDrain}），再等写入线程都收好文件
     *       （{@link CodecVideoRecorder#finishStop}），几路共用一个期限（{@link CodecVideoRecorder#STOP_BUDGET_MS}），
     *       再放掉。MediaRecorder 的在后台线程上停、放（停一路最长要几秒，不能卡主线程）；</li>
     *   <li>回主线程摘掉录像输出、重建会话 —— 以前开录没起来时停录只放编码器，相机上挂着的录像输出没人摘，
     *       之后每次建会话都带着一个死掉的输出去配；</li>
     *   <li>报「收拾完了」（{@link PipelineCallback#onPipelineStopped}），协调器这时才开下一次 ——
     *       以前开 → 停 → 开两秒内，上一次的收拾会把这一次刚建的编码器一起放掉。</li>
     * </ol>
     *
     * @param skipRelayTransfer 是否跳过自动传输（用于远程录制，上传完成后再传输）
     */
    public void stopRecording(boolean skipRelayTransfer) {
        stopRecording(skipRelayTransfer, false);
    }

    /**
     * @param forRelease 管理器要释放了：MediaRecorder 的录制器不再复用，连同分段线程一起放掉
     *                   （两种模式都放：它们每一路一个，建管理器时就建好了）
     */
    private void stopRecording(boolean skipRelayTransfer, boolean forRelease) {
        final int gen;
        synchronized (sessionLock) {
            gen = ++recordGeneration;
            if (pendingRecordingStart != null) {
                AppLog.d(TAG, "Cancelling pending recording start");
                pendingRecordingStart = null;
            }
            sessionConfiguredCount = 0;
            expectedSessionCount = 0;
            cameraSessionReady.clear();
            // 清理 Watchdog 回退状态（和代数同一步：重建在分段线程上读它们，见 handleRecordingRebuildRequest）
            currentRecordingTimestamp = null;
            currentEnabledCameras = null;
            rebuildAttemptCount = 0;
            isRebuildingRecording = false;  // 重置重建标志
        }
        if (sessionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(sessionTimeoutRunnable);
            sessionTimeoutRunnable = null;
        }
        AppLog.d(TAG, "stopRecording gen=" + gen + ", isRecording=" + isRecording + ", useCodecRecording="
                + useCodecRecording + ", skipRelayTransfer=" + skipRelayTransfer);

        // 车辆信号的登记（信息条、闪远光自动锁定）也只在录像期间，跟着录像状态撤（setRecording）
        setRecording(false);
        mainHandler.removeCallbacks(storageTick);
        StorageHelper.noteRecordingFallback(null, null);
        // 一路单独离开、接回的都到此为止：代数一变，排着的建录制器、开相机、录制器的回调回来都作废；
        // 接回中的录制器已经在录制器表里，下面和别的一起收拾。正在离开的那几路自己的收拾照常做完
        clearLanes();
        notifyLanes();

        // 这一次开录走到哪一步都一样收拾：在录的、接回中的、准备好还没启动的、挂到相机上的录像输出
        final List<CodecVideoRecorder> codecs = new ArrayList<>(codecRecorders.values());
        codecRecorders.clear();
        // 软编码录制器停录分两步。第一步就在这里，每一路都叫停（不等）：从这一刻起都不再录、不再分段、不再恢复，
        // 各自排空编码器、把收文件排在最后。以前在后台一路一路停，后面几路在等前面的时候还在录，可能跨过分段
        // 又开出新文件；三路各等十几秒，加起来超过协调器的收拾期限，协调器不等了、又开下一次，旧的还在写同一个盘
        for (CodecVideoRecorder codecRecorder : codecs) {
            try {
                codecRecorder.beginStop();
            } catch (Exception e) {
                AppLog.e(TAG, "Error asking codec recorder to stop", e);
            }
        }
        // 第二步（下面的后台线程上）一起等它们排空、收好文件，共用这一个期限。按开机时长（不含深睡）算：
        // 等待用的计时、协调器的收拾期限都不算深睡
        final long codecStopDeadline = android.os.SystemClock.uptimeMillis() + CodecVideoRecorder.STOP_BUDGET_MS;
        // MediaRecorder 的录制器每一路一个、一直留着复用：只在 MediaRecorder 模式下收（释放时都放）
        final List<VideoRecorder> mediaRecorders = useCodecRecording && !forRelease
                ? new ArrayList<>() : new ArrayList<>(recorders.values());
        // 挂着录像输出的那几路，连同挂着的是哪一个输出
        final Map<SingleCamera, android.view.Surface> recordOutputs = new LinkedHashMap<>();
        for (SingleCamera camera : cameras.values()) {
            android.view.Surface output = camera.getRecordSurface();
            if (output != null) {
                recordOutputs.put(camera, output);
            }
        }
        final File relayTarget = useRelayWrite && !skipRelayTransfer ? finalSaveDir : null;
        useRelayWrite = false;
        finalSaveDir = null;

        // 在后台线程执行停止操作，避免阻塞主线程。一次只收拾一份（teardown 是单线程的）：
        // 退出时「人停的」那一次和释放那一次、连着两次停，不会同时去动同一批录制器
        teardownsRunning.incrementAndGet();
        teardown.execute(() -> {
            // 停录时写入线程还没收好的文件（盘卡死了）：中转写入不转存它，半个文件转过去就坏了
            final Set<String> heldFiles = new HashSet<>();
            try {
                // 软编码录制第二步：一起等，共用一个期限。先等编码线程都排空 —— 到点没排空的几路，收文件都在那一刻排上；
                // 再等写入线程都写完收好（到点没收好的不再等，没落盘的交给抢救）；然后释放
                for (CodecVideoRecorder codecRecorder : codecs) {
                    try {
                        codecRecorder.awaitDrain(codecStopDeadline);
                    } catch (Exception e) {
                        AppLog.e(TAG, "Error draining codec recorder", e);
                    }
                }
                for (CodecVideoRecorder codecRecorder : codecs) {
                    try {
                        codecRecorder.finishStop(codecStopDeadline);
                    } catch (Exception e) {
                        AppLog.e(TAG, "Error stopping codec recorder", e);
                    }
                }
                for (CodecVideoRecorder codecRecorder : codecs) {
                    try {
                        codecRecorder.release();
                    } catch (Exception e) {
                        AppLog.e(TAG, "Error releasing codec recorder", e);
                    }
                    String held = codecRecorder.heldFile();
                    if (held != null) {
                        heldFiles.add(held);
                    }
                }

                // MediaRecorder：在录的停下收文件；准备好还没启动的放掉；都回到能再开一次的样子
                // （reset 保留分段线程。以前开录失败走的是 release，分段线程跟着没了，下一次录像不分段）
                for (VideoRecorder recorder : mediaRecorders) {
                    try {
                        if (forRelease) {
                            recorder.release();
                        } else {
                            if (recorder.isRecording()) {
                                recorder.stopRecording();
                            }
                            recorder.reset();
                        }
                    } catch (Exception e) {
                        AppLog.e(TAG, "Error stopping recorder", e);
                    }
                }

                // 如果使用中转写入，将临时目录中的所有文件传输到最终目录（写入线程还攥着的那个除外：
                // 它要是回过神来收好了，留在缓存里，下一次中转写入停录时一起转存）
                if (relayTarget != null) {
                    AppLog.d(TAG, "Scheduling relay transfer for remaining files...");
                    File tempDir = new File(context.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
                    final File[] filesToTransfer = tempDir.exists()
                            ? tempDir.listFiles((dir, name) -> name.endsWith(".mp4")
                                    && !heldFiles.contains(new File(dir, name).getAbsolutePath()))
                            : null;
                    if (!heldFiles.isEmpty()) {
                        AppLog.w(TAG, "Relay transfer skips files still held by a stuck writer: " + heldFiles);
                    }
                    mainHandler.postDelayed(() -> transferSpecificTempFiles(relayTarget, filesToTransfer), 500);
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Error in stopRecording", e);
            } finally {
                teardownsRunning.decrementAndGet();
            }

            // 录制器都停了：回主线程摘录像输出、重建会话（短延迟确保录制器已完全停止），然后报收拾完了
            mainHandler.postDelayed(() -> {
                for (Map.Entry<SingleCamera, android.view.Surface> entry : recordOutputs.entrySet()) {
                    SingleCamera camera = entry.getKey();
                    try {
                        // 只摘停录时挂着的那一个：之后又开了录、挂上了新的输出，不动它。
                        // 释放时（这一次是释放、或者收拾的这段时间里管线被释放了）相机正在关，只摘不重建；
                        // 马上就要关的（closesSoon）也只摘不重建 —— 不在关之前再动它一次
                        if (camera.clearRecordSurfaceIf(entry.getValue()) && !forRelease && !isReleased()
                                && !closesSoon(camera)) {
                            camera.recreateSession();
                        }
                    } catch (Exception e) {
                        AppLog.e(TAG, "Error clearing record surface for " + camera.getCameraId(), e);
                    }
                }
                if (gen != recordGeneration) {
                    // 之后又停过一次（那一次会报），或者已经又开了：这一次的「收拾完了」不算数
                    AppLog.d(TAG, "stopRecording gen=" + gen + " superseded by gen=" + recordGeneration);
                    return;
                }
                AppLog.d(TAG, "stopRecording completed gen=" + gen);
                if (pipelineCallback != null) {
                    pipelineCallback.onPipelineStopped();
                }
            }, 100);
        });

        AppLog.d(TAG, "All cameras stopped recording");
    }

    /**
     * 处理录制重建请求（Watchdog 触发）
     * 
     * 重建策略（只有 MediaRecorder 的录制器发重建请求）：
     * 1. 第一次触发：尝试重建 MediaRecorder（不切换模式）
     * 2. 第二次起：录制模式读出来是"自动"就切换到 Codec 模式（提示 msg_codec_fallback），否则再试 MediaRecorder
     * 3. 已在 Codec 模式：不再处理
     *
     * 第 2 条的回退很少走到："自动"开录本来就走 Codec（AppConfig.shouldUseCodecRecording），
     * 而管线只在相机管理器创建时定一次。只有按 MediaRecorder 建好之后、录制模式才读成"自动"
     * （开发者选项里改了，管理器没有重建）时，才会在这里改用 Codec。
     *
     * 注意：多个摄像头可能同时触发此方法，需要防重入保护
     * 
     * @param cameraId 触发重建的相机ID
     * @param reason 重建原因
     */
    private void handleRecordingRebuildRequest(String cameraId, String reason) {
        // 【关键】防重入保护：多个摄像头可能同时触发 Watchdog
        // 只处理第一个触发的请求，忽略后续的
        synchronized (this) {
            if (isRebuildingRecording) {
                AppLog.w(TAG, "Recording rebuild already in progress, ignoring request from camera " + cameraId);
                return;
            }
            isRebuildingRecording = true;
        }
        
        rebuildAttemptCount++;
        AppLog.w(TAG, "Handling recording rebuild request from camera " + cameraId + 
                ", reason: " + reason + ", attempt: " + rebuildAttemptCount);
        
        // 如果已经在 Codec 模式，则不再处理
        if (useCodecRecording) {
            AppLog.w(TAG, "Already using Codec recording, no further fallback available");
            isRebuildingRecording = false;
            return;
        }
        
        // 保存当前录制参数，连同这一次录像的代数一起读：和停录在同一把锁里，
        // 读到的要么是停之前的一整套，要么是停之后的（时间戳已清，下面就不重建）。
        // 这里在录制器的分段线程上跑，停录在主线程上 —— 分开读的话，停录插在中间，重开会带着新的一代照样开
        final String savedTimestamp;
        final Set<String> savedEnabledCameras;
        final int gen;
        synchronized (sessionLock) {
            savedTimestamp = currentRecordingTimestamp;
            savedEnabledCameras = currentEnabledCameras;
            gen = recordGeneration;
        }

        if (savedTimestamp == null) {
            AppLog.w(TAG, "No recording timestamp saved, cannot rebuild");
            isRebuildingRecording = false;
            return;
        }
        if (gen != recordGeneration) {
            // 刚读完就停了录：停录那条路在收拾，这里不再去动录制器
            AppLog.w(TAG, "重建前停过录，不重建");
            isRebuildingRecording = false;
            return;
        }

        // 停止当前录制（不清理状态）。重建属于这一次录像：同一代。等的这 500ms 里停过录，代数就变了，重开作废
        stopRecordingForRebuild();

        // 注意：不自动清除调试标志，让用户通过 UI 手动控制
        // 调试模式作为持久开关，直到用户手动关闭

        // 检查是否需要回退到 Codec：达到阈值、录制模式是「自动」才回退，否则再试 MediaRecorder
        final boolean toCodec;
        if (rebuildAttemptCount >= CODEC_FALLBACK_THRESHOLD) {
            String recordingMode = new AppConfig(context).getRecordingMode();
            toCodec = AppConfig.RECORDING_MODE_AUTO.equals(recordingMode);
            AppLog.w(TAG, toCodec
                    ? "Rebuild attempt " + rebuildAttemptCount + " failed, switching to Codec mode..."
                    : "Recording mode is '" + recordingMode + "' (not auto), retrying MediaRecorder...");
        } else {
            toCodec = false;
            AppLog.w(TAG, "Rebuild attempt " + rebuildAttemptCount + ", retrying MediaRecorder first...");
        }
        mainHandler.postDelayed(() -> restartAfterRebuild(gen, savedEnabledCameras, toCodec), 500);
    }

    /**
     * 重建的后一半：停了等 500ms 再开。
     *
     * <p>这中间停过录（人停了、被打断了）就不开 —— 以前照开，人按的停被它撤销。
     * 开不起来报开录失败，协调器走停录那条路 —— 以前不看返回值，一路都没准备好时协调器一直以为在录。</p>
     */
    private void restartAfterRebuild(int gen, Set<String> enabledCameras, boolean toCodec) {
        try {
            if (gen != recordGeneration) {
                AppLog.w(TAG, "重建等待期间停过录（第 " + gen + " 代，现在第 " + recordGeneration + " 代），不再重开");
                return;
            }
            // 生成新的时间戳（避免文件名冲突）
            String newTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            boolean started;
            if (toCodec) {
                AppLog.d(TAG, "Restarting recording with Codec mode, new timestamp: " + newTimestamp);
                useCodecRecording = true;  // 切换到 Codec 模式
                started = startCodecRecording(newTimestamp, enabledCameras, gen);
            } else {
                AppLog.d(TAG, "Restarting recording with MediaRecorder, new timestamp: " + newTimestamp);
                started = startMediaRecorderRecording(newTimestamp, enabledCameras, gen);
            }
            if (!started) {
                reportStartFailed(gen, "重建录制没起来（" + (toCodec ? "改用编码录制" : "MediaRecorder") + "）");
                return;
            }

            // 通知外部时间戳已更新（用于远程录制查找文件）
            if (timestampUpdateCallback != null) {
                timestampUpdateCallback.onTimestampUpdated(newTimestamp);
            }
            // 通知外部发生了 Codec 回退
            if (toCodec && codecFallbackCallback != null) {
                codecFallbackCallback.onCodecFallback();
            }
        } finally {
            isRebuildingRecording = false;  // 重建完成
        }
    }

    /**
     * 为重建停止录制（不清理 Watchdog 状态）
     * 使用 reset() 而不是 release()，以便保留 Handler/Thread 供重建时使用
     */
    private void stopRecordingForRebuild() {
        AppLog.d(TAG, "Stopping recording for rebuild...");
        
        List<String> keys = getActiveCameraKeys();
        
        // 重置 MediaRecorder 录制器（保留 Handler/Thread）
        for (String key : keys) {
            VideoRecorder recorder = recorders.get(key);
            if (recorder != null) {
                recorder.reset();  // 重置而不是释放，保留 Handler/Thread
            }
        }
        
        // 清理摄像头会话
        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            if (camera != null) {
                camera.clearRecordSurface();
                camera.recreateSession();
            }
        }
        
        setRecording(false);
    }
    
    /**
     * 调度将指定的已完成文件传输到最终目录
     * @param completedFilePath 已完成录制的文件完整路径
     */
    private void scheduleRelayTransfer(String completedFilePath) {
        if (finalSaveDir == null || completedFilePath == null) {
            return;
        }
        
        File tempFile = new File(completedFilePath);
        if (!tempFile.exists()) {
            AppLog.w(TAG, "Completed file does not exist: " + completedFilePath);
            return;
        }
        
        // 检查文件大小，避免传输空文件或损坏文件
        if (tempFile.length() < 1024) {
            AppLog.w(TAG, "Completed file too small, skipping transfer: " + completedFilePath + " (" + tempFile.length() + " bytes)");
            return;
        }
        
        File targetFile = new File(finalSaveDir, tempFile.getName());
        
        AppLog.d(TAG, "Scheduling relay transfer: " + tempFile.getName() + 
                " -> " + targetFile.getAbsolutePath());
        
        FileTransferManager transferManager = FileTransferManager.getInstance(context);
        transferManager.addTransferTask(tempFile, targetFile, 
                new FileTransferManager.TransferCallback() {
            @Override
            public void onTransferComplete(File sourceFile, File targetFile) {
                AppLog.d(TAG, "Relay transfer complete: " + targetFile.getName());
            }
            
            @Override
            public void onTransferFailed(File sourceFile, File targetFile, String error) {
                AppLog.e(TAG, "Relay transfer failed: " + sourceFile.getName() + " - " + error);
            }
        });
    }
    
    /**
     * 将指定的临时视频文件传输到最终目录
     * 【重要】此方法只传输预先指定的文件列表，避免传输在调用后新创建的文件
     * @param targetDir 目标目录
     * @param files 要传输的文件列表（在调用前收集）
     */
    private void transferSpecificTempFiles(File targetDir, File[] files) {
        if (targetDir == null) {
            AppLog.w(TAG, "Target directory is null, skipping transfer");
            return;
        }
        
        if (files == null || files.length == 0) {
            AppLog.d(TAG, "No temp files to transfer");
            return;
        }
        
        AppLog.d(TAG, "Transferring " + files.length + " temp file(s) to " + targetDir.getAbsolutePath());
        
        FileTransferManager transferManager = FileTransferManager.getInstance(context);
        
        // 确保 FileTransferManager 已启动（如果从后台服务录制，可能未启动）
        transferManager.start();
        
        for (File tempFile : files) {
            // 检查文件是否仍然存在（可能已经被删除或移动）
            if (!tempFile.exists()) {
                AppLog.d(TAG, "Skipping non-existent file: " + tempFile.getName());
                continue;
            }
            
            // 跳过空文件（可能是正在被其他录制使用的新文件）
            if (tempFile.length() == 0) {
                AppLog.d(TAG, "Skipping empty file (may be in use): " + tempFile.getName());
                continue;
            }
            
            File targetFile = new File(targetDir, tempFile.getName());
            
            transferManager.addTransferTask(tempFile, targetFile, 
                    new FileTransferManager.TransferCallback() {
                @Override
                public void onTransferComplete(File sourceFile, File targetFile) {
                    AppLog.d(TAG, "Transfer complete: " + targetFile.getName());
                }
                
                @Override
                public void onTransferFailed(File sourceFile, File targetFile, String error) {
                    AppLog.e(TAG, "Transfer failed: " + sourceFile.getName() + " - " + error);
                }
            });
        }
    }

    /**
     * 释放所有资源
     * 添加完善的清理逻辑和异常保护
     */
    public void release() {
        CameraNeeds.current().setListener(null);
        com.kooo.evcam.screen.ScreenState.removeListener(screenListener);
        mainHandler.removeCallbacks(closeWhenUnneeded);
        if (photoJob != null) {
            // 拍到一半管理器没了（退出）：这一张作罢，登记也撤掉
            photoJob = null;
            CameraNeeds.current().release(CameraNeeds.Holder.PHOTO);
        }
        AppLog.d(TAG, "Releasing MultiCameraManager resources");
        livenessRunning = false;
        
        try {
            // 1. 首先清理所有待执行的 Handler 任务（防止内存泄漏）
            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
            }
            
            // 2. 清理超时 Runnable 引用
            if (sessionTimeoutRunnable != null) {
                sessionTimeoutRunnable = null;
            }
            pendingRecordingStart = null;
            
            // 3. 重置会话配置计数器
            synchronized (sessionLock) {
                sessionConfiguredCount = 0;
                expectedSessionCount = 0;
            }
            
            // 4. 停止录制：录制器（两种）由停录那条路在后台线程上一并放掉。
            //    以前这里接着又在本线程上放一遍，和停录线程同时动同一批录制器。
            //    收拾完了照样报给协调器：它要是还以为在录，就按「管线没了」处理
            try {
                stopRecording(false, true);
            } catch (Exception e) {
                AppLog.e(TAG, "Error stopping recording during release", e);
            }
            
            // 5. 关闭所有摄像头（各自的相机线程去关，这里不等）
            try {
                closeAllCameras("release");
            } catch (Exception e) {
                AppLog.e(TAG, "Error closing cameras during release", e);
            }
            
        } catch (Exception e) {
            AppLog.e(TAG, "Unexpected error during release", e);
        } finally {
            // 8. 清理集合（确保执行）
            cameras.clear();
            recorders.clear();
            codecRecorders.clear();
            setRecording(false);
            isRebuildingRecording = false;
            currentRecordingTimestamp = null;
            currentEnabledCameras = null;
            AppLog.d(TAG, "All resources released");
        }
    }

    /**
     * release() 后 cameras map 为空，此方法用于外部判断实例是否已失效。
     */
    public boolean isReleased() {
        return cameras.isEmpty();
    }

    // ------------------------------------------------------------------ 拍照

    /** 拍照的结果，在主线程上回调。 */
    public interface PhotoCallback {
        /** 相机还没出画面，要等它（开相机、建会话）。只在确实要等时调一次。 */
        default void onWaitingForCameras() {
        }

        /**
         * @param saved    存下了几路
         * @param pressed  按了快门的有几路（出了画面的）
         * @param expected 该拍几路
         */
        void onPhotoResult(int saved, int pressed, int expected);

        /** 没有地方存照片（没有 U 盘、开发者选项没开）：一路都没拍。和 {@link #onPhotoResult} 二选一。 */
        void onNoStorage();
    }

    /** 等各路出画面最多多久：冷开相机，加上看门狗第一次重建会话（8 秒）都等得到。 */
    private static final long PHOTO_READY_TIMEOUT_MS = 10_000L;
    /** 按下快门后等各路存完最多多久（一张 JPEG 120–200 ms，加解码、盖角标、写盘）。 */
    private static final long PHOTO_SAVE_TIMEOUT_MS = 5_000L;
    /** 最近多久里出过画面算「在出画面」。 */
    private static final long PHOTO_FRESH_MS = 1_500L;
    /** 各路快门错开多久，免得几路同时解码、编码。 */
    private static final long PHOTO_STAGGER_MS = 300L;

    /** 正在拍的那一张；同一时刻只拍一张。 */
    private PhotoJob photoJob;

    private static final class PhotoJob {
        final PhotoCallback callback;
        final long startedAt = android.os.SystemClock.uptimeMillis();
        final long readyDeadline = startedAt + PHOTO_READY_TIMEOUT_MS;
        boolean toldWaiting;
        /** 被别的程序拿走、录像里不在录，这一张不拍的那几路记过黑匣子没有（一张只记一行）。 */
        boolean notedLeftOut;
        boolean shutterPressed;
        int expected;
        int pressed;
        int saved;
        final Set<String> reported = new HashSet<>();

        PhotoJob(PhotoCallback callback) {
            this.callback = callback;
        }
    }

    private final Runnable photoTick = this::stepPhoto;

    /**
     * 拍一张 —— 主界面的拍照键、悬浮按钮都走这里。
     *
     * <p>拍照在登记表上登记一项（{@link CameraNeeds.Holder#PHOTO}）：相机没开，由登记表的规则去开；
     * 主界面不在前台时，没有输出的那几路挂上出帧口出画面。等各路出了画面再按快门（最多等
     * {@link #PHOTO_READY_TIMEOUT_MS}，到点只拍出了画面的），存完注销 —— 没人要了相机照常在 1.5 秒后关。
     * 结果按真的存下了几路回报；以前按了就说「已保存」，相机没开时其实什么也没拍到。</p>
     *
     * <p>开相机之前先看有没有地方存：没有 U 盘、开发者选项没开时一路都不拍，回报 {@link PhotoCallback#onNoStorage}
     * （项目所有者 2026-10-06：不允许存到内置存储）。和拒录是同一个判断（存储快照的 {@code available}）；
     * 查盘在存储线程上，查完回主线程接着拍。以前不查，拍完存进内置存储。</p>
     *
     * @return false：上一张还在拍，这一次不接
     */
    public boolean takePhoto(PhotoCallback callback) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post(() -> takePhoto(callback));
            return true;
        }
        if (photoJob != null) {
            AppLog.d(TAG, "上一张还在拍，这一次不接");
            return false;
        }
        if (isReleased()) {
            callback.onPhotoResult(0, 0, 0);
            return false;
        }
        final PhotoJob job = new PhotoJob(callback);
        photoJob = job;
        com.kooo.evcam.storage.StorageState.refresh(context, "photo", snapshot -> {
            if (photoJob != job) {
                return;   // 查盘的时候管理器没了（release 已经把这一张作罢）
            }
            if (!snapshot.available) {
                photoJob = null;
                com.kooo.evcam.blackbox.BlackBox.note("拍照：没有地方存（没有 U 盘），不拍；" + snapshot.describe());
                callback.onNoStorage();
                return;
            }
            // 没开的相机、没有输出的会话，都由登记表的规则去补（reconcileCameras）
            CameraNeeds.current().claim(CameraNeeds.Holder.PHOTO);
            stepPhoto();
        });
        return true;
    }

    /** 拍照这件事的唯一节拍：等画面 → 按快门 → 等存完（或到点）→ 收尾。 */
    private void stepPhoto() {
        PhotoJob job = photoJob;
        if (job == null) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (job.shutterPressed) {
            // 各路都报了由 onCameraPhotoDone 收；走到这里是到点了还没报齐
            AppLog.w(TAG, "拍照：" + PHOTO_SAVE_TIMEOUT_MS + "ms 内只报回 " + job.reported.size()
                    + "/" + job.pressed + " 路");
            finishPhoto(job);
            return;
        }
        List<String> keys = getActiveCameraKeys();
        // 被别的程序拿走的那几路、录像里不在录的那几路（离开中、等着接回、接回中）这一张不拍、也不算在该拍的里面
        // （2026-10-10）：它们只等看门狗单独重开、接回，等到点也多半出不了画面 —— 以前每一张都白等满 10 秒，
        // 再报「有几路没出画面」
        List<String> leftOut = new ArrayList<>();
        for (java.util.Iterator<String> it = keys.iterator(); it.hasNext(); ) {
            String key = it.next();
            SingleCamera camera = cameras.get(key);
            RecordingLanes.State lane = lanes.state(key);
            if (camera != null && CameraTaken.benched(camera.getCameraId())) {
                leftOut.add(laneName(key) + "被别的程序拿走了");
                it.remove();
            } else if (lane != null && lane != RecordingLanes.State.LIVE) {
                leftOut.add(laneName(key) + "不在录像里（" + (lane == RecordingLanes.State.JOINING ? "接回中"
                        : lane == RecordingLanes.State.LEAVING ? "离开中" : "等着接回") + "）");
                it.remove();
            }
        }
        if (!leftOut.isEmpty() && !job.notedLeftOut) {
            job.notedLeftOut = true;
            com.kooo.evcam.blackbox.BlackBox.noteImportant("拍照：相机 " + String.join("、相机 ", leftOut)
                    + "，这一张不拍");
        }
        List<String> ready = new ArrayList<>();
        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            if (camera != null && camera.readyForPhoto(PHOTO_FRESH_MS)) {
                ready.add(key);
            }
        }
        boolean allReady = !keys.isEmpty() && ready.size() == keys.size();
        // 每一路都被拿走了、不在录像里：没有可等的，不白等到点
        boolean nothingToWaitFor = keys.isEmpty() && !leftOut.isEmpty();
        if (!allReady && !nothingToWaitFor && now < job.readyDeadline) {
            if (!job.toldWaiting) {
                job.toldWaiting = true;
                job.callback.onWaitingForCameras();
            }
            mainHandler.postDelayed(photoTick, STABLE_WAIT_INTERVAL_MS);
            return;
        }
        job.expected = keys.size();
        if (ready.isEmpty()) {
            AppLog.w(TAG, "拍照：等了 " + (now - job.startedAt) + "ms，没有一路出画面");
            finishPhoto(job);
            return;
        }
        if (!allReady) {
            AppLog.w(TAG, "拍照：到点只有 " + ready + " 出了画面（该拍 " + keys + "），先拍这几路");
        }
        pressShutter(job, ready);
    }

    private void pressShutter(PhotoJob job, List<String> keys) {
        // 时间戳取按快门这一刻（不是按键那一刻：中间可能等了开相机）。几路同一个，回看按它分组
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        job.shutterPressed = true;
        job.pressed = keys.size();
        AppLog.d(TAG, "拍照：按快门 " + keys + " ts=" + timestamp);
        // 每一路错开触发，避免几路同时解码 + 编码
        for (int i = 0; i < keys.size(); i++) {
            final String key = keys.get(i);
            mainHandler.postDelayed(() -> {
                if (photoJob != job) {
                    return;
                }
                SingleCamera camera = cameras.get(key);
                if (camera == null) {
                    onCameraPhotoDone(job, key, false);
                    return;
                }
                camera.takePicture(timestamp,
                        saved -> mainHandler.post(() -> onCameraPhotoDone(job, key, saved)));
            }, i * PHOTO_STAGGER_MS);
        }
        mainHandler.postDelayed(photoTick, PHOTO_SAVE_TIMEOUT_MS + (keys.size() - 1) * PHOTO_STAGGER_MS);
    }

    private void onCameraPhotoDone(PhotoJob job, String key, boolean saved) {
        if (photoJob != job || !job.reported.add(key)) {
            return;   // 这一张已经收尾了（到点），或者同一路报了两次
        }
        if (saved) {
            job.saved++;
        }
        if (job.reported.size() >= job.pressed) {
            finishPhoto(job);
        }
    }

    private void finishPhoto(PhotoJob job) {
        if (photoJob != job) {
            return;
        }
        photoJob = null;
        mainHandler.removeCallbacks(photoTick);
        long ms = android.os.SystemClock.uptimeMillis() - job.startedAt;
        com.kooo.evcam.blackbox.BlackBox.note("拍照：存下 " + job.saved + "/" + job.expected + " 路"
                + (job.pressed < job.expected ? "（" + (job.expected - job.pressed) + " 路没出画面）" : "")
                + "，用时 " + ms + "ms" + (job.toldWaiting ? "（等了相机）" : ""));
        // 拍完就注销：没人要了相机照常在 1.5 秒后关，出帧口也跟着摘掉
        CameraNeeds.current().release(CameraNeeds.Holder.PHOTO);
        job.callback.onPhotoResult(job.saved, job.pressed, job.expected);
    }

    private List<String> getActiveCameraKeys() {
        if (!activeCameraKeys.isEmpty()) {
            return new ArrayList<>(activeCameraKeys);
        }
        List<String> keys = new ArrayList<>();
        int opened = 0;
        Set<String> openedIds = new HashSet<>();
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            if (opened >= maxOpenCameras) {
                break;
            }
            SingleCamera camera = entry.getValue();
            String id = camera.getCameraId();
            if (!openedIds.add(id)) {
                continue;
            }
            keys.add(entry.getKey());
            opened++;
        }
        return keys;
    }

    /** 环视此刻出画面到多久以内算「正常」。 */
    private static final long SURROUND_FRESH_MS = 2_000L;

    /**
     * 环视此刻是不是在正常出画面 —— 「能获得视频流」以环视为准（规格 §2.2）。
     * 开录、接回都只看它（RecordingCoordinator）。
     */
    public boolean surroundHealthy() {
        SingleCamera surround = getCamera(CameraSlots.KEY_SURROUND);
        return surround != null && surround.isCameraOpened() && surround.hasFramesWithin(SURROUND_FRESH_MS);
    }

    /**
     * 获取已连接的相机数量
     */
    public int getConnectedCameraCount() {
        int count = 0;
        for (SingleCamera camera : cameras.values()) {
            if (camera.isConnected()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 别的程序不再占着相机（它放开了，或者我们开成了）、或者访问优先级变了（{@link CameraTaken} 在主线程调）：
     * 被拿走的那几路，看门狗下一次检查就看。
     *
     * <p>2026-10-10 起这里不再自己重开。以前它把该开着、没开着的几路当场全部重开，不看别的相机是不是正在开、
     * 正在关，也不一路一路来 —— 相机服务交接的中途就动了通道。重开只走看门狗那一道闸门（{@link #checkLiveness}、
     * {@link CameraTaken#gate}）；这里只把它们 30 秒的节奏清零：放开了、通道安静了就单独重开，
     * 还被占着（优先级变了那种）就下一次检查试一下 —— 前台的一方拿得到。</p>
     *
     * @param releasedCameraId 放开的那一路；优先级变了那种是 null
     */
    public void retryTaken(String releasedCameraId) {
        StringBuilder which = new StringBuilder();
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            SingleCamera camera = entry.getValue();
            if (camera == null || !CameraTaken.benched(camera.getCameraId())) {
                continue;
            }
            CameraLiveness.State state = livenessStates.get(entry.getKey());
            if (state != null) {
                state.due();
            }
            which.append(which.length() > 0 ? "、" : "")
                    .append(camera.getCameraId()).append("（").append(roleName(entry.getKey())).append("）");
        }
        if (which.length() > 0) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant((releasedCameraId == null ? "访问优先级变了"
                    : "别的程序不再占着相机 " + releasedCameraId + "，一路都不占了")
                    + "：被拿走的相机 " + which + " 由看门狗下一次检查接（通道安静了单独重开，还被占着就试一下）");
        }
    }

    /**
     * 获取所有摄像头当前使用的分辨率信息
     * @return 格式化的分辨率信息字符串
     */
    /**
     * 获取所有摄像头的实时调试信息（FPS + 分辨率）
     */
    public String getDebugStats() {
        StringBuilder sb = new StringBuilder();
        String[] order = CameraNames.POSITIONS;
        for (int i = 0; i < order.length; i++) {
            SingleCamera camera = cameras.get(order[i]);
            if (camera == null) continue;
            if (sb.length() > 0) sb.append("\n");
            android.util.Size previewSize = camera.getPreviewSize();
            String res = previewSize != null
                    ? previewSize.getWidth() + "×" + previewSize.getHeight()
                    : "-";
            float fps = camera.getCurrentFps();
            sb.append(CameraNames.of(context, order[i]))
                    .append("(").append(camera.getCameraId()).append(") ");
            sb.append(String.format(java.util.Locale.US, "%.1f fps  ", fps));
            sb.append(res);
        }
        return sb.toString();
    }

    public String getCameraResolutionsInfo() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            String key = entry.getKey();
            SingleCamera camera = entry.getValue();
            String cameraId = camera.getCameraId();
            Size previewSize = camera.getPreviewSize();
            
            if (sb.length() > 0) {
                sb.append("\n");
            }
            
            sb.append(key).append(" (摄像头").append(cameraId).append("): ");
            if (previewSize != null) {
                sb.append(previewSize.getWidth()).append("×").append(previewSize.getHeight());
            } else {
                sb.append("未初始化");
            }
        }
        return sb.toString();
    }

    /** 录像左上角要标的那行字：应用名 + 版本号，填了车牌号就跟在后面。 */
    private String buildBrandLine() {
        String version = "";
        try {
            version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            AppLog.w(TAG, "读取版本号失败: " + e);
        }
        // 拼法在 WatermarkText 里，照片那一侧用的是同一个函数
        return WatermarkText.brandLine(
                context.getString(com.kooo.evcam.R.string.app_name),
                version, new AppConfig(context).getLicensePlate());
    }

    /**
     * 「硬件最多能给多少帧」。
     *
     * <p>优先用相机自己声明的值。以前这里写死 25 —— 那是当初为了压低 CPU 定的假设，
     * 不是从相机读来的。实测下来这一路稳在 29–30，写死 25 的后果是：选 30 被悄悄
     * 夹到 25，而界面上还写着 30。</p>
     */
    private static int hardwareMaxFps() {
        int declared = CameraCapabilities.declaredMaxFps();
        return declared > 0 ? declared : AppConfig.RECORDER_MAX_FPS;
    }

}
