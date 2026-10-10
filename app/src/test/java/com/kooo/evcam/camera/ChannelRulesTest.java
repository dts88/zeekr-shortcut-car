package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;

import com.kooo.evcam.camera.CameraNeeds.Holder;
import com.kooo.evcam.camera.ChannelRules.Condition;
import com.kooo.evcam.camera.ChannelRules.Decision;
import com.kooo.evcam.camera.ChannelRules.Device;
import com.kooo.evcam.camera.ChannelRules.Event;
import com.kooo.evcam.camera.ChannelRules.Kind;
import com.kooo.evcam.camera.ChannelRules.Lane;
import com.kooo.evcam.camera.ChannelRules.Move;
import com.kooo.evcam.camera.ChannelRules.Out;
import com.kooo.evcam.camera.ChannelRules.Outcome;
import com.kooo.evcam.camera.ChannelRules.RecordPrep;
import com.kooo.evcam.camera.ChannelRules.Rescue;
import com.kooo.evcam.camera.ChannelRules.Why;
import com.kooo.evcam.camera.ChannelRules.World;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通道调度的规则（channel-logic §一、§二，项目所有者 2026-10-10 确认；§7 六问 2026-10-11 答「按建议」）。
 *
 * <p>确认文档的每一句在这里都有一条测试对着。判错的代价是实车上的：环视比座舱晚关完，下一次打开它一帧不出
 * （2026-10-09）；交接的中途动通道，就是自己顶自己（2026-10-08 那七次）；被拿着的一路去试开，在前台可能把原厂功能挤掉
 * （2026-10-10）。现场回放取自 {@code diag_15_bb.txt}（10-10）和 09-27 的冲突。</p>
 *
 * <p>相机那一侧由这里的几个小方法照 SingleCamera 的样子扮演：开好、配好、出第一帧、关完，都是测试说了算。</p>
 */
public class ChannelRulesTest {

    private static final long T0 = 1_000_000L;
    private static final String SUR = CameraSlots.KEY_SURROUND;
    private static final String REAR = CameraSlots.KEY_CABIN_REAR;
    private static final String FRONT = CameraSlots.KEY_CABIN_FRONT;
    /** 设备错误 4（ERROR_CAMERA_DEVICE）：不属于「被占用」一类。 */
    private static final int DEVICE_ERROR = CameraDevice.StateCallback.ERROR_CAMERA_DEVICE;

    private final Lane surround = new Lane(SUR, "2");
    private final Lane rear = new Lane(REAR, "1");
    private final Lane front = new Lane(FRONT, "0");
    /** 故意不按次序给：规则自己按开的次序排。 */
    private final World w = new World(Arrays.asList(front, rear, surround));

    private final Out pvS = new Out(Kind.PREVIEW, "preview-2");
    private final Out pvR = new Out(Kind.PREVIEW, "preview-1");
    private final Out pvF = new Out(Kind.PREVIEW, "preview-0");
    private final Out recS = new Out(Kind.RECORD, "recorder-2");
    private final Out recR = new Out(Kind.RECORD, "recorder-1");
    private final Out recF = new Out(Kind.RECORD, "recorder-0");
    private final Out mirror = new Out(Kind.MIRROR, "mirror");

    /** 最近这一轮记账报的事。 */
    private List<Event> events = new ArrayList<>();
    /** 照做了的每一步。 */
    private final List<Decision> done = new ArrayList<>();

    @Before
    public void setUp() {
        w.now = T0;
        w.screenOn = true;
        w.foregroundService = true;
        w.cameraUsable = true;
        w.heardAnything = true;
    }

    // ================================================================= 该开

    @Test
    public void previewWantsEveryLaneOnlyWhileTheScreenIsOn() {
        claim(Holder.PREVIEW);
        for (Lane lane : w.lanes) {
            assertEquals(EnumSet.of(Holder.PREVIEW), ChannelRules.wantedBy(w, lane));
        }
        w.screenOn = false;
        for (Lane lane : w.lanes) {
            assertFalse("熄屏时主界面看不见，PREVIEW 不算", ChannelRules.wanted(w, lane));
        }
    }

