package com.kooo.evcam.camera;


import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.camera.CloseChain.Closing;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.SystemClock;
import android.os.HandlerThread;
import android.util.Size;
import android.hardware.camera2.params.OutputConfiguration;
import android.os.Build;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个摄像头管理类
 *
 * <h3>2.11 起：给通道调度的接口（channel-logic §二，项目所有者 2026-10-10 确认）</h3>
 *
 * <p>项目所有者定：一个通道调度（ChannelScheduler）是唯一动相机的地方。这一类只照做、只报事实，自己不判该不该开、
 * 该带哪几样输出。接口分五块：</p>
 *
 * <ul>
 *   <li><b>动作</b>（只给调度调，主线程）：{@link #open(Set)}、{@link #applyOutputs}、{@link #rebuildSession}、
 *       {@link #reopen}、{@link #closeCamera(String)}。会话只按给定的那一组输出（{@link ChannelRules.Out}）建，
 *       外加常驻的拍照 JPEG 输出；带着录像输出才用 {@code TEMPLATE_RECORD}。</li>
 *   <li><b>声明</b>（谁都能调，主线程，<b>不触发任何重建</b>）：预览、后视镜、盲区画面、录像各自声明自己的消费者、各自撤回。
 *       同一块画布（同一个 SurfaceTexture）再声明一次什么都不做 —— 主界面每次画布就绪都把各路重新声明一遍，
 *       主界面重建时我们留住的画布被新界面接回也还是它（大纲 §7.6）。关相机只清会话和设备，不清声明。</li>
 *   <li><b>事实</b>（主线程读）：{@link #sessionOutputs()}、{@link #declaredOutputs()}、{@link #deadInSession()}、
 *       {@link #firstFrameOfSessionAt()}、{@link #resolvedSize()}、{@link #busySince()}。</li>
 *   <li><b>回调</b>：{@link CameraCallback} 下半截那几个，在相机线程上发。</li>
 *   <li><b>诊断</b>（调度交来，主线程）：会话里每一样输出算要的、留着的、还是死的（{@link #noteOutputStates}），
 *       只给卡顿现场标出来（{@link #describeForStall}）。</li>
 * </ul>
 *
 * <p><b>调度之外唯一允许的相机调用是请求级的</b>：画面调节（{@link #updateImageAdjustParams}）只改重复请求，
 * 拍照（{@link #takePicture}）只往常驻的 JPEG 输出发一张静态请求 —— 都不改会话、不改输出、不开不关。
 * 两样都在这一路的相机线程上发，这一路有一步在途时都不发（{@link #stepInFlight}）：画面调节下一次建会话时带上最新的参数，
 * 拍照改抓预览（项目所有者 2026-10-14 R1：动相机的都归通道调度管，请求级的从不和调度的一步叠在一起）。</p>
 *
 * <p><b>每一种关都登记成在关</b>（{@link #CLOSING}，2.11.4 R1 / 1c）：我们关的、丢了之后的关、重开时关旧的、作废卡住的那一轮、
 * 晚到的设备就地关 —— 同一台相机后面的开先等它关完，调度也认它在途（{@link #closingSince}）。以前重开时关旧设备、
 * 晚到的设备就地关不登记，调度看不见它们：重开那一步 15 秒到点、旧设备还在关，别的路照样动。</p>
 *
 * <p><b>关没返回就不开</b>（项目所有者 2026-10-28）：我们上一次关这一台相机还没返回，App 里谁都不开它，直到那一次关返回、
 * 或者进程结束 —— 不设时限，相机服务说它空闲也不算数（同一个进程里问只是读本进程的缓存；我们还开着的相机也被报过空闲，
 * 平台笔记 §3.1）。这一类是最后一道：开、重开在相机线程上等它返回（{@link #waitForNoClose}），关完时等之前的关返回
 * （{@link #finishClosing}），在途的开、重开、关合并掉再来的（{@link #openOrCloseInFlight}）。以前这几处都是 60 秒到点照样往下走。</p>
 */
public class SingleCamera {
    private static final String TAG = "SingleCamera";

    private final Context context;
    private final String cameraId;
    /** 出帧结果的心跳，给卡顿监测用（见 StallWatch）。 */
    private Heartbeat captureBeat;
    private TextureView textureView;
    private CameraCallback callback;
    private String cameraPosition;  // 摄像头位置（front/back/left/right）
    /** 最近一次按配置摆位的结果，只给诊断报告看。 */
    private volatile String laneTransformNote = "还没试过";
    /** 挂在预览视图上的重算监听：视图尺寸一变就重新摆位（见 {@link #watchLaneLayout}）。 */
    private android.view.View.OnLayoutChangeListener laneLayoutWatch;
    /**
     * 重算监听此刻挂在哪个视图上（只在主线程改）。弱引用：管线比主界面活得久，不能因为它留住销毁了的界面。
     *
     * <p>以前只挂一次、挂在头一个视图上（2026-10-11 审查，阶段 4a）：主界面重建以前总要重建会话，建会话时再摆一次位，
     * 看不出来；§7.6 起新界面接回同一块画布、通道零变动，没有那一次了 —— 新视图上没有监听，
     * 主界面按画面比例改了视图尺寸也没人重算，座舱一格就按格子尺寸摆着（缩放、裁剪不对），直到下一次动这一路。</p>
     */
    private java.lang.ref.WeakReference<TextureView> laneWatchedView;
    /** 主界面点开放大时临时改成「填充」；null 表示按配置里那一格走。 */
    private volatile String fitOverride;

    private CameraManager cameraManager;
    /**
     * 开着的设备。改在 {@link #deviceLock} 里，读不拿锁（主线程上的调度、相机服务的监听都读）：volatile，
     * 读到「设备没了」的就一定也看得到改它之前登记的「在关」（{@link #loseDevice}，审查 2026-10-11）。
     */
    private volatile CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    /** 这一轮的相机线程。关相机时从这里摘下来，之后来自它的回调就是过时的（见 isStale）。 */
    private volatile HandlerThread backgroundThread;
    private volatile Handler backgroundHandler;

    private Size previewSize;
    /**
     * 这台相机专属的目标尺寸。设了就用它，不再读全局的「画质设置」。
     *
     * <p>多路配置里必须这样：合成流那一路要 1280x5140，另外两路座舱相机根本不支持
     * 这个尺寸。之前是把合成流尺寸写进全局配置，等于命令所有相机都去够这个尺寸。</p>
     */
    private Size preferredSize;

    /**
     * 拍照用的 JPEG 输出，<b>常驻在会话里</b>。
     *
     * <p>方案 A：建会话时就把它挂上去，按下快门直接发一次静态拍照请求，
     * 不用重建会话，预览和录制都不会顿。代价是每一路多一条输出流。</p>
     *
     * <p>2.10.12 起一直开着、只有开发者能关（{@code AppConfig.isPhotoViaJpegEnabled}）：关着时照片只能
     * 从主界面的预览上截，主界面在后台时没有预览，悬浮按钮怎么拍都拍不到画面。</p>
     */
    private ImageReader jpegReader;

    /** 这次开相机时，拍照通道是否因为会话配不上而被丢掉了。 */
    private boolean jpegDropped;

    /** JPEG 通道用的尺寸：这一路声明的最大那个。 */
    private Size jpegSize;

    /** 一次拍照的回调；拿到图或失败之后就清掉。 */
    private volatile JpegCallback pendingJpeg;

    /** 拍到一张 JPEG 之后怎么处理。 */
    interface JpegCallback {
        void onJpeg(byte[] data);

        void onFailed(String reason);
    }

    /** 这一路这一张拍完了：存下了（true）或没拍成（false）。每次拍照只回一次。 */
    public interface PhotoDone {
        void onDone(boolean saved);
    }

    /**
     * 不显示的出帧口（{@link ChannelRules.Out#SINK}）。
     *
     * <p>这一路此刻没有预览、后视镜、盲区画面、录像任何一个活着的输出（主界面在后台，它的预览画面已被系统收回），
     * 而它还该出帧时（拍照时相机没开、在「保持 30 秒」里，又没有留着的活输出），调度要的那一组里就是它，相机才有地方出帧；
     * 要的输出一样都不剩时建会话也补上它（{@link #requestedTargets}）。和预览同尺寸、同格式，帧到了就丢。</p>
     */
    private ImageReader frameSink;

    // 鱼眼矫正
    
    // 亮度/降噪调节相关
    private CaptureRequest.Builder currentRequestBuilder;  // 当前的请求构建器（用于实时更新参数）
    private CameraCharacteristics cameraCharacteristics;  // 摄像头特性（缓存）
    /** 系统有没有把这台相机给 SurfaceTexture 的画面左右翻过；null 表示还没查到。 */
    private Boolean sourceMirrored;
    private boolean imageAdjustEnabled = false;  // 是否启用亮度/降噪调节
    
    // 当前相机实际使用的参数（从 CaptureResult 读取）
    private int actualExposureCompensation = 0;
    private int actualAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO;
    private int actualEdgeMode = CameraMetadata.EDGE_MODE_OFF;
    private int actualNoiseReductionMode = CameraMetadata.NOISE_REDUCTION_MODE_OFF;
    private int actualEffectMode = CameraMetadata.CONTROL_EFFECT_MODE_OFF;
    private int actualTonemapMode = CameraMetadata.TONEMAP_MODE_FAST;
    private boolean hasReadActualParams = false;  // 是否已读取过实际参数

    // 调试：帧捕获监控
    private long frameCount = 0;  // 总帧数
    private long lastFrameLogTime = 0;  // 上次输出帧计数的时间
    private static final long FRAME_LOG_INTERVAL_MS = 60_000;  // 每分钟一行帧率；卡住另有卡顿监测

    // 实时 FPS（1秒滚动窗口，供调试信息展示）
    private float currentFps = 0f;
    private long fpsWindowFrameCount = 0;
    private long fpsWindowStartTime = 0;

    private long lastFrameTimestampMs = 0;
    /**
     * 最后一次「有动静」的单调时刻：出了一帧、相机开了、会话建好了，都算。
     *
     * <p>和上面那个的区别有两处，都要紧：一是用不含深度睡眠的时钟 ——
     * 车停一夜醒来不该算「卡了一整夜」；二是<b>开相机、建会话也刷新它</b>，
     * 于是「一帧都没出过」和「出过帧然后停了」可以用同一个年龄来判断。</p>
     */
    private volatile long lastProgressUptimeMs = 0;
    /** 最近一次真的收到画面（capture 完成）—— 开相机、建会话不算。 */
    private volatile long lastCaptureUptimeMs = 0;
    /**
     * 这一份相机对象建起来以来一共失败了几次请求、丢了几次缓冲（不分会话，只增不减；出帧回调里加）。关座舱时环视那一行
     * 「开始关以来每一路失败 / 丢缓冲」拿开始关那一刻和之后的差（2.11.5 第 D2 项，{@link #captureFailures}、{@link #lostBuffers}）。
     */
    private final java.util.concurrent.atomic.AtomicLong failuresTotal = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong lostTotal = new java.util.concurrent.atomic.AtomicLong();
    /**
     * 两帧之间隔了这么久以上才记成一次断帧（{@link #longestFrameGapSince}）：30 / 15 fps 正常是 33–66 ms 一帧，
     * 会话重建、相机服务卡一下都在几百毫秒以上。
     */
    static final long NOTABLE_GAP_MS = 200L;
    /** 最近的断帧留几次：一路离开、接回录像那几秒里的，够了。 */
    private static final int GAP_HISTORY = 16;
    /** 最近几次断帧：结束的时刻（uptime）和隔了多久，循环覆盖。出帧回调（相机线程）写，主线程读，拿 gapLock。 */
    private final long[] gapEndedAt = new long[GAP_HISTORY];
    private final long[] gapLength = new long[GAP_HISTORY];
    private int gapNext;
    private final Object gapLock = new Object();
    /** 最后一次相机报错的短名，给界面说明「为什么点了没反应」。 */
    private volatile String lastErrorName;

    /**
     * 设备报错或被断开之后、还没重开。这一路不再自己重连（2.10.10 之前有一套按错误码退避的重连阶梯，和看门狗互相顶，
     * 2026-10-08 自己顶自己 7 次）：报错就关掉自己、标成「没开」，要不要再开、什么节奏，只有通道调度
     * （{@link ChannelScheduler}）一个地方判 —— 它从 {@link CameraCallback#onLossStarted} / {@link CameraCallback#onLossClosed}
     * 知道这件事，等关完、再等 300 ms 判是不是被别的程序拿走的（2.11 阶段 3 起；以前是这里关完 post 到主线程的
     * CameraTaken.judgeLoss，判完再叫看门狗重开）。
     */
    private volatile boolean deviceLost;
    /** 被断开、报错 1 / 2 之后的那一次关（{@link #CLOSING} 里登记的那个）；null = 没有。见 {@link #holdsInService()}。 */
    private volatile java.util.concurrent.CountDownLatch lostClose;
    /** 一次强制重开还在路上（关旧的、等 300 ms、开新的、等回调）：这期间再来的重开请求合并掉，不双开。 */
    private volatile boolean reopenInFlight;
    /** 最近一个动作（开 / 关 / 重开 / 配会话）上路的时刻（uptime）；0 = 没有过。见 {@link #isBusy()}。 */
    private volatile long inFlightSinceMs;
    private volatile boolean isOpening = false;  // 是否正在打开中（防止并行触发时重复调用 openCamera）
    /**
     * 这一份有几条相机线程正在等这一台的关返回（{@link #waitForNoClose}：从开始等，到返回之后记下有动作上路、或者作废为止）。
     * 不封顶地算在途（{@link #isBusy}、{@link #openOrCloseInFlight}，审查 2026-10-28）：在等的开从上路到此刻早可能过了 60 秒，
     * 那一次关返回之后、它记下有动作上路之前的那一下里，在关的登记已经摘了、在途又过了封顶 —— 不算的话，调度的开、救把它当成
     * 开了 60 秒没回音，作废那一轮再开。{@link #busySince} 照旧是那个动作上路的时刻：别的路照旧最多等它 60 秒。
     * 是个数不是开关：被关掉的那一轮还在等的时候，新的一轮也可能在等，各算各的。
     */
    private final java.util.concurrent.atomic.AtomicInteger waitingForClose =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 设备、会话、相机线程这几个字段的锁；拿着它时不调相机服务，见 closeCamera。 */
    private final Object deviceLock = new Object();

    /**
     * 每台相机正在关、还没关完的那一次，按相机 id 记。
     *
     * <p>关相机交给这一路自己的相机线程去做（见 {@link #closeCamera(String)}），调用方不等。
     * 于是紧接着的「再打开」可能赶在上一次关完之前 —— 同一个 App 对同一台相机
     * 「旧的还没放、新的又来要」，相机服务会把旧的踢掉，两轮的回调搅在一起。所以打开之前，
     * 先在新一轮的相机线程上等上一次关返回（{@link #waitForNoClose}）—— 不设时限（项目所有者 2026-10-28：关没返回之前，
     * 我们的连接可能还挂在相机服务里，这一台谁都不开）。</p>
     *
     * <p>按相机 id 而不是按对象记：主界面重建时会换一批新的 SingleCamera，旧对象的关闭照样要等。
     * 同一台相机连着关几次时串成一条链：后一次登记时记下前一次，要等前一次关完才算完 —— 所以只看最新的那一个就够了；
     * 这一台从什么时候起一直有关没返回，记在链头那一次的时刻上（{@link CloseChain}，纯 Java，单元测试直接测）。
     * 跟着进程：管线重建、调度停了都还在，进程结束才没（相机服务随进程断开我们的连接）。</p>
     *
     * <p>每一种关都登记在这里（{@link #beginClosing} / {@link #finishClosing}，2.11.4 R1 / 1c）：通道调度从
     * {@link #closingSince} 认「这一台还在关一个设备」—— 那是在途，手上那一步到点也不放过，别的路最多等 60 秒（ChannelRules.inFlight）；
     * 这一路自己，返回之前不开、关着在开的不救（ChannelRules.closeOutstanding）。</p>
     */
    private static final CloseChain CLOSING = new CloseChain();

    /**
     * 一台相机最近一次关完：什么时候关完的（uptime，和调度同一个钟）、关了多久、为什么关（英文短语）。不可变。
     * 记在每一台相机的来历里（{@link CameraHistory}，跨进程留着，{@link #lastClose}）。
     */
    public static final class LastClose {
        public final long endedAt;
        public final long ms;
        public final String why;

        LastClose(long endedAt, long ms, String why) {
            this.endedAt = endedAt;
            this.ms = ms;
            this.why = why;
        }
    }
    /**
     * 一路相机的开、重开、配会话，一次最多在途多久。在途期间同一路不叠第二个动作：
     * 调度不插队，强制重开合并，打开等上一次关返回。相机服务一次关实测要 2–20 秒
     * （2026-10-08），所以留足；超过就当回调不会来了，强制重开接手。每一种开、重开都封顶，重开那一次也一样
     * （审查 2026-10-11：以前重开在途单独挡、不封顶，那一次 openCamera 没回音，这一路就再也救不回来）：
     * 超过封顶的那一轮开、重开不拿来接着用，像关相机一样作废，换一条相机线程重开（{@link #retireStaleOpen}）。
     *
     * <p>关不封顶（项目所有者 2026-10-28）：一次关没返回，这一台的开一直等它（{@link #waitForNoClose}）、再来的开和重开一直合并掉
     * （{@link #openOrCloseInFlight}）。这个数对关只剩一个意思：调度让别的路最多等它多久（{@link #isBusy}、ChannelRules.inFlight）。</p>
     */
    static final long IN_FLIGHT_MAX_MS = 60_000L;
    /** 关的时候还有一次打开在途：它的回调可能晚到，这一轮的相机线程多留这么久，好把晚到的设备关掉。 */
    private static final long LATE_OPEN_GRACE_MS = 3_000L;
    /** 被相机服务断开时报给 MultiCameraManager 的错误码（自定义，onError 的码都是正的）。 */
    private static final int LOST_DISCONNECTED = -4;
    private boolean isConfiguring = false;    // 一次 createCaptureSession 发出去了、回调还没来
    private boolean isSessionClosing = false; // 旧会话在关，等 onClosed
    /**
     * 相机层自己排了一次会话重建、还没开始建（{@link #requestSessionRebuild}：关完旧会话后 50 ms、配置失败后 200 / 500 ms、
     * 关的兜底）。算在途（{@link #isBusy()}）：这几百毫秒里会话快照是空的、旧的标志都清了，不算的话调度会当这一路闲着、
     * 会话里没有那一样 —— 把交给它的画布放掉，而排着的那一次重建正要带上它（审查 2026-10-11）。{@link #sessionLock}。
     */
    private boolean rebuildQueued = false;
    /**
     * 会话重建的代数：每次真正去建就 +1；建到一半又有人要重建，就再 +1 把在途的那一次作废。
     * 回调带着自己那一代回来，不是最新一代的一律作废（关掉、然后按最新的输出再建一次）。
     * 以前靠「正在配置就记个 pending、配好了再来一次」加五处各自的延时来避免撞车（1.66.0 起只有这一套）。
     */
    private int sessionGeneration;
    /** 重建请求的去抖；关掉旧会话之后再建要隔一下（0 ms 会碰上 "Surface already has a stream"）；onClosed 不来时的兜底。 */
    private static final long REBUILD_DEBOUNCE_MS = 100L;
    private static final long REBUILD_AFTER_CLOSE_MS = 50L;
    private static final long CLOSE_FALLBACK_MS = 300L;
    private int configFailRetryCount = 0; // session 配置失败重试计数
    private static final int MAX_CONFIG_FAIL_RETRIES = 3; // 最大重试次数
    private final Object sessionLock = new Object(); // 新增：用于同步 Session 操作

    // ===================================================================== 2.11 通道调度的接口

    /**
     * 调度要这一份会话带的那一组输出（不可变）；关着时是空的。
     *
     * <p>调度的动作在主线程上换它；相机线程建会话时读一次。会话配不上、消费者死了，相机线程从里面摘掉那一样 ——
     * 摘用 {@code updateAndGet}，主线程同时换了一组新的也不会被盖回旧的。</p>
     */
    private final AtomicReference<Set<ChannelRules.Out>> requested =
            new AtomicReference<>(Collections.emptySet());

    /** 声明、我们建的 Surface、上一份会话带着的输出，这几样的锁。拿着它时不进相机服务、不回调。 */
    private final Object outputLock = new Object();
    /** 声明着的预览、后视镜、盲区画面（中控屏、HUD）、录像，每样最多一个（{@link #outputLock}）。 */
    private Declared declaredPreview;
    private Declared declaredMirror;
    private Declared declaredSide;
    private Declared declaredHud;
    private Declared declaredRecord;
    /**
     * 我们自己建的 Surface（预览、后视镜、盲区画面的 {@code new Surface(SurfaceTexture)}，或者鱼眼校正管线的入口），按输出记。
     * 一个 SurfaceTexture 只建一个：贴边、放回、再声明一次都不算变化。没声明着、会话里也没带着了才放（{@link #tidySurfaces}）。
     */
    private final Map<ChannelRules.Out, Surface> owned = new LinkedHashMap<>();
    /**
     * 上一份配好的会话带着哪几样输出、各是哪个 Surface（不含 JPEG）。重建会话的那几百毫秒里也还留着：
     * 撤回了声明、但消费者还活着的「留着的输出」，下一份会话照留时要从这里找到它的 Surface。设备没了才清。
     */
    private final Map<ChannelRules.Out, Surface> carried = new LinkedHashMap<>();
    /** 眼下这份会话的快照；没有会话是 {@link SessionOutputs#NONE}。相机线程写，主线程读。 */
    private volatile SessionOutputs sessionSnapshot = SessionOutputs.NONE;
    /**
     * 调度给的：会话里每一样输出算哪一类（要的、留着的、死的）、从什么时候起（{@link #noteOutputStates}）。
     * 只给卡顿现场看（{@link #describeForStall}），这一类自己不据此做任何事。
     */
    private volatile Map<ChannelRules.Out, ChannelScheduler.OutputState> outputStates = Collections.emptyMap();
    /** 本会话第一帧的时刻（uptime）；0 = 还没有。每建一份会话归零。 */
    private volatile long firstFrameAt;
    /**
     * 这一台设备眼下这份会话配好以来相机回了什么（{@link SessionCounts}，2.11.4 第 1a 项）：开好时清、配好时重新数；
     * 出帧回调只数眼下这份会话的。15 秒判定那一行（调度经 {@link #sessionCounts} 读）、关一份没出过画面的会话那一行照它写。
     */
    private final SessionCounts counts = new SessionCounts();
    /** 不开设备先算出来的画面尺寸（{@link #resolveSize}），开相机时也跟着更新；null = 还没算出来。 */
    private volatile Size resolved;
    /**
     * 最近一次画面调节给的参数（曝光、白平衡、色调、锐化、降噪、特效）；null = 没调过，只用配置里存的。
     * 调节赶上这一路有一步在途时先不发，下一次建会话时从这里带上。
     */
    private volatile int[] latestAdjust;
    /** 强制重开时我们自己摘下、正在关的旧设备：它晚到的报错不是「开不起来」。 */
    private volatile CameraDevice retiringDevice;

    /** 会话里几样输出的次序：预览、后视镜、盲区画面（中控屏、HUD）共用一条流（预览打头），出帧口、录像各一条，JPEG 最后。 */
    private static final ChannelRules.Kind[] SESSION_ORDER = {
            ChannelRules.Kind.PREVIEW, ChannelRules.Kind.MIRROR, ChannelRules.Kind.SIDE, ChannelRules.Kind.HUD,
            ChannelRules.Kind.SINK, ChannelRules.Kind.RECORD};
    /** 共享预览流最多能挂几块画布（框架给的上限，AOSP 是 4；车机上记一行看实际是几），变了才记；0 = 还没记过。 */
    private static volatile int sharedSurfaceLimitNoted;

    /**
     * 会话的快照：配好时记下的，之后不变。调度拿它填 {@code ChannelRules.Lane} 的 session / sessionSince。
     */
    public static final class SessionOutputs {
        /** 没有会话。 */
        public static final SessionOutputs NONE = new SessionOutputs(0, Collections.emptySet(), 0L);

        /** 会话的代号（每真去建一次 +1）；0 = 没有会话。 */
        public final int generation;
        /** 会话里的输出，按消费者认；出帧口是 {@link ChannelRules.Out#SINK}。不含常驻的 JPEG。 */
        public final Set<ChannelRules.Out> outputs;
        /** 配好的时刻（{@code SystemClock.uptimeMillis()}，和调度、{@link CameraLiveness} 同一个钟）；0 = 没有会话。 */
        public final long since;

        SessionOutputs(int generation, Set<ChannelRules.Out> outputs, long since) {
            this.generation = generation;
            this.outputs = Collections.unmodifiableSet(new LinkedHashSet<>(outputs));
            this.since = since;
        }

        public boolean exists() {
            return since != 0;
        }

        public boolean carries(ChannelRules.Out out) {
            return outputs.contains(out);
        }

        @Override
        public String toString() {
            return since == 0 ? "none" : "gen=" + generation + " " + outputs;
        }
    }

    /** 一样输出为什么离开了会话。前两种是相机层自己丢的（{@link CameraCallback#onOutputDropped}），后两种由调度派发。 */
    public enum Drop {
        /** 消费者已被系统收走（SurfaceTexture 放掉了、Surface 失效了）。 */
        RELEASED,
        /** 会话配不上（或者共享预览流挂不下），按 JPEG → 盲区画面 → 后视镜 → 录像的次序丢到了它。 */
        CONFIG_FAILED,
        /** 调度改输出时不要它了，摘掉了。 */
        REMOVED,
        /** 随相机关了。 */
        CLOSED
    }

    /** 录像、盲区画面、超级镜子的输出离开了会话：告诉声明它的那一侧（调度在主线程上派发）。 */
    public interface RecordDropped {
        void onDropped(Drop why);
    }

    /** 一样声明：消费者（{@link ChannelRules.Out#consumer}），加上各自要的那一点东西。 */
    private static final class Declared {
        final ChannelRules.Out out;
        /** 预览：主界面那一格的视图（鱼眼校正管线要画回它）。 */
        final TextureView view;
        /** 录像：录制器给的 Surface，归录制器管，这里不放。 */
        final Surface surface;
        /** 录像：离开会话时告诉谁。 */
        final RecordDropped onDropped;

        Declared(ChannelRules.Out out, TextureView view, Surface surface, RecordDropped onDropped) {
            this.out = out;
            this.view = view;
            this.surface = surface;
            this.onDropped = onDropped;
        }
    }

    /** 建一份会话时的一样输出：哪一样、相机写进哪个 Surface。 */
    private static final class Target {
        final ChannelRules.Out out;
        final Surface surface;

        Target(ChannelRules.Out out, Surface surface) {
            this.out = out;
            this.surface = surface;
        }
    }


    public SingleCamera(Context context, String cameraId, TextureView textureView) {
        this.context = context;
        this.cameraId = cameraId;
        this.captureBeat = StallWatch.capture(cameraId);
        StallWatch.registerCamera(this);
        this.textureView = textureView;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    public void setCallback(CameraCallback callback) {
        this.callback = callback;
    }

    public void setCameraPosition(String position) {
        this.cameraPosition = position;

        // 如果是后摄像头，应用左右镜像变换
        if (autoMirrorBack() && textureView != null) {
            applyMirrorTransform();
        }
        if (textureView != null) {
            applyLaneTransform();
        }
    }

    /**
     * 「back 一律加左右镜像」这条祖传规则还该不该生效。
     *
     * <p>它是给自定义 / E5 车型写的 —— 那里的 back 是倒车影像，本来就该反着看。
     * 但在「极氪7X（环视 + 前后座舱）」里，back 这个槽位装的是<b>座舱第一路</b>：它被无条件
     * 镜像，而且是直接 {@code setTransform}，把配置算出来的矩阵整个盖掉。</p>
     *
     * <p>判断只看<b>配置里有没有这一路的那一格</b>。原来看的是车型，而车型这个键
     * 默认是 {@code zeekr_7x}，没人手动改过就一直是它 —— 座舱旋转前三次没修好，
     * 每次都绕回这个坑。</p>
     */
    private boolean autoMirrorBack() {
        return "back".equals(cameraPosition) && laneForThisCamera() == null;
    }

    /**
     * 这一路的摆法：旋转、镜像、裁剪、缩放平移，全部来自配置里那一格。
     *
     * <h3>为什么写在这里，而不是主界面</h3>
     *
     * <p>试过两次写在 {@code MainActivity.applyPreviewSizeTransform} 里，两次都无效。
     * 第一次是被本类那条「back 一律加镜像」盖掉了；第二次盖不掉了，还是没生效 ——
     * 说明主界面那条路径要么没跑到，要么跑在 TextureView 有尺寸之前。</p>
     *
     * <p>而本类这几个调用点是<b>确定会执行</b>的：正是它们盖掉了第一次的修复。
     * 所以摆法搬到这里来 —— 谁盖谁，就由谁来负责。</p>
     *
     * <p>开发者选项里的「预览矫正」仍然叠在这之上：配置是存下来的摆法，
     * 那个悬浮窗是在它上面临时推一把。</p>
     */
    private void applyLaneTransform() {
        final TextureView view = textureView;
        if (view == null) {
            laneTransformNote = "没有 TextureView";
            return;
        }
        final com.kooo.evcam.profile.LaneLayout lane = laneForThisCamera();
        if (lane == null) {
            laneTransformNote = "配置里没有这一路";
            return;
        }
        view.post(() -> {
            // 先挂监听再量尺寸（主线程）：之后尺寸再变，监听会再算一次；之前变的，这里量到的就是变过的
            if (view == textureView) {
                watchLaneLayout(view);
            }
            int width = view.getWidth();
            int height = view.getHeight();
            if (width <= 0 || height <= 0) {
                // 还没量出来，等下一帧
                laneTransformNote = "视图还没有尺寸，等下一帧";
                view.postDelayed(this::applyLaneTransform, 100);
                return;
            }
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            android.util.Size buffer = previewSize;
            boolean shaped = LaneSurfaceMatrix.build(matrix, width, height,
                    buffer == null ? 0 : buffer.getWidth(),
                    buffer == null ? 0 : buffer.getHeight(),
                    lane.rotation, lane.mirrored,
                    lane.cropTop, lane.cropBottom, lane.cropLeft, lane.cropRight,
                    lane.scaleX, lane.scaleY, lane.translateX, lane.translateY,
                    fitOverride != null ? fitOverride : lane.fit);
            if (sourceMirrored()) {
                // 系统翻过的先翻回正常视角：裁剪、旋转、配置里的「镜像」都作用在正常视角上，
                // 配置里开着镜像就是镜像，关着就是正的 —— 每一路都一样
                matrix.preScale(-1f, 1f, width / 2f, height / 2f);
                shaped = true;
            }
            view.setTransform(matrix);
            laneTransformNote = (shaped ? "已应用 " + lane : "这一格没有任何变换")
                    + "，视图 " + width + "x" + height;
            AppLog.i(TAG, "Camera " + cameraId + " (" + cameraPosition + ") 按配置摆位: "
                    + laneTransformNote);
        });
    }

    /**
     * 把重算监听挂到这个视图上（主线程）：视图尺寸一变就重新摆位 —— 设宽高比会触发重新布局，而它和摆位谁先谁后
     * 没有保证，只算一次的话，算的可能是上一次的尺寸。
     *
     * <p>跟着视图走：换了视图（主界面重建，新界面接回同一块画布，大纲 §7.6）就从旧视图上摘下、挂到新视图上
     * （2026-10-11 审查，阶段 4a；以前只挂在头一个视图上，见 {@link #laneWatchedView}）。
     * 已经挂在它上面的什么都不做。</p>
     */
    private void watchLaneLayout(TextureView view) {
        TextureView watched = laneWatchedView == null ? null : laneWatchedView.get();
        if (watched == view) {
            return;
        }
        if (laneLayoutWatch == null) {
            laneLayoutWatch = (v, l, t, r, b, ol, ot, or2, ob) -> {
                // 只认此刻的那个视图：撤回了的那一格（textureView 已清）尺寸变了不关这一路的事
                if (v == textureView && (r - l != or2 - ol || b - t != ob - ot)) {
                    applyLaneTransform();
                }
            };
        }
        if (watched != null) {
            watched.removeOnLayoutChangeListener(laneLayoutWatch);
        }
        view.addOnLayoutChangeListener(laneLayoutWatch);
        laneWatchedView = new java.lang.ref.WeakReference<>(view);
    }

    /**
     * 临时换一种铺法：主界面点开放大时用「填充」，传 null 回到配置里那一格的铺法。
     *
     * <p>不写配置 —— 放大是看一眼的事，不该改掉存下来的摆法。</p>
     */
    public void setFitOverride(String fit) {
        fitOverride = fit;
        applyLaneTransform();
    }

    /** 最近一次按配置摆位的结果，给诊断报告用。 */
    public String getLaneTransformNote() {
        return laneTransformNote;
    }

    /** 配置里这一路的那一格；不拆分的那几路只有一格。取不到返回 null。 */
    private com.kooo.evcam.profile.LaneLayout laneForThisCamera() {
        try {
            String role = com.kooo.evcam.profile.ProfileSizes.roleForCameraKey(cameraPosition);
            if (role == null) {
                return null;
            }
            com.kooo.evcam.profile.CameraProfile camera =
                    new com.kooo.evcam.profile.ProfileStore(context).current().camera(role);
            if (camera == null || camera.lanes.isEmpty()) {
                return null;
            }
            return camera.lanes.get(0);
        } catch (Exception e) {
            AppLog.w(TAG, "Camera " + cameraId + " 取配置那一格失败: " + e);
            return null;
        }
    }

    /** 配置里这一路那一格的旋转（顺时针度数），取不到是 0：超级镜子摆后座舱画面时照它摆正，和主界面同一格。 */
    public int laneRotation() {
        com.kooo.evcam.profile.LaneLayout lane = laneForThisCamera();
        return lane == null ? 0 : lane.rotation;
    }

    /**
     * 应用左右镜像变换到TextureView
     */
    private void applyMirrorTransform() {
        // 取一次：post 出去之后字段可能已被清掉（撤回预览、换视图），和 applyLaneTransform 一样认这一刻的那个视图
        final TextureView view = textureView;
        if (view == null) {
            return;
        }

        // 在主线程中执行UI操作
        view.post(() -> {
            android.graphics.Matrix matrix = new android.graphics.Matrix();

            // 获取TextureView的中心点
            float centerX = view.getWidth() / 2f;
            float centerY = view.getHeight() / 2f;

            // 应用水平镜像：scaleX = -1
            matrix.setScale(-1f, 1f, centerX, centerY);

            view.setTransform(matrix);
            AppLog.d(TAG, "Camera " + cameraId + " (back) applied mirror transform");
        });
    }


    public String getCameraId() {
        return cameraId;
    }

    /**
     * 摄像头硬件是否已打开
     */
    /**
     * 开着、正在开、或者正在关 —— 相机服务报「被占用」时，用来判断是不是我们自己。
     *
     * <p>「正在关」也算：关是相机线程去做的（见 {@link #closeCamera(String)}），从字段上摘下来
     * 到相机服务真的放开之间，占着它的仍然是我们。</p>
     */
    public boolean holdsOrIsOpening() {
        return cameraDevice != null || isOpening || CLOSING.containsKey(cameraId);
    }

    /**
     * 相机服务那边此刻算不算我们占着这一路：开着、正在开、正在照常关。
     *
     * <p>被断开、报错 1 / 2 之后的那一次关不算（{@link #loseDevice}，2026-10-10）：那一刻相机服务已经把我们踢了
     * （或者压根没让我们连上），那几秒里它报的「被占用」是新的主人。算成我们的话，别的程序拿走相机被记成「我们开着」，
     * 它在那几秒里放开又被当成乱报。关相机的次序照样等这一次关完（那边问的是 {@link #holdsOrIsOpening()}）。</p>
     */
    public boolean holdsInService() {
        if (cameraDevice != null || isOpening) {
            return true;
        }
        java.util.concurrent.CountDownLatch closing = CLOSING.get(cameraId);
        return closing != null && closing != lostClose;
    }

    /**
     * 这一路此刻有没有动作在途：在开、在关（含丢了设备之后的那一次关）、在重开、在配会话、排着一次会话重建。
     * 丢了之后的判定不在这里：它在通道调度的账上（等关完、再等 300 ms，那段时间调度自己不动别的路，2.11 阶段 3 起）。
     *
     * <p>在途的时候不叠第二个动作 —— 调度不重开，强制重开合并。2026-10-08 的七次
     * 「被相机服务断开」全是自己顶自己：重连那一次打开还没回来，看门狗又开了一次，
     * 相机服务把先开的那份踢掉。在途超过 {@link #IN_FLIGHT_MAX_MS} 才当回调不会来了，不再算在途 —— 这只管调度让别的路等多久；
     * 这一台自己有关没返回，照样不开（{@link #openOrCloseInFlight}、{@link #waitForNoClose}，项目所有者 2026-10-28）。
     * 在等关返回的开、重开（{@link #waitingForClose}）一直算，不封顶：调度不救它；{@link #busySince} 照旧是它上路的时刻，
     * 别的路照旧最多等 60 秒。</p>
     */
    public boolean isBusy() {
        if (waitingForClose.get() > 0) {
            return true;
        }
        boolean inFlight = isOpening || reopenInFlight || CLOSING.containsKey(cameraId);
        if (!inFlight) {
            synchronized (sessionLock) {
                inFlight = isConfiguring || isSessionClosing || rebuildQueued;
            }
        }
        return inFlight && inFlightFresh();
    }

    /** 上一个动作上路还不到 {@link #IN_FLIGHT_MAX_MS}。 */
    private boolean inFlightFresh() {
        long since = inFlightSinceMs;
        return since != 0 && SystemClock.uptimeMillis() - since < IN_FLIGHT_MAX_MS;
    }

    /**
     * 开、重开还在途（封顶同 {@link #isBusy()}），或者这一台相机有一次关没返回（{@link #CLOSING}，任何一份实例、任何一种关，不封顶）：
     * 这时再开就是同一台相机两个句柄，相机服务会踢掉先开的那份（2026-10-08，自己顶自己 7 次）。
     * 配会话、关旧会话不在此列 —— 设备在我们手上，重开时连它们一起作废、关掉。
     *
     * <p>关不封顶（项目所有者 2026-10-28）：以前关也是 60 秒以后就不算了（新建的一份实例从没动过手，一开始就不算），
     * 再来的开、重开照样上路，打开那一下又只等 60 秒。封顶只留给开、重开：超过的那一轮作废、换一轮（{@link #retireStaleOpen}）。
     * 在等关返回的开、重开（{@link #waitingForClose}）也不封顶：那一次关返回之后、它记下有动作上路之前，不作废它。</p>
     */
    private boolean openOrCloseInFlight() {
        return (isOpening || reopenInFlight) && inFlightFresh() || CLOSING.containsKey(cameraId)
                || waitingForClose.get() > 0;
    }

    /** 在途的是什么、多久了，给日志看。 */
    public String describeBusy() {
        long since = inFlightSinceMs;
        return busyKinds() + (since == 0 ? "" : " " + (SystemClock.uptimeMillis() - since) + "ms");
    }

    /**
     * 在途的是什么，只有记号（{@code closing-lost}、{@code opening configuring} …），不带时长：
     * 通道调度写「等 X 在途（closing-lost 2.1 s）」那一行用，时长它按自己的账算。
     */
    public String busyKinds() {
        StringBuilder sb = new StringBuilder();
        if (isOpening) sb.append("opening ");
        if (reopenInFlight) sb.append("reopen ");
        java.util.concurrent.CountDownLatch closing = CLOSING.get(cameraId);
        if (closing != null) sb.append(closing == lostClose ? "closing-lost " : "closing ");
        synchronized (sessionLock) {
            if (isConfiguring) sb.append("configuring ");
            if (isSessionClosing) sb.append("session-closing ");
            if (rebuildQueued) sb.append("rebuild-queued ");
        }
        return sb.toString().trim();
    }

    /** 有动作上路了：记下时刻。调度的救援据此把它算作进度，而不是卡死（{@link #progressAgeMs()}）。 */
    private void markInFlight() {
        long now = SystemClock.uptimeMillis();
        inFlightSinceMs = now;
        lastProgressUptimeMs = now;
    }

    /**
     * 关相机，并量一下它在相机服务里卡了多久。
     *
     * <p>{@code CameraDevice.close()} 是一次进相机服务的 binder 调用。醒来之后它卡过一秒多
     * （2026-09-23，Camera-2 线程停在 {@code ICameraDeviceUser.disconnect()}）；
     * 而这几处调用都拿着 {@link #deviceLock}，主线程上的 {@link #closeCamera(String)} 也要这把锁 ——
     * 一边卡在 binder 里，另一边就在锁上等。超过半秒的都记进黑匣子，带上线程名：
     * 是不是卡在主线程上，看这一行就知道。</p>
     */
    private void closeDeviceTimed(CameraDevice device, String why) {
        if (device == null) {
            return;
        }
        long start = SystemClock.elapsedRealtime();
        try {
            device.close();
        } catch (Exception e) {
            AppLog.d(TAG, "Camera " + cameraId + " ignored exception while closing (" + why + "): "
                    + e.getMessage());
        }
        long ms = SystemClock.elapsedRealtime() - start;
        if (ms >= 500) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId + " 关闭卡了 " + ms + "ms（"
                    + why + "，线程 " + Thread.currentThread().getName() + "）");
        }
    }

    /**
     * 登记一次关（{@link #CLOSING}）：从这一刻起这一台算在关 —— 后面的开先等它关完，调度认它在途（{@link #closingSince}）。
     * 返回上一次还没关完的那一个（可能是别的实例的，链上的前一次）：这一次关完之前要等它，「这一次关完」蕴含「之前的都关完」。
     * 和 {@link #finishClosing} 成对，每一种关都走这一对（2.11.4 R1 / 1c）。
     *
     * <p>链上还没返回的哪一次（从最新登记的那一次一直往前看到返回了的那一次，{@link Closing#queuedOn}）排在当前这条线程上、还没轮到：
     * 这一次不另登记、不等（返回 null）—— 等它就是等自己，相机线程永远堵住，这一台从此不开。不另登记照样对：那一次在当前这一下之后
     * 才开始，比它新的每一次都要等它返回才返回，所以最新登记着的那一次一直在、盖住这一次，后面的开、调度照它等。
     * 两种来路：关相机那一下排在上一轮线程上，那一轮晚到的回调（过时的断开、报错、开好）排在它前面先跑（{@link #closeStale}）；
     * 救援重开关旧设备那一下排着，旧设备的断开、报错排在它前面先跑（{@link #loseDevice}）。</p>
     *
     * <p>以前只看最新登记的那一次（审查 2026-10-28）：这两种同步的关是回调跑起来才登记的，排在这条线程上的那一次上面可能已经压了一次
     * 别的线程的关（另一份实例在关、那边丢了之后的关）。这条线程等最新的那一次，最新的那一次等它前面那一次，前面那一次排在这条线程上、
     * 等这一下做完 —— 两条相机线程永远堵住。查和登记在同一下里做（{@link CloseChain#begin}）。</p>
     */
    private java.util.concurrent.CountDownLatch beginClosing(Closing closing) {
        return CLOSING.begin(cameraId, closing, android.os.Looper.myLooper());
    }

    /**
     * 一次关做完了（关它的那条线程上）：等之前那一次关返回，记下这一台最近一次关完（{@link #lastClose}），摘掉在关的登记、放行等它的开。
     *
     * <p>等之前那一次不设时限（{@link #awaitClosed}，项目所有者 2026-10-28）：以前等满 60 秒就摘掉自己的登记、放行，之前那一次还挂在
     * 相机服务里，这一台却谁都看不出还在关 —— 调度当它关好了，打开也不再等。之前那一次和它前面还没返回的那几次都不会排在这条线程上
     * （{@link #beginClosing} 遇到那种不另登记）。</p>
     */
    private void finishClosing(Closing closing, java.util.concurrent.CountDownLatch previous) {
        awaitClosed(previous);
        long endedAt = SystemClock.uptimeMillis();
        // 最近一次关完记进这一台的来历（跨进程留着：「离上次关」装新版、退出之后也算得出，2.11.5 第 D3 项）
        StepLog.deviceClosed(cameraId, endedAt - closing.since, closing.why);
        CLOSING.finish(cameraId, closing);
    }

    /**
     * 这一台相机此刻有关没返回的话（任何一份实例、任何一种关），从什么时候起一直有（uptime，和调度同一个钟；链头那一次的时刻，
     * {@link Closing#outstandingSince}）；没在关是 0。一次关卡住了、上面又登记一次（晚到的设备就地关、另一份实例在关），这个时刻不变
     * （审查 2026-10-28：以前是最新那一次的时刻，上面每压一次关，别的路就再等 60 秒，「满 60 秒还没返回」也跟着往后推）。
     * 通道调度据此认「还在关」（ChannelRules 1c：在途，手上那一步到点也不放过，别的路最多等 60 秒）；不是 0 的时候不开这一路，
     * 关着、在开的也不救（ChannelRules.closeOutstanding，项目所有者 2026-10-28）。
     */
    public long closingSince() {
        return CLOSING.outstandingSince(cameraId);
    }

    /**
     * 这一台相机最近一次关完的那一次（任何一份实例的，2.11.5 起也算之前的进程：记在 {@link CameraHistory} 里，同一次开机里跨进程对得上）；
     * 没有记录是 null。开座舱那一行、相机步骤统计要「我们上一次关它」。关完的时刻按含深睡的钟记，这里换回调度的钟：此刻 −（多久以前）。
     */
    public LastClose lastClose() {
        CameraHistory.Camera history = StepLog.history().camera(cameraId);
        if (history == null || history.closedElapsed < 0) {
            return null;
        }
        long ago = Math.max(0, SystemClock.elapsedRealtime() - history.closedElapsed);
        return new LastClose(SystemClock.uptimeMillis() - ago, history.closeMs, history.closeWhy);
    }

    /** 最后一次收到画面（{@code onCaptureCompleted}）的时刻（uptime，和调度同一个钟）；没有过是 0。 */
    public long lastResultAt() {
        return lastCaptureUptimeMs;
    }

    /** 这一份相机对象建起来以来一共失败了几次请求（不分会话，只增不减）。 */
    public long captureFailures() {
        return failuresTotal.get();
    }

    /** 这一份相机对象建起来以来一共丢了几次缓冲（各样输出加起来，不分会话，只增不减）。 */
    public long lostBuffers() {
        return lostTotal.get();
    }

    /** 眼下这份会话配好以来相机回了什么（{@link SessionCounts}）；设备开好、会话还没配好时 {@code configuredAt} 是 0。 */
    public SessionCounts.Snapshot sessionCounts() {
        return counts.snapshot();
    }

    /**
     * 这一路此刻有一步在途：在开、在重开、在关、在配会话、在关旧会话、排着一次会话重建。请求级的调用（画面调节、拍照）这时都不发 ——
     * 调度的那一步优先，请求级的从不和它叠在一起（项目所有者 2026-10-14 R1：动相机的都归通道调度管）。在相机线程上问。
     */
    private boolean stepInFlight() {
        synchronized (sessionLock) {
            if (isConfiguring || isSessionClosing || rebuildQueued) {
                return true;
            }
        }
        return isOpening || reopenInFlight || CLOSING.containsKey(cameraId);
    }

    /**
     * 一台设备关完了，它最后那一份会话一帧都没出过的话（含没配好），黑匣子记一行配好以来相机回了什么（2.11.4 第 1a 项）：
     * 和 15 秒判定那一行对着看，这一台从配好到关掉的整段都在。出过画面的不写。
     *
     * @param c   开始关那一刻的那一份（{@link #sessionCounts}）
     * @param why 关的原因（英文短语）
     * @param ms  关了多久
     */
    private void noteClosedWithoutFrames(SessionCounts.Snapshot c, String why, long ms) {
        if (c == null || c.results > 0) {
            return;
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + who() + " 关了一份没出过画面的会话（" + why + "，关了 "
                + ms + "ms）：" + countsText(c));
    }

    /**
     * 配好以来相机回了什么，黑匣子里那一段（15 秒判定那一行、关一份没出过画面的会话那一行共用，句子只有这一处）：
     * 「配好于 20:10:15.912，配好后 0.0 秒发重复请求；开始 0、结果 0、失败 1（第一次原因 0，配好后 8.0 秒，第 3 帧）、丢缓冲 0
     * → 一个都没回（相机 HAL 收了请求不回）」。
     */
    static String countsText(SessionCounts.Snapshot c) {
        if (c.configuredAt == 0) {
            return "会话没配好 → " + kindText(c.kind().name(), c.failures, c.lostTotal());
        }
        long wallOffset = System.currentTimeMillis() - SystemClock.uptimeMillis();
        StringBuilder sb = new StringBuilder("配好于 ")
                .append(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(c.configuredAt + wallOffset)))
                .append(c.repeatingAt == 0 ? "，没发出重复请求"
                        : "，配好后 " + secondsText(c.repeatingAt - c.configuredAt) + "发重复请求")
                .append("；开始 ").append(c.starts).append("、结果 ").append(c.results).append("、失败 ").append(c.failures);
        if (c.failures > 0) {
            sb.append("（第一次原因 ").append(c.firstFailReason).append("，配好后 ")
                    .append(secondsText(c.firstFailAt - c.configuredAt)).append("，第 ").append(c.firstFailFrame).append(" 帧）");
        }
        sb.append("、丢缓冲 ");
        if (c.lost.isEmpty()) {
            sb.append('0');
        } else {
            boolean first = true;
            for (Map.Entry<String, Long> entry : c.lost.entrySet()) {
                sb.append(first ? "" : "、").append(entry.getKey()).append(' ').append(entry.getValue());
                first = false;
            }
        }
        return sb.append(" → ").append(kindText(c.kind().name(), c.failures, c.lostTotal())).toString();
    }

    /**
     * 同 {@link #kindText(String)}，HAL 只回了错误的那一种带上数：「相机 HAL 没出过一帧，只回了错误（失败 2、丢缓冲 1）」
     * （2.11.5 第 D0 项）。黑匣子那两行、诊断报告「2.3.3」有数的地方都用这一个。
     */
    public static String kindText(String kind, long failures, long lost) {
        String text = kindText(kind);
        if ("HAL_FAILING".equals(kind) || "FAILING".equals(kind)) {
            return text + "（失败 " + Math.max(0, failures) + "、丢缓冲 " + Math.max(0, lost) + "）";
        }
        return text;
    }

    /**
     * 配好以来相机回了什么算哪一种（{@link SessionCounts.Kind} 的名字），中文：黑匣子那两行、诊断报告「2.3.3 相机步骤统计」共用。
     * 认不出的名字原样。HAL 开始出帧以后才说「我们的输出接不住」（2.11.5 第 D0 项，{@link SessionCounts#classify}）。
     */
    public static String kindText(String kind) {
        if (kind == null) {
            return "?";
        }
        switch (kind) {
            case "NOT_CONFIGURED":
                return "没配好";
            case "FRAMED":
                return "出过画面";
            case "HAL_HOLDING":
                return "一个都没回（相机 HAL 收了请求不回）";
            case "STARVING":
                return "我们的输出接不住（丢缓冲）";
            case "LATE_START":
                return "开始了、没有结果";
            case "HAL_FAILING":
            case "FAILING":   // 2.11.4 存下的旧名字：没开始、失败不止一次 —— 照新的规矩就是这一种
                return "相机 HAL 没出过一帧，只回了错误";
            default:
                return kind;
        }
    }

    /** 「0.3 秒」。 */
    private static String secondsText(long ms) {
        return String.format(Locale.US, "%.1f 秒", Math.max(0, ms) / 1000f);
    }

    public boolean isCameraOpened() {
        return cameraDevice != null;
    }

    /**
     * 向相机请求一个帧率区间。
     *
     * <p><b>这一项此前全项目一次都没设过。</b>不设的话，自动曝光会在 HAL 的默认区间里
     * 自己决定跑多快；而这台车机声明的是 15–30，于是它完全有权在负载一上来时
     * 滑到 15 —— 「设置里选 30、录出来 15」就是这么来的。相机不是给不到 30，
     * 是没人要求过它保持 30。</p>
     *
     * <p>挑哪个区间见 {@link FpsRangePicker}：优先固定区间，其次下限最高的那个。
     * 相机没声明区间时什么都不设 —— 硬塞一个没声明的值会让会话配置失败，
     * 那是把「帧率不对」变成「根本没有画面」。</p>
     */
    private void applyTargetFpsRange(CaptureRequest.Builder builder) {
        // 相机要的是一个具体目标：给 0 它会去挑最低的那个区间，恰好和「不限制」相反。
        // 帧率写在这一路自己的配置里，所以按这一路取。
        int target = com.kooo.evcam.profile.RecordSpecs.nominal(
                com.kooo.evcam.profile.RecordSpecs.forCameraKey(context, cameraPosition).fps,
                CameraCapabilities.declaredMaxFps() > 0
                        ? CameraCapabilities.declaredMaxFps()
                        : AppConfig.RECORDER_MAX_FPS);
        FpsRangePicker.Choice choice =
                FpsRangePicker.pick(CameraCapabilities.fpsRanges(cameraId), target);
        if (choice == null) {
            AppLog.i(TAG, "Camera " + cameraId + " 未声明帧率区间，不请求 —— 跑多少是多少");
            return;
        }
        try {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    new android.util.Range<>(choice.lower, choice.upper));
            AppLog.i(TAG, "Camera " + cameraId + " 请求帧率区间 " + choice
                    + "（目标 " + target + " fps）"
                    + (choice.isFixed() ? "" : "  << 可变区间，自动曝光仍可能下滑到下限"));
        } catch (Exception e) {
            AppLog.w(TAG, "Camera " + cameraId + " 设置帧率区间失败: " + e);
        }
    }

    /**
     * 获取预览分辨率
     */
    public Size getPreviewSize() {
        return previewSize;
    }

    /**
     * 指定这台相机的目标分辨率，优先于全局「画质设置」。
     * 传 null 表示恢复走全局配置。
     */
    public void setPreferredSize(Size size) {
        this.preferredSize = size;
        AppLog.d(TAG, "Camera " + cameraId + " 指定目标分辨率: " + size);
    }

    /**
     * 预览缓冲区实际使用的尺寸。
     *
     * <p>就是这一路的预览尺寸 —— 它写在配置里，按路设置。以前这里还夹着一个
     * 「预览用低分辨率」的全局开关，会在配置指定的尺寸之外再挑一个更小的缓冲区，
     * 于是配置编辑里写着 1280×5140、实际跑的是 1280×720。</p>
     */
    public Size getPreviewBufferSize() {
        return previewSize;
    }

    /**
     * 切换到仅预览模式 —— 停止往要走的录像输出送帧（相机线程，只由「改输出」那一步在重配之前调，{@link #reconfigure}；
     * 大纲阶段 5d）：
     * 重复请求换成只送会话里别的活输出 —— 除了要走的录像输出（会话里有、要的那一组里没有的录像），预览、后视镜、盲区画面、出帧口、
     * 留着的输出都照送，带上出帧回调（这期间的帧照样计数，不会被当成没画面）。
     *
     * <p>为什么：MediaRecorder 分段要换一个新的录像输出，旧的那个 MediaRecorder 等它离开会话才停、收文件
     * （{@link VideoRecorder}）。先停止往它送帧，它的文件就停在这一刻，重配的那几百毫秒不再往里写。</p>
     *
     * <p>没有别的活输出可送（会话里只有要走的录像）、或者会话、设备不在，就不换 —— 接着重配时关旧会话，送帧照样停。
     * 不再退回停掉整条重复请求（{@code stopRepeating}）：那样预览、后视镜跟着一起停。</p>
     *
     * @param wanted 这一步要的那一组输出
     * @return true：换成了只送别的输出
     */
    private boolean switchToPreviewOnlyMode(Set<ChannelRules.Out> wanted) {
        final CameraCaptureSession session = captureSession;
        final CameraDevice device = cameraDevice;
        List<Surface> keep = new ArrayList<>();
        synchronized (outputLock) {
            for (Map.Entry<ChannelRules.Out, Surface> entry : carried.entrySet()) {
                ChannelRules.Out out = entry.getKey();
                boolean leavingRecord = out.kind == ChannelRules.Kind.RECORD && !wanted.contains(out);
                if (!leavingRecord && alive(out.consumer) && entry.getValue().isValid()) {
                    keep.add(entry.getValue());
                }
            }
        }
        if (session == null || device == null || keep.isEmpty()) {
            AppLog.d(TAG, "Camera " + cameraId + " no other live output to keep while the record output leaves;"
                    + " the reconfigure closes the old session");
            return false;
        }
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            for (Surface surface : keep) {
                builder.addTarget(surface);
            }
            // 画面调节开着：曝光补偿、白平衡照现在的请求带上
            if (imageAdjustEnabled && currentRequestBuilder != null) {
                try {
                    Integer exposure = currentRequestBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
                    if (exposure != null) {
                        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure);
                    }
                    Integer awbMode = currentRequestBuilder.get(CaptureRequest.CONTROL_AWB_MODE);
                    if (awbMode != null) {
                        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
                    }
                } catch (Exception e) {
                    // 复制不了就用模板的默认值
                }
            }
            session.setRepeatingRequest(builder.build(), activeCaptureCallback, backgroundHandler);
            AppLog.d(TAG, "Camera " + cameraId + " stopped frames to the leaving record output, " + keep.size()
                    + " output(s) keep streaming");
            return true;
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            // 会话正在关、设备不支持换请求目标：不换，接着重配时关旧会话，送帧照样停
            AppLog.w(TAG, "Camera " + cameraId + " could not stop frames to the leaving record output: " + e);
            return false;
        }
    }

    /**
     * 选择最优分辨率
     * 根据用户配置的目标分辨率进行匹配：
     * - 默认：优先1280x800，否则最接近的
     * - 指定分辨率：优先精确匹配，否则最接近的
     */
    private Size chooseOptimalSize(Size[] sizes) {
        // 这台相机被单独指定了尺寸就直接用（前提是 HAL 确实声明过）
        Size preferred = preferredSize;
        if (preferred != null) {
            for (Size size : sizes) {
                if (size.getWidth() == preferred.getWidth()
                        && size.getHeight() == preferred.getHeight()) {
                    AppLog.d(TAG, "Camera " + cameraId + " 使用指定分辨率: " + preferred);
                    return size;
                }
            }
            AppLog.w(TAG, "Camera " + cameraId + " 指定分辨率 " + preferred
                    + " 未被声明，回退到全局配置");
        }

        // 配置里这一路写的是 auto（不指定）时才走到这里：挑最接近 1280x800 的。
        // 这个数是上游 guardapp 用的，留作没有任何指定时的落点。
        // 要具体尺寸就在配置编辑里写具体尺寸 —— 那才是唯一说了算的地方。
        int targetWidth = 1280;
        int targetHeight = 800;

        // 首先尝试找到精确匹配
        for (Size size : sizes) {
            if (size.getWidth() == targetWidth && size.getHeight() == targetHeight) {
                AppLog.d(TAG, "Camera " + cameraId + " found exact match: " + targetWidth + "x" + targetHeight);
                return size;
            }
        }

        // 找到最接近目标分辨率的
        Size bestSize = null;
        int minDiff = Integer.MAX_VALUE;

        for (Size size : sizes) {
            int width = size.getWidth();
            int height = size.getHeight();

            // 计算与目标分辨率的差距
            int diff = Math.abs(targetWidth - width) + Math.abs(targetHeight - height);
            if (diff < minDiff) {
                minDiff = diff;
                bestSize = size;
            }
        }

        if (bestSize == null) {
            // 如果还是没找到，使用第一个可用分辨率
            bestSize = sizes[0];
            AppLog.d(TAG, "Camera " + cameraId + " using first available size: " + bestSize.getWidth() + "x" + bestSize.getHeight());
        } else {
            AppLog.d(TAG, "Camera " + cameraId + " selected closest match: " + bestSize.getWidth() + "x" + bestSize.getHeight() + 
                    " (target was " + targetWidth + "x" + targetHeight + ")");
        }

        return bestSize;
    }

    /**
     * 启动后台线程
     */
    private void startBackgroundThread() {
        if (backgroundThread != null && backgroundThread.isAlive() && backgroundHandler != null) {
            return;   // 这一轮已经有相机线程了。以前每次都新起一条，旧的就悬在那里
        }
        backgroundThread = new HandlerThread("Camera-" + cameraId);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        // 卡顿报告里要看得出相机线程是不是被堵住了
        StallWatch.watchLooper("Camera-" + cameraId, backgroundHandler);
    }

    /**
     * 卡顿报告里这一路相机的状态，一行。
     *
     * <p>不加锁：从监测线程读，拿到的是那一瞬间的近似值，够判断就行 ——
     * 为了一份诊断去抢相机线程的锁，反而可能把相机线程卡住。</p>
     */
    public String describeForStall() {
        long last = lastFrameTimestampMs;
        return "camera " + cameraId + " (" + cameraPosition + " @"
                + Integer.toHexString(System.identityHashCode(this)) + ")"
                + " device=" + (cameraDevice != null)
                + " session=" + (captureSession != null)
                + " opening=" + isOpening
                + " configuring=" + isConfiguring
                + " closing=" + isSessionClosing
                + " lost=" + deviceLost
                + " jpeg=" + (jpegReader != null)
                + " channel[requested=" + requested.get() + " session=" + describeSessionOutputs() + "]"
                + " fps=" + String.format(java.util.Locale.US, "%.1f", currentFps)
                + " lastResult=" + (last > 0 ? (System.currentTimeMillis() - last) + "ms ago" : "never");
    }

    /**
     * 卡顿现场里的会话：每一样输出标上调度怎么看它 ——「要」「留着 31 秒」「死 3 秒」（大纲 §2 阶段 8）。
     * 调度还没分过类的那一样只写种类。
     */
    private String describeSessionOutputs() {
        SessionOutputs session = sessionSnapshot;
        if (!session.exists()) {
            return session.toString();
        }
        Map<ChannelRules.Out, ChannelScheduler.OutputState> states = outputStates;
        long now = SystemClock.uptimeMillis();
        StringBuilder sb = new StringBuilder("gen=").append(session.generation).append(" [");
        boolean first = true;
        for (ChannelRules.Out out : session.outputs) {
            sb.append(first ? "" : ", ").append(out.kind);
            first = false;
            ChannelScheduler.OutputState state = states.get(out);
            if (state == null) {
                continue;
            }
            long seconds = Math.max(0, now - state.since) / 1000;
            switch (state.state) {
                case WANTED:
                    sb.append(" 要");
                    break;
                case KEPT:
                    sb.append(" 留着 ").append(seconds).append(" 秒");
                    break;
                default:
                    sb.append(" 死 ").append(seconds).append(" 秒");
                    break;
            }
        }
        return sb.append(']').toString();
    }

    /**
     * 调度交来会话里每一样输出算哪一类、从什么时候起（主线程，变了才交；交的是不可变的一份）：只给卡顿现场标「要 / 留着 / 死」。
     */
    void noteOutputStates(Map<ChannelRules.Out, ChannelScheduler.OutputState> states) {
        outputStates = states == null ? Collections.emptyMap() : states;
    }

    /** 丢帧回调里的 Surface 是哪一路输出：拍照的 JPEG，或者按会话带着的输出认。 */
    private String describeTarget(Surface target) {
        if (target == null) {
            return "null";
        }
        if (jpegReader != null && target == jpegReader.getSurface()) {
            return "jpeg";
        }
        synchronized (outputLock) {
            for (Map.Entry<ChannelRules.Out, Surface> entry : carried.entrySet()) {
                if (entry.getValue() == target) {
                    switch (entry.getKey().kind) {
                        case PREVIEW:
                            return "main-preview";
                        case MIRROR:
                            return "mirror";
                        case SIDE:
                            return "side";
                        case HUD:
                            return "hud";
                        case RECORD:
                            return "record";
                        default:
                            return "sink";
                    }
                }
            }
        }
        return "other";
    }

    /**
     * 最近 {@code ms} 毫秒里有没有真的出过画面。
     *
     * <p>和 {@link #progressAgeMs()} 不同：那个开相机、建会话也算「有动静」；
     * 这里只认画面。一路录像写不进时，看它的相机是不是真在出画面（{@link RecordingLanes#FRESH_FRAMES_MS}），
     * 拍照能不能按快门（{@link #readyForPhoto}），问的都是这个。</p>
     */
    public boolean hasFramesWithin(long ms) {
        long last = lastCaptureUptimeMs;
        return last != 0 && SystemClock.uptimeMillis() - last < ms;
    }

    /** 记一次断帧（出帧回调里，相机线程）。 */
    private void noteFrameGap(long endedAt, long length) {
        synchronized (gapLock) {
            gapEndedAt[gapNext] = endedAt;
            gapLength[gapNext] = length;
            gapNext = (gapNext + 1) % GAP_HISTORY;
        }
    }

    /**
     * 从 {@code sinceUptimeMs} 到现在，这一路最长断了多久帧（毫秒）：这段时间里记下的断帧（只算落在这段时间里的那一截），
     * 和此刻还没结束的那一段。不到 {@link #NOTABLE_GAP_MS} 的不记，那时返回的也不到它。
     *
     * <p>一路单独离开、接回录像时（2026-10-10），黑匣子要写「这几秒里别的路最长断帧多久」：车上核对「别的路没被动到」
     * 看的就是它 —— 相机层的调试日志只留在内存里，车机断电、进程被杀之后就没了。</p>
     */
    public long longestFrameGapSince(long sinceUptimeMs) {
        long longest = 0;
        synchronized (gapLock) {
            for (int i = 0; i < GAP_HISTORY; i++) {
                if (gapEndedAt[i] > sinceUptimeMs) {
                    longest = Math.max(longest, Math.min(gapLength[i], gapEndedAt[i] - sinceUptimeMs));
                }
            }
        }
        long last = lastCaptureUptimeMs;
        return Math.max(longest, SystemClock.uptimeMillis() - Math.max(last, sinceUptimeMs));
    }

    /**
     * 会话重建的代数（每真去建一次、作废在途的一次、设备没了，都 +1）。一路单独离开、接回录像时记下别的路的，
     * 结束时对一下：变了就是它们的会话这段时间里被动过。
     */
    public int sessionGeneration() {
        synchronized (sessionLock) {
            return sessionGeneration;
        }
    }

    /**
     * 距上一次「有动静」多久：出了一帧、开了相机、建好会话、有动作上路，都算。调度的救援据此判这一路卡住没有
     * （{@link CameraLiveness#STUCK_MS}）。
     *
     * <p>从没开过相机的返回 0（不是无穷大）—— 没开过就不该被判成卡住。</p>
     */
    public long progressAgeMs() {
        long last = lastProgressUptimeMs;
        return last == 0 ? 0 : Math.max(0, SystemClock.uptimeMillis() - last);
    }

    /** 能按快门了：设备开着、会话在、最近 {@code freshMs} 毫秒里真出过画面。 */
    public boolean readyForPhoto(long freshMs) {
        return cameraDevice != null && captureSession != null && hasFramesWithin(freshMs);
    }

    /** 最后一次报错的短名，没有就返回 null。 */
    public String lastErrorName() {
        return lastErrorName;
    }

    /**
     * 获取当前实时 FPS（1秒滚动窗口）
     */
    public float getCurrentFps() {
        return currentFps;
    }

    /**
     * 打开。
     *
     * @param wanted 调度要这份会话带的那一组输出
     * @return false：没有相机线程，开不了（已经报了 {@link CameraCallback#onOpenFailed}）
     */
    private boolean beginOpen(Set<ChannelRules.Out> wanted) {
        isOpening = true;
        markInFlight();

        synchronized (deviceLock) {
            requested.set(wanted);
            deviceLost = false;
            AppLog.d(TAG, "openCamera: Starting for camera " + cameraId + " (channel) outputs " + wanted);
        }

        // 相机服务的调用（查设备、查参数、打开）都放到这一路自己的相机线程上：
        // 相机服务卡住时，卡住的是这一路的相机线程，不是主线程 —— 卡顿监测里一眼分得清。
        // 这一台还有关没返回的，也在那条线程上等它返回（waitForNoClose），主线程不等
        startBackgroundThread();
        Handler handler = backgroundHandler;
        if (handler == null || !handler.post(() -> openOnCameraThread(handler))) {
            isOpening = false;
            AppLog.e(TAG, "Camera " + cameraId + " has no camera thread, cannot open");
            reportOpenFailed(CameraTaken.Reason.ofException("NoCameraThread"));
            return false;
        }
        return true;
    }

    // ===================================================================== 2.11 动作（只给调度调，主线程）

    /**
     * 打开这一路，会话带着 {@code outputs} 建（一样都没有、或者都死了，补出帧口 —— 相机得有地方出帧）。
     *
     * <p>这一步做完看 {@link CameraCallback#onFirstFrame}；开不起来报 {@link CameraCallback#onOpenFailed}。</p>
     *
     * <p>上一次开、重开在途已经超过 {@link #IN_FLIGHT_MAX_MS}（回调当它不会来了）：那一轮作废，换一轮开
     * （{@link #retireStaleOpen}），和 {@link #reopen} 同一条规矩。这一台有一次关没返回的不作废：那一轮在等它返回
     * （{@link #openOrCloseInFlight}）。</p>
     *
     * <p>这一台有一次关没返回（任何一份实例的）：照样上路，在相机线程上等它返回再开（{@link #waitForNoClose}，不设时限，
     * 项目所有者 2026-10-28）。调度看得见那一次关（{@link #closingSince}），本来就不开这一路；走到这里的只有前后脚的那种。</p>
     *
     * @return false：这一路已经开着或正在开（含一次强制重开还在路上：那 300 ms 里设备是空的、也不算在开，
     *         可相机线程是同一条 —— 再开就是同一台相机两个句柄），什么都没做；或者没有相机线程
     */
    boolean open(Set<ChannelRules.Out> outputs) {
        if (cameraDevice != null) {
            AppLog.d(TAG, "Camera " + cameraId + " already open, channel open skipped");
            return false;
        }
        if (isOpening || reopenInFlight) {
            if (openOrCloseInFlight()) {
                AppLog.d(TAG, "Camera " + cameraId + " already opening (" + describeBusy() + "), channel open skipped");
                return false;
            }
            return retireStaleOpen(frozen(outputs));
        }
        return beginOpen(frozen(outputs));
    }

    /**
     * 改这一路的输出：会话按 {@code outputs} 重配（关掉旧会话、建新的）。哪怕和眼下一样也重配 —— 调度判的是要改。
     *
     * <p>有一路录像输出要换成另一个时（MediaRecorder 分段），先停止往旧的那个送帧
     * （{@link #switchToPreviewOnlyMode}，只由这一步调），再重配。</p>
     *
     * @return false：设备没开着（丢了、关了），什么都没做
     */
    boolean applyOutputs(Set<ChannelRules.Out> outputs) {
        return reconfigure(outputs, true, "apply");
    }

    /**
     * 救：重建会话（第一次救、本会话出过画面时用）。配好之后一帧都没出过的会话，重建没有意义 —— 它没有东西可以排空，
     * 重建的 waitUntilIdle 每次都超时、报设备错误，再关一次设备（2026-10-08 实测，环视一次要 4–13 秒）；
     * 那种由调度直接重开（{@link #reopen}）。
     *
     * @return false：设备没开着，什么都没做
     */
    boolean rebuildSession(Set<ChannelRules.Out> outputs) {
        return reconfigure(outputs, false, "rescue-rebuild");
    }

    /**
     * 救：重开（关掉设备，等 300 ms，再开），会话带着 {@code outputs}。关着的一路也走这里。
     *
     * <p>合并：这一台相机的开、重开还在途（{@link #openOrCloseInFlight}，封顶 {@link #IN_FLIGHT_MAX_MS}），或者有一次关没返回
     * （不封顶，任何一份实例的，项目所有者 2026-10-28），就合并掉、什么都不做 —— 同一台相机两个句柄，相机服务会踢掉先开的那份
     * （2026-10-08，自己顶自己 7 次）。
     * 配会话、关旧会话还挂着不合并：设备在我们手上，重开把那一次作废、连设备一起关掉再开。调度只在那一步到点之后才来救
     * （审查 2026-10-11：以前也合并，卡在配会话里的那一路，救援梯子空转 60 秒、白算次数）。
     * 开、重开在途超过封顶的，那一轮作废、换一轮开（{@link #retireStaleOpen}）。</p>
     *
     * @return false：合并掉了
     */
    boolean reopen(Set<ChannelRules.Out> outputs) {
        return reopenWith(frozen(outputs));
    }

    /** 改输出、重建会话：换上要的那一组，在相机线程上重配。 */
    private boolean reconfigure(Set<ChannelRules.Out> outputs, boolean swapRecordFirst, String why) {
        final Handler handler = backgroundHandler;
        if (cameraDevice == null || handler == null) {
            AppLog.w(TAG, "Camera " + cameraId + " " + why + " skipped: device not open");
            return false;
        }
        final Set<ChannelRules.Out> wanted = frozen(outputs);
        requested.set(wanted);
        markInFlight();
        AppLog.d(TAG, "Camera " + cameraId + " " + why + ": outputs " + wanted);
        return handler.post(() -> {
            if (handler != backgroundHandler || cameraDevice == null) {
                return;   // 这期间被关了、丢了：那一边自己有结果
            }
            configFailRetryCount = 0;   // 新的一步：重试从头数
            if (swapRecordFirst && replacesRecord(wanted)) {
                switchToPreviewOnlyMode(wanted);
            }
            handler.removeCallbacks(recreateSessionRunnable);
            synchronized (sessionLock) {
                rebuildQueued = false;   // 排着的那一次并进这一次
            }
            createCameraPreviewSession();
        });
    }

    /** 会话里有一路录像输出要换成另一个（相机线程）：旧的不在要的那一组里，新的还不在会话里。 */
    private boolean replacesRecord(Set<ChannelRules.Out> wanted) {
        if (captureSession == null) {
            return false;
        }
        Set<ChannelRules.Out> now = sessionSnapshot.outputs;
        boolean leaving = false;
        boolean coming = false;
        for (ChannelRules.Out out : now) {
            if (out.kind == ChannelRules.Kind.RECORD && !wanted.contains(out)) {
                leaving = true;
            }
        }
        for (ChannelRules.Out out : wanted) {
            if (out.kind == ChannelRules.Kind.RECORD && !now.contains(out)) {
                coming = true;
            }
        }
        return leaving && coming;
    }

    // ===================================================================== 2.11 声明（主线程；不触发任何重建）

    /**
     * 声明主界面这一格的预览：以画布（{@code texture}）作身份。同一块画布再声明一次什么都不做；
     * 视图换了（主界面重建后新界面接回同一块画布）只换视图 —— 摆位、抓预览跟着新视图走，通道零变化。
     *
     * <p>主界面重建时（大纲 §7.6）旧界面销毁那一下已经撤回了声明（{@link #withdrawPreview}），新界面接回同一块画布时
     * 这里看到的是「原来没有」—— 照样返回 true（调用方叫醒调度，规则看到的还是会话里那个输出，通道不动）；
     * 会话里正带着这块画布，就也只换视图（2026-10-11，阶段 4a）。</p>
     *
     * @return true：声明变了（换了一块画布，或者原来没有），调用方该叫醒调度
     */
    public boolean declarePreview(TextureView view, SurfaceTexture texture) {
        if (view == null || texture == null) {
            return false;
        }
        ChannelRules.Out out = new ChannelRules.Out(ChannelRules.Kind.PREVIEW, texture);
        boolean changed;
        synchronized (outputLock) {
            Declared current = declaredPreview;
            changed = current == null || !current.out.equals(out);
            if (changed || current.view != view) {
                declaredPreview = new Declared(out, view, null, null);
            }
        }
        if (textureView != view) {
            // 换了视图（换了一块画布；或者同一块画布换了视图 —— 撤回后被新界面接回、会话里还带着它，主界面重建，大纲 §7.6）：
            // 只换视图和摆位。预览的 Surface 按画布记（owned），不跟着视图放
            textureView = view;
            if (autoMirrorBack()) {
                applyMirrorTransform();
            }
            applyLaneTransform();
        }
        if (changed) {
            AppLog.d(TAG, "Camera " + cameraId + " preview declared: " + texture);
            tidyLater();
        }
        return changed;
    }

    /**
     * 撤回预览：只撤回 {@code texture} 这一块（不是它就什么都不做）。撤回不碰会话 —— 画布还活着就是「留着的输出」，
     * 等下一次本来要动、或者关相机时一起摘；画布已被系统收走的，调度排一步马上摘。
     *
     * <p>撤回的那一格的视图也不再拿着（2026-10-11，阶段 4a）：画布销毁时主界面也销毁了，管线比界面活得久，
     * 拿着它就把整个销毁了的界面留住。只清视图：预览的 Surface 按画布记，会话里还带着它（留着的输出）就不能放。</p>
     */
    public boolean withdrawPreview(SurfaceTexture texture) {
        boolean changed;
        TextureView view = null;
        synchronized (outputLock) {
            changed = texture != null && declaredPreview != null && declaredPreview.out.consumer == texture;
            if (changed) {
                view = declaredPreview.view;
                declaredPreview = null;
            }
        }
        if (changed) {
            if (view != null && textureView == view) {
                textureView = null;
            }
            AppLog.d(TAG, "Camera " + cameraId + " preview withdrawn: " + texture);
            tidyLater();
        }
        return changed;
    }

    /**
     * 声明超级镜子的画布：以 SurfaceTexture 作身份，一个 SurfaceTexture 只建一个 Surface（建会话时在相机线程上建）。
     * 贴边、放回都不算变化 —— 画面只要活着就留在会话里。
     *
     * @param onDropped 相机层丢了它（录像中会话配不上时它在 JPEG 之后让路）时告诉谁（调度在主线程上派发）：超级镜子收到后撤回，
     *                  调度就不会一直想把它加回去、一次次重建这一路的会话（2026-10-20 审查，车主批；同盲区画面）
     */
    public boolean declareMirror(SurfaceTexture texture, RecordDropped onDropped) {
        if (texture == null) {
            return false;
        }
        ChannelRules.Out out = new ChannelRules.Out(ChannelRules.Kind.MIRROR, texture);
        boolean changed;
        synchronized (outputLock) {
            changed = declaredMirror == null || !declaredMirror.out.equals(out);
            if (changed) {
                declaredMirror = new Declared(out, null, null, onDropped);
            }
        }
        if (changed) {
            AppLog.d(TAG, "Camera " + cameraId + " mirror declared: " + texture);
            tidyLater();
        }
        return changed;
    }

    /** 撤回后视镜：只撤回 {@code texture} 这一块。只在画布销毁、后视镜服务退出时撤回（贴边、熄屏只放登记）。 */
    public boolean withdrawMirror(SurfaceTexture texture) {
        boolean changed;
        synchronized (outputLock) {
            changed = texture != null && declaredMirror != null && declaredMirror.out.consumer == texture;
            if (changed) {
                declaredMirror = null;
            }
        }
        if (changed) {
            AppLog.d(TAG, "Camera " + cameraId + " mirror withdrawn: " + texture);
            tidyLater();
        }
        return changed;
    }

    /**
     * 声明盲区画面的画布：以 SurfaceTexture 作身份，同 {@link #declareMirror}。窗口藏起来、再显示都不算变化 ——
     * 画面只要活着就留在会话里。
     *
     * @param onDropped 相机层丢了它（共享预览流挂不下、会话配不上）时告诉谁（调度在主线程上派发）
     */
    public boolean declareSide(SurfaceTexture texture, RecordDropped onDropped) {
        if (texture == null) {
            return false;
        }
        ChannelRules.Out out = new ChannelRules.Out(ChannelRules.Kind.SIDE, texture);
        boolean changed;
        synchronized (outputLock) {
            changed = declaredSide == null || !declaredSide.out.equals(out);
            if (changed) {
                declaredSide = new Declared(out, null, null, onDropped);
            }
        }
        if (changed) {
            AppLog.d(TAG, "Camera " + cameraId + " side view declared: " + texture);
            tidyLater();
        }
        return changed;
    }

    /** 撤回盲区画面：只撤回 {@code texture} 这一块。 */
    public boolean withdrawSide(SurfaceTexture texture) {
        boolean changed;
        synchronized (outputLock) {
            changed = texture != null && declaredSide != null && declaredSide.out.consumer == texture;
            if (changed) {
                declaredSide = null;
            }
        }
        if (changed) {
            AppLog.d(TAG, "Camera " + cameraId + " side view withdrawn: " + texture);
            tidyLater();
        }
        return changed;
    }

    /**
     * 声明盲区画面在 HUD 上的画布（2026-10-21）：同 {@link #declareSide}，是另一样输出 —— 中控屏、HUD 两块可以同时在会话里。
     *
     * @param onDropped 相机层丢了它（共享预览流挂不下、会话配不上）时告诉谁（调度在主线程上派发）
     */
    public boolean declareHud(SurfaceTexture texture, RecordDropped onDropped) {
        if (texture == null) {
            return false;
        }
        ChannelRules.Out out = new ChannelRules.Out(ChannelRules.Kind.HUD, texture);
        boolean changed;
        synchronized (outputLock) {
            changed = declaredHud == null || !declaredHud.out.equals(out);
            if (changed) {
                declaredHud = new Declared(out, null, null, onDropped);
            }
        }
        if (changed) {
            AppLog.d(TAG, "Camera " + cameraId + " HUD side view declared: " + texture);
            tidyLater();
        }
        return changed;
    }

    /** 撤回 HUD 上的盲区画面：只撤回 {@code texture} 这一块。 */
    public boolean withdrawHud(SurfaceTexture texture) {
        boolean changed;
        synchronized (outputLock) {
            changed = texture != null && declaredHud != null && declaredHud.out.consumer == texture;
            if (changed) {
                declaredHud = null;
            }
        }
        if (changed) {
            AppLog.d(TAG, "Camera " + cameraId + " HUD side view withdrawn: " + texture);
            tidyLater();
        }
        return changed;
    }

    /**
     * 声明录像输出。
     *
     * @param consumer  身份：编码器模式是录制器的输入 SurfaceTexture（停放、重新备好都是同一个），
     *                  MediaRecorder 模式就是它给的 Surface
     * @param surface   相机写进去的 Surface，归录制器管，这里不放
     * @param onDropped 这个输出离开会话时告诉谁（调度派发：改输出摘掉、随相机关了、相机层自己丢了）
     * @return true：换了一个录像输出（或者原来没有）
     */
    public boolean declareRecord(Object consumer, Surface surface, RecordDropped onDropped) {
        if (consumer == null || surface == null) {
            return false;
        }
        ChannelRules.Out out = new ChannelRules.Out(ChannelRules.Kind.RECORD, consumer);
        boolean changed;
        synchronized (outputLock) {
            changed = declaredRecord == null || !declaredRecord.out.equals(out);
            declaredRecord = new Declared(out, null, surface, onDropped);
        }
        if (changed) {
            AppLog.d(TAG, "Camera " + cameraId + " record output declared: " + consumer);
        }
        return changed;
    }

    /** 撤回录像输出：只撤回 {@code consumer} 这一个。撤回了就不再通知它（撤回的一方自己知道）。 */
    public boolean withdrawRecord(Object consumer) {
        boolean changed;
        synchronized (outputLock) {
            changed = consumer != null && declaredRecord != null && declaredRecord.out.consumer == consumer;
            if (changed) {
                declaredRecord = null;
            }
        }
        if (changed) {
            AppLog.d(TAG, "Camera " + cameraId + " record output withdrawn: " + consumer);
        }
        return changed;
    }

    /** 声明着的录像、盲区画面（中控屏、HUD）、超级镜子输出 {@code out} 离开会话时该告诉谁；不是它、或者已经撤回了返回 null。 */
    public RecordDropped dropListener(ChannelRules.Out out) {
        synchronized (outputLock) {
            for (Declared current : new Declared[]{declaredRecord, declaredSide, declaredHud, declaredMirror}) {
                if (current != null && current.out.equals(out)) {
                    return current.onDropped;
                }
            }
            return null;
        }
    }

    // ===================================================================== 2.11 事实（主线程读）

    /** 眼下这份会话的快照：代号、各输出的消费者身份、出帧口、从何时起。没有会话是 {@link SessionOutputs#NONE}。 */
    public SessionOutputs sessionOutputs() {
        return sessionSnapshot;
    }

    /** 已声明、消费者还活着的输出。撤回了的不在这里，哪怕消费者还活着。 */
    public Set<ChannelRules.Out> declaredOutputs() {
        Set<ChannelRules.Out> result = new LinkedHashSet<>();
        synchronized (outputLock) {
            for (Declared declared : new Declared[]{declaredPreview, declaredMirror, declaredSide, declaredHud,
                    declaredRecord}) {
                if (declared != null && alive(declared.out.consumer)
                        && (declared.surface == null || declared.surface.isValid())) {
                    result.add(declared.out);
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** 会话里消费者已被系统收走的那几样（死输出）：调度排一步马上摘。出帧口是我们自己的，不会死。 */
    public Set<ChannelRules.Out> deadInSession() {
        Set<ChannelRules.Out> dead = new LinkedHashSet<>();
        for (ChannelRules.Out out : sessionSnapshot.outputs) {
            if (out.kind != ChannelRules.Kind.SINK && !alive(out.consumer)) {
                dead.add(out);
            }
        }
        return Collections.unmodifiableSet(dead);
    }

    /** 本会话第一帧的时刻（{@code SystemClock.uptimeMillis()}）；0 = 还没有。每建一份会话归零。 */
    public long firstFrameOfSessionAt() {
        return firstFrameAt;
    }

    /** 这一路的画面尺寸：{@link #resolveSize} 不开设备先算的，或者开相机时算的；null = 还没算出来（这一路不在）。 */
    public Size resolvedSize() {
        return resolved;
    }

    /**
     * 这一路此刻有动作在途的话，那个动作从什么时候起（uptime，见 {@link #isBusy()}）；不忙是 0。
     * 换了一个新动作这个时刻就跟着变 —— 调度据此分得清「同一个动作还挂着」和「又起了一个」。
     */
    public long busySince() {
        return isBusy() ? inFlightSinceMs : 0L;
    }

    /**
     * 不开设备，按 {@code CameraCharacteristics} 算出这一路的画面尺寸（和开相机前算尺寸是同一个函数）：
     * 录制器要在开相机之前按它建好，这一路开的时候才能直接带着录像输出开。
     *
     * <p>在一条临时的相机线程上做（{@code Camera-<id> size}，卡住了卡顿监测里看得出来），不占这一路的相机线程 ——
     * 没开着的一路就没有相机线程，关相机、强制重开都靠这一点认「没开着」。算出来报 {@link CameraCallback#onPreviewSizeChosen}。
     * 这一路不在相机列表里（车机拿着它）时算不出来：等它回来再调一次，或者开相机时照样会算。</p>
     */
    public void resolveSize() {
        final String watchName = "Camera-" + cameraId + " size";
        final HandlerThread thread = new HandlerThread(watchName);
        thread.start();
        final Handler handler = new Handler(thread.getLooper());
        StallWatch.watchLooper(watchName, handler);
        boolean posted = handler.post(() -> {
            try {
                resolveSizeNow();
            } finally {
                StallWatch.unwatchLooper(watchName);
                thread.quitSafely();
            }
        });
        if (!posted) {
            StallWatch.unwatchLooper(watchName);
            thread.quitSafely();
        }
    }

    private void resolveSizeNow() {
        try {
            if (!listedNow()) {
                AppLog.i(TAG, "Camera " + cameraId + " 不在相机列表里，画面尺寸等它回来再算");
                return;
            }
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            noteCharacteristics(characteristics);
            Size size = sizeFrom(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
            if (size == null) {
                AppLog.w(TAG, "Camera " + cameraId + " 没有声明可用的输出尺寸，画面尺寸算不出来");
                return;
            }
            previewSize = size;
            resolved = size;
            AppLog.i(TAG, "Camera " + cameraId + " 不开设备先算出画面尺寸: " + size);
            CameraCallback cb = callback;
            if (cb != null) {
                cb.onPreviewSizeChosen(cameraId, size);
            }
        } catch (CameraAccessException | RuntimeException e) {
            AppLog.w(TAG, "Camera " + cameraId + " 算画面尺寸失败（开相机时再算）: " + e);
        }
    }

    /** 这一路此刻在不在相机列表里（进相机服务，不在主线程上调）。 */
    private boolean listedNow() throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            if (id.equals(cameraId)) {
                return true;
            }
        }
        return false;
    }

    /** 查到的相机参数记下来：帧率上限给设置界面，镜像判断要的朝向也顺手记（视图线程上不该再进相机服务）。 */
    private void noteCharacteristics(CameraCharacteristics characteristics) {
        CameraCapabilities.record(cameraId, characteristics);
        cameraCharacteristics = characteristics;
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        sourceMirrored = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
    }

    /** 系统会不会把这一路给预览的画面左右翻一次（前置摄像头）：超级镜子摆后座舱画面时要先翻回正常视角。 */
    public boolean isSourceMirrored() {
        return sourceMirrored();
    }

    /**
     * 这一路的画面尺寸：PRIVATE 声明的尺寸里挑（没有就退到 SurfaceTexture 的），挑法见 {@link #chooseOptimalSize}。
     * 开相机前和 {@link #resolveSize} 用的是这同一个函数。算不出来返回 null。
     */
    private Size sizeFrom(StreamConfigurationMap map) {
        if (map == null) {
            return null;
        }
        // 优先使用 SurfaceTexture 的输出尺寸
        Size[] sizes = map.getOutputSizes(ImageFormat.PRIVATE);
        if (sizes == null || sizes.length == 0) {
            sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes != null && sizes.length > 0) {
                AppLog.w(TAG, "Camera " + cameraId + " no PRIVATE sizes, fallback to SurfaceTexture sizes");
            }
        }
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        // 打印所有可用分辨率
        AppLog.d(TAG, "Camera " + cameraId + " available sizes:");
        for (int i = 0; i < Math.min(sizes.length, 10); i++) {
            AppLog.d(TAG, "  [" + i + "] " + sizes[i].getWidth() + "x" + sizes[i].getHeight());
        }
        return chooseOptimalSize(sizes);
    }

    /** 开不起来：报给调度（相机线程，或者没有相机线程时的调用方线程）。 */
    private void reportOpenFailed(CameraTaken.Reason reason) {
        AppLog.w(TAG, "Camera " + cameraId + " open failed: " + reason);
        CameraCallback cb = callback;
        if (cb != null) {
            cb.onOpenFailed(cameraId, reason);
        }
    }

    /** 打开抛的异常换成原因：{@code CameraAccessException} 带着它的码，别的只记类名。 */
    private static CameraTaken.Reason reasonOf(Exception e) {
        return e instanceof CameraAccessException
                ? CameraTaken.Reason.ofAccess(((CameraAccessException) e).getReason())
                : CameraTaken.Reason.ofException(e.getClass().getSimpleName());
    }

    /** 消费者还活着：SurfaceTexture 没被放掉，Surface 还有效。出帧口那种（不是画布）算活着。 */
    private static boolean alive(Object consumer) {
        if (consumer instanceof SurfaceTexture) {
            return !((SurfaceTexture) consumer).isReleased();
        }
        if (consumer instanceof Surface) {
            return ((Surface) consumer).isValid();
        }
        return true;
    }

    private static Set<ChannelRules.Out> frozen(Set<ChannelRules.Out> outputs) {
        Set<ChannelRules.Out> copy = new LinkedHashSet<>();
        if (outputs != null) {
            for (ChannelRules.Out out : outputs) {
                if (out != null) {
                    copy.add(out);
                }
            }
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Set<ChannelRules.Out> without(Set<ChannelRules.Out> outputs, ChannelRules.Out out) {
        Set<ChannelRules.Out> copy = new LinkedHashSet<>(outputs);
        copy.remove(out);
        return Collections.unmodifiableSet(copy);
    }

    /** 和这一样输出相同的声明；没有返回 null（{@link #outputLock}）。 */
    private Declared declaredFor(ChannelRules.Out out) {
        for (Declared declared : new Declared[]{declaredPreview, declaredMirror, declaredSide, declaredHud,
                declaredRecord}) {
            if (declared != null && declared.out.equals(out)) {
                return declared;
            }
        }
        return null;
    }

    /**
     * 开、重开之前（这一轮的相机线程上）：这一台相机还有一次关没返回（{@link #CLOSING}：任何一份实例、任何一种关），就等它返回 ——
     * 不设时限（项目所有者 2026-10-28：我们上一次关一台相机还没返回，App 里谁都不开它，直到那一次关返回、或者进程结束；
     * 相机服务说它空闲也不算数）。返回了再看一次：等的时候可能又登记了一次（另一份实例在关、晚到的设备就地关）。
     * 开、重开那一步开头问一次，进相机服务打开之前（{@link #openCameraMarked}）再问一次：中间那几次进相机服务的时候也可能登记了一次。
     *
     * <ul>
     *   <li>每次等之前先看这一轮还是不是当前的：被关了、换了一轮的不等，这一次作废（标记归关它的那一下、或者新的那一轮）——
     *       两份实例、两轮互相等的那种由此排除；</li>
     *   <li>链上还没返回的哪一次，关它的那一下排在这条线程上、还没轮到（{@link Closing#queuedOn}，和 {@link #beginClosing} 同一个看法）
     *       的不等：等它就是等自己。这一次报开不起来，要不要再开由调度判 —— 调度看得见那一次关，返回之前不开、不救这一路；</li>
     *   <li>接着开之前记一下有动作上路（{@link #markInFlight}），等没等过都记：重开排在关旧设备那一下后面、没在这里等，那一下可能关了
     *       一分多钟；等过的那一轮是在等关、不是开了 60 秒没回音。等的时候、关返回之后到记下之前，这一份算在等关
     *       （{@link #waitingForClose}，不封顶地算在途）—— 两样一起，那一次关返回的那一下不会被当成开了 60 秒没回音作废
     *       （{@link #retireStaleOpen}，审查 2026-10-28）。</li>
     * </ul>
     *
     * <p>等的时候不拿锁（{@link #deviceLock}、{@link #sessionLock}）：主线程上的关相机照样做得了，这一轮被关了的，返回之后才看得到、作废。</p>
     *
     * @return true：没有没返回的关了、这一轮还是当前的，接着开；false：这一轮作废了，或者那一次关排在这条线程上（已经报了开不起来）
     */
    private boolean waitForNoClose(Handler handler) {
        long waitStart = 0;
        try {
            Closing closing;
            while ((closing = CLOSING.get(cameraId)) != null) {
                if (handler != backgroundHandler) {
                    return false;
                }
                if (closing.queuedOn(android.os.Looper.myLooper())) {
                    AppLog.w(TAG, "Camera " + cameraId + " open skipped: a close of it (latest " + closing.why
                            + ") is queued on this camera thread");
                    failOpenIfCurrent(handler, CameraTaken.Reason.ofException("CloseQueuedOnThisThread"), true, 0, null);
                    return false;
                }
                if (waitStart == 0) {
                    waitStart = SystemClock.elapsedRealtime();
                    waitingForClose.incrementAndGet();
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + who() + " 打开前等上一次关返回（" + closing.why
                            + "，已经关了 " + secondsText(SystemClock.uptimeMillis() - closing.outstandingSince)
                            + "）：返回之前不开");
                }
                awaitClosed(closing);
            }
            if (handler != backgroundHandler) {
                return false;
            }
            if (waitStart != 0) {
                long waited = SystemClock.elapsedRealtime() - waitStart;
                if (waited >= 500) {
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId + " 等上一次关闭完成用了 "
                            + waited + "ms 才打开");
                }
            }
            markInFlight();
            return true;
        } finally {
            if (waitStart != 0) {
                waitingForClose.decrementAndGet();   // 记下有动作上路之后才不算在等关（或者这一轮作废了）
            }
        }
    }

    /**
     * 打开的实际步骤，在这一路的相机线程上跑。
     *
     * <p>先等这一台相机没返回的关返回（{@link #waitForNoClose}，不设时限）。等的时候又被关了、或者已经换了一轮，
     * 这一次就作废。</p>
     */
    private void openOnCameraThread(Handler handler) {
        if (!waitForNoClose(handler) || !isOpening) {
            return;
        }
        configFailRetryCount = 0;   // 新的一步：会话配不上的重试从头数
        try {
            // 验证摄像头ID是否存在
            String[] availableCameraIds = cameraManager.getCameraIdList();
            boolean cameraExists = false;
            for (String id : availableCameraIds) {
                if (id.equals(cameraId)) {
                    cameraExists = true;
                    break;
                }
            }

            if (!cameraExists) {
                AppLog.e(TAG, "Camera ID " + cameraId + " does not exist on this device. Available IDs: " +
                         java.util.Arrays.toString(availableCameraIds));
                // 车机拿着后座舱时就是这样：相机服务的列表里干脆没有它（2026-10-10）
                failOpenIfCurrent(handler, CameraTaken.Reason.ofNotListed(), false,
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE, null);
                return;
            }

            // 获取摄像头特性（验证摄像头是否真正可用）
            CameraCharacteristics characteristics;
            try {
                characteristics = cameraManager.getCameraCharacteristics(cameraId);
                // 相机声明的帧率上限记下来 —— 设置界面拿不到相机对象，
                // 而「原始帧率」那一项以前显示的是一个和相机无关的写死的数；
                // 镜像判断要的 LENS_FACING 也顺手记下：sourceMirrored() 在视图线程上被调，不该再进相机服务
                noteCharacteristics(characteristics);
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " failed to get characteristics - camera may be virtual/invalid", e);
                failOpenIfCurrent(handler, reasonOf(e), true, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE, null);
                return;
            }

            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size chosen = sizeFrom(map);
                if (chosen == null) {
                    AppLog.e(TAG, "Camera " + cameraId + " has no output sizes for PRIVATE/SurfaceTexture - camera may be virtual/invalid");
                    failOpenIfCurrent(handler, CameraTaken.Reason.ofException("NoOutputSizes"), true,
                            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE, null);
                    return;
                }

                // 选择合适的分辨率
                previewSize = chosen;
                resolved = chosen;
                AppLog.d(TAG, "Camera " + cameraId + " selected preview size: " + previewSize);

                // 拍照通道：开着「拍照使用 JPEG 输出」时，建一个常驻的 JPEG 输出。
                // 关着时什么都不建，行为和以前完全一样（抓预览画面）。
                // 每次真正开相机都再试一次拍照通道：上一次是因为当时那套流
                // 配不上才丢的，换了配置未必还配不上
                jpegDropped = false;
                prepareJpegReader(map);
                closeFrameSink();   // 尺寸可能换了；要用时按新的预览尺寸再建

                // 通知回调预览尺寸已确定
                if (callback != null && previewSize != null) {
                    callback.onPreviewSizeChosen(cameraId, previewSize);
                }
            } else {
                AppLog.e(TAG, "Camera " + cameraId + " StreamConfigurationMap is null - camera may be virtual/invalid!");
                failOpenIfCurrent(handler, CameraTaken.Reason.ofException("NoStreamConfigurationMap"), true,
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE, null);
                return;
            }

            boolean textureAvailable = textureView != null && textureView.isAvailable();
            AppLog.d(TAG, "Camera " + cameraId + " TextureView available: " + textureAvailable);
            if (textureView != null && textureView.getSurfaceTexture() != null) {
                AppLog.d(TAG, "Camera " + cameraId + " SurfaceTexture exists");
            }

            // 打开摄像头（前面几次进相机服务的时候这一轮可能被关了：openCameraMarked 在锁里最后看一次）
            AppLog.d(TAG, "Camera " + cameraId + " calling openCamera...");
            openCameraMarked(handler);

        } catch (CameraAccessException | RuntimeException e) {
            // 打开没发出去（相机服务不可用、没权限、无效相机）：标成「没开」，报开不起来，再不再试由调度判
            AppLog.e(TAG, "Failed to open camera " + cameraId, e);
            failOpenIfCurrent(handler, reasonOf(e), true, e instanceof SecurityException ? -2
                            : e instanceof CameraAccessException ? -1 : CameraDevice.StateCallback.ERROR_CAMERA_DEVICE,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 摄像头状态回调
     */
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            // 相机服务交给我们一台设备：记进这一台的来历（车机重新初始化以来第几台，2.11.5 第 D1 项）。过时的也算 ——
            // 那也是 HAL 为我们开的一台，紧接着就地关掉
            StepLog.deviceOpened(cameraId);
            // 过时不过时、把设备挂上，在同一把锁里定（审查 2026-10-11）：以前锁外先看、锁里再挂，
            // 主线程的 closeCamera 落在中间的话，设备挂到一轮已经关掉的账上，再没人关它
            boolean stale;
            synchronized (deviceLock) {
                stale = isStale();
                if (!stale) {
                    isOpening = false;
                    reopenInFlight = false;
                    cameraDevice = camera;
                    lastErrorName = null;
                    deviceLost = false;
                    counts.clear();   // 新的一台设备：上一台那一份不再算，会话配好时重新数
                    CameraContention.ourCameraOpened(cameraId);
                    AppLog.d(TAG, "Camera " + cameraId + " opened");
                    if (callback != null) {
                        callback.onCameraOpened(cameraId);
                    }
                }
            }
            if (stale) {
                // 这一轮在打开途中就被关了：设备晚到一步。直接关掉（锁外：它进相机服务），别让它挂着占住相机
                closeStale(camera, "opened after close");
                return;
            }
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            if (isStale()) {
                closeStale(camera, "stale disconnect");
                return;
            }
            // 这是相机服务把我们踢掉：被别的程序（多半是原厂功能）拿走，或者设备自己没了。
            // 基座把它记成自定义的 -4，标签写的「资源耗尽」是错的
            com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + who() + " 被相机服务断开（onDisconnected）：先报、再关");
            CameraContention.ourCameraDisconnected(cameraId);
            AppLog.w(TAG, "Camera " + cameraId + " DISCONNECTED");
            loseDevice(camera, LOST_DISCONNECTED, "DISCONNECTED", "onDisconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            if (isStale()) {
                closeStale(camera, "stale error");
                return;
            }
            com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + who() + " 出错 error=" + error);
            String errorMsg;
            switch (error) {
                case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                    errorMsg = "ERROR_CAMERA_IN_USE (1) - Camera is being used by another app";
                    break;
                case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                    errorMsg = "ERROR_MAX_CAMERAS_IN_USE (2) - Too many cameras open";
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                    errorMsg = "ERROR_CAMERA_DISABLED (3) - Camera disabled by policy (likely background restriction)";
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                    errorMsg = "ERROR_CAMERA_DEVICE (4) - Device error (may be temporary due to resource contention)";
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                    errorMsg = "ERROR_CAMERA_SERVICE (5) - Camera service error";
                    break;
                default:
                    errorMsg = "UNKNOWN (" + error + ")";
                    break;
            }
            AppLog.e(TAG, "Camera " + cameraId + " error: " + errorMsg);
            CameraContention.ourOpenFailed(cameraId, errorMsg);
            loseDevice(camera, error, errorMsg, "onError");
        }
    };

    /**
     * 设备没了：被相机服务断开（{@code onDisconnected}），或者报错（{@code onError}）。在这一轮的相机线程上。
     *
     * <p>2026-10-10 起先摘、再关：锁里把设备和会话从字段上摘下来、标成「没开」，这一次关登记成在途
     * （{@link #isBusy()}、{@link #holdsOrIsOpening()} 都算上；调度让别的路最多等 {@link #IN_FLIGHT_MAX_MS}，这一台返回之前不开，
     * 项目所有者 2026-10-28），然后才去关。
     * 以前是先关、关完才摘：车机拿走后座舱相机 1 时，这一次关在相机服务里卡 5–8 秒（{@code ICameraDeviceUser.disconnect}），
     * 这几秒里设备字段还在、也不算在途 —— 看门狗、按次序开相机都当通道是静的，录像要等它关完才知道这一路没了，
     * 相机服务那一声「被占用」也被记成了「我们开着」。</p>
     *
     * <ul>
     *   <li>被断开、报错 1（被占用）/ 2（相机数到上限）：多半是别的程序拿走了 —— 先报（录像那一侧当场知道），再关；</li>
     *   <li>设备错误 3 / 4 / 5：照旧关完再报 —— 录制器留着，调度重开，画面回来同一个录制器接着录。</li>
     * </ul>
     *
     * <p>是不是被别的程序拿走的、要不要重开、什么节奏，2.11 阶段 3 起只由通道调度判（下面那条给调度的路）：
     * 以前这里关完还 post 一个 CameraTaken.judgeLoss 到主线程，判完叫看门狗重开 —— 和调度两头动手，删了。</p>
     *
     * <p>关的途中被 {@link #closeCamera} 关了（这一轮的相机线程已经摘下）：关完什么都不再写、不再报 ——
     * 它已经关了，不是丢了。</p>
     *
     * <p>2.11 起另有一条给调度的路（{@link CameraCallback} 下半截），不论错误码都一样：设备交到过我们手上的，
     * 关之前报 {@code onLossStarted}、关之后报 {@code onLossClosed}，判定交给调度；打开途中就出错的（设备从没交到我们手上），
     * 关完报 {@code onOpenFailed}。旧的报法（{@code onCameraError}）照旧发：给界面上的相机状态，也叫醒调度。</p>
     *
     * @param code      报给 MultiCameraManager 的错误码：被断开是 {@link #LOST_DISCONNECTED}，其余是 onError 的
     * @param errorName 记进 {@link #lastErrorName} 的短名
     * @param why       关设备那一行写的原因
     */
    private void loseDevice(CameraDevice camera, int code, String errorName, String why) {
        final boolean maybeTaken = code == LOST_DISCONNECTED
                || code == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
                || code == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE;
        final long lostAt = SystemClock.elapsedRealtime();
        // 关它的那一下就在这条相机线程上做（下面锁外那一下）
        final Closing done = new Closing(why, android.os.Looper.myLooper(), SystemClock.uptimeMillis());
        java.util.concurrent.CountDownLatch previous = null;
        SessionCounts.Snapshot lastCounts = null;
        boolean opened = false;
        boolean opening = false;
        boolean stale;
        synchronized (deviceLock) {
            // 过时不过时、收标记，和主线程的 closeCamera 在同一把锁里定（审查 2026-10-11）：回调进来时看过一次，
            // 那之后被关了的话，标记已经归关它的那一下（或者新的一轮），这里不能再清、不能再登记在关、不能再报
            stale = isStale();
            if (!stale) {
                // 打开还在途、来的又不是强制重开时我们自己摘下的旧设备：这是「开不起来」，不是「丢了」
                opening = (isOpening || reopenInFlight) && camera != retiringDevice;
                isOpening = false;
                reopenInFlight = false;
                opened = cameraDevice == camera;
                lastErrorName = errorName;
                deviceLost = true;   // 要不要再开、什么节奏：调度一处判（被别的程序拿走的，等它放开、通道安静了单独开）
                // 先登记再报：报出去之后调度看 isBusy() 就会等这一次关完。被断开、报 1 / 2 的那一次关
                // 相机服务那边已经不算我们的（holdsInService）；设备错误时我们的连接还在，关完之前照样算我们占着。
                // 也先登记、再把设备从字段上摘下：通道调度在主线程上不拿这把锁读「设备在不在、有没有在途」，反过来的话
                // 中间那一下读到「没设备、也没在关」，这一路被记成关着、当场又被开一次（审查 2026-10-11）
                if (maybeTaken) {
                    lostClose = done;
                }
                previous = beginClosing(done);
                markInFlight();
                if (opened) {
                    // 这一台最后那一份会话配好以来回了什么：关完时没出过画面就记一行
                    lastCounts = counts.snapshot();
                    // 会话跟着设备一起没了：排着的重建取消、在途的配置作废，没人再拿它当活的
                    cameraDevice = null;
                    captureSession = null;
                    voidSessionWork();
                    sessionGone();
                }
                if (maybeTaken && callback != null) {
                    callback.onCameraError(cameraId, code);
                }
            }
        }
        if (stale) {
            // 这一轮已经关了：设备就地关掉（锁外：它进相机服务），别的都归关它的那一下
            closeStale(camera, "stale " + why);
            return;
        }
        final CameraCallback channel = callback;
        if (opened && channel != null) {
            // 给调度：关之前就报，这几秒（车机拿走后座舱时 5–8 秒）里调度把它算成在途，别的路一步都不动
            channel.onLossStarted(cameraId, code);
        }
        // 锁外关：它进相机服务，可能卡住（见 closeDeviceTimed）
        closeDeviceTimed(camera, why);
        // 同一台相机上一次的关闭要是还没完，等它：「这一次关完」要蕴含「之前的都关完」
        finishClosing(done, previous);
        final long closeMs = SystemClock.elapsedRealtime() - lostAt;
        if (lostClose == done) {
            lostClose = null;
        }
        noteClosedWithoutFrames(lastCounts, why, closeMs);
        if (opened) {
            // 会话随设备没了：它带着的输出不再算在会话里，我们建的 Surface 谁都不用了就放掉
            forgetCarried();
            if (channel != null) {
                // 关的途中被 closeCamera 关了也照报：每一次 onLossStarted 都跟着恰好一次 onLossClosed
                channel.onLossClosed(cameraId, code, lostAt, closeMs);
            }
        } else if (opening && !isStale()) {
            // 打开途中出错：设备关完了才报（打开途中被调度关掉的不报 —— 那是关，不是开不起来）
            reportOpenFailed(CameraTaken.Reason.ofLoss(code));
        }
        if (isStale()) {
            return;   // closeCamera 已经把在途的标记清掉
        }
        if (!maybeTaken) {
            synchronized (deviceLock) {
                if (callback != null) {
                    callback.onCameraError(cameraId, code);
                }
            }
        }
    }

    /**
     * 上一轮晚到的设备就地关掉（这一轮已经被关了：回调来自摘下的那条相机线程），在那条旧线程上。也登记成在关
     * （{@link #beginClosing}，2.11.4 R1：每一种关都一样）—— 以前这一下不登记，调度看不见它，新的一轮可能同时去开同一台相机。
     * 关这一轮的那一下还排在这条线程上（回调排在它前面先跑）时，由它盖住：它和压在它上面的（别的线程的关）一直登记着、在这一下之后才关完，
     * 这一下不另登记、不等它（{@link #beginClosing}：看整条链，不只看最新的那一次）。
     */
    private void closeStale(CameraDevice camera, String why) {
        Closing closing = new Closing(why, android.os.Looper.myLooper(), SystemClock.uptimeMillis());
        java.util.concurrent.CountDownLatch previous = beginClosing(closing);
        try {
            closeDeviceTimed(camera, why);
        } finally {
            finishClosing(closing, previous);
        }
    }

    /** 黑匣子里的叫法：编号加环视 / 前座舱 / 后座舱，「1（后座舱）」。 */
    private String who() {
        return cameraPosition == null ? cameraId
                : cameraId + "（" + MultiCameraManager.roleName(cameraPosition) + "）";
    }

    /**
     * 创建预览会话（相机线程）。
     *
     * <p>会话带调度给的那一组输出（{@link #requested}，见 {@link #requestedTargets}）；建流、配会话、
     * 配不上怎么丢输出见 {@link #configureSession}。</p>
     */
    private void createCameraPreviewSession() {
        if (cameraDevice == null) {
            AppLog.e(TAG, "createCameraPreviewSession: cameraDevice is null for camera " + cameraId);
            return;
        }

        final int generation;
        synchronized (sessionLock) {
            if (isSessionClosing) {
                // 旧会话还在关：关完（onClosed，或到点的兜底）会再来建
                return;
            }
            if (isConfiguring) {
                // 一次配置在途：把它作废（回调来了会关掉那份会话、再按最新的输出建一次）
                sessionGeneration++;
                AppLog.d(TAG, "Camera " + cameraId + " configure in flight superseded, gen=" + sessionGeneration);
                return;
            }
            generation = ++sessionGeneration;
            isConfiguring = true;
        }
        markInFlight();

        List<Target> targets = Collections.emptyList();
        try {
            AppLog.d(TAG, "createCameraPreviewSession: Starting for camera " + cameraId + " gen=" + generation);

            // 【关键】旧会话还在就先关它，关完再建：HAL 不允许一个 Surface 同时绑到两条流
            if (captureSession != null) {
                final CameraCaptureSession oldSession = captureSession;
                captureSession = null;
                sessionGone();
                closeSessionForRebuild(oldSession, "rebuild");
                return;
            }

            targets = requestedTargets(requested.get());
            // 只有带着录像输出才用录像模板
            int template = carriesKind(targets, ChannelRules.Kind.RECORD)
                    ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;

            // 检查是否有可用的输出 Surface（后台初始化时可能全部为 null）
            if (targets.isEmpty()) {
                AppLog.d(TAG, "Camera " + cameraId + " no available surfaces, skipping session creation (waiting for surface)");
                synchronized (sessionLock) {
                    isConfiguring = false;
                }
                return;
            }

            configureSession(generation, targets, template);

        } catch (CameraAccessException e) {
            synchronized (sessionLock) { isConfiguring = false; isSessionClosing = false; }
            AppLog.e(TAG, "Failed to create preview session for camera " + cameraId, e);
            AppLog.e(TAG, "Exception details: " + e.getMessage());
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            synchronized (sessionLock) { isConfiguring = false; isSessionClosing = false; }
            // 特殊处理 "Surface was abandoned" 错误
            String message = e.getMessage();
            if (message != null && message.contains("abandoned") && dropAbandoned(targets)) {
                requestSessionRebuild("abandoned-surface", 100);
                return;
            }
            AppLog.e(TAG, "Unexpected IllegalArgumentException creating session for camera " + cameraId, e);
            e.printStackTrace();
        } catch (Exception e) {
            synchronized (sessionLock) { isConfiguring = false; isSessionClosing = false; }
            AppLog.e(TAG, "Unexpected exception creating session for camera " + cameraId, e);
            AppLog.e(TAG, "Exception details: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 调度要的那一组输出，换成相机要写的 Surface（相机线程）。
     *
     * <ul>
     *   <li>建会话前再查一次消费者死没死（{@code SurfaceTexture.isReleased()}）：死了的不进会话，从要的那一组里摘掉、报出来；</li>
     *   <li>撤回了声明、上一份会话里也没有的，跳过（调度下一轮看快照就知道）；</li>
     *   <li>一样都不剩：补出帧口 —— 和规则里「要出帧、会话里却没有一个活输出才加出帧口」是同一条，相机得有地方出帧。</li>
     * </ul>
     */
    private List<Target> requestedTargets(Set<ChannelRules.Out> wanted) {
        List<Target> targets = new ArrayList<>();
        List<ChannelRules.Out> dead = new ArrayList<>();
        for (ChannelRules.Kind kind : SESSION_ORDER) {
            for (ChannelRules.Out out : wanted) {
                if (out.kind != kind) {
                    continue;
                }
                if (kind == ChannelRules.Kind.SINK) {
                    Surface sink = frameSinkSurface();
                    if (sink != null) {
                        targets.add(new Target(out, sink));
                    }
                    continue;
                }
                if (!alive(out.consumer)) {
                    dead.add(out);
                    continue;
                }
                Surface surface = surfaceFor(out);
                if (surface == null && alive(out.consumer)) {
                    AppLog.d(TAG, "Camera " + cameraId + " output " + out + " withdrawn and not in the session, skipped");
                    continue;
                }
                if (surface == null || !surface.isValid()) {
                    dead.add(out);
                    continue;
                }
                targets.add(new Target(out, surface));
            }
        }
        for (ChannelRules.Out out : dead) {
            AppLog.w(TAG, "Camera " + cameraId + " output " + out + " was released before the session was built, dropped");
            dropOutput(out, Drop.RELEASED);
        }
        if (targets.isEmpty()) {
            Surface sink = frameSinkSurface();
            if (sink != null) {
                AppLog.i(TAG, "Camera " + cameraId + " 要的输出一样都不在了，补不显示的出帧口");
                targets.add(new Target(ChannelRules.Out.SINK, sink));
            }
        }
        if (carriesKind(targets, ChannelRules.Kind.PREVIEW)) {
            // 摆位跟着这一路的画面尺寸：开相机之前尺寸可能还不知道，建会话时再摆一次
            if (autoMirrorBack()) {
                applyMirrorTransform();
            }
            applyLaneTransform();
        }
        return targets;
    }

    /**
     * 这一样输出在相机里写进哪个 Surface（相机线程）。录像是录制器给的那个；预览、后视镜、盲区画面是我们建的，
     * 一块画布只建一个，声明着而还没建过就现在建。撤回了的只用上一份会话带着的那个（留着的输出），不为它新建。都不是返回 null。
     */
    private Surface surfaceFor(ChannelRules.Out out) {
        Declared declared;
        Surface existing;
        synchronized (outputLock) {
            declared = declaredFor(out);
            if (out.kind == ChannelRules.Kind.RECORD) {
                return declared != null ? declared.surface : carried.get(out);
            }
            existing = owned.get(out);
            if (existing == null && declared == null) {
                existing = carried.get(out);
            }
        }
        Size buffer = getPreviewBufferSize();
        Surface created;
        try {
            if (out.consumer instanceof SurfaceTexture && buffer != null) {
                // 每次建会话都拨一次：TextureView 尺寸一变就把它改成自己的布局尺寸，和 OutputConfiguration 对不上
                ((SurfaceTexture) out.consumer).setDefaultBufferSize(buffer.getWidth(), buffer.getHeight());
            }
            if (existing != null && existing.isValid()) {
                return existing;
            }
            if (declared == null || !(out.consumer instanceof SurfaceTexture)) {
                return existing;
            }
            SurfaceTexture texture = (SurfaceTexture) out.consumer;
            // 预览平时就是 new Surface(texture)；开发者选项开着时，环视这一路改写进 GPU 逐像素鱼眼校正的管线，
            // 由它再画到这个 TextureView 上（见 PreviewDewarp）
            created = out.kind == ChannelRules.Kind.PREVIEW
                    ? com.kooo.evcam.zeekr.PreviewDewarp.surfaceFor(cameraId, declared.view, texture, buffer)
                    : new Surface(texture);
        } catch (RuntimeException e) {
            // 查过还活着、建的这一下画布被收走了：当它没有，调用方再看一眼消费者死没死
            AppLog.w(TAG, "Camera " + cameraId + " could not make a surface for " + out + ": " + e);
            return null;
        }
        Surface stale;
        synchronized (outputLock) {
            stale = owned.put(out, created);
        }
        if (stale != null && stale != created) {
            releaseQuietly(stale);
        }
        AppLog.d(TAG, "Camera " + cameraId + " created " + out + " surface: " + created);
        return created;
    }

    /**
     * 按这一组输出建会话（相机线程）：预览、后视镜、盲区画面（中控屏、HUD）共用一条流（Surface Sharing，预览打头；挂不下时不带盲区画面），
     * 出帧口、录像各一条，常驻的拍照 JPEG 只进会话、不进预览请求。
     */
    private void configureSession(final int generation, final List<Target> targets, int template)
            throws CameraAccessException {
        AppLog.d(TAG, "Camera " + cameraId + " Creating capture request...");
        final CaptureRequest.Builder previewRequestBuilder = cameraDevice.createCaptureRequest(template);

        // 保存请求构建器引用（用于实时更新亮度/降噪参数）
        currentRequestBuilder = previewRequestBuilder;

        // 如果启用了亮度/降噪调节，应用配置中保存的参数
        if (imageAdjustEnabled) {
            applyImageAdjustParamsFromConfig(previewRequestBuilder);
        }

        applyTargetFpsRange(previewRequestBuilder);

        // 准备所有输出Surface
        List<Surface> surfaces = new ArrayList<>();
        List<OutputConfiguration> outputConfigs = new ArrayList<>();

        // 正常模式：使用 OutputConfiguration 实现 Surface Sharing (API 28+)
        // 将所有预览性质的 Surface（主预览、后视镜、盲区画面的中控屏和 HUD 两块）组合成一个硬件流；
        // 几块的缓冲区尺寸在这之前都拨成了相机的输出尺寸，OutputConfiguration 才对得上
        OutputConfiguration previewSharedConfig = null;
        int sharedCount = 0;
        final Map<ChannelRules.Out, Surface> built = new LinkedHashMap<>();
        for (Target target : targets) {
            ChannelRules.Kind kind = target.out.kind;
            boolean sideView = kind == ChannelRules.Kind.SIDE || kind == ChannelRules.Kind.HUD;
            if (kind == ChannelRules.Kind.PREVIEW || kind == ChannelRules.Kind.MIRROR || sideView) {
                if (previewSharedConfig == null) {
                    previewSharedConfig = new OutputConfiguration(target.surface);
                    previewSharedConfig.enableSurfaceSharing();
                    outputConfigs.add(previewSharedConfig);
                    noteSharedSurfaceLimit(previewSharedConfig.getMaxSharedSurfaceCount());
                } else if (sideView && sharedCount >= previewSharedConfig.getMaxSharedSurfaceCount()) {
                    // 共享预览流挂满了（框架给的上限）：盲区画面最可有可无，这一份会话不带它（HUD 那块排在最后，先轮到它），
                    // 告诉盲区画面那一侧（它撤回声明，不然调度会一直想把它加回来）
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId + " 共享预览流已挂 " + sharedCount
                            + " 块画布（上限 " + previewSharedConfig.getMaxSharedSurfaceCount() + "）：这一份会话不带盲区画面"
                            + (kind == ChannelRules.Kind.HUD ? "（HUD）" : "（中控屏）"));
                    dropOutput(target.out, Drop.CONFIG_FAILED);
                    continue;
                } else {
                    previewSharedConfig.addSurface(target.surface);
                }
                sharedCount++;
            } else {
                // 出帧口、录像各自一条独立的硬件流
                outputConfigs.add(new OutputConfiguration(target.surface));
            }
            surfaces.add(target.surface);
            previewRequestBuilder.addTarget(target.surface);
            built.put(target.out, target.surface);
            AppLog.d(TAG, "Camera " + cameraId + " output " + kind + " added: " + target.surface);
        }

        // 拍照通道：只加进会话，<b>不加进预览请求</b> ——
        // 每一帧都往 JPEG 编一遍是没有意义的开销，按下快门时才单发一次。
        if (jpegReader != null) {
            Surface jpegSurface = jpegReader.getSurface();
            if (jpegSurface != null && jpegSurface.isValid()) {
                outputConfigs.add(new OutputConfiguration(jpegSurface));
                surfaces.add(jpegSurface);
                AppLog.d(TAG, "Camera " + cameraId + " 拍照通道已挂入会话: " + jpegSize);
            }
        }

        if (outputConfigs.isEmpty()) {
            AppLog.w(TAG, "Camera " + cameraId + " No valid surfaces for session, skipping configuration");
            synchronized (sessionLock) {
                isConfiguring = false;
            }
            return;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Total physical streams (OutputConfigs): " + outputConfigs.size() +
                ", Total Surfaces: " + surfaces.size());

        // 诊断：列出所有 surfaces
        for (int i = 0; i < surfaces.size(); i++) {
            Surface s = surfaces.get(i);
            AppLog.d(TAG, "Camera " + cameraId + " Surface[" + i + "]: " + s + ", isValid=" + s.isValid());
        }

        // 配不上时按这份会话里有什么来丢
        final ChannelRules.Out sideOut = firstOfKind(built.keySet(), ChannelRules.Kind.SIDE);
        final ChannelRules.Out hudOut = firstOfKind(built.keySet(), ChannelRules.Kind.HUD);
        final ChannelRules.Out mirrorOut = firstOfKind(targets, ChannelRules.Kind.MIRROR);
        final ChannelRules.Out recordOut = firstOfKind(targets, ChannelRules.Kind.RECORD);

        // 注：旧会话关闭已提前到方法开头处理（确保 SurfaceTexture 断开连接后再创建 EGL Surface）

        // 创建会话 (使用 OutputConfiguration)
        AppLog.d(TAG, "Camera " + cameraId + " Creating capture session with " + outputConfigs.size() + " streams...");

        CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                if (isStale()) {
                    // 这一轮已经关了：会话配好得晚了一步。关掉，别当成新一轮的会话
                    closeSessionQuietly(session);
                    return;
                }
                boolean superseded;
                synchronized (sessionLock) {
                    superseded = generation != sessionGeneration;
                    isConfiguring = false;
                    isSessionClosing = false;
                }
                if (superseded) {
                    // 配置发出去之后又有人要重建：这份会话作废，关掉，关完按最新的输出再建
                    closeSessionForRebuild(session, "superseded gen=" + generation);
                    return;
                }
                AppLog.d(TAG, "Camera " + cameraId + " Session configured! gen=" + generation);
                configFailRetryCount = 0; // 成功，重置重试计数

                if (cameraDevice == null) {
                    AppLog.e(TAG, "Camera " + cameraId + " cameraDevice is null in onConfigured");
                    return;
                }

                if (captureSession != null && captureSession != session) {
                    AppLog.w(TAG, "Camera " + cameraId + " Session already replaced by newer session, ignoring this callback");
                    try { session.close(); } catch (Exception e) {}
                    return;
                }

                firstFrameAt = 0;
                captureSession = session;
                // 记下这份会话带着哪几样：给调度的快照，和「留着的输出」下一份会话照留时要用的 Surface
                carry(built);
                sessionSnapshot = new SessionOutputs(generation, built.keySet(), SystemClock.uptimeMillis());
                counts.reset(sessionSnapshot.since);   // 这一份会话配好以来回了什么，从这一刻数
                try {
                    frameCount = 0;
                    lastFrameLogTime = System.currentTimeMillis();

                    if (captureSession != session) return;
                    // 画面调节赶上在途时没发出去的参数，这里带上
                    overlayLatestAdjust(previewRequestBuilder);
                    captureSession.setRepeatingRequest(previewRequestBuilder.build(), activeCaptureCallback, backgroundHandler);
                    counts.repeating(SystemClock.uptimeMillis());
                    AppLog.d(TAG, "Camera " + cameraId + " preview started!");
                    lastFrameTimestampMs = System.currentTimeMillis();
                    lastProgressUptimeMs = SystemClock.uptimeMillis();
                    if (callback != null) callback.onCameraConfigured(cameraId);
                } catch (CameraAccessException e) {
                    AppLog.e(TAG, "Failed to start preview", e);
                } catch (IllegalStateException e) {
                    AppLog.w(TAG, "Session closed: " + e.getMessage());
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                if (isStale()) {
                    closeSessionQuietly(session);
                    return;
                }
                AppLog.e(TAG, "Failed to configure camera " + cameraId + " session!");
                // 关闭失败的 session，释放 Surface 绑定（否则重试会遇到 "Surface already has a stream"）
                try {
                    session.close();
                } catch (Exception ignored) {}
                boolean superseded;
                synchronized (sessionLock) {
                    superseded = generation != sessionGeneration;
                    isConfiguring = false;
                    isSessionClosing = false;
                }
                if (superseded) {
                    requestSessionRebuild("superseded-after-failure", REBUILD_DEBOUNCE_MS);
                    return;
                }

                // 重试逻辑：按 JPEG → 盲区画面 → 后视镜 → 录像的次序丢输出，一步里的重试都不往外报
                // 先丢拍照通道：它是这条会话里最可有可无的一条流，
                // 丢了照片退回抓预览，画面一帧不少；留着它却可能一帧都没有。
                if (jpegReader != null && !jpegDropped) {
                    jpegDropped = true;
                    closeJpegReader();
                    AppLog.w(TAG, "Camera " + cameraId
                            + " 会话配不上，先丢掉拍照通道再试（拍照将回退到抓预览）");
                    requestSessionRebuild("config-failed-drop-jpeg", 200);
                    return;
                }

                // 再丢盲区画面：录不录像都先丢它（预览、超级镜子、录像都比它要紧），中控屏、HUD 两块一起丢，
                // 告诉盲区画面那一侧撤回
                if (sideOut != null || hudOut != null) {
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId
                            + " 会话配置失败：先丢掉盲区画面再试"
                            + (sideOut != null && hudOut != null ? "（中控屏和 HUD）" : hudOut != null ? "（HUD）" : ""));
                    if (sideOut != null) {
                        dropOutput(sideOut, Drop.CONFIG_FAILED);
                    }
                    if (hudOut != null) {
                        dropOutput(hudOut, Drop.CONFIG_FAILED);
                    }
                    requestSessionRebuild("config-failed-drop-side", 200);
                    return;
                }

                if (recordOut != null) {
                    // 录制中：丢弃可选 Surface 后重试。丢了哪一样都报出去 —— 丢了录像输出，录像那一路当场退出，
                    // 不再悄悄饿 15 秒、然后被当成写不进整次停
                    if (mirrorOut != null) {
                        AppLog.w(TAG, "Retrying without main floating surface...");
                        com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId
                                + " 录像中会话配置失败：丢掉后视镜输出重试");
                        dropOutput(mirrorOut, Drop.CONFIG_FAILED);
                    } else {
                        AppLog.w(TAG, "Retrying without recording surface...");
                        com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + who()
                                + " 录像中会话配置失败：丢掉录像输出重试（已报给录像那一侧，这一路退出录像）");
                        dropOutput(recordOut, Drop.CONFIG_FAILED);
                    }
                    requestSessionRebuild("config-failed-recording", 500);
                } else {
                    configFailRetryCount++;
                    if (configFailRetryCount <= MAX_CONFIG_FAIL_RETRIES) {
                        // 可能是 Surface 正在从其他摄像头转移（connect: already connected），
                        // 短暂延迟后重试，等待旧 session 释放 Surface
                        AppLog.w(TAG, "Camera " + cameraId + " session config failed, retry " + configFailRetryCount + "/" + MAX_CONFIG_FAIL_RETRIES + " in 200ms...");
                        requestSessionRebuild("config-failed-retry", 200);
                    } else {
                        // 重试耗尽：不再重试，报一次。一步只报一次结果 —— 以前每试一次就报一次，
                        // 调度会在它还在重试时就判这一步完了
                        AppLog.e(TAG, "Camera " + cameraId + " config retries exhausted (" + configFailRetryCount + ")");
                        configFailRetryCount = 0;
                        if (callback != null) {
                            callback.onCameraError(cameraId, -3);
                        }
                    }
                }
            }
            @Override
            public void onClosed(@NonNull CameraCaptureSession session) {
                AppLog.d(TAG, "Camera " + cameraId + " Session CLOSED callback received gen=" + generation);
                if (isStale()) {
                    return; // 上一轮相机线程的会话，这一轮不管
                }
                boolean rebuild;
                synchronized (sessionLock) {
                    // 正在等的就是这份关完（closeCamera、重开会先把 closing 清掉）
                    rebuild = isSessionClosing;
                    isSessionClosing = false;
                }
                if (rebuild) {
                    // CLOSED 之后 HAL 还要一点时间放开 Surface 的绑定
                    requestSessionRebuild("closed", REBUILD_AFTER_CLOSE_MS);
                }
            }
        };

        // 使用 API 28 的 createCaptureSession (通过 OutputConfiguration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cameraDevice.createCaptureSessionByOutputConfigurations(outputConfigs, sessionCallback, backgroundHandler);
        } else {
            // 降级处理 (虽然 minSdk 是 28，但为了健壮性保留)
            cameraDevice.createCaptureSession(surfaces, sessionCallback, backgroundHandler);
        }
    }

    /**
     * 建会话碰上 "Surface was abandoned"（相机线程）：查出到底是哪几样的消费者死了（{@code SurfaceTexture.isReleased()}、
     * Surface 失效），只摘它们、报出来，在同一步里重试。以前是先猜后视镜、再猜录像 —— 猜错了就把好好的输出摘了。
     * 认不出是哪一个就不重试（重试也是同一个错），这一步到点由救援接手。
     *
     * @return true：摘了，该重试
     */
    private boolean dropAbandoned(List<Target> targets) {
        List<ChannelRules.Out> dead = new ArrayList<>();
        for (Target target : targets) {
            if (target.out.kind != ChannelRules.Kind.SINK
                    && (!alive(target.out.consumer) || !target.surface.isValid())) {
                dead.add(target.out);
            }
        }
        if (dead.isEmpty()) {
            AppLog.e(TAG, "Camera " + cameraId + " Surface abandoned, but every output still looks alive: not guessing, not retrying");
            return false;
        }
        AppLog.e(TAG, "Camera " + cameraId + " detected abandoned Surface " + dead + ", dropping only those and retrying");
        for (ChannelRules.Out out : dead) {
            dropOutput(out, Drop.RELEASED);
        }
        return true;
    }

    /**
     * 从这一路的会话里丢掉一样输出（相机线程）：从要的那一组里摘掉它（{@code updateAndGet}，
     * 主线程同时换了一组新的不会被盖回去），再报出去。
     */
    private void dropOutput(ChannelRules.Out out, Drop why) {
        requested.updateAndGet(set -> without(set, out));
        CameraCallback cb = callback;
        if (cb != null) {
            cb.onOutputDropped(cameraId, out, why);
        }
    }

    private static boolean carriesKind(List<Target> targets, ChannelRules.Kind kind) {
        return firstOfKind(targets, kind) != null;
    }

    private static ChannelRules.Out firstOfKind(List<Target> targets, ChannelRules.Kind kind) {
        for (Target target : targets) {
            if (target.out.kind == kind) {
                return target.out;
            }
        }
        return null;
    }

    private static ChannelRules.Out firstOfKind(java.util.Collection<ChannelRules.Out> outs, ChannelRules.Kind kind) {
        for (ChannelRules.Out out : outs) {
            if (out.kind == kind) {
                return out;
            }
        }
        return null;
    }

    /** 共享预览流最多能挂几块画布：变了才记一行（第一次建会话时、换了车机）。 */
    private void noteSharedSurfaceLimit(int limit) {
        if (sharedSurfaceLimitNoted != limit) {
            sharedSurfaceLimitNoted = limit;
            com.kooo.evcam.blackbox.BlackBox.note("相机 " + cameraId + "：共享预览流最多能挂 " + limit
                    + " 块画布（预览、超级镜子、盲区画面的中控屏和 HUD 共用）");
        }
    }

    /** 会话重建这一件事只有一个任务：去抖后在相机线程上跑 {@link #createCameraPreviewSession}。 */
    private final Runnable recreateSessionRunnable = () -> {
        synchronized (sessionLock) {
            rebuildQueued = false;
        }
        createCameraPreviewSession();
    };

    /**
     * 设备要没了（关相机 / 强制重开 / 掉线重连）：排着的重建取消，在途的配置作废，等着的关闭不再接着建。
     * 三条路都调这一个，别各自清一遍标志。
     */
    private void voidSessionWork() {
        Handler handler = backgroundHandler;
        if (handler != null) {
            handler.removeCallbacks(recreateSessionRunnable);
        }
        synchronized (sessionLock) {
            isConfiguring = false;
            isSessionClosing = false;
            rebuildQueued = false;
            sessionGeneration++;
        }
    }

    /**
     * 这一样输出此刻还挂在这台相机上：眼下的会话带着它，或者会话已经没了（关相机、丢了、重开、重建）而设备还没关完 ——
     * {@link #carried} 设备没了才清（{@link #forgetCarried}），关会话、关设备那几百毫秒到十几秒里相机还在往它写。
     * 画布要被系统收走时据此判断交给谁放（ChannelScheduler.sessionCarries）。哪个线程都能调。
     */
    public boolean stillCarries(ChannelRules.Out out) {
        if (out == null) {
            return false;
        }
        if (sessionSnapshot.carries(out)) {
            return true;
        }
        synchronized (outputLock) {
            return carried.containsKey(out);
        }
    }

    /** 会话没了（重建前关掉旧的、设备丢了、关相机、重开）：快照清空，「本会话第一帧」归零。 */
    private void sessionGone() {
        sessionSnapshot = SessionOutputs.NONE;
        firstFrameAt = 0;
    }

    /** 配好了一份会话（相机线程）：记下它带着的输出，再把谁都不用了的 Surface 放掉。 */
    private void carry(Map<ChannelRules.Out, Surface> built) {
        synchronized (outputLock) {
            carried.clear();
            carried.putAll(built);
        }
        tidySurfaces();
    }

    /** 设备没了（丢了、关了、重开）：会话带着的输出都不算了，谁都不用了的 Surface 放掉。 */
    private void forgetCarried() {
        synchronized (outputLock) {
            carried.clear();
        }
        tidySurfaces();
    }

    /** 声明变了：在这一路的相机线程上收一次（建会话也在那条线程上，收不到正在用的）；没有相机线程就当场收。 */
    private void tidyLater() {
        Handler handler = backgroundHandler;
        if (handler == null || !handler.post(this::tidySurfaces)) {
            tidySurfaces();
        }
    }

    /**
     * 我们建的 Surface（预览、后视镜、盲区画面），没声明着、会话里也没带着的放掉。配会话在途时不收（那一份会话可能正要用它），
     * 配好时（{@link #carry}）再收。录制器给的 Surface 归录制器，这里不放。
     */
    private void tidySurfaces() {
        synchronized (sessionLock) {
            if (isConfiguring || isSessionClosing) {
                return;
            }
        }
        List<Surface> unused = new ArrayList<>();
        synchronized (outputLock) {
            Iterator<Map.Entry<ChannelRules.Out, Surface>> it = owned.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<ChannelRules.Out, Surface> entry = it.next();
                if (declaredFor(entry.getKey()) == null && !carried.containsKey(entry.getKey())) {
                    unused.add(entry.getValue());
                    it.remove();
                }
            }
        }
        for (Surface surface : unused) {
            releaseQuietly(surface);
        }
        if (!unused.isEmpty()) {
            AppLog.d(TAG, "Camera " + cameraId + " released " + unused.size() + " unused output surface(s)");
        }
    }

    private static void releaseQuietly(Surface surface) {
        try {
            surface.release();
        } catch (Exception ignored) {
            // 已经放过了
        }
    }

    /**
     * 为了重建而关掉一份会话（旧的在用的、或建到一半被作废的）：关完（onClosed）接着建；
     * 回调迟迟不来就到点自己建。HAL 不允许一个 Surface 同时绑到两条流，所以关完才能建。
     */
    private void closeSessionForRebuild(CameraCaptureSession old, String why) {
        final int generation;
        synchronized (sessionLock) {
            isConfiguring = false;
            isSessionClosing = true;
            generation = sessionGeneration;
        }
        markInFlight();
        try {
            old.stopRepeating();
        } catch (Exception ignored) {
            // 没在跑就没得停
        }
        try {
            old.close();
            AppLog.d(TAG, "Camera " + cameraId + " closing session before rebuild (" + why + ")");
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " error closing old session: " + e.getMessage());
            synchronized (sessionLock) {
                isSessionClosing = false;
            }
            requestSessionRebuild("close-failed", REBUILD_AFTER_CLOSE_MS);
            return;
        }
        Handler handler = backgroundHandler;
        if (handler == null) {
            return;
        }
        handler.postDelayed(() -> {
            boolean stillClosing;
            synchronized (sessionLock) {
                stillClosing = isSessionClosing && generation == sessionGeneration;
                if (stillClosing) {
                    isSessionClosing = false;
                }
            }
            if (stillClosing) {
                AppLog.w(TAG, "Camera " + cameraId + " session close callback missing, rebuilding anyway");
                requestSessionRebuild("close-fallback", 0);
            }
        }, CLOSE_FALLBACK_MS);
    }

    /**
     * 相机层自己的重建请求都到这里（关完旧会话、配置失败重试、Surface 被弃、在途配置被作废；
     * 调度的改输出、重建会话在 {@link #reconfigure} 里当场建）。同一时刻只排一个；正在关旧会话时任务会自己等。
     */
    private void requestSessionRebuild(String why, long delayMs) {
        Handler handler = backgroundHandler;
        if (handler == null || cameraDevice == null) {
            return;
        }
        handler.removeCallbacks(recreateSessionRunnable);
        synchronized (sessionLock) {
            rebuildQueued = true;   // 先记、再排：任务跑起来时清
        }
        boolean posted = delayMs <= 0 ? handler.post(recreateSessionRunnable)
                : handler.postDelayed(recreateSessionRunnable, delayMs);
        if (!posted) {
            synchronized (sessionLock) {
                rebuildQueued = false;   // 线程在退了：这一轮已经关了，不会再建
            }
        }
        AppLog.d(TAG, "Camera " + cameraId + " session rebuild requested (" + why + ", " + delayMs + "ms)");
    }

    /**
     * 帧捕获回调（复用实例，供动态 Surface 更新时 setRepeatingRequest 使用）。开始、结果、失败、丢缓冲都记进眼下这份会话的那一份
     * （{@link #counts}；上一份会话晚到的不算）：没出画面时分得出是哪一种（2.11.4 第 1a 项）。
     */
    private final CameraCaptureSession.CaptureCallback activeCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                     @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            if (session == captureSession) {
                counts.started();
            }
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                      @NonNull CaptureRequest request,
                                      @NonNull TotalCaptureResult result) {
            if (session == captureSession) {
                counts.completed();
            }
            captureBeat.beat(StallWatch.now());
            long previousCapture = lastCaptureUptimeMs;
            lastProgressUptimeMs = SystemClock.uptimeMillis();
            lastCaptureUptimeMs = lastProgressUptimeMs;
            if (previousCapture != 0 && lastCaptureUptimeMs - previousCapture >= NOTABLE_GAP_MS) {
                noteFrameGap(lastCaptureUptimeMs, lastCaptureUptimeMs - previousCapture);
            }
            if (firstFrameAt == 0 && session == captureSession) {
                // 这一份会话的第一帧（上一份会话晚到的那几帧不算）：开、改输出、救那一步做完了
                firstFrameAt = lastCaptureUptimeMs;
                CameraCallback cb = callback;
                if (cb != null) {
                    cb.onFirstFrame(cameraId);
                }
            }
            frameCount++;
            long now = System.currentTimeMillis();
            lastFrameTimestampMs = now;
            if (!hasReadActualParams || frameCount == 1) {
                readActualParamsFromResult(result);
                hasReadActualParams = true;
            }
            fpsWindowFrameCount++;
            if (fpsWindowStartTime == 0) fpsWindowStartTime = now;
            long fpsElapsed = now - fpsWindowStartTime;
            if (fpsElapsed >= 1000) {
                currentFps = fpsWindowFrameCount * 1000f / fpsElapsed;
                fpsWindowFrameCount = 0;
                fpsWindowStartTime = now;
            }
            if (now - lastFrameLogTime >= FRAME_LOG_INTERVAL_MS) {
                long elapsed = now - lastFrameLogTime;
                float fps = frameCount * 1000f / elapsed;
                AppLog.d(TAG, "Camera " + cameraId + " FPS: " + String.format("%.1f", fps));
                frameCount = 0;
                lastFrameLogTime = now;
            }
        }

        // 下面两个只记账：一次请求失败、某一路输出没拿到这一帧。出问题时每帧都可能来一次，所以这里只计数 ——
        // 卡顿监测那一份日志由 StallWatch 限流；这一份会话的那一份（counts）只在 15 秒判定、关掉时各写一行
        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                    @NonNull CaptureRequest request,
                                    @NonNull android.hardware.camera2.CaptureFailure failure) {
            StallWatch.captureFailed(cameraId, failure.getReason());
            failuresTotal.incrementAndGet();
            if (session == captureSession) {
                counts.failed(SystemClock.uptimeMillis(), failure.getReason(), failure.getFrameNumber());
            }
        }

        @Override
        public void onCaptureBufferLost(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull Surface target, long frameNumber) {
            String which = describeTarget(target);
            StallWatch.bufferLost(cameraId, which);
            lostTotal.incrementAndGet();
            if (session == captureSession) {
                counts.lost(which);
            }
        }
    };

    /**
     * 建拍照用的 JPEG 输出。
     *
     * <p>尺寸取这一路声明的<b>最大</b>那个 —— 拍照是单张，没有帧率压力，
     * 没有理由拍得比相机能给的小。诊断报告里三路的 JPEG 尺寸列表和预览完全一致，
     * 实测每一路每个尺寸都能出图，耗时 120–200ms。</p>
     */
    private void prepareJpegReader(StreamConfigurationMap map) {
        closeJpegReader();
        if (jpegDropped) {
            // 这一次会话已经因为它配不上了，别再往回加
            AppLog.d(TAG, "Camera " + cameraId + " 拍照通道本次已被丢弃，不再重建");
            return;
        }
        if (!new AppConfig(context).isPhotoViaJpegEnabled()) {
            AppLog.d(TAG, "Camera " + cameraId + " 拍照仍走预览抓图（图片通道未开启）");
            return;
        }
        Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) {
            AppLog.w(TAG, "Camera " + cameraId + " 没有声明 JPEG 尺寸，拍照回退到预览抓图");
            return;
        }
        Size largest = sizes[0];
        for (Size size : sizes) {
            if ((long) size.getWidth() * size.getHeight()
                    > (long) largest.getWidth() * largest.getHeight()) {
                largest = size;
            }
        }
        // 配置里指定了拍照尺寸就用它；auto / max 时才是这一路的最大值。
        // 不读配置的话，配置编辑里那个「拍照分辨率」是个摆设。
        String role = com.kooo.evcam.profile.ProfileSizes.roleForCameraKey(cameraPosition);
        Size configured = role == null ? null
                : com.kooo.evcam.profile.ProfileSizes.photo(context, role, previewSize);
        jpegSize = configured != null ? configured : largest;
        // maxImages 2：一张在读、一张在路上就够了，多了只是占内存
        jpegReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(),
                android.graphics.ImageFormat.JPEG, 2);
        jpegReader.setOnImageAvailableListener(this::onJpegAvailable, backgroundHandler);
        AppLog.i(TAG, "Camera " + cameraId + " 拍照通道就绪: " + jpegSize
                + (configured != null ? "（配置指定）" : "（这一路的最大值）"));
    }

    private void onJpegAvailable(ImageReader reader) {
        JpegCallback callback = pendingJpeg;
        pendingJpeg = null;
        byte[] data = null;
        try (android.media.Image image = reader.acquireNextImage()) {
            if (image != null) {
                java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                data = new byte[buffer.remaining()];
                buffer.get(data);
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Camera " + cameraId + " 读取 JPEG 失败: " + e);
        }
        if (callback == null) {
            return;   // 没人等这张图（超时之后才到），丢掉
        }
        if (data == null) {
            callback.onFailed("没有拿到图像数据");
        } else {
            callback.onJpeg(data);
        }
    }

    /**
     * 发一次静态拍照请求。
     *
     * @return 发出去了返回 true；通道没开或会话不在时返回 false，调用方该回退
     */
    private boolean requestJpeg(JpegCallback callback) {
        ImageReader reader = jpegReader;
        CameraCaptureSession currentSession = captureSession;
        CameraDevice device = cameraDevice;
        Handler handler = backgroundHandler;
        if (reader == null || currentSession == null || device == null || handler == null) {
            return false;
        }
        pendingJpeg = callback;
        // 建请求、下发都是进相机服务的调用：放到这一路的相机线程上（以前在主线程，相机服务一卡主线程跟着卡）
        handler.post(() -> {
            if (stepInFlight() || captureSession != currentSession) {
                // 调度的一步在途、或者会话已经换了：不往它上面发（R1，请求级的从不和调度的一步叠在一起），改抓预览
                pendingJpeg = null;
                callback.onFailed("a channel step is in flight");
                return;
            }
            try {
                CaptureRequest.Builder builder = device.createCaptureRequest(
                        CameraDevice.TEMPLATE_STILL_CAPTURE);
                builder.addTarget(reader.getSurface());
                builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                currentSession.capture(builder.build(), null, handler);
            } catch (Exception e) {
                pendingJpeg = null;
                AppLog.w(TAG, "Camera " + cameraId + " 拍照请求失败: " + e);
                callback.onFailed("capture: " + e.getMessage());
            }
        });
        return true;
    }

    private void closeJpegReader() {
        if (jpegReader != null) {
            jpegReader.close();
            jpegReader = null;
        }
        jpegSize = null;
        pendingJpeg = null;
    }

    /** 出帧口的 Surface；还没建就按预览尺寸建一个（相机线程上调）。建不出来返回 null。 */
    private Surface frameSinkSurface() {
        if (frameSink == null) {
            Size size = previewSize;
            Handler handler = backgroundHandler;
            if (size == null || handler == null) {
                return null;
            }
            try {
                frameSink = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                        ImageFormat.PRIVATE, 2);
                frameSink.setOnImageAvailableListener(reader -> {
                    // 帧只是为了让相机转起来：拿到就还回去
                    try {
                        Image image = reader.acquireLatestImage();
                        if (image != null) {
                            image.close();
                        }
                    } catch (Exception e) {
                        AppLog.d(TAG, "Camera " + cameraId + " frame sink: " + e.getMessage());
                    }
                }, handler);
            } catch (Exception e) {
                AppLog.w(TAG, "Camera " + cameraId + " 建不出不显示的出帧口: " + e);
                frameSink = null;
                return null;
            }
        }
        Surface surface = frameSink.getSurface();
        return surface != null && surface.isValid() ? surface : null;
    }

    private void closeFrameSink() {
        if (frameSink != null) {
            frameSink.close();
            frameSink = null;
        }
    }

    /**
     * 拍照。
     *
     * <h3>为什么时间戳由调用方给</h3>
     *
     * <p>多路拍的是同一个瞬间，回看是按文件名里的时间戳分组的 —— 各自取各自的
     * 时间，跨过一秒就会被拆成两组。</p>
     *
     * <h3>结果</h3>
     *
     * <p>存下了还是没拍成，都经 {@code done} 回报一次 —— 界面按它说话，而不是按了就说「已保存」。</p>
     *
     * @param timestamp 文件命名用的时间戳，由调用方统一生成
     * @param done      这一路这一张的结果
     */
    public void takePicture(String timestamp, PhotoDone done) {
        if (previewSize == null) {
            AppLog.e(TAG, "Camera " + cameraId + " preview size not available");
            done.onDone(false);
            return;
        }

        // 图片通道优先：那是相机自己的 JPEG 输出，分辨率是这一路的最大值，
        // 和预览缓冲区无关，主界面在不在前台都能拍。发不出去（通道没开、会话不在）就回退抓预览。
        if (requestJpeg(new JpegCallback() {
            @Override
            public void onJpeg(byte[] data) {
                saveJpeg(data, timestamp, done);
            }

            @Override
            public void onFailed(String reason) {
                AppLog.w(TAG, "Camera " + cameraId + " 图片通道没出图（" + reason + "），改抓预览");
                grabPreview(timestamp, done);
            }
        })) {
            return;
        }
        grabPreview(timestamp, done);
    }

    /**
     * 把相机出的 JPEG 存下来。
     *
     * <p>要盖角标，所以得先解码成 Bitmap 再重新编码 —— 相机直出的那份字节
     * 里没有我们的应用名、车牌和时间。EXIF 由 {@code saveBitmapAsJPEG} 之后
     * 单独补写，重新编码会把相机写的标签丢掉。</p>
     */
    private void saveJpeg(byte[] data, String timestamp, PhotoDone done) {
        Handler handler = backgroundHandler;
        if (handler == null) {
            done.onDone(false);
            return;
        }
        handler.post(() -> {
            android.graphics.Bitmap bitmap = null;
            try {
                bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) {
                    AppLog.e(TAG, "Camera " + cameraId + " JPEG 解不开，改抓预览");
                    grabPreview(timestamp, done);
                    return;
                }
                AppLog.d(TAG, "Camera " + cameraId + " 图片通道拍到 "
                        + bitmap.getWidth() + "x" + bitmap.getHeight());
                done.onDone(saveBitmapAsJPEG(bitmap, timestamp));
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " 保存 JPEG 失败", e);
                done.onDone(false);
            } finally {
                if (bitmap != null) {
                    bitmap.recycle();
                }
            }
        });
    }

    /**
     * 老路子：从主界面的预览上抓一张。分辨率受预览缓冲区限制。
     * 主界面不在前台就没有预览可抓（画面已被系统收回），这一路算没拍成。
     */
    private void grabPreview(String timestamp, PhotoDone done) {
        Handler handler = backgroundHandler;
        TextureView view = textureView;
        Size size = previewSize;
        if (handler == null || view == null || !view.isAvailable() || size == null) {
            AppLog.w(TAG, "Camera " + cameraId + " 没有预览画面可抓，这一路没拍成");
            done.onDone(false);
            return;
        }
        handler.post(() -> {
            try {
                android.graphics.Bitmap bitmap = view.getBitmap(size.getWidth(), size.getHeight());
                if (bitmap == null) {
                    AppLog.e(TAG, "Camera " + cameraId + " failed to get bitmap from TextureView");
                    done.onDone(false);
                    return;
                }
                bitmap = toNormalView(bitmap);
                AppLog.d(TAG, "Camera " + cameraId + " picture captured ("
                        + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                boolean saved = saveBitmapAsJPEG(bitmap, timestamp);
                bitmap.recycle();
                done.onDone(saved);
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " error capturing picture", e);
                done.onDone(false);
            }
        });
    }

    /**
     * 将Bitmap保存为JPEG文件（使用指定的时间戳）
     *
     * @return 文件写完了（EXIF 写不进不算失败，见 {@link #writeExif}）
     */
    private boolean saveBitmapAsJPEG(android.graphics.Bitmap bitmap, String timestamp) {
        boolean saved = false;
        File photoDir = StorageHelper.getPhotoDir(context);
        if (photoDir == null) {
            // 没有地方可存（没有 U 盘、开发者选项没开）：拍照入口已经拦过，走到这里是拍的途中盘没了
            AppLog.w(TAG, "Camera " + cameraId + " 没有地方存照片（没有 U 盘），这一路没存");
            return false;
        }
        if (!photoDir.exists()) {
            photoDir.mkdirs();
        }

        // 检查存储空间是否充足（至少需要 5MB）
        long availableSpace = StorageHelper.getAvailableSpace(photoDir);
        if (availableSpace >= 0 && availableSpace < 5 * 1024 * 1024) {
            AppLog.w(TAG, "Camera " + cameraId + " 存储空间不足，剩余: " + StorageHelper.formatSize(availableSpace));
            // 仍然尝试保存，因为照片通常只有几百KB
        }

        // 使用传入的时间戳命名：yyyyMMdd_HHmmss_摄像头位置.jpg
        String position = (cameraPosition != null) ? cameraPosition : cameraId;
        File photoFile = new File(photoDir,
                timestamp + "_" + CameraSlots.suffixFor(position) + ".jpg");

        AppConfig appConfig = new AppConfig(context);

        // 工程模式：先把没动过的这一张留一份，重排和角标都在这之后
        if (appConfig.isRawFrameDumpEnabled()) {
            com.kooo.evcam.zeekr.RawFrameDump.save(
                    context, bitmap, photoDir, timestamp, cameraId, position);
        }

        // 四宫格：拍照拿到的是整张合成图（四个画面竖向一字排开），
        // 与录制保持一致地重排成 2x2 再存盘。用的是同一套拆分几何。
        android.graphics.Bitmap sourceBitmap = bitmap;
        android.graphics.Bitmap gridBitmap = null;
        // 照片跟着这一路录制的排列走：录像是 2×2，照片就该是 2×2
        if (com.kooo.evcam.profile.RecordSpecs.storedAsGrid(context, cameraPosition)) {
            gridBitmap = com.kooo.evcam.zeekr.CompositeBitmapComposer.toGrid(
                    cameraId, bitmap, null);
            if (gridBitmap != bitmap) {
                sourceBitmap = gridBitmap;
            } else {
                gridBitmap = null;  // 未发生重排，没有额外的 bitmap 需要回收
            }
        }

        // 盖角标：左上角应用名（填了车牌号跟在后面）每一张都有；时间和尺寸那两行跟着「时间水印」开关
        android.graphics.Bitmap finalBitmap =
                addWatermark(sourceBitmap, timestamp, appConfig.isTimestampWatermarkEnabled());

        FileOutputStream output = null;
        try {
            output = new FileOutputStream(photoFile);
            finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output);
            output.flush();
            output.close();
            output = null;
            saved = true;
            writeExif(photoFile, timestamp, finalBitmap.getWidth(), finalBitmap.getHeight());
            AppLog.i(TAG, "Photo saved: " + photoFile.getAbsolutePath());
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("ENOSPC")) {
                AppLog.e(TAG, "Camera " + cameraId + " 保存照片失败：存储空间已满");
            } else {
                AppLog.e(TAG, "Failed to save photo", e);
            }
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    // 关闭流时的 ENOSPC 错误通常表示文件已保存，但空间紧张
                    // 降低日志级别，避免误导用户以为保存失败
                    if (e.getMessage() != null && e.getMessage().contains("ENOSPC")) {
                        AppLog.w(TAG, "Camera " + cameraId + " 存储空间已满，请清理存储");
                    } else {
                        AppLog.e(TAG, "Failed to close output stream", e);
                    }
                }
            }
            // 如果创建了新的bitmap用于水印，需要回收
            if (finalBitmap != bitmap && finalBitmap != gridBitmap && finalBitmap != null) {
                finalBitmap.recycle();
            }
            // 四宫格重排产生的中间 bitmap 也要回收（原始 bitmap 由调用方负责）
            if (gridBitmap != null && !gridBitmap.isRecycled()) {
                gridBitmap.recycle();
            }
        }
        return saved;
    }

    /**
     * 把拍摄时间和相机信息写进 EXIF。
     *
     * <h3>为什么要自己写</h3>
     *
     * <p>照片要盖角标，所以拿到相机直出的 JPEG 之后必须解码成 Bitmap 再重新编码 ——
     * 这一来一回，相机原本写在文件里的 EXIF 全部丢掉了。抓预览那条路更彻底：
     * 那是一张屏幕截图，本来就没有任何标签。</p>
     *
     * <p>所以时间、机型、尺寸、是哪一路拍的，都在这里补回去。写不进去只记一条日志 ——
     * 照片本身已经存好了，不该因为标签失败就当成保存失败。</p>
     */
    private void writeExif(File file, String timestamp, int width, int height) {
        try {
            android.media.ExifInterface exif =
                    new android.media.ExifInterface(file.getAbsolutePath());

            // 文件名里的 yyyyMMdd_HHmmss 转成 EXIF 要的 yyyy:MM:dd HH:mm:ss
            String when;
            try {
                java.util.Date date = new SimpleDateFormat("yyyyMMdd_HHmmss",
                        Locale.getDefault()).parse(timestamp);
                when = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(date);
            } catch (Exception e) {
                when = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                        .format(new java.util.Date());
            }
            exif.setAttribute(android.media.ExifInterface.TAG_DATETIME, when);
            exif.setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, when);
            exif.setAttribute(android.media.ExifInterface.TAG_DATETIME_DIGITIZED, when);

            exif.setAttribute(android.media.ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER);
            exif.setAttribute(android.media.ExifInterface.TAG_MODEL, android.os.Build.MODEL);
            exif.setAttribute(android.media.ExifInterface.TAG_IMAGE_WIDTH, String.valueOf(width));
            exif.setAttribute(android.media.ExifInterface.TAG_IMAGE_LENGTH, String.valueOf(height));

            String version = "";
            try {
                version = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception ignored) {
                // 版本号取不到就不写，不影响其他标签
            }
            exif.setAttribute(android.media.ExifInterface.TAG_SOFTWARE,
                    context.getString(com.kooo.evcam.R.string.app_name)
                            + (version.isEmpty() ? "" : " " + version));
            // 哪一路拍的、相机 id 是多少 —— 回头对照日志时这两样最有用
            exif.setAttribute(android.media.ExifInterface.TAG_USER_COMMENT,
                    "camera=" + cameraId + " position=" + cameraPosition);
            exif.saveAttributes();
        } catch (Exception e) {
            AppLog.w(TAG, "Camera " + cameraId + " 写 EXIF 失败（照片已保存）: " + e);
        }
    }

    /**
     * 给照片盖角标。
     *
     * <h3>和录像盖的是同一套信息</h3>
     *
     * <p>以前照片只有一行时间，而录像有应用名、版本、车牌、尺寸 —— 同一台设备
     * 记录的两种东西，角标写的内容却对不上。现在两边共用
     * {@link WatermarkText} 拼字符串：</p>
     *
     * <pre>
     *   极氪即刻 v0.36.2  京A12345     &lt;- 每一张都有；车牌号可选
     *   2026-09-03 14:22:07            &lt;- 这一行和下一行跟着「时间水印」开关
     *   2560x2560                      &lt;- 这张图真实的尺寸
     * </pre>
     *
     * <p>照片没有帧率、码率、编码，那几项就不写 —— 为了「看起来一致」
     * 硬凑几个数，比不写更糟。</p>
     *
     * <p>盖不上（复制整张图时内存不够之类）就存原图：没有角标的照片也比丢掉这张强。</p>
     *
     * @param timestamp 时间戳字符串（格式：yyyyMMdd_HHmmss）
     * @param withTime 「时间水印」开着：画时间和尺寸两行
     */
    private android.graphics.Bitmap addWatermark(
            android.graphics.Bitmap originalBitmap, String timestamp, boolean withTime) {
        android.graphics.Bitmap mutableBitmap = null;
        try {
            java.util.List<String> lines = WatermarkText.photoLines(
                    buildPhotoBrandLine(), readableTime(timestamp),
                    originalBitmap.getWidth(), originalBitmap.getHeight(), withTime);
            if (lines.isEmpty()) {
                return originalBitmap;  // 一行都没有，不必复制
            }

            mutableBitmap = originalBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            android.graphics.Canvas canvas = new android.graphics.Canvas(mutableBitmap);

            // 字号跟着图片宽度走：四宫格 2560 和单路 1280 差一倍，
            // 固定字号在其中一边一定不合适
            float textSize = mutableBitmap.getWidth() * 0.03f;
            textSize = Math.max(16f, Math.min(48f, textSize));

            android.graphics.Paint shadowPaint = new android.graphics.Paint();
            shadowPaint.setColor(android.graphics.Color.BLACK);
            shadowPaint.setTextSize(textSize);
            shadowPaint.setAntiAlias(true);
            shadowPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

            android.graphics.Paint textPaint = new android.graphics.Paint();
            textPaint.setColor(android.graphics.Color.WHITE);
            textPaint.setTextSize(textSize);
            textPaint.setAntiAlias(true);
            textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

            // 空行 photoLines 已经去掉了，这里每一行都画
            float x = textSize * 0.5f;
            float y = textSize * 1.2f;
            for (String line : lines) {
                canvas.drawText(line, x + 2, y + 2, shadowPaint);
                canvas.drawText(line, x, y, textPaint);
                y += textSize * 1.25f;
            }

            AppLog.d(TAG, "Camera " + cameraId + " 照片角标: " + lines);
            return mutableBitmap;

        } catch (Exception | OutOfMemoryError e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to add watermark", e);
            // 复制出来但没画完的那张不要了
            if (mutableBitmap != null && mutableBitmap != originalBitmap) {
                mutableBitmap.recycle();
            }
            return originalBitmap;  // 失败时返回原图
        }
    }

    /** 和录像左上角那一行完全一样的拼法。 */
    private String buildPhotoBrandLine() {
        String version = "";
        try {
            version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            AppLog.w(TAG, "读取版本号失败: " + e);
        }
        return WatermarkText.brandLine(
                context.getString(com.kooo.evcam.R.string.app_name),
                version, new AppConfig(context).getLicensePlate());
    }

    /** yyyyMMdd_HHmmss -> yyyy-MM-dd HH:mm:ss；解析不了就用当前时间。 */
    private static String readableTime(String timestamp) {
        try {
            java.text.SimpleDateFormat in =
                    new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            java.text.SimpleDateFormat out =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            return out.format(in.parse(timestamp));
        } catch (Exception e) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()).format(new java.util.Date());
        }
    }

    /**
     * 关闭摄像头：调用方不等。
     *
     * <p>关相机是一次进相机服务的调用，实测卡过 13.6 秒（2026-09-26，主线程）。以前它在调用方的
     * 线程上做 —— 多半就是主线程 —— 而且拿着 {@link #deviceLock}：主线程一卡，主界面、
     * 超级镜子、悬浮按钮全都跟着不动，分不清是界面的问题还是相机的问题。</p>
     *
     * <p>现在调用方这边只做不进相机服务的事：改标志、摘掉待办、把要关的会话和设备从字段上摘下来。
     * 真正关的那几步交给<b>这一路自己的相机线程</b>（卡顿监测里叫 {@code Camera-<id> closing}），
     * 做完这一轮的相机线程也就退了。相机服务再卡，卡的是那条线程，主线程照常。
     * 紧接着的「再打开」会先等这一次关完，见 {@link #CLOSING}。</p>
     *
     * @param why 为什么关（英文短语，调度给的）。关完时往黑匣子记一行，带用时
     */
    public void closeCamera(String why) {
        final CameraCaptureSession session;
        final CameraDevice device;
        final HandlerThread thread;
        final Handler handler;
        final boolean openInFlight;
        synchronized (deviceLock) {
            openInFlight = isOpening && cameraDevice == null;
            isOpening = false;
            // 这一趟的标记都归零：路上的强制重开、设备报错的记号
            reopenInFlight = false;
            deviceLost = false;
            voidSessionWork();

            // 要关的从字段上摘下来，交给相机线程去关
            session = captureSession;
            captureSession = null;
            device = cameraDevice;
            cameraDevice = null;
            // 只清会话和设备，不清声明（预览、后视镜、盲区画面、录像的声明留着，下一次开照样带上）
            requested.set(Collections.emptySet());
            sessionGone();

            // 这一轮的相机线程也摘下来：之后来自它的回调就是过时的（见 isStale）
            thread = backgroundThread;
            handler = backgroundHandler;
            backgroundThread = null;
            backgroundHandler = null;
        }

        if (handler == null) {
            // 没有相机线程：这一路眼下没开着，也就没有设备、会话要关，就地收拾完
            closeJpegReader();
            closeFrameSink();
            forgetCarried();
            AppLog.d(TAG, "Camera " + cameraId + " closed (was not open)");
            if (callback != null) {
                callback.onCameraClosed(cameraId);
            }
            return;
        }

        final Closing done = new Closing(why, handler.getLooper(), SystemClock.uptimeMillis());
        final java.util.concurrent.CountDownLatch previous = beginClosing(done);
        markInFlight();
        // 这一台最后那一份会话配好以来回了什么（设备在的话）：关完时没出过画面就记一行
        final SessionCounts.Snapshot lastCounts = device == null ? null : counts.snapshot();
        final String watchName = "Camera-" + cameraId + " closing";
        StallWatch.unwatchLooper("Camera-" + cameraId);
        StallWatch.watchLooper(watchName, handler);
        final long requestedAt = SystemClock.elapsedRealtime();
        Runnable job = () -> {
            closeSessionQuietly(session);
            closeDeviceTimed(device, why);
            // 拍照通道、出帧口也要放，否则下次建会话会多一条悬着的流
            closeJpegReader();
            closeFrameSink();
            // 同一台相机上一次的关闭要是还没返回，等它（不设时限，项目所有者 2026-10-28）：「这一次关完」要蕴含「之前的都关完」
            awaitClosed(previous);
            // 会话随相机关了：它带着的输出不再算在会话里，我们建的 Surface 没声明着的放掉（下一轮开要等这一次关完，不会撞上）
            forgetCarried();
            finishClosing(done, null);
            long ms = SystemClock.elapsedRealtime() - requestedAt;
            AppLog.d(TAG, "Camera " + cameraId + " closed in " + ms + "ms");
            com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + who() + " 已关（"
                    + why + "，" + ms + "ms" + com.kooo.evcam.blackbox.BlackBox.afterScreenOff() + "）");
            noteClosedWithoutFrames(lastCounts, why, ms);
            lastProgressUptimeMs = SystemClock.uptimeMillis();
            StallWatch.unwatchLooper(watchName);
            if (callback != null) {
                callback.onCameraClosed(cameraId);
            }
            if (openInFlight) {
                // 打开还在途：它的 onOpened 可能晚到。线程多留一会儿，晚到的设备在回调里就地关掉
                handler.postDelayed(thread::quitSafely, LATE_OPEN_GRACE_MS);
            } else {
                thread.quitSafely();
            }
        };
        if (!handler.post(job)) {
            // 线程已经在退了（不该发生）：另起一条线程做，至少设备会被关掉。不在调用方这里做：调用方多半是主线程，
            // 这一下要进相机服务，还要等之前没返回的关（不设时限）
            new Thread(job, watchName).start();
        }
    }

    /**
     * 回调来自已经关掉的那一轮：它的相机线程已经从字段上摘下来了。
     *
     * <p>相机的回调都在这一轮的相机线程上跑。关相机时这条线程被摘下（新一轮会起一条新的），
     * 所以「当前线程不是现在这一轮的相机线程」就说明这是上一轮晚到的回调。</p>
     */
    private boolean isStale() {
        Handler current = backgroundHandler;
        return current == null || current.getLooper() != android.os.Looper.myLooper();
    }

    private void closeSessionQuietly(CameraCaptureSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception e) {
            // 忽略：车机 HAL 关会话时可能抛出
            AppLog.d(TAG, "Camera " + cameraId + " ignored exception while closing session: " + e.getMessage());
        }
    }

    /** 等一个 latch，最多等 {@code timeoutMs}；latch 为 null 当作已经好了。只给退出时那一下用（{@link #awaitAllClosed}）。 */
    private static boolean awaitQuietly(java.util.concurrent.CountDownLatch latch, long timeoutMs) {
        if (latch == null) {
            return true;
        }
        try {
            return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 等一次关返回（{@link Closing}；null 当作已经返回了）：不设时限 —— 关没返回之前这一台谁都不开（项目所有者 2026-10-28）。
     * 只在相机线程上等（开、重开之前 {@link #waitForNoClose}；关完时等之前那一次 {@link #finishClosing}、关相机那一下），从不在主线程上。
     * 被打断照等，等完把打断的记号补回去。
     */
    private static void awaitClosed(java.util.concurrent.CountDownLatch latch) {
        if (latch == null) {
            return;
        }
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 等所有正在关的相机关完，最多等 {@code timeoutMs}。退出应用时用。
     *
     * @return 到点还没关完的相机 id；都关完了返回空列表
     */
    public static java.util.List<String> awaitAllClosed(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        java.util.List<String> stuck = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Closing> entry : CLOSING.all().entrySet()) {
            long left = Math.max(0L, deadline - SystemClock.elapsedRealtime());
            if (!awaitQuietly(entry.getValue(), left)) {
                stuck.add(entry.getKey());
            }
        }
        return stuck;
    }


    /**
     * 检查摄像头是否已连接
     */
    public boolean isConnected() {
        return cameraDevice != null;
    }

    /**
     * 重开的实际步骤（{@link #reopen}）：即使这一路此刻开着也关了再开，会话带着 {@code wanted}。
     *
     * <p>关旧的、开新的都在这一路的相机线程上按顺序做，主线程只是把活派过去 ——
     * 以前旧的是在调用方线程上、拿着 {@link #deviceLock} 关的。</p>
     *
     * @return false：合并掉了，什么都没做
     */
    private boolean reopenWith(Set<ChannelRules.Out> wanted) {
        if (openOrCloseInFlight()) {
            // 开 / 重开还在途、或者这一台有一次关没返回（任何一份实例的，不封顶）：那一次自己会有结果（开成、报错、关返回）。
            // 这时再开一次，就是同一台相机两个句柄，相机服务会踢掉先开的那份（2026-10-08，自己顶自己 7 次）
            AppLog.d(TAG, "Camera " + cameraId + " busy (" + describeBusy() + "), reopen coalesced");
            return false;
        }
        if (isOpening || reopenInFlight) {
            // 上一次开、重开在途已经超过封顶：回调当它不会来了 —— 那一轮作废，不接着用它的相机线程
            return retireStaleOpen(wanted);
        }
        // 配会话、关旧会话还挂着不挡：下面连设备一起作废、关掉（调度只在那一步到点之后才来救）
        reopenInFlight = true;
        markInFlight();
        final CameraCaptureSession oldSession;
        final CameraDevice oldDevice;
        final Handler handler;
        synchronized (deviceLock) {
            AppLog.d(TAG, "Camera " + cameraId + " reopen requested (channel) outputs " + wanted);
            // 重置状态。isOpening / isConfiguring / isSessionClosing 这三个也要清 ——
            // 它们只在相机回调里复位，而回调不来正是这条路被走到的原因。
            // 不清的话：isOpening 会挡掉之后每一次打开，
            // isConfiguring 会让之后每一次建会话都只作废、不去建。设备和会话下面就关掉了，
            // 在途的那一次配置已经作废，清掉不会和谁打架。
            isOpening = false;
            voidSessionWork();
            requested.set(wanted);

            oldSession = captureSession;
            captureSession = null;
            oldDevice = cameraDevice;
            cameraDevice = null;
            // 摘下来的旧设备在关的途中报错，不算这一次重开「开不起来」
            retiringDevice = oldDevice;
            sessionGone();
            handler = backgroundHandler;
        }

        if (handler == null) {
            // 这一路眼下没有相机线程（没开着）：走正常的打开（在开的上面已经按在途合并掉了）
            reopenInFlight = false;
            return beginOpen(wanted);
        }
        // 关旧设备和别的关一样登记成在关（1c，项目所有者 2026-10-14 批准）：调度认它在途，救的那一步 15 秒到点、
        // 旧设备还在关（7 次里最长 14.0 秒，10-13 19:55:29），别的路也不动；下面那一下开排在它后面，等它（和之前的关）返回，
        // 不设时限（项目所有者 2026-10-28）
        final Closing closing = oldDevice == null ? null
                : new Closing("reopen", handler.getLooper(), SystemClock.uptimeMillis());
        final java.util.concurrent.CountDownLatch previous = closing == null ? null : beginClosing(closing);
        final SessionCounts.Snapshot lastCounts = oldDevice == null ? null : counts.snapshot();
        Runnable closeOld = () -> {
            long start = SystemClock.elapsedRealtime();
            closeSessionQuietly(oldSession);
            closeDeviceTimed(oldDevice, "reopen");
            if (retiringDevice == oldDevice) {
                retiringDevice = null;
            }
            // 旧会话随旧设备关了：它带着的输出不再算在会话里
            forgetCarried();
            if (closing != null) {
                // 同一台相机之前的关返回了（不设时限），这一次才算关完
                awaitClosed(previous);
                if (handler == backgroundHandler) {
                    // 关返回了，接着那一下开还没上路（300 ms 后，或者这一下关了很久、它排在后面马上跑）：摘掉在关的登记之前
                    // 记一下有动作上路 —— 这一下可能关了一分多钟，不记的话重开在途早过了封顶、在关的登记又已经摘了，
                    // 那一下里调度的开、救会把这一轮当成开了 60 秒没回音作废（审查 2026-10-28）
                    markInFlight();
                }
                finishClosing(closing, null);
                noteClosedWithoutFrames(lastCounts, "reopen", SystemClock.elapsedRealtime() - start);
            }
        };
        if (!handler.post(closeOld)) {
            // 线程已经在退了（不该发生）：和关相机那一下一样另起一条线程关，登记了的这一次关不会没人收（主线程上不做：要进相机服务，
            // 还要等之前没返回的关）
            new Thread(closeOld, "Camera-" + cameraId + " closing").start();
        }
        // 延迟300ms，给系统时间释放资源
        handler.postDelayed(() -> reopenOnCameraThread(handler), 300);
        return true;
    }

    /**
     * 强制重开的那一下打开：先确认这一轮还没被关掉、这台相机没有关没返回（{@link #waitForNoClose}：旧设备那一次关排在前面、已经做完；
     * 别的实例、晚到的设备的关等它返回，不设时限）、这台相机还在，打开在锁外。
     *
     * <p>中间那两次进相机服务（列相机、查参数）可能卡几秒，这期间这一轮可能被关掉：真正打开之前在锁里再看一次
     * （{@link #openCameraMarked}），没开成的只在这一轮还是当前的时候收标记、报上去（{@link #failOpenIfCurrent}）。
     * 早退的那一下什么标记都不碰：关它的 {@link #closeCamera} 已经清了，标记可能已经是新一轮的。</p>
     */
    private void reopenOnCameraThread(Handler handler) {
        if (!waitForNoClose(handler)) {
            return;   // 等的时候被关了，或者已经换了一轮（或者那一次关排在这条线程上，已经报了开不起来）
        }
        configFailRetryCount = 0;   // 新的一步：会话配不上的重试从头数
        try {
            // 验证摄像头ID是否存在
            String[] availableCameraIds = cameraManager.getCameraIdList();
            boolean cameraExists = false;
            for (String id : availableCameraIds) {
                if (id.equals(cameraId)) {
                    cameraExists = true;
                    break;
                }
            }
            if (!cameraExists) {
                AppLog.e(TAG, "Camera ID " + cameraId + " does not exist anymore. Available IDs: " +
                        java.util.Arrays.toString(availableCameraIds));
                // 以前这里什么都不报：重开时不在列表里，正是车机刚拿走它的样子
                failOpenIfCurrent(handler, CameraTaken.Reason.ofNotListed(), true, 0, null);
                return;
            }
            // 验证摄像头是否真正可用
            try {
                cameraManager.getCameraCharacteristics(cameraId);
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " failed to get characteristics - camera may be invalid", e);
                failOpenIfCurrent(handler, reasonOf(e), true, 0, null);
                return;
            }
            if (openCameraMarked(handler)) {
                AppLog.d(TAG, "Camera " + cameraId + " reopen initiated");
            }
        } catch (CameraAccessException | RuntimeException e) {
            // 没发出去：标成「没开」，报开不起来，再不再试由调度判
            AppLog.e(TAG, "Failed to reopen camera " + cameraId, e);
            failOpenIfCurrent(handler, reasonOf(e), true, 0, e.getClass().getSimpleName());
        }
    }

    /**
     * 开、重开那一下的 openCamera（相机线程）：进相机服务之前在 {@link #deviceLock} 里最后看一次这一轮还是不是当前的，
     * 是才标上「正在打开」—— 判断和标记在同一把锁里，和主线程上的 {@link #closeCamera} 不会错开。
     *
     * <p>以前不看就标（审查 2026-10-11）：前面那几次进相机服务卡着的时候调度把这一路关了，这里照样标上「正在打开」、照样打开，
     * 晚到的设备在过时的回调里就地关掉，「正在打开」却再也没人清 —— 这一路从此每一次开都被「已经在开」挡掉。
     * 被关了的这一轮什么都不做、不报：关它的那一下已经把账结了。</p>
     *
     * <p>「正在打开」也是给相机服务那几声认人用的：随后报这一路「被占用」时，{@link CameraAvailabilityWatch} 靠
     * {@link #holdsOrIsOpening()} 认我们；重开时 {@code cameraDevice} 还是 null，不标的话每次重开都被记成「不是我们」——
     * 2026-09-27 的日志里就是这样。抛异常就把标记收回（这一轮还是当前的话），成功与否由 {@code onOpened} / {@code onError} 清。</p>
     *
     * <p>进相机服务打开之前再等一次这一台没返回的关（{@link #waitForNoClose}，项目所有者 2026-10-28）：前面列相机、查参数那几下
     * 进相机服务的时候，可能又登记了一次关（另一份实例在关、晚到的设备就地关）。</p>
     *
     * @return false：这一轮已经被关了、或者换了一轮，没打开（或者那一次关排在这条线程上，已经报了开不起来）
     */
    private boolean openCameraMarked(Handler handler) throws CameraAccessException {
        if (!waitForNoClose(handler)) {
            return false;
        }
        synchronized (deviceLock) {
            if (handler != backgroundHandler) {
                return false;
            }
            isOpening = true;
            markInFlight();
        }
        try {
            cameraManager.openCamera(cameraId, stateCallback, handler);
            return true;
        } catch (CameraAccessException | RuntimeException e) {
            synchronized (deviceLock) {
                if (handler == backgroundHandler) {
                    isOpening = false;
                }
            }
            throw e;
        }
    }

    /**
     * 开、重开没成（相机线程，{@code handler} 那一轮上）：这一轮还是当前的，才收标记、报开不起来 —— 判断和改标记在同一把锁里。
     * 被关了、换了一轮的什么都不做：那是关，不是开不起来，标记归关它的那一下、或者新的那一轮（审查 2026-10-11：
     * 以前照报，调度把一路已经在关、或者已经重开的记成开不起来、关着，再判一次）。
     *
     * @param lost      记成丢了设备（{@link #deviceLost}）
     * @param uiError   给界面相机状态的码（{@link CameraCallback#onCameraError}）；0 = 不报
     * @param errorName 记进 {@link #lastErrorName} 的短名；null = 不改
     */
    private void failOpenIfCurrent(Handler handler, CameraTaken.Reason reason, boolean lost, int uiError,
                                   String errorName) {
        synchronized (deviceLock) {
            if (handler != backgroundHandler) {
                return;
            }
            isOpening = false;
            reopenInFlight = false;
            if (lost) {
                deviceLost = true;
            }
            if (errorName != null) {
                lastErrorName = errorName;
            }
        }
        if (uiError != 0 && callback != null) {
            callback.onCameraError(cameraId, uiError);
        }
        reportOpenFailed(reason);
    }

    /**
     * 上一次开、重开在途已经超过 {@link #IN_FLIGHT_MAX_MS}（相机服务一直没回调，或者这一路的相机线程卡在相机服务里）：
     * 那一轮当它丢了 —— 像关相机一样作废（{@link #closeCamera}：摘下那条相机线程，它晚到的回调都是过时的，
     * 晚到的设备就地关掉；这一次关登记成在关），再在一条新的相机线程上开（审查 2026-10-11）。
     * 新的那一轮先等这一次关返回（{@link #waitForNoClose}，不设时限，项目所有者 2026-10-28），同一台相机不会有两个句柄：
     * 那条线程卡在相机服务里的，那一次打开返回、那一轮的关做完之前，这一台谁都不开 —— 以前新的那一轮只等 60 秒，
     * 到点照样再开，救援每作废一轮就多叠一次。
     * 以前重开在途不封顶，那一次 openCamera 没回音，这一路就再也救不回来；接着用那条相机线程的话，卡住的还是它。
     * 这一台有一次关没返回时走不到这里（{@link #openOrCloseInFlight} 先合并掉）。
     *
     * <p>这一下的关完回调（{@link CameraCallback#onCameraClosed}）调度不当回事：调度那边这一路已经记成在开。</p>
     */
    private boolean retireStaleOpen(Set<ChannelRules.Out> wanted) {
        com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + who() + " 上一次打开 " + (IN_FLIGHT_MAX_MS / 1000)
                + " 秒还没回音（相机服务卡住？）：作废那一轮，换一条相机线程，等那一轮关完再开");
        closeCamera("stale-open");
        return beginOpen(wanted);
    }

    // ==================== 亮度/降噪调节相关方法 ====================
    
    /**
     * 设置是否启用亮度/降噪调节
     * @param enabled true 表示启用
     */
    public void setImageAdjustEnabled(boolean enabled) {
        this.imageAdjustEnabled = enabled;
        AppLog.d(TAG, "Camera " + cameraId + " image adjust: " + (enabled ? "ENABLED" : "DISABLED"));
    }
    
    /**
     * 从配置中读取并应用亮度/降噪调节参数
     * @param requestBuilder 请求构建器
     */
    private void applyImageAdjustParamsFromConfig(CaptureRequest.Builder requestBuilder) {
        try {
            AppConfig appConfig = new AppConfig(context);
            
            // 应用曝光补偿
            int exposureComp = appConfig.getExposureCompensation();
            if (exposureComp != 0) {
                Range<Integer> range = getExposureCompensationRange();
                if (range != null) {
                    int clampedValue = Math.max(range.getLower(), Math.min(exposureComp, range.getUpper()));
                    requestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedValue);
                    AppLog.d(TAG, "Camera " + cameraId + " applied exposure compensation: " + clampedValue);
                }
            }
            
            // 应用白平衡模式
            int awbMode = appConfig.getAwbMode();
            if (awbMode >= 0) {
                int[] supportedModes = getSupportedAwbModes();
                if (supportedModes != null && isModeSupported(supportedModes, awbMode)) {
                    requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
                    AppLog.d(TAG, "Camera " + cameraId + " applied AWB mode: " + awbMode);
                }
            }
            
            // 应用色调映射模式
            int tonemapMode = appConfig.getTonemapMode();
            if (tonemapMode >= 0) {
                int[] supportedModes = getSupportedTonemapModes();
                if (supportedModes != null && isModeSupported(supportedModes, tonemapMode)) {
                    requestBuilder.set(CaptureRequest.TONEMAP_MODE, tonemapMode);
                    AppLog.d(TAG, "Camera " + cameraId + " applied tonemap mode: " + tonemapMode);
                }
            }
            
            // 应用边缘增强模式
            int edgeMode = appConfig.getEdgeMode();
            if (edgeMode >= 0) {
                int[] supportedModes = getSupportedEdgeModes();
                if (supportedModes != null && isModeSupported(supportedModes, edgeMode)) {
                    requestBuilder.set(CaptureRequest.EDGE_MODE, edgeMode);
                    AppLog.d(TAG, "Camera " + cameraId + " applied edge mode: " + edgeMode);
                }
            }
            
            // 应用降噪模式
            int noiseReductionMode = appConfig.getNoiseReductionMode();
            if (noiseReductionMode >= 0) {
                int[] supportedModes = getSupportedNoiseReductionModes();
                if (supportedModes != null && isModeSupported(supportedModes, noiseReductionMode)) {
                    requestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode);
                    AppLog.d(TAG, "Camera " + cameraId + " applied noise reduction mode: " + noiseReductionMode);
                }
            }
            
            // 应用特效模式
            int effectMode = appConfig.getEffectMode();
            if (effectMode >= 0) {
                int[] supportedModes = getSupportedEffectModes();
                if (supportedModes != null && isModeSupported(supportedModes, effectMode)) {
                    requestBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, effectMode);
                    AppLog.d(TAG, "Camera " + cameraId + " applied effect mode: " + effectMode);
                }
            }
            
            AppLog.d(TAG, "Camera " + cameraId + " image adjust params applied from config");
            
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to apply image adjust params from config", e);
        }
    }
    
    /**
     * 获取是否启用亮度/降噪调节
     */
    public boolean isImageAdjustEnabled() {
        return imageAdjustEnabled;
    }
    
    /**
     * 获取曝光补偿范围
     * @return 曝光补偿范围 [min, max]，如果不支持返回 null
     */
    public Range<Integer> getExposureCompensationRange() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get exposure compensation range", e);
        }
        return null;
    }
    
    /**
     * 获取曝光补偿步长
     * @return 曝光补偿步长（EV 单位），如果不支持返回 null
     */
    public android.util.Rational getExposureCompensationStep() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get exposure compensation step", e);
        }
        return null;
    }
    
    /**
     * 获取支持的白平衡模式
     * @return 支持的白平衡模式数组，如果不支持返回 null
     */
    public int[] getSupportedAwbModes() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get supported AWB modes", e);
        }
        return null;
    }
    
    /**
     * 获取支持的色调映射模式
     * @return 支持的色调映射模式数组，如果不支持返回 null
     */
    public int[] getSupportedTonemapModes() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get supported tonemap modes", e);
        }
        return null;
    }
    
    /**
     * 获取支持的边缘增强模式
     * @return 支持的边缘增强模式数组，如果不支持返回 null
     */
    public int[] getSupportedEdgeModes() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get supported edge modes", e);
        }
        return null;
    }
    
    /**
     * 获取支持的降噪模式
     * @return 支持的降噪模式数组，如果不支持返回 null
     */
    public int[] getSupportedNoiseReductionModes() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get supported noise reduction modes", e);
        }
        return null;
    }
    
    /**
     * 获取支持的特效模式
     * @return 支持的特效模式数组，如果不支持返回 null
     */
    public int[] getSupportedEffectModes() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get supported effect modes", e);
        }
        return null;
    }
    
    /**
     * 获取摄像头特性（带缓存）
     */
    /**
     * 系统有没有把这台相机给预览的画面左右翻过一次。
     *
     * <h3>正常视角</h3>
     *
     * <p>定为<b>相机实际看到的样子、不镜像</b>。照片走 JPEG 通道，系统不翻，本来就是这样；
     * 录像和预览吃到了系统那一下，所以在它们进来的地方各翻回一次（录像见
     * {@code EglSurfaceEncoder.toNormalView}）。之后配置里的「镜像」只在显示时再翻一次。</p>
     *
     * <h3>系统那一下</h3>
     *
     * <p>安卓对朝向为「前置」的相机，默认把给 SurfaceTexture 的画面左右翻一次，好让预览像照镜子。
     * 这台车上后座舱那一路报的是前置：它的预览被系统翻了一次、配置里的「镜像」又翻一次，
     * 两下抵消 —— 前座舱开着镜像是镜像的，后座舱开着镜像反而是正的。</p>
     */
    private boolean sourceMirrored() {
        if (sourceMirrored != null) {
            return sourceMirrored;
        }
        CameraCharacteristics chars = getCameraCharacteristics();
        if (chars == null) {
            return false;   // 这次查不到，下次再查
        }
        Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
        sourceMirrored = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        AppLog.i(TAG, "Camera " + cameraId + " LENS_FACING=" + facing + (sourceMirrored
                ? "：前置，系统会把预览左右翻一次，显示前先翻回正常视角" : ""));
        return sourceMirrored;
    }

    /** 从预览抓的图也吃到了系统那一下（抓的是 SurfaceTexture 按它的矩阵画出来的样子），翻回来再存。 */
    private android.graphics.Bitmap toNormalView(android.graphics.Bitmap bitmap) {
        if (!sourceMirrored()) {
            return bitmap;
        }
        android.graphics.Matrix flip = new android.graphics.Matrix();
        flip.setScale(-1f, 1f);
        android.graphics.Bitmap normal = android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), flip, true);
        if (normal != bitmap) {
            bitmap.recycle();
        }
        AppLog.i(TAG, "Camera " + cameraId + " 预览抓图：前置相机，翻回正常视角再存");
        return normal;
    }

    private CameraCharacteristics getCameraCharacteristics() {
        if (cameraCharacteristics != null) {
            return cameraCharacteristics;
        }
        CameraCharacteristics cached = CameraCapabilities.characteristics(cameraId);
        if (cached != null) {
            cameraCharacteristics = cached;
            return cached;
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // 主线程不进相机服务：它忙的时候这一问要等几秒到十几秒，2026-10-08 的 ANR 就卡在这种调用上。
            // 相机打开时会把参数记进 CameraCapabilities，到那时再问就有了
            AppLog.w(TAG, "Camera " + cameraId + " characteristics not cached yet, not asking the camera service on the main thread");
            return null;
        }
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            CameraCapabilities.record(cameraId, cameraCharacteristics);
            return cameraCharacteristics;
        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get characteristics", e);
            return null;
        }
    }
    
    /**
     * 实时更新亮度/降噪调节参数
     * 参数会立即应用到预览和录制
     *
     * <p>这是请求级的动作：只改重复请求，不改会话、不改输出 —— 调度之外唯一允许的相机调用（见类说明）。
     * 在这一路的相机线程上做；这一路有一步在途（在开、在重开、在配会话、在关旧会话）时先不发，
     * 参数记下来，下一次建会话时带上（{@link #overlayLatestAdjust}）。</p>
     *
     * @param exposureCompensation 曝光补偿值（Integer.MIN_VALUE 表示不设置）
     * @param awbMode 白平衡模式（-1 表示不设置）
     * @param tonemapMode 色调映射模式（-1 表示不设置）
     * @param edgeMode 边缘增强模式（-1 表示不设置）
     * @param noiseReductionMode 降噪模式（-1 表示不设置）
     * @param effectMode 特效模式（-1 表示不设置）
     * @return true 表示成功，false 表示失败
     */
    public boolean updateImageAdjustParams(int exposureCompensation, int awbMode, int tonemapMode,
                                           int edgeMode, int noiseReductionMode, int effectMode) {
        if (!imageAdjustEnabled) {
            AppLog.d(TAG, "Camera " + cameraId + " image adjust not enabled, skip update");
            return false;
        }
        // 先记下：这一次发不出去（没开着、在途），下一次建会话时照样带上最新的
        latestAdjust = new int[]{exposureCompensation, awbMode, tonemapMode, edgeMode, noiseReductionMode, effectMode};
        Handler handler = backgroundHandler;
        if (cameraDevice == null || captureSession == null || currentRequestBuilder == null || handler == null) {
            AppLog.w(TAG, "Camera " + cameraId + " not ready for image adjust update");
            return false;
        }
        // 改请求、重新提交都是进相机服务的调用：放到这一路的相机线程上（以前在主线程，画面调节窗口每拖一下都可能卡一下）
        handler.post(() -> applyImageAdjustParamsNow(exposureCompensation, awbMode, tonemapMode,
                edgeMode, noiseReductionMode, effectMode));
        return true;
    }

    private boolean applyImageAdjustParamsNow(int exposureCompensation, int awbMode, int tonemapMode,
                                              int edgeMode, int noiseReductionMode, int effectMode) {
        if (cameraDevice == null || captureSession == null || currentRequestBuilder == null) {
            return false;
        }
        if (stepInFlight()) {
            // 手上这一步做完会建新会话，那时带上最新的参数；这会儿发，发的是一份马上作废的请求
            AppLog.d(TAG, "Camera " + cameraId + " image adjust deferred: a step is in flight, the next session carries it");
            return false;
        }
        try {
            putAdjustParams(currentRequestBuilder, exposureCompensation, awbMode, tonemapMode,
                    edgeMode, noiseReductionMode, effectMode);

            // 重新提交请求（实时生效）；带上出帧回调 —— 以前传 null，这之后的帧不计数，
            // 调度 8 秒后把好好的相机当成没画面去救
            captureSession.setRepeatingRequest(currentRequestBuilder.build(), activeCaptureCallback, backgroundHandler);
            AppLog.d(TAG, "Camera " + cameraId + " image adjust params updated successfully");
            return true;

        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to update image adjust params", e);
            return false;
        } catch (IllegalStateException e) {
            AppLog.e(TAG, "Camera " + cameraId + " session invalid during image adjust update", e);
            return false;
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " unexpected error during image adjust update", e);
            return false;
        }
    }

    /** 画面调节赶上在途时没发出去的参数，建会话时带上（相机线程）。 */
    private void overlayLatestAdjust(CaptureRequest.Builder builder) {
        int[] params = latestAdjust;
        if (!imageAdjustEnabled || params == null) {
            return;
        }
        try {
            putAdjustParams(builder, params[0], params[1], params[2], params[3], params[4], params[5]);
        } catch (Exception e) {
            AppLog.w(TAG, "Camera " + cameraId + " 带上最新的画面调节参数失败: " + e);
        }
    }

    /** 把画面调节的参数写进一个请求：不支持的模式不写，曝光补偿夹进相机声明的范围。 */
    private void putAdjustParams(CaptureRequest.Builder builder, int exposureCompensation, int awbMode,
                                 int tonemapMode, int edgeMode, int noiseReductionMode, int effectMode) {
        // 应用曝光补偿
        if (exposureCompensation != Integer.MIN_VALUE) {
            Range<Integer> range = getExposureCompensationRange();
            if (range != null) {
                // 确保值在有效范围内
                int clampedValue = Math.max(range.getLower(), Math.min(exposureCompensation, range.getUpper()));
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedValue);
                AppLog.d(TAG, "Camera " + cameraId + " set exposure compensation: " + clampedValue + " (range: " + range + ")");
            }
        }

        // 应用白平衡模式
        if (awbMode >= 0) {
            int[] supportedModes = getSupportedAwbModes();
            if (supportedModes != null && isModeSupported(supportedModes, awbMode)) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
                AppLog.d(TAG, "Camera " + cameraId + " set AWB mode: " + awbMode);
            } else {
                AppLog.w(TAG, "Camera " + cameraId + " AWB mode " + awbMode + " not supported");
            }
        }

        // 应用色调映射模式
        if (tonemapMode >= 0) {
            int[] supportedModes = getSupportedTonemapModes();
            if (supportedModes != null && isModeSupported(supportedModes, tonemapMode)) {
                builder.set(CaptureRequest.TONEMAP_MODE, tonemapMode);
                AppLog.d(TAG, "Camera " + cameraId + " set tonemap mode: " + tonemapMode);
            } else {
                AppLog.w(TAG, "Camera " + cameraId + " tonemap mode " + tonemapMode + " not supported");
            }
        }

        // 应用边缘增强模式
        if (edgeMode >= 0) {
            int[] supportedModes = getSupportedEdgeModes();
            if (supportedModes != null && isModeSupported(supportedModes, edgeMode)) {
                builder.set(CaptureRequest.EDGE_MODE, edgeMode);
                AppLog.d(TAG, "Camera " + cameraId + " set edge mode: " + edgeMode);
            } else {
                AppLog.w(TAG, "Camera " + cameraId + " edge mode " + edgeMode + " not supported");
            }
        }

        // 应用降噪模式
        if (noiseReductionMode >= 0) {
            int[] supportedModes = getSupportedNoiseReductionModes();
            if (supportedModes != null && isModeSupported(supportedModes, noiseReductionMode)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode);
                AppLog.d(TAG, "Camera " + cameraId + " set noise reduction mode: " + noiseReductionMode);
            } else {
                AppLog.w(TAG, "Camera " + cameraId + " noise reduction mode " + noiseReductionMode + " not supported");
            }
        }

        // 应用特效模式
        if (effectMode >= 0) {
            int[] supportedModes = getSupportedEffectModes();
            if (supportedModes != null && isModeSupported(supportedModes, effectMode)) {
                builder.set(CaptureRequest.CONTROL_EFFECT_MODE, effectMode);
                AppLog.d(TAG, "Camera " + cameraId + " set effect mode: " + effectMode);
            } else {
                AppLog.w(TAG, "Camera " + cameraId + " effect mode " + effectMode + " not supported");
            }
        }
    }
    
    /**
     * 检查模式是否在支持列表中
     */
    private boolean isModeSupported(int[] supportedModes, int mode) {
        if (supportedModes == null) {
            return false;
        }
        for (int supported : supportedModes) {
            if (supported == mode) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 CaptureResult 读取相机实际使用的参数
     */
    private void readActualParamsFromResult(TotalCaptureResult result) {
        try {
            // 曝光补偿
            Integer exposure = result.get(TotalCaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
            if (exposure != null) {
                actualExposureCompensation = exposure;
            }
            
            // 白平衡模式
            Integer awb = result.get(TotalCaptureResult.CONTROL_AWB_MODE);
            if (awb != null) {
                actualAwbMode = awb;
            }
            
            // 边缘增强模式
            Integer edge = result.get(TotalCaptureResult.EDGE_MODE);
            if (edge != null) {
                actualEdgeMode = edge;
            }
            
            // 降噪模式
            Integer noise = result.get(TotalCaptureResult.NOISE_REDUCTION_MODE);
            if (noise != null) {
                actualNoiseReductionMode = noise;
            }
            
            // 特效模式
            Integer effect = result.get(TotalCaptureResult.CONTROL_EFFECT_MODE);
            if (effect != null) {
                actualEffectMode = effect;
            }
            
            // 色调映射模式
            Integer tonemap = result.get(TotalCaptureResult.TONEMAP_MODE);
            if (tonemap != null) {
                actualTonemapMode = tonemap;
            }
            
            AppLog.d(TAG, "Camera " + cameraId + " actual params: exposure=" + actualExposureCompensation +
                    ", awb=" + actualAwbMode + ", edge=" + actualEdgeMode + 
                    ", noise=" + actualNoiseReductionMode + ", effect=" + actualEffectMode +
                    ", tonemap=" + actualTonemapMode);
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to read actual params", e);
        }
    }
    
    // ==================== 获取实际参数的方法 ====================
    
    /**
     * 获取相机实际使用的曝光补偿值
     */
    public int getActualExposureCompensation() {
        return actualExposureCompensation;
    }
    
    /**
     * 获取相机实际使用的白平衡模式
     */
    public int getActualAwbMode() {
        return actualAwbMode;
    }
    
    /**
     * 获取相机实际使用的边缘增强模式
     */
    public int getActualEdgeMode() {
        return actualEdgeMode;
    }
    
    /**
     * 获取相机实际使用的降噪模式
     */
    public int getActualNoiseReductionMode() {
        return actualNoiseReductionMode;
    }
    
    /**
     * 获取相机实际使用的特效模式
     */
    public int getActualEffectMode() {
        return actualEffectMode;
    }
    
    /**
     * 获取相机实际使用的色调映射模式
     */
    public int getActualTonemapMode() {
        return actualTonemapMode;
    }
    
    /**
     * 是否已读取过实际参数
     */
    public boolean hasActualParams() {
        return hasReadActualParams;
    }
}
