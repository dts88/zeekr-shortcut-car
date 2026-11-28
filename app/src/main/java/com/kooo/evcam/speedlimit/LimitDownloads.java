/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import com.kooo.evcam.update.DownloadIntegrity;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 限速数据的出网：问出网地址、取清单、下一个国家的包（项目本地文档 speed-limits/spec.md §二）。
 *
 * <p>只去 zeekr.autos、只走 https，跳转也不能跳出这个站；不带任何设备信息，也不上传任何东西。都是阻塞的，在后台线程上调。
 * 只在 Wi-Fi 下 —— 调之前先 {@link #network}，不是 Wi-Fi 就别调后面两个（界面那边管）。</p>
 */
public final class LimitDownloads {

    /** 下回来的东西和清单对不上、或者包本身有毛病：没有启用。 */
    public static final class Unverified extends IOException {
        public Unverified(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    private static final String USER_AGENT = "ZeekrShortcut-SpeedLimits";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_TRACE_BYTES = 4 * 1024;
    private static final int MAX_CATALOG_BYTES = 256 * 1024;
    private static final int MAX_REDIRECTS = 3;

    private LimitDownloads() {
    }

    /** 现在是不是 Wi-Fi：问一次出网地址再判断（{@link WifiGate}）。问不到算无法判断，没见过的要用户确认。 */
    public static WifiGate.Result network(WifiGate gate) {
        String prefix;
        try {
            prefix = WifiGate.prefixOf(getText(WifiGate.TRACE_URL, MAX_TRACE_BYTES));
        } catch (IOException e) {
            prefix = null;
        }
        return gate.judge(prefix);
    }

    /** 取清单。 */
    public static LimitCatalog catalog() throws IOException {
        return LimitCatalog.parse(getText(LimitCatalog.URL, MAX_CATALOG_BYTES), LimitCatalog.URL);
    }

    /**
     * 下一个国家的包并换上：边收边算 sha256，大小、sha256 对上清单，再交给 {@link LimitStore#install} 整个解一遍。
     * 哪一步不过都不启用、残件删掉；对不上的抛 {@link Unverified}，网络的照原样抛。
     */
    public static void download(LimitCatalog.Entry entry, LimitStore store, Progress progress) throws IOException {
        File part = store.part(entry.country);
        MessageDigest hash = DownloadIntegrity.sha256();
        long done = 0;
        HttpURLConnection connection = open(entry.url);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            long total = connection.getContentLengthLong();
            if (total > 0 && total != entry.bytes) {
                throw new Unverified("size " + total + " is not " + entry.bytes, null);
            }
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(part)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    done += n;
                    if (done > entry.bytes) {
                        throw new Unverified("more bytes than the catalog lists", null);
                    }
                    out.write(buffer, 0, n);
                    hash.update(buffer, 0, n);
                    if (progress != null) {
                        progress.onProgress(done, entry.bytes);
                    }
                }
            }
            try {
                DownloadIntegrity.verify(entry.sha256, hash.digest(), entry.bytes, done);
            } catch (IOException e) {
                throw new Unverified(e.getMessage(), e);
            }
        } catch (IOException e) {
            LimitStore.deleteQuietly(part);
            throw e;
        } finally {
            connection.disconnect();
        }
        try {
            store.install(entry, part);
        } catch (LimitPackage.Invalid e) {
            throw new Unverified(e.getMessage(), e);
        }
    }

    private static String getText(String url, int maxBytes) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8 * 1024];
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                    if (buffer.size() > maxBytes) {
                        throw new IOException("Response too large");
                    }
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    /** 发一个 GET；跳转自己跟，每一跳都要是 https、还在 zeekr.autos。 */
    private static HttpURLConnection open(String url) throws IOException {
        URL next = checked(url);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) next.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            try {
                int status = connection.getResponseCode();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                    return connection;
                }
                String location = connection.getHeaderField("Location");
                if (location == null) {
                    throw new IOException("Redirect without Location");
                }
                next = checked(new URL(next, location).toString());
            } catch (IOException e) {
                connection.disconnect();
                throw e;
            }
            connection.disconnect();
        }
        throw new IOException("Too many redirects");
    }

    private static URL checked(String url) throws IOException {
        URL u = DownloadIntegrity.https(url);
        if (!LimitCatalog.HOST.equals(u.getHost().toLowerCase(Locale.ROOT)) || u.getPort() != -1) {
            throw new IOException("Speed limit data only comes from " + LimitCatalog.HOST);
        }
        return u;
    }
}
