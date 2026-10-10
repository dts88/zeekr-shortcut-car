package com.kooo.evcam.screen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.recording.RecordingCoordinator;
import com.kooo.evcam.recording.ScreenOffRecording;
import com.kooo.evcam.recovery.Recovery;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕亮着还是黑着 —— 进程里唯一的一份。
 *
 * <h3>实测</h3>
 *
 * <p>熄屏广播可靠（四次四次都到）；深睡醒来之后的亮屏广播一次都没到（2026-09-24）。所以「黑」由广播告诉我们，
 * 「亮」只能自己去看：黑着的时候每 {@link #WAKE_POLL_MS} 问一次系统，亮了就当收到了亮屏。
 * 深睡时这个计时是停住的，醒来两秒内必然问到。</p>
 *
 * <h3>为什么只有这一份</h3>
 *
 * <p>以前主界面、后视镜服务、熄屏录制各注册一份广播，各记一个「黑着」的标记，各自再去问系统纠正标记，
 * 前台服务每分钟再核对一次 —— 六处响应、三处轮询，同一件事四套规则（2026-09-27 审查）。
 * 现在所有人只听这里，顺序固定：</p>
 *
 * <ol>
 *   <li>熄屏录制的唤醒锁（{@link ScreenOffRecording}）；</li>
 *   <li>录像的规矩（{@link RecordingCoordinator#screenOff()} / {@link RecordingCoordinator#screenOn()}）：
 *       照 {@code ScreenOffPlan} 接着录、10 秒后停、或者现在停（哨兵模式没开、车没在走：车机几秒后就断电），
 *       亮屏再判接不接；</li>
 *   <li>相机不在这里管：谁要用就登记，熄屏时没人登记了就现在关（环视先单独关），由相机层按登记表判
 *       （MultiCameraManager.reconcileCameras）—— 现在停的那一次录像先放开，后视镜、主界面跟着注销，相机自然就关了，
 *       赶在断电之前；</li>
 *   <li>界面：因熄屏自己退下去的主界面，亮屏就接回来（{@link Recovery#bringBackUiAfterScreenOn}）；</li>
 *   <li>之后才是主界面、后视镜自己登记的监听者（退后台、摘/接后视镜）。</li>
 * </ol>
 */
public final class ScreenState {

    private static final String TAG = "ScreenState";
    /** 黑着的时候多久问一次系统「亮了没」。 */
    static final long WAKE_POLL_MS = 2_000L;

    /** 主线程上收到。 */
    public interface Listener {
        void onScreenOff();

        void onScreenOn();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static Context app;
    private static boolean installed;
    private static volatile boolean dark;
    /** 这一次是什么时候黑的（开机起算，含深睡）；0 = 亮着，或者进程起来时就黑着（不知道什么时候黑的）。 */
    private static volatile long darkAtMs;

    private ScreenState() {
    }

    /** 进程一起来就装上：广播只能动态注册，而主界面可能不在。 */
    public static synchronized void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        app = context.getApplicationContext();
        dark = !interactive(app);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenOff();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    screenOn("broadcast");
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
        if (dark) {
            MAIN.postDelayed(WAKE_POLL, WAKE_POLL_MS);
        }
        AppLog.i(TAG, "installed, dark=" + dark);
    }

    /** 记下来的状态：黑着 = 收到过熄屏、还没亮。要最新的用 {@link #refresh()}。 */
    public static boolean dark() {
        return dark;
    }

    /**
     * 熄屏多久了（毫秒，含深睡）；亮着、或者不知道什么时候黑的，是 -1。
     * 熄屏后按次序关的那几步在黑匣子里记成「熄屏后 +N ms」（{@code BlackBox.afterScreenOff}），任何线程都能问。
     */
    public static long darkForMs() {
        long at = darkAtMs;
        return dark && at > 0 ? SystemClock.elapsedRealtime() - at : -1;
    }

    /**
     * 问一次系统并纠正记下来的状态；变了就当收到了相应的广播。
     * 主界面回到前台、任何「此刻到底黑不黑」的判断，都用它。
     *
     * @return 此刻是不是黑着
     */
    public static boolean refresh() {
        Context context = app;
        if (context == null) {
            return dark;
        }
        boolean nowDark = !interactive(context);
        if (dark && !nowDark) {
            screenOn("noticed");
        } else if (!dark && nowDark) {
            screenOff();
        }
        return nowDark;
    }

    /** 主线程调。 */
    public static void addListener(Listener listener) {
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static final Runnable WAKE_POLL = new Runnable() {
        @Override
        public void run() {
            if (!dark) {
                return;
            }
            Context context = app;
            if (context != null && interactive(context)) {
                screenOn("noticed");
                return;
            }
            MAIN.postDelayed(this, WAKE_POLL_MS);
        }
    };

    private static void screenOff() {
        if (dark) {
            return;
        }
        darkAtMs = SystemClock.elapsedRealtime();
        dark = true;
        BlackBox.noteImportant("熄屏");
        ScreenOffRecording.onScreenOff(app);
        RecordingCoordinator.get(app).screenOff();
        for (Listener listener : new ArrayList<>(LISTENERS)) {
            listener.onScreenOff();
        }
        MAIN.removeCallbacks(WAKE_POLL);
        MAIN.postDelayed(WAKE_POLL, WAKE_POLL_MS);
    }

    private static void screenOn(String how) {
        if (!dark) {
            return;
        }
        dark = false;
        darkAtMs = 0;
        MAIN.removeCallbacks(WAKE_POLL);
        // 总原则（规格 §0）：停车熄屏是特殊情况；屏幕亮了，特殊情况就结束了，回到用户设定的状态
        BlackBox.noteImportant("亮屏（" + how + "）");
        ScreenOffRecording.onScreenOn();
        RecordingCoordinator.get(app).screenOn();
        Recovery.bringBackUiAfterScreenOn(app);
        // 亮屏 = 车机醒了、能正常运行：按开机自启动的规矩恢复该恢复的（以前挂在静态广播接收器上）
        Recovery.restore(app, "screen-on");
        for (Listener listener : new ArrayList<>(LISTENERS)) {
            listener.onScreenOn();
        }
    }

    private static boolean interactive(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null || power.isInteractive();
    }
}
