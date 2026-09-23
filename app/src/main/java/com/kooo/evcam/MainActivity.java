package com.kooo.evcam;


import com.kooo.evcam.AppLog;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.navigation.NavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.camera.ImageAdjustManager;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.PreviewSlots;
import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.Profile;
import com.kooo.evcam.profile.ProfileResolution;
import com.kooo.evcam.profile.ProfileStore;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.settings.Languages;
import com.kooo.evcam.settings.SettingSpec;
import com.kooo.evcam.settings.SettingsRegistry;
import com.kooo.evcam.recording.RecordingCoordinator;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.FileTransferManager;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.view.MacOSToggleButton;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    /** 别的界面上的菜单键：回到这里，并且把抽屉拉开。 */
    public static final String EXTRA_OPEN_DRAWER = "open_drawer";

    /** 悬浮按钮在应用没运行时按了「拍照」：拉起来，等相机就绪再拍。 */
    public static final String EXTRA_AUTO_TAKE_PHOTO = "auto_take_photo";

    /** 悬浮按钮在应用还活着时按了「拍照」。 */
    public static final String ACTION_TAKE_PHOTO = "com.kooo.evcam.action.TAKE_PHOTO";

    /** 环视流探测结果的那几行文字；相机开起来后可能被实际尺寸替掉。 */
    private String compositeProbeInfo;

    /** 探测出来的尺寸，用来和相机真正在用的那个对比。 */
    private android.util.Size compositeProbeSize;

    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 100;
    /** 读点火状态那个权限单独申请，见 requestPowertrainPermissionQuietly。 */
    private static final int REQUEST_CAR_POWERTRAIN = 101;
    
    // 静态实例引用（用于悬浮窗等外部组件访问）
    private static MainActivity instance;

    // 根据Android版本动态获取需要的权限
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
        } else {
            // Android 12及以下
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
    }

    private AutoFitTextureView textureFront, textureBack, textureLeft, textureRight;
    /** 极氪合成流四宫格容器；非该车型时为 null。 */
    private com.kooo.evcam.zeekr.FourLaneContainer compositeContainer;
    private TextView tvCompositeInfo;
    private final java.util.Map<String, android.graphics.Matrix> previewBaseTransforms = new java.util.HashMap<>();
    private PreviewCorrectionFloatingWindow previewCorrectionFloatingWindow;

    // 调试信息覆盖层（连点5下空白处显示）
    private TextView tvDebugOverlay;
    private boolean debugOverlayVisible = false;
    private final android.os.Handler debugUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable debugUpdateRunnable;
    private int debugTapCount = 0;
    private long debugLastTapTime = 0;
    private static final int DEBUG_TAP_COUNT = 5;
    private static final long DEBUG_TAP_INTERVAL_MS = 800;  // 连续点击的最大间隔

    /** 极氪布局里是一张卡片（点 + 环 + 两行字），自定义车型里还是普通按钮 —— 所以只当 View 用。 */
    private View btnStartRecord;
    private Button btnExit, btnMinimize, btnTakePhoto;

    /** 点开放大了哪一块：环视那一块，或者座舱的某一格；null 表示没放大。 */
    private View expandedPreview;
    /** 放大的是座舱哪一路（back / left）；放大的是环视时为 null。 */
    private String expandedCameraKey;
    /** 放大时让出来的那几块，还原时原样放回去。 */
    private final java.util.List<View> hiddenForExpand = new java.util.ArrayList<>();
    /** 后座舱那一格上边有一道间隙，放大时先拿掉，还原时放回。 */
    private int expandedTopMargin = -1;
    /** 放大前那一块在屏幕上的矩形 {left, top, width, height}：收回时缩回这里。 */
    private float[] expandedFrom;
    /** 收回的过渡正在跑：这时再点一下就直接收完，不再起一次过渡。 */
    private boolean collapsing;
    private final com.kooo.evcam.ui.PreviewExpandAnimator expandAnimator =
            new com.kooo.evcam.ui.PreviewExpandAnimator();
    /** 录制键的三个状态和本段进度都由它画。 */
    private com.kooo.evcam.ui.RecordButtonUi recordButtonUi;
    private MultiCameraManager cameraManager;
    /** 上一条已提示过的拒绝理由，用来挡住定时重试造成的重复提示。 */
    private String lastRefusalShown;
    /** 录不录、能不能录由它决定；这里只负责画面反馈。 */
    private RecordingCoordinator recordingCoordinator;

    public MultiCameraManager getCameraManager() {
        if (cameraManager == null) {
            cameraManager = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
            attachRecordingCoordinator();
        }
        return cameraManager;
    }
    private ImageAdjustManager imageAdjustManager;  // 亮度/降噪调节管理器
    private ImageAdjustFloatingWindow imageAdjustFloatingWindow;  // 亮度/降噪调节悬浮窗
    private int textureReadyCount = 0;  // 记录准备好的TextureView数量
    private boolean isRecording = false;  // 录制状态标志
    private boolean isInBackground = false;  // 是否在后台
  // 是否有待处理的远程命令
    private boolean isRemoteWakeUp = false;  // 是否是远程命令唤醒的（用于完成后自动退回后台）
    private boolean hasBeenResumedOnce = false;  // Activity 是否已经完全恢复过一次（用于区分新创建和已存在）
    
    // 防双击保护
    private long lastRecordButtonClickTime = 0;  // 上次点击录制按钮的时间
    private static final long RECORD_BUTTON_CLICK_INTERVAL = 1000;  // 最小点击间隔（1秒）
    
    // 录制异常提示防抖
    private long lastRecordingErrorToastTime = 0;  // 上次显示录制异常提示的时间
    private static final long RECORDING_ERROR_TOAST_INTERVAL = 20000;  // 最小显示间隔（20秒）
    private boolean shouldMoveToBackgroundOnReady = false;  // 开机自启动后，窗口准备好时移到后台
    private boolean isAutoRecordingPending = false;  // 标记自动录制已计划但尚未开始（防止 onPause 关闭摄像头）
    
    // 自动录制定时检查相关
    private android.os.Handler autoRecordingCheckHandler;  // 定时检查 Handler
    private Runnable autoRecordingCheckRunnable;  // 定时检查 Runnable
    private static final long AUTO_RECORDING_CHECK_INTERVAL_MS = 30000;  // 检查间隔（30秒）
    
    // 主题切换后恢复录制相关
    private boolean shouldResumeRecordingAfterRecreate = false;  // 主题切换后是否需要恢复录制
    private long savedRecordingStartTime = 0;  // 保存的录制开始时间（用于计时器恢复）
    private int savedSegmentCount = 1;  // 保存的分段数
    
    // 摄像头重连防抖相关
    private android.os.Handler reopenCameraHandler;  // 重新打开摄像头的 Handler
    private Runnable reopenCameraRunnable;  // 重新打开摄像头的 Runnable
    
    // 息屏录制相关
    private android.content.BroadcastReceiver screenStateReceiver;  // 屏幕状态广播接收器
    private android.content.BroadcastReceiver backgroundCommandReceiver;  // 后台切换广播接收器
    private android.content.BroadcastReceiver toggleRecordingReceiver;  // 录制切换广播接收器（来自悬浮窗）
    private android.content.BroadcastReceiver storageReceiver;  // U 盘插拔：录制键可不可录、状态条余量
    private android.os.Handler screenStateHandler;  // 息屏/亮屏延迟处理
    private Runnable screenOffStopRunnable;  // 息屏停止录制的延迟任务
    private Runnable screenOnStartRunnable;  // 亮屏恢复录制的延迟任务
    private Runnable screenOffBackgroundRunnable;  // 息屏退后台的延迟任务
    private Runnable screenOffCameraRunnable;  // 息屏尽快关相机的延迟任务
    private boolean isScreenOff = false;  // 当前是否息屏
    private boolean wasRecordingBeforeScreenOff = false;  // 息屏前是否正在录制
    private static final long SCREEN_OFF_DELAY_MS = 10000;  // 息屏后等待10秒（停止录制）
    private static final long SCREEN_ON_DELAY_MS = 10000;   // 亮屏后等待10秒（恢复录制）
    private static final long SCREEN_OFF_BACKGROUND_DELAY_MS = 15000;  // 息屏后等待15秒（退后台）
    /**
     * 息屏后多久放开相机。
     *
     * <p>退后台那一步可以慢慢来，<b>放开相机不能</b>：实测车机熄屏六秒后就深睡了，
     * 而 15 秒的延迟任务用的是 uptime 时钟，深睡期间根本不走 —— 那一次它是在
     * 十九分钟后、车机醒来时才执行的，相机就这么开着睡了过去。醒来时会话已经作废，
     * 关它卡在 binder 里，接着 error -4（资源耗尽），靠看门狗重开花了 9.4 秒。</p>
     *
     * <p>1.5 秒既躲得开深睡，也还留着一点余地：屏幕闪一下就亮回来的话，
     * 相机还没来得及关。</p>
     */
    private static final long SCREEN_OFF_CAMERA_DELAY_MS = 1500;
    
    
    // 车型配置相关
    private AppConfig appConfig;
    private int configuredCameraCount = 4;  // 配置的摄像头数量
    private CustomLayoutManager customLayoutManager;  // 自定义车型布局管理器

    // 录制状态显示相关
    private TextView tvRecordingStats;
    private android.os.Handler recordingTimerHandler;

    private Runnable recordingTimerRunnable;
    private long recordingStartTime = 0;  // 录制开始时间
    private int currentSegmentCount = 1;  // 当前分段数
    private long segmentStartTime = 0;  // 本段开始时间（分段进度环用）
    private long segmentLengthMs = com.kooo.evcam.profile.RecordSpecs.segmentMs(com.kooo.evcam.profile.RecordSpecs.DEFAULT_SEGMENT_MINUTES);
    private boolean isRecordingStatsEnabled = true;  // 录制状态显示开关
    private long lastStatsClickTime = 0;  // 上次点击录制状态显示的时间
    private static final long DOUBLE_CLICK_INTERVAL = 500;  // 双击判定间隔（毫秒）

    // 导航相关
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private View recordingLayout;  // 录制界面布局
    private View fragmentContainer;  // Fragment容器


    // 远程录制相关
    private android.os.Handler autoStopHandler;  // 自动停止录制的 Handler
    private Runnable autoStopRunnable;  // 自动停止录制的 Runnable
    private String remoteRecordingTimestamp;  // 远程录制统一时间戳（用于文件命名和查找）
    private boolean isRemoteRecording = false;  // 是否正在进行远程录制
    private boolean wasManualRecordingBeforeRemote = false;  // 远程录制前是否有手动录制在进行
    private int pendingRemoteDurationSeconds = 0;  // 待启动的远程录制时长（等待首次写入后启动定时器）
    private boolean isPreparingRecording = false;  // 是否正在准备录制（等待首次写入）

    /**
     * 「准备中」最多等多久。
     *
     * <p>开始录制之后，要等录制器写出第一笔数据才算真的录上。以前这一步<b>没有超时</b>：
     * 第一笔数据一直不来，按钮就永远停在「正在准备」，而录制器其实早就没在录了 ——
     * 典型场景是录制中退到后台、Activity 被重建（比如车机切夜间模式），
     * 新实例「恢复录制」没接上。用户得先点一下（弹出「录制异常」，那是在清理空的分段文件），
     * 再点一下才恢复。这里让应用自己做这两下。</p>
     */
    private static final long PREPARING_TIMEOUT_MS = 10_000L;
    private final android.os.Handler preparingHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable preparingWatchdog = this::onPreparingTimedOut;
    /** 连续几次准备超时。超时后自动重试一次；重试也超时就停下来交给用户。 */
    private int preparingTimeouts = 0;
    /** 看门狗自己停掉录制器时，不弹「录制已停止」「录制异常」—— 那是它在收拾，不是出了新问题。 */
    private boolean quietStop = false;
    private long suppressRecordErrorToastUntil = 0;










  // 待处理的飞书 Chat ID

    
    // 存储清理管理器
    private StorageCleanupManager storageCleanupManager;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.kooo.evcam.blackbox.BlackBox.attach(this, "Activity:MainActivity");
        com.kooo.evcam.blackbox.BlackBox.note("主界面 onCreate savedState=" + (savedInstanceState != null));
        instance = this;  // 设置静态实例引用
        AppLog.init(this);
        // 回到主界面时是不是换了一个新实例、Holder 里还有没有旧的相机管理器 ——
        // 「从诊断页回来四宫格变一整幅」要靠这几行对上
        AppLog.i(TAG, "onCreate " + instanceTag() + " restored=" + (savedInstanceState != null)
                + " cameraManagerInHolder="
                + (com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager() != null));

        // 设置字体缩放比例（1.3倍）
        adjustFontScale(1.2f);

        // 初始化应用配置
        appConfig = new AppConfig(this);
        
        // 重置U盘回退提示标志（每次冷启动重置）
        AppConfig.resetSdFallbackFlag();
        
        // 根据车型配置设置布局和摄像头数量
        setupLayoutByCarModel();

        // 设置状态栏沉浸式
        setupStatusBar();

        initViews();
        setupNavigationDrawer();

        // 检查是否需要在主题切换后恢复录制
        if (savedInstanceState != null) {
            boolean wasRecording = savedInstanceState.getBoolean("wasRecording", false);
            if (wasRecording) {
                shouldResumeRecordingAfterRecreate = true;
                savedRecordingStartTime = savedInstanceState.getLong("recordingStartTime", 0);
                savedSegmentCount = savedInstanceState.getInt("segmentCount", 1);
                AppLog.d(TAG, "onCreate: 检测到主题切换，需要恢复录制 - savedStartTime=" + savedRecordingStartTime + ", savedSegment=" + savedSegmentCount);
            }
        }

        // 检查是否首次启动
        checkFirstLaunch();

        // 初始化自动停止 Handler
        autoStopHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        
        // 初始化远程录制时间戳
        remoteRecordingTimestamp = null;
        
// 权限检查，但不立即初始化摄像头
        // 等待TextureView准备好后再初始化
        if (!checkPermissions()) {
            requestPermissions();
        }

        // 和相机那套分开：这一个是 normal 级、不弹框，没必要卷进去
        requestPowertrainPermissionQuietly();


        // 启动定时保活任务（车机必需，始终开启）
        KeepAliveManager.startKeepAliveWork(this);
        AppLog.d(TAG, "定时保活任务已启动");
        
        // 常驻唤醒锁：开发者选项里那一项，默认关。主要在 CameraForegroundService
        // 里维护，这里是备份，保证界面在时也有一把。
        //
        // 它原来挂在「开机自启动」上：那个开关只说开机要不要自己起来，
        // 却顺带让车机永不深睡（实测 26 小时里本该睡 20.8 小时）。两件事拆开了
        if (appConfig.isPersistentWakeLockEnabled()) {
            WakeUpHelper.acquirePersistentWakeLock(this);
            AppLog.d(TAG, "WakeLock 已获取（常驻唤醒锁已开启）");
        } else {
            AppLog.d(TAG, "WakeLock 未获取（常驻唤醒锁未开启）");
        }
        
        // 启动存储清理任务（如果用户设置了限制）
        storageCleanupManager = new StorageCleanupManager(this);
        storageCleanupManager.start();
        
        // 启动文件传输服务（用于U盘中转写入模式）
        FileTransferManager.getInstance(this).start();

        // 检查是否是开机自启动
        boolean autoStartFromBoot = getIntent().getBooleanExtra("auto_start_from_boot", false);
        if (autoStartFromBoot) {
            // 清除标志，避免后续重复检测
            getIntent().removeExtra("auto_start_from_boot");

            // 判断是否需要移到后台：
            // - 如果开启了自动录制：不移到后台，显示主界面并开始录制
            // - 如果未开启自动录制（只开启悬浮窗/推送等）：移到后台
            if (appConfig.isAutoStartRecording()) {
                AppLog.d(TAG, "开机自启动模式：已开启自动录制，保持前台显示");
                shouldMoveToBackgroundOnReady = false;
            } else {
                AppLog.d(TAG, "开机自启动模式：未开启自动录制，等待窗口准备好后移到后台");
                // 设置标志，等待 onWindowFocusChanged 时再移到后台
                // 这确保 Activity 完全初始化后再执行，避免中断初始化过程
                shouldMoveToBackgroundOnReady = true;
            }
        }

        // 检查是否是从录制悬浮按钮启动（需要自动开始录制）
        // 悬浮按钮在应用没运行时按了「拍照」：相机得先起来，晚一点再拍
        if (getIntent().getBooleanExtra(EXTRA_AUTO_TAKE_PHOTO, false)) {
            getIntent().removeExtra(EXTRA_AUTO_TAKE_PHOTO);
            scheduleAutoPhoto();
        }

        boolean autoStartRecording = getIntent().getBooleanExtra("auto_start_recording", false);
        if (autoStartRecording) {
            getIntent().removeExtra("auto_start_recording");
            AppLog.d(TAG, "从录制悬浮按钮启动，准备自动开始录制");
            // 标记自动录制等待中，防止 onPause 关闭摄像头
            isAutoRecordingPending = true;
            // 延迟等待摄像头初始化完成（需要更长时间确保摄像头完全准备好）
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (cameraManager != null && !cameraManager.isRecording()) {
                    // 确保摄像头已连接
                    if (!cameraManager.hasConnectedCameras()) {
                        AppLog.d(TAG, "摄像头未连接，先打开摄像头");
                        cameraManager.openAllCameras();
                    }
                    // 再等待一段时间确保摄像头完全准备好
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (cameraManager != null && cameraManager.hasConnectedCameras() && !cameraManager.isRecording()) {
                            AppLog.d(TAG, "摄像头已准备好，自动开始录制");
                            startRecording();
                            // 录制开始后，延迟将 Activity 移到后台（让用户看到录制已开始）
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                if (isRecording) {
                                    AppLog.d(TAG, "录制已开始，将 Activity 移到后台");
                                    moveTaskToBack(true);
                                }
                            }, 1500);
                        } else {
                            AppLog.w(TAG, "摄像头未准备好，无法开始录制");
                            isAutoRecordingPending = false;
                            Toast.makeText(this, R.string.msg_camera_not_ready, Toast.LENGTH_SHORT).show();
                        }
                    }, 2000);
                }
            }, 1000);
        }

        // 按设置把该开的悬浮窗恢复出来（画面悬浮窗 / 超级后视镜 /
        // 录制悬浮按钮 / 补盲）。该不该开、能不能开都在协调器里判断。
        OverlayCoordinator.restoreOnLaunch(this, this::broadcastCurrentRecordingState);
        
        // 初始化息屏录制检测
        initScreenStateReceiver();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        AppLog.d(TAG, "onNewIntent called");
        openDrawerIfAsked(intent);

