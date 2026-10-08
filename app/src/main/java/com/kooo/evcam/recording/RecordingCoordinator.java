package com.kooo.evcam.recording;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.CameraNeeds;
import com.kooo.evcam.camera.CameraTaken;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.service.RecordingFloatingService;
import com.kooo.evcam.storage.StorageState;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 录像的唯一入口：要不要录、能不能录、什么时候录、为什么停、停了接不接。
 *
 * <h3>规则（项目所有者 2026-09-27：一个框架，不到处建小房子）</h3>
 *
 * <ul>
 *   <li><b>要不要录</b>由 {@link RecordingIntent}（这一趟开过没有、人停过没有）和开关决定，
 *       决定的人只需要调 {@link #request}；</li>
 *   <li><b>能不能录</b>只看这里：至少选了一路、有 U 盘（存储快照）、写得下；</li>
 *   <li><b>什么时候录</b>：环视出画面就录（规格 §2.2）。这里只有<b>一个</b>等待 —— 每 {@link #POLL_MS}
 *       看一眼环视，出画面就开。以前主界面里有七处各自「等两秒再开」的梯子，都归到这一个；</li>
 *   <li><b>为什么停</b>：停的人把原因交给 {@link #stop}；停了之后接不接（{@link RecordingStops#resumesOnSurround}）、
 *       还剩几次额度（{@link RecordingStops.ResumeBudget}），都在这里判；</li>
 *   <li><b>在不在录</b>只有这里说了算（{@link RecordingLifecycle}：空闲 → 开录中 → 在录 → 停录中）。
 *       熄屏录制、恢复、前台服务心跳、主界面、悬浮按钮都问 {@link #isRecording}，不再问相机层（2026-10-05）；</li>
 *   <li><b>停只有一条路</b>（{@link #end}）：人停的、被打断、开录失败、录着的那一份管线没了，
 *       都在这里收通知、悬浮按钮、唤醒锁，让相机层按同一套收拾（{@link MultiCameraManager#stopRecording()}），
 *       收拾完了才开下一次；</li>
 *   <li>主界面、悬浮按钮、开机自启动、亮屏、被打断 —— 都是同一个入口，同一套答案。</li>
 * </ul>
 *
 * <p>进程里只有一份（{@link #get}）：录像不属于某个窗口，主界面不在时悬浮按钮照样走这里。
 * 画面反馈（按钮、计时器、Toast）由 {@link Listener} 的实现方做，这里不碰界面。</p>
 */
public final class RecordingCoordinator {

    private static final String TAG = "RecordingCoordinator";

    /** 等环视时多久看一眼。 */
    static final long POLL_MS = 2_000L;
    /** 条件不满足（没 U 盘、盘满）时多久再试：不必两秒一次地去探盘。 */
    static final long REFUSED_RETRY_MS = 30_000L;

    /** 谁要录 —— 只进日志。 */
    public enum Why {
        USER, FLOATING, AUTO_START, RESUME, SCREEN_ON
    }

    /** 决策的结果告诉谁（主线程）。实现方负责画面上的反馈。 */
    public interface Listener {
        /**
         * 录起来了。
         *
         * @param cameras    实际参与录制的几路
         * @param sdFellBack 用户选了 U 盘但没插，这次落到了内置存储
         */
        void onRecordingStarted(Set<String> cameras, boolean sdFellBack);

        /**
         * 停了。
         *
         * @param reason     为什么停
         * @param lastedMs   这一段录了多久
         * @param willResume 这里会等环视恢复再自动接回
         */
        void onRecordingStopped(RecordingStops.Reason reason, long lastedMs, boolean willResume);

        /** 条件不满足，压根没开始。 */
        void onRecordingRefused(String reason);

        /**
         * U 盘写入跟不上：录像照常在录，但写入排队满了，相机这一侧开始丢帧。
         * 已经限过频（{@link RecordingCoordinator#SLOW_WRITE_NOTICE_GAP_MS}），收到就提示。
         */
        default void onWriteSlow() {
        }
    }

    private static RecordingCoordinator instance;

    /** 整个进程共用的那一份。 */
    public static synchronized RecordingCoordinator get(Context context) {
        if (instance == null) {
            instance = new RecordingCoordinator(context);
        }
        return instance;
    }

    private final Context context;
    private final AppConfig appConfig;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final RecordingStops.ResumeBudget budget = new RecordingStops.ResumeBudget();
    /** 下一次开录用的那一份管线。 */
    private MultiCameraManager cameraManager;
    /** 这一次录像在哪一份管线上开的：停、收拾都找它，它报上来的才算数（管线换了也一样）。 */
    private MultiCameraManager attemptManager;
    private final RecordingLifecycle lifecycle = new RecordingLifecycle();

    /**
     * 正在等环视出画面、或者已经决定开录在查盘；null = 没在等。
     * 一直留到真正开录（{@link RecordingLifecycle#begin}）才清：查盘是异步的，这中间人停了、退出了，
     * 清掉它，查完回来就不开。
     */
    private Why pending;
    /** 这次接回计不计入额度（相机被别的程序拿走的那种不计）。 */
    private boolean pendingCounts;
    private long pendingSinceMs;
    private RecordingStops.Reason lastStopReason;
    private long startedAtMs;

    /**
     * 「U 盘写入跟不上」提示的限频：一次录像最多提示一次，两次提示之间至少隔这么久
     * （录像被打断、自动接回算新的一次录像，也受这个间隔管）。U 盘一直跟不上时一段接一段地满，
     * 每段都提示会变成一串关不掉的 Toast；黑匣子那边照样每段都有数。
     */
    static final long SLOW_WRITE_NOTICE_GAP_MS = 30 * 60 * 1000L;
    /** 上一次提示「U 盘写入跟不上」的时刻（elapsedRealtime）；0 = 这个进程里还没提示过。 */
    private long slowWriteNoticedAtMs;
    /** 这一次录像里提示过没有。开录时清。 */
    private boolean slowWriteNoticedThisRecording;

    /** 熄屏持续录制关着时，熄屏后多久停录 —— 唯一的一个缓冲（项目所有者 2026-09-27）。 */
    static final long SCREEN_OFF_STOP_MS = 10_000L;
    private Runnable screenOffStop;
    /** 熄屏那一刻在录（含等接回）：熄屏时刻（含深睡 / 不含深睡）和中途停过几次，亮屏时汇总一行。 */
    private long darkSinceElapsedMs;
    private long darkSinceUptimeMs;
    private int stopsWhileDark;

    private RecordingCoordinator(Context context) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(this.context);
    }

    // ================================================================= 接线

    /**
     * 相机管理器换了对象就要重新交一次：它换了而这里还握着旧的，表现是「按了录制，什么都没发生」。
     * 录制管线的几个回调（写不进、相机被拿走、盘满、开录 / 停录走到哪一步）都接到这里，主界面在不在都一样。
     *
     * <p>录着的时候换了一份（车型变了、路数对不上，旧的那份被释放重建）：旧的那份上的录像到头了，
     * 按「录制器自己停了」走停录那条路，环视好了在新的这一份上接回。以前这里不知道，
     * 一直以为还在录。</p>
     */
    public void setCameraManager(MultiCameraManager manager) {
        if (cameraManager == manager) {
            return;
        }
        cameraManager = manager;
        if (manager == null) {
            return;
        }
        wire(manager);
        if (lifecycle.isRecording() && attemptManager != null && attemptManager != manager) {
            BlackBox.noteImportant("录着的那一份相机管线换掉了：这一段到头，等环视恢复后在新的管线上接回");
            end(RecordingStops.Reason.UNKNOWN);
        }
    }

    /**
     * 管线的回调。只认这一次录像所在的那一份（{@link #attemptManager}）报上来的：
     * 换掉的、释放了的旧管线再报什么都不算。都转到主线程。
     */
    private void wire(MultiCameraManager manager) {
        manager.setStorageFullCallback(decision -> main.post(() -> {
            if (manager == attemptManager) {
                stop(decision.capless ? RecordingStops.Reason.STORAGE_FULL : decision.lockedFull
                        ? RecordingStops.Reason.STORAGE_LOCKED : RecordingStops.Reason.STORAGE_CANNOT_FREE);
            }
        }));
        // 录制器判的：写出过数据又断了是「写不进」，一个字节都没写出过是「没收到画面」
        manager.setWriteStallCallback((stalledMs, everWrote) -> main.post(() -> {
            if (manager == attemptManager) {
                stop(everWrote ? RecordingStops.Reason.WRITE_STALLED : RecordingStops.Reason.NO_DATA);
            }
        }));
        manager.setCameraLostCallback(cameraId -> main.post(() -> {
            if (manager == attemptManager) {
                stop(RecordingStops.Reason.CAMERA_LOST);
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
                main.post(() -> onPipelineReport(manager, RecordingLifecycle.Report.STARTED,
                        active + (failed.isEmpty() ? "" : " / failed " + failed)));
            }

            @Override
            public void onPipelineStartFailed(String why) {
                main.post(() -> onPipelineReport(manager, RecordingLifecycle.Report.START_FAILED, why));
            }

            @Override
            public void onPipelineStopped() {
                main.post(() -> onPipelineReport(manager, RecordingLifecycle.Report.STOPPED, null));
            }
        });
    }

    /** 管线报上来开录 / 停录走到了哪一步（主线程）。该怎么办由 {@link RecordingLifecycle#on} 判。 */
    private void onPipelineReport(MultiCameraManager from, RecordingLifecycle.Report report, String detail) {
        if (from != attemptManager) {
            return;
        }
        RecordingLifecycle.Action action = lifecycle.on(report);
        switch (action) {
            case NOTE:
                BlackBox.noteImportant("录制器已启动 " + detail);
                break;
            case END:
                BlackBox.noteImportant(report == RecordingLifecycle.Report.START_FAILED
                        ? "开录失败：" + detail : "录制管线自己停了（没人叫它停，被释放了）");
                // 原因按停之前的阶段定：开录中没起来是开录失败，录了一阵之后重建没起来是录制器自己停了
                end(lifecycle.endReason(report));
                break;
            case SETTLED:
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

    /**
     * 在不在录 —— 进程里唯一的答案：开录指令发出去起（开录中），到停为止（{@link RecordingLifecycle}）。
     * 录制器是不是已经写出了第一笔数据是另一回事（主界面的「准备中」），问相机层的 {@code hasWrittenFirstData}。
     */
    public boolean isRecording() {
        return lifecycle.isRecording();
    }

    /**
     * 这一段录像是什么时候开的（{@code SystemClock.elapsedRealtime}）；没在录是 0。
     * 悬浮按钮的时长从这里算：用系统时钟算的话，车机对时把钟往回拨，时长就成了负数。
     */
    public long startedAtElapsedMs() {
        return isRecording() ? startedAtMs : 0;
    }

    /** 正在等环视出画面、准备开录。 */
    public boolean isWaiting() {
        return pending != null;
    }

    /** 录制管线还有人要：在录、在等开录、或者上一次还在收拾。主界面销毁时据此决定留不留管线。 */
    public boolean needsPipeline() {
        return !lifecycle.isIdle() || pending != null;
    }

    public RecordingStops.Reason lastStopReason() {
        return lastStopReason;
    }

    // ================================================================= 要录

    /** 要录：环视一出画面就开。已经在录、已经在等，都无害。主线程调。 */
    public void request(Why why) {
        request(why, true);
    }

    private void request(Why why, boolean counts) {
        if (isRecording()) {
            return;
        }
        if (pending == null) {
            pendingSinceMs = android.os.SystemClock.elapsedRealtime();
            BlackBox.noteImportant("要录像（" + why + "）：等环视出画面");
        }
        pending = why;
        pendingCounts = counts;
        // 等的时候相机算「录像要用」：登记了相机层就会开着它，熄屏那一步也不会把它放掉
        CameraNeeds.current().claim(CameraNeeds.Holder.RECORDING);
        main.removeCallbacks(poll);
        main.post(poll);
    }

    /** 不等了：人停了、退出了。 */
    public void cancelPending(String why) {
        if (pending == null) {
            return;
        }
        AppLog.i(TAG, "不再等环视（" + why + "）");
        pending = null;
        main.removeCallbacks(poll);
        if (!isRecording()) {
            CameraNeeds.current().release(CameraNeeds.Holder.RECORDING);
        }
    }

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
            if (!lifecycle.isIdle()) {
                // 上一次还在收拾：收拾完了（settled）会立刻再来看
                main.postDelayed(this, POLL_MS);
                return;
            }
            MultiCameraManager manager = currentManager();
            // 环视出画面了，而且该开的几路都按次序开完了：没开完就开录，晚开的那一路这一段录不上
            if (manager != null && manager.surroundHealthy() && manager.openInOrderDone()) {
                Why why = pending;
                boolean counts = pendingCounts;
                long waited = (android.os.SystemClock.elapsedRealtime() - pendingSinceMs) / 1000;
                if (why == Why.RESUME && counts) {
                    budget.noteAttempt();
                }
                BlackBox.noteImportant("环视正常了，开录（" + why + "，等了 " + waited + " 秒"
                        + (why == Why.RESUME ? "，第 " + budget.attempts() + " 次接回" : "") + "）");
                start(why, counts);
                return;
            }
            main.postDelayed(this, POLL_MS);
        }
    };

    /**
     * 熄屏了（ScreenState 在主线程调）。规矩只有一条（项目所有者 2026-09-27）：
     * 「熄屏录制（阻止休眠）」（开发者，拿唤醒锁）或「熄屏持续录制」开着就接着录；两个都没开，熄屏 10 秒后停，
     * 手动开的、自动开的一样停。
     */
    public void screenOff() {
        cancelScreenOffStop();
        if (!isRecording() && pending == null) {
            return;
        }
        darkSinceElapsedMs = android.os.SystemClock.elapsedRealtime();
        darkSinceUptimeMs = android.os.SystemClock.uptimeMillis();
        stopsWhileDark = 0;
        // 停不停、亮屏接不接，规矩在 ScreenOffPlan 一处（录制键上的小字照的也是它）
        if (ScreenOffPlan.keepsRecording(appConfig)) {
            if (appConfig.isScreenOffRecordingEnabled()) {
                // 唤醒锁由 ScreenOffRecording 拿
                BlackBox.noteImportant("熄屏时在录像：熄屏录制生效，继续录");
            } else {
                // 不申请唤醒、不拉住车机 —— 车机睡了录像就停在那一刻，醒来接着录；熄屏期间断了照样等环视接回
                // 哨兵模式此刻开没开一起记下：熄屏持续录制开着时，接不接着录看的就是它
                Integer sentry = com.kooo.evcam.telemetry.Telemetry.get().latest().sentry;
                BlackBox.noteImportant("熄屏时在录像：熄屏持续录制开着，接着录（不唤醒车机）；哨兵模式"
                        + (sentry == null ? "读不到" : sentry == 2 ? "布防" : sentry == 1 ? "开"
                        : "关（车机睡着时录像停住，醒来接着录）"));
            }
            return;
        }
        BlackBox.noteImportant("熄屏时在录像：熄屏录制没生效"
                + (appConfig.isScreenOffRecordingStoredOn() ? "（存着是开，开发者选项没解锁）" : "")
                + "、熄屏持续录制关着，" + (SCREEN_OFF_STOP_MS / 1000) + " 秒后停录");
        screenOffStop = () -> {
            screenOffStop = null;
            // 到点再看一眼：屏幕其实亮了、录像早停了、等的这几秒里开关被打开了 —— 都不停
            if (!com.kooo.evcam.screen.ScreenState.refresh()) {
                return;
            }
            if (!isRecording() && pending == null) {
                return;
            }
            if (ScreenOffPlan.keepsRecording(appConfig)) {
                return;
            }
            BlackBox.noteImportant("熄屏已 " + (SCREEN_OFF_STOP_MS / 1000) + " 秒，停录");
            stop(RecordingStops.Reason.SCREEN_OFF);
        };
        main.postDelayed(screenOffStop, SCREEN_OFF_STOP_MS);
    }

    private void cancelScreenOffStop() {
        if (screenOffStop != null) {
            main.removeCallbacks(screenOffStop);
            screenOffStop = null;
        }
    }

    /**
     * 亮屏了（ScreenState 在主线程调）：熄屏那一段的结果记一行；
     * 因熄屏停下来的录像，自动录制开着就接回（项目所有者 2026-09-27）。
     */
    public void screenOn() {
        cancelScreenOffStop();
        if (darkSinceElapsedMs > 0) {
            long offMs = android.os.SystemClock.elapsedRealtime() - darkSinceElapsedMs;
            long awakeMs = android.os.SystemClock.uptimeMillis() - darkSinceUptimeMs;
            int stops = stopsWhileDark;
            darkSinceElapsedMs = 0;
            darkSinceUptimeMs = 0;
            stopsWhileDark = 0;
            BlackBox.noteImportant("亮屏：熄屏这一段结束。熄屏 " + offMs / 1000 + " 秒，其中车机睡了 "
                    + Math.max(0L, offMs - awakeMs) / 1000 + " 秒；录像"
                    + (stops == 0 && isRecording() ? "一直在录"
                    : "中途停过 " + stops + " 次（原因见上面的「录像停止原因」），现在" + (isRecording() ? "在录" : "没在录")));
        }
        if (isRecording() || pending != null) {
            return;
        }
        if (lastStopReason == RecordingStops.Reason.SCREEN_OFF && ScreenOffPlan.resumesOnScreenOn(appConfig)
                && RecordingIntent.current().shouldRestore(true)) {
            lastStopReason = null;
            request(Why.SCREEN_ON);
        }
    }

    // ================================================================= 开

    /**
     * 开录用哪一份管线。手里那份没了（被释放）而进程里已经有了新的一份（{@link CameraManagerHolder}），
     * 就换成它 —— 换管线的地方不一定都记得交过来（后台建的那一份、换车型重建的那一份）。
     */
    private MultiCameraManager currentManager() {
        MultiCameraManager held = CameraManagerHolder.getInstance().getCameraManager();
        if (held != null && held != cameraManager && (cameraManager == null || cameraManager.isReleased())) {
            setCameraManager(held);
        }
        return cameraManager;
    }

    /** 环视出画面了，真正去开。查盘在存储线程上，查完回主线程接着开。 */
    private void start(Why why, boolean counts) {
        // 录哪几路 = 配置里启用了哪几路。这两件事本来就是同一件：关掉的相机不开、不录、不占流
        Set<String> cameras = com.kooo.evcam.profile.RecordSpecs.enabledCameraKeys(context);
        if (cameras.isEmpty()) {
            refused(why, counts, context.getString(R.string.msg_keep_one_camera_refuse));
            return;
        }
        // 查盘是异步的：查完回来就接着开（或者拒了再等）。万一一直不回来，到点再看一次，别一直等下去
        main.removeCallbacks(poll);
        main.postDelayed(poll, REFUSED_RETRY_MS);
        StorageState.refresh(context, "start", snapshot -> startWith(snapshot, cameras, why, counts, true));
    }

    /**
     * @param checkStorage false：刚为这次开录清理过空间，不再检查一遍 ——
     *                     否则估算的余量稍有变化就会清完又清，一直开不起来
     */
    private void startWith(StorageState.Snapshot storage, Set<String> cameras, Why why, boolean counts,
                           boolean checkStorage) {
        // 查盘这段时间里人停了、退出了（不等了）：不开
        if (pending == null) {
            return;
        }
        MultiCameraManager manager = currentManager();
        if (!lifecycle.isIdle() || manager == null) {
            // 已经开起来了（轮询会把「等」清掉），或者还没有管线：接着等
            main.removeCallbacks(poll);
            main.postDelayed(poll, POLL_MS);
            return;
        }
        // 正常模式下不往内置存储录：行车记录是一直在写的，而车机闪存换不了。
        // 判断放在这里，所有入口才是同一个答案（以前只有按按钮那条路拦得住）
        if (!storage.available) {
            refused(why, counts, context.getString(R.string.msg_refuse_no_external));
            return;
        }
        // 开录前先确认写得下：以前不查，盘满时照常开录，第一笔数据写不进去，按钮就卡在「正在准备」
        if (checkStorage && !ensureRoomToStart(storage, cameras, why, counts)) {
            return;
        }
        // 从这里起算「在录」（开录中）：到这一步才清掉「等」
        lifecycle.begin();
        pending = null;
        main.removeCallbacks(poll);
        attemptManager = manager;
        startedAtMs = android.os.SystemClock.elapsedRealtime();
        lastStopReason = null;
        slowWriteNoticedThisRecording = false;
        CameraNeeds.current().claim(CameraNeeds.Holder.RECORDING);
        // 这一趟要录过了：开录失败也算，失败了照样接回（规格 2.3）
        RecordingIntent.current().noteRecordingStarted();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        if (!manager.startRecording(timestamp, cameras)) {
            // 准备就失败了：准备到一半的录像输出、编码器照样由停录那条路收拾
            BlackBox.noteImportant("开录失败：相机层没能开始（" + why + "）");
            end(RecordingStops.Reason.START_FAILED);
            return;
        }
        BlackBox.noteImportant("录像开始: " + cameras + "（" + why + "）");
        // 前台服务：没有它，系统会在应用退到后台后把录制掐掉
        CameraForegroundService.start(context,
                context.getString(R.string.notif_recording_title),
                context.getString(R.string.notif_recording_tap));
        RecordingFloatingService.sendRecordingStateChanged(context, true);
        // 屏幕已经黑着才开始的录像（熄屏期间接回的那种），熄屏录制同样要拿锁
        ScreenOffRecording.onRecordingStarted(context);
        AppLog.d(TAG, "开始录制 " + cameras.size() + " 路: " + cameras);
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onRecordingStarted(cameras, storage.sdFellBack);
        }
    }

    /**
     * 空间够就返回 true。不够时：没设上限直接拒绝（不删录像）；设了上限就在后台清理，
     * 清完再开一次，这次返回 false。
     */
    private boolean ensureRoomToStart(StorageState.Snapshot storage, Set<String> cameras, Why why, boolean counts) {
        java.io.File dir = storage.videoDir;
        long free = storage.freeBytes;
        if (dir == null || free < 0 || free >= com.kooo.evcam.camera.StorageGuard.lastMarginBytes()) {
            return true;
        }
        if (appConfig.getVideoStorageLimitGb() <= 0) {
            refused(why, counts, context.getString(R.string.msg_storage_full_refuse));
            return false;
        }
        AppLog.i(TAG, "开录前空间不够，先清理最旧的录像");
        // 清理出错时不会回来（StorageGuard 只记日志）：到点再看一次
        main.removeCallbacks(poll);
        main.postDelayed(poll, REFUSED_RETRY_MS);
        // 「正在删除最旧的录像」等真要删时才弹：一个都删不掉（全锁着、删光也不够）时不该先说在删
        com.kooo.evcam.camera.StorageGuard.enforceAsync(context, dir,
                () -> notifyRefused(context.getString(R.string.msg_storage_cleaning)), decision -> {
            if (pending == null) {
                return;  // 清理的这段时间里人停了、退出了
            }
            if (decision.verdict == com.kooo.evcam.camera.StoragePlan.Verdict.FULL) {
                refused(why, counts, context.getString(decision.lockedFull
                        ? R.string.msg_storage_locked_refuse : R.string.msg_storage_cannot_free));
            } else {
                startWith(storage, cameras, why, counts, false);
            }
        });
        return false;
    }

    /** 条件不满足：告诉界面，然后留在「要录」的状态里，过一会儿再看（U 盘插上、盘清出来了都算）。 */
    private void refused(Why why, boolean counts, String reason) {
        notifyRefused(reason);
        pending = why;
        pendingCounts = counts;
        main.removeCallbacks(poll);
        main.postDelayed(poll, REFUSED_RETRY_MS);
    }

    // ================================================================= 停

    /**
     * 停。停的人说清原因；接不接、什么时候接，这里判。主线程调。
     *
     * <p>人停的、退出的：不再等。熄屏停的：等亮屏（{@link #screenOn}）。
     * 写不进、相机被拿走、没画面、开录失败、录制器自己停了：环视恢复了自动接回，有额度。
     * 盘满：接回去也录不下，不接。</p>
     */
    public void stop(RecordingStops.Reason reason) {
        boolean wasRecording = isRecording();
        if (!wasRecording && pending == null) {
            return;
        }
        // 人停的：不再等。熄屏停的：也不再等（黑着的时候不该自己录起来），亮屏时 screenOn 再判
        if (reason == RecordingStops.Reason.USER || reason == RecordingStops.Reason.SCREEN_OFF) {
            cancelPending(reason.name());
            if (!wasRecording) {
                lastStopReason = reason;
                return;
            }
        }
        if (!wasRecording) {
            return;
        }
        end(reason);
    }

    /**
     * 这一次录像到头了 —— 唯一的停录路径。开录中、在录都从这里停：人停的、被打断、开录失败、管线没了。
     *
     * <p>以前停录先问相机层在不在录，一路都没起来时它说没有，于是通知、悬浮按钮、唤醒锁都没人收，
     * 也不接回（2026-10-05）。现在不问：这里说在录就收，相机层按同一套收拾（开录走到哪一步都一样），
     * 收拾完了报上来（{@link #settled}）才开下一次。</p>
     */
    private void end(RecordingStops.Reason reason) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!lifecycle.stop(now)) {
            return;
        }
        if (attemptManager != null) {
            attemptManager.stopRecording();
        }
        main.removeCallbacks(stopDeadline);
        main.postDelayed(stopDeadline, RecordingLifecycle.STOP_DEADLINE_MS);
        CameraForegroundService.stop(context);
        RecordingFloatingService.sendRecordingStateChanged(context, false);
        // 熄屏录制的唤醒锁只在录像期间拿（规格 §3.1）
        ScreenOffRecording.release("recording-stopped");

        long lasted = startedAtMs > 0 ? now - startedAtMs : 0;
        startedAtMs = 0;
        lastStopReason = reason;
        if (darkSinceElapsedMs > 0) {
            stopsWhileDark++;
        }
        budget.noteRecordingLasted(lasted);
        BlackBox.noteImportant("录像停止原因: " + reason + "，这一段录了 " + (lasted / 1000) + " 秒");
        AppLog.d(TAG, "录制已停止（" + reason + "），前台服务已关闭");

        boolean willResume = resumeAfter(reason);
        if (!willResume && pending == null) {
            CameraNeeds.current().release(CameraNeeds.Holder.RECORDING);
        }
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onRecordingStopped(reason, lasted, willResume);
        }
    }

    /** 停录中，管线收拾了太久还没报完：不等了，免得再也开不起来。 */
    private final Runnable stopDeadline = () -> {
        if (lifecycle.stopOverdue(android.os.SystemClock.elapsedRealtime())) {
            BlackBox.noteImportant("停录收拾了 " + (RecordingLifecycle.STOP_DEADLINE_MS / 1000)
                    + " 秒还没报完，不等了");
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
     * 录像因为这个原因停了，要不要等环视恢复再接回。
     *
     * <p>总原则（规格 §0）：用户开着录像，App 就该一直录着 —— 手动开的、自动开的都算，
     * 只要这一趟录起来过、不是人停的。相机被别的程序拿走的不计入额度：额度防的是
     * 「接回去又立刻停」的循环，那一种要等它放开才接，不会循环。</p>
     */
    private boolean resumeAfter(RecordingStops.Reason reason) {
        if (!RecordingStops.resumesOnSurround(reason)) {
            return false;
        }
        if (!RecordingIntent.current().shouldRestore(true)) {
            BlackBox.noteImportant("录像被打断（" + reason + "），不自动接回");
            return false;
        }
        // 相机被别的程序拿走的那种不计额度；没人占着却被断开的（自己顶自己）照计，否则无限循环（2026-10-08）
        boolean counts = RecordingStops.countsTowardBudget(reason, CameraTaken.othersHold());
        if (counts && !budget.allows()) {
            BlackBox.noteImportant("录像被打断（" + reason + "），自动恢复已连续失败 "
                    + budget.attempts() + " 次，不再尝试");
            return false;
        }
        BlackBox.noteImportant("录像被打断（" + reason + "），等环视恢复后自动接回（已试 "
                + budget.attempts() + " 次）");
        request(Why.RESUME, counts);
        return true;
    }

    /**
     * 录制器报写盘跟不上（主线程）：某一路的写入排队满了，相机这一侧开始丢帧。不停录 —— 录像照常，
     * 只是可能丢帧；提示一句（{@link Listener#onWriteSlow}），一次录像最多一次，两次至少隔
     * {@link #SLOW_WRITE_NOTICE_GAP_MS}。满了多久、丢了几帧，录制器自己记黑匣子。
     */
    private void onWriteBacklog(String cameraId) {
        if (!isRecording()) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
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

    /** 人自己开了：前面的失败都不算了。 */
    public void resetBudget() {
        budget.reset();
    }

    public int resumeAttempts() {
        return budget.attempts();
    }

    private void notifyRefused(String reason) {
        AppLog.w(TAG, "不满足录制条件：" + reason);
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onRecordingRefused(reason);
        }
    }
}
