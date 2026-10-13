package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link WatermarkText} 的单元测试。
 *
 * <p>录像和照片各画各的，但字必须是同一套 —— 这里钉住的就是那一套。</p>
 */
public class WatermarkTextTest {

    /** 没填车牌号时就是应用名 + 版本。 */
    @Test
    public void withoutAPlateItIsJustNameAndVersion() {
        assertEquals("极氪即刻 v0.36.2", WatermarkText.brandLine("极氪即刻", "0.36.2", ""));
        assertEquals("极氪即刻 v0.36.2", WatermarkText.brandLine("极氪即刻", "0.36.2", null));
    }

    /** 填了就跟在后面。 */
    @Test
    public void aPlateIsAppended() {
        String line = WatermarkText.brandLine("极氪即刻", "0.36.2", "A12345");
        assertTrue("车牌号应当出现在这一行里：" + line, line.contains("A12345"));
        assertTrue("应用名不能被挤掉：" + line, line.startsWith("极氪即刻"));
    }

    /** 读不到版本号也不能留下一个孤零零的 v。 */
    @Test
    public void anUnknownVersionLeavesNoStrayV() {
        assertEquals("极氪即刻", WatermarkText.brandLine("极氪即刻", "", ""));
        assertEquals("极氪即刻", WatermarkText.brandLine("极氪即刻", null, ""));
    }

    /** 照片规格行只有尺寸；尺寸不合法时什么都不写。 */
    @Test
    public void photoSpecIsSizeOnly() {
        assertEquals("2560x2560", WatermarkText.photoSpecLine(2560, 2560));
        assertEquals("", WatermarkText.photoSpecLine(0, 1080));
        assertEquals("", WatermarkText.photoSpecLine(-1, -1));
    }

    /** 时间水印关着：照片上只剩应用名那一行 —— 开关不管它。 */
    @Test
    public void withTheTimestampOffOnlyTheBrandLineRemains() {
        assertEquals(Collections.singletonList("极氪即刻 v2.1.1"),
                WatermarkText.photoLines("极氪即刻 v2.1.1", "2026-10-04 12:00:00",
                        2560, 2560, false));
    }

    /** 时间水印开着：应用名、时间、尺寸，按这个顺序。 */
    @Test
    public void withTheTimestampOnItIsBrandTimeThenSize() {
        assertEquals(Arrays.asList("极氪即刻 v2.1.1", "2026-10-04 12:00:00", "2560x2560"),
                WatermarkText.photoLines("极氪即刻 v2.1.1", "2026-10-04 12:00:00",
                        2560, 2560, true));
    }

    /** 车牌号跟着应用名走，时间水印关了也还在。 */
    @Test
    public void thePlateSurvivesWithTheTimestampOff() {
        List<String> lines = WatermarkText.photoLines(
                WatermarkText.brandLine("极氪即刻", "0.36.2", "A12345"),
                "2026-10-04 12:00:00", 2560, 2560, false);
        assertEquals(1, lines.size());
        assertTrue("车牌号应当还在：" + lines, lines.get(0).contains("A12345"));
    }

    /** 空的部分不留空行：尺寸不合法就没有尺寸行，应用名为空就没有那一行。 */
    @Test
    public void emptyPartsAreSkipped() {
        assertEquals(Arrays.asList("极氪即刻 v2.1.1", "2026-10-04 12:00:00"),
                WatermarkText.photoLines("极氪即刻 v2.1.1", "2026-10-04 12:00:00",
                        0, 1080, true));
        assertEquals(Arrays.asList("2026-10-04 12:00:00", "2560x2560"),
                WatermarkText.photoLines("", "2026-10-04 12:00:00", 2560, 2560, true));
        assertTrue(WatermarkText.photoLines(null, "2026-10-04 12:00:00",
                2560, 2560, false).isEmpty());
    }
}
