package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相机服务的每一声算谁引起的，问来的快照怎么并进我们的记录。
 *
 * <p>分错了的代价：把我们自己开关时它报的那几声算成别人的，按次序开的每一路都要多等 3 秒；把别人的算成我们的
 * （尤其是我们稳稳开着时它乱报的「空闲」），就会在相机服务交接的中途动通道。并错了的代价：漏收的那一声补不上，
 * 被拿着的那一路放开了也不知道；该记「不在」的没记，车机拿着后座舱时我们会去试开它。
 * 规矩是项目所有者 2026-10-10 确认的（安静只算别人引起的变化；被拿着的只问、不开）。</p>
 */
public class CameraAvailabilityWatchTest {

    private static final CameraAvailabilityWatch.Cause INITIAL = CameraAvailabilityWatch.Cause.INITIAL;
    private static final CameraAvailabilityWatch.Cause SAME = CameraAvailabilityWatch.Cause.SAME;
    private static final CameraAvailabilityWatch.Cause OURS = CameraAvailabilityWatch.Cause.OURS;
    private static final CameraAvailabilityWatch.Cause OTHERS = CameraAvailabilityWatch.Cause.OTHERS;

    private static final Boolean FREE = Boolean.TRUE;
    private static final Boolean BUSY = Boolean.FALSE;
    /** 三路的相机编号：环视 2、后座舱 1、前座舱 0。 */
    private static final List<String> SLOTS = Arrays.asList("2", "1", "0");

    // ------------------------------------------------------------------ 分类

    /** 注册那一刻的回放是现状，不是变化：不论说什么、我们在不在开关它，都不挡安静。 */
    @Test
    public void theRegistrationReplayIsNotAChange() {
        assertEquals(INITIAL, CameraAvailabilityWatch.classify(true, null, true, false));
        assertEquals(INITIAL, CameraAvailabilityWatch.classify(true, null, false, false));
        assertEquals(INITIAL, CameraAvailabilityWatch.classify(true, null, false, true));
        assertEquals("回放说的和记的不一样也只是现状", INITIAL,
                CameraAvailabilityWatch.classify(true, BUSY, true, false));
    }

    /** 和我们记的一样：同一声又到了一次，或者问的时候已经照它改过 —— 不再算一次变化。 */
    @Test
    public void aRepeatOfWhatWeRecordedIsNotAChange() {
        assertEquals(SAME, CameraAvailabilityWatch.classify(false, FREE, true, false));
        assertEquals(SAME, CameraAvailabilityWatch.classify(false, BUSY, false, false));
        assertEquals(SAME, CameraAvailabilityWatch.classify(false, BUSY, false, true));
    }

    /**
     * 我们自己开、关一路时它报的那几声算我们的，不挡安静：开 1 → 「1 被占用」，关 1 → 「1 空闲」。
     * 以前连这些都算，按次序开每一路都要多等 3 秒。
     */
    @Test
    public void ourOwnOpenOrCloseDoesNotHoldUpQuiet() {
        assertEquals(OURS, CameraAvailabilityWatch.classify(false, FREE, false, true));
        assertEquals(OURS, CameraAvailabilityWatch.classify(false, BUSY, true, true));
    }

    /**
     * 我们稳稳开着时它报的「空闲」算别人的：10-10 07:22:00.090，车机拿走相机 1 的同一毫秒，相机服务报了
     * 我们正开着、正出画面的 0 和 2「空闲」—— 那是交接中的乱报，通道要等它过去 3 秒。
     */
    @Test
    public void aFreeWhileWeHoldItSteadilyIsSomeoneElses() {
        assertEquals(OTHERS, CameraAvailabilityWatch.classify(false, BUSY, true, false));
    }

    /** 我们什么都没做，它说被占用了 / 空出来了：别的程序拿、放。 */
    @Test
    public void takesAndReleasesByOthersAreOthers() {
        assertEquals(OTHERS, CameraAvailabilityWatch.classify(false, FREE, false, false));
        assertEquals(OTHERS, CameraAvailabilityWatch.classify(false, BUSY, true, false));
    }

