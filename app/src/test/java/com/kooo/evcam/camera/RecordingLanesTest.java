package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次录像里每一路自己的状态：一路被拿走只停这一路，放开了单独接回（2026-10-10）。
 *
 * <p>判错的代价：「最后一路」算多了，环视还在录就整次停录、整次重开，正是这一次要去掉的；算少了，
 * 录像里一路都不在录，界面和通知却还说在录。接回的任务认错了录制器，前一次接回的回调会打到后一次上。</p>
 */
public class RecordingLanesTest {

    private static final String SURROUND = CameraSlots.KEY_SURROUND;
    private static final String CABIN_FRONT = CameraSlots.KEY_CABIN_FRONT;
    private static final String CABIN_REAR = CameraSlots.KEY_CABIN_REAR;
    private static final long T0 = 1_000_000L;

    private static Map<String, Boolean> live(Object... pairs) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Boolean) pairs[i + 1]);
        }
        return map;
    }

    // ------------------------------------------------------------------ 最后一路

    /** 后座舱被拿走，环视、前座舱还在录：只停后座舱，不整次停。 */
    @Test
    public void aLaneLeavesAloneWhileOthersRecord() {
        assertFalse(RecordingLanes.lastLiveLane(CABIN_REAR,
                live(SURROUND, true, CABIN_FRONT, true, CABIN_REAR, true)));
        assertFalse("另一路还在录就不是最后一路", RecordingLanes.lastLiveLane(CABIN_REAR,
                live(SURROUND, false, CABIN_FRONT, true, CABIN_REAR, true)));
    }

    /** 它是最后一路在录的：录像里一路都不在录了，就是一次停录 —— 整次停，和以前一样等环视接回。 */
    @Test
    public void theLastLiveLaneLeavingIsAWholeStop() {
        assertTrue(RecordingLanes.lastLiveLane(SURROUND,
                live(SURROUND, true, CABIN_FRONT, false, CABIN_REAR, false)));
        assertTrue("只录一路", RecordingLanes.lastLiveLane(SURROUND, live(SURROUND, true)));
    }

    /** 接回中、离开中、不在录的都不算在录：剩下的只有它们，离开的这一路就是最后一路。 */
    @Test
    public void lanesThatAreNotLiveDoNotKeepTheRecordingGoing() {
        assertTrue(RecordingLanes.lastLiveLane(CABIN_FRONT,
                live(SURROUND, false, CABIN_FRONT, true, CABIN_REAR, false)));
    }

    // ------------------------------------------------------------------ 等着接回

    /** 计划里的一路、录像开着、它不在录：等着接回 —— 不管是离开的、开录时没录起来的、还是开录时就被占着的。 */
    @Test
    public void anOutLaneOfAnOngoingCodecRecordingWaitsToJoin() {
        assertTrue(RecordingLanes.waitsToJoin(true, true, RecordingLanes.State.OUT));
    }

    @Test
    public void onlyOutLanesWaitToJoin() {
        assertFalse("在录的", RecordingLanes.waitsToJoin(true, true, RecordingLanes.State.LIVE));
        assertFalse("离开中的等它收拾完", RecordingLanes.waitsToJoin(true, true, RecordingLanes.State.LEAVING));
        assertFalse("接回中的等这一次有结果", RecordingLanes.waitsToJoin(true, true, RecordingLanes.State.JOINING));
        assertFalse("不在这一次录像的计划里的从来不接", RecordingLanes.waitsToJoin(true, true, null));
    }

    /** 录像没开着（开录还没走完、停了）不接；MediaRecorder 模式没有单独接回（离开就整次停）。 */
    @Test
    public void nothingJoinsOutsideACodecRecording() {
        assertFalse(RecordingLanes.waitsToJoin(false, true, RecordingLanes.State.OUT));
        assertFalse(RecordingLanes.waitsToJoin(true, false, RecordingLanes.State.OUT));
    }

    // ------------------------------------------------------------------ 计数：认准是哪一次

    /**
     * 同一次录像里离开 → 接回 → 又离开 → 又接回，录像的代数一直不变：前一次接回排着的任务（建好的录制器、
     * 等画面、录制器的回调）回来时对不上，作废，不打到后一次上。
     */
    @Test
    public void eachLeaveAndJoinRetiresTheTasksBeforeIt() {
        RecordingLanes lanes = new RecordingLanes();
        lanes.live(CABIN_REAR, T0);
        int started = lanes.token(CABIN_REAR);
        assertTrue("开录建的录制器", lanes.current(CABIN_REAR, started));

        int firstLeave = lanes.leave(CABIN_REAR, T0 + 1);
        assertFalse("离开之后，开录那个录制器的回调不算数", lanes.current(CABIN_REAR, started));
        lanes.out(CABIN_REAR, RecordingLanes.Reason.TAKEN, T0 + 2);
        assertTrue("不在录的这一段还是离开那一次", lanes.current(CABIN_REAR, firstLeave));

        int firstJoin = lanes.join(CABIN_REAR, T0 + 3);
        lanes.step(CABIN_REAR, RecordingLanes.Step.OPENING);
        assertTrue("走到下一步还是这一次接回", lanes.current(CABIN_REAR, firstJoin));

        int secondLeave = lanes.leave(CABIN_REAR, T0 + 4);
        lanes.out(CABIN_REAR, RecordingLanes.Reason.TAKEN, T0 + 5);
        int secondJoin = lanes.join(CABIN_REAR, T0 + 6);
        assertFalse("第一次接回建的录制器回来了：作废", lanes.current(CABIN_REAR, firstJoin));
        assertFalse(lanes.current(CABIN_REAR, secondLeave));
        assertTrue(lanes.current(CABIN_REAR, secondJoin));

        lanes.live(CABIN_REAR, T0 + 7);
        assertTrue("接回成了：接回那个录制器接着就是这一路的", lanes.current(CABIN_REAR, secondJoin));
    }

    /** 停录、开新的一次：表清空，上一次的任务都不算数。 */
    @Test
    public void aNewRecordingForgetsTheLastOne() {
        RecordingLanes lanes = new RecordingLanes();
        lanes.live(SURROUND, T0);
        int join = lanes.join(CABIN_REAR, T0);
        lanes.clear();
        assertFalse(lanes.current(CABIN_REAR, join));
        assertNull(lanes.state(SURROUND));
        assertTrue(lanes.keys().isEmpty());
    }

    // ------------------------------------------------------------------ 一次只动一路

    /** 离开中、接回中还没启动录制器的那一路在变：看门狗这时别的路都不动；录制器启动了、等第一笔数据时不挡。 */
    @Test
    public void aChangingLaneHoldsTheChannel() {
        RecordingLanes lanes = new RecordingLanes();
        lanes.live(SURROUND, T0);
        lanes.out(CABIN_REAR, RecordingLanes.Reason.TAKEN, T0);
        assertNull(lanes.changing());

        lanes.join(CABIN_REAR, T0 + 1);
        assertEquals(CABIN_REAR, lanes.changing());
        lanes.step(CABIN_REAR, RecordingLanes.Step.PREPARED);
        assertEquals(CABIN_REAR, lanes.changing());
        lanes.step(CABIN_REAR, RecordingLanes.Step.OPENING);
        assertEquals(CABIN_REAR, lanes.changing());
        lanes.step(CABIN_REAR, RecordingLanes.Step.STARTED);
        assertNull("录制器启动了：相机已经在出画面，别的路可以动了", lanes.changing());

        lanes.leave(CABIN_FRONT, T0 + 2);
        assertEquals(CABIN_FRONT, lanes.changing());
    }

    /** 走到哪一步只对接回中的一路有意义。 */
    @Test
    public void onlyAJoiningLaneHasAStep() {
        RecordingLanes lanes = new RecordingLanes();
        lanes.join(CABIN_REAR, T0);
        assertEquals(RecordingLanes.Step.PREPARING, lanes.step(CABIN_REAR));
        lanes.live(CABIN_REAR, T0 + 1);
        assertNull(lanes.step(CABIN_REAR));
        lanes.step(CABIN_REAR, RecordingLanes.Step.OPENING);
        assertNull("在录的一路不会被改成接回中", lanes.step(CABIN_REAR));
        assertEquals(RecordingLanes.State.LIVE, lanes.state(CABIN_REAR));
    }

    // ------------------------------------------------------------------ 接回：等会话、等画面

    @Test
    public void theRecorderStartsOnlyWhenTheSessionCarriesItAndFramesCome() {
        assertEquals(RecordingLanes.Opening.START, RecordingLanes.opening(false, true, true, 0));
        assertEquals("会话里有它，第一帧还没来", RecordingLanes.Opening.WAIT,
                RecordingLanes.opening(false, true, false, 500));
        assertEquals("出画面了，但会话里没有它（预览的帧）：不启动", RecordingLanes.Opening.WAIT,
                RecordingLanes.opening(false, false, true, 500));
    }

    /** 相机在开、在配会话：等多久都不算没成（在途最多多久由相机层封顶）。 */
    @Test
    public void aBusyCameraIsWaitedFor() {
        assertEquals(RecordingLanes.Opening.WAIT, RecordingLanes.opening(true, false, false, 60_000));
        assertEquals(RecordingLanes.Opening.WAIT, RecordingLanes.opening(true, true, true, 60_000));
    }

    /**
     * 相机不在途了还等不到（会话配不上时录像输出被丢掉、或者有它却没画面）：等了看门狗判「卡住」那么久就算没成，
     * 下一次什么时候试由看门狗定。
     */
    @Test
    public void aQuietCameraThatNeverCarriesTheRecordingFailsTheJoin() {
        assertEquals(RecordingLanes.Opening.WAIT,
                RecordingLanes.opening(false, false, true, CameraLiveness.STUCK_MS - 1));
        assertEquals(RecordingLanes.Opening.FAILED,
                RecordingLanes.opening(false, false, true, CameraLiveness.STUCK_MS));
        assertEquals("会话里有它却没画面", RecordingLanes.Opening.FAILED,
                RecordingLanes.opening(false, true, false, CameraLiveness.STUCK_MS));
    }

    // ------------------------------------------------------------------ 对齐分段

    /** 别的路换了新的共用名字：单独接回、名字还错开着的这一路马上跟着切。 */
    @Test
    public void aJoinedLaneFollowsTheNextSharedName() {
        assertTrue(RecordingLanes.alignsNow(true, true, false));
    }

    @Test
    public void onlyAnOffCycleRecordingLaneSwitches() {
        assertFalse("名字本来就是共用的：不切", RecordingLanes.alignsNow(false, true, false));
        assertFalse("正在切段、快速恢复：这一次不切，等下一次换名字", RecordingLanes.alignsNow(true, false, false));
        assertFalse("叫停了", RecordingLanes.alignsNow(true, true, true));
    }

    // ------------------------------------------------------------------ 主界面说哪一句

    private static RecordingLanes.Out out(String key, RecordingLanes.State state, RecordingLanes.Reason reason) {
        return new RecordingLanes.Out(key, state, reason, T0);
    }

    @Test
    public void nothingToSayWhileEveryLaneRecords() {
        assertEquals(RecordingLanes.Hint.Kind.NONE,
                RecordingLanes.hint(Collections.<RecordingLanes.Out>emptyList()).kind);
    }

    /** 一路被占用（开录那一刻就被占着的也算）：说这一路被占用、释放后自动恢复。 */
    @Test
    public void oneTakenLaneIsNamed() {
        RecordingLanes.Hint hint = RecordingLanes.hint(Collections.singletonList(
                out(CABIN_REAR, RecordingLanes.State.OUT, RecordingLanes.Reason.TAKEN)));
        assertEquals(RecordingLanes.Hint.Kind.LANE_TAKEN, hint.kind);
        assertEquals(CABIN_REAR, hint.key);
        assertEquals(RecordingLanes.Hint.Kind.LANE_TAKEN, RecordingLanes.hint(Collections.singletonList(
                out(SURROUND, RecordingLanes.State.OUT, RecordingLanes.Reason.START_SKIPPED))).kind);
    }

    /** 1 和 2 冲突时两路一起被拿走：说「部分摄像头被占用」。 */
    @Test
    public void twoTakenLanesAreSaidTogether() {
        RecordingLanes.Hint hint = RecordingLanes.hint(Arrays.asList(
                out(SURROUND, RecordingLanes.State.OUT, RecordingLanes.Reason.TAKEN),
                out(CABIN_REAR, RecordingLanes.State.OUT, RecordingLanes.Reason.TAKEN)));
        assertEquals(RecordingLanes.Hint.Kind.LANES_TAKEN, hint.kind);
        assertNull(hint.key);
    }

    /** 不是被占用的（出错、接回没成、试够了停手）：说那一路无法打开 —— 别人放开它也不会因此回来。 */
    @Test
    public void aFailedLaneIsSaidToBeUnavailable() {
        RecordingLanes.Hint hint = RecordingLanes.hint(Arrays.asList(
                out(SURROUND, RecordingLanes.State.OUT, RecordingLanes.Reason.TAKEN),
                out(CABIN_FRONT, RecordingLanes.State.OUT, RecordingLanes.Reason.FAILED)));
        assertEquals(RecordingLanes.Hint.Kind.LANE_UNAVAILABLE, hint.kind);
        assertEquals(CABIN_FRONT, hint.key);
    }

    /** 从在录离开、还没判出来的不说：是不是被别的程序拿走还没判出来，晚几秒说对，不先说错再改口。 */
    @Test
    public void aLaneLeavingTheRecordingIsNotSaidYet() {
        RecordingLanes lanes = new RecordingLanes();
        lanes.live(CABIN_REAR, T0);
        lanes.leave(CABIN_REAR, T0 + 1);
        RecordingLanes.Out leaving = lanes.outOf(CABIN_REAR);
        assertEquals(RecordingLanes.State.LEAVING, leaving.state);
        assertNull(leaving.reason);
        assertEquals(RecordingLanes.Hint.Kind.NONE,
                RecordingLanes.hint(Collections.singletonList(leaving)).kind);
    }

    /**
     * 接回中的、接回没成又离开的，照它出来时的原因说：被占用的那一路放开了、正在接回，「释放后自动恢复」正在兑现；
     * 被占着时每 30 秒试的那一下没成，这一句也不跟着一闪一闪。
     */
    @Test
    public void aJoiningLaneKeepsTheReasonItWasOut() {
        RecordingLanes lanes = new RecordingLanes();
        lanes.out(CABIN_REAR, RecordingLanes.Reason.TAKEN, T0);
        lanes.join(CABIN_REAR, T0 + 1);
        RecordingLanes.Out joining = lanes.outOf(CABIN_REAR);
        assertEquals(RecordingLanes.State.JOINING, joining.state);
        assertTrue(joining.taken());
        assertEquals(T0 + 1, joining.sinceMs);

        List<RecordingLanes.Out> outs = new ArrayList<>();
        outs.add(joining);
        assertEquals(RecordingLanes.Hint.Kind.LANE_TAKEN, RecordingLanes.hint(outs).kind);

        lanes.leave(CABIN_REAR, T0 + 2);
        RecordingLanes.Out failedJoin = lanes.outOf(CABIN_REAR);
        assertEquals(RecordingLanes.State.LEAVING, failedJoin.state);
        assertTrue("接回没成：判出来之前还照被占用说", failedJoin.taken());
        assertEquals(RecordingLanes.Hint.Kind.LANE_TAKEN,
                RecordingLanes.hint(Collections.singletonList(failedJoin)).kind);

        lanes.out(CABIN_REAR, RecordingLanes.Reason.FAILED, T0 + 3);
        assertFalse("判完换成新的原因", lanes.outOf(CABIN_REAR).taken());
        lanes.join(CABIN_REAR, T0 + 4);
        lanes.live(CABIN_REAR, T0 + 5);
        assertNull("在录的不在「不在录」的单子上", lanes.outOf(CABIN_REAR));
    }
}
