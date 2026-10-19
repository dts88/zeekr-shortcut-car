package com.kooo.evcam.update;

import java.io.IOException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 下载回来的 APK 是不是发布页上的那一个。
 *
 * <ul>
 *   <li>链接只认 https、不带用户名密码；跳转之后的每一个链接都过一遍（{@link #https}）。</li>
 *   <li>GitHub 给每个附件算好了 sha256（接口里的 {@code digest}）：边收边算，收完大小和 sha256 都对上才算下载成功；
 *       没给或格式不对的不下载。</li>
 * </ul>
 *
 * <p>为什么要对 sha256：发布包是用仓库里公开的那把测试密钥签的（README），谁都能用它签一个包，
 * 系统安装时核对签名挡不住。这里管的是「收到的就是发布页上那一个」：被截断、被换掉、被导到别处都会失败；
 * 发布页本身可不可信不在这里管。</p>
 *
 * <p>来源：wysie 的 PR #38；GPL-3.0。纯 Java，见 {@code DownloadIntegrityTest}。</p>
 */
final class DownloadIntegrity {

    private static final String SHA256_PREFIX = "sha256:";

    private DownloadIntegrity() {
    }

    /** 检查一个链接（下载的、跳转去的）：只认 https、不带用户名密码；不合格抛 IOException。 */
    static URL https(String value) throws IOException {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null) {
            throw new IOException("Update URLs must use HTTPS without credentials");
        }
        return url;
    }

    /** GitHub 给的 {@code digest}（「sha256:」加 64 位十六进制）→ 十六进制那一段；没有或格式不对抛 IOException。 */
    static String expected(String digest) throws IOException {
        if (digest == null || !digest.matches(SHA256_PREFIX + "[0-9a-fA-F]{64}")) {
            throw new IOException("Missing or invalid GitHub SHA-256 digest");
        }
        return digest.substring(SHA256_PREFIX.length());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // 每个 Java / 安卓运行环境都必须有 SHA-256
            throw new IllegalStateException(impossible);
        }
    }

    /** 收完之后对一遍：大小和 GitHub 说的一样、算出来的 sha256 和它给的一样；有一样不对抛 IOException。 */
    static void verify(String expected, byte[] actual, long expectedSize, long actualSize) throws IOException {
        if (expectedSize <= 0 || actualSize != expectedSize) {
            throw new IOException("Downloaded APK size does not match GitHub asset");
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : actual) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        if (!hex.toString().equalsIgnoreCase(expected)) {
            throw new IOException("Downloaded APK SHA-256 does not match GitHub asset");
        }
    }
}
