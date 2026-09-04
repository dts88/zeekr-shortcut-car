package com.kooo.evcam.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Map;

/**
 * 迁移的单元测试。
 *
 * <p>迁移漏一项的后果是<b>某个设置悄悄回到默认值</b> —— 用户下次开车才发现，
 * 而编译期一点痕迹都没有。所以每一项旧设置都在这里点名一次。</p>
 */
public class ProfileMigrationTest {

    private static ProfileMigration.Snapshot snapshot() {
        ProfileMigration.Snapshot snapshot = new ProfileMigration.Snapshot();
        snapshot.carModel = "zeekr_7x";
        snapshot.targetResolution = "default";
        snapshot.compositeSizeOverride = "";
        snapshot.recordFps = "auto";
        snapshot.bitrateLevel = "medium";
        snapshot.forceH264 = false;
        snapshot.segmentMinutes = 3;
        snapshot.enabledRecordingCameras = new HashSet<>();
        return snapshot;
    }

    // ---------- 单路合成流 ----------

    @Test
    public void theCompositePresetHasExactlyOneCamera() {
        Profile profile = ProfileMigration.migrate(snapshot());

        assertEquals(Profile.PRESET_COMPOSITE, profile.id);
        assertEquals(1, profile.cameras.size());
        assertNotNull(profile.camera(CameraProfile.ROLE_COMPOSITE));
        assertNull("单路配置里不该有座舱", profile.camera(CameraProfile.ROLE_CABIN_1));
    }

    /** 环视四格的摆位从 FourLaneContainer 提取出来：2×2 等分。 */
    @Test
    public void theCompositeGridIsExtractedAsFourEqualCells() {
        CameraProfile composite =
                ProfileMigration.migrate(snapshot()).camera(CameraProfile.ROLE_COMPOSITE);

        assertEquals(4, composite.lanes.size());
        for (int i = 0; i < 4; i++) {
            LaneLayout lane = composite.lanes.get(i);
            assertEquals("lane 序号要按顺序，前后左右靠它对应", i, lane.laneIndex);
            assertEquals(0.5f, lane.width, 1e-4f);
            assertEquals(0.5f, lane.height, 1e-4f);
        }
        // 左上、右上、左下、右下
        assertEquals(0f, composite.lanes.get(0).x, 1e-4f);
        assertEquals(0f, composite.lanes.get(0).y, 1e-4f);
        assertEquals(0.5f, composite.lanes.get(1).x, 1e-4f);
        assertEquals(0f, composite.lanes.get(1).y, 1e-4f);
        assertEquals(0f, composite.lanes.get(2).x, 1e-4f);
        assertEquals(0.5f, composite.lanes.get(2).y, 1e-4f);
        assertEquals(0.5f, composite.lanes.get(3).x, 1e-4f);
        assertEquals(0.5f, composite.lanes.get(3).y, 1e-4f);
    }

    /** 环视的尺寸不读全局「录制分辨率」——它一直由探测结果决定。 */
    @Test
    public void theCompositeIgnoresTheGlobalRecordingResolution() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.targetResolution = "1920x1080";

        CameraProfile composite =
                ProfileMigration.migrate(snapshot).camera(CameraProfile.ROLE_COMPOSITE);

