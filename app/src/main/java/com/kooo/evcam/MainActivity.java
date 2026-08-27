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

import com.google.android.material.navigation.NavigationView;
import com.kooo.evcam.camera.ImageAdjustManager;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.FileTransferManager;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.playback.PlaybackFragmentNew;
import com.kooo.evcam.playback.PhotoPlaybackFragmentNew;
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
    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 100;
    
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
    private FisheyeCorrectionFloatingWindow fisheyeCorrectionFloatingWindow;

    // 调试信息覆盖层（连点5下空白处显示）
    private TextView tvDebugOverlay;
    private boolean debugOverlayVisible = false;
    private final android.os.Handler debugUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable debugUpdateRunnable;
    private int debugTapCount = 0;
    private long debugLastTapTime = 0;
    private static final int DEBUG_TAP_COUNT = 5;
    private static final long DEBUG_TAP_INTERVAL_MS = 800;  // 连续点击的最大间隔

    private Button btnStartRecord, btnExit, btnTakePhoto;
    private MultiCameraManager cameraManager;

    public MultiCameraManager getCameraManager() {
        if (cameraManager == null) {
            cameraManager = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
        }
        return cameraManager;
    }
    private ImageAdjustManager imageAdjustManager;  // 亮度/降噪调节管理器
    private ImageAdjustFloatingWindow imageAdjustFloatingWindow;  // 亮度/降噪调节悬浮窗
    private int textureReadyCount = 0;  // 记录准备好的TextureView数量
    private int requiredTextureCount = 4;  // 需要准备好的TextureView数量（根据摄像头数量）
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
    private boolean autoStartRecordingTriggered = false;  // 标记自动录制是否已触发（避免重复触发）
    private boolean isAutoRecordingPending = false;  // 标记自动录制已计划但尚未开始（防止 onPause 关闭摄像头）
    
    // 自动录制定时检查相关
    private boolean isManuallyStoppedRecording = false;  // 用户是否手动停止了录制（手动停止后不自动恢复）
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
    private android.os.Handler screenStateHandler;  // 息屏/亮屏延迟处理
    private Runnable screenOffStopRunnable;  // 息屏停止录制的延迟任务
    private Runnable screenOnStartRunnable;  // 亮屏恢复录制的延迟任务
    private Runnable screenOffBackgroundRunnable;  // 息屏退后台的延迟任务
    private boolean isScreenOff = false;  // 当前是否息屏
    private boolean wasRecordingBeforeScreenOff = false;  // 息屏前是否正在录制
    private static final long SCREEN_OFF_DELAY_MS = 10000;  // 息屏后等待10秒（停止录制）
    private static final long SCREEN_ON_DELAY_MS = 10000;   // 亮屏后等待10秒（恢复录制）
    private static final long SCREEN_OFF_BACKGROUND_DELAY_MS = 15000;  // 息屏后等待15秒（退后台）
    
    
    // 车型配置相关
    private AppConfig appConfig;
    private int configuredCameraCount = 4;  // 配置的摄像头数量
    private CustomLayoutManager customLayoutManager;  // 自定义车型布局管理器

    // 录制按钮闪烁动画相关
    private android.os.Handler blinkHandler;
    private Runnable blinkRunnable;
    private boolean isBlinking = false;

    // 录制状态显示相关
    private TextView tvRecordingStats;
    private android.os.Handler recordingTimerHandler;

    private Runnable recordingTimerRunnable;
    private long recordingStartTime = 0;  // 录制开始时间
    private int currentSegmentCount = 1;  // 当前分段数
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










  // 待处理的飞书 Chat ID

    
    // 存储清理管理器
    private StorageCleanupManager storageCleanupManager;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;  // 设置静态实例引用
        AppLog.init(this);

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


        // 启动定时保活任务（车机必需，始终开启）
        KeepAliveManager.startKeepAliveWork(this);
        AppLog.d(TAG, "定时保活任务已启动");
        
        // 防止休眠（仅当开启"开机自启动"时）
        // WakeLock 主要在 CameraForegroundService 中维护
        // 这里作为备份，确保 Activity 存在时也有 WakeLock
        if (appConfig.isAutoStartOnBoot()) {
            WakeUpHelper.acquirePersistentWakeLock(this);
            AppLog.d(TAG, "WakeLock 已获取（开机自启动已开启）");
        } else {
            AppLog.d(TAG, "WakeLock 未获取（开机自启动未开启）");
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
                            Toast.makeText(this, "摄像头未准备好，请重试", Toast.LENGTH_SHORT).show();
                        }
                    }, 2000);
                }
            }, 1000);
        }

// 启动悬浮窗服务（如果已启用）
        if (appConfig.isFloatingWindowEnabled() && WakeUpHelper.hasOverlayPermission(this)) {
            FloatingWindowService.start(this);
            AppLog.d(TAG, "悬浮窗服务已启动");
            
            // 延迟发送当前状态（等待服务启动完成）
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                // 发送当前录制状态
                broadcastCurrentRecordingState();
                // 应用在前台，隐藏悬浮窗
                FloatingWindowService.sendAppForegroundState(this, true);
            }, 500);
        }

        // 启动录制悬浮按钮服务（如果已启用，默认开启）
        if (appConfig.isRecordingFloatingEnabled() && WakeUpHelper.hasOverlayPermission(this)) {
            Intent intent = new Intent(this, com.kooo.evcam.service.RecordingFloatingService.class);
            intent.setAction(com.kooo.evcam.service.RecordingFloatingService.ACTION_SHOW);
            startService(intent);
            AppLog.d(TAG, "录制悬浮按钮服务已启动");
        }

        // 启动补盲选项服务 (副屏/主屏悬浮窗/转向灯联动/模拟按钮/全景避让)
        // 定制键唤醒独立于补盲全局开关，单独判断
        if ((appConfig.isBlindSpotGlobalEnabled()
                && (appConfig.isSecondaryDisplayEnabled() || appConfig.isMainFloatingEnabled()
                    || appConfig.isTurnSignalLinkageEnabled() || appConfig.isMockTurnSignalFloatingEnabled()
                    || appConfig.isAvmAvoidanceEnabled()))
                || appConfig.isCustomKeyWakeupEnabled()) {
            BlindSpotService.update(this);
            AppLog.d(TAG, "补盲选项服务已启动");
        }
        
        // 初始化息屏录制检测
        initScreenStateReceiver();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        AppLog.d(TAG, "onNewIntent called");

