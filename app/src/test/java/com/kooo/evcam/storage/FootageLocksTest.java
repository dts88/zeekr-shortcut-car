package com.kooo.evcam.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Set;

/**
 * 锁定清单（U 盘上的 locked.txt）的读写格式：一行一个文件名，只认本应用的文件名。
 */
public class FootageLocksTest {

    @Test
    public void readsOnlyTheAppsOwnFileNames() {
        Set<String> names = FootageLocks.parse("# header\n\n"
                + "20261003_101500_surround.mp4\n"
                + "  20261003_101500_cabinfront.mp4  \r\n"
                + "holiday.mp4\n"
                + "20261003_101501_surround.jpg\n");
        assertEquals(3, names.size());
        assertTrue(names.contains("20261003_101500_cabinfront.mp4"));
        assertTrue(names.contains("20261003_101501_surround.jpg"));
    }

    @Test
    public void writingAndReadingBackGivesTheSameNames() {
        Set<String> names = FootageLocks.parse(FootageLocks.format(Arrays.asList(
                "20261003_101600_surround.mp4", "20261003_101500_surround.mp4")));
        assertEquals(2, names.size());
        assertEquals("按时间排", "20261003_101500_surround.mp4", names.iterator().next());
    }

    @Test
    public void anEmptyOrMissingListLocksNothing() {
        assertTrue(FootageLocks.parse(null).isEmpty());
        assertTrue(FootageLocks.parse("").isEmpty());
    }
}
