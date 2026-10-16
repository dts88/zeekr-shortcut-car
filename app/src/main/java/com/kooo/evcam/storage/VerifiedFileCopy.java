package com.kooo.evcam.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Copy closed footage without overwriting a destination or mistaking equal sizes for equal bytes.
 * Sources are NEVER removed here. Callers may delete only after a verified successful return.
 */
public final class VerifiedFileCopy {
    public enum Result { COPIED, ALREADY_IDENTICAL }
    private VerifiedFileCopy() {}
    public static synchronized Result copy(File source, File target) throws IOException {
        if (source == null || target == null || !source.isFile()) throw new IOException("Missing source or target");
        if (source.getCanonicalFile().equals(target.getCanonicalFile())) throw new IOException("Source equals target");
        long length = source.length(), modified = source.lastModified();
        if (length <= 0) throw new IOException("Source is empty; preserved for recovery");
        if (target.exists()) {
            if (identical(source, target)) return Result.ALREADY_IDENTICAL;
            throw new FileAlreadyExistsException(target.toString());
        }
        File parent = target.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create target directory");
        File part = File.createTempFile(".footage-copy-", ".part", parent);
        try {
            MessageDigest copied = sha256();
            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(part)) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count); copied.update(buffer, 0, count); written += count;
                }
                out.getFD().sync();
            }
            if (written != length || part.length() != length || source.length() != length || source.lastModified() != modified)
                throw new IOException("Source changed during copy; preserved");
            if (!Arrays.equals(copied.digest(), digest(part))) throw new IOException("Copied bytes failed verification");
            if (!part.setLastModified(modified)) { /* Modification time is advisory; verified bytes are essential. */ }
            try {
                Files.move(part.toPath(), target.toPath()); // No REPLACE_EXISTING: never knowingly replace footage.
            } catch (FileAlreadyExistsException collision) {
                if (identical(source, target)) return Result.ALREADY_IDENTICAL;
                throw collision;
            }
            return Result.COPIED;
        } finally { if (part.exists()) part.delete(); } // Only this call's exclusive temporary file.
    }
    public static boolean identical(File a, File b) throws IOException {
        return a.isFile() && b.isFile() && a.length() == b.length() && Arrays.equals(digest(a), digest(b));
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static byte[] digest(File file) throws IOException {
        MessageDigest hash = sha256(); byte[] buffer = new byte[64 * 1024];
        try (FileInputStream in = new FileInputStream(file)) {
            int count; while ((count = in.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        return hash.digest();
    }
}
