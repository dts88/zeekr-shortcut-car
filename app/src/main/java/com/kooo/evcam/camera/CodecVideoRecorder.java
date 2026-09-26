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
import android.view.Surface;

import com.kooo.evcam.AppLog;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 使用 MediaCodec + MediaMuxer 进行视频编码和录制
 * 用于 L6/L7 等不支持 MediaRecorder 直接录制的车机平台
 * 
 * 工作流程：
 * 1. 创建 MediaCodec 编码器，获取其输入 Surface
 * 2. 使用 EglSurfaceEncoder 将 Camera 的帧渲染到编码器输入 Surface
 * 3. 从 MediaCodec 获取编码后的数据
 * 4. 通过 MediaMuxer 写入 MP4 文件
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
    /** 编码线程收到帧的心跳和各步用时，给卡顿监测用（见 StallWatch）。 */
    private Heartbeat encoderBeat;
    private final int width;
    private final int height;

    // MediaCodec 相关
    private MediaCodec encoder;
    private Surface encoderInputSurface;
    private MediaCodec.BufferInfo bufferInfo;

    // MediaMuxer 相关
    private MediaMuxer muxer;
    private int videoTrackIndex = -1;
    private volatile boolean muxerStarted = false;
    /** muxer 写的那个文件，我们自己开的：muxer 内部 dup 了描述符，这一份留着 fsync。 */
    private volatile RandomAccessFile muxerFile;
    /** 每开一个文件加一。分段线程上 fsync 报错时，凭它认出说的是不是还是这个文件。 */
    private volatile int muxerSerial;

    // 盘写不进时换盘（见 SampleRing）
    private final SampleRing ring = new SampleRing();
    /** 环里样本那一代编码器的输出格式；补写时用它开轨。 */
    private MediaFormat ringFormat;
    private int ringGeneration = -1;
    /** 每建一个编码器加一：两代编码器的样本不能写进同一个文件。 */
    private int encoderGeneration;
    private Runnable syncTick;
    private FallbackDirs fallbackDirs;
    /** 这次录像里写不进的卷名，不再试。 */
    private final Set<String> deadVolumes = new HashSet<>();
    private int relocations;

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
    private String currentFilePath;
    
    // 缓存的录制 Surface，避免重复创建导致内存泄漏
    private Surface cachedRecordSurface = null;
    
    // 时间戳基准（用于计算相对时间戳，供输入端使用）
    private long firstFrameTimestampNs = -1;
    
    // 分段开始时间（用于计算 PTS，基于系统时间而非帧数）
    // 这样可以准确反映实际录制时长，不受帧率波动影响
    private long segmentStartTimeNs = 0;
    
    // 编码器输出帧计数（仅用于日志和统计，不再用于 PTS 计算）
    private long encodedOutputFrameCount = 0;
    /** 上一次写入 muxer 的 PTS，用于保证严格单调递增；-1 表示还没写过帧。 */
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
    /** 本分段第一帧的编码器时间戳，用于把每段的 PTS 归零；-1 表示本段还没开始。 */
    private long segmentBasePtsUs = -1L;

    // 分段录制相关
    private long segmentDurationMs = 60000;  // 分段时长，默认1分钟，可通过 setSegmentDuration 配置
    private static final long SEGMENT_DURATION_COMPENSATION_MS = 0;  // 分段时长补偿（H3修复后定时器更精确，不再需要补偿）
    private static final long MIN_VALID_FILE_SIZE = 1 * 1024;   // 最小有效文件大小 1KB（降低阈值，短录制也能保存）
    
    // 使用独立的后台线程处理分段和文件 I/O 操作，避免阻塞主线程导致 ANR
    private HandlerThread segmentThread;
    private Handler segmentHandler;
    
    private Runnable segmentRunnable;
    private int segmentIndex = 0;
    private String saveDirectory;
    private String cameraPosition;
    private VideoRecorder.SegmentTimestampProvider timestampProvider;  // 分段时间戳提供者（用于多路同步）
    private long lastFileSize = 0;
    private static final long FILE_SIZE_CHECK_INTERVAL_MS = 5000;
    private static final long FIRST_CHECK_DELAY_MS = 500;  // 首次检查延迟（更快检测首次写入）
    private Runnable fileSizeCheckRunnable;
    private long recordedFrameCount = 0;
    private List<String> recordedFilePaths = new ArrayList<>();  // 本次录制的所有文件路径
    
    // 首次写入检测（与 VideoRecorder 保持一致）
    private static final long FIRST_WRITE_TIMEOUT_MS = 10000;  // 首次写入超时（10秒）
    private boolean hasFirstWrite = false;  // 是否已有首次写入
    private Runnable firstWriteTimeoutRunnable;  // 首次写入超时检查任务
    
    // 快速恢复机制
    private static final long RECOVERY_RETRY_INTERVAL_MS = 5000;  // 恢复重试间隔：5秒
    private static final int MAX_RECOVERY_ATTEMPTS = 60;  // 最大重试次数（5秒 × 60 = 5分钟内重试）
    private int recoveryAttempts = 0;  // 当前重试次数
    private Runnable recoveryRunnable;  // 恢复重试任务

    // 编码器健康检查
    private static final long ENCODER_HEALTH_CHECK_INTERVAL_MS = 3000;  // 健康检查间隔：3秒
    private static final int MAX_FRAMES_WITHOUT_OUTPUT = 30;  // 无输出的最大帧数阈值
    private long lastEncoderOutputTime = 0;  // 最后一次编码器输出时间
    private int framesWithoutEncoderOutput = 0;  // 无编码器输出的连续帧数
    private volatile boolean encoderHealthy = true;  // 编码器是否健康

    /**
     * 最后一次真的往文件里写进数据的时刻（开机时长，深睡不算）。
     *
     * <p>录制管线的看门狗拿它判断「界面说在录、实际写不进文件」。相机有帧、编码线程在转，
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
        this.encoderBeat = StallWatch.encoder(cameraId);
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
     * 设置是否启用时间水印
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
    /** 上一秒写进文件的字节数，用来算实时码率。 */
    private long bytesThisSecond;
    private long bitrateWindowStartMs;
    private String liveBitrateText = "";

    /**
     * 记一笔刚写进文件的字节数，每满一秒折算成码率。
     *
     * <p>用的是<b>真正写进 muxer 的大小</b>，不是设置里的目标码率 ——
     * 编码器给的是可变码率，画面越复杂写得越多，目标值只是个上限。</p>
     *
     * <p>这一步几乎不花钱：字节数本来就在手上，而水印位图本来就每秒重画一次
     * （秒数变了才重画）。所以只是把一个已有的数字接到一行已有的文字上。</p>
     */
    private void noteEncodedBytes(int size) {
        if (size <= 0) {
            return;
        }
        lastWriteUptimeMs = android.os.SystemClock.uptimeMillis();
        everWrote = true;
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
        // 于是 encodedOutputFrameCount % 300 == 0 那一刻可能永远撞不上，
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

    /** 本窗口内写进文件的帧数，和字节数用同一个窗口结算。 */
    private int framesThisSecond;

    /** 码率取样窗口。一秒够用了，再快也看不清。 */
    private static final long BITRATE_WINDOW_MS = 1000L;

    /**
     * 录像左上角标的那行字：应用名 + 版本号。
     *
     * <p>由调用方给 —— 这个类拿不到 Context。录出来的文件常常是拿去当证据
     * 或者发给别人的，落上是哪个应用、哪个版本录的，回头出问题才对得上。</p>
     */
    public void setBrandLine(String line) {
        this.brandLine = line == null ? "" : line;
    }

    private String brandLine = "";

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

    /**
     * 检查是否启用了时间水印
     */
    public boolean isWatermarkEnabled() {
        return watermarkEnabled;
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
     * 获取分段时长（毫秒）
     */
    public long getSegmentDuration() {
        return segmentDurationMs;
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
     * <p>只在 {@link #nextPtsUs(long)} 的兜底分支里用到 —— 正常情况下时间戳来自
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
     * @param encoderPtsUs 编码器给出的时间戳
     * @return 严格大于上一帧的时间戳
     */
    private long nextPtsUs(long encoderPtsUs) {
        // 每个分段都是一个新的 muxer，PTS 要从 0 开始。
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
     * 获取当前配置的帧率
     */
    public int getFrameRate() {
        return frameRate;
    }


    /**
     * 准备录制
     * 
     * 警告：此方法包含阻塞操作（CountDownLatch.await），不建议在主线程调用
     * 如果必须在主线程调用，可能导致 ANR。建议在后台线程调用或使用 prepareRecordingAsync()
     * 
     * @param filePath 输出文件路径
     * @return 用于 Camera 输出的 SurfaceTexture
     */
    public SurfaceTexture prepareRecording(String filePath) {
        // 检查是否在主线程调用（可能导致 ANR）
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppLog.w(TAG, "Camera " + cameraId + " WARNING: prepareRecording() called on MAIN THREAD! " +
                    "This may cause ANR due to blocking operations. Consider using prepareRecordingAsync().");
        }
        
        if (isRecording.get()) {
            AppLog.w(TAG, "Camera " + cameraId + " is already recording");
            return inputSurfaceTexture;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Preparing codec recording: " + width + "x" + height);

        // 保存录制参数
        this.currentFilePath = filePath;
        this.segmentIndex = 0;
        this.recordedFrameCount = 0;
        this.firstFrameTimestampNs = -1;  // 重置时间戳基准
        this.encodedOutputFrameCount = 0;  // 重置编码输出帧计数
        this.lastWrittenPtsUs = -1L;
        this.segmentBasePtsUs = -1L;

        // 重置健康检查状态
        this.encoderHealthy = true;
        this.framesWithoutEncoderOutput = 0;
        this.lastEncoderOutputTime = System.currentTimeMillis();

        // 重置换盘状态
        ring.clear();
        ringFormat = null;
        ringGeneration = -1;
        deadVolumes.clear();
        relocations = 0;

        // 清空并初始化本次录制的文件列表
        recordedFilePaths.clear();
        recordedFilePaths.add(filePath);

        // 从文件路径中提取保存目录和摄像头位置
        File file = new File(filePath);
        this.saveDirectory = file.getParent();
        String fileName = file.getName();
        int lastUnderscoreIndex = fileName.lastIndexOf('_');
        if (lastUnderscoreIndex > 0 && fileName.endsWith(".mp4")) {
            this.cameraPosition = fileName.substring(lastUnderscoreIndex + 1, fileName.length() - 4);
        } else {
            this.cameraPosition = "unknown";
        }

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

            // 创建 MediaCodec 编码器
            createEncoder();

            // 创建 MediaMuxer
            createMuxer(filePath);

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
                    // 那块贴图在初始化时画一次，之后不再重画
                    eglEncoder.setBrandLine(brandLine);
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
                                framesSinceLastDrain++;
                            }

                            // 自适应 drain 控制：根据时间间隔决定是否 drain
                            // 优化：使用更激进的批量策略，减少系统调用开销
                            long currentTimeMs = System.currentTimeMillis();
                            // 优化：增加帧数阈值到 10 帧，进一步减少 drain 次数
                            if (currentTimeMs - lastDrainTimeMs >= currentDrainIntervalMs || framesSinceLastDrain >= 10) {
                                // 从编码器获取输出数据并写入 muxer
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

                    // 设置时间水印（如果启用）
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
     * 准备录制回调接口
     */
    public interface PrepareCallback {
        /**
         * 准备完成回调
         * @param success 是否成功
         * @param surfaceTexture 成功时返回的 SurfaceTexture，失败时为 null
         * @param errorMessage 失败时的错误信息，成功时为 null
         */
        void onPrepareComplete(boolean success, SurfaceTexture surfaceTexture, String errorMessage);
    }
    
    /**
     * 异步准备录制（推荐使用）
     * 
     * 此方法在后台线程执行准备操作，完成后在主线程回调
     * 避免在主线程执行阻塞操作导致 ANR
     * 
     * @param filePath 输出文件路径
     * @param callback 准备完成回调
     */
    public void prepareRecordingAsync(String filePath, PrepareCallback callback) {
        new Thread(() -> {
            try {
                SurfaceTexture result = prepareRecording(filePath);
                if (callback != null) {
                    // 在主线程回调
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (result != null) {
                            callback.onPrepareComplete(true, result, null);
                        } else {
                            callback.onPrepareComplete(false, null, "Preparation failed");
                        }
                    });
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " prepareRecordingAsync failed", e);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> 
                        callback.onPrepareComplete(false, null, e.getMessage()));
                }
            }
        }, "CodecRecorderPrepare-" + cameraId).start();
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

        AppLog.d(TAG, "Camera " + cameraId + " Starting codec recording");

        // 记录分段开始时间（用于 PTS 计算）
        segmentStartTimeNs = System.nanoTime();
        encodedOutputFrameCount = 0;
        lastWrittenPtsUs = -1L;
        segmentBasePtsUs = -1L;
        
        // 重置首次写入状态
        hasFirstWrite = false;
        lastFileSize = 0;
        
        isRecording.set(true);
        // 从现在起盯着这一路；录制 Surface 要等会话重建后才出帧，宽限期够它建好
        encoderBeat.arm(StallWatch.now(), StallRules.ARM_GRACE_MS);

        // 注意：不再使用单独的编码循环
        // 帧的处理直接在 onFrameAvailable 回调中完成（该回调在 encoderHandler 上执行）
        // 这样避免了 Handler 死锁问题

        // 【重要】分段定时器延迟到首次写入后启动
        // 这样可以确保：
        // 1. 摄像头启动慢或需要修复时，用户只会感觉"启动慢"而不是录制空视频
        // 2. 钉钉指定时长录制时，实际录制时长是有效的
        // scheduleNextSegment() 将在 scheduleFileSizeCheck() 检测到首次写入时调用

        // 启动首次写入超时检查
        scheduleFirstWriteTimeout();

        // 启动文件大小检查
        scheduleFileSizeCheck();

        // 启动编码器健康检查
        scheduleEncoderHealthCheck();

        // 每 5 秒 fsync 一次：内存环靠它知道什么落盘了，盘掉线也靠它最早发现
        scheduleSyncTick();

        if (callback != null && segmentIndex == 0) {
            callback.onRecordStart(cameraId);
        }

        AppLog.d(TAG, "Camera " + cameraId + " Codec recording started");
        return true;
    }

    /**
     * 停止录制
     */
    public void stopRecording() {
        if (!isRecording.get()) {
            AppLog.w(TAG, "Camera " + cameraId + " Not recording");
            return;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Stopping codec recording");

        // 立即标记停止状态，防止新帧处理
        isRecording.set(false);
        encoderBeat.disarm();

        // 取消所有定时器和任务
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
            segmentRunnable = null;
        }
        if (fileSizeCheckRunnable != null) {
            segmentHandler.removeCallbacks(fileSizeCheckRunnable);
            fileSizeCheckRunnable = null;
        }
        // 取消首次写入超时检查
        cancelFirstWriteTimeout();
        
        // 取消恢复重试任务
        if (recoveryRunnable != null) {
            segmentHandler.removeCallbacks(recoveryRunnable);
            recoveryRunnable = null;
        }
        recoveryAttempts = 0;

        // 取消健康检查任务
        if (healthCheckRunnable != null) {
            segmentHandler.removeCallbacks(healthCheckRunnable);
            healthCheckRunnable = null;
        }
        cancelSyncTick();

        // 文件收好了没、确认落盘了没（编码线程上填）
        final boolean[] closed = {false};
        final boolean[] confirmed = {false};

        // 在编码线程上执行停止操作
        if (encoderHandler != null) {
            final Object stopLock = new Object();
            encoderHandler.post(() -> {
                try {
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
                            // 排空编码器
                            drainEncoder(true);
                        } catch (Exception e) {
                            AppLog.e(TAG, "Camera " + cameraId + " Error signaling end of stream", e);
                        }
                    }

                    // 收文件：写文件尾、fsync
                    confirmed[0] = closeMuxer("stop");
                    closed[0] = true;

                    AppLog.d(TAG, "Camera " + cameraId + " Codec recording stopped on encoder thread, frames recorded: " + recordedFrameCount);
                } catch (Exception e) {
                    AppLog.e(TAG, "Camera " + cameraId + " Error in stopRecording on encoder thread", e);
                } finally {
                    synchronized (stopLock) {
                        stopLock.notifyAll();
                    }
                }
            });

            // 等待停止完成（最多3秒）
            synchronized (stopLock) {
                try {
                    stopLock.wait(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // 文件没确认落盘（盘掉了、或者编码线程卡住没收成）：内存里的最后一段抢救出来
        settleRing(closed[0] && confirmed[0], "stop");

        // 验证并清理所有录制的文件
        List<String> deletedFiles = validateAndCleanupAllFiles();

        AppLog.d(TAG, "Camera " + cameraId + " Codec recording stopped, frames recorded: " + recordedFrameCount);

        if (callback != null) {
            callback.onRecordStop(cameraId);
            // 通知损坏文件被删除
            if (!deletedFiles.isEmpty()) {
                callback.onCorruptedFilesDeleted(cameraId, deletedFiles);
            }
        }
        
        recordedFilePaths.clear();
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

        if (isRecording.get()) {
            stopRecording();
        }

        // 释放 EGL 渲染器
        if (eglEncoder != null) {
            eglEncoder.release();
            eglEncoder = null;
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

        // 释放 muxer
        closeMuxer("release");
        ring.clear();

        // 停止编码线程
        if (encoderThread != null) {
            StallWatch.unwatchLooper("Encoder-" + cameraId);
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
     * 获取当前文件路径
     */
    public String getCurrentFilePath() {
        return currentFilePath;
    }

    /**
     * 检查是否正在录制
     */
    public boolean isRecording() {
        return isRecording.get();
    }

    /** 距最后一次写进文件过了多久（开机时长，深睡不算）；这次录制还没写出过数据时返回 -1。 */
    public long msSinceLastWrite(long nowUptimeMs) {
        return everWrote ? nowUptimeMs - lastWriteUptimeMs : -1L;
    }

    /** 黑匣子里用的一行现状：录制器自己以为在不在录、编码器好不好、写到哪个文件、最近一次错在哪。 */
    public String describeWriteState() {
        return "recording=" + isRecording.get() + " encoderHealthy=" + encoderHealthy
                + " muxerStarted=" + muxerStarted + " recoveryAttempts=" + recoveryAttempts
                + " segment=" + segmentIndex
                + " file=" + (currentFilePath == null ? "none" : new File(currentFilePath).getName())
                + " dir=" + saveDirectory + " relocations=" + relocations
                + " ring=" + (ring.spanMs() / 1000) + "s/" + (ring.bytes() >> 20) + "MB"
                + (lastTrouble.isEmpty() ? "" : " lastTrouble=" + lastTrouble);
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
        encoderGeneration++;
        // 检测并选择最优编码格式（forceH264 开启时固定 H.264）
        mimeType = selectBestEncoder();

        int effectiveFrameRate = frameRate;

        // 码率只有一条公式（TargetBitrate），两种编码都走它 —— 它自己知道 H.264
        // 要的比 HEVC 多。以前兼容模式走另一条公式，于是「强制 H.264」这个为了
        // 兼容而存在的开关，反而成了码率最高、画质最好的那条路。
        int effectiveBitrate = calculateOptimalBitrate();

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
     * 创建 MediaMuxer。
     *
     * <p>文件由我们自己打开、把描述符交给 muxer（它内部会 dup 一份），这样才能对它 fsync：
     * 写进去不等于落盘，盘掉线时留在系统缓存里的那些就没了（见 {@link SampleRing}）。</p>
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
        muxerSerial++;
        videoTrackIndex = -1;
        muxerStarted = false;

        AppLog.d(TAG, "Camera " + cameraId + " Muxer created: " + filePath);
    }

    // 注意：encodingLoop() 方法已被移除
    // 帧处理现在直接在 onFrameAvailable 回调中完成
    // 这样可以避免 Handler 死锁问题

    /**
     * 排空编码器输出（优化版 - 不降低画质）
     * 
     * 优化策略：
     * - 使用批量处理，一次 drain 最多处理 DRAIN_BATCH_SIZE 帧
     * - 使用零超时非阻塞模式，快速返回避免阻塞渲染线程
     * - 捕获 IllegalStateException 并标记编码器不健康
     * - 跟踪无输出的帧数，用于健康检查
     */
    private void drainEncoder(boolean endOfStream) {
        if (encoder == null) {
            return;
        }

        // 优化：使用零超时非阻塞模式，快速检查是否有输出
        final int TIMEOUT_USEC = endOfStream ? 10000 : 0;  // 结束状态等待，正常状态非阻塞
        boolean gotOutput = false;
        int processedFrames = 0;  // 本次 drain 已处理帧数

        try {
            while (processedFrames < DRAIN_BATCH_SIZE) {  // 批量处理限制
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
                    // 结束状态且超时，继续等待
                    if (processedFrames > 0) {
                        break;  // 已经处理了一些帧，可以返回了
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 输出格式变化，添加视频轨道
                    if (muxerStarted) {
                        AppLog.w(TAG, "Camera " + cameraId + " Format changed twice");
                    } else {
                        startMuxerTrack(encoder.getOutputFormat());
                        AppLog.d(TAG, "Camera " + cameraId + " Muxer started, track=" + videoTrackIndex);
                    }
                    gotOutput = true;
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferIndex);

                    if (encodedData == null) {
                        AppLog.e(TAG, "Camera " + cameraId + " Encoder output buffer " + outputBufferIndex + " was null");
                    } else if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // 配置数据，忽略（已在 FORMAT_CHANGED 中处理）
                        bufferInfo.size = 0;
                    }

                    if (bufferInfo.size != 0) {
                        if (!muxerStarted) {
                            AppLog.e(TAG, "Camera " + cameraId + " Muxer not started but got data");
                        } else {
                            writeSample(encodedData, bufferInfo);

                            encodedOutputFrameCount++;
                            lastEncoderOutputTime = System.currentTimeMillis();
                            gotOutput = true;
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

        // 更新无输出帧计数器
        if (gotOutput) {
            framesWithoutEncoderOutput = 0;
        } else {
            framesWithoutEncoderOutput++;
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
                    if (!muxerStarted) {
                        startMuxerTrack(encoder.getOutputFormat());
                    }
                    gotOutput = true;
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferIndex);

                    if (encodedData != null && bufferInfo.size != 0) {
                        if (muxerStarted) {
                            writeSample(encodedData, bufferInfo);

                            encodedOutputFrameCount++;
                            lastEncoderOutputTime = System.currentTimeMillis();
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
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
        }

        segmentRunnable = () -> {
            if (isRecording.get() && encoderHandler != null) {
                AppLog.d(TAG, "Camera " + cameraId + " Scheduling segment switch on encoder thread");
                // 在编码线程上执行切换，避免线程冲突
                encoderHandler.post(() -> StallWatch.runTask(encoderBeat, cameraId, "segment-switch", this::switchToNextSegment));
            }
        };

        // 延迟执行（使用配置的分段时长 + 补偿时间）
        // 补偿编码器初始化延迟和停止时的帧丢失
        long actualDelayMs = segmentDurationMs + SEGMENT_DURATION_COMPENSATION_MS;
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
        if (!isRecording.get() || isReleased) {
            AppLog.w(TAG, "Camera " + cameraId + " Skipping segment switch (not recording or released)");
            return;
        }
        
        AppLog.d(TAG, "Camera " + cameraId + " Starting segment switch on encoder thread");
        
        boolean switchSuccess = false;
        
        try {
            // 1. 停止当前录制（会排空编码器、停止 Muxer）
            stopRecordingForSegmentSwitch();
            
            // 2. 验证当前文件（在主线程上执行，因为是 IO 操作）
            final String previousFilePath = currentFilePath;
            segmentHandler.post(() -> validateAndCleanupFile(previousFilePath));

            // 3. 准备下一段
            segmentIndex++;

            // 重置分段开始时间和帧计数
            segmentStartTimeNs = System.nanoTime();
            encodedOutputFrameCount = 0;
            lastWrittenPtsUs = -1L;
            segmentBasePtsUs = -1L;
            // 不重置 firstFrameTimestampNs，保持 EGL 时间戳单调递增

            // 4. 开下一个文件（这个盘开不了就换盘）
            openNextFile();
            String nextSegmentPath = currentFilePath;
            
            // 5. 重新开始录制
            isRecording.set(true);
            switchSuccess = true;
            
            // 成功：重置恢复计数器
            recoveryAttempts = 0;
            
            AppLog.d(TAG, "Camera " + cameraId + " Switched to segment " + segmentIndex + ": " + nextSegmentPath);

            if (callback != null) {
                final int newIndex = segmentIndex;
                final String completedPath = previousFilePath;  // 已完成的文件路径
                segmentHandler.post(() -> callback.onSegmentSwitch(cameraId, newIndex, completedPath));
            }

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
            // 失败：启动快速恢复机制
            recoveryAttempts++;
            if (recoveryAttempts <= MAX_RECOVERY_ATTEMPTS) {
                // 快速重试（5秒后）
                AppLog.w(TAG, "Camera " + cameraId + " Segment switch failed, quick retry in " 
                    + (RECOVERY_RETRY_INTERVAL_MS / 1000) + "s (attempt " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS + ")");
                scheduleRecoveryRetry();
            } else {
                // 超过最大重试次数，回到正常分段间隔
                AppLog.w(TAG, "Camera " + cameraId + " Max recovery attempts reached, will retry in " 
                    + (segmentDurationMs / 1000) + " seconds");
                recoveryAttempts = 0;  // 重置计数器
                segmentHandler.post(() -> scheduleNextSegment());
            }
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
            if (!isReleased && encoderHandler != null) {
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
        AppLog.d(TAG, "Camera " + cameraId + " Attempting recovery (attempt " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS + ")");
        
        boolean recoverySuccess = false;
        
        try {
            // 确保编码器和 EGL 已准备好
            if (encoder == null) {
                createEncoder();
                if (eglEncoder != null && encoderInputSurface != null) {
                    eglEncoder.updateOutputSurface(encoderInputSurface);
                }
            }
            
            // 开新文件（这个盘开不了就换盘）
            if (muxer == null) {
                openNextFile();
            }
            
            // 重置分段开始时间和帧计数
            segmentStartTimeNs = System.nanoTime();
            encodedOutputFrameCount = 0;
            lastWrittenPtsUs = -1L;
            segmentBasePtsUs = -1L;
            
            // 恢复录制
            isRecording.set(true);
            recoverySuccess = true;
            
            // 成功：重置恢复计数器
            recoveryAttempts = 0;
            
            AppLog.d(TAG, "Camera " + cameraId + " Recovery successful, recording resumed: " + currentFilePath);
            com.kooo.evcam.blackbox.BlackBox.noteImportant("录像恢复了（相机 " + cameraId + "），新文件 "
                    + new File(currentFilePath).getName());
            
            // 调度正常的1分钟定时器
            segmentHandler.post(() -> scheduleNextSegment());
            
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Recovery attempt failed", e);
            // 5 秒一次、最多 60 次：第一次和之后每一分钟记一行，别的只算次数
            if (recoveryAttempts <= 1 || recoveryAttempts % 12 == 0) {
                noteTrouble("recovery#" + recoveryAttempts, e);
            }
            isRecording.set(false);
            
            // 继续快速重试或回到正常间隔
            recoveryAttempts++;
            if (recoveryAttempts <= MAX_RECOVERY_ATTEMPTS) {
                AppLog.w(TAG, "Camera " + cameraId + " Recovery failed, quick retry in " 
                    + (RECOVERY_RETRY_INTERVAL_MS / 1000) + "s (attempt " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS + ")");
                scheduleRecoveryRetry();
            } else {
                AppLog.w(TAG, "Camera " + cameraId + " Max recovery attempts reached, will retry in "
                    + (segmentDurationMs / 1000) + " seconds");
                com.kooo.evcam.blackbox.BlackBox.noteImportant("录像快速恢复试满 " + MAX_RECOVERY_ATTEMPTS
                        + " 次都没成（相机 " + cameraId + "），停止快速重试");
                recoveryAttempts = 0;
                segmentHandler.post(() -> scheduleNextSegment());
            }
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
        
        // 2. 排空编码器（drainEncoder 现在在同一线程执行，不会有竞争）
        if (encoder != null) {
            try {
                drainEncoder(false);  // 先排空已有数据
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " Error draining encoder during segment switch", e);
            }
        }
        
        // 3. 收文件。没确认落盘的话（盘掉了），内存环里的最后一段先抢救出来 ——
        //    下面要换编码器，两代样本不能混在一个文件里
        settleRing(closeMuxer("segment-switch"), "segment-switch");
        
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
            AppLog.d(TAG, "Camera " + cameraId + " using provider timestamp: " + timestamp);
        } else {
            // 回退到独立生成时间戳（兼容旧逻辑）
            timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            AppLog.d(TAG, "Camera " + cameraId + " using local timestamp: " + timestamp);
        }
        return timestamp + "_" + CameraSlots.suffixFor(cameraPosition) + ".mp4";
    }

    // ===== 盘写不进时换盘：内存环、fsync、抢救 =====

    /** 录像盘写不进时还能写到哪些目录，按优先顺序；相机层提供，它知道此刻挂着哪些盘。 */
    public interface FallbackDirs {
        List<File> candidates(Set<String> deadVolumes);
    }

    public void setFallbackDirs(FallbackDirs dirs) {
        this.fallbackDirs = dirs;
    }

    private static final long SYNC_INTERVAL_MS = 5_000L;
    /** muxer 自己还攒着最近一秒左右的样本没写出去；fsync 时刻往前留这么多余量才算落盘。 */
    private static final long SYNC_SLACK_MS = 3_000L;

    /**
     * 每 5 秒把文件 fsync 一次（在分段线程上，不占编码线程）。
     *
     * <p>两件事：告诉内存环哪些样本已经落盘、可以丢了；盘掉了但写入还「成功」（进的是系统缓存）时，
     * fsync 会报错 —— 这是发现盘没了最早的一道，比等写入报错早。</p>
     */
    private void scheduleSyncTick() {
        cancelSyncTick();
        syncTick = () -> {
            if (!isRecording.get() || isReleased) {
                return;
            }
            RandomAccessFile file = muxerFile;
            final int serial = muxerSerial;
            if (file != null && muxerStarted) {
                long started = System.currentTimeMillis();
                try {
                    file.getFD().sync();
                    ring.markSyncedBefore(started - SYNC_SLACK_MS);
                } catch (IOException e) {
                    Handler handler = encoderHandler;
                    if (handler != null) {
                        handler.post(() -> {
                            // 这期间文件可能已经换过了：那这次报错说的是旧文件，不理
                            if (muxerSerial != serial || !isRecording.get() || muxer == null) {
                                return;
                            }
                            if (!relocate("fsync", e)) {
                                encoderHealthy = false;
                            }
                        });
                    }
                }
            }
            Handler segment = segmentHandler;
            if (segment != null && syncTick != null) {
                segment.postDelayed(syncTick, SYNC_INTERVAL_MS);
            }
        };
        segmentHandler.postDelayed(syncTick, SYNC_INTERVAL_MS);
    }

    private void cancelSyncTick() {
        if (syncTick != null && segmentHandler != null) {
            segmentHandler.removeCallbacks(syncTick);
        }
        syncTick = null;
    }

    /**
     * 编码器报了输出格式：开轨、启动 muxer。
     *
     * <p>格式同时记给内存环 —— 补写环里的样本时要用<b>它们那一代</b>编码器的格式开轨。
     * 文件头都写不进去的话换盘；换不了就把异常抛回去，走原来的路。</p>
     */
    private void startMuxerTrack(MediaFormat format) {
        if (ringGeneration != encoderGeneration && !ring.isEmpty()) {
            // 不该发生：上一代的样本该在收文件时就清掉或抢救掉了
            AppLog.w(TAG, "Camera " + cameraId + " ring still holds samples of encoder #" + ringGeneration
                    + ", dropping them");
            ring.clear();
        }
        ringFormat = format;
        ringGeneration = encoderGeneration;
        try {
            videoTrackIndex = muxer.addTrack(format);
            muxer.start();
        } catch (RuntimeException e) {
            if (!relocate("start", e)) {
                throw e;
            }
            return;  // 换盘时已经在新文件上开好了轨
        }
        muxerStarted = true;
        encoderHealthy = true;  // 收到格式变化说明编码器正常
        lastEncoderOutputTime = System.currentTimeMillis();
    }

    /**
     * 写一个样本：先进内存环，再写文件。
     *
     * <p>用编码器给出的真实时间戳，不按帧数推算（见 {@link #nextPtsUs}：推算会让回放速度不等于录制速度）。
     * 文件写不进就换盘，换盘会把环里的（包括这一个）先补写进新文件；换不了就把异常抛回去，
     * 走原来的路（编码器标不健康 → 重建 → 看门狗）。</p>
     */
    private void writeSample(ByteBuffer encodedData, MediaCodec.BufferInfo info) {
        boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        encodedData.position(info.offset);
        encodedData.limit(info.offset + info.size);
        ring.add(info.presentationTimeUs, System.currentTimeMillis(), keyframe, encodedData, info.size);
        info.presentationTimeUs = nextPtsUs(info.presentationTimeUs);
        long writeStart = StallWatch.now();
        try {
            muxer.writeSampleData(videoTrackIndex, encodedData, info);
        } catch (RuntimeException e) {
            if (!relocate("write", e)) {
                throw e;
            }
            return;  // 这一个样本已经随环补写进新文件了
        }
        StallWatch.noteOp(encoderBeat, cameraId, "write", writeStart);
        noteEncodedBytes(info.size);
    }

    /**
     * 收掉当前 muxer 和文件。
     *
     * @return 文件是不是确认落盘了：stop（写文件尾）和 fsync 都成功。没确认的话这个文件多半坏了，
     *         内存环里的是它最后那段的唯一副本（见 {@link #settleRing}）
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
     * 文件收掉之后，环里的样本怎么办。
     *
     * <p>确认落盘了就清掉。没确认（盘掉了、stop 报错），环里的就是那个文件最后一段的唯一副本，
     * 先抢救成单独的文件再清 —— 清是因为接下来是新一代编码器，两代样本不能混在一个文件里。</p>
     */
    private void settleRing(boolean confirmed, String stage) {
        if (!confirmed && !ring.isEmpty()) {
            rescueRing(stage);
        }
        ring.clear();
    }

    /**
     * 把环里的样本单独写成一个文件。
     *
     * <p>先试现在这个目录 —— 盘也许没事，只是 stop 报了错；不行再换别的盘。
     * 写成一个就够。</p>
     */
    private void rescueRing(String stage) {
        List<SampleRing.Sample> pending = ring.snapshot();
        if (pending.isEmpty() || ringFormat == null) {
            return;
        }
        long spanMs = pending.get(pending.size() - 1).wallMs - pending.get(0).wallMs;
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(saveDirectory));
        for (File dir : dirs) {
            try {
                String path = writeRescueFile(dir, pending);
                com.kooo.evcam.blackbox.BlackBox.noteImportant("上一个文件没确认落盘（相机 " + cameraId + "，"
                        + stage + "），把内存里的 " + (spanMs / 1000) + " 秒抢救到 " + path);
                return;
            } catch (Exception e) {
                AppLog.w(TAG, "Camera " + cameraId + " rescue to " + dir + " failed: " + e);
                deadVolumes.add(StorageHelper.volumeOf(dir.getAbsolutePath()));
                if (dirs.size() == 1) {
                    dirs.addAll(candidateDirs());
                }
            }
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("上一个文件没确认落盘（相机 " + cameraId + "，"
                + stage + "），内存里的 " + (spanMs / 1000) + " 秒没地方抢救；此刻挂着的盘："
                + StorageHelper.describeMounts());
    }

    /**
     * 把这些样本写成一个独立的 mp4，文件名是第一个样本的时刻。
     *
     * <p>写完 fsync：没确认落盘的抢救文件和没抢救一样。不成功就删掉并抛出。</p>
     */
    private String writeRescueFile(File dir, List<SampleRing.Sample> samples) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        String path = pathNamedAt(dir, samples.get(0).wallMs);
        RandomAccessFile file = new RandomAccessFile(path, "rw");
        MediaMuxer rescue = null;
        boolean ok = false;
        try {
            file.setLength(0);
            rescue = new MediaMuxer(file.getFD(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = rescue.addTrack(ringFormat);
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

    /** 按顺序写进 muxer，时间戳从第一个样本归零；返回最后写的时间戳。 */
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
        }
        return last;
    }

    /**
     * 现在这个盘写不进了：换一个盘接着录。
     *
     * <p>在编码线程上。旧 muxer 作废；环里的样本补写进新文件 —— 还是同一个编码器的话写进新文件开头、
     * 接着录（一个文件，画面连续）；编码器已经换过（分段切换时才发现盘没了）的话，环先单独落成一个
     * 抢救文件，新文件等编码器报格式再开轨。</p>
     *
     * @return 换成了没有。没换成时 muxer 已经没了，调用者把异常抛回去走原来的错误路径
     */
    private boolean relocate(String why, Throwable cause) {
        final boolean[] ok = {false};
        StallWatch.runTask(encoderBeat, cameraId, "relocate", () -> ok[0] = relocateNow(why, cause));
        return ok[0];
    }

    private boolean relocateNow(String why, Throwable cause) {
        String failed = saveDirectory;
        String failedVolume = StorageHelper.volumeOf(failed);
        deadVolumes.add(failedVolume);
        discardMuxer();
        String trouble = describe(cause);
        for (File dir : candidateDirs()) {
            try {
                long rescuedMs = openContinuationAt(dir);
                saveDirectory = dir.getAbsolutePath();
                relocations++;
                lastTrouble = why + ": " + trouble;
                com.kooo.evcam.blackbox.BlackBox.noteImportant("录像盘 " + failedVolume + " 写不进了（相机 " + cameraId
                        + "，" + why + "：" + trouble + "），改写到 " + dir + "，补写了内存里的 "
                        + (rescuedMs / 1000) + " 秒；此刻挂着的盘：" + StorageHelper.describeMounts());
                if (callback != null && segmentHandler != null) {
                    final File newDir = dir;
                    segmentHandler.post(() -> callback.onRecordingRelocated(cameraId, newDir, why, rescuedMs));
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
     * 在 dir 里开新文件接着录，环里的先补写进去。
     *
     * @return 补写了多长（毫秒）
     */
    private long openContinuationAt(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        List<SampleRing.Sample> pending = ring.snapshot();
        boolean sameEncoder = ringFormat != null && ringGeneration == encoderGeneration;
        long rescuedMs = pending.isEmpty() ? 0 : pending.get(pending.size() - 1).wallMs - pending.get(0).wallMs;
        if (!pending.isEmpty() && !sameEncoder) {
            // 环里是上一代编码器的：单独落一个抢救文件；这个盘不行的话这里就抛出了
            writeRescueFile(dir, pending);
            ring.clear();
            pending = new ArrayList<>();
        }
        String path = pending.isEmpty()
                ? new File(dir, segmentFileName()).getAbsolutePath()
                : pathNamedAt(dir, pending.get(0).wallMs);  // 文件名说的是画面从什么时候开始
        createMuxer(path);
        currentFilePath = path;
        recordedFilePaths.add(path);
        segmentStartTimeNs = System.nanoTime();
        encodedOutputFrameCount = 0;
        lastWrittenPtsUs = -1L;
        segmentBasePtsUs = -1L;
        if (sameEncoder) {
            // 编码器不会再报一次格式：这里直接开轨
            videoTrackIndex = muxer.addTrack(ringFormat);
            muxer.start();
            muxerStarted = true;
            if (!pending.isEmpty()) {
                segmentBasePtsUs = pending.get(0).ptsUs;
                lastWrittenPtsUs = writeSamples(muxer, videoTrackIndex, pending);
                encodedOutputFrameCount = pending.size();
                lastWriteUptimeMs = android.os.SystemClock.uptimeMillis();
                everWrote = true;
            }
        }
        return rescuedMs;
    }

    /**
     * 在当前目录开下一个文件；开不了就换盘（含补写）。
     *
     * <p>哪个盘都开不了时抛出，调用者走原来的恢复路径。</p>
     */
    private void openNextFile() throws IOException {
        String path = new File(saveDirectory, segmentFileName()).getAbsolutePath();
        try {
            createMuxer(path);
        } catch (IOException e) {
            if (!relocate("open", e)) {
                throw e;
            }
            return;  // 换盘时已经开好了文件
        }
        currentFilePath = path;
        recordedFilePaths.add(path);
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
        if (healthCheckRunnable != null) {
            segmentHandler.removeCallbacks(healthCheckRunnable);
        }

        healthCheckRunnable = () -> {
            if (!isRecording.get() || isReleased) {
                return;
            }

            // 检查编码器健康状态
            boolean needsRecovery = false;
            String reason = "";

            if (!encoderHealthy) {
                needsRecovery = true;
                reason = "encoder marked unhealthy";
            } else if (!muxerStarted && recordedFrameCount > MAX_FRAMES_WITHOUT_OUTPUT) {
                // Muxer 从未启动，但已经处理了很多帧
                needsRecovery = true;
                reason = "muxer never started after " + recordedFrameCount + " frames";
            } else if (framesWithoutEncoderOutput > MAX_FRAMES_WITHOUT_OUTPUT) {
                needsRecovery = true;
                reason = "no encoder output for " + framesWithoutEncoderOutput + " frames";
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
        AppLog.d(TAG, "Camera " + cameraId + " Rebuilding encoder due to health check failure");

        // 暂停录制
        isRecording.set(false);

        try {
            // 1. 收掉旧文件；没确认落盘的话内存环里的先抢救出来（下面要换编码器）
            settleRing(closeMuxer("rebuild"), "rebuild");

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

            // 6. 开新文件（这个盘开不了就换盘）
            segmentIndex++;
            openNextFile();
            String newFilePath = currentFilePath;

            // 7. 重置状态
            segmentStartTimeNs = System.nanoTime();
            encodedOutputFrameCount = 0;
            lastWrittenPtsUs = -1L;
            segmentBasePtsUs = -1L;
            framesWithoutEncoderOutput = 0;
            encoderHealthy = true;
            lastEncoderOutputTime = System.currentTimeMillis();

            // 8. 恢复录制
            isRecording.set(true);

            AppLog.d(TAG, "Camera " + cameraId + " Encoder rebuilt successfully, new file: " + newFilePath);
            com.kooo.evcam.blackbox.BlackBox.noteImportant("录像编码器重建好了（相机 " + cameraId + "），新文件 "
                    + new File(newFilePath).getName());

            // 9. 继续健康检查
            segmentHandler.post(() -> scheduleEncoderHealthCheck());

            // 10. 重新调度分段定时器
            segmentHandler.post(() -> scheduleNextSegment());

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to rebuild encoder", e);
            noteTrouble("rebuild", e);

            // 重建失败，启动恢复重试机制
            recoveryAttempts++;
            if (recoveryAttempts <= MAX_RECOVERY_ATTEMPTS) {
                AppLog.w(TAG, "Camera " + cameraId + " Will retry encoder rebuild in " 
                    + (RECOVERY_RETRY_INTERVAL_MS / 1000) + "s (attempt " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS + ")");
                scheduleRecoveryRetry();
            } else {
                AppLog.e(TAG, "Camera " + cameraId + " Max recovery attempts reached, giving up");
                com.kooo.evcam.blackbox.BlackBox.noteImportant("录像编码器重建失败，放弃（相机 " + cameraId + "）");
                if (callback != null) {
                    final String errorMsg = e.getMessage();
                    segmentHandler.post(() -> callback.onRecordError(cameraId, "Encoder rebuild failed: " + errorMsg));
                }
            }
        }
    }

    /**
     * 调度文件大小检查
     */
    private void scheduleFileSizeCheck() {
        if (fileSizeCheckRunnable != null) {
            segmentHandler.removeCallbacks(fileSizeCheckRunnable);
        }

        fileSizeCheckRunnable = () -> {
            if (isRecording.get() && currentFilePath != null) {
                File file = new File(currentFilePath);
                long currentSize = file.exists() ? file.length() : 0;
                long sizeIncrease = currentSize - lastFileSize;

                // 检查是否有写入
                boolean hasWrite = (sizeIncrease > 0) || (currentSize > MIN_VALID_FILE_SIZE);
                
                if (hasWrite) {
                    // 首次写入检测
                    if (!hasFirstWrite) {
                        hasFirstWrite = true;
                        AppLog.d(TAG, "Camera " + cameraId + " first write detected! Size: " + currentSize + " bytes");
                        // 取消首次写入超时检查
                        cancelFirstWriteTimeout();
                        
                        // 【核心改动】首次写入后才启动分段定时器
                        // 这确保了分段时长是"有效录制时长"而非"尝试录制时长"
                        scheduleNextSegment();
                        AppLog.d(TAG, "Camera " + cameraId + " segment timer started after first write");
                        
                        // 通知外部：首次写入成功，录制已真正开始
                        // 外部可以据此开始钉钉录制计时等
                        if (callback != null) {
                            callback.onFirstDataWritten(cameraId);
                        }
                    }
                    AppLog.d(TAG, "Camera " + cameraId + " file size: " + currentSize + " bytes (" + (currentSize / 1024) + " KB), frames: " + recordedFrameCount);
                } else if (sizeIncrease == 0 && lastFileSize > 0) {
                    AppLog.w(TAG, "Camera " + cameraId + " WARNING: File size not growing! Current: " + currentSize + " bytes");
                }

                lastFileSize = currentSize;
                
                // 继续下一次检查（首次写入前用快速间隔，之后用正常间隔）
                long nextDelay = hasFirstWrite ? FILE_SIZE_CHECK_INTERVAL_MS : FIRST_CHECK_DELAY_MS;
                segmentHandler.postDelayed(fileSizeCheckRunnable, nextDelay);
            }
        };

        // 首次检查使用更短的延迟，快速检测首次写入
        long initialDelay = hasFirstWrite ? FILE_SIZE_CHECK_INTERVAL_MS : FIRST_CHECK_DELAY_MS;
        segmentHandler.postDelayed(fileSizeCheckRunnable, initialDelay);
    }

    /**
     * 调度首次写入超时检查
     */
    private void scheduleFirstWriteTimeout() {
        // 取消之前的超时检查
        cancelFirstWriteTimeout();

        firstWriteTimeoutRunnable = () -> {
            if (isRecording.get() && !hasFirstWrite) {
                AppLog.e(TAG, "Camera " + cameraId + " FIRST WRITE TIMEOUT: No data written in " + (FIRST_WRITE_TIMEOUT_MS / 1000) + " seconds");
                // 触发编码器重建（通过健康检查机制处理）
                encoderHealthy = false;
                // 也可以通过回调通知外部
                if (callback != null) {
                    segmentHandler.post(() -> callback.onRecordingRebuildRequested(cameraId, "first_write_timeout"));
                }
            }
        };

        segmentHandler.postDelayed(firstWriteTimeoutRunnable, FIRST_WRITE_TIMEOUT_MS);
        AppLog.d(TAG, "Camera " + cameraId + " first write timeout scheduled: " + (FIRST_WRITE_TIMEOUT_MS / 1000) + " seconds");
    }

    /**
     * 取消首次写入超时检查
     */
    private void cancelFirstWriteTimeout() {
        if (firstWriteTimeoutRunnable != null) {
            segmentHandler.removeCallbacks(firstWriteTimeoutRunnable);
            firstWriteTimeoutRunnable = null;
        }
    }

    /**
     * 验证并清理所有录制的文件
     * @return 被删除的文件名列表
     */
    private List<String> validateAndCleanupAllFiles() {
        List<String> deletedFiles = new ArrayList<>();
        
        AppLog.d(TAG, "Camera " + cameraId + " validating " + recordedFilePaths.size() + " recorded files");
        
        for (String filePath : recordedFilePaths) {
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
