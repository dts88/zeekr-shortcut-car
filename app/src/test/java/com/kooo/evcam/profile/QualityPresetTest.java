package com.kooo.evcam.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link QualityPreset} 的单元测试。
 *
 * <p>钉两件事：存下去的那几个词不能改（它们写进了用户的配置），以及
 * 「我选了均衡，但后座舱不是」这件事一定认得出来 —— 界面上那个「已细调」
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
    }

    /** 每一路都对得上才算这一档。 */
    @Test
    public void aProfileMatchesOnlyWhenEveryCameraDoes() {
        Profile balanced = profileWith(
                StreamSpec.FPS_UNLIMITED, StreamSpec.BITRATE_MEDIUM,
                StreamSpec.FPS_UNLIMITED, StreamSpec.BITRATE_MEDIUM);
        assertEquals(QualityPreset.BALANCED, QualityPreset.of(balanced));

        Profile mixed = profileWith(
                StreamSpec.FPS_UNLIMITED, StreamSpec.BITRATE_MEDIUM,
                "15", StreamSpec.BITRATE_LOW);
        assertNull("一路细调过就不该算整体是哪一档", QualityPreset.of(mixed));
    }

    /** 空配置没有档。 */
    @Test
    public void anEmptyProfileHasNoStep() {
        assertNull(QualityPreset.of(new Profile()));
        assertNull(QualityPreset.of(null));
    }

    /** 选一档写下去，每一路都跟着走；其余参数不动。 */
    @Test
    public void applyingWritesEveryCameraAndLeavesTheRest() {
        Profile profile = profileWith("10", StreamSpec.BITRATE_VERY_LOW,
                "30", StreamSpec.BITRATE_HIGH);
        profile.cameras.get(0).record.segmentMinutes = 5;
        profile.cameras.get(0).record.codec = "h264";

        QualityPreset.SHARPEST.applyTo(profile);

        assertEquals(QualityPreset.SHARPEST, QualityPreset.of(profile));
        assertEquals("分段不该被档位动", 5, profile.cameras.get(0).record.segmentMinutes);
        assertEquals("编码不该被档位动", "h264", profile.cameras.get(0).record.codec);
        assertEquals("分辨率不该被档位动",
                StreamSpec.RESOLUTION_AUTO, profile.cameras.get(0).record.resolution);
    }

    /** 单独一路也认得出对不对得上，「已细调」角标靠它。 */
    @Test
    public void oneCameraKnowsWhetherItMatches() {
        StreamSpec record = StreamSpec.record(StreamSpec.RESOLUTION_AUTO,
                StreamSpec.FPS_UNLIMITED, StreamSpec.BITRATE_MEDIUM, "auto", 1);
        assertTrue(QualityPreset.BALANCED.matches(record));
        assertFalse(QualityPreset.SHARPEST.matches(record));
        assertFalse(QualityPreset.BALANCED.matches(null));
    }
}
