package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 通道调度的规则：每一路「该是什么样」、下一步动哪一路 —— 只有这里答（channel-logic §一、§二，项目所有者 2026-10-10 确认）。
 *
 * <h3>为什么单独成一层</h3>
 *
 * <p>2.10 动相机的有两处：登记表触发的有序开关（MultiCameraManager.reconcileCameras / openAllCameras / closeAllCameras），
 * 和看门狗的闸门（checkLiveness）。两处各看各的条件，谁先动看时机 —— 环视正在关，看门狗去重开座舱；
 * 被拿走的那一路，一边按次序开跳过它，一边每 30 秒试开它。项目所有者 2026-10-10 定：登记表只记「谁要哪几路」，
 * 一个通道调度（ChannelScheduler）是唯一动相机的地方，看门狗并进去。调度只管读事实、照做、写黑匣子；
 * 该做哪一步全在这里。纯 Java，时间由调用方传入，见 {@code ChannelRulesTest} —— 确认文档的每一句都有一条测试对着。</p>
 *
 * <h3>一轮怎么用（调度只在主线程上调）</h3>
 *
 * <ol>
 *   <li>调度把事实写进 {@link World} 和每一路的 {@link Lane}（标「事实」的字段）；丢了、开不起来用
 *       {@link #lossStarted} / {@link #lossClosed} / {@link #openFailed} 记；</li>
 *   <li>{@link #outcome} → {@link #finish}：手上那一步做完没有、到没到顶；</li>
 *   <li>{@link #settle}：记账 —— 谁要不要、保持、主动认出被拿走、到点的判定、放开、解除停手；</li>
 *   <li>{@link #next}：下一步，一次只出一步。调度照做，然后 {@link #begin}；</li>
 *   <li>{@link #askDue} 为真就问一次相机服务（只问，不开），问完 {@link #asked}。</li>
 * </ol>
 *
 * <p>标「账」的字段只由这里改，调度只读它们写黑匣子。这里不写日志、不拼句子（中文只许出现在 AppLog / BlackBox 语句里）：
 * 判定、放开、保持、停手都以 {@link Event} 交出去，下一步以 {@link Decision} 交出去，句子由调度拼。</p>
 *
 * <h3>项目所有者 2026-10-11 对大纲 §7 的答复，各落在一处判断</h3>
 *
 * <ul>
 *   <li>§7.1「保持 30 秒」每一路各自算：{@link #holding}；</li>
 *   <li>§7.4 停手的一路，相机服务报它「空闲」、或者亮屏，就解除停手、从头救（{@link #settle} 里的 liftStops）；</li>
 *   <li>§7.5 开发者「熄屏录制（阻止休眠）」开着、熄屏时没在录：照登记表关 —— 熄屏时 PREVIEW 不算（{@link #wantedBy}），也不保持；</li>
 *   <li>§7.6 主界面重建时预览画面由我们留着：输出按消费者认（{@link Out}），留着的 SurfaceTexture 是「留着的活输出」，
 *       新界面接回同一个 SurfaceTexture 就是同一个输出，通道零变动；没人接回的照「减输出能拖就拖」等下一次本来要动时摘。</li>
 * </ul>
 *
 * <p>§7.2（怎么算在开车）、§7.3（存储一类停了照旧接回）不在这一层。大纲 §7 末尾的四处解读：
 * ①「最后一句」不论多早说的、②同一次交接里被断开的另一路直接判 BLOCKED，都在 {@link CameraTaken#judge} 和这里的
 * 「先判 TAKEN」；③不在相机列表里、打开报被占用一类而最后一句不是空闲，都算被拿走（同上）；④摘死输出排在开前面，仍要等安静（{@link #next}）。</p>
 */
public final class ChannelRules {

    // ================================================================= 常量（大纲 §2 1a 的常量表）

    /** 调度自己多久醒一次；别的时候有事（登记表、相机回调、相机服务、亮熄屏、步骤计时器）就叫醒。 */
    public static final long TICK_MS = 2_000L;

    /**
     * 开、改输出、救之前，通道要安静多久：相机服务这么久没报过<b>别人引起的</b>变化（§二.6）。
     * 数在 {@link CameraTaken#QUIET_MS}：判被拿走和调度等的是同一份安静。
     */
    public static final long QUIET_MS = CameraTaken.QUIET_MS;

    /** 被拿着的一路有人要时，多久问一次相机服务它空没空（只问，不开；§二.7）。数在 {@link CameraTaken#ASK_EVERY_MS}。 */
    public static final long ASK_EVERY_MS = CameraTaken.ASK_EVERY_MS;

    /**
     * 没人要的一路，亮屏、前台服务在时留多久（§一「保持 30 秒」）。项目所有者 2026-10-09 定 30 秒：最小化、进诊断信息 / 回看，
     * 报告里 79% 在 30 秒内回来，这段时间里通道不动；2026-10-11 定每一路各自算（{@link #holding}）。
     */
    public static final long HOLD_MS = 30_000L;

    /** 车机要睡时，手上那一步最多再等多久就去关（§四：「手上那一步最多等 1 秒」）。 */
    public static final long SLEEP_GRACE_MS = 1_000L;

    /**
     * 开、改输出、救：一步最多等多久出第一帧。再久就先往下走，那一路由救援接手 ——
     * 2.10.10 起的有序开就是这个数，别让一路坏的挡住全部。
     */
    public static final long STEP_MAX_MS = 15_000L;

    /** 开一路之前最多等它的录像输出多久（从录像登记起算）：备好了就带着它开，省一次改输出。 */
    public static final long RECORD_OUTPUT_WAIT_MS = 2_000L;

    /** 环视单独关最多等多久（§二.2）；到点（相机服务卡住）不再等它，接着关座舱。 */
    public static final long CLOSE_SURROUND_MAX_MS = 10_000L;

    /** 座舱一起关最多等多久（§二.2）。 */
    public static final long CLOSE_CABINS_MAX_MS = 30_000L;

    /** 按次序关一轮的封顶：环视单独关 + 座舱一起关。 */
    public static final long ORDERED_CLOSE_MAX_MS = CLOSE_SURROUND_MAX_MS + CLOSE_CABINS_MAX_MS;

    /** 一路丢了（或开不起来）、我们这边关完以后再等多久才判：相机服务那几声「空闲 / 被占用」这时都到了。 */
    public static final long JUDGE_DELAY_MS = 300L;

    /**
     * 别人的在途（一路被接手 LOSING、一路 isBusy、另一个管理器实例在关）最多挡多久：再久就当回调不会来了，不再挡。
     * 数在 {@link SingleCamera#IN_FLIGHT_MAX_MS}。
     */
    public static final long IN_FLIGHT_MAX_MS = SingleCamera.IN_FLIGHT_MAX_MS;

    /**
     * 退出最多等多久才结束进程：手上那一步最坏占满在途封顶，再按次序关一轮，留 5 秒余量（最坏约 105 秒，平常一两秒）。
     * 2.10 用的 20 秒盖不住调度自己关相机的上限（10 + 30 秒），所以由常量算出来。
     */
    public static final long EXIT_WAIT_MAX_MS = IN_FLIGHT_MAX_MS + ORDERED_CLOSE_MAX_MS + 5_000L;

    /** 车机要睡时唤醒锁最多拉多久：手上那一步再等 1 秒，再按次序关一轮。 */
    public static final long WAKE_HOLD_MAX_MS = SLEEP_GRACE_MS + ORDERED_CLOSE_MAX_MS;

    private ChannelRules() {
    }

    // ================================================================= 输出

    /** 一路相机的输出有哪几种。拍照的 JPEG 通道常驻会话，不进这张表：它不出预览帧，也不跟着谁要不要变。 */
    public enum Kind {
        /** 主界面的预览画布。 */
        PREVIEW,
        /** 超级后视镜的画布（只有环视有）。 */
        MIRROR,
        /** 录制器的输入 SurfaceTexture：录制器停放、重新备好都是同一个，所以停录后它能留在会话里。 */
        RECORD,
        /** 不显示的出帧口：只用来补位 —— 一路要出帧、会话里却没有一个活着的输出能出帧时才加。 */
        SINK
    }

    /**
     * 一个输出：哪一种、消费者是谁。<b>按消费者认</b>：同一块画布（同一个 SurfaceTexture）再声明一次还是同一个输出 ——
     * 主界面每次画布就绪都把各路重新声明一遍，不算变化；主界面重建时我们留住的 SurfaceTexture 被新界面接回，
     * 也还是它（大纲 §7.6，项目所有者 2026-10-11）。换了一块画布才是另一个输出。
     */
    public static final class Out {
        /** 出帧口只有一个，消费者由 SingleCamera 自己建。 */
        public static final Out SINK = new Out(Kind.SINK, "sink");

        public final Kind kind;
        /** 消费者的身份：画布 / 录制器输入的 SurfaceTexture。这里只比身份，不碰它。 */
        public final Object consumer;

        public Out(Kind kind, Object consumer) {
            this.kind = Objects.requireNonNull(kind);
            this.consumer = Objects.requireNonNull(consumer);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Out)) {
                return false;
            }
            Out other = (Out) o;
            return kind == other.kind && consumer.equals(other.consumer);
        }

        @Override
        public int hashCode() {
            return 31 * kind.hashCode() + consumer.hashCode();
        }

        /** 日志用的记号（种类），不是给人读的句子。 */
        @Override
        public String toString() {
            return kind.name();
        }
    }

    // ================================================================= 一路的事实和账

    /** 一路相机的设备此刻在哪一步（调度照 SingleCamera 记）。 */
    public enum Device {
        /** 关着：从没开过、我们关完了、丢了或开不起来之后关完了。 */
        CLOSED,
        /** 在开：打开发出去了，设备还没回来。 */
        OPENING,
        /** 开着（会话配没配好看 {@link Lane#sessionSince}）。 */
        OPEN,
        /** 我们自己在关。 */
        CLOSING,
        /** 丢了（被断开、报错），我们这边那一次关还没关完：算在途，别的路都不动（§二.1）。 */
        LOSING
    }

    /** 一路的录像输出（录制器）此刻怎样，录像宿主报的。备好了就是 {@link Lane#declared} 里有 RECORD。 */
    enum RecordPrep {
        /** 没在备：不在录像计划里，或者宿主还没报。 */
        NONE,
        /** 录制器在建。 */
        PREPARING,
        /** 建不起来：开这一路不再等它。 */
        FAILED
    }

    /** 一路丢了、或者开不起来：判定要用的东西（{@link CameraTaken#judge}）。 */
    static final class Failure {

        /** 是怎么来的：决定判成普通故障时算不算救援梯子的一次。 */
        enum Origin {
            /** 开着的时候丢了：不计次数，由救援接手 —— 第一次救就是第 1 次。 */
            LOSS,
            /** 开（OPEN）那一步开不起来：算救援的第 1 次，开那一步因此不会马上再开它，由救援按 12 秒的节奏救。 */
            OPEN,
            /** 救援里重开那一下开不起来：救的时候已经算过一次，不再算。 */
            REOPEN
        }

        final CameraTaken.Reason reason;
        final Origin origin;
        /** 丢 / 开失败的那一刻。被拿走判定成立时，被拿着从这一刻算起。 */
        final long at;
        /** 出事的那一步从什么时候起：开不起来算次数时，按这一刻算（和救援自己记的那一下同一个意思）。 */
        final long stepStartedAt;
        /** 我们这边关完的时刻；0 = 还在关（开不起来的，相机层等设备关完才报，报的时候就是关完）。 */
        long closedAt;

        Failure(CameraTaken.Reason reason, Origin origin, long at, long stepStartedAt) {
            this.reason = reason;
            this.origin = origin;
            this.at = at;
            this.stepStartedAt = stepStartedAt;
        }

        @Override
        public String toString() {
            return origin + " " + reason;
        }
    }

    /**
     * 一路相机：调度填的事实，加上这里记的账。
     *
     * <p>所有时刻用同一个钟（调度定，单调、不含深睡 —— 和 {@link CameraLiveness} 一样，车停着睡一夜醒来不该算卡了一整夜）；
     * 0 一律表示「没有」。</p>
     */
    static final class Lane {
        /** 内部 key（{@link CameraSlots}：环视 front、后座舱 left、前座舱 back）和相机编号。 */
        final String key;
        final String cameraId;

        // ---- 事实：调度每一轮照 SingleCamera / CameraAvailabilityWatch / 录像宿主填 ----

        /** 设备在哪一步、从什么时候起。丢了、开不起来由 {@link ChannelRules#lossStarted} / {@link ChannelRules#lossClosed} / {@link ChannelRules#openFailed} 记。 */
        Device device = Device.CLOSED;
        long deviceSince;
        /** SingleCamera.isBusy() 从什么时候起（在开、在关、在配会话）；0 = 不忙。 */
        long busySince;
        /** 会话里此刻带着的输出（SingleCamera.sessionOutputs() 的快照）；没有会话是空的。 */
        final Set<Out> session = new LinkedHashSet<>();
        /** 会话配好的时刻；0 = 没有会话。 */
        long sessionSince;
        /** 会话里消费者已被系统收走的那几样（死输出）。 */
        final Set<Out> dead = new LinkedHashSet<>();
        /** 已声明、消费者还活着的输出（预览画布、后视镜画布、录制器的输入）。撤回了的不在这里，哪怕消费者还活着。 */
        final Set<Out> declared = new LinkedHashSet<>();
        /** 录像输出在不在备。 */
        RecordPrep record = RecordPrep.NONE;
        /** 本会话第一帧的时刻；0 = 还没有。每建一次会话归零 —— 「本会话出过画面」就是它不是 0。 */
        long firstFrameAt;
        /** 多久没有进展：出了一帧、开了相机、建好会话都算（SingleCamera.progressAgeMs）。 */
        long progressAgeMs;
        /** 不在：相机服务的缓存里没有这一路，问的时候回放里也没有（10-10 车机拿着后座舱时就是这样）。 */
        boolean absent;
        /** 相机服务对这一路说的最后一句：TRUE 空闲、FALSE 被占用、null 从没说过。不论多早说的（大纲 §7 解读 1）。 */
        Boolean serviceFree;
        /** 那一句是不是我们引起的（我们在这一路有开或关在途，见 CameraAvailabilityWatch.classify）。 */
        boolean serviceWordOurs;
        /** 那一句的时刻（问出来的、照快照改的，就是改的那一刻）。 */
        long serviceWordAt;

        // ---- 账：只由 ChannelRules 改，调度只读 ----

        /** 丢了 / 开不起来、还没判；null = 没有待判的。 */
        Failure failure;
        /** 被拿着：{@link CameraTaken.Verdict#TAKEN} / {@link CameraTaken.Verdict#BLOCKED}；null = 没有。 */
        CameraTaken.Verdict mark;
        /** 被拿着从什么时候起（丢的那一刻，或者主动认出的那一刻）。 */
        long markSince;
        /** 救援梯子（8 秒、12 秒、3 次、歇 60 秒、3 轮、停手 —— 数字照旧）。 */
        final CameraLiveness.State ladder = new CameraLiveness.State();
        /** 丢了、判成普通故障：该救。开那一步不再碰它，由救援按梯子的节奏重开（第一次救就是第 1 次）。 */
        boolean lostForRescue;
        /** 彻底停手的时刻；0 = 没停手。之后相机服务报它空闲、或者亮屏，就解除停手（§7.4）。 */
        long stoppedAt;
        /** 从什么时候起没人要（保持不算有人要）；0 = 有人要。保持 30 秒从这里算（§7.1 每一路各自算）。 */
        long unwantedSince;
        /** 这一次保持已经报过了（{@link Event.Type#HOLD_STARTED}）。 */
        boolean holdNoted;
        /**
         * 手上那一步到点没做完时，这一路那一刻的 busySince：同一个动作还挂着也不再挡（「先往下走」），
         * 它换了一个新动作（busySince 变了）照样挡。0 = 没有。
         */
        long excusedBusySince;
        /** 被拿着以来问了几次（给黑匣子「第 11 次」）。 */
        int asks;

        Lane(String key, String cameraId) {
            this.key = Objects.requireNonNull(key);
            this.cameraId = cameraId;
        }

        @Override
        public String toString() {
            return key + "(" + cameraId + ") " + device + (mark != null ? " " + mark : "")
                    + " session=" + session + (dead.isEmpty() ? "" : " dead=" + dead);
        }
    }

    /** 全局的事实（调度填）和账（这里记），加上按开的次序排好的几路。 */
    static final class World {
        /** 启用的那几路，按开的次序：环视 → 后座舱 → 前座舱（{@link CameraSlots#openOrder}）。 */
        final List<Lane> lanes;
        /** 此刻。 */
        long now;

        // ---- 事实 ----

        /** 登记表此刻有谁（{@link CameraNeeds}）。 */
        final Set<CameraNeeds.Holder> holders = EnumSet.noneOf(CameraNeeds.Holder.class);
        /** 录像计划里的那几路（MultiCameraManager.recordingPlan 的 key）。 */
        final Set<String> recordPlan = new LinkedHashSet<>();
        /** 登记表每变一次（含停了再开录）调度在 CameraNeeds 的监听里 +1：几次变化并成一轮也认得出「变过」。 */
        int needsVersion;
        boolean screenOn;
        /** 最近一次亮屏的时刻（§7.4：亮屏解除停手）。 */
        long screenOnAt;
        /** 前台服务在不在（CameraForegroundService.isRunning）：没有它就不保持 —— 后台拿着相机安卓要求有前台服务。 */
        boolean foregroundService;
        /**
         * 能用相机（大纲 §0）：主界面在前；或者前台服务是在主界面在前时起的、或那时刷新过。
         * 安卓 12 起后台才起的前台服务用不了相机（lifecycle-spec §3）。
         */
        boolean cameraUsable;
        /** 相机服务的可用性回调收到过没有（哪一路都算）：从没收到过，判不出被拿走（{@link CameraTaken#judge} 第 3 条）。 */
        boolean heardAnything;
        /** 上一次<b>别人引起的</b>变化（CameraAvailabilityWatch.lastOthersChangeAt）；0 = 没有过。 */
        long lastOthersChangeAt;
        /** 「车机要睡」从什么时候起（熄屏那张表判成现在停）；0 = 没有。亮屏时由协调器清。 */
        long sleepSince;
        /** 「退出」从什么时候起；0 = 没有。 */
        long exitSince;
        /** 另一个管理器实例在关相机（换车型时两份并存），从什么时候起；0 = 没有。 */
        long otherClosingSince;
        /** 手上那一步；null = 没有。{@link ChannelRules#begin} 记、{@link ChannelRules#finish} 清。 */
        Step step;

        // ---- 账 ----

        /** 上一轮看到的 {@link #needsVersion}。 */
        int seenNeedsVersion;
        /** RECORDING 从什么时候起登记着；0 = 没登记。开之前等录像输出从这里算。 */
        long recordingSince;
        /** 开不起来判成「现在用不了相机」的时刻；0 = 没有。主界面回到前台才清（{@link ChannelRules#mainCameForward}）。 */
        long unusableSince;
        /** 上一次问相机服务的时刻；0 = 没问过。 */
        long lastAskAt;

        World(List<Lane> lanes) {
            List<String> keys = new ArrayList<>();
            for (Lane lane : lanes) {
                keys.add(lane.key);
            }
            List<Lane> ordered = new ArrayList<>();
            for (String key : CameraSlots.openOrder(keys)) {
                for (Lane lane : lanes) {
                    if (lane.key.equals(key) && !ordered.contains(lane)) {
                        ordered.add(lane);
                        break;
                    }
                }
            }
            this.lanes = Collections.unmodifiableList(ordered);
        }

        /** 这一路；不在这一份里是 null。 */
        Lane lane(String key) {
            for (Lane lane : lanes) {
                if (lane.key.equals(key)) {
                    return lane;
                }
            }
            return null;
        }
    }

    // ================================================================= 一步、下一步、事件

    /** 下一步是什么。 */
    enum Move {
        /** 什么都不做。 */
        IDLE,
        /** 等（在途、待判、安静、录像输出……）。 */
        WAIT,
        /** 开一路，带着它要的输出（一样都不要就带出帧口）。 */
        OPEN,
        /** 改一路的输出（重配会话）。 */
        APPLY,
        /** 救一路：重建会话，或者重开。 */
        RESCUE,
        /** 关：环视单独关，或者座舱一起关。 */
        CLOSE
    }

    /** 为什么（黑匣子那一行照它写）。 */
    enum Why {
        // ---- 等 ----
        /** 手上那一步还没做完（含等第一帧）。 */
        STEP,
        /** 一路正被别人接手，我们这边的关还没关完。 */
        LOSING,
        /** 一路 isBusy（在开、在关、在配会话）。 */
        BUSY,
        /** 另一个管理器实例在关。 */
        OTHER_INSTANCE,
        /** 座舱等环视先关完（§二.2）。 */
        SURROUND_CLOSING,
        /** 一路丢了 / 开不起来，关完还不到 300 ms、或者还有别的丢失没关完：等判完。 */
        JUDGING,
        /** 相机服务 3 秒内报过别人引起的变化。 */
        QUIET,
        /** 开之前等这一路的录像输出（最多 2 秒）。 */
        RECORD_OUTPUT,
        /** 退出 / 车机要睡：等还没关完的那几路关完。 */
        CLOSING,
        // ---- 先不开（记在 Decision.deferredKey）----
        /** 现在用不了相机：不开、不重开、不计次数。 */
        UNUSABLE,
        // ---- 关 ----
        /** 没人要了，前台服务不在：不保持。 */
        UNWANTED,
        /** 保持 30 秒到点。 */
        HOLD_OVER,
        /** 熄屏：PREVIEW 不算、不保持。 */
        SCREEN_OFF,
        /** 车机要睡。 */
        SLEEP,
        /** 退出。 */
        EXIT,
        // ---- 开 ----
        /** 有人要它（{@link Decision#forWhom}）。 */
        WANTED,
        // ---- 改输出 ----
        /** 会话里有死输出：马上摘（画布被系统收走）。 */
        DEAD,
        /** 缺了要的输出：加上，多余的顺带摘掉。 */
        MISSING,
        /** 该出帧却没有一个活着的输出：补出帧口。 */
        NO_OUTLET,
        // ---- 救 ----
        /** 关着：丢了、或者开不起来。 */
        DOWN,
        /** 开着 8 秒没进展。 */
        NO_PROGRESS,
        // ---- 空闲 ----
        NOTHING
    }

    /** 救的两种做法：第一次救、本会话出过画面就重建会话（便宜），否则重开。 */
    enum Rescue {
        REBUILD,
        REOPEN
    }

    /** 手上那一步：什么、哪几路、带哪些输出、从何时开始、最多多久。 */
    static final class Step {
        final Move move;
        final List<String> keys;
        final Set<Out> outputs;
        final Rescue rescue;
        final long startedAt;
        final long maxMs;

        Step(Move move, List<String> keys, Set<Out> outputs, Rescue rescue, long startedAt, long maxMs) {
            this.move = move;
            this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
            this.outputs = Collections.unmodifiableSet(new LinkedHashSet<>(outputs));
            this.rescue = rescue;
            this.startedAt = startedAt;
            this.maxMs = maxMs;
        }

        @Override
        public String toString() {
            return move + (rescue != null ? "/" + rescue : "") + " " + keys + " " + outputs;
        }
    }

    /** {@link #next} 的答案：一次只有一步。 */
    static final class Decision {
        final Move move;
        final Why why;
        /** 动哪几路（关座舱时可能是两路）；等的时候是挡着的、或者在等的那一路，可能没有。 */
        List<String> keys = Collections.emptyList();
        /** 开、改输出、救之后会话里要的那一组输出。 */
        Set<Out> outputs = Collections.emptySet();
        /** 改输出：比现在会话多出来的、顺带摘掉的。 */
        Set<Out> added = Collections.emptySet();
        Set<Out> removed = Collections.emptySet();
        /** 救：重建还是重开。 */
        Rescue rescue;
        /** 救：梯子上的第几次。 */
        int attempt;
        /** 开：谁要它。 */
        Set<CameraNeeds.Holder> forWhom = Collections.emptySet();
        /** 等：等的是哪一种动作（开、改输出、救）；在途、待判那种是 null。 */
        Move pending;
        /** 等：最多还要等多久（毫秒；说不准是 -1）。救：多久没进展（关着的是 -1）。 */
        long ms = -1;
        /** 这一步最多等多久（开、改、救 15 秒，关环视 10 秒，关座舱 30 秒）。 */
        long maxMs;
        /** 有一路因为现在用不了相机先不开 / 不重开（主界面不在前、前台服务是后台起的）：哪一路；null = 没有。 */
        String deferredKey;
        /** 这一轮救援梯子上发生的事（歇 60 秒、停手）。 */
        final List<Event> events = new ArrayList<>();

        private Decision(Move move, Why why) {
            this.move = move;
            this.why = why;
        }

        static Decision idle() {
            return new Decision(Move.IDLE, Why.NOTHING);
        }

        static Decision waitFor(Why why, String key, long ms) {
            Decision d = new Decision(Move.WAIT, why);
            if (key != null) {
                d.keys = Collections.singletonList(key);
            }
            d.ms = ms;
            return d;
        }

        private Decision about(Move move) {
            this.pending = move;
            return this;
        }

        /** 第一路（等、开、改、救都只有一路）；没有是 null。 */
        String key() {
            return keys.isEmpty() ? null : keys.get(0);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder().append(move).append(' ').append(why).append(' ').append(keys);
            if (pending != null) {
                sb.append(" pending=").append(pending);
            }
            if (rescue != null) {
                sb.append(' ').append(rescue).append(" #").append(attempt);
            }
            if (!outputs.isEmpty()) {
                sb.append(" out=").append(outputs);
            }
            if (deferredKey != null) {
                sb.append(" deferred=").append(deferredKey);
            }
            return sb.toString();
        }
    }

    /** {@link #settle} 和 {@link #next} 记下的事，调度据此写黑匣子（一件事一行）。 */
    static final class Event {
        enum Type {
            /** 没人要了，亮屏、前台服务在：保持 30 秒（画面接着送进留着的输出）。 */
            HOLD_STARTED,
            /** 保持中又有人要了。 */
            HOLD_CANCELLED,
            /** 主动认出被拿着：我们没开、没在开，相机服务说它不在、或者被占用、不是我们。 */
            RECOGNIZED,
            /** 丢了 / 开不起来，判完了。 */
            JUDGED,
            /** 被拿着的一路放开了。 */
            RELEASED,
            /** 停手的一路解除停手、从头救。 */
            STOP_LIFTED,
            /** 连着救 3 次没用，歇 60 秒。 */
            GAVE_UP,
            /** 救了 3 轮没用，停手。 */
            STOPPED
        }

        /** 解除停手是因为什么。 */
        enum Cause {
            /** 登记表变了（项目所有者 2026-10-10）。 */
            NEEDS_CHANGED,
            /** 亮屏（项目所有者 2026-10-11，§7.4）。 */
            SCREEN_ON,
            /** 相机服务报它空闲（项目所有者 2026-10-11，§7.4）。 */
            SERVICE_FREE
        }

        final Type type;
        final List<String> keys;
        /** RECOGNIZED / JUDGED：判成什么；RELEASED：原来是什么。 */
        CameraTaken.Verdict verdict;
        /** JUDGED：判的是哪一次。 */
        Failure failure;
        /** RELEASED：被拿着多久。 */
        long ms;
        /** STOP_LIFTED：为什么。 */
        Cause cause;
        /** RECOGNIZED：是「不在」（true），还是「被占用、不是我们」（false）。 */
        boolean absent;

        Event(Type type, List<String> keys) {
            this.type = type;
            this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
        }

        Event(Type type, String key) {
            this(type, Collections.singletonList(key));
        }

        @Override
        public String toString() {
            return type + " " + keys + (verdict != null ? " " + verdict : "") + (cause != null ? " " + cause : "");
        }
    }

    // ================================================================= 相机层报来的：丢了、开不起来

    /**
     * 一路丢了（SingleCamera.onLossStarted，在阻塞的关之前发）：进 LOSING，算在途，等它关完再判。
     */
    static void lossStarted(World w, Lane lane, CameraTaken.Reason reason) {
        lane.device = Device.LOSING;
        lane.deviceSince = w.now;
        lane.failure = new Failure(reason, Failure.Origin.LOSS, w.now, w.now);
    }

    /** 丢了的那一路，我们这边关完了（SingleCamera.onLossClosed）：300 ms 后待判。关卡住、已经按当时的状态判过的，只记关完。 */
    static void lossClosed(World w, Lane lane) {
        lane.device = Device.CLOSED;
        lane.deviceSince = w.now;
        if (lane.failure != null && lane.failure.closedAt == 0) {
            lane.failure.closedAt = w.now;
        }
    }

    /**
     * 一路开不起来（SingleCamera.onOpenFailed：不在列表、打开抛异常、onError；相机层等设备关完才报）：300 ms 后待判。
     * 手上那一步是救援的重开，就不再算次数（救的时候算过了）。
     */
    static void openFailed(World w, Lane lane, CameraTaken.Reason reason) {
        Step step = w.step;
        boolean ours = step != null && step.keys.contains(lane.key);
        Failure.Origin origin = ours && step.move == Move.RESCUE ? Failure.Origin.REOPEN : Failure.Origin.OPEN;
        lane.device = Device.CLOSED;
        lane.deviceSince = w.now;
        lane.failure = new Failure(reason, origin, w.now, ours ? step.startedAt : w.now);
        lane.failure.closedAt = w.now;
    }

    /** 主界面回到前台：「现在用不了相机」作废，照常开。 */
    static void mainCameForward(World w) {
        w.unusableSince = 0;
    }

    // ================================================================= 手上那一步

    /** 手上那一步做到哪了。 */
    enum Outcome {
        /** 手上没有一步。 */
        NONE,
        RUNNING,
        /** 开、改输出、救：配好并出了第一帧；关：几路都关完了。 */
        DONE,
        /** 这一步里那一路丢了、或者开不起来（判定另外走）。 */
        FAILED,
        /** 到点（开、改、救 15 秒，关环视 10 秒，关座舱 30 秒）。 */
        TIMED_OUT
    }

    /** 手上那一步做完没有、到没到顶。 */
    static Outcome outcome(World w) {
        Step step = w.step;
        if (step == null) {
            return Outcome.NONE;
        }
        boolean late = w.now - step.startedAt >= step.maxMs;
        if (step.move == Move.CLOSE) {
            for (String key : step.keys) {
                Lane lane = w.lane(key);
                if (lane != null && lane.device != Device.CLOSED) {
                    return late ? Outcome.TIMED_OUT : Outcome.RUNNING;
                }
            }
            return Outcome.DONE;
        }
        Lane lane = w.lane(step.keys.get(0));
        if (lane == null) {
            return Outcome.DONE;
        }
        if (lane.failure != null && lane.failure.at >= step.startedAt) {
            return Outcome.FAILED;
        }
        if (lane.device == Device.OPEN && lane.firstFrameAt != 0 && lane.firstFrameAt >= step.startedAt) {
            return Outcome.DONE;
        }
        return late ? Outcome.TIMED_OUT : Outcome.RUNNING;
    }

    /**
     * 手上那一步结束了（做完、出事、到点）：清掉它。到点的那一路，同一个动作还挂着也不再挡别的路（「先往下走」）；
     * 开那一步满 15 秒没出画面，由救援接手、算第 1 次（从这一步开始的那一刻算，和救援自己记的那一下同一个意思）。
     */
    static void finish(World w, Outcome outcome) {
        Step step = w.step;
        if (step == null || outcome == Outcome.NONE || outcome == Outcome.RUNNING) {
            return;
        }
        if (outcome == Outcome.TIMED_OUT) {
            for (String key : step.keys) {
                Lane lane = w.lane(key);
                if (lane == null) {
                    continue;
                }
                lane.excusedBusySince = lane.busySince;
                if (step.move == Move.OPEN) {
                    count(lane, step.startedAt);
                }
            }
        }
        w.step = null;
    }

    /** 调度照 {@link #next} 的答案动手了：记下手上这一步。等、空闲不算一步。 */
    static void begin(World w, Decision d) {
        if (d.move == Move.IDLE || d.move == Move.WAIT) {
            return;
        }
        w.step = new Step(d.move, d.keys, d.outputs, d.rescue, w.now, d.maxMs);
    }

    // ================================================================= 记账

    /**
     * 每一轮在 {@link #next} 之前：谁要不要、保持、主动认出被拿走、到点的判定、放开、解除停手、梯子归零。
     *
     * @return 这一轮发生的事，按发生的先后（调度一件事写一行）
     */
    static List<Event> settle(World w) {
        List<Event> events = new ArrayList<>();
        boolean needsChanged = w.needsVersion != w.seenNeedsVersion;
        w.seenNeedsVersion = w.needsVersion;
        if (w.holders.contains(CameraNeeds.Holder.RECORDING)) {
            if (w.recordingSince == 0) {
                w.recordingSince = w.now;
            }
        } else {
            w.recordingSince = 0;
        }
        noteNeeds(w, events);
        recognize(w, events);
        judgeDue(w, events);
        release(w, events);
        liftStops(w, needsChanged, events);
        clearLadders(w);
        return events;
    }

    /** 谁不要了从什么时候起（保持按它算）；保持开始、取消各报一次。 */
    private static void noteNeeds(World w, List<Event> events) {
        List<String> started = new ArrayList<>();
        List<String> cancelled = new ArrayList<>();
        for (Lane lane : w.lanes) {
            if (wanted(w, lane)) {
                if (lane.holdNoted && lane.device == Device.OPEN && lane.unwantedSince != 0
                        && w.now - lane.unwantedSince < HOLD_MS) {
                    cancelled.add(lane.key);
                }
                lane.unwantedSince = 0;
                lane.holdNoted = false;
                continue;
            }
            if (lane.unwantedSince == 0) {
                lane.unwantedSince = w.now;
            }
            if (!lane.holdNoted && holding(w, lane)) {
                lane.holdNoted = true;
                started.add(lane.key);
            }
        }
        if (!started.isEmpty()) {
            events.add(new Event(Event.Type.HOLD_STARTED, started));
        }
        if (!cancelled.isEmpty()) {
            events.add(new Event(Event.Type.HOLD_CANCELLED, cancelled));
        }
    }

    /**
     * 主动认（§二.7）：我们既没开、也没在开这一路，而相机服务说它不在、或者被占用、不是我们 → 直接判 TAKEN，不碰。
     * 冷启动时车机还拿着相机 1 就是这样。不查嫌疑应用：不知道是什么时候拿的（和「注册时报的初始状态不查」同一条规矩）。
     */
    private static void recognize(World w, List<Event> events) {
        for (Lane lane : w.lanes) {
            if (lane.mark != null || lane.failure != null || lane.device != Device.CLOSED) {
                continue;
            }
            boolean othersHold = Boolean.FALSE.equals(lane.serviceFree) && !lane.serviceWordOurs;
            if (!lane.absent && !othersHold) {
                continue;
            }
            lane.mark = CameraTaken.Verdict.TAKEN;
            lane.markSince = w.now;
            lane.lostForRescue = false;
            Event event = new Event(Event.Type.RECOGNIZED, lane.key);
            event.verdict = CameraTaken.Verdict.TAKEN;
            event.absent = lane.absent;
            events.add(event);
        }
    }

    /**
     * 到点的判定：丢了 / 开不起来、关完了、又过了 300 ms。还有别的丢失在关、或者刚关完不到 300 ms 的，等全部关完一起判，
     * <b>先判 TAKEN</b> —— 另一路是不是 TAKEN 要看它们：09-27 那次相机 1 被拿走、我们的相机 2 在同一次交接里被断开，
     * 这样 2 直接判 BLOCKED，一次也不试开（大纲 §7 解读 2）。一个关卡死的，最多占住 {@link #IN_FLIGHT_MAX_MS}，
     * 之后按当时的状态判（大纲 §6 风险 5）。
     */
    private static void judgeDue(World w, List<Event> events) {
        List<Lane> due = new ArrayList<>();
        for (Lane lane : w.lanes) {
            Failure failure = lane.failure;
            if (failure == null) {
                continue;
            }
            boolean ready = failure.closedAt != 0
                    ? w.now - failure.closedAt >= JUDGE_DELAY_MS
                    : w.now - failure.at >= IN_FLIGHT_MAX_MS;
            if (!ready) {
                return;
            }
            due.add(lane);
        }
        List<Lane> rest = new ArrayList<>();
        for (Lane lane : due) {
            if (verdictOf(w, lane) == CameraTaken.Verdict.TAKEN) {
                applyVerdict(w, lane, CameraTaken.Verdict.TAKEN, events);
            } else {
                rest.add(lane);
            }
        }
        for (Lane lane : rest) {
            applyVerdict(w, lane, verdictOf(w, lane), events);
        }
    }

    /** 用 1b 的判法（{@link CameraTaken#judge}）判这一路此刻的那一次丢失 / 开失败。 */
    private static CameraTaken.Verdict verdictOf(World w, Lane lane) {
        return CameraTaken.judge(lane.failure.reason, w.heardAnything, Boolean.TRUE.equals(lane.serviceFree),
                lane.absent, anotherTaken(w, lane));
    }

    /**
     * 判完了照做（§二.5、§二.7）：
     * <ul>
     *   <li>TAKEN / BLOCKED：不计次数，记下被拿着，从丢的那一刻算；</li>
     *   <li>现在用不了相机：不计次数，记下「用不了」，等主界面回到前台；</li>
     *   <li>普通故障：开那一步开不起来的，算救援的第 1 次（开那一步因此不会马上再开它，由救援按 12 秒的节奏救）；
     *       开着丢了的，交给救援（第一次救就是第 1 次）；救援里重开没成的，救的时候已经算过。</li>
     * </ul>
     */
    private static void applyVerdict(World w, Lane lane, CameraTaken.Verdict verdict, List<Event> events) {
        Failure failure = lane.failure;
        lane.failure = null;
        switch (verdict) {
            case TAKEN:
            case BLOCKED:
                lane.mark = verdict;
                lane.markSince = failure.at;
                lane.lostForRescue = false;
                lane.asks = 0;
                break;
            case UNUSABLE:
                if (w.unusableSince == 0) {
                    w.unusableSince = w.now;
                }
                break;
            default:
                if (failure.origin == Failure.Origin.OPEN) {
                    count(lane, failure.stepStartedAt);
                } else if (failure.origin == Failure.Origin.LOSS) {
                    lane.lostForRescue = true;
                }
                break;
        }
        Event event = new Event(Event.Type.JUDGED, lane.key);
        event.verdict = verdict;
        event.failure = failure;
        events.add(event);
    }

    /**
     * 放开（{@link CameraTaken#releases}）：TAKEN 等相机服务说它空闲，BLOCKED 等再也没有 TAKEN 的路；先放 TAKEN，
     * 被它挡着的 BLOCKED 同一轮就看得到。放开以后等安静，按开的次序单独开，救援梯子从头算 —— 开那一步自然这么做。
     *
     * <p>「说它空闲」只认被拿着以后说的那一句：这一路是因为不在相机列表里判的 TAKEN 时，相机服务的最后一句可能是
     * 很早以前的「空闲」，拿它当放开就会开 → 不在列表 → 判 TAKEN → 放开 → 再开，绕圈。被拿着以后真空出来，
     * 相机服务一定会再说一句（或者问的时候照快照改）。</p>
     */
    private static void release(World w, List<Event> events) {
        for (Lane lane : w.lanes) {
            if (lane.mark == CameraTaken.Verdict.TAKEN && CameraTaken.releases(lane.mark,
                    freeSinceMarked(lane), lane.absent, anyTaken(w))) {
                free(w, lane, events);
            }
        }
        for (Lane lane : w.lanes) {
            if (lane.mark == CameraTaken.Verdict.BLOCKED && CameraTaken.releases(lane.mark,
                    freeSinceMarked(lane), lane.absent, anyTaken(w))) {
                free(w, lane, events);
            }
        }
    }

    private static boolean freeSinceMarked(Lane lane) {
        return Boolean.TRUE.equals(lane.serviceFree) && lane.serviceWordAt >= lane.markSince;
    }

    private static void free(World w, Lane lane, List<Event> events) {
        Event event = new Event(Event.Type.RELEASED, lane.key);
        event.verdict = lane.mark;
        event.ms = w.now - lane.markSince;
        lane.mark = null;
        lane.markSince = 0;
        lane.asks = 0;
        lane.ladder.released();
        lane.lostForRescue = false;
        lane.stoppedAt = 0;
        events.add(event);
    }

    /**
     * 停手的一路什么时候从头救：登记表变了（项目所有者 2026-10-10）；相机服务在停手以后报它「空闲」、或者停手以后亮过屏
     * （2026-10-11，§7.4 —— 录像开着时 RECORDING 一直登记着，登记表不会自己变，只等它的话，各路都停手之后录像会一直等下去）。
     * 关着的那一路交给救援（第一次救就是第 1 次），开着没画面的照 8 秒的规矩救。没人要了的不在这里报：
     * 梯子照「不再需要画面」归零（{@link #clearLadders}），以后有人要了从头开。
     */
    private static void liftStops(World w, boolean needsChanged, List<Event> events) {
        for (Lane lane : w.lanes) {
            if (!lane.ladder.stopped() || !wanted(w, lane)) {
                continue;
            }
            Event.Cause cause;
            boolean lifted;
            if (needsChanged) {
                cause = Event.Cause.NEEDS_CHANGED;
                lifted = lane.ladder.registerChanged();
            } else if (w.screenOnAt > lane.stoppedAt) {
                cause = Event.Cause.SCREEN_ON;
                lifted = lane.ladder.liftStop();
            } else if (Boolean.TRUE.equals(lane.serviceFree) && lane.serviceWordAt > lane.stoppedAt) {
                cause = Event.Cause.SERVICE_FREE;
                lifted = lane.ladder.liftStop();
            } else {
                continue;
            }
            if (!lifted) {
                continue;
            }
            lane.stoppedAt = 0;
            lane.lostForRescue = lane.device == Device.CLOSED;
            Event event = new Event(Event.Type.STOP_LIFTED, lane.key);
            event.cause = cause;
            events.add(event);
        }
    }

    /**
     * 梯子归零（{@link CameraLiveness.State#released}）：帧回来了，或者这一路没人要了（保持不算有人要）——
     * 「这一路不再需要画面之后重新需要」从头算，和以前一样。
     * 只看帧不看「进展」：开了相机、建好会话也算进展，拿它归零的话，开得起来却一帧不出的那一路永远爬不上梯子。
     */
    private static void clearLadders(World w) {
        for (Lane lane : w.lanes) {
            if (lane.busySince == 0) {
                lane.excusedBusySince = 0;
            }
            if (!rescuing(lane)) {
                continue;
            }
            if (!wanted(w, lane) || streaming(lane)) {
                lane.ladder.released();
                lane.lostForRescue = false;
                lane.stoppedAt = 0;
            }
        }
    }

    // ================================================================= 问

    /**
     * 该问相机服务了（只问，不开；§二.7）：被拿着的一路有人要，上一次问已经过了 {@link #ASK_EVERY_MS} ——
     * 每 30 秒一次；它刚变成有人要、而上一次问已超过 30 秒时，马上问。
     */
    static boolean askDue(World w) {
        if (winding(w)) {
            return false;
        }
        if (w.lastAskAt != 0 && w.now - w.lastAskAt < ASK_EVERY_MS) {
            return false;
        }
        for (Lane lane : w.lanes) {
            if (lane.mark != null && wanted(w, lane)) {
                return true;
            }
        }
        return false;
    }

    /** 问过了。 */
    static void asked(World w) {
        w.lastAskAt = w.now;
        for (Lane lane : w.lanes) {
            if (lane.mark != null && wanted(w, lane)) {
                lane.asks++;
            }
        }
    }

    // ================================================================= 该是什么样

    /**
     * 谁要这一路（§一的登记表）：预览 —— 登记着而且亮屏，要全部启用的路；录像 —— 录像计划里的路；
     * 拍照 —— 全部启用的路；后视镜 —— 环视。保持不在这里（{@link #holding}）。
     *
     * <p>熄屏时 PREVIEW 不算：主界面看不见。开发者「熄屏录制（阻止休眠）」开着、熄屏时没在录，于是照登记表关
     * （项目所有者 2026-10-11，§7.5）。</p>
     */
    static Set<CameraNeeds.Holder> wantedBy(World w, Lane lane) {
        Set<CameraNeeds.Holder> by = EnumSet.noneOf(CameraNeeds.Holder.class);
        for (CameraNeeds.Holder holder : w.holders) {
            if (holderWants(w, lane, holder)) {
                by.add(holder);
            }
        }
        return by;
    }

    /** 这一路有人要（保持不算）。 */
    static boolean wanted(World w, Lane lane) {
        for (CameraNeeds.Holder holder : w.holders) {
            if (holderWants(w, lane, holder)) {
                return true;
            }
        }
        return false;
    }

    private static boolean holderWants(World w, Lane lane, CameraNeeds.Holder holder) {
        switch (holder) {
            case PREVIEW:
                return w.screenOn;
            case RECORDING:
                return w.recordPlan.contains(lane.key);
            case MIRROR:
                return isSurround(lane);
            default:
                return true;
        }
    }

    /**
     * 这一路在保持（§一「保持 30 秒」）：开着、亮屏、前台服务在、没人要还不到 30 秒；车机要睡、退出时不保持。
     *
     * <p>「没人要」每一路各自算（项目所有者 2026-10-11，§7.1）：只开超级后视镜、主界面最小化时，两路座舱各自保持 30 秒，
     * 30 秒内回到主界面通道零变动；字面那种「登记表全空了才一起算」会让座舱当场关、回来再开。规则里只有这一处判断。
     * 关着的路不会为了保持去开。</p>
     */
    static boolean holding(World w, Lane lane) {
        return lane.device == Device.OPEN && w.screenOn && w.foregroundService && !winding(w)
                && !wanted(w, lane) && lane.unwantedSince != 0 && w.now - lane.unwantedSince < HOLD_MS;
    }

    /** 这一路该开（§一）：不在退出、车机不睡，并且有人要、或者在保持。 */
    static boolean shouldOpen(World w, Lane lane) {
        return !winding(w) && (wanted(w, lane) || holding(w, lane));
    }

    /** 退出、或者车机要睡：只关，不开、不改、不救。 */
    static boolean winding(World w) {
        return w.exitSince != 0 || w.sleepSince != 0;
    }

    /** 这一路要的这一种输出：预览 —— PREVIEW 登记着且亮屏；后视镜 —— MIRROR 登记着、这一路是环视；录像 —— 录像计划里有它。 */
    private static boolean wantsKind(World w, Lane lane, Kind kind) {
        switch (kind) {
            case PREVIEW:
                return w.holders.contains(CameraNeeds.Holder.PREVIEW) && w.screenOn;
            case MIRROR:
                return w.holders.contains(CameraNeeds.Holder.MIRROR) && isSurround(lane);
            case RECORD:
                return w.holders.contains(CameraNeeds.Holder.RECORDING) && w.recordPlan.contains(lane.key);
            default:
                return false;
        }
    }

    /** 要的输出：有人要、已声明、消费者还活着的那几样（预览、后视镜、录像）。 */
    static Set<Out> wantedOutputs(World w, Lane lane) {
        Set<Out> want = new LinkedHashSet<>();
        for (Out out : lane.declared) {
            if (!lane.dead.contains(out) && wantsKind(w, lane, out.kind)) {
                want.add(out);
            }
        }
        return want;
    }

    /** 会话里还活着的输出（含出帧口、留着的）。 */
    static Set<Out> live(Lane lane) {
        Set<Out> live = new LinkedHashSet<>(lane.session);
        live.removeAll(lane.dead);
        return live;
    }

    /**
     * 改会话时的新组合（§二.4「减输出能拖就拖」）：现在要的那几样，多余的顺带摘掉。要的一样都没有、这一路又还要出帧
     * （在保持、只为拍照开着）时，留着的活输出就是它的出帧口，照留、不换成出帧口 —— 停放的录像输出这样撑住保持，
     * 下一次开录直接沿用。连留着的都没有，才补出帧口。
     */
    static Set<Out> composition(World w, Lane lane) {
        Set<Out> want = wantedOutputs(w, lane);
        if (!want.isEmpty()) {
            return want;
        }
        Set<Out> live = live(lane);
        return live.isEmpty() ? Collections.singleton(Out.SINK) : live;
    }

    /** 开、重开时带的那一组：要的那几样；一样都不要就带出帧口（拍照时相机没开就是这样，§一）。 */
    static Set<Out> openOutputs(World w, Lane lane) {
        Set<Out> want = wantedOutputs(w, lane);
        return want.isEmpty() ? Collections.singleton(Out.SINK) : want;
    }

    /** 这一路在出画面：开着、本会话出过画面、8 秒内有进展。 */
    static boolean streaming(Lane lane) {
        return lane.device == Device.OPEN && lane.firstFrameAt != 0 && lane.progressAgeMs < CameraLiveness.STUCK_MS;
    }

    /** 这一路在救援梯子上（攒着次数、歇着、停手了），或者丢了等着救。 */
    static boolean rescuing(Lane lane) {
        return lane.lostForRescue || onLadder(lane);
    }

    private static boolean onLadder(Lane lane) {
        return lane.ladder.attempts() > 0 || lane.ladder.gaveUp() || lane.ladder.stopped();
    }

    /** 此刻有一路判的是 TAKEN。 */
    static boolean anyTaken(World w) {
        for (Lane lane : w.lanes) {
            if (lane.mark == CameraTaken.Verdict.TAKEN) {
                return true;
            }
        }
        return false;
    }

    private static boolean anotherTaken(World w, Lane self) {
        for (Lane lane : w.lanes) {
            if (lane != self && lane.mark == CameraTaken.Verdict.TAKEN) {
                return true;
            }
        }
        return false;
    }

    /** 几路都关好了（退出、车机要睡时调度等的就是它）。 */
    static boolean allClosed(World w) {
        for (Lane lane : w.lanes) {
            if (lane.device != Device.CLOSED) {
                return false;
            }
        }
        return true;
    }

    static boolean isSurround(Lane lane) {
        return CameraSlots.KEY_SURROUND.equals(lane.key);
    }

    /** 一路此刻怎样，给录像和后视镜读（ChannelScheduler.condition）。 */
    public enum Condition {
        /** 在出画面。 */
        STREAMING,
        /** 在开、或者等着开（排着、等安静、等录像输出、现在用不了相机）；没人要、关着的也算这一种 —— 读的人只问自己要的路。 */
        BRINGING_UP,
        /** 被别的程序拿着（TAKEN），或者被拿着的另一路挡着（BLOCKED）：不抢，等它空出来。 */
        TAKEN,
        /** 在救：丢了、开不起来、开着没画面，救援梯子在走（含歇 60 秒）。 */
        RESCUING,
        /** 救了 3 轮没用，停手了：等登记表变了、相机服务报它空闲、或者亮屏再试。 */
        GAVE_UP
    }

    static Condition condition(Lane lane) {
        if (lane.mark != null) {
            return Condition.TAKEN;
        }
        if (streaming(lane)) {
            return Condition.STREAMING;
        }
        if (lane.ladder.stopped()) {
            return Condition.GAVE_UP;
        }
        if (lane.failure != null || rescuing(lane)) {
            return Condition.RESCUING;
        }
        return Condition.BRINGING_UP;
    }

    /** 拍照不拍这一路：被拿着、或者停手了；在出画面的照拍（哪怕它的录像路不在）。 */
    static boolean excludedFromPhoto(Lane lane) {
        return !streaming(lane) && (lane.mark != null || lane.ladder.stopped());
    }

    // ================================================================= 下一步

    /**
     * 下一步：每次只出一步（§二），按这个次序找，先找到的就是它。
     *
     * <ol>
     *   <li><b>在途</b> → 等：手上那一步（含等第一帧）、一路 LOSING、一路 isBusy、另一个实例在关。后三种各封顶
     *       {@link #IN_FLIGHT_MAX_MS}。车机要睡时最多再等 {@link #SLEEP_GRACE_MS}，然后直接去关；退出照常等它做完；</li>
     *   <li><b>关</b>：环视开着或正在开、但已不该开 → 单独关它（最多 10 秒）；否则不该开的座舱一起关（最多 30 秒）。
     *       座舱等环视先关完。关不等安静。退出、车机要睡时到这里为止，不摘死输出、不改、不开、不救；</li>
     *   <li>有一路丢了 / 开不起来还没判 → 等判完（最多 300 ms，或者等别的丢失关完）；</li>
     *   <li><b>摘死输出</b>：一次一路、环视先，新组合照 {@link #composition}。排在开前面，因为 §二.4 说马上减（大纲 §7 解读 4），
     *       但照 §二.6 仍要等安静；</li>
     *   <li><b>开</b>（要安静、能用相机）：按开的次序，第一路该开、没开、没被拿着、不在救援梯子上的。它有录像要、录像输出还在备
     *       → 先等（最多 2 秒，从录像登记起算；等的时候后面的路也不开）。带着它要的输出开，一样都不要就带出帧口。
     *       被拿着的跳过，不挡后面；</li>
     *   <li><b>改输出</b>（要安静）：缺了要的输出、或者该出帧却没有活输出的那一路，环视先；</li>
     *   <li><b>救</b>（要安静；重开还要能用相机，重建不要）：有人要（保持不算）、没被拿着、关着等救或者开着 8 秒没进展的那一路，
     *       环视先。梯子照旧：第一次救、本会话出过画面就重建，否则重开；12 秒一次，3 次歇 60 秒，3 轮停手；</li>
     *   <li>都没有 → 空闲。</li>
     * </ol>
     *
     * <p>救援梯子在这里往前走（{@link CameraLiveness#step}）：调度要照答案做。先调 {@link #settle}。</p>
     */
    static Decision next(World w) {
        long now = w.now;
        Decision inFlight = inFlight(w);
        if (inFlight != null) {
            if (w.sleepSince == 0) {
                return inFlight;
            }
            long graceLeft = SLEEP_GRACE_MS - (now - w.sleepSince);
            if (graceLeft > 0) {
                inFlight.ms = inFlight.ms < 0 ? graceLeft : Math.min(inFlight.ms, graceLeft);
                return inFlight;
            }
            // 车机要睡、等满 1 秒了：不再等手上那一步，直接按次序关（关自己的次序在 closeStep 里照样守着）
        }
        Decision close = closeStep(w);
        if (close != null) {
            return close;
        }
        if (winding(w)) {
            for (Lane lane : w.lanes) {
                if (lane.device != Device.CLOSED) {
                    return Decision.waitFor(Why.CLOSING, lane.key, -1);
                }
            }
            return Decision.idle();
        }
        for (Lane lane : w.lanes) {
            Failure failure = lane.failure;
            if (failure != null) {
                long left = failure.closedAt != 0
                        ? JUDGE_DELAY_MS - (now - failure.closedAt)
                        : IN_FLIGHT_MAX_MS - (now - failure.at);
                return Decision.waitFor(Why.JUDGING, lane.key, Math.max(0, left));
            }
        }
        long quietLeft = w.lastOthersChangeAt == 0 ? 0 : QUIET_MS - (now - w.lastOthersChangeAt);
        boolean quiet = quietLeft <= 0;
        boolean usable = w.cameraUsable && w.unusableSince == 0;

        // 摘死输出
        for (Lane lane : w.lanes) {
            if (!hasDead(lane) || !reconfigurable(lane) || !shouldOpen(w, lane)) {
                continue;
            }
            if (!quiet) {
                return Decision.waitFor(Why.QUIET, lane.key, quietLeft).about(Move.APPLY);
            }
            return apply(w, lane, Why.DEAD);
        }

        // 开
        String deferredKey = null;
        for (Lane lane : w.lanes) {
            if (lane.device != Device.CLOSED || lane.mark != null || rescuing(lane) || !shouldOpen(w, lane)) {
                continue;
            }
            if (!usable) {
                deferredKey = lane.key;
                break;
            }
            if (!quiet) {
                return Decision.waitFor(Why.QUIET, lane.key, quietLeft).about(Move.OPEN);
            }
            long recordLeft = recordOutputLeft(w, lane);
            if (recordLeft > 0) {
                return Decision.waitFor(Why.RECORD_OUTPUT, lane.key, recordLeft).about(Move.OPEN);
            }
            return open(w, lane);
        }

        // 改输出
        for (Lane lane : w.lanes) {
            Why why = applyWhy(w, lane);
            if (why == null) {
                continue;
            }
            if (!quiet) {
                return withDeferred(Decision.waitFor(Why.QUIET, lane.key, quietLeft).about(Move.APPLY), deferredKey);
            }
            return withDeferred(apply(w, lane, why), deferredKey);
        }

        // 救
        List<Event> ladderEvents = new ArrayList<>();
        for (Lane lane : w.lanes) {
            long age = rescueAge(w, lane);
            if (age < 0) {
                continue;
            }
            boolean canRebuild = lane.device == Device.OPEN && lane.sessionSince != 0 && lane.firstFrameAt != 0;
            if (!usable && !(canRebuild && !onLadder(lane))) {
                // 重开要能用相机：不开、不计次数（重建会话不用开相机，梯子上第一次、出过画面的照样重建）
                if (deferredKey == null) {
                    deferredKey = lane.key;
                }
                continue;
            }
            if (!quiet) {
                if (lane.ladder.gaveUp()) {
                    continue;
                }
                Decision waiting = Decision.waitFor(Why.QUIET, lane.key, quietLeft).about(Move.RESCUE);
                waiting.events.addAll(ladderEvents);
                return withDeferred(waiting, deferredKey);
            }
            CameraLiveness.Action action = CameraLiveness.step(lane.ladder, true, age, now);
            if (action == CameraLiveness.Action.RESET) {
                Decision d = rescue(w, lane, canRebuild && lane.ladder.attempts() == 1, age);
                d.events.addAll(ladderEvents);
                return withDeferred(d, deferredKey);
            }
            if (action == CameraLiveness.Action.GIVE_UP) {
                ladderEvents.add(new Event(Event.Type.GAVE_UP, lane.key));
            } else if (action == CameraLiveness.Action.STOP) {
                lane.stoppedAt = now;
                ladderEvents.add(new Event(Event.Type.STOPPED, lane.key));
            }
        }
        Decision idle = Decision.idle();
        idle.events.addAll(ladderEvents);
        return withDeferred(idle, deferredKey);
    }

    /** 在途（§二.1）：有一样就等。 */
    private static Decision inFlight(World w) {
        long now = w.now;
        Step step = w.step;
        if (step != null && now - step.startedAt < step.maxMs) {
            return Decision.waitFor(Why.STEP, step.keys.isEmpty() ? null : step.keys.get(0),
                    step.maxMs - (now - step.startedAt));
        }
        for (Lane lane : w.lanes) {
            if (lane.device == Device.LOSING && now - lane.deviceSince < IN_FLIGHT_MAX_MS) {
                return Decision.waitFor(Why.LOSING, lane.key, IN_FLIGHT_MAX_MS - (now - lane.deviceSince));
            }
        }
        for (Lane lane : w.lanes) {
            if (lane.busySince != 0 && lane.busySince != lane.excusedBusySince
                    && now - lane.busySince < IN_FLIGHT_MAX_MS) {
                return Decision.waitFor(Why.BUSY, lane.key, IN_FLIGHT_MAX_MS - (now - lane.busySince));
            }
        }
        if (w.otherClosingSince != 0 && now - w.otherClosingSince < IN_FLIGHT_MAX_MS) {
            return Decision.waitFor(Why.OTHER_INSTANCE, null, IN_FLIGHT_MAX_MS - (now - w.otherClosingSince));
        }
        return null;
    }

    /**
     * 关（§二.2）：环视开着或正在开、但已不该开 → 单独关它；否则不该开的座舱一起关 —— 环视还在关（10 秒以内）就先等它关完。
     * 2026-10-09 黑匣子：环视比座舱晚关完的那几次，那一次关要 4–17 秒，下一次打开它一帧不出。关不等安静。
     */
    private static Decision closeStep(World w) {
        List<Lane> toClose = new ArrayList<>();
        for (Lane lane : w.lanes) {
            if ((lane.device == Device.OPEN || lane.device == Device.OPENING) && !shouldOpen(w, lane)) {
                toClose.add(lane);
            }
        }
        if (toClose.isEmpty()) {
            return null;
        }
        Lane first = toClose.get(0);
        if (isSurround(first)) {
            return close(w, Collections.singletonList(first), CLOSE_SURROUND_MAX_MS);
        }
        Lane surround = w.lane(CameraSlots.KEY_SURROUND);
        if (surround != null && (surround.device == Device.CLOSING || surround.device == Device.LOSING)
                && w.now - surround.deviceSince < CLOSE_SURROUND_MAX_MS) {
            return Decision.waitFor(Why.SURROUND_CLOSING, surround.key,
                    CLOSE_SURROUND_MAX_MS - (w.now - surround.deviceSince));
        }
        return close(w, toClose, CLOSE_CABINS_MAX_MS);
    }

    private static Decision close(World w, List<Lane> lanes, long maxMs) {
        Decision d = new Decision(Move.CLOSE, closeWhy(w, lanes.get(0)));
        List<String> keys = new ArrayList<>();
        for (Lane lane : lanes) {
            keys.add(lane.key);
        }
        d.keys = Collections.unmodifiableList(keys);
        d.maxMs = maxMs;
        return d;
    }

    /** 关的原因（黑匣子「通道：关 …（没人要 / 保持到点 / 熄屏 / 车机要睡 / 退出）」）。 */
    private static Why closeWhy(World w, Lane lane) {
        if (w.exitSince != 0) {
            return Why.EXIT;
        }
        if (w.sleepSince != 0) {
            return Why.SLEEP;
        }
        if (!w.screenOn) {
            return Why.SCREEN_OFF;
        }
        if (w.foregroundService && lane.unwantedSince != 0 && w.now - lane.unwantedSince >= HOLD_MS) {
            return Why.HOLD_OVER;
        }
        return Why.UNWANTED;
    }

    private static boolean hasDead(Lane lane) {
        for (Out out : lane.session) {
            if (lane.dead.contains(out)) {
                return true;
            }
        }
        return false;
    }

    /** 开这一路之前还要等它的录像输出多久（毫秒）；不用等是 0 或负数。 */
    private static long recordOutputLeft(World w, Lane lane) {
        if (!wantsKind(w, lane, Kind.RECORD) || lane.record == RecordPrep.FAILED || w.recordingSince == 0) {
            return 0;
        }
        for (Out out : lane.declared) {
            if (out.kind == Kind.RECORD && !lane.dead.contains(out)) {
                return 0;
            }
        }
        return RECORD_OUTPUT_WAIT_MS - (w.now - w.recordingSince);
    }

    private static Decision open(World w, Lane lane) {
        Decision d = new Decision(Move.OPEN, Why.WANTED);
        d.keys = Collections.singletonList(lane.key);
        d.outputs = openOutputs(w, lane);
        d.forWhom = wantedBy(w, lane);
        d.maxMs = STEP_MAX_MS;
        return d;
    }

    /** 这一路要不要改输出（§二.4 加输出要的时候加；不要了的活输出留着，不为它改）。 */
    private static Why applyWhy(World w, Lane lane) {
        if (!reconfigurable(lane) || !shouldOpen(w, lane)) {
            return null;
        }
        Set<Out> live = live(lane);
        if (live.isEmpty()) {
            return Why.NO_OUTLET;
        }
        return live.containsAll(wantedOutputs(w, lane)) ? null : Why.MISSING;
    }

    /**
     * 这一路能改会话：开着、会话配好了、相机层手上没有动作。手上那一步到点、相机层还挂着那个动作的
     * （{@link Lane#excusedBusySince}）不再给它改会话 —— 卡在配会话里的那一路，再改一次还是卡着，每 15 秒改一次
     * 就永远轮不到救援；没进展满 8 秒由救援接手。
     */
    private static boolean reconfigurable(Lane lane) {
        return lane.device == Device.OPEN && lane.sessionSince != 0 && lane.busySince == 0;
    }

    private static Decision apply(World w, Lane lane, Why why) {
        Set<Out> target = composition(w, lane);
        Set<Out> added = new LinkedHashSet<>(target);
        added.removeAll(lane.session);
        Set<Out> removed = new LinkedHashSet<>(lane.session);
        removed.removeAll(target);
        Decision d = new Decision(Move.APPLY, why);
        d.keys = Collections.singletonList(lane.key);
        d.outputs = target;
        d.added = added;
        d.removed = removed;
        d.maxMs = STEP_MAX_MS;
        return d;
    }

    /**
     * 这一路该不该救、多久没进展（§二.5）：有人要（保持不算）、没被拿着、没在待判、没停手，并且关着等救（丢了、开不起来），
     * 或者开着 8 秒没进展（{@link CameraLiveness#STUCK_MS}）。不救是 -1；关着的是 {@link Long#MAX_VALUE}。
     */
    private static long rescueAge(World w, Lane lane) {
        if (lane.mark != null || lane.failure != null || lane.ladder.stopped() || !wanted(w, lane)) {
            return -1;
        }
        switch (lane.device) {
            case CLOSED:
                return rescuing(lane) ? Long.MAX_VALUE : -1;
            case OPEN:
            case OPENING:
                return lane.progressAgeMs >= CameraLiveness.STUCK_MS ? lane.progressAgeMs : -1;
            default:
                return -1;
        }
    }

    private static Decision rescue(World w, Lane lane, boolean rebuild, long age) {
        Decision d = new Decision(Move.RESCUE, lane.device == Device.CLOSED ? Why.DOWN : Why.NO_PROGRESS);
        d.keys = Collections.singletonList(lane.key);
        d.rescue = rebuild ? Rescue.REBUILD : Rescue.REOPEN;
        d.outputs = rebuild ? composition(w, lane) : openOutputs(w, lane);
        d.attempt = lane.ladder.attempts();
        d.ms = age == Long.MAX_VALUE ? -1 : age;
        d.maxMs = STEP_MAX_MS;
        return d;
    }

    private static Decision withDeferred(Decision d, String deferredKey) {
        d.deferredKey = deferredKey;
        return d;
    }

    /**
     * 开那一步满 15 秒没出画面、或者开不起来判成普通故障：算救援梯子的第 1 次（按那一步开始的时刻）。
     * 已经在梯子上的（救援里的重开）不再算 —— 救的那一下算过了。
     */
    private static void count(Lane lane, long at) {
        if (onLadder(lane)) {
            return;
        }
        CameraLiveness.step(lane.ladder, true, Long.MAX_VALUE, at);
    }
}
