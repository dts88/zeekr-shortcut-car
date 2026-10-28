package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一台相机还没返回的关串成的链（{@link CloseChain}，SingleCamera.CLOSING）：登记、不等自己、链头的时刻、返回之后断开。
 * 「线程」用随便一个对象当记号（SingleCamera 给相机线程的 Looper）；「等前一次返回再放行」是调用方的事（SingleCamera.finishClosing），
 * 这里照它的样子做。
 */
public class CloseChainTest {

    private static final String ID = "1";
    /** 三条线程的记号：两条相机线程、主线程。 */
    private final Object threadA = new Object();
    private final Object threadB = new Object();
    private final Object main = new Object();
    private final CloseChain chain = new CloseChain();

    private static CloseChain.Closing closing(String why, Object finisher, long since) {
        return new CloseChain.Closing(why, finisher, since);
    }

    /**
     * 审查 2026-10-28 的那一种（两条相机线程永远堵住）：相机线程 A 上排着一次关（Q：关相机那一下），上面压了一次 B 上的关
     * （M：另一份实例那边丢了之后的关，回调里当场登记），A 上排在 Q 前面的那一下过时的回调要就地关（S）。只看最新的那一次
     * （M 在 B 上）会登记 S、让 A 等 M —— M 等 Q，Q 排在 A 上这一下后面。看整条链：Q 还没返回、排在 A 上，S 不另登记、不等；
     * 最新登记的还是 M，它盖住 S（M 要等 Q 返回才返回，Q 在 S 那一下之后才开始）。
     */
    @Test
    public void aCloseIsNotRegisteredBehindAnAncestorQueuedOnThisThread() {
        CloseChain.Closing q = closing("closeCamera", threadA, 1_000);
        assertNull(chain.begin(ID, q, main));
        CloseChain.Closing m = closing("onDisconnected", threadB, 2_000);
        assertSame("M 压在 Q 上", q, chain.begin(ID, m, threadB));
        assertSame(q, m.previous);
        assertFalse("最新的那一次不在 A 上：只看它的话会登记", m.finisher == threadA);

        CloseChain.Closing s = closing("stale disconnect", threadA, 3_000);
        assertNull("不另登记、不等", chain.begin(ID, s, threadA));
        assertSame("最新登记的还是 M", m, chain.get(ID));
        assertNull(s.previous);
        assertEquals(1_000, chain.outstandingSince(ID));

        // A 上：S 关完（不等谁），接着跑 Q 的关；B 上 M 等 Q 返回了才返回
        chain.finish(ID, s);
        assertSame("没登记的那一次返回了，不动最新的那一次", m, chain.get(ID));
        chain.finish(ID, q);
        assertSame(m, chain.get(ID));
        chain.finish(ID, m);
        assertNull(chain.get(ID));
        assertFalse(chain.containsKey(ID));
        assertEquals(0, chain.outstandingSince(ID));
    }

    /**
     * 同上，两条线程真的跑起来：A 先跑过时的回调（S），再跑 Q 的关；B 等 Q 返回了才让 M 返回。只看最新的那一次的话，
     * A 在 S 里等 M、B 在 M 里等 Q、Q 排在 A 上 —— 等到超时。
     */
    @Test(timeout = 20_000)
    public void bothCameraThreadsOfThatCaseFinish() throws Exception {
        CloseChain.Closing q = closing("closeCamera", threadA, 1_000);
        chain.begin(ID, q, main);
        CloseChain.Closing m = closing("onDisconnected", threadB, 2_000);
        CountDownLatch previousOfM = chain.begin(ID, m, threadB);
        AtomicReference<String> stuck = new AtomicReference<>();

        Thread b = new Thread(() -> {
            if (!awaitBriefly(previousOfM)) {
                stuck.compareAndSet(null, "B 等 Q");
            }
            chain.finish(ID, m);
        });
        Thread a = new Thread(() -> {
            // 排在 Q 前面的那一下：过时的回调就地关
            CloseChain.Closing s = closing("stale disconnect", threadA, 3_000);
            CountDownLatch previousOfS = chain.begin(ID, s, threadA);
            if (!awaitBriefly(previousOfS)) {
                stuck.compareAndSet(null, "A 等 M");
            }
            chain.finish(ID, s);
            // 然后才轮到 Q 的关
            chain.finish(ID, q);
        });
        b.start();
        a.start();
        a.join();
        b.join();
        assertNull("两条线程都没堵住：" + stuck.get(), stuck.get());
        assertFalse(chain.containsKey(ID));
    }

