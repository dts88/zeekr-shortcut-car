package com.kooo.evcam.update;

import java.io.IOException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Integrity of a GitHub asset; this does not authenticate a public APK signing key. */
final class DownloadIntegrity {
    private DownloadIntegrity() {}

    static URL https(String value) throws IOException {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null)
            throw new IOException("Update URLs must use HTTPS without credentials");
        return url;
    }

    static String expected(String digest) throws IOException {
        if (digest == null || !digest.matches("sha256:[0-9a-fA-F]{64}"))
            throw new IOException("Missing or invalid GitHub SHA-256 digest");
        return digest.substring(7);
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static void verify(String expected, byte[] actual, long expectedSize, long actualSize) throws IOException {
        if (expectedSize <= 0 || actualSize != expectedSize)
            throw new IOException("Downloaded APK size does not match GitHub asset");
        StringBuilder hex = new StringBuilder(64);
        for (byte b : actual) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        if (!hex.toString().equalsIgnoreCase(expected))
            throw new IOException("Downloaded APK SHA-256 does not match GitHub asset");
    }
}
