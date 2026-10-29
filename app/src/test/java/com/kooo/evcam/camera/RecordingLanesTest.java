package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.camera.ChannelRules.Condition;
import com.kooo.evcam.camera.RecordingLanes.Facts;
import com.kooo.evcam.camera.RecordingLanes.Hint;
import com.kooo.evcam.camera.RecordingLanes.Recorder;
import com.kooo.evcam.camera.RecordingLanes.State;
import com.kooo.evcam.camera.RecordingLanes.Summary;
import com.kooo.evcam.camera.RecordingLanes.View;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 录像每一路各自进出（channel-logic §三，项目所有者 2026-10-10 确认；大纲 §2 阶段 5b）：每一路在录像表上的状态
 * （等着 / 在加入 / 在录）、什么时候加入、写不进算不算相机的事、整体的样子（准备中 / 在录 / 等待摄像头）、
 * 开录落定、状态条那一句。
 *
 * <p>判错的代价：加入判早了，开的录一帧都收不到，15 秒后被当成写不进；判晚了，相机出了画面录像还空着。
 * 写不进算错了是盘的事，好好的另外几路跟着整次停；算错了是相机的事，盘坏了也只停一路、一直在重来。
 * 样子说错了，一路都录不了时界面还说在录，或者等摄像头时说成「正在准备」—— 车主以为录像停了。</p>
 */
public class RecordingLanesTest {

    private static final String SURROUND = CameraSlots.KEY_SURROUND;
    private static final String CABIN_REAR = CameraSlots.KEY_CABIN_REAR;
    private static final String CABIN_FRONT = CameraSlots.KEY_CABIN_FRONT;
    private static final List<String> PLAN = Arrays.asList(SURROUND, CABIN_REAR, CABIN_FRONT);
    private static final long T0 = 1_000_000L;

    private static Facts lane(String key, State state, Condition condition, Recorder recorder) {
        return new Facts(key, state, condition, false, false, false, recorder);
    }

    /** 等着、录制器能用，相机关着、调度现在用不了相机（主界面不在前、前台服务是后台起的）。 */
    private static Facts cannotOpen(String key) {
        return new Facts(key, State.WAITING, Condition.BRINGING_UP, true, false, false, Recorder.READY);
    }

    /** 等着、录制器停放着，熄屏放开了（座舱熄屏时谁都不要，项目所有者 2026-10-12 D2）；调度说它是这个样子。 */
    private static Facts dark(String key, Condition condition) {
        return new Facts(key, State.WAITING, condition, false, false, true, Recorder.READY);
    }

    /**
     * 等着、录制器能用，它这一台那一轮满 60 秒还有开或关没返回（项目所有者 2026-10-28、10-29）：那一下返回之前调度不开它 ——
     * 调度照旧说它在起来、被拿着或者在救，它也算「现在开不了」（cannotOpen）。
     */
    private static Facts held(String key, Condition condition) {
        return new Facts(key, State.WAITING, condition, true, true, false, Recorder.READY);
    }

    /** 等着、录制器能用、相机是这个样子。 */
    private static Facts waiting(String key, Condition condition) {
        return lane(key, State.WAITING, condition, Recorder.READY);
    }

    private static Facts live(String key) {
        return lane(key, State.LIVE, Condition.STREAMING, Recorder.BUSY);
    }

    private static RecordingLanes begun() {
        RecordingLanes lanes = new RecordingLanes();
        lanes.begin(PLAN, T0);
        return lanes;
    }

    // ------------------------------------------------------------------ 加入

    /** 四条同时成立才加入：在等着、录制器停放着或备好了、相机在出画面、会话带着这一路的录像输出。 */
    @Test
    public void aWaitingLaneJoinsOnceItsCameraStreamsWithItsRecordOutput() {
        assertTrue(RecordingLanes.joins(State.WAITING, Recorder.READY, true, true));
    }

