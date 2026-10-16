package com.kooo.evcam;


import com.kooo.evcam.AppLog;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    /** 放大中管线建好了：收回之后照它摆座舱那两格（{@link #showBuiltCabins}）；null = 没有要补摆的。 */
    private MultiCameraManager cabinPanesAfterCollapse;
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
    // 唤醒锁、录像照熄屏那张表停不停 / 亮屏接回、「车机要睡」、亮屏把退下去的界面接回来，
    // 都在它那里按固定顺序做完；相机由通道调度照登记表关（熄屏时预览不算有人要）。主界面只剩界面自己的事 —— 熄屏 15 秒退后台
    private final android.os.Handler screenStateHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());  // 熄屏退后台的延迟处理
    private Runnable screenOffBackgroundRunnable;  // 息屏退后台的延迟任务
    private static final long SCREEN_OFF_BACKGROUND_DELAY_MS = 15000;  // 息屏后等待15秒（退后台）
    /** 熄屏 15 秒那一步到点时屏幕黑没黑还不知道（安卓刚亮、车辆信号还没读回来）：过这么久再看（设计 v3.1 C7）。 */
    private static final long SCREEN_UNKNOWN_RECHECK_MS = 2000;
    /** 这一次熄屏已经退过后台了（亮屏、回到前台时清）：熄屏时停录之后会再排一次 15 秒，只退一次、只提示一次。 */
    private boolean leftForScreenOff;
    /**
     * 上一次 onPause 是熄屏带来的（那一刻系统已经不交互了；onResume 清）。熄屏时系统常常先把在前的界面暂停、熄屏广播后到
     * （见 onCreate 里登记 ScreenState 那一处），那时 {@link #isInBackground} 已经是真 —— 只看它的话，熄屏那一刻明明在前的主界面
     * 记不上「因熄屏退下去」，亮屏接不回来（审查 2026-10-11）。
     */
    private boolean pausedByScreenOff;

    /**
     * 熄屏 / 亮屏（{@link com.kooo.evcam.screen.ScreenState} 在主线程调；onCreate 登记、onDestroy 摘掉）。
     * 只管界面自己的事（大纲 §2 阶段 6）：熄屏那一刻主界面在前，就留下「因熄屏退下去」的记号；熄屏 15 秒退后台；亮屏取消它。
     * 熄屏 v3 起「熄屏」是屏幕真黑了（车主下车那一下，车辆信号判的；车机要睡、车机黑着醒了都还是熄屏，不叫到这里）：
     * 记号在屏幕一黑就记上，开车门亮屏时主界面就接回来（以前要等几分钟后车机下电那一次安卓熄屏）。
     *
     * <p>熄屏那张表（车机会不会睡、停不停录）不在这里算，预览也不在这里放：熄屏时预览按规则就不算有人要
     * （{@code ChannelRules}），车机要睡由录像协调器判、写给通道调度，相机由调度照登记表按次序关。以前这里自己再算一遍那张表，
     * 判成现在停就当场退后台、放开预览。记号在熄屏那一刻就落盘：车机几秒后就可能结束进程，等到 15 秒才记就记不上了 ——
     * 记上了，下次亮屏 Recovery 照样把主界面接回来。接回录像、接回界面不在这里 —— ScreenState 在叫到这里之前已经做完了。</p>
     */
    private final com.kooo.evcam.screen.ScreenState.Listener screenListener =
            new com.kooo.evcam.screen.ScreenState.Listener() {
                @Override
                public void onScreenOff() {
                    if (!isInBackground || pausedByScreenOff) {
                        // 主界面在前（或者正是熄屏把它暂停的）：是我们自己因为熄屏退下去的，亮屏时 Recovery 据此把界面接回来。
                        // 在后台时熄屏不记（人自己切走的，亮屏不抢到最前面）
                        appConfig.setUiLeftForScreenOff(true);
                    }
                    AppLog.d(TAG, "检测到息屏，15 秒后退后台（录像中、在等开录、熄屏录制生效则留在前台）");
                    scheduleBackgroundTask();
                }

                @Override
                public void onScreenOn() {
                    AppLog.d(TAG, "检测到亮屏，取消退后台任务");
                    leftForScreenOff = false;
                    // 那一次熄屏带来的暂停到此为止：没被接回前台的话，下一次熄屏时它是人切走之后的后台，不再算在前
                    pausedByScreenOff = false;
                    cancelBackgroundTask();
                }
            };


    // 车型配置相关
    private AppConfig appConfig;
    /** 启用的那几路（RecordSpecs.enabledCameraKeys）：主界面给它们摆格子、挂画布，一路一块（PreviewSlots）。 */
    private java.util.Set<String> enabledLanes = new java.util.LinkedHashSet<>();
    // 录制状态显示相关
    private TextView tvRecordingStats;
    private android.os.Handler recordingTimerHandler;

    private Runnable recordingTimerRunnable;
    /** 状态条上现在显示的「换过盘」状态。换盘发生在相机层，界面靠计时器每秒对一下。 */
    private String[] shownFallback;
    private long recordingStartTime = 0;  // 录制开始时间
    private int currentSegmentCount = 1;  // 当前分段数
    private long segmentStartTime = 0;  // 本段开始时间（分段进度环用）
    /** 上一次画录制键时录像的样子（renderRecordState）：等待摄像头之后又有一路在录时，本段从接回那一刻重新算。 */
    private RecordingCoordinator.View renderedRecordView;
    private long segmentLengthMs = com.kooo.evcam.profile.RecordSpecs.segmentMs(com.kooo.evcam.profile.RecordSpecs.DEFAULT_SEGMENT_MINUTES);
    private boolean isRecordingStatsEnabled = true;  // 录制状态显示开关
    private long lastStatsClickTime = 0;  // 上次点击录制状态显示的时间
    private static final long DOUBLE_CLICK_INTERVAL = 500;  // 双击判定间隔（毫秒）

    // 导航相关
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private View recordingLayout;  // 录制界面布局
    private View fragmentContainer;  // Fragment容器


    /**
     * 悬浮按钮拉起来开录时，录起来之后隔一会儿把主界面收回后台（{@link #hideMainAfterStart}）。
     * 录制键是什么样子（开录中、在录、等待摄像头）不在这里计时：照协调器说的画（{@link #renderRecordState}）。
     */
    private final android.os.Handler preparingHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** 状态条最右那一格的正文（合成流识别结果）；摄像头被占用时那一格临时改写成提示（{@link #cameraHint}）。 */
    private String compositeInfoText = "";
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
            RecordingIntent choices = RecordingIntent.current();
            if (choices.recordingWanted()) {
                // 人自己点开的是新的一趟：之前要录的（被结束的进程留下的、等亮屏接的）不再接；启动自动录制开着的照常自动开
                com.kooo.evcam.blackbox.BlackBox.noteImportant("人点开主界面，新的一趟：撤掉「这一趟要录」");
            }
            choices.reset();
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
        // 它等相机管线交过来、查过盘就开，不等环视（见 attachRecordingCoordinator）
        recordingCoordinator = RecordingCoordinator.get(this);
        recordingCoordinator.addListener(recordingListener);

        // 重置U盘回退提示标志（每次冷启动重置）
        AppConfig.resetSdFallbackFlag();
        
        // 照配置里启用的几路摆布局
        setupLayoutByCarModel();

        // 设置状态栏沉浸式
        setupStatusBar();

        initViews();
        setupNavigationDrawer();

        // 界面重建（切日夜模式、换语言）时录制管线一直在跑（管线属于应用，主界面销毁不放它，见 onDestroy），
        // 新界面只是把它现在的样子画出来（syncRecordingStateFromManager），没有「恢复录制」这回事；
        // 预览画布也是管线留着的那几块，initViews 里接回（reattachKeptCanvases，大纲 §7.6）

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
            // 退到后台照常录；开始录像由协调器查过盘就开（checkAutoStartRecording 只是提出要录）
            AppLog.d(TAG, "开机自启动模式：等待窗口准备好后移到后台");
            shouldMoveToBackgroundOnReady = true;
        }

        // 从录制悬浮按钮拉起来的：要录。相机还没建，协调器先记着，管线交过去、查过盘就开
        if (getIntent().getBooleanExtra("auto_start_recording", false)) {
            requestRecordingFromFloatingButton(getIntent());
        }

        // 按设置把该开的悬浮窗恢复出来（画面悬浮窗 / 超级镜子 /
        // 录制悬浮按钮 / 补盲）。该不该开、能不能开都在协调器里判断。
        OverlayCoordinator.restoreOnLaunch(this);
        
        // 悬浮按钮发来的录制切换 / 拍照广播
        // 屏幕状态只听 ScreenState；整个界面生命周期都听着（熄屏时界面可能先被暂停、广播后到）
        com.kooo.evcam.screen.ScreenState.addListener(screenListener);
        // 管线被 Holder 放掉重建时当场接上新的那一份（见 pipelineReplaced），onDestroy 摘掉
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().addReplacedListener(pipelineReplaced);
        initToggleRecordingReceiver();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        AppLog.d(TAG, "onNewIntent called");
        openDrawerIfAsked(intent);

        // 还活着的主界面被静默拉到前面（Recovery：要接录像、而那时用不了相机）：接上之后照样退回后台（规格 1.6）
        if (intent.getBooleanExtra("auto_start_from_boot", false)) {
            intent.removeExtra("auto_start_from_boot");
            shouldMoveToBackgroundOnReady = true;
        }

