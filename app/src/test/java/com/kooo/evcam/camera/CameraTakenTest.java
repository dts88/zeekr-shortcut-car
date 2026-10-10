package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 被别的程序拿走的相机：哪一次丢算「被拿走」、什么时候才重开、全局那张表什么时候算真放开。
 *
 * <p>判错的代价两头都大：把自己顶自己算成被拿走，看门狗就不计次数、永远不停手（2026-10-08 那七次）；
 * 把被拿走算成普通失败，就在别的程序拿着的时候按次数猛试、几分钟后彻底停手，放开了也不接
 * （2026-10-10，车机拿走后座舱相机 1）。闸门开早了，就是在相机服务交接的中途动通道。</p>
 *
 * <p>下半部分是 2.11 的判法（项目所有者 2026-10-10 确认、2026-10-11 确认几处读法）：丢失和开失败共用一个
 * {@code judge}，判成被拿着的只问不开；现场回放取自 2026-10-10 的黑匣子和 2026-09-27 的冲突。</p>
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

    // ================================================================== 2.11：判定表（丢失和开失败共用）

    private static final CameraTaken.Verdict TAKEN = CameraTaken.Verdict.TAKEN;
    private static final CameraTaken.Verdict BLOCKED = CameraTaken.Verdict.BLOCKED;
    private static final CameraTaken.Verdict UNUSABLE = CameraTaken.Verdict.UNUSABLE;
    private static final CameraTaken.Verdict ORDINARY = CameraTaken.Verdict.ORDINARY;

    /** 被断开（-4，相机层今天就这么报）。 */
    private static final CameraTaken.Reason LOST_DISCONNECTED = CameraTaken.Reason.ofLoss(DISCONNECTED);

    /** 相机层能报的每一种原因：每种来源的每个码都在，外加原因丢了（null）。 */
    private static List<CameraTaken.Reason> everyReason() {
        return Arrays.asList(
                CameraTaken.Reason.ofDisconnect(),
                CameraTaken.Reason.ofDeviceError(IN_USE),
                CameraTaken.Reason.ofDeviceError(MAX_IN_USE),
                CameraTaken.Reason.ofDeviceError(CameraDevice.StateCallback.ERROR_CAMERA_DISABLED),
                CameraTaken.Reason.ofDeviceError(DEVICE),
                CameraTaken.Reason.ofDeviceError(CameraDevice.StateCallback.ERROR_CAMERA_SERVICE),
                CameraTaken.Reason.ofAccess(CameraAccessException.CAMERA_DISABLED),
                CameraTaken.Reason.ofAccess(CameraAccessException.CAMERA_DISCONNECTED),
                CameraTaken.Reason.ofAccess(CameraAccessException.CAMERA_ERROR),
                CameraTaken.Reason.ofAccess(CameraAccessException.CAMERA_IN_USE),
                CameraTaken.Reason.ofAccess(CameraAccessException.MAX_CAMERAS_IN_USE),
                CameraTaken.Reason.ofNotListed(),
                CameraTaken.Reason.ofException("SecurityException"),
                null);
    }

    /**
     * 第 1 条：这一路不在 —— 不论报的什么、相机服务说过什么、另一路怎样，都是被拿着。
     * 10-10 车机拿着后座舱相机 1 时，相机服务一声不吭，开的时候列表里干脆没有它。
     */
    @Test
    public void anAbsentCameraIsTakenWhateverElse() {
        for (CameraTaken.Reason reason : everyReason()) {
            for (int bits = 0; bits < 8; bits++) {
                boolean heard = (bits & 1) != 0;
                boolean free = (bits & 2) != 0;
                boolean anotherTaken = (bits & 4) != 0;
                assertEquals(reason + " bits=" + bits, TAKEN,
                        CameraTaken.judge(reason, heard, free, true, anotherTaken));
            }
        }
    }

    /** 开的时候不在相机列表里，也是被拿着；相机服务的回调从没收到过也一样（项目所有者 2026-10-11 确认这样读）。 */
    @Test
    public void notInTheCameraListIsTaken() {
        CameraTaken.Reason notListed = CameraTaken.Reason.ofNotListed();
        assertEquals(TAKEN, CameraTaken.judge(notListed, false, false, false, false));
        assertEquals(TAKEN, CameraTaken.judge(notListed, true, true, false, false));
        assertEquals(TAKEN, CameraTaken.judge(notListed, true, true, false, true));
    }

    /** 第 2 条：现在用不了相机（onError 3、CAMERA_DISABLED）—— 不计次数，等主界面回到前台；只有「不在」排在它前面。 */
    @Test
    public void cameraDisabledIsUnusable() {
        CameraTaken.Reason[] disabled = {
                CameraTaken.Reason.ofDeviceError(CameraDevice.StateCallback.ERROR_CAMERA_DISABLED),
                CameraTaken.Reason.ofAccess(CameraAccessException.CAMERA_DISABLED)};
        for (CameraTaken.Reason reason : disabled) {
            assertTrue(reason.unusable());
            assertFalse(reason.inUseKind());
            assertEquals(UNUSABLE, CameraTaken.judge(reason, false, false, false, false));
            assertEquals("相机服务说被占用也一样", UNUSABLE, CameraTaken.judge(reason, true, false, false, true));
            assertEquals(UNUSABLE, CameraTaken.judge(reason, true, true, false, true));
            assertEquals("不在排在前面", TAKEN, CameraTaken.judge(reason, true, true, true, false));
        }
    }

    /** 第 3 条：相机服务从没说过话（容器没把回调转过来）：没有依据，按普通故障救 —— 哪一种原因都一样。 */
    @Test
    public void aSilentServiceMeansOrdinary() {
        for (CameraTaken.Reason reason : everyReason()) {
            if (reason != null && (reason.unusable() || reason.source == CameraTaken.Reason.Source.NOT_LISTED)) {
                continue;
            }
            assertEquals(String.valueOf(reason), ORDINARY, CameraTaken.judge(reason, false, false, false, true));
            assertEquals(String.valueOf(reason), ORDINARY, CameraTaken.judge(reason, false, true, false, true));
        }
    }

    /**
     * 第 4 条：关完了，相机服务对这一路说的最后一句不是「空闲」—— 还被占着，只能是别人。原因是什么都一样，
     * 错误 4、别的异常也是：我们这边已经关完了。
     */
    @Test
    public void aLastWordOtherThanFreeIsTaken() {
        for (CameraTaken.Reason reason : everyReason()) {
            if (reason != null && reason.unusable()) {
                continue;
            }
            assertEquals(String.valueOf(reason), TAKEN, CameraTaken.judge(reason, true, false, false, false));
            assertEquals(String.valueOf(reason), TAKEN, CameraTaken.judge(reason, true, false, false, true));
        }
    }

    /**
     * 第 5 条：最后一句是「空闲」、原因属于「被占用」一类、另一路是 TAKEN → BLOCKED（相机 1、2 冲突）。
     * 第 6 条：三样缺一样，就是普通故障。
     */
    @Test
    public void blockedNeedsFreeAnInUseKindAndAnotherTaken() {
        CameraTaken.Reason[] inUse = {
                CameraTaken.Reason.ofDisconnect(),
                CameraTaken.Reason.ofDeviceError(IN_USE),
                CameraTaken.Reason.ofDeviceError(MAX_IN_USE),
                CameraTaken.Reason.ofAccess(CameraAccessException.CAMERA_IN_USE),
                CameraTaken.Reason.ofAccess(CameraAccessException.MAX_CAMERAS_IN_USE),
                CameraTaken.Reason.ofAccess(CameraAccessException.CAMERA_DISCONNECTED)};
        for (CameraTaken.Reason reason : inUse) {
            assertTrue(String.valueOf(reason), reason.inUseKind());
            assertEquals(String.valueOf(reason), BLOCKED, CameraTaken.judge(reason, true, true, false, true));
            assertEquals("没有别的路被拿着：普通故障", ORDINARY, CameraTaken.judge(reason, true, true, false, false));
        }
    }

    /** 「被占用」一类就是确认的那几样：错误 4、5、CAMERA_ERROR、别的异常都不在里面。 */
    @Test
    public void theInUseKindIsExactlyTheConfirmedList() {
        int inUse = 0;
        for (CameraTaken.Reason reason : everyReason()) {
            if (reason != null && reason.inUseKind()) {
                inUse++;
            }
        }
        assertEquals("被断开 + onError 1、2 + CAMERA_IN_USE、MAX_CAMERAS_IN_USE、CAMERA_DISCONNECTED", 6, inUse);
        assertFalse(CameraTaken.Reason.ofDeviceError(DEVICE).inUseKind());
        assertFalse(CameraTaken.Reason.ofDeviceError(CameraDevice.StateCallback.ERROR_CAMERA_SERVICE).inUseKind());
        assertFalse(CameraTaken.Reason.ofAccess(CameraAccessException.CAMERA_ERROR).inUseKind());
        assertFalse(CameraTaken.Reason.ofException("IllegalArgumentException").inUseKind());
        assertFalse(CameraTaken.Reason.ofNotListed().inUseKind());
    }

    /**
     * 错误 4 永远不是 BLOCKED：10-10 08:57 实测，出错 2 秒后重开就成功了，它和谁占着无关。
     * 另一路被拿着、它自己空闲，也只是普通故障，按救援的节奏重开。
     */
    @Test
    public void errorFourIsNeverBlocked() {
        assertEquals(ORDINARY,
                CameraTaken.judge(CameraTaken.Reason.ofLoss(DEVICE), true, true, false, true));
        assertEquals(ORDINARY,
                CameraTaken.judge(CameraTaken.Reason.ofDeviceError(DEVICE), true, true, false, true));
    }

    /**
     * 几种来源的数字重叠，只看码会判错：onError 的 1 是被占用、CameraAccessException 的 1 是用不了相机；
     * onError 的 3 是用不了相机、CameraAccessException 的 3 只是普通的相机错误。
     */
    @Test
    public void theSourceTellsOverlappingCodesApart() {
        assertEquals(1, IN_USE);
        assertEquals(1, CameraAccessException.CAMERA_DISABLED);
        assertEquals(BLOCKED, CameraTaken.judge(CameraTaken.Reason.ofDeviceError(1), true, true, false, true));
        assertEquals(UNUSABLE, CameraTaken.judge(CameraTaken.Reason.ofAccess(1), true, true, false, true));

        assertEquals(3, CameraDevice.StateCallback.ERROR_CAMERA_DISABLED);
        assertEquals(3, CameraAccessException.CAMERA_ERROR);
        assertEquals(UNUSABLE, CameraTaken.judge(CameraTaken.Reason.ofDeviceError(3), true, true, false, true));
        assertEquals(ORDINARY, CameraTaken.judge(CameraTaken.Reason.ofAccess(3), true, true, false, true));

        assertEquals("onError 2 和 CameraAccessException 2 恰好都属于被占用一类",
                CameraTaken.Reason.ofDeviceError(2).inUseKind(), CameraTaken.Reason.ofAccess(2).inUseKind());
        assertFalse(CameraTaken.Reason.ofDeviceError(1).equals(CameraTaken.Reason.ofAccess(1)));
    }

    /** 丢失那条路报的码：-4 是被断开，其余是 onError 的码。 */
    @Test
    public void lossCodesBecomeReasons() {
        assertEquals(CameraTaken.Reason.Source.DISCONNECTED, LOST_DISCONNECTED.source);
        assertEquals(CameraTaken.Reason.ofDisconnect(), LOST_DISCONNECTED);
        assertEquals(CameraTaken.Reason.ofDeviceError(MAX_IN_USE), CameraTaken.Reason.ofLoss(MAX_IN_USE));
        assertEquals(CameraTaken.Reason.Source.DEVICE_ERROR, CameraTaken.Reason.ofLoss(DEVICE).source);
        assertEquals(DEVICE, CameraTaken.Reason.ofLoss(DEVICE).code);
        assertEquals("MAX_CAMERAS_IN_USE", CameraTaken.Reason.ofLoss(MAX_IN_USE).codeName());
        assertEquals("CAMERA_DEVICE", CameraTaken.Reason.ofLoss(DEVICE).codeName());
        assertEquals("MAX_CAMERAS_IN_USE",
                CameraTaken.Reason.ofAccess(CameraAccessException.MAX_CAMERAS_IN_USE).codeName());
        assertEquals("SecurityException", CameraTaken.Reason.ofException("SecurityException").codeName());
        assertEquals("认不出的码就是数字", "9", CameraTaken.Reason.ofDeviceError(9).codeName());
    }

    // ------------------------------------------------------------------ 开失败的几种原因

    /** 冷启动时车机还拿着相机 1：开的时候它不在相机列表里 → 直接被拿着，零次试开、不计次数。 */
    @Test
    public void openFailsBecauseCameraOneIsNotListed() {
        assertEquals(TAKEN, CameraTaken.judge(CameraTaken.Reason.ofNotListed(), true, false, false, false));
    }

    /**
     * 1 被拿着时开 2，报 MAX_CAMERAS_IN_USE（打开抛的、或者 onError 报的都一样）：2 自己空着 → BLOCKED，不计次数。
     * 本来关着的那一路照常开一次、开不起来才这样判（项目所有者 2026-10-11 确认这样读）。
     */
    @Test
    public void openingTwoWhileOneIsTakenIsBlocked() {
        assertEquals(BLOCKED, CameraTaken.judge(
                CameraTaken.Reason.ofAccess(CameraAccessException.MAX_CAMERAS_IN_USE), true, true, false, true));
        assertEquals(BLOCKED, CameraTaken.judge(
                CameraTaken.Reason.ofDeviceError(MAX_IN_USE), true, true, false, true));
    }

    /** 开不起来报错 4：普通故障，算救援梯子的一次（第二次至少隔 12 秒、3 轮后停手，见 CameraLivenessTest）。 */
    @Test
    public void openFailsWithErrorFourIsOrdinary() {
        assertEquals(ORDINARY, CameraTaken.judge(CameraTaken.Reason.ofDeviceError(DEVICE), true, true, false, false));
    }

    /** 开不起来报错 3 / CAMERA_DISABLED：现在用不了相机，不计次数，等主界面回到前台。 */
    @Test
    public void openFailsWhileTheCameraIsDisabledIsUnusable() {
        assertEquals(UNUSABLE, CameraTaken.judge(CameraTaken.Reason.ofDeviceError(
                CameraDevice.StateCallback.ERROR_CAMERA_DISABLED), true, true, false, false));
        assertEquals(UNUSABLE, CameraTaken.judge(CameraTaken.Reason.ofAccess(
                CameraAccessException.CAMERA_DISABLED), true, true, false, false));
    }

    /** 打开抛了别的异常（没权限之类）、或者原因丢了：普通故障，照梯子救、三轮停手，不会永远干等。 */
    @Test
    public void openFailsWithAnotherExceptionIsOrdinary() {
        assertEquals(ORDINARY, CameraTaken.judge(
                CameraTaken.Reason.ofException("SecurityException"), true, true, false, true));
        assertEquals(ORDINARY, CameraTaken.judge(null, true, true, false, true));
    }

    // ------------------------------------------------------------------ 现场回放

    /**
     * (a) 10-10 07:22:00：车机拿走后座舱相机 1。我们开着它时相机服务说过「1 被占用（我们开着）」，之后到 07:31:22
     * 一句都没再说；同一毫秒报的「0 空闲」「2 空闲」说的是别的相机。关它用了 5.4 秒，关完最后一句仍是被占用 → TAKEN。
     */
    @Test
    public void fieldA_theHeadUnitTakesTheRearCabin() {
        assertEquals(TAKEN, CameraTaken.judge(LOST_DISCONNECTED, true, false, false, false));
    }

    /**
     * (b) 10-10 07:51:16：「1 空闲」比关返回早 5 ms 到。等关完再判，最后一句就是空闲 → 普通故障，按救援的节奏重开。
     */
    @Test
    public void fieldB_aFreeJustBeforeTheCloseReturnsIsOrdinary() {
        assertEquals(ORDINARY, CameraTaken.judge(LOST_DISCONNECTED, true, true, false, false));
    }

    /**
     * (c) 10-10 08:56:57 我们开着相机 2 时相机服务报了一句「2 空闲」，08:57:11 2 出错误 4、关完它没再说话
     * （AOSP 对「空闲 → 空闲」不回调）。最后一句不论多早，仍是空闲；错误 4 不属于被占用一类 → 普通故障，
     * 哪怕那时 1 被拿着。只认关完前后那几句的话，这一次就会被判成被拿走。
     */
    @Test
    public void fieldC_aSpuriousFreeThenErrorFourIsOrdinary() {
        assertEquals(ORDINARY, CameraTaken.judge(CameraTaken.Reason.ofLoss(DEVICE), true, true, false, true));
    }

    /**
     * (e) 09-27 的冲突：别的程序拿了相机 1，我们的相机 2 在同一次交接里被断开。两路一起待判：先判出 1 是 TAKEN
     * （最后一句被占用、不是我们），再判 2 —— 它关完空着，被断开属于被占用一类，另一路是 TAKEN → BLOCKED，一次也不试开。
     * 次序反过来，2 会先被判成普通故障、被拿去试开，所以要先判 TAKEN。
     */
    @Test
    public void fieldE_theConflictJudgesTakenFirst() {
        CameraTaken.Verdict one = CameraTaken.judge(LOST_DISCONNECTED, true, false, false, false);
        assertEquals(TAKEN, one);
        CameraTaken.Verdict two = CameraTaken.judge(LOST_DISCONNECTED, true, true, false, one == TAKEN);
        assertEquals(BLOCKED, two);
        assertEquals("次序反了会判错", ORDINARY, CameraTaken.judge(LOST_DISCONNECTED, true, true, false, false));
    }

    // ------------------------------------------------------------------ 放开

    /** TAKEN 等相机服务说它「空闲」；它不在（缓存里没有它）不算。 */
    @Test
    public void takenIsReleasedOnlyWhenTheServiceSaysFree() {
        assertFalse(CameraTaken.releases(TAKEN, false, false, true));
        assertFalse(CameraTaken.releases(TAKEN, false, true, false));
        assertFalse("不在就没有「空闲」可言", CameraTaken.releases(TAKEN, true, true, false));
        assertTrue(CameraTaken.releases(TAKEN, true, false, false));
        assertTrue("别的路还被拿着不影响它自己", CameraTaken.releases(TAKEN, true, false, true));
    }

    /**
     * BLOCKED 等再也没有 TAKEN 的路：它自己从头到尾空着，那一声「空闲」不会再来，等的是挡着它的那一路
     * （09-27：1 放开了，2 才开得起来）。
     */
    @Test
    public void blockedIsReleasedWhenNothingIsTaken() {
        assertFalse(CameraTaken.releases(BLOCKED, true, false, true));
        assertTrue(CameraTaken.releases(BLOCKED, true, false, false));
        assertTrue(CameraTaken.releases(BLOCKED, false, false, false));
    }

    /** 普通故障、用不了相机本来就不算被拿着：没有「放开」这回事，照各自的路子走。 */
    @Test
    public void onlyTakenAndBlockedAreReleased() {
        assertFalse(CameraTaken.releases(ORDINARY, true, false, false));
        assertFalse(CameraTaken.releases(UNUSABLE, true, false, false));
        assertFalse(CameraTaken.releases(null, true, false, false));
    }

    /**
     * 09-27 的冲突走完：1 TAKEN、2 BLOCKED → 1 还被拿着时谁都不放开 → 相机服务报「1 空闲」：1 放开；
     * 1 不再算 TAKEN 之后 2 也放开。两路都空出来以后先开环视（2）、再开后座舱（1），那是调度按开的顺序排的。
     */
    @Test
    public void theConflictUnwindsWhenOneIsFreed() {
        boolean oneTaken = true;
        assertFalse(CameraTaken.releases(TAKEN, false, false, oneTaken));
        assertFalse(CameraTaken.releases(BLOCKED, true, false, oneTaken));
        assertTrue("相机服务报 1 空闲", CameraTaken.releases(TAKEN, true, false, oneTaken));
        oneTaken = false;
        assertTrue(CameraTaken.releases(BLOCKED, true, false, oneTaken));
    }

    /** 数字是确认过的：30 秒问一次（只问，不开），别人引起的变化过去 3 秒才算安静。 */
    @Test
    public void askAndQuietKeepTheConfirmedNumbers() {
        assertEquals(30_000L, CameraTaken.ASK_EVERY_MS);
        assertEquals(3_000L, CameraTaken.QUIET_MS);
    }
}
