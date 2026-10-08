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
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
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
import com.kooo.evcam.recording.RecordingIntent;
import com.kooo.evcam.recording.RecordingStops;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.FileTransferManager;
import com.kooo.evcam.StorageHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    /** 别的界面上的菜单键：回到这里，并且把抽屉拉开。 */
    public static final String EXTRA_OPEN_DRAWER = "open_drawer";

    /** 悬浮按钮在应用没运行时按了「拍照」：拉起来，等相机就绪再拍。 */

    /** 悬浮按钮在应用还活着时按了「拍照」。 */

    /** 环视流探测结果的那几行文字；相机开起来后可能被实际尺寸替掉。 */
    private String compositeProbeInfo;

    /** 探测出来的尺寸，用来和相机真正在用的那个对比。 */
    private android.util.Size compositeProbeSize;

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

    private AutoFitTextureView textureFront, textureBack, textureLeft;
    /** 极氪合成流四宫格容器；非该车型时为 null。 */
    private com.kooo.evcam.zeekr.FourLaneContainer compositeContainer;
    private TextView tvCompositeInfo;

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
    private Button btnMinimize, btnTakePhoto;

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
    /**
     * 录不录、能不能录、什么时候录、停了接不接，都由它决定（进程里只有一份）；
     * 这里只负责把它的结果画到屏幕上。
     */
    private RecordingCoordinator recordingCoordinator;
    /** 悬浮按钮拉起主界面来开录：录起来之后让用户看一眼，再把主界面收到后台。 */
    private boolean hideMainAfterStart;

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
    
    // 防双击保护
    private long lastRecordButtonClickTime = 0;  // 上次点击录制按钮的时间
    private static final long RECORD_BUTTON_CLICK_INTERVAL = 1000;  // 最小点击间隔（1秒）
    
    // 录制异常提示防抖
    private long lastRecordingErrorToastTime = 0;  // 上次显示录制异常提示的时间
    private static final long RECORDING_ERROR_TOAST_INTERVAL = 20000;  // 最小显示间隔（20秒）
    private boolean shouldMoveToBackgroundOnReady = false;  // 开机自启动后，窗口准备好时移到后台

    // 摄像头重连防抖相关
    
    // 悬浮按钮发来的广播
    private android.content.BroadcastReceiver toggleRecordingReceiver;  // 录制切换广播接收器（来自悬浮窗）

    // 熄屏：屏幕状态只有 ScreenState 一份（熄屏广播 + 黑着时每 2 秒问系统一次代替亮屏广播）。
    // 唤醒锁、录像的 10 秒停 / 亮屏接回、1.5 秒没人要就关相机、亮屏把退下去的界面接回来，
    // 都在它那里按固定顺序做完；主界面只剩自己的事 —— 熄屏 15 秒退后台
    private final android.os.Handler screenStateHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());  // 熄屏退后台的延迟处理
    private Runnable screenOffBackgroundRunnable;  // 息屏退后台的延迟任务
    private static final long SCREEN_OFF_BACKGROUND_DELAY_MS = 15000;  // 息屏后等待15秒（退后台）

    /**
     * 熄屏 / 亮屏（{@link com.kooo.evcam.screen.ScreenState} 在主线程调；onStart 登记、onStop 摘掉，
     * 和存储监听放在一起）。只管界面自己的事：熄屏 15 秒退后台，亮屏取消它。
     * 接回录像、接回界面不在这里 —— ScreenState 在叫到这里之前已经做完了。
     */
    private final com.kooo.evcam.screen.ScreenState.Listener screenListener =
            new com.kooo.evcam.screen.ScreenState.Listener() {
                @Override
                public void onScreenOff() {
                    AppLog.d(TAG, "检测到息屏，15 秒后退后台（录像中、熄屏录制生效则留在前台）");
                    scheduleBackgroundTask();
                }

                @Override
                public void onScreenOn() {
                    AppLog.d(TAG, "检测到亮屏，取消退后台任务");
                    cancelBackgroundTask();
                }
            };


    // 车型配置相关
    private AppConfig appConfig;
    private int configuredCameraCount = 4;  // 配置的摄像头数量
    // 录制状态显示相关
    private TextView tvRecordingStats;
    private android.os.Handler recordingTimerHandler;

    private Runnable recordingTimerRunnable;
    /** 状态条上现在显示的「换过盘」状态。换盘发生在相机层，界面靠计时器每秒对一下。 */
    private String[] shownFallback;
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


    private boolean isPreparingRecording = false;  // 是否正在准备录制（等待首次写入）

    /**
     * 「准备中」最多等多久。
     *
     * <p>开始录制之后，要等录制器写出第一笔数据才算真的录上。以前这一步<b>没有超时</b>：
     * 第一笔数据一直不来，按钮就永远停在「正在准备」，而录制器其实早就没在录了。
     * 到点就按「没收到画面」停掉（{@code RecordingStops.Reason.NO_DATA}）；
     * 停了之后接不接、还剩几次额度，由 {@link RecordingCoordinator} 判。</p>
     */
    private final android.os.Handler preparingHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** 状态条最右那一格的正文（合成流识别结果）；环视被拿走时那一格临时改写成提示。 */
    private String compositeInfoText = "";
    private boolean cameraTakenHint;
    /** 存储快照变了（U 盘插拔、换盘、定时探测）：录制键可不可录、状态条余量跟着变。 */
    private final com.kooo.evcam.storage.StorageState.Listener storageListener = snapshot -> {
        refreshRecordAvailability();
        updateStatusLine();
    };


    // 存储清理管理器
    private StorageCleanupManager storageCleanupManager;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.kooo.evcam.blackbox.BlackBox.attach(this, "Activity:MainActivity");
        com.kooo.evcam.blackbox.BlackBox.note("主界面 onCreate savedState=" + (savedInstanceState != null));
        // 用户退出之后，所有会自己拉起主界面的路都被拦住了 —— 这时主界面被创建，只能是人点开的
        UserExit.clear(this, "manual open");
        // 人点开的主界面 = 新的一趟（规格 1.2）：手动停录、自动开过的记录清零。
        // 我们自己静默拉起来的（恢复录像、亮屏接回）不算，界面重建（savedState 非空）也不算
        if (savedInstanceState == null
                && !getIntent().getBooleanExtra(com.kooo.evcam.recovery.Recovery.EXTRA_SILENT, false)
                && !getIntent().getBooleanExtra("restored_by_app", false)) {
            com.kooo.evcam.recording.RecordingIntent.current().reset();
        }
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

        // 录像的唯一入口是进程级的协调器：相机管理器还没建出来时就可以先「要录」，
        // 它会等相机交过来、环视出画面再开（见 attachRecordingCoordinator）
        recordingCoordinator = RecordingCoordinator.get(this);
        recordingCoordinator.addListener(recordingListener);

        // 重置U盘回退提示标志（每次冷启动重置）
        AppConfig.resetSdFallbackFlag();
        
        // 根据车型配置设置布局和摄像头数量
        setupLayoutByCarModel();

        // 设置状态栏沉浸式
        setupStatusBar();

        initViews();
        setupNavigationDrawer();

        // 界面重建（切日夜模式、换语言）时录制管线一直在跑（见 onDestroy 的 keepPipeline），
        // 新界面只是把它现在的样子画出来（syncRecordingStateFromManager），没有「恢复录制」这回事

        // 检查是否首次启动
        checkFirstLaunch();