// 处理从录制悬浮按钮启动（需要自动开始录制）
        if (intent.getBooleanExtra(EXTRA_AUTO_TAKE_PHOTO, false)) {
            intent.removeExtra(EXTRA_AUTO_TAKE_PHOTO);
            scheduleAutoPhoto();
        }

        boolean autoStartRecording = intent.getBooleanExtra("auto_start_recording", false);
        if (autoStartRecording) {
            intent.removeExtra("auto_start_recording");
            AppLog.d(TAG, "从录制悬浮按钮启动（onNewIntent），准备自动开始录制");
            // 标记自动录制等待中，防止 onPause 关闭摄像头
            isAutoRecordingPending = true;
            // 延迟等待摄像头准备好
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (cameraManager != null && !cameraManager.isRecording()) {
                    // 确保摄像头已连接
                    if (!cameraManager.hasConnectedCameras()) {
                        AppLog.d(TAG, "摄像头未连接，先打开摄像头");
                        cameraManager.openAllCameras();
                    }
                    // 再等待一段时间确保摄像头完全准备好
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (cameraManager != null && cameraManager.hasConnectedCameras() && !cameraManager.isRecording()) {
                            AppLog.d(TAG, "摄像头已准备好，自动开始录制");
                            startRecording();
                            // 录制开始后，延迟将 Activity 移到后台
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                if (isRecording) {
                                    AppLog.d(TAG, "录制已开始，将 Activity 移到后台");
                                    moveTaskToBack(true);
                                }
                            }, 1500);
                        } else {
                            AppLog.w(TAG, "摄像头未准备好，无法开始录制");
                            isAutoRecordingPending = false;
                            Toast.makeText(this, R.string.msg_camera_not_ready, Toast.LENGTH_SHORT).show();
                        }
                    }, 2000);
                }
            }, 500);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        
        // 如果是开机自启动模式，窗口准备好后自动移到后台
        if (hasFocus && shouldMoveToBackgroundOnReady) {
            AppLog.d(TAG, "开机自启动：窗口已就绪，移到后台（无感启动）");
            shouldMoveToBackgroundOnReady = false;  // 清除标志，避免重复执行
            
            // 延迟移到后台，确保初始化完成
            new android.os.Handler().postDelayed(() -> {
                moveTaskToBack(true);  // 将应用移到后台
                AppLog.d(TAG, "应用已移到后台，开机自启动完成");
            }, 500);  // 延迟 500ms
        }
    }








    
    /**
     * 执行启动持续录制（等同点击录制按钮）
     */
    private void executeStartPersistentRecording() {
        if (isRecording) {
            AppLog.d(TAG, "Already recording, skip");
            return;
        }
        
        startRecording();
        AppLog.d(TAG, "Persistent recording started");
        
        // 启动录制后不退到后台，保持前台
        isRemoteWakeUp = false;
    }
    
    /**
     * 执行停止录制并退到后台
     */
    private void executeStopRecordingAndBackground() {
        if (!isRecording) {
            AppLog.d(TAG, "Not recording, just move to background");
            moveTaskToBack(true);
            return;
        }
        
        stopRecording();
        AppLog.d(TAG, "Recording stopped");
        
        // 延迟退到后台
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            moveTaskToBack(true);
            AppLog.d(TAG, "Moved to background");
        }, 1000);
    }

    private void adjustFontScale(float scale) {
        android.content.res.Configuration configuration = getResources().getConfiguration();
        configuration.fontScale = scale;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        getBaseContext().getResources().updateConfiguration(configuration, metrics);
    }
    
    /**
     * 根据车型配置设置布局
     */
    /**
     * 按车型选布局。
     *
     * <p>只有三种可能：{@code getCarModel()} 走 {@link SettingsRegistry} 做净化，
     * 取值只会是极氪的两档或自定义。以前这里还排着银河 E5 / L6 / L7 / 星舰 / 手机
     * 等一长串分支，它们判断的取值早就选不出来了 —— 那些布局连同分支一起删掉了，
     * 留着只会让人以为还支持那些车。</p>
     */
    private void setupLayoutByCarModel() {
        int layoutId;

        // 用几路、摆什么布局，跟着<b>配置</b>走，不看车型那个字符串。
        //
        // 以前这两件事看车型：于是在配置编辑里往「极氪7X」那份配置加两路座舱之后，
        // 布局仍然是单路的（只有一个 TextureView），路数上限仍然是 1 ——
        // 加进去的相机既没有地方显示，也不会被拍照和录制算进去。
        Profile profile = new ProfileStore(this).current();
        int enabled = 0;
        for (CameraProfile camera : profile.cameras) {
            if (camera.enabled) {
                enabled++;
            }
        }

        if (Profile.PRESET_CUSTOM.equals(profile.id)) {
            layoutId = R.layout.activity_main_custom;
            configuredCameraCount = appConfig.getCameraCount();
            AppLog.d(TAG, "自定义布局：" + configuredCameraCount + " 路");
        } else if (enabled > 1) {
            layoutId = R.layout.activity_main_zeekr_7x_multi;
            configuredCameraCount = Math.min(enabled, 3);
            AppLog.d(TAG, "多路布局：配置里启用了 " + enabled + " 路");
        } else {
            // 一路合成流，由 FourLaneContainer 重画成四宫格。也是兜底。
            layoutId = R.layout.activity_main_zeekr_7x;
            configuredCameraCount = 1;
            AppLog.d(TAG, "单路布局：环视合成流 + 四宫格拆分");
        }

        setContentView(layoutId);
    }

    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 状态栏和页面底色连成一片
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.bg));

            // 根据当前主题模式设置状态栏图标颜色
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int nightModeFlags = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                    // 夜间模式：清除浅色状态栏标志，使用深色图标变为浅色图标
                    getWindow().getDecorView().setSystemUiVisibility(0);
                } else {
                    // 日间模式：设置状态栏图标为深色（因为背景是浅色）
                    getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    );
                }
            }
        }
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        recordingLayout = findViewById(R.id.main);
        fragmentContainer = findViewById(R.id.fragment_container);

        // 切黑白模式（以及旋转、语言这些）会让系统把这个界面整个重建一遍。
        // 重建时系统会把原来打开着的那个 Fragment 还回来，但布局的初始状态是
        // 「主界面可见、容器隐藏」—— 于是看起来像是自己退回了主界面，
        // 其实那一层还在，只是被盖住了。按实际在场的 Fragment 摆一次可见性。
        if (getSupportFragmentManager().findFragmentById(R.id.fragment_container) != null) {
            recordingLayout.setVisibility(View.GONE);
            fragmentContainer.setVisibility(View.VISIBLE);
        }

        openDrawerIfAsked(getIntent());
        
        // 设置导航头部版本号
        if (navigationView != null) {
            View headerView = navigationView.getHeaderView(0);
            if (headerView != null) {
                TextView versionText = headerView.findViewById(R.id.nav_header_version);
                if (versionText != null) {
                    try {
                        String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                        versionText.setText(
                                getString(R.string.nav_header_version, versionName));
                    } catch (Exception e) {
                        // 忽略异常，保持默认文本
                    }
                }
            }
        }

        // 根据布局获取TextureView（不同布局有不同数量的TextureView）
        textureFront = findViewById(R.id.texture_front);
        textureBack = findViewById(R.id.texture_back);  // 1摄布局中为null
        textureLeft = findViewById(R.id.texture_left);  // 1摄和2摄布局中为null
        textureRight = findViewById(R.id.texture_right);  // 1摄和2摄布局中为null

        // 极氪合成流：texture_front 是容器里那个普通的 TextureView，
        // 四宫格由父容器 FourLaneContainer 重画子视图实现
        compositeContainer = findViewById(R.id.composite_container);
        if (compositeContainer != null) {
            tvCompositeInfo = recordingLayout.findViewById(R.id.tv_composite_info);
            setupCompositeControls();
            // 容器是刚建出来的，摆位得立刻给它一份 —— 相机初始化不一定
            // 跟着布局重建走（横竖屏切换、主题切换都会重建布局）
            applyProfileCells();
        }
        
        btnStartRecord = findViewById(R.id.btn_start_record);
        btnMinimize = findViewById(R.id.btn_minimize);
        btnTakePhoto = findViewById(R.id.btn_take_photo);
        
        // 初始化录制状态显示
        tvRecordingStats = findViewById(R.id.tv_recording_stats);
        initRecordingStatsDisplay();

        // 初始化调试信息覆盖层
        tvDebugOverlay = findViewById(R.id.tv_debug_overlay);
        initDebugOverlayTapDetection();
        
        // 更新摄像头标签（如果是自定义车型）
        updateCameraLabels();

        // 初始化自定义布局管理器（如果是自定义车型）
        initCustomLayoutManager();

        // 菜单按钮点击事件（部分布局可能没有此按钮）
        View btnMenu = findViewById(R.id.btn_menu);
        if (btnMenu != null) {
            btnMenu.setOnClickListener(v -> {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    drawerLayout.openDrawer(GravityCompat.START);
                }
            });
        }
        
        // 多按钮布局的快捷导航按钮（仅在 L7-多按钮 布局中存在）
        View btnVideoPlayback = findViewById(R.id.btn_video_playback);
        if (btnVideoPlayback != null) {
            btnVideoPlayback.setOnClickListener(v -> showPlaybackInterface());
        }
        
        View btnPhotoPlayback = findViewById(R.id.btn_photo_playback);
        if (btnPhotoPlayback != null) {
            btnPhotoPlayback.setOnClickListener(v -> showPhotoPlaybackInterface());
        }
        
        View btnSettings = findViewById(R.id.btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsInterface());
        }
        
        bindRecordButtonUi();
        applyActionRailSide();
        updateStatusLine();

        // 录制按钮：点击切换录制状态
        btnStartRecord.setOnClickListener(v -> toggleRecording());

        // 隐藏到后台：录制和悬浮按钮照旧，只把界面收起来。
        // 长按是退出，和抽屉、设置左栏最底下的「退出应用」是同一件事。退出会停掉录制，
        // 放在长按上，碰一下不会误触（自定义布局的按钮在下面另接，那里仍是退出）
        if (btnMinimize != null) {
            btnMinimize.setOnClickListener(v -> {
                com.kooo.evcam.blackbox.BlackBox.note("用户点了最小化");
                moveTaskToBack(true);
            });
            btnMinimize.setOnLongClickListener(v -> {
                exitApp();
                return true;
            });
            // 读屏念出来的长按动作是「退出应用」，而不是笼统的「长按」
            androidx.core.view.ViewCompat.replaceAccessibilityAction(btnMinimize,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat
                            .AccessibilityActionCompat.ACTION_LONG_CLICK,
                    getString(R.string.nav_exit), null);
        }

        btnTakePhoto.setOnClickListener(v -> takePicture());

        if (textureFront != null) {
            textureFront.setSurfaceTextureListener(buildSurfaceListener("front"));
        }
        // 哪一路存在按槽位号判断，规则在 PreviewSlots 里 ——
        // 「left 是第三路还是和 right 成对」这处笔误已经咬过两次。
        if (textureBack != null && PreviewSlots.exists(configuredCameraCount, "back")) {
            textureBack.setSurfaceTextureListener(buildSurfaceListener("back"));
        }
        if (textureLeft != null && PreviewSlots.exists(configuredCameraCount, "left")) {
            textureLeft.setSurfaceTextureListener(buildSurfaceListener("left"));
        }
        if (textureRight != null && PreviewSlots.exists(configuredCameraCount, "right")) {
            textureRight.setSurfaceTextureListener(buildSurfaceListener("right"));
        }
    }

    /** 从别的界面按菜单键回来：抽屉要开着，否则那一下点击看起来没反应。 */
    private void openDrawerIfAsked(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_DRAWER, false)) {
            return;
        }
        intent.removeExtra(EXTRA_OPEN_DRAWER);
        if (drawerLayout != null) {
            drawerLayout.post(() ->
                    drawerLayout.openDrawer(androidx.core.view.GravityCompat.START));
        }
    }

    /** 别的界面上的菜单键用它：那些界面没有抽屉，抽屉长在这里。 */
    public void openDrawer() {
        if (drawerLayout != null) {
            drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
        }
    }

    private TextureView.SurfaceTextureListener buildSurfaceListener(String cameraKey) {
        return new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                textureReadyCount++;
                AppLog.d(TAG, "TextureView " + cameraKey + " ready: " + textureReadyCount
                        + "/" + PreviewSlots.requiredTextures(configuredCameraCount));

                if (PreviewSlots.canStartCamera(textureReadyCount, configuredCameraCount)
                        && checkPermissions()) {
                    if (cameraManager == null) {
                        initCamera();
                    } else {
                        cameraManager.updatePreviewTextureViews(textureFront, textureBack, textureLeft, textureRight);
                    }
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                AppLog.d(TAG, "TextureView " + cameraKey + " size changed: " + width + "x" + height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface) {
                textureReadyCount--;
                AppLog.d(TAG, "TextureView " + cameraKey + " destroyed, remaining: " + textureReadyCount);
                if (cameraManager != null) {
                    cameraManager.onPreviewTextureDestroyed(cameraKey);
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture surface) {
                // 这个回调每来一帧就响一次 —— 这就是「不录制时相机的出帧率」，
                // 也就是这条视频流本身的上限。录制时的帧率再低，也不会比它高。
                com.kooo.evcam.camera.PreviewFrameRates.onFrame(cameraKey);
            }
        };
    }
    
    /**
     * 更新摄像头标签
     * 统一使用 AppConfig.getCameraName() 的值，确保主界面和设置界面显示一致
     */
    private void updateCameraLabels() {
        // 获取标签控件（根据布局可能存在或不存在）
        TextView labelFront = findViewById(R.id.label_front);
        TextView labelBack = findViewById(R.id.label_back);
        TextView labelLeft = findViewById(R.id.label_left);
        TextView labelRight = findViewById(R.id.label_right);
        
        // 设置自定义名称，如果名称为空则隐藏标签
        if (labelFront != null) {
            updateCameraLabel(labelFront, appConfig.getCameraName(this, "front"));
        }
        if (labelBack != null && configuredCameraCount >= 2) {
            updateCameraLabel(labelBack, appConfig.getCameraName(this, "back"));
        }
        if (labelLeft != null && configuredCameraCount >= 3) {
            updateCameraLabel(labelLeft, appConfig.getCameraName(this, "left"));
        }
        if (labelRight != null && configuredCameraCount >= 4) {
            updateCameraLabel(labelRight, appConfig.getCameraName(this, "right"));
        }
    }
    
    /**
     * 更新单个摄像头标签，如果名称为空则隐藏
     */
    private void updateCameraLabel(TextView label, String name) {
        if (name == null || name.trim().isEmpty()) {
            label.setVisibility(View.GONE);
        } else {
            label.setText(name);
            label.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 初始化自定义布局管理器（仅在自定义车型时有效）
     * 业务逻辑委托给 CustomLayoutManager 处理
     */
    private void initCustomLayoutManager() {
        if (!appConfig.needsCustomLayoutManager()) {
            return;
        }

        // 获取视图引用
        android.widget.FrameLayout frameFront = findViewById(R.id.frame_front);
        android.widget.FrameLayout frameBack = findViewById(R.id.frame_back);
        android.widget.FrameLayout frameLeft = findViewById(R.id.frame_left);
        android.widget.FrameLayout frameRight = findViewById(R.id.frame_right);
        View editControls = findViewById(R.id.edit_controls);
        View containerCameras = findViewById(R.id.container_cameras);
        
        // 按钮容器根据方向选择
        String buttonOrientation = appConfig.getCustomButtonOrientation();
        boolean isVertical = AppConfig.BUTTON_ORIENTATION_VERTICAL.equals(buttonOrientation);
        android.view.ViewGroup buttonContainer = isVertical ? 
            findViewById(R.id.container_buttons_left) : 
            findViewById(R.id.container_buttons_bottom);

        // 根据摄像头数量隐藏不需要的容器。
        // 同样按槽位序号分开判断：3 路时 left 该留着，只藏 right。
        if (configuredCameraCount < 4) {
            if (frameRight != null) frameRight.setVisibility(View.GONE);
        }
        if (configuredCameraCount < 3) {
            if (frameLeft != null) frameLeft.setVisibility(View.GONE);
        }
        if (configuredCameraCount < 2) {
            if (frameBack != null) frameBack.setVisibility(View.GONE);
        }

        // 动态加载按钮布局
        setupCustomButtonLayout(buttonContainer);

        // 初始化布局管理器（所有业务逻辑由 Manager 处理）
        customLayoutManager = new CustomLayoutManager(this);
        customLayoutManager.setCameraCount(configuredCameraCount);
        customLayoutManager.setOnButtonLayoutChangeListener(orientation -> {
            // 重新加载按钮布局
            android.view.ViewGroup newContainer = orientation.equals(AppConfig.BUTTON_ORIENTATION_VERTICAL) ?
                    findViewById(R.id.container_buttons_left) : findViewById(R.id.container_buttons_bottom);
            setupCustomButtonLayout(newContainer);
            
            // 更新布局管理器中的按钮容器引用
            customLayoutManager.updateButtonContainer(newContainer);
        });
        customLayoutManager.setupFloatingViews(
                frameFront, frameBack, frameLeft, frameRight, null,
                buttonContainer, editControls, containerCameras,
                textureFront, textureBack, textureLeft, textureRight);

        // 初始化摄像头录制开关
        initCameraToggleButtons();

        AppLog.d(TAG, "自定义布局管理器初始化完成");
    }

    /**
     * 初始化摄像头开关（多视角布局）
     * 在每个画面右上角显示macOS风格开关，同时控制画面显示/隐藏和录制
     */
    private void initCameraToggleButtons() {
        // 获取摄像头画面容器
        android.widget.FrameLayout frameFront = findViewById(R.id.frame_front);
        android.widget.FrameLayout frameBack = findViewById(R.id.frame_back);
        android.widget.FrameLayout frameLeft = findViewById(R.id.frame_left);
        android.widget.FrameLayout frameRight = findViewById(R.id.frame_right);

        setupCameraFrameTouchListeners(frameFront, frameBack, frameLeft, frameRight);
    }

    private FullscreenPreviewDialog currentFullscreenDialog;

    private void setupCameraFrameTouchListeners(android.widget.FrameLayout frameFront,
                                                  android.widget.FrameLayout frameBack,
                                                  android.widget.FrameLayout frameLeft,
                                                  android.widget.FrameLayout frameRight) {
        setupSingleCameraFrameTouchListener(frameFront, "front");
        setupSingleCameraFrameTouchListener(frameBack, "back");
        setupSingleCameraFrameTouchListener(frameLeft, "left");
        setupSingleCameraFrameTouchListener(frameRight, "right");
    }

    private void setupSingleCameraFrameTouchListener(android.widget.FrameLayout frame, String cameraPosition) {
        if (frame == null) return;

        frame.setOnClickListener(v -> {
            if (currentFullscreenDialog != null && currentFullscreenDialog.isShowing()) {
                return;
            }
            showFullscreenPreview(cameraPosition);
        });
    }

    /**
     * 等相机就绪再拍一张。
     *
     * <p>悬浮按钮在应用没运行时按了拍照，只能先把界面拉起来 —— 而这会儿相机
     * 还没连上。和自动开始录制走同一个思路：等一会儿，等到了就拍，等不到就算了，
     * 不弹错误 —— 用户按的是一个快捷键，不是在等一个回执。</p>
     */
    private void scheduleAutoPhoto() {
        AppLog.d(TAG, "从悬浮按钮拍照，等相机就绪");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (cameraManager != null && cameraManager.hasConnectedCameras()) {
                takePicture();
            } else {
                AppLog.w(TAG, "相机还没就绪，这次拍照放弃");
            }
        }, 3000);
    }

    private void showFullscreenPreview(String cameraPosition) {
        AppLog.d(TAG, "显示全屏预览: " + cameraPosition);

        currentFullscreenDialog = new FullscreenPreviewDialog(this, cameraPosition);
        currentFullscreenDialog.setOnDismissListener(dialog -> {
            currentFullscreenDialog = null;
        });
        currentFullscreenDialog.show();
    }

    /**
     * 设置自定义按钮布局
     * 根据配置动态加载按钮样式和方向
     */
    private void setupCustomButtonLayout(android.view.ViewGroup ignoredContainer) {
        // 获取配置
        String buttonStyle = appConfig.getCustomButtonStyle();
        String buttonOrientation = appConfig.getCustomButtonOrientation();
        boolean isVertical = AppConfig.BUTTON_ORIENTATION_VERTICAL.equals(buttonOrientation);
        
        AppLog.d(TAG, "按钮配置读取: style=" + buttonStyle + " (standard=" + AppConfig.BUTTON_STYLE_STANDARD + "), orientation=" + buttonOrientation);
        
        // 获取两个按钮容器
        android.widget.FrameLayout leftContainer = findViewById(R.id.container_buttons_left);
        android.widget.FrameLayout bottomContainer = findViewById(R.id.container_buttons_bottom);
        
        if (leftContainer == null || bottomContainer == null) {
            AppLog.e(TAG, "Button containers not found");
            return;
        }
        
        // 清除两个容器
        leftContainer.removeAllViews();
        bottomContainer.removeAllViews();
        
        // 选择布局资源
        int layoutResId;
        boolean isStandard = AppConfig.BUTTON_STYLE_STANDARD.equals(buttonStyle);
        AppLog.d(TAG, "按钮样式判断: buttonStyle='" + buttonStyle + "', STANDARD='" + AppConfig.BUTTON_STYLE_STANDARD + "', isStandard=" + isStandard);
        
        if (isStandard) {
            // 标准按钮（E5风格图标按钮）
            layoutResId = isVertical ? 
                R.layout.layout_custom_buttons_standard_vertical : 
                R.layout.layout_custom_buttons_standard;
            AppLog.d(TAG, ">>> 使用标准按钮布局(图标) - " + (isVertical ? "竖版" : "横版") + ", layoutResId=" + layoutResId);
        } else {
            // 多按钮（文字按钮）
            layoutResId = isVertical ? 
                R.layout.layout_custom_buttons_multi_vertical : 
                R.layout.layout_custom_buttons_multi;
            AppLog.d(TAG, ">>> 使用多按钮布局(文字) - " + (isVertical ? "竖版" : "横版") + ", layoutResId=" + layoutResId);
        }
        
        // 加载布局到正确的容器
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(this);
        View buttonsView = inflater.inflate(layoutResId, null, false);
        
        android.view.ViewGroup targetContainer;
        if (isVertical) {
            // 竖版：按钮在左侧
            leftContainer.addView(buttonsView);
            leftContainer.setVisibility(View.VISIBLE);
            bottomContainer.setVisibility(View.GONE);
            targetContainer = leftContainer;
        } else {
            // 横版：按钮在底部
            bottomContainer.addView(buttonsView);
            bottomContainer.setVisibility(View.VISIBLE);
            leftContainer.setVisibility(View.GONE);
            targetContainer = bottomContainer;
        }

        // 重新获取按钮引用
        btnStartRecord = targetContainer.findViewById(R.id.btn_start_record);
        btnExit = targetContainer.findViewById(R.id.btn_exit);
        btnTakePhoto = targetContainer.findViewById(R.id.btn_take_photo);
        bindRecordButtonUi();

        // 设置按钮点击事件
        if (btnStartRecord != null) {
            btnStartRecord.setOnClickListener(v -> toggleRecording());
        }
        if (btnExit != null) {
            btnExit.setOnClickListener(v -> exitApp());
        }
        if (btnTakePhoto != null) {
            btnTakePhoto.setOnClickListener(v -> takePicture());
        }

        // 设置其他快捷按钮
        View btnVideoPlayback = targetContainer.findViewById(R.id.btn_video_playback);
        if (btnVideoPlayback != null) {
            btnVideoPlayback.setOnClickListener(v -> showPlaybackInterface());
        }

        View btnPhotoPlayback = targetContainer.findViewById(R.id.btn_photo_playback);
        if (btnPhotoPlayback != null) {
            btnPhotoPlayback.setOnClickListener(v -> showPhotoPlaybackInterface());
        }

        View btnSettings = targetContainer.findViewById(R.id.btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsInterface());
        }
        
        // 菜单按钮（标准按钮样式有此按钮）
        View btnMenu = targetContainer.findViewById(R.id.btn_menu);
        if (btnMenu != null) {
            btnMenu.setOnClickListener(v -> {
                if (drawerLayout != null) {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START);
                    } else {
                        drawerLayout.openDrawer(GravityCompat.START);
                    }
                }
            });
        }
    }
    
    /**
     * 初始化录制状态显示
     */
    private void initRecordingStatsDisplay() {
        if (tvRecordingStats == null) {
            return;
        }
        
        // 从设置加载显示开关状态
        isRecordingStatsEnabled = appConfig.isRecordingStatsEnabled();
        
        // 初始化计时器 Handler
        recordingTimerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        
        // 确保 View 可点击（即使 INVISIBLE 也能响应点击）
        tvRecordingStats.setClickable(true);
        tvRecordingStats.setFocusable(true);
        
        // 设置双击切换显示/隐藏
        tvRecordingStats.setOnClickListener(v -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastStatsClickTime < DOUBLE_CLICK_INTERVAL) {
                // 双击：切换显示状态
                toggleRecordingStatsDisplay();
                lastStatsClickTime = 0;  // 重置，避免三连击触发
            } else {
                lastStatsClickTime = currentTime;
            }
            AppLog.d(TAG, "录制状态显示被点击, isRecording=" + isRecording + ", enabled=" + isRecordingStatsEnabled);
        });
    }
    
    /**
     * 切换录制状态显示的开关
     */
    private void toggleRecordingStatsDisplay() {
        isRecordingStatsEnabled = !isRecordingStatsEnabled;
        appConfig.setRecordingStatsEnabled(isRecordingStatsEnabled);
        
        if (tvRecordingStats != null && isRecording) {
            if (isRecordingStatsEnabled) {
                // 显示状态（使用 alpha 恢复可见）
                tvRecordingStats.setAlpha(1.0f);
                Toast.makeText(this, R.string.msg_stats_on, Toast.LENGTH_SHORT).show();
            } else {
                // 使用 alpha=0 隐藏，但保持 VISIBLE 状态以响应点击
                tvRecordingStats.setAlpha(0.0f);
                Toast.makeText(this, R.string.msg_stats_off, Toast.LENGTH_SHORT).show();
            }
        }
        
        AppLog.d(TAG, "录制状态显示切换: " + (isRecordingStatsEnabled ? "开启" : "关闭"));
    }
    
    /**
     * 开始录制计时器
     */
    private void startRecordingTimer() {
        startRecordingTimer(0, 1);  // 使用默认值，从头开始计时
    }
    
    /**
     * 开始录制计时器（支持恢复）
     * @param savedStartTime 保存的开始时间（0表示从当前时间开始）
     * @param savedSegment 保存的分段数
     */
    private void startRecordingTimer(long savedStartTime, int savedSegment) {
        if (savedStartTime > 0) {
            // 恢复模式：使用保存的开始时间
            recordingStartTime = savedStartTime;
            currentSegmentCount = savedSegment;
            AppLog.d(TAG, "恢复录制计时器 - startTime=" + savedStartTime + ", segment=" + savedSegment);
        } else {
            // 新录制：使用当前时间
            recordingStartTime = System.currentTimeMillis();
            currentSegmentCount = 1;
        }
        
        segmentLengthMs = com.kooo.evcam.profile.RecordSpecs.segmentMs(
                com.kooo.evcam.profile.RecordSpecs.forCameraKey(this, "front").segmentMinutes);
        // 分段按时长切：恢复时第 N 段的起点就是开始时间往后数 N-1 段
        segmentStartTime = recordingStartTime + (currentSegmentCount - 1) * segmentLengthMs;
        updateStatusLine();

        if (tvRecordingStats != null) {
            // 始终设为 VISIBLE，通过 alpha 控制可见性
            tvRecordingStats.setVisibility(View.VISIBLE);
            tvRecordingStats.setAlpha(isRecordingStatsEnabled ? 1.0f : 0.0f);
            updateRecordingStatsDisplay();
        }
        
        // 创建定时更新任务
        recordingTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRecording) {
                    updateRecordingStatsDisplay();
                    recordingTimerHandler.postDelayed(this, 1000);  // 每秒更新一次
                }
            }
        };
        
        recordingTimerHandler.post(recordingTimerRunnable);
    }
    
    /**
     * 停止录制计时器
     */
    private void stopRecordingTimer() {
        if (recordingTimerHandler != null && recordingTimerRunnable != null) {
            recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
        }
        
        // 隐藏录制状态显示
        if (tvRecordingStats != null) {
            tvRecordingStats.setVisibility(View.GONE);
        }
        
        recordingStartTime = 0;
        currentSegmentCount = 1;
        segmentStartTime = 0;
    }
    
    /**
     * 更新录制状态显示
     */
    private void updateRecordingStatsDisplay() {
        updateSegmentProgress();
        if (tvRecordingStats == null) {
            return;
        }
        
        // 计算录制时长
        long elapsedMs = System.currentTimeMillis() - recordingStartTime;
        long totalSeconds = elapsedMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        
        // 即使隐藏也更新文本，便于双击显示时立即看到正确时间
        String elapsed = String.format(java.util.Locale.US, "%02d:%02d:%02d",
                minutes / 60, minutes % 60, seconds);
        tvRecordingStats.setText(getString(R.string.recording_chip, elapsed, currentSegmentCount));
    }
    
    /**
     * 当分段切换时调用，更新分段计数
     */
    public void onSegmentSwitch(int newSegmentIndex) {
        currentSegmentCount = newSegmentIndex + 1;  // 分段索引从0开始，显示从1开始
        segmentStartTime = System.currentTimeMillis();
        AppLog.d(TAG, "分段切换: 第 " + currentSegmentCount + " 段");
        
        // 立即更新显示；换段的那一下录制键淡一下，状态条的余量也顺手刷新
        runOnUiThread(() -> {
            updateRecordingStatsDisplay();
            if (recordButtonUi != null) {
                recordButtonUi.flashSegment();
            }
            updateStatusLine();
        });
    }
    
    /**
     * 刷新录制状态显示设置（从设置界面返回时调用）
     */
    public void refreshRecordingStatsSettings() {
        isRecordingStatsEnabled = appConfig.isRecordingStatsEnabled();
        
        // 如果正在录制，根据新设置显示或隐藏（通过 alpha 控制，保持可点击）
        if (isRecording && tvRecordingStats != null) {
            tvRecordingStats.setAlpha(isRecordingStatsEnabled ? 1.0f : 0.0f);
        }
    }

    /**
     * 本段进度，画在录制键外圈上。
     *
     * <p>按时长算，不按文件大小 —— 分段本来就是按时长切的。</p>
     *
     * <p>状态条上原来还有一条进度和「本段 xx%」，和这个环是同一个数。
     * 录制时它在实际值和 100% 之间来回跳，而同一件事录制键上已经有了，
     * 所以删掉（项目拥有者 2026-09 定）。</p>
     */
    private void updateSegmentProgress() {
        if (!isRecording || segmentStartTime <= 0 || segmentLengthMs <= 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - segmentStartTime;
        if (recordButtonUi != null) {
            recordButtonUi.setSegmentProgress(elapsed, segmentLengthMs);
        }
    }

    /**
     * 待机时录制键该是「开始录制」还是「插入 U 盘后可录制」。
     *
     * <p>判断和拒录是同一个（{@code StorageHelper.isRecordingStorageAvailable}）——
     * 按钮不会显示能录、按下去却被拒。录制中不动它：盘拔了由录制链路自己处理。</p>
     */
    private void refreshRecordAvailability() {
        if (recordButtonUi == null || isRecording) {
            return;
        }
        recordButtonUi.setState(StorageHelper.isRecordingStorageAvailable(this)
                ? com.kooo.evcam.ui.RecordButtonUi.State.IDLE
                : com.kooo.evcam.ui.RecordButtonUi.State.UNAVAILABLE);
    }

    /**
     * 状态条左侧：这次按什么录、U 盘还剩多少。
     *
     * <p>取的是录制链路真正读的那一份（{@code RecordSpecs}），不是另存的显示值。
     * 帧率在配置里的含义是上限（硬件给不到就按硬件的），所以写成「≤」；
     * 码率「自动」在录制链路里就是中档（{@code RecordSpecs.qualityLevel}），也照实写中档。</p>
     */
    /**
     * 状态条上「按什么录」和「剩多少空间」两格。
     *
     * <p>限定在 {@code recordingLayout} 这棵树里找：设置界面也有一条状态条，
     * 从 Activity 上找会撞到看不见的那一份。</p>
     */
    private void updateStatusLine() {
        com.kooo.evcam.ui.StatusLine.overlay(recordingLayout);
        com.kooo.evcam.ui.StatusLine.fill(recordingLayout);
    }

    /** 录制键换了实例（布局重建、自定义车型换按钮布局）就重新接一次。 */
    private void bindRecordButtonUi() {
        recordButtonUi = btnStartRecord != null ? new com.kooo.evcam.ui.RecordButtonUi(btnStartRecord) : null;
        if (recordButtonUi == null) {
            return;
        }
        recordButtonUi.setSegmentMinutes(
                com.kooo.evcam.profile.RecordSpecs.forCameraKey(this, "front").segmentMinutes);
        if (isRecording) {
            recordButtonUi.setState(isPreparingRecording ? com.kooo.evcam.ui.RecordButtonUi.State.PREPARING : com.kooo.evcam.ui.RecordButtonUi.State.RECORDING);
        } else {
            refreshRecordAvailability();
        }
    }

    /**
     * 获取当前各摄像头的分辨率信息（供分辨率设置界面使用）
     * @return 格式化的分辨率信息字符串
     */
    public String getCurrentCameraResolutionsInfo() {
        if (cameraManager != null) {
            return cameraManager.getCameraResolutionsInfo();
        }
        return null;
    }


    

    


    /**
     * 切换侧边栏的打开/关闭状态
     */
    public void toggleDrawer() {
        if (drawerLayout != null) {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        }
    }

    /**
     * 设置导航抽屉
     */
    private void setupNavigationDrawer() {
        // 设置导航菜单点击监听
        drawerLayout.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                syncDeveloperMenuVisibility();
                // 后视镜也可能在设置里、或者悬浮窗自己关掉过
                syncRearViewSwitch();
            }
        });
        syncDeveloperMenuVisibility();
        syncRearViewSwitch();

        navigationView.setNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();

            // 开关行：点一下就拨，不跳界面、不收抽屉 —— 拨完还看得见它变了没有
            if (itemId == R.id.nav_rearview) {
                toggleRearViewFromDrawer();
                return false;
            }

            if (itemId == R.id.nav_floating_button) {
                toggleFloatingButtonFromDrawer();
                return false;
            }

            if (itemId == R.id.nav_exit) {
                exitApp();
                return true;
            }
            
            if (itemId == R.id.nav_recording) {
                // 显示录制界面
                showRecordingInterface();
            } else if (itemId == R.id.nav_playback) {
                // 显示回看界面
                showPlaybackInterface();
            } else if (itemId == R.id.nav_photo_playback) {
                // 显示图片回看界面
                showPhotoPlaybackInterface();
            } else if (itemId == R.id.nav_secondary_display) {
                // 显示补盲选项界面
                showBlindSpotInterface();
            } else if (itemId == R.id.nav_supervision_mode) {
                // 切换超视模式
                toggleSupervisionMode();
            } else if (itemId == R.id.nav_settings) {
                showSettingsInterface();
            } else if (itemId == R.id.nav_about) {
                startActivity(new Intent(this, com.kooo.evcam.zeekr.AboutActivity.class));
            }
            selectNavItem(itemId);
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // 默认选中录制界面
        navigationView.setCheckedItem(R.id.nav_recording);
    }
    
    /**
     * 清除所有导航菜单项的选中状态
     * 用于处理跨组选中时的状态同步
     */
    /**
     * 选中侧边栏里的某一项。
     *
     * <p>跨组选中要先把别的清掉，否则会同时亮着两项。</p>
     */
    private void selectNavItem(int itemId) {
        if (navigationView == null) {
            return;
        }
        clearAllNavigationChecks();
        navigationView.setCheckedItem(itemId);
    }

    private void clearAllNavigationChecks() {
        Menu menu = navigationView.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            item.setChecked(false);
            // 处理子菜单
            if (item.hasSubMenu()) {
                SubMenu subMenu = item.getSubMenu();
                for (int j = 0; j < subMenu.size(); j++) {
                    subMenu.getItem(j).setChecked(false);
                }
            }
        }
    }

    /**
     * 检查并处理首次启动
     * 首次启动时自动进入设置界面并显示引导弹窗
     */
    private void checkFirstLaunch() {
        if (appConfig == null) {
            return;
        }

        // 语言先问。它和「首次启动」分开记：选完语言可能触发界面重建，
        // 重建之后引导弹窗还得照常出现，共用一个标记就会把引导吞掉。
        if (!appConfig.isLanguageChosen()) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(this::showLanguageChoiceDialog, 300);
            return;
        }

        // 再问方向盘在哪边：它决定操作按钮放哪一侧。老用户升级上来也问这一次
        if (!appConfig.isRailSideChosen()) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(this::showRailSideChoiceDialog, 300);
            return;
        }

        if (!appConfig.isFirstLaunch()) {
            return;
        }

        AppLog.d(TAG, "检测到首次启动，进入设置界面");

        // 标记首次启动已完成（在显示弹窗前标记，避免重复触发）
        appConfig.setFirstLaunchCompleted();

        // 延迟执行，确保 UI 完全初始化
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            // 进入设置界面
            showSettingsInterface();
            selectNavItem(R.id.nav_settings);

            // 显示引导弹窗
            showFirstLaunchGuideDialog();
        }, 300);
    }

    /**
     * 首次启动时选界面语言。
     *
     * <p>默认「跟随系统」并且预先选中 —— 车机是什么语言，应用就该是什么语言，
     * 这是不需要任何人做决定的默认。直接关掉弹窗也停在这一档。</p>
     *
     * <p>选完如果语言真的变了，系统会重建界面；那时 {@code isLanguageChosen()}
     * 已经是 true，于是接着走引导弹窗，而引导已经是新语言的了。</p>
     */
    private void showLanguageChoiceDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        SettingSpec spec = SettingsRegistry.LANGUAGE;
        String[] values = spec.values();
        String[] labels = new String[values.length];
        int[] res = spec.nameResIds();
        for (int i = 0; i < labels.length; i++) {
            labels[i] = res[i] != 0 ? getString(res[i]) : spec.displayNames()[i];
        }
        final int[] picked = {Math.max(0, spec.indexOf(appConfig.getLanguageMode()))};

        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_language_title)
                .setSingleChoiceItems(labels, picked[0], (d, which) -> picked[0] = which)
                .setPositiveButton(android.R.string.ok, (d, w) -> applyLanguageChoice(
                        spec.valueAt(picked[0])))
                .setOnCancelListener(d -> applyLanguageChoice(spec.valueAt(picked[0])))
                // 注意：AlertDialog 一旦设了选项列表就不再显示 message，
                // 所以「以后能在哪里改」这句放到选完之后提示
                .setCancelable(true));
    }

    private void applyLanguageChoice(String mode) {
        appConfig.setLanguageMode(mode);
        appConfig.setLanguageChosen();
        Languages.apply(mode);
        Toast.makeText(this, R.string.msg_language_hint, Toast.LENGTH_LONG).show();
        // 语言没变的话不会重建，这里接着往下走
        checkFirstLaunch();
    }

    /**
     * 首次启动问方向盘在哪边，决定操作按钮放哪一侧。
     *
     * <p>规则是<b>离驾驶位近的那一侧</b>：左舵车人坐在左边，中控屏在右手边，
     * 屏幕离人最近的是它的左边缘；右舵车反过来。选项上把这条规则直接写出来，
     * 不合心意选另一个就是。预先选中的是现在的设置，直接关掉弹窗也停在这一档。</p>
     */
    private void showRailSideChoiceDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        String[] sides = {"left", "right"};
        String[] labels = {
                getString(R.string.dlg_rail_side_lhd),
                getString(R.string.dlg_rail_side_rhd),
        };
        final int[] picked = {"left".equals(appConfig.getActionRailSide()) ? 0 : 1};
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_rail_side_title)
                .setSingleChoiceItems(labels, picked[0], (d, which) -> picked[0] = which)
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> applyRailSideChoice(sides[picked[0]]))
                .setOnCancelListener(d -> applyRailSideChoice(sides[picked[0]]))
                .setCancelable(true));
    }

    private void applyRailSideChoice(String side) {
        appConfig.setActionRailSide(side);
        appConfig.setRailSideChosen();
        applyActionRailSide();
        Toast.makeText(this, R.string.msg_rail_side_hint, Toast.LENGTH_LONG).show();
        checkFirstLaunch();
    }

    /**
     * 显示首次启动引导弹窗（美化版）
     */
    private void showFirstLaunchGuideDialog() {
        // 创建自定义对话框
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_first_launch_guide);
        dialog.setCancelable(false);

        // 设置对话框窗口属性
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            // 设置背景透明（让圆角生效）
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            // 设置对话框宽度
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85);
            window.setAttributes(params);
        }

        // 加载二维码图片

        // 设置确认按钮点击事件
        dialog.findViewById(R.id.btn_confirm).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /**
     * 显示录制界面
     */
    public void showRecordingInterface() {
        FragmentManager fragmentManager = getSupportFragmentManager();

        // 返回栈也要清掉。只把 Fragment 移走而把栈留着，下一次按返回键就会去
        // 反演一个已经不在屏幕上的事务 —— 表现是「按了没反应，得再按一次」。
        fragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);

        // 一个事务里移完，不是每个 Fragment 各提交一次
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        for (Fragment fragment : fragmentManager.getFragments()) {
            transaction.remove(fragment);
        }
        transaction.commit();

        // 可能刚在设置里换了边
        applyActionRailSide();
        recordingLayout.setVisibility(View.VISIBLE);
        fragmentContainer.setVisibility(View.GONE);
    }

    /**
     * 把一个界面放进内容区。
     *
     * <p>「藏录制布局、亮出容器、replace、commit」这四步原先在三处各写一遍。</p>
     *
     * <p>顺带把它标成<b>主导航 Fragment</b>。这是 Android 用来认「当前是哪一层」
     * 的标准做法：标了之后它自己的子返回栈才算数 —— 设置页的二级界面
     * （权限、相机映射）就在那个子返回栈里。</p>
     */
    private void showFragment(Fragment fragment) {
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);
        // 进一层：沿 Z 轴放大淡入，和设置里进二级界面是同一个动作。
        // 录制中（且开着「录制时减少动效」）或系统关了动画时直接切
        if (com.kooo.evcam.ui.MotionPolicy.decorative(this)) {
            fragment.setEnterTransition(new com.google.android.material.transition.MaterialSharedAxis(
                    com.google.android.material.transition.MaterialSharedAxis.Z, true));
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .setPrimaryNavigationFragment(fragment)
                .commit();
    }

    /**
     * 动作栏放到设置里选的那一侧（左舵 / 右舵）。
     *
     * <p>只挪这一列：从行里摘下来插到另一头，外边距跟着对调。预览画面不动，
     * 它的 Surface 也就不会被销毁重建。自定义车型的布局没有这一列，直接跳过。</p>
     */
    private void applyActionRailSide() {
        View rail = findViewById(R.id.action_rail);
        if (rail == null || !(rail.getParent() instanceof android.widget.LinearLayout)
                || !(rail.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams)) {
            return;
        }
        android.widget.LinearLayout row = (android.widget.LinearLayout) rail.getParent();
        boolean left = "left".equals(appConfig.getActionRailSide());
        int target = left ? 0 : row.getChildCount() - 1;
        if (row.indexOfChild(rail) != target) {
            if (row.isLaidOut() && com.kooo.evcam.ui.MotionPolicy.decorative(this)) {
                android.transition.TransitionManager.beginDelayedTransition(row,
                        new android.transition.ChangeBounds().setDuration(250));
            }
            row.removeView(rail);
            row.addView(rail, left ? 0 : row.getChildCount());
        }
        android.widget.LinearLayout.LayoutParams params =
                (android.widget.LinearLayout.LayoutParams) rail.getLayoutParams();
        int gutter = getResources().getDimensionPixelSize(R.dimen.gutter);
        params.setMarginStart(left ? 0 : gutter);
        params.setMarginEnd(left ? gutter : 0);
        rail.setLayoutParams(params);
    }

    /**
     * 公共方法：返回预览/录制界面
     * 供 Fragment 中的主页按钮调用
     */
    public void goToRecordingInterface() {
        // 关闭侧边栏（如果打开的话）
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
        showRecordingInterface();
        selectNavItem(R.id.nav_recording);
    }

    /**
     * 显示回看界面。
     *
     * <p>以前这里是一个自己开到 5 个播放器的四宫格 Fragment。环视录像本身就是
     * 一个 2×2 网格文件，四路都在同一个文件里 —— 放大其中一路只需要在同一个
     * 解码器上换个取景，根本不需要第二个播放器。那些播放器的来回创建与切换，
     * 正是绿屏、马赛克和卡顿的来源，所以整个界面换成了时间轴播放器。</p>
     */
    private void showPlaybackInterface() {
        startActivity(new Intent(this, com.kooo.evcam.zeekr.TimelinePlayerActivity.class));
    }

    /**
     * 显示图片回看界面（新版四宫格界面）
     */
    private void showPhotoPlaybackInterface() {
        startActivity(new Intent(this, com.kooo.evcam.playback.PhotoPlaybackActivity.class));
    }








    
    /**
     * 显示软件设置界面
     */
    private void showSettingsInterface() {
        showFragment(new com.kooo.evcam.settings.SettingsShellFragment());
    }

    /**
     * 显示补盲选项设置界面
     */
    public void showBlindSpotInterface() {
        showFragment(new BlindSpotSettingsFragment());
    }

    /**
     * 切换超视模式
     * 超视模式会同时显示左右两个补盲悬浮窗
     */
    public void toggleSupervisionMode() {
        AppConfig appConfig = new AppConfig(this);
        boolean currentEnabled = appConfig.isSupervisionModeEnabled();
        boolean newEnabled = !currentEnabled;
        
        // 更新配置
        appConfig.setSupervisionModeEnabled(newEnabled);
        
        // 显示提示
        String message = getString(newEnabled
                ? R.string.msg_supervision_on : R.string.msg_supervision_off);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        
        // 发送广播通知BlindSpotService
        Intent intent = new Intent("com.kooo.evcam.SUPERVISION_MODE_CHANGED");
        intent.putExtra("enabled", newEnabled);
        sendBroadcast(intent);
        
        // 启动或停止服务
        Intent serviceIntent = new Intent(this, BlindSpotService.class);
        if (newEnabled) {
            serviceIntent.setAction("START_SUPERVISION_MODE");
        } else {
            serviceIntent.setAction("STOP_SUPERVISION_MODE");
        }
        startService(serviceIntent);
        
        AppLog.d(TAG, "超视模式切换: " + newEnabled);
    }

    /**
     * 录制这一项的登记跟着状态走。
     *
     * <p>「正要开始录」也算 —— 开机自启动那条路上，相机得先开着等录制起来，
     * 这中间要是被判成「没人要」关掉了，录制就永远起不来。</p>
     */
    private void syncRecordingClaim() {
        com.kooo.evcam.camera.CameraNeeds needs = com.kooo.evcam.camera.CameraNeeds.current();
        if (isRecording || isRemoteRecording || isAutoRecordingPending) {
            needs.claim(com.kooo.evcam.camera.CameraNeeds.Holder.RECORDING);
        } else {
            needs.release(com.kooo.evcam.camera.CameraNeeds.Holder.RECORDING);
        }
    }

    private boolean checkPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                AppLog.d(TAG, "Missing permission: " + permission);
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        AppLog.d(TAG, "Requesting permissions...");
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_PERMISSIONS);
    }

    /**
     * 悄悄申请一次读点火状态的权限。
     *
     * <p>{@code CAR_POWERTRAIN} 是 <b>normal</b> 级（2026-08-29 在车上读出来的保护级别），
     * 本该安装时自动授予，但这台车机的容器没有代为授予。normal 级申请<b>不弹框</b>，
     * 所以这一次对用户是无感的。</p>
     *
     * <p>只申请这一个：同组的车速、电量是 dangerous 级会弹框，留给诊断页那颗按钮；
     * 转向灯和车门是 signature|privileged，申请也没用。</p>
     *
     * <p>拿到之后 {@link com.kooo.evcam.zeekr.VehicleSignalWatch} 才读得到点火状态 ——
     * 那是「断电前把录像关干净」目前最有希望的一条路。</p>
     */
    private void requestPowertrainPermissionQuietly() {
        String permission = "android.car.permission.CAR_POWERTRAIN";
        try {
            if (ContextCompat.checkSelfPermission(this, permission)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            AppLog.i(TAG, "申请 CAR_POWERTRAIN（normal 级，不弹框）");
            ActivityCompat.requestPermissions(this, new String[]{permission},
                    REQUEST_CAR_POWERTRAIN);
        } catch (Exception e) {
            AppLog.w(TAG, "申请 CAR_POWERTRAIN 失败: " + e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (checkPermissions()) {
                // 权限已授予，但需要等待TextureView准备好
                // 如果TextureView已经准备好，立即初始化摄像头
                if (PreviewSlots.canStartCamera(textureReadyCount, configuredCameraCount)) {
                    initCamera();
                }
            } else {
                Toast.makeText(this, R.string.msg_need_camera_storage, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void initCamera() {
        // 确保所有需要的TextureView都准备好
        if (!PreviewSlots.canStartCamera(textureReadyCount, configuredCameraCount)) {
            AppLog.w(TAG, "画面还没全部就绪: " + textureReadyCount
                    + "/" + PreviewSlots.requiredTextures(configuredCameraCount));
            return;
        }
        
        // 防止重复初始化：如果 cameraManager 已经存在，直接返回
        if (cameraManager != null) {
            AppLog.d(TAG, "Camera already initialized, skipping");
            return;
        }

        // 检查 Holder 中是否已有后台初始化的实例
        com.kooo.evcam.camera.CameraManagerHolder holder = com.kooo.evcam.camera.CameraManagerHolder.getInstance();
        MultiCameraManager existingManager = holder.getCameraManager();
        if (existingManager != null && existingManager.isReleased()) {
            AppLog.w(TAG, "Holder 中的 CameraManager 已被 release，丢弃");
            holder.setCameraManager(null);
            existingManager = null;
        }
        // 后台那份可能是按别的车型建的（切换车型只提示重启，但前台服务让进程一直活着，
        // 单例连同旧的摄像头映射就留下来了）。路数对不上就别复用，否则画面会全黑。
        if (existingManager != null && existingManager.getCameraCount() != configuredCameraCount) {
            AppLog.w(TAG, "后台摄像头映射为 " + existingManager.getCameraCount()
                    + " 路，当前车型需要 " + configuredCameraCount + " 路，丢弃重建");
            existingManager.release();
            holder.setCameraManager(null);
            existingManager = null;
        }
        if (existingManager != null) {
            // 后台已初始化，复用实例并绑定 TextureView
            AppLog.d(TAG, "复用后台已初始化的摄像头管理器，绑定 TextureView");
            cameraManager = existingManager;
            attachRecordingCoordinator();

            // 后台建的那份没有主界面的回调，在这里补上
            wireCameraCallbacks();

            // 绑定 TextureView
            cameraManager.updatePreviewTextureViews(textureFront, textureBack, textureLeft, textureRight);

            // 布局是新建的，座舱两格默认都显示。管线里只有真正分到了相机的那一路才有实例，
            // 所以问它就等于新建路径当时的结论 —— 以前这一步只在新建路径里做，
            // 界面一重建，没开的「前座舱」就成了一块空格子
            showCabinPanes(cameraManager.getCamera("back") != null,
                    cameraManager.getCamera("left") != null);

            // 打开所有摄像头（后台初始化时仅创建了对象，可能只打开了补盲所需的单个摄像头）
            // 主界面需要所有摄像头画面，已打开的摄像头会被 openCamera 内部的防重复检查跳过
            cameraManager.openAllCameras();

            // 手动触发 previewSizeCallback（摄像头可能已在补盲阶段打开并确定了预览尺寸）
            cameraManager.firePreviewSizeCallbacks();

            // 初始化亮度/降噪调节管理器
            imageAdjustManager = new ImageAdjustManager(this);
            registerCamerasToImageAdjustManager();
            AppLog.d(TAG, "Camera initialized with " + configuredCameraCount + " cameras (reused from background)");
            // 必须在下面几个检查之前：管线还在录的话，界面的标记要先对上，
            // 否则「重建后恢复录制」会以为没在录，又去开一次
            syncRecordingStateFromManager();
            checkResumeRecordingAfterRecreate();
            checkAutoStartRecording();
            startAutoRecordingCheck();
            return;
        }

        cameraManager = new MultiCameraManager(this);
        attachRecordingCoordinator();
        cameraManager.setMaxOpenCameras(configuredCameraCount);
        // 注册到全局 Holder
        holder.setCameraManager(cameraManager);
        
        // 初始化亮度/降噪调节管理器
        imageAdjustManager = new ImageAdjustManager(this);

        wireCameraCallbacks();

        // 等待TextureView准备好
        textureFront.post(() -> {
            try {
                // 检测可用的摄像头
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String[] cameraIds = cm.getCameraIdList();

                // 相机清单不在这里打了：诊断信息页采的是同样的东西而且不截断，
                // 极氪这一路 ZeekrCameraLocator 还会把候选尺寸和选用结果完整打一遍。
                // 这里每次初始化都遍历一遍 getCameraCharacteristics，只是白等。
                AppLog.d(TAG, "可用相机 " + cameraIds.length + " 个");

                initCamerasForConfiguredModel(cm, cameraIds);
                
                // 根据设置决定录制模式（支持用户手动选择）
                boolean useCodecRecording = appConfig.shouldUseCodecRecording();
                cameraManager.setCodecRecordingMode(useCodecRecording);
                String recordingMode = appConfig.getRecordingMode();
                String modeDesc = useCodecRecording ? "MediaCodec" : "MediaRecorder";
                AppLog.d(TAG, "录制模式: " + modeDesc + " (设置: " + recordingMode + ")");

                // 打开所有摄像头
                cameraManager.openAllCameras();
                
                // 注册摄像头到亮度/降噪调节管理器
                registerCamerasToImageAdjustManager();

                AppLog.d(TAG, "Camera initialized with " + configuredCameraCount + " cameras");
                //Toast.makeText(this, "已打开 " + configuredCameraCount + " 个摄像头", Toast.LENGTH_SHORT).show();
                
                // 检查是否需要恢复录制（主题切换后），优先级高于自动录制
                checkResumeRecordingAfterRecreate();
                
                // 检查并触发自动录制（延迟执行，确保摄像头准备就绪）
                checkAutoStartRecording();
                
                // 启动自动录制定时检查（如果启用了自动录制）
                startAutoRecordingCheck();

            } catch (CameraAccessException e) {
                AppLog.e(TAG, "Failed to access camera", e);
                Toast.makeText(this, getString(R.string.msg_camera_access_failed, e.getMessage()),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 按当前车型建立摄像头映射。
     *
     * <p>只剩三种：{@code getCarModel()} 读出来就已经 sanitize 过，
     * 存的值不在设置里列的三项之内会被拨回 zeekr_7x —— 所以银河 E5/L7、
     * 星舰7、手机模式那几个分支是<b>永远走不到</b>的，连同它们各自的
     * 映射方法一起删掉了。真要再支持别的车型，得先让它能被选出来。</p>
     */
    private void initCamerasForConfiguredModel(CameraManager cm, String[] cameraIds) {
        // 走哪条路由配置决定，不再看车型这个字符串。两个内置预设的区别就是
        // 「配置里有几路相机」——这本来就是它们唯一的实质区别。
        activeProfile = new ProfileStore(this).current();
        applyProfileCells();
        AppLog.i(TAG, "本次相机初始化使用的配置:\n" + activeProfile);

        if (Profile.PRESET_CUSTOM.equals(activeProfile.id)) {
            // 自定义的相机映射还是另一套数据，第 4 步才收编
            initCamerasForCustomModel(cameraIds);
            return;
        }
        int enabled = 0;
        for (CameraProfile camera : activeProfile.cameras) {
            if (camera.enabled) {
                enabled++;
            }
        }
        // 和 setupLayout 用同一个判断：布局摆了几个窗口，就接几路
        if (enabled > 1) {
            initCamerasForZeekrMulti(cm, cameraIds);
            return;
        }
        initCamerasForZeekrComposite(cm, cameraIds);
    }

    /**
     * 这次相机初始化用的那份配置。
     *
     * <p>取一次存着，别在初始化过程中反复去读 —— 中途换了值，前半截和后半截
     * 按不同的配置接线，出来的东西不属于任何一份配置。</p>
     */
    private Profile activeProfile;

    /**
     * 把配置里每一格的摆法交给四宫格容器。
     *
     * <h3>为什么要有这一步</h3>
     *
     * <p>位置、大小、旋转、镜像、裁切、缩放平移这些一直存在配置里，
     * 但<b>没有任何人读过它们</b> —— 容器画的是写死的 2×2 等分。
     * 于是编辑器里转 90°、开镜像，存下去了，画面纹丝不动。</p>
     *
     * <p>翻译放在这里而不是容器里：容器管「怎么画」，不该反过来认识配置的字段。</p>
     */
    private void applyProfileCells() {
        if (compositeContainer == null) {
            return;
        }
        if (activeProfile == null) {
            // 布局先于相机初始化建好，这时还没取过配置
            activeProfile = new ProfileStore(this).current();
        }
        CameraProfile composite = activeProfile.camera(CameraProfile.ROLE_COMPOSITE);
        if (composite == null || composite.lanes.isEmpty()) {
            compositeContainer.setCells(null);
            return;
        }
        com.kooo.evcam.zeekr.FourLaneContainer.Cell[] cells =
                new com.kooo.evcam.zeekr.FourLaneContainer.Cell[composite.lanes.size()];
        for (int i = 0; i < cells.length; i++) {
            com.kooo.evcam.profile.LaneLayout lane = composite.lanes.get(i);
            com.kooo.evcam.zeekr.FourLaneContainer.Cell cell =
                    new com.kooo.evcam.zeekr.FourLaneContainer.Cell();
            cell.laneIndex = lane.laneIndex;
            cell.x = lane.x;
            cell.y = lane.y;
            cell.width = lane.width;
            cell.height = lane.height;
            cell.rotation = lane.rotation;
            cell.mirrored = lane.mirrored;
            cell.cropTop = lane.cropTop;
            cell.cropBottom = lane.cropBottom;
            cell.cropLeft = lane.cropLeft;
            cell.cropRight = lane.cropRight;
            cell.scaleX = lane.scaleX;
            cell.scaleY = lane.scaleY;
            cell.translateX = lane.translateX;
            cell.translateY = lane.translateY;
            cell.fit = scaleModeOf(lane.fit);
            cells[i] = cell;
        }
        compositeContainer.setCells(cells);
        positionLaneLabels(cells);
        AppLog.i(TAG, "四宫格摆位按配置生效，共 " + cells.length + " 格");
    }


    /**
     * 座舱那两格显不显示。
     *
     * <p>配置里没有的那一路，整格藏起来 —— 一块黑方块下面写着「前座舱」，比空着更像出了故障。
     * 藏起来之后剩下那一格自己占满这一列。只开环视的布局里没有这两格，调了也不会有事。</p>
     *
     * <p>新建管线和复用管线两条路都要走这里：布局每次重建，两格都会回到默认的显示状态。</p>
     */
    private void showCabinPanes(boolean front, boolean rear) {
        showPane(R.id.pane_cabin_front, front);
        showPane(R.id.pane_cabin_rear, rear);
    }

    /** 某一格要不要出现在版面里。GONE 而不是 INVISIBLE：要把地方让出来。 */
    private void showPane(int id, boolean show) {
        View pane = findViewById(id);
        if (pane != null) {
            pane.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /** 配置里存的是三个词，容器认的是枚举。 */
    private static com.kooo.evcam.zeekr.FourLaneContainer.ScaleMode scaleModeOf(String fit) {
        if (com.kooo.evcam.profile.LaneLayout.FILL.equals(fit)) {
            return com.kooo.evcam.zeekr.FourLaneContainer.ScaleMode.FILL;
        }
        return com.kooo.evcam.zeekr.FourLaneContainer.ScaleMode.FIT;
    }

    /**
     * 四个角标跟着各自那一格走。
     *
     * <h3>为什么不能留在布局里</h3>
     *
     * <p>布局里这四个角标是用 {@code layout_gravity} 钉在容器四角的 —— 那是「一定是
     * 2×2 等分」时才成立的假设。格子现在可以挪、可以改大小，钉死的角标就会
     * 落在别人的画面上，或者干脆落在没有画面的地方。</p>
     *
     * <p>规则统一成<b>各自那一格的左上角</b>：换成任何摆法都说得通，
     * 而「哪个角离容器的哪个角近」在格子能任意摆之后根本没有答案。</p>
     *
     * <p>例外有两个，都因为菜单键浮在画面左上角：三路布局里「环视」是整块的角标，
     * 固定在这一块的右上角，不跟着格子走；四宫格里落在菜单键底下的那一格角标往右让开。</p>
     */
    private void positionLaneLabels(com.kooo.evcam.zeekr.FourLaneContainer.Cell[] cells) {
        TextView[] labels = {
                findViewById(R.id.label_front),
                findViewById(R.id.label_back),
                findViewById(R.id.label_left),
                findViewById(R.id.label_right),
        };
        // 容器的尺寸要等布局量完才知道
        compositeContainer.post(() -> {
            int width = compositeContainer.getWidth();
            int height = compositeContainer.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            int inset = (int) (8 * getResources().getDisplayMetrics().density);
            boolean paneLabel = findViewById(R.id.cabin_column) != null;
            int menuRight = 0;
            int menuBottom = 0;
            View menu = findViewById(R.id.btn_menu);
            if (menu != null && menu.getVisibility() == View.VISIBLE) {
                int[] menuAt = new int[2];
                int[] containerAt = new int[2];
                menu.getLocationOnScreen(menuAt);
                compositeContainer.getLocationOnScreen(containerAt);
                menuRight = menuAt[0] - containerAt[0] + menu.getWidth();
                menuBottom = menuAt[1] - containerAt[1] + menu.getHeight();
            }
            for (com.kooo.evcam.zeekr.FourLaneContainer.Cell cell : cells) {
                if (cell == null || cell.laneIndex < 0 || cell.laneIndex >= labels.length) {
                    continue;
                }
                TextView label = labels[cell.laneIndex];
                // 多路布局里 label_back / label_left 是座舱那两路的角标，
                // 不在四宫格上，不能跟着格子挪
                if (label == null || label.getParent() != compositeContainer.getParent()
                        || !(label.getLayoutParams()
                        instanceof android.widget.FrameLayout.LayoutParams)) {
                    continue;
                }
                // 三路布局里的「环视」：位置在布局里定好了（这一块的右上角）
                if (paneLabel && cell.laneIndex == 0) {
                    continue;
                }
                int start = (int) (cell.x * width) + inset;
                int top = (int) (cell.y * height) + inset;
                if (top < menuBottom && start < menuRight + inset) {
                    start = menuRight + inset;
                }
                android.widget.FrameLayout.LayoutParams params =
                        (android.widget.FrameLayout.LayoutParams) label.getLayoutParams();
                params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                params.setMarginStart(start);
                params.topMargin = top;
                params.setMarginEnd(0);
                params.bottomMargin = 0;
                label.setLayoutParams(params);
            }
        });
    }

    /**
     * 某一路在配置里要用的预览尺寸。
     *
     * @param probed 这一路探测到的尺寸；没有传 null
     * @return null 表示不指定，交给 {@code chooseOptimalSize} 按老规则挑
     */
    private android.util.Size profileSize(String role, android.util.Size probed) {
        if (activeProfile == null) {
            return probed;
        }
        CameraProfile camera = activeProfile.camera(role);
        if (camera == null) {
            return probed;
        }
        int[] probedPair = probed == null
                ? null : new int[]{probed.getWidth(), probed.getHeight()};
        // 「最大」也要能解出来：不给声明的最大尺寸的话，选了 max 等于没选 ——
        // 解成「不指定」，相机就按老规则挑一个 1280x800 附近的
        ProfileResolution.Size size = ProfileResolution.resolve(camera.preview.resolution,
                probedPair, com.kooo.evcam.profile.ProfileSizes.declaredMax(this, role));
        return size.specified() ? new android.util.Size(size.width, size.height) : null;
    }

    /**
     * 把主界面要的那 8 个回调挂到 {@link #cameraManager} 上。
     *
     * <p>复用后台实例和新建实例两条路都要挂，而且挂的必须<b>一模一样</b> ——
     * 原来是各抄了一份，两份已经开始漂了（一份多个注释、一份带着尾随空格）。
     * 漂到功能上就是「同样的操作，冷启动和从后台回来表现不一致」，
     * 这种问题查起来最费劲，因为两条路看着都对。</p>
     *
     * <p>后台（BlindSpotService）建的那份没有主界面的回调，
     * 不补上的话左右摄像头的旋转变换、录制计时都不正常。</p>
     */
    private void wireCameraCallbacks() {
        // 设置摄像头状态回调
        cameraManager.setStatusCallback((cameraId, status) -> {
            AppLog.d(TAG, "摄像头 " + cameraId + ": " + status);

            if (MultiCameraManager.STATUS_PREVIEW_STARTED.equals(status)) {
                refreshCompositeSizeOverlay();
            }
            // 以前这里还按状态文字弹「被占用」「重连失败」两个提示。状态早就是中文了，
            // 它找的 ERROR_CAMERA_IN_USE / DISCONNECTED / max reconnect attempts
            // 一次都没匹配上过。断开和被占用本来就会自动重连，而重连从不放弃，
            // 没有「失败」可报 —— 真接上反而是每次短暂断开都弹一次。两个提示一起删了。
        });

        // 设置分段切换回调
        cameraManager.setSegmentSwitchCallback(newSegmentIndex -> {
            onSegmentSwitch(newSegmentIndex);
        });

        // U 盘满了录不下去：由界面来停，按钮、计时器、前台服务一起回到待机
        cameraManager.setStorageFullCallback(capless -> runOnUiThread(() -> {
            quietStop = true;
            try {
                stopRecording();
            } finally {
                quietStop = false;
            }
            Toast.makeText(this, capless ? R.string.msg_storage_full_stopped
                    : R.string.msg_storage_cannot_free, Toast.LENGTH_LONG).show();
        }));

        // 设置损坏文件删除回调
        cameraManager.setCorruptedFilesCallback(deletedFiles -> {
            showCorruptedFilesDeletedDialog(deletedFiles);
        });

        // 设置 Codec 回退通知回调
        cameraManager.setCodecFallbackCallback(() -> {
            runOnUiThread(() -> {
                Toast.makeText(this, 
                    getString(R.string.msg_codec_fallback),
                    Toast.LENGTH_LONG).show();
            });
        });

        // 设置录制时间戳更新回调
        // 当 Watchdog 触发重建录制时，时间戳会改变，需要更新以便正确查找视频文件
        cameraManager.setTimestampUpdateCallback(newTimestamp -> {
            if (isRemoteRecording && remoteRecordingTimestamp != null) {
                AppLog.d(TAG, "远程录制时间戳更新: " + remoteRecordingTimestamp + " -> " + newTimestamp);
                remoteRecordingTimestamp = newTimestamp;
            }
});

        // 设置录制状态回调（监听录制成功或失败）
        cameraManager.setRecordingStatusCallback((activeCameras, failedCameras) -> {
            AppLog.d(TAG, "录制状态回调: 成功=" + activeCameras.size() + ", 失败=" + failedCameras.size());
            if (activeCameras.isEmpty()) {
                // 所有摄像头都启动失败
                runOnUiThread(() -> {
                    AppLog.e(TAG, "所有摄像头启动录制失败");
                    preparingHandler.removeCallbacks(preparingWatchdog);
                    isRecording = false;
                    isAutoRecordingPending = false;
                    isPreparingRecording = false;
                    hidePreparingIndicator();
                    Toast.makeText(this, R.string.msg_record_start_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });

        // 设置首次数据写入回调
        // 用于在摄像头真正开始输出数据后启动计时器（分段计时、钉钉录制计时等）
        cameraManager.setFirstDataWrittenCallback(() -> {
            AppLog.d(TAG, "收到首次数据写入回调，录制已真正开始");
            runOnUiThread(() -> {
                // 结束"准备中"状态
                preparingHandler.removeCallbacks(preparingWatchdog);
                preparingTimeouts = 0;
                if (isPreparingRecording) {
                    isPreparingRecording = false;
                    hidePreparingIndicator();
                    AppLog.d(TAG, "准备状态结束，录制进入正常状态");
                }

                // 启动录制计时器（从首次写入开始计时，而不是从录制请求开始）
                // 这样右上角显示的时间是"有效录制时长"
                if (isRecording && !isRemoteRecording) {
                    // 检查是否是主题切换后恢复的录制
                    if (shouldResumeRecordingAfterRecreate && savedRecordingStartTime > 0) {
                        // 使用保存的时间恢复计时器（计时不重置）
                        startRecordingTimer(savedRecordingStartTime, savedSegmentCount);
                        AppLog.d(TAG, "主题切换后恢复录制计时器（首次写入后）");
                        // 重置恢复标志
                        shouldResumeRecordingAfterRecreate = false;
                        savedRecordingStartTime = 0;
                        savedSegmentCount = 1;
                    } else {
                        startRecordingTimer();
                        AppLog.d(TAG, "手动录制计时器已启动（首次写入后）");
                    }
                }

// 兼容旧逻辑：如果是远程录制，现在才启动定时器
                if (isRemoteRecording && pendingRemoteDurationSeconds > 0) {
                    AppLog.d(TAG, "远程录制首次写入成功，启动 " + pendingRemoteDurationSeconds + " 秒定时器");
                    autoStopHandler.postDelayed(autoStopRunnable, pendingRemoteDurationSeconds * 1000L);
                    pendingRemoteDurationSeconds = 0;  // 重置
                }
            });
        });

        // 设置预览尺寸回调
        cameraManager.setPreviewSizeCallback((cameraKey, cameraId, previewSize) -> {
            AppLog.d(TAG, "摄像头 " + cameraId + " 预览尺寸: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            runOnUiThread(() -> {
                final AutoFitTextureView textureView;
                switch (cameraKey) {
                    case "front": textureView = textureFront; break;
                    case "back":  textureView = textureBack;  break;
                    case "left":  textureView = textureLeft;  break;
                    case "right": textureView = textureRight; break;
                    default:      textureView = null;         break;
                }
                if (textureView != null) {
                    applyPreviewSizeTransform(cameraKey, textureView, previewSize);
                }
            });
        });
    }

    /**
     * 极氪7X：车机只提供一路四联合成流。
     *
     * <p>不写死摄像头下标，而是遍历所有相机、挑出真正声明了合成流尺寸的那一路。
     * 找到后把分辨率写进配置，让 SingleCamera.chooseOptimalSize 精确命中它。</p>
     */
    private void initCamerasForZeekrComposite(CameraManager cm, String[] cameraIds) {
        com.kooo.evcam.zeekr.ZeekrCameraLocator.Result located =
                com.kooo.evcam.zeekr.ZeekrCameraLocator.locate(cm);
        AppLog.i(TAG, "极氪合成流探测结果:\n" + located.diagnostics);

        String cameraId;
        if (located.found()) {
            cameraId = located.cameraId;
            if (compositeContainer != null) {
                compositeContainer.setSourceSize(located.size);
            }
        } else {
            // 没找到就退回第一个相机，界面上会显示原始画面并提示不支持
            cameraId = cameraIds.length > 0 ? cameraIds[0] : null;
            AppLog.w(TAG, "未找到合成流，退回相机 " + cameraId);
            runOnUiThread(() -> Toast.makeText(this,
                    R.string.zeekr_composite_not_found, Toast.LENGTH_LONG).show());
        }

        // 角标先写探测结果；相机真正开起来之后会用实际尺寸再刷一次
        // （见 refreshCompositeSizeOverlay）—— 强制换了尺寸而 HAL 不认时，
        // 这里写探测值就成了「界面一个数、实际另一个数」。
        compositeProbeInfo = located.diagnostics;
        compositeProbeSize = located.size;
        updateCompositeInfoOverlay(compositeSummary(located.cameraId, located.size));

        // 手动指定优先于自动探测
        String overrideFront = appConfig.getCameraOverride("front");
        if (overrideFront != null) {
            AppLog.i(TAG, "环视使用手动指定的相机 " + overrideFront + "（自动探测结果为 " + cameraId + "）");
            cameraId = overrideFront;
        }

        if (cameraId != null) {
            cameraManager.initCameras(cameraId, textureFront, null, null, null, null, null, null);
            // 只对这一路指定合成流尺寸，不去改全局「画质设置」
            if (located.found()) {
                com.kooo.evcam.camera.SingleCamera cam = cameraManager.getCamera("front");
                if (cam != null) {
                    // 尺寸来自配置。配置里是 auto 时就是探测结果，
                    // 是具体值时（开发者选项改过的）就用那个值。
                    android.util.Size use = profileSize(
                            CameraProfile.ROLE_COMPOSITE, located.size);
                    if (use != null && !use.equals(located.size)) {
                        AppLog.w(TAG, "环视流尺寸按配置定为 " + use
                                + "（探测结果是 " + located.size + "）");
                        if (compositeContainer != null) {
                            compositeContainer.setSourceSize(use);
                        }
                    }
                    if (use != null) {
                        cam.setPreferredSize(use);
                    }
                }
            }
        }
    }

    /**
     * 相机开起来之后，用它<b>实际</b>在用的尺寸刷新角标。
     *
     * <p>强制指定的尺寸如果 HAL 没声明，{@code SingleCamera} 会退回全局配置 ——
     * 那时候界面上再写着强制值就是假的。</p>
     */
    private void refreshCompositeSizeOverlay() {
        if (cameraManager == null || compositeProbeInfo == null) {
            return;
        }
        com.kooo.evcam.camera.SingleCamera cam = cameraManager.getCamera("front");
        if (cam == null || cam.getPreviewSize() == null) {
            return;
        }
        android.util.Size actual = cam.getPreviewSize();
        // 尺寸已经不是探测出来的那个了（按配置改过），就把探测值也写上 ——
        // 排查时一眼看得出「尺寸是配置定的」
        String summary = compositeSummary(cam.getCameraId(), actual);
        if (compositeProbeSize != null && !actual.equals(compositeProbeSize)) {
            summary += " · " + getString(R.string.composite_probed,
                    compositeProbeSize.getWidth(), compositeProbeSize.getHeight());
        }
        final String line = summary;
        runOnUiThread(() -> {
            updateCompositeInfoOverlay(line);
            if (compositeContainer != null) {
                // 拆分几何按实际尺寸重排，否则四宫格会照着一个没在用的尺寸切
                compositeContainer.setSourceSize(actual);
            }
        });
    }

    /**
     * 「环视 + 座舱 3 路」模式的相机初始化。
     *
     * <p>槽位分配交给 {@link com.kooo.evcam.zeekr.ZeekrMultiPlan}（纯逻辑、有单元测试），
     * 这里只负责套用与显示。</p>
     *
     * <p><b>环视这一路固定用探测到的合成流尺寸</b>，与单路配置一致。</p>
     *
     * <p>0.6.0 曾以「强制 1280×5140 撑爆了 HAL 并发预算」为由去掉了这个固定，
     * 那个判断是错的 —— 0.7.0 找到真正的原因（{@code configuredCameraCount >= 4}
     * 那道判断让 initCamera 根本没被调用），固定与否都不影响三路能不能出画面。
     * 而不固定会留下一个真实的坑：环视相机会退回去用全局「目标分辨率」，
     * 可相机 2 压根没声明 1280×800，最近匹配会落到 1280×720 ——
     * <b>四联合成的内容就此丢失，画面变成一张普通的小图</b>。
     * 也就是说三路能正常工作，只是因为用户手动把画质里的目标分辨率设成了
     * 1280×5140，这不该是它的依赖。</p>
     *
     * <p>合成流的尺寸是硬件事实、不是偏好：四联内容只在 1280×5140 下存在。
     * 固定它之后，「目标分辨率」才回到它应有的含义 —— 那是给普通相机（座舱两路）
     * 用的设置，在那里选择才是真的有意义。</p>
     */
    private void initCamerasForZeekrMulti(CameraManager cm, String[] cameraIds) {
        com.kooo.evcam.zeekr.ZeekrCameraLocator.Result located =
                com.kooo.evcam.zeekr.ZeekrCameraLocator.locate(cm);
        AppLog.i(TAG, "极氪多路探测结果:\n" + located.diagnostics);

        // 三路模式下以计划为准：顶替上来的普通相机不是合成流，不能按四格拆
        com.kooo.evcam.zeekr.ZeekrMultiPlan plan = com.kooo.evcam.zeekr.ZeekrMultiPlan.build(
                cameraIds,
                located.found() ? located.cameraId : null,
                appConfig.getCameraOverride("front"),
                appConfig.getCameraOverride("back"),
                appConfig.getCameraOverride("left"));

        AppLog.i(TAG, "极氪多路槽位分配:\n" + plan.explanation + "-> " + plan);

        if (plan.assignedCount() == 0) {
            AppLog.w(TAG, "没有任何可用相机，三路模式无法显示");
            runOnUiThread(() -> Toast.makeText(this,
                    R.string.zeekr_composite_not_found, Toast.LENGTH_LONG).show());
        }

        // 合成流的几何先按探测结果给上；若最终协商到的不是条带尺寸，
        // onPreviewSizeChosen 那一路会保持原样显示（容器会忽略非条带尺寸）
        if (plan.compositeIsReal && compositeContainer != null) {
            compositeContainer.setSourceSize(located.size);
        }

        // 座舱那两路按<b>配置里有哪一路</b>分槽位，不按相机 id 的先后。
        //
        // ZeekrMultiPlan 只认相机 id 的顺序：除去合成流之后，第一个填座舱 1、
        // 第二个填座舱 2。于是在配置里只加了「后座舱」的人，画面会出现在前座舱
        // 那一格里 —— 报上来的现象是「无论加哪一路，先出来的总是前座」。
        // 角色和相机是<b>固定</b>对应的：除去合成流之后，第一路是前座舱、第二路是
        // 后座舱（和 ProfileSizes.cameraIdFor 同一条规则）。只加了后座舱时，
        // 前座舱那一路就空着 —— 不能拿它去填后座舱的位置。
        //
        // 上一版这里写的是「只加了后座舱就把第一路顶上，别让槽位空着」。结果是
        // 格子对、名字对、画面是前座舱的 —— 比空着更难发现。
        boolean wantsFront = laneFor("back") != null;
        boolean wantsRear = laneFor("left") != null;
        String cabinFrontId = wantsFront ? plan.cabin1Id : null;
        String cabinRearId = wantsRear ? plan.cabin2Id : null;
        AppLog.i(TAG, "座舱槽位按配置分: 前座舱=" + cabinFrontId + "，后座舱=" + cabinRearId
                + "（配置里 前=" + wantsFront + " 后=" + wantsRear + "）");

        showCabinPanes(cabinFrontId != null, cabinRearId != null);

        cameraManager.initCameras(
                plan.compositeId, textureFront,
                cabinFrontId, textureBack,
                cabinRearId, textureLeft,
                null, null);

        // 只钉住环视这一路的尺寸，不动全局「画质设置」——
        // 座舱两路仍然按目标分辨率自己挑。
        if (plan.compositeIsReal && located.found()) {
            com.kooo.evcam.camera.SingleCamera cam = cameraManager.getCamera("front");
            android.util.Size use = profileSize(CameraProfile.ROLE_COMPOSITE, located.size);
            if (cam != null && use != null) {
                cam.setPreferredSize(use);
            }
        }

        // 座舱两路的尺寸也从配置来。配置里是 auto 时不指定，
        // chooseOptimalSize 按它原来的规则挑 —— 和以前一模一样。
        pinCabinSize(CameraProfile.ROLE_CABIN_1, "back");
        pinCabinSize(CameraProfile.ROLE_CABIN_2, "left");

        com.kooo.evcam.zeekr.StreamLayoutTable.setCompositeCameraId(
                plan.compositeIsReal ? plan.compositeId : null);

        updateCompositeInfoOverlay(describeMultiSlots(plan));
    }

    private void pinCabinSize(String role, String cameraKey) {
        android.util.Size use = profileSize(role, null);
        if (use == null) {
            return;
        }
        com.kooo.evcam.camera.SingleCamera cam = cameraManager.getCamera(cameraKey);
        if (cam != null) {
            cam.setPreferredSize(use);
            AppLog.i(TAG, role + " 尺寸按配置定为 " + use);
        }
    }

    /** 三路模式下把槽位分配显示在画面上——黑屏时这是最直接的线索。 */
    private String describeMultiSlots(com.kooo.evcam.zeekr.ZeekrMultiPlan plan) {
        return getString(R.string.slot_surround) + " " + plan.compositeId
                + " (" + getString(plan.compositeIsReal
                        ? R.string.composite_kind_real : R.string.composite_kind_plain) + ")"
                + " · " + getString(R.string.slot_cabin_front) + " " + plan.cabin1Id
                + " · " + getString(R.string.slot_cabin_rear) + " " + plan.cabin2Id;
    }

    /**
     * 状态条上那一行：环视流多大、拆不拆。
     *
     * <p>以前这里直接摆探测器的诊断文字（中文、带内部术语），英文界面下就是一行中文，
     * 中文界面下也只有排查的人读得懂。细节照样进日志和诊断报告，这里只说人看得懂的两件事。</p>
     */
    private String compositeSummary(String cameraId, android.util.Size size) {
        if (size == null) {
            return "";
        }
        boolean split = com.kooo.evcam.zeekr.CompositeStreamGeometry.looksLikeComposite(
                cameraId, size.getWidth(), size.getHeight());
        return getString(R.string.composite_summary, size.getWidth(), size.getHeight(),
                getString(split ? R.string.composite_split : R.string.composite_whole));
    }

    /**
     * 自定义车型：使用用户配置的摄像头映射
     */
    private void initCamerasForCustomModel(String[] cameraIds) {
        // 获取用户配置的摄像头ID
        String frontId = appConfig.getCameraId("front");
        String backId = appConfig.getCameraId("back");
        String leftId = appConfig.getCameraId("left");
        String rightId = appConfig.getCameraId("right");
        
        AppLog.d(TAG, "自定义车型配置 - 摄像头数量: " + configuredCameraCount);
        AppLog.d(TAG, "  前: " + frontId + ", 后: " + backId + ", 左: " + leftId + ", 右: " + rightId);
        
        switch (configuredCameraCount) {
            case 1:
                // 1摄像头模式
                if (textureFront != null) {
                    cameraManager.initCameras(
                            frontId, textureFront,
                            null, null,
                            null, null,
                            null, null
                    );
                }
                break;
            case 2:
                // 2摄像头模式
                if (textureFront != null && textureBack != null) {
                    cameraManager.initCameras(
                            frontId, textureFront,
                            backId, textureBack,
                            null, null,
                            null, null
                    );
                }
                break;
            default:
                // 4摄像头模式
                if (textureFront != null && textureBack != null && textureLeft != null && textureRight != null) {
                    cameraManager.initCameras(
                            frontId, textureFront,
                            backId, textureBack,
                            leftId, textureLeft,
                            rightId, textureRight
                    );

                    // 设置自定义旋转角度（仅用于自定义车型）
                    setCustomRotationForCameras();
                }
                break;
        }
    }

    /**
     * 为自定义车型的摄像头设置旋转角度
     * 注意：自定义布局默认不旋转、不镜像，所有调节在自由调节界面进行
     */
    private void setCustomRotationForCameras() {
        if (!appConfig.needsCustomLayoutManager()) {
            return;  // 只对自定义车型/多视角应用
        }

        // 自定义布局：默认不应用任何旋转，保持原始状态
        // 所有旋转、镜像等调节都在自由调节界面进行
        AppLog.d(TAG, "自定义车型：保持摄像头原始状态，不应用自动旋转");
        
        // 明确设置所有摄像头旋转为0
        if (cameraManager != null) {
            SingleCamera frontCamera = cameraManager.getCamera("front");
            SingleCamera backCamera = cameraManager.getCamera("back");
            SingleCamera leftCamera = cameraManager.getCamera("left");
            SingleCamera rightCamera = cameraManager.getCamera("right");

            if (frontCamera != null) frontCamera.setCustomRotation(0);
            if (backCamera != null) backCamera.setCustomRotation(0);
            if (leftCamera != null) leftCamera.setCustomRotation(0);
            if (rightCamera != null) rightCamera.setCustomRotation(0);
        }
    }

    /**
     * 对 TextureView 应用旋转变换 (修正版 - 解决变形问题)
     * @param textureView 要旋转的 TextureView
     * @param previewSize 预览尺寸（原始的 1280x800）
     * @param rotation 旋转角度（90 或 270）
     * @param cameraKey 摄像头标识
     */
    /**
     * 根据车型和摄像头位置，对 TextureView 应用正确的宽高比和旋转变换。
     * 从 previewSizeCallback 提取，避免正常初始化和后台复用路径的代码重复。
     */
    /**
     * 这一路在当前配置里的那一格；不拆分的那几路只有一格。取不到返回 null。
     *
     * <p>「取得到」= 这一路的摆位由配置说了算，本界面不要再往它的 TextureView
     * 上写矩阵。判断只看配置，不看车型 —— 看车型是前三次修座舱旋转都栽的那个坑。</p>
     */
    private com.kooo.evcam.profile.LaneLayout laneFor(String cameraKey) {
        String role = com.kooo.evcam.profile.ProfileSizes.roleForCameraKey(cameraKey);
        if (role == null) {
            return null;
        }
        if (activeProfile == null) {
            activeProfile = new ProfileStore(this).current();
        }
        CameraProfile camera = activeProfile.camera(role);
        // 关着的那一路不算数。三路现在永远都在配置里（开关才是开关），
        // 只问「有没有这一路」的话，一份只开环视的配置也会把座舱那两路开起来
        if (camera == null || !camera.enabled || camera.lanes.isEmpty()) {
            return null;
        }
        return camera.lanes.get(0);
    }

    private void applyPreviewSizeTransform(String cameraKey, AutoFitTextureView textureView, android.util.Size previewSize) {
        String carModel = appConfig.getCarModel();

        // 极氪合成流：比例与排版由 FourLaneContainer 负责。
        //
        // 这一段必须排在下面「配置里有这一格」前面：环视在配置里也有格子（四格），
        // 排在后面就永远走不到这里。冷启动时 initCamerasForZeekrMulti 会另外把尺寸交给容器，
        // 所以一直没露馅；应用被重启、主界面复用后视镜在后台建好的相机时只剩这一条路，
        // 容器拿不到尺寸，环视就成了一整条被拉伸的画面（2026-09-15 的诊断报告）。
        //
        // 这里绝不能给 TextureView 设 1280:5140 这种长条宽高比或预览矩阵，
        // 否则子视图会被测量成细长条，四宫格就错位了。
        if (compositeContainer != null && textureView == textureFront) {
            // 只有看起来像合成流的尺寸才更新几何；HAL 有时会给一个压扁的小尺寸提示，
            // 那种尺寸会被容器忽略，已探测到的正确几何得以保留。
            compositeContainer.setSourceSize(previewSize);
            AppLog.d(TAG, "合成流预览尺寸: " + previewSize
                    + " -> " + compositeContainer.describePlan());
            updateCompositeInfoOverlay(compositeSummary(
                    com.kooo.evcam.zeekr.StreamLayoutTable.compositeCameraId(), previewSize));
            return;
        }

        // 配置里有这一路的那一格时，矩阵由 SingleCamera 一家说了算。
        //
        // 这里原来按车型分了好几个分支，每一条最后都会往 TextureView 上写一次矩阵
        // （applyPreviewCorrectionOnly 写的是单位阵）。而车型这个键默认是 zeekr_7x，
        // 于是三路配置的座舱相机掉进了「E5 兜底」那一条，把按配置算好的摆位盖掉 ——
        // 座舱旋转连修三次都没生效，就是栽在这。判断不看车型，只看配置里有没有那一格。
        com.kooo.evcam.profile.LaneLayout ownLane = laneFor(cameraKey);
        if (ownLane != null) {
            // 视图的形状也归这一格管：
            //   适应 —— 视图取画面的形状，在格子里居中，四周留黑（原来的样子）
            //   填充 —— 视图<b>让出整格</b>，形状交给矩阵去补
            //
            // 只改矩阵是不够的：「适应」模式下 AutoFitTextureView 会先把视图缩成
            // 画面的形状，转 90° 之后画面再怎么铺也只能铺满那个已经缩过的视图 ——
            // 报上来的「选了填充还是顶不满」就是卡在这一层。
            // 主界面点开放大的那一路一律铺满（见 setupCompositeControls）
            boolean fill = cameraKey.equals(expandedCameraKey)
                    || com.kooo.evcam.profile.LaneLayout.FILL.equals(
                    com.kooo.evcam.profile.LaneLayout.normaliseFit(ownLane.fit));
            if (fill) {
                textureView.setAspectRatio(0, 0);
            } else {
                // 适应：视图取<b>转过之后</b>的形状。取转之前的话会留两层黑边 ——
                // 视图先在格子里缩一次（缩成画面的形状），矩阵再在视图里缩一次
                // （因为转过之后长宽对调了），长边就怎么都顶不到格子的边。
                textureView.setAspectRatioWithRotation(
                        previewSize.getWidth(), previewSize.getHeight(),
                        com.kooo.evcam.camera.LaneOrientation.normalise(ownLane.rotation));
            }
            textureView.setFillContainer(false);
            AppLog.d(TAG, "设置 " + cameraKey + (fill ? " 铺满整格" : " 宽高比 "
                    + previewSize.getWidth() + ":" + previewSize.getHeight())
                    + "，摆位交给配置（SingleCamera）");
            return;
        }

        if (appConfig.needsCustomLayoutManager()) {
            textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
            textureView.setFillContainer(true);
            AppLog.d(TAG, "设置 " + cameraKey + " 宽高比(自定义-填充): " + previewSize.getWidth() + "x" + previewSize.getHeight());
            if (customLayoutManager != null) {
                customLayoutManager.updateCameraAspectRatio(cameraKey, previewSize.getWidth(), previewSize.getHeight(), 0);
            }
            applyPreviewCorrectionOnly(textureView, cameraKey);
        } else {
            // E5 等其他车型
            boolean needRotation = "left".equals(cameraKey) || "right".equals(cameraKey);
            if (needRotation) {
                textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
                AppLog.d(TAG, "设置 " + cameraKey + " 宽高比(E5旋转后): " + previewSize.getHeight() + ":" + previewSize.getWidth());
                int rotation = "left".equals(cameraKey) ? 270 : 90;
                applyRotationTransform(textureView, previewSize, rotation, cameraKey);
            } else {
                textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
                boolean useFillMode = configuredCameraCount >= 4;
                if (useFillMode) {
                    textureView.setFillContainer(true);
                    AppLog.d(TAG, "设置 " + cameraKey + " 宽高比: " + previewSize.getWidth() + ":" + previewSize.getHeight() + ", 填满模式");
                } else {
                    textureView.setFillContainer(false);
                    AppLog.d(TAG, "设置 " + cameraKey + " 宽高比: " + previewSize.getWidth() + ":" + previewSize.getHeight() + ", 适应模式");
                }
                applyPreviewCorrectionOnly(textureView, cameraKey);
            }
        }
    }

    private void applyRotationTransform(AutoFitTextureView textureView, android.util.Size previewSize,
                                        int rotation, String cameraKey) {
        // 延迟执行，确保 TextureView 已经完成布局
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();

            if (viewWidth == 0 || viewHeight == 0) {
                AppLog.d(TAG, cameraKey + " TextureView 尺寸为0，延迟应用旋转");
                // 如果视图还没有尺寸，再次延迟
                textureView.postDelayed(() -> applyRotationTransform(textureView, previewSize, rotation, cameraKey), 100);
                return;
            }

            android.graphics.Matrix matrix = new android.graphics.Matrix();
            android.graphics.RectF viewRect = new android.graphics.RectF(0, 0, viewWidth, viewHeight);
            
            // 缓冲区矩形，使用 float 精度
            android.graphics.RectF bufferRect = new android.graphics.RectF(0, 0, previewSize.getWidth(), previewSize.getHeight());

            float centerX = viewRect.centerX();
            float centerY = viewRect.centerY();

            if (rotation == 90 || rotation == 270) {
                // 1. 将 bufferRect 中心移动到 viewRect 中心
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                
                // 2. 将 buffer 映射到 view，这一步会处理拉伸校正
                matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL);
                
                // 3. 计算缩放比例以填满屏幕 (Center Crop)
                // 因为旋转了 90 度，所以 viewHeight 对应 previewWidth，viewWidth 对应 previewHeight
                float scale = Math.max(
                        (float) viewHeight / previewSize.getWidth(),
                        (float) viewWidth / previewSize.getHeight());
                
                // 4. 应用缩放
                matrix.postScale(scale, scale, centerX, centerY);
                
                // 5. 应用旋转
                matrix.postRotate(rotation, centerX, centerY);
            } else if (android.view.Surface.ROTATION_180 == rotation) {
                // 如果需要处理 180 度翻转
                matrix.postRotate(180, centerX, centerY);
            }

            // 保存基础变换，并叠加预览矫正
            previewBaseTransforms.put(cameraKey, new android.graphics.Matrix(matrix));
            PreviewCorrection.postApply(matrix, appConfig, cameraKey, viewWidth, viewHeight);

            textureView.setTransform(matrix);
            AppLog.d(TAG, cameraKey + " 应用修正旋转: " + rotation + "度");
        });
    }

    /**
     * 对没有基础变换的 TextureView 单独应用预览矫正
     * 用于 E5/L7 前后摄像头、自定义车型等不需要旋转的场景
     */
    private void applyPreviewCorrectionOnly(AutoFitTextureView textureView, String cameraKey) {
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth <= 0 || viewHeight <= 0) {
                textureView.postDelayed(() -> applyPreviewCorrectionOnly(textureView, cameraKey), 100);
                return;
            }
            android.graphics.Matrix matrix = new android.graphics.Matrix(); // identity
            previewBaseTransforms.put(cameraKey, new android.graphics.Matrix(matrix));
            PreviewCorrection.postApply(matrix, appConfig, cameraKey, viewWidth, viewHeight);
            textureView.setTransform(matrix);
        });
    }

    /**
     * 刷新所有预览 TextureView 的矫正变换
     * 由悬浮窗调参或设置页调用
     */
    public void refreshPreviewCorrection() {
        runOnUiThread(() -> {
            refreshSinglePreviewCorrection(textureFront, "front");
            refreshSinglePreviewCorrection(textureBack, "back");
            refreshSinglePreviewCorrection(textureLeft, "left");
            refreshSinglePreviewCorrection(textureRight, "right");
        });
    }

    private void refreshSinglePreviewCorrection(AutoFitTextureView textureView, String cameraKey) {
        if (textureView == null) return;
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth <= 0 || viewHeight <= 0) return;

            android.graphics.Matrix base = previewBaseTransforms.get(cameraKey);
            android.graphics.Matrix matrix;
            if (base != null) {
                matrix = new android.graphics.Matrix(base);
            } else {
                matrix = new android.graphics.Matrix(); // identity
            }
            PreviewCorrection.postApply(matrix, appConfig, cameraKey, viewWidth, viewHeight);
            textureView.setTransform(matrix);
        });
    }

    /**
     * 显示预览画面矫正悬浮窗
     */
    public void showPreviewCorrectionFloating() {
        if (previewCorrectionFloatingWindow != null && previewCorrectionFloatingWindow.isShowing()) {
            return;
        }
        previewCorrectionFloatingWindow = new PreviewCorrectionFloatingWindow(this);
        previewCorrectionFloatingWindow.show();
    }

    /**
     * 关闭预览画面矫正悬浮窗
     */
    public void dismissPreviewCorrectionFloating() {
        if (previewCorrectionFloatingWindow != null) {
            previewCorrectionFloatingWindow.dismiss();
            previewCorrectionFloatingWindow = null;
        }
    }

    // ==================== 调试信息覆盖层（连点5下显示） ====================

    /**
     * 在录制布局上检测连续5次点击，切换调试信息显示
     */
    private void initDebugOverlayTapDetection() {
        if (recordingLayout == null) return;
        recordingLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                long now = System.currentTimeMillis();
                if (now - debugLastTapTime > DEBUG_TAP_INTERVAL_MS) {
                    debugTapCount = 0;
                }
                debugTapCount++;
                debugLastTapTime = now;
                if (debugTapCount >= DEBUG_TAP_COUNT) {
                    debugTapCount = 0;
                    toggleDebugOverlay();
                }
            }
            return false; // 不消费事件，让其他点击/触摸正常工作
        });
    }

    private void toggleDebugOverlay() {
        if (tvDebugOverlay == null) {
            AppLog.w(TAG, "当前布局不含 tv_debug_overlay，跳过调试信息切换");
            return;
        }
        debugOverlayVisible = !debugOverlayVisible;
        if (debugOverlayVisible) {
            tvDebugOverlay.setVisibility(View.VISIBLE);
            startDebugUpdates();
            android.widget.Toast.makeText(this, R.string.debug_on, android.widget.Toast.LENGTH_SHORT).show();
        } else {
            tvDebugOverlay.setVisibility(View.GONE);
            stopDebugUpdates();
            android.widget.Toast.makeText(this, R.string.debug_off, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void startDebugUpdates() {
        stopDebugUpdates();
        debugUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!debugOverlayVisible) return;
                updateDebugInfo();
                debugUpdateHandler.postDelayed(this, 1000);
            }
        };
        debugUpdateHandler.post(debugUpdateRunnable);
    }

    private void stopDebugUpdates() {
        if (debugUpdateRunnable != null) {
            debugUpdateHandler.removeCallbacks(debugUpdateRunnable);
            debugUpdateRunnable = null;
        }
    }

    private void updateDebugInfo() {
        if (tvDebugOverlay == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("── Debug ──\n");

        // 摄像头 FPS 和分辨率
        if (cameraManager != null) {
            sb.append(cameraManager.getDebugStats());
        } else {
            sb.append("Camera: not initialized");
        }

        // 录制状态
        sb.append("\n\n");
        sb.append(getString(R.string.debug_recording,
                getString(isRecording ? R.string.debug_rec : R.string.debug_stopped)));
        if (isRecording) {
            sb.append("  ").append(getString(R.string.debug_mode, appConfig.getRecordingMode()));
        }

        // 内存使用
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long totalMB = rt.maxMemory() / (1024 * 1024);
        sb.append("\n").append(getString(R.string.debug_memory, usedMB, totalMB));

        // 车型
        sb.append("\n").append(getString(R.string.debug_model,
                appConfig.getCarModel(), appConfig.getCameraCount()));

        // 版本
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            sb.append("\n").append(getString(R.string.debug_version, versionName));
        } catch (Exception ignored) {}

        tvDebugOverlay.setText(sb.toString());
    }

    /**
     * 检查是否需要在主题切换后恢复录制
     * 在摄像头初始化完成后调用，如果之前正在录制（非钉钉指令），则自动恢复录制
     */
    private void checkResumeRecordingAfterRecreate() {
        if (!shouldResumeRecordingAfterRecreate) {
            return;
        }
        
        AppLog.d(TAG, "检测到需要恢复录制（主题切换后），将在2秒后自动恢复...");
        
        // 延迟2秒后恢复录制，确保所有摄像头都已准备就绪
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            // 再次检查是否已经在录制（可能用户手动开始了）
            if (isRecording) {
                AppLog.d(TAG, "已在录制中，跳过恢复录制");
                shouldResumeRecordingAfterRecreate = false;
                return;
            }
            
            // 检查摄像头是否就绪
            if (cameraManager == null || !cameraManager.hasConnectedCameras()) {
                AppLog.w(TAG, "摄像头未就绪，无法恢复录制");
                Toast.makeText(this, R.string.msg_resume_failed, Toast.LENGTH_SHORT).show();
                shouldResumeRecordingAfterRecreate = false;
                savedRecordingStartTime = 0;
                savedSegmentCount = 1;
                return;
            }
            
            AppLog.d(TAG, "主题切换后自动恢复录制...");
            startRecording();
            Toast.makeText(this, R.string.msg_recording_resumed, Toast.LENGTH_SHORT).show();
            // 注意：shouldResumeRecordingAfterRecreate 在首次数据写入回调中重置，
            // 以便计时器使用保存的时间
        }, 2000);  // 延迟2秒
    }
    
    /**
     * 检查并触发自动录制
     * 在摄像头初始化完成后调用，如果用户启用了"启动自动录制"则自动开始录制
     */
    private void checkAutoStartRecording() {
        // 如果正在恢复录制（主题切换后），跳过自动录制
        if (shouldResumeRecordingAfterRecreate) {
            AppLog.d(TAG, "正在恢复录制，跳过自动录制检查");
            return;
        }
        
        // 这一趟开过没有、用户停过没有，都记在进程级的 RecordingIntent 上 ——
        // 以前记在界面的字段里，界面一重建（切日夜模式、换语言、关掉再打开）就忘了，
        // 于是用户明明按过停止，重建之后又自己录上了
        com.kooo.evcam.recording.RecordingIntent intent =
                com.kooo.evcam.recording.RecordingIntent.current();
        if (!intent.shouldAutoStart(appConfig.isAutoStartRecording())) {
            AppLog.d(TAG, "不自动开始录制（" + intent.describe() + "，开关="
                    + appConfig.isAutoStartRecording() + "）");
            return;
        }
        intent.noteAutoStarted();
        isAutoRecordingPending = true;  // 标记自动录制正在等待中（防止 onPause 关闭摄像头）
        AppLog.d(TAG, "检测到启用了启动自动录制，将在2秒后自动开始录制...");
        
        // 延迟2秒后开始录制，确保所有摄像头都已准备就绪
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            // 自动录制等待结束
            isAutoRecordingPending = false;
            
            // 再次检查是否已经在录制（可能用户手动开始了）
            if (isRecording) {
                AppLog.d(TAG, "已在录制中，跳过自动录制");
                return;
            }
            
            // 检查摄像头是否就绪
            if (cameraManager == null || !cameraManager.hasConnectedCameras()) {
                AppLog.w(TAG, "摄像头未就绪，无法自动开始录制");
                Toast.makeText(this, R.string.msg_auto_record_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            
            AppLog.d(TAG, "自动开始录制...");
            startRecording();
            Toast.makeText(this, R.string.msg_recording_auto_started, Toast.LENGTH_SHORT).show();
        }, 2000);  // 延迟2秒
    }
    
    /**
     * 启动自动录制定时检查
     * 定期检查录制状态，如果启用了自动录制且不是手动停止的，则自动恢复录制
     */
    private void startAutoRecordingCheck() {
        // 检查是否启用了自动录制
        if (!appConfig.isAutoStartRecording()) {
            AppLog.d(TAG, "未启用自动录制，跳过定时检查");
            return;
        }
        
        // 初始化 Handler
        if (autoRecordingCheckHandler == null) {
            autoRecordingCheckHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        
        // 取消之前的检查任务
        if (autoRecordingCheckRunnable != null) {
            autoRecordingCheckHandler.removeCallbacks(autoRecordingCheckRunnable);
        }
        
        // 创建定时检查任务
        autoRecordingCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkAndRestoreAutoRecording();
                // 继续下一次检查
                if (autoRecordingCheckHandler != null && autoRecordingCheckRunnable != null) {
                    autoRecordingCheckHandler.postDelayed(this, AUTO_RECORDING_CHECK_INTERVAL_MS);
                }
            }
        };
        
        // 延迟首次检查（给自动录制启动一些时间）
        autoRecordingCheckHandler.postDelayed(autoRecordingCheckRunnable, AUTO_RECORDING_CHECK_INTERVAL_MS);
        AppLog.d(TAG, "自动录制定时检查已启动（每 " + (AUTO_RECORDING_CHECK_INTERVAL_MS / 1000) + " 秒检查一次）");
    }
    
    /**
     * 停止自动录制定时检查
     */
    private void stopAutoRecordingCheck() {
        if (autoRecordingCheckHandler != null && autoRecordingCheckRunnable != null) {
            autoRecordingCheckHandler.removeCallbacks(autoRecordingCheckRunnable);
            AppLog.d(TAG, "自动录制定时检查已停止");
        }
        autoRecordingCheckRunnable = null;
    }
    
    /**
     * 检查并恢复自动录制
     * 条件：启用了自动录制 + 不是手动停止 + 当前没在录制 + 摄像头已连接
     */
    private void checkAndRestoreAutoRecording() {
        // 开着功能、这一趟真的录起来过、而且不是用户自己停的 —— 三条缺一不可。
        // 中间那条是新加的：以前「从来没录起来过」也会被这里开起来，
        // 于是看完回放切回主界面、开机没插 U 盘，都会莫名其妙开始录制
        com.kooo.evcam.recording.RecordingIntent intent =
                com.kooo.evcam.recording.RecordingIntent.current();
        if (!intent.shouldRestore(appConfig.isAutoStartRecording())) {
            return;
        }
        
        // 如果已经在录制，不需要恢复
        if (isRecording) {
            return;
        }
        
        // 如果正在准备录制，不需要恢复
        if (isAutoRecordingPending || isPreparingRecording) {
            return;
        }
        
        // 检查摄像头是否就绪
        if (cameraManager == null || !cameraManager.hasConnectedCameras()) {
            AppLog.w(TAG, "自动录制检查：摄像头未就绪，跳过恢复");
            return;
        }
        
        // 满足所有条件，自动恢复录制
        intent.noteRestoreAttempt();
        AppLog.d(TAG, "自动录制检查：录制意外停了，接回去（第 "
                + intent.restoreAttempts() + " 次）");
        startRecording();
        Toast.makeText(this, R.string.msg_recording_resumed, Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 初始化息屏状态广播接收器
     * 用于检测屏幕开关状态，实现息屏录制功能
     */
    private void initScreenStateReceiver() {
        screenStateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        
        screenStateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                
                if (android.content.Intent.ACTION_SCREEN_OFF.equals(action)) {
                    onScreenOff();
                } else if (android.content.Intent.ACTION_SCREEN_ON.equals(action)) {
                    onScreenOn();
                }
            }
        };
        
        // 注册广播接收器
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(android.content.Intent.ACTION_SCREEN_OFF);
        filter.addAction(android.content.Intent.ACTION_SCREEN_ON);
        registerReceiver(screenStateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        
        AppLog.d(TAG, "息屏状态广播接收器已注册");
        
        // 初始化后台切换广播接收器
        initBackgroundCommandReceiver();
        initStorageReceiver();
        
        // 初始化录制切换广播接收器（来自悬浮窗）
        initToggleRecordingReceiver();
    }
    
    /**
     * 初始化录制切换广播接收器
     * 用于接收录制悬浮按钮的录制切换指令
     */
    private void initToggleRecordingReceiver() {
        toggleRecordingReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                String action = intent.getAction();
                if ("com.kooo.evcam.action.TOGGLE_RECORDING".equals(action)) {
                    AppLog.d(TAG, "收到录制切换广播（来自悬浮按钮）");
                    // 在主线程执行录制切换
                    runOnUiThread(() -> {
                        toggleRecording();
                    });
                } else if (ACTION_TAKE_PHOTO.equals(action)) {
                    AppLog.d(TAG, "收到拍照广播（来自悬浮按钮）");
                    runOnUiThread(MainActivity.this::takePicture);
                }
            }
        };
        
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction("com.kooo.evcam.action.TOGGLE_RECORDING");
        filter.addAction(ACTION_TAKE_PHOTO);
        registerReceiver(toggleRecordingReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        
        AppLog.d(TAG, "录制切换广播接收器已注册");
    }
    
    /**
     * 初始化后台切换广播接收器
     * 用于接收远程"后台"指令，避免使用 startActivity 导致闪屏
     */
    private void initBackgroundCommandReceiver() {
        backgroundCommandReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                String action = intent.getAction();
                if (WakeUpHelper.ACTION_MOVE_TO_BACKGROUND.equals(action)) {
                    AppLog.d(TAG, "收到后台切换广播");
                    // 直接退到后台，无需启动 Activity
                    moveTaskToBack(true);
                    AppLog.d(TAG, "应用已切换到后台（通过广播）");
                }
            }
        };
        
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(WakeUpHelper.ACTION_MOVE_TO_BACKGROUND);
        registerReceiver(backgroundCommandReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        
        AppLog.d(TAG, "后台切换广播接收器已注册");
    }

    /** U 盘插拔：录制键的「可不可录」和状态条的余量跟着变。 */
    private void initStorageReceiver() {
        storageReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                // 存储探测结果有缓存，插拔之后得先作废
                StorageHelper.clearCache();
                refreshRecordAvailability();
                updateStatusLine();
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(android.content.Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(android.content.Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(android.content.Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addAction(android.content.Intent.ACTION_MEDIA_EJECT);
        // 这几条系统广播带的是卷的路径，不加 file 这个 scheme 收不到
        filter.addDataScheme("file");
        registerReceiver(storageReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
    }
    
    /**
     * 息屏时的处理逻辑
     */
    private void onScreenOff() {
        isScreenOff = true;
        AppLog.d(TAG, "检测到息屏");
        
// 取消可能存在的亮屏恢复录制任务
        if (screenOnStartRunnable != null) {
            screenStateHandler.removeCallbacks(screenOnStartRunnable);
            screenOnStartRunnable = null;
        }
        
        // 判断是否为"自动录制+息屏录制"组合（需要保持相机活跃）
        boolean keepCameraActive = appConfig.isAutoStartRecording() && appConfig.isScreenOffRecordingEnabled();
        
        // 如果正在录制
        if (isRecording) {
            // 如果开启了自动录制+息屏录制，继续录制
            if (keepCameraActive) {
                AppLog.d(TAG, "息屏录制已启用，继续录制");
                return;
            }
            
            // 如果未开启自动录制功能，不干预手动录制，也不退后台
            if (!appConfig.isAutoStartRecording()) {
                AppLog.d(TAG, "手动录制中，不受息屏影响，保持前台");
                return;
            }
            
            // 开启了自动录制但未开启息屏录制，10秒后停止录制，15秒后退后台
            AppLog.d(TAG, "息屏录制未启用，将在10秒后停止录制，15秒后退后台...");
            wasRecordingBeforeScreenOff = true;
            
            screenOffStopRunnable = () -> {
                // 再次检查是否仍然息屏
                if (!isScreenOff) {
                    AppLog.d(TAG, "屏幕已亮起，取消停止录制");
                    return;
                }
                
                // 检查是否仍在录制
                if (!isRecording) {
                    AppLog.d(TAG, "已不在录制状态，无需停止");
                    return;
                }
                
                // 检查是否启用了自动录制（防止在等待期间用户关闭了设置）
                if (!appConfig.isAutoStartRecording()) {
                    AppLog.d(TAG, "自动录制功能已关闭，忽略");
                    return;
                }
                
                // 检查息屏录制设置是否被更改（防止在等待期间用户开启了息屏录制）
                if (appConfig.isScreenOffRecordingEnabled()) {
                    AppLog.d(TAG, "息屏录制已被启用，继续录制");
                    return;
                }
                
                AppLog.d(TAG, "息屏已持续10秒，自动停止录制");
                stopRecording();
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, R.string.msg_screen_off_stopped, Toast.LENGTH_SHORT).show();
                });
            };
            
            screenStateHandler.postDelayed(screenOffStopRunnable, SCREEN_OFF_DELAY_MS);
            
            // 同时安排15秒后退后台（与停止录制任务并行）
            scheduleBackgroundTask();
        } else {
            // 未在录制
            if (keepCameraActive) {
                // 开启了自动录制+息屏录制，保持前台（以便亮屏后可以立即录制）
                AppLog.d(TAG, "息屏录制模式，保持相机活跃");
                return;
            }
            
            // 其他情况：15秒后退后台，释放相机资源
            AppLog.d(TAG, "未在录制，将在15秒后退到后台释放相机资源...");
            scheduleBackgroundTask();
        }
    }
    
    /**
     * 安排息屏后退到后台的任务
     */
    private void scheduleBackgroundTask() {
        // 取消可能存在的退后台任务
        if (screenOffBackgroundRunnable != null) {
            screenStateHandler.removeCallbacks(screenOffBackgroundRunnable);
        }
        
        screenOffBackgroundRunnable = () -> {
            // 再次检查是否仍然息屏
            if (!isScreenOff) {
                AppLog.d(TAG, "屏幕已亮起，取消退后台");
                return;
            }
            
            // 如果正在录制，不退后台
            if (isRecording) {
                AppLog.d(TAG, "正在录制中，不退后台");
                return;
            }
            
            // 如果开启了自动录制+息屏录制，不退后台
            if (appConfig.isAutoStartRecording() && appConfig.isScreenOffRecordingEnabled()) {
                AppLog.d(TAG, "息屏录制模式已启用，不退后台");
                return;
            }
            
            // 以前这里直接关相机，不看后视镜也不看悬浮窗 —— 于是开着后视镜时
            // 关掉两秒后又被它的看门狗打开，每次熄屏白做一遍。现在问同一张登记表
            com.kooo.evcam.camera.CameraNeeds needs = com.kooo.evcam.camera.CameraNeeds.current();
            if (needs.heldByAnyoneExcept(com.kooo.evcam.camera.CameraNeeds.Holder.PREVIEW)) {
                AppLog.d(TAG, "息屏 15 秒，但相机还有人要: " + needs.describe() + "，不关");
                appConfig.setUiLeftForScreenOff(true);
                moveTaskToBack(true);
                return;
            }

            AppLog.d(TAG, "息屏已持续15秒，退到后台释放相机资源");

            // 留个记号：是我们自己因为熄屏退下去的。亮屏时据此把界面接回来 ——
            // 在这之前只退不回，人上车看到的是车机桌面，得自己再点一次图标
            appConfig.setUiLeftForScreenOff(true);
            
            // 关闭摄像头释放资源
            if (cameraManager != null) {
                cameraManager.closeAllCameras();
                AppLog.d(TAG, "已关闭所有摄像头");
            }
            
            // 退到后台
            moveTaskToBack(true);
            
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, R.string.msg_screen_off_background, Toast.LENGTH_SHORT).show();
            });
        };
        
        screenStateHandler.postDelayed(screenOffBackgroundRunnable, SCREEN_OFF_BACKGROUND_DELAY_MS);

        // 相机不等那 15 秒：车机熄屏没几秒就深睡，晚一步就是开着相机睡过去
        screenOffCameraRunnable = () -> {
            if (!isScreenOff || isRecording) {
                return;
            }
            if (appConfig.isAutoStartRecording() && appConfig.isScreenOffRecordingEnabled()) {
                return;
            }
            com.kooo.evcam.camera.CameraNeeds needs = com.kooo.evcam.camera.CameraNeeds.current();
            if (needs.heldByAnyone()) {
                AppLog.d(TAG, "熄屏，但相机还有人要: " + needs.describe() + "，等退后台那一步再说");
                return;
            }
            if (cameraManager != null) {
                cameraManager.closeAllCameras();
                com.kooo.evcam.blackbox.BlackBox.noteImportant("熄屏：相机已放开（深睡前）");
                AppLog.i(TAG, "熄屏，没人要相机，先关掉 —— 别开着相机睡过去");
            }
        };
        screenStateHandler.postDelayed(screenOffCameraRunnable, SCREEN_OFF_CAMERA_DELAY_MS);
    }
    
    /**
     * 亮屏时的处理逻辑
     */
    private void onScreenOn() {
        isScreenOff = false;
        AppLog.d(TAG, "检测到亮屏");
        
// 取消可能存在的息屏停止录制任务
        if (screenOffStopRunnable != null) {
            screenStateHandler.removeCallbacks(screenOffStopRunnable);
            screenOffStopRunnable = null;
            // 如果仍在录制，说明息屏停止任务没有执行，重置标记
            if (isRecording) {
                AppLog.d(TAG, "息屏期间录制未被停止（亮屏及时），重置标记");
                wasRecordingBeforeScreenOff = false;
            }
        }
        
        // 取消可能存在的退后台任务
        if (screenOffBackgroundRunnable != null) {
            screenStateHandler.removeCallbacks(screenOffBackgroundRunnable);
            screenOffBackgroundRunnable = null;
            AppLog.d(TAG, "亮屏，取消退后台任务");
        }
        if (screenOffCameraRunnable != null) {
            screenStateHandler.removeCallbacks(screenOffCameraRunnable);
            screenOffCameraRunnable = null;
        }

        // 因为熄屏自己退下去的，亮屏就自己回来。界面已经被系统收走的那种情况
        // 由 KeepAliveReceiver 接手（那时候这里根本不会被调用）
        if (appConfig.didUiLeaveForScreenOff()) {
            appConfig.setUiLeftForScreenOff(false);
            AppLog.i(TAG, "亮屏，把因熄屏退下去的主界面接回前台");
            try {
                Intent back = new Intent(this, MainActivity.class);
                back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(back);
            } catch (Exception e) {
                AppLog.w(TAG, "接回前台失败: " + e);
            }
        }
        
        // 检查是否启用了自动录制功能
        if (!appConfig.isAutoStartRecording()) {
            AppLog.d(TAG, "未启用自动录制功能，忽略亮屏事件");
            return;
        }
        
        // 检查息屏录制设置
        if (appConfig.isScreenOffRecordingEnabled()) {
            // 息屏录制已启用，无需恢复（一直在录制）
            AppLog.d(TAG, "息屏录制已启用，无需恢复录制");
            return;
        }
        
        // 检查是否需要恢复录制（之前因息屏而停止了录制）
        if (!wasRecordingBeforeScreenOff) {
            AppLog.d(TAG, "息屏前未在录制或录制未被中断，无需恢复");
            return;
        }
        
        // 如果已经在录制，无需恢复（这种情况理论上不会发生，因为上面已经处理）
        if (isRecording) {
            AppLog.d(TAG, "已在录制中，无需恢复");
            wasRecordingBeforeScreenOff = false;
            return;
        }
        
        AppLog.d(TAG, "亮屏后将在10秒后恢复录制...");
        
        // 如果摄像头已关闭，先重新打开
        if (cameraManager != null && !cameraManager.hasConnectedCameras()) {
            AppLog.d(TAG, "摄像头已关闭，先重新打开摄像头");
            try {
                cameraManager.openAllCameras();
            } catch (Exception e) {
                AppLog.e(TAG, "重新打开摄像头失败: " + e.getMessage(), e);
            }
        }
        
        screenOnStartRunnable = () -> {
            // 再次检查是否仍然亮屏
            if (isScreenOff) {
                AppLog.d(TAG, "屏幕又息屏了，取消恢复录制");
                return;
            }
            
            // 重置标记
            wasRecordingBeforeScreenOff = false;
            
            // 检查是否启用了自动录制（防止在等待期间用户关闭了设置）
            if (!appConfig.isAutoStartRecording()) {
                AppLog.d(TAG, "自动录制功能已关闭，不恢复录制");
                return;
            }
            
            // 检查息屏录制设置
            if (appConfig.isScreenOffRecordingEnabled()) {
                AppLog.d(TAG, "息屏录制已被启用，无需处理");
                return;
            }
            
            // 检查是否已在录制
            if (isRecording) {
                AppLog.d(TAG, "已在录制中，无需恢复");
                return;
            }
            
            // 检查摄像头是否就绪
            if (cameraManager == null || !cameraManager.hasConnectedCameras()) {
                AppLog.w(TAG, "摄像头未就绪，尝试重新打开...");
                // 再次尝试打开摄像头
                if (cameraManager != null) {
                    try {
                        cameraManager.openAllCameras();
                        // 延迟2秒后再次尝试恢复录制
                        screenStateHandler.postDelayed(() -> {
                            if (!isScreenOff && !isRecording && cameraManager.hasConnectedCameras()) {
                                AppLog.d(TAG, "摄像头已就绪，开始恢复录制");
                                startRecording();
                                Toast.makeText(MainActivity.this, R.string.msg_recording_resumed, Toast.LENGTH_SHORT).show();
                            }
                        }, 2000);
                    } catch (Exception e) {
                        AppLog.e(TAG, "打开摄像头失败: " + e.getMessage(), e);
                    }
                }
                return;
            }
            
            AppLog.d(TAG, "亮屏已持续10秒，自动恢复录制");
            startRecording();
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, R.string.msg_screen_on_resumed, Toast.LENGTH_SHORT).show();
            });
        };
        
        screenStateHandler.postDelayed(screenOnStartRunnable, SCREEN_ON_DELAY_MS);
    }
    /**
     * 切换录制状态（开始/停止）
     */
    /**
     * 录到内置存储之前先提醒一次。
     *
     * <p>只挡<b>手动开始</b>：自动录制（开机、回前台、远程唤醒）不弹，
     * 那些场景没人在看屏幕，一个等着点确定的弹窗只会让记录仪根本不录 ——
     * 那比写内置存储糟得多。自动路径改用一条 Toast 提示。</p>
     */
    private void confirmInternalStorageThen(Runnable onProceed) {
        if (!StorageHelper.willRecordToInternal(this)) {
            onProceed.run();
            return;
        }
        // 正常模式下根本不往内置存储录 —— 与其偷偷降级，不如说清楚并且不录
        if (!StorageHelper.isInternalStorageAllowed()) {
            com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(R.string.dlg_no_external_title)
                    .setMessage(R.string.dlg_no_external_msg)
                    .setPositiveButton(R.string.action_got_it, null));
            return;
        }
        boolean chosen = !new AppConfig(this).isUsingExternalSdCard();
        String why = getString(chosen
                ? R.string.dlg_internal_reason_chosen
                : R.string.dlg_internal_reason_fallback);
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_will_use_internal_title)
                .setMessage(getString(R.string.dlg_internal_warn, why))
                .setPositiveButton(R.string.action_record_anyway, (dialog, which) -> onProceed.run())
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void toggleRecording() {
        // 防双击保护
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRecordButtonClickTime < RECORD_BUTTON_CLICK_INTERVAL) {
            AppLog.d(TAG, "录制按钮点击过快，忽略（间隔: " + (currentTime - lastRecordButtonClickTime) + "ms）");
            return;
        }
        lastRecordButtonClickTime = currentTime;
        
        if (isRecording) {
            // 用户手动停止录制，设置手动停止标记
            // 这样自动录制检查不会自动恢复录制
            com.kooo.evcam.recording.RecordingIntent.current().noteUserStopped();
            AppLog.d(TAG, "用户手动停止录制，这一趟不再自动开始");
            
            // 用户手动停止录制，重置息屏录制标记
            // 这样亮屏后不会错误地恢复录制
            wasRecordingBeforeScreenOff = false;
            stopRecording();
        } else {
            // 用户手动开始录制，重置手动停止标记
            // 这样后续如果录制异常停止，可以自动恢复
            com.kooo.evcam.recording.RecordingIntent.current().noteUserStarted();
            AppLog.d(TAG, "用户手动开始录制，自动恢复重新生效");
            confirmInternalStorageThen(this::startRecording);
        }
    }

    /**
     * 录制结果的画面反馈。
     *
     * <p>协调器只管录不录得起来；指示器、计时器、Toast 都在这里 ——
     * 它们出错只是看着别扭，和「有没有录上」不是一回事，
     * 混在一起会让后者也没法单独验证。</p>
     */
    private final RecordingCoordinator.Listener recordingListener =
            new RecordingCoordinator.Listener() {
        @Override
        public void onRecordingStarted(java.util.Set<String> cameras, boolean sdFellBack) {
            lastRefusalShown = null;
            isRecording = true;
            isPreparingRecording = true;
            isAutoRecordingPending = false;
            com.kooo.evcam.recording.RecordingIntent.current().noteRecordingStarted();
            syncRecordingClaim();

            // 橙色旋转圈；首次写入回调里换成绿色闪烁。
            // 计时器也在那时才启动 —— 从「真的录上了」开始计，而不是从「尝试录」开始
            showPreparingIndicator();

            if (sdFellBack && !AppConfig.isSdFallbackShownThisSession()) {
                AppConfig.setSdFallbackShownThisSession(true);
                Toast.makeText(MainActivity.this,
                        R.string.msg_sd_fallback, Toast.LENGTH_LONG).show();
            } else {
                int count = cameras.size();
                Toast.makeText(MainActivity.this,
                        count == appConfig.getCameraCount()
                                ? getString(R.string.msg_recording_started_all)
                                : getString(R.string.msg_recording_started_n, count),
                        Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onRecordingStopped() {
            lastRefusalShown = null;
            preparingHandler.removeCallbacks(preparingWatchdog);
            isRecording = false;
            isPreparingRecording = false;
            syncRecordingClaim();
            setRecordState(com.kooo.evcam.ui.RecordButtonUi.State.IDLE);
            stopRecordingTimer();
            if (!quietStop) {
                Toast.makeText(MainActivity.this, R.string.msg_recording_stopped, Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onRecordingRefused(String reason) {
            // 自动录制会定时重试，同一条理由不必每次都弹一遍 ——
            // 那会变成一串关不掉的提示，反而盖住真正该看的东西
            if (reason != null && reason.equals(lastRefusalShown)) {
                return;
            }
            lastRefusalShown = reason;
            Toast.makeText(MainActivity.this, reason, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onRecordingFailed(String reason) {
            Toast.makeText(MainActivity.this, reason, Toast.LENGTH_SHORT).show();
        }
    };

    /**
     * 开始录制。
     *
     * <p>要不要录、能不能录、怎么录，都在 {@link RecordingCoordinator} 里；
     * 这里只剩把结果画到屏幕上。</p>
     */
    /**
     * 把当前的相机管理器交给协调器。
     *
     * <p>每一处给 {@code cameraManager} 赋值之后都要调一次：它换了对象而协调器
     * 还握着旧的，表现会是「按了录制，什么都没发生」—— 这种不报错的失败最难查。</p>
     */
    private void attachRecordingCoordinator() {
        if (recordingCoordinator == null) {
            recordingCoordinator = new RecordingCoordinator(this);
            recordingCoordinator.setListener(recordingListener);
        }
        recordingCoordinator.setCameraManager(cameraManager);
    }

    private void startRecording() {
        recordingCoordinator.start();
    }

    private void stopRecording() {
        recordingCoordinator.stop();
    }

    /**
     * 完全退出应用（包括后台进程）
     * 这是用户主动退出，需要停止所有服务
     *
     * <p>入口在抽屉和设置左栏的最底下。主界面右下角那个键现在是「隐藏到后台」——
     * 原来那个 × 两件事都像，按下去之前猜不到是收起来还是全关掉。长按那个键才走到这里。</p>
     */
    public void exitApp() {
        AppLog.d(TAG, "用户请求退出应用，停止所有服务...");
        // 退出这条路到底走到哪一步、之后还有谁把我们拉起来 —— 靠这一行和后面的进程启动行对照
        com.kooo.evcam.blackbox.BlackBox.noteImportant("==== 用户退出应用 ====");

        // 退出算一趟结束：下次打开是新的一趟，「启动自动录制」该重新生效
        com.kooo.evcam.recording.RecordingIntent.current().reset();
        // 主动退出之后不该再被亮屏拉回来
        appConfig.setUiLeftForScreenOff(false);

        // 停止录制（如果正在录制）
        if (isRecording) {
            stopRecording();
        }

        // 停止前台服务（确保清理）
        CameraForegroundService.stop(this);


        // 后视镜和录制按钮不停：它们本来就是脱离主界面用的
        OverlayCoordinator.onActivityDestroyed(this);
        
        // 释放持续唤醒锁
        WakeUpHelper.releasePersistentWakeLock();

        // 释放摄像头资源
        if (cameraManager != null) {
            cameraManager.release();
        }
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().release();
        
        // 保存日志（System.exit 会跳过 onDestroy，所以这里手动保存）
        AppLog.saveToPersistentLog(this);

        // 结束所有Activity并退出应用
        finishAffinity();

        // 完全退出进程
        System.exit(0);
    }

    /** 录制键切到某个状态。回调可能来自相机线程，统一丢回主线程。 */
    private void setRecordState(com.kooo.evcam.ui.RecordButtonUi.State state) {
        com.kooo.evcam.ui.MotionPolicy.setRecording(state == com.kooo.evcam.ui.RecordButtonUi.State.PREPARING
                || state == com.kooo.evcam.ui.RecordButtonUi.State.RECORDING);
        runOnUiThread(() -> {
            if (recordButtonUi == null) {
                return;
            }
            if (state == com.kooo.evcam.ui.RecordButtonUi.State.IDLE) {
                refreshRecordAvailability();
            } else {
                recordButtonUi.setState(state);
            }
        });
    }

    /** 准备中：点已收成方块、一明一暗，外圈在转 —— 录制器还没起来。 */
    private void showPreparingIndicator() {
        setRecordState(com.kooo.evcam.ui.RecordButtonUi.State.PREPARING);
        preparingHandler.removeCallbacks(preparingWatchdog);
        preparingHandler.postDelayed(preparingWatchdog, PREPARING_TIMEOUT_MS);
        AppLog.d(TAG, "进入准备中状态，" + PREPARING_TIMEOUT_MS + "ms 内等第一笔数据");
    }

    /**
     * 准备中超时：第一笔数据迟迟不来。
     *
     * <p>先把这一次当作没录上 —— 停掉录制器（它会清掉那个一个字节都没写进去的分段文件），
     * 状态回到待机；然后自动再开一次。用户手动做这两下是能恢复的，说明第二次通常能成，
     * 那就不该让用户来做。重试也超时就停下来，明确告诉用户，不再无限重试。</p>
     */
    private void onPreparingTimedOut() {
        if (!isPreparingRecording) {
            return;
        }
        boolean managerRecording = cameraManager != null && cameraManager.isRecording();
        boolean connected = cameraManager != null && cameraManager.hasConnectedCameras();
        AppLog.w(TAG, "准备中超时：" + PREPARING_TIMEOUT_MS + "ms 没收到第一笔数据 " + instanceTag()
                + " 第" + (preparingTimeouts + 1) + "次 managerRecording=" + managerRecording
                + " camerasConnected=" + connected
                + " resumeAfterRecreate=" + shouldResumeRecordingAfterRecreate
                + " inBackground=" + isInBackground);

        quietStop = true;
        suppressRecordErrorToastUntil = System.currentTimeMillis() + 5_000L;
        try {
            stopRecording();
        } catch (Exception e) {
            AppLog.w(TAG, "准备中超时后停止录制器失败: " + e);
        } finally {
            quietStop = false;
        }
        // 协调器的停止回调可能不来（录制器本来就没起来）—— 这里自己把状态摆正
        isRecording = false;
        isPreparingRecording = false;
        stopRecordingTimer();
        setRecordState(com.kooo.evcam.ui.RecordButtonUi.State.IDLE);

        preparingTimeouts++;
        if (preparingTimeouts <= 1) {
            Toast.makeText(this, R.string.msg_record_start_retrying, Toast.LENGTH_SHORT).show();
            preparingHandler.postDelayed(() -> {
                if (!isRecording && !isFinishing() && !isDestroyed()) {
                    AppLog.i(TAG, "准备中超时后自动重试录制");
                    startRecording();
                }
            }, 1_500L);
        } else {
            AppLog.w(TAG, "准备中连续超时 " + preparingTimeouts + " 次，不再自动重试");
            preparingTimeouts = 0;
            Toast.makeText(this, R.string.msg_record_start_gave_up, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 接上一条还在进行的录制：状态以录制管线为准，而不是去「恢复」它。
     *
     * <p>主界面被重建或者重新打开时，录制管线一直在跑（见 onDestroy 的 keepPipeline）。
     * 新界面要做的只是把按钮、计时器画成管线现在的样子：写出过第一笔数据就是录制中，
     * 计时从那一刻接着走；还没写出就是准备中，看门狗照常盯着。</p>
     */
    private void syncRecordingStateFromManager() {
        if (cameraManager == null || !cameraManager.isRecording()) {
            return;
        }
        isRecording = true;
        // 录制根本没断，不存在「恢复」—— 那条路会以为要重开一次
        shouldResumeRecordingAfterRecreate = false;
        savedRecordingStartTime = 0;
        if (cameraManager.hasWrittenFirstData()) {
            isPreparingRecording = false;
            startRecordingTimer(cameraManager.getFirstDataWrittenAtMs(),
                    cameraManager.getCurrentSegmentIndex() + 1);
            setRecordState(com.kooo.evcam.ui.RecordButtonUi.State.RECORDING);
        } else {
            isPreparingRecording = true;
            showPreparingIndicator();
        }
        AppLog.i(TAG, "接上了还在进行的录制 " + instanceTag()
                + " firstData=" + cameraManager.hasWrittenFirstData()
                + " segment=" + (cameraManager.getCurrentSegmentIndex() + 1));
    }

    /**
     * 回到前台时，对一下「界面以为在录」和「录制器真的在录」是不是一回事。
     *
     * <p>以前这里只写了一句「录制中，相机应该还连着」就什么都不查。录制器若在后台停了，
     * 界面会一直停在准备中或录制中，要等用户去点才暴露。录制器在请求开始的那一刻就会
     * 把自己标成在录（不等第一笔数据），所以正常的准备中不会被误判。</p>
     */
    private void reconcileRecordingState() {
        if (!isRecording || isRemoteRecording || cameraManager == null) {
            return;
        }
        if (cameraManager.isRecording()) {
            return;
        }
        AppLog.w(TAG, "回到前台：界面以为在录，录制器其实没在录 " + instanceTag()
                + " preparing=" + isPreparingRecording);
        preparingHandler.removeCallbacks(preparingWatchdog);
        preparingTimeouts = 0;
        isRecording = false;
        isPreparingRecording = false;
        stopRecordingTimer();
        setRecordState(com.kooo.evcam.ui.RecordButtonUi.State.IDLE);
        Toast.makeText(this, R.string.msg_recording_lost_in_background, Toast.LENGTH_LONG).show();
    }

    /**
     * 结束准备中：真的录上了就进录制中，没录上就回待机。
     *
     * <p>以前没录上时这里什么都不做，按钮就停在「准备中」的颜色上。</p>
     */
    private void hidePreparingIndicator() {
        setRecordState(isRecording || isRemoteRecording
                ? com.kooo.evcam.ui.RecordButtonUi.State.RECORDING
                : com.kooo.evcam.ui.RecordButtonUi.State.IDLE);
    }

    private void takePicture() {
        if (cameraManager != null) {
            cameraManager.takePicture();
            Toast.makeText(this, R.string.msg_photo_taken, Toast.LENGTH_SHORT).show();
            AppLog.d(TAG, "Picture taken");
        }
    }

    /**
     * 如果是远程唤醒的，完成后自动退回后台
     * 延迟2秒后执行，让用户看到上传成功的提示
     */
    private void returnToBackgroundIfRemoteWakeUp() {
        if (!isRemoteWakeUp) {
            AppLog.d(TAG, "Not a remote wake-up, staying in foreground");
            return;
        }

        AppLog.d(TAG, "Remote command completed, will return to background in 2 seconds");

        // 延迟2秒后退回后台，让用户看到 Toast 提示
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            // 重置标记
            isRemoteWakeUp = false;

            // 释放唤醒锁，让屏幕可以自然熄灭
            WakeUpHelper.releaseWakeLock();

            // 将应用退到后台
            AppLog.d(TAG, "Moving task to back...");
            moveTaskToBack(true);

            AppLog.d(TAG, "Returned to background successfully");
        }, 2000);
    }

















    // ==================== 飞书服务管理 ====================











    /**
     * 获取当前录制状态（供外部查询）
     */
    public boolean isCurrentlyRecording() {
        return isRecording;
    }

    /**
     * 发送当前录制状态广播（供悬浮窗服务查询）
     */
    public void broadcastCurrentRecordingState() {
        com.kooo.evcam.service.RecordingFloatingService.sendRecordingStateChanged(this, isRecording);
    }
    
    /**
     * 重启存储清理任务（配置更改后调用）
     */
    public void restartStorageCleanupTask() {
        if (storageCleanupManager != null) {
            storageCleanupManager.stop();
        }
        storageCleanupManager = new StorageCleanupManager(this);
        storageCleanupManager.start();
        AppLog.d(TAG, "存储清理任务已重启");
    }



    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        
        // 保存录制状态（用于主题切换后恢复）
        // 注意：只保存非远程录制的状态，远程录制（钉钉指令）不自动恢复
        if (isRecording && !isRemoteRecording) {
            outState.putBoolean("wasRecording", true);
            outState.putLong("recordingStartTime", recordingStartTime);
            outState.putInt("segmentCount", currentSegmentCount);
            AppLog.d(TAG, "onSaveInstanceState: 保存录制状态 - startTime=" + recordingStartTime + ", segment=" + currentSegmentCount);
        } else {
            outState.putBoolean("wasRecording", false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isInBackground = true;
        com.kooo.evcam.camera.StallWatch.setForeground(false);
        AppLog.d(TAG, "onPause called, isRecording=" + isRecording);
        
        // 通知悬浮窗：应用退到后台
        OverlayCoordinator.onAppBackground(this);
        
        // 预览不在前台了，注销这一项；剩下还有没有人要，问登记表
        syncRecordingClaim();
        com.kooo.evcam.camera.CameraNeeds needs = com.kooo.evcam.camera.CameraNeeds.current();
        needs.release(com.kooo.evcam.camera.CameraNeeds.Holder.PREVIEW);
        if (cameraManager != null) {
            if (needs.heldByAnyone()) {
                AppLog.d(TAG, "退到后台，相机留着 —— 还有人要: " + needs.describe());
            } else {
                AppLog.d(TAG, "退到后台，没人要相机，关掉");
                cameraManager.closeAllCameras();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppLog.i(TAG, "onStart " + instanceTag() + " surround: " + describeComposite());
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        AppLog.i(TAG, "onRestart " + instanceTag());
    }

    private String instanceTag() {
        return "MainActivity@" + Integer.toHexString(System.identityHashCode(this));
    }

    private String describeComposite() {
        return compositeContainer == null ? "no surround container in this layout"
                : compositeContainer.describeState();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 界面是怎么离开的：关掉？只是切走？还是在重建？三种后果完全不同
        com.kooo.evcam.blackbox.BlackBox.note("主界面 onStop finishing=" + isFinishing()
                + " changingConfigurations=" + isChangingConfigurations()
                + " recording=" + isRecording);
        AppLog.d(TAG, "onStop called, isRecording=" + isRecording);
        AppLog.i(TAG, "onStop " + instanceTag() + " surround: " + describeComposite());
        
        // 主界面被关掉（返回键退出、从最近任务划掉）时录制不停：录制管线不属于界面，
        // 它由前台服务保着，界面再打开时接回去（见 syncRecordingStateFromManager）。
        // 真正停录只有两条路：点停止，或者长按退出应用（exitApp 自己停，不经过这里）
        if (isRecording && isFinishing()) {
            AppLog.i(TAG, "主界面关闭，录制继续在后台进行 " + instanceTag());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean wasInBackground = isInBackground;
        isInBackground = false;
        com.kooo.evcam.camera.StallWatch.setForeground(true);
        AppLog.i(TAG, "onResume " + instanceTag() + " surround: " + describeComposite());
        
        // 标记 Activity 已经完全恢复过一次（用于区分新创建和已存在的 Activity）
        // 这个标记在 onCreate 后第一次 onResume 时设为 true
        boolean wasFirstResume = !hasBeenResumedOnce;
        hasBeenResumedOnce = true;
        
        AppLog.d(TAG, "onResume called, wasInBackground=" + wasInBackground + ", isRecording=" + isRecording + ", firstResume=" + wasFirstResume);
        
        // 通知悬浮窗：应用回到前台
        OverlayCoordinator.onAppForeground(this);

        // 人已经在界面上了，「因熄屏退下去」这个记号就作废 —— 不管是自己接回来的，
        // 还是用户自己点回来的
        appConfig.setUiLeftForScreenOff(false);

        // 预览又要用相机了
        com.kooo.evcam.camera.CameraNeeds.current().claim(com.kooo.evcam.camera.CameraNeeds.Holder.PREVIEW);
        syncRecordingClaim();

        // 界面记的录制状态和录制器的真实状态先对一下；对不上就以录制器为准
        reconcileRecordingState();

        // U 盘可能在后台时插拔过
        refreshRecordAvailability();
        updateStatusLine();
        
        // 返回前台时，检查摄像头连接状态
        if (cameraManager != null && wasInBackground) {
            // 初始化 Handler（如果需要）
            if (reopenCameraHandler == null) {
                reopenCameraHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            }
            
            // 取消之前的延迟任务（防抖：避免 onResume 被多次调用时重复打开摄像头）
            if (reopenCameraRunnable != null) {
                reopenCameraHandler.removeCallbacks(reopenCameraRunnable);
                AppLog.d(TAG, "Cancelled previous camera reopen task (debounce)");
            }
            
            // 创建新的延迟任务
            reopenCameraRunnable = () -> {
                // 只在没有正在录制时重新打开（录制时摄像头应该保持连接）
                if (!isRecording) {
                    AppLog.d(TAG, "Reopening cameras after returning from background");
                    cameraManager.openAllCameras();

                    // 这里原本还有一段：只要开着「启动自动录制」，回到前台就开始录。
                    // 它不看用户停没停过，也不看这一趟有没有录起来过 ——
                    // 于是「打开视频回放再切回主界面」就会自己录上。
                    // 回到前台不是开始录制的理由：启动时开一次由 checkAutoStartRecording 管，
                    // 录着录着意外停了由 checkAndRestoreAutoRecording 接，两条都在
                    // RecordingIntent 里统一判断。
                    
                    // 重新启动超视模式窗口的摄像头预览
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        AppLog.d(TAG, "重新启动超视模式摄像头预览");
                        BlindSpotService.restartSupervisionCameraPreview();
                    }, 500);  // 等待摄像头打开后
                } else {
                    AppLog.d(TAG, "Recording in progress, cameras should still be connected");
                }
                
            };
            
            // 延迟100ms后执行（只有最后一次 onResume 会真正执行）
            reopenCameraHandler.postDelayed(reopenCameraRunnable, 100);
        }
}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        com.kooo.evcam.blackbox.BlackBox.noteImportant("主界面 onDestroy finishing=" + isFinishing()
                + " changingConfigurations=" + isChangingConfigurations());
        AppLog.i(TAG, "onDestroy " + instanceTag() + " finishing=" + isFinishing()
                + " changingConfigurations=" + isChangingConfigurations());

        // 录制管线留不留：还在录，或者只是重建（夜间模式、语言切换）—— 留着，界面再起来时接回去。
        // 以前这里一律释放，录制就断在重建上；新界面再去「恢复录制」，恢复没接上就卡在准备中
        final boolean keepPipeline = cameraManager != null && !cameraManager.isReleased()
                && (cameraManager.isRecording() || isChangingConfigurations());
        AppLog.i(TAG, "onDestroy 录制管线" + (keepPipeline ? "保留" : "释放") + " "
                + instanceTag() + " recording=" + (cameraManager != null && cameraManager.isRecording())
                + " changingConfigurations=" + isChangingConfigurations());
        if (!keepPipeline) {
            // 不留就清掉 Holder：release() 会清空 cameras map，进程若因 Service 存活而不退出，
            // Holder 会握着一个已清空的实例被下次复用
            com.kooo.evcam.camera.CameraManagerHolder.getInstance().setCameraManager(null);
        }

        // 关闭预览矫正悬浮窗
        dismissPreviewCorrectionFloating();

        // 停止调试信息更新
        stopDebugUpdates();

        // 清除静态实例引用
        if (instance == this) {
            instance = null;
        }
        
        // 保存当前运行日志到持久化文件（用于下次启动时可上传"上次运行日志"）
        // 放在 onDestroy 开头，确保在清理其他资源前保存完整日志
        AppLog.saveToPersistentLog(this);

        preparingHandler.removeCallbacksAndMessages(null);

        // 取消自动停止录制的任务
        if (autoStopHandler != null && autoStopRunnable != null) {
            autoStopHandler.removeCallbacks(autoStopRunnable);
        }
        
        // 停止自动录制定时检查
        stopAutoRecordingCheck();
        
        // 重置远程录制状态
        isRemoteRecording = false;
        wasManualRecordingBeforeRemote = false;
        
        // 清理息屏录制相关资源
        if (screenStateReceiver != null) {
            try {
                unregisterReceiver(screenStateReceiver);
            } catch (Exception e) {
                AppLog.w(TAG, "注销息屏广播接收器时出错: " + e.getMessage());
            }
            screenStateReceiver = null;
        }
        
        // 清理后台切换广播接收器
        if (backgroundCommandReceiver != null) {
            try {
                unregisterReceiver(backgroundCommandReceiver);
            } catch (Exception e) {
                AppLog.w(TAG, "注销后台切换广播接收器时出错: " + e.getMessage());
            }
            backgroundCommandReceiver = null;
        }
        
        if (storageReceiver != null) {
            try {
                unregisterReceiver(storageReceiver);
            } catch (Exception e) {
                AppLog.w(TAG, "注销 U 盘插拔广播接收器时出错: " + e.getMessage());
            }
            storageReceiver = null;
        }

        // 清理录制切换广播接收器
        if (toggleRecordingReceiver != null) {
            try {
                unregisterReceiver(toggleRecordingReceiver);
            } catch (Exception e) {
                AppLog.w(TAG, "注销录制切换广播接收器时出错: " + e.getMessage());
            }
            toggleRecordingReceiver = null;
        }
        if (screenStateHandler != null) {
            if (screenOffStopRunnable != null) {
                screenStateHandler.removeCallbacks(screenOffStopRunnable);
            }
            if (screenOnStartRunnable != null) {
                screenStateHandler.removeCallbacks(screenOnStartRunnable);
            }
            if (screenOffBackgroundRunnable != null) {
                screenStateHandler.removeCallbacks(screenOffBackgroundRunnable);
            }
        }

        // 前台服务：录制还在继续就不能停 —— 没有它，系统会在后台把录制掐掉
        if (!keepPipeline) {
            CameraForegroundService.stop(this);
        }

        // 停止存储清理任务
        if (storageCleanupManager != null) {
            storageCleanupManager.stop();
        }
        
        // 文件传输（U 盘中转写入）：录制还在继续，分段还要往 U 盘搬，不能停
        if (!keepPipeline) {
            FileTransferManager.getInstance(this).stop();
        }

        if (keepPipeline) {
            // 留着管线：只摘掉这个界面设的回调
            cameraManager.detachUiCallbacks();
        } else if (cameraManager != null) {
            // 带超时保护的摄像头资源释放
            releaseCameraManagerWithTimeout(3000);  // 3秒超时
        }
    }
    
    /**
     * 带超时保护的摄像头管理器释放
     * 防止 release() 操作阻塞过久导致 ANR
     * 
     * @param timeoutMs 超时时间（毫秒）
     */
    private void releaseCameraManagerWithTimeout(long timeoutMs) {
        if (cameraManager == null) {
            return;
        }
        
        final CountDownLatch latch = new CountDownLatch(1);
        
        // 在后台线程执行 release，避免阻塞主线程
        new Thread(() -> {
            try {
                AppLog.d(TAG, "Releasing camera manager in background thread...");
                cameraManager.release();
                AppLog.d(TAG, "Camera manager released successfully");
            } catch (Exception e) {
                AppLog.e(TAG, "Error releasing camera manager", e);
            } finally {
                latch.countDown();
            }
        }, "CameraRelease").start();
        
        try {
            // 等待 release 完成，但设置超时避免 ANR
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                AppLog.w(TAG, "Camera manager release timed out after " + timeoutMs + "ms, " +
                        "resources may not be fully released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AppLog.w(TAG, "Camera manager release interrupted");
        }
    }

    /**
     * 显示录制异常的提示（自动消失，每20秒最多显示一次）
     */
    private void showCorruptedFilesDeletedDialog(List<String> deletedFiles) {
        if (deletedFiles == null || deletedFiles.isEmpty()) {
            return;
        }

        // 记录日志（始终记录）
        AppLog.w(TAG, "Recording error, deleted " + deletedFiles.size() + " corrupted files: " + deletedFiles);

        // 准备中超时后看门狗停掉录制器，清掉的正是那个空分段 —— 这是在收拾，不是新的异常
        if (System.currentTimeMillis() < suppressRecordErrorToastUntil) {
            AppLog.d(TAG, "准备中超时的清理，不弹录制异常");
            return;
        }

        // 检查是否可以显示 Toast（20秒内只显示一次）
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRecordingErrorToastTime < RECORDING_ERROR_TOAST_INTERVAL) {
            AppLog.d(TAG, "Recording error toast suppressed (rate limited)");
            return;
        }
        lastRecordingErrorToastTime = currentTime;

        runOnUiThread(() -> {
            android.widget.Toast.makeText(this, R.string.msg_record_error, android.widget.Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }

        FragmentManager fragmentManager = getSupportFragmentManager();

        // 先问当前这一层自己能不能退一步。设置页的二级界面（权限、相机映射）
        // 进的是设置外壳自己的子返回栈，活动这一层的返回栈是空的 ——
        // 不问它，从二级界面按返回会一路跳回录制界面，而不是回到刚才那个分区。
        // isAdded() 一定要查：这个指针在 Fragment 被 replace 掉之后不一定跟着清空，
        // 对着一个已经移走的 Fragment 取子 FragmentManager 会炸
        Fragment current = fragmentManager.getPrimaryNavigationFragment();
        if (current != null && current.isAdded()
                && current.getChildFragmentManager().popBackStackImmediate()) {
            AppLog.d(TAG, "返回键：退回上一级设置界面");
            return;
        }

        if (fragmentManager.getBackStackEntryCount() > 0) {
            fragmentManager.popBackStack();
            AppLog.d(TAG, "返回键：弹出返回栈");
        } else if (fragmentContainer != null && fragmentContainer.getVisibility() == View.VISIBLE) {
            goToRecordingInterface();
            AppLog.d(TAG, "返回键：回到录制界面");
        } else {
            // 录制界面按返回不关闭 Activity，退到后台 —— 下次打开能直接恢复
            moveTaskToBack(true);
            AppLog.d(TAG, "返回键：退到后台");
        }
    }
    
    // ==================== 亮度/降噪调节相关方法 ====================
    
    /**
     * 获取亮度/降噪调节管理器
     * @return ImageAdjustManager 实例
     */
    public ImageAdjustManager getImageAdjustManager() {
        return imageAdjustManager;
    }
    
    /**
     * 注册摄像头到亮度/降噪调节管理器
     */
    private void registerCamerasToImageAdjustManager() {
        if (imageAdjustManager == null || cameraManager == null) {
            return;
        }
        
        // 清空之前注册的摄像头
        imageAdjustManager.clearCameras();
        
        // 注册各位置的摄像头
        String[] positions = {"front", "back", "left", "right"};
        for (String position : positions) {
            SingleCamera camera = cameraManager.getCamera(position);
            if (camera != null) {
                imageAdjustManager.registerCamera(camera);
            }
        }
        
        // 如果启用了亮度/降噪调节，设置各摄像头的启用状态
        boolean enabled = appConfig.isImageAdjustEnabled();
        if (enabled) {
            setImageAdjustEnabled(true);
        }
        
        AppLog.d(TAG, "Registered cameras to ImageAdjustManager, adjust enabled: " + enabled);
    }
    
    // ==================== 心跳推图相关方法 ====================
    

    

    
    /**
     * 获取已连接的摄像头数量
     */
    public int getConnectedCameraCount() {
        if (cameraManager != null) {
            return cameraManager.getConnectedCameraCount();
        }
        return 0;
    }
    
    /**
     * 获取配置的摄像头总数
     */
    public int getTotalCameraCount() {
        return configuredCameraCount;
    }
    

    

    
    /**
     * 转义 JSON 字符串
     */
    private String escapeJsonString(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    /**
     * 设置亮度/降噪调节启用状态
     * @param enabled true 表示启用
     */
    public void setImageAdjustEnabled(boolean enabled) {
        if (cameraManager == null) {
            return;
        }
        
        // 设置各摄像头的启用状态
        String[] positions = {"front", "back", "left", "right"};
        for (String position : positions) {
            SingleCamera camera = cameraManager.getCamera(position);
            if (camera != null) {
                camera.setImageAdjustEnabled(enabled);
            }
        }
        
        // 如果启用，立即应用当前配置的参数
        if (enabled && imageAdjustManager != null) {
            // 延迟执行，确保摄像头会话已经配置好
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                imageAdjustManager.updateAllCameras();
            }, 500);
        }
        
        AppLog.d(TAG, "Image adjust enabled: " + enabled);
    }
    
    /**
     * 显示亮度/降噪调节悬浮窗
     * 悬浮窗由 MainActivity 管理，这样即使退出设置页面也能保持显示
     */
    public void showImageAdjustFloatingWindow() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.msg_need_overlay_adjust, Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            return;
        }
        
        if (imageAdjustManager == null) {
            Toast.makeText(this, R.string.msg_camera_not_ready_adjust, Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 关闭之前的悬浮窗（如果有）
        if (imageAdjustFloatingWindow != null && imageAdjustFloatingWindow.isShowing()) {
            imageAdjustFloatingWindow.dismiss();
        }
        
        // 创建并显示悬浮窗
        imageAdjustFloatingWindow = new ImageAdjustFloatingWindow(this, imageAdjustManager);
        imageAdjustFloatingWindow.setOnDismissListener(() -> {
            AppLog.d(TAG, "Image adjust floating window dismissed");
        });
        imageAdjustFloatingWindow.show();
        
        AppLog.d(TAG, "Image adjust floating window shown");
    }
    
    /**
     * 关闭亮度/降噪调节悬浮窗
     */
    public void dismissImageAdjustFloatingWindow() {
        if (imageAdjustFloatingWindow != null && imageAdjustFloatingWindow.isShowing()) {
            imageAdjustFloatingWindow.dismiss();
            imageAdjustFloatingWindow = null;
        }
    }
    
    /**
     * 检查亮度/降噪调节悬浮窗是否正在显示
     */
    public boolean isImageAdjustFloatingWindowShowing() {
        return imageAdjustFloatingWindow != null && imageAdjustFloatingWindow.isShowing();
    }
    
    private static final int REQUEST_OVERLAY_PERMISSION = 1001;
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && android.provider.Settings.canDrawOverlays(this)) {
                // 权限已授予，打开悬浮窗
                showImageAdjustFloatingWindow();
            } else {
                Toast.makeText(this, R.string.msg_overlay_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    // ==================== 静态实例访问 ====================
    
    /**
     * 获取 MainActivity 实例
     * 用于 CameraForegroundService 检查 Activity 是否在运行
     * 
     * @return MainActivity 实例，如果 Activity 未创建或已销毁则返回 null
     */
    public static MainActivity getInstance() {
        return instance;
    }
    

    // ==================== 极氪合成流 ====================

    /**
     * 点画面放大，再点还原。
     *
     * <h3>为什么撤掉四宫格键</h3>
     *
     * <p>那个键只做一件事：四宫格和单画面来回切。可切到单画面时它总是先给前视，
     * 想看左视还得再点画面轮一圈 —— 想看哪一格，却要先按键、再数着点。
     * 直接点那一格才是这件事本来的手势。拍照键因此独占一行。</p>
     *
     * <h3>放大到哪、怎么铺</h3>
     *
     * <p>整块预览区。三路布局里点环视的一格，座舱那一列先让出来，再放大那一格；
     * 点座舱的一路，环视和另一路让出来。放大后一律<b>填充</b>（铺满、裁边），
     * 不按那一格原来的「适应」—— 放大就是为了看清，留两条黑边等于没放大。
     * 这些都不写配置：放大是看一眼的事。</p>
     */
    private void setupCompositeControls() {
        if (compositeContainer == null) {
            return;
        }
        // 布局可能是重建出来的：上一份布局里的放大状态作废，
        // 但相机对象还是那几个，座舱那一路的「填充」得撤掉
        expandAnimator.finishNow();
        collapsing = false;
        expandedFrom = null;
        hiddenForExpand.clear();
        if (expandedCameraKey != null && cameraManager != null) {
            com.kooo.evcam.camera.SingleCamera camera = cameraManager.getCamera(expandedCameraKey);
            if (camera != null) {
                camera.setFitOverride(null);
            }
        }
        expandedPreview = null;
        expandedCameraKey = null;
        expandedTopMargin = -1;

        compositeContainer.setOnClickListener(v -> {
            if (expandedPreview != null) {
                collapsePreview();
                return;
            }
            int lane = compositeContainer.laneAtLastTouch();
            if (lane >= 0) {
                expandCompositeLane(lane);
            }
        });
        View cabinFront = findViewById(R.id.pane_cabin_front);
        if (cabinFront != null) {
            cabinFront.setOnClickListener(v -> toggleCabinExpanded(v, "back"));
        }
        View cabinRear = findViewById(R.id.pane_cabin_rear);
        if (cabinRear != null) {
            cabinRear.setOnClickListener(v -> toggleCabinExpanded(v, "left"));
        }
        updateCompositeLabels();
    }

    /**
     * 环视的某一格放大到整块预览区。
     *
     * <p>三路布局里长大的是整块环视（座舱那一列让出来），起点是<b>这一格此刻在屏幕上的
     * 位置</b>，过渡由 {@link #expandAnimator} 做。只有环视的布局里环视本来就占满，
     * 由容器自己让那一格从格子里长出来。</p>
     */
    private void expandCompositeLane(int lane) {
        View cabinColumn = findViewById(R.id.cabin_column);
        View wrapper = findViewById(R.id.composite_wrapper);
        View target = wrapper != null ? wrapper : compositeContainer;
        boolean paneGrows = cabinColumn != null && cabinColumn.getVisibility() == View.VISIBLE;
        float[] from = paneGrows ? laneRectOnScreen(lane) : null;
        hideForExpand(cabinColumn);
        expandedPreview = target;
        expandedCameraKey = null;
        expandedFrom = from;
        compositeContainer.focusLane(lane, !paneGrows);
        if (paneGrows) {
            expandAnimator.grow(target, from);
        }
        updateCompositeLabels();
    }

    /** 环视的某一格此刻在屏幕上的矩形 {left, top, width, height}；量不出来时为 null。 */
    private float[] laneRectOnScreen(int lane) {
        android.graphics.RectF bounds = new android.graphics.RectF();
        if (!compositeContainer.laneBounds(lane, bounds)) {
            return null;
        }
        float[] container = com.kooo.evcam.ui.PreviewExpandAnimator.screenRect(compositeContainer);
        return new float[]{container[0] + bounds.left, container[1] + bounds.top,
                bounds.width(), bounds.height()};
    }

    /** 座舱的某一路放大到整块预览区；已经放大了就还原。过渡从这一格此刻的位置长出来。 */
    private void toggleCabinExpanded(View pane, String cameraKey) {
        if (expandedPreview != null) {
            collapsePreview();
            return;
        }
        float[] from = com.kooo.evcam.ui.PreviewExpandAnimator.screenRect(pane);
        hideForExpand(findViewById(R.id.composite_wrapper));
        hideForExpand(findViewById(pane.getId() == R.id.pane_cabin_front
                ? R.id.pane_cabin_rear : R.id.pane_cabin_front));
        if (pane.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams params =
                    (android.view.ViewGroup.MarginLayoutParams) pane.getLayoutParams();
            expandedTopMargin = params.topMargin;
            params.topMargin = 0;
            pane.setLayoutParams(params);
        }
        expandedPreview = pane;
        expandedCameraKey = cameraKey;
        expandedFrom = from;
        setCabinFill(cameraKey, true);
        expandAnimator.grow(pane, from);
    }

    /** 只收起本来看得见的：本来就藏着的（这一路没配）还原时也不该冒出来。 */
    private void hideForExpand(View view) {
        if (view != null && view.getVisibility() == View.VISIBLE) {
            view.setVisibility(View.GONE);
            hiddenForExpand.add(view);
        }
    }

    /** 放回原样：先缩回放大前的位置，再把布局还原。 */
    private void collapsePreview() {
        View expanded = expandedPreview;
        if (expanded == null) {
            return;
        }
        if (collapsing) {
            // 收回的过渡还在跑时又点了一下：直接收完
            expandAnimator.finishNow();
            return;
        }
        // 只有环视的布局里，放大的是容器里的一格、布局没动过，由容器自己缩回格子
        boolean paneShrinks = expandedCameraKey != null || !hiddenForExpand.isEmpty();
        float[] to = expandedFrom;
        if (!paneShrinks || to == null) {
            finishCollapse(!paneShrinks);
            return;
        }
        collapsing = true;
        expandAnimator.shrink(expanded, to, () -> finishCollapse(false));
    }

    /**
     * 还原布局。
     *
     * @param gridAnimates 只有环视的布局里为 true：由容器让那一格缩回格子
     */
    private void finishCollapse(boolean gridAnimates) {
        View expanded = expandedPreview;
        String cameraKey = expandedCameraKey;
        collapsing = false;
        if (expanded == null) {
            return;
        }
        // 先清状态再恢复形状：applyPreviewSizeTransform 会看 expandedCameraKey，
        // 不先清的话它还会按「放大中」把视图摆成铺满
        expandedPreview = null;
        expandedCameraKey = null;
        expandedFrom = null;
        for (View view : hiddenForExpand) {
            view.setVisibility(View.VISIBLE);
        }
        hiddenForExpand.clear();
        if (cameraKey != null) {
            if (expandedTopMargin >= 0 && expanded != null && expanded.getLayoutParams()
                    instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams params =
                        (android.view.ViewGroup.MarginLayoutParams) expanded.getLayoutParams();
                params.topMargin = expandedTopMargin;
                expanded.setLayoutParams(params);
            }
            setCabinFill(cameraKey, false);
        } else if (compositeContainer != null) {
            compositeContainer.showGrid(gridAnimates);
            updateCompositeLabels();
        }
        expandedTopMargin = -1;
    }

    /**
     * 座舱那一路放大时铺满，还原时回到配置里的摆法。
     *
     * <p>要动两层：视图的形状（AutoFitTextureView 会先把自己缩成画面的形状）和
     * 矩阵（SingleCamera 按「适应 / 填充」算）。只改矩阵是顶不满的 ——
     * 0.47.x 那两次「填充不顶满」就是卡在视图那一层。</p>
     */
    private void setCabinFill(String cameraKey, boolean fill) {
        com.kooo.evcam.camera.SingleCamera camera =
                cameraManager == null ? null : cameraManager.getCamera(cameraKey);
        if (camera != null) {
            camera.setFitOverride(fill ? com.kooo.evcam.profile.LaneLayout.FILL : null);
        }
        AutoFitTextureView view = "back".equals(cameraKey) ? textureBack : textureLeft;
        if (view == null) {
            return;
        }
        if (fill) {
            view.setAspectRatio(0, 0);
        } else if (camera != null && camera.getPreviewSize() != null) {
            applyPreviewSizeTransform(cameraKey, view, camera.getPreviewSize());
        }
    }

    /** 单画面模式下只留一个角标，四宫格模式下四个都显示。 */
    private void updateCompositeLabels() {
        if (compositeContainer == null) {
            return;
        }
        boolean grid = compositeContainer.getDisplayMode()
                == com.kooo.evcam.zeekr.FourLaneContainer.DisplayMode.GRID;
        TextView[] labels = {
                findViewById(R.id.label_front),
                findViewById(R.id.label_back),
                findViewById(R.id.label_left),
                findViewById(R.id.label_right),
        };
        int[] order = compositeContainer.getLaneOrder();
        int focused = compositeContainer.getFocusedLane();
        for (int cell = 0; cell < labels.length; cell++) {
            // 同上：座舱那两路的角标不归单画面 / 四宫格管
            if (labels[cell] == null
                    || labels[cell].getParent() != compositeContainer.getParent()) {
                continue;
            }
            // 三路布局里的「环视」在放大某一格时收起：那时这一块占满画面，
            // 它的右上角正好是录制角标的位置
            boolean visible = grid
                    || (order[cell] == focused && findViewById(R.id.cabin_column) == null);
            labels[cell].setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /** 把合成流的识别结果显示在画面底部，方便用户和排查问题时确认。 */
    private void updateCompositeInfoOverlay(String text) {
        if (tvCompositeInfo == null || text == null) {
            return;
        }
        String oneLine = text.trim().replace('\n', ' ');
        runOnUiThread(() -> {
            tvCompositeInfo.setText(oneLine);
            tvCompositeInfo.setVisibility(oneLine.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        });
    }


    /**
     * 抽屉里的「超级后视镜」开关。
     *
     * <p>和设置里那个开关走同一条路（{@code OverlayCoordinator.setRearViewEnabled}）：
     * 悬浮窗权限不够时它会返回 false，这时去申请权限，开关停在原位 ——
     * 开关显示的必须是后视镜真实的状态，不是「刚才按了一下」。</p>
     */
    private void toggleRearViewFromDrawer() {
        boolean on = !appConfig.isRearViewEnabled();
        if (!OverlayCoordinator.setRearViewEnabled(this, on)) {
            Toast.makeText(this, R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
            WakeUpHelper.requestOverlayPermission(this);
        } else if (on) {
            Toast.makeText(this, R.string.msg_rearview_on, Toast.LENGTH_SHORT).show();
            com.kooo.evcam.ui.RearViewGuide.showOnce(this);
        }
        syncRearViewSwitch();
    }

    /** 抽屉里的悬浮按钮开关，和后视镜那一行表现一致。 */
    private void toggleFloatingButtonFromDrawer() {
        boolean on = !appConfig.isRecordingFloatingEnabled();
        if (!OverlayCoordinator.setRecordButtonEnabled(this, on)) {
            Toast.makeText(this, R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
            WakeUpHelper.requestOverlayPermission(this);
        } else if (on) {
            broadcastCurrentRecordingState();
        }
        syncRearViewSwitch();
    }

    /** 行尾那两个开关照各自的真实状态摆。 */
    private void syncRearViewSwitch() {
        if (navigationView == null || appConfig == null) {
            return;
        }
        syncNavSwitch(R.id.nav_rearview, appConfig.isRearViewEnabled());
        syncNavSwitch(R.id.nav_floating_button, appConfig.isRecordingFloatingEnabled());
    }

    private void syncNavSwitch(int itemId, boolean on) {
        android.view.MenuItem item = navigationView.getMenu().findItem(itemId);
        View action = item != null ? item.getActionView() : null;
        View toggle = action != null ? action.findViewById(R.id.nav_switch) : null;
        if (toggle instanceof android.widget.CompoundButton) {
            ((android.widget.CompoundButton) toggle).setChecked(on);
        }
    }

    /**
     * 补盲和超视只在开发者选项打开时出现在抽屉里。
     *
     * <p>每次拉开抽屉都对一次：开发者选项是不持久化的，重启就关，
     * 菜单得跟着它走，不能只在启动时判断一次。</p>
     */
    private void syncDeveloperMenuVisibility() {
        if (navigationView == null) {
            return;
        }
        boolean unlocked = com.kooo.evcam.settings.DeveloperMode.isUnlocked();
        android.view.Menu menu = navigationView.getMenu();
        android.view.MenuItem blindSpot = menu.findItem(R.id.nav_secondary_display);
        if (blindSpot != null) {
            blindSpot.setVisible(unlocked);
        }
        android.view.MenuItem supervision = menu.findItem(R.id.nav_supervision_mode);
        if (supervision != null) {
            supervision.setVisible(unlocked);
        }
    }
}
