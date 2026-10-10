package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.hardware.camera2.CameraDevice;

import org.junit.Test;

/**
 * 被别的程序拿走的相机：哪一次丢算「被拿走」、什么时候才重开、全局那张表什么时候算真放开。
 *
 * <p>判错的代价两头都大：把自己顶自己算成被拿走，看门狗就不计次数、永远不停手（2026-10-08 那七次）；
 * 把被拿走算成普通失败，就在别的程序拿着的时候按次数猛试、几分钟后彻底停手，放开了也不接
 * （2026-10-10，车机拿走后座舱相机 1）。闸门开早了，就是在相机服务交接的中途动通道。</p>
 */
public class CameraTakenTest {

    private static final int DISCONNECTED = -4;
    private static final int IN_USE = CameraDevice.StateCallback.ERROR_CAMERA_IN_USE;
    private static final int MAX_IN_USE = CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE;
    private static final int DEVICE = CameraDevice.StateCallback.ERROR_CAMERA_DEVICE;
    /** 很久以前就这样了。 */
    private static final long LONG_AGO = 60_000L;

    private final CameraTaken.Book book = new CameraTaken.Book();

    // ------------------------------------------------------------------ 哪一次丢算被拿走

    /** 关完了、相机服务说它被占用、我们自己不占着：别的程序拿着 —— 被断开、报 1、报 2 都一样。 */
    @Test
    public void heldWhenTheServiceSaysTakenAndItIsNotUs() {
        assertEquals(CameraTaken.Loss.HELD, CameraTaken.judge(DISCONNECTED, false, false, false));
        assertEquals(CameraTaken.Loss.HELD, CameraTaken.judge(IN_USE, false, false, false));
        assertEquals(CameraTaken.Loss.HELD, CameraTaken.judge(MAX_IN_USE, false, false, true));
    }

    /**
     * 被断开了、相机服务却没说别的程序占着：按普通的失败算，计次数、三次停手。
     * 2026-10-08 那七次「被断开」全是自己顶自己，不能因此不计次数。
     */
    @Test
    public void ordinaryWhenNobodyElseHoldsIt() {
        assertEquals(CameraTaken.Loss.ORDINARY, CameraTaken.judge(DISCONNECTED, true, false, false));
        assertEquals(CameraTaken.Loss.ORDINARY, CameraTaken.judge(IN_USE, true, false, false));
        // 相机服务的通知从来没收到过（容器没转过来）：没有依据，照普通的算
        assertEquals(CameraTaken.Loss.ORDINARY, CameraTaken.judge(DISCONNECTED, null, false, false));
    }

    /** 我们自己又在开它（另一次打开排在后面）：「被占用」说的是我们自己。 */
    @Test
    public void ordinaryWhenTheBusyOneIsUs() {
        assertEquals(CameraTaken.Loss.ORDINARY, CameraTaken.judge(DISCONNECTED, false, true, true));
        assertEquals(CameraTaken.Loss.ORDINARY, CameraTaken.judge(MAX_IN_USE, true, true, true));
    }

    /**
     * 相机数到上限（报 2）、它自己空闲、别的程序占着另一路：1 和 2 冲突的那种，被另一路挡着。
     * 只有报 2 才这么认 —— 被断开、报 1 时另一路被占着，不说明是它挡的。
     */
    @Test
    public void blockedOnlyByErrorTwoWhileAnotherIsHeld() {
        assertEquals(CameraTaken.Loss.BLOCKED, CameraTaken.judge(MAX_IN_USE, true, false, true));
        assertEquals(CameraTaken.Loss.ORDINARY, CameraTaken.judge(MAX_IN_USE, true, false, false));
        assertEquals(CameraTaken.Loss.ORDINARY, CameraTaken.judge(DISCONNECTED, true, false, true));
        assertEquals(CameraTaken.Loss.ORDINARY, CameraTaken.judge(IN_USE, true, false, true));
        assertEquals(CameraTaken.Loss.ORDINARY, CameraTaken.judge(DEVICE, true, false, true));
    }

