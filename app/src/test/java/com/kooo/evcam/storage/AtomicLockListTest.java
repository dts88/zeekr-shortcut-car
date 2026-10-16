package com.kooo.evcam.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class AtomicLockListTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void replacesWholeListWithoutLeavingTemporaryFiles() throws Exception {
        File target = temp.newFile("locked.txt");
        AtomicLockList.write(target, "first\nsecond\n");
        AtomicLockList.write(target, "second\n");
        assertEquals("second\n", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, temp.getRoot().listFiles().length);
    }
    @Test public void failedReplacementPreservesExistingDestination() throws Exception {
        File target = temp.newFolder("locked.txt"), existing = new File(target, "existing.txt");
        Files.write(existing.toPath(), new byte[]{42});
        assertThrows(IOException.class, () -> AtomicLockList.write(target, "new\n"));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(existing.toPath()));
        assertEquals(1, temp.getRoot().listFiles().length);
    }
    @Test public void failedAtomicPublicationPreservesRegularProtectionFile() throws Exception {
        File target = temp.newFile("locked.txt");
        byte[] previous = "# locks\nimportant.mp4\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target.toPath(), previous);
        assertThrows(IOException.class, () -> AtomicLockList.write(target, "replacement\n", (part, destination) -> {
            assertTrue(part.isFile());
            throw new java.nio.file.AtomicMoveNotSupportedException(part.toString(), destination.toString(), "simulated filesystem");
        }));
        assertArrayEquals(previous, Files.readAllBytes(target.toPath()));
        assertEquals(1, temp.getRoot().listFiles().length);
    }
}
