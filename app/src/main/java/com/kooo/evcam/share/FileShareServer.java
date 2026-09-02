package com.kooo.evcam.share;

import android.content.Context;

import com.kooo.evcam.AppLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;

import fi.iki.elonen.NanoHTTPD;

/**
 * 把<b>一个</b>文件通过局域网发给手机。
 *
 * <h3>为什么是 HTTP + 二维码，而不是配套 App</h3>
 *
 * <p>手机上不用装任何东西：扫码 → 浏览器打开 → 长按保存。
 * 上游 EVCam 用的就是这条路（NanoHTTPD + ZXing），这里沿用。</p>
 *
 * <h3>一次只放一个文件</h3>
 *
 * <p>不做文件浏览器。分享的是「你正在看的这张照片 / 这一段录像」，
 * 把整块盘的目录暴露到局域网上是另一回事，风险也完全不同。</p>
 *
 * <h3>路径带一段随机串</h3>
 *
 * <p>同一个热点下可能不止一台设备。猜不到路径不等于安全，但至少不是
 * 「谁连上来都能顺手拿走」。服务本身也是<b>用完就关</b>的。</p>
 */
public class FileShareServer extends NanoHTTPD {

    private static final String TAG = "FileShareServer";

    /** 端口交给系统分配：写死一个端口迟早撞上别的服务。 */
    private static final int AUTO_PORT = 0;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Context context;
    private File file;
    private String token;

    public FileShareServer(Context context) {
        super(AUTO_PORT);
        this.context = context.getApplicationContext();
    }

    /**
     * 换成分享这个文件。
     *
     * <p>每换一次都换一段新的随机串 —— 上一张照片的链接就此失效。</p>
     */
    public synchronized void share(File target) {
        this.file = target;
        this.token = randomToken();
    }

    public synchronized File sharedFile() {
        return file;
    }

    /**
     * 手机要访问的地址。
     *
     * @param host 从 {@link LocalNetwork} 里选出来的那个 IPv4 地址
     */
    public synchronized String urlFor(String host) {
        if (host == null || token == null) {
            return null;
        }
        return String.format(Locale.US, "http://%s:%d/s/%s", host, getListeningPort(), token);
    }

    private static String randomToken() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 请求

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri() == null ? "" : session.getUri();
        File shared;
        String expected;
        synchronized (this) {
            shared = file;
            expected = token;
        }
        if (shared == null || expected == null) {
            return text(Response.Status.NOT_FOUND, "没有正在分享的文件 / Nothing is being shared");
        }

        if (uri.equals("/s/" + expected)) {
            return landingPage(shared, expected);
        }
        if (uri.equals("/f/" + expected)) {
            return fileResponse(shared);
        }
        // 路径不对就当没有这个东西，不提示「token 错了」——
        // 那等于告诉对方「猜对格式了，继续猜」
        return text(Response.Status.NOT_FOUND, "Not found");
    }

    private Response fileResponse(File shared) {
        try {
            Response response = newFixedLengthResponse(Response.Status.OK,
                    mimeTypeOf(shared.getName()), new FileInputStream(shared), shared.length());
            // inline 而不是 attachment：手机浏览器直接把图片/视频显示出来，
            // 用户长按就能存 —— attachment 会变成一次下载，反而多一步
            response.addHeader("Content-Disposition",
                    "inline; filename=\"" + shared.getName() + "\"");
            return response;
        } catch (IOException e) {
            AppLog.e(TAG, "读不到要分享的文件", e);
            return text(Response.Status.INTERNAL_ERROR, "读取失败 / Cannot read file");
        }
    }

    /** 一个极简页面：内容本身 + 怎么存。 */
    private Response landingPage(File shared, String tokenValue) {
        boolean video = isVideo(shared.getName());
        String media = video
                ? "<video src=\"/f/" + tokenValue + "\" controls playsinline></video>"
                : "<img src=\"/f/" + tokenValue + "\" alt=\"\">";
        String howTo = video
                ? "长按视频 → 选择「保存视频」或「下载」<br>"
                  + "Long-press the video → Save video / Download"
                : "长按图片 → 选择「保存图片」或「下载」<br>"
                  + "Long-press the image → Save image / Download";

        String html = "<!doctype html><html lang=\"zh\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escape(shared.getName()) + "</title><style>"
                + "body{margin:0;padding:16px;background:#1a1a1a;color:#fff;"
                + "font-family:system-ui,-apple-system,sans-serif;text-align:center}"
                + "img,video{max-width:100%;height:auto;border-radius:8px;background:#000}"
                + ".name{margin:12px 0 4px;font-size:15px;word-break:break-all}"
                + ".size{color:#999;font-size:13px}"
                + ".tip{margin-top:20px;padding:14px;background:#2a2a2a;border-radius:8px;"
                + "font-size:14px;line-height:1.7;color:#ddd}"
                + "a{color:#4a90d9}"
                + "</style></head><body>"
                + media
                + "<div class=\"name\">" + escape(shared.getName()) + "</div>"
                + "<div class=\"size\">" + readableSize(shared.length()) + "</div>"
                + "<div class=\"tip\">" + howTo
                + "<br><br>存不下来时，点这里直接下载：<br>"
                + "<a href=\"/f/" + tokenValue + "\" download>" + escape(shared.getName()) + "</a>"
                + "</div></body></html>";
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
    }

    private Response text(Response.Status status, String body) {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", body);
    }

    // ------------------------------------------------------------------ 小工具

    static boolean isVideo(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm");
    }

    static String mimeTypeOf(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    static String readableSize(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
