package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 通道调度：<b>唯一动相机的地方</b> —— 开、关、改输出、救、重开都由它做（channel-logic §二，项目所有者 2026-10-10 确认）。
 *
 * <h3>为什么要它</h3>
 *
 * <p>2.10 动相机的有两处：登记表触发的有序开关（MultiCameraManager.reconcileCameras / openAllCameras / closeAllCameras），
 * 和看门狗的闸门（checkLiveness），再加上丢了设备之后 CameraTaken 的判定自己去叫重开（retryTaken）。几处各看各的条件，
 * 谁先动看时机：环视正在关，看门狗去重开座舱；被拿走的那一路，一边按次序开跳过它，一边每 30 秒试开它。
 * 项目所有者 2026-10-10 定：登记表只记「谁要哪几路」，一个调度是唯一动手的地方，看门狗并进来。
 * 该做哪一步全在 {@link ChannelRules}（纯规则，有测试）；这里只读事实、照做、记账、写黑匣子。</p>
 *
 * <h3>一轮</h3>
 *
 * <ol>
 *   <li>刷新事实：每一路照 SingleCamera（{@link CameraPort}）填，全局的照 {@link Host} 填 —— 登记表、录像计划、亮熄屏、
 *       前台服务、能不能用相机、相机服务对每一路说的最后一句、车机要睡、退出；</li>
 *   <li>手上那一步做完没有、到没到顶（{@link ChannelRules#outcome} → {@link ChannelRules#finish}）；</li>
 *   <li>记账（{@link ChannelRules#settle}）：保持、主动认出被拿走、到点的判定、放开、解除停手；</li>
 *   <li>下一步（{@link ChannelRules#next}），一次只出一步；照做（{@link CameraPort} 的动作），记下这一步
 *       （{@link ChannelRules#begin}）；</li>
 *   <li>交给它的消费者（{@link #handOver}：主界面重建时留住的预览画布、换下来的录制器）离开了会话的，放掉；</li>
 *   <li>被拿着的一路有人要、到了 30 秒就问一次相机服务（只问，不开）；</li>
 *   <li>告诉录像那一侧（{@link Recording}）：哪几路走了、哪几路的样子变了、哪几路带着新会话出了画面 ——
 *       录像按路加入、退出只看这几声，自己从不动相机。</li>
 * </ol>
 *
 * <h3>线程</h3>
 *
 * <p>只在主线程上跑。每一轮都是 post 出去的：几次叫醒（登记表、相机回调、相机服务、亮熄屏、输出声明、前台服务、
 * 主界面进出前台、计时器、每 {@link ChannelRules#TICK_MS}）并成一次 post —— 熄屏那一下所有监听者都做完了它才跑。
 * 相机回调在相机线程上发，由 MultiCameraManager 转到主线程再交进来（{@link #onOpened} 那几个入口）；从不在回调里
 * 当场跑一轮。能在别的线程上调的只有 {@link #wake}、{@link #exit}、{@link #onNeedsChanged}、{@link #askNow}、
 * {@link #closingAnywhere}：它们只动原子字段，然后叫醒。</p>
 *
 * <h3>钟</h3>
 *
 * <p>调度的钟是 {@link Clock}：开机以来、不含深睡（{@code SystemClock.uptimeMillis}），和 SingleCamera 的会话时刻、
 * 第一帧、在途的起点、{@link CameraLiveness} 同一个钟 —— 车停着睡一夜醒来，不该算卡了一整夜。相机服务那几句的时刻
 * （{@link CameraAvailabilityWatch}）是含深睡的 {@code elapsedRealtime}，每一轮按「此刻 −（elapsedRealtime − 那个时刻）」
 * 换过来，0 仍是 0；记争用（{@link CameraContention#othersTookAt}）时再换回去。</p>
 *
 * <h3>可测</h3>
 *
 * <p>不引 {@code android.*}：钟、队列、每一路相机、全局事实、黑匣子的句子都经接口注入（{@link Clock}、{@link Poster}、
 * {@link CameraPort}、{@link Host}、{@link Journal}），Android 那一层在 MultiCameraManager 里，很薄。
 * 这里没有中文字面量（只许出现在 AppLog / BlackBox 语句和白名单文件里）：每一件事以 {@link Journal} 的一个方法交出去，
 * 句子在 MultiCameraManager 里拼，一件事一行，绝不每一轮都写。见 {@code ChannelSchedulerTest}。</p>
 */
public final class ChannelScheduler {

    // ================================================================= 注入的接口

    /** 调度的钟：开机以来的毫秒，不含深睡（{@code SystemClock.uptimeMillis}）。哪个线程都能读。 */
    interface Clock {
        long now();
    }

    /** 主线程的队列：调度的每一轮、计时器都排在这里。哪个线程都能 post。 */
    interface Poster {
        void post(Runnable task);

        void postDelayed(Runnable task, long delayMs);

        void cancel(Runnable task);
    }

    /**
     * 一路相机：SingleCamera 的动作和事实（MultiCameraManager 照转，主线程上调）。动作只有调度调；
     * 返回 false 就是没做（已经开着 / 在开、设备不在、合并掉了），这一步不算开始。
     */
    interface CameraPort {
        String cameraId();

        /** 开，会话带着这一组输出（一样都没有就带出帧口）。 */
        boolean open(Set<ChannelRules.Out> outputs);

        /** 改输出：按这一组重配会话。 */
        boolean applyOutputs(Set<ChannelRules.Out> outputs);

        /** 救：重建会话。 */
        boolean rebuildSession(Set<ChannelRules.Out> outputs);

        /** 救：重开（关着的一路也走这里）。 */
        boolean reopen(Set<ChannelRules.Out> outputs);

        /** 关（只清会话和设备，不清声明）。{@code why} 是给相机那一行的英文短语。 */
        void close(String why);

        /** 不开设备先算画面尺寸（这一路不在时算不出来，等它回来再算）。 */
        void resolveSize();

        boolean sizeResolved();

        /** 设备在我们手上（开成了、还没关）。 */
        boolean deviceOpen();

        /** 开着、在开、或者在关（任何一份实例在关这一台相机都算）。 */
        boolean holdsOrIsOpening();

        /** 有动作在途的话那个动作从什么时候起（调度的钟）；不忙是 0。 */
        long busySince();

        /** 在途的是什么（给黑匣子那一行，英文记号，不带时长 —— 时长调度自己算）。 */
        String describeBusy();

        /** 眼下这份会话的快照：输出、代号、配好的时刻（调度的钟）。 */
        SingleCamera.SessionOutputs sessionOutputs();

        /** 已声明、消费者还活着的输出。 */
        Set<ChannelRules.Out> declaredOutputs();

        /** 会话里消费者已被系统收走的那几样。 */
        Set<ChannelRules.Out> deadInSession();

        /** 本会话第一帧的时刻（调度的钟）；0 = 还没有。 */
        long firstFrameAt();

        /** 多久没有进展。 */
        long progressAgeMs();

        /** 会话重建的代数（每真去建一次 +1）：一步前后对一下，变了就是这一路的会话被动过。 */
        int sessionGeneration();

        /** 从 {@code since}（调度的钟）起最长断了多久帧。 */
        long longestFrameGapSince(long since);

        /** 最后一次报错的短名；没有是 null。 */
        String lastErrorName();

        /** 一个录像输出离开了会话：告诉声明它的录像那一侧（撤回了的不告诉）。 */
        void dropped(ChannelRules.Out out, SingleCamera.Drop why);
    }

    /**
     * 全局的事实，加上调度要对外做的几件事（问相机服务、记争用、停下来以后摘掉监听）。主线程上调。
     */
    interface Host {
        /** 登记表此刻有谁（{@link CameraNeeds}）。哪个线程都能调。 */
        Set<CameraNeeds.Holder> holders();

        /** 车机要睡（{@link CameraNeeds#headUnitSleeps}）。哪个线程都能调。 */
        boolean headUnitSleeps();

        /** 录像要哪几路：这一次录像的计划。 */
        Set<String> recordPlan();

        boolean screenOn();

        /** 前台服务在不在：没有它不保持。 */
        boolean foregroundService();

        /** 主界面在前。 */
        boolean mainForeground();

        /** 前台服务是在主界面在前时起的、或那时刷新过（安卓 12 起后台才起的前台服务用不了相机）。 */
        boolean serviceStartedWhileVisible();

        /** 相机服务的可用性回调收到过没有。 */
        boolean heardAnything();

        /** 相机服务那几句用的钟（{@code elapsedRealtime}，含深睡）的此刻。 */
        long elapsedNow();

        /** 上一次别人引起的变化（{@link #elapsedNow} 的钟）；0 = 没有过。 */
        long lastOthersChangeAt();

        /** 相机服务对这一路说的最后一句（时刻是 {@link #elapsedNow} 的钟）。 */
        CameraAvailabilityWatch.Word word(String cameraId);

        /** 这一路的录像输出在不在备（录像宿主报的）。 */
        ChannelRules.RecordPrep recordPrep(String key);

        /**
         * 问相机服务一次（只问，不开）。答了在主线程上交回照回放改了的那几路的相机编号（可能是空的）；
         * 问不成也交，是空的。
         */
        void ask(Consumer<Set<String>> answered);

        /** 判出这一路被别的程序拿走了：按丢的 / 开不起来的那一刻（{@link #elapsedNow} 的钟）记争用、查嫌疑应用。 */
        void othersTook(String cameraId, long atElapsed);

        /** 退出之后都关完了，调度停了：摘掉它接在别处的监听。 */
        void stopped();
    }

    /**
     * 录像那一侧（MultiCameraManager 的录像宿主，大纲 §2 阶段 5 定的接口）：每一路的相机此刻怎样，调度在每一轮最后、
     * 主线程上告诉它（{@link #setRecording}）。录像只声明、撤回录像输出，照这几声加入、退出，从不动相机。
     * 录像输出离开会话由 {@link CameraPort#dropped} 告诉声明它的那一方（SingleCamera.dropListener），不在这里。
     */
    interface Recording {
        /** 这一路带着一份新会话出了画面（会话配好、出了第一帧，sessionOutputs 读得到）：每一份会话说一次。 */
        void onLaneCameraStreaming(String key);

        /**
         * 这一路的相机走了：丢了（{@link Gone#LOST}，在阻塞的关之前；{@code reason} 是丢的那一下）、我们关了它
         * （{@link Gone#CLOSED}）、或者它卡住了在救（{@link Gone#RESCUED}：重建会话、重开）。
         */
        void onLaneCameraGone(String key, Gone why, CameraTaken.Reason reason);

        /**
         * 这一路的 {@link #condition} 变了（被拿着、放开了、在救、停手、在出画面），或者它关着时能不能用相机变了
         * （{@link #cannotOpen}）。
         */
        void onLaneConditionChanged(String key);
    }

    /** 一路的相机为什么走了（{@link Recording#onLaneCameraGone}）。 */
    enum Gone {
        /** 丢了：被断开、报错（在那一次阻塞的关之前就说）。 */
        LOST,
        /** 我们关的：没人要、保持到点、熄屏、车机要睡、退出。 */
        CLOSED,
        /** 开着却没进展（卡住了），调度在救：重建会话或者重开。 */
        RESCUED
    }

    private static final Recording NO_RECORDING = new Recording() {
        @Override
        public void onLaneCameraStreaming(String key) {
        }

        @Override
        public void onLaneCameraGone(String key, Gone why, CameraTaken.Reason reason) {
        }

        @Override
        public void onLaneConditionChanged(String key) {
        }
    };

    /** 现在用不了相机是因为什么（黑匣子那一行）。 */
    enum Unusable {
        /** 主界面不在前，前台服务是后台起的。 */
        SERVICE_FROM_BACKGROUND,
        /** 主界面不在前，前台服务不在。 */
        NO_SERVICE,
        /** 开的时候相机层报了「用不了相机」（错误 3 / CAMERA_DISABLED）：等主界面回到前台。 */
        REPORTED
    }

    /**
     * 黑匣子：调度的每一件事一个方法，句子在 MultiCameraManager 里拼（这里不写中文）。一件事一行；
     * 「等」只在原因变了时写，「问」每次都进日志、黑匣子只记第一次和每第 10 次（{@code important}）。主线程上调。
     */
    interface Journal {
        /** 登记表变了：「登记表：PREVIEW+RECORDING（录 …）」。 */
        void needs(Set<CameraNeeds.Holder> holders, List<String> recordKeys);

        /** 这几路没人要了：亮屏、前台服务在，保持 30 秒，画面接着送进 {@code outlets}。 */
        void holding(List<String> keys, Set<ChannelRules.Kind> outlets);

        /** 保持中又有人要了。 */
        void holdCancelled(List<String> keys, Set<CameraNeeds.Holder> by);

        /** 主动认出被拿着：还没开，相机服务说它不在（{@code absent}），或者被占用、不是我们。 */
        void recognized(String key, String cameraId, boolean absent);

        /** 一路丢了、或者开不起来，判完了。 */
        void judged(Judgement judgement);

        /** 被拿着的一路放开了（{@code asked}：是问出来的）。 */
        void released(String key, String cameraId, CameraTaken.Verdict was, long heldMs, boolean asked);

        /** 停手的一路重新救。 */
        void stopLifted(String key, ChannelRules.Event.Cause cause, Set<CameraNeeds.Holder> holders);

        /** 连着救 3 次没用，歇 60 秒。 */
        void gaveUp(String key);

        /** 救了 3 轮没用，停手。 */
        void stopped(String key, String lastError);

        /** 在等（只在原因变了时）。 */
        void waiting(Wait wait);

        /** 现在用不了相机，这一路先不开 / 不重开（只在变了时）。 */
        void deferred(String key, Unusable why);

        /** 开之前等录像输出，等完了：等了多久，带没带着它开。 */
        void recordWaitEnded(String key, long waitedMs, boolean withRecord);

        /** 开、改输出、救做完了（出了画面），或者到点了。开不起来、丢了的由 {@link #judged} 写。 */
        void stepEnded(StepReport report);

        /** 连着开的这几路：「按次序开相机：环视 1.1s → 后座舱 0.2s → 前座舱 0.3s」。 */
        void openTrail(List<String> keys, List<Long> ms);

        /** 开始关这几路。 */
        void closing(List<String> keys, ChannelRules.Why why);

        /** 关到点了还没关完的这几路（{@code surround}：是环视单独关的那一步）。 */
        void closeTimedOut(List<String> keys, long maxMs, boolean surround);

        /**
         * 一轮关完：「关相机：环视 0.1s → 后座舱 0.1s、前座舱 0.1s」。{@code surroundKey} 为 null 是这一轮没关环视；
         * 座舱按关完的先后。
         */
        void closeRound(String surroundKey, long surroundMs, boolean surroundTimedOut,
                        List<String> cabinKeys, List<Long> cabinMs);

        /** 车机要睡：手上那一步（{@code key} 为 null 是没有）开了多久，最多再等 1 秒。 */
        void sleep(String key, ChannelRules.Move move, long ms);

        /** 退出：登记表作废，手上那一步做完后按次序关。 */
        void exit(String key, ChannelRules.Move move, long ms);

        /** 问了相机服务，答回来了：这一路此刻怎么说（第 {@code n} 次）。 */
        void asked(String key, String cameraId, int n, long heldMs, Boolean free, boolean absent, boolean important);
    }

    /** 一次判定，黑匣子那一行要的东西。 */
    static final class Judgement {
        final String key;
        final String cameraId;
        final CameraTaken.Verdict verdict;
        final ChannelRules.Failure.Origin origin;
        final CameraTaken.Reason reason;
        /** 丢了：从丢到我们这边关完用了多久；-1 不知道（开不起来的）。 */
        final long closeMs;
        final boolean heardAnything;
        /** 相机服务对这一路说的最后一句：TRUE 空闲、FALSE 被占用、null 没说过。 */
        final Boolean serviceFree;
        final boolean absent;
        /** 那一句比关完早多少毫秒（负数 = 晚）；{@link Long#MIN_VALUE} = 没有那一句。 */
        final long wordBeforeCloseMs;
        /** 判的时候别的被拿着（TAKEN）的那几路的相机编号。 */
        final List<String> takenIds;
        /** 判成普通故障：救援梯子上第几次了。 */
        final int attempt;

        Judgement(String key, String cameraId, CameraTaken.Verdict verdict, ChannelRules.Failure.Origin origin,
                  CameraTaken.Reason reason, long closeMs, boolean heardAnything, Boolean serviceFree, boolean absent,
                  long wordBeforeCloseMs, List<String> takenIds, int attempt) {
            this.key = key;
            this.cameraId = cameraId;
            this.verdict = verdict;
            this.origin = origin;
            this.reason = reason;
            this.closeMs = closeMs;
            this.heardAnything = heardAnything;
            this.serviceFree = serviceFree;
            this.absent = absent;
            this.wordBeforeCloseMs = wordBeforeCloseMs;
            this.takenIds = takenIds;
            this.attempt = attempt;
        }
    }

    /** 一次「等」，黑匣子那一行要的东西。 */
    static final class Wait {
        final ChannelRules.Why why;
        /** 等的、或者挡着的那一路；可能是 null。 */
        final String key;
        /** 等的是哪一种动作（开、改输出、救）；在途那种是 null。 */
        final ChannelRules.Move pending;
        /** 最多还要等多久；-1 说不准。 */
        final long ms;
        /** 在途的是什么（LOSING / BUSY）。 */
        final String busy;
        /** QUIET：别人引起的变化是多久以前；LOSING / BUSY：在途多久了。 */
        final long agoMs;
        /** QUIET：是相机服务对哪一路说的那一句（认不出是 null）。 */
        final String wordCameraId;
        final Boolean wordFree;
        final boolean wordAbsent;
        /** QUIET：那一路此刻我们开着。 */
        final boolean weHoldIt;

        Wait(ChannelRules.Why why, String key, ChannelRules.Move pending, long ms, String busy, long agoMs,
             String wordCameraId, Boolean wordFree, boolean wordAbsent, boolean weHoldIt) {
            this.why = why;
            this.key = key;
            this.pending = pending;
            this.ms = ms;
            this.busy = busy;
            this.agoMs = agoMs;
            this.wordCameraId = wordCameraId;
            this.wordFree = wordFree;
            this.wordAbsent = wordAbsent;
            this.weHoldIt = weHoldIt;
        }
    }

    /** 开、改输出、救的一步结束了，黑匣子那一行要的东西。 */
    static final class StepReport {
        final ChannelRules.Move move;
        final ChannelRules.Why why;
        final String key;
        final ChannelRules.Rescue rescue;
        final int attempt;
        /** 救：多久没进展；-1 = 关着（丢了、开不起来）。 */
        final long ageMs;
        final Set<CameraNeeds.Holder> forWhom;
        final Set<ChannelRules.Kind> outputs;
        final Set<ChannelRules.Kind> added;
        final Set<ChannelRules.Kind> removed;
        final boolean timedOut;
        /** 从这一步开始到配好会话；-1 = 没配好。 */
        final long configuredMs;
        /** 到出第一帧；-1 = 没出。 */
        final long frameMs;
        /** 这段时间里别的在出画面的几路怎样。 */
        final List<Other> others;

        StepReport(ChannelRules.Move move, ChannelRules.Why why, String key, ChannelRules.Rescue rescue, int attempt,
                   long ageMs, Set<CameraNeeds.Holder> forWhom, Set<ChannelRules.Kind> outputs,
                   Set<ChannelRules.Kind> added, Set<ChannelRules.Kind> removed, boolean timedOut,
                   long configuredMs, long frameMs, List<Other> others) {
            this.move = move;
            this.why = why;
            this.key = key;
            this.rescue = rescue;
            this.attempt = attempt;
            this.ageMs = ageMs;
            this.forWhom = forWhom;
            this.outputs = outputs;
            this.added = added;
            this.removed = removed;
            this.timedOut = timedOut;
            this.configuredMs = configuredMs;
            this.frameMs = frameMs;
            this.others = others;
        }
    }

    /** 一步里别的路：最长断帧、会话动没动过（「这段时间里环视最长断帧不到 0.2 秒、会话没动」）。 */
    static final class Other {
        final String key;
        final long gapMs;
        final boolean sessionChanged;

        Other(String key, long gapMs, boolean sessionChanged) {
            this.key = key;
            this.gapMs = gapMs;
            this.sessionChanged = sessionChanged;
        }
    }

    /**
     * 我们的开、关做完之后多久以内，相机服务为这一路说的话还算我们引起的（{@link #ourStepInFlight}）。那一句从 binder 线程上来，
     * 和相机线程报的开好 / 关完谁先到主线程没有保证；差的是同一次进相机服务的前后脚，几十毫秒的事。
     */
    static final long OWN_WORD_GRACE_MS = 500L;

    // ================================================================= 进程里的几份调度

    /** 活着的调度（每个管理器一个；换车型时两份并存）。 */
    private static final CopyOnWriteArrayList<ChannelScheduler> LIVE = new CopyOnWriteArrayList<>();

    /**
     * 正在为退出、车机要睡按次序关相机的调度，从什么时候起（各自的钟）：{@link #closingAnywhere} 就是它里面有一份
     * 进来还不到 {@link ChannelRules#EXIT_WAIT_MAX_MS}。{@link #exit} 和发布「车机要睡」时当场同步放进来，
     * 不等 post 出去的那一轮；只在两种情况下拿掉 —— 那一份报告全部关完，或者车机要睡被亮屏清掉、手上没有关在途。
     */
    private static final Map<ChannelScheduler, Long> WINDING = new ConcurrentHashMap<>();

    /**
     * 有没有哪一份调度正在为退出、车机要睡按次序关相机，还没关完。谁读它：新实例开相机之前（换车型时，见大纲 §6 风险 10），
     * 退出的最后一步（MainActivity.finishExit），熄屏录制放唤醒锁之前（ScreenOffRecording）。哪个线程都能读。
     *
     * <p>每一份从进来起最多算 {@link ChannelRules#EXIT_WAIT_MAX_MS}（手上那一步封顶 + 按次序关封顶 + 余量）：一次关卡死在
     * 相机服务里的那一份永远关不完，不封顶的话进程活着就一直为真 —— 读它的旧路径（画布没了、停录收拾完不重建会话）
     * 从此全都不动（审查 2026-10-11）。</p>
     */
    public static boolean closingAnywhere() {
        for (Map.Entry<ChannelScheduler, Long> entry : WINDING.entrySet()) {
            if (entry.getKey().clock.now() - entry.getValue() < ChannelRules.EXIT_WAIT_MAX_MS) {
                return true;
            }
        }
        return false;
    }

    /** 叫醒每一份调度：前台服务就绪 / 没了、主界面进出前台（这几件事是进程级的，不归哪一份管理器）。 */
    public static void wakeAll() {
        for (ChannelScheduler scheduler : LIVE) {
            scheduler.wake();
        }
    }

    /**
     * 这台相机此刻在哪一份调度里判的是被拿着（TAKEN / BLOCKED）。给旧读者回答 {@link CameraTaken#benched}：
     * 被拿着这件事只记在调度里。后视镜服务（阶段 4）、协调器（阶段 5）都已改读 {@link #condition}，应用里没有读者了，
     * 阶段 7 随 {@link CameraTaken#benched} 一起删。主线程上读。
     */
    static boolean takenAnywhere(String cameraId) {
        for (ChannelScheduler scheduler : LIVE) {
            ChannelRules.Lane lane = scheduler.laneById(cameraId);
            if (lane != null && lane.mark != null) {
                return true;
            }
        }
        return false;
    }

    /** 此刻有没有哪一路判的是被别的程序拿着（TAKEN）。给 {@link CameraTaken#othersHold} 答（阶段 7 随它一起删）。主线程上读。 */
    static boolean anyTakenAnywhere() {
        for (ChannelScheduler scheduler : LIVE) {
            if (scheduler.anyTaken()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 此刻有没有哪一路丢了 / 开不起来、原因是「被占用」一类（被断开、报错 1 / 2 …，{@link CameraTaken.Reason#inUseKind}），
     * 还没判完（关完再过 300 ms 才判）。给 {@link CameraTaken#othersHold} 答：协调器在丢的那一刻就要知道这一次停录算不算
     * 接回的次数，那时还判不出来（审查 2026-10-11）。阶段 5 起相机丢了不再整次停、协调器不再问它，
     * 阶段 7 随 {@link CameraTaken#othersHold} 一起删。主线程上读。
     */
    static boolean takeoverPendingAnywhere() {
        for (ChannelScheduler scheduler : LIVE) {
            for (ChannelRules.Lane lane : scheduler.w.lanes) {
                ChannelRules.Failure failure = lane.failure;
                if (failure != null && failure.reason != null && failure.reason.inUseKind()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ================================================================= 这一份

    private final Clock clock;
    private final Poster poster;
    private final Host host;
    private final Journal journal;

    /** 每一路的相机，按 key（主线程）。 */
    private final Map<String, CameraPort> ports = new LinkedHashMap<>();
    /** 规则的世界：每一路的事实和账（主线程）。 */
    private ChannelRules.World w = new ChannelRules.World(new ArrayList<>());

    private final AtomicBoolean roundPosted = new AtomicBoolean();
    private final Runnable roundTask = this::round;
    private final Runnable timerTask = this::wake;

    /** 登记表的监听叫过来、登记表真的变了的次数（哪个线程都能加；每一轮收走）。 */
    private final AtomicInteger needsChanges = new AtomicInteger();
    /** 监听上一次看到的登记表：车机要睡那一项变了也会叫，但那不算「登记表变了」（不解除停手）。 */
    private final AtomicReference<Set<CameraNeeds.Holder>> notifiedHolders =
            new AtomicReference<>(EnumSet.noneOf(CameraNeeds.Holder.class));
    /** 第一次看到「车机要睡」的时刻（调度的钟）；0 = 没有。手上那一步最多再等 1 秒从这里算。 */
    private final AtomicLong sleepNoticedAt = new AtomicLong();
    /** {@link #exit} 的时刻（调度的钟）；0 = 没退出。 */
    private volatile long exitAt;
    /** 访问优先级变了：下一轮马上问一次。 */
    private volatile boolean askRequested;
    /** 退出之后都关完了：不再跑。 */
    private volatile boolean stopped;

    // ---- 主线程上的记账（写黑匣子、派发「输出被丢」用）----

    /** 手上那一步的上下文（规则那一份在 {@code w.step}）。 */
    private Running running;
    private String lastWait;
    private String lastDeferred;
    private final Set<CameraNeeds.Holder> lastHolders = EnumSet.noneOf(CameraNeeds.Holder.class);
    private final List<String> lastRecordKeys = new ArrayList<>();
    private boolean mainWasForeground;
    private boolean screenSeen;
    private long sleepNoted;
    private boolean exitNoted;
    private final List<String> trailKeys = new ArrayList<>();
    private final List<Long> trailMs = new ArrayList<>();
    private CloseRound closeRound;
    /** 每一路最近一次关完的时刻（调度的钟）。 */
    private final Map<String, Long> closedAt = new HashMap<>();
    /** 每一路最近一次丢了之后关完用了多久。 */
    private final Map<String, Long> lossCloseMs = new HashMap<>();
    /** 每一路相机服务最后一句的原始时刻（elapsedRealtime），和问来改过的那一句对着看：放开是不是问出来的。 */
    private final Map<String, Long> wordAtRaw = new HashMap<>();
    private final Map<String, Long> askedWordAt = new HashMap<>();
    /** 等着派发的「录像输出离开了会话」：改输出摘掉的（REMOVED）、随相机关了的（CLOSED）。 */
    private final Map<String, Map<ChannelRules.Out, SingleCamera.Drop>> drops = new HashMap<>();
    /** 开之前在等录像输出的那几路，从什么时候起。 */
    private final Map<String, Long> recordWaitSince = new HashMap<>();
    /** 每一路我们最近一次开好、关完的时刻（调度的钟）：之后 {@link #OWN_WORD_GRACE_MS} 以内相机服务的话还算我们的。 */
    private final Map<String, Long> ownStepEndedAt = new HashMap<>();
    /**
     * 交给调度、等它离开会话再放的消费者（{@link #handOver}），按路：输出 → 怎么放。大纲 §7.6 主界面重建时留住的预览画布。
     * 不随 {@link #setLanes} 清：路没了的下一轮就放，不漏。
     */
    private final Map<String, Map<ChannelRules.Out, Runnable>> handedOver = new LinkedHashMap<>();

    // ---- 告诉录像那一侧的（主线程）----

    private Recording recording = NO_RECORDING;
    /** 每一路上一次告诉录像时在出画面的那一份会话（它配好的时刻；0 = 没在出画面）。 */
    private final Map<String, Long> toldStreaming = new HashMap<>();
    /** 每一路上一次告诉录像时的 condition。 */
    private final Map<String, ChannelRules.Condition> toldCondition = new HashMap<>();
    /** 每一路上一次告诉录像时，它是不是关着、而此刻用不了相机（{@link #cannotOpen}）。 */
    private final Map<String, Boolean> toldCannotOpen = new HashMap<>();
    /** 这一轮里走了的几路（丢了、我们关了、在救），一轮最后按先后告诉录像。 */
    private final List<Object[]> gone = new ArrayList<>();

    ChannelScheduler(Clock clock, Poster poster, Host host, Journal journal) {
        this.clock = Objects.requireNonNull(clock);
        this.poster = Objects.requireNonNull(poster);
        this.host = Objects.requireNonNull(host);
        this.journal = Objects.requireNonNull(journal);
        LIVE.add(this);
    }

    /**
     * 这一份管线要动的几路（主线程，MultiCameraManager.initCameras）：按开的次序排好，每一路从「关着、没人要」起。
     */
    void setLanes(Map<String, CameraPort> lanes) {
        ports.clear();
        List<ChannelRules.Lane> list = new ArrayList<>();
        for (Map.Entry<String, CameraPort> entry : lanes.entrySet()) {
            ports.put(entry.getKey(), entry.getValue());
            list.add(new ChannelRules.Lane(entry.getKey(), entry.getValue().cameraId()));
        }
        w = new ChannelRules.World(list);
        running = null;
        lastWait = null;
        lastDeferred = null;
        trailKeys.clear();
        trailMs.clear();
        closeRound = null;
        closedAt.clear();
        lossCloseMs.clear();
        wordAtRaw.clear();
        askedWordAt.clear();
        drops.clear();
        recordWaitSince.clear();
        ownStepEndedAt.clear();
        toldStreaming.clear();
        toldCondition.clear();
        toldCannotOpen.clear();
        gone.clear();
        wake();
    }

    /** 录像那一侧（主线程，MultiCameraManager 构造里接上）：之后每一轮最后照 {@link Recording} 告诉它。 */
    void setRecording(Recording recording) {
        this.recording = recording == null ? NO_RECORDING : recording;
    }

    // ================================================================= 叫醒（哪个线程都能调）

    /** 叫醒：几次叫醒并成一次 post，这一轮跑完之前再叫的不另排。 */
    public void wake() {
        if (!stopped && roundPosted.compareAndSet(false, true)) {
            poster.post(roundTask);
        }
    }

    /**
     * 登记表变了（CameraNeeds 的监听，在登记的那个线程上调）。车机要睡发布时当场把 {@link #closingAnywhere} 置真 ——
     * 熄屏那一刻协调器写下它，紧接着 ScreenOffRecording 就要读「关完没有」，不能等 post 出去的那一轮。
     */
    void onNeedsChanged() {
        Set<CameraNeeds.Holder> now = host.holders();
        Set<CameraNeeds.Holder> before = notifiedHolders.getAndSet(now);
        if (!now.equals(before)) {
            needsChanges.incrementAndGet();
        }
        if (host.headUnitSleeps()) {
            long at = clock.now();
            sleepNoticedAt.compareAndSet(0, at);
            if (!stopped) {
                WINDING.putIfAbsent(this, at);
            }
        }
        wake();
    }

    /**
     * 退出（MultiCameraManager.release）：同步写下「退出」，置 {@link #closingAnywhere}；登记表从此作废。
     * 手上那一步做完后按次序关（环视单独先关，座舱一起关），关完调度就停。只关，不开、不改输出、不救。重复调用无害。
     */
    public void exit() {
        if (exitAt != 0 || stopped) {
            return;
        }
        long at = clock.now();
        exitAt = at;
        WINDING.putIfAbsent(this, at);
        wake();
    }

    /** 相机服务说访问优先级变了：叫醒，马上问一次（被拿着的一路才问）—— 绝不因此去开。 */
    void askNow() {
        askRequested = true;
        wake();
    }

    // ================================================================= 交给调度的消费者（主线程）

    /**
     * 把一个已经撤回了声明、可能还挂在会话里的输出交给调度：等这一路的会话里没有它了、相机层手上也没有动作
     * （或者这一路关了、调度停了），再调 {@code release} 放掉它的消费者。同一个输出再交一次，换成新的 {@code release}。
     *
     * <p>摘不摘、什么时候摘不在这里判：消费者还活着，它就是「留着的输出」，照「减输出能拖就拖」
     * （{@link ChannelRules#composition}）等下一次本来要动、或者关相机时一起摘 —— 这里只等它离开会话。
     * 不能早放：会话还带着它的时候放掉，它就成了死输出，那一路整条重复请求停下，只能马上摘（大纲 §6 风险 2）。</p>
     *
     * <p>用在大纲 §7.6（项目所有者 2026-10-11）：主界面重建（换日夜模式、换语言、从最近任务划掉）时
     * onSurfaceTextureDestroyed 返回 false，预览画布交到这里；新界面用 {@code TextureView.setSurfaceTexture}
     * 接回同一块的，先 {@link #reclaim} 拿回去 —— 同一块画布就是同一个输出，通道零变动。</p>
     */
    public void handOver(String key, ChannelRules.Out out, Runnable release) {
        if (key == null || out == null || release == null) {
            return;
        }
        if (stopped) {
            release.run();   // 调度停了：相机都关完了，没有会话会带着它
            return;
        }
        Map<ChannelRules.Out, Runnable> outs = handedOver.get(key);
        if (outs == null) {
            outs = new LinkedHashMap<>();
            handedOver.put(key, outs);
        }
        outs.put(out, release);
        wake();
    }

    /**
     * 拿回交给调度的那一个（新界面接回了同一块画布）：从此不归调度放，归接回的那一方。
     *
     * @return true：拿回来了（还没放）；false：没交过，或者已经放了
     */
    public boolean reclaim(String key, ChannelRules.Out out) {
        Map<ChannelRules.Out, Runnable> outs = handedOver.get(key);
        if (outs == null || outs.remove(out) == null) {
            return false;
        }
        if (outs.isEmpty()) {
            handedOver.remove(key);
        }
        return true;
    }

    // ================================================================= 相机层报来的（主线程，由管理器 post 过来）

    /**
     * 设备开成了：在开的那一路开成了；关着的那一路照记成开着（{@link #deviceOpened}）—— 调度的账跟着事实走。
     * 在关的不动：那是我们关之前就发出来的一声，设备已经被关相机那一下就地关掉了，关完的回调随后到。
     */
    void onOpened(String cameraId) {
        ChannelRules.Lane lane = live(cameraId);
        if (lane == null) {
            return;
        }
        if (lane.device == ChannelRules.Device.OPENING || lane.device == ChannelRules.Device.CLOSED) {
            deviceOpened(lane, w.now);
        }
        wake();
    }

    /**
     * 我们关的那一次关完了。丢了之后的那一次关看 {@link #onLossClosed}。在开的不动：那是上一次关晚到的一声，
     * 这一次打开已经在等它关完了（SingleCamera 打开前等同一台相机上一次关完）。
     */
    void onClosed(String cameraId) {
        ChannelRules.Lane lane = live(cameraId);
        if (lane == null) {
            return;
        }
        if (lane.device == ChannelRules.Device.CLOSING || lane.device == ChannelRules.Device.OPEN) {
            setDevice(lane, ChannelRules.Device.CLOSED, w.now);
            closedAt.put(lane.key, w.now);
            ownStepEndedAt.put(lane.key, w.now);
        }
        wake();
    }

    /** 会话配好了、出了第一帧、旧的报错：事实下一轮读，这里只叫醒。 */
    void onCameraEvent() {
        wake();
    }

    /**
     * 一路开着的相机丢了，在那一次阻塞的关之前：进 LOSING，算在途，别的路这几秒里一步都不动。
     * 录像那一侧在这一轮最后听说（{@link Recording#onLaneCameraGone}），那一路自己收尾退出。
     */
    void onLossStarted(String cameraId, int code) {
        ChannelRules.Lane lane = live(cameraId);
        if (lane == null) {
            return;
        }
        CameraTaken.Reason reason = CameraTaken.Reason.ofLoss(code);
        ChannelRules.lossStarted(w, lane, reason);
        noteGone(lane.key, Gone.LOST, reason);
        wake();
    }

    /** 丢了的那一路我们这边关完了：300 ms 后待判。 */
    void onLossClosed(String cameraId, long closeMs) {
        ChannelRules.Lane lane = live(cameraId);
        if (lane == null) {
            return;
        }
        ChannelRules.lossClosed(w, lane);
        lossCloseMs.put(lane.key, closeMs);
        closedAt.put(lane.key, w.now);
        wake();
    }

    /** 开不起来（相机层等设备关完才报）：300 ms 后待判。 */
    void onOpenFailed(String cameraId, CameraTaken.Reason reason) {
        ChannelRules.Lane lane = live(cameraId);
        if (lane == null) {
            return;
        }
        ChannelRules.openFailed(w, lane, reason);
        wake();
    }

    /** 相机层自己丢了一样输出（消费者被系统收走、会话配不上）：是录像输出就当场告诉录像那一侧。 */
    void onOutputDropped(String cameraId, ChannelRules.Out out, SingleCamera.Drop why) {
        ChannelRules.Lane lane = live(cameraId);
        if (lane == null) {
            return;
        }
        CameraPort port = ports.get(lane.key);
        if (port != null && out != null && out.kind == ChannelRules.Kind.RECORD) {
            port.dropped(out, why);
        }
        wake();
    }

    /** 回调要用的那一路；调度停了、不认识这台相机是 null。顺手对一下钟。 */
    private ChannelRules.Lane live(String cameraId) {
        if (stopped) {
            return null;
        }
        w.now = clock.now();
        return laneById(cameraId);
    }

    // ================================================================= 对外的事实（主线程上读）

    /**
     * 我们在这一路有没有开或关在途（CameraAvailabilityWatch 分类相机服务那一声用）：在开（含救援的重开，那 300 ms 里
     * 设备是空的）、在关、丢了之后的那一次关。只是改会话不算。
     *
     * <p>我们的开、关做完之后 {@link #OWN_WORD_GRACE_MS} 以内也算：相机服务为这一开 / 一关说的那一句（「被占用」「空闲」）
     * 从 binder 线程上来，可能晚于相机线程报的开好 / 关完 —— 不算的话，我们自己关的那一声「空闲」被记成别人引起的变化，
     * 下一步白等 3 秒安静，黑匣子还写一行「相机服务报了别人引起的变化」（审查 2026-10-11）。</p>
     */
    boolean ourStepInFlight(String cameraId) {
        ChannelRules.Lane lane = laneById(cameraId);
        if (lane == null) {
            return false;
        }
        if (lane.device == ChannelRules.Device.OPENING || lane.device == ChannelRules.Device.CLOSING
                || lane.device == ChannelRules.Device.LOSING) {
            return true;
        }
        Long ended = ownStepEndedAt.get(lane.key);
        return ended != null && clock.now() - ended < OWN_WORD_GRACE_MS;
    }

    /** 几路都关好了：账上都关着，相机层也没有一路开着、在开、在关。 */
    public boolean allClosed() {
        return ChannelRules.allClosed(w) && !anyPortHolds();
    }

    /** 此刻有一路判的是被别的程序拿着（TAKEN）。 */
    public boolean anyTaken() {
        return ChannelRules.anyTaken(w);
    }

    /** 一路此刻怎样（给录像和后视镜读）；不在这一份里的当作在起来。 */
    public ChannelRules.Condition condition(String key) {
        ChannelRules.Lane lane = w.lane(key);
        return lane == null ? ChannelRules.Condition.BRINGING_UP : ChannelRules.condition(lane);
    }

    /** 这一路此刻能用：在出画面。 */
    public boolean usable(String key) {
        return condition(key) == ChannelRules.Condition.STREAMING;
    }

    /**
     * 这一路关着、而此刻用不了相机（主界面不在前、前台服务是后台起的，或者开相机报过「现在用不了」）：主界面回到前台之前
     * 不会开它。{@link #condition} 照旧说它在起来；录像读这一句，不把它算作在起来（RecordingLanes.Facts#cannotOpen）。
     */
    public boolean cannotOpen(String key) {
        ChannelRules.Lane lane = w.lane(key);
        return lane != null && ChannelRules.cannotOpen(w, lane);
    }

    /** 拍照不拍这一路：被拿着、被挡着、或者停手了；在出画面的照拍（哪怕它的录像路不在）。 */
    public boolean excludedFromPhoto(String key) {
        ChannelRules.Lane lane = w.lane(key);
        return lane != null && ChannelRules.excludedFromPhoto(lane);
    }

    /** 诊断报告用：每一路的账和手上那一步（英文记号）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (ChannelRules.Lane lane : w.lanes) {
            sb.append(lane).append(" cond=").append(ChannelRules.condition(lane))
                    .append(" want=").append(ChannelRules.wantedBy(w, lane))
                    .append(ChannelRules.holding(w, lane) ? " holding" : "")
                    .append(" ladder=").append(lane.ladder.attempts()).append('/').append(lane.ladder.cycles())
                    .append(lane.ladder.gaveUp() ? " resting" : "").append(lane.ladder.stopped() ? " stopped" : "")
                    .append(lane.absent ? " absent" : "")
                    .append(" word=").append(lane.serviceFree).append(lane.serviceWordOurs ? "(ours)" : "")
                    .append(handedOver.containsKey(lane.key) ? " handedOver=" + handedOver.get(lane.key).keySet() : "")
                    .append('\n');
        }
        sb.append("step=").append(w.step)
                .append(" usable=").append(w.cameraUsable).append(w.unusableSince != 0 ? "(reported)" : "")
                .append(" screenOn=").append(w.screenOn).append(" fgs=").append(w.foregroundService)
                .append(w.sleepSince != 0 ? " sleep" : "").append(w.exitSince != 0 ? " exit" : "")
                .append(stopped ? " stopped" : "");
        return sb.toString();
    }

    /** 测试和停下来时用：从进程级的两张表里摘掉自己。 */
    void detach() {
        LIVE.remove(this);
        WINDING.remove(this);
    }

    // ================================================================= 一轮

    private void round() {
        roundPosted.set(false);
        if (stopped) {
            return;
        }
        long now = clock.now();
        w.now = now;
        refresh(now);
        noteWinding(now);
        ChannelRules.Step step = w.step;
        ChannelRules.Outcome outcome = ChannelRules.outcome(w);
        if (step != null && outcome != ChannelRules.Outcome.RUNNING && outcome != ChannelRules.Outcome.NONE) {
            stepEnded(step, outcome, now);
            ChannelRules.finish(w, outcome);
        }
        note(ChannelRules.settle(w), now);
        pruneRecordWaits();
        ChannelRules.Decision d = ChannelRules.next(w);
        note(d.events, now);
        noteWait(d, now);
        execute(d, now);
        flushTrails(d);
        dispatchDrops();
        releaseHandedOver(false);
        maybeAsk();
        tellRecording();
        if (updateWinding(now)) {
            stop();
            return;
        }
        arm(d, now);
    }

    // ---- 1. 刷新事实

    private void refresh(long now) {
        long offset = host.elapsedNow() - now;
        Set<CameraNeeds.Holder> holders = host.holders();
        int changes = needsChanges.getAndSet(0);
        if (changes > 0) {
            w.needsVersion += changes;
        } else if (!w.holders.equals(holders)) {
            w.needsVersion++;
        }
        w.holders.clear();
        w.holders.addAll(holders);
        w.recordPlan.clear();
        w.recordPlan.addAll(host.recordPlan());
        noteNeeds();

        boolean on = host.screenOn();
        if (on && (!w.screenOn || !screenSeen)) {
            w.screenOnAt = now;
        }
        screenSeen = true;
        w.screenOn = on;
        w.foregroundService = host.foregroundService();
        boolean main = host.mainForeground();
        if (main && !mainWasForeground) {
            // 主界面回到前台：开失败判出来的「现在用不了相机」作废，照常开
            ChannelRules.mainCameForward(w);
        }
        mainWasForeground = main;
        w.cameraUsable = main || host.serviceStartedWhileVisible();
        w.heardAnything = host.heardAnything();
        w.lastOthersChangeAt = ourClock(host.lastOthersChangeAt(), offset);

        if (host.headUnitSleeps()) {
            sleepNoticedAt.compareAndSet(0, now);
            w.sleepSince = sleepNoticedAt.get();
        } else {
            sleepNoticedAt.set(0);
            w.sleepSince = 0;
        }
        w.exitSince = exitAt;
        // 自己也在为退出、车机要睡关：不等另一份 —— 关不用谁放行。等的话两份同时在关时（换车型后紧接着退出）互相等，
        // 谁都要等对方进来满 60 秒才动（审查 2026-10-11）。等另一份关完只为开、改、救（大纲 §6 风险 10）
        w.otherClosingSince = ChannelRules.winding(w) ? 0 : otherClosingSince();

        for (ChannelRules.Lane lane : w.lanes) {
            CameraPort port = ports.get(lane.key);
            if (port != null) {
                refreshLane(lane, port, offset, now);
            }
        }
    }

    private void refreshLane(ChannelRules.Lane lane, CameraPort port, long offset, long now) {
        lane.busySince = port.busySince();
        SingleCamera.SessionOutputs session = port.sessionOutputs();
        lane.session.clear();
        lane.session.addAll(session.outputs);
        lane.sessionSince = session.since;
        lane.dead.clear();
        lane.dead.addAll(port.deadInSession());
        lane.declared.clear();
        lane.declared.addAll(port.declaredOutputs());
        lane.record = host.recordPrep(lane.key);
        lane.firstFrameAt = port.firstFrameAt();
        lane.progressAgeMs = port.progressAgeMs();
        reconcileDevice(lane, port, now);

        CameraAvailabilityWatch.Word word = host.word(lane.cameraId);
        boolean wasAbsent = lane.absent;
        lane.serviceFree = word.free;
        lane.absent = word.absent;
        lane.serviceWordOurs = word.ours;
        lane.serviceWordAt = ourClock(word.at, offset);
        wordAtRaw.put(lane.key, word.at);
        if (wasAbsent && !lane.absent && !port.sizeResolved()) {
            // 它回来了：不在的时候画面尺寸算不出来，现在补上（录制器要按它在开相机之前建好）
            port.resolveSize();
        }
    }

    /**
     * 账上的设备状态跟着事实对一下：设备在我们手上就记成开着（{@link #deviceOpened}）；开着的设备没了、
     * 又没有在途的动作（回调丢了），记成关着。在开、在关、丢了由回调推进，这里不抢。
     *
     * <p>「没有在途的动作」在读过设备之后再问一次相机层，不用这一轮早先读的 {@link ChannelRules.Lane#busySince}：
     * 相机层丢设备时先登记「在关」、再把设备从字段上摘下（SingleCamera.loseDevice），早先那一读可能落在它之前 ——
     * 那样这一路被记成关着，这一轮就可能去开一台别的程序刚拿走的相机（审查 2026-10-11）。</p>
     */
    private void reconcileDevice(ChannelRules.Lane lane, CameraPort port, long now) {
        boolean open = port.deviceOpen();
        if (open && (lane.device == ChannelRules.Device.CLOSED || lane.device == ChannelRules.Device.OPENING)) {
            deviceOpened(lane, now);
        } else if (!open && lane.device == ChannelRules.Device.OPEN && port.busySince() == 0
                && !port.holdsOrIsOpening()) {
            setDevice(lane, ChannelRules.Device.CLOSED, now);
        }
    }

    /**
     * 设备到了我们手上。在开的那一路：开成了。关着的那一路（不是调度这一步开的：旧入口迁完之前、或者回调晚到）：
     * 照记成开着，它挂着的待判作废 —— 设备在我们手上，那一次丢失 / 开不起来已经过去了；接着判的话，相机服务为我们这一开
     * 说的「被占用」会被当成别的程序拿着，在出画面的一路被记成 TAKEN、再也放不开（审查 2026-10-11）。
     */
    private void deviceOpened(ChannelRules.Lane lane, long now) {
        if (lane.device == ChannelRules.Device.CLOSED) {
            lane.failure = null;
        }
        setDevice(lane, ChannelRules.Device.OPEN, now);
        ownStepEndedAt.put(lane.key, now);
    }

    private static void setDevice(ChannelRules.Lane lane, ChannelRules.Device device, long now) {
        lane.device = device;
        lane.deviceSince = now;
    }

    /** 相机服务那边的时刻（elapsedRealtime）换到调度的钟；0 仍是 0，太早的记成 1（很早以前）。 */
    private static long ourClock(long atElapsed, long offset) {
        return atElapsed == 0 ? 0 : Math.max(1, atElapsed - offset);
    }

    /** 另一份调度在为退出、车机要睡关相机，最早从什么时候起；0 = 没有。 */
    private long otherClosingSince() {
        long since = 0;
        for (Map.Entry<ChannelScheduler, Long> entry : WINDING.entrySet()) {
            if (entry.getKey() != this && (since == 0 || entry.getValue() < since)) {
                since = entry.getValue();
            }
        }
        return since;
    }

    private void noteNeeds() {
        List<String> recordKeys = new ArrayList<>();
        if (w.holders.contains(CameraNeeds.Holder.RECORDING)) {
            for (ChannelRules.Lane lane : w.lanes) {
                if (w.recordPlan.contains(lane.key)) {
                    recordKeys.add(lane.key);
                }
            }
        }
        if (lastHolders.equals(w.holders) && lastRecordKeys.equals(recordKeys)) {
            return;
        }
        lastHolders.clear();
        lastHolders.addAll(w.holders);
        lastRecordKeys.clear();
        lastRecordKeys.addAll(recordKeys);
        EnumSet<CameraNeeds.Holder> copy = EnumSet.noneOf(CameraNeeds.Holder.class);
        copy.addAll(w.holders);
        journal.needs(copy, new ArrayList<>(recordKeys));
    }

    /** 车机要睡、退出：第一次看到时各写一行（手上那一步是什么、开了多久）。 */
    private void noteWinding(long now) {
        ChannelRules.Step step = w.step;
        String key = step == null || step.keys.isEmpty() ? null : step.keys.get(0);
        ChannelRules.Move move = step == null ? null : step.move;
        long ms = step == null ? 0 : now - step.startedAt;
        if (w.sleepSince != 0 && sleepNoted != w.sleepSince) {
            sleepNoted = w.sleepSince;
            journal.sleep(key, move, ms);
        } else if (w.sleepSince == 0) {
            sleepNoted = 0;
        }
        if (w.exitSince != 0 && !exitNoted) {
            exitNoted = true;
            journal.exit(key, move, ms);
        }
    }

    // ---- 2. 手上那一步结束了

    private void stepEnded(ChannelRules.Step step, ChannelRules.Outcome outcome, long now) {
        Running r = running;
        running = null;
        if (step.move == ChannelRules.Move.CLOSE) {
            closeEnded(step, now);
            return;
        }
        if (outcome == ChannelRules.Outcome.FAILED || r == null || step.keys.isEmpty()) {
            return;   // 开不起来、这一步里丢了：判定那一行写
        }
        String key = step.keys.get(0);
        ChannelRules.Lane lane = w.lane(key);
        if (lane == null) {
            return;
        }
        boolean timedOut = outcome == ChannelRules.Outcome.TIMED_OUT;
        long frameMs = !timedOut && lane.firstFrameAt >= step.startedAt ? lane.firstFrameAt - step.startedAt : -1;
        long configuredMs = lane.sessionSince >= step.startedAt ? lane.sessionSince - step.startedAt : -1;
        ChannelRules.Decision d = r.decision;
        List<Other> others = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : r.generations.entrySet()) {
            CameraPort other = ports.get(entry.getKey());
            if (other != null) {
                others.add(new Other(entry.getKey(), other.longestFrameGapSince(step.startedAt),
                        other.sessionGeneration() != entry.getValue()));
            }
        }
        journal.stepEnded(new StepReport(step.move, d.why, key, d.rescue, d.attempt, d.ms, d.forWhom,
                kinds(d.outputs), kinds(d.added), kinds(d.removed), timedOut, configuredMs, frameMs, others));
        if (step.move == ChannelRules.Move.OPEN) {
            trailKeys.add(key);
            trailMs.add(timedOut ? now - step.startedAt : frameMs);
        }
    }

    /**
     * 关的一步结束了（都关完了，或者到点）：记进这一轮关的轨迹 —— 环视单独那一步一项，座舱按关完的先后；
     * 到点还没关完的写一行，不再等它们。
     */
    private void closeEnded(ChannelRules.Step step, long now) {
        if (closeRound == null) {
            closeRound = new CloseRound();
        }
        boolean surroundStep = step.maxMs == ChannelRules.CLOSE_SURROUND_MAX_MS;
        List<String> stuck = new ArrayList<>();
        List<String> doneKeys = new ArrayList<>();
        List<Long> doneMs = new ArrayList<>();
        for (String key : step.keys) {
            ChannelRules.Lane lane = w.lane(key);
            if (lane == null) {
                continue;
            }
            if (lane.device != ChannelRules.Device.CLOSED) {
                stuck.add(key);
                continue;
            }
            Long at = closedAt.get(key);
            long ms = (at != null && at >= step.startedAt ? at : now) - step.startedAt;
            int i = 0;
            while (i < doneMs.size() && doneMs.get(i) <= ms) {
                i++;
            }
            doneKeys.add(i, key);
            doneMs.add(i, ms);
        }
        if (!stuck.isEmpty()) {
            journal.closeTimedOut(stuck, step.maxMs, surroundStep);
        }
        if (surroundStep) {
            closeRound.surroundKey = step.keys.get(0);
            closeRound.surroundTimedOut = !stuck.isEmpty();
            closeRound.surroundMs = closeRound.surroundTimedOut ? step.maxMs
                    : doneMs.isEmpty() ? now - step.startedAt : doneMs.get(0);
        } else {
            closeRound.cabinKeys.addAll(doneKeys);
            closeRound.cabinMs.addAll(doneMs);
        }
    }

    // ---- 3. 记账报的事

    private void note(List<ChannelRules.Event> events, long now) {
        for (ChannelRules.Event event : events) {
            switch (event.type) {
                case HOLD_STARTED:
                    journal.holding(event.keys, outlets(event.keys));
                    break;
                case HOLD_CANCELLED: {
                    ChannelRules.Lane lane = w.lane(event.keys.get(0));
                    journal.holdCancelled(event.keys, lane == null
                            ? EnumSet.noneOf(CameraNeeds.Holder.class) : copyOf(ChannelRules.wantedBy(w, lane)));
                    break;
                }
                case RECOGNIZED: {
                    ChannelRules.Lane lane = w.lane(event.keys.get(0));
                    if (lane != null) {
                        journal.recognized(lane.key, lane.cameraId, event.absent);
                    }
                    break;
                }
                case JUDGED:
                    judged(event, now);
                    break;
                case RELEASED: {
                    ChannelRules.Lane lane = w.lane(event.keys.get(0));
                    if (lane != null) {
                        Long asked = askedWordAt.remove(lane.key);
                        boolean byAsk = asked != null && asked.equals(wordAtRaw.get(lane.key));
                        journal.released(lane.key, lane.cameraId, event.verdict, event.ms, byAsk);
                    }
                    break;
                }
                case STOP_LIFTED:
                    journal.stopLifted(event.keys.get(0), event.cause, copyOf(w.holders));
                    break;
                case GAVE_UP:
                    journal.gaveUp(event.keys.get(0));
                    break;
                case STOPPED: {
                    CameraPort port = ports.get(event.keys.get(0));
                    journal.stopped(event.keys.get(0), port == null ? null : port.lastErrorName());
                    break;
                }
                default:
                    break;
            }
        }
    }

    /**
     * 判完了一路：写一行；判成被别的程序拿着（TAKEN）的，按丢的 / 开不起来的那一刻记争用、查嫌疑应用 ——
     * 主动认出的不查（不知道是什么时候拿的，和「注册时报的初始状态不查」同一条规矩）。
     */
    private void judged(ChannelRules.Event event, long now) {
        ChannelRules.Lane lane = w.lane(event.keys.get(0));
        ChannelRules.Failure failure = event.failure;
        if (lane == null || failure == null) {
            return;
        }
        List<String> takenIds = new ArrayList<>();
        for (ChannelRules.Lane other : w.lanes) {
            if (other != lane && other.mark == CameraTaken.Verdict.TAKEN) {
                takenIds.add(other.cameraId);
            }
        }
        long wordBeforeClose = lane.serviceWordAt != 0 && failure.closedAt != 0
                ? failure.closedAt - lane.serviceWordAt : Long.MIN_VALUE;
        Long closeMs = lossCloseMs.get(lane.key);
        journal.judged(new Judgement(lane.key, lane.cameraId, event.verdict, failure.origin, failure.reason,
                failure.origin == ChannelRules.Failure.Origin.LOSS && closeMs != null ? closeMs : -1,
                w.heardAnything, lane.serviceFree, lane.absent, wordBeforeClose, takenIds, lane.ladder.attempts()));
        if (event.verdict == CameraTaken.Verdict.TAKEN) {
            host.othersTook(lane.cameraId, failure.at + (host.elapsedNow() - now));
        }
    }

    /** 保持中的这几路画面接着送进哪几样（留着的活输出；一样都没有就是出帧口）。 */
    private Set<ChannelRules.Kind> outlets(List<String> keys) {
        Set<ChannelRules.Kind> kinds = EnumSet.noneOf(ChannelRules.Kind.class);
        for (String key : keys) {
            ChannelRules.Lane lane = w.lane(key);
            if (lane != null) {
                kinds.addAll(kinds(ChannelRules.live(lane)));
            }
        }
        if (kinds.isEmpty()) {
            kinds.add(ChannelRules.Kind.SINK);
        }
        return kinds;
    }

    // ---- 4. 等（只在原因变了时写）

    private void noteWait(ChannelRules.Decision d, long now) {
        String sig = null;
        Wait wait = null;
        if (d.move == ChannelRules.Move.WAIT) {
            String key = d.key();
            switch (d.why) {
                case LOSING:
                case BUSY: {
                    CameraPort port = key == null ? null : ports.get(key);
                    ChannelRules.Lane lane = key == null ? null : w.lane(key);
                    long since = lane == null ? 0
                            : d.why == ChannelRules.Why.LOSING ? lane.deviceSince : lane.busySince;
                    sig = d.why + "|" + key;
                    wait = new Wait(d.why, key, null, d.ms, port == null ? "" : port.describeBusy(),
                            since == 0 ? 0 : now - since, null, null, false, false);
                    break;
                }
                case OTHER_INSTANCE:
                    sig = d.why.name();
                    wait = new Wait(d.why, null, null, d.ms, "", 0, null, null, false, false);
                    break;
                case QUIET:
                    sig = d.why + "|" + key + "|" + d.pending;
                    wait = quietWait(d, now);
                    break;
                case RECORD_OUTPUT:
                    sig = d.why + "|" + key;
                    if (key != null && !recordWaitSince.containsKey(key)) {
                        recordWaitSince.put(key, now);
                    }
                    wait = new Wait(d.why, key, d.pending, d.ms, "", 0, null, null, false, false);
                    break;
                default:
                    // 手上那一步、待判、座舱等环视关完、退出时等关完：都是我们自己的步子，不写
                    sig = d.why.name();
                    break;
            }
        }
        if (wait != null && !Objects.equals(sig, lastWait)) {
            journal.waiting(wait);
        }
        lastWait = sig;

        String deferred = null;
        Unusable why = null;
        if (d.deferredKey != null) {
            why = w.unusableSince != 0 ? Unusable.REPORTED
                    : w.foregroundService ? Unusable.SERVICE_FROM_BACKGROUND : Unusable.NO_SERVICE;
            deferred = d.deferredKey + "|" + why;
        }
        if (deferred != null && !deferred.equals(lastDeferred)) {
            journal.deferred(d.deferredKey, why);
        }
        lastDeferred = deferred;
    }

    /**
     * 开之前等录像输出的那几路，不再等着开的作废：录像登记放了，或者那一路不再是「该开、关着、没被拿着、不在救」。
     * 不作废的话，很久以后为别的原因开它时（预览、放开之后）会写一行从没发生过的「等录像输出：…」（审查 2026-10-11）。
     */
    private void pruneRecordWaits() {
        if (recordWaitSince.isEmpty()) {
            return;
        }
        if (w.recordingSince == 0) {
            recordWaitSince.clear();
            return;
        }
        for (Iterator<String> it = recordWaitSince.keySet().iterator(); it.hasNext(); ) {
            ChannelRules.Lane lane = w.lane(it.next());
            if (lane == null || lane.device != ChannelRules.Device.CLOSED || lane.mark != null
                    || ChannelRules.rescuing(lane) || !ChannelRules.shouldOpen(w, lane)) {
                it.remove();
            }
        }
    }

    /** 等安静：别人引起的变化是多久以前、是相机服务对哪一路说的哪一句（认得出的话）。 */
    private Wait quietWait(ChannelRules.Decision d, long now) {
        ChannelRules.Lane source = null;
        for (ChannelRules.Lane lane : w.lanes) {
            if (!lane.serviceWordOurs && lane.serviceWordAt != 0 && lane.serviceWordAt == w.lastOthersChangeAt) {
                source = lane;
                break;
            }
        }
        return new Wait(d.why, d.key(), d.pending, d.ms, "", now - w.lastOthersChangeAt,
                source == null ? null : source.cameraId, source == null ? null : source.serviceFree,
                source != null && source.absent, source != null && source.device == ChannelRules.Device.OPEN);
    }

    // ---- 5. 照做

    private void execute(ChannelRules.Decision d, long now) {
        switch (d.move) {
            case OPEN:
                open(d, now);
                break;
            case APPLY:
                apply(d, now);
                break;
            case RESCUE:
                rescue(d, now);
                break;
            case CLOSE:
                close(d, now);
                break;
            default:
                break;
        }
    }

    private void open(ChannelRules.Decision d, long now) {
        String key = d.key();
        ChannelRules.Lane lane = w.lane(key);
        CameraPort port = ports.get(key);
        if (lane == null || port == null) {
            return;
        }
        Long waitedSince = recordWaitSince.remove(key);
        if (waitedSince != null) {
            journal.recordWaitEnded(key, now - waitedSince, hasKind(d.outputs, ChannelRules.Kind.RECORD));
        }
        if (!port.open(d.outputs)) {
            return;   // 已经开着、在开（旧入口开的），或者没有相机线程（那时相机层已经报了开不起来）
        }
        setDevice(lane, ChannelRules.Device.OPENING, now);
        begin(d, now);
    }

    private void apply(ChannelRules.Decision d, long now) {
        String key = d.key();
        ChannelRules.Lane lane = w.lane(key);
        CameraPort port = ports.get(key);
        if (lane == null || port == null || !port.applyOutputs(d.outputs)) {
            return;
        }
        queueRemoved(lane, d.outputs);
        begin(d, now);
    }

    private void rescue(ChannelRules.Decision d, long now) {
        String key = d.key();
        ChannelRules.Lane lane = w.lane(key);
        CameraPort port = ports.get(key);
        if (lane == null || port == null) {
            return;
        }
        boolean rebuild = d.rescue == ChannelRules.Rescue.REBUILD;
        boolean started = rebuild ? port.rebuildSession(d.outputs) : port.reopen(d.outputs);
        if (!started) {
            return;
        }
        // 开着却卡住了的一路（关着等救的那种丢的时候已经说过）：录像那一路自己收尾退出，救好了带着录像输出出画面再加入
        if (lane.device == ChannelRules.Device.OPEN || lane.device == ChannelRules.Device.OPENING) {
            noteGone(key, Gone.RESCUED, null);
        }
        queueRemoved(lane, d.outputs);
        if (!rebuild) {
            setDevice(lane, ChannelRules.Device.OPENING, now);
        }
        begin(d, now);
    }

    /**
     * 关：先在账上记成在关（相机层的回调可能就在 close 里同步来，由管理器 post 过来），再交给相机层去关。
     * 会话里带着的录像输出随相机关了：关完时告诉录像那一侧。
     */
    private void close(ChannelRules.Decision d, long now) {
        List<String> keys = new ArrayList<>();
        for (String key : d.keys) {
            ChannelRules.Lane lane = w.lane(key);
            if (lane == null || ports.get(key) == null) {
                continue;
            }
            keys.add(key);
            for (ChannelRules.Out out : lane.session) {
                if (out.kind == ChannelRules.Kind.RECORD) {
                    queueDrop(key, out, SingleCamera.Drop.CLOSED);
                }
            }
            setDevice(lane, ChannelRules.Device.CLOSING, now);
            closedAt.remove(key);
            noteGone(key, Gone.CLOSED, null);
        }
        if (keys.isEmpty()) {
            return;
        }
        journal.closing(keys, d.why);
        String why = closeWhy(d.why);
        for (String key : keys) {
            ports.get(key).close(why);
        }
        begin(d, now);
    }

    private void begin(ChannelRules.Decision d, long now) {
        ChannelRules.begin(w, d);
        Map<String, Integer> generations = new LinkedHashMap<>();
        if (d.move != ChannelRules.Move.CLOSE) {
            for (ChannelRules.Lane lane : w.lanes) {
                CameraPort port = ports.get(lane.key);
                if (!d.keys.contains(lane.key) && port != null && ChannelRules.streaming(lane)) {
                    generations.put(lane.key, port.sessionGeneration());
                }
            }
        }
        running = new Running(d, generations);
    }

    /** 关的原因，给相机那一行（「相机 2（环视）已关（…）」）的英文短语。 */
    private static String closeWhy(ChannelRules.Why why) {
        switch (why) {
            case EXIT:
                return "exit";
            case SLEEP:
                return "head-unit-sleeps";
            case SCREEN_OFF:
                return "screen-off";
            case HOLD_OVER:
                return "hold-over";
            default:
                return "unwanted";
        }
    }

    // ---- 6. 「输出被丢」：调度改输出摘掉的、关相机带走的，派给录像那一侧

    /** 改输出、救之后会话里不再要的录像输出：等旧会话真的没了再告诉（不然录制器放早了，相机还往它那里送帧）。 */
    private void queueRemoved(ChannelRules.Lane lane, Set<ChannelRules.Out> next) {
        Map<ChannelRules.Out, SingleCamera.Drop> queued = drops.get(lane.key);
        if (queued != null) {
            queued.keySet().removeAll(next);   // 又要了的不再算被丢
        }
        for (ChannelRules.Out out : lane.session) {
            if (out.kind == ChannelRules.Kind.RECORD && !next.contains(out)) {
                queueDrop(lane.key, out, SingleCamera.Drop.REMOVED);
            }
        }
    }

    private void queueDrop(String key, ChannelRules.Out out, SingleCamera.Drop why) {
        Map<ChannelRules.Out, SingleCamera.Drop> queued = drops.get(key);
        if (queued == null) {
            queued = new LinkedHashMap<>();
            drops.put(key, queued);
        }
        queued.put(out, why);
    }

    /**
     * 派发：关完了的一路，它带走的录像输出一律「随相机关了」；开着的一路，会话里已经没有它、相机层手上也没有动作
     * （旧会话关掉了、新会话配好了）才算「摘掉了」。
     */
    private void dispatchDrops() {
        for (Iterator<Map.Entry<String, Map<ChannelRules.Out, SingleCamera.Drop>>> it = drops.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<String, Map<ChannelRules.Out, SingleCamera.Drop>> entry = it.next();
            ChannelRules.Lane lane = w.lane(entry.getKey());
            CameraPort port = ports.get(entry.getKey());
            if (lane == null || port == null) {
                it.remove();
                continue;
            }
            boolean closed = lane.device == ChannelRules.Device.CLOSED;
            for (Iterator<Map.Entry<ChannelRules.Out, SingleCamera.Drop>> outs = entry.getValue().entrySet().iterator();
                 outs.hasNext(); ) {
                Map.Entry<ChannelRules.Out, SingleCamera.Drop> drop = outs.next();
                if (closed) {
                    outs.remove();
                    port.dropped(drop.getKey(), SingleCamera.Drop.CLOSED);
                } else if (lane.device == ChannelRules.Device.OPEN && lane.busySince == 0
                        && !lane.session.contains(drop.getKey())) {
                    outs.remove();
                    port.dropped(drop.getKey(), drop.getValue());
                }
            }
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    /**
     * 交给调度的消费者（{@link #handOver}），离开了会话的放掉：这一路开着或关着、相机层手上没有动作、会话快照里没有它
     * （照做之后再问一次相机层，不用这一轮早先读的 —— 这一轮刚开始的改输出、关还在途，旧会话可能还带着它）。
     * 这一路不在这一份里、或者调度停了（{@code all}），一律放。放在遍历之外调，{@code release} 里再交、再拿回都无害。
     */
    private void releaseHandedOver(boolean all) {
        if (handedOver.isEmpty()) {
            return;
        }
        List<Runnable> due = new ArrayList<>();
        for (Iterator<Map.Entry<String, Map<ChannelRules.Out, Runnable>>> it = handedOver.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<String, Map<ChannelRules.Out, Runnable>> entry = it.next();
            ChannelRules.Lane lane = w.lane(entry.getKey());
            CameraPort port = ports.get(entry.getKey());
            boolean idle = lane == null || port == null
                    || (port.busySince() == 0
                    && (lane.device == ChannelRules.Device.OPEN || lane.device == ChannelRules.Device.CLOSED));
            SingleCamera.SessionOutputs session = port == null ? SingleCamera.SessionOutputs.NONE : port.sessionOutputs();
            for (Iterator<Map.Entry<ChannelRules.Out, Runnable>> outs = entry.getValue().entrySet().iterator();
                 outs.hasNext(); ) {
                Map.Entry<ChannelRules.Out, Runnable> handed = outs.next();
                if (all || idle && !session.carries(handed.getKey())) {
                    outs.remove();
                    due.add(handed.getValue());
                }
            }
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
        for (Runnable release : due) {
            release.run();
        }
    }

    // ---- 7. 黑匣子的轨迹：连着开的、一轮关的

    private void flushTrails(ChannelRules.Decision d) {
        ChannelRules.Step step = w.step;
        boolean opening = d.move == ChannelRules.Move.OPEN
                || step != null && step.move == ChannelRules.Move.OPEN
                || d.move == ChannelRules.Move.WAIT && (d.pending == ChannelRules.Move.OPEN
                || d.why == ChannelRules.Why.RECORD_OUTPUT);
        if (!opening && !trailKeys.isEmpty()) {
            journal.openTrail(new ArrayList<>(trailKeys), new ArrayList<>(trailMs));
            trailKeys.clear();
            trailMs.clear();
        }
        boolean closing = d.move == ChannelRules.Move.CLOSE
                || step != null && step.move == ChannelRules.Move.CLOSE
                || d.move == ChannelRules.Move.WAIT && d.why == ChannelRules.Why.SURROUND_CLOSING;
        if (!closing && closeRound != null) {
            CloseRound round = closeRound;
            closeRound = null;
            journal.closeRound(round.surroundKey, round.surroundMs, round.surroundTimedOut,
                    round.cabinKeys, round.cabinMs);
        }
    }

    // ---- 8. 问（只问，不开）

    /**
     * 被拿着的一路有人要、上一次问已经过了 30 秒（刚变成有人要的马上问；规则见 {@link ChannelRules#askDue}），
     * 或者访问优先级变了：问相机服务一次。答回来时每一路写一行（日志每次、黑匣子第一次和每第 10 次）。
     */
    private void maybeAsk() {
        boolean forced = askRequested;
        askRequested = false;
        boolean due = ChannelRules.askDue(w);
        if (!due && !(forced && anyMarked())) {
            return;
        }
        final List<String> keys = new ArrayList<>();
        for (ChannelRules.Lane lane : w.lanes) {
            if (lane.mark != null && ChannelRules.wanted(w, lane)) {
                keys.add(lane.key);
            }
        }
        ChannelRules.asked(w);
        host.ask(corrected -> poster.post(() -> answered(keys, corrected)));
    }

    private boolean anyMarked() {
        for (ChannelRules.Lane lane : w.lanes) {
            if (lane.mark != null) {
                return true;
            }
        }
        return false;
    }

    /** 问的答案回来了（主线程）：照回放改了的那几路记下（放开那一行据此写「问出来的」），每一路写一行，叫醒。 */
    private void answered(List<String> keys, Set<String> corrected) {
        if (stopped) {
            return;
        }
        long now = clock.now();
        for (String cameraId : corrected) {
            ChannelRules.Lane lane = laneById(cameraId);
            if (lane != null) {
                askedWordAt.put(lane.key, host.word(cameraId).at);
            }
        }
        for (String key : keys) {
            ChannelRules.Lane lane = w.lane(key);
            if (lane == null || lane.mark == null) {
                continue;
            }
            CameraAvailabilityWatch.Word word = host.word(lane.cameraId);
            int n = lane.asks;
            journal.asked(key, lane.cameraId, n, now - lane.markSince, word.free, word.absent, n % 10 == 1);
        }
        wake();
    }

    // ---- 9. 告诉录像那一侧

    private void noteGone(String key, Gone why, CameraTaken.Reason reason) {
        gone.add(new Object[]{key, why, reason});
    }

    /**
     * 一轮最后告诉录像那一侧（{@link Recording}）：先说这一轮里走了的几路（录像那一路先退出），再说 condition 变了的
     * （含关着时能不能用相机变了的，{@link #cannotOpen}），
     * 最后说带着一份新会话出了画面的（录像那一路看会话带没带着它的录像输出，带着就加入）。
     * 走了的那一路，下一份出画面的会话一定算新的。录像那一侧在回调里只叫醒、声明、撤回，不在这里再跑一轮。
     */
    private void tellRecording() {
        List<Object[]> left = new ArrayList<>(gone);
        gone.clear();
        for (Object[] g : left) {
            toldStreaming.remove((String) g[0]);
            recording.onLaneCameraGone((String) g[0], (Gone) g[1], (CameraTaken.Reason) g[2]);
        }
        List<String> changed = new ArrayList<>();
        List<String> streamingNow = new ArrayList<>();
        for (ChannelRules.Lane lane : w.lanes) {
            ChannelRules.Condition condition = ChannelRules.condition(lane);
            boolean conditionChanged = toldCondition.put(lane.key, condition) != condition;
            boolean cannotOpen = ChannelRules.cannotOpen(w, lane);
            boolean cannotOpenChanged = !Boolean.valueOf(cannotOpen).equals(toldCannotOpen.put(lane.key, cannotOpen));
            if (conditionChanged || cannotOpenChanged) {
                changed.add(lane.key);
            }
            long session = ChannelRules.streaming(lane) ? lane.sessionSince : 0L;
            Long told = toldStreaming.put(lane.key, session);
            if (session != 0 && (told == null || told != session)) {
                streamingNow.add(lane.key);
            }
        }
        for (String key : changed) {
            recording.onLaneConditionChanged(key);
        }
        for (String key : streamingNow) {
            recording.onLaneCameraStreaming(key);
        }
    }

    // ---- 10. 退出、车机要睡：关完了没有

    /**
     * 退出、车机要睡时：都关完了就从「在关」那张表里拿掉；退出的关完了调度就停（返回 true）。
     * 车机要睡被亮屏清掉了：手上没有关在途就拿掉。
     */
    private boolean updateWinding(long now) {
        boolean winding = w.exitSince != 0 || w.sleepSince != 0;
        if (winding) {
            if (allClosed()) {
                WINDING.remove(this);
                return w.exitSince != 0;
            }
            WINDING.putIfAbsent(this, now);
            return false;
        }
        ChannelRules.Step step = w.step;
        // 这一轮读过之后才发布的（监听在别的线程上叫）不拿掉：下一轮就看得到
        if ((step == null || step.move != ChannelRules.Move.CLOSE) && exitAt == 0 && !host.headUnitSleeps()) {
            WINDING.remove(this);
        }
        return false;
    }

    private void stop() {
        stopped = true;
        poster.cancel(timerTask);
        dispatchDrops();
        releaseHandedOver(true);
        detach();
        host.stopped();
    }

    // ---- 11. 下一次什么时候醒

    /**
     * 最迟 {@link ChannelRules#TICK_MS} 醒一次；手上那一步到点、等的东西到点（待判 300 ms、安静、录像输出、车机要睡的 1 秒）、
     * 保持到点，早一点醒。
     *
     * <p>待判的只算还没到点的：已经到点、只等别的丢失关完的那一路，算进来就是负数，按 1 ms 醒 —— 另一路那一次关要 5–8 秒，
     * 这几秒里主线程每一毫秒跑一轮（审查 2026-10-11）。另一路关完有回调叫醒，关卡死的由在途封顶（等的 {@code d.ms}）叫醒。</p>
     */
    private void arm(ChannelRules.Decision d, long now) {
        long delay = ChannelRules.TICK_MS;
        ChannelRules.Step step = w.step;
        if (step != null) {
            delay = Math.min(delay, step.startedAt + step.maxMs - now);
        }
        if (d.move == ChannelRules.Move.WAIT && d.ms >= 0) {
            delay = Math.min(delay, d.ms);
        }
        for (ChannelRules.Lane lane : w.lanes) {
            if (ChannelRules.holding(w, lane)) {
                delay = Math.min(delay, lane.unwantedSince + ChannelRules.HOLD_MS - now);
            }
            ChannelRules.Failure failure = lane.failure;
            if (failure != null && failure.closedAt != 0) {
                long due = failure.closedAt + ChannelRules.JUDGE_DELAY_MS;
                if (due > now) {
                    delay = Math.min(delay, due - now);
                }
            }
        }
        poster.cancel(timerTask);
        poster.postDelayed(timerTask, Math.max(1, delay));
    }

    // ================================================================= 小工具

    private ChannelRules.Lane laneById(String cameraId) {
        if (cameraId == null) {
            return null;
        }
        for (ChannelRules.Lane lane : w.lanes) {
            if (cameraId.equals(lane.cameraId)) {
                return lane;
            }
        }
        return null;
    }

    private boolean anyPortHolds() {
        for (CameraPort port : ports.values()) {
            if (port.holdsOrIsOpening()) {
                return true;
            }
        }
        return false;
    }

    private static Set<ChannelRules.Kind> kinds(Set<ChannelRules.Out> outs) {
        Set<ChannelRules.Kind> kinds = EnumSet.noneOf(ChannelRules.Kind.class);
        for (ChannelRules.Out out : outs) {
            kinds.add(out.kind);
        }
        return kinds;
    }

    private static boolean hasKind(Set<ChannelRules.Out> outs, ChannelRules.Kind kind) {
        for (ChannelRules.Out out : outs) {
            if (out.kind == kind) {
                return true;
            }
        }
        return false;
    }

    private static Set<CameraNeeds.Holder> copyOf(Set<CameraNeeds.Holder> holders) {
        Set<CameraNeeds.Holder> copy = EnumSet.noneOf(CameraNeeds.Holder.class);
        copy.addAll(holders);
        return copy;
    }

    /** 手上那一步的上下文：规则的答案（黑匣子那一行要的为什么、为谁、加了什么），开始时别的在出画面的几路的会话代数。 */
    private static final class Running {
        final ChannelRules.Decision decision;
        final Map<String, Integer> generations;

        Running(ChannelRules.Decision decision, Map<String, Integer> generations) {
            this.decision = decision;
            this.generations = generations;
        }
    }

    /** 一轮关：环视单独那一步、座舱一起那一步，整轮完了写一行「关相机：…」。 */
    private static final class CloseRound {
        String surroundKey;
        long surroundMs;
        boolean surroundTimedOut;
        final List<String> cabinKeys = new ArrayList<>();
        final List<Long> cabinMs = new ArrayList<>();
    }

    @Override
    public String toString() {
        return "ChannelScheduler" + new LinkedHashSet<>(ports.keySet());
    }
}
