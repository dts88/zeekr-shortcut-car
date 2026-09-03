package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link TargetBitrate} 的单元测试。
 *
 * <p>这段公式现在有两个使用方：编码器拿它配置码率，设置界面拿它显示「目标 X Mbps」。
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

    /** 再大的画面也不会超过编码器扛得住的上限。 */
    @Test
    public void neverExceedsTheCeiling() {
        assertEquals(TargetBitrate.MAX_H264,
                TargetBitrate.compute(3, 3840, 2160, 30, false));
        assertEquals(TargetBitrate.MAX_HEVC,
                TargetBitrate.compute(3, 3840, 2160, 30, true));
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