// 处理从录制悬浮按钮启动（需要自动开始录制）
        if (intent.getBooleanExtra("auto_start_recording", false)) {
            requestRecordingFromFloatingButton(intent);
        }
    }

    /**
     * 悬浮按钮在主界面不在前台时按了「开始录制」，把主界面拉了起来。
     *
     * <p>人按的：前面的失败都不算了。什么时候开由协调器定（查过盘就开，不等环视）；
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
     * 照配置里启用的几路选布局（{@link PreviewSlots}）：只启用了环视是单路四宫格，其余是多路布局。
     *
     * <p>以前按车型选，后来数路数。车型（视频流配置的选择）决定用哪一份配置，布局只看那一份配置启用了哪几路；
     * 银河 E5 / L6 / L7 / 星舰 / 手机那些车型和布局早就删了。</p>
     */
    private void setupLayoutByCarModel() {
        int layoutId;

        // 摆什么布局、挂哪几块画布，跟着<b>配置里启用了哪几路</b>走（和建相机管线、录像同一个函数），不看车型那个字符串，
        // 也不数路数。
        //
        // 以前先看车型，后来数路数：只开一路座舱时数出来是 1，摆成环视的四宫格，那一路座舱没地方显示、也没建；
        // 只开两路座舱时数出来是 2，按槽位号挂的是环视和前座舱（2026-10-16）。
        enabledLanes = com.kooo.evcam.profile.RecordSpecs.enabledCameraKeys(new ProfileStore(this).current());
        if (PreviewSlots.singleLayout(enabledLanes)) {
            // 只启用了环视：一路合成流，由 FourLaneContainer 重画成四宫格
            layoutId = R.layout.activity_main_zeekr_7x;
            AppLog.d(TAG, "单路布局：环视合成流 + 四宫格拆分");
        } else {
            // 启用了座舱（或者一路都没启用）：多路布局，没启用的那几格藏起来（showLanePanes）
            layoutId = R.layout.activity_main_zeekr_7x_multi;
            AppLog.d(TAG, "多路布局：启用了 " + enabledLanes);
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
        textureBack = findViewById(R.id.texture_back);  // 只有环视的单路布局里为 null
        textureLeft = findViewById(R.id.texture_left);  // 同上

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

        // 启用的那几路各挂一块画布（PreviewSlots）；没启用的不挂，它那一格在多路布局里藏起来
        for (String key : PreviewSlots.KEYS) {
            AutoFitTextureView canvas = canvasFor(key);
            if (canvas != null) {
                canvas.setSurfaceTextureListener(buildSurfaceListener(key));
            }
        }
        showLanePanes(enabledLanes);
        // 主界面重建：上一个界面留下的预览画布接回来（大纲 §7.6），要在画布挂上窗口、系统自己建画布之前
        reattachKeptCanvases();
        // 画布已经齐了（留着的全部接回了，或者一路都没启用、没有画布可等）：不会再有系统建画布的回调来叫，排一次接管线 ——
        // 接上时（getForMainScreen）启用的几路和管线对不上就重建，录制状态也照管线画（onPipelineBuilt）。
        // 视图挂上窗口时跑（View.post 在挂上之前排进视图自己的队列）：onCreate、onResume 都走完了
        if (PreviewSlots.canStartCamera(textureReadyCount, enabledLanes)) {
            getWindow().getDecorView().post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    startPreviewIfReady();
                }
            });
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

    /**
     * 「预览要相机」只在画布都在、主界面接上了管线（{@link #initCamera} 里 getForMainScreen，启用的几路变了先重建）的时候登记：
     * 登记了相机就开，开出来的第一份会话要带着预览。
     *
     * <p>登记了就是主界面起来了（在前、能用相机）：「这一趟要录」还在、又没在录没在等的，现在接
     * （{@link RecordingCoordinator#resumeIfWanted}）。要录像、而那时用不了相机时，协调器打开录像意图那一下把主界面拉起来、录像当场就开了
     * （大纲 §2 阶段 6），这里什么都不做；这一下兜的是别的路起来的主界面。人自己点开的主界面是新的一趟，
     * 「这一趟要录」在 onCreate 里撤了，这里也什么都不做。</p>
     */
    private void claimPreviewIfReady() {
        // 接上了管线才登记：接上时（getForMainScreen）启用的几路变了会先重建，登记在旧的那一份上，它会把配置里已经关掉的路开一遍
        if (cameraManager != null && PreviewSlots.canStartCamera(textureReadyCount, enabledLanes)) {
            com.kooo.evcam.camera.CameraNeeds.current().claim(com.kooo.evcam.camera.CameraNeeds.Holder.PREVIEW);
            if (!isInBackground) {
                recordingCoordinator.resumeIfWanted("main-screen");
            }
        }
    }

    /**
     * 一块画布就绪了（系统建的，或者接回的上一个界面留下的那一块）：数到齐了就把相机这一侧接上、声明各路预览、登记 PREVIEW。
     */
    private void onPreviewCanvasReady(String cameraKey) {
        textureReadyCount++;
        AppLog.d(TAG, "TextureView " + cameraKey + " ready: " + textureReadyCount
                + "/" + enabledLanes.size());
        startPreviewIfReady();
    }

    /**
     * 画布都在了：头一次接上管线（{@link #initCamera}，它声明各路预览），之后每次把各路画布重新声明一遍 ——
     * 同一块画布再声明一次什么都不做（2.11 阶段 4a：只声明、叫醒通道调度；开不开、带不带由调度定，
     * 以前这里是 setTextureView + 重建会话）。然后登记「预览要相机」。
     */
    private void startPreviewIfReady() {
        if (!PreviewSlots.canStartCamera(textureReadyCount, enabledLanes) || !checkPermissions()) {
            return;
        }
        if (cameraManager == null) {
            initCamera();
        } else {
            cameraManager.declarePreviews(canvasFor("front"), canvasFor("back"), canvasFor("left"));
        }
        // 画布都在了，这时才登记「预览要相机」：相机一开，第一份会话就带着预览。
        // 以前在 onResume 登记，而画布要到第一次绘制之后才有 —— 开出来的会话没有预览，
        // 画布一到又重配一遍，三路各多一次进相机服务（2026-10-08，环视那一次重配卡了 12 秒）
        claimPreviewIfReady();
    }

    /** 这一路的画布；没启用（没挂监听、那一格藏着）、或者这套布局里没有这一格是 null。 */
    private AutoFitTextureView canvasFor(String cameraKey) {
        if (!enabledLanes.contains(cameraKey)) {
            return null;
        }
        switch (cameraKey) {
            case "front":
                return textureFront;
            case "back":
                return textureBack;
            case "left":
                return textureLeft;
            default:
                return null;
        }
    }

    /**
     * 主界面重建（换日夜模式、换语言、从最近任务划掉后再打开）：上一个界面留下的预览画布接回来
     * （大纲 §7.6，项目所有者 2026-10-11「按建议」；留是 {@link MultiCameraManager#keepPreviewCanvas}）。
     *
     * <p>在 onCreate 里、画布挂上窗口之前做：{@code TextureView.setSurfaceTexture} 之后视图不再自己建画布，
     * 也就不会来 onSurfaceTextureAvailable —— 所以这里当场把预览声明回去（同一块画布就是同一个输出，
     * 通道零变动、录像不断），画布照「就绪」数上；全部接回了就由 initViews 排的那一次接上管线、登记 PREVIEW：重建这一趟里调度醒来时，
     * 登记表和声明都和重建之前一样。相机这一侧的其余部分（回调、亮度调节、录制状态）等 onCreate 走完再接，
     * 和系统建的画布一样晚。没留下的那几格照旧等系统建。</p>
     */
    private void reattachKeptCanvases() {
        // 留着画布的只会是进程里那一份管线（管线属于应用，主界面销毁不放它）。这里不建：没有就没有画布可接。
        // 它要是随后因为换车型、配置里开关了一路被 Holder 放掉重建，接回的画布归视图，新的那一份照样声明它
        MultiCameraManager pipeline = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
        if (pipeline == null || pipeline.isReleased()) {
            return;
        }
        List<String> back = new ArrayList<>();
        for (String key : PreviewSlots.KEYS) {
            AutoFitTextureView view = canvasFor(key);
            if (view == null || view.isAvailable()) {
                continue;
            }
            android.graphics.SurfaceTexture kept = pipeline.takeKeptPreview(key);
            if (kept == null) {
                continue;
            }
            try {
                view.setSurfaceTexture(kept);
            } catch (RuntimeException e) {
                // 接不上（还挂在别的 GL 上下文）：还给管线，照旧等它离开会话再放 —— 当场放的话，会话里那个成了死输出，
                // 那一路整条重复请求停到调度摘掉它（审查 2026-10-11，和后视镜接不上时一样）；还不回去（管线里已经没有这一路）
                // 才放掉，这块画布不在它的会话里。这一格照旧等系统建
                AppLog.w(TAG, "接回留着的预览画布失败 " + key + ": " + e);
                if (!MultiCameraManager.keepPreviewCanvas(pipeline, key, kept)) {
                    kept.release();
                }
                continue;
            }
            pipeline.declarePreview(key, view);
            textureReadyCount++;
            back.add(MultiCameraManager.roleName(key));
        }
        if (back.isEmpty()) {
            return;
        }
        com.kooo.evcam.blackbox.BlackBox.note("主界面重建：接回留着的预览画布 " + String.join("、", back)
                + "（同一块画布，通道不动）");
    }

    private TextureView.SurfaceTextureListener buildSurfaceListener(String cameraKey) {
        return new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                onPreviewCanvasReady(cameraKey);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                AppLog.d(TAG, "TextureView " + cameraKey + " size changed: " + width + "x" + height);
            }

            /**
             * 画布要被收走了（主界面销毁，视图从窗口上摘下来；最小化不走这里 —— 安卓 12 的 TextureView 那时只拆显示层）。
             * 把画布交给管线（大纲 §7.6）：返回 false，TextureView 不放；新界面接回同一块，没人接回的
             * 由通道调度等它离开会话再放。退出、换车型时管线已经放了、它的调度还在按次序关：会话里还带着这块的照样交给那一份的调度，
             * 随相机关了再放（2.11.4 R1：会话里的画布只由通道调度放，MultiCameraManager.ownerOf）；哪一份都不带着它才照常让 TextureView 放。
             * 这一下在 onDestroy 之后才来（ActivityThread 先 performDestroy、再从窗口上摘视图）。
             */
            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface) {
                textureReadyCount--;
                MultiCameraManager pipeline = cameraManager != null ? cameraManager
                        : com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
                boolean kept = MultiCameraManager.keepPreviewCanvas(pipeline, cameraKey, surface);
                AppLog.d(TAG, "TextureView " + cameraKey + " destroyed, remaining: " + textureReadyCount
                        + (kept ? ", canvas kept by the pipeline" : ""));
                return !kept;
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
        if (labelFront != null && enabledLanes.contains("front")) {
            updateCameraLabel(labelFront, appConfig.getCameraName(this, "front"));
        }
        if (labelBack != null && enabledLanes.contains("back")) {
            updateCameraLabel(labelBack, appConfig.getCameraName(this, "back"));
        }
        if (labelLeft != null && enabledLanes.contains("left")) {
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
                com.kooo.evcam.profile.RecordSpecs.forCameraKey(this, com.kooo.evcam.profile.RecordSpecs.shownCameraKey(this)).segmentMinutes);
        // 分段按时长切：恢复时第 N 段的起点就是开始时间往后数 N-1 段
        segmentStartTime = recordingStartTime + (currentSegmentCount - 1) * segmentLengthMs;
        updateStatusLine();

        if (tvRecordingStats != null) {
            // 显不显示照录像的样子（renderRecordState：在录时显示，等待摄像头时收起）；开关只管 alpha
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
        // 角标随之收起：recordingStartTime 归零后 renderRecordState 不再显示它
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
     * 按钮不会显示能录、按下去却被拒。录像意图开着时不动它（开录中、在录、等待摄像头）：盘拔了由录制链路自己处理。</p>
     */
    private void refreshRecordAvailability() {
        if (recordButtonUi == null || recordingCoordinator.view() != RecordingCoordinator.View.IDLE) {
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
                com.kooo.evcam.profile.RecordSpecs.forCameraKey(this, com.kooo.evcam.profile.RecordSpecs.shownCameraKey(this)).segmentMinutes);
        renderRecordState();
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
                if (PreviewSlots.canStartCamera(textureReadyCount, enabledLanes)) {
                    initCamera();
                }
            } else {
                Toast.makeText(this, R.string.msg_need_camera_storage, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    /**
     * 接上相机管线（画布都在时）。管线属于应用，只在 {@link com.kooo.evcam.camera.CameraManagerHolder} 里建（大纲 §2 阶段 6，
     * channel-logic §五）：这里拿进程里那一份（没有就让它去建 —— 相机列表、合成流探测在相机线程上做，主线程不进相机服务），
     * 挂上主界面的回调，建好了再声明画布、画状态条（{@link #onPipelineBuilt}）。
     *
     * <p>以前这里自己按配置建一份映射（列相机、探测合成流都在主线程上），后台（悬浮窗、录制服务）在 Holder 里按车型再建一份，
     * 两份规则对不上；路数对不上时还会把后台那份放掉重建。现在只有一份，放不放、重不重建由 Holder 定：换车型、上一次列相机出错，
     * 以及主界面接上时（这里，{@code getForMainScreen}）配置里开的几路和它对不上 —— 布局照的就是此刻的配置。
     * Holder 放掉重建了会告诉这里（{@link #pipelineReplaced}），当场接上新的那一份。</p>
     */
    private void initCamera() {
        // 确保所有需要的TextureView都准备好
        if (!PreviewSlots.canStartCamera(textureReadyCount, enabledLanes)) {
            AppLog.w(TAG, "画面还没全部就绪: " + textureReadyCount + "/" + enabledLanes.size());
            return;
        }

        // 防止重复初始化：如果 cameraManager 已经存在，直接返回
        if (cameraManager != null) {
            AppLog.d(TAG, "Camera already initialized, skipping");
            return;
        }

        com.kooo.evcam.camera.CameraManagerHolder holder = com.kooo.evcam.camera.CameraManagerHolder.getInstance();
        final MultiCameraManager pipeline = holder.getForMainScreen(this);
        cameraManager = pipeline;
        attachRecordingCoordinator();
        // 管线比界面活得久：回调在 onDestroy 一律摘掉（detachUiCallbacks），这里每个界面重新挂一遍
        wireCameraCallbacks();
        // 初始化亮度/降噪调节管理器
        imageAdjustManager = new ImageAdjustManager(this);
        holder.whenBuilt(pipeline, built -> onPipelineBuilt(pipeline, built));
    }

    /**
     * Holder 放掉重建了（换车型、上一次列相机出错）：主界面在前就当场接上新的那一份；在后台的等回到前台（onResume）再接 ——
     * 接的时候会登记预览（{@link #claimPreviewIfReady}），后台的主界面不该要相机。onCreate 挂上、onDestroy 摘掉
     * （CameraManagerHolder.addReplacedListener）。以前只在 onResume 里看手里那份放没放：主界面在前时管线被别人换掉
     * （录像在等开录时每 2 秒要一次管线，碰上上一次列相机出错就重建；拍照碰上换了车型），它握着死的那一份，预览黑着、
     * 回调挂在死的那份上，直到离开再回来（审查 2026-10-11）。
     */
    private final Runnable pipelineReplaced = () -> {
        if (!isInBackground && !isFinishing() && !isDestroyed()) {
            reattachPipelineIfReplaced("replaced");
        }
    };

    /**
     * 手里那份管线不再是进程里在用的那一份（被 Holder 放掉重建了，或者建的时候列相机出错 —— 隔够了 Holder 会重建）：
     * 摘掉旧的回调，接上现在那一份（{@link #startPreviewIfReady} → {@link #initCamera}）。
     *
     * @param when 什么时候发现的（ASCII，进日志）
     */
    private void reattachPipelineIfReplaced(String when) {
        if (cameraManager == null || com.kooo.evcam.camera.CameraManagerHolder.getInstance().isCurrent(cameraManager)) {
            return;
        }
        AppLog.i(TAG, "相机管线换过了（" + when + "）：摘掉旧的回调，接上进程里现在那一份 " + instanceTag());
        cameraManager.detachUiCallbacks();
        cameraManager = null;
        startPreviewIfReady();
    }

    /**
     * 管线建好了（主线程；已经建好的当场来）：画出它是照什么建的（四宫格几何、状态条那一格、座舱格子、出错的提示），
     * 声明各路画布，然后把录制状态画成它现在的样子、问要不要自动开一次。
     *
     * <p>相机由通道调度开：画布都在时登记了 PREVIEW（{@link #startPreviewIfReady}），它照登记表按次序开、开的时候就带着声明了的预览。
     * 接回的留着的那几块已经声明过，再声明一次什么都不做。</p>
     */
    private void onPipelineBuilt(MultiCameraManager pipeline, com.kooo.evcam.camera.CameraManagerHolder.Built built) {
        if (pipeline != cameraManager || isFinishing() || isDestroyed()) {
            return;
        }
        showBuilt(built);
        showBuiltCabins(pipeline);
        pipeline.declarePreviews(canvasFor("front"), canvasFor("back"), canvasFor("left"));
        // 各路的画面尺寸可能在挂上回调之前就算出来了（不开设备先算的、上一个界面时开过的）：补一次
        pipeline.firePreviewSizeCallbacks();
        registerCamerasToImageAdjustManager();
        AppLog.d(TAG, "Camera initialized with " + pipeline.getCameraCount() + " cameras (canvases "
                + enabledLanes + ")");
        // 管线还在录的话，界面先画成它现在的样子；然后才问要不要自动开一次（什么时候开由协调器定：查过盘就开，不等环视）
        syncRecordingStateFromManager();
        checkAutoStartRecording();
    }

    /**
     * 管线是照什么建的，画到界面上：合成流的几何交给四宫格容器，状态条最右那一格写槽位（多路）或合成流尺寸（单路）；
     * 列相机出错、一路都没分到、没找到合成流时提示一句（和以前主界面自己建时的提示一样）。
     */
    private void showBuilt(com.kooo.evcam.camera.CameraManagerHolder.Built built) {
        if (built.failed || built.plan == null) {
            // 列相机时相机服务出错：原因（英文原话和错误码）只进日志，下次有人要管线时 Holder 重建
            Toast.makeText(this, R.string.msg_camera_access_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        com.kooo.evcam.zeekr.ZeekrMultiPlan plan = built.plan;
        // 合成流的几何先按探测（或存下）的尺寸给上；按配置钉了别的尺寸就照钉的。最终协商到的尺寸由 previewSizeCallback 再刷一次
        if (plan.compositeIsReal && built.compositeSize != null && compositeContainer != null) {
            compositeContainer.setSourceSize(built.compositeUse != null ? built.compositeUse : built.compositeSize);
        }
        if (!PreviewSlots.singleLayout(enabledLanes)) {
            if (plan.assignedCount() == 0) {
                AppLog.w(TAG, "没有任何可用相机，三路模式无法显示");
                Toast.makeText(this, R.string.msg_no_camera_available, Toast.LENGTH_LONG).show();
            }
            updateCompositeInfoOverlay(describeMultiSlots(plan));
            return;
        }
        if (!plan.compositeIsReal) {
            // 没找到合成流：管线拿第一个相机顶上，界面上显示原始画面并提示不支持
            Toast.makeText(this, R.string.zeekr_composite_not_found, Toast.LENGTH_LONG).show();
        }
        // 角标先写探测结果；相机真正开起来之后会用实际尺寸再刷一次（见 refreshCompositeSizeOverlay）——
        // 强制换了尺寸而 HAL 不认时，这里写探测值就成了「界面一个数、实际另一个数」
        compositeProbeInfo = built.diagnostics;
        compositeProbeSize = built.compositeSize;
        updateCompositeInfoOverlay(compositeSummary(plan.compositeId, built.compositeSize));
    }

    /**
     * 这一界面的配置（布局、各格摆法、哪一路的摆位由配置说了算）。
     *
     * <p>取一次存着，别在初始化过程中反复去读 —— 中途换了值，前半截和后半截
     * 按不同的配置接线，出来的东西不属于任何一份配置。管线照的是同一份（CameraManagerHolder 建的时候读）。</p>
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
     * 多路布局里各格显不显示：没启用的那一路整格藏起来 —— 一块黑方块下面写着「前座舱」，比空着更像出了故障。
     *
     * <p>藏起来之后剩下的格子占满：环视那块藏了，座舱那一列占满整块预览区；前座舱藏了，后座舱占满这一列。
     * 让出来的间距一并去掉，不留一道缝。只开环视的布局里没有这几格，调了也不会有事。</p>
     *
     * <p>布局每次重建（initViews）都回到默认的显示状态，在那里照启用的几路摆一次。管线建好后再照建出来没有摆启用的座舱
     * （{@link #showBuiltCabins}）。</p>
     *
     * @param shown 要显示的那几路
     */
    private void showLanePanes(java.util.Set<String> shown) {
        boolean surround = shown.contains("front");
        boolean cabinFront = shown.contains("back");
        showPane(R.id.composite_wrapper, surround);
        showPane(R.id.pane_cabin_front, cabinFront);
        showPane(R.id.pane_cabin_rear, shown.contains("left"));
        // 间距照这一次摆：旁边那一格在就留（布局里的 gutter），藏了就不留 —— 两个方向都摆，同一个界面上再摆一次时能还回去
        int gutter = getResources().getDimensionPixelSize(R.dimen.gutter);
        View column = findViewById(R.id.cabin_column);
        if (column != null && column.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams params =
                    (android.view.ViewGroup.MarginLayoutParams) column.getLayoutParams();
            params.setMarginStart(surround ? gutter : 0);
            column.setLayoutParams(params);
        }
        View rear = findViewById(R.id.pane_cabin_rear);
        if (rear != null && rear.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams params =
                    (android.view.ViewGroup.MarginLayoutParams) rear.getLayoutParams();
            params.topMargin = cabinFront ? gutter : 0;
            rear.setLayoutParams(params);
        }
    }

    /**
     * 管线建好了：启用的座舱那一格，建出来了就显示、没建出来（列相机出错、相机列表里没有它、映射指重了）就藏起来 ——
     * 两个方向都摆，和以前 showCabinPanes 一样：列相机出错时藏起来的，过后重建成功了要显示回来。没启用的那一格在 initViews 里
     * 已经藏了，这里不管；间距不动。
     *
     * <p>正放大着一格时不动版面（不打断放大），收回之后再照这一份管线摆（{@link #finishCollapse}）。</p>
     */
    private void showBuiltCabins(MultiCameraManager pipeline) {
        if (expandedPreview != null) {
            cabinPanesAfterCollapse = pipeline;
            return;
        }
        cabinPanesAfterCollapse = null;
        showBuiltCabin(pipeline, "back", R.id.pane_cabin_front);
        showBuiltCabin(pipeline, "left", R.id.pane_cabin_rear);
    }

    private void showBuiltCabin(MultiCameraManager pipeline, String key, int paneId) {
        if (enabledLanes.contains(key)) {
            showPane(paneId, pipeline.getCamera(key) != null);
        }
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

        // 设置首次数据写入回调
        // 这一次录像第一笔数据写出来了：计时从这里起（之后一路都不在录、等待摄像头，计时的起点也不变）
        cameraManager.setFirstDataWrittenCallback(() -> {
            AppLog.d(TAG, "收到首次数据写入回调，录制已真正开始");
            runOnUiThread(() -> {
                // 启动录制计时器（从首次写入开始计时，而不是从录制请求开始）
                // 这样右上角显示的时间是"有效录制时长"
                if (isRecording) {
                    startRecordingTimer();
                    AppLog.d(TAG, "录制计时器已启动（首次写入后）");
                }
                renderRecordState();
            });
        });

        // 设置预览尺寸回调
        cameraManager.setPreviewSizeCallback((cameraKey, cameraId, previewSize) -> {
            AppLog.d(TAG, "摄像头 " + cameraId + " 预览尺寸: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            runOnUiThread(() -> {
                // 没启用的那一路（只为后视镜建着的环视）没有画布，不摆
                final AutoFitTextureView textureView = canvasFor(cameraKey);
                if (textureView != null) {
                    applyPreviewSizeTransform(cameraKey, textureView, previewSize);
                }
            });
        });
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
        // 排在后面就永远走不到这里。冷启动时管线建好那一下（showBuilt）会另外把尺寸交给容器，
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
            textureView.setFillContainer(false);
            AppLog.d(TAG, "设置 " + cameraKey + " 宽高比: " + previewSize.getWidth() + ":" + previewSize.getHeight() + ", 适应模式");
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
                appConfig.getCarModel(), enabledLanes.size()));

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
     * 于是用户明明按过停止，重建之后又自己录上了。什么时候真的开（查过盘就开，不等环视）、
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
        AppLog.d(TAG, "启动自动录制开着，交给协调器：查过盘就录");
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
     * 熄屏 15 秒后退到后台 —— 主界面在熄屏这件事上自己管的唯一一步（大纲 §2 阶段 6：熄屏时只管界面）。
     *
     * <p>唤醒锁、录像（照熄屏那张表停不停）、车机要睡、亮屏接回因熄屏退下去的界面，
     * 都在 {@link com.kooo.evcam.screen.ScreenState} 里按固定顺序做完了；相机由通道调度照登记表关（熄屏时预览不算有人要），
     * 这里不放预览、不再自己算那张表。
     * 到点先问一遍屏幕是不是真的还黑着：深睡时这个计时是停住的，醒来之后才到点，
     * 而醒来时亮屏广播不来，只信旧标记就会把刚回到车上的人的界面退下去。安卓刚亮、车辆信号还没读回来
     * （{@link com.kooo.evcam.screen.ScreenState#known} 为假）时屏幕黑没黑还不知道：过 2 秒再看，不当场退（设计 v3.1 C7）。
     * 录像还在（含等待摄像头，或者在等开录、等自动接回）、熄屏录制生效，就留在前台（这几个条件照旧）；
     * 熄屏期间停了录，停录那一下再排一次（{@link #recordingListener}）。一次熄屏只退一次。</p>
     */
    private void scheduleBackgroundTask() {
        scheduleBackgroundStep(SCREEN_OFF_BACKGROUND_DELAY_MS);
    }

    /** 熄屏后退后台那一步排在 {@code delayMs} 之后（到点还不知道屏幕黑没黑的，过一会儿再排一次）。 */
    private void scheduleBackgroundStep(long delayMs) {
        cancelBackgroundTask();

        screenOffBackgroundRunnable = () -> {
            screenOffBackgroundRunnable = null;
            boolean dark = com.kooo.evcam.screen.ScreenState.refresh();
            if (!com.kooo.evcam.screen.ScreenState.known()) {
                // 安卓刚亮，车辆信号还没读回来：屏幕可能已经亮了，过一会儿再看
                scheduleBackgroundStep(SCREEN_UNKNOWN_RECHECK_MS);
                return;
            }
            if (!dark) {
                AppLog.d(TAG, "屏幕已亮起，取消退后台");
                return;
            }

            // 正在录制（含等待摄像头）、或在等开录（等 U 盘、等自动接回）：不退后台
            if (isRecording || recordingCoordinator.isWaiting()) {
                AppLog.d(TAG, "正在录制中（或在等开录），不退后台");
                return;
            }

            // 熄屏录制开着，不退后台（只是界面：没在录时相机照样照登记表关，大纲 §7 第 5 问）
            if (appConfig.isScreenOffRecordingEnabled()) {
                AppLog.d(TAG, "息屏录制模式已启用，不退后台");
                return;
            }

            if (leftForScreenOff) {
                return;
            }
            leftForScreenOff = true;
            moveTaskToBack(true);
            com.kooo.evcam.blackbox.BlackBox.noteImportant("熄屏 15 秒：主界面退到后台"
                    + com.kooo.evcam.blackbox.BlackBox.afterScreenOff());
            Toast.makeText(MainActivity.this, R.string.msg_screen_off_background, Toast.LENGTH_SHORT).show();
        };

        screenStateHandler.postDelayed(screenOffBackgroundRunnable, delayMs);
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
            // 协调器收到 USER 也不会自动接回
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
     * 为什么停、停了接不接、还剩几次额度、黑匣子里的起止记录、前台服务、悬浮按钮的状态、
     * 录像此刻的样子（开录中 / 在录 / 等待摄像头）。
     * 这里只把结果画出来 —— 录制键、计时器、Toast。它们出错只是看着别扭，
     * 和「有没有录上」不是一回事，混在一起会让后者也没法单独验证。回调都在主线程。</p>
     */
    private final RecordingCoordinator.Listener recordingListener =
            new RecordingCoordinator.Listener() {
        @Override
        public void onRecordingStarted() {
            lastRefusalShown = null;
            isRecording = true;
            // 录制键照协调器此刻的样子画：开录中（各路带着录像输出出了画面才加入）。
            // 计时器在第一笔数据写出来时才启动 —— 从「真的录上了」开始计，而不是从「尝试录」开始
            renderRecordState();

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
        public void onStartSettled(java.util.Collection<String> liveKeys, java.util.Collection<String> planned,
                                   boolean sdFellBack) {
            // 说的是落定时真正在录的几路：被占用、在救、没录起来的那一路不算（它回来了自己加入，状态条上说）；
            // 一路都没在录，就说在等摄像头 —— 录像没停
            if (sdFellBack && !AppConfig.isSdFallbackShownThisSession()) {
                AppConfig.setSdFallbackShownThisSession(true);
                Toast.makeText(MainActivity.this,
                        R.string.msg_sd_fallback, Toast.LENGTH_LONG).show();
            } else if (liveKeys.isEmpty()) {
                Toast.makeText(MainActivity.this,
                        R.string.msg_recording_waiting_cameras, Toast.LENGTH_LONG).show();
            } else {
                int count = liveKeys.size();
                Toast.makeText(MainActivity.this,
                        count == planned.size()
                                ? getString(R.string.msg_recording_started_all)
                                : getString(R.string.msg_recording_started_n, count),
                        Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onRecordingViewChanged() {
            // 一路加入、退出，开录中 → 在录 → 等待摄像头：录制键、角标、状态条那一句都照协调器此刻的样子重画
            renderRecordState();
        }

        @Override
        public void onRecordingStopped(RecordingStops.Reason reason, long lastedMs, boolean willResume) {
            lastRefusalShown = null;
            isRecording = false;
            stopRecordingTimer();
            renderRecordState();
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
                    // 录制器都建不起来、一笔都没写进：录像没开始，不说「中断」（界面文字审查 rec_reason_unknown）
                    text = getString(willResume
                            ? R.string.msg_record_start_retrying : R.string.msg_record_start_gave_up);
                    break;
                default: {
                    // 存储一类（写不进、管线被换）：照旧自动恢复（大纲 §7 第 3 问）。摄像头被占用不是停，不会走到这里
                    String why = getString(reason == RecordingStops.Reason.WRITE_STALLED
                            ? R.string.rec_reason_write_stalled : R.string.rec_reason_unknown);
                    text = getString(willResume
                            ? R.string.msg_recording_interrupted_resuming
                            : R.string.msg_recording_interrupted, why);
                    break;
                }
            }
            Toast.makeText(MainActivity.this, text, length).show();

            // 熄屏期间停下来的（因熄屏停的、录不下去了的）：熄屏那一刻排的 15 秒那一步可能早就因为在录没退，再排一次。
            // 只是界面：相机由通道调度照登记表关（熄屏时预览不算有人要），不用等主界面退下去
            if (com.kooo.evcam.screen.ScreenState.refresh() && screenOffBackgroundRunnable == null
                    && !leftForScreenOff) {
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
     * 会一直等到这里把相机交过去、查过盘为止。</p>
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
        // 结束进程交给看门的线程：它等主线程收尾、等停录收好文件、等每一路相机关完，最多等 EXIT_DEADLINE_MS，
        // 到点照样结束。以前是主线程自己关相机、关完再结束 —— 相机服务一卡，进程就退不掉
        // （2026-09-26 09:40 那一次：点了退出，系统里却没有这次退出的记录）
        final long exitStartedAt = android.os.SystemClock.elapsedRealtime();
        final java.util.concurrent.CountDownLatch mainCleanupDone = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch filesClosed = new java.util.concurrent.CountDownLatch(1);
        final Context app = getApplicationContext();
        new Thread(() -> finishExit(app, exitStartedAt, mainCleanupDone, filesClosed), "exit-watchdog").start();

        // 退出的次序（channel-logic §五：停录 → 登记表清空 → 调度按顺序关完 → 再退出；大纲 §2 阶段 6）：
        // 1. 先告诉调度「退出」：同步写下、置 closingAnywhere，从此只关，不开、不改输出、不救；登记表从此作废。
        //    它的下一轮是 post 出去的，跑的时候下面几步的同步部分都已做完
        com.kooo.evcam.camera.CameraManagerHolder holder = com.kooo.evcam.camera.CameraManagerHolder.getInstance();
        MultiCameraManager pipeline = holder.getCameraManager();
        if (pipeline != null) {
            pipeline.channelScheduler().exit();
        }

        // 2. 退出算一趟结束：下次打开是新的一趟，「启动自动录制」该重新生效
        com.kooo.evcam.recording.RecordingIntent.current().reset();
        // 主动退出之后不该再被亮屏拉回来
        appConfig.setUiLeftForScreenOff(false);

        // 3. 停止录制（在录的停掉，在等开录的也不等了）
        recordingCoordinator.stop(RecordingStops.Reason.USER);

        // 停止前台服务（确保清理）
        CameraForegroundService.stop(this);

        // 4. 退出就是退出：后视镜、录制按钮、补盲一起停（它们的画布死了，调度也不会再为它们排改输出 —— 退出时只关）。
        //    以前这里写着「不停，它们本来就是脱离主界面用的」—— 但紧接着 System.exit，它们反正活不下去，
        //    留着只是让系统以 START_STICKY 的名义再去重启它们
        OverlayCoordinator.onActivityDestroyed(this);
        com.kooo.evcam.zeekr.RearViewMirrorService.stop(this);

        // 释放持续唤醒锁
        WakeUpHelper.releasePersistentWakeLock();

        // 5. 放管线（只在退出和换车型时才放）：停录收尾；调度关完相机、丢掉输出，再放录制器（MultiCameraManager.release）。
        //    这一份的调度已经在退出了，release 里再叫一次 exit 无害
        holder.release();
        // 6. 停录收尾（等编码排空、收好文件）在管线的收拾线程上排着：排在它们后面数一下，退出的最后一步等到它
        //    （finishExit；channel-logic §五 停录在先 —— 以前只等相机关完，U 盘慢时进程可能在收好最后一段之前就结束了）
        if (pipeline != null) {
            pipeline.afterStopsFinished(filesClosed::countDown);
        } else {
            filesClosed.countDown();
        }

        // 保存日志（System.exit 会跳过 onDestroy，所以这里手动保存）
        AppLog.saveToPersistentLog(this);

        // 结束所有Activity；进程由看门的线程结束（见 finishExit）
        finishAffinity();
        mainCleanupDone.countDown();
    }

    /**
     * 退出最多等多久：主线程收尾、停录收好文件、调度按次序关完每一路相机。到点没好也照样结束进程。
     * 环视先关、座舱后关，通常不到半秒；相机服务卡住时一次关实测 2–20 秒（2026-10-08），要留够：进程带着没关完的相机死掉，
     * 相机服务里就剩一个没人收的句柄，下一个进程开相机全部超时。数由调度的常量算出来（{@code ChannelRules.EXIT_WAIT_MAX_MS}：
     * 手上那一步的在途封顶 + 按次序关一轮的封顶 + 余量，最坏约 105 秒，界面早就没了，用户看不到）；以前的 20 秒盖不住调度自己
     * 关相机的上限（环视 10 秒 + 座舱 30 秒）。
     */
    private static final long EXIT_DEADLINE_MS = com.kooo.evcam.camera.ChannelRules.EXIT_WAIT_MAX_MS;

    /**
     * 退出的最后一步，在看门的线程上跑：等主线程收尾、等相机关完、等停录收好文件，然后结束进程。
     *
     * <p>黑匣子里记一行结果：用了多久；到点没好的话，卡在哪 —— 是主线程收尾、录像文件，还是哪一路相机。
     * 下次再「点了退出却退不掉」，这一行就是答案。</p>
     *
     * <p>收尾的这段时间里人又点开了应用（主界面 onCreate 清掉了退出标记，{@link UserExit#isExited}）：不结束进程 ——
     * 新打开的主界面正接着用，它自己建了一份管线、等这一份关完才开相机，这时结束进程就把它一起杀了，相机开到一半丢给相机服务
     * （审查 2026-10-11；EXIT_DEADLINE_MS 加长到「退出最多等」之后这段时间可以很长）。</p>
     *
     * @param filesClosed 停录收尾都做完了（{@link MultiCameraManager#afterStopsFinished}）
     */
    private static void finishExit(Context app, long startedAt, java.util.concurrent.CountDownLatch mainCleanupDone,
                                   java.util.concurrent.CountDownLatch filesClosed) {
        boolean mainDone = false;
        try {
            mainDone = mainCleanupDone.await(EXIT_DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 相机由通道调度按次序关（环视先、座舱后）：等它报关完（closingAnywhere 变假），再等还在关的那几路的 latch
        java.util.List<String> stuck;
        while (true) {
            long left = EXIT_DEADLINE_MS - (android.os.SystemClock.elapsedRealtime() - startedAt);
            stuck = com.kooo.evcam.camera.SingleCamera.awaitAllClosed(Math.min(Math.max(left, 0L), 200L));
            if (left <= 0 || (!com.kooo.evcam.camera.ChannelScheduler.closingAnywhere() && stuck.isEmpty())) {
                break;
            }
            if (stuck.isEmpty()) {
                // 眼下没有一路在关（调度还在等手上那一步做完，开到一半、配会话最多 15 秒）：awaitAllClosed 当场返回，
                // 不歇一下就空转占满一个核，和要跑关相机那几轮的主线程抢（审查 2026-10-11）
                try {
                    Thread.sleep(Math.min(200L, left));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // 停录收尾（收拾线程上，每一路最多 CodecVideoRecorder.STOP_BUDGET_MS）：通常比关相机先做完，没做完就等到同一个期限
        boolean filesDone = false;
        try {
            long left = EXIT_DEADLINE_MS - (android.os.SystemClock.elapsedRealtime() - startedAt);
            filesDone = filesClosed.await(Math.max(left, 0L), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long took = android.os.SystemClock.elapsedRealtime() - startedAt;
        if (mainDone && stuck.isEmpty() && filesDone) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("退出：收尾用了 " + took + "ms，相机都关好了");
        } else {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("退出：等满 " + EXIT_DEADLINE_MS
                    + "ms 强制结束。" + (mainDone ? "" : "主线程收尾没做完；")
                    + (filesDone ? "" : "停录还没收好文件；")
                    + (stuck.isEmpty() ? "" : "相机 " + stuck + " 还没关好"));
        }
        if (!UserExit.isExited(app)) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("退出：收尾的时候应用又被打开了，不结束进程（新打开的主界面接着用）");
            return;
        }
        // 黑匣子在自己的线程上写：等它把上面几行写完再结束进程
        com.kooo.evcam.blackbox.BlackBox.flush(1000);
        System.exit(0);
    }

    /**
     * 录像的样子画在界面上 —— 只有这一处（大纲 5c）：录制键、计时角标、状态条上摄像头那一句，
     * 都照协调器此刻的 {@link RecordingCoordinator#view()}。协调器的回调、第一笔数据、界面重建、回到前台都走这里。主线程。
     *
     * <ul>
     *   <li>开录中：点收成方块、一明一暗，外圈在转 —— 还没有一路加入；</li>
     *   <li>在录：方块，外圈按本段走；计时角标从第一笔数据起算；</li>
     *   <li>等待摄像头：一路都没在录（录像不算停），灰方块慢慢呼吸、外圈不走，角标收起；</li>
     *   <li>没在录：照存储快照画成「开始录制」或「插入 U 盘后可录制」（{@link #refreshRecordAvailability}）。</li>
     * </ul>
     */
    private void renderRecordState() {
        RecordingCoordinator.View view = recordingCoordinator.view();
        long liveSince = recordingCoordinator.liveSinceElapsedMs();
        if (renderedRecordView == RecordingCoordinator.View.WAITING && view == RecordingCoordinator.View.RECORDING
                && segmentStartTime > 0 && liveSince > 0) {
            // 等摄像头之后第一路接回：它的文件从那一刻起（按那一刻起名），本段从这里重新算；段号只在出新的共用名字时加 1
            segmentStartTime = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - liveSince);
            updateSegmentProgress();
        }
        renderedRecordView = view;
        // 录像意图开着（开录中、在录、等待摄像头）就算在录：装饰动效让路
        com.kooo.evcam.ui.MotionPolicy.setRecording(view != RecordingCoordinator.View.IDLE);
        if (recordButtonUi != null) {
            switch (view) {
                case PREPARING:
                    recordButtonUi.setState(com.kooo.evcam.ui.RecordButtonUi.State.PREPARING);
                    break;
                case RECORDING:
                    recordButtonUi.setState(com.kooo.evcam.ui.RecordButtonUi.State.RECORDING);
                    break;
                case WAITING:
                    recordButtonUi.setState(com.kooo.evcam.ui.RecordButtonUi.State.WAITING);
                    break;
                default:
                    refreshRecordAvailability();
                    break;
            }
        }
        if (tvRecordingStats != null) {
            // 计时角标只在在录、而且计时已经起来（写出过第一笔）时显示；等待摄像头时收起，计时的起点不变
            tvRecordingStats.setVisibility(view == RecordingCoordinator.View.RECORDING && recordingStartTime > 0
                    ? View.VISIBLE : View.GONE);
        }
        renderCompositeInfoCell();
    }

    /**
     * 接上一条还在进行的录制：状态以协调器和录制管线为准，而不是去「恢复」它。
     *
     * <p>主界面被重建或者重新打开时，录制管线一直在跑（管线属于应用，主界面销毁不放它，见 onDestroy）。
     * 新界面要做的只是把按钮、计时器画成现在的样子（{@link #renderRecordState}）：写出过第一笔数据，
     * 计时从那一刻接着走。在不在录、什么样子（开录中 / 在录 / 等待摄像头）问协调器 ——
     * 以前问相机层，开录中重建的界面会画成没在录。</p>
     */
    private void syncRecordingStateFromManager() {
        if (cameraManager != null && recordingCoordinator.isRecording()) {
            isRecording = true;
            // 这一段从什么时候开始、停时录了多久，协调器记着（它不随界面重建），这里只画
            if (cameraManager.hasWrittenFirstData()) {
                startRecordingTimer(cameraManager.getFirstDataWrittenAtMs(),
                        cameraManager.getCurrentSegmentIndex() + 1);
            }
            AppLog.i(TAG, "接上了还在进行的录制 " + instanceTag() + " view=" + recordingCoordinator.view()
                    + " firstData=" + cameraManager.hasWrittenFirstData()
                    + " segment=" + (cameraManager.getCurrentSegmentIndex() + 1));
        }
        // 状态条上摄像头那一句也照此刻的样子画：界面重建时一路正被占用，新界面一样要说
        renderRecordState();
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
        AppLog.w(TAG, "回到前台：界面以为在录，录制器其实没在录 " + instanceTag());
        isRecording = false;
        stopRecordingTimer();
        renderRecordState();
        Toast.makeText(this, R.string.msg_recording_lost_in_background, Toast.LENGTH_LONG).show();
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
        // 熄屏带来的暂停：那一刻系统已经不交互了（人切走、重建、关掉时还交互着）。熄屏广播后到时据此照样记「因熄屏退下去」
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        pausedByScreenOff = !isFinishing() && !isChangingConfigurations() && power != null && !power.isInteractive();
        com.kooo.evcam.camera.StallWatch.setForeground(false);
        AppLog.d(TAG, "onPause called, isRecording=" + isRecording);

        // 预览不在前台了，注销这一项；剩下还有没有人要，问登记表。
        // 录像那一项由协调器登记（录像意图开着时），这里不替它填
        // 关不关由通道调度按登记表判（没人要了的那一路：亮屏、前台服务在时各自保持 30 秒，否则当场按次序关），
        // 这里不自己关。画布不撤回：最小化时它还在（安卓 12 只拆显示层），预览输出成了「留着的」，通道不动
        //
        // 重建（换日夜模式、换语言）时不注销：主界面一直看得见，新界面在同一趟里接回画布、自己再登记（大纲 §7.6）。
        // 有一格没接回（管线里没有那一路的相机，画布留不住），新界面要等第一次绘制建出那一格画布才数齐、才登记 ——
        // 这里先注销的话，调度在那之前醒来那一轮看到没人要，没有前台服务时就当场把相机关了，画布一齐又按次序重开
        // （2026-10-11 审查，阶段 4a）。从最近任务划掉不是重建，照常注销
        if (isChangingConfigurations()) {
            AppLog.d(TAG, "onPause during a configuration change: PREVIEW stays claimed for the new instance");
            return;
        }
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
        pausedByScreenOff = false;
        com.kooo.evcam.camera.StallWatch.setForeground(true);
        AppLog.i(TAG, "onResume " + instanceTag() + " surround: " + describeComposite());
        
        AppLog.d(TAG, "onResume called, wasInBackground=" + wasInBackground + ", isRecording=" + isRecording);
        
        // 回到前台：让 ScreenState 重问一遍安卓（亮屏广播没来的话这一下补上）；车辆说屏幕黑着的，让车辆信号当场读一遍 ——
        // 人真回来了它会读到亮屏。熄屏 v3 起回到前台不等于屏幕亮着：车辆说黑而安卓亮着时（车机黑着醒了）主界面也可能回到前台，
        // 所以不硬当亮屏
        com.kooo.evcam.screen.ScreenState.refresh();

        // 人已经在界面上了，「因熄屏退下去」这个记号就作废 —— 不管是自己接回来的，
        // 还是用户自己点回来的；下一次熄屏照样再退一次
        appConfig.setUiLeftForScreenOff(false);
        leftForScreenOff = false;

        // 手里那份管线不再是进程里在用的那一份（在后台时被 Holder 放掉重建了、建的时候列相机出错）：接上现在那一份
        reattachPipelineIfReplaced("resume");

        // 预览又要用相机了 —— 画布都在、管线也接着的话现在登记（最小化回来就是这样：画布还在）；新建的界面还没接上管线，等接上再登记（见 claimPreviewIfReady）
        claimPreviewIfReady();

        // 前台服务：每次回到前台都刷新一次（大纲 §1 MainActivity，2.11 阶段 4a）
        refreshForegroundService();

        // 界面记的录制状态和录制器的真实状态先对一下；对不上就以录制器为准
        reconcileRecordingState();
        // 在后台的这段时间里有一路退出、加入过（回调可能没赶上这个界面），U 盘可能插拔过：
        // 录制键、状态条照此刻的样子重画（存储先按上一份快照，再去后台探测一次，结果经 storageListener 回来）
        renderRecordState();
        updateStatusLine();
        com.kooo.evcam.storage.StorageState.refresh(this, "resume");

        // 回到前台要用相机：上面 claim(PREVIEW) 那一下叫醒通道调度，它照登记表开（主界面在前就能用相机）
    }

    /**
     * 刷新前台服务：开机自启动开着、或者有录像意图（「这一趟要录」：在录、在等开录、因熄屏停下等亮屏接回）时，
     * 主界面每次回到前台都 start 一次。通知上的字服务自己照录像此刻的样子写（{@link RecordingCoordinator#notificationTitle}：
     * 正在录制、等待摄像头，或者在后台运行）。
     *
     * <p>为什么每次都刷：安卓 12 起，后台才起的前台服务用不了相机（lifecycle-spec §3）。通道调度只认两种「能用相机」：
     * 主界面在前，或者前台服务是主界面在前时起的、那时刷新过（{@link CameraForegroundService#startedWhileVisible()}，
     * 在 onStartCommand 那一刻判，只升不降）。以前只在服务不在时才起 —— 开机后在后台起的那个一直在，
     * 就永远不会变成「能用相机」，主界面一退到后台调度就什么都不开（大纲 §1、§6 风险 7：通知会刷一下）。</p>
     *
     * <p>留不留服务的条件和 {@link CameraForegroundService#stop} 是同一条（开机自启动、或者这一趟要录）：
     * 都不是就不起 —— 停不停由停录、退出那几处管。</p>
     */
    private void refreshForegroundService() {
        if (!appConfig.isAutoStartOnBoot() && !RecordingIntent.current().recordingWanted()
                && !recordingCoordinator.isRecording()) {
            return;
        }
        CameraForegroundService.start(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        com.kooo.evcam.blackbox.BlackBox.noteImportant("主界面 onDestroy finishing=" + isFinishing()
                + " changingConfigurations=" + isChangingConfigurations());
        AppLog.i(TAG, "onDestroy " + instanceTag() + " finishing=" + isFinishing()
                + " changingConfigurations=" + isChangingConfigurations());

        // 相机管线属于应用，不属于主界面（大纲 §2 阶段 6，channel-logic §五）：主界面销毁不放它，相机关不关交给通道调度 ——
        // 主界面只是登记表里的一个需要方。预览画布在画布销毁那一下交给管线（keepPreviewCanvas；onSurfaceTextureDestroyed 在
        // onDestroy 之后才来），PREVIEW 在 onPause 放过了（重建时留给新界面）：这里都不再做 —— 再放一遍 PREVIEW 的话，
        // 重建时调度会把相机关了再开。以前这里看「还要不要」决定放不放管线：放了的话后视镜当场黑掉、录像断在重建上
        if (cameraManager != null && !cameraManager.isReleased()) {
            // 退出那条路已经放了管线（exitApp），那时不写
            com.kooo.evcam.blackbox.BlackBox.note("主界面销毁：预览画布交给管线，管线留给应用");
        }
        // 前台服务、中转写入留不留（keepServices，条件照旧）：还在录、在等开录（开机自启动、悬浮按钮拉起）、上一次停录还在收拾
        // （needsPipeline），只是重建（夜间模式、语言切换），或者还有别人在用相机（超级镜子、拍照）—— 留着
        com.kooo.evcam.camera.CameraNeeds needs = com.kooo.evcam.camera.CameraNeeds.current();
        final boolean othersNeedCamera = needs.heldByAnyoneExcept(
                com.kooo.evcam.camera.CameraNeeds.Holder.PREVIEW);
        final boolean keepServices = cameraManager != null && !cameraManager.isReleased()
                && (recordingCoordinator.needsPipeline() || isChangingConfigurations() || othersNeedCamera);
        AppLog.i(TAG, "onDestroy 前台服务、中转写入" + (keepServices ? "保留" : "照没人要收") + " "
                + instanceTag() + " recording=" + recordingCoordinator.isRecording()
                + " changingConfigurations=" + isChangingConfigurations() + " needs=" + needs.describe());

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
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().removeReplacedListener(pipelineReplaced);
        cancelBackgroundTask();

        // 前台服务：录制还在继续就不能停 —— 没有它，系统会在后台把录制掐掉（停不停还看开机自启动、这一趟要录，见 stop）
        if (!keepServices) {
            CameraForegroundService.stop(this);
        }

        // 停止存储清理任务
        if (storageCleanupManager != null) {
            storageCleanupManager.stop();
        }

        // 文件传输（U 盘中转写入）：录制还在继续，分段还要往 U 盘搬，不能停
        if (!keepServices) {
            FileTransferManager.getInstance(this).stop();
        }

        // 这个界面设在管线上的回调一律摘掉：管线比界面活得久，不摘的话销毁了的界面被它留住、还被回调
        if (cameraManager != null) {
            cameraManager.detachUiCallbacks();
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
        // 放大中管线建好了：现在照它摆座舱那两格（还是手里这一份才摆）
        MultiCameraManager pending = cabinPanesAfterCollapse;
        if (pending != null && pending == cameraManager) {
            showBuiltCabins(pending);
        }
        cabinPanesAfterCollapse = null;
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

    /** 状态条最右那一格：在录时有一路被占用、无法录制，写这件事（{@link #cameraHint}），否则是合成流的识别结果。 */
    private void renderCompositeInfoCell() {
        if (tvCompositeInfo == null) {
            return;
        }
        String hint = cameraHint();
        String line = hint != null ? hint : compositeInfoText;
        tvCompositeInfo.setText(line);
        tvCompositeInfo.setVisibility(line.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    }

    /**
     * 状态条上关于摄像头的那一句，照此刻的样子说（2026-10-10）；不用说是 null。
     *
     * <p>只在在录（至少一路在录）时说，取自协调器给的各路摘要（{@link RecordingCoordinator#summary}，
     * 管线按 {@link com.kooo.evcam.camera.RecordingLanes#hint} 选的那一句）：一路被占用、两路以上被占用、
     * 一路无法录制（停手了、录制器建不起来）。一路都没在录时录制键上说「等待摄像头」，这里不再说。</p>
     *
     * <p>每次都现问协调器，不靠回调时记下的标记：界面重建、回到前台时，一路正被占用，新界面照样说。</p>
     */
    private String cameraHint() {
        if (recordingCoordinator == null
                || recordingCoordinator.view() != RecordingCoordinator.View.RECORDING) {
            return null;
        }
        com.kooo.evcam.camera.RecordingLanes.Summary summary = recordingCoordinator.summary();
        if (summary == null) {
            return null;
        }
        com.kooo.evcam.camera.RecordingLanes.Hint hint = summary.hint;
        switch (hint.kind) {
            case LANE_TAKEN:
                return getString(R.string.status_camera_lane_taken,
                        com.kooo.evcam.camera.CameraNames.ofSlot(this, hint.key));
            case LANES_TAKEN:
                return getString(R.string.status_cameras_taken);
            case LANE_UNAVAILABLE:
                return getString(R.string.status_camera_lane_unavailable,
                        com.kooo.evcam.camera.CameraNames.ofSlot(this, hint.key));
            default:
                return null;
        }
    }


    /**
     * 抽屉里的「超级镜子」开关。
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
