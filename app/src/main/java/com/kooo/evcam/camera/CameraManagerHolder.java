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
import java.util.Map;
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
 * <h3>生效的配置</h3>
 *
 * <p>生效的配置只有一份：现在这一份管线照的那份（{@link #effective}：车型、选的哪份视频流配置、那份里开了哪几路和每一路的参数、
 * 摄像头映射）。录像录哪几路、各路参数和尺寸、各格的摆法、状态条上的配置名都问它（{@link RecordSpecs#effectiveProfile}、
 * {@link RecordSpecs#enabledCameraKeys(Context)}）。设置页编辑的是存下的那份（ProfileStore、AppConfig），
 * <b>回到主界面时生效</b>（项目所有者 2026-10-30）：在设置里改几项都只换一次。</p>
 *
 * <h3>什么时候放掉重建</h3>
 *
 * <ul>
 *   <li>退出（{@link #release}）；</li>
 *   <li>配置变了：只在主界面接上管线时（{@link #getForMainScreen}）比。设置页就长在主界面里，回到主界面那一刻主界面先照存下的配置
 *       重新摆（MainActivity.relayoutIfConfigChanged），新界面接上时存下的配置生效：建管线、录像用到的变了（{@link Shape#pipelineKey}），
 *       放掉旧的、照存下的建新的 —— 唯一的通道调度交接，旧的按次序关完、新的再开，在录的话那一段到头、在新的一份上自动接回
 *       （RecordingCoordinator.setCameraManager）；只是名字、各格的摆法变了，管线不换，生效的配置换成存下的。
 *       布局和管线照的是同一份：只有照存下的配置摆过的主界面接上才生效；还没重新摆就接上的（摆着设置页时管线被换了一份）
 *       照用生效的，等它重新摆了再换（2026-10-20 审查，车主批：以前比的是此刻的配置，主界面还没重新摆就接上，布局和管线对不上）。
 *       后台的调用方（拍照、录像、超级镜子、悬浮按钮）一律照用生效的那一份，不因为配置变了重建；它们也要重建时（下面几条），
 *       照生效的配置建；</li>
 *   <li>上一次列相机出错（这一份一路相机都没有）：谁来要都重建，但两次之间至少隔 {@link #FAILED_REBUILD_GAP_MS} ——
 *       录像在等开录时每 2 秒要一次管线，相机服务一直出错的话就成了每 2 秒放一份、建一份；</li>
 *   <li>超级镜子要后座舱（切换范围换到「后视 + 后座舱」）而这一份里没有后座舱：谁来要都重建（项目所有者 2026-10-19 方案 A；
 *       设置页改了这一档就来要一次，当场重建），照生效的配置加上后座舱建。换出这一档不当场重建：建了不等于开，没人要的那一路
 *       照登记表关；主界面下一次接上管线时把它去掉（和配置变了同一个时机）。所以设置里来回切，一份管线最多为它重建一次 ——
 *       每一次重建都要关完再开，在录的话那一段到头、自动接回，接回的额度是有限的。</li>
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
 *       后座舱这一格就没了，以后既认不出、也开不了。没存过（第一次装）照列表建（大纲 §6 风险 13）。手动指定的那几路照这一份的
 *       （{@link Shape#overrideSurround}），不另外再读设置。</li>
 *   <li>建哪几路照那份配置里启用了哪几路（{@link RecordSpecs#enabledCameraKeys(Profile)}，和主界面的布局同一个函数）：环视一直建
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
     * 生效的配置：这一份管线是照什么建的（还在建的那一份也算；见类说明）。录像、尺寸、状态条问的「生效的」都是它
     * （{@link #effectiveProfile}、{@link #effectiveLanes}）。主界面接上时存下的配置和它不一样才换；离车去掉前座舱时就地改
     * （{@link #frontCabinRetired}）。null = 没有管线（进程刚起来、退出后）。
     *
     * <p>在锁里换、换的是整个对象（Shape 不改），读的不拿锁（{@link #effectiveProfile} 在相机线程上配会话时也读，
     * 不和主线程上建管线、放管线抢这把锁）。</p>
     */
    private volatile Shape effective;
    /** 生效的几路变了（离车去掉了前座舱）要告诉的：主界面据此照存下的配置重新摆（主线程上叫）。 */
    private final List<Runnable> lanesListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
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
     * 这一份照什么建：车型（存下的映射按它存）、那份配置和它启用了哪几路（{@link RecordSpecs#enabledCameraKeys(Profile)}；
     * 主界面的布局照同一个函数）、摄像头映射。环视一直建：登记表 §一 超级镜子、盲区画面要的是环视，配置里没开环视也要能看
     * （项目所有者 2026-10-16）；座舱启用了才建；后座舱另外在超级镜子要它时也建（{@link #mirrorCabin}）。建了不等于开：
     * 主界面预览、拍照只要启用的那几路，交给调度的就是这份（{@link MultiCameraManager#setEnabledLanes}）。
     */
    static final class Shape {
        final String carModel;
        final Profile profile;
        /** 启用的那几路（key）。 */
        final Set<String> enabled;
        /** 超级镜子要后座舱：超级镜子开着，切换范围是「后视 + 后座舱」（AppConfig.rearViewWantsCabinRear）。 */
        final boolean mirrorCabin;
        /** 摄像头映射：手动指定的环视、前座舱、后座舱（AppConfig.getCameraOverride；没指定是 null）。 */
        final String overrideSurround;
        final String overrideCabinFront;
        final String overrideCabinRear;
        /** 前座舱（key back，配置里的座舱 1）要建。 */
        final boolean cabinFront;
        /** 后座舱（key left，配置里的座舱 2）要建：启用了，或者超级镜子要它。 */
        final boolean cabinRear;
        /** 建几路：环视，加上要建的座舱。 */
        final int count;

        Shape(String carModel, Profile profile, Set<String> enabled, boolean mirrorCabin,
              String overrideSurround, String overrideCabinFront, String overrideCabinRear) {
            this.carModel = carModel;
            this.profile = profile;
            this.enabled = enabled;
            this.mirrorCabin = mirrorCabin;
            this.overrideSurround = overrideSurround;
            this.overrideCabinFront = overrideCabinFront;
            this.overrideCabinRear = overrideCabinRear;
            this.cabinFront = enabled.contains(CameraSlots.KEY_CABIN_FRONT);
            this.cabinRear = enabled.contains(CameraSlots.KEY_CABIN_REAR) || mirrorCabin;
            this.count = 1 + (cabinFront ? 1 : 0) + (cabinRear ? 1 : 0);
        }

        /** 存下的配置（设置页编辑的那份）。 */
        static Shape of(Context app) {
            return of(app, new ProfileStore(app).current());
        }

        /** 存下的设置，配置是 {@code profile}（主界面摆布局时取的那一份）。 */
        static Shape of(Context app, Profile profile) {
            AppConfig config = new AppConfig(app);
            // 超级镜子关着时不为它建后座舱：用不上的一路建着，车机拿相机 1 时调度照样会认它、拿它判别的路
            return new Shape(config.getCarModel(), profile, RecordSpecs.enabledCameraKeys(profile),
                    config.isRearViewEnabled() && config.rearViewWantsCabinRear(),
                    config.getCameraOverride(CameraSlots.KEY_SURROUND),
                    config.getCameraOverride(CameraSlots.KEY_CABIN_FRONT),
                    config.getCameraOverride(CameraSlots.KEY_CABIN_REAR));
        }

        /** 同一份配置，超级镜子要不要后座舱换成 {@code wants}（此刻的设置）。 */
        Shape withMirrorCabin(boolean wants) {
            return new Shape(carModel, profile, enabled, wants, overrideSurround, overrideCabinFront, overrideCabinRear);
        }

        /** 离车去掉前座舱（{@link FrontCabinOneShot}）：启用的几路拿掉 {@code key}，配置换成关掉前座舱的那份 {@code off}。 */
        Shape withoutFrontCabin(String key, Profile off) {
            Set<String> remaining = new java.util.LinkedHashSet<>(enabled);
            remaining.remove(key);
            return new Shape(carModel, off != null ? off : profile, remaining, mirrorCabin,
                    overrideSurround, overrideCabinFront, overrideCabinRear);
        }

        /** 车型和启用的几路（主界面的布局照它）。 */
        String lanesKey() {
            return lanesKeyOf(carModel, enabled);
        }

        /** 建了哪几路：{@link #lanesKey}，加上只为超级镜子建的后座舱。 */
        String key() {
            return keyOf(carModel, enabled, cabinRear);
        }

        /** 摄像头映射编成一个比较用的键（{@link ZeekrMultiPlan.Stored#overridesKey}）。 */
        String overridesKey() {
            return ZeekrMultiPlan.Stored.overridesKey(overrideSurround, overrideCabinFront, overrideCabinRear);
        }

        /**
         * 建管线、录像用到的全部（主界面接上时变了就重建）：建了哪几路（{@link #key}）、摄像头映射、那份配置里除了名字、
         * 各格摆法以外的全部（每一路的开关、预览 / 录像 / 拍照的参数、画质档）。
         */
        String pipelineKey() {
            return key() + "|" + overridesKey() + "|" + profileKey(profile, false);
        }

        /**
         * 主界面照它摆的全部（{@link #configKeyOf}）：车型、启用的几路、摄像头映射、那份配置的全部（名字、各格的摆法也算）。
         * 超级镜子要不要后座舱不算：它不在主界面上。
         */
        String configKey() {
            return lanesKey() + "|" + overridesKey() + "|" + profileKey(profile, true);
        }

        static String lanesKeyOf(String carModel, Set<String> enabled) {
            return carModel + "/" + (enabled.isEmpty() ? "-" : String.join("+", CameraSlots.openOrder(enabled)));
        }

        static String keyOf(String carModel, Set<String> enabled, boolean cabinRear) {
            return lanesKeyOf(carModel, enabled) + (cabinRear && !enabled.contains(CameraSlots.KEY_CABIN_REAR)
                    ? "/mirror:" + CameraSlots.KEY_CABIN_REAR : "");
        }

        /**
         * 一份配置摊平的样子（{@link Profile#toMap}）。{@code withView} 为 false 时名字、各格的摆法不算 —— 它们只管主界面、
         * 超级镜子怎么摆、状态条写什么，改了不用换管线。
         */
        static String profileKey(Profile profile, boolean withView) {
            if (profile == null) {
                return "-";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : profile.toMap().entrySet()) {
                String key = entry.getKey();
                if (!withView && ("name".equals(key) || key.endsWith(".lanes") || key.contains(".lane."))) {
                    continue;
                }
                sb.append(key).append('=').append(entry.getValue()).append(';');
            }
            return sb.toString();
        }
    }

    /**
     * 主界面照 {@code profile} 摆的那份配置的键（{@link Shape#configKey}）：主界面摆布局时记下，回到主界面时和存下的
     * （{@code new ProfileStore(context).current()}）比，不一样就照存下的重新摆（MainActivity.relayoutIfConfigChanged）。
     */
    public static String configKeyOf(Context context, Profile profile) {
        return Shape.of(context.getApplicationContext(), profile).configKey();
    }

    /**
     * 进程里那一份管线；没有就建（大纲 §2 阶段 6：只在这里建）。后台的调用方都调它：录像协调器、后视镜、拍照（主界面的、
     * 悬浮按钮的）。主界面接上管线调 {@link #getForMainScreen}。
     *
     * <p>对象当场交出去，各路相机要等相机线程上列完相机、探测完合成流才建出来（{@link MultiCameraManager#isBuilt}、
     * {@link #whenBuilt}）。超级镜子要后座舱而这一份里没有、或者上一次列相机出错（隔够 {@link #FAILED_REBUILD_GAP_MS}），
     * 照生效的配置放掉重建；配置变了不在这里重建（见类说明）。主线程调。</p>
     */
    public synchronized MultiCameraManager getOrInit(Context context) {
        return obtain(context, null);
    }

    /**
     * 主界面接上管线（MainActivity.initCamera）：和 {@link #getOrInit} 一样，另外主界面是照存下的配置摆的（{@code laidOut}
     * 和存下的一样）时，存下的配置就在这一刻生效，和生效的不一样就在这里换（见类说明）。在录的话那一段到头，在新的一份上自动接回
     * （RecordingCoordinator.setCameraManager）。主界面还没照新的配置重新摆（摆着设置页时管线被换了一份，回来接上）就和后台的
     * 调用方一样照用生效的，等它重新摆了再换 —— 布局和管线照的是同一份，不拿新配置配旧格子。主线程调。
     *
     * @param laidOut 主界面照着摆的那份配置的键（{@link #configKeyOf}，它摆布局时记下的）
     */
    public synchronized MultiCameraManager getForMainScreen(Context context, String laidOut) {
        return obtain(context, laidOut == null ? "" : laidOut);
    }

    /** @param laidOut 主界面接上时是它照着摆的那份配置的键；后台的调用方是 null */
    private MultiCameraManager obtain(Context context, String laidOut) {
        Context app = context.getApplicationContext();
        boolean have = cameraManager != null && !cameraManager.isReleased() && effective != null;
        Shape stored = Shape.of(app);
        // 主界面照存下的配置摆过、来接上：存下的这一刻生效（启用的几路就是它摆了格子的那几路）
        boolean commits = laidOut != null && laidOut.equals(stored.configKey());
        // 这一回照什么建（见类说明）：存下的这一刻生效时照存下的；别的（后台的调用方、还没重新摆的主界面）照生效的那份，
        // 超级镜子要不要后座舱照此刻的设置；还没有管线时（进程刚起来、退出后）照存下的
        Shape shape = commits ? stored : have ? effective.withMirrorCabin(stored.mirrorCabin) : stored;
        if (have) {
            Rebuild why = rebuildReason(shape, commits);
            if (why == Rebuild.NO) {
                if (commits && !shape.configKey().equals(effective.configKey())) {
                    // 建管线、录像用到的没变，只是名字、各格的摆法：管线不换，生效的配置换成存下的（主界面刚照它摆过）
                    BlackBox.note("管线：主界面照存下的配置重新摆了，建管线用到的没变（" + effective.key() + "），管线不换");
                    effective = shape;
                }
                return cameraManager;
            }
            if (why == Rebuild.LISTING_FAILED && failedStreak > 1) {
                AppLog.w(TAG, "管线：上一次列相机出错，放掉重建（连着第 " + failedStreak + " 次出错）");
            } else if (why == Rebuild.CONFIG) {
                noteConfigChange(effective, shape);
            } else {
                BlackBox.noteImportant("管线：" + (why == Rebuild.LISTING_FAILED ? "上一次列相机出错，放掉重建（再出错的话，隔 "
                        + FAILED_REBUILD_GAP_MS / 1000 + " 秒以上才再重建，不再逐次记）"
                        : "超级镜子要后座舱（切换范围「后视 + 后座舱」），这一份里没有（"
                        + effective.key() + " → " + shape.key() + "），放掉旧的、重建"));
            }
            cameraManager.release();
            cameraManager = null;
            for (Runnable listener : replacedListeners) {
                main.post(listener);
            }
        }
        MultiCameraManager manager = new MultiCameraManager(app);
        manager.setMaxOpenCameras(shape.count);
        // 录制模式建管线时定一次（录制模式归开发者选项）
        manager.setCodecRecordingMode(new AppConfig(app).shouldUseCodecRecording());
        cameraManager = manager;
        effective = shape;
        built = null;
        AppLog.i(TAG, "建相机管线（" + shape.key() + "，建 " + shape.count + " 路）：相机列表、合成流探测在相机线程上做");
        if (shape.cabinFront) {
            // 前座舱只用一次（暂时的规矩）：带着它建了，记下「在用」—— 没等到离车就结束的话，下一次启动关掉它的开关
            FrontCabinOneShot.inUse(app, shape.profile);
        }
        setup.execute(() -> build(app, manager, shape));
        return manager;
    }

    /** 黑匣子：主界面接上时配置变了，生效的 {@code from} 换成 {@code to}：建的几路、选的哪份配置（参数变没变）、摄像头映射。 */
    private static void noteConfigChange(Shape from, Shape to) {
        String fromId = from.profile == null ? "-" : from.profile.id;
        String toId = to.profile == null ? "-" : to.profile.id;
        boolean params = !Shape.profileKey(from.profile, false).equals(Shape.profileKey(to.profile, false));
        BlackBox.noteImportant("管线：主界面接上时配置变了（建的几路 " + from.key() + " → " + to.key()
                + "，视频流配置 " + fromId + " → " + toId + (params ? "（参数变了）" : "")
                + "，摄像头映射 " + from.overridesKey() + " → " + to.overridesKey() + "），放掉旧的、重建");
    }

    /**
     * 生效的启用的几路（拷贝；见 {@link #effective}）；没有管线是 null —— 那时问「启用的几路」的照存下的配置。
     * 哪个线程都能调。
     */
    public Set<String> effectiveLanes() {
        Shape now = effective;
        return now == null ? null : new java.util.LinkedHashSet<>(now.enabled);
    }

    /** 生效的那份配置（见 {@link #effective}；只读，不要改它）；没有管线是 null。哪个线程都能调。 */
    public Profile effectiveProfile() {
        Shape now = effective;
        return now == null ? null : now.profile;
    }

    /**
     * 离车去掉了前座舱（调度报的，通道眼里熄屏的那一刻；暂时的规矩 {@link FrontCabinOneShot}，主线程）：配置里前座舱的开关关掉，
     * 生效的那份就地一起改 —— 启用的几路拿掉它、配置换成关掉前座舱的那份：主界面照存下的配置重新摆、接上时和生效的对得上，不重建
     * （前座舱这一路还建着，没人要就关着）。后座舱没动，这一份带不带后座舱照旧。告诉主界面。
     */
    synchronized void frontCabinRetired(Context context, MultiCameraManager manager, String key) {
        boolean current = manager == cameraManager && effective != null;
        Profile off = FrontCabinOneShot.retire(context.getApplicationContext(), current ? effective.profile : null);
        if (current) {
            effective = effective.withoutFrontCabin(key, off);
        }
        for (Runnable listener : lanesListeners) {
            main.post(listener);
        }
    }

    /** 生效的几路变了就叫 {@code listener}（主线程）：主界面挂上，照存下的配置重新摆。挂了就要在销毁时摘。 */
    public void addLanesListener(Runnable listener) {
        if (listener != null && !lanesListeners.contains(listener)) {
            lanesListeners.add(listener);
        }
    }

    public void removeLanesListener(Runnable listener) {
        lanesListeners.remove(listener);
    }

    /** 手里这一份为什么要放掉重建（{@link #rebuildReason}；见类说明）。 */
    enum Rebuild {
        /** 不用：照用手里这一份。 */
        NO,
        /** 上一次列相机出错，而且隔够了 {@link CameraManagerHolder#FAILED_REBUILD_GAP_MS}（谁来要都重建）。 */
        LISTING_FAILED,
        /**
         * 配置变了（车型、选的哪份视频流配置、开的几路、各路参数、摄像头映射，或者超级镜子不再要只为它建的后座舱）：
         * 只在主界面接上管线时（{@link CameraManagerHolder#getForMainScreen}）。
         */
        CONFIG,
        /** 超级镜子要后座舱，这一份里没有（谁来要都重建，主界面照「放掉重建了」接上新的那一份）。 */
        MIRROR_CABIN
    }

    /**
     * 手里这一份要不要放掉重建（见类说明）。配置变了、超级镜子要后座舱当场重建；都没有、上一次列相机出错，隔够了才重建。
     * 还在建的那一份（built 为 null）只看配置和超级镜子。
     */
    private Rebuild rebuildReason(Shape shape, boolean forMainScreen) {
        Rebuild why = rebuildFor(shape, effective, forMainScreen);
        if (why == Rebuild.NO && built != null && built.failed) {
            return android.os.SystemClock.elapsedRealtime() - failedAtMs < FAILED_REBUILD_GAP_MS
                    ? Rebuild.NO : Rebuild.LISTING_FAILED;
        }
        return why;
    }

    /**
     * 列相机没出错时要不要重建（纯函数，测试直接调）：主界面接上时，建管线、录像用到的（{@link Shape#pipelineKey}）和生效的
     * 不一样就重建（配置变了，或者这一份里有只为超级镜子建、现在没人要的后座舱）；超级镜子要后座舱而这一份里没有，谁来要都重建。
     * 后台的调用方照用生效的那一份，多出来的那一路建了不等于开。
     */
    static Rebuild rebuildFor(Shape shape, Shape effective, boolean forMainScreen) {
        if (forMainScreen && !shape.pipelineKey().equals(effective.pipelineKey())) {
            return Rebuild.CONFIG;
        }
        if (shape.mirrorCabin && !effective.cabinRear) {
            return Rebuild.MIRROR_CABIN;
        }
        return Rebuild.NO;
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
     * 管线只在退出和换配置时才放。之后没有生效的配置，问的照存下的。
     */
    public synchronized void release() {
        if (cameraManager != null) {
            cameraManager.release();
            cameraManager = null;
        }
        effective = null;
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

        String overrideComposite = shape.overrideSurround;
        String overrideCabin1 = shape.overrideCabinFront;
        String overrideCabin2 = shape.overrideCabinRear;
        String overrides = shape.overridesKey();
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
                AppLog.i(TAG, "相机管线建好了（" + shape.key() + "）: " + plan + "，环视尺寸 " + result.compositeUse
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
