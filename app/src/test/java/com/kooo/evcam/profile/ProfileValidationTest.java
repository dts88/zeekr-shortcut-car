package com.kooo.evcam.profile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * {@link ProfileValidation} 的单元测试。
 *
 * <p>这一关拦的是「不用开相机就知道错」的配置。真正的带宽风险拦不住，
 * 那要靠实测 —— 所以这里只该拦确定的错误，不该乱拦。</p>
 */
public class ProfileValidationTest {

    /** 这台车的三路都声明 3840×2160，环视那一路还多一个 1280×5140。 */
    private static final ProfileValidation.Capabilities CAPS =
            new ProfileValidation.Capabilities() {
                @Override
                public int[][] declaredSizes(String role) {
                    if (CameraProfile.ROLE_COMPOSITE.equals(role)) {
                        return new int[][]{{3840, 2160}, {1280, 5140}, {1920, 1080}};
                    }
                    return new int[][]{{3840, 2160}, {1920, 1080}};
                }

                @Override
                public boolean splits(String role, int width, int height) {
                    return CameraProfile.ROLE_COMPOSITE.equals(role)
                            && (width == 1280 && height == 5140
                                || width == 3840 && height == 2160);
                }
            };

    private static Profile oneComposite(String previewSize, String recordSize) {
        Profile profile = new Profile();
        CameraProfile camera = new CameraProfile(CameraProfile.ROLE_COMPOSITE);
        camera.preview = StreamSpec.preview(previewSize);
        camera.record = StreamSpec.record(recordSize, StreamSpec.FPS_UNLIMITED,
                "medium", "auto", 3);
        camera.photo = StreamSpec.photo(StreamSpec.RESOLUTION_MAX, 95);
        profile.cameras.add(camera);
        return profile;
    }

    @Test
    public void aNormalProfilePasses() {
        List<ProfileValidation.Issue> issues =
                ProfileValidation.check(oneComposite("1280x5140", "1280x5140"), CAPS);

        assertFalse(ProfileValidation.hasBlocking(issues));
    }

    @Test
    public void anEmptyProfileIsBlocked() {
        assertTrue(ProfileValidation.hasBlocking(
                ProfileValidation.check(new Profile(), CAPS)));
    }

    @Test
    public void aProfileWithNoEnabledCameraIsBlocked() {
        Profile profile = oneComposite("1280x5140", "1280x5140");
        profile.cameras.get(0).enabled = false;

        assertTrue("一路都没启用，什么都不会显示也不会录",
                ProfileValidation.hasBlocking(ProfileValidation.check(profile, CAPS)));
    }

    /** 选了这一路没声明过的尺寸 —— 开下去就是会话配置失败。 */
    @Test
    public void anUndeclaredSizeIsBlocked() {
        assertTrue(ProfileValidation.hasBlocking(
                ProfileValidation.check(oneComposite("1280x5140", "2560x1440"), CAPS)));
    }

    /** auto 和 max 交给下游解析，这一关不该有意见。 */
    @Test
    public void autoAndMaxAreNotSecondGuessed() {
        assertFalse(ProfileValidation.hasBlocking(ProfileValidation.check(
                oneComposite(StreamSpec.RESOLUTION_AUTO, StreamSpec.RESOLUTION_MAX), CAPS)));
    }

    /**
     * 录制拆、预览不拆：提醒，但不拦。
     *
     * <p>这是合法的组合，只是有个容易忽略的后果 —— 超级后视镜从预览流取画面。</p>
     */
    @Test
    public void aSplitMismatchWarnsWithoutBlocking() {
        List<ProfileValidation.Issue> issues =
                ProfileValidation.check(oneComposite("1920x1080", "1280x5140"), CAPS);

        assertFalse("这是合法组合，不该拦", ProfileValidation.hasBlocking(issues));
        boolean warned = false;
        for (ProfileValidation.Issue issue : issues) {
            if (!issue.blocking && issue.message.contains("后视镜")) {
                warned = true;
            }
        }
        assertTrue("应当提醒后视镜取不到单独那一路", warned);
    }

    /** 查不到设备能力时不下结论 —— 猜一个「不支持」比不查更糟。 */
    @Test
    public void unknownCapabilitiesProduceNoBlockingIssue() {
        ProfileValidation.Capabilities blind = new ProfileValidation.Capabilities() {
            @Override
            public int[][] declaredSizes(String role) {
                return null;
            }

            @Override
            public boolean splits(String role, int width, int height) {
                return false;
            }
        };

        assertFalse(ProfileValidation.hasBlocking(
                ProfileValidation.check(oneComposite("9999x9999", "9999x9999"), blind)));
    }
}
