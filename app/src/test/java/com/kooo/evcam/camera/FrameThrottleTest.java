package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link FrameThrottle} 的单元测试。
 *
 * <p>核心那一条是 {@link #source29TargetTwentyFiveMustNotHalve()} ——
 * 它复现的正是「设置里选 25、录出来 15」这个实际现象。</p>
 */
public class FrameThrottleTest {

    /** 喂一段等间隔的帧，返回实际渲染出的帧率。 */
    private static float run(int targetFps, float sourceFps, int seconds) {
        FrameThrottle throttle = new FrameThrottle(1_000_000_000L / targetFps);
        long stepNs = (long) (1_000_000_000L / sourceFps);
        int frames = (int) (sourceFps * seconds);
        int rendered = 0;
        long now = 0;
        for (int i = 0; i < frames; i++) {
            if (throttle.shouldRender(now)) {
                rendered++;
            }
            now += stepNs;
        }
        return rendered / (float) seconds;
    }

    /**
     * 相机 29fps、目标 25fps —— 不能掉到 15。
     *
     * <p>旧写法在这里正好每两帧渲一帧，得到 14.5fps。整帧丢弃拿不到 25，
     * 那么 29 和 14.5 之间应当选 29：用户选 25 是想要「大约这么流畅」，
     * 不是「不许超过 25」。</p>
     */
    @Test
    public void source29TargetTwentyFiveMustNotHalve() {
        float actual = run(25, 29f, 10);
        assertTrue("29fps 的源、目标 25，结果不该掉到一半，实际 " + actual, actual > 20f);
    }

    /** 源和目标一致时，一帧都不该丢。 */
    @Test
    public void matchingRatesRenderEveryFrame() {
        assertEquals(30f, run(30, 30f, 10), 0.6f);
    }

    /** 目标高于源时也不该丢帧 —— 丢了也变不快。 */
    @Test
    public void targetAboveSourceRendersEverything() {
        assertEquals(29f, run(30, 29f, 10), 0.6f);
    }

    /** 目标是源的一半时，就该老老实实隔帧。 */
    @Test
    public void halfRateActuallyHalves() {
        assertEquals(15f, run(15, 30f, 10), 1.0f);
    }

    /** 目标远低于源时按比例降下来。 */
    @Test
    public void muchLowerTargetThrottlesProportionally() {
        float actual = run(10, 30f, 10);
        assertTrue("目标 10、源 30，应当在 10 附近，实际 " + actual,
                actual > 8f && actual < 12f);
    }

    /** 间隔 <= 0 表示不限速。 */
    @Test
    public void zeroIntervalMeansNoThrottling() {
        FrameThrottle throttle = new FrameThrottle(0);
        for (int i = 0; i < 100; i++) {
            assertTrue(throttle.shouldRender(i * 1_000_000L));
        }
    }

    /** 第一帧永远放行，否则录制开头会白等一个间隔。 */
    @Test
    public void firstFrameAlwaysPasses() {
        assertTrue(new FrameThrottle(1_000_000_000L).shouldRender(0L));
    }
}
