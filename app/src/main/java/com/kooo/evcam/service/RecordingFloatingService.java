package com.kooo.evcam.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import androidx.core.content.ContextCompat;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.overlay.FloatingAction;

/**
 * 悬浮按钮服务：整个应用只有这一个悬浮按钮。
 *
 * <h3>为什么类名还叫 Recording</h3>
 *
 * <p>0.45 之前有两个按钮：这一个管录制、另一个管打开应用。合并时留下了这一个
 * —— 录制那套「应用在前台 / 在后台 / 没运行」的分支是真正难的部分，另一个只是
 * 一句 startActivity。类名没跟着改：改名和合并放进同一个提交，diff 就没法看了。</p>
 *
 * <p>按钮的颜色说明现在是不是在录（录制红的方块 + 本段进度，空闲是个空心圈；一路都没在录、在等摄像头时
 * 是灰环、灰方块，没有进度弧）。单击和长按各做什么由设置决定，默认都是打开主界面（见 {@link FloatingAction}）。</p>
 */
public class RecordingFloatingService extends Service {
    private static final String TAG = "RecordingFloatingService";
    private static final int NOTIFICATION_ID = 1002;

    public static final String ACTION_SHOW = "com.kooo.evcam.action.SHOW_RECORDING_FLOATING";
    public static final String ACTION_HIDE = "com.kooo.evcam.action.HIDE_RECORDING_FLOATING";
    public static final String ACTION_UPDATE_SIZE = "com.kooo.evcam.action.UPDATE_RECORDING_FLOATING_SIZE";

    /** 大小、字号、透明度、时长显示改了，就地重贴一遍。 */
    public static final String ACTION_UPDATE_STYLE = "com.kooo.evcam.action.UPDATE_FLOATING_STYLE";
    /** 「重置悬浮窗布局」：大小和位置回默认，当场挪过去。 */
    public static final String ACTION_RESET_POSITION = "com.kooo.evcam.action.RESET_FLOATING_POSITION";

    /**
     * 录像的样子变了（RecordingCoordinator 推的：开录、停录、等待摄像头、又有一路在录），告诉按钮重画。
     *
     * <p>这个广播原来由已删掉的那个按钮的类发，本服务一直同时听着这两个 action。广播只是叫一声：
     * 收到了照协调器此刻的样子画（{@link #renderRecordingState}），不带状态 —— 以前带着「在不在录」，
     * 说不出「在等摄像头」。</p>
     */
    public static final String ACTION_RECORDING_STATE_CHANGED = "com.kooo.evcam.RECORDING_STATE_CHANGED";

