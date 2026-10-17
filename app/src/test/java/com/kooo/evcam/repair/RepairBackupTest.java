package com.kooo.evcam.repair;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RepairBackupTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void failedInPlaceRepairCannotAlterVerifiedOriginalBackup() throws Exception {
        File source = temp.newFile("20261014_120000_surround.mp4");
        byte[] original = {1, 2, 3, 4}; Files.write(source.toPath(), original);
        File backup = RepairBackup.preserve(source);
        Files.write(source.toPath(), new byte[]{1, 0});
        assertArrayEquals(original, Files.readAllBytes(backup.toPath()));
        assertThrows(IOException.class, () -> RepairBackup.preserve(source));
        assertArrayEquals(original, Files.readAllBytes(backup.toPath()));
        assertArrayEquals(new byte[]{1, 0}, Files.readAllBytes(source.toPath()));
    }
    @Test public void repeatedUnchangedRepairPreparationReusesVerifiedBackup() throws Exception {
        File source = temp.newFile("20261014_120000_surround.mp4");
        Files.write(source.toPath(), new byte[]{9});
        File backup = RepairBackup.preserve(source);
        assertEquals(backup, RepairBackup.preserve(source));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(source.toPath()));
        assertEquals(1, backup.getParentFile().listFiles().length);
    }
}
