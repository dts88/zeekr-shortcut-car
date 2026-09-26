package com.kooo.evcam.camera;


import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;
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
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 单个摄像头管理类
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
    /** 挂在预览视图上的重算监听，挂一次就够。 */
    private android.view.View.OnLayoutChangeListener laneLayoutWatch;
    /** 主界面点开放大时临时改成「填充」；null 表示按配置里那一格走。 */
    private volatile String fitOverride;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
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
     * 预览缓冲区尺寸。默认等于 {@link #previewSize}；开启「预览/录制分辨率解耦」后
     * 会是一个更小的已声明尺寸，录制仍用 previewSize。
     */

    private Surface recordSurface;  // 录制Surface
    private Surface mainFloatingSurface; // 主屏悬浮窗Surface
    private android.graphics.SurfaceTexture mainFloatingSurfaceTexture; // 主屏悬浮窗SurfaceTexture（用于设置buffer尺寸）
    private Surface secondaryDisplaySurface; // 副屏预览Surface
    private android.graphics.SurfaceTexture secondaryDisplaySurfaceTexture; // 副屏SurfaceTexture（用于设置buffer尺寸）
    private OutputConfiguration activePreviewConfig; // 共享预览配置，用于动态 Surface 增减
    private Surface previewSurface;  // 预览Surface（缓存以避免重复创建）

    /**
     * 拍照用的 JPEG 输出，<b>常驻在会话里</b>。
     *
     * <p>方案 A：建会话时就把它挂上去，按下快门直接发一次静态拍照请求，
     * 不用重建会话，预览和录制都不会顿。代价是每一路多一条输出流 ——
     * 这也是它藏在开发者选项后面、默认关着的原因：真撑爆了表现是
     * 「会话配置失败 = 没有画面」，得先在车上确认三路都起得来。</p>
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
    private boolean singleOutputMode = false;  // 单一输出模式（用于不支持多路输出的车机平台）
    
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
     * 这一趟有没有<b>成功打开过</b>。
     *
     * <p>兜底看门狗只管「开起来之后又不出帧」，不管「压根打不开」——
     * 后者是开相机那条路自己的事（它有退避重连），看门狗再去强制重开只会
     * 一起捶已经卡住的相机服务。</p>
     */
    private volatile boolean everOpened;
    /** 最后一次相机报错的短名，给界面说明「为什么点了没反应」。 */
    private volatile String lastErrorName;
    private long lastStallRecoveryMs = 0;
    private int stallRecoveryLevel = 0;
    private Runnable healthCheckRunnable;
    private static final long HEALTH_CHECK_INTERVAL_MS = 1000;
    private static final long STALL_TIMEOUT_MS = 2500;
    private static final long MIN_RECOVERY_INTERVAL_MS = 2000;

    private boolean shouldReconnect = false;  // 是否应该重连
    private int reconnectAttempts = 0;  // 重连尝试次数
    private static final long RECONNECT_DELAY_MS = 2000;  // 重连延迟（毫秒）
    private long reconnectDelayFloorMs = 0;
    private Runnable reconnectRunnable;  // 重连任务
    private boolean isPausedByLifecycle = false;  // 是否因生命周期暂停（用于区分主动关闭和系统剥夺）
    private boolean isReconnecting = false;  // 是否正在重连中（防止多个重连任务同时运行）
    private volatile boolean isOpening = false;  // 是否正在打开中（防止并行触发时重复调用 openCamera）
    private volatile boolean deferSessionCreation = false;  // 延迟 Session 创建（与 Surface 并行打开相机时使用）
    private final Object reconnectLock = new Object();  // 重连锁；拿着它时不调相机服务，见 closeCamera

    /**
     * 每台相机正在关、还没关完的那一次，按相机 id 记。
     *
     * <p>关相机交给这一路自己的相机线程去做（见 {@link #closeCamera(String)}），调用方不等。
     * 于是紧接着的「再打开」可能赶在上一次关完之前 —— 同一个 App 对同一台相机
     * 「旧的还没放、新的又来要」，相机服务会把旧的踢掉，两轮的回调搅在一起。所以打开之前，
     * 先在新一轮的相机线程上等上一次关完（{@link #openOnCameraThread}）。</p>
     *
     * <p>按相机 id 而不是按对象记：主界面重建时会换一批新的 SingleCamera，旧对象的关闭照样要等。
     * 同一台相机连着关两次时，后一次要等前一次关完才算完 —— 所以只看最新的那一个就够了。</p>
     */
    private static final java.util.Map<String, java.util.concurrent.CountDownLatch> CLOSING =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 打开前最多等上一次关闭多久。关不完多半是相机服务卡住了，等完照样试着打开。 */
    private static final long OPEN_WAIT_FOR_CLOSE_MS = 10_000L;
    /** 关的时候还有一次打开在途：它的回调可能晚到，这一轮的相机线程多留这么久，好把晚到的设备关掉。 */
    private static final long LATE_OPEN_GRACE_MS = 3_000L;
    private boolean isPrimaryInstance = true;  // 是否是主实例（用于多实例共享同一个cameraId时，只有主实例负责重连）
    private boolean isConfiguring = false; // 新增：标记是否正在配置中
    private boolean isPendingReconfiguration = false; // 新增：标记是否有待处理的配置请求
    private boolean isSessionClosing = false; // 新增：标记 Session 是否正在关闭中
    private int configFailRetryCount = 0; // session 配置失败重试计数
    private static final int MAX_CONFIG_FAIL_RETRIES = 3; // 最大重试次数
    private final Object sessionLock = new Object(); // 新增：用于同步 Session 操作

    // 相机打开后的一次性回调（用于副屏等待相机就绪后立即绑定 Surface）
    private final java.util.List<Runnable> onCameraOpenedCallbacks = new java.util.ArrayList<>();

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
     * 但在「环视 + 两路座舱」里，back 这个槽位装的是<b>座舱第一路</b>：它被无条件
     * 镜像，而且是直接 {@code setTransform}，把配置算出来的矩阵整个盖掉。</p>
     *
     * <p>判断只看<b>配置里有没有这一路的那一格</b>。原来看的是车型，而车型这个键
     * 默认是 {@code zeekr_7x}，没人手动改过就一直是它 —— 座舱旋转前三次没修好，
     * 每次都绕回这个坑。</p>
     */
    private boolean autoMirrorBack() {
        return "back".equals(cameraPosition) && laneForThisCamera() == null;
    }

    public void setTextureView(TextureView textureView) {
        this.textureView = textureView;
        clearPreviewSurface();
        if (autoMirrorBack() && this.textureView != null) {
            applyMirrorTransform();
        }
        if (this.textureView != null) {
            applyLaneTransform();
        }
    }

    public void clearPreviewSurface() {
        if (previewSurface != null) {
            try {
                previewSurface.release();
            } catch (Exception e) {
            }
            previewSurface = null;
        }
    }


    /**
     * 设置是否为主实例（用于多实例共享同一个cameraId时）
     * 只有主实例负责打开摄像头和重连，从属实例只负责显示
     */
    public void setPrimaryInstance(boolean isPrimary) {
        this.isPrimaryInstance = isPrimary;
        if (!isPrimary) {
            // 从属实例不需要重连
            synchronized (reconnectLock) {
                shouldReconnect = false;
            }
        }
        AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") set as " + (isPrimary ? "PRIMARY" : "SECONDARY") + " instance");
    }

    /**
     * 检查是否是主实例
     */
    public boolean isPrimaryInstance() {
        return isPrimaryInstance;
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
        if (laneLayoutWatch == null) {
            // 视图尺寸一变就重算：设宽高比会触发重新布局，而它和这里谁先谁后
            // 没有保证 —— 只算一次的话，算的可能是上一次的尺寸
            laneLayoutWatch = (v, l, t, r, b, ol, ot, or2, ob) -> {
                if (r - l != or2 - ol || b - t != ob - ot) {
                    applyLaneTransform();
                }
            };
            view.addOnLayoutChangeListener(laneLayoutWatch);
        }
        view.post(() -> {
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

    /**
     * 应用左右镜像变换到TextureView
     */
    private void applyMirrorTransform() {
        if (textureView == null) {
            return;
        }

        // 在主线程中执行UI操作
        textureView.post(() -> {
            android.graphics.Matrix matrix = new android.graphics.Matrix();

            // 获取TextureView的中心点
            float centerX = textureView.getWidth() / 2f;
            float centerY = textureView.getHeight() / 2f;

            // 应用水平镜像：scaleX = -1
            matrix.setScale(-1f, 1f, centerX, centerY);

            textureView.setTransform(matrix);
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
     * 关相机，并量一下它在相机服务里卡了多久。
     *
     * <p>{@code CameraDevice.close()} 是一次进相机服务的 binder 调用。醒来之后它卡过一秒多
     * （2026-09-23，Camera-2 线程停在 {@code ICameraDeviceUser.disconnect()}）；
     * 而这几处调用都拿着 {@link #reconnectLock}，主线程上的 {@link #closeCamera()} 也要这把锁 ——
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

    public boolean isCameraOpened() {
        return cameraDevice != null;
    }

    /**
     * 注册一次性回调：相机打开后立即执行（在 createCameraPreviewSession 之前）。
     * 如果相机已经打开，立即执行。
     */
    public void addOnCameraOpenedCallback(Runnable callback) {
        if (cameraDevice != null) {
            callback.run();
            return;
        }
        synchronized (onCameraOpenedCallbacks) {
            onCameraOpenedCallbacks.add(callback);
        }
    }

    private void fireOnCameraOpenedCallbacks() {
        java.util.List<Runnable> copy;
        synchronized (onCameraOpenedCallbacks) {
            if (onCameraOpenedCallbacks.isEmpty()) return;
            copy = new java.util.ArrayList<>(onCameraOpenedCallbacks);
            onCameraOpenedCallbacks.clear();
        }
        for (Runnable cb : copy) {
            try {
                cb.run();
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " onCameraOpened callback error: " + e.getMessage());
            }
        }
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

    public boolean isSecondaryDisplaySurfaceBound(Surface surface) {
        return surface != null && secondaryDisplaySurface == surface && secondaryDisplaySurface.isValid();
    }

    /**
     * 设置单一输出模式（用于不支持多路输出的车机平台，如 L6/L7）
     * 在此模式下，录制时只使用 MediaRecorder Surface，不使用 TextureView Surface
     * 这会导致录制期间预览冻结，但能确保录制正常工作
     */
    public void setSingleOutputMode(boolean enabled) {
        this.singleOutputMode = enabled;
        AppLog.d(TAG, "Camera " + cameraId + " single output mode: " + (enabled ? "ENABLED" : "DISABLED"));
    }

    /**
     * 检查是否启用了单一输出模式
     */
    public boolean isSingleOutputMode() {
        return singleOutputMode;
    }

    // 当前录制模式（用于调试模式区分）
    private boolean isCodecRecording = false;

    /**
     * 设置录制Surface
     */
    public void setRecordSurface(Surface surface) {
        this.recordSurface = surface;
        if (surface != null) {
            AppLog.d(TAG, "Record surface set for camera " + cameraId + ": " + surface + ", isValid=" + surface.isValid());
        } else {
            AppLog.w(TAG, "Record surface set to NULL for camera " + cameraId);
        }
    }

    /**
     * 设置录制Surface（带模式标识）
     * @param surface 录制Surface
     * @param isCodec true 表示 Codec 模式，false 表示 MediaRecorder 模式
     */
    public void setRecordSurface(Surface surface, boolean isCodec) {
        this.recordSurface = surface;
        this.isCodecRecording = isCodec;
        if (surface != null) {
            AppLog.d(TAG, "Record surface set for camera " + cameraId + ": " + surface + 
                    ", isValid=" + surface.isValid() + ", mode=" + (isCodec ? "Codec" : "MediaRecorder"));
        } else {
            AppLog.w(TAG, "Record surface set to NULL for camera " + cameraId);
        }
    }

    /**
     * 设置主屏悬浮窗Surface
     */
    public void setMainFloatingSurface(Surface surface) {
        setMainFloatingSurface(surface, null);
    }

    /**
     * 设置主屏悬浮窗Surface（带SurfaceTexture引用，用于在创建Session时统一设置buffer尺寸）
     */
    public void setMainFloatingSurface(Surface surface, android.graphics.SurfaceTexture surfaceTexture) {
        this.mainFloatingSurface = surface;
        this.mainFloatingSurfaceTexture = surfaceTexture;
        if (surface != null) {
            AppLog.d(TAG, "Main floating surface set for camera " + cameraId + ": " + surface + ", isValid=" + surface.isValid());
        } else {
            AppLog.d(TAG, "Main floating surface cleared for camera " + cameraId);
        }
    }

    /**
     * 设置副屏显示Surface
     */
    public void setSecondaryDisplaySurface(Surface surface) {
        setSecondaryDisplaySurface(surface, null);
    }

    /**
     * 设置副屏显示Surface（带SurfaceTexture引用，用于在创建Session时统一设置buffer尺寸）
     */
    public void setSecondaryDisplaySurface(Surface surface, android.graphics.SurfaceTexture surfaceTexture) {
        this.secondaryDisplaySurface = surface;
        this.secondaryDisplaySurfaceTexture = surfaceTexture;
        if (surface != null) {
            AppLog.d(TAG, "Secondary display surface set for camera " + cameraId + ": " + surface + ", isValid=" + surface.isValid());
        } else {
            AppLog.d(TAG, "Secondary display surface cleared for camera " + cameraId);
        }
    }

    /**
     * 设置副屏预览Surface (保留兼容性)
     * @param surface 副屏预览Surface
     * @deprecated 请使用 setMainFloatingSurface 或 setSecondaryDisplaySurface
     */
    @Deprecated
    public void setSecondarySurface(Surface surface) {
        setSecondaryDisplaySurface(surface);
    }

    /**
     * 清除录制Surface
     */
    public void clearRecordSurface() {
        this.recordSurface = null;
        AppLog.d(TAG, "Record surface cleared for camera " + cameraId);
    }

    /**
     * 暂停向录制 Surface 发送帧（旧方法，保留兼容性）
     * 注意：此方法会停止整个预览，导致画面卡顿，建议使用 switchToPreviewOnlyMode() 代替
     */
    public void pauseRecordSurface() {
        if (captureSession != null) {
            try {
                // 停止向所有 Surface（包括 recordSurface）发送帧
                captureSession.stopRepeating();
                AppLog.d(TAG, "Camera " + cameraId + " paused recording surface (stopped repeating request)");
            } catch (CameraAccessException e) {
                AppLog.e(TAG, "Camera " + cameraId + " failed to pause recording surface", e);
            } catch (IllegalStateException e) {
                // Session 可能已经关闭
                AppLog.w(TAG, "Camera " + cameraId + " session already closed when trying to pause");
            }
        } else {
            AppLog.w(TAG, "Camera " + cameraId + " captureSession is null, cannot pause recording surface");
        }
    }

    /**
     * 切换到仅预览模式（优化的分段切换方法）
     * 
     * 与 pauseRecordSurface() 不同，此方法不会停止预览，而是：
     * 1. 创建一个只包含预览 Surface 的新请求
     * 2. 继续向预览 Surface 发送帧（预览不卡顿）
     * 3. 停止向录制 Surface 发送帧（安全停止 MediaRecorder）
     * 
     * @return true 如果成功切换，false 如果失败（将回退到 pauseRecordSurface）
     */
    public boolean switchToPreviewOnlyMode() {
        if (captureSession == null || cameraDevice == null || previewSurface == null) {
            AppLog.w(TAG, "Camera " + cameraId + " cannot switch to preview-only mode: session/device/surface not ready");
            // 回退到旧方法
            pauseRecordSurface();
            return false;
        }

        try {
            // 创建一个只包含预览 Surface 的请求
            CaptureRequest.Builder previewOnlyBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewOnlyBuilder.addTarget(previewSurface);
            
            // 应用当前的图像调节参数（如果启用）
            if (imageAdjustEnabled && currentRequestBuilder != null) {
                // 复制关键参数
                try {
                    Integer exposure = currentRequestBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
                    if (exposure != null) {
                        previewOnlyBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure);
                    }
                    Integer awbMode = currentRequestBuilder.get(CaptureRequest.CONTROL_AWB_MODE);
                    if (awbMode != null) {
                        previewOnlyBuilder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
                    }
                } catch (Exception e) {
                    // 忽略参数复制错误
                }
            }
            
            // 替换当前的重复请求（预览继续，但不再向录制 Surface 发送帧）
            captureSession.setRepeatingRequest(previewOnlyBuilder.build(), null, backgroundHandler);
            AppLog.d(TAG, "Camera " + cameraId + " switched to preview-only mode (preview continues, recording paused)");
            return true;
            
        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to switch to preview-only mode", e);
            // 回退到旧方法
            pauseRecordSurface();
            return false;
        } catch (IllegalStateException e) {
            AppLog.w(TAG, "Camera " + cameraId + " session closed when switching to preview-only mode");
            return false;
        } catch (IllegalArgumentException e) {
            // 某些设备可能不支持动态切换请求目标
            AppLog.w(TAG, "Camera " + cameraId + " device may not support dynamic request change: " + e.getMessage());
            pauseRecordSurface();
            return false;
        }
    }

    public Surface getSurface() {
        if (textureView != null && textureView.isAvailable()) {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture != null) {
                // 缓存 Surface 以避免重复创建和资源泄漏
                if (previewSurface == null) {
                    previewSurface = new Surface(surfaceTexture);
                    AppLog.d(TAG, "Camera " + cameraId + " created new preview surface");
                }
                return previewSurface;
            }
        }
        return null;
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

    private void startHealthMonitor() {
        stopHealthMonitor();
        if (backgroundHandler == null) {
            return;
        }
        healthCheckRunnable = () -> {
            Handler handler = backgroundHandler;
            if (handler == null) {
                return;
            }
            if (cameraDevice == null || captureSession == null) {
                stopHealthMonitor();
                return;
            }
            if (isPausedByLifecycle) {
                Runnable next = healthCheckRunnable;
                if (next != null) {
                    handler.postDelayed(next, HEALTH_CHECK_INTERVAL_MS);
                }
                return;
            }
            synchronized (sessionLock) {
                if (isConfiguring || isSessionClosing) {
                    Runnable next = healthCheckRunnable;
                    if (next != null) {
                        handler.postDelayed(next, HEALTH_CHECK_INTERVAL_MS);
                    }
                    return;
                }
            }
            long now = System.currentTimeMillis();
            long last = lastFrameTimestampMs;
            boolean isStalled = last > 0 && (now - last) > STALL_TIMEOUT_MS;
            if (isStalled) {
                if (now - lastStallRecoveryMs >= MIN_RECOVERY_INTERVAL_MS) {
                    lastStallRecoveryMs = now;
                    if (stallRecoveryLevel == 0) {
                        stallRecoveryLevel = 1;
                        AppLog.w(TAG, "Camera " + cameraId + " stalled (" + (now - last) + "ms), recreating session");
                        recreateSession();
                    } else {
                        stallRecoveryLevel++;
                        AppLog.w(TAG, "Camera " + cameraId + " stalled (" + (now - last) + "ms), force reopening (level " + stallRecoveryLevel + ")");
                        forceReopen();
                    }
                }
            } else {
                stallRecoveryLevel = 0;
            }
            Runnable next = healthCheckRunnable;
            if (next != null) {
                handler.postDelayed(next, HEALTH_CHECK_INTERVAL_MS);
            }
        };
        backgroundHandler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS);
    }

    private void stopHealthMonitor() {
        if (backgroundHandler != null && healthCheckRunnable != null) {
            backgroundHandler.removeCallbacks(healthCheckRunnable);
        }
        healthCheckRunnable = null;
        stallRecoveryLevel = 0;
    }

    /**
     * 卡顿报告里这一路相机的状态，一行。
     *
     * <p>不加锁：从监测线程读，拿到的是那一瞬间的近似值，够判断就行 ——
     * 为了一份诊断去抢相机线程的锁，反而可能把相机线程卡住。</p>
     */
    public String describeForStall() {
        long last = lastFrameTimestampMs;
        return "camera " + cameraId + " (" + cameraPosition + ", "
                + (isPrimaryInstance ? "primary" : "secondary") + " @"
                + Integer.toHexString(System.identityHashCode(this)) + ")"
                + " device=" + (cameraDevice != null)
                + " session=" + (captureSession != null)
                + " opening=" + isOpening
                + " configuring=" + isConfiguring
                + " closing=" + isSessionClosing
                + " pendingRebuild=" + isPendingReconfiguration
                + " reconnecting=" + isReconnecting
                + " pausedByLifecycle=" + isPausedByLifecycle
                + " outputs[main-preview=" + surfaceState(previewSurface)
                + " mirror=" + surfaceState(mainFloatingSurface)
                + " record=" + surfaceState(recordSurface)
                + " secondary=" + surfaceState(secondaryDisplaySurface)
                + " jpeg=" + (jpegReader != null) + "]"
                + " fps=" + String.format(java.util.Locale.US, "%.1f", currentFps)
                + " lastResult=" + (last > 0 ? (System.currentTimeMillis() - last) + "ms ago" : "never")
                + " stallRecoveryLevel=" + stallRecoveryLevel;
    }

    private static String surfaceState(Surface surface) {
        if (surface == null) {
            return "none";
        }
        return surface.isValid() ? "ok" : "INVALID";
    }

    /** 丢帧回调里的 Surface 是哪一路输出。 */
    private String describeTarget(Surface target) {
        if (target == null) {
            return "null";
        }
        if (target == previewSurface) {
            return "main-preview";
        }
        if (target == mainFloatingSurface) {
            return "mirror";
        }
        if (target == recordSurface) {
            return "record";
        }
        if (target == secondaryDisplaySurface) {
            return "secondary";
        }
        if (jpegReader != null && target == jpegReader.getSurface()) {
            return "jpeg";
        }
        return "other";
    }

    public long getLastFrameTimestampMs() {
        return lastFrameTimestampMs;
    }

    /**
     * 距上一次「有动静」多久。给 {@link CameraLiveness} 那层兜底看门狗用。
     *
     * <p>从没开过相机的返回 0（不是无穷大）—— 没开过就不该被判成卡住。</p>
     */
    /**
     * 最近 {@code ms} 毫秒里有没有真的出过画面。
     *
     * <p>和 {@link #progressAgeMs()} 不同：那个开相机、建会话也算「有动静」；
     * 这里只认画面。「环视恢复了没有」要问的是这个。</p>
     */
    public boolean hasFramesWithin(long ms) {
        long last = lastCaptureUptimeMs;
        return last != 0 && SystemClock.uptimeMillis() - last < ms;
    }

    public long progressAgeMs() {
        long last = lastProgressUptimeMs;
        return last == 0 ? 0 : Math.max(0, SystemClock.uptimeMillis() - last);
    }

    /**
     * 此刻该不该有帧：是主实例、没有被生命周期主动暂停、而且至少有一路输出在等画面。
     *
     * <p>注意这里<b>不看</b>相机开没开、会话建没建 —— 兜底看门狗要救的恰恰是
     * 那些状态标志卡住的情形，拿卡住的标志当前提就等于不救。</p>
     */
    public boolean wantsFrames() {
        return isPrimaryInstance && !isPausedByLifecycle
                && (previewSurface != null || mainFloatingSurface != null
                || recordSurface != null || secondaryDisplaySurface != null);
    }

    public boolean isPausedByLifecycle() {
        return isPausedByLifecycle;
    }

    /** 这一趟成功打开过没有。见 {@link #everOpened}。 */
    public boolean hasEverOpened() {
        return everOpened;
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
     * 打开摄像头
     */
    public void openCamera() {
        // 如果不是主实例，不执行打开操作
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping openCamera");
            return;
        }

        // 已经打开，不重复打开
        if (cameraDevice != null) {
            AppLog.d(TAG, "Camera " + cameraId + " already opened, skipping openCamera");
            return;
        }

        // 正在打开中，不重复触发
        if (isOpening) {
            AppLog.d(TAG, "Camera " + cameraId + " already opening, skipping duplicate openCamera");
            return;
        }
        isOpening = true;
        lastProgressUptimeMs = SystemClock.uptimeMillis();

        synchronized (reconnectLock) {
            // 安全措施：清理可能残留的录制 Surface 引用（防止 Surface abandoned 错误）
            // 放在同步块内，避免与 setRecordSurface() 的竞态条件
            if (recordSurface != null) {
                AppLog.w(TAG, "Camera " + cameraId + " found stale recordSurface on open, clearing it");
                recordSurface = null;
            }
            
            // 如果已经在重连中，忽略新的打开请求
            if (isReconnecting) {
                AppLog.d(TAG, "Camera " + cameraId + " already reconnecting, ignoring openCamera call");
                isOpening = false;
                return;
            }
            
            AppLog.d(TAG, "openCamera: Starting for camera " + cameraId + " (PRIMARY instance)");
            shouldReconnect = true;  // 启用自动重连
            reconnectAttempts = 0;  // 重置重连计数
        }
        
        // 相机服务的调用（查设备、查参数、打开）都放到这一路自己的相机线程上：
        // 相机服务卡住时，卡住的是这一路的相机线程，不是主线程 —— 卡顿监测里一眼分得清
        startBackgroundThread();
        Handler handler = backgroundHandler;
        java.util.concurrent.CountDownLatch previousClose = CLOSING.get(cameraId);
        if (handler == null || !handler.post(() -> openOnCameraThread(handler, previousClose))) {
            isOpening = false;
            AppLog.e(TAG, "Camera " + cameraId + " has no camera thread, cannot open");
        }
    }

    /**
     * 打开的实际步骤，在这一路的相机线程上跑。
     *
     * <p>先等同一台相机上一次关完（见 {@link #CLOSING}）。等的时候又被关了、或者已经换了一轮，
     * 这一次就作废。</p>
     */
    private void openOnCameraThread(Handler handler,
                                    java.util.concurrent.CountDownLatch previousClose) {
        if (previousClose != null && previousClose.getCount() > 0) {
            long waitStart = SystemClock.elapsedRealtime();
            boolean closed = awaitQuietly(previousClose, OPEN_WAIT_FOR_CLOSE_MS);
            long waited = SystemClock.elapsedRealtime() - waitStart;
            if (!closed) {
                com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId + " 上一次关闭等了 "
                        + waited + "ms 还没完成（相机服务卡住？），照样试着打开");
            } else if (waited >= 500) {
                com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId + " 等上一次关闭完成用了 "
                        + waited + "ms 才打开");
            }
        }
        if (handler != backgroundHandler || !isOpening) {
            return;
        }
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
                if (callback != null) {
                    callback.onCameraError(cameraId, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
                }
                isOpening = false;
                return;
            }

            // 获取摄像头特性（验证摄像头是否真正可用）
            CameraCharacteristics characteristics;
            try {
                characteristics = cameraManager.getCameraCharacteristics(cameraId);
                // 相机声明的帧率上限记下来 —— 设置界面拿不到相机对象，
                // 而「原始帧率」那一项以前显示的是一个和相机无关的写死的数
                CameraCapabilities.record(cameraId, characteristics);
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " failed to get characteristics - camera may be virtual/invalid", e);
                if (callback != null) {
                    callback.onCameraError(cameraId, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
                }
                synchronized (reconnectLock) {
                    shouldReconnect = false;  // 无效摄像头不应重连
                }
                isOpening = false;
                return;
            }
            
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                // 优先使用 SurfaceTexture 的输出尺寸
                Size[] sizes = map.getOutputSizes(ImageFormat.PRIVATE);
                if (sizes == null || sizes.length == 0) {
                    sizes = map.getOutputSizes(SurfaceTexture.class);
                    if (sizes != null && sizes.length > 0) {
                        AppLog.w(TAG, "Camera " + cameraId + " no PRIVATE sizes, fallback to SurfaceTexture sizes");
                    }
                }
                if (sizes == null || sizes.length == 0) {
                    AppLog.e(TAG, "Camera " + cameraId + " has no output sizes for PRIVATE/SurfaceTexture - camera may be virtual/invalid");
                    if (callback != null) {
                        callback.onCameraError(cameraId, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
                    }
                    synchronized (reconnectLock) {
                        shouldReconnect = false;  // 无效摄像头不应重连
                    }
                    isOpening = false;
                    return;
                }

                // 打印所有可用分辨率
                AppLog.d(TAG, "Camera " + cameraId + " available sizes:");
                for (int i = 0; i < Math.min(sizes.length, 10); i++) {
                    AppLog.d(TAG, "  [" + i + "] " + sizes[i].getWidth() + "x" + sizes[i].getHeight());
                }

                // 选择合适的分辨率
                previewSize = chooseOptimalSize(sizes);
                AppLog.d(TAG, "Camera " + cameraId + " selected preview size: " + previewSize);

                // 拍照通道：开着「拍照走图片通道」时，建一个常驻的 JPEG 输出。
                // 关着时什么都不建，行为和以前完全一样（抓预览画面）。
                // 每次真正开相机都再试一次拍照通道：上一次是因为当时那套流
                // 配不上才丢的，换了配置未必还配不上
                jpegDropped = false;
                prepareJpegReader(map);

                // 通知回调预览尺寸已确定
                if (callback != null && previewSize != null) {
                    callback.onPreviewSizeChosen(cameraId, previewSize);
                }
            } else {
                AppLog.e(TAG, "Camera " + cameraId + " StreamConfigurationMap is null - camera may be virtual/invalid!");
                if (callback != null) {
                    callback.onCameraError(cameraId, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
                }
                synchronized (reconnectLock) {
                    shouldReconnect = false;  // 无效摄像头不应重连
                }
                isOpening = false;
                return;
            }

            boolean textureAvailable = textureView != null && textureView.isAvailable();
            AppLog.d(TAG, "Camera " + cameraId + " TextureView available: " + textureAvailable);
            if (textureView != null && textureView.getSurfaceTexture() != null) {
                AppLog.d(TAG, "Camera " + cameraId + " SurfaceTexture exists");
            }

            // 打开摄像头
            AppLog.d(TAG, "Camera " + cameraId + " calling openCamera...");
            cameraManager.openCamera(cameraId, stateCallback, handler);

        } catch (CameraAccessException e) {
            isOpening = false;
            AppLog.e(TAG, "Failed to open camera " + cameraId, e);
            if (callback != null) {
                callback.onCameraError(cameraId, -1);
            }
            // 尝试重连（检查是否已经在重连中）
            synchronized (reconnectLock) {
                if (shouldReconnect && !isReconnecting) {
                    scheduleReconnect();
                }
            }
        } catch (SecurityException e) {
            isOpening = false;
            AppLog.e(TAG, "No camera permission", e);
            if (callback != null) {
                callback.onCameraError(cameraId, -2);
            }
        } catch (IllegalArgumentException e) {
            isOpening = false;
            // 某些设备在打开无效摄像头时会抛出 IllegalArgumentException
            AppLog.e(TAG, "Camera " + cameraId + " invalid argument - camera may be virtual/invalid", e);
            if (callback != null) {
                callback.onCameraError(cameraId, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
            }
            synchronized (reconnectLock) {
                shouldReconnect = false;  // 无效摄像头不应重连
            }
        } catch (RuntimeException e) {
            isOpening = false;
            // 捕获所有其他运行时异常，防止应用崩溃
            AppLog.e(TAG, "Camera " + cameraId + " runtime exception - camera may be virtual/invalid", e);
            if (callback != null) {
                callback.onCameraError(cameraId, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
            }
            synchronized (reconnectLock) {
                shouldReconnect = false;  // 异常情况下不应重连
            }
        }
    }

    /**
     * 打开相机，但延迟 Session 创建。
     * 用于与 Surface 创建并行：camera 打开后不立即创建 Session，
     * 等待 setMainFloatingSurface 后再创建，避免没有 floating Surface 的空 Session 需要重建。
     */
    public void openCameraDeferred() {
        if (cameraDevice != null) return; // 已打开，无需延迟
        deferSessionCreation = true;
        openCamera();
    }

    /**
     * 调度重连任务
     */
    private void scheduleReconnect() {
        // 如果不是主实例，不执行重连
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping reconnect");
            return;
        }
        
        synchronized (reconnectLock) {
            // 检查是否允许重连
            if (!shouldReconnect) {
                AppLog.d(TAG, "Camera " + cameraId + " reconnect disabled, skipping");
                return;
            }
            
            // 如果已经在重连中，忽略新的重连请求
            if (isReconnecting) {
                AppLog.d(TAG, "Camera " + cameraId + " already reconnecting, skipping new request");
                return;
            }

            reconnectAttempts++;
            isReconnecting = true;
            // 只计数不逐条记：一路相机被反复重连时，这个数会在汇总里冒出来
            com.kooo.evcam.blackbox.BlackBox.count("相机 " + cameraId + " 自动重连");
            long delayMs = Math.max(getReconnectDelayMs(reconnectAttempts), reconnectDelayFloorMs);
            AppLog.d(TAG, "Camera " + cameraId + " scheduling reconnect attempt " + reconnectAttempts + " in " + delayMs + "ms");

            // 取消之前的重连任务
            if (reconnectRunnable != null && backgroundHandler != null) {
                backgroundHandler.removeCallbacks(reconnectRunnable);
            }

            // 创建新的重连任务
            reconnectRunnable = () -> {
                // 旧的会话和设备先从字段上摘下来，在锁外关：关是进相机服务的调用，可能卡住，
                // 拿着锁关的话，主线程上任何要这把锁的操作都得陪着等
                CameraCaptureSession oldSession;
                CameraDevice oldDevice;
                synchronized (reconnectLock) {
                    oldSession = captureSession;
                    captureSession = null;
                    oldDevice = cameraDevice;
                    cameraDevice = null;
                }
                closeSessionQuietly(oldSession);
                closeDeviceTimed(oldDevice, "reconnect");
                Handler handler = backgroundHandler;
                if (handler == null) {
                    synchronized (reconnectLock) {
                        isReconnecting = false;
                    }
                    return;
                }
                handler.postDelayed(() -> reopenOnCameraThread(handler), 150);
            };

            // 延迟执行重连
            if (backgroundHandler != null) {
                backgroundHandler.postDelayed(reconnectRunnable, delayMs);
            } else {
                isReconnecting = false;
            }
        }
    }

    private long getReconnectDelayMs(int attempt) {
        long baseDelayMs = 500;
        long maxDelayMs = 30000;
        long expMultiplier = 1L << Math.min(attempt - 1, 6);
        long delay = Math.min(baseDelayMs * expMultiplier, maxDelayMs);
        long jitter = (long) (delay * 0.2 * (Math.random() - 0.5) * 2);
        long result = delay + jitter;
        return Math.max(500, result);
    }

    /**
     * 摄像头状态回调
     */
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            if (isStale()) {
                // 这一轮在打开途中就被关了：设备晚到一步。直接关掉，别让它挂着占住相机
                closeDeviceTimed(camera, "opened after close");
                return;
            }
            isOpening = false;
            synchronized (reconnectLock) {
                cameraDevice = camera;
                everOpened = true;
                lastErrorName = null;
                reconnectAttempts = 0;  // 重置重连计数
                isReconnecting = false;  // 重连成功，清除重连标志
                reconnectDelayFloorMs = 0;
                AppLog.d(TAG, "Camera " + cameraId + " opened");
                if (callback != null) {
                    callback.onCameraOpened(cameraId);
                }
            }
            // 触发一次性回调（副屏绑定等），在 createCameraPreviewSession 之前执行
            // 这样回调中设置的 Surface 能被第一次 Session 包含，避免重建
            fireOnCameraOpenedCallbacks();
            if (deferSessionCreation) {
                deferSessionCreation = false;
                if (mainFloatingSurface != null && mainFloatingSurface.isValid()) {
                    // Surface 已先于相机打开就绪，立即创建 Session
                    createCameraPreviewSession();
                } else {
                    // 相机先于 Surface 打开，等 Surface 到达后由调用方触发 Session 创建
                    AppLog.d(TAG, "Camera " + cameraId + " opened (deferred), waiting for surface");
                }
            } else {
                createCameraPreviewSession();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            if (isStale()) {
                closeDeviceTimed(camera, "stale disconnect");
                return;
            }
            isOpening = false;
            // 这是相机服务把我们踢掉：被别的程序（多半是原厂功能）拿走，或者设备自己没了。
            // 基座把它记成自定义的 -4，标签写的「资源耗尽」是错的
            com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId + " 被相机服务断开（onDisconnected）");
            // 锁外关：它进相机服务，可能卡住（见 closeDeviceTimed）
            closeDeviceTimed(camera, "onDisconnected");
            synchronized (reconnectLock) {
                if (cameraDevice == camera) {
                    cameraDevice = null;
                }
                AppLog.w(TAG, "Camera " + cameraId + " DISCONNECTED - will attempt to reconnect...");
                if (callback != null) {
                    callback.onCameraError(cameraId, -4); // 自定义错误码：断开连接
                }

                // 断开连接可能发生在重连过程中（openCamera 后但配置 session 前）
                // 需要重置 isReconnecting 标志以允许继续重试
                if (isReconnecting) {
                    AppLog.d(TAG, "Camera " + cameraId + " disconnected during reconnect, resetting flag");
                    isReconnecting = false;
                }
                
                // 启动自动重连
                if (shouldReconnect) {
                    scheduleReconnect();
                }
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            if (isStale()) {
                closeDeviceTimed(camera, "stale error");
                return;
            }
            isOpening = false;
            com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId + " 出错 error=" + error);
            // 锁外关：它进相机服务，可能卡住（见 closeDeviceTimed）
            closeDeviceTimed(camera, "onError");
            synchronized (reconnectLock) {
                if (cameraDevice == camera) {
                    cameraDevice = null;
                }
                String errorMsg = "UNKNOWN";
                boolean shouldRetry = false;
                boolean shouldStopReconnect = false;

                switch (error) {
                    case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                        errorMsg = "ERROR_CAMERA_IN_USE (1) - Camera is being used by another app";
                        shouldRetry = true;  // 摄像头被占用，可以重试
                        reconnectDelayFloorMs = 500;
                        break;
                    case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                        errorMsg = "ERROR_MAX_CAMERAS_IN_USE (2) - Too many cameras open";
                        shouldRetry = true;  // 摄像头数量超限，可以重试
                        reconnectDelayFloorMs = 1000;
                        break;
                    case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                        errorMsg = "ERROR_CAMERA_DISABLED (3) - Camera disabled by policy (likely background restriction)";
                        shouldRetry = true;
                        // 冷启动时前台服务可能刚启动还未完全建立，1.5秒后重试通常已就绪
                        reconnectDelayFloorMs = 1500;
                        break;
                    case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                        errorMsg = "ERROR_CAMERA_DEVICE (4) - Device error (may be temporary due to resource contention)";
                        reconnectDelayFloorMs = 8000;
                        shouldRetry = true;
                        shouldStopReconnect = false;
                        break;
                    case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                        errorMsg = "ERROR_CAMERA_SERVICE (5) - Camera service error";
                        shouldRetry = true;  // 服务错误，可以重试
                        reconnectDelayFloorMs = 2000;
                        break;
                }

                AppLog.e(TAG, "Camera " + cameraId + " error: " + errorMsg);
                lastErrorName = errorMsg;
                if (callback != null) {
                    callback.onCameraError(cameraId, error);
                }

                if (shouldStopReconnect) {
                    shouldReconnect = false;
                    isReconnecting = false;
                    if (reconnectRunnable != null && backgroundHandler != null) {
                        backgroundHandler.removeCallbacks(reconnectRunnable);
                        reconnectRunnable = null;
                    }
                    return;
                }

                // 重连过程中收到错误，说明 openCamera 已经执行完毕（通过回调返回了错误）
                // 需要重置 isReconnecting 标志，以便可以继续下一次重连尝试
                if (isReconnecting) {
                    AppLog.d(TAG, "Camera " + cameraId + " reconnect attempt completed with error, resetting flag");
                    isReconnecting = false;
                }
                
                // 如果应该重试且允许重连，则启动自动重连
                if (shouldRetry && shouldReconnect) {
                    scheduleReconnect();
                }
            }
        }
    };

    /**
     * 创建预览会话
     */
    private void createCameraPreviewSession() {
        if (cameraDevice == null) {
            AppLog.e(TAG, "createCameraPreviewSession: cameraDevice is null for camera " + cameraId);
            return;
        }

        synchronized (sessionLock) {
            if (isConfiguring) {
                AppLog.d(TAG, "Camera " + cameraId + " is already configuring, marking as pending");
                isPendingReconfiguration = true;
                return;
            }
            if (isSessionClosing) {
                AppLog.d(TAG, "Camera " + cameraId + " is closing old session, marking as pending and delaying");
                isPendingReconfiguration = true;
                if (backgroundHandler != null) {
                    backgroundHandler.postDelayed(this::createCameraPreviewSession, 200);
                }
                return;
            }
            isConfiguring = true;
            isPendingReconfiguration = false;
        }

        try {
            AppLog.d(TAG, "createCameraPreviewSession: Starting for camera " + cameraId);

            // 【关键】如果旧会话仍在运行，必须先关闭它再创建新 session。
            // HAL 不允许 Surface 同时绑定到多个 stream（"Surface already has a stream created for it"）
            if (captureSession != null) {
                final CameraCaptureSession oldSession = captureSession;
                captureSession = null;
                try {
                    synchronized (sessionLock) {
                        isSessionClosing = true;
                    }
                    oldSession.stopRepeating();
                    oldSession.close();
                    AppLog.d(TAG, "Camera " + cameraId + " initiated session close (early, before surface prep)");
                } catch (Exception e) {
                    AppLog.e(TAG, "Camera " + cameraId + " error closing old session: " + e.getMessage());
                    synchronized (sessionLock) {
                        isSessionClosing = false;
                    }
                }

                // 通过 onClosed 回调触发重建；设置 300ms 安全兜底
                if (backgroundHandler != null) {
                    backgroundHandler.postDelayed(sessionCloseFallbackRunnable, 300);
                }
                synchronized (sessionLock) {
                    isConfiguring = false;
                }
                return;
            }

            SurfaceTexture surfaceTexture = null;
            if (textureView != null && textureView.isAvailable()) {
                surfaceTexture = textureView.getSurfaceTexture();
            }
            if (surfaceTexture != null) {
                if (previewSize != null) {
                    surfaceTexture.setDefaultBufferSize(getPreviewBufferSize().getWidth(), getPreviewBufferSize().getHeight());
                    AppLog.d(TAG, "Camera " + cameraId + " buffer size set to: " + previewSize);
                } else {
                    AppLog.e(TAG, "Camera " + cameraId + " Cannot set buffer size - previewSize: " + previewSize + ", SurfaceTexture: " + surfaceTexture);
                }

                if (autoMirrorBack()) {
                    applyMirrorTransform();
                }

                applyLaneTransform();

                if (previewSurface == null || !previewSurface.isValid()) {
                    if (previewSurface != null) {
                        try { previewSurface.release(); } catch (Exception e) {}
                        previewSurface = null;
                    }

                    previewSurface = new Surface(surfaceTexture);
                    AppLog.d(TAG, "Camera " + cameraId + " Created NEW preview surface: " + previewSurface);
                }
            } else {
                if (previewSurface != null) {
                    try { previewSurface.release(); } catch (Exception e) {}
                    previewSurface = null;
                }
            }

            Surface surface = (previewSurface != null && previewSurface.isValid()) ? previewSurface : null;
            if (surface == null) {
                if (mainFloatingSurface != null && mainFloatingSurface.isValid()) {
                    surface = mainFloatingSurface;
                } else if (secondaryDisplaySurface != null && secondaryDisplaySurface.isValid()) {
                    surface = secondaryDisplaySurface;
                }
            }
            
            // 检查是否有可用的输出 Surface（后台初始化时可能全部为 null）
            boolean hasAnySurface = (surface != null && surface.isValid())
                    || (mainFloatingSurface != null && mainFloatingSurface.isValid())
                    || (secondaryDisplaySurface != null && secondaryDisplaySurface.isValid())
                    || (recordSurface != null && recordSurface.isValid());
            if (!hasAnySurface) {
                AppLog.d(TAG, "Camera " + cameraId + " no available surfaces, skipping session creation (waiting for surface)");
                // 关闭旧 session，防止继续推帧到已销毁的 Surface（queueBuffer abandoned）
                if (captureSession != null) {
                    try {
                        captureSession.close();
                    } catch (Exception e) {
                        // 忽略
                    }
                    captureSession = null;
                    AppLog.d(TAG, "Camera " + cameraId + " closed old session (no surfaces)");
                }
                synchronized (sessionLock) {
                    isConfiguring = false;
                }
                return;
            }

            AppLog.d(TAG, "Camera " + cameraId + " Creating capture request...");
            int template = (recordSurface != null) ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
            final CaptureRequest.Builder previewRequestBuilder = cameraDevice.createCaptureRequest(template);
            
            // 保存请求构建器引用（用于实时更新亮度/降噪参数）
            currentRequestBuilder = previewRequestBuilder;
            
            // 如果启用了亮度/降噪调节，应用配置中保存的参数
            if (imageAdjustEnabled) {
                applyImageAdjustParamsFromConfig(previewRequestBuilder);
            }

            applyTargetFpsRange(previewRequestBuilder);
            
            // 准备所有输出Surface
            java.util.List<Surface> surfaces = new java.util.ArrayList<>();
            java.util.List<OutputConfiguration> outputConfigs = new java.util.ArrayList<>();

            // 单一输出模式处理（用于 L6/L7 等不支持多路输出的车机平台）
            if (singleOutputMode && recordSurface != null && recordSurface.isValid()) {
                AppLog.d(TAG, "Camera " + cameraId + " SINGLE OUTPUT MODE: Using ONLY record surface");
                surfaces.add(recordSurface);
                previewRequestBuilder.addTarget(recordSurface);
                outputConfigs.add(new OutputConfiguration(recordSurface));
            } else {
                // 正常模式：使用 OutputConfiguration 实现 Surface Sharing (API 28+)
                // 将所有预览性质的 Surface (主预览、主悬浮、副悬浮) 组合成一个硬件流
                {
                    AppLog.d(TAG, "Camera " + cameraId + " Using Surface Sharing for preview streams");

                    // 统一设置所有共享 Surface 的 buffer 尺寸，确保与相机输出一致
                    // 避免悬浮窗/副屏 TextureView 使用物理布局尺寸导致 OutputConfiguration 尺寸不匹配
                    if (previewSize != null) {
                        if (mainFloatingSurfaceTexture != null) {
                            mainFloatingSurfaceTexture.setDefaultBufferSize(getPreviewBufferSize().getWidth(), getPreviewBufferSize().getHeight());
                        }
                        if (secondaryDisplaySurfaceTexture != null) {
                            secondaryDisplaySurfaceTexture.setDefaultBufferSize(getPreviewBufferSize().getWidth(), getPreviewBufferSize().getHeight());
                        }
                    }

                    if (surface != null && surface.isValid()) {
                        OutputConfiguration previewSharedConfig = new OutputConfiguration(surface);
                        previewSharedConfig.enableSurfaceSharing();
                        activePreviewConfig = previewSharedConfig;
                        surfaces.add(surface);
                        previewRequestBuilder.addTarget(surface);

                        if (previewSurface != null && previewSurface.isValid() && previewSurface != surface &&
                            previewSurface != mainFloatingSurface && previewSurface != secondaryDisplaySurface) {
                            previewSharedConfig.addSurface(previewSurface);
                            surfaces.add(previewSurface);
                            previewRequestBuilder.addTarget(previewSurface);
                            AppLog.d(TAG, "Added preview surface to SHARED preview stream");
                        }

                        if (mainFloatingSurface != null && mainFloatingSurface.isValid() && mainFloatingSurface != surface) {
                            previewSharedConfig.addSurface(mainFloatingSurface);
                            surfaces.add(mainFloatingSurface);
                            previewRequestBuilder.addTarget(mainFloatingSurface);
                            AppLog.d(TAG, "Added main floating surface to SHARED preview stream");
                        }

                        if (secondaryDisplaySurface != null && secondaryDisplaySurface.isValid() &&
                            secondaryDisplaySurface != surface && secondaryDisplaySurface != mainFloatingSurface) {
                            previewSharedConfig.addSurface(secondaryDisplaySurface);
                            surfaces.add(secondaryDisplaySurface);
                            previewRequestBuilder.addTarget(secondaryDisplaySurface);
                            AppLog.d(TAG, "Added secondary display surface to SHARED preview stream");
                        }


                        outputConfigs.add(previewSharedConfig);
                    }
                }

                // 录制 Surface 作为一个独立的硬件流
                if (recordSurface != null && recordSurface.isValid()) {
                    outputConfigs.add(new OutputConfiguration(recordSurface));
                    surfaces.add(recordSurface);
                    previewRequestBuilder.addTarget(recordSurface);
                    AppLog.d(TAG, "Added record surface as SEPARATE stream");
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
            }

            if (outputConfigs.isEmpty()) {
                AppLog.w(TAG, "Camera " + cameraId + " No valid surfaces for session, skipping configuration");
                if (captureSession != null) {
                    try {
                        captureSession.close();
                    } catch (Exception e) {
                    }
                    captureSession = null;
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
                    AppLog.d(TAG, "Camera " + cameraId + " Session configured!");
                    configFailRetryCount = 0; // 成功，重置重试计数
                    
                    boolean pending;
                    synchronized (sessionLock) {
                        isConfiguring = false;
                        isSessionClosing = false;
                        pending = isPendingReconfiguration;
                    }

                    if (pending) {
                        AppLog.d(TAG, "Camera " + cameraId + " found pending configuration request, restarting...");
                        createCameraPreviewSession();
                        return;
                    }

                    if (cameraDevice == null) {
                        AppLog.e(TAG, "Camera " + cameraId + " cameraDevice is null in onConfigured");
                        return;
                    }

                    if (captureSession != null && captureSession != session) {
                        AppLog.w(TAG, "Camera " + cameraId + " Session already replaced by newer session, ignoring this callback");
                        try { session.close(); } catch (Exception e) {}
                        return;
                    }

                    captureSession = session;
                    try {
                        frameCount = 0;
                        lastFrameLogTime = System.currentTimeMillis();

                        if (captureSession != session) return;
                        captureSession.setRepeatingRequest(previewRequestBuilder.build(), activeCaptureCallback, backgroundHandler);
                        AppLog.d(TAG, "Camera " + cameraId + " preview started!");
                        lastFrameTimestampMs = System.currentTimeMillis();
                        lastProgressUptimeMs = SystemClock.uptimeMillis();
                        stallRecoveryLevel = 0;
                        lastStallRecoveryMs = 0;
                        startHealthMonitor();
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
                    boolean pending;
                    synchronized (sessionLock) {
                        isConfiguring = false;
                        isSessionClosing = false;
                        pending = isPendingReconfiguration;
                    }

                    if (pending) {
                        AppLog.d(TAG, "Camera " + cameraId + " found pending configuration request after failure, retrying...");
                        createCameraPreviewSession();
                        return;
                    }
                    
                    // 重试逻辑
                    // 先丢拍照通道：它是这条会话里最可有可无的一条流，
                    // 丢了照片退回抓预览，画面一帧不少；留着它却可能一帧都没有。
                    if (jpegReader != null && !jpegDropped) {
                        jpegDropped = true;
                        closeJpegReader();
                        AppLog.w(TAG, "Camera " + cameraId
                                + " 会话配不上，先丢掉拍照通道再试（拍照将回退到抓预览）");
                        if (backgroundHandler != null) {
                            backgroundHandler.postDelayed(() -> {
                                if (cameraDevice != null) createCameraPreviewSession();
                            }, 200);
                        }
                        return;
                    }

                    if (recordSurface != null) {
                        // 录制中：丢弃可选 Surface 后重试
                        boolean droppedOptionalSurface = false;
                        if (secondaryDisplaySurface != null) {
                            secondaryDisplaySurface = null;
                            droppedOptionalSurface = true;
                            AppLog.w(TAG, "Retrying without secondary display surface...");
                        }
                        if (!droppedOptionalSurface && mainFloatingSurface != null) {
                            mainFloatingSurface = null;
                            droppedOptionalSurface = true;
                            AppLog.w(TAG, "Retrying without main floating surface...");
                            com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId
                                    + " 录像中会话配置失败：丢掉后视镜输出重试");
                        }
                        if (!droppedOptionalSurface) {
                            AppLog.w(TAG, "Retrying without recording surface...");
                            // 这一路从这里起不再往录像里送帧，而界面上什么都看不出来
                            com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId
                                    + " 录像中会话配置失败：丢掉了录像输出，这一路从此不再录");
                            recordSurface = null;
                        }
                        if (backgroundHandler != null) {
                            backgroundHandler.postDelayed(() -> {
                                if (cameraDevice != null) createCameraPreviewSession();
                            }, 500);
                        }
                    } else {
                        configFailRetryCount++;
                        if (configFailRetryCount <= MAX_CONFIG_FAIL_RETRIES) {
                            // 可能是 Surface 正在从其他摄像头转移（connect: already connected），
                            // 短暂延迟后重试，等待旧 session 释放 Surface
                            AppLog.w(TAG, "Camera " + cameraId + " session config failed, retry " + configFailRetryCount + "/" + MAX_CONFIG_FAIL_RETRIES + " in 200ms...");
                            if (backgroundHandler != null) {
                                backgroundHandler.postDelayed(() -> {
                                    if (cameraDevice != null) {
                                        AppLog.d(TAG, "Camera " + cameraId + " retrying session after config failure");
                                        createCameraPreviewSession();
                                    }
                                }, 200);
                            }
                        } else {
                            // 重试耗尽，丢弃副屏 Surface 后尝试只用主 Surface
                            AppLog.e(TAG, "Camera " + cameraId + " config retries exhausted (" + configFailRetryCount + "), dropping secondary display surface");
                            configFailRetryCount = 0;
                            if (secondaryDisplaySurface != null) {
                                secondaryDisplaySurface = null;
                                if (backgroundHandler != null) {
                                    backgroundHandler.postDelayed(() -> {
                                        if (cameraDevice != null) createCameraPreviewSession();
                                    }, 100);
                                }
                            }
                        }
                        if (callback != null) {
                            callback.onCameraError(cameraId, -3);
                        }
                    }
                }
                @Override
                public void onClosed(@NonNull CameraCaptureSession session) {
                    AppLog.d(TAG, "Camera " + cameraId + " Session CLOSED callback received");
                    boolean wasClosing;
                    synchronized (sessionLock) {
                        wasClosing = isSessionClosing;
                        isSessionClosing = false;
                    }
                    // CLOSED 回调后 HAL 仍需少量时间释放 Surface 绑定
                    // 延迟 50ms 重建（0ms 会触发 "Surface already has a stream" 错误）
                    if (wasClosing && backgroundHandler != null) {
                        // 移除所有待执行的重建任务，避免重复重建
                        backgroundHandler.removeCallbacks(sessionCloseFallbackRunnable);
                        backgroundHandler.removeCallbacks(recreateSessionRunnable);
                        backgroundHandler.postDelayed(recreateSessionRunnable, 50);
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

        } catch (CameraAccessException e) {
            synchronized (sessionLock) { isConfiguring = false; isSessionClosing = false; }
            AppLog.e(TAG, "Failed to create preview session for camera " + cameraId, e);
            AppLog.e(TAG, "Exception details: " + e.getMessage());
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            synchronized (sessionLock) { isConfiguring = false; isSessionClosing = false; }
            // 特殊处理 "Surface was abandoned" 错误
            String message = e.getMessage();
            if (message != null && message.contains("abandoned")) {
                AppLog.e(TAG, "Camera " + cameraId + " detected abandoned Surface, attempting recovery...");
                boolean cleared = false;
                if (secondaryDisplaySurface != null) {
                    secondaryDisplaySurface = null;
                    cleared = true;
                    AppLog.w(TAG, "Camera " + cameraId + " cleared abandoned secondaryDisplaySurface and retrying");
                }
                if (!cleared && mainFloatingSurface != null) {
                    mainFloatingSurface = null;
                    cleared = true;
                    AppLog.w(TAG, "Camera " + cameraId + " cleared abandoned mainFloatingSurface and retrying");
                }
                if (!cleared && recordSurface != null) {
                    recordSurface = null;
                    cleared = true;
                    AppLog.w(TAG, "Camera " + cameraId + " cleared abandoned recordSurface and retrying");
                }
                if (cleared && backgroundHandler != null) {
                    backgroundHandler.postDelayed(() -> {
                        if (cameraDevice != null) {
                            AppLog.d(TAG, "Camera " + cameraId + " retrying session creation after abandoning surface cleanup");
                            createCameraPreviewSession();
                        }
                    }, 100);
                    return;
                }
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
     * 安全兜底：如果 CLOSED 回调未触发，300ms 后检查并重建
     */
    private void createCameraPreviewSessionIfClosePending() {
        synchronized (sessionLock) {
            if (isSessionClosing) {
                // 回调还没来，继续等
                return;
            }
        }
        // CLOSED 回调已经来过但没触发重建（理论上不该到这），或回调丢失，兜底重建
        if (cameraDevice != null && captureSession == null) {
            AppLog.d(TAG, "Camera " + cameraId + " session close fallback triggered");
            createCameraPreviewSession();
        }
    }

    /**
     * 重新创建会话（用于开始/停止录制时，或者悬浮窗切换时）
     * 增加防抖处理，避免频繁重建导致黑屏
     */
    private final Runnable recreateSessionRunnable = this::createCameraPreviewSession;
    private final Runnable sessionCloseFallbackRunnable = this::createCameraPreviewSessionIfClosePending;

    /** 帧捕获回调（复用实例，供动态 Surface 更新时 setRepeatingRequest 使用） */
    private final CameraCaptureSession.CaptureCallback activeCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                      @NonNull CaptureRequest request,
                                      @NonNull TotalCaptureResult result) {
            captureBeat.beat(StallWatch.now());
            lastProgressUptimeMs = SystemClock.uptimeMillis();
            lastCaptureUptimeMs = lastProgressUptimeMs;
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

        // 下面两个只为卡顿监测记账：一次请求失败、某一路输出没拿到这一帧。
        // 出问题时每帧都可能来一次，所以这里只计数，日志由 StallWatch 限流
        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                    @NonNull CaptureRequest request,
                                    @NonNull android.hardware.camera2.CaptureFailure failure) {
            StallWatch.captureFailed(cameraId, failure.getReason());
        }

        @Override
        public void onCaptureBufferLost(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull Surface target, long frameNumber) {
            StallWatch.bufferLost(cameraId, describeTarget(target));
        }
    };

    /**
     * 立即停止当前会话的 repeating request，防止帧继续推到即将销毁的 Surface。
     * 用于悬浮窗 dismiss 前调用，避免 queueBuffer: BufferQueue has been abandoned 刷屏。
     */
    public void stopRepeatingNow() {
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
                AppLog.d(TAG, "Camera " + cameraId + " stopRepeating (surface about to be removed)");
            } catch (Exception e) {
                // 忽略
            }
        }
    }

    // ===== 动态 Surface 管理（补盲优化：避免 ~300ms Session 关闭等待） =====

    /**
     * 动态添加 Surface 到当前预览 Session。
     * 利用 OutputConfiguration.addSurface() + finalizeOutputConfigurations() 实现
     * 在不关闭旧 Session 的情况下添加新输出，跳过 ~300ms 的 HAL 关闭等待。
     * 失败时自动降级到 recreateSession。
     *
     * @param surface 要添加的 Surface
     * @param isMainFloating true=主屏悬浮窗, false=副屏
     */
    public void addDynamicSurface(Surface surface, boolean isMainFloating) {
        // 1. 存储引用（无论动态是否成功，后续 createCameraPreviewSession 都能拿到）
        if (isMainFloating) {
            this.mainFloatingSurface = surface;
            AppLog.d(TAG, "Main floating surface set for camera " + cameraId +
                    ": " + surface + ", isValid=" + (surface != null && surface.isValid()));
        } else {
            this.secondaryDisplaySurface = surface;
            AppLog.d(TAG, "Secondary display surface set for camera " + cameraId +
                    ": " + surface + ", isValid=" + (surface != null && surface.isValid()));
        }

        if (surface == null || !surface.isValid()) return;

        // 2. 如果 Session 正忙，新 Surface 会被进行中的 createCameraPreviewSession 自动包含
        synchronized (sessionLock) {
            if (isConfiguring || isSessionClosing) {
                AppLog.d(TAG, "Camera " + cameraId + " session busy, dynamic surface will be included in pending session");
                return;
            }
        }

        // 3. 尝试动态添加（在后台线程执行）
        if (backgroundHandler != null && captureSession != null && activePreviewConfig != null) {
            backgroundHandler.removeCallbacks(recreateSessionRunnable);
            backgroundHandler.post(() -> {
                if (!tryDynamicSurfaceAdd(surface, isMainFloating)) {
                    AppLog.d(TAG, "Camera " + cameraId + " dynamic add failed, falling back to full session rebuild");
                    createCameraPreviewSession();
                }
            });
        } else {
            // 没有现有 Session（如摄像头刚打开），走正常创建路径
            recreateSession(true);
        }
    }

    /**
     * 动态移除 Surface（补盲隐藏优化）。
     * 利用 OutputConfiguration.removeSurface() + finalizeOutputConfigurations() 实现
     * 在不关闭 Session 的情况下移除输出。
     * 失败时自动降级到 recreateSession。
     *
     * @param isMainFloating true=主屏悬浮窗, false=副屏
     */
    public void removeDynamicSurface(boolean isMainFloating) {
        // 1. 取出并清除引用
        final Surface surfaceToRemove;
        if (isMainFloating) {
            surfaceToRemove = this.mainFloatingSurface;
            this.mainFloatingSurface = null;
            AppLog.d(TAG, "Main floating surface cleared for camera " + cameraId);
        } else {
            surfaceToRemove = this.secondaryDisplaySurface;
            this.secondaryDisplaySurface = null;
            AppLog.d(TAG, "Secondary display surface cleared for camera " + cameraId);
        }

        // 2. 立即停止推帧，防止 Surface 销毁后 queueBuffer abandoned
        stopRepeatingNow();

        // 3. 尝试动态移除（在后台线程执行）
        if (surfaceToRemove != null && backgroundHandler != null
                && captureSession != null && activePreviewConfig != null) {
            backgroundHandler.removeCallbacks(recreateSessionRunnable);
            backgroundHandler.post(() -> {
                if (!tryDynamicSurfaceRemove(surfaceToRemove)) {
                    AppLog.d(TAG, "Camera " + cameraId + " dynamic remove failed, falling back to full session rebuild");
                    createCameraPreviewSession();
                }
            });
        } else {
            recreateSession(false);
        }
    }

    private boolean tryDynamicSurfaceAdd(Surface surface, boolean isMainFloating) {
        synchronized (sessionLock) {
            if (isConfiguring || isSessionClosing) return false;
        }
        if (captureSession == null || activePreviewConfig == null || currentRequestBuilder == null) {
            return false;
        }
        if (surface == null || !surface.isValid()) return false;

        try {
            // 1. 添加到共享 OutputConfiguration
            activePreviewConfig.addSurface(surface);

            // 2. 通知 Session 配置变更
            captureSession.finalizeOutputConfigurations(
                    java.util.Collections.singletonList(activePreviewConfig));

            // 3. 将新 Surface 加入 CaptureRequest 目标
            currentRequestBuilder.addTarget(surface);

            // 4. 更新 repeating request
            captureSession.setRepeatingRequest(
                    currentRequestBuilder.build(), activeCaptureCallback, backgroundHandler);

            AppLog.d(TAG, "Camera " + cameraId + " dynamic surface ADD succeeded (" +
                    (isMainFloating ? "main floating" : "secondary display") + ")");
            return true;
        } catch (Exception e) {
            AppLog.w(TAG, "Camera " + cameraId + " dynamic surface add failed: " + e.getMessage());
            // 回滚：尽力恢复状态
            try { activePreviewConfig.removeSurface(surface); } catch (Exception ignored) {}
            try { currentRequestBuilder.removeTarget(surface); } catch (Exception ignored) {}
            return false;
        }
    }

    private boolean tryDynamicSurfaceRemove(Surface surface) {
        synchronized (sessionLock) {
            if (isConfiguring || isSessionClosing) return false;
        }
        if (captureSession == null || activePreviewConfig == null || currentRequestBuilder == null) {
            return false;
        }
        if (surface == null) return false;

        try {
            // 1. 从 CaptureRequest 移除目标（停止向该 Surface 推帧）
            currentRequestBuilder.removeTarget(surface);

            // 2. 从共享 OutputConfiguration 移除
            activePreviewConfig.removeSurface(surface);

            // 3. 通知 Session 配置变更
            captureSession.finalizeOutputConfigurations(
                    java.util.Collections.singletonList(activePreviewConfig));

            // 4. 恢复 repeating request（仅包含剩余 Surface）
            captureSession.setRepeatingRequest(
                    currentRequestBuilder.build(), activeCaptureCallback, backgroundHandler);

            AppLog.d(TAG, "Camera " + cameraId + " dynamic surface REMOVE succeeded");
            return true;
        } catch (Exception e) {
            AppLog.w(TAG, "Camera " + cameraId + " dynamic surface remove failed: " + e.getMessage());
            return false;
        }
    }

    public void recreateSession() {
        recreateSession(false);
    }

    /**
     * 重新创建会话
     * @param urgent 紧急模式（如补盲悬浮窗），跳过防抖延迟以最快速度重建
     */
    public void recreateSession(boolean urgent) {
        if (cameraDevice != null) {
            if (backgroundHandler != null) {
                // 移除待执行的任务，实现防抖
                backgroundHandler.removeCallbacks(recreateSessionRunnable);
                
                int delay;
                if (urgent) {
                    // 紧急模式：最小延迟，用于补盲悬浮窗等需要快速响应的场景
                    delay = isConfiguring ? 50 : 0;
                } else {
                    // 普通模式：保持防抖延迟
                    delay = isConfiguring ? 500 : 100;
                }

                if (delay == 0) {
                    backgroundHandler.post(recreateSessionRunnable);
                } else {
                    backgroundHandler.postDelayed(recreateSessionRunnable, delay);
                }
                AppLog.d(TAG, "Camera " + cameraId + " recreateSession scheduled (delay=" + delay + "ms, isConfiguring=" + isConfiguring + ", urgent=" + urgent + ")");
            } else {
                createCameraPreviewSession();
            }
        }
    }

    /**
     * 获取当前 TextureView（用于心跳推图等功能）
     */
    public TextureView getTextureView() {
        return textureView;
    }

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
        if (reader == null || currentSession == null || cameraDevice == null) {
            return false;
        }
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(reader.getSurface());
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            pendingJpeg = callback;
            currentSession.capture(builder.build(), null, backgroundHandler);
            return true;
        } catch (Exception e) {
            pendingJpeg = null;
            AppLog.w(TAG, "Camera " + cameraId + " 拍照请求失败: " + e);
            return false;
        }
    }

    private void closeJpegReader() {
        if (jpegReader != null) {
            jpegReader.close();
            jpegReader = null;
        }
        jpegSize = null;
        pendingJpeg = null;
    }

    /**
     * 拍照。
     *
     * <h3>为什么时间戳由调用方给</h3>
     *
     * <p>多路拍的是同一个瞬间，回看是按文件名里的时间戳分组的 —— 各自取各自的
     * 时间，跨过一秒就会被拆成两组。</p>
     *
     * @param timestamp 文件命名用的时间戳，由调用方统一生成
     */
    public void takePicture(String timestamp) {
        if (textureView == null || !textureView.isAvailable()) {
            AppLog.e(TAG, "Camera " + cameraId + " TextureView not available");
            return;
        }

        if (previewSize == null) {
            AppLog.e(TAG, "Camera " + cameraId + " preview size not available");
            return;
        }

        // 图片通道优先：那是相机自己的 JPEG 输出，分辨率是这一路的最大值，
        // 和预览缓冲区无关。发不出去（通道没开、会话不在）就回退抓预览。
        if (requestJpeg(new JpegCallback() {
            @Override
            public void onJpeg(byte[] data) {
                saveJpeg(data, timestamp);
            }

            @Override
            public void onFailed(String reason) {
                AppLog.w(TAG, "Camera " + cameraId + " 图片通道没出图（" + reason + "），改抓预览");
                grabPreview(timestamp);
            }
        })) {
            return;
        }
        grabPreview(timestamp);
    }

    /**
     * 把相机出的 JPEG 存下来。
     *
     * <p>要盖角标，所以得先解码成 Bitmap 再重新编码 —— 相机直出的那份字节
     * 里没有我们的应用名、车牌和时间。EXIF 由 {@code saveBitmapAsJPEG} 之后
     * 单独补写，重新编码会把相机写的标签丢掉。</p>
     */
    private void saveJpeg(byte[] data, String timestamp) {
        if (backgroundHandler == null) {
            return;
        }
        backgroundHandler.post(() -> {
            android.graphics.Bitmap bitmap = null;
            try {
                bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) {
                    AppLog.e(TAG, "Camera " + cameraId + " JPEG 解不开，改抓预览");
                    grabPreview(timestamp);
                    return;
                }
                AppLog.d(TAG, "Camera " + cameraId + " 图片通道拍到 "
                        + bitmap.getWidth() + "x" + bitmap.getHeight());
                saveBitmapAsJPEG(bitmap, timestamp);
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " 保存 JPEG 失败", e);
            } finally {
                if (bitmap != null) {
                    bitmap.recycle();
                }
            }
        });
    }

    /** 老路子：从 TextureView 抓一张预览画面。分辨率受预览缓冲区限制。 */
    private void grabPreview(String timestamp) {
        if (backgroundHandler == null) {
            return;
        }
        backgroundHandler.post(() -> {
            try {
                // 立即从 TextureView 抓一张（快速抓拍）
                android.graphics.Bitmap bitmap = textureView.getBitmap(
                        previewSize.getWidth(),
                        previewSize.getHeight()
                );
                if (bitmap == null) {
                    AppLog.e(TAG, "Camera " + cameraId + " failed to get bitmap from TextureView");
                    return;
                }
                bitmap = toNormalView(bitmap);
                AppLog.d(TAG, "Camera " + cameraId + " picture captured ("
                        + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                saveBitmapAsJPEG(bitmap, timestamp);
                bitmap.recycle();
                AppLog.d(TAG, "Camera " + cameraId + " picture saved");
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " error capturing picture", e);
            }
        });
    }

    /**
     * 将Bitmap保存为JPEG文件（使用指定的时间戳）
     */
    private void saveBitmapAsJPEG(android.graphics.Bitmap bitmap, String timestamp) {
        File photoDir = StorageHelper.getPhotoDir(context);
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

        // 检查是否需要添加时间角标
        android.graphics.Bitmap finalBitmap = sourceBitmap;
        if (appConfig.isTimestampWatermarkEnabled()) {
            finalBitmap = addTimestampWatermark(sourceBitmap, timestamp);
        }

        FileOutputStream output = null;
        try {
            output = new FileOutputStream(photoFile);
            finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output);
            output.flush();
            output.close();
            output = null;
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
     *   极氪即刻 v0.36.2  京A12345     &lt;- 无条件；车牌号可选
     *   2026-09-03 14:22:07
     *   2560x2560                      &lt;- 这张图真实的尺寸
     * </pre>
     *
     * <p>照片没有帧率、码率、编码，那几项就不写 —— 为了「看起来一致」
     * 硬凑几个数，比不写更糟。</p>
     *
     * @param timestamp 时间戳字符串（格式：yyyyMMdd_HHmmss）
     */
    private android.graphics.Bitmap addTimestampWatermark(
            android.graphics.Bitmap originalBitmap, String timestamp) {
        try {
            android.graphics.Bitmap mutableBitmap =
                    originalBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            android.graphics.Canvas canvas = new android.graphics.Canvas(mutableBitmap);

            java.util.List<String> lines = new java.util.ArrayList<>();
            lines.add(buildPhotoBrandLine());
            lines.add(readableTime(timestamp));
            String spec = WatermarkText.photoSpecLine(
                    mutableBitmap.getWidth(), mutableBitmap.getHeight());
            if (!spec.isEmpty()) {
                lines.add(spec);
            }

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

            float x = textSize * 0.5f;
            float y = textSize * 1.2f;
            for (String line : lines) {
                if (line == null || line.isEmpty()) {
                    continue;
                }
                canvas.drawText(line, x + 2, y + 2, shadowPaint);
                canvas.drawText(line, x, y, textPaint);
                y += textSize * 1.25f;
            }

            AppLog.d(TAG, "Camera " + cameraId + " 照片角标: " + lines);
            return mutableBitmap;

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to add timestamp watermark", e);
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
     * 关闭摄像头。
     *
     * @see #closeCamera(String)
     */
    public void closeCamera() {
        closeCamera(null);
    }

    /**
     * 关闭摄像头：调用方不等。
     *
     * <p>关相机是一次进相机服务的调用，实测卡过 13.6 秒（2026-09-26，主线程）。以前它在调用方的
     * 线程上做 —— 多半就是主线程 —— 而且拿着 {@link #reconnectLock}：主线程一卡，主界面、
     * 超级后视镜、悬浮按钮全都跟着不动，分不清是界面的问题还是相机的问题。</p>
     *
     * <p>现在调用方这边只做不进相机服务的事：改标志、摘掉待办、把要关的会话和设备从字段上摘下来。
     * 真正关的那几步交给<b>这一路自己的相机线程</b>（卡顿监测里叫 {@code Camera-<id> closing}），
     * 做完这一轮的相机线程也就退了。相机服务再卡，卡的是那条线程，主线程照常。
     * 紧接着的「再打开」会先等这一次关完，见 {@link #CLOSING}。</p>
     *
     * @param why 为什么关（英文短语）。给了就在关完时往黑匣子记一行，带用时；null 表示例行的关，不记
     */
    public void closeCamera(String why) {
        // 如果不是主实例，不执行关闭操作
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping closeCamera");
            return;
        }

        final CameraCaptureSession session;
        final CameraDevice device;
        final Surface preview;
        final HandlerThread thread;
        final Handler handler;
        final boolean openInFlight;
        synchronized (reconnectLock) {
            shouldReconnect = false;  // 禁用自动重连
            reconnectAttempts = 0;
            isReconnecting = false;
            openInFlight = isOpening && cameraDevice == null;
            isOpening = false;
            deferSessionCreation = false;
            stopHealthMonitor();

            // 取消待处理的重连、会话重建（防止关了之后还去 createCaptureSession）
            if (backgroundHandler != null) {
                if (reconnectRunnable != null) {
                    backgroundHandler.removeCallbacks(reconnectRunnable);
                }
                backgroundHandler.removeCallbacks(recreateSessionRunnable);
                backgroundHandler.removeCallbacks(sessionCloseFallbackRunnable);
            }
            reconnectRunnable = null;

            // 重置 Session 状态标志（防止重新打开时残留状态导致死循环）
            synchronized (sessionLock) {
                isSessionClosing = false;
                isConfiguring = false;
                isPendingReconfiguration = false;
            }
            synchronized (onCameraOpenedCallbacks) {
                onCameraOpenedCallbacks.clear();
            }

            // 要关的从字段上摘下来，交给相机线程去关
            session = captureSession;
            captureSession = null;
            device = cameraDevice;
            cameraDevice = null;
            preview = previewSurface;
            previewSurface = null;
            // 录制、悬浮窗、副屏的 Surface 只清引用、不 release：它们归各自的主人管
            // （录制那个不清的话，下次建会话会碰上 Surface abandoned）
            recordSurface = null;
            mainFloatingSurface = null;
            mainFloatingSurfaceTexture = null;
            secondaryDisplaySurface = null;
            secondaryDisplaySurfaceTexture = null;

            // 这一轮的相机线程也摘下来：之后来自它的回调就是过时的（见 isStale）
            thread = backgroundThread;
            handler = backgroundHandler;
            backgroundThread = null;
            backgroundHandler = null;
        }

        if (handler == null) {
            // 没有相机线程：这一路眼下没开着，也就没有设备、会话要关，就地收拾完
            closeJpegReader();
            AppLog.d(TAG, "Camera " + cameraId + " closed (was not open)");
            if (callback != null) {
                callback.onCameraClosed(cameraId);
            }
            return;
        }

        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch previous = CLOSING.put(cameraId, done);
        final String watchName = "Camera-" + cameraId + " closing";
        StallWatch.unwatchLooper("Camera-" + cameraId);
        StallWatch.watchLooper(watchName, handler);
        final long requestedAt = SystemClock.elapsedRealtime();
        Runnable job = () -> {
            closeSessionQuietly(session);
            closeDeviceTimed(device, why != null ? why : "closeCamera");
            if (preview != null) {
                try {
                    preview.release();
                } catch (Exception e) {
                    AppLog.d(TAG, "Camera " + cameraId + " ignored exception while releasing preview surface: " + e.getMessage());
                }
            }
            // 拍照通道也要放，否则下次建会话会多一条悬着的流
            closeJpegReader();
            // 同一台相机上一次的关闭要是还没完，等它：「这一次关完」要蕴含「之前的都关完」
            awaitQuietly(previous, OPEN_WAIT_FOR_CLOSE_MS);
            long ms = SystemClock.elapsedRealtime() - requestedAt;
            AppLog.d(TAG, "Camera " + cameraId + " closed in " + ms + "ms");
            if (why != null) {
                com.kooo.evcam.blackbox.BlackBox.noteImportant("相机 " + cameraId + " 已关（"
                        + why + "，" + ms + "ms）");
            }
            CLOSING.remove(cameraId, done);
            done.countDown();
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
            // 线程已经在退了（不该发生）：就地做，至少设备会被关掉
            job.run();
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

    /** 等一个 latch，最多等 {@code timeoutMs}；latch 为 null 当作已经好了。 */
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
     * 等所有正在关的相机关完，最多等 {@code timeoutMs}。退出应用时用。
     *
     * @return 到点还没关完的相机 id；都关完了返回空列表
     */
    public static java.util.List<String> awaitAllClosed(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        java.util.List<String> stuck = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.concurrent.CountDownLatch> entry : CLOSING.entrySet()) {
            long left = Math.max(0L, deadline - SystemClock.elapsedRealtime());
            if (!awaitQuietly(entry.getValue(), left)) {
                stuck.add(entry.getKey());
            }
        }
        return stuck;
    }


    private boolean tryDynamicSurfaceAddFullscreen(Surface surface) {
        synchronized (sessionLock) {
            if (isConfiguring || isSessionClosing) return false;
        }
        if (captureSession == null || activePreviewConfig == null || currentRequestBuilder == null) {
            return false;
        }
        if (surface == null || !surface.isValid()) return false;

        try {
            activePreviewConfig.addSurface(surface);
            captureSession.finalizeOutputConfigurations(
                    java.util.Collections.singletonList(activePreviewConfig));
            currentRequestBuilder.addTarget(surface);
            captureSession.setRepeatingRequest(
                    currentRequestBuilder.build(), activeCaptureCallback, backgroundHandler);
            AppLog.d(TAG, "Camera " + cameraId + " dynamic fullscreen surface ADD succeeded");
            return true;
        } catch (Exception e) {
            AppLog.w(TAG, "Camera " + cameraId + " dynamic fullscreen surface add failed: " + e.getMessage());
            try { activePreviewConfig.removeSurface(surface); } catch (Exception ignored) {}
            try { currentRequestBuilder.removeTarget(surface); } catch (Exception ignored) {}
            return false;
        }
    }

    private boolean tryDynamicSurfaceRemoveFullscreen(Surface surface) {
        synchronized (sessionLock) {
            if (isConfiguring || isSessionClosing) return false;
        }
        if (captureSession == null || activePreviewConfig == null || currentRequestBuilder == null) {
            return false;
        }
        if (surface == null) return false;

        try {
            currentRequestBuilder.removeTarget(surface);
            activePreviewConfig.removeSurface(surface);
            captureSession.finalizeOutputConfigurations(
                    java.util.Collections.singletonList(activePreviewConfig));
            captureSession.setRepeatingRequest(
                    currentRequestBuilder.build(), activeCaptureCallback, backgroundHandler);
            AppLog.d(TAG, "Camera " + cameraId + " dynamic fullscreen surface REMOVE succeeded");
            return true;
        } catch (Exception e) {
            AppLog.w(TAG, "Camera " + cameraId + " dynamic fullscreen surface remove failed: " + e.getMessage());
            return false;
        }
    }


    /**
     * 手动触发重连（重置重连计数）
     */
    public void reconnect() {
        // 如果不是主实例，不执行重连操作
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping reconnect");
            return;
        }
        
        synchronized (reconnectLock) {
            AppLog.d(TAG, "Camera " + cameraId + " manual reconnect requested (PRIMARY instance)");
            
            // 取消所有待执行的重连任务
            if (reconnectRunnable != null && backgroundHandler != null) {
                backgroundHandler.removeCallbacks(reconnectRunnable);
                reconnectRunnable = null;
            }
            
            reconnectAttempts = 0;
            shouldReconnect = true;
            isReconnecting = false;
        }
        closeCamera();
        openCamera();
    }

    /**
     * 检查摄像头是否已连接
     */
    public boolean isConnected() {
        return cameraDevice != null;
    }

    /**
     * 生命周期：暂停摄像头（App退到后台时调用）
     * 暂停时不会触发自动重连，因为是主动暂停
     */
    public void pauseByLifecycle() {
        // 如果不是主实例，不执行暂停操作
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping pauseByLifecycle");
            return;
        }
        
        synchronized (reconnectLock) {
            AppLog.d(TAG, "Camera " + cameraId + " paused by lifecycle (PRIMARY instance)");
            isPausedByLifecycle = true;
            shouldReconnect = false;  // 禁用自动重连，因为是主动暂停
            isReconnecting = false;  // 清除重连状态
            
            // 取消所有待执行的重连任务
            if (reconnectRunnable != null && backgroundHandler != null) {
                backgroundHandler.removeCallbacks(reconnectRunnable);
                reconnectRunnable = null;
            }
        }
        closeCamera();
    }

    /**
     * 生命周期：恢复摄像头（App返回前台时调用）
     * 如果摄像头之前是暂停状态，会自动重新打开
     */
    public void resumeByLifecycle() {
        // 如果不是主实例，不执行恢复操作
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping resumeByLifecycle");
            return;
        }
        
        boolean shouldOpen = false;
        synchronized (reconnectLock) {
            AppLog.d(TAG, "Camera " + cameraId + " resume by lifecycle (PRIMARY instance)");
            if (isPausedByLifecycle) {
                isPausedByLifecycle = false;
                reconnectAttempts = 0;  // 重置重连计数
                shouldReconnect = true;  // 启用自动重连
                isReconnecting = false;  // 清除重连状态
                shouldOpen = true;
                
                // 取消所有待执行的重连任务
                if (reconnectRunnable != null && backgroundHandler != null) {
                    backgroundHandler.removeCallbacks(reconnectRunnable);
                    reconnectRunnable = null;
                }
            }
        }
        if (shouldOpen) {
            openCamera();
        }
    }

    /**
     * 强制重新打开摄像头（用于从后台返回前台时）
     * 即使摄像头当前是连接状态，也会重新打开
     *
     * <p>关旧的、开新的都在这一路的相机线程上按顺序做，主线程只是把活派过去 ——
     * 以前旧的是在调用方线程上、拿着 {@link #reconnectLock} 关的。</p>
     */
    public void forceReopen() {
        // 如果不是主实例，不执行重开操作
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping forceReopen");
            return;
        }

        final CameraCaptureSession oldSession;
        final CameraDevice oldDevice;
        final Handler handler;
        synchronized (reconnectLock) {
            AppLog.d(TAG, "Camera " + cameraId + " force reopen requested (PRIMARY instance)");

            // 取消所有待执行的重连任务
            if (reconnectRunnable != null && backgroundHandler != null) {
                backgroundHandler.removeCallbacks(reconnectRunnable);
                reconnectRunnable = null;
            }

            // 重置状态。isOpening / isConfiguring / isSessionClosing 这三个也要清 ——
            // 它们只在相机回调里复位，而回调不来正是这条路被走到的原因。
            // 不清的话：isOpening 会挡掉之后每一次 openCamera，
            // isConfiguring 会让自愈的每次检查都直接跳过。设备和会话下面就关掉了，
            // 在途的那一次配置已经作废，清掉不会和谁打架。
            reconnectAttempts = 0;
            shouldReconnect = true;
            isReconnecting = false;
            isOpening = false;
            synchronized (sessionLock) {
                isConfiguring = false;
                isSessionClosing = false;
                isPendingReconfiguration = false;
            }

            oldSession = captureSession;
            captureSession = null;
            oldDevice = cameraDevice;
            cameraDevice = null;
            handler = backgroundHandler;
        }

        if (handler == null) {
            // 这一路眼下没有相机线程（没开着）：走正常的打开
            openCamera();
            return;
        }
        handler.post(() -> {
            closeSessionQuietly(oldSession);
            closeDeviceTimed(oldDevice, "forceReopen");
        });
        // 延迟300ms，给系统时间释放资源
        handler.postDelayed(() -> forceReopenOnCameraThread(handler), 300);
    }

    /** 强制重开的那一下打开：先确认这台相机还在、还该开，打开在锁外。 */
    private void forceReopenOnCameraThread(Handler handler) {
        synchronized (reconnectLock) {
            if (handler != backgroundHandler || !shouldReconnect) {
                return;   // 等的时候被关了，或者已经换了一轮
            }
        }
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
                synchronized (reconnectLock) {
                    shouldReconnect = false;
                }
                return;
            }
            // 验证摄像头是否真正可用
            try {
                cameraManager.getCameraCharacteristics(cameraId);
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " failed to get characteristics - camera may be invalid", e);
                synchronized (reconnectLock) {
                    shouldReconnect = false;
                }
                return;
            }
            cameraManager.openCamera(cameraId, stateCallback, handler);
            AppLog.d(TAG, "Camera " + cameraId + " force reopen initiated");
        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Failed to force reopen camera " + cameraId, e);
            synchronized (reconnectLock) {
                if (shouldReconnect) {
                    scheduleReconnect();
                }
            }
        } catch (SecurityException e) {
            AppLog.e(TAG, "No camera permission during force reopen", e);
        } catch (IllegalArgumentException e) {
            AppLog.e(TAG, "Camera " + cameraId + " invalid argument - camera may be virtual/invalid", e);
            synchronized (reconnectLock) {
                shouldReconnect = false;
            }
        } catch (RuntimeException e) {
            AppLog.e(TAG, "Camera " + cameraId + " runtime exception - camera may be virtual/invalid", e);
            synchronized (reconnectLock) {
                shouldReconnect = false;
            }
        }
    }

    /** 自动重连的那一下打开：先在锁里看还该不该开，打开在锁外。 */
    private void reopenOnCameraThread(Handler handler) {
        synchronized (reconnectLock) {
            if (handler != backgroundHandler || !shouldReconnect) {
                isReconnecting = false;
                return;   // 等的时候被关了，或者已经换了一轮
            }
        }
        try {
            cameraManager.openCamera(cameraId, stateCallback, handler);
        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Failed to reconnect camera " + cameraId + ": " + e.getMessage());
            synchronized (reconnectLock) {
                isReconnecting = false;
                if (shouldReconnect) {
                    scheduleReconnect();
                }
            }
        } catch (SecurityException e) {
            AppLog.e(TAG, "No camera permission during reconnect", e);
            synchronized (reconnectLock) {
                shouldReconnect = false;
                isReconnecting = false;
            }
        } catch (IllegalArgumentException e) {
            AppLog.e(TAG, "Camera " + cameraId + " unknown during reconnect (camera service may have restarted): " + e.getMessage());
            synchronized (reconnectLock) {
                shouldReconnect = false;
                isReconnecting = false;
            }
        } catch (RuntimeException e) {
            AppLog.e(TAG, "Camera " + cameraId + " runtime exception during reconnect: " + e.getMessage());
            synchronized (reconnectLock) {
                isReconnecting = false;
                if (shouldReconnect) {
                    scheduleReconnect();
                }
            }
        }
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
        
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
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
        
        if (cameraDevice == null || captureSession == null || currentRequestBuilder == null) {
            AppLog.w(TAG, "Camera " + cameraId + " not ready for image adjust update");
            return false;
        }
        
        try {
            // 应用曝光补偿
            if (exposureCompensation != Integer.MIN_VALUE) {
                Range<Integer> range = getExposureCompensationRange();
                if (range != null) {
                    // 确保值在有效范围内
                    int clampedValue = Math.max(range.getLower(), Math.min(exposureCompensation, range.getUpper()));
                    currentRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedValue);
                    AppLog.d(TAG, "Camera " + cameraId + " set exposure compensation: " + clampedValue + " (range: " + range + ")");
                }
            }
            
            // 应用白平衡模式
            if (awbMode >= 0) {
                int[] supportedModes = getSupportedAwbModes();
                if (supportedModes != null && isModeSupported(supportedModes, awbMode)) {
                    currentRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
                    AppLog.d(TAG, "Camera " + cameraId + " set AWB mode: " + awbMode);
                } else {
                    AppLog.w(TAG, "Camera " + cameraId + " AWB mode " + awbMode + " not supported");
                }
            }
            
            // 应用色调映射模式
            if (tonemapMode >= 0) {
                int[] supportedModes = getSupportedTonemapModes();
                if (supportedModes != null && isModeSupported(supportedModes, tonemapMode)) {
                    currentRequestBuilder.set(CaptureRequest.TONEMAP_MODE, tonemapMode);
                    AppLog.d(TAG, "Camera " + cameraId + " set tonemap mode: " + tonemapMode);
                } else {
                    AppLog.w(TAG, "Camera " + cameraId + " tonemap mode " + tonemapMode + " not supported");
                }
            }
            
            // 应用边缘增强模式
            if (edgeMode >= 0) {
                int[] supportedModes = getSupportedEdgeModes();
                if (supportedModes != null && isModeSupported(supportedModes, edgeMode)) {
                    currentRequestBuilder.set(CaptureRequest.EDGE_MODE, edgeMode);
                    AppLog.d(TAG, "Camera " + cameraId + " set edge mode: " + edgeMode);
                } else {
                    AppLog.w(TAG, "Camera " + cameraId + " edge mode " + edgeMode + " not supported");
                }
            }
            
            // 应用降噪模式
            if (noiseReductionMode >= 0) {
                int[] supportedModes = getSupportedNoiseReductionModes();
                if (supportedModes != null && isModeSupported(supportedModes, noiseReductionMode)) {
                    currentRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode);
                    AppLog.d(TAG, "Camera " + cameraId + " set noise reduction mode: " + noiseReductionMode);
                } else {
                    AppLog.w(TAG, "Camera " + cameraId + " noise reduction mode " + noiseReductionMode + " not supported");
                }
            }
            
            // 应用特效模式
            if (effectMode >= 0) {
                int[] supportedModes = getSupportedEffectModes();
                if (supportedModes != null && isModeSupported(supportedModes, effectMode)) {
                    currentRequestBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, effectMode);
                    AppLog.d(TAG, "Camera " + cameraId + " set effect mode: " + effectMode);
                } else {
                    AppLog.w(TAG, "Camera " + cameraId + " effect mode " + effectMode + " not supported");
                }
            }
            
            // 重新提交请求（实时生效）
            captureSession.setRepeatingRequest(currentRequestBuilder.build(), null, backgroundHandler);
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
     * 获取当前请求构建器（用于外部调试）
     */
    public CaptureRequest.Builder getCurrentRequestBuilder() {
        return currentRequestBuilder;
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
