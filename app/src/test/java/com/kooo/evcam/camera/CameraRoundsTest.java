package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.camera.CameraRound.Action;
import com.kooo.evcam.camera.CameraRound.Call;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 账本（{@link CameraRounds}）：一台相机有一轮还没结束，谁都不下达新的一轮；结束了才放行（第 1 步「轮次 + 账本」，项目所有者 2026-10-29 批）。
 * 「相机对象」用随便一个对象当记号（SingleCamera 交的是它自己）；主线程那一侧（下达、结束）在测试线程上调。
 */
public class CameraRoundsTest {

    private static final String CAM = "1";
    /** 旧管线、新管线里同一台相机的两份相机对象。 */
    private final Object oldPipeline = new Object();
    private final Object newPipeline = new Object();
    private final CameraRounds rounds = new CameraRounds();

    /** 有一轮没结束：谁都不下达 —— 别的相机对象的不下达，这一份自己上一次开的那一轮还没结束也不下达。 */
    @Test
    public void anIssueIsRefusedWhileAnotherRoundOfTheCameraIsLive() {
        CameraRound first = rounds.issue(CAM, oldPipeline, CameraSlots.KEY_CABIN_REAR, 0);
        assertNotNull(first);
        assertSame(first, rounds.live(CAM));
        assertNull("别的相机对象", rounds.issue(CAM, newPipeline, CameraSlots.KEY_CABIN_REAR, 10));
        assertNull("同一份，上一次开的那一轮还没结束", rounds.issue(CAM, oldPipeline, CameraSlots.KEY_CABIN_REAR, 20));
        assertNotNull("别的相机照常", rounds.issue("2", newPipeline, CameraSlots.KEY_SURROUND, 30));
        assertEquals(Arrays.asList(CAM, "2"), rounds.liveIds());
    }

    /** 结束了（那一声报上来）：从账上划掉，这一台又能下达；轮次号只增。重复的那一声什么都不做。 */
    @Test
    public void theEndOfARoundFreesTheCamera() {
        CameraRound first = rounds.issue(CAM, oldPipeline, CameraSlots.KEY_CABIN_REAR, 0);
        first.noCall(5);
        assertTrue(first.takeEnd());
        assertTrue(rounds.isLive(first));
        assertTrue(rounds.end(first, 7));
        assertFalse(rounds.isLive(first));
        assertNull(rounds.live(CAM));

        CameraRound second = rounds.issue(CAM, oldPipeline, CameraSlots.KEY_CABIN_REAR, 10);
        assertNotNull(second);
        assertTrue(second.number > first.number);
        assertFalse("上一轮重复的那一声：不动这一轮", rounds.end(first, 7));
        assertSame(second, rounds.live(CAM));
    }

    /**
     * 两份管线共用同一台相机（换车型、退出后马上又开）：旧管线关它一直没返回，新管线一直不下达 —— 10 分钟以后也一样，
     * 相机服务说它空闲也不算数（这里根本没人问它）；那一次关返回了、这一轮结束了，新管线才下达。
     */
    @Test
    public void twoPipelinesSharingACameraNeverHoldItAtOnce() {
        CameraRound old = rounds.issue(CAM, oldPipeline, CameraSlots.KEY_CABIN_REAR, 0);
        Call call = old.beginCall(0);
        old.returned(call, 0);
        Object device = new Object();
        assertEquals(Action.ADOPT, old.opened(call, device, 100));
        assertTrue(old.closeRequested(1_000));   // 旧管线退出：关它（一直不返回）
        assertEquals(Collections.singletonList(CAM), rounds.closingIds());
        for (long t = 1_000; t <= 10 * 60_000; t += 60_000) {
            assertFalse(old.takeEnd());
            assertNull(rounds.issue(CAM, newPipeline, CameraSlots.KEY_CABIN_REAR, t));
        }
        old.closed(device, 10 * 60_000 + 1);
        old.closeDone(10 * 60_000 + 1);
        assertTrue(old.takeEnd());
        assertTrue(rounds.end(old, 10 * 60_000 + 1));
        assertTrue(rounds.closingIds().isEmpty());
        CameraRound fresh = rounds.issue(CAM, newPipeline, CameraSlots.KEY_CABIN_REAR, 10 * 60_000 + 2);
        assertNotNull(fresh);
        assertSame(newPipeline, fresh.owner);
    }

