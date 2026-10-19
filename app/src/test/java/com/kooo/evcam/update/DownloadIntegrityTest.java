package com.kooo.evcam.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;

/**
 * {@link DownloadIntegrity} 的单元测试：大小和 sha256 都对上才算下载成功，链接只认 https。
 */
public class DownloadIntegrityTest {

    /** 空内容的 sha256。 */
    private static final String EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    public void acceptsVerifiedBytesAndSize() throws Exception {
        byte[] bytes = new byte[]{97, 98, 99};
        // 「abc」的 sha256
        String digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        DownloadIntegrity.verify(DownloadIntegrity.expected("sha256:" + digest),
                DownloadIntegrity.sha256().digest(bytes), bytes.length, bytes.length);
    }

    /** 接口里 digest 是 JSON null 时，安卓的 optString 给的是字符串 "null"。 */
    @Test
    public void rejectsMissingOrMalformedDigest() {
        for (String invalid : new String[]{null, "", "null", "sha1:" + EMPTY_SHA, "sha256:abc"}) {
            assertThrows(IOException.class, () -> DownloadIntegrity.expected(invalid));
        }
    }

    @Test
    public void rejectsWrongBytesEvenAtTheExpectedLength() {
        byte[] actual = DownloadIntegrity.sha256().digest(new byte[]{1});
        assertThrows(IOException.class, () -> DownloadIntegrity.verify(EMPTY_SHA, actual, 1, 1));
    }

    @Test
    public void rejectsTruncatedAndUnknownSizes() {
        byte[] hash = DownloadIntegrity.sha256().digest();
        assertThrows(IOException.class, () -> DownloadIntegrity.verify(EMPTY_SHA, hash, 2, 1));
        assertThrows(IOException.class, () -> DownloadIntegrity.verify(EMPTY_SHA, hash, 0, 1));
    }

    @Test
    public void refusesHttpDowngradeAndCredentialUrls() throws Exception {
        assertEquals("https", DownloadIntegrity.https("https://github.com/file").getProtocol());
        assertThrows(IOException.class, () -> DownloadIntegrity.https("http://github.com/file"));
        assertThrows(IOException.class, () -> DownloadIntegrity.https("https://user@github.com/file"));
        assertThrows(IOException.class, () -> DownloadIntegrity.https("file:/tmp/file.apk"));
    }
}