    /**
     * 相机服务那一声「被占用」被记成了我们开着：在丢之前紧挨着报的（被拿走时那一声可能比断开先到）才算别的程序；
     * 更早的是我们自己开着它时的旧账 —— 被断开之后的「空闲」可能还在路上，说不准，按普通的失败算。
     */
    @Test
    public void anOldBusyOfOurOwnIsNotATake() {
        long lostAt = 100_000;
        assertEquals(Boolean.FALSE, CameraTaken.serviceSays(false, false, 10_000, lostAt));
        assertEquals(Boolean.FALSE,
                CameraTaken.serviceSays(false, true, lostAt - CameraTaken.TAKE_LINK_MS, lostAt));
        assertEquals("关设备的那几秒里报的", Boolean.FALSE, CameraTaken.serviceSays(false, true, lostAt + 2_000, lostAt));
        assertNull(CameraTaken.serviceSays(false, true, lostAt - CameraTaken.TAKE_LINK_MS - 1, lostAt));
        assertEquals(CameraTaken.Loss.ORDINARY, CameraTaken.judge(DISCONNECTED,
                CameraTaken.serviceSays(false, true, 10_000, lostAt), false, false));
        assertEquals(Boolean.TRUE, CameraTaken.serviceSays(true, true, 10_000, lostAt));
        assertNull("没收到过", CameraTaken.serviceSays(null, false, 0, lostAt));
    }

    // ------------------------------------------------------------------ 闸门

    /** 通道还在变（交接还没完）：放开了也不动。 */
    @Test
    public void waitsWhileTheChannelIsChanging() {
        assertEquals(CameraTaken.Gate.WAIT, CameraTaken.gate(false, LONG_AGO, 0));
        assertEquals(CameraTaken.Gate.WAIT, CameraTaken.gate(false, LONG_AGO, CameraTaken.QUIET_MS - 1));
        assertEquals(CameraTaken.Gate.WAIT, CameraTaken.gate(true, -1, CameraTaken.QUIET_MS - 1));
    }

    /** 别的程序还占着哪一路：只按 30 秒的节奏试，不重开。 */
    @Test
    public void probesWhileOthersHoldAnything() {
        assertEquals(CameraTaken.Gate.PROBE, CameraTaken.gate(true, -1, LONG_AGO));
        assertEquals(CameraTaken.Gate.PROBE, CameraTaken.gate(true, LONG_AGO, LONG_AGO));
    }

    /** 别的程序一路都不占、这一路自己空闲满 3 秒、所有相机 3 秒没变过：单独重开。 */
    @Test
    public void reopensOnceReleasedAndQuiet() {
        assertEquals(CameraTaken.Gate.REOPEN,
                CameraTaken.gate(false, CameraTaken.QUIET_MS, CameraTaken.QUIET_MS));
        assertEquals(CameraTaken.Gate.REOPEN, CameraTaken.gate(false, LONG_AGO, LONG_AGO));
    }

    /**
     * 相机服务的说法对不上：别的程序都不占了，这一路却还报被占用（或者表上没有它）。
     * 不重开，但也不永远干等 —— 照 30 秒的节奏试。
     */
    @Test
    public void probesWhenTheServiceDisagreesWithItself() {
        assertEquals(CameraTaken.Gate.PROBE, CameraTaken.gate(false, -1, LONG_AGO));
    }

    // ------------------------------------------------------------------ 账本

    /** 全局那张表：别的程序一路都不占了才算真放开，划掉一路还剩一路不算。 */
    @Test
    public void releasedOnlyWhenNothingIsHeldAnyMore() {
        assertTrue(book.othersTook("0"));
        assertTrue(book.othersTook("1"));
        assertFalse("同一路再报一次不是新的", book.othersTook("1"));
        assertTrue(book.heldIds().contains("1"));
        assertFalse(book.released("1"));
        assertTrue(book.othersHold());
        assertTrue(book.released("0"));
        assertFalse(book.othersHold());
        assertFalse("没在表上的那一路放开（我们开着的那几路被乱报「空闲」）什么也不算", book.released("2"));
    }

