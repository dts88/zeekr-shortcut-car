package com.kooo.evcam.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.R;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.ChannelRules.PipelineReport;
import com.kooo.evcam.camera.ChannelRules.RecordPhase;
import com.kooo.evcam.camera.ChannelRules.RecordStop;
import com.kooo.evcam.recovery.Recovery;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.service.RecordingFloatingService;
import com.kooo.evcam.storage.AutoLock;
import com.kooo.evcam.storage.StorageState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 相机管家对外的唯一入口（项目所有者 2026-11-02「用于控制摄像头行为的模块太多了，应该收于一处」）：要相机做什么都向它请求，
 * 相机、录像此刻是什么样子都问它。判断只在它这里 —— 规矩在 {@link ChannelRules}，动相机的是通道调度（{@link ChannelScheduler}），
 * 照做的是相机层（{@link MultiCameraManager}、{@link SingleCamera}、录制器）。
 *
 * <p>2026-11-03 起：录像（3a：以前的录像协调器抹掉，用户要的那几样由这里照实际的需要处理）；画面要不要相机（主界面预览、超级镜子、
 * 盲区画面，{@link #need}）、拍照（{@link #takePhoto}）、前台服务开不开（{@link #foregroundServiceNeeded}）、退出的次序
 * （{@link #beginExit}）也都向这里请求（3b）。</p>
 *
 * <h3>录像</h3>
 *
 * <ul>
 *   <li><b>请求</b>：人按的开始 / 停止（主界面、悬浮按钮、灵动贴，{@link #startByUser}、{@link #stopByUser}、{@link #toggleByUser}）、
 *       退出（{@link #beginExit}）。别的都是这里自己看着事实判：亮屏（{@link #screenOn}）、应用被带回来（{@link #appRestored}）、
 *       主界面打开 / 回到前台（{@link #mainScreenOpened}、{@link #mainScreenShown}）时看要不要开 —— 「这一趟要录」立着就接着录，
 *       这一趟还没看过「启动自动录制」就看一次（{@link ChannelRules#autoStartMoment}）。</li>
 *   <li><b>能不能录</b>：至少选了一路、有 U 盘（存储快照）、写得下。U 盘不能用就等着，一能用就开（{@link ChannelRules#nextStartCheckMs}）；
 *       盘满了设了容量上限就删最旧的，没设就提示。</li>
 *   <li><b>什么时候录</b>：查过盘就开 —— 定下这次录哪几路、存在哪，不等环视，也不等相机开完。每一路的相机带着录像输出出了画面，
 *       那一路就加入；一路被别的程序拿走、断开、卡住不是停，那一路自己退出、回来自己加入（相机层的事）。</li>
 *   <li><b>在不在录</b>只有这里一个答案（{@link #isRecording}，阶段 {@link RecordPhase}）：主界面、悬浮按钮、前台通知、自动锁定都问它；
 *       此刻的样子（开录中 / 在录 / 等待摄像头）问 {@link #view}。</li>
 *   <li><b>停只有一条路</b>（{@link #end}）：人停的、熄屏停的、被打断、开录失败、录着的那一份管线没了 —— 收通知、悬浮按钮，
 *       相机层按同一套收拾，收拾完了才开下一次。停了接不接照 {@link ChannelRules#resumesAfter}（写不进、录制器出故障这一类收拾完、
 *       查过盘就接回，连着 {@link ChannelRules#RESUME_TRIES} 次都没录满一分钟就不再试）。</li>
 *   <li><b>录像要用相机</b>（登记 {@link CameraNeeds.Holder#RECORDING}）：在录，或者被这一类打断、正在接回时登记着 —— 相机不放、
 *       停放的录制器留在会话里，接回时直接加入；接回被拒（等 U 盘）、不接了就放。</li>
 *   <li><b>熄屏</b>：停不停、黑着能不能开，照相机管家的规矩（{@link ChannelRules#recordInDark}、{@link ChannelRules#startsInDark}）：
 *       通道算黑时判了停就停（相机先封好文件再关）；熄屏中哨兵模式变成开、通道留下了环视，「这一趟要录」立着就接着录（只录环视）。</li>
 *   <li><b>熄屏后的帧率</b>（项目所有者 2026-11-04）：环视留着只录像时按设置里选的帧率录，降下来从下一段起、亮屏或开车马上切一段恢复
 *       （{@link ChannelRules#darkFps}、{@link ChannelRules#darkFpsChange}）。开录时、通道的熄屏决定变了时这里定，相机层、录制器照做。</li>
 *   <li><b>这一趟</b>（{@link Trip}）：车机真正开机、人点开主界面起，到退出。「这一趟要录」落盘，进程被车机结束后又起来，照它接着录。</li>
 *   <li>后台开录而现在用不了相机（安卓 12 的限制）、屏幕亮着：把主界面拉起来（{@link Recovery#bringUpMainForRecording}）。</li>
 * </ul>
 *
 * <p>进程里只有一份（{@link #get}），主线程上用。画面上的反馈（按钮、计时器、提示）由 {@link Listener} 的实现方做。</p>
 */
public final class CameraKeeper {

    private static final String TAG = "CameraKeeper";

    /** 要录、却还没有相机管线时多久再看一眼。 */
    static final long POLL_MS = 2_000L;
    /**
     * 「U 盘写入跟不上」提示的限频：一次录像最多提示一次，两次提示之间至少隔这么久（被打断、自动接回算新的一次录像，也受它管）。
     * U 盘一直跟不上时一段接一段地满，每段都提示会变成一串关不掉的提示；黑匣子那边照样每段都有数。
     */
    static final long SLOW_WRITE_NOTICE_GAP_MS = 30 * 60 * 1000L;

    /**
     * 谁要录 —— 进日志。{@link #USER} 主界面的录制键，{@link #FLOATING} 悬浮按钮，{@link #BUTTON} 灵动贴（都是人按的）；
     * {@link #AUTO_START} 启动自动录制；{@link #RESUME} 存储一类打断后的自动接回（屏幕黑着时只有它不看黑着开不开）；
     * {@link #SCREEN_ON} 接着录「这一趟要录」（亮屏、应用被带回来、主界面回到前台）；{@link #SENTRY} 熄屏中哨兵模式变成开、
     * 通道留下了环视之后的接回（黑满 30 秒以后只有它开得起来）。
     */
    public enum Why {
        USER, FLOATING, AUTO_START, RESUME, SCREEN_ON, SENTRY, BUTTON
    }

    /** 录像此刻的样子（{@link #view}）：录制键、悬浮按钮、前台通知、黑匣子都照它。 */
    public enum View {
        /** 没在录（含上一次停录还在收拾）。 */
        IDLE,
        /** 在录，这一次还没有一路在录过，有一路在起来。 */
        PREPARING,
        /** 至少一路在录。 */
        RECORDING,
        /**
         * 在录，一路都没在录：都被别的程序占用、在救、现在用不了相机（主界面不在前、前台服务是后台起的），或者录制器建不起来。
         * 录像不算停 ——「等待摄像头，恢复后自动继续」，哪一路回来哪一路自己加入。
         */
        WAITING
    }

    /** 录像的结果告诉谁（主线程）。实现方负责画面上的反馈。 */
    public interface Listener {
        /** 开录了。各路带着录像输出出了画面才加入，落定时报 {@link #onStartSettled}。 */
        void onRecordingStarted();

        /**
         * 开录落定了（相机层说的，每次录像只报一次）：此刻在录的是哪几路、这次计划录哪几路。
         *
         * @param liveKeys 落定时在录的几路；空 = 一路都没在录（等待摄像头）
         * @param planned  这次计划录的几路
         */
        default void onStartSettled(Collection<String> liveKeys, Collection<String> planned) {
        }

        /** 录像的样子（{@link #view}）或者各路的摘要（{@link #summary}）可能变了：照它们重画。 */
        default void onRecordingViewChanged() {
        }

        /**
         * 停了。
         *
         * @param reason     为什么停
         * @param lastedMs   这一段录了多久
         * @param willResume 收拾完、查过盘会自动接回
         */
        void onRecordingStopped(RecordStop reason, long lastedMs, boolean willResume);

        /** 条件不满足，压根没开始。 */
        void onRecordingRefused(String reason);

        /** U 盘写入跟不上：录像照常在录，相机这一侧开始丢帧。已经限过频（{@link #SLOW_WRITE_NOTICE_GAP_MS}），收到就提示。 */
        default void onWriteSlow() {
        }
    }

    private static CameraKeeper instance;

    /** 整个进程共用的那一份。 */
    public static synchronized CameraKeeper get(Context context) {
        if (instance == null) {
            instance = new CameraKeeper(context);
        }
        return instance;
    }

    private final Context context;
    private final AppConfig appConfig;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Trip trip;

    /** 下一次开录用的那一份管线。 */
    private MultiCameraManager cameraManager;
    /** 正等着建好的那一份管线（{@link #currentManager}）：一份只挂一次「建好了再看」。 */
    private MultiCameraManager awaitedBuild;
    /** 这一次录像在哪一份管线上开的：停、收拾都找它，它报上来的才算数（管线换了也一样）。 */
    private MultiCameraManager attemptManager;

    /** 「在不在录」：{@link RecordPhase}。 */
    private RecordPhase phase = RecordPhase.IDLE;
    /** 进停录中的那一刻（elapsedRealtime）：收拾到 {@link ChannelRules#STOP_DEADLINE_MS} 不等了。 */
    private long stoppingSinceMs;
    /**
     * 在等开录：查盘、等 U 盘插上、等上一次停录收拾完、等相机管线建出来；null = 没在等。一直留到真正开录才清：
     * 查盘是异步的，这中间人停了、退出了，清掉它，查完回来就不开。
     */
    private Why pending;
    /** 自动接回这一阵试了几次（{@link ChannelRules#RESUME_TRIES}）：有一路录满一分钟、人开始录时清零。 */
    private int resumeTries;
    /** 等 U 盘从什么时候起（第一次因为盘不能用拒的那一刻，elapsedRealtime）；0 = 没在等 U 盘。 */
    private long diskWaitSince;
    private RecordStop lastStopReason;
    private long startedAtMs;

    /** 上一次推出去的样子（{@link #pushView}）：变了才换通知上的字、告诉悬浮按钮。 */
    private View shownView = View.IDLE;
    /** {@link #shownView} 从什么时候起是这个样子（elapsedRealtime）；0 = 还没推过。 */
    private long viewSinceMs;
    /** 这一次录像里有一路在录一共多久（{@link #account}）：自动接回的额度照它算录没录满一分钟。 */
    private long recordedMs;
    /** 这一次录像里一路都不在录（等待摄像头）一共多久：停录那一行写。 */
    private long waitedMs;
    /** 这一次录像的开录落定报过没有。 */
    private boolean settleReported;
    /** 这一阵有一路在录是从什么时候起的（elapsedRealtime）；0 = 眼下没有一路在录。 */
    private long liveSinceMs;
    /** 上一次提示「U 盘写入跟不上」的时刻（elapsedRealtime）；0 = 这个进程里还没提示过。 */
    private long slowWriteNoticedAtMs;
    /** 这一次录像里提示过没有。开录时清。 */
    private boolean slowWriteNoticedThisRecording;
    /** 这一次因熄屏停的录像亮屏接不接：停的那一刻照规矩定（{@link #stopForScreenOff}），{@link #settleWanted} 照它留不留「这一趟要录」。 */
    private boolean screenOffResumes;
    /**
     * 熄屏后的帧率：最后定下的那一个（fps；0 = 照视频流配置）。开录时照此刻的定一次、交给相机层（{@link #startWith}），
     * 通道对环视的熄屏决定变了时跟着换（{@link #followDarkFps}）。
     */
    private int darkFps;

    private CameraKeeper(Context context) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(this.context);
        this.trip = new Trip(new TripStore(this.context.getSharedPreferences(TripStore.FILE, Context.MODE_PRIVATE)));
        // 调度对熄屏录像、环视留不留的决定（调度在一轮当中发）：排到主线程上再做，做的时候读此刻的决定。只挂一次 —— 先装上调度
        // （应用一起来就装上了，装过了什么都不做）：没装上时挂的会丢
        ChannelFacts.install(this.context);
        ChannelScheduler.setSurroundVerdictListener(() -> main.post(this::onChannelVerdict));
    }

    // ================================================================= 请求

    /**
     * 人按了开始（主界面的录制键、悬浮按钮、灵动贴）：这一趟里不再自动开（{@link Trip#useAutoStart}），前面接回失败的额度清零，要录。
     * 主线程。
     */
    public void startByUser(Why who) {
        trip.useAutoStart();
        resumeTries = 0;
        AppLog.d(TAG, "人按了开始（" + who + "）");
        request(who);
    }

    /** 人按了停止：这一趟里没有任何一条路可以再自动开起来，「这一趟要录」也撤（{@link Trip#userStopped}）。主线程。 */
    public void stopByUser(Why who) {
        trip.userStopped();
        AppLog.d(TAG, "人按了停止（" + who + "），这一趟不再自动开始");
        stop(RecordStop.USER);
    }

    /**
     * 悬浮按钮、灵动贴在主界面不在前时按的：在录（开录中、在录、等待摄像头）就停，没在录就开。主线程。
     *
     * @return true：这一下是开始
     */
    public boolean toggleByUser(Why who) {
        if (view() != View.IDLE) {
            stopByUser(who);
            return false;
        }
        startByUser(who);
        return true;
    }

    /** 退出的那一下：这一趟到头（下次打开是新的一趟，启动自动录制重新生效），在录的停、在等开录的不等。主线程。 */
    private void endTripForExit() {
        newTrip("exit");
        stop(RecordStop.USER);
    }

    /**
     * 新的一趟（规格 1.2）：车机真正开机、人自己点开主界面。这一趟看没看过启动自动录制、人停没停过都清零；「这一趟要录」只在眼下没在录、
     * 没在等时撤 —— 之前要录的（被结束的进程留下的、等亮屏接的）不再接，正在录的这一次属于新的一趟。主线程。
     *
     * @param why 为什么（ASCII，进黑匣子）
     */
    public void newTrip(String why) {
        boolean keepWanted = isRecording() || pending != null;
        if (trip.reset(keepWanted)) {
            BlackBox.noteImportant("新的一趟（" + why + "）：撤掉「这一趟要录」");
        }
    }

    /** 亮屏了（ScreenState 在主线程调）：「这一趟要录」立着就接着录；黑着时开起来、用不了相机一直等待摄像头的，照开录时那条规矩拉主界面。 */
    public void screenOn() {
        reconsider(false, "screen-on");
        if (isRecording()) {
            bringUpMainIfCamerasUnusable(Why.SCREEN_ON);
        }
    }

    /** 应用被开机自启动带回来了（Recovery：进程起来、例行、车机醒了、亮屏、更新后；开机自启动开着才来）：照「这一趟」看要不要开。 */
    public void appRestored(String why) {
        reconsider(true, why);
    }

    /** 主界面打开了（它接上的管线建好了）：照「这一趟」看要不要开 —— 这一趟还没看过启动自动录制就看一次。 */
    public void mainScreenOpened() {
        reconsider(true, "main-screen-opened");
    }

    /** 主界面回到前台、登记了预览（能用相机了）：「这一趟要录」立着就接着录。 */
    public void mainScreenShown() {
        reconsider(false, "main-screen");
    }

    /**
     * 看一眼要不要开录 —— 一处判（亮屏、应用被带回来、主界面打开或回到前台都走这里）。屏幕不知道黑没黑的那一小段（进程刚起来、
     * 安卓刚亮在等车辆信号）排到知道了再判（设计 v3.1 C11）。
     *
     * <ol>
     *   <li>应用启动的那一下、屏幕亮着、这一趟还没看过启动自动录制：看一次（{@link ChannelRules#autoStartMoment}）—— 开关开着就要开。</li>
     *   <li>在录、在等开录：不用管。</li>
     *   <li>「这一趟要录」立着：接着录（屏幕黑着时照 {@link #darkStartNow}）。</li>
     *   <li>不然，上面那一下看到开关开着：开（{@link Why#AUTO_START}）。</li>
     * </ol>
     *
     * <p>用户退出了什么都不做。</p>
     *
     * @param appStarting 这一下是应用启动
     * @param where       谁叫的（ASCII，进黑匣子）
     */
    private void reconsider(boolean appStarting, String where) {
        ScreenState.whenKnown(() -> {
            if (UserExit.isExited(context)) {
                return;
            }
            boolean autoStart = false;
            if (ChannelRules.autoStartMoment(trip.autoStartUsed(), appStarting, ScreenState.dark())) {
                trip.useAutoStart();
                autoStart = appConfig.isAutoStartRecording();
                AppLog.d(TAG, "这一趟看启动自动录制（" + where + "）：" + (autoStart ? "开着，要录" : "关着，不录"));
            }
            if (isRecording() || pending != null) {
                return;
            }
            if (trip.wanted()) {
                resume(where, Why.SCREEN_ON);
            } else if (autoStart) {
                request(Why.AUTO_START);
            }
        });
    }

    // ================================================================= 画面要相机、拍照（3b）

    /** 要相机的画面（{@link #need}）：主界面预览、超级镜子、盲区画面（中控屏和 HUD 共用一份）。 */
    public enum Use {
        PREVIEW(CameraNeeds.Holder.PREVIEW),
        MIRROR(CameraNeeds.Holder.MIRROR),
        SIDE_VIEW(CameraNeeds.Holder.SIDE_VIEW);

        final CameraNeeds.Holder holder;

        Use(CameraNeeds.Holder holder) {
            this.holder = holder;
        }
    }

    /**
     * 这个画面要相机了（画布在、声明好了）：开不开、带不带它、什么时候关，调度照登记表判（{@link CameraNeeds}）。重复要无害。
     * 哪个线程都能调。
     */
    public void need(Use use) {
        CameraNeeds.current().claim(use.holder);
    }

    /** 这个画面不要相机了：没人要的那一路照「保持」的规矩关。没要过也无害。哪个线程都能调。 */
    public void release(Use use) {
        CameraNeeds.current().release(use.holder);
    }

    /** 这个画面此刻登记着要相机。 */
    public boolean needs(Use use) {
        return CameraNeeds.current().isHeld(use.holder);
    }

    /** 此刻谁登记着要相机（日志、诊断用，ASCII）。 */
    public String describeNeeds() {
        return CameraNeeds.current().describe();
    }

    /**
     * 拍一张（主界面的拍照键、悬浮按钮、灵动贴）：相机没开就先开（登记表的规矩），出了画面再拍，提示按真的存下了几路说。
     * 主界面在不在前台都一样。主线程。
     */
    public void takePhoto(MultiCameraManager.PhotoCallback callback) {
        CameraManagerHolder.getInstance().getOrInit(context).takePhoto(callback);
    }

    // ================================================================= 前台服务（3b）

    /**
     * 前台服务该不该开着 —— 只在这里判（规格 §3）：没退出，而且开机自启动开着、在录、或者「这一趟要录」（被打断等接回、因熄屏停下
     * 等亮屏接：接回时相机是从后台开的，后台拿着相机安卓要求有前台服务）。
     */
    public boolean foregroundServiceNeeded() {
        return ChannelRules.foregroundServiceNeeded(UserExit.isExited(context), appConfig.isAutoStartOnBoot(),
                isRecording(), trip.wanted());
    }

    /**
     * 照规矩起、停前台服务（录像开、停那几下）：要的，没起就起、起着就照录像此刻的样子换通知上的字（不为换字再 start 一次）；
     * 不要的，起着就停 —— 先停服务再放相机：调度看到没有前台服务，没人要的相机当场按次序关，不在后台拿着相机保持 30 秒。
     */
    private void syncForegroundService() {
        if (foregroundServiceNeeded()) {
            if (CameraForegroundService.isRunning()) {
                CameraForegroundService.showText(context, notificationTitle(), notificationContent());
            } else {
                CameraForegroundService.start(context);
            }
        } else {
            CameraForegroundService.stop(context);
        }
    }

    /**
     * 刷新前台服务（主界面回到前台、录制声音的开关或录音权限变了）：要的再 start 一次 —— 安卓 12 起后台才起的前台服务用不了相机，
     * 主界面在前时起过、刷新过的才能（只升不降，{@link CameraForegroundService#startedWhileVisible}）；起的时候也照此刻的设置
     * 带不带麦克风类型。不要的，起着就停。
     *
     * @param why 谁叫的（ASCII，日志）
     */
    public void refreshForegroundService(String why) {
        boolean needed = foregroundServiceNeeded();
        AppLog.d(TAG, "刷新前台服务（" + why + "）：" + (needed ? "要" : "不要"));
        if (needed) {
            CameraForegroundService.start(context);
        } else if (CameraForegroundService.isRunning()) {
            CameraForegroundService.stop(context);
        }
    }

    /** 保活（开机广播、例行叫醒、无障碍、ContentProvider）要前台服务在：该开着、又没起的才起。 */
    public void ensureForegroundService(String why) {
        if (foregroundServiceNeeded() && !CameraForegroundService.isRunning()) {
            AppLog.d(TAG, "前台服务没在，起来（" + why + "）");
            CameraForegroundService.start(context);
        }
    }

    /**
     * 主界面销毁了（相机管线属于应用，主界面销毁不放它）：只是重建（日夜模式、换语言）什么都不动；不然前台服务照规矩留不留
     * （{@link #foregroundServiceNeeded}）。
     */
    public void mainScreenGone(boolean changingConfigurations) {
        if (!changingConfigurations) {
            syncForegroundService();
        }
    }

    // ================================================================= 退出（3b）

    /**
     * 退出最多等多久：主线程收尾、停录收好文件、调度按次序关完每一路相机。到点没好也照样结束进程。环视先关、座舱后关，通常不到半秒；
     * 相机服务卡住时一次关实测 2–20 秒（2026-10-08），要留够：进程带着没关完的相机死掉，相机服务里就剩一个没人收的句柄，
     * 下一个进程开相机全部超时。数由调度的常量算出来（{@link ChannelRules#EXIT_WAIT_MAX_MS}，最坏约 105 秒，界面早就没了）。
     */
    private static final long EXIT_DEADLINE_MS = ChannelRules.EXIT_WAIT_MAX_MS;

    /** 退出的把手：主界面做完它自己那几件（关界面、存日志）就叫 {@link #uiDone}，看门的线程等它。 */
    public static final class Exit {
        private final java.util.concurrent.CountDownLatch uiDone = new java.util.concurrent.CountDownLatch(1);

        public void uiDone() {
            uiDone.countDown();
        }
    }

    /**
     * 用户退出（主界面长按退出那一下，主线程）：照退出的次序收 —— channel-logic §五：停录 → 登记表作废 → 调度按次序关完 → 再结束进程。
     *
     * <ol>
     *   <li>记下「用户退出了」：必须在停服务之前（前台服务的 onDestroy 会发保活广播，保活接收器收到就去重启它）；</li>
     *   <li>结束进程交给看门的线程（{@link #finishExit}）：等主界面收尾、等停录收好文件、等每一路相机关完，最多等
     *       {@link #EXIT_DEADLINE_MS}，到点照样结束。以前是主线程自己关相机、关完再结束 —— 相机服务一卡，进程就退不掉；</li>
     *   <li>告诉调度「退出」：从此只关，不开、不改输出、不救；登记表从此作废，直到退出的记号清掉（人又点开主界面、真正开机）；</li>
     *   <li>这一趟到头、在录的停掉、在等开录的不等（{@link #endTripForExit}）；前台服务停；超级镜子、盲区画面停（它们的画布死了，
     *       留着只是让系统以 START_STICKY 的名义再去重启它们）；唤醒锁放；</li>
     *   <li>放管线：停录收尾、这一份换下去，调度按次序关完相机、丢掉输出，再放录制器；停录收尾排在管线的收拾线程上，看门的线程等到它。</li>
     * </ol>
     *
     * <p>界面那几件（关界面、存日志、亮屏不再拉回来）由主界面自己做，做完叫 {@link Exit#uiDone}。</p>
     */
    public Exit beginExit() {
        BlackBox.noteImportant("==== 用户退出应用 ====");
        UserExit.markExited(context);
        Exit exit = new Exit();
        final long startedAt = SystemClock.elapsedRealtime();
        final java.util.concurrent.CountDownLatch filesClosed = new java.util.concurrent.CountDownLatch(1);
        new Thread(() -> finishExit(context, startedAt, exit.uiDone, filesClosed), "exit-watchdog").start();

        CameraManagerHolder holder = CameraManagerHolder.getInstance();
        MultiCameraManager pipeline = holder.getCameraManager();
        ChannelScheduler scheduler = ChannelScheduler.app();
        if (scheduler != null) {
            scheduler.exit();
        }
        endTripForExit();
        CameraForegroundService.stop(context);
        com.kooo.evcam.zeekr.RearViewMirrorService.stop(context);
        com.kooo.evcam.zeekr.SideViewService.stop(context);
        com.kooo.evcam.WakeUpHelper.releasePersistentWakeLock();
        holder.release();
        if (pipeline != null) {
            pipeline.afterStopsFinished(filesClosed::countDown);
        } else {
            filesClosed.countDown();
        }
        return exit;
    }

    /**
     * 退出的最后一步，在看门的线程上跑：等主界面收尾、等相机关完、等停录收好文件，然后结束进程。黑匣子里记一行结果：用了多久；
     * 到点没好的话，卡在哪。收尾的这段时间里人又点开了应用（退出的记号清掉了）：不结束进程 —— 新打开的主界面正接着用。
     */
    private static void finishExit(Context app, long startedAt, java.util.concurrent.CountDownLatch uiDone,
                                   java.util.concurrent.CountDownLatch filesClosed) {
        boolean mainDone = false;
        try {
            mainDone = uiDone.await(EXIT_DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 相机由调度按次序关（环视先、座舱后）：等它报关完，再看账本上还有没有一轮在关、没结束。只是看，不等谁的锁：
        // 每 200 ms 看一次，歇着不空转、不和要跑关相机那几轮的主线程抢
        List<String> stuck;
        while (true) {
            long left = EXIT_DEADLINE_MS - (SystemClock.elapsedRealtime() - startedAt);
            stuck = CameraRounds.process().closingIds();
            if (left <= 0 || (!ChannelScheduler.closingAnywhere() && stuck.isEmpty())) {
                break;
            }
            try {
                Thread.sleep(Math.min(200L, left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 停录收尾通常比关相机先做完，没做完就等到同一个期限
        boolean filesDone = false;
        try {
            long left = EXIT_DEADLINE_MS - (SystemClock.elapsedRealtime() - startedAt);
            filesDone = filesClosed.await(Math.max(left, 0L), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long took = SystemClock.elapsedRealtime() - startedAt;
        if (mainDone && stuck.isEmpty() && filesDone) {
            BlackBox.noteImportant("退出：收尾用了 " + took + "ms，相机都关好了");
        } else {
            BlackBox.noteImportant("退出：等满 " + EXIT_DEADLINE_MS
                    + "ms 强制结束。" + (mainDone ? "" : "主线程收尾没做完；")
                    + (filesDone ? "" : "停录还没收好文件；")
                    + (stuck.isEmpty() ? "" : "相机 " + stuck + " 还没关好"));
        }
        if (!UserExit.isExited(app)) {
            BlackBox.noteImportant("退出：收尾的时候应用又被打开了，不结束进程（新打开的主界面接着用）");
            return;
        }
        // 黑匣子在自己的线程上写：等它把上面几行写完再结束进程
        BlackBox.flush(1000);
        System.exit(0);
    }

    // ================================================================= 事实

    /**
     * 建了一份新的相机管线（CameraManagerHolder 建的那一刻）：交给这里接上录像的回调。录着的时候换了一份（配置变了、旧的那份被释放重建）：
     * 旧的那份上的录像到头了，按「录制器自己停了」走停录那条路，收拾完、查过盘在新的这一份上自动接回。
     */
    public void pipelineCreated(MultiCameraManager manager) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            setCameraManager(manager);
        } else {
            main.post(() -> setCameraManager(manager));
        }
    }

    private void setCameraManager(MultiCameraManager manager) {
        if (cameraManager == manager || manager == null) {
            return;
        }
        cameraManager = manager;
        wire(manager);
        if (isRecording() && attemptManager != null && attemptManager != manager) {
            BlackBox.noteImportant("录着的那一份相机管线换掉了：这一段到头，收拾完在新的管线上自动接回");
            end(RecordStop.UNKNOWN);
        }
    }

    /**
     * 管线的回调。只认这一次录像所在的那一份（{@link #attemptManager}）报上来的：换掉的、释放了的旧管线再报什么都不算。
     * 都转到主线程。
     */
    private void wire(MultiCameraManager manager) {
        manager.setStorageFullCallback(decision -> main.post(() -> {
            if (manager == attemptManager) {
                stop(decision.capless ? RecordStop.STORAGE_FULL : decision.lockedFull
                        ? RecordStop.STORAGE_LOCKED : RecordStop.STORAGE_CANNOT_FREE);
            }
        }));
        // 录制器判的、相机层认定是盘或编码器的事：写出过数据又断了是「写不进」，一个字节都没写出过是「没写进」。相机的问题只那一路退出
        manager.setWriteStallCallback((stalledMs, everWrote) -> main.post(() -> {
            if (manager == attemptManager) {
                stop(everWrote ? RecordStop.WRITE_STALLED : RecordStop.NO_DATA);
            }
        }));
        // 一路加入、退出，各路的样子变了：只把样子推出去
        manager.setLaneListener(() -> main.post(() -> {
            if (manager == attemptManager) {
                pushView();
            }
        }));
        // 写盘跟不上：不停录（录像照常，只是开始丢帧），提示一句，限频
        manager.setWriteBacklogCallback(cameraId -> main.post(() -> {
            if (manager == attemptManager) {
                onWriteBacklog(cameraId);
            }
        }));
        manager.setPipelineCallback(new MultiCameraManager.PipelineCallback() {
            @Override
            public void onPipelineStarted(Set<String> active, Set<String> failed) {
                main.post(() -> onPipelineReport(manager, PipelineReport.STARTED,
                        active + (failed.isEmpty() ? "" : " / failed " + failed)));
            }

            @Override
            public void onPipelineStartFailed(String why) {
                main.post(() -> onPipelineReport(manager, PipelineReport.START_FAILED, why));
            }

            @Override
            public void onPipelineStopped() {
                main.post(() -> onPipelineReport(manager, PipelineReport.STOPPED, null));
            }
        });
    }

    /** 管线报上来开录 / 停录走到了哪一步（主线程）。该怎么办照 {@link ChannelRules#onPipelineReport}。 */
    private void onPipelineReport(MultiCameraManager from, PipelineReport report, String detail) {
        if (from != attemptManager) {
            return;
        }
        switch (ChannelRules.onPipelineReport(phase, report)) {
            case NOTE:
                phase = RecordPhase.RECORDING;
                BlackBox.noteImportant("录制器已启动 " + detail);
                pushView();
                break;
            case END:
                BlackBox.noteImportant(report == PipelineReport.START_FAILED
                        ? "开录失败：" + detail : "录制管线自己停了（没人叫它停，被释放了）");
                end(ChannelRules.endReason(phase, report));
                break;
            case SETTLED:
                phase = RecordPhase.IDLE;
                settled("pipeline-stopped");
                break;
            default:
                break;
        }
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ================================================================= 状态

    /**
     * 在不在录 —— 进程里唯一的答案：从开录指令发出去起（开录中），到停为止（{@link RecordPhase#recording}）。
     * 一路都没在录（等待摄像头）也算在录；此刻是什么样子问 {@link #view}。
     */
    public boolean isRecording() {
        return phase.recording();
    }

    /** 这一段录像是什么时候开的（elapsedRealtime）；没在录是 0。悬浮按钮的时长从这里算（车机对时会把系统时钟往回拨）。 */
    public long startedAtElapsedMs() {
        return isRecording() ? startedAtMs : 0;
    }

    /**
     * 这一阵有一路在录是从什么时候起的（elapsedRealtime）；眼下没有一路在录是 0。录制键、悬浮按钮上「本段」的进度从这里量：
     * 一路都不在录之后第一路接回，它的文件从那一刻起，分段也从那一刻重新算。
     */
    public long liveSinceElapsedMs() {
        return isRecording() ? liveSinceMs : 0;
    }

    /** 在等开录：查盘、等 U 盘插上、等上一次停录收拾完、等相机管线建出来。 */
    public boolean isWaiting() {
        return pending != null;
    }

    /** 录制管线还有人要：在录、在等开录、或者上一次还在收拾。主界面销毁时据此决定留不留前台服务。 */
    public boolean needsPipeline() {
        return phase != RecordPhase.IDLE || pending != null;
    }

    /** 「这一趟要录」立着（{@link Trip#wanted}）：前台服务据此留着 —— 接回时相机是从后台开的。 */
    public boolean recordingWanted() {
        return trip.wanted();
    }

    public RecordStop lastStopReason() {
        return lastStopReason;
    }

    /** 诊断、恢复那一行用（ASCII）。 */
    public String describe() {
        return "phase=" + phase + " pending=" + pending + " " + trip.describe() + " resumeTries=" + resumeTries;
    }

    /**
     * 录像此刻的样子 —— 录制键、悬浮按钮、前台通知都照它画。没在录是 {@link View#IDLE}；在录时照相机层给的各路摘要：有一路在录是在录；
     * 这一次还没有一路录过、有一路在起来是开录中；一路都没在录、也没有一路在起来是等待摄像头。主线程。
     */
    public View view() {
        return viewOf(summary());
    }

    /** 这一次录像各路的摘要（计划录哪几路、在录的几路、落定没有、状态条该说哪一句）；没在录、没有管线时是 null。主线程。 */
    public RecordingLanes.Summary summary() {
        return isRecording() && attemptManager != null ? attemptManager.laneSummary() : null;
    }

    private View viewOf(RecordingLanes.Summary summary) {
        if (!isRecording()) {
            return View.IDLE;
        }
        if (summary == null || summary.planned.isEmpty()) {
            return phase == RecordPhase.RECORDING ? View.RECORDING : View.PREPARING;
        }
        switch (summary.view) {
            case RECORDING:
                return View.RECORDING;
            case WAITING:
                return View.WAITING;
            default:
                return View.PREPARING;
        }
    }

    /** 前台通知的标题照录像此刻的样子写：正在录制、等待摄像头；没在录是在后台运行。 */
    public String notificationTitle() {
        return titleFor(view());
    }

    /** 前台通知的正文：能做的动作（点击返回应用）。 */
    public String notificationContent() {
        return contentFor(view());
    }

    private String titleFor(View view) {
        return context.getString(view == View.WAITING ? R.string.notif_waiting_cameras_title
                : view == View.IDLE ? R.string.notif_background_title : R.string.notif_recording_title);
    }

    private String contentFor(View view) {
        return context.getString(view == View.IDLE ? R.string.notif_tap_to_return : R.string.notif_recording_tap);
    }

    /**
     * 把录像的样子推出去（主线程）：开录、停录、管线报上来、一路加入或退出、各路的样子变了都叫一次。样子变了：前台通知换字（服务在才换，
     * 在后台时绝不为换字 startForegroundService）、悬浮按钮重画；在录、等摄像头各自多久记下来（{@link #account}）。开录落定告诉监听者
     * 一次；监听者每次都叫（状态条上那一句跟着摘要变）。
     */
    private void pushView() {
        RecordingLanes.Summary summary = summary();
        View now = viewOf(summary);
        if (now != shownView) {
            long at = SystemClock.elapsedRealtime();
            account(at);
            AppLog.i(TAG, "录像的样子：" + shownView + " → " + now);
            liveSinceMs = now == View.RECORDING ? at : 0;
            shownView = now;
            CameraForegroundService.showText(context, titleFor(now), contentFor(now));
            RecordingFloatingService.sendRecordingStateChanged(context);
        }
        if (summary != null && summary.settled && !settleReported) {
            settleReported = true;
            for (Listener listener : new ArrayList<>(listeners)) {
                listener.onStartSettled(summary.liveKeys, summary.planned);
            }
        }
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onRecordingViewChanged();
        }
    }

    /** 眼下这个样子算到 {@code at} 为止：这一次录像里有一路在录一共多久、一路都不在录一共多久。 */
    private void account(long at) {
        long span = viewSinceMs > 0 ? at - viewSinceMs : 0;
        if (shownView == View.RECORDING) {
            recordedMs += span;
        } else if (shownView == View.WAITING) {
            waitedMs += span;
        }
        viewSinceMs = at;
    }

    /** 黑匣子里的几路：按开的次序写每一路的叫法（环视、后座舱、前座舱）。 */
    private static String roles(Collection<String> keys) {
        List<String> names = new ArrayList<>();
        for (String key : CameraSlots.openOrder(keys)) {
            names.add(MultiCameraManager.roleName(key));
        }
        return String.join("、", names);
    }

    /**
     * 这一次录像的计划里有环视 —— 熄屏那几条规矩和录制键上的小字同一个来源：在录时看这一次的计划；没在录、计划还没定时看配置里启用的
     * 那几路（开录时计划就从它们里定）。主线程。
     */
    public boolean surroundPlanned() {
        RecordingLanes.Summary s = summary();
        if (s != null && !s.planned.isEmpty()) {
            return s.planned.contains(CameraSlots.KEY_SURROUND);
        }
        return com.kooo.evcam.profile.RecordSpecs.enabledCameraKeys(context).contains(CameraSlots.KEY_SURROUND);
    }

    // ================================================================= 要录

    /**
     * 要录：查过盘就开，不等环视。已经在录、已经在等，都无害。主线程。
     *
     * <p>屏幕黑着时照 {@link #darkStartNow}（{@link ChannelRules#startsInDark}）：车机醒着、计划里有环视，黑了不到 30 秒、在开车时开；
     * 黑满 30 秒以后只有熄屏中哨兵模式变成开那一下（{@link Why#SENTRY}）、能用相机才开 —— 只录环视。别的只记下「这一趟要录」，亮屏接。
     * 例外是存储一类打断后的自动接回（{@link Why#RESUME}）：它接的是正在录的那一次，安卓熄屏着也接。黑着不开的那一下只在「这一趟要录」
     * 真的变了时记一行（恢复每 5 分钟例行来一次，不然一夜几十行）。</p>
     */
    private void request(Why why) {
        if (isRecording()) {
            return;
        }
        ChannelRules.DarkStart start = ScreenState.dark() && why != Why.RESUME ? darkStartNow(why) : null;
        if (start != null && start != ChannelRules.DarkStart.STARTS) {
            if (trip.want(true)) {
                BlackBox.noteImportant("要录像（" + why + "）：屏幕黑着（"
                        + (start == ChannelRules.DarkStart.SLEEPS ? "车机要睡"
                        : start == ChannelRules.DarkStart.NO_SURROUND ? "计划里没有环视"
                        : start == ChannelRules.DarkStart.SETTING_OFF ? "熄屏持续录制关着，熄屏后不录"
                        : start == ChannelRules.DarkStart.RELEASED ? "通道没留环视"
                        : start == ChannelRules.DarkStart.NO_SENTRY_RISE ? "通道留着环视，熄屏中只在哨兵模式变成开时开录"
                        : "现在用不了相机") + "），先不开录；记下「这一趟要录」，"
                        + (appConfig.isScreenOffKeepRecording() && surroundPlanned()
                        ? "亮屏、或熄屏中哨兵模式变成开（布防）后接着录" : "亮屏后接着录"));
            }
            return;
        }
        if (pending == null) {
            // 黑着开录录哪几路照通道眼里的熄屏写：黑了不到 30 秒时关着的座舱不开、开着的录到黑满 30 秒才放开
            BlackBox.noteImportant("要录像（" + why + "）：查过盘就开录，不等环视"
                    + (!ScreenState.dark() ? "" : MultiCameraManager.channelCountsDark() ? "（屏幕黑着：只录环视）"
                    : "（屏幕黑了不到 " + (ChannelRules.HOLD_MS / 1000)
                    + " 秒：座舱不开，开着的录到黑满 " + (ChannelRules.HOLD_MS / 1000) + " 秒）"));
        }
        pending = why;
        noteWanted(true, why.name());
        main.removeCallbacks(poll);
        main.post(poll);
    }

    /** 不等了：人停了、退出了、熄屏停了。被打断、正在接回时留着的相机一起放（{@link #releaseResumeClaim}）。 */
    private void cancelPending(String why) {
        if (pending != null) {
            AppLog.i(TAG, "不再等开录（" + why + "）");
            pending = null;
            main.removeCallbacks(poll);
        }
        diskWaitSince = 0;
        releaseResumeClaim(why);
    }

    /** 等开录的那一个等待：要录、上一次收拾完了、拒了之后过一会儿、还没有管线时过一会儿，都从这里再看一次。 */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (pending == null) {
                return;
            }
            if (isRecording()) {
                pending = null;
                return;
            }
            if (phase != RecordPhase.IDLE) {
                // 上一次还在收拾：收拾完了（settled，到期限也算）会立刻再来看
                return;
            }
            if (currentManager() == null) {
                main.postDelayed(this, POLL_MS);
                return;
            }
            start(pending);
        }
    };

    /**
     * 熄屏了（ScreenState 在主线程调，屏幕真黑了）：写一行此刻的样子、照现在的车和设置录像会怎样。停不停、什么时候停由
     * {@link #onChannelVerdict} 在通道算黑时照规矩判。
     *
     * @param androidOff 安卓也熄屏了（熄屏和车机要睡是同一下）
     */
    public void screenOff(boolean androidOff) {
        boolean keep = appConfig.isScreenOffKeepRecording();
        boolean auto = appConfig.isAutoStartRecording();
        boolean surround = surroundPlanned();
        ChannelRules.DarkOutlook outlook = ChannelScheduler.darkOutlook(keep, surround, auto);
        BlackBox.noteImportant("熄屏时" + (isRecording() ? "在录像"
                : pending == null ? "没在录像" : resuming() ? "在等自动接回" : "在等开录（" + pending + "）")
                + "：录像停不停由相机管家在通道算黑时定（黑满 " + (ChannelRules.HOLD_MS / 1000) + " 秒、或者车机要睡），照现在的车和设置是「"
                + (outlook == ChannelRules.DarkOutlook.CONTINUE ? "接着录环视"
                : "黑满 " + (ChannelRules.HOLD_MS / 1000) + " 秒停，亮屏后"
                + (outlook == ChannelRules.DarkOutlook.PAUSE ? "接着录" : "不接"))
                + "」（熄屏持续录制" + (keep ? "开" : "关") + "、启动自动录制" + (auto ? "开" : "关")
                + (surround ? "" : "、计划里没有环视") + "）"
                + (androidOff ? "；安卓也熄屏了" : "") + BlackBox.afterScreenOff());
    }

    /**
     * 判了熄屏停（{@link ChannelRules#recordInDark}）：在录的停、在等开录的不等、在等自动接回的不等；亮屏接不接照
     * {@link ChannelRules#resumesOnLit} 记下。相机由通道调度先封好文件再关（{@link ChannelRules#sealFirst}）。
     */
    private void stopForScreenOff(ChannelRules.RecordInDark why) {
        screenOffResumes = ChannelRules.resumesOnLit(appConfig.isScreenOffKeepRecording(),
                appConfig.isAutoStartRecording());
        BlackBox.noteImportant("录像：相机管家定了熄屏停（" + (why == ChannelRules.RecordInDark.SLEEPS ? "车机要睡"
                : why == ChannelRules.RecordInDark.SETTING_OFF ? "熄屏持续录制关着，黑满 " + (ChannelRules.HOLD_MS / 1000) + " 秒"
                : why == ChannelRules.RecordInDark.NO_SURROUND ? "计划里没有环视，黑满 " + (ChannelRules.HOLD_MS / 1000) + " 秒"
                : why == ChannelRules.RecordInDark.RELEASED ? "停着车、哨兵模式没开，环视放开" : why.name())
                + "）：" + (isRecording() ? "现在停录" : resuming() ? "不等自动接回了" : "不等开录了")
                + (screenOffResumes ? "；亮屏后接着录" : "；亮屏后不接") + BlackBox.afterScreenOff());
        stop(RecordStop.SCREEN_OFF);
    }

    /**
     * 这一次录像的计划里有环视、屏幕黑着时录像能不能（再）开起来，开不起来是因为什么（{@link ChannelScheduler#startsInDark}，
     * 规矩在 {@link ChannelRules#startsInDark}）。
     */
    private ChannelRules.DarkStart darkStartNow(Why why) {
        return ChannelScheduler.startsInDark(surroundPlanned(), why == Why.SENTRY, MultiCameraManager.cameraUsableNow());
    }

    /**
     * 调度对熄屏录像的决定变了（调度在一轮当中叫，排到主线程上做；做的时候读此刻的决定，多做一次无害）—— 照
     * {@link ChannelRules#onRecordVerdict}：黑着、在录（含等开录、等自动接回）而判了停 → 熄屏停；黑着、没在录，熄屏中哨兵模式变成开
     * 让通道留下了环视 →「这一趟要录」立着就接着录，只录环视。现在用不了相机（前台服务是后台起的）就不开、写一行，亮屏再接。
     * 之后熄屏后的帧率跟着换（{@link #followDarkFps}：停了的不切）。屏幕不知道黑没黑时排到知道了再做。
     */
    private void onChannelVerdict() {
        if (!ScreenState.known()) {
            ScreenState.whenKnown(this::onChannelVerdict);
            return;
        }
        ChannelRules.RecordInDark verdict = ChannelScheduler.recordInDark();
        switch (ChannelRules.onRecordVerdict(verdict, ChannelScheduler.surroundVerdictCause(), ScreenState.dark(),
                isRecording() || pending != null)) {
            case STOP:
                stopForScreenOff(verdict);
                break;
            case START:
                if (trip.wanted() && darkStartNow(Why.SENTRY) == ChannelRules.DarkStart.CAMERA_UNUSABLE) {
                    BlackBox.noteImportant("通道留下了环视（熄屏中哨兵模式变成开或布防），「这一趟要录」立着，但现在用不了相机"
                            + "（前台服务是后台起的）：不开录，亮屏后接着录" + BlackBox.afterScreenOff());
                } else {
                    resume("sentry", Why.SENTRY);
                }
                break;
            default:
                break;
        }
        followDarkFps();
    }

    /** 熄屏后的帧率此刻该是多少（{@link ChannelRules#darkFps}：通道对环视的熄屏决定，加上设置里选的那一档）。 */
    private int darkFpsNow() {
        return ChannelRules.darkFps(ChannelScheduler.surroundVerdict(), appConfig.getScreenOffFps());
    }

    /**
     * 通道对环视的熄屏决定变了（{@link #onChannelVerdict}，停录、开录那一步之后）：熄屏后的帧率要换，在录就交给相机层 ——
     * 降下来从下一段起、升回去马上切一段（{@link ChannelRules#darkFpsChange}）。刚判了停的（哨兵模式关了、车机要睡）已经不在录，
     * 不交、不切；没在录的开录时再定（{@link #startWith}）。
     */
    private void followDarkFps() {
        int target = darkFpsNow();
        ChannelRules.FpsChange change = ChannelRules.darkFpsChange(darkFps, target);
        if (change == ChannelRules.FpsChange.NONE) {
            return;
        }
        darkFps = target;
        if (!isRecording() || attemptManager == null || attemptManager.isReleased()) {
            return;
        }
        boolean cut = change == ChannelRules.FpsChange.CUT_NOW;
        attemptManager.setDarkFps(target, cut);
        BlackBox.noteImportant("录像：通道对环视的熄屏决定是 " + ChannelScheduler.surroundVerdict() + "，"
                + (cut ? "马上切一段，按" : "从下一段起按")
                + (target > 0 ? "「熄屏后的帧率」" + target + " fps（视频流配置更低的照低的）" : "视频流配置的帧率") + "录");
    }

    /**
     * 这一趟要录而眼下没在录、没在等：接着录。屏幕黑着时照 {@link #darkStartNow}，不接就什么都不做、也不写（恢复每 5 分钟例行来一次）。
     *
     * @param where 谁叫的（ASCII，进黑匣子）
     */
    private void resume(String where, Why why) {
        if (isRecording() || pending != null || !trip.wanted()) {
            return;
        }
        boolean dark = ScreenState.dark();
        if (dark && darkStartNow(why) != ChannelRules.DarkStart.STARTS) {
            return;
        }
        BlackBox.noteImportant("「这一趟要录」还在（" + where
                + (lastStopReason == null ? "，上一个进程留下的" : "，" + lastStopReason + " 停的") + "）：接着录"
                + (!dark ? "" : MultiCameraManager.channelCountsDark() ? "（屏幕黑着，只录环视）"
                : "（屏幕黑了不到 " + (ChannelRules.HOLD_MS / 1000)
                + " 秒，座舱不开，开着的录到黑满 " + (ChannelRules.HOLD_MS / 1000) + " 秒）"));
        request(why);
    }

    // ================================================================= 开

    /**
     * 开录用哪一份管线：进程里那一份（{@link CameraManagerHolder#getOrInit}）。还没有就让它建；还在建就当还没有，建好那一下马上再看
     * （不等 {@link #POLL_MS} 的下一轮）。换了一份就接上。
     */
    private MultiCameraManager currentManager() {
        CameraManagerHolder holder = CameraManagerHolder.getInstance();
        MultiCameraManager held = holder.getOrInit(context);
        if (held != cameraManager) {
            setCameraManager(held);
        }
        if (held.isBuilt()) {
            return held;
        }
        if (awaitedBuild != held) {
            awaitedBuild = held;
            holder.whenBuilt(held, built -> {
                awaitedBuild = null;
                if (pending != null) {
                    main.removeCallbacks(poll);
                    main.post(poll);
                }
            });
        }
        return null;
    }

    /** 真正去开：先查盘。查盘在存储线程上，查完回主线程接着开。 */
    private void start(Why why) {
        // 录哪几路 = 配置里启用了哪几路
        Set<String> cameras = com.kooo.evcam.profile.RecordSpecs.enabledCameraKeys(context);
        if (cameras.isEmpty()) {
            refused(why, context.getString(R.string.msg_keep_one_camera_refuse), false);
            return;
        }
        // 查盘是异步的：查完回来就接着开（或者拒了再等）。万一一直不回来，到点再看一次
        main.removeCallbacks(poll);
        main.postDelayed(poll, ChannelRules.START_RETRY_MS);
        StorageState.refresh(context, "start", snapshot -> startWith(snapshot, cameras, why, true));
    }

    /**
     * 查过盘了：开录。相机层定下这次录哪几路、建录制器、声明各路的录像输出；每一路带着录像输出出了画面就加入，落定了报上来。
     * 屏幕亮着、现在用不了相机时先把主界面拉起来（{@link #bringUpMainIfCamerasUnusable}）。
     *
     * @param checkStorage false：刚为这次开录清理过空间，不再检查一遍（否则估算的余量稍有变化就会清完又清）
     */
    private void startWith(StorageState.Snapshot storage, Set<String> cameras, Why why, boolean checkStorage) {
        if (pending == null) {
            // 查盘这段时间里人停了、退出了
            return;
        }
        MultiCameraManager manager = currentManager();
        if (phase != RecordPhase.IDLE || manager == null) {
            main.removeCallbacks(poll);
            main.postDelayed(poll, POLL_MS);
            return;
        }
        // 正常模式下不往内置存储录：行车记录是一直在写的，而车机闪存换不了
        if (!storage.available) {
            refused(why, context.getString(R.string.msg_refuse_no_external), true);
            return;
        }
        if (diskWaitSince != 0) {
            // 在等 U 盘的，等到了：等了多久写一行。只说盘能用了，开没开看后面「开录」那一行
            BlackBox.noteImportant("U 盘能用了：开录（" + why + "）等了 " + String.format(Locale.US, "%.1f",
                    (SystemClock.elapsedRealtime() - diskWaitSince) / 1000f) + " 秒（盘不能用时头 "
                    + (ChannelRules.DISK_FAST_FOR_MS / 60_000) + " 分钟每 " + (ChannelRules.DISK_FAST_EVERY_MS / 1000)
                    + " 秒查一次）；此刻挂着的盘：" + storage.mounts);
            diskWaitSince = 0;
        }
        if (checkStorage && !ensureRoomToStart(storage, cameras, why)) {
            return;
        }
        // 从这里起算「在录」（开录中）
        phase = RecordPhase.PREPARING;
        pending = null;
        main.removeCallbacks(poll);
        attemptManager = manager;
        startedAtMs = SystemClock.elapsedRealtime();
        lastStopReason = null;
        slowWriteNoticedThisRecording = false;
        settleReported = false;
        viewSinceMs = 0;
        recordedMs = 0;
        waitedMs = 0;
        if (why == Why.RESUME) {
            resumeTries++;
        }
        BlackBox.noteImportant("开录（" + why + (why == Why.RESUME ? "，第 " + resumeTries + " 次自动接回" : "")
                + "）：计划 " + roles(cameras) + "，写到 " + storage.videoDir
                + "；不等环视，每一路带着录像输出出了画面就加入");
        // 在录就登记「录像要用相机」：相机关着的那几路，调度开的时候就带着录像输出
        CameraNeeds.current().claim(CameraNeeds.Holder.RECORDING);
        // 在录就立着「这一趟要录」：录着的进程被结束了要接得回来
        noteWanted(true, why.name());
        // 熄屏后的帧率照此刻定好交给相机层，各路第一段就按它录（熄屏中哨兵模式变成开、接着录环视的那一种是 fps 大于 0）
        darkFps = darkFpsNow();
        manager.setDarkFps(darkFps, false);
        if (darkFps > 0) {
            BlackBox.noteImportant("录像：环视留着只录像，按「熄屏后的帧率」" + darkFps + " fps 录（视频流配置更低的照低的）");
        }
        if (!manager.startRecording(cameras, storage)) {
            BlackBox.noteImportant("开录失败：相机层没能开始（" + why + "）");
            end(RecordStop.START_FAILED);
            return;
        }
        AutoLock.get().recordingStarted(context, manager.relayTargetDir());
        AppLog.d(TAG, "开始录制 " + cameras.size() + " 路: " + cameras);
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onRecordingStarted();
        }
        pushView();
        // 前台服务：没有它，系统会在应用退到后台后把录制掐掉。已经在的不再 start 一次 —— 通知上的字由 pushView 换
        syncForegroundService();
        bringUpMainIfCamerasUnusable(why);
    }

    /**
     * 在录、屏幕亮着、现在用不了相机（主界面不在前、前台服务是后台起的 —— 安卓 12 起后台才起的前台服务用不了相机）：先把主界面拉起来，
     * 不然录像输出声明了、相机却开不起来，一直「等待摄像头」。开录那一下、亮屏那一下判。屏幕黑着一律不拉（项目所有者 2026-10-13）。
     */
    private void bringUpMainIfCamerasUnusable(Why why) {
        if (!ScreenState.dark() && !MultiCameraManager.cameraUsableNow()) {
            Recovery.bringUpMainForRecording(context, why.name());
        }
    }

    /** 空间够就返回 true。不够时：没设上限直接拒绝（不删录像）；设了上限就在后台清理，清完再开一次，这次返回 false。 */
    private boolean ensureRoomToStart(StorageState.Snapshot storage, Set<String> cameras, Why why) {
        java.io.File dir = storage.videoDir;
        long free = storage.freeBytes;
        if (dir == null || free < 0 || free >= StorageGuard.lastMarginBytes()) {
            return true;
        }
        if (appConfig.getVideoStorageLimitGb() <= 0) {
            refused(why, context.getString(R.string.msg_storage_full_refuse), false);
            return false;
        }
        AppLog.i(TAG, "开录前空间不够，先清理最旧的录像");
        main.removeCallbacks(poll);
        main.postDelayed(poll, ChannelRules.START_RETRY_MS);
        // 「正在删除最旧的录像」等真要删时才弹：一个都删不掉时不该先说在删
        StorageGuard.enforceAsync(context, dir,
                () -> notifyRefused(context.getString(R.string.msg_storage_cleaning)), decision -> {
            if (pending == null) {
                return;
            }
            if (decision.verdict == StoragePlan.Verdict.FULL) {
                refused(why, context.getString(decision.lockedFull
                        ? R.string.msg_storage_locked_refuse : R.string.msg_storage_cannot_free), false);
            } else {
                startWith(storage, cameras, why, false);
            }
        });
        return false;
    }

    /**
     * 条件不满足：告诉界面，然后留在「要录」里，过一会儿再看（U 盘插上、盘清出来了都算）。U 盘不能用拒的：盘一能用就开 ——
     * 头 2 分钟每 2 秒查一次、同一个原因不再说一遍（{@link ChannelRules#nextStartCheckMs}）。被打断、正在接回时被拒的：之后是等，
     * 等的不登记，留着的相机放掉。
     */
    private void refused(Why why, String reason, boolean noDisk) {
        long now = SystemClock.elapsedRealtime();
        boolean repeat = noDisk && ChannelRules.inFastDiskWait(diskWaitSince, now);
        if (noDisk && diskWaitSince == 0) {
            diskWaitSince = now;
        }
        if (!repeat) {
            notifyRefused(reason);
        }
        pending = why;
        main.removeCallbacks(poll);
        main.postDelayed(poll, noDisk ? ChannelRules.nextStartCheckMs(diskWaitSince, now) : ChannelRules.START_RETRY_MS);
        releaseResumeClaim("refused");
    }

    // ================================================================= 停

    /**
     * 停。停的说清原因；接不接、什么时候接，这里判。人停的、熄屏停的：不再等（熄屏停的等亮屏、或者熄屏中哨兵模式变成开）。
     * 写不进、没写进、开录失败、录制器自己停了：收拾完、查过盘就自动接回，有额度。盘满：接回去也录不下，不接。
     */
    private void stop(RecordStop reason) {
        boolean wasRecording = isRecording();
        if (!wasRecording && pending == null) {
            return;
        }
        if (reason == RecordStop.USER || reason == RecordStop.SCREEN_OFF) {
            if (!wasRecording) {
                // 只是在等开录：「这一趟要录」留不留先定，前台服务跟着收，和停录同一个次序（见 end）
                lastStopReason = reason;
                settleWanted(reason);
                syncForegroundService();
                cancelPending(reason.name());
                return;
            }
            cancelPending(reason.name());
        }
        if (wasRecording) {
            end(reason);
        }
    }

    /**
     * 这一次录像到头了 —— 唯一的停录路径。不问相机层在不在录：这里说在录就收，相机层按同一套收拾（开录走到哪一步都一样），
     * 收拾完了报上来（{@link #settled}）才开下一次。判了自动接回的：「录像要用相机」留着 —— 相机不放、停放的录制器留在会话里，
     * 接回开录时直接加入；不接回的当场放。
     */
    private void end(RecordStop reason) {
        long now = SystemClock.elapsedRealtime();
        if (!isRecording()) {
            return;
        }
        phase = RecordPhase.STOPPING;
        stoppingSinceMs = now;
        // 录着的那一份管线已经放了（换了一份）：放的时候它自己停过录，收拾完照样报上来，不再叫它停第二次
        if (attemptManager != null && !attemptManager.isReleased()) {
            attemptManager.stopRecording();
        }
        main.removeCallbacks(stopDeadline);
        main.postDelayed(stopDeadline, ChannelRules.STOP_DEADLINE_MS);

        long lasted = startedAtMs > 0 ? now - startedAtMs : 0;
        account(now);
        startedAtMs = 0;
        lastStopReason = reason;
        // 录满一分钟说的是有一路在录满一分钟：一路都不在录（等待摄像头）、开录中的那几段不算
        if (recordedMs >= ChannelRules.RESUME_COUNTS_MS) {
            resumeTries = 0;
        }
        BlackBox.noteImportant("录像停止原因: " + reason + "，这一段录了 " + (lasted / 1000) + " 秒（有一路在录 "
                + recordedMs / 1000 + " 秒" + (waitedMs > 0 ? "，一路都不在录 " + waitedMs / 1000 + " 秒" : "") + "）"
                + BlackBox.afterScreenOff());
        AppLog.d(TAG, "录制已停止（" + reason + "）");

        boolean willResume = resumeAfter(reason);
        if (!willResume && pending == null) {
            settleWanted(reason);
        }
        // 停录的次序：「这一趟要录」留不留 → 前台服务 → 放开相机。等着接回时前台服务留着（亮屏后从后台开相机要有它）；
        // 不接了、开机自启动又关着的先停服务再放相机：调度看到没有前台服务，没人要的相机当场按次序关
        syncForegroundService();
        if (!willResume) {
            CameraNeeds.current().release(CameraNeeds.Holder.RECORDING);
        }
        AutoLock.get().recordingStopped();
        pushView();
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onRecordingStopped(reason, lasted, willResume);
        }
    }

    /** 停录中，管线收拾了太久还没报完：不等了，免得再也开不起来。 */
    private final Runnable stopDeadline = () -> {
        if (phase == RecordPhase.STOPPING
                && SystemClock.elapsedRealtime() - stoppingSinceMs >= ChannelRules.STOP_DEADLINE_MS) {
            phase = RecordPhase.IDLE;
            BlackBox.noteImportant("停录收拾了 " + (ChannelRules.STOP_DEADLINE_MS / 1000) + " 秒还没报完，不等了");
            settled("deadline");
        }
    };

    /** 上一次收拾完了（停录中 → 空闲）：等着开录的现在就去看。 */
    private void settled(String why) {
        main.removeCallbacks(stopDeadline);
        attemptManager = null;
        AppLog.d(TAG, "停录收拾完（" + why + "）");
        if (pending != null) {
            main.removeCallbacks(poll);
            main.post(poll);
        }
    }

    /**
     * 录像因为这个原因停了，要不要自动接回（{@link ChannelRules#resumesAfter}）。用户开着录像，App 就该一直录着 —— 只要不是人停的。
     * 每一次自动接回都计入额度：连着 {@link ChannelRules#RESUME_TRIES} 次都没有一路录满一分钟就不再试。
     */
    private boolean resumeAfter(RecordStop reason) {
        if (!ChannelRules.resumesAfter(reason)) {
            return false;
        }
        if (!ChannelRules.resumeAllowed(resumeTries)) {
            BlackBox.noteImportant("录像被打断（" + reason + "），自动恢复已连续 "
                    + resumeTries + " 次没录满一分钟，不再尝试");
            return false;
        }
        BlackBox.noteImportant("录像被打断（" + reason + "）：收拾完、查过盘就自动接回（已试 "
                + resumeTries + " 次）；相机留着等接回");
        request(Why.RESUME);
        return true;
    }

    /** 被打断、正在接回（没在录，「录像要用相机」还登记着）。 */
    private boolean resuming() {
        return !isRecording() && CameraNeeds.current().isHeld(CameraNeeds.Holder.RECORDING);
    }

    /**
     * 不接了（人停了、退出了、熄屏停了、接回查过盘被拒了）：被打断时为接回留着的相机放掉。没留着什么都不做。
     *
     * @param why 为什么（ASCII，进黑匣子）
     */
    private void releaseResumeClaim(String why) {
        if (!resuming()) {
            return;
        }
        BlackBox.noteImportant("不等自动接回了（" + why + "）：留着的相机放开");
        CameraNeeds.current().release(CameraNeeds.Holder.RECORDING);
    }

    /** 停了、也不等了：「这一趟要录」留不留 —— 因熄屏停下、亮屏要接的留着（进程被车机结束了也接）；别的停都撤。 */
    private void settleWanted(RecordStop reason) {
        noteWanted(reason == RecordStop.SCREEN_OFF && screenOffResumes, reason.name());
    }

    /** 立、撤「这一趟要录」（落盘）：变了才记黑匣子一行。 */
    private void noteWanted(boolean wanted, String why) {
        if (trip.want(wanted)) {
            BlackBox.noteImportant(wanted ? "记下「这一趟要录」（" + why + "）：进程被结束了，屏幕亮着时也接着录"
                    : "撤掉「这一趟要录」（" + why + "）");
        }
    }

    /**
     * 录制器报写盘跟不上（主线程）：某一路的写入排队满了，相机这一侧开始丢帧。不停录；提示一句，一次录像最多一次，
     * 两次至少隔 {@link #SLOW_WRITE_NOTICE_GAP_MS}。
     */
    private void onWriteBacklog(String cameraId) {
        if (!isRecording()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (slowWriteNoticedThisRecording
                || (slowWriteNoticedAtMs != 0 && now - slowWriteNoticedAtMs < SLOW_WRITE_NOTICE_GAP_MS)) {
            return;
        }
        slowWriteNoticedThisRecording = true;
        slowWriteNoticedAtMs = now;
        BlackBox.noteImportant("提示用户：U 盘写入跟不上（相机 " + cameraId + " 的写入排队满了，开始丢帧）");
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onWriteSlow();
        }
    }

    private void notifyRefused(String reason) {
        AppLog.w(TAG, "不满足录制条件：" + reason);
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onRecordingRefused(reason);
        }
    }

    // ================================================================= 这一趟

    /**
     * 这一趟（规格 1.2：车机真正开机、人自己点开主界面起，到退出）的两样记录，落盘 —— 进程被车机结束又被拉起来时还在：
     *
     * <ul>
     *   <li>{@link #autoStartUsed}：这一趟看过「启动自动录制」、人开过或者停过 —— 启动自动录制每一趟最多开一次，人停过的不再开；</li>
     *   <li>{@link #wanted}「这一趟要录」：在录、在等开录、因熄屏停下等亮屏接时立着；人停了、新的一趟撤。新的进程在屏幕亮着时照它接着录。</li>
     * </ul>
     *
     * <p>以前记了四样（还有「人停过」「录起来过」）：「人停过」只用来不再自动开，并进前一样；「录起来过」只在被打断时问，那时一定录起来过。
     * 纯 Java，见 {@code CameraKeeperTripTest}。</p>
     */
    public static final class Trip {

        /**
         * 怎么存。
         *
         * <p>{@code durable} 为 true 时写到盘上才返回（commit）：只给「这一趟要录」变了、人按了停止这两种 —— 哨兵模式没开时车机下电
         * （安卓熄屏）3–5 秒后就把进程结束，那一刻改的「这一趟要录」得赶在那之前落盘；别的排队异步写（apply）。</p>
         */
        public interface Store {
            boolean autoStartUsed();

            boolean wanted();

            void put(boolean autoStartUsed, boolean wanted, boolean durable);
        }

        private final Store store;
        private boolean autoStartUsed;
        private boolean wanted;

        public Trip(Store store) {
            this.store = store;
            if (store != null) {
                autoStartUsed = store.autoStartUsed();
                wanted = store.wanted();
            }
        }

        public boolean autoStartUsed() {
            return autoStartUsed;
        }

        public boolean wanted() {
            return wanted;
        }

        /** 这一趟看过启动自动录制、人开过：不再自动开。 */
        public void useAutoStart() {
            if (!autoStartUsed) {
                autoStartUsed = true;
                persist(false);
            }
        }

        /** 人按了停止：不再自动开，「这一趟要录」也撤。写到盘上才返回。 */
        public void userStopped() {
            if (autoStartUsed && !wanted) {
                return;
            }
            autoStartUsed = true;
            wanted = false;
            persist(true);
        }

        /**
         * 立、撤「这一趟要录」。
         *
         * @return 变了没有：变了才落盘（写到盘上才返回），调用方据此记黑匣子
         */
        public boolean want(boolean value) {
            if (wanted == value) {
                return false;
            }
            wanted = value;
            persist(true);
            return true;
        }

        /**
         * 新的一趟：看没看过、人停没停过清零；「这一趟要录」除非 {@code keepWanted}（正在录的这一次属于新的一趟）也撤。
         *
         * @return 撤掉了一个立着的「这一趟要录」
         */
        public boolean reset(boolean keepWanted) {
            boolean dropWanted = wanted && !keepWanted;
            if (!autoStartUsed && !dropWanted) {
                return false;
            }
            autoStartUsed = false;
            if (dropWanted) {
                wanted = false;
            }
            persist(dropWanted);
            return dropWanted;
        }

        public String describe() {
            return "autoStartUsed=" + autoStartUsed + " wanted=" + wanted;
        }

        private void persist(boolean durable) {
            if (store != null) {
                store.put(autoStartUsed, wanted, durable);
            }
        }
    }

    /**
     * 「这一趟」存在哪：沿用以前的文件（{@code recording_intent}）。旧版本存的是四样（autoStarted、stoppedByUser、everStarted、
     * recordingWanted），第一次读时照旧的并成两样，写的时候把旧的几样删掉。
     */
    private static final class TripStore implements Trip.Store {
        static final String FILE = "recording_intent";
        private static final String USED = "autoStartUsed";
        private static final String WANTED = "recordingWanted";

        private final SharedPreferences prefs;

        TripStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public boolean autoStartUsed() {
            if (prefs.contains(USED)) {
                return prefs.getBoolean(USED, false);
            }
            return prefs.getBoolean("autoStarted", false) || prefs.getBoolean("stoppedByUser", false);
        }

        @Override
        public boolean wanted() {
            return prefs.getBoolean(WANTED, false);
        }

        @Override
        public void put(boolean autoStartUsed, boolean wanted, boolean durable) {
            SharedPreferences.Editor editor = prefs.edit()
                    .putBoolean(USED, autoStartUsed)
                    .putBoolean(WANTED, wanted)
                    .remove("autoStarted")
                    .remove("stoppedByUser")
                    .remove("everStarted");
            if (!durable) {
                editor.apply();
            } else if (!editor.commit()) {
                AppLog.w(TAG, "trip not saved: autoStartUsed=" + autoStartUsed + " wanted=" + wanted);
            }
        }
    }
}