    @Test
    public void anyMissingConditionKeepsTheLaneWaiting() {
        assertFalse("相机没在出画面", RecordingLanes.joins(State.WAITING, Recorder.READY, false, true));
        assertFalse("出画面了，但会话里没有它的录像输出（预览的帧）：开的录一帧都收不到",
                RecordingLanes.joins(State.WAITING, Recorder.READY, true, false));
        assertFalse("录制器在建", RecordingLanes.joins(State.WAITING, Recorder.BUILDING, true, true));
        assertFalse("还没有录制器（画面尺寸没算出来）", RecordingLanes.joins(State.WAITING, Recorder.NONE, true, true));
        assertFalse("录制器在忙（在停、在停放、在重新备好）", RecordingLanes.joins(State.WAITING, Recorder.BUSY, true, true));
        assertFalse("录制器建不起来", RecordingLanes.joins(State.WAITING, Recorder.FAILED, true, true));
    }

    @Test
    public void aLaneAlreadyJoiningOrRecordingDoesNotJoinAgain() {
        assertFalse(RecordingLanes.joins(State.STARTING, Recorder.READY, true, true));
        assertFalse(RecordingLanes.joins(State.LIVE, Recorder.READY, true, true));
        assertFalse("不在这一次录像的计划里的从来不加入", RecordingLanes.joins(null, Recorder.READY, true, true));
    }

    // ------------------------------------------------------------------ 写不进：是不是相机的事

    /** 相机在出画面、会话里有它的录像输出、最近出过画面：写不进是盘或编码器的事（整次停，存储一类照旧自动恢复）。 */
    @Test
    public void aStallWithAHealthyCameraIsTheDisksBusiness() {
        assertFalse(RecordingLanes.stallIsCamera(true, true, true));
    }

    /** 任何一项不成立就是相机的事：只这一路退出，别的路照录。 */
    @Test
    public void aStallWithAnyCameraTroubleIsTheCamerasBusiness() {
        assertTrue("相机没在出画面", RecordingLanes.stallIsCamera(false, true, true));
        assertTrue("会话里已经没有它的录像输出", RecordingLanes.stallIsCamera(true, false, true));
        assertTrue("最近没出过画面", RecordingLanes.stallIsCamera(true, true, false));
        assertTrue(RecordingLanes.stallIsCamera(false, false, false));
    }

    // ------------------------------------------------------------------ 表：状态和计数

    /** 开一个录像意图：计划里每一路都从等着起；不在计划里的问不到。 */
    @Test
    public void aNewIntentStartsEveryPlannedLaneWaiting() {
        RecordingLanes lanes = begun();
        assertEquals(PLAN, lanes.keys());
        for (String key : PLAN) {
            assertEquals(State.WAITING, lanes.state(key));
            assertEquals(T0, lanes.sinceMs(key));
        }
        assertNull(lanes.state("right"));
        assertEquals(0, lanes.token("right"));
        assertEquals("不在计划里的什么都不做", 0, lanes.starting("right", T0));
        assertNull(lanes.state("right"));
    }

    /**
     * 加入、退出各 +1 计数，写进第一笔不变（加入的这一次接着就是它）：排着的任务带着加入那一刻的计数回来，
     * 这一路之后退出过、又加入过就作废 —— 同一个录像意图里录像的代数一直不变，只靠它分清是哪一次。
     */
    @Test
    public void joiningAndLeavingBumpTheTokenAndStaleTasksAreVoid() {
        RecordingLanes lanes = begun();
        int joined = lanes.starting(CABIN_REAR, T0 + 1);
        assertEquals(State.STARTING, lanes.state(CABIN_REAR));
        assertTrue(lanes.current(CABIN_REAR, joined));
        lanes.live(CABIN_REAR, T0 + 2);
        assertEquals(State.LIVE, lanes.state(CABIN_REAR));
        assertEquals(T0 + 2, lanes.sinceMs(CABIN_REAR));
        assertTrue("写进第一笔不换计数", lanes.current(CABIN_REAR, joined));

        int left = lanes.waiting(CABIN_REAR, T0 + 3);
        assertEquals(State.WAITING, lanes.state(CABIN_REAR));
        assertFalse("退出之后，加入那一次的写不进、第一笔数据都不算", lanes.current(CABIN_REAR, joined));
        int again = lanes.starting(CABIN_REAR, T0 + 4);
        assertNotEquals(left, again);
        assertTrue(lanes.current(CABIN_REAR, again));
        assertFalse(lanes.current(CABIN_REAR, joined));
        assertEquals("别的路的计数不受影响", 0, lanes.token(SURROUND));
        assertTrue(lanes.current(SURROUND, 0));
    }

