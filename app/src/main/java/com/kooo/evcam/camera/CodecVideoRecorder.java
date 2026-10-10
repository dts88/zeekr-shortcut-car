package com.kooo.evcam.camera;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 使用 MediaCodec + MediaMuxer 进行视频编码和录制
 * 用于 L6/L7 等不支持 MediaRecorder 直接录制的车机平台
 * 
 * 工作流程：
 * 1. 创建 MediaCodec 编码器，获取其输入 Surface
 * 2. 使用 EglSurfaceEncoder 将 Camera 的帧渲染到编码器输入 Surface（编码线程）
 * 3. 从 MediaCodec 取出编码后的数据，拷出来排给写入线程（编码线程，见 SampleRing）
 * 4. 写入线程通过 MediaMuxer 写入 MP4 文件（MediaMuxer 只在写入线程上动，见「写入线程」那一节）
 */
public class CodecVideoRecorder {
    private static final String TAG = "CodecVideoRecorder";

    // 编码参数（常量）
    private static final String MIME_TYPE_H264 = MediaFormat.MIMETYPE_VIDEO_AVC;      // H.264
    private static final String MIME_TYPE_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;     // H.265/HEVC
    private String mimeType = MIME_TYPE_H264;  // 默认使用 H.264，支持时自动切换到 HEVC
    
    private static final int I_FRAME_INTERVAL = 3;  // I帧间隔（秒）- 改为3秒减少CPU占用和文件大小
    
    // 编码参数（可配置）
    private int frameRate = 20;       // 默认 20fps - 降低帧率减少CPU占用，同时保持流畅
    
    // 编码器选择：是否强制使用 H.264（默认 false，优先使用 HEVC）
    private boolean forceH264 = false;
    
    // 画质等级：0=极低, 1=低, 2=中, 3=高（见 TargetBitrate）
    private int qualityLevel = 2;

    /** 渲染节流上限；0 = 不限制。和 {@link #frameRate}（标称值）不是同一件事。 */
    private int frameRateCap;
    
    // 自适应 drain 间隔控制（优化版 - 减少CPU占用）
    private volatile long currentDrainIntervalMs = 20;  // 当前 drain 间隔（毫秒）- 提高到20ms减少CPU占用
    private static final long DRAIN_INTERVAL_MIN_MS = 10;   // 最小 10ms（保证流畅性）
    private static final long DRAIN_INTERVAL_MAX_MS = 50;  // 最大 50ms（减少CPU占用）
    private static final int DRAIN_BATCH_SIZE = 8;  // 每次 drain 批量处理更多帧，减少系统调用开销
    private long lastDrainTimeMs = 0;  // 上次 drain 时间
    private int framesSinceLastDrain = 0;  // 上次 drain 以来的帧数

    private final String cameraId;
    /** 编码线程收到帧的心跳和各步用时，给卡顿监测用（见 StallWatch）。这个录制器自己一份。 */
    private final Heartbeat encoderBeat;
    private final int width;
    private final int height;

    // MediaCodec 相关
    private MediaCodec encoder;
    private Surface encoderInputSurface;
    private MediaCodec.BufferInfo bufferInfo;

    // MediaMuxer 那一侧（文件、轨道、fsync、换盘）只在写入线程上动，字段在「写入线程」那一节

    /** 排着等写的和写过多留的已编码样本，编码线程和写入线程之间就这一份（见 SampleRing）。 */
    private final SampleRing ring = new SampleRing();
    /** 这一代编码器报过输出格式没有（编码线程上写）。健康检查看它：画了很多帧还没报，就当编码器坏了。 */
    private volatile boolean formatSeen;
    /** 这一代编码器画了几帧（换编码器时清零）。健康检查拿它配 {@link #formatSeen}，不拿整场录像的总帧数。 */
    private volatile int framesThisEncoder;
    /**
     * 这一代编码器的输出格式里带没带参数集（HEVC 的 csd-0；H.264 的 csd-0 和 csd-1）。
     * 带了，单独的参数集缓冲区就不当样本写（muxer 开轨时已经拿到了）；没带，照旧写进去，否则文件里没有参数集。
     */
    private boolean csdInFormat;
    /** 排了「开文件」、还没排「收文件」（编码线程上）。快速恢复时据此决定要不要开新文件。 */
    private boolean fileQueued;
    /** 开过录没有（{@link #startRecording} 成功过）。停录时没开过录的不收文件、不报，留给释放。 */
    private volatile boolean everStarted;
    /**
     * 叫停了（{@link #beginStop}）。之后分段切换、快速恢复、重建编码器都不再开新文件、不再把「在录」置回去 ——
     * 这些都在编码线程上，停录的收尾也排在编码线程上，所以「收文件（停录）」之后不会再有「开文件」。
     */
    private volatile boolean stopRequested;
    /** 编码线程排的第几个文件。写入线程说哪个文件写不下去了，凭它认出说的是不是还是现在这个。 */
    private int openSerial;
    /** 最近一次排的文件名（给日志和黑匣子用；真正写到哪个盘由写入线程定）。 */
    private String queuedFileName = "";

    // EGL 渲染器
    private volatile EglSurfaceEncoder eglEncoder;
    private SurfaceTexture inputSurfaceTexture;
    private int textureId;

    // 编码线程
    private HandlerThread encoderThread;
    private Handler encoderHandler;

    // 状态
    private final AtomicBoolean isRecording = new AtomicBoolean(false);  // 使用 AtomicBoolean 确保线程安全
    private volatile boolean isReleased = false;
    /** 正在写的文件（写入线程开文件、换盘时改；开录时先填上第一个）。 */
    private volatile String currentFilePath;
    
    // 缓存的录制 Surface，避免重复创建导致内存泄漏
    private Surface cachedRecordSurface = null;
    
    // 时间戳基准（用于计算相对时间戳，供输入端使用）
    private long firstFrameTimestampNs = -1;

    /** 上一次写入 muxer 的 PTS，用于保证严格单调递增；-1 表示还没写过帧。写入线程上，每个文件从头算。 */
    private long lastWrittenPtsUs = -1L;
    /** 角标是否附带录制规格。 */
    private boolean watermarkSpecEnabled = true;
    /** 编码器实际使用的规格，用于角标第二行。 */
    private String encoderSpecLine = "";
    private String specSizeText = "";
    private String specCodecText = "";
    /** 设置里选的那个帧率。它是<b>上限</b>，不是结果。 */
    private int nominalFrameRate;
    /** 实测帧率：已写帧数 / 时间戳跨度。0 表示还没测出来。 */
    private int measuredFrameRate;

    /**
     * 拼角标第二行。
     *
     * <p>帧率优先写<b>实测值</b>。设置里那个数只是渲染节流的上限 ——
     * 相机给不到那么多帧时，编码器就出不到那么多帧。角标写标称值等于
     * 「界面显示的和实际录到的不是一回事」：分享到手机上，播放器读出来的
     * 是 15 fps，而画面角上印着 25 fps，两个数对不上，而印在画面里的那个是错的。</p>
     *
     * <p>测出来之前先写标称值并加个「~」，否则录制刚开始那几秒角标是空的。</p>
     */
    private void rebuildSpecLine() {
        String fps = measuredFrameRate > 0
                ? measuredFrameRate + "fps"
                : "~" + nominalFrameRate + "fps";
        // 不写目标码率：那是设置里选出来的一个上限，印在画面里没有信息量。
        // 角标上的码率只有一个 —— 每秒实测的那个（见 applyWatermarkInfoLine）。
        encoderSpecLine = specSizeText + "  " + fps + "  " + specCodecText;
        applyWatermarkInfoLine();
    }
    /** 本文件第一帧的编码器时间戳，用于把每个文件的 PTS 归零；-1 表示还没开始。写入线程上。 */
    private long segmentBasePtsUs = -1L;

    // 分段录制相关
    private long segmentDurationMs = 60000;  // 分段时长，默认1分钟，可通过 setSegmentDuration 配置
    private static final long MIN_VALID_FILE_SIZE = 1 * 1024;   // 最小有效文件大小 1KB（降低阈值，短录制也能保存）
    
    // 使用独立的后台线程处理分段和文件 I/O 操作，避免阻塞主线程导致 ANR
    private HandlerThread segmentThread;
    private Handler segmentHandler;
    
    private Runnable segmentRunnable;
    private int segmentIndex = 0;
    /** 录像写在哪个目录。开录时定；之后只有写入线程改（换盘）。 */
    private volatile String saveDirectory;
    private String cameraPosition;
    private VideoRecorder.SegmentTimestampProvider timestampProvider;  // 分段时间戳提供者（用于多路同步）
    private long recordedFrameCount = 0;
    /**
     * 本次录制还在这个录制器手里的文件路径。写入线程开文件、抢救时加，分段切换收好、报出去（{@link RecordCallback#onSegmentSwitch}）
     * 时拿掉，停录时读，所以加锁。
     */
    private final List<String> recordedFilePaths = Collections.synchronizedList(new ArrayList<>());
    /** 上一次停录收掉的文件（{@link #finishStop} 结束时还在手里的那几个），见 {@link #stoppedFiles}。 */
    private volatile List<String> stoppedFiles = Collections.emptyList();
    /**
     * 第一个文件等开录时再开（单独接回的一路，{@link #prepareWithoutFile}）：名字是开录那一刻。
     * 准备的线程上写、开录的线程上读，中间隔着一次 Handler 投递。
     */
    private boolean firstFileAtStart;
    /**
     * 现在这个文件的名字不是共用的分段时间戳（单独接回时按那一刻起的）：别的路一换新的共用名字，
     * 这一路马上跟着切（{@link #switchNow}）。开录时置上，从共用的时间戳拿到名字时清掉。
     */
    private volatile boolean offCycleName;
    
    /** 这次录制写出过第一笔数据没有：分段计时、外面的「录制中」都从那一刻起。 */
    private volatile boolean hasFirstWrite = false;

    // 快速恢复：修不好就 5 秒后再试，一直到写不进文件的裁判（下面）报一次、停
    private static final long RECOVERY_RETRY_INTERVAL_MS = 5000;
    private int recoveryAttempts = 0;  // 当前重试次数（只进日志）
    private Runnable recoveryRunnable;  // 恢复重试任务

    // 编码器健康检查
    private static final long ENCODER_HEALTH_CHECK_INTERVAL_MS = 3000;  // 健康检查间隔：3秒
    private static final int MUXER_START_GRACE_FRAMES = 30;  // 处理了这么多帧编码器还没报输出格式，就当编码器坏了
    private volatile boolean encoderHealthy = true;  // 编码器是否健康

    /**
     * 写不进文件的裁判 —— 唯一的一个（项目所有者 2026-09-27：录像健不健康只由录制器判）。
     *
     * <p>从开录（或最后一次写进文件）起 {@link #WRITE_STALL_MS} 没有新数据写进文件，就报一次
     * {@link RecordCallback#onWriteStalled}，由 RecordingCoordinator 按打断处理。这 15 秒里自己的修复
     * （换盘、重建编码器、快速恢复）照常跑，救回来了就不报。以前相机层还有一个 15 秒看门狗、
     * 主界面还有一个 10 秒「无首帧」看门狗、这里还有一个 10 秒「首次写入超时」，四个裁判互相抢。</p>
     */
    private static final long WRITE_STALL_MS = 15_000L;
    private static final long WRITE_CHECK_MS = 5_000L;
    private long startedUptimeMs;
    private Runnable writeStallCheck;

    /**
     * 最后一次真的往文件里写进数据的时刻（开机时长，深睡不算）。
     *
     * <p>写不进文件的裁判拿它判断「界面说在录、实际写不进文件」。相机有帧、编码线程在转，
     * 都不等于写进去了 —— 2026-09-26 哨兵模式那一次，这两样都好好的，文件却两个小时没长。</p>
     */
    private volatile long lastWriteUptimeMs;
    private volatile boolean everWrote;
    /** 最近一次出错在哪一步、系统怎么说的。写不进文件时黑匣子带上它。 */
    private volatile String lastTrouble = "";
    private Runnable healthCheckRunnable;  // 健康检查任务

    // 回调
    private RecordCallback callback;

    // 时间水印设置
    private boolean watermarkEnabled = false;

    // 注意：帧同步变量已移除，帧处理现在直接在 onFrameAvailable 回调中完成

    /**
     * 相机输出缓冲区尺寸。默认等于编码尺寸；四宫格录制时这里是<b>合成流原始尺寸</b>
     * （如 1280x5140），而编码输出是 2x2 的正方形。
     */
    private int sourceWidth;
    private int sourceHeight;
    /** 非 null 时按四宫格编码。 */
    private com.kooo.evcam.zeekr.CompositeStreamGeometry.Plan fourLanePlan;
    private int[] fourLaneOrder;

    /**
     * 启用四宫格录制。
     *
     * <p>相机仍按 {@code srcWidth x srcHeight} 出帧，编码器把它拆成四个画面渲染成
     * 2x2 后再编码。构造函数里的 width/height 此时应当传 2x2 的输出尺寸。</p>
     *
     * @param srcWidth  合成流原始宽度
     * @param srcHeight 合成流原始高度
     * @param plan      拆分方案（按原始尺寸算出）
     * @param order     画面排列，可为 null
     */
    public void setFourLaneSource(int srcWidth, int srcHeight,
                                  com.kooo.evcam.zeekr.CompositeStreamGeometry.Plan plan,
                                  int[] order) {
        if (srcWidth > 0 && srcHeight > 0) {
            this.sourceWidth = srcWidth;
            this.sourceHeight = srcHeight;
        }
        this.fourLanePlan = plan;
        this.fourLaneOrder = order;
        AppLog.i(TAG, "Camera " + cameraId + " 四宫格录制: 源 " + srcWidth + "x" + srcHeight
                + " -> 编码 " + width + "x" + height);
    }

    public CodecVideoRecorder(String cameraId, int width, int height) {
        this.cameraId = cameraId;
        // 心跳每个录制器自己一份，开录时登记、释放时按这一份撤（同一路新旧录制器交替时互不影响）
        this.encoderBeat = new Heartbeat();
        this.writerBeat = new Heartbeat();
        this.width = width;
        this.height = height;
        // 默认相机输出尺寸与编码尺寸一致；四宫格模式下由 setFourLaneSource 覆盖
        this.sourceWidth = width;
        this.sourceHeight = height;
        // 创建独立的后台线程用于分段处理和文件 I/O 操作
        segmentThread = new HandlerThread("CodecRecorder-Segment-" + cameraId) {
            @Override
            protected void onLooperPrepared() {
                // 降低分段线程优先级，减少对主线程的影响
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            }
        };
        segmentThread.start();
        this.segmentHandler = new Handler(segmentThread.getLooper());
    }

    /**
     * 设置是否启用时间水印。只管右上角时间 + 规格行；左上角应用名（+ 车牌号）每一路都画，不看它。
     * @param enabled true 表示启用水印
     */
    public void setWatermarkEnabled(boolean enabled) {
        this.watermarkEnabled = enabled;
        // 如果 EGL 编码器已初始化，同步设置
        if (eglEncoder != null) {
            eglEncoder.setWatermarkEnabled(enabled);
        }
        applyWatermarkInfoLine();
        AppLog.d(TAG, "Camera " + cameraId + " Watermark " + (enabled ? "enabled" : "disabled"));
    }

    /** 角标是否附带录制规格那一行。 */
    public void setWatermarkSpecEnabled(boolean enabled) {
        this.watermarkSpecEnabled = enabled;
        applyWatermarkInfoLine();
    }

    /**
     * 把录制规格送到角标第二行。
     *
     * <p>用的是真正配置给编码器的值，不是设置里的目标值 —— 请求的尺寸可能被夹过，
     * 编码可能回退到 H.264，帧率也可能被补盲模式改写。角标要如实反映录出来的东西。</p>
     */
    /** 上一秒编码器给出（排给写入线程）的字节数，用来算实时码率。 */
    private long bytesThisSecond;
    private long bitrateWindowStartMs;
    private String liveBitrateText = "";

    /**
     * 记一笔编码器刚给出的字节数，每满一秒折算成码率（编码线程上）。
     *
     * <p>用的是<b>编码器真正给出的大小</b>，不是设置里的目标码率 ——
     * 编码器给的是可变码率，画面越复杂写得越多，目标值只是个上限。这些字节排给写入线程，
     * 一个不少地写进文件；在这里数而不在写入线程上数，是因为 U 盘卡一下时写入是一阵一阵的，
     * 按写入算出来的码率会跟着跳，而录进去的画面并没有变。</p>
     *
     * <p>这一步几乎不花钱：字节数本来就在手上，而水印位图本来就每秒重画一次
     * （秒数变了才重画）。所以只是把一个已有的数字接到一行已有的文字上。</p>
     */
    private void noteEncodedBytes(int size) {
        if (size <= 0) {
            return;
        }
        bytesThisSecond += size;
        framesThisSecond++;
        long now = android.os.SystemClock.elapsedRealtime();
        if (bitrateWindowStartMs == 0) {
            bitrateWindowStartMs = now;
            return;
        }
        long elapsed = now - bitrateWindowStartMs;
        if (elapsed < BITRATE_WINDOW_MS) {
            return;
        }
        float mbps = bytesThisSecond * 8f / elapsed / 1000f;   // 字节/毫秒 -> Mbps
        liveBitrateText = String.format(java.util.Locale.US, "%.1f Mbps", mbps);

        // 帧率和码率用同一个窗口。
        //
        // 之前是在写 muxer 的地方按「每 300 帧算一次」——而写 muxer 有两条路径，
        // 那段判断只在其中一条里。帧数从另一条路走过去时计数照加、判断照跳，
        // 于是「第 300 帧」那一刻可能永远撞不上，
        // 实测帧率就一次都没算出来过 —— 角标始终停在「~标称值」。
        //
        // noteEncodedBytes 是两条路径都会调的那个点，挂在这里才数得全。
        int measured = Math.round(framesThisSecond * 1000f / elapsed);
        if (measured > 0 && measured != measuredFrameRate) {
            measuredFrameRate = measured;
            rebuildSpecLine();
        }
        framesThisSecond = 0;

        bytesThisSecond = 0;
        bitrateWindowStartMs = now;
        applyWatermarkInfoLine();
    }

