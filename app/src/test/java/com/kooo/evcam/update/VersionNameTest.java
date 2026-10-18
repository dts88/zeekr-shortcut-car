package com.kooo.evcam.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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

    /** 关了「接收 Beta 版」：只有正式版算，beta 和 alpha 都不算。 */
    @Test
    public void onlyReleasesCountWhenBetaIsOff() {
        assertTrue(VersionName.isRelease("1.0.0"));
        assertTrue(VersionName.isRelease("v0.60.1"));
        assertFalse(VersionName.isRelease("0.60.0-beta"));
        assertFalse(VersionName.isRelease("0.60.0-alpha"));
        assertFalse(VersionName.isRelease("nightly"));
        assertFalse(VersionName.isRelease(null));
    }

    @Test public void numberedPrereleasesUseNumericOrdering() {
        assertTrue(VersionName.isNewer("v2.11.42-beta.10", "2.11.42-beta.2"));
        assertTrue(VersionName.isNewer("2.11.42-beta.1", "2.11.42-alpha.99"));
        assertTrue(VersionName.isNewer("2.11.42", "2.11.42-beta.10"));
        assertFalse(VersionName.isNewer("2.11.42-beta.2", "2.11.42-beta.10"));
    }


    @Test public void prereleaseFlagAndTagMustAgree() {
        for (UpdateChannel channel : UpdateChannel.values()) {
            assertTrue(VersionName.isEligible("v2.11.44", false, channel));
            assertFalse(VersionName.isEligible("v2.11.44", true, channel));
            assertFalse(VersionName.isEligible("v2.11.44-alpha.1", false, channel));
            assertFalse(VersionName.isEligible("v2.11.44-beta.1", false, channel));
        }
    }

    @Test public void channelsIncludeOnlyTheirOwnAndMoreStableReleases() {
        assertTrue(VersionName.isEligible("v2.11.44", false, UpdateChannel.STABLE));
        assertFalse(VersionName.isEligible("v2.11.44-beta.1", true, UpdateChannel.STABLE));
        assertFalse(VersionName.isEligible("v2.11.44-alpha.1", true, UpdateChannel.STABLE));
        assertTrue(VersionName.isEligible("v2.11.44", false, UpdateChannel.BETA));
        assertTrue(VersionName.isEligible("v2.11.44-beta.1", true, UpdateChannel.BETA));
        assertFalse(VersionName.isEligible("v2.11.44-alpha.1", true, UpdateChannel.BETA));
        assertTrue(VersionName.isEligible("v2.11.44", false, UpdateChannel.ALPHA));
        assertTrue(VersionName.isEligible("v2.11.44-beta.1", true, UpdateChannel.ALPHA));
        assertTrue(VersionName.isEligible("v2.11.44-alpha.1", true, UpdateChannel.ALPHA));
    }

    @Test public void unknownChannelSettingsFallBackToStable() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromValue(null));
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromValue("nightly"));
        assertEquals(UpdateChannel.ALPHA, UpdateChannel.fromValue("alpha"));
        assertTrue(VersionName.isEligible("v2.11.44", false, null));
        assertFalse(VersionName.isEligible("v2.11.44-alpha.1", true, null));
    }

    @Test public void enablingAlphaDoesNotMakeOlderAlphaAnUpgradeFromBeta() {
        assertTrue(VersionName.isEligible("v2.11.43-alpha.99", true, UpdateChannel.ALPHA));
        assertFalse(VersionName.isNewer("v2.11.43-alpha.99", "2.11.43-beta.1"));
        assertTrue(VersionName.isNewer("v2.11.44-alpha.1", "2.11.43-beta.1"));
        assertTrue(VersionName.isNewer("v2.11.44-alpha.10", "2.11.44-alpha.2"));
    }

    @Test public void malformedAndUnknownChannelsNeverBecomeUpdates() {
        for (String value : new String[]{null, "", "nightly", "v2.11.42-rc.1", "v2.11.42-betamax",
                "v2.11.42-betafoo", "2.11.42-beta.01", "2.11.42-beta.", "2.11.42-",
                "02.11.42", "2.11.42+", "2.11.42/alpha", "999999999999999.0.0"}) {
            assertFalse(String.valueOf(value), VersionName.isEligible(value, true, UpdateChannel.ALPHA));
            assertFalse(String.valueOf(value), VersionName.isEligible(value, false, UpdateChannel.ALPHA));
        }
    }
}
