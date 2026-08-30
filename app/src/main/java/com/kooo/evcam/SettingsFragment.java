package com.kooo.evcam;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.kooo.evcam.settings.SettingsRegistry;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.zeekr.FisheyeProjection;

import java.io.File;
import java.util.List;

/**
 * 软件设置界面 Fragment
 */
public class SettingsFragment extends Fragment {
    private static final String TAG = "SettingsFragment";

    private SwitchMaterial debugSwitch;
    private Button saveLogsButton;
    private Button uploadLogsButton;
    private LinearLayout logButtonsLayout;
    private SwitchMaterial autoStartSwitch;
    private SwitchMaterial autoStartRecordingSwitch;
    private SwitchMaterial screenOffRecordingSwitch;
    private LinearLayout screenOffRecordingLayout;
    // 定时保活和防止休眠已改为始终开启，无需用户设置（车机必需）
    // private SwitchMaterial keepAliveSwitch;
    // private SwitchMaterial preventSleepSwitch;
    private SwitchMaterial recordingStatsSwitch;
    private SwitchMaterial timestampWatermarkSwitch;
    private SwitchMaterial watermarkSpecSwitch;
    private SwitchMaterial rearViewSwitch;
    private SwitchMaterial forceH264Switch;
    
    // 预览画面矫正相关
    private SwitchMaterial previewCorrectionSwitch;
    private LinearLayout previewCorrectionButtonsLayout;
    private Button openPreviewCorrectionFloatingButton;
    private Button resetPreviewCorrectionButton;
    private PreviewCorrectionFloatingWindow previewCorrectionFloatingWindow;
    
    // 鱼眼矫正相关
    private SwitchMaterial fisheyeCorrectionSwitch;
    private LinearLayout fisheyeCorrectionButtonsLayout;
    private Button openFisheyeCorrectionFloatingButton;
    private Button resetFisheyeCorrectionButton;
    private FisheyeCorrectionFloatingWindow fisheyeCorrectionFloatingWindow;
    
    private AppConfig appConfig;
    
    // 悬浮窗相关
    private SwitchMaterial floatingWindowSwitch;
    private LinearLayout floatingWindowSettingsLayout;
    private SeekBar floatingWindowSizeSeekBar;
    private TextView floatingWindowSizeValueText;
    private SeekBar floatingWindowAlphaSeekBar;
    private TextView floatingWindowAlphaText;
    
    // 车型配置相关
    private Spinner carModelSpinner;
    private Button customCameraConfigButton;
    // 只保留极氪配置加「自定义」；银河/星舰/手机等上游车型与本项目无关，已隐藏。
    // 对应的代码仍在（AppConfig 的常量与 MainActivity 的分支），改回来只需恢复这个数组。
    //
    // 「自定义」是排查用的：它不做任何极氪专属处理（不探测合成流、不强制分辨率、
    // 不走四宫格容器），单纯按数量把相机铺开。三路模式黑屏时，用它可以确认
    // 「到底是相机开不起来，还是极氪那套处理有问题」——目前已知在它下面三路都能看到。
    // 选项、顺序、显示名都来自 SettingsRegistry —— 这里不再各存一份
    private static final String[] CAR_MODEL_OPTIONS =
            SettingsRegistry.CAR_MODEL.displayNames();
    private static final String[] CAR_MODEL_VALUES =
            SettingsRegistry.CAR_MODEL.values();
    private boolean isInitializingCarModel = false;
    private String lastAppliedCarModel = null;
    
    // 录制模式配置相关
    private Spinner recordingModeSpinner;
    private TextView recordingModeDescText;
    private static final String[] RECORDING_MODE_OPTIONS =
            SettingsRegistry.RECORDING_MODE.displayNames();
    private static final String[] RECORDING_MODE_VALUES =
            SettingsRegistry.RECORDING_MODE.values();
    private boolean isInitializingRecordingMode = false;
    private String lastAppliedRecordingMode = null;
    
    // 分段时长配置相关
    private Spinner segmentDurationSpinner;
    private static final String[] SEGMENT_DURATION_OPTIONS = {"1分钟", "3分钟", "5分钟"};

    // 录制画面排列
    private Spinner recordLayoutSpinner;
    private static final String[] RECORD_LAYOUT_OPTIONS =
            SettingsRegistry.RECORD_LAYOUT.displayNames();
    private static final String[] RECORD_LAYOUT_VALUES =
            SettingsRegistry.RECORD_LAYOUT.values();
    private boolean isInitializingRecordLayout = false;

    // 录制帧率配置相关
    private Spinner recordFpsSpinner;
    private static final String[] RECORD_FPS_OPTIONS =
            SettingsRegistry.RECORD_FPS.displayNames();
    private boolean isInitializingRecordFps = false;

    // 预览/录制分辨率解耦
    private com.kooo.evcam.view.MacOSToggleButton decouplePreviewToggle;
    private Spinner previewResolutionSpinner;
    private TextView previewResolutionDescText;
    private boolean isInitializingPreviewResolution = false;

    // 悬浮窗布局重置/保存
    private Button resetFloatingLayoutButton;

    // 手动指定相机映射
    private Button cameraMappingButton;
    private TextView cameraMappingDescText;
    private boolean isInitializingSegmentDuration = false;
    private int lastAppliedSegmentDuration = -1;
    
    // 存储位置配置相关
    private Spinner storageLocationSpinner;
    private TextView storageLocationDescText;
    private Button storageDebugButton;
    private String[] storageLocationOptions;
    /** 与 spinner 选项一一对应；下标 0（内部存储）为 null。 */
    private java.util.List<StorageHelper.VolumeInfo> storageVolumes = new java.util.ArrayList<>();
    private boolean isInitializingStorageLocation = false;
    private String lastAppliedStorageLocation = null;
    private boolean hasExternalSdCard = false;
    
    // 中转写入配置相关
    private SwitchMaterial relayWriteSwitch;
    private TextView relayWriteDescText;
    private boolean isInitializingRelayWrite = false;
    
    
    // 存储清理配置相关
    private EditText videoStorageLimitEdit;
    private EditText photoStorageLimitEdit;
    private TextView videoUsedSizeText;
    private TextView photoUsedSizeText;
    private boolean isInitializingStorageCleanup = false;
    
    // 录制摄像头选择配置相关
    private android.widget.CheckBox cbRecordCameraFront;
    private android.widget.CheckBox cbRecordCameraBack;
    private android.widget.CheckBox cbRecordCameraLeft;
    private android.widget.CheckBox cbRecordCameraRight;
    private boolean isInitializingRecordingCameraSelection = false;
    
    // 版本更新相关

    // 定制键唤醒相关
    private SwitchMaterial customKeyWakeupSwitch;
    private LinearLayout customKeyWakeupDetailLayout;
    private EditText customKeySpeedThresholdEditText;
    private EditText customKeySpeedPropIdEditText;
    private EditText customKeyButtonPropIdEditText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        // 初始化控件
        debugSwitch = view.findViewById(R.id.switch_debug_to_info);
        saveLogsButton = view.findViewById(R.id.btn_save_logs);
        uploadLogsButton = view.findViewById(R.id.btn_upload_logs);
        logButtonsLayout = view.findViewById(R.id.layout_log_buttons);
        Button menuButton = view.findViewById(R.id.btn_menu);
        Button homeButton = view.findViewById(R.id.btn_home);

        // 设置菜单按钮点击事件
        menuButton.setOnClickListener(v -> {
            if (getActivity() != null) {
                DrawerLayout drawerLayout = getActivity().findViewById(R.id.drawer_layout);
                if (drawerLayout != null) {
                    drawerLayout.openDrawer(GravityCompat.START);
                }
            }
        });

