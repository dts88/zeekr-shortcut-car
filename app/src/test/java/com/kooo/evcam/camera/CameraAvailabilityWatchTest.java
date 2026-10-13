package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 相机服务的每一声算谁引起的，问来的快照怎么并进我们的记录，记录里的最后一句、「不在」、上一次别人引起的变化怎么记。
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

    // ------------------------------------------------------------------ 记录（相机服务说过的话、不在、别人引起的变化）

    /** 没收全过回放之前，什么都不算「不在」：判不出来就别判（相机服务从没说过话时，丢失按普通故障救）。 */
    @Test
    public void nothingIsAbsentBeforeAReplay() {
        CameraAvailabilityWatch.Ledger ledger = new CameraAvailabilityWatch.Ledger();
        CameraAvailabilityWatch.Word word = ledger.word("1");
        assertNull(word.free);
        assertFalse(word.absent);
        assertFalse(word.ours);
        assertEquals(0, word.at);
        assertEquals(0, ledger.lastOthersChangeAt());
    }

    /**
     * 注册那一刻的回放照记，不算变化：冷启动时车机还拿着相机 1，回放里它是「被占用」、不是我们 —— 调度据此直接判被拿着，
     * 但不因此等 3 秒。回放里「被占用」的那一路那一刻是我们拿着的，记成我们的。
     */
    @Test
    public void theRegistrationReplayIsRecordedButIsNotAChange() {
        CameraAvailabilityWatch.Ledger ledger = new CameraAvailabilityWatch.Ledger();
        assertEquals(INITIAL, ledger.heard("1", false, true, false, false, 100));
        assertEquals(INITIAL, ledger.heard("2", false, true, false, true, 100));
        assertEquals(INITIAL, ledger.heard("0", true, true, false, false, 100));
        assertEquals(BUSY, ledger.word("1").free);
        assertFalse("别的程序拿着", ledger.word("1").ours);
        assertTrue("那一刻是我们拿着", ledger.word("2").ours);
        assertEquals(FREE, ledger.word("0").free);
        assertEquals(100, ledger.word("0").at);
        assertEquals(0, ledger.lastOthersChangeAt());
    }

    /**
     * 冷启动、车机拿着后座舱：注册回放里只有 0 和 2。回放收全时管线可能还没建好、不知道槽位 —— 之后才问到的 1 照样是
     * 「不在」（从没听相机服务说过它），从第一次收全回放算起。知道槽位的话当场记下。两种都是第一次记，不挡安静。
     */
    @Test
    public void aSlotNeverHeardAfterTheReplayIsAbsent() {
        CameraAvailabilityWatch.Ledger early = coldStartWithoutCabinRear(new ArrayList<String>());
        assertTrue(early.word("1").absent);
        assertNull(early.word("1").free);
        assertEquals(120, early.word("1").at);
        assertFalse(early.word("0").absent);
        assertEquals(0, early.lastOthersChangeAt());

        CameraAvailabilityWatch.Ledger known = coldStartWithoutCabinRear(SLOTS);
        assertTrue(known.word("1").absent);
        assertEquals(120, known.word("1").at);
        assertEquals(0, known.lastOthersChangeAt());
    }

    /**
     * 「不在」的那一路一开口就不再是不在：车机放开后座舱时那句「1 空闲」。被拿着的那一路要等被拿着以后的一句「空闲」
     * 才放开，这一句同时把「不在」清掉。它回来了，是别人引起的变化。
     */
    @Test
    public void aWordForAnAbsentCameraClearsTheMark() {
        CameraAvailabilityWatch.Ledger ledger = coldStartWithoutCabinRear(new ArrayList<String>());
        assertEquals(OTHERS, ledger.heard("1", true, false, false, false, 5_000));
        CameraAvailabilityWatch.Word word = ledger.word("1");
        assertFalse(word.absent);
        assertEquals(FREE, word.free);
        assertFalse(word.ours);
        assertEquals(5_000, word.at);
        assertEquals(5_000, ledger.lastOthersChangeAt());
    }

    /** 我们自己开、关一路时它报的那几声记下来、算我们的，不动「上一次别人引起的变化」。 */
    @Test
    public void ourOwnOpenAndCloseAreRecordedAsOurs() {
        CameraAvailabilityWatch.Ledger ledger = coldStartWithoutCabinRear(SLOTS);
        assertEquals(OURS, ledger.heard("2", false, false, true, true, 1_000));
        assertEquals(BUSY, ledger.word("2").free);
        assertTrue(ledger.word("2").ours);
        assertEquals(OURS, ledger.heard("2", true, false, true, false, 2_000));
        assertEquals(FREE, ledger.word("2").free);
        assertEquals(2_000, ledger.word("2").at);
        assertEquals(0, ledger.lastOthersChangeAt());
    }

    /**
     * 我们稳稳开着 2 时它报「2 空闲」（10-10 08:56:57）：算别人引起的变化，记成最后一句。之后我们关 2，相机服务不再说话
     * （AOSP 对「空闲 → 空闲」不回调）—— 最后一句还是那句「空闲」，不管多早说的（大纲 §7 解读 1）。同一声再到一次不算。
     */
    @Test
    public void aSpuriousFreeIsOthersAndStaysTheLastWord() {
        CameraAvailabilityWatch.Ledger ledger = coldStartWithoutCabinRear(SLOTS);
        ledger.heard("2", false, false, true, true, 1_000);
        assertEquals(OTHERS, ledger.heard("2", true, false, false, true, 3_000));
        assertEquals(3_000, ledger.lastOthersChangeAt());
        assertEquals(SAME, ledger.heard("2", true, false, false, true, 9_000));
        CameraAvailabilityWatch.Word word = ledger.word("2");
        assertEquals(FREE, word.free);
        assertFalse(word.ours);
        assertEquals("重复的那一声不改时刻", 3_000, word.at);
        assertEquals(3_000, ledger.lastOthersChangeAt());
    }

    /** 问来的回放和记录一致：什么都不动，时刻也不动 —— 每 30 秒问一次，不该每次都挡一次安静。 */
    @Test
    public void anAskThatAgreesChangesNothing() {
        CameraAvailabilityWatch.Ledger ledger = coldStartWithoutCabinRear(SLOTS);
        ledger.heard("2", false, false, true, true, 1_000);
        assertTrue(ledger.replayed(map("0", FREE, "2", BUSY), SLOTS, 31_000).isEmpty());
        assertEquals(1_000, ledger.word("2").at);
        assertTrue(ledger.word("1").absent);
        assertEquals(120, ledger.word("1").at);
        assertEquals(0, ledger.lastOthersChangeAt());
    }

    /** 漏收了一声：表上 1 被占用，问来的回放说空闲 → 照它改，时刻是改的那一刻，不是我们的，算别人引起的变化。 */
    @Test
    public void aMissedWordFromAnAskIsAnOthersChange() {
        CameraAvailabilityWatch.Ledger ledger = new CameraAvailabilityWatch.Ledger();
        ledger.heard("0", true, true, false, false, 100);
        ledger.heard("1", false, true, false, false, 100);
        ledger.heard("2", true, true, false, false, 100);
        ledger.replayed(map("0", FREE, "1", BUSY, "2", FREE), SLOTS, 120);
        List<CameraAvailabilityWatch.Correction> fixes =
                ledger.replayed(map("0", FREE, "1", FREE, "2", FREE), SLOTS, 30_000);
        assertEquals(1, fixes.size());
        assertEquals("1: busy -> free", fixes.get(0).toString());
        CameraAvailabilityWatch.Word word = ledger.word("1");
        assertEquals(FREE, word.free);
        assertFalse(word.ours);
        assertEquals(30_000, word.at);
        assertEquals(30_000, ledger.lastOthersChangeAt());
    }

    /**
     * 10-10：车机拿走后座舱时相机服务一句话都不说，相机 1 从缓存里消失了。问的时候回放里没有它 → 记为「不在」，
     * 算一次变化；再问还是没有，不再算；它再开口（「1 空闲」）就回来了。
     */
    @Test
    public void aCameraThatVanishesIsAbsentUntilItSpeaksAgain() {
        CameraAvailabilityWatch.Ledger ledger = new CameraAvailabilityWatch.Ledger();
        ledger.heard("0", true, true, false, false, 100);
        ledger.heard("1", true, true, false, false, 100);
        ledger.heard("2", true, true, false, false, 100);
        ledger.replayed(map("0", FREE, "1", FREE, "2", FREE), SLOTS, 120);
        ledger.heard("1", false, false, true, true, 1_000);

        List<CameraAvailabilityWatch.Correction> fixes = ledger.replayed(map("0", FREE, "2", FREE), SLOTS, 31_000);
        assertEquals(1, fixes.size());
        assertEquals("1: busy -> absent", fixes.get(0).toString());
        assertTrue(ledger.word("1").absent);
        assertNull(ledger.word("1").free);
        assertEquals(31_000, ledger.word("1").at);
        assertEquals(31_000, ledger.lastOthersChangeAt());

        assertTrue(ledger.replayed(map("0", FREE, "2", FREE), SLOTS, 61_000).isEmpty());
        assertEquals(31_000, ledger.word("1").at);
        assertEquals(31_000, ledger.lastOthersChangeAt());

        assertEquals(OTHERS, ledger.heard("1", true, false, false, false, 600_000));
        assertFalse(ledger.word("1").absent);
        assertEquals(FREE, ledger.word("1").free);
        assertEquals(600_000, ledger.lastOthersChangeAt());
    }

    /**
     * 记的「不在」、问来的回放里它回来了：照它改、算变化。回放收全时还不知道槽位、只是「从没开过口」的那一路也一样 ——
     * 它已经算不在了，这次出现就是回来了。
     */
    @Test
    public void anAbsentCameraBackInAnAskIsAChange() {
        CameraAvailabilityWatch.Ledger known = coldStartWithoutCabinRear(SLOTS);
        List<CameraAvailabilityWatch.Correction> fixes =
                known.replayed(map("0", FREE, "1", FREE, "2", FREE), SLOTS, 40_000);
        assertEquals(1, fixes.size());
        assertEquals("1: absent -> free", fixes.get(0).toString());
        assertFalse(known.word("1").absent);
        assertEquals(FREE, known.word("1").free);
        assertEquals(40_000, known.lastOthersChangeAt());

        CameraAvailabilityWatch.Ledger early = coldStartWithoutCabinRear(new ArrayList<String>());
        fixes = early.replayed(map("0", FREE, "1", FREE, "2", FREE), SLOTS, 40_000);
        assertEquals(1, fixes.size());
        assertEquals("1: absent -> free", fixes.get(0).toString());
        assertTrue(fixes.get(0).changed());
        assertFalse(early.word("1").absent);
        assertEquals(40_000, early.lastOthersChangeAt());
    }

    /**
     * 一声都没有的回放不算数：那是没连上相机服务（进程里的缓存是空的），不是每一路都不在了。照它记的话，每一路都被当成
     * 被拿着、再也不开。收全过回放之前、之后都一样。
     */
    @Test
    public void anEmptyReplayChangesNothing() {
        CameraAvailabilityWatch.Ledger fresh = new CameraAvailabilityWatch.Ledger();
        assertTrue(fresh.replayed(new HashMap<String, Boolean>(), SLOTS, 100).isEmpty());
        assertFalse("没收全过回放：还判不出不在", fresh.word("1").absent);

        CameraAvailabilityWatch.Ledger ledger = new CameraAvailabilityWatch.Ledger();
        ledger.heard("0", true, true, false, false, 100);
        ledger.heard("1", false, true, false, false, 100);
        ledger.heard("2", true, true, false, false, 100);
        ledger.replayed(map("0", FREE, "1", BUSY, "2", FREE), SLOTS, 120);
        assertTrue(ledger.replayed(new HashMap<String, Boolean>(), SLOTS, 30_000).isEmpty());
        assertEquals(BUSY, ledger.word("1").free);
        assertFalse(ledger.word("1").absent);
        assertEquals(FREE, ledger.word("0").free);
        assertEquals(0, ledger.lastOthersChangeAt());
    }

    // ------------------------------------------------------------------ 工具

    /** 冷启动、车机拿着后座舱：注册回放（100 ms）只有 0 和 2，120 ms 收全；{@code slots} 是那时知道的槽位。 */
    private static CameraAvailabilityWatch.Ledger coldStartWithoutCabinRear(List<String> slots) {
        CameraAvailabilityWatch.Ledger ledger = new CameraAvailabilityWatch.Ledger();
        ledger.heard("0", true, true, false, false, 100);
        ledger.heard("2", true, true, false, false, 100);
        List<CameraAvailabilityWatch.Correction> fixes = ledger.replayed(map("0", FREE, "2", FREE), slots, 120);
        for (CameraAvailabilityWatch.Correction fix : fixes) {
            assertTrue(fix.toString(), fix.nowAbsent());
            assertFalse("第一次记，不算变化", fix.changed());
        }
        return ledger;
    }

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