    /** 本窗口内编码器给出的帧数，和字节数用同一个窗口结算。 */
    private int framesThisSecond;

    /** 码率取样窗口。一秒够用了，再快也看不清。 */
    private static final long BITRATE_WINDOW_MS = 1000L;

    /**
     * 录像左上角标的那行字：应用名 + 版本号，填了车牌号跟在后面。每一路都画，和时间水印开关无关
     * （只限这条 MediaCodec 录制路径；开发者选项里的 MediaRecorder 模式没有 GL 这一步，什么角标都不盖）。
     *
     * <p>由调用方给 —— 这个类拿不到 Context。录出来的文件常常是拿去当证据
     * 或者发给别人的，落上是哪个应用、哪个版本录的，回头出问题才对得上。</p>
     */
    public void setBrandLine(String line) {
        this.brandLine = line == null ? "" : line;
    }

    private String brandLine = "";

    /** 录像下方的行驶信息条；null 表示没有。要在 prepareRecording 之前设好。 */
    private com.kooo.evcam.telemetry.InfoBarRenderer infoBar;

    /**
     * 录像下方的行驶信息条。编码尺寸（构造时给的 height）必须已经包含它的高
     * （{@link EncodeSize#withInfoBar}）；渲染器在编码器建起来时交给 GL 那边。
     */
    public void setInfoBar(com.kooo.evcam.telemetry.InfoBarRenderer bar) {
        this.infoBar = bar;
    }

    private void applyWatermarkInfoLine() {
        if (eglEncoder == null) {
            return;  // 编码器还没建，createEncoder 结束时会再调一次
        }
        String line = "";
        if (watermarkEnabled && watermarkSpecEnabled) {
            line = encoderSpecLine;
            if (!liveBitrateText.isEmpty()) {
                line = line + "  " + liveBitrateText;
            }
        }
        eglEncoder.setWatermarkInfoLine(line);
    }

    public void setCallback(RecordCallback callback) {
        this.callback = callback;
    }

    /**
     * 设置分段时间戳提供者
     * 用于多路摄像头分段切换时使用统一的时间戳，避免时间戳差1秒导致分组错误
     * @param provider 时间戳提供者
     */
    public void setTimestampProvider(VideoRecorder.SegmentTimestampProvider provider) {
        this.timestampProvider = provider;
    }

    /**
     * 设置分段时长
     * @param durationMs 分段时长（毫秒）
     */
    public void setSegmentDuration(long durationMs) {
        this.segmentDurationMs = durationMs;
        AppLog.d(TAG, "Camera " + cameraId + " segment duration set to " + (durationMs / 1000) + " seconds");
    }

    /**
     * 设置录制帧率
     * @param fps 帧率（fps）
     */
    public void setFrameRate(int fps) {
        setFrameRate(fps, fps);
    }

    /**
     * @param nominalFps 标称帧率，<b>必须是正数</b>。用于 {@code KEY_FRAME_RATE}
     *                   与码率估算 —— 这两处拿到 0 会配置失败或算出 0 码率。
     * @param capFps     渲染节流上限；0 表示不限制，视频流给多少录多少。
     */
    public void setFrameRate(int nominalFps, int capFps) {
        this.frameRate = Math.max(1, nominalFps);
        this.frameRateCap = Math.max(0, capFps);
        applyEncoderFrameRate();
        AppLog.d(TAG, "Camera " + cameraId + " 帧率：标称 " + this.frameRate
                + " fps，节流上限 "
                + (this.frameRateCap == 0 ? "不限制" : this.frameRateCap + " fps"));
    }

    /**
     * 标称帧率对应的时间戳步长（微秒）。
     *
     * <p>只在 {@link #nextPtsUs(long, boolean)} 的兜底分支里用到 —— 正常情况下时间戳来自
     * 编码器，不需要这个值。</p>
     */
    private long ptsStepUs() {
        int fps = frameRate;
        if (fps <= 0) {
            fps = 25;  // 兜底，与历史行为一致
        }
        return 1_000_000L / fps;
    }

    /**
     * 决定这一帧写进 muxer 的时间戳。
     *
     * <p>编码器输出的 {@code presentationTimeUs} 已经是真实的采集时间 ——
     * 它来自 {@code surfaceTexture.getTimestamp()}，经
     * {@code eglPresentationTimeANDROID} 一路传到这里。直接用它，
     * 回放速度才等于实际录制速度。</p>
     *
     * <p>这里原来是按帧计数递推（帧号 × 标称步长）。<b>那个做法从根上就不成立</b>：
     * 它假设编码器真的按标称帧率收到了帧。而 1280×5140 的合成流跑不满 30fps，
     * 于是 N 帧被标成 N/30 秒、实际却花了 N/15 秒，回放就快了一倍。
     * 「原始帧率」这一档最明显，因为它的标称值最高 —— 之前只把写死的 25fps
     * 步长改成跟随设置，治的是症状，递推本身才是病根。</p>
     *
     * <p>当初保留递推是担心时间戳抖动或丢帧导致乱序被 muxer 拒绝。
     * 那个顾虑用一个单调性兜底就够了，不需要牺牲真实时间。</p>
     *
     * <p>写入线程上调；基准每开一个文件清一次（{@link #createMuxer}）。</p>
     *
     * @param encoderPtsUs 编码器给出的时间戳
     * @param config       只有参数集的样本（输出格式里没带参数集时才当样本写）：它的时间戳常是 0，
     *                     不拿它定这个文件从哪个时间起，也不往前推 —— 第二段起画面的时间戳早过了 60 秒，
     *                     拿它当起点整个文件就错开了。跟在上一个画面后面（文件开头就是 0）
     * @return 严格大于上一帧的时间戳（参数集除外）
     */
    private long nextPtsUs(long encoderPtsUs, boolean config) {
        if (config) {
            return Math.max(0L, lastWrittenPtsUs);
        }
        // 每个文件都是一个新的 muxer，PTS 要从 0 开始。
        // firstFrameTimestampNs 整场录制都不重置（EGL 需要单调递增的时间戳，
        // 见 onFrameAvailable 处的说明），编码器给的时间戳会一路累加下去 ——
        // 所以这里按本段第一帧再减一次基准。
        if (segmentBasePtsUs < 0) {
            segmentBasePtsUs = encoderPtsUs;
        }
        long pts = encoderPtsUs - segmentBasePtsUs;
        if (pts <= lastWrittenPtsUs) {
            // 时间戳没有前进（或编码器没给出有效值）时兜底：
            // 用标称步长顶一格，保证 muxer 不会因为 PTS 不递增而拒绝这一帧
            pts = lastWrittenPtsUs + ptsStepUs();
        }
        lastWrittenPtsUs = pts;
        return pts;
    }

    /**
     * 把当前生效的帧率下发给 GL 编码器。
     *
     * <p>只设 MediaFormat 的 KEY_FRAME_RATE 是不会降帧的 —— 那对 Surface 输入的编码器
     * 只是码率分配提示。真正决定出帧节奏的是 GL 侧隔多久交换一次缓冲区，
     * 所以两处必须用同一个值，否则设置里选的帧率不会生效。</p>
     */
    private void applyEncoderFrameRate() {
        EglSurfaceEncoder encoder = eglEncoder;
        if (encoder == null) {
            return;  // 还没创建，创建时会再套用一次
        }
        // 节流用上限（可以是 0 = 不限制），不是标称值
        encoder.setFrameRate(frameRateCap);
    }

    /**
     * 设置画质等级
     * @param level 画质等级：0=低, 1=中, 2=高, 3=最高
     */
    public void setQualityLevel(int level) {
        this.qualityLevel = Math.max(0, Math.min(3, level));
        AppLog.d(TAG, "Camera " + cameraId + " quality level set to " + this.qualityLevel);
    }

    /**
     * 设置是否强制使用 H.264 编码器
     * @param force true 表示强制 H.264（兼容性优先），false 表示优先使用 HEVC
     */
    public void setForceH264(boolean force) {
        this.forceH264 = force;
        AppLog.d(TAG, "Camera " + cameraId + " forceH264 = " + force);
    }

    /**
     * 准备录制
     *
     * 警告：此方法包含阻塞操作（CountDownLatch.await），不建议在主线程调用
     * 如果必须在主线程调用，可能导致 ANR。建议在后台线程调用
     *
     * @param filePath 输出文件路径
     * @return 用于 Camera 输出的 SurfaceTexture
     */
    public SurfaceTexture prepareRecording(String filePath) {
        // 从文件路径中提取保存目录和摄像头位置
        File file = new File(filePath);
        String fileName = file.getName();
        int lastUnderscoreIndex = fileName.lastIndexOf('_');
        String position = lastUnderscoreIndex > 0 && fileName.endsWith(".mp4")
                ? fileName.substring(lastUnderscoreIndex + 1, fileName.length() - 4) : "unknown";
        return prepare(file.getParent(), position, fileName);
    }

    /**
     * 准备好、先不开文件：单独接回录像的一路用（2026-10-10）。编码器、EGL、SurfaceTexture 照样建好，
     * 第一个文件等开录时再开（{@link #startRecording}），名字是开录那一刻 —— 准备完还要等相机开好、会话配好、出画面，
     * 按准备那一刻起名的话，名字比画面早好几秒（回放按名字里的时刻摆）。
     *
     * <p>和 {@link #prepareRecording} 一样会阻塞（等 EGL 初始化，最多 5 秒）：不要在主线程上调。</p>
     *
     * @param dir       写到哪个目录（录像此刻实际写在哪个盘上）
     * @param cameraKey 这一路的 key（文件名的后缀按它取）
     * @return 给相机输出的 SurfaceTexture；没准备好是 null（已经放掉）
     */
    public SurfaceTexture prepareWithoutFile(File dir, String cameraKey) {
        return prepare(dir.getAbsolutePath(), CameraSlots.suffixFor(cameraKey), null);
    }

    /**
     * @param dir           写到哪个目录
     * @param position      文件名的后缀（surround / cabinfront / cabinrear）
     * @param firstFileName 第一个文件的名字，在这里开好；null = 开录时再开（{@link #prepareWithoutFile}）
     */
    private SurfaceTexture prepare(String dir, String position, String firstFileName) {
        // 检查是否在主线程调用（可能导致 ANR）
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppLog.w(TAG, "Camera " + cameraId + " WARNING: prepareRecording() called on MAIN THREAD! " +
                    "This may cause ANR due to blocking operations.");
        }

