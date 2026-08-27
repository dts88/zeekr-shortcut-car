package com.kooo.evcam.stream;

import com.kooo.evcam.AppLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UDP 分片流服务器。
 * <p>
 * 帧格式：JPEG 按 ≤1400B 分片，每片：
 * <pre>
 * struct "&lt;HHIII" (16 字节头)
 *   uint16 magic = 0x5354
 *   uint16 flags = 0
 *   uint32 total = JPEG 总长度
 *   uint32 index = 当前分片序号 (0..n-1)
 *   uint32 n     = 总分片数
 * [16B 头][≤1400B 数据]
 * </pre>
 * 和 Python 脚本的 UDP 协议一致。
 * <p>
 * 客户端需先发一个心跳包（任意内容）到服务器端口，服务器记录地址后开始推流。
 * 心跳超时 10 秒后移除客户端。
 */
public class UdpStreamServer implements FrameStreamServer {
    private static final String TAG = "UdpStreamServer";

    private static final int UDP_MAGIC = 0x5354;
    private static final int UDP_CHUNK_SIZE = 1400;
    private static final long HEARTBEAT_TIMEOUT_MS = 10_000;

    private final int port;
    private final MjpegFrameHolder frameHolder;
    private DatagramSocket socket;
    private Thread receiveThread;
    private Thread sendThread;
    private volatile boolean stopped = false;

    /** 客户端地址 → 最后心跳时间戳。 */
    private final ConcurrentHashMap<SocketAddress, Long> clients = new ConcurrentHashMap<>();
    private final AtomicInteger clientCount = new AtomicInteger(0);

    public UdpStreamServer(int port, MjpegFrameHolder frameHolder) {
        this.port = port;
        this.frameHolder = frameHolder;
    }

    @Override
    public void startServer() throws IOException {
        socket = new DatagramSocket(port);
        stopped = false;
        receiveThread = new Thread(this::receiveLoop, "Udp-Recv");
        receiveThread.setDaemon(true);
        receiveThread.start();
        sendThread = new Thread(this::sendLoop, "Udp-Send");
        sendThread.setDaemon(true);
        sendThread.start();
        AppLog.i(TAG, "UDP stream server started on port " + port);
    }

    /** 接收线程：等客户端心跳包，记录地址。 */
    private void receiveLoop() {
        byte[] buf = new byte[64];
        while (!stopped) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                SocketAddress addr = pkt.getSocketAddress();
                boolean isNew = !clients.containsKey(addr);
                clients.put(addr, System.currentTimeMillis());
                if (isNew) {
                    clientCount.set(clients.size());
                    AppLog.d(TAG, "UDP client joined: " + addr + ", total=" + clientCount.get());
                }
            } catch (IOException e) {
                if (!stopped) AppLog.w(TAG, "UDP receive error: " + e.getMessage());
            }
        }
    }

    /** 发送线程：从 frameHolder 取最新帧，分片发给所有活跃客户端。 */
    private void sendLoop() {
        byte[] lastSent = null;
        while (!stopped) {
            try {
                // 清理超时客户端
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<SocketAddress, Long>> it = clients.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<SocketAddress, Long> e = it.next();
                    if (now - e.getValue() > HEARTBEAT_TIMEOUT_MS) {
                        AppLog.d(TAG, "UDP client timeout: " + e.getKey());
                        it.remove();
                    }
                }
                clientCount.set(clients.size());

                if (clients.isEmpty()) {
                    Thread.sleep(50);
                    continue;
                }

                byte[] jpg = frameHolder.get();
                if (jpg == null || jpg == lastSent) {
                    Thread.sleep(5);
                    continue;
                }
                lastSent = jpg;
                sendFrameToAll(jpg);
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                AppLog.w(TAG, "UDP send loop error: " + e.getMessage());
            }
        }
    }

    /** 把一帧 JPEG 分片发给所有活跃客户端。 */
    private void sendFrameToAll(byte[] jpg) {
        int total = jpg.length;
        int n = (total + UDP_CHUNK_SIZE - 1) / UDP_CHUNK_SIZE;
        for (int i = 0; i < n; i++) {
            int off = i * UDP_CHUNK_SIZE;
            int len = (i == n - 1) ? (total - off) : UDP_CHUNK_SIZE;
            // 16B 头 + 数据
            byte[] packet = new byte[16 + len];
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) UDP_MAGIC)  // uint16 magic
                    .putShort((short) 0)          // uint16 flags
                    .putInt(total)                // uint32 total
                    .putInt(i)                    // uint32 index
                    .putInt(n);                   // uint32 n
            System.arraycopy(jpg, off, packet, 16, len);

            for (SocketAddress addr : clients.keySet()) {
                try {
                    socket.send(new DatagramPacket(packet, packet.length, addr));
                } catch (IOException e) {
                    AppLog.d(TAG, "UDP send fail to " + addr + ": " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void stopServer() {
        stopped = true;
        if (socket != null) socket.close();
        if (receiveThread != null) receiveThread.interrupt();
        if (sendThread != null) sendThread.interrupt();
        clients.clear();
        clientCount.set(0);
    }

    @Override
    public int getClientCount() {
        return clientCount.get();
    }
}
