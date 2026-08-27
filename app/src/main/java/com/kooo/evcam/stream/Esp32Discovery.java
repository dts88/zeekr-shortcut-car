package com.kooo.evcam.stream;

import com.kooo.evcam.AppLog;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * ESP32 自动发现：监听 8444 端口的 UDP 广播，解析 ESP32 上报的 JSON。
 * <p>
 * ESP32 广播格式：{@code {"k":"esp32hud","p":1234,"pr":"UDP","w":240,"h":320}}
 * <p>
 * 用法：{@code Esp32Discovery.discover(context, 4000, (host, port, proto, w, h) -> {...})}
 */
public final class Esp32Discovery {
    private static final String TAG = "Esp32Discovery";
    public static final int DISCOVERY_PORT = 8444;

    public interface DiscoveryCallback {
        /** 发现到 ESP32 设备。 */
        void onFound(String host, int port, String proto, int width, int height);
        /** 扫描超时，未发现任何设备。 */
        void onTimeout();
    }

    private Esp32Discovery() {}

    /**
     * 异步扫描 ESP32 设备（阻塞在线程里，回调在调用线程通过 Handler 投递）。
     * @param timeoutMs 超时毫秒
     * @param callback  回调（在后台线程触发）
     */
    public static void discover(int timeoutMs, DiscoveryCallback callback) {
        Thread t = new Thread(() -> {
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket(null);
                sock.setReuseAddress(true);
                sock.bind(new InetSocketAddress(DISCOVERY_PORT));
                byte[] buf = new byte[256];
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < deadline) {
                    int remain = (int) Math.max(1, deadline - System.currentTimeMillis());
                    sock.setSoTimeout(Math.min(remain, 500));
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(pkt);
                    } catch (SocketTimeoutException ignored) {
                        continue;
                    }
                    String json = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    JSONObject msg;
                    try {
                        msg = new JSONObject(json);
                    } catch (Exception parseError) {
                        AppLog.w(TAG, "ignore non-json discovery packet: " + json);
                        continue;
                    }
                    if (!"esp32hud".equals(msg.optString("k"))) {
                        AppLog.w(TAG, "unknown discovery message: " + json);
                        continue;
                    }
                    String host = pkt.getAddress().getHostAddress();
                    int port = msg.optInt("p", pkt.getPort());
                    String proto = msg.optString("pr", "UDP");
                    int w = msg.optInt("w", 240);
                    int h = msg.optInt("h", 320);
                    AppLog.i(TAG, "found ESP32: " + host + ":" + port + " " + proto + " " + w + "x" + h);
                    callback.onFound(host, port, proto, w, h);
                    return;
                }
                callback.onTimeout();
            } catch (Exception e) {
                AppLog.d(TAG, "discovery timeout or error: " + e.getMessage());
                callback.onTimeout();
            } finally {
                if (sock != null) sock.close();
            }
        }, "Esp32Discovery");
        t.setDaemon(true);
        t.start();
    }
}
