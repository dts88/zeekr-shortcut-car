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
        assertEquals(SoundStyle.AMERICAN, SoundStyle.of(null));
        assertEquals(SoundStyle.AMERICAN, SoundStyle.of("v12"));
    }

    @Test
    public void onlyThePrototypeCranks() {
        assertTrue(SoundStyle.PROTOTYPE.cranks());
        assertFalse(SoundStyle.AMERICAN.cranks());
        assertFalse(SoundStyle.JET.cranks());
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
