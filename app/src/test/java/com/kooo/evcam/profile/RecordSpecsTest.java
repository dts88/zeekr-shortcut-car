package com.kooo.evcam.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link RecordSpecs} 的单元测试。
 *
 * <p>这几条规则原来长在 AppConfig 里，读的是设置里的全局键。搬到配置之后取值口换了，
 * <b>取值规则一个字都不该变</b> —— 变了就是「升级之后录出来的东西不一样了」。
 * 所以这里钉的是和旧实现逐条对齐的行为。</p>
 */
public class RecordSpecsTest {

    private static final int HARDWARE_MAX = 30;

    /** 「原始帧率」是不限制，不是某个具体的数。 */
    @Test
    public void unlimitedMeansNoCap() {
        assertEquals(RecordSpecs.FPS_UNLIMITED,
                RecordSpecs.cap(StreamSpec.FPS_UNLIMITED, HARDWARE_MAX));
        assertEquals(RecordSpecs.FPS_UNLIMITED, RecordSpecs.cap(null, HARDWARE_MAX));
    }

    /** 但编码器要一个具体的数，那就是硬件能给到多少。 */
    @Test
    public void unlimitedStillHasANominalRate() {
        assertEquals(HARDWARE_MAX, RecordSpecs.nominal(StreamSpec.FPS_UNLIMITED, HARDWARE_MAX));
        assertEquals(5, RecordSpecs.nominal(StreamSpec.FPS_UNLIMITED, 0));
    }

    /** 选了具体帧率时，两个数是同一个。 */
    @Test
    public void anExplicitRateIsBothCapAndNominal() {
        assertEquals(15, RecordSpecs.cap("15", HARDWARE_MAX));
        assertEquals(15, RecordSpecs.nominal("15", HARDWARE_MAX));
    }

    /** 要得比硬件能给的多，只能得到硬件能给的。 */
    @Test
    public void anExplicitRateIsClampedToTheHardware() {
        assertEquals(25, RecordSpecs.cap("60", 25));
        assertEquals(5, RecordSpecs.cap("1", 25));
    }

    /** 解不开的值按硬件上限走，不能返回 0 —— 0 会让编码器按最低码率工作。 */
    @Test
    public void anUnparsableRateFallsBackToTheHardware() {
        assertEquals(HARDWARE_MAX, RecordSpecs.nominal("每秒很多帧", HARDWARE_MAX));
        assertEquals(HARDWARE_MAX, RecordSpecs.cap("每秒很多帧", HARDWARE_MAX));
    }

    /** 码率等级对应的画质档，和旧的 getEncoderQualityLevel 一致。 */
    @Test
    public void bitrateLevelsKeepTheirQualityStep() {
        assertEquals(1, RecordSpecs.qualityLevel("low"));
        assertEquals(2, RecordSpecs.qualityLevel("medium"));
        assertEquals(3, RecordSpecs.qualityLevel("high"));
        assertEquals("auto 与 medium 同档", 2, RecordSpecs.qualityLevel(StreamSpec.BITRATE_AUTO));
        assertEquals("认不出来的值也得给一档", 2, RecordSpecs.qualityLevel("moderate"));
    }

    /** 只有明确选了 h264 才强制，auto 交给编码器。 */
    @Test
    public void onlyH264IsForced() {
        assertTrue(RecordSpecs.forceH264("h264"));
        assertFalse(RecordSpecs.forceH264("auto"));
        assertFalse(RecordSpecs.forceH264("hevc"));
        assertFalse(RecordSpecs.forceH264(null));
    }

    /** 分段 0 不是「不分段」：断电时那一段就什么都不剩了。 */
    @Test
    public void segmentZeroFallsBackInsteadOfNeverClosingTheFile() {
        assertEquals(3 * 60_000L, RecordSpecs.segmentMs(3));
        assertEquals(RecordSpecs.DEFAULT_SEGMENT_MINUTES * 60_000L, RecordSpecs.segmentMs(0));
        assertEquals(RecordSpecs.DEFAULT_SEGMENT_MINUTES * 60_000L, RecordSpecs.segmentMs(-5));
    }

    /** 兜底的那一份必须是能直接拿去录的，不能是一堆零。 */
    @Test
    public void theFallbackSpecIsUsable() {
        StreamSpec spec = RecordSpecs.defaults();
        assertEquals(StreamSpec.FPS_UNLIMITED, spec.fps);
        assertEquals(2, RecordSpecs.qualityLevel(spec.bitrate));
        assertTrue(spec.segmentMinutes > 0);
    }
}
