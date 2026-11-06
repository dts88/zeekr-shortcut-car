/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * 锁定清单（U 盘上的 locked.txt）的读写格式：一行一个文件名，只认本应用的文件名。
 * 换清单：换不上时原来那份留着，换到一半断了读旧的那份。
 */
public class FootageLocksTest {

    private static final String A = "20261003_101500_surround.mp4";
    private static final String B = "20261003_101600_surround.mp4";

    /** 不让改名盖掉已有文件的文件系统。 */
    private static final FootageLocks.Rename NO_OVERWRITE = (from, to) -> !to.exists() && from.renameTo(to);

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

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

    @Test
    public void aFileSystemThatWillNotOverwriteStillGetsTheNewList() throws IOException {
        File dir = temp.getRoot();
        File file = write(dir, "locked.txt", A);
        File tmp = write(dir, "locked.txt.tmp", A, B);
        File old = new File(dir, "locked.txt.old");
        assertTrue(FootageLocks.replace(tmp, file, old, NO_OVERWRITE));
        assertEquals(names(A, B), FootageLocks.read(dir));
        assertFalse(tmp.exists());
        assertFalse(old.exists());
    }

    @Test
    public void whenTheNewListWillNotGoInTheOldOneStays() throws IOException {
        File dir = temp.getRoot();
        File file = write(dir, "locked.txt", A);
        File tmp = write(dir, "locked.txt.tmp", A, B);
        File old = new File(dir, "locked.txt.old");
        // 新清单怎么都放不上去：以前到这一步旧的已经删了，锁的全丢
        FootageLocks.Rename refuseNew = (from, to) -> !from.equals(tmp) && NO_OVERWRITE.to(from, to);
        assertFalse(FootageLocks.replace(tmp, file, old, refuseNew));
        assertEquals(names(A), FootageLocks.read(dir));
        assertTrue(file.isFile());
        assertFalse(tmp.exists());
        assertFalse(old.exists());
    }

    @Test
    public void aSwapCutHalfwayReadsTheOldList() throws IOException {
        File dir = temp.getRoot();
        write(dir, "locked.txt.old", A);
        write(dir, "locked.txt.tmp", A, B); // 新的还没换上就断了电
        assertEquals(names(A), FootageLocks.read(dir));
    }

    @Test
    public void theListItselfWinsOverALeftoverOld() throws IOException {
        File dir = temp.getRoot();
        write(dir, "locked.txt", B);
        write(dir, "locked.txt.old", A);
        assertEquals(names(B), FootageLocks.read(dir));
    }

    @Test
    public void unlockingEverythingRemovesTheLeftoverOldToo() throws IOException {
        File dir = temp.getRoot();
        write(dir, "locked.txt.old", A); // 换到一半留下的：这时它就是清单
        FootageLocks.forget(dir, Collections.singletonList(A));
        assertTrue(FootageLocks.read(dir).isEmpty());
        assertFalse(new File(dir, "locked.txt.old").exists());
    }

    private static File write(File dir, String name, String... names) throws IOException {
        File file = new File(dir, name);
        Files.write(file.toPath(), FootageLocks.format(Arrays.asList(names)).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static Set<String> names(String... names) {
        return new TreeSet<>(Arrays.asList(names));
    }
}
