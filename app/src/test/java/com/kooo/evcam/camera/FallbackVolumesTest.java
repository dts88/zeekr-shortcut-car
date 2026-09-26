package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.StorageHelper;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * {@link FallbackVolumes} 的单元测试：盘写不进时该换到哪个盘。
 */
public class FallbackVolumesTest {

    private static final File SSD = new File("/storage/1D8C-1D23");
    private static final File STICK = new File("/storage/B905-2EDD");
    private static final File SMALL = new File("/storage/AAAA-0001");
    private static final File INTERNAL = new File("/storage/emulated/0");

    private static long free(File root) {
        if (root.equals(SSD)) {
            return 2L << 30;
        }
        if (root.equals(STICK)) {
            return 100L << 30;
        }
        return 8L << 30;
    }

    @Test
    public void volumeNames() {
        assertEquals("1D8C-1D23", StorageHelper.volumeOf("/storage/1D8C-1D23/DCIM/EVCam_Video"));
        assertEquals("B905-2EDD", StorageHelper.volumeOf("/storage/B905-2EDD"));
        assertEquals("emulated", StorageHelper.volumeOf("/storage/emulated/0/DCIM"));
    }

    @Test
    public void skipsDeadVolumesAndOrdersByFreeSpace() {
        List<File> out = FallbackVolumes.rank(Arrays.asList(SSD, SMALL, STICK), SSD.getAbsolutePath(),
                new HashSet<>(Collections.singletonList("1D8C-1D23")), FallbackVolumesTest::free, null);
        assertEquals(Arrays.asList(STICK, SMALL), out);
    }

    /** 设定的盘回来了就先回它那里去。 */
    @Test
    public void chosenVolumeComesFirstWhenAlive() {
        List<File> out = FallbackVolumes.rank(Arrays.asList(SMALL, STICK, SSD), SSD.getAbsolutePath(),
                new HashSet<>(Collections.singletonList("B905-2EDD")), FallbackVolumesTest::free, null);
        assertEquals(Arrays.asList(SSD, SMALL), out);
    }

    /** 内置存储只在放行时出现，而且排最后。 */
    @Test
    public void internalStorageIsLastResort() {
        List<File> out = FallbackVolumes.rank(Collections.singletonList(STICK), null,
                new HashSet<>(), FallbackVolumesTest::free, INTERNAL);
        assertEquals(Arrays.asList(STICK, INTERNAL), out);
        List<File> none = FallbackVolumes.rank(Collections.emptyList(), null,
                new HashSet<>(Collections.singletonList("emulated")), FallbackVolumesTest::free, INTERNAL);
        assertTrue(none.isEmpty());
    }
}
