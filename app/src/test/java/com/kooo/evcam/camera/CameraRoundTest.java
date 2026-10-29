package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.camera.CameraRound.Action;
import com.kooo.evcam.camera.CameraRound.Call;
import com.kooo.evcam.camera.CameraRound.Phase;

import org.junit.Test;

/**
 * 一轮（{@link CameraRound}）：什么时候结束、回调来了怎么办（第 1 步「轮次 + 账本」，项目所有者 2026-10-29 批）。
 * 「设备」用随便一个对象（SingleCamera 交的是 CameraDevice，按对象认）；关相机那一下、openCamera、回调都照 SingleCamera 的样子
 * 一件一件调，每一次改完问一次 {@link CameraRound#takeEnd}。没有哪一下要等谁。
 */
public class CameraRoundTest {

    private static final long T0 = 1_000L;
    private final Object owner = new Object();

    private CameraRound issue() {
        return new CameraRound(1, "1", owner, CameraSlots.KEY_CABIN_REAR, T0);
    }

    /** 开好、挂上的那一次：哪一次 openCamera、交来的哪一台。 */
    private static final class Opened {
        final Call call;
        final Object device;

        Opened(Call call, Object device) {
            this.call = call;
            this.device = device;
        }
    }

    /** 开好、挂上（ADOPT）：这一轮稳稳开着。 */
    private static Opened openToAdopt(CameraRound round, long at) {
        Call call = round.beginCall(at);
        assertNotNull(call);
        round.returned(call, at);
        Object device = new Object();
        assertEquals(Action.ADOPT, round.opened(call, device, at + 100));
        assertFalse(round.takeEnd());
        assertEquals(Phase.OPEN, round.phase());
        assertEquals("稳稳开着：没有开或关没返回", 0, round.outstandingSince());
        return new Opened(call, device);
    }

    // ================================================================= 下达关的那一刻还没进 openCamera

    /** 开之前就被关了（开的那一下排在关相机那一下前面）：不进 openCamera；关相机那一下做完当场结束，不等任何回调。 */
    @Test
    public void aCancelBeforeOpenCameraEndsAsSoonAsTheCloseIsDone() {
        CameraRound round = issue();
        assertEquals(Phase.OPENING, round.phase());
        assertEquals("下达的那一刻起有开没返回", T0, round.outstandingSince());
        assertTrue(round.closeRequested(T0 + 10));
        assertEquals(Phase.CLOSING, round.phase());
        assertNull("开的那一下：不进 openCamera", round.beginCall(T0 + 20));
        assertFalse("关相机那一下还排着", round.takeEnd());
        round.closeDone(T0 + 30);
        assertTrue(round.takeEnd());
        assertFalse("只结束一次", round.takeEnd());
        assertTrue(round.ended());
        assertEquals("一直有开或关没返回：钟从下达那一刻起，没从头算", T0, round.outstandingSince());
    }

    /** 同上，次序反过来（重开等的那 300 ms 里被关了：关相机那一下先跑完）：开的那一下跑到时这一轮才结束。 */
    @Test
    public void aCancelDuringTheReopenGapEndsWhenTheOpenGivesUp() {
        CameraRound round = issue();
        assertTrue(round.closeRequested(T0 + 10));
        round.closeDone(T0 + 20);
        assertFalse("还有一次开没进 openCamera", round.takeEnd());
        round.noCall(T0 + 300);
        assertTrue(round.takeEnd());
    }

    // ================================================================= openCamera 抛了

    /** openCamera 抛了（没被关）：这一次没有在等的，当场结束 —— 报开不起来。框架照样 post 的那一声回调不归账。 */
    @Test
    public void anOpenCameraThatThrowsEndsTheRoundAtOnce() {
        CameraRound round = issue();
        Call call = round.beginCall(T0);
        assertTrue(round.status().inOpenCamera);
        round.threw(call, T0 + 5);
        assertTrue(round.takeEnd());
        assertEquals("抛了那一次的回调：不归账", Action.STRAY, round.failed(call, new Object(), T0 + 10));
    }

    /** 被关之后 openCamera 才抛：关相机那一下做完就结束，不等回调。 */
    @Test
    public void anOpenCameraThatThrowsAfterACancelEndsWithTheClose() {
        CameraRound round = issue();
        Call call = round.beginCall(T0);
        assertTrue(round.closeRequested(T0 + 10));
        round.threw(call, T0 + 20);
        assertFalse("关相机那一下还排着", round.takeEnd());
        round.closeDone(T0 + 30);
        assertTrue(round.takeEnd());
    }

    /** 查列表、查参数就没成（不在列表里）：没进 openCamera，当场结束。 */
    @Test
    public void anOpenThatFailsBeforeOpenCameraEndsAtOnce() {
        CameraRound round = issue();
        round.noCall(T0 + 5);
        assertTrue(round.takeEnd());
    }

    // ================================================================= openCamera 返回了、回调还没来就被关了

