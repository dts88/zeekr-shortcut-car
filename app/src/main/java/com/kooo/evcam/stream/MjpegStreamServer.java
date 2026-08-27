package com.kooo.evcam.stream;

import android.graphics.BitmapFactory;

import com.kooo.evcam.AppLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import fi.iki.elonen.NanoHTTPD;

/**
 * MJPEG over HTTP 服务器。
 * <p>
 * 路由：
 * <ul>
 *   <li>{@code GET /}          简单 HTML 页面，浏览器打开即看</li>
 *   <li>{@code GET /stream}    {@code multipart/x-mixed-replace} MJPEG 流</li>
 *   <li>{@code GET /snapshot}  单帧 JPEG（方便截图调试）</li>
 * </ul>
 * <p>
 * 多客户端各自独立线程，从 {@link MjpegFrameHolder} 取最新帧推送，慢客户端自然丢帧。
 * 实现用 PipedInputStream/PipedOutputStream 桥接：推流线程往 PipedOutputStream 写，
 * NanoHTTPD 从 PipedInputStream 读并转发到 socket。
 */
public class MjpegStreamServer extends NanoHTTPD implements FrameStreamServer {
    private static final String TAG = "MjpegStreamServer";
    private static final String BOUNDARY = "frame";
    private static final String CONTENT_TYPE_STREAM =
            "multipart/x-mixed-replace; boundary=" + BOUNDARY;
    private static final int PIPE_BUF = 256 * 1024;

    private final MjpegFrameHolder frameHolder;
    private final CopyOnWriteArrayList<Thread> streamThreads = new CopyOnWriteArrayList<>();
    private final AtomicInteger clientCount = new AtomicInteger(0);

    public MjpegStreamServer(int port, MjpegFrameHolder frameHolder) {
        super(port);
        this.frameHolder = frameHolder;
    }

    /** 当前连接中的流客户端数。上层据此决定是否暂停编码以省 CPU。 */
    public int getClientCount() {
        return clientCount.get();
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (session.getMethod() != Method.GET) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "GET only");
        }
        String uri = session.getUri();
        switch (uri) {
            case "/":
            case "/index.html":
                return htmlIndex();
            case "/stream":
            case "/mjpeg":
                return startMjpegStream();
            case "/snapshot":
            case "/shot":
                return snapshot();
            default:
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
    }

    private Response htmlIndex() {
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>EVCam MJPEG Stream</title>"
                + "<style>body{margin:0;background:#000;color:#eee;font-family:sans-serif}"
                + "img{width:100vw;height:100vh;object-fit:contain}"
                + "a{color:#4af}</style></head>"
                + "<body><img src=\"/stream\" alt=\"stream\"></body></html>";
        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }

    /**
     * 启动一个 MJPEG 推流响应。NanoHTTPD 会从返回的 InputStream 持续读取并写入 socket，
     * 直到流关闭或客户端断开。我们用一对 PipedStream 把推流线程产出的字节送到
     * NanoHTTPD 的读取端。
     */
    private Response startMjpegStream() {
        final PipedInputStream pis = new PipedInputStream(PIPE_BUF);
        final PipedOutputStream pos;
        try {
            pos = new PipedOutputStream(pis);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "text/plain", "pipe setup failed: " + e.getMessage());
        }

        Thread t = new Thread(() -> streamLoop(pos), "MjpegStream-Client");
        t.setDaemon(true);
        streamThreads.add(t);
        clientCount.incrementAndGet();
        t.start();

        Response res = newChunkedResponse(Response.Status.OK, CONTENT_TYPE_STREAM, pis);
        res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        res.addHeader("Pragma", "no-cache");
        res.addHeader("Connection", "close");
        return res;
    }

    /**
     * 推流主循环：循环从 {@link MjpegFrameHolder} 取最新 JPEG，按 MJPEG 分隔格式写入
     * PipedOutputStream。客户端断开时 write 抛 IOException，循环退出。
     */
    private void streamLoop(PipedOutputStream pos) {
        byte[] lastSent = null;
        try {
            // 先写 HTTP 头部的 boundary 前缀（multipart 起始）
            // NanoHTTPD 已经写了 status line + Content-Type，这里从第一帧 boundary 开始
            while (!Thread.currentThread().isInterrupted()) {
                byte[] jpg = frameHolder.get();
                if (jpg == null || jpg == lastSent) {
                    try { Thread.sleep(5); } catch (InterruptedException ie) { break; }
                    continue;
                }
                lastSent = jpg;

                ByteArrayOutputStream frame = new ByteArrayOutputStream(64 + jpg.length);
                frame.write(("--" + BOUNDARY + "\r\n").getBytes());
                frame.write("Content-Type: image/jpeg\r\n".getBytes());
                frame.write(("Content-Length: " + jpg.length + "\r\n\r\n").getBytes());
                frame.write(jpg);
                frame.write("\r\n".getBytes());

                pos.write(frame.toByteArray());
                pos.flush();
            }
        } catch (IOException e) {
            // 客户端断开，正常退出
            AppLog.d(TAG, "stream client disconnected: " + e.getMessage());
        } catch (Exception e) {
            AppLog.w(TAG, "stream loop error: " + e.getMessage());
        } finally {
            clientCount.decrementAndGet();
            streamThreads.remove(Thread.currentThread());
            try { pos.close(); } catch (IOException ignored) {}
        }
    }

    private Response snapshot() {
        byte[] jpg = frameHolder.get();
        if (jpg == null) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE,
                    "text/plain", "No frame yet");
        }
        Response res = newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                new ByteArrayInputStream(jpg), jpg.length);
        res.addHeader("Cache-Control", "no-store");
        return res;
    }

    /** 由上层停止时调用，唤醒所有阻塞的推流线程。 */
    public void shutdown() {
        for (Thread t : streamThreads) {
            if (t != null) t.interrupt();
        }
        streamThreads.clear();
        clientCount.set(0);
    }

    // ===== FrameStreamServer 接口实现 =====

    @Override
    public void startServer() throws IOException {
        super.start();
    }

    @Override
    public void stopServer() {
        shutdown();
        try { super.stop(); } catch (Exception ignored) {}
    }

    /** 获取本机 IPv4 地址，用于 UI 显示访问 URL。 */
    public static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets)) {
                if (!netint.isUp() || netint.isLoopback()) continue;
                Enumeration<InetAddress> addrs = netint.getInetAddresses();
                for (InetAddress addr : Collections.list(addrs)) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "getLocalIp failed: " + e.getMessage());
        }
        return "127.0.0.1";
    }

    @SuppressWarnings("unused")
    private static android.graphics.Bitmap decodeJpeg(byte[] jpg) {
        if (jpg == null) return null;
        return BitmapFactory.decodeStream(new ByteArrayInputStream(jpg));
    }
}