    /** 停录：表清空，排着的任务回来都作废。 */
    @Test
    public void clearingVoidsEveryTask() {
        RecordingLanes lanes = begun();
        int token = lanes.starting(SURROUND, T0);
        lanes.clear();
        assertTrue(lanes.keys().isEmpty());
        assertNull(lanes.state(SURROUND));
        assertFalse(lanes.current(SURROUND, token));
    }

    // ------------------------------------------------------------------ 整体的样子

    /** 开录时各路在起来、还没有一路录起来过：准备中。 */
    @Test
    public void startingWithLanesComingUpIsPreparing() {
        RecordingLanes lanes = begun();
        Summary summary = lanes.summarize(Arrays.asList(
                lane(SURROUND, State.WAITING, Condition.BRINGING_UP, Recorder.BUILDING),
                lane(CABIN_REAR, State.WAITING, Condition.BRINGING_UP, Recorder.NONE),
                waiting(CABIN_FRONT, Condition.BRINGING_UP)));
        assertEquals(View.PREPARING, summary.view);
        assertEquals(PLAN, summary.planned);
        assertTrue(summary.liveKeys.isEmpty());
        assertFalse(summary.settled);
    }

    /** 出了画面、录制器还在备（会话还没带上它）、或者在加入：都算在起来。 */
    @Test
    public void aStreamingLaneWaitingForItsRecorderOrAJoiningLaneIsComingUp() {
        assertTrue(RecordingLanes.bringingUp(lane(SURROUND, State.WAITING, Condition.STREAMING, Recorder.BUILDING)));
        assertTrue(RecordingLanes.bringingUp(waiting(SURROUND, Condition.STREAMING)));
        assertTrue(RecordingLanes.bringingUp(lane(SURROUND, State.STARTING, Condition.STREAMING, Recorder.BUSY)));
        assertFalse("被拿着的一时半会儿回不来", RecordingLanes.bringingUp(waiting(SURROUND, Condition.TAKEN)));
        assertFalse("在救", RecordingLanes.bringingUp(waiting(SURROUND, Condition.RESCUING)));
        assertFalse("停手", RecordingLanes.bringingUp(waiting(SURROUND, Condition.GAVE_UP)));
        assertFalse("录制器建不起来",
                RecordingLanes.bringingUp(lane(SURROUND, State.WAITING, Condition.STREAMING, Recorder.FAILED)));
        assertFalse("关着、现在用不了相机：主界面回到前台之前不会开", RecordingLanes.bringingUp(cannotOpen(SURROUND)));
    }

    /**
     * 悬浮按钮在后台开的录（前台服务是后台起的）：各路关着、调度不开 —— 不是「准备中」，是等待摄像头，开录就落定；
     * 主界面回到前台、各路开起来之后也不再回到准备中。以前算它们在起来，录制键一直「正在准备」，通知说「正在录制」。
     */
    @Test
    public void lanesThatCannotOpenAreWaitingNotPreparing() {
        RecordingLanes lanes = begun();
        Summary first = lanes.summarize(Arrays.asList(
                cannotOpen(SURROUND), cannotOpen(CABIN_REAR), cannotOpen(CABIN_FRONT)));
        assertEquals(View.WAITING, first.view);
        assertTrue(first.settled);
        assertEquals("会自己起来的不算停手", Hint.Kind.NONE, first.hint.kind);
        assertEquals(View.WAITING, lanes.summarize(Arrays.asList(
                waiting(SURROUND, Condition.BRINGING_UP), cannotOpen(CABIN_REAR), cannotOpen(CABIN_FRONT))).view);
    }

