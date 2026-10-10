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

    // ---- 通道调度（2.11，项目所有者 2026-10-10 确认）----
    // 开、关、改输出、救、重开都只在 ChannelScheduler 一处：这里的有序开（一路出了画面再开下一路）、关一轮（环视单独先关，
    // 关完再关座舱）、看门狗、登记表的「没人要等 30 秒再关」都搬了进去。为什么环视要单独开、单独关，见 ChannelRules
    // closeStep 的说明（2026-10-09 黑匣子：环视比座舱晚关完的那几次，那一次关要 4–17 秒，下一次打开它一帧不出）。
    /**
     * 调度自己的 Handler（同一个主线程 Looper）。不用 mainHandler：release() 开头会 removeCallbacksAndMessages(null)，
     * 而调度那时正要按次序关相机 —— 2.10.5 时就是这样把「上一路关完、关下一路」的续命清掉了，后两路没关进程就退了
     * （2026-10-08 23:18）。
     */
    private final android.os.Handler channelHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 这一份管线的通道调度：唯一动相机的地方。构造里建，initCameras 里交给它要动的几路。 */
    private final ChannelScheduler scheduler;
    /** 调度的 Android 那一层：事实、问相机服务、黑匣子的句子（中文都在这里拼）。 */
    private final ChannelSide channelSide = new ChannelSide();
    /** 登记表的监听：只叫醒自己这一份调度。release 时只在它还是监听者时才摘（换车型时两份并存）。 */
    private final CameraNeeds.Listener needsListener;
    /** 亮屏、熄屏：叫醒调度（熄屏时 PREVIEW 不算、不保持，由规则判）。 */
    private final com.kooo.evcam.screen.ScreenState.Listener screenWake =
            new com.kooo.evcam.screen.ScreenState.Listener() {
                @Override
                public void onScreenOff() {
                    scheduler.wake();
                }

                @Override
                public void onScreenOn() {
                    scheduler.wake();
                }
            };
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

    // ---- 一路单独离开录像（项目所有者 2026-10-10）----
    // 一路被别的程序拿走（车主离车时车机拿走后座舱相机 1），只停这一路：别的几路照录，会话不重建，录像的代数、
    // 「在录」、协调器都不动。它是最后一路在录的，才和以前一样整次停、等环视接回。MediaRecorder 模式没有单独接回，离开就整次停。
    // v2 的单独接回（看门狗的那一下重开就是接回）随看门狗一起拆了（2.11 阶段 3）：阶段 5 换成「路」的宿主 ——
    // 相机带着录像输出出了画面就加入，第一次开录和中途接回是同一件事。

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
    /** 离开中的一路，相机还在关时隔多久再看一次它走完没有（{@link #settleLeave}）。 */
    private static final long LEAVE_SETTLE_POLL_MS = 500L;
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
        // 通道调度：唯一动相机的地方（2.11，项目所有者 2026-10-10）。它只在主线程上跑，每一轮都 post 到 channelHandler
        scheduler = new ChannelScheduler(android.os.SystemClock::uptimeMillis, new ChannelScheduler.Poster() {
            @Override
            public void post(Runnable task) {
                channelHandler.post(task);
            }

            @Override
            public void postDelayed(Runnable task, long delayMs) {
                channelHandler.postDelayed(task, delayMs);
            }

            @Override
            public void cancel(Runnable task) {
                channelHandler.removeCallbacks(task);
            }
        }, channelSide, channelSide);
        // 相机服务每说一句、问的结果回来、注册回放收全：叫醒调度；分类时问它「这一路有没有我们的开或关在途」
        CameraAvailabilityWatch.addListener(channelSide);
        // 登记表一变就叫醒调度（只认这一份：换车型时旧的那份晚 release，摘不掉这份的监听）
        needsListener = scheduler::onNeedsChanged;
        CameraNeeds.current().setListener(needsListener);
        // 亮屏、熄屏叫醒调度（ScreenState 在主线程上叫）
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            com.kooo.evcam.screen.ScreenState.addListener(screenWake);
        } else {
            mainHandler.post(() -> com.kooo.evcam.screen.ScreenState.addListener(screenWake));
        }
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
     * 这一路此刻被拿着（被别的程序拿着，或者被拿着的另一路挡着）：照调度的账答（2.11 起被拿着只记在调度里）。
     * 录像那几处（开录先不录它、离开时说清楚是不是被拿走的）读它，阶段 5 换成「路」的宿主以后跟着改。主线程。
     */
    private boolean takenNow(String key) {
        return scheduler.condition(key) == ChannelRules.Condition.TAKEN;
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
     *   <li>在录的一路报设备错误 3 / 4 / 5：录制器留着，调度按救援的节奏重开。2.11 阶段 3 起重开出来的会话只带声明了的输出，
     *       录像输出阶段 5b 才声明，所以这一路的录制器收不到帧：15 秒后录制器的裁判报上来，按「会话里已经没有它的录像输出」
     *       单独离开（{@link #onLaneWriteStalled}），不再是 v2 那样同一个录制器接着录。</li>
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
                + "：整次停录，等环视恢复后接回；被拿着的 " + scheduler.marks());
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
     * 离开中的一路走完了没有（主线程；它的录制器收拾完时看，相机还在途、调度还没判完就隔一会儿再看）：录制器收好了、
     * 相机不在途了（被断开的那一次关完了）、调度也判完了。完了就是不在录：调度判出被拿着的（{@link #takenNow}）
     * 等它放开、通道安静了由调度单独开它；别的失败由调度按救援的节奏重开。黑匣子一行：离开用了多久、文件、
     * 这段时间里别的路怎么样。
     */
    private void settleLeave(String key) {
        LaneChange change = laneChanges.get(key);
        SingleCamera camera = cameras.get(key);
        if (lanes.state(key) != RecordingLanes.State.LEAVING || change == null || !change.recorderDone) {
            return;
        }
        if ((camera != null && camera.isBusy()) || scheduler.judging(key)) {
            // 被断开的那一次关还没关完（车机拿走后座舱时 5–8 秒），或者关完了、调度还在那 300 ms 里没判：隔一会儿再看。
            // 以前由看门狗每 2 秒带着看（stepLanes），isBusy 里还含着「在判」（judgingLoss）；看门狗并进调度之后这里自己看，
            // 「在判」问调度 —— 只看 isBusy 的话，关完到判完之间看一眼，被拿走的会被写成「不是被别的程序拿走的」
            //（审查 2026-10-11）
            mainHandler.postDelayed(() -> settleLeave(key), LEAVE_SETTLE_POLL_MS);
            return;
        }
        boolean taken = takenNow(key);
        lanes.out(key, taken ? RecordingLanes.Reason.TAKEN : RecordingLanes.Reason.FAILED,
                android.os.SystemClock.elapsedRealtime());
        laneChanges.remove(key);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + laneName(key) + " 离开录像了（" + change.why
                + "，离开用了 " + seconds(android.os.SystemClock.uptimeMillis() - change.startedAt) + "）："
                + (taken ? "被别的程序拿着，等它放开、通道安静了调度单独开它" : "不是被别的程序拿走的，调度按救援的节奏重开它")
                + "；" + change.recorderResult + "；" + othersDuring(change));
        notifyLanes();
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
     *   <li>在录的一路，而它的相机没了、在重开（设备错误之后调度在救），或者相机的会话里已经没有它的录像输出：
     *       是相机的事，这一路单独离开 —— 它是最后一路在录的才整次停；</li>
     *   <li>别的（相机好好的、会话里有它，写不进是盘的事）：和以前一样整次停（{@link #onWriteStalled}）。</li>
     * </ul>
     *
     * <p>「会话里已经没有它的录像输出」是阶段 5b {@code stallIsCamera} 里「带着」那一项，提前到这里（审查 2026-10-11）：
     * 2.11 起救援重开只带声明了的输出，录像输出阶段 5b 才声明 —— 设备错误之后调度重开出来的会话里没有它，录制器一帧都收不到；
     * 不算相机的事的话会整次停、记成盘的错，好好的另外几路也跟着停。</p>
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
        boolean carried = camera != null && camera.sessionCarries(laneSurfaces.get(key));
        if (state == RecordingLanes.State.LIVE && camera != null
                && (camera.deviceLost() || camera.isBusy() || !carried)) {
            String why = (stalledMs / 1000) + " 秒没写进数据，它的相机" + (camera.deviceLost() ? "没了"
                    : camera.isBusy() ? "在重开" : "会话里已经没有它的录像输出");
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
     * 这一路马上就要关了：调度正在为退出、车机要睡按次序关（{@link ChannelScheduler#closingAnywhere}），或者没人要相机。
     * 摘了它的输出也不为它重建会话 —— 不为摘一个输出再进一次相机服务，更不在关之前动它：环视会话还在配就去关，
     * 是车上见过的坏样子（关得慢、下一次打开没画面）。只给还没迁走的旧路径用（画布没了、停录收拾完），阶段 4、5 删。
     */
    private static boolean closesSoon(SingleCamera camera) {
        return ChannelScheduler.closingAnywhere()
                || (!CameraNeeds.current().heldByAnyone() && !camera.keepsStreaming());
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

        // 清空之前的摄像头实例
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
                // 相机线程上来的：转到主线程交给调度（调度从不在回调里当场跑一轮）
                channelHandler.post(() -> scheduler.onOpened(cameraId));
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, STATUS_OPENED);
                }
            }

            @Override
            public void onCameraConfigured(String cameraId) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " configured");
                channelHandler.post(scheduler::onCameraEvent);
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
                channelHandler.post(() -> scheduler.onClosed(cameraId));
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, STATUS_CLOSED);
                }
            }

            @Override
            public void onCameraError(String cameraId, int errorCode) {
                String errorMsg = getErrorMessage(errorCode);
                AppLog.e(TAG, "Callback: Camera " + cameraId + " error: " + errorCode + " - " + errorMsg);
                channelHandler.post(scheduler::onCameraEvent);
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

            // ---- 2.11 通道调度的那几个：都在相机线程上发，转到主线程交给调度

            @Override
            public void onFirstFrame(String cameraId) {
                channelHandler.post(scheduler::onCameraEvent);
            }

            @Override
            public void onLossStarted(String cameraId, int code) {
                channelHandler.post(() -> scheduler.onLossStarted(cameraId, code));
            }

            @Override
            public void onLossClosed(String cameraId, int code, long lostAt, long closeMs) {
                channelHandler.post(() -> scheduler.onLossClosed(cameraId, closeMs));
            }

            @Override
            public void onOpenFailed(String cameraId, CameraTaken.Reason reason) {
                channelHandler.post(() -> scheduler.onOpenFailed(cameraId, reason));
            }

            @Override
            public void onOutputDropped(String cameraId, ChannelRules.Out out, SingleCamera.Drop why) {
                channelHandler.post(() -> scheduler.onOutputDropped(cameraId, out, why));
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

        // 交给调度：这一份管线要动的几路 —— 按开的次序，同一个相机 id 只算一次，不超过 maxOpenCameras
        // （以前在 openAllCameras 里算；现在建好就定，录像、拍照、调度读的是同一份）
        activeCameraKeys.clear();
        Map<String, ChannelScheduler.CameraPort> ports = new LinkedHashMap<>();
        final List<SingleCamera> active = new ArrayList<>();
        Set<String> activeIds = new HashSet<>();
        for (String key : CameraSlots.openOrder(cameras.keySet())) {
            if (activeCameraKeys.size() >= maxOpenCameras) {
                break;
            }
            SingleCamera camera = cameras.get(key);
            if (camera == null || !activeIds.add(camera.getCameraId())) {
                continue;
            }
            activeCameraKeys.add(key);
            ports.put(key, new LanePort(camera));
            active.add(camera);
        }
        // 主线程上交（调度只在主线程上跑）；画面尺寸不开设备先算（SingleCamera 在自己起的相机线程上算），排在这一轮主线程
        // 任务之后 —— 调用方紧接着会钉住环视的合成流尺寸（setPreferredSize），先算就算成别的尺寸了。然后叫醒调度
        channelSide.noteIds(ports);
        channelHandler.post(() -> {
            scheduler.setLanes(ports);
            for (SingleCamera camera : active) {
                camera.resolveSize();
            }
        });

        AppLog.d(TAG, "Cameras initialized: " + activeCameraKeys);
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

    /** 一路走完，记到这一轮的轨迹里：「环视 1.1s」（整轮走完黑匣子记一行，关的那行按关完的先后）。 */
    private static void noteStep(List<String> trail, String key, long ms) {
        trail.add(roleName(key) + " " + String.format(java.util.Locale.US, "%.1fs", ms / 1000f));
    }

    private static void noteTrail(String what, List<String> trail, String separator) {
        if (!trail.isEmpty()) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant(what + "：" + String.join(separator, trail));
            trail.clear();
        }
    }

    /**
     * 叫醒通道调度（2.11）。以前这里按次序把每一路开起来；现在开不开、先开哪一路、带什么输出只由调度判
     * （{@link ChannelScheduler}，项目所有者 2026-10-10）：相机靠登记表（主界面的 PREVIEW、录像、拍照、后视镜）开起来。
     * MainActivity 的两处调用阶段 4 删，阶段 7 删掉这个方法。
     */
    public void openAllCameras() {
        scheduler.wake();
    }

    /** 按次序开相机走完了没有：调度手上不是开的那一步，也没有一路等着开（录像开录前等它，阶段 5 删那道闸）。 */
    public boolean openInOrderDone() {
        return !scheduler.openStepsPending();
    }

    /**
     * 这一份管线的相机都关好了：调度的账上每一路都关着，相机层也没有一路开着、在开、在关。
     * 熄屏录制到点停录之后，等它成立再放唤醒锁（{@code ScreenOffRecording}，阶段 6 改等 {@link ChannelScheduler#closingAnywhere}）。
     */
    public boolean allCamerasClosed() {
        return scheduler.allClosed();
    }

    /** 这一份管线的通道调度（录像、后视镜、诊断读它的事实：condition、usable、describe）。 */
    public ChannelScheduler channelScheduler() {
        return scheduler;
    }

    /** 录像要哪几路：这一次录像的计划；计划还没定（开录之前、MediaRecorder 模式）就是启用的全部 —— 和以前一样。 */
    private Set<String> recordPlanKeys() {
        Set<String> keys = new java.util.LinkedHashSet<>();
        if (!CameraNeeds.current().isHeld(CameraNeeds.Holder.RECORDING)) {
            return keys;
        }
        keys.addAll(recordingPlan.isEmpty() ? activeCameraKeys : recordingPlan.keySet());
        return keys;
    }

    // ------------------------------------------------------------------ 通道调度的 Android 那一层

    /** 一路相机给调度的样子：SingleCamera 的动作和事实照转（主线程上调）。 */
    private static final class LanePort implements ChannelScheduler.CameraPort {
        private final SingleCamera camera;

        LanePort(SingleCamera camera) {
            this.camera = camera;
        }

        @Override
        public String cameraId() {
            return camera.getCameraId();
        }

        @Override
        public boolean open(Set<ChannelRules.Out> outputs) {
            return camera.open(outputs);
        }

        @Override
        public boolean applyOutputs(Set<ChannelRules.Out> outputs) {
            return camera.applyOutputs(outputs);
        }

        @Override
        public boolean rebuildSession(Set<ChannelRules.Out> outputs) {
            return camera.rebuildSession(outputs);
        }

        @Override
        public boolean reopen(Set<ChannelRules.Out> outputs) {
            return camera.reopen(outputs);
        }

        @Override
        public void close(String why) {
            camera.closeCamera(why);
        }

        @Override
        public void resolveSize() {
            camera.resolveSize();
        }

        @Override
        public boolean sizeResolved() {
            return camera.resolvedSize() != null;
        }

        @Override
        public boolean deviceOpen() {
            return camera.isCameraOpened();
        }

        @Override
        public boolean holdsOrIsOpening() {
            return camera.holdsOrIsOpening();
        }

        @Override
        public long busySince() {
            return camera.busySince();
        }

        @Override
        public String describeBusy() {
            return camera.busyKinds();
        }

        @Override
        public SingleCamera.SessionOutputs sessionOutputs() {
            return camera.sessionOutputs();
        }

        @Override
        public Set<ChannelRules.Out> declaredOutputs() {
            return camera.declaredOutputs();
        }

        @Override
        public Set<ChannelRules.Out> deadInSession() {
            return camera.deadInSession();
        }

        @Override
        public long firstFrameAt() {
            return camera.firstFrameOfSessionAt();
        }

        @Override
        public long progressAgeMs() {
            return camera.progressAgeMs();
        }

        @Override
        public int sessionGeneration() {
            return camera.sessionGeneration();
        }

        @Override
        public long longestFrameGapSince(long since) {
            return camera.longestFrameGapSince(since);
        }

        @Override
        public String lastErrorName() {
            return camera.lastErrorName();
        }

        @Override
        public void dropped(ChannelRules.Out out, SingleCamera.Drop why) {
            SingleCamera.RecordDropped listener = camera.dropListener(out);
            if (listener != null) {
                listener.onDropped(why);
            }
        }
    }

    /**
     * 调度的 Android 那一层：全局事实（登记表、亮熄屏、前台服务、主界面在不在前、相机服务那几句）、问相机服务、记争用；
     * 黑匣子的句子也在这里拼（调度本身不写中文，见 {@link ChannelScheduler.Journal}）。一件事一行。主线程上调。
     */
    private final class ChannelSide implements ChannelScheduler.Host, ChannelScheduler.Journal,
            CameraAvailabilityWatch.Listener {

        /** 每一路的相机编号：关相机那几行要写，release 之后 cameras 清空了也还查得到。 */
        private final Map<String, String> ids = new java.util.concurrent.ConcurrentHashMap<>();

        void noteIds(Map<String, ChannelScheduler.CameraPort> ports) {
            for (Map.Entry<String, ChannelScheduler.CameraPort> entry : ports.entrySet()) {
                ids.put(entry.getKey(), entry.getValue().cameraId());
            }
        }

        // ---- 全局事实

        @Override
        public Set<CameraNeeds.Holder> holders() {
            Set<CameraNeeds.Holder> held = java.util.EnumSet.noneOf(CameraNeeds.Holder.class);
            CameraNeeds needs = CameraNeeds.current();
            for (CameraNeeds.Holder holder : CameraNeeds.Holder.values()) {
                if (needs.isHeld(holder)) {
                    held.add(holder);
                }
            }
            return held;
        }

        @Override
        public boolean headUnitSleeps() {
            return CameraNeeds.current().headUnitSleeps();
        }

        @Override
        public Set<String> recordPlan() {
            return recordPlanKeys();
        }

        @Override
        public boolean screenOn() {
            return !com.kooo.evcam.screen.ScreenState.dark();
        }

        @Override
        public boolean foregroundService() {
            return com.kooo.evcam.CameraForegroundService.isRunning();
        }

        @Override
        public boolean mainForeground() {
            return StallWatch.isForeground();
        }

        @Override
        public boolean serviceStartedWhileVisible() {
            return com.kooo.evcam.CameraForegroundService.startedWhileVisible();
        }

        @Override
        public boolean heardAnything() {
            return CameraAvailabilityWatch.heardAnything();
        }

        @Override
        public long elapsedNow() {
            return android.os.SystemClock.elapsedRealtime();
        }

        @Override
        public long lastOthersChangeAt() {
            return CameraAvailabilityWatch.lastOthersChangeAt();
        }

        @Override
        public CameraAvailabilityWatch.Word word(String cameraId) {
            return CameraAvailabilityWatch.word(cameraId);
        }

        @Override
        public ChannelRules.RecordPrep recordPrep(String key) {
            // 录像那一侧还没报（阶段 5 由「路」的宿主报在备 / 备好了 / 建不起来）
            return ChannelRules.RecordPrep.NONE;
        }

        @Override
        public void ask(java.util.function.Consumer<Set<String>> answered) {
            CameraAvailabilityWatch.ask(asked -> {
                Set<String> corrected = new java.util.TreeSet<>();
                for (CameraAvailabilityWatch.Correction fix : asked.corrections) {
                    corrected.add(fix.cameraId);
                }
                answered.accept(corrected);
            });
        }

        @Override
        public void othersTook(String cameraId, long atElapsed) {
            // 相机服务那一声「被占用、不是我们」当时就记过争用的，不再记一遍
            if (!CameraContention.othersHoldNow(cameraId)) {
                CameraContention.othersTookAt(cameraId, atElapsed);
            }
        }

        @Override
        public void stopped() {
            CameraAvailabilityWatch.removeListener(this);
        }

        // ---- 相机服务那边（CameraAvailabilityWatch.Listener）

        @Override
        public boolean ourStepInFlight(String cameraId) {
            return scheduler.ourStepInFlight(cameraId);
        }

        @Override
        public void onServiceChanged() {
            scheduler.wake();
        }

        @Override
        public void onAccessPrioritiesChanged() {
            scheduler.askNow();
        }

        // ---- 黑匣子（一件事一行）

        @Override
        public void needs(Set<CameraNeeds.Holder> holders, List<String> recordKeys) {
            com.kooo.evcam.blackbox.BlackBox.note("登记表：" + holderCodes(holders)
                    + (recordKeys.isEmpty() ? "" : "（录 " + roles(recordKeys) + "）"));
        }

        @Override
        public void holding(List<String> keys, Set<ChannelRules.Kind> outlets) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：没人要 " + roles(keys) + " 了：亮屏、前台服务在，保持 "
                    + (ChannelRules.HOLD_MS / 1000) + " 秒（画面接着送进 " + kindNames(outlets) + "）");
        }

        @Override
        public void holdCancelled(List<String> keys, Set<CameraNeeds.Holder> by) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：" + roles(keys) + " 保持取消（" + holderCodes(by) + " 又要了）");
        }

        @Override
        public void recognized(String key, String cameraId, boolean absent) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId + "（" + roleName(key) + "）还没开，相机服务说它"
                    + (absent ? "不在" : "被占用、不是我们") + " → 不抢，等它空出来（每 "
                    + (ChannelRules.ASK_EVERY_MS / 1000) + " 秒问一次相机服务，只问，不开）");
        }

        @Override
        public void judged(ChannelScheduler.Judgement j) {
            String who = j.cameraId + "（" + roleName(j.key) + "）";
            String line;
            if (j.origin == ChannelRules.Failure.Origin.LOSS) {
                String head = "相机 " + who + " 丢了（" + lossText(j.reason) + "）"
                        + (j.closeMs >= 0 ? "，关完用了 " + seconds(j.closeMs) : "") + "；";
                switch (j.verdict) {
                    case TAKEN:
                        line = head + (j.absent ? "相机服务的缓存里没有它（不在）"
                                : j.serviceFree == null ? "相机服务没说过它空闲" : "相机服务一直没说它空闲")
                                + " → 别的程序拿着它：不抢，每 " + (ChannelRules.ASK_EVERY_MS / 1000)
                                + " 秒问一次相机服务（只问，不开）";
                        break;
                    case BLOCKED:
                        line = head + "它自己空着，别的程序拿着相机 " + String.join("、", j.takenIds)
                                + " → 也算被拿着，等都放开再开";
                        break;
                    case UNUSABLE:
                        line = head + "现在用不了相机 → 不计次数，等主界面回到前台";
                        break;
                    default:
                        line = head + (!j.heardAnything ? "相机服务从没报过空闲 / 被占用 → 认不出被拿走，按普通故障救"
                                : "相机服务说它空闲" + wordWhen(j.wordBeforeCloseMs) + " → 普通故障，按救援的节奏重开");
                        break;
                }
            } else {
                boolean reopen = j.origin == ChannelRules.Failure.Origin.REOPEN;
                String head = "通道：" + (reopen ? "救 " + roleName(j.key) + " 重开" : "开 " + roleName(j.key))
                        + " → 开不起来" + (j.reason != null && j.reason.source == CameraTaken.Reason.Source.NOT_LISTED
                        ? "：相机 " + j.cameraId + " 不在相机列表里" : "（" + openFailText(j.reason) + "）");
                switch (j.verdict) {
                    case TAKEN:
                        line = head + " → 别的程序拿着它：不抢，每 " + (ChannelRules.ASK_EVERY_MS / 1000)
                                + " 秒问一次（只问，不开）";
                        break;
                    case BLOCKED:
                        line = head + "，别的程序拿着相机 " + String.join("、", j.takenIds) + " → 也算被拿着，等都放开再开";
                        break;
                    case UNUSABLE:
                        line = head + " → 不计次数，等主界面回到前台";
                        break;
                    default:
                        line = head + (j.heardAnything ? "" : "（相机服务从没报过空闲 / 被占用，认不出被拿走）")
                                + " → 普通故障，按救援的节奏，第 " + Math.max(1, j.attempt) + " 次";
                        break;
                }
            }
            com.kooo.evcam.blackbox.BlackBox.noteImportant(line);
        }

        @Override
        public void released(String key, String cameraId, CameraTaken.Verdict was, long heldMs, boolean asked) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId + "（" + roleName(key) + "）放开了（"
                    + (was == CameraTaken.Verdict.BLOCKED ? "挡着它的那一路放开了；被挡了 "
                    : (asked ? "问出来的" : "相机服务报的") + "；被拿走 ") + duration(heldMs)
                    + "）：等通道安静 " + (ChannelRules.QUIET_MS / 1000) + " 秒，单独开");
        }

        @Override
        public void stopLifted(String key, ChannelRules.Event.Cause cause, Set<CameraNeeds.Holder> holders) {
            String why = cause == ChannelRules.Event.Cause.SCREEN_ON ? "亮屏了"
                    : cause == ChannelRules.Event.Cause.SERVICE_FREE ? "相机服务报它空闲"
                    : "登记表变了（" + holderCodes(holders) + "）";
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：" + why + "，停手的 " + roleName(key) + " 重新救");
        }

        @Override
        public void gaveUp(String key) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：救 " + roleName(key) + " 连着救 "
                    + CameraLiveness.MAX_ATTEMPTS + " 次没用，歇 " + (CameraLiveness.COOL_OFF_MS / 1000) + " 秒");
        }

        @Override
        public void stopped(String key, String lastError) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：救 " + roleName(key) + " 救了 "
                    + CameraLiveness.MAX_CYCLES + " 轮没用，停手：等登记表变了、相机服务报它空闲、或者亮屏再试（最后一次报错 "
                    + (lastError == null ? "没有" : lastError) + "）");
        }

        @Override
        public void waiting(ChannelScheduler.Wait wait) {
            String line;
            switch (wait.why) {
                case LOSING:
                case BUSY:
                    // 格式照大纲的黑匣子目录：「通道：等 后座舱 在途（closing-lost 2.1 s）再动别的」
                    line = "通道：等 " + roleName(wait.key) + " 在途（" + (wait.busy.isEmpty() ? "" : wait.busy + " ")
                            + String.format(java.util.Locale.US, "%.1f s", wait.agoMs / 1000f) + "）再动别的";
                    break;
                case OTHER_INSTANCE:
                    line = "通道：等另一份管线把相机关完（换车型、退出后马上又开）再动";
                    break;
                case QUIET:
                    line = "通道：相机服务 " + seconds(wait.agoMs) + "前报了别人引起的变化"
                            + (wait.wordCameraId == null ? "" : "（" + wait.wordCameraId + " "
                            + (wait.wordAbsent ? "不在" : Boolean.TRUE.equals(wait.wordFree) ? "空闲"
                            : Boolean.FALSE.equals(wait.wordFree) ? "被占用" : "?")
                            + (wait.weHoldIt ? "，我们开着它" : "") + "）")
                            + "，安静满 " + (ChannelRules.QUIET_MS / 1000) + " 秒再" + pendingText(wait.pending, wait.key);
                    break;
                case RECORD_OUTPUT:
                    line = "通道：开 " + roleName(wait.key) + " 之前先等它的录像输出（录制器在建，最多 "
                            + (ChannelRules.RECORD_OUTPUT_WAIT_MS / 1000) + " 秒）";
                    break;
                default:
                    return;
            }
            com.kooo.evcam.blackbox.BlackBox.note(line);
        }

        @Override
        public void deferred(String key, ChannelScheduler.Unusable why) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：现在用不了相机（"
                    + (why == ChannelScheduler.Unusable.REPORTED ? "打开时报了用不了相机，等主界面回到前台"
                    : why == ChannelScheduler.Unusable.NO_SERVICE ? "主界面不在前，前台服务不在"
                    : "主界面不在前，前台服务是后台起的") + "），先不开 " + roleName(key));
        }

        @Override
        public void recordWaitEnded(String key, long waitedMs, boolean withRecord) {
            com.kooo.evcam.blackbox.BlackBox.note("通道：开 " + roleName(key) + " 之前等录像输出："
                    + (withRecord ? "等了 " + seconds(waitedMs) + "，带着录像输出开"
                    : (ChannelRules.RECORD_OUTPUT_WAIT_MS / 1000) + " 秒还没好，先开"));
        }

        @Override
        public void stepEnded(ChannelScheduler.StepReport r) {
            String name = roleName(r.key);
            String result = r.timedOut
                    ? (ChannelRules.STEP_MAX_MS / 1000) + " 秒没出画面" + (r.move == ChannelRules.Move.OPEN
                    ? "，先往下走（由救援接手）" : "（由救援接手）")
                    : (r.configuredMs >= 0 && r.move != ChannelRules.Move.OPEN ? "配好 " + seconds(r.configuredMs) + "、" : "")
                    + "出画面 " + seconds(Math.max(0, r.frameMs));
            String line;
            switch (r.move) {
                case OPEN:
                    line = "通道：开 " + name + "（为 " + holderNames(r.forWhom) + "，输出 " + kindNames(r.outputs) + "）→ "
                            + result;
                    break;
                case APPLY:
                    if (r.why == ChannelRules.Why.DEAD) {
                        line = "通道：马上摘 " + name + " 的" + kindNames(r.removed) + "（画布被系统收走）"
                                + (r.added.isEmpty() ? "" : "；顺带加 " + kindNames(r.added)) + " → " + result;
                    } else {
                        line = "通道：改输出 " + name + "：加 " + kindNames(r.added)
                                + (r.removed.isEmpty() ? "" : "；顺带摘 " + kindNames(r.removed)) + " → " + result;
                    }
                    break;
                default:
                    line = "通道：救 " + name + "（" + (r.ageMs >= 0 ? seconds(r.ageMs) + "没画面" : "关着：丢了 / 开不起来")
                            + "），第 " + r.attempt + " 次：" + (r.rescue == ChannelRules.Rescue.REBUILD ? "重建会话" : "重开")
                            + " → " + result;
                    break;
            }
            com.kooo.evcam.blackbox.BlackBox.noteImportant(line + othersText(r.others));
        }

        @Override
        public void openTrail(List<String> keys, List<Long> ms) {
            List<String> trail = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                noteStep(trail, keys.get(i), ms.get(i));
            }
            noteTrail("按次序开相机", trail, " → ");
        }

        @Override
        public void closing(List<String> keys, ChannelRules.Why why) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：关 " + roles(keys) + "（" + closeWhyText(why) + "）"
                    + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        }

        @Override
        public void closeTimedOut(List<String> keys, long maxMs, boolean surround) {
            if (surround) {
                com.kooo.evcam.blackbox.BlackBox.noteImportant("关相机：环视等了 " + (maxMs / 1000)
                        + " 秒还没关完（相机服务卡住？），先关座舱" + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
                return;
            }
            StringBuilder stuck = new StringBuilder();
            for (String key : keys) {
                stuck.append(stuck.length() > 0 ? "、" : "").append(roleName(key)).append("(")
                        .append(ids.containsKey(key) ? ids.get(key) : "?").append(")");
            }
            com.kooo.evcam.blackbox.BlackBox.noteImportant("关相机：座舱等了 " + (maxMs / 1000)
                    + " 秒还没关完：" + stuck + "，不再等" + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        }

        @Override
        public void closeRound(String surroundKey, long surroundMs, boolean surroundTimedOut,
                               List<String> cabinKeys, List<Long> cabinMs) {
            List<String> cabins = new ArrayList<>();
            for (int i = 0; i < cabinKeys.size(); i++) {
                noteStep(cabins, cabinKeys.get(i), cabinMs.get(i));
            }
            String surround = surroundKey == null ? null : surroundTimedOut
                    ? roleName(surroundKey) + " >" + (surroundMs / 1000) + "s"
                    : roleName(surroundKey) + " " + String.format(java.util.Locale.US, "%.1fs", surroundMs / 1000f);
            String cabinLine = String.join("、", cabins);
            String line = surround == null ? cabinLine : cabinLine.isEmpty() ? surround : surround + " → " + cabinLine;
            if (!line.isEmpty()) {
                // 「关相机：…」这一行的格式从 2.10.10 起没变，旧的分析脚本照样能读（阶段 8 只在末尾追加）
                com.kooo.evcam.blackbox.BlackBox.noteImportant("关相机：" + line
                        + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
            }
        }

        @Override
        public void sleep(String key, ChannelRules.Move move, long ms) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：车机要睡：相机都不要了"
                    + (key == null || move == null ? "，按次序关"
                    : "；手上 " + roleName(key) + " " + moveText(move) + "（" + seconds(ms) + "），最多再等 "
                    + (ChannelRules.SLEEP_GRACE_MS / 1000) + " 秒")
                    + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        }

        @Override
        public void exit(String key, ChannelRules.Move move, long ms) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：退出：登记表作废，"
                    + (key == null || move == null ? "按次序关"
                    : "手上的 " + roleName(key) + " " + moveText(move) + "（" + seconds(ms) + "）做完后按次序关"));
        }

        @Override
        public void asked(String key, String cameraId, int n, long heldMs, Boolean free, boolean absent,
                          boolean important) {
            String line = "问相机服务：" + cameraId + " " + (absent ? "还是不在"
                    : Boolean.TRUE.equals(free) ? "说空闲了" : "还被占着") + "（被拿走 " + duration(heldMs)
                    + "，第 " + n + " 次）";
            AppLog.i(TAG, line);
            if (important) {
                com.kooo.evcam.blackbox.BlackBox.note(line);
            }
        }

        // ---- 拼句子的小工具

        private String roles(List<String> keys) {
            List<String> names = new ArrayList<>();
            for (String key : keys) {
                names.add(roleName(key));
            }
            return String.join("、", names);
        }

        /** 登记表的记号（和 CameraNeeds.describe 一样）：PREVIEW+RECORDING。 */
        private String holderCodes(Set<CameraNeeds.Holder> holders) {
            List<String> codes = new ArrayList<>();
            for (CameraNeeds.Holder holder : holders) {
                codes.add(holder.name());
            }
            return codes.isEmpty() ? "(none)" : String.join("+", codes);
        }

        /** 谁要它，中文：预览、录像、拍照、后视镜。 */
        private String holderNames(Set<CameraNeeds.Holder> holders) {
            List<String> names = new ArrayList<>();
            for (CameraNeeds.Holder holder : holders) {
                switch (holder) {
                    case PREVIEW:
                        names.add("预览");
                        break;
                    case RECORDING:
                        names.add("录像");
                        break;
                    case PHOTO:
                        names.add("拍照");
                        break;
                    default:
                        names.add("后视镜");
                        break;
                }
            }
            return names.isEmpty() ? "保持" : String.join("、", names);
        }

        /** 输出的叫法：预览、后视镜、录像、出帧口。 */
        private String kindNames(Set<ChannelRules.Kind> kinds) {
            List<String> names = new ArrayList<>();
            for (ChannelRules.Kind kind : kinds) {
                switch (kind) {
                    case PREVIEW:
                        names.add("预览");
                        break;
                    case MIRROR:
                        names.add("后视镜");
                        break;
                    case RECORD:
                        names.add("录像");
                        break;
                    default:
                        names.add("出帧口");
                        break;
                }
            }
            return names.isEmpty() ? "（无）" : String.join("、", names);
        }

        private String closeWhyText(ChannelRules.Why why) {
            switch (why) {
                case EXIT:
                    return "退出";
                case SLEEP:
                    return "车机要睡";
                case SCREEN_OFF:
                    return "熄屏";
                case HOLD_OVER:
                    return "保持到点";
                default:
                    return "没人要";
            }
        }

        private String moveText(ChannelRules.Move move) {
            switch (move) {
                case OPEN:
                    return "开到一半";
                case APPLY:
                    return "改输出到一半";
                case RESCUE:
                    return "救到一半";
                case CLOSE:
                    return "正在关";
                default:
                    return "在途";
            }
        }

        private String pendingText(ChannelRules.Move pending, String key) {
            String name = roleName(key);
            if (pending == ChannelRules.Move.APPLY) {
                return "改 " + name + " 的输出";
            }
            if (pending == ChannelRules.Move.RESCUE) {
                return "救 " + name;
            }
            return "开 " + name;
        }

        /** 丢的那一下：被断开、报错 N。 */
        private String lossText(CameraTaken.Reason reason) {
            if (reason == null) {
                return "原因不明";
            }
            if (reason.source == CameraTaken.Reason.Source.DISCONNECTED) {
                return "被断开";
            }
            return "报错 " + reason.code + (reason.unusable() ? "，现在用不了相机" : "");
        }

        /** 开不起来的原因：被占用一类写名字（MAX_CAMERAS_IN_USE），设备错误写「报错 N」，用不了相机注明。 */
        private String openFailText(CameraTaken.Reason reason) {
            if (reason == null) {
                return "原因不明";
            }
            String text;
            switch (reason.source) {
                case DISCONNECTED:
                    text = "被断开";
                    break;
                case DEVICE_ERROR:
                    text = reason.inUseKind() ? reason.codeName() : "报错 " + reason.code;
                    break;
                default:
                    text = reason.codeName();
                    break;
            }
            return text + (reason.unusable() ? "，现在用不了相机" : "");
        }

        /** 那一句「空闲」比关完早多少：「（关完前 5 ms 报的）」。 */
        private String wordWhen(long beforeCloseMs) {
            if (beforeCloseMs == Long.MIN_VALUE) {
                return "";
            }
            return beforeCloseMs >= 0 ? "（关完前 " + beforeCloseMs + " ms 报的）" : "（关完后 " + (-beforeCloseMs) + " ms 报的）";
        }

        /** 「9 分 12 秒」；一分钟以内「3.4 秒」。 */
        private String duration(long ms) {
            if (ms < 60_000L) {
                return seconds(Math.max(0, ms));
            }
            long s = ms / 1000;
            return (s / 60) + " 分 " + (s % 60) + " 秒";
        }

        /** 「（这段时间里环视最长断帧不到 0.2 秒、会话没动）」。 */
        private String othersText(List<ChannelScheduler.Other> others) {
            if (others.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (ChannelScheduler.Other other : others) {
                sb.append(sb.length() > 0 ? "，" : "").append(roleName(other.key))
                        .append(other.gapMs < SingleCamera.NOTABLE_GAP_MS
                                ? "最长断帧不到 " + seconds(SingleCamera.NOTABLE_GAP_MS) : "最长断帧 " + seconds(other.gapMs))
                        .append(other.sessionChanged ? "、会话被重建过" : "、会话没动");
            }
            return "（这段时间里" + sb + "）";
        }
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
            if (camera != null && takenNow(key)) {
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
            if (takenNow(key)) {
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
        // 登记表的监听只在还是自己时才摘：换车型时旧的这份晚 release，不能把新那份接上的监听摘了
        CameraNeeds.current().clearListener(needsListener);
        com.kooo.evcam.screen.ScreenState.removeListener(screenWake);
        if (photoJob != null) {
            // 拍到一半管理器没了（退出）：这一张作罢，登记也撤掉
            photoJob = null;
            CameraNeeds.current().release(CameraNeeds.Holder.PHOTO);
        }
        AppLog.d(TAG, "Releasing MultiCameraManager resources");

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
            
            // 5. 相机交给调度：同步写下「退出」、置 closingAnywhere，登记表从此作废；手上那一步做完后按次序关
            //    （环视单独先关，座舱一起关），关完调度自己停。这里不等（退出的最后一步 finishExit 等 closingAnywhere）。
            //    录制器阶段 3 仍由上面那次停录在后台放（阶段 5 改成等调度关完相机、丢掉输出再放）
            try {
                scheduler.exit();
            } catch (Exception e) {
                AppLog.e(TAG, "Error handing the cameras to the scheduler during release", e);
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
     * <p>拍照在登记表上登记一项（{@link CameraNeeds.Holder#PHOTO}）：相机没开，由通道调度按次序开、带出帧口；
     * 已经开着的路什么都不改（JPEG 通道常驻会话）。等各路出了画面再按快门（最多等
     * {@link #PHOTO_READY_TIMEOUT_MS}，到点只拍出了画面的），存完注销 —— 拍完出帧口不摘，没人要了的路照「保持」的规矩关。
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
            // 没开的相机由通道调度按次序开、带出帧口（拍照只登记，不开相机、不改会话）
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
        // 调度判出被别的程序拿着（含被拿着的另一路挡着）、救了 3 轮停手的那几路这一张不拍、也不算在该拍的里面
        // （2026-10-10）：它们等到点也出不了画面 —— 以前每一张都白等满 10 秒，再报「有几路没出画面」。
        // 一路只要在出画面就算数，哪怕它的录像路不在（2.11：以前录像里不在录的那几路也不拍）
        List<String> leftOut = new ArrayList<>();
        for (java.util.Iterator<String> it = keys.iterator(); it.hasNext(); ) {
            String key = it.next();
            if (scheduler.excludedFromPhoto(key)) {
                leftOut.add(laneName(key) + (scheduler.condition(key) == ChannelRules.Condition.TAKEN
                        ? "被别的程序拿着" : "救了 " + CameraLiveness.MAX_CYCLES + " 轮没用、停手了"));
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
        // 拍完就注销：开着的路什么都不动、出帧口不摘（减输出能拖就拖）；没人要了的路由调度照「保持」的规矩关
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