// 处理从录制悬浮按钮启动（需要自动开始录制）
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
                            Toast.makeText(this, "摄像头未准备好，请重试", Toast.LENGTH_SHORT).show();
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
    private void setupLayoutByCarModel() {
        // 默认使用4摄像头布局（银河E5专用）
        int layoutId = R.layout.activity_main;
        configuredCameraCount = 4;
        requiredTextureCount = 4;

        String carModel = appConfig.getCarModel();
        
        // 银河E5-多按钮：横屏布局，左侧按钮列表
        if (AppConfig.CAR_MODEL_E5_MULTI.equals(carModel)) {
            layoutId = R.layout.activity_main_e5_multi;
            configuredCameraCount = 4;
            requiredTextureCount = 4;
            AppLog.d(TAG, "使用银河E5-多按钮配置：横屏左侧按钮列表布局");
        }
        // 银河L6/L7：竖屏四宫格布局
        else if (AppConfig.CAR_MODEL_L7.equals(carModel)) {
            layoutId = R.layout.activity_main_l7;
            configuredCameraCount = 4;
            requiredTextureCount = 4;
            AppLog.d(TAG, "使用银河L6/L7配置：竖屏四宫格布局");
        }
        // 银河L7-多按钮：竖屏四宫格布局（顶部多功能按钮）
        else if (AppConfig.CAR_MODEL_L7_MULTI.equals(carModel)) {
            layoutId = R.layout.activity_main_l7_multi;
            configuredCameraCount = 4;
            requiredTextureCount = 4;
            AppLog.d(TAG, "使用银河L7-多按钮配置：竖屏四宫格+顶部快捷按钮布局");
        }
        // 手机：自适应2摄像头布局
        else if (AppConfig.CAR_MODEL_PHONE.equals(carModel)) {
            layoutId = R.layout.activity_main_phone;
            configuredCameraCount = 2;
            requiredTextureCount = 2;
            AppLog.d(TAG, "使用手机配置：自适应2摄像头布局");
        }
        // 26款星舰7：横屏四摄像头布局（基于银河E5布局）
        else if (AppConfig.CAR_MODEL_XINGHAN_7.equals(carModel)) {
            layoutId = R.layout.activity_main;
            configuredCameraCount = 4;
            requiredTextureCount = 4;
            AppLog.d(TAG, "使用26款星舰7配置：横屏4摄像头布局");
        }
        // 银河A7：横屏四摄像头布局（沿用银河E5）
        else if (AppConfig.CAR_MODEL_GALAXY_A7.equals(carModel)) {
            layoutId = R.layout.activity_main;
            configuredCameraCount = 4;
            requiredTextureCount = 4;
            AppLog.d(TAG, "使用银河A7配置：横屏4摄像头布局（沿用E5）");
        }
        // 极氪7X：一路四联合成流，由 FourLaneContainer 把单个 TextureView 重画成四宫格
        else if (AppConfig.CAR_MODEL_ZEEKR_7X.equals(carModel)) {
            layoutId = R.layout.activity_main_zeekr_7x;
            configuredCameraCount = 1;
            requiredTextureCount = 1;
            AppLog.d(TAG, "使用极氪7X配置：单路合成流 + 四宫格拆分");
        }
        // 极氪7X 多路：环视合成流 + 两路座舱摄像头
        else if (AppConfig.CAR_MODEL_ZEEKR_7X_MULTI.equals(carModel)) {
            layoutId = R.layout.activity_main_zeekr_7x_multi;
            configuredCameraCount = 3;
            requiredTextureCount = 3;
            AppLog.d(TAG, "使用极氪7X多路配置：环视合成流 + 2 路座舱");
        }
        // 多视角布局：自定义布局 + 圆角UI + 车辆控制
        else if (appConfig.isMultiviewCarModel()) {
            layoutId = R.layout.activity_main_multiview;
            configuredCameraCount = appConfig.getCameraCount();
            requiredTextureCount = configuredCameraCount;
            AppLog.d(TAG, "使用多视角布局：" + configuredCameraCount + "摄像头");
        }
        // 自定义车型：使用统一的自定义布局（支持自由操控）
        else if (appConfig.isCustomCarModel()) {
            layoutId = R.layout.activity_main_custom;
            configuredCameraCount = appConfig.getCameraCount();
            requiredTextureCount = configuredCameraCount;
            AppLog.d(TAG, "使用自定义车型布局：" + configuredCameraCount + "摄像头");
        }
        // 银河E5：横屏四摄像头布局
        else {
            AppLog.d(TAG, "使用银河E5默认配置：4摄像头布局");
        }

        setContentView(layoutId);
    }

    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 设置状态栏颜色为菜单栏背景色
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.menu_background));

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
        
        // 仅针对手机布局添加沉浸式状态栏兼容
        String carModel = appConfig.getCarModel();
        if (AppConfig.CAR_MODEL_PHONE.equals(carModel)) {
            View mainLayout = findViewById(R.id.main);
            if (mainLayout != null) {
                final int originalPaddingTop = mainLayout.getPaddingTop();
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mainLayout, (v, insets) -> {
                    int statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                    v.setPadding(v.getPaddingLeft(), statusBarHeight + originalPaddingTop, v.getPaddingRight(), v.getPaddingBottom());
                    return insets;
                });
                androidx.core.view.ViewCompat.requestApplyInsets(mainLayout);
            }
        }
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        recordingLayout = findViewById(R.id.main);
        fragmentContainer = findViewById(R.id.fragment_container);
        
        // 设置导航头部版本号
        if (navigationView != null) {
            View headerView = navigationView.getHeaderView(0);
            if (headerView != null) {
                TextView versionText = headerView.findViewById(R.id.nav_header_version);
                if (versionText != null) {
                    try {
                        String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                        versionText.setText("版本：v" + versionName);
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
            tvCompositeInfo = findViewById(R.id.tv_composite_info);
            setupCompositeControls();
        }
        
        btnStartRecord = findViewById(R.id.btn_start_record);
        btnExit = findViewById(R.id.btn_exit);
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
        
        // E5-多按钮布局的快捷导航按钮
        View btnPlayback = findViewById(R.id.btn_playback);
        if (btnPlayback != null) {
            btnPlayback.setOnClickListener(v -> showPlaybackInterface());
        }
        
        View btnPhotos = findViewById(R.id.btn_photos);
        if (btnPhotos != null) {
            btnPhotos.setOnClickListener(v -> showPhotoPlaybackInterface());
        }

        // 录制按钮：点击切换录制状态
        btnStartRecord.setOnClickListener(v -> toggleRecording());

        // 退出按钮：完全退出应用
        btnExit.setOnClickListener(v -> exitApp());

        btnTakePhoto.setOnClickListener(v -> takePicture());

        if (textureFront != null) {
            textureFront.setSurfaceTextureListener(buildSurfaceListener("front"));
        }
        if (textureBack != null && configuredCameraCount >= 2) {
            textureBack.setSurfaceTextureListener(buildSurfaceListener("back"));
        }
        if (textureLeft != null && configuredCameraCount >= 4) {
            textureLeft.setSurfaceTextureListener(buildSurfaceListener("left"));
        }
        if (textureRight != null && configuredCameraCount >= 4) {
            textureRight.setSurfaceTextureListener(buildSurfaceListener("right"));
        }
    }

    private TextureView.SurfaceTextureListener buildSurfaceListener(String cameraKey) {
        return new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                textureReadyCount++;
                AppLog.d(TAG, "TextureView " + cameraKey + " ready: " + textureReadyCount + "/" + requiredTextureCount);

                if (textureReadyCount >= requiredTextureCount && checkPermissions()) {
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
            updateCameraLabel(labelFront, appConfig.getCameraName("front"));
        }
        if (labelBack != null && configuredCameraCount >= 2) {
            updateCameraLabel(labelBack, appConfig.getCameraName("back"));
        }
        if (labelLeft != null && configuredCameraCount >= 4) {
            updateCameraLabel(labelLeft, appConfig.getCameraName("left"));
        }
        if (labelRight != null && configuredCameraCount >= 4) {
            updateCameraLabel(labelRight, appConfig.getCameraName("right"));
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
        android.widget.FrameLayout frameVehicleControl = findViewById(R.id.frame_vehicle_control);
        View editControls = findViewById(R.id.edit_controls);
        View containerCameras = findViewById(R.id.container_cameras);
        
        // 按钮容器根据方向选择
        String buttonOrientation = appConfig.getCustomButtonOrientation();
        boolean isVertical = AppConfig.BUTTON_ORIENTATION_VERTICAL.equals(buttonOrientation);
        android.view.ViewGroup buttonContainer = isVertical ? 
            findViewById(R.id.container_buttons_left) : 
            findViewById(R.id.container_buttons_bottom);

        // 根据摄像头数量隐藏不需要的容器
        if (configuredCameraCount < 4) {
            if (frameLeft != null) frameLeft.setVisibility(View.GONE);
            if (frameRight != null) frameRight.setVisibility(View.GONE);
            if (frameVehicleControl != null) frameVehicleControl.setVisibility(View.GONE);
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
                frameFront, frameBack, frameLeft, frameRight, frameVehicleControl,
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

        // 前摄像头开关 - 控制画面显示和录制
        MacOSToggleButton toggleFront = findViewById(R.id.toggle_front);
        if (toggleFront != null) {
            boolean frontEnabled = appConfig.isRecordingCameraEnabled("front");
            toggleFront.setChecked(frontEnabled);
            // 初始化时设置画面可见性（只隐藏CardView，不隐藏整个frame）
            setCameraFrameVisible(frameFront, frontEnabled);
            toggleFront.setOnCheckedChangeListener((button, isChecked) -> {
                appConfig.setRecordingCameraEnabled("front", isChecked);
                setCameraFrameVisible(frameFront, isChecked);
                updateRequiredTextureCount();
                AppLog.d(TAG, "前摄像头开关: " + isChecked + ", 画面和录制: " + isChecked);
            });
        }

        // 后摄像头开关 - 控制画面显示和录制
        MacOSToggleButton toggleBack = findViewById(R.id.toggle_back);
        if (toggleBack != null) {
            boolean backEnabled = appConfig.isRecordingCameraEnabled("back");
            toggleBack.setChecked(backEnabled);
            // 初始化时设置画面可见性
            setCameraFrameVisible(frameBack, backEnabled);
            toggleBack.setOnCheckedChangeListener((button, isChecked) -> {
                appConfig.setRecordingCameraEnabled("back", isChecked);
                setCameraFrameVisible(frameBack, isChecked);
                updateRequiredTextureCount();
                AppLog.d(TAG, "后摄像头开关: " + isChecked + ", 画面和录制: " + isChecked);
            });
        }

        // 左摄像头开关 - 控制画面显示和录制
        MacOSToggleButton toggleLeft = findViewById(R.id.toggle_left);
        if (toggleLeft != null) {
            boolean leftEnabled = appConfig.isRecordingCameraEnabled("left");
            toggleLeft.setChecked(leftEnabled);
            // 初始化时设置画面可见性
            setCameraFrameVisible(frameLeft, leftEnabled);
            toggleLeft.setOnCheckedChangeListener((button, isChecked) -> {
                appConfig.setRecordingCameraEnabled("left", isChecked);
                setCameraFrameVisible(frameLeft, isChecked);
                updateRequiredTextureCount();
                AppLog.d(TAG, "左摄像头开关: " + isChecked + ", 画面和录制: " + isChecked);
            });
        }

        // 右摄像头开关 - 控制画面显示和录制
        MacOSToggleButton toggleRight = findViewById(R.id.toggle_right);
        if (toggleRight != null) {
            boolean rightEnabled = appConfig.isRecordingCameraEnabled("right");
            toggleRight.setChecked(rightEnabled);
            // 初始化时设置画面可见性
            setCameraFrameVisible(frameRight, rightEnabled);
            toggleRight.setOnCheckedChangeListener((button, isChecked) -> {
                appConfig.setRecordingCameraEnabled("right", isChecked);
                setCameraFrameVisible(frameRight, isChecked);
                updateRequiredTextureCount();
                AppLog.d(TAG, "右摄像头开关: " + isChecked + ", 画面和录制: " + isChecked);
            });
        }

        // 根据开关状态调整 requiredTextureCount
        updateRequiredTextureCount();

        setupCameraFrameTouchListeners(frameFront, frameBack, frameLeft, frameRight);

        AppLog.d(TAG, "摄像头开关初始化完成，requiredTextureCount=" + requiredTextureCount);
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

    private void showFullscreenPreview(String cameraPosition) {
        AppLog.d(TAG, "显示全屏预览: " + cameraPosition);

        currentFullscreenDialog = new FullscreenPreviewDialog(this, cameraPosition);
        currentFullscreenDialog.setOnParamsSavedListener((pos, k1, k2, zoom, cx, cy, rotation) -> {
            AppLog.d(TAG, "鱼眼参数已保存: " + pos + " k1=" + k1 + " k2=" + k2 + " zoom=" + zoom + " rotation=" + rotation);
        });
        currentFullscreenDialog.setOnDismissListener(dialog -> {
            currentFullscreenDialog = null;
        });
        currentFullscreenDialog.show();
    }

    /**
     * 根据可见的画面数量更新 requiredTextureCount
     */
    private void updateRequiredTextureCount() {
        int visibleCount = 0;
        if (appConfig.isRecordingCameraEnabled("front")) visibleCount++;
        if (appConfig.isRecordingCameraEnabled("back")) visibleCount++;
        if (appConfig.isRecordingCameraEnabled("left")) visibleCount++;
        if (appConfig.isRecordingCameraEnabled("right")) visibleCount++;
        
        // 至少需要一个可见的画面来初始化摄像头
        requiredTextureCount = Math.max(1, visibleCount);
        AppLog.d(TAG, "更新 requiredTextureCount: " + requiredTextureCount + " (可见画面: " + visibleCount + ")");
    }

    /**
     * 设置摄像头画面的可见性（只隐藏画面内容，保留开关可见）
     * @param frame 摄像头FrameLayout
     * @param visible 是否可见
     */
    private void setCameraFrameVisible(android.widget.FrameLayout frame, boolean visible) {
        if (frame == null) return;
        // 只隐藏第一个子View（CardView，包含画面内容）
        // 开关是第二个子View，保持可见
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            // 如果是CardView（画面内容），控制其可见性
            if (child instanceof androidx.cardview.widget.CardView) {
                child.setVisibility(visible ? View.VISIBLE : View.GONE);
                break; // 只处理第一个CardView
            }
        }
        
        // 当画面变为可见时，检查是否需要更新摄像头预览
        if (visible && cameraManager != null) {
            // 延迟一点等待TextureView准备好
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (cameraManager != null) {
                    cameraManager.updatePreviewTextureViews(textureFront, textureBack, textureLeft, textureRight);
                }
            }, 100);
        }
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
                Toast.makeText(this, "录制状态显示已开启", Toast.LENGTH_SHORT).show();
            } else {
                // 使用 alpha=0 隐藏，但保持 VISIBLE 状态以响应点击
                tvRecordingStats.setAlpha(0.0f);
                Toast.makeText(this, "录制状态显示已关闭", Toast.LENGTH_SHORT).show();
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
    }
    
    /**
     * 更新录制状态显示
     */
    private void updateRecordingStatsDisplay() {
        if (tvRecordingStats == null) {
            return;
        }
        
        // 计算录制时长
        long elapsedMs = System.currentTimeMillis() - recordingStartTime;
        long totalSeconds = elapsedMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        
        // 格式化时间：MM:SS / 分段数（即使隐藏也更新文本，便于双击显示时立即看到正确时间）
        String timeStr = String.format(java.util.Locale.getDefault(), "%02d:%02d / %d", minutes, seconds, currentSegmentCount);
        tvRecordingStats.setText(timeStr);
    }
    
    /**
     * 当分段切换时调用，更新分段计数
     */
    public void onSegmentSwitch(int newSegmentIndex) {
        currentSegmentCount = newSegmentIndex + 1;  // 分段索引从0开始，显示从1开始
        AppLog.d(TAG, "分段切换: 第 " + currentSegmentCount + " 段");
        
        // 立即更新显示
        runOnUiThread(this::updateRecordingStatsDisplay);
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
        navigationView.setNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();
            // 先清除所有菜单项的选中状态（处理跨组选中）
            clearAllNavigationChecks();
            
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
            } else if (itemId == R.id.nav_diagnostics) {
                startActivity(new Intent(this, com.kooo.evcam.zeekr.DiagnosticsActivity.class));
            } else if (itemId == R.id.nav_about) {
                startActivity(new Intent(this, com.kooo.evcam.zeekr.AboutActivity.class));
            }
            // 设置当前项为选中
            navigationView.setCheckedItem(itemId);
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
        if (appConfig == null || !appConfig.isFirstLaunch()) {
            return;
        }

        AppLog.d(TAG, "检测到首次启动，进入设置界面");

        // 标记首次启动已完成（在显示弹窗前标记，避免重复触发）
        appConfig.setFirstLaunchCompleted();

        // 延迟执行，确保 UI 完全初始化
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            // 进入设置界面
            showSettingsInterface();
            clearAllNavigationChecks();
            navigationView.setCheckedItem(R.id.nav_settings);

            // 显示引导弹窗
            showFirstLaunchGuideDialog();
        }, 300);
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
        // 清除所有Fragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        for (Fragment fragment : fragmentManager.getFragments()) {
            fragmentManager.beginTransaction().remove(fragment).commit();
        }

        // 显示录制布局，隐藏Fragment容器
        recordingLayout.setVisibility(View.VISIBLE);
        fragmentContainer.setVisibility(View.GONE);
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
        // 更新导航菜单选中状态（先清除所有选中，再设置当前项）
        if (navigationView != null) {
            clearAllNavigationChecks();
            navigationView.setCheckedItem(R.id.nav_recording);
        }
    }

    /**
     * 显示回看界面（新版四宫格界面）
     */
    private void showPlaybackInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示新版PlaybackFragment（支持四宫格预览）
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new PlaybackFragmentNew());
        transaction.commit();
    }

    /**
     * 显示图片回看界面（新版四宫格界面）
     */
    private void showPhotoPlaybackInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示新版PhotoPlaybackFragment（支持四宫格预览）
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new PhotoPlaybackFragmentNew());
        transaction.commit();
    }








    
    /**
     * 显示软件设置界面
     */
    private void showSettingsInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示SettingsFragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new SettingsFragment());
        transaction.commit();
    }

    /**
     * 显示补盲选项设置界面
     */
    private void showBlindSpotInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示BlindSpotSettingsFragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new BlindSpotSettingsFragment());
        transaction.commit();
    }

    /**
     * 切换超视模式
     * 超视模式会同时显示左右两个补盲悬浮窗
     */
    private void toggleSupervisionMode() {
        AppConfig appConfig = new AppConfig(this);
        boolean currentEnabled = appConfig.isSupervisionModeEnabled();
        boolean newEnabled = !currentEnabled;
        
        // 更新配置
        appConfig.setSupervisionModeEnabled(newEnabled);
        
        // 显示提示
        String message = newEnabled ? "超视模式已开启" : "超视模式已关闭";
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (checkPermissions()) {
                // 权限已授予，但需要等待TextureView准备好
                // 如果TextureView已经准备好，立即初始化摄像头
                if (textureReadyCount >= requiredTextureCount) {
                    initCamera();
                }
            } else {
                Toast.makeText(this, "需要相机和存储权限", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void initCamera() {
        // 确保所有需要的TextureView都准备好
        if (textureReadyCount < requiredTextureCount) {
            AppLog.w(TAG, "Not all TextureViews are ready yet: " + textureReadyCount + "/" + requiredTextureCount);
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
        if (existingManager != null) {
            // 后台已初始化，复用实例并绑定 TextureView
            AppLog.d(TAG, "复用后台已初始化的摄像头管理器，绑定 TextureView");
            cameraManager = existingManager;

            // --- 补全后台初始化时缺失的回调 ---
            // 后台（BlindSpotService）初始化的 MultiCameraManager 没有设置 MainActivity 的回调，
            // 必须在此处设置，否则左右摄像头旋转变换、录制计时等功能不正常。

            // 摄像头状态回调
            cameraManager.setStatusCallback((cameraId, status) -> {
                AppLog.d(TAG, "摄像头 " + cameraId + ": " + status);
                if (status.contains("错误") || status.contains("断开")) {
                    runOnUiThread(() -> {
                        if (status.contains("ERROR_CAMERA_IN_USE") || status.contains("DISCONNECTED")) {
                            Toast.makeText(MainActivity.this,
                                "摄像头 " + cameraId + " 被占用，正在自动重连...",
                                Toast.LENGTH_SHORT).show();
                        } else if (status.contains("max reconnect attempts")) {
                            Toast.makeText(MainActivity.this,
                                "摄像头 " + cameraId + " 重连失败，请手动重启应用",
                                Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });

            // 分段切换回调
            cameraManager.setSegmentSwitchCallback(newSegmentIndex -> {
                onSegmentSwitch(newSegmentIndex);
            });

            // 损坏文件删除回调
            cameraManager.setCorruptedFilesCallback(deletedFiles -> {
                showCorruptedFilesDeletedDialog(deletedFiles);
            });

            // Codec 回退通知回调
            cameraManager.setCodecFallbackCallback(() -> {
                runOnUiThread(() -> {
                    Toast.makeText(this,
                        "录制故障，已回退到MediaCodec模式，如果频繁故障请手动更改录制模式",
                        Toast.LENGTH_LONG).show();
                });
            });

            // 录制时间戳更新回调
            cameraManager.setTimestampUpdateCallback(newTimestamp -> {
                if (isRemoteRecording && remoteRecordingTimestamp != null) {
                    AppLog.d(TAG, "远程录制时间戳更新: " + remoteRecordingTimestamp + " -> " + newTimestamp);
                    remoteRecordingTimestamp = newTimestamp;
                }
            });

            // 录制状态回调（监听录制成功或失败）
            cameraManager.setRecordingStatusCallback((activeCameras, failedCameras) -> {
                AppLog.d(TAG, "录制状态回调: 成功=" + activeCameras.size() + ", 失败=" + failedCameras.size());
                if (activeCameras.isEmpty()) {
                    // 所有摄像头都启动失败
                    runOnUiThread(() -> {
                        AppLog.e(TAG, "所有摄像头启动录制失败");
                        isRecording = false;
                        isAutoRecordingPending = false;
                        isPreparingRecording = false;
                        hidePreparingIndicator();
                        Toast.makeText(this, "录制启动失败，请重试", Toast.LENGTH_SHORT).show();
                    });
                }
            });

            // 首次数据写入回调（录制计时器依赖此回调）
            cameraManager.setFirstDataWrittenCallback(() -> {
                AppLog.d(TAG, "收到首次数据写入回调，录制已真正开始");
                runOnUiThread(() -> {
                    if (isPreparingRecording) {
                        isPreparingRecording = false;
                        hidePreparingIndicator();
                        AppLog.d(TAG, "准备状态结束，录制进入正常状态");
                    }
                    if (isRecording && !isRemoteRecording) {
                        if (shouldResumeRecordingAfterRecreate && savedRecordingStartTime > 0) {
                            startRecordingTimer(savedRecordingStartTime, savedSegmentCount);
                            AppLog.d(TAG, "主题切换后恢复录制计时器（首次写入后）");
                            shouldResumeRecordingAfterRecreate = false;
                            savedRecordingStartTime = 0;
                            savedSegmentCount = 1;
                        } else {
                            startRecordingTimer();
                            AppLog.d(TAG, "手动录制计时器已启动（首次写入后）");
                        }
                    }
                    if (isRemoteRecording && pendingRemoteDurationSeconds > 0) {
                        AppLog.d(TAG, "远程录制首次写入成功，启动 " + pendingRemoteDurationSeconds + " 秒定时器");
                        autoStopHandler.postDelayed(autoStopRunnable, pendingRemoteDurationSeconds * 1000L);
                        pendingRemoteDurationSeconds = 0;
                    }
                });
            });

            // 预览尺寸回调（关键：负责左右摄像头旋转变换）
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

            // 绑定 TextureView
            cameraManager.updatePreviewTextureViews(textureFront, textureBack, textureLeft, textureRight);

            // 打开所有摄像头（后台初始化时仅创建了对象，可能只打开了补盲所需的单个摄像头）
            // 主界面需要所有摄像头画面，已打开的摄像头会被 openCamera 内部的防重复检查跳过
            cameraManager.openAllCameras();

            // 手动触发 previewSizeCallback（摄像头可能已在补盲阶段打开并确定了预览尺寸）
            cameraManager.firePreviewSizeCallbacks();

            // 初始化亮度/降噪调节管理器
            imageAdjustManager = new ImageAdjustManager(this);
            registerCamerasToImageAdjustManager();
            AppLog.d(TAG, "Camera initialized with " + configuredCameraCount + " cameras (reused from background)");
            checkResumeRecordingAfterRecreate();
            checkAutoStartRecording();
            startAutoRecordingCheck();
            return;
        }

        cameraManager = new MultiCameraManager(this);
        cameraManager.setMaxOpenCameras(configuredCameraCount);
        // 注册到全局 Holder
        holder.setCameraManager(cameraManager);
        
        // 初始化亮度/降噪调节管理器
        imageAdjustManager = new ImageAdjustManager(this);

        // 设置摄像头状态回调
        cameraManager.setStatusCallback((cameraId, status) -> {
            AppLog.d(TAG, "摄像头 " + cameraId + ": " + status);

            // 如果摄像头断开或被占用，提示用户
            if (status.contains("错误") || status.contains("断开")) {
                runOnUiThread(() -> {
                    if (status.contains("ERROR_CAMERA_IN_USE") || status.contains("DISCONNECTED")) {
                        Toast.makeText(MainActivity.this,
                            "摄像头 " + cameraId + " 被占用，正在自动重连...",
                            Toast.LENGTH_SHORT).show();
                    } else if (status.contains("max reconnect attempts")) {
                        Toast.makeText(MainActivity.this,
                            "摄像头 " + cameraId + " 重连失败，请手动重启应用",
                            Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        // 设置分段切换回调
        cameraManager.setSegmentSwitchCallback(newSegmentIndex -> {
            onSegmentSwitch(newSegmentIndex);
        });

        // 设置损坏文件删除回调
        cameraManager.setCorruptedFilesCallback(deletedFiles -> {
            showCorruptedFilesDeletedDialog(deletedFiles);
        });

        // 设置 Codec 回退通知回调
        cameraManager.setCodecFallbackCallback(() -> {
            runOnUiThread(() -> {
                Toast.makeText(this, 
                    "录制故障，已回退到MediaCodec模式，如果频繁故障请手动更改录制模式", 
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
                    isRecording = false;
                    isAutoRecordingPending = false;
                    isPreparingRecording = false;
                    hidePreparingIndicator();
                    Toast.makeText(this, "录制启动失败，请重试", Toast.LENGTH_SHORT).show();
                });
            }
        });

        // 设置首次数据写入回调
        // 用于在摄像头真正开始输出数据后启动计时器（分段计时、钉钉录制计时等）
        cameraManager.setFirstDataWrittenCallback(() -> {
            AppLog.d(TAG, "收到首次数据写入回调，录制已真正开始");
            runOnUiThread(() -> {
                // 结束"准备中"状态
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

        // 等待TextureView准备好
        textureFront.post(() -> {
            try {
                // 检测可用的摄像头
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String[] cameraIds = cm.getCameraIdList();

                AppLog.d(TAG, "========== 摄像头诊断信息 ==========");
                AppLog.d(TAG, "Available cameras: " + cameraIds.length);

                for (String id : cameraIds) {
                    AppLog.d(TAG, "---------- Camera ID: " + id + " ----------");

                    try {
                        android.hardware.camera2.CameraCharacteristics characteristics = cm.getCameraCharacteristics(id);

                        // 打印摄像头方向
                        Integer facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                        String facingStr = "UNKNOWN";
                        if (facing != null) {
                            switch (facing) {
                                case android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT:
                                    facingStr = "FRONT";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK:
                                    facingStr = "BACK";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL:
                                    facingStr = "EXTERNAL";
                                    break;
                            }
                        }
                        AppLog.d(TAG, "  Facing: " + facingStr);

                        // 打印支持的输出格式和分辨率
                        android.hardware.camera2.params.StreamConfigurationMap map =
                            characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                        if (map != null) {
                            // 打印 ImageFormat.PRIVATE 的分辨率
                            android.util.Size[] privateSizes = map.getOutputSizes(android.graphics.ImageFormat.PRIVATE);
                            if (privateSizes != null && privateSizes.length > 0) {
                                AppLog.d(TAG, "  PRIVATE formats (" + privateSizes.length + " sizes):");
                                for (int i = 0; i < Math.min(privateSizes.length, 5); i++) {
                                    AppLog.d(TAG, "    [" + i + "] " + privateSizes[i].getWidth() + "x" + privateSizes[i].getHeight());
                                }
                                if (privateSizes.length > 5) {
                                    AppLog.d(TAG, "    ... and " + (privateSizes.length - 5) + " more");
                                }
                            }

                            // 打印 ImageFormat.YUV_420_888 的分辨率
                            android.util.Size[] yuvSizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
                            if (yuvSizes != null && yuvSizes.length > 0) {
                                AppLog.d(TAG, "  YUV_420_888 formats (" + yuvSizes.length + " sizes):");
                                for (int i = 0; i < Math.min(yuvSizes.length, 5); i++) {
                                    AppLog.d(TAG, "    [" + i + "] " + yuvSizes[i].getWidth() + "x" + yuvSizes[i].getHeight());
                                }
                                if (yuvSizes.length > 5) {
                                    AppLog.d(TAG, "    ... and " + (yuvSizes.length - 5) + " more");
                                }
                            }

                            // 打印 SurfaceTexture 的分辨率
                            android.util.Size[] textureSizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                            if (textureSizes != null && textureSizes.length > 0) {
                                AppLog.d(TAG, "  SurfaceTexture formats (" + textureSizes.length + " sizes):");
                                for (int i = 0; i < Math.min(textureSizes.length, 5); i++) {
                                    AppLog.d(TAG, "    [" + i + "] " + textureSizes[i].getWidth() + "x" + textureSizes[i].getHeight());
                                }
                                if (textureSizes.length > 5) {
                                    AppLog.d(TAG, "    ... and " + (textureSizes.length - 5) + " more");
                                }
                            }
                        } else {
                            AppLog.w(TAG, "  StreamConfigurationMap is NULL!");
                        }

                        // 打印硬件级别
                        Integer hwLevel = characteristics.get(android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                        String hwLevelStr = "UNKNOWN";
                        if (hwLevel != null) {
                            switch (hwLevel) {
                                case android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                                    hwLevelStr = "LEGACY";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                                    hwLevelStr = "LIMITED";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                                    hwLevelStr = "FULL";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                                    hwLevelStr = "LEVEL_3";
                                    break;
                            }
                        }
                        AppLog.d(TAG, "  Hardware Level: " + hwLevelStr);

                    } catch (Exception e) {
                        AppLog.e(TAG, "  Error getting characteristics for camera " + id + ": " + e.getMessage());
                    }
                }

                AppLog.d(TAG, "========================================");

                // 根据车型配置初始化摄像头
                String carModel = appConfig.getCarModel();
                if (AppConfig.CAR_MODEL_L7.equals(carModel) || AppConfig.CAR_MODEL_L7_MULTI.equals(carModel)) {
                    // 银河L6/L7 / L7-多按钮：使用固定映射
                    initCamerasForL7(cameraIds);
                } else if (AppConfig.CAR_MODEL_PHONE.equals(carModel)) {
                    // 手机模式：2摄像头（前+后）
                    initCamerasForPhone(cameraIds);
                } else if (AppConfig.CAR_MODEL_XINGHAN_7.equals(carModel)) {
                    // 26款星舰7：使用固定映射（前3后2左4右1）
                    initCamerasForXinghan7(cameraIds);
                } else if (AppConfig.CAR_MODEL_GALAXY_A7.equals(carModel)) {
                    // 银河A7：沿用银河E5固定映射
                    initCamerasForGalaxyE5(cameraIds);
                } else if (AppConfig.CAR_MODEL_ZEEKR_7X.equals(carModel)) {
                    // 极氪7X：按能力查找提供合成流的那一路相机
                    initCamerasForZeekrComposite(cm, cameraIds);
                } else if (AppConfig.CAR_MODEL_ZEEKR_7X_MULTI.equals(carModel)) {
                    // 极氪7X 多路：合成流 + 其余两路座舱
                    initCamerasForZeekrMulti(cm, cameraIds);
                } else if (appConfig.needsCustomLayoutManager()) {
                    // 自定义车型/多视角：使用用户配置的摄像头映射
                    initCamerasForCustomModel(cameraIds);
                } else {
                    // 银河E5：使用固定映射
                    initCamerasForGalaxyE5(cameraIds);
                }
                
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
                Toast.makeText(this, "摄像头访问失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
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
            // 让相机精确选中这个尺寸，而不是回退到最接近 1280x800 的那个
            appConfig.setTargetResolution(
                    located.size.getWidth() + "x" + located.size.getHeight());
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

        updateCompositeInfoOverlay(located.diagnostics);

        if (cameraId != null) {
            cameraManager.initCameras(cameraId, textureFront, null, null, null, null, null, null);
        }
    }

    /**
     * 极氪7X 多路：环视合成流 + 两路座舱摄像头。
     *
     * <p>合成流那一路仍然按能力查找；剩下的相机按 id 顺序补进「座舱 1 / 座舱 2」。
     * 哪一路是后座、哪一路是主驾目前无法从 Camera2 的信息判断（朝向都可能报 EXTERNAL），
     * 所以先按顺序排，实车看过画面后再决定要不要加一个交换选项。
     * 诊断页会列出每路相机的完整能力，便于确认。</p>
     */
    private void initCamerasForZeekrMulti(CameraManager cm, String[] cameraIds) {
        com.kooo.evcam.zeekr.ZeekrCameraLocator.Result located =
                com.kooo.evcam.zeekr.ZeekrCameraLocator.locate(cm);
        AppLog.i(TAG, "极氪多路探测结果:\n" + located.diagnostics);

        String compositeId = located.found() ? located.cameraId : null;
        if (located.found()) {
            appConfig.setTargetResolution(
                    located.size.getWidth() + "x" + located.size.getHeight());
            if (compositeContainer != null) {
                compositeContainer.setSourceSize(located.size);
            }
        } else {
            AppLog.w(TAG, "未找到合成流，多路配置将只使用普通相机");
            runOnUiThread(() -> Toast.makeText(this,
                    R.string.zeekr_composite_not_found, Toast.LENGTH_LONG).show());
        }
        updateCompositeInfoOverlay(located.diagnostics);

        // 其余相机按 id 顺序补进两个座舱槽位
        java.util.List<String> others = new java.util.ArrayList<>();
        for (String id : cameraIds) {
            if (!id.equals(compositeId)) {
                others.add(id);
            }
        }
        if (compositeId == null && !others.isEmpty()) {
            // 没有合成流时，第一路顶上主画面，避免整个界面空着
            compositeId = others.remove(0);
        }

        String cabin1 = others.size() > 0 ? others.get(0) : null;
        String cabin2 = others.size() > 1 ? others.get(1) : null;
        AppLog.i(TAG, "极氪多路映射: 环视=" + compositeId
                + ", 座舱1=" + cabin1 + ", 座舱2=" + cabin2
                + "（共 " + cameraIds.length + " 路可用）");

        cameraManager.initCameras(
                compositeId, textureFront,
                cabin1, textureBack,
                cabin2, textureLeft,
                null, null);
    }

    /**
     * 银河E5车型：使用固定的摄像头映射
     */
    private void initCamerasForGalaxyE5(String[] cameraIds) {
        if (cameraIds.length >= 4) {
            // 有4个或更多摄像头
            // 修正摄像头位置映射：前=cameraIds[2], 后=cameraIds[1], 左=cameraIds[3], 右=cameraIds[0]
            cameraManager.initCameras(
                    cameraIds[2], textureFront,  // 前摄像头使用 cameraIds[2]
                    cameraIds[1], textureBack,   // 后摄像头使用 cameraIds[1]
                    cameraIds[3], textureLeft,   // 左摄像头使用 cameraIds[3]
                    cameraIds[0], textureRight   // 右摄像头使用 cameraIds[0]
            );
        } else if (cameraIds.length >= 2) {
            // 只有2个摄像头，复用到四个位置
            // 注意：参数顺序必须与 initCameras(frontId, frontView, backId, backView, leftId, leftView, rightId, rightView) 对应
            cameraManager.initCameras(
                    null, null,
                    null, null,                    
                    cameraIds[0], textureLeft,   // left位置使用 textureLeft
                    cameraIds[1], textureRight   // right位置使用 textureRight
            );
        } else if (cameraIds.length == 1) {
            // 只有1个摄像头，所有位置使用同一个
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[0], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[0], textureRight
            );
        } else {
            Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 银河L6/L7车型：使用固定的摄像头映射（竖屏四宫格）
     * 前=2, 后=3, 左=0, 右=1
     */
    private void initCamerasForL7(String[] cameraIds) {
        if (cameraIds.length >= 4) {
            // 有4个或更多摄像头
            cameraManager.initCameras(
                    cameraIds[2], textureFront,  // 前摄像头使用 cameraIds[2]
                    cameraIds[3], textureBack,   // 后摄像头使用 cameraIds[3]
                    cameraIds[0], textureLeft,   // 左摄像头使用 cameraIds[0]
                    cameraIds[1], textureRight   // 右摄像头使用 cameraIds[1]
            );
        } else if (cameraIds.length >= 2) {
            // 只有2个摄像头，复用到四个位置
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[1], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[1], textureRight
            );
        } else if (cameraIds.length == 1) {
            // 只有1个摄像头，所有位置使用同一个
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[0], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[0], textureRight
            );
        } else {
            Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 26款星舰7车型：使用固定的摄像头映射
     * 前=3, 后=2, 左=4, 右=1
     */
    private void initCamerasForXinghan7(String[] cameraIds) {
        if (cameraIds.length >= 5) {
            // 有5个或更多摄像头
            cameraManager.initCameras(
                    cameraIds[3], textureFront,  // 前摄像头使用 cameraIds[3]
                    cameraIds[2], textureBack,   // 后摄像头使用 cameraIds[2]
                    cameraIds[4], textureLeft,   // 左摄像头使用 cameraIds[4]
                    cameraIds[1], textureRight   // 右摄像头使用 cameraIds[1]
            );
        } else if (cameraIds.length >= 4) {
            // 只有4个摄像头，使用可用的ID
            cameraManager.initCameras(
                    cameraIds[3], textureFront,
                    cameraIds[2], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[1], textureRight
            );
        } else if (cameraIds.length >= 2) {
            // 只有2个摄像头，复用到四个位置
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[1], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[1], textureRight
            );
        } else if (cameraIds.length == 1) {
            // 只有1个摄像头，所有位置使用同一个
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[0], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[0], textureRight
            );
        } else {
            Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 手机模式：使用前后2个摄像头
     * 与银河E5不同，手机布局只有 textureFront 和 textureBack
     */
    private void initCamerasForPhone(String[] cameraIds) {
        if (cameraIds.length >= 2) {
            // 有2个或更多摄像头：使用前后两个摄像头
            // 通常 cameraIds[0] 是后置摄像头，cameraIds[1] 是前置摄像头
            cameraManager.initCameras(
                    cameraIds[1], textureFront,  // 前置摄像头（通常 ID=1）
                    cameraIds[0], textureBack,   // 后置摄像头（通常 ID=0）
                    null, null,
                    null, null
            );
            AppLog.d(TAG, "手机模式初始化：前置=" + cameraIds[1] + ", 后置=" + cameraIds[0]);
        } else if (cameraIds.length == 1) {
            // 只有1个摄像头，前后使用同一个
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[0], textureBack,
                    null, null,
                    null, null
            );
            AppLog.d(TAG, "手机模式初始化：单摄像头=" + cameraIds[0]);
        } else {
            Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
        }
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
     * 应用手机缩放变换，保持摄像头预览的宽高比不被拉伸
     */
    private void applyPhoneScaleTransform(AutoFitTextureView textureView, android.util.Size previewSize, String cameraKey) {
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();

            if (viewWidth == 0 || viewHeight == 0) {
                AppLog.d(TAG, cameraKey + " TextureView 尺寸为0，延迟应用缩放");
                textureView.postDelayed(() -> applyPhoneScaleTransform(textureView, previewSize, cameraKey), 100);
                return;
            }

            int previewWidth = previewSize.getWidth();
            int previewHeight = previewSize.getHeight();

            android.graphics.Matrix matrix = new android.graphics.Matrix();

            float centerX = viewWidth / 2f;
            float centerY = viewHeight / 2f;

            // 计算缩放比例，使用 FIT_CENTER 策略（保持比例，完整显示）
            float scaleX = (float) viewWidth / previewWidth;
            float scaleY = (float) viewHeight / previewHeight;
            float scale = Math.min(scaleX, scaleY);  // 取较小值，确保完整显示

            // 计算缩放后的尺寸
            float scaledWidth = previewWidth * scale;
            float scaledHeight = previewHeight * scale;

            // 计算偏移量，使内容居中
            float dx = (viewWidth - scaledWidth) / 2f;
            float dy = (viewHeight - scaledHeight) / 2f;

            // 设置变换矩阵：先缩放，再平移居中
            matrix.setScale(scale, scale);
            matrix.postTranslate(dx, dy);

            // 保存基础变换，并叠加预览矫正
            previewBaseTransforms.put(cameraKey, new android.graphics.Matrix(matrix));
            PreviewCorrection.postApply(matrix, appConfig, cameraKey, viewWidth, viewHeight);

            textureView.setTransform(matrix);
            AppLog.d(TAG, cameraKey + " 应用手机缩放变换: view=" + viewWidth + "x" + viewHeight + 
                    ", preview=" + previewWidth + "x" + previewHeight + 
                    ", scale=" + scale);
        });
    }

    /**
     * 根据车型和摄像头位置，对 TextureView 应用正确的宽高比和旋转变换。
     * 从 previewSizeCallback 提取，避免正常初始化和后台复用路径的代码重复。
     */
    private void applyPreviewSizeTransform(String cameraKey, AutoFitTextureView textureView, android.util.Size previewSize) {
        String carModel = appConfig.getCarModel();

        // 极氪合成流：比例与排版由 FourLaneContainer 负责。
        // 这里绝不能给 TextureView 设 1280:5140 这种长条宽高比或预览矩阵，
        // 否则子视图会被测量成细长条，四宫格就错位了。
        if (compositeContainer != null && textureView == textureFront) {
            // 只有看起来像合成流的尺寸才更新几何；HAL 有时会给一个压扁的小尺寸提示，
            // 那种尺寸会被容器忽略，已探测到的正确几何得以保留。
            compositeContainer.setSourceSize(previewSize);
            AppLog.d(TAG, "合成流预览尺寸: " + previewSize
                    + " -> " + compositeContainer.describePlan());
            updateCompositeInfoOverlay(compositeContainer.describePlan());
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
        } else if (AppConfig.CAR_MODEL_L7.equals(carModel) || AppConfig.CAR_MODEL_L7_MULTI.equals(carModel)) {
            boolean needRotation = "left".equals(cameraKey) || "right".equals(cameraKey);
            if (needRotation) {
                textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
                AppLog.d(TAG, "设置 " + cameraKey + " 宽高比(旋转后): " + previewSize.getHeight() + ":" + previewSize.getWidth());
                int rotation = "left".equals(cameraKey) ? 270 : 90;
                applyRotationTransform(textureView, previewSize, rotation, cameraKey);
            } else {
                textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
                textureView.setFillContainer(false);
                AppLog.d(TAG, "设置 " + cameraKey + " 宽高比: " + previewSize.getWidth() + ":" + previewSize.getHeight() + ", 适应模式");
                applyPreviewCorrectionOnly(textureView, cameraKey);
            }
        } else if (AppConfig.CAR_MODEL_PHONE.equals(carModel)) {
            textureView.setFillContainer(false);
            applyPhoneScaleTransform(textureView, previewSize, cameraKey);
            AppLog.d(TAG, "设置 " + cameraKey + " 手机缩放变换, 预览尺寸: " + previewSize.getWidth() + "x" + previewSize.getHeight());
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

    // ==================== 鱼眼矫正 ====================

    /**
     * 鱼眼矫正开关切换后刷新所有摄像头预览
     * 需要重建 Camera session（切换直接 Surface / GL 中间层）
     */
    public void refreshFisheyeCorrection() {
        MultiCameraManager cm = cameraManager;
        if (cm == null) return;
        String[] positions = {"front", "back", "left", "right"};
        for (String pos : positions) {
            com.kooo.evcam.camera.SingleCamera camera = cm.getCamera(pos);
            if (camera != null) {
                camera.recreateForFisheyeToggle();
            }
        }
    }

    /**
     * 显示鱼眼矫正悬浮窗
     */
    public void showFisheyeCorrectionFloating() {
        if (fisheyeCorrectionFloatingWindow != null && fisheyeCorrectionFloatingWindow.isShowing()) {
            return;
        }
        fisheyeCorrectionFloatingWindow = new FisheyeCorrectionFloatingWindow(this);
        fisheyeCorrectionFloatingWindow.show();
    }

    /**
     * 关闭鱼眼矫正悬浮窗
     */
    public void dismissFisheyeCorrectionFloating() {
        if (fisheyeCorrectionFloatingWindow != null) {
            fisheyeCorrectionFloatingWindow.dismiss();
            fisheyeCorrectionFloatingWindow = null;
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
            android.widget.Toast.makeText(this, "调试信息已开启", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            tvDebugOverlay.setVisibility(View.GONE);
            stopDebugUpdates();
            android.widget.Toast.makeText(this, "调试信息已关闭", android.widget.Toast.LENGTH_SHORT).show();
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
        sb.append("── EVCam Debug ──\n");

        // 摄像头 FPS 和分辨率
        if (cameraManager != null) {
            sb.append(cameraManager.getDebugStats());
        } else {
            sb.append("Camera: not initialized");
        }

        // 录制状态
        sb.append("\n\n");
        sb.append("录制: ").append(isRecording ? "● REC" : "○ 停止");
        if (isRecording) {
            sb.append("  模式: ").append(appConfig.getRecordingMode());
        }

        // 内存使用
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long totalMB = rt.maxMemory() / (1024 * 1024);
        sb.append("\n");
        sb.append("内存: ").append(usedMB).append("/").append(totalMB).append(" MB");

        // 车型
        sb.append("\n");
        sb.append("车型: ").append(appConfig.getCarModel());
        sb.append("  摄像头数: ").append(appConfig.getCameraCount());

        // 版本
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            sb.append("\n");
            sb.append("版本: ").append(versionName);
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
                Toast.makeText(this, "摄像头未就绪，恢复录制失败", Toast.LENGTH_SHORT).show();
                shouldResumeRecordingAfterRecreate = false;
                savedRecordingStartTime = 0;
                savedSegmentCount = 1;
                return;
            }
            
            AppLog.d(TAG, "主题切换后自动恢复录制...");
            startRecording();
            Toast.makeText(this, "已自动恢复录制", Toast.LENGTH_SHORT).show();
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
        
        // 避免重复触发
        if (autoStartRecordingTriggered) {
            AppLog.d(TAG, "自动录制已触发过，跳过");
            return;
        }
        
        // 检查是否启用了自动录制
        if (!appConfig.isAutoStartRecording()) {
            AppLog.d(TAG, "未启用启动自动录制");
            return;
        }
        
        // 标记已触发
        autoStartRecordingTriggered = true;
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
                Toast.makeText(this, "摄像头未就绪，自动录制失败", Toast.LENGTH_SHORT).show();
                return;
            }
            
            AppLog.d(TAG, "自动开始录制...");
            startRecording();
            Toast.makeText(this, "已自动开始录制", Toast.LENGTH_SHORT).show();
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
        // 检查是否启用了自动录制
        if (!appConfig.isAutoStartRecording()) {
            return;
        }
        
        // 如果用户手动停止了录制，不自动恢复
        if (isManuallyStoppedRecording) {
            // 每5分钟打印一次日志（避免日志刷屏）
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
        AppLog.d(TAG, "自动录制检查：检测到未在录制，自动恢复录制...");
        startRecording();
        Toast.makeText(this, "已自动恢复录制", Toast.LENGTH_SHORT).show();
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
                    AppLog.d(TAG, "收到录制切换广播（来自悬浮窗）");
                    // 在主线程执行录制切换
                    runOnUiThread(() -> {
                        toggleRecording();
                    });
                }
            }
        };
        
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction("com.kooo.evcam.action.TOGGLE_RECORDING");
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
                    Toast.makeText(MainActivity.this, "息屏10秒，已自动停止录制", Toast.LENGTH_SHORT).show();
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
            
            AppLog.d(TAG, "息屏已持续15秒，退到后台释放相机资源");
            
            // 关闭摄像头释放资源
            if (cameraManager != null) {
                cameraManager.closeAllCameras();
                AppLog.d(TAG, "已关闭所有摄像头");
            }
            
            // 退到后台
            moveTaskToBack(true);
            
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "息屏15秒，已退到后台", Toast.LENGTH_SHORT).show();
            });
        };
        
        screenStateHandler.postDelayed(screenOffBackgroundRunnable, SCREEN_OFF_BACKGROUND_DELAY_MS);
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
                                Toast.makeText(MainActivity.this, "已自动恢复录制", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(MainActivity.this, "亮屏10秒，已自动恢复录制", Toast.LENGTH_SHORT).show();
            });
        };
        
        screenStateHandler.postDelayed(screenOnStartRunnable, SCREEN_ON_DELAY_MS);
    }
    /**
     * 切换录制状态（开始/停止）
     */
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
            isManuallyStoppedRecording = true;
            AppLog.d(TAG, "用户手动停止录制，自动录制检查将不再自动恢复");
            
            // 用户手动停止录制，重置息屏录制标记
            // 这样亮屏后不会错误地恢复录制
            wasRecordingBeforeScreenOff = false;
            stopRecording();
        } else {
            // 用户手动开始录制，重置手动停止标记
            // 这样后续如果录制异常停止，可以自动恢复
            isManuallyStoppedRecording = false;
            AppLog.d(TAG, "用户手动开始录制，自动录制检查已启用");
            startRecording();
        }
    }

    private void startRecording() {
        if (cameraManager != null && !cameraManager.isRecording()) {
            // 从配置读取启用的录制摄像头
            AppConfig appConfig = new AppConfig(this);
            java.util.Set<String> enabledCameras = appConfig.getEnabledRecordingCameras();
            
            if (enabledCameras.isEmpty()) {
                Toast.makeText(this, "请至少选择一个录制摄像头", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 检测U盘回退情况（用户选择了U盘但不可用）
            boolean isFallback = StorageHelper.isSdCardFallback(this);
            
            // 生成统一时间戳
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(new java.util.Date());
            
            // 使用指定的摄像头进行录制
            boolean success = cameraManager.startRecording(timestamp, enabledCameras);
            if (success) {
                isRecording = true;
                isPreparingRecording = true;  // 标记为准备中状态
                isAutoRecordingPending = false;  // 录制成功，清除等待标记

                // 启动前台服务保护（防止后台录制被中断）
                CameraForegroundService.start(this, "正在录制视频", "录制进行中，点击返回应用");

                // 显示准备中指示器（橙色旋转圈）
                // 首次数据写入后会自动切换到绿色闪烁动画
                showPreparingIndicator();
                
                // 注意：录制计时器延迟到首次写入回调中启动
                // 这样计时从"有效录制"开始，而不是从"尝试录制"开始

                // 发送录制状态广播（通知悬浮窗）
                FloatingWindowService.sendRecordingStateChanged(this, true);

                // L7-多按钮布局：更新录制按钮文字为"停止"
                if (AppConfig.CAR_MODEL_L7_MULTI.equals(appConfig.getCarModel()) && btnStartRecord != null) {
                    btnStartRecord.setText("停止");
                }

                // 显示提示：优先显示回退提示（每次冷启动只显示一次）
                if (isFallback && !AppConfig.isSdFallbackShownThisSession()) {
                    AppConfig.setSdFallbackShownThisSession(true);
                    Toast.makeText(this, "未检测到U盘，已回退到内部存储", Toast.LENGTH_LONG).show();
                    AppLog.w(TAG, "U盘回退：用户选择U盘但不可用，使用内部存储");
                } else {
                    // 显示录制的摄像头数量
                    int cameraCount = enabledCameras.size();
                    String cameraText = cameraCount == appConfig.getCameraCount() ? "全部" : cameraCount + "个";
                    Toast.makeText(this, "开始录制" + cameraText + "摄像头", Toast.LENGTH_SHORT).show();
                }
                AppLog.d(TAG, "Recording started with " + enabledCameras.size() + " camera(s): " + enabledCameras);
            } else {
                Toast.makeText(this, "录制失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void stopRecording() {
        if (cameraManager != null) {
            cameraManager.stopRecording();
            isRecording = false;
            isPreparingRecording = false;  // 重置准备中状态

            // 停止前台服务
            CameraForegroundService.stop(this);

            // 停止闪烁动画，恢复红色
            stopBlinkAnimation();
            
            // 停止录制计时器
            stopRecordingTimer();

            // 发送录制状态广播（通知悬浮窗）
            FloatingWindowService.sendRecordingStateChanged(this, false);

            // L7-多按钮布局：恢复录制按钮文字为"录像"
            if (AppConfig.CAR_MODEL_L7_MULTI.equals(appConfig.getCarModel()) && btnStartRecord != null) {
                btnStartRecord.setText("录像");
            }

            Toast.makeText(this, "录制已停止", Toast.LENGTH_SHORT).show();
            AppLog.d(TAG, "Recording stopped, foreground service stopped");
        }
    }

    /**
     * 完全退出应用（包括后台进程）
     * 这是用户主动退出，需要停止所有服务
     */
    private void exitApp() {
        AppLog.d(TAG, "用户请求退出应用，停止所有服务...");
        
        // 停止录制（如果正在录制）
        if (isRecording) {
            stopRecording();
        }

        // 停止前台服务（确保清理）
        CameraForegroundService.stop(this);


        // 释放悬浮窗服务
        FloatingWindowService.stop(this);
        
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

    private void startBlinkAnimation() {
        if (blinkHandler == null) {
            blinkHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }

        isBlinking = true;
        blinkRunnable = new Runnable() {
            @Override
            public void run() {
                if (isBlinking) {
                    // 切换颜色：绿色和深绿色交替
                    int currentColor = btnStartRecord.getTextColors().getDefaultColor();
                    if (currentColor == 0xFF00FF00) {  // 亮绿色
                        btnStartRecord.setTextColor(0xFF006400);  // 深绿色
                    } else {
                        btnStartRecord.setTextColor(0xFF00FF00);  // 亮绿色
                    }
                    blinkHandler.postDelayed(this, 1000);  // 每500ms闪烁一次
                }
            }
        };

        // 初始设置为绿色
        btnStartRecord.setTextColor(0xFF00FF00);
        blinkHandler.post(blinkRunnable);
    }

    private void stopBlinkAnimation() {
        isBlinking = false;
        if (blinkHandler != null && blinkRunnable != null) {
            blinkHandler.removeCallbacks(blinkRunnable);
        }
        // 恢复红色（确保在主线程执行，且按钮不为空）
        if (btnStartRecord != null) {
            runOnUiThread(() -> {
                if (btnStartRecord != null) {
                    btnStartRecord.setTextColor(0xFFFF0000);
                }
            });
        }
    }

    /**
     * 显示准备中状态
     * 按钮变为暗绿色（不闪烁），表示录制正在初始化
     */
    private void showPreparingIndicator() {
        if (btnStartRecord != null) {
            // 设置按钮为暗绿色（不闪烁），表示准备中
            btnStartRecord.setTextColor(0xFF006400);  // 暗绿色
            AppLog.d(TAG, "进入准备中状态：暗绿色（不闪烁）");
        }
    }

    /**
     * 结束准备中状态
     * 录制真正开始后调用，开始绿色闪烁动画
     */
    private void hidePreparingIndicator() {
        // 开始绿色闪烁动画（如果正在录制）
        if (isRecording || isRemoteRecording) {
            startBlinkAnimation();
            AppLog.d(TAG, "准备完成，开始绿色闪烁");
        }
    }

    private void takePicture() {
        if (cameraManager != null) {
            cameraManager.takePicture();
            Toast.makeText(this, "拍照完成", Toast.LENGTH_SHORT).show();
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
     * 处理启动录制指令
     * 唤醒到前台并开始持续录制（等同点击录制按钮）
     */
    private String handleStartRecordingCommand() {
        AppLog.d(TAG, "处理启动录制指令");
        
        // 如果已经在录制，返回提示
        if (isRecording) {
            return "⚠️ 已在录制中，无需重复启动";
        }
        
        // 使用 WakeUpHelper 唤醒应用并启动录制
        // 这确保即使在后台也能正确打开摄像头并录制
        WakeUpHelper.launchForStartRecording(this);
        
        return "▶️ 正在启动录制...\n\n发送「状态」查看录制状态\n发送「结束录制」停止录制";
    }

    /**
     * 处理结束录制指令
     * 停止录制并退到后台
     */
    private String handleStopRecordingCommand() {
        AppLog.d(TAG, "处理结束录制指令");
        
        // 如果没有在录制，返回提示
        if (!isRecording) {
            return "⚠️ 当前未在录制";
        }
        
        // 记录录制时长用于返回信息
        String durationInfo = "";
        if (recordingStartTime > 0) {
            long elapsedMs = System.currentTimeMillis() - recordingStartTime;
            long totalSeconds = elapsedMs / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            durationInfo = String.format("，共录制 %02d:%02d", minutes, seconds);
        }
        
        // 使用 WakeUpHelper 确保应用在前台后停止录制
        // 然后会自动退到后台
        WakeUpHelper.launchForStopRecording(this);
        
        return "⏹️ 录制已停止" + durationInfo + "\n应用将退到后台";
    }

    /**
     * 处理前台指令
     * 将应用切换到前台
     */
    private String handleForegroundCommand() {
        AppLog.d(TAG, "处理前台指令");
        
        // 使用 WakeUpHelper 将应用唤醒到前台
        WakeUpHelper.launchForForeground(this);
        
        return "📱 应用已切换到前台";
    }

    /**
     * 处理后台指令
     * 将应用切换到后台
     */
    private String handleBackgroundCommand() {
        AppLog.d(TAG, "处理后台指令");
        
        // 在主线程中执行退到后台
        runOnUiThread(() -> {
            moveTaskToBack(true);
            AppLog.d(TAG, "应用已切换到后台");
        });
        
        return "📴 应用已切换到后台";
    }

    /**
     * 处理退出指令
     */
    private String handleExitCommand(boolean confirmed) {
        AppLog.d(TAG, "处理退出指令，confirmed=" + confirmed);
        
        if (!confirmed) {
            return "⚠️ 确认要退出 EVCam 吗？\n发送「确认退出」执行退出操作。";
        }
        
        // 在主线程中执行退出
        runOnUiThread(() -> {
            AppLog.d(TAG, "执行退出操作...");
            exitApp();
        });
        
        return "👋 EVCam 正在退出...";
    }





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
        FloatingWindowService.sendRecordingStateChanged(this, isRecording);
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
        BlindSpotService.notifySelfBackground();
        AppLog.d(TAG, "onPause called, isRecording=" + isRecording);
        
        // 通知悬浮窗服务：应用进入后台，显示悬浮窗
        if (appConfig.isFloatingWindowEnabled()) {
            FloatingWindowService.sendAppForegroundState(this, false);
        }
        
        // 根据是否正在录制，决定如何处理摄像头
        if (cameraManager != null) {
            if (isRecording || isRemoteRecording) {
                // 正在录制（手动或远程）：保持摄像头连接（有前台服务保护）
                AppLog.d(TAG, "Recording in progress (manual=" + isRecording + ", remote=" + isRemoteRecording + "), keeping cameras connected");
            } else if (isAutoRecordingPending) {
                // 自动录制正在等待中：保持摄像头连接（开机自启动场景）
                AppLog.d(TAG, "Auto recording pending, keeping cameras connected for startup recording");
            } else if (BlindSpotService.hasActiveCameraWindows()) {
                // 有悬浮窗（补盲/常驻/副屏）正在使用摄像头：保持连接
                // 悬浮窗关闭时会自行释放摄像头（closeCamerasIfIdle）
                AppLog.d(TAG, "Active camera windows exist, keeping cameras connected");
            } else {
                // 未录制且无悬浮窗：主动断开摄像头，释放资源
                AppLog.d(TAG, "Not recording, closing all cameras to release resources");
                cameraManager.closeAllCameras();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLog.d(TAG, "onStop called, isRecording=" + isRecording);
        
        // 如果正在录制但 Activity 即将被销毁，提前停止录制
        // 这给予了比 onDestroy 更充裕的时间来完成清理
        if (isRecording && cameraManager != null && isFinishing()) {
            AppLog.d(TAG, "Activity is finishing, stopping recording in onStop for safer cleanup");
            try {
                cameraManager.stopRecording();
                isRecording = false;
                // 停止录制相关的 UI 更新（Activity 即将销毁，不显示 Toast）
                stopBlinkAnimation();
                stopRecordingTimer();
                // 停止前台服务
                CameraForegroundService.stop(this);
            } catch (Exception e) {
                AppLog.e(TAG, "Error stopping recording in onStop", e);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean wasInBackground = isInBackground;
        isInBackground = false;
        BlindSpotService.notifySelfForeground();
        
        // 标记 Activity 已经完全恢复过一次（用于区分新创建和已存在的 Activity）
        // 这个标记在 onCreate 后第一次 onResume 时设为 true
        boolean wasFirstResume = !hasBeenResumedOnce;
        hasBeenResumedOnce = true;
        
        AppLog.d(TAG, "onResume called, wasInBackground=" + wasInBackground + ", isRecording=" + isRecording + ", firstResume=" + wasFirstResume);
        
        // 通知悬浮窗服务：应用进入前台，隐藏悬浮窗
        if (appConfig.isFloatingWindowEnabled()) {
            FloatingWindowService.sendAppForegroundState(this, true);
        }
        
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

                    // 如果启用了自动录制，从后台返回时自动恢复录制
                    if (appConfig.isAutoStartRecording()) {
                        AppLog.d(TAG, "启用了自动录制，从后台返回后将自动恢复录制");
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            if (!isRecording && cameraManager != null && cameraManager.hasConnectedCameras()) {
                                AppLog.d(TAG, "自动恢复录制...");
                                startRecording();
                                Toast.makeText(this, "已自动恢复录制", Toast.LENGTH_SHORT).show();
                            }
                        }, 1500);  // 等待摄像头准备好
                    }
                    
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

        // 无论是 recreate 还是 finishing，都清掉 Holder 中的旧引用。
        // isFinishing()=true 时（如从最近任务划掉），release() 会清空 cameras map，
        // 但进程可能因 Service 存活而不退出，导致 Holder 持有已清空的实例被复用。
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().setCameraManager(null);

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

        // 停止前台服务（确保清理）
        CameraForegroundService.stop(this);

// 停止存储清理任务
        if (storageCleanupManager != null) {
            storageCleanupManager.stop();
        }
        
        // 停止文件传输服务
        FileTransferManager.getInstance(this).stop();

        // 带超时保护的摄像头资源释放
        if (cameraManager != null) {
            releaseCameraManagerWithTimeout(3000);  // 3秒超时
        }
        
        // 重置自动录制触发标志（下次启动时可以再次触发）
        autoStartRecordingTriggered = false;
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

        // 检查是否可以显示 Toast（20秒内只显示一次）
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRecordingErrorToastTime < RECORDING_ERROR_TOAST_INTERVAL) {
            AppLog.d(TAG, "Recording error toast suppressed (rate limited)");
            return;
        }
        lastRecordingErrorToastTime = currentTime;

        runOnUiThread(() -> {
            android.widget.Toast.makeText(this, "录制发生异常", android.widget.Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            // 如果 Fragment 返回栈不为空（在二级菜单中），则返回上一级
            getSupportFragmentManager().popBackStack();
            AppLog.d(TAG, "Popped fragment back stack, returning to previous screen");
        } else if (fragmentContainer != null && fragmentContainer.getVisibility() == View.VISIBLE) {
            // 如果当前在非录制界面（Fragment界面），先返回录制界面
            goToRecordingInterface();
            AppLog.d(TAG, "Returned to recording interface via back button");
        } else {
            // 在录制界面，按返回键将应用移到后台，而不是关闭Activity
            // 这样下次打开应用时能快速恢复，无需重新创建Activity
            moveTaskToBack(true);
            AppLog.d(TAG, "Moved to background via back button");
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
            Toast.makeText(this, "需要悬浮窗权限才能打开调节窗口", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            return;
        }
        
        if (imageAdjustManager == null) {
            Toast.makeText(this, "摄像头未就绪，无法打开调节窗口", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "悬浮窗权限未授予", Toast.LENGTH_SHORT).show();
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
    
    /**
     * 显示摄像头预览悬浮窗
     * 
     * @param cameraPosition 要显示的摄像头位置（front/back/left/right）
     */
    public void showCameraPreviewFloating(String cameraPosition) {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限才能显示预览", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }
        
        // TODO: CameraPreviewFloatingService 尚未实现
        // CameraPreviewFloatingService.start(this, cameraPosition);
        AppLog.d(TAG, "Camera preview floating not implemented yet for: " + cameraPosition);
        Toast.makeText(this, "摄像头预览悬浮窗功能尚未实现", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 关闭摄像头预览悬浮窗
     */
    public void dismissCameraPreviewFloating() {
        // TODO: CameraPreviewFloatingService 尚未实现
        // CameraPreviewFloatingService.stop(this);
        AppLog.d(TAG, "Camera preview floating stop - not implemented yet");
    }

    // ==================== 极氪合成流 ====================

    /**
     * 合成流界面的交互：点击画面在四宫格 / 单画面之间切换，
     * 单画面模式下再次点击换下一个画面。
     */
    private void setupCompositeControls() {
        if (compositeContainer == null) {
            return;
        }
        Button btnMode = findViewById(R.id.btn_composite_mode);
        if (btnMode != null) {
            btnMode.setOnClickListener(v -> toggleCompositeMode(btnMode));
        }
        compositeContainer.setOnClickListener(v -> {
            if (compositeContainer.getDisplayMode()
                    == com.kooo.evcam.zeekr.FourLaneContainer.DisplayMode.SINGLE) {
                int next = (compositeContainer.getFocusedLane() + 1)
                        % com.kooo.evcam.zeekr.CompositeStreamGeometry.LANE_COUNT;
                compositeContainer.focusLane(next);
                updateCompositeLabels();
            }
        });
        updateCompositeLabels();
    }

    private void toggleCompositeMode(Button btnMode) {
        if (compositeContainer == null) {
            return;
        }
        boolean isGrid = compositeContainer.getDisplayMode()
                == com.kooo.evcam.zeekr.FourLaneContainer.DisplayMode.GRID;
        if (isGrid) {
            compositeContainer.focusLane(0);
            btnMode.setText(R.string.zeekr_mode_single);
        } else {
            compositeContainer.showGrid();
            btnMode.setText(R.string.zeekr_mode_grid);
        }
        updateCompositeLabels();
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
            if (labels[cell] == null) {
                continue;
            }
            boolean visible = grid || order[cell] == focused;
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
            tvCompositeInfo.setVisibility(oneLine.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

}
