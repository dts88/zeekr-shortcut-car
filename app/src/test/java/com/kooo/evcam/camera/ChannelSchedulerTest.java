package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
    private final FakePort surround = new FakePort("2");
    private final FakePort rear = new FakePort("1");
    private final FakePort front = new FakePort("0");
    private ChannelScheduler s;

    private final Out pvS = new Out(Kind.PREVIEW, "preview-2");
    private final Out pvR = new Out(Kind.PREVIEW, "preview-1");
    private final Out pvF = new Out(Kind.PREVIEW, "preview-0");
    private final Out recS = new Out(Kind.RECORD, "recorder-2");
    private final Out recR = new Out(Kind.RECORD, "recorder-1");
    private final Out recF = new Out(Kind.RECORD, "recorder-0");

    @Before
    public void setUp() {
        host.screenOn = true;
        host.foregroundService = true;
        host.mainForeground = true;
        host.heard = true;
        s = new ChannelScheduler(() -> now, poster, host, new RecordingJournal());
        s.setRecording(new FakeRecording());
        Map<String, ChannelScheduler.CameraPort> lanes = new LinkedHashMap<>();
        // 故意不按开的次序给：调度照开的次序排
        lanes.put(FRONT, front);
        lanes.put(REAR, rear);
        lanes.put(SUR, surround);
        s.setLanes(lanes);
    }

    @After
    public void tearDown() {
        s.detach();
    }

    // ================================================================= 叫醒

    /** 几次叫醒并成一次 post：熄屏那一下所有监听者都做完了它才跑一轮。 */
    @Test
    public void wakesCoalesceIntoOneRound() {
        settle();
        int posts = poster.posts;
        int rounds = host.rounds;
        s.wake();
        s.wake();
        s.onNeedsChanged();
        s.wake();
        assertEquals("并成一次 post", posts + 1, poster.posts);
        settle();
        assertEquals("跑了一轮", rounds + 1, host.rounds);
        s.wake();
        assertEquals("跑完之后再叫，再排一次", posts + 2, poster.posts);
    }

    /** 没人叫也每 2 秒醒一次（保持到点、问的节奏都靠它兜底）。 */
    @Test
    public void ticksEveryTwoSeconds() {
        settle();
        int rounds = host.rounds;
        at(ChannelRules.TICK_MS * 5);
        assertTrue("至少醒了 5 次：" + (host.rounds - rounds), host.rounds - rounds >= 5);
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
        assertEquals(ChannelRules.Condition.STREAMING, s.condition(SUR));
        assertTrue(s.usable(SUR));
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
        s.onLossStarted("1", DISCONNECTED);
        say(rear, false, true);
        settle();
        assertEquals(1, lines("waiting LOSING left").size());
        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertTrue(lines("judged").isEmpty());
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals("到点按当时的状态判（最后一句被占用 → 被拿着）", 1, lines("judged left TAKEN LOSS").size());
        assertEquals(ChannelRules.Condition.TAKEN, s.condition(REAR));
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
        s.onLossStarted("2", DEVICE_ERROR);
        rear.lose(now);
        s.onLossStarted("1", DEVICE_ERROR);
        at(100);
        say(surround, true, true);
        surround.closedNow();
        s.onLossClosed("2", 100);
        at(1_000);
        say(rear, true, true);
        rear.closedNow();
        s.onLossClosed("1", 1_000);
        settle();
        int rounds = host.rounds;
        at(1_299);
        assertTrue("环视早到点了，只等后座舱到点：" + (host.rounds - rounds), host.rounds - rounds <= 2);
        assertTrue(lines("judged").isEmpty());
        at(1_300);
        assertEquals(2, lines("judged").size());
    }

    /**
     * 丢了还没判完：判完之前 condition 说不出是不是被拿着（在救）；原因是「被占用」一类的，旧读者问
     * {@link CameraTaken#othersHold} 当场就是真（阶段 7 随它一起删）。
     */
    @Test
    public void anInUseLossIsRescuingUntilJudgedAndCountsAsHeldMeanwhile() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        assertEquals(ChannelRules.Condition.STREAMING, s.condition(REAR));
        assertFalse(CameraTaken.othersHold());
        rear.lose(now);
        s.onLossStarted("1", DISCONNECTED);
        assertEquals(ChannelRules.Condition.RESCUING, s.condition(REAR));
        assertTrue("被断开、还没判：算别的程序拿着", CameraTaken.othersHold());
        at(5_400);
        rear.closedNow();
        s.onLossClosed("1", 5_400);
        settle();
        assertEquals("关完了，那 300 ms 里还在判：说不出是被拿着", ChannelRules.Condition.RESCUING, s.condition(REAR));
        at(5_700);
        assertEquals(ChannelRules.Condition.TAKEN, s.condition(REAR));
        assertTrue(CameraTaken.othersHold());
    }

    /** 设备错误（4）丢了：还在判（在救），但不属于「被占用」一类，旧读者不当成别的程序拿着。 */
    @Test
    public void aDeviceErrorLossIsRescuingButNotHeld() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        settle();
        surround.lose(now);
        s.onLossStarted("2", DEVICE_ERROR);
        assertEquals(ChannelRules.Condition.RESCUING, s.condition(SUR));
        assertFalse(CameraTaken.othersHold());
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
        s.onLossStarted("2", DEVICE_ERROR);
        at(1_000);
        surround.closedNow();
        s.onLossClosed("2", 1_000);
        at(1_100);
        say(surround, false, true);
        surround.deviceOpen = true;
        surround.holds = true;
        configured(surround, pvS);
        frame(surround);
        s.onOpened("2");
        at(2_000);
        assertTrue("待判作废，不判", lines("judged").isEmpty());
        assertEquals(ChannelRules.Condition.STREAMING, s.condition(SUR));
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
        s.handOver(SUR, pvS, () -> released.add("pvS"));
        settle();
        assertTrue("还挂在会话里：不放", released.isEmpty());
        assertTrue("留着的活输出不为它改会话", surround.actions.isEmpty());

        at(1_000);
        host.plan(SUR);
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
        s.handOver(SUR, pvS, () -> released.add("pvS"));
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
        s.handOver(SUR, pvS, () -> released.add("pvS"));
        s.handOver("nowhere", new Out(Kind.PREVIEW, "preview-x"), () -> released.add("x"));
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
        s.handOver(SUR, pvS, () -> released.add("pvS"));
        assertTrue(s.reclaim(SUR, pvS));
        assertFalse("拿回过了", s.reclaim(SUR, pvS));
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
        s.handOver(SUR, pvS, () -> released.add("pvS"));
        s.exit();
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
        assertTrue(host.stopped);
        assertEquals("只放一次", Arrays.asList("pvS"), released);
    }

    /**
     * 大纲 §7.7（项目所有者 2026-10-11「按建议，留住」）：关超级后视镜时画布也交给调度。环视在录、只有后视镜和录像要它：
     * 后视镜放开登记、撤回输出、画布交过来 —— 环视一步不动（留着的活输出不为它改会话、不当死输出马上摘，录像不断）；
     * 新开的窗口没接回、自己建了一块，下一次改输出加上新的、顺带摘掉旧的，新会话配好之后才放旧的那一块。
     */
    @Test
    public void aClosedMirrorsCanvasLeavesTheRecordingSurroundAloneUntilALaterApply() {
        Out mirror = new Out(Kind.MIRROR, "mirror-a");
        host.plan(SUR);
        claim(Holder.RECORDING);
        claim(Holder.MIRROR);
        streaming(surround, mirror, recS);
        settle();
        assertTrue(surround.actions.isEmpty());
        List<String> released = new ArrayList<>();
        drop(Holder.MIRROR);
        surround.declared.remove(mirror);
        s.handOver(SUR, mirror, () -> released.add("mirror-a"));
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

    /** 相机层自己丢的录像输出（会话配不上、消费者被收走）：当场告诉；别的输出不归录像管。 */
    @Test
    public void aRecordOutputTheCameraDroppedIsReportedAtOnce() {
        settle();
        s.onOutputDropped("2", recS, SingleCamera.Drop.CONFIG_FAILED);
        s.onOutputDropped("2", pvS, SingleCamera.Drop.RELEASED);
        assertEquals(Arrays.asList("RECORD CONFIG_FAILED"), surround.drops);
    }

    // ================================================================= 告诉录像那一侧（大纲 §2 阶段 5）

    /**
     * 一份会话出了画面（配好、出了第一帧）才告诉录像：同一份会话只说一次，换了一份（改输出加了后视镜）再说一次 ——
     * 录像那一侧每次都看会话带没带着它的录像输出，带着就加入。配好了还没出第一帧不说。
     */
    @Test
    public void eachSessionThatStreamsIsToldToRecordingOnce() {
        claim(Holder.RECORDING);
        host.plan(SUR);
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
        host.plan(SUR, REAR);
        streaming(surround, recS);
        streaming(rear, recR);
        settle();
        assertEquals(Arrays.asList("streaming front", "streaming left"), told("streaming"));
        told.clear();
        rear.lose(now);
        s.onLossStarted("1", DISCONNECTED);
        settle();
        assertEquals(Arrays.asList("gone left LOST DISCONNECTED"), told("gone"));
        assertEquals(Arrays.asList("condition left RESCUING"), told("condition left"));
        at(5_400);
        rear.closedNow();
        s.onLossClosed("1", 5_400);
        settle();
        at(5_700);
        assertEquals(ChannelRules.Condition.TAKEN, s.condition(REAR));
        assertEquals(Arrays.asList("condition left RESCUING", "condition left TAKEN"), told("condition left"));
        assertEquals("关完不再说一次走了", 1, told("gone").size());
        assertTrue("别的路不受影响", told("gone front").isEmpty() && told("condition front").isEmpty());
    }

    /** 我们关的（没人要了、没有前台服务）：开始关的那一轮告诉录像「走了」。 */
    @Test
    public void aCloseIsToldAsGone() {
        host.foregroundService = false;
        claim(Holder.RECORDING);
        host.plan(SUR);
        streaming(surround, recS);
        settle();
        told.clear();
        drop(Holder.RECORDING);
        settle();
        assertEquals(Arrays.asList("close unwanted"), surround.actions);
        assertEquals(Arrays.asList("gone front CLOSED"), told("gone"));
    }

    /**
     * 开着却卡住了（8 秒没进展），调度在救：告诉录像「走了」（那一路收尾退出）；救好了新会话带着同一个录像输出出了画面，
     * 再告诉一次出画面（那一路再加入）。救的那一步照旧带着录像输出，不算被丢。
     */
    @Test
    public void aRescueOfAStuckLaneIsToldAsGoneAndItsNextSessionAsStreaming() {
        claim(Holder.RECORDING);
        host.plan(SUR);
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
        assertFalse(ChannelScheduler.closingAnywhere());

        s.exit();
        assertTrue("exit() 之后当场为真", ChannelScheduler.closingAnywhere());
        settle();
        assertEquals(1, lines("exit left OPEN").size());
        assertTrue("手上那一步做完之前不关", surround.actions.isEmpty());
        at(400);
        opened(rear);
        frame(rear);
        settle();
        assertEquals(Arrays.asList("close exit"), surround.actions);
        assertTrue(ChannelScheduler.closingAnywhere());
        at(500);
        closed(surround);
        settle();
        assertEquals(Arrays.asList("open [PREVIEW]", "close exit"), rear.actions);
        assertEquals(Arrays.asList("close exit"), front.actions);
        at(600);
        closed(rear);
        settle();
        assertTrue("还有一路没关完", ChannelScheduler.closingAnywhere());
        at(700);
        closed(front);
        settle();
        assertFalse("最后一路关完才变假", ChannelScheduler.closingAnywhere());
        assertTrue(host.stopped);
        assertEquals(1, lines("closeRound front | left, back").size());
    }

    /**
     * 车机要睡：发布的那一刻 closingAnywhere 当场为真；手上那一步最多再等 1 秒（这 1 秒里一直为真），然后按次序关；
     * 最后一路关完变假。亮屏清掉之后照常。
     */
    @Test
    public void theHeadUnitSleepingWaitsOneSecondForTheStepInHandThenClosesInOrder() {
        claim(Holder.RECORDING);
        host.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(front, recF);
        declare(rear, recR);
        settle();
        assertEquals(Arrays.asList("open [RECORD]"), rear.actions);

        at(100);
        host.sleeps = true;
        s.onNeedsChanged();
        assertTrue("发布的那一刻就为真", ChannelScheduler.closingAnywhere());
        settle();
        assertEquals(1, lines("sleep left OPEN").size());
        at(100 + ChannelRules.SLEEP_GRACE_MS - 1);
        assertTrue("1 秒宽限里不动", surround.actions.isEmpty());
        assertTrue(ChannelScheduler.closingAnywhere());
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
        assertFalse(ChannelScheduler.closingAnywhere());
        assertFalse("车机要睡不停调度（亮屏还要用）", host.stopped);

        // 亮屏：清掉这项事实，照常开
        host.sleeps = false;
        s.onNeedsChanged();
        at(5_000);
        assertFalse(ChannelScheduler.closingAnywhere());
        assertEquals(Arrays.asList("close head-unit-sleeps", "open [RECORD]"), surround.actions);
    }

    /** 车机要睡被亮屏清掉、手上没有关在途：当场不再算在关。 */
    @Test
    public void screenOnClearsTheSleepWhileNothingCloses() {
        settle();
        host.sleeps = true;
        s.onNeedsChanged();
        assertTrue(ChannelScheduler.closingAnywhere());
        host.sleeps = false;
        s.onNeedsChanged();
        settle();
        assertFalse(ChannelScheduler.closingAnywhere());
    }

    /**
     * 等「关完」（熄屏录制的唤醒锁等它再放，大纲 §2 阶段 6）：没有在关的时候当场叫；车机要睡时登记的，按次序关、最后一路关完的那一轮
     * 才叫，只叫一次；不等了摘掉的不叫。
     */
    @Test
    public void aCloseWaiterRunsOnceWhenTheLastLaneClosedAndAtOnceWhenNothingCloses() {
        List<String> calls = new ArrayList<>();
        ChannelScheduler.whenClosedEverywhere(() -> calls.add("idle"));
        assertEquals("没有在关的：当场叫", Arrays.asList("idle"), calls);

        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        settle();
        host.sleeps = true;
        s.onNeedsChanged();
        Runnable cancelled = () -> calls.add("cancelled");
        ChannelScheduler.whenClosedEverywhere(() -> calls.add("closed"));
        ChannelScheduler.whenClosedEverywhere(cancelled);
        ChannelScheduler.cancelClosedWaiter(cancelled);
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
        assertFalse(ChannelScheduler.closingAnywhere());
        assertEquals("最后一路关完的那一轮叫", Arrays.asList("idle", "closed"), calls);
        at(10_000);
        assertEquals("只叫一次", Arrays.asList("idle", "closed"), calls);
    }

    /**
     * 两份管线同时为退出关相机（换车型后紧接着退出）：自己在关的不等另一份 —— 关不用谁放行（审查 2026-10-11：以前互相等，
     * 谁都要等对方进来满 60 秒）。一份的关卡死在相机服务里：closingAnywhere 最多为真 EXIT_WAIT_MAX_MS，不会一直为真。
     */
    @Test
    public void twoInstancesClosingDoNotWaitOnEachOtherAndAStuckCloseIsCapped() {
        FakePort other = new FakePort("2");
        ChannelScheduler b = new ChannelScheduler(() -> now, poster, host, new RecordingJournal());
        try {
            Map<String, ChannelScheduler.CameraPort> lanes = new LinkedHashMap<>();
            lanes.put(SUR, other);
            b.setLanes(lanes);
            claim(Holder.MIRROR);   // 只要环视：两份手上都没有开到一半的步子，退出当场就关
            streaming(surround, pvS);
            streaming(other, pvS);
            settle();
            // 旧的那一份（换车型时晚 release 的）退出，它关环视卡死在相机服务里
            s.exit();
            settle();
            assertEquals(Arrays.asList("close exit"), surround.actions);
            assertTrue(ChannelScheduler.closingAnywhere());

            // 新的这一份紧接着退出：当场按次序关，不等旧的那一份
            at(1_000);
            b.exit();
            settle();
            assertEquals(Arrays.asList("close exit"), other.actions);
            at(1_100);
            other.closedNow();
            b.onClosed("2");
            settle();
            assertTrue("旧的那一份还没关完", ChannelScheduler.closingAnywhere());

            at(ChannelRules.EXIT_WAIT_MAX_MS - 1);
            assertTrue(ChannelScheduler.closingAnywhere());
            at(ChannelRules.EXIT_WAIT_MAX_MS);
            assertFalse("关卡死的那一份最多算这么久", ChannelScheduler.closingAnywhere());
        } finally {
            b.detach();
        }
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
        host.plan(SUR);
        claim(Holder.RECORDING);
        declare(surround, recS);
        settle();
        assertTrue(s.cannotOpen(SUR));
        assertFalse("没人要的那几路不算", s.cannotOpen(REAR));
        assertEquals(ChannelRules.Condition.BRINGING_UP, s.condition(SUR));
        told.clear();
        host.mainForeground = true;
        s.wake();
        settle();
        assertEquals(Arrays.asList("open [RECORD]"), surround.actions);
        assertFalse(s.cannotOpen(SUR));
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
        surround.busySince = 0;
        surround.holds = false;
        s.onOpenFailed("2", CameraTaken.Reason.ofDeviceError(CameraDevice.StateCallback.ERROR_CAMERA_DISABLED));
        at(100 + ChannelRules.JUDGE_DELAY_MS);
        assertEquals(1, lines("judged front UNUSABLE OPEN").size());
        at(60_000);
        assertEquals("不重开、不计次数", 1, surround.actions.size());
        host.mainForeground = true;
        s.wake();
        settle();
        assertEquals(2, surround.actions.size());
    }

    // ================================================================= 现场回放（diag_15_bb.txt，10-10；09-27 的冲突）

    /**
     * (a) 10-10 07:22：录着三路，车机拿走后座舱相机 1。关它的 5.4 秒里相机 0 和 2 一步都不动；关完判 TAKEN、记争用；
     * 之后只问、不开；07:31:22 报「1 空闲」之后至少等 3 秒，相机 1 单独开（带着它的录像输出）。
     */
    @Test
    public void replayA_rearCabinTakenWhileRecording() {
        claim(Holder.RECORDING);
        host.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        say(front, true, false);
        say(surround, true, false);
        rear.lose(now);
        s.onLossStarted("1", DISCONNECTED);
        for (long t = 0; t < 5_409; t += 500) {
            at(t);
        }
        assertTrue("0 和 2 一步都不动", surround.actions.isEmpty() && front.actions.isEmpty());
        at(5_409);
        rear.closedNow();
        s.onLossClosed("1", 5_409);
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
        s.onLossStarted("1", DISCONNECTED);
        settle();
        at(9_622);
        say(rear, true, true);
        at(9_627);
        rear.closedNow();
        s.onLossClosed("1", 9_627);
        at(9_927);
        assertEquals(Arrays.asList("judged left ORDINARY LOSS close=9627"), lines("judged"));
        assertTrue(host.took.isEmpty());
        assertEquals(Arrays.asList("reopen [PREVIEW]"), rear.actions);
    }

    /**
     * (c) 10-10 08:56:57：我们开着相机 2 时相机服务报了一句「2 空闲」（乱报），之后 2 出错误 4 ——
     * 最后一句是空闲、错误 4 不属于被占用一类：普通故障，按救援的节奏重开（不因为 1 被拿着就判 BLOCKED）。
     */
    @Test
    public void replayC_error4AfterASpuriousFreeIsOrdinary() {
        claim(Holder.RECORDING);
        host.plan(SUR, REAR, FRONT);
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        settle();
        say(front, true, false);
        rear.lose(now);
        s.onLossStarted("1", DISCONNECTED);
        say(surround, true, false);
        at(7_975);
        rear.closedNow();
        s.onLossClosed("1", 7_975);
        at(8_275);
        assertEquals(ChannelRules.Condition.TAKEN, s.condition(REAR));

        at(14_114);
        surround.lose(now);
        s.onLossStarted("2", DEVICE_ERROR);
        at(15_270);
        surround.closedNow();
        s.onLossClosed("2", 1_156);
        at(15_570);
        assertEquals(1, lines("judged front ORDINARY LOSS").size());
        assertEquals(Arrays.asList("reopen [RECORD]"), surround.actions);
        assertTrue(rear.actions.isEmpty());
    }

    /** (d) 10-10 09:00:18：1 放开和一句假的「2 空闲」同时到 → 安静满 3 秒后，1 单独开。 */
    @Test
    public void replayD_aReleaseTogetherWithASpuriousFreeOpensTheRearAloneAfterQuiet() {
        claim(Holder.RECORDING);
        host.plan(SUR, REAR, FRONT);
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
        s.onLossStarted("1", DISCONNECTED);
        surround.lose(now);
        s.onLossStarted("2", DISCONNECTED);
        at(50);
        say(surround, true, true);
        at(1_000);
        surround.closedNow();
        s.onLossClosed("2", 1_000);
        at(1_300);
        assertTrue("还有丢失没关完：等它一起判", lines("judged").isEmpty());
        int rounds = host.rounds;
        at(5_399);
        assertTrue("等后座舱关完的这几秒不空转（环视早到点了，按 2 秒一轮等）：" + (host.rounds - rounds),
                host.rounds - rounds <= 5);
        at(5_400);
        rear.closedNow();
        s.onLossClosed("1", 5_400);
        at(5_700);
        assertEquals(Arrays.asList("judged left TAKEN LOSS close=5400", "judged front BLOCKED LOSS close=1000"),
                lines("judged"));
        assertEquals("BLOCKED 不记争用", Arrays.asList("1@" + (T0 + ELAPSED_AHEAD)), host.took);
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

    // ================================================================= 被拿着的旧读者（后视镜服务、协调器）

    /** 被拿着只记在调度里：还没迁走的旧读者问 CameraTaken，照调度的账答。 */
    @Test
    public void theOldReadersSeeTheSchedulersMarks() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        rear.declared.add(pvR);
        assertFalse(CameraTaken.benched("1"));
        host.absent("1");
        settle();
        assertTrue(CameraTaken.benched("1"));
        assertTrue(CameraTaken.othersHold());
        assertFalse(CameraTaken.benched("2"));
        assertTrue(s.excludedFromPhoto(REAR));
        assertFalse(s.excludedFromPhoto(SUR));
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
        host.plan(SUR);
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

    // ================================================================= 帮手：扮演相机层、登记表

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

    private void drop(Holder holder) {
        host.holders.remove(holder);
        s.onNeedsChanged();
    }

    private void declare(FakePort port, Out... outs) {
        port.declared.addAll(Arrays.asList(outs));
    }

    /** 一路早就开着、在出画面，会话里带着这几样（出帧口之外的都声明着）；开的时候相机服务说过「被占用（我们开着）」。 */
    private void streaming(FakePort port, Out... outs) {
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
        port.deviceOpen = true;
        port.holds = true;
        configured(port, port.lastOutputs.toArray(new Out[0]));
        s.onOpened(port.id);
    }

    private void configured(FakePort port, Out... outs) {
        port.generation++;
        port.session = new SingleCamera.SessionOutputs(port.generation, new LinkedHashSet<>(Arrays.asList(outs)), now);
        port.firstFrameAt = 0;
        port.busySince = 0;
        port.progressAgeMs = 0;
        s.onCameraEvent();
    }

    private void frame(FakePort port) {
        if (port.firstFrameAt == 0) {
            port.firstFrameAt = now;
        }
        port.progressAgeMs = 0;
        s.onCameraEvent();
    }

    private void closed(FakePort port) {
        port.closedNow();
        s.onClosed(port.id);
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

    /** 一路相机：动作记下来（「open [PREVIEW]」「close unwanted」），事实由测试改。 */
    private final class FakePort implements ChannelScheduler.CameraPort {
        final String id;
        final List<String> actions = new ArrayList<>();
        final List<String> drops = new ArrayList<>();
        final Set<Out> declared = new LinkedHashSet<>();
        final Set<Out> dead = new LinkedHashSet<>();
        Set<Out> lastOutputs = new LinkedHashSet<>();
        boolean deviceOpen;
        boolean holds;
        long busySince;
        SingleCamera.SessionOutputs session = SingleCamera.SessionOutputs.NONE;
        long firstFrameAt;
        long progressAgeMs;
        int generation;

        FakePort(String id) {
            this.id = id;
        }

        /** 丢了：设备、会话从字段上摘下，那一次关在途（SingleCamera.loseDevice）。 */
        void lose(long at) {
            deviceOpen = false;
            session = SingleCamera.SessionOutputs.NONE;
            firstFrameAt = 0;
            busySince = at;
            holds = true;
        }

        void closedNow() {
            deviceOpen = false;
            holds = false;
            busySince = 0;
            session = SingleCamera.SessionOutputs.NONE;
            firstFrameAt = 0;
            progressAgeMs = 0;
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

        @Override
        public boolean open(Set<Out> outputs) {
            return start("open", outputs);
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
        public boolean reopen(Set<Out> outputs) {
            return start("reopen", outputs);
        }

        @Override
        public void close(String why) {
            actions.add("close " + why);
            deviceOpen = false;
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
        public String describeBusy() {
            return "closing-lost";
        }

        @Override
        public SingleCamera.SessionOutputs sessionOutputs() {
            return session;
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
            return 0;
        }

        @Override
        public String lastErrorName() {
            return null;
        }

        @Override
        public void dropped(Out out, SingleCamera.Drop why) {
            drops.add(out.kind + " " + why);
        }
    }

    /** 全局事实：登记表、亮熄屏、前台服务、相机服务那几句（含深睡的钟）；问相机服务的回调排着，测试来答。 */
    private final class FakeHost implements ChannelScheduler.Host {
        final Set<Holder> holders = EnumSet.noneOf(Holder.class);
        final Set<String> plan = new LinkedHashSet<>();
        final Map<String, CameraAvailabilityWatch.Word> words = new HashMap<>();
        final List<Consumer<Set<String>>> asks = new ArrayList<>();
        final List<String> took = new ArrayList<>();
        boolean sleeps;
        boolean screenOn;
        boolean foregroundService;
        boolean mainForeground;
        boolean startedWhileVisible;
        boolean heard;
        long lastOthersChangeAt;
        boolean stopped;
        int rounds;
        private int answered;

        void plan(String... keys) {
            plan.addAll(Arrays.asList(keys));
        }

        /** 冷启动时这一路不在（10-10 车机拿着后座舱时那样）。 */
        void absent(String cameraId) {
            words.put(cameraId, new CameraAvailabilityWatch.Word(null, true, false, elapsed(now)));
        }

        /** 冷启动时相机服务说它被占用、不是我们。 */
        void busyNotOurs(String cameraId) {
            words.put(cameraId, new CameraAvailabilityWatch.Word(false, false, false, elapsed(now)));
        }

        /** 答上还没答的那一次问（和记录一致：没有改的）。 */
        void answer() {
            while (answered < asks.size()) {
                asks.get(answered++).accept(new LinkedHashSet<>());
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
        public Set<String> recordPlan() {
            rounds++;
            return holders.contains(Holder.RECORDING) ? new LinkedHashSet<>(plan) : new LinkedHashSet<>();
        }

        @Override
        public boolean screenOn() {
            return screenOn;
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

        @Override
        public ChannelRules.RecordPrep recordPrep(String key) {
            return ChannelRules.RecordPrep.NONE;
        }

        @Override
        public void ask(Consumer<Set<String>> answered) {
            asks.add(answered);
        }

        @Override
        public void othersTook(String cameraId, long atElapsed) {
            took.add(cameraId + "@" + atElapsed);
        }

        @Override
        public void stopped() {
            stopped = true;
        }
    }

    /** 录像那一侧：调度告诉它的每一声记成一行记号。 */
    private final class FakeRecording implements ChannelScheduler.Recording {
        @Override
        public void onLaneCameraStreaming(String key) {
            told.add("streaming " + key);
        }

        @Override
        public void onLaneCameraGone(String key, ChannelScheduler.Gone why, CameraTaken.Reason reason) {
            told.add("gone " + key + " " + why + (reason == null ? "" : " " + reason.source));
        }

        @Override
        public void onLaneConditionChanged(String key) {
            told.add("condition " + key + " " + s.condition(key));
        }
    }

    /** 黑匣子：每一件事记成一行记号（「judged left TAKEN LOSS close=5409」），测试按前缀找。 */
    private final class RecordingJournal implements ChannelScheduler.Journal {
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
                    + (j.closeMs >= 0 ? " close=" + j.closeMs : ""));
        }

        @Override
        public void released(String key, String cameraId, CameraTaken.Verdict was, long heldMs, boolean asked) {
            journal.add("released " + key + " " + was + (asked ? " asked" : ""));
        }

        @Override
        public void stopLifted(String key, ChannelRules.Event.Cause cause, Set<Holder> holders) {
            journal.add("stopLifted " + key + " " + cause);
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
            journal.add("stepEnded " + r.move + " " + r.key + (r.timedOut ? " timedOut" : ""));
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
                               List<String> cabinKeys, List<Long> cabinMs) {
            journal.add("closeRound " + surroundKey + " | " + String.join(", ", cabinKeys));
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
        public void asked(String key, String cameraId, int n, long heldMs, Boolean free, boolean absent,
                          boolean important) {
            journal.add("asked " + key + " " + n + (important ? " important" : " quiet"));
        }
    }
}