        // 主页按钮 - 返回预览界面
        homeButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });

        // 初始化应用配置
        if (getContext() != null) {
            appConfig = new AppConfig(getContext());
            
            // 初始化Debug开关状态
            debugSwitch.setChecked(AppLog.isDebugToInfoEnabled(getContext()));
            
            // 根据 Debug 状态显示或隐藏保存日志按钮
            updateSaveLogsButtonVisibility(debugSwitch.isChecked());
            
            // 初始化车型配置
            initCarModelConfig(view);
            
            // 初始化录制模式配置
            initRecordingModeConfig(view);
            
            // 初始化分段时长配置
            initSegmentDurationConfig(view);

            initCameraMapping(view);

            initFloatingLayoutButtons(view);

            initRecordLayoutConfig(view);

            initRecordFpsConfig(view);

            initDecouplePreviewConfig(view);
            initRearViewConfig(view);
            initPreviewResolutionConfig(view);
            
            // 初始化录制摄像头选择配置
            initRecordingCameraSelectionConfig(view);
            
            // 初始化存储位置配置
            initStorageLocationConfig(view);
            
            // 初始化存储清理配置
            initStorageCleanupConfig(view);
        }

        // 设置Debug开关监听器
        debugSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null) {
                AppLog.setDebugToInfoEnabled(getContext(), isChecked);
                updateSaveLogsButtonVisibility(isChecked);
                String message = isChecked ? "Debug logs will show as info" : "Debug logs will show as debug";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });

        // 设置保存日志按钮监听器
        saveLogsButton.setOnClickListener(v -> {
            if (getContext() != null) {
                File logFile = AppLog.saveLogsToFile(getContext());
                if (logFile != null) {
                    Toast.makeText(getContext(), "Logs saved to: " + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getContext(), "Failed to save logs", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 设置一键上传日志按钮监听器
        uploadLogsButton.setOnClickListener(v -> {
            if (getContext() != null && appConfig != null) {
                // 检查是否已设置设备名称
                if (!appConfig.hasDeviceNickname()) {
                    // 首次上传，显示输入框
                    showDeviceNicknameInputDialog();
                } else {
                    // 已有设备名称，显示确认对话框
                    showUploadConfirmDialog(appConfig.getDeviceNickname());
                }
            }
        });

        
        // 初始化使用提示入口
        Button btnUsageGuide = view.findViewById(R.id.btn_usage_guide);
        btnUsageGuide.setOnClickListener(v -> showUsageGuideDialog());

        // 初始化权限设置入口
        Button btnPermissionSettings = view.findViewById(R.id.btn_permission_settings);
        btnPermissionSettings.setOnClickListener(v -> openPermissionSettings());

        // 初始化分辨率设置入口
        Button btnResolutionSettings = view.findViewById(R.id.btn_resolution_settings);
        btnResolutionSettings.setOnClickListener(v -> openResolutionSettings());

        // 初始化录制状态显示开关
        recordingStatsSwitch = view.findViewById(R.id.switch_recording_stats);
        if (getContext() != null && appConfig != null) {
            recordingStatsSwitch.setChecked(appConfig.isRecordingStatsEnabled());
        }

        // 设置录制状态显示开关监听器
        recordingStatsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setRecordingStatsEnabled(isChecked);
                String message = isChecked ? "录制状态显示已开启" : "录制状态显示已关闭";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
                
                // 通知 MainActivity 刷新设置
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).refreshRecordingStatsSettings();
                }
            }
        });

        // 初始化时间角标开关
        timestampWatermarkSwitch = view.findViewById(R.id.switch_timestamp_watermark);
        watermarkSpecSwitch = view.findViewById(R.id.switch_watermark_spec);
        if (getContext() != null && appConfig != null) {
            timestampWatermarkSwitch.setChecked(appConfig.isTimestampWatermarkEnabled());
        }

        // 设置时间角标开关监听器
        timestampWatermarkSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setTimestampWatermarkEnabled(isChecked);
                String message = isChecked ? "时间角标已开启" : "时间角标已关闭";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
            }
        });

        if (watermarkSpecSwitch != null) {
            watermarkSpecSwitch.setChecked(appConfig.isWatermarkSpecEnabled());
            watermarkSpecSwitch.setEnabled(appConfig.isTimestampWatermarkEnabled());
            watermarkSpecSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                appConfig.setWatermarkSpecEnabled(isChecked);
                if (getContext() != null) {
                    Toast.makeText(getContext(),
                            isChecked ? "角标将附带录制规格，下次开始录制时生效"
                                      : "角标只显示时间，下次开始录制时生效",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 初始化强制 H.264 编码开关
        forceH264Switch = view.findViewById(R.id.switch_force_h264);
        if (getContext() != null && appConfig != null) {
            forceH264Switch.setChecked(appConfig.isForceH264Encoding());
        }
        forceH264Switch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setForceH264Encoding(isChecked);
                String message = isChecked ? "已切换为 H.264 兼容编码，下一段录制生效" : "已切换为 H.265/HEVC 编码，下一段录制生效";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
            }
        });

        // 初始化开机自启动开关
        autoStartSwitch = view.findViewById(R.id.switch_auto_start);
        if (getContext() != null && appConfig != null) {
            autoStartSwitch.setChecked(appConfig.isAutoStartOnBoot());
        }

        // 设置开机自启动开关监听器
        autoStartSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setAutoStartOnBoot(isChecked);
                String message = isChecked ? "开机自启动已启用" : "开机自启动已禁用";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
            }
        });

        // 初始化启动自动录制开关
        autoStartRecordingSwitch = view.findViewById(R.id.switch_auto_start_recording);
        if (getContext() != null && appConfig != null) {
            autoStartRecordingSwitch.setChecked(appConfig.isAutoStartRecording());
        }

        // 初始化息屏录制开关
        screenOffRecordingSwitch = view.findViewById(R.id.switch_screen_off_recording);
        screenOffRecordingLayout = view.findViewById(R.id.layout_screen_off_recording);
        if (getContext() != null && appConfig != null) {
            screenOffRecordingSwitch.setChecked(appConfig.isScreenOffRecordingEnabled());
            // 根据启动自动录制的状态决定是否显示息屏录制开关
            updateScreenOffRecordingVisibility(appConfig.isAutoStartRecording());
        }

        // 设置启动自动录制开关监听器
        autoStartRecordingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setAutoStartRecording(isChecked);
                String message = isChecked ? "启动自动录制已启用，下次启动生效" : "启动自动录制已禁用";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
                
                // 更新息屏录制开关的可见性
                updateScreenOffRecordingVisibility(isChecked);
            }
        });

        // 设置息屏录制开关监听器
        screenOffRecordingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setScreenOffRecordingEnabled(isChecked);
                String message = isChecked ? "息屏录制已启用，息屏时将继续录制" : "息屏录制已禁用，息屏10秒后将自动停止录制";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
            }
        });

        // 定时保活已改为始终开启（车机必需），无需设置开关
        // 隐藏定时保活开关
        View keepAliveSwitch = view.findViewById(R.id.switch_keep_alive);
        if (keepAliveSwitch != null) {
            View parent = (View) keepAliveSwitch.getParent();
            if (parent != null) {
                parent.setVisibility(View.GONE);
            }
        }
        // 确保定时保活任务已启动
        if (getContext() != null) {
            KeepAliveManager.startKeepAliveWork(getContext());
        }

        // 防止休眠已改为始终开启（车机必需），无需设置开关
        // WakeLock 在 CameraForegroundService 中自动获取
        // 隐藏防止休眠开关
        View preventSleepLayout = view.findViewById(R.id.switch_prevent_sleep);
        if (preventSleepLayout != null) {
            // 隐藏整个布局（包括开关和说明文字）
            View parent = (View) preventSleepLayout.getParent();
            if (parent != null) {
                parent.setVisibility(View.GONE);
            }
        }

        // 初始化悬浮窗设置
        initFloatingWindowSettings(view);

        // 初始化录制悬浮按钮设置
        initRecordingFloatingSettings(view);

        
        // 沉浸式状态栏兼容
        View toolbar = view.findViewById(R.id.toolbar);
        if (toolbar != null) {
            final int originalPaddingTop = toolbar.getPaddingTop();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
                int statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(v.getPaddingLeft(), statusBarHeight + originalPaddingTop, v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            androidx.core.view.ViewCompat.requestApplyInsets(toolbar);
        }

        return view;
    }
    
    /**
     * 显示使用提示对话框
     */
    private void showUsageGuideDialog() {
        if (getContext() == null) return;

        // 创建自定义对话框
        android.app.Dialog dialog = new android.app.Dialog(getContext());
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_first_launch_guide);
        dialog.setCancelable(true);

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
     * 打开权限设置页面
     */
    private void openPermissionSettings() {
        if (getActivity() == null) return;
        
        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, new PermissionSettingsFragment());
        transaction.addToBackStack(null);
        transaction.commit();
    }
    
    /**
     * 初始化悬浮窗设置
     */
    private void initFloatingWindowSettings(View view) {
        floatingWindowSwitch = view.findViewById(R.id.switch_floating_window);
        floatingWindowSettingsLayout = view.findViewById(R.id.layout_floating_window_settings);
        floatingWindowSizeSeekBar = view.findViewById(R.id.seekbar_floating_window_size);
        floatingWindowSizeValueText = view.findViewById(R.id.text_floating_window_size_value);
        floatingWindowAlphaSeekBar = view.findViewById(R.id.seekbar_floating_window_alpha);
        floatingWindowAlphaText = view.findViewById(R.id.tv_floating_window_alpha_value);
        
        if (floatingWindowSwitch == null || getContext() == null || appConfig == null) {
            return;
        }
        
        // 初始化悬浮窗开关状态
        boolean floatingEnabled = appConfig.isFloatingWindowEnabled();
        floatingWindowSwitch.setChecked(floatingEnabled);
        updateFloatingWindowSettingsVisibility(floatingEnabled);
        
        // 设置悬浮窗开关监听器
        floatingWindowSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() == null || appConfig == null) {
                return;
            }
            
            // 检查悬浮窗权限
            if (isChecked && !WakeUpHelper.hasOverlayPermission(getContext())) {
                Toast.makeText(getContext(), "请先在权限设置中授权悬浮窗权限", Toast.LENGTH_SHORT).show();
                buttonView.setChecked(false);
                WakeUpHelper.requestOverlayPermission(getContext());
                return;
            }
            
            appConfig.setFloatingWindowEnabled(isChecked);
            updateFloatingWindowSettingsVisibility(isChecked);
            
            if (isChecked) {
                FloatingWindowService.start(getContext());
                Toast.makeText(getContext(), "悬浮窗已开启", Toast.LENGTH_SHORT).show();
                
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).broadcastCurrentRecordingState();
                }
            } else {
                FloatingWindowService.stop(getContext());
                Toast.makeText(getContext(), "悬浮窗已关闭", Toast.LENGTH_SHORT).show();
            }
        });
        
        // 初始化悬浮窗大小选择器
        initFloatingWindowSizeSeekBar();
        
        // 初始化悬浮窗透明度滑块
        initFloatingWindowAlphaSeekBar();
    }

    /**
     * 悬浮窗按钮大小。
     *
     * <p>原来是十档命名下拉框（超小/特小/小/中/大/超大/特大/特特大/PLUS大/MAX大）——
     * 一个数字配十个名字，而名字并不比数字多说明什么。录制悬浮按钮那边早就是滑块 +
     * dp 读数，这里改成同一种，顺便让设置页少一套词汇。</p>
     */
    private void initFloatingWindowSizeSeekBar() {
        if (floatingWindowSizeSeekBar == null || getContext() == null || appConfig == null) {
            return;
        }

        int currentSize = appConfig.getFloatingWindowSize();
        floatingWindowSizeSeekBar.setProgress(currentSize);
        if (floatingWindowSizeValueText != null) {
            floatingWindowSizeValueText.setText(currentSize + "dp");
        }

        floatingWindowSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int size = clampFloatingSize(progress);
                if (floatingWindowSizeValueText != null) {
                    floatingWindowSizeValueText.setText(size + "dp");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 松手时才落盘，拖动过程中不反复写 SharedPreferences
                int size = clampFloatingSize(seekBar.getProgress());
                appConfig.setFloatingWindowSize(size);
                if (getContext() != null && appConfig.isFloatingWindowEnabled()) {
                    FloatingWindowService.sendUpdateFloatingWindow(getContext());
                }
            }
        });
    }

    /** 悬浮窗按钮大小的取值范围，与原来十档命名的首尾一致。 */
    private static int clampFloatingSize(int dp) {
        return Math.max(AppConfig.FLOATING_SIZE_TINY,
                Math.min(AppConfig.FLOATING_SIZE_MAX, dp));
    }

    /**
     * 初始化悬浮窗透明度滑块
     */
    private void initFloatingWindowAlphaSeekBar() {
        if (floatingWindowAlphaSeekBar == null || floatingWindowAlphaText == null || getContext() == null) {
            return;
        }
        
        floatingWindowAlphaSeekBar.setMax(80);
        
        int currentAlpha = appConfig.getFloatingWindowAlpha();
        floatingWindowAlphaSeekBar.setProgress(currentAlpha - 20);
        floatingWindowAlphaText.setText(currentAlpha + "%");
        
        floatingWindowAlphaSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int alpha = progress + 20;
                floatingWindowAlphaText.setText(alpha + "%");
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int alpha = seekBar.getProgress() + 20;
                appConfig.setFloatingWindowAlpha(alpha);
                
                if (getContext() != null && appConfig.isFloatingWindowEnabled()) {
                    FloatingWindowService.sendUpdateFloatingWindow(getContext());
                }
            }
        });
    }
    
    /**
     * 更新悬浮窗设置区域的可见性
     */
    private void updateFloatingWindowSettingsVisibility(boolean visible) {
        if (floatingWindowSettingsLayout != null) {
            floatingWindowSettingsLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 初始化录制悬浮按钮设置
     */
    private void initRecordingFloatingSettings(View view) {
        SwitchMaterial recordingFloatingSwitch = view.findViewById(R.id.switch_recording_floating);
        LinearLayout sizeSettingsLayout = view.findViewById(R.id.recording_floating_size_settings);
        SeekBar buttonSizeSeekBar = view.findViewById(R.id.seekbar_button_size);
        SeekBar textSizeSeekBar = view.findViewById(R.id.seekbar_text_size);
        TextView buttonSizeValueText = view.findViewById(R.id.text_button_size_value);
        TextView textSizeValueText = view.findViewById(R.id.text_time_size_value);

        if (recordingFloatingSwitch == null || getContext() == null || appConfig == null) {
            return;
        }

        // 初始化开关状态
        boolean isEnabled = appConfig.isRecordingFloatingEnabled();
        recordingFloatingSwitch.setChecked(isEnabled);
        if (sizeSettingsLayout != null) {
            sizeSettingsLayout.setVisibility(isEnabled ? View.VISIBLE : View.GONE);
        }

        // 初始化大小设置
        if (buttonSizeSeekBar != null && textSizeSeekBar != null) {
            // 设置当前值
            int currentButtonSize = appConfig.getRecordingFloatingButtonSizeDp();
            int currentTextSize = appConfig.getRecordingFloatingTimeTextSizeSp();

            buttonSizeSeekBar.setProgress(currentButtonSize);
            textSizeSeekBar.setProgress(currentTextSize);

            buttonSizeValueText.setText(currentButtonSize + "dp");
            textSizeValueText.setText(currentTextSize + "sp");

            // 按钮大小监听器
            buttonSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int size = Math.max(32, progress); // 最小32dp
                    buttonSizeValueText.setText(size + "dp");

                    // 实时发送广播更新悬浮按钮大小
                    if (getContext() != null) {
                        Intent intent = new Intent(com.kooo.evcam.service.RecordingFloatingService.ACTION_UPDATE_SIZE);
                        intent.putExtra(com.kooo.evcam.service.RecordingFloatingService.EXTRA_BUTTON_SIZE, size);
                        intent.putExtra(com.kooo.evcam.service.RecordingFloatingService.EXTRA_TEXT_SIZE, -1);
                        getContext().sendBroadcast(intent);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    int size = Math.max(32, seekBar.getProgress());
                    appConfig.setRecordingFloatingButtonSizeDp(size);
                }
            });

            // 文字大小监听器
            textSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int size = Math.max(8, progress); // 最小8sp
                    textSizeValueText.setText(size + "sp");

                    // 实时发送广播更新文字大小
                    if (getContext() != null) {
                        Intent intent = new Intent(com.kooo.evcam.service.RecordingFloatingService.ACTION_UPDATE_SIZE);
                        intent.putExtra(com.kooo.evcam.service.RecordingFloatingService.EXTRA_BUTTON_SIZE, -1);
                        intent.putExtra(com.kooo.evcam.service.RecordingFloatingService.EXTRA_TEXT_SIZE, size);
                        getContext().sendBroadcast(intent);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    int size = Math.max(8, seekBar.getProgress());
                    appConfig.setRecordingFloatingTimeTextSizeSp(size);
                }
            });
        }

        // 设置开关监听器
        recordingFloatingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() == null) {
                return;
            }

            // 保存开关状态
            appConfig.setRecordingFloatingEnabled(isChecked);

            // 显示/隐藏大小设置
            if (sizeSettingsLayout != null) {
                sizeSettingsLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }

            // 检查悬浮窗权限
            if (isChecked && !WakeUpHelper.hasOverlayPermission(getContext())) {
                Toast.makeText(getContext(), "请先在权限设置中授权悬浮窗权限", Toast.LENGTH_SHORT).show();
                buttonView.setChecked(false);
                appConfig.setRecordingFloatingEnabled(false);
                WakeUpHelper.requestOverlayPermission(getContext());
                return;
            }

            // 在后台线程启动或停止服务，避免ANR
            new Thread(() -> {
                try {
                    Intent intent = new Intent(getContext(), com.kooo.evcam.service.RecordingFloatingService.class);
                    if (isChecked) {
                        intent.setAction(com.kooo.evcam.service.RecordingFloatingService.ACTION_SHOW);
                        getContext().startService(intent);
                    } else {
                        intent.setAction(com.kooo.evcam.service.RecordingFloatingService.ACTION_HIDE);
                        getContext().startService(intent);
                    }
                } catch (Exception e) {
                    AppLog.e(TAG, "启动/停止录制悬浮服务失败", e);
                }
            }).start();

            Toast.makeText(getContext(), isChecked ? "录制悬浮按钮已开启" : "录制悬浮按钮已关闭", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 更新息屏录制开关的可见性
     * 仅当启动自动录制开启时才显示
     */
    private void updateScreenOffRecordingVisibility(boolean autoStartRecordingEnabled) {
        if (screenOffRecordingLayout != null) {
            screenOffRecordingLayout.setVisibility(autoStartRecordingEnabled ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        
        // 重新检测 U盘（可能在授权后返回或U盘插拔）- 异步执行避免卡顿
        if (getContext() != null) {
            final Context context = getContext();
            final String currentLocation = appConfig != null ? appConfig.getStorageLocation() : AppConfig.STORAGE_INTERNAL;
            
            // 异步检测 U盘
            new Thread(() -> {
                // 与初始化路径保持一致：hasExternalSdCard 统一由卷列表推导，
                // 避免两处用不同方式判断而结论不一致
                final java.util.List<StorageHelper.VolumeInfo> detected =
                        StorageHelper.listExternalVolumes(context);
                boolean newHasSdCard = !detected.isEmpty();

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (getContext() == null) return;

                        boolean volumesChanged = detected.size() != storageVolumesCount();
                        storageVolumes = buildVolumeSlots(detected);

                        if (newHasSdCard != hasExternalSdCard || volumesChanged) {
                            hasExternalSdCard = newHasSdCard;
                            if (storageDebugButton != null) {
                                storageDebugButton.setVisibility(hasExternalSdCard ? View.GONE : View.VISIBLE);
                            }
                            
                            rebuildStorageSpinner(currentLocation);
                            // 重建过程中的回调可能影响中转写入行的可见性，复位一下
                            updateRelayWriteVisibility();
                        }
                        
                        // 始终更新描述文字（可能U盘状态变化或空间变化）
                        updateStorageLocationDescriptionAsync(currentLocation);
                    });
                }
            }).start();
            
            // 更新存储占用大小显示（已经是异步的）
            updateStorageUsedSizeDisplay();
        }
        
        // 更新悬浮窗开关状态
        if (floatingWindowSwitch != null && getContext() != null && appConfig != null) {
            boolean hasPermission = WakeUpHelper.hasOverlayPermission(getContext());
            boolean isEnabled = appConfig.isFloatingWindowEnabled();
            
            if (isEnabled && hasPermission) {
                FloatingWindowService.start(getContext());
            }
        }
    }
    
    /**
     * 初始化车型配置
     */
    private void initCarModelConfig(View view) {
        carModelSpinner = view.findViewById(R.id.spinner_car_model);
        // 自定义摄像头映射入口已随自定义车型一起移除；这里不能再把它算进
        // null 检查，否则整个车型配置会直接 return 掉
        customCameraConfigButton = null;

        if (carModelSpinner == null || getContext() == null) {
            return;
        }

        isInitializingCarModel = true;
        lastAppliedCarModel = (appConfig != null) ? appConfig.getCarModel() : null;
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                CAR_MODEL_OPTIONS
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        carModelSpinner.setAdapter(adapter);
        
        carModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= CAR_MODEL_VALUES.length) {
                    return;
                }
                String newModel = CAR_MODEL_VALUES[position];
                String modelName = CAR_MODEL_OPTIONS[position];

                // 只有「自定义」需要摄像头数量/布局配置；极氪两档是固定映射
                updateCustomConfigButtonVisibility(
                        AppConfig.CAR_MODEL_CUSTOM.equals(newModel));

                if (isInitializingCarModel) {
                    return;
                }

                if (newModel.equals(lastAppliedCarModel)) {
                    return;
                }

                lastAppliedCarModel = newModel;
                appConfig.setCarModel(newModel);
                
                // 切换车型时重置录制摄像头选择为全选（避免之前的设置导致无法录制）
                appConfig.resetRecordingCameraSelection();
                
                // 更新录制摄像头选择的 UI（摄像头数量由 AppConfig.getCameraCount() 自动根据车型返回）
                updateRecordingCameraSelectionUI();
                
                if (getContext() != null) {
                    Toast.makeText(getContext(), "已切换为「" + modelName + "」，重启应用后生效", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        // 从旧版本升级上来的用户可能停留在已隐藏的车型上，一律回落到第一项（极氪）
        String currentModel = appConfig.getCarModel();
        int selectedIndex = 0;
        for (int i = 0; i < CAR_MODEL_VALUES.length; i++) {
            if (CAR_MODEL_VALUES[i].equals(currentModel)) {
                selectedIndex = i;
                break;
            }
        }
        carModelSpinner.setSelection(selectedIndex);
        updateCustomConfigButtonVisibility(
                AppConfig.CAR_MODEL_CUSTOM.equals(currentModel));
        
        carModelSpinner.post(() -> {
            isInitializingCarModel = false;
        });
        
    }
    
    /**
     * 更新自定义配置按钮的可见性
     */
    private void updateCustomConfigButtonVisibility(boolean visible) {
        if (customCameraConfigButton != null) {
            customCameraConfigButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
    
    /**
     * 初始化录制模式配置
     */
    private void initRecordingModeConfig(View view) {
        recordingModeSpinner = view.findViewById(R.id.spinner_recording_mode);
        recordingModeDescText = view.findViewById(R.id.tv_recording_mode_desc);
        
        if (recordingModeSpinner == null || getContext() == null) {
            return;
        }
        
        isInitializingRecordingMode = true;
        lastAppliedRecordingMode = (appConfig != null) ? appConfig.getRecordingMode() : null;
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                RECORDING_MODE_OPTIONS
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        recordingModeSpinner.setAdapter(adapter);
        
        recordingModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newMode;
                String modeName;
                String modeDesc;
                
                // 取值和显示名都按下标从注册表取，不再假设「第 0 项就是自动」
                if (position < 0 || position >= RECORDING_MODE_VALUES.length) {
                    return;
                }
                newMode = RECORDING_MODE_VALUES[position];
                modeName = RECORDING_MODE_OPTIONS[position];
                if (AppConfig.RECORDING_MODE_MEDIA_RECORDER.equals(newMode)) {
                    modeDesc = "使用系统硬件编码器，兼容性好";
                } else if (AppConfig.RECORDING_MODE_CODEC.equals(newMode)) {
                    modeDesc = "软编码方案，解决部分设备兼容问题";
                } else {
                    // 自动：把当前实际会用哪个也说出来
                    String actualMode = appConfig.shouldUseCodecRecording() ? "MediaCodec" : "MediaRecorder";
                    modeDesc = "MediaRecorder编码更稳定，MediaCodec兼容性更好，如果无法存储视频，尝试修改\n当前自动选择：" + actualMode;
                }
                
                updateRecordingModeDescription(modeDesc);
                
                if (isInitializingRecordingMode) {
                    return;
                }
                
                if (newMode.equals(lastAppliedRecordingMode)) {
                    return;
                }
                
                lastAppliedRecordingMode = newMode;
                appConfig.setRecordingMode(newMode);
                
                if (getContext() != null) {
                    Toast.makeText(getContext(), "已切换为「" + modeName + "」模式，下次录制生效", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        // getRecordingMode() 已经过 sanitize，取值必定合法，indexOf 不会是 -1；
        // 仍然显式兜底 —— 悄悄落到第 0 项正是这轮改动要消灭的行为
        int selectedIndex = SettingsRegistry.RECORDING_MODE.indexOf(appConfig.getRecordingMode());
        recordingModeSpinner.setSelection(Math.max(0, selectedIndex));
        
        recordingModeSpinner.post(() -> {
            isInitializingRecordingMode = false;
        });
    }
    
    /**
     * 更新录制模式描述文字
     */
    private void updateRecordingModeDescription(String desc) {
        if (recordingModeDescText != null) {
            recordingModeDescText.setText(desc);
        }
    }
    
    /** spinner 里外置卷的数量（不含下标 0 的内部存储）。 */
    private int storageVolumesCount() {
        return Math.max(0, storageVolumes.size() - 1);
    }

    /**
     * 把检测到的卷排成与 spinner 一一对应的列表：下标 0 是内部存储（null 占位）。
     */
    private java.util.List<StorageHelper.VolumeInfo> buildVolumeSlots(
            java.util.List<StorageHelper.VolumeInfo> detected) {
        java.util.List<StorageHelper.VolumeInfo> slots = new java.util.ArrayList<>();
        slots.add(null);  // 内部存储
        if (detected != null) {
            slots.addAll(detected);
        }
        return slots;
    }

    /**
     * 按当前检测到的卷重建存储位置选择器。
     *
     * <p>上游写死成「内部存储 / U盘」两项，插两个盘时第二个永远选不到。
     * 这里每个卷都单独列一项，并显示剩余/总容量。</p>
     */
    private void rebuildStorageSpinner(String currentLocation) {
        if (storageLocationSpinner == null || getContext() == null) {
            return;
        }

        java.util.List<String> labels = new java.util.ArrayList<>();
        labels.add("内部存储");
        for (int i = 1; i < storageVolumes.size(); i++) {
            StorageHelper.VolumeInfo v = storageVolumes.get(i);
            labels.add(v != null ? v.describe() : "外置存储");
        }
        if (labels.size() == 1) {
            labels.add("外置存储（未检测到）");
        }
        storageLocationOptions = labels.toArray(new String[0]);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(), R.layout.spinner_item, storageLocationOptions);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);

        isInitializingStorageLocation = true;
        storageLocationSpinner.setAdapter(adapter);

        // 恢复选择：优先按之前钉住的卷路径匹配，匹配不到就退回第一个外置卷
        int selectedIndex = 0;
        if (AppConfig.STORAGE_EXTERNAL_SD.equals(currentLocation)) {
            selectedIndex = storageLocationOptions.length > 1 ? 1 : 0;
            String pinned = appConfig != null ? appConfig.getCustomSdCardPath() : null;
            if (pinned != null && !pinned.isEmpty()) {
                for (int i = 1; i < storageVolumes.size(); i++) {
                    StorageHelper.VolumeInfo v = storageVolumes.get(i);
                    if (v != null && pinned.equals(v.root.getAbsolutePath())) {
                        selectedIndex = i;
                        break;
                    }
                }
            }
        }
        storageLocationSpinner.setSelection(selectedIndex);
        storageLocationSpinner.post(() -> isInitializingStorageLocation = false);
    }

    /**
     * 「录制模式」只在「原始长条」时有意义 —— 四宫格必须走 MediaCodec，
     * 此时这个选项会被忽略，露出来只会让人以为可以改。
     */
    private void updateRecordingModeVisibility() {
        if (recordingModeSpinner == null || appConfig == null) {
            return;
        }
        // spinner 的直接父节点就是那一行（横向 LinearLayout）。
        // 不能再往上走一层 —— 那是整个设置列表的容器，会把所有项都藏掉。
        android.view.ViewParent parent = recordingModeSpinner.getParent();
        if (parent instanceof View) {
            ((View) parent).setVisibility(
                    appConfig.isRecordGridLayout() ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * 手动指定各槽位使用哪个相机。
     *
     * <p>自动分配是「合成流按能力找、其余按 id 顺序补」，后者只是猜测 ——
     * Camera2 分辨不出哪一路是后排、哪一路是驾驶位。多路配置下某一路不出画面时，
     * 手动指定是最直接的排查手段。</p>
     */
    private void initCameraMapping(View view) {
        cameraMappingButton = view.findViewById(R.id.btn_camera_mapping);
        cameraMappingDescText = view.findViewById(R.id.tv_camera_mapping_desc);
        if (cameraMappingButton == null || appConfig == null) {
            return;
        }
        updateCameraMappingDesc();
        cameraMappingButton.setOnClickListener(v -> showCameraMappingDialog());
    }

    private void updateCameraMappingDesc() {
        if (cameraMappingDescText == null || appConfig == null) {
            return;
        }
        if (!appConfig.hasCameraOverride()) {
            cameraMappingDescText.setText("自动分配。多路配置下若某一路不出画面，可在这里手动指定相机");
            return;
        }
        StringBuilder sb = new StringBuilder("已手动指定：");
        appendMapping(sb, "环视", "front");
        appendMapping(sb, "座舱1", "back");
        appendMapping(sb, "座舱2", "left");
        cameraMappingDescText.setText(sb.toString());
    }

    private void appendMapping(StringBuilder sb, String label, String slot) {
        String id = appConfig.getCameraOverride(slot);
        if (id != null) {
            sb.append(' ').append(label).append('=').append(id);
        }
    }

    /** 每个槽位一个下拉框，选项是车机实际报出来的相机 id。 */
    private void showCameraMappingDialog() {
        if (getContext() == null) {
            return;
        }
        final String[] cameraIds = listCameraIds();
        if (cameraIds.length == 0) {
            Toast.makeText(getContext(), "读取不到相机列表", Toast.LENGTH_SHORT).show();
            return;
        }

        // 选项 = 自动 + 每个相机 id
        final String[] options = new String[cameraIds.length + 1];
        options[0] = "自动";
        for (int i = 0; i < cameraIds.length; i++) {
            options[i + 1] = "相机 " + cameraIds[i];
        }

        final String[] slots = {"front", "back", "left"};
        final String[] slotLabels = {"环视（合成流）", "座舱 1", "座舱 2"};
        final Spinner[] spinners = new Spinner[slots.length];

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < slots.length; i++) {
            TextView label = new TextView(getContext());
            label.setText(slotLabels[i]);
            label.setTextSize(16f);
            root.addView(label);

            Spinner spinner = new Spinner(getContext());
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    getContext(), android.R.layout.simple_spinner_item, options);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);

            String current = appConfig.getCameraOverride(slots[i]);
            int selected = 0;
            if (current != null) {
                for (int k = 0; k < cameraIds.length; k++) {
                    if (cameraIds[k].equals(current)) {
                        selected = k + 1;
                        break;
                    }
                }
            }
            spinner.setSelection(selected);
            root.addView(spinner);
            spinners[i] = spinner;
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("相机映射")
                .setView(root)
                .setPositiveButton("保存", (d, w) -> {
                    for (int i = 0; i < slots.length; i++) {
                        int pos = spinners[i].getSelectedItemPosition();
                        appConfig.setCameraOverride(slots[i],
                                pos <= 0 ? null : cameraIds[pos - 1]);
                    }
                    updateCameraMappingDesc();
                    Toast.makeText(getContext(), "相机映射已保存，重启应用后生效",
                            Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("全部自动", (d, w) -> {
                    appConfig.clearCameraOverrides();
                    updateCameraMappingDesc();
                    Toast.makeText(getContext(), "已恢复自动分配，重启应用后生效",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String[] listCameraIds() {
        try {
            android.hardware.camera2.CameraManager cm =
                    (android.hardware.camera2.CameraManager)
                            requireContext().getSystemService(android.content.Context.CAMERA_SERVICE);
            if (cm != null) {
                return cm.getCameraIdList();
            }
        } catch (Exception e) {
            AppLog.w("SettingsFragment", "读取相机列表失败: " + e.getMessage());
        }
        return new String[0];
    }

    /**
     * 悬浮窗布局的「重置」。
     *
     * <p>两个悬浮窗的位置现在都在拖动结束时自动落盘 —— 主屏悬浮窗上游本来就有，
     * 录制悬浮按钮的持久化是这一版补上的（之前每次启动都回到屏幕左侧中间）。
     * 既然位置自动记住，就不需要额外的「保存」按钮，只留「重置」：
     * 悬浮窗被拖到别扭的位置或调得过大时能一键还原。</p>
     */
    private void initFloatingLayoutButtons(View view) {
        resetFloatingLayoutButton = view.findViewById(R.id.btn_reset_floating_layout);
        if (appConfig == null) {
            return;
        }

        if (resetFloatingLayoutButton != null) {
            resetFloatingLayoutButton.setOnClickListener(v -> {
                appConfig.resetFloatingWindowLayout();
                appConfig.resetRecordingFloatingLayout();
                restartFloatingServices();
                // 尺寸滑块/选择器要跟着回到默认值
                initFloatingWindowSizeSeekBar();
                initFloatingWindowAlphaSeekBar();
                if (getContext() != null) {
                    Toast.makeText(getContext(),
                            "悬浮窗与录制按钮的位置、大小、透明度、字号已恢复默认",
                            Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    /** 重置后让悬浮服务按新值重建。 */
    private void restartFloatingServices() {
        if (getContext() == null) {
            return;
        }
        try {
            android.content.Context ctx = getContext().getApplicationContext();
            if (appConfig.isFloatingWindowEnabled()) {
                ctx.stopService(new android.content.Intent(ctx, FloatingWindowService.class));
                ctx.startService(new android.content.Intent(ctx, FloatingWindowService.class));
            }
            if (appConfig.isRecordingFloatingEnabled()) {
                ctx.stopService(new android.content.Intent(
                        ctx, com.kooo.evcam.service.RecordingFloatingService.class));
                ctx.startService(new android.content.Intent(
                        ctx, com.kooo.evcam.service.RecordingFloatingService.class));
            }
        } catch (Exception e) {
            AppLog.w("SettingsFragment", "重启悬浮服务失败: " + e.getMessage());
        }
    }

    /**
     * 初始化录制画面排列配置。
     *
     * <p>四宫格需要走 MediaCodec 路径（在编码前用 GL 重排），因此选中它时
     * {@link AppConfig#shouldUseCodecRecording()} 会强制返回 true。</p>
     */
    private void initRecordLayoutConfig(View view) {
        recordLayoutSpinner = view.findViewById(R.id.spinner_record_layout);
        if (recordLayoutSpinner == null || getContext() == null || appConfig == null) {
            return;
        }

        isInitializingRecordLayout = true;

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(), R.layout.spinner_item, RECORD_LAYOUT_OPTIONS);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        recordLayoutSpinner.setAdapter(adapter);

        String current = appConfig.getRecordLayout();
        int selectedIndex = 0;
        for (int i = 0; i < RECORD_LAYOUT_VALUES.length; i++) {
            if (RECORD_LAYOUT_VALUES[i].equals(current)) {
                selectedIndex = i;
                break;
            }
        }
        recordLayoutSpinner.setSelection(selectedIndex);

        recordLayoutSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializingRecordLayout
                        || position < 0 || position >= RECORD_LAYOUT_VALUES.length) {
                    return;
                }
                String value = RECORD_LAYOUT_VALUES[position];
                if (value.equals(appConfig.getRecordLayout())) {
                    return;
                }
                appConfig.setRecordLayout(value);
                updateRecordingModeVisibility();
                if (getContext() != null) {
                    Toast.makeText(getContext(),
                            "录制画面排列已设为「" + RECORD_LAYOUT_OPTIONS[position]
                                    + "」，重启应用后生效",
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        recordLayoutSpinner.post(() -> isInitializingRecordLayout = false);
        updateRecordingModeVisibility();
    }

    /**
     * 初始化录制帧率配置。
     *
     * <p>「原始帧率」保持上游行为（跟随车型/硬件默认），其余为显式帧率。
     * 合成流分辨率很高，降低帧率是压编码负载最直接的手段。</p>
     */
    private void initRecordFpsConfig(View view) {
        recordFpsSpinner = view.findViewById(R.id.spinner_record_fps);
        if (recordFpsSpinner == null || getContext() == null || appConfig == null) {
            return;
        }

        isInitializingRecordFps = true;

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(), R.layout.spinner_item, RECORD_FPS_OPTIONS);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        recordFpsSpinner.setAdapter(adapter);

        // 当前值 -> 下标
        String current = appConfig.getRecordFps();
        int selectedIndex = 0;
        for (int i = 0; i < AppConfig.RECORD_FPS_VALUES.length; i++) {
            if (AppConfig.RECORD_FPS_VALUES[i].equals(current)) {
                selectedIndex = i;
                break;
            }
        }
        recordFpsSpinner.setSelection(selectedIndex);

        recordFpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializingRecordFps) {
                    return;
                }
                if (position < 0 || position >= AppConfig.RECORD_FPS_VALUES.length) {
                    return;
                }
                String value = AppConfig.RECORD_FPS_VALUES[position];
                if (value.equals(appConfig.getRecordFps())) {
                    return;
                }
                appConfig.setRecordFps(value);
                if (getContext() != null) {
                    Toast.makeText(getContext(),
                            "录制帧率已设为「" + AppConfig.getRecordFpsDisplayName(value)
                                    + "」，下次开始录制时生效",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        recordFpsSpinner.post(() -> isInitializingRecordFps = false);
    }

    /**
     * 超级后视镜开关与重置。
     *
     * <p>它和主屏悬浮窗共用同一个相机附加输出槽位，所以两者不同时存在 ——
     * 本来就是同一件事的两种形态。</p>
     */
    private void initRearViewConfig(View view) {
        rearViewSwitch = view.findViewById(R.id.switch_rearview_mirror);
        if (rearViewSwitch != null && appConfig != null) {
            rearViewSwitch.setChecked(appConfig.isRearViewEnabled());
            rearViewSwitch.setOnCheckedChangeListener((button, isChecked) -> {
                if (isChecked == appConfig.isRearViewEnabled()) {
                    return;
                }
                appConfig.setRearViewEnabled(isChecked);
                if (getContext() == null) {
                    return;
                }
                if (isChecked) {
                    com.kooo.evcam.zeekr.RearViewMirrorService.start(getContext());
                    Toast.makeText(getContext(),
                            "超级后视镜已开启：中间上下滑调取景、左右划换一路，两侧拖动窗口，双指缩放",
                            Toast.LENGTH_LONG).show();
                } else {
                    com.kooo.evcam.zeekr.RearViewMirrorService.stop(getContext());
                }
            });
        }

        initRearViewSizeSliders(view);
        initRearViewFisheye(view);
        initRearViewLaneMode(view);

        View resetButton = view.findViewById(R.id.btn_reset_rearview);
        if (resetButton != null) {
            resetButton.setOnClickListener(v -> {
                appConfig.resetRearViewLayout();
                if (getContext() != null) {
                    // 重开一次让新的默认值生效
                    if (appConfig.isRearViewEnabled()) {
                        com.kooo.evcam.zeekr.RearViewMirrorService.stop(getContext());
                        com.kooo.evcam.zeekr.RearViewMirrorService.start(getContext());
                    }
                    Toast.makeText(getContext(), "后视镜取景与位置已恢复默认",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /** 「只显示前后视」开关。 */
    private void initRearViewLaneMode(View view) {
        SwitchMaterial laneSwitch = view.findViewById(R.id.switch_rearview_front_rear);
        if (laneSwitch == null || appConfig == null) {
            return;
        }
        laneSwitch.setChecked(appConfig.isRearViewFrontRearOnly());
        laneSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            appConfig.setRearViewFrontRearOnly(isChecked);
            if (getContext() != null && appConfig.isRearViewEnabled()) {
                com.kooo.evcam.zeekr.RearViewMirrorService.applyLaneMode(getContext());
            }
        });
    }

    /**
     * 鱼眼校正开关与视野滑块。
     *
     * <p>视野用角度而不是畸变系数，是因为角度看得懂：「110°」能对着画面判断合不合适，
     * 而 k1/k2 那种标定系数得拿棋盘格标定才有意义，没法凭手感调。</p>
     */
    private void initRearViewFisheye(View view) {
        SwitchMaterial fisheyeSwitch = view.findViewById(R.id.switch_rearview_fisheye);
        SeekBar fovBar = view.findViewById(R.id.seekbar_rearview_fov);
        TextView fovText = view.findViewById(R.id.text_rearview_fov);
        if (appConfig == null) {
            return;
        }

        if (fisheyeSwitch != null) {
            fisheyeSwitch.setChecked(appConfig.isRearViewFisheyeCorrection());
            fisheyeSwitch.setOnCheckedChangeListener((button, isChecked) -> {
                appConfig.setRearViewFisheyeCorrection(isChecked);
                pushRearViewCorrection();
            });
        }

        if (fovBar == null) {
            return;
        }
        int fov = Math.round(appConfig.getRearViewFov());
        fovBar.setProgress(fov);
        if (fovText != null) {
            fovText.setText(fov + "°");
        }
        fovBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fovText != null) {
                    fovText.setText(Math.round(FisheyeProjection.clampFov(progress)) + "°");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                appConfig.setRearViewFov(bar.getProgress());
                pushRearViewCorrection();
            }
        });
    }

    /** 把校正设置推给正在显示的后视镜窗口。 */
    private void pushRearViewCorrection() {
        if (getContext() != null && appConfig.isRearViewEnabled()) {
            com.kooo.evcam.zeekr.RearViewMirrorService.applyCorrection(getContext());
        }
    }

    /** 后视镜窗口的宽高滑块。改动立刻套用到正在显示的窗口。 */
    private void initRearViewSizeSliders(View view) {
        SeekBar widthBar = view.findViewById(R.id.seekbar_rearview_width);
        SeekBar heightBar = view.findViewById(R.id.seekbar_rearview_height);
        TextView widthText = view.findViewById(R.id.text_rearview_width);
        TextView heightText = view.findViewById(R.id.text_rearview_height);
        if (widthBar == null || heightBar == null || appConfig == null) {
            return;
        }

        // 上限就是这块屏幕本身 —— 想把后视镜拉到铺满整屏就该允许，
        // 没有理由替用户设一个更小的天花板。
        final int screenWidth = getResources().getDisplayMetrics().widthPixels;
        final int screenHeight = getResources().getDisplayMetrics().heightPixels;
        widthBar.setMin(AppConfig.REARVIEW_MIN_SIZE);
        heightBar.setMin(AppConfig.REARVIEW_MIN_SIZE);
        widthBar.setMax(screenWidth);
        heightBar.setMax(screenHeight);

        widthBar.setProgress(appConfig.getRearViewWidth(screenWidth));
        heightBar.setProgress(appConfig.getRearViewHeight(screenHeight));
        if (widthText != null) {
            widthText.setText(appConfig.getRearViewWidth(screenWidth) + " px");
        }
        if (heightText != null) {
            heightText.setText(appConfig.getRearViewHeight(screenHeight) + " px");
        }

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                boolean isWidth = bar == widthBar;
                int value = AppConfig.clampRearViewSize(
                        progress, isWidth ? screenWidth : screenHeight);
                TextView label = isWidth ? widthText : heightText;
                if (label != null) {
                    label.setText(value + " px");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                // 松手才落盘并套用，拖动过程中不反复写配置、不反复重排窗口
                appConfig.setRearViewSize(
                        widthBar.getProgress(), heightBar.getProgress(),
                        screenWidth, screenHeight);
                if (getContext() != null && appConfig.isRearViewEnabled()) {
                    com.kooo.evcam.zeekr.RearViewMirrorService.applySize(getContext());
                }
            }
        };
        widthBar.setOnSeekBarChangeListener(listener);
        heightBar.setOnSeekBarChangeListener(listener);
    }

    /**
     * 预览分辨率选择。
     *
     * <p>配置项与读取侧（{@code SingleCamera} 按此挑最接近的已声明尺寸）早就写好了，
     * 但一直没做界面 —— 于是这个设置永远停在默认的 1280x720，看起来只有一个开关。</p>
     *
     * <p>只有开启「预览用低分辨率」时才可用，关闭时预览跟录制同分辨率，这里选什么都没意义。</p>
     */
    private void initPreviewResolutionConfig(View view) {
        previewResolutionSpinner = view.findViewById(R.id.spinner_preview_resolution);
        previewResolutionDescText = view.findViewById(R.id.text_preview_resolution_desc);
        if (previewResolutionSpinner == null || getContext() == null || appConfig == null) {
            return;
        }

        isInitializingPreviewResolution = true;

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(), R.layout.spinner_item, AppConfig.PREVIEW_RES_VALUES);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        previewResolutionSpinner.setAdapter(adapter);

        String current = appConfig.getPreviewResolution();
        int selectedIndex = 0;
        for (int i = 0; i < AppConfig.PREVIEW_RES_VALUES.length; i++) {
            if (AppConfig.PREVIEW_RES_VALUES[i].equals(current)) {
                selectedIndex = i;
                break;
            }
        }
        previewResolutionSpinner.setSelection(selectedIndex);

        previewResolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializingPreviewResolution) {
                    return;
                }
                if (position < 0 || position >= AppConfig.PREVIEW_RES_VALUES.length) {
                    return;
                }
                String value = AppConfig.PREVIEW_RES_VALUES[position];
                if (value.equals(appConfig.getPreviewResolution())) {
                    return;
                }
                appConfig.setPreviewResolution(value);
                if (getContext() != null) {
                    Toast.makeText(getContext(),
                            "预览分辨率已设为 " + value + "，重启应用后生效",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        isInitializingPreviewResolution = false;
        updatePreviewResolutionEnabled(appConfig.isDecouplePreviewEnabled());
    }

    /** 「预览用低分辨率」关闭时，把分辨率选择灰掉——此时它不起作用。 */
    private void updatePreviewResolutionEnabled(boolean enabled) {
        if (previewResolutionSpinner != null) {
            previewResolutionSpinner.setEnabled(enabled);
            previewResolutionSpinner.setAlpha(enabled ? 1f : 0.4f);
        }
        if (previewResolutionDescText != null) {
            previewResolutionDescText.setText(enabled
                    ? "会在车机已声明的尺寸里挑最接近的，挑不到就退回录制尺寸"
                    : "需先开启上面的「预览用低分辨率」");
        }
    }

    /**
     * 初始化「预览用低分辨率」开关。
     *
     * <p>默认关闭。开启后预览缓冲区会选一个接近 640x480 的已声明尺寸，
     * 录制仍使用完整分辨率。能否生效取决于车机 HAL 是否支持这种组合，
     * 因此提示用户开启后要实际确认预览与录制都正常。</p>
     */
    private void initDecouplePreviewConfig(View view) {
        decouplePreviewToggle = view.findViewById(R.id.toggle_decouple_preview);
        if (decouplePreviewToggle == null || appConfig == null) {
            return;
        }

        decouplePreviewToggle.setChecked(appConfig.isDecouplePreviewEnabled());
        decouplePreviewToggle.setOnCheckedChangeListener((button, isChecked) -> {
            if (isChecked == appConfig.isDecouplePreviewEnabled()) {
                return;
            }
            appConfig.setDecouplePreviewEnabled(isChecked);
            updatePreviewResolutionEnabled(isChecked);
            if (getContext() != null) {
                Toast.makeText(getContext(),
                        isChecked
                                ? "已开启：预览低分辨率、录制完整分辨率。重启应用后生效，请确认预览与录制都正常"
                                : "已关闭：预览与录制共用同一分辨率。重启应用后生效",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 初始化分段时长配置
     */
    private void initSegmentDurationConfig(View view) {
        segmentDurationSpinner = view.findViewById(R.id.spinner_segment_duration);
        
        if (segmentDurationSpinner == null || getContext() == null) {
            return;
        }
        
        isInitializingSegmentDuration = true;
        lastAppliedSegmentDuration = (appConfig != null) ? appConfig.getSegmentDurationMinutes() : AppConfig.SEGMENT_DURATION_1_MIN;
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                SEGMENT_DURATION_OPTIONS
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        segmentDurationSpinner.setAdapter(adapter);
        
        segmentDurationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int newDuration;
                String durationName;
                
                if (position == 0) {
                    newDuration = AppConfig.SEGMENT_DURATION_1_MIN;
                    durationName = "1分钟";
                } else if (position == 1) {
                    newDuration = AppConfig.SEGMENT_DURATION_3_MIN;
                    durationName = "3分钟";
                } else {
                    newDuration = AppConfig.SEGMENT_DURATION_5_MIN;
                    durationName = "5分钟";
                }
                
                if (isInitializingSegmentDuration) {
                    return;
                }
                
                if (newDuration == lastAppliedSegmentDuration) {
                    return;
                }
                
                lastAppliedSegmentDuration = newDuration;
                appConfig.setSegmentDurationMinutes(newDuration);
                
                if (getContext() != null) {
                    Toast.makeText(getContext(), "分段时长已设置为「" + durationName + "」，下次录制生效", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        // 根据当前配置设置选中项
        int currentDuration = appConfig.getSegmentDurationMinutes();
        int selectedIndex = 0;  // 默认1分钟
        if (currentDuration == AppConfig.SEGMENT_DURATION_3_MIN) {
            selectedIndex = 1;
        } else if (currentDuration == AppConfig.SEGMENT_DURATION_5_MIN) {
            selectedIndex = 2;
        }
        segmentDurationSpinner.setSelection(selectedIndex);
        
        segmentDurationSpinner.post(() -> {
            isInitializingSegmentDuration = false;
        });
    }
    
    /**
     * 初始化录制摄像头选择配置
     */
    private void initRecordingCameraSelectionConfig(View view) {
        cbRecordCameraFront = view.findViewById(R.id.cb_record_camera_front);
        cbRecordCameraBack = view.findViewById(R.id.cb_record_camera_back);
        cbRecordCameraLeft = view.findViewById(R.id.cb_record_camera_left);
        cbRecordCameraRight = view.findViewById(R.id.cb_record_camera_right);
        
        if (cbRecordCameraFront == null || getContext() == null || appConfig == null) {
            return;
        }
        
        isInitializingRecordingCameraSelection = true;
        
        // 根据摄像头数量显示/隐藏对应的 CheckBox
        int cameraCount = appConfig.getCameraCount();
        
        // 前摄像头（1摄及以上都有）
        cbRecordCameraFront.setVisibility(cameraCount >= 1 ? View.VISIBLE : View.GONE);
        cbRecordCameraFront.setText(appConfig.getRecordingCameraDisplayName("front", 1));
        cbRecordCameraFront.setChecked(appConfig.isRecordingCameraEnabled("front"));
        
        // 后摄像头（2摄及以上才有）
        cbRecordCameraBack.setVisibility(cameraCount >= 2 ? View.VISIBLE : View.GONE);
        cbRecordCameraBack.setText(appConfig.getRecordingCameraDisplayName("back", 2));
        cbRecordCameraBack.setChecked(appConfig.isRecordingCameraEnabled("back"));
        
        // 第三路（3 摄及以上才有）——极氪「环视+座艡3路」配置正好是 3 摄，
        // 原来写的是 >= 4，导致第三路永远没有复选框
        cbRecordCameraLeft.setVisibility(cameraCount >= 3 ? View.VISIBLE : View.GONE);
        cbRecordCameraLeft.setText(appConfig.getRecordingCameraDisplayName("left", 3));
        cbRecordCameraLeft.setChecked(appConfig.isRecordingCameraEnabled("left"));
        
        // 右摄像头（4摄才有）
        cbRecordCameraRight.setVisibility(cameraCount >= 4 ? View.VISIBLE : View.GONE);
        cbRecordCameraRight.setText(appConfig.getRecordingCameraDisplayName("right", 4));
        cbRecordCameraRight.setChecked(appConfig.isRecordingCameraEnabled("right"));
        
        // 设置监听器
        android.widget.CompoundButton.OnCheckedChangeListener listener = (buttonView, isChecked) -> {
            if (isInitializingRecordingCameraSelection) {
                return;
            }
            
            // 检查是否至少有一个勾选
            if (!isChecked && !hasAtLeastOneRecordingCameraEnabled(buttonView)) {
                // 恢复勾选状态
                buttonView.setChecked(true);
                Toast.makeText(getContext(), "至少需要选择一个摄像头", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 保存设置
            String position = getPositionFromCheckBox(buttonView);
            if (position != null) {
                appConfig.setRecordingCameraEnabled(position, isChecked);
                String cameraName = ((android.widget.CheckBox) buttonView).getText().toString();
                String message = isChecked ? "已启用「" + cameraName + "」录制" : "已禁用「" + cameraName + "」录制";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        };
        
        cbRecordCameraFront.setOnCheckedChangeListener(listener);
        cbRecordCameraBack.setOnCheckedChangeListener(listener);
        cbRecordCameraLeft.setOnCheckedChangeListener(listener);
        cbRecordCameraRight.setOnCheckedChangeListener(listener);
        
        // 延迟结束初始化标记
        cbRecordCameraFront.post(() -> {
            isInitializingRecordingCameraSelection = false;
        });
    }
    
    /**
     * 更新录制摄像头选择的 UI（车型切换时调用）
     */
    private void updateRecordingCameraSelectionUI() {
        if (cbRecordCameraFront == null || getContext() == null || appConfig == null) {
            return;
        }
        
        isInitializingRecordingCameraSelection = true;
        
        // 根据摄像头数量显示/隐藏对应的 CheckBox
        int cameraCount = appConfig.getCameraCount();
        
        // 前摄像头（1摄及以上都有）
        cbRecordCameraFront.setVisibility(cameraCount >= 1 ? View.VISIBLE : View.GONE);
        cbRecordCameraFront.setText(appConfig.getRecordingCameraDisplayName("front", 1));
        cbRecordCameraFront.setChecked(appConfig.isRecordingCameraEnabled("front"));
        
        // 后摄像头（2摄及以上才有）
        cbRecordCameraBack.setVisibility(cameraCount >= 2 ? View.VISIBLE : View.GONE);
        cbRecordCameraBack.setText(appConfig.getRecordingCameraDisplayName("back", 2));
        cbRecordCameraBack.setChecked(appConfig.isRecordingCameraEnabled("back"));
        
        // 第三路（3 摄及以上才有）——极氪「环视+座艡3路」配置正好是 3 摄，
        // 原来写的是 >= 4，导致第三路永远没有复选框
        cbRecordCameraLeft.setVisibility(cameraCount >= 3 ? View.VISIBLE : View.GONE);
        cbRecordCameraLeft.setText(appConfig.getRecordingCameraDisplayName("left", 3));
        cbRecordCameraLeft.setChecked(appConfig.isRecordingCameraEnabled("left"));
        
        // 右摄像头（4摄才有）
        cbRecordCameraRight.setVisibility(cameraCount >= 4 ? View.VISIBLE : View.GONE);
        cbRecordCameraRight.setText(appConfig.getRecordingCameraDisplayName("right", 4));
        cbRecordCameraRight.setChecked(appConfig.isRecordingCameraEnabled("right"));
        
        // 延迟结束初始化标记
        cbRecordCameraFront.post(() -> {
            isInitializingRecordingCameraSelection = false;
        });
    }
    
    /**
     * 检查除了当前按钮外，是否还有至少一个摄像头被勾选
     */
    private boolean hasAtLeastOneRecordingCameraEnabled(View excludeButton) {
        if (cbRecordCameraFront != excludeButton && cbRecordCameraFront.getVisibility() == View.VISIBLE && cbRecordCameraFront.isChecked()) {
            return true;
        }
        if (cbRecordCameraBack != excludeButton && cbRecordCameraBack.getVisibility() == View.VISIBLE && cbRecordCameraBack.isChecked()) {
            return true;
        }
        if (cbRecordCameraLeft != excludeButton && cbRecordCameraLeft.getVisibility() == View.VISIBLE && cbRecordCameraLeft.isChecked()) {
            return true;
        }
        if (cbRecordCameraRight != excludeButton && cbRecordCameraRight.getVisibility() == View.VISIBLE && cbRecordCameraRight.isChecked()) {
            return true;
        }
        return false;
    }
    
    /**
     * 根据 CheckBox 获取对应的摄像头位置
     */
    private String getPositionFromCheckBox(View checkBox) {
        if (checkBox == cbRecordCameraFront) {
            return "front";
        } else if (checkBox == cbRecordCameraBack) {
            return "back";
        } else if (checkBox == cbRecordCameraLeft) {
            return "left";
        } else if (checkBox == cbRecordCameraRight) {
            return "right";
        }
        return null;
    }
    
    /**
     * 初始化存储位置配置
     * 注意：U盘检测涉及文件系统操作，需要异步执行避免卡顿
     */
    private void initStorageLocationConfig(View view) {
        storageLocationSpinner = view.findViewById(R.id.spinner_storage_location);
        storageLocationDescText = view.findViewById(R.id.tv_storage_location_desc);
        storageDebugButton = view.findViewById(R.id.btn_storage_debug);
        
        if (storageLocationSpinner == null || getContext() == null) {
            return;
        }
        
        isInitializingStorageLocation = true;
        lastAppliedStorageLocation = (appConfig != null) ? appConfig.getStorageLocation() : null;
        
        // 先使用默认状态初始化 UI（假设没有U盘，避免主线程阻塞）
        hasExternalSdCard = false;
        
        // 设置调试按钮点击事件（先显示，检测完后可能隐藏）
        if (storageDebugButton != null) {
            storageDebugButton.setVisibility(View.VISIBLE);
            storageDebugButton.setOnClickListener(v -> showStorageDebugInfo());
        }
        
        // 初始化 Spinner（先给一个占位，检测完成后由 rebuildStorageSpinner 重建）
        storageLocationOptions = new String[] {"内部存储", "外置存储（检测中...）"};

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                storageLocationOptions
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        storageLocationSpinner.setAdapter(adapter);
        
        storageLocationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newLocation;
                String locationName;

                if (position == 0) {
                    newLocation = AppConfig.STORAGE_INTERNAL;
                    locationName = "内部存储";
                    // 回到自动检测，清掉之前钉住的卷
                    if (!isInitializingStorageLocation && appConfig != null) {
                        appConfig.setCustomSdCardPath(null);
                    }
                } else {
                    newLocation = AppConfig.STORAGE_EXTERNAL_SD;
                    // 选中的是哪个卷？把它的根目录钉到 customSdCardPath，
                    // StorageHelper 的检测逻辑第一步就会优先用它，
                    // 这样插多个盘时不会再永远落到第一个上。
                    StorageHelper.VolumeInfo picked =
                            (position < storageVolumes.size()) ? storageVolumes.get(position) : null;
                    if (picked != null) {
                        locationName = picked.label;
                        if (!isInitializingStorageLocation && appConfig != null) {
                            appConfig.setCustomSdCardPath(picked.root.getAbsolutePath());
                        }
                    } else {
                        locationName = "外置存储";
                        if (!hasExternalSdCard && !isInitializingStorageLocation && getContext() != null) {
                            Toast.makeText(getContext(), "当前未检测到外置存储，录制将临时使用内部存储", Toast.LENGTH_LONG).show();
                        }
                    }
                }
                
                // 初始化期间（换 adapter 会先回调一次 position 0）什么都不做，
                // 描述文字由检测完成后的那次刷新负责
                if (isInitializingStorageLocation) {
                    return;
                }

                updateStorageLocationDescriptionAsync(newLocation);
                
                // 注意：不能只比较 internal/external_sd —— 在两个外置卷之间切换时
                // newLocation 是一样的，那样会被当成"没变化"直接 return。
                boolean sameAsBefore = newLocation.equals(lastAppliedStorageLocation)
                        && AppConfig.STORAGE_INTERNAL.equals(newLocation);
                if (sameAsBefore) {
                    return;
                }

                lastAppliedStorageLocation = newLocation;
                appConfig.setStorageLocation(newLocation);
                
                // 更新中转写入开关的可见性
                updateRelayWriteVisibility();
                
                if (getContext() != null) {
                    Toast.makeText(getContext(), "存储位置已切换为「" + locationName + "」", Toast.LENGTH_SHORT).show();
                    // 异步获取路径描述
                    new Thread(() -> {
                        String pathDesc = StorageHelper.getCurrentStoragePathDesc(getContext());
                        AppLog.d("SettingsFragment", "存储位置已切换为: " + newLocation + "，路径: " + pathDesc);
                    }).start();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        String currentLocation = appConfig.getStorageLocation();
        int selectedIndex = 0;
        // 保持用户选择的存储位置，即使外置存储不可用也显示选中状态
        if (AppConfig.STORAGE_EXTERNAL_SD.equals(currentLocation)) {
            selectedIndex = 1;
        }
        storageLocationSpinner.setSelection(selectedIndex);

        
        // 显示加载中状态
        if (storageLocationDescText != null) {
            storageLocationDescText.setText("正在检测存储设备...");
        }
        
        // 只做一次检测。
        //
        // 这里原本有两段异步检测在赛跑：旧的那段用写死的「内部存储 / U盘」两项重建
        // spinner，新的那段用真实卷列表重建，谁后完成谁说了算 —— 于是首次进入常常
        // 显示成「U盘（未检测到）」，退出再进才正常。更糟的是旧那段在 setAdapter 前
        // 没有置 isInitializingStorageLocation，Spinner 换 adapter 时会先回调一次
        // onItemSelected(0)，被当成用户选了「内部存储」写进配置，
        // 于是下面的「中转写入」选项也跟着消失了。
        //
        // 现在合并成一次：枚举卷 -> 由卷列表推导 hasExternalSdCard -> 重建 spinner
        // （带初始化标志）-> 更新描述与中转写入可见性。
        final String finalCurrentLocation = currentLocation;
        final Context detectContext = getContext();
        new Thread(() -> {
            final java.util.List<StorageHelper.VolumeInfo> detected =
                    StorageHelper.listExternalVolumes(detectContext);

            if (getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> {
                if (getContext() == null || storageLocationSpinner == null) {
                    return;
                }

                storageVolumes = buildVolumeSlots(detected);
                hasExternalSdCard = !detected.isEmpty();

                if (storageDebugButton != null) {
                    storageDebugButton.setVisibility(hasExternalSdCard ? View.GONE : View.VISIBLE);
                }

                rebuildStorageSpinner(finalCurrentLocation);

                // 检测完成后才更新描述，否则会用尚未确定的状态算出"未检测到U盘"
                updateStorageLocationDescriptionAsync(finalCurrentLocation);
                // 中转写入的可见性也要在这时候复位，
                // 以防初始化过程中的回调把它藏起来了
                updateRelayWriteVisibility();
            });
        }).start();
        
        // 初始化中转写入开关
        initRelayWriteConfig(view);
    }
    
    /**
     * 初始化中转写入配置
     */
    private void initRelayWriteConfig(View view) {
        relayWriteSwitch = view.findViewById(R.id.switch_relay_write);
        relayWriteDescText = view.findViewById(R.id.tv_relay_write_desc);
        
        if (relayWriteSwitch == null || getContext() == null) {
            return;
        }
        
        isInitializingRelayWrite = true;
        
        // 加载当前设置
        boolean relayWriteEnabled = appConfig.isRelayWriteEnabled();
        relayWriteSwitch.setChecked(relayWriteEnabled);
        updateRelayWriteDescription(relayWriteEnabled);
        
        // 根据存储位置显示/隐藏中转写入选项
        updateRelayWriteVisibility();
        
        relayWriteSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isInitializingRelayWrite) {
                return;
            }
            
            appConfig.setRelayWriteEnabled(isChecked);
            updateRelayWriteDescription(isChecked);
            
            String message = isChecked ? 
                    "中转写入已开启：视频先写入内部存储再传输到U盘，避免录制卡顿" : 
                    "中转写入已关闭：视频直接写入U盘，可能因U盘速度慢导致录制卡顿";
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        });
        
        isInitializingRelayWrite = false;
    }
    
    /**
     * 更新中转写入开关的可见性
     * 仅在U盘存储时显示
     */
    private void updateRelayWriteVisibility() {
        if (relayWriteSwitch == null || relayWriteDescText == null) {
            return;
        }
        
        ViewGroup parent = (ViewGroup) relayWriteSwitch.getParent();
        if (parent != null) {
            boolean useExternalSd = appConfig.isUsingExternalSdCard();
            parent.setVisibility(useExternalSd ? View.VISIBLE : View.GONE);
        }
    }
    
    /**
     * 更新中转写入描述文字
     */
    private void updateRelayWriteDescription(boolean enabled) {
        if (relayWriteDescText == null) {
            return;
        }
        
        if (enabled) {
            relayWriteDescText.setText("已开启：视频先写入内部存储再传输到U盘，避免录制卡顿");
            relayWriteDescText.setTextColor(ContextCompat.getColor(getContext(), R.color.button_accent));
        } else {
            relayWriteDescText.setText("已关闭：视频直接写入U盘，可能因U盘速度慢导致录制卡顿");
            relayWriteDescText.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        }
    }
    
    /**
     * 更新存储位置描述文字（同步版本，仅在已有数据时使用）
     * @deprecated 请使用 {@link #updateStorageLocationDescriptionAsync(String)} 避免主线程阻塞
     */
    @Deprecated
    private void updateStorageLocationDescription(String location) {
        // 直接调用异步版本
        updateStorageLocationDescriptionAsync(location);
    }
    
    /**
     * 异步更新存储位置描述文字
     * 避免在主线程执行文件系统 I/O 操作导致卡顿
     */
    private void updateStorageLocationDescriptionAsync(String location) {
        if (storageLocationDescText == null || getContext() == null) {
            return;
        }
        
        // 先显示加载状态
        storageLocationDescText.setText("正在获取存储信息...");
        
        final Context context = getContext();
        final boolean useExternal = AppConfig.STORAGE_EXTERNAL_SD.equals(location);
        final boolean isFallback = useExternal && !hasExternalSdCard;
        
        new Thread(() -> {
            // 在后台线程执行耗时的 I/O 操作
            java.io.File videoDir = useExternal ? 
                    StorageHelper.getVideoDir(context, true) :
                    StorageHelper.getVideoDir(context, false);
            String path = videoDir.getAbsolutePath();
            
            // 获取内部存储根路径用于判断
            String internalRoot = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            
            // 简化路径显示
            String displayPath;
            if (path.startsWith(internalRoot + "/")) {
                // 是内部存储
                displayPath = path.replace(internalRoot + "/", "内部存储/");
            } else if (path.startsWith("/storage/emulated/")) {
                // 其他 emulated 路径也是内部存储
                displayPath = "内部存储" + path.substring(path.indexOf("/", "/storage/emulated/".length()));
            } else if (path.matches("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}/.*")) {
                // XXXX-XXXX 格式是 SD 卡
                int dcimIndex = path.indexOf("/DCIM/");
                if (dcimIndex > 0) {
                    displayPath = "U盘" + path.substring(dcimIndex);
                } else {
                    displayPath = "U盘/" + path.substring(path.lastIndexOf("/") + 1);
                }
            } else {
                // 其他路径原样显示
                displayPath = path;
            }
            
            // 获取容量信息
            long availableSpace = StorageHelper.getAvailableSpace(videoDir);
            long totalSpace = StorageHelper.getTotalSpace(videoDir);
            String availableStr = StorageHelper.formatSize(availableSpace);
            String totalStr = StorageHelper.formatSize(totalSpace);
            
            // 构建最终显示文字
            final String finalText;
            if (isFallback) {
                finalText = "⚠ U盘不可用，临时使用内部存储\n" + displayPath + "\n可用: " + availableStr + " / 共: " + totalStr;
            } else {
                finalText = displayPath + "\n可用: " + availableStr + " / 共: " + totalStr;
            }
            
            // 回到主线程更新 UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (storageLocationDescText != null) {
                        storageLocationDescText.setText(finalText);
                    }
                });
            }
        }).start();
    }
    
    /**
     * 显示存储设备调试信息
     */
    private void showStorageDebugInfo() {
        if (getContext() == null) {
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        
        // 首先检测存储权限状态
        sb.append("=== 存储权限状态 ===\n");
        
        // 检查所有文件访问权限（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean hasAllFilesAccess = android.os.Environment.isExternalStorageManager();
            sb.append("所有文件访问权限 (Android 11+): ");
            if (hasAllFilesAccess) {
                sb.append("已授权 ✓\n");
            } else {
                sb.append("未授权 ✗\n");
                sb.append("⚠️ 提示: 访问U盘需要此权限！\n");
                sb.append("   请前往「权限设置」授予「所有文件访问权限」\n");
            }
        } else {
            sb.append("Android 版本低于 11，无需「所有文件访问权限」\n");
        }
        
        // 检查基础存储权限
        boolean hasStoragePermission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasStoragePermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    getContext(), android.Manifest.permission.READ_MEDIA_VIDEO) 
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            sb.append("媒体文件权限 (Android 13+): ");
        } else {
            hasStoragePermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    getContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            sb.append("存储读写权限: ");
        }
        sb.append(hasStoragePermission ? "已授权 ✓\n" : "未授权 ✗\n");
        
        // 显示当前自定义路径
        String customPath = appConfig.getCustomSdCardPath();
        sb.append("\n=== 自定义U盘路径 ===\n");
        if (customPath != null) {
            sb.append("当前设置: " + customPath + "\n");
            java.io.File customDir = new java.io.File(customPath);
            sb.append("路径状态: " + (customDir.exists() ? "存在" : "不存在") + 
                    ", " + (customDir.canWrite() ? "可写" : "不可写") + "\n");
        } else {
            sb.append("未设置（使用自动检测）\n");
        }
        
        sb.append("\n");
        
        // 然后显示存储设备检测信息
        List<String> debugInfo = StorageHelper.getStorageDebugInfo(getContext());
        for (String line : debugInfo) {
            sb.append(line).append("\n");
        }
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("存储设备检测信息")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .setNeutralButton("复制", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = 
                            (android.content.ClipboardManager) getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("存储调试信息", sb.toString());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(getContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("手动设置路径", (dialog, which) -> {
                    showManualSdCardPathDialog();
                })
                .show();
    }
    
    /**
     * 显示手动设置U盘路径对话框
     */
    private void showManualSdCardPathDialog() {
        if (getContext() == null) return;
        
        android.widget.EditText input = new android.widget.EditText(getContext());
        input.setHint("例如: /storage/ABCD-1234");
        input.setSingleLine(true);
        // 适配夜间模式
        input.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        input.setBackgroundResource(R.drawable.edit_text_background);
        
        // 显示当前设置的路径
        String currentPath = appConfig.getCustomSdCardPath();
        if (currentPath != null) {
            input.setText(currentPath);
        }
        
        // 设置边距
        android.widget.FrameLayout container = new android.widget.FrameLayout(getContext());
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = 48;
        params.rightMargin = 48;
        input.setLayoutParams(params);
        container.addView(input);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("手动设置U盘路径")
                .setMessage("如果自动检测失败，你可以手动输入U盘的挂载路径。\n\n" +
                        "常见格式：/storage/XXXX-XXXX（十六进制ID）\n\n" +
                        "留空表示使用自动检测。")
                .setView(container)
                .setPositiveButton("保存", (dialog, which) -> {
                    String path = input.getText().toString().trim();
                    if (path.isEmpty()) {
                        appConfig.setCustomSdCardPath(null);
                        Toast.makeText(getContext(), "已清除自定义路径，使用自动检测", Toast.LENGTH_SHORT).show();
                    } else {
                        java.io.File testDir = new java.io.File(path);
                        if (!testDir.exists()) {
                            Toast.makeText(getContext(), "警告：路径不存在，但已保存", Toast.LENGTH_LONG).show();
                        } else if (!testDir.isDirectory()) {
                            Toast.makeText(getContext(), "警告：路径不是目录，但已保存", Toast.LENGTH_LONG).show();
                        } else if (!testDir.canWrite()) {
                            Toast.makeText(getContext(), "警告：路径不可写，但已保存", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(getContext(), "U盘路径已设置", Toast.LENGTH_SHORT).show();
                        }
                        appConfig.setCustomSdCardPath(path);
                    }
                    
                    // 重新检测并更新UI
                    hasExternalSdCard = StorageHelper.hasExternalSdCard(getContext());
                    if (storageDebugButton != null) {
                        storageDebugButton.setVisibility(hasExternalSdCard ? View.GONE : View.VISIBLE);
                    }
                    if (hasExternalSdCard && storageLocationSpinner != null) {
                        storageLocationOptions = new String[] {"内部存储", "U盘"};
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                getContext(),
                                R.layout.spinner_item,
                                storageLocationOptions
                        );
                        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                        storageLocationSpinner.setAdapter(adapter);
                    }
                    String currentLocation = appConfig.getStorageLocation();
                    updateStorageLocationDescriptionAsync(currentLocation);
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 初始化存储清理配置
     */
    private void initStorageCleanupConfig(View view) {
        videoStorageLimitEdit = view.findViewById(R.id.et_video_storage_limit);
        photoStorageLimitEdit = view.findViewById(R.id.et_photo_storage_limit);
        videoUsedSizeText = view.findViewById(R.id.tv_video_used_size);
        photoUsedSizeText = view.findViewById(R.id.tv_photo_used_size);
        
        if (videoStorageLimitEdit == null || photoStorageLimitEdit == null || getContext() == null) {
            return;
        }
        
        isInitializingStorageCleanup = true;
        
        // 加载当前设置
        int videoLimit = appConfig.getVideoStorageLimitGb();
        int photoLimit = appConfig.getPhotoStorageLimitGb();
        
        // 设置初始值（0显示为空）
        if (videoLimit > 0) {
            videoStorageLimitEdit.setText(String.valueOf(videoLimit));
        } else {
            videoStorageLimitEdit.setText("");
        }
        
        if (photoLimit > 0) {
            photoStorageLimitEdit.setText(String.valueOf(photoLimit));
        } else {
            photoStorageLimitEdit.setText("");
        }
        
        // 更新当前占用大小显示
        updateStorageUsedSizeDisplay();
        
        // 添加文本变化监听器 - 视频
        videoStorageLimitEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (isInitializingStorageCleanup) {
                    return;
                }
                
                int limit = 0;
                String text = s.toString().trim();
                if (!text.isEmpty()) {
                    try {
                        limit = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        // 忽略无效输入
                    }
                }
                
                appConfig.setVideoStorageLimitGb(limit);
                AppLog.d("SettingsFragment", "视频存储限制已设置为: " + limit + " GB");
                
                // 通知 MainActivity 重启清理任务
                notifyStorageCleanupConfigChanged();
            }
        });
        
        // 添加文本变化监听器 - 图片
        photoStorageLimitEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (isInitializingStorageCleanup) {
                    return;
                }
                
                int limit = 0;
                String text = s.toString().trim();
                if (!text.isEmpty()) {
                    try {
                        limit = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        // 忽略无效输入
                    }
                }
                
                appConfig.setPhotoStorageLimitGb(limit);
                AppLog.d("SettingsFragment", "图片存储限制已设置为: " + limit + " GB");
                
                // 通知 MainActivity 重启清理任务
                notifyStorageCleanupConfigChanged();
            }
        });
        
        // 延迟结束初始化标记
        videoStorageLimitEdit.post(() -> {
            isInitializingStorageCleanup = false;
        });
    }
    
    /**
     * 更新存储占用大小显示
     */
    private void updateStorageUsedSizeDisplay() {
        if (getContext() == null) {
            return;
        }
        
        // 在后台线程计算大小，避免阻塞UI
        new Thread(() -> {
            StorageCleanupManager cleanupManager = new StorageCleanupManager(getContext());
            long videoSize = cleanupManager.getVideoUsedSize();
            long photoSize = cleanupManager.getPhotoUsedSize();
            
            String videoSizeStr = "已用: " + StorageHelper.formatSize(videoSize);
            String photoSizeStr = "已用: " + StorageHelper.formatSize(photoSize);
            
            // 回到主线程更新UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (videoUsedSizeText != null) {
                        videoUsedSizeText.setText(videoSizeStr);
                    }
                    if (photoUsedSizeText != null) {
                        photoUsedSizeText.setText(photoSizeStr);
                    }
                });
            }
        }).start();
    }
    
    /**
     * 通知 MainActivity 存储清理配置已更改
     */
    private void notifyStorageCleanupConfigChanged() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).restartStorageCleanupTask();
        }
    }
    
    /**
     * 更新日志按钮区域的可见性（仅 Debug 开启时显示）
     */
    private void updateSaveLogsButtonVisibility(boolean visible) {
        if (logButtonsLayout != null) {
            logButtonsLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
    
    /**
     * 打开自定义摄像头配置界面
     */
    private void openCustomCameraConfig() {
        if (getActivity() == null) {
            return;
        }
        
        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, new CustomCameraConfigFragment());
        transaction.addToBackStack(null);
        transaction.commit();
    }
    
    /**
     * 打开分辨率设置界面
     */
    private void openResolutionSettings() {
        if (getActivity() == null) {
            return;
        }
        
        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, new ResolutionSettingsFragment());
        transaction.addToBackStack(null);
        transaction.commit();
    }
    
    // ==================== 日志上传相关方法 ====================
    
    /**
     * 显示设备名称输入对话框（首次上传时）
     */
    private void showDeviceNicknameInputDialog() {
        if (getContext() == null) return;
        
        EditText inputEditText = new EditText(getContext());
        inputEditText.setInputType(InputType.TYPE_CLASS_TEXT);
        inputEditText.setHint("例如：张三的银河E5");
        inputEditText.setPadding(48, 32, 48, 32);
        // 适配夜间模式
        inputEditText.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        inputEditText.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        inputEditText.setBackgroundResource(R.drawable.edit_text_background);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("设置设备识别名称")
                .setMessage("请输入一个便于识别的名称，用于区分不同用户的日志：")
                .setView(inputEditText)
                .setPositiveButton("确认", (dialog, which) -> {
                    String nickname = inputEditText.getText().toString().trim();
                    if (nickname.isEmpty()) {
                        Toast.makeText(getContext(), "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 显示二次确认
                    showNicknameConfirmDialog(nickname);
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 显示设备名称二次确认对话框（首次设置名称后）
     */
    private void showNicknameConfirmDialog(String nickname) {
        if (getContext() == null) return;
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("确认设备名称")
                .setMessage("您输入的设备名称是：\n\n「" + nickname + "」\n\n确认使用此名称吗？")
                .setPositiveButton("确认", (dialog, which) -> {
                    // 保存名称，然后显示上传确认框
                    if (appConfig != null) {
                        appConfig.setDeviceNickname(nickname);
                    }
                    showUploadConfirmDialog(nickname);
                })
                .setNegativeButton("重新输入", (dialog, which) -> {
                    // 重新显示输入框
                    showDeviceNicknameInputDialog();
                })
                .show();
    }
    
    /**
     * 显示上传确认对话框（包含名称确认和问题描述输入）
     */
    private void showUploadConfirmDialog(String nickname) {
        if (getContext() == null) return;
        
        // 创建包含名称显示和问题描述输入的布局
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 8);
        
        // 名称显示 - 适配夜间模式
        TextView nicknameLabel = new TextView(getContext());
        nicknameLabel.setText("上传身份：「" + nickname + "」");
        nicknameLabel.setTextSize(16);
        nicknameLabel.setPadding(0, 0, 0, 24);
        nicknameLabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        layout.addView(nicknameLabel);
        
        // 日志选择标签
        TextView logTypeLabel = new TextView(getContext());
        logTypeLabel.setText("选择日志：");
        logTypeLabel.setTextSize(14);
        logTypeLabel.setPadding(0, 0, 0, 8);
        logTypeLabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        layout.addView(logTypeLabel);
        
        // 日志选择 RadioGroup
        RadioGroup logTypeGroup = new RadioGroup(getContext());
        logTypeGroup.setOrientation(RadioGroup.VERTICAL);
        logTypeGroup.setPadding(0, 0, 0, 16);
        
        // 本次运行日志选项
        RadioButton currentLogRadio = new RadioButton(getContext());
        currentLogRadio.setId(View.generateViewId());
        currentLogRadio.setText("本次运行日志");
        currentLogRadio.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        currentLogRadio.setChecked(true);
        logTypeGroup.addView(currentLogRadio);
        
        // 上次运行日志选项
        RadioButton previousLogRadio = new RadioButton(getContext());
        previousLogRadio.setId(View.generateViewId());
        boolean hasPrevious = AppLog.hasPreviousSessionLogs(getContext());
        if (hasPrevious) {
            String prevInfo = AppLog.getPreviousSessionLogInfo(getContext());
            previousLogRadio.setText("上次运行日志" + (prevInfo != null ? "\n  " + prevInfo : ""));
            previousLogRadio.setEnabled(true);
        } else {
            previousLogRadio.setText("上次运行日志（无可用日志）");
            previousLogRadio.setEnabled(false);
        }
        previousLogRadio.setTextColor(ContextCompat.getColor(getContext(), 
                hasPrevious ? R.color.text_primary : R.color.text_secondary));
        logTypeGroup.addView(previousLogRadio);
        
        layout.addView(logTypeGroup);
        
        // 问题描述标签 - 适配夜间模式
        TextView descLabel = new TextView(getContext());
        descLabel.setText("问题描述：");
        descLabel.setTextSize(14);
        descLabel.setPadding(0, 0, 0, 8);
        descLabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        layout.addView(descLabel);
        
        // 问题描述输入框 - 适配夜间模式
        EditText inputEditText = new EditText(getContext());
        inputEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        inputEditText.setMinLines(3);
        inputEditText.setMaxLines(6);
        inputEditText.setHint("请描述遇到的问题...");
        inputEditText.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        inputEditText.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        inputEditText.setBackgroundResource(R.drawable.edit_text_background);
        layout.addView(inputEditText);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("上传日志")
                .setView(layout)
                .setPositiveButton("上传", (dialog, which) -> {
                    String problemDesc = inputEditText.getText().toString().trim();
                    if (problemDesc.isEmpty()) {
                        problemDesc = "（用户未填写问题描述）";
                    }
                    // 判断选择了哪个日志
                    boolean uploadPreviousSession = previousLogRadio.isChecked();
                    performLogUpload(nickname, problemDesc, uploadPreviousSession);
                })
                .setNeutralButton("修改名称", (dialog, which) -> {
                    showDeviceNicknameInputDialog();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 执行日志上传（默认上传本次运行日志）
     */
    private void performLogUpload(String deviceNickname, String problemDescription) {
        performLogUpload(deviceNickname, problemDescription, false);
    }
    
    /**
     * 执行日志上传
     * @param uploadPreviousSession 是否上传上次运行的日志
     */
    private void performLogUpload(String deviceNickname, String problemDescription, boolean uploadPreviousSession) {
        if (getContext() == null) return;
        
        // 禁用按钮防止重复点击
        uploadLogsButton.setEnabled(false);
        uploadLogsButton.setText("上传中...");
        
        String logType = uploadPreviousSession ? "上次运行" : "本次运行";
        
        AppLog.uploadLogsToServer(getContext(), deviceNickname, problemDescription, uploadPreviousSession, new AppLog.UploadCallback() {
            @Override
            public void onSuccess() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        uploadLogsButton.setEnabled(true);
                        uploadLogsButton.setText("一键上传");
                        Toast.makeText(getContext(), "作者已收到" + logType + "日志", Toast.LENGTH_LONG).show();
                    });
                }
            }

            @Override
            public void onError(String error) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        uploadLogsButton.setEnabled(true);
                        uploadLogsButton.setText("一键上传");
                        Toast.makeText(getContext(), "上传失败: " + error, Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }
}
