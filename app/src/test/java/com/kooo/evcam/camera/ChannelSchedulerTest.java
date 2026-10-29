package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;

import com.kooo.evcam.camera.CameraNeeds.Holder;
import com.kooo.evcam.camera.ChannelRules.Kind;
import com.kooo.evcam.camera.ChannelRules.Out;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 通道调度（channel-logic §二，项目所有者 2026-10-10 确认）：调度只读事实、照做、记账、写黑匣子 —— 该做哪一步在
 * {@link ChannelRules}（有它自己的测试）。这里钉住的是调度这一层：叫醒并成一次、一步做完没有、15 秒和 60 秒到点、
 * 「输出被丢」派给谁、交给调度的画布什么时候放（大纲 §7.6、§7.7）、问的节奏、{@link ChannelScheduler#closingAnywhere()} 的起落，
 * 再把 1a 的现场回放 (a)–(e) 原样跑一遍 —— 这一次是经调度、经假的相机层（{@link FakePort}）跑。
 *
 * <p>相机层由这里扮演：动作只记下来；开好、配好、第一帧、关完、丢了、开不起来，都是测试说了算，照 SingleCamera 的样子
 * 改事实、再调调度的入口（真机上由 MultiCameraManager 从相机线程 post 到主线程）。队列和钟也是假的：
 * 任务按到点的先后跑，{@link #at} 把钟拨到那一刻。</p>
 */
public class ChannelSchedulerTest {

    private static final long T0 = 1_000_000L;
    /** 相机服务那几句用的钟（含深睡）比调度的钟快这么多：换钟换错了，回放就对不上。 */
    private static final long ELAPSED_AHEAD = 7_000_000L;
    private static final String SUR = CameraSlots.KEY_SURROUND;
    private static final String REAR = CameraSlots.KEY_CABIN_REAR;
    private static final String FRONT = CameraSlots.KEY_CABIN_FRONT;
    private static final int DEVICE_ERROR = CameraDevice.StateCallback.ERROR_CAMERA_DEVICE;
    private static final int DISCONNECTED = CameraTaken.Reason.DISCONNECTED_CODE;

    private long now = T0;
    private final FakePoster poster = new FakePoster();
    private final FakeHost host = new FakeHost();
    private final List<String> journal = new ArrayList<>();
    /** 调度告诉录像那一侧的（「streaming front」「gone left LOST DISCONNECTED」「condition left TAKEN」）。 */
    private final List<String> told = new ArrayList<>();
    /** 交给相机步骤统计的每一条（Journal.recorded）。 */
    private final List<ChannelScheduler.StepRecord> records = new ArrayList<>();
    /** 调度告诉录像那一侧「谁要它」变了的那几路（Recording.onLaneWantersChanged）。 */
    private final List<String> wantersChanged = new ArrayList<>();
    /** 关座舱时环视那几行（Journal.coupling）。 */
    private final List<ChannelScheduler.Coupling> couplings = new ArrayList<>();
    /** 通道的决定发给录像协调器的每一次（{@link #listenToVerdict}）：「KEPT DARK_ONSET」。 */
    private final List<String> verdicts = new ArrayList<>();
    /**
     * 账本（照 CameraRounds.process()：跟着进程、不跟着管线 —— 两份管线、几份假相机看的是同一本）。假相机照 SingleCamera 的样子
     * 下达一轮、下达关、报结束（{@link FakePort}）。
     */
    private final CameraRounds rounds = new CameraRounds();
    /**
     * 假的相机服务：每台相机此刻是哪一路相机对象拿着设备（开好了、还没关完）。同一台相机两份相机对象同时拿着 —— 两个句柄 —— 任何一条测试都当场失败
     * （{@link FakePort#openedRound}，第 2 步：一个调度、管线重建一个接一个）。
     */
    private final Map<String, FakePort> service = new HashMap<>();
    private final FakePort surround = new FakePort("2");
    private final FakePort rear = new FakePort("1");
    private final FakePort front = new FakePort("0");
    /** 这一份管线自己的那几样（录像计划、去掉前座舱、放完了）和它的黑匣子（{@link FakePipe}）。 */
    private final FakePipe pipe = new FakePipe();
    /** 进程里那一个调度（每一条测试一个新的：进程刚起来）。 */
    private ChannelScheduler s;
    /** 这一份管线的把手：setUp 里接上（第一份，当场接上）。 */
    private ChannelScheduler.Pipeline p;

    private final Out pvS = new Out(Kind.PREVIEW, "preview-2");
    private final Out pvR = new Out(Kind.PREVIEW, "preview-1");
    private final Out pvF = new Out(Kind.PREVIEW, "preview-0");
    private final Out recS = new Out(Kind.RECORD, "recorder-2");
    private final Out recR = new Out(Kind.RECORD, "recorder-1");
    private final Out recF = new Out(Kind.RECORD, "recorder-0");

    @Before
    public void setUp() {
        // 环视熄屏时留不留跟着进程（这一个调度）：每一条测试一个新的调度，从进程刚起来的样子起
        host.screenOn = true;
        host.foregroundService = true;
        host.mainForeground = true;
        host.heard = true;
        s = scheduler();
        p = register(s, pipe, true);
        Map<String, ChannelScheduler.CameraPort> lanes = new LinkedHashMap<>();
        // 故意不按开的次序给：调度照开的次序排
        lanes.put(FRONT, front);
        lanes.put(REAR, rear);
        lanes.put(SUR, surround);
        p.setLanes(lanes);
    }

    // ================================================================= 叫醒

    /** 几次叫醒并成一次 post：熄屏那一下所有监听者都做完了它才跑一轮。 */
    @Test
    public void wakesCoalesceIntoOneRound() {
        settle();
        int posts = poster.posts;
        int rounds = pipe.rounds;
        s.wake();
        s.wake();
        s.onNeedsChanged();
        s.wake();
        assertEquals("并成一次 post", posts + 1, poster.posts);
        settle();
        assertEquals("跑了一轮", rounds + 1, pipe.rounds);
        s.wake();
        assertEquals("跑完之后再叫，再排一次", posts + 2, poster.posts);
    }

    /** 没人叫也每 2 秒醒一次（保持到点、问的节奏都靠它兜底）。 */
    @Test
    public void ticksEveryTwoSeconds() {
        settle();
        int rounds = pipe.rounds;
        at(ChannelRules.TICK_MS * 5);
        assertTrue("至少醒了 5 次：" + (pipe.rounds - rounds), pipe.rounds - rounds >= 5);
    }

    // ================================================================= 一步做完没有、到点

    /** 开那一步：出第一帧才算完，下一路这时才开；黑匣子每一路一行，连着开完一行「按次序开相机」。 */
    @Test
    public void anOpenIsDoneOnItsFirstFrameAndTheNextLaneOpensOnlyThen() {
        claim(Holder.PREVIEW);
        declare(surround, pvS);
        declare(rear, pvR);
        declare(front, pvF);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), surround.actions);
        assertTrue(rear.actions.isEmpty());
        at(300);
        opened(surround);
        settle();
        assertTrue("配好了还没出画面：下一路不开", rear.actions.isEmpty());
        at(800);
        frame(surround);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), rear.actions);
        assertTrue(lines("stepEnded OPEN front").size() == 1);
        at(1_000);
        opened(rear);
        frame(rear);
        settle();
        at(1_200);
        opened(front);
        frame(front);
        settle();
        assertEquals(Arrays.asList("openTrail [front, left, back]"), lines("openTrail"));
        assertEquals(ChannelRules.Condition.STREAMING, p.condition(SUR));
        assertTrue(p.usable(SUR));
    }

    /** 开那一步满 15 秒还没出画面：先往下走（下一路开），这一步由救援接手。 */
    @Test
    public void anOpenThatShowsNothingForFifteenSecondsLetsTheNextLaneGo() {
        claim(Holder.PREVIEW);
        declare(surround, pvS);
        declare(rear, pvR);
        settle();
        at(500);
        opened(surround);
        settle();
        at(ChannelRules.STEP_MAX_MS - 1);
        assertTrue(rear.actions.isEmpty());
        at(ChannelRules.STEP_MAX_MS);
        assertEquals(Arrays.asList("open [PREVIEW]"), rear.actions);
        assertEquals(1, lines("stepEnded OPEN front timedOut").size());
    }

    /**
     * 配置里只开座舱（2026-10-16）：环视照样建着，交给调度时不在「启用的」里。主界面预览只开两路座舱，按开的次序；
     * 拍照也不为它开环视（登记表 §一「启用的全部」）。
     */
    @Test
    public void withOnlyTheCabinsEnabledPreviewAndPhotoLeaveTheSurroundClosed() {
        cabinsOnly();
        claim(Holder.PREVIEW);
        declare(rear, pvR);
        declare(front, pvF);
        settle();
        assertTrue("没启用的环视：预览不开它", surround.actions.isEmpty());
        assertEquals(Arrays.asList("open [PREVIEW]"), rear.actions);
        at(300);
        opened(rear);
        frame(rear);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), front.actions);
        at(600);
        opened(front);
        frame(front);
        settle();
        claim(Holder.PHOTO);
        at(5_000);
        assertTrue("拍照也不开它", surround.actions.isEmpty());
    }

    /**
     * 配置里只开座舱、超级镜子开着：后视镜要的是环视，照样开（项目所有者 2026-10-16），会话里只带后视镜。
     * 贴边收起注销 MIRROR：照现行逻辑保持 30 秒，没人要就关（项目所有者 2026-10-16 定贴边不始终保持）。
     */
    @Test
    public void withOnlyTheCabinsEnabledTheMirrorStillGetsTheSurround() {
        cabinsOnly();
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        declare(surround, mirror);
        claim(Holder.MIRROR);
        settle();
        assertEquals(Arrays.asList("open [MIRROR]"), surround.actions);
        assertTrue("座舱没人要", rear.actions.isEmpty() && front.actions.isEmpty());
        at(300);
        opened(surround);
        frame(surround);
        settle();
        drop(Holder.MIRROR);
        at(300 + ChannelRules.HOLD_MS - ChannelRules.TICK_MS);
        assertEquals("保持 30 秒内不动", 1, surround.actions.size());
        at(300 + ChannelRules.HOLD_MS + ChannelRules.TICK_MS);
        assertEquals(2, surround.actions.size());
        assertTrue(surround.actions.get(1), surround.actions.get(1).startsWith("close "));
    }

    /**
     * 一路丢了、那一次关一直没关完：最多挡 60 秒（在途封顶），到点按当时的状态判，别的路不再等它。
     */
    @Test
    public void aLossThatNeverClosesBlocksForAtMostSixtySeconds() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        declare(front, pvF);
        settle();
        // 前座舱在开的时候后座舱丢了：前座舱这一步做完之前、后座舱在关的 60 秒里，别的都不动
        assertEquals(Arrays.asList("open [PREVIEW]"), front.actions);
        opened(front);
        frame(front);
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        say(rear, false, true);
        settle();
        assertEquals(1, lines("waiting LOSING left").size());
        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertTrue(lines("judged").isEmpty());
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals("到点按当时的状态判（最后一句被占用 → 被拿着）", 1, lines("judged left TAKEN LOSS").size());
        assertEquals(ChannelRules.Condition.TAKEN, p.condition(REAR));
        // 那一次关返回之前不开它（项目所有者 2026-10-28）：黑匣子写一次，录像那一侧照现成的那一句说它无法录制
        assertEquals(Arrays.asList("held left 1 round=2 left own closing-lost ms=" + ChannelRules.IN_FLIGHT_MAX_MS),
                lines("held "));
        assertTrue(p.heldByClose(REAR));
        assertTrue(p.cannotOpen(REAR));
        at(ChannelRules.IN_FLIGHT_MAX_MS + 5 * ChannelRules.TICK_MS);
        assertEquals("一段只写一次", 1, lines("held ").size());
        assertEquals(0, rear.heldRefusals);
    }

    /**
     * 两路丢失前后脚关完：先关完的那一路到点了，也等后一路到点一起判 —— 等的是后一路，中间不空转
     * （审查 2026-10-11：以前按 0 等，每一毫秒醒一轮）。
     */
    @Test
    public void aDueJudgementWaitingForAnotherLossDoesNotSpin() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        surround.lose(now);
        p.onLossStarted("2", DEVICE_ERROR);
        rear.lose(now);
        p.onLossStarted("1", DEVICE_ERROR);
        at(100);
        say(surround, true, true);
        surround.closedNow();
        p.onLossClosed("2", 100);
        at(1_000);
        say(rear, true, true);
        rear.closedNow();
        p.onLossClosed("1", 1_000);
        settle();
        int rounds = pipe.rounds;
        at(1_299);
        assertTrue("环视早到点了，只等后座舱到点：" + (pipe.rounds - rounds), pipe.rounds - rounds <= 2);
        assertTrue(lines("judged").isEmpty());
        at(1_300);
        assertEquals(2, lines("judged").size());
    }

    /**
     * openCamera 发出去一直没回音（这一轮在开、在等回调）：开那一步满 15 秒算救援第 1 次，之后一直不救 —— 开没返回照「关没返回」同一条
     * （项目所有者 2026-10-29 Q1）：不作废那一轮、不换一条相机线程再开，等它返回或者 App 重启。相机层的在途过了 60 秒（busySince 归 0，
     * 只管别的路等多久）也照样不救、不计次数；满 60 秒写一次「还有开或关没返回」。它终于回音了（开好了），写一次「返回了」，照常出画面。
     * 以前相机层的在途封顶一到，调度就重开、相机层作废那一轮换一条相机线程开（retireStaleOpen，2026-10-29 删）。
     */
    @Test
    public void anOpenThatNeverAnswersIsNeverReopened() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        declare(surround, mirror);
        settle();
        assertEquals(Arrays.asList("open [MIRROR]"), surround.actions);
        surround.progressAgeMs = 20_000;   // 开出去以后一点进展都没有
        at(ChannelRules.STEP_MAX_MS);
        assertEquals(1, lines("stepEnded OPEN front timedOut").size());
        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertEquals("这一轮在开：不救", Arrays.asList("open [MIRROR]"), surround.actions);
        assertTrue(lines("held ").isEmpty());
        assertFalse("60 秒以内照旧算在起来", p.cannotOpen(SUR));
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        surround.busySince = 0;   // 相机层的在途过了 60 秒：只管别的路等多久
        s.wake();
        settle();
        assertEquals(Arrays.asList("held front 2 round=1 front own opening open-pending ms="
                + ChannelRules.IN_FLIGHT_MAX_MS), lines("held "));
        assertTrue(p.heldByClose(SUR));
        assertTrue(p.cannotOpen(SUR));

        at(10 * 60_000);
        assertEquals("10 分钟也不重开", Arrays.asList("open [MIRROR]"), surround.actions);
        assertEquals("一次也没去合并", 0, surround.refused);
        assertEquals(0, surround.heldRefusals);
        assertTrue(lines("gaveUp").isEmpty());
        assertEquals("不计次数", 1, view(s.snapshot(), SUR).attempts);
        assertEquals(1, lines("held ").size());
        ChannelScheduler.LaneView v = view(s.snapshot(), SUR);
        assertEquals(1, v.round);
        assertTrue(v.roundOwnedByLane);
        assertFalse("这一路手上那一轮：不算被占着", v.held);
        assertTrue(v.heldByClose);
        assertEquals(T0, v.roundStatus.outstandingSince);

        opened(surround);   // 它终于回音了
        frame(surround);
        settle();
        assertEquals(Arrays.asList("heldEnded front 2 round=1 ms=" + (10 * 60_000)), lines("heldEnded"));
        assertEquals(ChannelRules.Condition.STREAMING, p.condition(SUR));
        assertFalse(p.heldByClose(SUR));
    }

    /**
     * 改输出卡在配会话里（新会话一直配不好）：这一步满 15 秒，救援接手重开 —— 先下达关（第 2b 步：关相机那一下把那一次配置作废、
     * 连设备一起关掉），关完再在同一步里下达开；不因为在配会话合并掉（审查 2026-10-11：以前合并掉，救援梯子空转到 60 秒封顶）。
     */
    @Test
    public void aStuckApplyIsTakenOverByAReopenAtFifteenSeconds() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        settle();
        pipe.plan(SUR);
        declare(surround, recS);
        claim(Holder.RECORDING);
        settle();
        assertEquals(Arrays.asList("apply [MIRROR, RECORD]"), surround.actions);
        at(100);
        surround.session = SingleCamera.SessionOutputs.NONE;   // 旧会话关掉了，新的一直配不好
        surround.firstFrameAt = 0;
        surround.progressAgeMs = ChannelRules.STEP_MAX_MS;
        at(ChannelRules.STEP_MAX_MS + ChannelRules.TICK_MS);
        assertEquals(1, lines("stepEnded APPLY front timedOut").size());
        assertEquals("重开先下达关", Arrays.asList("apply [MIRROR, RECORD]", "close reopen"), surround.actions);
        long reopenAt = now;
        closed(surround);
        at(reopenAt - T0 + ChannelRules.REOPEN_GAP_MS);
        assertEquals(Arrays.asList("apply [MIRROR, RECORD]", "close reopen", "reopen [MIRROR, RECORD]"),
                surround.actions);
        assertEquals(0, surround.refused);
    }

    /** 丢了还没判完：判完之前 condition 说不出是不是被拿着（在救），判完才是 TAKEN。 */
    @Test
    public void anInUseLossIsRescuingUntilJudged() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        assertEquals(ChannelRules.Condition.STREAMING, p.condition(REAR));
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        assertEquals(ChannelRules.Condition.RESCUING, p.condition(REAR));
        at(5_400);
        rear.closedNow();
        p.onLossClosed("1", 5_400);
        settle();
        assertEquals("关完了，那 300 ms 里还在判：说不出是被拿着", ChannelRules.Condition.RESCUING, p.condition(REAR));
        at(5_700);
        assertEquals(ChannelRules.Condition.TAKEN, p.condition(REAR));
    }

    /** 设备错误（4）丢了：还在判，算在救。 */
    @Test
    public void aDeviceErrorLossIsRescuing() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        settle();
        surround.lose(now);
        p.onLossStarted("2", DEVICE_ERROR);
        assertEquals(ChannelRules.Condition.RESCUING, p.condition(SUR));
    }

    /**
     * 丢了、关完了、还没判，设备又到了我们手上（不是调度这一步开的）：照记成开着，那一次待判作废 ——
     * 不拿相机服务为我们这一开说的「被占用」把在出画面的一路判成被别的程序拿着（审查 2026-10-11）。
     */
    @Test
    public void aDeviceBackInOurHandsBeforeTheJudgementVoidsIt() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        settle();
        surround.lose(now);
        p.onLossStarted("2", DEVICE_ERROR);
        at(1_000);
        surround.closedNow();
        p.onLossClosed("2", 1_000);
        at(1_100);
        say(surround, false, true);
        surround.deviceOpen = true;
        surround.holds = true;
        configured(surround, pvS);
        frame(surround);
        p.onOpened("2");
        at(2_000);
        assertTrue("待判作废，不判", lines("judged").isEmpty());
        assertEquals(ChannelRules.Condition.STREAMING, p.condition(SUR));
    }

    // ================================================================= 2.11.4（项目所有者 2026-10-14 批准）

    /**
     * 丢了之后的那一次关也是我们的关（第 1b 项）：关完 5 ms 后相机服务为这一关说的「空闲」，照调度的答案（ourStepInFlight，
     * CameraAvailabilityWatch.classify 问的就是它）算我们引起的 —— 判完普通故障就救，不白等 3 秒安静。
     * 10-13 20:10:42：「1 空闲」比关完晚 6 ms 到，被记成别人引起的变化，救援白等了 3 秒。
     */
    @Test
    public void theFreeWordJustAfterOurLossCloseIsOursAndTheRescueDoesNotWaitForQuiet() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        rear.lose(now);
        p.onLossStarted("1", DEVICE_ERROR);
        settle();
        at(6_200);
        rear.closedNow();
        p.onLossClosed("1", 6_200);
        settle();
        at(6_205);
        boolean ours = s.ourStepInFlight("1");
        assertTrue("关完 5 ms：为这一关说的「空闲」是我们的", ours);
        say(rear, true, ours);
        s.wake();
        at(6_200 + ChannelRules.JUDGE_DELAY_MS);
        assertEquals(1, lines("judged left ORDINARY LOSS").size());
        assertTrue("不等安静：" + lines("waiting"), lines("waiting QUIET").isEmpty());
        assertEquals("关着的一路的重开：照开那样开", Arrays.asList("open [PREVIEW]"), rear.actions);
    }

    /**
     * 在关旧设备的相机是在途（第 1c 项）：救援的重开先下达关（第 2b 步），关的那一半 15 秒到点还没关完 —— 这一步照救援到点结束，
     * 开的那一半不再下达；这一路的关是重开的关那一半，别的路照「在途」一步都不动（最多 60 秒）。关完了，这一路交给平常的规矩：
     * 排着的前座舱先开，后座舱照救援梯子再救（第 3 次；关着的一路照开那样开）。以前到点就「先往下走」，旧设备还在拆，别的路就动了；
     * 2b 之前关完是相机层接着开新的（不算次数），现在是梯子上的下一次。
     */
    @Test
    public void aRescueReopenWhoseOldDeviceIsStillClosingHoldsEveryoneAfterItsStepTimesOut() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR);
        streaming(surround, recS);
        declare(rear, recR);
        settle();
        assertEquals(Arrays.asList("open [RECORD]"), rear.actions);
        at(100);
        opened(rear);   // 配好了，一帧不出
        settle();
        rear.progressAgeMs = ChannelRules.STEP_MAX_MS - 100;
        at(ChannelRules.STEP_MAX_MS);
        assertEquals("第 2 次：重开，先下达关", Arrays.asList("open [RECORD]", "close reopen"), rear.actions);
        long reopenAt = now;
        assertEquals("关的那一半在那一轮里（账本：在关）", CameraRound.Phase.CLOSING, rear.live.phase());

        pipe.plan(FRONT);   // 这期间前座舱也要录了：它排着
        declare(front, recF);
        s.onNeedsChanged();
        settle();
        at(reopenAt - T0 + ChannelRules.STEP_MAX_MS);
        assertEquals(1, lines("stepEnded RESCUE left timedOut").size());
        assertEquals(1, lines("waiting BUSY left").size());
        at(reopenAt - T0 + ChannelRules.STEP_MAX_MS + 5_000);
        assertTrue("旧设备还在关：前座舱不开", front.actions.isEmpty());
        assertEquals("开的那一半随这一步结束不再下达", Arrays.asList("open [RECORD]", "close reopen"), rear.actions);

        // 旧设备关了 20.5 秒，关完：这一路关着，平常的规矩接手 —— 排着的前座舱先开
        at(reopenAt - T0 + 20_500);
        closed(rear);
        settle();
        assertEquals(Arrays.asList("open [RECORD]"), front.actions);
        assertEquals(Arrays.asList("open [RECORD]", "close reopen"), rear.actions);
        opened(front);
        frame(front);
        settle();
        assertEquals("后座舱照梯子再救：关着的一路照开那样开", Arrays.asList("open [RECORD]", "close reopen", "open [RECORD]"),
                rear.actions);
        assertEquals(Arrays.asList(SingleCamera.OpenKind.PLAIN, SingleCamera.OpenKind.PLAIN), rear.kinds);
        assertEquals("关的那一半关了 20.5 秒：一条重开的关", 1,
                lines("recorded left CLOSE CLOSED ms=" + 20_500 + " kind=REOPEN").size());
    }

    /**
     * 10-13 20:10 经调度回放（第 1a、1d 项、R2）：后座舱只为录像开、配好了一帧不出，那 15 秒里要了预览；到点先开前座舱，
     * 再救后座舱 —— 重开、带着预览和录像，不改输出。开座舱的那两下各有一行「时的情形」（哨兵、主界面、离上次关多久、环视）；
     * 15 秒那一行带着配好以来相机回了什么（HAL 一个都没回）；每一步结束交一条统计，重开那一条带着旧设备关了多久。
     */
    @Test
    public void replay1013_aBlackRearIsReopenedWithItsPreviewAndEveryStepIsRecorded() {
        host.vehicle = new ChannelScheduler.Vehicle(1, 100, 4_600, 100, 125);
        host.mainForeground = false;
        host.startedWhileVisible = true;
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        declare(rear, recR);
        declare(front, recF);
        settle();
        assertEquals(Arrays.asList("open [RECORD]"), rear.actions);
        assertEquals(1, lines("opening left OPEN sentry=1 fg=false sinceClose=-1 held=-1 surround=STREAMING").size());
        at(100);
        opened(rear);
        rear.counts = new SessionCounts.Snapshot(now, now, 0, 0, 0, 0, -1, -1,
                java.util.Collections.<String, Long>emptyMap());
        settle();

        at(6_700);   // 主界面回到前台：两路座舱要预览
        host.mainForeground = true;
        claim(Holder.PREVIEW);
        declare(rear, pvR);
        declare(front, pvF);
        settle();
        assertEquals("开那一步在途：不动", Arrays.asList("open [RECORD]"), rear.actions);

        rear.progressAgeMs = ChannelRules.STEP_MAX_MS - 100;
        at(ChannelRules.STEP_MAX_MS);
        assertEquals(1, lines("stepEnded OPEN left timedOut counts=HAL_HOLDING").size());
        assertEquals("排在后面的前座舱先开", Arrays.asList("open [PREVIEW, RECORD]"), front.actions);
        opened(front);
        frame(front);
        settle();
        assertEquals("不改输出，重开：先下达关", Arrays.asList("open [RECORD]", "close reopen"), rear.actions);
        assertTrue(lines("stepEnded APPLY").isEmpty());
        assertEquals(1, lines("opening left RESCUE").size());

        long reopenAt = now;
        at(reopenAt - T0 + 8_535);
        closed(rear);   // 旧设备关了 8.5 秒
        settle();
        assertEquals("关完了，早过了 300 ms：同一步里再下达开，带上预览",
                Arrays.asList("open [RECORD]", "close reopen", "reopen [PREVIEW, RECORD]"), rear.actions);
        at(reopenAt - T0 + 8_700);
        opened(rear);
        frame(rear);
        settle();

        assertEquals(Arrays.asList(
                "recorded left OPEN NO_FRAMES ms=15000 counts=HAL_HOLDING surround=STREAMING",
                "recorded back OPEN FRAMED ms=0 surround=STREAMING",
                "recorded left CLOSE CLOSED ms=8535 kind=REOPEN surround=STREAMING",
                "recorded left REOPEN FRAMED ms=8700 oldClose=8535 surround=STREAMING"), lines("recorded"));
        assertEquals("重开时关旧设备、环视开着：关完那一刻量了环视（2.11.5 第 D2 项）",
                1, lines("coupling END REOPEN [left]").size());
        ChannelScheduler.StepRecord open = records.get(0);
        assertEquals(Integer.valueOf(1), open.context.vehicle.sentry);
        assertFalse("开的那一刻主界面在后台", open.context.mainForeground);
        assertEquals(125, open.context.vehicle.importance);
    }

    // ================================================================= 救援重开分两半（第 2b 步，项目所有者 2026-10-29）

    /** 后座舱只为录像开着、配好了一帧不出：开那一步 15 秒到点，救援第 2 次就重开 —— 先下达关。返回下达关的那一刻（调度的钟）。 */
    private long stuckRearIsReopened() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR);
        streaming(surround, recS);
        declare(rear, recR);
        settle();
        assertEquals(Arrays.asList("open [RECORD]"), rear.actions);
        at(100);
        opened(rear);   // 配好了，一帧不出
        settle();
        rear.progressAgeMs = ChannelRules.STEP_MAX_MS - 100;
        at(ChannelRules.STEP_MAX_MS);
        assertEquals("重开：先下达关", Arrays.asList("open [RECORD]", "close reopen"), rear.actions);
        return now;
    }

    /**
     * 救援的重开是两轮（第 2b 步）：关的那一半是这一路那一轮的一次关（不是计划里的关 —— 没有「通道：关 …」「关相机：…」、关相机小结）；
     * 关完、那一轮结束、离下达关满 300 ms 才下达开：新的一轮（轮次号 +1）、带着救援的那一种。同一台相机从不同时两个句柄（假的相机服务当场查）。
     * 这一步一行、一条重开的统计，关的那一半一条重开的关；录像那一侧只听说一次「在救」。
     */
    @Test
    public void aRescueReopenIsACloseThenARescueOpenInTheNextRound() {
        long reopenAt = stuckRearIsReopened();
        int first = rear.roundNumbers.get(0);
        assertEquals("关的那一半是这一路那一轮的关", first, rear.live.number);
        assertTrue(rear.live.cancelled());
        assertEquals(1, told("gone left RESCUED").size());

        at(reopenAt - T0 + 80);
        closed(rear);   // 关得快：80 ms
        settle();
        assertEquals("关完了，还不到 300 ms：不开", Arrays.asList("open [RECORD]", "close reopen"), rear.actions);
        at(reopenAt - T0 + ChannelRules.REOPEN_GAP_MS - 1);
        assertEquals(2, rear.actions.size());
        at(reopenAt - T0 + ChannelRules.REOPEN_GAP_MS);
        assertEquals(Arrays.asList("open [RECORD]", "close reopen", "reopen [RECORD]"), rear.actions);
        assertEquals(Arrays.asList(SingleCamera.OpenKind.PLAIN, SingleCamera.OpenKind.RESCUE), rear.kinds);
        assertEquals("开的那一半是新的一轮", first + 1, (int) rear.roundNumbers.get(1));

        at(reopenAt - T0 + 700);
        opened(rear);
        frame(rear);
        settle();
        assertEquals("一步一行", 1, lines("stepEnded RESCUE left").size());
        assertEquals(Arrays.asList(
                "recorded left OPEN NO_FRAMES ms=15000 surround=STREAMING",
                "recorded left CLOSE CLOSED ms=80 kind=REOPEN surround=STREAMING",
                "recorded left REOPEN FRAMED ms=700 oldClose=80 surround=STREAMING"), lines("recorded"));
        assertEquals("录像那一侧只听说一次在救", 1, told("gone left RESCUED").size());
        assertTrue("不是计划里的关", lines("closing ").isEmpty());
        assertTrue(lines("closeRound").isEmpty());
        assertTrue(lines("openSpan").isEmpty());
        assertEquals(0, rear.heldRefusals);
        assertEquals(0, rear.refused);
        assertEquals(ChannelRules.Condition.STREAMING, p.condition(REAR));
    }

    /**
     * 两半之间那一下（关完了、开的那一半还没下达）相机服务说的话，调度答「我们在途」（CameraAvailabilityWatch 照它分类，不挡安静）——
     * 和以前整个重开都记成在开一样：主线程卡了一下、离关完早过了 500 ms 也照算。开的那一半下达以后是在开，照旧算。
     */
    @Test
    public void aServiceWordInTheGapBetweenTheHalvesIsOurs() {
        long reopenAt = stuckRearIsReopened();
        assertTrue("关的那一半：在关", s.ourStepInFlight("1"));
        at(reopenAt - T0 + 80);
        closed(rear);   // 关完了：那一轮结束，这一路关着（叫醒的那一轮还没跑）
        now = reopenAt + 900;   // 主线程卡了一下：离关完早过了 500 ms，开的那一半还没下达
        assertTrue("两半之间：我们在途", s.ourStepInFlight("1"));
        settle();
        assertEquals(Arrays.asList("open [RECORD]", "close reopen", "reopen [RECORD]"), rear.actions);
        assertTrue("开的那一半：在开", s.ourStepInFlight("1"));
    }

    /**
     * 关的那一半在关的时候退出了：手上这一步照等（退出照常等它做完）；关完了，开的那一半不再下达，这一步当场结束（一行「不再开：退出」，
     * 不记重开那一条），接着照次序关别的路。以前是相机层先把它开起来，再在按次序关里关掉（第 2b 步改了的那一处行为）。
     */
    @Test
    public void anExitDuringTheCloseHalfDropsTheOpenHalf() {
        long reopenAt = stuckRearIsReopened();
        at(reopenAt - T0 + 500);
        exitApp();
        settle();
        assertTrue("手上那一步在做：先不关别的", surround.actions.isEmpty());
        at(reopenAt - T0 + 1_000);
        closed(rear);
        settle();
        assertEquals("开的那一半不再下达", Arrays.asList("open [RECORD]", "close reopen"), rear.actions);
        assertEquals(1, lines("stepEnded RESCUE left dropped EXIT after=1000").size());
        assertTrue("没开设备：不记重开那一条", lines("recorded left REOPEN").isEmpty());
        assertEquals(1, lines("recorded left CLOSE CLOSED ms=1000 kind=REOPEN").size());
        assertEquals("接着照次序关：环视单独关", Arrays.asList("close exit"), surround.actions);
    }

    /** 开的那一半开好了、还没出画面就丢了：这一步里丢了（和以前重开之后丢了一样），一行、一条出事的统计，录像那一侧听说它丢了。 */
    @Test
    public void aLossDuringTheOpenHalfEndsTheStepAsALoss() {
        long reopenAt = stuckRearIsReopened();
        at(reopenAt - T0 + 100);
        closed(rear);
        at(reopenAt - T0 + ChannelRules.REOPEN_GAP_MS);
        assertEquals(3, rear.actions.size());
        at(reopenAt - T0 + 500);
        opened(rear);
        settle();
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        settle();
        assertEquals(1, lines("stepEnded RESCUE left failed LOSS").size());
        assertEquals(1, lines("recorded left REOPEN ERROR").size());
        assertEquals(1, told("gone left LOST").size());
        assertEquals("关的那一半那一条照记，丢了之后那一次关另记", 1, lines("recorded left CLOSE CLOSED ms=100 kind=REOPEN").size());
    }

    /**
     * 这一步里出了事（改输出时丢了）：2.11.4 起也写一行（第 1a 项）、交一条统计 —— 以前什么都不写，
     * 10-13 那一次没做完的改输出只在关相机小结的账里看得出来。
     */
    @Test
    public void aFailedApplyWritesItsOwnLineAndIsRecorded() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        settle();
        pipe.plan(SUR);
        declare(surround, recS);
        claim(Holder.RECORDING);
        settle();
        assertEquals(Arrays.asList("apply [MIRROR, RECORD]"), surround.actions);
        at(600);
        surround.lose(now);
        p.onLossStarted("2", DEVICE_ERROR);
        settle();
        assertEquals(1, lines("stepEnded APPLY front failed LOSS after=600 added=[RECORD]").size());
        assertEquals(Arrays.asList("recorded front APPLY ERROR ms=600"), lines("recorded"));
        assertEquals(CameraTaken.Reason.Source.DEVICE_ERROR, records.get(0).reason.source);
    }

    /**
     * 前座舱只用一次（暂时的规矩，项目所有者 2026-10-22 定、10-23 改成只管前座舱）：黑满 30 秒放开座舱的同一刻，调度报一次
     * 「前座舱去掉了」；之后亮屏，后座舱照一般的规矩开回来接着录，前座舱录像计划里还写着也不再开。
     */
    @Test
    public void withTheOneShotRuleOnlyTheFrontCabinIsRetiredAndTheRearComesBackWhenLit() {
        host.frontCabinOneShot = true;
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        at(1_000);
        host.goDark();
        s.wake();
        settle();
        assertTrue("黑了不到 30 秒：还没去掉", pipe.frontRetired.isEmpty());
        at(1_000 + ChannelRules.HOLD_MS);
        assertEquals(Arrays.asList(FRONT), pipe.frontRetired);
        at(1_000 + ChannelRules.HOLD_MS + 120);
        closed(surround);
        settle();
        assertEquals("两路座舱照旧熄屏放开", Arrays.asList("close dark-release"), rear.actions);
        assertEquals(Arrays.asList("close dark-release"), front.actions);
        at(1_000 + ChannelRules.HOLD_MS + 220);
        closed(rear);
        closed(front);
        settle();
        opened(surround);
        frame(surround);
        settle();
        at(1_000 + ChannelRules.HOLD_MS + 5_000);
        host.goLit();
        s.wake();
        settle();
        at(1_000 + ChannelRules.HOLD_MS + 20_000);
        assertEquals("亮屏：后座舱开回来接着录", Arrays.asList("close dark-release", "open [RECORD]"), rear.actions);
        assertEquals("前座舱不再开", Arrays.asList("close dark-release"), front.actions);
        assertEquals("只报一次", Arrays.asList(FRONT), pipe.frontRetired);
    }

    /**
     * 座舱的每一次关都记下关的时候环视怎样、关了多久（R3「关座舱时先短暂关环视」要比的就是它）。2.11.6 起能用相机时环视先关，
     * 座舱那两条记「环视关着」（{@link #replay1014_aDarkReleaseClosesTheSurroundFirstAndItRejoinsTheRecording}）；这里此刻用不了相机
     * （主界面在后台、前台服务是后台起的），座舱照旧在出画面的环视旁边关。
     */
    @Test
    public void everyCabinCloseRecordsWhatTheSurroundWasDoing() {
        host.mainForeground = false;
        host.startedWhileVisible = false;
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        at(1_000);
        host.sentryArmed();   // 哨兵模式布防：熄屏时环视留着（项目所有者 2026-10-27）
        host.goDark();
        s.wake();
        settle();
        at(1_000 + ChannelRules.HOLD_MS);
        assertEquals(Arrays.asList("close dark-release"), rear.actions);
        at(1_000 + ChannelRules.HOLD_MS + 200);
        closed(rear);
        settle();
        at(1_000 + ChannelRules.HOLD_MS + 6_200);
        closed(front);
        settle();
        assertEquals(Arrays.asList(
                "recorded left CLOSE CLOSED ms=200 surround=STREAMING",
                "recorded back CLOSE CLOSED ms=6200 surround=STREAMING"), lines("recorded"));
        assertEquals("环视这段时间里量了：会话没动", Boolean.FALSE, records.get(1).surround.sessionChanged);
    }

    /**
     * 10-14 21:52:20（A7）的数，2.11.5 那样座舱在环视旁边关（项目所有者 2026-10-15 批准，2.11.5 第 D1、D2、D3 项，只记录）—— 2.11.6 起
     * 能用相机时环视先关（{@link #replay1014_aDarkReleaseClosesTheSurroundFirstAndItRejoinsTheRecording}），这几行只剩用不了相机时
     * 的计划里的关（这里：主界面在后台、前台服务是后台起的）和计划外的关。环视是相机 2 回来以后的第 5 台，屏幕黑满 30 秒放开座舱；
     * 环视最后一帧在开始关后 67 ms（前座舱关完前 35 ms、后座舱关完前 73 ms），之后一帧不出。
     * 关完那一刻、关完之后头一轮（1 秒以后，不为它另醒）、关完 30 秒各一行；8 秒时规则照常救环视（重建会话不用开相机），30 秒那一行量到救它那一刻；
     * 那一条记进统计：环视断了、第 5 台、主界面在后台。这几行一步都不动：动环视的只有规则那一下救。
     */
    @Test
    public void aCabinReleaseBesideANotFirstSurroundWhileTheCameraIsUnusableIsMeasuredThreeTimesAndCounted() {
        host.cameraHistory.reset("2", CameraHistory.Reset.CAME_BACK, 1_000L, 1_000L);
        for (int i = 0; i < 5; i++) {
            host.cameraHistory.opened("2", 2_000L + i);
        }
        host.mainForeground = false;
        host.startedWhileVisible = false;
        host.vehicle = new ChannelScheduler.Vehicle(0, 0x00200104, 30_000, -1, 30_000, 125);
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        at(1_000);
        host.sentryArmed();   // 哨兵模式布防：熄屏时环视留着（项目所有者 2026-10-27）
        host.goDark();
        s.wake();
        settle();
        long release = 1_000 + ChannelRules.HOLD_MS;
        at(release);
        assertEquals(Arrays.asList("close dark-release"), rear.actions);
        assertEquals(Arrays.asList("close dark-release"), front.actions);
        surround.lastResultAt = T0 + release + 67;
        at(release + 102);
        closed(front);
        settle();
        at(release + 140);
        closed(rear);
        settle();
        assertEquals("关完那一刻：按关完的先后，环视最后一帧比关完早 73 ms、比开始关晚 67 ms", 1,
                lines("coupling END PLANNED [back, left] DARK_RELEASE front gap=0 same last=-73/67"
                        + " trouble=back:0/0,left:0/0,front:0/0 lineage=5/CAME_BACK").size());
        ChannelScheduler.Coupling end = couplings.get(0);
        assertEquals(Arrays.asList(102L, 140L), end.endedMs);
        assertFalse(end.mainForeground);
        assertEquals(Integer.valueOf(0x00200104), end.vehicle.ignition);

        at(release + 140 + ChannelScheduler.COUPLING_CHECK_MS - 1);
        assertTrue(lines("coupling AFTER_1S").isEmpty());
        at(release + 140 + ChannelRules.TICK_MS + ChannelScheduler.COUPLING_CHECK_MS);
        assertEquals("关完以后环视没出过画面", 1, lines("coupling AFTER_1S PLANNED [back, left] DARK_RELEASE front gap=0 same"
                + " last=-73/67").size());
        assertFalse(couplings.get(1).deliveredAfterEnd());

        // 8 秒没画面：规则照常救环视（重建会话）—— 30 秒那一行量到那一刻
        at(release + 8_100);
        surround.gapMs = 8_000;
        surround.failures = 1;
        surround.progressAgeMs = CameraLiveness.STUCK_MS;
        s.wake();
        settle();
        assertEquals(1, surround.actions.size());
        assertTrue(surround.actions.get(0), surround.actions.get(0).startsWith("rebuild"));
        // 醒的那一轮和 2.11.4 一样是开始关 + 30 秒（那一下 2.11.4 写关完一轮那一行）；关完还不到 30 秒，不量，之后的头一轮量
        at(release + ChannelScheduler.AFTERMATH_MS);
        assertTrue(pipe.roundAt.toString(), pipe.roundAt.contains(release + ChannelScheduler.AFTERMATH_MS));
        assertTrue(lines("coupling AFTER_30S").isEmpty());
        at(release + 140 + ChannelScheduler.AFTERMATH_MS + ChannelRules.TICK_MS);
        assertEquals(1, lines("coupling AFTER_30S PLANNED [back, left] DARK_RELEASE front gap=8000 same ours=RESCUE@8100"
                + " last=-73/67 trouble=back:0/0,left:0/0,front:1/0 lineage=5/CAME_BACK a1=0").size());
        assertTrue("计划里的关不另写关完一轮那一行", lines("closeAftermath").isEmpty());
        assertEquals(1, lines("recorded front COUPLE STALLED ms=8000 lineage=5/CAME_BACK").size());
        ChannelScheduler.StepRecord couple = null;
        for (ChannelScheduler.StepRecord r : records) {
            if (r.move == StepStats.Move.COUPLE) {
                couple = r;
            }
        }
        assertTrue(couple != null);
        assertEquals(SUR, couple.context.key);
        assertFalse("开始关那一刻主界面在后台", couple.context.mainForeground);
        assertEquals(0, couple.coupling.deliveredAfter);
        assertEquals(ChannelRules.Move.RESCUE, couple.coupling.surround.ours);
        assertEquals("座舱关着，一步没动", Arrays.asList("close dark-release"), rear.actions);
        assertEquals(Arrays.asList("close dark-release"), front.actions);
    }

    // ================================================================= 画布算到关完（2026-10-20 审查，车主批）

    /**
     * 关一开始会话快照就清了，相机还在关：这期间画布还算挂在这一路上，交给这一份调度、关完才放（以前当场就不算了，
     * 退出、重建时画布在关完之前被放掉）。
     */
    @Test
    public void aCanvasStaysCarriedUntilItsCameraHasClosed() {
        host.foregroundService = false;   // 没有前台服务就不保持：没人要当场关
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        settle();
        assertTrue(p.sessionCarries(SUR, mirror));
        drop(Holder.MIRROR);
        settle();
        assertEquals(1, surround.actions.size());
        assertTrue(surround.actions.get(0), surround.actions.get(0).startsWith("close "));
        assertTrue("快照清了，相机还在关：还带着", p.sessionCarries(SUR, mirror));
        at(300);
        closed(surround);
        settle();
        assertFalse("关完了：不带着了", p.sessionCarries(SUR, mirror));
    }

    // ================================================================= 超级镜子「后视 + 后座舱」（项目所有者 2026-10-19）

    /**
     * 配置里只开环视，管线为后视镜建了后座舱（不算启用的）。切换范围换到「后视 + 后座舱」：后座舱为后视镜开，会话里只带后视镜的画布；
     * 换出这一档：它没人要了，保持 30 秒，到点照「关座舱前先关环视」—— 环视给后视镜开着，先关环视、再关后座舱、环视开回来。
     */
    @Test
    public void theRearCabinViewOpensTheCabinForTheMirrorAndHoldsItWhenLeft() {
        surroundOnly();
        Out mirrorS = new Out(Kind.MIRROR, "mirror-2");
        Out mirrorR = new Out(Kind.MIRROR, "mirror-1");
        claim(Holder.MIRROR);
        streaming(surround, mirrorS);
        declare(rear, mirrorR);
        settle();
        assertTrue("切换范围不含后座舱：后视镜只要环视", rear.actions.isEmpty());

        host.mirrorCabin = true;
        s.wake();
        settle();
        assertEquals("换到这一档：后座舱为后视镜开，只带后视镜的画布", Arrays.asList("open [MIRROR]"), rear.actions);
        assertEquals(EnumSet.of(Holder.MIRROR), view(s.snapshot(), REAR).wantedBy);
        at(300);
        opened(rear);
        frame(rear);
        settle();
        assertEquals(ChannelRules.Condition.STREAMING, p.condition(REAR));

        at(1_000);
        host.mirrorCabin = false;
        s.wake();
        settle();
        long expiry = 1_000 + ChannelRules.HOLD_MS;
        at(expiry - 1);
        assertEquals("换出这一档：没人要了，保持 30 秒内不动", Arrays.asList("open [MIRROR]"), rear.actions);
        assertTrue(surround.actions.isEmpty());
        at(expiry);
        assertEquals("保持到点：先关环视", Arrays.asList("close surround-first"), surround.actions);
        at(expiry + 150);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("open [MIRROR]", "close hold-over"), rear.actions);
        at(expiry + 250);
        closed(rear);
        settle();
        assertEquals("环视开回来给后视镜", Arrays.asList("close surround-first", "open [MIRROR]"), surround.actions);
    }

    /** 后视镜没登记着（贴边、熄屏、窗口关了）：切换范围是「后视 + 后座舱」也不为它开后座舱。 */
    @Test
    public void theRearCabinViewWantsNothingWhileTheMirrorIsNotClaimed() {
        surroundOnly();
        host.mirrorCabin = true;
        declare(rear, new Out(Kind.MIRROR, "mirror-1"));
        settle();
        at(5_000);
        assertTrue(rear.actions.isEmpty());
        assertTrue(surround.actions.isEmpty());
        assertTrue(view(s.snapshot(), REAR).wantedBy.isEmpty());
    }

    // ================================================================= 关座舱前先关环视（项目所有者 2026-10-15 定，2.11.6）

    /**
     * 10-14 21:52:20（A7）经调度回放，2.11.6：三路在录、主界面在后台（前台服务是主界面在前时起的，能用相机），屏幕黑满 30 秒。
     * 那一刻只关环视（「surround-first」）：录像那一侧听说它走了、原因是关座舱前先关环视，录制器停放（录像还留着它，不派「随相机关了」）；
     * 环视关完座舱才一起关（熄屏：座舱放开），座舱那两条统计记「环视关着」，不写关座舱时环视那几行（环视不在旁边）；座舱关完「开」那一步
     * 照常开回环视、带着同一个录像输出，出了画面录像那一侧再听说一次（同一个录制器再加入）。一轮关一行「关相机：环视 → 座舱」，
     * 那一趟一行（环视断了多久、各段多久），开回来那一条统计带着那一趟。登记表一次都没变，救援一次都没有。
     */
    @Test
    public void replay1014_aDarkReleaseClosesTheSurroundFirstAndItRejoinsTheRecording() {
        host.mainForeground = false;
        host.startedWhileVisible = true;
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        told.clear();
        int needsLines = lines("needs").size();
        Set<Holder> holders = EnumSet.copyOf(host.holders);

        at(1_000);
        host.sentryArmed();   // 哨兵模式布防：熄屏时环视留着（项目所有者 2026-10-27）
        host.goDark();
        s.wake();
        settle();
        long release = 1_000 + ChannelRules.HOLD_MS;
        at(release - 1);
        assertTrue(surround.actions.isEmpty() && rear.actions.isEmpty() && front.actions.isEmpty());
        at(release);
        assertEquals("黑满 30 秒：只关环视", Arrays.asList("close surround-first"), surround.actions);
        assertEquals("环视留着那一行", Arrays.asList("surroundInDark KEPT DARK_ONSET sentry=2 records"),
                lines("surroundInDark"));
        assertTrue("座舱等环视关完", rear.actions.isEmpty() && front.actions.isEmpty());
        assertEquals(Arrays.asList("closing [front] SURROUND_FIRST"), lines("closing"));
        assertEquals(Arrays.asList("gone front CLOSED SURROUND_FIRST"), told("gone"));

        at(release + 150);
        closed(surround);
        settle();
        assertTrue("录像还要它：录制器停放，不派", surround.drops.isEmpty());
        assertEquals(Arrays.asList("close dark-release"), rear.actions);
        assertEquals(Arrays.asList("close dark-release"), front.actions);
        assertEquals(1, lines("closing [left, back] DARK_RELEASE").size());
        assertEquals(Arrays.asList("gone front CLOSED SURROUND_FIRST", "gone left CLOSED DARK_RELEASE",
                "gone back CLOSED DARK_RELEASE"), told("gone"));

        at(release + 250);
        closed(front);
        settle();
        at(release + 290);
        closed(rear);
        settle();
        assertEquals("座舱关完：开那一步照常开回环视，带着同一个录像输出",
                Arrays.asList("close surround-first", "open [RECORD]"), surround.actions);
        assertEquals("一轮关一行：环视 → 座舱", Arrays.asList("closeRound front | back, left"), lines("closeRound"));
        assertTrue("环视不在座舱旁边：不写关座舱时环视那几行", lines("coupling").isEmpty());
        assertTrue(surround.drops.isEmpty() && rear.drops.isEmpty() && front.drops.isEmpty());

        at(release + 400);
        opened(surround);
        at(release + 520);
        frame(surround);
        settle();
        assertEquals("出了画面：录像那一侧再听说一次，同一个录制器再加入", Arrays.asList("streaming front"), told("streaming"));
        assertEquals(Arrays.asList("surroundFirst FRAMED gap=520 surround=150 cabins=[back, left]/DARK_RELEASE/140 open=230"),
                lines("surroundFirst"));
        assertEquals(Arrays.asList(
                "recorded front CLOSE CLOSED ms=150",
                "recorded left CLOSE CLOSED ms=140 surround=CLOSED",
                "recorded back CLOSE CLOSED ms=100 surround=CLOSED",
                "recorded front OPEN FRAMED ms=230 sf=290/140"), lines("recorded"));

        at(release + 60_000);
        assertEquals("之后一步不动", Arrays.asList("close surround-first", "open [RECORD]"), surround.actions);
        assertEquals(Arrays.asList("close dark-release"), rear.actions);
        assertTrue("救援一次都没有", lines("stepEnded RESCUE").isEmpty());
        assertEquals(0, view(s.snapshot(), SUR).attempts);
        assertEquals("登记表一次都没变", holders, host.holders);
        assertEquals(needsLines, lines("needs").size());
        assertTrue(lines("closeAftermath").isEmpty() && lines("coupling").isEmpty());
    }

    /**
     * 10-15 07:42:42（车主的测试 A 第 2 次）经调度回放，2.11.6：三路录过、停了录（录像输出留在会话里），超级镜子开着，07:42:12 最小化 ——
     * 两路座舱各自保持 30 秒。到点只关环视；停了录，录像不再留着它的录像输出，关完当轮「随相机关了」（录制器放掉，和以前关相机一样）；
     * 环视关完座舱保持到点一起关；座舱关完环视照常开回来给后视镜（只带后视镜）。以前这一下座舱在环视旁边关，环视在开始关后 60 ms 停了、救了约 45 秒。
     */
    @Test
    public void replay1015_aHoldExpiryBesideTheMirrorClosesTheSurroundFirstAndTheMirrorComesBack() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        streaming(surround, pvS, mirror, recS);   // 07:42:10 停录：录制器停放，录像输出还声明着、留在会话里
        streaming(rear, pvR, recR);
        streaming(front, pvF, recF);
        settle();
        told.clear();

        at(1_000);   // 最小化：主界面退到后台（前台服务是主界面在前时起的），只剩后视镜要环视
        host.mainForeground = false;
        host.startedWhileVisible = true;
        drop(Holder.PREVIEW);
        settle();
        assertEquals(1, lines("holding [left, back]").size());
        long expiry = 1_000 + ChannelRules.HOLD_MS;
        at(expiry - 1);
        assertTrue(surround.actions.isEmpty() && rear.actions.isEmpty() && front.actions.isEmpty());
        at(expiry);
        assertEquals("保持到点：只关环视", Arrays.asList("close surround-first"), surround.actions);
        assertTrue(rear.actions.isEmpty() && front.actions.isEmpty());
        at(expiry + 150);
        closed(surround);
        settle();
        assertEquals("停了录：录像不再留着它，随相机关了", Arrays.asList("RECORD CLOSED"), surround.drops);
        assertEquals(Arrays.asList("close hold-over"), rear.actions);
        assertEquals(Arrays.asList("close hold-over"), front.actions);
        at(expiry + 250);
        closed(rear);
        closed(front);
        settle();
        assertEquals("环视开回来给后视镜", Arrays.asList("close surround-first", "open [MIRROR]"), surround.actions);
        at(expiry + 450);
        opened(surround);
        frame(surround);
        settle();
        assertEquals(1, lines("surroundFirst FRAMED gap=450 surround=150 cabins=[left, back]/HOLD_OVER/100").size());
        assertEquals(Arrays.asList("gone front CLOSED SURROUND_FIRST", "gone left CLOSED HOLD_OVER",
                "gone back CLOSED HOLD_OVER"), told("gone"));
        assertEquals(Arrays.asList("streaming front"), told("streaming"));
        assertTrue(lines("coupling").isEmpty());
        assertTrue(lines("stepEnded RESCUE").isEmpty());
        assertEquals("登记表没变：后视镜一直登记着", EnumSet.of(Holder.MIRROR), host.holders);
        assertEquals(0, view(s.snapshot(), SUR).attempts);
    }

    /**
     * 只在保持的环视（后视镜晚 10 秒收起）而座舱先到点：先单独关它，它的保持跟着结束、不再开回来 —— 那一趟写「没人要了，不再开回来」。
     * 关座舱前先关环视的时候退出：环视关完、座舱照退出关完，那一趟写「不再开回来」，调度停下。
     */
    @Test
    public void aSurroundFirstCloseThatIsNotOpenedAgainSaysWhy() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        streaming(surround, pvS, mirror);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        drop(Holder.PREVIEW);
        settle();
        at(10_000);
        drop(Holder.MIRROR);
        settle();
        at(ChannelRules.HOLD_MS);
        assertEquals(Arrays.asList("close surround-first"), surround.actions);
        at(ChannelRules.HOLD_MS + 100);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("close hold-over"), rear.actions);
        at(ChannelRules.HOLD_MS + 200);
        closed(rear);
        closed(front);
        settle();
        assertEquals("保持要开着：不再开回来", Arrays.asList("close surround-first"), surround.actions);
        assertEquals(Arrays.asList("surroundFirst UNWANTED gap=-1 surround=100 cabins=[left, back]/HOLD_OVER/100 open=-1"),
                lines("surroundFirst"));

        // 关座舱前先关环视的那一步还在关，人退出了：手上那一步做完、座舱照退出关完，那一趟写「不再开回来」，调度停下
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        streaming(surround, pvS, mirror);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        surround.actions.clear();
        rear.actions.clear();
        front.actions.clear();
        long t = ChannelRules.HOLD_MS + 1_000;
        at(t);
        drop(Holder.PREVIEW);
        settle();
        at(t + ChannelRules.HOLD_MS);
        assertEquals(Arrays.asList("close surround-first"), surround.actions);
        exitApp();
        settle();
        assertTrue("手上那一步做完再关", rear.actions.isEmpty() && front.actions.isEmpty());
        at(t + ChannelRules.HOLD_MS + 100);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("close exit"), rear.actions);
        assertEquals(Arrays.asList("close exit"), front.actions);
        at(t + ChannelRules.HOLD_MS + 200);
        closed(rear);
        closed(front);
        settle();
        assertEquals(1, lines("surroundFirst WINDING gap=-1 surround=100 cabins=[left, back]/EXIT/100 open=-1").size());
        assertEquals(2, lines("surroundFirst").size());
        assertTrue(pipe.gone);
    }

    /**
     * 车机拿走座舱（被断开）、环视开着（2.11.5 第 D2、D3 项）：丢了之后那一次关（5.4 秒）记一条统计（关，丢了之后），
     * 关完那一刻量环视一行。环视、前座舱一步都不动。
     */
    @Test
    public void aCabinTakenBesideTheSurroundCountsItsCloseAndMeasuresTheSurround() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        settle();
        surround.lastResultAt = T0 + 5_000;
        at(5_409);
        rear.closedNow();
        p.onLossClosed("1", 5_409);
        settle();
        assertEquals(Arrays.asList("recorded left CLOSE CLOSED ms=5409 kind=LOSS surround=STREAMING"), lines("recorded"));
        assertEquals(CameraTaken.Reason.Source.DISCONNECTED, records.get(0).reason.source);
        assertEquals(1, lines("coupling END LOSS [left] DISCONNECTED front gap=0 same last=-409/5000").size());
        assertTrue(surround.actions.isEmpty() && front.actions.isEmpty());
    }

    /**
     * 环视自己丢了：那一次关照样记一条统计（2.11.5 第 D3 项：以前救环视时报错 4 之后那一次关卡 4.5–5.8 秒，统计里看不到），
     * 不量「关座舱时环视」。
     */
    @Test
    public void theSurroundsOwnLossCloseIsCountedButNotCoupled() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR);
        streaming(surround, recS);
        streaming(rear, recR);
        settle();
        surround.lose(now);
        p.onLossStarted("2", DEVICE_ERROR);
        settle();
        at(5_798);
        surround.closedNow();
        p.onLossClosed("2", 5_798);
        settle();
        assertEquals(Arrays.asList("recorded front CLOSE CLOSED ms=5798 kind=LOSS"), lines("recorded"));
        assertTrue(lines("coupling").isEmpty());
    }

    /**
     * 救一路那一行之后接着量别的几路，量到下一步开始（2.11.5 第 D2 项）：救完以后环视断了，救那一行看不见，接着量的那一行看得见。
     */
    @Test
    public void aRescueKeepsMeasuringTheOtherLanesUntilTheNextStepStarts() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR);
        streaming(surround, recS);
        streaming(rear, recR);
        settle();
        rear.progressAgeMs = CameraLiveness.STUCK_MS;
        s.wake();
        settle();
        assertEquals(1, rear.actions.size());
        assertTrue(rear.actions.get(0), rear.actions.get(0).startsWith("rebuild"));
        at(300);
        configured(rear, recR);
        frame(rear);
        settle();
        assertEquals(1, lines("stepEnded RESCUE left").size());
        assertTrue(lines("rescueAftermath").isEmpty());

        at(5_000);
        surround.gapMs = 2_000;
        pipe.plan(FRONT);   // 前座舱也要录了：下一步是开它
        declare(front, recF);
        s.onNeedsChanged();
        settle();
        assertEquals(Arrays.asList("open [RECORD]"), front.actions);
        assertEquals(Arrays.asList("rescueAftermath left step=300 measured=5000 next=OPEN back front gap=2000 same"),
                lines("rescueAftermath"));
    }

    /**
     * 环视自己开的那一步记下这一台是车机重新初始化后第几台（2.11.5 第 D1 项：相机服务交给我们设备、SingleCamera 开好那一下数一台）；
     * 座舱的那一步记着那时开着的环视是第几台。
     */
    @Test
    public void anOpenRecordsWhichDeviceSinceTheHeadUnitReinitialisedTheSurround() {
        host.cameraHistory.reset("2", CameraHistory.Reset.TAKEN, 1_000L, 1_000L);
        claim(Holder.PREVIEW);
        declare(surround, pvS);
        declare(rear, pvR);
        declare(front, pvF);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), surround.actions);
        at(100);
        host.cameraHistory.opened("2", 2_000L);   // SingleCamera 开好那一下（StepLog.deviceOpened）
        opened(surround);
        frame(surround);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), rear.actions);
        at(200);
        opened(rear);
        frame(rear);
        settle();
        assertEquals(Arrays.asList(
                "recorded front OPEN FRAMED ms=100 lineage=1/TAKEN",
                "recorded left OPEN FRAMED ms=100 surround=STREAMING lineage=1/TAKEN"), lines("recorded"));
    }

    // ================================================================= 「输出被丢」派给录像那一侧

    /** 改输出摘掉的录像输出：旧会话没了、新会话配好之后才告诉（录制器放早了，相机还往它那里送帧）。 */
    @Test
    public void aRecordOutputRemovedByAnApplyIsDroppedOnceTheNewSessionIsUp() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS, recS);   // 停录后留着的录像输出（录制器停放着，还声明着）
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        assertTrue("不要了的活输出留着，不为它改", surround.actions.isEmpty());

        // 主界面换了一块画布：要加新的预览，多余的（旧预览、留着的录像）顺带摘
        Out pvS2 = new Out(Kind.PREVIEW, "preview-2b");
        surround.declared.remove(pvS);
        surround.declared.add(pvS2);
        s.wake();
        settle();
        assertEquals(Arrays.asList("apply [PREVIEW]"), surround.actions);
        assertTrue("新会话还没配好：先不告诉", surround.drops.isEmpty());
        at(100);
        surround.busySince = now;
        surround.session = SingleCamera.SessionOutputs.NONE;   // 旧会话关掉了，新的在配
        s.wake();
        settle();
        assertTrue(surround.drops.isEmpty());
        at(400);
        configured(surround, pvS2);
        frame(surround);
        settle();
        assertEquals(Arrays.asList("RECORD REMOVED"), surround.drops);
    }

    /** 关相机带走的录像输出：关完时告诉「随相机关了」。 */
    @Test
    public void aRecordOutputCarriedIntoACloseIsDroppedAsClosed() {
        host.foregroundService = false;
        streaming(surround, recS);
        settle();
        assertEquals("没人要、没有前台服务：不保持，环视单独关", Arrays.asList("close unwanted"), surround.actions);
        assertTrue(surround.drops.isEmpty());
        at(200);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("RECORD CLOSED"), surround.drops);
    }

    // ================================================================= 交给调度的画布（大纲 §7.6、§7.7）

    /**
     * 主界面被划掉，留下的预览画布交给调度：还挂在会话里就不放（留着的活输出，照「减输出能拖就拖」不为它改会话）；
     * 下一次本来要动（这里是开录加录像）顺带摘掉，新会话配好之后才放 —— 早放了它就成了死输出，整条重复请求停下。
     */
    @Test
    public void aHandedOverCanvasIsReleasedOnlyOnceALaterApplyLeftItOut() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        List<String> released = new ArrayList<>();
        drop(Holder.PREVIEW);
        surround.declared.remove(pvS);
        p.handOver(SUR, pvS, () -> released.add("pvS"));
        settle();
        assertTrue("还挂在会话里：不放", released.isEmpty());
        assertTrue("留着的活输出不为它改会话", surround.actions.isEmpty());

        at(1_000);
        pipe.plan(SUR);
        declare(surround, recS);
        claim(Holder.RECORDING);
        settle();
        assertEquals(Arrays.asList("apply [RECORD]"), surround.actions);
        assertTrue("新会话还没配好：不放", released.isEmpty());
        at(1_100);
        surround.session = SingleCamera.SessionOutputs.NONE;   // 旧会话关掉了，新的在配
        s.wake();
        settle();
        assertTrue(released.isEmpty());
        at(1_400);
        configured(surround, recS);
        frame(surround);
        settle();
        assertEquals(Arrays.asList("pvS"), released);
        at(5_000);
        assertEquals("只放一次", Arrays.asList("pvS"), released);
    }

    /** 留下的画布随相机关了：关完才放。 */
    @Test
    public void aHandedOverCanvasCarriedIntoACloseIsReleasedWhenClosed() {
        host.foregroundService = false;
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        List<String> released = new ArrayList<>();
        drop(Holder.PREVIEW);
        surround.declared.remove(pvS);
        p.handOver(SUR, pvS, () -> released.add("pvS"));
        settle();
        assertEquals("没人要、没有前台服务：不保持，关", Arrays.asList("close unwanted"), surround.actions);
        assertTrue("还在关：不放", released.isEmpty());
        at(200);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("pvS"), released);
    }

    /** 交过来时这一路没有会话带着它（相机关着）：下一轮就放；不认识的路也放，不漏。 */
    @Test
    public void aHandedOverCanvasNoSessionCarriesIsReleasedAtOnce() {
        settle();
        List<String> released = new ArrayList<>();
        p.handOver(SUR, pvS, () -> released.add("pvS"));
        p.handOver("nowhere", new Out(Kind.PREVIEW, "preview-x"), () -> released.add("x"));
        settle();
        assertEquals(Arrays.asList("pvS", "x"), released);
    }

    /**
     * 新界面接回同一块画布（主界面重建）：拿回去之后不归调度放；再声明就是同一个输出 —— 通道零变动，
     * 之后相机关了也不放（归接回的那个视图）。
     */
    @Test
    public void aReclaimedCanvasIsNeverReleasedAndChangesNothing() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        List<String> released = new ArrayList<>();
        drop(Holder.PREVIEW);
        surround.declared.remove(pvS);
        p.handOver(SUR, pvS, () -> released.add("pvS"));
        assertTrue(p.reclaim(SUR, pvS));
        assertFalse("拿回过了", p.reclaim(SUR, pvS));
        surround.declared.add(pvS);
        claim(Holder.PREVIEW);
        settle();
        assertTrue("通道零变动", surround.actions.isEmpty() && rear.actions.isEmpty() && front.actions.isEmpty());

        host.foregroundService = false;
        drop(Holder.PREVIEW);
        settle();
        at(200);
        closed(surround);
        settle();
        assertTrue(surround.actions.contains("close unwanted"));
        assertTrue("接回的不归调度放", released.isEmpty());
    }

    /** 退出：按次序关，留下的画布随它那一路关完就放（不等别的路），调度停下之后也不再放第二次。 */
    @Test
    public void exitReleasesAHandedOverCanvasOnceItsLaneIsClosed() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        List<String> released = new ArrayList<>();
        drop(Holder.PREVIEW);
        surround.declared.remove(pvS);
        p.handOver(SUR, pvS, () -> released.add("pvS"));
        exitApp();
        settle();
        assertEquals(Arrays.asList("close exit"), surround.actions);
        assertTrue(released.isEmpty());
        at(300);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("pvS"), released);
        assertEquals(Arrays.asList("close exit"), rear.actions);
        at(400);
        closed(rear);
        closed(front);
        settle();
        assertTrue(pipe.gone);
        assertEquals("只放一次", Arrays.asList("pvS"), released);
    }

    /**
     * 大纲 §7.7（项目所有者 2026-10-11「按建议，留住」）：关超级镜子时画布也交给调度。环视在录、只有后视镜和录像要它：
     * 后视镜放开登记、撤回输出、画布交过来 —— 环视一步不动（留着的活输出不为它改会话、不当死输出马上摘，录像不断）；
     * 新开的窗口没接回、自己建了一块，下一次改输出加上新的、顺带摘掉旧的，新会话配好之后才放旧的那一块。
     */
    @Test
    public void aClosedMirrorsCanvasLeavesTheRecordingSurroundAloneUntilALaterApply() {
        Out mirror = new Out(Kind.MIRROR, "mirror-a");
        pipe.plan(SUR);
        claim(Holder.RECORDING);
        claim(Holder.MIRROR);
        streaming(surround, mirror, recS);
        settle();
        assertTrue(surround.actions.isEmpty());
        List<String> released = new ArrayList<>();
        drop(Holder.MIRROR);
        surround.declared.remove(mirror);
        p.handOver(SUR, mirror, () -> released.add("mirror-a"));
        settle();
        at(10_000);
        assertTrue("环视一步不动：" + surround.actions, surround.actions.isEmpty());
        assertTrue("还挂在会话里：不放", released.isEmpty());
        assertTrue("录像输出没被摘", surround.drops.isEmpty());

        Out next = new Out(Kind.MIRROR, "mirror-b");
        declare(surround, next);
        claim(Holder.MIRROR);
        settle();
        assertEquals(Arrays.asList("apply [MIRROR, RECORD]"), surround.actions);
        assertTrue("新会话还没配好：不放", released.isEmpty());
        at(10_300);
        configured(surround, next, recS);
        frame(surround);
        settle();
        assertEquals(Arrays.asList("mirror-a"), released);
        at(15_000);
        assertEquals("只放一次", Arrays.asList("mirror-a"), released);
        assertTrue("座舱不受影响", rear.actions.isEmpty() && front.actions.isEmpty());
    }

    /**
     * 相机层自己丢的输出（会话配不上、消费者被收走）：录像、盲区画面、超级镜子当场告诉声明它的那一侧（盲区画面、超级镜子收到
     * 「配不上」撤回，调度就不会一直想把它加回去）；预览不告诉。
     */
    @Test
    public void anOutputTheCameraDroppedIsReportedAtOnce() {
        settle();
        p.onOutputDropped("2", recS, SingleCamera.Drop.CONFIG_FAILED);
        p.onOutputDropped("2", new Out(Kind.SIDE, "side-2"), SingleCamera.Drop.CONFIG_FAILED);
        p.onOutputDropped("2", new Out(Kind.HUD, "hud-2"), SingleCamera.Drop.CONFIG_FAILED);
        p.onOutputDropped("2", new Out(Kind.MIRROR, "mirror-2"), SingleCamera.Drop.CONFIG_FAILED);
        p.onOutputDropped("2", pvS, SingleCamera.Drop.RELEASED);
        assertEquals(Arrays.asList("RECORD CONFIG_FAILED", "SIDE CONFIG_FAILED", "HUD CONFIG_FAILED",
                "MIRROR CONFIG_FAILED"), surround.drops);
    }

    // ================================================================= 告诉录像那一侧（大纲 §2 阶段 5）

    /**
     * 一份会话出了画面（配好、出了第一帧）才告诉录像：同一份会话只说一次，换了一份（改输出加了后视镜）再说一次 ——
     * 录像那一侧每次都看会话带没带着它的录像输出，带着就加入。配好了还没出第一帧不说。
     */
    @Test
    public void eachSessionThatStreamsIsToldToRecordingOnce() {
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        declare(surround, recS);
        settle();
        assertEquals("关着的带着录像输出开", Arrays.asList("open [RECORD]"), surround.actions);
        at(300);
        opened(surround);
        settle();
        assertTrue("配好了还没出第一帧：不说", told("streaming").isEmpty());
        at(500);
        frame(surround);
        settle();
        assertEquals(Arrays.asList("streaming front"), told("streaming"));
        at(ChannelRules.TICK_MS * 3);
        assertEquals("同一份会话只说一次", 1, told("streaming").size());

        // 后视镜打开了：改输出加上它，新的一份会话出了画面再说一次
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        declare(surround, mirror);
        claim(Holder.MIRROR);
        settle();
        assertEquals(2, surround.actions.size());
        assertTrue(surround.actions.get(1), surround.actions.get(1).startsWith("apply"));
        at(ChannelRules.TICK_MS * 3 + 300);
        configured(surround, mirror, recS);
        frame(surround);
        settle();
        assertEquals("新的一份会话出了画面：再说一次", 2, told("streaming").size());
    }

    /**
     * 丢了：在阻塞的关之前（丢的那一轮）就告诉录像「走了」，带着丢的那一下；判完被拿着再说一声样子变了。
     * 关完不再说一次走了。
     */
    @Test
    public void aLossIsToldAsGoneBeforeTheBlockingCloseEnds() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR);
        streaming(surround, recS);
        streaming(rear, recR);
        settle();
        assertEquals(Arrays.asList("streaming front", "streaming left"), told("streaming"));
        told.clear();
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        settle();
        assertEquals(Arrays.asList("gone left LOST DISCONNECTED"), told("gone"));
        assertEquals(Arrays.asList("condition left RESCUING"), told("condition left"));
        at(5_400);
        rear.closedNow();
        p.onLossClosed("1", 5_400);
        settle();
        at(5_700);
        assertEquals(ChannelRules.Condition.TAKEN, p.condition(REAR));
        assertEquals(Arrays.asList("condition left RESCUING", "condition left TAKEN"), told("condition left"));
        assertEquals("关完不再说一次走了", 1, told("gone").size());
        assertTrue("别的路不受影响", told("gone front").isEmpty() && told("condition front").isEmpty());
    }

    /** 我们关的（没人要了、没有前台服务）：开始关的那一轮告诉录像「走了」。 */
    @Test
    public void aCloseIsToldAsGone() {
        host.foregroundService = false;
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        streaming(surround, recS);
        settle();
        told.clear();
        drop(Holder.RECORDING);
        settle();
        assertEquals(Arrays.asList("close unwanted"), surround.actions);
        assertEquals("带着关的原因", Arrays.asList("gone front CLOSED UNWANTED"), told("gone"));
    }

    /**
     * 停放着的录制器所在的那一路丢了：关完时它「随相机关了」，录像那一侧放掉录制器 —— 重开时不再带它，不派的话它永远回不到
     * 会话里、永远等不到被丢（审查 2026-10-11）。在录时丢了照旧不派：相机回来时同一个录制器直接加入；之后停了录，
     * 那一路还关着，停录那一轮就派（设计 v3.1 C13：以前丢的那一下定了就不再看，这个录制器一直留着）。
     */
    @Test
    public void aParkedRecorderOnALostLaneIsDroppedWhenTheCloseEnds() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR);
        streaming(surround, recS);
        streaming(rear, recR);
        settle();
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        settle();
        at(5_000);
        rear.closedNow();
        p.onLossClosed("1", 5_000);
        settle();
        assertTrue("在录：同一个录制器等相机回来直接加入", rear.drops.isEmpty());

        drop(Holder.RECORDING);   // 停录：环视的录制器停放，录像输出留在会话里
        settle();
        assertEquals("关着的那一路：停录那一轮就派", Arrays.asList("RECORD CLOSED"), rear.drops);
        assertEquals(ChannelRules.OutState.KEPT, surround.states.get(recS).state);
        at(6_000);
        surround.lose(now);
        p.onLossStarted("2", DISCONNECTED);
        settle();
        assertTrue("关完之前不派", surround.drops.isEmpty());
        at(8_000);
        surround.closedNow();
        p.onLossClosed("2", 2_000);
        settle();
        assertEquals(Arrays.asList("RECORD CLOSED"), surround.drops);
    }

    /**
     * 开着却卡住了（8 秒没进展），调度在救：告诉录像「走了」（那一路收尾退出）；救好了新会话带着同一个录像输出出了画面，
     * 再告诉一次出画面（那一路再加入）。救的那一步照旧带着录像输出，不算被丢。
     */
    @Test
    public void aRescueOfAStuckLaneIsToldAsGoneAndItsNextSessionAsStreaming() {
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        streaming(surround, recS);
        settle();
        told.clear();
        surround.progressAgeMs = CameraLiveness.STUCK_MS;
        s.wake();
        settle();
        assertEquals(1, surround.actions.size());
        assertTrue(surround.actions.get(0), surround.actions.get(0).startsWith("rebuild"));
        assertEquals(Arrays.asList("gone front RESCUED"), told("gone"));
        at(300);
        configured(surround, recS);
        frame(surround);
        settle();
        assertEquals(Arrays.asList("streaming front"), told("streaming"));
        assertTrue("救的那一步照旧带着录像输出", surround.drops.isEmpty());
    }

    // ================================================================= 熄屏放开座舱（项目所有者 2026-10-12 D2）

    /**
     * 录着三路熄屏：两路座舱一起关（「dark-release」），录像那一侧听说它们走了、带着关的原因（退出录像那一行写「熄屏：座舱放开」）；
     * 录像还留着它们的录制器 —— 不派「随相机关了」，停放过夜。2.11.6 起（项目所有者 2026-10-15）座舱关之前环视先单独关（关座舱前先关环视），
     * 座舱关完它带着停放的录制器开回来、接着录，环视不在座舱旁边，关座舱时环视那几行不写；之后熄屏这段时间里一步不动。
     * 亮屏：后座舱 → 前座舱一路一路带着各自的录像输出回来，环视不再动。
     */
    @Test
    public void theDarkReleasesBothCabinsKeepsTheirRecordersAndBringsTheSurroundBack() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        assertFalse("亮着：没放开", p.releasedByDark(REAR));
        told.clear();

        at(1_000);
        host.sentryArmed();   // 哨兵模式布防：熄屏时环视留着（项目所有者 2026-10-27）
        host.screenOn = false;
        s.wake();
        settle();
        assertEquals("先单独关环视", Arrays.asList("close surround-first"), surround.actions);
        assertTrue("座舱等环视关完", rear.actions.isEmpty() && front.actions.isEmpty());
        assertTrue(p.releasedByDark(REAR) && p.releasedByDark(FRONT));
        assertFalse(p.releasedByDark(SUR));
        at(1_100);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("close dark-release"), rear.actions);
        assertEquals(Arrays.asList("close dark-release"), front.actions);
        assertEquals(1, lines("closing [left, back] DARK_RELEASE").size());
        assertEquals(Arrays.asList("gone front CLOSED SURROUND_FIRST", "gone left CLOSED DARK_RELEASE",
                "gone back CLOSED DARK_RELEASE"), told("gone"));

        at(1_200);
        closed(rear);
        closed(front);
        settle();
        assertTrue("录像留着它们：录制器停放，不派",
                rear.drops.isEmpty() && front.drops.isEmpty() && surround.drops.isEmpty());
        assertEquals(Arrays.asList("closeRound front | left, back"), lines("closeRound"));
        assertEquals("座舱关完环视开回来接着录", Arrays.asList("close surround-first", "open [RECORD]"), surround.actions);
        at(1_400);
        opened(surround);
        frame(surround);
        settle();
        assertEquals(1, lines("surroundFirst FRAMED gap=400").size());
        at(600_000);
        assertTrue("熄屏这段时间里什么都不动", surround.actions.size() == 2
                && rear.actions.size() == 1 && front.actions.size() == 1);
        assertTrue(rear.drops.isEmpty() && front.drops.isEmpty() && surround.drops.isEmpty());
        assertTrue("环视不在座舱旁边：不写关座舱时环视那几行",
                lines("coupling").isEmpty() && lines("closeAftermath").isEmpty());

        host.screenOn = true;
        s.wake();
        settle();
        assertEquals("亮屏：后座舱先回来，带着停放的录制器", Arrays.asList("close dark-release", "open [RECORD]"),
                rear.actions);
        assertEquals("一路一路来", Arrays.asList("close dark-release"), front.actions);
        assertFalse(p.releasedByDark(REAR));
        at(600_300);
        opened(rear);
        frame(rear);
        settle();
        assertEquals(Arrays.asList("close dark-release", "open [RECORD]"), front.actions);
        assertEquals("环视不再动", Arrays.asList("close surround-first", "open [RECORD]"), surround.actions);
    }

    /**
     * 熄屏放开的座舱关着的时候停了录（熄屏持续录制关着，10 秒后停）：停录那一轮就告诉它们「随相机关了」，录像那一侧放掉
     * 停放着的录制器（设计 v3.1 C13），只说一次；环视熄屏不保持，跟着关（原因是熄屏，不是座舱放开）。
     * 座舱那一轮关完 30 秒再量环视的那一行只量到我们关环视那一刻、写明是我们关的 —— 不把之后的 20 秒算成环视断帧、
     * 会话被重建（审查 rev3 B2）。座舱在出画面的环视旁边关：这里此刻用不了相机（主界面在后台、前台服务是后台起的；能用相机时
     * 2.11.6 起环视先关，{@link #theDarkReleasesBothCabinsKeepsTheirRecordersAndBringsTheSurroundBack}），关相机那一行带上环视这段时间里怎样。
     */
    @Test
    public void stoppingTheRecordingWhileTheCabinsAreDarkDropsTheirParkedRecorders() {
        host.mainForeground = false;
        host.startedWhileVisible = false;
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        host.sentryArmed();   // 哨兵模式布防：熄屏时环视留着（项目所有者 2026-10-27）
        host.screenOn = false;
        s.wake();
        settle();
        assertEquals("用不了相机：座舱在环视旁边关", Arrays.asList("close dark-release"), rear.actions);
        assertTrue(surround.actions.isEmpty());
        at(100);
        closed(rear);
        closed(front);
        settle();
        assertTrue(rear.drops.isEmpty() && front.drops.isEmpty());
        assertEquals(Arrays.asList("closeRound null | left, back | others front gap=0 same"), lines("closeRound"));
        assertEquals(1, lines("coupling END PLANNED [left, back] DARK_RELEASE front gap=0 same").size());

        at(10_000);
        drop(Holder.RECORDING);
        settle();
        assertEquals(Arrays.asList("RECORD CLOSED"), rear.drops);
        assertEquals(Arrays.asList("RECORD CLOSED"), front.drops);
        assertEquals(Arrays.asList("close screen-off"), surround.actions);
        assertTrue("环视关完之前不派", surround.drops.isEmpty());
        at(10_100);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("RECORD CLOSED"), surround.drops);
        at(20_000);
        assertEquals("只说一次", Arrays.asList("RECORD CLOSED"), rear.drops);
        assertEquals(Arrays.asList("RECORD CLOSED"), surround.drops);
        assertTrue(lines("closeAftermath").isEmpty());
        at(30_000);
        assertTrue("醒的那一轮照旧是座舱那一轮开始关 + 30 秒", pipe.roundAt.contains(30_000L));
        assertTrue("关完（100）还不到 30 秒", lines("coupling AFTER_30S").isEmpty());
        at(100 + ChannelScheduler.AFTERMATH_MS + ChannelRules.TICK_MS);
        assertTrue(lines("closeAftermath").isEmpty());
        assertEquals("量到我们关环视那一刻（座舱那一轮开始关起 10 秒），会话照关之前的算", 1,
                lines("coupling AFTER_30S PLANNED [left, back] DARK_RELEASE front gap=0 same ours=CLOSE@10000").size());
        assertTrue(lines("coupling AFTER_30S").get(0).endsWith(" | others front gap=0 same ours=CLOSE@10000"));
    }

    /**
     * 别的程序拿走一路相机之后再量环视（设计 v3 §6 L5，{@link ChannelScheduler#watchAfter}）：同一件只等一次；
     * 这 30 秒里我们给环视加了录像输出（重配会话，会话代数变了），只量到加输出那一刻、写明是我们改的，不算会话被重建（审查 rev3 B2）。
     * 量完这一件，同一路相机再被拿走又能排上。
     */
    @Test
    public void aWatchAfterATakeStopsMeasuringAtOurOwnStepOnTheLane() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        settle();
        List<String> reports = new ArrayList<>();
        Consumer<List<ChannelScheduler.Other>> report = others -> reports.add(othersText(others));
        assertTrue(p.watchAfter("take 1", SUR, now, report));
        assertFalse("同一件只等一次", p.watchAfter("take 1", SUR, now, report));
        assertFalse("这一份管线里没有的一路", p.watchAfter("take 9", "nowhere", now, report));

        at(5_000);
        pipe.plan(SUR);
        declare(surround, recS);
        claim(Holder.RECORDING);
        settle();
        assertEquals(Arrays.asList("apply [MIRROR, RECORD]"), surround.actions);
        at(5_300);
        configured(surround, mirror, recS);
        frame(surround);
        settle();

        at(ChannelScheduler.AFTERMATH_MS - 1);
        assertTrue(reports.isEmpty());
        at(ChannelScheduler.AFTERMATH_MS);
        assertEquals(Arrays.asList("front gap=0 same ours=APPLY@5000"), reports);
        assertTrue("量完了，同一路再被拿走又能排上", p.watchAfter("take 1", SUR, now, report));
    }

    /**
     * 熄屏放开、亮屏回来，录像那一侧都听说一声（样子没变也说）：编码录制亮屏时在这一声里补建熄屏时没建的录制器
     * （设计 v3.1 C4）。车机熄屏后拿走的后座舱一直是「被拿着」，只有这一声告诉录像它被不被熄屏放开。
     */
    @Test
    public void recordingHearsWhenTheDarkReleasesALaneAndWhenItGivesItBack() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR);
        streaming(surround, recS);
        declare(rear, recR);
        host.absent("1");
        settle();
        assertEquals(ChannelRules.Condition.TAKEN, p.condition(REAR));
        told.clear();

        host.screenOn = false;
        s.wake();
        settle();
        assertEquals(Arrays.asList("condition left TAKEN"), told("condition left"));
        assertTrue(p.releasedByDark(REAR));
        at(5_000);
        assertEquals("没变不再说", 1, told("condition left").size());

        host.screenOn = true;
        s.wake();
        settle();
        assertEquals(Arrays.asList("condition left TAKEN", "condition left TAKEN"), told("condition left"));
        assertFalse(p.releasedByDark(REAR));
    }

    /**
     * 熄屏拍照（设计 v3.1 C4；项目所有者 2026-10-27）：通道算黑、没在开车时不为拍照开相机 —— 座舱熄屏放开、关着的环视也不开，
     * 三路都不拍、不算在该拍的里面（拍照那一侧当场收尾）。
     */
    @Test
    public void aDarkPhotoOpensNoCamera() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        host.screenOn = false;
        s.wake();
        settle();
        assertEquals("预览熄屏不算：环视单独先关", Arrays.asList("close screen-off"), surround.actions);
        at(100);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("close dark-release"), rear.actions);
        at(200);
        closed(rear);
        closed(front);
        settle();
        assertTrue(p.excludedFromPhoto(REAR) && p.excludedFromPhoto(FRONT));
        assertTrue("关着的环视：不为拍照开", p.excludedFromPhoto(SUR));
        assertTrue(p.parkedDark());

        claim(Holder.PHOTO);
        settle();
        at(5_000);
        assertEquals("拍照不开相机", Arrays.asList("close screen-off"), surround.actions);
        assertEquals(Arrays.asList("close dark-release"), rear.actions);
        assertEquals(Arrays.asList("close dark-release"), front.actions);
        assertTrue(p.excludedFromPhoto(SUR));
    }

    // ================================================================= 黑满 30 秒再放（项目所有者 2026-10-13）

    /**
     * 屏幕黑了不到 30 秒又亮（10-13 20:10:11 车门开关的那 4.7 秒，哨兵模式开着：那时关了后座舱、亮屏再开，重开 15 秒没画面，
     * 救援那一次关又卡了 6.2 秒）：相机一步不动 —— 不关、不改输出，录像那一侧什么都没听说，座舱不算放开。黑了 29 秒才亮也一样。
     */
    @Test
    public void aDarkShorterThanThirtySecondsTouchesNoCamera() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        told.clear();
        int journalLines = journal.size();

        at(1_000);
        host.goDark();
        s.wake();
        settle();
        assertFalse("黑了还不到 30 秒：不算放开", p.releasedByDark(REAR));
        at(5_700);
        host.goLit();
        s.wake();
        settle();

        at(100_000);
        host.goDark();
        s.wake();
        settle();
        at(100_000 + ChannelRules.HOLD_MS - 1_000);
        assertFalse(p.releasedByDark(REAR) || p.releasedByDark(FRONT));
        host.goLit();
        s.wake();
        settle();
        at(300_000);

        assertTrue("一步不动：" + surround.actions + rear.actions + front.actions,
                surround.actions.isEmpty() && rear.actions.isEmpty() && front.actions.isEmpty());
        assertTrue("录像那一侧什么都没听说：" + told, told.isEmpty());
        assertTrue("录制器不停放、不派「随相机关了」", rear.drops.isEmpty() && front.drops.isEmpty());
        assertTrue(lines("closing").isEmpty() && lines("holding").isEmpty());
        assertEquals("黑匣子里通道这一侧一行都没有", journalLines, journal.size());
    }

    /**
     * 黑满 30 秒的那一刻（不是下一个两秒）放开两路座舱（dark-release，「熄屏：座舱放开」）：2.11.6 起那一刻先单独关环视（关座舱前先关环视），
     * 环视关完两路座舱一起关，环视开回来照录。黑下来的那一下比调度那一轮早半秒（监听者先做完才轮到调度）：照黑下来的那一刻算。
     */
    @Test
    public void theCabinsLeaveTheMomentTheScreenHasBeenDarkForThirtySeconds() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        told.clear();

        at(1_000);
        host.sentryArmed();   // 哨兵模式布防：熄屏时环视留着（项目所有者 2026-10-27）
        host.goDark();
        host.darkAt = T0 + 500;
        s.wake();
        settle();
        at(500 + ChannelRules.HOLD_MS - 1);
        assertTrue("差 1 毫秒：不动", surround.actions.isEmpty() && rear.actions.isEmpty() && front.actions.isEmpty());
        assertFalse(p.releasedByDark(REAR));
        at(500 + ChannelRules.HOLD_MS);
        assertEquals("那一刻：先单独关环视", Arrays.asList("close surround-first"), surround.actions);
        assertTrue(rear.actions.isEmpty() && front.actions.isEmpty());
        assertTrue(p.releasedByDark(REAR) && p.releasedByDark(FRONT));
        assertFalse(p.releasedByDark(SUR));
        at(500 + ChannelRules.HOLD_MS + 120);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("close dark-release"), rear.actions);
        assertEquals(Arrays.asList("close dark-release"), front.actions);
        assertEquals(1, lines("closing [left, back] DARK_RELEASE").size());
        assertEquals(Arrays.asList("gone front CLOSED SURROUND_FIRST", "gone left CLOSED DARK_RELEASE",
                "gone back CLOSED DARK_RELEASE"), told("gone"));
        at(500 + ChannelRules.HOLD_MS + 220);
        closed(rear);
        closed(front);
        settle();
        assertEquals("环视开回来照录", Arrays.asList("close surround-first", "open [RECORD]"), surround.actions);
    }

    /**
     * 同一个那一刻，哨兵模式关着（项目所有者 2026-10-27）：环视没留着，和座舱同一轮放开 —— 环视先单独关（熄屏），关完座舱一起关
     * （熄屏：座舱放开），三路都不开回来、录制器都停放着；录像那一侧听说三路都走了（环视「熄屏」）。黑匣子一行「环视放开（哨兵关）」。
     * 操作 3 次（以前 5 次：环视关、座舱关、环视开，再到车机要睡关一次）。
     */
    @Test
    public void theCabinsLeaveTheMomentTheScreenHasBeenDarkForThirtySecondsNotKeptReleasesAllThreeWithNoReopen() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        host.sentry = 0;
        settle();
        told.clear();

        at(1_000);
        host.goDark();
        s.wake();
        settle();
        at(1_000 + ChannelRules.HOLD_MS - 1);
        assertTrue(surround.actions.isEmpty() && rear.actions.isEmpty() && front.actions.isEmpty());
        assertFalse(p.releasedByDark(SUR));
        at(1_000 + ChannelRules.HOLD_MS);
        assertEquals("那一刻：环视先单独关，原因是熄屏", Arrays.asList("close screen-off"), surround.actions);
        assertTrue(rear.actions.isEmpty() && front.actions.isEmpty());
        assertTrue(p.releasedByDark(SUR) && p.releasedByDark(REAR) && p.releasedByDark(FRONT));
        assertEquals(Arrays.asList("surroundInDark RELEASED DARK_ONSET sentry=0 records"), lines("surroundInDark"));
        at(1_000 + ChannelRules.HOLD_MS + 120);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("close dark-release"), rear.actions);
        assertEquals(Arrays.asList("close dark-release"), front.actions);
        assertEquals(Arrays.asList("gone front CLOSED SCREEN_OFF", "gone left CLOSED DARK_RELEASE",
                "gone back CLOSED DARK_RELEASE"), told("gone"));
        at(1_000 + ChannelRules.HOLD_MS + 220);
        closed(rear);
        closed(front);
        settle();
        at(600_000);
        assertEquals("不开回来", Arrays.asList("close screen-off"), surround.actions);
        assertEquals(1, rear.actions.size());
        assertEquals(1, front.actions.size());
        assertTrue("录制器都停放着：不派", surround.drops.isEmpty() && rear.drops.isEmpty() && front.drops.isEmpty());
    }

    /**
     * 车机要睡（黑着时安卓熄屏）不等 30 秒：黑了 5 秒就要睡的，当场按次序关（环视先单独关，再关座舱）。
     * 安卓熄屏了而车机不睡的（哨兵模式、开发者唤醒锁拉着）：同样当场放开，环视也放开（下一条）。
     */
    @Test
    public void theHeadUnitSleepingDoesNotWaitThirtySeconds() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();

        at(1_000);
        host.goDark();
        s.wake();
        settle();
        at(6_000);
        assertTrue(surround.actions.isEmpty() && rear.actions.isEmpty());
        host.androidOff = true;
        host.sleeps = true;
        s.onNeedsChanged();
        settle();
        assertEquals("当场按次序关：环视单独先关", Arrays.asList("close head-unit-sleeps"), surround.actions);
        assertTrue(rear.actions.isEmpty());
        at(6_100);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("close head-unit-sleeps"), rear.actions);
        assertEquals(Arrays.asList("close head-unit-sleeps"), front.actions);
    }

    /**
     * 安卓熄屏了、车机没判成要睡（哨兵模式布防、开发者唤醒锁拉着车机）：同样不等 30 秒，座舱当场放开；环视也放开
     * （项目所有者 2026-10-27「车机要睡」：不管哨兵模式、唤醒锁）—— 环视先单独关（熄屏），座舱再关，什么都不开回来。
     * 醒来哨兵模式还布防着不算变成开：相机不开；睡着时被打开的（关 → 布防），醒来读到算。
     */
    @Test
    public void androidGoingOffWhileDarkReleasesEverythingEvenWithSentryArmed() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        host.sentryArmed();
        settle();

        at(1_000);
        host.goDark();
        s.wake();
        settle();
        at(6_000);
        host.androidOff = true;
        s.wake();
        settle();
        assertEquals("当场：环视先单独关", Arrays.asList("close screen-off"), surround.actions);
        assertTrue(rear.actions.isEmpty() && front.actions.isEmpty());
        assertEquals(Arrays.asList("surroundInDark RELEASED ANDROID_OFF sentry=2 records"), lines("surroundInDark"));
        at(6_100);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("close dark-release"), rear.actions);
        assertEquals(Arrays.asList("close dark-release"), front.actions);
        at(6_200);
        closed(rear);
        closed(front);
        settle();
        at(60_000);
        assertEquals("不开回来", Arrays.asList("close screen-off"), surround.actions);

        // 醒了（安卓亮了、屏幕还黑着）：哨兵模式还布防着 —— 不是变成开，相机不开
        host.androidOff = false;
        s.wake();
        at(120_000);
        assertEquals(Arrays.asList("close screen-off"), surround.actions);
        assertTrue(p.releasedByDark(SUR));
    }

    /**
     * 熄屏持续录制关着：录像 10 秒后停（那张表不跟着挪），座舱、环视没人要了，前台服务在 —— 保持到屏幕黑满 30 秒，
     * 那一刻照熄屏的规矩关：环视单独先关（熄屏），关完再关座舱（熄屏：座舱放开）。不早于 30 秒。
     */
    @Test
    public void aRecordingStoppedAtTenSecondsLeavesTheCamerasUntilThirtySeconds() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        host.keep = false;
        settle();

        at(1_000);
        host.goDark();
        s.wake();
        settle();
        at(11_000);
        drop(Holder.RECORDING);
        settle();
        assertEquals("没人要了：保持（到屏幕黑满 30 秒）", 1, lines("holding").size());
        at(1_000 + ChannelRules.HOLD_MS - 1);
        assertTrue(surround.actions.isEmpty() && rear.actions.isEmpty() && front.actions.isEmpty());
        at(1_000 + ChannelRules.HOLD_MS);
        assertEquals(Arrays.asList("close screen-off"), surround.actions);
        assertTrue("座舱等环视先关完", rear.actions.isEmpty());
        assertEquals(Arrays.asList("surroundInDark RELEASED DARK_ONSET sentry=null keepOff"), lines("surroundInDark"));
        at(1_000 + ChannelRules.HOLD_MS + 100);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("close dark-release"), rear.actions);
        assertEquals(Arrays.asList("close dark-release"), front.actions);
    }

    /**
     * 黑了不到 30 秒时才来的开录（等 U 盘的那一次这时等到了、存储停了接回）：关着的座舱不开，只开环视（和 2.11.2 一样 ——
     * 黑屏里用不着它，哨兵模式开着时开后座舱正是 10-12、10-13 两次一帧不出的那一种操作）；录像那一侧读到座舱被熄屏放开，不等它们。
     * 黑满 30 秒时没有座舱可放，什么都不动；亮了座舱照常一路一路开（后座舱 → 前座舱）。
     */
    @Test
    public void aStartInTheFirstThirtySecondsOfDarkLeavesTheClosedCabinsClosed() {
        settle();
        at(1_000);
        host.sentryArmed();   // 哨兵模式布防：熄屏时环视留着（项目所有者 2026-10-27）
        host.goDark();
        s.wake();
        settle();
        assertTrue("关着的座舱黑着不开：算熄屏放开", p.releasedByDark(REAR) && p.releasedByDark(FRONT));
        assertFalse(p.releasedByDark(SUR));

        at(7_000);
        pipe.plan(SUR, REAR, FRONT);
        declare(surround, recS);
        declare(rear, recR);
        declare(front, recF);
        claim(Holder.RECORDING);
        settle();
        assertEquals("只开环视", Arrays.asList("open [RECORD]"), surround.actions);
        at(7_300);
        opened(surround);
        frame(surround);
        settle();
        at(1_000 + ChannelRules.HOLD_MS + 5_000);
        assertTrue("座舱一直没开：" + rear.actions + front.actions, rear.actions.isEmpty() && front.actions.isEmpty());
        assertTrue(lines("closing").isEmpty());
        assertTrue(p.releasedByDark(REAR) && p.releasedByDark(FRONT));

        host.goLit();
        s.wake();
        settle();
        assertEquals("亮了：后座舱先开", Arrays.asList("open [RECORD]"), rear.actions);
        assertTrue("一路一路来", front.actions.isEmpty());
        assertFalse(p.releasedByDark(REAR) || p.releasedByDark(FRONT));
    }

    // ================================================================= 熄屏放开相机（项目所有者 2026-10-27）

    /**
     * 10-14 21:01 那一种（车机睡过、进程只是冻住）：哨兵模式关着，30 秒那一下三路放开；车机要睡、停录；睡着时远程打开哨兵模式，
     * 醒来读到布防算变成开 —— 环视留着，可录像还没回来（查 U 盘要 4.5 秒）不开；录像一登记，环视带着停放的录制器开起来，座舱不动。
     */
    @Test
    public void replay1014_sentryArmedAtWakeOpensTheSurroundWhenRecordingArrives() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        host.sentry = 0;
        settle();
        at(1_000);
        host.goDark();
        s.wake();
        settle();
        long release = 1_000 + ChannelRules.HOLD_MS;
        at(release);
        assertEquals(Arrays.asList("close screen-off"), surround.actions);
        at(release + 100);
        closed(surround);
        settle();
        at(release + 200);
        closed(rear);
        closed(front);
        settle();

        at(60_000);   // 车机要睡：停录
        host.androidOff = true;
        host.sleeps = true;
        s.onNeedsChanged();
        settle();
        drop(Holder.RECORDING);
        settle();

        at(3_600_000);   // 醒了：睡着时哨兵模式被远程打开
        host.sentryArmed();
        host.androidOff = false;
        host.sleeps = false;
        s.onNeedsChanged();
        settle();
        assertEquals("录像还没回来：不开", Arrays.asList("close screen-off"), surround.actions);
        at(3_604_500);
        claim(Holder.RECORDING);
        settle();
        assertEquals("录像一登记：环视开起来录", Arrays.asList("close screen-off", "open [RECORD]"), surround.actions);
        assertEquals(Arrays.asList("close dark-release"), rear.actions);
        assertEquals(Arrays.asList("close dark-release"), front.actions);
        assertEquals(Arrays.asList("surroundInDark RELEASED DARK_ONSET sentry=0 records",
                "surroundInDark KEPT SENTRY_ON sentry=2"), lines("surroundInDark"));
        assertEquals("发给录像协调器：留着，原因是哨兵变成开（醒来读到的也算）", ChannelRules.SurroundInDark.KEPT,
                s.verdict());
        assertEquals(ChannelRules.Event.Cause.SENTRY_ON, s.verdictCause());
    }

    /**
     * 屏幕不知道黑没黑（进程刚起来、安卓刚亮）：谁登记着都不开，黑匣子一行「先不开」（只在变了时写，不写成用不了相机）；
     * 知道了（Android 那一层叫醒调度）照常开。
     */
    @Test
    public void aStartWhileTheScreenIsUnknownWaitsThenOpensOnceKnown() {
        host.known = false;
        claim(Holder.PREVIEW);
        declare(surround, pvS);
        declare(rear, pvR);
        declare(front, pvF);
        settle();
        at(1_000);
        assertTrue(surround.actions.isEmpty() && rear.actions.isEmpty() && front.actions.isEmpty());
        assertEquals(Arrays.asList("waiting SCREEN_UNKNOWN " + SUR), lines("waiting SCREEN_UNKNOWN"));
        assertTrue("不写成用不了相机", lines("deferred").isEmpty());
        assertFalse(s.snapshot().screenKnown);
        host.known = true;
        s.wake();
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), surround.actions);
    }

    /** 环视留不留每变一次一行（通道刚算黑那一下、哨兵关了、变成开、车机要睡），不变不写。 */
    @Test
    public void surroundInDarkJournalLines() {
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        streaming(surround, recS);
        host.sentryArmed();
        settle();
        at(1_000);
        host.goDark();
        s.wake();
        settle();
        long release = 1_000 + ChannelRules.HOLD_MS;
        at(release);
        assertTrue("留着：一步不动", surround.actions.isEmpty());
        host.sentry = 0;
        s.wake();
        settle();
        assertEquals(Arrays.asList("close screen-off"), surround.actions);
        at(release + 100);
        closed(surround);
        settle();
        at(120_000);
        host.sentry = null;   // 读不到：不变
        s.wake();
        settle();
        host.sentry = 1;
        s.wake();
        settle();
        assertEquals(Arrays.asList("close screen-off", "open [RECORD]"), surround.actions);
        at(120_300);
        opened(surround);
        frame(surround);
        settle();
        at(200_000);
        host.androidOff = true;
        s.wake();
        settle();
        assertEquals(Arrays.asList("close screen-off", "open [RECORD]", "close screen-off"), surround.actions);
        at(300_000);
        assertEquals(Arrays.asList(
                "surroundInDark KEPT DARK_ONSET sentry=2 records",
                "surroundInDark RELEASED SENTRY_OFF sentry=0 records",
                "surroundInDark KEPT SENTRY_ON sentry=1 records",
                "surroundInDark RELEASED ANDROID_OFF sentry=1 records"), lines("surroundInDark"));
    }

    /** 环视留着、在录，可这一次录像的计划里没有环视（只录座舱的配置）：那一行照实报「计划里没有」，不当成没在录。 */
    @Test
    public void theSurroundInDarkLineTellsAPlanWithoutTheSurroundFromNotRecording() {
        claim(Holder.RECORDING);
        pipe.plan(REAR, FRONT);
        streaming(rear, recR);
        streaming(front, recF);
        host.sentryArmed();
        settle();
        at(1_000);
        host.goDark();
        s.wake();
        settle();
        at(1_000 + ChannelRules.HOLD_MS);
        assertEquals(Arrays.asList("surroundInDark KEPT DARK_ONSET sentry=2 notInPlan"), lines("surroundInDark"));
    }

    /**
     * 环视留不留跟着进程、不跟着管线（项目所有者 2026-10-27）：管线放掉重建（旧的那一份换下去、只关），新的一份从调度记着的那一份接着用 ——
     * 排着的时候（换上之前）就读得到「环视留着」，熄屏录像不断；进程重新起来（一个新的调度）是放开。
     */
    @Test
    public void theSurroundInDarkSurvivesAPipelineRebuildButNotAProcessStart() {
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        streaming(surround, recS);
        host.sentryArmed();
        settle();
        at(1_000);
        host.goDark();
        s.wake();
        settle();
        at(1_000 + ChannelRules.HOLD_MS);
        assertEquals(ChannelRules.SurroundInDark.KEPT, s.snapshot().surroundInDark);
        assertTrue(s.snapshot().surroundKept);

        p.retire();   // 换车型：旧的那一份放掉，只关
        ChannelScheduler.Pipeline next = rebuilt(new FakePipe(), SUR, new FakePort("2"), REAR, new FakePort("1"));
        assertFalse("排着、第一轮之前：环视照旧留着", next.releasedByDark(SUR));
        assertTrue("座舱照旧放开", next.releasedByDark(REAR));
        at(1_000 + ChannelRules.HOLD_MS + 5_000);
        assertEquals(ChannelRules.SurroundInDark.KEPT, s.snapshot().surroundInDark);

        ChannelScheduler fresh = scheduler();   // 进程重新起来
        ChannelScheduler.Pipeline again = register(fresh, new FakePipe(), false);
        Map<String, ChannelScheduler.CameraPort> lanes = new LinkedHashMap<>();
        lanes.put(SUR, new FakePort("2"));
        again.setLanes(lanes);
        assertTrue("进程刚起来、黑着：放开", again.releasedByDark(SUR));
    }

    /**
     * 管线重建时新的那一份接上来、各路还没交给它（列相机、探合成流要一会儿）也照样一轮一轮跑（相机服务、登记表、车辆信号叫醒）：
     * 那几轮接着调度记着的那一份判，写回去的还是「留着」—— 交了各路环视照旧留着，旧的那一份关完就换上、开起来录（审查 2026-10-27）。
     */
    @Test
    public void roundsBeforeTheLanesArriveKeepTheSurroundInDark() {
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        streaming(surround, recS);
        host.sentryArmed();
        settle();
        at(1_000);
        host.goDark();
        s.wake();
        settle();
        long kept = 1_000 + ChannelRules.HOLD_MS;
        at(kept);
        assertTrue(s.snapshot().surroundKept);

        p.retire();   // 列相机出错：旧的那一份放掉，只关
        FakePipe second = new FakePipe();
        second.plan(SUR);
        ChannelScheduler.Pipeline next = rebuilt(second);
        s.wake();   // 各路还没交：相机服务、登记表、车辆信号叫醒
        at(kept + 1_000);
        assertEquals("交各路之前的那几轮", ChannelRules.SurroundInDark.KEPT, s.snapshot().surroundInDark);
        assertEquals(Arrays.asList("close rebuild"), surround.actions);

        FakePort newSurround = new FakePort("2");
        declare(newSurround, recS);
        newSurround.pipe = next;
        Map<String, ChannelScheduler.CameraPort> lanes = new LinkedHashMap<>();
        lanes.put(SUR, newSurround);
        next.setLanes(lanes);
        assertFalse("交了各路：调度记着的那一份还是留着", next.releasedByDark(SUR));
        at(kept + 1_100);
        closed(surround);   // 旧的那一份关完：换上新的一份
        at(kept + 5_000);
        assertEquals(ChannelRules.SurroundInDark.KEPT, s.snapshot().surroundInDark);
        assertEquals("新的那一份开起来录", Arrays.asList("open [RECORD]"), newSurround.actions);
    }

    /**
     * 车机要睡那一下的放开也写回调度记着的那一份（审查 2026-10-27）：留着的环视，车辆接口断了（哨兵模式读不到，留着不算关），
     * 黑着时安卓熄屏、车机要睡 —— 环视放开。醒了哨兵模式还布防着，旧的那一份还没再跑一轮管线就重建了：
     * 新的那一份接着「放开」，不开环视（醒来不算哨兵模式变成开）。
     */
    @Test
    public void theHeadUnitSleepReleaseSurvivesARebuildAtTheWake() {
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        streaming(surround, recS);
        host.sentryArmed();
        settle();
        at(1_000);
        host.goDark();
        s.wake();
        settle();
        at(1_000 + ChannelRules.HOLD_MS);
        assertTrue(s.snapshot().surroundKept);

        at(60_000);
        host.sentry = null;   // 车辆接口断了：读不到，留着不算关
        s.wake();
        settle();
        assertTrue(s.snapshot().surroundKept);
        host.androidOff = true;   // 车机要睡
        host.sleeps = true;
        s.onNeedsChanged();
        settle();
        assertEquals(Arrays.asList("close head-unit-sleeps"), surround.actions);
        at(60_100);
        closed(surround);
        settle();
        assertEquals(ChannelRules.SurroundInDark.RELEASED, s.snapshot().surroundInDark);

        at(3_600_000);   // 醒了，哨兵模式还布防着；旧的那一份还没再跑一轮，管线就重建了
        host.androidOff = false;
        host.sleeps = false;
        host.sentryArmed();
        p.retire();
        FakePipe second = new FakePipe();
        second.plan(SUR);
        FakePort newSurround = new FakePort("2");
        declare(newSurround, recS);
        ChannelScheduler.Pipeline next = rebuilt(second, SUR, newSurround);
        assertTrue("醒来不算变成开：环视还是放开", next.releasedByDark(SUR));
        s.wake();
        at(3_660_000);
        assertEquals(ChannelRules.SurroundInDark.RELEASED, s.snapshot().surroundInDark);
        assertTrue("不开：" + newSurround.actions, newSurround.actions.isEmpty());
        assertTrue("旧的那一份关完了，换上了新的一份", pipe.gone);
    }

    // ================================================================= 通道的决定发给录像协调器（项目所有者 2026-10-27）

    /** 录像协调器那一头：每听到一次记一行「KEPT DARK_ONSET」（此刻发布的决定和原因），也记进 told 看先后。 */
    private void listenToVerdict() {
        s.setVerdictListener(() -> {
            String line = s.verdict() + " " + s.verdictCause();
            verdicts.add(line);
            told.add("verdict " + line);
        });
    }

    /**
     * 决定每变一次发一次（连同原因），不变不发：通道刚算黑（留着）、哨兵关了、哨兵变成开、车机要睡、亮了 —— 每一下都是一次变；
     * 头 30 秒照亮着算，不发；中间空转的那些轮不发。
     */
    @Test
    public void theVerdictIsPublishedOncePerChangeWithItsCause() {
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        streaming(surround, recS);
        host.sentryArmed();
        settle();
        assertEquals("亮着", ChannelRules.SurroundInDark.LIT, s.verdict());
        listenToVerdict();
        at(1_000);
        host.goDark();
        s.wake();
        settle();
        at(1_000 + ChannelRules.HOLD_MS - 1);
        assertTrue("头 30 秒：照亮着算，不发", verdicts.isEmpty());
        at(1_000 + ChannelRules.HOLD_MS);
        assertEquals(Arrays.asList("KEPT DARK_ONSET"), verdicts);
        at(1_000 + ChannelRules.HOLD_MS + 20_000);
        assertEquals("空转的那几轮不发", 1, verdicts.size());

        host.sentry = 0;
        s.wake();
        settle();
        at(60_000);
        host.sentry = 1;
        s.wake();
        settle();
        at(90_000);
        host.androidOff = true;
        s.wake();
        settle();
        at(120_000);
        host.goLit();
        s.wake();
        settle();
        at(150_000);
        assertEquals(Arrays.asList("KEPT DARK_ONSET", "RELEASED SENTRY_OFF", "KEPT SENTRY_ON", "RELEASED ANDROID_OFF",
                "LIT null"), verdicts);
    }

    /**
     * 不是环视留不留变出来的变化也发，原因是 null：通道算黑时「熄屏持续录制」关了 / 又开了（KEPT ↔ RELEASED），
     * 开起车来 / 停下（LIT ↔ 放开）—— 停下那一刻就是通道刚算黑那一下。录像协调器只在原因是哨兵变成开时开录。
     */
    @Test
    public void settingAndDrivingChangesWhileDarkArePublishedWithoutASentryCause() {
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        streaming(surround, recS);
        host.sentryArmed();
        settle();
        listenToVerdict();
        at(1_000);
        host.goDark();
        s.wake();
        settle();
        at(1_000 + ChannelRules.HOLD_MS);
        host.keep = false;
        s.wake();
        settle();
        at(40_000);
        host.keep = true;
        s.wake();
        settle();
        at(50_000);
        host.driving = true;
        s.wake();
        settle();
        at(60_000);
        host.sentry = 0;
        host.driving = false;
        s.wake();
        settle();
        at(70_000);
        assertEquals(Arrays.asList("KEPT DARK_ONSET", "RELEASED null", "KEPT null", "LIT null", "RELEASED DARK_ONSET"),
                verdicts);
    }

    /**
     * 通道刚算黑那一轮：决定先发（录像协调器排它的那一下），再报这一轮的事（去掉前座舱那一下也会叫录像重算各路）、关相机、
     * 告诉录像那一侧各路的样子变了 —— 协调器排的在各路样子变了的那几下之前，录像的样子不会闪一下「等待摄像头」。
     */
    @Test
    public void theVerdictIsPublishedBeforeTheLanesAreToldInTheOnsetRound() {
        host.frontCabinOneShot = true;
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        host.sentry = 0;
        settle();
        List<Integer> retiredWhenPublished = new ArrayList<>();
        s.setVerdictListener(() -> {
            told.add("verdict " + s.verdict() + " " + s.verdictCause());
            retiredWhenPublished.add(pipe.frontRetired.size());
        });
        at(1_000);
        host.goDark();
        s.wake();
        settle();
        told.clear();
        at(1_000 + ChannelRules.HOLD_MS);
        assertEquals(Arrays.asList("close screen-off"), surround.actions);
        assertEquals("verdict RELEASED DARK_ONSET", told.get(0));
        assertFalse("同一轮里告诉了录像那一侧：" + told, told("condition").isEmpty());
        assertEquals("前座舱同一轮去掉，在发决定之后", Arrays.asList(FRONT), pipe.frontRetired);
        assertEquals(Arrays.asList(0), retiredWhenPublished);
    }

    /** 在退出的那一份不发：管线换掉、旧的那一份只关，它那几轮算出来的不算数。 */
    @Test
    public void anExitingSchedulerPublishesNothing() {
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        streaming(surround, recS);
        settle();
        listenToVerdict();
        s.exit();
        host.screenOn = false;   // 起点不明：当场算黑
        host.darkAt = 0;
        s.wake();
        settle();
        at(5_000);
        assertTrue(verdicts.isEmpty());
        assertEquals(ChannelRules.SurroundInDark.LIT, s.verdict());
    }

    /** 管线重建：换下去的那一份、换上的那一份都接着调度记着的那一份判，算出来的一样 —— 不再发。 */
    @Test
    public void aRebuildPublishesNothingNew() {
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        streaming(surround, recS);
        host.sentryArmed();
        settle();
        listenToVerdict();
        at(1_000);
        host.goDark();
        s.wake();
        settle();
        at(1_000 + ChannelRules.HOLD_MS);
        assertEquals(Arrays.asList("KEPT DARK_ONSET"), verdicts);

        p.retire();
        ChannelScheduler.Pipeline next = rebuilt(new FakePipe());
        s.wake();
        at(1_000 + ChannelRules.HOLD_MS + 1_000);
        FakePort newSurround = new FakePort("2");
        newSurround.pipe = next;
        Map<String, ChannelScheduler.CameraPort> lanes = new LinkedHashMap<>();
        lanes.put(SUR, newSurround);
        next.setLanes(lanes);
        at(1_000 + ChannelRules.HOLD_MS + 10_000);
        closed(surround);
        at(1_000 + ChannelRules.HOLD_MS + 20_000);
        assertTrue("换上了新的一份", pipe.gone);
        assertEquals(Arrays.asList("KEPT DARK_ONSET"), verdicts);
        assertEquals(ChannelRules.SurroundInDark.KEPT, s.verdict());
    }

    /**
     * 屏幕不知道黑没黑时不发（和环视留不留一样不判；这时算出来的「放开」是假的）；车机要睡那一条照发。知道了照常发。
     */
    @Test
    public void anUnknownScreenPublishesOnlyTheHeadUnitSleeping() {
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        streaming(surround, recS);
        host.sentryArmed();
        settle();
        listenToVerdict();
        host.known = false;
        host.screenOn = false;   // 起点不明：当场算黑
        host.darkAt = 0;
        s.wake();
        settle();
        at(5_000);
        assertTrue("不知道：不发", verdicts.isEmpty());
        host.androidOff = true;
        s.wake();
        settle();
        assertEquals(Arrays.asList("RELEASED ANDROID_OFF"), verdicts);
    }

    /** 进程刚起来（一个新的调度）：决定是放开、没有原因；挂在别的调度上的监听者听不到它。 */
    @Test
    public void aFreshSchedulerStartsReleasedWithoutACause() {
        settle();
        assertEquals(ChannelRules.SurroundInDark.LIT, s.verdict());
        listenToVerdict();
        ChannelScheduler fresh = scheduler();
        assertEquals(ChannelRules.SurroundInDark.RELEASED, fresh.verdict());
        assertNull(fresh.verdictCause());
        fresh.wake();
        settle();
        assertEquals("还没有管线：空的那一份不判、不发", ChannelRules.SurroundInDark.RELEASED, fresh.verdict());
        register(fresh, new FakePipe(), false);
        settle();
        assertEquals("第一份管线那一轮：亮着，发了一次 LIT", ChannelRules.SurroundInDark.LIT, fresh.verdict());
        assertTrue("挂在另一个调度上的监听者听不到", verdicts.isEmpty());
    }

    /** 进程在黑着时起来（进程那一层从放开起、起点不明）：决定就是放开，不发 —— 录像协调器读到放开，不开录。 */
    @Test
    public void aProcessStartingInTheDarkPublishesNothingAndReadsReleased() {
        listenToVerdict();
        host.screenOn = false;
        host.darkAt = 0;
        host.sentryArmed();
        s.wake();
        settle();
        at(10_000);
        assertTrue(verdicts.isEmpty());
        assertEquals(ChannelRules.SurroundInDark.RELEASED, s.verdict());
        assertNull(s.verdictCause());
    }

    /**
     * 进程在黑着时被拉起、还没有一份管线（审查 2026-10-30）：调度装上了，可空的那一份不判环视留不留、不发决定、不写回，也不按 2 秒醒 ——
     * 之后哨兵模式开了也一样（空的那一份照判的话：先读到关、再读到开，就算「变成开」留下环视、发出 KEPT，录像协调器就去开录）。
     * 第一份管线照进程刚起来的样子起（放开、没有上一次读数）：黑着、哨兵开着也不开环视、不发决定。
     */
    @Test
    public void beforeAnyPipelineTheDarkIsNotJudged() {
        FakePoster quiet = new FakePoster();
        ChannelScheduler fresh = new ChannelScheduler(() -> now, quiet, host, rounds);
        List<String> heard = new ArrayList<>();
        fresh.setVerdictListener(() -> heard.add(fresh.verdict() + " " + fresh.verdictCause()));
        host.screenOn = false;
        host.darkAt = 0;   // 起点不明：照黑满 30 秒算
        host.sentry = 0;
        fresh.wake();
        quiet.runUntil(now);
        assertTrue("空的那一份不按时醒", quiet.tasks.isEmpty());
        quiet.runUntil(T0 + 5_000);
        host.sentryArmed();   // 哨兵模式开了
        fresh.wake();
        quiet.runUntil(now);
        assertTrue(heard.isEmpty());
        assertEquals(ChannelRules.SurroundInDark.RELEASED, fresh.verdict());
        assertNull(fresh.verdictCause());
        assertTrue(quiet.tasks.isEmpty());

        host.holders.add(Holder.RECORDING);
        fresh.onNeedsChanged();
        FakePipe first = new FakePipe();
        first.plan(SUR);
        FakePort s2 = new FakePort("2");
        declare(s2, recS);
        ChannelScheduler.Pipeline handle = register(fresh, first, false);
        s2.pipe = handle;
        Map<String, ChannelScheduler.CameraPort> lanes = new LinkedHashMap<>();
        lanes.put(SUR, s2);
        handle.setLanes(lanes);
        quiet.runUntil(T0 + 60_000);
        assertTrue("第一份管线照进程刚起来的样子起：不开环视", s2.actions.isEmpty());
        assertTrue("不发决定", heard.isEmpty());
        assertEquals(ChannelRules.SurroundInDark.RELEASED, fresh.snapshot().surroundInDark);
        assertTrue(handle.releasedByDark(SUR));
    }

    // ================================================================= 问（只问，不开）

    /**
     * 被拿着的一路有人要：马上问一次，之后每 30 秒一次；答回来每次都进日志，黑匣子只记第一次和每第 10 次。
     * 一次都不开它。
     */
    @Test
    public void asksEveryThirtySecondsWithoutOpeningAndSamplesTheBlackBox() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        rear.declared.add(pvR);
        host.absent("1");
        settle();
        assertEquals(1, lines("recognized left absent").size());
        assertEquals("刚判成被拿着、有人要：马上问", 1, host.asks.size());
        host.answer();
        at(ChannelRules.ASK_EVERY_MS - 1);
        assertEquals(1, host.asks.size());
        at(ChannelRules.ASK_EVERY_MS);
        assertEquals(2, host.asks.size());
        for (int i = 2; i < 12; i++) {
            host.answer();
            at(i * ChannelRules.ASK_EVERY_MS);
        }
        host.answer();
        List<String> asked = lines("asked left");
        assertEquals(12, asked.size());
        assertEquals("asked left 1 important", asked.get(0));
        assertEquals("asked left 2 quiet", asked.get(1));
        assertEquals("asked left 11 important", asked.get(10));
        assertTrue("被拿着的时候一次都不开", rear.actions.isEmpty());

        drop(Holder.PREVIEW);
        settle();
        int asks = host.asks.size();
        at(12 * ChannelRules.ASK_EVERY_MS + 60_000);
        assertEquals("没人要它了：不问", asks, host.asks.size());
    }

    /** 访问优先级变了：马上问一次，绝不因此去开。 */
    @Test
    public void anAccessPriorityChangeAsksAtOnceButNeverOpens() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        rear.declared.add(pvR);
        host.busyNotOurs("1");
        settle();
        assertEquals(1, host.asks.size());
        host.answer();
        at(5_000);
        s.askNow();
        settle();
        assertEquals(2, host.asks.size());
        assertTrue(rear.actions.isEmpty());
    }

    // ================================================================= closingAnywhere 的起落

    /** 退出：exit() 之后当场为真；手上那一步做完、按次序关（环视先单独关），最后一路关完才变假，调度随之停下。 */
    @Test
    public void exitRaisesClosingAnywhereAtOnceAndLowersItOnlyWhenTheLastLaneClosed() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        declare(rear, pvR);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), rear.actions);
        assertFalse(s.closing());

        exitApp();
        assertTrue("exit() 之后当场为真", s.closing());
        settle();
        assertEquals(1, lines("exit left OPEN").size());
        assertTrue("手上那一步做完之前不关", surround.actions.isEmpty());
        at(400);
        opened(rear);
        frame(rear);
        settle();
        assertEquals(Arrays.asList("close exit"), surround.actions);
        assertTrue(s.closing());
        at(500);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]", "close exit"), rear.actions);
        assertEquals(Arrays.asList("close exit"), front.actions);
        at(600);
        closed(rear);
        settle();
        assertTrue("还有一路没关完", s.closing());
        at(700);
        closed(front);
        settle();
        assertFalse("最后一路关完才变假", s.closing());
        assertTrue(pipe.gone);
        assertEquals(1, lines("closeRound front | left, back").size());
    }

    /**
     * 车机要睡：发布的那一刻 closingAnywhere 当场为真；手上那一步最多再等 1 秒（这 1 秒里一直为真），然后按次序关；
     * 最后一路关完变假。亮屏清掉之后照常。
     */
    @Test
    public void theHeadUnitSleepingWaitsOneSecondForTheStepInHandThenClosesInOrder() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(front, recF);
        declare(rear, recR);
        settle();
        assertEquals(Arrays.asList("open [RECORD]"), rear.actions);

        at(100);
        host.sleeps = true;
        s.onNeedsChanged();
        assertTrue("发布的那一刻就为真", s.closing());
        settle();
        assertEquals(1, lines("sleep left OPEN").size());
        at(100 + ChannelRules.SLEEP_GRACE_MS - 1);
        assertTrue("1 秒宽限里不动", surround.actions.isEmpty());
        assertTrue(s.closing());
        at(100 + ChannelRules.SLEEP_GRACE_MS);
        assertEquals("等满 1 秒：环视单独先关", Arrays.asList("close head-unit-sleeps"), surround.actions);
        assertTrue(front.actions.isEmpty());
        at(1_300);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("close head-unit-sleeps"), front.actions);
        assertEquals("开到一半的那一路也一起关", Arrays.asList("open [RECORD]", "close head-unit-sleeps"),
                rear.actions);
        at(1_400);
        closed(rear);
        closed(front);
        settle();
        assertFalse(s.closing());
        assertFalse("车机要睡不停调度（亮屏还要用）", pipe.gone);

        // 亮屏：清掉这项事实，照常开
        host.sleeps = false;
        s.onNeedsChanged();
        at(5_000);
        assertFalse(s.closing());
        assertEquals(Arrays.asList("close head-unit-sleeps", "open [RECORD]"), surround.actions);
    }

    /** 车机要睡被亮屏清掉、手上没有关在途：当场不再算在关。 */
    @Test
    public void screenOnClearsTheSleepWhileNothingCloses() {
        settle();
        host.sleeps = true;
        s.onNeedsChanged();
        assertTrue(s.closing());
        host.sleeps = false;
        s.onNeedsChanged();
        settle();
        assertFalse(s.closing());
    }

    /**
     * 等「关完」（熄屏录制的唤醒锁等它再放，大纲 §2 阶段 6）：没有在关的时候当场叫；车机要睡时登记的，按次序关、最后一路关完的那一轮
     * 才叫，只叫一次；不等了摘掉的不叫。
     */
    @Test
    public void aCloseWaiterRunsOnceWhenTheLastLaneClosedAndAtOnceWhenNothingCloses() {
        List<String> calls = new ArrayList<>();
        s.whenClosed(() -> calls.add("idle"));
        assertEquals("没有在关的：当场叫", Arrays.asList("idle"), calls);

        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        host.sleeps = true;
        s.onNeedsChanged();
        Runnable cancelled = () -> calls.add("cancelled");
        s.whenClosed(() -> calls.add("closed"));
        s.whenClosed(cancelled);
        s.cancelWhenClosed(cancelled);
        settle();
        assertEquals("环视单独先关", Arrays.asList("close head-unit-sleeps"), surround.actions);
        assertEquals("还在关：不叫", Arrays.asList("idle"), calls);
        at(200);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("close head-unit-sleeps"), rear.actions);
        assertEquals("座舱还没关完", Arrays.asList("idle"), calls);
        at(300);
        closed(rear);
        closed(front);
        settle();
        assertFalse(s.closing());
        assertEquals("最后一路关完的那一轮叫", Arrays.asList("idle", "closed"), calls);
        at(10_000);
        assertEquals("只叫一次", Arrays.asList("idle", "closed"), calls);
    }

    /**
     * 退出时一次关卡死在相机服务里：closingAnywhere 最多为真 EXIT_WAIT_MAX_MS，不会一直为真 —— 卡住的那一路那一下开始关以后满 60 秒，
     * 换下去的那一份留下它当孤儿、换上一份空的，那一路的那一轮照旧在账本上（还在关），封顶从退出那一刻算。
     */
    @Test
    public void aStuckCloseOnExitKeepsClosingAnywhereOnlyUntilTheCap() {
        claim(Holder.MIRROR);   // 只要环视：手上没有开到一半的步子，退出当场就关
        streaming(surround, pvS);
        settle();
        exitApp();
        settle();
        assertEquals(Arrays.asList("close exit"), surround.actions);
        assertTrue(s.closing());

        at(ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals("满 60 秒：换下去，那一路当孤儿", 1, lines("swapped").size());
        assertTrue(lines("swapped").get(0).contains("none orphans front 2"));
        assertFalse("还有一轮没结束：没放完", pipe.gone);
        assertTrue(s.closing());
        at(ChannelRules.EXIT_WAIT_MAX_MS - 1);
        assertTrue(s.closing());
        at(ChannelRules.EXIT_WAIT_MAX_MS);
        assertFalse("关卡死的那一下最多算这么久", s.closing());

        at(ChannelRules.EXIT_WAIT_MAX_MS + 10_000);
        closed(surround);   // 那一下终于返回了：孤儿那一轮结束，这一份放完
        settle();
        assertTrue(pipe.gone);
        assertEquals(Arrays.asList("stray 2 closed"), lines("stray"));
    }

    // ================================================================= 相机服务的话算谁引起的

    /**
     * 我们关完之后 {@link ChannelScheduler#OWN_WORD_GRACE_MS} 以内，相机服务为这一关说的话还算我们的 ——
     * 那一句从 binder 线程上来，可能晚于关完的回调到主线程（审查 2026-10-11）。
     */
    @Test
    public void wordsJustAfterOurOwnCloseStillCountAsOurs() {
        host.foregroundService = false;
        streaming(surround, pvS);
        settle();
        assertEquals(Arrays.asList("close unwanted"), surround.actions);
        assertTrue("在关：我们的", s.ourStepInFlight("2"));
        at(200);
        closed(surround);
        assertTrue("刚关完：还算我们的", s.ourStepInFlight("2"));
        at(200 + ChannelScheduler.OWN_WORD_GRACE_MS - 1);
        assertTrue(s.ourStepInFlight("2"));
        at(200 + ChannelScheduler.OWN_WORD_GRACE_MS);
        assertFalse(s.ourStepInFlight("2"));
    }

    /**
     * 管线在同一个进程里重建（配置里开关了一路、换车型、退出后马上又开）：新的一份读到的「被占用」还是旧的那一份开的时候记下的
     * 「我们开着」，旧的那一份早关完了 —— 关着的这一路还被占着，只能是别人（原厂 360 拿着环视）。认出来，只问、不开；
     * 相机服务说它空了，安静 3 秒再开（审查 2026-10-11：以前当成我们的，安静了就去开，正是 §二.7 不许的试开）。
     */
    @Test
    public void aRebuiltPipelineDoesNotOpenALaneOthersHoldBehindAnOldOursWord() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        host.words.put("2", new CameraAvailabilityWatch.Word(false, false, true, elapsed(now - 600_000)));
        claim(Holder.MIRROR);
        declare(surround, mirror);
        settle();
        assertEquals(Arrays.asList("recognized front busy"), lines("recognized"));
        assertTrue("一次也不试开", surround.actions.isEmpty());
        assertEquals("有人要：马上问", 1, host.asks.size());
        host.answer();
        at(ChannelRules.ASK_EVERY_MS);
        assertEquals(2, host.asks.size());
        assertTrue(surround.actions.isEmpty());

        at(40_000);
        say(surround, true, false);
        s.wake();
        settle();
        assertEquals(1, lines("released front TAKEN").size());
        at(40_000 + ChannelRules.QUIET_MS);
        assertEquals(Arrays.asList("open [MIRROR]"), surround.actions);
    }

    /**
     * 同上，旧的那一份还在关（管线重建、退出后马上又开）：新的那一份排着，那一句「被占用（我们开着）」不会被它认成别人拿着
     * （排着的不判）；旧的那一份关完、相机服务说「空闲」，换上新的一份，照常开。
     */
    @Test
    public void anOursWordWhileTheOldPipelineIsStillClosingIsNotTakenForOthers() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);   // 旧的那一份开着环视
        settle();
        p.retire();
        FakePort fresh = new FakePort("2");
        declare(fresh, mirror);
        rebuilt(new FakePipe(), SUR, fresh);
        settle();
        assertEquals(Arrays.asList("close rebuild"), surround.actions);
        assertTrue("旧的那一份还在关：那一句还算我们的", lines("recognized").isEmpty());
        assertTrue(fresh.actions.isEmpty());

        at(1_000);
        closed(surround);   // 相机服务为这一关说「空闲」（我们引起的）
        at(1_000 + ChannelRules.TICK_MS * 2);
        assertEquals(Arrays.asList("open [MIRROR]"), fresh.actions);
        assertTrue(lines("recognized").isEmpty());
    }

    // ================================================================= 轮次和账本（第 1 步，项目所有者 2026-10-29 批）

    /**
     * 退出又马上打开（第 2 步：只有一个调度）：退出时旧的那一份关环视，人又点开了主界面 —— 不再只关，新建的那一份排着；
     * 旧的那一份一秒就关完了，换上新的一份、开它（账本上那一台空了），一次都没被相机层挡（heldRefusals 0）；
     * 同一台相机两份相机对象从不同时有一轮。黑匣子：退出、主界面又回来了（进程这一层）、换下去了，各一行；退出不算了，
     * 旧的那一份还在换下去 —— 那时补上它「这一份换下去」那一行（审查 2026-10-30：以前「主界面又回来了」由还在只关的它写，它那一行从不写）。
     */
    @Test
    public void aNormalExitThenAnImmediateReopenOpensOnlyAfterTheOldRoundEnds() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        settle();
        exitApp();
        settle();
        assertEquals(Arrays.asList("close exit"), surround.actions);

        s.resumeAfterExit();   // 人又点开了主界面
        FakePort fresh = new FakePort("2");
        declare(fresh, mirror);
        FakePipe second = new FakePipe();
        rebuilt(second, SUR, fresh);
        settle();
        assertTrue("旧的那一轮还没结束：不开", fresh.actions.isEmpty());
        assertSame(surround, rounds.live("2").owner);

        at(1_000);
        closed(surround);
        assertNull("旧的那一轮结束了", rounds.live("2"));
        at(1_000 + ChannelRules.TICK_MS * 2);
        assertEquals(Arrays.asList("open [MIRROR]"), fresh.actions);
        assertSame(fresh, rounds.live("2").owner);
        assertEquals(0, fresh.heldRefusals);
        assertTrue(lines("held ").isEmpty());
        assertEquals(1, lines("exit ").size());
        assertEquals(1, lines("resumed").size());
        assertEquals("退出不算了，旧的那一份还在换下去：补上它那一行", Arrays.asList("retiring " + SUR + " CLOSE"),
                lines("retiring"));
        assertTrue("先写主界面又回来了", journal.indexOf("resumed") < journal.indexOf("retiring " + SUR + " CLOSE"));
        assertEquals(1, lines("swapped").size());
        assertTrue(pipe.gone);
        assertFalse(second.gone);
        assertFalse(s.closing());
    }

    /**
     * 同上，旧的那一份关环视一直不返回（项目所有者 2026-10-28：我们上一次关一台相机还没返回，App 里谁都不开它，直到那一次关返回）：
     * 旧的那一份环视单独关、10 秒到点照样关座舱，座舱关完；环视那一下开始关以后满 60 秒（项目所有者 2026-10-29「等旧管线这一步做完再算
     * 60 秒」）换上新的一份 —— 环视当孤儿留下，新的一份那一路被一轮占着、不开，别的路照常开；黑匣子写一次「还有开或关没返回」；
     * 旧的那一句「被占用（我们开着）」也不认成别人拿着；录像那一侧听说它不再算在起来。10 分钟后那一次关返回了：写一次「返回了」，
     * 旧的那一份放完，环视照常开。相机层一次都没被挡（规则照账本本来就不让它开），同一台相机从不两份同时拿着。
     */
    @Test
    public void aRebuiltPipelineNeverOpensACameraTheOldPipelinesRoundStillHolds() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        claim(Holder.PREVIEW);
        streaming(surround, mirror);   // 旧的那一份开着三路
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        p.retire();
        FakePipe second = new FakePipe();
        FakePort newSurround = new FakePort("2");
        FakePort newRear = new FakePort("1");
        FakePort newFront = new FakePort("0");
        declare(newSurround, mirror);
        declare(newRear, new Out(Kind.PREVIEW, "preview-1b"));
        declare(newFront, new Out(Kind.PREVIEW, "preview-0b"));
        ChannelScheduler.Pipeline next = rebuilt(second, SUR, newSurround, REAR, newRear, FRONT, newFront);
        settle();
        assertEquals("旧的那一份环视单独先关（一直不返回）", Arrays.asList("close rebuild"), surround.actions);
        assertEquals(1, lines("retiring").size());
        at(ChannelRules.CLOSE_SURROUND_MAX_MS);
        assertEquals("环视 10 秒到点：座舱照样关", Arrays.asList("close rebuild"), rear.actions);
        at(ChannelRules.CLOSE_SURROUND_MAX_MS + 200);
        closed(rear);
        closed(front);
        assertFalse("60 秒以内录像那一侧照旧算它在起来", next.cannotOpen(SUR));

        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertTrue("头 60 秒：新的一份排着，什么都不开",
                newSurround.actions.isEmpty() && newRear.actions.isEmpty() && newFront.actions.isEmpty());
        assertTrue(lines("swapped").isEmpty());
        second.told.clear();
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals(Arrays.asList("swapped " + ChannelRules.IN_FLIGHT_MAX_MS + " successor orphans front 2 round=1 closing"),
                lines("swapped"));
        assertEquals("满 60 秒：换上新的一份，别的路照常，环视跳过", Arrays.asList("open [PREVIEW]"), newRear.actions);
        assertTrue(newSurround.actions.isEmpty());
        assertEquals(Arrays.asList("held front 2 round=1 front other closing ms=" + ChannelRules.IN_FLIGHT_MAX_MS),
                lines("held "));
        assertTrue("录像那一侧听说了", second.told.contains("condition " + SUR + " BRINGING_UP"));
        assertTrue(next.heldByClose(SUR));
        assertTrue(next.cannotOpen(SUR));
        opened(newRear);
        frame(newRear);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), newFront.actions);
        opened(newFront);
        frame(newFront);
        settle();

        at(10 * 60_000);
        assertTrue("10 分钟也不开", newSurround.actions.isEmpty());
        assertEquals("只写一次", 1, lines("held ").size());
        assertTrue("旧的那一句还算我们的：不认成别人拿着", lines("recognized").isEmpty());
        ChannelScheduler.LaneView v = view(s.snapshot(), SUR);
        assertTrue(v.held);
        assertTrue(v.heldByClose);
        assertFalse("旧的那一份管线的那一轮", v.roundOwnedByLane);
        assertEquals(T0, v.roundStatus.outstandingSince);
        assertEquals(1, s.snapshot().orphans.size());
        assertFalse(pipe.gone);
        assertEquals(0, newSurround.heldRefusals);

        closed(surround);   // 那一次关返回了，相机服务为这一关说「空闲」（叫醒调度）
        settle();
        assertEquals(Arrays.asList("heldEnded front 2 round=1 ms=" + (10 * 60_000)), lines("heldEnded"));
        assertFalse(next.heldByClose(SUR));
        assertTrue("孤儿那一轮结束了：旧的那一份放完", pipe.gone);
        assertTrue(s.snapshot().orphans.isEmpty());
        at(10 * 60_000 + ChannelRules.TICK_MS * 2);
        assertEquals("返回了：照常开", Arrays.asList("open [MIRROR]"), newSurround.actions);
        assertTrue(lines("recognized").isEmpty());
    }

    /**
     * 占着它的那一轮「从什么时候起一直有开或关没返回」不从头算（项目所有者 2026-10-29）：环视开，openCamera 返回了、回调一直不来；
     * 30 秒时它不该开了、下达了关 —— 那一轮还是同一段（开的那一刻起）；又有人要它时它被自己那一轮占着，满 60 秒（从开的那一刻算）
     * 就写那一行，不是从下达关那一刻再等 60 秒。
     */
    @Test
    public void theHeldClockRunsFromTheRoundsOutstandingStartAndNeverRestarts() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        declare(surround, mirror);
        claim(Holder.MIRROR);
        settle();
        assertEquals("开环视：openCamera 返回了，回调一直不来", Arrays.asList("open [MIRROR]"), surround.actions);

        at(30_000);
        host.foregroundService = false;   // 不保持
        drop(Holder.MIRROR);
        settle();
        assertEquals(Arrays.asList("open [MIRROR]", "close unwanted"), surround.actions);
        assertEquals(CameraRound.Phase.CLOSING, surround.live.phase());
        claim(Holder.MIRROR);
        settle();
        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertTrue(lines("held ").isEmpty());
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals(Arrays.asList("held front 2 round=1 front own closing open-pending ms="
                + ChannelRules.IN_FLIGHT_MAX_MS), lines("held "));
        assertEquals("被自己那一轮占着：不再开", Arrays.asList("open [MIRROR]", "close unwanted"), surround.actions);
    }

    /**
     * 换下去的那一份关一路，那一下开始关以后满 60 秒才换上新的一份（项目所有者 2026-10-29「等旧管线这一步做完再算 60 秒」）：
     * 那一路的 openCamera 早就卡着（开的那一刻起一直有开没返回），换下去时下达了关 —— 从下达关那一刻算 60 秒，不是从开的那一刻。
     * 新的一份换上以后照旧按那一轮的钟（开的那一刻起）写「还有开或关没返回」。
     */
    @Test
    public void theSwapWaitsSixtySecondsFromWhenTheOldCloseBegan() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        declare(surround, mirror);
        claim(Holder.MIRROR);
        settle();
        assertEquals(Arrays.asList("open [MIRROR]"), surround.actions);

        at(30_000);
        p.retire();
        FakePort fresh = new FakePort("2");
        declare(fresh, mirror);
        rebuilt(new FakePipe(), SUR, fresh);
        settle();
        assertEquals(Arrays.asList("open [MIRROR]", "close rebuild"), surround.actions);
        at(30_000 + ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertTrue("从开始关起还不到 60 秒：不换", lines("swapped").isEmpty());
        at(30_000 + ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals(1, lines("swapped").size());
        assertEquals(Arrays.asList("held front 2 round=1 front other closing open-pending ms="
                + (30_000 + ChannelRules.IN_FLIGHT_MAX_MS)), lines("held "));
        assertTrue(fresh.actions.isEmpty());
    }

    /**
     * 被拿着的一路放开了，可它丢了之后那一次关还没返回（项目所有者 2026-10-28）：「放开了」那一行不说「等通道安静，单独开」、说返回之前不开
     * （「released … stillHeld」），安静了也不开；那一次关返回了，「返回了」那一行说照常开，当场开。
     */
    @Test
    public void aLaneReleasedWhileItsCloseIsOutstandingIsSaidNotToOpen() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        streaming(rear, pvR);
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        say(rear, false, true);
        settle();
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals(1, lines("judged left TAKEN LOSS").size());
        assertEquals(1, lines("held left 1 ").size());

        at(2 * 60_000);
        say(rear, true, false);   // 相机服务说它空闲了：放开了被拿着
        s.wake();
        settle();
        assertEquals(Arrays.asList("released left TAKEN stillHeld"), lines("released"));
        at(2 * 60_000 + ChannelRules.QUIET_MS + ChannelRules.TICK_MS);
        assertTrue("返回之前不开", rear.actions.isEmpty());

        at(3 * 60_000);
        rear.closedNow();
        p.onLossClosed("1", 3 * 60_000);
        settle();
        assertEquals(Arrays.asList("heldEnded left 1 round=3 ms=" + (3 * 60_000)), lines("heldEnded"));
        assertEquals("返回了：照常开", Arrays.asList("open [PREVIEW]"), rear.actions);
    }

    /**
     * 「返回了」那一行照这一路此刻的样子说接下来怎样（项目所有者 2026-10-28）：还被拿着 —— 等它空出来再开（「… TAKEN」），不开；
     * 放开了的那一行照常说「等通道安静，单独开」。
     */
    @Test
    public void theHeldEndedLineSaysTheLaneIsStillTaken() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        streaming(rear, pvR);
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        say(rear, false, true);
        settle();
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals(1, lines("held left 1 ").size());

        at(2 * 60_000);
        rear.closedNow();
        p.onLossClosed("1", 2 * 60_000);
        settle();
        assertEquals(Arrays.asList("heldEnded left 1 round=3 ms=" + (2 * 60_000) + " TAKEN"), lines("heldEnded"));
        at(2 * 60_000 + ChannelRules.QUIET_MS + ChannelRules.TICK_MS);
        assertTrue("还被拿着：不开", rear.actions.isEmpty());

        at(3 * 60_000);
        say(rear, true, false);
        s.wake();
        settle();
        assertEquals(Arrays.asList("released left TAKEN"), lines("released"));
        at(3 * 60_000 + ChannelRules.QUIET_MS);
        assertEquals("放开了：安静了单独开", Arrays.asList("open [PREVIEW]"), rear.actions);
    }

    /** 同上，那一次关返回时这一路已经没人要了：「… unwanted」，不开；没人要的那一段不算在等它（状态条不说）。 */
    @Test
    public void theHeldEndedLineSaysNobodyWantsTheLaneAnyMore() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        streaming(rear, pvR);
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        say(rear, true, true);   // 为这一关说的「空闲」（我们引起的）：到点判成普通故障
        settle();
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals(1, lines("held left 1 ").size());
        drop(Holder.PREVIEW);
        settle();
        assertFalse("没人要了：不算在等它", p.heldByClose(REAR));

        at(2 * 60_000);
        rear.closedNow();
        p.onLossClosed("1", 2 * 60_000);
        settle();
        assertEquals(Arrays.asList("heldEnded left 1 round=3 ms=" + (2 * 60_000) + " unwanted"), lines("heldEnded"));
        at(2 * 60_000 + ChannelRules.QUIET_MS + ChannelRules.TICK_MS);
        assertTrue("没人要：不开", rear.actions.isEmpty());
    }

    // ================================================================= 只有一个调度：管线一个接一个（第 2 步，项目所有者 2026-10-29 批）

    /**
     * 管线重建：旧的那一份放掉（换下去），和退出一样按次序关 —— 环视单独先关，关完座舱一起关；关的原因写「管线重建」，
     * 黑匣子写一行「管线重建：这一份换下去」；这期间 closingAnywhere 为真（和以前旧的那一份调度照退出关一样）。新的那一份排着，
     * 一步都不动；旧的那一份各路关完，换上它（「换下去了」一行），它照开的次序开 —— 环视先，出了画面再开下一路，相机服务为旧的那几关说的
     * 「空闲」是我们引起的，不用等安静。旧的那一份放完（retired）。
     */
    @Test
    public void aRebuildClosesTheOldPipelineInOrderThenOpensTheNewOneInOrder() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        p.retire();
        assertTrue("放掉那一下当场为真", s.closing());
        FakePipe second = new FakePipe();
        FakePort s2 = new FakePort("2");
        FakePort r2 = new FakePort("1");
        FakePort f2 = new FakePort("0");
        declare(s2, new Out(Kind.PREVIEW, "preview-2b"));
        declare(r2, new Out(Kind.PREVIEW, "preview-1b"));
        declare(f2, new Out(Kind.PREVIEW, "preview-0b"));
        ChannelScheduler.Pipeline next = rebuilt(second, FRONT, f2, REAR, r2, SUR, s2);
        settle();
        assertEquals("环视单独先关", Arrays.asList("close rebuild"), surround.actions);
        assertTrue(rear.actions.isEmpty() && front.actions.isEmpty());
        assertEquals(Arrays.asList("retiring null null"), lines("retiring"));
        assertEquals("排着的那一份照它建好的世界答：在起来", ChannelRules.Condition.BRINGING_UP, next.condition(SUR));

        at(200);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("close rebuild"), rear.actions);
        assertEquals(Arrays.asList("close rebuild"), front.actions);
        assertTrue("还排着：一步都不动", s2.actions.isEmpty() && r2.actions.isEmpty() && f2.actions.isEmpty());
        assertTrue(s.closing());

        at(300);
        closed(rear);
        closed(front);
        settle();
        assertEquals(Arrays.asList("swapped 300 successor"), lines("swapped"));
        assertEquals(1, lines("closeRound front | left, back").size());
        assertTrue(pipe.gone);
        assertFalse(s.closing());
        assertEquals("换上了：环视先开", Arrays.asList("open [PREVIEW]"), s2.actions);
        assertTrue(r2.actions.isEmpty());
        at(600);
        opened(s2);
        frame(s2);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), r2.actions);
        at(800);
        opened(r2);
        frame(r2);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), f2.actions);
        assertTrue("相机服务为旧的那几关说的话是我们的：不等安静", lines("waiting QUIET").isEmpty());
    }

    /**
     * 最坏的那一种（项目所有者 2026-10-29「等旧管线这一步做完再算 60 秒」）：放掉时旧的那一份手上有一步（开后座舱，15 秒没出画面），
     * 做完才关；环视单独关差一点满 10 秒；座舱一起关，前座舱那一次关一直不返回 —— 从它开始关算满 60 秒（放掉以后约 85 秒）才换上新的一份。
     * 新的一份那一路（前座舱）被一轮占着、不开，环视、后座舱照常开；满 60 秒那一行、状态条那一句当场就有（从那一次关开始算已经满了）。
     */
    @Test
    public void aStuckCabinCloseDuringARebuildSwapsSixtySecondsAfterThatCloseBegan() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        declare(rear, pvR);
        settle();
        assertEquals("手上那一步：开后座舱（一直不出画面）", Arrays.asList("open [PREVIEW]"), rear.actions);
        p.retire();
        FakePipe second = new FakePipe();
        FakePort s2 = new FakePort("2");
        FakePort r2 = new FakePort("1");
        FakePort f2 = new FakePort("0");
        declare(s2, new Out(Kind.PREVIEW, "preview-2b"));
        declare(r2, new Out(Kind.PREVIEW, "preview-1b"));
        declare(f2, new Out(Kind.PREVIEW, "preview-0b"));
        ChannelScheduler.Pipeline next = rebuilt(second, SUR, s2, REAR, r2, FRONT, f2);
        settle();
        assertTrue("手上那一步做完再关", surround.actions.isEmpty());

        at(ChannelRules.STEP_MAX_MS);
        assertEquals("那一步 15 秒到点：环视单独关", Arrays.asList("close rebuild"), surround.actions);
        long cabinsAt = ChannelRules.STEP_MAX_MS + ChannelRules.CLOSE_SURROUND_MAX_MS - 100;
        at(cabinsAt);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]", "close rebuild"), rear.actions);
        assertEquals(Arrays.asList("close rebuild"), front.actions);
        at(cabinsAt + 100);
        closed(rear);   // 后座舱关完；前座舱那一次关一直不返回

        at(cabinsAt + ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertTrue("从前座舱开始关算还不到 60 秒：不换", lines("swapped").isEmpty());
        assertTrue(s2.actions.isEmpty() && r2.actions.isEmpty() && f2.actions.isEmpty());
        at(cabinsAt + ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals("放掉以后约 85 秒", 84_900, cabinsAt + ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals(1, lines("swapped").size());
        assertTrue(lines("swapped").get(0), lines("swapped").get(0).contains("successor orphans back 0 round=2 closing"));
        assertEquals(Arrays.asList("held back 0 round=2 back other closing ms=" + ChannelRules.IN_FLIGHT_MAX_MS),
                lines("held "));
        assertTrue("状态条那一句", next.heldByClose(FRONT));
        assertEquals("环视照常开", Arrays.asList("open [PREVIEW]"), s2.actions);
        opened(s2);
        frame(s2);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), r2.actions);
        opened(r2);
        frame(r2);
        settle();
        assertTrue("被一轮占着：不开", f2.actions.isEmpty());
        assertEquals(1, s.snapshot().orphans.size());
        assertFalse(pipe.gone);
    }

    /**
     * 退出又马上打开，连着三次（第 2 步：没有第二个调度）：每一次新建的那一份管线都排着，旧的那一份还在关；再退出就放掉排着的那一份
     * （它什么都没开过，当场放完），新的一份替上来。旧的那一份关完，换上最后那一份，开它 —— 一个调度、一份接一份。
     */
    @Test
    public void exitAndReopenThreeTimesKeepsOnlyTheNewestPendingPipeline() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        settle();
        exitApp();
        settle();
        assertEquals(Arrays.asList("close exit"), surround.actions);
        List<FakePipe> hosts = new ArrayList<>();
        List<FakePort> ports = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            at(100 * (i + 1));
            s.resumeAfterExit();   // 人又点开了主界面
            FakePipe host = new FakePipe();
            FakePort port = new FakePort("2");
            declare(port, mirror);
            ChannelScheduler.Pipeline handle = rebuilt(host, SUR, port);
            settle();
            hosts.add(host);
            ports.add(port);
            if (i < 2) {
                s.exit();   // 又退出了：放管线 —— 排着的那一份当场放完
                handle.retire();
                settle();
                assertTrue(host.gone);
            }
        }
        assertTrue("旧的那一份还在关：排着的一份都不开", ports.get(2).actions.isEmpty());
        assertEquals(ChannelRules.Condition.BRINGING_UP, s.snapshot().lanes.get(0).condition);
        assertTrue(s.snapshot().waiting != null);

        at(1_000);
        closed(surround);
        at(1_000 + ChannelRules.TICK_MS * 2);
        assertEquals(Arrays.asList("open [MIRROR]"), ports.get(2).actions);
        assertTrue(ports.get(0).actions.isEmpty() && ports.get(1).actions.isEmpty());
        assertTrue(pipe.gone);
        assertFalse(hosts.get(2).gone);
        assertEquals(1, lines("swapped").size());
        assertEquals(3, lines("resumed").size());
        assertFalse(s.closing());
    }

    /**
     * 退出当中换上的那一份（后台来要管线：排着，旧的那一份关完换上它）不再写一遍退出（审查 2026-10-30）：退出、车机要睡一件事一行，
     * 进程这一层写，不跟着哪一份管线。
     */
    @Test
    public void aPipelineSwappedInDuringExitWritesNoSecondExitLine() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        settle();
        exitApp();
        settle();
        FakePort background = new FakePort("2");
        declare(background, mirror);
        rebuilt(new FakePipe(), SUR, background);   // 退出当中后台来要管线：排着
        settle();
        at(200);
        closed(surround);   // 旧的那一份关完：换上它，接着只关
        at(200 + ChannelRules.TICK_MS * 2);
        assertEquals(1, lines("swapped").size());
        assertTrue(pipe.gone);
        assertEquals(Arrays.asList("exit null null"), lines("exit"));
        assertTrue(lines("retiring").isEmpty());
        assertTrue("退出时什么都不开", background.actions.isEmpty());
    }

    /**
     * 退出、关完、换上空的那一份以后（还没有新的管线）人又点开了主界面：「主界面又回来了」照写一行 —— 以前写给空的那一份，丢了
     * （审查 2026-10-30）。车机要睡那一行也一样不跟着管线。
     */
    @Test
    public void aReopenWithNoPipelineAttachedStillWritesResumed() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        settle();
        exitApp();
        settle();
        at(200);
        closed(surround);
        settle();
        assertTrue("换上了空的那一份", pipe.gone);
        assertTrue(lines("resumed").isEmpty());
        s.resumeAfterExit();
        settle();
        assertEquals(1, lines("resumed").size());
        host.sleeps = true;
        s.onNeedsChanged();
        settle();
        assertEquals(Arrays.asList("sleep null null"), lines("sleep"));
    }

    /**
     * 接着的那一份还没放掉就又接上了一份（建新的之前没放旧的，不该有）：新的那一份为准（审查 2026-10-30）—— 旧的那一份换下去，
     * 照放掉一样按次序关（原因「管线重建」），写一行；关完换上新的那一份、开它。以前新的那一份一直排着。
     */
    @Test
    public void aNewerPipelineRetiresTheAttachedOneThatWasNeverReleased() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        settle();
        FakePipe second = new FakePipe();
        FakePort s2 = new FakePort("2");
        declare(s2, mirror);
        rebuilt(second, SUR, s2);   // 旧的那一份没放掉
        settle();
        assertEquals(1, lines("superseded").size());
        assertEquals(Arrays.asList("close rebuild"), surround.actions);
        assertEquals(1, lines("retiring").size());
        assertTrue("旧的那一份还在关：新的一份排着", s2.actions.isEmpty());
        at(200);
        closed(surround);
        at(200 + ChannelRules.TICK_MS * 2);
        assertEquals(1, lines("swapped").size());
        assertTrue(pipe.gone);
        assertEquals(Arrays.asList("open [MIRROR]"), s2.actions);
        assertFalse(second.gone);
        assertEquals(1, lines("superseded").size());
    }

    /** 重建当中又重建：排着的那一份被新的一份替掉（还没交各路的也一样），只换上最新的那一份。 */
    @Test
    public void aRebuildDuringARebuildReplacesThePendingPipeline() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        settle();
        p.retire();
        FakePipe first = new FakePipe();
        rebuilt(first);   // 列相机还在做，各路还没交
        FakePipe second = new FakePipe();
        FakePort port = new FakePort("2");
        declare(port, mirror);
        rebuilt(second, SUR, port);
        assertTrue("被替掉的那一份当场放完", first.gone);
        settle();
        at(200);
        closed(surround);
        at(200 + ChannelRules.TICK_MS);
        assertEquals(Arrays.asList("open [MIRROR]"), port.actions);
        assertFalse(second.gone);
    }

    /**
     * 一路相机都没有的那一份管线（列相机出错）：接上、换下去都不用等谁 —— 换下去当场放完，新的一份当场接上、开起来。
     * 问它的那几样照「不在这一份里」答（在起来、能不能开都是否）。
     */
    @Test
    public void anEmptyPipelineComesAndGoesWithoutWaiting() {
        settle();
        p.retire();   // 没有开着的：当场换下去
        settle();
        assertTrue(pipe.gone);
        FakePipe emptyHost = new FakePipe();
        ChannelScheduler.Pipeline empty = rebuilt(emptyHost);
        empty.setLanes(new LinkedHashMap<>());
        claim(Holder.PREVIEW);
        settle();
        assertEquals(ChannelRules.Condition.BRINGING_UP, empty.condition(SUR));
        assertFalse(empty.cannotOpen(SUR));
        assertFalse(empty.releasedByDark(SUR));
        empty.retire();
        settle();
        assertTrue(emptyHost.gone);
        FakePort s2 = new FakePort("2");
        declare(s2, new Out(Kind.PREVIEW, "preview-2b"));
        rebuilt(new FakePipe(), SUR, s2);
        at(ChannelRules.TICK_MS);
        assertEquals(Arrays.asList("open [PREVIEW]"), s2.actions);
    }

    /**
     * 退出以后（主界面还没回来）后台来要管线（录像协调器、后视镜）：新建的那一份接上了也什么都不开 —— 退出时只关；退出的看门线程照样看到
     * 「关完」。人又点开了主界面（resumeAfterExit）才照常开。
     */
    @Test
    public void aPipelineBuiltDuringExitOpensNothingUntilTheMainScreenIsBack() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        settle();
        exitApp();
        settle();
        at(200);
        closed(surround);
        settle();
        assertTrue(pipe.gone);
        assertFalse("关完了：退出的看门线程照样往下走", s.closing());

        FakePort background = new FakePort("2");
        declare(background, mirror);
        ChannelScheduler.Pipeline late = rebuilt(new FakePipe(), SUR, background);
        at(60_000);
        assertTrue("退出时什么都不开", background.actions.isEmpty());
        assertFalse(s.closing());
        assertEquals(ChannelRules.Condition.BRINGING_UP, late.condition(SUR));

        s.resumeAfterExit();
        at(60_000 + ChannelRules.TICK_MS);
        assertEquals(Arrays.asList("open [MIRROR]"), background.actions);
        assertEquals(1, lines("resumed").size());
    }

    /**
     * 换上新的一份以后，相机服务为旧的那一份那一关说的话照样算我们的（第 1 步审查留给第 2 步：先到「那一轮结束」、后到「空闲」那一声）：
     * 旧的那一份后座舱关完、那一轮结束，换上新的一份，相机服务那一声「空闲」还没来 —— 新的那一路读到的还是旧的那一份开的时候那一句
     * 「被占用（我们开着）」，0.5 秒以内照算我们的，不认成别人拿着；「空闲」晚到也算我们引起的。过了 0.5 秒才不算。
     */
    @Test
    public void aWordAfterTheSwapForTheOldCloseIsStillOursWhenTheRoundEndedFirst() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        host.foregroundService = false;
        drop(Holder.PREVIEW);   // 谁都不要了：新的那一路换上来也不开
        p.retire();
        FakePort r2 = new FakePort("1");
        rebuilt(new FakePipe(), REAR, r2);
        settle();
        at(100);
        closed(surround);
        settle();
        at(200);
        closed(front);
        rear.closedNow();   // 后座舱关完、那一轮结束 —— 相机服务那一声「空闲」还在路上
        p.onClosed("1");
        settle();
        assertEquals(1, lines("swapped").size());
        assertTrue("刚关完（换下去的那一份关的）：相机服务那一声还算我们的", s.ourStepInFlight("1"));
        assertTrue("「被占用（我们开着）」那一句不认成别人拿着", lines("recognized").isEmpty());
        at(200 + ChannelRules.OWN_WORD_GRACE_MS - 1);
        assertTrue(s.ourStepInFlight("1"));
        say(r2, true, true);   // 「空闲」晚到，还在 0.5 秒以内：分类时算我们引起的
        s.wake();
        settle();
        at(200 + ChannelRules.OWN_WORD_GRACE_MS);
        assertFalse(s.ourStepInFlight("1"));
        assertTrue(lines("recognized").isEmpty());
        assertEquals(0, host.asks.size());
    }

    /**
     * 同上，另一种先后：「空闲」那一声先到、那一轮后结束 —— 旧的那一份后座舱那一次关卡住了，满 60 秒换上新的一份，它当孤儿留下：
     * 那一轮还在账本上（在关、手上没挂着设备），相机服务这时说的话算我们引起的；那一下终于返回了，之后 0.5 秒以内也还算。
     */
    @Test
    public void aWordAfterTheSwapForAnOrphansCloseIsOursBeforeAndJustAfterItReturns() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        host.foregroundService = false;
        drop(Holder.PREVIEW);
        p.retire();
        FakePort r2 = new FakePort("1");
        rebuilt(new FakePipe(), REAR, r2);
        settle();
        at(100);
        closed(surround);
        settle();
        at(200);
        closed(front);   // 后座舱那一次关一直不返回
        settle();
        at(100 + ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals(1, lines("swapped").size());
        assertTrue("孤儿那一轮还在关：我们的", s.ourStepInFlight("1"));
        say(r2, true, true);   // 「空闲」先到
        s.wake();
        settle();
        assertTrue(lines("recognized").isEmpty());

        at(120_000);
        rear.closedNow();   // 那一下终于返回了
        p.onClosed("1");
        settle();
        assertTrue("刚返回：还算我们的", s.ourStepInFlight("1"));
        assertTrue(pipe.gone);
        assertEquals(Arrays.asList("stray 1 closed"), lines("stray"));
        at(120_000 + ChannelRules.OWN_WORD_GRACE_MS);
        assertFalse(s.ourStepInFlight("1"));
    }

    /**
     * 熄屏记着的那几样跨过一次换上（项目所有者 2026-10-29：只有每一路摄像头的账本和熄屏记录跨重建保留）：环视留着、通道的决定是留着，
     * 旧的那一份关完、换上新的一份，接着「留着」、不再发一次决定；以前重建清零的照旧清零（被拿着从新的一份自己认出来那一刻算、
     * 上次问相机服务、手上那一步）。
     */
    @Test
    public void theDarkLatchAndVerdictSurviveASwapWhileTheRestStartsOver() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR);
        streaming(surround, recS);
        rear.declared.add(recR);
        host.absent("1");   // 车机拿着后座舱：认出来，被拿着
        host.sentryArmed();
        settle();
        assertEquals(CameraTaken.Verdict.TAKEN, view(s.snapshot(), REAR).mark);
        long askedAt = s.snapshot().askedAt;
        assertTrue(askedAt != 0);
        listenToVerdict();
        at(1_000);
        host.goDark();
        s.wake();
        settle();
        at(1_000 + ChannelRules.HOLD_MS);
        assertEquals(Arrays.asList("KEPT DARK_ONSET"), verdicts);

        p.retire();
        FakePipe second = new FakePipe();
        second.plan(SUR, REAR);
        FakePort s2 = new FakePort("2");
        FakePort r2 = new FakePort("1");
        declare(s2, recS);
        declare(r2, recR);
        ChannelScheduler.Pipeline next = rebuilt(second, SUR, s2, REAR, r2);
        settle();
        at(1_000 + ChannelRules.HOLD_MS + 200);
        closed(surround);
        settle();
        assertTrue(pipe.gone);
        assertEquals(ChannelRules.SurroundInDark.KEPT, s.snapshot().surroundInDark);
        assertEquals("不再发", Arrays.asList("KEPT DARK_ONSET"), verdicts);
        assertEquals(ChannelRules.SurroundInDark.KEPT, s.verdict());
        assertFalse(next.releasedByDark(SUR));
        assertEquals("换上的那一份接着录环视", Arrays.asList("open [RECORD]"), s2.actions);
        assertTrue("座舱照旧熄屏放开", next.releasedByDark(REAR));
        ChannelScheduler.LaneView taken = view(s.snapshot(), REAR);
        assertEquals("被拿着：新的一份排着时自己认出来的", CameraTaken.Verdict.TAKEN, taken.mark);
        assertEquals("从它认出来那一刻算，不接着旧的那一份", T0 + 1_000 + ChannelRules.HOLD_MS, taken.markSince);
        assertEquals(2, lines("recognized").size());
        assertEquals("上次问相机服务清零（黑着时座舱没人要，不问）", 0, s.snapshot().askedAt);
    }

    /**
     * 别的程序拿着一路座舱时管线重建（审查 2026-10-30）：排着的那一份照样认出它被拿着（ChannelRules.settleMarks），问它的和以前新建一份调度
     * 第一轮之后一样 —— 被拿着、拍照不等它、录像不为它备文件名定死了的录制器（录像那一侧听说了）。排着时不问相机服务；换上以后那一路
     * 照旧被拿着，接着问。
     */
    @Test
    public void aPendingPipelineAnswersTakenForALaneAnotherAppHolds() {
        Map<String, ChannelScheduler.CameraPort> lanes = new LinkedHashMap<>();
        lanes.put(SUR, surround);
        p.setLanes(lanes);   // 旧的那一份只有环视
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        settle();
        p.retire();   // 换车型：旧的那一份关环视（还没关完）
        host.busyNotOurs("1");   // 车机拿着后座舱
        FakePipe second = new FakePipe();
        FakePort s2 = new FakePort("2");
        FakePort r2 = new FakePort("1");
        declare(s2, pvS);
        declare(r2, pvR);
        ChannelScheduler.Pipeline next = rebuilt(second, SUR, s2, REAR, r2);
        settle();
        assertTrue("旧的那一份还在关：排着", s.snapshot().waiting != null);
        assertEquals(ChannelRules.Condition.TAKEN, next.condition(REAR));
        assertTrue("拍照不等它", next.excludedFromPhoto(REAR));
        assertFalse("录像不为它备录制器", RecordingLanes.keepsNamedRecorder(next.condition(REAR), next.cannotOpen(REAR),
                next.releasedByDark(REAR)));
        assertTrue("录像那一侧听说了", second.told.contains("condition " + REAR + " TAKEN"));
        assertEquals(Arrays.asList("recognized " + REAR + " busy"), lines("recognized"));
        assertEquals(ChannelRules.Condition.BRINGING_UP, next.condition(SUR));
        assertEquals("排着的不问相机服务", 0, host.asks.size());

        at(200);
        closed(surround);
        at(200 + ChannelRules.TICK_MS);
        assertEquals("换上了", Arrays.asList("open [PREVIEW]"), s2.actions);
        assertTrue(r2.actions.isEmpty());
        assertEquals(ChannelRules.Condition.TAKEN, next.condition(REAR));
        assertEquals("换上以后接着问", 1, host.asks.size());
        assertEquals(1, lines("recognized").size());
    }

    /**
     * 同上，那一台是换下去的那一份自己拿着的（审查 2026-10-30）：账本上它那一轮还没结束（后座舱那一次关卡住了），相机服务那一句
     * 「被占用（我们开着）」排着的那一份不认成别人拿着；换上以后它当孤儿、那一轮照旧占着，接着的那一份也不认 —— 被一轮占着就是我们的
     * （ChannelRules.camHeld）。
     */
    @Test
    public void aCameraTheRetiringPipelinesOrAnOrphansRoundHoldsIsNeverRecognized() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        p.retire();
        FakePipe second = new FakePipe();
        FakePort s2 = new FakePort("2");
        FakePort r2 = new FakePort("1");
        FakePort f2 = new FakePort("0");
        declare(s2, pvS);
        declare(r2, pvR);
        declare(f2, pvF);
        ChannelScheduler.Pipeline next = rebuilt(second, SUR, s2, REAR, r2, FRONT, f2);
        settle();
        at(100);
        closed(surround);
        settle();
        at(200);
        closed(front);   // 后座舱那一次关一直不返回
        settle();
        at(30_000);
        assertEquals("排着：换下去的那一份那一轮占着，不算被拿着", ChannelRules.Condition.BRINGING_UP, next.condition(REAR));
        assertTrue(lines("recognized").isEmpty());

        at(100 + ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals(1, lines("swapped").size());
        at(10 * 60_000);
        assertEquals("孤儿那一轮占着：也不认", ChannelRules.Condition.BRINGING_UP, next.condition(REAR));
        assertTrue(next.heldByClose(REAR));
        assertTrue(lines("recognized").isEmpty());
        assertTrue(r2.actions.isEmpty());
        assertEquals(1, s.snapshot().orphans.size());
    }

    // ================================================================= 能用相机

    /** 前台服务是后台起的、主界面不在前：不开、不计次数，写一行「现在用不了相机」；主界面回到前台才开。 */
    @Test
    public void nothingOpensUntilTheCameraIsUsable() {
        host.mainForeground = false;
        host.startedWhileVisible = false;
        claim(Holder.PHOTO);
        settle();
        at(10_000);
        assertTrue(surround.actions.isEmpty());
        assertEquals(1, lines("deferred front SERVICE_FROM_BACKGROUND").size());
        host.mainForeground = true;
        s.wake();
        settle();
        assertEquals("拍照：没开的路带出帧口开", Arrays.asList("open [SINK]"), surround.actions);
    }

    /**
     * 现在用不了相机时，该开的那一路关着、调度不开它：录像那一侧照 {@link ChannelScheduler#cannotOpen} 不把它算作在起来
     * （不然录像一直是「准备中」）。主界面回到前台、它开了，告诉录像那一侧一声 —— condition 没变（还是在起来）也说。
     */
    @Test
    public void aWantedLaneCannotOpenWhileTheCameraIsUnusableAndRecordingHearsWhenThatChanges() {
        host.mainForeground = false;
        host.startedWhileVisible = false;
        pipe.plan(SUR);
        claim(Holder.RECORDING);
        declare(surround, recS);
        settle();
        assertTrue(p.cannotOpen(SUR));
        assertFalse("没人要的那几路不算", p.cannotOpen(REAR));
        assertEquals(ChannelRules.Condition.BRINGING_UP, p.condition(SUR));
        told.clear();
        host.mainForeground = true;
        s.wake();
        settle();
        assertEquals(Arrays.asList("open [RECORD]"), surround.actions);
        assertFalse(p.cannotOpen(SUR));
        assertEquals(Arrays.asList("condition " + SUR + " BRINGING_UP"), told("condition"));
    }

    /** 开的时候报「用不了相机」：不计次数，记下来，等主界面回到前台。 */
    @Test
    public void anOpenFailingAsUnusableWaitsForTheMainScreen() {
        host.mainForeground = false;
        host.startedWhileVisible = true;
        claim(Holder.PHOTO);
        settle();
        assertEquals(Arrays.asList("open [SINK]"), surround.actions);
        at(100);
        openFailed(surround, CameraTaken.Reason.ofDeviceError(CameraDevice.StateCallback.ERROR_CAMERA_DISABLED));
        at(100 + ChannelRules.JUDGE_DELAY_MS);
        assertEquals(1, lines("judged front UNUSABLE OPEN").size());
        at(60_000);
        assertEquals("不重开、不计次数", 1, surround.actions.size());
        host.mainForeground = true;
        s.wake();
        settle();
        assertEquals(2, surround.actions.size());
    }

    /**
     * 开的时候报「用不了相机」，而主界面一直在前（隐私开关、策略禁用那种）：没什么可等，按普通故障走梯子 —— 12 秒后重开
     * （审查 2026-10-11：以前记下「用不了」、只等主界面「回到前台」那一下，主界面一直在前，就一直不开）。
     */
    @Test
    public void anOpenFailingAsUnusableWithTheMainScreenInFrontGoesOnTheLadder() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        declare(surround, mirror);
        settle();
        assertEquals(Arrays.asList("open [MIRROR]"), surround.actions);
        at(100);
        openFailed(surround, CameraTaken.Reason.ofAccess(CameraAccessException.CAMERA_DISABLED));
        at(100 + ChannelRules.JUDGE_DELAY_MS);
        assertEquals(1, lines("judged front UNUSABLE OPEN LADDER").size());
        assertTrue("不等主界面", lines("deferred").isEmpty());
        at(CameraLiveness.RETRY_GAP_MS - 1);
        assertEquals(1, surround.actions.size());
        at(CameraLiveness.RETRY_GAP_MS + ChannelRules.TICK_MS);
        assertEquals("关着的一路的重开：照开那样开", Arrays.asList("open [MIRROR]", "open [MIRROR]"), surround.actions);
    }

    /**
     * 丢了、报「用不了相机」（错误 3，后台，前台服务是主界面在前时起的），关的那几秒里人回到了主界面：判的时候已经不用等了 ——
     * 不计次数，当场重新开（审查 2026-10-11：以前记下「用不了」，可主界面「回到前台」那一下已经过去了，一直不开）。
     */
    @Test
    public void aLossJudgedUnusableAfterTheMainScreenCameBackOpensAgainAtOnce() {
        host.mainForeground = false;
        host.startedWhileVisible = true;
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        settle();
        surround.lose(now);
        p.onLossStarted("2", CameraDevice.StateCallback.ERROR_CAMERA_DISABLED);
        settle();
        at(2_000);
        host.mainForeground = true;   // 那一次关还没关完
        s.wake();
        settle();
        at(5_000);
        say(surround, true, true);
        surround.closedNow();
        p.onLossClosed("2", 5_000);
        at(5_000 + ChannelRules.JUDGE_DELAY_MS);
        assertEquals(1, lines("judged front UNUSABLE LOSS close=5000 MAIN_CAME_BACK").size());
        assertEquals(Arrays.asList("open [MIRROR]"), surround.actions);
        assertTrue(lines("deferred").isEmpty());
    }

    // ================================================================= 现场回放（diag_15_bb.txt，10-10；09-27 的冲突）

    /**
     * (a) 10-10 07:22：录着三路，车机拿走后座舱相机 1。关它的 5.4 秒里相机 0 和 2 一步都不动；关完判 TAKEN、记争用；
     * 之后只问、不开；07:31:22 报「1 空闲」之后至少等 3 秒，相机 1 单独开（带着它的录像输出）。
     */
    @Test
    public void replayA_rearCabinTakenWhileRecording() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        say(front, true, false);
        say(surround, true, false);
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        for (long t = 0; t < 5_409; t += 500) {
            at(t);
        }
        assertTrue("0 和 2 一步都不动", surround.actions.isEmpty() && front.actions.isEmpty());
        at(5_409);
        rear.closedNow();
        p.onLossClosed("1", 5_409);
        settle();
        assertTrue(lines("judged").isEmpty());
        at(5_709);
        assertEquals(Arrays.asList("judged left TAKEN LOSS close=5409"), lines("judged"));
        assertEquals("按丢的那一刻记争用（相机服务的钟）", Arrays.asList("1@" + (T0 + ELAPSED_AHEAD)), host.took);
        assertEquals(1, host.asks.size());

        long freeAt = 561_982;
        at(freeAt);
        say(rear, true, false);
        s.wake();
        settle();
        assertEquals(1, lines("released left TAKEN").size());
        assertTrue("被拿着的时候一次也不试开", rear.actions.isEmpty());
        at(freeAt + ChannelRules.QUIET_MS - 1);
        assertTrue(rear.actions.isEmpty());
        at(freeAt + ChannelRules.QUIET_MS);
        assertEquals(Arrays.asList("open [RECORD]"), rear.actions);
        assertTrue(surround.actions.isEmpty() && front.actions.isEmpty());
    }

    /** (b) 10-10 07:51:16：「1 空闲」比关返回早 5 ms —— 关完再判，它就是最后一句：普通故障，按救援的节奏重开。 */
    @Test
    public void replayB_freeSaidJustBeforeTheCloseReturnsIsOrdinary() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        settle();
        at(9_622);
        say(rear, true, true);
        at(9_627);
        rear.closedNow();
        p.onLossClosed("1", 9_627);
        at(9_927);
        assertEquals(Arrays.asList("judged left ORDINARY LOSS close=9627"), lines("judged"));
        assertTrue(host.took.isEmpty());
        assertEquals("关着的一路的重开：照开那样开", Arrays.asList("open [PREVIEW]"), rear.actions);
    }

    /**
     * (c) 10-10 08:56:57：我们开着相机 2 时相机服务报了一句「2 空闲」（乱报），之后 2 出错误 4 ——
     * 最后一句是空闲、错误 4 不属于被占用一类：普通故障，按救援的节奏重开（不因为 1 被拿着就判 BLOCKED）。
     */
    @Test
    public void replayC_error4AfterASpuriousFreeIsOrdinary() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        say(front, true, false);
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        say(surround, true, false);
        at(7_975);
        rear.closedNow();
        p.onLossClosed("1", 7_975);
        at(8_275);
        assertEquals(ChannelRules.Condition.TAKEN, p.condition(REAR));

        at(14_114);
        surround.lose(now);
        p.onLossStarted("2", DEVICE_ERROR);
        at(15_270);
        surround.closedNow();
        p.onLossClosed("2", 1_156);
        at(15_570);
        assertEquals(1, lines("judged front ORDINARY LOSS").size());
        assertEquals("关着的一路的重开：照开那样开", Arrays.asList("open [RECORD]"), surround.actions);
        assertTrue(rear.actions.isEmpty());
    }

    /** (d) 10-10 09:00:18：1 放开和一句假的「2 空闲」同时到 → 安静满 3 秒后，1 单独开。 */
    @Test
    public void replayD_aReleaseTogetherWithASpuriousFreeOpensTheRearAloneAfterQuiet() {
        claim(Holder.RECORDING);
        pipe.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(front, recF);
        rear.declared.add(recR);
        host.busyNotOurs("1");
        settle();
        assertEquals(1, lines("recognized left busy").size());
        at(180_000);
        say(rear, true, false);
        say(surround, true, false);
        s.wake();
        settle();
        assertEquals(1, lines("released left TAKEN").size());
        at(180_000 + ChannelRules.QUIET_MS - 1);
        assertTrue(rear.actions.isEmpty());
        at(180_000 + ChannelRules.QUIET_MS);
        assertEquals(Arrays.asList("open [RECORD]"), rear.actions);
        opened(rear);
        frame(rear);
        at(190_000);
        assertEquals("只开了 1", Arrays.asList("open [RECORD]"), rear.actions);
        assertTrue(surround.actions.isEmpty() && front.actions.isEmpty());
        assertTrue("主动认出的不记争用", host.took.isEmpty());
    }

    /**
     * (e) 09-27 的冲突：别的程序拿走相机 1，相机服务几毫秒内把我们的相机 2 断开（1 和 2 同时只能开一路）。
     * 等两路都关完一起判、先判 TAKEN：1 TAKEN，2 BLOCKED，一次也不试开；两路都空出来以后，先开环视，再开后座舱。
     */
    @Test
    public void replayE_aHandoverConflictBlocksTheSurroundUntilBothAreFree() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        rear.lose(now);
        p.onLossStarted("1", DISCONNECTED);
        surround.lose(now);
        p.onLossStarted("2", DISCONNECTED);
        at(50);
        say(surround, true, true);
        at(1_000);
        surround.closedNow();
        p.onLossClosed("2", 1_000);
        at(1_300);
        assertTrue("还有丢失没关完：等它一起判", lines("judged").isEmpty());
        int rounds = pipe.rounds;
        at(5_399);
        assertTrue("等后座舱关完的这几秒不空转（环视早到点了，按 2 秒一轮等）：" + (pipe.rounds - rounds),
                pipe.rounds - rounds <= 5);
        at(5_400);
        rear.closedNow();
        p.onLossClosed("1", 5_400);
        at(5_700);
        assertEquals(Arrays.asList("judged left TAKEN LOSS close=5400", "judged front BLOCKED LOSS close=1000"),
                lines("judged"));
        assertEquals("BLOCKED 不记争用", Arrays.asList("1@" + (T0 + ELAPSED_AHEAD)), host.took);
        host.answer();
        assertEquals("被挡着的那一路问出来是空的：不是放开，等挡着它的那一路",
                Arrays.asList("asked front 1 important blocked"), lines("asked front"));
        assertEquals(Arrays.asList("asked left 1 important"), lines("asked left"));
        at(60_000);
        assertTrue("被拿着的时候一次也不试开", surround.actions.isEmpty() && rear.actions.isEmpty());

        say(rear, true, false);
        s.wake();
        settle();
        assertEquals(2, lines("released").size());
        at(60_000 + ChannelRules.QUIET_MS);
        assertEquals(Arrays.asList("open [PREVIEW]"), surround.actions);
        assertTrue("环视出画面之前后座舱不开", rear.actions.isEmpty());
        at(60_000 + ChannelRules.QUIET_MS + 400);
        opened(surround);
        frame(surround);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), rear.actions);
        assertTrue(front.actions.isEmpty());
    }

    // ================================================================= 拍照照调度的账

    /** 被拿着只记在调度里：拍照照调度的账不拍被拿着的那一路，在出画面的照拍。 */
    @Test
    public void photoLeavesOutTheLaneTheSchedulerMarksTaken() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        rear.declared.add(pvR);
        assertFalse(p.excludedFromPhoto(REAR));
        host.absent("1");
        settle();
        assertEquals(ChannelRules.Condition.TAKEN, p.condition(REAR));
        assertTrue(p.excludedFromPhoto(REAR));
        assertFalse(p.excludedFromPhoto(SUR));
    }

    // ================================================================= 黑匣子：一件事一行

    /** 「等」只在原因变了时写；登记表只在变了时写。 */
    @Test
    public void waitsAndNeedsAreWrittenOnlyWhenTheyChange() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        declare(rear, pvR);
        say(front, true, false);   // 别人引起的变化：挡安静
        settle();
        at(500);
        at(1_000);
        at(2_000);
        assertEquals(1, lines("waiting QUIET left").size());
        at(ChannelRules.QUIET_MS);
        assertEquals(Arrays.asList("open [PREVIEW]"), rear.actions);
        assertEquals(Arrays.asList("needs [PREVIEW] []"), lines("needs"));
        claim(Holder.PREVIEW);
        settle();
        assertEquals("登记表没变：不再写", 1, lines("needs").size());
    }

    /** 开之前等录像输出、没等完录像就放了：很久以后为预览开它，不写一行从没发生过的「等录像输出：…」。 */
    @Test
    public void aRecordWaitThatEndedOtherwiseLeavesNoLine() {
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        settle();
        assertEquals(1, lines("waiting RECORD_OUTPUT front").size());
        at(500);
        drop(Holder.RECORDING);
        settle();
        at(60_000);
        declare(surround, pvS);
        claim(Holder.PREVIEW);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), surround.actions);
        assertTrue(lines("recordWaitEnded").isEmpty());
    }

    /**
     * 「现在用不了相机，先不开 X」只在变了时写：别的路做了一步（改输出）、那几轮在等它，之后也不再写一遍（审查 2026-10-11）。
     */
    @Test
    public void theDeferredLineIsNotRewrittenAfterAnotherLanesStep() {
        host.mainForeground = false;
        host.startedWhileVisible = false;
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        pipe.plan(SUR, REAR);
        claim(Holder.MIRROR);
        claim(Holder.RECORDING);
        streaming(surround, mirror);
        declare(rear, recR);
        settle();
        assertEquals(Arrays.asList("deferred left SERVICE_FROM_BACKGROUND"), lines("deferred"));
        declare(surround, recS);
        s.wake();
        settle();
        assertEquals(Arrays.asList("apply [MIRROR, RECORD]"), surround.actions);
        at(300);
        configured(surround, mirror, recS);
        frame(surround);
        settle();
        at(5_000);
        assertEquals("同一句不再写", 1, lines("deferred").size());
    }

    /**
     * 开不起来的那一路照旧记进「按次序开相机」，一轮一行 —— 判完（300 ms）接着开下一路，还在这一轮里
     * （审查 2026-10-11：以前不记它、判的那一下就把这一轮断了，旧的分析脚本看到的是拆开的、缺一路的两行）。
     */
    @Test
    public void aLaneThatFailsToOpenStaysInTheOpenTrail() {
        claim(Holder.PREVIEW);
        declare(surround, pvS);
        declare(rear, pvR);
        declare(front, pvF);
        settle();
        at(300);
        opened(surround);
        frame(surround);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]"), rear.actions);
        at(500);
        openFailed(rear, CameraTaken.Reason.ofNotListed());
        at(500 + ChannelRules.JUDGE_DELAY_MS);
        assertEquals(1, lines("judged left TAKEN OPEN").size());
        assertTrue("判完接着开：还在这一轮里", lines("openTrail").isEmpty());
        assertEquals(Arrays.asList("open [PREVIEW]"), front.actions);
        at(1_000);
        opened(front);
        frame(front);
        settle();
        assertEquals(Arrays.asList("openTrail [front, left, back]"), lines("openTrail"));
    }

    // ================================================================= 诊断：关相机那两行、快照（大纲 §2 阶段 8）

    /**
     * 关相机那一行末尾带上会话里带走的留着的输出、各留了多久；每关一路再交一份这一次开着的小结（重配了几次、各为什么）——
     * 停录、关后视镜都不动通道（留着的输出），保持到点关的时候一起带走。卡顿现场那一份分类也交给了相机层。
     */
    @Test
    public void aCloseCarriesTheKeptOutputsAndSumsUpTheOpen() {
        Out mirror = new Out(Kind.MIRROR, "mirror-2");
        claim(Holder.RECORDING);
        pipe.plan(SUR);
        streaming(surround, recS);
        settle();
        declare(surround, mirror);
        claim(Holder.MIRROR);
        settle();
        assertEquals(Arrays.asList("apply [MIRROR, RECORD]"), surround.actions);
        at(300);
        configured(surround, mirror, recS);
        frame(surround);
        settle();
        assertEquals(ChannelRules.OutState.WANTED, surround.states.get(recS).state);

        at(1_000);
        drop(Holder.RECORDING);   // 停录：录制器停放，录像输出留在会话里
        settle();
        assertEquals("停录不动通道：留着", ChannelRules.OutState.KEPT, surround.states.get(recS).state);
        assertEquals(T0 + 1_000, surround.states.get(recS).since);
        at(2_000);
        drop(Holder.MIRROR);   // 后视镜关了：亮屏、前台服务在，环视保持 30 秒
        settle();
        assertEquals(1, surround.actions.size());

        at(2_000 + ChannelRules.HOLD_MS);
        assertEquals(Arrays.asList("apply [MIRROR, RECORD]", "close hold-over"), surround.actions);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("closeRound front |  | carried front MIRROR 30000, front RECORD 31000"),
                lines("closeRound"));
        assertEquals(Arrays.asList("openSpan front open=32000 added=1 dead=0 rescued=0 removedOnly=0"),
                lines("openSpan"));
        assertTrue("关了：分类清空", surround.states.isEmpty());
    }

    /** 一路一没人要就关（没有前台服务、不保持）：会话里的输出是跟着关的，不算留着；小结照记。 */
    @Test
    public void anOutputDroppedInTheRoundThatClosesIsNotCountedAsKept() {
        host.foregroundService = false;
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        at(5_000);
        drop(Holder.PREVIEW);
        settle();
        assertEquals(Arrays.asList("close unwanted"), surround.actions);
        at(5_100);
        closed(surround);
        settle();
        at(5_200);
        closed(rear);
        closed(front);
        settle();
        assertEquals(Arrays.asList("closeRound front | left, back"), lines("closeRound"));
        assertEquals(Arrays.asList("openSpan front open=5000 added=0 dead=0 rescued=0 removedOnly=0",
                "openSpan left open=5100 added=0 dead=0 rescued=0 removedOnly=0",
                "openSpan back open=5100 added=0 dead=0 rescued=0 removedOnly=0"), lines("openSpan"));
    }

    /**
     * 诊断报告读的快照：每一轮最后记一份 —— 每一路会话里的输出算要的、留着的、死的，从什么时候起；被拿着、不在；
     * 最近一次问相机服务问成了没有、它怎么说；手上那一步。
     */
    @Test
    public void theSnapshotShowsOutputsMarksAnswersAndTheStepInHand() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS, recS);   // 停录后留着的录像输出
        streaming(front, pvF);
        rear.declared.add(pvR);
        host.absent("1");
        settle();
        ChannelScheduler.Snapshot snap = s.snapshot();
        ChannelScheduler.LaneView sur = view(snap, SUR);
        assertEquals(2, sur.outputs.size());
        assertEquals(ChannelRules.OutState.WANTED, sur.outputs.get(0).state);
        assertEquals(ChannelRules.OutState.KEPT, sur.outputs.get(1).state);
        assertEquals(T0, sur.outputs.get(1).since);
        ChannelScheduler.LaneView left = view(snap, REAR);
        assertEquals(CameraTaken.Verdict.TAKEN, left.mark);
        assertTrue(left.absent);
        assertTrue(left.shouldOpen);
        assertEquals(T0, snap.askedAt);
        assertEquals("问了，还没答回来", 0, snap.answeredAt);
        assertTrue(snap.usable);
        assertTrue("手上没有一步", snap.step == null);

        at(500);
        host.answer();
        snap = s.snapshot();
        assertEquals(T0 + 500, snap.answeredAt);
        assertTrue("收到了回放：问成了", snap.answered);
        assertEquals(1, view(snap, REAR).lastAnswer.n);
        assertTrue(view(snap, REAR).lastAnswer.absent);

        // 环视的预览画布被系统收走：死的，调度马上摘 —— 手上那一步就是它
        at(1_000);
        surround.dead.add(pvS);
        surround.declared.remove(pvS);
        s.wake();
        settle();
        snap = s.snapshot();
        assertEquals(ChannelRules.OutState.DEAD, view(snap, SUR).outputs.get(0).state);
        assertEquals(T0 + 1_000, view(snap, SUR).outputs.get(0).since);
        assertEquals(ChannelRules.Move.APPLY, snap.step.move);
        assertEquals(Arrays.asList(SUR), snap.step.keys);
    }

    // ================================================================= 帮手：扮演相机层、登记表

    /** 一个调度（进程里那一个），看测试这一本账（{@link #rounds}）。 */
    private ChannelScheduler scheduler() {
        return new ChannelScheduler(() -> now, poster, host, rounds);
    }

    /**
     * 一份管线接上 {@code to} 这个调度（MultiCameraManager 的构造）：它的录像那一侧照记（{@code told}，{@code tell} 为假的不记 ——
     * 第二份管线的录像那一侧另记在 {@link FakePipe#told}）。
     */
    private ChannelScheduler.Pipeline register(ChannelScheduler to, FakePipe host, boolean tell) {
        FakeRecording recording = new FakeRecording(tell ? told : host.told);
        ChannelScheduler.Pipeline handle = to.register(host, recording);
        recording.of = handle;
        return handle;
    }

    /** 一份新的管线接上来（管线重建：旧的那一份先放掉），交它这几路（{@code ports}：key、相机交替给）。 */
    private ChannelScheduler.Pipeline rebuilt(FakePipe host, Object... ports) {
        ChannelScheduler.Pipeline handle = register(s, host, false);
        if (ports.length > 0) {
            Map<String, ChannelScheduler.CameraPort> lanes = new LinkedHashMap<>();
            for (int i = 0; i < ports.length; i += 2) {
                FakePort port = (FakePort) ports[i + 1];
                port.pipe = handle;
                lanes.put((String) ports[i], port);
            }
            handle.setLanes(lanes);
        }
        return handle;
    }

    /** 退出（MainActivity.exitApp）：先告诉调度退出，再放管线（这一份换下去）。 */
    private void exitApp() {
        s.exit();
        p.retire();
    }

    /** 相机层报给这一路那一份管线的把手（{@link FakePort#pipe}；没设的是 setUp 那一份）。 */
    private ChannelScheduler.Pipeline of(FakePort port) {
        return port.pipe != null ? port.pipe : p;
    }

    /** 快照里的那一路。 */
    private static ChannelScheduler.LaneView view(ChannelScheduler.Snapshot snap, String key) {
        for (ChannelScheduler.LaneView lane : snap.lanes) {
            if (lane.key.equals(key)) {
                return lane;
            }
        }
        fail("快照里没有 " + key);
        return null;
    }

    /** 把钟拨到 T0 + ms，到点的任务按先后跑。 */
    private void at(long ms) {
        poster.runUntil(T0 + ms);
    }

    /** 跑完此刻已经到点的任务（不拨钟）。 */
    private void settle() {
        poster.runUntil(now);
    }

    private void claim(Holder holder) {
        host.holders.add(holder);
        s.onNeedsChanged();
    }

    /** 配置里只开环视；管线另外建了后座舱（超级镜子「后视 + 后座舱」要它，不算启用的）。 */
    private void surroundOnly() {
        Map<String, ChannelScheduler.CameraPort> lanes = new LinkedHashMap<>();
        lanes.put(REAR, rear);
        lanes.put(SUR, surround);
        p.setLanes(lanes, new LinkedHashSet<>(Arrays.asList(SUR)));
    }

    /** 重新交一次各路：环视建着、只有两路座舱启用（配置里只开座舱）。 */
    private void cabinsOnly() {
        Map<String, ChannelScheduler.CameraPort> lanes = new LinkedHashMap<>();
        lanes.put(FRONT, front);
        lanes.put(REAR, rear);
        lanes.put(SUR, surround);
        p.setLanes(lanes, new LinkedHashSet<>(Arrays.asList(REAR, FRONT)));
    }

    private void drop(Holder holder) {
        host.holders.remove(holder);
        s.onNeedsChanged();
    }

    private void declare(FakePort port, Out... outs) {
        port.declared.addAll(Arrays.asList(outs));
    }

    /** 一路早就开着、在出画面，会话里带着这几样（出帧口之外的都声明着）；开的时候相机服务说过「被占用（我们开着）」。 */
    private void streaming(FakePort port, Out... outs) {
        port.openedRound();
        port.deviceOpen = true;
        port.holds = true;
        port.session = new SingleCamera.SessionOutputs(1, new LinkedHashSet<>(Arrays.asList(outs)), now - 60_000);
        for (Out out : outs) {
            if (out.kind != Kind.SINK) {
                port.declared.add(out);
            }
        }
        port.firstFrameAt = now - 59_000;
        port.progressAgeMs = 0;
        host.words.put(port.id, new CameraAvailabilityWatch.Word(false, false, true, elapsed(now - 60_000)));
    }

    /** 开成了，会话按它这一步要的那一组配好了。 */
    private void opened(FakePort port) {
        port.openedRound();
        port.deviceOpen = true;
        port.opening = false;
        port.holds = true;
        configured(port, port.lastOutputs.toArray(new Out[0]));
        of(port).onOpened(port.id);
    }

    private void configured(FakePort port, Out... outs) {
        port.generation++;
        port.session = new SingleCamera.SessionOutputs(port.generation, new LinkedHashSet<>(Arrays.asList(outs)), now);
        port.firstFrameAt = 0;
        port.busySince = 0;
        port.progressAgeMs = 0;
        of(port).onCameraEvent();
    }

    private void frame(FakePort port) {
        if (port.firstFrameAt == 0) {
            port.firstFrameAt = now;
        }
        port.progressAgeMs = 0;
        of(port).onCameraEvent();
    }

    /** 开不起来（相机层照 SingleCamera：那一轮先结束、再报）：这一步没开成，账本上这一台空了。 */
    private void openFailed(FakePort port, CameraTaken.Reason reason) {
        port.failOpen();
        of(port).onOpenFailed(port.id, reason);
    }

    /** 我们关的那一路关完了；相机服务照例为这一关说一句「空闲」（我们引起的）。 */
    private void closed(FakePort port) {
        port.closedNow();
        host.words.put(port.id, new CameraAvailabilityWatch.Word(true, false, true, elapsed(now)));
        of(port).onClosed(port.id);
    }

    /** 相机服务对这一路说了一句；不是我们引起的就挡「安静」。 */
    private void say(FakePort port, boolean free, boolean ours) {
        host.words.put(port.id, new CameraAvailabilityWatch.Word(free, false, ours, elapsed(now)));
        if (!ours) {
            host.lastOthersChangeAt = elapsed(now);
        }
    }

    private long elapsed(long t) {
        return t + ELAPSED_AHEAD;
    }

    /** 「front gap=0 same」；中途我们自己动了它的带上是哪一步、从开始量起多久（「front gap=0 same ours=CLOSE@10000」）。 */
    private static String othersText(List<ChannelScheduler.Other> others) {
        List<String> parts = new ArrayList<>();
        for (ChannelScheduler.Other other : others) {
            parts.add(other.key + " gap=" + other.gapMs + (other.sessionChanged ? " changed" : " same")
                    + (other.ours == null ? "" : " ours=" + other.ours + "@" + other.oursAtMs));
        }
        return String.join(", ", parts);
    }

    private List<String> told(String prefix) {
        List<String> out = new ArrayList<>();
        for (String line : told) {
            if (line.startsWith(prefix)) {
                out.add(line);
            }
        }
        return out;
    }

    private List<String> lines(String prefix) {
        List<String> out = new ArrayList<>();
        for (String line : journal) {
            if (line.startsWith(prefix)) {
                out.add(line);
            }
        }
        return out;
    }

    private static String kinds(Set<Out> outs) {
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        for (Out out : outs) {
            kinds.add(out.kind);
        }
        return kinds.toString();
    }

    // ================================================================= 假的队列、相机层、全局事实、黑匣子

    private final class FakePoster implements ChannelScheduler.Poster {
        private final List<Object[]> tasks = new ArrayList<>();
        private long seq;
        int posts;

        @Override
        public void post(Runnable task) {
            posts++;
            tasks.add(new Object[]{now, seq++, task});
        }

        @Override
        public void postDelayed(Runnable task, long delayMs) {
            tasks.add(new Object[]{now + delayMs, seq++, task});
        }

        @Override
        public void cancel(Runnable task) {
            tasks.removeIf(t -> t[2] == task);
        }

        /** 按到点的先后（同一刻按排进来的先后）跑到 {@code target}，然后把钟停在那里。 */
        void runUntil(long target) {
            for (int guard = 0; guard < 100_000; guard++) {
                Object[] next = null;
                for (Object[] t : tasks) {
                    long due = (Long) t[0];
                    if (due <= target && (next == null || due < (Long) next[0]
                            || due == (Long) next[0] && (Long) t[1] < (Long) next[1])) {
                        next = t;
                    }
                }
                if (next == null) {
                    now = Math.max(now, target);
                    return;
                }
                tasks.remove(next);
                now = Math.max(now, (Long) next[0]);
                ((Runnable) next[2]).run();
            }
            fail("队列停不下来");
        }
    }

    /**
     * 一路相机：动作记下来（「open [PREVIEW]」「close unwanted」；救援重开的开那一半是「reopen [PREVIEW]」），事实由测试改。
     * 一轮一轮照 SingleCamera 的样子记在测试那一本账上（{@link #rounds}，{@link CameraRound}）：开下达一轮（账本上这一台有一轮还没结束就不下达
     * —— 记一次，规则说开了才走到这里，不该有）、openCamera 当场返回在等回调；开好了挂上；关的那一下下达关、摘下；关完、丢了之后关完、
     * 开不起来，这一轮结束、从账上划掉。救援的重开是两轮（第 2b 步）：关的那一半就是一次关（关完由测试说，{@link #closedNow}），
     * 开的那一半就是一次开（带着 {@link SingleCamera.OpenKind#RESCUE}，{@link #kinds}）。
     */
    private final class FakePort implements ChannelScheduler.CameraPort {
        final String id;
        /** 哪一路（账本上那一轮记的）：照相机编号认 —— 2 环视、1 后座舱、0 前座舱。 */
        final String key;
        final List<String> actions = new ArrayList<>();
        final List<String> drops = new ArrayList<>();
        final Set<Out> declared = new LinkedHashSet<>();
        final Set<Out> dead = new LinkedHashSet<>();
        Set<Out> lastOutputs = new LinkedHashSet<>();
        /** 关、丢的那一下会话快照清了，设备还没关完时相机还带着的那几样（SingleCamera.stillCarries 的 carried）。 */
        Set<Out> closingWith = new LinkedHashSet<>();
        boolean deviceOpen;
        boolean holds;
        long busySince;
        SingleCamera.SessionOutputs session = SingleCamera.SessionOutputs.NONE;
        long firstFrameAt;
        long progressAgeMs;
        int generation;
        /** 开发出去了、设备还没回来（手上那一轮在开）。 */
        boolean opening;
        /** 开被合并掉了几次（已经开着、手上那一轮还在途时：不设时限）。 */
        int refused;
        /** 开被账本挡掉了几次（SingleCamera.heldByAnotherRound：规则照账本本来就不让它开，不该有）。 */
        int heldRefusals;
        /** 每一次下达了的开是哪一种（{@link SingleCamera.OpenKind}），按先后。 */
        final List<SingleCamera.OpenKind> kinds = new ArrayList<>();
        /** 每一次下达了的开那一轮是第几轮（账本给的轮次号），按先后。 */
        final List<Integer> roundNumbers = new ArrayList<>();
        /** 这一份的那一轮（手上的，或者下达了关、还没结束的）；没有是 null。 */
        CameraRound live;
        /** {@link #live} 还在手上（没下达关）。 */
        boolean attached;
        /** 那一轮那一次 openCamera、它还在等回调没有；挂着的那一台。 */
        CameraRound.Call call;
        boolean pending;
        Object device;
        /** 这一次关、丢了之后那一次关从什么时候起（关完时记 lastClose）；0 = 没有。 */
        long closeStartedAt;
        /** 最近一次关完（SingleCamera.lastClose）；没关过是 null。 */
        SingleCamera.LastClose lastClose;
        /** 眼下这份会话配好以来相机回了什么（SingleCamera.sessionCounts）。 */
        SessionCounts.Snapshot counts = SessionCounts.NONE;
        /** 最后一次收到画面（SingleCamera.lastResultAt）；0 = 没有过。 */
        long lastResultAt;
        /** 一共失败了几次请求、丢了几次缓冲（SingleCamera.captureFailures / lostBuffers）。 */
        long failures;
        long lost;
        /** 量断帧时交回的最长断帧（SingleCamera.longestFrameGapSince）。 */
        long gapMs;
        /** 调度最后一次交来的输出分类（卡顿现场用的那一份）。 */
        Map<Out, ChannelScheduler.OutputState> states = new HashMap<>();
        /** 这一路是哪一份管线的（相机层的报告交给它的把手）；null = setUp 那一份。 */
        ChannelScheduler.Pipeline pipe;

        FakePort(String id) {
            this.id = id;
            this.key = "2".equals(id) ? SUR : "1".equals(id) ? REAR : "0".equals(id) ? FRONT : SUR;
        }

        /** 下达一轮（SingleCamera.beginRound）：账本上这一台有一轮还没结束就不下达。openCamera 当场返回，这一次在等回调。 */
        private boolean issue() {
            CameraRound r = rounds.issue(id, this, key, now);
            if (r == null) {
                heldRefusals++;
                return false;
            }
            live = r;
            attached = true;
            call = r.beginCall(now);
            r.returned(call, now);
            pending = true;
            return true;
        }

        /** 开好了、挂上（SingleCamera.deviceOpened：ADOPT）。手上没有一轮的（测试直接摆出来的）先下达一轮。 */
        void openedRound() {
            if (!attached && !issue()) {
                return;
            }
            if (device != null) {
                return;   // 已经挂着一台（同一路又摆一次「开着」）
            }
            if (!pending) {
                call = live.beginCall(now);
                live.returned(call, now);
            }
            pending = false;
            device = new Object();
            FakePort other = service.put(id, this);
            if (other != null && other != this && other.device != null) {
                fail("相机服务里相机 " + id + " 有两个句柄：" + other.key + " 那一份还拿着，" + key + " 又开好了一台");
            }
            assertEquals(CameraRound.Action.ADOPT, live.opened(call, device, now));
        }

        /** 开不起来（还没开好就报错：SingleCamera.failOpenedCall）：那个设备对象关掉，这一轮结束。 */
        void failOpen() {
            busySince = 0;
            holds = false;
            opening = false;
            if (live != null && pending) {
                Object failed = new Object();
                live.failed(call, failed, false, now);
                live.closed(failed, now);
                pending = false;
            }
            endRound();
        }

        /**
         * 这一轮手上的都做完了：在等的回调来了（下达了关的，晚到的设备就地关）、在关的设备关完、关相机那一下做完 —— 结束了从账上划掉
         * （SingleCamera.finishRound：结束的那一声先到主线程）。卡住的那种测试不调它。
         */
        private void endRound() {
            CameraRound r = live;
            if (r == null) {
                return;
            }
            if (pending) {
                Object late = new Object();
                r.opened(call, late, now);
                r.closed(late, now);
                pending = false;
            }
            if (device != null) {
                r.closed(device, now);
            }
            device = null;
            if (r.cancelled()) {
                r.closeDone(now);
            }
            assertTrue("这一轮该结束了", r.takeEnd());
            assertTrue(rounds.end(r, now));
            live = null;
            attached = false;
        }

        /** 丢了：设备、会话从字段上摘下，那一次关在途（SingleCamera.deviceGone：LOST，在丢）。 */
        void lose(long at) {
            if (attached && device != null) {
                assertEquals(CameraRound.Action.LOST, live.failed(call, device, false, at));
            }
            closeStartedAt = at;
            deviceOpen = false;
            opening = false;
            closingWith = new LinkedHashSet<>(session.outputs);
            session = SingleCamera.SessionOutputs.NONE;
            firstFrameAt = 0;
            busySince = at;
            holds = true;
        }

        /** 关完了（我们关的、丢了之后那一次关）：这一轮结束。 */
        void closedNow() {
            deviceOpen = false;
            opening = false;
            holds = false;
            busySince = 0;
            session = SingleCamera.SessionOutputs.NONE;
            closingWith.clear();
            firstFrameAt = 0;
            progressAgeMs = 0;
            if (closeStartedAt != 0) {
                lastClose = new SingleCamera.LastClose(now, now - closeStartedAt, "test");
            }
            closeStartedAt = 0;
            endRound();
        }

        private boolean start(String what, Set<Out> outputs) {
            actions.add(what + " " + kinds(outputs));
            lastOutputs = new LinkedHashSet<>(outputs);
            busySince = now;
            holds = true;
            return true;
        }

        @Override
        public String cameraId() {
            return id;
        }

        /**
         * 照 SingleCamera.open：开着、手上那一轮在途就合并掉；账本上这一台有一轮还没结束就不开（断言）。救援重开的开那一半
         * （{@link SingleCamera.OpenKind#RESCUE}）记成「reopen」：和照常的开同一条路，只是那一种。
         */
        @Override
        public boolean open(Set<Out> outputs, SingleCamera.OpenKind kind) {
            if (deviceOpen || attached) {
                refused++;
                return false;
            }
            if (!issue()) {
                return false;
            }
            opening = true;
            kinds.add(kind);
            roundNumbers.add(live.number);
            return start(kind == SingleCamera.OpenKind.RESCUE ? "reopen" : "open", outputs);
        }

        @Override
        public boolean applyOutputs(Set<Out> outputs) {
            return start("apply", outputs);
        }

        @Override
        public boolean rebuildSession(Set<Out> outputs) {
            return start("rebuild", outputs);
        }

        @Override
        public void close(String why) {
            actions.add("close " + why);
            if (attached) {
                // 照 SingleCamera.closeCamera：给手上这一轮下达关、摘下（挂着的那一台交给关相机那一下，关完才算）
                live.closeRequested(now);
                attached = false;
                closeStartedAt = now;
            }
            deviceOpen = false;
            opening = false;
            closingWith = new LinkedHashSet<>(session.outputs);
            session = SingleCamera.SessionOutputs.NONE;
            busySince = now;
            holds = true;
        }

        @Override
        public void resolveSize() {
        }

        @Override
        public boolean sizeResolved() {
            return true;
        }

        @Override
        public boolean deviceOpen() {
            return deviceOpen;
        }

        @Override
        public boolean holdsOrIsOpening() {
            return holds;
        }

        @Override
        public long busySince() {
            return busySince;
        }

        @Override
        public CameraRound round() {
            return attached ? live : null;
        }

        @Override
        public boolean owns(CameraRound round) {
            return round != null && round.owner == this;
        }

        @Override
        public SingleCamera.LastClose lastClose() {
            return lastClose;
        }

        @Override
        public SessionCounts.Snapshot sessionCounts() {
            return counts;
        }

        @Override
        public long lastResultAt() {
            return lastResultAt;
        }

        @Override
        public long captureFailures() {
            return failures;
        }

        @Override
        public long lostBuffers() {
            return lost;
        }

        @Override
        public String describeBusy() {
            return "closing-lost";
        }

        @Override
        public SingleCamera.SessionOutputs sessionOutputs() {
            return session;
        }

        @Override
        public boolean stillCarries(Out out) {
            return session.carries(out) || closingWith.contains(out);
        }

        @Override
        public Set<Out> declaredOutputs() {
            return new LinkedHashSet<>(declared);
        }

        @Override
        public Set<Out> deadInSession() {
            Set<Out> out = new LinkedHashSet<>(dead);
            out.retainAll(session.outputs);
            return out;
        }

        @Override
        public long firstFrameAt() {
            return firstFrameAt;
        }

        @Override
        public long progressAgeMs() {
            return progressAgeMs;
        }

        @Override
        public int sessionGeneration() {
            return generation;
        }

        @Override
        public long longestFrameGapSince(long since) {
            return gapMs;
        }

        @Override
        public String lastErrorName() {
            return null;
        }

        @Override
        public void dropped(Out out, SingleCamera.Drop why) {
            drops.add(out.kind + " " + why);
        }

        @Override
        public void outputStates(Map<Out, ChannelScheduler.OutputState> states) {
            this.states = states;
        }
    }

    /** 进程这一层的事实：登记表、亮熄屏、前台服务、相机服务那几句（含深睡的钟）；问相机服务的回调排着，测试来答。 */
    private final class FakeHost implements ChannelScheduler.Facts {
        final Set<Holder> holders = EnumSet.noneOf(Holder.class);
        final Map<String, CameraAvailabilityWatch.Word> words = new HashMap<>();
        final List<Consumer<CameraAvailabilityWatch.Asked>> asks = new ArrayList<>();
        final List<String> took = new ArrayList<>();
        boolean sleeps;
        boolean screenOn;
        /** 超级镜子的切换范围是「后视 + 后座舱」。 */
        boolean mirrorCabin;
        /**
         * 屏幕黑下来的那一刻（调度的钟）；0 = 起点不明 —— 规则按黑满 30 秒算（ChannelRules.darkForChannel），
         * 只设 {@link #screenOn} 的那些测试就是「一黑就放开」。
         */
        long darkAt;
        /** 黑着时安卓也熄屏了（车机要睡 / 睡着）。 */
        boolean androidOff;
        boolean foregroundService;
        boolean mainForeground;
        boolean startedWhileVisible;
        boolean heard;
        long lastOthersChangeAt;
        private int answered;

        /** 冷启动时这一路不在（10-10 车机拿着后座舱时那样）。 */
        void absent(String cameraId) {
            words.put(cameraId, new CameraAvailabilityWatch.Word(null, true, false, elapsed(now)));
        }

        /** 冷启动时相机服务说它被占用、不是我们。 */
        void busyNotOurs(String cameraId) {
            words.put(cameraId, new CameraAvailabilityWatch.Word(false, false, false, elapsed(now)));
        }

        /** 答上还没答的那一次问：回放照记录（说过话的那几路），和记录一致，没有改的。 */
        void answer() {
            Map<String, Boolean> replay = new HashMap<>();
            for (Map.Entry<String, CameraAvailabilityWatch.Word> entry : words.entrySet()) {
                if (!entry.getValue().absent && entry.getValue().free != null) {
                    replay.put(entry.getKey(), entry.getValue().free);
                }
            }
            while (answered < asks.size()) {
                asks.get(answered++).accept(new CameraAvailabilityWatch.Asked(replay, new ArrayList<>()));
            }
            settle();
        }

        @Override
        public Set<Holder> holders() {
            Set<Holder> copy = EnumSet.noneOf(Holder.class);
            copy.addAll(holders);
            return copy;
        }

        @Override
        public boolean headUnitSleeps() {
            return sleeps;
        }

        @Override
        public boolean mirrorWantsCabinRear() {
            return mirrorCabin;
        }

        @Override
        public boolean screenOn() {
            return screenOn;
        }

        @Override
        public long darkForMs() {
            return screenOn || darkAt == 0 ? -1 : now - darkAt;
        }

        @Override
        public boolean androidOff() {
            return androidOff;
        }

        /** 屏幕知道黑没黑（ScreenState.known）：测试默认知道。 */
        boolean known = true;
        /** 哨兵模式：0 关、1 开、2 布防、null 读不到（默认）。 */
        Integer sentry;
        /** 「熄屏持续录制」：测试默认开着。 */
        boolean keep = true;
        /** 在开车。 */
        boolean driving;

        @Override
        public boolean screenKnown() {
            return known;
        }

        @Override
        public Integer sentry() {
            return sentry;
        }

        @Override
        public boolean keepRecordingWhenDark() {
            return keep;
        }

        @Override
        public boolean driving() {
            return driving;
        }

        /** 哨兵模式布防着（车主离车前开的）：熄屏时环视留着。 */
        void sentryArmed() {
            sentry = 2;
        }

        /** 屏幕真黑了（车主下车那一下）：从此刻起算黑了多久。 */
        void goDark() {
            screenOn = false;
            darkAt = now;
        }

        /** 亮了（开车门）。 */
        void goLit() {
            screenOn = true;
            darkAt = 0;
            androidOff = false;
        }

        @Override
        public boolean foregroundService() {
            return foregroundService;
        }

        @Override
        public boolean mainForeground() {
            return mainForeground;
        }

        @Override
        public boolean serviceStartedWhileVisible() {
            return startedWhileVisible;
        }

        @Override
        public boolean heardAnything() {
            return heard;
        }

        @Override
        public long elapsedNow() {
            return elapsed(now);
        }

        @Override
        public long lastOthersChangeAt() {
            return lastOthersChangeAt;
        }

        @Override
        public CameraAvailabilityWatch.Word word(String cameraId) {
            CameraAvailabilityWatch.Word word = words.get(cameraId);
            return word == null ? CameraAvailabilityWatch.Word.NONE : word;
        }

        /** 车上的那几样：测试改它（哨兵、点火多久以前变过……）；没改是「都不知道」。 */
        ChannelScheduler.Vehicle vehicle = ChannelScheduler.Vehicle.UNKNOWN;

        @Override
        public ChannelScheduler.Vehicle vehicle() {
            return vehicle;
        }

        /** 每一台相机的来历（真的那一份，测试照 SingleCamera 那样数台、重置）。 */
        final CameraHistory cameraHistory = new CameraHistory();

        @Override
        public CameraHistory.Camera history(String cameraId) {
            return cameraHistory.camera(cameraId);
        }

        @Override
        public void ask(Consumer<CameraAvailabilityWatch.Asked> answered) {
            asks.add(answered);
        }

        @Override
        public void othersTook(String cameraId, long atElapsed) {
            took.add(cameraId + "@" + atElapsed);
        }

        /** 前座舱只用一次的规矩（暂时）：测试默认关，原来的熄屏、亮屏测试照旧；要测它的打开。 */
        boolean frontCabinOneShot;

        @Override
        public boolean frontCabinOneShot() {
            return frontCabinOneShot;
        }

        /** 进程这一层的那几行（退出、主界面又回来了、车机要睡）：和各份管线的记在同一处（{@link #journal}）。 */
        final RecordingJournal processLines = new RecordingJournal();

        @Override
        public ChannelScheduler.ProcessJournal journal() {
            return processLines;
        }
    }

    /**
     * 一份管线自己的那几样（MultiCameraManager 的 ChannelSide）：录像计划（RECORDING 登记着才有）、录像输出在不在备（不备）、
     * 去掉前座舱、放完了；黑匣子照 {@link RecordingJournal} 记进 {@link #journal}。每接一份管线一个。
     */
    private final class FakePipe extends RecordingJournal implements ChannelScheduler.PipelineHost {
        final Set<String> plan = new LinkedHashSet<>();
        int rounds;
        /** 每一轮跑在什么时候（离 T0 多少毫秒，一轮读一次录像计划）：诊断不多醒、不挪哪一轮（2.11.5）。 */
        final List<Long> roundAt = new ArrayList<>();
        /** 调度报的「前座舱去掉了」，每一次一行（去掉的那一路）。 */
        final List<String> frontRetired = new ArrayList<>();
        /** 放完了（{@link ChannelScheduler.PipelineHost#retired}），几次。 */
        int retiredCalls;
        boolean gone;
        /** 第二份管线的录像那一侧听到的（setUp 那一份记在 {@link #told}）。 */
        final List<String> told = new ArrayList<>();

        void plan(String... keys) {
            plan.addAll(Arrays.asList(keys));
        }

        @Override
        public Set<String> recordPlan() {
            rounds++;
            roundAt.add(now - T0);
            return host.holders.contains(Holder.RECORDING) ? new LinkedHashSet<>(plan) : new LinkedHashSet<>();
        }

        @Override
        public ChannelRules.RecordPrep recordPrep(String key) {
            return ChannelRules.RecordPrep.NONE;
        }

        @Override
        public void frontCabinRetired(String key) {
            frontRetired.add(key);
        }

        @Override
        public void retired() {
            retiredCalls++;
            gone = true;
        }
    }

    /** 录像那一侧：调度告诉它的每一声记成一行记号（记进 {@link #lines}）。 */
    private final class FakeRecording implements ChannelScheduler.Recording {
        final List<String> lines;
        /** 它那一份管线的把手（register 之后接上）。 */
        ChannelScheduler.Pipeline of;

        FakeRecording(List<String> lines) {
            this.lines = lines;
        }

        @Override
        public void onLaneCameraStreaming(String key) {
            lines.add("streaming " + key);
        }

        @Override
        public void onLaneCameraGone(String key, ChannelScheduler.Gone why, CameraTaken.Reason reason,
                                     ChannelRules.Why closed) {
            lines.add("gone " + key + " " + why + (reason == null ? "" : " " + reason.source)
                    + (closed == null ? "" : " " + closed));
        }

        @Override
        public void onLaneConditionChanged(String key) {
            lines.add("condition " + key + " " + of.condition(key));
        }

        @Override
        public void onLaneWantersChanged(String key) {
            wantersChanged.add(key);
        }
    }

    /** 黑匣子：每一件事记成一行记号（「judged left TAKEN LOSS close=5409」），测试按前缀找。 */
    private class RecordingJournal implements ChannelScheduler.Journal, ChannelScheduler.ProcessJournal {
        @Override
        public void needs(Set<Holder> holders, List<String> recordKeys) {
            journal.add("needs " + holders + " " + recordKeys);
        }

        @Override
        public void holding(List<String> keys, Set<Kind> outlets) {
            journal.add("holding " + keys + " " + outlets);
        }

        @Override
        public void holdCancelled(List<String> keys, Set<Holder> by) {
            journal.add("holdCancelled " + keys + " " + by);
        }

        @Override
        public void recognized(String key, String cameraId, boolean absent) {
            journal.add("recognized " + key + " " + (absent ? "absent" : "busy"));
        }

        @Override
        public void judged(ChannelScheduler.Judgement j) {
            journal.add("judged " + j.key + " " + j.verdict + " " + j.origin
                    + (j.closeMs >= 0 ? " close=" + j.closeMs : "")
                    + (j.unusableThen != null ? " " + j.unusableThen : ""));
        }

        /** 「released left TAKEN」；问出来的带「asked」，这一台还被一轮占着、它那一次关还没返回的带「stillHeld」。 */
        @Override
        public void released(String key, String cameraId, CameraTaken.Verdict was, long heldMs, boolean asked,
                             boolean stillHeld) {
            journal.add("released " + key + " " + was + (asked ? " asked" : "") + (stillHeld ? " stillHeld" : ""));
        }

        @Override
        public void stopLifted(String key, ChannelRules.Event.Cause cause, Set<Holder> holders) {
            journal.add("stopLifted " + key + " " + cause);
        }

        /** 「surroundInDark KEPT DARK_ONSET sentry=2 records」；在录、计划里没有环视的是「notInPlan」。 */
        @Override
        public void surroundInDark(boolean kept, ChannelRules.Event.Cause why, Integer sentry, boolean keepSetting,
                                   boolean recording, boolean recordsSurround) {
            journal.add("surroundInDark " + (kept ? "KEPT" : "RELEASED") + " " + why + " sentry=" + sentry
                    + (keepSetting ? "" : " keepOff")
                    + (recordsSurround ? " records" : recording ? " notInPlan" : ""));
        }

        @Override
        public void gaveUp(String key) {
            journal.add("gaveUp " + key);
        }

        @Override
        public void stopped(String key, String lastError) {
            journal.add("stopped " + key);
        }

        @Override
        public void waiting(ChannelScheduler.Wait wait) {
            journal.add("waiting " + wait.why + " " + wait.key);
        }

        /** 「held front 2 round=1 left other closing-lost ms=60000」；占着它的是这一路自己的那一轮写「own」。 */
        @Override
        public void held(ChannelScheduler.Held h) {
            journal.add("held " + h.key + " " + h.cameraId + " round=" + h.round + " " + h.ownerKey
                    + (h.ownRound ? " own" : " other") + (h.kinds.isEmpty() ? "" : " " + h.kinds) + " ms=" + h.ms);
        }

        /** 「heldEnded front 2 round=1 ms=600000」；没人要了带「unwanted」，被拿着带「TAKEN」/「BLOCKED」。 */
        @Override
        public void heldEnded(String key, String cameraId, int round, long ms, CameraTaken.Verdict mark,
                              boolean wanted) {
            journal.add("heldEnded " + key + " " + cameraId + " round=" + round + " ms=" + ms
                    + (wanted ? "" : " unwanted") + (mark == null ? "" : " " + mark));
        }

        @Override
        public void deferred(String key, ChannelScheduler.Unusable why) {
            journal.add("deferred " + key + " " + why);
        }

        @Override
        public void recordWaitEnded(String key, long waitedMs, boolean withRecord) {
            journal.add("recordWaitEnded " + key + " " + withRecord);
        }

        @Override
        public void stepEnded(ChannelScheduler.StepReport r) {
            journal.add("stepEnded " + r.move + " " + r.key + (r.timedOut ? " timedOut" : "")
                    + (r.failure != null ? " failed " + r.failure.origin + " after=" + r.durationMs : "")
                    + (r.dropped != null ? " dropped " + r.dropped + " after=" + r.durationMs : "")
                    + (r.added.isEmpty() ? "" : " added=" + r.added)
                    + (r.counts != null ? " counts=" + r.counts.kind() : ""));
        }

        @Override
        public void opening(ChannelScheduler.StepContext c) {
            journal.add("opening " + c.key + " " + c.move + " sentry=" + c.vehicle.sentry + " fg=" + c.mainForeground
                    + " sinceClose=" + c.sinceCloseMs + " held=" + c.heldMs + " surround=" + c.surround);
        }

        @Override
        public void recorded(ChannelScheduler.StepRecord r) {
            records.add(r);
            journal.add("recorded " + r.context.key + " " + r.move + " " + r.result + " ms=" + r.ms
                    + (r.closeKind != null ? " kind=" + r.closeKind : "")
                    + (r.counts != null ? " counts=" + r.counts.kind() : "")
                    + (r.oldCloseMs >= 0 ? " oldClose=" + r.oldCloseMs : "")
                    + (r.context.surround != null ? " surround=" + r.context.surround : "")
                    + (r.lineage > 0 ? " lineage=" + r.lineage + "/" + r.lineageReset : "")
                    + (r.surroundFirstStartMs >= 0 ? " sf=" + r.surroundFirstStartMs + "/" + r.surroundFirstCabinMs : ""));
        }

        /** 「surroundFirst FRAMED gap=520 surround=150 cabins=[back, left]/DARK_RELEASE/140 open=230」。 */
        @Override
        public void surroundFirst(ChannelScheduler.SurroundFirst f) {
            journal.add("surroundFirst " + f.end + " gap=" + f.gapMs + " surround=" + f.surroundMs
                    + (f.surroundTimedOut ? "!" : "") + " cabins=" + f.cabinKeys + "/" + f.cabinWhy + "/" + f.cabinMs
                    + (f.cabinTimedOut ? "!" : "") + " open=" + f.reopenMs
                    + (f.failure != null ? " failed " + f.failure.origin : ""));
        }

        @Override
        public void openTrail(List<String> keys, List<Long> ms) {
            journal.add("openTrail " + keys);
        }

        @Override
        public void closing(List<String> keys, ChannelRules.Why why) {
            journal.add("closing " + keys + " " + why);
        }

        @Override
        public void closeTimedOut(List<String> keys, long maxMs, boolean surround) {
            journal.add("closeTimedOut " + keys);
        }

        @Override
        public void closeRound(String surroundKey, long surroundMs, boolean surroundTimedOut,
                               List<String> cabinKeys, List<Long> cabinMs, List<ChannelScheduler.Carried> carried,
                               List<ChannelScheduler.Other> others) {
            List<String> kept = new ArrayList<>();
            for (ChannelScheduler.Carried one : carried) {
                kept.add(one.key + " " + one.kind + " " + one.keptMs);
            }
            journal.add("closeRound " + surroundKey + " | " + String.join(", ", cabinKeys)
                    + (kept.isEmpty() ? "" : " | carried " + String.join(", ", kept))
                    + (others.isEmpty() ? "" : " | others " + othersText(others)));
        }

        @Override
        public void closeAftermath(List<String> closedKeys, ChannelRules.Why why, List<ChannelScheduler.Other> others) {
            journal.add("closeAftermath " + closedKeys + " " + why + " " + othersText(others));
        }

        /**
         * 「coupling END PLANNED [back, left] DARK_RELEASE front gap=0 same last=-35/+65 trouble=front:1/0,left:0/0,back:0/0
         * lineage=5/CAME_BACK」；30 秒那一次再带「| others …」和 a1=（关完 1 秒那一次看到的）。
         */
        @Override
        public void coupling(ChannelScheduler.Coupling c) {
            couplings.add(c);
            List<String> trouble = new ArrayList<>();
            for (ChannelScheduler.Trouble t : c.trouble) {
                trouble.add(t.key + ":" + t.failures + "/" + t.lost);
            }
            long end = c.lastResultAfterEndMs();
            journal.add("coupling " + c.phase + " " + c.kind + " " + c.keys + " "
                    + (c.why != null ? c.why : c.reason != null ? c.reason.source : "-") + " "
                    + othersText(java.util.Collections.singletonList(c.surround))
                    + " last=" + (end == Long.MIN_VALUE ? "none" : end + "/" + c.lastResultAfterStartMs())
                    + " trouble=" + String.join(",", trouble)
                    + (c.lineage > 0 ? " lineage=" + c.lineage + "/" + c.lineageReset : "")
                    + (c.phase == ChannelScheduler.Coupling.Phase.AFTER_30S ? " a1=" + c.deliveredAfter : "")
                    + (c.others.isEmpty() ? "" : " | others " + othersText(c.others)));
        }

        @Override
        public void rescueAftermath(String key, long stepMs, long measuredMs, ChannelRules.Move next, String nextKey,
                                    List<ChannelScheduler.Other> others) {
            journal.add("rescueAftermath " + key + " step=" + stepMs + " measured=" + measuredMs + " next=" + next
                    + (nextKey == null ? "" : " " + nextKey) + " " + othersText(others));
        }

        @Override
        public void openSpan(ChannelScheduler.OpenSpan span) {
            journal.add("openSpan " + span.key + " open=" + span.openMs + " added=" + span.added
                    + " dead=" + span.deadRemoved + " rescued=" + span.rescued + " removedOnly=" + span.removedOnly);
        }

        @Override
        public void sleep(String key, ChannelRules.Move move, long ms) {
            journal.add("sleep " + key + " " + move);
        }

        @Override
        public void exit(String key, ChannelRules.Move move, long ms) {
            journal.add("exit " + key + " " + move);
        }

        @Override
        public void resumed() {
            journal.add("resumed");
        }

        @Override
        public void retiring(String key, ChannelRules.Move move, long ms) {
            journal.add("retiring " + key + " " + move);
        }

        /** 「swapped 1200 successor」；卡住的那几路接着「 orphans front 2 round=1 closing」。 */
        @Override
        public void swapped(long retiredMs, boolean successor, List<ChannelScheduler.Orphan> orphans) {
            List<String> stuck = new ArrayList<>();
            for (ChannelScheduler.Orphan o : orphans) {
                stuck.add(o.key + " " + o.cameraId + " round=" + o.round + (o.kinds.isEmpty() ? "" : " " + o.kinds));
            }
            journal.add("swapped " + retiredMs + (successor ? " successor" : " none")
                    + (stuck.isEmpty() ? "" : " orphans " + String.join(", ", stuck)));
        }

        @Override
        public void stray(String cameraId, String report) {
            journal.add("stray " + cameraId + " " + report);
        }

        @Override
        public void superseded() {
            journal.add("superseded");
        }

        @Override
        public void asked(String key, String cameraId, CameraTaken.Verdict mark, int n, long heldMs, Boolean free,
                          boolean absent, boolean important) {
            journal.add("asked " + key + " " + n + (important ? " important" : " quiet")
                    + (mark == CameraTaken.Verdict.BLOCKED ? " blocked" : ""));
        }
    }
}