    /** 最新登记的那一次排在这条线程上（以前那一种）照旧盖住这一次。 */
    @Test
    public void theNewestCloseQueuedOnThisThreadStillCoversIt() {
        CloseChain.Closing q = closing("reopen", threadA, 1_000);
        chain.begin(ID, q, main);
        CloseChain.Closing s = closing("onError", threadA, 1_200);
        assertNull(chain.begin(ID, s, threadA));
        assertSame(q, chain.get(ID));
        assertTrue(q.queuedOn(threadA));
        assertFalse(q.queuedOn(threadB));
        assertFalse("不知道在哪条线程上的不算", closing("x", null, 0).queuedOn(null));
        assertFalse(q.queuedOn(null));
    }

    /**
     * 链头的时刻（审查 2026-10-28）：一次关卡住了，上面又压一次关（晚到的设备就地关、另一份实例在关），这一台「从什么时候起一直有关
     * 没返回」不变 —— 前面那一次先返回了也不变（压上去的那一次登记时它还没返回）；都返回了才清。之后再关是新的一条链。
     */
    @Test
    public void theChainStartSurvivesACloseStackedOnAStuckOne() {
        CloseChain.Closing stuckOne = closing("onDisconnected", threadA, 1_000);
        chain.begin(ID, stuckOne, threadA);
        assertEquals(1_000, chain.outstandingSince(ID));
        CloseChain.Closing stacked = closing("opened after close", threadB, 61_000);
        chain.begin(ID, stacked, threadB);
        assertEquals(1_000, stacked.outstandingSince);
        assertEquals("不从头算", 1_000, chain.outstandingSince(ID));

        chain.finish(ID, stuckOne);
        assertEquals("前面那一次先返回了也不变", 1_000, chain.outstandingSince(ID));
        chain.finish(ID, stacked);
        assertEquals(0, chain.outstandingSince(ID));

        CloseChain.Closing next = closing("unwanted", threadA, 100_000);
        assertNull(chain.begin(ID, next, main));
        assertEquals("新的一条链", 100_000, chain.outstandingSince(ID));
    }

    /**
     * 返回的次序和断开：前面那一次返回了，往前看就停在它那里（它前面的都返回了），排在它那条线程上的关又能登记；
     * 一次返回了就和前一次断开，链上已经返回的不留着。上面又登记了一次的那一次返回了，最新登记的不动。
     */
    @Test
    public void theWalkStopsAtAReturnedCloseAndAReturnedCloseDropsItsPrevious() {
        CloseChain.Closing first = closing("closeCamera", threadA, 1_000);
        chain.begin(ID, first, main);
        CloseChain.Closing second = closing("onDisconnected", threadB, 2_000);
        chain.begin(ID, second, threadB);
        assertTrue("经过 first：排在 A 上", second.queuedOn(threadA));

        chain.finish(ID, first);
        assertNull(first.previous);
        assertSame("second 还没返回，还连着", first, second.previous);
        assertFalse("往前看停在返回了的 first", second.queuedOn(threadA));
        assertSame(second, chain.get(ID));

        CloseChain.Closing third = closing("stale error", threadA, 3_000);
        assertSame("A 上的关又能登记：压在 second 上", second, chain.begin(ID, third, threadA));
        assertEquals(1_000, third.outstandingSince);

        chain.finish(ID, second);
        assertNull("返回了就断开", second.previous);
        assertSame("上面又登记了一次：最新的不动", third, chain.get(ID));
        assertFalse(third.queuedOn(threadB));
        chain.finish(ID, third);
        assertNull(third.previous);
        assertFalse(chain.containsKey(ID));
        assertTrue(chain.all().isEmpty());
    }

    /** 每台相机各是一条链：相机 1 排在 A 上的关不挡相机 2 在 A 上登记。 */
    @Test
    public void eachCameraHasItsOwnChain() {
        CloseChain.Closing one = closing("unwanted", threadA, 1_000);
        CloseChain.Closing two = closing("unwanted", threadB, 2_000);
        assertNull(chain.begin("1", one, main));
        assertNull(chain.begin("2", two, main));
        assertEquals(1_000, chain.outstandingSince("1"));
        assertEquals(2_000, chain.outstandingSince("2"));
        assertEquals(2, chain.all().size());

        CloseChain.Closing onA = closing("stale error", threadA, 3_000);
        assertSame(two, chain.begin("2", onA, threadA));
        assertSame(onA, chain.get("2"));
        assertSame(one, chain.get("1"));

        chain.finish("1", one);
        assertEquals(0, chain.outstandingSince("1"));
        assertEquals(2_000, chain.outstandingSince("2"));
        chain.finish("2", two);
        chain.finish("2", onA);
        assertTrue(chain.all().isEmpty());
    }

    private static boolean awaitBriefly(CountDownLatch latch) {
        if (latch == null) {
            return true;
        }
        try {
            return latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