    public static void sendRecordingStateChanged(Context context) {
        Intent intent = new Intent(ACTION_RECORDING_STATE_CHANGED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    public static final String EXTRA_BUTTON_SIZE = "button_size";
    public static final String EXTRA_TEXT_SIZE = "text_size";

    /** 按住多久算长按。比系统默认的 500ms 略长一点：这个按钮是在车上按的。 */
    private static final long LONG_PRESS_MS = 600;

    private final IBinder binder = new LocalBinder();
    private Handler mainHandler;
    private AppConfig appConfig;
    private WindowManager windowManager;

    // 长按：计时器要在 appConfig 之后声明，它读的是 appConfig
    private boolean longPressFired = false;
    /** 位置锁着时手指滑了一段：这一下既不是点击也不是长按。 */
    private boolean slidWhileLocked = false;
    private final Runnable longPressRunnable = () -> {
        longPressFired = true;
        perform(FloatingAction.fromKey(appConfig.getFloatingLongPressAction()));
    };

    // 视图组件
    private FrameLayout floatingContainer;
    private RecordingButtonView recordingButton;
    private TextView timeTextView;

    // 布局参数
    private WindowManager.LayoutParams layoutParams;
    private int screenWidth;
    private int screenHeight;

    // 拖动相关
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;
    private static final int CLICK_THRESHOLD = 10;

    // 录制服务：按钮在时绑着它（开录、停录不经它，见 QuickActions）
    private boolean isServiceBound = false;

    // 录制状态（照协调器画，见 renderRecordingState）
    /** 录像意图开着（开录中、在录、等待摄像头）：按一下是停。 */
    private boolean isRecording = false;
    /** 一路都没在录、在等摄像头：灰环、灰方块、没有进度弧，不显示时长。 */
    private boolean waitingForCameras = false;
    private long recordingStartTime = 0;
    private Runnable timeUpdateRunnable;

    // 广播接收器
    private BroadcastReceiver sizeUpdateReceiver;
    private BroadcastReceiver recordingStateReceiver;

    public class LocalBinder extends Binder {
        public RecordingFloatingService getService() {
            return RecordingFloatingService.this;
        }
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            isServiceBound = true;

            // 之后的变化协调器会发 ACTION_RECORDING_STATE_CHANGED 广播，本服务一直听着；这里只对一次当前状态
            renderRecordingState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isServiceBound = false;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        com.kooo.evcam.blackbox.BlackBox.attach(this, "Service:RecordingFloatingService");
        com.kooo.evcam.blackbox.BlackBox.noteImportant("后台服务 RecordingFloatingService onCreate");
        AppLog.d(TAG, "录制悬浮服务创建");

        mainHandler = new Handler(Looper.getMainLooper());
        appConfig = new AppConfig(this);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        // 获取屏幕尺寸
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        // 在后台线程绑定录制服务
        new Thread(() -> bindRecordingService()).start();

        // 注册大小更新广播接收器
        registerSizeUpdateReceiver();
        
        // 注册录制状态广播接收器
        registerRecordingStateReceiver();
    }

    private void registerSizeUpdateReceiver() {
        sizeUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_UPDATE_SIZE.equals(intent.getAction())) {
                    int buttonSize = intent.getIntExtra(EXTRA_BUTTON_SIZE, -1);
                    int textSize = intent.getIntExtra(EXTRA_TEXT_SIZE, -1);
                    AppLog.d(TAG, "收到大小更新广播: button=" + buttonSize + ", text=" + textSize);
                    updateFloatingSize(buttonSize, textSize);
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_UPDATE_SIZE);
        // Android 14+ 需要指定 RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(sizeUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(sizeUpdateReceiver, filter);
        }
    }
    
    private void registerRecordingStateReceiver() {
        recordingStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                // 兼容两种广播 action；广播只是叫一声，样子照协调器此刻的画
                if ("com.kooo.evcam.RECORDING_STATE_CHANGED".equals(action) ||
                    "com.kooo.evcam.action.RECORDING_STATE_CHANGED".equals(action)) {
                    AppLog.d(TAG, "收到录制状态广播");
                    mainHandler.post(() -> renderRecordingState());
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.kooo.evcam.RECORDING_STATE_CHANGED");
        filter.addAction("com.kooo.evcam.action.RECORDING_STATE_CHANGED");
        // Android 14+ 需要指定 RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(recordingStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(recordingStateReceiver, filter);
        }
    }

    private void updateFloatingSize(int buttonSizeDp, int textSizeSp) {
        if (floatingContainer == null || recordingButton == null || timeTextView == null) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;

        // 更新按钮大小
        if (buttonSizeDp > 0) {
            int buttonSize = (int) (buttonSizeDp * density);
            recordingButton.getLayoutParams().width = buttonSize;
            recordingButton.getLayoutParams().height = buttonSize;
            recordingButton.setButtonSize(buttonSize);
            recordingButton.requestLayout();
        }

        // 更新时间文字大小
        if (textSizeSp > 0) {
            timeTextView.setTextSize(textSizeSp);
            // 根据按钮大小调整padding
            int buttonSize = recordingButton.getLayoutParams().width;
            int padding = Math.max(8, buttonSize / 8);
            timeTextView.setPadding(padding, padding / 2, padding, padding / 2);
            timeTextView.requestLayout();
        }

        // 刷新窗口
        windowManager.updateViewLayout(floatingContainer, layoutParams);
        AppLog.d(TAG, "悬浮按钮大小已更新");
    }