        assertEquals(StreamSpec.RESOLUTION_AUTO, composite.record.resolution);
        assertEquals(StreamSpec.RESOLUTION_AUTO, composite.preview.resolution);
    }

    /** 开发者选项里覆盖过尺寸时，覆盖值跟着走。 */
    @Test
    public void theCompositeSizeOverrideCarriesOver() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.compositeSizeOverride = "3840x2160";

        CameraProfile composite =
                ProfileMigration.migrate(snapshot).camera(CameraProfile.ROLE_COMPOSITE);

        assertEquals("3840x2160", composite.record.resolution);
        assertEquals("3840x2160", composite.preview.resolution);
    }

    // ---------- 三路 ----------

    @Test
    public void theMultiPresetHasThreeCameras() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.carModel = "zeekr_7x_multi";

        Profile profile = ProfileMigration.migrate(snapshot);

        assertEquals(Profile.PRESET_COMPOSITE_MULTI, profile.id);
        assertEquals(3, profile.cameras.size());
        assertNotNull(profile.camera(CameraProfile.ROLE_CABIN_1));
        assertNotNull(profile.camera(CameraProfile.ROLE_CABIN_2));
    }

    /** 全局「录制分辨率」真正作用的地方是座舱两路。 */
    @Test
    public void theGlobalResolutionLandsOnTheCabinCameras() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.carModel = "zeekr_7x_multi";
        snapshot.targetResolution = "1920x1080";

        Profile profile = ProfileMigration.migrate(snapshot);

        assertEquals("1920x1080",
                profile.camera(CameraProfile.ROLE_CABIN_1).record.resolution);
        assertEquals("1920x1080",
                profile.camera(CameraProfile.ROLE_CABIN_2).preview.resolution);
    }

    /** 没勾选录制的那一路，迁移后是「未启用」，不是消失。 */
    @Test
    public void anUncheckedCameraBecomesDisabledNotMissing() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.carModel = "zeekr_7x_multi";
        snapshot.enabledRecordingCameras = new HashSet<>();
        snapshot.enabledRecordingCameras.add("back");   // 只勾了座舱1

        Profile profile = ProfileMigration.migrate(snapshot);

        assertTrue(profile.camera(CameraProfile.ROLE_CABIN_1).enabled);
        assertFalse(profile.camera(CameraProfile.ROLE_CABIN_2).enabled);
        assertNotNull("未启用也要留在配置里，否则改回来时没有它的参数",
                profile.camera(CameraProfile.ROLE_CABIN_2));
    }

    /** 每一路的旋转和镜像跟着搬过来。 */
    @Test
    public void rotationAndMirrorCarryOverPerCamera() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.carModel = "zeekr_7x_multi";
        snapshot.rotation = key -> "back".equals(key) ? 180 : 0;
        snapshot.mirror = key -> "left".equals(key);

        Profile profile = ProfileMigration.migrate(snapshot);

        assertEquals(180, profile.camera(CameraProfile.ROLE_CABIN_1).lanes.get(0).rotation);
        assertFalse(profile.camera(CameraProfile.ROLE_CABIN_1).lanes.get(0).mirrored);
        assertEquals(0, profile.camera(CameraProfile.ROLE_CABIN_2).lanes.get(0).rotation);
        assertTrue(profile.camera(CameraProfile.ROLE_CABIN_2).lanes.get(0).mirrored);
    }

    // ---------- 意图，不是数字 ----------

    /** 旧的「原始帧率」存的是 auto，含义是不限制。 */
    @Test
    public void nativeFrameRateBecomesUnlimitedNotANumber() {
        CameraProfile composite =
                ProfileMigration.migrate(snapshot()).camera(CameraProfile.ROLE_COMPOSITE);

        assertEquals(StreamSpec.FPS_UNLIMITED, composite.record.fps);
    }

    /** 选了具体帧率就存那个数 —— 它本来就是一个用户给的值。 */
    @Test
    public void anExplicitFrameRateIsKept() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.recordFps = "30";

        CameraProfile composite =
                ProfileMigration.migrate(snapshot).camera(CameraProfile.ROLE_COMPOSITE);

        assertEquals("30", composite.record.fps);
    }

    /** 拍照没有旧设置，直接给「最大」这个模式，不是某个具体尺寸。 */
    @Test
    public void photoDefaultsToTheMaxModeNotASize() {
        CameraProfile composite =
                ProfileMigration.migrate(snapshot()).camera(CameraProfile.ROLE_COMPOSITE);

        assertEquals(StreamSpec.RESOLUTION_MAX, composite.photo.resolution);
    }

    @Test
    public void codecAndSegmentAndBitrateCarryOver() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.forceH264 = true;
        snapshot.bitrateLevel = "high";
        snapshot.segmentMinutes = 10;

        CameraProfile composite =
                ProfileMigration.migrate(snapshot).camera(CameraProfile.ROLE_COMPOSITE);

        assertEquals("h264", composite.record.codec);
        assertEquals("high", composite.record.bitrate);
        assertEquals(10, composite.record.segmentMinutes);
    }

    // ---------- 存取往返 ----------

    /** 摊平再读回来，必须还是同一份配置。 */
    @Test
    public void aProfileSurvivesTheRoundTrip() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.carModel = "zeekr_7x_multi";
        snapshot.targetResolution = "1920x1080";
        snapshot.recordFps = "24";
        snapshot.rotation = key -> "back".equals(key) ? 90 : 0;

        Profile before = ProfileMigration.migrate(snapshot);
        Map<String, String> flat = before.toMap();
        Profile after = Profile.fromMap(flat);

        assertEquals(before.id, after.id);
        assertEquals(before.cameras.size(), after.cameras.size());
        for (CameraProfile camera : before.cameras) {
            CameraProfile copy = after.camera(camera.role);
            assertNotNull("角色 " + camera.role + " 丢了", copy);
            assertEquals(camera.enabled, copy.enabled);
            assertEquals(camera.preview.resolution, copy.preview.resolution);
            assertEquals(camera.record.resolution, copy.record.resolution);
            assertEquals(camera.record.fps, copy.record.fps);
            assertEquals(camera.record.bitrate, copy.record.bitrate);
            assertEquals(camera.record.codec, copy.record.codec);
            assertEquals(camera.record.segmentMinutes, copy.record.segmentMinutes);
            assertEquals(camera.photo.resolution, copy.photo.resolution);
            assertEquals(camera.lanes.size(), copy.lanes.size());
            for (int i = 0; i < camera.lanes.size(); i++) {
                LaneLayout a = camera.lanes.get(i);
                LaneLayout b = copy.lanes.get(i);
                assertEquals(a.laneIndex, b.laneIndex);
                assertEquals(a.x, b.x, 1e-4f);
                assertEquals(a.y, b.y, 1e-4f);
                assertEquals(a.width, b.width, 1e-4f);
                assertEquals(a.height, b.height, 1e-4f);
                assertEquals(a.rotation, b.rotation);
                assertEquals(a.mirrored, b.mirrored);
            }
        }
    }

    // ---------- 预览矫正 ----------

    /** 矫正开着时，缩放和平移跟着搬过来。 */
    @Test
    public void previewCorrectionCarriesOverWhenEnabled() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.carModel = "zeekr_7x_multi";
        snapshot.previewCorrectionEnabled = true;
        snapshot.scaleX = key -> "back".equals(key) ? 1.2f : 1f;
        snapshot.translateY = key -> "back".equals(key) ? 0.05f : 0f;

        LaneLayout lane = ProfileMigration.migrate(snapshot)
                .camera(CameraProfile.ROLE_CABIN_1).lanes.get(0);

        assertEquals(1.2f, lane.scaleX, 1e-4f);
        assertEquals(0.05f, lane.translateY, 1e-4f);
    }

    /**
     * 矫正开关关着时不能搬。
     *
     * <p>那几个值现在不生效，搬过来等于悄悄给用户开了一个他从没开过的功能。</p>
     */
    @Test
    public void previewCorrectionIsNotCarriedOverWhenDisabled() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.carModel = "zeekr_7x_multi";
        snapshot.previewCorrectionEnabled = false;
        snapshot.scaleX = key -> 1.2f;
        snapshot.translateY = key -> 0.05f;

        LaneLayout lane = ProfileMigration.migrate(snapshot)
                .camera(CameraProfile.ROLE_CABIN_1).lanes.get(0);

        assertEquals("关着的功能不能被迁移带开", 1f, lane.scaleX, 1e-4f);
        assertEquals(0f, lane.translateY, 1e-4f);
    }

    /** 缩放平移也要经得起存取往返。 */
    @Test
    public void correctionSurvivesTheRoundTrip() {
        LaneLayout before = LaneLayout.cell(2, 0f, 0.5f, 0.5f, 0.5f);
        before.scaleX = 1.25f;
        before.scaleY = 0.8f;
        before.translateX = -0.1f;
        before.cropTop = 0.02f;

        LaneLayout after = LaneLayout.fromMap(before.toMap());

        assertEquals(1.25f, after.scaleX, 1e-4f);
        assertEquals(0.8f, after.scaleY, 1e-4f);
        assertEquals(-0.1f, after.translateX, 1e-4f);
        assertEquals(0.02f, after.cropTop, 1e-4f);
    }

    /** 没设过矫正时，缩放是 1、平移是 0 —— 不能是 0 缩放，那是一格黑。 */
    @Test
    public void anUntouchedLaneHasNeutralCorrection() {
        LaneLayout lane = LaneLayout.fromMap(new java.util.LinkedHashMap<>());

        assertEquals(1f, lane.scaleX, 1e-4f);
        assertEquals(1f, lane.scaleY, 1e-4f);
        assertEquals(0f, lane.translateX, 1e-4f);
    }

    /** 空的存储读出来是一份空配置，不能抛。 */
    @Test
    public void anEmptyStoreYieldsAnEmptyProfile() {
        Profile profile = Profile.fromMap(new java.util.LinkedHashMap<>());
        assertTrue(profile.cameras.isEmpty());
    }
}