    /**
     * openCamera 返回之后、onOpened 之前被关了（熄屏、退出赶上开座舱）：关相机那一下做完也不结束 —— 那一次还在等回调；
     * 晚到的 onOpened 交来一台设备，就地关掉（CLOSE_LATE），关完才结束。钟一直从下达那一刻起。
     */
    @Test
    public void aCancelAfterOpenCameraReturnedEndsOnlyAfterTheLateDeviceIsClosed() {
        CameraRound round = issue();
        Call call = round.beginCall(T0);
        round.returned(call, T0 + 50);
        assertTrue(round.status().openPending);
        assertTrue(round.closeRequested(T0 + 100));
        round.closeDone(T0 + 120);
        assertFalse("那一次 openCamera 还在等回调", round.takeEnd());
        assertEquals(Phase.CLOSING, round.phase());
        assertTrue(round.status().closing());

        Object late = new Object();
        assertEquals(Action.CLOSE_LATE, round.opened(call, late, T0 + 400));
        assertFalse("晚到的那一台还没关", round.takeEnd());
        round.closed(late, T0 + 500);
        assertTrue(round.takeEnd());
        assertEquals(T0, round.outstandingSince());
    }

    /** 同上，晚到的是 onError：那一个设备对象也关掉，关完才结束。 */
    @Test
    public void aCancelledOpenAnsweredByAnErrorEndsAfterItsDeviceIsClosed() {
        CameraRound round = issue();
        Call call = round.beginCall(T0);
        round.returned(call, T0 + 50);
        assertTrue(round.closeRequested(T0 + 100));
        round.closeDone(T0 + 120);
        Object device = new Object();
        assertEquals(Action.CLOSE_LATE, round.failed(call, device, T0 + 300));
        assertFalse(round.takeEnd());
        round.closed(device, T0 + 310);
        assertTrue(round.takeEnd());
    }

    /** 同上，晚到的是 onDisconnected（和 onError 同一个入口）；关相机那一下比回调晚做完也一样。 */
    @Test
    public void aCancelledOpenAnsweredByADisconnectEndsAfterTheCloseAndTheDevice() {
        CameraRound round = issue();
        Call call = round.beginCall(T0);
        round.returned(call, T0 + 50);
        assertTrue(round.closeRequested(T0 + 100));
        Object device = new Object();
        assertEquals(Action.CLOSE_LATE, round.failed(call, device, T0 + 200));
        round.closed(device, T0 + 210);
        assertFalse("关相机那一下还没做完", round.takeEnd());
        round.closeDone(T0 + 220);
        assertTrue(round.takeEnd());
    }

    /** 开不起来（还没开好就报错，没被关）：OPEN_FAILED，那一个设备对象关完才结束 —— 结束了才报开不起来。 */
    @Test
    public void anOpenAnsweredByAnErrorEndsAfterItsDeviceIsClosed() {
        CameraRound round = issue();
        Call call = round.beginCall(T0);
        round.returned(call, T0 + 50);
        Object device = new Object();
        assertEquals(Action.OPEN_FAILED, round.failed(call, device, T0 + 80));
        assertEquals("关那一个设备对象", Phase.CLOSING, round.phase());
        assertFalse(round.takeEnd());
        round.closed(device, T0 + 90);
        assertTrue(round.takeEnd());
    }

    // ================================================================= 开着的时候

    /** 挂着的那一台丢了（被断开、报错）：在丢，关完结束；之后同一台再来一声不归账。 */
    @Test
    public void aLossThenItsCloseEndsTheRound() {
        CameraRound round = issue();
        Opened open = openToAdopt(round, T0);
        Call call = open.call;
        Object device = open.device;
        assertEquals(Action.LOST, round.failed(call, device, T0 + 5_000));
        assertEquals(Phase.LOSING, round.phase());
        assertEquals("丢的那一刻起有关没返回", T0 + 5_000, round.outstandingSince());
        assertEquals("同一台又报一声：关已经在做", Action.IGNORE, round.failed(call, device, T0 + 5_001));
        assertFalse(round.takeEnd());
        round.closed(device, T0 + 11_000);
        assertTrue(round.takeEnd());
        assertEquals(Action.STRAY, round.failed(call, device, T0 + 11_001));
    }

    /** 下达关之后挂着的那一台才报错：关相机那一下在关它，什么都不做（不是丢了）。 */
    @Test
    public void anErrorAfterTheCloseWasOrderedIsLeftToTheClose() {
        CameraRound round = issue();
        Opened open = openToAdopt(round, T0);
        Call call = open.call;
        Object device = open.device;
        assertTrue(round.closeRequested(T0 + 1_000));
        assertEquals(Action.IGNORE, round.failed(call, device, T0 + 1_001));
        round.closed(device, T0 + 1_200);
        round.closeDone(T0 + 1_200);
        assertTrue(round.takeEnd());
    }

    /** 关卡住了（close() 不返回）：永远不结束，钟停在开始关的那一刻 —— 10 分钟以后也一样。 */
    @Test
    public void aStuckCloseNeverEndsTheRound() {
        CameraRound round = issue();
        openToAdopt(round, T0);
        assertTrue(round.closeRequested(T0 + 2_000));
        for (long t = T0 + 2_000; t <= T0 + 10 * 60_000; t += 30_000) {
            assertFalse(round.takeEnd());
        }
        assertEquals(Phase.CLOSING, round.phase());
        assertEquals(T0 + 2_000, round.outstandingSince());
        assertFalse(round.ended());
    }