// 权限检查，但不立即初始化摄像头
        // 等待TextureView准备好后再初始化
        if (!checkPermissions()) {
            requestPermissions();
        }


        // 定时保活任务：只在「开机自启动」开关打开时登记，关着就取消（见 KeepAliveManager）
        KeepAliveManager.startKeepAliveWork(this);
        AppLog.d(TAG, "定时保活任务已启动");
        
        // 唤醒锁只属于「熄屏录制（阻止休眠）」（规格 §3.1）：熄屏时在录像才拿，见 ScreenOffRecording
                
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

            // 静默拉起来的主界面闪一下就退后台（规格 1.6）：录像不靠主界面显示，
            // 退到后台照常录；开始录像由协调器在环视出画面时开（checkAutoStartRecording 只是提出要录）
            AppLog.d(TAG, "开机自启动模式：等待窗口准备好后移到后台");
            shouldMoveToBackgroundOnReady = true;
        }

        // 从录制悬浮按钮拉起来的：要录。相机还没建，协调器先记着，环视出画面就开
        if (getIntent().getBooleanExtra("auto_start_recording", false)) {
            requestRecordingFromFloatingButton(getIntent());
        }

        // 按设置把该开的悬浮窗恢复出来（画面悬浮窗 / 超级后视镜 /
        // 录制悬浮按钮 / 补盲）。该不该开、能不能开都在协调器里判断。
        OverlayCoordinator.restoreOnLaunch(this);
        
        // 悬浮按钮发来的录制切换 / 拍照广播
        // 屏幕状态只听 ScreenState；整个界面生命周期都听着（熄屏时界面可能先被暂停、广播后到）
        com.kooo.evcam.screen.ScreenState.addListener(screenListener);
        initToggleRecordingReceiver();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        AppLog.d(TAG, "onNewIntent called");
        openDrawerIfAsked(intent);