    /**
     * 大小、字号、透明度、显示不显示时长：<b>就地</b>改，不重建视图。
     *
     * <h3>为什么不是关掉再开</h3>
     *
     * <p>0.45 里透明度和时长开关走的是「先发 HIDE 再发 SHOW」，两条指令各自
     * 起一个线程 —— 顺序没有保证。SHOW 先跑完、HIDE 后到，按钮就没了。
     * 「有时能调、有时按钮消失」和「关掉时长把整个按钮关掉」是同一个竞态。</p>
     *
     * <p>这里不碰生命周期，只把当前配置重新读一遍贴上去。</p>
     */
    private void applyStyle() {
        if (floatingContainer == null || recordingButton == null || timeTextView == null) {
            return;
        }
        updateFloatingSize(appConfig.getRecordingFloatingButtonSizeDp(),
                appConfig.getRecordingFloatingTimeTextSizeSp());
        floatingContainer.setAlpha(appConfig.getFloatingWindowAlpha() / 100f);
        timeTextView.setVisibility(isRecording && !waitingForCameras && appConfig.isFloatingDurationVisible()
                ? View.VISIBLE : View.GONE);
        AppLog.d(TAG, "悬浮按钮外观已更新: 大小 "
                + appConfig.getRecordingFloatingButtonSizeDp() + "dp, 透明度 "
                + appConfig.getFloatingWindowAlpha() + "%, 时长 "
                + (appConfig.isFloatingDurationVisible() ? "显示" : "隐藏"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (com.kooo.evcam.UserExit.blocks(this, "RecordingFloatingService")) {
            // 用户已经退出：被系统重启也不起来
            stopSelf();
            return START_NOT_STICKY;
        }
        // 只记系统做 sticky 重启的那种（flags 里有 RETRY / REDELIVERY，或 intent 为空）：
        // 「START_STICKY 到底生不生效」看它。例行的启动不记，和前台服务一样
        if (flags != 0 || intent == null) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("RecordingFloatingService onStartCommand flags=" + flags
                    + (intent == null ? " intent=null(sticky重启)" : "")
                    + " startId=" + startId);
        }
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_HIDE.equals(action)) {
                hideFloatingWindow();
            } else if (ACTION_UPDATE_STYLE.equals(action) || ACTION_UPDATE_SIZE.equals(action)) {
                // 只在已经显示时才有意义；没显示就什么都不做，
                // 尤其不能顺手 showFloatingWindow —— 那会把关掉的按钮又拉出来
                if (floatingContainer != null) {
                    mainHandler.post(this::applyStyle);
                }
            } else if (ACTION_RESET_POSITION.equals(action)) {
                // 同上：没显示就不管，下次显示时自然落在默认位置
                if (floatingContainer != null) {
                    mainHandler.post(this::resetPosition);
                }
            } else {
                showFloatingWindow();
            }
        }
        // 被杀了要不要重启：跟着「开机自启动」开关（规格 §3）。悬浮按钮由 Recovery 按设置恢复，不用自己粘着
        return com.kooo.evcam.CameraForegroundService.stickiness(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        com.kooo.evcam.blackbox.BlackBox.noteImportant("RecordingFloatingService onDestroy");
        super.onDestroy();
        hideFloatingWindow();

        if (isServiceBound) {
            unbindService(serviceConnection);
        }

        // 注销广播接收器
        if (sizeUpdateReceiver != null) {
            unregisterReceiver(sizeUpdateReceiver);
        }
        if (recordingStateReceiver != null) {
            unregisterReceiver(recordingStateReceiver);
        }

        stopTimeUpdate();
    }

    // ========== 服务绑定 ==========