    /**
     * 现场 (d)：10-10 09:00:18.034，「1 空闲」（被拿着的 1 放开了，我们没动它）和一句假的「2 空闲」（我们稳稳开着 2）
     * 同时到 —— 两声都是别人引起的，安静要从这一刻重新算 3 秒，满了才单独开 1。
     */
    @Test
    public void fieldD_aReleaseAndASpuriousFreeTogether() {
        assertEquals("1 放开", OTHERS, CameraAvailabilityWatch.classify(false, BUSY, true, false));
        assertEquals("2 乱报", OTHERS, CameraAvailabilityWatch.classify(false, BUSY, true, false));
    }

    /** 记的是「不在」（或者从没收到过）、注册之后又来了一声：它回来了，是一次变化。 */
    @Test
    public void aCameraComingBackIsAChange() {
        assertEquals(OTHERS, CameraAvailabilityWatch.classify(false, null, true, false));
        assertEquals(OTHERS, CameraAvailabilityWatch.classify(false, null, false, false));
        assertEquals(OURS, CameraAvailabilityWatch.classify(false, null, false, true));
    }

    // ------------------------------------------------------------------ 并快照

    /** 快照和记录一致：什么都不动。 */
    @Test
    public void anAgreeingSnapshotChangesNothing() {
        Map<String, Boolean> recorded = map("0", FREE, "1", BUSY, "2", FREE);
        assertTrue(CameraAvailabilityWatch.merge(recorded, set(), map("0", FREE, "1", BUSY, "2", FREE), SLOTS)
                .isEmpty());
    }

    /**
     * 漏收了一声：表上 1 被占用，它现在说空闲 → 照它说的改，算一次别人引起的变化（被拿着的 1 也就放开了）。
     */
    @Test
    public void aMissedWordIsTakenFromTheSnapshot() {
        List<CameraAvailabilityWatch.Correction> fixes = CameraAvailabilityWatch.merge(
                map("0", FREE, "1", BUSY, "2", FREE), set(), map("0", FREE, "1", FREE, "2", FREE), SLOTS);
        assertEquals(1, fixes.size());
        CameraAvailabilityWatch.Correction fix = fixes.get(0);
        assertEquals("1", fix.cameraId);
        assertEquals(BUSY, fix.before);
        assertFalse(fix.wasAbsent);
        assertEquals(FREE, fix.after);
        assertFalse(fix.nowAbsent());
        assertTrue(fix.changed());
        assertEquals("1: busy -> free", fix.toString());
    }

    /**
     * 冷启动、车机还拿着后座舱：回放里只有 0 和 2，没有 1 → 1 记为「不在」，调度据此直接判被拿着、一次也不试开。
     * 这是第一次记，不算变化。
     */
    @Test
    public void aSlotMissingFromTheFirstReplayIsAbsent() {
        List<CameraAvailabilityWatch.Correction> fixes = CameraAvailabilityWatch.merge(
                map("0", FREE, "2", FREE), set(), map("0", FREE, "2", FREE), SLOTS);
        assertEquals(1, fixes.size());
        CameraAvailabilityWatch.Correction fix = fixes.get(0);
        assertEquals("1", fix.cameraId);
        assertNull(fix.before);
        assertTrue(fix.nowAbsent());
        assertFalse("从没记过它：只是补上", fix.changed());
    }

    /** 回放还没并进记录时整份并：每一路都是第一次记，都不算变化；缺的那一路记为「不在」。 */
    @Test
    public void mergingTheWholeFirstReplayIsNotAChange() {
        List<CameraAvailabilityWatch.Correction> fixes = CameraAvailabilityWatch.merge(
                new HashMap<>(), set(), map("0", FREE, "2", BUSY), SLOTS);
        assertEquals(3, fixes.size());
        for (CameraAvailabilityWatch.Correction fix : fixes) {
            assertFalse(fix.toString(), fix.changed());
        }
        assertEquals("1", fixes.get(1).cameraId);
        assertTrue(fixes.get(1).nowAbsent());
        assertEquals(BUSY, fixes.get(2).after);
    }