// 处理从录制悬浮按钮启动（需要自动开始录制）
        if (intent.getBooleanExtra("auto_start_recording", false)) {
            requestRecordingFromFloatingButton(intent);
        }
    }

    /**
     * 悬浮按钮在主界面不在前台时按了「开始录制」，把主界面拉了起来。
     *
     * <p>人按的：前面的失败都不算了。什么时候开由协调器定（环视出画面就开）；
     * 录起来之后让用户看一眼，再把主界面收回后台（见 {@link #recordingListener}）。
     * 以前这里是「等一秒再等两秒再开」的梯子，onCreate 和 onNewIntent 各抄一份 —— 都归到协调器那一个等待里。</p>
     */
    private void requestRecordingFromFloatingButton(Intent intent) {
        intent.removeExtra("auto_start_recording");
        AppLog.d(TAG, "从录制悬浮按钮启动，交给协调器开录");
        hideMainAfterStart = true;
        RecordingIntent.current().noteUserStarted();
        recordingCoordinator.resetBudget();
        recordingCoordinator.request(RecordingCoordinator.Why.FLOATING);
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
     * 取值只会是极氪的两档。以前这里还排着银河 E5 / L6 / L7 / 星舰 / 手机
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

        if (enabled > 1) {
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
        
        updateCameraLabels();

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
                        cameraManager.updatePreviewTextureViews(textureFront, textureBack, textureLeft, null);
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
        
        // 设置自定义名称，如果名称为空则隐藏标签。
        // 单路布局里这几个是四宫格的方向角标（前后左右，布局里写好了），不是相机槽位的名字：
        // 按槽位取名的话 front 是合成流，左上那一格就成了「环视」
        if (findViewById(R.id.cabin_column) == null) {
            return;
        }
        if (labelFront != null) {
            updateCameraLabel(labelFront, appConfig.getCameraName(this, "front"));
        }
        if (labelBack != null && configuredCameraCount >= 2) {
            updateCameraLabel(labelBack, appConfig.getCameraName(this, "back"));
        }
        if (labelLeft != null && configuredCameraCount >= 3) {
            updateCameraLabel(labelLeft, appConfig.getCameraName(this, "left"));
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
     * 初始化录制状态显示
     */
    private void initRecordingStatsDisplay() {
        if (tvRecordingStats == null) {
            return;
        }
        
        // 从设置加载显示开关状态
        applyRecordingStatsSetting();
        
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
     * 按设置里的「录制状态显示」画角标。主界面建好时读一次，从设置回到主界面时再读一次 ——
     * 以前只在 onCreate 读，设置里切了要等界面重建才生效。不在录时角标是 GONE，只记下开关。
     */
    private void applyRecordingStatsSetting() {
        isRecordingStatsEnabled = appConfig.isRecordingStatsEnabled();
        if (tvRecordingStats != null) {
            tvRecordingStats.setAlpha(isRecordingStatsEnabled ? 1.0f : 0.0f);
        }
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
                    if (StorageHelper.recordingFallback() != shownFallback) {
                        updateStatusLine();
                    }
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
     * <p>判断和拒录是同一个（快照里的 {@code available}，源头都是 {@code StorageHelper.isRecordingStorageAvailable}）——
     * 按钮不会显示能录、按下去却被拒。录制中不动它：盘拔了由录制链路自己处理。</p>
     */
    private void refreshRecordAvailability() {
        if (recordButtonUi == null || isRecording) {
            return;
        }
        // 只读快照，不碰盘；还没探测过时先按能录画，探测完监听器会再画一次
        com.kooo.evcam.storage.StorageState.Snapshot storage = com.kooo.evcam.storage.StorageState.current();
        recordButtonUi.setState(!storage.known || storage.available
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
        shownFallback = StorageHelper.recordingFallback();
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
                // 后视镜也可能在设置里、或者悬浮窗自己关掉过
                syncRearViewSwitch();
            }
        });
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
                .setPositiveButton(R.string.action_ok, (d, w) -> applyLanguageChoice(
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
                .setPositiveButton(R.string.action_ok,
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
        // 可能刚在设置里开关了「录制状态显示」
        applyRecordingStatsSetting();
        // 可能刚在设置里换了录像盘：状态条的余量按新盘重算（探测缓存已在设置里清掉）
        updateStatusLine();
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
            cameraManager.updatePreviewTextureViews(textureFront, textureBack, textureLeft, null);

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
            // 管线还在录的话，界面先画成它现在的样子；然后才问要不要自动开一次
            syncRecordingStateFromManager();
            checkAutoStartRecording();
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

                // 「启动自动录制」开着就要录一次；什么时候开由协调器定（环视出画面就开）
                checkAutoStartRecording();

            } catch (CameraAccessException e) {
                // 这里只会是列相机、读参数时相机服务出错，原因（英文原话和错误码）只进日志
                AppLog.e(TAG, "Failed to access camera", e);
                Toast.makeText(this, R.string.msg_camera_access_failed, Toast.LENGTH_SHORT).show();
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
            cell.fitCorrected = scaleModeOf(lane.fitCorrected);
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
     * 把主界面要的几个<b>画面</b>回调挂到 {@link #cameraManager} 上。
     *
     * <p>复用后台实例和新建实例两条路都要挂，而且挂的必须<b>一模一样</b> ——
     * 原来是各抄了一份，两份已经开始漂了（一份多个注释、一份带着尾随空格）。
     * 漂到功能上就是「同样的操作，冷启动和从后台回来表现不一致」，
     * 这种问题查起来最费劲，因为两条路看着都对。</p>
     *
     * <p>后台建的那份没有主界面的回调，
     * 不补上的话左右摄像头的旋转变换、录制计时都不正常。</p>
     *
     * <p>盘满、写不进、相机被拿走、一路都没起来 —— 这几个决定「停不停、接不接」的回调
     * 不在这里：它们由 {@link RecordingCoordinator#setCameraManager} 接，主界面在不在都一样。</p>
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

        // 设置首次数据写入回调
        // 用于在摄像头真正开始输出数据后启动计时器（分段计时等）
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
                if (isRecording) {
                    startRecordingTimer();
                    AppLog.d(TAG, "录制计时器已启动（首次写入后）");
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
                    R.string.msg_no_camera_available, Toast.LENGTH_LONG).show());
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
        // （有的写的是单位阵）。而车型这个键默认是 zeekr_7x，
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

        // 配置里没有这一格：按老规矩摆（上游给银河 E5 写的那条路）
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
            textureView.setTransform(new android.graphics.Matrix());   // 回到单位阵：这一路以前可能带着别的矩阵
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

            textureView.setTransform(matrix);
            AppLog.d(TAG, cameraKey + " 应用修正旋转: " + rotation + "度");
        });
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
        if (isRecording && cameraManager != null) {
            // 实际在用的管线（相机管理器建好时定的，回退后会变），括号里是设置值：两者可以不一样
            sb.append("  ").append(getString(R.string.debug_mode,
                    cameraManager.isCodecRecordingMode() ? "MediaCodec" : "MediaRecorder",
                    appConfig.getRecordingMode()));
        }

        // 内存使用
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long totalMB = rt.maxMemory() / (1024 * 1024);
        sb.append("\n").append(getString(R.string.debug_memory, usedMB, totalMB));

        // 车型
        sb.append("\n").append(getString(R.string.debug_model,
                appConfig.getCarModel(), configuredCameraCount));

        // 版本
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            sb.append("\n").append(getString(R.string.debug_version, versionName));
        } catch (Exception ignored) {}

        tvDebugOverlay.setText(sb.toString());
    }

    /**
     * 「启动自动录制」：这一趟还没自动开过、用户也没停过，就向协调器要一次。
     *
     * <p>开过没有、停过没有记在进程级的 {@link RecordingIntent} 上 ——
     * 以前记在界面的字段里，界面一重建（切日夜模式、换语言、关掉再打开）就忘了，
     * 于是用户明明按过停止，重建之后又自己录上了。什么时候真的开（环视出画面）、
     * 录不起来怎么办，都是协调器的事；以前这里的「等两秒再开」和 30 秒定时检查都归到它那一个等待里。</p>
     */
    private void checkAutoStartRecording() {
        RecordingIntent intent = RecordingIntent.current();
        if (!intent.shouldAutoStart(appConfig.isAutoStartRecording())) {
            AppLog.d(TAG, "不自动开始录制（" + intent.describe() + "，开关="
                    + appConfig.isAutoStartRecording() + "）");
            return;
        }
        intent.noteAutoStarted();
        AppLog.d(TAG, "启动自动录制开着，交给协调器：环视出画面就录");
        recordingCoordinator.request(RecordingCoordinator.Why.AUTO_START);
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
                }
            }
        };
        
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction("com.kooo.evcam.action.TOGGLE_RECORDING");
        registerReceiver(toggleRecordingReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        
        AppLog.d(TAG, "录制切换广播接收器已注册");
    }
    
    
    /**
     * 熄屏 15 秒后退到后台 —— 主界面在熄屏这件事上唯一自己管的一步。
     *
     * <p>唤醒锁、录像（10 秒停 / 接着录）、1.5 秒没人要就关相机、亮屏接回因熄屏退下去的界面，
     * 都在 {@link com.kooo.evcam.screen.ScreenState} 里按固定顺序做完了，这里不再有自己的一份。
     * 到点先问一遍屏幕是不是真的还黑着：深睡时这个计时是停住的，醒来之后才到点，
     * 而醒来时亮屏广播不来，只信旧标记就会把刚回到车上的人的界面退下去。
     * 录像还在（或还在等环视接回）、熄屏录制生效，就留在前台。</p>
     */
    private void scheduleBackgroundTask() {
        cancelBackgroundTask();

        screenOffBackgroundRunnable = () -> {
            screenOffBackgroundRunnable = null;
            if (!com.kooo.evcam.screen.ScreenState.refresh()) {
                AppLog.d(TAG, "屏幕已亮起，取消退后台");
                return;
            }

            // 正在录制、或在等环视接回：不退后台
            if (isRecording || recordingCoordinator.isWaiting()) {
                AppLog.d(TAG, "正在录制中（或在等环视接回），不退后台");
                return;
            }

            // 熄屏录制开着，不退后台
            if (appConfig.isScreenOffRecordingEnabled()) {
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

            // 留个记号：是我们自己因为熄屏退下去的。亮屏时 Recovery 据此把界面接回来 ——
            // 在这之前只退不回，人上车看到的是车机桌面，得自己再点一次图标
            appConfig.setUiLeftForScreenOff(true);

            // 退到后台
            moveTaskToBack(true);

            Toast.makeText(MainActivity.this, R.string.msg_screen_off_background, Toast.LENGTH_SHORT).show();
        };

        screenStateHandler.postDelayed(screenOffBackgroundRunnable, SCREEN_OFF_BACKGROUND_DELAY_MS);
    }

    private void cancelBackgroundTask() {
        if (screenOffBackgroundRunnable != null) {
            screenStateHandler.removeCallbacks(screenOffBackgroundRunnable);
            screenOffBackgroundRunnable = null;
        }
    }

    /**
     * 切换录制状态（开始/停止）
     */
    /**
     * 录到内置存储之前先提醒一次。
     *
     * <p>只挡<b>手动开始</b>：自动录制（开机、回前台）不弹，
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
            // 用户手动停止：这一趟里没有任何一条路可以再自动开起来（RecordingIntent），
            // 协调器收到 USER 也不会去等环视接回
            RecordingIntent.current().noteUserStopped();
            AppLog.d(TAG, "用户手动停止录制，这一趟不再自动开始");
            recordingCoordinator.stop(RecordingStops.Reason.USER);
        } else {
            // 用户手动开始：「停过」的记录作废，前面接回失败的额度也清掉
            RecordingIntent.current().noteUserStarted();
            recordingCoordinator.resetBudget();
            AppLog.d(TAG, "用户手动开始录制，自动恢复重新生效");
            confirmInternalStorageThen(() -> recordingCoordinator.request(RecordingCoordinator.Why.USER));
        }
    }

    /**
     * 录制结果的画面反馈。
     *
     * <p>所有决定都在 {@link RecordingCoordinator} 里：要不要录、能不能录、什么时候开、
     * 为什么停、停了接不接、还剩几次额度、黑匣子里的起止记录、前台服务、悬浮按钮的状态。
     * 这里只把结果画出来 —— 指示器、计时器、Toast。它们出错只是看着别扭，
     * 和「有没有录上」不是一回事，混在一起会让后者也没法单独验证。回调都在主线程。</p>
     */
    private final RecordingCoordinator.Listener recordingListener =
            new RecordingCoordinator.Listener() {
        @Override
        public void onRecordingStarted(java.util.Set<String> cameras, boolean sdFellBack) {
            lastRefusalShown = null;
            isRecording = true;
            isPreparingRecording = true;

            // 橙色旋转圈；首次写入回调里换成绿色闪烁。
            // 计时器也在那时才启动 —— 从「真的录上了」开始计，而不是从「尝试录」开始
            showPreparingIndicator();
            // 等相机放开的那句提示随接回撤掉
            showCameraTakenHint(false);

            if (sdFellBack && !AppConfig.isSdFallbackShownThisSession()) {
                AppConfig.setSdFallbackShownThisSession(true);
                Toast.makeText(MainActivity.this,
                        R.string.msg_sd_fallback, Toast.LENGTH_LONG).show();
            } else {
                int count = cameras.size();
                Toast.makeText(MainActivity.this,
                        count == configuredCameraCount
                                ? getString(R.string.msg_recording_started_all)
                                : getString(R.string.msg_recording_started_n, count),
                        Toast.LENGTH_SHORT).show();
            }

            // 悬浮按钮拉起来开录的：让用户看一眼「录上了」，再把主界面收回后台
            if (hideMainAfterStart) {
                hideMainAfterStart = false;
                preparingHandler.postDelayed(() -> {
                    if (isRecording && !isFinishing() && !isDestroyed()) {
                        AppLog.d(TAG, "录制已开始，将 Activity 移到后台");
                        moveTaskToBack(true);
                    }
                }, 1500);
            }
        }

        @Override
        public void onRecordingStopped(RecordingStops.Reason reason, long lastedMs, boolean willResume) {
            lastRefusalShown = null;
            isRecording = false;
            isPreparingRecording = false;
            setRecordState(com.kooo.evcam.ui.RecordButtonUi.State.IDLE);
            stopRecordingTimer();
            // 状态条上「录像改写到别的盘」那句随这次录像结束
            updateStatusLine();

            // 为什么停的、接不接，协调器已经定了；这里只按它说的提示
            String text;
            int length = Toast.LENGTH_LONG;
            switch (reason) {
                case USER:
                    text = getString(R.string.msg_recording_stopped);
                    length = Toast.LENGTH_SHORT;
                    break;
                case STORAGE_FULL:
                    text = getString(R.string.msg_storage_full_stopped);
                    break;
                case STORAGE_CANNOT_FREE:
                    text = getString(R.string.msg_storage_cannot_free);
                    break;
                case STORAGE_LOCKED:
                    text = getString(R.string.msg_storage_locked_stopped);
                    break;
                case SCREEN_OFF:
                    text = getString(R.string.msg_screen_off_stopped);
                    length = Toast.LENGTH_SHORT;
                    break;
                case NO_DATA:
                case START_FAILED:
                    // 一路都没起来、起来了一直没画面：录像没开始，不说「中断」（界面文字审查 rec_reason_unknown）
                    text = getString(willResume
                            ? R.string.msg_record_start_retrying : R.string.msg_record_start_gave_up);
                    break;
                default: {
                    String why = getString(reason == RecordingStops.Reason.WRITE_STALLED
                            ? R.string.rec_reason_write_stalled
                            : reason == RecordingStops.Reason.CAMERA_LOST
                            ? R.string.rec_reason_camera_lost : R.string.rec_reason_unknown);
                    text = getString(willResume
                            ? R.string.msg_recording_interrupted_resuming
                            : R.string.msg_recording_interrupted, why);
                    break;
                }
            }
            Toast.makeText(MainActivity.this, text, length).show();
            // 相机被别的程序拿走、正在等它放开：状态条上说一声
            showCameraTakenHint(reason == RecordingStops.Reason.CAMERA_LOST && willResume);

            // 熄屏期间停下来的（熄屏持续录制录不下去了，或者别的原因）：照熄屏的规矩退后台、
            // 放开相机，别开着相机睡过去；亮屏再接回。熄屏 10 秒停录那一条协调器在熄屏时已经安排过了
            if (reason != RecordingStops.Reason.SCREEN_OFF && com.kooo.evcam.screen.ScreenState.refresh()) {
                scheduleBackgroundTask();
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
        public void onWriteSlow() {
            // 录像照常在录，只是 U 盘写不过来、开始丢帧；多久提示一次协调器已经定了
            Toast.makeText(MainActivity.this, R.string.msg_storage_too_slow, Toast.LENGTH_LONG).show();
        }
    };

    /**
     * 把当前的相机管理器交给协调器。
     *
     * <p>每一处给 {@code cameraManager} 赋值之后都要调一次：它换了对象而协调器
     * 还握着旧的，表现会是「按了录制，什么都没发生」—— 这种不报错的失败最难查。
     * 协调器是进程级的那一份（onCreate 里已经取到、挂上了监听）；相机建好之前它收到的「要录」
     * 会一直等到这里把相机交过去、环视出画面为止。</p>
     */
    private void attachRecordingCoordinator() {
        if (recordingCoordinator == null) {
            recordingCoordinator = RecordingCoordinator.get(this);
        }
        recordingCoordinator.setCameraManager(cameraManager);
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
        // 必须在停服务之前：前台服务的 onDestroy 会发保活广播，保活接收器收到就去重启它
        UserExit.markExited(this);
        // 结束进程交给看门的线程：它等主线程收尾、等每一路相机关完，最多等 EXIT_DEADLINE_MS，
        // 到点照样结束。以前是主线程自己关相机、关完再结束 —— 相机服务一卡，进程就退不掉
        // （2026-09-26 09:40 那一次：点了退出，系统里却没有这次退出的记录）
        final long exitStartedAt = android.os.SystemClock.elapsedRealtime();
        final java.util.concurrent.CountDownLatch mainCleanupDone = new java.util.concurrent.CountDownLatch(1);
        new Thread(() -> finishExit(exitStartedAt, mainCleanupDone), "exit-watchdog").start();

        // 退出算一趟结束：下次打开是新的一趟，「启动自动录制」该重新生效
        com.kooo.evcam.recording.RecordingIntent.current().reset();
        // 主动退出之后不该再被亮屏拉回来
        appConfig.setUiLeftForScreenOff(false);

        // 停止录制（在录的停掉，在等环视的也不等了）
        recordingCoordinator.stop(RecordingStops.Reason.USER);

        // 停止前台服务（确保清理）
        CameraForegroundService.stop(this);


        // 退出就是退出：后视镜、录制按钮、补盲一起停。以前这里写着「不停，它们本来就是
        // 脱离主界面用的」—— 但紧接着 System.exit，它们反正活不下去，留着只是让系统
        // 以 START_STICKY 的名义再去重启它们
        OverlayCoordinator.onActivityDestroyed(this);
        com.kooo.evcam.zeekr.RearViewMirrorService.stop(this);
        
        // 释放持续唤醒锁
        WakeUpHelper.releasePersistentWakeLock();

        // 释放摄像头资源
        if (cameraManager != null) {
            cameraManager.release();
        }
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().release();
        
        // 保存日志（System.exit 会跳过 onDestroy，所以这里手动保存）
        AppLog.saveToPersistentLog(this);

        // 结束所有Activity；进程由看门的线程结束（见 finishExit）
        finishAffinity();
        mainCleanupDone.countDown();
    }

    /**
     * 退出最多等多久：主线程收尾、每一路相机按次序关完。到点没好也照样结束进程。
     * 相机服务一次关实测 2–20 秒（2026-10-08），三路按次序关要留够；进程带着没关完的相机死掉，
     * 相机服务里就剩一个没人收的句柄，下一个进程开相机全部超时。
     */
    private static final long EXIT_DEADLINE_MS = 20_000L;

    /**
     * 退出的最后一步，在看门的线程上跑：等主线程收尾、等相机关完，然后结束进程。
     *
     * <p>黑匣子里记一行结果：用了多久；到点没好的话，卡在哪 —— 是主线程收尾，还是哪一路相机。
     * 下次再「点了退出却退不掉」，这一行就是答案。</p>
     */
    private static void finishExit(long startedAt, java.util.concurrent.CountDownLatch mainCleanupDone) {
        boolean mainDone = false;
        try {
            mainDone = mainCleanupDone.await(EXIT_DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 相机是一路一路按次序关的（MultiCameraManager.closeAllCameras）：等它排完队，再等最后那几路关完
        java.util.List<String> stuck;
        while (true) {
            long left = EXIT_DEADLINE_MS - (android.os.SystemClock.elapsedRealtime() - startedAt);
            stuck = com.kooo.evcam.camera.SingleCamera.awaitAllClosed(Math.min(Math.max(left, 0L), 200L));
            if (left <= 0 || (!com.kooo.evcam.camera.MultiCameraManager.closingInOrder() && stuck.isEmpty())) {
                break;
            }
        }
        long took = android.os.SystemClock.elapsedRealtime() - startedAt;
        if (mainDone && stuck.isEmpty()) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("退出：收尾用了 " + took + "ms，相机都关好了");
        } else {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("退出：等满 " + EXIT_DEADLINE_MS
                    + "ms 强制结束。" + (mainDone ? "" : "主线程收尾没做完；")
                    + (stuck.isEmpty() ? "" : "相机 " + stuck + " 还没关好"));
        }
        // 黑匣子在自己的线程上写：等它把上面几行写完再结束进程
        com.kooo.evcam.blackbox.BlackBox.flush(1000);
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

    /**
     * 准备中：点已收成方块、一明一暗，外圈在转 —— 录制器还没写出第一笔数据。
     * 迟迟不来怎么办由录制器判（15 秒没写出就报「没收到画面」），这里不再另设看门狗。
     */
    private void showPreparingIndicator() {
        setRecordState(com.kooo.evcam.ui.RecordButtonUi.State.PREPARING);
    }

    /**
     * 接上一条还在进行的录制：状态以录制管线为准，而不是去「恢复」它。
     *
     * <p>主界面被重建或者重新打开时，录制管线一直在跑（见 onDestroy 的 keepPipeline）。
     * 新界面要做的只是把按钮、计时器画成管线现在的样子：写出过第一笔数据就是录制中，
     * 计时从那一刻接着走；还没写出就是准备中，看门狗照常盯着。
     * 在不在录问协调器（开录中也算）—— 以前问相机层，开录中重建的界面会画成没在录。</p>
     */
    private void syncRecordingStateFromManager() {
        if (cameraManager == null || !recordingCoordinator.isRecording()) {
            return;
        }
        isRecording = true;
        // 这一段从什么时候开始、停时录了多久，协调器记着（它不随界面重建），这里只画
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
     * 界面会一直停在准备中或录制中，要等用户去点才暴露。在不在录问协调器：开录指令一发出去就算
     * （不等录制器启动、不等第一笔数据），所以正常的准备中不会被误判 —— 以前问的是相机层，
     * 它要等会话建好才算，开录那几秒里回到前台会被误判成「已在后台停止」。</p>
     */
    private void reconcileRecordingState() {
        if (!isRecording || cameraManager == null) {
            return;
        }
        if (recordingCoordinator.isRecording()) {
            return;
        }
        AppLog.w(TAG, "回到前台：界面以为在录，录制器其实没在录 " + instanceTag()
                + " preparing=" + isPreparingRecording);
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
        setRecordState(isRecording
                ? com.kooo.evcam.ui.RecordButtonUi.State.RECORDING
                : com.kooo.evcam.ui.RecordButtonUi.State.IDLE);
    }

    /**
     * 拍照键：交给相机层的拍照入口（悬浮按钮走的也是它）。
     * 提示按真实结果说 —— 以前按下就弹「已保存」，不管拍没拍到。
     */
    private void takePicture() {
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().getOrInit(this)
                .takePhoto(new com.kooo.evcam.ui.PhotoFeedback(this));
    }



    @Override
    protected void onPause() {
        super.onPause();
        isInBackground = true;
        com.kooo.evcam.camera.StallWatch.setForeground(false);
        AppLog.d(TAG, "onPause called, isRecording=" + isRecording);

        // 预览不在前台了，注销这一项；剩下还有没有人要，问登记表。
        // 录像那一项由协调器登记（在录、在等环视都算），这里不替它填
        // 关不关由相机层按登记表判（没人要 1.5 秒后关），这里不再自己关
        com.kooo.evcam.camera.CameraNeeds.current().release(com.kooo.evcam.camera.CameraNeeds.Holder.PREVIEW);
    }

    @Override
    protected void onStart() {
        super.onStart();
        com.kooo.evcam.storage.StorageState.addListener(this, storageListener);
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
        com.kooo.evcam.storage.StorageState.removeListener(storageListener);
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
        
        AppLog.d(TAG, "onResume called, wasInBackground=" + wasInBackground + ", isRecording=" + isRecording);
        
        // 人已经在界面上了，屏幕一定亮着。ScreenState 记的还是黑着就说明亮屏广播没来，让它补跑一次亮屏
        com.kooo.evcam.screen.ScreenState.refresh();

        // 人已经在界面上了，「因熄屏退下去」这个记号就作废 —— 不管是自己接回来的，
        // 还是用户自己点回来的
        appConfig.setUiLeftForScreenOff(false);

        // 预览又要用相机了
        com.kooo.evcam.camera.CameraNeeds.current().claim(com.kooo.evcam.camera.CameraNeeds.Holder.PREVIEW);

        // 界面记的录制状态和录制器的真实状态先对一下；对不上就以录制器为准
        reconcileRecordingState();

        // U 盘可能在后台时插拔过：先按上一份快照画，再去后台探测一次（结果经 storageListener 回来）
        refreshRecordAvailability();
        updateStatusLine();
        com.kooo.evcam.storage.StorageState.refresh(this, "resume");
        
        // 回到前台要用相机：上面 claim(PREVIEW) 那一下相机层自己会开（reconcileCameras）
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
        // 还有别人在用相机（超级后视镜、悬浮窗、补盲）也要留着 —— 以前只看录制，
        // 主界面一被销毁就把整个相机管理器拆掉，后视镜当场黑掉，两秒后它的看门狗又去重开：
        // 环视就这样被关了又开。登记表的规矩是「都不用了才释放」，这里是最后绕过它的一处
        com.kooo.evcam.camera.CameraNeeds needs = com.kooo.evcam.camera.CameraNeeds.current();
        final boolean othersNeedCamera = needs.heldByAnyoneExcept(
                com.kooo.evcam.camera.CameraNeeds.Holder.PREVIEW);
        // 协调器还在等环视开录的（开机自启动、悬浮按钮拉起）也算有人要：管线拆了它就永远开不起来。
        // 在录（含开录中）、上一次停录还在收拾的，同样留着（needsPipeline，问的是协调器那一份「在不在录」）
        final boolean keepPipeline = cameraManager != null && !cameraManager.isReleased()
                && (recordingCoordinator.needsPipeline() || isChangingConfigurations() || othersNeedCamera);
        if (othersNeedCamera && cameraManager != null && !cameraManager.isReleased()) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("主界面销毁，但相机还有人要（"
                    + needs.describe() + "），不释放");
        }
        AppLog.i(TAG, "onDestroy 录制管线" + (keepPipeline ? "保留" : "释放") + " "
                + instanceTag() + " recording=" + recordingCoordinator.isRecording()
                + " changingConfigurations=" + isChangingConfigurations());
        if (!keepPipeline) {
            // 不留就清掉 Holder：release() 会清空 cameras map，进程若因 Service 存活而不退出，
            // Holder 会握着一个已清空的实例被下次复用
            com.kooo.evcam.camera.CameraManagerHolder.getInstance().setCameraManager(null);
        }

        // 关闭预览矫正悬浮窗

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

        // 协调器是进程级的，录像和它的等待都不随这个界面走；只把画面反馈摘掉
        recordingCoordinator.removeListener(recordingListener);

        // 清理录制切换广播接收器
        if (toggleRecordingReceiver != null) {
            try {
                unregisterReceiver(toggleRecordingReceiver);
            } catch (Exception e) {
                AppLog.w(TAG, "注销录制切换广播接收器时出错: " + e.getMessage());
            }
            toggleRecordingReceiver = null;
        }
        // 熄屏退后台的那一步：界面都没了，不用退
        com.kooo.evcam.screen.ScreenState.removeListener(screenListener);
        cancelBackgroundTask();

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

        // 准备中超时后按「没收到画面」停掉录制器、开录一路都没起来时停录收拾，清掉的正是那个空分段 ——
        // 这是在收拾，不是新的异常，提示里已经说了「录制未能启动」。
        // 这个回调是发到主线程上来的，到这里时协调器已经把停录原因记下了
        if (recordingCoordinator.lastStopReason() == RecordingStops.Reason.NO_DATA
                || recordingCoordinator.lastStopReason() == RecordingStops.Reason.START_FAILED) {
            AppLog.d(TAG, "准备中超时、开录失败的清理，不弹录制异常");
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
        // 窗口真打开了才说「已打开」：没权限、摄像头没就绪各有自己的提示（上面两处）；
        // show() 加不上窗口时只记日志不抛，所以问一下它
        if (imageAdjustFloatingWindow.isShowing()) {
            Toast.makeText(this, R.string.msg_adjust_opened, Toast.LENGTH_SHORT).show();
        }

        AppLog.d(TAG, "Image adjust floating window shown");
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
        if (text == null) {
            return;
        }
        compositeInfoText = text.trim().replace('\n', ' ');
        runOnUiThread(this::renderCompositeInfoCell);
    }

    /** 状态条最右那一格：环视被车机占用时写这件事，否则是合成流的识别结果。 */
    private void renderCompositeInfoCell() {
        if (tvCompositeInfo == null) {
            return;
        }
        String line = cameraTakenHint ? getString(R.string.status_camera_taken) : compositeInfoText;
        tvCompositeInfo.setText(line);
        tvCompositeInfo.setVisibility(line.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    }

    /** 环视被别的程序拿走、正在等它放开：状态条上说一声，接回后撤掉。 */
    private void showCameraTakenHint(boolean on) {
        if (cameraTakenHint == on) {
            return;
        }
        cameraTakenHint = on;
        renderCompositeInfoCell();
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

}
