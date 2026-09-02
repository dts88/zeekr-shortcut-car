package com.kooo.evcam.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link VersionName} 的单元测试。
 *
 * <p>这里最要紧的两条：{@code 0.9.0} 不能被判成比 {@code 0.25.0} 新
 * （按字符串比就会），以及认不出来的版本号不能冒充有更新。</p>
 */
public class VersionNameTest {

    @Test
    public void tagPrefixDoesNotCount() {
        assertTrue(VersionName.compare("v0.25.2-alpha", "0.25.2-alpha") == 0);
        assertTrue(VersionName.isNewer("v0.26.0-alpha", "0.25.2-alpha"));
    }

    /** 按字符串比会得出 0.9.0 更大 —— 那就是「有更新却说没有」。 */
    @Test
    public void twoDigitSegmentsBeatSingleDigitOnes() {
        assertTrue(VersionName.isNewer("0.25.0-alpha", "0.9.0-alpha"));
        assertFalse(VersionName.isNewer("0.9.0-alpha", "0.25.0-alpha"));
    }

    @Test
    public void sameVersionIsNotAnUpdate() {
        assertFalse(VersionName.isNewer("0.25.2-alpha", "0.25.2-alpha"));
        assertFalse(VersionName.isNewer("v0.25.2-alpha", "0.25.2-alpha"));
    }

    @Test
    public void patchAndMinorAndMajorAllCount() {
        assertTrue(VersionName.isNewer("0.25.3-alpha", "0.25.2-alpha"));
        assertTrue(VersionName.isNewer("0.26.0-alpha", "0.25.9-alpha"));
        assertTrue(VersionName.isNewer("1.0.0-alpha", "0.99.99-alpha"));
    }

    /** 段数不一样时按 0 补齐。 */
    @Test
    public void missingSegmentsCountAsZero() {
        assertTrue(VersionName.compare("0.25", "0.25.0") == 0);
        assertTrue(VersionName.isNewer("0.25.1", "0.25"));
    }

    /** 按 semver：同号的正式版比预发布版新。 */
    @Test
    public void releaseBeatsPrereleaseOfTheSameNumber() {
        assertTrue(VersionName.isNewer("0.25.2", "0.25.2-alpha"));
        assertFalse(VersionName.isNewer("0.25.2-alpha", "0.25.2"));
    }

    /**
     * 认不出来的版本号一律当作「没有更新」。
     *
     * <p>误报的代价是把人带去装一个来路不明的包，比漏报严重得多。</p>
     */
    @Test
    public void unparseableVersionsNeverLookLikeAnUpdate() {
        assertFalse(VersionName.isNewer("nightly", "0.25.2-alpha"));
        assertFalse(VersionName.isNewer("0.25.2-alpha", "nightly"));
        assertFalse(VersionName.isNewer(null, "0.25.2-alpha"));
        assertFalse(VersionName.isNewer("0.25.2-alpha", null));
        assertFalse(VersionName.isNewer("", "0.25.2-alpha"));
    }

    @Test
    public void buildMetadataIsIgnored() {
        assertTrue(VersionName.compare("0.25.2+abc", "0.25.2") == 0);
    }
}