    /**
     * 熄屏放开的座舱不算在起来（设计 v3.1 C4）：调度照旧说它「在起来」（关着、没人要），可亮屏之前它不会开 —— 熄屏时开的录
     * 只等环视：环视在起来是准备中，环视录起来了就落定；算它在起来的话，开录永远落定不了。
     */
    @Test
    public void lanesReleasedByTheDarkAreNotComingUp() {
        assertFalse(RecordingLanes.bringingUp(dark(CABIN_REAR, Condition.BRINGING_UP)));
        assertFalse("被车机拿着也一样", RecordingLanes.bringingUp(dark(CABIN_REAR, Condition.TAKEN)));
        assertTrue("在加入的照算（它马上会退出）", RecordingLanes.bringingUp(new Facts(CABIN_REAR, State.STARTING,
                Condition.BRINGING_UP, false, false, true, Recorder.BUSY)));

        RecordingLanes lanes = begun();
        Summary first = lanes.summarize(Arrays.asList(waiting(SURROUND, Condition.BRINGING_UP),
                dark(CABIN_REAR, Condition.BRINGING_UP), dark(CABIN_FRONT, Condition.BRINGING_UP)));
        assertEquals("环视在起来：准备中", View.PREPARING, first.view);
        assertFalse(first.settled);
        Summary settled = lanes.summarize(Arrays.asList(live(SURROUND),
                dark(CABIN_REAR, Condition.BRINGING_UP), dark(CABIN_FRONT, Condition.BRINGING_UP)));
        assertEquals(View.RECORDING, settled.view);
        assertTrue("环视录起来就落定", settled.settled);
        assertEquals(Collections.singletonList(SURROUND), settled.liveKeys);
    }

    /**
     * 环视和座舱一起被熄屏放开（项目所有者 2026-10-27：哨兵模式关着、没留着的环视）：三路都不算在起来、不算被占用 ——
     * 录像是「等待摄像头」，落定了，状态条什么都不说；亮屏它们自己回来。
     */
    @Test
    public void aSurroundReleasedByTheDarkWithTheCabinsIsWaitingAndSettled() {
        RecordingLanes lanes = begun();
        Summary summary = lanes.summarize(Arrays.asList(dark(SURROUND, Condition.BRINGING_UP),
                dark(CABIN_REAR, Condition.TAKEN), dark(CABIN_FRONT, Condition.BRINGING_UP)));
        assertEquals(View.WAITING, summary.view);
        assertTrue("三路都熄屏放开：落定", summary.settled);
        assertTrue(summary.liveKeys.isEmpty());
        assertEquals(RecordingLanes.Hint.Kind.NONE, summary.hint.kind);
    }

    /** 亮着、谁都没登记的座舱照旧算在起来（只有熄屏放开才不算）：刚开录、调度还没跑那一轮也落定不了。 */
    @Test
    public void aLitLaneNobodyHasClaimedYetIsStillComingUp() {
        RecordingLanes lanes = begun();
        assertFalse(lanes.summarize(Arrays.asList(live(SURROUND), waiting(CABIN_REAR, Condition.BRINGING_UP),
                waiting(CABIN_FRONT, Condition.BRINGING_UP))).settled);
    }

    /** 有一路在录就是在录，别的路在不在录都一样。 */
    @Test
    public void oneLiveLaneIsRecording() {
        RecordingLanes lanes = begun();
        Summary summary = lanes.summarize(Arrays.asList(
                live(SURROUND), waiting(CABIN_REAR, Condition.TAKEN), waiting(CABIN_FRONT, Condition.BRINGING_UP)));
        assertEquals(View.RECORDING, summary.view);
        assertEquals(Collections.singletonList(SURROUND), summary.liveKeys);
    }