    /** openCamera 卡在相机服务里（不返回）：永远不结束；被关了也一样（关相机那一下做完了，openCamera 还在调）。 */
    @Test
    public void aStuckOpenCameraNeverEndsTheRound() {
        CameraRound round = issue();
        assertNotNull(round.beginCall(T0));
        assertFalse(round.takeEnd());
        assertTrue(round.status().inOpenCamera);
        assertEquals(Phase.OPENING, round.phase());
        assertTrue(round.closeRequested(T0 + 60_000));
        round.closeDone(T0 + 60_001);
        assertFalse(round.takeEnd());
        assertEquals("一直有开没返回：从下达那一刻起", T0, round.outstandingSince());
        assertEquals("closing openCamera", round.status().kinds());
    }

    // ================================================================= 救援重开（同一轮）

    /**
     * 开着时救援重开：同一轮里关旧的、开新的，中间一直不结束；关旧的时候在关、之后在开，钟从重开那一刻起，开好了归 0。
     * 旧的那一次晚到的回调认不成新的那一次：旧设备还在关时什么都不做，关完以后不归账。
     */
    @Test
    public void aReopenStaysInOneRoundAndALateCallbackOfTheOlderCallIsIgnored() {
        CameraRound round = issue();
        Opened old = openToAdopt(round, T0);
        Call oldCall = old.call;
        Object oldDevice = old.device;
        assertTrue(round.reopenRequested(T0 + 10_000));
        assertTrue(round.status().reopening);
        assertEquals("关旧的", Phase.CLOSING, round.phase());
        assertEquals(T0 + 10_000, round.outstandingSince());
        assertFalse("重开时不另起一次重开", round.reopenRequested(T0 + 10_001));
        assertEquals("旧的那一次报错：旧设备在关", Action.IGNORE, round.failed(oldCall, oldDevice, T0 + 10_002));
        round.closed(oldDevice, T0 + 14_000);
        assertFalse(round.takeEnd());
        assertEquals("关完旧的，在开新的", Phase.OPENING, round.phase());
        assertEquals("reopen", round.status().kinds());

        Call newCall = round.beginCall(T0 + 14_300);
        round.returned(newCall, T0 + 14_300);
        assertEquals("旧的那一次又来一声：早关完了，不归账", Action.STRAY, round.failed(oldCall, oldDevice, T0 + 14_400));
        Object newDevice = new Object();
        assertEquals(Action.ADOPT, round.opened(newCall, newDevice, T0 + 14_500));
        assertEquals(Phase.OPEN, round.phase());
        assertEquals(0, round.outstandingSince());
        assertFalse(round.status().reopening);
        assertFalse(round.takeEnd());
        assertEquals(2, newCall.number);
    }

    /** 重开只给挂着一台、什么都不在途的那一轮：在开、在丢、下达了关都合并掉。 */
    @Test
    public void aReopenIsRefusedWhileAnythingIsInFlight() {
        CameraRound opening = issue();
        assertFalse("还没开好", opening.reopenRequested(T0));
        Call call = opening.beginCall(T0);
        opening.returned(call, T0);
        assertFalse("openCamera 在等回调", opening.reopenRequested(T0 + 1));

        CameraRound lost = issue();
        Opened open = openToAdopt(lost, T0);
        assertEquals(Action.LOST, lost.failed(open.call, open.device, T0 + 1_000));
        assertFalse("在丢", lost.reopenRequested(T0 + 1_001));

        CameraRound closing = issue();
        openToAdopt(closing, T0);
        assertTrue(closing.closeRequested(T0 + 1_000));
        assertFalse("下达了关", closing.reopenRequested(T0 + 1_001));
    }

    /** 重开那一下的 openCamera 被关掉之前已经返回：照样等它的回调，晚到的设备关掉才结束。 */
    @Test
    public void aReopenCancelledWhileItsOpenIsPendingWaitsForTheCallback() {
        CameraRound round = issue();
        Object oldDevice = openToAdopt(round, T0).device;
        assertTrue(round.reopenRequested(T0 + 1_000));
        round.closed(oldDevice, T0 + 1_100);
        Call call = round.beginCall(T0 + 1_400);
        round.returned(call, T0 + 1_400);
        assertTrue(round.closeRequested(T0 + 1_500));
        round.closeDone(T0 + 1_510);
        assertFalse(round.takeEnd());
        Object late = new Object();
        assertEquals(Action.CLOSE_LATE, round.opened(call, late, T0 + 1_700));
        round.closed(late, T0 + 1_800);
        assertTrue(round.takeEnd());
        assertEquals("重开那一刻起一直有开或关没返回", T0 + 1_000, round.outstandingSince());
    }

    /** 结束了的那一轮不再收关：相机层当它没开着。 */
    @Test
    public void anEndedRoundRefusesAClose() {
        CameraRound round = issue();
        round.noCall(T0);
        assertTrue(round.takeEnd());
        assertFalse(round.closeRequested(T0 + 1));
    }
}
