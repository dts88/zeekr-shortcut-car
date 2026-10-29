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
 * <p>2.10 动相机的有两处（见 {@link ChannelScheduler} 的类说明）：登记表触发的有序开关，
 * 和看门狗的闸门。两处各看各的条件，谁先动看时机 —— 环视正在关，看门狗去重开座舱；
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
 * <p>项目所有者 2026-10-12（熄屏 v3，D2）：屏幕一黑（2026-10-13 起是黑满 30 秒），两路座舱谁都不要 —— 录像、预览、拍照一样不留，亮屏再回来；
 * 环视照登记表（熄屏持续录制那张表定它录不录；2026-10-27 起看下面「熄屏放开相机」）。只有一处判断：{@link #releasedByDark}。座舱这样比环视早关 3–6 分钟；
 * 2.11.2–2.11.5 座舱就在开着的环视旁边关（D4 的例外）。项目所有者 2026-10-15 定（2.11.6）：计划里的关座舱，环视开着、此刻能用相机，
 * 先单独关环视，座舱关完它照常开回来 —— 「环视单独开、单独关」对每一次计划里的关都成立（{@link #closeStep}、{@link Why#SURROUND_FIRST}）。
 * 用不了相机时照旧（环视关了开不回来）。</p>
 *
 * <p>项目所有者 2026-10-13「黑满 30 秒再放」：通道眼里的熄屏是屏幕黑满 {@link #HOLD_MS}、或者车机要睡
 * （{@link #darkForChannel}）—— 和「保持 30 秒」同一个数。不到 30 秒又亮了，通道零变动。</p>
 *
 * <p>项目所有者 2026-10-27「熄屏放开相机」：通道算黑、没在开车（{@link #parkedDark}）时，环视也看情形放开 ——
 * 「熄屏持续录制」开着、哨兵模式开或布防、录像要它才留着（{@link #surroundKept}，账在 {@link World#surroundInDark}，
 * 只由 {@link #judgeSurroundInDark} 改）；这时只算录像（{@link #holderWants}、{@link #wantsKind}），不为拍照开相机
 * （{@link #excludedFromPhoto}），屏幕不知道黑没黑时谁都不开、不重开（{@link Why#SCREEN_UNKNOWN}），
 * 黑着、没在开车时卡住的座舱不重开（{@link #next} 第 7 条）。在开车时这些一条都不用，和以前一样。</p>
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
     *
     * <p>屏幕黑了也先留这么久（项目所有者 2026-10-13「黑满 30 秒再放」，{@link #darkForChannel}）：同一个数、同一个意思 ——
     * 30 秒内回来，通道零变动。</p>
     */
    public static final long HOLD_MS = 30_000L;

    /** 车机要睡时，手上那一步最多再等多久就去关（§四：「手上那一步最多等 1 秒」）。 */
    public static final long SLEEP_GRACE_MS = 1_000L;

    /**
     * 开、改输出、救：一步最多等多久出第一帧。再久就先往下走，那一路由救援接手 ——
     * 2.10.10 起的有序开就是这个数，别让一路坏的挡住全部。
     */
    public static final long STEP_MAX_MS = 15_000L;

    /**
     * 开一路之前最多等它的录像输出多久（从这一路的录像输出变成要的那一刻起算，{@link Lane#recordWantedSince}）：
     * 备好了就带着它开，省一次改输出。
     */
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
     * 别人的在途（一路被接手 LOSING、一台相机那一轮有一次关没返回、一路 isBusy）最多挡别的路多久：
     * 再久就当回调不会来了，别的路不再等它。数在 {@link SingleCamera#IN_FLIGHT_MAX_MS}。
     *
     * <p>只管别的路（项目所有者 2026-10-28）：被一轮占着的那一台自己（{@link #camHeld}：关没返回、开没返回），那一轮结束之前不开、
     * 关着在开的不救，不设时限；满这么久还有开或关没返回的，录像那一侧不再算它在起来、状态条说它无法录制（{@link #heldByClose}）。
     * 管线重建时换下去的那一份关一路卡住（ChannelScheduler：那一下开始关以后满这么久），新的一份照样接上，那一台照这一条不开
     * （项目所有者 2026-10-29「等旧管线这一步做完再算 60 秒」）。</p>
     */
    public static final long IN_FLIGHT_MAX_MS = SingleCamera.IN_FLIGHT_MAX_MS;

    /**
     * 我们的开、关做完之后多久以内，相机服务为这一路说的话还算我们引起的：那一句从 binder 线程上来，和相机线程报的开好 / 关完
     * 谁先到主线程没有保证，差的是同一次进相机服务的前后脚，几十毫秒的事。分类相机服务的话（ChannelScheduler.ourStepInFlight）
     * 和主动认（{@link #recognize}）用的是这同一个数。管线重建换上新的一份之后也照算：「我们关完」看账本记的那一刻
     * （{@link Lane#ourCloseAt}，谁的那一轮都算），不看新的这一路自己的账（第 2 步「只有一个调度」）。
     */
    static final long OWN_WORD_GRACE_MS = 500L;

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
        /** 超级镜子的画布：环视有；切换范围是「后视 + 后座舱」时后座舱也有（{@link World#mirrorCabin}）。 */
        MIRROR,
        /** 盲区画面的画布（只有环视有）：和预览、超级镜子挂在同一条共享的预览流上。 */
        SIDE,
        /**
         * 盲区画面在 HUD 上的画布（只有环视有，2026-10-21）：和中控屏那块一样挂在共享的预览流上、跟同一个登记方
         * （SIDE_VIEW）；共享预览流挂不下、会话配不上时和它一起最先让路。
         */
        HUD,
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
        /**
         * 这一台在账本上的那一轮（CameraRounds，谁的都算）有一次关没返回，从什么时候起（那一轮的 outstandingSince：卡住的关上面又来一次关、
         * 被关掉的开在等回调都不从头算）；0 = 没有。每一种关都算：我们关的、丢了之后的关、救援重开时关旧的、晚到的设备就地关，任何一份管线的。
         * 在途、手上那一步到点也不放过，别的路最多等 {@link ChannelRules#IN_FLIGHT_MAX_MS}（{@link ChannelRules#next} 第 1 条）。
         */
        long closingSince;
        /**
         * 被占着（{@link ChannelRules#camHeld}）：这一台在账本上有一轮还没结束，而它不是这一路手上那一轮 —— 别的管线的，
         * 或者这一路关了、还没结束的那一轮（关没返回、被关掉的开还在等回调）。第 1 步「轮次 + 账本」，项目所有者 2026-10-29 批。
         */
        boolean held;
        /**
         * 这一台在账本上的那一轮（谁的都算，这一路手上的也算）从什么时候起一直有开或关没返回（CameraRound.outstandingSince）；
         * 0 = 没有那一轮、或者它稳稳开着。满 {@link ChannelRules#IN_FLIGHT_MAX_MS} 的那一路告诉录像（{@link ChannelRules#heldByClose}）。
         */
        long outstandingSince;
        /**
         * 这一台我们最近一次关完的时刻（账本记的：一轮在下达了关、或者丢了之后结束的那一下，谁的那一轮都算 —— CameraRounds.closedAt）；
         * 0 = 进程里还没有。之后 {@link ChannelRules#OWN_WORD_GRACE_MS} 以内相机服务为它说的话还算我们的（{@link ChannelRules#stillOurs}）：
         * 管线重建时那一关是换下去的那一份做的，新的这一路自己的账（{@link #deviceSince}）里没有它（第 2 步「只有一个调度」）。
         */
        long ourCloseAt;
        /** 会话里此刻带着的输出（SingleCamera.sessionOutputs() 的快照）；没有会话是空的。 */
        final Set<Out> session = new LinkedHashSet<>();
        /** 会话配好的时刻；0 = 没有会话。 */
        long sessionSince;
        /** 会话里消费者已被系统收走的那几样（死输出）。 */
        final Set<Out> dead = new LinkedHashSet<>();
        /** 已声明、消费者还活着的输出（预览画布、后视镜画布、盲区画面画布、录制器的输入）。撤回了的不在这里，哪怕消费者还活着。 */
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
        /**
         * 那一句是不是我们引起的（说的时候我们在这一路有开或关在途，见 CameraAvailabilityWatch.classify）。只记说的那一刻：
         * 关着的一路，这个记号过了多久还作数由主动认判（{@link ChannelRules#recognize}）。
         */
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
        /**
         * 这一路的录像输出从什么时候起是要的（{@link #wantsKind} RECORD）；0 = 不要。开之前等录像输出从这里算：
         * 开录时就是 RECORDING 登记的那一刻；熄屏放开的座舱亮屏回来时是亮屏的那一轮 —— 它的录制器那时才建
         * （熄屏时不为放开的座舱建录制器，设计 v3.1 C4），从开录那一刻算的话早过了 2 秒，开了还得再改一次输出。
         */
        long recordWantedSince;

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
        /**
         * 这一份管线管的那几路，按开的次序：环视 → 后座舱 → 前座舱（{@link CameraSlots#openOrder}）。启用的那几路，
         * 加上一直建着的环视（登记表 §一 超级镜子、盲区画面那两行要它，配置里没开环视也要）。
         */
        final List<Lane> lanes;
        /** 此刻。 */
        long now;

        // ---- 事实 ----

        /** 登记表此刻有谁（{@link CameraNeeds}）。 */
        final Set<CameraNeeds.Holder> holders = EnumSet.noneOf(CameraNeeds.Holder.class);
        /** 录像计划里的那几路（MultiCameraManager.recordingPlan 的 key）。 */
        final Set<String> recordPlan = new LinkedHashSet<>();
        /**
         * 启用的那几路（配置里开着的，RecordSpecs.enabledCameraKeys，建管线那一刻的）：登记表 §一 里主界面预览、拍照要的
         * 「启用的全部」就是它。配置里没开环视时环视不在这里 —— 它照样建着，只为超级镜子、盲区画面开（项目所有者 2026-10-16）。
         * 没给是 {@link #lanes} 的全部。
         */
        final Set<String> enabled = new LinkedHashSet<>();
        /**
         * 上一轮以来登记、放开过的登记方（含停了再开录：几次变化并成一轮也在这里）。调度在 CameraNeeds 的监听里记、每一轮交进来，
         * {@link #settle} 收走：停手的一路只在要它的登记方变过时解除（{@link #wantersChanged}，2026-10-20 审查，车主批）。
         */
        final Set<CameraNeeds.Holder> changed = EnumSet.noneOf(CameraNeeds.Holder.class);
        /**
         * 通道眼里屏幕亮着（{@link ChannelRules#darkForChannel} 的反面）：屏幕真黑了还不到 30 秒、车机也没要睡，照亮着算。
         * 预览要不要、保持、熄屏放开相机、关的原因、亮屏解除停手，读的都是它。
         */
        boolean screenOn;
        /**
         * 屏幕真黑了（ScreenState.dark，物理屏幕）。黑了还不到 30 秒时 {@link #screenOn} 照亮着算，这时只有两件事看它：
         * 关着的座舱不开（{@link ChannelRules#releasedByDark}）—— 「30 秒内一步不动」，也不在黑屏里去开它（项目所有者 2026-10-13）；
         * 没在开车时卡住的座舱不重开（{@link ChannelRules#next} 第 7 条，项目所有者 2026-10-27）。
         */
        boolean screenDark;
        /**
         * 屏幕知道黑没黑（ScreenState.known）：不知道（进程刚起来、安卓刚亮，最多 2.5 秒 / 2 秒）时谁登记着都不开、不重开
         * （{@link Why#SCREEN_UNKNOWN}），环视留不留也不判（车机要睡照判，{@link ChannelRules#judgeSurroundInDark}）。
         */
        boolean screenKnown = true;
        /** 黑着时安卓也熄屏了（ScreenState.androidOff）：没在开车时就是车机要睡，环视放开（{@link ChannelRules#judgeSurroundInDark}）。 */
        boolean androidOff;
        /**
         * 哨兵模式（车辆信号核心那一份，Telemetry.latest）：0 关、1 开、2 布防；null 读不到（车辆接口没连着也是）。
         * 只有环视留不留读它（{@link ChannelRules#judgeSurroundInDark}）。
         */
        Integer sentry;
        /**
         * 「熄屏持续录制」开着（AppConfig.isScreenOffKeepRecording；开发者「熄屏录制（阻止休眠）」不算）：环视留着要它
         * （{@link ChannelRules#surroundKept}）。
         */
        boolean keepWhenDark;
        /**
         * 在开车（ScreenOffPlan.driving；车辆接口没连着算没在开车）：2026-10-27 加的熄屏规矩一条都不用 ——
         * 环视不按熄屏放开、谁登记着照算、拍照照开（{@link ChannelRules#parkedDark}）。座舱照旧黑满 30 秒放开。
         */
        boolean driving;
        /**
         * 超级镜子要后座舱：切换范围是「后视 + 后座舱」（AppConfig.rearViewWantsCabinRear，项目所有者 2026-10-19）。
         * 登记着 MIRROR 时后座舱和环视一样有人要、带超级镜子的画布；配置里没启用后座舱也一样（管线照它建了这一路）。
         * 熄屏放开座舱、车机要睡、退出照旧（{@link #releasedByDark}）。
         */
        boolean mirrorCabin;
        /** 最近一次（通道眼里）亮屏的时刻（§7.4：亮屏解除停手）。黑了不到 30 秒又亮的，没黑过，不算。 */
        long screenOnAt;
        /** 前台服务在不在（CameraForegroundService.isRunning）：没有它就不保持 —— 后台拿着相机安卓要求有前台服务。 */
        boolean foregroundService;
        /**
         * 能用相机（大纲 §0）：主界面在前；或者前台服务是在主界面在前时起的、或那时刷新过。
         * 安卓 12 起后台才起的前台服务用不了相机（lifecycle-spec §3）。
         */
        boolean cameraUsable;
        /** 主界面在前（{@link ChannelRules#applyVerdict}：开相机报「用不了」时主界面一直在前，就没什么可等）。 */
        boolean mainForeground;
        /** 相机服务的可用性回调收到过没有（哪一路都算）：从没收到过，判不出被拿走（{@link CameraTaken#judge} 第 3 条）。 */
        boolean heardAnything;
        /** 上一次<b>别人引起的</b>变化（CameraAvailabilityWatch.lastOthersChangeAt）；0 = 没有过。 */
        long lastOthersChangeAt;
        /** 「车机要睡」从什么时候起（熄屏那张表判成现在停）；0 = 没有。亮屏时由协调器清。 */
        long sleepSince;
        /** 「退出」从什么时候起；0 = 没有。 */
        long exitSince;
        /**
         * 这一份管线放掉了、要换下去（管线重建：换车型、列相机出错、启用的几路变了、超级镜子要后座舱），从什么时候起；0 = 没有。
         * 和退出一样只关、按次序关（{@link ChannelRules#winding}），关的原因写「管线重建」（{@link Why#REBUILD}）。
         * 关完（或者卡住的那一路那一下开始关以后满 {@link ChannelRules#IN_FLIGHT_MAX_MS}），唯一的那一个调度换上新的一份（第 2 步）。
         */
        long retireSince;
        /** 手上那一步；null = 没有。{@link ChannelRules#begin} 记、{@link ChannelRules#finish} 清。 */
        Step step;

        // ---- 账 ----

        /**
         * 开不起来判成「现在用不了相机」、要等主界面回到前台的时刻；0 = 没有。主界面回到前台才清（{@link ChannelRules#mainCameForward}）。
         * 只在判的时候主界面不在前、出事以后它也没回到过前台时记（{@link ChannelRules#applyVerdict}）。
         */
        long unusableSince;
        /** 主界面最近一次回到前台的时刻；0 = 还没有（{@link ChannelRules#mainCameForward}）。 */
        long mainForwardAt;
        /** 上一次问相机服务的时刻；0 = 没问过。 */
        long lastAskAt;
        /**
         * 环视这一段熄屏是哪一种（{@link SurroundInDark}，项目所有者 2026-10-27）：只由 {@link ChannelRules#judgeSurroundInDark} 改。
         * 新世界从「放开」起 —— 没见过通道刚算黑的那一下（进程在黑着时被拉起）。跟着进程、不跟着管线：唯一的那一个调度记着它
         * （ChannelScheduler 的字段），建世界时照它填，跑的那一份世界每一轮写回（退出时不写，车机要睡、睡着时也写），
         * 管线放掉重建不断熄屏录像。
         */
        SurroundInDark surroundInDark = SurroundInDark.RELEASED;
        /**
         * 环视留不留记着的上一次读得到的哨兵模式；null = 还没有（第一次读到的只当底）。和 {@link #surroundInDark} 一起跟着进程。
         */
        Integer lastSentry;
        /**
         * 这一轮 {@link #surroundInDark} 为什么变了（通道刚算黑那一下不管变没变都算）：和 {@link Event.Type#SURROUND_IN_DARK} 那一行
         * 同一个原因；这一轮没变是 null。只由 {@link ChannelRules#judgeSurroundInDark} 改，每一轮重算。调度发布通道的决定时连同它一起发
         * （ChannelScheduler.publishVerdict：录像协调器只在熄屏中哨兵模式变成开时开录）。
         */
        Event.Cause surroundCause;

        // ---- 前座舱只用一次（暂时的规矩，项目所有者 2026-10-22 定、10-23 改成只管前座舱；{@link ChannelRules#retireFrontCabin}）----

        /** 事实：这条规矩开着（FrontCabinOneShot.ACTIVE；调度每一轮照 Host 填）。 */
        boolean frontCabinOneShot;
        /**
         * 账：这次运行启用的前座舱已经去掉 —— 通道眼里熄屏的那一刻起，主界面预览、拍照、录像都不再要它，亮屏也不回来。
         * 后座舱照一般的规矩（熄屏放开、亮屏接回）。只由 {@link ChannelRules#retireFrontCabin} 改。
         */
        boolean frontCabinRetired;

        World(List<Lane> lanes) {
            this(lanes, null);
        }

        /** @param enabled 启用的那几路（{@link #enabled}）；null 是全部 */
        World(List<Lane> lanes, Set<String> enabled) {
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
            for (Lane lane : this.lanes) {
                if (enabled == null || enabled.contains(lane.key)) {
                    this.enabled.add(lane.key);
                }
            }
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
        /**
         * 一路 isBusy（在开、在关、在配会话）；或者它的相机层还在关一个设备（{@link Lane#closingSince}：重开时关旧的、晚到的设备就地关
         * —— 手上那一步到点也照等，2.11.4 第 1c 项）。
         */
        BUSY,
        /** 座舱等环视先关完（§二.2）。 */
        SURROUND_CLOSING,
        /** 一路丢了 / 开不起来，关完还不到 300 ms、或者还有别的丢失没关完：等判完。 */
        JUDGING,
        /** 相机服务 3 秒内报过别人引起的变化。 */
        QUIET,
        /** 开之前等这一路的录像输出（最多 2 秒）。 */
        RECORD_OUTPUT,
        /**
         * 屏幕不知道黑没黑（{@link World#screenKnown}，项目所有者 2026-10-27）：这一路先不开；改输出、第一次重建会话、关照做，
         * 都没有才等它。上限是屏幕判断自己的（进程起来 2.5 秒、安卓刚亮 2 秒），知道了调度马上醒。
         */
        SCREEN_UNKNOWN,
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
        /**
         * 熄屏：PREVIEW 不算、不保持（环视在熄屏时没人要了就是这个）。熄屏放开的环视（{@link #releasedByDark}，2026-10-27）也是这个。
         */
        SCREEN_OFF,
        /**
         * 熄屏放开座舱（{@link #releasedByDark}，项目所有者 2026-10-12 D2）：座舱谁都不要，跟着熄屏关。
         * 和 {@link #SCREEN_OFF} 分开，环视熄屏时关不写成「座舱放开」（设计 v3.1 C14）—— 放开的环视也一样。
         */
        DARK_RELEASE,
        /**
         * 关座舱前先单独关环视（项目所有者 2026-10-15 定，2.11.6，{@link ChannelRules#closeStep}）：计划里要关座舱（熄屏放开、保持到点、
         * 没人要），环视开着、还该开、此刻能用相机 —— 这一步只关环视。座舱接着一起关（原因照它们自己的），环视还有人要就由「开」那一步
         * 照常开回来。只是一步的原因：登记表、谁要它都不变。
         */
        SURROUND_FIRST,
        /** 车机要睡。 */
        SLEEP,
        /** 退出。 */
        EXIT,
        /**
         * 管线重建（{@link World#retireSince}：换车型、列相机出错、启用的几路变了、超级镜子要后座舱）：这一份管线换下去，
         * 和退出一样按次序关（第 2 步「只有一个调度」：以前旧的那一份调度照「退出」关，黑匣子写的是退出）。
         */
        REBUILD,
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
        /**
         * 这一轮看过「先不开」没有：走到开那一段才看（{@link #deferredKey} 才作数）；在途、关、待判、摘死输出那几种在它前面就答了，
         * 不知道 —— 调度写「先不开」那一行时不拿它们当「不再先不开」（黑匣子只在变了时写）。
         */
        boolean deferralKnown;
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

    /**
     * {@link #settle} 和 {@link #next} 记下的事，调度据此写黑匣子（一件事一行）。{@link Cause} 公开：通道对环视的熄屏决定连同原因
     * 发给录像协调器（{@link ChannelScheduler#surroundVerdictCause}）。
     */
    public static final class Event {
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
            /**
             * 这一路「谁要它」变了：要它的登记方上一轮以来登记、放开过（{@link #wantersChanged}）。录像那一侧据此再试会话带不上
             * 录像输出、在等的那一路 —— 和解除停手同一个解锁。
             */
            WANTERS_CHANGED,
            /** 连着救 3 次没用，歇 60 秒。 */
            GAVE_UP,
            /** 救了 3 轮没用，停手。 */
            STOPPED,
            /**
             * 这次运行启用的前座舱去掉了（{@link ChannelRules#retireFrontCabin}，暂时的规矩）：{@code keys} 是前座舱那一路。
             * 管线、录像、主界面据此把它从这次运行里拿掉。只报一次。
             */
            FRONT_CABIN_RETIRED,
            /**
             * 环视这一段熄屏留不留变了（{@link ChannelRules#judgeSurroundInDark}，项目所有者 2026-10-27）：{@code keys} 是环视，
             * {@code cause} 是为什么（通道刚算黑、哨兵变成开、哨兵关了、车机要睡）。通道刚算黑那一下不管变没变都报一次。
             */
            SURROUND_IN_DARK
        }

        /** 为什么：解除停手（STOP_LIFTED）、环视留不留变了（SURROUND_IN_DARK）。 */
        public enum Cause {
            /** 要它的登记方变了（项目所有者 2026-10-10；2026-10-20 起只算要这一路的登记方，{@link #wantersChanged}）。 */
            NEEDS_CHANGED,
            /** 亮屏（项目所有者 2026-10-11，§7.4）。 */
            SCREEN_ON,
            /** 相机服务报它空闲（项目所有者 2026-10-11，§7.4）。 */
            SERVICE_FREE,
            /** 通道刚算黑（黑满 30 秒那一轮；起点不明的当场）：照哨兵模式定环视留不留。 */
            DARK_ONSET,
            /** 熄屏中哨兵模式从不是开变成开或布防。 */
            SENTRY_ON,
            /** 熄屏中哨兵模式读到关。 */
            SENTRY_OFF,
            /** 黑着时安卓熄屏、没在开车：车机要睡。 */
            ANDROID_OFF
        }

        final Type type;
        final List<String> keys;
        /** RECOGNIZED / JUDGED：判成什么；RELEASED：原来是什么。 */
        CameraTaken.Verdict verdict;
        /** JUDGED：判的是哪一次。 */
        Failure failure;
        /** RELEASED：被拿着多久。 */
        long ms;
        /** STOP_LIFTED、SURROUND_IN_DARK：为什么。 */
        Cause cause;
        /** RECOGNIZED：是「不在」（true），还是「被占用、不是我们」（false）。 */
        boolean absent;
        /** JUDGED 成「现在用不了相机」：接下来怎么办；别的判定是 null。 */
        UnusableThen unusableThen;

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

    /**
     * 判成「现在用不了相机」（{@link CameraTaken.Verdict#UNUSABLE}）以后怎么办（{@link #applyVerdict}，审查 2026-10-11）：
     * 「等主界面回到前台」只对在后台出的事有意义 —— 按时间判，不按「主界面从后台回到前台」那一下判。
     */
    enum UnusableThen {
        /** 主界面不在前：不计次数，记下「用不了」，等主界面回到前台。 */
        WAIT_FOR_MAIN,
        /** 出事以后主界面回到过前台：要等的已经等到了，不计次数，照常开（救援梯子上的照梯子的节奏）。 */
        MAIN_CAME_BACK,
        /**
         * 主界面从出事前到判的时候一直在前，相机还是用不了（隐私开关、策略禁用、车机自己的限制）：没什么可等，
         * 按普通故障走救援的梯子 —— 隔 12 秒、3 次歇 60 秒、3 轮停手，不会没完没了地等下去。
         */
        LADDER
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

    /**
     * 主界面回到前台：「现在用不了相机」作废，照常开；记下这一刻 —— 之前出的事、之后才判的，也不再等（{@link #applyVerdict}）。
     */
    static void mainCameForward(World w) {
        w.unusableSince = 0;
        w.mainForwardAt = w.now;
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
     *
     * <p>「不再挡」只放过那个动作本身（{@link Lane#excusedBusySince}），不放过相机层在关的设备（{@link Lane#closingSince}）：
     * 一台还在关旧设备的相机是在途，不管它属于哪一步，到点也照等，封顶 {@link #IN_FLIGHT_MAX_MS}（{@link #next} 第 1 条；
     * 项目所有者 2026-10-14 批准，2.11.4 第 1c 项）。以前救援的重开 15 秒到点、旧设备还在关（10-13 19:55:29 那一次关了 14.0 秒），
     * 别的路就可以动了 —— 2.10.9–2.10.11 那几条规矩防的正是这种「一台在拆、另一台在动」。</p>
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
        Set<CameraNeeds.Holder> changed = EnumSet.noneOf(CameraNeeds.Holder.class);
        changed.addAll(w.changed);
        w.changed.clear();
        // 环视留不留最先判：后面每一处读 releasedByDark，刚留下的环视当轮就算要录像（recordWantedSince 从这一轮起）
        judgeSurroundInDark(w, events);
        retireFrontCabin(w, events);
        for (Lane lane : w.lanes) {
            if (!wantsKind(w, lane, Kind.RECORD)) {
                lane.recordWantedSince = 0;
            } else if (lane.recordWantedSince == 0) {
                lane.recordWantedSince = w.now;
            }
        }
        noteNeeds(w, events);
        recognize(w, events);
        judgeDue(w, events);
        release(w, events);
        liftStops(w, changed, events);
        clearLadders(w);
        return events;
    }

    /**
     * 前座舱只用一次（暂时的规矩，项目所有者 2026-10-22 定、10-23 改成只管前座舱）：车机不稳定的根因找到之前，前座舱不留到下一次用车。
     * 这次运行启用了前座舱的，通道眼里一熄屏（黑满 30 秒、或者车机要睡，{@link #darkForChannel}）—— 正是座舱本来就放开的那一刻
     * （{@link #releasedByDark}）—— 就把它从这次运行里去掉：之后亮屏也没人要（{@link #retiredFrontCabin}），关相机还是放开那一步。
     * 后座舱照一般的规矩：熄屏放开、亮屏接回。另一半在 FrontCabinOneShot：生效后就把那份配置里的前座舱开关关掉。
     * 找到根因后，这一条连同 {@link World#frontCabinOneShot}、{@link World#frontCabinRetired} 和 FrontCabinOneShot 一起拿掉。
     */
    private static void retireFrontCabin(World w, List<Event> events) {
        if (!w.frontCabinOneShot || w.frontCabinRetired || w.screenOn) {
            return;
        }
        for (Lane lane : w.lanes) {
            if (isCabinFront(lane) && w.enabled.contains(lane.key)) {
                w.frontCabinRetired = true;
                events.add(new Event(Event.Type.FRONT_CABIN_RETIRED, Collections.singletonList(lane.key)));
                return;
            }
        }
    }

    /** 这一路是这次运行已经去掉的前座舱（{@link #retireFrontCabin}）：主界面预览、拍照、录像都不要它。 */
    static boolean retiredFrontCabin(World w, Lane lane) {
        return w.frontCabinRetired && isCabinFront(lane);
    }

    /**
     * 环视这一段熄屏是哪一种（{@link World#surroundInDark}，项目所有者 2026-10-27「熄屏放开相机」）。通道对环视的熄屏决定
     * （{@link #surroundVerdict}，发给录像协调器）也用它：那时 KEPT 是真留着（含「熄屏持续录制」开着）。
     */
    public enum SurroundInDark {
        /** 照亮着算、不判：亮着、黑了还不到 30 秒、在开车。 */
        LIT,
        /** 留着：通道刚算黑时哨兵模式开或布防，或者之后变成开或布防。「熄屏持续录制」开着才真留（{@link #surroundKept}）。 */
        KEPT,
        /** 放开：哨兵模式关、读不到（通道刚算黑那一下）、之后读到关、车机要睡；新世界也从这里起。 */
        RELEASED
    }

    /** 哨兵模式开或布防（1、2）；关（0）、读不到（null）都不算。 */
    static boolean sentryOn(Integer sentry) {
        return sentry != null && (sentry == 1 || sentry == 2);
    }

    /**
     * 通道算黑、没在开车（项目所有者 2026-10-27）：2026-10-27 加的熄屏规矩只在这时用 —— 环视看留不留（{@link #releasedByDark}）、
     * 只算录像（{@link #holderWants}、{@link #wantsKind}）、不为拍照开相机（{@link #excludedFromPhoto}）。
     * 亮着、黑了不到 30 秒、在开车，都和以前一样。
     */
    static boolean parkedDark(World w) {
        return !w.screenOn && !w.driving;
    }

    /** 环视留着：「熄屏持续录制」开着，而且这一段熄屏是 {@link SurroundInDark#KEPT}。真开起来还要录像要它（{@link #holderWants}）。 */
    static boolean surroundKept(World w) {
        return w.keepWhenDark && w.surroundInDark == SurroundInDark.KEPT;
    }

    /**
     * 通道此刻对环视的熄屏决定（项目所有者 2026-10-27，录像协调器照它停录、开录，ChannelScheduler.surroundVerdict）：
     * 通道没算黑、或者在开车（{@link #parkedDark} 不成立：亮着、黑了不到 30 秒、在开车）→ {@link SurroundInDark#LIT}，不归熄屏管；
     * 留着（{@link #surroundKept}：含「熄屏持续录制」）→ KEPT；不然 RELEASED。{@link #releasedByDark} 的环视那一句就是它。
     */
    static SurroundInDark surroundVerdict(World w) {
        if (!parkedDark(w)) {
            return SurroundInDark.LIT;
        }
        return surroundKept(w) ? SurroundInDark.KEPT : SurroundInDark.RELEASED;
    }

    /**
     * 环视这一段熄屏留不留（项目所有者 2026-10-27 定，为了车机稳定：不再追求环视录到最后一刻，也不为中间不明原因的启动、亮屏去试着录），
     * 每一轮最先判（{@link #settle}），只在这里改 {@link World#surroundInDark}、{@link World#lastSentry}：
     *
     * <ol>
     *   <li>车机要睡（黑着时安卓熄屏、没在开车）→ 放开，不管哨兵模式、屏幕知不知道；上一次读数不动 —— 醒来哨兵模式还开着不算变成开，
     *       睡着时被打开的醒来读到算。照亮着算的时候直接到这里的也算通道刚算黑那一下：没读到过哨兵模式，记下这一下的读数，
     *       读不到记成关；</li>
     *   <li>屏幕不知道黑没黑 → 不判；</li>
     *   <li>亮着、黑了不到 30 秒、在开车 → 照亮着算（这期间哨兵模式变了，也等 30 秒那一下再判）；</li>
     *   <li>通道刚算黑 → 哨兵模式开或布防就留着；关、读不到就放开（读不到算没开，上一次读数记成关）；</li>
     *   <li>之后读到关 → 放开（只认读得到的关）；</li>
     *   <li>之后从不是开变成开或布防（哪个先到都算）→ 留着。读不到不变：留着的不算关，放开的不算开。</li>
     * </ol>
     *
     * <p>只看车的事实和屏幕，不看谁登记着（设计 v3.1 C4）：「在录」由 {@link #holderWants} 每一轮看。
     * 变了（通道刚算黑那一下不管变没变）记下为什么（{@link World#surroundCause}），有环视那一路时报一行。</p>
     */
    static void judgeSurroundInDark(World w, List<Event> events) {
        SurroundInDark was = w.surroundInDark;
        Event.Cause why = null;
        w.surroundCause = null;
        if (!w.screenOn && w.androidOff && !w.driving) {
            if (was == SurroundInDark.LIT && w.lastSentry == null) {
                // 照亮着算时直接到这里：也算通道刚算黑那一下 —— 这一下读得到的就是睡前那一次，读不到记成关
                w.lastSentry = w.sentry != null ? w.sentry : 0;
            }
            w.surroundInDark = SurroundInDark.RELEASED;
            why = Event.Cause.ANDROID_OFF;
        } else if (!w.screenKnown) {
            return;
        } else if (w.screenOn || w.driving) {
            w.surroundInDark = SurroundInDark.LIT;
        } else if (was == SurroundInDark.LIT) {
            w.surroundInDark = sentryOn(w.sentry) ? SurroundInDark.KEPT : SurroundInDark.RELEASED;
            if (w.sentry == null) {
                w.lastSentry = 0;
            }
            why = Event.Cause.DARK_ONSET;
        } else if (w.sentry != null && w.sentry == 0) {
            w.surroundInDark = SurroundInDark.RELEASED;
            why = Event.Cause.SENTRY_OFF;
        } else if (sentryOn(w.sentry) && w.lastSentry != null && !sentryOn(w.lastSentry)) {
            w.surroundInDark = SurroundInDark.KEPT;
            why = Event.Cause.SENTRY_ON;
        }
        if (why != Event.Cause.ANDROID_OFF && w.sentry != null) {
            w.lastSentry = w.sentry;
        }
        if (why != null && (w.surroundInDark != was || why == Event.Cause.DARK_ONSET)) {
            w.surroundCause = why;
        }
        if (w.surroundCause != null && w.lane(CameraSlots.KEY_SURROUND) != null) {
            Event event = new Event(Event.Type.SURROUND_IN_DARK, CameraSlots.KEY_SURROUND);
            event.cause = why;
            events.add(event);
        }
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
     *
     * <p>「被占用」是不是我们，按此刻判（{@link #stillOurs}），不只看说的时候：那个记号只记说的那一刻、之后同一句不再改它。</p>
     */
    private static void recognize(World w, List<Event> events) {
        for (Lane lane : w.lanes) {
            if (lane.mark != null || lane.failure != null || lane.device != Device.CLOSED) {
                continue;
            }
            boolean othersHold = Boolean.FALSE.equals(lane.serviceFree) && !stillOurs(w, lane);
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
     * 关着的这一路，相机服务那一句「被占用（我们引起的）」此刻还能不能算我们的：只在我们的哪一份还可能拿着它的时候 ——
     * 这一路刚关完、开不起来、丢了之后关完，或者这一台我们刚关完（账本记的 {@link Lane#ourCloseAt}，谁的那一轮都算：管线重建时
     * 那一关是换下去的那一份做的），{@link #OWN_WORD_GRACE_MS} 以内（为这一关说的「空闲」可能还在路上）；
     * 或者这一台被一轮占着（{@link #camHeld}，不设时限，第 1 步「轮次 + 账本」）—— 那一轮结束之前，我们的连接可能还挂在相机服务里。
     * 以前另有一扇「另一份管线还在关」的窗（最多 60 秒）：第 2 步起进程里只有一个调度，旧的那一份的关在账本上，不再另开窗。
     *
     * <p>过了这些还被占着，只能是别人（审查 2026-10-11）。两种来路：别的程序在我们开着时悄悄拿走 —— 相机服务从「我们开着」
     * 直接变成「别人开着」，中间不说「空闲」（10-10，§二.7），同一句「被占用」不再改那个记号；管线在同一个进程里重建
     * （配置里开关了一路、换车型、退出后马上又开），新的一份读到的还是旧的那一份开的时候记下的那一句。
     * 当成我们的话，新的一份不认它、又不算它被拿着，安静了就去开 —— 正是 §二.7 不许的试开。</p>
     */
    private static boolean stillOurs(World w, Lane lane) {
        return lane.serviceWordOurs
                && (w.now - Math.max(lane.deviceSince, lane.ourCloseAt) < OWN_WORD_GRACE_MS || camHeld(lane));
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
     *   <li>现在用不了相机（{@link UnusableThen}）：主界面不在前 → 不计次数，记下「用不了」，等主界面回到前台；
     *       出事以后主界面回到过前台 → 要等的已经等到了，不计次数、照常开；主界面一直在前 → 没什么可等，按普通故障走梯子；</li>
     *   <li>普通故障：开那一步开不起来的，算救援的第 1 次（开那一步因此不会马上再开它，由救援按 12 秒的节奏救）；
     *       开着丢了的，交给救援（第一次救就是第 1 次）；救援里重开没成的，救的时候已经算过。</li>
     * </ul>
     *
     * <p>「现在用不了」按时间判（审查 2026-10-11）：判在出事之后 300 ms、丢了的还要等关完（车上一次关要 2–20 秒），
     * 主界面可能在这中间回到前台。以前一律记下「用不了」、只等主界面「从后台回到前台」那一下清 —— 判的时候主界面已经在前，
     * 那一下不会再来，开、重开就一直不做，也不上梯子，直到人离开主界面再回来。出事的那一刻用那一步开始的时刻
     * （丢了的是丢的那一刻）。</p>
     */
    private static void applyVerdict(World w, Lane lane, CameraTaken.Verdict verdict, List<Event> events) {
        Failure failure = lane.failure;
        lane.failure = null;
        UnusableThen unusableThen = null;
        if (verdict == CameraTaken.Verdict.UNUSABLE) {
            unusableThen = w.mainForwardAt > failure.stepStartedAt ? UnusableThen.MAIN_CAME_BACK
                    : w.mainForeground ? UnusableThen.LADDER : UnusableThen.WAIT_FOR_MAIN;
        }
        switch (verdict) {
            case TAKEN:
            case BLOCKED:
                lane.mark = verdict;
                lane.markSince = failure.at;
                lane.lostForRescue = false;
                lane.asks = 0;
                break;
            case UNUSABLE:
                if (unusableThen == UnusableThen.LADDER) {
                    rescueLater(lane, failure);
                } else if (unusableThen == UnusableThen.WAIT_FOR_MAIN && w.unusableSince == 0) {
                    w.unusableSince = w.now;
                }
                break;
            default:
                rescueLater(lane, failure);
                break;
        }
        Event event = new Event(Event.Type.JUDGED, lane.key);
        event.verdict = verdict;
        event.unusableThen = unusableThen;
        event.failure = failure;
        events.add(event);
    }

    /**
     * 按普通故障交给救援：开那一步开不起来的，算救援的第 1 次；开着丢了的，交给救援（第一次救就是第 1 次）；
     * 救援里重开没成的，救的时候已经算过。
     */
    private static void rescueLater(Lane lane, Failure failure) {
        if (failure.origin == Failure.Origin.OPEN) {
            count(lane, failure.stepStartedAt);
        } else if (failure.origin == Failure.Origin.LOSS) {
            lane.lostForRescue = true;
        }
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
     * 停手的一路什么时候从头救：要它的登记方变了（项目所有者 2026-10-10；2026-10-20 起只看要这一路的，{@link #wantersChanged}）；
     * 相机服务在停手以后报它「空闲」、或者停手以后亮过屏（2026-10-11，§7.4 —— 录像开着时 RECORDING 一直登记着，登记表不会自己变，
     * 只等它的话，各路都停手之后录像会一直等下去）。关着的那一路交给救援（第一次救就是第 1 次），开着没画面的照 8 秒的规矩救。
     * 没人要了的不在这里报：梯子照「不再需要画面」归零（{@link #clearLadders}），以后有人要了从头开。
     *
     * <p>要它的登记方变了的每一路（不管停没停手）另报一件 {@link Event.Type#WANTERS_CHANGED}：录像那一侧照同一个解锁再试。</p>
     */
    private static void liftStops(World w, Set<CameraNeeds.Holder> changed, List<Event> events) {
        List<String> concerned = new ArrayList<>();
        for (Lane lane : w.lanes) {
            boolean wantersChanged = wantersChanged(w, lane, changed);
            if (wantersChanged) {
                concerned.add(lane.key);
            }
            if (!lane.ladder.stopped() || !wanted(w, lane)) {
                continue;
            }
            Event.Cause cause;
            boolean lifted;
            if (wantersChanged) {
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
        if (!concerned.isEmpty()) {
            events.add(new Event(Event.Type.WANTERS_CHANGED, concerned));
        }
    }

    /**
     * 这一路「谁要它」变了：上一轮以来登记、放开过的登记方（{@link World#changed}）里有要它的（{@link #holderWants}，
     * 不管此刻登没登记着 —— 停了再开录并成一轮也算）。盲区画面只要环视：挂 D 挡、R 挡时它登记、放开，只算环视变了，
     * 停手的座舱不为它重开（2026-10-20 审查，车主批；以前登记表一变，所有停手的路都解除）。
     */
    static boolean wantersChanged(World w, Lane lane, Set<CameraNeeds.Holder> changed) {
        for (CameraNeeds.Holder holder : changed) {
            if (holderWants(w, lane, holder)) {
                return true;
            }
        }
        return false;
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
     * 谁要这一路（§一的登记表）：预览 —— 登记着而且亮屏，要启用的全部（{@link World#enabled}）；录像 —— 录像计划里的路；
     * 拍照 —— 启用的全部；后视镜 —— 环视，切换范围是「后视 + 后座舱」时还有后座舱（{@link #mirrorWants}）；盲区画面 —— 环视；
     * 后视镜、盲区画面要的路配置里没开也要（项目所有者 2026-10-16、2026-10-19）。保持不在这里（{@link #holding}）。
     *
     * <p>熄屏时 PREVIEW 不算：主界面看不见。开发者「熄屏录制（阻止休眠）」开着、熄屏时没在录，于是照登记表关
     * （项目所有者 2026-10-11，§7.5）。</p>
     *
     * <p>熄屏时座舱谁都不要（{@link #releasedByDark}，项目所有者 2026-10-12 D2）：预览、录像、拍照、后视镜一律不算。
     * 环视（2026-10-27）：通道算黑、没在开车（{@link #parkedDark}）时只算录像，而且要留着（{@link #surroundKept}）——
     * 超级镜子、盲区画面、拍照登记着也不算，亮屏又算；在开车时照登记表。</p>
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
        if (releasedByDark(w, lane)) {
            return false;
        }
        // 通道算黑、没在开车：只算录像（项目所有者 2026-10-27：熄屏期间不为拍照、超级镜子、盲区画面开相机）
        if (parkedDark(w) && holder != CameraNeeds.Holder.RECORDING) {
            return false;
        }
        switch (holder) {
            case PREVIEW:
                return w.screenOn && w.enabled.contains(lane.key) && !retiredFrontCabin(w, lane);
            case RECORDING:
                return w.recordPlan.contains(lane.key) && !retiredFrontCabin(w, lane);
            case MIRROR:
                return mirrorWants(w, lane);
            case SIDE_VIEW:
                return isSurround(lane);
            default:   // 拍照
                return w.enabled.contains(lane.key) && !retiredFrontCabin(w, lane);
        }
    }

    /**
     * 这一路在保持（§一「保持 30 秒」）：开着、亮屏、前台服务在、没人要还不到 30 秒；车机要睡、退出时不保持。
     *
     * <p>「没人要」每一路各自算（项目所有者 2026-10-11，§7.1）：只开超级镜子、主界面最小化时，两路座舱各自保持 30 秒，
     * 30 秒内回到主界面通道零变动；字面那种「登记表全空了才一起算」会让座舱当场关、回来再开。规则里只有这一处判断。
     * 关着的路不会为了保持去开。</p>
     *
     * <p>「亮屏」是通道眼里的（{@link World#screenOn}）：屏幕黑了还不到 30 秒也算亮着 —— 那时没人要了的（熄屏持续录制关着、
     * 10 秒后停了录）保持到屏幕黑满 30 秒，和座舱放开同一刻关（项目所有者 2026-10-13）。</p>
     */
    static boolean holding(World w, Lane lane) {
        return lane.device == Device.OPEN && w.screenOn && w.foregroundService && !winding(w)
                && !wanted(w, lane) && lane.unwantedSince != 0 && w.now - lane.unwantedSince < HOLD_MS;
    }

    /** 这一路该开（§一）：不在退出、车机不睡、这一份管线不在换下去，并且有人要、或者在保持。 */
    static boolean shouldOpen(World w, Lane lane) {
        return !winding(w) && (wanted(w, lane) || holding(w, lane));
    }

    /** 退出、车机要睡、或者这一份管线要换下去（{@link World#retireSince}）：只关，不开、不改、不救。 */
    static boolean winding(World w) {
        return w.exitSince != 0 || w.sleepSince != 0 || w.retireSince != 0;
    }

    /**
     * 通道眼里屏幕黑了（{@link World#screenOn} 的反面，调度每一轮照它填）：屏幕真黑了满 {@link #HOLD_MS}，或者车机要睡
     * （黑着时安卓熄屏 —— 这台车上就是车机下电，那一下照旧马上关）。项目所有者 2026-10-13「黑满 30 秒再放」：
     * 屏幕黑了不到 30 秒，相机一样不动 —— 开着的接着开、输出不改、录像不分段、录制器不停放，关着的座舱也不开
     * （{@link #releasedByDark} 看 {@link World#screenDark}）；这期间亮了，什么都没发生过。
     * 黑满 30 秒才照熄屏的规矩来（座舱放开、没留着的环视放开 {@link #releasedByDark}、预览不算、不保持）。
     *
     * <p>为什么：10-13 20:10:11 车门开关的那 4.7 秒（哨兵模式开着）里我们关了后座舱、亮屏又开，重开 15 秒没画面，
     * 救援的那一次关又卡了 6.2 秒；车机要拿后座舱是在屏幕黑了 98 秒以后（98.5、148 秒），30 秒还赶在它前面。
     * 和「保持 30 秒」同一个数、同一个意思（没人要的一路亮屏时留 30 秒，屏幕黑了的也先留 30 秒），不另立一个数。</p>
     *
     * <p>只挪相机：录像那张表（熄屏持续录制、10 秒后停、车机要睡现在停，RecordingCoordinator / ScreenOffPlan）照屏幕真黑的那一刻判。
     * 起点不明（进程起来时已经黑着、车辆信号读到时已经黑着）按满 30 秒算。</p>
     *
     * @param dark       屏幕真黑了（ScreenState.dark）
     * @param darkForMs  黑了多久；起点不明是负数
     * @param androidOff 黑着时安卓也熄屏了（车机要睡 / 睡着）
     */
    static boolean darkForChannel(boolean dark, long darkForMs, boolean androidOff) {
        return dark && (androidOff || darkForMs < 0 || darkForMs >= HOLD_MS);
    }

    /**
     * 屏幕真黑了、还不到 30 秒（{@link #darkForChannel}）：再过多久通道就算黑了（毫秒）—— 调度到点醒那一下，不等下一个两秒。
     * 不用等（亮着、已经算黑了）是 -1。
     */
    static long untilDarkForChannel(boolean dark, long darkForMs, boolean androidOff) {
        return !dark || darkForChannel(dark, darkForMs, androidOff) ? -1 : HOLD_MS - darkForMs;
    }

    /**
     * 这一路被熄屏放开了：熄屏时座舱谁都不要它（环视看下面最后一段；项目所有者 2026-10-12 D2：屏幕一黑两路座舱都放开 ——
     * 录像、预览、拍照，一样不留。为了稳：车机熄屏约 98 秒后要拿后座舱相机 1，那时我们拿着它，关它要卡 5–8 秒，
     * 4 次里 3 次环视跟着断了 35–43 秒；我们不拿着它的那一晚车机拿了 6 次，环视一次没断）。亮屏又都要了。
     * 「熄屏」是通道眼里的（{@link World#screenOn}）：屏幕黑满 30 秒、或者车机要睡（{@link #darkForChannel}，项目所有者 2026-10-13）。
     * 屏幕真黑了还不到 30 秒（{@link World#screenDark}）时，关着的座舱也算放开：开着的照旧，关着的不在黑屏里开 ——
     * 「30 秒内一步不动」（项目所有者 2026-10-13；黑屏里用不着它，哨兵模式开着时开后座舱正是 10-12、10-13 两次一帧不出的那一种操作），
     * 这期间来的开录、拍照、接回只开环视（和 2.11.2 一样），黑满 30 秒时也就没有座舱可放。在开的、在关的照旧走完。
     *
     * <p>和谁登记着无关，只看屏幕和是不是环视（黑了不到 30 秒时再看它关没关着）：{@link #holderWants}、{@link #wantsKind} 开头的那一道闸、录像的事实
     * （RecordingLanes.Facts#releasedByDark）、拍照不拍哪几路（{@link #excludedFromPhoto}）、关的原因（{@link #closeWhy}）
     * 问的都是这一句（设计 v3.1 C4）—— 按「有没有人要」问的话，录像刚登记、拍照刚登记的那一下读到的是上一轮的登记表，
     * 开录落定不了、拍照一路不拍就收尾。</p>
     *
     * <p>按「不是环视那一路」认：非极氪的自定义车型上，熄屏时除了环视那一路，别的路一律放开（记进规格）。</p>
     *
     * <p>环视（项目所有者 2026-10-27「熄屏放开相机」）：通道算黑、没在开车（{@link #parkedDark}），又没留着（{@link #surroundKept}：
     * 「熄屏持续录制」开着、哨兵模式开或布防）就放开（通道的决定 {@link #surroundVerdict} 是 RELEASED，录像协调器照它停录），
     * 和座舱同一轮 —— 关的次序照旧环视先单独关（原因 {@link Why#SCREEN_OFF}），
     * 关完座舱一起关，什么都不开回来。黑了不到 30 秒、在开车时不放开（照登记表）。它也只看车的事实和设置，不看谁登记着。</p>
     */
    static boolean releasedByDark(World w, Lane lane) {
        if (isSurround(lane)) {
            return surroundVerdict(w) == SurroundInDark.RELEASED;
        }
        return !w.screenOn || w.screenDark && lane.device == Device.CLOSED;
    }

    /**
     * 录像留着这一路的录像输出（录制器）：RECORDING 登记着、这一路在录像计划里（设计 v3.1 C13）。相机关着的一路（我们关的、
     * 丢了的、被拿着的、熄屏放开的），录制器在这期间一直停放着，相机回来时同一个录制器直接加入；不再留着了 ——
     * 停了录、这一路不在计划里 —— 调度当轮就告诉录像那一侧它「随相机关了」（{@link SingleCamera.Drop#CLOSED}），
     * 它才放掉那个录制器（ChannelScheduler 每一轮的那一条规矩）。
     */
    static boolean keepsRecordOutput(World w, Lane lane) {
        return w.holders.contains(CameraNeeds.Holder.RECORDING) && w.recordPlan.contains(lane.key)
                && !retiredFrontCabin(w, lane);
    }

    /**
     * 这一路要的这一种输出：预览 —— PREVIEW 登记着、亮屏、这一路启用；后视镜 —— MIRROR 登记着、这一路是环视
     * （切换范围是「后视 + 后座舱」时还有后座舱）；
     * 盲区画面（中控屏、HUD 两块）—— SIDE_VIEW 登记着、这一路是环视；录像 —— 录像计划里有它。
     * 熄屏放开的一路一样都不要（{@link #releasedByDark}）；通道算黑、没在开车时留着的环视只要录像输出（{@link #parkedDark}，
     * 2026-10-27：不为超级镜子、盲区画面在黑屏里重建会话、多切一个文件）。
     */
    private static boolean wantsKind(World w, Lane lane, Kind kind) {
        if (releasedByDark(w, lane) || parkedDark(w) && kind != Kind.RECORD) {
            return false;
        }
        switch (kind) {
            case PREVIEW:
                return w.holders.contains(CameraNeeds.Holder.PREVIEW) && w.screenOn && w.enabled.contains(lane.key)
                        && !retiredFrontCabin(w, lane);
            case MIRROR:
                return w.holders.contains(CameraNeeds.Holder.MIRROR) && mirrorWants(w, lane);
            case SIDE:
            case HUD:
                return w.holders.contains(CameraNeeds.Holder.SIDE_VIEW) && isSurround(lane);
            case RECORD:
                return w.holders.contains(CameraNeeds.Holder.RECORDING) && w.recordPlan.contains(lane.key)
                        && !retiredFrontCabin(w, lane);
            default:
                return false;
        }
    }

    /** 要的输出：有人要、已声明、消费者还活着的那几样（预览、后视镜、盲区画面、录像）。 */
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

    /** 会话里的一样输出此刻算哪一类（大纲 §0 的名词；{@link #outState}）。 */
    enum OutState {
        /** 要的：有人要、已声明、消费者还活着（{@link #wantedOutputs}）。出帧口也算：它只在没有别的活输出时才加。 */
        WANTED,
        /** 留着的：没人要了，消费者还活着 —— 照「减输出能拖就拖」等下一次本来要动、或者关相机时一起摘（§二.4）。 */
        KEPT,
        /** 死的：消费者已被系统收走，马上摘。 */
        DEAD
    }

    /**
     * 会话里的 {@code out} 此刻算哪一类：诊断报告的「Channel」一节、卡顿现场、关相机那一行（「带走留着的输出」）都照它分。
     * 只是分类，动不动它照旧由 {@link #next} 定。出帧口是我们自己的：在会话里就算要的 —— 有了要的输出，
     * 下一次改输出顺带摘掉它（{@link #composition}）。
     */
    static OutState outState(World w, Lane lane, Out out) {
        if (lane.dead.contains(out)) {
            return OutState.DEAD;
        }
        if (out.kind == Kind.SINK || wantedOutputs(w, lane).contains(out)) {
            return OutState.WANTED;
        }
        return OutState.KEPT;
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

    /** 后座舱那一路（超级镜子「后视 + 后座舱」要它，{@link World#mirrorCabin}）。 */
    static boolean isCabinRear(Lane lane) {
        return CameraSlots.KEY_CABIN_REAR.equals(lane.key);
    }

    /** 前座舱那一路（暂时只用一次，{@link #retireFrontCabin}）。 */
    static boolean isCabinFront(Lane lane) {
        return CameraSlots.KEY_CABIN_FRONT.equals(lane.key);
    }

    /**
     * 后视镜要哪几路（登记着 MIRROR 时）：环视；切换范围是「后视 + 后座舱」时还有后座舱。谁要（{@link #holderWants}）和要哪种输出
     * （{@link #wantsKind}）都问这一处。
     */
    static boolean mirrorWants(World w, Lane lane) {
        return isSurround(lane) || w.mirrorCabin && isCabinRear(lane);
    }

    /** 一路此刻怎样，给录像和后视镜读（ChannelScheduler.condition）。 */
    public enum Condition {
        /** 在出画面。 */
        STREAMING,
        /**
         * 在开、或者等着开（排着、等安静、等录像输出、现在用不了相机）；没人要、关着的也算这一种 —— 读的人只问自己要的路。
         * 录像计划里的座舱熄屏时（2026-10-27 起没留着的环视也是）也是这一种，录像另读 {@link ChannelRules#releasedByDark}
         * （RecordingLanes.Facts）：它一时半会儿不会开。
         */
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

    /**
     * 此刻能用相机（大纲 §0）：能开、能重开。开相机报过「现在用不了」、判的时候主界面不在前的，主界面回到前台之前也不算
     * （{@link #mainCameForward}、{@link #applyVerdict}）。
     */
    static boolean usable(World w) {
        return w.cameraUsable && w.unusableSince == 0;
    }

    /**
     * 这一路该开、关着，而此刻用不了相机：调度不会开它，主界面回到前台之前它起不来（{@link #condition} 照旧说它在起来）；
     * 或者它这一台那一轮满 {@link #IN_FLIGHT_MAX_MS} 还有开或关没返回（{@link #heldByClose}）：那一下返回之前调度不会开它。
     * 给录像读：这样的一路不算在起来，不然录像一直是「准备中」（RecordingLanes.Facts#cannotOpen）。
     * 60 秒以内照旧算在起来：平常退出又马上打开，那一次关一两秒就返回，录像不闪一下「等待摄像头」。
     */
    static boolean cannotOpen(World w, Lane lane) {
        return lane.device == Device.CLOSED && !usable(w) && shouldOpen(w, lane) || heldByClose(w, lane);
    }

    /**
     * 被占着（第 1 步「轮次 + 账本」，项目所有者 2026-10-28、10-29）：这一台在账本上有一轮还没结束，而它不是这一路手上那一轮
     * （{@link Lane#held}）—— 别的管线的那一轮（换车型、退出后马上又开），或者这一路关了、还没结束的那一轮（关没返回、被关掉的开还在等回调）。
     * 一台相机我们上一次关它（或者开它）还没返回，App 里谁都不开它，直到那一轮结束、或者进程结束 —— 不设时限；相机服务说它空闲不算数
     * （同一个进程里问相机服务只是读本进程的缓存；我们还开着的相机也被报过空闲，平台笔记 §3.1）。
     *
     * <p>这时：不开（{@link #next} 第 5 条，像被拿着的一样跳过、不挡后面）；设备不在我们手上的不救、不计次数（{@link #rescueAge}），
     * 开着的只许重建会话、不重开（第 7 条：重建不开新句柄）；关座舱前不先关它（{@link #surroundFirst}：关了开不回来）；
     * 关着的它，相机服务那一句「被占用（我们引起的）」还算我们的（{@link #stillOurs}）。不为它去问相机服务：问只是读本进程的缓存，放不开它；
     * 它同时被拿着的，照被拿着的规矩每 30 秒问 —— 问出来的「空闲」放得开被拿着，放不开这个。</p>
     *
     * <p>和「被拿着」（{@link Lane#mark}：相机服务说别人拿着它）是两样，一路要开得了，两样都不能有。别的路只在头 60 秒等它
     * （{@link #inFlight}），之后照常（项目所有者 2026-10-28）。相机层的开、重开照账本再看一次（SingleCamera，断言）。</p>
     */
    static boolean camHeld(Lane lane) {
        return lane.held;
    }

    /**
     * 这一路该开，而它这一台那一轮满 60 秒还有开或关没返回（{@link #roundStuck}）：那一下返回之前不会开它。
     * 录像那一侧据此不算它在起来（{@link #cannotOpen}），在录时状态条照现成的那一句说它「无法录制，其他摄像头继续录制」
     * （RecordingLanes.Facts#heldByClose，项目所有者 2026-10-28）；调度写一行（ChannelScheduler.Journal#held）。
     */
    static boolean heldByClose(World w, Lane lane) {
        return shouldOpen(w, lane) && roundStuck(w, lane);
    }

    /**
     * 设备不在我们手上，这一台那一轮（谁的都算，这一路手上的也算：关卡住、openCamera 卡住、丢了之后那一次关卡住）
     * 满 {@link #IN_FLIGHT_MAX_MS} 还有开或关没返回（{@link Lane#outstandingSince}，接连不断就不从头算）：那一下返回之前它出不了画面
     * （{@link #heldByClose}；拍照不等它，{@link #excludedFromPhoto}）。开着的不算：设备在我们手上。
     */
    static boolean roundStuck(World w, Lane lane) {
        return lane.device != Device.OPEN && lane.outstandingSince != 0
                && w.now - lane.outstandingSince >= IN_FLIGHT_MAX_MS;
    }

    /**
     * 拍照不拍这一路：被拿着、停手了、它这一台那一轮满 60 秒还有开或关没返回（{@link #roundStuck}：那一下返回之前它出不了画面，
     * 也不会被救到停手 —— 不白等 10 秒，2026-10-29）、或者熄屏放开了（{@link #releasedByDark}：熄屏拍的那一张只有环视，不白等 10 秒，
     * 该拍几路照实数）；在出画面的照拍（哪怕它的录像路不在）。通道算黑、没在开车（{@link #parkedDark}，项目所有者 2026-10-27）
     * 时不为拍照开相机：不在出画面的一路都不拍（拍照这时也不算要它，{@link #holderWants}），一路都没有就当场收尾。
     */
    static boolean excludedFromPhoto(World w, Lane lane) {
        return retiredFrontCabin(w, lane)
                || !streaming(lane) && (lane.mark != null || lane.ladder.stopped() || roundStuck(w, lane)
                || releasedByDark(w, lane) || parkedDark(w));
    }

    // ================================================================= 下一步

    /**
     * 下一步：每次只出一步（§二），按这个次序找，先找到的就是它。
     *
     * <ol>
     *   <li><b>在途</b> → 等：手上那一步（含等第一帧）、一路 LOSING、一台相机那一轮有一次关没返回（我们按次序关的那一步除外，
     *       {@link #inFlight}；手上那一步到点也照等）、一路 isBusy。后三种各封顶
     *       {@link #IN_FLIGHT_MAX_MS}（关没返回的那一种从那一轮一直有开或关没返回的那一刻算，不从头算）—— 封顶只管别的路等多久：
     *       被一轮占着的那一台自己，那一轮结束之前不开、关着在开的不救（{@link #camHeld}，项目所有者 2026-10-28、10-29）。
     *       车机要睡时最多再等 {@link #SLEEP_GRACE_MS}，然后直接去关；退出、管线换下去照常等它做完；</li>
     *   <li><b>关</b>：环视开着或正在开、但已不该开 → 单独关它（最多 10 秒）；否则不该开的座舱一起关（最多 30 秒）。
     *       座舱等环视先关完；环视还该开、此刻能用相机的，先单独关它（关座舱前先关环视，2.11.6，{@link #closeStep}），
     *       座舱关完由第 5 条照常开回来。关不等安静。退出、车机要睡、管线换下去时到这里为止，不摘死输出、不改、不开、不救；</li>
     *   <li>有一路丢了 / 开不起来还没判 → 等判完（最多 300 ms，或者等别的丢失关完；等的是最晚到点的那一路）；</li>
     *   <li><b>摘死输出</b>：一次一路、环视先，新组合照 {@link #composition}。排在开前面，因为 §二.4 说马上减（大纲 §7 解读 4），
     *       但照 §二.6 仍要等安静。只摘本会话出过画面的（{@link #reconfigurable}）；没出过画面的由第 7 条重开时丢掉它；</li>
     *   <li><b>开</b>（要安静、能用相机、屏幕知道黑没黑）：按开的次序，第一路该开、没开、没被拿着、没被一轮占着（{@link #camHeld}）、
     *       不在救援梯子上的。它有录像要、录像输出还在备
     *       → 先等（最多 2 秒，从录像登记起算；等的时候后面的路也不开）。带着它要的输出开，一样都不要就带出帧口。
     *       被拿着的、被一轮占着的跳过，不挡后面。用不了相机、屏幕不知道（{@link Why#SCREEN_UNKNOWN}，2026-10-27）时这一路先不开，
     *       接着往下看改输出、救，都没有才等；</li>
     *   <li><b>改输出</b>（要安静）：缺了要的输出、或者该出帧却没有活输出的那一路，环视先。只改本会话出过画面的
     *       （{@link #reconfigurable}）；没出过画面的由第 7 条重开时带上现在要的输出；</li>
     *   <li><b>救</b>（要安静；重开还要能用相机、屏幕知道黑没黑，屏幕真黑着、没在开车时座舱不重开 —— 熄屏期间绝不开座舱，
     *       项目所有者 2026-10-27；重建不要）：有人要（保持不算）、没被拿着、关着等救或者开着 8 秒没进展的那一路，
     *       环视先；关着、在开而相机层手上还有开或关的，等它（最多 {@link #IN_FLIGHT_MAX_MS}，{@link #rescueAge}）；
     *       关着、在开而这一台那一轮还有开或关没返回、或者被一轮占着的不救、不计次数，不设时限（{@link #camHeld}）；
     *       开着而被一轮占着的只许重建会话，不重开。
     *       梯子照旧：第一次救、本会话出过画面就重建，否则重开；12 秒一次，3 次歇 60 秒，3 轮停手；</li>
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
        // 待判的等全部到点一起判（judgeDue）：等的是最晚到点的那一路。已经到点、只等别的路的那一路按 0 等的话，
        // 调度每一毫秒醒一次，一直醒到另一路到点（审查 2026-10-11）
        String judgingKey = null;
        long judgingLeft = 0;
        for (Lane lane : w.lanes) {
            Failure failure = lane.failure;
            if (failure == null) {
                continue;
            }
            long left = failure.closedAt != 0
                    ? JUDGE_DELAY_MS - (now - failure.closedAt)
                    : IN_FLIGHT_MAX_MS - (now - failure.at);
            if (judgingKey == null || left > judgingLeft) {
                judgingKey = lane.key;
                judgingLeft = left;
            }
        }
        if (judgingKey != null) {
            return Decision.waitFor(Why.JUDGING, judgingKey, Math.max(0, judgingLeft));
        }
        long quietLeft = w.lastOthersChangeAt == 0 ? 0 : QUIET_MS - (now - w.lastOthersChangeAt);
        boolean quiet = quietLeft <= 0;
        boolean usable = usable(w);

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
        // 屏幕不知道黑没黑时先不开的那一路（和「用不了相机」同一个样子：接着看改输出、救，都没有才等它）
        String unknownKey = null;
        for (Lane lane : w.lanes) {
            // 被拿着的、被一轮占着的（那一轮结束之前不开，第 1 步「轮次 + 账本」）都跳过，不挡后面
            if (lane.device != Device.CLOSED || lane.mark != null || camHeld(lane) || rescuing(lane)
                    || !shouldOpen(w, lane)) {
                continue;
            }
            if (!w.screenKnown) {
                unknownKey = lane.key;
                break;
            }
            if (!usable) {
                deferredKey = lane.key;
                break;
            }
            // 走到这里能用相机：「先不开」的没有（deferredKey 还是 null），也照样标上「看过了」
            if (!quiet) {
                return withDeferred(Decision.waitFor(Why.QUIET, lane.key, quietLeft).about(Move.OPEN), deferredKey);
            }
            long recordLeft = recordOutputLeft(w, lane);
            if (recordLeft > 0) {
                return withDeferred(Decision.waitFor(Why.RECORD_OUTPUT, lane.key, recordLeft).about(Move.OPEN),
                        deferredKey);
            }
            return withDeferred(open(w, lane), deferredKey);
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
            boolean canRebuild = framedSession(lane);
            // 重开要能用相机、屏幕知道黑没黑；屏幕真黑着、没在开车时座舱不重开（熄屏期间绝不开座舱，项目所有者 2026-10-27）；
            // 被一轮占着的不重开（那一轮结束之前不开新句柄；重建会话不开）
            boolean noReopen = !usable || !w.screenKnown || w.screenDark && !w.driving && !isSurround(lane)
                    || camHeld(lane);
            if (noReopen && !(canRebuild && !onLadder(lane))) {
                // 不开、不计次数（重建会话不用开相机，梯子上第一次、出过画面的照样重建）
                if (!usable && deferredKey == null) {
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
        if (unknownKey != null) {
            // 别的都没有：等屏幕知道黑没黑（不带「先不开」：那一句只说用不了相机，记的那一条不动）
            Decision waiting = Decision.waitFor(Why.SCREEN_UNKNOWN, unknownKey, -1).about(Move.OPEN);
            waiting.events.addAll(ladderEvents);
            return waiting;
        }
        Decision idle = Decision.idle();
        idle.events.addAll(ladderEvents);
        return withDeferred(idle, deferredKey);
    }

    /**
     * 在途（§二.1）：有一样就等。
     *
     * <p>相机层在关一个设备的那一路（{@link Lane#closingSince}）是在途，不管它属于哪一步（救援的重开关旧的、作废卡住的那一轮、
     * 晚到的设备就地关），手上那一步到点也照等，封顶 {@link #IN_FLIGHT_MAX_MS} —— 和丢了之后的那一次关（LOSING）同一条规矩
     * （项目所有者 2026-10-14 批准，2.11.4 第 1c 项）。我们按次序关的那一步（{@link Device#CLOSING}）不在此列：它照 §二.2 自己的封顶，
     * 环视 10 秒、座舱 30 秒到点就不再等（「环视等了 10 秒还没关完，先关座舱」）。</p>
     *
     * <p>这里的封顶只管别的路等多久（项目所有者 2026-10-28：一路的关卡住超过 60 秒，别的路照以前那样不再等它）。钟从账本上那一轮一直有开或关
     * 没返回的那一刻算（{@link Lane#closingSince}）：卡住的关上面又来一次关，别的路不再从头等 60 秒。被一轮占着的那一路自己，
     * 那一轮结束之前不开、关着在开的不救，不设时限（{@link #camHeld}）。</p>
     */
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
            if (lane.closingSince != 0 && lane.device != Device.CLOSING
                    && now - lane.closingSince < IN_FLIGHT_MAX_MS) {
                return Decision.waitFor(Why.BUSY, lane.key, IN_FLIGHT_MAX_MS - (now - lane.closingSince));
            }
        }
        for (Lane lane : w.lanes) {
            if (lane.busySince != 0 && lane.busySince != lane.excusedBusySince
                    && now - lane.busySince < IN_FLIGHT_MAX_MS) {
                return Decision.waitFor(Why.BUSY, lane.key, IN_FLIGHT_MAX_MS - (now - lane.busySince));
            }
        }
        return null;
    }

    /**
     * 关（§二.2）：环视开着或正在开、但已不该开 → 单独关它；否则不该开的座舱一起关 —— 环视还在关（10 秒以内）就先等它关完。
     * 2026-10-09 黑匣子：环视比座舱晚关完的那几次，那一次关要 4–17 秒，下一次打开它一帧不出。关不等安静。
     *
     * <p><b>关座舱前先关环视</b>（项目所有者 2026-10-15 定，2.11.6，{@link Why#SURROUND_FIRST}）：要关座舱，而环视开着或在开、还该开
     * （在录、后视镜、盲区画面、预览，或者在保持），此刻又能用相机（{@link #surroundFirst}）→ 这一步只关环视。之后照这里本来的规矩走：
     * 下一轮环视关着了，座舱一起关（原因照它们自己的：熄屏放开、保持到点、没人要）；再下一轮「开」那一步把还有人要的环视照常开回来
     * （{@link #next} 第 5 条，开的次序、安静、等录像输出都照旧）。不另立计时、常数、动手的地方；登记表一样不加、不改 ——
     * 开回来靠的是登记表上本来就要它的那几样（录像、后视镜、盲区画面、预览）。只在保持、没人要的环视关了就不再开（保持要开着，{@link #holding}）：
     * 它的保持跟着座舱一起结束。环视的 10 秒封顶照旧：到点没关完，座舱照样关，环视关完了再开回来。</p>
     *
     * <p>为什么（平台笔记 §3.6「放开座舱时环视跟着断」）：2.11.2–2.11.5 熄屏放开座舱、保持到点时座舱在开着的环视旁边关（D4 的例外），
     * 之前 20 次里环视跟着停了 8 次，都停在一个座舱关完的那一帧前后，救了 40–50 秒；10-15 07:42 车主测试 A 的第 2 次又是这样（座舱开始关后
     * 60 ms）。「先关环视、再关座舱、再开环视」同一个进程 23 次、退出再开 21 次都正常。代价：每一次计划里的关座舱，环视（含环视录像）
     * 断约 0.5–1 秒、录像切一个新文件，开着的后视镜闪一下、显示着的盲区画面黑一下（挂 D 挡、盲区画面备着时环视一直有人要，
     * 座舱只为预览开着的那种关也算在内）。</p>
     *
     * <p>只管这里出的关（计划里的关）。救援重开时关旧设备、丢了之后那一次关、车机拿走那一路，都不是这一步，不走它；
     * 退出、车机要睡时环视本来就先单独关（它也不该开了，走上面第一条）。</p>
     *
     * <p>通道刚算黑那一轮环视没留着（{@link #releasedByDark}，项目所有者 2026-10-27）：它和座舱同一轮不该开了，照上面第一条
     * 先单独关（原因 {@link Why#SCREEN_OFF}），下一轮座舱一起关（{@link Why#DARK_RELEASE}），什么都不开回来 ——
     * 关座舱前先关环视要环视还该开。不会先关环视、开回来给录像、紧接着又关。</p>
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
            return close(Collections.singletonList(first), closeWhy(w, first), CLOSE_SURROUND_MAX_MS);
        }
        Lane surround = w.lane(CameraSlots.KEY_SURROUND);
        if (surroundFirst(w, surround)) {
            return close(Collections.singletonList(surround), Why.SURROUND_FIRST, CLOSE_SURROUND_MAX_MS);
        }
        if (surround != null && (surround.device == Device.CLOSING || surround.device == Device.LOSING)
                && w.now - surround.deviceSince < CLOSE_SURROUND_MAX_MS) {
            return Decision.waitFor(Why.SURROUND_CLOSING, surround.key,
                    CLOSE_SURROUND_MAX_MS - (w.now - surround.deviceSince));
        }
        return close(toClose, closeWhy(w, first), CLOSE_CABINS_MAX_MS);
    }

    /**
     * 座舱要关的这一轮，环视要不要先单独关（{@link #closeStep}，项目所有者 2026-10-15 定，2.11.6）：环视开着或在开、还该开，此刻能用相机
     * （{@link #usable}，和「开」那一步看的是同一个 —— 关了它，开那一步才开得回来；用不了的照旧座舱在环视旁边关），没被拿着，
     * 也不在救援梯子上（{@link #rescuing}：在梯子上的关了只能由救援按梯子的节奏重开，不是「开」那一步照常开回来，还要白算次数 ——
     * 它此刻本来也没在出画面）。屏幕不知道黑没黑时也不（{@link World#screenKnown}，2026-10-27）：那时「开」那一步不开，关了开不回来。
     * 被一轮占着时也不（{@link #camHeld}）：那一轮结束之前「开」那一步不开它，关了也开不回来。
     */
    private static boolean surroundFirst(World w, Lane surround) {
        return surround != null && (surround.device == Device.OPEN || surround.device == Device.OPENING)
                && shouldOpen(w, surround) && usable(w) && w.screenKnown && surround.mark == null
                && !rescuing(surround) && !camHeld(surround);
    }

    private static Decision close(List<Lane> lanes, Why why, long maxMs) {
        Decision d = new Decision(Move.CLOSE, why);
        List<String> keys = new ArrayList<>();
        for (Lane lane : lanes) {
            keys.add(lane.key);
        }
        d.keys = Collections.unmodifiableList(keys);
        d.maxMs = maxMs;
        return d;
    }

    /**
     * 关的原因（黑匣子「通道：关 …（没人要 / 保持到点 / 熄屏 / 熄屏：座舱放开 / 车机要睡 / 退出 / 管线重建）」）。一步里的几路原因一样：
     * 座舱一起关的那一步看第一路就是座舱，环视单独关的那一步就是环视。关座舱前先关环视的那一步不在这里判（{@link Why#SURROUND_FIRST}，
     * {@link #closeStep} 直接给）：那时环视还该开，这里答不出它为什么关。熄屏放开的环视（2026-10-27）照旧是「熄屏」，
     * 不写成「座舱放开」。
     */
    private static Why closeWhy(World w, Lane lane) {
        if (w.exitSince != 0) {
            return Why.EXIT;
        }
        if (w.sleepSince != 0) {
            return Why.SLEEP;
        }
        if (w.retireSince != 0) {
            return Why.REBUILD;
        }
        if (releasedByDark(w, lane) && !isSurround(lane)) {
            return Why.DARK_RELEASE;
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

    /**
     * 开这一路之前还要等它的录像输出多久（毫秒）；不用等是 0 或负数。从这一路的录像输出变成要的那一刻算
     * （{@link Lane#recordWantedSince}）。
     */
    private static long recordOutputLeft(World w, Lane lane) {
        if (!wantsKind(w, lane, Kind.RECORD) || lane.record == RecordPrep.FAILED || lane.recordWantedSince == 0) {
            return 0;
        }
        for (Out out : lane.declared) {
            if (out.kind == Kind.RECORD && !lane.dead.contains(out)) {
                return 0;
            }
        }
        return RECORD_OUTPUT_WAIT_MS - (w.now - lane.recordWantedSince);
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
     * 这一路能改会话（改输出、摘死输出）：本会话出过画面（{@link #framedSession}），相机层手上也没有动作。
     *
     * <p>项目所有者 2026-10-14 批准（2.11.4 第 1d 项）：<b>本会话没出过画面的，不改输出、不摘死输出；由救援重开时带上现在要的输出</b>
     * （{@link #openOutputs}）。这是 2.10.8 那条「一帧都没出过的会话不重建」（SingleCamera.rebuildSession）用到每一种改会话上：
     * 改输出、救援的重建在相机层是同一个动作（关旧会话、建新会话，建之前框架要等 HAL 把手上的请求做完 —— waitUntilIdle），
     * 一帧不出的会话没有东西可等完，那一下卡满 4.7–4.9 秒、报错 4、再关一次设备。2.11 两次全是这样：10-12 20:04 环视（哨兵关）、
     * 10-13 20:10 后座舱（开 15 秒没画面之后加预览），都没写进黑匣子，只在关相机小结的账里看得出来。救援排在改输出后面，
     * 以前改输出先轮到它；改输出那一下的在途还把「8 秒没进展」重新算起。现在没出过画面的那一路直接落到救援：满 8 秒没进展
     * （开 15 秒到点时早就满了）就重开，第一次救就是第 2 次（开那一步算了第 1 次）。出了第一帧，它就又能改了。</p>
     *
     * <p>相机层手上有动作（配会话、关旧会话、排着一次重建）的也不改：卡在配会话里的那一路，再改一次还是卡着；相机层的那个动作
     * 最多挂 {@link #IN_FLIGHT_MAX_MS}，在那之前它就不能改，没进展满 8 秒由救援接手（救援不看它，重开把那一次作废）。</p>
     */
    private static boolean reconfigurable(Lane lane) {
        return framedSession(lane) && lane.busySince == 0;
    }

    /**
     * 本会话出过画面：开着、会话配好了、这一份会话出过第一帧。改输出、摘死输出（{@link #reconfigurable}）、救援的第一次重建（{@link #next}）
     * 共用这一个定义 —— 只对出过画面的会话动会话，没出过的只重开。
     */
    private static boolean framedSession(Lane lane) {
        return lane.device == Device.OPEN && lane.sessionSince != 0 && lane.firstFrameAt != 0;
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
     *
     * <p>设备不在我们手上（关着、在开）而相机层手上还有动作（{@link Lane#busySince}，开、重开、关还在途）的不救，
     * 等它有结果、或者到它自己的封顶（{@link #IN_FLIGHT_MAX_MS}）：这时重开，相机层只会合并掉（同一台相机不许两个句柄），
     * 梯子却已经算了一次 —— 一次没救的「救」，3 次不成歇 60 秒、3 轮停手都会提前（审查 2026-10-11）。
     * 开着的那一路卡在配会话里不在此列：相机层的重开会把那一次配置作废、关掉设备再开（SingleCamera.reopen），满 8 秒照救。</p>
     *
     * <p>设备不在我们手上、这一台被一轮占着（{@link #camHeld}）、或者这一台那一轮还有开或关没返回（{@link Lane#outstandingSince}：
     * 这一路手上那一轮的 openCamera 还没回音也是）的不救（第 1 步「轮次 + 账本」，项目所有者 2026-10-28、10-29）：重开开不出去
     * （那一轮结束之前不开，相机层只会合并掉），梯子不算次数，也就不会歇 60 秒、不会停手 —— 那一轮的那一下返回之前一直这样，不设时限。
     * 以前在开的那一下 60 秒没回音，相机层的在途封顶一到就当它丢了、作废那一轮再开（2026-10-29 删：开没返回照「关没返回」同一条）。</p>
     */
    private static long rescueAge(World w, Lane lane) {
        if (lane.mark != null || lane.failure != null || lane.ladder.stopped() || !wanted(w, lane)) {
            return -1;
        }
        if (lane.device != Device.OPEN && (lane.busySince != 0 || camHeld(lane) || lane.outstandingSince != 0)) {
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
        d.deferralKnown = true;
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