        if (isRecording.get()) {
            AppLog.w(TAG, "Camera " + cameraId + " is already recording");
            return inputSurfaceTexture;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Preparing codec recording: " + width + "x" + height);

        // 保存录制参数（正在写的文件由写入线程开成了才填，见 openFile）
        this.segmentIndex = 0;
        this.recordedFrameCount = 0;
        this.firstFrameTimestampNs = -1;  // 重置时间戳基准
        // 每个文件的 PTS 基准由写入线程在开文件时清（createMuxer）

        // 重置健康检查状态
        this.encoderHealthy = true;

        // 重置排队、换盘状态（写入线程这时还没起来）
        ring.clear();
        backpressure.reset();
        deadVolumes.clear();
        relocations = 0;

        // 清空本次录制的文件列表：写入线程开成一个文件才记一个
        recordedFilePaths.clear();

        this.saveDirectory = dir;
        this.cameraPosition = position;
        this.firstFileAtStart = firstFileName == null;

        try {
            // 创建编码线程
            encoderThread = new HandlerThread("Encoder-" + cameraId) {
                @Override
                protected void onLooperPrepared() {
                    // 降低编码线程优先级，避免与补盲画面渲染竞争资源
                    // THREAD_PRIORITY_BACKGROUND 比 FOREGROUND 更低，给补盲画面留出更多 CPU 时间
                    // 同时保持比 THREAD_PRIORITY_LOWEST 高，确保录制不会掉帧
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    AppLog.d(TAG, "Camera " + cameraId + " 编码线程优先级设置为 BACKGROUND");
                }
            };
            encoderThread.start();
            encoderHandler = new Handler(encoderThread.getLooper());
            StallWatch.watchLooper("Encoder-" + cameraId, encoderHandler);
            StallWatch.watchEncoder(cameraId, encoderBeat);

            // 写入线程：MediaMuxer 只在它上面动（开文件、写、fsync、收文件、换盘、抢救）
            startWriterThread();

            // 创建 MediaCodec 编码器
            createEncoder();

            // 第一个文件：排给写入线程开，在这里等结果。开不了就是开录失败（这一步不换盘，和以前一样）。
            // 单独接回的一路不给名字：开录时再开（openFirstFileNow）
            if (firstFileName != null) {
                openFirstFile(firstFileName);
            }

            // 在编码线程上初始化 EGL 和 SurfaceTexture（重要：必须在同一线程上）
            // 使用 CountDownLatch 等待初始化完成
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final int[] resultTextureId = {0};
            final Exception[] initException = {null};

            encoderHandler.post(() -> {
                try {
                    // 创建 EGL 渲染器（在编码线程上）
                    eglEncoder = new EglSurfaceEncoder(cameraId, width, height);
                    // 左上角的应用名与版本号。要在 initialize() 之前设好 ——
                    // 那块贴图在 initialize() 里画一次（每一路都画，和时间水印无关），之后不再重画
                    eglEncoder.setBrandLine(brandLine);
                    // 行驶信息条也要在 initialize() 之前：画面区域按它的高让出来
                    eglEncoder.setInfoBar(infoBar);
                    // setFrameRate 通常在 prepareRecording 之前就调用了，这里补上
                    applyEncoderFrameRate();
                    resultTextureId[0] = eglEncoder.initialize(encoderInputSurface);
                    textureId = resultTextureId[0];

                    // 创建 SurfaceTexture 供 Camera 输出（在编码线程上，绑定到 EGL context）
                    inputSurfaceTexture = new SurfaceTexture(textureId);
                    // 相机按源尺寸出帧；四宫格模式下与编码尺寸不同
                    inputSurfaceTexture.setDefaultBufferSize(sourceWidth, sourceHeight);

                    // 设置帧可用回调（在编码线程上）
                    // 直接在回调中处理帧，避免 Handler 死锁
                    inputSurfaceTexture.setOnFrameAvailableListener(surfaceTexture -> {
                        if (isReleased) {
                            return;
                        }
                        // 编码线程接到了一帧（录不录都算）：卡顿监测靠它判断录制这一路有没有断流
                        encoderBeat.beat(StallWatch.now());

                        try {
                            // 关键修复：即使不在录制状态，也必须调用 updateTexImage() 消费帧
                            // 否则 SurfaceTexture 会保持 pending 状态，不再触发后续回调
                            // updateTexImage 在 drawFrame 内部调用，这里单独处理非录制状态
                            if (!isRecording.get()) {
                                // 不在录制状态时，仍需消费帧以保持 SurfaceTexture 正常工作
                                if (eglEncoder != null && eglEncoder.isInitialized()) {
                                    eglEncoder.consumeFrame();  // 只消费帧，不编码
                                }
                                return;
                            }

                            // 检查编码器健康状态，不健康时只消费帧不编码
                            if (!encoderHealthy) {
                                if (eglEncoder != null && eglEncoder.isInitialized()) {
                                    eglEncoder.consumeFrame();  // 只消费帧，等待重建
                                }
                                return;
                            }

                            // 写入线程跟不上、排队满了：等一小会儿，还满就不编这一帧（见 admitFrame）。
                            // 丢在相机这一侧，和以前写文件卡住时一样；已经编码的一个都不丢
                            if (!admitFrame()) {
                                if (eglEncoder != null && eglEncoder.isInitialized()) {
                                    eglEncoder.consumeFrame();
                                }
                                return;
                            }

                            // 获取绝对时间戳（系统启动以来的纳秒）
                            long absoluteTimestampNs = surfaceTexture.getTimestamp();
                            
                            // 计算相对时间戳（以第一帧为基准）
                            // 注意：firstFrameTimestampNs 在整个录制期间不重置
                            // 因为 eglPresentationTimeANDROID 需要单调递增的时间戳
                            // 否则 GraphicBufferSource 会拒绝帧
                            if (firstFrameTimestampNs < 0) {
                                firstFrameTimestampNs = absoluteTimestampNs;
                                AppLog.d(TAG, "Camera " + cameraId + " First frame timestamp: " + absoluteTimestampNs + " ns");
                            }
                            long relativeTimestampNs = absoluteTimestampNs - firstFrameTimestampNs;

                            // 直接渲染帧到编码器（使用相对时间戳）
                            if (eglEncoder != null && eglEncoder.isInitialized()) {
                                long drawStart = StallWatch.now();
                                eglEncoder.drawFrame(relativeTimestampNs);
                                StallWatch.noteOp(encoderBeat, cameraId, "draw", drawStart);
                                recordedFrameCount++;
                                framesThisEncoder++;
                                framesSinceLastDrain++;
                            }

                            // 自适应 drain 控制：根据时间间隔决定是否 drain
                            // 优化：使用更激进的批量策略，减少系统调用开销
                            // 间隔按开机时长算：车机睡醒后墙上时间会跳 18–23 秒，按墙上时间算会一阵不按时 drain
                            long currentTimeMs = SystemClock.elapsedRealtime();
                            // 优化：增加帧数阈值到 10 帧，进一步减少 drain 次数
                            if (currentTimeMs - lastDrainTimeMs >= currentDrainIntervalMs || framesSinceLastDrain >= 10) {
                                // 从编码器取出输出、拷出来排给写入线程
                                long drainStart = StallWatch.now();
                                boolean hadOutput = drainEncoderWithResult(false);
                                StallWatch.noteOp(encoderBeat, cameraId, "drain", drainStart);
                                lastDrainTimeMs = currentTimeMs;
                                framesSinceLastDrain = 0;
                                
                                // 调整 drain 间隔：有输出时缩短间隔，无输出时延长间隔
                                // 优化：使用更平滑的调整策略
                                if (hadOutput) {
                                    currentDrainIntervalMs = Math.max(DRAIN_INTERVAL_MIN_MS, currentDrainIntervalMs - 1);
                                } else {
                                    currentDrainIntervalMs = Math.min(DRAIN_INTERVAL_MAX_MS, currentDrainIntervalMs + 2);
                                }
                            }

                        } catch (Exception e) {
                            // 只记这一轮坏掉的第一次：坏了之后每一帧都会再抛一次，逐帧记会把别的日志全冲掉
                            if (encoderHealthy) {
                                AppLog.e(TAG, "Camera " + cameraId + " Error processing frame", e);
                                noteTrouble("frame", e);
                            }
                            encoderHealthy = false;
                        }
                    }, encoderHandler);

                    // 设置 EGL 渲染器的输入
                    eglEncoder.setInputSurfaceTexture(inputSurfaceTexture);
                    if (fourLanePlan != null) {
                        eglEncoder.setFourLanePlan(fourLanePlan, fourLaneOrder);
                    }

                    // 设置时间水印（如果启用）。只建右上角时间那一块；左上角应用名上面 initialize() 已经建好
                    if (watermarkEnabled) {
                        eglEncoder.setWatermarkEnabled(true);
                        // 规格行必须在这里再补一次。createEncoder() 里算好 encoderSpecLine
                        // 之后也调过 applyWatermarkInfoLine()，但那时 eglEncoder 还是 null
                        // —— 它是在本 runnable 里才 new 出来的 —— 所以那次是空转。
                        // 不补的话第一段没有规格行，从第二段起才有：那时 eglEncoder 已经存在了。
                        applyWatermarkInfoLine();
                    }

                    AppLog.d(TAG, "Camera " + cameraId + " EGL/SurfaceTexture initialized on encoder thread, textureId=" + textureId + ", watermark=" + watermarkEnabled);

                } catch (Exception e) {
                    AppLog.e(TAG, "Camera " + cameraId + " Failed to initialize EGL on encoder thread", e);
                    initException[0] = e;
                } finally {
                    latch.countDown();
                }
            });

            // 等待初始化完成（最多 5 秒）
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new RuntimeException("Timeout waiting for EGL initialization");
            }

            // 检查是否有初始化错误
            if (initException[0] != null) {
                throw initException[0];
            }

            AppLog.d(TAG, "Camera " + cameraId + " Codec recording prepared, textureId=" + textureId);

            return inputSurfaceTexture;

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to prepare codec recording", e);
            release();
            if (callback != null) {
                callback.onRecordError(cameraId, e.getMessage());
            }
            return null;
        }
    }

    /**
     * 开始录制
     */
    public boolean startRecording() {
        if (encoder == null || eglEncoder == null) {
            AppLog.e(TAG, "Camera " + cameraId + " Encoder not prepared");
            return false;
        }

        if (isRecording.get()) {
            AppLog.w(TAG, "Camera " + cameraId + " Already recording");
            return false;
        }
        if (stopRequested) {
            // 叫停过的录制器不再开（停录和开录任务交错时）：要录就是新的一次、新的录制器
            AppLog.w(TAG, "Camera " + cameraId + " already stopped, not starting again");
            return false;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Starting codec recording");

        // 单独接回的一路：第一个文件现在才开，名字是此刻 —— 画面从这一刻起才往里编。
        // 排在置「在录」之前：开文件那条命令要排在第一帧编出来的开轨之前
        if (firstFileAtStart) {
            firstFileAtStart = false;
            openFirstFileNow();
        }

        // 重置首次写入状态；写不进文件的裁判从此刻起算
        hasFirstWrite = false;
        startedUptimeMs = android.os.SystemClock.uptimeMillis();

        everStarted = true;
        isRecording.set(true);
        // 从现在起盯着这一路；录制 Surface 要等会话重建后才出帧，宽限期够它建好
        encoderBeat.arm(StallWatch.now(), StallRules.ARM_GRACE_MS);
        // 写入线程另外盯：卡在一次写 / fsync / 收文件里太久就记一份报告（闲着不算）
        writerBeat.arm(StallWatch.now(), 0L);

        // 注意：不再使用单独的编码循环
        // 帧的处理直接在 onFrameAvailable 回调中完成（该回调在 encoderHandler 上执行）
        // 这样避免了 Handler 死锁问题

        // 【重要】分段定时器延迟到首次写入后启动
        // 这样可以确保：
        // 1. 摄像头启动慢或需要修复时，用户只会感觉"启动慢"而不是录制空视频
        // 2. 钉钉指定时长录制时，实际录制时长是有效的
        // scheduleNextSegment() 在第一笔数据写进文件时调用（markWritten）

        // 写不进文件的裁判（唯一的一个）：15 秒没写出新数据就报一次
        scheduleWriteStallCheck();

        // 启动编码器健康检查
        scheduleEncoderHealthCheck();

        // 每 5 秒 fsync 一次的定时在写入线程上，写入线程起来时就排好了（startWriterThread）

        if (callback != null && segmentIndex == 0) {
            callback.onRecordStart(cameraId);
        }

        AppLog.d(TAG, "Camera " + cameraId + " Codec recording started");
        return true;
    }

    /**
     * 停止录制（只停这一路）：叫停，再等它收好，期限 {@link #STOP_BUDGET_MS}。
     * 几路一起停时 {@code MultiCameraManager} 分开调：先每一路都叫停（{@link #beginStop}），再一起等 ——
     * 先等编码线程都排空（{@link #awaitDrain}），再等写入线程都收好（{@link #finishStop}），共用一个期限。
     */
    public void stopRecording() {
        beginStop();
        finishStop(SystemClock.uptimeMillis() + STOP_BUDGET_MS);
    }

    /**
     * 停录第一步：叫停，不等（几路一起停时，先每一路都叫停，再一起等 —— 以前一路一路停，
     * 后面几路在等前面的时候还在录，可能跨过分段又开出新文件，三路加起来还超过协调器的收拾期限）。
     *
     * <p>不再录、不再分段切换 / 恢复 / 重建（{@link #stopRequested}），定时器都撤掉；编码线程上排空编码器
     * （结束流，等到最后一帧），把「收文件」排在最后一个样本后面。没开过录的什么都不做，文件留给释放收。</p>
     */
    public void beginStop() {
        if (stopRequested) {
            return;
        }
        stopRequested = true;
        // 立即标记停止状态，防止新帧处理
        isRecording.set(false);
        encoderBeat.disarm();

        // 取消所有定时器和任务
        Handler segment = segmentHandler;
        if (segment != null) {
            if (segmentRunnable != null) {
                segment.removeCallbacks(segmentRunnable);
            }
            if (recoveryRunnable != null) {
                segment.removeCallbacks(recoveryRunnable);
            }
            if (healthCheckRunnable != null) {
                segment.removeCallbacks(healthCheckRunnable);
            }
        }
        segmentRunnable = null;
        recoveryRunnable = null;
        healthCheckRunnable = null;
        recoveryAttempts = 0;
        cancelWriteStallCheck();

        if (!everStarted) {
            return;
        }
        AppLog.d(TAG, "Camera " + cameraId + " Stopping codec recording");

        // 收尾的顺序靠排队保证：编码线程排空编码器、把「收文件」排在最后一个样本后面；
        // 写入线程写完排着的，写文件尾、fsync、关掉（没确认落盘就抢救），这个文件才算完
        final CloseFile close = new CloseFile("stop", -1);
        final CountDownLatch drained = new CountDownLatch(1);
        stopClose = close;
        stopDrained = drained;
        Runnable drain = () -> {
            try {
                // 分段切换、恢复刚好在叫停之前把「在录」置回去的话（它们在这条线程上、排在这一步前面），这里再关一次
                isRecording.set(false);
                // 稍等一下让正在处理的帧完成
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // 发送结束信号给编码器
                if (encoder != null) {
                    try {
                        encoder.signalEndOfInputStream();
                        // 排空编码器，一直到它交出最后一帧（有期限）：剩下的样本排给写入线程
                        drainEncoder(true);
                    } catch (Exception e) {
                        AppLog.e(TAG, "Camera " + cameraId + " Error signaling end of stream", e);
                    }
                }
                // 排队满过的那几段，没记进黑匣子的补上
                noteBackpressure(backpressure.flush(SystemClock.elapsedRealtime()));
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " Error in stopRecording on encoder thread", e);
            } finally {
                queueClose(close);
                drained.countDown();
            }
        };
        Handler encoderLooper = encoderHandler;
        if (encoderLooper == null || !encoderLooper.post(drain)) {
            queueClose(close);
            drained.countDown();
        }
    }

    /**
     * 停录第二步的前一半：等编码线程排空（最后一帧排给写入线程、「收文件」排在它后面），最多到 deadlineMs
     * （开机时长，不含深睡；几路共用一个）。编码线程这一步不碰盘，正常几十毫秒。到点还没排空的不等了：
     * 剩下的几帧不要，「收文件」这就排上。几路一起停时每一路都先过这一步、再一起等收文件 ——
     * 到点没排空的几路，收文件都在期限那一刻排上，谁也不用等前面那几路收完文件才排上。
     */
    public void awaitDrain(long deadlineMs) {
        CloseFile close = stopClose;
        CountDownLatch drained = stopDrained;
        if (close == null || drained == null) {
            return;  // 没开过录，或者已经等过了
        }
        stopDrained = null;
        if (!awaitUntil(drained, deadlineMs)) {
            drainLate = true;
            AppLog.w(TAG, "Camera " + cameraId + " encoder thread did not finish draining by the stop deadline,"
                    + " closing the file without the last frames");
        }
        // 编码线程卡住没排上的话这里补排（只排一次）
        queueClose(close);
    }

    /**
     * 停录第二步的后一半：等写入线程写完、写文件尾、fsync、关好，最多到 deadlineMs（和前一半同一个期限；
     * 单停一路时前一半也在这里做）。编码线程拖到了期限、写入线程却没卡住的 —— 收文件是到点才排上的 ——
     * 期限之后再给 {@link #STOP_GRACE_MS}，不然好好的写入线程会因为编码线程慢而被放弃。
     * 到点还没收好（盘多半没了，或者极慢）就不等了（{@link #abandonWriter}）。然后验证文件、报停。
     */
    public void finishStop(long deadlineMs) {
        CloseFile close = stopClose;
        if (close == null) {
            return;  // 没开过录（文件留给释放收），或者已经收拾过
        }
        awaitDrain(deadlineMs);
        stopClose = null;

        // 等写入线程写完、收好文件，最多到点（上面说的那种多给一会儿）
        long closeDeadlineMs = drainLate && !writerStuck() ? deadlineMs + STOP_GRACE_MS : deadlineMs;
        boolean finished = awaitUntil(close.done, closeDeadlineMs);
        if (!finished) {
            abandonWriter("stop");
        }
        writerBeat.disarm();

        // 验证并清理所有录制的文件。没收好的那个文件还在写入线程手里，不碰（它收好时自己验证，见 closeFile）
        List<String> deletedFiles = validateAndCleanupAllFiles(finished ? null : heldFilePath);

        AppLog.d(TAG, "Camera " + cameraId + " Codec recording stopped, frames recorded: " + recordedFrameCount);

        if (callback != null) {
            callback.onRecordStop(cameraId);
            // 通知损坏文件被删除
            if (!deletedFiles.isEmpty()) {
                callback.onCorruptedFilesDeleted(cameraId, deletedFiles);
            }
        }

        synchronized (recordedFilePaths) {
            stoppedFiles = new ArrayList<>(recordedFilePaths);
            recordedFilePaths.clear();
        }
    }

    /**
     * 这一次停录收掉的文件：{@link #finishStop} 结束时还在这个录制器手里的那几个 —— 最后一个文件、重建 / 恢复时收掉的、
     * 抢救出来的；太小删掉了的也在单子上（用的时候看在不在）。分段切换收好的那几个当时就报出去了
     * （{@link RecordCallback#onSegmentSwitch}，中转写入那时就转存），不在里面。
     *
     * <p>一路单独离开录像时，中转写入只转存这几个（{@code MultiCameraManager}）：缓存目录里别的路还在写。</p>
     */
    public List<String> stoppedFiles() {
        return stoppedFiles;
    }

    /**
     * 停录放弃等写入线程时它手里还开着的那个文件（还没写完、没收好）；没放弃过是 null。
     * 中转写入停录时转存缓存里的文件要跳过它，半个文件转过去就坏了。
     */
    public String heldFile() {
        return writerAbandoned ? heldFilePath : null;
    }

    /**
     * 释放资源
     */
    public void release() {
        if (isReleased) {
            return;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Releasing CodecVideoRecorder");

        isReleased = true;
        encoderBeat.disarm();

        // 还没叫停的先停（单独释放这一路时）；叫停了、还没等它收好的，在这里等
        if (!stopRequested) {
            stopRecording();
        } else if (stopClose != null) {
            finishStop(SystemClock.uptimeMillis() + STOP_BUDGET_MS);
        }

        // 释放 EGL 渲染器：排到编码线程上拆，等它拆完（见 releaseGlOnEncoderThread）。
        // 拆完之后不会再有帧去画，下面收信息条位图才不会跟 drawFrame 撞上
        releaseGlOnEncoderThread();
        if (infoBar != null) {
            infoBar.recycle();
            infoBar = null;
        }

        // 释放缓存的录制 Surface（必须在 SurfaceTexture 之前释放）
        if (cachedRecordSurface != null) {
            cachedRecordSurface.release();
            cachedRecordSurface = null;
        }

        // 释放 SurfaceTexture
        if (inputSurfaceTexture != null) {
            inputSurfaceTexture.release();
            inputSurfaceTexture = null;
        }

        // 释放编码器
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception e) {
                // Ignore
            }
            encoder.release();
            encoder = null;
        }

        // 释放编码器输入 Surface
        if (encoderInputSurface != null) {
            encoderInputSurface.release();
            encoderInputSurface = null;
        }

        // 写入线程：还开着的文件在这里收（开录没起来、停录前就不在录的那几种），排着的做完，退出
        stopWriter();

        // 停止编码线程。卡顿监测按这一个录制器的撤：同一路新的录制器可能已经登记了
        StallWatch.unwatchEncoder(cameraId, encoderBeat);
        if (encoderThread != null) {
            StallWatch.unwatchLooper("Encoder-" + cameraId, encoderHandler);
            encoderThread.quitSafely();
            try {
                encoderThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            encoderThread = null;
            encoderHandler = null;
        }

        // 清理分段处理线程
        if (segmentHandler != null) {
            segmentHandler.removeCallbacksAndMessages(null);
        }
        if (segmentThread != null) {
            segmentThread.quitSafely();
            try {
                segmentThread.join(1000);  // 1秒超时
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AppLog.w(TAG, "Camera " + cameraId + " segment thread join interrupted");
            }
            segmentThread = null;
        }
        segmentHandler = null;

        AppLog.d(TAG, "Camera " + cameraId + " CodecVideoRecorder released");
    }

    /** 等编码线程拆完 GL 最多多久。正常一帧之内就拆完；编码线程卡住时多等也没用（和下面等线程退出同一个数）。 */
    private static final long GL_TEARDOWN_WAIT_MS = 1000L;

    /**
     * 拆 EGL 渲染器（上下文、着色器、贴图）—— 排到编码线程上拆，这里最多等 {@link #GL_TEARDOWN_WAIT_MS}。
     *
     * <p>为什么非得在编码线程上：EGL 上下文是在编码线程上建的，每一帧也在那里画，它只在那个线程上是「当前」的。
     * 以前在调用者线程上直接 {@code eglEncoder.release()}：那个线程没有当前上下文，里面的 glDelete* 全是空操作；
     * eglDestroyContext 碰上一个还在编码线程上当前的上下文，也只是记成「待删」，等编码线程放开才真释放。
     * 而且拆的同时编码线程可能正在 drawFrame，两边一起动同一套 EGL/GL 对象。</p>
     *
     * <p>排在编码线程的队列里：前面正在画的那一帧、stopRecording 收文件那一步都先做完；拆完之后再来的帧，
     * 帧回调看到 isReleased 就返回，不会再画。prepareRecording 失败走到这里时也一样 ——
     * 初始化那一步就算还没跑完，拆的这一步也排在它后面。</p>
     *
     * <p>等不到（编码线程卡在写盘之类的地方）就不等了，接着放后面的东西。拆这一步仍排在编码线程上，
     * 线程一空就做（下面的 quitSafely 会先把已经排着的做完），不在这里另拆一遍 ——
     * 在这个线程上拆既拆不干净，又会跟卡住的那一帧抢。</p>
     */
    private void releaseGlOnEncoderThread() {
        Handler handler = encoderHandler;
        if (handler == null) {
            return;  // 编码线程没建起来：EGL 渲染器只在编码线程上建，也就没有可拆的
        }
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        handler.post(() -> {
            try {
                EglSurfaceEncoder egl = eglEncoder;
                eglEncoder = null;
                if (egl != null) {
                    egl.release();
                }
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(GL_TEARDOWN_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                AppLog.w(TAG, "Camera " + cameraId + " encoder thread busy for " + GL_TEARDOWN_WAIT_MS
                        + "ms, GL teardown stays queued on it");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取录制用的 Surface（供 Camera 使用）
     * 使用缓存模式避免重复创建 Surface 导致内存泄漏
     */
    public Surface getRecordSurface() {
        if (inputSurfaceTexture == null) {
            return null;
        }
        
        // 检查缓存的 Surface 是否有效
        if (cachedRecordSurface != null && cachedRecordSurface.isValid()) {
            return cachedRecordSurface;
        }
        
        // 释放旧的无效 Surface
        if (cachedRecordSurface != null) {
            AppLog.d(TAG, "Camera " + cameraId + " releasing invalid cached record surface");
            cachedRecordSurface.release();
            cachedRecordSurface = null;
        }
        
        // 创建新的 Surface 并缓存
        cachedRecordSurface = new Surface(inputSurfaceTexture);
        AppLog.d(TAG, "Camera " + cameraId + " created new record surface");
        return cachedRecordSurface;
    }

    /**
     * 检查是否正在录制
     */
    public boolean isRecording() {
        return isRecording.get();
    }

    /**
     * 这一路算不算在录：开过录、没叫停（2026-10-10）。
     *
     * <p>和 {@link #isRecording} 不同：那个在分段切换、快速恢复、重建编码器的那一下是 false。
     * 一路离开录像时要数「还有没有别的路在录」，按那个数，别的路正好在切段就会被当成没在录，整次停录。</p>
     */
    public boolean isLive() {
        return everStarted && !stopRequested;
    }

    /** 正在写的文件（写入线程开成了才有）；没有是 null。黑匣子用。 */
    public String currentFile() {
        return currentFilePath;
    }

    /**
     * 共用的分段时间戳刚生成了一个新的（别的路在切段，{@code MultiCameraManager} 叫）：这一路的名字要是还和别的路错开着
     * （单独接回的那一路，{@link #offCycleName}），马上跟着切，拿同一个名字（{@link RecordingLanes#alignsNow}）。
     * 在编码线程上判、切（和分段计时到点切的是同一个 {@link #switchToNextSegment}）；名字本来就对得上的什么都不做。
     */
    public void switchNow() {
        Handler encoderLooper = encoderHandler;
        if (!offCycleName || encoderLooper == null) {
            return;
        }
        encoderLooper.post(() -> {
            if (RecordingLanes.alignsNow(offCycleName, isRecording.get(), stopRequested)) {
                AppLog.d(TAG, "Camera " + cameraId + " joined off-cycle, switching to the shared segment name");
                StallWatch.runTask(encoderBeat, cameraId, "segment-align", this::switchToNextSegment);
            }
        });
    }

    /**
     * 黑匣子里用的一行现状：录制器自己以为在不在录、编码器好不好、写到哪个文件、
     * 写入排队排了多少（最深到过多少、满过几次、因此丢了几帧相机帧）、写入线程在忙什么、最近一次错在哪。
     * 不加锁读，拿到的是近似值。
     */
    public String describeWriteState() {
        String writerTask = writerBeat.task();
        String file = currentFilePath;
        return "recording=" + isRecording.get() + " encoderHealthy=" + encoderHealthy
                + " muxerStarted=" + muxerStarted + " recoveryAttempts=" + recoveryAttempts
                + " segment=" + segmentIndex
                + " file=" + (file == null ? "none" : new File(file).getName())
                + " dir=" + saveDirectory + " relocations=" + relocations
                + " queue=" + ring.queuedSamples() + "/" + ring.queuedMs() + "ms/" + megabytes(ring.queuedBytes()) + "MB"
                + " queueHigh=" + ring.highMs() + "ms/" + megabytes(ring.highBytes()) + "MB"
                + " queueCap=" + SampleRing.QUEUE_MS + "ms/" + megabytes(ring.queueCapBytes()) + "MB"
                + " queueFull=" + backpressure.totalEpisodes() + "x/" + backpressure.totalDropped() + "frames"
                + " writer=" + (writerTask == null ? "idle"
                        : writerTask + " " + writerBeat.taskAgeMs(StallWatch.now()) + "ms")
                + " ring=" + (ring.keptMs() / 1000) + "s/" + (ring.keptBytes() >> 20) + "MB"
                + (lastTrouble.isEmpty() ? "" : " lastTrouble=" + lastTrouble);
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / (1024f * 1024f));
    }

    private static String seconds(long ms) {
        return String.format(Locale.US, "%.1f", ms / 1000f);
    }

    /** 记下出错在哪一步、系统怎么说的，并进黑匣子。 */
    private void noteTrouble(String step, Throwable t) {
        String what = describe(t);
        lastTrouble = step + ": " + what;
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像出错（相机 " + cameraId + "，" + step + "）：" + what);
    }

    /**
     * 一个异常写成一行：类名、消息、编解码器给的错误码和说明、起因、出在我们哪个方法。
     *
     * <p>编解码器的错误要把错误码和「能不能恢复」带上：被系统收回（资源不够、给了别人）
     * 和编码器自己坏了，处理办法完全不同。</p>
     */
    static String describe(Throwable t) {
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        if (t instanceof MediaCodec.CodecException) {
            MediaCodec.CodecException ce = (MediaCodec.CodecException) t;
            sb.append(" [code=").append(ce.getErrorCode())
                    .append(" transient=").append(ce.isTransient())
                    .append(" recoverable=").append(ce.isRecoverable())
                    .append(' ').append(ce.getDiagnosticInfo()).append(']');
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append(" <- ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
        }
        for (StackTraceElement frame : t.getStackTrace()) {
            if (frame.getClassName().startsWith("com.kooo")) {
                sb.append(" @").append(frame.getMethodName()).append(':').append(frame.getLineNumber());
                break;
            }
        }
        String text = sb.toString().replace('\n', ' ');
        return text.length() > 400 ? text.substring(0, 400) + "..." : text;
    }

    // ===== 私有方法 =====

    /**
     * 创建 MediaCodec 编码器
     * 优先尝试 HEVC (H.265)，如果不支持则回退到 H.264
     */
    private void createEncoder() throws IOException {
        // 新编码器：等它报输出格式，开轨要用；健康检查从这一代画的第一帧数起
        formatSeen = false;
        framesThisEncoder = 0;
        csdInFormat = false;
        // 检测并选择最优编码格式（forceH264 开启时固定 H.264）
        mimeType = selectBestEncoder();

        int effectiveFrameRate = frameRate;

        // 码率只有一条公式（TargetBitrate），两种编码都走它 —— 它自己知道 H.264
        // 要的比 HEVC 多。以前兼容模式走另一条公式，于是「强制 H.264」这个为了
        // 兼容而存在的开关，反而成了码率最高、画质最好的那条路。
        int effectiveBitrate = calculateOptimalBitrate();
        // 写入排队的字节上限跟着这个码率走（SampleRing.queueBytesFor）
        ring.setQueueCapBytes(SampleRing.queueBytesFor(effectiveBitrate));

        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, effectiveFrameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

        if (!forceH264) {
            // HEVC/H.264 优化路径：附加 Profile/Level 以获得更好效率
            if (mimeType.equals(MIME_TYPE_HEVC)) {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
                // KEY_LEVEL 故意不设。原来写死 HEVC Level 4，而 Level 4 的最大画面是
                // 2,228,224 个亮度采样（≈1920×1080），环视四宫格是 6,579,200 个 ——
                // 超了三倍。声明一个装不下这幅画的 Level，有的编码器会照着那个 Level
                // 的限制去夹自己的码率控制。不声明，编码器按实际尺寸和码率自己定。
            } else {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            }
        }
        // forceH264 开启：不设置 Profile/Level，走 v1.2.4 兼容路径，避免车机硬件 configure 失败

        // 编码器创建：兼容模式用 createEncoderByType；优化模式优先选择硬件编码器
        if (forceH264) {
            encoder = MediaCodec.createEncoderByType(mimeType);
        } else {
            encoder = createHardwareEncoder(mimeType);
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        encoderInputSurface = encoder.createInputSurface();
        encoder.start();

        bufferInfo = new MediaCodec.BufferInfo();

        AppLog.d(TAG, "Camera " + cameraId + " Encoder created: " + width + "x" + height +
                " @ " + effectiveFrameRate + "fps" +
                ", " + (effectiveBitrate / 1000) + " Kbps, " +
                (mimeType.equals(MIME_TYPE_HEVC) ? "HEVC" : "H.264") +
                (forceH264 ? " [兼容模式]" : ""));

        // 同一批数字也送给角标第二行
        specSizeText = width + "x" + height;
        specCodecText = mimeType.equals(MIME_TYPE_HEVC) ? "H.265" : "H.264";
        nominalFrameRate = effectiveFrameRate;
        // 实测值不清零：换分段会重建编码器，而相机的出帧率不会因此改变。
        // 清零的话每段开头都要重新等一秒，角标先闪回「~标称值」再跳回来。
        rebuildSpecLine();
    }

    /**
     * 选择最优编码器类型
     * 优先使用 HEVC (H.265)，如果不支持则回退到 H.264
     * 优化：优先选择硬件编码器，性能更好
     */
    private String selectBestEncoder() {
        // 用户强制 H.264：兼容部分车型（避免 HEVC 在车机硬件上的闪烁/configure 失败）
        if (forceH264) {
            AppLog.i(TAG, "Camera " + cameraId + " force H.264 encoder (user setting)");
            return MIME_TYPE_H264;
        }
        try {
            // 检查 HEVC 编码器是否可用
            MediaCodec hevcEncoder = MediaCodec.createEncoderByType(MIME_TYPE_HEVC);
            hevcEncoder.release();
            AppLog.d(TAG, "HEVC encoder available, using H.265 for better efficiency");
            return MIME_TYPE_HEVC;
        } catch (Exception e) {
            AppLog.w(TAG, "HEVC encoder not available, falling back to H.264");
            return MIME_TYPE_H264;
        }
    }
    
    /**
     * 创建编码器，优先使用硬件编码器
     * 优化：通过编码器名称选择硬件编码器，避免软件编码器性能问题
     */
    private MediaCodec createHardwareEncoder(String mimeType) throws IOException {
        // 获取所有支持该类型的编码器
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
        
        MediaCodecInfo bestEncoder = null;
        String bestEncoderName = null;
        
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (!codecInfo.isEncoder()) {
                continue;
            }
            
            String[] supportedTypes = codecInfo.getSupportedTypes();
            boolean supportsMimeType = false;
            for (String type : supportedTypes) {
                if (type.equalsIgnoreCase(mimeType)) {
                    supportsMimeType = true;
                    break;
                }
            }
            
            if (!supportsMimeType) {
                continue;
            }
            
            String name = codecInfo.getName();
            
            // 优先选择硬件编码器（通常名称包含特定关键字）
            // 避免软件编码器（如 c2.android.* 或 OMX.google.*）
            if (name.contains("c2.android") || name.contains("OMX.google")) {
                // 软件编码器，作为备选
                if (bestEncoder == null) {
                    bestEncoder = codecInfo;
                    bestEncoderName = name;
                }
                continue;
            }
            
            // 硬件编码器优先
            bestEncoder = codecInfo;
            bestEncoderName = name;
            AppLog.i(TAG, "Camera " + cameraId + " Selected hardware encoder: " + name);
            break;
        }
        
        if (bestEncoder != null) {
            return MediaCodec.createByCodecName(bestEncoderName);
        }
        
        // 回退到默认方式
        return MediaCodec.createEncoderByType(mimeType);
    }

    /** 公式在 {@link TargetBitrate} 里，设置界面显示的也是它算出来的同一个数。 */
    private int calculateOptimalBitrate() {
        return TargetBitrate.compute(qualityLevel, width, height, frameRate,
                mimeType.equals(MIME_TYPE_HEVC));
    }

    /**
     * 创建 MediaMuxer（写入线程上）。
     *
     * <p>文件由我们自己打开、把描述符交给 muxer（它内部会 dup 一份），这样才能对它 fsync：
     * 写进去不等于落盘，盘掉线时留在系统缓存里的那些就没了（见 {@link SampleRing}）。
     * 每个文件的 PTS 从 0 起（{@link #nextPtsUs}），基准在这里清。</p>
     */
    private void createMuxer(String filePath) throws IOException {
        closeMuxerFile();
        RandomAccessFile file = new RandomAccessFile(filePath, "rw");
        try {
            file.setLength(0);
            muxer = new MediaMuxer(file.getFD(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException | RuntimeException e) {
            try {
                file.close();
            } catch (IOException ignored) {
                // 开都没开成，关不上也无所谓
            }
            new File(filePath).delete();
            throw e;
        }
        muxerFile = file;
        videoTrackIndex = -1;
        muxerStarted = false;
        lastWrittenPtsUs = -1L;
        segmentBasePtsUs = -1L;

        AppLog.d(TAG, "Camera " + cameraId + " Muxer created: " + filePath);
    }

    // 注意：encodingLoop() 方法已被移除
    // 帧处理现在直接在 onFrameAvailable 回调中完成
    // 这样可以避免 Handler 死锁问题

    /**
     * 排空编码器输出（优化版 - 不降低画质）
     * 
     * 优化策略：
     * - 分段切换（endOfStream = false）：批量处理，一次最多 DRAIN_BATCH_SIZE 帧，零超时、不阻塞。
     *   编码器里还在路上的最后 1–3 帧不等，换编码器时丢掉（已知的限制：每段交界少这几帧）
     * - 停录（endOfStream = true）：一直取到编码器交出带结束标记的那一个，最多 {@link #EOS_DRAIN_MS}。
     *   以前也只取 8 帧，最后几帧和结束标记会被截掉；现在编码线程不碰盘，等得起
     * - 捕获 IllegalStateException 并标记编码器不健康
     */
    private void drainEncoder(boolean endOfStream) {
        if (encoder == null) {
            return;
        }

        // 优化：使用零超时非阻塞模式，快速检查是否有输出
        final int TIMEOUT_USEC = endOfStream ? 10000 : 0;  // 结束状态等待，正常状态非阻塞
        // 期限按开机时长（不含深睡）算：和停录那几步一样，睡过去的那一段不算等了
        final long eosDeadlineMs = SystemClock.uptimeMillis() + EOS_DRAIN_MS;
        int processedFrames = 0;  // 本次 drain 已处理帧数

        try {
            while (endOfStream || processedFrames < DRAIN_BATCH_SIZE) {  // 平时批量处理限制；结束流取到底
                if (endOfStream && SystemClock.uptimeMillis() >= eosDeadlineMs) {
                    AppLog.w(TAG, "Camera " + cameraId + " encoder gave no end of stream within "
                            + EOS_DRAIN_MS + "ms, " + processedFrames + " frames drained");
                    break;
                }
                int outputBufferIndex;
                try {
                    outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                } catch (IllegalStateException e) {
                    // 编码器处于无效状态，标记为不健康
                    if (encoderHealthy) {
                        AppLog.e(TAG, "Camera " + cameraId + " Encoder in invalid state during dequeueOutputBuffer", e);
                        noteTrouble("dequeue", e);
                    }
                    encoderHealthy = false;
                    return;
                }

                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) {
                        break;  // 没有数据了，快速返回
                    }
                    // 结束流：最后一帧和结束标记还没出来，接着等（到期限为止）
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 输出格式变化：交给写入线程添加视频轨道
                    if (formatSeen) {
                        AppLog.w(TAG, "Camera " + cameraId + " Format changed twice");
                    } else {
                        onEncoderFormat(encoder.getOutputFormat());
                    }
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferIndex);

                    if (encodedData == null) {
                        AppLog.e(TAG, "Camera " + cameraId + " Encoder output buffer " + outputBufferIndex + " was null");
                    } else if (skipAsSample(bufferInfo.flags)) {
                        // 单独的参数集，开轨时已随输出格式交给 muxer（见 skipAsSample）
                        bufferInfo.size = 0;
                    }

                    if (bufferInfo.size != 0) {
                        if (!formatSeen) {
                            AppLog.e(TAG, "Camera " + cameraId + " Encoder gave data before its output format");
                        } else {
                            // 拷出来排给写入线程，缓冲区下面马上还给编码器
                            queueSample(encodedData, bufferInfo);

                            processedFrames++;  // 增加已处理帧计数
                        }
                    }

                    try {
                        encoder.releaseOutputBuffer(outputBufferIndex, false);
                    } catch (IllegalStateException e) {
                        if (encoderHealthy) {
                            AppLog.e(TAG, "Camera " + cameraId + " Encoder in invalid state during releaseOutputBuffer", e);
                            noteTrouble("release-output", e);
                        }
                        encoderHealthy = false;
                        return;
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;  // 流结束
                    }
                }
            }
        } catch (Exception e) {
            if (encoderHealthy) {
                AppLog.e(TAG, "Camera " + cameraId + " Unexpected error in drainEncoder", e);
                noteTrouble("drain", e);
            }
            encoderHealthy = false;
        }
    }

    /**
     * 排空编码器输出（带返回值）
     * @param endOfStream 是否结束流
     * @return 是否有输出数据
     */
    private boolean drainEncoderWithResult(boolean endOfStream) {
        if (encoder == null) {
            return false;
        }

        final int TIMEOUT_USEC = 10000;
        boolean gotOutput = false;

        try {
            while (true) {
                int outputBufferIndex;
                try {
                    outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                } catch (IllegalStateException e) {
                    if (encoderHealthy) {
                        AppLog.e(TAG, "Camera " + cameraId + " Encoder in invalid state during dequeueOutputBuffer", e);
                        noteTrouble("dequeue", e);
                    }
                    encoderHealthy = false;
                    return false;
                }

                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) {
                        break;
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!formatSeen) {
                        onEncoderFormat(encoder.getOutputFormat());
                    }
                    gotOutput = true;
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferIndex);

                    if (encodedData != null && bufferInfo.size != 0) {
                        if (formatSeen) {
                            // 拷出来排给写入线程，缓冲区下面马上还给编码器
                            queueSample(encodedData, bufferInfo);

                            gotOutput = true;
                        }
                    }

                    try {
                        encoder.releaseOutputBuffer(outputBufferIndex, false);
                    } catch (IllegalStateException e) {
                        if (encoderHealthy) {
                            AppLog.e(TAG, "Camera " + cameraId + " Encoder in invalid state during releaseOutputBuffer", e);
                            noteTrouble("release-output", e);
                        }
                        encoderHealthy = false;
                        return gotOutput;
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            if (encoderHealthy) {
                AppLog.e(TAG, "Camera " + cameraId + " Error in drainEncoderWithResult", e);
                noteTrouble("drain", e);
            }
            encoderHealthy = false;
        }

        return gotOutput;
    }

    /**
     * 调度下一段录制
     * 
     * 注意：分段时长需要加上补偿时间，因为：
     * 1. 编码器初始化需要时间
     * 2. 停止时需要排空编码器缓冲区
     * 3. 这样可以确保实际录制的视频时长达到设定的分段时长
     */
    private void scheduleNextSegment() {
        if (stopRequested || segmentHandler == null) {
            return;  // 叫停了：不再分段
        }
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
        }

        segmentRunnable = () -> {
            if (isRecording.get() && !stopRequested && encoderHandler != null) {
                AppLog.d(TAG, "Camera " + cameraId + " Scheduling segment switch on encoder thread");
                // 在编码线程上执行切换，避免线程冲突
                encoderHandler.post(() -> StallWatch.runTask(encoderBeat, cameraId, "segment-switch", this::switchToNextSegment));
            }
        };

        // 延迟执行（使用配置的分段时长）
        long actualDelayMs = segmentDurationMs;
        segmentHandler.postDelayed(segmentRunnable, actualDelayMs);
        AppLog.d(TAG, "Camera " + cameraId + " Scheduled next segment in " + (segmentDurationMs / 1000) + " seconds (actual delay: " + actualDelayMs + "ms)");
    }

    /**
     * 切换到下一段（在编码线程上执行）
     * 
     * 采用简单方案：完整停止当前录制，然后重新开始
     * 类似 MediaRecorder 的方式，虽然会丢失几帧，但更简单可靠
     * 
     * 快速恢复机制：
     * - 成功时：重置恢复计数器，调度正常的1分钟定时器
     * - 失败时：使用5秒快速重试，最多重试6次（30秒内），之后回到正常1分钟间隔
     */
    private void switchToNextSegment() {
        // 检查是否仍在录制状态（防止与 stopRecording 竞态）
        if (!isRecording.get() || isReleased || stopRequested) {
            AppLog.w(TAG, "Camera " + cameraId + " Skipping segment switch (not recording, stopping or released)");
            return;
        }
        
        AppLog.d(TAG, "Camera " + cameraId + " Starting segment switch on encoder thread");
        
        boolean switchSuccess = false;
        
        try {
            // 1. 收这一段、换编码器：排空编码器，把「收文件」排在这一段最后一个样本后面。
            //    写入线程写完、收好之后才验证这个文件、报「这一段完成」（中转写入靠它转存，收好之前转存会拿到半个文件）
            stopRecordingForSegmentSwitch();

            // 不重置 firstFrameTimestampNs，保持 EGL 时间戳单调递增；每个文件的 PTS 基准写入线程开文件时清

            // 换编码器这一阵里叫停了：不再开下一个文件，也不再置回「在录」。收文件（停录）排在这一步后面
            if (stopRequested) {
                AppLog.d(TAG, "Camera " + cameraId + " stop requested during segment switch, no next file");
                return;
            }

            // 2. 开下一个文件（写入线程上开，这个盘开不了它自己换盘）
            openNextFile();

            // 3. 重新开始录制
            isRecording.set(true);
            switchSuccess = true;
            
            // 成功：重置恢复计数器
            recoveryAttempts = 0;

            AppLog.d(TAG, "Camera " + cameraId + " Switched to segment " + segmentIndex + ": " + queuedFileName);

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to switch segment (attempt " + (recoveryAttempts + 1) + ")", e);
            if (recoveryAttempts == 0) {
                noteTrouble("segment-switch", e);
            }
            
            // 标记录制状态（允许帧回调继续消费帧）
            isRecording.set(false);
            
            if (callback != null) {
                final String errorMsg = e.getMessage();
                segmentHandler.post(() -> callback.onRecordError(cameraId, "Failed to switch segment: " + errorMsg));
            }
        }
        
        // 6. 根据结果调度下一次操作
        if (switchSuccess) {
            // 成功：调度正常的1分钟定时器
            segmentHandler.post(() -> scheduleNextSegment());
        } else {
            // 失败：5 秒后再试；一直修不好的话，写不进文件的裁判会在 15 秒时报一次、停
            recoveryAttempts++;
            AppLog.w(TAG, "Camera " + cameraId + " Segment switch failed, quick retry in "
                + (RECOVERY_RETRY_INTERVAL_MS / 1000) + "s (attempt " + recoveryAttempts + ")");
            scheduleRecoveryRetry();
        }
    }
    
    /**
     * 调度快速恢复重试
     */
    private void scheduleRecoveryRetry() {
        // 取消之前的恢复任务
        if (recoveryRunnable != null) {
            segmentHandler.removeCallbacks(recoveryRunnable);
        }
        
        recoveryRunnable = () -> {
            if (!isReleased && !stopRequested && encoderHandler != null) {
                AppLog.d(TAG, "Camera " + cameraId + " Recovery retry triggered");
                // 在编码线程上执行恢复
                encoderHandler.post(() -> StallWatch.runTask(encoderBeat, cameraId, "recovery", this::attemptRecovery));
            }
        };
        
        segmentHandler.postDelayed(recoveryRunnable, RECOVERY_RETRY_INTERVAL_MS);
    }
    
    /**
     * 尝试恢复录制
     */
    private void attemptRecovery() {
        if (stopRequested || isReleased) {
            return;  // 叫停了：不再开新文件
        }
        AppLog.d(TAG, "Camera " + cameraId + " Attempting recovery (attempt " + recoveryAttempts + ")");
        
        boolean recoverySuccess = false;
        
        try {
            // 确保编码器和 EGL 已准备好
            if (encoder == null) {
                createEncoder();
                if (eglEncoder != null && encoderInputSurface != null) {
                    eglEncoder.updateOutputSurface(encoderInputSurface);
                }
            }
            if (stopRequested) {
                return;  // 建编码器这一阵里叫停了
            }

            // 开新文件（写入线程上开，这个盘开不了它自己换盘）。上一个文件收了、新的还没排才开
            if (!fileQueued) {
                openNextFile();
            }
            
            // 恢复录制
            isRecording.set(true);
            recoverySuccess = true;
            
            // 成功：重置恢复计数器
            recoveryAttempts = 0;
            
            AppLog.d(TAG, "Camera " + cameraId + " Recovery successful, recording resumed: " + queuedFileName);
            com.kooo.evcam.blackbox.BlackBox.noteImportant("录像恢复了（相机 " + cameraId + "），新文件 "
                    + queuedFileName);
            
            // 调度正常的1分钟定时器；健康检查也接着跑 —— 写入线程报写不下去（编码器标不健康）时要靠它原地重建
            segmentHandler.post(() -> scheduleNextSegment());
            segmentHandler.post(this::scheduleEncoderHealthCheck);
            
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Recovery attempt failed", e);
            // 5 秒一次：第一次和之后每一分钟记一行，别的只算次数
            if (recoveryAttempts <= 1 || recoveryAttempts % 12 == 0) {
                noteTrouble("recovery#" + recoveryAttempts, e);
            }
            isRecording.set(false);
            
            // 5 秒后再试；一直修不好的话，写不进文件的裁判会在 15 秒时报一次、停
            recoveryAttempts++;
            AppLog.w(TAG, "Camera " + cameraId + " Recovery failed, quick retry in "
                + (RECOVERY_RETRY_INTERVAL_MS / 1000) + "s (attempt " + recoveryAttempts + ")");
            scheduleRecoveryRetry();
        }
    }
    
    /**
     * 为分段切换停止录制（在编码线程上执行）
     * 完整停止并重新创建编码器
     * 
     * 注意：此方法有完善的异常处理，即使部分操作失败也会继续执行
     */
    private void stopRecordingForSegmentSwitch() {
        AppLog.d(TAG, "Camera " + cameraId + " Stopping recording for segment switch");
        
        // 1. 停止录制（阻止新帧写入）
        isRecording.set(false);
        
        // 2. 排空编码器（drainEncoder 现在在同一线程执行，不会有竞争）。只取已经出来的，不发结束流、不等：
        //    编码器里还在路上的最后 1–3 帧随旧编码器丢掉 —— 已知的限制，每段交界少这几帧（换编码器的代价）
        if (encoder != null) {
            try {
                drainEncoder(false);  // 先排空已有数据
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " Error draining encoder during segment switch", e);
            }
        }
        
        // 3. 收文件：排给写入线程，排在这一段最后一个样本后面。它写完、写文件尾、fsync、关好，
        //    再报「这一段完成」（带上下一段的序号）。没确认落盘的话（盘掉了），写过多留的那段由它抢救出来 ——
        //    下面要换编码器，两代样本不能混在一个文件里
        segmentIndex++;
        if (fileQueued) {
            closeCurrentFile("segment-switch", segmentIndex);
        }

        // 4. 释放旧编码器（即使失败也继续）
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception e) {
                AppLog.w(TAG, "Camera " + cameraId + " Error stopping encoder: " + e.getMessage());
            }
            try {
                encoder.release();
            } catch (Exception e) {
                AppLog.w(TAG, "Camera " + cameraId + " Error releasing encoder: " + e.getMessage());
            }
            encoder = null;
        }
        
        if (encoderInputSurface != null) {
            try {
                encoderInputSurface.release();
            } catch (Exception e) {
                AppLog.w(TAG, "Camera " + cameraId + " Error releasing encoder surface: " + e.getMessage());
            }
            encoderInputSurface = null;
        }
        
        // 5. 重新创建编码器
        try {
            createEncoder();
            
            // 重新设置 EGL 的输出 Surface
            if (eglEncoder != null && encoderInputSurface != null) {
                eglEncoder.updateOutputSurface(encoderInputSurface);
            }
            
            AppLog.d(TAG, "Camera " + cameraId + " Encoder recreated for new segment");
            
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to recreate encoder", e);
            // 抛出异常，让调用者处理
            throw new RuntimeException("Failed to recreate encoder for segment switch", e);
        }
    }

    /**
     * 生成新的分段文件名
     * 优先使用 TimestampProvider 获取统一时间戳（多路摄像头同步）
     * 如果没有设置 provider，则使用当前时间
     */
    private String segmentFileName() {
        String timestamp;
        if (timestampProvider != null) {
            // 使用统一的时间戳提供者（确保多路摄像头使用相同时间戳）
            timestamp = timestampProvider.getSegmentTimestamp();
            // 单独接回的一路从这一段起和别的路同名了：别的路再换名字，它照自己的分段计时走（switchNow 不再切）
            offCycleName = false;
            AppLog.d(TAG, "Camera " + cameraId + " using provider timestamp: " + timestamp);
        } else {
            // 回退到独立生成时间戳（兼容旧逻辑）
            timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            AppLog.d(TAG, "Camera " + cameraId + " using local timestamp: " + timestamp);
        }
        return timestamp + "_" + CameraSlots.suffixFor(cameraPosition) + ".mp4";
    }

    // ===== 写入线程：MediaMuxer 只在它上面动 =====
    //
    // 2026-10-08 起写文件不在编码线程上（为什么见 SampleRing）。编码线程只把编码好的样本拷出来，
    // 连同「开文件 / 开轨 / 收文件」这几条命令按顺序排进 ring；写入线程按顺序做：开文件、开轨、写样本、
    // 每 5 秒 fsync（夹在两次写之间）、收文件（写文件尾、fsync、关掉）、写不进时换盘并补写、没确认落盘时抢救。
    // 下面这些字段只有写入线程读写（标了 volatile 的给诊断、停录、抢救线程随时读）。

    /** 录像盘写不进时还能写到哪些目录，按优先顺序；相机层提供，它知道此刻挂着哪些盘。 */
    public interface FallbackDirs {
        List<File> candidates(Set<String> deadVolumes);
    }

    public void setFallbackDirs(FallbackDirs dirs) {
        this.fallbackDirs = dirs;
    }

    private FallbackDirs fallbackDirs;

    /** 每 5 秒 fsync 一次。 */
    private static final long SYNC_INTERVAL_MS = 5_000L;
    /** 写入线程一轮最多连着干这么久就让一让：fsync 定时、卡顿探针要能插进来。 */
    private static final long WRITER_SLICE_MS = 200L;
    /** 排队满了，编码线程先等这么久：写入线程一般写走一两个样本就让出空位。还满就不编这一帧。 */
    private static final long ADMIT_WAIT_MS = 50L;
    /** 开录时等写入线程开好第一个文件最多多久（和等 EGL 初始化一样）。 */
    private static final long FIRST_OPEN_WAIT_MS = 5_000L;
    /** 停录时排空编码器（结束流）最多等多久：编码器里在路上的只有几帧，正常几十毫秒就交出结束标记。 */
    private static final long EOS_DRAIN_MS = 2_000L;
    /**
     * 停录的期限：几路一起叫停之后，一起等编码线程排空、写入线程写完收好，共用这一个期限
     * （{@code MultiCameraManager} 停录分两步：先每一路都叫停，再一起等）。按开机时长（不含深睡）算，
     * 和协调器的收拾期限、等待用的计时是同一个钟。协调器等收拾最多 20 秒（{@code RecordingLifecycle.STOP_DEADLINE_MS}），
     * 这里连同 {@link #STOP_GRACE_MS} 最多用一半，另一半留给释放录制器、摘录像输出、重建会话。
     * 排队最多 3 秒的画面，盘还在动的话 9 秒写得完；到点还没收好（盘多半没了），不再等（{@link #abandonWriter}）。
     */
    public static final long STOP_BUDGET_MS = 9_000L;
    /** 释放时等写入线程收尾、退出最多多久（和别的线程一样）。停录时已经等到放弃过的不再等。 */
    private static final long WRITER_JOIN_MS = 1_000L;
    /**
     * 编码线程拖到了停录期限才排空（收文件到点才排上）、写入线程却没卡住时，期限之后再给它这么久收文件
     * （和等线程退出同一个数）。几路共用：都按同一个期限往后加，一起停的几路加起来也只多这么久。
     */
    public static final long STOP_GRACE_MS = WRITER_JOIN_MS;
    /**
     * 写入线程最多比编码线程晚多久把一个文件开出来、写完：排队最多 {@code SampleRing.QUEUE_MS} 的画面，
     * 再卡一次写 / 收文件最多 {@link StallRules#WRITER_STALL_MS}（再久卡顿监测就算它卡住了）。
     * 闪远光自动锁定的第二遍要多等这么久，晚建好的那个文件才在盘上（{@code LockWindow.SECOND_PASS_DELAY_MS}）。
     */
    public static final long MAX_FILE_LAG_MS = SampleRing.QUEUE_MS + StallRules.WRITER_STALL_MS;

    private HandlerThread writerThread;
    private volatile Handler writerHandler;
    /** 写入线程的心跳：写进文件一次跳一下；正在写 / fsync / 开、收文件时记着在忙什么、忙了多久（见 StallWatch）。 */
    private final Heartbeat writerBeat;
    /** 写入线程做完了「退出」那一条。 */
    private volatile boolean writerQuit;
    /**
     * 等写入线程等到放弃了（停录到点没收好、开录时第一个文件开不出来）：释放时不再干等第二遍。
     * 它回过神来只把手里那个文件收好、退出 —— 不再开新文件、开轨、换盘、抢救（每一步开始前都看这个）。
     */
    private volatile boolean writerAbandoned;
    /** 放弃那时写入线程手里开着的文件（{@link #heldFile}）。 */
    private volatile String heldFilePath;
    /** 写入线程正在换盘、补写：这时排队满了是补写占着，不是 U 盘慢，不提示用户。 */
    private volatile boolean writerRelocating;
    /**
     * 写入线程最近一次动的目录（开文件、换盘、收文件时抢救，每次动之前填）。它卡住时多半就卡在这个目录所在的盘上 ——
     * 不一定是 {@link #saveDirectory}（换盘换到一半卡在新盘上时，录像目录还是旧的）。抢救线程不往这个盘上写。
     */
    private volatile String writerTargetDir;
    /** 停录第一步排的「收文件」和编码线程排空的信号：{@link #beginStop} 设，{@link #awaitDrain} / {@link #finishStop} 等。 */
    private volatile CloseFile stopClose;
    private volatile CountDownLatch stopDrained;
    /** 停录时编码线程到期限还没排空，收文件是到点才排上的（{@link #finishStop} 据此多给写入线程一会儿）。 */
    private volatile boolean drainLate;
    /** 已经叫过写入线程、它还没开始干：不再叫第二遍。 */
    private final AtomicBoolean pumpPosted = new AtomicBoolean();
    private final MediaCodec.BufferInfo writerInfo = new MediaCodec.BufferInfo();

    private MediaMuxer muxer;
    private int videoTrackIndex = -1;
    private volatile boolean muxerStarted;
    /** muxer 写的那个文件，我们自己开的：muxer 内部 dup 了描述符，这一份留着 fsync。 */
    private RandomAccessFile muxerFile;
    /** 这个文件本来叫什么（开文件那条命令给的）：换盘时没有可补写的，新盘上用同一个名字。 */
    private String writerFileName;
    /** 这个文件的轨道格式（开轨那条命令给的）：换盘补写、抢救都用它开轨。收文件时清掉。放弃等写入线程时抢救线程也读。 */
    private volatile MediaFormat fileFormat;
    /** 这个文件开过轨没有：没开过的是空文件，收文件时删掉（不报）。 */
    private boolean fileStarted;
    /** 这个文件写不下去了、也没盘可换：这个文件后面排着的样本都写不了，等下一个文件。 */
    private boolean fileFailed;
    /** 这个文件是编码线程排的第几个：报错时编码线程凭它认出说的是不是还是现在这个。 */
    private int fileSerial;
    /** 这次录像里写不进的卷名，不再试。写入线程加，抢救线程读。 */
    private final Set<String> deadVolumes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile int relocations;

    /** 排队满了、丢相机帧的那几段（编码线程上）：黑匣子什么时候记、什么时候提示用户。 */
    private final SampleRing.Backpressure backpressure = new SampleRing.Backpressure();

    // ----- 写入线程的命令：和样本一起按顺序排在 ring 里 -----

    /** 开一个新文件。名字由编码线程定（分段时间戳几路共用），目录由写入线程定（换过盘就是新盘）。 */
    private static final class OpenFile {
        final String name;
        final int serial;
        /** 这个目录开不了就换盘。开录时的第一个文件不换：开不了就是开录失败，和以前一样。 */
        final boolean relocate;
        /** 开录时在这里等结果；别的时候 null。 */
        final CountDownLatch done;
        volatile Exception failure;

        OpenFile(String name, int serial, boolean relocate, CountDownLatch done) {
            this.name = name;
            this.serial = serial;
            this.relocate = relocate;
            this.done = done;
        }

        @Override
        public String toString() {
            return "open " + name;
        }
    }

    /** 编码器报了输出格式：开轨、启动 muxer。 */
    private static final class StartTrack {
        final MediaFormat format;

        StartTrack(MediaFormat format) {
            this.format = format;
        }

        @Override
        public String toString() {
            return "track";
        }
    }

    /** 收这个文件：写文件尾、fsync、关掉；没确认落盘就把写过多留的那段抢救成单独的文件。 */
    private static final class CloseFile {
        final String stage;
        /** 分段切换时是下一段的序号：收好了才报「这一段完成」；别的时候 -1。 */
        final int nextSegmentIndex;
        final CountDownLatch done = new CountDownLatch(1);
        /** 只排一次：停录时编码线程卡住了由调用者补排，编码线程回过神来不再排第二遍。 */
        final AtomicBoolean queued = new AtomicBoolean();

        CloseFile(String stage, int nextSegmentIndex) {
            this.stage = stage;
            this.nextSegmentIndex = nextSegmentIndex;
        }

        @Override
        public String toString() {
            return "close " + stage;
        }
    }

    /** 写入线程退出：最后一条，前面排着的都做完了才轮到它。 */
    private static final Object QUIT_WRITER = new Object() {
        @Override
        public String toString() {
            return "quit";
        }
    };

    // ----- 编码线程（和开录、停录的调用者）这一侧：排命令、排样本 -----

    /** 起写入线程，排好 fsync 定时。 */
    private void startWriterThread() {
        writerQuit = false;
        writerAbandoned = false;
        writerThread = new HandlerThread("CodecRecorder-Writer-" + cameraId) {
            @Override
            protected void onLooperPrepared() {
                // 和编码线程一样的优先级。写盘多半在等 I/O，不抢 CPU
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            }
        };
        writerThread.start();
        Handler handler = new Handler(writerThread.getLooper());
        writerHandler = handler;
        // 卡顿报告里要看得出写入线程是不是卡在写盘上（和编码线程分开看）
        StallWatch.watchLooper("Writer-" + cameraId, handler);
        StallWatch.watchWriter(cameraId, writerBeat);
        handler.postDelayed(syncTick, SYNC_INTERVAL_MS);
    }

    /**
     * 开录时的第一个文件：排给写入线程开，等它的结果（开录多半在主线程上，所以有期限）。
     * 等不到就是写入线程卡在这个盘上了：开录失败，也不再等它（{@link #abandonWriter}）。
     */
    private void openFirstFile(String name) throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        OpenFile open = new OpenFile(name, ++openSerial, false, opened);
        queuedFileName = name;
        fileQueued = true;
        queueCommand(open);
        if (!opened.await(FIRST_OPEN_WAIT_MS, TimeUnit.MILLISECONDS)) {
            abandonWriter("open");
            throw new IOException("writer thread did not open " + name + " within " + FIRST_OPEN_WAIT_MS + "ms");
        }
        if (open.failure != null) {
            throw open.failure;
        }
    }

    /**
     * 单独接回的一路开录（{@link #startRecording}）：第一个文件按此刻命名，不经共用的分段时间戳 —— 经它的话，
     * 十秒内另一路切段会拿到这个名字，这一路也可能拿到别的路早先的名字，都比画面早。排给写入线程开，不在这里等：
     * 开录在主线程上，不等盘；这个盘开不了，写入线程自己换盘（和分段切换开下一个文件同一条路）。
     * 撞名由写入线程按此刻另起一个（{@link #freshPath}）。
     */
    private void openFirstFileNow() {
        queuedFileName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())
                + "_" + CameraSlots.suffixFor(cameraPosition) + ".mp4";
        offCycleName = true;
        fileQueued = true;
        queueCommand(new OpenFile(queuedFileName, ++openSerial, true, null));
    }

    /** 开下一个文件（名字按分段时间戳）：写入线程上开，这个盘开不了它自己换盘。编码线程上调。 */
    private void openNextFile() {
        queuedFileName = segmentFileName();
        fileQueued = true;
        queueCommand(new OpenFile(queuedFileName, ++openSerial, true, null));
    }

    /** 收现在这个文件：写入线程写完前面排着的再收。编码线程上调。 */
    private void closeCurrentFile(String stage, int nextSegmentIndex) {
        fileQueued = false;
        queueClose(new CloseFile(stage, nextSegmentIndex));
    }

    private void queueClose(CloseFile close) {
        if (close.queued.compareAndSet(false, true)) {
            queueCommand(close);
        }
    }

    private void queueCommand(Object command) {
        ring.offerCommand(command);
        kickWriter();
    }

    /**
     * 编码器报了输出格式（编码线程上）：交给写入线程开轨。每一代编码器一次，顺带记一行参数集在不在格式里 ——
     * 在，单独的参数集缓冲区就不当样本写（{@link #skipAsSample}）；不在，照旧写进文件，否则文件里没有参数集。
     */
    private void onEncoderFormat(MediaFormat format) {
        formatSeen = true;
        encoderHealthy = true;  // 收到格式变化说明编码器正常
        boolean avc = MIME_TYPE_H264.equals(mimeType);
        boolean csd0 = format.containsKey("csd-0");
        boolean csd1 = format.containsKey("csd-1");
        csdInFormat = csd0 && (!avc || csd1);
        AppLog.i(TAG, "Camera " + cameraId + " encoder output format " + mimeType
                + ": csd-0=" + csd0 + (avc ? " csd-1=" + csd1 : ""));
        if (!csdInFormat) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("录像编码器的输出格式里没有参数集（相机 " + cameraId
                    + "，" + mimeType + "，csd-0=" + csd0 + (avc ? "，csd-1=" + csd1 : "")
                    + "）：参数集照旧当样本写进文件");
        }
        queueCommand(new StartTrack(format));
    }

    /**
     * 只有参数集的缓冲区（CODEC_CONFIG，不带关键帧标志），而且参数集已经在输出格式里了：不当样本写 ——
     * 开轨时 muxer 已经拿到了。带关键帧标志的从来不丢；格式里没有参数集的也不丢（见 {@link #onEncoderFormat}）。
     */
    private boolean skipAsSample(int flags) {
        return csdInFormat
                && (flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                && (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0;
    }

    /**
     * 编码器刚给出的一个样本（编码线程上）：拷出来排给写入线程，编码器的缓冲区马上就能还回去。
     * 写盘慢不再卡住这里 —— 以前写文件就在这一步，U 盘刷缓存时一卡一秒，那一秒的画面全丢。
     */
    private void queueSample(ByteBuffer encodedData, MediaCodec.BufferInfo info) {
        if (skipAsSample(info.flags)) {
            // 参数集已经随输出格式交给 muxer 了（开轨时），不当样本排
            return;
        }
        byte[] data = new byte[info.size];
        encodedData.position(info.offset);
        encodedData.limit(info.offset + info.size);
        encodedData.get(data);
        // 关键帧、只有参数集都由 ring 从标志位上看（参数集不算排队的画面长度，见 SampleRing.Sample#config）
        ring.offerSample(info.presentationTimeUs, info.flags, data, SystemClock.elapsedRealtime());
        kickWriter();
        noteEncodedBytes(info.size);
    }

    /** 叫醒写入线程。叫过了、它还没开始干的话不再叫。 */
    private void kickWriter() {
        Handler handler = writerHandler;
        if (handler != null && pumpPosted.compareAndSet(false, true) && !handler.post(writerPump)) {
            pumpPosted.set(false);  // 写入线程已经退了
        }
    }

    /**
     * 这一帧编不编（编码线程上）。写入线程跟不上、排队满了：先等 {@link #ADMIT_WAIT_MS}；还满就不编，
     * 在相机这一侧丢这一帧 —— 和以前写文件卡住时一样，但编码线程不会被一直卡住，已经编码的一个都不丢。
     */
    private boolean admitFrame() {
        boolean room = ring.hasRoom();
        if (!room) {
            long start = StallWatch.now();
            try {
                room = ring.awaitRoom(ADMIT_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            StallWatch.noteOp(encoderBeat, cameraId, "queue-wait", start);
        }
        long now = SystemClock.elapsedRealtime();
        if (room) {
            if (backpressure.isFull()) {
                // 满了的那一段要等排队退到一半以下才算完：卡在上限边上时编一帧、丢一帧，还是同一段
                noteBackpressure(backpressure.admitted(now, ring.isDrained()));
            }
            return true;
        }
        long queuedMs = ring.queuedMs();
        long queuedBytes = ring.queuedBytes();
        if (backpressure.dropped(now, queuedMs, queuedBytes)) {
            AppLog.w(TAG, "Camera " + cameraId + " write queue full (" + queuedMs + "ms, "
                    + megabytes(queuedBytes) + "MB): dropping camera frames until the writer catches up");
            // 提示用户「U 盘写入跟不上」：只在写的是 U 盘、又不是换盘补写占着排队的时候（中转写入的内部缓存、
            // 内置存储、换盘补写都不是 U 盘慢）。多久提示一次由 RecordingCoordinator 定
            boolean slowDrive = isOnUsbDrive(saveDirectory) && !writerRelocating;
            RecordCallback cb = callback;
            Handler segment = segmentHandler;
            if (slowDrive && cb != null && segment != null) {
                segment.post(() -> cb.onWriteBacklog(cameraId));
            }
        }
        return false;
    }

    /**
     * 这个目录在不在 U 盘上：/storage/卷名/…，卷名不是内置存储（emulated、self）。
     * 中转写入写的是内部缓存，开发者选项下可以写内置存储 —— 那时说「U 盘写入跟不上」是错的。
     */
    private static boolean isOnUsbDrive(String dir) {
        if (dir == null || !dir.startsWith("/storage/")) {
            return false;
        }
        String volume = StorageHelper.volumeOf(dir);
        return !volume.isEmpty() && !"emulated".equals(volume) && !"self".equals(volume);
    }

    /**
     * 排队满过的那几段，到了 Backpressure 定的时候记一行黑匣子（编码线程上）。
     * 「没编的帧」只数编码线程亲手放掉的；等空位那 50 毫秒里相机那边被新帧顶掉的看不见，所以写「至少」。
     */
    private void noteBackpressure(SampleRing.Backpressure.Summary summary) {
        if (summary == null) {
            return;
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("U 盘写入跟不上（相机 " + cameraId + "）：写入排队满过 "
                + summary.episodes + " 次，共 " + seconds(summary.fullMs) + " 秒，至少 " + summary.droppedFrames
                + " 帧没编（丢在相机这一侧）；排得最深 " + seconds(summary.deepestMs) + " 秒 / "
                + megabytes(summary.deepestBytes) + " MB（上限 " + seconds(SampleRing.QUEUE_MS) + " 秒 / "
                + megabytes(ring.queueCapBytes()) + " MB），这次录像最深 " + seconds(ring.highMs()) + " 秒");
    }

    /**
     * 等 latch 到 deadlineMs 为止。期限按开机时长（不含深睡）算：latch 自己的等待、协调器的收拾期限都不算深睡，
     * 按含深睡的钟算的话，车机睡一会儿，醒来期限就算过了，一口都没等。
     */
    private static boolean awaitUntil(CountDownLatch latch, long deadlineMs) {
        long leftMs = deadlineMs - SystemClock.uptimeMillis();
        try {
            return latch.await(Math.max(0L, leftMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return latch.getCount() == 0;
        }
    }

    /**
     * 写入线程此刻是不是卡在一次写 / fsync / 开、收文件里 —— 和卡顿监测一个标准（忙同一件事超过
     * {@link StallRules#WRITER_STALL_MS}）。闲着的、一次 fsync 刚开始一会儿的（U 盘上 0.3–1 秒是常态）不算。
     */
    private boolean writerStuck() {
        return writerBeat.task() != null
                && writerBeat.taskAgeMs(StallWatch.now()) > StallRules.WRITER_STALL_MS;
    }

    /**
     * 不再等写入线程了（停录到点没收好、开录时第一个文件开不出来、释放时收尾也卡住）：它多半卡在一次写 / fsync 里，
     * 盘没了或者极慢。
     *
     * <p>内存里还没确认落盘的 —— 写过多留的那段和排着的 —— 交给一个抢救线程，尽力写到别的盘上（卡住的这个盘不试）；
     * 然后从内存里拿掉，内存不跟着一个卡死的线程一直占着（{@link SampleRing#abandon}）。命令留在队里：
     * 写入线程回过神来照样收文件、退出。它手里开着的那个文件不验证、中转写入不转存（{@link #heldFile}），
     * 它自己收好时再验证（{@link #closeFile}）。</p>
     */
    private void abandonWriter(String stage) {
        if (writerAbandoned) {
            return;
        }
        writerAbandoned = true;
        heldFilePath = currentFilePath;
        List<Object> unsynced = ring.abandon();
        int samples = 0;
        for (Object entry : unsynced) {
            if (entry instanceof SampleRing.Sample) {
                samples++;
            }
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("写入线程到点还没做完（相机 " + cameraId + "，" + stage
                + "）：不再等它，内存里 " + samples + " 个没确认落盘的样本交给抢救线程；" + describeWriteState());
        startRescueThread(unsynced, stage);
    }

    /** 抢救的一段：同一个文件、同一个格式的样本，从关键帧开始。 */
    private static final class RescueRun {
        final MediaFormat format;
        final List<SampleRing.Sample> samples;

        RescueRun(MediaFormat format, List<SampleRing.Sample> samples) {
            this.format = format;
            this.samples = samples;
        }
    }

    /**
     * 把交出来的（写过多留的 + 排着的，按顺序）分成一段一段：开头那段用正在写的那个文件的格式；
     * 排着的里面跨过分段的（收文件 → 开文件 → 开轨），从开轨那条起换成新编码器的格式 —— 两代样本不能写进一个文件。
     * 不知道格式的、一段里第一个关键帧之前的丢掉（写不进去、解不出来）。
     */
    private List<RescueRun> rescueRuns(List<Object> entries) {
        List<RescueRun> runs = new ArrayList<>();
        MediaFormat format = fileFormat;
        List<SampleRing.Sample> current = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof SampleRing.Sample) {
                SampleRing.Sample sample = (SampleRing.Sample) entry;
                if (format != null && (!current.isEmpty() || sample.keyframe)) {
                    current.add(sample);
                }
            } else if (entry instanceof CloseFile || entry instanceof StartTrack) {
                if (format != null && !current.isEmpty()) {
                    runs.add(new RescueRun(format, current));
                }
                current = new ArrayList<>();
                format = entry instanceof StartTrack ? ((StartTrack) entry).format : null;
            }
        }
        if (format != null && !current.isEmpty()) {
            runs.add(new RescueRun(format, current));
        }
        return runs;
    }

    /**
     * 放弃等写入线程时，把交出来的样本尽力写到别的盘上：一个短命的线程，写完就退，谁也不等它。
     * 用它自己的 muxer 写单独的抢救文件，和卡住的写入线程手里那个互不相干。
     */
    private void startRescueThread(List<Object> entries, String stage) {
        final List<RescueRun> runs = rescueRuns(entries);
        if (runs.isEmpty()) {
            return;
        }
        // 写入线程卡在哪个盘上：录像目录那个，和它最近一次动的那个（换盘换到一半卡在新盘上时两个不一样）。都不试
        final String stuckDir = saveDirectory;
        final String touchedDir = writerTargetDir;
        final Set<String> skip = new HashSet<>(deadVolumes);
        skip.add(StorageHelper.volumeOf(stuckDir));
        if (touchedDir != null) {
            skip.add(StorageHelper.volumeOf(touchedDir));
        }
        Thread rescue = new Thread(() -> {
            List<File> dirs = new ArrayList<>();
            try {
                if (fallbackDirs != null) {
                    for (File dir : fallbackDirs.candidates(skip)) {
                        String path = dir.getAbsolutePath();
                        if (!path.equals(stuckDir) && !path.equals(touchedDir)) {
                            dirs.add(dir);
                        }
                    }
                }
            } catch (RuntimeException e) {
                AppLog.w(TAG, "Camera " + cameraId + " cannot list fallback dirs for the rescue: " + e);
            }
            for (RescueRun run : runs) {
                String saved = null;
                for (File dir : dirs) {
                    try {
                        saved = writeRescueFile(dir, run.samples, run.format);
                        break;
                    } catch (Exception e) {
                        AppLog.w(TAG, "Camera " + cameraId + " rescue to " + dir + " failed: " + e);
                    }
                }
                long spanMs = spanMs(run.samples);
                if (saved != null) {
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("写入线程卡住（相机 " + cameraId + "，" + stage
                            + "）：内存里的 " + (spanMs / 1000) + " 秒抢救到 " + saved);
                } else {
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("写入线程卡住（相机 " + cameraId + "，" + stage
                            + "）：内存里的 " + (spanMs / 1000) + " 秒没地方抢救；此刻挂着的盘："
                            + StorageHelper.describeMounts());
                }
            }
        }, "CodecRecorder-Rescue-" + cameraId);
        rescue.start();
    }

    /**
     * 写入线程收尾（释放时）：还开着的文件收掉，排着的做完，退出。停录时已经等到放弃过的不再等；
     * 没放弃过的，前面的都收好了，这一步只收开录没起来的那种空文件，很快。收尾也卡住的，同样交给抢救。
     */
    private void stopWriter() {
        HandlerThread thread = writerThread;
        if (thread == null) {
            return;
        }
        writerThread = null;
        boolean abandoned = writerAbandoned;
        CloseFile close = new CloseFile("release", -1);
        queueClose(close);
        queueCommand(QUIT_WRITER);
        boolean finished = !abandoned && awaitUntil(close.done, SystemClock.uptimeMillis() + WRITER_JOIN_MS);
        if (finished) {
            try {
                thread.join(WRITER_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 卡顿监测按这一个录制器的撤：同一路新的录制器可能已经登记了
        StallWatch.unwatchLooper("Writer-" + cameraId, writerHandler);
        StallWatch.unwatchWriter(cameraId, writerBeat);
        writerBeat.disarm();
        if (finished && !thread.isAlive()) {
            ring.clear();
        } else if (!abandoned) {
            abandonWriter("release");
        }
    }

    // ----- 写入线程这一侧 -----

    private final Runnable writerPump = this::pumpWriter;

    /** 写入线程上：按顺序做排着的事，做完了，或者连着干了 {@link #WRITER_SLICE_MS} 让一让。 */
    private void pumpWriter() {
        pumpPosted.set(false);
        long until = StallWatch.now() + WRITER_SLICE_MS;
        while (!writerQuit) {
            Object next = ring.peek();
            if (next == null) {
                return;
            }
            if (next instanceof SampleRing.Sample) {
                SampleRing.Sample sample = (SampleRing.Sample) next;
                try {
                    writeQueued(sample);
                } catch (RuntimeException e) {
                    // 每一步自己的错误都有去处，不该到这里；到了也不能让写入线程死掉、卡死在这一个上：
                    // 记下，这个文件算写不了（编码线程重建），这一个丢掉
                    writerBeat.endTask();
                    AppLog.e(TAG, "Camera " + cameraId + " writer failed on a sample", e);
                    failFile("writer-sample", e);
                    ring.discard(sample);
                }
            } else {
                runCommand(next);
            }
            if (StallWatch.now() >= until && yieldWriter()) {
                return;
            }
        }
    }

    /** 让一让：排到写入线程队尾再接着做。排不上（线程在退）就不让，接着做完。 */
    private boolean yieldWriter() {
        Handler handler = writerHandler;
        if (handler == null || writerQuit) {
            return false;
        }
        if (!pumpPosted.compareAndSet(false, true)) {
            return true;  // 编码线程刚叫过，已经排着一轮
        }
        if (handler.post(writerPump)) {
            return true;
        }
        pumpPosted.set(false);
        return false;
    }

    private void runCommand(Object command) {
        try {
            if (command instanceof OpenFile) {
                openFile((OpenFile) command);
            } else if (command instanceof StartTrack) {
                startTrack((StartTrack) command);
            } else if (command instanceof CloseFile) {
                closeFile((CloseFile) command);
            } else if (command == QUIT_WRITER) {
                quitWriter();
            }
        } catch (RuntimeException e) {
            // 每一步自己的错误都有去处，不该到这里；到了也不能卡死在这一条上
            writerBeat.endTask();
            AppLog.e(TAG, "Camera " + cameraId + " writer failed on " + command, e);
            if (command instanceof OpenFile || command instanceof StartTrack) {
                // 开文件、开轨出了意外：这个文件算写不了，和写不进时一样（编码线程重建）
                failFile("writer " + command, e);
            } else {
                noteTrouble("writer " + command, e);
            }
        } finally {
            ring.done(command);
        }
    }

    /** 开文件（写入线程上）。这个目录开不了就换盘；换不了，这个文件就写不了，告诉编码线程。 */
    private void openFile(OpenFile open) {
        boolean opened = false;
        try {
            if (muxer != null || muxerFile != null) {
                // 不该发生：每个文件开之前都排过一条收文件
                AppLog.w(TAG, "Camera " + cameraId + " previous file still open when opening " + open.name);
                settleRing(closeMuxer("reopen"), "reopen");
            }
            writerFileName = open.name;
            fileSerial = open.serial;
            fileFormat = null;
            fileStarted = false;
            fileFailed = false;
            // 这个文件开成了才填：收文件时按它报，没开成就没什么可报的（见 closeFile）
            currentFilePath = null;
            if (writerAbandoned) {
                // 已经不再等这个写入线程了（内存里的交给了抢救线程）：它回过神来只收尾、退出，不再开新文件
                fileFailed = true;
                return;
            }
            writerTargetDir = saveDirectory;
            String path = freshPath(new File(saveDirectory), open.name);
            long start = StallWatch.now();
            writerBeat.beginTask("open", start);
            try {
                createMuxer(path);
            } catch (IOException | RuntimeException e) {
                writerBeat.endTask();
                if (!open.relocate) {
                    // 开录时的第一个文件：开不了就是开录失败，调用者在等这个结果
                    open.failure = e;
                    fileFailed = true;
                } else if (relocate("open", e)) {
                    opened = true;  // 换盘时已经在新盘上开好了
                } else {
                    failFile("open", e);
                }
                return;
            }
            writerBeat.endTask();
            StallWatch.noteOp(writerBeat, cameraId, "open", start);
            currentFilePath = path;
            recordedFilePaths.add(path);
            opened = true;
        } finally {
            if (open.done != null) {
                if (!opened && open.failure == null) {
                    open.failure = new IOException("writer could not open " + open.name);
                }
                open.done.countDown();
            }
        }
    }

    /** 开轨、启动 muxer（写入线程上）。文件头都写不进去的话换盘；换不了，这个文件就写不了。 */
    private void startTrack(StartTrack track) {
        if (muxerStarted) {
            AppLog.w(TAG, "Camera " + cameraId + " Format changed twice");
            return;
        }
        fileFormat = track.format;
        if (fileFailed || muxer == null) {
            return;  // 这个文件没开成：后面的样本写不了，等下一个文件
        }
        if (writerAbandoned) {
            // 已经不再等这个写入线程了：后面的样本都交给了抢救线程，开了轨也是个空文件。不开轨的收文件时删掉
            return;
        }
        long start = StallWatch.now();
        writerBeat.beginTask("track", start);
        try {
            videoTrackIndex = muxer.addTrack(track.format);
            muxer.start();
            muxerStarted = true;
            fileStarted = true;
        } catch (RuntimeException e) {
            writerBeat.endTask();
            if (!relocate("start", e)) {
                failFile("start", e);
            }
            return;  // 换盘时已经在新文件上开好了轨
        }
        writerBeat.endTask();
        StallWatch.noteOp(writerBeat, cameraId, "track", start);
        AppLog.d(TAG, "Camera " + cameraId + " Muxer started, track=" + videoTrackIndex);
    }

    /**
     * 写一个排着的样本（写入线程上）。
     *
     * <p>用编码器给出的真实时间戳，不按帧数推算（见 {@link #nextPtsUs}：推算会让回放速度不等于录制速度）。
     * 写不进就换盘：写过多留的那段先补写进新文件，这一个接着写进去；换不了，这个文件就算写不了
     * （{@link #failFile}），以前那样走编码器不健康 → 重建 → 写不进文件的裁判。</p>
     */
    private void writeQueued(SampleRing.Sample sample) {
        if (fileFailed || muxer == null || !muxerStarted) {
            // 没有能写的文件：这个文件没开成、开轨失败、换盘也没换成。写不了的只能丢
            ring.discard(sample);
            return;
        }
        while (true) {
            writerInfo.set(0, sample.data.length, nextPtsUs(sample.ptsUs, sample.config), sample.flags);
            long start = StallWatch.now();
            writerBeat.beginTask("write", start);
            try {
                muxer.writeSampleData(videoTrackIndex, ByteBuffer.wrap(sample.data), writerInfo);
            } catch (RuntimeException e) {
                writerBeat.endTask();
                if (!relocate("write", e)) {
                    failFile("write", e);
                    ring.discard(sample);
                    return;
                }
                continue;  // 换好了：写过多留的那段已经补写进新文件，这一个接着写
            }
            writerBeat.endTask();
            // U 盘刷写缓存时这一步会卡（2026-10-08 实测 0.3–1.05 秒）：卡多久都只是排队涨，画面不丢
            StallWatch.noteOp(writerBeat, cameraId, "write", start);
            break;
        }
        writerBeat.beat(StallWatch.now());
        ring.written(sample, SystemClock.elapsedRealtime());
        markWritten();
    }

    /**
     * 这个文件写不下去了，也没有别的盘可换（写入线程上）。这个文件后面排着的样本都写不了，丢掉；
     * 告诉编码线程：它把编码器标成不健康，健康检查重建 —— 新编码器、新文件，和以前写不进时走的是同一条路。
     */
    private void failFile(String stage, Throwable cause) {
        discardMuxer();
        fileFailed = true;
        noteTrouble(stage, cause);
        final int serial = fileSerial;
        Handler encoderLooper = encoderHandler;
        if (encoderLooper != null) {
            encoderLooper.post(() -> onWriterFailed(serial, stage));
        }
    }

    /** 写入线程说这个文件写不下去了（编码线程上）。说的是已经换掉的文件就不理。 */
    private void onWriterFailed(int serial, String stage) {
        if (serial != openSerial || isReleased || stopRequested) {
            return;
        }
        AppLog.e(TAG, "Camera " + cameraId + " writer gave up on file #" + serial + " (" + stage
                + "), encoder marked unhealthy so the health check rebuilds it");
        encoderHealthy = false;
    }

    /**
     * 收文件（写入线程上），这个文件前面排着的都写完了才轮到这一步：写文件尾、fsync、关掉；
     * 没确认落盘（或者这个文件中途写不下去了），写过多留的那段抢救成单独的文件。
     * 分段切换的，收好了才验证这个文件、报「这一段完成」—— 中转写入在那时把它转存走，早了会拿到半个文件。
     * 没开成的文件没什么可报；开了、一直没开轨的是空文件，删掉、不报。
     */
    private void closeFile(CloseFile close) {
        try {
            final String completed = currentFilePath;
            final boolean hadFile = muxer != null || muxerFile != null || fileFailed || completed != null;
            final boolean started = fileStarted;
            final boolean failed = fileFailed;
            final boolean[] confirmed = {true};
            if (hadFile) {
                StallWatch.runTask(writerBeat, cameraId, "close-" + close.stage, () -> {
                    confirmed[0] = closeMuxer(close.stage) && !failed;
                    settleRing(confirmed[0], close.stage);
                });
            } else {
                ring.clearKept();
            }
            fileFormat = null;
            fileStarted = false;
            fileFailed = false;
            currentFilePath = null;
            if (completed == null) {
                return;  // 这个文件没开成：没有可验证、可报的（以前这里会把上一个文件再报一遍）
            }
            if (!started) {
                // 开录没起来、开第一个文件等超时了：开了、一直没开轨，是个空文件。删掉，不报
                if (new File(completed).delete()) {
                    recordedFilePaths.remove(completed);
                    AppLog.d(TAG, "Camera " + cameraId + " removed never-started file " + completed);
                }
                return;
            }
            AppLog.d(TAG, "Camera " + cameraId + " file closed (" + close.stage + ", "
                    + (confirmed[0] ? "on disk" : "not confirmed") + "): " + completed);
            if (writerAbandoned) {
                // 停录时没等到它收好：调用者那边跳过了这个文件（还在这里手里），这里自己验证
                validateAndCleanupFile(completed);
                return;
            }
            Handler segment = segmentHandler;
            if (close.nextSegmentIndex >= 0 && segment != null) {
                final int nextIndex = close.nextSegmentIndex;
                // 这一段交出去了（中转写入这就转存）：不再算这个录制器手里的，停录收掉的单子里没有它（stoppedFiles）
                recordedFilePaths.remove(completed);
                segment.post(() -> validateAndCleanupFile(completed));
                RecordCallback cb = callback;
                if (cb != null) {
                    segment.post(() -> cb.onSegmentSwitch(cameraId, nextIndex, completed));
                }
            }
        } finally {
            close.done.countDown();
        }
    }

    private void quitWriter() {
        writerQuit = true;
        Handler handler = writerHandler;
        if (handler != null) {
            handler.removeCallbacks(syncTick);
        }
        Looper looper = Looper.myLooper();
        if (looper != null) {
            looper.quitSafely();
        }
    }

    /** 每 5 秒 fsync 一次（写入线程上，夹在两次写之间）。一直跑到写入线程退出，没有开着的文件时什么都不做。 */
    private final Runnable syncTick = new Runnable() {
        @Override
        public void run() {
            if (writerQuit) {
                return;
            }
            try {
                syncNow();
            } catch (RuntimeException e) {
                // 不该到这里（fsync 的 IOException 在里面就换盘了）；到了也不能让写入线程死掉：这个文件算写不了
                writerBeat.endTask();
                AppLog.e(TAG, "Camera " + cameraId + " fsync tick failed", e);
                failFile("fsync-tick", e);
            }
            Handler handler = writerHandler;
            if (handler != null && !writerQuit) {
                handler.postDelayed(this, SYNC_INTERVAL_MS);
            }
        }
    };

    /**
     * fsync 一次（写入线程上）。两件事：告诉 ring 写过的哪些已经落盘、可以丢了（按 fsync 之前取的那个点，
     * 见 {@link SampleRing#syncPoint}）；盘掉了但写入还「成功」（进的是系统缓存）时，fsync 会报错 ——
     * 这是发现盘没了最早的一道，比等写入报错早。fsync 时写入线程不写，排队兜着；U 盘刷缓存时这一步卡得最久，
     * 以前它在分段线程上，和编码线程上的写同时挤这个盘。
     */
    private void syncNow() {
        RandomAccessFile file = muxerFile;
        if (file == null || !muxerStarted) {
            return;
        }
        long point = ring.syncPoint(SystemClock.elapsedRealtime());
        long start = StallWatch.now();
        writerBeat.beginTask("sync", start);
        try {
            file.getFD().sync();
        } catch (IOException e) {
            writerBeat.endTask();
            if (!relocate("fsync", e)) {
                failFile("fsync", e);
            }
            return;
        }
        writerBeat.endTask();
        StallWatch.noteOp(writerBeat, cameraId, "sync", start);
        ring.markSynced(point);
    }

    /**
     * 收掉当前 muxer 和文件（写入线程上）。
     *
     * @return 文件是不是确认落盘了：stop（写文件尾）和 fsync 都成功。没确认的话这个文件多半坏了，
     *         写过多留的那段是它最后那段的唯一副本（见 {@link #settleRing}）
     */
    private boolean closeMuxer(String stage) {
        boolean confirmed = true;
        if (muxer != null) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                    RandomAccessFile file = muxerFile;
                    if (file != null) {
                        file.getFD().sync();
                    }
                }
            } catch (Exception e) {
                confirmed = false;
                AppLog.e(TAG, "Camera " + cameraId + " Error closing muxer (" + stage + ")", e);
                noteTrouble(stage + "-close", e);
            }
            try {
                muxer.release();
            } catch (Exception e) {
                AppLog.w(TAG, "Camera " + cameraId + " Error releasing muxer: " + e.getMessage());
            }
            muxer = null;
            muxerStarted = false;
            videoTrackIndex = -1;
        }
        closeMuxerFile();
        return confirmed;
    }

    /** 旧 muxer 作废（盘已经写不进了）：能收就收，收不了不算错。 */
    private void discardMuxer() {
        if (muxer != null) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
            } catch (Exception e) {
                AppLog.w(TAG, "Camera " + cameraId + " Discarded muxer would not stop: " + e.getMessage());
            }
            try {
                muxer.release();
            } catch (Exception ignored) {
                // 作废的东西释放不掉也没什么可做的
            }
            muxer = null;
            muxerStarted = false;
            videoTrackIndex = -1;
        }
        closeMuxerFile();
    }

    private void closeMuxerFile() {
        RandomAccessFile file = muxerFile;
        muxerFile = null;
        if (file != null) {
            try {
                file.close();
            } catch (IOException e) {
                AppLog.w(TAG, "Camera " + cameraId + " Error closing muxer file: " + e.getMessage());
            }
        }
    }

    /**
     * 文件收掉之后，写过多留的那段怎么办（写入线程上）。
     *
     * <p>确认落盘了就清掉。没确认（盘掉了、stop 报错、中途写不下去了），它就是那个文件最后一段的唯一副本，
     * 先抢救成单独的文件再清 —— 清是因为接下来是新一代编码器，两代样本不能混在一个文件里。
     * 抢救出了岔子也要清，否则会混进下一个文件。</p>
     */
    private void settleRing(boolean confirmed, String stage) {
        try {
            if (!confirmed && !ring.keptIsEmpty()) {
                rescueRing(stage);
            }
        } finally {
            ring.clearKept();
        }
    }

    /**
     * 把写过多留的那段单独写成一个文件（写入线程上）。
     *
     * <p>先试现在这个目录 —— 盘也许没事，只是 stop 报了错；不行再换别的盘。写成一个就够。</p>
     */
    private void rescueRing(String stage) {
        List<SampleRing.Sample> pending = ring.keptSnapshot();
        MediaFormat format = fileFormat;
        if (pending.isEmpty() || format == null) {
            return;
        }
        long spanMs = spanMs(pending);
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(saveDirectory));
        // 按下标走：第一个目录不行时才把别的盘加进来（边遍历边加，for-each 会抛 ConcurrentModificationException）
        for (int i = 0; i < dirs.size(); i++) {
            if (writerAbandoned) {
                // 已经不再等这个写入线程了（开始前、或者试到一半）：没落盘的交给了抢救线程，这里不再开文件、不再试盘
                return;
            }
            File dir = dirs.get(i);
            writerTargetDir = dir.getAbsolutePath();
            try {
                String path = writeRescueFile(dir, pending, format);
                com.kooo.evcam.blackbox.BlackBox.noteImportant("上一个文件没确认落盘（相机 " + cameraId + "，"
                        + stage + "），把内存里的 " + (spanMs / 1000) + " 秒抢救到 " + path);
                return;
            } catch (Exception e) {
                AppLog.w(TAG, "Camera " + cameraId + " rescue to " + dir + " failed: " + e);
                deadVolumes.add(StorageHelper.volumeOf(dir.getAbsolutePath()));
                if (i == 0) {
                    try {
                        dirs.addAll(candidateDirs());
                    } catch (RuntimeException listing) {
                        AppLog.w(TAG, "Camera " + cameraId + " cannot list fallback dirs: " + listing);
                    }
                }
            }
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("上一个文件没确认落盘（相机 " + cameraId + "，"
                + stage + "），内存里的 " + (spanMs / 1000) + " 秒没地方抢救；此刻挂着的盘："
                + StorageHelper.describeMounts());
    }

    /**
     * 把这些样本写成一个独立的 mp4，文件名是第一个样本的时刻。写入线程上收文件时、放弃等写入线程时的抢救线程上都用它，
     * 各用各的 muxer。
     *
     * <p>写完 fsync：没确认落盘的抢救文件和没抢救一样。不成功就删掉并抛出。</p>
     */
    private String writeRescueFile(File dir, List<SampleRing.Sample> samples, MediaFormat format)
            throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        String path = pathNamedAt(dir, wallClockOf(samples.get(0)));
        RandomAccessFile file = new RandomAccessFile(path, "rw");
        MediaMuxer rescue = null;
        boolean ok = false;
        try {
            file.setLength(0);
            rescue = new MediaMuxer(file.getFD(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = rescue.addTrack(format);
            rescue.start();
            writeSamples(rescue, track, samples);
            rescue.stop();
            file.getFD().sync();
            ok = true;
        } finally {
            if (rescue != null) {
                try {
                    rescue.release();
                } catch (RuntimeException ignored) {
                    // 释放失败不影响结果
                }
            }
            try {
                file.close();
            } catch (IOException ignored) {
                // 同上
            }
            if (!ok) {
                new File(path).delete();
            }
        }
        recordedFilePaths.add(path);
        return path;
    }

    /**
     * 按顺序写进 muxer，时间戳从第一个样本归零；返回最后写的时间戳。
     * 每写一个都算「写进去了」（{@link #markWritten}）：换盘时补写十几秒，不能让写不进文件的裁判以为停了。
     */
    private long writeSamples(MediaMuxer target, int track, List<SampleRing.Sample> samples) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long base = samples.get(0).ptsUs;
        long last = -1L;
        for (SampleRing.Sample s : samples) {
            long pts = s.ptsUs - base;
            if (pts <= last) {
                pts = last + ptsStepUs();
            }
            last = pts;
            info.set(0, s.data.length, pts, s.keyframe ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            target.writeSampleData(track, ByteBuffer.wrap(s.data), info);
            markWritten();
        }
        return last;
    }

    /** 这些样本从头到尾多长的画面（毫秒）。 */
    private static long spanMs(List<SampleRing.Sample> samples) {
        if (samples.isEmpty()) {
            return 0L;
        }
        return Math.max(0L, samples.get(samples.size() - 1).ptsUs - samples.get(0).ptsUs) / 1000L;
    }

    /**
     * 这个样本对应的墙上时间，只给文件起名用：现在的墙上时间减去它的年龄（年龄按开机时长算）。
     * 车机睡醒后墙上时间会跳一下；按现在的钟往回推，名字和这一刻别的文件对得上。
     */
    private static long wallClockOf(SampleRing.Sample sample) {
        return System.currentTimeMillis() - (SystemClock.elapsedRealtime() - sample.queuedAtMs);
    }

    /**
     * 现在这个盘写不进了：换一个盘接着录（写入线程上）。
     *
     * <p>旧 muxer 作废。开好轨的话，写过多留的那段先补写进新文件（新文件以那段开头的时刻命名），
     * 再接着写排着的 —— 一个文件，画面连续。还没开轨（开文件、开轨这一步就写不进）的，新盘上用同一个名字开，
     * 等开轨那条命令。写过多留的那段只会是这个文件的：每个文件收的时候都清掉了。补写这一阵排队会涨，
     * 那不是 U 盘慢，不提示用户（{@link #writerRelocating}）。</p>
     *
     * @return 换成了没有。没换成时 muxer 已经没了，调用者按写不了处理（{@link #failFile}）
     */
    private boolean relocate(String why, Throwable cause) {
        if (writerAbandoned) {
            // 已经不再等这个写入线程了：不再换盘、不再开新文件，调用者按写不了处理（failFile 放掉 muxer）
            return false;
        }
        final boolean[] ok = {false};
        writerRelocating = true;
        try {
            StallWatch.runTask(writerBeat, cameraId, "relocate", () -> ok[0] = relocateNow(why, cause));
        } finally {
            writerRelocating = false;
        }
        return ok[0];
    }

    private boolean relocateNow(String why, Throwable cause) {
        String failed = saveDirectory;
        String failedVolume = StorageHelper.volumeOf(failed);
        deadVolumes.add(failedVolume);
        discardMuxer();
        String trouble = describe(cause);
        List<File> dirs;
        try {
            dirs = candidateDirs();
        } catch (RuntimeException e) {
            // 问「还能写到哪」本身出了错（读挂载表、读设置）：当作没有别的盘，按写不了处理，不能让写入线程死掉
            AppLog.e(TAG, "Camera " + cameraId + " cannot list fallback dirs", e);
            noteTrouble("relocate-candidates", e);
            dirs = new ArrayList<>();
        }
        for (File dir : dirs) {
            if (writerAbandoned) {
                return false;  // 换到一半不再等这个写入线程了：不再试下一个盘，调用者按写不了处理
            }
            writerTargetDir = dir.getAbsolutePath();
            try {
                long rescuedMs = openContinuationAt(dir);
                if (rescuedMs < 0) {
                    return false;  // 开好文件时已经不再等这个写入线程了：算没换成，调用者按写不了处理
                }
                saveDirectory = dir.getAbsolutePath();
                relocations++;
                lastTrouble = why + ": " + trouble;
                com.kooo.evcam.blackbox.BlackBox.noteImportant("录像盘 " + failedVolume + " 写不进了（相机 " + cameraId
                        + "，" + why + "：" + trouble + "），改写到 " + dir + "，补写了内存里的 "
                        + (rescuedMs / 1000) + " 秒；此刻挂着的盘：" + StorageHelper.describeMounts());
                RecordCallback cb = callback;
                Handler segment = segmentHandler;
                if (cb != null && segment != null) {
                    final File newDir = dir;
                    segment.post(() -> cb.onRecordingRelocated(cameraId, newDir, why, rescuedMs));
                }
                return true;
            } catch (Exception e) {
                AppLog.w(TAG, "Camera " + cameraId + " cannot relocate to " + dir + ": " + e);
                deadVolumes.add(StorageHelper.volumeOf(dir.getAbsolutePath()));
                discardMuxer();
            }
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("录像盘 " + failedVolume + " 写不进了（相机 " + cameraId
                + "，" + why + "：" + trouble + "），没有别的盘可换；此刻挂着的盘：" + StorageHelper.describeMounts());
        return false;
    }

    /** 除了现在这个目录，还能写到哪。 */
    private List<File> candidateDirs() {
        List<File> out = new ArrayList<>();
        if (fallbackDirs == null) {
            return out;
        }
        for (File dir : fallbackDirs.candidates(new HashSet<>(deadVolumes))) {
            if (!dir.getAbsolutePath().equals(saveDirectory)) {
                out.add(dir);
            }
        }
        return out;
    }

    /**
     * 在 dir 里开新文件接着录，写过多留的那段先补写进去（写入线程上）。
     *
     * @return 补写了多长（毫秒）。开好文件时已经不再等这个写入线程了（在新盘上开文件时卡住、回过神来）是 -1：
     *         不开轨、不补写 —— 写过多留的那段交给了抢救线程。这个没开轨的文件收文件时删掉
     */
    private long openContinuationAt(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        List<SampleRing.Sample> kept = ring.keptSnapshot();
        long rescuedMs = spanMs(kept);
        String name = writerFileName != null ? writerFileName : segmentFileName();
        String path = kept.isEmpty()
                ? freshPath(dir, name)
                : pathNamedAt(dir, wallClockOf(kept.get(0)));  // 文件名说的是画面从什么时候开始
        createMuxer(path);
        currentFilePath = path;
        recordedFilePaths.add(path);
        if (writerAbandoned) {
            return -1L;
        }
        if (fileFormat != null) {
            // 编码器不会再报一次格式：这里直接开轨
            videoTrackIndex = muxer.addTrack(fileFormat);
            muxer.start();
            muxerStarted = true;
            fileStarted = true;
            if (!kept.isEmpty()) {
                segmentBasePtsUs = kept.get(0).ptsUs;
                lastWrittenPtsUs = writeSamples(muxer, videoTrackIndex, kept);
                // 在新盘上还没落盘：等新盘上的 fsync 确认了才能丢
                ring.rewritten(SystemClock.elapsedRealtime());
            }
        }
        return rescuedMs;
    }

    /**
     * 新文件的路径：dir 下的 name。那里已经有同名的了（同一路两次换文件落在分段时间戳的同一个缓存期里、
     * 换盘时对面盘上正好有同名的），就按现在的时刻另起一个名字 —— 开文件会把它截成 0 字节，不能覆盖已有的录像。
     */
    private String freshPath(File dir, String name) {
        File file = new File(dir, name);
        return file.exists() ? pathNamedAt(dir, System.currentTimeMillis()) : file.getAbsolutePath();
    }

    /** 以这一刻命名的文件路径；撞名就往后挪一秒 —— 不能覆盖已有的文件。 */
    private String pathNamedAt(File dir, long wallMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String suffix = "_" + CameraSlots.suffixFor(cameraPosition) + ".mp4";
        for (int i = 0; ; i++) {
            File file = new File(dir, format.format(new Date(wallMs + i * 1000L)) + suffix);
            if (!file.exists()) {
                return file.getAbsolutePath();
            }
        }
    }

    /**
     * 调度编码器健康检查
     * 检测编码器是否正常工作，如果长时间无输出则尝试重建
     */
    private void scheduleEncoderHealthCheck() {
        if (stopRequested || segmentHandler == null) {
            return;  // 叫停了：不再查
        }
        if (healthCheckRunnable != null) {
            segmentHandler.removeCallbacks(healthCheckRunnable);
        }

        healthCheckRunnable = () -> {
            if (stopRequested || isReleased) {
                return;
            }
            if (!isRecording.get()) {
                // 正在分段切换、快速恢复：这一阵不查，过一会儿接着查（以前在这里就不再查了，
                // 恢复回来之后写入线程报写不下去、编码器标了不健康，也没人重建）
                scheduleEncoderHealthCheck();
                return;
            }

            // 检查编码器健康状态
            boolean needsRecovery = false;
            String reason = "";

            int frames = framesThisEncoder;
            if (!encoderHealthy) {
                needsRecovery = true;
                reason = "encoder marked unhealthy";
            } else if (!formatSeen && frames > MUXER_START_GRACE_FRAMES) {
                // 这一代编码器画了很多帧还没报输出格式。按这一代的帧数算，不按整场录像的：
                // 刚换过编码器（分段切换、重建）时整场的帧数早过了门槛，一查就误判；
                // 看的也是编码线程这一侧 —— 写入线程开轨要排在前面没写完的后面，U 盘慢时会晚几秒，那不算编码器坏
                needsRecovery = true;
                reason = "encoder gave no output format after " + frames + " frames";
            }

            if (needsRecovery) {
                AppLog.w(TAG, "Camera " + cameraId + " Encoder health check FAILED: " + reason);
                AppLog.w(TAG, "Camera " + cameraId + " Attempting to rebuild encoder...");
                com.kooo.evcam.blackbox.BlackBox.noteImportant("录像编码器不正常（相机 " + cameraId + "）："
                        + reason + "，重建");

                // 在编码线程上执行重建
                if (encoderHandler != null) {
                    encoderHandler.post(() -> StallWatch.runTask(encoderBeat, cameraId, "rebuild-encoder", this::rebuildEncoder));
                }
            } else {
                // 编码器健康，继续调度下一次检查
                scheduleEncoderHealthCheck();
            }
        };

        segmentHandler.postDelayed(healthCheckRunnable, ENCODER_HEALTH_CHECK_INTERVAL_MS);
    }

    /**
     * 重建编码器（在编码线程上执行）
     * 当检测到编码器不健康时调用
     */
    private void rebuildEncoder() {
        if (stopRequested || isReleased) {
            return;  // 叫停了：停录那边收文件，这里不再换编码器、开新文件
        }
        AppLog.d(TAG, "Camera " + cameraId + " Rebuilding encoder due to health check failure");

        // 暂停录制
        isRecording.set(false);

        try {
            // 1. 收掉旧文件：排给写入线程，它写完前面排着的再收；没确认落盘的话写过多留的那段由它抢救
            //    （下面要换编码器，两代样本不能混在一个文件里）
            if (fileQueued) {
                closeCurrentFile("rebuild", -1);
            }

            // 2. 清理旧的编码器
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception e) {
                    // Ignore
                }
                try {
                    encoder.release();
                } catch (Exception e) {
                    // Ignore
                }
                encoder = null;
            }

            if (encoderInputSurface != null) {
                try {
                    encoderInputSurface.release();
                } catch (Exception e) {
                    // Ignore
                }
                encoderInputSurface = null;
            }

            // 3. 小延迟让系统释放资源
            Thread.sleep(100);

            // 4. 重新创建编码器
            createEncoder();

            // 5. 更新 EGL 输出 Surface
            if (eglEncoder != null && encoderInputSurface != null) {
                eglEncoder.updateOutputSurface(encoderInputSurface);
            }

            // 重建这一阵里叫停了：不再开新文件，也不再置回「在录」
            if (stopRequested) {
                AppLog.d(TAG, "Camera " + cameraId + " stop requested during encoder rebuild, no new file");
                return;
            }

            // 6. 开新文件（写入线程上开，这个盘开不了它自己换盘）
            segmentIndex++;
            openNextFile();

            // 7. 重置状态（每个文件的 PTS 基准写入线程开文件时清）
            encoderHealthy = true;

            // 8. 恢复录制
            isRecording.set(true);

            AppLog.d(TAG, "Camera " + cameraId + " Encoder rebuilt successfully, new file: " + queuedFileName);
            com.kooo.evcam.blackbox.BlackBox.noteImportant("录像编码器重建好了（相机 " + cameraId + "），新文件 "
                    + queuedFileName);

            // 9. 继续健康检查
            segmentHandler.post(() -> scheduleEncoderHealthCheck());

            // 10. 重新调度分段定时器
            segmentHandler.post(() -> scheduleNextSegment());

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to rebuild encoder", e);
            noteTrouble("rebuild", e);

            // 重建失败：5 秒后再试；一直修不好的话，写不进文件的裁判会在 15 秒时报一次、停
            recoveryAttempts++;
            AppLog.w(TAG, "Camera " + cameraId + " Will retry encoder rebuild in "
                + (RECOVERY_RETRY_INTERVAL_MS / 1000) + "s (attempt " + recoveryAttempts + ")");
            scheduleRecoveryRetry();
        }
    }

    /**
     * 有数据写进文件了。
     *
     * <p>第一次写进去的那一刻，录像才算真的开始：分段计时从这里起，外面的「录制中」也从这里起
     * （以前靠每 500 ms 看一次文件大小来发现）。写入线程上调，真的交给 muxer 之后才算 ——
     * 写不进文件的裁判量的是真写进去的，不是编码出来的；分段计时和回调派到分段线程上。</p>
     */
    private void markWritten() {
        lastWriteUptimeMs = android.os.SystemClock.uptimeMillis();
        everWrote = true;
        if (hasFirstWrite) {
            return;
        }
        hasFirstWrite = true;
        AppLog.d(TAG, "Camera " + cameraId + " first data written");
        Handler segment = segmentHandler;
        if (segment != null) {
            segment.post(() -> {
                // 看叫停没有，不看「在录」：写入线程比编码线程晚，第一笔写进去时编码线程可能正好在换编码器、
                // 「在录」暂时是关的，那时一看就把这次通知丢了（上一次录像的由相机层按代数挡掉）
                if (stopRequested || isReleased) {
                    return;
                }
                // 【核心】首次写入后才启动分段定时器：分段时长是「有效录制时长」，不是「尝试录制时长」
                scheduleNextSegment();
                if (callback != null) {
                    callback.onFirstDataWritten(cameraId);
                }
            });
        }
    }

    /**
     * 写不进文件的裁判：每 {@link #WRITE_CHECK_MS} 看一眼，从开录或最后一次写进文件起
     * {@link #WRITE_STALL_MS} 没新数据就报一次、不再看。快速恢复期间 {@code isRecording} 会暂时为 false，
     * 所以这里不看它，只看有没有被 {@link #cancelWriteStallCheck} 撤掉（真正停录、释放）。
     */
    private void scheduleWriteStallCheck() {
        cancelWriteStallCheck();
        writeStallCheck = new Runnable() {
            @Override
            public void run() {
                if (isReleased || writeStallCheck != this) {
                    return;
                }
                long now = android.os.SystemClock.uptimeMillis();
                long since = everWrote ? now - lastWriteUptimeMs : now - startedUptimeMs;
                if (since >= WRITE_STALL_MS) {
                    writeStallCheck = null;
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("录像写不进文件：相机 " + cameraId + " 已 "
                            + (since / 1000) + " 秒没有新数据（" + describeWriteState()
                            + "）；此刻挂着的盘：" + com.kooo.evcam.storage.StorageState.current().mounts);
                    if (callback != null) {
                        callback.onWriteStalled(cameraId, since, everWrote);
                    }
                    return;
                }
                Handler segment = segmentHandler;
                if (segment != null) {
                    segment.postDelayed(this, WRITE_CHECK_MS);
                }
            }
        };
        segmentHandler.postDelayed(writeStallCheck, WRITE_CHECK_MS);
    }

    private void cancelWriteStallCheck() {
        if (writeStallCheck != null && segmentHandler != null) {
            segmentHandler.removeCallbacks(writeStallCheck);
        }
        writeStallCheck = null;
    }

    /**
     * 验证并清理所有录制的文件
     * @param skip 还在写入线程手里的那个文件（停录没等到它收好）：不碰，它收好时自己验证；没有传 null
     * @return 被删除的文件名列表
     */
    private List<String> validateAndCleanupAllFiles(String skip) {
        List<String> deletedFiles = new ArrayList<>();
        // 写入线程、抢救线程可能还在加：拿一份拷贝来走
        List<String> files;
        synchronized (recordedFilePaths) {
            files = new ArrayList<>(recordedFilePaths);
        }

        AppLog.d(TAG, "Camera " + cameraId + " validating " + files.size() + " recorded files");

        for (String filePath : files) {
            if (filePath.equals(skip)) {
                continue;
            }
            String deletedFileName = validateAndCleanupFile(filePath);
            if (deletedFileName != null) {
                deletedFiles.add(deletedFileName);
            }
        }
        
        if (!deletedFiles.isEmpty()) {
            AppLog.w(TAG, "Camera " + cameraId + " deleted " + deletedFiles.size() + " corrupted files: " + deletedFiles);
        }
        
        return deletedFiles;
    }

    /**
     * 验证并清理损坏的文件
     * @return 如果文件被删除，返回文件名；否则返回 null
     */
    private String validateAndCleanupFile(String filePath) {
        if (filePath == null) {
            return null;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }

        long fileSize = file.length();

        if (fileSize < MIN_VALID_FILE_SIZE) {
            AppLog.w(TAG, "Camera " + cameraId + " Video file too small: " + filePath + " (" + fileSize + " bytes). Deleting...");
            file.delete();
            return file.getName();
        } else {
            AppLog.d(TAG, "Camera " + cameraId + " Video file validated: " + filePath + " (" + (fileSize / 1024) + " KB)");
            return null;
        }
    }
}
