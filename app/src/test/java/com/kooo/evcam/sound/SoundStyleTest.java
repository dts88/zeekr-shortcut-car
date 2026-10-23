package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.AppConfig;

import org.junit.Test;

/**
 * 声音风格：设置里存的值认得回来，认不出的按默认；每种风格（车外、车内两种听感）都出得了声、不超过 −3 dBFS；
 * 只有「试制 V8」启动时带起动机。
 */
public class SoundStyleTest {

    @Test
    public void storedKeysMapBackAndUnknownOnesFallBackToTheDefault() {
        for (SoundStyle s : SoundStyle.values()) {
            assertEquals(s, SoundStyle.of(s.key));
        }
        assertEquals(SoundStyle.AMERICAN, SoundStyle.of(AppConfig.DRIVE_SOUND_STYLE_AMERICAN));
        assertEquals(SoundStyle.PROTOTYPE, SoundStyle.of(AppConfig.DRIVE_SOUND_STYLE_PROTOTYPE));
        assertEquals(SoundStyle.JET, SoundStyle.of(AppConfig.DRIVE_SOUND_STYLE_JET));
        assertEquals(SoundStyle.JET_OLD, SoundStyle.of(AppConfig.DRIVE_SOUND_STYLE_JET_OLD));
        assertEquals(SoundStyle.AMERICAN, SoundStyle.of(null));
        assertEquals(SoundStyle.AMERICAN, SoundStyle.of("v12"));
    }

    @Test
    public void onlyThePrototypeCranks() {
        assertTrue(SoundStyle.PROTOTYPE.cranks());
        assertFalse(SoundStyle.AMERICAN.cranks());
        assertFalse(SoundStyle.JET.cranks());
        assertFalse(SoundStyle.JET_OLD.cranks());
    }

    @Test
    public void eachStyleOnlyUsesTheSettingsThatFitIt() {
        // 项目所有者 2026-10-23：不同的模式，可以有各自适配的设置选项。「听感」只有美式 V8 分车外、车内；两种超音速不看「变速箱」，
        // 固定 8 速（参考车的 e-Shift 模拟 8 速双离合），「连续」时也不走伪挡位
        assertTrue(SoundStyle.AMERICAN.hasViews());
        assertFalse(SoundStyle.PROTOTYPE.hasViews());
        assertFalse(SoundStyle.JET.hasViews());
        assertFalse(SoundStyle.JET_OLD.hasViews());
        for (SoundStyle s : new SoundStyle[] {SoundStyle.AMERICAN, SoundStyle.PROTOTYPE}) {
            assertTrue(s.choosesGearbox());
            assertEquals(Gearbox.MANUAL6, s.gearbox(Gearbox.MANUAL6));
            assertEquals(Gearbox.AUTO8, s.gearbox(Gearbox.AUTO8));
            assertTrue(s.pseudoGears());
        }
        for (SoundStyle s : new SoundStyle[] {SoundStyle.JET, SoundStyle.JET_OLD}) {
            assertFalse(s.choosesGearbox());
            assertEquals(Gearbox.AUTO8, s.gearbox(Gearbox.MANUAL6));
            assertFalse(s.pseudoGears());
        }
    }

    @Test
    public void everyStyleSoundsInStereo() {
        for (SoundStyle s : SoundStyle.values()) {
            for (boolean cabin : new boolean[] {false, true}) {
                Voice voice = s.voice(48_000f, cabin);
                VoiceParams p = new VoiceParams();
                p.rpm = 2000f;
                p.load = 0.5f;
                p.level = 1f;
                p.gain = 1f;
                float[] lr = new float[512];
                double left = 0;
                double right = 0;
                for (int b = 0; b < 40; b++) {
                    voice.renderStereo(lr, 256, p);
                    for (int i = 0; i < 256; i++) {
                        assertTrue(Math.abs(lr[2 * i]) <= EngineVoice.CEILING + 1e-6f);
                        assertTrue(Math.abs(lr[2 * i + 1]) <= EngineVoice.CEILING + 1e-6f);
                        left += lr[2 * i] * lr[2 * i];
                        right += lr[2 * i + 1] * lr[2 * i + 1];
                    }
                }
                assertTrue(s + " 左 " + cabin, left > 1e-3);
                assertTrue(s + " 右 " + cabin, right > 1e-3);
            }
        }
    }
}
