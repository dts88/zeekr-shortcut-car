package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link StorageBudget} 的单元测试。
 *
 * <p>这几个数会直接印在界面上给人做决定，所以它们和 U 盘上印的 GB 必须是
 * 同一个进制 —— 用 1024 会凭空少报 7%，而那正是「说了 18 小时、实际 19 小时」
 * 这类不信任的来源。</p>
 */
public class StorageBudgetTest {

    @Test
    public void oneStreamAddsUp() {
        // 10 Mbps = 1.25 MB/s = 4500 MB/小时
        assertEquals(4_500_000_000L, StorageBudget.bytesPerHour(10_000_000));
        assertEquals(4.5f, StorageBudget.gigabytesPerHour(
                StorageBudget.bytesPerHour(10_000_000)), 0.01f);
    }

    @Test
    public void streamsAddTogether() {
        long three = StorageBudget.bytesPerHour(10_000_000, 4_000_000, 2_000_000);
        assertEquals(StorageBudget.bytesPerHour(16_000_000), three);
    }

    /** 负数和 0 不参与，不能让一路算不出来就把总数拉低。 */
    @Test
    public void junkRatesAreIgnored() {
        assertEquals(StorageBudget.bytesPerHour(10_000_000),
                StorageBudget.bytesPerHour(10_000_000, 0, -5));
        assertEquals(0L, StorageBudget.bytesPerHour());
        assertEquals(0L, StorageBudget.bytesPerHour((int[]) null));
    }

    /** GB 按 1000 进制：128GB 的盘按 7.1 GB/小时，是 18 小时。 */
    @Test
    public void hoursMatchWhatIsPrintedOnTheStick() {
        long perHour = StorageBudget.bytesPerHour(15_800_000);   // ≈ 7.1 GB/小时
        assertEquals(7.1f, StorageBudget.gigabytesPerHour(perHour), 0.05f);
        assertEquals(18f, StorageBudget.hours(128_000_000_000L, perHour), 0.3f);
    }

    /** 算不出来就返回 0，不要抛，也不要给一个假的数。 */
    @Test
    public void nothingToDivideByGivesZero() {
        assertEquals(0f, StorageBudget.hours(0, 1000), 0.001f);
        assertEquals(0f, StorageBudget.hours(1000, 0), 0.001f);
        assertEquals(0f, StorageBudget.hours(-1, -1), 0.001f);
    }
}
