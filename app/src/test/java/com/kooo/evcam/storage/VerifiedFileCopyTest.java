package com.kooo.evcam.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

public class VerifiedFileCopyTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File file(String name, byte... bytes) throws IOException {
        File f = new File(temp.getRoot(), name); Files.write(f.toPath(), bytes); return f;
    }
    @Test public void sameSizeCollisionPreservesBothRecordings() throws Exception {
        File source = file("source.mp4", (byte)1, (byte)2), target = file("target.mp4", (byte)3, (byte)4);
        assertThrows(IOException.class, () -> VerifiedFileCopy.copy(source, target));
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(source.toPath()));
        assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(target.toPath()));
    }
    @Test public void verifiedDuplicateDoesNotRewriteOrRemoveEitherFile() throws Exception {
        File source = file("source.mp4", (byte)1), target = file("target.mp4", (byte)1);
        long stamp = target.lastModified();
        assertEquals(VerifiedFileCopy.Result.ALREADY_IDENTICAL, VerifiedFileCopy.copy(source, target));
        assertTrue(source.exists()); assertEquals(stamp, target.lastModified());
    }
    @Test public void tinyNonEmptyClipIsRetainedAndCopied() throws Exception {
        File source = file("source.mp4", (byte)7), target = new File(temp.getRoot(), "target.mp4");
        assertEquals(VerifiedFileCopy.Result.COPIED, VerifiedFileCopy.copy(source, target));
        assertArrayEquals(Files.readAllBytes(source.toPath()), Files.readAllBytes(target.toPath()));
        assertEquals(2, temp.getRoot().listFiles().length);
    }
    @Test public void impossibleDestinationKeepsSourceAndOtherFile() throws Exception {
        File source = file("source.mp4", (byte)7), blocker = file("not-a-directory", (byte)8);
        assertThrows(IOException.class, () -> VerifiedFileCopy.copy(source, new File(blocker, "target.mp4")));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(source.toPath()));
        assertArrayEquals(new byte[]{8}, Files.readAllBytes(blocker.toPath()));
    }
    @Test public void selfCopyAndEmptySourceNeverRemoveSource() throws Exception {
        File source = file("source.mp4", (byte)7), empty = file("empty.mp4");
        assertThrows(IOException.class, () -> VerifiedFileCopy.copy(source, source));
        assertThrows(IOException.class, () -> VerifiedFileCopy.copy(empty, new File(temp.getRoot(), "out.mp4")));
        assertTrue(source.exists()); assertTrue(empty.exists());
    }
    @Test public void largeCopyIsByteExactAndLeavesNoPartialFiles() throws Exception {
        byte[] bytes = new byte[250_001]; new java.util.Random(41).nextBytes(bytes);
        File source = file("source.mp4", bytes), target = new File(temp.getRoot(), "target.mp4");
        VerifiedFileCopy.copy(source, target);
        assertArrayEquals(bytes, Files.readAllBytes(target.toPath()));
        assertFalse(Arrays.stream(temp.getRoot().listFiles()).anyMatch(f -> f.getName().endsWith(".part")));
    }
}
