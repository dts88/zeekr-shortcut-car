package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link LogcatNoise}：只按标签滤，格式认不出的一律留着。 */
public class LogcatNoiseTest {

    @Test
    public void readsTheTagOfATimeFormattedLine() {
        assertEquals("falco_32", LogcatNoise.tagOf(
                "09-26 10:00:57.863 D/falco_32( 9314): android.app.IActivityClientController"));
        assertEquals("SingleCamera", LogcatNoise.tagOf(
                "09-26 10:00:56.173 D/SingleCamera( 9314): Camera 2 recreateSession scheduled"));
        assertEquals("StallWatch", LogcatNoise.tagOf(
                "2026-09-26 08:16:11.624 W/StallWatch: STALL recorder 1"));
    }

    @Test
    public void dropsContainerAndCodecChatterOnly() {
        assertTrue(LogcatNoise.isNoise("09-26 10:00:57.863 D/falco_32( 9314): relayout(...)"));
        assertTrue(LogcatNoise.isNoise("09-26 08:16:02.1 I/CCodecConfig( 9314): query failed"));
        assertFalse(LogcatNoise.isNoise("09-26 08:16:02.4 D/SingleCamera( 9314): Camera 0 FPS: 30.2"));
        assertFalse(LogcatNoise.isNoise("09-26 08:16:02.4 I/MPEG4Writer( 9314): Received total/0-length"));
    }

    /** 车辆接口 SDK 的流水账（标签带进程号）按开头滤（2.11.5 第 D4 项：10-14 21:52 的卡顿现场 logcat 一段全是它）。 */
    @Test
    public void dropsTheVehicleSdkChatterByTagPrefix() {
        assertTrue(LogcatNoise.isNoise(
                "10-14 21:52:21.992 D/AdaptL_13951_ISensorImpl(13951): getSensorLatestValue function=1051392"));
        assertTrue(LogcatNoise.isNoise(
                "10-14 21:52:21.993 D/AdaptL_13951_AdaptFuncManager(13951): getCustomizeFunctionValue function=-1"));
        assertFalse(LogcatNoise.isNoise("10-14 21:52:23.259 W/StallWatch(13951): STALL recorder 2"));
        assertFalse("开头不一样的不算", LogcatNoise.isNoise("10-14 21:52:23.259 D/MyAdaptL_1(13951): x"));
    }

    @Test
    public void keepsLinesItCannotParse() {
        assertNull(LogcatNoise.tagOf("--------- beginning of main"));
        assertFalse(LogcatNoise.isNoise("--------- beginning of main"));
        assertFalse(LogcatNoise.isNoise(null));
    }

    @Test
    public void clipsVeryLongLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append('x');
        }
        String clipped = LogcatNoise.clip(sb.toString());
        assertTrue(clipped.startsWith(sb.substring(0, LogcatNoise.MAX_LINE_CHARS)));
        assertTrue(clipped.endsWith("(1000 chars)"));
        assertEquals("short", LogcatNoise.clip("short"));
    }
}
