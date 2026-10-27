package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.Collections;
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
 *   <li>刷新事实：每一路照 SingleCamera（{@link CameraPort}）填，全局的照 {@link Host} 填 —— 登记表、录像计划、亮熄屏
 *       （含屏幕知不知道）、前台服务、能不能用相机、相机服务对每一路说的最后一句、车机要睡、退出，还有环视熄屏时留不留要的
 *       哨兵模式、在不在开车、「熄屏持续录制」（2026-10-27）；</li>
 *   <li>手上那一步做完没有、到没到顶（{@link ChannelRules#outcome} → {@link ChannelRules#finish}）；结束了的那一步写一行
 *       （做完、到点、出事都写），每一路交一条相机步骤统计（{@link #stepEnded}，2.11.4）；</li>
 *   <li>记账（{@link ChannelRules#settle}）：保持、主动认出被拿走、到点的判定、放开、解除停手；会话里每一样输出算要的、
 *       留着的、还是死的，从什么时候起（{@link #trackOutputs}）；</li>
 *   <li>下一步（{@link ChannelRules#next}），一次只出一步；照做（{@link CameraPort} 的动作），记下这一步
 *       （{@link ChannelRules#begin}）；</li>
 *   <li>录像输出离开了会话（改输出摘掉的），或者关着的一路录像不再留着它（停了录、不在计划里），告诉声明它的那一方
 *       （{@link #dispatchDrops}）；交给它的消费者（{@link #handOver}：主界面重建时留住的预览画布、关掉的超级镜子留住的画布、
 *       移走的盲区画面窗口留住的画布、换下来的录制器）离开了会话的，放掉；</li>
 *   <li>被拿着的一路有人要、到了 30 秒就问一次相机服务（只问，不开）；关完一轮 30 秒的、别的程序拿走一路相机 30 秒的
 *       （{@link #watchAfter}），量一次还开着的那几路（{@link #noteAftermaths}；在那之前我们自己动了它的，量到动它那一刻）；
 *       关座舱时环视开着的，关完那一刻、1 秒、30 秒各量一次环视（{@link Coupling}，2.11.5 第 D2 项）；救完一路接着量别的几路到下一步开始；
 *       关座舱前先关环视的那一趟（规则那一步，2.11.6）走完写一行：环视断了多久、各段多久（{@link SurroundFirst}）；</li>
 *   <li>告诉录像那一侧（{@link Recording}）：哪几路走了、哪几路的样子变了、哪几路带着新会话出了画面 ——
 *       录像按路加入、退出只看这几声，自己从不动相机；</li>
 *   <li>记下诊断报告读的快照（{@link #snapshot()}）。</li>
 * </ol>
 *
 * <h3>线程</h3>
 *
 * <p>只在主线程上跑。每一轮都是 post 出去的：几次叫醒（登记表、相机回调、相机服务、亮熄屏、输出声明、前台服务、
 * 主界面进出前台、计时器、每 {@link ChannelRules#TICK_MS}）并成一次 post —— 熄屏那一下所有监听者都做完了它才跑。
 * 相机回调在相机线程上发，由 MultiCameraManager 转到主线程再交进来（{@link #onOpened} 那几个入口）；从不在回调里
 * 当场跑一轮。能在别的线程上调的只有 {@link #wake}、{@link #exit}、{@link #onNeedsChanged}、{@link #askNow}、
 * {@link #closingAnywhere}：它们只动原子字段，然后叫醒；诊断报告读的 {@link #snapshot()} 是每一轮最后建好的不可变的一份，
 * 放在 volatile 字段里。</p>
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

        /**
         * 救：重开（关着的一路也走这里）。只在这一台相机的开、重开、关还在途（各封顶 60 秒）时合并掉、返回 false；
         * 配会话、关旧会话还挂着不挡它 —— 重开把那一次作废、连设备一起关掉再开。在途超过封顶的那一轮当它丢了，换一轮重开。
         */
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

        /**
         * 这一台相机此刻在关一个设备的话（任何一种关、任何一份实例在关），从什么时候起（调度的钟）；没在关是 0。
         * 在途、手上那一步到点也不放过（ChannelRules 第 1c 项，2.11.4）。
         */
        long closingSince();

        /** 这一台最近一次关完的那一次（时刻是调度的钟）；没关过是 null。开座舱那一行、相机步骤统计要「我们上一次关它」。 */
        SingleCamera.LastClose lastClose();

        /** 眼下这份会话配好以来相机回了什么（开始、结果、失败、丢缓冲，{@link SessionCounts}）。 */
        SessionCounts.Snapshot sessionCounts();

        /** 最后一次收到画面的时刻（调度的钟）；没有过是 0。关座舱时环视最后一帧在什么时候（{@link Coupling}，2.11.5 第 D2 项）。 */
        long lastResultAt();

        /** 这一路相机对象建起来以来一共失败了几次请求（不分会话，只增不减）：关座舱那几行拿开始关那一刻的差。 */
        long captureFailures();

        /** 这一路相机对象建起来以来一共丢了几次缓冲（各样输出加起来，不分会话，只增不减）。 */
        long lostBuffers();

        /** 在途的是什么（给黑匣子那一行，英文记号，不带时长 —— 时长调度自己算）。 */
        String describeBusy();

        /** 眼下这份会话的快照：输出、代号、配好的时刻（调度的钟）。 */
        SingleCamera.SessionOutputs sessionOutputs();

        /**
         * 这一样输出此刻还挂在这一路相机上：眼下的会话带着它，或者会话、设备已经开始关、还没关完（相机还在往它写）。
         * 关一开始会话快照就清了，关会话、关设备还要几百毫秒到十几秒（{@link #sessionCarries}）。
         */
        boolean stillCarries(ChannelRules.Out out);

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

        /** 一个录像、盲区画面输出离开了会话：告诉声明它的那一侧（撤回了的不告诉）。 */
        void dropped(ChannelRules.Out out, SingleCamera.Drop why);

        /**
         * 会话里每一样输出此刻算哪一类、从什么时候起（{@link OutputState}，不可变的一份）：变了才交，只给卡顿现场看
         * （SingleCamera.describeForStall 标「要 / 留着 31 秒 / 死 3 秒」）。
         */
        void outputStates(Map<ChannelRules.Out, OutputState> states);
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

        /** 超级镜子要后座舱：切换范围是「后视 + 后座舱」（{@link ChannelRules.World#mirrorCabin}）。哪个线程都能调。 */
        boolean mirrorWantsCabinRear();

        /**
         * 屏幕亮着（屏幕真没黑：ScreenState.dark 的反面）。通道眼里亮不亮由调度照规则算（{@link ChannelRules#darkForChannel}：
         * 黑满 30 秒、或者车机要睡才算黑），下面两样是它要的另外两个事实。
         */
        boolean screenOn();

        /** 屏幕黑了多久（毫秒）；亮着、或者起点不明（进程起来时、车辆信号读到时已经黑着）是 -1。 */
        long darkForMs();

        /** 黑着时安卓也熄屏了（车机要睡 / 睡着；ScreenState.androidOff）。 */
        boolean androidOff();

        /**
         * 屏幕知道黑没黑（ScreenState.known）：不知道时谁登记着都不开（{@link ChannelRules.Why#SCREEN_UNKNOWN}）。
         * 答「不知道」时 Android 那一层排一下：知道了叫醒调度。
         */
        boolean screenKnown();

        /**
         * 哨兵模式（车辆信号核心那一份）：0 关、1 开、2 布防；读不到、车辆接口没连着是 null。环视熄屏时留不留看它
         * （{@link ChannelRules#judgeSurroundInDark}）；变了 Android 那一层叫醒调度。
         */
        Integer sentry();

        /** 「熄屏持续录制」开着（AppConfig.isScreenOffKeepRecording；开发者「熄屏录制」不算）。每一轮现读。 */
        boolean keepRecordingWhenDark();

        /** 在开车（ScreenOffPlan.driving；车辆接口没连着算没在开车）。变了 Android 那一层叫醒调度。 */
        boolean driving();

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
         * 车上的几样事实（{@link Vehicle}：哨兵模式原值、点火 / 主驾门 / 屏幕电源事件多久以前变过、进程的重要性），
         * 都是缓存的值，不进车辆接口、不进系统服务（主线程上调）。一步开始时读一次：开座舱那一行、相机步骤统计用。
         */
        Vehicle vehicle();

        /**
         * 这一台相机的来历（{@link CameraHistory}：车机重新初始化以来我们开过几台、眼下那一台是第几台、上一次重置）；没有记录是 null。
         * 内存里的一份，不碰盘（主线程上调）。开那一行、关座舱那几行、相机步骤统计都要「环视是不是车机重新初始化后的第一台」（2.11.5 第 D1 项）。
         */
        CameraHistory.Camera history(String cameraId);

        /**
         * 问相机服务一次（只问，不开）。答了在主线程上交回问的结果：照回放改了的那几路（可能没有）、问成了没有；
         * 问不成也交，回放是空的。
         */
        void ask(Consumer<CameraAvailabilityWatch.Asked> answered);

        /** 判出这一路被别的程序拿走了：按丢的 / 开不起来的那一刻（{@link #elapsedNow} 的钟）记争用、查嫌疑应用。 */
        void othersTook(String cameraId, long atElapsed);

        /** 退出之后都关完了，调度停了：摘掉它接在别处的监听。 */
        void stopped();

        /** 前座舱只用一次的规矩开着（暂时，FrontCabinOneShot.ACTIVE；{@link ChannelRules.World#frontCabinOneShot}）。 */
        boolean frontCabinOneShot();

        /**
         * 这次运行启用的前座舱去掉了（{@link ChannelRules.Event.Type#FRONT_CABIN_RETIRED}，通道眼里熄屏的那一刻）：管线、录像、主界面
         * 把 {@code key} 这一路从这次运行里拿掉。只来一次。
         */
        void frontCabinRetired(String key);
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
         * （{@link Gone#CLOSED}；{@code closed} 是关的原因，熄屏放开座舱是 {@link ChannelRules.Why#DARK_RELEASE} ——
         * 退出录像那一行照它写「熄屏：座舱放开」；熄屏放开的环视是 {@link ChannelRules.Why#SCREEN_OFF}、它此刻
         * {@link #releasedByDark}，那一行写「熄屏：环视放开」（2026-10-27）；关座舱前先关环视是 {@link ChannelRules.Why#SURROUND_FIRST}，那一行写
         * 「关座舱前先关环视」，录制器停放，环视开回来带着同一个录像输出出了画面就再加入）、或者它卡住了在救
         * （{@link Gone#RESCUED}：重建会话、重开）。
         *
         * @param reason 丢的那一下；不是丢了的是 null
         * @param closed 我们关它的原因；不是我们关的是 null
         */
        void onLaneCameraGone(String key, Gone why, CameraTaken.Reason reason, ChannelRules.Why closed);

        /**
         * 这一路的 {@link #condition} 变了（被拿着、放开了、在救、停手、在出画面），或者它关着时能不能用相机变了
         * （{@link #cannotOpen}），或者它被不被熄屏放开变了（{@link #releasedByDark}：熄屏放开的座舱亮屏时、放开的环视又留下时，
         * 编码录制在这时补建录制器或者沿用停放着的 —— 熄屏放开时不为它建）。
         */
        void onLaneConditionChanged(String key);

        /**
         * 这一路「谁要它」变了（{@link ChannelRules#wantersChanged}）：会话连着几次带不上录像输出、在等的那一路这时再试 ——
         * 和解除停手同一个解锁。
         */
        void onLaneWantersChanged(String key);
    }

    /** 一路的相机为什么走了（{@link Recording#onLaneCameraGone}）。 */
    enum Gone {
        /** 丢了：被断开、报错（在那一次阻塞的关之前就说）。 */
        LOST,
        /** 我们关的：没人要、保持到点、熄屏（含熄屏放开的环视）、熄屏放开座舱、关座舱前先关环视、车机要睡、退出。 */
        CLOSED,
        /** 开着却没进展（卡住了），调度在救：重建会话或者重开。 */
        RESCUED
    }

    private static final Recording NO_RECORDING = new Recording() {
        @Override
        public void onLaneCameraStreaming(String key) {
        }

        @Override
        public void onLaneCameraGone(String key, Gone why, CameraTaken.Reason reason, ChannelRules.Why closed) {
        }

        @Override
        public void onLaneConditionChanged(String key) {
        }

        @Override
        public void onLaneWantersChanged(String key) {
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

        /**
         * 环视这一段熄屏留不留变了（项目所有者 2026-10-27，{@link ChannelRules#judgeSurroundInDark}）：每变一次一行；通道刚算黑那一下
         * 不管变没变都写。{@code kept} 是此刻留着（{@link ChannelRules#surroundKept}，含设置）；{@code recordsSurround}：录像要它
         * （RECORDING 登记着、计划里有环视）。
         */
        void surroundInDark(boolean kept, ChannelRules.Event.Cause why, Integer sentry, boolean keepSetting,
                            boolean recordsSurround);

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

        /**
         * 开、改输出、救做完了（出了画面）、到点了、或者这一步里出了事（{@link StepReport#failure}：开不起来、丢了 ——
         * 2.11.4 起也写一行，以前什么都不写，10-13 那一次没做完的改输出只在关相机小结的账里看得出来）。判定另由 {@link #judged} 写。
         */
        void stepEnded(StepReport report);

        /**
         * 一步开设备（开、救援的重开）开始了：那一刻的情形（{@link StepContext}）。黑匣子给座舱写一行（项目所有者 2026-10-14 批准的诊断，
         * 2.11.4 第 1a 项）：哨兵、点火 / 车门 / 屏幕电源事件多久以前变过、主界面在不在前、相机服务最后一句、我们上一次关它、车机拿了它多久。
         */
        void opening(StepContext context);

        /**
         * 一步结束了（开、改输出、救、关；做完、出事、到点），每一路一条：相机步骤统计记一条（{@link StepRecord}，2.11.4 R2）。
         * 只在调度写一步结束的那一处交（{@link ChannelScheduler#stepEnded}）。
         */
        void recorded(StepRecord record);

        /** 连着开的这几路：「按次序开相机：环视 1.1s → 后座舱 0.2s → 前座舱 0.3s」。 */
        void openTrail(List<String> keys, List<Long> ms);

        /** 开始关这几路。 */
        void closing(List<String> keys, ChannelRules.Why why);

        /** 关到点了还没关完的这几路（{@code surround}：是环视单独关的那一步）。 */
        void closeTimedOut(List<String> keys, long maxMs, boolean surround);

        /**
         * 一轮关完：「关相机：环视 0.1s → 后座舱 0.1s、前座舱 0.1s」。{@code surroundKey} 为 null 是这一轮没关环视；
         * 座舱按关完的先后。{@code carried}：关的时候各路会话里带走的留着的输出（{@link Carried}），追加在那一行末尾
         * （「带走留着的输出：录像（留了 12 分 3 秒）、预览（31 秒）」）；没有是空的。{@code others}：这一轮关完还开着、
         * 关的时候在出画面的那几路，关的这段时间里最长断帧、会话动没动（熄屏只放开座舱时就是环视，设计 v3 §6 L3）；
         * 写在那一行最末尾、括号里，旧的分析脚本照样读，也不会被读成又一路带走的输出。没有是空的。
         */
        void closeRound(String surroundKey, long surroundMs, boolean surroundTimedOut,
                        List<String> cabinKeys, List<Long> cabinMs, List<Carried> carried, List<Other> others);

        /**
         * 关了一轮、还开着的那几路（关的时候在出画面）从开始关起 {@link ChannelScheduler#AFTERMATH_MS} 怎样：最长断帧、会话动没动
         * （设计 v3.1 C22）。关那一下只有零点几秒，看不出后劲 —— 10-12 那次环视是相机 1 被拿走 6–14 秒之后才断的。
         * 这段时间里我们自己动了其中一路（关、改输出、救）的，那一路量到动它那一刻，带着是哪一步（{@link Other#ours}）：
         * 我们自己之后的关、改不算成它的断帧（审查 rev3 B2）。
         *
         * @param closedKeys 那一轮关的几路，按开始关的先后（环视单独关的那一步在前）
         * @param why        那一轮第一步关的原因
         */
        void closeAftermath(List<String> closedKeys, ChannelRules.Why why, List<Other> others);

        /**
         * 关座舱时环视开着（{@link Coupling}，项目所有者 2026-10-15 批准，2.11.5 第 D2 项）：关完那一刻、关完 1 秒、关完 30 秒各一行。
         * 计划里的关（熄屏放开、保持到点、没人要）关完 30 秒那一行就是 {@link #closeAftermath} 那一行（前面一字不改），这时不另写那一行。
         */
        void coupling(Coupling c);

        /**
         * 关座舱前先关环视的一趟走完了（{@link SurroundFirst}，项目所有者 2026-10-15 定，2.11.6）：一趟一行 ——
         * 环视开回来出了画面（断了多久，关环视 → 关座舱 → 开环视各多久）、开回来没画面、开回来出了事，或者不再开回来（没人要了、退出、车机要睡）。
         */
        void surroundFirst(SurroundFirst f);

        /**
         * 救一路那一行之后接着量别的几路，量到下一步开始（{@code next} 为 null 是 {@link ChannelScheduler#AFTERMATH_MS} 里没有下一步）：
         * 以前量到救完那一刻为止（2.11.5 第 D2 项：10-14 22:33 救后座舱那一行量到它出画面，离关完旧设备才 0.2 秒）。
         *
         * @param stepMs     救那一步做了多久
         * @param measuredMs 从救那一步开始量了多久
         */
        void rescueAftermath(String key, long stepMs, long measuredMs, ChannelRules.Move next, String nextKey,
                             List<Other> others);

        /**
         * 这一轮关的一路这一次开着的小结（{@link OpenSpan}），一路一行，跟在「关相机：…」那一行后面：
         * 「这一次开了 25 分钟：重配 3 次（加输出 2、摘死的 1、救援 0），没为摘输出重建过」。
         */
        void openSpan(OpenSpan span);

        /** 车机要睡：手上那一步（{@code key} 为 null 是没有）开了多久，最多再等 1 秒。 */
        void sleep(String key, ChannelRules.Move move, long ms);

        /** 退出：登记表作废，手上那一步做完后按次序关。 */
        void exit(String key, ChannelRules.Move move, long ms);

        /**
         * 问了相机服务，答回来了：这一路此刻怎么说（第 {@code n} 次）。{@code mark} 是它被拿着（TAKEN）还是被挡着（BLOCKED）：
         * 被挡着的那一路自己本来就空着，等的是挡着它的那一路放开，「说空闲了」对它不是放开。
         */
        void asked(String key, String cameraId, CameraTaken.Verdict mark, int n, long heldMs, Boolean free,
                   boolean absent, boolean important);
    }

    /** 一次判定，黑匣子那一行要的东西；诊断报告也留着每一路最近一次开不起来、最近一次丢了的那一份。 */
    static final class Judgement {
        final String key;
        final String cameraId;
        final CameraTaken.Verdict verdict;
        /** 判成「现在用不了相机」：接下来怎么办（等主界面、不用再等、按普通故障走梯子）；别的判定是 null。 */
        final ChannelRules.UnusableThen unusableThen;
        final ChannelRules.Failure.Origin origin;
        final CameraTaken.Reason reason;
        /** 丢了 / 开不起来的那一刻（调度的钟）。 */
        final long at;
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

        Judgement(String key, String cameraId, CameraTaken.Verdict verdict, ChannelRules.UnusableThen unusableThen,
                  ChannelRules.Failure.Origin origin, CameraTaken.Reason reason, long at, long closeMs,
                  boolean heardAnything, Boolean serviceFree, boolean absent, long wordBeforeCloseMs,
                  List<String> takenIds, int attempt) {
            this.key = key;
            this.cameraId = cameraId;
            this.verdict = verdict;
            this.unusableThen = unusableThen;
            this.origin = origin;
            this.reason = reason;
            this.at = at;
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

    /** 开、改输出、救的一步结束了（做完、到点、出事），黑匣子那一行要的东西。 */
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
        /** 这一步里出了事（开不起来、丢了）：哪一种、什么原因；做完、到点的是 null。 */
        final ChannelRules.Failure failure;
        /** 这一步从什么时候起（调度的钟）、做了多久（到出画面、到点、到出事）。 */
        final long startedAt;
        final long durationMs;
        /** 从这一步开始到配好会话；-1 = 没配好。 */
        final long configuredMs;
        /** 到出第一帧；-1 = 没出。 */
        final long frameMs;
        /** 这一步里配好的那一份会话配好以来相机回了什么；这一步里没配好的是 null（相机层那一份还是上一份会话的）。 */
        final SessionCounts.Snapshot counts;
        /** 这段时间里别的在出画面的几路怎样。 */
        final List<Other> others;

        StepReport(ChannelRules.Move move, ChannelRules.Why why, String key, ChannelRules.Rescue rescue, int attempt,
                   long ageMs, Set<CameraNeeds.Holder> forWhom, Set<ChannelRules.Kind> outputs,
                   Set<ChannelRules.Kind> added, Set<ChannelRules.Kind> removed, boolean timedOut,
                   ChannelRules.Failure failure, long startedAt, long durationMs, long configuredMs, long frameMs,
                   SessionCounts.Snapshot counts, List<Other> others) {
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
            this.failure = failure;
            this.startedAt = startedAt;
            this.durationMs = durationMs;
            this.configuredMs = configuredMs;
            this.frameMs = frameMs;
            this.counts = counts;
            this.others = others;
        }
    }

    /**
     * 车上的几样事实，一步开始时读一次（{@link Host#vehicle}，缓存的值）。不可变。
     */
    static final class Vehicle {
        static final Vehicle UNKNOWN = new Vehicle(null, -1, -1, -1, -1);

        /** 哨兵模式原值（0 关、1 开、2 布防）；null 读不到。 */
        final Integer sentry;
        /** 点火（用车状态）此刻的原值（ACC、ON、DRIVING 那几个码）；null 读不到。关座舱那几行写「点火 ACC，30.0 秒前变过」（2.11.5）。 */
        final Integer ignition;
        /** 点火、主驾门、屏幕电源事件上一次真的变了是多久以前（毫秒）；-1 = 进程起来以后没见它变过、或者读不到。 */
        final long sinceIgnitionMs;
        final long sinceDoorMs;
        final long sinceScreenEventMs;
        /** 进程的重要性（{@code RunningAppProcessInfo.importance}，监测线程缓存的）；-1 不知道。 */
        final int importance;

        Vehicle(Integer sentry, long sinceIgnitionMs, long sinceDoorMs, long sinceScreenEventMs, int importance) {
            this(sentry, null, sinceIgnitionMs, sinceDoorMs, sinceScreenEventMs, importance);
        }

        Vehicle(Integer sentry, Integer ignition, long sinceIgnitionMs, long sinceDoorMs, long sinceScreenEventMs,
                int importance) {
            this.sentry = sentry;
            this.ignition = ignition;
            this.sinceIgnitionMs = sinceIgnitionMs;
            this.sinceDoorMs = sinceDoorMs;
            this.sinceScreenEventMs = sinceScreenEventMs;
            this.importance = importance;
        }
    }

    /** 座舱的一步开始时环视怎样（{@link StepContext#surround}）。 */
    enum SurroundState {
        /** 在出画面。 */
        STREAMING,
        /** 开着（或者在开），没在出画面。 */
        OPEN,
        /** 关着、在关、被接手。 */
        CLOSED
    }

    /**
     * 一步开始那一刻这一路的情形（{@link ChannelScheduler#begin} 记，每一路一份）：开座舱那一行（{@link Journal#opening}）、
     * 这一步结束时那一条统计（{@link StepRecord}）都照它写。都是调度账上、相机层缓存的值，不进相机服务、车辆接口。不可变。
     *
     * <p>为什么要这些（项目所有者 2026-10-14 批准，2.11.4 第 1a / R2 项）：2.11 以来两次后座舱一帧不出都是哨兵开着、ACC→ON 两秒内、
     * 只为录像开、主界面在后台；可哨兵开着 0.2 秒出画面的也有一次，哨兵关着没画面的环视有三次。哪一样跟没画面有关，样本太少、又纠缠在一起，
     * 每一次开都当场记下，统计攒够了才分得开。</p>
     */
    static final class StepContext {
        final String key;
        final String cameraId;
        final ChannelRules.Move move;
        /** 救：重建还是重开；别的是 null。 */
        final ChannelRules.Rescue rescue;
        /** 这一步开始的时刻（调度的钟）。 */
        final long startedAt;
        final Vehicle vehicle;
        final boolean mainForeground;
        /** 相机服务对这一路的最后一句是多久以前（-1 没说过）；空闲 / 被占用（null 没说过）、不在、是不是我们引起的。 */
        final long wordAgoMs;
        final Boolean wordFree;
        final boolean wordAbsent;
        final boolean wordOurs;
        /** 我们上一次关完这一台是多久以前、那一次关了多久；-1 = 没关过。 */
        final long sinceCloseMs;
        final long closeMs;
        /** 这一路上一次被别的程序拿着：拿了多久、放开多久了；-1 = 没有过。 */
        final long heldMs;
        final long releasedAgoMs;
        /** 座舱的一步开始时环视怎样；环视自己那一步、没有环视是 null。 */
        final SurroundState surround;
        /** 这一台相机此刻的来历（车机重新初始化以来开过几台，{@link CameraHistory}）；没有记录是 null（2.11.5 第 D1 项）。 */
        final CameraHistory.Camera history;
        /** 座舱的一步：环视那一台相机此刻的来历（环视开着时，眼下那一台是第几台）；环视自己那一步、没有环视、没有记录是 null。 */
        final CameraHistory.Camera surroundHistory;

        StepContext(String key, String cameraId, ChannelRules.Move move, ChannelRules.Rescue rescue, long startedAt,
                    Vehicle vehicle, boolean mainForeground, long wordAgoMs, Boolean wordFree, boolean wordAbsent,
                    boolean wordOurs, long sinceCloseMs, long closeMs, long heldMs, long releasedAgoMs,
                    SurroundState surround, CameraHistory.Camera history, CameraHistory.Camera surroundHistory) {
            this.key = key;
            this.cameraId = cameraId;
            this.move = move;
            this.rescue = rescue;
            this.startedAt = startedAt;
            this.vehicle = vehicle;
            this.mainForeground = mainForeground;
            this.wordAgoMs = wordAgoMs;
            this.wordFree = wordFree;
            this.wordAbsent = wordAbsent;
            this.wordOurs = wordOurs;
            this.sinceCloseMs = sinceCloseMs;
            this.closeMs = closeMs;
            this.heldMs = heldMs;
            this.releasedAgoMs = releasedAgoMs;
            this.surround = surround;
            this.history = history;
            this.surroundHistory = surroundHistory;
        }
    }

    /**
     * 一步结束了，一路一条（{@link Journal#recorded}，相机步骤统计）：那一步开始时的情形，加上结果。不可变。
     */
    static final class StepRecord {
        final StepContext context;
        final StepStats.Move move;
        final StepStats.Result result;
        /** 出画面用了多久、到点前等了多久、关了多久、出事前做了多久。 */
        final long ms;
        /** 从这一步开始到配好会话；-1 = 这一步里没配好（关的那一步也是 -1）。 */
        final long configuredMs;
        /** 出事的那一下；没出事是 null。 */
        final CameraTaken.Reason reason;
        /** 这一步里配好的那一份会话配好以来回了什么；没有是 null。 */
        final SessionCounts.Snapshot counts;
        /** 救援的重开：旧设备在这一步里关了多久；-1 = 没有。 */
        final long oldCloseMs;
        /** 座舱的一步里环视怎样（最长断帧、会话动没动；开始时在出画面才量）；没量是 null。 */
        final Other surround;
        /** 关的是哪一种：计划里的关、开、改、救是 null；救援重开时关旧设备、丢了之后那一次关（2.11.5 第 D3 项，以前不进统计）。 */
        final StepStats.CloseKind closeKind;
        /**
         * 环视那一台是车机重新初始化后第几台（2.11.5 第 D1 项）：环视自己开的那一步是这一步开的那一台；座舱的一步、关座舱时环视那一条是
         * 那时开着的环视；不知道是 0。{@link #lineageReset} 是那一台开的时候上一次重置是什么，null = 从记录开始数的。
         */
        final int lineage;
        final CameraHistory.Reset lineageReset;
        /** 关座舱时环视那一条（{@link StepStats.Move#COUPLE}）：关完 30 秒那一次量的；别的是 null。 */
        final Coupling coupling;
        /**
         * 关座舱前先关环视之后环视开回来的那一步（2.11.6）：从开始关环视到这一步开始多久；别的步是 -1。
         * 环视断了多久 = 它 + 这一步出画面用的时间（{@link #ms}，出了画面的话）。
         */
        final long surroundFirstStartMs;
        /** 同上：那一趟里座舱一起关的那一步关了多久（最慢的一路；到点还没关完是那一步的封顶）；座舱没关、别的步是 -1。 */
        final long surroundFirstCabinMs;

        StepRecord(StepContext context, StepStats.Move move, StepStats.Result result, long ms, long configuredMs,
                   CameraTaken.Reason reason, SessionCounts.Snapshot counts, long oldCloseMs, Other surround) {
            this(context, move, result, ms, configuredMs, reason, counts, oldCloseMs, surround, null, 0, null, null);
        }

        StepRecord(StepContext context, StepStats.Move move, StepStats.Result result, long ms, long configuredMs,
                   CameraTaken.Reason reason, SessionCounts.Snapshot counts, long oldCloseMs, Other surround,
                   StepStats.CloseKind closeKind, int lineage, CameraHistory.Reset lineageReset, Coupling coupling) {
            this(context, move, result, ms, configuredMs, reason, counts, oldCloseMs, surround, closeKind, lineage,
                    lineageReset, coupling, -1, -1);
        }

        StepRecord(StepContext context, StepStats.Move move, StepStats.Result result, long ms, long configuredMs,
                   CameraTaken.Reason reason, SessionCounts.Snapshot counts, long oldCloseMs, Other surround,
                   StepStats.CloseKind closeKind, int lineage, CameraHistory.Reset lineageReset, Coupling coupling,
                   long surroundFirstStartMs, long surroundFirstCabinMs) {
            this.context = context;
            this.move = move;
            this.result = result;
            this.ms = ms;
            this.configuredMs = configuredMs;
            this.reason = reason;
            this.counts = counts;
            this.oldCloseMs = oldCloseMs;
            this.surround = surround;
            this.closeKind = closeKind;
            this.lineage = lineage;
            this.lineageReset = lineageReset;
            this.coupling = coupling;
            this.surroundFirstStartMs = surroundFirstStartMs;
            this.surroundFirstCabinMs = surroundFirstCabinMs;
        }
    }

    /**
     * 一步里别的路：最长断帧、会话动没动过（「这段时间里环视最长断帧不到 0.2 秒、会话没动」）。开、改输出、救那一步、
     * 关一轮、关完之后 {@link ChannelScheduler#AFTERMATH_MS}（{@link ChannelScheduler#othersSince}），还有别的程序拿走一路相机之后的
     * 那一行（MultiCameraManager，争用，{@link ChannelScheduler#watchAfter}），量的是同一样东西。
     */
    static final class Other {
        final String key;
        final long gapMs;
        final boolean sessionChanged;
        /**
         * 过一阵再量的那两行（{@link ChannelScheduler#watchAfter}）：量到一半我们自己在这一路上走了一步（关、改输出、救），
         * 就只量到那一刻 —— 是哪一步；没有是 null。
         */
        final ChannelRules.Move ours;
        /** 那一步从开始量起多久开始的（毫秒）；{@link #ours} 为 null 时是 -1。 */
        final long oursAtMs;
        /** 量的那一刻（量到我们那一步的，就是那一刻）这一路最后一次收到画面（调度的钟）；没有过、没量是 0。 */
        final long lastResultAt;

        Other(String key, long gapMs, boolean sessionChanged) {
            this(key, gapMs, sessionChanged, null, -1, 0);
        }

        Other(String key, long gapMs, boolean sessionChanged, ChannelRules.Move ours, long oursAtMs) {
            this(key, gapMs, sessionChanged, ours, oursAtMs, 0);
        }

        Other(String key, long gapMs, boolean sessionChanged, ChannelRules.Move ours, long oursAtMs, long lastResultAt) {
            this.key = key;
            this.gapMs = gapMs;
            this.sessionChanged = sessionChanged;
            this.ours = ours;
            this.oursAtMs = oursAtMs;
            this.lastResultAt = lastResultAt;
        }
    }

    /**
     * 关座舱时环视怎样（项目所有者 2026-10-15 批准，2.11.5 第 D2 项；只记录，不改任何一步）：一次关座舱 —— 不管为什么关（熄屏放开、保持到点、
     * 没人要，救援重开时关旧设备，丢了之后那一次关；车机拿走座舱也是丢了那一种）—— 关完的时候环视开着（开着就算，出没出画面都量），
     * 关完那一刻、关完 {@link ChannelScheduler#COUPLING_CHECK_MS}、关完 {@link ChannelScheduler#AFTERMATH_MS} 各量一次：
     * 环视最后一帧在开始关、关完前后多少毫秒，开始关以来最长断帧、会话动没动，开始关以来每一路失败、丢缓冲几次，点火、离点火上一次变化多久，
     * 主界面在不在前，环视那一台是车机重新初始化后第几台。不可变。2.11.6 起计划里的关座舱、能用相机时环视先单独关了
     * （{@link ChannelRules.Why#SURROUND_FIRST}），关座舱时环视已经关着、不量；这几行只剩用不了相机时的计划里的关和计划外的关（重开、丢了）。
     *
     * <p>为什么（rel_final §1–§3）：10-13 23:59、10-14 21:52 两次熄屏放开座舱，环视在一个座舱关完的那一帧前后就停了，关完那一行写的却是
     * 「环视最长断帧不到 0.2 秒」—— 那一行只量到关完，看不见关完前不到 0.2 秒开始的断帧；救援的关、丢了的关、车机拿走的关以前根本不量。
     * 关完 1 秒那一次看环视关完以后出没出过画面。30 秒那一次就是以前关完一轮的那一行（{@link Journal#closeAftermath}），量到关完 30 秒以后
     * （为它醒的那一轮照旧是开始关 + 30 秒那一下，一轮都不多、不挪：量在那之后的头一轮）。</p>
     */
    static final class Coupling {
        /** 关的是哪一种。 */
        enum Kind {
            /** 计划里的关：熄屏放开、保持到点、没人要（{@link #why}）。 */
            PLANNED,
            /** 丢了之后那一次关（{@link #reason}：被断开 —— 车机拿走 —— 或者报错）。 */
            LOSS,
            /** 救援重开时关旧设备。 */
            REOPEN
        }

        /** 量的是哪一次。 */
        enum Phase {
            /** 关完那一刻。 */
            END,
            /** 关完 {@link ChannelScheduler#COUPLING_CHECK_MS}：关完以后环视出没出过画面。 */
            AFTER_1S,
            /** 关完 {@link ChannelScheduler#AFTERMATH_MS}。 */
            AFTER_30S
        }

        final Phase phase;
        final Kind kind;
        /** 计划里的关为什么关；别的是 null。 */
        final ChannelRules.Why why;
        /** 丢了的那一下；别的是 null。 */
        final CameraTaken.Reason reason;
        /** 开始关、关完（最后一路关完；到点没关完的算到点那一刻）的时刻（调度的钟）。 */
        final long startedAt;
        final long endedAt;
        /** 关的几路（座舱），按关完的先后；各自开始关以后多久关完（-1 = 到点还没关完）。 */
        final List<String> keys;
        final List<Long> endedMs;
        /** 环视那一路此刻：开始关以来最长断帧、会话动没动、我们自己动了它的量到那一刻（{@link Other#ours}）、最后一次收到画面。 */
        final Other surround;
        /** 开始关以来每一路失败、丢缓冲几次（这一份管线里的每一路）。 */
        final List<Trouble> trouble;
        /** 量的这一刻车上那几样（点火、多久以前变过）。 */
        final Vehicle vehicle;
        /** 开始关那一刻主界面在不在前。 */
        final boolean mainForeground;
        /** 环视那一台是车机重新初始化后第几台（开始关那一刻开着的那一台）；不知道是 0。重置是 null = 从记录开始数的。 */
        final int lineage;
        final CameraHistory.Reset lineageReset;
        /** 那一次重置的墙上时钟；0 = 不知道。 */
        final long lineageResetWall;
        /** 计划里的关、关完 30 秒那一次：还开着的每一路（以前关完一轮那一行的内容）；别的是空的。 */
        final List<Other> others;
        /** 关完 30 秒那一次带上关完 1 秒那一次看到的：1 关完以后出过画面、0 没有、-1 没量到。 */
        final int deliveredAfter;
        /** 量的那一刻（调度的钟）。 */
        final long measuredAt;

        Coupling(Phase phase, Kind kind, ChannelRules.Why why, CameraTaken.Reason reason, long startedAt, long endedAt,
                 List<String> keys, List<Long> endedMs, Other surround, List<Trouble> trouble, Vehicle vehicle,
                 boolean mainForeground, int lineage, CameraHistory.Reset lineageReset, long lineageResetWall,
                 List<Other> others, int deliveredAfter, long measuredAt) {
            this.phase = phase;
            this.kind = kind;
            this.why = why;
            this.reason = reason;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
            this.endedMs = Collections.unmodifiableList(new ArrayList<>(endedMs));
            this.surround = surround;
            this.trouble = Collections.unmodifiableList(new ArrayList<>(trouble));
            this.vehicle = vehicle;
            this.mainForeground = mainForeground;
            this.lineage = lineage;
            this.lineageReset = lineageReset;
            this.lineageResetWall = lineageResetWall;
            this.others = Collections.unmodifiableList(new ArrayList<>(others));
            this.deliveredAfter = deliveredAfter;
            this.measuredAt = measuredAt;
        }

        /** 环视最后一帧比开始关晚多少毫秒（负数 = 早）；没有过是 {@link Long#MIN_VALUE}。 */
        long lastResultAfterStartMs() {
            return surround.lastResultAt == 0 ? Long.MIN_VALUE : surround.lastResultAt - startedAt;
        }

        /** 环视最后一帧比关完晚多少毫秒（负数 = 早：关完之前就停了）；没有过是 {@link Long#MIN_VALUE}。 */
        long lastResultAfterEndMs() {
            return surround.lastResultAt == 0 ? Long.MIN_VALUE : surround.lastResultAt - endedAt;
        }

        /** 关完以后环视出过画面。 */
        boolean deliveredAfterEnd() {
            return surround.lastResultAt > endedAt;
        }
    }

    /** 一路开始关以来失败、丢缓冲几次（{@link Coupling#trouble}）。 */
    static final class Trouble {
        final String key;
        final long failures;
        final long lost;

        Trouble(String key, long failures, long lost) {
            this.key = key;
            this.failures = failures;
            this.lost = lost;
        }
    }

    /**
     * 关座舱前先关环视的一趟（项目所有者 2026-10-15 定，2.11.6；规则那一步是 {@link ChannelRules.Why#SURROUND_FIRST}）：
     * 环视单独关 → 座舱一起关 → 「开」那一步把环视开回来，走完写一行（{@link Journal#surroundFirst}）。只是记账、写黑匣子：
     * 每一步照旧是规则出、调度做，这里一步都不加、不挪。不可变。
     *
     * <p>为什么记它：这一趟就是代价（环视断了多久、录像多切一个文件），也是这条规矩的证据 —— 开回来没画面（R3 那种黑）要一眼看得出来，
     * 中间座舱关得慢也要看得出来（座舱关卡住之后紧接着开环视，10-10 07:51、10-11 23:19 两次都黑了，平台笔记 §3.6）。</p>
     */
    static final class SurroundFirst {
        /** 这一趟怎么结束的。 */
        enum End {
            /** 环视开回来了，出了画面。 */
            FRAMED,
            /** 开回来那一步 15 秒没出画面（由救援接手）。 */
            NO_FRAMES,
            /** 开回来那一步里开不起来、或者丢了（{@link #failure}）。 */
            ERROR,
            /** 不再开回来：没人要它了（只在保持的环视、或者座舱关的时候要它的也放了）。 */
            UNWANTED,
            /** 不再开回来：退出、车机要睡。 */
            WINDING
        }

        final End end;
        /** 开始关环视的时刻（调度的钟）。 */
        final long startedAt;
        /** 环视那一步关了多久；到点（{@link ChannelRules#CLOSE_SURROUND_MAX_MS}）还没关完是 {@link #surroundTimedOut}。 */
        final long surroundMs;
        final boolean surroundTimedOut;
        /** 座舱一起关的那一步：关的几路（按关完的先后，到点没关完的在后）、为什么关；座舱没关（又有人要了）是空的、null。 */
        final List<String> cabinKeys;
        final ChannelRules.Why cabinWhy;
        /** 座舱那一步关了多久（最慢的一路）；到点还没关完是 {@link #cabinTimedOut}（那一步的封顶）；没关是 -1。 */
        final long cabinMs;
        final boolean cabinTimedOut;
        /** 开回来那一步从开始关环视起多久开始的；没开回来是 -1。 */
        final long reopenAfterMs;
        /** 开回来那一步做了多久（到出画面、到点、到出事）；没开回来是 -1。 */
        final long reopenMs;
        /** 从开始关环视到它又出画面：环视断了多久；没出画面是 -1。 */
        final long gapMs;
        /** 开回来那一步里出了事（开不起来、丢了）；别的是 null。 */
        final ChannelRules.Failure failure;
        /** 这一趟结束的时刻（调度的钟）。 */
        final long endedAt;

        SurroundFirst(End end, long startedAt, long surroundMs, boolean surroundTimedOut, List<String> cabinKeys,
                      ChannelRules.Why cabinWhy, long cabinMs, boolean cabinTimedOut, long reopenAfterMs, long reopenMs,
                      long gapMs, ChannelRules.Failure failure, long endedAt) {
            this.end = end;
            this.startedAt = startedAt;
            this.surroundMs = surroundMs;
            this.surroundTimedOut = surroundTimedOut;
            this.cabinKeys = Collections.unmodifiableList(new ArrayList<>(cabinKeys));
            this.cabinWhy = cabinWhy;
            this.cabinMs = cabinMs;
            this.cabinTimedOut = cabinTimedOut;
            this.reopenAfterMs = reopenAfterMs;
            this.reopenMs = reopenMs;
            this.gapMs = gapMs;
            this.failure = failure;
            this.endedAt = endedAt;
        }
    }

    // ================================================================= 诊断：输出的三类、这一次开着的账、快照

    /** 会话里的一样输出：算哪一类（{@link ChannelRules#outState}）、从什么时候起（调度的钟）。不可变。 */
    static final class OutputState {
        final ChannelRules.Kind kind;
        final ChannelRules.OutState state;
        final long since;

        OutputState(ChannelRules.Kind kind, ChannelRules.OutState state, long since) {
            this.kind = kind;
            this.state = state;
            this.since = since;
        }

        /** 日志用的记号：{@code KEPT@1234}。 */
        @Override
        public String toString() {
            return state + "@" + since;
        }
    }

    /** 关相机时会话里带走的一样留着的输出：哪一路、哪一种、留了多久（「带走留着的输出：录像（留了 12 分 3 秒）」）。 */
    static final class Carried {
        final String key;
        final ChannelRules.Kind kind;
        final long keptMs;

        Carried(String key, ChannelRules.Kind kind, long keptMs) {
            this.key = key;
            this.kind = kind;
            this.keptMs = keptMs;
        }
    }

    /**
     * 一路这一次开着的小结，我们关它时交出去（{@link Journal#openSpan}）：开了多久、这期间会话重配了几次、各为什么。
     * 车主问过「拖着摘有没有用」，这一行就是车上的答案 —— 和 2.10.x 的黑匣子比，每开一次相机重配了几次；
     * 只为摘不要了的输出重配（{@link #removedOnly}）照「减输出能拖就拖」一次都不该有，这里照实数。
     */
    static final class OpenSpan {
        final String key;
        /** 从开起来到开始关。 */
        final long openMs;
        /** 改输出：加要的输出、补出帧口（不要了的顺带摘）。 */
        final int added;
        /** 改输出：马上摘死输出（画布被系统收走）。 */
        final int deadRemoved;
        /** 救：重建会话、重开。 */
        final int rescued;
        /** 改输出：只为摘不要了的输出。 */
        final int removedOnly;

        OpenSpan(String key, long openMs, int added, int deadRemoved, int rescued, int removedOnly) {
            this.key = key;
            this.openMs = openMs;
            this.added = added;
            this.deadRemoved = deadRemoved;
            this.rescued = rescued;
            this.removedOnly = removedOnly;
        }

        /** 一共重配了几次。 */
        int reconfigured() {
            return added + deadRemoved + rescued + removedOnly;
        }
    }

    /** 最近一次问相机服务时，被拿着的这一路它怎么说（诊断报告）。不可变。 */
    static final class Answer {
        /** 答回来的时刻（调度的钟）。 */
        final long at;
        /** 被拿着以来第几次问。 */
        final int n;
        /** TRUE 空闲、FALSE 被占用、null 没说过。 */
        final Boolean free;
        final boolean absent;

        Answer(long at, int n, Boolean free, boolean absent) {
            this.at = at;
            this.n = n;
            this.free = free;
            this.absent = absent;
        }
    }

    /** 诊断报告「Channel」一节里的一路：调度此刻怎么看它。在主线程上照这一路的账建，不可变。 */
    static final class LaneView {
        final String key;
        final String cameraId;
        final ChannelRules.Condition condition;
        /** 该开：有人要、或者在保持；退出、车机要睡时不该。 */
        final boolean shouldOpen;
        /** 谁要它（保持不算）。 */
        final Set<CameraNeeds.Holder> wantedBy;
        final boolean holding;
        /** 从什么时候起没人要；0 = 有人要。 */
        final long unwantedSince;
        /** 关着、该开，而此刻用不了相机：主界面回到前台之前不开（{@link ChannelRules#cannotOpen}）。 */
        final boolean cannotOpen;
        /** 熄屏放开了：座舱熄屏时谁都不要，没留着的环视也是（{@link ChannelRules#releasedByDark}）。诊断报告「该开」照它写原因。 */
        final boolean releasedByDark;
        final ChannelRules.Device device;
        final long deviceSince;
        /** 相机层手上的动作从什么时候起；0 = 没有。 */
        final long busySince;
        /** 会话配好的时刻；0 = 没有会话。 */
        final long sessionSince;
        /** 会话里的输出，按会话里的次序，各算哪一类、从什么时候起。 */
        final List<OutputState> outputs;
        /** 交给调度、等它离开会话再放的那几样（{@link ChannelScheduler#handOver}）。 */
        final Set<ChannelRules.Kind> handedOver;
        final CameraTaken.Verdict mark;
        final long markSince;
        final boolean absent;
        /** 相机服务对这一路说的最后一句：TRUE 空闲、FALSE 被占用、null 没说过；是不是我们引起的；什么时候（调度的钟）。 */
        final Boolean serviceFree;
        final boolean serviceWordOurs;
        final long serviceWordAt;
        /** 救援梯子：这一轮第几次、第几轮、歇着、彻底停手（从什么时候起）。 */
        final int attempts;
        final int cycles;
        final boolean resting;
        final boolean ladderStopped;
        final long stoppedAt;
        /** 丢了、判成普通故障，等救。 */
        final boolean lostForRescue;
        /** 丢了 / 开不起来，还没判（等关完、再等 300 ms）。 */
        final boolean judging;
        final Judgement lastOpenFailure;
        final Judgement lastLoss;
        /** 被拿着以来问了几次。 */
        final int asks;
        final Answer lastAnswer;

        LaneView(ChannelRules.World w, ChannelRules.Lane lane, List<OutputState> outputs,
                 Set<ChannelRules.Kind> handedOver, Judgement lastOpenFailure, Judgement lastLoss, Answer lastAnswer) {
            this.key = lane.key;
            this.cameraId = lane.cameraId;
            this.condition = ChannelRules.condition(lane);
            this.shouldOpen = ChannelRules.shouldOpen(w, lane);
            this.wantedBy = Collections.unmodifiableSet(copyOf(ChannelRules.wantedBy(w, lane)));
            this.holding = ChannelRules.holding(w, lane);
            this.unwantedSince = lane.unwantedSince;
            this.cannotOpen = ChannelRules.cannotOpen(w, lane);
            this.releasedByDark = ChannelRules.releasedByDark(w, lane);
            this.device = lane.device;
            this.deviceSince = lane.deviceSince;
            this.busySince = lane.busySince;
            this.sessionSince = lane.sessionSince;
            this.outputs = Collections.unmodifiableList(new ArrayList<>(outputs));
            this.handedOver = Collections.unmodifiableSet(handedOver);
            this.mark = lane.mark;
            this.markSince = lane.markSince;
            this.absent = lane.absent;
            this.serviceFree = lane.serviceFree;
            this.serviceWordOurs = lane.serviceWordOurs;
            this.serviceWordAt = lane.serviceWordAt;
            this.attempts = lane.ladder.attempts();
            this.cycles = lane.ladder.cycles();
            this.resting = lane.ladder.gaveUp();
            this.ladderStopped = lane.ladder.stopped();
            this.stoppedAt = lane.stoppedAt;
            this.lostForRescue = lane.lostForRescue;
            this.judging = lane.failure != null;
            this.lastOpenFailure = lastOpenFailure;
            this.lastLoss = lastLoss;
            this.asks = lane.asks;
            this.lastAnswer = lastAnswer;
        }
    }

    /**
     * 诊断报告「Channel」一节读的快照（{@link ChannelScheduler#snapshot()}）：每一轮最后在主线程上建好，不可变，哪个线程都能读。
     * 句子在 MultiCameraManager 里拼（这里不写中文）。
     */
    static final class Snapshot {
        /** 建的时刻（调度的钟）。 */
        final long at;
        final List<LaneView> lanes;
        final Set<CameraNeeds.Holder> holders;
        /** 录像要的那几路（RECORDING 登记着时，按开的次序）。 */
        final List<String> recordKeys;
        /** 通道眼里亮着（{@link ChannelRules#darkForChannel} 的反面：屏幕黑了不到 30 秒、车机没要睡也算亮着）。 */
        final boolean screenOn;
        /** 屏幕知道黑没黑（不知道时谁都不开，{@link ChannelRules.Why#SCREEN_UNKNOWN}）。 */
        final boolean screenKnown;
        /** 在开车（2026-10-27 的熄屏规矩这时不用）。 */
        final boolean driving;
        /** 环视这一段熄屏是哪一种（{@link ChannelRules.SurroundInDark}）、此刻留着没有（含设置，{@link ChannelRules#surroundKept}）。 */
        final ChannelRules.SurroundInDark surroundInDark;
        final boolean surroundKept;
        /** 哨兵模式（这一轮读到的；null 读不到）、「熄屏持续录制」开着。 */
        final Integer sentry;
        final boolean keepWhenDark;
        final boolean foregroundService;
        final boolean mainForeground;
        final boolean serviceStartedWhileVisible;
        /** 此刻能用相机（{@link ChannelRules#usable}）。 */
        final boolean usable;
        /** 开相机报过「现在用不了」的时刻；0 = 没有。 */
        final long unusableSince;
        final long sleepSince;
        final long exitSince;
        /** 另一个管理器实例在关相机，从什么时候起；0 = 没有。 */
        final long otherClosingSince;
        /** 上一次别人引起的变化（调度的钟）；0 = 没有过。 */
        final long lastOthersChangeAt;
        /** 手上那一步；null = 没有。 */
        final ChannelRules.Step step;
        /** 这一轮的答案：动作、为什么、哪一路；等的话等的是哪一种动作、最多还要等多久（-1 说不准）；先不开的那一路。 */
        final ChannelRules.Move move;
        final ChannelRules.Why why;
        final String key;
        final ChannelRules.Move pending;
        final long waitMs;
        final String deferredKey;
        /** 上一次问相机服务、答回来的时刻（调度的钟）；0 = 没有。 */
        final long askedAt;
        final long answeredAt;
        /** 上一次问成了（收到了回放）。 */
        final boolean answered;
        /** 退出之后都关完了，调度停了。 */
        final boolean stopped;

        Snapshot(long at, ChannelRules.World w, List<LaneView> lanes, List<String> recordKeys, boolean mainForeground,
                 boolean serviceStartedWhileVisible, ChannelRules.Decision d, long answeredAt, boolean answered,
                 boolean stopped) {
            this.at = at;
            this.lanes = Collections.unmodifiableList(new ArrayList<>(lanes));
            this.holders = Collections.unmodifiableSet(copyOf(w.holders));
            this.recordKeys = Collections.unmodifiableList(new ArrayList<>(recordKeys));
            this.screenOn = w.screenOn;
            this.screenKnown = w.screenKnown;
            this.driving = w.driving;
            this.surroundInDark = w.surroundInDark;
            this.surroundKept = ChannelRules.surroundKept(w);
            this.sentry = w.sentry;
            this.keepWhenDark = w.keepWhenDark;
            this.foregroundService = w.foregroundService;
            this.mainForeground = mainForeground;
            this.serviceStartedWhileVisible = serviceStartedWhileVisible;
            this.usable = ChannelRules.usable(w);
            this.unusableSince = w.unusableSince;
            this.sleepSince = w.sleepSince;
            this.exitSince = w.exitSince;
            this.otherClosingSince = w.otherClosingSince;
            this.lastOthersChangeAt = w.lastOthersChangeAt;
            this.step = w.step;
            this.move = d.move;
            this.why = d.why;
            this.key = d.key();
            this.pending = d.pending;
            this.waitMs = d.ms;
            this.deferredKey = d.deferredKey;
            this.askedAt = w.lastAskAt;
            this.answeredAt = answeredAt;
            this.answered = answered;
            this.stopped = stopped;
        }
    }

    /**
     * 我们的开、关做完之后多久以内，相机服务为这一路说的话还算我们引起的（{@link #ourStepInFlight}）。那一句从 binder 线程上来，
     * 和相机线程报的开好 / 关完谁先到主线程没有保证；差的是同一次进相机服务的前后脚，几十毫秒的事。
     * 数在 {@link ChannelRules#OWN_WORD_GRACE_MS}：主动认（关着的一路那一句「被占用」还算不算我们的）用的是同一个。
     */
    static final long OWN_WORD_GRACE_MS = ChannelRules.OWN_WORD_GRACE_MS;

    /**
     * 关了一轮之后多久再量一次还开着的那几路（{@link Journal#closeAftermath}，设计 v3.1 C22）；别的程序拿走一路相机之后
     * 量环视也是这个数（MultiCameraManager，设计 v3 §6 L5，{@link #watchAfter}）。10-12 黑匣子：车机拿走相机 1 之后 6–14 秒环视才断，
     * 量满 30 秒看得到后劲；断帧记录（SingleCamera 最近 16 次）盖得住。
     */
    static final long AFTERMATH_MS = 30_000L;

    /**
     * 关座舱时环视开着：关完多久再看一次环视关完以后出没出过画面（{@link Coupling.Phase#AFTER_1S}，2.11.5 第 D2 项）。
     * 30 fps 一秒 30 帧；环视停在关完那一帧前后的那两次（10-13 23:59、10-14 21:52），关完那一刻量的「不到 0.2 秒」看不出来，一秒后看得出。
     */
    static final long COUPLING_CHECK_MS = 1_000L;

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
     * 退出的最后一步（MainActivity.finishExit），熄屏录制放唤醒锁之前（ScreenOffRecording 经 {@link #whenClosedEverywhere}
     * 等它变假）。哪个线程都能读。
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

    /** 等「都关完了」的那几位（{@link #whenClosedEverywhere}），只在主线程上增删、叫。 */
    private static final List<Runnable> CLOSED_WAITERS = new CopyOnWriteArrayList<>();

    /**
     * 都关完了（{@link #closingAnywhere} 为假）就叫 {@code done}，只叫一次（主线程上调，主线程上叫）：此刻就是，当场叫；
     * 否则等哪一份调度报告关完、或者车机要睡被亮屏清掉之后，再没有一份在关时叫 —— 这就是调度发出的「关完」。
     *
     * <p>熄屏录制的唤醒锁等它再放（ScreenOffRecording，大纲 §2 阶段 6）：以前每 100 ms 问一次管理器「关完没有」、5 秒封顶，
     * 5 秒盖不住环视单独先关（最多 10 秒）再关座舱。这里不封顶，封顶由等的一方定（唤醒锁最多拉
     * {@link ChannelRules#WAKE_HOLD_MAX_MS}）；不等了调 {@link #cancelClosedWaiter}。{@link #closingAnywhere} 到了
     * {@link ChannelRules#EXIT_WAIT_MAX_MS} 自己变假的那一下不叫（没有哪一轮报关完）—— 等的一方的封顶都比它短。</p>
     */
    public static void whenClosedEverywhere(Runnable done) {
        if (done == null) {
            return;
        }
        if (!closingAnywhere()) {
            done.run();
            return;
        }
        if (!CLOSED_WAITERS.contains(done)) {
            CLOSED_WAITERS.add(done);
        }
    }

    /** 不等「关完」了（等的一方到了自己的封顶、或者亮屏了）。没在等也无害。 */
    public static void cancelClosedWaiter(Runnable done) {
        CLOSED_WAITERS.remove(done);
    }

    /** 再没有一份在关了：叫等着的那几位（先摘下来再叫，叫的时候再登记的不算这一次）。 */
    private static void tellClosedWaiters() {
        if (CLOSED_WAITERS.isEmpty() || closingAnywhere()) {
            return;
        }
        List<Runnable> due = new ArrayList<>(CLOSED_WAITERS);
        CLOSED_WAITERS.removeAll(due);
        for (Runnable done : due) {
            done.run();
        }
    }

    // ---- 环视这一段熄屏留不留：跟着进程，不跟着管线（项目所有者 2026-10-27；只在主线程上读写）----

    /**
     * 进程这一层记着的环视这一段熄屏是哪一种（{@link ChannelRules.World#surroundInDark}）：调度建世界时照它填（建调度时、
     * {@link #setLanes}，{@link #withProcessDark}），除了在退出的那一份，每一份每一轮写回（{@link #keepProcessDark}，车机要睡、睡着时也写）。
     * 管线放掉重建（换车型、列相机出错、启用的几路变了、超级镜子要后座舱）时新的那一份调度接着用 —— 熄屏录像不断，
     * 车机要睡放开的也不会被重建带回来；进程起来时是放开、没有上一次读数（进程在黑着时被拉起不开相机）。
     */
    private static ChannelRules.SurroundInDark processSurroundInDark = ChannelRules.SurroundInDark.RELEASED;
    /** 同上，上一次读得到的哨兵模式（{@link ChannelRules.World#lastSentry}）；null = 还没有。 */
    private static Integer processLastSentry;

    /** 测试用：回到进程刚起来的样子（每一条测试之前调，不让上一条的熄屏留到下一条）。 */
    static void resetProcessDark() {
        processSurroundInDark = ChannelRules.SurroundInDark.RELEASED;
        processLastSentry = null;
    }

    /**
     * 新世界照进程那一份接着：环视这一段熄屏是哪一种、上一次读数（建调度时、{@link #setLanes} 时）。建调度到交各路之间
     * 也会跑几轮（相机服务、登记表、车辆信号叫醒），那几轮也得接着进程那一份判，不能从「放开」起再写回去。
     */
    private static ChannelRules.World withProcessDark(ChannelRules.World world) {
        world.surroundInDark = processSurroundInDark;
        world.lastSentry = processLastSentry;
        return world;
    }

    /** 叫醒每一份调度：前台服务就绪 / 没了、主界面进出前台（这几件事是进程级的，不归哪一份管理器）。 */
    public static void wakeAll() {
        for (ChannelScheduler scheduler : LIVE) {
            scheduler.wake();
        }
    }

    // ================================================================= 这一份

    private final Clock clock;
    private final Poster poster;
    private final Host host;
    private final Journal journal;

    /** 每一路的相机，按 key（主线程）。 */
    private final Map<String, CameraPort> ports = new LinkedHashMap<>();
    /** 规则的世界：每一路的事实和账（主线程）。 */
    private ChannelRules.World w = withProcessDark(new ChannelRules.World(new ArrayList<>()));

    private final AtomicBoolean roundPosted = new AtomicBoolean();
    private final Runnable roundTask = this::round;
    private final Runnable timerTask = this::wake;

    /** 登记表的监听叫过来、登记、放开过的登记方（哪个线程都能加；每一轮收走，交给 {@link ChannelRules.World#changed}）。 */
    private final Set<CameraNeeds.Holder> changedHolders = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
    /**
     * 屏幕黑了还不到 30 秒时，再过多久通道就算黑了（毫秒，这一轮刷新事实时算的，{@link ChannelRules#untilDarkForChannel}）；
     * 不用等是 -1。{@link #arm} 照它到点醒：亮了、车机要睡了，下一轮它就是 -1，那一下也就不排了。
     */
    private long darkDueInMs = -1;
    private long sleepNoted;
    private boolean exitNoted;
    private final List<String> trailKeys = new ArrayList<>();
    private final List<Long> trailMs = new ArrayList<>();
    private CloseRound closeRound;
    /** 关座舱前先关环视的那一趟，还没走完的（{@link SurroundFirstRun}，2.11.6）；没有是 null。只记账，不动哪一步。 */
    private SurroundFirstRun surroundFirst;
    /** 每一路最近一次关完的时刻（调度的钟）。 */
    private final Map<String, Long> closedAt = new HashMap<>();
    /** 每一路最近一次丢了之后关完用了多久。 */
    private final Map<String, Long> lossCloseMs = new HashMap<>();
    /** 每一路相机服务最后一句的原始时刻（elapsedRealtime），和问来改过的那一句对着看：放开是不是问出来的。 */
    private final Map<String, Long> wordAtRaw = new HashMap<>();
    private final Map<String, Long> askedWordAt = new HashMap<>();
    /** 等着派发的「录像输出离开了会话」：改输出摘掉、救的时候不再带的（REMOVED），开着的一路等新会话配好再说。 */
    private final Map<String, Map<ChannelRules.Out, SingleCamera.Drop>> drops = new HashMap<>();
    /**
     * 关着的每一路，已经说过「随相机关了」的录像输出（{@link #dispatchDrops}）：同一个只说一次 —— 声明它的那一方听到后
     * 撤回声明，下一轮就不在声明里了；这一路一开就清。
     */
    private final Map<String, Set<ChannelRules.Out>> closedDropsTold = new HashMap<>();
    /**
     * 等着到点再量一次的：关完一轮还开着的那几路、别的程序拿走一路相机之后的环视（{@link #watchAfter}、{@link #noteAftermaths}）、
     * 关座舱时开着的环视（关完 1 秒、30 秒，{@link Coupling}）、救完一路之后别的几路（量到下一步开始）。
     */
    private final List<Aftermath> aftermaths = new ArrayList<>();
    /** 丢了的那几路，从丢的那一下起（关完之前）：关完那一下照它记统计、量环视（{@link CloseWatch}，2.11.5 第 D2、D3 项）。 */
    private final Map<String, CloseWatch> lossWatches = new HashMap<>();
    /** 救援重开时在关旧设备的那几路：相机层报关完（{@link CameraPort#lastClose}）那一轮照它记统计、量环视。 */
    private final List<CloseWatch> reopenWatches = new ArrayList<>();
    /** 丢了之后那一次关、救援重开时关旧设备，关完了、这一轮里记统计、量环视的（{@link #noteWatches}）。 */
    private final List<CloseWatch> closedWatches = new ArrayList<>();
    /** 开之前在等录像输出的那几路，从什么时候起。 */
    private final Map<String, Long> recordWaitSince = new HashMap<>();
    /** 每一路我们最近一次开好、关完的时刻（调度的钟）：之后 {@link #OWN_WORD_GRACE_MS} 以内相机服务的话还算我们的。 */
    private final Map<String, Long> ownStepEndedAt = new HashMap<>();
    /** 每一路最近一次被拿着、放开了：放开的时刻（调度的钟）、被拿了多久（开座舱那一行、相机步骤统计，{@link StepContext}）。 */
    private final Map<String, long[]> releases = new HashMap<>();
    /**
     * 交给调度、等它离开会话再放的消费者（{@link #handOver}），按路：输出 → 怎么放。大纲 §7.6 主界面重建时留住的预览画布、
     * §7.7 关掉的超级镜子留住的画布（移走的盲区画面窗口同样）、换下来的录制器。
     * 不随 {@link #setLanes} 清：路没了的下一轮就放，不漏。
     */
    private final Map<String, Map<ChannelRules.Out, Runnable>> handedOver = new LinkedHashMap<>();

    // ---- 诊断：输出的三类、这一次开着的账、快照（主线程上记，快照哪个线程都能读）----

    /** 会话里每一样输出算哪一类、从什么时候起，按路（{@link #trackOutputs}）。 */
    private final Map<String, Map<ChannelRules.Out, OutputState>> outputStates = new HashMap<>();
    /** 每一路从什么时候起不该开了（没人要也不保持、退出、车机要睡），到关它为止；该开的没有（{@link #trackOutputs}）。 */
    private final Map<String, Long> unneededSince = new HashMap<>();
    /** 每一路这一次开着的账（{@link Tally}）：开起来时起，我们关它时交出去。 */
    private final Map<String, Tally> tallies = new HashMap<>();
    /** 每一路最近一次开不起来、最近一次丢了的判定。 */
    private final Map<String, Judgement> lastOpenFailure = new HashMap<>();
    private final Map<String, Judgement> lastLoss = new HashMap<>();
    /** 被拿着的每一路最近一次问相机服务时它怎么说。 */
    private final Map<String, Answer> answers = new HashMap<>();
    /** 上一次问相机服务答回来的时刻（调度的钟；0 = 没有），问成了没有。 */
    private long lastAnsweredAt;
    private boolean lastAskAnswered;
    /** 这一轮读到的：前台服务是在主界面在前时起的、或那时刷新过。 */
    private boolean serviceStartedVisible;
    /** 诊断报告读的快照（{@link #snapshot()}）；还没跑过一轮是 null。 */
    private volatile Snapshot snapshot;

    // ---- 告诉录像那一侧的（主线程）----

    private Recording recording = NO_RECORDING;
    /** 每一路上一次告诉录像时在出画面的那一份会话（它配好的时刻；0 = 没在出画面）。 */
    private final Map<String, Long> toldStreaming = new HashMap<>();
    /** 每一路上一次告诉录像时的 condition。 */
    private final Map<String, ChannelRules.Condition> toldCondition = new HashMap<>();
    /** 每一路上一次告诉录像时，它是不是关着、而此刻用不了相机（{@link #cannotOpen}）。 */
    private final Map<String, Boolean> toldCannotOpen = new HashMap<>();
    /** 每一路上一次告诉录像时，它是不是被熄屏放开了（{@link #releasedByDark}）。 */
    private final Map<String, Boolean> toldReleasedByDark = new HashMap<>();
    /** 这一轮里走了的几路（丢了、我们关了、在救），一轮最后按先后告诉录像。 */
    private final List<Left> gone = new ArrayList<>();

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
        setLanes(lanes, null);
    }

    /** 同上；{@code enabled} 是其中启用的那几路（{@link ChannelRules.World#enabled}），null 是全部。 */
    void setLanes(Map<String, CameraPort> lanes, Set<String> enabled) {
        ports.clear();
        List<ChannelRules.Lane> list = new ArrayList<>();
        for (Map.Entry<String, CameraPort> entry : lanes.entrySet()) {
            ports.put(entry.getKey(), entry.getValue());
            list.add(new ChannelRules.Lane(entry.getKey(), entry.getValue().cameraId()));
        }
        // 环视这一段熄屏留不留照进程那一份接着用：管线重建不断熄屏录像（项目所有者 2026-10-27）
        w = withProcessDark(new ChannelRules.World(list, enabled));
        // 亮熄屏从一开始就照实：第一轮之前录像、拍照来问「座舱被熄屏放开没有」（releasedByDark），读到的不能是新世界的初值（黑着）。
        // 环视留不留要的车上几样也一样（留着的环视第一轮之前不能读成放开）
        readScreen();
        readCar();
        running = null;
        lastWait = null;
        lastDeferred = null;
        trailKeys.clear();
        trailMs.clear();
        closeRound = null;
        surroundFirst = null;
        closedAt.clear();
        lossCloseMs.clear();
        wordAtRaw.clear();
        askedWordAt.clear();
        drops.clear();
        closedDropsTold.clear();
        aftermaths.clear();
        lossWatches.clear();
        reopenWatches.clear();
        closedWatches.clear();
        recordWaitSince.clear();
        ownStepEndedAt.clear();
        releases.clear();
        toldStreaming.clear();
        toldCondition.clear();
        toldCannotOpen.clear();
        toldReleasedByDark.clear();
        gone.clear();
        outputStates.clear();
        unneededSince.clear();
        tallies.clear();
        lastOpenFailure.clear();
        lastLoss.clear();
        answers.clear();
        lastAnsweredAt = 0;
        lastAskAnswered = false;
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
        changedHolders.addAll(symmetricDifference(before, now));
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
     * <p>用在大纲 §7.6、§7.7（项目所有者 2026-10-11 都答「按建议」）：主界面重建（换日夜模式、换语言、从最近任务划掉）、
     * 关超级镜子（服务退出、窗口移走）、移走盲区画面窗口时 onSurfaceTextureDestroyed 返回 false，预览、后视镜、盲区画面的画布交到这里；
     * 新界面、新开的后视镜、盲区画面窗口用 {@code TextureView.setSurfaceTexture} 接回同一块的，先 {@link #reclaim} 拿回去 ——
     * 同一块画布就是同一个输出，通道零变动。调度这一侧不分是谁的画布：输出种类、哪一路都在 {@code out}、{@code key} 里。</p>
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
     * 这一路相机此刻还带着 {@code out} 吗（调度还没停）：会话里带着，或者那一次关还没关完（{@link CameraPort#stillCarries}）。
     * 画布要被系统收走时，管线据此找还带着它的那一份调度去交（MultiCameraManager.ownerOf：退出、换车型、为超级镜子重建时
     * 放了的那一份管线，它的调度还在按次序关）。
     *
     * <p>以前只看会话快照（2026-10-20 审查，车主批）：关环视一开始快照就清了，可相机线程上关会话、关设备还要几百毫秒到十几秒，
     * 这期间退出或者重建，主界面、超级镜子、盲区画面的画布在关完之前就被放掉 —— 正是「会话里的画布只由调度放」（2.11.4 R1）要防的。
     * 现在算到关完：交给那一份调度，它照「离开会话、手上没有在途的一步」再放（{@link #releaseHandedOver}）。</p>
     */
    boolean sessionCarries(String key, ChannelRules.Out out) {
        CameraPort port = ports.get(key);
        return !stopped && port != null && out != null && port.stillCarries(out);
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

    // ================================================================= 过一阵再量一路（主线程）

    /**
     * 从 {@code since} 起 {@link #AFTERMATH_MS} 再量一次这一路：最长断帧、会话动没动（{@link Other}），交给 {@code report}
     * （在一轮里、主线程上）—— 别的程序拿走一路相机之后的环视那一行（MultiCameraManager，设计 v3 §6 L5）。关完一轮之后
     * 还开着的那几路（C22）走的也是这一份（{@link #flushTrails}）。在那之前我们自己要在这一路上走一步（关、改输出、救），
     * 就量到那一刻为止、记下是哪一步（{@link Other#ours}）：我们自己之后的关、改不算成它的断帧（审查 rev3 B2）。
     * 这一路不在这一份管线里、同一件（{@code tag}）还在等、调度停了，不排。
     *
     * @param tag   同一件只等一次（「take 1」：相机 1 被拿走那一件）
     * @param since 从什么时候量（调度的钟）
     * @return 排上了
     */
    boolean watchAfter(String tag, String key, long since, Consumer<List<Other>> report) {
        CameraPort port = ports.get(key);
        if (stopped || port == null || tag == null || report == null) {
            return false;
        }
        for (Aftermath a : aftermaths) {
            if (tag.equals(a.tag)) {
                return false;
            }
        }
        Map<String, Integer> generations = new LinkedHashMap<>();
        generations.put(key, port.sessionGeneration());
        aftermaths.add(new Aftermath(since, since + AFTERMATH_MS, tag, generations, report));
        // 下一次醒的时刻要算上它（arm）
        wake();
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
     *
     * <p>它的录像输出（录制器）留不留，和我们关相机一样由每一轮的那一条规矩管（{@link #dispatchDrops}）：关完以后录像不再留着它
     * 就当轮「随相机关了」，录像那一侧才放那个录制器 —— 不然重开时不再带它，它再也回不到会话里、也就永远等不到被丢，
     * EGL、编码线程、输入画布一直占着（审查 2026-10-11）；录像留着（在录、等自动接回）就不派，相机回来时同一个录制器直接加入，
     * 停了录的那一轮再派（设计 v3.1 C13：以前丢的那一下按「要不要」定了就不再看，在录时丢了、之后停录，录制器就一直留着）。</p>
     */
    void onLossStarted(String cameraId, int code) {
        ChannelRules.Lane lane = live(cameraId);
        if (lane == null) {
            return;
        }
        CameraTaken.Reason reason = CameraTaken.Reason.ofLoss(code);
        ChannelRules.lossStarted(w, lane, reason);
        noteGone(lane.key, Gone.LOST, reason, null);
        // 丢了之后那一次关：关完记一条统计（2.11.5 第 D3 项）；座舱丢了而环视开着的，关完前后量环视（第 D2 项，车机拿走座舱也是这一种）
        lossWatches.put(lane.key, watch(Coupling.Kind.LOSS, null, reason, lane, w.now));
        wake();
    }

    /**
     * 丢了的那一路我们这边关完了：300 ms 后待判。
     *
     * <p>这一下关也是我们的关：和 {@link #onClosed} 一样记下关完的那一刻（{@link #ownStepEndedAt}），之后
     * {@link #OWN_WORD_GRACE_MS} 以内相机服务为这一关说的「空闲」算我们引起的（项目所有者 2026-10-14 批准，2.11.4 第 1b 项）。
     * 以前不记：10-13 20:10:42 关完 6 ms 后那一声「1 空闲」被记成别人引起的变化，黑匣子先写「普通故障」、紧接着又写
     * 「别人引起的变化，安静满 3 秒再救」，救援白等了 3 秒 —— 规则（ChannelRules.stillOurs）和规格 §3.5「安静」早就说它算我们的。</p>
     */
    void onLossClosed(String cameraId, long closeMs) {
        ChannelRules.Lane lane = live(cameraId);
        if (lane == null) {
            return;
        }
        ChannelRules.lossClosed(w, lane);
        lossCloseMs.put(lane.key, closeMs);
        closedAt.put(lane.key, w.now);
        ownStepEndedAt.put(lane.key, w.now);
        CloseWatch watch = lossWatches.remove(lane.key);
        if (watch != null) {
            watch.ended(lane.key, w.now, closeMs);
            closedWatches.add(watch);
        }
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

    /**
     * 相机层自己丢了一样输出（消费者被系统收走、会话配不上、共享预览流挂不下）：当场告诉声明它的那一侧 ——
     * 录像、盲区画面、超级镜子（只有它们留了接收方，{@link SingleCamera#dropListener}）。盲区画面、超级镜子收到「配不上」
     * 撤回声明，调度就不会一直想把它加回去、一次次重建这一路的会话。
     */
    void onOutputDropped(String cameraId, ChannelRules.Out out, SingleCamera.Drop why) {
        ChannelRules.Lane lane = live(cameraId);
        if (lane == null) {
            return;
        }
        CameraPort port = ports.get(lane.key);
        if (port != null && out != null
                && (out.kind == ChannelRules.Kind.RECORD || out.kind == ChannelRules.Kind.SIDE
                || out.kind == ChannelRules.Kind.HUD || out.kind == ChannelRules.Kind.MIRROR)) {
            port.dropped(out, why);
        }
        wake();
    }

    /** {@code a}、{@code b} 只在一边的那几个登记方。 */
    private static Set<CameraNeeds.Holder> symmetricDifference(Set<CameraNeeds.Holder> a, Set<CameraNeeds.Holder> b) {
        Set<CameraNeeds.Holder> diff = EnumSet.noneOf(CameraNeeds.Holder.class);
        for (CameraNeeds.Holder holder : CameraNeeds.Holder.values()) {
            if (a.contains(holder) != b.contains(holder)) {
                diff.add(holder);
            }
        }
        return diff;
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

    /**
     * 这一路被熄屏放开了（{@link ChannelRules#releasedByDark}：熄屏时座舱谁都不要，项目所有者 2026-10-12 D2），读的是这一轮的
     * 亮熄屏。和谁登记着无关 —— 录像、拍照刚登记的那一下来问，读到的也是对的（设计 v3.1 C4）。它变了告诉录像那一侧
     * （{@link Recording#onLaneConditionChanged}）。录像据此不把它算作在起来（RecordingLanes.Facts）、不为它备录制器。
     * 屏幕黑了还不到 30 秒时，开着的座舱不算、关着的算（黑着不开它；项目所有者 2026-10-13「黑满 30 秒再放」）。
     * 环视（2026-10-27）：通道算黑、没在开车、没留着（「熄屏持续录制」开着、哨兵模式开或布防）时算。
     */
    public boolean releasedByDark(String key) {
        ChannelRules.Lane lane = w.lane(key);
        return lane != null && ChannelRules.releasedByDark(w, lane);
    }

    /**
     * 拍照不拍这一路：被拿着、被挡着、停手了、或者熄屏放开了；通道算黑、没在开车时不在出画面的都不拍（不为拍照开相机，2026-10-27）。
     * 在出画面的照拍（哪怕它的录像路不在）。
     */
    public boolean excludedFromPhoto(String key) {
        ChannelRules.Lane lane = w.lane(key);
        return lane != null && ChannelRules.excludedFromPhoto(w, lane);
    }

    /**
     * 这一轮通道算黑、没在开车（{@link ChannelRules#parkedDark}）：这时只算录像，不为拍照、超级镜子、盲区画面开相机。
     * 拍照不拍的那一行据此写「熄屏：不为拍照开相机」。
     */
    public boolean parkedDark() {
        return ChannelRules.parkedDark(w);
    }

    /**
     * 诊断报告「Channel」一节读的快照（{@link Snapshot}）：每一轮最后在主线程上建好；调度停了就是停下那一刻的那一份；
     * 还没跑过一轮是 null。哪个线程都能读。
     */
    Snapshot snapshot() {
        return snapshot;
    }

    /** 测试和停下来时用：从进程级的两张表里摘掉自己；摘掉之后再没有一份在关，等「关完」的那几位现在叫。 */
    void detach() {
        LIVE.remove(this);
        WINDING.remove(this);
        tellClosedWaiters();
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
        trackOutputs(now);
        pruneRecordWaits();
        ChannelRules.Decision d = ChannelRules.next(w);
        note(d.events, now);
        noteWait(d, now);
        execute(d, now);
        flushTrails(d);
        endSurroundFirstWithoutReopen(now);
        noteWatches(now);
        dispatchDrops();
        releaseHandedOver(false);
        maybeAsk();
        noteAftermaths(now);
        tellRecording();
        keepProcessDark();
        if (updateWinding(now)) {
            stop();
            publish(d, now);
            return;
        }
        arm(d, now);
        publish(d, now);
        // 这一份关完了（或者车机要睡被亮屏清掉）、也没有别的在关：等「关完」的那几位现在叫（{@link #whenClosedEverywhere}）
        tellClosedWaiters();
    }

    // ---- 1. 刷新事实

    private void refresh(long now) {
        long offset = host.elapsedNow() - now;
        Set<CameraNeeds.Holder> holders = host.holders();
        for (java.util.Iterator<CameraNeeds.Holder> it = changedHolders.iterator(); it.hasNext(); ) {
            w.changed.add(it.next());
            it.remove();
        }
        // 没经过监听的变化（监听还没挂上时）也认
        w.changed.addAll(symmetricDifference(w.holders, holders));
        w.holders.clear();
        w.holders.addAll(holders);
        w.recordPlan.clear();
        w.recordPlan.addAll(host.recordPlan());
        boolean mirrorCabin = host.mirrorWantsCabinRear();
        if (mirrorCabin != w.mirrorCabin) {
            w.mirrorCabin = mirrorCabin;
            if (w.holders.contains(CameraNeeds.Holder.MIRROR)) {
                // 切换范围换了、后视镜登记着：和后视镜登记、放开一样，后座舱要不要、带不带超级镜子的画布跟着变。
                // 没登记着时谁的要求都没变，不算（不为它放开停手的路）
                w.changed.add(CameraNeeds.Holder.MIRROR);
            }
        }
        noteNeeds();

        boolean wasOn = w.screenOn;
        readScreen();
        if (w.screenOn && (!wasOn || !screenSeen)) {
            w.screenOnAt = now;
        }
        screenSeen = true;
        w.foregroundService = host.foregroundService();
        boolean main = host.mainForeground();
        if (main && !mainWasForeground) {
            // 主界面回到前台：开失败判出来的「现在用不了相机」作废，照常开（之前出的事、之后才判的也不再等）
            ChannelRules.mainCameForward(w);
        }
        mainWasForeground = main;
        w.mainForeground = main;
        serviceStartedVisible = host.serviceStartedWhileVisible();
        w.cameraUsable = main || serviceStartedVisible;
        w.heardAnything = host.heardAnything();
        w.lastOthersChangeAt = ourClock(host.lastOthersChangeAt(), offset);
        w.frontCabinOneShot = host.frontCabinOneShot();
        readCar();

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

    /**
     * 屏幕的事实，只在这里填（项目所有者 2026-10-13「黑满 30 秒再放」）：{@code w.screenOn} 是通道眼里亮着没有
     * （{@link ChannelRules#darkForChannel}：屏幕黑满 30 秒、或者车机要睡才算黑）—— 规则里读它的每一处（预览、保持、
     * 熄屏放开相机、关的原因、亮屏解除停手）都是这一个；{@code w.screenDark} 是屏幕真黑了没有，黑了还不到 30 秒时
     * 关着的座舱不开（{@link ChannelRules#releasedByDark}）。顺带记下还要多久才算黑（{@link #darkDueInMs}），{@link #arm} 照它到点醒。
     * 还记下安卓熄屏了没有（车机要睡，环视放开）、屏幕知不知道黑没黑（不知道时不开，2026-10-27）。
     */
    private void readScreen() {
        boolean dark = !host.screenOn();
        long darkForMs = host.darkForMs();
        boolean androidOff = host.androidOff();
        darkDueInMs = ChannelRules.untilDarkForChannel(dark, darkForMs, androidOff);
        w.screenOn = !ChannelRules.darkForChannel(dark, darkForMs, androidOff);
        w.screenDark = dark;
        w.androidOff = androidOff;
        w.screenKnown = host.screenKnown();
    }

    /**
     * 环视熄屏时留不留要的三样（项目所有者 2026-10-27）：哨兵模式、在不在开车（变了 Android 那一层叫醒）、「熄屏持续录制」（每一轮现读）。
     */
    private void readCar() {
        w.sentry = host.sentry();
        w.keepWhenDark = host.keepRecordingWhenDark();
        w.driving = host.driving();
    }

    /**
     * 环视这一段熄屏留不留写回进程那一层（{@link #processSurroundInDark}）：除了在退出的那一份，每一份每一轮都写 ——
     * 放掉的旧管线不会把新那一份的账盖掉；车机要睡、睡着时也写，车机要睡那一下的放开和上一次读数留到醒来，醒来重建的管线不会把放开前的「留着」带回来。
     */
    private void keepProcessDark() {
        if (w.exitSince != 0) {
            return;
        }
        processSurroundInDark = w.surroundInDark;
        processLastSentry = w.lastSentry;
    }

    private void refreshLane(ChannelRules.Lane lane, CameraPort port, long offset, long now) {
        lane.busySince = port.busySince();
        lane.closingSince = port.closingSince();
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
     * 设备到了我们手上。在开的那一路：开成了。关着的那一路（不是调度这一步开的：回调晚到）：
     * 照记成开着，它挂着的待判作废 —— 设备在我们手上，那一次丢失 / 开不起来已经过去了；接着判的话，相机服务为我们这一开
     * 说的「被占用」会被当成别的程序拿着，在出画面的一路被记成 TAKEN、再也放不开（审查 2026-10-11）。
     */
    private void deviceOpened(ChannelRules.Lane lane, long now) {
        if (lane.device == ChannelRules.Device.CLOSED) {
            lane.failure = null;
        }
        setDevice(lane, ChannelRules.Device.OPEN, now);
        ownStepEndedAt.put(lane.key, now);
        if (!tallies.containsKey(lane.key)) {
            tallies.put(lane.key, new Tally(now));   // 不是调度这一步开的（回调晚到）：这一次开着的账从这里算
        }
    }

    private static void setDevice(ChannelRules.Lane lane, ChannelRules.Device device, long now) {
        lane.device = device;
        lane.deviceSince = now;
    }

    /**
     * 会话里每一样输出此刻算哪一类（{@link ChannelRules#outState}：要的、留着的、死的）、从什么时候起：变了的那一路交给它的
     * 相机层（卡顿现场标「要 / 留着 31 秒 / 死 3 秒」，{@link CameraPort#outputStates}）；快照和关相机那一行（「带走留着的输出」）
     * 读这里。
     *
     * <p>相机层手上有动作（重配会话的那几百毫秒：旧会话关了、新的还没配好）时，会话里暂时没有的那几样不忘：
     * 同一个输出到了下一份会话里、类没变，还从原来的时刻算。关着的一路全忘。</p>
     *
     * <p>顺带记每一路从什么时候起不该开了（{@link #unneededSince}）：在记账（保持开始、到点）之后记，才对得上这一轮的保持。</p>
     */
    private void trackOutputs(long now) {
        for (ChannelRules.Lane lane : w.lanes) {
            if (ChannelRules.shouldOpen(w, lane)) {
                unneededSince.remove(lane.key);
            } else if (!unneededSince.containsKey(lane.key)) {
                unneededSince.put(lane.key, now);
            }
            Map<ChannelRules.Out, OutputState> before = outputStates.get(lane.key);
            Map<ChannelRules.Out, OutputState> after = new LinkedHashMap<>();
            for (ChannelRules.Out out : lane.session) {
                ChannelRules.OutState state = ChannelRules.outState(w, lane, out);
                OutputState was = before == null ? null : before.get(out);
                after.put(out, was != null && was.state == state ? was : new OutputState(out.kind, state, now));
            }
            if (before != null && lane.busySince != 0 && lane.device != ChannelRules.Device.CLOSED) {
                for (Map.Entry<ChannelRules.Out, OutputState> entry : before.entrySet()) {
                    if (!after.containsKey(entry.getKey())) {
                        after.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            if (sameStates(before, after)) {
                continue;
            }
            Map<ChannelRules.Out, OutputState> frozen = Collections.unmodifiableMap(after);
            outputStates.put(lane.key, frozen);
            CameraPort port = ports.get(lane.key);
            if (port != null) {
                port.outputStates(frozen);
            }
        }
    }

    /** 两份分类一样（同一批输出、每一样还是同一个 {@link OutputState}）；{@code before} 是 null 当作空的。 */
    private static boolean sameStates(Map<ChannelRules.Out, OutputState> before,
                                      Map<ChannelRules.Out, OutputState> after) {
        if (before == null) {
            return after.isEmpty();
        }
        if (before.size() != after.size()) {
            return false;
        }
        for (Map.Entry<ChannelRules.Out, OutputState> entry : after.entrySet()) {
            if (before.get(entry.getKey()) != entry.getValue()) {
                return false;
            }
        }
        return true;
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

    /**
     * 手上那一步结束了（做完、出事、到点）：开、改输出、救写一行（{@link Journal#stepEnded}，出事的也写），关的记进这一轮关
     * （{@link #closeEnded}）；每一路交一条给相机步骤统计（{@link Journal#recorded}）—— 统计只在这里记（2.11.4 R2）。
     *
     * <p>这一行带着这一步里配好的那一份会话配好以来相机回了什么（{@link StepReport#counts}）：15 秒没画面的那一行自己分得出
     * 是 HAL 一个都没回、我们的输出接不住、还是开始了没结果（2.11.4 第 1a 项）。</p>
     */
    private void stepEnded(ChannelRules.Step step, ChannelRules.Outcome outcome, long now) {
        Running r = running;
        running = null;
        if (step.move == ChannelRules.Move.CLOSE) {
            closeEnded(step, r, now);
            return;
        }
        if (r == null || step.keys.isEmpty()) {
            return;
        }
        String key = step.keys.get(0);
        ChannelRules.Lane lane = w.lane(key);
        CameraPort port = ports.get(key);
        if (lane == null) {
            return;
        }
        boolean timedOut = outcome == ChannelRules.Outcome.TIMED_OUT;
        // 开不起来、这一步里丢了：判定那一行另写（被拿走、普通故障……），这里写这一步自己 —— 做的是什么、做了多久、怎么没做完
        ChannelRules.Failure failure = outcome == ChannelRules.Outcome.FAILED ? lane.failure : null;
        long endedAt = failure != null ? Math.max(step.startedAt, failure.at) : now;
        long frameMs = outcome == ChannelRules.Outcome.DONE && lane.firstFrameAt >= step.startedAt
                ? lane.firstFrameAt - step.startedAt : -1;
        long configuredMs = lane.sessionSince >= step.startedAt ? lane.sessionSince - step.startedAt : -1;
        SessionCounts.Snapshot counts = port == null ? null : port.sessionCounts();
        if (counts != null && (counts.configuredAt == 0 || counts.configuredAt < step.startedAt)) {
            counts = null;   // 这一步里没配好：相机层那一份还是上一份会话的
        }
        long durationMs = frameMs >= 0 ? frameMs : endedAt - step.startedAt;
        List<Other> others = othersSince(r.generations, step.startedAt);
        ChannelRules.Decision d = r.decision;
        journal.stepEnded(new StepReport(step.move, d.why, key, d.rescue, d.attempt, d.ms, d.forWhom,
                kinds(d.outputs), kinds(d.added), kinds(d.removed), timedOut, failure, step.startedAt, durationMs,
                configuredMs, frameMs, counts, others));
        if (step.move == ChannelRules.Move.OPEN) {
            // 开不起来的那一路照旧记进「按次序开相机」，和 2.10 一样一轮一行（审查 2026-10-11：以前不记，后面的路接着开就另起一行，
            // 旧的分析脚本看到的是拆开的、缺一路的两行）
            trailKeys.add(key);
            trailMs.add(durationMs);
        }
        SingleCamera.LastClose lastClose = port == null ? null : port.lastClose();
        long oldCloseMs = d.rescue == ChannelRules.Rescue.REOPEN && lastClose != null
                && lastClose.endedAt >= step.startedAt ? lastClose.ms : -1;
        StepStats.Result result = failure != null ? StepStats.Result.ERROR
                : timedOut ? StepStats.Result.NO_FRAMES : StepStats.Result.FRAMED;
        // 关座舱前先关环视之后环视开回来的那一步（2.11.6）：统计里带上这一趟，再写那一趟的一行
        SurroundFirstRun run = surroundFirst;
        boolean reopen = run != null && step.move == ChannelRules.Move.OPEN && run.reopenStartedAt == step.startedAt
                && CameraSlots.KEY_SURROUND.equals(key);
        record(r, key, result, durationMs, configuredMs, failure == null ? null : failure.reason, counts, oldCloseMs,
                others, reopen ? step.startedAt - run.startedAt : -1, reopen ? run.cabinMs : -1);
        if (reopen) {
            surroundFirst = null;
            journal.surroundFirst(run.ended(result == StepStats.Result.FRAMED ? SurroundFirst.End.FRAMED
                            : result == StepStats.Result.NO_FRAMES ? SurroundFirst.End.NO_FRAMES : SurroundFirst.End.ERROR,
                    durationMs, frameMs >= 0 ? lane.firstFrameAt - run.startedAt : -1, failure, endedAt));
        }
        if (step.move == ChannelRules.Move.RESCUE && !r.generations.isEmpty()) {
            // 救完一路接着量别的几路，量到下一步开始（2.11.5 第 D2 项）：救那一步关旧设备之后环视要是停了，停在关完那一帧前后，
            // 救那一行在出画面那一刻就量完了，看不见
            final String rescued = key;
            final long startedAt = step.startedAt;
            final long stepMs = endedAt - step.startedAt;
            final Aftermath a = new Aftermath(startedAt, now + AFTERMATH_MS, null, r.generations, null);
            a.untilNextStep = true;
            a.wakeAt = 0;
            a.report = measured -> {
                long measuredMs = w.now - startedAt;
                if (measuredMs - stepMs >= COUPLING_CHECK_MS) {
                    journal.rescueAftermath(rescued, stepMs, measuredMs, a.nextMove, a.nextKey, measured);
                }
            };
            aftermaths.add(a);
        }
    }

    /**
     * 交一条给相机步骤统计（{@link Journal#recorded}）：这一步开始时这一路的情形（{@link Running#contexts}），加上结果。
     * 座舱的那一步里环视在出画面的话，带上它这段时间最长断帧、会话动没动。
     */
    private void record(Running r, String key, StepStats.Result result, long ms, long configuredMs,
                        CameraTaken.Reason reason, SessionCounts.Snapshot counts, long oldCloseMs, List<Other> others) {
        record(r, key, result, ms, configuredMs, reason, counts, oldCloseMs, others, -1, -1);
    }

    /**
     * 同上；关座舱前先关环视之后环视开回来的那一步带上那一趟（2.11.6）：从开始关环视到这一步开始多久、座舱那一步关了多久
     * （{@link StepRecord#surroundFirstStartMs}；别的步是 -1）。
     */
    private void record(Running r, String key, StepStats.Result result, long ms, long configuredMs,
                        CameraTaken.Reason reason, SessionCounts.Snapshot counts, long oldCloseMs, List<Other> others,
                        long surroundFirstStartMs, long surroundFirstCabinMs) {
        StepContext context = r == null ? null : r.contexts.get(key);
        if (context == null) {
            return;
        }
        Other surround = null;
        if (!CameraSlots.KEY_SURROUND.equals(key)) {
            for (Other other : others) {
                if (CameraSlots.KEY_SURROUND.equals(other.key)) {
                    surround = other;
                }
            }
        }
        record(context, statsMove(r.decision), null, result, ms, configuredMs, reason, counts, oldCloseMs, surround,
                null, surroundFirstStartMs, surroundFirstCabinMs);
    }

    /**
     * 统计记一条的那一处（{@link Journal#recorded}）：一步结束了的（上面那一个）、丢了之后那一次关和救援重开时关旧设备（{@link #noteWatches}）、
     * 关座舱时环视那一条（{@link #coupled}）都走这里。带上环视那一台是车机重新初始化后第几台（2.11.5 第 D1 项）：
     * 环视自己开的那一步是这一步开的那一台（这一步里开好了才有），座舱的一步是开始时开着的环视。
     */
    private void record(StepContext context, StepStats.Move move, StepStats.CloseKind closeKind, StepStats.Result result,
                        long ms, long configuredMs, CameraTaken.Reason reason, SessionCounts.Snapshot counts,
                        long oldCloseMs, Other surround, Coupling coupling) {
        record(context, move, closeKind, result, ms, configuredMs, reason, counts, oldCloseMs, surround, coupling, -1, -1);
    }

    /** 同上，带上关座舱前先关环视的那一趟（{@link StepRecord#surroundFirstStartMs}；不是那一步是 -1）。 */
    private void record(StepContext context, StepStats.Move move, StepStats.CloseKind closeKind, StepStats.Result result,
                        long ms, long configuredMs, CameraTaken.Reason reason, SessionCounts.Snapshot counts,
                        long oldCloseMs, Other surround, Coupling coupling, long surroundFirstStartMs,
                        long surroundFirstCabinMs) {
        int lineage = 0;
        CameraHistory.Reset lineageReset = null;
        if (coupling != null) {
            lineage = coupling.lineage;
            lineageReset = coupling.lineageReset;
        } else if (CameraSlots.KEY_SURROUND.equals(context.key)) {
            if (move == StepStats.Move.OPEN || move == StepStats.Move.REOPEN) {
                CameraHistory.Camera now = host.history(context.cameraId);
                CameraHistory.Camera before = context.history;
                boolean openedHere = now != null && now.lastOrdinal > 0 && (before == null
                        || now.lastOrdinal != before.lastOrdinal || now.lastResetWall != before.lastResetWall);
                if (openedHere) {
                    lineage = now.lastOrdinal;
                    lineageReset = now.lastReset;
                }
            }
        } else if (context.surroundHistory != null && context.surround != null
                && context.surround != SurroundState.CLOSED) {
            lineage = context.surroundHistory.lastOrdinal;
            lineageReset = context.surroundHistory.lastReset;
        }
        journal.recorded(new StepRecord(context, move, result, ms, configuredMs, reason, counts, oldCloseMs, surround,
                closeKind, lineage, lineageReset, coupling, surroundFirstStartMs, surroundFirstCabinMs));
    }

    /** 统计里的动作：开、救援的重开 / 重建、改输出、关。 */
    private static StepStats.Move statsMove(ChannelRules.Decision d) {
        switch (d.move) {
            case OPEN:
                return StepStats.Move.OPEN;
            case APPLY:
                return StepStats.Move.APPLY;
            case RESCUE:
                return d.rescue == ChannelRules.Rescue.REBUILD ? StepStats.Move.REBUILD : StepStats.Move.REOPEN;
            default:
                return StepStats.Move.CLOSE;
        }
    }

    /**
     * 关的一步结束了（都关完了，或者到点）：记进这一轮关的轨迹 —— 环视单独那一步一项，座舱按关完的先后；
     * 到点还没关完的写一行，不再等它们。关的时候在出画面的别的几路，这段时间里最长断帧、会话动没动也记进这一轮
     * （{@link CloseRound#others}，设计 v3 §6 L3：熄屏只放开座舱时，环视有没有被带着）。
     */
    private void closeEnded(ChannelRules.Step step, Running r, long now) {
        if (closeRound == null) {
            closeRound = new CloseRound();
        }
        List<Other> others = r == null ? Collections.<Other>emptyList() : othersSince(r.generations, step.startedAt);
        closeRound.noteOthers(others);
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
                record(r, key, StepStats.Result.CLOSE_TIMED_OUT, step.maxMs, -1, null, null, -1, others);
                continue;
            }
            Long at = closedAt.get(key);
            long ms = (at != null && at >= step.startedAt ? at : now) - step.startedAt;
            // 座舱每一次关都带着关的时候环视怎样（开始时的样子、这段时间断没断帧）：「关座舱时先短暂关环视」要比的就是它（R3，2.11.6 起照做）
            record(r, key, StepStats.Result.CLOSED, ms, -1, null, null, -1, others);
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
        SurroundFirstRun run = surroundFirst;
        if (run != null && run.reopenStartedAt == 0) {
            // 关座舱前先关环视的那一趟（2.11.6）：环视那一步、紧接着座舱那一步各关了多久（只记账）
            if (surroundStep && r != null && r.decision.why == ChannelRules.Why.SURROUND_FIRST) {
                run.surroundTimedOut = !stuck.isEmpty();
                run.surroundMs = run.surroundTimedOut ? step.maxMs : doneMs.isEmpty() ? now - step.startedAt : doneMs.get(0);
            } else if (!surroundStep && run.cabinMs < 0) {
                run.cabinKeys.addAll(doneKeys);
                run.cabinKeys.addAll(stuck);
                run.cabinWhy = r == null ? null : r.decision.why;
                run.cabinTimedOut = !stuck.isEmpty();
                run.cabinMs = run.cabinTimedOut ? step.maxMs
                        : doneMs.isEmpty() ? now - step.startedAt : doneMs.get(doneMs.size() - 1);
            }
        }
        if (surroundStep) {
            closeRound.surroundKey = step.keys.get(0);
            closeRound.surroundTimedOut = !stuck.isEmpty();
            closeRound.surroundMs = closeRound.surroundTimedOut ? step.maxMs
                    : doneMs.isEmpty() ? now - step.startedAt : doneMs.get(0);
        } else {
            closeRound.cabinKeys.addAll(doneKeys);
            closeRound.cabinMs.addAll(doneMs);
            CloseWatch watch = closeRound.watch;
            if (watch != null && watch.endedAt == 0) {
                // 关座舱时环视开着：每一路关完的那一刻；到点还没关完的算到这一刻（2.11.5 第 D2 项）
                for (int i = 0; i < doneKeys.size(); i++) {
                    watch.keys.add(doneKeys.get(i));
                    watch.endedMs.add(doneMs.get(i));
                }
                for (String key : stuck) {
                    watch.keys.add(key);
                    watch.endedMs.add(-1L);
                }
                watch.endedAt = stuck.isEmpty() && !doneMs.isEmpty()
                        ? step.startedAt + doneMs.get(doneMs.size() - 1) : now;
            }
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
                        if (event.verdict == CameraTaken.Verdict.TAKEN) {
                            releases.put(lane.key, new long[]{now, event.ms});   // 被挡着的那一路不是被拿着
                        }
                    }
                    break;
                }
                case STOP_LIFTED:
                    journal.stopLifted(event.keys.get(0), event.cause, copyOf(w.holders));
                    break;
                case WANTERS_CHANGED:
                    for (String key : event.keys) {
                        recording.onLaneWantersChanged(key);
                    }
                    break;
                case GAVE_UP:
                    journal.gaveUp(event.keys.get(0));
                    break;
                case STOPPED: {
                    CameraPort port = ports.get(event.keys.get(0));
                    journal.stopped(event.keys.get(0), port == null ? null : port.lastErrorName());
                    break;
                }
                case FRONT_CABIN_RETIRED:
                    host.frontCabinRetired(event.keys.get(0));
                    break;
                case SURROUND_IN_DARK: {
                    ChannelRules.Lane lane = w.lane(event.keys.get(0));
                    journal.surroundInDark(ChannelRules.surroundKept(w), event.cause, w.sentry, w.keepWhenDark,
                            lane != null && ChannelRules.keepsRecordOutput(w, lane));
                    break;
                }
                default:
                    break;
            }
        }
    }

    /**
     * 判完了一路：写一行，留给诊断报告（最近一次开不起来、最近一次丢了各一份）；判成被别的程序拿着（TAKEN）的，
     * 按丢的 / 开不起来的那一刻记争用、查嫌疑应用 —— 主动认出的不查（不知道是什么时候拿的，和「注册时报的初始状态不查」
     * 同一条规矩）。
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
        boolean loss = failure.origin == ChannelRules.Failure.Origin.LOSS;
        Judgement judgement = new Judgement(lane.key, lane.cameraId, event.verdict, event.unusableThen, failure.origin,
                failure.reason, failure.at, loss && closeMs != null ? closeMs : -1, w.heardAnything, lane.serviceFree,
                lane.absent, wordBeforeClose, takenIds, lane.ladder.attempts());
        if (loss) {
            lastLoss.put(lane.key, judgement);
        } else {
            lastOpenFailure.put(lane.key, judgement);
        }
        journal.judged(judgement);
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
                    // 在关一个设备（重开时关旧的、晚到的设备就地关）从它开始关算，别的在途从那个动作上路算
                    long since = lane == null ? 0
                            : d.why == ChannelRules.Why.LOSING ? lane.deviceSince
                            : lane.closingSince != 0 && lane.device != ChannelRules.Device.CLOSING ? lane.closingSince
                            : lane.busySince;
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
                case SCREEN_UNKNOWN:
                    // 屏幕不知道黑没黑，先不开这一路（2026-10-27）：变了才写
                    sig = d.why + "|" + key;
                    wait = new Wait(d.why, key, d.pending, -1, "", 0, null, null, false, false);
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

        if (!d.deferralKnown) {
            // 这一轮在「开」那一段之前就答了（在途、关、待判、摘死输出）：没看「先不开」，记的那一条不动 ——
            // 不然别的路每做一步，同一句「现在用不了相机，先不开 X」就重写一次（审查 2026-10-11）
            return;
        }
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
     * 开之前等录像输出的那几路，不再等着开的作废：这一路的录像输出不再是要的（录像登记放了、熄屏放开了），或者那一路不再是
     * 「该开、关着、没被拿着、不在救」。不作废的话，很久以后为别的原因开它时（预览、放开之后）会写一行从没发生过的
     * 「等录像输出：…」（审查 2026-10-11）。
     */
    private void pruneRecordWaits() {
        if (recordWaitSince.isEmpty()) {
            return;
        }
        for (Iterator<String> it = recordWaitSince.keySet().iterator(); it.hasNext(); ) {
            ChannelRules.Lane lane = w.lane(it.next());
            if (lane == null || lane.recordWantedSince == 0 || lane.device != ChannelRules.Device.CLOSED
                    || lane.mark != null || ChannelRules.rescuing(lane) || !ChannelRules.shouldOpen(w, lane)) {
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
        if (d.move == ChannelRules.Move.OPEN || d.move == ChannelRules.Move.APPLY
                || d.move == ChannelRules.Move.RESCUE || d.move == ChannelRules.Move.CLOSE) {
            cutAftermaths(d, now);
            nextStepStarts(d, now);
        }
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

    /**
     * 这一步要动的路里有等着再量的（{@link #watchAfter}、关完一轮之后的那几路）：那一路量到此刻、记下是哪一步，到点时照这一份写 ——
     * 我们自己的关、改、救不算成它的断帧（审查 rev3 B2：以前熄屏持续录制关着时，座舱放开 10 秒后照表停录、环视跟着关，
     * 30 秒那一行把我们关环视之后的 20 秒写成了「环视最长断帧 20.0 秒、会话被重建过」）。在照做之前量：关相机、改输出
     * 当场就让会话代数变了。一路只量第一次。
     */
    private void cutAftermaths(ChannelRules.Decision d, long now) {
        for (Aftermath a : aftermaths) {
            for (String key : d.keys) {
                Integer generation = a.generations.get(key);
                CameraPort port = ports.get(key);
                if (generation == null || port == null || a.cut.containsKey(key)) {
                    continue;
                }
                a.cut.put(key, new Other(key, port.longestFrameGapSince(a.since),
                        port.sessionGeneration() != generation, d.move, now - a.since, port.lastResultAt()));
            }
        }
    }

    /** 下一步开始了：救完一路之后接着量的那几件（{@link Aftermath#untilNextStep}）这一轮就量完，记下下一步是什么。 */
    private void nextStepStarts(ChannelRules.Decision d, long now) {
        for (Aftermath a : aftermaths) {
            if (a.untilNextStep && a.nextMove == null) {
                a.dueAt = Math.min(a.dueAt, now);
                a.nextMove = d.move;
                a.nextKey = d.key();
            }
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
            return;   // 已经开着、在开、重开还在路上（回调还没到），或者没有相机线程（那时相机层已经报了开不起来）
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
            noteGone(key, Gone.RESCUED, null, null);
            if (!rebuild) {
                // 重开要先关旧设备：关完记一条统计（2.11.5 第 D3 项）；座舱的、环视开着的，关完前后量环视（第 D2 项）
                reopenWatches.add(watch(Coupling.Kind.REOPEN, null, null, lane, now));
            }
        }
        queueRemoved(lane, d.outputs);
        if (!rebuild) {
            setDevice(lane, ChannelRules.Device.OPENING, now);
        }
        begin(d, now);
    }

    /**
     * 关：先在账上记成在关（相机层的回调可能就在 close 里同步来，由管理器 post 过来），再交给相机层去关。
     * 会话里带着的录像输出留不留由每一轮的那一条规矩管（{@link #dispatchDrops}）：关完以后录像不再留着它就「随相机关了」，
     * 录像还留着它（在录、这一路在计划里 —— 熄屏放开的座舱就是这样）就停放着等相机回来（设计 v3.1 C13）。
     * 会话里带走的留着的输出、这一次开着的账记进这一轮关（{@link CloseRound}），整轮关完跟着「关相机：…」那一行写。
     * 录像那一侧听说这一路走了，带着关的原因（{@link Recording#onLaneCameraGone}）。
     */
    private void close(ChannelRules.Decision d, long now) {
        List<String> keys = new ArrayList<>();
        for (String key : d.keys) {
            ChannelRules.Lane lane = w.lane(key);
            if (lane == null || ports.get(key) == null) {
                continue;
            }
            keys.add(key);
            noteClosing(lane, now);
            setDevice(lane, ChannelRules.Device.CLOSING, now);
            closedAt.remove(key);
            noteGone(key, Gone.CLOSED, null, d.why);
        }
        if (keys.isEmpty()) {
            return;
        }
        journal.closing(keys, d.why);
        if (d.why == ChannelRules.Why.SURROUND_FIRST) {
            // 关座舱前先关环视（规则那一步，2.11.6）：从这里记这一趟，走完写一行（只记账）
            surroundFirst = new SurroundFirstRun(now);
        }
        String why = closeWhy(d.why);
        for (String key : keys) {
            ports.get(key).close(why);
        }
        begin(d, now);
    }

    /**
     * 记下手上这一步：别的在出画面的几路此刻的会话代数，这一步结束时对一下（开、改、救那一行，关一轮那一行）。
     * 关的那一步也记（设计 v3 §6 L3）：熄屏只放开座舱时环视一直开着，要看得出它有没有被带着；一轮关的第一步还记下
     * 从什么时候起、为什么关，关完之后 {@link ChannelScheduler#AFTERMATH_MS} 再量一次（{@link #noteAftermaths}）。
     */
    private void begin(ChannelRules.Decision d, long now) {
        ChannelRules.begin(w, d);
        Map<String, Integer> generations = new LinkedHashMap<>();
        for (ChannelRules.Lane lane : w.lanes) {
            CameraPort port = ports.get(lane.key);
            if (!d.keys.contains(lane.key) && port != null && ChannelRules.streaming(lane)) {
                generations.put(lane.key, port.sessionGeneration());
            }
        }
        // 这一步开始时每一路的情形：这一步结束时那一条统计照它写；开设备的那一步（开、救援的重开）黑匣子写一行（座舱）
        Map<String, StepContext> contexts = new LinkedHashMap<>();
        Vehicle vehicle = host.vehicle();
        for (String key : d.keys) {
            ChannelRules.Lane lane = w.lane(key);
            if (lane != null) {
                contexts.put(key, context(d, lane, vehicle == null ? Vehicle.UNKNOWN : vehicle, now));
            }
        }
        running = new Running(d, generations, contexts);
        if (d.move == ChannelRules.Move.CLOSE && closeRound != null) {
            closeRound.begun(now, d.why, generations);
            if (closeRound.watch == null && closesCabinBesideSurround(d)) {
                // 关座舱时环视开着：开始关那一刻的样子（2.11.5 第 D2 项），整轮关完量第一次
                closeRound.watch = watch(Coupling.Kind.PLANNED, d.why, null, null, now);
            }
        }
        noteTally(d, now);
        SurroundFirstRun run = surroundFirst;
        if (run != null && run.reopenStartedAt == 0 && d.move == ChannelRules.Move.OPEN
                && CameraSlots.KEY_SURROUND.equals(d.key())) {
            run.reopenStartedAt = now;   // 关座舱前先关环视之后，「开」那一步照常把环视开回来
        }
        boolean opensDevice = d.move == ChannelRules.Move.OPEN
                || d.move == ChannelRules.Move.RESCUE && d.rescue == ChannelRules.Rescue.REOPEN;
        if (opensDevice) {
            for (StepContext context : contexts.values()) {
                journal.opening(context);
            }
        }
    }

    /**
     * 一步开始那一刻这一路的情形（{@link StepContext}）：调度账上的（相机服务最后一句、主界面在不在前、上一次被拿着）、
     * 相机层缓存的（上一次关完），加上车上的那几样（{@code vehicle}，这一轮读的一份）。
     */
    private StepContext context(ChannelRules.Decision d, ChannelRules.Lane lane, Vehicle vehicle, long now) {
        return context(d.move, d.rescue, lane, vehicle, now);
    }

    /**
     * 同上；不是一步的也用（丢了之后那一次关、关座舱时环视那一条，{@link CloseWatch}）。来历（{@link CameraHistory}）读内存里的那一份：
     * 这一台的，座舱的再带上环视那一台的。
     */
    private StepContext context(ChannelRules.Move move, ChannelRules.Rescue rescue, ChannelRules.Lane lane,
                                Vehicle vehicle, long now) {
        CameraPort port = ports.get(lane.key);
        SingleCamera.LastClose last = port == null ? null : port.lastClose();
        long[] release = releases.get(lane.key);
        ChannelRules.Lane surround = w.lane(CameraSlots.KEY_SURROUND);
        CameraHistory.Camera surroundHistory = surround == null || surround == lane ? null
                : host.history(surround.cameraId);
        return new StepContext(lane.key, lane.cameraId, move, rescue, now, vehicle, w.mainForeground,
                lane.serviceWordAt == 0 ? -1 : Math.max(0, now - lane.serviceWordAt), lane.serviceFree, lane.absent,
                lane.serviceWordOurs, last == null ? -1 : Math.max(0, now - last.endedAt), last == null ? -1 : last.ms,
                release == null ? -1 : release[1], release == null ? -1 : Math.max(0, now - release[0]),
                surroundState(lane), host.history(lane.cameraId), surroundHistory);
    }

    /** 座舱的这一路：环视此刻怎样；环视自己、没有环视是 null。 */
    private SurroundState surroundState(ChannelRules.Lane lane) {
        ChannelRules.Lane surround = w.lane(CameraSlots.KEY_SURROUND);
        if (surround == null || surround == lane) {
            return null;
        }
        if (ChannelRules.streaming(surround)) {
            return SurroundState.STREAMING;
        }
        return surround.device == ChannelRules.Device.OPEN || surround.device == ChannelRules.Device.OPENING
                ? SurroundState.OPEN : SurroundState.CLOSED;
    }

    /**
     * 记进这一路这一次开着的账（{@link Tally}）：开是新的一次；改输出按为什么记一次（摘死的、加输出、只为摘）；
     * 救（重建会话、重开）记一次。救援重开一路关着的（丢了之后）还算原来那一次开着。
     */
    private void noteTally(ChannelRules.Decision d, long now) {
        String key = d.key();
        if (key == null || d.move != ChannelRules.Move.OPEN && d.move != ChannelRules.Move.APPLY
                && d.move != ChannelRules.Move.RESCUE) {
            return;
        }
        Tally tally = tallies.get(key);
        if (tally == null || d.move == ChannelRules.Move.OPEN) {
            tally = new Tally(now);
            tallies.put(key, tally);
        }
        if (d.move == ChannelRules.Move.RESCUE) {
            tally.rescued++;
        } else if (d.move == ChannelRules.Move.APPLY) {
            if (d.why == ChannelRules.Why.DEAD) {
                tally.deadRemoved++;
            } else if (!d.added.isEmpty()) {
                tally.added++;
            } else {
                tally.removedOnly++;
            }
        }
    }

    /**
     * 一路要关了：会话里带走的留着的输出、这一次开着的账，记进这一轮关（{@link CloseRound}）。
     *
     * <p>「留着」只算这一路还该开的时候（照「减输出能拖就拖」留着、或者在保持）：从没人要它那一刻，到这一路不该开了为止
     * （{@link #unneededSince}）。和这一路一起没人要的，是跟着关；不该开以后排着按次序关的那一小会儿（座舱等环视先关完）
     * 也不算 —— 不然每次关都带一串「留了 0.1 秒」。</p>
     */
    private void noteClosing(ChannelRules.Lane lane, long now) {
        if (closeRound == null) {
            closeRound = new CloseRound();
        }
        closeRound.keys.add(lane.key);
        Long unneeded = unneededSince.get(lane.key);
        long until = unneeded == null ? now : unneeded;
        Map<ChannelRules.Out, OutputState> states = outputStates.get(lane.key);
        for (ChannelRules.Out out : lane.session) {
            OutputState state = states == null ? null : states.get(out);
            if (state != null && state.state == ChannelRules.OutState.KEPT && state.since < until) {
                closeRound.carried.add(new Carried(lane.key, out.kind, until - state.since));
            }
        }
        Tally tally = tallies.remove(lane.key);
        if (tally != null) {
            closeRound.spans.add(new OpenSpan(lane.key, now - tally.openedAt, tally.added, tally.deadRemoved,
                    tally.rescued, tally.removedOnly));
        }
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
            case DARK_RELEASE:
                return "dark-release";
            case SURROUND_FIRST:
                return "surround-first";
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
     * 派发，每一轮：
     * <ul>
     *   <li>改输出、救排着的（{@link #queueRemoved}）：开着的一路，会话里已经没有它、相机层手上也没有动作
     *       （旧会话关掉了、新会话配好了）才算「摘掉了」；这一路先关了的，算「随相机关了」；</li>
     *   <li>关着的一路（我们关的、丢了的、被拿着的、熄屏放开的）声明着的录像输出，录像不再留着它
     *       （{@link ChannelRules#keepsRecordOutput}：停了录、这一路不在计划里）就当轮「随相机关了」，录像那一侧才放那个录制器；
     *       录像还留着它就停放着，相机回来时同一个录制器直接加入（设计 v3.1 C13，一条规矩管我们关的和丢了的 ——
     *       以前关、丢的那一下按当时定了就不再看：在录时丢了、之后停录，录制器就一直留着；熄屏放开的座舱要它停放过夜）。</li>
     * </ul>
     * 关着的一路，同一个录像输出只说一次（{@link #closedDropsTold}）。
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
                    if (toldClosed(lane.key).add(drop.getKey())) {
                        port.dropped(drop.getKey(), SingleCamera.Drop.CLOSED);
                    }
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
        for (ChannelRules.Lane lane : w.lanes) {
            CameraPort port = ports.get(lane.key);
            if (lane.device != ChannelRules.Device.CLOSED || port == null) {
                closedDropsTold.remove(lane.key);
                continue;
            }
            Set<ChannelRules.Out> told = closedDropsTold.get(lane.key);
            if (told != null) {
                told.retainAll(lane.declared);   // 撤回了的不再记：同一个再声明进来，照样要说
            }
            if (ChannelRules.keepsRecordOutput(w, lane)) {
                continue;
            }
            for (ChannelRules.Out out : new ArrayList<>(lane.declared)) {
                if (out.kind == ChannelRules.Kind.RECORD && toldClosed(lane.key).add(out)) {
                    port.dropped(out, SingleCamera.Drop.CLOSED);
                }
            }
        }
    }

    /** 这一路关着时已经说过「随相机关了」的录像输出（{@link #closedDropsTold}）。 */
    private Set<ChannelRules.Out> toldClosed(String key) {
        Set<ChannelRules.Out> told = closedDropsTold.get(key);
        if (told == null) {
            told = new LinkedHashSet<>();
            closedDropsTold.put(key, told);
        }
        return told;
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

    /**
     * 连着开的一轮开完了、一轮关完了，各写一行。一轮关完时，关的时候在出画面、关完还开着的那几路要过
     * {@link ChannelScheduler#AFTERMATH_MS} 再量一次（{@link #noteAftermaths}），这里排上。
     */
    private void flushTrails(ChannelRules.Decision d) {
        ChannelRules.Step step = w.step;
        // 开不起来之后等判（300 ms）也还在这一轮里：判完接着开下一路，「按次序开相机」照旧一行
        boolean opening = d.move == ChannelRules.Move.OPEN
                || step != null && step.move == ChannelRules.Move.OPEN
                || d.move == ChannelRules.Move.WAIT && (d.pending == ChannelRules.Move.OPEN
                || d.why == ChannelRules.Why.RECORD_OUTPUT || d.why == ChannelRules.Why.JUDGING);
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
                    round.cabinKeys, round.cabinMs, round.carried, round.staying());
            for (OpenSpan span : round.spans) {
                journal.openSpan(span);
            }
            Map<String, Integer> staying = new LinkedHashMap<>(round.generations);
            staying.keySet().removeAll(round.keys);
            CloseWatch watch = round.watch;
            // 2.11.4 在这里排下面那一行、到点（开始关 + 30 秒）为它醒一轮的条件：醒的时刻一模一样，一步都不挪
            boolean wakesAsBefore = round.startedAt != 0 && !staying.isEmpty();
            if (watch != null && watch.endedAt != 0 && !watch.keys.isEmpty() && !round.keys.contains(watch.surroundKey)
                    && ports.get(watch.surroundKey) != null) {
                // 关座舱时环视开着（2.11.5 第 D2 项）：关完那一刻量一次，关完 1 秒、30 秒再量；30 秒那一次就是下面那一行，
                // 量到关完 30 秒以后（醒还是照旧那一下）。这一轮里我们接着把环视也关了的不算：之后的断帧是我们关的
                watch.staying.putAll(staying);
                closed(watch, wakesAsBefore ? round.startedAt + AFTERMATH_MS : 0);
            } else if (wakesAsBefore) {
                final List<String> closedKeys = Collections.unmodifiableList(new ArrayList<>(round.keys));
                final ChannelRules.Why why = round.why;
                aftermaths.add(new Aftermath(round.startedAt, round.startedAt + AFTERMATH_MS, null, staying,
                        others -> journal.closeAftermath(closedKeys, why, others)));
            }
        }
    }

    // ---- 7.1 关座舱时环视怎样（2.11.5 第 D2、D3 项）

    /**
     * 开始关一路（或者一轮座舱）的那一刻记下的（{@link CloseWatch}）：关的是座舱、环视开着的，要量环视（{@link CloseWatch#couples}）；
     * 每一路此刻失败、丢缓冲几次（之后拿差）；环视此刻的会话代数、那一台的来历；关的那一路此刻的情形（丢了、重开的那一次关记统计用）。
     *
     * @param lane 丢了、重开的那一路；计划里关的一轮是 null（关的几路关完时才知道）
     */
    private CloseWatch watch(Coupling.Kind kind, ChannelRules.Why why, CameraTaken.Reason reason, ChannelRules.Lane lane,
                             long now) {
        Vehicle vehicle = host.vehicle();
        if (vehicle == null) {
            vehicle = Vehicle.UNKNOWN;
        }
        ChannelRules.Lane surround = w.lane(CameraSlots.KEY_SURROUND);
        CameraPort surroundPort = surround == null ? null : ports.get(surround.key);
        boolean couples = surroundPort != null && surround.device == ChannelRules.Device.OPEN
                && (lane == null || lane != surround);
        Map<String, long[]> base = new LinkedHashMap<>();
        for (Map.Entry<String, CameraPort> entry : ports.entrySet()) {
            base.put(entry.getKey(), new long[]{entry.getValue().captureFailures(), entry.getValue().lostBuffers()});
        }
        CameraHistory.Camera lineage = couples ? host.history(surround.cameraId) : null;
        CloseWatch watch = new CloseWatch(kind, why, reason, lane == null ? null : lane.key, now, couples,
                couples ? surround.key : null, couples ? surroundPort.sessionGeneration() : 0, base,
                lane == null ? null : context(ChannelRules.Move.CLOSE, null, lane, vehicle, now),
                couples ? context(ChannelRules.Move.CLOSE, null, surround, vehicle, now) : null,
                w.mainForeground, lineage);
        watch.surroundStreaming = couples && ChannelRules.streaming(surround);
        return watch;
    }

    /** 这一步关的有座舱（环视以外的一路），环视开着、不在这一步里。 */
    private boolean closesCabinBesideSurround(ChannelRules.Decision d) {
        ChannelRules.Lane surround = w.lane(CameraSlots.KEY_SURROUND);
        if (surround == null || surround.device != ChannelRules.Device.OPEN || d.keys.contains(surround.key)) {
            return false;
        }
        for (String key : d.keys) {
            if (!CameraSlots.KEY_SURROUND.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 丢了之后那一次关、救援重开时关旧设备，关完了的（这一轮里，{@link #flushTrails} 之后）：每一路记一条统计（关了多久，2.11.5 第 D3 项：
     * 以前这两种关不进统计 —— 救环视时关旧设备卡 4.5–5.8 秒，统计里看不到）；座舱的、环视开着的，量环视（{@link #closed}）。
     * 救援重开的那一次关，相机层报关完（{@link CameraPort#lastClose} 比开始关晚）的那一轮才算关完；在途封顶还没关完的扔掉。
     */
    private void noteWatches(long now) {
        for (Iterator<CloseWatch> it = reopenWatches.iterator(); it.hasNext(); ) {
            CloseWatch watch = it.next();
            CameraPort port = ports.get(watch.key);
            SingleCamera.LastClose last = port == null ? null : port.lastClose();
            if (last != null && last.endedAt >= watch.startedAt) {
                it.remove();
                // 关是从相机层登记在关那一刻开始的（晚于救那一步开始几毫秒）
                watch.startedAt = Math.max(watch.startedAt, last.endedAt - last.ms);
                watch.ended(watch.key, last.endedAt, last.ms);
                closedWatches.add(watch);
            } else if (port == null || now - watch.startedAt > ChannelRules.IN_FLIGHT_MAX_MS) {
                it.remove();
            }
        }
        if (closedWatches.isEmpty()) {
            return;
        }
        List<CloseWatch> due = new ArrayList<>(closedWatches);
        closedWatches.clear();
        for (CloseWatch watch : due) {
            if (watch.context != null) {
                record(watch.context, StepStats.Move.CLOSE, watch.kind == Coupling.Kind.LOSS
                                ? StepStats.CloseKind.LOSS : StepStats.CloseKind.REOPEN, StepStats.Result.CLOSED,
                        watch.closeMs, -1, watch.reason, null, -1, null, null);
            }
            if (watch.couples) {
                closed(watch, 0);
            }
        }
    }

    /**
     * 一次关座舱关完了、环视开着（{@link CloseWatch#couples}）：关完那一刻量一次、写一行（{@link Coupling.Phase#END}）；
     * 关完 {@link #COUPLING_CHECK_MS}、{@link #AFTERMATH_MS} 各排一次再量（同一份到点再量的那一套，{@link Aftermath}）。
     * 30 秒那一次量的是环视，计划里的关还量关完还开着的那几路（以前关完一轮那一行），调度停了（退出）时提前量完、记统计。
     *
     * @param wakeAt 30 秒那一次为它醒一轮的时刻：计划里的关是 2.11.4 关完一轮那一行醒的那一刻（开始关 + {@link #AFTERMATH_MS}，
     *               那时有那一行才醒），丢了、重开的那一次关是 0（不醒）—— 醒的时刻和 2.11.4 一模一样，量在关完 30 秒以后的头一轮
     */
    private void closed(final CloseWatch watch, long wakeAt) {
        if (!watch.couples || ports.get(watch.surroundKey) == null) {
            return;
        }
        coupled(watch, Coupling.Phase.END, null);
        Map<String, Integer> surround = new LinkedHashMap<>();
        surround.put(watch.surroundKey, watch.surroundGeneration);
        Aftermath oneSecond = new Aftermath(watch.startedAt, watch.endedAt + COUPLING_CHECK_MS, null, surround,
                measured -> coupled(watch, Coupling.Phase.AFTER_1S, measured));
        oneSecond.wakeAt = 0;
        aftermaths.add(oneSecond);
        Map<String, Integer> later = new LinkedHashMap<>(watch.staying);
        later.put(watch.surroundKey, watch.surroundGeneration);
        Aftermath thirty = new Aftermath(watch.startedAt, watch.endedAt + AFTERMATH_MS, null, later,
                measured -> coupled(watch, Coupling.Phase.AFTER_30S, measured));
        thirty.flushOnStop = true;
        thirty.wakeAt = wakeAt;
        aftermaths.add(thirty);
    }

    /**
     * 量一次关座舱时的环视、写一行（{@link Journal#coupling}）；关完 30 秒那一次再记一条统计（{@link StepStats.Move#COUPLE}：
     * 关完之后环视断没断、断了多久，按环视是不是车机重新初始化后第一台、主界面在不在前分开数，2.11.5 第 D3 项）。
     *
     * @param measured 到点再量的那几路（我们中途动了的量到动它那一刻）；关完那一刻量的是 null（当场量环视）
     */
    private void coupled(CloseWatch watch, Coupling.Phase phase, List<Other> measured) {
        long now = w.now;
        Other surround = null;
        List<Other> others = new ArrayList<>();
        if (measured != null) {
            for (Other other : measured) {
                if (other.key.equals(watch.surroundKey)) {
                    surround = other;
                }
                if (watch.staying.containsKey(other.key)) {
                    others.add(other);
                }
            }
        }
        CameraPort port = ports.get(watch.surroundKey);
        if (surround == null) {
            if (port == null) {
                return;
            }
            surround = new Other(watch.surroundKey, port.longestFrameGapSince(watch.startedAt),
                    port.sessionGeneration() != watch.surroundGeneration, null, -1, port.lastResultAt());
        }
        List<Trouble> trouble = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : watch.base.entrySet()) {
            CameraPort lanePort = ports.get(entry.getKey());
            if (lanePort != null) {
                trouble.add(new Trouble(entry.getKey(), Math.max(0, lanePort.captureFailures() - entry.getValue()[0]),
                        Math.max(0, lanePort.lostBuffers() - entry.getValue()[1])));
            }
        }
        Vehicle vehicle = host.vehicle();
        CameraHistory.Camera lineage = watch.lineage;
        Coupling c = new Coupling(phase, watch.kind, watch.why, watch.reason, watch.startedAt, watch.endedAt,
                watch.keys, watch.endedMs, surround, trouble, vehicle == null ? Vehicle.UNKNOWN : vehicle,
                watch.mainForeground, lineage == null ? 0 : lineage.lastOrdinal,
                lineage == null ? null : lineage.lastReset, lineage == null ? 0 : lineage.lastResetWall,
                phase == Coupling.Phase.AFTER_30S && watch.kind == Coupling.Kind.PLANNED ? others
                        : Collections.<Other>emptyList(), watch.deliveredAfter, now);
        if (phase == Coupling.Phase.AFTER_1S) {
            watch.deliveredAfter = c.deliveredAfterEnd() ? 1 : 0;
        }
        journal.coupling(c);
        if (phase == Coupling.Phase.AFTER_30S && watch.surroundContext != null && watch.surroundStreaming) {
            record(watch.surroundContext, StepStats.Move.COUPLE, watch.kind == Coupling.Kind.PLANNED ? null
                            : watch.kind == Coupling.Kind.LOSS ? StepStats.CloseKind.LOSS : StepStats.CloseKind.REOPEN,
                    surround.gapMs >= StepStats.SURROUND_STALL_MS ? StepStats.Result.STALLED : StepStats.Result.STEADY,
                    surround.gapMs, -1, watch.reason, null, -1, surround, c);
        }
    }

    // ---- 7.2 关座舱前先关环视的那一趟（2.11.6）

    /**
     * 关座舱前先关环视的那一趟，环视不再开回来的（这一轮照做之后看）：手上没有一步了，环视关着、不该开了（只在保持的环视关了，
     * 或者座舱关的时候要它的那几样也放了）—— 「没人要了，不再开回来」；退出、车机要睡 —— 「不再开回来」。写那一趟的一行，不再记它。
     * 还该开、只是这一轮没开（等安静、此刻用不了相机、相机层还在关它）的接着等：「开」那一步开它的时候记下开回来那一刻（{@link #begin}），
     * 那一步结束时写那一行（{@link #stepEnded}）。只记账，一步都不动。
     */
    private void endSurroundFirstWithoutReopen(long now) {
        SurroundFirstRun run = surroundFirst;
        if (run == null || run.reopenStartedAt != 0 || w.step != null) {
            return;
        }
        ChannelRules.Lane lane = w.lane(CameraSlots.KEY_SURROUND);
        boolean winding = ChannelRules.winding(w);
        if (lane != null && !winding
                && (lane.device != ChannelRules.Device.CLOSED || ChannelRules.shouldOpen(w, lane))) {
            return;
        }
        surroundFirst = null;
        journal.surroundFirst(run.ended(winding ? SurroundFirst.End.WINDING : SurroundFirst.End.UNWANTED, -1, -1, null,
                now));
    }

    /**
     * 到了 {@link ChannelScheduler#AFTERMATH_MS} 的：那几路从开始量起最长断帧多久、会话动没动，交出去写一行（关完一轮的那一行
     * {@link Journal#closeAftermath}，设计 v3.1 C22；别的程序拿走一路相机之后的那一行，{@link #watchAfter}）。
     * 我们中途动过的那一路用动它那一刻量的（{@link #cutAftermaths}）。一件事一行，量完就扔。交出去的在遍历之外调。
     */
    private void noteAftermaths(long now) {
        List<Aftermath> due = new ArrayList<>();
        for (Iterator<Aftermath> it = aftermaths.iterator(); it.hasNext(); ) {
            Aftermath a = it.next();
            if (now >= a.dueAt) {
                it.remove();
                due.add(a);
            }
        }
        for (Aftermath a : due) {
            List<Other> others = new ArrayList<>();
            for (Other measured : othersSince(a.generations, a.since)) {
                Other cut = a.cut.get(measured.key);
                others.add(cut != null ? cut : measured);
            }
            if (!others.isEmpty()) {
                a.report.accept(others);
            }
        }
    }

    /**
     * 别的几路（{@code generations}：开始时在出画面的那几路、那一刻的会话代数）从 {@code since}（调度的钟）起最长断帧多久、
     * 会话动没动 —— 开、改、救那一行，关一轮那一行，关完之后再量的那一行都照它。这一份管线里已经没有的一路不算。
     */
    private List<Other> othersSince(Map<String, Integer> generations, long since) {
        List<Other> others = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : generations.entrySet()) {
            CameraPort other = ports.get(entry.getKey());
            if (other != null) {
                others.add(new Other(entry.getKey(), other.longestFrameGapSince(since),
                        other.sessionGeneration() != entry.getValue(), null, -1, other.lastResultAt()));
            }
        }
        return others;
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
        host.ask(asked -> poster.post(() -> answered(keys, asked)));
    }

    private boolean anyMarked() {
        for (ChannelRules.Lane lane : w.lanes) {
            if (lane.mark != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 问的答案回来了（主线程）：照回放改了的那几路记下（放开那一行据此写「问出来的」），每一路写一行、留给诊断报告，
     * 问成了没有也留着，叫醒。
     */
    private void answered(List<String> keys, CameraAvailabilityWatch.Asked asked) {
        if (stopped) {
            return;
        }
        long now = clock.now();
        lastAnsweredAt = now;
        lastAskAnswered = asked.answered();
        for (CameraAvailabilityWatch.Correction fix : asked.corrections) {
            ChannelRules.Lane lane = laneById(fix.cameraId);
            if (lane != null) {
                askedWordAt.put(lane.key, host.word(fix.cameraId).at);
            }
        }
        for (String key : keys) {
            ChannelRules.Lane lane = w.lane(key);
            if (lane == null || lane.mark == null) {
                continue;
            }
            CameraAvailabilityWatch.Word word = host.word(lane.cameraId);
            int n = lane.asks;
            answers.put(key, new Answer(now, n, word.free, word.absent));
            journal.asked(key, lane.cameraId, lane.mark, n, now - lane.markSince, word.free, word.absent, n % 10 == 1);
        }
        wake();
    }

    // ---- 9. 告诉录像那一侧

    private void noteGone(String key, Gone why, CameraTaken.Reason reason, ChannelRules.Why closed) {
        gone.add(new Left(key, why, reason, closed));
    }

    /**
     * 一轮最后告诉录像那一侧（{@link Recording}）：先说这一轮里走了的几路（录像那一路先退出；我们关的带着关的原因），
     * 再说 condition 变了的（含关着时能不能用相机变了的 {@link #cannotOpen}、被不被熄屏放开变了的 {@link #releasedByDark}），
     * 最后说带着一份新会话出了画面的（录像那一路看会话带没带着它的录像输出，带着就加入）。
     * 走了的那一路，下一份出画面的会话一定算新的。录像那一侧在回调里只叫醒、声明、撤回，不在这里再跑一轮。
     */
    private void tellRecording() {
        List<Left> left = new ArrayList<>(gone);
        gone.clear();
        for (Left g : left) {
            toldStreaming.remove(g.key);
            recording.onLaneCameraGone(g.key, g.why, g.reason, g.closed);
        }
        List<String> changed = new ArrayList<>();
        List<String> streamingNow = new ArrayList<>();
        for (ChannelRules.Lane lane : w.lanes) {
            ChannelRules.Condition condition = ChannelRules.condition(lane);
            boolean conditionChanged = toldCondition.put(lane.key, condition) != condition;
            boolean cannotOpen = ChannelRules.cannotOpen(w, lane);
            boolean cannotOpenChanged = !Boolean.valueOf(cannotOpen).equals(toldCannotOpen.put(lane.key, cannotOpen));
            boolean dark = ChannelRules.releasedByDark(w, lane);
            boolean darkChanged = !Boolean.valueOf(dark).equals(toldReleasedByDark.put(lane.key, dark));
            if (conditionChanged || cannotOpenChanged || darkChanged) {
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
        // 关座舱时环视那一条还没到 30 秒的：调度停了（退出时环视也关了，量到我们关它那一刻），提前量完、记统计（2.11.5）
        for (Aftermath a : aftermaths) {
            if (a.flushOnStop) {
                a.dueAt = Math.min(a.dueAt, w.now);
            }
        }
        noteAftermaths(w.now);
        aftermaths.clear();
        detach();
        host.stopped();
    }

    // ---- 11. 下一次什么时候醒

    /**
     * 最迟 {@link ChannelRules#TICK_MS} 醒一次；手上那一步到点、等的东西到点（待判 300 ms、安静、录像输出、车机要睡的 1 秒）、
     * 保持到点、屏幕黑满 30 秒（{@link #darkDueInMs}）、关完一轮之后再量的那一下到点（{@link #noteAftermaths}），早一点醒。
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
        for (Aftermath a : aftermaths) {
            // 到点了的这一轮已经量完扔掉了（noteAftermaths 在前）；只剩计划里关座舱那一件醒过了、还没到量的时候 —— 不再为它醒
            if (a.wakeAt > now) {
                delay = Math.min(delay, a.wakeAt - now);
            }
        }
        if (darkDueInMs >= 0) {
            // 屏幕黑满 30 秒的那一刻（ChannelRules.darkForChannel）：到点就醒，座舱当场放开
            delay = Math.min(delay, darkDueInMs);
        }
        poster.cancel(timerTask);
        poster.postDelayed(timerTask, Math.max(1, delay));
    }

    // ---- 12. 诊断报告读的快照

    /** 照这一轮的账建一份快照（{@link Snapshot}），换掉上一份。 */
    private void publish(ChannelRules.Decision d, long now) {
        List<LaneView> lanes = new ArrayList<>();
        for (ChannelRules.Lane lane : w.lanes) {
            Map<ChannelRules.Out, OutputState> states = outputStates.get(lane.key);
            List<OutputState> outputs = new ArrayList<>();
            for (ChannelRules.Out out : lane.session) {
                OutputState state = states == null ? null : states.get(out);
                if (state != null) {
                    outputs.add(state);
                }
            }
            Map<ChannelRules.Out, Runnable> handed = handedOver.get(lane.key);
            lanes.add(new LaneView(w, lane, outputs,
                    handed == null ? EnumSet.noneOf(ChannelRules.Kind.class) : kinds(handed.keySet()),
                    lastOpenFailure.get(lane.key), lastLoss.get(lane.key), answers.get(lane.key)));
        }
        snapshot = new Snapshot(now, w, lanes, lastRecordKeys, mainWasForeground, serviceStartedVisible, d,
                lastAnsweredAt, lastAskAnswered, stopped);
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

    /**
     * 手上那一步的上下文：规则的答案（黑匣子那一行要的为什么、为谁、加了什么），开始时别的在出画面的几路的会话代数，
     * 开始时这一步的每一路的情形（{@link StepContext}，结束时交给相机步骤统计）。
     */
    private static final class Running {
        final ChannelRules.Decision decision;
        final Map<String, Integer> generations;
        final Map<String, StepContext> contexts;

        Running(ChannelRules.Decision decision, Map<String, Integer> generations, Map<String, StepContext> contexts) {
            this.decision = decision;
            this.generations = generations;
            this.contexts = contexts;
        }
    }

    /**
     * 一轮关：环视单独那一步、座舱一起那一步，整轮完了写一行「关相机：…」（末尾带上各路带走的留着的输出、还开着的那几路
     * 这段时间里怎样），再每一路一行这一次开着的小结；还开着的那几路 {@link ChannelScheduler#AFTERMATH_MS} 之后再量一次（{@link Aftermath}）。
     */
    private static final class CloseRound {
        String surroundKey;
        long surroundMs;
        boolean surroundTimedOut;
        final List<String> cabinKeys = new ArrayList<>();
        final List<Long> cabinMs = new ArrayList<>();
        /** 这一轮关的几路，按关的先后（含到点还没关完的）。 */
        final List<String> keys = new ArrayList<>();
        /** 关的时候各路会话里带走的留着的输出，按关的先后。 */
        final List<Carried> carried = new ArrayList<>();
        /** 这一轮关的各路这一次开着的小结，按关的先后。 */
        final List<OpenSpan> spans = new ArrayList<>();
        /** 第一步开始关的时刻（调度的钟）；0 = 还没有一步开始。 */
        long startedAt;
        /** 第一步关的原因。 */
        ChannelRules.Why why;
        /** 每一步开始时别的在出画面的几路的会话代数（同一路记最早的那一份）。 */
        final Map<String, Integer> generations = new LinkedHashMap<>();
        /** 每一步里别的在出画面的几路怎样（同一路并成一份：最长的断帧，会话动过就算动过）。 */
        final Map<String, Other> others = new LinkedHashMap<>();
        /** 这一轮关座舱时环视开着：开始关座舱那一刻的样子（{@link CloseWatch}，2.11.5 第 D2 项）；没有是 null。 */
        CloseWatch watch;

        /** 一步开始关了（{@link ChannelScheduler#begin}）。 */
        void begun(long at, ChannelRules.Why stepWhy, Map<String, Integer> stepGenerations) {
            if (startedAt == 0) {
                startedAt = at;
                why = stepWhy;
            }
            for (Map.Entry<String, Integer> entry : stepGenerations.entrySet()) {
                if (!generations.containsKey(entry.getKey())) {
                    generations.put(entry.getKey(), entry.getValue());
                }
            }
        }

        /** 一步关完了：这一步里别的几路怎样，并进这一轮。 */
        void noteOthers(List<Other> stepOthers) {
            for (Other other : stepOthers) {
                Other before = others.get(other.key);
                others.put(other.key, before == null ? other : new Other(other.key,
                        Math.max(before.gapMs, other.gapMs), before.sessionChanged || other.sessionChanged));
            }
        }

        /** 这一轮关完还开着的那几路这段时间里怎样（关了的那几路不算：它们自己就在关）。 */
        List<Other> staying() {
            List<Other> staying = new ArrayList<>();
            for (Other other : others.values()) {
                if (!keys.contains(other.key)) {
                    staying.add(other);
                }
            }
            return staying;
        }
    }

    /**
     * 关座舱前先关环视的那一趟，还没走完（{@link ChannelScheduler#surroundFirst}，2.11.6）：从关环视那一步开始记（{@link ChannelScheduler#close}），
     * 环视、座舱那两步关完各记一笔（{@link ChannelScheduler#closeEnded}），「开」那一步开环视时记下那一刻（{@link ChannelScheduler#begin}）；
     * 那一步结束、或者不再开回来时交成一份 {@link SurroundFirst} 写一行。只记账。
     */
    private static final class SurroundFirstRun {
        /** 开始关环视的时刻（调度的钟）。 */
        final long startedAt;
        /** 环视那一步关了多久；-1 = 还没关完。 */
        long surroundMs = -1;
        boolean surroundTimedOut;
        /** 座舱那一步：关的几路、为什么、关了多久（最慢的一路）；-1 = 座舱还没关（或者不用关了）。 */
        final List<String> cabinKeys = new ArrayList<>();
        ChannelRules.Why cabinWhy;
        long cabinMs = -1;
        boolean cabinTimedOut;
        /** 「开」那一步开环视的时刻（调度的钟）；0 = 还没开。 */
        long reopenStartedAt;

        SurroundFirstRun(long startedAt) {
            this.startedAt = startedAt;
        }

        /**
         * 这一趟走完了：交成一份写黑匣子的。
         *
         * @param reopenMs 开回来那一步做了多久；没开回来是 -1
         * @param gapMs    从开始关环视到它又出画面；没出画面是 -1
         * @param endedAt  结束的时刻（调度的钟）
         */
        SurroundFirst ended(SurroundFirst.End end, long reopenMs, long gapMs, ChannelRules.Failure failure, long endedAt) {
            return new SurroundFirst(end, startedAt, surroundMs, surroundTimedOut, cabinKeys,
                    cabinKeys.isEmpty() ? null : cabinWhy, cabinMs, cabinTimedOut,
                    reopenStartedAt == 0 ? -1 : reopenStartedAt - startedAt, reopenMs, gapMs, failure, endedAt);
        }
    }

    /**
     * 等着从 {@code since} 起 {@link ChannelScheduler#AFTERMATH_MS} 再量一次的那几路（{@link ChannelScheduler#noteAftermaths}）：
     * 关完一轮还开着的、别的程序拿走一路相机之后的环视（{@link ChannelScheduler#watchAfter}）。从什么时候量、那几路开始时的会话代数、
     * 量好了交给谁写；中途我们自己动了的那几路在动它那一刻量的（{@link ChannelScheduler#cutAftermaths}）。
     */
    private static final class Aftermath {
        final long since;
        /** 到点（该量）的时刻（调度的钟）：一般是 {@link #since} + {@link ChannelScheduler#AFTERMATH_MS}；关座舱时环视那两次从关完起算。 */
        long dueAt;
        /** 同一件只等一次（{@link ChannelScheduler#watchAfter}）；关完一轮的那一种是 null。 */
        final String tag;
        final Map<String, Integer> generations;
        Consumer<List<Other>> report;
        /** 中途我们自己动了的那几路：动它那一刻量的（{@link Other#ours}）。 */
        final Map<String, Other> cut = new LinkedHashMap<>();
        /** 量到下一步开始（救完一路之后那一件，{@link ChannelScheduler#nextStepStarts}），{@link #dueAt} 是封顶。 */
        boolean untilNextStep;
        /** 下一步是什么、动的哪一路；封顶到了还没有下一步是 null。 */
        ChannelRules.Move nextMove;
        String nextKey;
        /** 调度停了（退出）时提前量完（关座舱时环视那一条的 30 秒，统计要它）；别的照旧扔掉。 */
        boolean flushOnStop;
        /**
         * 为它醒一轮的时刻（{@link ChannelScheduler#arm}，调度的钟）；0 = 不为它醒。2.11.4 就有的那几件就是 {@link #dueAt}。
         * 2.11.5 新加的几件不醒（关座舱时环视的 1 秒、丢了 / 重开那一次关的 30 秒、救完一路之后接着量的封顶）；计划里关座舱的 30 秒那一件
         * 照旧在 2.11.4 醒的那一刻醒（开始关 + {@link ChannelScheduler#AFTERMATH_MS}，2.11.4 那时有那一行才醒），量却在关完 30 秒之后。
         * 都在到点之后的头一轮量（最多晚一个 {@link ChannelRules#TICK_MS}）：诊断不多跑一轮、不挪哪一轮、不挪任何一步的时刻。
         */
        long wakeAt;

        Aftermath(long since, long dueAt, String tag, Map<String, Integer> generations, Consumer<List<Other>> report) {
            this.since = since;
            this.dueAt = dueAt;
            this.wakeAt = dueAt;
            this.tag = tag;
            this.generations = generations;
            this.report = report;
        }
    }

    /**
     * 开始关的那一刻记下的（2.11.5 第 D2、D3 项）：一轮计划里关座舱时环视开着（{@link CloseRound#watch}），丢了的一路（{@link #lossWatches}），
     * 救援重开时关旧设备的一路（{@link #reopenWatches}）。关完时照它量环视（{@link #couples} 的），丢了、重开的那一次关照它记一条统计。
     */
    private static final class CloseWatch {
        final Coupling.Kind kind;
        final ChannelRules.Why why;
        final CameraTaken.Reason reason;
        /** 丢了、重开的那一路；计划里的一轮是 null。 */
        final String key;
        /** 开始关的时刻（调度的钟）；重开的那一次关，相机层报关完时改成它真正开始关的那一刻。 */
        long startedAt;
        /** 关完了的几路（座舱），按关完的先后，各自开始关以后多久关完（-1 = 到点还没关完）。 */
        final List<String> keys = new ArrayList<>();
        final List<Long> endedMs = new ArrayList<>();
        /** 关完的时刻（调度的钟）；0 = 还没关完。 */
        long endedAt;
        /** 丢了、重开的那一次关关了多久（相机层量的）；-1 = 不知道。 */
        long closeMs = -1;
        /** 关的是座舱、开始关那一刻环视开着：要量环视。 */
        final boolean couples;
        /**
         * 开始关那一刻环视在出画面（{@link ChannelRules#streaming}）：只有这样，关完 30 秒那一条才记进统计（「关座舱之后环视断没断」）——
         * 开着却早就没画面了（还没出第一帧、已经卡着）的，之后的断帧不是关座舱带的，几行照写、统计不记。
         */
        boolean surroundStreaming;
        final String surroundKey;
        final int surroundGeneration;
        /** 开始关那一刻每一路一共失败、丢缓冲几次。 */
        final Map<String, long[]> base;
        /** 关的那一路开始关那一刻的情形（丢了、重开的那一次关记统计用）；计划里的一轮是 null（统计在关那一步结束时记）。 */
        final StepContext context;
        /** 环视开始关那一刻的情形（关座舱时环视那一条统计用）；不量环视是 null。 */
        final StepContext surroundContext;
        final boolean mainForeground;
        /** 环视那一台开始关那一刻的来历；不量环视、没有记录是 null。 */
        final CameraHistory.Camera lineage;
        /** 计划里的一轮：关完还开着、要再量的那几路（以前关完一轮那一行量的那几路）。 */
        final Map<String, Integer> staying = new LinkedHashMap<>();
        /** 关完 1 秒那一次看到的：1 关完以后出过画面、0 没有、-1 还没量。 */
        int deliveredAfter = -1;

        CloseWatch(Coupling.Kind kind, ChannelRules.Why why, CameraTaken.Reason reason, String key, long startedAt,
                   boolean couples, String surroundKey, int surroundGeneration, Map<String, long[]> base,
                   StepContext context, StepContext surroundContext, boolean mainForeground,
                   CameraHistory.Camera lineage) {
            this.kind = kind;
            this.why = why;
            this.reason = reason;
            this.key = key;
            this.startedAt = startedAt;
            this.couples = couples;
            this.surroundKey = surroundKey;
            this.surroundGeneration = surroundGeneration;
            this.base = base;
            this.context = context;
            this.surroundContext = surroundContext;
            this.mainForeground = mainForeground;
            this.lineage = lineage;
        }

        /** 丢了、重开的那一路关完了（调度的钟），关了多久。 */
        void ended(String laneKey, long at, long ms) {
            keys.clear();
            endedMs.clear();
            keys.add(laneKey);
            endedMs.add(Math.max(0, at - startedAt));
            endedAt = at;
            closeMs = ms;
        }
    }

    /** 这一轮里走了的一路（{@link #tellRecording}）：哪一路、怎么走的、丢的那一下、我们关它的原因。 */
    private static final class Left {
        final String key;
        final Gone why;
        final CameraTaken.Reason reason;
        final ChannelRules.Why closed;

        Left(String key, Gone why, CameraTaken.Reason reason, ChannelRules.Why closed) {
            this.key = key;
            this.why = why;
            this.reason = reason;
            this.closed = closed;
        }
    }

    /** 一路这一次开着的账：从什么时候开起来的，重配了几次、各为什么（关的时候成了 {@link OpenSpan}）。 */
    private static final class Tally {
        final long openedAt;
        int added;
        int deadRemoved;
        int rescued;
        int removedOnly;

        Tally(long openedAt) {
            this.openedAt = openedAt;
        }
    }

    @Override
    public String toString() {
        return "ChannelScheduler" + new LinkedHashSet<>(ports.keySet());
    }
}
