package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 两套槽位名字的对应关系。
 *
 * <p>错一个字，回放界面就找不到文件 —— 而「找不到」表现出来是画面空白，
 * 看着像相机坏了。</p>
 */
public class CameraSlotsTest {

    @Test
    public void internalKeysBecomeTheOutwardNames() {
        assertEquals(CameraSlots.SURROUND, CameraSlots.suffixFor(CameraSlots.KEY_SURROUND));
        assertEquals(CameraSlots.CABIN_FRONT, CameraSlots.suffixFor(CameraSlots.KEY_CABIN_FRONT));
        assertEquals(CameraSlots.CABIN_REAR, CameraSlots.suffixFor(CameraSlots.KEY_CABIN_REAR));
    }

    @Test
    public void outwardNamesMapBack() {
        assertEquals(CameraSlots.KEY_SURROUND, CameraSlots.keyForSuffix(CameraSlots.SURROUND));
        assertEquals(CameraSlots.KEY_CABIN_FRONT, CameraSlots.keyForSuffix(CameraSlots.CABIN_FRONT));
        assertEquals(CameraSlots.KEY_CABIN_REAR, CameraSlots.keyForSuffix(CameraSlots.CABIN_REAR));
    }

    /** 改名之前录的文件还在 U 盘上，读的时候必须照样认得。 */
    @Test
    public void oldFilesStillResolve() {
        assertEquals(CameraSlots.KEY_SURROUND, CameraSlots.keyForSuffix("front"));
        assertEquals(CameraSlots.KEY_CABIN_FRONT, CameraSlots.keyForSuffix("back"));
        assertEquals(CameraSlots.KEY_CABIN_REAR, CameraSlots.keyForSuffix("left"));
    }

    /** 新旧文件要落进同一个格子，否则同一次拍摄会被拆成两组。 */
    @Test
    public void oldAndNewNamesLandInTheSameBucket() {
        assertEquals(CameraSlots.SURROUND, CameraSlots.canonical("front"));
        assertEquals(CameraSlots.SURROUND, CameraSlots.canonical(CameraSlots.SURROUND));
        assertEquals(CameraSlots.CABIN_FRONT, CameraSlots.canonical("back"));
        assertEquals(CameraSlots.CABIN_REAR, CameraSlots.canonical("left"));
    }

    /**
     * 名字里不能有下划线。
     *
     * <p>文件名形如 {@code 20250101_120000_surround.mp4}，解析取的是最后一个下划线
     * 之后的部分 —— 名字里再有下划线，{@code cabin_front} 会被切成 {@code front}，
     * 正好撞上旧名字，前座舱的文件会被当成环视。</p>
     */
    @Test
    public void namesHaveNoUnderscore() {
        for (String name : new String[]{CameraSlots.SURROUND,
                CameraSlots.CABIN_FRONT, CameraSlots.CABIN_REAR}) {
            assertFalse(name + " 里不能有下划线", name.contains("_"));
            assertTrue(name + " 只能是小写字母数字", name.matches("[a-z0-9]+"));
        }
    }

    /** 自定义车型那种四面各一个相机的接法，名字归用户，我们不替他改。 */
    @Test
    public void unknownKeysArePassedThrough() {
        assertEquals("right", CameraSlots.suffixFor("right"));
        assertEquals("right", CameraSlots.keyForSuffix("right"));
        assertEquals("cam5", CameraSlots.canonical("cam5"));
    }

    @Test
    public void tellsWhichSpellingAFileUses() {
        assertTrue(CameraSlots.isLegacySuffix("front"));
        assertTrue(CameraSlots.isLegacySuffix("left"));
        assertFalse(CameraSlots.isLegacySuffix(CameraSlots.SURROUND));
        assertFalse(CameraSlots.isLegacySuffix("cam5"));
    }
}
