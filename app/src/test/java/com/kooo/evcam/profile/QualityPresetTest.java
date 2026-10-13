package com.kooo.evcam.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * {@link QualityPreset} 的单元测试。
 *
 * <p>钉两件事：存下去的那几个词不能改（它们写进了用户的配置），以及
 * 「我选了均衡，但后座舱不是」这件事一定认得出来 —— 界面上那个「自定义」
 * 角标全靠它。</p>
 */
public class QualityPresetTest {

    private static Profile profileWith(String... fpsAndBitratePairs) {
        Profile profile = new Profile();
        for (int i = 0; i < fpsAndBitratePairs.length; i += 2) {
            CameraProfile camera = new CameraProfile("cam" + i);
            camera.record = StreamSpec.record(StreamSpec.RESOLUTION_AUTO,
                    fpsAndBitratePairs[i], fpsAndBitratePairs[i + 1], "auto", 1);
            profile.cameras.add(camera);
        }
        return profile;
    }

    /** 存下去的字符串是契约，写死在测试里。 */
    @Test
    public void theStoredKeysAreFixed() {
        assertEquals("space", QualityPreset.SAVE_SPACE.key);
        assertEquals("balanced", QualityPreset.BALANCED.key);
        assertEquals("sharp", QualityPreset.SHARPEST.key);
    }

    @Test
    public void unknownKeysFallBackToBalanced() {
        assertEquals(QualityPreset.BALANCED, QualityPreset.fromKey(null));
        assertEquals(QualityPreset.BALANCED, QualityPreset.fromKey("whatever"));
        for (QualityPreset preset : QualityPreset.values()) {
            assertEquals(preset, QualityPreset.fromKey(preset.key));
        }
    }

    /** 三档必须真的不一样，否则界面上摆三张卡是骗人的。 */
    @Test
    public void theThreeStepsDiffer() {
        assertFalse(QualityPreset.SAVE_SPACE.bitrate.equals(QualityPreset.BALANCED.bitrate));
        assertFalse(QualityPreset.BALANCED.bitrate.equals(QualityPreset.SHARPEST.bitrate));
        assertFalse(QualityPreset.SAVE_SPACE.fps.equals(QualityPreset.BALANCED.fps));
        assertFalse(QualityPreset.BALANCED.fps.equals(QualityPreset.SHARPEST.fps));
    }

    /** 三档的帧率是项目拥有者定的，写死在测试里。 */
    @Test
    public void theFrameRatesAreFixed() {
        assertEquals("10", QualityPreset.SAVE_SPACE.fps);
        assertEquals("20", QualityPreset.BALANCED.fps);
        assertEquals(StreamSpec.FPS_UNLIMITED, QualityPreset.SHARPEST.fps);
    }

    /**
     * 选了一档、再单独改一路：选的那一档不变，改过的那一路对不上它。
     *
     * <p>「自定义」标记和哪张卡亮着全靠这两件事，存取往返之后也得成立。
     * 以前选的是哪一档是从各路的参数倒推的，一路改过就推不出来了。</p>
     */
    @Test
    public void thePickSurvivesTuningAndTheRoundTrip() {
        Profile profile = profileWith(
                QualityPreset.BALANCED.fps, StreamSpec.BITRATE_MEDIUM,
                QualityPreset.BALANCED.fps, StreamSpec.BITRATE_MEDIUM);
        QualityPreset.SHARPEST.applyTo(profile);
        profile.cameras.get(1).record.bitrate = StreamSpec.BITRATE_LOW;

        Profile after = Profile.fromMap(profile.toMap());

        assertEquals(QualityPreset.SHARPEST, after.quality);
        assertTrue(after.quality.matches(after.cameras.get(0).record));
        assertFalse("改过的那一路要标出来", after.quality.matches(after.cameras.get(1).record));
    }

    /** 早先存下的配置里没有这个键：按开着的几路里对得上最多的那一档算。 */
    @Test
    public void anOldProfileInfersTheStepMostCamerasStillMatch() {
        Profile profile = profileWith(
                QualityPreset.SHARPEST.fps, StreamSpec.BITRATE_HIGH,
                QualityPreset.SHARPEST.fps, StreamSpec.BITRATE_HIGH,
                "24", StreamSpec.BITRATE_LOW);
        Map<String, String> flat = profile.toMap();
        flat.remove("quality");

        assertEquals("单独改过一路，其余几路还是当初选的那一档",
                QualityPreset.SHARPEST, Profile.fromMap(flat).quality);
    }

    /** 关着的那几路不算：后来补上的是默认值，不代表当初选了什么。 */
    @Test
    public void camerasThatAreOffDoNotCount() {
        Profile profile = profileWith(
                QualityPreset.SHARPEST.fps, StreamSpec.BITRATE_HIGH,
                QualityPreset.BALANCED.fps, StreamSpec.BITRATE_MEDIUM,
                QualityPreset.BALANCED.fps, StreamSpec.BITRATE_MEDIUM);
        profile.cameras.get(1).enabled = false;
        profile.cameras.get(2).enabled = false;

        assertEquals(QualityPreset.SHARPEST, QualityPreset.inferredFrom(profile));
    }

    /** 推不出来（没有相机、或者哪一档都对不上）时算「均衡」，和认不出来的词一样。 */
    @Test
    public void nothingToGoOnMeansBalanced() {
        assertEquals(QualityPreset.BALANCED, QualityPreset.inferredFrom(new Profile()));
        assertEquals(QualityPreset.BALANCED, QualityPreset.inferredFrom(
                profileWith("24", StreamSpec.BITRATE_VERY_LOW)));
    }

    /** 选一档写下去，每一路都跟着走，并记下选的是它；其余参数不动。 */
    @Test
    public void applyingWritesEveryCameraAndLeavesTheRest() {
        Profile profile = profileWith("10", StreamSpec.BITRATE_VERY_LOW,
                "30", StreamSpec.BITRATE_HIGH);
        profile.cameras.get(0).record.segmentMinutes = 5;
        profile.cameras.get(0).record.codec = "h264";

        QualityPreset.SHARPEST.applyTo(profile);

        assertEquals(QualityPreset.SHARPEST, profile.quality);
        for (CameraProfile camera : profile.cameras) {
            assertTrue(QualityPreset.SHARPEST.matches(camera.record));
        }
        assertEquals("分段不该被档位动", 5, profile.cameras.get(0).record.segmentMinutes);
        assertEquals("编码不该被档位动", "h264", profile.cameras.get(0).record.codec);
        assertEquals("分辨率不该被档位动",
                StreamSpec.RESOLUTION_AUTO, profile.cameras.get(0).record.resolution);
    }

    /** 单独一路也认得出对不对得上，「自定义」角标靠它。 */
    @Test
    public void oneCameraKnowsWhetherItMatches() {
        StreamSpec record = StreamSpec.record(StreamSpec.RESOLUTION_AUTO,
                QualityPreset.BALANCED.fps, StreamSpec.BITRATE_MEDIUM, "auto", 1);
        assertTrue(QualityPreset.BALANCED.matches(record));
        assertFalse(QualityPreset.SHARPEST.matches(record));
        assertFalse(QualityPreset.BALANCED.matches(null));
    }
}