    @Test
    public void recordingWantsTheLanesInItsPlan() {
        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);
        w.recordPlan.add(REAR);
        assertTrue(ChannelRules.wanted(w, surround));
        assertTrue(ChannelRules.wanted(w, rear));
        assertFalse("不在这次录像计划里", ChannelRules.wanted(w, front));
        w.screenOn = false;
        assertTrue("熄屏照录", ChannelRules.wanted(w, surround));
    }

    @Test
    public void photoWantsEveryLaneAndTheMirrorOnlyTheSurround() {
        claim(Holder.MIRROR);
        assertEquals(EnumSet.of(Holder.MIRROR), ChannelRules.wantedBy(w, surround));
        assertFalse(ChannelRules.wanted(w, rear));
        assertFalse(ChannelRules.wanted(w, front));
        claim(Holder.PHOTO);
        assertEquals(EnumSet.of(Holder.MIRROR, Holder.PHOTO), ChannelRules.wantedBy(w, surround));
        assertEquals(EnumSet.of(Holder.PHOTO), ChannelRules.wantedBy(w, rear));
        assertEquals(EnumSet.of(Holder.PHOTO), ChannelRules.wantedBy(w, front));
    }

    /** 拍照（§一）：没开的路按次序开、带出帧口；已经开着的路什么都不改；拍完出帧口也不摘。 */
    @Test
    public void photoOpensClosedLanesWithTheSinkAndLeavesOpenOnesAlone() {
        // 主界面最小化：环视开着，预览画布还活着，PREVIEW 没登记 —— 留着的活输出
        streaming(surround, pvS);
        claim(Holder.PHOTO);
        assertEquals(Move.IDLE, run().move);
        assertEquals(2, done.size());
        assertEquals(Collections.singletonList(REAR), done.get(0).keys);
        assertEquals(set(Out.SINK), done.get(0).outputs);
        assertEquals(Collections.singletonList(FRONT), done.get(1).keys);
        assertEquals(set(Out.SINK), done.get(1).outputs);
        assertEquals("开着的环视什么都不改", 0, count(Move.APPLY));
        assertEquals(set(pvS), surround.session);

        drop(Holder.PHOTO);
        assertEquals(Move.IDLE, round().move);
        assertEquals("拍完：开着的路不动，出帧口不摘", 2, done.size());
        assertEquals(set(Out.SINK), rear.session);
    }

    // ================================================================= 保持（§7.1：每一路各自算）

    @Test
    public void holdsAnUnwantedLaneThirtySecondsWithTheScreenOnAndTheForegroundService() {
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);

        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        Event held = event(Event.Type.HOLD_STARTED);
        assertNotNull(held);
        assertEquals(Arrays.asList(SUR, REAR, FRONT), held.keys);

        at(ChannelRules.HOLD_MS - 1);
        assertEquals(Move.IDLE, round().move);

        at(ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals("环视单独先关", Collections.singletonList(SUR), d.keys);
        assertEquals(Why.HOLD_OVER, d.why);
        assertEquals(ChannelRules.CLOSE_SURROUND_MAX_MS, d.maxMs);
        perform(d);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.HOLD_OVER, d.why);
        assertEquals(ChannelRules.CLOSE_CABINS_MAX_MS, d.maxMs);
        assertEquals("会话一次都没改", 0, count(Move.APPLY));
    }

    @Test
    public void noHoldWithoutTheForegroundService() {
        w.foregroundService = false;
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);
        drop(Holder.PREVIEW);
        Decision d = round();
        assertNull(event(Event.Type.HOLD_STARTED));
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.UNWANTED, d.why);
        perform(d);
        d = round();
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.UNWANTED, d.why);
    }

    /**
     * 熄屏时 PREVIEW 不算、不保持（§7.5，项目所有者 2026-10-11）：开发者「熄屏录制（阻止休眠）」开着时主界面熄屏不退后台，
     * PREVIEW 还登记着，没在录也照登记表按次序关。
     */
    @Test
    public void screenOffClosesWhenNotRecordingEvenWithTheDevWakeLock() {
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);
        w.screenOn = false;
        Decision d = round();
        assertNull(event(Event.Type.HOLD_STARTED));
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SCREEN_OFF, d.why);
        perform(d);
        d = round();
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.SCREEN_OFF, d.why);
    }

    @Test
    public void noHoldWhenTheHeadUnitSleepsOrOnExit() {
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);
        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.holding(w, rear));

        w.sleepSince = w.now;
        assertFalse(ChannelRules.holding(w, rear));
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Why.SLEEP, d.why);

        w.sleepSince = 0;
        w.exitSince = w.now;
        assertFalse(ChannelRules.holding(w, rear));
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Why.EXIT, d.why);
    }

    @Test
    public void aClosedLaneIsNeverOpenedToHold() {
        streaming(surround, pvS);
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.holding(w, surround));
        assertFalse(ChannelRules.shouldOpen(w, rear));
        assertFalse(ChannelRules.shouldOpen(w, front));
        at(ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertTrue(done.isEmpty());
    }

    /**
     * §7.1 每一路各自算（项目所有者 2026-10-11）：只开超级后视镜、主界面最小化时，两路座舱各自保持 30 秒，
     * 30 秒内回到主界面通道零变动；超过 30 秒只关两路座舱，环视留给后视镜。
     */
    @Test
    public void eachLaneHoldsOnItsOwn() {
        claim(Holder.PREVIEW);
        claim(Holder.MIRROR);
        streaming(surround, pvS, mirror);
        streaming(rear, pvR);
        streaming(front, pvF);
        assertEquals(Move.IDLE, round().move);

        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        assertEquals(Arrays.asList(REAR, FRONT), event(Event.Type.HOLD_STARTED).keys);

        at(20_000);
        claim(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        assertEquals(Arrays.asList(REAR, FRONT), event(Event.Type.HOLD_CANCELLED).keys);
        assertTrue("30 秒内回来，通道零变动", done.isEmpty());

        at(25_000);
        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        at(25_000 + ChannelRules.HOLD_MS - 1);
        assertEquals(Move.IDLE, round().move);
        at(25_000 + ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals("只关座舱，环视留给后视镜", Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(Why.HOLD_OVER, d.why);
    }

    // ================================================================= 顺序

    /** 手上那一步没做完（含等第一帧）：别的路一步都不动。 */
    @Test
    public void theStepInHandBlocksEverythingIncludingTheWaitForTheFirstFrame() {
        claim(Holder.PREVIEW);
        declarePreviews();
        Decision d = round();
        assertEquals(Move.OPEN, d.move);
        startOnly(d);
        at(500);
        opened(surround, d.outputs);
        Decision waiting = round();
        assertEquals(Move.WAIT, waiting.move);
        assertEquals(Why.STEP, waiting.why);
        assertEquals(ChannelRules.STEP_MAX_MS - 500, waiting.ms);
        at(3_000);
        frame(surround);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
    }

    /** 别人的在途（一路 isBusy、另一个实例在关、一路正被接手）也挡住一切，各最多 60 秒。 */
    @Test
    public void othersInFlightBlockEverythingForAtMostSixtySeconds() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        front.declared.add(pvF);

        rear.busySince = w.now;
        assertWait(Why.BUSY, REAR, round());
        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertWait(Why.BUSY, REAR, round());
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals(Move.OPEN, round().move);

        rear.busySince = 0;
        w.otherClosingSince = w.now;
        assertEquals(Why.OTHER_INSTANCE, round().why);
        at(2 * ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals(Move.OPEN, round().move);

        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDisconnect());
        assertWait(Why.LOSING, REAR, round());
    }

    @Test
    public void closesBeforeOpening() {
        w.foregroundService = false;
        claim(Holder.RECORDING);
        w.recordPlan.add(REAR);
        rear.declared.add(recR);
        streaming(surround, pvS);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        perform(d);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(set(recR), d.outputs);
    }

    /** §二.2：环视单独先关，关完了座舱才一起关（2026-10-09：环视比座舱晚关完，下一次打开它一帧不出）。 */
    @Test
    public void theSurroundClosesAloneThenTheCabinsTogether() {
        w.foregroundService = false;
        streamingAll();
        Decision d = round();
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(ChannelRules.CLOSE_SURROUND_MAX_MS, d.maxMs);
        startOnly(d);
        at(100);
        assertWait(Why.STEP, SUR, round());
        at(200);
        closed(surround);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        assertEquals(ChannelRules.CLOSE_CABINS_MAX_MS, d.maxMs);
    }

    /** 环视关卡住了：10 秒到点不再等它，接着关座舱。 */
    @Test
    public void aStuckSurroundCloseStopsHoldingTheCabinsAfterTenSeconds() {
        w.foregroundService = false;
        streamingAll();
        Decision d = round();
        startOnly(d);
        at(ChannelRules.CLOSE_SURROUND_MAX_MS - 1);
        assertEquals(Outcome.RUNNING, ChannelRules.outcome(w));
        assertWait(Why.STEP, SUR, round());
        at(ChannelRules.CLOSE_SURROUND_MAX_MS);
        assertEquals(Outcome.TIMED_OUT, ChannelRules.outcome(w));
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
    }

    @Test
    public void closingDoesNotWaitForQuiet() {
        w.foregroundService = false;
        streamingAll();
        w.lastOthersChangeAt = w.now;
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
    }

    /** §二.3：环视 → 后座舱 → 前座舱，上一路出第一帧、或者满 15 秒，才开下一路；满 15 秒的由救援接手、算第 1 次。 */
    @Test
    public void opensSurroundThenRearThenFrontOneAtATime() {
        claim(Holder.PREVIEW);
        declarePreviews();
        Decision d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(set(pvS), d.outputs);
        assertEquals(EnumSet.of(Holder.PREVIEW), d.forWhom);
        assertEquals(ChannelRules.STEP_MAX_MS, d.maxMs);
        startOnly(d);
        at(800);
        opened(surround, d.outputs);
        assertEquals("配好了还没出画面：下一路不开", Move.WAIT, round().move);
        at(1_100);
        frame(surround);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        startOnly(d);

        // 后座舱一直没回音，相机层还挂着那一下打开
        at(1_100 + ChannelRules.STEP_MAX_MS - 1);
        assertWait(Why.STEP, REAR, round());
        at(1_100 + ChannelRules.STEP_MAX_MS);
        d = round();
        assertEquals("满 15 秒还没出画面：先往下走", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
        assertEquals("由救援接手，算第 1 次", 1, rear.ladder.attempts());
    }

    @Test
    public void aTakenLaneIsSkippedNotWaitedFor() {
        claim(Holder.PREVIEW);
        declarePreviews();
        rear.absent = true;
        assertEquals(Move.IDLE, run().move);
        assertEquals(2, done.size());
        assertEquals(Collections.singletonList(SUR), done.get(0).keys);
        assertEquals(Collections.singletonList(FRONT), done.get(1).keys);
        assertEquals(CameraTaken.Verdict.TAKEN, rear.mark);
        assertEquals(Condition.TAKEN, ChannelRules.condition(rear));
    }

    @Test
    public void nothingOpensWhileTheCameraCannotBeUsed() {
        w.cameraUsable = false;
        claim(Holder.RECORDING);
        planAll();
        surround.declared.add(recS);
        rear.declared.add(recR);
        front.declared.add(recF);
        Decision d = round();
        assertEquals(Move.IDLE, d.move);
        assertEquals(SUR, d.deferredKey);
        at(60_000);
        assertEquals(Move.IDLE, round().move);
        assertTrue(done.isEmpty());
        for (Lane lane : w.lanes) {
            assertEquals("不计次数", 0, lane.ladder.attempts());
        }
        w.cameraUsable = true;
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(set(recS), d.outputs);
    }

    // ================================================================= 安静（§二.6）

    @Test
    public void quietHoldsBackOpening() {
        claim(Holder.PREVIEW);
        declarePreviews();
        w.lastOthersChangeAt = w.now - (ChannelRules.QUIET_MS - 1);
        Decision d = round();
        assertWait(Why.QUIET, SUR, d);
        assertEquals(Move.OPEN, d.pending);
        assertEquals(1, d.ms);
        at(1);
        assertEquals(Move.OPEN, round().move);
    }

    @Test
    public void quietHoldsBackDeadRemovalApplyAndRescue() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, Out.SINK);
        streaming(front, pvF);
        rear.declared.add(pvR);
        // 环视的画布被系统收走了（撤回了声明，会话里的那一个死了）
        surround.dead.add(pvS);
        surround.declared.remove(pvS);

        w.lastOthersChangeAt = w.now;
        Decision d = round();
        assertWait(Why.QUIET, SUR, d);
        assertEquals(Move.APPLY, d.pending);
        at(ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Why.DEAD, d.why);
        assertEquals(set(Out.SINK), d.outputs);
        assertEquals(set(pvS), d.removed);
        perform(d);

        w.lastOthersChangeAt = w.now;
        d = round();
        assertWait(Why.QUIET, REAR, d);
        assertEquals(Move.APPLY, d.pending);
        at(2 * ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Why.MISSING, d.why);
        perform(d);

        front.progressAgeMs = CameraLiveness.STUCK_MS;
        w.lastOthersChangeAt = w.now;
        d = round();
        assertWait(Why.QUIET, FRONT, d);
        assertEquals(Move.RESCUE, d.pending);
        at(3 * ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
    }

    /** 我们自己开、关时相机服务报的那几声不挡安静；我们稳稳开着时它报的「空闲」算别人的（交接时的乱报）。 */
    @Test
    public void ourOwnOpenAndCloseDoNotCountAgainstQuiet() {
        claim(Holder.PREVIEW);
        declarePreviews();
        Decision d = round();
        startOnly(d);
        serviceSays(surround, false, true);
        at(400);
        opened(surround, d.outputs);
        frame(surround);
        d = round();
        assertEquals("我们自己开引起的那一声不挡", Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        perform(d);
        serviceSays(surround, true, false);
        d = round();
        assertWait(Why.QUIET, FRONT, d);
        assertEquals(Move.OPEN, d.pending);
    }

    // ================================================================= 减输出能拖就拖（§二.4）

    @Test
    public void aLiveOutputNobodyWantsStaysInTheSession() {
        claim(Holder.PREVIEW);
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, pvS, recS);
        streaming(rear, pvR, recR);
        streaming(front, pvF, recF);
        assertEquals(Move.IDLE, round().move);
        // 主界面最小化，录像照录：预览画布还活着、没人要了
        drop(Holder.PREVIEW);
        assertEquals(Move.IDLE, round().move);
        w.screenOn = false;
        assertEquals(Move.IDLE, round().move);
        assertTrue(done.isEmpty());
        assertEquals(set(pvS, recS), surround.session);
    }

    /** §7 解读 4：死输出排在开前面摘 —— 有一路待开、同时环视的预览死了，先摘环视的死预览。 */
    @Test
    public void deadOutputsAreRemovedBeforeOpening() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(front, pvF);
        rear.declared.add(pvR);
        surround.dead.add(pvS);
        surround.declared.remove(pvS);
        Decision d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.DEAD, d.why);
        assertEquals(set(pvS), d.removed);
        perform(d);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
    }

    @Test
    public void addingAnOutputDropsTheSpareOnesAlongTheWay() {
        claim(Holder.PREVIEW);
        streaming(surround, Out.SINK);
        streaming(rear, pvR);
        streaming(front, pvF);
        surround.declared.add(pvS);
        Decision d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Why.MISSING, d.why);
        assertEquals(set(pvS), d.outputs);
        assertEquals(set(pvS), d.added);
        assertEquals(set(Out.SINK), d.removed);
    }

    /** 保持中的路被迫改输出（画布被收走）：留着的录像输出就是它的出帧口，照留，不换成出帧口。 */
    @Test
    public void aHeldLaneForcedToChangeKeepsItsRecordOutputInsteadOfTheSink() {
        // 停了录像、主界面在后台：环视在保持，会话里带着停放的录像输出和预览
        streaming(surround, pvS, recS);
        surround.declared.remove(pvS);
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.holding(w, surround));
        surround.dead.add(pvS);
        Decision d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Why.DEAD, d.why);
        assertEquals(set(recS), d.outputs);
        assertEquals(set(pvS), d.removed);
        assertTrue(d.added.isEmpty());
    }

    @Test
    public void stopRecordingThenHoldThenCloseNeverTouchesTheSession() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        assertEquals(Move.IDLE, round().move);
        // 停录：录制器停放，录像输出留在会话里
        drop(Holder.RECORDING);
        assertEquals(Move.IDLE, round().move);
        at(10_000);
        assertEquals(Move.IDLE, round().move);
        at(ChannelRules.HOLD_MS);
        Decision d = round();
        assertEquals(Collections.singletonList(SUR), d.keys);
        perform(d);
        d = round();
        assertEquals(Arrays.asList(REAR, FRONT), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals(0, count(Move.APPLY));
        assertEquals(2, done.size());
    }

    /**
     * §7.6（项目所有者 2026-10-11）：主界面重建（换日夜模式、换语言）时预览画面由我们留着 —— 撤回声明，SurfaceTexture 不放；
     * 新界面接回同一个 SurfaceTexture，就是同一个输出。停录 → 重建 → 30 秒内再开录：通道零变动，录制器沿用。
     */
    @Test
    public void aRecreatedMainScreenThatReattachesThePreviewChangesNothing() {
        claim(Holder.PREVIEW);
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, pvS, recS);
        streaming(rear, pvR, recR);
        streaming(front, pvF, recF);
        assertEquals(Move.IDLE, round().move);
        drop(Holder.RECORDING);
        assertEquals(Move.IDLE, round().move);

        drop(Holder.PREVIEW);
        for (Lane lane : w.lanes) {
            lane.declared.removeIf(out -> out.kind == Kind.PREVIEW);
        }
        assertEquals(Move.IDLE, round().move);

        at(2_000);
        claim(Holder.PREVIEW);
        surround.declared.add(pvS);
        rear.declared.add(pvR);
        front.declared.add(pvF);
        assertEquals(Move.IDLE, round().move);

        at(20_000);
        claim(Holder.RECORDING);
        assertEquals(Move.IDLE, round().move);
        assertTrue("通道零变动", done.isEmpty());
        assertEquals("录制器沿用", set(pvS, recS), surround.session);
    }

    /**
     * 画布真被系统收走（没留住），新画布和再开录在调度的同一轮里到：每一路只有一次改输出 —— 摘死的预览、加新的，
     * 录像输出（停放的录制器）照留，录制器沿用。
     */
    @Test
    public void aCollectedCanvasAndANewOneCostOneApplyPerLaneAndKeepTheRecorder() {
        claim(Holder.PREVIEW);
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, pvS, recS);
        streaming(rear, pvR, recR);
        streaming(front, pvF, recF);
        assertEquals(Move.IDLE, round().move);
        drop(Holder.RECORDING);
        assertEquals(Move.IDLE, round().move);

        Out[] old = {pvS, pvR, pvF};
        Lane[] lanes = {surround, rear, front};
        for (int i = 0; i < lanes.length; i++) {
            lanes[i].dead.add(old[i]);
            lanes[i].declared.remove(old[i]);
            lanes[i].declared.add(new Out(Kind.PREVIEW, "new-" + lanes[i].cameraId));
        }
        claim(Holder.RECORDING);
        assertEquals(Move.IDLE, run().move);
        assertEquals(3, count(Move.APPLY));
        for (int i = 0; i < lanes.length; i++) {
            assertEquals(1, count(Move.APPLY, lanes[i]));
        }
        for (Decision d : done) {
            assertEquals(1, d.removed.size());
            assertEquals(Kind.PREVIEW, d.removed.iterator().next().kind);
        }
        assertTrue("录制器沿用", surround.session.contains(recS));
        assertTrue(rear.session.contains(recR));
        assertTrue(front.session.contains(recF));
    }

    // ================================================================= 录像输出

    @Test
    public void aColdStartWithRecordingOpensEveryLaneWithItsRecordOutput() {
        claim(Holder.RECORDING);
        planAll();
        surround.declared.add(recS);
        rear.declared.add(recR);
        front.declared.add(recF);
        assertEquals(Move.IDLE, run().move);
        assertEquals(3, count(Move.OPEN));
        assertEquals(0, count(Move.APPLY));
        assertEquals(set(recS), done.get(0).outputs);
        assertEquals(set(recR), done.get(1).outputs);
        assertEquals(set(recF), done.get(2).outputs);
    }

    @Test
    public void aRecordOutputNotReadyInTwoSecondsIsAddedLaterWithOneApply() {
        claim(Holder.RECORDING);
        planAll();
        surround.record = RecordPrep.PREPARING;
        rear.declared.add(recR);
        front.declared.add(recF);
        Decision d = round();
        assertWait(Why.RECORD_OUTPUT, SUR, d);
        assertEquals(Move.OPEN, d.pending);
        assertEquals(ChannelRules.RECORD_OUTPUT_WAIT_MS, d.ms);
        at(1_000);
        d = round();
        assertWait(Why.RECORD_OUTPUT, SUR, d);
        assertEquals("等的时候后面的路也不开", 1_000, d.ms);
        at(ChannelRules.RECORD_OUTPUT_WAIT_MS);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals("2 秒还没好：先开", set(Out.SINK), d.outputs);
        perform(d);
        assertEquals(Move.IDLE, run().move);
        assertEquals(set(recR), done.get(1).outputs);

        surround.record = RecordPrep.NONE;
        surround.declared.add(recS);
        d = round();
        assertEquals(Move.APPLY, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(set(recS), d.outputs);
        assertEquals(set(Out.SINK), d.removed);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals(1, count(Move.APPLY));
    }

    // ================================================================= 能用相机

    /** 前台服务是后台起的：不开、不重开、不计次数；主界面回到前台以后照常。 */
    @Test
    public void aServiceStartedInTheBackgroundNeitherReopensNorCounts() {
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        assertEquals(Move.IDLE, round().move);
        w.cameraUsable = false;
        ChannelRules.lossStarted(w, surround, CameraTaken.Reason.ofDeviceError(DEVICE_ERROR));
        at(500);
        serviceSays(surround, true, true);
        ChannelRules.lossClosed(w, surround);
        closed(surround);
        at(800);
        Decision d = round();
        assertEquals(CameraTaken.Verdict.ORDINARY, event(Event.Type.JUDGED).verdict);
        assertEquals(Move.IDLE, d.move);
        assertEquals(SUR, d.deferredKey);
        at(60_000);
        assertEquals(Move.IDLE, round().move);
        assertEquals(0, surround.ladder.attempts());
        assertTrue(done.isEmpty());

        w.cameraUsable = true;
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(1, d.attempt);
    }

    // ================================================================= 开失败、被拿走（§二.7）

    /**
     * 冷启动时车机还拿着相机 1（相机服务的缓存里没有它）：直接判 TAKEN，零次试开；
     * 它回来、说空闲了，安静满 3 秒，只开一次。
     */
    @Test
    public void aMissingCameraAtColdStartIsTakenWithoutTryingAndOpensOnceWhenFree() {
        claim(Holder.PREVIEW);
        declarePreviews();
        rear.absent = true;
        assertEquals(Move.IDLE, run().move);
        assertEquals(0, count(Move.OPEN, rear));
        assertEquals(CameraTaken.Verdict.TAKEN, rear.mark);
        assertTrue(ChannelRules.askDue(w));
        ChannelRules.asked(w);

        at(60_000);
        serviceSays(rear, true, false);
        Decision d = round();
        Event released = event(Event.Type.RELEASED);
        assertNotNull(released);
        assertEquals(60_000, released.ms);
        assertWait(Why.QUIET, REAR, d);
        assertEquals(Move.OPEN, d.pending);
        at(60_000 + ChannelRules.QUIET_MS - 1);
        assertWait(Why.QUIET, REAR, round());
        at(60_000 + ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals(1, count(Move.OPEN, rear));
    }

    /** 主动认只认别人的：相机服务说「被占用（我们开着）」的，关着也不算被拿走。 */
    @Test
    public void recognizesOnlyWhatOthersHold() {
        serviceSays(rear, false, true);
        serviceSays(front, false, false);
        w.lastOthersChangeAt = 0;
        round();
        assertNull(rear.mark);
        assertEquals(CameraTaken.Verdict.TAKEN, front.mark);
        Event recognized = event(Event.Type.RECOGNIZED);
        assertEquals(Collections.singletonList(FRONT), recognized.keys);
        assertFalse(recognized.absent);
    }

    /** 1 是 TAKEN 时开 2，报 MAX_CAMERAS_IN_USE：判 BLOCKED、不计次数；1 放开了，两路一起放开，先开环视再开后座舱。 */
    @Test
    public void maxCamerasWhileAnotherIsTakenIsBlockedAndNotCounted() {
        claim(Holder.PREVIEW);
        declarePreviews();
        serviceSays(rear, false, false);
        w.lastOthersChangeAt = 0;
        Decision d = round();
        assertNotNull(event(Event.Type.RECOGNIZED));
        assertEquals(Collections.singletonList(SUR), d.keys);
        startOnly(d);
        serviceSays(surround, false, true);
        at(400);
        ChannelRules.openFailed(w, surround,
                CameraTaken.Reason.ofAccess(CameraAccessException.MAX_CAMERAS_IN_USE));
        surround.busySince = 0;
        serviceSays(surround, true, true);
        assertWait(Why.JUDGING, SUR, round());

        at(700);
        d = round();
        Event judged = event(Event.Type.JUDGED);
        assertEquals(CameraTaken.Verdict.BLOCKED, judged.verdict);
        assertEquals(CameraTaken.Verdict.BLOCKED, surround.mark);
        assertEquals("不计次数", 0, surround.ladder.attempts());
        assertEquals("被拿着的跳过，不挡后面", Collections.singletonList(FRONT), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);

        at(10_000);
        serviceSays(rear, true, false);
        d = round();
        assertEquals(2, all(Event.Type.RELEASED).size());
        assertWait(Why.QUIET, SUR, d);
        at(10_000 + ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Collections.singletonList(SUR), d.keys);
        perform(d);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
    }

    /** 开失败报错误 4：普通故障 —— 第一次开就算第 1 次，之后 12 秒一次，3 次歇 60 秒，3 轮停手。 */
    @Test
    public void error4RetriesEveryTwelveSecondsAndStopsAfterThreeRounds() {
        claim(Holder.MIRROR);
        surround.declared.add(mirror);
        List<Long> tries = new ArrayList<>();
        List<Long> gaveUp = new ArrayList<>();
        List<Long> stopped = new ArrayList<>();
        for (long t = 0; t <= 600_000; t += ChannelRules.TICK_MS) {
            at(t);
            Decision d = round();
            for (Event e : d.events) {
                if (e.type == Event.Type.GAVE_UP) {
                    gaveUp.add(t);
                } else if (e.type == Event.Type.STOPPED) {
                    stopped.add(t);
                }
            }
            if (d.move == Move.OPEN || d.move == Move.RESCUE) {
                startOnly(d);
                tries.add(t);
                ChannelRules.openFailed(w, surround, CameraTaken.Reason.ofDeviceError(DEVICE_ERROR));
                surround.busySince = 0;
                serviceSays(surround, true, true);
            }
        }
        assertEquals(Arrays.asList(0L, 12_000L, 24_000L, 96_000L, 108_000L, 120_000L,
                192_000L, 204_000L, 216_000L), tries);
        assertEquals(Arrays.asList(36_000L, 132_000L), gaveUp);
        assertEquals(Collections.singletonList(228_000L), stopped);
        assertTrue(surround.ladder.stopped());
        assertEquals(Condition.GAVE_UP, ChannelRules.condition(surround));
        assertTrue(ChannelRules.excludedFromPhoto(surround));
    }

    /** 报错误 3 / CAMERA_DISABLED（现在用不了相机）：不计次数，等主界面回到前台。 */
    @Test
    public void cameraDisabledIsNotCountedAndWaitsForTheMainScreen() {
        claim(Holder.MIRROR);
        surround.declared.add(mirror);
        Decision d = round();
        startOnly(d);
        at(300);
        ChannelRules.openFailed(w, surround,
                CameraTaken.Reason.ofDeviceError(CameraDevice.StateCallback.ERROR_CAMERA_DISABLED));
        surround.busySince = 0;
        at(600);
        d = round();
        assertEquals(CameraTaken.Verdict.UNUSABLE, event(Event.Type.JUDGED).verdict);
        assertEquals(Move.IDLE, d.move);
        assertEquals(SUR, d.deferredKey);
        assertEquals(0, surround.ladder.attempts());
        at(60_000);
        assertEquals(Move.IDLE, round().move);
        assertEquals(1, done.size());

        ChannelRules.mainCameForward(w);
        d = round();
        assertEquals(Move.OPEN, d.move);
        startOnly(d);
        at(60_300);
        ChannelRules.openFailed(w, surround,
                CameraTaken.Reason.ofAccess(CameraAccessException.CAMERA_DISABLED));
        surround.busySince = 0;
        at(60_600);
        d = round();
        assertEquals(CameraTaken.Verdict.UNUSABLE, event(Event.Type.JUDGED).verdict);
        assertEquals(Move.IDLE, d.move);
        assertEquals(0, surround.ladder.attempts());
    }

    /** 一个卡死的关最多占住通道 60 秒，之后按当时的状态判（大纲 §6 风险 5）。 */
    @Test
    public void aStuckLossStopsBlockingAfterSixtySecondsAndIsJudgedThen() {
        claim(Holder.PREVIEW);
        streaming(surround, pvS);
        streaming(rear, pvR);
        front.declared.add(pvF);
        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDisconnect());
        rear.busySince = w.now;
        assertWait(Why.LOSING, REAR, round());
        at(ChannelRules.IN_FLIGHT_MAX_MS - 1);
        assertWait(Why.LOSING, REAR, round());
        at(ChannelRules.IN_FLIGHT_MAX_MS);
        Decision d = round();
        assertEquals(CameraTaken.Verdict.TAKEN, event(Event.Type.JUDGED).verdict);
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(FRONT), d.keys);
    }

    // ================================================================= 车机要睡、退出

    /** 车机要睡：手上那一步最多再等 1 秒，然后按次序关（环视单独先关）；亮屏、这项事实清掉以后照常开。 */
    @Test
    public void headUnitSleepWaitsAtMostOneSecondThenClosesInOrder() {
        claim(Holder.PREVIEW);
        declarePreviews();
        perform(round());
        Decision d = round();
        assertEquals(Collections.singletonList(REAR), d.keys);
        startOnly(d);

        at(400);
        w.sleepSince = w.now;
        d = round();
        assertWait(Why.STEP, REAR, d);
        assertEquals(ChannelRules.SLEEP_GRACE_MS, d.ms);
        at(400 + ChannelRules.SLEEP_GRACE_MS - 1);
        assertEquals(Move.WAIT, round().move);
        at(400 + ChannelRules.SLEEP_GRACE_MS);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.SLEEP, d.why);
        startOnly(d);
        at(1_500);
        assertWait(Why.SURROUND_CLOSING, SUR, round());
        at(1_600);
        closed(surround);
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals("开到一半的后座舱一起关", Collections.singletonList(REAR), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.allClosed(w));

        w.sleepSince = 0;
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
    }

    /** 退出：等手上那一步做完（没有 1 秒的宽限），再按次序关。 */
    @Test
    public void exitWaitsForTheStepInHandThenCloses() {
        claim(Holder.PREVIEW);
        declarePreviews();
        Decision open = round();
        startOnly(open);
        at(100);
        w.exitSince = w.now;
        assertWait(Why.STEP, SUR, round());
        at(1_500);
        assertWait(Why.STEP, SUR, round());
        at(2_000);
        opened(surround, open.outputs);
        frame(surround);
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.EXIT, d.why);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertFalse(ChannelRules.askDue(w));
    }

    @Test
    public void deadOutputsCauseNoApplyWhenSleepingOrExiting() {
        claim(Holder.PREVIEW);
        streamingAll();
        surround.dead.add(pvS);
        w.sleepSince = w.now;
        Decision d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        w.sleepSince = 0;
        w.exitSince = w.now;
        d = round();
        assertEquals(Move.CLOSE, d.move);
        assertEquals(Why.EXIT, d.why);
    }

    // ================================================================= 救援（§二.5）

    @Test
    public void rescuesTheSurroundFirst() {
        claim(Holder.PREVIEW);
        streamingAll();
        rear.progressAgeMs = 9_000;
        surround.progressAgeMs = 9_000;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Why.NO_PROGRESS, d.why);
    }

    /** 第一次救、本会话出过画面才重建会话（便宜）；重建没用，12 秒后第 2 次就重开。 */
    @Test
    public void theFirstRescueRebuildsOnlyIfTheSessionHadStreamed() {
        claim(Holder.MIRROR);
        streaming(surround, mirror);
        surround.progressAgeMs = CameraLiveness.STUCK_MS;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REBUILD, d.rescue);
        assertEquals(1, d.attempt);
        assertEquals(set(mirror), d.outputs);
        startOnly(d);
        at(500);
        configured(surround, d.outputs);
        at(ChannelRules.STEP_MAX_MS);
        surround.progressAgeMs = ChannelRules.STEP_MAX_MS - 500;
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(2, d.attempt);
    }

    /** 配好了却一帧都没出过：第一次救就重开（重建没有东西可排空，2026-10-08 实测环视一次 4–13 秒还报错）。 */
    @Test
    public void aSessionThatNeverStreamedIsReopenedAtTheFirstRescue() {
        claim(Holder.MIRROR);
        surround.declared.add(mirror);
        surround.device = Device.OPEN;
        surround.session.add(mirror);
        surround.sessionSince = w.now - 9_000;
        surround.progressAgeMs = 9_000;
        Decision d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(1, d.attempt);
    }

    @Test
    public void aLaneThatIsOnlyHeldIsNotRescued() {
        streaming(surround, pvS);
        surround.progressAgeMs = 20_000;
        assertEquals(Move.IDLE, round().move);
        assertTrue(ChannelRules.holding(w, surround));
        assertEquals(0, surround.ladder.attempts());
    }

    /** 登记表变了，停手的那一路从头救（项目所有者 2026-10-10）。 */
    @Test
    public void aRegisterChangeLiftsAStop() {
        claim(Holder.MIRROR);
        surround.declared.add(mirror);
        round();
        stopLadder(surround);
        at(1_000);
        assertEquals(Move.IDLE, round().move);
        assertEquals(Condition.GAVE_UP, ChannelRules.condition(surround));

        claim(Holder.RECORDING);
        w.recordPlan.add(SUR);
        Decision d = round();
        Event lifted = event(Event.Type.STOP_LIFTED);
        assertNotNull(lifted);
        assertEquals(Event.Cause.NEEDS_CHANGED, lifted.cause);
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(1, d.attempt);
    }

    /** §7.4（项目所有者 2026-10-11）：停手之后，相机服务报它「空闲」、或者亮屏，也解除停手、从头救。 */
    @Test
    public void theServiceSayingFreeOrTheScreenComingOnLiftsAStop() {
        claim(Holder.MIRROR);
        surround.declared.add(mirror);
        round();
        stopLadder(surround);
        at(1_000);
        assertEquals(Move.IDLE, round().move);

        at(5_000);
        serviceSays(surround, true, false);
        Decision d = round();
        assertEquals(Event.Cause.SERVICE_FREE, event(Event.Type.STOP_LIFTED).cause);
        assertWait(Why.QUIET, SUR, d);
        assertEquals(Move.RESCUE, d.pending);
        at(5_000 + ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(1, d.attempt);

        at(10_000);
        stopLadder(surround);
        assertEquals(Move.IDLE, round().move);
        at(11_000);
        w.screenOnAt = w.now;
        d = round();
        assertEquals(Event.Cause.SCREEN_ON, event(Event.Type.STOP_LIFTED).cause);
        assertEquals(Move.RESCUE, d.move);
        assertEquals(1, d.attempt);
    }

    // ================================================================= 现场回放（diag_15_bb.txt，10-10；09-27 的冲突）

    /**
     * (a) 10-10 07:22（:149-166）：录着三路、主界面最小化，车机拿走后座舱相机 1。关它的 5.4 秒里相机 0 和 2 一步都不动；
     * 关完判 TAKEN；07:31:22 报「1 空闲」之后至少等 3 秒，相机 1 单独开。
     */
    @Test
    public void replayA_rearCabinTakenWhileRecording() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        assertEquals(Move.IDLE, round().move);

        serviceSays(front, true, false);
        serviceSays(surround, true, false);
        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDisconnect());
        rear.busySince = w.now;
        for (long t = 0; t < 5_409; t += 500) {
            at(t);
            assertWait(Why.LOSING, REAR, round());
        }
        at(5_409);
        ChannelRules.lossClosed(w, rear);
        closed(rear);
        assertWait(Why.JUDGING, REAR, round());
        at(5_709);
        Decision d = round();
        assertEquals(CameraTaken.Verdict.TAKEN, event(Event.Type.JUDGED).verdict);
        assertEquals("被拿着从丢的那一刻算", T0, rear.markSince);
        assertEquals(0, rear.ladder.attempts());
        assertEquals(Move.IDLE, d.move);
        assertTrue("0 和 2 一步都没动", done.isEmpty());
        assertTrue(ChannelRules.askDue(w));
        ChannelRules.asked(w);

        long freeAt = 561_982;
        at(freeAt);
        serviceSays(rear, true, false);
        d = round();
        assertNotNull(event(Event.Type.RELEASED));
        assertWait(Why.QUIET, REAR, d);
        at(freeAt + ChannelRules.QUIET_MS - 1);
        assertWait(Why.QUIET, REAR, round());
        at(freeAt + ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(set(recR), d.outputs);
    }

    /** (b) 10-10 07:51:16（:209-211）：「1 空闲」比关返回早 5 ms 到 —— 关完再判，它就是最后一句：普通故障，按救援的节奏重开。 */
    @Test
    public void replayB_freeSaidJustBeforeTheCloseReturnsIsOrdinary() {
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);
        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDisconnect());
        rear.busySince = w.now;
        at(9_622);
        serviceSays(rear, true, true);
        at(9_627);
        ChannelRules.lossClosed(w, rear);
        closed(rear);
        at(9_927);
        Decision d = round();
        assertEquals(CameraTaken.Verdict.ORDINARY, event(Event.Type.JUDGED).verdict);
        assertNull(rear.mark);
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        assertEquals(Rescue.REOPEN, d.rescue);
        assertEquals(Why.DOWN, d.why);
        assertEquals(1, d.attempt);
    }

    /**
     * (c) 10-10 08:56:57–08:57:13（:302-323）：我们开着相机 2 时相机服务报了一句「2 空闲」（乱报），之后 2 出错误 4 ——
     * 最后一句是空闲、错误 4 不属于「被占用」一类：普通故障，按救援的节奏重开（不因为 1 被拿着就判 BLOCKED）。
     */
    @Test
    public void replayC_error4AfterASpuriousFreeIsOrdinary() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(rear, recR);
        streaming(front, recF);
        assertEquals(Move.IDLE, round().move);

        serviceSays(front, true, false);
        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDisconnect());
        rear.busySince = w.now;
        serviceSays(surround, true, false);
        at(7_975);
        ChannelRules.lossClosed(w, rear);
        closed(rear);
        at(8_275);
        assertEquals(Move.IDLE, round().move);
        assertEquals(CameraTaken.Verdict.TAKEN, rear.mark);

        at(14_114);
        ChannelRules.lossStarted(w, surround, CameraTaken.Reason.ofDeviceError(DEVICE_ERROR));
        surround.busySince = w.now;
        at(15_270);
        ChannelRules.lossClosed(w, surround);
        closed(surround);
        at(15_570);
        Decision d = round();
        assertEquals(CameraTaken.Verdict.ORDINARY, event(Event.Type.JUDGED).verdict);
        assertNull(surround.mark);
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(Rescue.REOPEN, d.rescue);
    }

    /** (d) 10-10 09:00:18（:329-330）：1 放开和一句假的「2 空闲」同时到 → 安静满 3 秒后，1 单独开。 */
    @Test
    public void replayD_aReleaseTogetherWithASpuriousFreeOpensTheRearAloneAfterQuiet() {
        claim(Holder.RECORDING);
        planAll();
        streaming(surround, recS);
        streaming(front, recF);
        rear.declared.add(recR);
        rear.mark = CameraTaken.Verdict.TAKEN;
        rear.markSince = T0 - 180_000;
        rear.serviceFree = Boolean.FALSE;
        rear.serviceWordOurs = true;
        rear.serviceWordAt = T0 - 200_000;
        assertEquals(Move.IDLE, round().move);

        serviceSays(rear, true, false);
        serviceSays(surround, true, false);
        Decision d = round();
        assertNotNull(event(Event.Type.RELEASED));
        assertWait(Why.QUIET, REAR, d);
        at(ChannelRules.QUIET_MS - 1);
        assertWait(Why.QUIET, REAR, round());
        at(ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Move.OPEN, d.move);
        assertEquals(Collections.singletonList(REAR), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals("只开了 1", 1, count(Move.OPEN));
    }

    /**
     * (e) 09-27 的冲突：别的程序拿走相机 1，相机服务几毫秒内把我们的相机 2 断开（1 和 2 同时只能开一路）。
     * 等两路都关完一起判、先判 TAKEN：1 TAKEN，2 BLOCKED，一次也不试开；两路都空出来以后，先开环视，再开后座舱。
     */
    @Test
    public void replayE_aHandoverConflictBlocksTheSurroundUntilBothAreFree() {
        claim(Holder.PREVIEW);
        streamingAll();
        assertEquals(Move.IDLE, round().move);

        ChannelRules.lossStarted(w, rear, CameraTaken.Reason.ofDisconnect());
        rear.busySince = w.now;
        ChannelRules.lossStarted(w, surround, CameraTaken.Reason.ofDisconnect());
        surround.busySince = w.now;
        at(50);
        serviceSays(surround, true, true);
        at(1_000);
        ChannelRules.lossClosed(w, surround);
        closed(surround);
        assertWait(Why.LOSING, REAR, round());
        assertNull("还有丢失没关完：等它一起判", surround.mark);
        at(5_400);
        ChannelRules.lossClosed(w, rear);
        closed(rear);
        at(5_700);
        Decision d = round();
        List<Event> judged = all(Event.Type.JUDGED);
        assertEquals(2, judged.size());
        assertEquals("先判 TAKEN", Collections.singletonList(REAR), judged.get(0).keys);
        assertEquals(CameraTaken.Verdict.TAKEN, judged.get(0).verdict);
        assertEquals(CameraTaken.Verdict.BLOCKED, judged.get(1).verdict);
        assertEquals(CameraTaken.Verdict.BLOCKED, surround.mark);
        assertEquals(Move.IDLE, d.move);

        for (long t = 10_000; t < 60_000; t += ChannelRules.TICK_MS) {
            at(t);
            assertEquals(Move.IDLE, round().move);
        }
        assertTrue("被拿着的时候一次也不试开", done.isEmpty());

        at(60_000);
        serviceSays(rear, true, false);
        d = round();
        assertEquals(2, all(Event.Type.RELEASED).size());
        assertWait(Why.QUIET, SUR, d);
        at(60_000 + ChannelRules.QUIET_MS);
        d = round();
        assertEquals(Collections.singletonList(SUR), d.keys);
        perform(d);
        d = round();
        assertEquals(Collections.singletonList(REAR), d.keys);
        perform(d);
        assertEquals(Move.IDLE, round().move);
        assertEquals(1, count(Move.OPEN, surround));
        assertEquals(1, count(Move.OPEN, rear));
    }

    // ================================================================= 问（只问，不开）

    @Test
    public void asksEveryThirtySecondsOnlyWhileATakenLaneIsWanted() {
        claim(Holder.PREVIEW);
        declarePreviews();
        rear.absent = true;
        round();
        assertTrue(ChannelRules.askDue(w));
        ChannelRules.asked(w);
        assertEquals(1, rear.asks);
        at(ChannelRules.ASK_EVERY_MS - 1);
        assertFalse(ChannelRules.askDue(w));
        at(ChannelRules.ASK_EVERY_MS);
        assertTrue(ChannelRules.askDue(w));
        drop(Holder.PREVIEW);
        round();
        assertFalse("没人要后座舱：不问", ChannelRules.askDue(w));
    }

    @Test
    public void asksAtOnceWhenATakenLaneBecomesWantedAgain() {
        claim(Holder.PREVIEW);
        declarePreviews();
        rear.absent = true;
        round();
        ChannelRules.asked(w);
        drop(Holder.PREVIEW);
        round();
        at(40_000);
        assertFalse(ChannelRules.askDue(w));
        claim(Holder.PREVIEW);
        assertTrue("刚变成有人要、上一次问已超过 30 秒：马上问", ChannelRules.askDue(w));
        ChannelRules.asked(w);
        at(50_000);
        assertFalse(ChannelRules.askDue(w));
    }

    // ================================================================= 一步做完没有

    @Test
    public void anOpenIsDoneOnItsFirstFrameAndFailedOnAnOpenFailure() {
        claim(Holder.PREVIEW);
        declarePreviews();
        assertEquals(Outcome.NONE, ChannelRules.outcome(w));
        Decision d = round();
        startOnly(d);
        assertEquals(Outcome.RUNNING, ChannelRules.outcome(w));
        at(300);
        opened(surround, d.outputs);
        assertEquals("配好了还没出画面", Outcome.RUNNING, ChannelRules.outcome(w));
        frame(surround);
        assertEquals(Outcome.DONE, ChannelRules.outcome(w));
        ChannelRules.finish(w, Outcome.DONE);
        assertNull(w.step);

        d = round();
        assertEquals(Collections.singletonList(REAR), d.keys);
        startOnly(d);
        at(500);
        ChannelRules.openFailed(w, rear, CameraTaken.Reason.ofNotListed());
        assertEquals(Outcome.FAILED, ChannelRules.outcome(w));
    }

    @Test
    public void anApplyIsDoneOnlyOnTheFirstFrameOfTheNewSession() {
        claim(Holder.PREVIEW);
        streaming(surround, Out.SINK);
        surround.declared.add(pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        Decision d = round();
        assertEquals(Move.APPLY, d.move);
        startOnly(d);
        assertEquals("旧会话出过的画面不算", Outcome.RUNNING, ChannelRules.outcome(w));
        at(300);
        configured(surround, d.outputs);
        assertEquals(Outcome.RUNNING, ChannelRules.outcome(w));
        frame(surround);
        assertEquals(Outcome.DONE, ChannelRules.outcome(w));
    }

    /** 改输出卡在配会话里（相机层一直没回来）：到点不再改第二次，没进展满 8 秒由救援接手。 */
    @Test
    public void aStuckApplyIsNotRepeatedAndTheRescueTakesOver() {
        claim(Holder.PREVIEW);
        streaming(surround, Out.SINK);
        surround.declared.add(pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
        Decision d = round();
        assertEquals(Move.APPLY, d.move);
        startOnly(d);
        at(ChannelRules.STEP_MAX_MS - 1);
        assertWait(Why.STEP, SUR, round());
        at(ChannelRules.STEP_MAX_MS);
        surround.progressAgeMs = ChannelRules.STEP_MAX_MS;
        d = round();
        assertEquals(Move.RESCUE, d.move);
        assertEquals(Collections.singletonList(SUR), d.keys);
        assertEquals(1, count(Move.APPLY));
    }

    // ================================================================= 状态、身份、常量

    @Test
    public void conditionAndPhotoExclusion() {
        streaming(surround, pvS);
        assertEquals(Condition.STREAMING, ChannelRules.condition(surround));
        assertFalse(ChannelRules.excludedFromPhoto(surround));
        assertEquals(Condition.BRINGING_UP, ChannelRules.condition(front));
        assertFalse(ChannelRules.excludedFromPhoto(front));

        rear.mark = CameraTaken.Verdict.BLOCKED;
        assertEquals(Condition.TAKEN, ChannelRules.condition(rear));
        assertTrue(ChannelRules.excludedFromPhoto(rear));

        front.lostForRescue = true;
        assertEquals(Condition.RESCUING, ChannelRules.condition(front));
        assertFalse(ChannelRules.excludedFromPhoto(front));
        stopLadder(front);
        assertEquals(Condition.GAVE_UP, ChannelRules.condition(front));
        assertTrue(ChannelRules.excludedFromPhoto(front));

        stopLadder(surround);
        assertEquals("停手的那一路在出画面就照拍", Condition.STREAMING, ChannelRules.condition(surround));
        assertFalse(ChannelRules.excludedFromPhoto(surround));
    }

    /** 输出按消费者认（§7.6）：同一块画布再声明一次还是同一个输出，换了画布才是另一个。 */
    @Test
    public void outputsAreIdentifiedByTheirConsumer() {
        Object canvas = new Object();
        Out once = new Out(Kind.PREVIEW, canvas);
        Out again = new Out(Kind.PREVIEW, canvas);
        assertEquals(once, again);
        assertEquals(once.hashCode(), again.hashCode());
        assertFalse(once.equals(new Out(Kind.PREVIEW, new Object())));
        assertFalse(once.equals(new Out(Kind.MIRROR, canvas)));
    }

    @Test
    public void lanesAreKeptInOpenOrder() {
        List<String> keys = new ArrayList<>();
        for (Lane lane : w.lanes) {
            keys.add(lane.key);
        }
        assertEquals(Arrays.asList(SUR, REAR, FRONT), keys);
    }

    /** 大纲 §2 1a 的常量表；退出、唤醒锁的上限由常量算出来。 */
    @Test
    public void constantsFollowTheTable() {
        assertEquals(2_000L, ChannelRules.TICK_MS);
        assertEquals(3_000L, ChannelRules.QUIET_MS);
        assertEquals(CameraTaken.QUIET_MS, ChannelRules.QUIET_MS);
        assertEquals(30_000L, ChannelRules.ASK_EVERY_MS);
        assertEquals(CameraTaken.ASK_EVERY_MS, ChannelRules.ASK_EVERY_MS);
        assertEquals(30_000L, ChannelRules.HOLD_MS);
        assertEquals(1_000L, ChannelRules.SLEEP_GRACE_MS);
        assertEquals(15_000L, ChannelRules.STEP_MAX_MS);
        assertEquals(2_000L, ChannelRules.RECORD_OUTPUT_WAIT_MS);
        assertEquals(10_000L, ChannelRules.CLOSE_SURROUND_MAX_MS);
        assertEquals(30_000L, ChannelRules.CLOSE_CABINS_MAX_MS);
        assertEquals(40_000L, ChannelRules.ORDERED_CLOSE_MAX_MS);
        assertEquals(300L, ChannelRules.JUDGE_DELAY_MS);
        assertEquals(60_000L, ChannelRules.IN_FLIGHT_MAX_MS);
        assertEquals("退出最多等：在途封顶 + 按次序关 + 5 秒", 105_000L, ChannelRules.EXIT_WAIT_MAX_MS);
        assertEquals("唤醒锁最多拉：等在途 1 秒 + 按次序关", 41_000L, ChannelRules.WAKE_HOLD_MAX_MS);
        assertEquals("梯子上的数字不变", 8_000L, CameraLiveness.STUCK_MS);
        assertEquals(12_000L, CameraLiveness.RETRY_GAP_MS);
        assertEquals(3, CameraLiveness.MAX_ATTEMPTS);
        assertEquals(60_000L, CameraLiveness.COOL_OFF_MS);
        assertEquals(3, CameraLiveness.MAX_CYCLES);
    }

    // ================================================================= 帮手：扮演相机层、登记表

    private void at(long ms) {
        w.now = T0 + ms;
    }

    private void claim(Holder holder) {
        if (w.holders.add(holder)) {
            w.needsVersion++;
        }
    }

    private void drop(Holder holder) {
        if (w.holders.remove(holder)) {
            w.needsVersion++;
        }
    }

    private void planAll() {
        w.recordPlan.addAll(Arrays.asList(SUR, REAR, FRONT));
    }

    private void declarePreviews() {
        surround.declared.add(pvS);
        rear.declared.add(pvR);
        front.declared.add(pvF);
    }

    private static Set<Out> set(Out... outs) {
        return new LinkedHashSet<>(Arrays.asList(outs));
    }

    /** 一轮：手上那一步怎样了 → 记账 → 下一步（调度在主线程上的那一轮）。 */
    private Decision round() {
        ChannelRules.finish(w, ChannelRules.outcome(w));
        events = ChannelRules.settle(w);
        return ChannelRules.next(w);
    }

    /** 一轮一轮照做（相机当场做完），直到空闲或者要等；返回最后那个答案。 */
    private Decision run() {
        for (int i = 0; i < 30; i++) {
            Decision d = round();
            if (d.move == Move.IDLE || d.move == Move.WAIT) {
                return d;
            }
            perform(d);
        }
        fail("30 步还没停下来");
        return null;
    }

    /** 照做，相机当场做完：开好、配好并出第一帧，或者关完。 */
    private void perform(Decision d) {
        startOnly(d);
        for (String key : d.keys) {
            Lane lane = w.lane(key);
            if (d.move == Move.CLOSE) {
                closed(lane);
            } else if (d.move == Move.APPLY || d.rescue == Rescue.REBUILD) {
                configured(lane, d.outputs);
                frame(lane);
            } else {
                opened(lane, d.outputs);
                frame(lane);
            }
        }
    }

    /** 照做，相机这边才开始（在开、在关、在配）。 */
    private void startOnly(Decision d) {
        assertTrue("只有一步才照做：" + d, d.move != Move.IDLE && d.move != Move.WAIT);
        ChannelRules.begin(w, d);
        done.add(d);
        for (String key : d.keys) {
            Lane lane = w.lane(key);
            lane.busySince = w.now;
            if (d.move == Move.OPEN || (d.move == Move.RESCUE && d.rescue == Rescue.REOPEN)) {
                lane.device = Device.OPENING;
                lane.deviceSince = w.now;
            } else if (d.move == Move.CLOSE) {
                lane.device = Device.CLOSING;
                lane.deviceSince = w.now;
            }
        }
    }

    private void opened(Lane lane, Set<Out> outputs) {
        lane.device = Device.OPEN;
        lane.deviceSince = w.now;
        configured(lane, outputs);
    }

    /** 会话按这一组配好了（新会话：第一帧归零，不在新会话里的死输出跟着没了）。 */
    private void configured(Lane lane, Set<Out> outputs) {
        lane.session.clear();
        lane.session.addAll(outputs);
        lane.dead.retainAll(outputs);
        lane.sessionSince = w.now;
        lane.firstFrameAt = 0;
        lane.progressAgeMs = 0;
        lane.busySince = 0;
    }

    private void frame(Lane lane) {
        if (lane.firstFrameAt == 0) {
            lane.firstFrameAt = w.now;
        }
        lane.progressAgeMs = 0;
    }

    private void closed(Lane lane) {
        lane.device = Device.CLOSED;
        lane.deviceSince = w.now;
        lane.busySince = 0;
        lane.session.clear();
        lane.dead.clear();
        lane.sessionSince = 0;
        lane.firstFrameAt = 0;
        lane.progressAgeMs = 0;
    }

    /** 一路早就开着、在出画面，会话里带着这几样（出帧口之外的都声明着）；开的时候相机服务说过「被占用（我们开着）」。 */
    private void streaming(Lane lane, Out... outs) {
        lane.device = Device.OPEN;
        lane.deviceSince = w.now - 60_000;
        lane.session.clear();
        lane.session.addAll(Arrays.asList(outs));
        for (Out out : outs) {
            if (out.kind != Kind.SINK) {
                lane.declared.add(out);
            }
        }
        lane.sessionSince = w.now - 60_000;
        lane.firstFrameAt = w.now - 59_000;
        lane.progressAgeMs = 0;
        lane.serviceFree = Boolean.FALSE;
        lane.serviceWordOurs = true;
        lane.serviceWordAt = w.now - 60_000;
    }

    private void streamingAll() {
        streaming(surround, pvS);
        streaming(rear, pvR);
        streaming(front, pvF);
    }

    /** 相机服务报了一句；别人引起的挡「安静」。报了就说明它在。 */
    private void serviceSays(Lane lane, boolean free, boolean ours) {
        lane.serviceFree = free;
        lane.serviceWordOurs = ours;
        lane.serviceWordAt = w.now;
        lane.absent = false;
        if (!ours) {
            w.lastOthersChangeAt = w.now;
        }
    }

    /** 把这一路的梯子推到彻底停手（梯子自己的钟，和 w.now 无关），记下停手的时刻。 */
    private void stopLadder(Lane lane) {
        lane.ladder.released();
        long t = 0;
        while (!lane.ladder.stopped()) {
            CameraLiveness.step(lane.ladder, true, Long.MAX_VALUE, t);
            t += CameraLiveness.COOL_OFF_MS;
            assertTrue("梯子该停手了", t < 100 * CameraLiveness.COOL_OFF_MS);
        }
        lane.stoppedAt = w.now;
    }

    private void assertWait(Why why, String key, Decision d) {
        assertEquals(d.toString(), Move.WAIT, d.move);
        assertEquals(d.toString(), why, d.why);
        assertEquals(d.toString(), key, d.key());
    }

    private int count(Move move) {
        int n = 0;
        for (Decision d : done) {
            if (d.move == move) {
                n++;
            }
        }
        return n;
    }

    private int count(Move move, Lane lane) {
        int n = 0;
        for (Decision d : done) {
            if (d.move == move && d.keys.contains(lane.key)) {
                n++;
            }
        }
        return n;
    }

    private Event event(Event.Type type) {
        for (Event e : events) {
            if (e.type == type) {
                return e;
            }
        }
        return null;
    }

    private List<Event> all(Event.Type type) {
        List<Event> out = new ArrayList<>();
        for (Event e : events) {
            if (e.type == type) {
                out.add(e);
            }
        }
        return out;
    }
}
