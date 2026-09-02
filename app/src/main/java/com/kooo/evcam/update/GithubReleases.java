package com.kooo.evcam.update;

import com.kooo.evcam.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

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

/**
 * 去 GitHub Releases 问一句「有没有更新」，有就把 APK 拉回来。
 *
 * <h3>为什么不用 /releases/latest</h3>
 *
 * <p>那个接口<b>只认正式版</b>，会把预发布版全部跳过。本项目目前发的是 beta
 * 预发布，用它会永远返回 404 —— 表现就是「永远没有更新」。
 * 所以这里拉列表，自己挑。</p>
 *
 * <h3>只推 beta 与正式版</h3>
 *
 * <p>alpha 是开发过程中随手发的，数量多、稳定性没有保证 ——
 * 不该被推给一台正在用的车机。判断在 {@link VersionName#isBetaOrRelease}。</p>
 *
 * <h3>这是本应用唯一一次主动出网</h3>
 *
 * <p>只在用户点「检查更新」时发生，去的是 {@code api.github.com} 和
 * GitHub 的下载域名，不带任何设备信息，也不上传任何东西。</p>
 */
public final class GithubReleases {

    private static final String TAG = "GithubReleases";

    private static final String OWNER = "dts88";
    private static final String REPO = "zeekr-shortcut-car";
    private static final String LIST_URL =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases?per_page=20";

    /** GitHub 要求带 User-Agent，不带会直接 403。 */
    private static final String USER_AGENT = "ZeekrShortcut-UpdateCheck";

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

    /** 响应体最大只收这么多，防止对面给一个没完没了的流把内存吃光。 */
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;

    /** APK 的大小上限。本应用约 16 MB，留足余量，超过就当作不对劲。 */
    private static final long MAX_APK_BYTES = 200L * 1024 * 1024;

    private GithubReleases() {
    }

    /** 一个可安装的版本。 */
    public static final class Release {
        public final String tagName;
        public final String apkName;
        public final String apkUrl;
        public final long apkBytes;
        public final String pageUrl;

        Release(String tagName, String apkName, String apkUrl, long apkBytes, String pageUrl) {
            this.tagName = tagName;
            this.apkName = apkName;
            this.apkUrl = apkUrl;
            this.apkBytes = apkBytes;
            this.pageUrl = pageUrl;
        }
    }

    /** 下载进度。{@code total} 为 0 表示对面没给长度。 */
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    /**
     * 取版本号最大的那个非草稿、且属于 beta 或正式版的版本。
     *
     * @return 没有符合条件、且带 APK 的版本时返回 null
     * @throws IOException 网络或解析出错
     */
    public static Release fetchLatest() throws IOException {
        String body = getText(LIST_URL);
        Release best = null;
        try {
            JSONArray releases = new JSONArray(body);
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null || release.optBoolean("draft", false)) {
                    // 草稿是不该被公众看到的版本
                    continue;
                }
                Release candidate = toRelease(release);
                if (candidate == null) {
                    continue;
                }
                if (!VersionName.isBetaOrRelease(candidate.tagName)) {
                    // alpha 不推：那是开发过程里随手发的，不该盖到一台在用的车机上
                    continue;
                }
                if (best == null || VersionName.compare(candidate.tagName, best.tagName) > 0) {
                    best = candidate;
                }
            }
        } catch (org.json.JSONException e) {
            throw new IOException("看不懂 GitHub 的响应: " + e.getMessage(), e);
        }
        return best;
    }

    /** 挑出这个版本里的 APK 附件；没有就返回 null。 */
    private static Release toRelease(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            if (name.toLowerCase(java.util.Locale.US).endsWith(".apk") && !url.isEmpty()) {
                return new Release(
                        release.optString("tag_name", ""),
                        name,
                        url,
                        asset.optLong("size", 0),
                        release.optString("html_url", ""));
            }
        }
        return null;
    }

    /**
     * 把 APK 下载到指定文件。
     *
     * <p>失败时会把没下完的残件删掉 —— 留着一个半截的 APK，下次装的时候
     * 只会得到一句莫名其妙的「解析包时出现问题」。</p>
     */
    public static void download(Release release, File target, ProgressListener listener)
            throws IOException {
        HttpURLConnection connection = open(release.apkUrl);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载失败，HTTP " + code);
            }
            long total = connection.getContentLengthLong();
            if (total > MAX_APK_BYTES) {
                throw new IOException("文件过大（" + total + " 字节），已中止");
            }

            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("建不了下载目录: " + parent);
            }

            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(target)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    done += n;
                    if (done > MAX_APK_BYTES) {
                        throw new IOException("下载内容超出上限，已中止");
                    }
                    if (listener != null) {
                        listener.onProgress(done, total);
                    }
                }
            }
            if (total > 0 && done != total) {
                throw new IOException("只收到 " + done + "/" + total + " 字节");
            }
            AppLog.d(TAG, "已下载 " + release.apkName + "（" + done + " 字节）");
        } catch (IOException e) {
            // 残件必须删掉
            if (target.exists() && !target.delete()) {
                AppLog.w(TAG, "残留的下载文件删不掉: " + target);
            }
            throw e;
        } finally {
            connection.disconnect();
        }
    }

    private static String getText(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub 返回 HTTP " + code);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            try (InputStream in = new BufferedInputStream(connection.getInputStream())) {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                    if (buffer.size() > MAX_JSON_BYTES) {
                        throw new IOException("响应过大，已中止");
                    }
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setInstanceFollowRedirects(true);
        return connection;
    }
}
