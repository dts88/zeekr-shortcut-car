package com.kooo.evcam.blackbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * 只记变化的小账本（2026-10-12，找黑屏信号只加记录）：没变不出行，变了出「旧 → 新」，
 * 来回跳的只记前几次、其余只数，窗口过了补一段停在哪儿 —— 黑匣子不能被一个来回跳的号刷爆。
 */
public class ChangeTrackerTest {

    private static final long WINDOW = 60_000L;

    @Test
    public void onlyRealChangesComeOut() {
        ChangeTracker t = new ChangeTracker(8, WINDOW);
        t.seed("gear", "P");
        assertNull("和起点一样：不出", t.change("gear", "P", 0));
        assertEquals("P → D", t.change("gear", "D", 10));
        assertNull("又读到一样的：不出", t.change("gear", "D", 20));
        assertEquals("D → P", t.change("gear", "P", 30));
        assertEquals("P", t.value("gear"));
    }

    @Test
    public void unseenValuesAreQuestionMarks() {
        ChangeTracker t = new ChangeTracker(8, WINDOW);
        assertEquals("没起点的项第一次出现", "? → ON", t.change("0", "ON", 0));
        assertEquals("ON → ?", t.change("0", null, 1));
        assertNull("一直没数据：不出", t.change("0", null, 2));
        assertNull(t.value("missing"));
    }

    @Test
    public void keysAreIndependent() {
        ChangeTracker t = new ChangeTracker(1, WINDOW);
        t.seed("a", "0");
        t.seed("b", "0");
        assertEquals("0 → 1", t.change("a", "1", 0));
        assertNull("a 这个窗口用完了", t.change("a", "0", 1));
        assertEquals("b 有自己的份", "0 → 1", t.change("b", "1", 2));
    }

    /** 超量的只数；下一段从最后记下的值说起，带上中间没记的次数。 */
    @Test
    public void flappingIsCappedAndCounted() {
        ChangeTracker t = new ChangeTracker(2, WINDOW);
        t.seed("hud", "0");
        assertEquals("0 → 1", t.change("hud", "1", 0));
        assertEquals("1 → 0", t.change("hud", "0", 100));
        assertNull(t.change("hud", "1", 200));
        assertNull(t.change("hud", "0", 300));
        assertNull(t.change("hud", "1", 400));
        assertEquals("1", t.value("hud"));
        assertEquals("窗口过了：从最后记下的 0 说起", "0 → 0 (+3 skipped)", t.change("hud", "0", WINDOW + 1));
        assertEquals("新窗口的第二段", "0 → 1", t.change("hud", "1", WINDOW + 2));
        assertNull("新窗口又用完了", t.change("hud", "0", WINDOW + 3));
    }

    /** 来回跳完停住了：不等下一次变化，窗口过了就补一段停在哪儿。 */
    @Test
    public void drainReportsWhereItSettled() {
        ChangeTracker t = new ChangeTracker(1, WINDOW);
        t.seed("power_mode_state", "255");
        t.seed("gear", "P");
        assertEquals("255 → 1", t.change("power_mode_state", "1", 0));
        assertNull(t.change("power_mode_state", "2", 10));
        assertNull(t.change("power_mode_state", "3", 20));
        assertTrue("窗口没过：不补", t.drain(WINDOW - 1).isEmpty());
        Map<String, String> drained = t.drain(WINDOW);
        assertEquals(1, drained.size());
        assertEquals("1 → 3 (+2 skipped)", drained.get("power_mode_state"));
        assertTrue("补过了：不再补", t.drain(2 * WINDOW + 1).isEmpty());
        assertNull("补的那一段算进了新窗口", t.change("power_mode_state", "4", WINDOW + 5));
        assertEquals("3 → 4 (+1 skipped)", t.drain(2 * WINDOW).get("power_mode_state"));
    }

    /** 什么时候该补：压下过的项里窗口最早过完的那一刻；用的人掐着它补，不等下一次变化。 */
    @Test
    public void nextDrainAtIsWhenTheEarliestWindowEnds() {
        ChangeTracker t = new ChangeTracker(1, WINDOW);
        t.seed("a", "0");
        t.seed("b", "0");
        assertEquals("没压下过：不用补", -1, t.nextDrainAt());
        assertEquals("0 → 1", t.change("a", "1", 100));
        assertEquals("记下了、没压下：不用补", -1, t.nextDrainAt());
        assertEquals("0 → 1", t.change("b", "1", 50));
        assertNull(t.change("a", "0", 200));
        assertEquals("从 a 这个窗口开始那一刻算", 100 + WINDOW, t.nextDrainAt());
        assertNull(t.change("b", "0", 300));
        assertEquals("b 的窗口开始得早，先到", 50 + WINDOW, t.nextDrainAt());
        assertTrue("还没到：不补", t.drain(50 + WINDOW - 1).isEmpty());
        assertEquals("1 → 0 (+1 skipped)", t.drain(50 + WINDOW).get("b"));
        assertEquals("补完 b 剩 a", 100 + WINDOW, t.nextDrainAt());
        assertEquals("1 → 0 (+1 skipped)", t.drain(100 + WINDOW).get("a"));
        assertEquals(-1, t.nextDrainAt());
    }

    /** 扔掉的项（屏被拿掉了）：压下的也不补，再出现是新的一项。 */
    @Test
    public void forgottenKeysStartOver() {
        ChangeTracker t = new ChangeTracker(1, WINDOW);
        t.seed("5", "ON");
        assertEquals("ON → OFF", t.change("5", "OFF", 0));
        assertNull(t.change("5", "ON", 1));
        assertEquals(WINDOW, t.nextDrainAt());
        t.forget("5");
        assertNull(t.value("5"));
        assertEquals(-1, t.nextDrainAt());
        assertTrue(t.drain(10 * WINDOW).isEmpty());
        assertEquals("? → ON", t.change("5", "ON", 2));
        t.forget("never-seen");
    }

    @Test
    public void arrowFormat() {
        assertEquals("ON → OFF", ChangeTracker.arrow("ON", "OFF", 0));
        assertEquals("? → ? (+5 skipped)", ChangeTracker.arrow(null, null, 5));
    }
}