    private void bindRecordingService() {
        Intent intent = new Intent(this, CameraRecordingService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    // ========== 悬浮窗管理 ==========

    private void showFloatingWindow() {
        if (floatingContainer != null) {
            return; // 已经显示
        }

        if (!WakeUpHelper.hasOverlayPermission(this)) {
            AppLog.e(TAG, "没有悬浮窗权限");
            stopSelf();
            return;
        }

        createFloatingWindow();
    }

    private void hideFloatingWindow() {
        if (floatingContainer != null && windowManager != null) {
            windowManager.removeView(floatingContainer);
            floatingContainer = null;
            recordingButton = null;
            timeTextView = null;
        }
        stopTimeUpdate();
    }

    /**
     * 放到上次拖到的位置；没存过（或刚被重置）就放到默认位置。
     *
     * <p>默认位置贴在右上角，离右边、离上边多远按 dp 记（实车调好后测得，见 AppConfig）。</p>
     */
    private void placeAtSavedOrDefault(int buttonSizePx) {
        int x = appConfig.getRecordingFloatingX();
        int y = appConfig.getRecordingFloatingY();
        if (x < 0 || y < 0) {
            float density = getResources().getDisplayMetrics().density;
            x = AppConfig.defaultFloatingX(screenWidth, buttonSizePx, density);
            y = AppConfig.defaultFloatingY(density);
        }
        layoutParams.x = Math.min(x, Math.max(0, screenWidth - buttonSizePx));
        layoutParams.y = Math.min(y, Math.max(0, screenHeight - buttonSizePx));
    }

    /**
     * 重置之后当场挪过去。
     *
     * <p>以前重置只清配置，按钮留在原地，要等下次重新显示才回去 —— 点了提示
     * 「已重置」、按钮纹丝不动，看起来就是没生效。</p>
     */
    private void resetPosition() {
        if (floatingContainer == null || recordingButton == null) {
            return;
        }
        applyStyle();
        placeAtSavedOrDefault(recordingButton.getLayoutParams().width);
        windowManager.updateViewLayout(floatingContainer, layoutParams);
        AppLog.i(TAG, "悬浮按钮已回到默认位置: " + layoutParams.x + "," + layoutParams.y);
    }

    private void createFloatingWindow() {
        // 获取配置的大小
        int buttonSizeDp = appConfig.getRecordingFloatingButtonSizeDp();
        int timeTextSizeSp = appConfig.getRecordingFloatingTimeTextSizeSp();
        float density = getResources().getDisplayMetrics().density;
        int buttonSize = (int) (buttonSizeDp * density);

        // 创建容器
        floatingContainer = new FrameLayout(this);
        // 透明度沿用原来那个按钮的设置项，合并之后它管这一个
        floatingContainer.setAlpha(appConfig.getFloatingWindowAlpha() / 100f);

        // 创建水平布局容器
        LinearLayout horizontalContainer = new LinearLayout(this);
        horizontalContainer.setOrientation(LinearLayout.HORIZONTAL);
        horizontalContainer.setGravity(Gravity.CENTER_VERTICAL);

        // 创建录制按钮
        recordingButton = new RecordingButtonView(this, buttonSize);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(buttonSize, buttonSize);
        horizontalContainer.addView(recordingButton, buttonParams);

        // 创建时间显示
        timeTextView = new TextView(this);
        timeTextView.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        timeTextView.setTextSize(timeTextSizeSp);
        timeTextView.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));
        // 根据按钮大小调整padding
        int padding = Math.max(8, buttonSize / 8);
        timeTextView.setPadding(padding, padding / 2, padding, padding / 2);
        timeTextView.setVisibility(View.GONE); // 默认隐藏
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        timeParams.leftMargin = Math.max(4, buttonSize / 16); // 距离按钮
        horizontalContainer.addView(timeTextView, timeParams);

        // 将水平容器添加到浮动容器
        floatingContainer.addView(horizontalContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        // 设置布局参数
        int windowType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            windowType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        layoutParams.gravity = Gravity.TOP | Gravity.START;

        // 恢复上次拖到的位置；没存过就用默认位置
        placeAtSavedOrDefault(buttonSize);

        // 设置触摸事件
        floatingContainer.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        longPressFired = false;
                        mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int deltaX = (int) (event.getRawX() - initialTouchX);
                        int deltaY = (int) (event.getRawY() - initialTouchY);

                        if (Math.abs(deltaX) > CLICK_THRESHOLD || Math.abs(deltaY) > CLICK_THRESHOLD) {
                            // 位置锁上时手指照样能滑，只是按钮不跟着走 ——
                            // 但这一下已经不算「点一下」了，也不该触发长按
                            isDragging = !appConfig.isFloatingPositionLocked();
                            mainHandler.removeCallbacks(longPressRunnable);
                            if (!isDragging) {
                                slidWhileLocked = true;
                            }
                        }

                        if (isDragging) {
                            int newX = initialX + deltaX;
                            int newY = initialY + deltaY;

                            // 边界限制：只把按钮夹在屏幕内，不再额外限制活动范围。
                            //
                            // 原实现是：
                            //     int maxWidth = screenWidth - 200;
                            //     newX = Math.max(0, Math.min(newX, screenWidth - maxWidth));
                            // screenWidth - maxWidth 恒等于 200，等于把 X 锁死在 0..200 ——
                            // 无论屏幕多宽，按钮都只能在左边一小条里移动。这是个笔误，
                            // 本意应该是 Math.min(newX, maxWidth)。
                            newX = Math.max(0, Math.min(newX, screenWidth - buttonSize));
                            newY = Math.max(0, Math.min(newY, screenHeight - buttonSize));

                            layoutParams.x = newX;
                            layoutParams.y = newY;
                            windowManager.updateViewLayout(floatingContainer, layoutParams);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        mainHandler.removeCallbacks(longPressRunnable);
                        if (slidWhileLocked) {
                            slidWhileLocked = false;
                            return true;
                        }
                        if (!isDragging && !longPressFired) {
                            perform(FloatingAction.fromKey(appConfig.getFloatingTapAction()));
                        } else if (!isDragging) {
                            // 长按已经在计时器里做过了，抬手不再做第二件事
                            AppLog.d(TAG, "长按已处理，忽略这次抬手");
                        } else {
                            // 拖动结束才落盘，避免拖动过程中反复写 SharedPreferences
                            appConfig.setRecordingFloatingPosition(layoutParams.x, layoutParams.y);
                        }
                        return true;
                }
                return false;
            }
        });

        // 添加到窗口
        try {
            windowManager.addView(floatingContainer, layoutParams);
            // 在不在录、什么样子只问协调器（和录制服务、前台通知同一个答案），之后的变化它会广播过来。
            // 以前靠主界面隔 500 ms 推它自己记的状态：主界面刚重建、还没对上时推的是「没在录」，
            // 录着像按钮却是灰的
            renderRecordingState();
            applyStyle();
            AppLog.d(TAG, "录制悬浮窗创建成功");
        } catch (Exception e) {
            AppLog.e(TAG, "添加悬浮窗失败", e);
            stopSelf();
        }
    }

    // ========== 按钮动作 ==========

    /**
     * 单击 / 长按各做什么，见 {@link FloatingAction}；怎么做在 {@link com.kooo.evcam.overlay.QuickActions}（灵动贴走同一份）。
     */
    private void perform(FloatingAction action) {
        com.kooo.evcam.overlay.QuickActions.perform(this, action,
                com.kooo.evcam.recording.RecordingCoordinator.Why.FLOATING);
    }

    /**
     * 照协调器此刻的样子画按钮（主线程）：开录中、在录是录制红的方块 + 本段进度 + 时长；等待摄像头是灰环、灰方块，
     * 没有进度弧、不显示时长；没在录是灰圈灰点。在不在录、什么样子只问协调器（{@code RecordingCoordinator#view}），
     * 和主界面录制键、前台通知同一个答案。
     */
    private void renderRecordingState() {
        com.kooo.evcam.recording.RecordingCoordinator coordinator =
                com.kooo.evcam.recording.RecordingCoordinator.get(this);
        com.kooo.evcam.recording.RecordingCoordinator.View view = coordinator.view();
        isRecording = view != com.kooo.evcam.recording.RecordingCoordinator.View.IDLE;
        waitingForCameras = view == com.kooo.evcam.recording.RecordingCoordinator.View.WAITING;

        if (recordingButton != null) {
            recordingButton.setRecording(isRecording, waitingForCameras);
        }

        if (isRecording && !waitingForCameras) {
            // 从这一段真正开录的时刻算（中途才显示出来的按钮也对得上），用开机以来的时钟：
            // 车机对时会把系统时钟往回拨（2026-10-08 实车拨回过 20 秒），按系统时钟算时长会变负
            long startedAt = coordinator.startedAtElapsedMs();
            recordingStartTime = startedAt > 0 ? startedAt : android.os.SystemClock.elapsedRealtime();
            startTimeUpdate();
            if (timeTextView != null) {
                // 时长是个开关：有人只要一个按钮，不要旁边那串数字
                timeTextView.setVisibility(appConfig.isFloatingDurationVisible()
                        ? View.VISIBLE : View.GONE);
            }
        } else {
            stopTimeUpdate();
            if (recordingButton != null) {
                // 环归零：下次开录从十二点开始，而不是接着上次那一段；等摄像头时没有进度弧
                recordingButton.setProgress(0f);
            }
            if (timeTextView != null) {
                timeTextView.setVisibility(View.GONE);
                timeTextView.setText("00:00");
            }
        }
    }

    // ========== 时间更新 ==========

    private void startTimeUpdate() {
        stopTimeUpdate();

        timeUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRecording && timeTextView != null) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    long duration = Math.max(0, now - recordingStartTime);
                    String timeStr = formatDuration(duration);
                    timeTextView.setText(timeStr);
                    // 本段的进度从这一阵有一路在录起量：等摄像头之后接回，分段从接回那一刻重新算
                    long liveSince = com.kooo.evcam.recording.RecordingCoordinator.get(RecordingFloatingService.this)
                            .liveSinceElapsedMs();
                    updateRingProgress(liveSince > 0 ? Math.max(0, now - liveSince) : duration);
                    mainHandler.postDelayed(this, 1000);
                }
            }
        };

        mainHandler.post(timeUpdateRunnable);
    }

    private void stopTimeUpdate() {
        if (timeUpdateRunnable != null) {
            mainHandler.removeCallbacks(timeUpdateRunnable);
            timeUpdateRunnable = null;
        }
    }

    /**
     * 环走到哪儿了。
     *
     * <p>服务这边不知道当前这一段是什么时候切的，只知道这一阵有一路在录是从什么时候起的（协调器的
     * {@code liveSinceElapsedMs}；等摄像头之后接回，分段从接回那一刻重新算）—— 分段是等长的，
     * 所以「从那一刻起录了多久对分段时长取余」就是本段的进度。差别只会出现在中途改过分段长度的那一次。</p>
     *
     * @param elapsedMs 这一阵有一路在录了多久
     */
    private void updateRingProgress(long elapsedMs) {
        if (recordingButton == null) {
            return;
        }
        long segment = com.kooo.evcam.profile.RecordSpecs.segmentMs(
                com.kooo.evcam.profile.RecordSpecs.forCameraKey(this, com.kooo.evcam.profile.RecordSpecs.shownCameraKey(this)).segmentMinutes);
        if (segment <= 0) {
            return;
        }
        recordingButton.setProgress((elapsedMs % segment) / (float) segment);
    }

    private String formatDuration(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        seconds = seconds % 60;
        minutes = minutes % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }

    // ========== 自定义录制按钮视图（iOS 扁平化风格）==========

    private static class RecordingButtonView extends View {
        private Paint backgroundPaint;
        private Paint iconPaint;
        private Paint shadowPaint;
        /** 外面那一圈：待机、等摄像头是灰的空心圈，录制中是浅红的轨道。 */
        private Paint ringPaint;
        /** 录制中沿着轨道走的那一段，和主界面录制键的分段进度环同一个意思。 */
        private Paint progressPaint;
        private final android.graphics.RectF ringRect = new android.graphics.RectF();
        private boolean isRecording = false;
        /** 录像意图开着、一路都没在录（等摄像头）：灰方块、灰环，没有进度弧 —— 红色只标「正在录」。 */
        private boolean waiting = false;
        /** 本段录到哪儿了，0–1。 */
        private float progress = 0f;
        private float centerX, centerY;
        private float radius;
        private int buttonSize;
        private float cornerRadius;

        public RecordingButtonView(Context context, int buttonSize) {
            super(context);
            this.buttonSize = buttonSize;
            init();
        }

        private void init() {
            // 背景画笔 - iOS 扁平化纯色
            backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            backgroundPaint.setStyle(Paint.Style.FILL);

            // 中间那个点 / 方块是录制状态本身，所以它是红的；底色不是
            iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            iconPaint.setStyle(Paint.Style.FILL);
            iconPaint.setColor(ContextCompat.getColor(getContext(), R.color.recording));

            // 阴影画笔 - iOS 风格轻微阴影
            shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(Color.parseColor("#20000000")); // 半透明黑色阴影

            ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);

            progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            progressPaint.setStyle(Paint.Style.STROKE);
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setColor(ContextCompat.getColor(getContext(), R.color.recording));
        }

        /** 本段录到哪儿了，0–1。只在录制中画。 */
        public void setProgress(float value) {
            float clamped = value < 0f ? 0f : (value > 1f ? 1f : value);
            if (Math.abs(clamped - progress) > 0.002f) {
                progress = clamped;
                invalidate();
            }
        }

        public void setRecording(boolean recording, boolean waitingForCameras) {
            isRecording = recording;
            waiting = waitingForCameras;
            invalidate();
        }

        public void setButtonSize(int size) {
            this.buttonSize = size;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            centerX = w / 2f;
            centerY = h / 2f;
            radius = Math.min(w, h) / 2f - 6;
            // iOS 风格圆角 - 圆形按钮但带轻微圆角
            cornerRadius = radius * 0.25f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            // 和主界面那个录制键一样：底色只在两档之间走（待机、等摄像头 sunken、
            // 录制中 recording_quiet），红色始终只出现在中间那个点上。
            // 整块变红等于把「这个按钮」和「正在录」混为一谈。
            boolean live = isRecording && !waiting;
            backgroundPaint.setColor(ContextCompat.getColor(getContext(),
                    live ? R.color.recording_quiet : R.color.sunken));

            // 绘制阴影（iOS 风格）
            float shadowOffset = radius * 0.08f;
            canvas.drawRoundRect(
                    centerX - radius + shadowOffset,
                    centerY - radius + shadowOffset,
                    centerX + radius + shadowOffset,
                    centerY + radius + shadowOffset,
                    cornerRadius,
                    cornerRadius,
                    shadowPaint
            );

            // 绘制圆角矩形背景（iOS 扁平化风格）
            canvas.drawRoundRect(
                    centerX - radius,
                    centerY - radius,
                    centerX + radius,
                    centerY + radius,
                    cornerRadius,
                    cornerRadius,
                    backgroundPaint
            );

            // 外面那一圈，和主界面录制键的分段进度环一个意思。
            //
            // 待机时是<b>灰的空心圈</b> —— 合并之前那个「打开主界面」的悬浮按钮
            // 就长这样，而这个按钮默认干的也正是那件事。红色留给「正在录」。
            float stroke = radius * 0.14f;
            float ringRadius = radius * 0.72f;
            ringPaint.setStrokeWidth(stroke);
            progressPaint.setStrokeWidth(stroke);
            ringPaint.setColor(ContextCompat.getColor(getContext(),
                    live ? R.color.recording_track : R.color.text_tertiary));
            canvas.drawCircle(centerX, centerY, ringRadius, ringPaint);

            if (isRecording) {
                if (live) {
                    // 轨道上走过的那一段：从十二点开始顺时针
                    ringRect.set(centerX - ringRadius, centerY - ringRadius,
                            centerX + ringRadius, centerY + ringRadius);
                    canvas.drawArc(ringRect, -90f, 360f * progress, false, progressPaint);
                }

                // 停止方块：在录是红的；等摄像头时是灰的 —— 还是方块，按一下照样是停
                iconPaint.setColor(ContextCompat.getColor(getContext(),
                        live ? R.color.recording : R.color.text_tertiary));
                float rectSize = radius * 0.42f;
                float iconCornerRadius = rectSize * 0.2f;
                canvas.drawRoundRect(
                        centerX - rectSize / 2,
                        centerY - rectSize / 2,
                        centerX + rectSize / 2,
                        centerY + rectSize / 2,
                        iconCornerRadius,
                        iconCornerRadius,
                        iconPaint
                );
            } else {
                // 待机：中间那个点留着，但和外圈一样是灰的。
                //
                // 试过中间不画东西，只剩一个空圈 —— 太素，一眼认不出这是个按钮。
                // 红色仍然只在录制时出现：说明「正在录」的是颜色，不是有没有点。
                iconPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_tertiary));
                canvas.drawCircle(centerX, centerY, radius * 0.32f, iconPaint);
                iconPaint.setColor(ContextCompat.getColor(getContext(), R.color.recording));
            }
        }
    }
}
