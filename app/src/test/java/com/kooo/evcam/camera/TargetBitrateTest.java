package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link TargetBitrate} 的单元测试。
 *
 * <p>这段公式现在有两个使用方：编码器拿它配置码率，配置编辑拿它显示「目标 X Mbps」。
 * 钉住它，是为了「界面写的数」和「实际配下去的数」永远是同一个。</p>
 */
public class TargetBitrateTest {

    /** 帧率 0 不能算出 0 码率 —— 「原始帧率」传下来的就可能是 0。 */
    @Test
    public void zeroFrameRateFallsBackToTheFloor() {
        assertEquals(TargetBitrate.MIN_H264,
                TargetBitrate.compute(2, 1920, 1080, 0, false));
        assertEquals(TargetBitrate.MIN_HEVC,
                TargetBitrate.compute(2, 1920, 1080, 0, true));
    }

    /** 画质档越高，码率越高。 */
    @Test
    public void higherQualityMeansHigherBitrate() {
        int low = TargetBitrate.compute(0, 1280, 720, 30, false);
        int medium = TargetBitrate.compute(2, 1280, 720, 30, false);
        assertTrue("高画质应当高于低画质，实际 " + low + " / " + medium, medium > low);
    }

    /** 同画质下 HEVC 要的码率更低。 */
    @Test
    public void hevcNeedsLessThanH264() {
        int h264 = TargetBitrate.compute(2, 1280, 720, 30, false);
        int hevc = TargetBitrate.compute(2, 1280, 720, 30, true);
        assertTrue("HEVC 应当低于 H.264，实际 " + hevc + " / " + h264, hevc < h264);
    }

    /** 再大的画面也不会超过编码器扛得住的上限，两种编码同一条。 */
    @Test
    public void neverExceedsTheCeiling() {
        assertEquals(TargetBitrate.MAX,
                TargetBitrate.compute(3, 3840, 2160, 30, false));
        assertEquals(TargetBitrate.MAX,
                TargetBitrate.compute(3, 7680, 4320, 30, true));
    }

    /**
     * 环视四宫格上四档落在哪。
     *
     * <p>这四个数就是界面上要写给用户看的数，也是这次调整的全部目的：
     * 每档翻一倍，「中」比 0.43 的「中」高一倍。写进测试，下次谁改公式都得
     * 先面对这张表。</p>
     */
    @Test
    public void theFourTiersLandOnTheirTargets() {
        int width = 2560;
        int height = 2570;
        assertEquals(2_700_000, TargetBitrate.compute(0, width, height, 25, true));
        assertEquals(5_400_000, TargetBitrate.compute(1, width, height, 25, true));
        assertEquals(10_000_000, TargetBitrate.compute(2, width, height, 25, true));
        assertEquals(20_000_000, TargetBitrate.compute(3, width, height, 25, true));
    }

    /** 每一档都比上一档高，四档都不重合。 */
    @Test
    public void everyTierIsAStepUp() {
        int previous = 0;
        for (int level = 0; level <= 3; level++) {
            int bitrate = TargetBitrate.compute(level, 2560, 2570, 25, true);
            assertTrue("第 " + level + " 档 " + bitrate + " 没有高于上一档 " + previous,
                    bitrate > previous);
            previous = bitrate;
        }
    }

    /** 结果取整到 100Kbps，界面和日志才好读。 */
    @Test
    public void roundedToHundredKbps() {
        assertEquals(0, TargetBitrate.compute(2, 1280, 720, 30, false) % 100_000);
    }

    /** 显示文字：1Mbps 以上用 Mbps，以下用 Kbps。 */
    @Test
    public void formatPicksAReadableUnit() {
        assertEquals("8.0 Mbps", TargetBitrate.format(8_000_000));
        assertEquals("800 Kbps", TargetBitrate.format(800_000));
    }
}
