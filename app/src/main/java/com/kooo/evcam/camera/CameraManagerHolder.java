package com.kooo.evcam.camera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.Profile;
import com.kooo.evcam.profile.ProfileResolution;
import com.kooo.evcam.profile.ProfileSizes;
import com.kooo.evcam.profile.ProfileStore;
import com.kooo.evcam.profile.RecordSpecs;
import com.kooo.evcam.zeekr.StreamLayoutTable;
import com.kooo.evcam.zeekr.ZeekrCameraLocator;
import com.kooo.evcam.zeekr.ZeekrMultiPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 进程里那一份相机管线（{@link MultiCameraManager}）—— <b>只在这里建</b>（大纲 §2 阶段 6，channel-logic §五：
 * 相机管线属于应用，不属于主界面；主界面只是登记表里的一个需要方）。
 *
 * <h3>为什么只在这里</h3>
 *
 * <p>以前有两份按车型建映射的代码：主界面自己建一份（按配置分座舱、钉尺寸、画四宫格），后台（悬浮窗、录制服务）在这里建一份
 * （按车型字符串、不看配置），规则对不上；主界面销毁时还会把管线放掉，后视镜、录像跟着断。现在谁要管线都调
 * {@link #getOrInit}（主界面调 {@link #getForMainScreen}）：没有就在这里建，主界面拿到它只声明自己的画布、挂自己的回调
 * （{@link #whenBuilt}）。</p>
 *
 * <h3>什么时候放掉重建</h3>
 *
 * <ul>
 *   <li>退出（{@link #release}）；</li>
 *   <li>换了车型：谁来要都重建（切换车型只提示「重启应用后生效」，前台服务却让进程一直活着）；</li>
 *   <li>上一次列相机出错（这一份一路相机都没有）：谁来要都重建，但两次之间至少隔 {@link #FAILED_REBUILD_GAP_MS} ——
 *       录像在等开录时每 2 秒要一次管线，相机服务一直出错的话就成了每 2 秒放一份、建一份；</li>
 *   <li>配置里开的几路变了：只在主界面接上管线时（{@link #getForMainScreen}）重建 —— 主界面的布局照的就是此刻的配置，
 *       和以前「主界面看路数对不上就放掉重建」一样。后台的调用方（拍照、录像、后视镜）照用手里那一份：编辑配置的界面就长在
 *       主界面里，存一下不重建主界面，那时谁一要管线就重建的话，主界面握着放掉了的那一份，预览黑着、回调挂在死的那份上
 *       （审查 2026-10-11）。</li>
 *   <li>只为超级镜子建的后座舱要不要了（切换范围换到、换出「后视 + 后座舱」，配置里没启用后座舱）：谁来要都重建 ——
 *       启用的几路没变，主界面的布局不变，它照「放掉重建了」接上新的那一份（项目所有者 2026-10-19 方案 A；
 *       设置页改了这一档就来要一次，当场重建）。</li>
 * </ul>
 *
 * <p>放掉重建了就告诉挂着的监听者（{@link #addReplacedListener}，主线程）：主界面据此当场接上新的那一份。</p>
 *
 * <h3>怎么建</h3>
 *
 * <ul>
 *   <li>对象当场建好交出去（登记表的监听、调度、录像宿主都在它的构造里接上）；相机列表、合成流探测、照配置定尺寸
 *       都要进相机服务，在 {@link #setup} 这条线程上做 —— 主线程不进相机服务（lifecycle-spec §3.5：相机服务忙的时候一问要等
 *       几秒到十几秒）。做完回主线程 {@link MultiCameraManager#initCameras}，各路的画面尺寸由 SingleCamera 在它自己的相机线程上
 *       不开设备先算（{@code resolveSize}）。</li>
 *   <li>槽位照存下的映射建（{@link ZeekrMultiPlan#choose}）：相机列表完整时照列表分，值得存才存下来（{@code worthStoring}：
 *       环视是探测到的合成流、或者还没存过能用的；只在变了时写）；不完整时照存下的建，
 *       列表里少的那一路照样建出来，交给通道调度判「不在」（被别的程序拿着）—— 车机拿着后座舱时它就不在列表里，以前照列表分，
 *       后座舱这一格就没了，以后既认不出、也开不了。没存过（第一次装）照列表建（大纲 §6 风险 13）。</li>
 *   <li>建哪几路照当前配置里启用了哪几路（{@link RecordSpecs#enabledCameraKeys}，和主界面的布局同一个函数）：环视一直建
 *       （登记表 §一 超级镜子、盲区画面要它，配置里没开环视也要），座舱启用了才建；后座舱另外在超级镜子的切换范围是
 *       「后视 + 后座舱」时也建（超级镜子要它，配置里没开也要 —— 和环视同一条规矩），它不算启用的（主界面预览、拍照、录像都不要它）。
 *       建了不等于开，开不开由通道调度照登记表定。
 *       环视钉合成流尺寸（照配置解，「最大」也解得出来），座舱照配置定尺寸、auto 不钉。</li>
 * </ul>
 */
public class CameraManagerHolder {
    private static final String TAG = "CameraManagerHolder";
    private static CameraManagerHolder instance;
    private MultiCameraManager cameraManager;
    /**
     * 这一份是照什么建的（{@link Shape#key}）：车型，加上配置里开了哪几路。
     *
     * <p>切换车型只提示「重启应用后生效」，但前台服务会让进程一直活着，这一份连同它的摄像头映射也就跟着留下来了。
     * 不记住的话，换了配置仍然会复用旧映射 —— 表现就是画面全黑。车型变了谁来要都放掉重建；开的几路变了，主界面接上时重建
     * （见类说明）。</p>
     */
    private String builtFor;
    /** 这一份照哪几路启用的建的（{@link Shape#lanesKey}）：变了只在主界面接上时重建（见类说明）。 */
    private String builtLanes;
    /** 这一份是照哪个车型建的（{@link Shape#carModel}）：车型变了谁来要都重建。 */
    private String builtCarModel;
    /** 这一份建好之后的样子（主线程上写）；null = 还在建。 */
    private Built built;
    /** 等这一份建好的（{@link #whenBuilt}，主线程）。 */
    private final List<Waiter> waiters = new ArrayList<>();
    /** 放掉重建了要告诉的（{@link #addReplacedListener}）：在主线程上叫。 */
    private final List<Runnable> replacedListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 列相机出错之后，至少隔这么久才再放掉重建一次（见类说明）。 */
    static final long FAILED_REBUILD_GAP_MS = 10_000L;
    /** 上一次建出错（列相机出错）的时刻（elapsedRealtime）；建好了清成 0。 */
    private long failedAtMs;
    /** 连着出错了几次（建好了清零）：只有第一次进黑匣子，接着的进日志，好了再记一行一共几次。 */
    private int failedStreak;
    /**
     * 建管线的那一条线程（相机列表、合成流探测、照配置定尺寸都要进相机服务）：一次只建一份，空闲几秒线程就退。
     */
    private final java.util.concurrent.ThreadPoolExecutor setup = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 5, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
            runnable -> new Thread(runnable, "CameraSetup"));

    private CameraManagerHolder() {}

    public static synchronized CameraManagerHolder getInstance() {
        if (instance == null) {
            instance = new CameraManagerHolder();
        }
        return instance;
    }

    /** 一份管线建好之后的样子：主界面画四宫格几何、状态条那一格、座舱格子、出错的提示用。 */
    public static final class Built {
        /** 槽位怎么分的（照存下的、或者照列表）；列相机出错时是 null。 */
        public final ZeekrMultiPlan plan;
        /** 合成流的尺寸：探测到的，或者存下的；null = 不知道（环视不是合成流、探测不到也没存过）。 */
        public final Size compositeSize;
        /** 照配置钉在环视上的尺寸；null = 不钉（不是合成流，或者配置解不出来）。 */
        public final Size compositeUse;
        /** 合成流探测器的说明（日志、主界面状态条那一格的来历）。 */
        public final String diagnostics;
        /** 相机服务没答上来（列相机出错）：这一份一路相机都没有，隔够 {@link CameraManagerHolder#FAILED_REBUILD_GAP_MS} 之后有人要管线时重建。 */
        public final boolean failed;

        Built(ZeekrMultiPlan plan, Size compositeSize, Size compositeUse, String diagnostics, boolean failed) {
            this.plan = plan;
            this.compositeSize = compositeSize;
            this.compositeUse = compositeUse;
            this.diagnostics = diagnostics;
            this.failed = failed;
        }
    }

    /** 等某一份管线建好的一位。 */
    private static final class Waiter {
        final MultiCameraManager manager;
        final Consumer<Built> then;

        Waiter(MultiCameraManager manager, Consumer<Built> then) {
            this.manager = manager;
            this.then = then;
        }
    }

    /**
     * 这一份照什么建：车型（存下的映射按它存）、配置里启用了哪几路（{@link RecordSpecs#enabledCameraKeys}；主界面的布局
     * 照同一个函数）。环视一直建：登记表 §一 超级镜子、盲区画面要的是环视，配置里没开环视也要能看（项目所有者 2026-10-16）；
     * 座舱启用了才建；后座舱另外在超级镜子要它时也建（{@link #mirrorCabin}）。建了不等于开：主界面预览、拍照只要启用的那几路，
     * 交给调度的就是这份（{@link MultiCameraManager#setEnabledLanes}）。
     */
    static final class Shape {
        final String carModel;
        final Profile profile;
        /** 启用的那几路（key）。 */
        final Set<String> enabled;
        /** 超级镜子要后座舱：切换范围是「后视 + 后座舱」（AppConfig.rearViewWantsCabinRear）。 */
        final boolean mirrorCabin;
        /** 前座舱（key back，配置里的座舱 1）要建。 */
        final boolean cabinFront;
        /** 后座舱（key left，配置里的座舱 2）要建：启用了，或者超级镜子要它。 */
        final boolean cabinRear;
        /** 建几路：环视，加上要建的座舱。 */
        final int count;

        Shape(String carModel, Profile profile, Set<String> enabled, boolean mirrorCabin) {
            this.carModel = carModel;
            this.profile = profile;
            this.enabled = enabled;
            this.mirrorCabin = mirrorCabin;
            this.cabinFront = enabled.contains(CameraSlots.KEY_CABIN_FRONT);
            this.cabinRear = enabled.contains(CameraSlots.KEY_CABIN_REAR) || mirrorCabin;
            this.count = 1 + (cabinFront ? 1 : 0) + (cabinRear ? 1 : 0);
        }

        static Shape of(Context app) {
            Profile profile = new ProfileStore(app).current();
            AppConfig config = new AppConfig(app);
            return new Shape(config.getCarModel(), profile, RecordSpecs.enabledCameraKeys(profile),
                    config.rearViewWantsCabinRear());
        }

        /** 车型和启用的几路（主界面的布局照它）。 */
        String lanesKey() {
            return carModel + "/" + (enabled.isEmpty() ? "-" : String.join("+", CameraSlots.openOrder(enabled)));
        }

        /** 建了哪几路：{@link #lanesKey}，加上只为超级镜子建的后座舱。 */
        String key() {
            return lanesKey() + (cabinRear && !enabled.contains(CameraSlots.KEY_CABIN_REAR)
                    ? "/mirror:" + CameraSlots.KEY_CABIN_REAR : "");
        }
    }

    /**
     * 进程里那一份管线；没有就建（大纲 §2 阶段 6：只在这里建）。后台的调用方都调它：录像协调器、后视镜、拍照（主界面的、
     * 悬浮按钮的）。主界面接上管线调 {@link #getForMainScreen}。
     *
     * <p>对象当场交出去，各路相机要等相机线程上列完相机、探测完合成流才建出来（{@link MultiCameraManager#isBuilt}、
     * {@link #whenBuilt}）。换了车型、或者上一次列相机出错（隔够 {@link #FAILED_REBUILD_GAP_MS}），放掉旧的、重建；
     * 配置里开的几路变了不在这里重建（见类说明）。主线程调。</p>
     */
    public synchronized MultiCameraManager getOrInit(Context context) {
        return obtain(context, false);
    }

    /**
     * 主界面接上管线（MainActivity.initCamera）：和 {@link #getOrInit} 一样，另外配置里开的几路和这一份对不上也放掉重建 ——
     * 主界面的布局照的就是此刻的配置（以前主界面看路数对不上就放掉重建，这里照旧）。在录的话那一段到头，在新的一份上自动接回
     * （RecordingCoordinator.setCameraManager）。主线程调。
     */
    public synchronized MultiCameraManager getForMainScreen(Context context) {
        return obtain(context, true);
    }

    private MultiCameraManager obtain(Context context, boolean forMainScreen) {
        Context app = context.getApplicationContext();
        Shape shape = Shape.of(app);
        if (cameraManager != null && !cameraManager.isReleased()) {
            Rebuild why = rebuildReason(shape, forMainScreen);
            if (why == Rebuild.NO) {
                return cameraManager;
            }
            if (why == Rebuild.LISTING_FAILED && failedStreak > 1) {
                AppLog.w(TAG, "管线：上一次列相机出错，放掉重建（连着第 " + failedStreak + " 次出错）");
            } else {
                BlackBox.noteImportant("管线：" + (why == Rebuild.CAR_MODEL
                        ? "车型变了（" + builtCarModel + " → " + shape.carModel + "），放掉旧的、重建"
                        : why == Rebuild.LISTING_FAILED ? "上一次列相机出错，放掉重建（再出错的话，隔 "
                        + FAILED_REBUILD_GAP_MS / 1000 + " 秒以上才再重建，不再逐次记）"
                        : why == Rebuild.MIRROR_CABIN ? "超级镜子的切换范围变了，只为它建的后座舱要建 / 不要了（"
                        + builtFor + " → " + shape.key() + "），放掉旧的、重建"
                        : "主界面接上时配置里开的几路变了（" + builtFor + " → " + shape.key() + "），放掉旧的、重建"));
            }
            cameraManager.release();
            cameraManager = null;
            for (Runnable listener : replacedListeners) {
                main.post(listener);
            }
        }
        MultiCameraManager manager = new MultiCameraManager(app);
        manager.setMaxOpenCameras(shape.count);
        // 录制模式建管线时定一次（录制模式归开发者选项，从下一次启动应用起生效）
        manager.setCodecRecordingMode(new AppConfig(app).shouldUseCodecRecording());
        cameraManager = manager;
        builtFor = shape.key();
        builtLanes = shape.lanesKey();
        builtCarModel = shape.carModel;
        built = null;
        AppLog.i(TAG, "建相机管线（" + builtFor + "，建 " + shape.count + " 路）：相机列表、合成流探测在相机线程上做");
        setup.execute(() -> build(app, manager, shape));
        return manager;
    }

    /** 手里这一份为什么要放掉重建（{@link #rebuildReason}；见类说明）。 */
    private enum Rebuild {
        /** 不用：照用手里这一份。 */
        NO,
        /** 换了车型（谁来要都重建）。 */
        CAR_MODEL,
        /** 上一次列相机出错，而且隔够了 {@link CameraManagerHolder#FAILED_REBUILD_GAP_MS}（谁来要都重建）。 */
        LISTING_FAILED,
        /** 配置里开的几路变了，主界面在接上管线（只在 {@link CameraManagerHolder#getForMainScreen} 里重建）。 */
        LANES,
        /** 只为超级镜子建的后座舱要建 / 不要了（谁来要都重建：主界面的布局不变，照「放掉重建了」接上新的那一份）。 */
        MIRROR_CABIN
    }

    /** 手里这一份要不要放掉重建（见类说明）。还在建的那一份（built 为 null）只看车型、开的几路、超级镜子要不要后座舱。 */
    private Rebuild rebuildReason(Shape shape, boolean forMainScreen) {
        if (!shape.carModel.equals(builtCarModel)) {
            return Rebuild.CAR_MODEL;
        }
        if (built != null && built.failed) {
            return android.os.SystemClock.elapsedRealtime() - failedAtMs < FAILED_REBUILD_GAP_MS
                    ? Rebuild.NO : Rebuild.LISTING_FAILED;
        }
        if (!shape.lanesKey().equals(builtLanes)) {
            return forMainScreen ? Rebuild.LANES : Rebuild.NO;
        }
        return shape.key().equals(builtFor) ? Rebuild.NO : Rebuild.MIRROR_CABIN;
    }

    /**
     * 这一份还是进程里在用的那一份：没放、没被换掉、建的时候列相机没出错。主界面回到前台、收到「放掉重建了」时据此判断要不要
     * 接上新的那一份（列相机出错的那一份也当作要换：接的时候隔够了就重建）。
     */
    public synchronized boolean isCurrent(MultiCameraManager manager) {
        return manager != null && manager == cameraManager && !manager.isReleased()
                && !(built != null && built.failed);
    }

    /**
     * 放掉重建了就叫 {@code listener}（主线程上叫，可能在当时持有的那一份已经放了之后）：主界面挂上，据此当场接上新的那一份 ——
     * 它握着放掉了的那一份时，预览黑着、回调挂在死的那份上。退出（{@link #release}）不叫。挂了就要在销毁时摘
     * （{@link #removeReplacedListener}）。
     */
    public void addReplacedListener(Runnable listener) {
        if (listener != null && !replacedListeners.contains(listener)) {
            replacedListeners.add(listener);
        }
    }

    public void removeReplacedListener(Runnable listener) {
        replacedListeners.remove(listener);
    }

    /** 已有的那一份（不建）；没有是 null。只读它的事实（诊断、可用性分类、熄屏录制）用这个。 */
    public synchronized MultiCameraManager getCameraManager() {
        return cameraManager;
    }

    /**
     * 这一份管线建好了就叫 {@code then}（主线程上调、主线程上叫）：已经建好当场叫，否则等建好时叫。
     * 管线已经不是这一份了（放了、换了一份）就不叫 —— 等的一方再去拿新的那一份。
     */
    public void whenBuilt(MultiCameraManager manager, Consumer<Built> then) {
        Built done;
        synchronized (this) {
            if (manager == null || manager != cameraManager) {
                return;
            }
            done = built;
            if (done == null) {
                waiters.add(new Waiter(manager, then));
                return;
            }
        }
        then.accept(done);
    }

    /**
     * 放掉这一份（退出时）：MultiCameraManager.release 停录收尾、让调度按次序关相机，关完调度自己停、放掉交给它的录制器。
     * 管线只在退出和换车型时才放。
     */
    public synchronized void release() {
        if (cameraManager != null) {
            cameraManager.release();
            cameraManager = null;
        }
        builtFor = null;
        builtCarModel = null;
        built = null;
        failedAtMs = 0;
        failedStreak = 0;
        waiters.clear();
    }

    // ================================================================= 建（相机线程）

    /**
     * 在 {@link #setup} 线程上：列相机、探测合成流、分槽位（照存下的映射或者照列表）、照配置定尺寸，做完回主线程交给管线。
     * 这里会进相机服务，所以不在主线程上。
     */
    private void build(Context app, MultiCameraManager manager, Shape shape) {
        AppLog.i(TAG, "本次建管线使用的配置:\n" + shape.profile);
        AppConfig config = new AppConfig(app);
        CameraManager service = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
        String[] ids;
        try {
            if (service == null) {
                throw new IllegalStateException("camera service not available");
            }
            ids = service.getCameraIdList();
        } catch (CameraAccessException | RuntimeException e) {
            AppLog.e(TAG, "管线：列相机失败，这一份没有相机，隔够 " + FAILED_REBUILD_GAP_MS / 1000 + " 秒之后有人要管线时重建: " + e);
            Built failed = new Built(null, null, null, String.valueOf(e), true);
            main.post(() -> finish(manager, shape, failed, null, null));
            return;
        }
        ZeekrCameraLocator.Result located = ZeekrCameraLocator.locate(service);
        AppLog.i(TAG, "极氪合成流探测结果（相机列表 " + java.util.Arrays.toString(ids) + "）:\n" + located.diagnostics);

        String overrideComposite = config.getCameraOverride(CameraSlots.KEY_SURROUND);
        String overrideCabin1 = config.getCameraOverride(CameraSlots.KEY_CABIN_FRONT);
        String overrideCabin2 = config.getCameraOverride(CameraSlots.KEY_CABIN_REAR);
        String overrides = ZeekrMultiPlan.Stored.overridesKey(overrideComposite, overrideCabin1, overrideCabin2);
        String storedText = config.getStoredSlotMap(shape.carModel);
        ZeekrMultiPlan.Stored stored = ZeekrMultiPlan.Stored.decode(storedText);
        ZeekrMultiPlan plan = ZeekrMultiPlan.choose(ids, located.found() ? located.cameraId : null,
                overrideComposite, overrideCabin1, overrideCabin2, stored);
        AppLog.i(TAG, "槽位分配:\n" + plan.explanation + "-> " + plan);

        // 合成流尺寸：探测到的就是环视这一路，用探测的；环视不在列表里（探测不到它）用存下的
        Size compositeSize = null;
        if (located.found() && located.cameraId.equals(plan.compositeId)) {
            compositeSize = located.size;
        } else if (plan.fromStored && stored != null && stored.compositeWidth > 0 && stored.compositeHeight > 0) {
            compositeSize = new Size(stored.compositeWidth, stored.compositeHeight);
        }

        if (plan.fromStored) {
            // 列表里没有、又是这一次要建的那几路：照样建出来，调度开之前看相机服务说它「不在」，判成被别的程序拿着
            List<String> absent = new ArrayList<>();
            for (String id : plan.missing) {
                if (id.equals(plan.compositeId) || shape.cabinFront && id.equals(plan.cabin1Id)
                        || shape.cabinRear && id.equals(plan.cabin2Id)) {
                    absent.add(id);
                }
            }
            BlackBox.noteImportant("管线：照存下的映射建 环视=" + plan.compositeId + "、后座舱=" + plan.cabin2Id
                    + "、前座舱=" + plan.cabin1Id + "（车型 " + shape.carModel + "）"
                    + (absent.isEmpty() ? "" : "；相机列表里现在没有 " + String.join("、", absent) + " → 交给调度判"));
        } else if (plan.worthStoring(stored, overrides)) {
            // 列表完整、值得存（环视是探测到的合成流，或者还没存过能用的）才更新：合成流这一次没探测到时，照列表分出来的是
            // 「普通相机顶替环视、座舱顺次挪位」，不能拿它盖掉存着的那一份好的
            ZeekrMultiPlan.Stored now = ZeekrMultiPlan.Stored.of(plan,
                    compositeSize == null ? 0 : compositeSize.getWidth(),
                    compositeSize == null ? 0 : compositeSize.getHeight(), overrides);
            String encoded = now.encode();
            if (!encoded.equals(storedText)) {
                config.setStoredSlotMap(shape.carModel, encoded);
                BlackBox.noteImportant("管线：相机列表完整，更新存下的映射 " + now + "（车型 " + shape.carModel + "）");
            }
        }

        // 「哪一路是合成流」（四宫格拆不拆、照片和录像的几何都看它）：照分好的槽位定，探测器探到的不一定是环视那一路（手动指定）
        StreamLayoutTable.setCompositeCameraId(plan.compositeIsReal ? plan.compositeId : null);

        // 照配置定尺寸（「最大」要问相机服务这一路声明了哪些尺寸，所以也在这条线程上算）：环视只在真是合成流时钉
        Size compositeUse = plan.compositeIsReal && compositeSize != null
                ? profileSize(app, shape.profile, CameraProfile.ROLE_COMPOSITE, compositeSize) : null;
        Size cabinFrontUse = shape.cabinFront ? profileSize(app, shape.profile, CameraProfile.ROLE_CABIN_1, null) : null;
        Size cabinRearUse = shape.cabinRear ? profileSize(app, shape.profile, CameraProfile.ROLE_CABIN_2, null) : null;
        Built result = new Built(plan, compositeSize, compositeUse, located.diagnostics, false);
        main.post(() -> finish(manager, shape, result, cabinFrontUse, cabinRearUse));
    }

    /**
     * 回到主线程：照分好的槽位建各路相机、交给调度，紧接着钉尺寸（同一个主线程任务里：各路的画面尺寸排在这之后才算，
     * 先算就算成别的尺寸了），然后告诉等着的。这期间管线放了、换了一份，就什么都不做。
     */
    private void finish(MultiCameraManager manager, Shape shape, Built result, Size cabinFrontUse, Size cabinRearUse) {
        List<Waiter> due = new ArrayList<>();
        synchronized (this) {
            if (manager != cameraManager || manager.isReleased()) {
                return;
            }
            ZeekrMultiPlan plan = result.plan;
            if (plan == null) {
                manager.initCameras(null, null, null, null, null, null, null, null);
            } else {
                manager.setEnabledLanes(shape.enabled);
                manager.initCameras(plan.compositeId, null,
                        shape.cabinFront ? plan.cabin1Id : null, null,
                        shape.cabinRear ? plan.cabin2Id : null, null,
                        null, null);
                pin(manager, CameraSlots.KEY_SURROUND, result.compositeUse);
                pin(manager, CameraSlots.KEY_CABIN_FRONT, cabinFrontUse);
                pin(manager, CameraSlots.KEY_CABIN_REAR, cabinRearUse);
                AppLog.i(TAG, "相机管线建好了（" + builtFor + "）: " + plan + "，环视尺寸 " + result.compositeUse
                        + "（合成流 " + result.compositeSize + "），前座舱 " + cabinFrontUse + "，后座舱 " + cabinRearUse);
            }
            if (result.failed) {
                failedAtMs = android.os.SystemClock.elapsedRealtime();
                failedStreak++;
            } else {
                if (failedStreak > 1) {
                    BlackBox.noteImportant("管线：列相机连着出错 " + failedStreak + " 次之后建好了");
                }
                failedAtMs = 0;
                failedStreak = 0;
            }
            built = result;
            for (java.util.Iterator<Waiter> it = waiters.iterator(); it.hasNext(); ) {
                Waiter waiter = it.next();
                if (waiter.manager == manager) {
                    due.add(waiter);
                }
                it.remove();   // 别的那几份（放了、换了）的等待一起作废
            }
        }
        for (Waiter waiter : due) {
            waiter.then.accept(result);
        }
    }

    private static void pin(MultiCameraManager manager, String key, Size size) {
        SingleCamera camera = manager.getCamera(key);
        if (camera != null && size != null) {
            camera.setPreferredSize(size);
        }
    }

    /**
     * 这一路在配置里要用的预览尺寸。
     *
     * @param probed 这一路探测到的尺寸；没有传 null
     * @return null 表示不指定，交给 {@code chooseOptimalSize} 按老规则挑
     */
    private static Size profileSize(Context app, Profile profile, String role, Size probed) {
        CameraProfile camera = profile.camera(role);
        if (camera == null) {
            return probed;
        }
        int[] probedPair = probed == null ? null : new int[]{probed.getWidth(), probed.getHeight()};
        // 「最大」也要能解出来：不给声明的最大尺寸的话，选了 max 等于没选 —— 解成「不指定」，相机就按老规则挑一个 1280x800 附近的
        ProfileResolution.Size size = ProfileResolution.resolve(camera.preview.resolution,
                probedPair, ProfileSizes.declaredMax(app, role));
        return size.specified() ? new Size(size.width, size.height) : null;
    }
}
