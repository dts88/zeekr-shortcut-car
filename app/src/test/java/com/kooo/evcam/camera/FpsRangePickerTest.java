package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link FpsRangePicker} 的单元测试。
 *
 * <p>要钉住的是「优先挑固定区间」：可变区间等于把帧率交给自动曝光决定，
 * 而这正是「选了 30、录出来 15」的成因。</p>
 */
public class FpsRangePickerTest {

    /** 固定区间优先 —— AE 没有滑动余地，才谈得上「选 30 就是 30」。 */
    @Test
    public void prefersAFixedRange() {
        int[][] ranges = {{15, 30}, {30, 30}, {15, 15}};
        FpsRangePicker.Choice choice = FpsRangePicker.pick(ranges, 30);
        assertEquals(new FpsRangePicker.Choice(30, 30), choice);
        assertTrue(choice.isFixed());
    }

    /** 没有固定区间时，挑下限最高的那个，把可滑动的空间压到最小。 */
    @Test
    public void otherwiseTakesTheHighestLowerBound() {
        int[][] ranges = {{7, 30}, {15, 30}, {24, 30}};
        assertEquals(new FpsRangePicker.Choice(24, 30), FpsRangePicker.pick(ranges, 30));
    }

    /** 这台车机声明的就是 15–30：没有固定区间，只能用它，但至少是明确请求过的。 */
    @Test
    public void thisHeadUnitOnlyDeclaresOneVariableRange() {
        int[][] ranges = {{15, 30}};
        FpsRangePicker.Choice choice = FpsRangePicker.pick(ranges, 30);
        assertEquals(new FpsRangePicker.Choice(15, 30), choice);
        assertTrue("可变区间意味着 AE 仍可能下滑，这一点要能看出来", !choice.isFixed());
    }

    /** 目标帧率不在任何区间里时，取上限最接近的那个。 */
    @Test
    public void fallsBackToTheNearestUpperBound() {
        int[][] ranges = {{10, 15}, {20, 24}};
        assertEquals(new FpsRangePicker.Choice(20, 24), FpsRangePicker.pick(ranges, 30));
    }

    @Test
    public void lowerTargetPicksItsOwnFixedRange() {
        int[][] ranges = {{15, 15}, {15, 30}, {30, 30}};
        assertEquals(new FpsRangePicker.Choice(15, 15), FpsRangePicker.pick(ranges, 15));
    }

    /**
     * 没有声明就不要设。
     *
     * <p>硬塞一个相机没声明的区间，会让整个会话配置失败 ——
     * 那是从「帧率不对」变成「根本没有画面」。</p>
     */
    @Test
    public void noDeclaredRangesMeansDoNotSetAnything() {
        assertNull(FpsRangePicker.pick(null, 30));
        assertNull(FpsRangePicker.pick(new int[0][], 30));
    }

    @Test
    public void invalidTargetIsIgnored() {
        assertNull(FpsRangePicker.pick(new int[][]{{15, 30}}, 0));
    }

    /** 顺序写反的区间也要认得。 */
    @Test
    public void toleratesReversedBounds() {
        assertEquals(new FpsRangePicker.Choice(15, 30),
                FpsRangePicker.pick(new int[][]{{30, 15}}, 30));
    }
}