    /**
     * 账本记每台相机我们最近一次关完的时刻（谁的那一轮都算）：管线重建、换上新的一份之后，相机服务为旧的那一份那一关说的话
     * 照它算我们引起的（第 2 步）。开不起来、没下达关就结束的那一轮不记。
     */
    @Test
    public void theLedgerRemembersWhenWeLastClosedEachCamera() {
        assertEquals("进程里还没关过", 0, rounds.closedAt(CAM));
        CameraRound old = rounds.issue(CAM, oldPipeline, CameraSlots.KEY_CABIN_REAR, 0);
        Call call = old.beginCall(0);
        old.returned(call, 0);
        Object device = new Object();
        assertEquals(Action.ADOPT, old.opened(call, device, 100));
        assertTrue(old.closeRequested(1_000));
        old.closed(device, 1_200);
        old.closeDone(1_200);
        assertTrue(old.takeEnd());
        assertTrue(rounds.end(old, 1_250));
        assertEquals(1_250, rounds.closedAt(CAM));

        CameraRound failed = rounds.issue(CAM, newPipeline, CameraSlots.KEY_CABIN_REAR, 2_000);
        failed.noCall(2_010);
        assertTrue(failed.takeEnd());
        assertTrue(rounds.end(failed, 2_020));
        assertEquals("开不起来的那一轮不记", 1_250, rounds.closedAt(CAM));
        assertFalse("重复的那一声不动它", rounds.end(old, 3_000));
        assertEquals(1_250, rounds.closedAt(CAM));
    }

    /** 退出的看门线程只等有关没返回的那几台：稳稳开着、在开的不在里面（退出时调度先关它们）。 */
    @Test
    public void onlyClosingRoundsAreWaitedForOnExit() {
        CameraRound opening = rounds.issue("0", oldPipeline, CameraSlots.KEY_CABIN_FRONT, 0);
        CameraRound open = rounds.issue("2", oldPipeline, CameraSlots.KEY_SURROUND, 0);
        Call call = open.beginCall(0);
        open.returned(call, 0);
        assertEquals(Action.ADOPT, open.opened(call, new Object(), 10));
        assertTrue(rounds.closingIds().isEmpty());
        assertEquals(Arrays.asList("0", "2"), rounds.liveIds());
        assertTrue(opening.closeRequested(20));
        assertEquals(Collections.singletonList("0"), rounds.closingIds());
    }

    /**
     * 审查 2026-10-28 的那一种（PR #117 时两条相机线程永远堵住）换成轮次：相机 1 旧管线那一轮的相机线程 A 上，关相机那一下排着（Q），
     * 排在它前面的是一下过时的回调（S：关了之后才开好的那一台）；新管线要开同一台相机 —— 账本不下达，它就没有那一轮、没有那条线程上的关（M），
     * 也就没有谁等谁。两条线程真的跑起来：A 跑 S、再跑 Q，B 跑另一台相机那一轮丢了之后的关；都跑完，这一轮结束了新管线才下达。
     */
    @Test(timeout = 20_000)
    public void theDeadlockOf117IsGoneBothThreadsFinishAndNobodyWaits() throws Exception {
        CameraRound a = rounds.issue(CAM, oldPipeline, CameraSlots.KEY_CABIN_REAR, 0);
        Call pending = a.beginCall(0);
        a.returned(pending, 0);
        assertTrue("Q：关相机那一下排在 A 上", a.closeRequested(10));
        assertNull("新管线开同一台：不下达（不会有 M）", rounds.issue(CAM, newPipeline, CameraSlots.KEY_CABIN_REAR, 20));

        CameraRound b = rounds.issue("2", newPipeline, CameraSlots.KEY_SURROUND, 30);
        Call open = b.beginCall(30);
        b.returned(open, 30);
        Object surround = new Object();
        assertEquals(Action.ADOPT, b.opened(open, surround, 40));

        AtomicBoolean aEnded = new AtomicBoolean();
        AtomicBoolean bEnded = new AtomicBoolean();
        Thread threadA = new Thread(() -> {
            Object late = new Object();
            if (a.opened(pending, late, 50) == Action.CLOSE_LATE) {   // S：晚到的那一台就地关，不等谁
                a.closed(late, 60);
            }
            a.closeDone(70);                                         // 然后才轮到 Q
            aEnded.set(a.takeEnd());
        });
        Thread threadB = new Thread(() -> {
            if (b.failed(open, surround, false, 80) == Action.LOST) {      // 另一台丢了：先报、再关，不等谁
                b.closed(surround, 90);
            }
            bEnded.set(b.takeEnd());
        });
        threadB.start();
        threadA.start();
        threadA.join();
        threadB.join();
        assertTrue("A 跑完、这一轮结束了", aEnded.get());
        assertTrue("B 跑完、那一轮结束了", bEnded.get());

        // 主线程收到两声结束
        assertTrue(rounds.end(a, 95));
        assertTrue(rounds.end(b, 95));
        assertNotNull("这一轮结束了，新管线才下达", rounds.issue(CAM, newPipeline, CameraSlots.KEY_CABIN_REAR, 100));
    }
}