    /** 我们记过的一路从回放里消失了（相机服务的缓存里没有它了）：记为「不在」，是一次变化。 */
    @Test
    public void aCameraThatDisappearsIsMarkedAbsent() {
        List<CameraAvailabilityWatch.Correction> fixes = CameraAvailabilityWatch.merge(
                map("0", FREE, "1", BUSY, "2", FREE), set(), map("0", FREE, "2", FREE), SLOTS);
        assertEquals(1, fixes.size());
        assertEquals("1", fixes.get(0).cameraId);
        assertTrue(fixes.get(0).nowAbsent());
        assertTrue(fixes.get(0).changed());
        assertEquals("1: busy -> absent", fixes.get(0).toString());
    }

    /** 记的「不在」、快照里还是没有：不动 —— 每 30 秒问一次，不该每次都算一次变化。 */
    @Test
    public void stillAbsentChangesNothing() {
        assertTrue(CameraAvailabilityWatch.merge(map("0", FREE, "2", FREE), set("1"),
                map("0", FREE, "2", FREE), SLOTS).isEmpty());
    }

    /** 记的「不在」、快照里它回来了：照快照改，是一次变化（车机放开后座舱）。 */
    @Test
    public void anAbsentCameraThatReappearsIsAChange() {
        List<CameraAvailabilityWatch.Correction> fixes = CameraAvailabilityWatch.merge(
                map("0", FREE, "2", FREE), set("1"), map("0", FREE, "1", FREE, "2", FREE), SLOTS);
        assertEquals(1, fixes.size());
        CameraAvailabilityWatch.Correction fix = fixes.get(0);
        assertEquals("1", fix.cameraId);
        assertTrue(fix.wasAbsent);
        assertNull(fix.before);
        assertEquals(FREE, fix.after);
        assertTrue(fix.changed());
        assertEquals("1: absent -> free", fix.toString());
    }

    /** 记录里一路既记了话又记了「不在」：以「不在」为准，记录里那句不看。 */
    @Test
    public void absentWinsOverAStaleRecord() {
        List<CameraAvailabilityWatch.Correction> fixes = CameraAvailabilityWatch.merge(
                map("1", FREE), set("1"), map("1", FREE), Collections.singletonList("1"));
        assertEquals(1, fixes.size());
        assertTrue(fixes.get(0).wasAbsent);
        assertNull(fixes.get(0).before);
    }

    /** 不是我们槽位的相机也照样并（记过的消失了记「不在」）；槽位里的 null 跳过。 */
    @Test
    public void otherCamerasAndNullSlotsAreHandled() {
        List<CameraAvailabilityWatch.Correction> fixes = CameraAvailabilityWatch.merge(
                map("3", FREE), set(), map("0", FREE), Arrays.asList("0", null));
        assertEquals(2, fixes.size());
        assertEquals("0", fixes.get(0).cameraId);
        assertFalse(fixes.get(0).changed());
        assertEquals("3", fixes.get(1).cameraId);
        assertTrue(fixes.get(1).nowAbsent());
        assertTrue(fixes.get(1).changed());
    }

    /** 要改的那几路按相机编号排好：黑匣子里一眼对得上。 */
    @Test
    public void correctionsComeInCameraOrder() {
        List<CameraAvailabilityWatch.Correction> fixes = CameraAvailabilityWatch.merge(
                map("0", BUSY, "1", BUSY, "2", BUSY), set(), map("2", FREE, "0", FREE), SLOTS);
        assertEquals(3, fixes.size());
        assertEquals("0", fixes.get(0).cameraId);
        assertEquals("1", fixes.get(1).cameraId);
        assertEquals("2", fixes.get(2).cameraId);
    }

    // ------------------------------------------------------------------ 工具

    /** 成对的 编号, 空闲? */
    private static Map<String, Boolean> map(Object... pairs) {
        Map<String, Boolean> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Boolean) pairs[i + 1]);
        }
        return out;
    }

    private static Set<String> set(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }
}
