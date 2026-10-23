package com.kooo.evcam.storage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Never delete the old protection list as a fallback for a failed replacement. */
public final class AtomicLockList {
    private AtomicLockList() {}
    interface Publisher { void publish(File part, File target) throws IOException; }
    public static void write(File target, String contents) throws IOException {
        write(target, contents, (part, destination) -> Files.move(part.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }
    static void write(File target, String contents, Publisher publisher) throws IOException {
        File part = File.createTempFile(".lock-list-", ".tmp", target.getAbsoluteFile().getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(contents.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            // If atomic replacement is unsupported or denied, fail without unlinking the old list.
            publisher.publish(part, target);
        } finally { if (part.exists()) part.delete(); }
    }
}
