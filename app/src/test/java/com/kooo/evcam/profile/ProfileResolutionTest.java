package com.kooo.evcam.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ProfileResolution} 的单元测试。
 *
 * <p>这是配置里的「意图」变成一个具体数字的唯一入口。翻错了的后果是相机开在
 * 一个没人要求过的尺寸上，而配置界面还显示着原来那个意图。</p>
 */
public class ProfileResolutionTest {

    /** auto：有探测结果就用探测结果 —— 合成流那一路就是靠这条走的。 */
    @Test
    public void autoFollowsTheProbedSize() {
        ProfileResolution.Size size = ProfileResolution.resolve(
                StreamSpec.RESOLUTION_AUTO, new int[]{1280, 5140}, new int[]{3840, 2160});

        assertTrue(size.specified());
        assertEquals(1280, size.width);
        assertEquals(5140, size.height);
    }

    /**
     * auto 且没有探测结果：不指定。
     *
     * <p>座舱两路走的是这一条 —— 不指定就等于交回给 chooseOptimalSize，
     * 和配置化之前的行为一模一样。</p>
     */
    @Test
    public void autoWithoutAProbeLeavesItUnspecified() {
        ProfileResolution.Size size = ProfileResolution.resolve(
                StreamSpec.RESOLUTION_AUTO, null, new int[]{3840, 2160});

        assertFalse("不指定，不能擅自替用户挑一个", size.specified());
    }

    /** max：用声明的最大尺寸，拍照默认走这条。 */
    @Test
    public void maxUsesTheDeclaredMaximum() {
        ProfileResolution.Size size = ProfileResolution.resolve(
                StreamSpec.RESOLUTION_MAX, new int[]{1280, 5140}, new int[]{3840, 2160});

        assertEquals(3840, size.width);
        assertEquals(2160, size.height);
    }

    /** max 但不知道最大是多少：不指定，不能拿探测结果冒充。 */
    @Test
    public void maxWithoutADeclaredMaximumLeavesItUnspecified() {
        ProfileResolution.Size size = ProfileResolution.resolve(
                StreamSpec.RESOLUTION_MAX, new int[]{1280, 5140}, null);

        assertFalse(size.specified());
    }

    /** 具体值原样用，探测结果不参与。 */
    @Test
    public void anExplicitSizeWins() {
        ProfileResolution.Size size = ProfileResolution.resolve(
                "3840x2160", new int[]{1280, 5140}, new int[]{1920, 1080});

        assertEquals(3840, size.width);
        assertEquals(2160, size.height);
    }

    /** 认不出来的值不能变成 0×0 去开相机。 */
    @Test
    public void nonsenseFallsBackToUnspecified() {
        assertFalse(ProfileResolution.resolve("很大", null, null).specified());
        assertFalse(ProfileResolution.resolve("1280x", null, null).specified());
        assertFalse(ProfileResolution.resolve("x5140", null, null).specified());
        assertFalse(ProfileResolution.resolve("0x0", null, null).specified());
        assertFalse(ProfileResolution.resolve(null, null, null).specified());
        assertFalse(ProfileResolution.resolve("", null, null).specified());
    }

    @Test
    public void parseReadsTheUsualForm() {
        int[] parsed = ProfileResolution.parse("1280x5140");
        assertEquals(1280, parsed[0]);
        assertEquals(5140, parsed[1]);
        assertNull(ProfileResolution.parse("1280"));
        assertNull(ProfileResolution.parse(null));
    }
}
