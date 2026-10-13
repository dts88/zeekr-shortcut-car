package com.kooo.evcam.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * {@link ProfileValidation} 的单元测试。
 *
 * <p>这一关查的是「不用开相机就知道错」的配置。真正的带宽风险查不出来，
 * 那要靠实测 —— 所以这里只该报确定的错误，不该乱报。</p>
 */
public class ProfileValidationTest {

    /** 这台车的三路都声明 3840×2160，环视那一路还多一个 1280×5140。 */
    private static final ProfileValidation.Capabilities CAPS = role -> {
        if (CameraProfile.ROLE_COMPOSITE.equals(role)) {
            return new int[][]{{3840, 2160}, {1280, 5140}, {1920, 1080}};
        }
        return new int[][]{{3840, 2160}, {1920, 1080}};
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
        assertTrue(ProfileValidation.check(oneComposite("1280x5140", "1280x5140"), CAPS).isEmpty());
    }

    @Test
    public void aProfileWithNoEnabledCameraIsReported() {
        Profile profile = oneComposite("1280x5140", "1280x5140");
        profile.cameras.get(0).enabled = false;

        List<ProfileValidation.Issue> issues = ProfileValidation.check(profile, CAPS);

        assertEquals("一路都没开启，什么都不会显示也不会录", 1, issues.size());
        assertEquals(ProfileValidation.Issue.Kind.NONE_ENABLED, issues.get(0).kind);
    }

    /** 一路相机都没有，和一路都没开启是同一件事，也不能抛。 */
    @Test
    public void anEmptyProfileIsReportedAsNoneEnabled() {
        List<ProfileValidation.Issue> issues = ProfileValidation.check(new Profile(), CAPS);

        assertEquals(1, issues.size());
        assertEquals(ProfileValidation.Issue.Kind.NONE_ENABLED, issues.get(0).kind);
    }

    /** 选了这一路没声明过的尺寸 —— 开下去就是会话配置失败。 */
    @Test
    public void anUndeclaredSizeIsReported() {
        List<ProfileValidation.Issue> issues =
                ProfileValidation.check(oneComposite("1280x5140", "2560x1440"), CAPS);

        assertEquals(1, issues.size());
        ProfileValidation.Issue issue = issues.get(0);
        assertEquals(ProfileValidation.Issue.Kind.UNDECLARED_SIZE, issue.kind);
        assertEquals(ProfileValidation.Issue.Stream.RECORD, issue.stream);
        assertEquals(CameraProfile.ROLE_COMPOSITE, issue.role);
        assertEquals(2560, issue.width);
        assertEquals(1440, issue.height);
    }

    /** auto 和 max 交给下游解析，这一关不该有意见。 */
    @Test
    public void autoAndMaxAreNotSecondGuessed() {
        assertTrue(ProfileValidation.check(
                oneComposite(StreamSpec.RESOLUTION_AUTO, StreamSpec.RESOLUTION_MAX), CAPS).isEmpty());
    }

    /** 查不到设备能力时不下结论 —— 猜一个「不支持」比不查更糟。 */
    @Test
    public void unknownCapabilitiesProduceNoIssue() {
        ProfileValidation.Capabilities blind = role -> null;

        assertTrue(ProfileValidation.check(
                oneComposite("9999x9999", "9999x9999"), blind).isEmpty());
    }
}