    /**
     * 开录时一路都起不来（都被拿着、在救、停手、录制器建不起来）：等待摄像头 —— 录像不算停（项目所有者 2026-10-10）；
     * 之后有一路在起来也不再回到准备中（同一个录像意图里）。
     */
    @Test
    public void startingWithNothingComingUpIsWaitingAndNeverPreparingAgain() {
        RecordingLanes lanes = begun();
        Summary first = lanes.summarize(Arrays.asList(
                waiting(SURROUND, Condition.TAKEN), waiting(CABIN_REAR, Condition.RESCUING),
                lane(CABIN_FRONT, State.WAITING, Condition.STREAMING, Recorder.FAILED)));
        assertEquals(View.WAITING, first.view);
        Summary later = lanes.summarize(Arrays.asList(
                waiting(SURROUND, Condition.BRINGING_UP), waiting(CABIN_REAR, Condition.RESCUING),
                lane(CABIN_FRONT, State.WAITING, Condition.STREAMING, Recorder.FAILED)));
        assertEquals("环视放开了、在起来：不回到准备中", View.WAITING, later.view);
    }

    /** 录起来过、一路都不在录了：等待摄像头，哪怕有一路正在回来（在起来），也不说准备中。 */
    @Test
    public void afterRecordingNoLiveLaneIsWaitingEvenWhileOneComesBack() {
        RecordingLanes lanes = begun();
        assertEquals(View.PREPARING, lanes.summarize(Collections.singletonList(
                waiting(SURROUND, Condition.BRINGING_UP))).view);
        assertEquals(View.RECORDING, lanes.summarize(Collections.singletonList(live(SURROUND))).view);
        assertEquals(View.WAITING, lanes.summarize(Collections.singletonList(
                waiting(SURROUND, Condition.TAKEN))).view);
        assertEquals(View.WAITING, lanes.summarize(Collections.singletonList(
                waiting(SURROUND, Condition.STREAMING))).view);
        assertEquals(View.RECORDING, lanes.summarize(Collections.singletonList(live(SURROUND))).view);
    }

    /** 新的录像意图从头算：又能是准备中。 */
    @Test
    public void aNewIntentCanBePreparingAgain() {
        RecordingLanes lanes = begun();
        lanes.summarize(Collections.singletonList(live(SURROUND)));
        lanes.clear();
        lanes.begin(PLAN, T0 + 1_000);
        assertEquals(View.PREPARING, lanes.summarize(Collections.singletonList(
                waiting(SURROUND, Condition.BRINGING_UP))).view);
    }

    // ------------------------------------------------------------------ 开录落定

    /** 每一路都录起来了、或者暂时起不来（被拿着、在救、停手、录制器建不起来）：落定。还有一路在起来就没落定。 */
    @Test
    public void theStartSettlesOnceNoLaneIsStillComingUp() {
        RecordingLanes lanes = begun();
        assertFalse(lanes.summarize(Arrays.asList(
                live(SURROUND), waiting(CABIN_REAR, Condition.TAKEN), waiting(CABIN_FRONT, Condition.BRINGING_UP)))
                .settled);
        Summary settled = lanes.summarize(Arrays.asList(
                live(SURROUND), waiting(CABIN_REAR, Condition.TAKEN), live(CABIN_FRONT)));
        assertTrue(settled.settled);
        assertEquals(Arrays.asList(SURROUND, CABIN_FRONT), settled.liveKeys);
    }

    /** 落定只从假变真一次：之后有一路又在起来（被拿走的那一路回来了）也还是落定的。 */
    @Test
    public void theStartSettlesOnlyOnce() {
        RecordingLanes lanes = begun();
        assertTrue(lanes.summarize(Arrays.asList(
                live(SURROUND), waiting(CABIN_REAR, Condition.TAKEN), live(CABIN_FRONT))).settled);
        assertTrue(lanes.summarize(Arrays.asList(
                live(SURROUND), waiting(CABIN_REAR, Condition.STREAMING), live(CABIN_FRONT))).settled);
    }