    @Test
    public void knowsWhetherAnotherCameraIsHeld() {
        assertFalse(book.othersHoldOtherThan("2"));
        book.othersTook("2");
        assertFalse("只有它自己被占着", book.othersHoldOtherThan("2"));
        book.othersTook("1");
        assertTrue(book.othersHoldOtherThan("2"));
    }

    /**
     * 1 和 2 冲突（2026-09-27 实测）：别的程序拿了 1 → 我们的 2 被断开 → 重开 2 报 2 → 1 放开 → 2 才能重开。
     * 2 自己从头到尾没人占，它等的是 1 放开，不是它自己的「空闲」—— 那一声永远不会再来。
     */
    @Test
    public void cameraTwoWaitsForCameraOneInTheDocumentedConflict() {
        // 相机服务报 1 被占用、不是我们
        book.othersTook("1");
        // 我们的 2 紧跟着被断开；关完时相机服务报 2 空闲（没人占它）：按普通的失败算，看门狗计次数重开
        assertEquals(CameraTaken.Loss.ORDINARY,
                CameraTaken.judge(DISCONNECTED, true, false, book.othersHoldOtherThan("2")));
        // 看门狗重开 2，报错 2：被别的程序占着的 1 挡着
        CameraTaken.Loss loss = CameraTaken.judge(MAX_IN_USE, true, false, book.othersHoldOtherThan("2"));
        assertEquals(CameraTaken.Loss.BLOCKED, loss);
        book.bench("2", 1_000, true);
        assertTrue(book.benched("2"));
        assertFalse("2 不进全局那张表：它不会有「空闲」来把自己划掉", book.heldIds().contains("2"));
        assertEquals("2<-[1]", book.describeBenched());
        // 1 还被占着：2 只按 30 秒的节奏试
        assertEquals(CameraTaken.Gate.PROBE, CameraTaken.gate(book.othersHold(), LONG_AGO, LONG_AGO));
        // 试的那一下又报 2：还从最初那一刻算
        book.bench("2", 31_000, true);
        // 1 放开了：别的程序一路都不占
        assertTrue(book.released("1"));
        // 刚放开，通道还在变
        assertEquals(CameraTaken.Gate.WAIT, CameraTaken.gate(book.othersHold(), LONG_AGO, 0));
        // 3 秒没变过：2 单独重开
        assertEquals(CameraTaken.Gate.REOPEN,
                CameraTaken.gate(book.othersHold(), LONG_AGO, CameraTaken.QUIET_MS));
        assertEquals("被拿走了多久从最初那一刻算", 99_000, book.unbench("2", 100_000));
        assertFalse(book.benched("2"));
        assertEquals(-1, book.unbench("2", 100_000));
    }

    /** 别的程序拿着它自己（2026-10-10 那种）：进全局那张表，等它自己的「空闲」。 */
    @Test
    public void aHeldCameraWaitsForItsOwnRelease() {
        book.othersTook("1");
        book.bench("1", 5_000, false);
        assertEquals("1", book.describeBenched());
        assertTrue(book.anyBenched());
        assertEquals(CameraTaken.Gate.PROBE, CameraTaken.gate(book.othersHold(), -1, LONG_AGO));
        assertTrue(book.released("1"));
        // 它自己刚报空闲：这一声本身就是一次变化，等满 3 秒
        assertEquals(CameraTaken.Gate.WAIT, CameraTaken.gate(book.othersHold(), 1_000, 1_000));
        assertEquals(CameraTaken.Gate.REOPEN,
                CameraTaken.gate(book.othersHold(), CameraTaken.QUIET_MS, CameraTaken.QUIET_MS));
    }
}
