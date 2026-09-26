package com.kooo.evcam.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * 迁移的单元测试。
 *
 * <p>漏一项的后果是<b>某个值悄悄变成别的</b> —— 用户下次开车才发现，
 * 而编译期一点痕迹都没有。所以每一路、每一条流的初值都在这里点名一次。</p>
 */
public class ProfileMigrationTest {

    private static ProfileMigration.Snapshot snapshot() {
        ProfileMigration.Snapshot snapshot = new ProfileMigration.Snapshot();
        snapshot.carModel = "zeekr_7x";
        return snapshot;
    }

    // ---------- 单路合成流 ----------

    /**
     * 三路都在，只有环视开着。
     *
     * <p>以前这份配置里只有一路，于是编辑器要多一个「加一路相机」的步骤。
     * 这台车就那三路，“加”不是一件真实发生的事，开和关才是。</p>
     */
    @Test
    public void theCompositePresetCarriesAllThreeWithOnlySurroundOn() {
        Profile profile = ProfileMigration.migrate(snapshot());

        assertEquals(Profile.PRESET_COMPOSITE, profile.id);
        assertEquals(3, profile.cameras.size());
        assertTrue(profile.camera(CameraProfile.ROLE_COMPOSITE).enabled);
        assertFalse("单路配置里座舱默认关着",
                profile.camera(CameraProfile.ROLE_CABIN_1).enabled);
        assertFalse(profile.camera(CameraProfile.ROLE_CABIN_2).enabled);
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

    /** 环视的尺寸初值是「跟随探测」，不是某个写死的数。 */
    @Test
    public void theCompositeSizeStartsAsAuto() {
        CameraProfile composite =
                ProfileMigration.migrate(snapshot()).camera(CameraProfile.ROLE_COMPOSITE);

        assertEquals(StreamSpec.RESOLUTION_AUTO, composite.record.resolution);
        assertEquals(StreamSpec.RESOLUTION_AUTO, composite.preview.resolution);
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

    /** 座舱两路的尺寸也是「跟随探测」，要钉死去配置编辑里钉。 */
    @Test
    public void theCabinSizesStartAsAuto() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.carModel = "zeekr_7x_multi";

        Profile profile = ProfileMigration.migrate(snapshot);

        assertEquals(StreamSpec.RESOLUTION_AUTO,
                profile.camera(CameraProfile.ROLE_CABIN_1).record.resolution);
        assertEquals(StreamSpec.RESOLUTION_AUTO,
                profile.camera(CameraProfile.ROLE_CABIN_2).preview.resolution);
    }

    /** 新建的三路配置里，三路都是启用的 —— 要关某一路在配置编辑里关。 */
    @Test
    public void everyCameraStartsEnabled() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.carModel = "zeekr_7x_multi";

        Profile profile = ProfileMigration.migrate(snapshot);

        for (CameraProfile camera : profile.cameras) {
            assertTrue(camera.role + " 应当是启用的", camera.enabled);
        }
    }


    /**
     * 座舱两路在两份极氪预设里都默认镜像，环视四格不镜像。
     *
     * <p>「环视合成流」那份里两路是关着的，但之后在配置编辑里打开时也得是镜像的，
     * 所以关着的也要带上。</p>
     */
    @Test
    public void cabinsStartMirroredInBothZeekrPresets() {
        for (String model : new String[]{"zeekr_7x", "zeekr_7x_multi"}) {
            ProfileMigration.Snapshot snapshot = snapshot();
            snapshot.carModel = model;

            Profile profile = ProfileMigration.migrate(snapshot);

            assertTrue(model, profile.camera(CameraProfile.ROLE_CABIN_1).lanes.get(0).mirrored);
            assertTrue(model, profile.camera(CameraProfile.ROLE_CABIN_2).lanes.get(0).mirrored);
            for (LaneLayout lane : profile.camera(CameraProfile.ROLE_COMPOSITE).lanes) {
                assertFalse(model, lane.mirrored);
            }
        }
    }


    // ---------- 意图，不是数字 ----------

    /**
     * 新建的配置正好是「均衡」那一档。
     *
     * <p>初值必须落在某一档上 —— 否则刚建的配置一打开，三张卡一张都没选中，
     * 每一路还都标着「已细调」。</p>
     */
    @Test
    public void aNewProfileIsExactlyTheBalancedStep() {
        Profile profile = ProfileMigration.migrate(snapshot());

        assertEquals(QualityPreset.BALANCED, QualityPreset.of(profile));
    }

    /** 拍照没有旧设置，直接给「最大」这个模式，不是某个具体尺寸。 */
    @Test
    public void photoDefaultsToTheMaxModeNotASize() {
        CameraProfile composite =
                ProfileMigration.migrate(snapshot()).camera(CameraProfile.ROLE_COMPOSITE);

        assertEquals(StreamSpec.RESOLUTION_MAX, composite.photo.resolution);
    }

    /**
     * 新建的配置必须是能直接拿去录的。
     *
     * <p>这几项以前是设置里的全局键，现在只存在于配置里。默认值得和当时一致，
     * 否则「什么都没改过」的车升上来，录出来的东西就变了。</p>
     */
    @Test
    public void theRecordDefaultsAreReadyToUse() {
        CameraProfile composite =
                ProfileMigration.migrate(snapshot()).camera(CameraProfile.ROLE_COMPOSITE);

        assertEquals("auto", composite.record.codec);
        assertEquals("medium", composite.record.bitrate);
        assertEquals(RecordSpecs.DEFAULT_SEGMENT_MINUTES, composite.record.segmentMinutes);
    }

    // ---------- 存取往返 ----------

    /** 摊平再读回来，必须还是同一份配置。 */
    @Test
    public void aProfileSurvivesTheRoundTrip() {
        ProfileMigration.Snapshot snapshot = snapshot();
        snapshot.carModel = "zeekr_7x_multi";

        Profile before = ProfileMigration.migrate(snapshot);
        // 往返要拿真值试，全是默认值的话，丢了哪一项都看不出来
        CameraProfile edited = before.camera(CameraProfile.ROLE_CABIN_1);
        edited.lanes.get(0).rotation = 90;
        edited.enabled = false;
        edited.record.resolution = "1920x1080";
        edited.record.fps = "24";
        edited.record.bitrate = "high";
        edited.record.codec = "h264";
        edited.record.segmentMinutes = 10;
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

    // ---------- 缩放 / 平移 ----------


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