    /** 开录时一路都起不来也是落定（0 路在录）。 */
    @Test
    public void startingWithNothingComingUpSettlesAtOnce() {
        RecordingLanes lanes = begun();
        Summary summary = lanes.summarize(Arrays.asList(
                waiting(SURROUND, Condition.TAKEN), waiting(CABIN_REAR, Condition.TAKEN),
                waiting(CABIN_FRONT, Condition.GAVE_UP)));
        assertTrue(summary.settled);
        assertTrue(summary.liveKeys.isEmpty());
    }

    // ------------------------------------------------------------------ 状态条那一句

    @Test
    public void nothingToSayWhileEveryLaneRecordsOrComesUp() {
        assertEquals(Hint.Kind.NONE, RecordingLanes.hint(Arrays.asList(live(SURROUND), live(CABIN_REAR))).kind);
        assertEquals("在救、在起来的不说", Hint.Kind.NONE, RecordingLanes.hint(Arrays.asList(
                live(SURROUND), waiting(CABIN_REAR, Condition.RESCUING),
                waiting(CABIN_FRONT, Condition.BRINGING_UP))).kind);
        assertEquals(Hint.Kind.NONE, RecordingLanes.hint(Collections.<Facts>emptyList()).kind);
    }

    /** 在录时一路被占用：说这一路被占用、释放后自动恢复。 */
    @Test
    public void oneTakenLaneIsNamed() {
        Hint hint = RecordingLanes.hint(Arrays.asList(live(SURROUND), waiting(CABIN_REAR, Condition.TAKEN)));
        assertEquals(Hint.Kind.LANE_TAKEN, hint.kind);
        assertEquals(CABIN_REAR, hint.key);
    }

    /**
     * 熄屏放开的后座舱被车机拿走（熄屏约 98 秒后）：它没在录是因为熄屏，亮屏自己回来 —— 状态条不说它被占用
     * （夜里车机自己醒着、屏幕黑着的那几分钟，主界面可能在前）。
     */
    @Test
    public void aLaneReleasedByTheDarkIsNotSaidToBeTaken() {
        assertEquals(Hint.Kind.NONE, RecordingLanes.hint(Arrays.asList(
                live(SURROUND), dark(CABIN_REAR, Condition.TAKEN), dark(CABIN_FRONT, Condition.BRINGING_UP))).kind);
        Hint hint = RecordingLanes.hint(Arrays.asList(
                live(SURROUND), dark(CABIN_REAR, Condition.TAKEN), waiting(CABIN_FRONT, Condition.TAKEN)));
        assertEquals("只说亮着时被拿着的那一路", Hint.Kind.LANE_TAKEN, hint.kind);
        assertEquals(CABIN_FRONT, hint.key);
    }

    /** 两路或更多被占用（1 和 2 冲突时一起被拿走）：说部分摄像头被占用。 */
    @Test
    public void twoTakenLanesAreSaidTogether() {
        Hint hint = RecordingLanes.hint(Arrays.asList(
                waiting(SURROUND, Condition.TAKEN), waiting(CABIN_REAR, Condition.TAKEN), live(CABIN_FRONT)));
        assertEquals(Hint.Kind.LANES_TAKEN, hint.kind);
        assertNull(hint.key);
    }

    /** 停手了、或者录制器建不起来：说那一路无法录制 —— 它不会因为别人放开就回来，这一句比「被占用」要紧。 */
    @Test
    public void aLaneThatGaveUpOrHasNoRecorderIsSaidToBeUnavailable() {
        Hint gaveUp = RecordingLanes.hint(Arrays.asList(
                live(SURROUND), waiting(CABIN_REAR, Condition.TAKEN), waiting(CABIN_FRONT, Condition.GAVE_UP)));
        assertEquals(Hint.Kind.LANE_UNAVAILABLE, gaveUp.kind);
        assertEquals(CABIN_FRONT, gaveUp.key);
        Hint noRecorder = RecordingLanes.hint(Arrays.asList(
                live(SURROUND), lane(CABIN_REAR, State.WAITING, Condition.STREAMING, Recorder.FAILED)));
        assertEquals(Hint.Kind.LANE_UNAVAILABLE, noRecorder.kind);
        assertEquals(CABIN_REAR, noRecorder.key);
    }

