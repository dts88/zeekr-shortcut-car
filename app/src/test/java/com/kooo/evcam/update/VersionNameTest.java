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

    /** 检查更新只推 beta 与正式版。 */
    @Test
    public void onlyBetaAndReleaseCountAsUpdatable() {
        assertTrue(VersionName.isBetaOrRelease("v0.30.0-beta"));
        assertTrue(VersionName.isBetaOrRelease("0.30.0-beta.2"));
        assertTrue("正式版没有后缀", VersionName.isBetaOrRelease("1.0.0"));
        assertFalse("alpha 不推给在用的车机", VersionName.isBetaOrRelease("0.29.2-alpha"));
    }

    /**
     * rc 也不推。
     *
     * <p>这不是疏忽：真要发 rc，应当在代码里明确加上那一档，
     * 而不是靠「看起来比 beta 新」这种默认放行。</p>
     */
    @Test
    public void otherPrereleaseLabelsAreNotAssumedUpdatable() {
        assertFalse(VersionName.isBetaOrRelease("1.0.0-rc1"));
        assertFalse(VersionName.isBetaOrRelease("1.0.0-nightly"));
    }

    @Test
    public void unparseableVersionsBelongToNoChannel() {
        assertFalse(VersionName.isBetaOrRelease("nightly"));
        assertFalse(VersionName.isBetaOrRelease(""));
        assertFalse(VersionName.isBetaOrRelease(null));
    }

    /** 同号时 beta 比 alpha 新，正式版又比 beta 新。 */
    @Test
    public void channelOrderFollowsSemver() {
        assertTrue(VersionName.isNewer("0.30.0-beta", "0.30.0-alpha"));
        assertTrue(VersionName.isNewer("0.30.0", "0.30.0-beta"));
        assertTrue(VersionName.isNewer("0.30.0-beta", "0.29.2-alpha"));
    }

    @Test
    public void buildMetadataIsIgnored() {
        assertTrue(VersionName.compare("0.25.2+abc", "0.25.2") == 0);
    }
}
