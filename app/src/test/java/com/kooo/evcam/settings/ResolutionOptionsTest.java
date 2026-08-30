package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * {@link ResolutionOptions} 的单元测试。
 *
 * <p>选错的表现是「录出来的分辨率和设置里写的不一样」——
 * 肉眼很难发现，所以规则必须在这里钉死。</p>
 */
public class ResolutionOptionsTest {

    private List<int[]> sizes(int... pairs) {
        List<int[]> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(new int[]{pairs[i], pairs[i + 1]});
        }
        return list;
    }

    /**
     * 只留每一路都支持的：某一路独有的尺寸如果列出来，选中后那几路会悄悄退回
     * 最接近的一个，界面写的和实际录的就对不上了。
     */
    @Test
    public void onlyResolutionsEveryCameraSupportsSurvive() {
        List<String> common = ResolutionOptions.common(Arrays.asList(
                sizes(1920, 1080, 1280, 720, 640, 480),
                sizes(1280, 720, 640, 480)));
        assertFalse("只有第一路支持的不该出现", common.contains("1920x1080"));
        assertTrue(common.contains("1280x720"));
        assertTrue(common.contains("640x480"));
        assertEquals(2, common.size());
    }

    @Test
    public void aSingleCameraKeepsAllOfItsSizes() {
        List<String> common = ResolutionOptions.common(Arrays.asList(
                sizes(1920, 1080, 1280, 720)));
        assertEquals(2, common.size());
    }

    /** 合成条带排除掉：环视那一路固定用探测到的尺寸，这个设置管不到它。 */
    @Test
    public void compositeStripSizesAreExcluded() {
        List<String> common = ResolutionOptions.common(Arrays.asList(
                sizes(1280, 5140, 1920, 1080, 1280, 720)));
        assertFalse("1280x5140 是合成条带，不该出现", common.contains("1280x5140"));
        assertTrue(common.contains("1920x1080"));
    }

    /** 从大到小排，用户一眼看到的是最高的那个。 */
    @Test
    public void sortedByPixelCountDescending() {
        List<String> common = ResolutionOptions.common(Arrays.asList(
                sizes(640, 480, 1920, 1080, 1280, 720)));
        assertEquals(Arrays.asList("1920x1080", "1280x720", "640x480"), common);
    }

    @Test
    public void noOverlapYieldsNothing() {
        List<String> common = ResolutionOptions.common(Arrays.asList(
                sizes(1920, 1080),
                sizes(1280, 720)));
        assertTrue(common.isEmpty());
    }

    @Test
    public void nonsenseInputIsSurvivable() {
        assertTrue(ResolutionOptions.common(null).isEmpty());
        assertTrue(ResolutionOptions.common(new ArrayList<>()).isEmpty());
        List<List<int[]>> withNulls = new ArrayList<>();
        withNulls.add(null);
        assertTrue(ResolutionOptions.common(withNulls).isEmpty());
    }
}