    /**
     * 一路这一台那一轮满 60 秒还有开或关没返回（项目所有者 2026-10-28、10-29）：别的路在录时，状态条照现成的那一句说它无法录制 ——
     * 被拿着的也是这一句（那一下返回之前谁都不开它，放开了也不回来）。它不算在起来：一路都没在录时是等待摄像头，也不算各路都停手
     * （那一下一返回它就照常开）。在录的那一路不说（设备在我们手上，调度不会这样报它；报了也不说）。
     */
    @Test
    public void aLaneHeldPastSixtySecondsIsSaidToBeUnavailable() {
        Hint hint = RecordingLanes.hint(Arrays.asList(live(SURROUND), held(CABIN_REAR, Condition.BRINGING_UP)));
        assertEquals(Hint.Kind.LANE_UNAVAILABLE, hint.kind);
        assertEquals(CABIN_REAR, hint.key);
        Hint taken = RecordingLanes.hint(Arrays.asList(live(SURROUND), held(CABIN_REAR, Condition.TAKEN),
                waiting(CABIN_FRONT, Condition.TAKEN)));
        assertEquals("占着没返回的那一路先说", Hint.Kind.LANE_UNAVAILABLE, taken.kind);
        assertEquals(CABIN_REAR, taken.key);
        assertFalse("不算在起来", RecordingLanes.bringingUp(held(CABIN_REAR, Condition.BRINGING_UP)));
        Hint streaming = RecordingLanes.hint(Arrays.asList(live(SURROUND),
                new Facts(CABIN_REAR, State.LIVE, Condition.STREAMING, false, true, false, Recorder.BUSY)));
        assertEquals("在录的那一路不说", Hint.Kind.NONE, streaming.kind);
        Hint notYet = RecordingLanes.hint(Arrays.asList(live(SURROUND), waiting(CABIN_REAR, Condition.BRINGING_UP)));
        assertEquals("60 秒以内（调度还没报）：照旧在起来，不说", Hint.Kind.NONE, notYet.kind);

        RecordingLanes lanes = begun();
        Summary summary = lanes.summarize(Arrays.asList(held(SURROUND, Condition.BRINGING_UP),
                held(CABIN_REAR, Condition.RESCUING)));
        assertEquals("一路都没在录：等待摄像头", View.WAITING, summary.view);
        assertTrue(summary.settled);
        assertEquals("不算各路都停手", Hint.Kind.NONE, summary.hint.kind);
    }

    /**
     * 一路都没在录、每一路都停手了或者建不起来：各路都停手（只进黑匣子，界面照「等待摄像头」说 —— §7.4 不加新文字）。
     * 还有一路在救、被拿着、在起来，它们还会自己回来，不算。
     */
    @Test
    public void everyLaneGivingUpWithNothingLiveIsAllGaveUp() {
        List<Facts> all = Arrays.asList(waiting(SURROUND, Condition.GAVE_UP),
                lane(CABIN_REAR, State.WAITING, Condition.STREAMING, Recorder.FAILED),
                waiting(CABIN_FRONT, Condition.GAVE_UP));
        assertTrue(RecordingLanes.allGaveUp(all));
        assertEquals(Hint.Kind.ALL_GAVE_UP, RecordingLanes.hint(all).kind);
        assertNull(RecordingLanes.hint(all).key);

        List<Facts> oneStillTrying = new ArrayList<>(all);
        oneStillTrying.set(2, waiting(CABIN_FRONT, Condition.RESCUING));
        assertFalse(RecordingLanes.allGaveUp(oneStillTrying));
        assertEquals("一路都没在录、也不是都停手：不说（界面照等待摄像头说）", Hint.Kind.NONE,
                RecordingLanes.hint(oneStillTrying).kind);
        oneStillTrying.set(2, waiting(CABIN_FRONT, Condition.TAKEN));
        assertFalse(RecordingLanes.allGaveUp(oneStillTrying));
        assertFalse("没有路不算", RecordingLanes.allGaveUp(Collections.<Facts>emptyList()));
    }

