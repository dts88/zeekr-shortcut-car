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

    private final Context context;
    private final Map<String, SingleCamera> cameras = new LinkedHashMap<>();
    private final List<String> activeCameraKeys = new ArrayList<>();
    /**
     * 启用的那几路（配置里开着的，建管线时 Holder 给，{@link #setEnabledLanes}）；null 是建了的全部。
     * 环视一直建着：没启用时只为超级镜子、盲区画面开（登记表 §一，项目所有者 2026-10-16）。
     */
    private Set<String> enabledLanes;
    private int maxOpenCameras = DEFAULT_MAX_OPEN_CAMERAS;
    /** 放了（{@link #release}）。见 {@link #isReleased}。 */
    private volatile boolean released;
    /** 建好了（{@link #initCameras} 做完）。见 {@link #isBuilt}。 */
    private volatile boolean camerasBuilt;

    private boolean isRecording = false;
    /** 用软编码录制（编码录制器）；false = MediaRecorder（开发者选项里的非默认模式）。建管线时定一次。 */
    private boolean useCodecRecording = false;
    private boolean useRelayWrite = false;      // 是否使用中转写入（录制到内部存储，异步传输到U盘）
    private File finalSaveDir = null;           // 最终存储目录（用于中转写入模式）

    /**
     * 几路分段时共用的名字：出了一个名字之后这么久之内来切段的几路都拿它（数在 {@link SharedSegmentName#SHARE_MS}）。
     * 自动锁定算文件结束时也按它放宽（LockWindow.SLACK_MS）。
     */
    public static final long TIMESTAMP_CACHE_DURATION_MS = SharedSegmentName.SHARE_MS;
    /** 共用的分段名字和段号：几路分段时拿同一个名字；出一个新名字，界面上的段号才加 1（开新的录像意图时清零）。 */
    private final SharedSegmentName sharedName = new SharedSegmentName();

    private StatusCallback statusCallback;
    private PreviewSizeCallback previewSizeCallback;
    private android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // ---- 通道调度（2.11，项目所有者 2026-10-10 确认）----
    // 开、关、改输出、救、重开都只在 ChannelScheduler 一处：这里的有序开（一路出了画面再开下一路）、关一轮（环视单独先关，
    // 关完再关座舱）、看门狗、登记表的「没人要等 30 秒再关」都搬了进去。为什么环视要单独开、单独关，见 ChannelRules
    // closeStep 的说明（2026-10-09 黑匣子：环视比座舱晚关完的那几次，那一次关要 4–17 秒，下一次打开它一帧不出）。
    // 熄屏 v3（项目所有者 2026-10-12 D2）：屏幕一黑两路座舱就放开（一起关），环视照熄屏持续录制那张表录 ——
    // 座舱比环视早关几分钟，环视先关只对连环视一起关的那几次还成立（ChannelRules.releasedByDark）。
    // 2026-10-13 起「一黑」是黑满 30 秒（或者车机要睡）：不到 30 秒又亮，相机一样不动（ChannelRules.darkForChannel）。
    // 2026-10-27 起环视看「熄屏持续录制」、哨兵模式、在不在录：没留着的和座舱同一轮放开（ChannelRules.judgeSurroundInDark）。
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
    /**
     * 亮屏、熄屏：叫醒调度（熄屏时 PREVIEW 不算、不保持、座舱谁都不要、没留着的环视放开，由规则判 —— ChannelRules.releasedByDark；
     * 屏幕黑满 30 秒才算，那一刻调度自己醒，ChannelRules.darkForChannel）。
     * 熄屏那一下从头记这一段熄屏的座舱放开（{@link #newDarkPeriod}）。
     * 亮屏时等着的录像输出重新声明（{@link #liftRefusals}），和调度的「亮屏解除停手」是同一个亮屏：通道把这一段算作黑过
     * （{@link #channelCountedDark}）才算 —— 黑了不到 30 秒又亮，通道一步不动，录像输出也不动（项目所有者 2026-10-13）。
     */
    private final com.kooo.evcam.screen.ScreenState.Listener screenWake =
            new com.kooo.evcam.screen.ScreenState.Listener() {
                @Override
                public void onScreenOff() {
                    newDarkPeriod();
                    scheduler.wake();
                }

                @Override
                public void onScreenOn() {
                    // 亮屏那一轮还没跑（wake 只是排一轮）：快照还是这一段熄屏最后的样子
                    boolean countedDark = channelCountedDark();
                    // 这一段熄屏结束：之后亮着时关座舱（保持到点）不算熄屏放开；记下的那一刻留到下一次熄屏
                    awaitingCabinRelease = false;
                    scheduler.wake();
                    if (countedDark) {
                        liftRefusals("亮屏了", null);
                    }
                }
            };
    /**
     * 车辆信号变了：哨兵模式（车辆接口没连着算读不到）、在不在开车变了才叫醒调度 —— 环视熄屏时留不留看它们（项目所有者 2026-10-27，
     * ChannelRules.judgeSurroundInDark）。每个管线一个，release 时摘；Telemetry 在主线程上叫。
     */
    private final com.kooo.evcam.telemetry.Telemetry.Listener vehicleWake =
            new com.kooo.evcam.telemetry.Telemetry.Listener() {
                private boolean seen;
                private Integer sentry;
                private boolean driving;

                @Override
                public void onReadingsChanged(com.kooo.evcam.telemetry.Readings readings) {
                    Integer nowSentry = channelSide.sentry();
                    boolean nowDriving = channelSide.driving();
                    if (seen && java.util.Objects.equals(nowSentry, sentry) && nowDriving == driving) {
                        return;
                    }
                    seen = true;
                    sentry = nowSentry;
                    driving = nowDriving;
                    scheduler.wake();
                }
            };
    private PipelineCallback pipelineCallback;

    // ---- 熄屏放开座舱（项目所有者 2026-10-12 D2）：协调器写熄屏小结要的那一个数（设计 v3.1 C16）----
    /**
     * 这一段熄屏里座舱放开完的那一刻（elapsedRealtime，含深睡）：熄屏以后第一轮关了座舱的那一轮关完时；0 = 还没有。
     * 每次熄屏从头算（{@link #newDarkPeriod}）。主线程上写，哪个线程都能读。
     */
    private volatile long cabinsReleasedAt;
    /** 熄屏那一刻座舱（环视以外的几路）开着、在开、或者在关。 */
    private volatile boolean cabinsOpenAtDark;
    /** 在等这一段熄屏里第一轮关了座舱的那一轮关完（主线程）。 */
    private boolean awaitingCabinRelease;

    /**
     * 录像的代数：每次开录、每次停录都 +1（主线程上改）。录制器的回调、排着的回帖都带着开录时的那一代，
     * 对不上就作废、什么都不报 —— 开 → 停 → 开两秒内，上一次晚到的「写不进」「第一笔」不会打到这一次上。
     */
    private volatile int recordGeneration;
    /**
     * 停录的收拾一次只做一份（停一路编码器最长要几秒，不能在主线程上做）。空闲几秒线程就退，
     * 管线释放后再停也照样能用。
     */
    private final java.util.concurrent.ThreadPoolExecutor teardown = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 5, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
            runnable -> new Thread(runnable, "StopRecording"));

    // ---- 录像按路进出（channel-logic §三，项目所有者 2026-10-10 确认；2.11 阶段 5）----
    // 开录只打开「录像意图」、定下这次录哪几路、写到哪；每一路的录制器建好、声明录像输出，它的相机带着录像输出出了画面，
    // 这一路就加入 —— 第一次开录和中途接回是同一件事，文件按它真实开始的时刻起名，下一次分段时和别的路对齐。
    // 一路的相机走了（丢了、我们关了、卡住了在救）、它的录像输出离开了会话、或者写不进而是相机的事：这一路自己收尾（文件封好）
    // 退出，录制器停放（编码器放掉，录像输出还是同一个），别的路不动；回来了再加入。所有路都暂时录不了时录像不算停，显示等待摄像头。
    // 录像只声明、撤回录像输出，照调度的那几声（ChannelScheduler.Recording）进出，从不动相机。整次停只剩：人停、存储一类
    // （写不进、一笔没写进、管线被换、每一路的录制器都建不起来 —— 项目所有者 2026-10-11 §7.3：照旧自动恢复）、车机要睡、退出。
    // MediaRecorder 模式（开发者选项里的非默认模式，阶段 5d）走同一张表、同一套加入 / 退出，只是录制器不一样（VideoRecorder）：
    // 它不能停放（退出、停录就停掉放掉，它的 Surface 一放，会话里的那个录像输出就是死输出，调度马上摘），文件名在备好时就定了
    // （所以只在相机在出画面、或者正要开时才备），分段要换一个新的录像输出（新的声明进来、旧的不再声明，调度那一步改会话）。

    /** 这个录像意图里每一路的状态（主线程）：等着、在加入、在录。见 {@link RecordingLanes}。 */
    private final RecordingLanes lanes = new RecordingLanes();
    /**
     * 这个录像意图的计划：开录时每一路照当时的设置定下的录法（主线程）。建录制器只照它 ——
     * 录到一半改了视频流配置、车牌号、信息条，从下一次录像起生效。
     */
    private final Map<String, LanePlan> recordingPlan = new LinkedHashMap<>();
    /**
     * 这个录像意图里每一路录像的源尺寸（主线程）：第一次算出来就定下（{@link #sourceOf}），和录法一样 —— 录到一半改了
     * 录制分辨率，从下一次录像起生效，一路中途接回时不因此换录制器、文件不中途换尺寸。
     */
    private final Map<String, Size> recordSources = new HashMap<>();
    /**
     * 每一路的录制器（主线程）：在录的、在加入的、在停的、停放着的、备好了还没开过录的。停录之后也留着（停放，录像输出留在会话里），
     * 下一次开录计划和源尺寸都一样就沿用；它的录像输出离开了会话（被摘掉、随相机关了）才放。
     */
    private final Map<String, Slot> slots = new HashMap<>();
    /** 在 {@link #lanePrep} 上建着录制器的那几路，带着这一次建的号（主线程）：停录时清掉，建好回来对不上就放掉。 */
    private final Map<String, Integer> building = new HashMap<>();
    private int buildSerial;
    /**
     * 这个录像意图里录制器建不起来的那几路（主线程；MediaRecorder 还有开不了录的）：这个意图里不再录它们。这是录制器的事，
     * 不是相机的事 —— 计划里每一路都这样就整次停（{@link #checkAllUnrecordable}）。
     */
    private final Set<String> unrecordable = new HashSet<>();
    /**
     * 会话带不上录像输出的那几路（主线程）：相机层配不上会话，把这一路的录像输出丢了（{@link SingleCamera.Drop#CONFIG_FAILED}）。
     * 这是相机那一侧的事（倒车时原厂 360 刚放开环视、HAL 还在拆它的流，就会这样），不算录不了、不整次停：这一路退出，
     * 隔 {@link CameraLiveness#RETRY_GAP_MS} 再声明一个；连着 {@link CameraLiveness#MAX_ATTEMPTS} 次都带不上，等它的相机走了
     * （丢了、关了、在救）回来时再试 —— 和救援梯子一个节奏，不会一直重配会话。加入了（会话带上了）就清。
     */
    private final Map<String, Refusal> refusals = new HashMap<>();
    /** 换下来、交给调度等录像输出离开会话再放的录制器（主线程）：调度停不下来时到点直接放（{@link #releaseLeftovers}）。 */
    private final Set<Slot> retiring = new HashSet<>();
    /**
     * 这个录像意图写到哪（开录时定）。建录制器、重新备好时先用 {@link StorageHelper#lastRecordingDir()}：
     * 别的路换过盘就是新盘（{@link #laneDir}）。
     */
    private File recordDir;
    /** 这个录像意图里每一路进出了几次、上一次退出的时刻（主线程，黑匣子用）。 */
    private final Map<String, LaneTrip> trips = new HashMap<>();
    /** 这个录像意图此刻的样子（主线程）：协调器、录制键、通知、悬浮按钮读它（{@link #laneSummary}）。 */
    private RecordingLanes.Summary summary = RecordingLanes.Summary.NONE;
    /** 这个录像意图里从什么时候起一路都不在录（uptime，主线程）；0 = 有一路在录，或者还没等过摄像头。黑匣子那一行用。 */
    private long noneLiveSince;
    /** 这个录像意图已经报过「录起来了」（第一路加入时报一次）。 */
    private boolean startReported;
    private LaneListener laneListener;
    /**
     * 被放弃的写入线程手上还开着的文件（这个进程里所有录制器的）：中转写入整次停时扫缓存目录要跳过它们 ——
     * 半个文件转过去就坏了。写入线程回过神来收好的，留在缓存里，下一个进程停录时一起转存。
     */
    private static final Set<String> HELD_BY_STUCK_WRITERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 建录制器、重新备好、停放用的那一条线程：一次只做一个，按排进来的先后（建的时候环视先）。都是等录制器的编码线程做完一步
     * （正常几十到几百毫秒，最多 5 秒）。停放不放在收拾线程上：收拾线程只收文件、放录制器 —— 一次停放卡住，
     * 排在后面的停录收文件不跟着等。空闲几秒线程就退，管线放了也照样能用。
     */
    private final java.util.concurrent.ThreadPoolExecutor lanePrep = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 5, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
            runnable -> new Thread(runnable, "LanePrep"));
    /**
     * 录像宿主的 Handler（同一个主线程 Looper）：带着录制器回来的几件事（建好了、重新备好了、停放好了、放掉的期限）排在这里。
     * 不用 mainHandler：release() 开头会清掉它，排在那里的回帖一清，录制器就漏放了（v2 集成检查记过的风险）。
     */
    private final android.os.Handler laneHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    /** 管线放了（{@link #release}）：录制器停放好就交出去放掉，不再沿用。 */
    private boolean releasing;
    /** 放掉的期限到了（调度停不下来）：之后换下来的录制器直接放，不再交给调度。 */
    private boolean leftoversDue;
    /**
     * MediaRecorder 模式每一路一个录制器（主线程）：第一次要备时建，跟着管线走，管线放了时放掉；停不下来的那个换掉
     * （{@link #mediaStopped}）。一个录制器一次只有一趟：上一趟停完、从 {@link #mediaSlots} 拿掉，才备下一趟。
     */
    private final Map<String, VideoRecorder> videoRecorders = new HashMap<>();
    /** MediaRecorder 模式每一路此刻的那一趟（主线程）：在备、备好了、在开录、在录、在停。停完才拿掉。 */
    private final Map<String, MediaSlot> mediaSlots = new HashMap<>();

    /** 这一次录像里一路的录法：开录时照当时的设置定下（{@link #recordingPlan}），建录制器照它。 */
    private static final class LanePlan {
        final StreamSpec spec;
        /** 环视录像下面加一条行驶信息条（只放环视）。 */
        final boolean infoBar;
        /** 左上角那行字：应用名 + 版本号（+ 车牌号）。 */
        final String brandLine;
        final boolean forceH264;
        final boolean watermark;
        final boolean watermarkSpec;
        /** 带声音的话用哪个来源（只给环视，{@link #footageAudioSource}）；null = 不带。 */
        final String audio;

        LanePlan(StreamSpec spec, boolean infoBar, String brandLine, boolean forceH264,
                 boolean watermark, boolean watermarkSpec, String audio) {
            this.spec = spec;
            this.infoBar = infoBar;
            this.brandLine = brandLine;
            this.forceH264 = forceH264;
            this.watermark = watermark;
            this.watermarkSpec = watermarkSpec;
            this.audio = audio;
        }

        /** 和另一份是不是同一种录法：一样的话停放着的录制器能沿用。 */
        boolean sameAs(LanePlan other) {
            return other != null && spec.toMap().equals(other.spec.toMap()) && infoBar == other.infoBar
                    && brandLine.equals(other.brandLine) && forceH264 == other.forceH264
                    && watermark == other.watermark && watermarkSpec == other.watermarkSpec
                    && java.util.Objects.equals(audio, other.audio);
        }
    }

    /** 一个录制器在哪一步（只在主线程改；录制器自己那一份走法在 {@link RecorderRun}）。 */
    private enum SlotPhase {
        /** 备好了，还没开过录（新建的）：加入时直接开录。 */
        PREPARED,
        /** 停放着：编码器放掉了，录像输出还活着。加入时先重新备好。 */
        PARKED,
        /** 在 {@link #lanePrep} 上重新备好。 */
        REARMING,
        /** 开着录（在加入、在录）。 */
        RUNNING,
        /** 在收拾线程上停、收文件、停放。 */
        STOPPING
    }

    /** 一个录制器这一次开录的身份（录制器线程上报回调时读，回主线程对一下）：哪一路、哪个录像意图、这一路第几次加入。 */
    private static final class RunTag {
        final String key;
        /** 开录时的录像代数；-1 = 还没开过录，它报的都不算数。 */
        volatile int gen = -1;
        /** 开录时这一路的计数（{@link RecordingLanes#token}）。 */
        volatile int token;

        RunTag(String key) {
            this.key = key;
        }
    }

    /** 一路的录制器和它的录像输出（主线程）。 */
    private static final class Slot {
        final String key;
        final SingleCamera camera;
        final CodecVideoRecorder recorder;
        final RunTag tag;
        final LanePlan plan;
        /** 录制器按这个源尺寸建的（{@link #sourceOf}）。 */
        final Size source;
        /** 录像输出的身份：录制器的输入 SurfaceTexture（停放、重新备好都是同一个）。 */
        final android.graphics.SurfaceTexture texture;
        /** 相机写进去的 Surface（录制器的，一辈子不变；归录制器放）。 */
        final android.view.Surface surface;
        final ChannelRules.Out out;
        SlotPhase phase = SlotPhase.PREPARED;
        /** 停放好的时刻（uptime）；0 = 没停放过。 */
        long parkedAt;
        /** 黑匣子写过「沿用停放着的那个」的那个录像代数。 */
        int reuseNotedGen = -1;
        /**
         * 这一路写不进、判成了盘或编码器的事（整次停，{@link #onLaneWriteStalled}）：停放好就换下来（这里写换下来的原因），
         * 接回时另建一个；null = 没事。毛病可能就在这个录制器自己（EGL 每帧出错、编码器怎么重建都不成），沿用的话每次接回都重演，
         * 三次之后额度用完、录像彻底停（审查 2026-10-11）。写入线程被放弃过的（盘的事）本来就重新备不好、也要换，换得早一点而已。
         */
        String suspectWhy;
        /** 不用了（换下来、录像输出被丢了、管线放了）：撤回了声明，手上那一步做完就交出去放掉。 */
        boolean retired;
        String retiredWhy = "";
        /** 交给调度等它离开会话了。 */
        boolean handedOver;
        /** 放掉了（只放一次）。 */
        boolean released;

        Slot(String key, SingleCamera camera, CodecVideoRecorder recorder, RunTag tag, LanePlan plan, Size source,
             android.graphics.SurfaceTexture texture, android.view.Surface surface) {
            this.key = key;
            this.camera = camera;
            this.recorder = recorder;
            this.tag = tag;
            this.plan = plan;
            this.source = source;
            this.texture = texture;
            this.surface = surface;
            this.out = new ChannelRules.Out(ChannelRules.Kind.RECORD, texture);
        }

        /** 手上没事：能加入、能交出去放掉。 */
        boolean idle() {
            return phase == SlotPhase.PREPARED || phase == SlotPhase.PARKED;
        }
    }

    /** MediaRecorder 模式一趟走到哪一步（主线程）。 */
    private enum MediaPhase {
        /** 在 {@link #lanePrep} 上备（配 MediaRecorder、prepare，第一段的文件按此刻起名）。 */
        PREPARING,
        /** 备好了，声明了它的录像输出，等相机带着它出画面再开录。 */
        READY,
        /** 在 {@link #lanePrep} 上开录。 */
        STARTING,
        /** 在录（含换段：新的一段声明进来、等调度带进会话）。 */
        RUNNING,
        /** 在收拾线程上停、收文件。 */
        STOPPING
    }

    /**
     * MediaRecorder 模式这一路的一趟（主线程）：从备好到停完。录像输出的身份就是 MediaRecorder 给的 Surface，
     * 每换一段换一个（{@link #mediaNextSegment}）。
     */
    private static final class MediaSlot {
        final String key;
        final SingleCamera camera;
        final VideoRecorder recorder;
        final RunTag tag;
        MediaPhase phase = MediaPhase.PREPARING;
        /** 声明着的录像输出：这一段的 Surface（备好了才有）。 */
        android.view.Surface surface;
        ChannelRules.Out out;
        /** 第一段的文件（备好那一刻起的名）。 */
        String file;
        /** 备好的时刻（uptime）。 */
        long preparedAt;
        /** 换段：新的一段声明了，等会话带着它出了画面就开（{@link VideoRecorder#segmentCarried}）。 */
        boolean nextPending;
        /** 这一趟不用了（停录、管线放了）：手上那一步（在备、在开录）做完就停。 */
        boolean retired;
        /**
         * 中转写入时，这一趟作废时还在备、在开录（叫停时不在录）：它做完那一步自己停的时候把文件转存到这里；null = 不转存。
         * 整次停扫缓存时跳过它的文件 —— 它那时可能还开着。
         */
        File relayOnStop;

        MediaSlot(String key, SingleCamera camera, VideoRecorder recorder) {
            this.key = key;
            this.camera = camera;
            this.recorder = recorder;
            this.tag = new RunTag(key);
        }
    }

    /** 一路的录像输出连着被会话带不上的账（主线程，{@link #refusals}）。 */
    private static final class Refusal {
        /** 连着几次。 */
        int count;
        /** 这个时刻（uptime）之前不再声明。 */
        long retryAt;
    }

    /** 这个录像意图里一路进出的账（主线程，黑匣子用）。 */
    private static final class LaneTrip {
        int joins;
        int leaves;
        /** 上一次退出的时刻（uptime）；0 = 这个意图里还没退出过。 */
        long leftAt;
        /** 这一次加入开录的时刻（uptime）。 */
        long startedAt;
    }

    /** 一路退出的这一段（主线程）：量别的路用的起点，录制器停放好时写黑匣子那一行。 */
    private static final class LaneChange {
        /** 这一段从什么时候起（uptime）：别的路最长断帧从这里量。 */
        final long startedAt = android.os.SystemClock.uptimeMillis();
        /** 那一刻别的在录的几路的会话代数：这一段结束时对一下，变了就是它们的会话被动过。 */
        final Map<String, Integer> generations;
        /** 为什么退出。 */
        final String why;

        LaneChange(Map<String, Integer> generations, String why) {
            this.generations = generations;
            this.why = why;
        }
    }

    /** 录像的样子变了（主线程）：一路加入、退出、相机被拿着 / 放开、开录落定。RecordingCoordinator 照 {@link #laneSummary()} 重画。 */
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
     * 开录、停录走到哪一步了，报给 RecordingCoordinator。「在不在录」只有它说了算（RecordingLifecycle），
     * 这里只报事实；过时的任务（代数对不上）不报。可能在任何线程上调。
     */
    public interface PipelineCallback {
        /**
         * 录起来了：这个录像意图里第一路加入（开了录），一个意图只报一次。两种录制器一样。
         *
         * @param active 启动了的几路
         * @param failed 没起来的几路
         */
        void onPipelineStarted(Set<String> active, Set<String> failed);

        /**
         * 录不起来：计划里每一路的录制器都建不起来（MediaRecorder 还算上开录没成）——
         * 这不是相机的事。这里不收拾 —— 协调器走停录那条路，由 {@link #stopRecording()} 按同一套收拾。
         */
        void onPipelineStartFailed(String why);

        /**
         * 停录收拾完了：文件收好了。编码录制的录制器接着停放（编码器放掉，录像输出留在会话里，等本来要动或关相机时一起摘）；
         * MediaRecorder 的停了就放掉（录像输出成了死输出，调度马上摘）。
         */
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
        // 录像那一侧：每一路的相机走了、样子变了、带着新会话出了画面，调度一轮最后告诉录像宿主（它只声明录像输出、照这几声进出）
        scheduler.setRecording(new LaneSide());
        // 相机服务每说一句、问的结果回来、注册回放收全：叫醒调度；分类时问它「这一路有没有我们的开或关在途」
        CameraAvailabilityWatch.addListener(channelSide);
        // 登记表一变就叫醒调度（只认这一份：换车型时旧的那份晚 release，摘不掉这份的监听）。会话连着几次带不上录像输出、
        // 等着的那一路，在要它的登记方变了时由调度告诉（LaneSide.onLaneWantersChanged，和救援停手一样解锁）。监听可能在别的线程上叫
        needsListener = scheduler::onNeedsChanged;
        CameraNeeds.current().setListener(needsListener);
        // 亮屏、熄屏叫醒调度（ScreenState 在主线程上叫）
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            com.kooo.evcam.screen.ScreenState.addListener(screenWake);
        } else {
            mainHandler.post(() -> com.kooo.evcam.screen.ScreenState.addListener(screenWake));
        }
        // 哨兵模式、在不在开车变了叫醒调度（环视熄屏时留不留，2026-10-27；哪个线程上登记都行，回调在主线程上）
        com.kooo.evcam.telemetry.Telemetry.get().addListener(vehicleWake);
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

    /** 黑匣子里的秒数，一位小数：「3.4 秒」。 */
    private static String seconds(long ms) {
        return String.format(java.util.Locale.US, "%.1f 秒", ms / 1000f);
    }

    /** 「9 分 12 秒」；一分钟以内「3.4 秒」。 */
    private static String duration(long ms) {
        if (ms < 60_000L) {
            return seconds(Math.max(0, ms));
        }
        long s = ms / 1000;
        return (s / 60) + " 分 " + (s % 60) + " 秒";
    }

    /** 整秒的时长：「31 秒」「12 分 3 秒」「2 小时 5 分」—— 留了多久、开了多久、多久以前（关相机那两行、诊断报告）。 */
    private static String timeText(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 60) {
            return s + " 秒";
        }
        if (s < 3600) {
            return (s / 60) + " 分 " + (s % 60) + " 秒";
        }
        return (s / 3600) + " 小时 " + (s % 3600 / 60) + " 分";
    }

    /** 几路的叫法连起来：「环视、后座舱」。 */
    private static String roles(List<String> keys) {
        List<String> names = new ArrayList<>();
        for (String key : keys) {
            names.add(roleName(key));
        }
        return String.join("、", names);
    }

    /**
     * 几路分段时的共用名字（编码录制、MediaRecorder 都从这里拿）：{@link SharedSegmentName#SHARE_MS} 之内来切段的几路拿同一个，
     * 回放才对得上同一段。出了一个新名字（段号加 1）：中途加入、名字还错开着的那几路马上跟着切（{@link #alignJoinedLanes}），
     * 界面上的段号跟着变 —— 段号只在出新名字时加 1，一路加入、退出不让它卡住或者跳。在录制器的线程上调。
     */
    private final VideoRecorder.SegmentTimestampProvider segmentTimestampProvider = () -> {
        SharedSegmentName.Name name = sharedName.next(android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis());
        if (name.fresh) {
            AppLog.d(TAG, "New shared segment name " + name.text + " (segment " + name.count + ")");
            final int count = name.count;
            mainHandler.post(() -> onSharedNameMinted(count));
        } else {
            AppLog.d(TAG, "Using shared segment name " + name.text);
        }
        return name.text;
    };

    /** 出了一个新的共用名字（主线程）：错开着的那几路跟着切，告诉界面段号。 */
    private void onSharedNameMinted(int count) {
        alignJoinedLanes();
        if (segmentSwitchCallback != null) {
            segmentSwitchCallback.onSegmentSwitch(count);
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
     * 写不进文件：裁判在录制器里（{@link CodecVideoRecorder} 的 15 秒规则），是盘或编码器的事才转给协调器（整次停）。
     * 和「每一路的录制器都建不起来」共用一个「已经报过」的标记：一次录像只报一次打断。
     */
    private WriteStallCallback writeStallCallback;
    private boolean interruptReported;

    /**
     * 这次录像实际写到哪个盘，进黑匣子。
     *
     * <p>设定的盘不在时，路径选择会悄悄改用别的盘（2026-09-26 21:55 那次，1D8C 掉线后自动接回，
     * 就写到了 B905 上）—— 不记下来的话，事后连「录像在哪」都要猜。</p>
     *
     * <p>目录是开录前查盘那一次在存储线程上找好的（{@link #openRecordingIntent}），这里不再找一遍：
     * 主线程上找目录要碰 U 盘，盘掉线时会卡住（审查 2026-10-11）。</p>
     */
    private void noteRecordingDir(File dir) {
        try {
            StorageHelper.noteRecordingDir(dir);
            String[] off = StorageHelper.offTarget(context, dir);
            StorageHelper.noteRecordingFallback(off);
            com.kooo.evcam.blackbox.BlackBox.noteImportant("录像写到 " + dir.getAbsolutePath()
                    + (off != null ? "（设定的是 " + (off[1] != null ? off[1] : "U 盘") + "，那个盘此刻不可用）" : "")
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

    /** 录像的样子变了时告诉谁（RecordingCoordinator，它转给录制键、通知、悬浮按钮、主界面）。 */
    public void setLaneListener(LaneListener listener) {
        this.laneListener = listener;
    }

    // ------------------------------------------------------------------ 录像宿主：每一路各自进出（2.11 阶段 5）

    /**
     * 这个录像意图此刻的样子（主线程）：在录 / 准备中 / 等待摄像头、计划、在录的几路、开录落定没有、状态条那一句
     * （{@link RecordingLanes#summarize}）。没在录是 {@link RecordingLanes.Summary#NONE}。变了会告诉 {@link LaneListener}。
     */
    public RecordingLanes.Summary laneSummary() {
        return summary;
    }

    private void notifyLanes() {
        if (laneListener != null) {
            laneListener.onLanesChanged();
        }
    }

    /**
     * 照此刻重算这个录像意图的样子（主线程）：变了就告诉协调器（{@link LaneListener}），样子变了的那几行黑匣子也在这里写
     * （{@link #noteViewChanged}：只有这一处 —— 各路此刻怎样只有这里知道）。两种录制器一样。
     */
    private void publishLanes() {
        RecordingLanes.Summary before = summary;
        List<RecordingLanes.Facts> facts = isRecording ? laneFacts() : null;
        RecordingLanes.Summary now = facts == null ? RecordingLanes.Summary.NONE : lanes.summarize(facts);
        summary = now;
        if (facts != null) {
            noteViewChanged(before, now, facts);
        }
        if (!now.sameAs(before)) {
            notifyLanes();
        }
    }

    /**
     * 录像的样子变了的那几行黑匣子（主线程）：开录落定、一路都不在录了、又有一路在录了、各路都停手了。各路为什么没在录
     * 照 {@link #laneWhy} 写 —— 一整夜都在等摄像头的话，黑匣子里要看得出是从什么时候、为什么。
     */
    private void noteViewChanged(RecordingLanes.Summary before, RecordingLanes.Summary now,
                                 List<RecordingLanes.Facts> facts) {
        long at = android.os.SystemClock.uptimeMillis();
        if (!before.settled && now.settled) {
            StringBuilder waiting = new StringBuilder();
            for (RecordingLanes.Facts lane : facts) {
                if (lane.state != RecordingLanes.State.LIVE) {
                    waiting.append(waiting.length() > 0 ? "，" : "").append(roleName(lane.key)).append(" 等着（")
                            .append(laneWhy(lane)).append("）");
                }
            }
            com.kooo.evcam.blackbox.BlackBox.noteImportant("开录落定：" + now.liveKeys.size() + "/" + now.planned.size()
                    + " 路在录" + (now.liveKeys.isEmpty() ? "" : "（" + roles(now.liveKeys) + "）")
                    + (waiting.length() == 0 ? "" : "；" + waiting));
        }
        if (now.liveKeys.isEmpty()) {
            if (!before.liveKeys.isEmpty()) {
                // 每一路都是熄屏放开的（通道刚算黑、哨兵关了、车机要睡那一轮，环视没留着）：不是等摄像头 ——
                // 录像协调器照通道的决定随后停（ChannelScheduler.surroundVerdict）
                boolean allReleased = !facts.isEmpty();
                for (RecordingLanes.Facts lane : facts) {
                    allReleased &= lane.releasedByDark;
                }
                com.kooo.evcam.blackbox.BlackBox.noteImportant((allReleased
                        ? "录像：熄屏放开了在录的几路（通道定的），录像随后停（录像协调器）"
                        : "录像：一路都不在录了 —— 等待摄像头，恢复后自动继续（录像不算停）")
                        + "；各路：" + lanesText(facts));
            }
            if (now.view == RecordingLanes.View.WAITING && noneLiveSince == 0) {
                noneLiveSince = at;
            }
        } else {
            if (noneLiveSince > 0) {
                com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：又有一路在录了（" + roles(now.liveKeys)
                        + "），一路都不在录持续了 " + duration(at - noneLiveSince));
            }
            noneLiveSince = 0;
        }
        if (now.hint.kind == RecordingLanes.Hint.Kind.ALL_GAVE_UP
                && before.hint.kind != RecordingLanes.Hint.Kind.ALL_GAVE_UP) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：各路都停手了 —— 不再按节奏自己重试，等登记表变了、"
                    + "相机服务报它空闲、或者亮屏再试（录像不算停）；各路：" + lanesText(facts));
        }
    }

    /** 黑匣子：各路此刻怎样，「环视 被占用，后座舱 在救，前座舱 停手了」。 */
    private String lanesText(List<RecordingLanes.Facts> facts) {
        StringBuilder sb = new StringBuilder();
        for (RecordingLanes.Facts lane : facts) {
            sb.append(sb.length() > 0 ? "，" : "").append(roleName(lane.key)).append(" ").append(laneWhy(lane));
        }
        return sb.toString();
    }

    /** 黑匣子：一路此刻在不在录、为什么没在录（录像的样子那几行）。 */
    private String laneWhy(RecordingLanes.Facts lane) {
        if (lane.state == RecordingLanes.State.LIVE) {
            return "在录";
        }
        if (lane.state == RecordingLanes.State.STARTING) {
            return "在加入";
        }
        // 熄屏放开的座舱先说这一句（设计 v3.1 C4）：车机熄屏约 98 秒后拿走后座舱，它也是「被占用」，可没在录是因为熄屏。
        // 放开的环视写「熄屏：环视放开」（2026-10-27）
        if (lane.releasedByDark) {
            return channelSide.darkText(lane.key);
        }
        if (unrecordable.contains(lane.key)) {
            return useCodecRecording ? "录制器建不起来" : "MediaRecorder 备不起来、或者开不了录";
        }
        if (refusedUntilGone(lane.key)) {
            return "会话连着 " + CameraLiveness.MAX_ATTEMPTS + " 次带不上录像输出，等相机走了回来、登记表变了或者亮屏再试";
        }
        // 一次关满 60 秒还没返回（项目所有者 2026-10-28）：被拿着、在救也是它在先 —— 返回之前谁都不开它
        if (lane.heldByClose) {
            return "上一次关还没返回，返回之前不开它";
        }
        switch (lane.condition) {
            case TAKEN:
                return "被占用";
            case RESCUING:
                return "在救";
            case GAVE_UP:
                return "停手了";
            case STREAMING:
                return refused(lane.key) ? "在出画面，会话刚带不上录像输出，等一会儿再试" : "在出画面，录制器在备";
            default:
                return lane.cannotOpen ? "现在用不了相机，等主界面回到前台" : "在开";
        }
    }

    /**
     * 这个录像意图里每一路此刻的事实，按开的次序：录像表上的状态、调度说的相机怎样（能不能开、是不是在等一次满 60 秒还没返回的关、
     * 是不是被熄屏放开了）、录制器怎样。
     */
    private List<RecordingLanes.Facts> laneFacts() {
        List<RecordingLanes.Facts> facts = new ArrayList<>();
        for (String key : lanes.keys()) {
            facts.add(new RecordingLanes.Facts(key, lanes.state(key), scheduler.condition(key),
                    scheduler.cannotOpen(key), scheduler.heldByClose(key), scheduler.releasedByDark(key),
                    recorderOf(key)));
        }
        return facts;
    }

    /** 这一路的录制器此刻怎样（主线程）。 */
    private RecordingLanes.Recorder recorderOf(String key) {
        if (unrecordable.contains(key) || refusedUntilGone(key)) {
            return RecordingLanes.Recorder.FAILED;
        }
        if (!useCodecRecording) {
            MediaSlot media = mediaSlots.get(key);
            if (media == null) {
                return RecordingLanes.Recorder.NONE;
            }
            return media.phase == MediaPhase.PREPARING ? RecordingLanes.Recorder.BUILDING
                    : media.phase == MediaPhase.READY ? RecordingLanes.Recorder.READY : RecordingLanes.Recorder.BUSY;
        }
        Slot slot = slots.get(key);
        if (slot != null) {
            return slot.idle() ? RecordingLanes.Recorder.READY : RecordingLanes.Recorder.BUSY;
        }
        return building.containsKey(key) ? RecordingLanes.Recorder.BUILDING : RecordingLanes.Recorder.NONE;
    }

    /**
     * 这一路的录像输出在不在备（主线程，调度每一轮读）：录制器在建、或者画面尺寸还没算出来等着建 —— 在备，调度开它之前
     * 最多等 {@link ChannelRules#RECORD_OUTPUT_WAIT_MS}；录制器建不起来、会话刚带不上它（眼下不声明）—— 建不起来，不等。
     * 备好了就是声明里有它的录像输出（调度看声明），不在录像计划里的是没在备。
     */
    private ChannelRules.RecordPrep recordPrepOf(String key) {
        if (!isRecording || !recordingPlan.containsKey(key)) {
            return ChannelRules.RecordPrep.NONE;
        }
        if (unrecordable.contains(key) || refused(key)) {
            return ChannelRules.RecordPrep.FAILED;
        }
        if (!useCodecRecording) {
            // MediaRecorder：在备的那一下（相机被拿着、在救时不备，那时不是「在备」）
            MediaSlot media = mediaSlots.get(key);
            return media != null && media.phase == MediaPhase.PREPARING
                    ? ChannelRules.RecordPrep.PREPARING : ChannelRules.RecordPrep.NONE;
        }
        return slots.containsKey(key) ? ChannelRules.RecordPrep.NONE : ChannelRules.RecordPrep.PREPARING;
    }

    /** 调度告诉录像宿主的那几声（主线程，每一轮最后）：一路加入、退出只照它们，从不自己去动相机。 */
    private final class LaneSide implements ChannelScheduler.Recording {
        @Override
        public void onLaneCameraStreaming(String key) {
            if (useCodecRecording) {
                tryJoin(key);
            } else {
                mediaStreaming(key);
            }
            publishLanes();
        }

        @Override
        public void onLaneCameraGone(String key, ChannelScheduler.Gone why, CameraTaken.Reason reason,
                                     ChannelRules.Why closed) {
            // 熄屏放开的座舱：退出录像那一行写「熄屏：座舱放开」（设计 v3 §4）；熄屏放开的环视写「熄屏：环视放开」（2026-10-27）；
            // 关座舱前先关环视（2.11.6）写「关座舱前先关环视」—— 录制器停放，环视开回来带着同一个录像输出出了画面再加入；
            // 别的我们关的照旧是「相机关了」
            leaveLane(key, why == ChannelScheduler.Gone.LOST ? channelSide.lossText(reason)
                    : why == ChannelScheduler.Gone.RESCUED ? "相机卡住了，调度在救"
                    : closed == ChannelRules.Why.DARK_RELEASE || closed == ChannelRules.Why.SURROUND_FIRST
                    ? channelSide.closeWhyText(closed)
                    : closed == ChannelRules.Why.SCREEN_OFF && scheduler.releasedByDark(key) ? channelSide.darkText(key)
                    : "相机关了");
            // 会话带不上它的录像输出的账随这一路的相机走了就清：回来时是一份新会话，照常声明
            if (refusals.remove(key) != null) {
                ensureLane(key);
            }
            publishLanes();
        }

        /**
         * 这一路的样子变了：MediaRecorder 看备好的那一趟还留不留（{@link #mediaConditionChanged}）；编码录制缺了录制器的
         * 现在补（{@link #ensureRecorder}）—— 熄屏放开的座舱熄屏时不为它建，亮屏它不再被放开的那一下在这里建
         * （设计 v3.1 C4），调度开它之前最多等它 2 秒（ChannelRules.RECORD_OUTPUT_WAIT_MS）。
         */
        @Override
        public void onLaneConditionChanged(String key) {
            if (useCodecRecording) {
                ensureRecorder(key);
            } else {
                mediaConditionChanged(key);
            }
            publishLanes();
        }

        /** 这一路「谁要它」变了：会话连着几次带不上录像输出、在等的话，这时再试（和救援停手一样解锁）。 */
        @Override
        public void onLaneWantersChanged(String key) {
            liftRefusals("登记表变了", java.util.Collections.singleton(key));
        }
    }

    /**
     * 这一路这个意图要用的录制器（主线程）：停放着的、录法和源尺寸都一样就沿用（会话不动）；不一样就换下来
     * （{@link #retire}）、另建一个（{@link #build}）。手上有事的（在加入、在录、在停）等它做完再看（{@link #slotIdle}）；
     * 画面尺寸还没算出来（它不在相机列表里）的等它回来算出来（{@link #onSizeResolved}）—— 不再要求「本进程开过这一路」，
     * 也不用 1280×800 兜底。有了能用的，就看能不能加入（{@link #tryJoin}）。
     *
     * <p>熄屏放开的座舱（{@link ChannelScheduler#releasedByDark}；2026-10-27 起还有没留着的环视）不建、不换：
     * 熄屏时它不会开，建好的录制器（编码器、EGL、输入画面）白白占一整夜（设计 v3.1 C4）。停放着的照旧停放着（调度留着它的录像输出）；亮屏它不再被放开时调度告诉一声
     * （{@link LaneSide#onLaneConditionChanged}），那时再看。</p>
     */
    private void ensureRecorder(String key) {
        if (!isRecording || !useCodecRecording || !recordingPlan.containsKey(key) || unrecordable.contains(key)
                || refused(key) || building.containsKey(key) || scheduler.releasedByDark(key)) {
            return;
        }
        SingleCamera camera = cameras.get(key);
        Slot slot = slots.get(key);
        if (camera == null || (slot != null && !slot.idle())) {
            return;
        }
        LanePlan plan = recordingPlan.get(key);
        Size size = camera.resolvedSize();
        Size source = size == null ? null : sourceOf(key, size);
        if (slot != null) {
            if (slot.plan.sameAs(plan) && (source == null || slot.source.equals(source))) {
                if (slot.phase == SlotPhase.PARKED && slot.reuseNotedGen != recordGeneration) {
                    slot.reuseNotedGen = recordGeneration;
                    com.kooo.evcam.blackbox.BlackBox.note("录像：相机 " + laneName(key) + " 录制器沿用停放着的那个（停放了 "
                            + duration(android.os.SystemClock.uptimeMillis() - slot.parkedAt) + "），会话不动");
                }
                tryJoin(key);
                return;
            }
            retire(slot, "换下来了（录法或画面尺寸变了）");
        }
        if (size == null) {
            AppLog.i(TAG, "Recording lane " + key + " waits for its picture size (camera not listed yet)");
            return;
        }
        build(key, camera, plan, size, source);
    }

    /**
     * 这个录像意图里这一路录像的源尺寸（主线程）：配置里给录制流单独定了就用它，否则就是画面尺寸（录制是一条独立的相机输出流）。
     * 第一次算出来就定下，这个意图里不再照设置重算（{@link #recordSources}）。
     */
    private Size sourceOf(String key, Size size) {
        Size source = recordSources.get(key);
        if (source == null) {
            String role = com.kooo.evcam.profile.ProfileSizes.roleForCameraKey(key);
            Size record = role == null ? null : com.kooo.evcam.profile.ProfileSizes.record(context, role, size);
            source = record != null ? record : size;
            recordSources.put(key, source);
        }
        return source;
    }

    /** 录制器写到哪：这一次录像此刻实际写进的那个目录（别的路换过盘就是新盘）；还没有就是开录时定的。 */
    private File laneDir() {
        File actual = StorageHelper.lastRecordingDir();
        return actual != null ? actual : recordDir;
    }

    /**
     * 在 {@link #lanePrep} 上建这一路的录制器（不开文件：第一个文件等加入时按那一刻起名），建好回主线程声明录像输出
     * （{@link #recorderBuilt}）。
     */
    private void build(String key, SingleCamera camera, LanePlan plan, Size size, Size source) {
        final int ticket = ++buildSerial;
        building.put(key, ticket);
        final File dir = laneDir();
        final RunTag tag = new RunTag(key);
        final long startedAt = android.os.SystemClock.uptimeMillis();
        lanePrep.execute(() -> {
            CodecVideoRecorder recorder = null;
            android.graphics.SurfaceTexture texture = null;
            android.view.Surface surface = null;
            String failure = null;
            try {
                recorder = newCodecRecorder(key, camera.getCameraId(), plan, size, source);
                recorder.setCallback(laneCallback(tag));
                texture = recorder.prepareWithoutFile(dir, key);
                surface = texture == null ? null : recorder.getRecordSurface();
                if (surface == null) {
                    failure = "编码器、EGL 或输入画面没备好";
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Building the codec recorder of " + key + " failed", e);
                failure = String.valueOf(e);
            }
            final CodecVideoRecorder built = recorder;
            final android.graphics.SurfaceTexture builtTexture = texture;
            final android.view.Surface builtSurface = surface;
            final String why = failure;
            final long ms = android.os.SystemClock.uptimeMillis() - startedAt;
            laneHandler.post(() -> recorderBuilt(key, ticket, camera, plan, source, tag, built, builtTexture,
                    builtSurface, why, ms));
        });
    }

    /**
     * 一路的录制器建好了（主线程）：声明录像输出、叫醒调度 —— 相机关着的，调度开的时候就带着它；开着的，调度排一步
     * 「改输出」加上它。建不起来的，这个意图不录这一路；计划里每一路都建不起来，整次停（START_FAILED）。
     * 停过录、换过计划、管线放了（建的号对不上），这一个用不上，放掉。
     */
    private void recorderBuilt(String key, int ticket, SingleCamera camera, LanePlan plan, Size source, RunTag tag,
                               CodecVideoRecorder recorder, android.graphics.SurfaceTexture texture,
                               android.view.Surface surface, String failure, long ms) {
        Integer expected = building.get(key);
        if (expected == null || expected != ticket || cameras.get(key) != camera) {
            // 停过录、管线放了（停录清掉了在建的号），或者这一路的相机换了一个：这个录制器没声明过输出，直接放
            if (recorder != null) {
                releaseLater(recorder);
            }
            return;
        }
        building.remove(key);
        if (failure != null) {
            if (recorder != null) {
                releaseLater(recorder);
            }
            unrecordable.add(key);
            noteRecorderFailed(laneName(key), failure, ms);
            checkAllUnrecordable();
            scheduler.wake();
            publishLanes();
            return;
        }
        Slot slot = new Slot(key, camera, recorder, tag, plan, source, texture, surface);
        slot.reuseNotedGen = recordGeneration;
        slots.put(key, slot);
        camera.declareRecord(texture, surface, why -> onRecordOutputDropped(slot, why));
        noteRecorderBuilt(laneName(key), source.getWidth(), source.getHeight(), ms, "");
        scheduler.wake();
        tryJoin(key);
        publishLanes();
    }

    /**
     * 计划里每一路的录制器都建不起来（MediaRecorder 还有开录没成）：这不是相机的事，整次停（START_FAILED，存储一类，
     * 照旧自动恢复）。会话带不上录像输出是相机那一侧的事，不算在这里（{@link #refusals}）。
     */
    private void checkAllUnrecordable() {
        if (!isRecording || interruptReported || !unrecordable.containsAll(recordingPlan.keySet())) {
            return;
        }
        interruptReported = true;
        String what = useCodecRecording ? "录制器都建不起来" : "MediaRecorder 都备不起来、或者开不了录";
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：计划里每一路（" + roles(new ArrayList<>(recordingPlan.keySet()))
                + "）的" + what + " → 整次停");
        reportStartFailed(recordGeneration, "计划里每一路的" + what);
    }

    /** 一路的画面尺寸算出来了（相机线程报的，挪到主线程）：录像计划里还没有录制器的，现在建。 */
    private void onSizeResolved(String cameraId) {
        String key = keyOf(cameraId);
        if (key == null || !isRecording || !recordingPlan.containsKey(key)) {
            return;
        }
        ensureLane(key);
        publishLanes();
    }

    /** 这一路备一个录制器（主线程）：编码录制 {@link #ensureRecorder}，MediaRecorder {@link #ensureMedia}。 */
    private void ensureLane(String key) {
        if (useCodecRecording) {
            ensureRecorder(key);
        } else {
            ensureMedia(key);
        }
    }

    /**
     * 这一路现在能不能加入，能就加入（主线程）：在等着、录制器停放着或备好了、相机在出画面、会话带着它的录像输出
     * （{@link RecordingLanes#joins}）。停放着的先在 {@link #lanePrep} 上重新备好（{@link CodecVideoRecorder#rearm}），
     * 新建的直接开录；文件按开录那一刻起名，到下一次分段时和别的路对齐。
     */
    private void tryJoin(String key) {
        if (!isRecording || !useCodecRecording) {
            return;
        }
        Slot slot = slots.get(key);
        boolean streaming = scheduler.usable(key);
        boolean carried = slot != null && slot.camera.sessionOutputs().carries(slot.out);
        if (slot == null || !RecordingLanes.joins(lanes.state(key), recorderOf(key), streaming, carried)) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        final long frameAt = Math.max(slot.camera.firstFrameOfSessionAt(), lanes.sinceMs(key));
        final int token = lanes.starting(key, now);
        if (slot.phase == SlotPhase.PREPARED) {
            startSlot(slot, token, frameAt, -1);
            return;
        }
        slot.phase = SlotPhase.REARMING;
        lanePrep.execute(() -> {
            long from = android.os.SystemClock.uptimeMillis();
            boolean ok;
            try {
                ok = slot.recorder.rearm();
            } catch (Exception e) {
                AppLog.e(TAG, "Re-arming the codec recorder of " + key + " failed", e);
                ok = false;
            }
            final boolean armed = ok;
            final long ms = android.os.SystemClock.uptimeMillis() - from;
            laneHandler.post(() -> rearmed(slot, token, frameAt, armed, ms));
        });
        publishLanes();
    }

    /**
     * 重新备好回来了（主线程）。拒了（写入线程被放弃过、或者编码器建不起来）：它还停放着、输出还活着，换一个新录制器。
     * 这一次加入作废了（这一路又退出了、停了录、换了计划、管线放了）：备好的编码器不留着，照「停 → 停放」放掉它。
     */
    private void rearmed(Slot slot, int token, long frameAt, boolean ok, long ms) {
        String key = slot.key;
        boolean current = !slot.retired && slots.get(key) == slot && isRecording && lanes.current(key, token);
        if (!ok) {
            slot.phase = SlotPhase.PARKED;
            com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + slotName(slot) + " 的录制器重新备好没成（" + ms
                    + " ms；写入线程被放弃过、或者编码器建不起来）：换一个新录制器");
            if (current) {
                lanes.waiting(key, android.os.SystemClock.uptimeMillis());
            }
            retire(slot, "换下来了（重新备好没成）");
            ensureRecorder(key);
            publishLanes();
            return;
        }
        if (!current) {
            stopAndPark(slot, null, null);
            return;
        }
        startSlot(slot, token, frameAt, ms);
    }

    /**
     * 开录（主线程）：第一个文件按此刻起名，写进录像此刻实际写在的那个目录（{@link #laneDir}）—— 不是建录制器时的那个：
     * 建好到加入之间可能隔了好几分钟（相机被拿着），这期间别的路换过盘的话，那个已经是死盘。
     * 这个录像意图里第一路加入时报「录起来了」。
     */
    private void startSlot(Slot slot, int token, long frameAt, long rearmMs) {
        String key = slot.key;
        slot.tag.gen = recordGeneration;
        slot.tag.token = token;
        boolean started;
        try {
            started = slot.recorder.startRecording(laneDir());
        } catch (Exception e) {
            AppLog.e(TAG, "Starting the codec recorder of " + key + " failed", e);
            started = false;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (!started) {
            slot.tag.gen = -1;
            slot.phase = SlotPhase.PREPARED;
            lanes.waiting(key, now);
            com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + laneName(key) + " 开录没成（录制器不肯开）：换一个新录制器");
            retire(slot, "换下来了（开录没成）");
            ensureRecorder(key);
            publishLanes();
            return;
        }
        slot.phase = SlotPhase.RUNNING;
        // 会话带上了它的录像输出：带不上的账清掉
        refusals.remove(key);
        noteJoined(key, frameAt, "文件按此刻起名", rearmMs >= 0 ? "（含重新备好 " + rearmMs + " ms）" : "");
        publishLanes();
    }

    /**
     * 一路加入了录像（主线程，开了录）：记这一路进出的账，写「加入录像」那一行；这个录像意图里第一路加入时报「录起来了」。
     * 两种录制器共用。
     *
     * @param fileText 文件那一句：编码录制是「文件按此刻起名」，MediaRecorder 是哪个文件、备好时起的名
     * @param extra    「出画面到开录 N ms」后面补的一句（含重新备好、开录用了多久）；没有是空串
     */
    private void noteJoined(String key, long frameAt, String fileText, String extra) {
        long now = android.os.SystemClock.uptimeMillis();
        LaneTrip trip = trip(key);
        boolean first = trip.leftAt == 0;
        trip.joins++;
        trip.startedAt = now;
        String live = liveNames();
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + laneName(key) + " 加入录像：" + fileText
                + "；出画面到开录 " + (now - frameAt) + " ms" + extra
                + "；" + (first ? "第一次" : "离开 " + duration(now - trip.leftAt) + "后接回")
                + "；在录：" + (live.isEmpty() ? "还没有" : live));
        if (!startReported) {
            startReported = true;
            Set<String> active = new HashSet<>();
            active.add(key);
            reportStarted(recordGeneration, active, new HashSet<>());
        }
    }

    /** 一路的录制器写进了第一笔（主线程）：在加入的那一路就在录了。 */
    private void onLaneFirstData(String key, int gen, int token) {
        if (gen != recordGeneration || !lanes.current(key, token) || lanes.state(key) != RecordingLanes.State.STARTING) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        lanes.live(key, now);
        String file = currentFileOf(key);
        com.kooo.evcam.blackbox.BlackBox.note("录像：相机 " + laneName(key) + " 写进第一笔（文件 "
                + (file == null ? "?" : new File(file).getName()) + "，开录后 " + (now - trip(key).startedAt) + " ms）");
        publishLanes();
    }

    /** 这一路此刻在写的文件（黑匣子用）；没有是 null。 */
    private String currentFileOf(String key) {
        if (useCodecRecording) {
            Slot slot = slots.get(key);
            return slot == null ? null : slot.recorder.currentFile();
        }
        MediaSlot media = mediaSlots.get(key);
        return media == null ? null : media.recorder.currentFile();
    }

    /**
     * 一路退出录像（主线程）：它的相机走了（丢了、我们关了、卡住了在救）、它的录像输出离开了会话、或者写不进而是相机的事。
     * 只动这一路：录制器叫停、收好文件、停放（{@link #stopAndPark}），别的路照录、会话不动，录像不算停；
     * 相机回来带着同一个录像输出出了画面就再加入。MediaRecorder 不能停放：停了放掉（{@link #stopMedia}），回来时另备一个。
     */
    private void leaveLane(String key, String why) {
        RecordingLanes.State state = lanes.state(key);
        if (!isRecording || (state != RecordingLanes.State.STARTING && state != RecordingLanes.State.LIVE)) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        LaneChange change = new LaneChange(liveGenerations(key), why);
        lanes.waiting(key, now);
        LaneTrip trip = trip(key);
        trip.leaves++;
        trip.leftAt = now;
        File relayTarget = useRelayWrite ? finalSaveDir : null;
        if (!useCodecRecording) {
            MediaSlot media = mediaSlots.get(key);
            if (media != null && media.phase == MediaPhase.RUNNING) {
                stopMedia(media, change, relayTarget);
            } else {
                // 还在开录：开起来回来时看到这一次加入作废了，当场停掉、收文件、转存（mediaStarted）
                if (media != null) {
                    media.relayOnStop = relayTarget;
                }
                noteLeftUnstarted(key, why, "MediaRecorder 还在开录，开起来就停掉、收文件");
            }
            publishLanes();
            return;
        }
        Slot slot = slots.get(key);
        if (slot != null && slot.phase == SlotPhase.RUNNING) {
            stopAndPark(slot, change, relayTarget);
        } else {
            // 还在重新备好（没开过录、没有文件）：备好回来时看到这一次加入作废了，照「停 → 停放」放掉编码器（rearmed）
            noteLeftUnstarted(key, why, "还没开录（录制器在重新备好），没有文件");
        }
        publishLanes();
    }

    /**
     * 叫停这个录制器（不等），在收拾线程上收好文件（期限 {@link CodecVideoRecorder#STOP_BUDGET_MS}），再在 {@link #lanePrep}
     * 上停放（编码器放掉；EGL 和输入 SurfaceTexture 留着照常取帧 —— 录像输出还是同一个、还在会话里），回主线程（{@link #slotParked}）。
     * 中转写入只转存这一个录制器收掉的那几个文件：缓存目录里别的路还在写。
     *
     * @param change      一路退出的这一段（黑匣子那一行要）；作废的加入是 null
     * @param relayTarget 中转写入的目标目录；null = 不转存
     */
    private void stopAndPark(Slot slot, LaneChange change, File relayTarget) {
        slot.phase = SlotPhase.STOPPING;
        final CodecVideoRecorder recorder = slot.recorder;
        try {
            recorder.beginStop();
        } catch (Exception e) {
            AppLog.e(TAG, "Error asking the codec recorder of " + slot.key + " to stop", e);
        }
        final long deadline = android.os.SystemClock.uptimeMillis() + CodecVideoRecorder.STOP_BUDGET_MS;
        teardown.execute(() -> {
            String result;
            try {
                recorder.awaitDrain(deadline);
                recorder.finishStop(deadline);
                result = handOverStopped(recorder, relayTarget);
            } catch (Exception e) {
                AppLog.e(TAG, "Error stopping the codec recorder of " + slot.key, e);
                result = "收拾它的录制器出错：" + e;
            }
            final String sealed = result;
            lanePrep.execute(() -> {
                final String done = sealed + parkText(park(slot));
                laneHandler.post(() -> slotParked(slot, change, done));
            });
        });
    }

    /** 停放一个收好了文件的录制器（{@link #lanePrep} 上）：停放好了没有。 */
    private static boolean park(Slot slot) {
        try {
            return slot.recorder.park();
        } catch (Exception e) {
            AppLog.e(TAG, "Error parking the codec recorder of " + slot.key, e);
            return false;
        }
    }

    /** 一个录制器停放好了（主线程）：退出的那一路写一行（文件、别的路怎样），然后看它还用不用（{@link #slotIdle}）。 */
    private void slotParked(Slot slot, LaneChange change, String result) {
        slot.phase = SlotPhase.PARKED;
        slot.parkedAt = android.os.SystemClock.uptimeMillis();
        if (change != null) {
            noteLeft(slotName(slot), change, slot.parkedAt, result, "录制器停放（同一个录像输出）");
        }
        slotIdle(slot);
    }

    /** 手上那一步做完了（主线程）：不用了的交出去放掉；还要用的看能不能加入（相机可能早就回来了），录法变了的换一个。 */
    private void slotIdle(Slot slot) {
        if (slot.retired || releasing) {
            retire(slot, slot.retired ? slot.retiredWhy : "随管线放了");
            return;
        }
        if (slot.suspectWhy != null) {
            // 写不进、判成盘或编码器的事的那个录制器不沿用（Slot.suspectWhy）：换下来；又在录了（接回开得快）就另建一个
            retire(slot, slot.suspectWhy);
        }
        ensureRecorder(slot.key);
        publishLanes();
    }

    /**
     * 调度说这个录像输出离开了会话（主线程）：被「改输出」摘掉、随相机关了、相机层配不上会话丢了它、它的消费者没了。
     * 这一路在录、在加入的退出；这个录制器不再用（{@link #retire}，停放好就放），照计划另建一个。会话配不上的是相机那一侧的事：
     * 隔一会儿再声明，连着几次都带不上就等它的相机走了再来（{@link #sessionRefused}），不算这一路录不了。
     */
    private void onRecordOutputDropped(Slot slot, SingleCamera.Drop why) {
        String key = slot.key;
        if (slot.retired || slots.get(key) != slot) {
            return;
        }
        String text = dropText(why);
        leaveLane(key, "它的录像输出" + text);
        retire(slot, text);
        if (why == SingleCamera.Drop.CONFIG_FAILED && isRecording && recordingPlan.containsKey(key)) {
            sessionRefused(key, slotName(slot));
        }
        ensureRecorder(key);
        scheduler.wake();
        publishLanes();
    }

    /** 会话带不上这一路的录像输出，眼下不再声明（主线程）：还没到再试的时候，或者连着几次都带不上了（{@link #refusals}）。 */
    private boolean refused(String key) {
        Refusal refusal = refusals.get(key);
        return refusal != null && (refusal.count >= CameraLiveness.MAX_ATTEMPTS
                || android.os.SystemClock.uptimeMillis() < refusal.retryAt);
    }

    /**
     * 会话连着 {@link CameraLiveness#MAX_ATTEMPTS} 次都带不上这一路的录像输出：等它的相机走了回来、登记表变了、或者亮屏再试
     * （主线程，{@link #liftRefusals}）。
     */
    private boolean refusedUntilGone(String key) {
        Refusal refusal = refusals.get(key);
        return refusal != null && refusal.count >= CameraLiveness.MAX_ATTEMPTS;
    }

    /**
     * 会话连着几次都带不上录像输出、等着的那几路（{@link #refusedUntilGone}）重新声明（主线程）：和救援梯子停手之后同样的解锁
     * （ChannelRules.liftStops，§7.4）—— 要它的登记方变了（只那一路，{@link LaneSide#onLaneWantersChanged}）、亮屏（各路）；
     * 相机服务报它空闲说明它的相机走过了，那时已经清了
     * （{@link LaneSide#onLaneCameraGone}）。以前只等相机走：录像开着时 RECORDING 一直让它开着、在出画面，相机不会走，
     * 这一路这一趟就再也不录，「等待摄像头，恢复后自动继续」和「各路都停手了 …再试」那一行都不属实（审查 2026-10-11）。
     *
     * @param why  为什么现在再试（进黑匣子）
     * @param keys 只看这几路；null 是各路
     */
    private void liftRefusals(String why, java.util.Collection<String> keys) {
        if (!isRecording || refusals.isEmpty()) {
            return;
        }
        List<String> lifted = new ArrayList<>();
        for (String key : new ArrayList<>(refusals.keySet())) {
            if ((keys == null || keys.contains(key)) && refusedUntilGone(key)) {
                refusals.remove(key);
                lifted.add(key);
            }
        }
        if (lifted.isEmpty()) {
            return;
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：" + roles(lifted) + " 的会话连着 " + CameraLiveness.MAX_ATTEMPTS
                + " 次带不上录像输出，" + why + "：重新声明，从头再试");
        for (String key : lifted) {
            ensureLane(key);
        }
        scheduler.wake();
        publishLanes();
    }

    /**
     * 会话带不上这一路的录像输出（主线程；这一路已经退出、录制器换下来了）：记一次。还没到次数就隔
     * {@link CameraLiveness#RETRY_GAP_MS} 再声明一个（{@link #ensureLane}）；到了次数就等它的相机走了再来
     * （{@link LaneSide#onLaneCameraGone}）。
     */
    private void sessionRefused(String key, String name) {
        Refusal refusal = refusals.get(key);
        if (refusal == null) {
            refusal = new Refusal();
            refusals.put(key, refusal);
        }
        refusal.count++;
        boolean again = refusal.count < CameraLiveness.MAX_ATTEMPTS;
        noteSessionRefused(name, refusal.count, again);
        if (!again) {
            return;
        }
        refusal.retryAt = android.os.SystemClock.uptimeMillis() + CameraLiveness.RETRY_GAP_MS;
        final int gen = recordGeneration;
        laneHandler.postDelayed(() -> {
            if (gen == recordGeneration && isRecording) {
                ensureLane(key);
                scheduler.wake();
                publishLanes();
            }
        }, CameraLiveness.RETRY_GAP_MS);
    }

    /** 录像输出为什么离开了会话。 */
    private static String dropText(SingleCamera.Drop why) {
        switch (why) {
            case REMOVED:
                return "摘掉了";
            case CLOSED:
                return "随相机关了";
            case CONFIG_FAILED:
                return "被相机丢了（会话配不上）";
            default:
                return "没了（消费者被收走）";
        }
    }

    /**
     * 这个录制器不用了（主线程）：撤回它的录像输出（之后的「改输出」顺带摘掉它），手上没事就交给调度，等它离开会话再放
     * （{@link ChannelScheduler#handOver}）—— 会话还带着它的时候放掉，它就成了死输出，那一路整条重复请求停下。
     * 手上有事的（在录、在停、在重新备好），做完那一步再交（{@link #slotIdle}）。
     */
    private void retire(Slot slot, String why) {
        if (!slot.retired) {
            slot.retired = true;
            slot.retiredWhy = why;
            if (slots.get(slot.key) == slot) {
                slots.remove(slot.key);
            }
            slot.camera.withdrawRecord(slot.texture);
        }
        if (slot.idle()) {
            handOverForRelease(slot);
        }
    }

    private void handOverForRelease(Slot slot) {
        if (slot.handedOver || slot.released) {
            return;
        }
        slot.handedOver = true;
        if (leftoversDue) {
            releaseSlot(slot);
            return;
        }
        retiring.add(slot);
        scheduler.handOver(slot.key, slot.out, () -> releaseSlot(slot));
    }

    /** 放掉一个不用了的录制器（主线程；它的录像输出已经离开会话，或者到了期限）：在收拾线程上放。 */
    private void releaseSlot(Slot slot) {
        if (slot.released) {
            return;
        }
        slot.released = true;
        retiring.remove(slot);
        com.kooo.evcam.blackbox.BlackBox.note("录像：相机 " + slotName(slot) + " 的录像输出" + slot.retiredWhy + "：录制器放掉"
                + (slot.parkedAt > 0 ? "（停放了 " + duration(android.os.SystemClock.uptimeMillis() - slot.parkedAt) + "）"
                : ""));
        releaseLater(slot.recorder);
    }

    /**
     * 管线放了以后过了「退出最多等」（{@link ChannelRules#EXIT_WAIT_MAX_MS}）调度还没停（一次关卡死在相机服务里）：
     * 换下来还没放的录制器直接放掉，不再等它们离开会话；之后换下来的也直接放。
     */
    private void releaseLeftovers() {
        leftoversDue = true;
        String why = "随管线放了（调度 " + (ChannelRules.EXIT_WAIT_MAX_MS / 1000) + " 秒还没关完相机）";
        for (Slot slot : new ArrayList<>(slots.values())) {
            retire(slot, why);
        }
        for (Slot slot : new ArrayList<>(retiring)) {
            releaseSlot(slot);
        }
    }

    /** {@link #releaseLeftovers} 排在 {@link #laneHandler} 上的那一下：调度停了（{@link ChannelSide#stopped}）就撤。 */
    private final Runnable leftoversTask = this::releaseLeftovers;

    /** 黑匣子里一个录制器那一路的叫法（管线放了以后 cameras 清空了也叫得出）。 */
    private static String slotName(Slot slot) {
        return slot.camera.getCameraId() + "（" + roleName(slot.key) + "）";
    }

    /**
     * 一路的录制器报写不进（主线程；裁判在录制器里：编码录制 15 秒没写进新数据，MediaRecorder 文件 9 秒没长、开录 10 秒
     * 没写进第一笔、换段时新的一段开不起来）。是不是相机的事看 {@link RecordingLanes#stallIsCamera}：
     * 相机没在出画面、会话里没有它的录像输出、或者最近没出过画面 —— 只这一路退出；都好好的是盘或编码器的事，
     * 整次停（{@link #onWriteStalled}，存储一类，照旧自动恢复）。
     *
     * @param gen   报上来的那个录制器开录时的录像代数
     * @param token 开录时这一路的计数：这一路之后退出过、又加入过，之前那一次报的不算
     */
    private void onLaneWriteStalled(String key, int gen, int token, long stalledMs, boolean everWrote) {
        RecordingLanes.State state = lanes.state(key);
        SingleCamera camera = cameras.get(key);
        ChannelRules.Out out = recordOutOf(key);
        if (gen != recordGeneration || !isRecording || !lanes.current(key, token) || camera == null || out == null
                || (state != RecordingLanes.State.STARTING && state != RecordingLanes.State.LIVE)) {
            return;
        }
        boolean streaming = scheduler.usable(key);
        boolean carried = camera.sessionOutputs().carries(out);
        boolean fresh = camera.hasFramesWithin(RecordingLanes.FRESH_FRAMES_MS);
        String head = "录像写不进：相机 " + laneName(key) + " " + (stalledMs / 1000) + " 秒没写进";
        if (RecordingLanes.stallIsCamera(streaming, carried, fresh)) {
            String what = !carried ? "会话里没有它的录像输出" : !streaming ? "它没在出画面"
                    : "它 " + seconds(RecordingLanes.FRESH_FRAMES_MS) + "没出过画面";
            com.kooo.evcam.blackbox.BlackBox.noteImportant(head + "，" + what + " → 只这一路退出");
            leaveLane(key, (stalledMs / 1000) + " 秒没写进、" + what);
            return;
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant(head + "，它在出画面、会话里有它的录像输出 → 盘或编码器的事：整次停"
                + (useCodecRecording ? "，这一路的录制器停放好就换下来、接回时另建" : ""));
        Slot stalled = useCodecRecording ? slots.get(key) : null;
        if (stalled != null) {
            stalled.suspectWhy = "换下来了（写不进，整次停）";
        }
        // 「一笔没写进」说的是这一次录像：录制器的那个数每次加入从头算，中途接回的一路还没写进就卡住，也是录着录着写不进了
        onWriteStalled(stalledMs, everWrote || firstDataWritten);
    }

    /**
     * 这一路此刻声明着的录像输出（主线程）：编码录制是录制器的输入 SurfaceTexture，MediaRecorder 是这一段的 Surface
     * （换段时是新的那一段的）；没有是 null。
     */
    private ChannelRules.Out recordOutOf(String key) {
        if (useCodecRecording) {
            Slot slot = slots.get(key);
            return slot == null ? null : slot.out;
        }
        MediaSlot media = mediaSlots.get(key);
        return media == null ? null : media.out;
    }

    /**
     * 退出录像的那个录制器收掉了哪些文件（收拾线程上）：黑匣子一句；中转写入时把它们转存到这一次录像的目标目录
     * （写入线程还攥着的那个除外，半个文件转过去就坏了 —— 记进 {@link #HELD_BY_STUCK_WRITERS}，整次停扫缓存时也跳过它）。
     */
    private String handOverStopped(CodecVideoRecorder recorder, File relayTarget) {
        String held = recorder.heldFile();
        if (held != null) {
            HELD_BY_STUCK_WRITERS.add(held);
        }
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
            laneHandler.postDelayed(() -> transferSpecificTempFiles(relayTarget, transfer), 500);
        }
        String result = (names.length() == 0 ? "没有留下文件" : "文件 " + names + " 收好了")
                + (recorder.drainLate() ? "（编码线程到期限还没排空，最后几帧没要）" : "");
        return held == null ? result
                : result + "；" + new File(held).getName() + " 写入线程卡住没收好，交给了抢救";
    }

    /** 停放的结果（收拾线程上）：停放好了不多说；到点编码线程还没做到的，它做到时自己停放好。 */
    private static String parkText(boolean parked) {
        return parked ? "" : "；编码器还没放完（编码线程忙，轮到时自己停放）";
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

    /** 除了这一路，在录的几路此刻的会话代数：一路开始退出时记下，停放好时对。 */
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

    /** 黑匣子：这一段里别的在录的几路最长断帧多久、会话动没动过 —— 车上核对「一路退出时别的路没被动到」看这一句。 */
    private String othersDuring(LaneChange change) {
        List<ChannelScheduler.Other> others = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : change.generations.entrySet()) {
            SingleCamera camera = cameras.get(entry.getKey());
            if (camera != null) {
                others.add(new ChannelScheduler.Other(entry.getKey(), camera.longestFrameGapSince(change.startedAt),
                        camera.sessionGeneration() != entry.getValue()));
            }
        }
        return others.isEmpty() ? "这段时间里没有别的路在录" : "这段时间里" + gapsText(others);
    }

    /**
     * 黑匣子：几路这段时间里最长断帧多久、会话动没动 ——「环视最长断帧不到 0.2 秒、会话没动，后座舱最长断帧 3.4 秒、会话被重建过」。
     * 一路退出录像时别的路、调度开 / 改 / 救 / 关那一行、关完之后 30 秒再量的那一行、别的程序拿走一路相机之后 30 秒的那一行
     * 都照它写（{@link ChannelScheduler.Other}），同一句话只有这一处。过一阵再量的那两行里我们中途自己动了那一路的
     * （{@link ChannelScheduler.Other#ours}），只量到那一刻，写明「（只量到第 10.0 秒：那时我们关了它）」—— 之后的不是断帧（审查 rev3 B2）。
     */
    private static String gapsText(List<ChannelScheduler.Other> others) {
        StringBuilder sb = new StringBuilder();
        for (ChannelScheduler.Other other : others) {
            sb.append(sb.length() > 0 ? "，" : "").append(roleName(other.key))
                    .append(other.gapMs < SingleCamera.NOTABLE_GAP_MS
                            ? "最长断帧不到 " + seconds(SingleCamera.NOTABLE_GAP_MS) : "最长断帧 " + seconds(other.gapMs))
                    .append(other.sessionChanged ? "、会话被重建过" : "、会话没动");
            if (other.ours != null) {
                sb.append("（只量到第 ").append(seconds(other.oursAtMs)).append("：那时我们")
                        .append(other.ours == ChannelRules.Move.CLOSE ? "关了它"
                                : other.ours == ChannelRules.Move.APPLY ? "改了它的输出"
                                : other.ours == ChannelRules.Move.RESCUE ? "在救它"
                                : other.ours == ChannelRules.Move.OPEN ? "重开了它" : "动了它")
                        .append("）");
            }
        }
        return sb.toString();
    }

    /**
     * 共用的分段名字刚换了一个新的（主线程）：中途加入、名字还和别的路错开着的那几路马上跟着切、拿同一个名字
     * （{@link CodecVideoRecorder#switchNow}、{@link VideoRecorder#switchNow}；名字本来就对得上的什么都不做）。
     */
    private void alignJoinedLanes() {
        for (Slot slot : slots.values()) {
            if (slot.phase == SlotPhase.RUNNING) {
                slot.recorder.switchNow();
            }
        }
        for (MediaSlot media : mediaSlots.values()) {
            if (media.phase == MediaPhase.RUNNING) {
                media.recorder.switchNow();
            }
        }
    }

    /** 放掉一个用不上了的录制器：放在收拾线程上（收文件、拆 GL、等线程退出要几百毫秒）。 */
    private void releaseLater(CodecVideoRecorder recorder) {
        teardown.execute(() -> {
            try {
                recorder.release();
            } catch (Exception e) {
                AppLog.e(TAG, "Error releasing an unused codec recorder", e);
            }
        });
    }

    private LaneTrip trip(String key) {
        LaneTrip trip = trips.get(key);
        if (trip == null) {
            trip = new LaneTrip();
            trips.put(key, trip);
        }
        return trip;
    }

    /**
     * 录制器的回调（编码录制器每个一份，建的时候接上；MediaRecorder 每一趟一份，备的时候接上）。录制器在自己的线程上报；
     * 带着它这一次开录的代数和计数（{@link RunTag}）回主线程对一下：停过录（又开过）、这一路之后退出过又加入过，
     * 之前那一次报的写不进、第一笔数据都不算。分段、换盘只看代数：同一次录像里退出的那一路晚到的分段照样转存。
     */
    private RecordCallback laneCallback(RunTag tag) {
        return new RecordCallback() {
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
            public void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath) {
                AppLog.d(TAG, "Segment switch for camera " + cameraId + " to segment " + newSegmentIndex);
                if (staleCallback(tag.gen, "segment switch", cameraId)) {
                    // 停过录（又开过）：中转目标、空间检查都是这一次的了。上一次的文件由那次停录转存
                    return;
                }
                // 中转写入：把收好的上一段转存到最终目录（由回调给出确切路径，不会转到正在录的新文件）。
                // 界面上的段号不在这里报：出一个新的共用名字才加 1（segmentTimestampProvider）
                if (useRelayWrite && finalSaveDir != null && newSegmentIndex > 0 && completedFilePath != null) {
                    scheduleRelayTransfer(completedFilePath);
                }
                checkStorage("分段切换");
            }

            @Override
            public void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles) {
                if (deletedFiles != null && !deletedFiles.isEmpty()) {
                    AppLog.w(TAG, "Corrupted files deleted for camera " + cameraId + ": " + deletedFiles.size()
                            + " file(s)");
                    for (String file : deletedFiles) {
                        AppLog.d(TAG, "  Deleted: " + file);
                    }
                    if (corruptedFilesCallback != null) {
                        mainHandler.post(() -> corruptedFilesCallback.onCorruptedFilesDeleted(deletedFiles));
                    }
                }
            }

            @Override
            public void onRecordingRelocated(String cameraId, File dir, String why, long rescuedMs) {
                AppLog.w(TAG, "Camera " + cameraId + " relocated recording to " + dir + " (" + why
                        + "), rescued " + rescuedMs + "ms from memory");
                if (staleCallback(tag.gen, "relocation", cameraId)) {
                    return;
                }
                noteRelocated(dir);
            }

            @Override
            public void onWriteStalled(String cameraId, long stalledMs, boolean everWrote) {
                final int gen = tag.gen;
                final int token = tag.token;
                mainHandler.post(() -> onLaneWriteStalled(tag.key, gen, token, stalledMs, everWrote));
            }

            @Override
            public void onWriteBacklog(String cameraId) {
                final int gen = tag.gen;
                mainHandler.post(() -> {
                    if (!staleCallback(gen, "write backlog", cameraId)) {
                        MultiCameraManager.this.onWriteBacklog(cameraId);
                    }
                });
            }

            @Override
            public void onFirstDataWritten(String cameraId) {
                AppLog.d(TAG, "First data written for camera " + cameraId);
                final int gen = tag.gen;
                final int token = tag.token;
                if (staleCallback(gen, "first data", cameraId)) {
                    return;
                }
                // 这次录像的第一笔（哪一路都算）：外面的计时从这里起，每次录像只通知一次
                if (!firstDataWritten) {
                    firstDataWritten = true;
                    firstDataWrittenAtMs = System.currentTimeMillis();
                }
                if (!hasNotifiedFirstDataWritten && firstDataWrittenCallback != null) {
                    hasNotifiedFirstDataWritten = true;
                    AppLog.d(TAG, "Notifying external: first data written, recording truly started");
                    mainHandler.post(() -> firstDataWrittenCallback.onFirstDataWritten());
                }
                // 在加入的那一路：写进第一笔才算在录
                mainHandler.post(() -> onLaneFirstData(tag.key, gen, token));
            }
        };
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
     * <p>别的挂着的 U 盘，设定的那个优先、其余按剩余空间；开发者选项打开着时最后是内置存储（降级，项目所有者 2026-10-24：
     * 只在开发者选项打开时，要说清楚 —— {@link StorageHelper#recordingFallback}）。
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

    /**
     * 存储位置选的那个 U 盘上的录像目录，此刻挂着（看传进来的挂载表，不碰盘）才给：录制器写在别处时，每开一个新文件先问它，
     * 挂回来就写回去。选的是内置存储、中转写入（录制器写的是内置缓存）、选的盘没挂着：null。
     */
    private File homeDirFor(List<File> mounted) {
        AppConfig config = new AppConfig(context);
        if (!config.isUsingExternalSdCard() || config.shouldUseRelayWrite()) {
            return null;
        }
        File root = FallbackVolumes.home(mounted, config.getCustomSdCardPath(), config.getLastDetectedSdPath());
        return root != null ? StorageHelper.videoDirOn(root) : null;
    }

    /** 录制器换了盘（写回设定的盘也算）：记下实际写到哪、状态条那一格跟着变、存储检查改看新盘。 */
    private void noteRelocated(File dir) {
        StorageHelper.noteRecordingDir(dir);
        StorageHelper.noteRecordingFallback(StorageHelper.offTarget(context, dir));
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
    private FirstDataWrittenCallback firstDataWrittenCallback;
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

    /**
     * 当前是第几个分段，从 0 数：这一次录像出过几个共用的分段名字（{@link SharedSegmentName#count}）。
     * 一路中途加入、退出不动它；每一路的第一个文件按它自己开录的时刻起名，不算新的一段。
     */
    public int getCurrentSegmentIndex() {
        return sharedName.count();
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
     * <p>盘满、写不进、录像的样子变了（一路加入 / 退出）、开录 / 停录走到哪一步这几个<b>不在这里动</b>：它们是
     * RecordingCoordinator 接的，它不随主界面走 —— 换掉的话主界面一重建，录像就没人停、没人接了。</p>
     */
    public void detachUiCallbacks() {
        statusCallback = (cameraId, status) -> { };
        previewSizeCallback = (cameraKey, cameraId, previewSize) -> { };
        corruptedFilesCallback = deletedFiles -> { };
        segmentSwitchCallback = newSegmentIndex -> { };
        firstDataWrittenCallback = () -> { };
        AppLog.i(TAG, "主界面已离开，回调换成空实现，录制管线继续 recording=" + isRecording);
    }

    public void setFirstDataWrittenCallback(FirstDataWrittenCallback callback) {
        this.firstDataWrittenCallback = callback;
    }

    public void setMaxOpenCameras(int maxOpenCameras) {
        this.maxOpenCameras = Math.max(1, maxOpenCameras);
    }

    /**
     * 启用的那几路（建管线时 Holder 给，在 {@link #initCameras} 之前）：登记表 §一 里主界面预览、拍照要的「启用的全部」，
     * 录像也只录它们（{@link #openRecordingIntent}）。交给调度（{@link ChannelRules.World#enabled}）。
     */
    public void setEnabledLanes(Set<String> keys) {
        enabledLanes = keys == null ? null : new java.util.LinkedHashSet<>(keys);
    }

    /**
     * 这次运行启用的前座舱去掉了（调度报的，通道眼里熄屏的那一刻；暂时的规矩，见 {@link FrontCabinOneShot}）：启用的几路、
     * 这一次录像的计划都拿掉它，再告诉管线的持有者（主界面回到前台时不再摆它）。关相机不在这里 —— 那一刻座舱
     * 本来就照熄屏放开，之后谁都不要它，调度照登记表关；它的录制器由调度照「不再留着录像输出」交回来再放。主线程。
     */
    private void retireFrontCabin(String key) {
        if (isReleased() || key == null) {
            return;
        }
        if (enabledLanes != null) {
            enabledLanes.remove(key);
        }
        boolean dropped = recordingPlan.containsKey(key);
        if (dropped) {
            leaveLane(key, "这次运行去掉了前座舱");
            recordingPlan.remove(key);
            lanes.drop(key);
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("前座舱只用一次：通道眼里熄屏，前座舱从这次运行里去掉，"
                + "亮屏也不再开；下次启动不带前座舱" + (dropped ? "（这一次录像不再录前座舱）" : ""));
        if (dropped) {
            publishLanes();
            scheduler.wake();
        }
        CameraManagerHolder.getInstance().frontCabinRetired(this, key);
    }

    /** 管线里的、启用的那几路，按开的次序。 */
    private List<String> enabledActiveKeys() {
        List<String> keys = getActiveCameraKeys();
        if (enabledLanes != null) {
            keys.retainAll(enabledLanes);
        }
        return keys;
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
    /** 当前已初始化的摄像头路数（照存下的映射建时，相机列表里此刻没有的那一路也算）。 */
    public int getCameraCount() {
        return cameras.size();
    }

    public SingleCamera getCamera(String position) {
        return cameras.get(position);
    }

    // ------------------------------------------------------------------ 主界面的预览画布、后视镜、盲区画面的画布（2.11 阶段 4a、4b）
    // 主界面、超级镜子、盲区画面只声明、撤回自己的画布，再叫醒调度；带不带、什么时候摘由调度定（channel-logic §一、§二.4）。
    // 以前画布一就绪、一销毁就 setTextureView + recreateSession，是调度之外的又一个动手方。

    /**
     * 画布被系统收走时由管线留下的那几块（主线程），按输出的种类、再按路：主界面重建时的预览画布（大纲 §7.6），
     * 关掉的超级镜子的画布（大纲 §7.7：环视那一路，「后视 + 后座舱」时还有后座舱），移走的盲区画面窗口的画布（同 §7.7，2026-10-16）。
     * 项目所有者 2026-10-11 两问都答「按建议」，做法同一套。
     *
     * <p>为什么留：画布被系统收走时，会话里的消费者一死，那一路整条重复请求停下 —— 预览和录像一起停，只能一路一路马上摘，
     * 摘到之前录像断着（大纲 §6 风险 2；10-10 09:13:10 主界面重建时在录就发生过；关后视镜时环视也一样，那一步还要等安静、
     * 等别的路手上那一步）。所以画布销毁时 onSurfaceTextureDestroyed 返回 false，画布交到这里（{@link #keepPreviewCanvas}、
     * {@link #keepMirrorCanvas}、{@link #keepSideCanvas}）：新界面、新开的后视镜、盲区画面窗口用 {@code TextureView.setSurfaceTexture}
     * 接回同一块（{@link #takeKeptPreview}、{@link #takeKeptMirror}、{@link #takeKeptSide}）—— 同一块画布就是同一个输出，通道零变动、录像不断；
     * 没人接回的交给调度（{@link ChannelScheduler#handOver}），等它照「减输出」的规矩摘掉、或者随相机关了再放
     * （{@link #releaseKeptCanvas}）。</p>
     */
    private final Map<ChannelRules.Kind, Map<String, android.graphics.SurfaceTexture>> keptCanvases = new HashMap<>();

    /** 这一种画布留着的那几块，按路（主线程）。 */
    private Map<String, android.graphics.SurfaceTexture> keptOf(ChannelRules.Kind kind) {
        Map<String, android.graphics.SurfaceTexture> byLane = keptCanvases.get(kind);
        if (byLane == null) {
            byLane = new HashMap<>();
            keptCanvases.put(kind, byLane);
        }
        return byLane;
    }

    /**
     * 主界面这几格画布就绪了：各自声明预览（主线程）。同一块画布再声明一次什么都不做（{@link SingleCamera#declarePreview}），
     * 所以每次画布就绪、主界面接上管线时都把各路重新声明一遍。这一路没有相机、画布还没就绪的跳过。
     */
    public void declarePreviews(TextureView frontView, TextureView backView, TextureView leftView) {
        declarePreview("front", frontView);
        declarePreview("back", backView);
        declarePreview("left", leftView);
    }

    /**
     * 声明这一格的预览，声明变了就叫醒调度（主线程）。只声明：开不开、带不带它、什么时候改会话都由调度定 ——
     * 相机关着的，调度开的时候就带着它；开着的，调度排一步「改输出」加上它。
     *
     * @return true：声明变了
     */
    public boolean declarePreview(String cameraKey, TextureView view) {
        SingleCamera camera = cameras.get(cameraKey);
        android.graphics.SurfaceTexture texture = view == null ? null : view.getSurfaceTexture();
        if (camera == null || texture == null || texture.isReleased()) {
            return false;
        }
        Map<String, android.graphics.SurfaceTexture> kept = keptOf(ChannelRules.Kind.PREVIEW);
        if (kept.get(cameraKey) == texture) {
            // 留着的那一块被接回了（平常由 takeKeptPreview 先拿回去；这里兜一下，免得调度回头把它放掉）
            kept.remove(cameraKey);
            scheduler.reclaim(cameraKey, new ChannelRules.Out(ChannelRules.Kind.PREVIEW, texture));
        }
        boolean changed = camera.declarePreview(view, texture);
        if (changed) {
            scheduler.wake();
        }
        return changed;
    }

    /**
     * 主界面这一格的画布要被系统收走了（onSurfaceTextureDestroyed，主线程；主界面销毁时视图从窗口上摘下来那一下）：
     * 撤回预览，画布由管线留着（大纲 §7.6，见 {@link #keptCanvases}、{@link #keepCanvas}）。
     *
     * <p>交给会话里还带着它的那一份管线（{@link #ownerOf}）：退出、换车型时那一份已经放了、它的调度还在按次序关，
     * 画布多半还在它的会话里 —— 以前这时照常让 TextureView 放，关到一半的会话里多一个死输出（2.11.4 R1 审查）。</p>
     *
     * @param current 调用方手里的那一份（可能是 null、可能已经放了）
     * @return true：画布留下了，调用方返回 false（TextureView 不放）；false：哪一份都不带着它、也没有这一路，调用方照常让它放
     */
    public static boolean keepPreviewCanvas(MultiCameraManager current, String cameraKey,
                                            android.graphics.SurfaceTexture texture) {
        MultiCameraManager owner = ownerOf(current, ChannelRules.Kind.PREVIEW, cameraKey, texture);
        return owner != null && owner.keepCanvas(ChannelRules.Kind.PREVIEW, cameraKey, texture);
    }

    /**
     * 超级镜子的画布要被系统收走了（onSurfaceTextureDestroyed，主线程；关后视镜 —— 服务退出、窗口移走那一下）：
     * 撤回后视镜输出，画布由管线留着（大纲 §7.7，项目所有者 2026-10-11「按建议，留住」；见 {@link #keptCanvases}、
     * {@link #keepCanvas}）。后视镜声明在环视那一路上；切换范围是「后视 + 后座舱」时后座舱也有一块（{@code cameraKey}）。
     *
     * <p>这样关后视镜时环视零变动：画布还活着就是「留着的输出」，环视（含环视的录像）照旧出画面，等下一次本来要动环视
     * （改输出）、或者关环视时一起摘，摘掉以后才放。代价是这期间环视还往一块没人显示的画布送帧，和贴边、熄屏时留着的一样
     * （大纲 §6 风险 3）。</p>
     *
     * @param current 调用方手里的那一份（可能是 null、可能已经放了）；交给哪一份照 {@link #ownerOf}
     * @return true：画布留下了，调用方返回 false（TextureView 不放）；false：哪一份都不带着它、也没有环视那一路，调用方照常让它放
     */
    public static boolean keepMirrorCanvas(MultiCameraManager current, String cameraKey,
                                           android.graphics.SurfaceTexture texture) {
        MultiCameraManager owner = ownerOf(current, ChannelRules.Kind.MIRROR, cameraKey, texture);
        return owner != null && owner.keepCanvas(ChannelRules.Kind.MIRROR, cameraKey, texture);
    }

    /**
     * 盲区画面的窗口移走了（onSurfaceTextureDestroyed，主线程）：撤回盲区画面输出，画布由管线留着、由调度放 ——
     * 和超级镜子同一个办法（{@link #keepMirrorCanvas}），会话里的画布不自己放。只声明在环视那一路上。
     *
     * @return true：画布留下了，调用方返回 false（TextureView 不放）；false：照常让它放
     */
    public static boolean keepSideCanvas(MultiCameraManager current, android.graphics.SurfaceTexture texture) {
        MultiCameraManager owner = ownerOf(current, ChannelRules.Kind.SIDE, CameraSlots.KEY_SURROUND, texture);
        return owner != null && owner.keepCanvas(ChannelRules.Kind.SIDE, CameraSlots.KEY_SURROUND, texture);
    }

    /** HUD 上的盲区画面窗口移走了：同 {@link #keepSideCanvas}，是另一样输出（{@link ChannelRules.Kind#HUD}）。 */
    public static boolean keepHudCanvas(MultiCameraManager current, android.graphics.SurfaceTexture texture) {
        MultiCameraManager owner = ownerOf(current, ChannelRules.Kind.HUD, CameraSlots.KEY_SURROUND, texture);
        return owner != null && owner.keepCanvas(ChannelRules.Kind.HUD, CameraSlots.KEY_SURROUND, texture);
    }

    /**
     * 最近放了的那一份（{@link #release}）：退出、换车型时它的调度还在按次序关，会话里可能还带着主界面、后视镜、盲区画面的画布
     * （{@link #ownerOf}）。弱引用；放的那一下（哪个线程都可能）写，主线程上读。
     */
    private static volatile java.lang.ref.WeakReference<MultiCameraManager> lastReleased =
            new java.lang.ref.WeakReference<>(null);

    /**
     * 一块画布要被系统收走时，交给哪一份管线留着（R1，项目所有者 2026-10-14：动相机的都归通道调度管 —— 会话里还带着的画布，
     * 不许由别人放掉，只交给带着它的那一份调度，等它离开会话再放）：最近放了、调度还在关、会话里正带着它的那一份优先；
     * 否则就是 {@code current}（可能是 null）。主线程。
     */
    static MultiCameraManager ownerOf(MultiCameraManager current, ChannelRules.Kind kind, String cameraKey,
                                      android.graphics.SurfaceTexture texture) {
        MultiCameraManager released = lastReleased.get();
        if (released != null && released != current && texture != null
                && released.scheduler.sessionCarries(cameraKey, new ChannelRules.Out(kind, texture))) {
            return released;
        }
        return current;
    }

    /**
     * 留下这一块画布（主线程）：预览、后视镜、盲区画面同一套（大纲 §7.6、§7.7）。
     *
     * <p>先摘掉旧视图挂在画布上的出帧监听 —— 那个监听拿着旧视图（连同销毁了的主界面、退出了的后视镜服务），新视图接回时
     * 会挂它自己的；再撤回这一种输出的声明 —— 撤回不碰会话：画布还活着，就是「留着的输出」，照「减输出能拖就拖」留在会话里；
     * 最后交给调度，等它离开会话再放。</p>
     *
     * <p>这一份已经放了（退出、换车型）：各路的相机对象已经交给调度（{@link #cameras} 清了），会话里还带着这块画布就照样交给调度 ——
     * 它随相机关了才放（2.11.4 R1）；不带着的照常让 TextureView 放。</p>
     */
    private boolean keepCanvas(ChannelRules.Kind kind, String cameraKey, android.graphics.SurfaceTexture texture) {
        if (texture == null || texture.isReleased()) {
            return false;
        }
        SingleCamera camera = cameras.get(cameraKey);
        if (camera == null && !scheduler.sessionCarries(cameraKey, new ChannelRules.Out(kind, texture))) {
            return false;
        }
        texture.setOnFrameAvailableListener(null);
        boolean withdrawn = camera != null && withdrawCanvas(camera, kind, texture);
        android.graphics.SurfaceTexture older = keptOf(kind).put(cameraKey, texture);
        if (older != null && older != texture) {
            // 上一块留着的还没人接回就又来一块（新界面、新窗口没接回它，自己建了画布）：那一块不再等人接回，
            // 仍在调度手里，等它离开会话再放（releaseKeptCanvas 看到表里已经不是它，只放不摘表）
            AppLog.w(TAG, "Kept " + kind + " canvas for " + cameraKey + " replaced before it was taken back");
        }
        ChannelRules.Out out = new ChannelRules.Out(kind, texture);
        scheduler.handOver(cameraKey, out, () -> releaseKeptCanvas(kind, cameraKey, texture));
        if (withdrawn) {
            scheduler.wake();
        }
        AppLog.d(TAG, kind + " canvas for " + cameraKey + " kept by the pipeline (withdrawn=" + withdrawn
                + ", in session=" + scheduler.sessionCarries(cameraKey, out) + (released ? ", released pipeline" : "")
                + ")");
        return true;
    }

    /** 撤回这一种画布的声明（只撤回 {@code texture} 这一块，不是它就什么都不做）。 */
    private static boolean withdrawCanvas(SingleCamera camera, ChannelRules.Kind kind,
                                          android.graphics.SurfaceTexture texture) {
        switch (kind) {
            case MIRROR:
                return camera.withdrawMirror(texture);
            case SIDE:
                return camera.withdrawSide(texture);
            case HUD:
                return camera.withdrawHud(texture);
            default:
                return camera.withdrawPreview(texture);
        }
    }

    /**
     * 新界面要接回这一格留着的画布（主线程，MainActivity.onCreate、画布挂上窗口之前）：从调度那里拿回来，交给调用方
     * （{@code TextureView.setSurfaceTexture}，之后归那个视图；它销毁时再走一遍 {@link #keepPreviewCanvas}）。
     * 拿回来之后调用方要接着 {@link #declarePreview}。
     *
     * @return 留着、还活着的那一块；没有是 null
     */
    public android.graphics.SurfaceTexture takeKeptPreview(String cameraKey) {
        return takeKeptCanvas(ChannelRules.Kind.PREVIEW, cameraKey);
    }

    /**
     * 新开的后视镜窗口要接回上一个窗口留下的画布（主线程，RearViewMirrorService 窗口加上之后、第一次画之前）：
     * 从调度那里拿回来，交给调用方（{@code TextureView.setSurfaceTexture}，之后归那个窗口；它再被收走时再走一遍
     * {@link #keepMirrorCanvas}）。拿回来之后调用方要接着在环视上重新声明后视镜输出（{@link SingleCamera#declareMirror}）。
     *
     * @return 留着、还活着的那一块；没有是 null
     */
    public android.graphics.SurfaceTexture takeKeptMirror(String cameraKey) {
        return takeKeptCanvas(ChannelRules.Kind.MIRROR, cameraKey);
    }

    /** 新开的盲区画面窗口接回上一个窗口留下的画布（同 {@link #takeKeptMirror}）；拿回来之后接着重新声明（{@link SingleCamera#declareSide}）。 */
    public android.graphics.SurfaceTexture takeKeptSide() {
        return takeKeptCanvas(ChannelRules.Kind.SIDE, CameraSlots.KEY_SURROUND);
    }

    /** 新开的 HUD 窗口接回上一个留下的画布（同 {@link #takeKeptSide}）；拿回来之后接着重新声明（{@link SingleCamera#declareHud}）。 */
    public android.graphics.SurfaceTexture takeKeptHud() {
        return takeKeptCanvas(ChannelRules.Kind.HUD, CameraSlots.KEY_SURROUND);
    }

    private android.graphics.SurfaceTexture takeKeptCanvas(ChannelRules.Kind kind, String cameraKey) {
        android.graphics.SurfaceTexture texture = keptOf(kind).remove(cameraKey);
        if (texture == null) {
            return null;
        }
        boolean held = scheduler.reclaim(cameraKey, new ChannelRules.Out(kind, texture));
        if (!held || texture.isReleased()) {
            return null;   // 调度已经放了（不会走到这里：放的时候先从表里拿掉）
        }
        AppLog.i(TAG, "Kept " + kind + " canvas for " + cameraKey + " taken back by a new view");
        return texture;
    }

    /**
     * 调度说这一块留着的画布已经离开了会话（被「改输出」顺带摘掉、随相机关了、或者调度停了）：放掉它（主线程）。
     * 没人接回才会走到这里 —— 接回的已经从调度那里拿走了。
     */
    private void releaseKeptCanvas(ChannelRules.Kind kind, String cameraKey, android.graphics.SurfaceTexture texture) {
        Map<String, android.graphics.SurfaceTexture> kept = keptOf(kind);
        if (kept.get(cameraKey) == texture) {
            kept.remove(cameraKey);
        }
        SingleCamera camera = cameras.get(cameraKey);
        if (camera != null) {
            withdrawCanvas(camera, kind, texture);   // 留下时已经撤回了；这里只防万一，撤回过的什么都不做
        }
        texture.release();
        if (kind == ChannelRules.Kind.MIRROR) {
            com.kooo.evcam.blackbox.BlackBox.note("后视镜留下的画布没人接回：" + roleName(cameraKey)
                    + "的会话里已经没有它，放掉" + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        } else if (kind == ChannelRules.Kind.SIDE || kind == ChannelRules.Kind.HUD) {
            com.kooo.evcam.blackbox.BlackBox.note("盲区画面" + (kind == ChannelRules.Kind.HUD ? "（HUD）" : "")
                    + "留下的画布没人接回：环视的会话里已经没有它，放掉" + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        } else {
            com.kooo.evcam.blackbox.BlackBox.note("主界面留下的预览画布没人接回：" + roleName(cameraKey)
                    + " 那一块已经离开会话，放掉" + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        }
    }

    /**
     * 手动触发所有已有 previewSize 的摄像头的 PreviewSizeCallback。
     * 管线属于应用（CameraManagerHolder 建，比主界面活得久）：各路的画面尺寸可能在主界面挂上回调（旋转变换等）之前就算出来了。
     * 主界面接上建好的管线后调用此方法，补偿缺失的回调触发。
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
     * 按分好的槽位建各路相机、交给调度（主线程）。只有 {@link CameraManagerHolder} 调（大纲 §2 阶段 6：管线只在那里建，
     * 槽位照存下的映射或相机列表分，相机列表、合成流探测都在相机线程上做完了才到这里）。画布一律是 null：主界面只声明自己的画布
     * （{@link #declarePreviews}）。某一路的 id 传 null 就不建那一路。做完算建好了（{@link #isBuilt}）。
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
                // 录着的一路的相机丢了：不在这里判。调度在那一轮最后告诉录像宿主（ChannelScheduler.Recording），那一路自己退出
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
                // 画面尺寸算出来了（不开设备先算的、或者开相机时算的）：录像计划里还没有录制器的那一路现在建
                laneHandler.post(() -> onSizeResolved(cameraId));
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
        final Set<String> enabled = enabledLanes == null ? null : new java.util.LinkedHashSet<>(enabledLanes);
        channelHandler.post(() -> {
            scheduler.setLanes(ports, enabled);
            for (SingleCamera camera : active) {
                camera.resolveSize();
            }
        });

        camerasBuilt = true;
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

    /**
     * 诊断报告 2.3.2 每一路「来历：」那一段（{@link CameraHistory}，2.11.5 第 D1 项）：「上一次重置以来开过 5 台（上次重置：它在相机服务里没了又回来，
     * 10-14 21:01:55）；最近开的那一台是第 5 台；最后一次关完 3 分 2 秒前、关了 0.1 秒（dark-release）」。
     */
    private static String historyText(CameraHistory.Camera h) {
        if (h == null) {
            return "没有记录（2.11.5 起记：车机重新初始化以来开过几台、最后一次关完）";
        }
        java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        if (h.reset == null) {
            sb.append("记录开始以来开过 ").append(h.opens).append(" 台（记录从 ")
                    .append(h.sinceWall > 0 ? clock.format(new Date(h.sinceWall)) : "?").append(" 起，之前的重置不知道）");
        } else {
            sb.append("上一次重置以来开过 ").append(h.opens).append(" 台（上次重置：")
                    .append(h.reset == CameraHistory.Reset.BOOT ? "车机开机"
                            : h.reset == CameraHistory.Reset.TAKEN ? "别的程序拿了它又空出来" : "它在相机服务里没了又回来")
                    .append(h.resetWall > 0 ? "，" + clock.format(new Date(h.resetWall)) : "").append("）");
        }
        sb.append("；最近开的那一台是").append(h.lastOrdinal > 0 ? "第 " + h.lastOrdinal + " 台" : "还没开过");
        sb.append("；最后一次关完").append(h.closedElapsed < 0 ? "：没有记录"
                : " " + timeText(android.os.SystemClock.elapsedRealtime() - h.closedElapsed) + "前、关了 " + seconds(h.closeMs)
                + (h.closeWhy == null ? "" : "（" + h.closeWhy + "）"));
        return sb.toString();
    }

    /** 点火码的记号：ACC、ON、DRIVING，认不出的写十六进制（统计、关座舱那几行共用）。 */
    static String ignitionCode(int code) {
        if (code == com.kooo.evcam.telemetry.Signal.IGNITION_ACC) {
            return "ACC";
        }
        if (code == com.kooo.evcam.telemetry.Signal.IGNITION_ON) {
            return "ON";
        }
        if (code == com.kooo.evcam.telemetry.Signal.IGNITION_DRIVING) {
            return "DRIVING";
        }
        return String.format(Locale.US, "0x%08X", code);
    }

    /** 黑匣子里的叫法：环视 / 后座舱 / 前座舱；别的 key 原样。主界面接回留着的画布那一行也用它。 */
    public static String roleName(String key) {
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
     * 这一份管线的相机都关好了：调度的账上每一路都关着，相机层也没有一路开着、在开、在关。
     * （熄屏录制放唤醒锁以前轮询它；2.11 阶段 6 起改等调度发出的「关完」，{@link ChannelScheduler#whenClosedEverywhere}。）
     */
    public boolean allCamerasClosed() {
        return scheduler.allClosed();
    }

    /**
     * 此刻能不能用相机（大纲 §0 名词「能用相机」）：主界面在前，或者前台服务是主界面在前时起的、那时刷新过 ——
     * 安卓 12 起后台才起的前台服务用不了相机（lifecycle-spec §3）。通道调度读的是同一对事实（{@link ChannelSide} 的
     * mainForeground、serviceStartedWhileVisible）；录像协调器在屏幕亮着、打开录像意图和亮屏时
     * （RecordingCoordinator.bringUpMainIfCamerasUnusable）照它决定要不要先把主界面拉起来。
     * 哪个线程都能读。
     */
    public static boolean cameraUsableNow() {
        return StallWatch.isForeground() || com.kooo.evcam.CameraForegroundService.startedWhileVisible();
    }

    /** 这一份管线的通道调度（录像、后视镜读它的事实：condition、usable；诊断报告读 {@link #describeChannel}）。 */
    public ChannelScheduler channelScheduler() {
        return scheduler;
    }

    // ------------------------------------------------------------------ 熄屏放开座舱：给熄屏小结、诊断报告的几个数

    /**
     * 这一段熄屏里座舱放开完的那一刻（elapsedRealtime，含深睡；设计 v3.1 C16，协调器写熄屏小结「座舱 熄屏后 +N ms 放开完」用）：
     * 熄屏以后第一轮关了座舱的那一轮关完时 —— 平常就是熄屏放开座舱的那一轮（{@link ChannelRules.Why#DARK_RELEASE}），
     * 安卓直接熄屏、车机马上要睡的那种是按次序关的那一轮。0 = 还没有：熄屏时座舱就没开着（{@link #cabinsOpenAtDark}），
     * 或者还没关完。每次熄屏（ScreenState 的熄屏那一下，{@link #newDarkPeriod}）从头算，亮屏后留着到下一次熄屏。
     * 哪个线程都能读。
     */
    public long cabinsReleasedAtElapsed() {
        return cabinsReleasedAt;
    }

    /** 这一段熄屏开始的那一刻座舱（环视以外的几路）开着、在开、或者在关：没开着的就没什么可放开。哪个线程都能读。 */
    public boolean cabinsOpenAtDark() {
        return cabinsOpenAtDark;
    }

    /**
     * 屏幕黑了多久，按通道的算法（{@link ChannelRules#darkForChannel} 要的那个数，调度经 {@link ChannelSide#darkForMs} 读它）：
     * 起点不明交 -1（按黑满 30 秒算）。车辆信号读到时已经黑着的（进程刚起来、之前读不到）也是起点不明 —— ScreenState 那时
     * 从读到的那一刻起算「熄屏后 +N ms」，屏幕其实早就黑了（熄屏小结写「起点不明」的就是这一种）。哪个线程都能调。
     */
    static long channelDarkForMs() {
        return com.kooo.evcam.screen.ScreenState.source() == com.kooo.evcam.screen.ScreenState.Source.VEHICLE_READ
                ? -1 : com.kooo.evcam.screen.ScreenState.darkForMs();
    }

    /**
     * 此刻通道眼里屏幕黑了（{@link ChannelRules#darkForChannel}：黑满 30 秒、起点不明、或者车机要睡）—— 座舱放开、预览不算、
     * 不保持。和调度用的是同一句、同一组事实（{@link #channelDarkForMs}）；协调器写黑匣子时问它，不自己再判一遍
     * （熄屏判的那一行「座舱放开 / 座舱黑满 30 秒后放开」、黑着开录的那几行）。哪个线程都能调。
     */
    public static boolean channelCountsDark() {
        return ChannelRules.darkForChannel(com.kooo.evcam.screen.ScreenState.dark(), channelDarkForMs(),
                com.kooo.evcam.screen.ScreenState.androidOff());
    }

    /**
     * 调度最近一轮把屏幕算作黑了（{@link ChannelScheduler#snapshot}，还没跑过一轮算没有）。亮屏那一下（协调器写熄屏小结、
     * 相机层的亮屏监听，都在亮屏后的那一轮之前 —— 叫醒调度只是排一轮）读的就是这一段熄屏最后的样子：不是，就是屏幕黑了
     * 不到 30 秒又亮了，通道一步没动（项目所有者 2026-10-13「黑满 30 秒再放」）。主线程。
     */
    public boolean channelCountedDark() {
        ChannelScheduler.Snapshot s = scheduler.snapshot();
        return s != null && !s.screenOn;
    }

    /**
     * 熄屏了（ScreenState 的熄屏那一下，主线程）：这一段熄屏的座舱放开从头记 —— 那一刻座舱开着没有，之后第一轮关了座舱的
     * 那一轮关完时记下时刻（{@link ChannelSide#closeRound}）。
     */
    private void newDarkPeriod() {
        boolean open = false;
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            if (!CameraSlots.KEY_SURROUND.equals(entry.getKey()) && entry.getValue().holdsOrIsOpening()) {
                open = true;
                break;
            }
        }
        cabinsOpenAtDark = open;
        cabinsReleasedAt = 0;
        awaitingCabinRelease = open;
    }

    /**
     * 别的程序拿走了一路相机（{@link CameraContention} 记争用那一行时调；哪个线程都能调）：拿走的不是环视、环视那时开着，
     * 就在拿走之后 {@link ChannelScheduler#AFTERMATH_MS} 写一行环视怎样（设计 v3 §6 L5）—— 最长断帧、会话动没动、
     * 在不在录、是不是被挡着（BLOCKED）。在不在录都写：10-11 只录环视那一晚车机拿了 6 次相机 1，环视一次没断；
     * 10-12 我们拿着相机 1 的 4 次里 3 次环视在 6–14 秒后断了 35–43 秒 —— 下一次上车要看熄屏放开座舱之后是哪一种。
     * 同一路相机一次只等一件。量在调度里（{@link ChannelScheduler#watchAfter}，和关完一轮之后再量的那一行同一份）：
     * 这 30 秒里我们自己关了、改了、救了环视的，只量到那一刻、写明是我们动的 —— 不把我们自己的关写成环视断帧（审查 rev3 B2）。
     *
     * @param tookAtElapsed 拿走的那一刻（elapsedRealtime，含深睡）
     */
    void watchSurroundAfterTake(String cameraId, long tookAtElapsed) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post(() -> watchSurroundAfterTake(cameraId, tookAtElapsed));
            return;
        }
        SingleCamera surround = cameras.get(CameraSlots.KEY_SURROUND);
        if (released || cameraId == null || surround == null || cameraId.equals(surround.getCameraId())
                || !surround.holdsOrIsOpening()) {
            return;
        }
        long ago = Math.max(0, android.os.SystemClock.elapsedRealtime() - tookAtElapsed);
        // 断帧记录、调度用的是开机以来、不含深睡的钟：拿走的那一刻换过去
        long since = android.os.SystemClock.uptimeMillis() - ago;
        scheduler.watchAfter("take " + cameraId, CameraSlots.KEY_SURROUND, since,
                others -> noteSurroundAfterTake(cameraId, surround, others));
    }

    /**
     * 别的程序拿走相机 {@code cameraId} 之后到点了（主线程，调度量好的那一份）：环视这段时间里怎样，一行。
     * 环视这时已经不开着（我们关了、或者它也丢了）就写「环视已关」，不写在没在录。
     */
    private void noteSurroundAfterTake(String cameraId, SingleCamera surround, List<ChannelScheduler.Other> others) {
        if (released || cameras.get(CameraSlots.KEY_SURROUND) != surround) {
            return;
        }
        CameraTaken.Verdict mark = markOf(CameraSlots.KEY_SURROUND);
        RecordingLanes.State state = isRecording ? lanes.state(CameraSlots.KEY_SURROUND) : null;
        String recording = !surround.holdsOrIsOpening() ? "环视已关"
                : !isRecording ? "没在录像"
                : state == null ? "环视不在这一次录像里"
                : state == RecordingLanes.State.LIVE ? "环视在录" : "环视没在录";
        com.kooo.evcam.blackbox.BlackBox.noteImportant("争用：相机 " + cameraId + " 被拿走之后 "
                + (ChannelScheduler.AFTERMATH_MS / 1000) + " 秒：" + gapsText(others)
                + (mark == CameraTaken.Verdict.BLOCKED ? "；环视被挡着（BLOCKED：等被拿着的那一路放开）"
                : mark == CameraTaken.Verdict.TAKEN ? "；环视也被别的程序拿着" : "")
                + "；" + recording + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
    }

    /** 调度此刻记着这一路被拿着（TAKEN）还是被挡着（BLOCKED）；没有是 null（读调度最后一轮的快照）。 */
    private CameraTaken.Verdict markOf(String key) {
        ChannelScheduler.Snapshot s = scheduler.snapshot();
        if (s == null) {
            return null;
        }
        for (ChannelScheduler.LaneView lane : s.lanes) {
            if (lane.key.equals(key)) {
                return lane.mark;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 诊断报告的「Channel」一节

    /**
     * 诊断报告的「Channel」一节（DiagnosticsCollector 接在相机服务视角那张表后面；哪个线程都能调）：通道调度此刻怎么看每一路 ——
     * 该开还是实际开着；会话里的输出分要的、留着的、死的，各从什么时候起；被拿着（TAKEN / BLOCKED）、不在，从什么时候起；
     * 最近一次开不起来、丢了和它的判定；救援梯子；最后一次问相机服务的时间和答案。另起两行：能不能用相机、为什么；
     * 手上正在做哪一步、在等什么（大纲 §2 阶段 8）。读的是调度每一轮最后记的那一份快照（{@link ChannelScheduler#snapshot()}），
     * 不碰调度的账。
     */
    public String describeChannel() {
        ChannelScheduler.Snapshot s = scheduler.snapshot();
        if (s == null) {
            return "调度还没跑过一轮\n";
        }
        long now = android.os.SystemClock.uptimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("快照：").append(timeText(now - s.at)).append("前（调度每一轮最后记一份）")
                .append(s.stopped ? "；调度已停（退出之后都关完了）" : "").append('\n');
        sb.append("登记表：").append(channelSide.holderCodes(s.holders))
                .append(s.recordKeys.isEmpty() ? "" : "（录 " + roles(s.recordKeys) + "）")
                .append("；").append(!s.screenOn ? "熄屏" + darkSinceText()
                        // 屏幕黑了还不到 30 秒：通道照亮着算（ChannelRules.darkForChannel，项目所有者 2026-10-13）
                        : com.kooo.evcam.screen.ScreenState.dark() ? "熄屏" + darkSinceText() + "，不到 "
                        + (ChannelRules.HOLD_MS / 1000) + " 秒：开着的相机照旧、关着的座舱不开，黑满 "
                        + (ChannelRules.HOLD_MS / 1000) + " 秒放开座舱，环视照哨兵模式留或放" : "亮屏")
                .append("；前台服务").append(s.foregroundService ? "在" : "不在").append('\n');
        if (!s.screenOn) {
            sb.append("熄屏放开座舱：").append(cabinReleaseText()).append('\n');
            sb.append("熄屏时环视：").append(surroundInDarkText(s)).append('\n');
        }
        sb.append("能用相机：").append(usableText(s, now)).append('\n');
        sb.append("手上：").append(stepText(s.step, now)).append('\n');
        sb.append("在等：").append(waitText(s)).append('\n');
        if (s.sleepSince != 0) {
            sb.append("车机要睡：").append(timeText(now - s.sleepSince)).append("前起（不保持；手上那一步最多再等 ")
                    .append(ChannelRules.SLEEP_GRACE_MS / 1000).append(" 秒，然后按次序关）").append('\n');
        }
        if (s.exitSince != 0) {
            sb.append("退出：").append(timeText(now - s.exitSince)).append("前起（登记表作废，只关）").append('\n');
        }
        if (s.otherClosingSince != 0) {
            sb.append("另一份管线在关相机：").append(timeText(now - s.otherClosingSince)).append("前起").append('\n');
        }
        sb.append("别人引起的变化：").append(s.lastOthersChangeAt == 0 ? "没有过"
                : "上一次 " + timeText(now - s.lastOthersChangeAt) + "前（安静满 " + (ChannelRules.QUIET_MS / 1000)
                + " 秒才开、改输出、救）").append('\n');
        sb.append("问相机服务：").append(askText(s, now)).append('\n');
        for (ChannelScheduler.LaneView lane : s.lanes) {
            appendLane(sb, lane, now);
        }
        return sb.toString();
    }

    /**
     * 熄屏是谁判的、多久了：「（车辆信号，12 分 3 秒前起）」—— 熄屏 v3 起熄屏是屏幕真黑了（安卓熄屏，或者车辆说黑，设计 v3 §1、§6），
     * 车主走开后安卓还亮着的那几分钟也算。写法和报告「3.1 熄屏」那一节是同一处（{@code DiagnosticsCollector.darkText}）。
     * 读 ScreenState 此刻一次取齐的那一份；快照之后又亮了的是空的。
     */
    private static String darkSinceText() {
        com.kooo.evcam.screen.ScreenState.Now n = com.kooo.evcam.screen.ScreenState.describeNow();
        return n.dark ? "（" + com.kooo.evcam.zeekr.DiagnosticsCollector.darkText(n.source, n.darkForMs) + "）" : "";
    }

    /** 熄屏时环视留不留（2026-10-27）：在开车照登记表；留着 / 放开，和哨兵模式、「熄屏持续录制」。 */
    private static String surroundInDarkText(ChannelScheduler.Snapshot s) {
        String facts = "（哨兵 " + (s.sentry == null ? "读不到" : s.sentry == 0 ? "关" : s.sentry == 1 ? "开" : "布防")
                + "，熄屏持续录制" + (s.keepWhenDark ? "开" : "关") + "）";
        if (s.driving) {
            return "在开车，照登记表" + facts;
        }
        return (s.surroundKept ? "留着（录像要它才开）" : "放开") + facts;
    }

    /** 这一段熄屏里座舱放开了没有：「熄屏后 +212ms 放开完」/「熄屏时座舱没开着」/「还没关完」（{@link #cabinsReleasedAtElapsed}）。 */
    private String cabinReleaseText() {
        if (!cabinsOpenAtDark) {
            return "熄屏时座舱没开着";
        }
        long releasedAt = cabinsReleasedAt;
        if (releasedAt == 0) {
            return "还没关完";
        }
        long darkMs = com.kooo.evcam.screen.ScreenState.darkForMs();
        long now = android.os.SystemClock.elapsedRealtime();
        return darkMs < 0 ? timeText(now - releasedAt) + "前放开完"
                : "熄屏后 +" + (releasedAt - (now - darkMs)) + "ms 放开完";
    }

    /** 能不能用相机、为什么（和调度判「能用相机」是同一对事实）。 */
    private static String usableText(ChannelScheduler.Snapshot s, long now) {
        if (s.unusableSince != 0) {
            return "不能 —— 开相机时报了用不了相机（" + timeText(now - s.unusableSince) + "前），等主界面回到前台";
        }
        if (s.mainForeground) {
            return "能 —— 主界面在前";
        }
        if (s.serviceStartedWhileVisible) {
            return "能 —— 前台服务是主界面在前时起的（或那时刷新过）";
        }
        return "不能 —— 主界面不在前，前台服务" + (s.foregroundService
                ? "是后台起的（安卓 12 起后台才起的前台服务用不了相机）" : "不在");
    }

    /** 手上那一步：做什么、哪一路、带哪些输出、做了多久。 */
    private String stepText(ChannelRules.Step step, long now) {
        if (step == null) {
            return "没有";
        }
        String keys = roles(step.keys);
        String outputs = channelSide.kindNames(kindsOf(step.outputs));
        String what;
        switch (step.move) {
            case OPEN:
                what = "开 " + keys + "（输出 " + outputs + "）";
                break;
            case APPLY:
                what = "改输出 " + keys + "（改成 " + outputs + "）";
                break;
            case RESCUE:
                what = "救 " + keys + "：" + (step.rescue == ChannelRules.Rescue.REBUILD ? "重建会话" : "重开")
                        + "（输出 " + outputs + "）";
                break;
            default:
                what = "关 " + keys;
                break;
        }
        return what + "，做了 " + seconds(now - step.startedAt) + "（最多 " + (step.maxMs / 1000) + " 秒）";
    }

    /** 这一轮在等什么；有一路因为现在用不了相机先不开的，也写上。 */
    private String waitText(ChannelScheduler.Snapshot s) {
        String deferred = s.deferredKey == null ? "" : "；现在用不了相机，先不开 " + roleName(s.deferredKey);
        if (s.move != ChannelRules.Move.WAIT) {
            return "没在等" + deferred;
        }
        String key = s.key == null ? "" : roleName(s.key);
        String text;
        switch (s.why) {
            case STEP:
                text = "手上那一步做完";
                break;
            case LOSING:
                text = key + " 正被别人接手，我们这边的关还没关完（别的路这时一步都不动）";
                break;
            case BUSY:
                text = key + " 在途（相机层手上有动作）";
                break;
            case OTHER_INSTANCE:
                text = "另一份管线把相机关完（换车型、退出后马上又开）";
                break;
            case SURROUND_CLOSING:
                text = "环视先关完，再关座舱";
                break;
            case JUDGING:
                text = "判 " + key + " 那一次丢了 / 开不起来（关完再等 " + ChannelRules.JUDGE_DELAY_MS + " ms）";
                break;
            case QUIET:
                text = "通道安静（相机服务 " + (ChannelRules.QUIET_MS / 1000) + " 秒内报过别人引起的变化），再"
                        + channelSide.pendingText(s.pending, s.key);
                break;
            case RECORD_OUTPUT:
                text = "开 " + key + " 之前等它的录像输出（录制器在建）";
                break;
            case SCREEN_UNKNOWN:
                text = "屏幕知道黑没黑（进程刚起来 / 安卓刚亮），再开 " + key;
                break;
            case CLOSING:
                text = "退出 / 车机要睡：等 " + key + " 关完";
                break;
            default:
                text = s.why.name();
                break;
        }
        return text + (s.waitMs < 0 ? "" : "，最多还要 " + seconds(s.waitMs)) + deferred;
    }

    /** 最后一次问相机服务：什么时候、问成了没有。 */
    private static String askText(ChannelScheduler.Snapshot s, long now) {
        if (s.askedAt == 0) {
            return "还没问过（被拿着的一路有人要时每 " + (ChannelRules.ASK_EVERY_MS / 1000) + " 秒问一次，只问，不开）";
        }
        String asked = "上一次 " + timeText(now - s.askedAt) + "前问的";
        if (s.answeredAt < s.askedAt) {
            return asked + "，还没答回来";
        }
        return asked + "，" + (s.answered ? "问成了（收到了回放）" : "问不成（一声回放都没有：多半没连上相机服务）");
    }

    /** 一路：调度怎么看它，几行。 */
    private void appendLane(StringBuilder sb, ChannelScheduler.LaneView v, long now) {
        sb.append("  ").append(roleName(v.key)).append("（相机 ").append(v.cameraId).append("）：")
                .append(conditionText(v)).append('\n');
        sb.append("    该开：").append(wantText(v, now)).append("；实际：").append(deviceText(v, now))
                .append(v.cannotOpen && !v.stuckClose ? "；现在用不了相机，主界面回到前台之前不开" : "").append('\n');
        if (v.closingSince != 0) {
            // 相机层有一次关没返回（项目所有者 2026-10-28）：返回之前不开它，关着、在开的也不救；满 60 秒别的路不再等它。
            // 时刻是这一台一直有关没返回的那一刻（链头那一次），上面又登记一次关不从头算
            sb.append("    在关：上一次关还没返回，").append(timeText(now - v.closingSince)).append("前开始")
                    .append(v.closeBusy.isEmpty() ? "" : "（" + v.closeBusy + "）").append("；返回之前不开它")
                    .append(v.stuckClose ? "，别的路不再等它" : "").append('\n');
        }
        sb.append("    会话：").append(outputsText(v, now)).append('\n');
        sb.append("    被拿着：").append(v.mark == null ? "没有"
                : (v.mark == CameraTaken.Verdict.TAKEN ? "别的程序拿着（TAKEN）" : "被拿着的另一路挡着（BLOCKED）")
                + "，" + timeText(now - v.markSince) + "前起")
                .append(v.absent ? "；不在：相机服务的缓存里没有它，" + timeText(now - v.serviceWordAt) + "前起" : "")
                .append('\n');
        sb.append("    相机服务最后一句：").append(v.absent ? "不在" : v.serviceFree == null ? "没说过"
                : (v.serviceFree ? "空闲" : "被占用") + (v.serviceWordOurs ? "（我们引起的）" : "（别人引起的）")
                + "，" + timeText(now - v.serviceWordAt) + "前").append('\n');
        sb.append("    最近一次开不起来：").append(judgedText(v.lastOpenFailure, now)).append('\n');
        sb.append("    最近一次丢了：").append(judgedText(v.lastLoss, now)).append('\n');
        sb.append("    救援梯子：").append(ladderText(v, now)).append('\n');
        sb.append("    来历：").append(historyText(StepLog.history().camera(v.cameraId))).append('\n');
        if (v.asks > 0 || v.lastAnswer != null) {
            ChannelScheduler.Answer a = v.lastAnswer;
            sb.append("    问相机服务：被拿着以来问了 ").append(v.asks).append(" 次")
                    .append(a == null ? "" : "；最后一次（第 " + a.n + " 次）" + timeText(now - a.at) + "前答："
                            + (a.absent ? "还是不在" : Boolean.TRUE.equals(a.free) ? "说空闲了" : "还被占着"))
                    .append('\n');
        }
    }

    private static String conditionText(ChannelScheduler.LaneView v) {
        switch (v.condition) {
            case STREAMING:
                return "在出画面";
            case TAKEN:
                return "被拿着";
            case RESCUING:
                return "在救";
            case GAVE_UP:
                return "停手了";
            default:
                return v.shouldOpen ? "在起来" : "没在用";
        }
    }

    /** 该开还是不该开、为谁；保持的写没人要多久了；熄屏放开的座舱、环视先说是熄屏（设计 v3 §4；环视 2026-10-27）。 */
    private String wantText(ChannelScheduler.LaneView v, long now) {
        if (v.releasedByDark) {
            return "不该开（" + channelSide.darkText(v.key) + "）";
        }
        String unwanted = v.unwantedSince == 0 ? "" : " " + timeText(now - v.unwantedSince);
        if (!v.wantedBy.isEmpty()) {
            return v.shouldOpen ? "是（为 " + channelSide.holderNames(v.wantedBy) + "）"
                    : "不该开（退出 / 车机要睡：只关）";
        }
        return v.holding ? "保持（没人要" + unwanted + "，满 " + (ChannelRules.HOLD_MS / 1000) + " 秒关）"
                : "不该开（没人要" + unwanted + "）";
    }

    /** 设备此刻在哪一步、从什么时候起；相机层手上有动作也写上。 */
    private static String deviceText(ChannelScheduler.LaneView v, long now) {
        String since = v.deviceSince == 0 ? "" : " " + timeText(now - v.deviceSince);
        String busy = v.busySince == 0 ? "" : "，相机层手上有动作 " + seconds(now - v.busySince);
        switch (v.device) {
            case OPEN:
                return "开着" + since + busy;
            case OPENING:
                return "在开" + since;
            case CLOSING:
                return "在关" + since;
            case LOSING:
                return "被接手（丢了，我们这边的关还没关完）" + since;
            default:
                return "关着" + since;
        }
    }

    /** 会话里的输出：要的、留着的、死的，各从什么时候起；交给管线留着、等离开会话再放的也写上。 */
    private String outputsText(ChannelScheduler.LaneView v, long now) {
        StringBuilder sb = new StringBuilder();
        if (v.sessionSince == 0 || v.outputs.isEmpty()) {
            sb.append("没有");
        } else {
            sb.append("配好 ").append(timeText(now - v.sessionSince)).append("前");
            ChannelRules.OutState[] order = {
                    ChannelRules.OutState.WANTED, ChannelRules.OutState.KEPT, ChannelRules.OutState.DEAD};
            String[] names = {"要的", "留着的", "死的"};
            for (int i = 0; i < order.length; i++) {
                List<String> items = new ArrayList<>();
                for (ChannelScheduler.OutputState state : v.outputs) {
                    if (state.state == order[i]) {
                        items.add(channelSide.kindNames(java.util.EnumSet.of(state.kind)) + "（"
                                + timeText(now - state.since) + "前起）");
                    }
                }
                sb.append("；").append(names[i]).append(' ').append(items.isEmpty() ? "没有" : String.join("、", items));
            }
        }
        if (!v.handedOver.isEmpty()) {
            sb.append("；交给管线留着、等离开会话再放：").append(channelSide.kindNames(v.handedOver));
        }
        return sb.toString();
    }

    /** 一次判定：多久以前、怎么丢的 / 为什么开不起来、判成什么。 */
    private String judgedText(ChannelScheduler.Judgement j, long now) {
        if (j == null) {
            return "没有";
        }
        boolean loss = j.origin == ChannelRules.Failure.Origin.LOSS;
        String cause = loss ? channelSide.lossText(j.reason)
                + (j.closeMs >= 0 ? "，关完用了 " + seconds(j.closeMs) : "")
                : j.reason != null && j.reason.source == CameraTaken.Reason.Source.NOT_LISTED ? "不在相机列表里"
                : channelSide.openFailText(j.reason)
                + (j.origin == ChannelRules.Failure.Origin.REOPEN ? "（救援里的重开）" : "");
        String verdict;
        switch (j.verdict) {
            case TAKEN:
                verdict = "别的程序拿着它（TAKEN）";
                break;
            case BLOCKED:
                verdict = "它自己空着，别的程序拿着相机 " + String.join("、", j.takenIds) + "，也算被拿着（BLOCKED）";
                break;
            case UNUSABLE:
                verdict = j.unusableThen == ChannelRules.UnusableThen.LADDER
                        ? "现在用不了相机，主界面一直在前：按普通故障救"
                        : j.unusableThen == ChannelRules.UnusableThen.MAIN_CAME_BACK
                        ? "现在用不了相机，主界面已经回到前台：不计次数，不用再等"
                        : "现在用不了相机，不计次数，等主界面回到前台";
                break;
            default:
                verdict = "普通故障，按救援的节奏救";
                break;
        }
        return timeText(now - j.at) + "前，" + cause + " → " + verdict;
    }

    /** 救援梯子：待判、等救、第几次第几轮、歇着、停手了。 */
    private static String ladderText(ChannelScheduler.LaneView v, long now) {
        if (v.ladderStopped) {
            return "停手了（救了 " + CameraLiveness.MAX_CYCLES + " 轮没用"
                    + (v.stoppedAt == 0 ? "" : "，" + timeText(now - v.stoppedAt) + "前")
                    + "；等登记表变了、相机服务报它空闲、或者亮屏再试）";
        }
        if (v.resting) {
            return "连着救 " + CameraLiveness.MAX_ATTEMPTS + " 次没用，歇 " + (CameraLiveness.COOL_OFF_MS / 1000)
                    + " 秒（已 " + v.cycles + " 轮）";
        }
        if (v.judging) {
            return "丢了 / 开不起来，等判";
        }
        if (v.attempts > 0) {
            return "第 " + v.attempts + " 次（第 " + (v.cycles + 1) + " 轮）";
        }
        return v.lostForRescue ? "丢了、判成普通故障，等救" : "没在救";
    }

    /** 几样输出的种类。 */
    private static Set<ChannelRules.Kind> kindsOf(Set<ChannelRules.Out> outs) {
        Set<ChannelRules.Kind> kinds = java.util.EnumSet.noneOf(ChannelRules.Kind.class);
        for (ChannelRules.Out out : outs) {
            kinds.add(out.kind);
        }
        return kinds;
    }

    /** 录像要哪几路：这一次录像的计划；计划还没定（开录之前）就是启用的全部 —— 和以前一样。 */
    private Set<String> recordPlanKeys() {
        Set<String> keys = new java.util.LinkedHashSet<>();
        if (!CameraNeeds.current().isHeld(CameraNeeds.Holder.RECORDING)) {
            return keys;
        }
        keys.addAll(recordingPlan.isEmpty() ? enabledActiveKeys() : recordingPlan.keySet());
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
        public long closingSince() {
            return camera.closingSince();
        }

        @Override
        public SingleCamera.LastClose lastClose() {
            return camera.lastClose();
        }

        @Override
        public SessionCounts.Snapshot sessionCounts() {
            return camera.sessionCounts();
        }

        @Override
        public long lastResultAt() {
            return camera.lastResultAt();
        }

        @Override
        public long captureFailures() {
            return camera.captureFailures();
        }

        @Override
        public long lostBuffers() {
            return camera.lostBuffers();
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
        public boolean stillCarries(ChannelRules.Out out) {
            return camera.stillCarries(out);
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

        @Override
        public void outputStates(Map<ChannelRules.Out, ChannelScheduler.OutputState> states) {
            camera.noteOutputStates(states);
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

        /** 上一轮读到的「超级镜子要后座舱」：变了记一行黑匣子。 */
        private volatile boolean mirrorCabinNoted;
        /** 读切换范围用（第一次要时建：这个对象跟着管线的字段一起建，那时 context 还没给）。 */
        private volatile com.kooo.evcam.AppConfig config;

        /** 设置（第一次要时建）。 */
        private com.kooo.evcam.AppConfig config() {
            com.kooo.evcam.AppConfig settings = config;
            if (settings == null) {
                settings = new com.kooo.evcam.AppConfig(context);
                config = settings;
            }
            return settings;
        }

        @Override
        public boolean mirrorWantsCabinRear() {
            boolean wants = config().rearViewWantsCabinRear();
            if (wants != mirrorCabinNoted) {
                mirrorCabinNoted = wants;
                com.kooo.evcam.blackbox.BlackBox.note(wants
                        ? "超级镜子切换范围是「后视 + 后座舱」：登记着超级镜子时后座舱也有人要"
                        : "超级镜子切换范围不含后座舱：超级镜子只要环视");
            }
            return wants;
        }

        @Override
        public boolean screenOn() {
            return !com.kooo.evcam.screen.ScreenState.dark();
        }

        /** 屏幕黑了多久；起点不明交 -1（规则按黑满 30 秒算）。算法只在 {@link MultiCameraManager#channelDarkForMs} 一处。 */
        @Override
        public long darkForMs() {
            return channelDarkForMs();
        }

        @Override
        public boolean androidOff() {
            return com.kooo.evcam.screen.ScreenState.androidOff();
        }

        /** 屏幕知道的那一刻要叫醒调度：已经排着的不再排（主线程）。 */
        private boolean knownWakeQueued;

        /**
         * 屏幕知道黑没黑（ScreenState.known）。不知道时排一下（ScreenState.whenKnown，主线程上叫）：知道了叫醒调度，
         * 不等下一个两秒（项目所有者 2026-10-27：屏幕不知道时不开相机，上限是屏幕判断自己的）。
         */
        @Override
        public boolean screenKnown() {
            if (com.kooo.evcam.screen.ScreenState.known()) {
                return true;
            }
            if (!knownWakeQueued) {
                knownWakeQueued = true;
                com.kooo.evcam.screen.ScreenState.whenKnown(() -> {
                    knownWakeQueued = false;
                    scheduler.wake();
                });
            }
            return false;
        }

        /** 哨兵模式（车辆信号核心那一份，熄屏判断登记的、一直在读）；车辆接口没连着算读不到。 */
        @Override
        public Integer sentry() {
            com.kooo.evcam.telemetry.Telemetry t = com.kooo.evcam.telemetry.Telemetry.get();
            return t.carLinkConnected() ? t.latest().sentry : null;
        }

        /** 「熄屏持续录制」（开发者「熄屏录制（阻止休眠）」不算，lifecycle-spec §3.1）。每一轮现读：黑着时没人改得了它。 */
        @Override
        public boolean keepRecordingWhenDark() {
            return config().isScreenOffKeepRecording();
        }

        /** 在开车（ScreenOffPlan.driving，和录像那张表同一句）；车辆接口没连着算没在开车。 */
        @Override
        public boolean driving() {
            com.kooo.evcam.telemetry.Telemetry t = com.kooo.evcam.telemetry.Telemetry.get();
            if (!t.carLinkConnected()) {
                return false;
            }
            com.kooo.evcam.telemetry.VehicleState v = t.latest();
            return com.kooo.evcam.recording.ScreenOffPlan.driving(v.gear, v.speedKmh);
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
            return recordPrepOf(key);
        }

        /**
         * 车上的几样事实（ChannelScheduler.Vehicle）：哨兵原值读车辆信号那一份快照，点火 / 主驾门 / 屏幕电源事件多久以前变过读
         * {@link com.kooo.evcam.telemetry.SignalAges}，进程的重要性读监测线程缓存的 —— 都不进车辆接口、不进系统服务（主线程）。
         */
        @Override
        public ChannelScheduler.Vehicle vehicle() {
            try {
                com.kooo.evcam.telemetry.Readings r = com.kooo.evcam.telemetry.Telemetry.get().readings();
                long now = android.os.SystemClock.elapsedRealtime();
                return new ChannelScheduler.Vehicle(r.code(com.kooo.evcam.telemetry.Signal.SENTRY_MODE),
                        r.code(com.kooo.evcam.telemetry.Signal.IGNITION),
                        com.kooo.evcam.telemetry.SignalAges.ageMs(com.kooo.evcam.telemetry.Signal.IGNITION, now),
                        com.kooo.evcam.telemetry.SignalAges.ageMs(com.kooo.evcam.telemetry.Signal.DOOR_DRIVER, now),
                        com.kooo.evcam.telemetry.SignalAges.ageMs(com.kooo.evcam.telemetry.Signal.SCREEN_POWER_EVENT, now),
                        StallWatch.importance());
            } catch (RuntimeException e) {
                AppLog.w(TAG, "vehicle facts unavailable: " + e);
                return ChannelScheduler.Vehicle.UNKNOWN;
            }
        }

        /** 这一台相机的来历（{@link CameraHistory}，内存里的那一份，主线程不碰盘）。 */
        @Override
        public CameraHistory.Camera history(String cameraId) {
            return StepLog.history().camera(cameraId);
        }

        @Override
        public void ask(java.util.function.Consumer<CameraAvailabilityWatch.Asked> answered) {
            CameraAvailabilityWatch.ask(answered);
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
            // 相机都关完了：之后换下来的录制器交给调度就当场放（ChannelScheduler.handOver），不用再到点兜底
            laneHandler.removeCallbacks(leftoversTask);
        }

        @Override
        public boolean frontCabinOneShot() {
            return FrontCabinOneShot.ACTIVE;
        }

        @Override
        public void frontCabinRetired(String key) {
            retireFrontCabin(key);
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

        /**
         * 保持开始。屏幕黑了还不到 30 秒时也在保持（通道眼里还亮着，ChannelRules.darkForChannel）：那时保持到屏幕黑满 30 秒为止，
         * 和座舱放开同一刻关 —— 那一行照实写，带「，熄屏后 +Nms」。
         */
        @Override
        public void holding(List<String> keys, Set<ChannelRules.Kind> outlets) {
            boolean dark = com.kooo.evcam.screen.ScreenState.dark();
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：没人要 " + roles(keys) + " 了：" + (dark
                    ? "熄屏还不到 " + (ChannelRules.HOLD_MS / 1000) + " 秒、前台服务在，保持到熄屏满 "
                    + (ChannelRules.HOLD_MS / 1000) + " 秒"
                    : "亮屏、前台服务在，保持 " + (ChannelRules.HOLD_MS / 1000) + " 秒")
                    + "（画面接着送进 " + kindNames(outlets) + "）" + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
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
                        line = head + (j.unusableThen == ChannelRules.UnusableThen.LADDER
                                ? "主界面一直在前也用不了相机 → 按普通故障，按救援的节奏重开"
                                : j.unusableThen == ChannelRules.UnusableThen.MAIN_CAME_BACK
                                ? "现在用不了相机，主界面在这之后回到了前台 → 不计次数，不用再等"
                                : "现在用不了相机 → 不计次数，等主界面回到前台");
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
                        line = head + (j.unusableThen == ChannelRules.UnusableThen.LADDER
                                ? " → 主界面一直在前也用不了：按普通故障，按救援的节奏，第 " + Math.max(1, j.attempt) + " 次"
                                : j.unusableThen == ChannelRules.UnusableThen.MAIN_CAME_BACK
                                ? " → 不计次数；主界面已经回到前台，不用再等"
                                : " → 不计次数，等主界面回到前台");
                        break;
                    default:
                        line = head + (j.heardAnything ? "" : "（相机服务从没报过空闲 / 被占用，认不出被拿走）")
                                + " → 普通故障，按救援的节奏，第 " + Math.max(1, j.attempt) + " 次";
                        break;
                }
            }
            com.kooo.evcam.blackbox.BlackBox.noteImportant(line);
        }

        /**
         * 「相机 1（后座舱）放开了（相机服务报的；被拿走 3 分 12 秒）：等通道安静 3 秒，单独开」；相机层关它还没返回的
         * （项目所有者 2026-10-28：放开了也不开它）结尾换成「；上一次关还没返回，返回之前不开」。
         */
        @Override
        public void released(String key, String cameraId, CameraTaken.Verdict was, long heldMs, boolean asked,
                             boolean closeOutstanding) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId + "（" + roleName(key) + "）放开了（"
                    + (was == CameraTaken.Verdict.BLOCKED ? "挡着它的那一路放开了；被挡了 "
                    : (asked ? "问出来的" : "相机服务报的") + "；被拿走 ") + duration(heldMs) + "）"
                    + (closeOutstanding ? "；上一次关还没返回，返回之前不开"
                    : "：等通道安静 " + (ChannelRules.QUIET_MS / 1000) + " 秒，单独开"));
        }

        @Override
        public void stopLifted(String key, ChannelRules.Event.Cause cause, Set<CameraNeeds.Holder> holders) {
            String why = cause == ChannelRules.Event.Cause.SCREEN_ON ? "亮屏了"
                    : cause == ChannelRules.Event.Cause.SERVICE_FREE ? "相机服务报它空闲"
                    : "登记表变了（" + holderCodes(holders) + "）";
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：" + why + "，停手的 " + roleName(key) + " 重新救");
        }

        /**
         * 环视这一段熄屏留不留（项目所有者 2026-10-27）：每变一次一行，带「，熄屏后 +Nms」。通道刚算黑那一行不写「满 30 秒」——
         * 起点不明时是当场算黑的。只写通道定了什么（留下、放开，录像要不要它），不说相机此刻开没开得起来 ——
         * 用不了相机、屏幕还不知道黑没黑时另有「先不开」那一行。录像协调器照通道这一决定停录、开录
         * （{@link ChannelScheduler#surroundVerdict}）：放开了在录的就停；熄屏中哨兵变成开、留下了环视，「这一趟要录」立着就接着录。
         */
        @Override
        public void surroundInDark(boolean kept, ChannelRules.Event.Cause why, Integer sentry, boolean keepSetting,
                                   boolean recording, boolean recordsSurround) {
            // 留着的环视此刻录像要不要它：要 → 留下；在录、计划里没有环视 → 不用；没在录 → 留下 —— 哨兵变成开留下的，
            // 录像协调器照「这一趟要录」接着开；通道刚算黑那一下没在录的，熄屏中不为它开录
            String keptText = recordsSurround ? "环视留下（录像）"
                    : recording ? "环视不用（录像计划里没有环视）"
                    : why == ChannelRules.Event.Cause.SENTRY_ON ? "环视留下（没在录：「这一趟要录」立着的话录像接着开）"
                    : "环视留下（没在录）";
            String line;
            switch (why) {
                case DARK_ONSET:
                    line = kept ? "通道：熄屏，哨兵 " + sentryText(sentry) + "、熄屏持续录制开 → " + keptText
                            : "通道：熄屏，环视放开（" + (!keepSetting ? "熄屏持续录制关着"
                            : sentry == null ? "哨兵读不到" : "哨兵关") + "）";
                    break;
                case SENTRY_ON:
                    line = "通道：熄屏中哨兵变成 " + sentryText(sentry) + " → "
                            + (kept ? keptText : "环视不开（熄屏持续录制关着）");
                    break;
                case SENTRY_OFF:
                    line = "通道：熄屏中哨兵关了 → 环视放开";
                    break;
                default:
                    line = "通道：车机要睡（安卓熄屏）→ 环视放开；醒来不为它开（哨兵关了再开、或者人回来才开）";
                    break;
            }
            com.kooo.evcam.blackbox.BlackBox.noteImportant(line + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        }

        /** 哨兵模式的叫法：1 开、2 布防（只用在读到开或布防的那几行）。 */
        private String sentryText(Integer sentry) {
            return sentry != null && sentry == 2 ? "布防" : "开";
        }

        /**
         * 熄屏放开的一路怎么叫（退出录像那一行、报告里「该开」、拍照不拍的那一行）：环视是「熄屏：环视放开」，
         * 座舱照旧「熄屏：座舱放开」（2026-10-27：环视不写成座舱放开）。
         */
        String darkText(String key) {
            return CameraSlots.KEY_SURROUND.equals(key) ? "熄屏：环视放开"
                    : closeWhyText(ChannelRules.Why.DARK_RELEASE);
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
                case SCREEN_UNKNOWN:
                    line = "通道：屏幕还不知道黑没黑（进程刚起来 / 安卓刚亮），先不开 " + roleName(wait.key);
                    break;
                default:
                    return;
            }
            com.kooo.evcam.blackbox.BlackBox.note(line);
        }

        /**
         * 一路的一次关满 60 秒还没返回（项目所有者 2026-10-28：我们上一次关它还没返回，返回之前谁都不开它；别的路照常）：
         * 「通道：后座舱（相机 1）上一次关还没返回（closing-lost，关了 60.0 秒）：返回之前不开它，别的路照常」。一次没返回的关一行。
         */
        @Override
        public void heldByClose(String key, String cameraId, String busy, long ms) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：" + roleName(key) + "（相机 " + cameraId + "）上一次关还没返回（"
                    + (busy == null || busy.isEmpty() ? "" : busy + "，") + "关了 " + duration(ms)
                    + "）：返回之前不开它，别的路照常");
        }

        /**
         * 「通道：后座舱（相机 1）上一次关返回了（关了 3 分 12 秒）：照常开」。接下来怎样照这一路此刻的样子写：没人要了
         * 「眼下没人要它，不开」，被拿着「别的程序拿着它，等它空出来再开」，被挡着「也算被拿着，等都放开再开」。
         */
        @Override
        public void closeReturned(String key, String cameraId, long ms, CameraTaken.Verdict mark, boolean wanted) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：" + roleName(key) + "（相机 " + cameraId
                    + "）上一次关返回了（关了 " + duration(ms) + "）："
                    + (!wanted ? "眼下没人要它，不开"
                    : mark == CameraTaken.Verdict.TAKEN ? "别的程序拿着它，等它空出来再开"
                    : mark == CameraTaken.Verdict.BLOCKED ? "也算被拿着，等都放开再开"
                    : "照常开"));
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

        /**
         * 开、改输出、救那一行。出了画面的格式从 2.11 起没变（旧的分析脚本照样读）；15 秒没出画面的带上配好没有、配好以来相机回了什么
         * （SingleCamera.countsText：HAL 一个都没回 / 我们的输出接不住 / 开始了没结果）；这一步里出了事的（开不起来、丢了）
         * 2.11.4 起也写一行：做了多久、什么时候开始的、怎么没做完（项目所有者 2026-10-14 批准的诊断，第 1a 项）。
         */
        @Override
        public void stepEnded(ChannelScheduler.StepReport r) {
            String name = roleName(r.key);
            String result;
            if (r.failure != null) {
                result = "没做完：" + (r.failure.origin == ChannelRules.Failure.Origin.LOSS
                        ? "这一步里丢了（" + lossText(r.failure.reason) + "）"
                        : "开不起来（" + openFailText(r.failure.reason) + "）")
                        + "，做了 " + seconds(r.durationMs) + "（" + wallClock(r.startedAt) + " 开始）"
                        + (r.configuredMs >= 0 ? "，配好过（" + seconds(r.configuredMs) + "）" : "");
            } else if (r.timedOut) {
                result = (ChannelRules.STEP_MAX_MS / 1000) + " 秒没出画面" + (r.move == ChannelRules.Move.OPEN
                        ? "，先往下走（由救援接手）" : "（由救援接手）")
                        + "；" + (r.configuredMs >= 0 ? "配好 " + seconds(r.configuredMs) : "没配好");
            } else {
                result = (r.configuredMs >= 0 && r.move != ChannelRules.Move.OPEN
                        ? "配好 " + seconds(r.configuredMs) + "、" : "")
                        + "出画面 " + seconds(Math.max(0, r.frameMs));
            }
            if (r.counts != null && (r.failure != null || r.timedOut)) {
                result += "；" + SingleCamera.countsText(r.counts);
            }
            String line;
            switch (r.move) {
                case OPEN:
                    line = "通道：开 " + name + "（为 " + holderNames(r.forWhom) + "，输出 " + kindNames(r.outputs) + "）→ "
                            + result;
                    break;
                case APPLY:
                    if (r.why == ChannelRules.Why.DEAD) {
                        line = "通道：马上摘 " + name + " 的" + kindNames(r.removed) + "（" + deadText(r.removed) + "）"
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

        /**
         * 开一路（开、救援的重开）那一刻的情形，一行（项目所有者 2026-10-14 批准的诊断，2.11.4 第 1a 项）：
         * 「通道：开 后座舱 时的情形：哨兵 1（开）；点火 0.2 秒前变过、主驾门 4.6 秒前变过、屏幕电源事件 0.1 秒前变过；主界面在后台（importance 125）；
         * 相机服务 4.5 秒前说空闲（我们引起的）；我们上一次关它 4.5 秒前、关了 0.2 秒；车机拿过它 18 分 47 秒、3.0 秒前放开；环视在出画面；
         * 环视：车机重新初始化后第 5 次打开（上次重置：它在相机服务里没了又回来，10-14 21:01:55，50 分 25 秒前）」。
         * 2.11.5 起环视自己开、重开也写（项目所有者 2026-10-15 批准，第 D1 项）：要看的正是环视那一台是不是车机重新初始化后的第一台。
         */
        @Override
        public void opening(ChannelScheduler.StepContext c) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：" + (c.move == ChannelRules.Move.RESCUE ? "救" : "开") + " "
                    + roleName(c.key) + " 时的情形：" + contextText(c));
        }

        /** 开座舱那一行「时的情形：」后面那一段。 */
        private String contextText(ChannelScheduler.StepContext c) {
            ChannelScheduler.Vehicle v = c.vehicle;
            StringBuilder sb = new StringBuilder("哨兵 ").append(v.sentry == null ? "读不到"
                    : v.sentry + (v.sentry == 0 ? "（关）" : v.sentry == 1 ? "（开）" : v.sentry == 2 ? "（布防）" : ""));
            sb.append("；点火").append(changedText(v.sinceIgnitionMs)).append("、主驾门").append(changedText(v.sinceDoorMs))
                    .append("、屏幕电源事件").append(changedText(v.sinceScreenEventMs));
            sb.append("；主界面").append(c.mainForeground ? "在前" : "在后台")
                    .append("（importance ").append(v.importance < 0 ? "?" : String.valueOf(v.importance)).append('）');
            sb.append("；相机服务").append(c.wordAgoMs < 0 ? "没说过它"
                    : " " + duration(c.wordAgoMs) + "前说" + (c.wordAbsent ? "不在" : Boolean.TRUE.equals(c.wordFree)
                    ? "空闲" : Boolean.FALSE.equals(c.wordFree) ? "被占用" : "?") + (c.wordOurs ? "（我们引起的）" : "（别人引起的）"));
            sb.append("；我们").append(c.sinceCloseMs < 0 ? "没有关过它的记录"
                    : "上一次关它 " + duration(c.sinceCloseMs) + "前、关了 " + seconds(c.closeMs));
            if (c.heldMs >= 0) {
                sb.append("；车机拿过它 ").append(duration(c.heldMs)).append("、").append(duration(c.releasedAgoMs)).append("前放开");
            }
            if (c.surround != null) {
                sb.append("；环视").append(c.surround == ChannelScheduler.SurroundState.STREAMING ? "在出画面"
                        : c.surround == ChannelScheduler.SurroundState.OPEN ? "开着、没在出画面" : "关着");
                if (c.surround != ChannelScheduler.SurroundState.CLOSED && c.surroundHistory != null) {
                    // 开着的那一台环视是车机重新初始化后第几台（2.11.5 第 D1 项）
                    CameraHistory.Camera h = c.surroundHistory;
                    sb.append("；环视：").append(lineageText(h.lastOrdinal, h.lastReset, h.lastResetWall, h.sinceWall));
                }
            } else if (CameraSlots.KEY_SURROUND.equals(c.key)) {
                // 环视自己要开的这一台是车机重新初始化后第几台（2.11.5 第 D1 项）
                CameraHistory.Camera h = c.history;
                sb.append("；环视：").append(h == null ? lineageText(1, null, 0, 0)
                        : lineageText(h.opens + 1, h.reset, h.resetWall, h.sinceWall));
            }
            return sb.toString();
        }

        /**
         * 第几次打开，「车机重新初始化后第 5 次打开（上次重置：它在相机服务里没了又回来，10-14 21:01:55，50 分 25 秒前）」；
         * 没见过重置的：「记录开始以来第 5 次打开（记录从 10-15 08:00:00 起，之前的重置不知道）」（2.11.5 第 D1 项）。
         *
         * @param ordinal   第几台；0 及以下是不知道
         * @param reset     那一台开的时候上一次重置；null = 从记录开始数的
         * @param resetWall 那一次重置（墙上时钟）
         * @param sinceWall 记录从什么时候开始（墙上时钟；0 不知道）
         */
        private String lineageText(int ordinal, CameraHistory.Reset reset, long resetWall, long sinceWall) {
            if (ordinal <= 0) {
                return "第几次打开不知道（这一台是 2.11.5 之前开的，或者来历还没读回来）";
            }
            if (reset == null) {
                return "记录开始以来第 " + ordinal + " 次打开（" + (sinceWall > 0 ? "记录从 " + wallText(sinceWall) + " 起，" : "")
                        + "之前的重置不知道）";
            }
            return "车机重新初始化后第 " + ordinal + " 次打开（上次重置：" + resetText(reset)
                    + (resetWall > 0 ? "，" + wallText(resetWall) + "，" + timeText(System.currentTimeMillis() - resetWall) + "前"
                    : "") + "）";
        }

        /** 一次重置是什么。 */
        private String resetText(CameraHistory.Reset reset) {
            switch (reset) {
                case BOOT:
                    return "车机开机";
                case TAKEN:
                    return "别的程序拿了它又空出来";
                default:
                    return "它在相机服务里没了又回来";
            }
        }

        /** 墙上时钟「10-14 21:01:55」。 */
        private String wallText(long wall) {
            return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(wall));
        }

        /**
         * 关座舱时环视怎样（{@link ChannelScheduler.Coupling}，项目所有者 2026-10-15 批准，2.11.5 第 D2 项）：关完那一刻、关完 1 秒、关完 30 秒各一行。
         * <ul>
         *   <li>「通道：座舱关完时环视：关 前座舱 0.1 秒、后座舱 0.1 秒（熄屏：座舱放开）；环视最后一帧在开始关后 +67 ms、前座舱关完前 35 ms、
         *       后座舱关完前 73 ms；开始关以来环视最长断帧不到 0.2 秒、会话没动；失败 / 丢缓冲（开始关以来）：环视 0 / 0、后座舱 0 / 0、前座舱 0 / 0；
         *       点火 ACC（30.0 秒前变过）；主界面在后台；环视：车机重新初始化后第 5 次打开（…）」</li>
         *   <li>「通道：座舱关完 1.6 秒后环视（关 前座舱、后座舱，熄屏：座舱放开）：关完以后没出过画面（最后一帧在关完前 73 ms）；失败 / 丢缓冲…」</li>
         *   <li>计划里的关的 30 秒那一行就是以前关完一轮那一行（前面一字不改），后面接着这几样：「通道：关 前座舱、后座舱（熄屏：座舱放开）之后 30 秒：
         *       环视最长断帧 8.1 秒、会话没动（只量到第 8.1 秒：那时我们在救它）；环视最后一帧在关完前 73 ms；关完 1 秒后没出过画面；…」；
         *       丢了、重开的那一次关：「通道：座舱关完 30 秒后环视（后座舱 丢了：被断开）：…」。</li>
         * </ul>
         */
        @Override
        public void coupling(ChannelScheduler.Coupling c) {
            StringBuilder sb = new StringBuilder();
            switch (c.phase) {
                case END:
                    sb.append("通道：座舱关完时环视：").append(closedText(c, true)).append("；")
                            .append(lastResultText(c, true)).append("；开始关以来")
                            .append(gapsText(java.util.Collections.singletonList(c.surround)));
                    break;
                case AFTER_1S:
                    sb.append("通道：座舱关完 ").append(seconds(c.measuredAt - c.endedAt)).append("后环视（")
                            .append(closedText(c, false)).append("）：")
                            .append(c.deliveredAfterEnd() ? "关完以后出过画面" : "关完以后没出过画面")
                            .append("（").append(lastResultText(c, false)).append("）");
                    break;
                default:
                    if (c.kind == ChannelScheduler.Coupling.Kind.PLANNED) {
                        // 以前关完一轮的那一行，前面一字不改（旧的分析脚本照样读）
                        sb.append("通道：关 ").append(roles(c.keys)).append("（").append(closeWhyText(c.why))
                                .append("）之后 ").append(ChannelScheduler.AFTERMATH_MS / 1000).append(" 秒：")
                                .append(gapsText(c.others.isEmpty()
                                        ? java.util.Collections.singletonList(c.surround) : c.others));
                    } else {
                        sb.append("通道：座舱关完 ").append(ChannelScheduler.AFTERMATH_MS / 1000).append(" 秒后环视（")
                                .append(closedText(c, false)).append("）：")
                                .append(gapsText(java.util.Collections.singletonList(c.surround)));
                    }
                    sb.append("；").append(lastResultText(c, false)).append("；").append(c.deliveredAfter < 0
                            ? "关完 1 秒后没量到" : c.deliveredAfter == 1 ? "关完 1 秒后出过画面" : "关完 1 秒后没出过画面");
                    break;
            }
            sb.append("；失败 / 丢缓冲（开始关以来）：").append(troubleText(c.trouble));
            if (c.phase != ChannelScheduler.Coupling.Phase.AFTER_1S) {
                ChannelScheduler.Vehicle v = c.vehicle;
                sb.append("；点火 ").append(v.ignition == null ? "读不到" : ignitionText(v.ignition))
                        .append("（").append(v.sinceIgnitionMs < 0 ? "起来以后没变过" : duration(v.sinceIgnitionMs) + "前变过")
                        .append("）；主界面").append(c.mainForeground ? "在前" : "在后台")
                        .append("；环视：").append(lineageText(c.lineage, c.lineageReset, c.lineageResetWall, 0));
            }
            com.kooo.evcam.blackbox.BlackBox.noteImportant(sb + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        }

        /**
         * 关的是什么：计划里的关「关 前座舱 0.1 秒、后座舱 0.1 秒（熄屏：座舱放开）」（{@code withTimes} 为 false 时不带秒数、为什么跟在逗号后面），
         * 丢了「后座舱 丢了（被断开），关了 5.4 秒」，重开「救 后座舱 重开时关旧设备，关了 5.6 秒」。
         */
        private String closedText(ChannelScheduler.Coupling c, boolean withTimes) {
            String lane = c.keys.isEmpty() ? "?" : roleName(c.keys.get(0));
            long ms = c.endedMs.isEmpty() ? -1 : c.endedMs.get(0);
            switch (c.kind) {
                case LOSS:
                    return lane + " 丢了" + (withTimes ? "（" + lossText(c.reason) + "），关了 " + seconds(Math.max(0, ms))
                            : "：" + lossText(c.reason));
                case REOPEN:
                    return "救 " + lane + " 重开时关旧设备" + (withTimes ? "，关了 " + seconds(Math.max(0, ms)) : "");
                default:
                    if (!withTimes) {
                        return "关 " + roles(c.keys) + "，" + closeWhyText(c.why);
                    }
                    List<String> parts = new ArrayList<>();
                    for (int i = 0; i < c.keys.size(); i++) {
                        long each = i < c.endedMs.size() ? c.endedMs.get(i) : -1;
                        parts.add(roleName(c.keys.get(i)) + (each < 0 ? " 到点还没关完" : " " + seconds(each)));
                    }
                    return "关 " + String.join("、", parts) + "（" + closeWhyText(c.why) + "）";
            }
        }

        /**
         * 环视最后一帧在什么时候：{@code fromStart} 时「环视最后一帧在开始关后 +67 ms、前座舱关完前 35 ms、后座舱关完前 73 ms」，
         * 不然只对关完（最后一路关完）：「最后一帧在关完前 73 ms」「最后一帧在关完后 +1.5 秒」。没出过画面：「环视还没出过画面」。
         */
        private String lastResultText(ChannelScheduler.Coupling c, boolean fromStart) {
            long afterStart = c.lastResultAfterStartMs();
            if (afterStart == Long.MIN_VALUE) {
                return "环视还没出过画面";
            }
            if (!fromStart) {
                return "最后一帧在关完" + relativeText(c.lastResultAfterEndMs());
            }
            StringBuilder sb = new StringBuilder("环视最后一帧在开始关").append(relativeText(afterStart));
            for (int i = 0; i < c.keys.size() && i < c.endedMs.size(); i++) {
                long ended = c.endedMs.get(i);
                if (ended >= 0) {
                    sb.append("、").append(roleName(c.keys.get(i))).append("关完").append(relativeText(afterStart - ended));
                }
            }
            return sb.toString();
        }

        /** 「后 +67 ms」「前 35 ms」；一秒以上写秒：「后 +1.5 秒」。 */
        private String relativeText(long ms) {
            long abs = Math.abs(ms);
            String amount = abs < 1_000L ? abs + " ms" : seconds(abs);
            return ms >= 0 ? "后 +" + amount : "前 " + amount;
        }

        /** 「环视 0 / 0、后座舱 1 / 0、前座舱 0 / 0」（失败 / 丢缓冲）。 */
        private String troubleText(List<ChannelScheduler.Trouble> trouble) {
            List<String> parts = new ArrayList<>();
            for (ChannelScheduler.Trouble t : trouble) {
                parts.add(roleName(t.key) + " " + t.failures + " / " + t.lost);
            }
            return parts.isEmpty() ? "没有" : String.join("、", parts);
        }

        /** 点火的样子：「ACC」「ON」「DRIVING」，认不出的写十六进制（「0x00200102」）。 */
        private String ignitionText(int code) {
            return ignitionCode(code);
        }

        /**
         * 救完一路之后接着量别的几路，量到下一步开始（2.11.5 第 D2 项）：「通道：救 后座舱 之后接着量到下一步开始（救那一步量了 5.8 秒，
         * 一共量了 21.3 秒；下一步：开 前座舱）：环视最长断帧 8.1 秒、会话没动」。比救那一行多量不到 1 秒的不写（调度那边就不交）。
         */
        @Override
        public void rescueAftermath(String key, long stepMs, long measuredMs, ChannelRules.Move next, String nextKey,
                                    List<ChannelScheduler.Other> others) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：救 " + roleName(key) + " 之后接着量到下一步开始（救那一步量了 "
                    + seconds(stepMs) + "，一共量了 " + seconds(measuredMs) + "；"
                    + (next == null ? (ChannelScheduler.AFTERMATH_MS / 1000) + " 秒里没有下一步"
                    : "下一步：" + stepName(next) + " " + roleName(nextKey)) + "）：" + gapsText(others)
                    + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        }

        /** 一步的叫法：开、改输出、救、关。 */
        private String stepName(ChannelRules.Move move) {
            switch (move) {
                case OPEN:
                    return "开";
                case APPLY:
                    return "改输出";
                case RESCUE:
                    return "救";
                case CLOSE:
                    return "关";
                default:
                    return "动";
            }
        }

        /** 「 0.2 秒前变过」；进程起来以后没见它变过：「起来以后没变过」。 */
        private String changedText(long agoMs) {
            return agoMs < 0 ? "起来以后没变过" : " " + duration(agoMs) + "前变过";
        }

        /**
         * 一步结束了：相机步骤统计记一条（{@link StepLog}，应用目录，最近 200 条；诊断报告「2.3.3 相机步骤统计」读）。
         * 不写黑匣子 —— 那一步自己的那一行另有。写在 StepLog 自己的线程上，主线程不碰盘。
         */
        @Override
        public void recorded(ChannelScheduler.StepRecord r) {
            ChannelScheduler.StepContext c = r.context;
            ChannelScheduler.Vehicle v = c.vehicle;
            StepStats.Builder b = new StepStats.Builder()
                    .step(System.currentTimeMillis(), c.key, c.cameraId, r.move, r.result, r.ms)
                    .configured(r.configuredMs)
                    .error(r.reason == null ? null : r.reason.source + (r.reason.code == 0 ? "" : ":" + r.reason.code))
                    .counts(r.counts)
                    .oldClose(r.oldCloseMs)
                    .vehicle(v.sentry, v.sinceIgnitionMs, v.sinceDoorMs, v.sinceScreenEventMs, v.importance)
                    .main(c.mainForeground)
                    .lastClose(c.sinceCloseMs, c.closeMs)
                    .released(c.heldMs, c.releasedAgoMs)
                    .word(c.wordAgoMs, c.wordAgoMs < 0 ? null : c.wordOurs)
                    .surround(c.surround == null ? null : c.surround.name(),
                            r.surround == null ? -1 : r.surround.gapMs,
                            r.surround == null ? null : r.surround.sessionChanged)
                    .closeKind(r.closeKind)
                    .lineage(r.lineage, r.lineageReset == null ? null : r.lineageReset.name())
                    // 关座舱前先关环视之后环视开回来的那一步（2.11.6）：从开始关环视到这一步开始多久、座舱那一步关了多久
                    .surroundFirst(r.surroundFirstStartMs, r.surroundFirstCabinMs);
            ChannelScheduler.Coupling coupling = r.coupling;
            if (coupling != null) {
                // 关座舱时环视那一条（2.11.5 第 D3 项）：关的几路、为什么关、环视最后一帧比关完晚多少、关完 1 秒后出没出过画面、我们中途动没动它
                b.coupling(coupling.keys, coupling.kind == ChannelScheduler.Coupling.Kind.PLANNED
                                ? (coupling.why == null ? null : coupling.why.name())
                                : coupling.reason == null ? coupling.kind.name()
                                : coupling.reason.source + (coupling.reason.code == 0 ? "" : ":" + coupling.reason.code),
                        coupling.lastResultAfterEndMs(), coupling.deliveredAfter,
                        coupling.surround.ours == null ? null : coupling.surround.ours.name(), coupling.surround.oursAtMs)
                        // 点火照开始关那一刻的（和哨兵、主界面那几样一样），不是关完 30 秒量的那一刻
                        .ignition(v.ignition == null ? null : ignitionCode(v.ignition));
            }
            StepLog.append(context, b.build());
        }

        /** 调度的钟（uptime）上的一刻换成墙上时钟「20:10:30.918」，和黑匣子每行开头对得上。 */
        private String wallClock(long uptimeMs) {
            long wall = System.currentTimeMillis() - (android.os.SystemClock.uptimeMillis() - uptimeMs);
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(wall));
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
                               List<String> cabinKeys, List<Long> cabinMs, List<ChannelScheduler.Carried> carried,
                               List<ChannelScheduler.Other> others) {
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
                // 「关相机：…」这一行的格式从 2.10.10 起没变，旧的分析脚本照样能读；阶段 8 只在末尾追加带走的留着的输出，
                // 熄屏 v3 再在最末尾、括号里追加还开着的那几路这段时间里怎样（设计 v3 §6 L3、v3.1 C22）
                com.kooo.evcam.blackbox.BlackBox.noteImportant("关相机：" + line
                        + com.kooo.evcam.blackbox.BlackBox.afterScreenOff() + carriedText(carried) + othersText(others));
            }
            // 这一段熄屏里第一轮关了座舱的那一轮关完了：记下那一刻（熄屏小结「座舱 熄屏后 +N ms 放开完」，设计 v3.1 C16）
            if (awaitingCabinRelease && !cabinKeys.isEmpty()) {
                awaitingCabinRelease = false;
                cabinsReleasedAt = android.os.SystemClock.elapsedRealtime();
            }
        }

        @Override
        public void closeAftermath(List<String> closedKeys, ChannelRules.Why why, List<ChannelScheduler.Other> others) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("通道：关 " + roles(closedKeys) + "（" + closeWhyText(why)
                    + "）之后 " + (ChannelScheduler.AFTERMATH_MS / 1000) + " 秒：" + gapsText(others)
                    + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        }

        /**
         * 关座舱前先关环视的一趟走完了（{@link ChannelScheduler.SurroundFirst}，项目所有者 2026-10-15 定，2.11.6），一趟一行：
         * 「关座舱前先关环视：环视断了 0.6 秒（关 0.1 秒 → 座舱关 0.1 秒 → 开 0.2 秒）」；开回来没画面、出了事、不再开回来的照实写
         * （「环视开回来 15 秒没出画面，由救援接手（…）；从开始关环视起 15.5 秒」「环视没人要了，不再开回来（…）」）。
         */
        @Override
        public void surroundFirst(ChannelScheduler.SurroundFirst f) {
            List<String> parts = new ArrayList<>();
            parts.add(f.surroundMs < 0 ? "关 还没关完" : f.surroundTimedOut
                    ? "关 " + (ChannelRules.CLOSE_SURROUND_MAX_MS / 1000) + " 秒还没关完" : "关 " + seconds(f.surroundMs));
            parts.add(f.cabinMs < 0 ? "座舱没关（又有人要了）" : f.cabinTimedOut
                    ? "座舱关 " + (ChannelRules.CLOSE_CABINS_MAX_MS / 1000) + " 秒还没关完" : "座舱关 " + seconds(f.cabinMs));
            String head;
            switch (f.end) {
                case FRAMED:
                    parts.add("开 " + seconds(f.reopenMs));
                    head = "环视断了 " + seconds(f.gapMs);
                    break;
                case NO_FRAMES:
                    parts.add("开 " + (ChannelRules.STEP_MAX_MS / 1000) + " 秒没画面");
                    head = "环视开回来 " + (ChannelRules.STEP_MAX_MS / 1000) + " 秒没出画面，由救援接手";
                    break;
                case ERROR:
                    parts.add("开 " + seconds(Math.max(0, f.reopenMs)) + " 出事");
                    head = "环视开回来" + (f.failure != null && f.failure.origin == ChannelRules.Failure.Origin.LOSS
                            ? "那一步里丢了（" + lossText(f.failure.reason) + "）"
                            : "时开不起来（" + openFailText(f.failure == null ? null : f.failure.reason) + "）");
                    break;
                case WINDING:
                    head = "退出 / 车机要睡，环视不再开回来";
                    break;
                default:
                    head = "环视没人要了，不再开回来";
                    break;
            }
            String tail = f.end == ChannelScheduler.SurroundFirst.End.NO_FRAMES
                    || f.end == ChannelScheduler.SurroundFirst.End.ERROR
                    ? "；从开始关环视起 " + seconds(f.endedAt - f.startedAt) : "";
            com.kooo.evcam.blackbox.BlackBox.noteImportant("关座舱前先关环视：" + head + "（" + String.join(" → ", parts) + "）"
                    + tail + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
        }

        @Override
        public void openSpan(ChannelScheduler.OpenSpan span) {
            // 车主问过「拖着摘有没有用」：这一行和 2.10.x 的黑匣子比，看每开一次相机重配了几次
            com.kooo.evcam.blackbox.BlackBox.noteImportant("关相机小结：" + roleName(span.key) + " 这一次开了 "
                    + timeText(span.openMs) + "：重配 " + span.reconfigured() + " 次（加输出 " + span.added
                    + "、摘死的 " + span.deadRemoved + "、救援 " + span.rescued + "），"
                    + (span.removedOnly == 0 ? "没为摘输出重建过" : "为摘输出重建过 " + span.removedOnly + " 次"));
        }

        /**
         * 「关相机：…」那一行末尾：关的时候各路会话里带走的留着的输出、各留了多久 ——
         * 「；带走留着的输出：环视 录像（留了 12 分 3 秒）、预览（31 秒）；后座舱 预览（留了 31 秒）」。没有是空的。
         */
        private String carriedText(List<ChannelScheduler.Carried> carried) {
            if (carried.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            String lane = null;
            for (ChannelScheduler.Carried one : carried) {
                boolean first = !one.key.equals(lane);
                if (first) {
                    sb.append(lane == null ? "" : "；").append(roleName(one.key)).append(' ');
                    lane = one.key;
                } else {
                    sb.append('、');
                }
                sb.append(kindNames(java.util.EnumSet.of(one.kind))).append('（').append(first ? "留了 " : "")
                        .append(timeText(one.keptMs)).append('）');
            }
            return "；带走留着的输出：" + sb;
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
        public void asked(String key, String cameraId, CameraTaken.Verdict mark, int n, long heldMs, Boolean free,
                          boolean absent, boolean important) {
            // 被挡着的那一路自己本来就空着：「说空闲了」不是放开，它等的是挡着它的那一路（审查 2026-10-11）
            boolean blocked = mark == CameraTaken.Verdict.BLOCKED && !absent && Boolean.TRUE.equals(free);
            String line = "问相机服务：" + cameraId + " " + (blocked
                    ? "自己空着，等挡着它的那一路放开（被挡了 "
                    : (absent ? "还是不在" : Boolean.TRUE.equals(free) ? "说空闲了" : "还被占着") + "（被拿走 ")
                    + duration(heldMs) + "，第 " + n + " 次）";
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

        /** 谁要它，中文：预览、录像、拍照、后视镜、盲区画面。 */
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
                    case MIRROR:
                        names.add("后视镜");
                        break;
                    case SIDE_VIEW:
                        names.add("盲区画面");
                        break;
                    default:
                        names.add(holder.name());
                        break;
                }
            }
            return names.isEmpty() ? "保持" : String.join("、", names);
        }

        /** 输出的叫法：预览、后视镜、盲区画面、HUD 盲区画面、录像、出帧口。 */
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
                    case SIDE:
                        names.add("盲区画面");
                        break;
                    case HUD:
                        names.add("HUD 盲区画面");
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

        /**
         * 死输出为什么死了：画布（预览、后视镜、盲区画面）是被系统收走的；录像输出死了只有一种 —— MediaRecorder 停了、放掉了
         * 它的 Surface。编码录制的录像输出离开会话之后才放，不会死在会话里（这一步顺带摘的录像是留着的输出）。
         */
        private String deadText(Set<ChannelRules.Kind> removed) {
            boolean canvas = removed.contains(ChannelRules.Kind.PREVIEW) || removed.contains(ChannelRules.Kind.MIRROR)
                    || removed.contains(ChannelRules.Kind.SIDE) || removed.contains(ChannelRules.Kind.HUD);
            boolean record = !useCodecRecording && removed.contains(ChannelRules.Kind.RECORD);
            if (record && !canvas) {
                return "MediaRecorder 停了";
            }
            return record ? "画布被系统收走、MediaRecorder 停了" : "画布被系统收走";
        }

        /**
         * 关的原因（「通道：关 …（…）」、退出录像那一行、诊断报告「该开」、拍照不拍哪几路）。关座舱前先关环视（2.11.6）那一行：
         * 「通道：关 环视（关座舱前先关环视），熄屏后 +30001ms」。
         */
        private String closeWhyText(ChannelRules.Why why) {
            switch (why) {
                case EXIT:
                    return "退出";
                case SLEEP:
                    return "车机要睡";
                case SCREEN_OFF:
                    return "熄屏";
                case DARK_RELEASE:
                    return "熄屏：座舱放开";
                case SURROUND_FIRST:
                    return "关座舱前先关环视";
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

        /** 「（这段时间里环视最长断帧不到 0.2 秒、会话没动）」；没有别的路是空的。 */
        private String othersText(List<ChannelScheduler.Other> others) {
            return others.isEmpty() ? "" : "（这段时间里" + gapsText(others) + "）";
        }
    }

    /**
     * 打开录像意图（唯一的开录入口是 RecordingCoordinator）：定下这次录哪几路、写到哪，每一路各自加入
     * （{@link #openRecordingIntent}）。两种录制器一样，文件都按这一路真实开始的时刻起名。
     *
     * @param enabledCameras 要录的那几路（如 ["front", "back"]）；null 或空是启用的全部
     * @param storage        开录前刚查过的那一份存储快照：写到哪照它（目录在存储线程上找好、建好），主线程不碰盘
     * @return false：没有地方存、没有能录的一路 —— 这里不收拾，协调器按开录失败走停录，由 {@link #stopRecording()} 收
     */
    public boolean startRecording(Set<String> enabledCameras, com.kooo.evcam.storage.StorageState.Snapshot storage) {
        // 以前还有不带参数的两个重载给悬浮按钮走，那条路没有写入看门狗、没有存储检查、也不拒录内置存储（2026-09-27 审查），删了
        if (isRecording) {
            AppLog.w(TAG, "Already recording");
            return false;
        }
        ++recordGeneration;
        // 新的录像意图：共用的分段名字、段号从头来；「已经报过打断」清掉
        sharedName.reset();
        interruptReported = false;

        boolean started = openRecordingIntent(enabledCameras, storage);
        if (started) {
            lastStorageCheckMs = 0;
            mainHandler.removeCallbacks(storageTick);
            mainHandler.postDelayed(storageTick, STORAGE_TICK_MS);
            noteRecordingDir(recordDir);
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
     * 按这一路的录法（{@link LanePlan}）建一个软编码录制器：尺寸、四宫格、帧率、画质、水印、信息条（在 {@link #lanePrep} 上调）。
     *
     * <h3>为什么只有这一处</h3>
     *
     * <p>以前开录和「强制重开相机后重新准备」各写一遍这段。重新准备那一遍写的是<b>预览尺寸、不拆四宫格、帧率写死 25</b> ——
     * 相机重开一次，剩下整场录制就悄悄变成预览那么大的一条长条，日志里看不出来，下车才发现。
     * 一段代码一个调用方（{@link #build}），这类分叉才不会再长出来。</p>
     *
     * @param size   这一路的画面尺寸（{@link SingleCamera#resolvedSize}：不开设备先算的，或者开相机时算的）
     * @param source 录制流的源尺寸（{@link #sourceOf}）
     * @return 配好尺寸、四宫格、帧率、画质和水印的录制器，还没准备（{@link CodecVideoRecorder#prepareWithoutFile}）
     */
    private CodecVideoRecorder newCodecRecorder(String key, String cameraId, LanePlan plan, Size size, Size source) {
        StreamSpec spec = plan.spec;

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
        if (!source.equals(size)) {
            AppLog.i(TAG, "Camera " + key + " 录制流按配置用 " + source + "（画面是 " + size + "）");
        }
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        EncodeSize encodeSize = EncodeSize.forSource(cameraId, sourceWidth, sourceHeight, spec.grid);
        // 行驶信息条：开着就在环视录像的画面下面加一条（项目所有者 2026-10-09：只放环视，座舱两路不放）
        if (plan.infoBar) {
            encodeSize = encodeSize.withInfoBar(com.kooo.evcam.telemetry.InfoBar.HEIGHT);
        }

        com.kooo.evcam.zeekr.CompositeStreamGeometry.Plan fourLanePlan = null;
        if (encodeSize.grid) {
            fourLanePlan = com.kooo.evcam.zeekr.CompositeStreamGeometry.analyse(cameraId, sourceWidth, sourceHeight);
            AppLog.i(TAG, "Camera " + key + " 四宫格录制: 源 "
                    + sourceWidth + "x" + sourceHeight
                    + " -> 编码 " + encodeSize.width + "x" + encodeSize.height);
        } else {
            // 配置写着四宫格却录出长条时，得能一眼看出是哪一步没成立
            boolean sourceIsComposite =
                    com.kooo.evcam.zeekr.CompositeStreamGeometry.looksLikeComposite(cameraId, sourceWidth, sourceHeight);
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

        CodecVideoRecorder codecRecorder = new CodecVideoRecorder(cameraId, encodeSize.width, encodeSize.height);
        codecRecorder.setBrandLine(plan.brandLine);
        if (plan.infoBar) {
            codecRecorder.setInfoBar(
                    new com.kooo.evcam.telemetry.InfoBarRenderer(context, encodeSize.width));
        }
        if (fourLanePlan != null) {
            codecRecorder.setFourLaneSource(sourceWidth, sourceHeight, fourLanePlan, null);
        }
        // 共用的分段名字：几路分段切换时拿同一个
        codecRecorder.setTimestampProvider(segmentTimestampProvider);
        // 盘写不进时换到哪个盘；写在别处时，存储位置选的那个盘挂回来就写回去
        codecRecorder.setFallbackDirs(new CodecVideoRecorder.FallbackDirs() {
            @Override
            public List<File> candidates(Set<String> deadVolumes) {
                return fallbackDirsFor(deadVolumes);
            }

            @Override
            public File home(List<File> mounted) {
                return homeDirFor(mounted);
            }
        });
        codecRecorder.setSegmentDuration(segmentDurationMs);
        codecRecorder.setFrameRate(targetFrameRate, frameRateCap);
        // 跟随这一路配置里的码率等级。写死一档的话那个选项就是个摆设。
        // 码率本身由录制器按这一档和编码尺寸算（见 TargetBitrate）——
        // 这里不再算第二遍：以前算了，然后那个数在默认路径上被丢掉。
        codecRecorder.setQualityLevel(RecordSpecs.qualityLevel(spec.bitrate));
        codecRecorder.setForceH264(plan.forceH264);
        codecRecorder.setWatermarkEnabled(plan.watermark);
        codecRecorder.setWatermarkSpecEnabled(plan.watermarkSpec);
        // 环视录像带声音（开发者选项「录制声音」）：MediaRecorder 那条路不带
        codecRecorder.setFootageAudio(plan.audio);
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
        // 时间和录制规格同开同关：都跟着「时间水印」（项目所有者 2026-10-23）
        boolean stamp = appConfig.isTimestampWatermarkEnabled();
        return new LanePlan(spec, CameraSlots.KEY_SURROUND.equals(key) && infoBarOn, brandLine,
                // 设置里的「强制 H.264」是总闸，盖过这一路配置里的编码选择
                appConfig.isForceH264Encoding() || RecordSpecs.forceH264(spec.codec),
                stamp, stamp,
                // 声音只进环视（项目所有者 2026-10-20）
                CameraSlots.KEY_SURROUND.equals(key) ? footageAudioSource(appConfig) : null);
    }

    /**
     * 环视录像带不带声音、用哪个来源（开发者选项「录制声音」，试验）：开着、又有录音权限才带。开关打开时就要过权限（设置里），
     * 走到这里还没有，多半是在系统设置里收回了：这一次不带，记一行。
     */
    private String footageAudioSource(AppConfig appConfig) {
        if (!appConfig.isFootageAudioEnabled()) {
            return null;
        }
        String source = FootageAudio.sourceFor(context, appConfig);
        if (source == null) {
            AppLog.w(TAG, "录制声音开着，但这一次环视录像不带声音（没有录音权限，或者安卓 11 以下）");
        }
        return source;
    }

    /**
     * 打开录像意图（主线程）。定下这次录哪几路、每一路的录法（{@link #recordingPlan}）、写到哪；先置「在录」，
     * 再给每一路备录制器 —— 编码录制 {@link #ensureRecorder}：停放着的、录法和源尺寸都一样就沿用，否则在 {@link #lanePrep}
     * 上建一个；MediaRecorder {@link #ensureMedia}：相机在出画面、或者正要开的那几路现在备 —— 建好声明录像输出、叫醒调度：
     * 相机关着的，调度开的时候就带着它；开着的，调度排一步「改输出」加上它。
     *
     * <p>不等环视、不等开完、不建会话：每一路的相机带着录像输出出了画面就加入（{@link #tryJoin}、{@link #tryJoinMedia}），
     * 第一次开录和中途接回是同一件事；已经在出画面、会话又带着它的录像输出的（停放着的沿用）马上开录。开录时被拿着的那一路
     * 照样在计划里，它回来了自己加入。以前要等环视正常、各路按次序开完，再一起重建会话、等 3 秒会话计数，被拿着的那一路
     * 这一次不录（MediaRecorder 模式还要等画面稳定、再等 300 ms）。</p>
     *
     * <p>写到哪照开录前查盘的那一份快照（{@code storage.recordDir}、中转写入的目标 {@code storage.videoDir}）：
     * 目录在存储线程上找好、建好，主线程不碰盘 —— 以前这里在主线程上再找一遍、exists / mkdirs，盘掉线时会卡住主线程
     * （审查 2026-10-11）。</p>
     *
     * @return false：没有地方存、没有能录的一路（这时什么都没动）
     */
    private boolean openRecordingIntent(Set<String> enabledCameras, com.kooo.evcam.storage.StorageState.Snapshot storage) {
        // 重置首次写入通知标志（每次录制只通知一次）
        hasNotifiedFirstDataWritten = false;
        firstDataWritten = false;
        firstDataWrittenAtMs = 0;

        AppConfig appConfig = new AppConfig(context);
        // 录制目录（中转写入时是内置存储上的临时目录，否则是最终目录）：查盘时在存储线程上找好、建好的
        File saveDir = storage == null ? null : storage.recordDir;
        boolean relay = appConfig.shouldUseRelayWrite();
        if (saveDir == null || (relay && storage.videoDir == null)) {
            // 没有地方可存（没有 U 盘、开发者选项没开）：RecordingCoordinator 开录前已经拦过，走到这里是查盘时就没找到目录
            AppLog.e(TAG, "没有地方存录像（没有 U 盘），不开录");
            return false;
        }
        // 这次录哪几路：这一份管线建的时候启用的那几路（和主界面预览、拍照同一份，enabledActiveKeys），再照协调器给的
        // 此刻的配置收一收，按开的次序。只为超级镜子、盲区画面建的那一路（没启用的环视、后座舱）不录 —— 配置里刚开了它、
        // 还没重启的也不录，照「重启应用后生效」（2026-10-20 审查，车主批：以前录像照此刻的配置、预览拍照照建管线那一刻的）
        List<String> keys = new ArrayList<>();
        for (String key : enabledActiveKeys()) {
            if (enabledCameras == null || enabledCameras.isEmpty() || enabledCameras.contains(key)) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            AppLog.e(TAG, "No camera to record: active=" + activeCameraKeys + ", built enabled=" + enabledLanes
                    + ", profile enabled=" + enabledCameras);
            return false;
        }

        // 如果使用中转写入，记录最终目录
        useRelayWrite = relay;
        if (useRelayWrite) {
            finalSaveDir = storage.videoDir;
            AppLog.d(TAG, "Relay write mode: recording to " + saveDir.getAbsolutePath()
                    + ", will transfer to " + finalSaveDir.getAbsolutePath());
        } else {
            finalSaveDir = null;
        }
        // 行驶信息条开着：录像期间登记要用车辆信号，停录时注销（setRecording(false)；没别人在用就全停）。
        // MediaRecorder 直接吃相机输出，画不了信息条（开着信息条的话建管线时就改用编码录制，见 AppConfig.shouldUseCodecRecording）
        boolean infoBarOn = useCodecRecording && com.kooo.evcam.telemetry.InfoBar.isOnForRecording(context);
        if (infoBarOn) {
            com.kooo.evcam.telemetry.Telemetry.get().acquire(context, "recording");
        }

        // 这个录像意图：写到哪（建录制器、重新备好时先看录像此刻实际写在哪个盘上，见 laneDir），每一路的录法照此刻的设置定下
        recordDir = saveDir;
        StorageHelper.noteRecordingDir(saveDir);
        recordingPlan.clear();
        recordSources.clear();
        unrecordable.clear();
        refusals.clear();
        trips.clear();
        startReported = false;
        noneLiveSince = 0;
        String brandLine = buildBrandLine();
        for (String key : keys) {
            recordingPlan.put(key, planFor(key, appConfig, infoBarOn, brandLine));
        }
        lanes.begin(keys, android.os.SystemClock.uptimeMillis());
        setRecording(true);
        for (String key : keys) {
            ensureLane(key);
        }
        // 录像计划定了：调度下一轮照它开相机、给开着的加上录像输出
        scheduler.wake();
        publishLanes();
        return true;
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
     *   <li>代数 +1：录制器晚到的回调、排着的回帖跑的时候对不上，作废、什么都不报；</li>
     *   <li>录像意图关了：每一路的表、计划清掉。在建的录制器回来对不上号就放（{@link #recorderBuilt}），在重新备好的
     *       回来看到这一次加入作废了，照「停 → 停放」收（{@link #rearmed}）；MediaRecorder 在备、在开录的回来看到这一趟作废了
     *       自己停（{@link #mediaPrepared}、{@link #mediaStarted}）；一路单独退出的那几路自己的收拾照常做完；</li>
     *   <li>编码录制：在录的、备好了还没开过录的录制器当场每一路都叫停（不等，{@link CodecVideoRecorder#beginStop}）；
     *       收拾线程上一起等 —— 先等编码线程都排空（{@link CodecVideoRecorder#awaitDrain}），再等写入线程都收好文件
     *       （{@link CodecVideoRecorder#finishStop}），几路共用一个期限（{@link CodecVideoRecorder#STOP_BUDGET_MS}）。
     *       MediaRecorder：在录的、备好了还没开录的当场每一路都叫停（不等，{@link VideoRecorder#stop}），各自在自己的线程上
     *       停下、收文件、放掉，收拾线程上共用同一个期限等；</li>
     *   <li>文件都封好了就报「收拾完了」（{@link PipelineCallback#onPipelineStopped}），协调器这时才开下一次 ——
     *       以前开 → 停 → 开两秒内，上一次的收拾会把这一次刚建的编码器一起放掉；</li>
     *   <li>编码录制的录制器接着每一路停放（{@link CodecVideoRecorder#park}：编码器放掉，EGL 和输入 SurfaceTexture 留着照常取帧）。
     *       不动相机：录像输出留在会话里（不要了、但还活着，照「减输出能拖就拖」），下一次开录计划一样就沿用；
     *       等下一次本来要动通道或关相机时调度一起摘，摘掉了才放录制器（{@link #onRecordOutputDropped}）。
     *       以前停录收拾完回主线程摘录像输出、100 ms 后重建会话 —— 每次停录每一路都要重配一次。
     *       MediaRecorder 不能停放：停了就放掉，它的 Surface 一放，会话里的录像输出就是死输出，调度马上摘
     *       （{@link #mediaStopsDone}）。</li>
     * </ol>
     *
     * @param skipRelayTransfer 是否跳过自动传输（用于远程录制，上传完成后再传输）
     */
    public void stopRecording(boolean skipRelayTransfer) {
        stopRecording(skipRelayTransfer, false);
    }

    /**
     * @param forRelease 管理器要释放了：编码录制的录制器停放好就交给调度、等它离开会话再放（{@link #releasing}）；
     *                   MediaRecorder 的录制器这一趟停完就放掉，手上没有一趟的现在一起放
     */
    private void stopRecording(boolean skipRelayTransfer, boolean forRelease) {
        final int gen = ++recordGeneration;
        AppLog.d(TAG, "stopRecording gen=" + gen + ", isRecording=" + isRecording + ", useCodecRecording="
                + useCodecRecording + ", skipRelayTransfer=" + skipRelayTransfer);

        // 车辆信号的登记（信息条、闪远光自动锁定）也只在录像期间，跟着录像状态撤（setRecording）
        setRecording(false);
        mainHandler.removeCallbacks(storageTick);
        StorageHelper.noteRecordingFallback(null);
        // 录像意图关了：每一路的表、计划清掉（这一次每一路进出了几次先记下，停录那一行要）
        final String tally = tripsText();
        lanes.clear();
        recordingPlan.clear();
        recordSources.clear();
        building.clear();
        unrecordable.clear();
        refusals.clear();
        trips.clear();
        startReported = false;
        noneLiveSince = 0;
        publishLanes();

        final File relayTarget = useRelayWrite && !skipRelayTransfer ? finalSaveDir : null;
        // 编码录制第一步就在这里：在录的、备好了还没开过录的，每一路都叫停（不等）。从这一刻起都不再录、不再分段、不再恢复，
        // 各自排空编码器、把收文件排在最后。以前在后台一路一路停，后面几路在等前面的时候还在录，可能跨过分段又开出新文件；
        // 三路各等十几秒，加起来超过协调器的收拾期限，协调器不等了、又开下一次，旧的还在写同一个盘。
        // 停放着的什么都不用做；管线放了的话直接交给调度，等它离开会话再放
        final List<Slot> stopping = new ArrayList<>();
        for (String key : CameraSlots.openOrder(new ArrayList<>(slots.keySet()))) {
            Slot slot = slots.get(key);
            if (slot == null) {
                continue;
            }
            if (slot.phase == SlotPhase.RUNNING || slot.phase == SlotPhase.PREPARED) {
                slot.phase = SlotPhase.STOPPING;
                try {
                    slot.recorder.beginStop();
                } catch (Exception e) {
                    AppLog.e(TAG, "Error asking the codec recorder of " + key + " to stop", e);
                }
                stopping.add(slot);
            } else if (forRelease && slot.idle()) {
                retire(slot, "随管线放了");
            }
        }
        // MediaRecorder：在录的、备好了还没开录的，每一路都叫停（不等），各自在自己的线程上停下、收文件、放掉 —— 停下的
        // MediaRecorder 不再取帧，不能像编码录制那样停放着留在会话里；叫停时它交出去的 Surface 当场放掉，会话里的录像输出
        // 就是死输出，调度马上摘。在备的、在开录的回来看到这一趟作废了，自己停（mediaPrepared、mediaStarted）
        final List<MediaSlot> mediaStopping = new ArrayList<>();
        final List<VideoRecorder.Stopping> mediaTickets = new ArrayList<>();
        // 在备、在开录的那几趟：做完那一步自己停、自己转存（relayOnStop）；它们的文件那时可能还开着，扫缓存时按名字跳过
        final Set<String> stillOpen = new HashSet<>();
        for (String key : CameraSlots.openOrder(new ArrayList<>(mediaSlots.keySet()))) {
            MediaSlot media = mediaSlots.get(key);
            if (media == null) {
                continue;
            }
            media.retired = true;
            if (media.phase == MediaPhase.RUNNING || media.phase == MediaPhase.READY) {
                media.phase = MediaPhase.STOPPING;
                media.nextPending = false;
                withdrawMedia(media);
                mediaTickets.add(media.recorder.stop());
                mediaStopping.add(media);
            } else if (media.phase == MediaPhase.PREPARING || media.phase == MediaPhase.STARTING) {
                media.relayOnStop = relayTarget;
                if (media.file != null) {
                    stillOpen.add(new File(media.file).getName());
                }
            }
        }
        if (!mediaStopping.isEmpty()) {
            // 它们交出去的 Surface 当场放了：会话里还带着的，调度马上摘
            scheduler.wake();
        }
        // 管线放了：手上没有一趟的 MediaRecorder 录制器现在一起放（有一趟的，那一趟停完再放，mediaGone）
        final List<VideoRecorder> mediaIdle = new ArrayList<>();
        if (forRelease) {
            for (java.util.Iterator<Map.Entry<String, VideoRecorder>> it = videoRecorders.entrySet().iterator();
                 it.hasNext(); ) {
                Map.Entry<String, VideoRecorder> entry = it.next();
                if (!mediaSlots.containsKey(entry.getKey())) {
                    mediaIdle.add(entry.getValue());
                    it.remove();
                }
            }
        }
        // 第二步（下面的收拾线程上）一起等它们排空、收好文件，共用这一个期限。按开机时长（不含深睡）算：
        // 等待用的计时、协调器的收拾期限都不算深睡
        final long codecStopDeadline = android.os.SystemClock.uptimeMillis() + CodecVideoRecorder.STOP_BUDGET_MS;
        useRelayWrite = false;
        finalSaveDir = null;
        final boolean codec = useCodecRecording;

        // 在收拾线程上停，不卡主线程。一次只收拾一份（teardown 是单线程的）：退出时「人停的」那一次和释放那一次、
        // 连着两次停，不会同时去动同一批录制器；一路单独退出排在前面的，这里排到时它已经收好了
        teardown.execute(() -> {
            final String[] results = new String[stopping.size()];
            final List<VideoRecorder.Stopped> mediaOutcomes = new ArrayList<>();
            final String[] mediaResults = new String[mediaStopping.size()];
            try {
                // 编码录制第二步：一起等，共用一个期限。先等编码线程都排空 —— 到点没排空的几路，收文件都在那一刻排上；
                // 再等写入线程都写完收好（到点没收好的不再等，没落盘的交给抢救）
                for (Slot slot : stopping) {
                    try {
                        slot.recorder.awaitDrain(codecStopDeadline);
                    } catch (Exception e) {
                        AppLog.e(TAG, "Error draining the codec recorder of " + slot.key, e);
                    }
                }
                for (int i = 0; i < stopping.size(); i++) {
                    Slot slot = stopping.get(i);
                    try {
                        slot.recorder.finishStop(codecStopDeadline);
                        // 整次停的文件由下面扫缓存一并转存，这里不单独转
                        results[i] = handOverStopped(slot.recorder, null);
                    } catch (Exception e) {
                        AppLog.e(TAG, "Error stopping the codec recorder of " + slot.key, e);
                        results[i] = "收拾它的录制器出错：" + e;
                    }
                }

                // MediaRecorder：一起等，共用同一个期限
                for (int i = 0; i < mediaStopping.size(); i++) {
                    VideoRecorder.Stopped outcome = mediaTickets.get(i).await(codecStopDeadline);
                    mediaOutcomes.add(outcome);
                    // 整次停的文件由下面扫缓存一并转存，这里不单独转
                    mediaResults[i] = mediaHandOver(outcome, null);
                }
                for (VideoRecorder idle : mediaIdle) {
                    idle.release();
                }

                // 中转写入：把缓存目录里的录像都转存到最终目录 —— 这个进程里被放弃的写入线程手上还开着的那几个除外
                // （半个文件转过去就坏了；它们要是回过神来收好了，留在缓存里，下一次中转写入停录时一起转存），
                // 还在开录的那几趟 MediaRecorder 的也除外（它们停的时候自己转存）
                if (relayTarget != null) {
                    AppLog.d(TAG, "Scheduling relay transfer for remaining files...");
                    File tempDir = new File(context.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
                    final File[] filesToTransfer = tempDir.exists()
                            ? tempDir.listFiles((dir, name) -> name.endsWith(".mp4")
                                    && !HELD_BY_STUCK_WRITERS.contains(new File(dir, name).getAbsolutePath())
                                    && !stillOpen.contains(name))
                            : null;
                    if (!HELD_BY_STUCK_WRITERS.isEmpty()) {
                        AppLog.w(TAG, "Relay transfer skips files still held by stuck writers: " + HELD_BY_STUCK_WRITERS);
                    }
                    laneHandler.postDelayed(() -> transferSpecificTempFiles(relayTarget, filesToTransfer), 500);
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Error in stopRecording", e);
            }

            if (!codec) {
                // MediaRecorder：停完的从表里拿掉、写停录那一行（先于报「收拾完了」：协调器接着开下一次时，这几路已经能另备）
                laneHandler.post(() -> mediaStopsDone(mediaStopping, mediaOutcomes, mediaResults, tally));
            }
            // 文件都封好了：报「收拾完了」。停放不算在里面 —— 协调器等的是文件（期限见 CodecVideoRecorder.STOP_BUDGET_MS），
            // 一路停放最多等 5 秒，几路加起来会超过它的收拾期限
            laneHandler.post(() -> pipelineStopped(gen));
            if (!codec) {
                return;
            }

            // 编码录制第三步：每一路停放（放掉编码器；EGL 和输入 SurfaceTexture 留着照常取帧，录像输出留在会话里）
            lanePrep.execute(() -> {
                final String[] parks = new String[stopping.size()];
                for (int i = 0; i < stopping.size(); i++) {
                    parks[i] = parkText(park(stopping.get(i)));
                }
                laneHandler.post(() -> lanesParked(stopping, results, parks, tally));
            });
        });

        AppLog.d(TAG, "All cameras stopped recording");
    }

    /**
     * 排在此刻已经排着的停录收拾后面叫 {@code done}（在收拾线程上叫，不在主线程）：整次停、一路单独退出的收拾都在
     * {@link #teardown} 这一条线程上按先后做（等编码线程排空、写入线程收好文件，期限 {@link CodecVideoRecorder#STOP_BUDGET_MS}），
     * 轮到它时前面那几次的文件都封好了（或者到了期限不再等）。退出的最后一步（MainActivity.finishExit）据此等文件收好再结束进程
     * —— 以前只等相机关完，U 盘慢时进程可能在最后一段收好之前就结束了（审查 2026-10-11）。管线放了也照样能用。
     */
    public void afterStopsFinished(Runnable done) {
        teardown.execute(done);
    }

    /** 停录收拾完了（主线程，文件都封好了）：报给协调器。之后又停过、又开过的不算数（那一次自己报）。 */
    private void pipelineStopped(int gen) {
        if (gen != recordGeneration) {
            AppLog.d(TAG, "stopRecording gen=" + gen + " superseded by gen=" + recordGeneration);
            return;
        }
        AppLog.d(TAG, "stopRecording completed gen=" + gen);
        if (pipelineCallback != null) {
            pipelineCallback.onPipelineStopped();
        }
    }

    /**
     * 停录叫停的那几个录制器停放好了（主线程）：写停录那一行（各路的文件、停放、这一次录像每一路进出了几次），
     * 然后每一个看还用不用（{@link #slotIdle}：管线放了就交出去放掉；又开了录的看能不能加入）。
     */
    private void lanesParked(List<Slot> parked, String[] results, String[] parks, String tally) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < parked.size(); i++) {
            Slot slot = parked.get(i);
            lines.add(roleName(slot.key) + " " + (results[i] == null ? "没收完（收拾出错）" : results[i])
                    + (parks[i] == null ? "" : parks[i]));
        }
        noteStopped(lines, "录制器停放（编码器放掉），" + (releasing
                ? "管线放了：等调度关完相机、录像输出离开会话再放"
                : "录像输出留在会话里，等下一次本来要动通道或关相机时一起摘"), tally);
        for (Slot slot : parked) {
            slotParked(slot, null, null);
        }
    }

    // ---- 录像的黑匣子行：两种录制器共用（黑匣子目录里每一行在代码里只有一处）

    /** 「录像：相机 2（环视）录制器建好了 1280x5140，… ms」，后面跟 {@code extra}。 */
    private static void noteRecorderBuilt(String name, int width, int height, long ms, String extra) {
        com.kooo.evcam.blackbox.BlackBox.note("录像：相机 " + name + " 录制器建好了 " + width + "x" + height + "，" + ms
                + " ms" + extra);
    }

    /** 「录像：相机 … 录制器建不起来（…，… ms）：这个录像意图不录这一路，别的路照录」。 */
    private static void noteRecorderFailed(String name, String why, long ms) {
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + name + " 录制器建不起来（" + why + "，" + ms
                + " ms）：这个录像意图不录这一路，别的路照录");
    }

    /** 一路要退出时它的录制器还没开录：「录像：相机 … 退出录像（为什么）：…」。 */
    private void noteLeftUnstarted(String key, String why, String what) {
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + laneName(key) + " 退出录像（" + why + "）：" + what);
    }

    /**
     * 「录像：相机 … 退出录像（为什么，用了 N 秒）：文件 … 收好了；录制器 …，别的路照录：…」。
     *
     * @param doneAt   收拾完的时刻（uptime）
     * @param recorder 录制器怎样了（停放、放掉）
     */
    private void noteLeft(String name, LaneChange change, long doneAt, String result, String recorder) {
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + name + " 退出录像（" + change.why + "，用了 "
                + seconds(doneAt - change.startedAt) + "）：" + result + "；" + recorder + "，别的路照录："
                + othersDuring(change));
    }

    /**
     * 「录像：相机 … 的会话带不上录像输出（配不上，被相机丢了；连着第 N 次）：这一路退出，别的路照录；隔 12 秒再声明一次」，
     * 到了次数是「…；等这一路的相机走了回来、登记表变了或者亮屏时再试」。
     */
    private static void noteSessionRefused(String name, int count, boolean again) {
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + name + " 的会话带不上录像输出（配不上，被相机丢了；连着第 "
                + count + " 次）：这一路退出，别的路照录；" + (again
                ? "隔 " + (CameraLiveness.RETRY_GAP_MS / 1000) + " 秒再声明一次"
                : "等这一路的相机走了回来、登记表变了或者亮屏时再试"));
    }

    /**
     * 停录那一行：「停录：环视 文件 … 收好了；… —— 录制器怎样了；这一次录像：…」。没有在录的录制器、也没有进出过的，不写。
     *
     * @param lines    各路的文件
     * @param recorder 录制器怎样了（停放、放掉），录像输出怎么办
     * @param tally    这一次录像每一路进出了几次（{@link #tripsText}）
     */
    private static void noteStopped(List<String> lines, String recorder, String tally) {
        if (lines.isEmpty() && tally.isEmpty()) {
            return;
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("停录：" + (lines.isEmpty() ? "没有在录的录制器"
                : String.join("；", lines) + " —— " + recorder) + (tally.isEmpty() ? "" : "；这一次录像：" + tally));
    }

    /** 黑匣子：这个录像意图里每一路进出了几次，「环视 加入 1 次，后座舱 加入 2 次、退出 1 次，前座舱 没录起来」。 */
    private String tripsText() {
        List<String> parts = new ArrayList<>();
        for (String key : lanes.keys()) {
            LaneTrip trip = trips.get(key);
            int joins = trip == null ? 0 : trip.joins;
            int leaves = trip == null ? 0 : trip.leaves;
            parts.add(roleName(key) + (joins == 0 ? " 没录起来"
                    : " 加入 " + joins + " 次" + (leaves == 0 ? "" : "、退出 " + leaves + " 次")));
        }
        return String.join("，", parts);
    }

    // ------------------------------------------------------------------ MediaRecorder 模式的路（2.11 阶段 5d）
    // 开发者选项里的非默认模式，和编码录制走同一张表、同一套加入 / 退出（RecordingLanes）：每一路备一个 MediaRecorder、
    // 声明它的 Surface 作录像输出，相机带着它出了画面就开录；相机走了这一路自己停掉、收文件，回来时另备一个再加入。
    // 从不动相机：以前开录、分段、看门狗重建都是这里自己设录像 Surface、重建会话（3 秒会话计数、等画面稳定、300 ms 延迟）。

    /**
     * MediaRecorder：这一路现在备不备这一趟（主线程）。在录像计划里、在等着、手上没有一趟、这个意图还录它、画面尺寸算出来了，
     * 而且相机在出画面或者正要开（{@link RecordingLanes#keepsNamedRecorder}）—— 文件名在备好时就定了，被拿着、在救、停手、
     * 熄屏放开时不备，回来时再备。在 {@link #lanePrep} 上备，备好回主线程声明录像输出（{@link #mediaPrepared}）。
     */
    private void ensureMedia(String key) {
        if (!isRecording || useCodecRecording || !recordingPlan.containsKey(key) || unrecordable.contains(key)
                || refused(key) || mediaSlots.containsKey(key) || lanes.state(key) != RecordingLanes.State.WAITING) {
            return;
        }
        SingleCamera camera = cameras.get(key);
        if (camera == null || !RecordingLanes.keepsNamedRecorder(scheduler.condition(key), scheduler.cannotOpen(key),
                scheduler.releasedByDark(key))) {
            return;
        }
        final Size size = camera.resolvedSize();
        if (size == null) {
            AppLog.i(TAG, "Recording lane " + key + " waits for its picture size (camera not listed yet)");
            return;
        }
        final VideoRecorder recorder = videoRecorderFor(key, camera);
        final MediaSlot media = new MediaSlot(key, camera, recorder);
        recorder.setCallback(laneCallback(media.tag));
        recorder.setSegmentOutputs((next, leaving) -> laneHandler.post(() -> mediaNextSegment(media, next, leaving)));
        mediaSlots.put(key, media);
        StreamSpec spec = recordingPlan.get(key).spec;
        // MediaRecorder 只接受一个具体数字，没有「不限制」这个说法
        final int frameRate = RecordSpecs.nominal(spec.fps, hardwareMaxFps());
        final long segmentMs = RecordSpecs.segmentMs(spec.segmentMinutes);
        final int bitrate = AppConfig.actualBitrate(spec.bitrate, size.getWidth(), size.getHeight(), frameRate);
        final File dir = laneDir();
        final long startedAt = android.os.SystemClock.uptimeMillis();
        lanePrep.execute(() -> {
            VideoRecorder.Prepared prepared;
            try {
                prepared = recorder.prepare(dir, size, frameRate, bitrate, segmentMs);
            } catch (Exception e) {
                AppLog.e(TAG, "Preparing the MediaRecorder of " + key + " failed", e);
                prepared = null;
            }
            final VideoRecorder.Prepared result = prepared;
            final long ms = android.os.SystemClock.uptimeMillis() - startedAt;
            laneHandler.post(() -> mediaPrepared(media, result, ms));
        });
    }

    /** 这一路的 MediaRecorder 录制器：没有就建一个（这一路换了一台相机的，旧的放掉）。主线程。 */
    private VideoRecorder videoRecorderFor(String key, SingleCamera camera) {
        VideoRecorder recorder = videoRecorders.get(key);
        if (recorder != null && !recorder.cameraId().equals(camera.getCameraId())) {
            videoRecorders.remove(key);
            releaseVideoRecorderLater(recorder);
            recorder = null;
        }
        if (recorder == null) {
            recorder = new VideoRecorder(camera.getCameraId(), key);
            // 共用的分段名字：几路换段时拿同一个
            recorder.setTimestampProvider(segmentTimestampProvider);
            videoRecorders.put(key, recorder);
        }
        return recorder;
    }

    /**
     * MediaRecorder 这一趟备好了（主线程）：声明录像输出、叫醒调度 —— 相机关着的，调度开的时候就带着它；开着的，调度排一步
     * 「改输出」加上它。备不起来的，这个意图不录这一路；计划里每一路都录不了，整次停（START_FAILED）。
     * 停过录、管线放了、相机换了一台，或者备的这一会儿相机被拿走了、在救：这一个用不上，停掉放掉（文件是空的，删掉）。
     */
    private void mediaPrepared(MediaSlot media, VideoRecorder.Prepared prepared, long ms) {
        String key = media.key;
        boolean current = !media.retired && mediaSlots.get(key) == media && isRecording
                && cameras.get(key) == media.camera;
        if (prepared == null || prepared.surface == null) {
            if (current) {
                unrecordable.add(key);
                noteRecorderFailed(laneName(key), "MediaRecorder：" + (prepared == null ? "出错" : prepared.failure), ms);
                checkAllUnrecordable();
                scheduler.wake();
            } else {
                AppLog.w(TAG, "MediaRecorder of " + key + " could not be prepared after the run was dropped: "
                        + (prepared == null ? "error" : prepared.failure));
            }
            // 到点没备好的（录制器线程卡住了）换一个；它回过神来备好的那一个自己放掉
            mediaGone(media, prepared != null && prepared.stuck ? null : VideoRecorder.Stopped.NOTHING);
            publishLanes();
            return;
        }
        media.surface = prepared.surface;
        media.out = new ChannelRules.Out(ChannelRules.Kind.RECORD, prepared.surface);
        media.file = prepared.file;
        media.preparedAt = android.os.SystemClock.uptimeMillis();
        if (!current || !RecordingLanes.keepsNamedRecorder(scheduler.condition(key), scheduler.cannotOpen(key),
                scheduler.releasedByDark(key))) {
            media.retired = true;
            stopMedia(media, null, media.relayOnStop);
            return;
        }
        media.phase = MediaPhase.READY;
        final android.view.Surface surface = prepared.surface;
        media.camera.declareRecord(surface, surface, why -> onMediaOutputDropped(media, surface, why));
        noteRecorderBuilt(laneName(key), prepared.width, prepared.height, ms,
                "（MediaRecorder，文件 " + new File(prepared.file).getName() + " 按此刻起名）");
        scheduler.wake();
        tryJoinMedia(key);
        publishLanes();
    }

    /**
     * MediaRecorder：这一路现在能不能加入，能就加入（主线程）：在等着、这一趟备好了、相机在出画面、会话带着它的录像输出
     * （{@link RecordingLanes#joins}）。在 {@link #lanePrep} 上开录，回主线程（{@link #mediaStarted}）。
     */
    private void tryJoinMedia(String key) {
        MediaSlot media = mediaSlots.get(key);
        if (!isRecording || media == null || media.retired || media.phase != MediaPhase.READY) {
            return;
        }
        boolean streaming = scheduler.usable(key);
        boolean carried = media.camera.sessionOutputs().carries(media.out);
        if (!RecordingLanes.joins(lanes.state(key), RecordingLanes.Recorder.READY, streaming, carried)) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        final long frameAt = Math.max(media.camera.firstFrameOfSessionAt(), lanes.sinceMs(key));
        final int token = lanes.starting(key, now);
        media.phase = MediaPhase.STARTING;
        media.tag.gen = recordGeneration;
        media.tag.token = token;
        lanePrep.execute(() -> {
            long from = android.os.SystemClock.uptimeMillis();
            boolean ok;
            try {
                ok = media.recorder.start();
            } catch (Exception e) {
                AppLog.e(TAG, "Starting the MediaRecorder of " + key + " failed", e);
                ok = false;
            }
            final boolean started = ok;
            final long ms = android.os.SystemClock.uptimeMillis() - from;
            laneHandler.post(() -> mediaStarted(media, token, frameAt, started, ms));
        });
        publishLanes();
    }

    /**
     * MediaRecorder 开录回来了（主线程）。开不起来的：这个意图不录这一路（MediaRecorder 不肯开，换一个多半也一样；
     * 计划里每一路都录不了，整次停）。这一次加入作废了（这一路又退出了、停了录、管线放了）：开起来的这一段马上停、收文件。
     */
    private void mediaStarted(MediaSlot media, int token, long frameAt, boolean ok, long ms) {
        String key = media.key;
        boolean current = !media.retired && mediaSlots.get(key) == media && isRecording && lanes.current(key, token);
        if (!ok) {
            media.tag.gen = -1;
            if (current) {
                lanes.waiting(key, android.os.SystemClock.uptimeMillis());
                unrecordable.add(key);
                com.kooo.evcam.blackbox.BlackBox.noteImportant("录像：相机 " + laneName(key) + " 开录没成（MediaRecorder 不肯开，"
                        + ms + " ms）：这个录像意图不录这一路，别的路照录");
                checkAllUnrecordable();
            }
            media.retired = true;
            stopMedia(media, null, media.relayOnStop);
            publishLanes();
            return;
        }
        if (!current) {
            media.retired = true;
            stopMedia(media, null, media.relayOnStop);
            return;
        }
        media.phase = MediaPhase.RUNNING;
        // 会话带上了它的录像输出：带不上的账清掉
        refusals.remove(key);
        noteJoined(key, frameAt, "文件 " + new File(media.file).getName() + "（备好时起的名，备好到开录 "
                + seconds(android.os.SystemClock.uptimeMillis() - media.preparedAt) + "）", "（开录用了 " + ms + " ms）");
        publishLanes();
    }

    /**
     * MediaRecorder：这一路带着一份新会话出了画面（主线程）。换段时新的一段带进来了就开它（{@link VideoRecorder#segmentCarried}）；
     * 在等着的看要不要备、能不能加入。
     */
    private void mediaStreaming(String key) {
        MediaSlot media = mediaSlots.get(key);
        if (media != null && media.phase == MediaPhase.RUNNING && media.nextPending
                && media.camera.sessionOutputs().carries(media.out)) {
            media.nextPending = false;
            media.recorder.segmentCarried(media.surface);
        }
        ensureMedia(key);
        tryJoinMedia(key);
    }

    /**
     * MediaRecorder：这一路的相机换了样子（主线程）。备好还没开录的那一趟，相机被拿走了、在救、停手了、关着而现在用不了相机、
     * 熄屏放开了 —— 停掉放掉，回来时按那一刻另备（文件名在备好时就定了，{@link RecordingLanes#keepsNamedRecorder}）；
     * 手上没有一趟的看要不要备。
     */
    private void mediaConditionChanged(String key) {
        MediaSlot media = mediaSlots.get(key);
        if (media == null) {
            ensureMedia(key);
            return;
        }
        ChannelRules.Condition condition = scheduler.condition(key);
        boolean cannotOpen = scheduler.cannotOpen(key);
        boolean dark = scheduler.releasedByDark(key);
        if (media.phase == MediaPhase.READY && !media.retired
                && !RecordingLanes.keepsNamedRecorder(condition, cannotOpen, dark)) {
            AppLog.i(TAG, "MediaRecorder of " + key + " was prepared but the camera is " + condition
                    + (cannotOpen ? " (cannot open now)" : "") + (dark ? " (released by the dark screen)" : "")
                    + ": released, a new one is prepared when it comes back");
            media.retired = true;
            stopMedia(media, null, null);
        }
    }

    /**
     * MediaRecorder 换段（主线程，录制器备好了下一段）：新的一段声明进来、顶替旧的 —— 旧的不再声明，就是「要走」：
     * 调度那一步「改输出」加上新的、顺带摘掉旧的，先停止往旧的送帧再重配（SingleCamera.applyOutputs）。旧的交给调度，
     * 离开会话了才叫录制器停它、收文件（{@link VideoRecorder#segmentLeft}）；新的带进会话出了画面才开（{@link #mediaStreaming}）。
     * 这一趟在停、停了的什么都不声明：停那一下两段一起收。
     */
    private void mediaNextSegment(MediaSlot media, android.view.Surface next, android.view.Surface leaving) {
        if (media.retired || mediaSlots.get(media.key) != media || media.phase != MediaPhase.RUNNING) {
            return;
        }
        media.surface = next;
        media.out = new ChannelRules.Out(ChannelRules.Kind.RECORD, next);
        media.nextPending = true;
        media.camera.declareRecord(next, next, why -> onMediaOutputDropped(media, next, why));
        final VideoRecorder recorder = media.recorder;
        scheduler.handOver(media.key, new ChannelRules.Out(ChannelRules.Kind.RECORD, leaving),
                () -> recorder.segmentLeft(leaving));
        scheduler.wake();
        AppLog.d(TAG, "MediaRecorder of " + media.key + " switches segment: next output declared, the old one leaves");
    }

    /**
     * MediaRecorder 这一趟声明着的录像输出离开了会话（主线程）：随相机关了、相机层配不上会话丢了它、它的 Surface 失效了。
     * 在录、在开录的这一路退出；备好还没开录的，配不上、失效了就停掉放掉（关了的照样声明着，相机开的时候带着它）。
     * 会话配不上的是相机那一侧的事：隔一会儿再备一个，连着几次都带不上就等它的相机走了再来（{@link #sessionRefused}）。
     */
    private void onMediaOutputDropped(MediaSlot media, android.view.Surface surface, SingleCamera.Drop why) {
        String key = media.key;
        if (media.retired || mediaSlots.get(key) != media || media.surface != surface) {
            return;
        }
        String text = dropText(why);
        boolean configFailed = why == SingleCamera.Drop.CONFIG_FAILED;
        if (configFailed && isRecording && recordingPlan.containsKey(key)) {
            sessionRefused(key, mediaName(media));
        }
        if (media.phase == MediaPhase.RUNNING || media.phase == MediaPhase.STARTING) {
            leaveLane(key, "它的录像输出" + text);
        } else if (media.phase == MediaPhase.READY && (configFailed || why == SingleCamera.Drop.RELEASED)) {
            media.retired = true;
            stopMedia(media, null, null);
        }
        scheduler.wake();
        publishLanes();
    }

    /** 撤回这一趟声明着的录像输出（主线程）。 */
    private static void withdrawMedia(MediaSlot media) {
        if (media.surface != null) {
            media.camera.withdrawRecord(media.surface);
        }
    }

    /**
     * MediaRecorder 这一路的这一趟停掉（主线程叫，不等）：撤回它的录像输出；录制器当场放掉它交出去的 Surface，会话里还带着它的话
     * 就是死输出，调度马上摘（停下的 MediaRecorder 不再取帧，留在会话里那一路整条重复请求会停下）；录制器线程上停下、收好文件、
     * 放掉（{@link VideoRecorder#stop}）。收拾线程上等结果（期限 {@link CodecVideoRecorder#STOP_BUDGET_MS}），回主线程
     * （{@link #mediaStopped}）。中转写入只转存这一趟收掉的那几个文件：缓存目录里别的路还在写。
     *
     * @param change      一路退出的这一段（黑匣子那一行要）；作废的、放掉的那几趟是 null
     * @param relayTarget 中转写入的目标目录；null = 不转存
     */
    private void stopMedia(MediaSlot media, LaneChange change, File relayTarget) {
        media.phase = MediaPhase.STOPPING;
        media.nextPending = false;
        withdrawMedia(media);
        final VideoRecorder.Stopping ticket = media.recorder.stop();
        scheduler.wake();
        final long deadline = android.os.SystemClock.uptimeMillis() + CodecVideoRecorder.STOP_BUDGET_MS;
        teardown.execute(() -> {
            VideoRecorder.Stopped outcome = ticket.await(deadline);
            String text;
            try {
                text = mediaHandOver(outcome, relayTarget);
            } catch (Exception e) {
                AppLog.e(TAG, "Error handing over the files of the MediaRecorder of " + media.key, e);
                text = "收拾它的文件出错：" + e;
            }
            final String result = text;
            laneHandler.post(() -> mediaStopped(media, change, outcome, result));
        });
    }

    /**
     * 一趟 MediaRecorder 收掉了哪些文件（收拾线程上）：黑匣子一句；中转写入时把它们转存到这一次录像的目标目录。
     * 到点没停下的（录制器线程卡住了，多半是盘），那个还开着的文件记进 {@link #HELD_BY_STUCK_WRITERS}，整次停扫缓存时跳过它。
     */
    private String mediaHandOver(VideoRecorder.Stopped outcome, File relayTarget) {
        if (outcome.held != null) {
            HELD_BY_STUCK_WRITERS.add(outcome.held);
        }
        List<File> files = new ArrayList<>();
        StringBuilder names = new StringBuilder();
        for (String path : outcome.files) {
            File file = new File(path);
            if (!file.exists()) {
                continue;
            }
            files.add(file);
            names.append(names.length() > 0 ? "、" : "").append(file.getName());
        }
        if (relayTarget != null && !files.isEmpty()) {
            final File[] transfer = files.toArray(new File[0]);
            laneHandler.postDelayed(() -> transferSpecificTempFiles(relayTarget, transfer), 500);
        }
        if (!outcome.finished) {
            return "MediaRecorder " + (CodecVideoRecorder.STOP_BUDGET_MS / 1000) + " 秒还没停下"
                    + (outcome.held == null ? "" : "（" + new File(outcome.held).getName() + " 没收好）")
                    + "，这一路换一个新的录制器";
        }
        return names.length() == 0 ? "没有留下文件" : "文件 " + names + " 收好了";
    }

    /** MediaRecorder 一路退出的这一趟停完了（主线程）：退出的那一路写一行（文件、别的路怎样），从表里拿掉。 */
    private void mediaStopped(MediaSlot media, LaneChange change, VideoRecorder.Stopped outcome, String result) {
        if (change != null) {
            noteLeft(mediaName(media), change, android.os.SystemClock.uptimeMillis(), result,
                    "录制器放掉（MediaRecorder 不能停放，回来时另备一个）");
        } else {
            AppLog.i(TAG, "MediaRecorder run of " + media.key + " stopped: " + result);
        }
        mediaGone(media, outcome);
        publishLanes();
    }

    /**
     * 整次停叫停的那几趟 MediaRecorder 都停完了（主线程）：写停录那一行（各路的文件、这一次录像每一路进出了几次），
     * 每一趟从表里拿掉（{@link #mediaGone}）。
     */
    private void mediaStopsDone(List<MediaSlot> stopped, List<VideoRecorder.Stopped> outcomes, String[] results,
                                String tally) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < stopped.size(); i++) {
            MediaSlot media = stopped.get(i);
            lines.add(roleName(media.key) + " " + (results[i] == null ? "没收完（收拾出错）" : results[i]));
            mediaGone(media, i < outcomes.size() ? outcomes.get(i) : null);
        }
        noteStopped(lines, "MediaRecorder 停了就放掉，录像输出马上摘", tally);
    }

    /**
     * 一趟停完了（主线程）：从表里拿掉。录制器没停下的（{@code outcome} 是 null 或者没停完）换掉 —— 它回过神来自己收，
     * 这一路下一趟用新的；管线放了的放掉。还在录、这一路还要录的，看能不能另备一趟。
     */
    private void mediaGone(MediaSlot media, VideoRecorder.Stopped outcome) {
        String key = media.key;
        if (mediaSlots.get(key) == media) {
            mediaSlots.remove(key);
        }
        boolean stuck = outcome == null || !outcome.finished;
        if ((stuck || releasing) && videoRecorders.get(key) == media.recorder) {
            videoRecorders.remove(key);
            releaseVideoRecorderLater(media.recorder);
        }
        ensureMedia(key);
    }

    /** 放掉一个 MediaRecorder 录制器（它这一趟停掉、线程退出要一会儿）：放在收拾线程上。 */
    private void releaseVideoRecorderLater(VideoRecorder recorder) {
        teardown.execute(() -> {
            try {
                recorder.release();
            } catch (Exception e) {
                AppLog.e(TAG, "Error releasing a MediaRecorder recorder", e);
            }
        });
    }

    /** 黑匣子里一趟 MediaRecorder 那一路的叫法（管线放了以后 cameras 清空了也叫得出）。 */
    private static String mediaName(MediaSlot media) {
        return media.camera.getCameraId() + "（" + roleName(media.key) + "）";
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
        released = true;
        // 调度还要按次序关一阵：这期间被系统收走的画布交给这一份（ownerOf），等它离开会话再放
        lastReleased = new java.lang.ref.WeakReference<>(this);
        // 登记表的监听只在还是自己时才摘：换车型时旧的这份晚 release，不能把新那份接上的监听摘了
        CameraNeeds.current().clearListener(needsListener);
        com.kooo.evcam.screen.ScreenState.removeListener(screenWake);
        com.kooo.evcam.telemetry.Telemetry.get().removeListener(vehicleWake);
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

            // 2. 停止录制：编码录制的录制器收好文件、停放，管线放了（releasing）就交给调度，等它把相机关完、
            //    录像输出离开会话再放（retire）—— 会话还带着它时放掉，它就成了死输出。MediaRecorder 的录制器这一趟停完就放
            //    （它停了就不再取帧，Surface 一放调度就摘；mediaGone），手上没有一趟的在停录那条路上一起放。
            //    以前这里接着又在本线程上放一遍，和停录线程同时动同一批录制器。
            //    收拾完了照样报给协调器：它要是还以为在录，就按「管线没了」处理
            releasing = true;
            try {
                stopRecording(false, true);
            } catch (Exception e) {
                AppLog.e(TAG, "Error stopping recording during release", e);
            }

            // 3. 相机交给调度：同步写下「退出」、置 closingAnywhere，登记表从此作废；手上那一步做完后按次序关
            //    （环视单独先关，座舱一起关），关完调度自己停、放掉交给它的录制器。这里不等（退出的最后一步 finishExit 等
            //    closingAnywhere）。调度过了「退出最多等」还没停（一次关卡死在相机服务里），录制器不再等，直接放
            try {
                scheduler.exit();
            } catch (Exception e) {
                AppLog.e(TAG, "Error handing the cameras to the scheduler during release", e);
            }
            laneHandler.removeCallbacks(leftoversTask);
            laneHandler.postDelayed(leftoversTask, ChannelRules.EXIT_WAIT_MAX_MS);
            
        } catch (Exception e) {
            AppLog.e(TAG, "Unexpected error during release", e);
        } finally {
            // 4. 清理集合（确保执行）
            cameras.clear();
            setRecording(false);
            AppLog.d(TAG, "All resources released");
        }
    }

    /**
     * 这一份已经放了（{@link #release}）：外部据此判断实例是否已失效。
     *
     * <p>以前看 cameras 是不是空的。2.11 阶段 6 起管线在 CameraManagerHolder 里先建出对象、相机列表和合成流探测在相机线程上做完才
     * {@link #initCameras}：还在建的那一份 cameras 也是空的，却不是放了 —— 照旧看空的话，它一建出来就被当成放了、再建一份。</p>
     */
    public boolean isReleased() {
        return released;
    }

    /**
     * 建好了：相机列表、合成流探测做完，各路相机交给调度了（{@link #initCameras}，CameraManagerHolder 在主线程上调）。
     * 开录等它（RecordingCoordinator）；一路相机都没分到的也算建好了。
     */
    public boolean isBuilt() {
        return camerasBuilt;
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

        /** 配置里一路都没启用：不拍（和拒录同一个判断）。和 {@link #onPhotoResult} 二选一。 */
        default void onNoCameraEnabled() {
            onPhotoResult(0, 0, 0);
        }
    }

    /** 等各路出画面最多多久：冷开相机，加上调度第一次救（8 秒没画面就重建会话，{@link CameraLiveness#STUCK_MS}）都等得到。 */
    private static final long PHOTO_READY_TIMEOUT_MS = 10_000L;
    /** 按下快门后等各路存完最多多久（一张 JPEG 120–200 ms，加解码、盖角标、写盘）。 */
    private static final long PHOTO_SAVE_TIMEOUT_MS = 5_000L;
    /** 最近多久里出过画面算「在出画面」。 */
    private static final long PHOTO_FRESH_MS = 1_500L;
    /** 各路快门错开多久，免得几路同时解码、编码。 */
    private static final long PHOTO_STAGGER_MS = 300L;
    /** 等各路出画面时多久看一眼。 */
    private static final long PHOTO_POLL_MS = 200L;

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
        if (com.kooo.evcam.profile.RecordSpecs.enabledCameraKeys(context).isEmpty()) {
            // 此刻的配置里一路都没启用：当场拒，和拒录读同一个（RecordingCoordinator 开录前问的也是此刻的配置），管线建没建好都一样。
            // 拍哪几路照管线建它那一刻启用的（登记表 §一「启用的全部」）；两者对不上（改了配置没重启）时 stepPhoto 不白等
            callback.onNoCameraEnabled();
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
        // 拍启用的那几路（登记表 §一）：只为超级镜子、盲区画面开着的环视不拍
        List<String> keys = enabledActiveKeys();
        // 调度判出被别的程序拿着（含被拿着的另一路挡着）、救了 3 轮停手的那几路这一张不拍、也不算在该拍的里面
        // （2026-10-10）：它们等到点也出不了画面 —— 以前每一张都白等满 10 秒，再报「有几路没出画面」。
        // 熄屏放开的座舱也一样（项目所有者 2026-10-12 D2，设计 v3.1 C4）：熄屏拍的那一张只有环视。
        // 通道算黑、没在开车时不为拍照开相机（2026-10-27）：不在出画面的一路都不拍，一路都没有就当场收尾。
        // 一路只要在出画面就算数，哪怕它的录像路不在（2.11：以前录像里不在录的那几路也不拍）
        List<String> leftOut = new ArrayList<>();
        for (java.util.Iterator<String> it = keys.iterator(); it.hasNext(); ) {
            String key = it.next();
            if (scheduler.excludedFromPhoto(key)) {
                ChannelRules.Condition condition = scheduler.condition(key);
                leftOut.add(laneName(key) + (scheduler.releasedByDark(key) ? channelSide.darkText(key)
                        : condition == ChannelRules.Condition.TAKEN ? "被别的程序拿着"
                        : scheduler.parkedDark() && condition != ChannelRules.Condition.GAVE_UP ? "熄屏：不为拍照开相机"
                        : "救了 " + CameraLiveness.MAX_CYCLES + " 轮没用、停手了"));
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
        // 每一路都被拿着或停手了：没有可等的，不白等到点
        // 管线建好了却一路可拍都没有（建管线之后才在配置里打开的那一路，重启应用后才生效）：也没有可等的
        boolean nothingToWaitFor = keys.isEmpty() && (!leftOut.isEmpty() || isBuilt());
        if (!allReady && !nothingToWaitFor && now < job.readyDeadline) {
            if (!job.toldWaiting) {
                job.toldWaiting = true;
                job.callback.onWaitingForCameras();
            }
            mainHandler.postDelayed(photoTick, PHOTO_POLL_MS);
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