    /** 各路都停手是一个等待的样子：录像不算停。 */
    @Test
    public void allGaveUpIsStillWaitingNotStopped() {
        RecordingLanes lanes = begun();
        Summary summary = lanes.summarize(Arrays.asList(waiting(SURROUND, Condition.GAVE_UP),
                waiting(CABIN_REAR, Condition.GAVE_UP), waiting(CABIN_FRONT, Condition.GAVE_UP)));
        assertEquals(View.WAITING, summary.view);
        assertEquals(Hint.Kind.ALL_GAVE_UP, summary.hint.kind);
    }

    // ------------------------------------------------------------------ 摘要变没变（变了才告诉协调器）

    @Test
    public void summariesCompareByWhatTheySay() {
        List<Facts> facts = Arrays.asList(live(SURROUND), waiting(CABIN_REAR, Condition.TAKEN),
                waiting(CABIN_FRONT, Condition.BRINGING_UP));
        assertTrue(begun().summarize(facts).sameAs(begun().summarize(facts)));
        RecordingLanes lanes = begun();
        Summary taken = lanes.summarize(facts);
        Summary rescuing = lanes.summarize(Arrays.asList(live(SURROUND), waiting(CABIN_REAR, Condition.RESCUING),
                waiting(CABIN_FRONT, Condition.BRINGING_UP)));
        assertFalse("状态条那一句变了", taken.sameAs(rescuing));
        assertFalse(Summary.NONE.sameAs(taken));
        assertTrue(Summary.NONE.sameAs(Summary.NONE));
        assertFalse(Summary.NONE.sameAs(null));
        assertTrue("没在录：计划是空的", Summary.NONE.planned.isEmpty());
    }

    // ------------------------------------------------------------------ 文件名在备好时就定了的录制器（MediaRecorder）

    /** 相机在出画面、或者正在起来（排着要开、在开）：备、备好了的留着 —— 它多半马上带着录像输出出画面，文件名就是开录那一刻。 */
    @Test
    public void aNamedRecorderIsPreparedWhileTheCameraStreamsOrComesUp() {
        assertTrue(RecordingLanes.keepsNamedRecorder(Condition.STREAMING, false, false));
        assertTrue(RecordingLanes.keepsNamedRecorder(Condition.BRINGING_UP, false, false));
    }

    /**
     * 被拿着、在救、停手、关着而现在用不了相机、熄屏放开了：不备，备好还没开录的放掉 —— 车机拿着后座舱 9 分钟的话，
     * 按备好那一刻起的名早了 9 分钟，回放里摆错地方（channel-logic §三：文件名用它真实开始的时刻）；熄屏放开的座舱要到亮屏
     * 才开，早的可能是一整夜。
     */
    @Test
    public void aNamedRecorderIsNotKeptForACameraThatWillNotStreamSoon() {
        assertFalse("被拿着", RecordingLanes.keepsNamedRecorder(Condition.TAKEN, false, false));
        assertFalse("在救", RecordingLanes.keepsNamedRecorder(Condition.RESCUING, false, false));
        assertFalse("停手", RecordingLanes.keepsNamedRecorder(Condition.GAVE_UP, false, false));
        assertFalse("关着、现在用不了相机", RecordingLanes.keepsNamedRecorder(Condition.BRINGING_UP, true, false));
        assertFalse("熄屏放开了", RecordingLanes.keepsNamedRecorder(Condition.BRINGING_UP, false, true));
        assertFalse("熄屏那一下还在出画面也一样：调度这一轮就关它", RecordingLanes.keepsNamedRecorder(
                Condition.STREAMING, false, true));
    }

    // ------------------------------------------------------------------ 对齐分段

    /** 别的路换了新的共用名字：中途加入、名字还错开着的这一路马上跟着切。 */
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
}
